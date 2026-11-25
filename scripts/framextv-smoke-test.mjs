// Live smoke test for the frameXTV provider. Mirrors FramextvProvider: the site's own backend
// (api.framextv.cc) for home rows (/api/discover), search, details and seasons, then /api/stream
// for every available provider, checking the HLS/MP4 sources are real full-length media.
// Run with Node.js 18+: node scripts/framextv-smoke-test.mjs
import assert from "node:assert/strict";

const SITE = "https://framextv.cc";
const API = "https://api.framextv.cc";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
const HEADERS = { "User-Agent": UA, Referer: `${SITE}/`, Origin: SITE };
const today = new Date().toISOString().slice(0, 10);

async function get(url, headers = {}, ms = 30000) {
  return fetch(url, { headers: { ...HEADERS, ...headers }, signal: AbortSignal.timeout(ms) });
}

async function api(path, params = {}, ms = 30000) {
  const response = await get(`${API}/api/${path}?${new URLSearchParams(params)}`, {}, ms);
  assert.ok(response.ok || response.status === 404, `${path}: HTTP ${response.status}`);
  return response.json();
}

function results(json, defaultType) {
  return (json.results ?? []).filter((i) => ["movie", "tv"].includes(i.type ?? defaultType) && /^\d+$/.test(String(i.id)));
}

// ---- Home rows (FramextvProvider.mainPage), with paging ----
const rows = [
  ["movie", "trending"], ["movie", "popular"], ["movie", "now_playing"], ["movie", "top_rated"],
  ["tv", "trending"], ["tv", "popular"], ["tv", "on_the_air"], ["tv", "top_rated"],
];
for (const [type, category] of rows) {
  const p1 = await api("discover", { type, category, page: 1 });
  const p2 = await api("discover", { type, category, page: 2 });
  const a = results(p1, type), b = results(p2, type);
  assert.ok(a.length >= 10 && b.length >= 10, `${type}/${category}: too few results (${a.length}, ${b.length})`);
  assert.ok(p1.totalPages > 1, `${type}/${category}: no further pages`);
  assert.ok(a.every((x) => x.type === type), `${type}/${category}: mixed types`);
  assert.ok(a.every((x) => x.poster?.startsWith("https://")), `${type}/${category}: missing posters`);
  const overlap = a.filter((x) => b.some((y) => y.id === x.id)).length;
  assert.ok(overlap < a.length / 2, `${type}/${category}: page 2 repeats page 1`);
  console.log(`row ok     ${type}/${category}: ${a.length}+${b.length} items, ${p1.totalPages} pages, e.g. ${a[0].title}`);
}

// ---- Search with paging ----
const s1 = await api("search", { q: "love", page: 1, per_page: 24 });
const s2 = await api("search", { q: "love", page: 2, per_page: 24 });
assert.ok(results(s1).length >= 10 && results(s2).length >= 10 && s1.totalPages > 2, "search paging");
assert.notEqual(results(s1)[0].id, results(s2)[0].id, "search page 2 repeats page 1");
const bb = results(await api("search", { q: "breaking bad", page: 1, per_page: 24 }));
const show = bb.find((x) => x.type === "tv" && x.title === "Breaking Bad");
assert.ok(show, "search did not find Breaking Bad");
console.log(`search ok  "love": ${s1.totalPages} pages; "breaking bad" -> tv/${show.id}`);

// ---- Movie details ----
const movie = await api("details", { id: 27205, type: "movie" });
assert.equal(movie.title, "Inception");
assert.ok(movie.poster && movie.overview && movie.year && +movie.rating > 0 && movie.runtime > 100, "movie fields");
assert.ok(movie.genres.length && movie.cast.length >= 5, "movie genres/cast");
console.log(`movie ok   ${movie.title} (${movie.year}) ${movie.rating}/10, ${movie.runtime} min, ${movie.genres.join("/")}, cast ${movie.cast.slice(0, 3).map((c) => c.name).join(", ")}`);

// ---- TV details, seasons and episodes ----
const tv = await api("details", { id: show.id, type: "tv" });
assert.equal(tv.type, "tv");
assert.equal(tv.title, "Breaking Bad", "details without type=tv would return a movie");
const seasonNumbers = tv.seasons.map((s) => s.season_number).filter((n) => n > 0);
assert.equal(seasonNumbers.length, 5, `expected 5 seasons, got ${seasonNumbers}`);
let total = 0;
for (const n of seasonNumbers) {
  const season = await api("season", { id: show.id, season: n });
  const eps = season.episodes.filter((e) => !e.air_date || e.air_date <= today);
  assert.ok(eps.length >= 7, `season ${n}: ${eps.length} episodes`);
  assert.ok(eps.every((e) => e.season_number === n && e.episode_number > 0), `season ${n}: bad numbering`);
  assert.deepEqual(eps.map((e) => e.episode_number), eps.map((_, i) => i + 1), `season ${n}: episodes not 1..N`);
  total += eps.length;
}
assert.equal(total, 62, `Breaking Bad should have 62 episodes, got ${total}`);
console.log(`tv ok      ${tv.title}: ${seasonNumbers.length} seasons, ${total} episodes, cast ${tv.cast.length}, ${tv.genres.join("/")}`);

