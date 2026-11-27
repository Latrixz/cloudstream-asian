/**
 * Per-platform install instructions for the Telegram bot. Download buttons point straight at the
 * files in the latest CloudStream / SkyStream GitHub releases (looked up live, cached for an hour),
 * falling back to the releases page if GitHub can't be reached.
 */

const CS = "recloudstream/cloudstream";
const SKY = "akashdh11/skystream";

export const PLATFORMS = [
  ["android", "📱 Android phone / tablet"],
  ["tv", "📺 Android TV / Google TV"],
  ["ios", "🍏 iPhone / iPad"],
  ["windows", "🪟 Windows"],
  ["mac", "🍎 Mac"],
  ["linux", "🐧 Linux"],
];

// Each message is English first, then the same text in Chinese as its own paragraph.
function bilingual(en, zh) {
  return [...en, "", ...zh].join("\n");
}

const ADD_REPO_EN = [
  "",
  "<b>Then add our repo</b>",
  "Settings → Extensions → Add repository → <code>csasian</code>",
  "Open CloudStream Asian → ⬇️ download the sources you want.",
];
const ADD_REPO_ZH = [
  "",
  "<b>然后添加仓库</b>",
  "设置 → 扩展 → 添加仓库 → 输入 <code>csasian</code>",
  "点开 CloudStream Asian，下载你想要的片源。",
];

export function menu() {
  return {
    text: bilingual([
      "📲 <b>How to install</b>",
      "CloudStream Asian works in <b>CloudStream</b> (Android) and <b>SkyStream</b> (Android, iPhone, Windows, Mac, Linux).",
      "Which device are you using?",
    ], [
      "📲 <b>安装教程</b>",
      "支持 <b>CloudStream</b>（安卓）和 <b>SkyStream</b>（安卓、iPhone、Windows、Mac、Linux）。",
      "你用的是什么设备？",
    ]),
    keyboard: [
      ...PLATFORMS.map(([id, label]) => [{ text: label, callback_data: `os:${id}` }]),
      [{ text: "❓ Help: report a problem | 帮助与反馈", callback_data: "os:help" }],
    ],
  };
}

const ISSUES = "https://github.com/Latrixz/cloudstream-asian/issues/new";

// /help: where to report problems, request sites and get updates.
export function help() {
  return {
    text: bilingual([
      "❓ <b>Help</b>",
      "📲 <b>Install on your device:</b> send /install or tap the button below.",
      "",
      "🔧 <b>A source not working?</b> Report it on GitHub. Please include the source name, the show/episode, and whether you use CloudStream or SkyStream.",
      "➕ <b>Want a new site?</b> Request it on GitHub with the site's address.",
      "",
      "💡 <b>Quick fixes</b>",
      "• A source won't load: try again later, or pick another source for the same show.",
      "• New sources don't show up: Settings → Extensions → CloudStream Asian → download them.",
      "• Installed sources update automatically; news is posted in @csasian.",
      "",
      "☕ Like the project? You can support it with the button below.",
    ], [
      "❓ <b>帮助</b>",
      "📲 <b>按设备安装：</b>发送 /install 或点下方按钮。",
      "",
      "🔧 <b>片源失效？</b>请在 GitHub 反馈，注明片源名称、影片/集数，以及你用的是 CloudStream 还是 SkyStream。",
      "➕ <b>想要新站点？</b>请在 GitHub 提交，附上网站地址。",
      "",
      "💡 <b>常见问题</b>",
      "• 片源打不开：稍后再试，或换一个片源看同一部剧。",
      "• 看不到新片源：设置 → 扩展 → CloudStream Asian → 下载新片源。",
      "• 已安装的片源会自动更新，更新消息发布在 @csasian。",
      "",
      "☕ 喜欢这个项目？可以点下方按钮支持我们。",
    ]),
    keyboard: [
      [{ text: "📲 How to install | 安装教程", callback_data: "os:menu" }],
      [{ text: "🔧 Report a problem | 反馈问题", url: `${ISSUES}?title=${encodeURIComponent("[Broken] ")}` }],
      [{ text: "➕ Request a site | 申请新站点", url: `${ISSUES}?title=${encodeURIComponent("[Request] ")}` }],
      [{ text: "☕ Support | 支持我们", url: "https://buymeacoffee.com/latrixz" }],
    ],
  };
}

