// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
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
