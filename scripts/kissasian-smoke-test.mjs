// Live smoke test for the Kissasian provider. Mirrors KissasianProvider: home rows and paging,
// search, drama details + full episode list, a movie, and real streams through the
// kissasian -> zumivids -> vidmoly / streamtape / mixdrop chain.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const MAIN = "https://kissasian.ng";
const ZUMI = "https://zumivids.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

async function fetchText(url, referer = `${MAIN}/`, extra = {}) {
  const response = await fetch(url, { headers: { "User-Agent": UA, Referer: referer, ...extra } });
  return { status: response.status, url: response.url, text: await response.text() };
}
async function page(url, referer) {
  const r = await fetchText(url, referer);
  assert.equal(r.status, 200, `${url}: HTTP ${r.status}`);
  return r.text;
}
const decode = (s) => s.replace(/&amp;/g, "&").replace(/&#0?39;|&rsquo;|&#8217;/g, "'").replace(/&quot;/g, '"').replace(/<[^>]+>/g, "").trim();

// ---- listing cards (article.bs) ----
function cards(html) {
  const out = [];
  for (const m of html.matchAll(/<article class="bs"[\s\S]*?<\/article>/g)) {
    const a = m[0].match(/<a href="([^"]+)"[^>]*title="([^"]*)"/);
    if (!a || !a[1].startsWith(MAIN) || /\/random|list-mode/.test(a[1])) continue;
    const poster = (m[0].match(/data-src="([^"]+)"/) || m[0].match(/<img[^>]+src="(http[^"]+)"/) || [])[1];
    const type = (m[0].match(/class="typez ([^"]+)"/) || [])[1];
    out.push({ url: a[1], title: decode(a[2]), poster, type });
  }
  return out;
}
function homeSection(html, heading) {
  const boxes = html.split('<div class="bixbox"').slice(1);
  const box = boxes.find((b) => new RegExp(`<h[23]>${heading}`).test(b.slice(0, 400)));
  return box ? cards(box.split('<div class="bixbox"')[0]) : [];
}

const counts = {};
// Home: Latest Episodes (paged) and Hot Series Update (single page).
const home1 = await page(`${MAIN}/`);
const home2 = await page(`${MAIN}/page/2/`);
const latest1 = homeSection(home1, "Latest Release");
const latest2 = homeSection(home2, "Latest Release");
const hot = homeSection(home1, "Hot Series");
assert.ok(latest1.length >= 10, "Latest Episodes empty");
assert.ok(hot.length > 0, "Hot Series Update empty");
assert.notEqual(latest2[0]?.url, latest1[0]?.url, "Latest Episodes page 2 repeated page 1");
assert.ok(/class="r"|next page-numbers/.test(home1), "home has no next page link");
counts["Latest Episodes"] = `${latest1.length}+${latest2.length}`;
counts["Hot Series Update"] = hot.length;

const rows = {
  "Recently Updated Dramas": "order=update",
  "Popular Dramas": "order=popular",
  "Newly Added Dramas": "order=latest",
  "Ongoing Dramas": "status=ongoing&order=popular",
  Movies: "type=movie&order=update",
  "Korean Dramas": "country%5B%5D=south-korea&order=update",
  "Chinese Dramas": "country%5B%5D=china&order=update",
  "Japanese Dramas": "country%5B%5D=japan&order=update",
  "Thai Dramas": "country%5B%5D=thailand&order=update",
  "Taiwanese Dramas": "country%5B%5D=taiwan&order=update",
  Filipino: "country%5B%5D=philippines&order=update",
  "Top Rated": "order=rating",
};
const rowItems = {};
await Promise.all(Object.entries(rows).map(async ([label, query]) => {
  const p1 = await page(`${MAIN}/series/?${query}&page=1`);
  const items = cards(p1);
  assert.ok(items.length > 0, `${label} is empty`);
  rowItems[label] = items;
  if (/<a href="\?page=2[^"]*" class="r"/.test(p1)) {
    const items2 = cards(await page(`${MAIN}/series/?${query}&page=2`));
    assert.ok(items2.length > 0 && items2[0].url !== items[0].url, `${label} page 2 repeated page 1`);
    counts[label] = `${items.length}+${items2.length} (more)`;
  } else counts[label] = `${items.length} (single page)`;
}));
assert.ok(rowItems.Movies.every((c) => c.type === "Movie"), "Movies row has non-movies");

