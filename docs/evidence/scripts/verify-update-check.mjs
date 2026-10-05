// 设置面板 · 版本号旁边的「检查更新」——
// 一个按钮扛五种状态(idle / 检查中 / 已是最新 / 有新版本 / 失败), 判据:
//   1. 版本号显示的是**二进制里**的版本(getVersion), 不是源码里写死的字面量
//   2. 版本号与按钮在同一行、按钮命中区 ≥24×24、设置面板**没有因为这一行多出滚动条**
//      ("一屏放下"是这块面板的前置约束, 见 settings-dialog.tsx 的注释)
//   3. 点击打到 GitHub 的 /releases/latest, 且四种结果各自落到对的字面
//   4. 有新版时那一下是"去发布页"(走 openUrl, 浏览器里兜底 window.open)
//   5. 键盘能 Tab 到按钮且焦点环可见
//
// 跑法: node verify-update-check.mjs   (需先 npm run tauri dev, 或任意 vite 起着 1420)
//   APP_URL 覆盖地址(默认 http://localhost:1420); PW_BASE / CHROME 覆盖 playwright 与浏览器
//   SHOT=<path> 落地一张截图
// 退出码: 0 = 通过, 1 = 失败
//
// 这个脚本用 route 把 GitHub 接口接到桩上(所以"有新版本"那一路在浏览器里能演)。
// 真机上"有新版本 / 点它去发布页"这两下**只能靠临时改代码**验 —— 刚发完版, 线上永远
// 和本机同版本, 走不到 available 分支。做法: 临时让 fetchLatestRelease 返回一个更高的
// version(但 url 指向另一个真实存在的 tag, 这样新开的浏览器窗口标题能认出来), 点完立刻
// 改回、确认 git diff 干净。真机证据: 线上版本 v1.1.1 时点按钮 → UIA 读到"已是最新";
// 改成 9.9.9 后 → 读到"有新版本 9.9.9", 再点一下, Edge 标签数 6 → 7 且活动标题变成该发布页。
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";

const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = process.env.CHROME || "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const BASE = process.env.APP_URL || "http://localhost:1420";
const PKG_VERSION = JSON.parse(readFileSync(new URL("../../../package.json", import.meta.url), "utf8")).version;
const API = "https://api.github.com/repos/XUTENGXIANG/ImageFilter/releases/latest";
const RELEASE_URL = "https://github.com/XUTENGXIANG/ImageFilter/releases/tag/v9.9.9";

let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`  ok   ${label}`); }
  else { failed++; console.log(`  FAIL ${label}${detail !== undefined ? "  " + JSON.stringify(detail) : ""}`); }
}

// Tauri IPC 桩: 只为让面板在没有真 Tauri 的浏览器里能开出来。
// 挂钩子的是 `plugin:app|version`(验"版本号来自二进制")与 `plugin:opener|open_url`
// (浏览器里必然失败 → 走前端兜底的 window.open, 好把它记下来)。
const STUB = `
(function () {
  var Q = new URLSearchParams(location.search);
  var cbId = 0;
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: "main" }, currentWebview: { label: "main" } },
    invoke: function (cmd) {
      if (cmd === "plugin:event|listen") return Promise.resolve(++cbId);
      if (cmd === "plugin:event|unlisten") return Promise.resolve(null);
      if (cmd === "plugin:app|version") return Promise.resolve(Q.get("ver"));
      if (cmd === "plugin:opener|open_url") return Promise.reject(new Error("no opener outside Tauri"));
      if (cmd === "get_os_capabilities") return Promise.resolve({ platform: "windows", windowsBuild: 26200, supportsMica: true });
      if (cmd === "detect_drives" || cmd === "get_import_history" || cmd === "read_decisions" || cmd === "get_rules") return Promise.resolve([]);
      if (cmd === "count_import_history") return Promise.resolve(0);
      if (cmd === "probe_lightroom") return Promise.resolve({ found: false });
      if (cmd === "probe_xmp_target") return Promise.resolve({ ok: false });
      return Promise.resolve(null);
    },
    transformCallback: function (cb) { return ++cbId; },
    unregisterCallback: function () {},
    convertFileSrc: function () { return "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='4' height='3'/>"; },
    plugins: {},
  };
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: function () {} };
  window.__OPENED__ = [];
  window.open = function (u) { window.__OPENED__.push(String(u)); return null; };
})();
`;

