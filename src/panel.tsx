import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Tip } from "./components/tip";

interface Props {
  side: "left" | "right";
  title?: string;
  defaultOpen?: boolean;
  /** 该值变化时自动展开面板（用于选中照片后弹出详情） */
  autoOpenKey?: string;
  children: React.ReactNode;
}

export function FloatingPanel({ side, title, defaultOpen = true, autoOpenKey, children }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(defaultOpen);
  const isLeft = side === "left";

  useEffect(() => {
    if (autoOpenKey) setOpen(true);
  }, [autoOpenKey]);

  return (
    <div className={`relative z-40 flex-shrink-0 self-stretch flex flex-col transition-[width] duration-300 ease-in-out ${open ? "w-60" : "w-6"}`}>
      <div className={`flex flex-1 min-h-0 overflow-hidden ${isLeft ? "justify-start" : "justify-end"}`}>
        <div
          className={`h-full w-60 p-3 transition-opacity duration-200 ${open ? "opacity-100" : "opacity-0 pointer-events-none"}`}
          inert={!open}
          aria-hidden={!open}
        >
          <div className="h-full flex flex-col border border-zinc-800 rounded-2xl overflow-hidden relative z-20 transition-colors duration-200 bg-zinc-900">
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
        </div>
      </div>
      {!open && (
        <Tip label={isLeft ? t("panel.expandLeft") : t("panel.expandRight")} className="absolute top-1/2 -translate-y-1/2 w-full h-10 flex items-center justify-center">
        <button
          onClick={() => setOpen(true)}
          className={`w-full h-10 border border-zinc-800 hover:bg-zinc-800 cursor-pointer flex items-center justify-center transition-colors duration-200 ${
            isLeft ? "right-0 rounded-r-lg border-l-0" : "left-0 rounded-l-lg border-r-0"
          } bg-zinc-900`}
        >
          <span className="text-[9px] text-zinc-500">{isLeft ? "▶" : "◀"}</span>
        </button>
        </Tip>
      )}
    </div>
  );
}
