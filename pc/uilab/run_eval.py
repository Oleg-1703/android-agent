"""CLI entry point. Dry-run never executes ADB or Claude."""

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import time

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    __package__ = "uilab"

from .decide import CodexDecider, Decider
from .device import Device
from .loop import Budget, run_task


def load_tasks(path):
    tasks = json.loads(path.read_text(encoding="utf-8"))
    ids = set()
    for task in tasks:
        if task["id"] in ids or not task["allow"] or not isinstance(task["goal"], str):
            raise ValueError("invalid_task")
        ids.add(task["id"])
        if not all(isinstance(cmd, str) for cmd in task["setup"]):
            raise ValueError("invalid_setup")
        re.compile(task["check"]["regex"])
    return tasks


def check_task(device, task, status, answer=""):
    check = task["check"]
    try:
        output = device.shell(check["command"])
        if re.search(r"(?i)permission denial|securityexception|error:|unknown uri|exception occurred", output):
            return False, "checker_failed"
        matched = re.search(check["regex"], output) is not None
        if check.get("status"):
            matched = matched and status == check["status"]
        if check.get("answer_from_output"):
            expected = output.strip()
            # A sentence-ending period is fine; "111" or "11.1" is another version.
            matched = matched and bool(expected) and re.search(
                r"(?<![\w.])" + re.escape(expected) + r"(?!\w|\.\w)", answer) is not None
        return matched, None
    except (OSError, RuntimeError, TimeoutError, subprocess.TimeoutExpired):
        return False, "checker_failed"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--provider", choices=("claude", "codex"), default="claude")
    parser.add_argument("--model", help="claude: sonnet (default) or haiku; codex: a model from its catalog")
    parser.add_argument("--codex", help="path to the codex CLI")
    parser.add_argument("--debug", action="store_true",
                        help="save screen text and full model actions under results/debug/ (disposable emulator only)")
    parser.add_argument("--tasks", type=Path, default=Path(__file__).with_name("tasks.json"),
                        help="task definition JSON file (default: tasks.json)")
    parser.add_argument("--task")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--max-calls", type=int, default=150)
    args = parser.parse_args(argv)
    try:
        device = Device(args.adb, args.serial)
        budget = Budget(args.max_calls)
        tasks = load_tasks(args.tasks)
        if args.provider == "claude" and args.model not in (None, "sonnet", "haiku"):
            raise ValueError("unsupported_model")
    except ValueError as error:
        parser.error(str(error))
    if args.task:
        tasks = [task for task in tasks if task["id"] == args.task]
        if not tasks:
            parser.error("unknown task")
    if args.dry_run:
        for task in tasks:
            print(f'{task["id"]}: allow={",".join(task["allow"])} setup={len(task["setup"])} checker=ok')
        print(f"dry-run: {len(tasks)} tasks, max-calls={budget.maximum}, no ADB/model calls")
        return 0
    chosen = {"model": args.model} if args.model else {}
    try:
        decider = (CodexDecider(cli=args.codex, **chosen) if args.provider == "codex" else Decider(**chosen))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.error(f"{args.provider}: {error}")
    if not device.dumper.is_file():
        print("warning: dumper/uidump.jar is missing; screens that never become idle will be unreadable")
    directory = Path(__file__).with_name("results")
    directory.mkdir(exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    debug = None
    if args.debug:
        (directory / "debug").mkdir(exist_ok=True)
        debug = (directory / "debug" / (stamp + ".jsonl")).open("x", encoding="utf-8")
    with (directory / (stamp + ".jsonl")).open("x", encoding="utf-8") as stream:
        def write(target, row):
            target.write(json.dumps(row, ensure_ascii=False) + "\n")
            target.flush()

        def emit(row):
            write(stream, row)

        trace = (lambda row: write(debug, row)) if debug else None
        for task in tasks:
            started = time.monotonic()
            answer = ""
            if budget.calls >= budget.maximum:
                row = {"type": "task", "task": task["id"], "status": "call_limit",
                       "success": False, "steps": 0, "seconds": 0}
            else:
                try:
                    device.deadline = started + 600
                    device.wake()
                    fallbacks = device.fallback_dumps
                    launcher = device.launcher()
                    for command in task["setup"]:
                        output = device.shell(command)
                        if re.search(r"(?i)error|exception|failed|failure", output):
                            raise RuntimeError("setup_failed")
                    device.home()
                    # Seeding takes minutes for long lists; the task's time limit starts after it.
                    started = time.monotonic()
                    device.deadline = started + 300
                    row, answer = run_task(device, decider, task, budget, emit,
                                           deadline=started + 300, launcher=launcher, trace=trace)
                    row["fallback_dumps"] = device.fallback_dumps - fallbacks
                except (OSError, RuntimeError, ValueError, TimeoutError, subprocess.TimeoutExpired):
                    row = {"type": "task", "task": task["id"], "status": "setup_failed",
                           "success": False, "steps": 0, "seconds": 0}
                finally:
                    device.deadline = None
                final = None
                if trace:
                    # Before the checker, which may bring another screen to the front.
                    try:
                        final = device.dump().compact()
                    except (OSError, RuntimeError, ValueError, TimeoutError, subprocess.TimeoutExpired):
                        final = "unavailable"
                row["success"], check_error = check_task(device, task, row["status"], answer)
                if trace:
                    trace({"task": task["id"], "final_observation": final, "status": row["status"],
                           "answer": answer, "success": row["success"]})
                if check_error:
                    row["check_error"] = check_error
                row["seconds"] = round(time.monotonic() - started, 3)
            row.update(provider=decider.provider, model=decider.model)
            emit(row)
            print(f'{row["task"]}: {row["status"]}, success={row["success"]}, steps={row["steps"]}, '
                  f'seconds={row["seconds"]}', flush=True)
    if debug:
        debug.close()
    print(f"results: {directory / (stamp + '.jsonl')}; calls={budget.calls}/{budget.maximum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
