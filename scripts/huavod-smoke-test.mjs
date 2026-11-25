// Live smoke test for the Huavod provider. Mirrors HuavodProvider's steps against the real site:
// home rows → paging → Chinese search → movie/series detail → player token → m3u8 playlist.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const BASE = "https://huavod.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const PAGE_HEADERS = { "User-Agent": UA, Referer: `${BASE}/` };
const LIST_PAGE_SIZE = 40;
const AD_WINDOW_MARGIN_MS = 1500;

const CATEGORIES = [
  [1, "电影"], [2, "电视剧"], [3, "综艺"], [4, "动漫"], [5, "短剧"], [42, "纪录片"],
];
const ORDERS = [["hits", "热播"], ["id", "最新添加"], ["time", "最近更新"]];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const decode = (s) => s.replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#039;/g, "'").trim();
const strip = (s) => decode(s.replace(/<[^>]*>/g, ""));

// Like HuavodProvider.getPage(): back off and retry when Cloudflare answers a burst with 429.
async function getText(url, headers = PAGE_HEADERS) {
  let response;
  for (let attempt = 0; attempt < 4; attempt++) {
    response = await fetch(url, { headers });
    if (response.status !== 429 || attempt === 3) break;
    await response.arrayBuffer();
    await sleep(1500 * (attempt + 1));
  }
  assert.equal(response.ok, true, `${url}: HTTP ${response.status}`);
  return response.text();
}

function imageOf(tag) {
  for (const attr of ["data-original", "data-src", "src"]) {
    const value = tag.match(new RegExp(`\\s${attr}="([^"]*)"`))?.[1];
    if (value && !value.startsWith("data:")) return new URL(value, BASE).href;
  }
  return null;
}

