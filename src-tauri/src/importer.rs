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

    // Folder: empty = no subfolder
    let folder = if folder_template.is_empty() {
        String::new()
    } else {
        sanitize_path(&folder_template
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
