// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";

const SEEN_KEY = "imagefilter-mac-perm-seen";

/**
 * macOS 首次启动的权限引导。
 *
 * 为什么需要: macOS 的 TCC 会对每个受保护目录(桌面 / 文稿 / 下载 / 外置卷…)单独询问
 * 「文件与文件夹」权限 —— 用户在挑照片文件夹、尤其开启「写入 .xmp」之后会被反复弹窗。
 * 根治办法是让用户一次性给「完全磁盘访问权限」, 但那个开关在系统设置里藏得很深,
 * 不给指引等于让人自己翻。所以首次启动把话说清, 并直接把他送到那一页。
 *
 * 只在 macOS 出现(平台由调用方判断后传进来), 且只出现一次(见过就记 localStorage)。
 * 不依赖 UI 库: 这是一个 self-contained 的固定层, 避免为了一个一次性引导去动对话框组件。
 */
export function MacPermissionGuide({ isMac }: { isMac: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<boolean>(() => {
    if (!isMac) return false;
    try {
      return localStorage.getItem(SEEN_KEY) === null;
    } catch {
      // 隐私模式下读不到 localStorage: 宁可不打扰, 也不要每次启动都弹
      return false;
    }
  });
  if (!isMac || !open) return null;

  const dismiss = () => {
    try {
      localStorage.setItem(SEEN_KEY, "1");
    } catch {
      /* 存不进去就算了: 下次再提示一遍也不算坏 */
    }
    setOpen(false);
  };

  const openSettings = () => {
    // 命令失败(或非 macOS)时静默降级 —— 下面的路径文字仍然把位置说清楚了
    invoke("open_macos_privacy_settings").catch(() => {});
    dismiss();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-6">
      <div className="w-full max-w-md rounded-xl border border-border bg-zinc-900 p-5 text-zinc-100 shadow-2xl">
        <h2 className="text-sm font-semibold">{t("settings.macPermTitle")}</h2>
        <p className="mt-2 text-xs leading-5 text-zinc-400">{t("settings.macPermBody")}</p>
        <p className="mt-2 rounded-md bg-zinc-800/70 px-2 py-1.5 text-[11px] leading-4 text-zinc-400">
          {t("settings.macPermPath")}
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={dismiss}
            className="rounded-md px-3 py-1.5 text-xs text-zinc-400 hover:bg-zinc-800"
          >
            {t("settings.macPermLater")}
          </button>
          <button
            type="button"
            onClick={openSettings}
            className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-emerald-500"
          >
            {t("settings.macPermOpen")}
          </button>
        </div>
      </div>
    </div>
  );
}
