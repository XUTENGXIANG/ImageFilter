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
  status: string; // "checking" | "copying" | "verifying" | "done" | "skipped" | "error"
  message: string;
  percent: number;
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

