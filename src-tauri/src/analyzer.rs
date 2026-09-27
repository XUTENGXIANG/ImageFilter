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

/// 清晰度评估前先把图像归一化到这个长边。
///
/// 为什么必须归一化: 这个得分随像素规模漂移。同一张图实测(长边 → 拉普拉斯方差)
/// 512→139.5 / 768→175.9 / 1024→203.4 / 1536→534.1, 相差近 4 倍。
/// 而 `load_analysis_image` 对 JPEG 返回**原图**(可达 6000px)、对 RAW 返回**内嵌预览**
/// (各机型不同, 常见 1616px), 不归一化就没有一个阈值能同时适配两种情况。
const BLUR_ANALYSIS_EDGE: u32 = 1024;

/// 分块网格边长: 8 → 8x8 = 64 块(1024 长边时每块约 128x128 像素)
const SHARPNESS_BLOCK_GRID: u32 = 8;

/// 清晰度阈值(归一化到 1024 长边、再取 8x8 分块最大块方差后标定): 低于该值判为模糊。
///
/// 标定数据 —— 29 张真实照片(取自应用自身的预览/全图缓存) 与其高斯 sigma=6 软化版:
///   真实照片:          最小 122.4 / p10 188.4 / 中位数 407.6
///   软化后:            中位数 3.4 / p90 42.8 / 最大 57.6
/// 阈值 75 落在两簇之间(距最软的软图 1.30x, 距最糊的真图 1.63x): 该样本上
/// 真实照片误报 0/28, 软化图命中 28/28。
///
/// 为什么用"分块最大值"而不是全图方差: 全图方差会被大面积平滑背景拉低,
/// **大光圈虚化背景的人像会被误判成模糊** —— 而这类照片的主体其实是清晰的。
/// 取最大块方差等价于问"画面里有没有任何一块是清晰的", 这才符合初筛意图。
/// 实测分离度: 全图方差 1.08x(几乎无法分离) → 分块最大 2.12x。
/// 代价: 主体极小、只有一小块清晰区域时可能漏报(初筛场景可接受)。
/// 想更抗单块噪声可改为"前 4 块均值"(实测分离度 1.36x, 略低)。
const BLUR_THRESHOLD: f64 = 75.0;

/// 近重复判定的 Hamming 距离阈值(位): 两张图的感知哈希距离 ≤ 该值才归为同组。
///
/// 指标: `image_hasher` 的 `DoubleGradient` + `hash_size(16, 16)` = **256 位**,
/// 距离是纯 Hamming 位数(无条件、可解释), 取代了原先 imgfprint 的"条件均值 score"
/// —— 后者会把 Hamming 距离 >32/64 的块排除、不计入分母, 于是大面积平坦区
/// (天空/虚化背景)会把不同照片也推成高相似度。
///
/// 参考值: Czkawka 源码 `SIMILAR_VALUES` 的 hash 16 行是 `[2, 5, 15, 30, 40, 40]`
/// (六档相似度预设, 越小越严); 其 GUI 说明 hash 32/64 "几乎不会有误报"。
/// 本项目取偏严的一档: 初筛时**误标比漏标更烦人**(用户实测反馈"重复标记太多"),
/// 所以只把几乎同一张的连拍归组。
///
/// 实测标定(应用自身缓存的 28 张真实照片, 378 个两两组合):
///   同源(同一张照片的 preview vs full, 含分辨率/压缩差异): 距离 2/4/6/9/10/14/24
///   不同源: 最小 4/4/5/5/7/8/9..., 而真正不同的场景**全部 > 46 位**(断层明显)
///   阈值扫描 —— dist<=2: 误判 0/371; <=5: 4/371(1.1%); <=10: 11/371; <=30: 15/371
/// 注意"不同源"里距离 4~16 的那些其实是**同一场景的连拍**, 感知上确实几乎一样:
/// 本阈值只归并最接近的一小撮, 想更宽松(把同场景连拍也归组)可调到 8~10。
const DUPLICATE_MAX_DISTANCE: u32 = 5;

