import { useState } from "react";
import { useTranslation } from "react-i18next";
import { UpOne, SortAmountUp, SortAmountDown } from "@icon-park/react";
import { CollapsibleBar } from "./collapsible-bar";
import { Collapse } from "./collapse";
import { Dropdown } from "./ui/select";
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
        {/* 这一行要尽量保持**单行**(实机反馈: 不要换行、不要竖排)。为了做到这点:
            · 星级筛选收成了下拉(省掉原来 6 个 chip 约 250px 的宽度);
            · 每项 shrink-0 + whitespace-nowrap, 谁都不许被压窄、文字不许折行;
            · flex-wrap 只当**安全网** —— 窗口被压到极窄(左栏+右栏占位后不足 ~450px)时才换行,
              换行时右侧那组(AI 分析 + 收起箭头)整体落到下一行右端, 不会出现竖排文字;
            · min-h-9 + py-1: 单行时高度与原来的 h-9 完全一致。 */}
        <div className="flex flex-wrap items-center px-4 min-h-9 py-1 gap-2">
          <button onClick={onSelectAll} className="relative hit-24 shrink-0 whitespace-nowrap text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.selectAll")}</button>
          <button onClick={onClearSelection} className="relative hit-24 shrink-0 whitespace-nowrap text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.clear")}</button>
          <span className="shrink-0 whitespace-nowrap text-[10px] text-zinc-600">{t("toolbar.selected", { n: selectedCount, total: photosCount })}</span>
          <Dropdown
            value={sortBy}
            onValueChange={(v) => onSortByChange(v)}
            ariaLabel={t("toolbar.sortBy")}
            options={[
              { value: "name" as const, label: t("toolbar.sortName") },
              { value: "type" as const, label: t("toolbar.sortType") },
              { value: "date" as const, label: t("toolbar.sortDate") },
            ]}
            className="bg-zinc-800 text-[10px] leading-none text-zinc-400 border-zinc-700 px-1.5"
          />
          {/* 排序方向: asc = "今天的观感"(name/type A→Z, date 新→旧), 见 docs 4.5 ——
              所以提示只写"切换方向", 不写"升序/降序"(否则与日期的字面含义打架) */}
          <Tip label={t("toolbar.sortDir")} className="flex items-center shrink-0">
          <button
            onClick={onToggleSortDir}
            className="relative hit-24 shrink-0 w-5 h-5 flex items-center justify-center rounded text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800"
          >
            {sortDir === "asc"
              ? <SortAmountUp theme="outline" size="13" strokeWidth={3} />
              : <SortAmountDown theme="outline" size="13" strokeWidth={3} />}
          </button>
          </Tip>
          {/* 星级筛选收成一个下拉(实机反馈: 6 个 chip 一字排开占 ~250px, 窄窗口直接把那一行
              挤爆 —— 竖排或换行)。展开才呈现几星, 常态只占一个控件宽, 那一行因此能保持单行。
              语义没变: 仍是"至少 N 星"(0 = 全部); 选中态用琥珀色标出来, 免得忘了筛选还开着。 */}
          <Dropdown
            value={starFilter}
            onValueChange={(v) => onStarFilterChange(v)}
            ariaLabel={t("toolbar.starFilterLabel")}
            options={[
              { value: 0, label: t("toolbar.all") },
              ...[1, 2, 3, 4, 5].map((s) => ({ value: s, label: t("toolbar.starFilter", { n: s }) })),
            ]}
            className={`bg-zinc-800 text-[10px] leading-none px-1.5 ${
              starFilter > 0 ? "text-amber-400 border-amber-500/40" : "text-zinc-400 border-zinc-700"
            }`}
          />
          {/* Phase 4: 标签 + 分析结果收进这里(那一行本来就满, 硬塞会挤爆) */}
          <button
            onClick={() => setFilterOpen((v) => !v)}
            className={`relative hit-24 shrink-0 whitespace-nowrap text-[10px] px-2 py-0.5 rounded ${
              filtersActive || filterOpen ? "bg-zinc-700 text-zinc-200" : "bg-zinc-800 text-zinc-500 hover:text-zinc-300"
            }`}
          >
            {t("toolbar.filter")}
          </button>
          <ThumbSizeSlider />
          {/* 右侧这一组(AI 分析 + 收起箭头)**必须当成一个整体**: ml-auto 让整组永远贴右,
              组内不拆行 —— 只给 AI 按钮加 ml-auto 的话, 后面的箭头会被挤到下一行的最左边
              (用户实机反馈: 收起的箭头跑到第二行左端)。 */}
          <div className="ml-auto shrink-0 flex items-center gap-2">
          <button
            onClick={() => analyzing ? onStopAnalysis() : onAnalyzeAll()}
            title={!analyzing && analyzeCount > 0 ? t("toolbar.aiSelected", { n: analyzeCount }) : undefined}
            className={`relative hit-24 shrink-0 whitespace-nowrap text-[10px] px-2 py-0.5 rounded text-zinc-400 ${
              analyzing
                ? "bg-red-900/50 hover:bg-red-800/50 text-red-400"
                : "bg-zinc-800 hover:bg-zinc-700"
            }`}
          >
            {/* 有勾选就分析勾选的(带张数), 否则分析整个文件夹 —— 与 App 里的 scope 同一份定义。
                文案里必须出现"选中": 实机首测时 `AI 分析 (1)` 被问"后面这个 1 是什么"。 */}
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
        </div>
        <Collapse open={filterOpen}>
          {/* 展开后这一行的呼吸空间(实机反馈: 挤在上一行控件下面, 像被压住)。
              原来是 pb-2 + -mt-1 —— 负外边距把面板往上拽 4px, 实测第一行控件底边
              离面板里的控件顶边只剩 2px。现在改成 pt-1.5 + pb-3(去掉负外边距):
              上下各留 12px, 与内层边框那一圈 12px 内边距对齐, 面板成为独立的一条。 */}
          <div className="flex items-center flex-wrap gap-2 px-4 pt-1.5 pb-3 text-[10px] text-zinc-600">
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
            <Dropdown
              value={flagFilter}
              onValueChange={(v) => onFlagFilterChange(v)}
              ariaLabel={t("toolbar.flags")}
              options={[
                { value: "all" as const, label: t("toolbar.all") },
                { value: "blurry" as const, label: t("grid.blurry") },
                { value: "over" as const, label: t("grid.overexposed") },
                { value: "under" as const, label: t("grid.underexposed") },
                { value: "duplicate" as const, label: t("grid.duplicate") },
                { value: "best" as const, label: t("grid.best") },
              ]}
              className="bg-zinc-800 text-[10px] leading-none px-1.5 text-zinc-400 border-zinc-700"
            />
            <button
              onClick={onClearFilters}
              className="ml-auto shrink-0 whitespace-nowrap px-2 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300"
            >{t("toolbar.clearFilters")}</button>
          </div>
        </Collapse>
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
