// Offline parse check for the Ddys (ddys.io) provider. The site sits behind a Cloudflare
// challenge that plain HTTP cannot pass, so this checks DdysProvider's selectors against pages
// saved from a browser instead of the live site.
// Run with Node.js 18+:
//   node scripts/ddys-parse-check.mjs [saved-dir]   (default /tmp/ddys/saved; needs detail.html and list.html)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const dir = process.argv[2] ?? "/tmp/ddys/saved";
const detail = readFileSync(join(dir, "detail.html"), "utf8");
const list = readFileSync(join(dir, "list.html"), "utf8");

const text = (html) => html.replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim();

/** The box that holds `<h2>heading</h2>`, up to the next box (sectionAfter() in the provider). */
function section(html, heading) {
  const m = new RegExp(`<h2[^>]*>\\s*${heading}\\s*</h2>`).exec(html);
  if (!m) return null;
  const rest = html.slice(m.index + m[0].length);
  const end = rest.search(/<div class="bg-white[^"]*border[^"]*p-4 md:p-8/);
  return end < 0 ? rest : rest.slice(0, end);
}

/** `div.movie-card` → { title, url, poster, kind } as in toResult(). */
function cards(html) {
  return html.split(/<div class="movie-card[ "]/).slice(1).map((card) => {
    const link = card.match(/<h3[^>]*>\s*<a href="([^"]+)"[^>]*>([^<]*)</);
    const img = card.match(/<img[^>]*?(?:data-src|src)="([^"]+)"/);
    // The first div.text-xs is the hidden 暂无海报 placeholder; the kind line contains "·".
    const kind = [...card.matchAll(/<div class="text-xs[^"]*"[^>]*>([^<]*)</g)].map((m) => m[1].trim()).find((t) => t.includes("·")) ?? "";
    return link && { url: link[1], title: link[2].trim(), poster: img?.[1], kind };
  }).filter(Boolean);
}

// Detail fields, in the order load() reads them.
const wrap = detail.slice(detail.indexOf('id="movie-content-wrap"'));
assert.ok(wrap.length < detail.length, "#movie-content-wrap missing");
const h1 = wrap.match(/<h1[^>]*>([\s\S]*?)<\/h1>\s*<div[^>]*>([^<]*)</);
assert.ok(h1, "h1 + meta line missing");
const title = text(h1[1].replace(/<span[\s\S]*?<\/span>/g, ""));
const meta = h1[2].split("·").map((s) => s.trim()).filter(Boolean);
const year = Number(meta[0]?.slice(0, 4));
const tags = meta.slice(1);
const poster = wrap.match(/<img[^>]*src="([^"]+)"[^>]*alt="[^"]+"/)?.[1];
const score = Number(wrap.match(/class="rating-display">豆瓣[（(]\s*([\d.]+)/)?.[1]);
const cast = text(wrap.match(/主演：<\/span>\s*<span>([^<]*)</)?.[1] ?? "").split(/[/、,]/).map((s) => s.trim()).filter(Boolean);
const plotBox = section(detail, "剧情介绍");
const paragraphs = [...(plotBox ?? "").matchAll(/<p>([\s\S]*?)<\/p>/g)].map((m) => text(m[1])).filter(Boolean);
const related = cards(section(detail, "相关推荐") ?? "").slice(0, 16);

assert.ok(title, "no title");
assert.ok(poster, `${title}: no poster`);
assert.ok(year > 1900, `${title}: no year`);
assert.ok(tags.length > 0, `${title}: no tags`);
assert.ok(score > 0, `${title}: no score`);
assert.ok(cast.length > 0, `${title}: no cast`);
assert.ok(paragraphs.join("").length > 20, `${title}: no plot`);
assert.ok(related.length > 0, `${title}: no recommendations`);
assert.ok(related.every((r) => r.url.startsWith("https://ddys.io/movie/") && r.title && r.poster), "recommendation without url/title/poster");
assert.ok(/allSources\s*=\s*\[/.test(detail), `${title}: allSources missing`);

// List cards: the kind line ("2023 · 剧集") must be found past the 暂无海报 placeholder.
const listed = cards(list);
assert.ok(listed.length > 0, "list page has no cards");
assert.ok(listed.every((c) => c.kind), "list card without a kind line");

console.log(JSON.stringify({
  detail: { title, year, tags, score, cast, poster: !!poster, plotParagraphs: paragraphs.length, plotChars: paragraphs.join("").length, recommendations: related.map((r) => r.title) },
  list: { cards: listed.length, kinds: [...new Set(listed.map((c) => c.kind.split("·").pop().trim()))] },
}, null, 2));
