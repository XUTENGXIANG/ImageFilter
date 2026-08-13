use rayon::prelude::*;
use serde::Serialize;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

static ABORT: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisResult {
    pub path: String,
    pub blur_score: f64,
    pub is_blurry: bool,
    pub is_overexposed: bool,
    pub is_underexposed: bool,
    pub duplicate_group: Option<u32>,
    pub is_best_in_group: bool,
}

/// Laplacian variance blur detection.
/// Score < 100 = blurry, 100-300 = soft, >300 = sharp.
fn laplacian_variance(gray: &[u8], w: u32, h: u32) -> f64 {
    let w = w as usize;
    let h = h as usize;
    // 过小图像没有完整的 3x3 邻域, 直接返回 0(不可判定), 避免 1..h-1 下溢/除零
    if w < 3 || h < 3 {
        return 0.0;
    }
    let mut sum = 0.0;
    let mut count = 0u64;

    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let idx = y * w + x;
            let center = gray[idx] as f64;
            let v = (4.0 * center
                - gray[idx - w] as f64
                - gray[idx + w] as f64
                - gray[idx - 1] as f64
                - gray[idx + 1] as f64)
                .abs();
            sum += v;
            count += 1;
        }
    }

    let mean = sum / count as f64;
    let var = gray
        .iter()
        .enumerate()
        .filter(|(i, _)| {
            let y = i / w;
            let x = i % w;
            y > 0 && y < h - 1 && x > 0 && x < w - 1
        })
        .map(|(i, _)| {
            let v = (gray[i] as f64 - mean).abs();
            v * v
        })
        .sum::<f64>()
        / count as f64;

    var
}

/// Exposure check via histogram. Returns (overexposed, underexposed).
fn exposure_check(img: &image::DynamicImage) -> (bool, bool) {
    let gray = img.to_luma8();
    let (w, h) = gray.dimensions();
    let total = (w * h) as f64;

    let mut highlights = 0u64;
    let mut shadows = 0u64;

    for pixel in gray.iter() {
        if *pixel > 250 {
            highlights += 1;
        }
        if *pixel < 5 {
            shadows += 1;
        }
    }

    let over = (highlights as f64 / total) > 0.15;
    let under = (shadows as f64 / total) > 0.30;
    (over, under)
}

/// Analyze single photo: blur + exposure
/// RAW 文件走内嵌 JPEG 提取（image::open 不支持 RAW, 直接解码会静默失败）
fn analyze_single(path: &Path) -> Option<(f64, bool, bool, bool)> {
    let img = crate::scanner::images::load_analysis_image(path)?;
    let gray = img.to_luma8();
    let (w, h) = gray.dimensions();
    let score = laplacian_variance(gray.as_raw(), w, h);
    let (over, under) = exposure_check(&img);
    Some((score, score < 100.0, over, under))
}

/// Stop ongoing analysis
#[tauri::command]
pub fn stop_analysis() {
    ABORT.store(true, Ordering::SeqCst);
}

/// Batch analyze photos — 串行 + 可中止（每项检查 ABORT 标志）;
/// 注意: 此处为串行循环（rayon 仅用于 find_duplicates 的并行指纹计算）
#[tauri::command]
pub async fn analyze_photos(
    file_paths: Vec<String>,
    on_progress: tauri::ipc::Channel<AnalysisResult>,
) -> Result<(), String> {
    ABORT.store(false, Ordering::SeqCst);

    for path_str in &file_paths {
        if ABORT.load(Ordering::Relaxed) { break; }
        let path = Path::new(path_str);
        let result = if let Some((score, blurry, over, under)) = analyze_single(path) {
            AnalysisResult {
                path: path_str.clone(), blur_score: score,
                is_blurry: blurry, is_overexposed: over, is_underexposed: under,
                duplicate_group: None, is_best_in_group: false,
            }
        } else {
            AnalysisResult {
                path: path_str.clone(), blur_score: 0.0,
                is_blurry: false, is_overexposed: false, is_underexposed: false,
                duplicate_group: None, is_best_in_group: false,
            }
        };
        on_progress.send(result).ok();
    }
    Ok(())
}

