use inspect_path::inspect_path;
use serde::Serialize;

const SUPPORTED_EXTENSIONS: &[&str] = &[
    "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp",
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw", "raw",
    "mp4", "mov", "avi", "mkv",
    "heic", "heif",
];

const RAW_EXTENSIONS: &[&str] = &[
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw",
];

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DriveInfo {
    pub mount_point: String,
    pub drive_type: String,
    pub label: String,
    pub available: bool,
}

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

/// Open folder in OS file manager
#[tauri::command]
pub fn open_folder(path: String) {
    #[cfg(target_os = "windows")]
    { let _ = std::process::Command::new("explorer").arg(&path).spawn(); }
    #[cfg(target_os = "macos")]
    { let _ = std::process::Command::new("open").arg(&path).spawn(); }
    #[cfg(target_os = "linux")]
    { let _ = std::process::Command::new("xdg-open").arg(&path).spawn(); }
}

/// Detect all drives, removable drives first
#[tauri::command]
pub fn detect_drives() -> Vec<DriveInfo> {
    let mut drives = Vec::new();

    for letter in 'A'..='Z' {
        let mount = format!("{}:\\", letter);
        let path = std::path::Path::new(&mount);
        if !path.exists() {
            continue;
        }

        if let Ok(info) = inspect_path(path) {
            let drive_type = if info.is_removable() {
                "removable"
            } else if info.is_fixed() {
                "fixed"
            } else if info.is_remote() {
                "network"
            } else if info.is_cdrom() {
                "cdrom"
            } else if info.is_ramdisk() {
                "ramdisk"
            } else {
                "unknown"
            };

            let is_removable = info.is_removable();
            let label = format!(
                "{}{}",
                mount,
                if is_removable { " (可移动)" } else { "" }
            );

            drives.push(DriveInfo {
                mount_point: mount,
                drive_type: drive_type.to_string(),
                label,
                available: true,
            });
        }
    }

    drives.sort_by(|a, b| {
        let a_rem = a.drive_type == "removable";
        let b_rem = b.drive_type == "removable";
        b_rem.cmp(&a_rem).then_with(|| a.mount_point.cmp(&b.mount_point))
    });

    drives
}

/// Instant browse: tree structure only, count=0 for subfolders.
#[tauri::command]
pub async fn browse_directory(dir_path: String) -> Result<FolderEntry, String> {
    let path = std::path::Path::new(&dir_path);
    if !path.is_dir() {
        return Err("Not a directory".into());
    }

    let mut photo_count: u32 = 0;
    let mut subfolders: Vec<FolderEntry> = Vec::new();

    let entries = std::fs::read_dir(path).map_err(|e| format!("Read dir: {}", e))?;

    for entry in entries.flatten() {
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };

        if ft.is_dir() {
            let child_path = entry.path();
            let has_subdirs = has_subdirectories(&child_path);
            subfolders.push(FolderEntry {
                path: child_path.to_string_lossy().to_string(),
                name: entry.file_name().to_string_lossy().to_string(),
                photo_count: 0,
                has_subdirs,
                subfolders: vec![],
            });
        } else if ft.is_file() {
            if let Some(ext) = entry.path().extension().and_then(|e| e.to_str()) {
                if SUPPORTED_EXTENSIONS.contains(&ext.to_lowercase().as_str()) {
                    photo_count += 1;
                }
            }
        }
    }

    subfolders.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    Ok(FolderEntry {
        path: path.to_string_lossy().to_string(),
        name: path.file_name().unwrap_or_default().to_string_lossy().to_string(),
        photo_count,
        has_subdirs: !subfolders.is_empty(),
        subfolders,
    })
}

/// Background: count photos for given paths, returns map of path→count
#[tauri::command]
pub async fn count_folders(
    folder_paths: Vec<String>,
) -> Result<std::collections::HashMap<String, u32>, String> {
    let mut map = std::collections::HashMap::new();
    for p in &folder_paths {
        map.insert(p.clone(), count_photos_recursive(std::path::Path::new(p)));
    }
    Ok(map)
}

