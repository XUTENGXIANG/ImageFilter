use inspect_path::inspect_path;
use serde::Serialize;
use tokio::sync::Semaphore;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

/// rawler 全解码信号量 — 同时只允许1个, 防止切换照片时堆积卡死
static RAW_DECODE_SEM: OnceLock<Semaphore> = OnceLock::new();
fn raw_decode_sem() -> &'static Semaphore {
    RAW_DECODE_SEM.get_or_init(|| Semaphore::new(1))
}

/// 全图解码任务号 — 新请求递增; 旧请求检测到被取代即放弃
static FULL_TASK_ID: AtomicU64 = AtomicU64::new(0);

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

/// Get Windows volume label for a drive (e.g., "CANON_DC" for D:\)
fn volume_label(mount: &str) -> String {
    #[cfg(target_os = "windows")]
    {
        use std::ffi::OsString;
        use std::os::windows::ffi::OsStringExt;
        let path_str = format!("{}\\\\", mount);
        let path: Vec<u16> = path_str.encode_utf16().chain(std::iter::once(0)).collect();
        let mut buf = vec![0u16; 128];
        #[link(name = "kernel32")]
        extern "system" {
            fn GetVolumeInformationW(
                root: *const u16,
                name: *mut u16, name_len: u32,
                serial: *mut u32, max_len: *mut u32, flags: *mut u32,
                fs_name: *mut u16, fs_len: u32,
            ) -> i32;
        }
        unsafe {
            if GetVolumeInformationW(path.as_ptr(), buf.as_mut_ptr(), buf.len() as u32,
                std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
                std::ptr::null_mut(), 0) != 0
            {
                let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
                return OsString::from_wide(&buf[..len]).to_string_lossy().to_string();
            }
        }
    }
    #[cfg(not(target_os = "windows"))]
    { String::new() }
    String::new()
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

/// 安全弹出可移动设备 — 完整 IOCTL 序列 (与资源管理器"弹出"一致)
/// CreateFile(GENERIC_READ|GENERIC_WRITE) → LOCK → DISMOUNT → MEDIA_REMOVAL → EJECT
#[tauri::command]
pub fn eject_drive(mount_point: String) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        use std::ffi::OsStr;
        use std::os::windows::ffi::OsStrExt;
        use windows::core::PCWSTR;

        // 卷路径: "D:" → "\\\\.\\D:"
        let letter = mount_point.trim_end_matches('\\');
        let vol_path = format!("\\\\.\\{}", letter);
        let wide: Vec<u16> = OsStr::new(&vol_path).encode_wide().chain(Some(0)).collect();

        // GENERIC_READ | GENERIC_WRITE (必须, 否则IOCTL失败 ERROR_INVALID_FUNCTION)
        let handle = unsafe {
            windows::Win32::Storage::FileSystem::CreateFileW(
                PCWSTR::from_raw(wide.as_ptr()),
                (windows::Win32::Foundation::GENERIC_READ | windows::Win32::Foundation::GENERIC_WRITE).0,
                windows::Win32::Storage::FileSystem::FILE_SHARE_READ
                    | windows::Win32::Storage::FileSystem::FILE_SHARE_WRITE,
                None,
                windows::Win32::Storage::FileSystem::OPEN_EXISTING,
                windows::Win32::Storage::FileSystem::FILE_ATTRIBUTE_NORMAL,
                None,
            )
        }
        .map_err(|e| format!("打开卷失败: {}", e))?;
        if handle.is_invalid() {
            return Err(format!("打开卷失败: 句柄无效"));
        }

        let mut ret: u32 = 0;
        let mut ioctl = |code: u32| -> bool {
            unsafe {
                windows::Win32::System::IO::DeviceIoControl(
                    handle,
                    code,
                    None,
                    0,
                    None,
                    0,
                    Some(&mut ret),
                    None,
                )
            }
            .is_ok()
        };

        // 1. 锁卷
        if !ioctl(0x0009_0018) { unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); } return Err("锁卷失败（设备被占用?）".into()); }
        // 2. 卸载卷
        if !ioctl(0x0009_0020) { unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); } return Err("卸载卷失败".into()); }
        // 3. 禁用移除保护
        let _ = ioctl(0x002D_4804); // IOCTL_STORAGE_MEDIA_REMOVAL (失败忽略)
        // 4. 弹出介质
        let ejected = ioctl(0x002D_4808); // IOCTL_STORAGE_EJECT_MEDIA

        unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); };

        if ejected {
            Ok(())
        } else {
            Err("弹出失败（设备可能不支持热插拔）".into())
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        let _ = mount_point;
        Err("仅支持 Windows".into())
    }
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
            let vol_name = volume_label(&mount);
            let label = if vol_name.is_empty() {
                format!("{}{}", mount, if is_removable { " (可移动)" } else { "" })
            } else {
                format!("{} ({}){}", vol_name, &mount[..1], if is_removable { " 可移动" } else { "" })
            };

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

