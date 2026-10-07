// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Moon, Sun } from "@icon-park/react";
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
// 取个别名: 同一个文件里 ServerPanel 的 `open` prop(对话框开着没有)会把它遮住。
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Toggle } from "@/components/ui/toggle";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Collapse } from "./collapse";
import type { Lang } from "../i18n";
import { xmpErrKey, type XmpMode, type XmpStatus } from "../xmp";
import { isLrcModeUsable, type LrcSendMode } from "../lightroom";
import { checkForUpdate, type UpdateDetail, type UpdateState } from "../updater";
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
  /** 系统不支持 Mica(Win10)时为真 */
  micaUnsupported: boolean;
  glassOpacity: number;
  onGlassOpacityChange: (v: number) => void;
  backgroundOpacity: number;
  onBackgroundOpacityChange: (v: number) => void;
}

/**
 * 图库服务(手机端)的状态快照 —— 与 `src-tauri/src/server/mod.rs` 的 `ServerStatus` 一一对应
 * (serde camelCase)。
 *
 * `enabled` 与 `running` 是**两件事**, 面板必须同时显示:
 *   · `enabled` = 用户的选择, 而且永远是"盘上此刻真正写着的那一份"(决定下次开机要不要拉起);
 *   · `running` = 此刻**真的**有 socket 在监听(端口被别的软件占着时它是 false);
 *   · `lastError` = 启动那一步失败的理由 —— 后端刻意**不**把开关回滚(Task 8), 所以
 *     "你要它开、它没在跑"是合法状态, 而界面必须把理由摆出来, 否则用户只会以为没点中。
 */
interface ServerStatus {
  running: boolean;
  port: number;
  url: string;
  token: string;
  shares: string[];
  enabled: boolean;
  lastError: string | null;
}

/**
 * 设置里的「图库服务」面板(Phase 8)。**全部长在设置对话框里** —— 不新增窗口、不动布局。
 *
 * 它只做四件事, 每件都对着一个用户真的会问的问题:
 *   ① 现在到底是开是关、**有没有真的在监听**(开 ≠ 在跑, 这两件事分开显示);
 *   ② 配对码: 看得见、能复制、能轮换(轮换两段确认 —— 它会让所有手机重新配对);
 *   ③ 共享目录的增删(空 = 服务对所有路径回"无权限", 这是有意的安全默认, 面板要说清);
 *   ④ 起不来时给出**可行动**的理由 + 一张排查清单。
 *
 * 纪律: 状态永远以后端为准(`get_server_status`) —— 每次动作之后再读一次, 面板开着时每 4 秒
 * 再读一次。前端**不**自己猜"应该开着了吧": `enabled`/`running`/`lastError` 三个字段合起来
 * 才是真相, 而真相只有 `server::status_of_state` 说得清。
 */
