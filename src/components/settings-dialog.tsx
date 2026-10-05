import { useTranslation } from "react-i18next";
import { Moon, Sun } from "@icon-park/react";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Toggle } from "@/components/ui/toggle";
import type { Lang } from "../i18n";
import { xmpErrKey, type XmpMode, type XmpStatus } from "../xmp";
import { isLrcModeUsable, type LrcSendMode } from "../lightroom";
import type { LightroomProbe } from "../types";

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
  /** Phase 5: XMP 边车三档开关 + 常驻状态行(见 docs §5.2/§5.3) */
  xmpMode: XmpMode;
  onXmpModeChange: (m: XmpMode) => void;
  xmpStatus: XmpStatus | null;
  /** Phase 7: Lightroom 衔接(探测结果 / 发送模式 / 重新检测) */
  lrcProbe: LightroomProbe | null;
  lrcMode: LrcSendMode;
  onLrcModeChange: (m: LrcSendMode) => void;
  onReprobeLightroom: () => void;
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
  xmpMode, onXmpModeChange, xmpStatus,
  lrcProbe, lrcMode, onLrcModeChange, onReprobeLightroom,
  glassOpacity, onGlassOpacityChange, backgroundOpacity, onBackgroundOpacityChange,
}: Props) {
  const { t } = useTranslation();

  // 边车状态: 拼进上面那一行的 desc, **不新增整行** —— 加行要让对话框重新算总高(会话 ③ 第 28 项)。
  // 注意顺序: "不可写"的判断必须排在档位判断**之前** —— 自动降级会把档位改回 off,
  // 若先看档位, 用户就再也看不到"为什么被降级"了。
  const xmpStatusText = (() => {
    if (xmpStatus && !xmpStatus.writable) {
      return t("settings.xmpStatusUnwritable", {
        dir: xmpStatus.dir,
        reason: t(`xmp.err.${xmpErrKey(xmpStatus.code)}`),
      });
    }
    if (xmpMode !== "on") return t("settings.xmpStatusOff");
    if (!xmpStatus) return "";
    return t("settings.xmpStatusWritable", { dir: xmpStatus.dir }) + (xmpStatus.degraded ? t("settings.xmpStatusDegraded") : "");
  })();

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
        {/* Phase 7 加了第 10 行(Lightroom), 而那一段又比其他行高一档(三档按钮 + 重新检测)。
            为守住"一屏放下、不出现滚动条"这条前置约束, 行距 3→2 并同步把这一行的
            py-1.5 收到 py-1(见该行): 两处必须一起改, 只收其中一处仍会超。 */}
        <div className="space-y-2 py-2">
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

          {/* Phase 5: XMP 边车三档。默认"询问"(会话 ⑥ 由"关闭"改来, 见 src/xmp.ts::DEFAULT_XMP_MODE) */}
          <SettingRow
            title={t("settings.xmpMode")}
            desc={`${t("settings.xmpModeDesc")}${xmpStatusText ? ` · ${xmpStatusText}` : ""}`}
          >
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {([
                ["off", t("settings.xmpModeOff")],
                ["ask", t("settings.xmpModeAsk")],
                ["on", t("settings.xmpModeOn")],
              ] as [XmpMode, string][]).map(([m, label]) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => onXmpModeChange(m)}
                  className={`px-3 py-1.5 transition-colors ${xmpMode === m ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {label}
                </button>
              ))}
            </div>
          </SettingRow>

          {/* Phase 7: Lightroom 衔接。三段式与上面的 XMP 行同一套写法。
              探不到 LrC → 整行显示"未检测到"并给一个"重新检测"(用户可能刚装上)。
              "静默导入"这一档**刻意禁用**: 它需要用户先在 LrC 里配好自动导入的监听
              文件夹(本机实测从未配过), 现在给个能点但会报错的选项是骗人。 */}
          <SettingRow
            title={t("settings.lrcMode")}
            desc={(() => {
              if (!lrcProbe) return t("settings.lrcProbing");
              if (!lrcProbe.found) return t("settings.lrcNotFound");
              const where = lrcProbe.running ? t("settings.lrcRunning") : t("settings.lrcNotRunning");
              return `${lrcProbe.exe ?? ""} · ${where}`;
            })()}
          >
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {([
                ["dialog", t("settings.lrcModeDialog")],
                ["silent", t("settings.lrcModeSilent")],
              ] as [LrcSendMode, string][]).map(([m, label]) => {
                const blocked = !isLrcModeUsable(m);
                return (
                  <button
                    key={m}
                    type="button"
                    disabled={blocked}
                    title={blocked ? t("settings.lrcModeSilentWhy") : undefined}
                    onClick={() => onLrcModeChange(m)}
                    className={`px-3 py-1 transition-colors ${
                      blocked
                        ? "text-muted-foreground/40 cursor-not-allowed"
                        : lrcMode === m
                          ? "bg-foreground text-background"
                          : "hover:bg-muted text-muted-foreground"
                    }`}
                  >
                    {label}
                  </button>
                );
              })}
              <button
                type="button"
                onClick={onReprobeLightroom}
                className="px-2 py-1 border-l border-border hover:bg-muted text-muted-foreground"
              >
                {t("settings.lrcReprobe")}
              </button>
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
              {t("settings.version", { v: "1.1.0" })}
            </p>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
