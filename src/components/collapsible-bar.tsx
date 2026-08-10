import { useTranslation } from "react-i18next";
import { DownOne, UpOne } from "@icon-park/react";

interface Props {
  align: "top" | "bottom";
  expanded: boolean;
  onToggle: () => void;
  glass: boolean;
  /** 展开时收起按钮集成在主体内(由调用方渲染), 独立按钮仅折叠态显示展开 */
  collapseInside?: boolean;
  children: React.ReactNode;
}

export function CollapsibleBar({ align, expanded, onToggle, glass, collapseInside = false, children }: Props) {
  const { t } = useTranslation();
  const collapseIcon = align === "top" ? <UpOne theme="filled" size="14" strokeWidth={3} /> : <DownOne theme="filled" size="14" strokeWidth={3} />;
  const expandIcon = align === "top" ? <DownOne theme="filled" size="14" strokeWidth={3} /> : <UpOne theme="filled" size="14" strokeWidth={3} />;

  return (
    <div className={`flex flex-shrink-0 gap-2 px-3 ${align === "top" ? "pt-2 items-start" : "pb-2 items-end"}`}>
      <div className="relative grid flex-1 transition-[grid-template-rows] duration-300 ease-in-out"
        style={{ gridTemplateRows: expanded ? "1fr" : "0fr" }}>
        {/* 阴影层 — 精确贴卡片区域(卡片在 p-3 内), 放在裁剪容器之外不被裁剪, 折叠时淡出
             注意: 透明窗口+毛玻璃下, 完全透明元素上的 box-shadow 会被 WebView2 渲染成白色块,
             因此背景给 0.001 极低 alpha, 走正常合成路径 */}
        <div
          className={`absolute inset-3 rounded-2xl shadow-xl shadow-black/40 pointer-events-none transition-opacity duration-200 ${expanded ? "opacity-100" : "opacity-0"}`}
          style={{ background: "rgba(0,0,0,0.001)" }}
        />
        <div
          className={`min-h-0 overflow-hidden transition-opacity duration-200 ${expanded ? "opacity-100" : "opacity-0 pointer-events-none"}`}
          inert={!expanded}
          aria-hidden={!expanded}
        >
          <div className={`${expanded ? "p-3" : "p-0"} transition-[padding] duration-200`}>
            <div className={`rounded-2xl border border-zinc-800 overflow-hidden transition-colors duration-200 ${glass ? "bg-zinc-900/70" : "bg-zinc-900/95"}`}>
              {children}
            </div>
          </div>
        </div>
      </div>
      {/* 独立按钮: 折叠态总显示(展开用); collapseInside 展开态隐藏(收起按钮在主体内) */}
      {(!collapseInside || !expanded) && (
        <button
          onClick={onToggle}
          aria-expanded={expanded}
          title={expanded ? t("bars.collapse") : t("bars.expand")}
          className="w-7 h-7 flex-shrink-0 flex items-center justify-center rounded-full border border-zinc-800 bg-zinc-900/80 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 shadow-lg shadow-black/30 transition-colors"
        >
          {expanded ? collapseIcon : expandIcon}
        </button>
      )}
    </div>
  );
}
