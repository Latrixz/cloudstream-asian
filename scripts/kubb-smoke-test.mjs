// Live API smoke test for the Kubb provider. Mirrors KubbProvider.signed() and the
// list → detail → playlist → play flow. Run with Node.js 18+: node scripts/smoke-test.mjs
import crypto from "node:crypto";
import assert from "node:assert/strict";

const LIST_API = "https://m10.kubb.tv/api";
const VIDEO_API = "https://m10.kubb.tv/v3";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const PRIVATE_KEYS = ["version001", "vers1on001", "vers1on00i", "bersion001", "vcrsion001", "versi0n001", "versio_001", "version0o1"];

function signed(base, params, now = Date.now()) {
  const all = { ...params, v: "1" };
  const raw = Object.entries(all).map(([k, v]) => `${k}=${v}`).join("&");
  const encoded = Object.entries(all).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
  const pub = String(now);
  const priv = PRIVATE_KEYS[now % PRIVATE_KEYS.length];
  const vv = crypto.createHash("md5").update(`${pub}&${raw.toLowerCase()}&${priv}`).digest("hex");
  return `${base}?${encoded}&vv=${vv}&pub=${pub}`;
}

async function get(base, params) {
  const response = await fetch(signed(base, params), {
    headers: { "User-Agent": UA, Origin: "https://www.kubb.tv", Referer: "https://www.kubb.tv/" },
  });
  assert.equal(response.ok, true, `${base}: HTTP ${response.status}`);
  const json = await response.json();
  assert.equal(json.data?.code, 0, `${base}: ${json.data?.msg || "API error"}`);
  return json.data.info?.[0];
}

const search = (tags, cid, orderby, page = 1) => get(`${LIST_API}/list/Search`, {
  cinema: "1", tags, page: String(page), size: "24", orderby, desc: "1", cid,
  isserial: "-1", isIndex: "-1", isfree: "-1",
});

const categories = { 3: "电影", 4: "电视剧", 5: "综艺", 6: "动漫", 7: "纪录片", 8: "短剧" };
const counts = {};
for (const [cid, label] of Object.entries(categories)) {
  for (const order of ["2", "0", "1"]) {
    const page = await search("", `0,1,${cid}`, order);
    assert.ok(page.result.length > 0, `${label} orderby=${order} is empty`);
    counts[label] = page.recordcount;
  }
}

const drama1 = await search("", "0,1,4", "2", 1);
const drama2 = await search("", "0,1,4", "2", 2);
assert.notEqual(drama2.result[0].key, drama1.result[0].key, "drama page 2 repeated page 1");

const found = await search("海贼王", "", "0");
assert.ok(found.result.some((item) => item.title.includes("海贼王")), "Chinese search found nothing");

async function playable(key) {
  const detail = await get(`${VIDEO_API}/video/detail`, {
    ispath: "false", cinema: "1", device: "1", player: "CkPlayer", tech: "HLS",
    country: "HU", lang: "cns", region: "GL.", id: key,
  });
  assert.ok(detail?.title, `${key}: detail missing`);
  const playlist = await get(`${VIDEO_API}/video/languagesplaylist`, {
    cinema: "1", vid: detail.key || key, lsk: "1", taxis: "1", cid: detail.cid,
  });
  const free = playlist.playList.filter((episode) => !episode.isVip && episode.key);
  assert.ok(free.length > 0, `${detail.title}: no free episodes`);
  const play = await get(`${VIDEO_API}/video/play`, {
    cinema: "1", id: free[0].key, a: "0", lang: "cns", usersign: "1", region: "GL.", device: "1", isMasterSupport: "1",
  });
  const hls = play.flvPathList.filter((path) => path.isHls && path.result);
  assert.ok(hls.length > 0, `${detail.title}: no HLS stream`);
  const m3u8 = await fetch(hls[0].result, { headers: { "User-Agent": UA } });
  assert.equal(m3u8.ok, true, `${detail.title}: HLS HTTP ${m3u8.status}`);
  assert.match(await m3u8.text(), /^#EXTM3U/m, `${detail.title}: not an HLS playlist`);
  return { title: detail.title, episodes: free.length, quality: play.mediaTitle };
}

const movie = await playable((await search("", "0,1,3", "0")).result[0].key);
const drama = await playable(drama1.result[0].key);

console.log(JSON.stringify({ catalogueTotals: counts, searchHits: found.recordcount, movie, drama }, null, 2));
