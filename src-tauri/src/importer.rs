use serde::Serialize;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ImportProgress {
    pub file_name: String,
    /// "checking"(检查) / "copying"(复制) / "renamed"(重名改名) / "verifying"(MD5 校验)
    /// / "done" / "skipped" / "error" / "sidecar"(Phase 5 的边车行)
    /// —— 前端 import-bar 对未知状态走默认样式, 所以新增状态不会渲染出错
    pub status: String,
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
    /// Phase 6: 目标同名但内容不同 → 走了 `_1/_2/...` 唯一名(照片**确实导入成功**了,
    /// 所以它是 `ImportSummary::imported` 的**子集**, 与 skipped/failed 并列互斥)。
    /// 只有 [`copy_one_reporting`] 知道这件事 —— 别在外面拿"计划名 != 实际名"反推:
    /// copy_one 在 skipped 分支返回的是**相对**路径、成功分支返回**绝对**路径。
    renamed: bool,
    /// Phase 5: 一并复制过来的 XMP 边车(没有源边车/目标已一致 → None)
    sidecar: Option<PathBuf>,
    /// Phase 5: 边车没复制成功的原因。**不是致命的** —— 照片已经复制好了,
    /// 只作为一行进度透出(边车冲突/失败不该让整张照片算导入失败)。
    sidecar_error: Option<String>,
}

/// Phase 6 · 导入结果统计(契约变更: `import_photos` 从 `u32` 改成这个结构体)。
///
/// 口径(前端直接显示, **不许再自己算**, 老前端就是用 `paths.len() - count` 把"跳过"
/// 算成了"失败"):
///   · `imported` = 真的复制进归档的张数(**含** `renamed`);
///   · `renamed`  = 其中"目标同名但内容不同、被改名成 `_1`"的张数(⊆ imported);
///   · `skipped`  = 目标已存在且内容相同(既不覆盖也不计数, 但**不是失败**);
///   · `failed`   = 复制/校验/任务失败;
///   · 不变式: `imported + skipped + failed == file_paths.len()`。
#[derive(Debug, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportSummary {
    pub imported: u32,
    pub skipped: u32,
    pub renamed: u32,
    pub failed: u32,
}

/// 导入过程中的计数累积。单独成结构是为了能单测(命令体拿不到 Channel/State):
/// 最要紧的两条 —— "跳过不算失败" 与 "renamed 是 imported 的子集"。
#[derive(Default)]
struct ImportTally {
    imported: u32,
    skipped: u32,
    renamed: u32,
    failed: u32,
}

impl ImportTally {
    /// 一次"没报错"的结果: skipped 记跳过, 其余记成功(renamed 时再记一次改名)
    fn record(&mut self, f: &ImportedFile) {
        if f.skipped {
            self.skipped += 1;
            return;
        }
        self.imported += 1;
        if f.renamed {
            self.renamed += 1;
        }
    }

    /// 一次失败(复制/校验/任务失败)
    fn record_error(&mut self) {
        self.failed += 1;
    }

    fn summary(self) -> ImportSummary {
        ImportSummary {
            imported: self.imported,
            skipped: self.skipped,
            renamed: self.renamed,
            failed: self.failed,
        }
    }
}

