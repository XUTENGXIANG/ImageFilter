import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { invoke } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
import { setLanguage, type Lang } from "../i18n";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Setting, Sun, Moon, Close, Help } from "@icon-park/react";
import { Step } from "./step";
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
  const [theme, setTheme] = useState<"dark" | "light">(() =>
    (localStorage.getItem("imagefilter-theme") as "dark" | "light") || "light"
  );
  const [lang, setLang] = useState<Lang>(() =>
    (localStorage.getItem("imagefilter-lang") as Lang) || "zh"
  );
  const [glassOpacity, setGlassOpacity] = useState<number>(() => {
    const v = Number(localStorage.getItem("imagefilter-glass-opacity"));
    return Number.isFinite(v) ? Math.min(100, Math.max(0, v)) : 70;
  });
  useEffect(() => {
    const root = document.documentElement;
    if (theme === "dark") {
      root.classList.add("dark");
      root.setAttribute("data-theme", "dark");
    } else {
      root.classList.remove("dark");
      root.setAttribute("data-theme", "light");
    }
    localStorage.setItem("imagefilter-theme", theme);
  }, [theme]);

  useEffect(() => {
    document.documentElement.style.setProperty("--glass-opacity", `${glassOpacity}%`);
    localStorage.setItem("imagefilter-glass-opacity", String(glassOpacity));
  }, [glassOpacity]);

  // 一律使用 Mica(失焦时系统自动回退为纯色), 仅在开关/主题变化时应用
  useEffect(() => {
    invoke("set_glass_bg", {
      enabled: transparentBg,
      dark: theme === "dark",
    }).catch(() => {});
  }, [transparentBg, theme]);

  return (
    <div
      data-tauri-drag-region
      className={`h-9 flex items-center justify-between px-1 select-none flex-shrink-0 transition-colors duration-200 ${transparentBg ? "" : "bg-zinc-900"}`}
      style={transparentBg ? { backgroundColor: "var(--glass-bg)" } : undefined}
    >
      <span className="text-[11px] text-zinc-500 ml-3">ImageFilter</span>
      {/* ── 标题栏按钮组 ─────────────────────────────
          每个按钮的尺寸调整方法:
            - 宽度:  改 w-8(32px) / w-10(40px) → w-9(36px)、w-12(48px) 等任意值
            - 高度:  按钮 h-full 自动跟随标题栏; 改容器 h-9(36px) → h-10(40px) 整体变高
            - 圆角:  rounded(4px) → rounded-md(6px) / rounded-lg(8px) / rounded-full(胶囊/圆形)
            - 悬停背景: hover:bg-zinc-800(灰色) → hover:bg-red-400/10(红, 关闭按钮示例)
          每个按钮外层由 <Tip> 包裹提供圆角提示框, 提示文字 = label 属性
      ───────────────────────────────────────────── */}
      <div className="flex items-center h-7.5">
        <Tip label={t("titlebar.help")} className="h-full flex items-center">
        <button onClick={() => setShowHelp(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          <Help theme="filled" size="15" strokeWidth={3} />
        </button>
        </Tip>
        <Tip label={t("titlebar.settings")} className="h-full flex items-center">
        <button onClick={() => setShowSettings(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          <Setting theme="filled" size="15" strokeWidth={3} />
        </button>
        </Tip>
        <Tip label={theme === "dark" ? t("titlebar.themeLight") : t("titlebar.themeDark")} className="h-full flex items-center">
        <button onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md"
        >
          {theme === "dark" ? <Sun theme="filled" size="15" strokeWidth={3} /> : <Moon theme="filled" size="15" strokeWidth={3} />}
        </button>
        </Tip>
        <Tip label={t("titlebar.min")} className="h-full flex items-center">
        <button onClick={() => win.minimize()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md">
          <svg width="10" height="1"><rect width="10" height="1" fill="currentColor"/></svg>
        </button>
        </Tip>
        <Tip label={t("titlebar.max")} className="h-full flex items-center">
        <button onClick={() => win.toggleMaximize()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-md">
          <svg width="10" height="10" viewBox="0 0 10 10">
            <rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor" strokeWidth="1"/>
          </svg>
        </button>
        </Tip>
        <Tip label={t("titlebar.close")} className="h-full flex items-center">
        <button onClick={() => win.close()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-red-400 hover:bg-red-400/10 rounded-md">
          <Close theme="filled" size="14" strokeWidth={4} />
        </button>
        </Tip>
      </div>

      <Dialog open={showSettings} onOpenChange={setShowSettings}>
        <DialogContent className="w-[420px] max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle>{t("settings.title")}</DialogTitle>
            <DialogDescription>{t("settings.subtitle")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-foreground">{t("settings.language")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.languageDesc")}</p>
              </div>
              <div className="flex rounded-md border border-border overflow-hidden text-sm">
                {(["zh", "en"] as Lang[]).map((l) => (
                  <button
                    key={l}
                    onClick={() => { setLang(l); setLanguage(l); }}
                    className={`px-3 py-1.5 transition-colors ${lang === l ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                  >
                    {l === "zh" ? "中文" : "EN"}
                  </button>
                ))}
              </div>
            </div>
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-foreground">{t("settings.theme")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.themeDesc")}</p>
              </div>
              <button
                onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted"
              >
                {theme === "dark" ? `🌙 ${t("settings.dark")}` : `☀️ ${t("settings.light")}`}
              </button>
            </div>
            <div className="flex items-start justify-between gap-3">
              <div className="flex-1 min-w-0">
                <p className="text-sm text-foreground">{t("settings.preload")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.preloadDesc")}</p>
              </div>
              <button
                onClick={onTogglePreloadFull}
                className={`mt-0.5 w-10 h-6 rounded-full relative shrink-0 transition-colors ${preloadFull ? "bg-emerald-500" : "bg-muted"}`}
              >
                <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-all ${preloadFull ? "left-[18px]" : "left-0.5"}`} />
              </button>
            </div>
            <div className="flex items-start justify-between gap-3">
              <div className="flex-1 min-w-0">
                <p className="text-sm text-foreground">{t("settings.transparentBg")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.transparentBgDesc")}</p>
              </div>
              <button
                onClick={onToggleTransparentBg}
                className={`mt-0.5 w-10 h-6 rounded-full relative shrink-0 transition-colors ${transparentBg ? "bg-emerald-500" : "bg-muted"}`}
              >
                <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-all ${transparentBg ? "left-[18px]" : "left-0.5"}`} />
              </button>
            </div>
            <div className={`flex items-start justify-between gap-3 ${transparentBg ? "" : "opacity-50"}`}>
              <div className="flex-1 min-w-0">
                <p className="text-sm text-foreground">{t("settings.transparentBgOpacity")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.transparentBgOpacityDesc")}</p>
              </div>
              <div className="w-40 flex items-center gap-2 shrink-0">
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={glassOpacity}
                  disabled={!transparentBg}
                  onChange={(e) => setGlassOpacity(Number(e.target.value))}
                  className="flex-1 thumb-slider"
                />
                <span className="text-xs text-muted-foreground w-8 text-right">{glassOpacity}%</span>
              </div>
            </div>
            <div className={`flex items-start justify-between gap-3 ${transparentBg ? "" : "opacity-50"}`}>
              <div className="flex-1 min-w-0">
                <p className="text-sm text-foreground">{t("settings.backgroundOpacity")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.backgroundOpacityDesc")}</p>
              </div>
              <div className="w-40 flex items-center gap-2 shrink-0">
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={backgroundOpacity}
                  disabled={!transparentBg}
                  onChange={(e) => onBackgroundOpacityChange(Number(e.target.value))}
                  className="flex-1 thumb-slider"
                />
                <span className="text-xs text-muted-foreground w-8 text-right">{backgroundOpacity}%</span>
              </div>
            </div>
            {/* 版本信息 */}
            <div className="border-t border-border pt-3">
              <p className="text-xs text-muted-foreground text-center pt-2">
                {t("settings.version", { v: "1.0.0" })}
              </p>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={showHelp} onOpenChange={setShowHelp}>
        <DialogContent className="w-[480px] max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle>{t("help.title")}</DialogTitle>
            <DialogDescription>{t("help.subtitle")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2 text-sm">
            <div className="space-y-2">
              <Step num="1" title={t("help.step1Title")} desc={t("help.step1Desc")} />
              <Step num="2" title={t("help.step2Title")} desc={t("help.step2Desc")} />
              <Step num="3" title={t("help.step3Title")} desc={t("help.step3Desc")} />
              <Step num="4" title={t("help.step4Title")} desc={t("help.step4Desc")} />
            </div>
            <div className="border-t border-border pt-3">
              <p className="text-xs font-medium text-foreground mb-2">{t("help.shortcuts")}</p>
              <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
                <span><kbd className="px-1 bg-muted rounded">J</kbd> {t("help.keep")}</span>
                <span><kbd className="px-1 bg-muted rounded">X</kbd> {t("help.trash")}</span>
                <span><kbd className="px-1 bg-muted rounded">1-5</kbd> {t("help.star")}</span>
                <span><kbd className="px-1 bg-muted rounded">R</kbd> {t("help.rotate")}</span>
                <span><kbd className="px-1 bg-muted rounded">←→</kbd> {t("help.nav")}</span>
                <span><kbd className="px-1 bg-muted rounded">0</kbd> {t("help.reset")}</span>
                <span><kbd className="px-1 bg-muted rounded">空格</kbd> {t("help.select")}</span>
                <span><kbd className="px-1 bg-muted rounded">Ctrl+点击</kbd> {t("help.multi")}</span>
                <span><kbd className="px-1 bg-muted rounded">Shift+点击</kbd> {t("help.range")}</span>
              </div>
            </div>
            <div className="border-t border-border pt-3">
              <p className="text-xs font-medium text-foreground mb-2">{t("help.contextMenu")}</p>
              <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
                <span>{t("help.ctxPhoto")}</span>
                <span>{t("help.ctxEmpty")}</span>
                <span>{t("help.ctxDevice")}</span>
                <span>{t("help.ctxFolder")}</span>
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
