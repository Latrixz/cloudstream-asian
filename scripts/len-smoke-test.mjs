// Live smoke test for the Len (len.tv) provider. Follows the same steps as LenProvider:
// home rows (list API) → paging → search → movie and series detail → episode → stream playlist.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const MAIN = "https://len.tv";
const API = `${MAIN}/api`;
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const HEADERS = { "User-Agent": UA, Referer: `${MAIN}/` };
const LIST_PAGE_SIZE = 60;
const SEARCH_PAGE_SIZE = 24;

async function getJson(url) {
  const response = await fetch(url, { headers: HEADERS });
  assert.equal(response.ok, true, `${url}: HTTP ${response.status}`);
  return response.json();
}

// Mirrors LenProvider.mainPage / getMainPage.
const categories = [
  ["movies", "", "电影"],
  ["tv-series", "", "电视剧"],
  ["tv-series", "chinese-drama", "国产剧"],
  ["tv-series", "american-drama", "欧美剧"],
  ["tv-series", "korean-drama", "韩剧"],
  ["tv-series", "japanese-drama", "日剧"],
  ["tv-series", "hk-tw-drama", "港台剧"],
  ["tv-series", "thai", "泰剧"],
  ["tv-series", "dongman", "动漫"],
];
const orders = [["views", "热播"], ["", "最近更新"], ["rating", "高分"]];

function listUrl(endpoint, type, sort, page) {
  const q = [`page=${page}`, `pageSize=${LIST_PAGE_SIZE}`];
  if (type) q.push(`type=${type}`);
  if (sort) q.push(`sort=${sort}`);
  return `${API}/${endpoint}?${q.join("&")}`;
}

async function listPage(endpoint, type, sort, page) {
  const json = await getJson(listUrl(endpoint, type, sort, page));
  assert.equal(json.success, true, `${endpoint} ${type} ${sort}: ${json.error}`);
  return json.data;
}

const rowSummary = [];
for (const [endpoint, type, label] of categories) {
  for (const [sort, sortLabel] of orders) {
    const data = await listPage(endpoint, type, sort, 1);
    assert.ok(data.data.length > 0, `${label} · ${sortLabel}: empty row`);
    assert.ok(data.data.every((x) => x.id && x.title), `${label} · ${sortLabel}: item without id/title`);
    assert.ok(data.totalPages > 1, `${label} · ${sortLabel}: no further pages`);
    rowSummary.push(`${label}·${sortLabel}=${data.total}/${data.totalPages}p`);
  }
}
console.log(`home rows OK (${rowSummary.length}): ${rowSummary.join(" ")}`);

// Paging: page 2 differs from page 1, and the last page is reachable (the API clamps page at 1000).
{
  const p1 = await listPage("movies", "", "views", 1);
  const p2 = await listPage("movies", "", "views", 2);
  const ids1 = new Set(p1.data.map((x) => x.id));
  assert.ok(p2.page === 2 && p2.data.some((x) => !ids1.has(x.id)), "page 2 repeats page 1");
  const last = await listPage("movies", "", "", p1.totalPages);
  assert.equal(last.page, p1.totalPages, `last movie page ${p1.totalPages} not reachable (got ${last.page})`);
  assert.ok(last.data.length > 0, "last movie page empty");
  const tvFirst = await listPage("tv-series", "", "", 1);
  const tvLast = await listPage("tv-series", "", "", tvFirst.totalPages);
  assert.equal(tvLast.page, tvFirst.totalPages, "last tv page not reachable");
  console.log(`paging OK: movies ${p1.totalPages} pages (last has ${last.data.length}), tv ${tvFirst.totalPages} pages`);
}