/// Check if directory contains any subdirectory
fn has_subdirectories(path: &std::path::Path) -> bool {
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            if let Ok(ft) = entry.file_type() {
                if ft.is_dir() {
                    return true;
                }
            }
        }
    }
    false
}

/// Count ALL photos in directory tree (avoid file_type() syscalls — use extension heuristic)
fn count_photos_recursive(path: &std::path::Path) -> u32 {
    let mut count: u32 = 0;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let file_path = entry.path();
            // Fast path: check extension (directories never match photo extensions)
            if let Some(ext) = file_path.extension().and_then(|e| e.to_str()) {
                if SUPPORTED_EXTENSIONS.contains(&ext.to_lowercase().as_str()) {
                    count += 1;
                    continue; // It's a photo file, skip dir check
                }
            }
            // No matching extension → might be a directory, recurse
            // (skip file_type() call — just try read_dir, it fails fast for files)
            count += count_photos_recursive(&file_path);
        }
    }
    count
}

/// Scan a single directory (non-recursive) — fast listing, NO EXIF parsing
#[tauri::command]
pub async fn scan_directory(dir_path: String) -> Vec<ScannedPhoto> {
    let path = std::path::Path::new(&dir_path);
    if !path.exists() || !path.is_dir() {
        return vec![];
    }

    let mut photos: Vec<ScannedPhoto> = Vec::new();

    let entries = match std::fs::read_dir(path) {
        Ok(e) => e,
        Err(_) => return vec![],
    };

    for entry in entries.flatten() {
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if !ft.is_file() {
            continue;
        }

        let file_path = entry.path();
        let ext = file_path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        if !SUPPORTED_EXTENSIONS.contains(&ext.as_str()) {
            continue;
        }

        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };

        let file_size = metadata.len();
        let is_raw = RAW_EXTENSIONS.contains(&ext.as_str());
        let is_video = matches!(ext.as_str(), "mp4" | "mov" | "avi" | "mkv");

        photos.push(ScannedPhoto {
            path: file_path.to_string_lossy().to_string(),
            file_name: file_path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .to_string(),
            file_size,
            is_raw,
            is_video,
            exif: PhotoExif::default(),
        });
    }

    // Sort by file name (consistent, no EXIF needed)
    photos.sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));

    photos
}

/// Lazy-load EXIF for a single photo (called when user selects a photo)
#[tauri::command]
pub fn get_exif(file_path: String) -> PhotoExif {
    extract_exif(std::path::Path::new(&file_path))
}

fn extract_exif(file_path: &std::path::Path) -> PhotoExif {
    let mut exif = PhotoExif::default();

    if let Ok(meta) = std::fs::metadata(file_path) {
        exif.file_size = meta.len();
    }

    let file = match std::fs::File::open(file_path) {
        Ok(f) => f,
        Err(_) => return exif,
    };

    let mut reader = std::io::BufReader::new(file);
    let exif_reader = match exif::Reader::new().read_from_container(&mut reader) {
        Ok(r) => r,
        Err(_) => return exif,
    };

    for field in exif_reader.fields() {
        match field.tag {
            exif::Tag::Make => {
                exif.camera_make = Some(field.display_value().to_string());
            }
            exif::Tag::Model => {
                exif.camera_model = Some(field.display_value().to_string());
            }
            exif::Tag::LensModel => {
                exif.lens_model = Some(field.display_value().to_string());
            }
            exif::Tag::FocalLength => {
                exif.focal_length = Some(field.display_value().to_string());
            }
            exif::Tag::FNumber => {
                exif.aperture = Some(field.display_value().to_string());
            }
            exif::Tag::ExposureTime => {
                exif.shutter_speed = Some(field.display_value().to_string());
            }
            exif::Tag::PhotographicSensitivity => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.iso = Some(v);
                }
            }
            exif::Tag::DateTimeOriginal | exif::Tag::DateTime => {
                let date_str = field.display_value().to_string();
                if !date_str.is_empty() {
                    exif.date_taken = Some(date_str);
                }
            }
            exif::Tag::ImageWidth => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.image_width = Some(v);
                }
            }
            exif::Tag::ImageLength => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.image_height = Some(v);
                }
            }
            _ => {}
        }
    }

    exif
}

