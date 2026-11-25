// Live smoke test for the Duboku (dbku.tv) provider: list rows → detail → play page → stream.
// Run with Node.js 18+: node scripts/smoke-test.mjs
import assert from "node:assert/strict";

const BASE = "https://www.dbku.tv";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const get = async (url) => {
  const r = await fetch(url, { headers: { "User-Agent": UA, Referer: `${BASE}/` } });
  assert.equal(r.ok, true, `${url}: HTTP ${r.status}`);
  return r.text();
};

const categories = { 2: "连续剧", 13: "陆剧", 15: "日韩剧", 14: "台泰剧", 21: "短剧", 1: "电影", 3: "综艺", 4: "动漫" };
const rows = {};
for (const [id, label] of Object.entries(categories)) {
  for (const by of ["hits", "time", ""]) {
    const html = await get(`${BASE}/vodshow/${id}--${by}---------.html`);
    const n = (html.match(/\/voddetail\/\d+\.html/g) ?? []).length;
    assert.ok(n > 0, `${label} by=${by || "default"} is empty`);
    rows[`${label}/${by || "default"}`] = n;
  }
}

const page2 = await get(`${BASE}/vodshow/2--time------2---.html`);
assert.ok(/\/voddetail\/\d+\.html/.test(page2), "page 2 is empty");

const search = await get(`${BASE}/vodsearch/-------------.html?wd=${encodeURIComponent("爱")}`);
assert.ok(/\/voddetail\/\d+\.html/.test(search), "search returned nothing");

function decodePlayer(html) {
  const m = html.match(/var\s+player_\w+\s*=\s*(\{.*?\})\s*</s);
  assert.ok(m, "player data missing");
  const data = JSON.parse(m[1]);
  let url = data.url;
  if (String(data.encrypt) === "1") url = decodeURIComponent(url);
  if (String(data.encrypt) === "2") url = decodeURIComponent(Buffer.from(url, "base64").toString("utf8"));
  return url;
}

async function checkTitle(categoryId) {
  const list = await get(`${BASE}/vodshow/${categoryId}--hits---------.html`);
  const detailPath = list.match(/\/voddetail\/\d+\.html/)[0];
  const detail = await get(BASE + detailPath);
  const plays = [...new Set(detail.match(/\/vodplay\/\d+-\d+-\d+\.html/g) ?? [])];
  assert.ok(plays.length > 0, `${detailPath}: no episodes`);
  // Recommendations: the sidebar "{分类}热播" list (the page has no 猜你喜欢 block), minus this title.
  const sidebar = detail.split(' myui-sidebar">')[1] ?? "";
  const related = [...new Set(sidebar.match(/\/voddetail\/\d+\.html/g) ?? [])].filter((p) => p !== detailPath).slice(0, 16);
  assert.ok(related.length > 0, `${detailPath}: no recommendations`);
  const relatedDetail = await get(BASE + related[0]);
  assert.ok(relatedDetail.includes("myui-content__detail"), `${related[0]}: recommendation does not open a detail page`);
  const stream = decodePlayer(await get(BASE + plays[0]));
  const m3u8 = await fetch(stream, { headers: { "User-Agent": UA } });
  assert.equal(m3u8.ok, true, `${stream}: HTTP ${m3u8.status}`);
  assert.match(await m3u8.text(), /^#EXTM3U/m, "not an HLS playlist");
  return { detailPath, episodeLinks: plays.length, recommendations: related.length };
}

console.log(JSON.stringify({ rows, series: await checkTitle(2), movie: await checkTitle(1) }, null, 2));
