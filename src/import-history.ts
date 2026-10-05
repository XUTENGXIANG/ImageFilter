// ═══════════════════════════════════════════════════════
// Phase 6 · 导入历史的纯逻辑(零 React、零 Tauri 依赖)
// 与 undo.ts / labels.ts / xmp.ts 并列: 组件只消费结果, 规则都写在这里。
// 这里的东西都能用"临时脚本 + 仓库自带 esbuild 转译 + Node 断言"直接验。
//
// 「取父目录」不在这里重复实现 —— 直接用 xmp.ts 的 dirOfPath(两份定义必有一份腐烂)。
// ═══════════════════════════════════════════════════════
import type { ImportHistoryItem } from "./types";

/** 默认一次读多少条 */
export const DEFAULT_HISTORY_LIMIT = 100;
/** 「加载更多」每次追加多少条 */
export const HISTORY_PAGE_SIZE = 100;

/**
 * 列表硬上限。没有虚拟滚动(本仓库不引第三方依赖), 几百行以上重渲染会明显卡,
 * 所以宁可截断也不让 UI 被拖死; 截断必须**可见** —— 头部同时显示"已显示 N / 共 M"。
 */
export const HISTORY_MAX = 500;

/** 历史列表 + 加载入口。由 useScanner 组装, 组件只读它(组件不许直接 invoke) */
export interface ImportHistoryApi {
  items: ImportHistoryItem[];
  total: number;
  loading: boolean;
  error: string | null;
  /** 重新读前 limit 条(不是追加): 单调增长 + 每次重查, 列表与总数不可能错位 */
  load: (limit: number) => void;
}

/**
 * SQLite 的 `CURRENT_TIMESTAMP` 是 **UTC** 且形如 `2026-09-27 10:00:00`。
 * V8 会把这种"空格分隔"的串按**本地时间**解释 → 必须显式补 `T`/`Z` 再转本地,
 * 否则显示出来差一个时区(实测差 8 小时, 且看起来像"导入时间不对"的 bug)。
 * 解析不出来就原样返回 —— 宁可显示原始串, 也不显示 "Invalid Date"。
 */
export function formatImportedAt(utc: string): string {
  const d = new Date(`${utc.trim().replace(" ", "T")}Z`);
  return Number.isNaN(d.getTime()) ? utc : d.toLocaleString();
}
