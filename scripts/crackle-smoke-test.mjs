// Live smoke test for the Crackle provider. Mirrors CrackleProvider: TMDB catalogue (the key from
// crackle.beauty's bundle), VixSrc availability filtering, details, seasons/episodes, and the
// VixSrc api → embed → master playlist → variant → segment chain.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";
import crypto from "node:crypto";

const TMDB_API = "https://api.themoviedb.org/3";
const TMDB_IMAGE = "https://image.tmdb.org/t/p/";
const TMDB_KEY = "e1a8efff4415028c5c266b3fcd50db6e";
const VIX = "https://vixsrc.to";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
const VIX_HEADERS = { "User-Agent": UA, Referer: `${VIX}/` };
const today = new Date().toISOString().slice(0, 10);

async function fetchOk(url, headers = {}) {
  const response = await fetch(url, { headers: { "User-Agent": UA, ...headers } });
  assert.ok(response.ok, `${url.replace(/token=[^&]+/, "token=…").replace(TMDB_KEY, "KEY")}: HTTP ${response.status}`);
  return response;
}

async function tmdb(path, params = {}) {
  const query = new URLSearchParams({ api_key: TMDB_KEY, language: "en-US", ...params });
  return (await fetchOk(`${TMDB_API}/${path}?${query}`, { Referer: "https://crackle.beauty/" })).json();
}

// ---- Availability lists (CrackleProvider.availability) ----
const [movieIds, showIds] = await Promise.all(["movie", "tv"].map(async (type) => {
  const list = await (await fetchOk(`${VIX}/api/list/${type}/`, VIX_HEADERS)).json();
  return new Set(list.map((x) => x.tmdb_id));
}));
assert.ok(movieIds.size > 10000 && showIds.size > 3000, "VixSrc availability lists look too small");

function playable(results, defaultType) {
  return results.flatMap((item) => {
    const type = item.media_type ?? defaultType ?? ("title" in item ? "movie" : "tv");
    if (type !== "movie" && type !== "tv") return [];
    if (!(type === "movie" ? movieIds : showIds).has(item.id)) return [];
    return [{ type, id: item.id, title: item.title ?? item.name, poster: item.poster_path && `${TMDB_IMAGE}w342${item.poster_path}` }];
  });
}
const hasNext = (json, page) => page < Math.min(json.total_pages ?? 0, 500);

