// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// ═══════════════════════════════════════════════════════
// Phase 6 · 命名方案(import_rules)的纯逻辑 —— 零 React、零 Tauri 依赖
//
// 最重要的一条不变式(整份 Phase 6 的红线之一):
//   **没有 localStorage 键 = 模板就是 ""/""(平铺 + 原名)**, 也就是升级前的既有行为。
//   DB 里 `init_db` 播种的"默认"方案是 `{date}` —— 启动时自动套用它会**改掉所有老用户
//   的归档结构**(突然多一层日期目录), 所以只在用户主动从下拉里选它时才生效。
// ═══════════════════════════════════════════════════════
import type { ImportRule } from "./types";

/** 上次用的命名方案(名字 + 两个模板)。名字为 null = 自定义(手改过模板) */
export interface ImportScheme {
  name: string | null;
  folder: string;
  file: string;
}

export const SCHEME_STORAGE_KEY = "imagefilter-import-scheme";

export const EMPTY_SCHEME: ImportScheme = { name: null, folder: "", file: "" };

/**
 * 读上次用的方案。**任何不合法都回落到 null**(= 平铺 + 原名), 与 readXmpMode / readLabels
 * 同一套"读取时兜底"纪律: 损坏的 localStorage 绝不能把用户带进意料之外的归档结构。
 *
 * 注意: 不能用 useLocalStorageSetting 存它(那个 hook 用 String(v) → 对象会变成
 * "[object Object]"), 与 autoAdvance / labelModifier / xmpMode 是同一条已知陷阱。
 */
export function readScheme(): ImportScheme | null {
  try {
    const raw = localStorage.getItem(SCHEME_STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    const o = parsed as Record<string, unknown>;
    if (typeof o.folder !== "string" || typeof o.file !== "string") return null;
    if (o.name !== null && typeof o.name !== "string") return null;
    const name = o.name === "" || o.name === undefined ? null : (o.name as string | null);
    return { name, folder: o.folder, file: o.file };
  } catch {
    return null;
  }
}

/** 写回(失败静默: 隐私模式/配额满都不该让导入栏崩掉) */
export function writeScheme(s: ImportScheme): void {
  try {
    localStorage.setItem(SCHEME_STORAGE_KEY, JSON.stringify(s));
  } catch {
    /* 忽略写入失败 */
  }
}

/**
 * 下拉里要显示的方案名。
 *
 * 是 `rules` 与 `{当前名}` 的**并集**, 不是只用 rules:
 *  · 方案列表是惰性加载的(首次展开高级选项才拉), 拉回来之前 `<select value>` 找不到
 *    对应的 `<option>` 会**显示第一个选项**(等于说谎);
 *  · 方案在别处被删掉之后, 也不该让已选中的名字凭空消失。
 */
export function schemeOptions(rules: ImportRule[], current: string | null): string[] {
  const names = rules.map((r) => r.name);
  if (current && !names.includes(current)) names.push(current);
  return names;
}

/** 方案面板要的一切(由 useScanner 组装)。组件只读它, 不许自己 invoke */
export interface ImportSchemeApi {
  rules: ImportRule[];
  /** 当前选中的方案名; null = 自定义(手改过模板) */
  name: string | null;
  /** 读/存失败的一次性提示(文案在组件里用 i18n 拼: hook 不依赖 i18n) */
  error: string | null;
  /** 拉一次 get_rules —— 惰性: 首次展开高级选项时才调, 不在启动时白跑一次 IPC */
  load: () => void;
  /** 选中方案(null = 回到"平铺 + 原名"); **只写两个模板**, customFolder 不动 */
  pick: (name: string | null) => void;
  /** 另存为(同名 = 覆盖)。true = 成功(组件据此清空输入框) */
  save: (name: string) => Promise<boolean>;
}

// ── 「按序号重命名」的两种规范形态 ─────────────────────────────────────
// 老实现写死 `fileRule === "{seq}.{ext}" ? "" : "{seq}.{ext}"`: 勾上就整条替换, 取消勾选
// 会把自定义模板抹成空, "序号 + 原名"根本无法表达。
// 新实现: 勾上仍然是 SEQ_ONLY(**与改动前逐字节相同 → 老用户归档结构不变**),
// 子选项才切到 SEQ_KEEP。规则放在这里(而不是组件里)是为了能用 Node 断言钉住。

/** 勾"按序号重命名"的默认结果 —— 不许改, 改了就是老用户归档结构突变 */
export const SEQ_ONLY = "{seq}.{ext}";
/** "序号 + 原名" */
export const SEQ_KEEP = "{seq}_{original}.{ext}";
/** "不改文件名" = 空模板(importer 的 build_dest_path 对空模板保留原名) */
export const FILE_RULE_ORIGINAL = "";

/** 当前模板是否含 {seq} */
export function isSeqRule(file: string): boolean {
  return file.includes("{seq}");
}

/** 当前模板是否"序号 + 原名"形态 */
export function keepsOriginalWithSeq(file: string): boolean {
  return file.includes("{seq}") && file.includes("{original}");
}

/**
 * 勾选框切换。关闭时一律回到"原名"(空模板) —— 这是**预设控件不是自由编辑器**:
 * 从方案里带进来的非规范模板(如 `{year}{seq}.{ext}`)会被归一, 不在这里做猜测式改写。
 */
export function toggleSeqRule(file: string): string {
  return isSeqRule(file) ? FILE_RULE_ORIGINAL : SEQ_ONLY;
}

/** 子选项切换: 只在"序号 + 原名"与"纯序号"之间来回 */
export function toggleKeepOriginalRule(file: string): string {
  return keepsOriginalWithSeq(file) ? SEQ_ONLY : SEQ_KEEP;
}
