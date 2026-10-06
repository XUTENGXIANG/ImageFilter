// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { DownOne, UpOne } from "@icon-park/react";
import { Tip } from "./tip";
import { Collapse } from "./collapse";

interface Props {
  align: "top" | "bottom";
  expanded: boolean;
  onToggle: () => void;
  /** 展开时收起按钮集成在主体内(由调用方渲染), 独立按钮仅折叠态显示展开 */
  collapseInside?: boolean;
  children: React.ReactNode;
}

export function CollapsibleBar({ align, expanded, onToggle, collapseInside = false, children }: Props) {
  const { t } = useTranslation();
  const collapseIcon = align === "top" ? <UpOne theme="filled" size="14" strokeWidth={3} /> : <DownOne theme="filled" size="14" strokeWidth={3} />;
  const expandIcon = align === "top" ? <DownOne theme="filled" size="14" strokeWidth={3} /> : <UpOne theme="filled" size="14" strokeWidth={3} />;
  const rootRef = useRef<HTMLDivElement>(null);

  // 把自身高度写成一个 CSS 变量, 供网格容器的内边距使用。
  //
  // 为什么需要: 这两条栏是**浮在网格之上的**(App 里它们绝对定位), 网格铺满整个高度、
  // 照片从它们底下滚过去。所以网格必须知道"上面/下面被盖住了多少", 否则滚到两端时
  // 首行/末行的照片会永远藏在浮窗底下、滚不出来。
  //
  // 为什么用 ResizeObserver 而不是写死一个数: 折叠态与展开态差好几行(实测工具栏 70px、
  // 导入栏展开后 97px), 写死一个值就会在展开时把内容压到浮窗底下。
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const varName = align === "top" ? "--top-bar-h" : "--bottom-bar-h";
    const write = () => {
      document.documentElement.style.setProperty(varName, `${el.getBoundingClientRect().height}px`);
    };
    write();
    const ro = new ResizeObserver(write);
    ro.observe(el);
    return () => {
      ro.disconnect();
      document.documentElement.style.removeProperty(varName);
    };
  }, [align]);

  return (
    <div
      ref={rootRef}
      className={`relative z-40 flex flex-shrink-0 gap-2 px-3 ${align === "top" ? "pt-2 items-start" : "pb-2 items-end"}`}
    >
      {/* 展开/收起的手法与工具栏「筛选」面板、导入栏「高级选项」共用 Collapse ——
          三处各写一遍就会各飘各的时长与曲线。这里传 300ms 保持折叠条原有的手感。 */}
      <div className="relative flex-1">
        <Collapse
          open={expanded}
          durationMs={300}
          className={`${expanded ? "p-3" : "p-0"} transition-[padding] duration-200`}
        >
          <div className="rounded-2xl border border-zinc-800 overflow-hidden transition-colors duration-200 bg-zinc-900">
            {children}
          </div>
        </Collapse>
      </div>
      {/* 独立按钮: 折叠态总显示(展开用); collapseInside 展开态隐藏(收起按钮在主体内) */}
      {(!collapseInside || !expanded) && (
        <Tip label={expanded ? t("bars.collapse") : t("bars.expand")} className="flex-shrink-0 flex items-center">
          <button
            onClick={onToggle}
            aria-expanded={expanded}
            className="w-7 h-7 flex items-center justify-center rounded-full border border-zinc-800 bg-zinc-900/80 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 shadow-lg shadow-black/30 transition-colors"
          >
            {expanded ? collapseIcon : expandIcon}
          </button>
        </Tip>
      )}
    </div>
  );
}