/// 拉普拉斯响应方差(纯计算, 便于单测直接喂合成像素): 值越大越锐, 平坦画面为 0。
///
/// 注意 `gray` 必须是**连续缓冲、行距等于 w**; 分块时由 [`block_sharpness`] 先拷成连续块。
///
/// 修复记录: 此前实现先求 `mean = 平均|拉普拉斯响应|`, 再求**灰度值相对该 mean 的方差**,
/// 即 `E[(gray - mean|L|)²]` —— 那衡量的是全局亮度离散度, 与对焦无关:
/// 实测同一张图原图得分 56569、高斯模糊 15px 后 57533(几乎不变), 连纯灰图都有 16384,
/// 而判定阈值是 100, 导致"模糊"标记在真实使用中永不出现。
fn laplacian_variance(gray: &[u8], w: u32, h: u32) -> f64 {
    let w = w as usize;
    let h = h as usize;
    // 过小图像没有完整的 3x3 邻域, 直接返回 0(不可判定), 避免 1..h-1 下溢/除零
    if w < 3 || h < 3 {
        return 0.0;
    }

    let mut sum = 0.0;
    let mut sum_sq = 0.0;
    let mut count = 0u64;

    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let idx = y * w + x;
            // 拉普拉斯响应 L = 4*中心 - 上下左右
            let lap = 4.0 * gray[idx] as f64
                - gray[idx - w] as f64
                - gray[idx + w] as f64
                - gray[idx - 1] as f64
                - gray[idx + 1] as f64;
            sum += lap;
            sum_sq += lap * lap;
            count += 1;
        }
    }

    if count == 0 {
        return 0.0;
    }
    let n = count as f64;
    let mean = sum / n;
    // Var(L) = E[L²] - E[L]²; 浮点误差可能给出极小负数, 夹到 0
    (sum_sq / n - mean * mean).max(0.0)
}

/// 分块清晰度: 切成 [`SHARPNESS_BLOCK_GRID`]² 块, 每块算拉普拉斯方差, 取**最大值**。
/// 图像过小时(< 3 * 网格)退化为整图方差。
fn block_sharpness(gray: &[u8], w: u32, h: u32) -> f64 {
    let grid = SHARPNESS_BLOCK_GRID;
    if w < 3 * grid || h < 3 * grid {
        return laplacian_variance(gray, w, h);
    }

    let stride = w as usize;
    let mut best = 0.0f64;
    let mut block: Vec<u8> = Vec::new();

    for by in 0..grid {
        let y0 = (by * h / grid) as usize;
        let y1 = ((by + 1) * h / grid) as usize;
        let bh = y1 - y0;
        for bx in 0..grid {
            let x0 = (bx * w / grid) as usize;
            let x1 = ((bx + 1) * w / grid) as usize;
            let bw = x1 - x0;
            if bw < 3 || bh < 3 {
                continue;
            }
            // 拷成连续块(约 16KB)再复用整图实现, 避免为带 stride 的切片再写一套卷积
            block.clear();
            for row in y0..y1 {
                let start = row * stride + x0;
                block.extend_from_slice(&gray[start..start + bw]);
            }
            let v = laplacian_variance(&block, bw as u32, bh as u32);
            if v > best {
                best = v;
            }
        }
    }
    best
}

/// 一张照片的分析结果(清晰度 + 曝光)
struct Metrics {
    sharpness: f64,
    overexposed: bool,
    underexposed: bool,
}

/// 解码后的图只做**一次**归一化 + **一次**灰度转换, 由清晰度与曝光共用。
///
/// 之前曝光是在**全尺寸**图上另做一次 `to_luma8()`: 对 24MP 原图等于多遍历 2400 万像素、
/// 多分配 24MB, 这是 AI 分析时 CPU 占用偏高的一处来源。
fn metrics_of(img: &image::DynamicImage) -> Metrics {
    // 用双线性(Triangle)而非 Lanczos: Lanczos 的振铃会凭空抬高高频能量, 让"锐度"虚高
    let normalized;
    let img = if img.width().max(img.height()) > BLUR_ANALYSIS_EDGE {
        normalized = img.resize(
            BLUR_ANALYSIS_EDGE,
            BLUR_ANALYSIS_EDGE,
            image::imageops::FilterType::Triangle,
        );
        &normalized
    } else {
        img
    };
    let gray = img.to_luma8();
    let (overexposed, underexposed) = exposure_check_luma(&gray);
    Metrics {
        sharpness: block_sharpness(gray.as_raw(), gray.width(), gray.height()),
        overexposed,
        underexposed,
    }
}

/// 仅取清晰度(单测/标定用)
#[cfg(test)]
fn sharpness_of(img: &image::DynamicImage) -> f64 {
    metrics_of(img).sharpness
}

