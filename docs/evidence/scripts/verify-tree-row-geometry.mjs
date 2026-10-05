// 树行几何断言：
//   1. 行高必须 24px（WCAG 2.5.8）
//   2. 箭头必须是同一个字形 + 靠 transform 旋转（不是 ▶/▼ 换字形）
//   3. 可展开的行有 aria-expanded，叶子没有该属性
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const HERE = dirname(fileURLToPath(import.meta.url));
const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const MOCK = readFileSync(resolve(HERE, "tree-mock.js"), "utf8");

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e)));
await page.addInitScript(MOCK);
await page.goto("http://localhost:1420/.design-audit/harness.html?gap=0", { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(600);

const clickText = (t) => page.evaluate((s) => {
  const b = [...document.querySelectorAll("button")].find((x) => x.textContent.includes(s));
  if (!b) return false;
  b.click(); return true;
}, t);

await clickText("EOS_DIGITAL");
await page.waitForTimeout(500);
await clickText("DCIM");
await page.waitForTimeout(500);

const tree = () => page.evaluate(() => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  const box = root.parentElement;
  const rows = [...box.querySelectorAll("button")].filter((b) => !b.textContent.includes("根目录"));
  return rows.map((b) => {
    const r = b.getBoundingClientRect();
    const name = b.querySelector("span.truncate");
    return {
      text: b.textContent.trim().slice(0, 12),
      h: +r.height.toFixed(2),
      w: +r.width.toFixed(2),
      aria: b.getAttribute("aria-expanded"),
    };
  });
});

// ── 判定与退出码 ──
// 这个探针是本任务唯一的验证产物，而 Task 4 会把它归档进 docs/evidence/。
// 只在 console 里喊 FAIL 而不设退出码，等于任何自动化调用者都会把回归读成成功
// （实测过：旧版打印 FAIL 之后仍然 exit=0）。所以判定一律走 check()。
let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`ok   ${label}`); }
  else { failed++; console.log(`FAIL ${label}${detail ? "  " + detail : ""}`); }
}

const before = await tree();
const heights = [...new Set(before.map((r) => r.h))];
console.log("行数:", before.length, " 去重行高:", heights.join(", "));

// 断言 1：行高 24
const badH = before.filter((r) => r.h !== 24);
check(badH.length === 0, "所有行高 24px", badH.map((r) => `${r.text}=${r.h}`).join(", "));

// 断言 2：箭头必须是**同一个字形 + 靠 transform 旋转**，而不是换字形。
// ⚠️ 别写成"展开后其它行的文字不位移" —— 旧实现里箭头那层 span 是固定的
//    w-3 flex-shrink-0，后面的文字本来就不会动，那种断言在改动前就已经通过，
//    没有任何鉴别力。这一条在改动前会真的失败：旧实现渲染 ▶/▼ 两个不同字符，
//    且完全没有 transform。
const caretOf = (name) => page.evaluate((n) => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  const row = [...root.parentElement.querySelectorAll("button")].find((r) => r.textContent.includes(n));
  if (!row) return { err: "no row " + n };
  // 新旧实现里箭头都在行内第一个 span 里，所以这个取法两边都成立
  const span = row.querySelector(".tree-caret") || row.querySelector("span");
  const el = span.querySelector("i") || span.firstElementChild || span;
  return { glyph: (el.textContent || "").trim(), transform: getComputedStyle(el).transform };
}, name);

const closedCaret = await caretOf("102EOSR5");
await clickText("102EOSR5");
await page.waitForTimeout(800);
const openCaret = await caretOf("102EOSR5");
console.log("收起态箭头:", JSON.stringify(closedCaret));
console.log("展开态箭头:", JSON.stringify(openCaret));

check(!!closedCaret.glyph && closedCaret.glyph === openCaret.glyph,
  `两个状态是同一个字形 "${closedCaret.glyph}"`,
  `"${closedCaret.glyph}" → "${openCaret.glyph}"`);
// 只判 !== "none" 不够：rotate(180deg) 或 scale(0) 也会通过。
// 直接把矩阵钉成 rotate(90deg) 在 Chromium 里的形式 matrix(0, 1, -1, 0, 0, 0)。
check(closedCaret.transform === "none" && openCaret.transform === "matrix(0, 1, -1, 0, 0, 0)",
  "靠 transform: rotate(90deg) 旋转",
  `收起 ${closedCaret.transform} / 展开 ${openCaret.transform}`);

const after = await tree();

// 断言 3：可展开的行有 aria-expanded，叶子**一个属性都没有**（不是 "false"）。
// 分类必须按"有没有箭头元素"判，不能按名字里有没有 "EOSR5" —— 叶子 103EOSR5 会被
// 名字子串扫进"可展开行"那一桶，把唯一一眼可见的那行标签写错。
const rowInfo = await page.evaluate(() => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  return [...root.parentElement.querySelectorAll("button")]
    .filter((b) => !b.textContent.includes("根目录"))
    .map((b) => ({ name: b.textContent.trim().slice(0, 12), expandable: !!b.querySelector(".tree-caret i"), aria: b.getAttribute("aria-expanded") }));
});
const expandableRows = rowInfo.filter((r) => r.expandable);
const leafRows = rowInfo.filter((r) => !r.expandable);
console.log("可展开行的 aria-expanded:", JSON.stringify(expandableRows.map((r) => [r.name, r.aria])));
console.log("叶子行的 aria-expanded:", JSON.stringify(leafRows.map((r) => [r.name, r.aria])));
check(expandableRows.length > 0 && expandableRows.every((r) => r.aria === "true" || r.aria === "false"),
  "可展开行都有 aria-expanded（true 或 false）",
  JSON.stringify(expandableRows.map((r) => [r.name, r.aria])));
check(leafRows.length > 0 && leafRows.every((r) => r.aria === null),
  "叶子行完全没有 aria-expanded 属性（不是 false）",
  JSON.stringify(leafRows.map((r) => [r.name, r.aria])));

// 断言 4：箭头字形不能进可访问名 —— 这是本轮 aria-hidden="true" 改动的**唯一目的**，
// 加了属性却不断言它，等于这个改动没有任何验证覆盖。
// 算法：把 aria-hidden 的子树摘掉再看文本，与可访问名"隐藏子树不参与"的规则一致。
const accName = (n) => page.evaluate((name) => {
  const root = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("根目录"));
  const row = [...root.parentElement.querySelectorAll("button")].find((r) => r.textContent.includes(name));
  const clone = row.cloneNode(true);
  clone.querySelectorAll('[aria-hidden="true"]').forEach((e) => e.remove());
  return clone.textContent.replace(/\s+/g, " ").trim();
}, n);
const nameDCIM = await accName("DCIM");
const nameLeaf = await accName("100CANON");
console.log("可访问名:", JSON.stringify({ DCIM: nameDCIM, "100CANON": nameLeaf }));
check(!/[▶▼]/.test(nameDCIM + nameLeaf), "箭头字形不在可访问名里", JSON.stringify({ nameDCIM, nameLeaf }));
check(nameDCIM.startsWith("DCIM"), "可访问名以行文本开头", nameDCIM);

// 截图按需: SHOT=<path> node verify-tree-row-geometry.mjs
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
console.log("page errors:", errs.length ? errs : "none");
check(errs.length === 0, "无页面错误", JSON.stringify(errs));
await browser.close();

console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
