// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
use serde::Serialize;

pub mod browse;
pub mod drives;
pub mod exif;
pub mod images;

/// pub(crate) 而不是私有: xmp.rs 要用它断言"传进来的是照片路径" ——
/// 边车写入只接受照片路径(不接受任意路径), 这是 Phase 5 的路径红线。
pub(crate) const SUPPORTED_EXTENSIONS: &[&str] = &[
    "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp",
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw", "raw",
    "mp4", "mov", "avi", "mkv",
    "heic", "heif",
];

const RAW_EXTENSIONS: &[&str] = &[
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw",
];

#[derive(Debug, Serialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct PhotoExif {
    pub camera_make: Option<String>,
    pub camera_model: Option<String>,
    pub lens_model: Option<String>,
    pub focal_length: Option<String>,
    pub aperture: Option<String>,
    pub shutter_speed: Option<String>,
    pub iso: Option<u32>,
    pub date_taken: Option<String>,
    pub image_width: Option<u32>,
    pub image_height: Option<u32>,
    pub file_size: u64,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ScannedPhoto {
    pub path: String,
    pub file_name: String,
    pub file_size: u64,
    pub is_raw: bool,
    pub is_video: bool,
    pub modified_at: i64,
    pub exif: PhotoExif,
}

/// Folder entry from browse_directory — lightweight, no EXIF parsing
#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct FolderEntry {
    pub path: String,
    pub name: String,
    /// **直接**照片文件数(不含视频、不含子目录里的照片)。
    pub photo_count: u32,
    /// **直接**视频文件数。界面可以用它单独显示视频数量。
    pub video_count: u32,
    /// **直接**子目录数。有子目录时界面显示这个, 而不是递归照片总数。
    pub subfolder_count: u32,
    pub has_subdirs: bool,
    pub subfolders: Vec<FolderEntry>,
}

/// 视频扩展名判定 —— `scan_directory` 与目录计数共用同一份, 不允许两边各写一套。
pub(crate) fn is_video_ext(ext: &str) -> bool {
    matches!(ext, "mp4" | "mov" | "avi" | "mkv")
}