const browser = await chromium.launch({ executablePath: EXE });

/** 一路读到设置面板里的那一行 */
const ROW_JS = `(() => {
  const box = document.querySelector('[data-slot="dialog-content"]');
  if (!box) return null;
  const holder = box.querySelector('[role="status"]');
  const btn = holder ? holder.querySelector('button') : null;
  if (!btn) return null;
  const versionEl = holder.parentElement.firstElementChild; // 行首的"版本 x.y.z"
  const b = btn.getBoundingClientRect();
  const v = versionEl.getBoundingClientRect();
  const cs = getComputedStyle(btn);
  // 按钮文字与其实际背景的对比度(WCAG 相对亮度)。颜色要过一遍 canvas:
  // 本项目的颜色都是 oklch(), Chromium 的 computed value 会原样回 oklch(0.708 0 0),
  // 直接抓数字当 rgb 会把两边都算成 0 → 对比度恒等于 1(踩过)。
  const cv = document.createElement('canvas'); cv.width = cv.height = 1;
  const cx = cv.getContext('2d', { willReadFrequently: true });
  const rgbOf = (c) => {
    cx.clearRect(0, 0, 1, 1);
    cx.fillStyle = '#000'; cx.fillStyle = c; cx.fillRect(0, 0, 1, 1);
    const d = cx.getImageData(0, 0, 1, 1).data;
    return [d[0], d[1], d[2], d[3] / 255];
  };
  const lum = ([r, g, b]) => {
    const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  let n = btn, bg = null;
  while (n && !bg) {
    const c = getComputedStyle(n).backgroundColor;
    if (rgbOf(c)[3] > 0) bg = c; // 一路向上找第一个不透明的背景
    n = n.parentElement;
  }
  const L1 = lum(rgbOf(cs.color)), L2 = lum(rgbOf(bg || 'rgb(0,0,0)'));
  return {
    label: btn.textContent.trim(),
    title: btn.getAttribute('title'),
    busy: btn.getAttribute('aria-busy'),
    disabled: btn.disabled,
    hit: { w: Math.round(b.width), h: Math.round(b.height) },
    sameLine: Math.abs((b.top + b.height / 2) - (v.top + v.height / 2)),
    versionText: versionEl.textContent.trim(),
    contrast: Math.round(((Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05)) * 100) / 100,
    // 这一行有没有把面板顶出滚动条
    overflow: { scroll: box.scrollHeight - box.clientHeight, rowH: Math.round(holder.getBoundingClientRect().height) },
    boxShadow: cs.boxShadow,
    activeIsBtn: document.activeElement === btn,
  };
})()`;

async function makePage(ver) {
  const page = await browser.newPage({ viewport: { width: 1202, height: 802 }, deviceScaleFactor: 2 });
  const errs = [];
  page.on("pageerror", (e) => errs.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errs.push(`[${m.type()}] ${m.text()}`); });
  await page.addInitScript({ content: STUB });
  await page.goto(BASE + "/" + (ver ? `?ver=${ver}` : ""), { waitUntil: "domcontentloaded" });
  await page.waitForSelector("#root > *");
  await page.waitForTimeout(600);
  await page.getByRole("button", { name: /^(设置|Settings)$/ }).click();
  await page.waitForSelector('[data-slot="dialog-content"]');
  await page.waitForTimeout(200); // 入场动画 100ms
  return { page, errs };
}

/** 把 GitHub 接口接到桩上; delay 用来抓"检查中" */
async function routeApi(page, { status = 200, tag, html_url, delay = 0, abort = false }, seen) {
  await page.route(API, async (route) => {
    if (seen) seen.push({ url: route.request().url(), accept: route.request().headers()["accept"] });
    if (abort) return route.abort("failed");
    if (delay) await new Promise((r) => setTimeout(r, delay));
    return route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(status === 200 ? { tag_name: tag, html_url } : { message: "nope" }),
    });
  });
}

const read = (page) => page.evaluate(ROW_JS);
const clickUpdate = (page) => page.evaluate(`document.querySelector('[data-slot="dialog-content"] [role="status"] button').click()`);
/** 等按钮字面变成目标(轮询, 避免死等) */
async function waitLabel(page, re, timeout = 3000) {
  const t0 = Date.now();
  for (;;) {
    const s = await read(page);
    if (s && re.test(s.label)) return s;
    if (Date.now() - t0 > timeout) return s;
    await page.waitForTimeout(60);
  }
}

