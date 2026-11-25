#!/usr/bin/env node
// Live smoke test for the LiveTv source (iptv-org). Node 18+.
// Rebuilds the catalogue the same way as LiveTvProvider, checks every home row and search,
// counts channels per row with a stream whose master playlist answers, and fully plays
// (master -> media playlist -> segment) channels from at least five countries.
// Downloads are cached under /tmp/livetv/.
import fs from 'node:fs';

const API = 'https://iptv-org.github.io/api';
const DIR = '/tmp/livetv';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const COUNTRIES = ['CN', 'HK', 'TW', 'SG', 'MY', 'JP', 'KR', 'ID', 'VN', 'TH', 'PH', 'KH', 'MM', 'LA', 'US', 'UK', 'CA', 'AU', 'NZ', 'IN'];
const CATEGORIES = ['news', 'sports', 'movies', 'kids', 'music', 'documentary'];
const ROW_NAMES = {
  'country/CN': '中国 China', 'country/HK': '香港 Hong Kong', 'country/TW': '台灣 Taiwan',
  'country/SG': '新加坡 Singapore', 'country/MY': '马来西亚 Malaysia', 'country/JP': '日本 Japan',
  'country/KR': '한국 Korea', 'category/news': '新闻 News', 'category/sports': '体育 Sports',
  'country/ID': 'Indonesia 印尼',
  'country/VN': 'Việt Nam 越南',
  'country/TH': 'ประเทศไทย 泰国',
  'country/PH': 'Philippines 菲律宾',
  'country/KH': 'កម្ពុជា 柬埔寨',
  'country/MM': 'မြန်မာ 缅甸',
  'country/LA': 'ລາວ 老挝',
  'country/US': 'United States 美国',
  'country/UK': 'United Kingdom 英国',
  'country/CA': 'Canada 加拿大',
  'country/AU': 'Australia 澳大利亚',
  'country/NZ': 'New Zealand 新西兰',
  'country/IN': 'India 印度',
  'category/movies': '电影 Movies', 'category/kids': '少儿 Kids', 'category/music': '音乐 Music',
  'category/documentary': '纪录 Documentary',
};
const CHECK_MS = 6000;

let failures = 0;
const fail = (msg) => { failures++; console.log(`FAIL ${msg}`); };
const ok = (msg) => console.log(`ok   ${msg}`);

async function json(file) {
  fs.mkdirSync(DIR, { recursive: true });
  const path = `${DIR}/${file}`;
  if (fs.existsSync(path) && Date.now() - fs.statSync(path).mtimeMs < 30 * 60 * 1000) {
    return JSON.parse(fs.readFileSync(path, 'utf8'));
  }
  const r = await fetch(`${API}/${file}`, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`${file}: HTTP ${r.status}`);
  const text = await r.text();
  fs.writeFileSync(path, text);
  return JSON.parse(text);
}

async function get(url, s, ms = CHECK_MS) {
  const headers = { 'User-Agent': s.user_agent || UA };
  if (s.referrer) headers.Referer = s.referrer;
  return fetch(url, { headers, signal: AbortSignal.timeout(ms), redirect: 'follow' });
}

async function head(r, n = 4096) {
  const reader = r.body.getReader();
  const chunks = [];
  let size = 0;
  while (size < n) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
  }
  reader.cancel().catch(() => {});
  return Buffer.concat(chunks).subarray(0, n);
}

async function isLive(s) {
  try {
    const r = await get(s.url, s);
    if (!r.ok) return false;
    const text = (await head(r)).toString('utf8').replace(/^[﻿\s\0]+/, '');
    return /\.mpd(\?|$)/i.test(s.url) ? text.includes('<MPD') : text.startsWith('#EXTM3U');
  } catch { return false; }
}

const firstUri = (pl) => pl.split(/\r?\n/).map((l) => l.trim()).find((l) => l && !l.startsWith('#'));

// Master -> media playlist -> first bytes of a segment.
async function plays(s) {
  try {
    let url = s.url;
    let r = await get(url, s, 10000);
    let pl = await r.text();
    if (!pl.trimStart().startsWith('#EXTM3U')) return 'no #EXTM3U';
    if (!pl.includes('#EXTINF')) {
      // Highest bandwidth variant last is common; take the first one listed.
      const v = firstUri(pl);
      if (!v) return 'no variant';
      url = new URL(v, r.url || url).href;
      r = await get(url, s, 10000);
      pl = await r.text();
      if (!pl.includes('#EXTINF')) return 'media playlist without #EXTINF';
    }
    const seg = firstUri(pl);
    if (!seg) return 'no segment';
    const segUrl = new URL(seg, r.url || url).href;
    const sr = await get(segUrl, s, 10000);
    if (!sr.ok) return `segment HTTP ${sr.status}`;
    const bytes = await head(sr, 1024);
    return bytes.length > 0 ? null : 'empty segment';
  } catch (e) { return e.name === 'TimeoutError' ? 'timeout' : e.message; }
}

async function pool(items, n, fn) {
  const out = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k], k); }
  }));
  return out;
}

const natural = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });
const norm = (s) => s.toLowerCase().replace(/[\s\-_.·]+/g, '');

