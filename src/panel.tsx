// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Left, Right } from "@icon-park/react";
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
    <div className={`relative z-40 flex-shrink-0 self-stretch flex flex-col transition-[width] duration-300 ease-in-out ${open ? "w-64" : "w-6"}`}>
      <div className={`flex flex-1 min-h-0 overflow-hidden ${isLeft ? "justify-start" : "justify-end"}`}>
        <div
          className={`h-full w-64 p-3 transition-opacity duration-200 ${open ? "opacity-100" : "opacity-0 pointer-events-none"}`}
          inert={!open}
          aria-hidden={!open}
        >
          <div className="h-full flex flex-col border border-zinc-800 dark:border-white/[0.08] rounded-2xl overflow-hidden relative z-20 transition-colors duration-200 bg-zinc-900">
            {/* Header with collapse button */}
            <div className="flex items-center justify-between px-3 py-2.5 border-b border-zinc-800/60 dark:border-white/[0.06] flex-shrink-0">
              <span className="text-[11px] font-medium text-zinc-500 tracking-[0.08em]">{title}</span>
              <Tip label={isLeft ? t("panel.collapseLeft") : t("panel.collapseRight")}>
              <button
                onClick={() => setOpen(false)}
                className="w-6 h-6 flex items-center justify-center rounded-md hover:bg-zinc-800 text-zinc-600 hover:text-zinc-300 transition-colors"
              >
                {isLeft ? <Left theme="outline" size={12} strokeWidth={3} /> : <Right theme="outline" size={12} strokeWidth={3} />}
              </button>
              </Tip>
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
          className={`w-full h-10 border border-zinc-800 dark:border-white/[0.08] hover:bg-zinc-800 cursor-pointer flex items-center justify-center transition-colors duration-200 ${
            isLeft ? "right-0 rounded-r-lg border-l-0" : "left-0 rounded-l-lg border-r-0"
          } bg-zinc-900`}
        >
          {isLeft ? <Right theme="outline" size={10} strokeWidth={3} className="text-zinc-500" /> : <Left theme="outline" size={10} strokeWidth={3} className="text-zinc-500" />}
        </button>
        </Tip>
      )}
    </div>
  );
}