// ── 场景 1: 版本号来自二进制(getVersion 桩返回 9.9.8), 行几何与命中区 ──────────
console.log("场景 1 · 面板里的这一行(版本号取二进制, 几何不变):");
{
  const { page, errs } = await makePage("9.9.8");
  const s = await read(page);
  check(!!s, "设置面板里有「版本号 + 检查更新」这一行");
  check(s.versionText === "版本 9.9.8", "版本号显示二进制里的 9.9.8(不是源码字面量)", s && s.versionText);
  check(s.label === "检查更新", "初始字面 = 检查更新", s && s.label);
  check(s.hit.w >= 24 && s.hit.h >= 24, "按钮命中区 ≥24×24", s && s.hit);
  check(s.sameLine < 6, "版本号与按钮在同一行(中心线差 < 6px)", s && s.sameLine);
  check(s.overflow.scroll <= 0, "没有因为这一行多出滚动条(设置面板仍然一屏放下)", s && s.overflow);
  check(s.overflow.rowH <= 20, "这一行仍然只有单行文字那么高", s && s.overflow.rowH);
  check(s.contrast >= 4.5, "「检查更新」文字对比度 ≥4.5:1(AA 正文)", s && s.contrast);
  console.log(`  info 实测对比度: 空态 ${s.contrast}:1`);

  // 键盘可达 + 焦点环
  let focused = null;
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press("Tab");
    const now = await read(page);
    if (now && now.activeIsBtn) { focused = now; break; }
  }
  check(!!focused, "键盘能 Tab 到「检查更新」");
  check(!!focused && focused.boxShadow !== "none", "键盘聚焦时有可见焦点环", focused && focused.boxShadow);

  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  await page.close();
}

// ── 场景 2: 拿不到二进制版本 → 退回构建期注入的 package.json 版本 ──────────────
console.log("场景 2 · 拿不到二进制版本(纯浏览器) → 兜底成构建期注入的版本:");
{
  const { page, errs } = await makePage(null);
  const s = await read(page);
  check(s.versionText === `版本 ${PKG_VERSION}`, `兜底显示 package.json 的 ${PKG_VERSION}`, s && s.versionText);
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  await page.close();
}

// ── 场景 3: 有新版本 → 字面/悬停/点击去发布页 ────────────────────────────────
console.log("场景 3 · 线上更新(桩返回 v9.9.9):");
{
  const { page, errs } = await makePage("9.9.8");
  const seen = [];
  await routeApi(page, { tag: "v9.9.9", html_url: RELEASE_URL }, seen);
  await clickUpdate(page);
  const s = await waitLabel(page, /有新版本/);
  check(seen.length === 1 && seen[0].url === API, "点击打到 /releases/latest", seen);
  check(seen.length === 1 && seen[0].accept === "application/vnd.github+json", "请求带 GitHub media type", seen);
  check(s.label === "有新版本 9.9.9", "字面 = 有新版本 9.9.9", s && s.label);
  check(s.contrast >= 4.5, "「有新版本」文字对比度 ≥4.5:1(AA 正文)", s && s.contrast);
  console.log(`  info 实测对比度: 有新版本 ${s.contrast}:1`);
  check(s.title === "打开 GitHub 发布页下载", "悬停说明 = 去发布页", s && s.title);
  check(!s.disabled, "检查完按钮恢复可点");

  await clickUpdate(page); // 这一下是"去下载", 不是"再检查"
  const opened = await page.evaluate("window.__OPENED__");
  check(opened.length === 1 && opened[0] === RELEASE_URL, "点一下真的去打开发布页(openUrl → 兜底 window.open)", opened);
  check(seen.length === 1, "去发布页没有再打一次接口", seen.length);
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  await page.close();
}

// ── 场景 4: 已经是最新 ─────────────────────────────────────────────────────
console.log("场景 4 · 已是最新(桩返回 v9.9.8, 与本机相同):");
{
  const { page, errs } = await makePage("9.9.8");
  await routeApi(page, { tag: "v9.9.8", html_url: RELEASE_URL });
  await clickUpdate(page);
  const s = await waitLabel(page, /已是最新/);
  check(s.label === "已是最新", "字面 = 已是最新", s && s.label);
  check(/重新检查/.test(s.title || ""), "悬停说明保留了“再点一次”的出路", s && s.title);
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  await page.close();
}