/// 曝光判定的纯计算部分(analyzer 内部与单测共用)
fn exposure_check_luma(gray: &image::GrayImage) -> (bool, bool) {
    let (w, h) = gray.dimensions();
    let total = (w * h) as f64; // 尺寸为 0 时 total=0, 下面的比值会是 NaN → 两个判定都 false
    if total == 0.0 {
        return (false, false);
    }

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
    let m = metrics_of(&img);
    Some((
        m.sharpness,
        m.sharpness < BLUR_THRESHOLD,
        m.overexposed,
        m.underexposed,
    ))
}

/// Stop ongoing analysis
#[tauri::command]
pub fn stop_analysis() {
    ABORT.store(true, Ordering::SeqCst);
}

/// Batch analyze photos — 串行 + 可中止（每项检查 ABORT 标志）;
/// 解码与统计都是重 CPU / 阻塞 I/O, 整体放进 blocking 线程池, 不占 tokio worker
/// (否则分析期间会占住一个异步 worker, 影响 IPC 响应)。
#[tauri::command]
pub async fn analyze_photos(
    file_paths: Vec<String>,
    on_progress: tauri::ipc::Channel<AnalysisResult>,
) -> Result<(), String> {
    ABORT.store(false, Ordering::SeqCst);

    tokio::task::spawn_blocking(move || {
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
    })
    .await
    .map_err(|e| format!("分析任务失败: {}", e))?;

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

    // 限制并发: 之前裸 par_iter 会占满所有逻辑核(本机 16 线程), 笔记本上风扇起飞、整机卡顿。
    // 这里上限 4 个线程, 给 UI 与系统留出余量; 再往上收益会先撞上磁盘带宽。
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .clamp(2, 4);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(workers)
        .build()
        .map_err(|e| format!("线程池创建失败: {}", e))?;

    // 一张图只解码一次: 同一个 DynamicImage 既算感知哈希, 也算清晰度/曝光
    // (旧实现先用 imgfprint 解一次字节、再 load_analysis_image 解一次, 白跑一遍全量解码)
    struct Entry {
        hash: image_hasher::ImageHash,
        sharpness: f64,
        size: u64,
        exposure_ok: bool,
    }

    let hasher = image_hasher::HasherConfig::new()
        .hash_alg(image_hasher::HashAlg::DoubleGradient)
        .hash_size(16, 16)
        .to_hasher();

    let entries: Vec<Entry> = pool.install(|| {
        file_paths
            .par_iter()
            .filter_map(|path_str| {
                let path = Path::new(path_str);
                let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
                // RAW 走内嵌 JPEG 预览(image::open 不支持 RAW, 直接解码会静默失败)
                let img = crate::scanner::images::load_analysis_image(path)?;
                let hash = hasher.hash_image(&img);
                let m = metrics_of(&img);
                Some(Entry {
                    hash,
                    sharpness: m.sharpness,
                    exposure_ok: !m.overexposed && !m.underexposed,
                    size,
                })
            })
            .collect()
    });

    // 按文件大小分桶(±16KB 容差), 只比较同桶/相邻桶 → 数千张时从 O(n²) 降为近线性
    const BUCKET: u64 = 16384;
    let mut buckets: std::collections::BTreeMap<u64, Vec<usize>> = Default::default();
    for (i, e) in entries.iter().enumerate() {
        buckets.entry(e.size / BUCKET).or_default().push(i);
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

    // 组内两两比较: 感知哈希 Hamming 距离 ≤ DUPLICATE_MAX_DISTANCE 视为同组（连拍/重复）
    let mut groups: Vec<Vec<usize>> = Vec::new();
    let mut assigned = vec![false; entries.len()];

    for i in 0..entries.len() {
        if assigned[i] { continue; }
        let mut group = vec![i];
        for &j in &compare_set[i] {
            if assigned[j] { continue; }
            if entries[i].hash.dist(&entries[j].hash) <= DUPLICATE_MAX_DISTANCE {
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
        // 选最佳: 先看曝光是否正常, 同档内再取最锐的。
        // (旧实现只看单一"模糊分", 会在连拍组里把过曝/欠曝的那张当成最佳并打上绿色徽标)
        let best_idx = group
            .iter()
            .max_by(|&&a, &&b| {
                let ka = (entries[a].exposure_ok, entries[a].sharpness);
                let kb = (entries[b].exposure_ok, entries[b].sharpness);
                ka.partial_cmp(&kb).unwrap_or(std::cmp::Ordering::Equal)
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

#[cfg(test)]
mod tests {
    use super::*;

    /// 尺度无关的合成图: 固定条带数量的竖条纹(放大图像不会增加"每像素细节量")
    fn stripes(size: u32, count: u32) -> image::DynamicImage {
        let mut img = image::GrayImage::new(size, size);
        for (x, _y, p) in img.enumerate_pixels_mut() {
            let band = (x * count) / size;
            *p = image::Luma([if band % 2 == 0 { 30 } else { 220 }]);
        }
        image::DynamicImage::ImageLuma8(img)
    }

    fn temp_path(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("imagefilter_test_{}_{}.png", tag, std::process::id()))
    }

    #[test]
    fn laplacian_variance_is_zero_on_flat_image() {
        let flat = vec![128u8; 64 * 64];
        assert_eq!(laplacian_variance(&flat, 64, 64), 0.0);
    }

    /// 回归护栏: 旧实现衡量的是"灰度相对 mean|L| 的方差", 平滑渐变会得到巨大分数;
    /// 真正的拉普拉斯方差对无细节的线性渐变必须是 0。
    #[test]
    fn laplacian_variance_ignores_smooth_gradient() {
        let mut g = image::GrayImage::new(512, 256);
        for (_x, y, p) in g.enumerate_pixels_mut() {
            *p = image::Luma([y as u8]); // 垂直方向完美线性 0..255 → 拉普拉斯恒为 0
        }
        let img = image::DynamicImage::ImageLuma8(g);
        assert_eq!(sharpness_of(&img), 0.0);
    }

    #[test]
    fn laplacian_variance_drops_after_blur() {
        // 1px 棋盘: 拉普拉斯响应极大
        let mut sharp = image::GrayImage::new(64, 64);
        for (x, y, p) in sharp.enumerate_pixels_mut() {
            *p = image::Luma([if (x + y) % 2 == 0 { 0 } else { 255 }]);
        }
        let sharp_var = laplacian_variance(sharp.as_raw(), 64, 64);
        let blurred = image::imageops::blur(&sharp, 2.0);
        let blur_var = laplacian_variance(blurred.as_raw(), 64, 64);

        assert!(sharp_var > 100_000.0, "sharp_var={}", sharp_var);
        assert!(
            blur_var < sharp_var / 10.0,
            "blur_var={} sharp_var={}",
            blur_var,
            sharp_var
        );
    }

    #[test]
    fn laplacian_variance_handles_too_small_images() {
        assert_eq!(laplacian_variance(&[], 0, 0), 0.0);
        assert_eq!(laplacian_variance(&[1, 2, 3, 4], 2, 2), 0.0);
        assert_eq!(laplacian_variance(&[1, 2, 3], 3, 1), 0.0);
    }

    /// 归一化必须生效且幂等: 先手动缩到目标长边, 与让 sharpness_of 自己缩, 结果应一致。
    /// (这就是"阈值不随像素规模漂移"的保障 —— 实测同一内容不归一化时缩放 4 倍得分差 4 倍)
    #[test]
    fn sharpness_of_normalizes_long_edge() {
        let big = stripes(4096, 16);
        let pre = big.resize(
            BLUR_ANALYSIS_EDGE,
            BLUR_ANALYSIS_EDGE,
            image::imageops::FilterType::Triangle,
        );

        let auto = sharpness_of(&big);
        let manual = sharpness_of(&pre);
        assert!(
            (auto - manual).abs() < 1e-6 * auto.abs().max(1.0),
            "auto={} manual={}",
            auto,
            manual
        );
    }

    /// 端到端: 锐的图不能被标记模糊, 模糊的图必须被标记模糊(旧实现在这两种情况下都会给出"不模糊")
    #[test]
    fn analyze_single_flags_blurred_not_sharp() {
        let sharp_path = temp_path("sharp");
        let blur_path = temp_path("blur");
        let sharp = stripes(BLUR_ANALYSIS_EDGE, 16);
        sharp.save(&sharp_path).expect("save sharp");
        sharp.blur(6.0).save(&blur_path).expect("save blur");

        let (score_sharp, blurry_sharp, _, _) =
            analyze_single(&sharp_path).expect("analyze sharp");
        let (score_blur, blurry_blur, _, _) = analyze_single(&blur_path).expect("analyze blur");

        let _ = std::fs::remove_file(&sharp_path);
        let _ = std::fs::remove_file(&blur_path);

        assert!(!blurry_sharp, "锐图被误判模糊, score={}", score_sharp);
        assert!(blurry_blur, "模糊图未被判模糊, score={}", score_blur);
        assert!(
            score_sharp > score_blur * 10.0,
            "锐度对比不明显: sharp={} blur={}",
            score_sharp,
            score_blur
        );
    }

    /// 用户实际抱怨的场景: **主体清晰 + 背景大光圈虚化** 不能被判为模糊。
    /// 合成: 平滑渐变背景(模拟虚化, 无高频细节) + 中央一小块高频棋盘(主体, 仅占画面 4.6%)。
    /// 全图方差会被平滑背景拉低而误报, 分块取最大则能看出"有一块是清晰的"。
    #[test]
    fn sharp_subject_on_smooth_background_is_not_blurry() {
        let edge = BLUR_ANALYSIS_EDGE;
        let mut g = image::GrayImage::new(edge, edge);
        for (x, y, p) in g.enumerate_pixels_mut() {
            let on_subject = (400..620).contains(&x) && (400..620).contains(&y);
            *p = image::Luma([if on_subject {
                if (x + y) % 2 == 0 { 20 } else { 230 }
            } else {
                // 平滑渐变背景: 拉普拉斯响应恒为 0
                (40 + (x + y) / 16) as u8
            }]);
        }

        let img = image::DynamicImage::ImageLuma8(g);
        let score = sharpness_of(&img);
        assert!(
            score > BLUR_THRESHOLD,
            "主体清晰的虚化背景照片被误判为模糊, score={}",
            score
        );

        // 同内容整幅软化(连主体也糊了) → 必须判为模糊
        let soft = img.blur(6.0);
        let soft_score = sharpness_of(&soft);
        assert!(
            soft_score < BLUR_THRESHOLD,
            "整幅软化后仍未判为模糊, score={}",
            soft_score
        );
    }

    /// 感知哈希判定的基本性质: 完全相同的图距离为 0; 明显不同的图必须落在重复阈值之外;
    /// JPEG 轻微重压后的副本应比"明显不同的图"更接近原图(排序不变式)。
    #[test]
    fn duplicate_hamming_distance_orders_copies_below_threshold() {
        let hasher = image_hasher::HasherConfig::new()
            .hash_alg(image_hasher::HashAlg::DoubleGradient)
            .hash_size(16, 16)
            .to_hasher();

        let base = stripes(1024, 16);
        let identical = base.clone();

        // 明显不同的结构: 大块棋盘(与竖条纹的频率/方向都不同)
        let mut checker = image::GrayImage::new(1024, 1024);
        for (x, y, p) in checker.enumerate_pixels_mut() {
            *p = image::Luma([if (x / 32 + y / 32) % 2 == 0 { 20 } else { 235 }]);
        }
        let different = image::DynamicImage::ImageLuma8(checker);

        // 轻微重压的副本
        let mut jpeg_bytes: Vec<u8> = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg_bytes, 80)
            .encode_image(&base.to_luma8())
            .expect("编码 JPEG 失败");
        let recompressed = image::load_from_memory(&jpeg_bytes).expect("解码 JPEG 失败");

        let h_base = hasher.hash_image(&base);
        let d_identical = h_base.dist(&hasher.hash_image(&identical));
        let d_recompressed = h_base.dist(&hasher.hash_image(&recompressed));
        let d_different = h_base.dist(&hasher.hash_image(&different));

        assert_eq!(d_identical, 0, "完全相同的图距离应为 0");
        assert!(
            d_different > DUPLICATE_MAX_DISTANCE,
            "明显不同的图不应落入重复阈值(阈值可能过低): dist={}",
            d_different
        );
        assert!(
            d_recompressed < d_different,
            "重压副本({})应比明显不同的图({})更接近原图",
            d_recompressed,
            d_different
        );
    }

    /// 全图无任何清晰区域(纯色/纯渐变)必须判为模糊
    #[test]
    fn flat_and_gradient_images_are_blurry() {
        let flat = image::DynamicImage::ImageLuma8(image::GrayImage::from_pixel(
            512,
            512,
            image::Luma([128]),
        ));
        assert!(sharpness_of(&flat) < BLUR_THRESHOLD);

        let mut g = image::GrayImage::new(512, 512);
        for (x, y, p) in g.enumerate_pixels_mut() {
            *p = image::Luma([(40 + (x + y) / 16) as u8]);
        }
        let grad = image::DynamicImage::ImageLuma8(g);
        assert!(sharpness_of(&grad) < BLUR_THRESHOLD);
    }

    /// 取本机 image-filter 缓存里的样张(标定/计时辅助用, 不触碰原始照片库)
    fn calibration_files(subdirs: &[&str]) -> Vec<std::path::PathBuf> {
        let mut roots: Vec<std::path::PathBuf> = Vec::new();
        if let Ok(local) = std::env::var("LOCALAPPDATA") {
            roots.push(std::path::PathBuf::from(local).join("image-filter"));
        } else if let Ok(home) = std::env::var("HOME") {
            roots.push(std::path::PathBuf::from(home).join(".cache").join("image-filter"));
        }
        let mut files = Vec::new();
        for r in &roots {
            for sub in subdirs {
                if let Ok(rd) = std::fs::read_dir(r.join(sub)) {
                    for e in rd.flatten() {
                        let p = e.path();
                        if p.extension().and_then(|x| x.to_str()) == Some("jpg") {
                            files.push(p);
                        }
                    }
                }
            }
        }
        files.sort();
        files
    }

    /// 标定/计时辅助(默认忽略): 打印真实样张在新指标下的分布与单张分析耗时。
    /// debug 构建的耗时 ≈ 用户 `tauri dev` 的体感; 加 `--release` 看生产构建。
    ///
    /// 运行: cargo test --lib bench_analyze_cache -- --ignored --nocapture
    #[test]
    #[ignore = "calibration helper — 需要本机有 image-filter 缓存样本"]
    fn bench_analyze_cache() {
        let files = calibration_files(&["preview_v3", "full_v3"]);
        if files.is_empty() {
            println!("本机没有缓存样张, 跳过");
            return;
        }

        let mut scores: Vec<f64> = Vec::new();
        let start = std::time::Instant::now();
        for f in &files {
            let t = std::time::Instant::now();
            let r = analyze_single(f);
            let ms = t.elapsed().as_secs_f64() * 1000.0;
            let name = f.file_name().unwrap().to_string_lossy().to_string();
            match r {
                Some((score, blurry, over, under)) => {
                    println!(
                        "{:>8.1} ms  score={:>10.1}  blurry={:<5} over={:<5} under={:<5}  {}",
                        ms, score, blurry, over, under, name
                    );
                    scores.push(score);
                }
                None => println!("{:>8.1} ms  <无法分析>  {}", ms, name),
            }
        }
        let total = start.elapsed().as_secs_f64();
        println!(
            "\n合计 {} 张 / 总耗时 {:.2}s / 平均 {:.1} ms 每张",
            files.len(),
            total,
            total * 1000.0 / files.len() as f64
        );
        if !scores.is_empty() {
            scores.sort_by(|a, b| a.partial_cmp(b).unwrap());
            println!(
                "score 分布: 最小 {:.1} / p10 {:.1} / 中位 {:.1} / 最大 {:.1}",
                scores[0],
                scores[scores.len() / 10],
                scores[scores.len() / 2],
                scores[scores.len() - 1]
            );
            println!(
                "被判为模糊(<{}): {} / {}",
                BLUR_THRESHOLD,
                scores.iter().filter(|x| **x < BLUR_THRESHOLD).count(),
                scores.len()
            );
        }
    }

    /// 标定辅助(默认忽略): 打印 image_hasher(256bit DoubleGradient) 的 Hamming 距离分布,
    /// 用来确定 DUPLICATE_MAX_DISTANCE。样本取自应用自身的缓存目录(preview_v3 / full_v3),
    /// 同一张源照片的 preview 与 full 互为"正样本", 不同源照片互为"负样本"
    /// —— 后者里不少是同一场景的连拍, 属于"难负样本", 正好用来判断会不会过度分组。
    ///
    /// 运行: cargo test --lib calibrate_duplicate_thresholds -- --ignored --nocapture
    #[test]
    #[ignore = "calibration helper — 需要本机有 image-filter 缓存样本"]
    fn calibrate_duplicate_thresholds() {
        let files = calibration_files(&["preview_v3", "full_v3"]);
        println!("样本文件数: {}", files.len());

        let hasher = image_hasher::HasherConfig::new()
            .hash_alg(image_hasher::HashAlg::DoubleGradient)
            .hash_size(16, 16)
            .to_hasher();

        let mut hashes: Vec<(String, image_hasher::ImageHash)> = Vec::new();
        for f in &files {
            match crate::scanner::images::load_analysis_image(f) {
                Some(img) => {
                    let name = f.file_name().unwrap().to_string_lossy().to_string();
                    hashes.push((name, hasher.hash_image(&img)));
                }
                None => println!("  跳过无法解码的样本: {}", f.display()),
            }
        }
        println!("可用哈希数(256bit DoubleGradient): {}\n", hashes.len());

        let prefix = |name: &str| name.split('_').next().unwrap_or("").to_string();
        let (mut pos, mut neg) = (Vec::new(), Vec::new());
        for i in 0..hashes.len() {
            for j in (i + 1)..hashes.len() {
                let d = hashes[i].1.dist(&hashes[j].1);
                let same = prefix(&hashes[i].0) == prefix(&hashes[j].0);
                let row = (d, hashes[i].0.clone(), hashes[j].0.clone());
                if same { pos.push(row) } else { neg.push(row) }
            }
        }

        println!("== 同源(同一张照片的 preview vs full) {} 对 ==", pos.len());
        for r in &pos {
            println!("  dist={:>3}  {} | {}", r.0, r.1, r.2);
        }

        neg.sort_by_key(|r| r.0);
        println!(
            "\n== 不同源(应判为不重复) {} 对 — 距离最小的 20 对(即最容易被误判的) ==",
            neg.len()
        );
        for r in neg.iter().take(20) {
            println!("  dist={:>3}  {} | {}", r.0, r.1, r.2);
        }

        println!("\n阈值扫描(Hamming 位):");
        for thr in [0u32, 2, 5, 8, 10, 15, 20, 30, 40] {
            let fp = neg.iter().filter(|r| r.0 <= thr).count();
            let tp = pos.iter().filter(|r| r.0 <= thr).count();
            println!(
                "  dist<={:>3}: 不同源误判 {:>2}/{}   同源命中 {:>2}/{}",
                thr, fp, neg.len(), tp, pos.len()
            );
        }
    }

    /// 标定辅助(默认忽略): 计时目录递归计数(count_folders 的核心),
    /// 用于评估"切换设备触发全盘遍历"的成本。
    ///
    /// 运行: $env:IMAGEFILTER_COUNT_PATHS="C:\Windows\System32;C:\Program Files";
    ///       cargo test --lib bench_count_folder -- --ignored --nocapture
    #[test]
    #[ignore = "calibration helper — 需要指定 IMAGEFILTER_COUNT_PATHS"]
    fn bench_count_folder() {
        let Ok(paths) = std::env::var("IMAGEFILTER_COUNT_PATHS") else {
            println!("未设置 IMAGEFILTER_COUNT_PATHS, 跳过");
            return;
        };
        for p in paths.split(';').filter(|s| !s.trim().is_empty()) {
            let path = std::path::PathBuf::from(p.trim());
            if !path.is_dir() {
                println!("跳过(不是目录): {}", path.display());
                continue;
            }
            let t = std::time::Instant::now();
            let n = crate::scanner::browse::count_photos_recursive_for_bench(&path);
            println!(
                "{:>8.2}s  照片数={:<8} {}",
                t.elapsed().as_secs_f64(),
                n,
                path.display()
            );
        }
    }

    #[test]
    fn exposure_check_luma_uses_histogram_thresholds() {
        let over = image::GrayImage::from_pixel(100, 100, image::Luma([255]));
        assert_eq!(exposure_check_luma(&over), (true, false));

        let under = image::GrayImage::from_pixel(100, 100, image::Luma([0]));
        assert_eq!(exposure_check_luma(&under), (false, true));

        let normal = image::GrayImage::from_pixel(100, 100, image::Luma([128]));
        assert_eq!(exposure_check_luma(&normal), (false, false));

        // 小面积高光不应触发过曝(100x100 中 10x10=1% < 15%)
        let mut mostly_normal = image::GrayImage::from_pixel(100, 100, image::Luma([128]));
        for (x, y, p) in mostly_normal.enumerate_pixels_mut() {
            if x < 10 && y < 10 {
                *p = image::Luma([255]);
            }
        }
        assert_eq!(exposure_check_luma(&mostly_normal), (false, false));
    }
}
