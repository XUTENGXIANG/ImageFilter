use serde::Serialize;
use std::path::{Path, PathBuf};

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ImportProgress {
    pub file_name: String,
    pub status: String, // "copying", "verifying", "done", "skipped", "error"
    pub message: String,
    pub percent: u32,
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

    let file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return (year, month, day, camera),
    };

    let mut reader = std::io::BufReader::new(file);
    let exif_reader = match exif::Reader::new().read_from_container(&mut reader) {
        Ok(r) => r,
        Err(_) => return (year, month, day, camera),
    };

    for field in exif_reader.fields() {
        match field.tag {
            exif::Tag::DateTimeOriginal | exif::Tag::DateTime => {
                let d = field.display_value().to_string();
                if d.len() >= 10 {
                    year = d[0..4].to_string();
                    month = d[5..7].to_string();
                    day = d[8..10].to_string();
                }
            }
            exif::Tag::Model => {
                camera = sanitize_path(&field.display_value().to_string());
            }
            _ => {}
        }
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

/// Import: copy files with templates, verify, stream progress
#[tauri::command]
pub async fn import_photos(
    file_paths: Vec<String>,
    dest_dir: String,
    folder_template: String,
    file_template: String,
    custom_folder: String,
    on_progress: tauri::ipc::Channel<ImportProgress>,
) -> Result<u32, String> {
    let base_dir = if custom_folder.is_empty() {
        PathBuf::from(&dest_dir)
    } else {
        PathBuf::from(&dest_dir).join(sanitize_path(&custom_folder))
    };
    let mut imported: u32 = 0;

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
                percent: ((i as f64 / file_paths.len() as f64) * 100.0) as u32,
            })
            .ok();


        // Build destination path
        let (dest_path, dest_name) = build_dest_path(
            &folder_template, &file_template, src, imported + 1,
        );

        let full_dest = base_dir.join(&dest_path);

        // Create parent directories
        if let Some(parent) = full_dest.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                on_progress.send(ImportProgress {
                    file_name: file_name.clone(), status: "error".into(),
                    message: format!("创建目录失败: {}", e), percent: 0,
                }).ok();
                continue;
            }
        }

        // Copy
        on_progress.send(ImportProgress {
            file_name: file_name.clone(), status: "copying".into(),
            message: format!("复制中 → {}", dest_name), percent: 0,
        }).ok();

        if let Err(e) = std::fs::copy(src, &full_dest) {
            on_progress.send(ImportProgress {
                file_name: file_name.clone(), status: "error".into(),
                message: format!("复制失败: {}", e), percent: 0,
            }).ok();
            continue;
        }

        // Verify
        on_progress.send(ImportProgress {
            file_name: file_name.clone(), status: "verifying".into(),
            message: "校验中...".into(), percent: 0,
        }).ok();

        let src_hash = file_md5(src).unwrap_or_default();
        let dest_hash = file_md5(&full_dest).unwrap_or_else(|_| "DIFFER".into());

        if src_hash != dest_hash {
            on_progress.send(ImportProgress {
                file_name: file_name.clone(), status: "error".into(),
                message: "校验失败，文件不匹配".into(), percent: 0,
            }).ok();
            continue;
        }

        imported += 1;
        on_progress.send(ImportProgress {
            file_name: file_name.clone(), status: "done".into(),
            message: format!("完成 → {}", dest_name), percent: 0,
        }).ok();
    }

    Ok(imported)
}

