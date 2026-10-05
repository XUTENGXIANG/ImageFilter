// 玻璃开关的"随系统版本给默认值"端到端取证。
//
// 四个场景:
//   1. 本机(Win11) 全新安装        → 开, 开关可点
//   2. ?win10=1    全新安装        → 关, 开关置灰, 说明文案已替换
//   3. ?win10=1    但 localStorage 已存 "1"(老用户) → 仍然开(不被纠正)
//   4. ?osfail=1   探测失败        → 开, 开关可点(完整退回今天的行为)
//
// 先决条件: npm run tauri dev 在跑, 且 .design-audit/harness.html 存在 —— 它是 Tauri IPC 的
//           浏览器桩, 按设计不入库(只 mock 了 Tauri 桥, 由 Vite 提供 /src/main.tsx)。
// 断言读的是设置面板里那个开关的真实 DOM 状态(aria-pressed / disabled / 说明文案),
// 不是 React 内部状态。
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const HERE = dirname(fileURLToPath(import.meta.url));
const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const MOCK = readFileSync(resolve(HERE, "tree-mock.js"), "utf8");
const BASE = "http://localhost:1420/.design-audit/harness.html";

let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`  ok   ${label}`); }
  else { failed++; console.log(`  FAIL ${label}${detail ? "  " + detail : ""}`); }
}

const browser = await chromium.launch({ executablePath: EXE });

async function scenario(name, query, presetGlass, shotPath) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
  const errs = [];
  page.on("pageerror", (e) => errs.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errs.push(`[${m.type()}] ${m.text()}`); });
  await page.addInitScript(MOCK);
  if (presetGlass !== undefined) {
    await page.addInitScript((v) => { localStorage.setItem("imagefilter-glass", v); }, presetGlass);
  }
  await page.goto(BASE + query, { waitUntil: "domcontentloaded" });
  await page.waitForSelector("#root > *");
  await page.waitForTimeout(600);

  // 打开设置面板(标题栏那个按钮的可访问名由 Tip 注入)
  const opened = await page.evaluate(() => {
    const b = [...document.querySelectorAll("button")].find((x) => (x.getAttribute("aria-label") || "").includes("设置"));
    if (!b) return false;
    b.click(); return true;
  });
  if (!opened) { console.log(`  FAIL 找不到设置按钮`); failed++; await page.close(); return null; }
  await page.waitForTimeout(400);

  const row = await page.evaluate(() => {
    for (const b of document.querySelectorAll("button[aria-pressed]")) {
      const r = b.closest("div.flex.items-start");
      if (r && r.textContent.includes("透明毛玻璃背景")) {
        const ps = r.querySelectorAll("p");
        return {
          title: ps[0] ? ps[0].textContent : null,
          desc: ps[1] ? ps[1].textContent : null,
          pressed: b.getAttribute("aria-pressed"),
          disabled: b.disabled,
        };
      }
    }
    return null;
  });
  console.log(`\n=== ${name} ===`);
  if (!row) { console.log("  FAIL 找不到那一行"); failed++; await page.close(); return null; }
  console.log(`  标题=${row.title} / 说明="${row.desc}" / aria-pressed=${row.pressed} / disabled=${row.disabled}`);
  check(errs.length === 0, "控制台无 error / warning", JSON.stringify(errs.slice(0, 2)));
  if (shotPath) {
    const box = await page.evaluate(() => {
      for (const b of document.querySelectorAll("button[aria-pressed]")) {
        const r = b.closest("div.flex.items-start");
        if (r && r.textContent.includes("透明毛玻璃背景")) {
          const rect = r.getBoundingClientRect();
          return { x: rect.x - 10, y: rect.y - 10, width: rect.width + 20, height: rect.height + 20 };
        }
      }
      return null;
    });
    if (box) await page.screenshot({ path: shotPath, clip: box });
  }
  await page.close();
  return row;
}

// 1. 本机 Win11, 全新安装
const a = await scenario("本机 Win11 · 全新安装", "", undefined, resolve(HERE, "os-win11.png"));
if (a) {
  check(a.pressed === "true", "开关为开", a.pressed);
  check(a.disabled === false, "开关可点", String(a.disabled));
  check(a.desc === "用 Windows Mica 玻璃背景", "说明是正常文案", a.desc);
}

// 2. Win10, 全新安装
const b = await scenario("模拟 Win10 · 全新安装", "?win10=1", undefined, resolve(HERE, "os-win10.png"));
if (b) {
  check(b.pressed === "false", "开关为关", b.pressed);
  check(b.disabled === true, "开关已置灰", String(b.disabled));
  check(b.desc === "当前系统不支持（需要 Windows 11）", "说明文案已替换", b.desc);
}

// 3. Win10 + 老用户已存 "1"
const c = await scenario("模拟 Win10 · 老用户已存 \"1\"", "?win10=1", "1", null);
if (c) {
  check(c.pressed === "true", "仍然为开(老用户不被纠正)", c.pressed);
  check(c.disabled === true, "仍然置灰(但值保留)", String(c.disabled));
}

// 4. 探测失败
const d = await scenario("探测失败(?osfail=1) · 全新安装", "?osfail=1", undefined, null);
if (d) {
  check(d.pressed === "true", "开关为开(退回今天的行为)", d.pressed);
  check(d.disabled === false, "开关不置灰", String(d.disabled));
}

console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
await browser.close();
process.exit(failed === 0 ? 0 : 1);
