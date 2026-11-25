// Live smoke test for the Movieffm (mvffm.net) provider. Mirrors MvffmProvider's parsing:
// list rows → search → detail page `videourls` → decoded stream URLs.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const BASE = "https://www.mvffm.net";
const HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
  Referer: `${BASE}/`,
};

async function html(url) {
  const response = await fetch(url, { headers: HEADERS });
  assert.equal(response.ok, true, `${url}: HTTP ${response.status}`);
  return response.text();
}

function extractJsonArray(text, from) {
  const begin = text.indexOf("[", from);
  if (begin < 0 || begin - from >= 5) return null;
  let depth = 0, inString = false, escaped = false;
  for (let i = begin; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === "\"") inString = false;
      continue;
    }
    if (c === "\"") inString = true;
    else if (c === "[" || c === "{") depth++;
    else if (c === "]" || c === "}") { depth--; if (depth === 0) return text.slice(begin, i + 1); }
  }
  return null;
}

function decodeUrl(encoded) {
  if (!encoded) return null;
  let decoded = Buffer.from(encoded, "base64").toString("utf8");
  if (decoded.includes("%")) decoded = decodeURIComponent(decoded);
  return decoded.startsWith("http") ? decoded.trim() : null;
}

function parseSources(page) {
  const start = page.indexOf("videourls:");
  if (start < 0) return [];
  const root = JSON.parse(extractJsonArray(page, start + "videourls:".length));
  if (!root.length) return [];
  if (root[0] && typeof root[0] === "object" && !Array.isArray(root[0]) && "source" in root[0]) {
    const groups = new Map();
    for (const node of root) {
      const url = decodeUrl(node.url);
      if (!url) continue;
      if (!groups.has(node.source)) groups.set(node.source, []);
      groups.get(node.source).push({ name: node.qname ?? "", url });
    }
    return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(([, list]) => list);
  }
  return root.map((source) => {
    const nodes = Array.isArray(source)
      ? source
      : Object.entries(source).sort((a, b) => Number(a[0]) - Number(b[0])).map(([, v]) => v);
    return nodes.map((n) => ({ name: n.name ?? "", url: decodeUrl(n.url) })).filter((e) => e.url);
  }).filter((list) => list.length);
}

const rows = { "電影": "movies/?", "連續劇": "drama/?", "綜藝": "drama/?tvtype=10&", "動漫": "drama/?tvtype=anime&", "紀錄片": "drama/?tvtype=documentary-film&" };
const counts = {};
for (const [label, path] of Object.entries(rows)) {
  for (const order of ["week", "date", "modified"]) {
    const page = await html(`${BASE}/${path}orderby=${order}`);
    const items = page.match(/<article id="post-\d+" class="item /g) ?? [];
    assert.ok(items.length > 0, `${label} orderby=${order} is empty`);
    counts[label] = Number(page.match(/頁面\s*\d+\s*of\s*(\d+)/)?.[1] ?? 1);
  }
}

const page2 = await html(`${BASE}/drama/page/2/?orderby=week`);
assert.ok(/頁面\s*2\s*of/.test(page2), "drama page 2 did not load");

const search = await html(`${BASE}/xssearch?q=${encodeURIComponent("蜘蛛人")}&f=_all&p=1`);
const hits = (search.match(/class="result-item"/g) ?? []).length;
assert.ok(hits > 0, "search returned nothing");

async function checkTitle(url) {
  const page = await html(url);
  const sources = parseSources(page);
  assert.ok(sources.length > 0, `${url}: no sources`);
  let playable = 0;
  for (const list of sources) {
    try {
      const response = await fetch(list[0].url, { headers: { "User-Agent": HEADERS["User-Agent"] }, signal: AbortSignal.timeout(8000) });
      const body = await response.text();
      if (response.ok && (/^#EXTM3U/m.test(body) || !list[0].url.includes(".m3u8"))) playable++;
    } catch { /* unreachable source */ }
  }
  assert.ok(playable > 0, `${url}: no source returned a playable stream`);
  // Recommendations: the first (active) 猜你喜歡 tab of #single_relacionados_b, playable paths only.
  const box = page.split('id="single_relacionados_b"')[1] ?? "";
  const tab = box.split('<div class="relacion_tab')[1] ?? "";
  const related = [...new Set([...tab.matchAll(/<article><a href="([^"]+)"/g)].map((m) => m[1]))]
    .filter((u) => (u.includes("/movies/") || u.includes("/drama/")) && u !== url).slice(0, 16);
  assert.ok(related.length > 0, `${url}: no recommendations`);
  assert.ok(parseSources(await html(related[0])).length > 0, `${related[0]}: recommendation has no sources`);
  const extras = {
    castPhotos: (page.split('<div id="cast"')[1]?.split("srelacionados")[0].match(/data-lazy-src="https:\/\/image\.tmdb\.org\/t\/p\/w92\/[^"]+"/g) ?? []).length,
    duration: page.match(/class='runtime'>片長：<b>(\d+)/)?.[1] ?? null,
    background: /id='dt_galery'[\s\S]*?<a href='https?:/.test(page),
  };
  return { url, sources: sources.length, playableSources: playable, episodes: Math.max(...sources.map((s) => s.length)), recommendations: related.length, ...extras };
}

const movieUrl = (await html(`${BASE}/movies/?orderby=date`)).match(/href="(https:\/\/www\.mvffm\.net\/movies\/(?!page\/)[^"]+\/)"/)[1];
const dramaUrl = (await html(`${BASE}/drama/?orderby=week`)).match(/href="(https:\/\/www\.mvffm\.net\/drama\/\d+\/)"/)[1];
const movie = await checkTitle(movieUrl);
const drama = await checkTitle(dramaUrl);

console.log(JSON.stringify({ listPages: counts, searchHits: hits, movie, drama }, null, 2));
