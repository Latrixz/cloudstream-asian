#!/usr/bin/env python3
"""Keep the pinned installation guide in the @csasian Telegram group up to date.

Usage:
  telegram-guide.py post <plugins.json>   post the guide as a new message and pin it (prints the message id)
  telegram-guide.py edit <plugins.json>   edit the existing pinned guide (id in TELEGRAM_GUIDE_MESSAGE_ID)
  telegram-guide.py show <plugins.json>   just print the guide text

Env: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT (default @csasian), TELEGRAM_GUIDE_MESSAGE_ID (for edit).
The bot can only edit messages it posted itself, and pinning needs the "Pin messages" admin right.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "https://github.com/Latrixz/cloudstream-asian"
REPO_JSON = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/main/repo.json"
SUPPORT = "https://buymeacoffee.com/latrixz"

# Friendly names as shown in CloudStream, and which section each source belongs in.
NAMES = {
    "Olevod": "Olevod", "Kubb": "Kubb", "Mvffm": "Movieffm", "Len": "Len", "Huavod": "Huavod",
    "Ddys": "Ddys", "Duboku": "Duboku", "Xiaobao": "Xiaobao", "Kissasian": "Kissasian",
    "Crackle": "Crackle", "Flixvision": "FlixVision", "Framextv": "FrameXTV",
    "Rivestream": "Rivestream", "Rivo": "Rivo", "Movy": "Movy",
    "Huarw": "华人影院 Huarw", "Nbyy": "泥巴影院 Nbyy", "Haotv": "华视界 Haotv", "Suikan": "随便看 Suikan", "Aigua": "爱瓜TV Aigua",
    "OneTouchTV": "OneTouchTV Asia", "Kisskh": "Kisskh Asia", "Donghuastream": "Donghuastream CS",
    "AniKoto": "AniKoto CS", "AnimeSuge": "AnimeSuge CS", "FourKHDHub": "4KHDHub CS",
    "Trjvod": "唐人街 Trjvod", "Csxyy": "CN影院 Csxyy", "Qwfilm": "七味网 Qwfilm", "Kpkuang": "看片狂人 Kpkuang", "Nangua": "南瓜影视 Nangua", "Ncat": "网飞猫 Ncat", "Kkys": "可可影视 Kkys", "Netflixgc": "奈飞工厂 Netflixgc", "Kankanya": "看看鸭 Kankanya", "Gqc": "共青春影院 Gqc", "Darkvod": "黑夜影院 Darkvod", "Mujizy": "母鸡资源 Mujizy", "Cilixiong": "磁力熊 Cilixiong",
    "Movies321": "321Movies CS", "Cinejoy": "Cinejoy CS", "Dm233": "233动漫 Dm233",
    "LiveTv": "直播 Live TV", "AsianHome": "CS Asian 首页",
}
ASIAN_DRAMA_EN = {"Kissasian", "OneTouchTV", "Kisskh"}
ANIME = {"AniKoto", "AnimeSuge", "Donghuastream", "Dm233"}
SPECIAL = {"LiveTv", "AsianHome"}


def sections(plugins):
    zh, asian, en, other, anime = [], [], [], [], []
    for p in sorted(plugins, key=lambda p: p["internalName"].lower()):
        key = p["internalName"]
        if key in SPECIAL:
            continue
        name = NAMES.get(key, p.get("name") or key)
        if key in ANIME:
            anime.append(name)
        elif key in ASIAN_DRAMA_EN:
            asian.append(name)
        elif (p.get("language") or "").startswith("zh"):
            zh.append(name)
        elif (p.get("language") or "").startswith("en"):
            en.append(name)
        else:
            other.append(name)
    keys = {p["internalName"] for p in plugins}
    return zh, asian, en, other, anime, "LiveTv" in keys, "AsianHome" in keys


def guide(plugins):
    zh, asian, en, other, anime, live, home = sections(plugins)
    src = []
    if zh:
        src.append("🇨🇳 " + " · ".join(zh))
    if asian:
        src.append("🌏 " + " · ".join(asian) + " (Asian dramas, English subs)")
    if en:
        src.append("🇬🇧 " + " · ".join(en))
    if anime:
        src.append("🎌 " + " · ".join(anime) + " (anime & 国漫 donghua)")
    if other:
        src.append("🌐 " + " · ".join(other))
    if live:
        src.append("📺 直播 Live TV: free live channels (Asia, US, UK, Australia, India…)")
    if home:
        src.append("🏠 CS Asian 首页: build your own home screen from your sources")
    return "\n".join([
        "📌 CloudStream Asian: Installation Guide | 安装教程",
        REPO,
        "",
        "1️⃣ Install an app | 安装应用",
        "Use either one:",
        "• CloudStream (Android phone / TV): github.com/recloudstream/cloudstream/releases",
        "• SkyStream (Android, iPhone, Windows, Mac, Linux): github.com/akashdh11/skystream/releases",
        "Step-by-step help for your device: t.me/CSAsianBot?start=install",
        "任选一个安装：CloudStream（安卓手机/电视）或 SkyStream（安卓、iPhone、Windows、Mac、Linux）。",
        "按设备查看安装步骤：t.me/CSAsianBot?start=install",
        "",
        "2️⃣ Add our repository | 添加片源仓库",
        "Settings → Extensions → Add repository",
        "Type the short code: csasian",
        "设置 → 扩展 → 添加仓库 → 输入 csasian",
        "(If the code doesn't work, paste: " + REPO_JSON + ")",
        "",
        f"3️⃣ Install the sources | 安装片源 ({len(plugins)})",
        'Open "CloudStream Asian" → tap ⬇️ Download on the ones you want:',
        *src,
        '点开 "CloudStream Asian"，下载你想要的片源。',
        "",
        "4️⃣ Start watching | 开始观看",
        "Home screen → tap the source name at the top → pick a source.",
        "首页 → 点上方的片源名称 → 选择片源即可。",
        "",
        "🔄 Updates are automatic. No need to reinstall.",
        "更新会自动推送，无需重装。",
        "",
        "☕ Support the project | 支持我们",
        SUPPORT,
        "",
        "❓ A source broken or want a new site? Ask in this chat or send /help.",
        "片源失效或想要新站点？在群里提问或发送 /help。",
    ])


def api(method, **params):
    token = os.environ["TELEGRAM_BOT_TOKEN"]
    data = urllib.parse.urlencode({k: (json.dumps(v) if isinstance(v, (dict, list, bool)) else v) for k, v in params.items()}).encode()
    try:
        with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/{method}", data=data, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.load(e)


def main():
    mode, path = sys.argv[1], sys.argv[2]
    with open(path) as f:
        plugins = json.load(f)
    text = guide(plugins)
    chat = os.environ.get("TELEGRAM_CHAT", "@csasian")
    if mode == "show":
        print(text)
        return
    if mode == "post":
        r = api("sendMessage", chat_id=chat, text=text, link_preview_options={"url": REPO, "prefer_large_media": True})
        print("send:", r.get("ok"), r.get("description", ""))
        if r.get("ok"):
            mid = r["result"]["message_id"]
            print("GUIDE_MESSAGE_ID", mid)
            p = api("pinChatMessage", chat_id=chat, message_id=mid, disable_notification=True)
            print("pin:", p.get("ok"), p.get("description", ""))
        return
    if mode == "edit":
        mid = os.environ.get("TELEGRAM_GUIDE_MESSAGE_ID")
        if not mid:
            print("No TELEGRAM_GUIDE_MESSAGE_ID; skipping guide update.")
            return
        r = api("editMessageText", chat_id=chat, message_id=int(mid), text=text,
                link_preview_options={"url": REPO, "prefer_large_media": True})
        desc = r.get("description", "")
        if r.get("ok") or "not modified" in desc:
            print("guide:", "updated" if r.get("ok") else "already current")
        else:
            print("guide edit failed:", desc)


if __name__ == "__main__":
    main()
