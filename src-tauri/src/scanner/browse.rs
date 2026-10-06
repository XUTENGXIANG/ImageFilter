// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
use super::{FolderEntry, PhotoExif, RAW_EXTENSIONS, SUPPORTED_EXTENSIONS, ScannedPhoto};
use std::sync::atomic::{AtomicU64, Ordering};

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

/// 后台计数的"代次" — 每次 count_folders 领一个新的; 旧任务在遍历中检测到已被取代就立即退出。
///
/// 为什么需要: 连续切换设备会连续触发计数, 而每个计数都是**整棵子树的递归遍历**。
/// 实测(本机) C:\Program Files 3.1s、C:\Users 18.5s —— 不取消的话, 连点几下设备
/// 就是好几个整盘遍历并发跑, CPU 直接打满(实测该进程跑到约 5 个核心)。
static COUNT_GENERATION: AtomicU64 = AtomicU64::new(0);

/// 本次遍历是否已被更新的计数请求取代
fn count_superseded(my_gen: u64) -> bool {
    COUNT_GENERATION.load(Ordering::Relaxed) != my_gen
}

/// 这个路径所在的卷是否"顶层就是整个盘"(固定磁盘/网络盘)。
///
/// 这类卷不做后台递归计数: 本机 C:\ 有 18 个顶层目录, 点一下设备等于遍历整块盘
/// (实测 20~30 秒满载一个核心), 而系统目录的"照片数"本身没有参考价值。
/// 可移动卡是 DCIM 那种结构、规模有限, 照旧计数(这正是"哪个文件夹有照片"的用法)。
#[cfg(target_os = "windows")]
fn is_bulk_volume(path: &std::path::Path) -> bool {
    // 关键: inspect_path 底层是 GetDriveTypeW, 而它**只接受卷根**, 传子目录会返回
    // DRIVE_NO_ROOT_DIR(inspect_path 把它 map 成 Err)。而前端传进来的恰恰是设备根下的
    // 子目录(如 C:\Windows), 所以必须先归到卷根再判断 —— 否则这个守卫永远不生效。
    let root = path
        .ancestors()
        .filter(|p| !p.as_os_str().is_empty())
        .last()
        .unwrap_or(path);
    inspect_path::inspect_path(root)
        .map(|info| info.is_fixed() || info.is_remote())
        .unwrap_or(false)
}

#[cfg(not(target_os = "windows"))]
fn is_bulk_volume(_path: &std::path::Path) -> bool {
    false // macOS/Linux 挂载卷由用户插入, 子树规模有限
}

