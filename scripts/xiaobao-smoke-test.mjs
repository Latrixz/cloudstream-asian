// Live smoke test for the Xiaobao provider. Mirrors XiaobaoProvider's list → search → detail →
// play page → HLS flow using plain HTTP and regex parsing. Run with Node.js 18+:
//   node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const MAIN = "https://www.xiaobaotv.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const HEADERS = { "User-Agent": UA, Referer: `${MAIN}/` };

async function fetchText(url, { timeout = 20000, headers = HEADERS } = {}) {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(timeout) });
  assert.equal(response.ok, true, `${url}: HTTP ${response.status}`);
  return response.text();
}

const decode = (s) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, " ").trim();
const abs = (u) => (u.startsWith("http") ? u : u.startsWith("//") ? `https:${u}` : `${MAIN}${u}`);

function pageInfo(html) {
  const m = html.match(/<li class="visible-xs"><a[^>]*>\s*(\d+)\s*\/\s*(\d+)\s*</);
  return m ? [Number(m[1]), Number(m[2])] : [1, 1];
}

/** `.myui-vodlist__box a.myui-vodlist__thumb` items, as in listItems(). */
function listItems(html) {
  const items = [];
  const re = /<a class="myui-vodlist__thumb[^"]*" href="(\/vod\/detail\/\d+\.html)" title="([^"]*)"[^>]*data-original="([^"]*)"/g;
  for (const m of html.matchAll(re)) items.push({ url: abs(m[1]), title: decode(m[2]), poster: abs(m[3].split("#")[0]) });
  return [...new Map(items.map((i) => [i.url, i])).values()];
}

const categories = { 1: "电影", 2: "电视剧", 4: "综艺", 3: "动漫", 11: "短剧", 124: "纪录片" };
const orders = { hits: "热播", id: "最新添加", time: "最近更新" };
const listUrl = (cat, by, page) => `${MAIN}/vod/show/by/${by}/id/${cat}${page > 1 ? `/page/${page}` : ""}.html`;

// 1. Every home row, page 1 and page 2.
const posters = [];
for (const [cat, label] of Object.entries(categories)) {
  for (const [by, orderLabel] of Object.entries(orders)) {
    const p1 = await fetchText(listUrl(cat, by, 1));
    const items1 = listItems(p1);
    const [, last] = pageInfo(p1);
    assert.ok(items1.length > 0, `${label}·${orderLabel} empty`);
    assert.ok(last > 1, `${label}·${orderLabel} has no further pages`);
    const items2 = listItems(await fetchText(listUrl(cat, by, 2)));
    assert.ok(items2.length > 0, `${label}·${orderLabel} page 2 empty`);
    assert.notEqual(items2[0].url, items1[0].url, `${label}·${orderLabel} page 2 repeats page 1`);
    posters.push(items1[0].poster);
    console.log(`ok  ${label} · ${orderLabel}: ${items1.length} items, ${last} pages, first "${items1[0].title}"`);
  }
}

// The last page also exists, so rows really reach the end of the catalogue.
{
  const [, last] = pageInfo(await fetchText(listUrl(1, "id", 1)));
  const tail = await fetchText(listUrl(1, "id", last));
  assert.ok(listItems(tail).length > 0, "电影 last page empty");
  assert.deepEqual(pageInfo(tail), [last, last], "电影 last page pager mismatch");
  console.log(`ok  电影 · 最新添加 last page ${last} has items`);
}

// 2. Posters load.
for (const poster of posters.slice(0, 6)) {
  const r = await fetch(poster, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(20000) });
  assert.equal(r.ok, true, `poster ${poster}: HTTP ${r.status}`);
  assert.match(r.headers.get("content-type") || "", /^image\//, `poster ${poster} is not an image`);
}
console.log(`ok  ${Math.min(6, posters.length)} posters load as images`);

