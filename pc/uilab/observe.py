"""Compact, ephemeral accessibility observations."""

from dataclasses import dataclass
import json
import re
import xml.etree.ElementTree as ET


FLAGS = ("clickable", "long-clickable", "scrollable", "editable", "checkable")
# State is shown for listed nodes but does not by itself make a node worth listing.
STATES = ("checked", "selected", "focused")
# The dump has no "editable" attribute. Subclasses report these framework class names
# (MultiAutoCompleteTextView and SearchAutoComplete included).
EDITABLE_CLASSES = ("EditText", "AutoCompleteTextView")
# Reserve one line each for the header and optional truncation notice.
MAX_NODES = 198


@dataclass
class Node:
    index: int
    cls: str
    text: str
    desc: str
    resource: str
    flags: tuple
    bounds: tuple
    password: bool
    package: str


@dataclass
class Screen:
    package: str
    activity: str
    size: tuple
    nodes: list
    truncated: bool = False
    unreadable: bool = False

    def compact(self):
        lines = [f"package={self.package} activity={self.activity} size={self.size[0]}x{self.size[1]}"]
        for node in self.nodes:
            quote = lambda value: json.dumps(value[:80], ensure_ascii=False)
            cls = " ".join(node.cls.split())[:80]
            lines.append(f'[{node.index}] {cls} {quote(node.text)} desc={quote(node.desc)} '
                         f'id={quote(node.resource)} {",".join(node.flags)} '
                         + ",".join(map(str, node.bounds)))
        if self.unreadable:
            lines.append("[screen content could not be read; only actions without an index are possible]")
        if self.truncated:
            lines.append("[truncated: observation limited to 200 lines]")
        return "\n".join(lines)


def parse(xml, package, activity, size):
    root = ET.fromstring(xml)
    nodes = []
    truncated = False
    for element in root.iter("node"):
        a = element.attrib
        if a.get("visible-to-user", "true") != "true":
            continue
        coords = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not coords:
            continue
        x1, y1, x2, y2 = map(int, coords.groups())
        bounds = (max(0, x1), max(0, y1), min(size[0], x2), min(size[1], y2))
        if bounds[0] >= bounds[2] or bounds[1] >= bounds[3]:
            continue
        flags = tuple(flag for flag in FLAGS if a.get(flag) == "true"
                      or (flag == "editable" and a.get("class", "").endswith(EDITABLE_CLASSES)))
        text, desc = a.get("text", ""), a.get("content-desc", "")
        if not (text or desc or flags):
            continue
        flags += tuple(state for state in STATES if a.get(state) == "true")
        if a.get("enabled") == "false":
            flags += ("disabled",)
        if len(nodes) == MAX_NODES:
            truncated = True
            break
        password = a.get("password") == "true"
        if password:
            text, desc = "<password>", ""
        # Keep full labels in memory for the risk gate, truncate only the prompt.
        nodes.append(Node(len(nodes), a.get("class", "").rsplit(".", 1)[-1],
                          text, desc, a.get("resource-id", "").rsplit("/", 1)[-1],
                          flags, bounds, password, a.get("package", package)))
    return Screen(package, activity, size, nodes, truncated)