/// Background: count photos for given paths, returns map of path→count
///
/// 说明: 递归遍历是阻塞 I/O + CPU, 整体放进 blocking 线程池(不占 tokio worker),
/// 并且逐目录检查是否被更近的一次设备切换取代。
#[tauri::command]
pub async fn count_folders(
    folder_paths: Vec<String>,
) -> Result<std::collections::HashMap<String, u32>, String> {
    // 领一个代次: 比它旧的遍历会在下面检测到并被取消
    let my_gen = COUNT_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;

    tokio::task::spawn_blocking(move || {
        let mut map = std::collections::HashMap::new();
        for p in &folder_paths {
            if count_superseded(my_gen) {
                break; // 已被更近的一次设备切换取代 → 不再继续
            }
            let path = std::path::Path::new(p);
            if is_bulk_volume(path) {
                continue; // 固定/网络盘跳过, 避免整盘遍历
            }
            map.insert(p.clone(), count_photos_recursive(path, my_gen));
        }
        map
    })
    .await
    .map_err(|e| format!("计数任务失败: {}", e))
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
fn count_photos_recursive(path: &std::path::Path, my_gen: u64) -> u32 {
    let mut visited = std::collections::HashSet::new();
    count_photos_recursive_inner(path, &mut visited, 0, my_gen)
}

/// 标定用入口(仅测试构建): 让 bench 能直接计时递归计数
#[cfg(test)]
pub(crate) fn count_photos_recursive_for_bench(path: &std::path::Path) -> u32 {
    let gen = COUNT_GENERATION.load(Ordering::SeqCst);
    count_photos_recursive(path, gen)
}

fn count_photos_recursive_inner(
    path: &std::path::Path,
    visited: &mut std::collections::HashSet<std::path::PathBuf>,
    depth: u32,
    my_gen: u64,
) -> u32 {
    if depth > 64 || count_superseded(my_gen) {
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
            if count_superseded(my_gen) {
                return count; // 被取代: 立刻收手, 不做无用的剩余遍历
            }
            let ft = match entry.file_type() {
                Ok(t) => t,
                Err(_) => continue,
            };
            let file_path = entry.path();
            if ft.is_dir() || ft.is_symlink() {
                count += count_photos_recursive_inner(&file_path, visited, depth + 1, my_gen);
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

#[cfg(test)]
mod tests {
    use super::*;

    /// 下面两个测试共用全局计数代次, 必须串行, 否则会互相顶掉代次导致 flaky
    static GEN_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn temp_tree(tag: &str) -> std::path::PathBuf {
        let root = std::env::temp_dir()
            .join(format!("imagefilter_count_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("a").join("b")).unwrap();
        std::fs::write(root.join("a").join("one.jpg"), b"x").unwrap();
        std::fs::write(root.join("a").join("b").join("two.arw"), b"x").unwrap();
        std::fs::write(root.join("a").join("b").join("notes.txt"), b"x").unwrap(); // 非照片
        root
    }

    #[test]
    fn count_photos_recursive_counts_supported_files_only() {
        let _guard = GEN_LOCK.lock().unwrap();
        let root = temp_tree("ok");
        let gen = COUNT_GENERATION.load(Ordering::SeqCst);
        assert_eq!(count_photos_recursive(&root, gen), 2, "只应统计受支持的扩展名");
        let _ = std::fs::remove_dir_all(&root);
    }

    /// 回归护栏: 连续切换设备时, 被更新的请求取代的遍历必须**立刻停手** ——
    /// 否则多个整盘递归遍历并发跑会把 CPU 打满(用户实际反馈的问题)。
    #[test]
    fn count_photos_recursive_aborts_when_superseded() {
        let _guard = GEN_LOCK.lock().unwrap();
        let root = temp_tree("superseded");

        let stale_gen = COUNT_GENERATION.load(Ordering::SeqCst);
        // 模拟"用户又切了一次设备": 代次前进 → 旧 gen 作废
        COUNT_GENERATION.fetch_add(1, Ordering::SeqCst);
        assert_eq!(
            count_photos_recursive(&root, stale_gen),
            0,
            "被取代的遍历应立即返回, 不做无用功"
        );

        // 最新的一次请求仍能正常计数
        let fresh_gen = COUNT_GENERATION.load(Ordering::SeqCst);
        assert_eq!(count_photos_recursive(&root, fresh_gen), 2);

        let _ = std::fs::remove_dir_all(&root);
    }

    /// 固定磁盘(顶层就是整个盘)必须被识别出来, 从而跳过递归计数。
    /// **注意子目录**: 前端传给 count_folders 的是设备根下的子目录(C:\Windows),
    /// 而底层 GetDriveTypeW 只认卷根 —— 这个断言就是为了锁住"必须先归到卷根"。
    #[cfg(windows)]
    #[test]
    fn is_bulk_volume_detects_fixed_disk() {
        assert!(is_bulk_volume(std::path::Path::new("C:\\")));
        assert!(is_bulk_volume(std::path::Path::new("C:\\Windows")));
        assert!(is_bulk_volume(std::path::Path::new("C:\\Windows\\System32")));
    }

    /// 端到端: 固定磁盘的目录必须被 count_folders 跳过(返回空 map)。
    /// 否则点一下设备就会开始整盘递归遍历 —— 实测 C:\Windows 0.46s、C:\Users 18.5s,
    /// 连续切换设备时多个遍历并发跑, CPU 直接打满(用户实际反馈的问题)。
    #[cfg(windows)]
    #[tokio::test]
    async fn count_folders_skips_fixed_volume() {
        let map = count_folders(vec!["C:\\Windows".to_string(), "C:\\Users".to_string()])
            .await
            .expect("count_folders 不应报错");
        assert!(
            map.is_empty(),
            "固定磁盘目录应被跳过, 实际返回了计数: {:?}",
            map
        );
    }
}
