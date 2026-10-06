// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import type { ReactNode } from "react";

/**
 * 就地展开 / 收起的内容块。
 *
 * 手法与 `CollapsibleBar` 完全一致(`grid-template-rows` 0fr ↔ 1fr + 透明度),
 * 抽出来是因为工具栏的「筛选」面板、导入栏的「高级选项」和折叠条本身都是这件事 ——
 * 三处各写一遍就会各飘各的时长与减速曲线。
 *
 * **内容常驻挂载**: 高度过渡需要它(挂载瞬间没有"变化前"的高度可供过渡)。
 * 代价是收起时它仍在 DOM 里, 所以必须 `inert` + `aria-hidden`,
 * 否则收起的表单控件会漏进 Tab 顺序与无障碍树 —— 这与文件夹树子树是同一个坑。
 */
export function Collapse({
  open,
  /** 内层容器的类。给 padding / 间距这类"展开态才想要"的东西用。 */
  className = "",
  /** 与 CollapsibleBar 的 300ms 保持一致时才传它; 默认 200ms 更适合小面板。 */
  durationMs = 200,
  children,
}: {
  open: boolean;
  className?: string;
  durationMs?: number;
  children: ReactNode;
}) {
  return (
    <div
      className="grid"
      style={{
        gridTemplateRows: open ? "1fr" : "0fr",
        transition: `grid-template-rows ${durationMs}ms ease-in-out`,
      }}
    >
      <div
        className={`min-h-0 overflow-hidden transition-opacity duration-200 ${
          open ? "opacity-100" : "opacity-0"
        }`}
        inert={!open}
        aria-hidden={!open}
      >
        <div className={className}>{children}</div>
      </div>
    </div>
  );
}