/// `{seq}` 的编号 = **输入顺序位次**(1-based), 与这一张成功/跳过/失败**无关**。
///
/// 老实现传 `imported + 1`: 跳过或失败会让后面的编号整体前移, 编号与拍摄顺序错位
/// (比"跳号"更糟)。做成函数而不是内联 `i + 1`, 是为了让"跳过也递增"有一条单测钉住。
fn seq_for(index: usize) -> u32 {
    index as u32 + 1
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

// ── Phase 5 · XMP 边车 ───────────────────────────────────────────────
// 边车命名规则与 src/xmp.rs 的 sidecar_candidates 必须一致(两处都写死同一套规则,
// 各自有单测钉住)。这里只做"复制", 不改内容, 不解析。

/// 边车源候选: ① `stem.xmp`(RAW 约定, 也是 ImageFilter 写的那份)
/// ② `file_name.xmp`(Lightroom 对非 RAW 的写法)。取第一个存在的。
fn sidecar_source(src: &Path) -> Option<PathBuf> {
    let stem = src.file_stem()?.to_string_lossy().to_string();
    let full = src.file_name()?.to_string_lossy().to_string();
    let dir = src.parent()?;
    [format!("{}.xmp", stem), format!("{}.xmp", full)]
        .into_iter()
        .map(|n| dir.join(n))
        .find(|p| p.is_file())
}

/// 边车目标 = **照片最终目标路径**换扩展名。
///
/// 关键: 不重新跑命名模板, 也不对边车独立跑唯一化后缀逻辑 —— 照片被改名成
/// `0007_IMG_1234_1.arw` 时, 边车必须跟着变成 `0007_IMG_1234_1.xmp`;
/// 若对边车自己跑一遍 `_n` 逻辑, 会出现"照片叫 `_1`、边车还叫原名"这种配错对。
fn sidecar_dest(photo_dest: &Path) -> PathBuf {
    photo_dest.with_extension("xmp")
}

/// 复制边车。返回:
///   `Ok(Some(dest))` = 复制了; `Ok(None)` = 没有源边车 / 目标已一致(不重复复制);
///   `Err(...)` = 归档里已有同名但内容不同(**绝不覆盖**, 用户自己的东西优先)或复制失败。
/// 三种结果都不影响照片本身的导入结果。
fn copy_sidecar(
    photo_src: &Path,
    photo_dest: &Path,
    base_dir: &Path,
) -> Result<Option<PathBuf>, String> {
    let Some(src) = sidecar_source(photo_src) else {
        return Ok(None);
    };
    let dest = sidecar_dest(photo_dest);
    // 纵深防御: 边车目标必须落在归档目录内(它由目标路径派生, 理论上必然)
    if !dest.starts_with(base_dir) {
        return Err("边车目标越出归档目录".into());
    }
    if dest.exists() {
        let sh = file_md5(&src).unwrap_or_default();
        let dh = file_md5(&dest).unwrap_or_default();
        if !sh.is_empty() && sh == dh {
            return Ok(None);
        }
        return Err("归档中已有同名 .xmp(内容不同), 未覆盖".into());
    }
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {}", e))?;
    }
    std::fs::copy(&src, &dest).map_err(|e| format!("边车复制失败: {}", e))?;
    let sh = file_md5(&src).unwrap_or_default();
    let dh = file_md5(&dest).unwrap_or_default();
    if sh.is_empty() || sh != dh {
        return Err("边车校验失败".into());
    }
    Ok(Some(dest))
}

/// 边车结果作为一行进度透出(状态 "sidecar": import-bar 对未知状态走默认样式,
/// 不会渲染出错)。Rust 侧进度文案本来就是中文, 与既有 checking/copying 一致。
fn report_sidecar(ch: &tauri::ipc::Channel<ImportProgress>, f: &ImportedFile) {
    if let Some(p) = &f.sidecar {
        ch.send(ImportProgress {
            file_name: f.file_name.clone(),
            status: "sidecar".into(),
            message: format!("边车 → {}", p.display()),
            percent: 0,
        })
        .ok();
    }
    if let Some(e) = &f.sidecar_error {
        ch.send(ImportProgress {
            file_name: f.file_name.clone(),
            status: "sidecar".into(),
            message: format!("边车未复制: {}", e),
            percent: 0,
        })
        .ok();
    }
}

/// copy_one 内部值得单独透出的两个步骤。用枚举而不是字符串 —— 状态名写错是编译期能拦住的。
#[derive(Debug, PartialEq, Clone)]
enum CopyStep {
    /// 目标同名但内容不同 → 正在生成 `_1/_2/...` 唯一名(绝不覆盖)。
    /// **带上真正生成的那个名字**: 计划名(`0002.jpg`)正是被占用的那个, 报给人看只会误导
    /// —— 实机首测就是因此显示成"重名, 改名 → 0002.jpg"而磁盘上是 `0002_1.jpg`。
    Renamed(String),
    /// 复制已完成, 正在做双端 MD5 校验("校验中"这一步在 UI 上要看得见 —— 它是卖点,
    /// 也是大文件时唯一能解释"为什么卡住"的进度)
    Verifying,
}

