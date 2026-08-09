import { useState } from "react";

interface Props {
  side: "left" | "right";
  title?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}

export function FloatingPanel({ side, title, defaultOpen = true, children }: Props) {
  const [open, setOpen] = useState(defaultOpen);
  const isLeft = side === "left";

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className={`flex-shrink-0 w-6 bg-zinc-900 border border-zinc-800 hover:bg-zinc-800 cursor-pointer flex items-center justify-center ${
          isLeft ? "rounded-r-lg border-l-0 mr-2" : "rounded-l-lg border-r-0 ml-2"
        }`}
        title={isLeft ? "展开设备面板" : "展开信息面板"}
      >
        <span className="text-[9px] text-zinc-500">{isLeft ? "▶" : "◀"}</span>
      </button>
    );
  }

  return (
    <div className={`flex-shrink-0 flex ${isLeft ? "" : ""}`}>
      <div className="w-60 flex flex-col bg-zinc-900/95 border border-zinc-800 rounded-2xl shadow-2xl shadow-black/50 m-2 overflow-hidden">
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
        <div className="flex-1 overflow-auto min-h-0">
          {children}
        </div>
      </div>
    </div>
  );
}