// 3. Chinese search with paging.
const searchUrl = (q, page) => `${MAIN}/search/page/${page}/wd/${encodeURIComponent(q)}.html`;
function searchItems(html) {
  const list = (html.split('id="searchList"')[1] ?? "").split("</ul>")[0];
  return listItems(list);
}
{
  const s1 = await fetchText(searchUrl("永夜星河", 1));
  assert.ok(searchItems(s1).some((i) => i.title.includes("永夜星河")), "search 永夜星河 found nothing");
  const a1 = await fetchText(searchUrl("爱", 1));
  const a2 = await fetchText(searchUrl("爱", 2));
  const [, last] = pageInfo(a1);
  assert.ok(last > 1, "search 爱 has one page");
  assert.notEqual(searchItems(a2)[0]?.url, searchItems(a1)[0]?.url, "search page 2 repeats page 1");
  console.log(`ok  search 永夜星河 found; search 爱: ${searchItems(a1).length} per page, ${last} pages, page 2 differs`);
}

// 4. Details and streams.
function parseDetail(html) {
  const detail = html.split('class="myui-content__detail"')[1] ?? "";
  const title = decode(detail.match(/<h1 class="title">([^<]*)/)?.[1] ?? "");
  const poster = html.match(/class="myui-content__thumb"[\s\S]*?data-original="([^"]*)"/)?.[1];
  const category = detail.match(/分类：<\/span><a href="\/vod\/show\/id\/(\d+)\.html">([^<]*)/);
  const year = detail.match(/年份：<\/span><a[^>]*>(\d{4})/)?.[1];
  const actors = [...(detail.split("主演：")[1]?.split("</p>")[0] ?? "").matchAll(/\/search\/actor\/[^"]*"[^>]*>([^<]*)/g)].map((m) => decode(m[1]));
  const plot = decode((html.match(/<span class="data"[^>]*>([\s\S]*?)<\/span>/)?.[1] ?? "").replace(/<[^>]+>/g, ""));
  const labels = Object.fromEntries([...html.matchAll(/<a href="#(playlist\d+)" data-toggle="tab">([^<]*)/g)].map((m) => [m[1], decode(m[2])]));
  const sources = [...html.matchAll(/<div id="(playlist\d+)" class="tab-pane[^"]*">([\s\S]*?)<\/ul>/g)].map((m) => ({
    label: labels[m[1]] || m[1],
    entries: [...m[2].matchAll(/href="(\/vod\/play\/[^"]+)"[^>]*>([^<]*)/g)].map((e) => ({ url: abs(e[1]), name: decode(e[2]) })),
  })).filter((s) => s.entries.length > 0);
  return { title, poster: poster && abs(poster.split("#")[0]), categoryId: category?.[1], categoryName: category?.[2], year, actors, plot, sources };
}

const normalize = (n) => n.trim().match(/^第?\s*0*(\d+)\s*[集話话期]?$/)?.[1] ?? n.trim();

