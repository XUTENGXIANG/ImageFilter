// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// ═══════════════════════════════════════════════════════════════════
// Phase 2 · 撤销 / 重做 的纯逻辑层(零 React 依赖)
//
// 设计: 存"逆补丁"而不是状态快照 —— 几千张照片时快照法每次按键都要克隆
// 整份 ratings/labels。补丁只记录被改动的那一条路径。
//
// 为什么把这些函数抽到独立文件: 它们是本功能里唯一可以脱离 DOM/React
// 验证的部分(栈深、去重、往返一致性), 见 docs/ImageFilter-功能实施方案.md
// 会话 ②(零测试仓库的替代验证: 临时脚本 + esbuild 转译后跑 Node)。
// 日后引入 vitest 时, 本文件可以直接被 import, 无需改造。
//
// 补丁种类: rating(评分) / label(Phase 4 颜色标签) / selection(勾选)。
// ⚠️ 给 Patch 加新 kind 时, tsc 会把 undo() / redo() / patchToast() /
// samePatch() / patchPath() 这几个派发点全报出来 —— 逐个补齐,
// **不许用 any / as 兜底**(docs 「共同的前置约束」第 6 条)。
//
// 三条不变式(改动前先读):
// 1. pushPatch 的栈顶去重**只可能命中 React StrictMode 的 updater 双调用**:
//    真实连续操作要么被调用方的"无变化早退"挡掉, 要么产生不同的补丁。
//    绝不允许把去重放宽成"内容相等" —— 那会吞掉真实的连续操作,
//    表现是"连点两下只撤销得回一步"。
// 2. 补丁里的 prev/next 数组是**冻结快照**: 选择集在每次变更时都新建
//    Set, 所以数组引用即版本标识。数组生成后再也不能改。
// 3. 撤销/重做一律返回**新对象**, 不改传入的补丁 —— 同一补丁会在
//    undoStack / redoStack 之间来回移动, 一旦就地修改, 重做就会拿到被
//    改过的 prev/next(静默的数据损坏, 只在"撤-重做-再撤"时暴露)。
// ═══════════════════════════════════════════════════════════════════

import type { Label } from "./labels";

export type Patch =
  | { kind: "rating"; path: string; prev: number; next: number }
  | { kind: "label"; path: string; prev: Label | null; next: Label | null }
  | { kind: "selection"; paths: string[]; prev: string[]; next: string[] };

/** 栈深上限(只存在于当前会话, 不落盘) */
export const UNDO_LIMIT = 100;

export interface History {
  undo: Patch[];
  redo: Patch[];
}

export const EMPTY_HISTORY: History = { undo: [], redo: [] };

/** 入栈: 栈顶去重(StrictMode 双调用) → 截断到 UNDO_LIMIT → 尝试清空 redo */
export function pushPatch(h: History, p: Patch): History {
  const top = h.undo[h.undo.length - 1];
  if (top && samePatch(top, p)) return h; // StrictMode 双调用 → 同一条补丁被推两次
  const undo = h.undo.length >= UNDO_LIMIT ? [...h.undo.slice(1), p] : [...h.undo, p];
  return { undo, redo: h.redo.length === 0 ? h.redo : [] };
}

/** 撤销: 取栈顶移入 redo, 返回要回放的补丁(没有则 patch=null) */
export function popUndo(h: History): { patch: Patch | null; history: History } {
  const n = h.undo.length;
  if (n === 0) return { patch: null, history: h };
  return { patch: h.undo[n - 1], history: { undo: h.undo.slice(0, n - 1), redo: [...h.redo, h.undo[n - 1]] } };
}

/** 重做: 取 redo 栈顶移回 undo */
export function popRedo(h: History): { patch: Patch | null; history: History } {
  const n = h.redo.length;
  if (n === 0) return { patch: null, history: h };
  return { patch: h.redo[n - 1], history: { undo: [...h.undo, h.redo[n - 1]], redo: h.redo.slice(0, n - 1) } };
}

/**
 * 回放补丁到 ratings(评分路径的唯一写入点仍是 useScanner.setRating,
 * 本函数只负责"算出下一份")。
 *
 * 判据只看**目标值**: 目标已达成 → 返回原引用。这样它既幂等(同一补丁重复回放
 * 不会再产新对象 → 不会白写 localStorage、白触发重渲染、白跑 sortedPhotos),
 * 也容忍"当前值不在 {prev,next} 里"的意外状态(直接对齐到 prev, 不静默放弃)。
 * 0 星沿用既有语义: 保留 `path: 0` 这个键(不删除键), 与 `ratings[path] || 0` 的读法一致。
 */
export function applyRatingPatch(
  current: Record<string, number>,
  p: Extract<Patch, { kind: "rating" }>,
): Record<string, number> {
  const to = p.prev;
  if ((current[p.path] ?? 0) === to) return current; // 目标已达成(含"键不存在且目标是 0")
  return { ...current, [p.path]: to };
}

/**
 * 回放标签补丁(Phase 4)。写入点仍是 useScanner.setLabel, 本函数只算"下一份"。
 *
 * 与 applyRatingPatch 同款判据(**只看目标值**、达成即返原引用),
 * 但有一处必须不同: 目标是 null 表示"无标签", 走**删除键**(见 labels.ts 文件头),
 * 所以判据要把 undefined 与 null 归一 —— 否则"刚从存储里删掉的键"会被当成
 * "还没达成", 每次撤销都产新对象: 白写 localStorage、白触发重渲染,
 * 而且 redo 栈会多出一步无内容的补丁。
 */
export function applyLabelPatch(
  current: Record<string, Label>,
  p: Extract<Patch, { kind: "label" }>,
): Record<string, Label> {
  const to = p.prev;
  if ((current[p.path] ?? null) === to) return current; // 目标已达成(含"要求无标签且键本就不存在")
  const next = { ...current };
  if (to === null) delete next[p.path];
  else next[p.path] = to;
  return next;
}

/** 回放勾选补丁(选择集整体替换) */
export function applySelectionPatch(
  current: Set<string>,
  p: Extract<Patch, { kind: "selection" }>,
): Set<string> {
  const target = p.prev;
  if (current.size === target.length && target.every((x) => current.has(x))) return current;
  return new Set(target);
}

/** 让查看器能回到"被撤销的那张"—— 补丁里就带着路径, 不必上层猜 */
export function patchPath(p: Patch): string | null {
  return p.kind === "selection" ? null : p.path;
}

// ── 内部: 栈顶去重判据 ────────────────────────────────────────────
function samePatch(a: Patch, b: Patch): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === "rating" && b.kind === "rating") {
    // 数值比较: 等价于 StrictMode 双调用产生的"同一个补丁"
    return a.path === b.path && a.prev === b.prev && a.next === b.next;
  }
  if (a.kind === "label" && b.kind === "label") {
    // 全是稳定原语(path + 两个标签值/ null), 同上
    return a.path === b.path && a.prev === b.prev && a.next === b.next;
  }
  if (a.kind === "selection" && b.kind === "selection") {
    // 引用比较: 选择集按版本新建数组(见文件头不变式 2), 内容深比是多余开销
    return a.paths === b.paths;
  }
  return false;
}
