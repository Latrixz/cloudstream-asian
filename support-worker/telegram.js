/**
 * The @csasian Telegram bot: welcomes new channel subscribers, and in a private chat (or a group,
 * via /install) walks people through installing CloudStream or SkyStream on their device
 * (see install-guide.js).
 *
 * Telegram sends a `chat_member` update to this webhook when someone joins (the bot must be a
 * channel admin with "Post messages" and "Delete messages"). Each person gets their own welcome
 * naming them. Posts are silent, so subscribers don't get a notification for each one. To keep the
 * channel tidy, each welcome replaces the previous one; set WELCOME_REPLACE = "false" to keep them all.
 *
 *   POST /telegram         Telegram webhook (verified with the secret token header)
 *   POST /telegram/setup   registers the webhook       (Authorization: Bearer ADMIN_TOKEN)
 *   POST /telegram/status  webhook info + last update  (Authorization: Bearer ADMIN_TOKEN)
 *   POST /telegram/announce  posts the "install help bot" announcement once; ?force=1 to post again
 *
 * Secrets: TELEGRAM_BOT_TOKEN, ADMIN_TOKEN. KV binding: SUPPORT.
 * Vars: TELEGRAM_CHAT (default @csasian), TELEGRAM_GUIDE_MESSAGE_ID (optional, links the pinned guide),
 *       WELCOME_REPLACE (default "true"), WELCOME_ENABLED (default "true").
 * KV keeps only the current welcome's message id and the bot's username (no user data).
 *
 * Each welcome has a "📲 How to install" button that opens the bot (t.me/<bot>?start=install).
 * Bot commands: /start and /install show the device menu; /help shows how to report problems. /myid replies with your Telegram ID.
 */
import { help, menu, platform } from "./install-guide.js";

const JOINED_FROM = new Set(["left", "kicked"]);

export async function telegram(request, env, url) {
  if (url.pathname === "/telegram" && request.method === "POST") return update(request, env);
  if (!env.ADMIN_TOKEN || request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) {
    return json({ ok: false }, 401);
  }
  if (url.pathname === "/telegram/setup" && request.method === "POST") {
    await api(env, "setMyCommands", { commands: [
      { command: "install", description: "How to install on your device | 安装教程" },
      { command: "help", description: "Report a problem, request a site | 反馈与帮助" },
    ] });
    return json(await api(env, "setWebhook", {
      url: `${url.origin}/telegram`,
      secret_token: await webhookSecret(env),
      allowed_updates: ["chat_member", "message", "callback_query"],
      drop_pending_updates: true,
    }));
  }
  if (url.pathname === "/telegram/announce" && request.method === "POST") {
    return json(await announce(env, url.searchParams.get("force") === "1"));
  }
  if (url.pathname === "/telegram/status" && request.method === "POST") {
    return json({
      webhook: (await api(env, "getWebhookInfo", {})).result,
      last: await env.SUPPORT.get("tg:last", "json"),
    });
  }
  return json({ ok: false }, 404);
}

async function update(request, env) {
  if (request.headers.get("x-telegram-bot-api-secret-token") !== (await webhookSecret(env))) {
    return json({ ok: false }, 401);
  }
  const u = await request.json().catch(() => ({}));
  // Always answer 200: a non-2xx makes Telegram retry the same update.
  if (u.message) await message(env, u.message);
  if (u.callback_query) await callback(env, u.callback_query);
  const cm = u.chat_member;
  if (!cm || !isOurChat(cm.chat, env)) return json({ ok: true, ignored: true });

  // Off when the chat has Telegram's own Welcome Message set up instead.
  if (env.WELCOME_ENABLED === "false") return json({ ok: true, ignored: "welcome off" });
  const was = cm.old_chat_member?.status;
  const now = cm.new_chat_member?.status;
  const user = cm.new_chat_member?.user;
  await env.SUPPORT.put("tg:last", JSON.stringify({ at: new Date().toISOString(), was, now }), { expirationTtl: 60 * 60 * 24 * 30 });
  if (!user || user.is_bot || !JOINED_FROM.has(was) || now !== "member") return json({ ok: true, ignored: true });

  await welcome(env, cm.chat.id, user);
  return json({ ok: true });
}

