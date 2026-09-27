use serde::Serialize;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ImportProgress {
    pub file_name: String,
    pub status: String, // "checking", "copying", "verifying", "done", "skipped", "error"
    pub message: String,
    pub percent: u32,
}

/// 单个文件的导入结果（由 spawn_blocking 任务返回）
struct ImportedFile {
    dest_path: PathBuf,
    file_name: String,
    hash: String,
    size: u64,
    skipped: bool, // 目标已存在且内容相同 → 跳过
}

/// Build destination path from template.
/// Variables: {date}, {camera}, {original}, {ext}, {year}, {month}, {day}
fn build_dest_path(
    folder_template: &str,
    file_template: &str,
    source_path: &Path,
    counter: u32,
) -> (PathBuf, String) {
    let ext = source_path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    let original = source_path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("unknown");

    let (year, month, day, camera) = get_exif_info(source_path);

    // Folder: empty = no subfolder (多级模板如 "{date}/{camera}" 会保留目录层级)
    let folder = if folder_template.is_empty() {
        String::new()
    } else {
        sanitize_template_path(&folder_template
            .replace("{date}", &format!("{}-{}-{}", year, month, day))
            .replace("{year}", &year)
            .replace("{month}", &month)
            .replace("{day}", &day)
            .replace("{camera}", &camera))
    };

    // File: empty = keep original name
    let file_name = if file_template.is_empty() {
        sanitize_path(&format!("{}.{}", original, ext))
    } else {
        sanitize_path(&file_template
            .replace("{date}", &format!("{}-{}-{}", year, month, day))
            .replace("{year}", &year)
            .replace("{month}", &month)
            .replace("{day}", &day)
            .replace("{camera}", &camera)
            .replace("{original}", original)
            .replace("{ext}", &ext)
            .replace("{seq}", &format!("{:04}", counter)))
    };

    let dest_path = if folder.is_empty() {
        PathBuf::from(&file_name)
    } else {
        PathBuf::from(&folder).join(&file_name)
    };
    (dest_path, file_name)
}

fn get_exif_info(path: &Path) -> (String, String, String, String) {
    let mut year = "0000".to_string();
    let mut month = "00".to_string();
    let mut day = "00".to_string();
    let mut camera = "Unknown".to_string();

    let date_str = crate::exif_common::first_text_field(
        path,
        &[exif::Tag::DateTimeOriginal, exif::Tag::DateTime],
    )
    .unwrap_or_default();
    if date_str.len() >= 10 {
        year = date_str[0..4].to_string();
        month = date_str[5..7].to_string();
        day = date_str[8..10].to_string();
    }

    if let Some(model) = crate::exif_common::first_text_field(path, &[exif::Tag::Model]) {
        camera = sanitize_path(&model);
    }

    (year, month, day, camera)
}

/// Remove characters illegal in Windows paths
fn sanitize_path(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            _ => c,
        })
        .collect::<String>()
        .trim()
        .replace(' ', "_")
}

/// 清洗"文件夹模板"的替换结果: 按 `/` 或 `\` 拆段, 每段单独清洗后用 `/` 重新连接。
///
/// 不能直接用 [`sanitize_path`]: 它会把手写的路径分隔符也替换成 `_`, 于是 UI 上同时勾选
/// "按日期 + 按相机"得到的 `{date}/{camera}` 会被压成单层目录 `2026-08-10_Sony_A7M4`,
/// 与界面提示(`如 2024-08-08/照片.jpg`)和 README 承诺的多级归档都不符。
/// 段内的非法字符仍由 [`sanitize_path`] 清掉; 空段(如 `a//b`)会被丢弃。
///
/// `..` 这类段不会被这里过滤 —— 留给 [`is_safe_relative`] 在复制前拒绝并报错, 不静默改写用户意图。
fn sanitize_template_path(template: &str) -> String {
    template
        .split(['/', '\\'])
        .map(sanitize_path)
        .filter(|segment| !segment.is_empty())
        .collect::<Vec<_>>()
        .join("/")
}

/// Compute MD5 hash of file
fn file_md5(path: &Path) -> Result<String, String> {
    use md5::{Digest, Md5};
    let mut file = std::fs::File::open(path).map_err(|e| format!("Open: {}", e))?;
    let mut hasher = Md5::new();
    std::io::copy(&mut file, &mut hasher).map_err(|e| format!("Read: {}", e))?;
    let hash = hasher.finalize();
    Ok(format!("{:x}", hash))
}

