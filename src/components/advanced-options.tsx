import { useState } from "react";
import { useTranslation } from "react-i18next";

export function AdvancedOptions({
  folderRule, fileRule, setFolderRule, setFileRule,
  customFolder, setCustomFolder, useCustomFolder, setUseCustomFolder,
}: {
  folderRule: string; fileRule: string;
  setFolderRule: (v: string) => void; setFileRule: (v: string) => void;
  customFolder: string; setCustomFolder: (v: string) => void;
  useCustomFolder: boolean; setUseCustomFolder: (v: boolean) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  /** 从规则字符串中移除指定 token（含其前面的 "/" 分隔符），其他 token 保留 */
  const removeToken = (rule: string, token: string) =>
    rule
      .replace(`/${token}`, "")
      .replace(`${token}/`, "")
      .replace(token, "");

  const toggleDate = () =>
    setFolderRule(
      folderRule.includes("{date}")
        ? removeToken(folderRule, "{date}")
        : folderRule
          ? `${folderRule}/{date}`
          : "{date}"
    );
  const toggleCamera = () =>
    setFolderRule(
      folderRule.includes("{camera}")
        ? removeToken(folderRule, "{camera}")
        : folderRule
          ? `${folderRule}/{camera}`
          : "{camera}"
    );
  const toggleSeq = () => setFileRule(fileRule === "{seq}.{ext}" ? "" : "{seq}.{ext}");

  return (
    <div className="px-3">
      <button
        onClick={() => setOpen(!open)}
        className="text-[10px] text-zinc-600 hover:text-zinc-400"
      >
        {open ? `▾ ${t("import.advanced")}` : `▸ ${t("import.advanced")}`}
      </button>
      {open && (
        <div className="mt-1 pb-1.5 space-y-1">
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{date}")} onChange={toggleDate}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.dateFolder")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.dateFolderEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{camera}")} onChange={toggleCamera}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.cameraFolder")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.cameraFolderEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={fileRule === "{seq}.{ext}"} onChange={toggleSeq}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.seqRename")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.seqRenameEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={useCustomFolder}
              onChange={() => setUseCustomFolder(!useCustomFolder)}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.subFolder")}</span>
            {useCustomFolder && (
              <input
                value={customFolder}
                onChange={(e) => setCustomFolder(e.target.value)}
                placeholder={t("import.subFolderPlaceholder")}
                className="w-28 bg-zinc-800 text-[10px] text-zinc-300 px-2 py-0.5 rounded border border-zinc-700"
              />
            )}
          </label>
        </div>
      )}
    </div>
  );
}