// getMainPage(): cards are a.public-list-exp; hasNext uses the item total (the "N / M页" counter overstates).
async function listPage(typeId, order, page) {
  const html = await getText(`${BASE}/vodshow/${typeId}/by/${order}/page/${page}.html`);
  const items = [...html.matchAll(/<a[^>]*class="public-list-exp"[^>]*href="(\/voddetail\/\d+\.html)"[^>]*title="([^"]*)"[^>]*>\s*(<img[^>]*>)/g)]
    .map(([, href, title, img]) => ({ url: BASE + href, title: decode(title), poster: imageOf(img) }));
  const total = Number(html.match(/hl-total'\)\.html\('(\d+)'/)?.[1] ?? NaN);
  const lastPage = Number(html.match(/\d+\s*(?:&nbsp;)?\/(?:&nbsp;)?\s*(\d+)页/)?.[1] ?? page);
  const hasNext = items.length > 0 && (Number.isFinite(total) ? (page - 1) * LIST_PAGE_SIZE + items.length < total : page < lastPage);
  return { items, total, hasNext };
}

// searchPage()
async function searchPage(query, page) {
  const html = await getText(`${BASE}/vodsearch/page/${page}/wd/${encodeURIComponent(query)}.html`);
  const items = [...html.matchAll(/<div class="thumb-txt cor4 hide"><a[^>]*href="(\/voddetail\/\d+\.html)"[^>]*>([^<]*)<\/a>/g)]
    .map(([, href, title]) => ({ url: BASE + href, title: decode(title) }));
  const last = Number(html.match(/当前(\d+)\/(\d+)页/)?.[2] ?? page);
  return { items, hasNext: items.length > 0 && page < last };
}

const takeUntilCast = (tags) => { const i = tags.findIndex((t) => /[:：]/.test(t)); return i < 0 ? tags : tags.slice(0, i); };
const normalizeEpisode = (name) => name.trim().match(/^第?\s*0*(\d+)\s*[集话話期]?$/)?.[1] ?? name.trim();

// load(): detail fields + parseSources()
async function detail(url) {
  const html = await getText(url);
  const title = strip(html.match(/<h1 class="slide-info-title[^"]*">([\s\S]*?)<\/h1>/)?.[1] ?? "");
  const poster = imageOf(html.match(/<div class="detail-pic">\s*(<img[^>]*>)/)?.[1] ?? "");
  const plot = strip(html.match(/id="height_limit"[^>]*>([\s\S]*?)<\/div>/)?.[1] ?? "");
  const year = Number(html.match(/<a href="\/vodsearch\/year\/(\d{4})\.html"/)?.[1]) || null;
  const score = Number(html.match(/<div class="detail-score[\s\S]*?<div class="fraction">([\d.]+)</)?.[1]) || null;
  const actorLine = html.match(/<strong class="r6">演员 :<\/strong>([\s\S]*?)<\/div>/)?.[1] ?? "";
  const actors = [...actorLine.matchAll(/<a[^>]*>([^<]+)<\/a>/g)].map((m) => decode(m[1]));
  const tags = [...new Set([
    ...takeUntilCast([...(html.match(/<div class="vod-tag"[\s\S]*?<\/div>/)?.[0] ?? "").matchAll(/>#?([^<]*)<\/a>/g)].map((t) => decode(t[1])))
      .filter((t) => !t.includes("在线观看")),
    ...[...html.matchAll(/<span class="slide-info-remarks"><a href="[^"]*\/(?:class|area)\/[^"]*"[^>]*>([^<]+)<\/a>/g)].map((m) => decode(m[1])),
  ])].filter((t) => t.length >= 2);
  const typeId = Number(html.match(/"position":2,"name":"[^"]*","item":"[^"]*\/vodtype\/(\d+)\.html"/)?.[1]);

  const tabBlock = html.match(/<div class="anthology-tab[\s\S]*?<\/div><\/div>/)?.[0] ?? "";
  const labels = [...tabBlock.matchAll(/<a class="swiper-slide">([\s\S]*?)<\/a>/g)].map((m) => strip(m[1]));
  const listStart = html.indexOf('<div class="anthology-list ');
  const boxes = listStart < 0 ? [] : html.slice(listStart).split('<div class="anthology-list-box').slice(1)
    .map((box) => box.split(/<div class="star-works|<\/ul>/)[0]);
  const sources = boxes.map((box, index) => ({
    label: labels[index] || `线路 ${index + 1}`,
    episodes: [...box.matchAll(/href="\/vodplay\/(\d+)-(\d+)-(\d+)\.html">([^<]*)<\/a>/g)]
      .map(([, vodId, sid, nid, name]) => ({ vodId, sid: +sid, nid: +nid, name: decode(name) })),
  })).filter((source) => source.episodes.length > 0);
  // HuavodProvider.recommendations(): the "相关影片" grid on the same page, minus the title itself.
  const relStart = html.indexOf(">相关影片</h2>");
  const relEnd = html.indexOf("相关明星", relStart);
  const related = relStart < 0 ? [] : [...new Map(
    [...html.slice(relStart, relEnd < 0 ? undefined : relEnd).matchAll(/<a[^>]*class="public-list-exp"[^>]*href="(\/voddetail\/\d+\.html)"[^>]*title="([^"]*)"[^>]*>\s*(<img[^>]*>)/g)]
      .map(([, href, name, img]) => [BASE + href, { url: BASE + href, title: decode(name), poster: imageOf(img) }]),
  ).values()].filter((item) => item.url !== url.split("#")[0] && item.title).slice(0, 16);
  return { title, poster, plot, year, score, actors, tags, typeId, sources, related };
}

// loadLinks(): token per source → one ad-window wait → redeem → verify #EXTM3U
async function loadLinks(sources, wantedName) {
  const wanted = normalizeEpisode(wantedName);
  const targets = sources.map((source) => ({
    label: source.label,
    entry: source.episodes.find((e) => normalizeEpisode(e.name) === wanted),
  })).filter((t) => t.entry);
  assert.ok(targets.length > 0, `no source has episode ${wantedName}`);

  const tickets = await Promise.all(targets.map(async ({ label, entry }) => {
    const key = `${entry.vodId}-${entry.sid}-${entry.nid}`;
    const html = await getText(`${BASE}/Player/ec?episode=${key}`, { "User-Agent": UA, Referer: `${BASE}/vodplay/${key}.html` });
    const start = html.indexOf("ConFig=");
    const json = start < 0 ? null : extractObject(html, start + "ConFig=".length);
    const config = json ? JSON.parse(json) : {};
    return {
      label, key,
      ok: config.code === 200,
      token: config.mt || null,
      inline: /^https?:/.test(config.url || "") ? config.url : null,
      ad: Number(config.ad_duration_ms || 0),
    };
  }));
  const wait = Math.max(0, ...tickets.filter((t) => t.ok && !t.inline).map((t) => t.ad - AD_WINDOW_MARGIN_MS));
  await sleep(wait);

  const links = await Promise.all(tickets.map(async (ticket) => {
    if (!ticket.ok) return { label: ticket.label, error: "no free stream" };
    let url = ticket.inline;
    if (!url) {
      const response = await fetch(`${BASE}/Player/resolveUrl`, {
        method: "POST",
        headers: { "User-Agent": UA, Origin: BASE, Referer: `${BASE}/Player/ec?episode=${ticket.key}`, "Content-Type": "application/x-www-form-urlencoded" },
        body: `token=${encodeURIComponent(ticket.token)}`,
      });
      const json = await response.json();
      if (json.code !== 1) return { label: ticket.label, error: json.msg || "resolve failed" };
      url = json.data.url;
    }
    const playlist = await getText(url, { "User-Agent": UA });
    const segment = playlist.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    let segmentStatus = null;
    if (segment) {
      const response = await fetch(new URL(segment, url), { headers: { "User-Agent": UA, Range: "bytes=0-187" } });
      segmentStatus = response.status;
      await response.arrayBuffer();
    }
    return { label: ticket.label, url, playable: playlist.startsWith("#EXTM3U"), segmentStatus };
  }));
  return { wait, links };
}

function extractObject(text, from) {
  const begin = text.indexOf("{", from);
  let depth = 0, inString = false, escaped = false;
  for (let i = begin; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") { depth--; if (depth === 0) return text.slice(begin, i + 1); }
  }
  return null;
}

async function imageOk(url) {
  const response = await fetch(url, { headers: { "User-Agent": UA } });
  const type = response.headers.get("content-type") || "";
  await response.arrayBuffer();
  return response.ok && type.startsWith("image/");
}

// ---- Home rows ----
const firstItems = {};
for (const [typeId, label] of CATEGORIES) {
  const summary = [];
  for (const [order, orderLabel] of ORDERS) {
    const page = await listPage(typeId, order, 1);
    assert.ok(page.items.length > 0, `${label} · ${orderLabel} is empty`);
    assert.equal(page.hasNext, true, `${label} · ${orderLabel} should have a next page`);
    firstItems[`${typeId}|${order}`] = page.items;
    summary.push(`${orderLabel} ${page.items.length}`);
  }
  console.log(`row  ${label}: ${summary.join(", ")} (total ${(await listPage(typeId, "hits", 1)).total})`);
}

// ---- Paging: page 2 differs, and the real last page ends the row ----
const drama2 = await listPage(2, "hits", 2);
assert.ok(drama2.items.length > 0, "电视剧 page 2 empty");
assert.notEqual(drama2.items[0].url, firstItems["2|hits"][0].url, "电视剧 page 2 repeated page 1");
const docs = await listPage(42, "hits", 1);
const lastPage = Math.ceil(docs.total / LIST_PAGE_SIZE);
const docsLast = await listPage(42, "hits", lastPage);
assert.ok(docsLast.items.length > 0, `纪录片 last page ${lastPage} is empty`);
assert.equal(docsLast.hasNext, false, "纪录片 last page still reports a next page");
console.log(`page 电视剧 p2 ok; 纪录片 last page ${lastPage} has ${docsLast.items.length} items, hasNext=false`);

// ---- Posters from a row ----
const rowPosters = firstItems["1|hits"].slice(0, 3).map((i) => i.poster);
assert.ok(rowPosters.every(Boolean), "row item without poster");
assert.ok((await Promise.all(rowPosters.map(imageOk))).every(Boolean), "row poster failed to load");

// ---- Chinese search with paging ----
const search1 = await searchPage("海贼王", 1);
assert.ok(search1.items.some((i) => i.title.includes("海贼王")), "search 海贼王 found nothing");
const search2 = await searchPage("爱情", 2);
assert.ok(search2.items.length > 0, "search 爱情 page 2 empty");
console.log(`search 海贼王 p1: ${search1.items.length} results, hasNext=${search1.hasNext}; 爱情 p2: ${search2.items.length}, hasNext=${search2.hasNext}`);

// ---- Movie and series details ----
async function checkDetail(url, kind) {
  const d = await detail(url);
  assert.ok(d.title, `${url}: no title`);
  assert.ok(d.poster && (await imageOk(d.poster)), `${url}: poster missing or broken (${d.poster})`);
  assert.ok(d.plot.length > 10, `${url}: no plot`);
  assert.ok(d.sources.length > 0, `${url}: no sources`);
  assert.ok(d.related.length > 0, `${url}: no 相关影片 recommendations`);
  assert.ok(d.related.every((item) => item.poster), `${url}: recommendation without poster`);
  console.log(`${kind} ${d.title}: ${d.related.length} recommendations (${d.related.slice(0, 3).map((i) => i.title).join(", ")})`);
  // Same /voddetail/{id}.html links as the list rows, so load() opens them (not fetched again: Cloudflare 429s bursts).
  assert.ok(d.related.every((item) => /^https:\/\/huavod\.com\/voddetail\/\d+\.html$/.test(item.url)), `${url}: odd recommendation URL`);
  console.log(`${kind} ${d.title}: year ${d.year}, score ${d.score}, ${d.actors.length} actors, tags [${d.tags.slice(0, 4).join(" ")}], ` +
    `sources ${d.sources.map((s) => `${s.label}(${s.episodes.length})`).join(" ")}`);
  return d;
}
const movie = await checkDetail(firstItems["1|hits"][0].url, "movie ");
assert.equal(movie.typeId, 1);
const series = await checkDetail(firstItems["2|hits"].find(Boolean).url, "series");
assert.equal(series.typeId, 2);
const canonical = series.sources.reduce((a, b) => (b.episodes.length > a.episodes.length ? b : a)).episodes;
assert.ok(canonical.length > 1, "series has a single episode");
assert.ok(series.actors.length > 0, "series without cast");

// ---- Streams ----
for (const [kind, d, episode] of [["movie ", movie, movie.sources[0].episodes[0].name], ["series", series, canonical[Math.min(1, canonical.length - 1)].name]]) {
  const { wait, links } = await loadLinks(d.sources, episode);
  for (const link of links) {
    console.log(`play ${kind} ${d.title} [${episode}] ${link.label}: ${link.error ?? `${link.playable ? "#EXTM3U" : "NOT A PLAYLIST"} segment HTTP ${link.segmentStatus}`}`);
  }
  assert.ok(links.some((l) => l.playable), `${d.title}: no playable stream`);
  console.log(`     (waited ${wait} ms for the ad window)`);
}

console.log("smoke test passed");
