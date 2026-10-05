# 文件夹树展开动效 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把左侧文件夹树的展开从"静默一段然后整块蹦出来"改成一出现就平滑展开的动效，并把树行行高从 20.5px 提到 24px。

**Architecture:** 子目录是**一层一层异步取**的（`browse.rs:26-32` 给子项的 `subfolders` 一律空 `vec![]`），所以动效不能挂在"点击"上、必须挂在"子行到达"上。子树容器的高度由组件按 `inner.scrollHeight` 实测后用行内样式驱动：点击时若 120ms 内还没拿到子行就先展开一个占位行，子行到达后再长到真实高度，过渡结束后切成 `height: auto` 让嵌套分支能跟随内容。判断"该处于哪个阶段"的逻辑抽成纯函数，用 esbuild + node 断言。

**Tech Stack:** React 19 / TypeScript / Vite 6 / Tailwind v4；测试用 esbuild + node（项目既有约定，**不引 vitest**）。

## Global Constraints

- **不新增任何依赖**，`package.json` 不动。测试走 `npx esbuild` + `node`，与 `docs/evidence/scripts/lrc-logic.test.ts` 同一套。
- **树行高度只在 `src/index.css` 的 `.tree-row` 规则里声明一次。** JS 里不出现这个数。
  （说清楚：`24px` 这个长度在别处另有用途 —— `.bg-grid` 的点阵步长、`.hit-24` 的命中区下界、
  `thumb-size-slider.tsx` 的盒高 —— 那些与本方案无关，不是重复。约束针对的是"树行高度"这一个语义。）
- 子树高度**一律由 `inner.scrollHeight` 实测得出**，不写死像素。
- **不使用 `transitionend` / `onTransitionEnd`**。`src/index.css` 末尾的全局 reduced-motion 块注释里写明"项目内没有任何代码依赖 transitionend"，`src/` 下 grep 也为零。收尾一律用定时器。
- 动效时长/缓动与既有令牌一致：**200ms / `ease-in-out`**（同 `src/components/collapsible-bar.tsx:21`）。
- **不改** `src/App.tsx` 的渲染结构、`src/useScanner.ts`、以及任何 Rust 代码。
- **不引入** `role="tree"` / `treeitem` / 方向键导航。
- **不改**"点击行 = 展开 + 选中该文件夹"这个耦合（现状行为，属于交互设计变更，另议）。
- 浏览器探针写在 `.design-audit/_probe/`（已 gitignore），**只有结论与可复用脚本归档到 `docs/evidence/`**（既有约定，见 `docs/evidence/README.md`）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `src/components/folder-tree-motion.ts` | **新建。** 纯逻辑：阶段判定 `treePhase()` + 三个时间常量。无 DOM、无 React，可在 node 里直接断言。 |
| `docs/evidence/scripts/tree-motion.test.ts` | **新建。** 上面那份纯逻辑的断言（esbuild + node）。 |
| `src/components/folder-tree-item.tsx` | **改写。** 递归树节点：行几何、箭头、阶段接线、高度驱动、无障碍属性、占位行。 |
| `src/index.css` | **追加。** `.tree-row` / `.tree-caret` / `.tree-kids` / `.tree-row-skeleton`，与 `.badge-glass`、`.thumb-slider`、`.hit-24` 同层。 |
| `docs/evidence/folder-tree-motion/EVIDENCE.md` | **新建（Task 4）。** 实测数据归档。 |

---

### Task 1: 树行几何与箭头

先把"行"本身改对：行高 20.5px → 24px，并把 ▶/▼ 换字形改成同一个 ▶ 旋转。这一步不涉及任何异步时序，独立可验。

**Files:**
- Modify: `src/index.css`（在 `.thumb-slider:focus-visible` 之后追加两个块）
- Modify: `src/components/folder-tree-item.tsx:22-31`（行 button 的 class / 内联样式 / 箭头）
- Test: `.design-audit/_probe/verify-tree-row-geometry.mjs`（新建，一次性探针）

**Interfaces:**
- Consumes: 无
- Produces: CSS 类 `.tree-row`（高度 24px，后续 Task 3 的占位行复用）、`.tree-caret`（箭头旋转容器，靠 `[aria-expanded="true"]` 驱动）

- [ ] **Step 1: 写几何探针（先让它失败）**

新建 `.design-audit/_probe/verify-tree-row-geometry.mjs`。它复用已有的 `mock-tree.js` 桩（`browse_directory` 只返回一层子目录，与 Rust 一致）和 `playwright-core`（从 DSH profile 解析）：

```js
// 树行几何断言：
//   1. 行高必须 24px（WCAG 2.5.8）
//   2. 箭头必须是同一个字形 + 靠 transform 旋转（不是 ▶/▼ 换字形）
//   3. 可展开的行有 aria-expanded，叶子没有该属性
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
const require = createRequire("file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const MOCK = readFileSync("A:\\tenent\\.design-audit\\_probe\\mock-tree.js", "utf8");

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

await page.screenshot({ path: "A:\\tenent\\.design-audit\\_probe\\tree-row-after.png" });
console.log("page errors:", errs.length ? errs : "none");
check(errs.length === 0, "无页面错误", JSON.stringify(errs));
await browser.close();

console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
```

- [ ] **Step 2: 跑探针，确认它现在失败**