// Private chat: any message shows the device menu. Groups: only /start, /install or /help.
async function message(env, m) {
  // The old channel is linked to the group, so Telegram copies (and pins) its posts here.
  // Updates are posted in the group directly, so remove those copies.
  if (m.is_automatic_forward && isOurChat(m.chat, env)) {
    await api(env, "deleteMessage", { chat_id: m.chat.id, message_id: m.message_id });
    return;
  }
  const t = (m.text || "").trim();
  // /myid: replies with the sender's Telegram ID (and the chat's, in a group). Used to point the
  // daily source-watcher report (repo variable TELEGRAM_WATCH_CHAT) at a person or chat.
  if (/^\/myid(@\w+)?\b/i.test(t) && !m.from?.is_bot) {
    const lines = [`Your ID: <code>${m.from?.id}</code>`];
    if (m.chat?.type !== "private") lines.push(`This chat's ID: <code>${m.chat?.id}</code>`);
    await api(env, "sendMessage", { chat_id: m.chat.id, text: lines.join("\n"), parse_mode: "HTML" });
    return;
  }
  const cmd =/^\/(start|install|help)(@\w+)?\b/i.exec(t)?.[1]?.toLowerCase();
  if (m.chat?.type !== "private" && !cmd) return;
  if (m.from?.is_bot) return;
  const view = cmd === "help" ? help() : menu();
  await api(env, "sendMessage", {
    chat_id: m.chat.id,
    text: view.text,
    parse_mode: "HTML",
    reply_markup: { inline_keyboard: view.keyboard },
    ...(m.chat.type !== "private" ? { reply_parameters: { message_id: m.message_id, allow_sending_without_reply: true } } : {}),
  });
}

// A device button was tapped: swap the menu for that device's steps (or back to the menu).
async function callback(env, q) {
  const id = String(q.data || "").replace(/^os:/, "");
  await api(env, "answerCallbackQuery", { callback_query_id: q.id });
  if (!q.message) return;
  const view = id === "menu" ? menu() : id === "help" ? help() : await platform(id);
  await api(env, "editMessageText", {
    chat_id: q.message.chat.id,
    message_id: q.message.message_id,
    text: view.text,
    parse_mode: "HTML",
    link_preview_options: { is_disabled: true },
    reply_markup: { inline_keyboard: view.keyboard },
  });
}

// One-off post to the whole channel introducing the install help bot.
async function announce(env, force) {
  if (!force && (await env.SUPPORT.get("tg:announced"))) return { ok: false, error: "already announced (use ?force=1)" };
  const bot = await botUsername(env);
  if (!bot) return { ok: false, error: "getMe failed: check TELEGRAM_BOT_TOKEN" };
  const sent = await api(env, "sendMessage", {
    chat_id: env.TELEGRAM_CHAT || "@csasian",
    parse_mode: "HTML",
    link_preview_options: { is_disabled: true },
    reply_markup: { inline_keyboard: [[{ text: "📲 How to install | 安装教程", url: `https://t.me/${bot}?start=install` }]] },
    text: [
      "📢 <b>New: install help bot</b>",
      `Not sure how to set up CloudStream Asian? @${bot} walks you through it step by step for <b>Android, Android TV, iPhone/iPad, Windows, Mac and Linux</b>, with direct download links for <b>CloudStream</b> and <b>SkyStream</b>.`,
      "Tap the button below, pick your device, and follow the steps. You can also send /install to the bot any time.",
      "",
      "📢 <b>新功能：安装助手机器人</b>",
      `不知道怎么安装 CloudStream Asian？@${bot} 会按设备一步步教你安装：<b>安卓、安卓电视、iPhone/iPad、Windows、Mac 和 Linux</b>，并提供 <b>CloudStream</b> 和 <b>SkyStream</b> 的直接下载链接。`,
      "点下方按钮，选择你的设备，按步骤操作即可。随时给机器人发送 /install 也可以。",
    ].join("\n"),
  });
  if (sent.ok) await env.SUPPORT.put("tg:announced", String(sent.result.message_id));
  return { ok: !!sent.ok, message_id: sent.result?.message_id, error: sent.description };
}

