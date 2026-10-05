import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Check, Close, FolderOpen, More, Right } from "@icon-park/react";
import { AdvancedOptions } from "./advanced-options";
import { CollapsibleBar } from "./collapsible-bar";
import { ImportHistoryDialog } from "./import-history-dialog";
import { Tip } from "./tip";
import type { ImportProgress, ImportSummary, LightroomProbe } from "../types";
import type { ImportHistoryApi } from "../import-history";
import type { ImportSchemeApi } from "../import-rules";
import type { LrcSentInfo } from "../lightroom";

interface Props {
  destDir: string | null;
  folderRule: string;
  fileRule: string;
  setFolderRule: (v: string) => void;
  setFileRule: (v: string) => void;
  customFolder: string;
  setCustomFolder: (v: string) => void;
  useCustomFolder: boolean;
  setUseCustomFolder: (v: boolean) => void;
  importing: boolean;
  importProgress: ImportProgress[];
  importDone: number;
  importError: string | null;
  /** Phase 6 / 6.3: 导入结果统计(契约: ImportSummary —— 直接显示, 前端不再自己算) */
  importResult: ImportSummary | null;
  /** Phase 6 / 6.1: 导入历史(列表 + 总数 + 加载入口), 由 useScanner 组装 */
  history: ImportHistoryApi;
  /** Phase 6 / 6.2: 命名方案(方案下拉 + 另存为) */
  scheme: ImportSchemeApi;
  selectedCount: number;
  /**
   * Phase 7: 是否允许在"一张都没勾"时也能发送 —— 此时发的是当前浏览的文件夹。
   * 由 App 传 activeFolder 是否非空决定(没有浏览任何文件夹时这个入口没有意义)。
   */
  lrcCanUseActiveFolder: boolean;
  /** Phase 7: LrC 探测结果。found=false 时整个"发送到 Lightroom"入口隐藏 */
  lrcProbe: LightroomProbe | null;
  lrcSending: boolean;
  /** 上一次成功发送的信息(含"还有 N 个文件夹没发"的实话) */
  lrcSent: LrcSentInfo | null;
  onSendToLightroom: () => void;
  onPickDestDir: () => void;
  onOpenFolder: (dir: string) => void;
  onImport: () => void;
  expanded: boolean;
  onToggle: () => void;
}

