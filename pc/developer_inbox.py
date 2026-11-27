"""Store the owner's original request and wake the configured developer."""
from __future__ import annotations

import base64
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import subprocess


def wsl_path(path) -> str:
    path = str(path)
    if re.match(r"^[A-Za-z]:[\\/]", path):
        return "/mnt/" + path[0].lower() + "/" + path[3:].replace("\\", "/")
    return path


def forward(cwd: Path, typed: str, voice: str, images: list, wake: list[str],
            runner=subprocess.run, now=None) -> str:
    now = dt.datetime.now(dt.timezone.utc) if now is None else now.astimezone(dt.timezone.utc)
    stem = now.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(4)
    directory = Path(cwd).resolve() / "dev-inbox"
    path = directory / (stem + ".json")
    temporary = path.with_suffix(".tmp")
    files = []
    try:
        directory.mkdir(parents=True, exist_ok=True)
        for index, image in enumerate(images):
            suffix = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[image["media_type"]]
            target = directory / f"{stem}-{index + 1}{suffix}"
            files.append(target.name)
            temporary.write_bytes(base64.b64decode(image["data"], validate=True))
            os.replace(temporary, target)
        content = {"ts": now.isoformat(), "typed": typed, "voice": voice, "images": files}
        temporary.write_text(json.dumps(content, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, path)
    except (OSError, ValueError, KeyError, TypeError):
        return "failed"
    when = (now + dt.timedelta(minutes=2)).strftime("%Y-%m-%d %H:%M")
    message = "Request from Oleg in the bot chat: read " + wsl_path(path)
    try:
        result = runner(wake + [when, message], shell=False, timeout=30, capture_output=True,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return "sent" if result.returncode == 0 else "saved"
    except (subprocess.TimeoutExpired, OSError):
        return "saved"