/// 检查 dest_path 是否逃出 base_dir（纵深防御 — 模板/EXIF 值经 sanitize 后
/// 不含路径分隔符，理论上无法逃逸，此处做最终断言防止未来改动引入回归）
fn is_safe_relative(dest_path: &Path) -> bool {
    dest_path.components().all(|c| {
        matches!(c, Component::Normal(_))
    })
}

/// 复制单个文件到目标（spawn_blocking 中执行阻塞 I/O）
/// 覆盖策略（防数据丢失）:
///   - 目标存在且内容相同 → skipped（不覆盖、不计数）
///   - 目标存在但内容不同 → 追加 _1/_2/... 唯一后缀, 绝不静默覆盖
fn copy_one(
    src: &Path,
    base_dir: &Path,
    dest_path: &Path,
) -> Result<ImportedFile, String> {
    let file_name = src
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("unknown")
        .to_string();

    if !is_safe_relative(dest_path) {
        return Err(format!("非法目标路径: {}", dest_path.display()));
    }

    let mut full_dest = base_dir.join(dest_path);

    // 目标已存在 → 哈希比对, 相同跳过 / 不同唯一命名
    if full_dest.exists() {
        let src_hash = file_md5(src).unwrap_or_default();
        let dest_hash = file_md5(&full_dest).unwrap_or_default();
        if !src_hash.is_empty() && src_hash == dest_hash {
            return Ok(ImportedFile {
                dest_path: dest_path.to_path_buf(),
                file_name,
                hash: src_hash,
                size: std::fs::metadata(&full_dest).map(|m| m.len()).unwrap_or(0),
                skipped: true,
            });
        }
        // 内容不同 → 生成唯一文件名, 不覆盖已有文件
        let folder = dest_path.parent();
        let stem = dest_path
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".into());
        let ext = dest_path
            .extension()
            .map(|e| e.to_string_lossy().to_string())
            .unwrap_or_default();
        let mut n: u32 = 1;
        loop {
            let cand_name = if ext.is_empty() {
                format!("{}_{}", stem, n)
            } else {
                format!("{}_{}.{}", stem, n, ext)
            };
            let cand = match folder {
                Some(f) => f.join(&cand_name),
                None => PathBuf::from(&cand_name),
            };
            if !base_dir.join(&cand).exists() {
                full_dest = base_dir.join(&cand);
                break;
            }
            n += 1;
            if n > 9999 {
                return Err("无法生成唯一文件名".into());
            }
        }
    }

    // 创建父目录
    if let Some(parent) = full_dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {}", e))?;
    }

    // 复制
    std::fs::copy(src, &full_dest).map_err(|e| format!("复制失败: {}", e))?;

    // 校验 (MD5 全量比对)
    let src_hash = file_md5(src).map_err(|e| format!("校验失败: {}", e))?;
    let dest_hash = file_md5(&full_dest).map_err(|e| format!("校验失败: {}", e))?;
    if src_hash != dest_hash {
        return Err("校验失败，文件不匹配".into());
    }

    let size = std::fs::metadata(&full_dest).map(|m| m.len()).unwrap_or(0);
    Ok(ImportedFile {
        dest_path: full_dest,
        file_name,
        hash: src_hash,
        size,
        skipped: false,
    })
}