async function botUsername(env) {
  const cached = await env.SUPPORT.get("tg:botname");
  if (cached) return cached;
  const me = await api(env, "getMe", {});
  const name = me.result?.username;
  if (name) await env.SUPPORT.put("tg:botname", name, { expirationTtl: 60 * 60 * 24 * 7 });
  return name;
}

async function welcome(env, chatId, user) {
  const bot = await botUsername(env);
  const sent = await api(env, "sendMessage", {
    chat_id: chatId,
    text: text(user, env),
    parse_mode: "HTML",
    disable_notification: true,
    link_preview_options: { is_disabled: true },
    ...(bot ? { reply_markup: { inline_keyboard: [[{ text: "📲 How to install | 安装教程", url: `https://t.me/${bot}?start=install` }]] } } : {}),
  });
  if (!sent.ok || env.WELCOME_REPLACE === "false") return;
  const prev = await env.SUPPORT.get("tg:welcome");
  if (prev) await api(env, "deleteMessage", { chat_id: chatId, message_id: Number(prev) });
  await env.SUPPORT.put("tg:welcome", String(sent.result.message_id), { expirationTtl: 60 * 60 * 24 * 30 });
}

function text(user, env) {
  const name = `<a href="tg://user?id=${user.id}">${esc(user.first_name || user.username || "friend")}</a>`;
  const chat = (env.TELEGRAM_CHAT || "@csasian").replace(/^@/, "");
  const pinned = env.TELEGRAM_GUIDE_MESSAGE_ID ? `https://t.me/${chat}/${env.TELEGRAM_GUIDE_MESSAGE_ID}` : "";
  const link = (label) => (pinned ? `<a href="${pinned}">${label}</a>` : label);
  return [
    `👋 Welcome ${name} to CloudStream Asian!`,
    "Works with either <b>CloudStream</b> or <b>SkyStream</b> (Android, iPhone, Windows, Mac, Linux).",
    `New here? Tap 📲 below for step-by-step help for your device, or see the ${link("📌 pinned installation guide")}. Repo code: <code>csasian</code>`,
    "Type /install (how to install) or /help (report a broken source, request a site) here any time. Feel free to ask questions and chat 💬",
    "",
    `👋 欢迎 ${name} 加入 CloudStream Asian！`,
    "<b>CloudStream</b> 或 <b>SkyStream</b> 都可以使用（安卓、iPhone、Windows、Mac、Linux）。",
    `第一次来？点下方 📲 按设备查看安装步骤，或查看${link("📌 置顶安装教程")}。仓库代码：<code>csasian</code>`,
    "随时在群里发送 /install（安装教程）或 /help（反馈片源失效、申请新站点）。欢迎提问和聊天 💬",
  ].join("\n");
}

function isOurChat(chat, env) {
  const want = String(env.TELEGRAM_CHAT || "@csasian");
  return want.startsWith("@") ? `@${chat?.username || ""}`.toLowerCase() === want.toLowerCase() : String(chat?.id) === want;
}

// Derived from the bot token, so there's no extra secret to manage. Telegram allows [A-Za-z0-9_-].
async function webhookSecret(env) {
  const data = new TextEncoder().encode(`csasian-webhook:${env.TELEGRAM_BOT_TOKEN || ""}`);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function api(env, method, params) {
  const r = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(params),
  });
  return r.json().catch(() => ({ ok: false }));
}

function esc(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}