/// 复制单个文件到目标（spawn_blocking 中执行阻塞 I/O）
/// 覆盖策略（防数据丢失）:
///   - 目标存在且内容相同 → skipped（不覆盖、不计数）
///   - 目标存在但内容不同 → 追加 _1/_2/... 唯一后缀, 绝不静默覆盖
///
/// 签名保持不动(单测与既有调用点零改动), 需要进度的一方走 [`copy_one_reporting`]。
fn copy_one(
    src: &Path,
    base_dir: &Path,
    dest_path: &Path,
) -> Result<ImportedFile, String> {
    copy_one_reporting(src, base_dir, dest_path, &mut |_| {})
}

/// [`copy_one`] 的带步骤回调版本。
///
/// 为什么用回调而不是把 `Channel` 传进来: 这样 `copy_one` 的签名与它那三条数据安全
/// 单测(跳过/不覆盖/防逃逸)一个字都不用改, 而"步骤顺序"本身变成可单测的。
fn copy_one_reporting(
    src: &Path,
    base_dir: &Path,
    dest_path: &Path,
    on_step: &mut dyn FnMut(CopyStep),
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
    let mut renamed = false;

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
                renamed: false,
                sidecar: None,
                sidecar_error: None,
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
                renamed = true;
                on_step(CopyStep::Renamed(cand_name));
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

    // 校验 (MD5 全量比对) —— **先报步骤再算**: 一张 60MB RAW 的双端 MD5 约 1 秒,
    // 算完再报等于没报
    on_step(CopyStep::Verifying);
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
        renamed,
        sidecar: None,
        sidecar_error: None,
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
) -> Result<ImportSummary, String> {
    let base_dir = if custom_folder.is_empty() {
        PathBuf::from(&dest_dir)
    } else {
        PathBuf::from(&dest_dir).join(sanitize_path(&custom_folder))
    };
    let mut tally = ImportTally::default();
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

        // Build destination path。序号 = 输入顺序位次(见 seq_for): 跳过/失败也递增
        let (dest_path, dest_name) = build_dest_path(
            &folder_template, &file_template, src, seq_for(i),
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
            // 步骤回调: 把 copy_one 内部"改了什么名 / 开始校验"透成进度消息
            let mut on_step = |step: CopyStep| {
                let (status, message) = match step {
                    // 用 copy_one 真正生成的那个名字(不是被占用的计划名)
                    CopyStep::Renamed(new_name) => ("renamed", format!("重名, 改名 → {}", new_name)),
                    CopyStep::Verifying => ("verifying", "校验中...".to_string()),
                };
                on_progress_copy
                    .send(ImportProgress {
                        file_name: fname_for_progress.clone(),
                        status: status.into(),
                        message,
                        percent: 0,
                    })
                    .ok();
            };
            let mut f = copy_one_reporting(&src_owned, &base_owned, &dest_path, &mut on_step)?;
            // Phase 5: 边车一并复制(docs §5.3)。**照片 skipped 时也要走这一步** ——
            // 目标照片已存在且相同, 但归档里可能还没有边车, 不补就把决策丢了。
            // copy_one 在 skipped 分支返回的是**相对**路径, 所以这里先归一成绝对路径。
            let photo_dest_abs = if f.dest_path.is_absolute() {
                f.dest_path.clone()
            } else {
                base_owned.join(&f.dest_path)
            };
            match copy_sidecar(&src_owned, &photo_dest_abs, &base_owned) {
                Ok(Some(p)) => f.sidecar = Some(p),
                Ok(None) => {}
                Err(e) => f.sidecar_error = Some(e),
            }
            Ok::<ImportedFile, String>(f)
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
                tally.record_error();
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
                tally.record_error();
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
            report_sidecar(&on_progress, &outcome);
            tally.record(&outcome); // 记"跳过" —— 老前端把这一档算成了失败
            continue;
        }

        report_sidecar(&on_progress, &outcome);

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

        tally.record(&outcome); // 成功(含"改过名"的子集)
        on_progress
            .send(ImportProgress {
                file_name: outcome.file_name.clone(),
                status: "done".into(),
                message: format!("完成 → {}", outcome.dest_path.display()),
                percent: 0,
            })
            .ok();
    }

    Ok(tally.summary())
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

    // ── Phase 5 · XMP 边车 ──────────────────────────────────────────
    /// 源候选与 xmp.rs 的 sidecar_candidates 必须一致: stem 优先, full-name 兜底
    #[test]
    fn sidecar_source_prefers_stem_then_full_name() {
        let root = test_root("sidecar_src");
        let src_dir = root.join("src");
        std::fs::create_dir_all(&src_dir).unwrap();
        let photo = src_dir.join("IMG_1.ARW");
        std::fs::write(&photo, b"raw").unwrap();

        assert!(sidecar_source(&photo).is_none());
        let stem = src_dir.join("IMG_1.xmp");
        let full = src_dir.join("IMG_1.ARW.xmp");
        std::fs::write(&full, b"full").unwrap();
        assert_eq!(sidecar_source(&photo).unwrap(), full);
        std::fs::write(&stem, b"stem").unwrap();
        assert_eq!(sidecar_source(&photo).unwrap(), stem);

        let _ = std::fs::remove_dir_all(&root);
    }

    /// 边车目标必须跟着**照片最终名字**走(照片被唯一化改名时也要跟)
    #[test]
    fn sidecar_dest_follows_photo_dest_name() {
        let dest = Path::new("2026-08-10")
            .join("Sony_A7M4")
            .join("0007_IMG_1234.arw");
        assert_eq!(
            sidecar_dest(&dest),
            Path::new("2026-08-10")
                .join("Sony_A7M4")
                .join("0007_IMG_1234.xmp")
        );
        assert_eq!(
            sidecar_dest(Path::new("0007_IMG_1234_1.arw")),
            Path::new("0007_IMG_1234_1.xmp")
        );
    }

    /// 数据安全契约: 缺则复制; 已一致则跳过; 内容不同**绝不覆盖**
    #[test]
    fn copy_sidecar_copies_skips_and_never_overwrites() {
        let root = test_root("sidecar_copy");
        let src_dir = root.join("src");
        let dest_dir = root.join("dest");
        std::fs::create_dir_all(&src_dir).unwrap();
        std::fs::create_dir_all(&dest_dir).unwrap();

        let photo = src_dir.join("IMG_1.ARW");
        std::fs::write(&photo, b"raw").unwrap();
        std::fs::write(src_dir.join("IMG_1.xmp"), b"rating-4").unwrap();
        let dest_photo = dest_dir.join("0001_IMG_1.arw");
        std::fs::write(&dest_photo, b"raw").unwrap();

        // 首次 → 复制, 并按照片的名字改名
        let copied = copy_sidecar(&photo, &dest_photo, &dest_dir)
            .unwrap()
            .unwrap();
        assert_eq!(copied, dest_dir.join("0001_IMG_1.xmp"));
        assert_eq!(std::fs::read(&copied).unwrap(), b"rating-4");

        // 内容相同 → 跳过(不重复复制)
        assert!(copy_sidecar(&photo, &dest_photo, &dest_dir)
            .unwrap()
            .is_none());

        // 归档里已有同名但内容不同 → 报错且**一个字都不改**(用户的东西优先)
        std::fs::write(&copied, b"user-edited").unwrap();
        std::fs::write(src_dir.join("IMG_1.xmp"), b"rating-5").unwrap();
        assert!(copy_sidecar(&photo, &dest_photo, &dest_dir).is_err());
        assert_eq!(std::fs::read(&copied).unwrap(), b"user-edited");

        // 源没有边车 → None(照片照常导入)
        let other = src_dir.join("IMG_2.ARW");
        std::fs::write(&other, b"raw").unwrap();
        assert!(copy_sidecar(&other, &dest_dir.join("0002_IMG_2.arw"), &dest_dir)
            .unwrap()
            .is_none());

        // 目标越出归档目录 → 拒绝(纵深防御)
        let outside = root.join("elsewhere").join("0001_IMG_1.arw");
        assert!(copy_sidecar(&photo, &outside, &dest_dir).is_err());

        let _ = std::fs::remove_dir_all(&root);
    }

    // ── Phase 6 · 导入结果统计 / {seq} 计数 / 进度步骤 ────────────────

    fn fake_imported(skipped: bool, renamed: bool) -> ImportedFile {
        ImportedFile {
            dest_path: PathBuf::from("a.jpg"),
            file_name: "a.jpg".into(),
            hash: "h".into(),
            size: 1,
            skipped,
            renamed,
            sidecar: None,
            sidecar_error: None,
        }
    }

    /// 三条最要紧的计数口径:
    ///   ① **跳过不算失败**(老前端用 `paths.len() - count` 就是这么算错的);
    ///   ② renamed 是 imported 的**子集**(不能被当成第四类重复计数);
    ///   ③ imported + skipped + failed == 输入张数(前端不许再自己算)。
    #[test]
    fn import_tally_separates_skipped_renamed_and_failed() {
        let mut tally = ImportTally::default();
        tally.record(&fake_imported(false, false)); // 普通成功
        tally.record(&fake_imported(false, true)); // 改名成功
        tally.record(&fake_imported(true, false)); // 已存在且相同 → 跳过
        tally.record_error(); // 失败

        let s = tally.summary();
        assert_eq!(
            (s.imported, s.skipped, s.renamed, s.failed),
            (2, 1, 1, 1),
            "计数口径变了: imported 含 renamed, skipped 绝不算 failed"
        );
        assert!(s.renamed <= s.imported, "renamed 必须是 imported 的子集");
        assert_eq!(
            s.imported + s.skipped + s.failed,
            4,
            "三类必须覆盖全部输入(renamed 不另算一类)"
        );
    }

    /// `{seq}` = 输入顺序位次: 中间那张跳过/失败, 后面的编号**不前进**
    /// (老实现传 `imported + 1`, 第 3 张会拿到 0002, 与拍摄顺序错位)
    #[test]
    fn seq_follows_input_order_even_when_middle_is_skipped() {
        let src = Path::new("photos").join("IMG_1234.ARW");
        let names: Vec<String> = (0..3)
            .map(|i| build_dest_path("", "{seq}.{ext}", &src, seq_for(i)).1)
            .collect();
        assert_eq!(names, vec!["0001.arw", "0002.arw", "0003.arw"]);
        assert_eq!(seq_for(2), 3, "第 3 张的位次必须是 3, 与成功数无关");
    }

    /// 进度步骤: 首次复制只报"校验中"; 同名不同内容先报"改名"再报"校验中";
    /// 内容相同(跳过)不报步骤 —— 跳过判定的双端 MD5 **刻意不报**, 免得一次导入
    /// 出现两行"校验中"而被当成 bug。
    #[test]
    fn copy_one_reports_renamed_then_verifying() {
        let root = test_root("steps");
        let src_dir = root.join("src");
        let dest_dir = root.join("dest");
        std::fs::create_dir_all(&src_dir).unwrap();
        std::fs::create_dir_all(&dest_dir).unwrap();

        let src = src_dir.join("IMG_9.ARW");
        std::fs::write(&src, b"one").unwrap();
        let rel = PathBuf::from("IMG_9.ARW");

        let mut steps = Vec::new();
        copy_one_reporting(&src, &dest_dir, &rel, &mut |s| steps.push(s)).unwrap();
        assert_eq!(steps, vec![CopyStep::Verifying], "首次复制不该报改名");

        std::fs::write(&src, b"two").unwrap();
        let mut steps2 = Vec::new();
        copy_one_reporting(&src, &dest_dir, &rel, &mut |s| steps2.push(s)).unwrap();
        assert_eq!(
            steps2,
            vec![
                CopyStep::Renamed("IMG_9_1.ARW".to_string()),
                CopyStep::Verifying
            ],
            "同名不同内容必须按 改名 → 校验 的顺序报, 且改名带的是**真正生成的名字**"
        );
        assert!(dest_dir.join("IMG_9_1.ARW").exists());

        std::fs::write(&src, b"one").unwrap();
        let mut steps3 = Vec::new();
        copy_one_reporting(&src, &dest_dir, &rel, &mut |s| steps3.push(s)).unwrap();
        assert!(steps3.is_empty(), "跳过时不该报步骤(否则会出现两行校验中)");

        let _ = std::fs::remove_dir_all(&root);
    }
}
