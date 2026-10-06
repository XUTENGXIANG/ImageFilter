// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// ═══════════════════════════════════════════════════════════════════
// Phase 4 · 颜色标签的"共享词汇表"
//
// 为什么单独一个文件: 数据层(useScanner)与展示层(photo-card / viewer /
// photo-toolbar)都要用这几个常量与类型, 但**展示组件不许 import useScanner**
// —— useScanner 顶部 import 了 @tauri-apps/api, 那会把 Tauri 拖进纯展示组件。
//
// 存储: localStorage "imagefilter-labels" = { [绝对路径]: Label }
//   · **清除标签 = 删除该键**(与 ratings 保留 `path: 0` 的语义刻意不同):
//     标签是稀疏数据, 且 Phase 5 要拿这份 map 直接写 xmp:Label,
//     不能带脏值/半成品键过去。
//   · 读取时做一层合法性校验(isLabel), 非法值丢弃。
//   · 标签不随文件夹切换清空(key 是绝对路径, 与 ratings 同待遇)。
// 详细取舍见 docs/ImageFilter-功能实施方案.md 的 4.1 / 4.5。
// ═══════════════════════════════════════════════════════════════════

export type Label = "red" | "yellow" | "green" | "blue" | "purple";

/** 展示顺序, 同时决定快捷键顺序(Ctrl/Alt + 1..5 按这个次序) */
export const LABEL_ORDER: Label[] = ["red", "yellow", "green", "blue", "purple"];

/** localStorage key —— 不要与 imagefilter-ratings 混用(会话 ⓪ 已定) */
export const LABELS_STORAGE_KEY = "imagefilter-labels";

/** 卡片色点 / 查看器色点 / 筛选 chips 的底色: 只在这里定义一份, 三处共用 */
export const LABEL_BG: Record<Label, string> = {
  red: "bg-red-500",
  yellow: "bg-yellow-500",
  green: "bg-green-500",
  blue: "bg-blue-500",
  purple: "bg-purple-500",
};

export function isLabel(v: unknown): v is Label {
  return typeof v === "string" && (LABEL_ORDER as string[]).includes(v);
}

/** 读 localStorage → 只保留合法标签(JSON 损坏一律当空, 与 setRating 的兜底同款) */
export function readLabels(): Record<string, Label> {
  try {
    const raw = JSON.parse(localStorage.getItem(LABELS_STORAGE_KEY) || "{}");
    if (!raw || typeof raw !== "object") return {};
    const out: Record<string, Label> = {};
    for (const [path, v] of Object.entries(raw)) if (isLabel(v)) out[path] = v;
    return out;
  } catch {
    return {};
  }
}

/**
 * 这一击是否命中"打标签"的修饰键。
 * 纯函数、App 与 viewer 共用 —— 两处各写一遍迟早会分叉。
 *
 * 两个方向都要求"另一个修饰键没按下": 否则 Ctrl+Alt+2 会同时被
 * Ctrl 方案和 Alt 方案认领。
 */
export function isLabelChord(
  e: { ctrlKey: boolean; metaKey: boolean; altKey: boolean },
  mod: "ctrl" | "alt",
): boolean {
  return mod === "alt"
    ? e.altKey && !e.ctrlKey && !e.metaKey
    : (e.ctrlKey || e.metaKey) && !e.altKey;
}
