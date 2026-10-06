// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// 子树常驻挂载带来的**唯一**无障碍风险，本探针就是它的判据：
// 收起的分支子行仍然挂在 DOM 里（高度动画的前提），但必须同时对键盘与读屏不可达 ——
// inert + aria-hidden。若哪天有人把 inert 删掉，子行会重新漏进 Tab 顺序（WCAG 4.1.2），
// 而"高度动画看起来仍然正常"，所以这个回归没有任何别的东西能抓到。
// 顺带验证 auto 收尾：嵌套分支展开时父容器必须跟着长（固定像素高度会把内容裁掉）。
// 只测量 + 判定这两件事, 不评判审美。
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const HERE = dirname(fileURLToPath(import.meta.url));

const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const MOCK = readFileSync(resolve(HERE, "tree-mock.js"), "utf8");
const GAP = process.argv[2] || "0";
const URL = `http://localhost:1420/.design-audit/harness.html?gap=${GAP}`;

let failed = 0, passed = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? "ok  " : "FAIL"} ${label}${detail !== undefined ? "   " + detail : ""}`);
  ok ? passed++ : failed++;
};

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errs = [];
page.on("console", (m) => m.type() === "error" && errs.push(m.text()));
page.on("pageerror", (e) => errs.push(String(e)));
await page.addInitScript(MOCK);
await page.goto(URL, { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(600);

// 设备行(EOS_DIGITAL / 根目录)不是 .tree-row(它们有自己的类), 所以先按树行找、再退回任意按钮。
const click = (t) => page.evaluate((n) => {
  const r = [...document.querySelectorAll(".tree-row")].find((x) => x.textContent.includes(n))
    || [...document.querySelectorAll("button")].find((x) => x.textContent.includes(n));
  if (!r) return false;
  r.click();
  return true;
}, t);

// 每个可展开节点都有一个 .tree-kids（含未展开的），所以必须**按行走**，
// 直接 querySelector(".tree-kids") 取到的是第一个、未必是目标那个。
const pick = (name) => page.evaluate((n) => {
  const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes(n));
  if (!row) return { err: "no row " + n };
  const kids = row.parentElement.querySelector(".tree-kids");
  if (!kids) return { err: "no .tree-kids under " + n };
  return {
    isInert: kids.hasAttribute("inert"),
    ariaHidden: kids.getAttribute("aria-hidden"),
    height: Math.round(kids.getBoundingClientRect().height),
    focusables: kids.querySelectorAll("button:not([inert])").length,
  };
}, name);

console.log(`\n=== 1. inert / aria-hidden (卡速 ${GAP}ms) ===`);
console.log("click EOS_DIGITAL →", await click("EOS_DIGITAL"));
await page.waitForTimeout(GAP * 2 + 500);
console.log("click DCIM        →", await click("DCIM"));
await page.waitForTimeout(GAP * 2 + 500);

// 未展开的 103EOSR5（桩里它下面有 RAW / JPG）
const collapsed = await pick("103EOSR5");
const expanded = await pick("DCIM");
console.log("collapsed:", JSON.stringify(collapsed));
console.log("expanded :", JSON.stringify(expanded));
check(collapsed.isInert === true, "收起分支的子树容器带 inert", JSON.stringify(collapsed));
check(collapsed.ariaHidden === "true", "收起分支 aria-hidden=true", JSON.stringify(collapsed));
check(collapsed.height === 0, "收起分支高度为 0", `height=${collapsed.height}`);
check(expanded.isInert === false, "展开分支不带 inert", JSON.stringify(expanded));
check(expanded.ariaHidden === "false", "展开分支 aria-hidden=false", JSON.stringify(expanded));
check(expanded.height > 0, "展开分支高度 > 0", `height=${expanded.height}`);

console.log(`\n=== 2. 三层嵌套展开不被裁 (卡速 ${GAP}ms) ===`);
// 桩树最深一条链: DCIM → 102EOSR5（4 个拍摄目录）与 DCIM → 103EOSR5 → RAW/JPG。
// 每次之间等 800ms: 越过 EXPAND_MS + AUTO_SETTLE_SLACK_MS(250ms) 的 auto 收尾，
// 否则测到的是"还在过渡中"的中间高度，而不是收尾是否切成了 auto。
console.log("expand 102EOSR5 →", await click("102EOSR5"));
await page.waitForTimeout(800);
console.log("expand 103EOSR5 →", await click("103EOSR5"));
await page.waitForTimeout(800);

const nested = await page.evaluate(() => {
  const wrapOf = (n) => {
    const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes(n));
    if (!row) return null;
    return row.parentElement.querySelector(".tree-kids");
  };
  const out = {};
  for (const n of ["DCIM", "103EOSR5"]) {
    const w = wrapOf(n);
    if (!w) { out[n] = { err: "no .tree-kids" }; continue; }
    out[n] = {
      outer: Math.round(w.getBoundingClientRect().height),
      content: w.firstElementChild.scrollHeight,
      inline: w.style.height || "(none)",
    };
  }
  return out;
});
console.log("nested:", JSON.stringify(nested));
// 注意必须把 {err} 也算成失败: 两个 undefined 用 !== 比是 false,
// 只写 outer !== content 会让"容器根本不存在"这种最坏情况**假通过**。
const bad = Object.entries(nested).filter(([, v]) => v.err || v.outer !== v.content);
check(Object.keys(nested).length === 2 && bad.length === 0, "嵌套展开未裁剪（outer === scrollHeight）", JSON.stringify(bad));
// DCIM 这一条是关键：它的内容因为子孙分支展开而变高，固定像素高度会把这段裁掉，
// 所以 inline height 此时必须已经收尾成 auto。
check(nested.DCIM && nested.DCIM.inline === "auto", "DCIM 容器已收尾成 height:auto", nested.DCIM && nested.DCIM.inline);

console.log(`\n=== 3. 收起后子行不进 Tab 顺序 (卡速 ${GAP}ms) ===`);
// 收起 DCIM —— 它的子行**仍然挂在 DOM 里**（高度动画的前提），但必须够不到。
// 漏掉 inert 的话这一条会立刻红：第一次 Tab 就落在 100CANON 上。
console.log("collapse DCIM →", await click("DCIM"));
await page.waitForTimeout(600);

// 挂载证明。计划里写的是 `.tree-row`.length > 7，但那个阈值在"只展开过 DCIM"的状态下
// 达不到：DCIM 收起 = 它自己 1 行 + 5 个子行 = 6 行（桩树里 DCIM 只有这 5 个子目录）。
// 阈值写成永远不成立的数会变成一条恒 FAIL 的断言，所以这里直接判**具名子行是否还在 DOM 里**
// —— 这才是"没被卸载"的真正证据（若整棵子树被卸载，这些名字一个都找不到，Tab 测试就是假通过）。
const mounted = await page.evaluate(() => {
  const rows = [...document.querySelectorAll(".tree-row")];
  const names = ["100CANON", "101CANON", "102EOSR5", "103EOSR5", "200CANON"];
  return { count: rows.length, present: names.filter((n) => rows.some((r) => r.textContent.includes(n))) };
});
console.log("收起后 .tree-row 数量:", mounted.count, " 仍在 DOM 的具名子行:", JSON.stringify(mounted.present));
check(mounted.present.length === 5, "收起的 5 个子行仍挂在 DOM 里（不是被卸载）", JSON.stringify(mounted));

// 焦点先落在 DCIM 行上：这样第一次 Tab 就直接撞到子树边界，
// 比从文档开头盲按 25 次更能立刻暴露泄漏。
await page.evaluate(() => {
  const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes("DCIM"));
  if (row) row.focus();
});
const reached = [];
for (let i = 0; i < 25; i++) {
  await page.keyboard.press("Tab");
  reached.push(await page.evaluate(() => (document.activeElement.textContent || "").trim().slice(0, 14)));
}
const leaked = reached.filter((t) => /CANON|EOSR5/.test(t));
console.log("Tab 可达:", JSON.stringify([...new Set(reached)]));
check(leaked.length === 0, "收起后子行不可达", leaked.length ? JSON.stringify([...new Set(leaked)]) : "");

console.log("page errors:", errs.length ? errs : "none");
check(errs.length === 0, "无控制台 error", JSON.stringify(errs));

await browser.close();
console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
