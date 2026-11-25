// Live smoke test for the FlixVision provider. Mirrors FlixvisionProvider: home rows and paging,
// search (/api/suggest), movie and TV details, seasons/episodes, and VixSrc / VidRift streams.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const MAIN = "https://flixvision.st";
const VIXSRC = "https://vixsrc.to";
const VIDRIFT = "https://embed.vidrift.net";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

const ROWS = [
  ["/trending", "Trending"], ["/new-releases", "New Releases"], ["/new-episodes", "New Episodes"],
  ["/movie", "Latest Movies"], ["/tv", "Latest TV Shows"], ["/top-movies-2026", "Top Movies 2026"],
  ["/top-tv-shows-2026", "Top TV Shows 2026"], ["/top-imdb", "Top IMDb"],
  ["/genre/action", "Action"], ["/genre/comedy", "Comedy"], ["/genre/drama", "Drama"],
  ["/genre/horror", "Horror"], ["/genre/thriller", "Thriller"], ["/genre/science-fiction", "Science Fiction"],
  ["/genre/animation", "Animation"], ["/genre/crime", "Crime"], ["/genre/romance", "Romance"],
  ["/genre/documentary", "Documentary"],
];

async function get(url, headers = {}, timeoutMs = 30000) {
  const response = await fetch(url, {
    headers: { "User-Agent": UA, ...headers },
    signal: AbortSignal.timeout(timeoutMs),
  });
  return { status: response.status, text: await response.text(), type: response.headers.get("content-type") };
}

async function page(url) {
  const { status, text } = await get(url, { Referer: `${MAIN}/` });
  assert.equal(status, 200, `${url}: HTTP ${status}`);
  assert.ok(!/<title>Quick check/i.test(text), `${url}: hit the site's browser check`);
  return text;
}

