import { useState } from "react";
import { useTranslation } from "react-i18next";

interface Props {
  side: "left" | "right";
  title?: string;
  defaultOpen?: boolean;
  glass?: boolean;
  children: React.ReactNode;
}

export function FloatingPanel({ side, title, defaultOpen = true, glass = false, children }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(defaultOpen);
  const isLeft = side === "left";

  return (
    // 外层容器常驻 — 宽度类切换触发 CSS transition 平滑动画
    <div className={`flex-shrink-0 self-stretch flex flex-col transition-[width] duration-200 ease-linear ${open ? "w-60" : "w-6"}`}>
      {open ? (
        <div className={`flex-1 min-h-0 flex flex-col border border-zinc-800 rounded-2xl shadow-2xl shadow-black/50 m-2 overflow-hidden relative z-20 transition-colors duration-200 ${glass ? "bg-zinc-900/70" : "bg-zinc-900/95"}`}>
          {/* Header with collapse button */}
          <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800/50 flex-shrink-0">
            <span className="text-xs font-semibold text-zinc-400 tracking-wider">{title}</span>
            <button
              onClick={() => setOpen(false)}
              className="w-5 h-5 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-600 hover:text-zinc-400"
            >
              <span className="text-[10px]">{isLeft ? "◀" : "▶"}</span>
            </button>
          </div>
          {/* Content */}
          <div className="flex-1 overflow-auto min-h-0 no-scrollbar">
            {children}
          </div>
        </div>
      ) : (
        // 折叠态：窄条按钮（同一元素宽度过渡）
        <button
          onClick={() => setOpen(true)}
          className={`flex-1 min-h-0 w-full border border-zinc-800 hover:bg-zinc-800 cursor-pointer flex items-center justify-center transition-colors duration-200 ${
            isLeft ? "rounded-r-lg border-l-0" : "rounded-l-lg border-r-0"
          } ${glass ? "bg-zinc-900/70" : "bg-zinc-900"}`}
          title={isLeft ? t("panel.expandLeft") : t("panel.expandRight")}
        >
          <span className="text-[9px] text-zinc-500">{isLeft ? "▶" : "◀"}</span>
        </button>
      )}
    </div>
  );
}