/// Generate thumbnail to disk cache, return cached file path.
#[tauri::command]
pub async fn get_thumbnail_path(
    file_path: String,
    max_size: u32,
) -> Result<String, String> {
    thumb_single(&file_path, max_size)
}

/// Batch + stream thumbnails: sends each result via Channel as it completes.
/// Frontend renders thumbnails one-by-one for progressive loading UX.
#[tauri::command]
pub async fn batch_thumbnails(
    file_paths: Vec<String>,
    max_size: u32,
    on_progress: tauri::ipc::Channel<(String, String)>, // (source_path, cache_path)
) -> Result<(), String> {
    for path in &file_paths {
        if let Ok(cache_path) = thumb_single(path, max_size) {
            on_progress.send((path.clone(), cache_path)).ok();
        }
        // Rate limit: 5ms between COM calls to avoid overwhelming Explorer
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    Ok(())
}

/// Core: generate one thumbnail, with caching.
/// Windows: uses IShellItemImageFactory (same as Explorer) for instant system-cached thumbnails.
/// Fallback: zune-jpeg / embedded RAW preview / rawler.
fn thumb_single(file_path: &str, max_size: u32) -> Result<String, String> {
    let src = std::path::Path::new(file_path);
    if !src.exists() {
        return Err(format!("File not found: {}", file_path));
    }

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("thumbnails");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);

    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    file_path.hash(&mut h);
    mtime.hash(&mut h);

    let cache_name = format!("{:016x}_{}.jpg", h.finish(), max_size);
    let cache_path = cache_dir.join(&cache_name);

    if cache_path.exists() {
        return Ok(cache_path.to_string_lossy().to_string());
    }

    // Tier 1: Windows Shell (cached — 1ms; rate-limited to avoid Explorer load)
    #[cfg(target_os = "windows")]
    {
        if let Some((w, h, rgba)) = crate::win_thumb::get_shell_thumbnail(file_path, max_size) {
            if let Some(img) = image::RgbaImage::from_raw(w, h, rgba) {
                let dyn_img = image::DynamicImage::ImageRgba8(img);
                if dyn_img.save(&cache_path).is_ok() {
                    return Ok(cache_path.to_string_lossy().to_string());
                }
            }
        }
    }

    // Tier 2: Our optimized path
    let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();

    // Video: try ffmpeg frame extraction (IShellItemImageFactory handles cached ones above)
    if matches!(ext.as_str(), "mp4" | "mov" | "avi" | "mkv") {
        if let Some(img) = extract_video_frame(src) {
            let thumb = img.thumbnail(max_size, max_size);
            thumb.save(&cache_path).map_err(|e| format!("Save: {}", e))?;
            return Ok(cache_path.to_string_lossy().to_string());
        }
        return Err("No video thumbnail available".into());
    }

    let img = if RAW_EXTENSIONS.contains(&ext.as_str()) {
        // DNG: lossless JPEG in container → rawler handles it natively
        if ext == "dng" {
            decode_raw_slow(src)?
        } else {
            // Sony/Canon/Nikon/etc: standard JPEG previews in TIFF container
            extract_raw_preview(src)?
        }
    } else if ext == "jpg" || ext == "jpeg" {
        decode_jpeg_fast(src)?
    } else {
        image::open(src).map_err(|e| format!("Open: {}", e))?
    };

    let thumb = img.thumbnail(max_size, max_size);
    thumb.save(&cache_path).map_err(|e| format!("Save: {}", e))?;

    Ok(cache_path.to_string_lossy().to_string())
}


