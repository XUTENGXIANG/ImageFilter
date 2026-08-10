use serde::Serialize;

pub mod browse;
pub mod drives;
pub mod exif;
pub mod images;

const SUPPORTED_EXTENSIONS: &[&str] = &[
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
    pub photo_count: u32,
    pub has_subdirs: bool,
    pub subfolders: Vec<FolderEntry>,
}