// Search: /api/search returns every match; the provider pages through them 24 at a time.
async function search(q) {
  const json = await getJson(`${API}/search?q=${encodeURIComponent(q)}`);
  assert.ok(Array.isArray(json.results), `search ${q}: ${json.error}`);
  return json.results;
}
let movieId;
let seriesId;
{
  const results = await search("蜘蛛侠");
  assert.ok(results.length > SEARCH_PAGE_SIZE, `search 蜘蛛侠: only ${results.length} results, cannot test paging`);
  const page2 = results.slice(SEARCH_PAGE_SIZE, 2 * SEARCH_PAGE_SIZE);
  assert.ok(page2.length > 0, "search page 2 empty");
  assert.ok(results.some((r) => r.title.includes("蜘蛛侠")), "search results unrelated");
  movieId = results.find((r) => r.resultType === "movie" && r.title === "蜘蛛侠：崭新之日")?.id
    ?? results.find((r) => r.resultType === "movie").id;
  const series = await search("宝珠");
  seriesId = series.find((r) => r.resultType === "tv" && r.title === "宝珠")?.id
    ?? series.find((r) => r.resultType === "tv").id;
  console.log(`search OK: 蜘蛛侠 → ${results.length} results (${Math.ceil(results.length / SEARCH_PAGE_SIZE)} pages), 宝珠 → ${series.length}`);
}

// Detail + streams.
async function detail(kind, id) {
  const json = await getJson(`${API}/${kind === "movie" ? "movies" : "tv-series"}/${id}`);
  assert.equal(json.success, true, `detail ${kind}-${id}: ${json.error}`);
  return json.data;
}

async function checkImage(url) {
  const response = await fetch(url, { headers: { "User-Agent": UA } });
  assert.equal(response.ok, true, `poster ${url}: HTTP ${response.status}`);
  assert.match(response.headers.get("content-type") || "", /^image\//, `poster ${url}: not an image`);
  await response.arrayBuffer();
}

async function fetchPlaylist(url) {
  try {
    const response = await fetch(url, { headers: { "User-Agent": UA, Referer: `${MAIN}/` }, signal: AbortSignal.timeout(10000) });
    if (!response.ok) return { ok: false, why: `HTTP ${response.status}` };
    const text = await response.text();
    if (!text.trimStart().startsWith("#EXTM3U")) return { ok: false, why: "not a playlist" };
    // Follow a master playlist to its first variant, as the player would.
    const variant = text.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    if (variant && /\.m3u8/i.test(variant)) {
      const next = new URL(variant, response.url).toString();
      const inner = await fetch(next, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(10000) });
      const innerText = await inner.text();
      if (!innerText.includes("#EXTINF")) return { ok: false, why: "variant has no segments" };
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, why: e.cause?.code || e.name };
  }
}

// Same selection as LenProvider.loadLinks: one entry per source, matching episode number.
async function streamsFor(d, number, season = 1) {
  const picks = d.source_options.map((s) => {
    const ep = number == null
      ? s.episodes.find((e) => e.isAvailable !== false && e.videoUrl)
      : s.episodes.find((e) => e.number === number && (e.season ?? 1) === season && e.isAvailable !== false && e.videoUrl);
    return ep ? { source: s.name, url: ep.videoUrl } : null;
  }).filter(Boolean);
  const results = await Promise.all(picks.map(async (p) => ({ ...p, ...(await fetchPlaylist(p.url)) })));
  return results;
}

function fmt(results) {
  return results.map((r) => `${r.source}:${r.ok ? "ok" : r.why}`).join(", ");
}

{
  const movie = await detail("movie", movieId);
  assert.ok(movie.title && movie.poster && movie.description, "movie detail missing title/poster/plot");
  assert.ok(movie.year > 1900, "movie year missing");
  assert.ok(movie.actors?.split(",").filter(Boolean).length > 0, "movie actors missing");
  assert.ok(Number(movie.rating) > 0, "movie rating missing");
  assert.ok(movie.category?.name || movie.category_name, "movie category missing");
  await checkImage(movie.poster);
  const streams = await streamsFor(movie, null);
  assert.ok(streams.some((s) => s.ok), `movie ${movie.title}: no working stream (${fmt(streams)})`);
  console.log(`movie OK: ${movie.title} (${movie.year}) ★${movie.rating} actors=${movie.actors.split(",").length} tags=${[movie.category?.name, movie.region, movie.language].join("/")} streams: ${fmt(streams)}`);
}