/// Import: copy files with templates, verify, stream progress
#[tauri::command]
pub async fn import_photos(
    file_paths: Vec<String>,
    dest_dir: String,
    folder_template: String,
    file_template: String,
    custom_folder: String,
    on_progress: tauri::ipc::Channel<ImportProgress>,
    state: tauri::State<'_, crate::db::DbState>,
) -> Result<u32, String> {
    let base_dir = if custom_folder.is_empty() {
        PathBuf::from(&dest_dir)
    } else {
        PathBuf::from(&dest_dir).join(sanitize_path(&custom_folder))
    };
    let mut imported: u32 = 0;
    let total = file_paths.len().max(1);

    for (i, path_str) in file_paths.iter().enumerate() {
        let src = Path::new(path_str);
        let file_name = src
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("unknown")
            .to_string();

        on_progress
            .send(ImportProgress {
                file_name: file_name.clone(),
                status: "checking".into(),
                message: "检查中...".into(),
                percent: ((i as f64 / total as f64) * 100.0) as u32,
            })
            .ok();

        // Build destination path
        let (dest_path, dest_name) = build_dest_path(
            &folder_template, &file_template, src, imported + 1,
        );

        // 阻塞 I/O (复制 + 双端 MD5) 移入 spawn_blocking, 不占 tokio worker
        let src_owned = src.to_path_buf();
        let base_owned = base_dir.clone();
        let on_progress_copy = on_progress.clone();
        let fname_for_progress = file_name.clone();
        let outcome = match tokio::task::spawn_blocking(move || {
            let fname = fname_for_progress.clone();
            on_progress_copy
                .send(ImportProgress {
                    file_name: fname,
                    status: "copying".into(),
                    message: format!("复制中 → {}", dest_name),
                    percent: 0,
                })
                .ok();
            copy_one(&src_owned, &base_owned, &dest_path)
        })
        .await
        {
            Ok(Ok(f)) => f,
            Ok(Err(e)) => {
                on_progress
                    .send(ImportProgress {
                        file_name: file_name.clone(),
                        status: "error".into(),
                        message: e,
                        percent: 0,
                    })
                    .ok();
                continue;
            }
            Err(e) => {
                on_progress
                    .send(ImportProgress {
                        file_name: file_name.clone(),
                        status: "error".into(),
                        message: format!("任务失败: {}", e),
                        percent: 0,
                    })
                    .ok();
                continue;
            }
        };

        if outcome.skipped {
            on_progress
                .send(ImportProgress {
                    file_name: outcome.file_name.clone(),
                    status: "skipped".into(),
                    message: format!("已存在且相同 → {}", outcome.dest_path.display()),
                    percent: 0,
                })
                .ok();
            continue; // 不计数
        }

        // 校验通过 → 记录导入历史 (SQLite)
        if let Err(e) = sqlx::query(
            "INSERT INTO import_history (source_path, dest_path, file_hash, file_size) VALUES (?, ?, ?, ?)",
        )
        .bind(path_str)
        .bind(outcome.dest_path.to_string_lossy().to_string())
        .bind(&outcome.hash)
        .bind(outcome.size as i64)
        .execute(&state.pool)
        .await
        {
            eprintln!("import_history 写入失败: {}", e);
        }

        imported += 1;
        on_progress
            .send(ImportProgress {
                file_name: outcome.file_name.clone(),
                status: "done".into(),
                message: format!("完成 → {}", outcome.dest_path.display()),
                percent: 0,
            })
            .ok();
    }

    Ok(imported)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_root(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!("imagefilter_import_{}_{}", tag, std::process::id()))
    }

    #[test]
    fn sanitize_path_strips_illegal_chars_and_spaces() {
        assert_eq!(sanitize_path("Sony A7M4"), "Sony_A7M4");
        assert_eq!(sanitize_path("a/b\\c:d*e?f\"g<h>i|j"), "a_b_c_d_e_f_g_h_i_j");
        assert_eq!(sanitize_path("  trim me  "), "trim_me");
        assert_eq!(sanitize_path("正常名称"), "正常名称");
    }

    /// 文件夹模板必须保留目录层级, 只清洗段内非法字符
    #[test]
    fn sanitize_template_path_keeps_separators() {
        assert_eq!(sanitize_template_path("2024-08-08"), "2024-08-08");
        assert_eq!(sanitize_template_path("2024-08-08/Sony A7M4"), "2024-08-08/Sony_A7M4");
        assert_eq!(sanitize_template_path("a\\b"), "a/b");
        assert_eq!(sanitize_template_path("a//b/"), "a/b");
        assert_eq!(sanitize_template_path("a:b/c*d"), "a_b/c_d");
        assert_eq!(sanitize_template_path(""), "");
    }

    /// 回归护栏: UI 同时勾选"按日期 + 按相机"会生成 `{date}/{camera}`,
    /// 必须落成嵌套目录, 不能被压成单层 `日期_相机`
    #[test]
    fn build_dest_path_keeps_template_directory_levels() {
        let src = Path::new("photos").join("IMG_1234.ARW");
        let (dest, _) = build_dest_path("{date}/{camera}", "{seq}.{ext}", &src, 1);

        let parts: Vec<String> = dest
            .components()
            .map(|c| c.as_os_str().to_string_lossy().to_string())
            .collect();
        assert_eq!(parts, vec!["0000-00-00", "Unknown", "0001.arw"]);
    }

    #[test]
    fn is_safe_relative_only_accepts_plain_relative_paths() {
        assert!(is_safe_relative(Path::new("2026-08-10/Sony_A7M4/0001.arw")));
        assert!(is_safe_relative(Path::new("0001.arw")));
        assert!(!is_safe_relative(Path::new(".")));
        assert!(!is_safe_relative(Path::new("../escape.arw")));
        assert!(!is_safe_relative(Path::new("sub/../../escape.arw")));
        assert!(!is_safe_relative(Path::new("/abs/escape.arw")));
    }

    #[cfg(windows)]
    #[test]
    fn is_safe_relative_rejects_windows_absolute_path() {
        assert!(!is_safe_relative(Path::new("C:\\abs\\escape.arw")));
    }

    /// 无 EXIF 的文件(不存在)走默认值, 但模板替换/小写扩展名/序号补零必须正确
    #[test]
    fn build_dest_path_fills_templates_with_defaults_without_exif() {
        let src = Path::new("photos").join("IMG_1234.ARW");
        let (dest, name) =
            build_dest_path("{date}/{camera}", "{seq}_{original}.{ext}", &src, 7);

        assert_eq!(name, "0007_IMG_1234.arw");
        assert_eq!(
            dest,
            Path::new("0000-00-00").join("Unknown").join("0007_IMG_1234.arw")
        );
    }

    #[test]
    fn build_dest_path_keeps_original_name_when_templates_empty() {
        let src = Path::new("photos").join("IMG_1234.ARW");
        let (dest, name) = build_dest_path("", "", &src, 1);

        assert_eq!(name, "IMG_1234.arw");
        assert_eq!(dest, Path::new("IMG_1234.arw"));
    }

    #[test]
    fn file_md5_matches_known_digest() {
        let root = test_root("md5");
        std::fs::create_dir_all(&root).unwrap();
        let file = root.join("abc.txt");
        std::fs::write(&file, b"abc").unwrap();

        assert_eq!(
            file_md5(&file).unwrap(),
            "900150983cd24fb0d6963f7d28e17f72" // md5("abc")
        );

        let _ = std::fs::remove_dir_all(&root);
    }

    /// 数据安全契约: 目标相同内容 → 跳过不计数; 目标不同内容 → 唯一后缀, 绝不覆盖
    #[test]
    fn copy_one_skips_identical_and_never_overwrites_different() {
        let root = test_root("copy");
        let src_dir = root.join("src");
        let dest_dir = root.join("dest");
        std::fs::create_dir_all(&src_dir).unwrap();
        std::fs::create_dir_all(&dest_dir).unwrap();

        let src = src_dir.join("IMG_1.ARW");
        std::fs::write(&src, b"same-content").unwrap();
        let rel = PathBuf::from("sub").join("IMG_1.ARW");

        // 首次导入 → 正常复制并校验
        let first = copy_one(&src, &dest_dir, &rel).expect("首次复制应成功");
        assert!(!first.skipped);
        assert_eq!(std::fs::read(dest_dir.join("sub").join("IMG_1.ARW")).unwrap(), b"same-content");

        // 内容相同 → 跳过(不覆盖、不计数)
        let second = copy_one(&src, &dest_dir, &rel).expect("重复导入应跳过");
        assert!(second.skipped, "相同内容应判为 skipped");

        // 目标已存在但内容不同 → 生成 _1 后缀, 原文件必须保持原样
        std::fs::write(&src, b"different-content").unwrap();
        let third = copy_one(&src, &dest_dir, &rel).expect("不同内容应改名复制");
        assert!(!third.skipped);
        assert!(
            dest_dir.join("sub").join("IMG_1_1.ARW").exists(),
            "应生成唯一后缀文件"
        );
        assert_eq!(
            std::fs::read(dest_dir.join("sub").join("IMG_1.ARW")).unwrap(),
            b"same-content",
            "原有文件被改写了 — 违反绝不覆盖契约"
        );

        // 路径逃逸必须在触碰文件系统之前被拒绝
        assert!(copy_one(&src, &dest_dir, Path::new("../escape.ARW")).is_err());

        let _ = std::fs::remove_dir_all(&root);
    }
}