Run: `node .design-audit\_probe\verify-tree-row-geometry.mjs`
Expected: **退出码 1**，末行 `FAILURES: N passed, 8 failed` 里至少有这五条：
- `FAIL 所有行高 24px  100CANON=20.5, …`
- `FAIL 两个状态是同一个字形 "▶"  "▶" → "▼"`
- `FAIL 靠 transform: rotate(90deg) 旋转  收起 none / 展开 none`
- `FAIL 可展开行都有 aria-expanded（true 或 false）  []` —— 旧代码里根本没有 `.tree-caret`，按"有没有箭头元素"分类时一个可展开行都认不出来
- `FAIL 箭头字形不在可访问名里  {"DCIM":"▶DCIM874",…}` —— 加 `aria-hidden` 之前，箭头确实在可访问名里

先决条件：`npm run tauri dev` 已在跑，Vite 在 `localhost:1420`（只绑 IPv6，不能用 `127.0.0.1`）。

- [ ] **Step 3: 在 index.css 追加行与箭头的规则**

在 `src/index.css` 的 `.thumb-slider:focus-visible` 块之后插入：

```css
/* ── 左侧文件夹树的"行" ──
   行高 24px: WCAG 2.5.8 的目标尺寸下限。原先靠 11px 字号 + 上下各 2px 内边距隐式得到
   20.5px, 是左侧面板里唯一低于 24px 的行(面板收起按钮/刷新 24、设备行 28、根目录 26.5),
   而它恰恰是数量最多、最常点的那个。
   树行高度只在这一处声明 —— 占位行复用 .tree-row, 所以自动同高,
   JS 里不需要知道这个数(子树高度一律由 scrollHeight 实测)。 */
.tree-row {
  height: 24px;
}

/* 箭头改成同一个 ▶ 字形旋转, 而不是 ▶/▼ 换字形。
   真正的理由: **换字形没法做过渡** —— 它是文本内容的变化, CSS transition 无从插值,
   而 Task 3 需要展开时箭头转过去(160ms), 所以必须是一个能 transform 的元素。
   (顺带说明一个曾经写错过的点: 这不是为了修"文字横向抖动"。旧实现里箭头那层 span 是
    固定的 w-3 flex-shrink-0, 后面的文字本来就不会动。) */
.tree-caret i {
  display: inline-block;
  font-style: normal;
  transition: transform 160ms ease-out;
}
.tree-row[aria-expanded="true"] .tree-caret i {
  transform: rotate(90deg);
}
```

- [ ] **Step 4: 改 folder-tree-item.tsx 的行与箭头**

把 `src/components/folder-tree-item.tsx:15-38` 的 `return (...)` 开头到 `</button>` 替换为（`aria-expanded` 在这里就补上，Task 3 不再重复）：