// ── 场景 5: 三种失败各自说得清 ─────────────────────────────────────────────
console.log("场景 5 · 失败也要说清是哪一类:");
{
  const cases = [
    { name: "403(匿名限额/被挡)", opt: { status: 403 }, wantLabel: "检查失败，重试", wantTitle: /HTTP 403/ },
    { name: "断网(请求被 abort)", opt: { abort: true }, wantLabel: "检查失败，重试", wantTitle: /网络不可用/ },
  ];
  for (const c of cases) {
    const { page, errs } = await makePage("9.9.8");
    await routeApi(page, c.opt);
    await clickUpdate(page);
    const s = await waitLabel(page, /检查失败/);
    check(s.label === c.wantLabel, `${c.name} → 字面 = ${c.wantLabel}`, s && s.label);
    check(c.wantTitle.test(s.title || ""), `${c.name} → 悬停说明 = ${c.wantTitle}`, s && s.title);
    check(!s.disabled, `${c.name} → 失败后按钮仍可点(能重试)`);
    // 桩自己造成的网络报错(403 / ERR_FAILED)是浏览器打的, 不是应用打的 —— 只放过这两条
    const appErrs = errs.filter((e) => !/Failed to load resource/.test(e));
    check(appErrs.length === 0, `${c.name} → 无 console 报错`, appErrs.slice(0, 3));
    await page.close();
  }
}

// ── 场景 6: 检查中的状态(接口故意慢 600ms) ─────────────────────────────────
console.log("场景 6 · 检查中(接口慢 600ms):");
{
  const { page, errs } = await makePage("9.9.8");
  await routeApi(page, { tag: "v9.9.9", html_url: RELEASE_URL, delay: 600 });
  await clickUpdate(page);
  await page.waitForTimeout(150);
  const s = await read(page);
  check(s.label === "正在检查…", "字面 = 正在检查…", s && s.label);
  check(s.disabled === true && s.busy === "true", "检查中按钮 disabled + aria-busy", s && { d: s.disabled, b: s.busy });
  const s2 = await waitLabel(page, /有新版本/);
  check(s2.label === "有新版本 9.9.9", "慢响应也能落到结果", s2 && s2.label);
  check(s2.busy === null && s2.disabled === false, "落地后 aria-busy 摘掉、恢复可点", s2 && { b: s2.busy, d: s2.disabled });
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  await page.close();
}

// ── 场景 7: 英文 / 长文案 —— 这一行会不会被撑成两行 ─────────────────────────
console.log("场景 7 · 切成英文(文案更长)后这一行仍然是一行:");
{
  const { page, errs } = await makePage("9.9.8");
  await routeApi(page, { tag: "v9.9.9", html_url: RELEASE_URL });
  await page.getByRole("button", { name: "EN", exact: true }).click();
  await page.waitForTimeout(300);
  const idle = await read(page);
  check(idle.versionText === "Version 9.9.8", "英文版本号文案", idle && idle.versionText);
  check(idle.label === "Check for updates", "英文空态字面", idle && idle.label);
  check(idle.overflow.rowH <= 20 && idle.sameLine < 6, "英文下仍然一行放得下", idle && { h: idle.overflow.rowH, dy: idle.sameLine });
  check(idle.overflow.scroll <= 0, "英文下没有多出滚动条", idle && idle.overflow);
  await clickUpdate(page);
  const s = await waitLabel(page, /available/);
  check(s.label === "9.9.9 available", "英文有新版本字面", s && s.label);
  check(s.overflow.rowH <= 20 && s.sameLine < 6, "英文「有新版本」也还是一行", s && { h: s.overflow.rowH, dy: s.sameLine });
  check(errs.length === 0, "无 console 报错", errs.slice(0, 3));
  if (process.env.SHOT_EN) await page.screenshot({ path: process.env.SHOT_EN });
  await page.close();
}

await browser.close();
console.log("\n" + (failed === 0 ? "ALL PASS" : "FAILURES") + ": " + passed + " passed, " + failed + " failed");
process.exit(failed === 0 ? 0 : 1);
