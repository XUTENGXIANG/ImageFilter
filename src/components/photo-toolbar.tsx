import { useState } from "react";
import { useTranslation } from "react-i18next";
import { UpOne, SortAmountUp, SortAmountDown } from "@icon-park/react";
import { CollapsibleBar } from "./collapsible-bar";
import { ThumbSizeSlider } from "./thumb-size-slider";
import { Tip } from "./tip";
import { LABEL_BG, LABEL_ORDER, type Label } from "../labels";
import type { FlagFilter } from "../types";

interface Props {
  selectedDrive: string | null;
  photosCount: number;
  selectedCount: number;
  sortBy: "name" | "type" | "date";
  onSortByChange: (v: "name" | "type" | "date") => void;
  sortDir: "asc" | "desc";
  onToggleSortDir: () => void;
  starFilter: number;
  onStarFilterChange: (v: number) => void;
  labelFilter: Label[];
  onLabelFilterChange: (v: Label[]) => void;
  flagFilter: FlagFilter;
  onFlagFilterChange: (v: FlagFilter) => void;
  onClearFilters: () => void;
  /** >0 = "只分析选中的这 N 张"(= 选区在分析范围内); 0 = 对当前文件夹全部分析 */
  analyzeCount: number;
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
  sortDir,
  onToggleSortDir,
  starFilter,
  onStarFilterChange,
  labelFilter,
  onLabelFilterChange,
  flagFilter,
  onFlagFilterChange,
  onClearFilters,
  analyzeCount,
  analyzing,
  onSelectAll,
  onClearSelection,
  onAnalyzeAll,
  onStopAnalysis,
  expanded,
  onToggle,
}: Props) {
  const { t } = useTranslation();
  // 筛选面板的开合是**纯本地 UI 状态**(不落盘、不入撤销栈, 与筛选值同待遇)
  const [filterOpen, setFilterOpen] = useState(false);
  const filtersActive = labelFilter.length > 0 || flagFilter !== "all";

  // 标签筛选是多选: 命中**任一**即显示(见 docs 4.2)
  const toggleLabel = (l: Label) => {
    onLabelFilterChange(labelFilter.includes(l) ? labelFilter.filter((x) => x !== l) : [...labelFilter, l]);
  };

  return (
    <CollapsibleBar align="top" expanded={expanded} onToggle={onToggle} collapseInside>
      {selectedDrive && photosCount > 0 ? (
        <>
        {/* 窄窗口下这一行会挤爆(Phase 4 又加了"排序方向"和"筛选"两个控件):
            用 flex-wrap 换成多行, 并给每项 shrink-0 + whitespace-nowrap ——
            否则 flex 会把按钮压窄、文字竖着折成两行(全/选、AI 分/析)。
            min-h-9 + py-1 保证只有一行时高度与原来完全相同。 */}
        <div className="flex flex-wrap items-center px-4 min-h-9 py-1 gap-2">
          <button onClick={onSelectAll} className="shrink-0 whitespace-nowrap text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.selectAll")}</button>
          <button onClick={onClearSelection} className="shrink-0 whitespace-nowrap text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.clear")}</button>
          <span className="shrink-0 whitespace-nowrap text-[10px] text-zinc-600">{t("toolbar.selected", { n: selectedCount, total: photosCount })}</span>
          <select
            value={sortBy}
            onChange={(e) => onSortByChange(e.target.value as "name" | "type" | "date")}
            className="shrink-0 bg-zinc-800 text-[10px] text-zinc-400 px-1 py-0.5 rounded border border-zinc-700"
          >
            <option value="name">{t("toolbar.sortName")}</option>
            <option value="type">{t("toolbar.sortType")}</option>
            <option value="date">{t("toolbar.sortDate")}</option>
          </select>
          {/* 排序方向: asc = "今天的观感"(name/type A→Z, date 新→旧), 见 docs 4.5 ——
              所以提示只写"切换方向", 不写"升序/降序"(否则与日期的字面含义打架) */}
          <Tip label={t("toolbar.sortDir")} className="flex items-center shrink-0">
          <button
            onClick={onToggleSortDir}
            className="shrink-0 w-5 h-5 flex items-center justify-center rounded text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800"
          >
            {sortDir === "asc"
              ? <SortAmountUp theme="outline" size="13" strokeWidth={3} />
              : <SortAmountDown theme="outline" size="13" strokeWidth={3} />}
          </button>
          </Tip>
          {[0, 1, 2, 3, 4, 5].map((s) => (
            <button
              key={s}
              onClick={() => onStarFilterChange(starFilter === s ? 0 : s)}
              className={`shrink-0 whitespace-nowrap text-[10px] px-1 rounded ${starFilter === s ? "text-amber-400 bg-amber-400/10" : "text-zinc-600 hover:text-zinc-400"}`}
            >
              {s === 0 ? t("toolbar.all") : "★".repeat(s)}
            </button>
          ))}
          {/* Phase 4: 标签 + 分析结果收进这里(那一行本来就满, 硬塞会挤爆) */}
          <button
            onClick={() => setFilterOpen((v) => !v)}
            className={`shrink-0 whitespace-nowrap text-[10px] px-2 py-0.5 rounded ${
              filtersActive || filterOpen ? "bg-zinc-700 text-zinc-200" : "bg-zinc-800 text-zinc-500 hover:text-zinc-300"
            }`}
          >
            {t("toolbar.filter")}
          </button>
          <ThumbSizeSlider />
          {/* ml-auto 而不是 flex-1 占位块: 换行后这组按钮仍贴右, 占位块会让它留在左侧 */}
          <button
            onClick={() => analyzing ? onStopAnalysis() : onAnalyzeAll()}
            title={!analyzing && analyzeCount > 0 ? t("toolbar.aiSelected", { n: analyzeCount }) : undefined}
            className={`ml-auto shrink-0 whitespace-nowrap text-[10px] px-2 py-0.5 rounded text-zinc-400 ${
              analyzing
                ? "bg-red-900/50 hover:bg-red-800/50 text-red-400"
                : "bg-zinc-800 hover:bg-zinc-700"
            }`}
          >
            {/* 有勾选就分析勾选的(带张数), 否则分析整个文件夹 —— 与 App 里的 scope 同一份定义 */}
            {analyzing ? t("toolbar.stop") : analyzeCount > 0 ? t("toolbar.aiCount", { n: analyzeCount }) : t("toolbar.ai")}
          </button>
          {/* 收起按钮 — 集成在主体内 */}
          <Tip label={t("bars.collapse")} className="flex items-center shrink-0">
          <button
            onClick={onToggle}
            className="w-6 h-6 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            <UpOne theme="filled" size="13" strokeWidth={3} />
          </button>
          </Tip>
        </div>
        {filterOpen && (
          <div className="flex items-center flex-wrap gap-2 px-4 pb-2 -mt-1 text-[10px] text-zinc-600">
            <span className="shrink-0 whitespace-nowrap text-zinc-500">{t("label.title")}</span>
            <button
              onClick={() => onLabelFilterChange([])}
              className={`shrink-0 whitespace-nowrap px-1 rounded ${labelFilter.length === 0 ? "text-zinc-200 bg-zinc-700" : "text-zinc-600 hover:text-zinc-400"}`}
            >{t("toolbar.all")}</button>
            {/* 色卡用 title 而不是 Tip: 这一层有 overflow-hidden, Tip 的绝对定位气泡会被裁掉 */}
            {LABEL_ORDER.map((l) => (
              <button
                key={l}
                onClick={() => toggleLabel(l)}
                title={t(`label.${l}`)}
                className={`shrink-0 w-4 h-4 rounded-full border ${
                  labelFilter.includes(l)
                    ? "ring-2 ring-white/70 border-white/70"
                    : "border-white/20 opacity-60 hover:opacity-100"
                } ${LABEL_BG[l]}`}
              />
            ))}
            <span className="shrink-0 whitespace-nowrap ml-2 text-zinc-500">{t("toolbar.flags")}</span>
            <select
              value={flagFilter}
              onChange={(e) => onFlagFilterChange(e.target.value as FlagFilter)}
              className="shrink-0 bg-zinc-800 text-[10px] text-zinc-400 px-1 py-0.5 rounded border border-zinc-700"
            >
              <option value="all">{t("toolbar.all")}</option>
              <option value="blurry">{t("grid.blurry")}</option>
              <option value="over">{t("grid.overexposed")}</option>
              <option value="under">{t("grid.underexposed")}</option>
              <option value="duplicate">{t("grid.duplicate")}</option>
              <option value="best">{t("grid.best")}</option>
            </select>
            <button
              onClick={onClearFilters}
              className="ml-auto shrink-0 whitespace-nowrap px-2 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300"
            >{t("toolbar.clearFilters")}</button>
          </div>
        )}
        </>
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
