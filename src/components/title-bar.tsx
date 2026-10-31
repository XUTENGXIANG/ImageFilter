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
// Windows: 左侧产品名, 右侧 [帮助][设置][主题] + [最小化][最大化][关闭]。
// macOS:   窗口控件是左上三个圆点(自绘, 因为窗口是 decorations:false); 右侧**不放**
//          Windows 那套按钮。按用户要求, [帮助][设置][主题] 也移到**左边**,
//          顺序是: 三个圆点 → 产品名 → 三个图标按钮。
//          「不要压住三个圆点」靠分组间距与左侧留白保证(圆点组 ml-3.5, 图标组再隔开)。
// 圆角不在这里做 —— 由 App.tsx 的外壳在 macOS 上裁(见那边的注释)。
export function TitleBar({
  preloadFull,
  onTogglePreloadFull,
  autoAdvance,
  onToggleAutoAdvance,
  showCounts,
  onToggleShowCounts,
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
  showCounts: boolean;
  onToggleShowCounts: () => void;
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
  // 只有确认是 macOS 才换外观 —— 探测失败("unknown")保持 Windows 外观(今天的行为)。
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

  // macOS 一次性把玻璃默认值纠正到 70(用户要求)。
  // 为什么需要"迁移"而不只是改默认值: 1.2.0 在 macOS 上已经跑过, 它当时把 0 写进了这两个键
  // (0 就是当时的默认值), 于是"没存过才用新默认"的分支永远走不到 —— 用户看到的就是 0%。
  // 所以只在 macOS、且这个标记还没写过时, 把仍然是 0 的那两个键纠到 70;
  // 标记写完之后, 用户自己拖过的值一律优先, 不再介入。
  useEffect(() => {
    if (!isMac) return;
    try {
      if (localStorage.getItem("imagefilter-mac-defaults-v1") !== null) return;
      const bump = (key: string) => {
        const raw = localStorage.getItem(key);
        if (raw === null || Number(raw) === 0) {
          localStorage.setItem(key, "70");
        }
      };
      bump("imagefilter-glass-opacity");
      bump("imagefilter-background-opacity");
      localStorage.setItem("imagefilter-mac-defaults-v1", "1");
      setGlassOpacity(70);
      onBackgroundOpacityChange(70);
    } catch {
      /* 隐私模式下 localStorage 不可用: 忽略, 下次再试 */
    }
  }, []);

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

  // 三个图标按钮(帮助/设置/主题)在 Windows 上放右边、在 macOS 上放左边 ——
  // 同一份 JSX, 只换它被摆在哪一组里, 避免两个平台各写一遍(它会漂)。
  const iconButtons = (
    <>
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
    </>
  );

  return (
    <div
      data-tauri-drag-region
      className={`h-9 flex items-center justify-between px-1 select-none flex-shrink-0 transition-colors duration-200 ${transparentBg ? "" : "bg-zinc-900"}`}
      style={transparentBg ? { backgroundColor: "var(--glass-bg)" } : undefined}
    >
      {isMac ? (
        // macOS: 圆点 → 产品名 → 图标按钮, 全部靠左。右半边留空(没有 Windows 那套按钮)。
        <div className="flex items-center h-full">
          <div className="flex items-center gap-2 ml-3.5">
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
          </div>
          <span className="text-[11px] text-zinc-500 ml-3">ImageFilter</span>
          {/* ml-2 是圆点组与图标组之间的额外间距 —— 让两者读起来是两组, 不是挤在一起 */}
          <div className="flex items-center h-7.5 ml-2">{iconButtons}</div>
        </div>
      ) : (
        <span className="text-[11px] text-zinc-500 ml-3">ImageFilter</span>
      )}
      <div className="flex items-center h-7.5">
        {!isMac && iconButtons}
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
              <Close theme="filled" size={14} strokeWidth={4} />
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
        showCounts={showCounts}
        onToggleShowCounts={onToggleShowCounts}
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
        platform={platform}
        glassOpacity={glassOpacity}
        onGlassOpacityChange={setGlassOpacity}
        backgroundOpacity={backgroundOpacity}
        onBackgroundOpacityChange={onBackgroundOpacityChange}
      />

      <HelpDialog open={showHelp} onOpenChange={setShowHelp} />
    </div>
  );
}
