// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
export interface DriveInfo {
  mountPoint: string;
  driveType: string;
  label: string;
  available: boolean;
}

export interface PhotoExif {
  cameraMake?: string;
  cameraModel?: string;
  lensModel?: string;
  focalLength?: string;
  aperture?: string;
  shutterSpeed?: string;
  iso?: number;
  dateTaken?: string;
  imageWidth?: number;
  imageHeight?: number;
  fileSize: number;
}

export interface ScannedPhoto {
  path: string;
  fileName: string;
  fileSize: number;
  isRaw: boolean;
  isVideo: boolean;
  modifiedAt: number; // unix timestamp ms
  exif: PhotoExif;
}

export interface FolderEntry {
  path: string;
  name: string;
  photoCount: number;
  hasSubdirs: boolean;
  subfolders: FolderEntry[];
}

/** 导入进度事件（Rust import_photos 经 Channel 推送） */
export interface ImportProgress {
  fileName: string;
  /**
   * "checking" 检查 / "copying" 复制 / "renamed" 重名改名 / "verifying" MD5 校验 /
   * "done" 完成 / "skipped" 已存在且相同 / "error" 失败 / "sidecar" 边车那一行(Phase 5)。
   * 未知状态在 import-bar 里走默认样式, 不会渲染出错。
   */
  status: string;
  message: string;
  percent: number;
}

/**
 * Phase 6 / 6.3 · `import_photos` 的返回值（**契约变更**: 原来是 `u32`）。
 *
 * 口径(前端**直接显示, 不许再自己算** —— 老前端用 `paths.length - count` 把"跳过"
 * 算成了"失败"):
 *   · imported = 真的复制进归档的张数(**含** renamed);
 *   · renamed  = 其中"目标同名但内容不同、被改名成 `_1`"的张数(⊆ imported);
 *   · skipped  = 目标已存在且内容相同(不覆盖、不计数, 但**不是失败**);
 *   · failed   = 复制/校验/任务失败;
 *   · 不变式: imported + skipped + failed == 本次传入的张数。
 */
export interface ImportSummary {
  imported: number;
  skipped: number;
  renamed: number;
  failed: number;
}

/**
 * Phase 4 · 分析结果筛选维度（"all" = 不过滤）。
 * 取值与卡片徽标同一套语义：blurry/over/under 来自 analyze_photos，
 * duplicate/best 来自 find_duplicates。**没分析过的照片一律不算命中** ——
 * 这正是网格顶部"还有 N 张未分析"提示存在的原因（docs 4.2）。
 */
export type FlagFilter = "all" | "blurry" | "over" | "under" | "duplicate" | "best";

/** AI 分析结果（模糊/曝光/重复） */
export interface AnalysisResult {
  path: string;
  blurScore: number;
  isBlurry: boolean;
  isOverexposed: boolean;
  isUnderexposed: boolean;
  duplicateGroup?: number;
  isBestInGroup: boolean;
}

/** 左侧设备文件夹树节点 */
export interface FolderNode {
  name: string;
  path: string;
  children: FolderNode[];
  photoCount: number;
  hasSubdirs: boolean;
}

// ── Phase 5 · XMP 边车 ─────────────────────────────────────────────
// 与 src-tauri/src/xmp.rs 的 serde 结构一一对应(rename_all = "camelCase")。

/** read_decisions 的单项结果。rating/label 为 null = 边车里没有可靠的值 */
export interface DecisionRead {
  path: string;
  rating: number | null;
  label: string | null;
  sidecar: string | null;
}

/** write_decisions 的单项载荷。null = **不动**这个属性(不是清空) */
export interface DecisionWrite {
  path: string;
  rating: number | null;
  label: string | null;
  clearLabel: boolean;
}

export interface WriteFailure {
  path: string;
  /** 闭集错误码, 见 src/xmp.ts 的 XMP_ERR_CODES */
  code: string;
  detail: string;
}

export interface WriteSummary {
  written: number;
  skipped: number;
  failed: number;
  failures: WriteFailure[];
  /** true = 至少一次写入退化成了非原子直写(网络盘不支持 rename 覆盖) */
  degraded: boolean;
}

/** probe_xmp_target 的结果(writable=false 时 code 说明原因) */
export interface XmpProbe {
  dir: string;
  writable: boolean;
  code: string | null;
  detail: string | null;
  network: boolean;
}

// ── Phase 6 · 导入历史 / 命名方案 / 导入结果 ─────────────────────────
// 与 src-tauri/src/db.rs、importer.rs 的 serde 结构一一对应(都带 rename_all = "camelCase")。

/**
 * 一条导入历史 = 一次**真的把文件复制进归档**的留痕。
 * 注意: 目标已存在且内容相同(跳过)的**不写库**(见 importer.rs), 所以列表里的每一条
 * 都对应归档里的一个真实文件。
 */
export interface ImportHistoryItem {
  id: number;
  /** 源文件完整路径 —— `{seq}` 改名后**原文件名唯一的补救**就在这一列 */
  sourcePath: string;
  /** 归档后的完整路径 */
  destPath: string;
  fileHash: string;
  fileSize: number;
  /** SQLite CURRENT_TIMESTAMP, **UTC**; 展示前必须走 formatImportedAt */
  importedAt: string;
}

/** 一条命名方案(import_rules 表)。模板变量见 importer.rs 的 build_dest_path */
export interface ImportRule {
  id: number;
  name: string;
  /** 目录模板, 如 "{date}/{camera}"; 空 = 平铺 */
  folderTemplate: string;
  /** 文件名模板, 如 "{seq}_{original}.{ext}"; 空 = 保留原名 */
  fileTemplate: string;
  /**
   * 1 = `init_db` 播种的"默认"方案(`{date}` / `{original}`)。
   * 前端**绝不在启动时自动套用它** —— 套上会让老用户的归档结构突变(docs 6.2)。
   * 它只是下拉里的一个可选项, 要用得用户自己点。
   */
  isDefault: number;
}

// ── Phase 7 · Lightroom Classic 衔接 ────────────────────────────────
// 与 src-tauri/src/lightroom.rs 的 serde 结构一一对应(rename_all = "camelCase")。
// 错误码闭集在 src/lightroom.ts(LRC_ERR_CODES)。

/** probe_lightroom 的结果。found=false 时整个"发送到 Lightroom"入口隐藏 */
export interface LightroomProbe {
  found: boolean;
  /** 找到的 Lightroom.exe 完整路径(found=false 时为 null) */
  exe: string | null;
  /**
   * 命中的探测方式, 仅用于排查:
   * "classUser" | "classMachine" | "uninstall" | "appPath" | "programFiles"
   */
  source: string | null;
  /** Lightroom 是否正在运行。只影响提示文案(takes a while vs already open) */
  running: boolean;
}

