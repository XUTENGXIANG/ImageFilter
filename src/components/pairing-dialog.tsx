// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect, useLayoutEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  approvePairing,
  denyPairing,
  onPairingChanged,
  onPairingRequest,
  pairingStatus,
  PAIRING_MAX_PENDING,
  PAIRING_REQUEST_TTL_MS,
  type PairingStatus,
  type PendingPairing,
} from "../lib/pairing";

/**
 * 全局配对确认框(计划 2026-10-25 Task B2)。
 *
 * 为什么挂在**应用根**(App.tsx)而不是设置面板里: 手机的请求与用户此刻在看哪块设置
 * 毫无关系 —— 挂在面板里, 面板没开就永远看不到请求, 手机会一直等到 60 秒过期。
 *
 * 队列规则: 最多同时排队 3 条(与后端"最多 3 个待确认"同一条约束), 一次显示队首一条,
 * 先到先处理, 处理完自动露出下一条。
 * 每一条的关闭条件只有三种: 用户点「允许」/「拒绝」, 或请求过期(§0.2: 60 秒)。
 * 因此这个对话框没有关闭按钮, Esc / 点背景也关不掉 —— 一次安全确认必须有一个明确的
 * 答案; 放着不管, 60 秒后它自己会消失。这里**不**把"关掉"偷偷当成"拒绝"。
 *
 * 状态来源有两份, 刻意互相校准:
 *   ① 挂载时先读一次 `pairing_status`(组件可能比请求晚挂载, 事件不能补发);
 *   ② `pairing-request` 追加单条, `pairing-changed` 用整份快照覆盖(批准/拒绝/过期/移除)。
 * `pairing-changed` 里出现而我们队列里没有的请求也会被补上 —— 后端永远比前端新。
 */
