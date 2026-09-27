import { useTranslation } from "react-i18next";
import { Moon, Sun } from "@icon-park/react";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Toggle } from "@/components/ui/toggle";
import type { Lang } from "../i18n";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  theme: "dark" | "light";
  onThemeChange: (t: "dark" | "light") => void;
  lang: Lang;
  onLangChange: (l: Lang) => void;
  preloadFull: boolean;
  onTogglePreloadFull: () => void;
  autoAdvance: boolean;
  onToggleAutoAdvance: () => void;
  /** Phase 4: 颜色标签用哪个修饰键(Ctrl / Alt)。见 docs 4.1 */
  labelModifier: "ctrl" | "alt";
  onLabelModifierChange: (v: "ctrl" | "alt") => void;
  transparentBg: boolean;
  onToggleTransparentBg: () => void;
  glassOpacity: number;
  onGlassOpacityChange: (v: number) => void;
  backgroundOpacity: number;
  onBackgroundOpacityChange: (v: number) => void;
}

/** 设置项行: 标题 + 说明 + 右侧控件 */
function SettingRow({
  title, desc, children, dimmed = false,
}: {
  title: string; desc: string; children: React.ReactNode; dimmed?: boolean;
}) {
  return (
    <div className={`flex items-start justify-between gap-3 ${dimmed ? "opacity-50" : ""}`}>
      <div className="flex-1 min-w-0">
        <p className="text-sm text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{desc}</p>
      </div>
      <div className="flex items-center gap-2 shrink-0">{children}</div>
    </div>
  );
}

/** 透明度滑块 */
function OpacitySlider({
  value, onChange, disabled,
}: {
  value: number; onChange: (v: number) => void; disabled?: boolean;
}) {
  return (
    <>
      <input
        type="range" min={0} max={100} value={value} disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="flex-1 thumb-slider"
      />
      <span className="text-xs text-muted-foreground w-8 text-right">{value}%</span>
    </>
  );
}

export function SettingsDialog({
  open, onOpenChange, theme, onThemeChange, lang, onLangChange,
  preloadFull, onTogglePreloadFull, transparentBg, onToggleTransparentBg,
  autoAdvance, onToggleAutoAdvance,
  labelModifier, onLabelModifierChange,
  glassOpacity, onGlassOpacityChange, backgroundOpacity, onBackgroundOpacityChange,
}: Props) {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* max-h 从 80vh 抬到 88vh + 行距 4→3: 让 9 行设置尽量一屏放下(会话 ③ 加了
          "颜色标签快捷键"那一行后, 内容超过 80vh → 右侧滚动条回来了)。
          no-scrollbar 兜底: 万一窗口太矮还是要滚, 滚轮/触控板照旧可用, 但不显示侧边滚动条。 */}
      <DialogContent className="w-[420px] max-h-[88vh] overflow-auto no-scrollbar">
        <DialogHeader>
          <DialogTitle>{t("settings.title")}</DialogTitle>
          <DialogDescription>{t("settings.subtitle")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <SettingRow title={t("settings.language")} desc={t("settings.languageDesc")}>
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {(["zh", "en"] as Lang[]).map((l) => (
                <button
                  key={l}
                  type="button"
                  onClick={() => onLangChange(l)}
                  className={`px-3 py-1.5 transition-colors ${lang === l ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {l === "zh" ? "中文" : "EN"}
                </button>
              ))}
            </div>
          </SettingRow>

          <SettingRow title={t("settings.theme")} desc={t("settings.themeDesc")}>
            <button
              type="button"
              onClick={() => onThemeChange(theme === "dark" ? "light" : "dark")}
              className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted flex items-center gap-1.5"
            >
              {theme === "dark"
                ? <Moon theme="filled" size="15" strokeWidth={3} />
                : <Sun theme="filled" size="15" strokeWidth={3} />}
              {theme === "dark" ? t("settings.dark") : t("settings.light")}
            </button>
          </SettingRow>

          <SettingRow title={t("settings.preload")} desc={t("settings.preloadDesc")}>
            <Toggle checked={preloadFull} onChange={onTogglePreloadFull} />
          </SettingRow>

          <SettingRow title={t("settings.autoAdvance")} desc={t("settings.autoAdvanceDesc")}>
            <Toggle checked={autoAdvance} onChange={onToggleAutoAdvance} />
          </SettingRow>

          {/* Phase 4: 颜色标签的修饰键。分段按钮与上面的"语言"同一套写法。
              之所以让用户选: Tauri 的 WebView2 可能把 Ctrl+数字当浏览器加速键吃掉 */}
          <SettingRow title={t("settings.labelKeys")} desc={t("settings.labelKeysDesc")}>
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {(["ctrl", "alt"] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => onLabelModifierChange(m)}
                  className={`px-3 py-1.5 transition-colors ${labelModifier === m ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {m === "ctrl" ? "Ctrl" : "Alt"}
                </button>
              ))}
            </div>
          </SettingRow>

          <SettingRow title={t("settings.transparentBg")} desc={t("settings.transparentBgDesc")}>
            <Toggle checked={transparentBg} onChange={onToggleTransparentBg} />
          </SettingRow>

          <SettingRow title={t("settings.transparentBgOpacity")} desc={t("settings.transparentBgOpacityDesc")} dimmed={!transparentBg}>
            <div className="w-40 flex items-center gap-2">
              <OpacitySlider value={glassOpacity} onChange={onGlassOpacityChange} disabled={!transparentBg} />
            </div>
          </SettingRow>

          <SettingRow title={t("settings.backgroundOpacity")} desc={t("settings.backgroundOpacityDesc")} dimmed={!transparentBg}>
            <div className="w-40 flex items-center gap-2">
              <OpacitySlider value={backgroundOpacity} onChange={onBackgroundOpacityChange} disabled={!transparentBg} />
            </div>
          </SettingRow>

          {/* 版本信息 */}
          <div className="border-t border-border pt-3">
            <p className="text-xs text-muted-foreground text-center pt-2">
              {t("settings.version", { v: "1.0.1" })}
            </p>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
