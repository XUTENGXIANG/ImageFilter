// 上下两条栏"真浮窗"布局的回归断言。
//
// 三件事必须同时成立, 缺一个就是肉眼可见的毛病:
//   1. 网格铺满整个高度(这是"真浮窗"的代价与目的)
//   2. 两条栏写出的 CSS 变量 == 它们此刻的真实高度(折叠/展开都要对)
//   3. 滚到两端时, 首行/末行都在**留白之外** —— 即不会被浮窗盖住
//
// 留白常量与 App.tsx 里的 1.5rem 对应(两端相同)。
//
// 跑法: node verify-floating-bars.mjs
//   APP_URL 覆盖地址(默认 http://localhost:1420); PW_BASE / CHROME 覆盖 playwright 与浏览器
// 退出码: 0 = 通过, 1 = 失败
import { createRequire } from "node:module";
const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = process.env.CHROME || "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const BASE = process.env.APP_URL || "http://localhost:1420";
const CLEARANCE = 24; // 1.5rem

let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`  ok   ${label}`); }
  else { failed++; console.log(`  FAIL ${label}${detail ? "  " + detail : ""}`); }
}

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1202, height: 802 }, deviceScaleFactor: 2 });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e)));
page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errs.push(`[${m.type()}] ${m.text()}`); });
await page.goto(BASE + "/.design-audit/harness.html?photos=1&count=24", { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(2800);

const state = () => page.evaluate(() => {
  const wrap = [...document.querySelectorAll("div")].find((d) => d.className.includes("overflow-auto") && d.querySelector(".photo-grid"));
  const main = document.querySelector("main");
  const top = document.querySelector("main > div.absolute.top-0");
  const bottom = document.querySelector("main > div.absolute.bottom-0");
  const cards = [...document.querySelectorAll(".photo-grid > *")];
  const root = getComputedStyle(document.documentElement);
  return {
    变量: {
      上: root.getPropertyValue("--top-bar-h").trim(),
      下: root.getPropertyValue("--bottom-bar-h").trim(),
    },
    栏实测: {
      上: Math.round(top.getBoundingClientRect().height),
      下: Math.round(bottom.getBoundingClientRect().height),
    },
    栏边界: { 上栏底: Math.round(top.getBoundingClientRect().bottom), 下栏顶: Math.round(bottom.getBoundingClientRect().top) },
    网格视口高: Math.round(wrap.getBoundingClientRect().height),
    main高: Math.round(main.getBoundingClientRect().height),
    首卡顶: Math.round(cards[0].getBoundingClientRect().top),
    末卡底: Math.round(cards[cards.length - 1].getBoundingClientRect().bottom),
  };
});

const scrollTo = (where) => page.evaluate((w) => {
  const wrap = [...document.querySelectorAll("div")].find((d) => d.className.includes("overflow-auto") && d.querySelector(".photo-grid"));
  wrap.scrollTop = w === "top" ? 0 : wrap.scrollHeight;
}, where).then(() => page.waitForTimeout(400));

for (const [label, expand] of [["两条都收起(默认态)", false], ["两条都展开(极限态)", true]]) {
  if (expand) {
    await page.evaluate(() => {
      [...document.querySelectorAll("button")].find((b) => b.textContent.trim() === "筛选")?.click();
      [...document.querySelectorAll("button")].find((b) => b.textContent.includes("高级选项"))?.click();
    });
    await page.waitForTimeout(900);
  }
  console.log(`\n=== ${label} ===`);

  await scrollTo("top");
  const s = await state();
  console.log(`  变量 ${s.变量.上}/${s.变量.下}  栏实测 ${s.栏实测.上}/${s.栏实测.下}  网格视口 ${s.网格视口高} vs main ${s.main高}`);
  check(s.变量.上 === `${s.栏实测.上}px` && s.变量.下 === `${s.栏实测.下}px`,
    "CSS 变量与两条栏的真实高度一致", JSON.stringify({ 变量: s.变量, 实测: s.栏实测 }));
  check(s.网格视口高 === s.main高, "网格铺满整个高度(真浮窗)", `${s.网格视口高} vs ${s.main高}`);

  console.log(`  滚到顶: 上栏底 ${s.栏边界.上栏底}  首卡顶 ${s.首卡顶}  → 留白 ${s.首卡顶 - s.栏边界.上栏底}px`);
  check(s.首卡顶 - s.栏边界.上栏底 >= CLEARANCE - 1,
    `默认态顶部留白 >= ${CLEARANCE}px`, `${s.首卡顶 - s.栏边界.上栏底}px`);

  await scrollTo("bottom");
  const s2 = await state();
  console.log(`  滚到底: 下栏顶 ${s2.栏边界.下栏顶}  末卡底 ${s2.末卡底}  → 留白 ${s2.栏边界.下栏顶 - s2.末卡底}px`);
  check(s2.栏边界.下栏顶 - s2.末卡底 >= CLEARANCE - 1,
    `底部留白 >= ${CLEARANCE}px`, `${s2.栏边界.下栏顶 - s2.末卡底}px`);
  check(s2.末卡底 <= s2.栏边界.下栏顶, "滚到极限时末行不在控件下面", `${s2.末卡底} vs ${s2.栏边界.下栏顶}`);
}

check(errs.length === 0, "控制台无 error / warning", JSON.stringify(errs.slice(0, 3)));
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
await browser.close();
process.exit(failed === 0 ? 0 : 1);
