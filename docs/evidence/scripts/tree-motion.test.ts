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
