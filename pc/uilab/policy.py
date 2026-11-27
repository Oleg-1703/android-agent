"""Deterministic action gate, independent of model instructions."""

import re

from .decide import validate


RISK = re.compile("send|pay|buy|purchase|delete|remove|transfer|call|"
                  "отправить|оплатить|купить|удалить|перевести|позвонить|стереть", re.I)
LAUNCHERS = {"com.android.launcher3", "com.google.android.apps.nexuslauncher"}


def risky(node):
    return RISK.search(" ".join((node.text, node.desc, node.resource))) is not None


def gate(action, screen, allow, launcher=None):
    allowed = set(allow) | LAUNCHERS | ({launcher} if launcher else set())
    if screen.package not in allowed:
        return "blocked_package"
    try:
        validate(action)
    except ValueError:
        return "invalid_model_output"
    kind = action["action"]
    if kind == "open_app" and action.get("package") not in allowed:
        return "blocked_package"
    index = action.get("index")
    if index is not None and (type(index) is not int or not 0 <= index < len(screen.nodes)):
        return "invalid_index"
    if kind == "enter":
        focused = [n for n in screen.nodes if "focused" in n.flags and "editable" in n.flags]
        if not focused:
            return "invalid_enter"
        if any(n.package not in allowed for n in focused):
            return "blocked_package"
        if any(n.password for n in focused):
            return "password_blocked"
        # Enter can trigger the form's main button, so any risky control on screen stops it.
        if any(risky(n) for n in screen.nodes
               if n in focused or {"clickable", "long-clickable"} & set(n.flags)):
            return "needs_confirm"
    if kind in {"tap", "long_press", "type"}:
        if index is None:
            return "invalid_index"
        node = screen.nodes[index]
        if node.package not in allowed:
            return "blocked_package"
        if kind in {"tap", "long_press"} and risky(node):
            return "needs_confirm"
        if kind == "type":
            if node.password:
                return "password_blocked"
            if "editable" not in node.flags or not isinstance(action.get("text"), str):
                return "invalid_type"
    return None
