// ═══════════════════════════════════════════════════════════════════
// Phase 5 · XMP 边车的纯逻辑层(零 React / 零 Tauri 依赖)
//
// 为什么单独一个文件: 与 src/undo.ts 并列 —— 档位解析、边车/本地合并优先级、
// 提交载荷的构造是这一 Phase 唯一能脱离 GUI 验证的部分(docs 前置约束 8:
// 临时脚本 + 仓库自带 esbuild 转译后跑 Node 断言, 用完即删; 不引 vitest)。
//
// 四条不变式(改动前先读):
// 1. **档位读取只认显式值**: "off"/"ask"/"on" 之外(缺省/损坏/拼错)一律
//    [`DEFAULT_XMP_MODE`] —— 读取时不做任何猜测。
//    真正的绝对约束是"**永不静默写卡**": 缺省档位由会话 ⑥ 从 `off` 改为 `ask`,
//    于是首次写盘会**问一次**(用户点"只写本机"即回到 off 的行为), 但任何路径都
//    不会在用户不知情的情况下往卡上写字节。
// 2. **边车只在"真的有值"时覆盖本地**(rating/label 为 null 一律保留本地),
//    否则"卡格式化后新照片占用同名路径"会被当成"边车说是 0"。
// 3. **提交载荷只带用户真的改过的字段**(字段集合), 值在 flush 时现读 ——
//    绝不"把整份本地状态回写整卡": 那会把 Lightroom 写的星级抹成 0。
// 4. **"不动"与"清空"必须可区分**(label=null + clearLabel): 否则 Ctrl+Z
//    撤掉标签后, 边车里的旧标签会在下次读盘时把它复活。
// ═══════════════════════════════════════════════════════════════════

import { isLabel, type Label } from "./labels";
import type { DecisionRead, DecisionWrite } from "./types";

/** 三档开关(docs §5.2)。缺省 = [`DEFAULT_XMP_MODE`]("询问")。 */
export type XmpMode = "off" | "ask" | "on";

/** localStorage key —— 与 imagefilter-ratings / imagefilter-labels 都无关 */
export const XMP_MODE_STORAGE_KEY = "imagefilter-xmp-mode";

/** 待同步队列上限: 超过就丢最旧的并提示一次, 避免极端情况下无限堆积 */
export const XMP_QUEUE_LIMIT = 256;

/** 一次写盘要带上哪些字段 —— 队列存的是"字段集合", 不是值(不变式 3) */
export type XmpField = "rating" | "label";

export function isXmpMode(v: unknown): v is XmpMode {
  return v === "off" || v === "ask" || v === "on";
}

/**
 * 缺省档位 = **"ask"(询问)**。
 *
 * 会话 ⑥ 由用户改的(原文记录是"默认关闭", 已被推翻): 星级只留在这台电脑上毫无意义,
 * 而大多数人不会主动去设置里翻那三档。`ask` 是"既不静默碰卡、也不至于让用户永远
 * 发现不了这个功能"的折中 —— 首次将要写盘时弹**一次**问询, 作答后落成明确档位,
 * 本会话不再问(见 useScanner::queueXmpWrite / resolveXmpAsk)。
 *
 * 与 `off` 的实质差别: `off` 是"永不写盘、也永不询问"; 缺省改成 `ask` 之后,
 * 首次评分/打标会看到一次弹窗。用户点"只写本机"即回到 `off` 的行为。
 */
export const DEFAULT_XMP_MODE: XmpMode = "ask";

/**
 * 读档位。**只有显式 "off" / "ask" / "on" 才算**: 缺省、损坏、被别的版本写成
 * 别的东西 → 一律 [`DEFAULT_XMP_MODE`]。
 *
 * 刻意不用 useLocalStorageSetting: 那个 hook 原样读回字符串(会话 ① 已否决过它
 * 处理布尔值的写法), 绕不过校验; 与 autoAdvance / labelModifier 同款防御写法。
 */
export function readXmpMode(): XmpMode {
  try {
    const raw = localStorage.getItem(XMP_MODE_STORAGE_KEY);
    return isXmpMode(raw) ? raw : DEFAULT_XMP_MODE;
  } catch {
    return DEFAULT_XMP_MODE;
  }
}

// ── 错误码 ──────────────────────────────────────────────────────────
// 与 Rust 侧 xmp.rs 的 XmpError::code() 是同一份契约(那边有单测钉住字符串)。
// 前端按白名单拼 `xmp.err.<code>`, 未知码落到 unknown —— 不让脏值进 t()。

export const XMP_ERR_CODES = [
  "writeProtect",
  "readOnly",
  "permission",
  "diskFull",
  "busy",
  "notXmp",
  "unsupportedForm",
  "tooLarge",
  "encoding",
  "pathTooLong",
  "unknown",
] as const;

export type XmpErrCode = (typeof XMP_ERR_CODES)[number];

export function isXmpErrCode(v: unknown): v is XmpErrCode {
  return typeof v === "string" && (XMP_ERR_CODES as readonly string[]).includes(v);
}

/** i18n key 后缀(白名单兜底) */
export function xmpErrKey(code: unknown): XmpErrCode {
  return isXmpErrCode(code) ? code : "unknown";
}