```tsx
  return (
    <div>
      <button
        onClick={() => {
          if (canExpand) setOpen(!open);
          onSelect(node.path);
        }}
        aria-expanded={canExpand ? open : undefined}
        className={`tree-row w-full text-left rounded text-[11px] flex items-center gap-1 ${
          isActive
            ? "bg-emerald-900/30 text-emerald-300"
            : "text-zinc-400 hover:bg-zinc-800/50"
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px" }}
      >
        <span className="tree-caret text-[10px] w-3 flex-shrink-0 flex items-center justify-center" aria-hidden="true">
          {canExpand ? <i>▶</i> : <Folder theme="filled" size={12} />}
        </span>
        <span className="truncate">{node.name}</span>
        {!(counting && node.photoCount === 0) && (
          <span className="text-zinc-600 ml-auto flex-shrink-0">
            {node.photoCount}
          </span>
        )}
      </button>
```

注意**五处**改动：加 `tree-row` 类；**删掉内联的 `paddingTop` / `paddingBottom`**（高度已由类给出，`items-center` 负责垂直居中）；箭头从 `{canExpand ? (open ? "▼" : "▶") : <Folder .../>}` 改成恒为 `<i>▶</i>`，靠 `aria-expanded` 驱动 CSS 旋转；箭头那层 span 加 **`aria-hidden="true"`**；叶子的 `size="12"` 写成 `size={12}`（React 两种写法渲染结果相同，只是跟着 JSON 风格的属性写法走）。

> 为什么加 `aria-hidden`：这个 span 是装饰，但它现在**在按钮的可访问名里** —— 读屏会把整行读成"▶ DCIM 874"。规格 §7 写的是"可访问名保持现状（行文本 `DCIM 874`）"，而现状其实带着那个箭头字形，所以这里要顺手修正，让实际行为和规格描述一致。

- [ ] **Step 5: 跑探针，确认八条断言全通过**

Run: `node .design-audit\_probe\verify-tree-row-geometry.mjs`
Expected: **退出码 0**，末行 `ALL PASS: 8 passed, 0 failed`，包含 `ok   所有行高 24px`、`ok   两个状态是同一个字形 "▶"`、`ok   靠 transform: rotate(90deg) 旋转`、`ok   可展开行都有 aria-expanded（true 或 false）`、`ok   叶子行完全没有 aria-expanded 属性（不是 false）`、`ok   箭头字形不在可访问名里`、`ok   可访问名以行文本开头`、`ok   无页面错误`。

- [ ] **Step 6: 看图确认旋转后的 ▶ 读起来是"向下"且光学居中**

Run: `node .design-audit\_probe\verify-tree-row-geometry.mjs`（会写出 `tree-row-after.png`），然后**用 read_image 看图**。
Expected: 展开行的箭头是向下的实心三角，与折叠行（向右）能一眼区分；箭头在 12px 框内居中，没有偏上/偏下；与叶子的 `Folder` 图标在同一水平线上。

> 为什么要看图：`▶`(U+25B6) 的墨迹在基线上而不在盒中心，旋转 90° 后是否仍然居中取决于字体。这一步没有数值判据，必须肉眼确认。

- [ ] **Step 7: 提交**

```bash
git add src/index.css src/components/folder-tree-item.tsx
git commit -m "fix(tree): 树行行高 20.5px → 24px, 箭头改用同一个 ▶ 旋转"
```

---

### Task 2: 纯阶段判定 + 单测

把"该处于哪个阶段"的四条规则抽成纯函数。这一步没有任何 DOM，四条容易写错的规则全靠 node 断言钉住。

**Files:**
- Create: `src/components/folder-tree-motion.ts`
- Test: `docs/evidence/scripts/tree-motion.test.ts`

**Interfaces:**
- Consumes: 无
- Produces: `type TreePhase = "closed" | "placeholder" | "content"`；`treePhase(i: TreePhaseInput): TreePhase`；常量 `PLACEHOLDER_DELAY_MS = 120`、`EXPAND_MS = 200`、`AUTO_SETTLE_SLACK_MS = 50`。Task 3 全部依赖这些名字。

- [ ] **Step 1: 写失败的单测**

新建 `docs/evidence/scripts/tree-motion.test.ts`：

```ts
// src/components/folder-tree-motion.ts 的纯逻辑断言
// 跑法(项目既有做法: 临时 esbuild + Node, 不引 vitest):
//   npx esbuild docs/evidence/scripts/tree-motion.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/tree-motion.mjs
//   node .design-audit/_probe/tree-motion.mjs
// (产物落在 gitignore 的 .design-audit/ 下, 不入库 —— lrc-logic 那份是把产物也入了库, 这里不沿用)
import {
  treePhase,
  PLACEHOLDER_DELAY_MS,
  EXPAND_MS,
  AUTO_SETTLE_SLACK_MS,
} from "../../../src/components/folder-tree-motion";

let fail = 0;
let pass = 0;
function eq(name: string, got: unknown, want: unknown) {
  const g = JSON.stringify(got);
  const w = JSON.stringify(want);
  if (g === w) { pass++; console.log("  ok   " + name); }
  else { fail++; console.log("  FAIL " + name + "\n        got  " + g + "\n        want " + w); }
}

console.log("treePhase · 基本态:");
eq("没点开 → closed",
  treePhase({ open: false, canExpand: true, childCount: 0, placeholderElapsed: false }), "closed");
eq("已缓存子行 + 点开 → content",
  treePhase({ open: true, canExpand: true, childCount: 4, placeholderElapsed: false }), "content");

console.log("treePhase · 容易写错的四条:");
eq("叶子节点(不可展开) → closed, 即使占位延迟已过",
  treePhase({ open: true, canExpand: false, childCount: 0, placeholderElapsed: true }), "closed");
eq("快卡直连: 点开但 120ms 内子行还没到 → closed(不闪占位行)",
  treePhase({ open: true, canExpand: true, childCount: 0, placeholderElapsed: false }), "closed");
eq("慢卡: 点开且 120ms 后仍无子行 → placeholder",
  treePhase({ open: true, canExpand: true, childCount: 0, placeholderElapsed: true }), "placeholder");
eq("收起时子行还在 → closed(不能因为延迟已过就展出占位行)",
  treePhase({ open: false, canExpand: true, childCount: 5, placeholderElapsed: true }), "closed");
eq("content 优先于 placeholder(子行到达时占位延迟也已过)",
  treePhase({ open: true, canExpand: true, childCount: 4, placeholderElapsed: true }), "content");

console.log("常量(改动它们会破坏 index.css 里的过渡时长对应关系):");
eq("PLACEHOLDER_DELAY_MS", PLACEHOLDER_DELAY_MS, 120);
eq("EXPAND_MS", EXPAND_MS, 200);
eq("AUTO_SETTLE_SLACK_MS", AUTO_SETTLE_SLACK_MS, 50);

console.log("\n" + (fail === 0 ? "ALL PASS" : "FAILURES") + ": " + pass + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);
```

- [ ] **Step 2: 跑测试，确认它失败**

Run:
```bash
npx esbuild docs/evidence/scripts/tree-motion.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/tree-motion.mjs
node .design-audit/_probe/tree-motion.mjs
```
Expected: esbuild 报 `Could not resolve "../../../src/components/folder-tree-motion"`（文件还不存在），退出码非 0。

- [ ] **Step 3: 写实现**

新建 `src/components/folder-tree-motion.ts`：

```ts
/** 子树容器该处于哪个视觉阶段。
 *
 *  抽成纯函数是因为这里有几条反直觉、且写错了在快卡上完全看不出来的规则:
 *   - 子行没到之前不能挂动画(点击就挂会让慢卡静默失效, 见规格 §3.2)
 *   - 120ms 内子行就到了的话根本不该出现占位行(否则快卡上闪一下灰条)
 *   - 收起状态即使占位延迟已过也不能展出占位行
 */
export type TreePhase = "closed" | "placeholder" | "content";

/** 点击后多久还没拿到子行才显示占位行。
 *  取决于真实 browse_directory 的延迟分布, 是本方案唯一需要在真机上复核的值:
 *  本机是 NTFS SSD(一层目录几毫秒), 而 browse.rs:25 的 has_subdirectories 对每个
 *  子目录都要探一次, 真实 SD 卡上会明显更慢。 */
export const PLACEHOLDER_DELAY_MS = 120;

/** 高度过渡时长。必须与 src/index.css 里 .tree-kids 的 transition 一致。 */
export const EXPAND_MS = 200;

/** 过渡结束后把 height 从像素值切成 auto 的余量。
 *  项目约定不用 transitionend(index.css 末尾的全局 reduced-motion 块里写明),
 *  所以用定时器收尾。 */
export const AUTO_SETTLE_SLACK_MS = 50;

export interface TreePhaseInput {
  /** 用户是否点了展开 */
  open: boolean;
  /** node.hasSubdirs || node.children.length > 0 */
  canExpand: boolean;
  /** node.children.length —— 子行是否已经被 loadFolder 写回 */
  childCount: number;
  /** 点击后 PLACEHOLDER_DELAY_MS 是否已经过去 */
  placeholderElapsed: boolean;
}

export function treePhase(i: TreePhaseInput): TreePhase {
  if (!i.open || !i.canExpand) return "closed";
  if (i.childCount > 0) return "content";
  return i.placeholderElapsed ? "placeholder" : "closed";
}
```

- [ ] **Step 4: 跑测试，确认 11 条全通过**

Run:
```bash
npx esbuild docs/evidence/scripts/tree-motion.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/tree-motion.mjs
node .design-audit/_probe/tree-motion.mjs
```
Expected: `ALL PASS: 11 passed, 0 failed`，退出码 0。

- [ ] **Step 5: 提交**

```bash
git add src/components/folder-tree-motion.ts docs/evidence/scripts/tree-motion.test.ts
git commit -m "feat(tree): 展开阶段判定抽成纯函数并补断言"
```

---

### Task 3: 展开/收起动效接线

把阶段接到 DOM 上：子树常驻挂载、高度按 `scrollHeight` 驱动、慢卡先展开占位行、收尾切成 `auto`。同时补齐 `inert` / `aria-hidden` / 占位行的读屏文案。

**Files:**
- Modify: `src/index.css`（追加 `.tree-kids` 与占位行）
- Modify: `src/components/folder-tree-item.tsx`（整体改写）
- Test: `.design-audit/_probe/verify-tree-expand.mjs`（扩展一个"台阶数"断言）

**Interfaces:**
- Consumes: Task 2 的 `treePhase` / `PLACEHOLDER_DELAY_MS` / `EXPAND_MS` / `AUTO_SETTLE_SLACK_MS`；Task 1 的 `.tree-row` / `.tree-caret`
- Produces: CSS 类 `.tree-kids`（子树容器）、`.tree-row-skeleton` / `.tree-skeleton-bar`（占位行）

- [ ] **Step 1: 把探针改成测"容器高度轨迹"（先让它失败）**

⚠️ 已有的 `verify-tree-expand.mjs` 数的是**行数**变化 —— 行数只在数据到达时变一次，拿它当判据会把"跳变"和"平滑"测成同一个结果。动画在**容器高度**上，判据必须换成高度台阶数。

把 `verify-tree-expand.mjs` 里第二段（`const timeline = await page.evaluate(...)` 直到最后 `console.log`）整体替换为：

```js
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
    process.exitCode = 0;
  }
}
```

- [ ] **Step 2: 跑探针（0 / 250 / 600 三档），确认全部失败**

Run:
```bash
node .design-audit\_probe\verify-tree-expand.mjs 0
node .design-audit\_probe\verify-tree-expand.mjs 250
node .design-audit\_probe\verify-tree-expand.mjs 600
```
Expected: 三档都是 `台阶数 2` + `FAIL 跳变`，退出码 1。（这就是"现状没有任何动画"的可执行证据。）

- [ ] **Step 3: 在 index.css 追加子树容器与占位行**

在 Task 1 追加的 `.tree-row` / `.tree-caret` 之后继续追加：

```css
/* 子树容器。高度由组件按内容 scrollHeight 实测后写在行内(JS 不知道 24 这个数)。
   transition 常驻、只翻值 —— 与 collapsible-bar.tsx 同一写法;
   时长必须与 folder-tree-motion.ts 的 EXPAND_MS 一致。 */