const decode = (s) => s.replace(/&amp;/g, "&").replace(/&#0?39;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">");
const strip = (s) => decode(s.replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();

// Same fields as FlixvisionProvider.toCard()
function cards(html) {
  const out = [];
  for (const m of html.matchAll(/<a class="bf-card[^"]*" href="(\/watch\/[^"?]+)[^"]*"[^>]*>([\s\S]*?)<\/a>/g)) {
    const body = m[2];
    const img = body.match(/<img[^>]*>/)?.[0] ?? "";
    const attr = (n) => img.match(new RegExp(`${n}="([^"]*)"`))?.[1] ?? "";
    const poster = [attr("data-lsrc"), attr("data-src"), attr("src")].find((x) => x && !x.startsWith("data:"));
    const raw = strip(body.match(/class="bf-card__name">([\s\S]*?)<\/span>/)?.[1] ?? body.match(/class="bf-card__title">([\s\S]*?)<\/div>/)?.[1] ?? "");
    const title = raw.replace(/\s*\(\d{4}\)\s*$/, "").replace(/\s+-\s+Season\s+\d+$/, "");
    out.push({ url: MAIN + m[1], title, poster, tv: m[1].startsWith("/watch/tv-") });
  }
  return [...new Map(out.map((c) => [c.url, c])).values()];
}
const hasNext = (html, p) => new RegExp(`class="page-link" href="[^"]*page=${p + 1}"`).test(html);

// ---- Home rows + paging ----
const firstPages = {};
for (const [path, label] of ROWS) {
  const html = await page(MAIN + path);
  const list = cards(html);
  assert.ok(list.length >= 10, `${label}: only ${list.length} items`);
  assert.ok(list.every((c) => c.title && c.poster), `${label}: card without title or poster`);
  firstPages[path] = list;
  console.log(`row ${label.padEnd(18)} ${list.length} items, next page: ${hasNext(html, 1)}`);
}
for (const path of ["/movie", "/tv", "/genre/action"]) {
  const html = await page(`${MAIN}${path}?page=2`);
  const list = cards(html);
  assert.ok(list.length >= 10, `${path} page 2: ${list.length} items`);
  const overlap = list.filter((c) => firstPages[path].some((f) => f.url === c.url)).length;
  assert.ok(overlap < list.length / 2, `${path} page 2 repeats page 1`);
  console.log(`paging ${path} p2: ${list.length} items (${overlap} overlap), next page: ${hasNext(html, 2)}`);
}

// Posters load
for (const c of [firstPages["/movie"][0], firstPages["/tv"][0], firstPages["/trending"].at(-1)]) {
  const r = await fetch(c.poster, { headers: { "User-Agent": UA } });
  assert.equal(r.status, 200, `poster ${c.poster}`);
  assert.match(r.headers.get("content-type") ?? "", /^image\//);
  await r.arrayBuffer();
}
console.log("posters load");

// ---- Search (site autocomplete API; the full results page is behind a browser check) ----
const search = JSON.parse((await get(`${MAIN}/api/suggest?q=${encodeURIComponent("batman")}`, { Referer: `${MAIN}/` })).text);
assert.ok(Array.isArray(search) && search.length > 0, "search returned nothing");
assert.ok(search.some((x) => /batman/i.test(x.title)), "search results unrelated");
console.log(`search "batman": ${search.length} results: ${search.slice(0, 4).map((x) => `${x.title} (${x.type} ${x.year})`).join(", ")}`);

// ---- Details (mirrors FlixvisionProvider.load) ----
function details(html) {
  const ldRaw = [...html.matchAll(/<script type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)]
    .map((m) => { try { return JSON.parse(m[1]); } catch { return null; } })
    .find((x) => x && (x["@type"] === "Movie" || x["@type"] === "TVSeries")) ?? {};
  const meta = [...(html.match(/class="metadata set">([\s\S]*?)<\/div>/)?.[1] ?? "").matchAll(/<span[^>]*>([^<]*)<\/span>/g)].map((m) => m[1].trim());
  const row = (label) => html.match(new RegExp(`<span class="lbl">${label}</span>([\\s\\S]*?)</li>`))?.[1] ?? "";
  return {
    title: ldRaw.name,
    tmdb: html.match(/id="cm-root"[^>]*data-id="(\d+)"/)?.[1],
    poster: html.match(/class="dx-poster"><img[^>]*src="([^"]+)"/)?.[1]?.replace("/w342/", "/w500/"),
    plot: strip(html.match(/class="description[^"]*">([\s\S]*?)<\/div>/)?.[1] ?? ""),
    year: meta.find((x) => /^\d{4}$/.test(x)),
    rating: ldRaw.aggregateRating?.ratingValue,
    duration: meta.map((x) => x.match(/^(\d+)\s*min/)?.[1]).find(Boolean),
    tags: [...row("Genres").matchAll(/<a[^>]*>([^<]+)<\/a>/g)].map((m) => decode(m[1])),
    cast: [...row("Cast").matchAll(/<a[^>]*>([^<]+)<\/a>/g)].map((m) => decode(m[1])),
    recommendations: cards(html).length,
  };
}

function checkDetails(d, label) {
  for (const key of ["title", "tmdb", "poster", "plot", "year", "rating", "duration"]) assert.ok(d[key], `${label}: missing ${key}`);
  assert.ok(d.tags.length > 0, `${label}: no tags`);
  assert.ok(d.cast.length > 0, `${label}: no cast`);
  console.log(`${label}: "${d.title}" tmdb=${d.tmdb} ${d.year} ★${d.rating} ${d.duration} min [${d.tags.join(", ")}] cast: ${d.cast.slice(0, 3).join(", ")}; ${d.recommendations} recommendations`);
}

// Detail pages that Cloudflare has not cached get the site's "Quick check" browser challenge.
// The provider does not solve it: it falls back to /api/suggest + the player's /api/go redirect.
async function fetchDetail(url) {
  const { status, text } = await get(url, { Referer: `${MAIN}/` });
  return status === 200 && !/<title>Quick check/i.test(text) ? text : null;
}
async function tmdbFromPlayer(slug, tv) {
  const { text } = await get(`${MAIN}/api/go?t=${tv ? "tv" : "movie"}&slug=${slug}&x=0&st=0${tv ? "&s=1&e=1" : ""}`, { Referer: `${MAIN}/watch/${slug}` });
  const target = decode(text.match(/url=([^"'>\s]+)/)?.[1] ?? "");
  return target.match(/\/(?:movie|tv)\/(\d+)/)?.[1] ?? target.match(/[?&](?:tmdb|video_id|id)=(\d+)/)?.[1];
}

// Full details: the first listed movie whose page is served without the challenge.
let fullMovie = null;
for (const c of [...firstPages["/trending"], ...firstPages["/movie"]].filter((c) => !c.tv).slice(0, 12)) {
  const html = await fetchDetail(c.url);
  if (html) { fullMovie = details(html); break; }
}
assert.ok(fullMovie, "every movie detail page was behind the browser check");
checkDetails(fullMovie, "movie (page)");

// ---- TMDB mirror (mirrors tmdbDetails / trailerOf): cast photos + roles and trailer on the page
// path, and the whole details on the challenged-page fallback (loadWithoutPage). ----
async function mirror(tmdbId, tv) {
  const { text } = await get(`https://api.framextv.cc/api/details?id=${tmdbId}&type=${tv ? "tv" : "movie"}`, { Referer: "https://framextv.cc/" }, 15000);
  const d = JSON.parse(text);
  const cast = (d.cast ?? []).filter((c) => c.name);
  return {
    title: d.title, plot: d.overview, poster: d.poster, backdrop: d.backdrop, year: d.year, rating: d.rating,
    runtime: d.runtime, tags: d.genres ?? [], cast,
    trailer: d.trailer?.key ?? d.trailers?.find((t) => !t.site || t.site === "YouTube")?.key,
  };
}
{
  const m = await mirror(fullMovie.tmdb, false);
  const byName = new Map(m.cast.map((c) => [c.name, c]));
  const photos = fullMovie.cast.filter((n) => byName.get(n)?.profile).length;
  console.log(`movie (page) + mirror: cast photos ${photos}/${fullMovie.cast.length}, trailer ${m.trailer ? "yes" : "no"}`);
  assert.ok(photos > 0, "no cast photos matched from the mirror");
}
{
  // Way of the Warrior Kid (TMDB 1280115): shown through the fallback when its page is challenged.
  const hit = JSON.parse((await get(`${MAIN}/api/suggest?q=${encodeURIComponent("way of the warrior kid")}`, { Referer: `${MAIN}/` })).text)
    .find((x) => x.type === "movie" && x.title === "Way of the Warrior Kid");
  assert.ok(hit, "Way of the Warrior Kid not found");
  const id = await tmdbFromPlayer(hit.slug, false);
  assert.equal(id, "1280115", "/api/go gave the wrong TMDB id");
  const m = await mirror(id, false);
  for (const key of ["title", "plot", "poster", "backdrop", "year", "rating", "runtime", "trailer"]) assert.ok(m[key], `fallback: missing ${key}`);
  assert.ok(m.tags.length > 0 && m.cast.length > 0, "fallback: no tags or cast");
  const photos = m.cast.filter((c) => c.profile).length;
  assert.ok(photos > 0, "fallback: no cast photos");
  console.log(`fallback "${m.title}" ${m.year} ★${m.rating} ${m.runtime} min [${m.tags.join(", ")}] cast ${m.cast.length} (${photos} photos), backdrop, trailer ${m.trailer}`);
}

// A well-known older movie for the stream test (VixSrc carries older titles; very new releases
// may only be on hosts that are not usable). Its page may or may not be challenged.
const movieSearch = JSON.parse((await get(`${MAIN}/api/suggest?q=${encodeURIComponent("fight club")}`, { Referer: `${MAIN}/` })).text);
const movieHit = movieSearch.find((x) => x.type === "movie" && x.title === "Fight Club");
assert.ok(movieHit, "Fight Club not found");
const movieHtml = await fetchDetail(MAIN + movieHit.url);
const movie = movieHtml ? details(movieHtml) : { title: movieHit.title, tmdb: await tmdbFromPlayer(movieHit.slug, false) };
console.log(`movie "${movie.title}": page ${movieHtml ? "served" : "challenged -> fallback"}, tmdb=${movie.tmdb}`);
assert.equal(await tmdbFromPlayer(movieHit.slug, false), movie.tmdb, "/api/go and the page disagree on the TMDB id");
assert.ok(movie.tmdb, "no TMDB id for Fight Club");

// A long TV show with many seasons
const tvSearch = JSON.parse((await get(`${MAIN}/api/suggest?q=${encodeURIComponent("murdoch mysteries")}`, { Referer: `${MAIN}/` })).text);
const tvHit = tvSearch.find((x) => x.type === "tv");
assert.ok(tvHit, "Murdoch Mysteries not found");
const tvHtml = (await fetchDetail(MAIN + tvHit.url)) ?? "";
const tv = tvHtml ? details(tvHtml) : { title: tvHit.title, tmdb: await tmdbFromPlayer(tvHit.slug, true) };
if (tvHtml) checkDetails(tv, "tv (page)");
else console.log(`tv "${tv.title}": page challenged -> fallback, tmdb=${tv.tmdb}`);
assert.ok(tv.tmdb, "no TMDB id for the show");

// Seasons from the page, episode metadata per season from VidRift (as in buildEpisodes)
let seasons = [...tvHtml.matchAll(/<a class="we-s[^"]*" href="[^"]*\?s=(\d+)[^"]*" title="Season \d+">S\d+<small>(\d+) ep<\/small>/g)]
  .map((m) => ({ season: +m[1], count: +m[2] }));
if (!seasons.length) {
  const j = JSON.parse((await get(`${VIDRIFT}/api/episodes/${tv.tmdb}/1?v=2`, { Referer: `${VIDRIFT}/embed/tv/${tv.tmdb}/1/1` })).text);
  seasons = j.seasons.map((x) => ({ season: x.season, count: x.episodes }));
}
assert.ok(seasons.length >= 10, `only ${seasons.length} seasons`);
const pageEpisodes = [...tvHtml.matchAll(/class="we-c[^"]*" data-n="(\d+)"[^>]*>[\s\S]*?class="we-ct">([^<]*)</g)].map((m) => [+m[1], decode(m[2])]);
assert.ok(!tvHtml || pageEpisodes.length > 0, "no episodes on the page");
let totalEpisodes = 0;
let named = 0;
const seasonMeta = await Promise.all(seasons.map(async ({ season }) => {
  const r = await get(`${VIDRIFT}/api/episodes/${tv.tmdb}/${season}?v=2`, { Referer: `${VIDRIFT}/embed/tv/${tv.tmdb}/${season}/1` }, 15000).catch(() => null);
  try { const j = JSON.parse(r?.text ?? ""); return j.season === season ? j.episodes : []; } catch { return []; }
}));
seasons.forEach(({ season, count }, i) => {
  const numbers = new Set([...Array(count).keys()].map((n) => n + 1));
  seasonMeta[i].forEach((e) => numbers.add(e.episode));
  totalEpisodes += numbers.size;
  named += seasonMeta[i].filter((e) => e.name).length;
});
const s3 = seasonMeta[seasons.findIndex((s) => s.season === 3)] ?? [];
console.log(`tv seasons: ${seasons.length}, episodes: ${totalEpisodes} (${named} with titles from metadata); S1 on page: ${pageEpisodes.length} eps; S3E5 = "${s3.find((e) => e.episode === 5)?.name}"`);
assert.ok(totalEpisodes >= 100, "episode list too short");

// ---- Streams (mirrors vixsrcCandidates / vidriftCandidates / isPlayable) ----
async function vixsrc(path) {
  const api = await get(`${VIXSRC}/api/${path}`, { Referer: `${VIXSRC}/${path}` });
  assert.equal(api.status, 200, `vixsrc api ${path}: HTTP ${api.status}`);
  const embed = `${VIXSRC}${JSON.parse(api.text).src}`;
  const html = (await get(embed, { Referer: `${VIXSRC}/${path}` })).text;
  const token = html.match(/['"]token['"]\s*:\s*['"]([^'"]+)/)[1];
  const expires = html.match(/['"]expires['"]\s*:\s*['"]([^'"]+)/)[1];
  const fhd = /canPlayFHD\s*=\s*true/.test(html);
  const streams = JSON.parse(html.match(/window\.streams\s*=\s*(\[.*?\]);/)[1]);
  return streams.map((s) => ({ label: `VixSrc ${s.name}`, url: `${s.url}${s.url.includes("?") ? "&" : "?"}token=${token}&expires=${expires}${fhd ? "&h=1" : ""}&lang=en`, referer: `${VIXSRC}/` }));
}

async function vidrift(path) {
  const embed = `${VIDRIFT}/embed/${path}`;
  const r = await get(embed, { Referer: `${MAIN}/`, "Sec-Fetch-Dest": "iframe" }, 15000);
  const meta = r.text.match(/var embedMeta\s*=\s*(\{.*?\});\s*<\/script>/s)?.[1];
  if (!meta) return { links: [], note: `embed page HTTP ${r.status} without player data` };
  const m = JSON.parse(meta);
  let streams = m.orionStreams;
  if (!streams?.length) {
    const s = await get(`${VIDRIFT}/api/source/${path}?token=${encodeURIComponent(m.playbackToken)}&provider=orion&parent=${encodeURIComponent(m.parent || "flixvision.st")}`,
      { Referer: embed, Accept: "*/*", "Sec-Fetch-Site": "same-origin", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty" }, 25000);
    try { streams = JSON.parse(s.text).streams; } catch { return { links: [], note: `source API HTTP ${s.status}` }; }
  }
  return { links: (streams ?? []).filter((s) => s.type === "hls").map((s) => ({ label: `VidRift ${s.name}`, url: s.url.startsWith("http") ? s.url : VIDRIFT + s.url, referer: `${VIDRIFT}/` })), note: "" };
}

async function measure(link) {
  const master = await get(link.url, { Referer: link.referer }, 15000);
  if (!master.text.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `master HTTP ${master.status}` };
  const lines = master.text.split("\n").map((l) => l.trim());
  const variants = lines.filter((l) => l && !l.startsWith("#"));
  const resolutions = [...master.text.matchAll(/RESOLUTION=(\d+x\d+)/g)].map((m) => m[1]);
  const variant = new URL(variants[0], link.url).href;
  const playlist = (await get(variant, { Referer: link.referer })).text;
  const segs = [...playlist.matchAll(/#EXTINF:([\d.]+)/g)].map((m) => +m[1]);
  const minutes = segs.reduce((a, b) => a + b, 0) / 60;
  const segUrls = playlist.split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
  const seg = await fetch(new URL(segUrls[Math.floor(segUrls.length / 2)], variant).href, { headers: { "User-Agent": UA, Referer: link.referer } });
  const bytes = (await seg.arrayBuffer()).byteLength;
  const key = playlist.match(/#EXT-X-KEY:METHOD=([A-Z0-9-]+)/)?.[1] ?? "none";
  return { ok: seg.ok && bytes > 10000, minutes, segments: segs.length, resolutions, key, segStatus: seg.status, bytes, firstSeg: segUrls[0].slice(0, 60) };
}

async function streams(path, label) {
  const [vx, vr] = await Promise.all([vixsrc(path).catch((e) => { console.log(`  ${label} VixSrc failed: ${e.message}`); return []; }), vidrift(path).catch((e) => ({ links: [], note: e.message }))]);
  if (vr.note) console.log(`  ${label} VidRift: not available to this client (${vr.note})`);
  const all = [...vx, ...vr.links];
  const results = await Promise.all(all.map(async (l) => ({ ...l, ...(await measure(l).catch((e) => ({ ok: false, why: e.message }))) })));
  for (const r of results) {
    console.log(`  ${label} ${r.label}: ${r.ok ? `${r.minutes.toFixed(1)} min, ${r.segments} segs, ${r.resolutions.join("/")}, key ${r.key}, mid segment HTTP ${r.segStatus} ${r.bytes} B` : `dead (${r.why})`}`);
  }
  const live = results.filter((r) => r.ok);
  assert.ok(live.length > 0, `${label}: no working stream`);
  return live;
}

console.log("streams:");
const movieLive = await streams(`movie/${movie.tmdb}`, "movie");
assert.ok(movieLive.every((r) => r.minutes > 120), "movie stream is not full length");
const tvLive = await streams(`tv/${tv.tmdb}/3/5`, "tv S3E5");
assert.ok(tvLive.every((r) => r.minutes > 20), "episode stream is not full length");
const tvLive2 = await streams(`tv/${tv.tmdb}/1/1`, "tv S1E1");
assert.notEqual(tvLive[0].firstSeg, tvLive2[0].firstSeg, "different episodes returned the same stream");
assert.notEqual(movieLive[0].firstSeg, tvLive[0].firstSeg, "different titles returned the same stream");

console.log("smoke test passed");