{
  const series = await detail("tv", seriesId);
  assert.ok(series.title && series.poster && series.description, "series detail missing title/poster/plot");
  assert.ok(series.year > 1900, "series year missing");
  assert.ok(series.actors?.split(",").filter(Boolean).length > 0, "series actors missing");
  await checkImage(series.poster);
  const sources = series.source_options.filter((s) => Array.isArray(s.episodes) && s.episodes.length);
  assert.ok(sources.length > 0, "series has no sources");
  const canonical = sources.reduce((a, b) => (b.episodes.length > a.episodes.length ? b : a)).episodes;
  const free = canonical.filter((e) => e.isAvailable !== false && e.videoUrl);
  assert.ok(canonical.length > 1, "series has only one episode");
  const numbers = canonical.map((e) => e.number);
  assert.equal(new Set(numbers).size, numbers.length, "duplicate episode numbers");
  const lastEp = canonical[canonical.length - 1];
  const streams = await streamsFor(series, lastEp.number, lastEp.season ?? 1);
  assert.ok(streams.some((s) => s.ok), `series ${series.title} ep ${lastEp.number}: no working stream (${fmt(streams)})`);
  console.log(`series OK: ${series.title} (${series.year}) episodes ${free.length}/${canonical.length} free, ${sources.length} sources; ep ${lastEp.number} streams: ${fmt(streams)}`);
}

// A popular series from the home row, to cover an older and longer title as well.
{
  const row = await listPage("tv-series", "", "views", 1);
  const s = await detail("tv", row.data[0].id);
  const canonical = s.source_options.reduce((a, b) => (b.episodes.length > a.episodes.length ? b : a)).episodes;
  const streams = await streamsFor(s, canonical[0].number, canonical[0].season ?? 1);
  assert.ok(streams.some((x) => x.ok), `series ${s.title}: no working stream (${fmt(streams)})`);
  console.log(`popular series OK: ${s.title} ${canonical.length} episodes; ep ${canonical[0].number} streams: ${fmt(streams)}`);
}

// Recommendations: the detail page's server-rendered "猜你喜欢" grid (LenProvider.recommendations()).
async function recommendations(kind, id) {
  const response = await fetch(`${MAIN}/vod/${kind}-${id}`, { headers: HEADERS, redirect: "follow" });
  assert.equal(response.ok, true, `${kind}-${id} page: HTTP ${response.status}`);
  const html = await response.text();
  const start = html.indexOf(">猜你喜欢</h3>");
  assert.ok(start > 0, `${kind}-${id}: no 猜你喜欢 section`);
  const section = html.slice(start, html.indexOf("</section>", start));
  const items = [...section.matchAll(/<a class="group block" href="(\/vod\/(movie|tv)-(?:[^"]*-)?(\d+))">([\s\S]*?)<\/a>/g)]
    .map(([, href, k, itemId, body]) => ({
      url: `${MAIN}/vod/${k}-${itemId}`,
      title: body.match(/alt="([^"]*)"/)?.[1]?.replace(/\u200b/g, "").trim(),
      poster: decodeURIComponent(body.match(/[?&]url=([^&\s"]+)/)?.[1] ?? ""),
    }))
    .filter((item) => item.url !== `${MAIN}/vod/${kind}-${id}` && item.title)
    .slice(0, 16);
  assert.ok(items.length > 0, `${kind}-${id}: 猜你喜欢 is empty`);
  assert.ok(items.every((item) => item.poster.startsWith("http")), `${kind}-${id}: recommendation without poster`);
  // Every recommendation must open with load(): its detail API answers.
  const first = items[0].url.match(/\/vod\/(movie|tv)-(\d+)/);
  assert.ok((await detail(first[1], first[2])).title, `${items[0].url}: detail API failed`);
  return items;
}
for (const [kind, id] of [["movie", movieId], ["tv", seriesId]]) {
  const items = await recommendations(kind, id);
  console.log(`recommendations OK: ${kind}-${id} → ${items.length} (${items.slice(0, 3).map((i) => i.title).join(", ")})`);
}

console.log("All Len smoke tests passed.");