// ---- Streams (FramextvProvider.loadLinks) ----
const providers = (await api("providers")).providers
  .filter((p) => p.status === "available" && (p.category ?? "movie_tv") === "movie_tv").map((p) => p.id);
assert.ok(providers.length >= 3, `only ${providers.length} providers available`);

function resolve(base, ref) { return new URL(ref, base).href; }

/** Returns minutes of media, or null when the source is not playable. */
async function measure(src) {
  try {
    if (src.type === "mp4") {
      const r = await get(src.url, { Range: "bytes=0-1" }, 15000);
      const size = +(r.headers.get("content-range") ?? "").split("/")[1];
      return (r.status === 200 || r.status === 206) && size > 100e6 ? { minutes: null, mb: Math.round(size / 1e6) } : null;
    }
    const master = await (await get(src.url, {}, 15000)).text();
    if (!master.trimStart().startsWith("#EXTM3U")) return null;
    let playlist = master, playlistUrl = src.url;
    if (!master.includes("#EXTINF")) {
      const v = master.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
      playlistUrl = resolve(src.url, v);
      playlist = await (await get(playlistUrl, {}, 15000)).text();
    }
    const secs = [...playlist.matchAll(/#EXTINF:([\d.]+)/g)].reduce((a, m) => a + +m[1], 0);
    const seg = playlist.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    const body = Buffer.from(await (await get(resolve(playlistUrl, seg), {}, 20000)).arrayBuffer());
    // MPEG-TS (sync byte every 188 bytes) or fMP4, whatever the file extension claims.
    const ts = body[0] === 0x47 && body[188] === 0x47;
    const mp4 = body.subarray(4, 8).toString() === "ftyp" || body.subarray(4, 8).toString() === "styp" || body.subarray(4, 8).toString() === "moof";
    const encrypted = /#EXT-X-KEY:METHOD=AES-128/.test(playlist);
    if (!ts && !mp4 && !encrypted) return null;
    return { minutes: secs / 60, id: playlistUrl.replace(/[?&](Expires|Signature|KeyName|t)=[^&]*/g, "") };
  } catch {
    return null;
  }
}

async function checkStream(label, params, minMinutes) {
  const all = [null, ...providers];
  const responses = (await Promise.all(all.map((p) => api("stream", p ? { ...params, provider: p } : params, 60000).catch(() => null))))
    .filter((r) => r?.success && r.sources?.length);
  assert.ok(responses.length, `${label}: no provider returned sources`);
  const seen = new Set();
  const sources = responses.flatMap((r) => r.sources.map((s) => ({ ...s, provider: r.provider })))
    .filter((s) => !seen.has(s.url) && seen.add(s.url));
  const measured = await Promise.all(sources.map(async (s) => ({ s, m: await measure(s) })));
  const working = measured.filter((x) => x.m);
  assert.ok(working.length, `${label}: none of ${sources.length} sources play`);
  const hls = working.filter((x) => x.m.minutes != null);
  assert.ok(hls.length, `${label}: no working HLS source`);
  const best = Math.max(...hls.map((x) => x.m.minutes));
  assert.ok(best >= minMinutes, `${label}: longest stream only ${best.toFixed(1)} min`);
  const byProvider = {};
  for (const { s, m } of measured) {
    const p = (byProvider[s.provider] ??= { ok: 0, dead: 0 });
    m ? p.ok++ : p.dead++;
  }
  const subs = responses.flatMap((r) => r.subtitles ?? []);
  assert.ok(subs.length, `${label}: no subtitles`);
  const vtt = await (await get(subs[0].url)).text();
  assert.ok(/WEBVTT|-->/.test(vtt.slice(0, 2000)), `${label}: subtitle is not a text track`);
  const durations = [...new Set(hls.map((x) => x.m.minutes.toFixed(1)))].join("/");
  console.log(`stream ok  ${label}: ${working.length}/${sources.length} sources play, ${durations} min; ` +
    Object.entries(byProvider).map(([p, c]) => `${p}=${c.ok}/${c.ok + c.dead}`).join(" ") + `; ${subs.length} subtitles`);
  return new Set(hls.map((x) => x.m.id));
}

const a = await checkStream("Inception", { type: "movie", id: 27205 }, 140);
const b = await checkStream("Fight Club", { type: "movie", id: 550 }, 130);
const c = await checkStream("Breaking Bad S2E3", { type: "tv", id: show.id, season: 2, episode: 3 }, 40);
for (const [x, y, n] of [[a, b, "Inception/Fight Club"], [a, c, "Inception/Breaking Bad"], [b, c, "Fight Club/Breaking Bad"]]) {
  assert.ok(![...x].some((id) => y.has(id)), `${n} share a stream`);
}
console.log("all checks passed");