function unescapeJs(s) {
  return s.replace(/%u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/%([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
}

async function playerData(playUrl) {
  const html = await fetchText(playUrl);
  const json = html.match(/player_aaaa\s*=\s*(\{.*?\})<\/script>/s)?.[1];
  assert.ok(json, `${playUrl}: no player_aaaa`);
  return JSON.parse(json);
}

function streamUrl(data) {
  if (Number(data.points) > 0 || Number(data.trysee) > 0) return null;
  let url = data.url || "";
  if (String(data.encrypt) === "1") url = unescapeJs(url);
  else if (String(data.encrypt) === "2") url = unescapeJs(Buffer.from(url, "base64").toString("latin1"));
  return url.startsWith("//") ? `https:${url}` : url.startsWith("http") ? url : null;
}

/** Follows a master playlist to its first variant and sums #EXTINF durations. */
async function hlsMinutes(url) {
  let body = await fetchText(url, { timeout: 8000, headers: { "User-Agent": UA } });
  assert.match(body, /#EXTM3U/, `${url} is not HLS`);
  if (body.includes("#EXT-X-STREAM-INF")) {
    const variant = body.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    url = new URL(variant, url).href;
    body = await fetchText(url, { timeout: 15000, headers: { "User-Agent": UA } });
  }
  const lines = body.split("\n").map((l) => l.trim());
  const seconds = lines.filter((l) => l.startsWith("#EXTINF:")).reduce((sum, l) => sum + parseFloat(l.slice(8)), 0);
  const segment = new URL(lines.find((l) => l && !l.startsWith("#")), url).href;
  return { minutes: seconds / 60, segment, encrypted: body.includes("#EXT-X-KEY") };
}

/** Mirrors loadLinks(): every source's copy of an episode, checked in parallel, working ones first. */
async function links(sources, canonical, position) {
  const entry = canonical.entries[position];
  const candidates = sources.map((s) => {
    const match = s === canonical ? entry
      : s.entries.find((e) => normalize(e.name) === normalize(entry.name))
        ?? (canonical.entries.length === 1 && s.entries.length === 1 ? s.entries[0] : null);
    return match && { label: s.label, url: match.url };
  }).filter(Boolean);
  const results = await Promise.all(candidates.map(async (c, index) => {
    const stream = streamUrl(await playerData(c.url));
    if (!stream) return null;
    const hls = await hlsMinutes(stream).catch(() => null);
    return { index, label: c.label, stream, alive: !!hls, hls };
  }));
  return results.filter(Boolean).sort((a, b) => (a.alive === b.alive ? a.index - b.index : a.alive ? -1 : 1));
}

async function checkTitle(detailUrl, expectSeries) {
  const d = parseDetail(await fetchText(detailUrl));
  assert.ok(d.title, `${detailUrl}: no title`);
  assert.ok(d.poster, `${d.title}: no poster`);
  assert.ok(d.plot.length > 10, `${d.title}: no plot`);
  assert.ok(d.year, `${d.title}: no year`);
  assert.ok(d.sources.length > 0, `${d.title}: no sources`);
  const canonical = d.sources.reduce((a, b) => (b.entries.length > a.entries.length ? b : a));
  if (expectSeries) assert.ok(canonical.entries.length > 1, `${d.title}: expected several episodes`);
  const vod = (await playerData(canonical.entries[0].url)).vod_data;
  const poster = await fetch(d.poster, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(20000) });
  assert.equal(poster.ok, true, `${d.title}: poster HTTP ${poster.status}`);
  const position = expectSeries ? Math.min(4, canonical.entries.length - 1) : 0;
  const found = await links(d.sources, canonical, position);
  assert.ok(found.length > 0 && found[0].alive, `${d.title}: no working stream`);
  const best = found[0];
  assert.ok(best.hls.minutes > 20, `${d.title}: stream only ${best.hls.minutes.toFixed(1)} min`);
  const seg = await fetch(best.hls.segment, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(20000) });
  assert.equal(seg.ok, true, `${d.title}: segment HTTP ${seg.status}`);
  console.log(`ok  ${expectSeries ? "series" : "movie"} "${d.title}" (${d.categoryName}/${d.categoryId}, ${d.year}), ${canonical.entries.length} episode(s), ` +
    `sources [${d.sources.map((s) => `${s.label}:${s.entries.length}`).join(", ")}], cast ${(vod?.vod_actor || d.actors.join(",")).split(",").length}, genres "${vod?.vod_class}"`);
  for (const f of found) {
    console.log(`      ${f.alive ? "live" : "dead"} ${f.label} ${canonical.entries[position].name}: ${f.alive ? `${f.hls.minutes.toFixed(1)} min${f.hls.encrypted ? " (AES-128)" : ""}` : "unreachable"}  ${f.stream}`);
  }
  return best.stream;
}

const movieStream = await checkTitle(`${MAIN}/vod/detail/256576.html`, false); // 坏兽
const seriesStream = await checkTitle(`${MAIN}/vod/detail/185288.html`, true); // 永夜星河
// The top title of the 电影 热播 row, so the test does not depend on fixed ids only.
const newest = listItems(await fetchText(listUrl(1, "hits", 1)))[0];
const newestStream = await checkTitle(newest.url, false);
assert.equal(new Set([movieStream, seriesStream, newestStream]).size, 3, "different titles share a stream");
console.log("ok  every title has its own stream");
console.log("\nAll smoke tests passed.");