/// Check if directory contains any subdirectory (1 level only, fast)
fn has_subdirectories(path: &std::path::Path) -> bool {
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            if let Ok(ft) = entry.file_type() {
                if ft.is_dir() { return true; }
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
        let modified_at = metadata
            .modified()
            .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as i64)
            .unwrap_or(0);

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
            modified_at,
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

/// 快速预览: RAW 提取内嵌最大 JPEG（秒开）; 非RAW 直接原路径
#[tauri::command]
pub async fn get_preview_image(file_path: String) -> Result<String, String> {
    let src = std::path::Path::new(&file_path);
    if !src.exists() {
        return Err(format!("File not found: {}", file_path));
    }

    let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    if !RAW_EXTENSIONS.contains(&ext.as_str()) {
        return Ok(file_path);
    }

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("preview");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    use std::hash::{Hash, Hasher};
    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);
    let mut h = std::collections::hash_map::DefaultHasher::new();
    file_path.hash(&mut h);
    mtime.hash(&mut h);
    let cache_path = cache_dir.join(format!("{:016x}_prev.jpg", h.finish()));

    if cache_path.exists() {
        return Ok(cache_path.to_string_lossy().to_string());
    }

    // 提取内嵌最大 JPEG 字节（mmap扫描+直接写盘, 零解码零编码, 毫秒级）
    if let Some(bytes) = extract_largest_preview_bytes(src) {
        if std::fs::write(&cache_path, &bytes).is_ok() {
            return Ok(cache_path.to_string_lossy().to_string());
        }
    }

    // 无内嵌 → 返回空, 前端走 full（full 有 WIC/rawler 完整链路）
    Err("No preview available".into())
}

/// Get full-resolution image for viewer.
/// JPEG/PNG → returns original path (zero decode, instant).
/// RAW → WIC 系统codec → rawler 全解码, cached to disk as JPEG.
#[tauri::command]
pub async fn get_full_image(file_path: String) -> Result<String, String> {
    // 领取任务号 — 切换照片后旧任务检测到被取代即退出
    let my_id = FULL_TASK_ID.fetch_add(1, Ordering::SeqCst) + 1;

    let src = std::path::Path::new(&file_path);
    if !src.exists() {
        return Err(format!("File not found: {}", file_path));
    }

    let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();

    // Standard formats: direct path, zero decode
    if !RAW_EXTENSIONS.contains(&ext.as_str()) {
        return Ok(file_path);
    }

    // RAW: 优先提取最大内嵌JPEG（多数相机=全分辨率,秒开）
    // 无内嵌或太小才全解码
    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("full_v2");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    use std::hash::{Hash, Hasher};
    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);
    let mut h = std::collections::hash_map::DefaultHasher::new();
    file_path.hash(&mut h);
    mtime.hash(&mut h);
    let cache_name = format!("{:016x}_full.jpg", h.finish());
    let cache_path = cache_dir.join(&cache_name);

    if cache_path.exists() {
        return Ok(cache_path.to_string_lossy().to_string());
    }

    let src_path = std::path::PathBuf::from(&file_path);
    let cache_path_clone = cache_path.clone();
    let is_dng = ext == "dng";

    // ═══ DNG 独立路径: tinydng优先 → WIC → rawler 兜底 ═══
    if is_dng {
        // 1. tinydng 解码器 (优先, 支持lossless JPEG, 完整demosaic)
        {
            let fp = file_path.clone();
            let cc = cache_path_clone.clone();
            if tokio::task::spawn_blocking(move || {
                if let Some(img) = crate::tinydng::decode_dng_tinydng(&fp) {
                    let max_edge = 5000u32;
                    let img = if img.width().max(img.height()) > max_edge {
                        img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
                    } else {
                        img
                    };
                    if img.save(&cc).is_ok() {
                        return Some(cc.to_string_lossy().to_string());
                    }
                }
                None
            })
            .await
            .unwrap_or(None)
            .is_some()
            {
                return Ok(cache_path_clone.to_string_lossy().to_string());
            }
        }
        // 2. Windows WIC 解码 (系统支持时, 300ms级)
        #[cfg(target_os = "windows")]
        {
            let fp = file_path.clone();
            let cc = cache_path_clone.clone();
            if tokio::task::spawn_blocking(move || {
                if let Some(img) = crate::win_wic::decode_raw_wic(&fp) {
                    let max_edge = 5000u32;
                    let img = if img.width().max(img.height()) > max_edge {
                        img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
                    } else {
                        img
                    };
                    if img.save(&cc).is_ok() {
                        return Some(cc.to_string_lossy().to_string());
                    }
                }
                None
            })
            .await
            .unwrap_or(None)
            .is_some()
            {
                return Ok(cache_path_clone.to_string_lossy().to_string());
            }
        }
        // 2. rawler 全分辨率解码 (兜底)
        tokio::task::spawn_blocking(move || {
            let img = decode_raw_slow(&src_path)?;
            let max_edge = 5000u32;
            let img = if img.width().max(img.height()) > max_edge {
                img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
            } else {
                img
            };
            img.save(&cache_path_clone).map_err(|e| format!("Save: {}", e))?;
            Ok::<(), String>(())
        })
        .await
        .map_err(|e| format!("Join: {}", e))??;
        return Ok(cache_path.to_string_lossy().to_string());
    }

    // ═══ 其他RAW: 内嵌(≥3000px) → WIC → rawler ═══
    // 路径1: 内嵌最大 JPEG（≥3000px 才算高清, 否则继续解码）
    if let Some((w, h, bytes)) = extract_largest_preview_full(&src_path) {
        if w.max(h) >= 3000 {
            if std::fs::write(&cache_path_clone, &bytes).is_ok() {
                return Ok(cache_path_clone.to_string_lossy().to_string());
            }
        }
    }

    // 路径2: 全图解码统一信号量(并发1) — 防磁盘IO竞争
    if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
        return Err("superseded".into());
    }
    let _permit = raw_decode_sem().acquire().await.map_err(|e| e.to_string())?;
    if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
        return Err("superseded".into());
    }

    // 路径2a: WIC 系统 codec（300ms级）
    #[cfg(target_os = "windows")]
    {
        let fp = file_path.clone();
        let cc = cache_path_clone.clone();
        if tokio::task::spawn_blocking(move || {
            if let Some(img) = crate::win_wic::decode_raw_wic(&fp) {
                let max_edge = 5000u32;
                let img = if img.width().max(img.height()) > max_edge {
                    img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
                } else {
                    img
                };
                if img.save(&cc).is_ok() {
                    return Some(cc.to_string_lossy().to_string());
                }
            }
            None
        })
        .await
        .unwrap_or(None)
        .is_some()
        {
            return Ok(cache_path_clone.to_string_lossy().to_string());
        }
    }

    // 路径2b: rawler 全分辨率解码（非DNG, 正常支持）
    tokio::task::spawn_blocking(move || {
        let img = decode_raw_slow(&src_path)?;
        let max_edge = 5000u32;
        let img = if img.width().max(img.height()) > max_edge {
            img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
        } else {
            img
        };
        img.save(&cache_path_clone).map_err(|e| format!("Save: {}", e))?;
        Ok::<(), String>(())
    })
    .await
    .map_err(|e| format!("Join: {}", e))??;

    Ok(cache_path.to_string_lossy().to_string())
}

