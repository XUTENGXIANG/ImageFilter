import { useTranslation } from "react-i18next";
import { Check, Close, FolderOpen, More, Right } from "@icon-park/react";
import { AdvancedOptions } from "./advanced-options";
import { CollapsibleBar } from "./collapsible-bar";
import { Tip } from "./tip";

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
  importProgress: { fileName: string; status: string; message: string }[];
  importError: string | null;
  importResult: { ok: number; fail: number } | null;
  selectedCount: number;
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
  importError,
  importResult,
  selectedCount,
  onPickDestDir,
  onOpenFolder,
  onImport,
  expanded,
  onToggle,
}: Props) {
  const { t } = useTranslation();

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
            ? t("import.importingCount", { done: importProgress.filter((p) => p.status === "done").length, total: selectedCount })
            : t("import.importCount", { n: selectedCount })}
        </button>
      </div>
      {importError && (
        <div className="px-3 pb-1 text-[10px] text-red-400">{t("import.error", { msg: importError })}</div>
      )}
      {importResult && (
        <div className="px-3 pb-1 text-[10px] text-emerald-400">
          {t("import.doneOk", { n: importResult.ok })}{importResult.fail > 0 ? t("import.doneFail", { n: importResult.fail }) : ""}
        </div>
      )}
      <AdvancedOptions
        folderRule={folderRule}
        fileRule={fileRule}
        setFolderRule={setFolderRule}
        setFileRule={setFileRule}
        customFolder={customFolder}
        setCustomFolder={setCustomFolder}
        useCustomFolder={useCustomFolder}
        setUseCustomFolder={setUseCustomFolder}
      />
      {importing && importProgress.length > 0 && (
        <div className="px-3 pb-1.5 max-h-16 overflow-auto no-scrollbar">
          {importProgress.slice(-4).map((p, i) => (
            <div key={i} className="text-[9px] text-zinc-500 flex gap-1.5">
              <span className={
                p.status === "error" ? "text-red-400" :
                p.status === "done" ? "text-emerald-400" :
                p.status === "skipped" ? "text-zinc-600" : "text-zinc-500"
              }>
                {p.status === "done" ? <Check theme="filled" size="10" strokeWidth={4} /> :
                 p.status === "error" ? <Close theme="filled" size="10" strokeWidth={4} /> :
                 p.status === "skipped" ? <Right theme="filled" size="10" strokeWidth={4} /> :
                 <More theme="filled" size="10" strokeWidth={4} />}
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