// ---- Home rows (CrackleProvider.mainPage) ----
const rows = {
  "Trending Today": ["trending/all/day", {}, null],
  "Trending This Week": ["trending/all/week", {}, null],
  "Popular Movies": ["movie/popular", {}, "movie"],
  "Popular TV Shows": ["tv/popular", {}, "tv"],
  "Newest Movies": ["discover/movie", { sort_by: "primary_release_date.desc", "primary_release_date.lte": today, "vote_count.gte": "20" }, "movie"],
  "Newest TV Shows": ["discover/tv", { sort_by: "first_air_date.desc", "first_air_date.lte": today, "vote_count.gte": "20" }, "tv"],
  "Top Rated Movies": ["movie/top_rated", {}, "movie"],
  "Top Rated TV Shows": ["tv/top_rated", {}, "tv"],
};
const posters = [];
for (const [label, [path, params, type]] of Object.entries(rows)) {
  const p1 = await tmdb(path, { ...params, page: "1" });
  const p2 = await tmdb(path, { ...params, page: "2" });
  const items1 = playable(p1.results, type);
  const items2 = playable(p2.results, type);
  assert.ok(items1.length >= 5, `${label}: only ${items1.length} playable items on page 1`);
  assert.ok(items2.length > 0, `${label}: page 2 empty`);
  assert.ok(hasNext(p1, 1), `${label}: no next page`);
  assert.ok(!items2.some((b) => items1.some((a) => a.id === b.id && a.type === b.type)) || items2[0].id !== items1[0].id,
    `${label}: page 2 repeats page 1`);
  posters.push(items1[0].poster);
  console.log(`row ok  ${label.padEnd(20)} p1=${items1.length}/${p1.results.length} p2=${items2.length}/${p2.results.length} pages=${Math.min(p1.total_pages, 500)}  e.g. ${items1[0].title}`);
}
for (const poster of posters.filter(Boolean).slice(0, 4)) {
  const r = await fetchOk(poster);
  assert.match(r.headers.get("content-type") ?? "", /^image\//, `poster not an image: ${poster}`);
}
console.log("posters ok");

// ---- Search with paging ----
const s1 = await tmdb("search/multi", { query: "batman", page: "1", include_adult: "false" });
const s2 = await tmdb("search/multi", { query: "batman", page: "2", include_adult: "false" });
const found1 = playable(s1.results, null);
const found2 = playable(s2.results, null);
assert.ok(found1.some((x) => /batman/i.test(x.title)), "search found no Batman");
assert.ok(hasNext(s1, 1) && found2.length > 0, "search page 2 empty");
console.log(`search ok  batman p1=${found1.length} p2=${found2.length} pages=${s1.total_pages}`);

// ---- Details ----
const movie = await tmdb("movie/550", { append_to_response: "credits,external_ids,videos,recommendations" });
assert.equal(movie.title, "Fight Club");
assert.ok(movie.runtime > 100 && movie.vote_average > 5 && movie.genres.length && movie.credits.cast.length >= 5, "movie details incomplete");
await fetchOk(`${TMDB_IMAGE}w500${movie.poster_path}`);
console.log(`movie ok  ${movie.title} (${movie.release_date.slice(0, 4)}) ${movie.runtime} min, ${movie.vote_average}/10, ${movie.genres.map((g) => g.name).join(", ")}; cast ${movie.credits.cast.slice(0, 3).map((c) => c.name).join(", ")}`);

const showId = 1396; // Breaking Bad
const show = await tmdb(`tv/${showId}`, { append_to_response: "credits,external_ids,videos,recommendations" });
const seasonNumbers = show.seasons.map((s) => s.season_number).filter((n) => n > 0);
const episodes = [];
for (let i = 0; i < seasonNumbers.length; i += 20) {
  const chunk = seasonNumbers.slice(i, i + 20);
  const json = await tmdb(`tv/${showId}`, { append_to_response: chunk.map((n) => `season/${n}`).join(",") });
  for (const n of chunk) {
    for (const ep of json[`season/${n}`].episodes) {
      if (ep.air_date && ep.air_date <= today) episodes.push({ season: n, episode: ep.episode_number, name: ep.name });
    }
  }
}
const perSeason = seasonNumbers.map((n) => episodes.filter((e) => e.season === n).length);
assert.deepEqual(perSeason, [7, 13, 13, 13, 16], "Breaking Bad season sizes");
assert.ok(episodes.every((e, i) => i === 0 || e.season > episodes[i - 1].season || e.episode === episodes[i - 1].episode + 1), "episode numbering");
console.log(`tv ok  ${show.name}: ${seasonNumbers.length} seasons, episodes per season ${perSeason.join("/")}, cast ${show.credits.cast.slice(0, 2).map((c) => c.name).join(", ")}`);

// Many-season show: more than 20 seasons needs two batched requests.
const simpsons = await tmdb("tv/456");
assert.ok(simpsons.seasons.filter((s) => s.season_number > 0).length > 20, "Simpsons should have >20 seasons");

// ---- Streams (CrackleProvider.loadLinks) ----
async function probe(masterUrl) {
  const master = await (await fetchOk(masterUrl, VIX_HEADERS)).text();
  assert.match(master, /#EXT-X-STREAM-INF/);
  const variantUrl = master.split("\n").filter((l) => l.startsWith("http")).at(-1);
  const variant = await (await fetchOk(variantUrl, VIX_HEADERS)).text();
  const segment = variant.split("\n").find((l) => l.startsWith("http"));
  const seg = await fetch(segment, { headers: { ...VIX_HEADERS, Range: "bytes=0-1023" } });
  assert.ok(seg.status === 200 || seg.status === 206, `segment HTTP ${seg.status}`);
  await seg.body?.cancel();
  return { master, variant };
}

async function links(path) {
  const api = await (await fetchOk(`${VIX}/api/${path}`, VIX_HEADERS)).json();
  const embed = await (await fetchOk(VIX + api.src, VIX_HEADERS)).text();
  const token = embed.match(/['"]token['"]\s*:\s*['"]([^'"]+)['"]/)[1];
  const expires = embed.match(/['"]expires['"]\s*:\s*['"](\d+)['"]/)[1];
  const fhd = embed.includes("window.canPlayFHD = true");
  const servers = JSON.parse(embed.match(/window\.streams\s*=\s*(\[.*?]);/)[1]);
  assert.ok(servers.length >= 1, "no servers");
  const results = await Promise.all(servers.map(async (s) => {
    const url = `${s.url}${s.url.includes("?") ? "&" : "?"}token=${token}&expires=${expires}&lang=en${fhd ? "&h=1" : ""}`;
    try { return { name: s.name, url, ...(await probe(url)) }; } catch (e) { return { name: s.name, url, error: e.message }; }
  }));
  return results;
}

function duration(variant) {
  return [...variant.matchAll(/#EXTINF:([\d.]+)/g)].reduce((sum, m) => sum + Number(m[1]), 0);
}

async function checkStream(label, path) {
  const results = await links(path);
  const alive = results.filter((r) => !r.error);
  assert.ok(alive.length > 0, `${label}: no working server (${results.map((r) => r.error).join("; ")})`);
  const { master, variant, url } = alive[0];
  const minutes = duration(variant) / 60;
  assert.ok(minutes > 20, `${label}: only ${minutes.toFixed(1)} min`);
  assert.match(master, /NAME="English",DEFAULT=YES/, `${label}: English audio is not the default`);
  const heights = [...master.matchAll(/RESOLUTION=\d+x(\d+)/g)].map((m) => Number(m[1]));

  // Standard HLS AES-128: fetch the key and decrypt the first segment; it must be MPEG-TS.
  const keyTag = variant.match(/#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9A-Fa-f]+))?/);
  let decrypted = "clear";
  if (keyTag) {
    const key = Buffer.from(await (await fetchOk(new URL(keyTag[1], url).href, VIX_HEADERS)).arrayBuffer());
    const segUrl = variant.split("\n").find((l) => l.startsWith("http"));
    const seg = Buffer.from(await (await fetchOk(segUrl, VIX_HEADERS)).arrayBuffer());
    const iv = keyTag[2] ? Buffer.from(keyTag[2], "hex") : Buffer.alloc(16);
    const decipher = crypto.createDecipheriv("aes-128-cbc", key, iv);
    const ts = Buffer.concat([decipher.update(seg), decipher.final()]);
    assert.equal(ts[0], 0x47, `${label}: decrypted segment is not MPEG-TS`);
    decrypted = "AES-128 decrypts to MPEG-TS";
  }

  // Subtitles are passed through as the VTT files behind each subtitle playlist.
  const subs = [...master.matchAll(/TYPE=SUBTITLES[^\n]*?NAME="([^"]+)"[^\n]*?URI="([^"]+)"/g)];
  let subNote = "none";
  if (subs.length) {
    const playlist = await (await fetchOk(subs[0][2], VIX_HEADERS)).text();
    const vtt = playlist.split("\n").find((l) => l.trim() && !l.startsWith("#")).trim();
    const body = await (await fetchOk(vtt, VIX_HEADERS)).text();
    assert.ok(body.startsWith("WEBVTT"), `${label}: subtitle is not VTT`);
    subNote = `${subs.length} (${subs.map((m) => m[1]).join(", ")})`;
  }
  const id = url.match(/playlist\/(\d+)/)[1];
  console.log(`stream ok  ${label}: ${minutes.toFixed(1)} min, servers ${results.map((r) => `${r.name}=${r.error ? "dead" : "ok"}`).join(" ")}, up to ${Math.max(...heights)}p, ${decrypted}, subtitles ${subNote}`);
  return id;
}

const ids = [
  await checkStream("Fight Club", "movie/550"),
  await checkStream("Inception", "movie/27205"),
  await checkStream("Breaking Bad S2E3", `tv/${showId}/2/3`),
];
assert.equal(new Set(ids).size, ids.length, `titles share a stream: ${ids}`);

const missing = await fetch(`${VIX}/api/movie/999999999`, { headers: VIX_HEADERS });
assert.equal(missing.status, 404, "unknown title should 404");
console.log("all checks passed");
