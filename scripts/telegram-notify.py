#!/usr/bin/env python3
"""Post a CloudStream Asian update to the Telegram channel when sources are added, updated or removed.

Usage: telegram-notify.py <old plugins.json> <new plugins.json>
Env: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT (e.g. @csasian), COMMIT_MESSAGE (optional, for notes).
Posts nothing when no source changed (README or CI-only commits stay quiet).
"""
import json
import os
import sys
import urllib.parse
import urllib.request


# Friendly names as shown in CloudStream (plugins.json only has the module names).
NAMES = {
    "Olevod": "欧乐影院 Olevod", "Kubb": "酷播 Kubb", "Mvffm": "Movieffm", "Len": "冷映 Len",
    "Huavod": "华视影院 Huavod", "Ddys": "低端影视 Ddys", "Duboku": "独播库 Duboku", "Xiaobao": "小宝影院 Xiaobao",
    "Kissasian": "Kissasian", "Crackle": "Crackle", "Flixvision": "FlixVision", "Framextv": "FrameXTV",
    "LiveTv": "直播 Live TV", "AsianHome": "CS Asian 首页",
}


def label(p):
    return NAMES.get(p["internalName"], p.get("name") or p["internalName"])


def load(path):
    try:
        with open(path) as f:
            return {p["internalName"]: p for p in json.load(f)}
    except Exception:
        return {}


old, new = load(sys.argv[1]), load(sys.argv[2])
if not new:
    sys.exit(0)

added = [p for k, p in new.items() if k not in old]
updated = [(old[k], p) for k, p in new.items() if k in old and p.get("version") != old[k].get("version")]
removed = [p for k, p in old.items() if k not in new]
if not (added or updated or removed):
    print("No source changes; nothing to announce.")
    sys.exit(0)

lines = ["🔄 CloudStream Asian update"]
for p in added:
    lines.append(f"🆕 New: {label(p)} (v{p.get('version')})")
for o, p in updated:
    lines.append(f"⬆️ Updated: {label(p)} v{o.get('version')} → v{p.get('version')}")
for p in removed:
    lines.append(f"🗑 Removed: {label(p)}")

notes = []
for line in os.environ.get("COMMIT_MESSAGE", "").splitlines()[1:]:
    s = line.strip()
    if s.lower().startswith("co-authored-by") or not s:
        continue
    notes.append(s if s.startswith("-") else f"- {s}")
if notes:
    lines += ["", "📝 What changed:"] + notes[:12]

lines += ["", "CloudStream updates installed sources automatically. New sources: open the CloudStream Asian repo and tap Download.",
          "Repo: csasian · https://github.com/Latrixz/cloudstream-asian"]
text = "\n".join(lines)

token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT", "@csasian")
if not token:
    print("TELEGRAM_BOT_TOKEN not set; message would be:\n" + text)
    sys.exit(0)
data = urllib.parse.urlencode({"chat_id": chat, "text": text, "disable_web_page_preview": "true"}).encode()
with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/sendMessage", data=data, timeout=30) as r:
    print("Telegram:", json.load(r).get("ok"))
