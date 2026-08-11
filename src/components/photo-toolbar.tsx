import { useTranslation } from "react-i18next";
import { UpOne } from "@icon-park/react";
import { CollapsibleBar } from "./collapsible-bar";
import { ThumbSizeSlider } from "./thumb-size-slider";
import { Tip } from "./tip";

interface Props {
  selectedDrive: string | null;
  photosCount: number;
  selectedCount: number;
  sortBy: "name" | "type" | "date";
  onSortByChange: (v: "name" | "type" | "date") => void;
  starFilter: number;
  onStarFilterChange: (v: number) => void;
  analyzing: boolean;
  onSelectAll: () => void;
  onClearSelection: () => void;
  onAnalyzeAll: () => void;
  onStopAnalysis: () => void;
  expanded: boolean;
  onToggle: () => void;
}

export function PhotoToolbar({
  selectedDrive,
  photosCount,
  selectedCount,
  sortBy,
  onSortByChange,
  starFilter,
  onStarFilterChange,
  analyzing,
  onSelectAll,
  onClearSelection,
  onAnalyzeAll,
  onStopAnalysis,
  expanded,
  onToggle,
}: Props) {
  const { t } = useTranslation();

  return (
    <CollapsibleBar align="top" expanded={expanded} onToggle={onToggle} collapseInside>
      {selectedDrive && photosCount > 0 ? (
        <div className="flex items-center px-4 h-9 gap-2">
          <button onClick={onSelectAll} className="text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.selectAll")}</button>
          <button onClick={onClearSelection} className="text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.clear")}</button>
          <span className="text-[10px] text-zinc-600">{t("toolbar.selected", { n: selectedCount, total: photosCount })}</span>
          <select
            value={sortBy}
            onChange={(e) => onSortByChange(e.target.value as "name" | "type" | "date")}
            className="bg-zinc-800 text-[10px] text-zinc-400 px-1 py-0.5 rounded border border-zinc-700"
          >
            <option value="name">{t("toolbar.sortName")}</option>
            <option value="type">{t("toolbar.sortType")}</option>
            <option value="date">{t("toolbar.sortDate")}</option>
          </select>
          {[0, 1, 2, 3, 4, 5].map((s) => (
            <button
              key={s}
              onClick={() => onStarFilterChange(starFilter === s ? 0 : s)}
              className={`text-[10px] px-1 rounded ${starFilter === s ? "text-amber-400 bg-amber-400/10" : "text-zinc-600 hover:text-zinc-400"}`}
            >
              {s === 0 ? t("toolbar.all") : "★".repeat(s)}
            </button>
          ))}
          <ThumbSizeSlider />
          <div className="flex-1" />
          <button
            onClick={() => analyzing ? onStopAnalysis() : onAnalyzeAll()}
            className={`text-[10px] px-2 py-0.5 rounded text-zinc-400 ${
              analyzing
                ? "bg-red-900/50 hover:bg-red-800/50 text-red-400"
                : "bg-zinc-800 hover:bg-zinc-700"
            }`}
          >
            {analyzing ? t("toolbar.stop") : t("toolbar.ai")}
          </button>
          {/* 收起按钮 — 集成在主体内 */}
          <Tip label={t("bars.collapse")} className="flex items-center">
          <button
            onClick={onToggle}
            className="w-6 h-6 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            <UpOne theme="filled" size="13" strokeWidth={3} />
          </button>
          </Tip>
        </div>
      ) : (
        <div className="flex items-center px-4 h-9 gap-2 text-[10px] text-zinc-600">
          <span className="flex-1">{t("toolbar.empty")}</span>
          <Tip label={t("bars.collapse")} className="flex items-center">
          <button
            onClick={onToggle}
            className="w-6 h-6 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            <UpOne theme="filled" size="13" strokeWidth={3} />
          </button>
          </Tip>
        </div>
      )}
    </CollapsibleBar>
  );
}
