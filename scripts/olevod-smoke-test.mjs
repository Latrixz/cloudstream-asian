import crypto from "node:crypto";
import assert from "node:assert/strict";

const api = "https://api.olelive.com";

function signature(epochSeconds = Math.floor(Date.now() / 1000)) {
  const value = epochSeconds.toString();
  const rows = ["", "", "", ""];
  for (const digit of value) {
    const bits = digit.charCodeAt(0).toString(2);
    rows[0] += bits.slice(2, 3);
    rows[1] += bits.slice(3, 4);
    rows[2] += bits.slice(4, 5);
    rows[3] += bits.slice(5);
  }
  const inserted = rows.map((bits) => Number.parseInt(bits, 2).toString(16).padStart(3, "0"));
  const md5 = crypto.createHash("md5").update(value).digest("hex");
  return md5.slice(0, 3) + inserted[0] + md5.slice(6, 11) + inserted[1] +
    md5.slice(14, 19) + inserted[2] + md5.slice(22, 27) + inserted[3] + md5.slice(30);
}

async function get(path) {
  const response = await fetch(`${api}${path}${path.includes("?") ? "&" : "?"}_vv=${signature()}`);
  assert.equal(response.ok, true, `${path}: HTTP ${response.status}`);
  const json = await response.json();
  assert.equal(json.code, 0, `${path}: ${json.msg ?? "API error"}`);
  return json.data;
}

const home = await get("/v1/pub/vod/list/true/2/0/0/1/0/0/update/1/20");
assert.ok(home.list.length > 0, "movie homepage is empty");
assert.ok(home.list[0].pic, "movie poster is missing");
assert.ok(home.total > home.list.length, "movie catalogue is not paginated");
const moviePage2 = await get("/v1/pub/vod/list/true/2/0/0/1/0/0/update/2/20");
assert.ok(moviePage2.list.length > 0, "movie catalogue page 2 is empty");
assert.notEqual(moviePage2.list[0].id, home.list[0].id, "movie pagination repeated page 1");

for (const category of [1, 2, 3, 4, 14]) {
  for (const order of ["hot", "desc", "update"]) {
    const catalogue = await get(`/v1/pub/vod/list/true/2/0/0/${category}/0/0/${order}/1/20`);
    assert.ok(catalogue.list.length > 0, `category ${category} (${order}) catalogue is empty`);
  }
}

const search = await get(`/v1/pub/index/search/${encodeURIComponent("庆余年")}/vod/0/1/30`);
const results = search.data.flatMap((group) => group.type === "vod" ? (group.list ?? []) : []);
assert.ok(results.some((item) => item.name.includes("庆余年")), "Chinese search returned no matching title");

const broad1 = await get(`/v1/pub/index/search/${encodeURIComponent("爱")}/vod/0/1/30`);
const broad2 = await get(`/v1/pub/index/search/${encodeURIComponent("爱")}/vod/0/2/30`);
const vodList = (d) => d.data.flatMap((group) => group.type === "vod" ? (group.list ?? []) : []);
assert.ok(broad1.total > 30 && vodList(broad2).length > 0, "search pagination returned no page 2");
assert.notEqual(vodList(broad2)[0].id, vodList(broad1)[0].id, "search page 2 repeated page 1");
for (const item of vodList(broad1).filter((entry) => entry.vip).slice(0, 3)) {
  const detail = await get(`/v1/pub/vod/detail/${item.id}/true`);
  assert.ok(!(detail.urls ?? []).some((episode) => !episode.vip && episode.url),
    `${item.name}: vip-flagged search item unexpectedly has free episodes (filter would hide playable title)`);
}

const seriesResult = results.find((item) => item.typeId1 === 2);
assert.ok(seriesResult, "Chinese search returned no TV series");
const series = await get(`/v1/pub/vod/detail/${seriesResult.id}/true`);
const freeEpisodes = series.urls.filter((episode) => !episode.vip && episode.url);
assert.ok(freeEpisodes.length > 1, "TV series did not expose multiple public episodes");

const movie = await get(`/v1/pub/vod/detail/${home.list[0].id}/true`);
const freeMovie = movie.urls.find((episode) => !episode.vip && episode.url);
assert.ok(freeMovie, "movie has no public non-VIP playback entry");
assert.ok(movie.urls.filter((episode) => episode.vip).every((episode) => episode.url !== freeMovie.url),
  "free selection unexpectedly chose a VIP entry");

const playlist = await fetch(freeMovie.url, { headers: { Referer: "https://www.olevod.com/" } });
assert.equal(playlist.ok, true, `HLS playlist: HTTP ${playlist.status}`);
assert.match(await playlist.text(), /^#EXTM3U/m, "playback URL is not an HLS playlist");

console.log(JSON.stringify({
  homepageItems: home.list.length,
  fullMovieCatalogue: home.total,
  paginatedCategories: ["movies", "dramas", "variety", "anime", "short dramas"],
  chineseSearchResults: results.length,
  broadSearchTotal: broad1.total,
  seriesEpisodes: freeEpisodes.length,
  playback: "public non-VIP HLS reachable",
}, null, 2));