export function ImportBar({
  destDir,
  folderRule,
  fileRule,
  setFolderRule,
  setFileRule,
  customFolder,
  setCustomFolder,
  useCustomFolder,
  setUseCustomFolder,
  importing,
  importProgress,
  importDone,
  importError,
  importResult,
  history,
  scheme,
  selectedCount,
  lrcCanUseActiveFolder,
  lrcProbe,
  lrcSending,
  lrcSent,
  onSendToLightroom,
  onPickDestDir,
  onOpenFolder,
  onImport,
  expanded,
  onToggle,
}: Props) {
  const { t } = useTranslation();
  const [historyOpen, setHistoryOpen] = useState(false);

  return (
    <CollapsibleBar align="bottom" expanded={expanded} onToggle={onToggle}>
      <div className="flex items-center gap-2 px-3 py-1.5">
        <button
          onClick={onPickDestDir}
          className="text-[10px] px-2 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-400 truncate max-w-[180px]"
        >
          {destDir ? `...${destDir.slice(-25)}` : t("import.pickDest")}
        </button>
        {destDir && (
          <Tip label={t("import.openFolder")}>
          <button
            onClick={() => onOpenFolder(destDir)}
            className="text-[10px] px-1.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-500"
          >
            <FolderOpen theme="filled" size="12" strokeWidth={3} />
          </button>
          </Tip>
        )}
        <div className="flex-1" />
        {/* Phase 7 · 一键交给 Lightroom Classic。
            只发"选中照片所在的那一个文件夹"(实测只验证过这一种形式), 由 LrC
            自己打开导入对话框 —— 所以本按钮的语义是"打开 LrC 并定位到这批照片",
            不是"无声导入"。LrC 未安装时整块隐藏(不给一个点了只会报错的按钮)。 */}
        {lrcProbe?.found && (
          <Tip label={t("lrc.sendTip")}>
          <button
            disabled={lrcSending || (selectedCount === 0 && !lrcCanUseActiveFolder)}
            onClick={onSendToLightroom}
            className="text-[10px] px-2 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-sky-400 disabled:bg-zinc-800/50 disabled:text-zinc-600 shrink-0"
          >
            {lrcSending ? t("lrc.sending") : t("lrc.send")}
          </button>
          </Tip>
        )}
        {/* 导入历史: 任务态入口, 贴着导入动作(不进设置对话框 —— 见 import-history-dialog.tsx 注释) */}
        <button
          onClick={() => setHistoryOpen(true)}
          className="text-[10px] px-1.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-500 shrink-0"
        >
          {t("import.history")}
        </button>
        <span className="text-[10px] text-zinc-600">
          {!destDir ? t("import.needDest") :
           selectedCount === 0 ? t("import.needSelect") :
           importing ? t("import.importing") : ""}
        </span>
        <button
          disabled={!destDir || selectedCount === 0 || importing}
          onClick={onImport}
          className="text-[10px] px-3 py-1 rounded bg-emerald-600 hover:bg-emerald-500 disabled:bg-zinc-700 disabled:text-zinc-500 text-white font-medium"
        >
          {importing
            ? t("import.importingCount", { done: importDone, total: selectedCount })
            : t("import.importCount", { n: selectedCount })}
        </button>
      </div>
      {importError && (
        <div className="px-3 pb-1 text-[10px] text-red-400">{t("import.error", { msg: importError })}</div>
      )}
      {/* Phase 7 · 交给 LrC 之后的实话。
          必须显式说出"发的是哪个文件夹"和"还有 N 个文件夹没发": 只发一个文件夹是
          实测限制(见 src/lightroom.ts 不变式 2), 静默只发一部分会让用户以为全发了。 */}
      {lrcSent && (
        <div className="px-3 pb-1 text-[10px] text-sky-400 space-y-0.5">
          <div>
            {lrcSent.fromActiveFolder
              ? t("lrc.sentActiveFolder", { dir: lrcSent.folder })
              : t("lrc.sent", { n: lrcSent.count, dir: lrcSent.folder })}
          </div>
          {lrcSent.alsoInOtherFolders > 0 && (
            <div className="text-amber-400">
              {t("lrc.sentPartial", { n: lrcSent.alsoInOtherFolders })}
            </div>
          )}
        </div>
      )}
      {importResult && (
        <div className="px-3 pb-1 text-[10px] space-y-0.5">
          {/* 四行明细(Phase 6 / 6.3): 成功 / 改名 ⊂ 成功 / 跳过 / 失败。
              数字全部来自 Rust 的 ImportSummary —— 前端不许再做 paths.length - count
              那种算术(它会把"跳过"算成"失败")。为 0 的行不显示, 老路径观感不变。 */}
          <div className="text-emerald-400">{t("import.doneOk", { n: importResult.imported })}</div>
          {importResult.renamed > 0 && (
            <div className="text-amber-400">{t("import.doneRenamed", { n: importResult.renamed })}</div>
          )}
          {importResult.skipped > 0 && (
            <div className="text-zinc-500">{t("import.doneSkipped", { n: importResult.skipped })}</div>
          )}
          {importResult.failed > 0 && (
            <div className="text-red-400">{t("import.doneFail", { n: importResult.failed })}</div>
          )}
        </div>
      )}
      <ImportHistoryDialog
        open={historyOpen}
        onOpenChange={setHistoryOpen}
        history={history}
        onOpenFolder={onOpenFolder}
      />
      <AdvancedOptions
        folderRule={folderRule}
        fileRule={fileRule}
        setFolderRule={setFolderRule}
        setFileRule={setFileRule}
        customFolder={customFolder}
        setCustomFolder={setCustomFolder}
        useCustomFolder={useCustomFolder}
        setUseCustomFolder={setUseCustomFolder}
        scheme={scheme}
      />
      {importing && importProgress.length > 0 && (
        <div className="px-3 pb-1.5 max-h-16 overflow-auto no-scrollbar">
          {importProgress.slice(-4).map((p, i) => (
            <div key={`${i}-${p.fileName}`} className="text-[9px] text-zinc-500 flex gap-1.5">
              <span className={
                p.status === "error" ? "text-red-400" :
                p.status === "done" ? "text-emerald-400" :
                p.status === "renamed" ? "text-amber-400" :
                p.status === "verifying" ? "text-sky-400" :
                p.status === "skipped" ? "text-zinc-600" : "text-zinc-500"
              }>
                {p.status === "done" ? <Check theme="filled" size="10" strokeWidth={4} /> :
                 p.status === "error" ? <Close theme="filled" size="10" strokeWidth={4} /> :
                 p.status === "skipped" ? <Right theme="filled" size="10" strokeWidth={4} /> :
                 <More theme="filled" size={10} strokeWidth={4} />}
              </span>
              <span className="truncate flex-1">{p.fileName}</span>
              <span className="flex-shrink-0">{p.message}</span>
            </div>
          ))}
        </div>
      )}
    </CollapsibleBar>
  );
}
