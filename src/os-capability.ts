// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { invoke } from "@tauri-apps/api/core";

/** Rust `get_os_capabilities` 的返回(serde camelCase)。 */
export interface OsCapabilities {
  /** "windows" / "macos" / "linux"；整体探测失败时为 "unknown" */
  platform: string;
  /** Windows 构建号。**null 表示探测失败，不是「不支持」** */
  windowsBuild: number | null;
  supportsMica: boolean;
}

/**
 * 探测失败 / 超时的兜底。
 *
 * platform 特意不是 "windows" —— 于是 `osDefaultGlass` 返回 true、`micaUnsupported`
 * 返回 false，也就是**完整保持今天的行为**：不误关玻璃，也不把设置里的开关置灰。
 */
const UNKNOWN: OsCapabilities = { platform: "unknown", windowsBuild: null, supportsMica: false };

/**
 * 玻璃开关在**用户没存过值**时该用什么默认值。
 *
 * 三条规则，顺序不能换：
 *   1. 用户存过 → 永远听用户的（老用户不会被这次改动纠正）
 *   2. 没存过 + 确认是 Windows + 构建号读到了 → 用支持情况（Win11 开 / Win10 关）
 *   3. 其余（非 Windows / 探测失败 / 构建号读不到）→ true，即保持今天的行为
 *
 * 规则 3 里的「非 Windows」是必须的：`supportsMica` 在 macOS/Linux 上恒为 false，
 * 直接拿它当默认值会把 macOS 的默认从「开」改成「关」——那是本次范围外的行为变更。
 *
 * 规则 2 里的「构建号读到了」也是必须的：只看 `platform === "windows"` 的话，
 * 一次注册表读取失败就会把 Win11 用户的玻璃默认关掉（而且下面还会把开关置灰）。
 * 这是对规格 §4 的收紧，原因就是这个。
 */
export function osDefaultGlass(caps: OsCapabilities, stored: string | null): boolean {
  if (stored !== null) return stored !== "0";
  if (caps.platform === "windows" && caps.windowsBuild !== null) return caps.supportsMica;
  return true;
}

/** 只有「确认是 Windows、确认读到了构建号、且那个版本不支持」才置灰。 */
export function micaUnsupported(caps: OsCapabilities): boolean {
  return caps.platform === "windows" && caps.windowsBuild !== null && !caps.supportsMica;
}

async function probe(timeoutMs: number): Promise<OsCapabilities> {
  try {
    const caps = await Promise.race([
      invoke<OsCapabilities>("get_os_capabilities"),
      new Promise<OsCapabilities>((_, reject) =>
        setTimeout(() => reject(new Error("get_os_capabilities timeout")), timeoutMs),
      ),
    ]);
    // 兜底校验: invoke 返回了非预期形状(例如测试桩没实现这个命令、返回 null)时
    // 一律当探测失败。少了这一步, 上层拿到的可能是 null 而不是一个能力对象。
    if (!caps || typeof caps !== "object" || typeof caps.supportsMica !== "boolean") {
      return UNKNOWN;
    }
    return caps;
  } catch (err) {
    console.error("get_os_capabilities failed:", err);
    return UNKNOWN;
  }
}

let cached: Promise<OsCapabilities> | null = null;

/**
 * 启动时问一次系统能力。带超时 —— invoke 万一卡住，不能让窗口一直不出现。
 * 结果缓存: 系统版本在一次运行里不会变，重复调用不必再走一次 IPC。
 */
export function loadOsCapabilities(timeoutMs = 500): Promise<OsCapabilities> {
  if (!cached) cached = probe(timeoutMs);
  return cached;
}
