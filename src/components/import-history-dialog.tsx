import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { formatBytes } from "../lib/format";
import { DEFAULT_HISTORY_LIMIT, HISTORY_MAX, HISTORY_PAGE_SIZE, formatImportedAt, type ImportHistoryApi } from "../import-history";
import { dirOfPath } from "../xmp";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  history: ImportHistoryApi;
  /** 打开文件夹(由 App 注入 open_folder); 组件不直接 invoke */
  onOpenFolder: (dir: string) => void;
}

/**
 * 导入历史 —— Phase 6 / 6.1。
 *
 * 为什么是**独立对话框**而不是设置对话框里的一个 tab: 设置对话框 9 行已经顶到 88vh
 * (会话 ③ 第 28 项就是"多加一行把滚动条顶出来"的教训), 再塞一个最多 500 行的列表
 * 必然超高; 而且历史是**任务态**("我刚导的那批去哪了"), 入口该贴着导入动作。
 *
 * 列表**同时显示 sourcePath 与 destPath** —— `{seq}` 改名后原文件名只在 source 这一列,
 * 这就是这个界面存在的首要理由。
 */
export function ImportHistoryDialog({ open, onOpenChange, history, onOpenFolder }: Props) {
  const { t } = useTranslation();
  const { items, total, loading, error, load } = history;

  // 每次打开都重新读: 验收要求"能查到刚才那次导入"。深度沿用当前已加载的条数(不把
  // "加载更多"重置, 但至少 100 + 一页)。**依赖故意只有 open** —— 加 items.length 会
  // 让"load 的结果"再次触发 load, 成环。
  useEffect(() => {
    if (!open) return;
    load(Math.min(HISTORY_MAX, Math.max(DEFAULT_HISTORY_LIMIT, items.length + HISTORY_PAGE_SIZE)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const canLoadMore = items.length < total && items.length < HISTORY_MAX;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* no-scrollbar: 通用约定(共同前置约束 9)。500 行的列表必须能滚, 但不显示侧边滚动条 */}
      <DialogContent className="w-[620px] max-h-[80vh] overflow-auto no-scrollbar">
        <DialogHeader>
          <DialogTitle>{t("import.history")}</DialogTitle>
          <DialogDescription>{t("import.historyHint")}</DialogDescription>
        </DialogHeader>

        <div className="flex items-center justify-between text-[11px] text-muted-foreground">
          <span>{t("import.historyTotal", { n: total })}</span>
          {total > 0 && <span>{t("import.historyShown", { shown: items.length, total })}</span>}
        </div>

        {error && <div className="text-[11px] text-red-400">{t("import.historyError", { msg: error })}</div>}

        {!error && total === 0 && (
          <div className="text-[11px] text-muted-foreground py-6 text-center">{t("import.historyEmpty")}</div>
        )}

        <div className="space-y-2">
          {items.map((h) => (
            <div key={h.id} className="rounded-md border border-border px-2 py-1.5">
              <div className="flex items-baseline gap-2">
                <span className="text-[9px] text-muted-foreground shrink-0">{t("import.historyColSource")}</span>
                <span className="text-[11px] truncate flex-1" title={h.sourcePath}>{h.sourcePath}</span>
              </div>
              <div className="flex items-baseline gap-2 mt-0.5">
                <span className="text-[9px] text-muted-foreground shrink-0">{t("import.historyColDest")}</span>
                <button
                  type="button"
                  onClick={() => onOpenFolder(dirOfPath(h.destPath))}
                  title={`${h.destPath}\n${t("import.historyOpenFolder")}`}
                  className="text-[11px] truncate flex-1 text-left text-emerald-500/90 hover:text-emerald-400 hover:underline"
                >
                  {h.destPath}
                </button>
                <span className="text-[9px] text-muted-foreground shrink-0">
                  {formatImportedAt(h.importedAt)} · {formatBytes(h.fileSize)}
                </span>
              </div>
            </div>
          ))}
        </div>

        {canLoadMore && (
          <button
            type="button"
            disabled={loading}
            onClick={() => load(items.length + HISTORY_PAGE_SIZE)}
            className="text-[11px] py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50"
          >
            {t("import.historyLoadMore")}
          </button>
        )}
      </DialogContent>
    </Dialog>
  );
}
