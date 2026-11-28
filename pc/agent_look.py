"""Read-only phone observations. Never import an executor."""
import json
from pathlib import Path
import time
import uuid

import telemetry
from uilab.decide import Decider, validate as validate_action
from uilab.observe import Node, Screen, FLAGS, STATES

PACKAGE = "com.fatsecret.android"


def validate(body):
    if not isinstance(body, dict) or set(body) - {"task", "package", "size", "nodes", "truncated"}:
        raise ValueError("invalid_look")
    if not isinstance(body.get("task"), str) or not 1 <= len(body["task"].strip()) <= 2000:
        raise ValueError("invalid_look")
    if body.get("package") != PACKAGE or type(body.get("truncated", False)) is not bool:
        raise ValueError("invalid_look")
    size, nodes = body.get("size"), body.get("nodes")
    if not isinstance(size, list) or len(size) != 2 or any(type(n) is not int or not 1 <= n <= 20000 for n in size):
        raise ValueError("invalid_look")
    if not isinstance(nodes, list) or len(nodes) > 300 or len(json.dumps(body, ensure_ascii=False, separators=(",", ":"))) > 45000:
        raise ValueError("invalid_look")
    parsed = []
    for index, node in enumerate(nodes):
        if not isinstance(node, dict) or set(node) != {"index", "cls", "text", "desc", "id", "flags", "bounds"}:
            raise ValueError("invalid_look")
        if type(node["index"]) is not int or node["index"] != index:
            raise ValueError("invalid_look")
        for key, limit in (("cls", 80), ("text", 1000), ("desc", 1000), ("id", 120)):
            if not isinstance(node[key], str) or len(node[key]) > limit:
                raise ValueError("invalid_look")
        flags, bounds = node["flags"], node["bounds"]
        if not isinstance(flags, list) or len(flags) > 9 or any(not isinstance(f, str) or f not in (*FLAGS, *STATES, "disabled") for f in flags):
            raise ValueError("invalid_look")
        if not isinstance(bounds, list) or len(bounds) != 4 or any(type(n) is not int for n in bounds):
            raise ValueError("invalid_look")
        if not (0 <= bounds[0] < bounds[2] <= size[0] and 0 <= bounds[1] < bounds[3] <= size[1]):
            raise ValueError("invalid_look")
        parsed.append(Node(index, node["cls"], node["text"], node["desc"], node["id"], tuple(flags), tuple(bounds), False, PACKAGE))
    return Screen(PACKAGE, "", tuple(size), parsed, body.get("truncated", False))


def render(screen):
    # Use the lab's exact node representation; its old 200-line notice differs from this cap.
    plain = Screen(screen.package, screen.activity, screen.size, screen.nodes).compact()
    return plain + ("\n[truncated: phone observation limit reached]" if screen.truncated else "")


def words(action, screen):
    kind = action["action"]
    if kind == "open_app" and action.get("package") != PACKAGE:
        raise ValueError("invalid_model_output")
    if kind in {"tap", "long_press", "type"}:
        index = action.get("index")
        if type(index) is not int or not 0 <= index < len(screen.nodes):
            raise ValueError("invalid_model_output")
        node = screen.nodes[index]
        label = " ".join((node.text or node.desc or node.resource or f"элемент {index}").split())[:100]
        if kind == "type":
            if not isinstance(action.get("text"), str):
                raise ValueError("invalid_model_output")
            return f'ввести «{" ".join(action["text"].split())[:300]}» в «{label}»'
        return ("нажать" if kind == "tap" else "удерживать") + f" «{label}»"
    return {"enter": "подтвердить ввод", "scroll_down": "прокрутить вниз", "scroll_up": "прокрутить вверх",
            "back": "вернуться назад", "home": "открыть главный экран", "open_app": "открыть FatSecret",
            "wait": "подождать", "done": "готово", "give_up": "не могу"}[kind]


def look(body, workspace, cli="claude", decider=None):
    screen = validate(body)
    started = time.monotonic()
    ok = False
    try:
        prompt = json.dumps({"task": body["task"], "screen": render(screen), "previous_steps": []}, ensure_ascii=False)
        prompt += "\nПредложи один следующий шаг, ничего не выполняй. thought - одна короткая строка на русском. Разрешён только FatSecret."
        action, metrics, error = (decider or Decider(model="sonnet", cli=cli))(prompt, 60)
        try:
            answer = {"error": error} if error else {"action": validate_action(action), "model_ms": metrics["ms"],
                        "nodes": len(screen.nodes), "truncated": screen.truncated,
                        "step": words(action, screen), "reason": " ".join(action["thought"].split())}
        except ValueError:
            error = "invalid_model_output"
            answer = {"error": error, "action": action}
        folder = Path(workspace) / "agent-looks"
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"{time.time_ns()}-{uuid.uuid4().hex}.json"
        path.write_text(json.dumps({"request": body, "answer": answer}, ensure_ascii=False), encoding="utf-8")
        for old in sorted(folder.glob("*.json"), key=lambda p: p.name, reverse=True)[50:]:
            old.unlink()
        if error:
            raise RuntimeError(error)
        ok = True
        return answer
    finally:
        telemetry.emit("agent_look", ok=ok, ms=round((time.monotonic() - started) * 1000), n={"nodes": len(screen.nodes)})
