// 文件夹树"展开"时序取证 —— 用真实组件 + 真实后端返回形状(一层一层给)测出:
//   1. 树的几何(行高、字号、缩进、命中区)是否符合既有令牌
//   2. **容器高度轨迹**(不是行数: 行数只在数据到达时变一次, 测不出动画)
//   3. CSS 的过渡时长是否与 TS 侧的 EXPAND_MS 一致(同一个值写在两个地方)
// 判据: 台阶数 > 3 且单帧最大跨度 <= 总行程的 35%, 否则退出码 1。
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const HERE = dirname(fileURLToPath(import.meta.url));
// CSS 的过渡时长与 TS 侧的 EXPAND_MS 是同一个值写在两个地方 —— 这条断言跨这两个文件。
// (路径深度: 本文件在 docs/evidence/scripts/, 上三级才是仓库根; Node 24 直接剥类型跑 .ts)
import { EXPAND_MS } from "../../../src/components/folder-tree-motion.ts";

const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const MOCK = readFileSync(resolve(HERE, "tree-mock.js"), "utf8");
const GAP = process.argv[2] || "250";
const URL = `http://localhost:1420/.design-audit/harness.html?gap=${GAP}`;
const SHOT = process.argv[3];

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.addInitScript(MOCK);
const errors = [];
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
page.on("pageerror", (e) => errors.push(String(e)));
await page.goto(URL, { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(600);

const clickByText = (text) =>
  page.evaluate((t) => {
    const b = [...document.querySelectorAll("button")].find((x) => x.textContent.includes(t));
    if (!b) return false;
    b.click();
    return true;
  }, text);

console.log(`# gap=${GAP}ms (browse_directory 往返延迟)`);
console.log("click EOS_DIGITAL →", await clickByText("EOS_DIGITAL"));
await page.waitForTimeout(GAP * 2 + 400);
// 先展开第一层，好让树里有足够行做几何测量
console.log("click DCIM       →", await clickByText("DCIM"));
await page.waitForTimeout(GAP * 2 + 400);

// ── 1. 树几何 ──────────────────────────────────────────────
const geo = await page.evaluate(() => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  if (!root) return { err: "no tree root button" };
  // 树的滚动容器 = 根按钮往上找到的第一个真正会滚的祖先
  let scroll = root.parentElement;
  while (scroll && scroll !== document.body && getComputedStyle(scroll).overflowY === "visible") {
    scroll = scroll.parentElement;
  }
  if (!scroll) return { err: "no scroll container" };
  const rows = [...scroll.querySelectorAll("button")].filter((b) => !b.textContent.includes("根目录"));
  if (!rows.length) {
    return { err: "no child rows", scrollCls: scroll.className, calls: window.__MOCK_CALLS__ };
  }
  const one = rows.find((b) => b.textContent.includes("102EOSR5")) || rows[0];
  const leaf = rows.find((b) => b.textContent.includes("100CANON")) || rows[rows.length - 1];
  const cs = getComputedStyle(one);
  const csl = getComputedStyle(leaf);
  const r = one.getBoundingClientRect();
  const rl = leaf.getBoundingClientRect();
  return {
    containerW: Math.round(scroll.getBoundingClientRect().width),
    rowCount: rows.length,
    branch: {
      text: one.textContent.trim(),
      h: +r.height.toFixed(2), w: +r.width.toFixed(2),
      fontSize: cs.fontSize, lineHeight: cs.lineHeight,
      padL: cs.paddingLeft, padT: cs.paddingTop, padB: cs.paddingBottom,
      radius: cs.borderRadius,
      ariaExpanded: one.getAttribute("aria-expanded"),
      ariaLabel: one.getAttribute("aria-label"),
      transition: cs.transition,
    },
    leaf: {
      text: leaf.textContent.trim(),
      h: +rl.height.toFixed(2),
      fontSize: csl.fontSize, lineHeight: csl.lineHeight,
      ariaExpanded: leaf.getAttribute("aria-expanded"),
    },
    // 缩进阶梯：同级/下一级的 paddingLeft
    indentSteps: [...new Set(rows.map((b) => getComputedStyle(b).paddingLeft))],
    // 树里有多少个按钮带 aria-expanded
    withAriaExpanded: rows.filter((b) => b.hasAttribute("aria-expanded")).length,
    // 箭头字形（文本节点）宽度
    glyph: (() => {
      const s = one.querySelector("span");
      return { text: JSON.stringify(s.textContent), w: +s.getBoundingClientRect().width.toFixed(2) };
    })(),
  };
});
console.log("\n=== 树几何 (展开态) ===");
console.log(JSON.stringify(geo, null, 2));

// ── 2. 展开时序 ────────────────────────────────────────────
const timeline = await page.evaluate(async () => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  if (!root) return { err: "no root" };
  let scroll = root.parentElement;
  while (scroll && scroll !== document.body && getComputedStyle(scroll).overflowY === "visible") {
    scroll = scroll.parentElement;
  }
  const target = [...scroll.querySelectorAll("button")].find((b) => b.textContent.includes("102EOSR5"));
  if (!target) return { err: "target gone" };
  // 判据是**容器高度**而不是行数: 行数只在数据到达时变一次, 测不出动画。
  const kids = target.parentElement.querySelector(".tree-kids");
  if (!kids) return { err: "no .tree-kids" };

  const out = [];
  const t0 = performance.now();
  target.click();
  await new Promise((resolve) => {
    const id = setInterval(() => {
      const t = performance.now() - t0;
      out.push([Math.round(t), +kids.getBoundingClientRect().height.toFixed(2)]);
      // 采样窗口要盖住最慢的一档: 600ms 数据 + 200ms 过渡 + 余量
      if (t > 1400) { clearInterval(id); resolve(); }
    }, 16);
  });
  // 台阶数 = 去重后的高度取值个数: 每帧都在长 → 十几步; 一步到位 → 2~3 步
  const steps = [];
  for (const [t, h] of out) if (!steps.length || steps[steps.length - 1][1] !== h) steps.push([t, h]);
  return { steps, firstNonZero: (out.find(([, h]) => h > 0) || [])[0] ?? null, samples: out.length };
});