/// Fast JPEG decode using zune-jpeg (2x faster than image-rs default)
fn decode_jpeg_fast(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let data = std::fs::read(path).map_err(|e| format!("Read: {}", e))?;
    let mut decoder = zune_jpeg::JpegDecoder::new(&data);
    let pixels = decoder.decode().map_err(|e| format!("JPEG decode: {:?}", e))?;
    let info = decoder.info().ok_or("No JPEG info")?;
    image::RgbImage::from_raw(info.width as u32, info.height as u32, pixels)
        .map(image::DynamicImage::ImageRgb8)
        .ok_or_else(|| "Bad JPEG dimensions".into())
}

/// Extract embedded JPEG preview from RAW file — near-instant vs full sensor decode
fn extract_raw_preview(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let data = std::fs::read(path).map_err(|e| format!("Read RAW: {}", e))?;

    // Scan for JPEG markers in the file. Most RAW formats are TIFF containers
    // with embedded JPEG previews. DNG often uses lossless JPEG which image-rs
    // can't decode, so we try each candidate and fall back to rawler on failure.
    let mut jpeg_candidates: Vec<usize> = Vec::new();
    let mut i = 0;
    while i < data.len().saturating_sub(3) {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            jpeg_candidates.push(i);
        }
        i += 1;
    }

    // Try candidates from last to first (last JPEG is usually the largest preview)
    for &start in jpeg_candidates.iter().rev() {
        let jpeg_data = &data[start..];
        if let Ok(img) = image::load_from_memory(jpeg_data) {
            return Ok(img);
        }
        // JPEG failed (e.g. DNG lossless) — try next candidate
    }

    // No valid JPEG found → fall back to slow full RAW decode
    decode_raw_slow(path)
}

/// Slow fallback: full RAW sensor decode via rawler
fn decode_raw_slow(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let raw = rawler::decode_file(path).map_err(|e| format!("RAW: {}", e))?;
    let (w, h) = (raw.width as u32, raw.height as u32);
    if let rawler::RawImageData::Integer(data) = raw.data {
        let max = 65535u16;
        let mut rgb = vec![0u8; (w * h * 3) as usize];
        for (i, &pix) in data.iter().enumerate() {
            let v = ((pix as f32 / max as f32) * 255.0).clamp(0.0, 255.0) as u8;
            rgb[i * 3] = v;
            rgb[i * 3 + 1] = v;
            rgb[i * 3 + 2] = v;
        }
        image::RgbImage::from_raw(w, h, rgb)
            .map(image::DynamicImage::ImageRgb8)
            .ok_or_else(|| "Bad RGB buffer".into())
    } else {
        Err("Unsupported RAW format".into())
    }
}

/// Extract a frame from video using ffmpeg (if available)
fn extract_video_frame(path: &std::path::Path) -> Option<image::DynamicImage> {
    use std::process::Command;
    // Extract one frame at 1 second into the video as JPEG to stdout
    let output = Command::new("ffmpeg")
        .args([
            "-ss", "1",
            "-i", &path.to_string_lossy(),
            "-vframes", "1",
            "-f", "mjpeg",
            "-q:v", "2",
            "-loglevel", "quiet",
            "-",
        ])
        .output()
        .ok()?;

    if output.status.success() && !output.stdout.is_empty() {
        image::load_from_memory(&output.stdout).ok()
    } else {
        None
    }
}

/// Get cache dir for thumbnails
fn cache_dir() -> Option<std::path::PathBuf> {
    #[cfg(target_os = "windows")]
    {
        Some(std::path::PathBuf::from(
            std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into()),
        ))
    }
    #[cfg(not(target_os = "windows"))]
    {
        std::env::var("XDG_CACHE_HOME")
            .ok()
            .map(std::path::PathBuf::from)
            .or_else(|| {
                std::env::var("HOME").ok().map(|h| {
                    std::path::PathBuf::from(h).join(".cache")
                })
            })
    }
}

