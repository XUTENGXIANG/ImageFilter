// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Collapse } from "./collapse";
import { Dropdown } from "./ui/select";
import {
  isSeqRule, keepsOriginalWithSeq, schemeOptions, toggleKeepOriginalRule, toggleSeqRule,
  type ImportSchemeApi,
} from "../import-rules";

export function AdvancedOptions({
  folderRule, fileRule, setFolderRule, setFileRule,
  customFolder, setCustomFolder, useCustomFolder, setUseCustomFolder,
  scheme,
}: {
  folderRule: string; fileRule: string;
  setFolderRule: (v: string) => void; setFileRule: (v: string) => void;
  customFolder: string; setCustomFolder: (v: string) => void;
  useCustomFolder: boolean; setUseCustomFolder: (v: boolean) => void;
  /** Phase 6 / 6.2: 命名方案(列表 + 选中 + 另存为) */
  scheme: ImportSchemeApi;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [saveAsOpen, setSaveAsOpen] = useState(false);
  const [newName, setNewName] = useState("");

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

  // ── 「按序号重命名」的两种规范形态(Phase 6 / 6.2) ────────────────────
  // 判定与切换都在 import-rules.ts(纯函数, 有 Node 断言钉住):
  //   勾上 = SEQ_ONLY(与改动前逐字节相同 → 老用户归档结构不变), 子选项 = SEQ_KEEP。
  const seqOn = isSeqRule(fileRule);
  const keepWithSeq = keepsOriginalWithSeq(fileRule);

  const toggleSeq = () => setFileRule(toggleSeqRule(fileRule));
  const toggleKeepOriginal = () => setFileRule(toggleKeepOriginalRule(fileRule));

  const saveAs = async () => {
    const ok = await scheme.save(newName);
    if (ok) {
      setNewName("");
      setSaveAsOpen(false);
    }
  };

  return (
    <div className="px-3">
      <button
        onClick={() => {
          // 方案列表惰性加载: 首次展开才拉(不在启动时白跑一次 IPC)
          if (!open) scheme.load();
          setOpen(!open);
        }}
        className="text-[10px] leading-4 py-1 inline-flex items-center gap-1 text-zinc-600 hover:text-zinc-400"
      >
        {/* 箭头用同一个 ▸ 旋转, 而不是 ▸/▾ 换字形 —— 换字形是文本内容变化,
            CSS 过渡无从插值(与文件夹树的箭头同一个理由)。 */}
        <i
          aria-hidden="true"
          className="not-italic inline-block transition-transform duration-[160ms]"
          style={{ transform: open ? "rotate(90deg)" : "none" }}
        >
          ▸
        </i>
        {t("import.advanced")}
      </button>
      <Collapse open={open}>
        <div className="mt-1 pb-1.5 space-y-1">
          {/* ── 命名方案(Phase 6 / 6.2) ── */}
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[10px] text-zinc-400 shrink-0">{t("import.scheme")}</span>
            <Dropdown
              value={scheme.name ?? ""}
              onValueChange={(v) => scheme.pick(v || null)}
              ariaLabel={t("import.scheme")}
              options={[
                { value: "", label: t("import.schemeCustom") },
                ...schemeOptions(scheme.rules, scheme.name).map((n) => ({ value: n, label: n })),
              ]}
              className="bg-zinc-800 text-[10px] leading-none text-zinc-400 border-zinc-700 px-1.5 max-w-[150px]"
            />
            <button
              onClick={() => setSaveAsOpen((v) => !v)}
              className="text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-500 shrink-0"
            >
              {t("import.schemeSaveAs")}
            </button>
            {saveAsOpen && (
              <>
                <input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder={t("import.schemeNamePlaceholder")}
                  className="w-24 bg-zinc-800 text-[10px] text-zinc-300 px-2 py-0.5 rounded border border-zinc-700"
                />
                <button
                  disabled={!newName.trim()}
                  onClick={saveAs}
                  className="text-[10px] leading-4 px-1.5 py-1 rounded bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-800 disabled:text-zinc-600 text-white shrink-0"
                >
                  {t("import.schemeSave")}
                </button>
              </>
            )}
            {/* 当前文件名模板: 用 JSX 直接渲染(绝不放 i18n 值里 —— 值里的裸 { } 会被
                i18next 当成插值变量解析掉, 见 i18n/index.ts 的 prefix/suffix) */}
            <span className="text-[9px] text-zinc-600">
              {t("import.fileRuleNowLabel")}
              <span className="font-mono text-zinc-500">{fileRule || t("import.fileRuleOriginal")}</span>
            </span>
          </div>
          {scheme.error && (
            <div className="text-[9px] text-red-400">{t("import.schemeError", { msg: scheme.error })}</div>
          )}

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
            <input type="checkbox" checked={seqOn} onChange={toggleSeq}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.seqRename")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.seqRenameEx")}</span>
          </label>
          {/* 子选项: 只在"按序号重命名"勾上时可用(它描述的是序号命名的一种形态) */}
          <label className={`flex items-center gap-1.5 ml-4 ${seqOn ? "cursor-pointer" : "opacity-40"}`}>
            <input type="checkbox" disabled={!seqOn} checked={keepWithSeq} onChange={toggleKeepOriginal}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.seqKeepOriginal")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.seqKeepEx")}</span>
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
      </Collapse>
    </div>
  );
}