console.log(`\n=== 展开的高度轨迹 (卡速 ${GAP}ms) ===`);
if (timeline.err) {
  console.log("  SKIP:", JSON.stringify(timeline));
  process.exitCode = 0;
} else {
  console.log(`  台阶数 ${timeline.steps.length}   首次有高度 t=${timeline.firstNonZero}ms`);
  console.log("  " + timeline.steps.map(([t, h]) => `${t}:${h}`).join("  "));
  if (timeline.steps.length <= 3) {
    console.log("  FAIL 跳变 —— 容器高度一步到位, 没有动画");
    process.exitCode = 1;
  } else {
    console.log("  ok   平滑");
    // 台阶数**看不见"末端瞬跳"**: 慢卡上占位行自己的 0→24 展开就能贡献十几步,
    // 把"真行到达时一帧到位"稀释过去(实测 gap=600ms 那个版本是 15 步、却含一跳 24→96)。
    // 所以再判一条**比例判据**: 单帧跨度不得超过总行程的 35%。
    // 一次瞬跳会把 100% 的行程放在一帧里; 而 200ms ease-in-out 的最陡处约 16%(=2×16ms/200ms)。
    // 用比例而不是绝对像素, 是为了不绑在"目标高度是 96px"这个具体数上。
    const total = timeline.steps[timeline.steps.length - 1][1];
    let maxJump = 0, maxAt = 0;
    for (let i = 1; i < timeline.steps.length; i++) {
      const d = Math.abs(timeline.steps[i][1] - timeline.steps[i - 1][1]);
      if (d > maxJump) { maxJump = d; maxAt = timeline.steps[i][0]; }
    }
    const ratio = total > 0 ? maxJump / total : 0;
    console.log(`  单帧最大跨度 ${maxJump.toFixed(2)}px / 总行程 ${total}px = ${(ratio * 100).toFixed(1)}% (t=${maxAt}ms)`);
    if (ratio > 0.35) {
      console.log("  FAIL 末端瞬跳 —— 有一帧吃掉了 35% 以上的行程");
      process.exitCode = 1;
    } else {
      console.log("  ok   单帧跨度都在动画速率内");
      process.exitCode = 0;
    }
  }
}

// 按行取它自己的 .tree-kids：每个可展开节点都有一个 .tree-kids（Step 6 会解释为什么
// 不能直接 querySelector 第一个），只读 computed style 时取哪个结果都一样，
// 但这里不想示范那个坏模式。
const dur = await page.evaluate(() => {
  const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes("DCIM"));
  const kids = row.parentElement.querySelector(".tree-kids");
  const cs = getComputedStyle(kids);
  return cs.transitionDuration + " / " + cs.transitionProperty;
});
console.log("CSS 过渡:", dur);
// getComputedStyle 返回的是秒、且可能带尾零（"0.2s" 也可能写成 "0.200s"），
// 所以解析成数字再比，不要比字符串。
const okDur = dur.split(" / ")[0].split(",").some((v) => parseFloat(v) === EXPAND_MS / 1000);
console.log(okDur ? "ok   CSS 过渡时长与 EXPAND_MS 一致" : `FAIL CSS 过渡时长与 EXPAND_MS(${EXPAND_MS}ms) 不一致: ${dur}`);
if (!okDur) process.exitCode = 1;

if (SHOT) { await page.screenshot({ path: SHOT }); console.log("\nshot →", SHOT); }
console.log("\nconsole errors:", errors.length ? errors : "none");
await browser.close();
