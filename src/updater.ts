// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// ═══════════════════════════════════════════════════════
// 检查更新 —— 只做一件事: 拿 GitHub 最新发布的版本号和本机版本比大小。
//
// 取舍(为什么是这几行):
//   · 用 REST 的 /releases/latest 而不是 /releases —— 前者按 GitHub 自己的定义
//     就是"最新正式版"(自动跳过 draft 与 pre-release), 不需要前端再筛一遍。
//   · 走 WebView 自己的 fetch, 不引 Rust 侧 HTTP 客户端(项目里没有 reqwest,
//     为一次 GET 拖一个 TLS 栈不划算)。代价: tauri.conf.json 的 CSP
//     connect-src 必须放行 api.github.com —— 少这一条就静默失败。
//   · 不做认证。匿名限额 60 次/小时/IP, 手动点按钮完全够; 超限会拿到 403,
//     归到 error 分支照实显示, 不假装"已是最新"。
//   · 只比对版本号, 不下载、不静默安装(见设置面板里的注释)。
//
// 本文件不 import Tauri / React, 纯到能被 esbuild 直接打成 node 脚本跑断言
// (docs/evidence/scripts/update-check.test.ts)。
// ═══════════════════════════════════════════════════════

export const RELEASES_REPO = "XUTENGXIANG/ImageFilter";

/** 最新正式版(JSON: tag_name / html_url) */
export const LATEST_RELEASE_API = `https://api.github.com/repos/${RELEASES_REPO}/releases/latest`;

/** 拿不到 html_url 时的兜底落地页 */
export const RELEASES_PAGE = `https://github.com/${RELEASES_REPO}/releases/latest`;

/** 拉取超时。GitHub 正常在 200ms 内回; 12s 是"网络确实不通"的兜底, 不是预期耗时 */
export const UPDATE_TIMEOUT_MS = 12000;

/** 失败原因。是**机器码**, 文案在 i18n 里 (error 分支的 title 只给人看) */
export type UpdateDetail =
  | { code: "network" }
  | { code: "timeout" }
  | { code: "http"; status: number }
  | { code: "unexpected" };

export type UpdateState =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "latest"; version: string }
  | { kind: "available"; version: string; url: string }
  | { kind: "error"; detail: UpdateDetail };

/** 只为可注入而存在: 单测塞桩函数, 产品里就是全局 fetch */
export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/**
 * "v1.2.3-beta.1" → { core: [1,2,3], pre: "beta.1" }; 解析不了返回 null。
 * 段数不固定("v2" 合法), 缺的段按 0 比 —— 这样 "1.1" 与 "1.1.0" 等价。
 */
export function parseVersion(raw: string): { core: number[]; pre: string } | null {
  const m = /^v?(\d+(?:\.\d+)*)(?:[-+](.+))?$/i.exec(raw.trim());
  if (!m) return null;
  return { core: m[1].split(".").map(Number), pre: m[2] ?? "" };
}

/**
 * candidate 是否比 current 新。
 * 任一版本号解析不了 → false(宁可不动, 也不误报"有新版本")。
 * 核心号相同时只认一种情况: 自己是预发布、对面是正式版(1.2.0-rc1 → 1.2.0)。
 * 反过来的"降级"(1.2.0-rc1 当最新而本机是 1.2.0)不算更新 —— 不替用户往回退。
 */
export function isNewer(candidate: string, current: string): boolean {
  const a = parseVersion(candidate);
  const b = parseVersion(current);
  if (!a || !b) return false;
  const n = Math.max(a.core.length, b.core.length);
  for (let i = 0; i < n; i++) {
    const x = a.core[i] ?? 0;
    const y = b.core[i] ?? 0;
    if (x !== y) return x > y; // 逐段数值比 —— "1.1.10 > 1.1.9" 不能按字符串比
  }
  return b.pre !== "" && a.pre === "";
}

/** 把异常归到 UpdateDetail —— 界面上要的是"哪一类失败", 不是异常原文 */
function detailOf(e: unknown): UpdateDetail {
  if (e instanceof DOMException && e.name === "AbortError") return { code: "timeout" };
  if (e instanceof Error) {
    const m = /^HTTP (\d+)$/.exec(e.message);
    if (m) return { code: "http", status: Number(m[1]) };
    // 网络层失败在 Chromium 上是 TypeError("Failed to fetch"), 在别处文案不定;
    // 认不了的一律归 unexpected, 别把异常原文塞给用户
    if (e instanceof TypeError) return { code: "network" };
  }
  return { code: "unexpected" };
}

/** 拉最新正式版; 失败抛异常(交给 checkForUpdate 归类) */
export async function fetchLatestRelease(
  f: FetchLike = fetch,
  timeoutMs: number = UPDATE_TIMEOUT_MS,
): Promise<{ version: string; url: string }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await f(LATEST_RELEASE_API, {
      signal: ctrl.signal,
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const json = (await res.json()) as { tag_name?: unknown; html_url?: unknown };
    const tag = typeof json.tag_name === "string" ? json.tag_name : "";
    const parsed = parseVersion(tag);
    if (!parsed) throw new Error("unexpected tag_name");
    return {
      version: tag.trim().replace(/^v/i, ""),
      url: typeof json.html_url === "string" && json.html_url ? json.html_url : RELEASES_PAGE,
    };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 完整流程: 比版本号 → 五态之一。**不抛异常** —— 失败也是一条状态。
 * current 解析不了(拿不到本机版本)时直接判 unexpected: 没基准就没法比大小。
 */
export async function checkForUpdate(
  current: string,
  f: FetchLike = fetch,
  timeoutMs: number = UPDATE_TIMEOUT_MS,
): Promise<UpdateState> {
  if (!parseVersion(current)) return { kind: "error", detail: { code: "unexpected" } };
  try {
    const latest = await fetchLatestRelease(f, timeoutMs);
    return isNewer(latest.version, current)
      ? { kind: "available", version: latest.version, url: latest.url }
      : { kind: "latest", version: current };
  } catch (e) {
    return { kind: "error", detail: detailOf(e) };
  }
}
