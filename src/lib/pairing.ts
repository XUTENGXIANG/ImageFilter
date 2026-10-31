// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { invoke } from "@tauri-apps/api/core";

/**
 * 桌面端 mDNS 配对的前端封装 —— 与计划 2026-10-25 的 §0.3 **冻结契约**一一对应,
 * 命令名 / 事件名 / 字段名都不许在这里自行改名(后端、安卓、测试都按同一份写)。
 *
 * 所有命令的返回形状都是后端 serde camelCase 序列化的结果:
 *   · `open_pairing_window()`  → `PairingWindow`(expiresAt 是 unix 毫秒, now + 5 分钟);
 *   · `pairing_status()`       → `PairingStatus`(窗口 + 待确认 + 已配对, 一份权威快照);
 *   · `approve_pairing` / `deny_pairing` / `forget_paired_device` → 无返回。
 *
 * 事件:
 *   · `pairing-request` —— 手机刚发起一条请求, 负载是**一条** `PendingPairing`;
 *   · `pairing-changed` —— 批准 / 拒绝 / 过期 / 移除之后, 负载是**完整**的 `PairingStatus`。
 *
 * 纪律与 `useScanner` 里 `decisions-changed` 的那条一致: `@tauri-apps/api/event` 走**动态
 * import**, 且这里不吞错 —— 调用方(弹窗 / 面板)决定"没有事件通道"要不要说出来。
 * 浏览器探针里没有 Tauri 注入, 订阅会 reject; 那是预期内的, 不是崩溃。
 */

/** 配对窗口(§0.3)。`expiresAt` 为 unix 毫秒。 */
export interface PairingWindow {
  expiresAt: number;
}

/** 一条待确认的配对请求(§0.3)。`requestedAt` 为 unix 毫秒。 */
export interface PendingPairing {
  requestId: string;
  deviceId: string;
  deviceName: string;
  ip: string;
  requestedAt: number;
}

/** 一台已经配对过的设备(§0.3)。`lastSeenAt` 为 unix 毫秒。 */
export interface PairedDevice {
  deviceId: string;
  name: string;
  lastSeenAt: number;
}

/** 配对状态快照(§0.3)。窗口没开时 `expiresAt` 缺省。 */
export interface PairingStatus {
  open: boolean;
  expiresAt?: number;
  pending: PendingPairing[];
  paired: PairedDevice[];
}

/** 配对窗口时长: 5 分钟(§0.3: open_pairing_window 返回 now + 5min)。 */
export const PAIRING_WINDOW_MS = 5 * 60_000;

/** 单条请求的有效期: 60 秒(§0.2: 请求 60 秒过期)。超时后弹窗自行收起。 */
export const PAIRING_REQUEST_TTL_MS = 60_000;

/** 前端最多同时排队的待确认请求数。后端硬上限也是 3, 这里只是同一条防线的前端副本。 */
export const PAIRING_MAX_PENDING = 3;

/** 倒计时用的 `m:ss`; 负数一律显示 0:00(不显示负时间)。 */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

/** `open_pairing_window()` —— 打开 5 分钟配对窗口, 返回它的到期时刻。 */
export function openPairingWindow(): Promise<PairingWindow> {
  return invoke<PairingWindow>("open_pairing_window");
}

/** `close_pairing_window()` —— 提前关窗(已发出的请求不再能被确认)。 */
export function closePairingWindow(): Promise<void> {
  return invoke<void>("close_pairing_window");
}

/** `pairing_status()` —— 读一份权威快照(纯内存)。 */
export function pairingStatus(): Promise<PairingStatus> {
  return invoke<PairingStatus>("pairing_status");
}

/** `approve_pairing(requestId)` —— 批准; 之后手机轮询才会拿到 url + token。 */
export function approvePairing(requestId: string): Promise<void> {
  return invoke<void>("approve_pairing", { requestId });
}

/** `deny_pairing(requestId)` —— 拒绝; 手机上只看到 denied, 拿不到任何地址或配对码。 */
export function denyPairing(requestId: string): Promise<void> {
  return invoke<void>("deny_pairing", { requestId });
}

/** `forget_paired_device(deviceId)` —— v1 里只是"忘记", **不吊销** token。 */
export function forgetPairedDevice(deviceId: string): Promise<void> {
  return invoke<void>("forget_paired_device", { deviceId });
}

/** `listen` 的退订函数类型(与 @tauri-apps/api/event 的 UnlistenFn 一致)。 */
export type PairingUnlisten = () => void;

/**
 * 订阅 `pairing-request`。每条新请求触发一次; 负载就是那一行 `PendingPairing`。
 */
export async function onPairingRequest(
  handler: (pending: PendingPairing) => void,
): Promise<PairingUnlisten> {
  const { listen } = await import("@tauri-apps/api/event");
  return listen<PendingPairing>("pairing-request", (event) => handler(event.payload));
}

/**
 * 订阅 `pairing-changed`。批准 / 拒绝 / 过期 / 移除之后各触发一次,
 * 负载是整份 `PairingStatus` —— 调用方可以直接把它当新的权威状态用。
 */
export async function onPairingChanged(
  handler: (status: PairingStatus) => void,
): Promise<PairingUnlisten> {
  const { listen } = await import("@tauri-apps/api/event");
  return listen<PairingStatus>("pairing-changed", (event) => handler(event.payload));
}
