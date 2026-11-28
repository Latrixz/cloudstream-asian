#!/usr/bin/env python3
"""Post a CloudStream Asian update to the Telegram channel when sources are added, updated or removed.

Usage: telegram-notify.py <old plugins.json> <new plugins.json>
Env: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT (e.g. @csasian), COMMIT_MESSAGE (optional, for notes),
     TELEGRAM_NOTICE_CHAT (optional, e.g. the old @csasian_news channel): gets a short
     "there's an update, we've moved" notice linking to the full post in TELEGRAM_CHAT.
Posts nothing when no source changed (README or CI-only commits stay quiet).
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


# Friendly names as shown in CloudStream (plugins.json only has the module names).
NAMES = {
    "Olevod": "欧乐影院 Olevod", "Kubb": "酷播 Kubb", "Uvod": "优视频 UVOD", "Mvffm": "Movieffm", "Len": "冷映 Len",
    "Huavod": "华视影院 Huavod", "Ddys": "低端影视 Ddys", "Duboku": "独播库 Duboku", "Xiaobao": "小宝影院 Xiaobao",
    "Kissasian": "Kissasian", "Crackle": "Crackle", "Flixvision": "FlixVision", "Framextv": "FrameXTV",
    "Rivestream": "Rivestream", "Rivo": "Rivo", "Movy": "Movy",
    "Huarw": "华人影院 Huarw", "Nbyy": "泥巴影院 Nbyy", "Haotv": "华视界 Haotv", "Suikan": "随便看 Suikan", "Aigua": "爱瓜TV Aigua",
    "OneTouchTV": "OneTouchTV Asia", "Kisskh": "Kisskh Asia", "Donghuastream": "Donghuastream CS",
    "AniKoto": "AniKoto CS", "AnimeSuge": "AnimeSuge CS", "FourKHDHub": "4KHDHub CS",
    "Trjvod": "唐人街 Trjvod", "Csxyy": "CN影院 Csxyy", "Qwfilm": "七味网 Qwfilm", "Kpkuang": "看片狂人 Kpkuang", "Nangua": "南瓜影视 Nangua", "Ncat": "网飞猫 Ncat", "Kkys": "可可影视 Kkys", "Netflixgc": "奈飞工厂 Netflixgc", "Kankanya": "看看鸭 Kankanya", "Gqc": "共青春影院 Gqc", "Darkvod": "黑夜影院 Darkvod", "Mujizy": "母鸡资源 Mujizy", "Cilixiong": "磁力熊 Cilixiong",
    "Movies321": "321Movies CS", "Cinejoy": "Cinejoy CS", "Dm233": "233动漫 Dm233",
    "Zxcstream": "ZXCStream CS", "Reelfren": "ReelFren Shorts", "Chartdrama": "ChartDrama Shorts", "Dramatip": "DramaTip Shorts", "Dramafren": "DramaFren",
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

# Screenshots: screenshots/<internalName>/v<version>*.png|jpg in this commit are posted as an album.
shots_dir = os.environ.get("SCREENSHOTS_DIR", "")
sha = os.environ.get("GITHUB_SHA", "code")
photos = []
for p in added + [n for _, n in updated]:
    folder = os.path.join(shots_dir, p["internalName"]) if shots_dir else ""
    if not folder or not os.path.isdir(folder):
        continue
    prefix = f"v{p.get('version')}"
    for f in sorted(os.listdir(folder)):
        if f.startswith(prefix) and f.lower().endswith((".png", ".jpg", ".jpeg")):
            photos.append((label(p), f"https://raw.githubusercontent.com/Latrixz/cloudstream-asian/{sha}/screenshots/{p['internalName']}/{f}"))
photos = photos[:10]  # Telegram albums hold at most 10 items

token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT", "@csasian")
notice_chat = os.environ.get("TELEGRAM_NOTICE_CHAT", "")


def notice(post_url):
    counts = []
    if added:
        counts.append((f"🆕 {len(added)} new", f"新增 {len(added)} 个"))
    if updated:
        counts.append((f"⬆️ {len(updated)} updated", f"更新 {len(updated)} 个"))
    if removed:
        counts.append((f"🗑 {len(removed)} removed", f"移除 {len(removed)} 个"))
    group = "t.me/" + chat.lstrip("@")
    return "\n".join([
        f"🔄 CloudStream Asian has a new update: {', '.join(en for en, _ in counts)} source(s).",
        f"We've moved! Updates are now only posted in our group: {group}",
        "Tap below to see what changed and join the chat.",
        "",
        f"🔄 CloudStream Asian 有新更新：{'，'.join(zh for _, zh in counts)}片源。",
        f"我们已搬家！更新现在只发布在群组：{group}",
        "点下方按钮查看更新内容并加入群聊。",
    ]), {"inline_keyboard": [[{"text": "💬 See the update | 查看更新", "url": post_url}]]}


if not token:
    print("TELEGRAM_BOT_TOKEN not set; message would be:\n" + text)
    if notice_chat:
        print(f"\nnotice for {notice_chat}:\n" + notice(f"https://t.me/{chat.lstrip('@')}")[0])
    for name, url in photos:
        print("photo:", name, url)
    sys.exit(0)


def call(method, params):
    data = urllib.parse.urlencode(params).encode()
    try:
        with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/{method}", data=data, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.load(e)


r = call("sendMessage", {"chat_id": chat, "text": text, "disable_web_page_preview": "true"})
print("Telegram:", r.get("ok"), r.get("description", ""))
post_url = f"https://t.me/{chat.lstrip('@')}"
if r.get("ok"):
    post_url += f"/{r['result']['message_id']}"
if photos:
    media = [{"type": "photo", "media": url, **({"caption": f"📸 {name}"} if i == 0 or name != photos[i - 1][0] else {})}
             for i, (name, url) in enumerate(photos)]
    r = call("sendMediaGroup", {"chat_id": chat, "media": json.dumps(media)})
    print("Screenshots:", r.get("ok"), r.get("description", ""))

if notice_chat:
    body, markup = notice(post_url)
    r = call("sendMessage", {"chat_id": notice_chat, "text": body, "disable_web_page_preview": "true",
                             "reply_markup": json.dumps(markup)})
    print("Notice:", notice_chat, r.get("ok"), r.get("description", ""))
