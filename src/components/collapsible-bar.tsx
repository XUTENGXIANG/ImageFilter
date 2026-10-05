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

  return (
    <div className={`relative z-40 flex flex-shrink-0 gap-2 px-3 ${align === "top" ? "pt-2 items-start" : "pb-2 items-end"}`}>
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
