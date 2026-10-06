// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// 两处新展开动画的取证: 导入栏「高级选项」、工具栏「筛选」面板。
//
// 判据与文件夹树同一套: 量**容器高度轨迹**。台阶数 > 3 才算真在动;
// 一步到位(2~3 阶)就是没动画。收起方向也要量 —— 只开不合的动画看着更假。
import { createRequire } from "node:module";
const require = createRequire("file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const URL = "http://localhost:1420/.design-audit/harness.html?photos=1&count=12";

let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`  ok   ${label}`); }
  else { failed++; console.log(`  FAIL ${label}${detail ? "  " + detail : ""}`); }
}

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e)));
page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errs.push(`[${m.type()}] ${m.text()}`); });
await page.goto(URL, { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(2500);

/** 按内容找那一块折叠容器(页面上有多个 grid-template-rows 容器: 两条折叠条 + 两个面板) */
const COLLAPSES = [
  { name: "导入栏 · 高级选项", btn: "高级选项", marker: "按序号重命名" },
  { name: "工具栏 · 筛选面板", btn: "筛选", marker: "分析结果" },
];

async function trajectory(markerText, ms = 700) {
  return page.evaluate(({ marker, ms }) => new Promise((resolve) => {
    // 页面上有多个 grid-template-rows 容器: 两条折叠条 + 两个面板, 而面板就在折叠条**里面**,
    // 所以外层容器也包含这段文字。取文档序最后一个 = 最内层那个, 否则量到的是整条折叠条。
    const all = [...document.querySelectorAll('div[style*="grid-template-rows"]')]
      .filter((d) => d.textContent.includes(marker));
    const box = all[all.length - 1];
    if (!box) { resolve({ err: "找不到容器" }); return; }
    const t0 = performance.now();
    const out = [];
    const tick = () => {
      out.push([Math.round(performance.now() - t0), +box.getBoundingClientRect().height.toFixed(2)]);
      if (performance.now() - t0 > ms) {
        const steps = [];
        for (const [t, h] of out) if (!steps.length || steps[steps.length - 1][1] !== h) steps.push([t, h]);
        resolve({
          steps,
          finalH: out[out.length - 1][1],
          inert: box.firstElementChild.hasAttribute("inert"),
          layers: all.length,
        });
        return;
      }
      requestAnimationFrame(tick);
    };
    tick();
  }), { marker: markerText, ms });
}

const clickBtn = (text) => page.evaluate((t) => {
  const btns = [...document.querySelectorAll("button")];
  // 先精确匹配(否则"筛选"会先撞上"清除筛选"), 再退回包含匹配(高级选项按钮的文本是"▸高级选项")
  const b = btns.find((x) => x.textContent.trim() === t) || btns.find((x) => x.textContent.includes(t));
  if (!b) return false;
  b.click(); return true;
}, text);

for (const c of COLLAPSES) {
  console.log(`\n=== ${c.name} ===`);
  const clicked = await clickBtn(c.btn);
  if (!clicked) { check(false, `找到并点击「${c.btn}」`); continue; }

  // 展开
  const open = await trajectory(c.marker, 700);
  if (open.err) { check(false, "找到折叠容器", open.err); continue; }
  console.log(`  展开: 台阶 ${open.steps.length}  终高 ${open.finalH}px  命中层数 ${open.layers}`);
  console.log("    " + open.steps.slice(0, 10).map(([t, h]) => `${t}:${h}`).join("  "));
  check(open.steps.length > 3, "展开是动画(台阶 > 3)", `台阶 ${open.steps.length}`);
  check(open.finalH > 10, "展开后确实有高度", `${open.finalH}px`);
  check(open.inert === false, "展开时不是 inert");

  // 收起
  await clickBtn(c.btn);
  const shut = await trajectory(c.marker, 700);
  console.log(`  收起: 台阶 ${shut.steps.length}  终高 ${shut.finalH}px`);
  check(shut.steps.length > 3, "收起也是动画(台阶 > 3)", `台阶 ${shut.steps.length}`);
  check(shut.finalH < 1, "收起后高度归零", `${shut.finalH}px`);
  check(shut.inert === true, "收起时带 inert(收起的表单不会漏进 Tab 顺序)");
}

check(errs.length === 0, "控制台无 error / warning", JSON.stringify(errs.slice(0, 2)));

console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
await browser.close();
process.exit(failed === 0 ? 0 : 1);
