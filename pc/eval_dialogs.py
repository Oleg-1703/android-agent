"""Runs the recorded dialogs (pc/dialogs/*.json) against the REAL subscription Claude CLI and prints pass/fail per turn.

Local only, never in CI: every model turn costs subscription quota (about 10 dialogs x 2-3 turns).
  python pc/eval_dialogs.py --dry-run          scripted model, no quota: checks the dialogs, the PNG placeholders, the code paths
  python pc/eval_dialogs.py [--only NAME ...]  real model; web lookups are disabled, so estimates stay estimates
Image placeholders are generated PNGs showing the digits from the dialog's "images" map. All content is synthetic.
"""
import argparse
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).parent))
import dialog_runner  # noqa: E402


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="scripted model, no CLI call, no quota")
    parser.add_argument("--only", nargs="*", default=[], help="dialog names")
    parser.add_argument("--cli", default="", help="path of the claude CLI (default: PATH, then ~/.local/bin)")
    args = parser.parse_args()
    dialogs = [d for d in dialog_runner.load_dialogs() if not args.only or d["name"] in args.only]
    if not dialogs:
        parser.error("no dialogs selected")
    cli = args.cli or shutil.which("claude") or str(Path.home() / ".local/bin/claude.exe")
    if not args.dry_run and not Path(cli).exists() and not shutil.which(cli):
        parser.error("claude CLI not found; pass --cli")
    failed = total = 0
    for dialog in dialogs:
        print(f"{dialog['name']}: {dialog.get('about', '')}")
        for number, problems in dialog_runner.run_dialog(dialog, real=not args.dry_run, cli=cli):
            total += 1
            failed += bool(problems)
            print(f"  turn {number}: {'FAIL ' + '; '.join(problems) if problems else 'pass'}")
    print(f"{total - failed}/{total} turns passed" + (" (dry run, scripted model)" if args.dry_run else " (real model)"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
