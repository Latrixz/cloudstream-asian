#!/usr/bin/env python3
"""Build the Live TV logo set on the repo's orphan `livetv-logos` branch.

Every channel's logo is fitted ("contained") into a 320x200 (16:10) tile so CloudStream's
wide cards show the whole logo instead of a zoomed-in crop. The background is chosen per
logo: opaque logos are padded with their own edge colour; transparent logos get a dark tile
if the logo is light, or a light tile if the logo is dark. Output: <id>.jpg + index.json.

It also avoids image hosts that TVs fail to load (ibb.co, Wikimedia, Fandom, dead links),
because the TV only ever loads these copies from GitHub.

Run: python3 scripts/livetv-mirror-logos.py <out_dir>   (needs Pillow)
"""
import concurrent.futures
import io
import json
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image

API = "https://iptv-org.github.io/api/"
COUNTRIES = set("CN HK TW SG MY JP KR ID VN TH PH KH MM LA US UK CA AU NZ IN".split())
OUT = sys.argv[1]
TILE_W, TILE_H = 320, 200
BOX_W, BOX_H = int(TILE_W * 0.84), int(TILE_H * 0.74)
DARK, LIGHT = (28, 31, 38), (238, 238, 242)
UA = {"User-Agent": "Mozilla/5.0 (cloudstream-asian logo mirror)"}
os.makedirs(OUT, exist_ok=True)


def get(url, timeout=30):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


channels = json.loads(get(API + "channels.json"))
streams = json.loads(get(API + "streams.json"))
logos = json.loads(get(API + "logos.json"))
blocked = {b.get("channel") for b in json.loads(get(API + "blocklist.json"))}
playable = {s["channel"] for s in streams if s.get("channel") and s.get("url", "").startswith("http") and not s.get("label")}
wanted = {c["id"] for c in channels if c.get("country") in COUNTRIES and c["id"] in playable
          and c["id"] not in blocked and not c.get("closed") and not c.get("is_nsfw")}
# News rows also include English news from any country.
langs = {}
for f in json.loads(get(API + "feeds.json")):
    langs.setdefault(f.get("channel"), set()).update(f.get("languages") or [])
for c in channels:
    if c["id"] in playable and c["id"] not in blocked and not c.get("closed") and not c.get("is_nsfw") \
            and "news" in (c.get("categories") or []) and "eng" in langs.get(c["id"], set()):
        wanted.add(c["id"])


def host(u):
    return urllib.parse.urlparse(u).netloc.lower()


def rank(l):
    h = host(l["url"])
    s = 16 if (h == "i.imgur.com" or h.endswith("githubusercontent.com")) else 2 if h.endswith("wikimedia.org") \
        else -12 if any(x in h for x in ("wikia", "fandom", "tvtropes")) \
        else -16 if any(x in h for x in ("ibb.co", "postimg", "imgbb")) else 0
    return s + (4 if l.get("in_use", True) is not False else 0) + (2 if not l.get("feed") else 0) \
        + (1 if (l.get("format") or "").upper() != "SVG" else 0)


by_channel = {}
for l in logos:
    if l.get("channel") in wanted and l.get("url"):
        by_channel.setdefault(l["channel"], []).append(l)


def load_image(url):
    """Fetch a logo as an RGBA image; SVG and blocked hosts go through the weserv cache."""
    for src in (url, "https://images.weserv.nl/?url=" + urllib.parse.quote(url.split("://", 1)[-1], safe="") + "&w=640&output=png"):
        try:
            data = get(src, timeout=25)
            img = Image.open(io.BytesIO(data))
            img.load()
            return img.convert("RGBA")
        except Exception:
            continue
    return None


def tile(img):
    img = img.crop(img.getbbox()) if img.getbbox() else img  # trim fully transparent margins
    alpha = img.getchannel("A")
    transparent = alpha.getextrema()[0] < 250
    if transparent:
        # Average brightness of the visible pixels decides the tile colour.
        small = img.resize((48, 48))
        px = [p for p in small.getdata() if p[3] > 128]
        lum = sum(0.299 * r + 0.587 * g + 0.114 * b for r, g, b, _ in px) / max(len(px), 1)
        bg = DARK if lum > 140 else LIGHT
    else:
        # Opaque logo: pad with the colour of its border so the padding blends in.
        rgb = img.convert("RGB")
        w, h = rgb.size
        edge = [rgb.getpixel((x, y)) for x in (0, w - 1) for y in range(0, h, max(h // 8, 1))]
        bg = tuple(sum(c[i] for c in edge) // len(edge) for i in range(3))
    scale = min(BOX_W / img.width, BOX_H / img.height)
    if not transparent:  # opaque images can fill more of the tile
        scale = min(TILE_W * 0.94 / img.width, TILE_H * 0.9 / img.height)
    size = (max(1, int(img.width * scale)), max(1, int(img.height * scale)))
    logo = img.resize(size, Image.LANCZOS)
    canvas = Image.new("RGB", (TILE_W, TILE_H), bg)
    canvas.paste(logo, ((TILE_W - size[0]) // 2, (TILE_H - size[1]) // 2), logo)
    return canvas


def work(item):
    cid, ls = item
    for l in sorted(ls, key=rank, reverse=True):  # best logo first, fall back to the others
        img = load_image(l["url"])
        if img is None or img.width < 8 or img.height < 8:
            continue
        name = cid.replace("/", "_") + ".jpg"
        tile(img).save(os.path.join(OUT, name), "JPEG", quality=85, optimize=True)
        return cid, name
    return None


with concurrent.futures.ThreadPoolExecutor(12) as ex:
    results = [r for r in ex.map(work, by_channel.items()) if r]
index = dict(sorted(results))
with open(os.path.join(OUT, "index.json"), "w") as f:
    json.dump(index, f, indent=0)
print(f"{len(index)} of {len(wanted)} channels have a tiled logo in {OUT}")
