import { useTranslation } from "react-i18next";
import { DownOne, UpOne } from "@icon-park/react";

interface Props {
  align: "top" | "bottom";
  expanded: boolean;
  onToggle: () => void;
  glass: boolean;
  children: React.ReactNode;
}

export function CollapsibleBar({ align, expanded, onToggle, glass, children }: Props) {
  const { t } = useTranslation();
  const collapseIcon = align === "top" ? <UpOne theme="filled" size="14" strokeWidth={3} /> : <DownOne theme="filled" size="14" strokeWidth={3} />;
  const expandIcon = align === "top" ? <DownOne theme="filled" size="14" strokeWidth={3} /> : <UpOne theme="filled" size="14" strokeWidth={3} />;

  return (
    <div className={`flex flex-shrink-0 gap-2 px-3 ${align === "top" ? "pt-2 items-start" : "pb-2 items-end"}`}>
      <div
        className="grid flex-1 transition-[grid-template-rows] duration-300 ease-in-out"
        style={{ gridTemplateRows: expanded ? "1fr" : "0fr" }}
      >
        <div
          className={`min-h-0 overflow-hidden transition-opacity duration-200 ${expanded ? "opacity-100" : "opacity-0 pointer-events-none"}`}
          inert={!expanded}
          aria-hidden={!expanded}
        >
          <div className={`${expanded ? "p-3" : "p-0"} transition-[padding] duration-200`}>
            <div className={`rounded-2xl border border-zinc-800 shadow-xl shadow-black/40 overflow-hidden transition-colors duration-200 ${glass ? "bg-zinc-900/70" : "bg-zinc-900/95"}`}>
              {children}
            </div>
          </div>
        </div>
      </div>
      <button
        onClick={onToggle}
        aria-expanded={expanded}
        title={expanded ? t("bars.collapse") : t("bars.expand")}
        className="w-7 h-7 flex-shrink-0 flex items-center justify-center rounded-full border border-zinc-800 bg-zinc-900/80 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 shadow-lg shadow-black/30 transition-colors"
      >
        {expanded ? collapseIcon : expandIcon}
      </button>
    </div>
  );
}