.tree-kids {
  overflow: hidden;
  transition: height 200ms ease-in-out;
}

/* 加载占位行。与真行同高(复用 .tree-row), 但**不能读成"这是个空文件夹"** ——
   所以不画文件夹图标、不画计数, 只有一根呼吸的灰条。 */
.tree-row-skeleton {
  cursor: default;
}
.tree-skeleton-bar {
  display: inline-block;
  height: 8px;
  width: 64px;
  border-radius: 2px;
  background: #3f3f46; /* zinc-700, 与 .thumb-slider 用字面量同风格 */
  animation: tree-skeleton-pulse 1s ease-in-out infinite;
}
@keyframes tree-skeleton-pulse {
  50% { opacity: 0.4; }
}
```

- [ ] **Step 4: 改写 folder-tree-item.tsx**

整份替换 `src/components/folder-tree-item.tsx`：

```tsx
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Folder } from "@icon-park/react";
import type { FolderNode } from "../types";
import {
  treePhase,
  PLACEHOLDER_DELAY_MS,
  EXPAND_MS,
  AUTO_SETTLE_SLACK_MS,
} from "./folder-tree-motion";

/** 占位行 —— 不是按钮, 不可聚焦。
 *  用 role="status" + 视觉隐藏文案向读屏说明"在加载"; 注意实时区域是随内容一起插入的,
 *  部分读屏可能不播报, 所以主信号仍是父行的 aria-expanded="true"。 */
