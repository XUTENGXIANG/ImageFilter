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