/// Find duplicate/burst groups via perceptual hash
#[tauri::command]
pub async fn find_duplicates(
    file_paths: Vec<String>,
) -> Result<Vec<AnalysisResult>, String> {
    if file_paths.len() < 2 {
        return Ok(vec![]);
    }

    // 计算每张照片的感知哈希（并行）+ 文件大小（用于分桶裁剪比较集）
    // RAW 走内嵌 JPEG 字节, 避免 imgfprint 无法解码 RAW 而静默丢失
    let entries: Vec<(String, imgfprint::MultiHashFingerprint, f64, u64)> = file_paths
        .par_iter()
        .filter_map(|path_str| {
            let path = Path::new(path_str);
            let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
            let data = crate::scanner::images::load_analysis_bytes(path)?;
            let fp = imgfprint::ImageFingerprinter::fingerprint(&data).ok()?;
            let blur = if let Some(img) = crate::scanner::images::load_analysis_image(path) {
                let gray = img.to_luma8();
                let (w, h) = gray.dimensions();
                laplacian_variance(gray.as_raw(), w, h)
            } else { 0.0 };
            Some((path_str.clone(), fp, blur, size))
        })
        .collect();

    // 按文件大小分桶(±16KB 容差), 只比较同桶/相邻桶 → 数千张时从 O(n²) 降为近线性
    const BUCKET: u64 = 16384;
    let mut buckets: std::collections::BTreeMap<u64, Vec<usize>> = Default::default();
    for (i, e) in entries.iter().enumerate() {
        buckets.entry(e.3 / BUCKET).or_default().push(i);
    }
    let mut compare_set: Vec<Vec<usize>> = vec![Vec::new(); entries.len()];
    for (&k, v) in &buckets {
        let mut cands: Vec<usize> = Vec::new();
        for kk in [k.saturating_sub(1), k, k + 1] {
            if let Some(x) = buckets.get(&kk) {
                cands.extend(x.iter().copied());
            }
        }
        cands.sort_unstable();
        cands.dedup();
        for &i in v {
            compare_set[i] = cands.iter().copied().filter(|&j| j > i).collect();
        }
    }

    // 组内两两比较: 相似度 > 0.85 视为同组（连拍/重复）
    let mut groups: Vec<Vec<usize>> = Vec::new();
    let mut assigned = vec![false; entries.len()];

    for i in 0..entries.len() {
        if assigned[i] { continue; }
        let mut group = vec![i];
        for &j in &compare_set[i] {
            if assigned[j] { continue; }
            let sim = entries[i].1.compare(&entries[j].1);
            if sim.score > 0.85 {
                group.push(j);
                assigned[j] = true;
            }
        }
        if group.len() > 1 {
            assigned[i] = true;
            groups.push(group);
        }
    }

    // Build results: mark best in each group
    let mut results: Vec<AnalysisResult> = file_paths
        .iter()
        .map(|p| AnalysisResult {
            path: p.clone(),
            blur_score: 0.0,
            is_blurry: false,
            is_overexposed: false,
            is_underexposed: false,
            duplicate_group: None,
            is_best_in_group: false,
        })
        .collect();

    for (gi, group) in groups.iter().enumerate() {
        let gi = gi as u32;
        // Find best (highest blur score = sharpest)
        let best_idx = group
            .iter()
            .max_by(|&&a, &&b| {
                entries[a].2.partial_cmp(&entries[b].2).unwrap_or(std::cmp::Ordering::Equal)
            })
            .copied()
            .unwrap_or(group[0]);

        for &idx in group {
            results[idx].duplicate_group = Some(gi);
            results[idx].is_best_in_group = idx == best_idx;
        }
    }

    Ok(results)
}
