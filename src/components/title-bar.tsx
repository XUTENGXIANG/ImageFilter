import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { invoke } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
import { setLanguage, type Lang } from "../i18n";
import { useLocalStorageNumber, useLocalStorageSetting } from "../hooks/use-local-storage-setting";
import { SettingsDialog } from "./settings-dialog";
import { HelpDialog } from "./help-dialog";
import { Setting, Sun, Moon, Close, Help } from "@icon-park/react";
import { Tip } from "./tip";

// ── 标题栏 ──────────────────────────────────
// 高度: h-9 (36px)  背景: bg-zinc-900  底部边框: border-zinc-800
// 拖拽: data-tauri-drag-region  禁止选中: select-none
export function TitleBar({
  preloadFull,
  onTogglePreloadFull,
  transparentBg,
  onToggleTransparentBg,
  backgroundOpacity,
  onBackgroundOpacityChange,
}: {
  preloadFull: boolean;
  onTogglePreloadFull: () => void;
  transparentBg: boolean;
  onToggleTransparentBg: () => void;
  backgroundOpacity: number;
  onBackgroundOpacityChange: (v: number) => void;
}) {
  const { t } = useTranslation();
  const win = getCurrentWindow();
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
      <span className="text-[11px] text-zinc-500 ml-3">ImageFilter</span>
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
        transparentBg={transparentBg}
        onToggleTransparentBg={onToggleTransparentBg}
        glassOpacity={glassOpacity}
        onGlassOpacityChange={setGlassOpacity}
        backgroundOpacity={backgroundOpacity}
        onBackgroundOpacityChange={onBackgroundOpacityChange}
      />

      <HelpDialog open={showHelp} onOpenChange={setShowHelp} />
    </div>
  );
}
