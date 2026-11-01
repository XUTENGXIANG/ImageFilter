// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect, useLayoutEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  approveSync, denySync, onSyncRequest, syncStatus,
  SYNC_APPROVED_EVENT,
  type PendingSync,
} from "../lib/sync";

/**
 * 手机「同步」的电脑确认框（方案 A：电脑允许后手机才推拉）。
 *
 * 挂在应用根（App.tsx）而不是设置面板里：手机请求与用户此刻在看哪块设置无关。
 * 没有关闭按钮：一次确认必须有一个明确答案；放着不管，60 秒后它自己过期。
 * 默认焦点在「拒绝」上（与配对弹窗同一条）：按 Enter 的默认结果应该是"什么都没发出去"。
 *
 * 批准后会派发一个 **DOM 事件**（`SYNC_APPROVED_EVENT`），`useScanner` 据此在 2 分钟
 * 窗口内放行远端星级/标签的合并 —— 即使用户的 XMP 边车档位是"询问/关"。
 * 这是"电脑刚批准过这次同步"的显式同意，不是把全局 XMP 档位偷偷改掉。
 */
export function SyncDialogHost() {
  const { t } = useTranslation();
  const [current, setCurrent] = useState<PendingSync | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 订阅 + 挂载时补读：与配对弹窗同一条"先订阅、后补读"的顺序。
  useEffect(() => {
    let alive = true;
    let sawEvent = false;
    let off: (() => void) | undefined;
    void (async () => {
      try {
        const un = await onSyncRequest((pending) => {
          if (!alive) return;
          sawEvent = true;
          setCurrent(pending);
          setError(null);
        });
        if (alive) off = un;
        else un();
      } catch (err) {
        console.error("sync-request:", err);
      }
      if (!alive || sawEvent) return;
      try {
        const pending = await syncStatus();
        if (alive && !sawEvent) setCurrent(pending);
      } catch {
        /* 读不到权威状态时保持空；后续事件仍会填上 */
      }
    })();
    return () => {
      alive = false;
      off?.();
    };
  }, []);

  // 有请求时每秒对一次表；没有请求就不挂空转 interval。
  useLayoutEffect(() => {
    if (!current) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [current?.requestId]);

  // 过期自动收起：与服务端 60 秒 TTL 对齐，不把一条已经无效的请求留在屏幕上。
  useEffect(() => {
    if (current && now >= current.expiresAt) {
      setCurrent(null);
      setError(null);
    }
  }, [current, now]);

  const resolve = async (allow: boolean) => {
    if (!current) return;
    setBusy(true);
    setError(null);
    try {
      if (allow) {
        await approveSync(current.requestId);
        // 只在这一刻放行远端合并；拒绝时一个字节都不合。
        window.dispatchEvent(new CustomEvent(SYNC_APPROVED_EVENT));
      } else {
        await denySync(current.requestId);
      }
      setCurrent(null);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  if (!current) return null;

  const remainingSeconds = Math.max(
    0,
    Math.ceil((current.expiresAt - now) / 1000),
  );

  return (
    <Dialog open onOpenChange={() => {}}>
      <DialogContent className="w-[420px]" showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{t("sync.requestTitle")}</DialogTitle>
          <DialogDescription>{t("sync.requestBody")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          <div className="space-y-0.5 rounded-2xl border border-border bg-muted/40 px-2 py-1.5">
            <p className="text-sm text-foreground break-words">
              {t("sync.device", { name: current.deviceName })}
            </p>
            <p className="text-xs text-muted-foreground">
              {t("sync.items", { n: current.pendingItems })}
            </p>
            <p className="text-xs text-muted-foreground">
              {remainingSeconds > 0
                ? t("sync.expiresIn", { seconds: remainingSeconds })
                : t("sync.expired")}
            </p>
          </div>

          {error && (
            <p className="text-xs text-destructive break-words">
              {t("sync.actionFailed", { reason: error })}
            </p>
          )}
        </div>

        <DialogFooter>
          <button
            type="button"
            autoFocus
            disabled={busy}
            onClick={() => void resolve(false)}
            className="px-3 py-1.5 rounded-2xl border border-border text-sm hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("sync.deny")}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void resolve(true)}
            className="px-3 py-1.5 rounded-2xl bg-brand-strong hover:bg-brand text-white text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("sync.allow")}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
