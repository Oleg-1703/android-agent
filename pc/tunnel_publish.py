#!/usr/bin/env python3
"""Publish the current trycloudflare URL to the owner's Telegram chat as a pinned `AA-URL https://<x>.trycloudflare.com` message.

The phone app reads that pinned message when the PC does not answer, so the tunnel address never has to be typed.
The bot token and chat id are read at call time from the Windows Claude settings (the same source as the captain's `tg`
tool) and are never written to the repo or printed. The URL is not a secret; the pairing token still authorizes requests.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

import telemetry

URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")
MESSAGE_RE = re.compile(r"AA-URL (https://[a-z0-9-]+\.trycloudflare\.com)")
PREFIX = "AA-URL "
# Cloudflare prints this host in its banner text, not as the tunnel address.
IGNORED_HOSTS = {"https://api.trycloudflare.com"}


def settings_path() -> Path:
    return Path(os.environ.get("AA_TELEGRAM_SETTINGS") or Path.home() / ".claude" / "settings.json")


def latest_url(log_text: str) -> str | None:
    """The last tunnel URL printed in cloudflared's log (each tunnel run starts a fresh log)."""
    found = [m for m in URL_RE.findall(log_text) if m not in IGNORED_HOSTS]
    return found[-1] if found else None


def compose(url: str) -> str:
    if not MESSAGE_RE.fullmatch(PREFIX + url):
        raise ValueError("not a trycloudflare https URL")
    return PREFIX + url


def credentials(path: Path | None = None) -> tuple[str, str]:
    env = json.loads((path or settings_path()).read_text(encoding="utf-8"))["mcpServers"]["telegram"]["env"]
    return env["TELEGRAM_BOT_TOKEN"], str(env["TELEGRAM_CHAT_ID"])


def telegram_api(method: str, payload: dict, creds=credentials, timeout: int = 20) -> dict:
    token, _ = creds()
    request = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request, timeout=timeout))


def publish(url: str, state_file: Path, api=telegram_api, chat_id: str | None = None, dry_run: bool = False) -> str:
    """Returns 'unchanged', 'dry-run' or 'published'. Raises on a Telegram failure; the state file is written only after the pin succeeded."""
    text = compose(url)
    if state_file.exists() and state_file.read_text(encoding="utf-8").strip() == url:
        return "unchanged"
    if dry_run:
        print("would send:", text)
        return "dry-run"
    chat = chat_id or credentials()[1]
    sent = api("sendMessage", {"chat_id": chat, "text": text, "disable_notification": True, "disable_web_page_preview": True})
    if not sent.get("ok"):
        raise RuntimeError("sendMessage rejected")
    api("pinChatMessage", {"chat_id": chat, "message_id": sent["result"]["message_id"], "disable_notification": True})
    state_file.write_text(url, encoding="utf-8")
    return "published"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", required=True, help="brain state folder: tunnel.stderr.log is read, published-url.txt is kept here")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    state = Path(args.state_dir)
    log = state / "tunnel.stderr.log"
    url = latest_url(log.read_text(encoding="utf-8", errors="replace")) if log.exists() else None
    if not url:
        print("no tunnel URL yet")
        return 2
    tele = telemetry.Telemetry(telemetry.events_path(state / "empty-workspace"))
    try:
        outcome = publish(url, state / "published-url.txt", dry_run=args.dry_run)
        print(outcome)
        if outcome == "published":
            tele.emit("tunnel_url_change", ok=True)
            tele.emit("publish", ok=True)
        elif outcome == "unchanged":
            tele.emit("watchdog_tick", ok=True)  # every 5 minutes: its gaps show when the PC was off
    except Exception as error:  # Never echo request objects: they carry the token in their URL.
        print("publish failed:", type(error).__name__, getattr(error, "code", ""))
        tele.emit("publish", ok=False, err_class=type(error).__name__, flags={"http": getattr(error, "code", 0) or 0})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