export async function platform(id) {
  const g = GUIDES[id];
  if (!g) return menu();
  const [cs, sky] = await Promise.all([assets(CS), assets(SKY)]);
  const csApk = pick(cs, /\.apk$/i);
  const s = (re) => pick(sky, re);
  const { en, zh, buttons } = g({ csApk, s });
  return {
    text: bilingual([...en, ...ADD_REPO_EN], [...zh, ...ADD_REPO_ZH]),
    keyboard: [
      ...buttons.filter(([, url]) => url).map(([text, url]) => [{ text, url }]),
      [{ text: "« Other devices | 其他设备", callback_data: "os:menu" }],
    ],
  };
}

const GUIDES = {
  android: ({ csApk, s }) => ({
    en: [
      "📱 <b>Android phone / tablet</b>",
      "Install <b>either</b> app (CloudStream is the classic choice):",
      "1. Tap a download button below, then open the file and tap <b>Install</b>.",
      "2. If asked, allow \"Install unknown apps\" for your browser.",
    ],
    zh: [
      "📱 <b>安卓手机 / 平板</b>",
      "任选一个应用安装（推荐 CloudStream）：",
      "1. 点下方按钮下载，打开文件并点「安装」。",
      "2. 如有提示，请允许浏览器「安装未知应用」。",
    ],
    buttons: [
      ["⬇️ CloudStream (.apk)", csApk || rel(CS)],
      ["⬇️ SkyStream (most phones, arm64)", s(/android-arm64-v8a.*\.apk$/i) || rel(SKY)],
      ["⬇️ SkyStream (older phones, universal)", s(/android-universal.*\.apk$/i)],
    ],
  }),
  tv: ({ csApk, s }) => ({
    en: [
      "📺 <b>Android TV / Google TV / TV box</b>",
      "1. On the TV, install the free <b>Downloader</b> app from the Play Store.",
      "2. In Downloader, enter one of the links below (long-press a button to copy it).",
      "3. Install it; allow \"Install unknown apps\" for Downloader if asked.",
      "Most TV boxes use <b>armeabi-v7a</b>.",
    ],
    zh: [
      "📺 <b>安卓电视 / Google TV / 电视盒子</b>",
      "1. 在电视的 Play 商店安装免费的 <b>Downloader</b> 应用。",
      "2. 在 Downloader 中输入下方的下载链接（长按按钮可复制链接）。",
      "3. 安装应用；如有提示，请允许 Downloader「安装未知应用」。",
      "大多数电视盒子请选 <b>armeabi-v7a</b>。",
    ],
    buttons: [
      ["⬇️ CloudStream (.apk)", csApk || rel(CS)],
      ["⬇️ SkyStream for TV (armeabi-v7a)", s(/android-armeabi-v7a.*\.apk$/i) || rel(SKY)],
    ],
  }),
  ios: ({ s }) => ({
    en: [
      "🍏 <b>iPhone / iPad</b> (SkyStream)",
      "SkyStream isn't on the App Store, so it's installed from a computer (\"sideloading\"):",
      "1. Download the SkyStream <b>.ipa</b> below onto your computer.",
      "2. Install <b>Sideloadly</b> or <b>Impactor</b> (free) on the computer; Windows also needs iTunes.",
      "3. Connect your iPhone by USB, drag the .ipa in, sign in with your Apple ID, press Start.",
      "4. On the iPhone: Settings → General → VPN &amp; Device Management → your email → <b>Trust</b>.",
      "⏳ With a free Apple ID the app stops opening after <b>7 days</b>: reconnect and press Start again (or turn on Wi-Fi refresh in Sideloadly). Signing with a paid <b>Apple Developer</b> account ($99/year) lasts <b>1 year</b> instead.",
    ],
    zh: [
      "🍏 <b>iPhone / iPad</b>（SkyStream）",
      "SkyStream 不在 App Store 上架，需要用电脑安装（侧载）：",
      "1. 在电脑上下载下方的 SkyStream <b>.ipa</b> 文件。",
      "2. 在电脑上安装免费的 <b>Sideloadly</b> 或 <b>Impactor</b>；Windows 电脑还需安装 iTunes。",
      "3. 用 USB 连接 iPhone，把 .ipa 拖进去，登录 Apple ID，点 Start。",
      "4. 在 iPhone 上：设置 → 通用 → VPN 与设备管理 → 你的邮箱 → <b>信任</b>。",
      "⏳ 免费 Apple ID 签名 <b>7 天</b>后失效，需重新连接并点 Start（或在 Sideloadly 开启 Wi-Fi 自动刷新）。使用付费 <b>Apple 开发者</b>账号（$99/年）签名可用 <b>1 年</b>。",
    ],
    buttons: [
      ["⬇️ SkyStream (.ipa)", s(/ios.*\.ipa$/i) || rel(SKY)],
      ["Sideloadly", "https://sideloadly.io/"],
      ["Impactor", "https://impactor.khcrysalis.dev/"],
    ],
  }),
  windows: ({ s }) => ({
    en: [
      "🪟 <b>Windows</b> (SkyStream)",
      "1. Download the <b>Setup .exe</b> below and run it.",
      "2. If Windows SmartScreen appears, click <b>More info → Run anyway</b>.",
    ],
    zh: [
      "🪟 <b>Windows</b>（SkyStream）",
      "1. 下载下方的 <b>Setup .exe</b> 安装程序并运行。",
      "2. 如出现 SmartScreen 提示，点「<b>更多信息 → 仍要运行</b>」。",
    ],
    buttons: [
      ["⬇️ SkyStream installer (.exe)", s(/windows.*setup.*\.exe$/i) || rel(SKY)],
      ["⬇️ Portable (.zip, no install)", s(/windows.*\.zip$/i)],
    ],
  }),
  mac: ({ s }) => ({
    en: [
      "🍎 <b>Mac</b> (SkyStream)",
      "1. Check your chip: Apple menu → About This Mac. <b>M1/M2/M3/M4</b> = Apple silicon, <b>Intel</b> = Intel.",
      "2. Open the .dmg and drag SkyStream to Applications.",
      "3. If macOS blocks it: System Settings → Privacy &amp; Security → <b>Open Anyway</b> (one time).",
    ],
    zh: [
      "🍎 <b>Mac</b>（SkyStream）",
      "1. 查看芯片：苹果菜单 → 关于本机。<b>M1/M2/M3/M4</b> 选 Apple silicon，<b>Intel</b> 选 Intel。",
      "2. 打开 .dmg，把 SkyStream 拖到「应用程序」。",
      "3. 如被 macOS 拦截：系统设置 → 隐私与安全性 → <b>仍要打开</b>（只需一次）。",
    ],
    buttons: [
      ["⬇️ Apple silicon (M1–M4)", s(/macos-arm64.*\.dmg$/i) || rel(SKY)],
      ["⬇️ Intel Mac", s(/macos-x64.*\.dmg$/i)],
    ],
  }),
  linux: ({ s }) => ({
    en: [
      "🐧 <b>Linux</b> (SkyStream)",
      "Needs glibc 2.35+ (Ubuntu 22.04, Debian 12, Fedora 36 or newer).",
      "• Debian/Ubuntu: <code>sudo apt install ./skystream-linux-*.deb</code>",
      "• Any distro: <code>chmod +x skystream-*.AppImage</code>, then run it.",
      "Use the <b>arm64</b> files on ARM machines (e.g. Raspberry Pi).",
    ],
    zh: [
      "🐧 <b>Linux</b>（SkyStream）",
      "需要 glibc 2.35 或更新（Ubuntu 22.04、Debian 12、Fedora 36 及以上）。",
      "• Debian/Ubuntu：<code>sudo apt install ./skystream-linux-*.deb</code>",
      "• 其他发行版：<code>chmod +x skystream-*.AppImage</code>，然后运行。",
      "ARM 设备（如树莓派）请用 <b>arm64</b> 版本。",
    ],
    buttons: [
      ["⬇️ .deb (x64)", s(/linux-x64.*\.deb$/i) || rel(SKY)],
      ["⬇️ AppImage (x64)", s(/linux-x64.*\.AppImage$/i)],
      ["⬇️ .deb (arm64)", s(/linux-arm64.*\.deb$/i)],
      ["⬇️ AppImage (arm64)", s(/linux-arm64.*\.AppImage$/i)],
    ],
  }),
};

function rel(repo) {
  return `https://github.com/${repo}/releases/latest`;
}

function pick(list, re) {
  return list.find((a) => re.test(a.name) && !/debug/i.test(a.name))?.url;
}

// Latest stable release's files, [{name, url}]. Empty on any error (buttons then use the releases page).
async function assets(repo) {
  try {
    const r = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { "user-agent": "csasian-bot", accept: "application/vnd.github+json" },
      cf: { cacheTtl: 3600, cacheEverything: true },
    });
    if (!r.ok) return [];
    return ((await r.json()).assets || []).map((a) => ({ name: a.name, url: a.browser_download_url }));
  } catch {
    return [];
  }
}
