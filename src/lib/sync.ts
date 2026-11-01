// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { invoke } from "@tauri-apps/api/core";

/**
 * 手机「同步」确认门的前端封装。
 *
 * 这条链路与配对一样分三步，**手机不等到 approved 一个写请求都不发**：
 *   1. 手机 `POST /api/v1/sync/request` → 桌面端弹窗；
 *   2. 用户点「允许 / 拒绝」→ `approve_sync` / `deny_sync`；
 *   3. 手机轮询 `/api/v1/sync/status`。
 *
 * 桌面端只负责第 2 步；请求本身由服务端 `sync-request` 事件送过来。
 * 事件订阅走动态 import（与 pairing / decisions-changed 同一条纪律）：
 * 浏览器探针里没有 Tauri 注入，订阅会 reject，那是预期，不是崩溃。
 */

/** 一条待确认的同步请求。字段与 Rust `sync::PendingSync` 逐字一致。 */
export interface PendingSync {
  requestId: string;
  deviceName: string;
  /** 手机认为有多少项要写回电脑（只是给用户看的量级）。 */
  pendingItems: number;
  /** unix 毫秒。 */
  requestedAt: number;
  /** unix 毫秒。 */
  expiresAt: number;
}

export type SyncUnlisten = () => void;

/** 桌面端批准后由弹窗派发的 DOM 事件：`useScanner` 用它放行这次同步的远端星级/标签合并。 */
export const SYNC_APPROVED_EVENT = "imagefilter-sync-approved";

/** 批准后放行远端合并的窗口：手机写多张照片可能持续几秒，给 2 分钟足够。 */
export const SYNC_APPROVED_WINDOW_MS = 2 * 60_000;

/** `sync_status()` —— 弹窗挂载时补读一次还活着的待确认请求。 */
export function syncStatus(): Promise<PendingSync | null> {
  return invoke<PendingSync | null>("sync_status");
}

/** `approve_sync(requestId)` —— 允许；手机轮询到 approved 后才开始推拉。 */
export function approveSync(requestId: string): Promise<void> {
  return invoke<void>("approve_sync", { requestId });
}

/** `deny_sync(requestId)` —— 拒绝；手机一个写请求都不会发。 */
export function denySync(requestId: string): Promise<void> {
  return invoke<void>("deny_sync", { requestId });
}

/** 订阅 `sync-request`。每条新请求触发一次，负载就是那一行 `PendingSync`。 */
export async function onSyncRequest(
  handler: (pending: PendingSync) => void,
): Promise<SyncUnlisten> {
  const { listen } = await import("@tauri-apps/api/event");
  return listen<PendingSync>("sync-request", (event) => handler(event.payload));
}
