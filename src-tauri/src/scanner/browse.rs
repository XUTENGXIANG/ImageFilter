use super::{FolderEntry, PhotoExif, RAW_EXTENSIONS, SUPPORTED_EXTENSIONS, ScannedPhoto};

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

/// Count ALL photos in directory tree.
/// 环检测: 目录 canonicalize 后入 visited 集合, 符号链接指向已访问目录时停止递归,
/// 避免 junction/symlink 循环导致无限递归栈溢出; 另有深度上限兜底。
fn count_photos_recursive(path: &std::path::Path) -> u32 {
    let mut visited = std::collections::HashSet::new();
    count_photos_recursive_inner(path, &mut visited, 0)
}

fn count_photos_recursive_inner(
    path: &std::path::Path,
    visited: &mut std::collections::HashSet<std::path::PathBuf>,
    depth: u32,
) -> u32 {
    if depth > 64 {
        return 0;
    }
    if let Ok(canon) = std::fs::canonicalize(path) {
        if !visited.insert(canon) {
            return 0; // 已访问过（符号链接环或重复引用）→ 停止
        }
    }

    let mut count: u32 = 0;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let ft = match entry.file_type() {
                Ok(t) => t,
                Err(_) => continue,
            };
            let file_path = entry.path();
            if ft.is_dir() || ft.is_symlink() {
                count += count_photos_recursive_inner(&file_path, visited, depth + 1);
            } else if ft.is_file() {
                if let Some(ext) = file_path.extension().and_then(|e| e.to_str()) {
                    if SUPPORTED_EXTENSIONS.contains(&ext.to_lowercase().as_str()) {
                        count += 1;
                    }
                }
            }
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

    photos.sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase()));
    photos
}
