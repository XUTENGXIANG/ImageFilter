// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { invoke } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
import { setLanguage, type Lang } from "../i18n";
import { useLocalStorageNumber, useLocalStorageSetting } from "../hooks/use-local-storage-setting";
import type { XmpMode, XmpStatus } from "../xmp";
import type { LrcSendMode } from "../lightroom";
import type { LightroomProbe } from "../types";
import { SettingsDialog } from "./settings-dialog";
import { HelpDialog } from "./help-dialog";
import { Setting, Sun, Moon, Close, Help } from "@icon-park/react";
import { Tip } from "./tip";

// ── 标题栏 ──────────────────────────────────
// 高度: h-9 (36px)  背景: bg-zinc-900  底部边框: border-zinc-800
// 拖拽: data-tauri-drag-region  禁止选中: select-none
//
// ── 平台外观(macOS) ─────────────────────────
// Windows: 右侧三键(最小化/最大化/关闭) + 左侧产品名 —— 就是 Windows 的窗口按钮语义。
// macOS:   窗口控件在**左上**、是三个圆点(红=关闭, 黄=最小化, 绿=缩放), 右侧不该出现
//          Windows 那套按钮。这里按 platform 分支自绘三个圆点(系统那套只在
//          原生标题栏存在时才有, 而本项目是 decorations:false 的自绘窗口)。
// 圆角不在这里做 —— 由 App.tsx 的外壳在 macOS 上裁(见那边的注释)。
export function TitleBar({
  preloadFull,
  onTogglePreloadFull,
  autoAdvance,
  onToggleAutoAdvance,
  labelModifier,
  onLabelModifierChange,
  xmpMode,
  onXmpModeChange,
  xmpStatus,
  lrcProbe,
  lrcMode,
  onLrcModeChange,
  onReprobeLightroom,
  transparentBg,
  onToggleTransparentBg,
  micaUnsupported,
  platform,
  backgroundOpacity,
  onBackgroundOpacityChange,
}: {
  preloadFull: boolean;
  onTogglePreloadFull: () => void;
  autoAdvance: boolean;
  onToggleAutoAdvance: () => void;
  labelModifier: "ctrl" | "alt";
  onLabelModifierChange: (v: "ctrl" | "alt") => void;
  /** Phase 5: XMP 边车档位与状态(只透传给设置对话框) */
  xmpMode: XmpMode;
  onXmpModeChange: (m: XmpMode) => void;
  xmpStatus: XmpStatus | null;
  /** Phase 7: Lightroom 衔接(只透传给设置对话框) */
  lrcProbe: LightroomProbe | null;
  lrcMode: LrcSendMode;
  onLrcModeChange: (m: LrcSendMode) => void;
  onReprobeLightroom: () => void;
  transparentBg: boolean;
  onToggleTransparentBg: () => void;
  /** 系统不支持 Mica(Win10)时为真: 设置里把玻璃开关置灰并换掉说明文案 */
  micaUnsupported: boolean;
  /** 来自 Rust `get_os_capabilities` 的 platform: "windows" / "macos" / "linux" / "unknown" */
  platform: string;
  backgroundOpacity: number;
  onBackgroundOpacityChange: (v: number) => void;
}) {
  const { t } = useTranslation();
  const win = getCurrentWindow();
  // 只有确认是 macOS 才切外观 —— 探测失败("unknown")保持 Windows 外观(今天的行为)。
  const isMac = platform === "macos";
  const [showSettings, setShowSettings] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  // 主题/语言/玻璃透明度 — key 与 index.html 防闪脚本一致(imagefilter-*)
  const [theme, setTheme] = useLocalStorageSetting<"dark" | "light">("imagefilter-theme", "dark");
  const [lang, setLang] = useLocalStorageSetting<Lang>("imagefilter-lang", "zh");
  const [glassOpacity, setGlassOpacity] = useLocalStorageNumber("imagefilter-glass-opacity", 70, 0, 100);

  useEffect(() => {
    const root = document.documentElement;
    if (theme === "dark") {
      root.classList.add("dark");
      root.setAttribute("data-theme", "dark");
    } else {
      root.classList.remove("dark");
      root.setAttribute("data-theme", "light");
    }
  }, [theme]);

  useEffect(() => {
    document.documentElement.style.setProperty("--glass-opacity", `${glassOpacity}%`);
  }, [glassOpacity]);

  // 一律使用 Mica(失焦时系统自动回退为纯色), 仅在开关/主题变化时应用
  useEffect(() => {
    invoke("set_glass_bg", {
      enabled: transparentBg,
      dark: theme === "dark",
    }).catch(() => {});
  }, [transparentBg, theme]);

  const changeLang = (l: Lang) => {
    setLang(l);
    setLanguage(l);
  };

  return (
    <div
      data-tauri-drag-region
      className={`h-9 flex items-center justify-between px-1 select-none flex-shrink-0 transition-colors duration-200 ${transparentBg ? "" : "bg-zinc-900"}`}
      style={transparentBg ? { backgroundColor: "var(--glass-bg)" } : undefined}
    >
      {isMac ? (
        // macOS: 左上三个窗口圆点(自绘, 因为窗口是 decorations:false)。
        // 顺序与颜色照系统: 红关 / 黄最小化 / 绿缩放; 直径 12px、间距 8px。
        <div className="flex items-center gap-2 ml-3.5 h-full">
          <button
            type="button"
            aria-label={t("titlebar.close")}
            title={t("titlebar.close")}
            onClick={() => { win.close().catch(() => {}); }}
            className="w-3 h-3 rounded-full bg-[#ff5f57] hover:brightness-110 active:brightness-90 transition-[filter]"
          />
          <button
            type="button"
            aria-label={t("titlebar.min")}
            title={t("titlebar.min")}
            onClick={() => { win.minimize().catch(() => {}); }}
            className="w-3 h-3 rounded-full bg-[#febc2e] hover:brightness-110 active:brightness-90 transition-[filter]"
          />
          <button
            type="button"
            aria-label={t("titlebar.max")}
            title={t("titlebar.max")}
            onClick={() => { win.toggleMaximize().catch(() => {}); }}
            className="w-3 h-3 rounded-full bg-[#28c840] hover:brightness-110 active:brightness-90 transition-[filter]"
          />
          <span className="text-[11px] text-zinc-500 ml-2">ImageFilter</span>
        </div>
      ) : (
        <span className="text-[11px] text-zinc-500 ml-3">ImageFilter</span>
      )}
      <div className="flex items-center h-7.5">
        <Tip label={t("titlebar.help")} className="h-full flex items-center">
        <button type="button" onClick={() => setShowHelp(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          <Help theme="filled" size="15" strokeWidth={3} />
        </button>
        </Tip>
        <Tip label={t("titlebar.settings")} className="h-full flex items-center">
        <button type="button" onClick={() => setShowSettings(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          <Setting theme="filled" size="15" strokeWidth={3} />
        </button>
        </Tip>
        <Tip label={theme === "dark" ? t("titlebar.themeLight") : t("titlebar.themeDark")} className="h-full flex items-center">
        <button type="button" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          {theme === "dark" ? <Sun theme="filled" size="15" strokeWidth={3} /> : <Moon theme="filled" size="15" strokeWidth={3} />}
        </button>
        </Tip>
        {/* Windows 的窗口三键只在非 macOS 出现 —— macOS 用左上的圆点, 不再重复一套 */}
        {!isMac && (
          <>
            <Tip label={t("titlebar.min")} className="h-full flex items-center">
            <button type="button" onClick={() => { win.minimize().catch(() => {}); }}
              className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md">
              <svg width="10" height="1"><rect width="10" height="1" fill="currentColor"/></svg>
            </button>
            </Tip>
            <Tip label={t("titlebar.max")} className="h-full flex items-center">
            <button type="button" onClick={() => { win.toggleMaximize().catch(() => {}); }}
              className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md">
              <svg width="10" height="10" viewBox="0 0 10 10">
                <rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor" strokeWidth="1"/>
              </svg>
            </button>
            </Tip>
            <Tip label={t("titlebar.close")} className="h-full flex items-center">
            <button type="button" onClick={() => { win.close().catch(() => {}); }}
              className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-red-400 hover:bg-red-400/10 rounded-md">
              <Close theme="filled" size="14" strokeWidth={4} />
            </button>
            </Tip>
          </>
        )}
      </div>

      <SettingsDialog
        open={showSettings}
        onOpenChange={setShowSettings}
        theme={theme}
        onThemeChange={setTheme}
        lang={lang}
        onLangChange={changeLang}
        preloadFull={preloadFull}
        onTogglePreloadFull={onTogglePreloadFull}
        autoAdvance={autoAdvance}
        onToggleAutoAdvance={onToggleAutoAdvance}
        labelModifier={labelModifier}
        onLabelModifierChange={onLabelModifierChange}
        xmpMode={xmpMode}
        onXmpModeChange={onXmpModeChange}
        xmpStatus={xmpStatus}
        lrcProbe={lrcProbe}
        lrcMode={lrcMode}
        onLrcModeChange={onLrcModeChange}
        onReprobeLightroom={onReprobeLightroom}
        transparentBg={transparentBg}
        onToggleTransparentBg={onToggleTransparentBg}
        micaUnsupported={micaUnsupported}
        glassOpacity={glassOpacity}
        onGlassOpacityChange={setGlassOpacity}
        backgroundOpacity={backgroundOpacity}
        onBackgroundOpacityChange={onBackgroundOpacityChange}
      />

      <HelpDialog open={showHelp} onOpenChange={setShowHelp} />
    </div>
  );
}
