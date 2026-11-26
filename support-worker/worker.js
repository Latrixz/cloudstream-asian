/**
 * CS Asian support goal: receives Buy Me a Coffee webhooks and serves this month's totals for the
 * in-app support popup. Stores totals only (no names, emails or messages).
 *
 *   POST /bmc     Buy Me a Coffee webhook (verified with the BMC_SECRET signing secret)
 *   GET  /stats   {"month":"November 2026","goal":100,"raised":23,"percent":23,"supporters":7}
 *   POST /admin   {"month":"2026-11","raised":12,"supporters":3}  (Authorization: Bearer ADMIN_TOKEN)
 *
 * Secrets: BMC_SECRET, ADMIN_TOKEN. KV binding: SUPPORT. Var: GOAL_USD.
 */
const COUNTED = new Set(["donation.created", "membership.started", "extra_purchase.created"]);
const REFUNDS = new Set(["donation.refunded", "extra_purchase.refunded"]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/stats" && request.method === "GET") return stats(env);
    // The webhook: /bmc (or the bare address, in case it was entered without the path).
    if ((url.pathname === "/bmc" || url.pathname === "/") && request.method === "POST") return webhook(request, env);
    if (url.pathname === "/admin" && request.method === "POST") return admin(request, env);
    return json({ ok: true, service: "csasian-support" });
  },
};

function json(body, status = 200, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "access-control-allow-origin": "*", ...extra },
  });
}

function monthKey(date) {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
}

function monthName(key) {
  const [y, m] = key.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleString("en-US", { month: "long", year: "numeric", timeZone: "UTC" });
}

async function load(env, key) {
  return (await env.SUPPORT.get(`month:${key}`, "json")) || { raised: 0, supporters: 0 };
}

async function stats(env) {
  const key = monthKey(new Date());
  const m = await load(env, key);
  const goal = Number(env.GOAL_USD || 100);
  const raised = Math.round(m.raised * 100) / 100;
  return json(
    { month: monthName(key), goal, raised, percent: Math.min(100, Math.round((raised / goal) * 100)), supporters: m.supporters },
    200,
    { "cache-control": "public, max-age=300" },
  );
}

async function hmacHex(secret, body) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function toUsd(amount, currency) {
  if (!currency || currency.toUpperCase() === "USD") return amount;
  try {
    const r = await fetch(`https://open.er-api.com/v6/latest/USD`, { cf: { cacheTtl: 3600 } });
    const rate = (await r.json()).rates?.[currency.toUpperCase()];
    return rate ? amount / rate : amount;
  } catch {
    return amount;
  }
}

async function webhook(request, env) {
  const body = await request.text();
  const given = (request.headers.get("x-signature-sha256") || "").toLowerCase();
  const sigOk = !!env.BMC_SECRET && !!given && given === (await hmacHex(env.BMC_SECRET, body));
  // Last delivery, for checking the setup (no payload kept: header names and event type only).
  let type0 = "";
  try { type0 = JSON.parse(body).type || ""; } catch {}
  await env.SUPPORT.put("last", JSON.stringify({
    at: new Date().toISOString(), sigOk, type: type0, headers: [...request.headers.keys()],
  }), { expirationTtl: 60 * 60 * 24 * 30 });
  if (!sigOk) return json({ ok: false, error: "bad signature" }, 401);
  let event;
  try { event = JSON.parse(body); } catch { return json({ ok: false, error: "bad json" }, 400); }
  const type = event.type || "";
  if (event.live_mode === false) return json({ ok: true, ignored: "test" });
  if (!COUNTED.has(type) && !REFUNDS.has(type)) return json({ ok: true, ignored: type });

  const d = event.data || {};
  const id = `${type}:${event.event_id || d.id || d.transaction_id || ""}`;
  if (await env.SUPPORT.get(`seen:${id}`)) return json({ ok: true, duplicate: true });

  const raw = Number(d.amount ?? d.total_amount_charged ?? (Number(d.coffee_price || 0) * Number(d.support_coffees || d.coffee_count || 1)) ?? 0);
  const usd = await toUsd(isFinite(raw) ? raw : 0, d.currency);
  const created = event.created ? new Date(Number(event.created) * (event.created > 1e12 ? 1 : 1000)) : new Date();
  const key = monthKey(isNaN(created) ? new Date() : created);
  const m = await load(env, key);
  if (REFUNDS.has(type)) {
    m.raised = Math.max(0, m.raised - usd);
  } else {
    m.raised += usd;
    m.supporters += 1;
  }
  await env.SUPPORT.put(`month:${key}`, JSON.stringify(m));
  await env.SUPPORT.put(`seen:${id}`, "1", { expirationTtl: 60 * 60 * 24 * 120 });
  return json({ ok: true, month: key });
}

async function admin(request, env) {
  if (!env.ADMIN_TOKEN || request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) {
    return json({ ok: false }, 401);
  }
  const b = await request.json().catch(() => ({}));
  if (b.last) return json((await env.SUPPORT.get("last", "json")) || {});
  const key = b.month || monthKey(new Date());
  await env.SUPPORT.put(`month:${key}`, JSON.stringify({ raised: Number(b.raised || 0), supporters: Number(b.supporters || 0) }));
  return json({ ok: true, month: key });
}