// Posters (raw HTML data-src on i*.wp.com).
const posters = [...latest1.slice(0, 2), ...rowItems["Korean Dramas"].slice(0, 2)].map((c) => c.poster);
for (const p of posters) {
  assert.match(p, /^https:\/\/i\d\.wp\.com\//, `unexpected poster host ${p}`);
  const r = await fetch(p, { headers: { "User-Agent": UA } });
  assert.ok(r.ok && r.headers.get("content-type").startsWith("image/"), `poster failed ${p}`);
}

// Search with pagination.
const search = async (q, n) => page(n > 1 ? `${MAIN}/page/${n}/?s=${encodeURIComponent(q)}` : `${MAIN}/?s=${encodeURIComponent(q)}`);
const s1 = await search("love", 1);
const s2 = await search("love", 2);
const sr1 = cards(s1), sr2 = cards(s2);
assert.ok(sr1.length > 0 && /next page-numbers/.test(s1), "search page 1 empty or unpaged");
assert.ok(sr2.length > 0 && sr2[0].url !== sr1[0].url, "search page 2 repeated page 1");
assert.ok(sr1.some((c) => /love/i.test(c.title)), "search results don't match the query");

// ---- details (mirrors load()) ----
const seriesLinkOf = (html) => (html.match(/class="nvs nvsc"><a href='([^']+\/series\/[^']+)'/) || [])[1];
function details(html) {
  const info = html.slice(html.indexOf('class="bigcontent'), html.indexOf('class="bixbox synp"'));
  const field = (k) => { const m = info.match(new RegExp(`<b>${k}:</b>([\\s\\S]*?)</span>`)); return m ? decode(m[1]) : null; };
  const casts = [...(info.match(/<b>Casts:<\/b>([\s\S]*?)<\/span>/)?.[1] || "").matchAll(/rel="tag">([^<]+)/g)].map((m) => decode(m[1]));
  const genres = [...(info.match(/class="genxed">([\s\S]*?)<\/div>/)?.[1] || "").matchAll(/rel="tag">([^<]+)/g)].map((m) => decode(m[1]));
  const synopsis = html.match(/class="bixbox synp"[\s\S]*?itemprop="description">([\s\S]*?)<\/div>/)?.[1] || "";
  const episodes = [...html.matchAll(/<li data-index="\d+">\s*<a href="([^"]+)"><div class="epl-num">([^<]*)<\/div><div class="epl-title">([^<]*)/g)]
    .map((m, i) => ({ url: m[1], num: parseInt(m[2]) || parseInt((m[3].match(/Episode\s+(\d+)/i) || [])[1]) || null, title: decode(m[3]), i }))
    .sort((a, b) => (a.num ?? 1e9) - (b.num ?? 1e9) || b.i - a.i);
  return {
    title: decode(info.match(/<h1 class="entry-title"[^>]*>([^<]+)/)?.[1] || ""),
    poster: info.match(/class="thumb"[\s\S]*?data-src="([^"]+)"/)?.[1],
    rating: parseFloat(info.match(/itemprop="ratingValue" content="([\d.]+)"/)?.[1]),
    status: field("Status"), type: field("Type"), country: field("Country"), released: field("Released"),
    casts, genres, plot: decode(synopsis.replace(/<p>\(Source:[^<]*<\/p>/, "")), episodes,
  };
}

// A drama, reached the way the app does: from an episode card on the home page.
const epCard = latest1.find((c) => c.type !== "Movie" && /-episode-\d+\/$/.test(c.url));
const epHtml = await page(epCard.url);
const showUrl = seriesLinkOf(epHtml);
assert.ok(showUrl, `${epCard.url}: no drama link`);
const show = details(await page(showUrl));
assert.ok(show.title && show.poster && show.plot.length > 40, `${showUrl}: missing title/poster/plot`);
assert.ok(show.genres.length > 0 && show.country, `${showUrl}: missing genres/country`);
assert.ok(show.episodes.length > 0, `${showUrl}: no episodes`);
const nums = show.episodes.map((e) => e.num);
assert.deepEqual(nums, [...nums].sort((a, b) => a - b), "episodes not ascending");
assert.ok(show.episodes.some((e) => e.url === epCard.url), "episode card not in drama's list");

// A popular drama for a full-list check.
const completed = rowItems["Popular Dramas"].find((c) => c.type !== "Movie");
const full = details(await page(completed.url));
const fullNums = full.episodes.map((e) => e.num);
// A drama known to show a rating and cast.
const rated = details(await page(`${MAIN}/series/100-days-of-deception-2026/`));
assert.ok(rated.rating > 0 && rated.casts.length > 0 && /\d{4}/.test(rated.released || ""), "rating/cast/release missing");
const missing = [];
for (let n = 1; n <= Math.max(...fullNums); n++) if (!fullNums.includes(n)) missing.push(n);

// A movie.
const movieCard = rowItems.Movies[0];
const movie = details(await page(movieCard.url));
assert.equal(movie.type, "Movie", `${movieCard.url}: type ${movie.type}`);
assert.equal(movie.episodes.length, 1, `${movieCard.url}: movie should have one video`);

// ---- streams (mirrors loadLinks()) ----
function embedsOf(html) {
  const out = [];
  for (const m of html.matchAll(/<option value="([A-Za-z0-9+/=]{16,})"[^>]*>([^<]*)/g)) {
    const iframe = Buffer.from(m[1], "base64").toString("utf8");
    const src = (iframe.match(/\ssrc="([^"]+)"/) || iframe.match(/data-src="([^"]+)"/) || [])[1];
    if (src) out.push({ label: m[2].trim(), src });
  }
  return out;
}
async function zumividsHosts(url) {
  const r = await fetchText(url, `${MAIN}/`);
  if (r.status !== 200) return [];
  return [...new Set([...r.text.matchAll(/data-video="(https?:[^"]+)"/g)].map((m) => m[1]))].filter((u) => !u.includes("zumivids."));
}
function guessZumivids(html, pageUrl) {
  const slug = (seriesLinkOf(html) || "").match(/\/series\/([^/]+)/)?.[1];
  const n = (pageUrl.match(/-episode-(\d+)/) || html.match(/<h1[^>]*>[^<]*Episode\s+(\d+)/i) || [])[1];
  return slug && n ? `${ZUMI}/embed/${slug}-ep-${n}.html` : null;
}
async function hostsFor(pageUrl) {
  const html = await page(pageUrl);
  const embeds = embedsOf(html);
  const hosts = [];
  let zumi = false;
  for (const e of embeds) {
    if (e.src.includes("zumivids.")) { zumi = true; hosts.push(...await zumividsHosts(e.src)); }
    else if (!e.src.includes("dramavideo.")) hosts.push(e.src);
  }
  let guessed = null;
  if (!zumi && (guessed = guessZumivids(html, pageUrl))) hosts.push(...await zumividsHosts(guessed));
  return { embeds, hosts: [...new Set(hosts)], guessed };
}

async function vidmoly(url) {
  const embed = url.replace("/w/", "/embed-");
  const html = await page(embed, `${ZUMI}/`);
  const sources = html.match(/sources\s*:\s*\[([\s\S]*?)]/)?.[1] || "";
  const master = sources.match(/file\s*:\s*['"]([^'"]+)['"]/)?.[1];
  assert.ok(master?.includes(".m3u8"), `${url}: no m3u8`);
  const ref = { Referer: new URL(embed).origin + "/" };
  const m = await (await fetch(master, { headers: { "User-Agent": UA, ...ref } })).text();
  assert.match(m, /#EXTM3U/, "vidmoly master not HLS");
  const variants = m.split("\n").filter((l) => l && !l.startsWith("#"));
  const res = [...m.matchAll(/RESOLUTION=\d+x(\d+)/g)].map((x) => x[1] + "p");
  const variant = new URL(variants.at(-1), master).href;
  const v = await (await fetch(variant, { headers: { "User-Agent": UA, ...ref } })).text();
  const minutes = [...v.matchAll(/#EXTINF:([\d.]+)/g)].reduce((s, x) => s + parseFloat(x[1]), 0) / 60;
  const seg = new URL(v.split("\n").find((l) => l && !l.startsWith("#")), variant).href;
  const segRes = await fetch(seg, { headers: { "User-Agent": UA, ...ref } });
  const bytes = (await segRes.arrayBuffer()).byteLength;
  assert.ok(segRes.ok && bytes > 10000, `vidmoly segment failed (${segRes.status}, ${bytes} bytes)`);
  return { minutes, res: [...new Set(res)].join("/") || "?", bytes };
}
async function rangeCheck(url, referer) {
  const r = await fetch(url, { headers: { "User-Agent": UA, Referer: referer, Range: "bytes=0-1023" } });
  const size = parseInt((r.headers.get("content-range") || "").split("/")[1]) || 0;
  await r.arrayBuffer();
  assert.ok(r.status === 206 && r.headers.get("content-type").startsWith("video/"), `${url}: ${r.status}`);
  return Math.round(size / 1048576);
}
async function streamtape(url) {
  const html = await page(url, `${ZUMI}/`);
  const line = [...html.matchAll(/botlink'\)\.innerHTML\s*=\s*([^\n;]+)/g)].at(-1)?.[1];
  const head = line.match(/^\s*'([^']*)'/)[1];
  let tail = line.match(/\(\s*'([^']*)'\s*\)/)[1];
  for (const m of line.matchAll(/\.substring\((\d+)\)/g)) tail = tail.slice(parseInt(m[1]));
  return rangeCheck(`https:${head}${tail}&stream=1`, "https://streamtape.com/");
}
function unpack(html) {  // P.A.C.K.E.R. word substitution (string transform only, nothing is executed)
  const m = html.match(/}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)/s);
  let [p, a, c, k] = [m[1], +m[2], +m[3], m[4].split("|")];
  const enc = (n) => (n >= a ? enc(Math.floor(n / a)) : "") + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % a];
  while (c--) if (k[c]) p = p.replace(new RegExp(`\\b${enc(c)}\\b`, "g"), k[c]);
  return p;
}
async function mixdrop(url) {
  const r = await fetchText(url.replace("/f/", "/e/"), `${ZUMI}/`);
  const wurl = unpack(r.text).match(/wurl\s*=\s*"([^"]+)"/)[1];
  return rangeCheck(wurl.startsWith("//") ? `https:${wurl}` : wurl, new URL(r.url).origin + "/");
}
async function play(hosts) {
  const results = {};
  for (const h of hosts) {
    const host = new URL(h).hostname;
    try {
      if (host.includes("vidmoly")) { const v = await vidmoly(h); results[host] = `${v.minutes.toFixed(1)} min HLS ${v.res}`; }
      else if (host.includes("streamtape")) results[host] = `${await streamtape(h)} MB mp4`;
      else if (host.includes("mixdrop") || host.includes("mxdrop")) results[host] = `${await mixdrop(h)} MB mp4`;
      else results[host] = "built-in extractor (not checked)";
    } catch (e) { results[host] = `FAILED: ${e.message}`; }
  }
  return results;
}

const epStreams = await hostsFor(show.episodes.at(-1).url);
assert.ok(epStreams.hosts.some((h) => h.includes("vidmoly.")), "episode has no vidmoly host");
const epPlay = await play(epStreams.hosts);
const vm = Object.entries(epPlay).find(([h]) => h.includes("vidmoly"))[1];
assert.ok(/^\d/.test(vm) && parseFloat(vm) >= 20, `vidmoly stream too short or failed: ${vm}`);

const movieStreams = await hostsFor(movie.episodes[0].url);
const moviePlay = await play(movieStreams.hosts);
assert.ok(Object.values(moviePlay).some((v) => /^\d/.test(v)), "movie has no working stream");

// An episode whose only server is dramavideo.se (skipped) -> zumivids mirror guess.
const DV_EPISODE = `${MAIN}/this-is-hyunmoo-road-episode-10/`;
const dv = await hostsFor(DV_EPISODE);
const dvNote = dv.embeds.map((e) => new URL(e.src).hostname).join(", ");
assert.ok(dv.hosts.length > 0, `${DV_EPISODE}: no playable hosts (embeds: ${dvNote})`);
const dvPlay = await play(dv.hosts.filter((h) => h.includes("vidmoly.")));

console.log(JSON.stringify({
  rows: counts,
  search: { love: `${sr1.length}+${sr2.length}`, first: sr1[0].title },
  posters: `${posters.length} checked OK`,
  drama: {
    title: show.title, status: show.status, country: show.country, rating: show.rating,
    genres: show.genres.join(", "), cast: show.casts.length, episodes: `${nums[0]}..${nums.at(-1)} (${nums.length})`,
  },
  popularDrama: { title: full.title, episodes: fullNums.length, missingNumbers: missing },
  ratedDrama: { title: rated.title, rating: rated.rating, cast: rated.casts.slice(0, 3).join(", "), released: rated.released, episodes: rated.episodes.length },
  movie: { title: movie.title, video: movie.episodes[0].url },
  episodeStreams: { page: show.episodes.at(-1).url, ...epPlay },
  movieStreams: moviePlay,
  dramavideoEpisode: { embeds: dvNote, mirror: dv.guessed, ...dvPlay },
}, null, 2));