// ---- Catalogue (same rules as LiveTvProvider.loadFromApi) ----
const [channels, streams, feeds, logos, blocklist] = await Promise.all(
  ['channels.json', 'streams.json', 'feeds.json', 'logos.json', 'blocklist.json'].map(json),
);
const blocked = new Set(blocklist.map((b) => b.channel));
const langs = new Map();
for (const f of feeds) {
  const e = langs.get(f.channel) || { main: null, all: new Set() };
  if (f.is_main && f.languages.length) e.main = f.languages;
  f.languages.forEach((l) => e.all.add(l));
  langs.set(f.channel, e);
}
const byChannel = new Map();
for (const s of streams) {
  if (!s.channel || (s.labels || []).length) continue;
  if (!byChannel.has(s.channel)) byChannel.set(s.channel, []);
  if (!byChannel.get(s.channel).some((x) => x.url === s.url)) byChannel.get(s.channel).push(s);
}
const logoOf = new Map();
for (const l of logos) {
  const score = (l.in_use !== false ? 4 : 0) + (l.feed == null ? 2 : 0) + ((l.format || '').toUpperCase() !== 'SVG' ? 1 : 0);
  if ((logoOf.get(l.channel)?.score ?? -1) < score) logoOf.set(l.channel, { score, url: l.url });
}
const catalog = [];
for (const c of channels) {
  if (c.is_nsfw || c.categories.includes('xxx') || c.closed || blocked.has(c.id)) continue;
  if (!COUNTRIES.includes(c.country) && !c.categories.includes('news')) continue;
  const s = byChannel.get(c.id);
  if (!s) continue;
  const e = langs.get(c.id);
  const languages = e ? (e.main || [...e.all]) : [];
  if (!COUNTRIES.includes(c.country) && !languages.includes('eng')) continue;
  catalog.push({ ...c, languages, streams: s, logo: logoOf.get(c.id)?.url });
}
catalog.sort((a, b) => natural.compare(a.name, b.name));
const asian = catalog.filter((c) => COUNTRIES.includes(c.country));
const rows = {};
for (const cc of COUNTRIES) rows[`country/${cc}`] = asian.filter((c) => c.country === cc);
for (const cat of CATEGORIES) {
  rows[`category/${cat}`] = asian.filter((c) => c.categories.includes(cat));
  if (cat === 'news') {
    rows[`category/${cat}`].push(...catalog.filter((c) => !COUNTRIES.includes(c.country) && c.categories.includes('news') && c.languages.includes('eng')));
  }
}
console.log(`catalogue: ${catalog.length} channels, ${catalog.reduce((n, c) => n + c.streams.length, 0)} streams`);

// ---- Rows ----
for (const [key, list] of Object.entries(rows)) {
  if (list.length) ok(`row ${ROW_NAMES[key]}: ${list.length} channels (${Math.ceil(list.length / 40)} page(s))`);
  else fail(`row ${ROW_NAMES[key]} is empty`);
}
const noLogo = catalog.filter((c) => !c.logo).length;
console.log(`     ${catalog.length - noLogo}/${catalog.length} channels have a logo`);

// ---- Search ----
for (const q of ['CCTV', 'TVB', 'CNA', '凤凰', 'NHK']) {
  const n = norm(q);
  const hits = catalog.filter((c) => [c.name, ...c.alt_names].some((x) => norm(x).includes(n)));
  if (hits.length) ok(`search "${q}": ${hits.length} (${hits.slice(0, 4).map((c) => c.name).join(', ')})`);
  else if (['CCTV', 'TVB', 'CNA'].includes(q)) fail(`search "${q}" found nothing`);
  else console.log(`     search "${q}": 0`);
}

// ---- Liveness of every channel in the rows ----
const unique = [...new Map(Object.values(rows).flat().map((c) => [c.id, c])).values()];
const allStreams = unique.flatMap((c) => c.streams.map((s) => ({ c, s })));
console.log(`checking ${allStreams.length} streams of ${unique.length} channels (master playlist within ${CHECK_MS / 1000}s)...`);
const live = await pool(allStreams, 48, ({ s }) => isLive(s));
const working = new Map();
allStreams.forEach(({ c, s }, i) => { if (live[i]) { if (!working.has(c.id)) working.set(c.id, []); working.get(c.id).push(s); } });
console.log('working channels per row:');
for (const [key, list] of Object.entries(rows)) {
  const n = list.filter((c) => working.has(c.id)).length;
  console.log(`     ${ROW_NAMES[key].padEnd(18)} ${String(n).padStart(3)} / ${list.length}`);
  if (!n) fail(`no working channel in ${ROW_NAMES[key]}`);
}

// ---- Full playback on channels from different countries ----
const played = [];
for (const cc of COUNTRIES) {
  const candidates = rows[`country/${cc}`].filter((c) => working.has(c.id));
  for (const c of candidates.slice(0, 6)) {
    const s = working.get(c.id).sort((a, b) => (parseInt(b.quality) || 0) - (parseInt(a.quality) || 0))[0];
    const err = await plays(s);
    if (!err) { played.push(`${cc} ${c.name} (${s.quality || '?'})`); break; }
  }
}
if (played.length >= 5) ok(`playback (master -> media -> segment) in ${played.length} countries: ${played.join('; ')}`);
else fail(`playback works in only ${played.length} countries: ${played.join('; ')}`);

console.log(failures ? `\n${failures} failure(s)` : '\nall checks passed');
process.exit(failures ? 1 : 0);
