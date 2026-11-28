#!/usr/bin/env python3
"""Turn the source watcher's results into a daily report.

Usage: watch-report.py <results dir (build/watch)> <state dir (checkout of the "watch" branch)>
Writes into the state dir:
  status.json   latest result per source
  history.csv   one line per source per day (date,source,status) -> uptime %
  README.md     the status table (readable on GitHub)
Prints the Markdown report (for the Actions job summary). With TELEGRAM_BOT_TOKEN and
TELEGRAM_WATCH_CHAT set, sends a short Telegram message: what broke, what recovered, the totals.
"""
import csv
import datetime
import glob
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

def friendly_names():
    """The NAMES table from telegram-notify.py (read, not imported: that script runs on import)."""
    import ast
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "telegram-notify.py")) as f:
            for node in ast.parse(f.read()).body:
                if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == "NAMES":
                    return ast.literal_eval(node.value)
    except Exception:
        pass
    return {}


NAMES = friendly_names()

ICON = {"playable": "✅", "unverified": "🟡", "up": "⚠️", "down": "❌"}
MEANING = {
    "playable": "plays",
    "unverified": "site up, needs the app to verify (Cloudflare / WebView)",
    "up": "site up, but no video played",
    "down": "site or home page not working",
}
GOOD = {"playable", "unverified"}

results_dir, state_dir = sys.argv[1], sys.argv[2]
os.makedirs(state_dir, exist_ok=True)
today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")

results = {}
for path in sorted(glob.glob(os.path.join(results_dir, "*.json"))):
    with open(path) as f:
        r = json.load(f)
    results[r["module"]] = r

previous = {}
try:
    with open(os.path.join(state_dir, "status.json")) as f:
        previous = {k: v["status"] for k, v in json.load(f).items()}
except Exception:
    pass

# History: replace today's rows (a manual re-run the same day overwrites them).
history_path = os.path.join(state_dir, "history.csv")
rows = []
if os.path.exists(history_path):
    with open(history_path) as f:
        rows = [r for r in csv.reader(f) if r and r[0] != today and r[0] != "date"]
rows += [[today, m, r["status"]] for m, r in results.items()]
with open(history_path, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["date", "source", "status"])
    w.writerows(sorted(rows))


def uptime(module, days):
    since = (datetime.date.fromisoformat(today) - datetime.timedelta(days=days - 1)).isoformat()
    mine = [r[2] for r in rows if r[1] == module and r[0] >= since]
    return f"{round(100 * sum(s in GOOD for s in mine) / len(mine))}%" if mine else "–"


def label(m):
    return NAMES.get(m, m)


with open(os.path.join(state_dir, "status.json"), "w") as f:
    json.dump({m: {**r, "date": today} for m, r in results.items()}, f, ensure_ascii=False, indent=1)

order = {"down": 0, "up": 1, "unverified": 2, "playable": 3}
counts = {s: sum(r["status"] == s for r in results.values()) for s in order}
broke = [m for m, r in results.items() if r["status"] not in GOOD and previous.get(m, "playable") in GOOD]
fixed = [m for m, r in results.items() if r["status"] in GOOD and previous.get(m) and previous[m] not in GOOD]
still = [m for m, r in results.items() if r["status"] not in GOOD and m not in broke]

lines = [
    f"# Source status, {today}",
    "",
    f"✅ {counts['playable']} play · 🟡 {counts['unverified']} unverified · ⚠️ {counts['up']} no video · ❌ {counts['down']} down",
    "",
    "| | Source | Status | 7 days | 30 days | Details |",
    "|---|---|---|---|---|---|",
]
for m, r in sorted(results.items(), key=lambda kv: (order.get(kv[1]["status"], 9), kv[0])):
    detail = r.get("link") or r.get("detail") or ""
    if r["status"] == "playable" and r.get("title"):
        detail = f"{r['title']} via {detail}"
    detail = detail.replace("|", "/")[:160]
    lines.append(f"| {ICON.get(r['status'], '?')} | {label(m)} | {MEANING.get(r['status'], r['status'])} | "
                 f"{uptime(m, 7)} | {uptime(m, 30)} | {detail} |")
lines += ["", "Checked daily by `.github/workflows/watch.yml`: home page → open a title → get links → fetch the stream."]
report = "\n".join(lines)
with open(os.path.join(state_dir, "README.md"), "w") as f:
    f.write(report + "\n")
print(report)

token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_WATCH_CHAT")
if token and chat:
    msg = [f"🩺 Source check {today}",
           f"✅ {counts['playable']} play · 🟡 {counts['unverified']} unverified · ⚠️ {counts['up']} no video · ❌ {counts['down']} down"]

    def why(m):
        r = results[m]
        return f"{ICON[r['status']]} {label(m)}: {(r.get('detail') or MEANING[r['status']])[:120]}"

    if broke:
        msg += ["", "Newly broken:"] + [why(m) for m in broke]
    if fixed:
        msg += ["", "Working again:"] + [f"✅ {label(m)}" for m in fixed]
    if still:
        msg += ["", "Still broken: " + ", ".join(label(m) for m in still)]
    if os.environ.get("REPORT_URL"):
        msg += ["", os.environ["REPORT_URL"]]
    body = urllib.parse.urlencode({"chat_id": chat, "text": "\n".join(msg), "disable_web_page_preview": "true"}).encode()
    try:
        with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/sendMessage", body, timeout=30) as res:
            print("Telegram:", json.load(res).get("ok"), file=sys.stderr)
    except urllib.error.HTTPError as e:
        # e.g. "chat not found": that person hasn't pressed Start in a private chat with the bot yet.
        print("Telegram failed:", e, e.read().decode(errors="replace"), file=sys.stderr)
    except Exception as e:
        print("Telegram failed:", e, file=sys.stderr)