export function PairingDialogHost() {
  const { t } = useTranslation();
  const [queue, setQueue] = useState<PendingPairing[]>([]);
  /** 每秒走一次的"现在": 只为了让过期倒计时与自动收起是真的。 */
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ── 订阅后端事件 + 播种 ─────────────────────────────────────────────
  // 与 useScanner 的 decisions-changed 同一套卸载纪律: await 期间组件可能已经卸载,
  // 那时拿到退订函数要立刻调用, 而不是把监听器漏在事件总线上。
  //
  // 顺序是**先订阅、后补读快照**: `pairing-request` 是"新请求"的唯一通知, 它不会因为
  // 一次 `pairing_status` 而重发 —— 若先读快照再订阅, 恰好落在这两步之间的请求就会
  // 永远不出现(直到 60 秒后过期)。快照只在**一个事件都还没收到**时用来补历史;
  // 一旦事件先到了, 快照就是旧的, 不能用它盖掉新状态。
  useEffect(() => {
    let alive = true;
    let sawEvent = false;
    let offRequest: (() => void) | undefined;
    let offChanged: (() => void) | undefined;

    const apply = (status: PairingStatus) => {
      if (!alive) return;
      sawEvent = true;
      setQueue(status.open ? status.pending.slice(0, PAIRING_MAX_PENDING) : []);
    };

    void (async () => {
      try {
        const off = await onPairingRequest((pending) => {
          if (!alive) return;
          sawEvent = true;
          setQueue((prev) => {
            if (prev.some((p) => p.requestId === pending.requestId)) return prev;
            // 满 3 条之后到达的请求直接不排队: 后端此刻也会拒(最多 3 个待确认),
            // 这里只是不把一条已经注定失败的行摆出来。
            return [...prev, pending].slice(0, PAIRING_MAX_PENDING);
          });
        });
        if (alive) offRequest = off;
        else off();
      } catch (err) {
        console.error("pairing-request:", err);
      }
      try {
        const off = await onPairingChanged(apply);
        if (alive) offChanged = off;
        else off();
      } catch (err) {
        console.error("pairing-changed:", err);
      }
      // 组件挂载得比请求晚时(例如后端先起、窗口后开), 事件不会补发 —— 补读一次。
      if (!alive || sawEvent) return;
      try {
        const status = await pairingStatus();
        if (alive && !sawEvent) apply(status);
      } catch {
        /* 读不到权威状态时保持空队列; 后续事件仍会把它填上 */
      }
    })();

    return () => {
      alive = false;
      offRequest?.();
      offChanged?.();
    };
  }, []);

  const current = queue[0] ?? null;

  /**
   * 有请求在排队时每秒对一次表 —— 没有请求就不该有一个空转的 interval。
   *
   * 这里是 `useLayoutEffect` 而不是 `useEffect`: 第一条请求出现的那个渲染, `now`
   * 还停留在应用启动时刻 —— 用它在 paint 前算出的剩余秒数会是 0, 用户会看到一帧
   * "请求已过期"然后才跳回 60 秒。layout effect 在 paint 前把表对好, 这一帧就不会出现。
   */
  useLayoutEffect(() => {
    if (!current) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [current?.requestId]);

  /**
   * 过期就移出队列。判据用后端发来的 `requestedAt`(同一台机器, 不受手机时钟影响),
   * 而不是本地"弹窗打开的时刻" —— 后者会把一条早该过期的请求重新续命 60 秒。
   */
  useEffect(() => {
    if (queue.length === 0) return;
    setQueue((prev) => {
      const next = prev.filter((p) => p.requestedAt + PAIRING_REQUEST_TTL_MS > Date.now());
      return next.length === prev.length ? prev : next;
    });
  }, [now, queue.length]);

  /** 换到下一条请求时, 上一条的错误不应挂在新请求头上。 */
  useEffect(() => {
    setError(null);
  }, [current?.requestId]);

  const remainingSeconds = current
    ? Math.max(0, Math.ceil((current.requestedAt + PAIRING_REQUEST_TTL_MS - now) / 1000))
    : 0;

  const resolve = async (allow: boolean) => {
    if (!current) return;
    setBusy(true);
    setError(null);
    try {
      if (allow) await approvePairing(current.requestId);
      else await denyPairing(current.requestId);
      // 先把这一条移出队列(不依赖事件送达); `pairing-changed` 到了会再校准一次。
      setQueue((prev) => prev.filter((p) => p.requestId !== current.requestId));
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  // 没有请求时连 Dialog 都不挂: Base UI 的关闭动画在这里没有意义, 反倒会让
  // "对话框是否开着"多出一份需要同步的状态。
  if (!current) return null;

  return (
    <Dialog
      open
      // 只能由允许 / 拒绝 / 过期关闭 —— 关闭请求一律忽略。
      onOpenChange={() => {}}
    >
      {/* showCloseButton={false}: 右上角那个 × 会让人以为"关掉"是一个答案。 */}
      <DialogContent className="w-[420px]" showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{t("pairing.requestTitle")}</DialogTitle>
          <DialogDescription>{t("pairing.requestBody")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          <div className="space-y-0.5 rounded-2xl border border-border bg-muted/40 px-2 py-1.5">
            <p className="text-sm text-foreground break-words">
              {t("pairing.deviceOn", { name: current.deviceName, ip: current.ip })}
            </p>
            <p className="text-xs text-muted-foreground">
              {remainingSeconds > 0
                ? t("pairing.expiresIn", { seconds: remainingSeconds })
                : t("pairing.expired")}
            </p>
            <p className="text-xs text-muted-foreground">
              {t("pairing.requestedAt", {
                time: new Date(current.requestedAt).toLocaleTimeString(),
              })}
            </p>
          </div>

          {queue.length > 1 && (
            <p className="text-xs text-muted-foreground">
              {t("pairing.morePending", { n: queue.length - 1 })}
            </p>
          )}

          {error && (
            <p className="text-xs text-destructive break-words">
              {t("pairing.actionFailed", { reason: error })}
            </p>
          )}
        </div>

        <DialogFooter>
          {/* autoFocus 落在「拒绝」上: 用户按 Enter 的默认结果应该是"什么都没发出去"。 */}
          <button
            type="button"
            autoFocus
            disabled={busy}
            onClick={() => void resolve(false)}
            className="px-3 py-1.5 rounded-2xl border border-border text-sm hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("pairing.deny")}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void resolve(true)}
            className="px-3 py-1.5 rounded-2xl bg-brand-strong hover:bg-brand text-white text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("pairing.allow")}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