function ServerPanel({ open }: { open: boolean }) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<ServerStatus | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  /** 至少成功读到过一次状态 —— 决定一条失败信息该说"读不到状态"还是"这个操作失败了"。 */
  const [loadedOnce, setLoadedOnce] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [confirmRotate, setConfirmRotate] = useState(false);
  const [draftPath, setDraftPath] = useState("");
  const [showTips, setShowTips] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await invoke<ServerStatus>("get_server_status"));
      setProblem(null);
      setLoadedOnce(true);
    } catch (err) {
      // 拿不到状态就如实说拿不到(纯浏览器探针里没有 Tauri 注入, 这里必然走到) ——
      // 绝不能用一份猜测的状态把面板撑起来。
      setProblem(String(err));
    }
  }, []);

  useEffect(() => {
    if (!open) return; // 关着的时候不轮询: 一次 invoke 也不该白花
    void load();
    const id = window.setInterval(() => void load(), 4000);
    return () => window.clearInterval(id);
  }, [open, load]);

  /**
   * 所有动作共用同一条收尾: 忙 → 做 → 失败就显示后端那句人话 → **无论如何**重读一次权威状态。
   *
   * 重读是必需的, 不是保险: 开启失败时后端**刻意不回滚开关**(开关仍是"开"、服务没在跑、
   * 理由在 `lastError`), 只有重读才能让界面显示这个真实组合。
   */
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setProblem(null);
    try {
      await fn();
    } catch (err) {
      setProblem(String(err));
    } finally {
      setBusy(false);
      void load();
    }
  };

  const copyToken = async () => {
    const token = status?.token ?? "";
    if (!token) return;
    const done = () => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    };
    try {
      await navigator.clipboard.writeText(token);
      done();
    } catch {
      // WebView 里 clipboard API 可能不可用(权限/非安全上下文): 用"选中 + execCommand"兜底。
      // 为一次复制去引 clipboard 插件不值当。
      const ta = document.createElement("textarea");
      ta.value = token;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.top = "-1000px";
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch { ok = false; }
      ta.remove();
      if (ok) done();
      else setProblem(t("server.copyFailed"));
    }
  };

  const rotateToken = async () => {
    // 两段式确认: 轮换会让**所有**手机重新配对, 一次误点代价不小; 而 WebView 里
    // window.confirm 的行为不一致, 走对话框插件又要多一次 IPC —— 就地两段最稳、也最可读。
    if (!confirmRotate) {
      setConfirmRotate(true);
      window.setTimeout(() => setConfirmRotate(false), 5000);
      return;
    }
    setConfirmRotate(false);
    await run(() => invoke("rotate_server_token"));
  };

  const addShare = async () => {
    const path = draftPath.trim();
    if (!path) return;
    await run(async () => {
      await invoke<string[]>("add_share", { path });
      setDraftPath(""); // 只有成功才清空: 失败时留着输入, 用户改一改就能重试
    });
  };

  const removeShare = (path: string) => run(() => invoke<string[]>("remove_share", { path }));

  const browseForShare = async () => {
    try {
      const picked = await openDialog({ directory: true, title: t("server.sharesLabel") });
      if (typeof picked === "string" && picked) setDraftPath(picked);
    } catch (err) {
      setProblem(String(err));
    }
  };

  const token = status?.token ?? "";
  const shares = status?.shares ?? [];
  const disabled = busy || !status;

  // 状态行 —— 三句话各自对应一个真实状态, 不合并、不含糊:
  //   · 开着且在跑 → 把地址念出来(用户就是拿它填手机的);
  //   · 开着但没在跑 → 直说"你要它开, 它没在跑"(理由在下面的错误块里);
  //   · 没开 → 说"已关闭", 并**带上那句有界窗口的说明** —— "关"不等于"信号发出的一瞬间
  //     所有连接都断了"(serve 任务收敛前端口还能完成握手, 实测最坏 63ms)。
  const stateText = !status
    ? null
    : status.enabled && status.running
      ? t("server.listening", { url: status.url })
      : status.enabled
        ? t("server.wantedNotRunning")
        : t("server.offNote");

  return (
    <div className="min-w-0 border-t border-border pt-3 space-y-2">
      {/* 标题行: 状态点 + 标题 + 开/关胶囊 + 开关。开关是**用户的选择**, 胶囊是"这一份选择
          在盘上写着什么", 状态行是"此刻的真相" —— 三者一起才说清, 因为"开着但没在跑"正是最
          需要被看见的那个组合。 */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className={`inline-block size-2 rounded-full shrink-0 ${status?.running ? "bg-emerald-500" : "bg-muted-foreground/40"}`}
            />
            <p className="text-sm text-foreground">{t("server.title")}</p>
            <span className="text-xs text-muted-foreground">
              {status ? (status.enabled ? t("server.on") : t("server.off")) : ""}
            </span>
          </div>
          <p className="text-xs text-muted-foreground break-words">{stateText ?? t("server.switchDesc")}</p>
        </div>
        <Toggle
          checked={status?.enabled ?? false}
          disabled={disabled}
          onChange={() => void run(() => invoke("set_server_enabled", { enabled: !(status?.enabled ?? false) }))}
        />
      </div>

      {/* 安全警告: 左侧那条琥珀色竖线是"这是条警告"的唯一视觉信号 —— 文字色仍用
          muted-foreground(两个主题下都过对比度, 不为一句文案另造一个颜色)。 */}
      <p className="rounded-md border-l-2 border-amber-500/70 bg-muted/40 px-2 py-1.5 text-xs text-muted-foreground">
        {t("server.warn")}
      </p>

      {(problem || status?.lastError) && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 px-2 py-1.5 text-xs text-destructive space-y-0.5">
          {status?.lastError && (
            <p className="break-words">
              <span className="font-medium">{t("server.lastError")}: </span>
              {status.lastError}
            </p>
          )}
          {problem && (
            <p className="break-words">
              {loadedOnce
                ? t("server.actionFailed", { reason: problem })
                : t("server.unavailable", { reason: problem })}
            </p>
          )}
        </div>
      )}

      {/* 配对码: 单行等宽 + 可选中; 复制与轮换各一个按钮(轮换要按两下)。 */}
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">{t("server.tokenLabel")}</p>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={!token || busy}
              onClick={() => void copyToken()}
              className="px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {copied ? t("server.copied") : t("server.copy")}
            </button>
            <button
              type="button"
              disabled={!token || busy}
              onClick={() => void rotateToken()}
              className={`px-2 py-1 rounded-md border text-xs disabled:opacity-50 disabled:cursor-not-allowed ${
                confirmRotate
                  ? "border-destructive/50 text-destructive hover:bg-destructive/10"
                  : "border-border text-muted-foreground hover:bg-muted hover:text-foreground"
              }`}
            >
              {confirmRotate ? t("server.rotateConfirm") : t("server.rotate")}
            </button>
          </div>
        </div>
        <code className="block max-h-20 overflow-auto no-scrollbar rounded-md bg-muted/40 px-2 py-1 font-mono text-xs leading-4 text-foreground break-all select-all">
          {token || t("server.tokenEmpty")}
        </code>
        <p className="text-xs text-muted-foreground">{t("server.tokenDesc")}</p>
      </div>

      {/* 共享目录: 列表可删 + 一行"输入 / 浏览 / 添加"。空列表不是"没问题", 而是
          "谁都访问不了" —— 所以说的是 shareEmpty 那句话, 而不是一个空框。 */}
      <div className="space-y-1">
        <p className="text-xs text-muted-foreground">{t("server.sharesLabel")}</p>
        {shares.length === 0 ? (
          <p className="text-xs text-muted-foreground">{t("server.shareEmpty")}</p>
        ) : (
          <ul className="space-y-1 min-w-0">
            {shares.map((sh) => (
              <li key={sh} className="flex items-center gap-2 min-w-0">
                <span className="flex-1 min-w-0 truncate text-xs text-foreground" title={sh}>{sh}</span>
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`${t("server.remove")}: ${sh}`}
                  onClick={() => void removeShare(sh)}
                  className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {t("server.remove")}
                </button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex items-center gap-1.5">
          <Input
            value={draftPath}
            disabled={busy}
            placeholder={t("server.pathPlaceholder")}
            onChange={(e) => setDraftPath(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                void addShare();
              }
            }}
            className="h-7 flex-1 min-w-0 text-xs"
          />
          <button
            type="button"
            disabled={busy}
            onClick={() => void browseForShare()}
            className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("server.browse")}
          </button>
          <button
            type="button"
            disabled={disabled || !draftPath.trim()}
            onClick={() => void addShare()}
            className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("server.add")}
          </button>
        </div>
        <p className="text-xs text-muted-foreground">{t("server.sharesDesc")}</p>
      </div>

      {/* 排查清单默认收起: 它不是每天都看的东西, 但连不上的时候要在同一屏里能找到
          (所以标题一直在, 点开就是那三条)。 */}
      <div>
        <button
          type="button"
          aria-expanded={showTips}
          onClick={() => setShowTips((v) => !v)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <span aria-hidden="true">{showTips ? "▾" : "▸"}</span>
          {t("server.tipsTitle")}
        </button>
        <Collapse open={showTips} className="pt-1">
          <ul className="space-y-0.5 pl-4 text-xs text-muted-foreground list-disc">
            <li>{t("server.sleepTip")}</li>
            <li>{t("server.firewallTip")}</li>
            <li>{t("server.appRunningTip")}</li>
          </ul>
        </Collapse>
      </div>
    </div>
  );
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
  preloadFull, onTogglePreloadFull, transparentBg, onToggleTransparentBg, micaUnsupported,
  autoAdvance, onToggleAutoAdvance,
  labelModifier, onLabelModifierChange,
  xmpMode, onXmpModeChange, xmpStatus,
  lrcProbe, lrcMode, onLrcModeChange, onReprobeLightroom,
  glassOpacity, onGlassOpacityChange, backgroundOpacity, onBackgroundOpacityChange,
}: Props) {
  const { t } = useTranslation();

  // 版本号从**二进制**里读(getVersion), 不再写死在源码里 —— 原先那行字面量 "1.1.0"
  // 在 1.1.1 发布之后就变成了假话。__APP_VERSION__ 是构建期注入的兜底, 只为
  // 浏览器里(纯 vite / 探针)也能显示出版本, 真机上永远走 getVersion()。
  const [version, setVersion] = useState<string>(__APP_VERSION__);
  const [update, setUpdate] = useState<UpdateState>({ kind: "idle" });

  useEffect(() => {
    let alive = true;
    getVersion()
      .then((v) => { if (alive && v) setVersion(v); })
      .catch(() => { /* 拿不到就留兜底值, 不打扰用户 */ });
    return () => { alive = false; };
  }, []);

  function updateDetailText(d: UpdateDetail): string {
    switch (d.code) {
      case "network": return t("settings.updateErrNetwork");
      case "timeout": return t("settings.updateErrTimeout");
      case "http": return t("settings.updateErrHttp", { code: d.status });
      default: return t("settings.updateErrUnexpected");
    }
  }

  // 五种状态 → 同一个按钮的字面与悬停说明。
  // 刻意**不新增行、不换布局**: 结果替换按钮自己的字面, 这一行的几何和原来那行
  // 版本号逐像素相同 —— 否则设置面板会多出一条滚动条(见下方 max-h 的注释)。
  const { updateLabel, updateHint } = (() => {
    switch (update.kind) {
      case "checking":
        return { updateLabel: t("settings.checkingUpdate"), updateHint: undefined };
      case "latest":
        return { updateLabel: t("settings.updateLatest"), updateHint: t("settings.updateLatestHint") };
      case "available":
        return {
          updateLabel: t("settings.updateAvailable", { v: update.version }),
          updateHint: t("settings.updateAvailableHint"),
        };
      case "error":
        return {
          updateLabel: t("settings.updateFailed"),
          updateHint: t("settings.updateFailedHint", { reason: updateDetailText(update.detail) }),
        };
      default:
        return { updateLabel: t("settings.checkUpdate"), updateHint: t("settings.checkUpdateHint") };
    }
  })();

  const updateBusy = update.kind === "checking";

  // 有新版时这一下是"去下载页", 其余情况是"查一次"。
  // openUrl 走系统默认浏览器(不引第二个 http 权限, 也不在 webview 里开新窗口);
  // 兜底的 window.open 只在 non-Tauri 上下文(浏览器探针)里会被用到。
  const onUpdateClick = () => {
    if (updateBusy) return;
    if (update.kind === "available") {
      openUrl(update.url).catch(() => { window.open(update.url, "_blank", "noopener"); });
      return;
    }
    setUpdate({ kind: "checking" });
    checkForUpdate(version).then(setUpdate);
  };

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
        {/* min-w-0 **必须**在: DialogContent 是一张 grid, 而 grid item 的 `min-width` 默认是
            auto —— 于是这里面任何一个"不会换行的长串"(共享目录的完整路径就是)都会把 grid 轨道
            撑到超过对话框的 420px, 内容横向溢出(滚动条又被 no-scrollbar 藏掉), 右侧的"移除"
            按钮直接跑到框外。给它一个 min-w-0, 轨道就老老实实按 420px 算, 里面的 truncate /
            break-words 才真的生效。 */}
        <div className="min-w-0 space-y-2 py-2">
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

          {/* Win10 置灰: Mica 在 build < 22000 上不存在, 这个开关打开也没有效果。
              说明文案也一起换掉 —— 只置灰不解释, 用户会以为坏了。 */}
          <SettingRow
            title={t("settings.transparentBg")}
            desc={micaUnsupported ? t("settings.transparentBgUnsupported") : t("settings.transparentBgDesc")}
          >
            <Toggle checked={transparentBg} onChange={onToggleTransparentBg} disabled={micaUnsupported} />
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

          {/* Phase 8 · 图库服务(手机端)。放在最下面一行分隔线之上: 它是"整块"而不是一行设置,
              所以自带 border-t 与自己的小标题(不动 App.tsx 的布局 —— 面板全部长在这里)。 */}
          <ServerPanel open={open} />

          {/* 版本信息 + 检查更新。
              只比对版本号: 本机版本(二进制里的) vs GitHub 最新正式版的 tag。
              不下载、不静默安装 —— 有新版时按钮变成"去发布页", 剩下的交给浏览器。
              按钮 h-6(24px) 是点击目标下限, -my-1 把多出来的 8px 从行高里扣掉,
              所以这一行仍然只有 16px 高。 */}
          <div className="border-t border-border pt-3">
            <div className="flex items-center justify-center gap-1.5 pt-2 text-xs">
              <span className="text-muted-foreground">{t("settings.version", { v: version })}</span>
              <span aria-hidden="true" className="text-muted-foreground/40">·</span>
              {/* role="status" 包着按钮: 字面一变(检查中 → 已是最新 / 有新版本),
                  读屏会把新状态念出来。可视文案就是可访问名, 不再另加 aria-label
                  (WCAG 2.5.3 名字里要含可见文本)。 */}
              <span role="status">
                <Button
                  type="button"
                  variant="ghost"
                  size="xs"
                  onClick={onUpdateClick}
                  disabled={updateBusy}
                  title={updateHint}
                  aria-busy={updateBusy || undefined}
                  className={`-my-1 text-muted-foreground hover:text-foreground ${
                    update.kind === "available" ? "text-foreground font-medium underline underline-offset-2" : ""
                  }`}
                >
                  {updateLabel}
                </Button>
              </span>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