/**
 * 这几个错误码**可能**意味着"整卷/整目录不可写"，但**不足以据此降级** ——
 * 单个文件只读、单个文件 ACL 拒绝都会落到同样这几个码上（实测：把一个 .xmp 设成
 * 只读，会让整个功能被关掉，而按 docs §6 的规矩"单文件问题不降级"）。
 * 所以调用方的正确姿势是：命中这些码 → **重新探测该目录** → 探测也说不可写才降级。
 */
export function isDowngradeCandidate(code: unknown): boolean {
  return code === "writeProtect" || code === "readOnly" || code === "permission";
}

/** 取父目录（`\` 与 `/` 都认，UNC 路径也适用）；用于"失败后重新探测这个目录" */
export function dirOfPath(path: string): string {
  const i = Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/"));
  return i > 0 ? path.slice(0, i) : "";
}

// ── 合并(边车 → 本机 map) ──────────────────────────────────────────

/** 给 App 的一次性提示(文案在 App 里用 i18n 拼: hook 不依赖 i18n, 会话 ② 定纪律) */
export interface XmpNotice {
  /** 自增序号: 内容相同的提示也能重新触发 toast(去重在 hook 里做) */
  seq: number;
  kind: "failed" | "downgraded" | "overflow";
  code: string | null;
  /** failed 时是本次失败张数; overflow 时是队列上限 */
  n: number;
}

/** 设置页常驻状态行(不是 toast: "卡不可写"要能随时回看) */
export interface XmpStatus {
  dir: string;
  writable: boolean;
  code: string | null;
  pending: number;
  network: boolean;
  /** 至少有一次写入退化成非原子直写(网络盘不支持 rename 覆盖) */
  degraded: boolean;
}

export interface MergeResult<T> {
  map: Record<string, T>;
  /** 真的变了的 path。为空时**不要**回写 localStorage(省掉无意义写盘) */
  changed: string[];
}

/**
 * 星级合并。边车优先, 但只在边车真的有值的时候(不变式 2):
 *  - remote.rating 为 null(没边车/没该属性/值不认识) → **保留本地**;
 *  - remote.rating === 0 → **删掉本地键**, 而不是写一个 0 键: 语义上 0 = 无星级,
 *    读取一律 `ratings[path] || 0`, 删键与之等价; 不删的话, 一张"在 LR 里清了
 *    全部星级"的卡会给几千张照片各留一个 0 键, 白撑大 localStorage。
 *    (与 setRating 的既有语义**刻意不同**: 用户手动评 0 星保留 `path: 0` 键。)
 */
export function mergeRemoteRatings(
  local: Record<string, number>,
  remote: DecisionRead[],
): MergeResult<number> {
  let map = local;
  const changed: string[] = [];
  for (const r of remote) {
    if (typeof r.rating !== "number") continue;
    const v = Math.trunc(r.rating);
    if (!Number.isFinite(v) || v < 0 || v > 5) continue;
    if (v === 0) {
      if (map[r.path] === undefined) continue;
      if (map === local) map = { ...local };
      delete map[r.path];
      changed.push(r.path);
      continue;
    }
    if (map[r.path] === v) continue;
    if (map === local) map = { ...local };
    map[r.path] = v;
    changed.push(r.path);
  }
  return { map, changed };
}

/**
 * 标签合并。同样只在边车有值时覆盖(不变式 2); 越界值再校验一层丢弃。
 *
 * 已知边界(写进遗留): 边车的"没有 xmp:Label"与"用户在 LR 里清掉了标签"无法区分,
 * 所以本地标签会保留 —— 宁可留着, 也不要因为读不到就删掉用户的数据。
 */
export function mergeRemoteLabels(
  local: Record<string, Label>,
  remote: DecisionRead[],
): MergeResult<Label> {
  let map = local;
  const changed: string[] = [];
  for (const r of remote) {
    if (r.label == null) continue;
    if (!isLabel(r.label)) continue;
    if (map[r.path] === r.label) continue;
    if (map === local) map = { ...local };
    map[r.path] = r.label;
    changed.push(r.path);
  }
  return { map, changed };
}

/**
 * 构造一次写入的载荷(不变式 3 / 4)。
 *
 * ⚠️ 两处最危险的写法(会话 ② 的教训: 最危险的失败方向要专门钉死):
 *  · 星级必须 `ratings[path] ?? 0` —— **不能**写 `ratings[path] || null`:
 *    0 是合法值("清除星级"), 被当成"没有"就等于撤销回不去;
 *  · 清标签走 `clearLabel`, 而不是把 label 传成 "" —— "不动"与"清空"必须可区分。
 */
export function buildDecisionWrite(
  path: string,
  fields: ReadonlySet<XmpField>,
  ratings: Record<string, number>,
  labels: Record<string, Label>,
): DecisionWrite {
  const wantRating = fields.has("rating");
  const wantLabel = fields.has("label");
  return {
    path,
    rating: wantRating ? ratings[path] ?? 0 : null,
    label: wantLabel ? labels[path] ?? null : null,
    clearLabel: wantLabel && labels[path] === undefined,
  };
}