function SkeletonRow({ depth, label }: { depth: number; label: string }) {
  return (
    <div
      className="tree-row tree-row-skeleton flex items-center gap-1 rounded text-[11px] text-zinc-600"
      style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px" }}
      role="status"
    >
      <span className="sr-only">{label}</span>
      {/* 空占位撑出与真行相同的缩进: 真行在名字前有一个 12px 的箭头槽 */}
      <span className="w-3 flex-shrink-0" aria-hidden="true" />
      <span className="tree-skeleton-bar" aria-hidden="true" />
    </div>
  );
}

export function FolderTreeItem({
  node, activeFolder, onSelect, depth, counting,
}: {
  node: FolderNode; activeFolder: string; onSelect: (path: string) => void;
  depth: number; counting: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [placeholderElapsed, setPlaceholderElapsed] = useState(false);
  const canExpand = node.hasSubdirs || node.children.length > 0;
  const isActive = activeFolder === node.path;

  const kidsRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  /** 代次: 每次阶段变化 +1, 让先前排队的定时器/rAF 全部作废 */
  const genRef = useRef(0);
  /** 是否已经跑过一次高度 effect —— 见下面"首次运行不收起"的说明 */
  const mountedRef = useRef(false);

  const phase = treePhase({
    open, canExpand, childCount: node.children.length, placeholderElapsed,
  });

  // 占位行的 120ms 延迟: 快卡上子行会在这之前到达, 于是根本不出现占位行。
  // 收起时把标记清掉 —— 否则"延迟到点了"会在收起状态下展出占位行。
  useEffect(() => {
    if (!open || node.children.length > 0) { setPlaceholderElapsed(false); return; }
    const timer = window.setTimeout(() => setPlaceholderElapsed(true), PLACEHOLDER_DELAY_MS);
    return () => { window.clearTimeout(timer); setPlaceholderElapsed(false); };
  }, [open, node.children.length]);

  // 高度驱动。用 layout effect: 在浏览器绘制前就把高度定好, 避免闪一帧错误高度
  // (首次挂载时容器还没有行内高度, 不设的话会先按内容自然高度画出来)。
  useLayoutEffect(() => {
    const el = kidsRef.current;
    const inner = innerRef.current;
    if (!el || !inner) return;
    const myGen = ++genRef.current;
    const alive = () => genRef.current === myGen;
    // 首次运行不许"从当前高度收起": 挂载瞬间没有动画可言, 而一个"子行已缓存但收起"的
    // 分支此刻的自然高度是满高 —— 按它钉一下就会在首帧撑开再收回(逐帧看得见的抽动)。
    // 现阶段 children 只在展开时才有, 所以这个分支实际不会走到; 但别依赖这个巧合。
    const from = mountedRef.current ? el.getBoundingClientRect().height : 0;
    mountedRef.current = true;

    if (phase === "closed") {
      if (from > 0) {
        // height: auto 无法直接插值到 0, 必须先把当前像素高度钉住、下一帧再设 0 ——
        // 这是从 auto 收起唯一能出动画的写法。
        el.style.height = `${from}px`;
        requestAnimationFrame(() => { if (alive()) el.style.height = "0px"; });
      } else {
        el.style.height = "0px";
      }
      return;
    }

    el.style.height = `${inner.scrollHeight}px`;
    // 收尾切成 auto, 好让嵌套分支展开时父容器能跟着长(固定像素高度会把内容裁掉)。
    // 项目约定不用 transitionend, 所以用定时器。
    const settle = window.setTimeout(() => {
      if (alive()) el.style.height = "auto";
    }, EXPAND_MS + AUTO_SETTLE_SLACK_MS);
    return () => { window.clearTimeout(settle); };
  }, [phase]);

  return (
    <div>
      <button
        onClick={() => {
          if (canExpand) setOpen(!open);
          onSelect(node.path);
        }}
        aria-expanded={canExpand ? open : undefined}
        className={`tree-row w-full text-left rounded text-[11px] flex items-center gap-1 ${
          isActive
            ? "bg-emerald-900/30 text-emerald-300"
            : "text-zinc-400 hover:bg-zinc-800/50"
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px" }}
      >
        <span className="tree-caret text-[10px] w-3 flex-shrink-0 flex items-center justify-center" aria-hidden="true">
          {canExpand ? <i>▶</i> : <Folder theme="filled" size={12} />}
        </span>
        <span className="truncate">{node.name}</span>
        {!(counting && node.photoCount === 0) && (
          <span className="text-zinc-600 ml-auto flex-shrink-0">
            {node.photoCount}
          </span>
        )}
      </button>
      {/* 子树常驻挂载: 这是高度动画的前提, 也正因为常驻, 收起时必须 inert + aria-hidden,
          否则收起的子行会漏进 Tab 顺序与无障碍树(WCAG 4.1.2)。
          never-opened 的节点 children 为空, 渲染出来是空的, 不产生 DOM 规模。 */}
      {canExpand && (
        <div className="tree-kids" ref={kidsRef} inert={!open} aria-hidden={!open}>
          <div ref={innerRef}>
            {phase === "placeholder" ? (
              <SkeletonRow depth={depth + 1} label={t("devices.loading")} />
            ) : (
              node.children.map((c) => (
                <FolderTreeItem
                  key={c.path}
                  node={c}
                  activeFolder={activeFolder}
                  onSelect={onSelect}
                  depth={depth + 1}
                  counting={counting}
                />
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: 跑探针，确认四档都平滑**

Run:
```bash
node .design-audit\_probe\verify-tree-expand.mjs 0
node .design-audit\_probe\verify-tree-expand.mjs 32
node .design-audit\_probe\verify-tree-expand.mjs 250
node .design-audit\_probe\verify-tree-expand.mjs 600
```
Expected: 四档都是 `ok   平滑`（台阶数 > 10），退出码 0。
参考基准（规格 §3.2 实测）：32ms 应约 15 台阶、250ms 应约 26 台阶。

- [ ] **Step 6: 确认收起的子树不进 Tab 顺序**

新建 `.design-audit/_probe/verify-tree-inert.mjs`：加载桩、展开 `DCIM`，然后连续按 Tab 并记录焦点元素的可访问名，断言**没有出现已收起分支的子行名字**（如 `100CANON`、`101CANON`）。

⚠️ **不能直接 `document.querySelector(".tree-kids")`** —— 每个可展开节点都有一个 `.tree-kids`（含未展开的），所以要按行走：

```js
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

// 未展开的 103EOSR5（桩里它下面有 RAW / JPG）
console.log("collapsed:", JSON.stringify(await pick("103EOSR5")));
// 已展开的 DCIM
console.log("expanded :", JSON.stringify(await pick("DCIM")));
```

Run: `node .design-audit\_probe\verify-tree-inert.mjs`
Expected: `collapsed` 为 `isInert: true`、`ariaHidden: "true"`、`height: 0`；`expanded` 为 `isInert: false`、`ariaHidden: "false"`、`height` > 0。

**真正的 Tab 断言（这是本方案唯一引入的无障碍风险）**：展开 `DCIM` → 再收起 `DCIM`，此时它的子行**仍然挂在 DOM 里**（高度动画的前提），但 Tab 必须够不到它们。用 `page.keyboard.press("Tab")` 连续遍历并收集 `document.activeElement` 的文本：

```js
const reached = [];
for (let i = 0; i < 25; i++) {
  await page.keyboard.press("Tab");
  reached.push(await page.evaluate(() => (document.activeElement.textContent || "").trim().slice(0, 14)));
}
const leaked = reached.filter((t) => /CANON|EOSR5/.test(t));
console.log("Tab 可达:", JSON.stringify([...new Set(reached)]));
console.log(leaked.length ? `FAIL 收起后子行仍可达: ${JSON.stringify([...new Set(leaked)])}` : "ok  收起后子行不可达");
```

Expected: 收起 `DCIM` 后，`reached` 里**不出现** `100CANON` / `101CANON` / `200CANON`（它们的 wrapper 带 `inert`），同时 `document.querySelectorAll(".tree-row").length` 仍 > 7（证明它们确实还挂在 DOM 里、没有被卸载 —— 若被卸载，这个测试就是假通过）。

- [ ] **Step 7: 确认嵌套展开时父容器不被裁**

在同一个探针里加一段：依次展开 `DCIM` → `102EOSR5` → `103EOSR5`（桩里 `103EOSR5` 下面还有 `RAW` / `JPG`），每次之间等 800ms（要越过 250ms 的 auto 收尾），然后断言**祖先容器的实际高度等于其内容的 scrollHeight**：

```js
const nested = await page.evaluate(() => {
  const wrapOf = (n) => {
    const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes(n));
    return row.parentElement.querySelector(".tree-kids");
  };
  const out = {};
  for (const n of ["DCIM", "103EOSR5"]) {
    const w = wrapOf(n);
    out[n] = { outer: Math.round(w.getBoundingClientRect().height),
               content: w.firstElementChild.scrollHeight };
  }
  return out;
});
console.log("nested:", JSON.stringify(nested));
const bad = Object.entries(nested).filter(([, v]) => v.outer !== v.content);
console.log(bad.length ? `FAIL 容器被裁: ${JSON.stringify(bad)}` : "ok  嵌套展开未裁剪");
```

Run: `node .design-audit\_probe\verify-tree-inert.mjs`
Expected: `ok  嵌套展开未裁剪`（两处 `outer === content`，即 `height: auto` 在起作用）。`DCIM` 这一条是关键：它的内容因为 `103EOSR5` 展开了 RAW/JPG 而变高，固定像素高度会把这段裁掉。

- [ ] **Step 8: 全量类型检查 + 控制台**

Run:
```bash
npx tsc --noEmit
node .design-audit\_probe\verify-console-both.mjs
```
Expected: `tsc exit: 0`；两个状态都 `console: 无 error / warning`。

- [ ] **Step 9: 提交**

```bash
git add src/index.css src/components/folder-tree-item.tsx
git commit -m "feat(tree): 展开/收起接上高度动效, 慢卡先展开占位行"
```

---

### Task 4: 验收取证与归档

把 Task 1~3 的实测数据按既有约定归档到 `docs/evidence/`，让结论离开探针也读得出来。

**Files:**
- Create: `docs/evidence/folder-tree-motion/EVIDENCE.md`
- Create: `docs/evidence/folder-tree-motion/expand-{0,32,250,600}ms.txt`（探针原始输出）
- Create: `docs/evidence/folder-tree-motion/row-geometry.txt`、`inert-and-nesting.txt`
- Create: `docs/evidence/scripts/verify-tree-motion.mjs`（从 gitignore 的 `.design-audit/` 提出来，去掉绝对路径）
- Create: `docs/evidence/scripts/verify-tree-motion-mechanisms.mjs`（四机理对照，同上）
- Modify: `docs/evidence/README.md`（在 `scripts/` 表格里加两行）
- 已存在（Task 2 提交）：`docs/evidence/scripts/tree-motion.test.ts`

**Interfaces:**
- Consumes: Task 1~3 的全部产物
- Produces: 无（终态）

- [ ] **Step 1: 把可复用的探针复制到 docs/evidence/scripts/**

新建 `docs/evidence/scripts/verify-tree-motion.mjs`：内容是 `.design-audit/_probe/verify-tree-expand.mjs`，只改三处硬编码路径，让它不绑在这台机器上：

```js
// 改动 1: 从仓库根解析桩文件, 不再写死 A:\tenent
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const MOCK = readFileSync(resolve(REPO, ".design-audit/_probe/mock-tree.js"), "utf8");
```
```js
// 改动 2: 卡速从命令行读, 缺省 250
const GAP = process.argv[2] || "250";
```
```js
// 改动 3: 文件头补一段先决条件说明
// 先决条件:
//   1. npm run tauri dev 已在跑 (Vite 在 localhost:1420; 只绑 IPv6, 不能用 127.0.0.1)
//   2. .design-audit/_probe/mock-tree.js 存在 (本地桩, 已 gitignore, 不属于本脚本)
// 跑法: node docs/evidence/scripts/verify-tree-motion.mjs 250
// 退出码: 0 = 平滑 (>3 台阶); 1 = 跳变; 采集失败时也返回 0 并在输出里标 SKIP
```

同时把 `.design-audit/_probe/verify-tree-motion.mjs`（四机理对照，量 `grid-template-rows` / `height:auto` / 占位行 / 仅行淡入的高度轨迹）也一并提出去。⚠️ **它必须和 `.design-audit/_probe/tree-motion.html` 一起** —— 那个脚本是用 `file://` 打开这个 HTML 的，只复制 .mjs 会得到一个打不开的探针：

```bash
copy .design-audit\_probe\tree-motion.html docs\evidence\scripts\tree-motion.html
copy .design-audit\_probe\verify-tree-motion.mjs docs\evidence\scripts\verify-tree-motion-mechanisms.mjs
```
然后把 `verify-tree-motion-mechanisms.mjs` 里写死的 `file:///A:/tenent/.design-audit/_probe/tree-motion.html` 改成同目录下的相对路径：

```js
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const HERE = dirname(fileURLToPath(import.meta.url));
const FILE = "file:///" + resolve(HERE, "tree-motion.html").replace(/\\/g, "/") + "?d=" + D;
```

在 `docs/evidence/README.md` 里注明 `tree-motion.html` 是「探针页面（含四个机理的对照实现），由 `verify-tree-motion-mechanisms.mjs` 打开」。

- [ ] **Step 2: 跑四档卡速并把原始输出存成证据**

Run:
```bash
node docs/evidence/scripts/verify-tree-motion.mjs 0   > docs/evidence/folder-tree-motion/expand-0ms.txt
node docs/evidence/scripts/verify-tree-motion.mjs 32  > docs/evidence/folder-tree-motion/expand-32ms.txt
node docs/evidence/scripts/verify-tree-motion.mjs 250 > docs/evidence/folder-tree-motion/expand-250ms.txt
node docs/evidence/scripts/verify-tree-motion.mjs 600 > docs/evidence/folder-tree-motion/expand-600ms.txt
```
Expected: 四个文件的末行都是 `ok   平滑`。

- [ ] **Step 3: 跑几何/无障碍探针并存证**

Run:
```bash
node .design-audit\_probe\verify-tree-row-geometry.mjs > docs/evidence/folder-tree-motion/row-geometry.txt
node .design-audit\_probe\verify-tree-inert.mjs        > docs/evidence/folder-tree-motion/inert-and-nesting.txt
```

- [ ] **Step 4: 核对"减少动效"偏好下的行为**

新建 `.design-audit/_probe/verify-tree-reduced-motion.mjs`。这一步验两件事，别混在一起：**动效要被压掉**（转移瞬间到位），但**占位行照样出现**（它是状态不是动效）。

```js
// 用 Playwright 模拟系统"减少动效"偏好
await page.emulateMedia({ reducedMotion: "reduce" });

// (a) 快卡: 展开一个已缓存子行的分支, 断言台阶数 ≤ 3 (过渡被 0.01ms 压掉)
//     gap 用 0, 先展开再收起 102EOSR5 把它的子行缓存住, 然后计时展开
// (b) 慢卡: gap=250, 展开一个没缓存的分支, 在点击后 ~160ms 断言占位行存在
const skeletonAt160 = await page.evaluate(() => document.querySelectorAll(".tree-row-skeleton").length);
console.log("减少动效下 160ms 时占位行数量:", skeletonAt160);
```

Run: `node .design-audit\_probe\verify-tree-reduced-motion.mjs`
Expected: (a) `台阶数 ≤ 3`；(b) `占位行数量 = 1`。
**为什么 (b) 必须是 1**：`src/index.css` 末尾的全局块只压 `transition-duration` / `animation-duration`，不影响组件里那个 120ms 的 `setTimeout`。开着"减少动效"的用户同样需要知道"点了、在加载"，所以占位行该出现还得出现。若这里测出 0，说明有人把占位逻辑也一起"优化"掉了。

- [ ] **Step 5: 把"嵌套展开会被短暂裁掉"这条限制实测成数字**

规格 §9 里这条限制目前只是推断。这一步给出真实数字，写进 EVIDENCE 时用实测值而不是"可能"。

场景：先展开再收起 `102EOSR5`（把它的 4 个子行缓存住），然后展开 `DCIM` 并在 **80ms 内**展开 `102EOSR5` —— 此时 `DCIM` 的容器还钉在像素高度上（`EXPAND_MS + AUTO_SETTLE_SLACK_MS = 250ms` 内），而它的内容已经变高。

```js
// 断言 DCIM 容器的 outer 与 content 之差, 以及在 t=600ms 时是否自愈
const probe = () => page.evaluate(() => {
  const row = [...document.querySelectorAll(".tree-row")].find((r) => r.textContent.includes("DCIM"));
  const w = row.parentElement.querySelector(".tree-kids");
  return { outer: Math.round(w.getBoundingClientRect().height),
           content: w.firstElementChild.scrollHeight };
});
console.log("t=150ms (应被裁):", JSON.stringify(await probe()));
await page.waitForTimeout(500);
console.log("t=650ms (应自愈):", JSON.stringify(await probe()));
```

Run: `node .design-audit\_probe\verify-tree-reduced-motion.mjs`
Expected: t=150ms 处 `outer < content`（被裁）；t=650ms 处 `outer === content`（自愈）。把这两个数字原样写进 EVIDENCE.md。

- [ ] **Step 6: 真机上确认 120ms 阈值（人工，唯一没有探针的一步）**

Run: `npm run tauri dev`，插上真实 SD 卡，点设备进入 DCIM。

要判断的是**占位行会不会闪**：
- 在快目录（缓存热、子目录少）上展开——**不应该**看到灰条闪一下；
- 在慢目录（子目录多、卡上首次读）上展开——**应该**看到灰条出现并持续到真行到达。

若快目录上也闪，把 `PLACEHOLDER_DELAY_MS` 调大（`src/components/folder-tree-motion.ts`）并重跑 Task 2 的单测（该常量有断言，改它就必须同步改测试）；若慢目录上灰条出现得太晚，调小。

**为什么只能人工**：本机是 NTFS SSD，`browse_directory` 在一层目录上是几毫秒量级，而 `browse.rs:25` 的 `has_subdirectories` 对每个子目录都要探一次，真实卡上的延迟分布我测不到。这个数是全案唯一没有实测依据的值。

- [ ] **Step 7: 写 EVIDENCE.md**

新建 `docs/evidence/folder-tree-motion/EVIDENCE.md`，必须包含：

1. **前置事实**：子目录是一层一层异步取的，附 `browse.rs:26-32` 与改动前 `folder-tree-item.tsx:39` 的行号。
2. **改动前的四档实测表**（0/32/250/600ms 的子行到达时刻与台阶数），这是"现状没有动画"的证据。
3. **反例矩阵**：把动效挂在"点击"而不是"数据到达"上，32ms 下 14 台阶、250ms 下 2 台阶 —— 说明为什么这是那种只在 SSD 上开发会漏掉的 bug。附 `verify-tree-motion-mechanisms.mjs` 的四个机理对照结论。
4. **改动后的四档实测表**（逐档台阶数，引用 `expand-*.txt`）。
5. **行高对照表**（面板内五种行高 → 改动后的值）。
6. **减少动效下的行为**（Step 4 的两条结果）。
7. **嵌套展开竞态实测**（Step 5 的两个数字：裁掉多少 px、多久自愈）。
8. **未入库的截图清单**（文件名 + 一句话说明，依 `docs/evidence/README.md` 里 xmp-sidecar 的写法）。
9. **已知限制（诚实列出）**：
   - `PLACEHOLDER_DELAY_MS = 120` 只有 Step 6 的人工观感依据，没有延迟分布数据；
   - 嵌套展开的短暂裁剪（引用第 7 条的实测数字）；
   - 树没有 `role="tree"` 语义，也没有方向键导航，读作"一串按钮"（既有缺口，本次不引入）；
   - 收起后子树常驻 DOM，规模由"用户实际展开过多少分支"决定（未展开的节点 `children` 为空，不产生 DOM）。

- [ ] **Step 8: 在 README 的 scripts 表格里加两行**

在 `docs/evidence/README.md` 的 `scripts/` 表格中加：

```markdown
| `verify-tree-motion.mjs` | 文件夹树展开的台阶数断言（>3 台阶为平滑） | `node verify-tree-motion.mjs <卡速ms>`，需先跑 `npm run tauri dev` |
| `tree-motion.test.ts` | `src/components/folder-tree-motion.ts` 的阶段判定断言（11 条） | 见文件头注释（esbuild + node，不引 vitest —— 项目既有约定） |
```

- [ ] **Step 9: 最终全量核对**

Run:
```bash
npx tsc --noEmit
node .design-audit\_probe\verify-console-both.mjs
npx esbuild docs/evidence/scripts/tree-motion.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/tree-motion.mjs
node .design-audit\_probe\tree-motion.mjs
git status --short
```
Expected: `tsc exit: 0`；控制台无 error / warning；单测 `ALL PASS: 11 passed, 0 failed`（退出码 0）；`git status` 只剩本次要提交的文件。

- [ ] **Step 10: 提交**

```bash
git add docs/evidence/folder-tree-motion docs/evidence/scripts/verify-tree-motion.mjs docs/evidence/scripts/verify-tree-motion-mechanisms.mjs docs/evidence/scripts/tree-motion.test.ts docs/evidence/README.md
git commit -m "docs(evidence): 归档文件夹树展开动效的实测数据"
```

---

## 收尾：把规格与计划一起提交

```bash
git add docs/superpowers/specs/2026-10-05-folder-tree-expand-motion-design.md docs/superpowers/plans/2026-10-05-folder-tree-expand-motion.md
git commit -m "docs: 加文件夹树展开动效的规格与实施计划"
```

（`docs/superpowers/specs/2026-10-05-mica-os-default-design.md` 仍未确认，**不要**一起提交。）