/// 提取RAW中分辨率最大的内嵌JPEG（返回尺寸+原始字节, mmap零解码）
fn extract_largest_preview_full(path: &std::path::Path) -> Option<(u32, u32, Vec<u8>)> {
    let file = std::fs::File::open(path).ok()?;
    let map = unsafe { memmap2::Mmap::map(&file).ok()? };
    let data = &map[..];

    let mut best: Option<(u32, u32, usize, usize)> = None;

    let mut i = 0;
    while i + 3 < data.len() {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            // 找 JPEG 结束标记 EOI (FF D9)
            let mut j = i + 2;
            let mut eoi = None;
            while j + 1 < data.len() {
                if data[j] == 0xFF && data[j + 1] == 0xD9 { eoi = Some(j + 2); break; }
                j += 1;
            }
            if let Some(end) = eoi {
                // 解析 SOF 段拿宽高
                if let Some((w, h)) = jpeg_dimensions(&data[i..end]) {
                    if best.as_ref().map(|(bw, bh, _, _)| w * h > bw * bh).unwrap_or(true) {
                        best = Some((w, h, i, end));
                    }
                }
                i = end;
                continue;
            }
        }
        i += 1;
    }

    let (w, h, start, end) = best?;
    Some((w, h, data[start..end].to_vec()))
}

/// 预览用: 任意内嵌JPEG都返回（>=600px即可, 宁可小图不空白）
fn extract_largest_preview_bytes(path: &std::path::Path) -> Option<Vec<u8>> {
    let (w, h, bytes) = extract_largest_preview_full(path)?;
    if w.max(h) < 600 { return None; }
    Some(bytes)
}

/// 从 JPEG 字节解析宽高（找 SOF0/SOF2 段）
fn jpeg_dimensions(data: &[u8]) -> Option<(u32, u32)> {
    let mut i = 2; // 跳过 SOI
    while i + 4 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        // SOF0=0xC0, SOF2=0xC2 (部分相机用渐进)
        if marker == 0xC0 || marker == 0xC2 {
            if i + 9 < data.len() {
                let h = u16::from_be_bytes([data[i + 5], data[i + 6]]) as u32;
                let w = u16::from_be_bytes([data[i + 7], data[i + 8]]) as u32;
                return Some((w, h));
            }
        }
        // 跳过段（段长在 marker 后两个字节）
        let len = u16::from_be_bytes([data[i + 2], data[i + 3]]) as usize;
        if len < 2 { return None; }
        i += 2 + len;
    }
    None
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

    // Optimized thumbnail path:
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
        // 所有RAW先提取内嵌JPEG预览（含DNG）
        extract_raw_preview(src)?
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
        // 安全处理: 数据长度可能与 w*h 不完全匹配(带padding), 取最小
        let expected = (w as usize) * (h as usize);
        let n = expected.min(data.len());
        let mut rgb = vec![0u8; n * 3];
        for (i, &pix) in data[..n].iter().enumerate() {
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

