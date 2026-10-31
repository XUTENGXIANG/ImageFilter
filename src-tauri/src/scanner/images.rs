// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
use super::RAW_EXTENSIONS;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::sync::Semaphore;

/// rawler 全解码信号量 — 同时只允许1个, 防止切换照片时堆积卡死
static RAW_DECODE_SEM: OnceLock<Semaphore> = OnceLock::new();
fn raw_decode_sem() -> &'static Semaphore {
    RAW_DECODE_SEM.get_or_init(|| Semaphore::new(1))
}

/// 全图解码任务号 — 新请求递增; 旧请求检测到被取代即放弃
static FULL_TASK_ID: AtomicU64 = AtomicU64::new(0);

const FULL_MIN_EDGE: u32 = 1500;

fn cache_hash(file_path: &str, mtime_secs: u64) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    file_path.hash(&mut h);
    mtime_secs.hash(&mut h);
    h.finish()
}

/// 缩放到长边不超过 `max_edge`。**只降不升** —— 放大只会把插值糊出来的像素
/// 当成真实分辨率交给调用方(手机端会以为拿到了 2048 的细节)。
pub(crate) fn resize_max_edge(img: image::DynamicImage, max_edge: u32) -> image::DynamicImage {
    if img.width().max(img.height()) > max_edge {
        img.resize(max_edge, max_edge, image::imageops::FilterType::Lanczos3)
    } else {
        img
    }
}

fn is_full_res_image(img: &image::DynamicImage) -> bool {
    img.width().max(img.height()) >= FULL_MIN_EDGE
}

fn is_full_res_cache(path: &std::path::Path) -> bool {
    image::image_dimensions(path)
        .map(|(w, h)| w.max(h) >= FULL_MIN_EDGE)
        .unwrap_or(false)
}

fn save_full_res_image(
    img: image::DynamicImage,
    source_path: &std::path::Path,
    cache_path: &std::path::Path,
) -> Result<(), String> {
    let img = apply_exif_orientation(source_path, img);
    if !is_full_res_image(&img) {
        return Err("decode returned low resolution".into());
    }
    let img = resize_max_edge(img, 5000);
    img.save(cache_path).map_err(|e| format!("Save: {}", e))
}

fn save_jpeg_quality(
    img: &image::DynamicImage,
    path: &std::path::Path,
    quality: u8,
) -> Result<(), String> {
    let file = std::fs::File::create(path).map_err(|e| format!("Create: {}", e))?;
    encode_jpeg_quality(img, file, quality)
}

/// 把 JPEG 编进任意 `Write`（服务端要编进内存, 而不是落盘）。
/// 抽出来的目的只有一个: **编码参数只允许有一处**——否则服务端另写一套,
/// 就会出现"手机上的预览比电脑上的缩略图还糙"这种找不到原因的差异。
pub(crate) fn encode_jpeg_quality<W: std::io::Write>(
    img: &image::DynamicImage,
    w: W,
    quality: u8,
) -> Result<(), String> {
    let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(w, quality);
    img.write_with_encoder(encoder)
        .map_err(|e| format!("Encode: {}", e))
}

fn transpose_image(img: &image::DynamicImage) -> image::DynamicImage {
    let (w, h) = (img.width(), img.height());
    let rgb = img.to_rgb8();
    let mut out = image::RgbImage::new(h, w);
    for y in 0..h {
        for x in 0..w {
            out.put_pixel(y, x, *rgb.get_pixel(x, y));
        }
    }
    image::DynamicImage::ImageRgb8(out)
}

/// 按 EXIF 物理校正方向。
///
/// **调用方必须自己保证只调一次**: 桌面端已经在写缓存时校正过(RAW 预览/全图),
/// 服务端若对"已经是校正过的缓存"再调一次, 结果就是多转 90°。
pub(crate) fn apply_exif_orientation(
    path: &std::path::Path,
    img: image::DynamicImage,
) -> image::DynamicImage {
    let Some(orientation) = crate::exif_common::orientation(path) else {
        return img;
    };
    let (swap, flip_x, flip_y) = rawler::decoders::Orientation::from_u16(orientation).to_flips();
    let mut img = img;
    if flip_x {
        img = img.fliph();
    }
    if flip_y {
        img = img.flipv();
    }
    if swap {
        img = transpose_image(&img);
    }
    img
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

    // 目录版本 v4: v3 时期 jpeg_dimensions 校验过松, 可能缓存了损坏的内嵌图
    // (实测存在一个 ff d8 ff 7d 开头的坏文件)。换版本号让这些条目自然失效重建。
    let cache_dir = cache_dir().ok_or("No cache dir")?.join("image-filter").join("preview_v4");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);
    let cache_path = cache_dir.join(format!("{:016x}_prev.jpg", cache_hash(&file_path, mtime)));

    if cache_path.exists() {
        return Ok(cache_path.to_string_lossy().to_string());
    }

    if let Some(bytes) = extract_largest_preview_bytes(src) {
        let orientation = crate::exif_common::orientation(src);
        if orientation.is_none() || orientation == Some(1) {
            if std::fs::write(&cache_path, &bytes).is_ok() {
                return Ok(cache_path.to_string_lossy().to_string());
            }
        } else if let Ok(img) = image::load_from_memory(&bytes) {
            let oriented = apply_exif_orientation(src, img);
            if save_jpeg_quality(&oriented, &cache_path, 92).is_ok() {
                return Ok(cache_path.to_string_lossy().to_string());
            }
        }
    }

    Err("No preview available".into())
}

/// **debug 构建专用的 DNG 分支计时器**(G2)。
///
/// 一张 50MB 线性 DNG 的全解码可能卡在三个完全不同的地方: rawler 的传感器解码、
/// tinydng 的线性化、Windows Imaging Component 的系统 codec。release 里它们是一条
/// "谁先成功用谁"的静默链, 只看总耗时无法判断该优化哪一步。所以这里给每个**尝试过的**
/// 分支打一行(成功/失败 + 毫秒); release 构建里整个日志块被 cfg 掉, 函数退化成直通。
///
/// 时间包含该分支自己的解码**和写缓存**(`save_full_res_image`) —— 用户等待的就是这两步。
#[inline]
fn time_dng_branch<T>(name: &str, f: impl FnOnce() -> Option<T>) -> Option<T> {
    #[cfg(debug_assertions)]
    {
        let started = std::time::Instant::now();
        let out = f();
        eprintln!(
            "[preview] dng branch={} ok={} ms={}",
            name,
            out.is_some(),
            started.elapsed().as_millis()
        );
        out
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = name;
        f()
    }
}

/// Get full-resolution image for viewer.
/// JPEG/PNG → returns original path (zero decode, instant).
/// RAW → WIC 系统codec → rawler 全解码, cached to disk as JPEG.
#[tauri::command]
pub async fn get_full_image(file_path: String) -> Result<String, String> {
    let my_id = FULL_TASK_ID.fetch_add(1, Ordering::SeqCst) + 1;

    let src = std::path::Path::new(&file_path);
    if !src.exists() {
        return Err(format!("File not found: {}", file_path));
    }

    let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();

    if !RAW_EXTENSIONS.contains(&ext.as_str()) {
        return Ok(file_path);
    }

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("image-filter").join("full_v3");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);
    let cache_name = format!("{:016x}_full.jpg", cache_hash(&file_path, mtime));
    let cache_path = cache_dir.join(&cache_name);

    if cache_path.exists() {
        if is_full_res_cache(&cache_path) {
            return Ok(cache_path.to_string_lossy().to_string());
        }
        let _ = std::fs::remove_file(&cache_path);
    }

    let src_path = std::path::PathBuf::from(&file_path);
    let cache_path_clone = cache_path.clone();
    let is_dng = ext == "dng";

    if is_dng {
        if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
            return Err("superseded".into());
        }
        let _permit = raw_decode_sem().acquire().await.map_err(|e| e.to_string())?;
        if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
            return Err("superseded".into());
        }
        {
            let fp = file_path.clone();
            let cc = cache_path_clone.clone();
            if tokio::task::spawn_blocking(move || {
                if let Some(done) = time_dng_branch("rawler", || {
                    let img = decode_raw_slow(std::path::Path::new(&fp)).ok()?;
                    save_full_res_image(img, std::path::Path::new(&fp), &cc).ok()?;
                    Some(cc.to_string_lossy().to_string())
                }) {
                    return Some(done);
                }
                if let Some(done) = time_dng_branch("tinydng", || {
                    let img = crate::tinydng::decode_dng_tinydng(&fp)?;
                    save_full_res_image(img, std::path::Path::new(&fp), &cc).ok()?;
                    Some(cc.to_string_lossy().to_string())
                }) {
                    return Some(done);
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
        #[cfg(target_os = "windows")]
        {
            let fp = file_path.clone();
            let cc = cache_path_clone.clone();
            if tokio::task::spawn_blocking(move || {
                time_dng_branch("wic", || {
                    let img = crate::win_wic::decode_raw_wic(&fp)?;
                    let img = resize_max_edge(img, 5000);
                    let img = apply_exif_orientation(std::path::Path::new(&fp), img);
                    if !is_full_res_image(&img) {
                        return None;
                    }
                    img.save(&cc).ok()?;
                    Some(cc.to_string_lossy().to_string())
                })
            })
            .await
            .unwrap_or(None)
            .is_some()
            {
                return Ok(cache_path_clone.to_string_lossy().to_string());
            }
        }
        tokio::task::spawn_blocking(move || {
            let img = decode_raw_slow(&src_path)?;
            if !is_full_res_image(&img) {
                return Err("DNG full decode returned low resolution".into());
            }
            let img = resize_max_edge(img, 5000);
            img.save(&cache_path_clone).map_err(|e| format!("Save: {}", e))?;
            Ok::<(), String>(())
        })
        .await
        .map_err(|e| format!("Join: {}", e))??;
        return Ok(cache_path.to_string_lossy().to_string());
    }

    if let Some((w, h, bytes)) = extract_largest_preview_full(&src_path) {
        if w.max(h) >= 3000 {
            if std::fs::write(&cache_path_clone, &bytes).is_ok() {
                return Ok(cache_path_clone.to_string_lossy().to_string());
            }
        }
    }

    if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
        return Err("superseded".into());
    }
    let _permit = raw_decode_sem().acquire().await.map_err(|e| e.to_string())?;
    if my_id != FULL_TASK_ID.load(Ordering::SeqCst) {
        return Err("superseded".into());
    }

    #[cfg(target_os = "windows")]
    {
        let fp = file_path.clone();
        let cc = cache_path_clone.clone();
        if tokio::task::spawn_blocking(move || {
            if let Some(img) = crate::win_wic::decode_raw_wic(&fp) {
                let img = resize_max_edge(img, 5000);
                let img = apply_exif_orientation(std::path::Path::new(&fp), img);
                if is_full_res_image(&img) && img.save(&cc).is_ok() {
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

    tokio::task::spawn_blocking(move || {
        let img = decode_raw_slow(&src_path)?;
        if !is_full_res_image(&img) {
            return Err("RAW full decode returned low resolution".into());
        }
        let img = resize_max_edge(img, 5000);
        img.save(&cache_path_clone).map_err(|e| format!("Save: {}", e))?;
        Ok::<(), String>(())
    })
    .await
    .map_err(|e| format!("Join: {}", e))??;

    Ok(cache_path.to_string_lossy().to_string())
}

/// 提取RAW中分辨率最大的内嵌JPEG（返回尺寸+原始字节, mmap零解码）
/// 内层 EOI 扫描有 16MB 上限, 无 EOI 时跳过该 SOI 签名, 避免坏文件 O(n²)
const MAX_JPEG_LOOKAHEAD: usize = 16 * 1024 * 1024;
fn extract_largest_preview_full(path: &std::path::Path) -> Option<(u32, u32, Vec<u8>)> {
    let file = std::fs::File::open(path).ok()?;
    let map = unsafe { memmap2::Mmap::map(&file).ok()? };
    let data = &map[..];

    let mut best: Option<(u32, u32, usize, usize)> = None;
    let mut i = 0;
    while i + 3 < data.len() {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            let limit = (i + MAX_JPEG_LOOKAHEAD).min(data.len());
            let mut j = i + 2;
            let mut eoi = None;
            while j + 1 < limit {
                if data[j] == 0xFF && data[j + 1] == 0xD9 { eoi = Some(j + 2); break; }
                j += 1;
            }
            if let Some(end) = eoi {
                if let Some((w, h)) = jpeg_dimensions(&data[i..end]) {
                    if best.as_ref().map(|(bw, bh, _, _)| w * h > bw * bh).unwrap_or(true) {
                        best = Some((w, h, i, end));
                    }
                }
                i = end;
                continue;
            }
            // 无 EOI（坏文件）→ 跳过该签名避免反复扫描
            i += 3;
            continue;
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

/// 分析用图片加载（analyzer.rs 复用）: RAW 走内嵌 JPEG 提取(秒级), 其他格式直接解码
pub(crate) fn load_analysis_image(path: &std::path::Path) -> Option<image::DynamicImage> {
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    if RAW_EXTENSIONS.contains(&ext.as_str()) {
        let bytes = extract_largest_preview_bytes(path)?;
        return image::load_from_memory(&bytes).ok();
    }
    image::open(path).ok()
}

/// 从 JPEG 字节解析宽高（严格按段结构遍历, 只接受 SOF0/SOF2）
///
/// 为什么不能"扫到 0xFF 就当段头": 那样会在**任意垃圾字节**里误判出 SOF ——
/// 坏数据也能解析出"宽高", 于是被当成合法预览写进缓存并长期复用。
/// 实测已经出现过一次: preview_v3 里有个以 `ff d8 ff 7d` 开头的损坏文件,
/// 既导致查看器显示空白, 也让分析器直接静默跳过那张照片。
/// 这里要求严格按段长度推进, 遇到非 0xFF 的段边界即判定数据已损坏。
fn jpeg_dimensions(data: &[u8]) -> Option<(u32, u32)> {
    if data.len() < 4 || data[0] != 0xFF || data[1] != 0xD8 {
        return None; // 必须以 SOI 开头
    }

    let mut i = 2usize;
    while i + 3 < data.len() {
        if data[i] != 0xFF {
            return None; // 段头必须以 0xFF 开头, 否则结构已损坏
        }
        // 跳过填充的连续 0xFF
        let mut j = i;
        while j < data.len() && data[j] == 0xFF {
            j += 1;
        }
        if j >= data.len() {
            return None;
        }
        let marker = data[j];
        i = j + 1;

        // 无长度字段的独立标记
        match marker {
            0xD8 | 0x01 | 0xD0..=0xD7 => continue, // SOI / TEM / RSTn
            0xD9 => return None,                   // EOI: 还没遇到 SOF
            _ => {}
        }

        if i + 1 >= data.len() {
            return None;
        }
        let len = u16::from_be_bytes([data[i], data[i + 1]]) as usize;
        if len < 2 {
            return None;
        }
        let seg_end = i + len;
        if seg_end > data.len() {
            return None;
        }

        // SOF0(基线) / SOF2(渐进): 段内固定偏移 —— 精度(1) 高(2) 宽(2)
        if marker == 0xC0 || marker == 0xC2 {
            if len < 8 {
                return None;
            }
            let h = u16::from_be_bytes([data[i + 3], data[i + 4]]) as u32;
            let w = u16::from_be_bytes([data[i + 5], data[i + 6]]) as u32;
            if w == 0 || h == 0 {
                return None;
            }
            return Some((w, h));
        }

        i = seg_end;
    }
    None
}

/// Batch + stream thumbnails: sends each result via Channel as it completes.
#[tauri::command]
pub async fn batch_thumbnails(
    file_paths: Vec<String>,
    max_size: u32,
    on_progress: tauri::ipc::Channel<(String, String)>,
) -> Result<(), String> {
    let semaphore = std::sync::Arc::new(Semaphore::new(4));
    let mut tasks = tokio::task::JoinSet::new();

    for path in file_paths {
        let semaphore = semaphore.clone();
        tasks.spawn(async move {
            let _permit = semaphore.acquire().await.ok()?;
            let path_for_task = path.clone();
            let result = tokio::task::spawn_blocking(move || thumb_single(&path_for_task, max_size))
                .await
                .ok()?;
            Some((path, result.ok()?))
        });
    }

    while let Some(joined) = tasks.join_next().await {
        if let Ok(Some((path, cache_path))) = joined {
            on_progress.send((path, cache_path)).ok();
        }
    }
    Ok(())
}

/// Core: generate one thumbnail, with caching.
fn thumb_single(file_path: &str, max_size: u32) -> Result<String, String> {
    let src = std::path::Path::new(file_path);
    if !src.exists() {
        return Err(format!("File not found: {}", file_path));
    }

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("image-filter").join("thumbnails_v2");
    std::fs::create_dir_all(&cache_dir).map_err(|e| format!("Mkdir: {}", e))?;

    let mtime = std::fs::metadata(src)
        .and_then(|m| m.modified())
        .map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs())
        .unwrap_or(0);

    let cache_name = format!("{:016x}_{}.jpg", cache_hash(file_path, mtime), max_size);
    let cache_path = cache_dir.join(&cache_name);

    if cache_path.exists() {
        return Ok(cache_path.to_string_lossy().to_string());
    }

    #[cfg(target_os = "windows")]
    if let Some(img) = crate::win_wic::thumbnail_from_shell(file_path, max_size) {
        if img.save(&cache_path).is_ok() {
            return Ok(cache_path.to_string_lossy().to_string());
        }
    }

    let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();

    if matches!(ext.as_str(), "mp4" | "mov" | "avi" | "mkv") {
        if let Some(img) = extract_video_frame(src) {
            let thumb = img.thumbnail(max_size, max_size);
            thumb.save(&cache_path).map_err(|e| format!("Save: {}", e))?;
            return Ok(cache_path.to_string_lossy().to_string());
        }
        return Err("No video thumbnail available".into());
    }

    let img = if RAW_EXTENSIONS.contains(&ext.as_str()) {
        extract_raw_preview(src)?
    } else if ext == "jpg" || ext == "jpeg" {
        decode_jpeg_fast(src)?
    } else {
        image::open(src).map_err(|e| format!("Open: {}", e))?
    };

    let thumb = apply_exif_orientation(src, img.thumbnail(max_size, max_size));
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
/// 只保留最后 64 个候选(内嵌图通常靠后, 且可防止恶意文件的假 SOI 撑爆 Vec)
fn extract_raw_preview(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let data = std::fs::read(path).map_err(|e| format!("Read RAW: {}", e))?;

    const MAX_CANDIDATES: usize = 64;
    let mut jpeg_candidates: Vec<usize> = Vec::new();
    let mut i = 0;
    while i < data.len().saturating_sub(3) {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            jpeg_candidates.push(i);
            if jpeg_candidates.len() > MAX_CANDIDATES {
                jpeg_candidates.remove(0); // 只保留靠后的候选
            }
        }
        i += 1;
    }

    for &start in jpeg_candidates.iter().rev() {
        let jpeg_data = &data[start..];
        if let Ok(img) = image::load_from_memory(jpeg_data) {
            return Ok(img);
        }
    }

    decode_raw_slow(path)
}

/// Slow fallback: full RAW sensor decode via rawler
fn decode_raw_slow(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let params = rawler::decoders::RawDecodeParams::default();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        rawler::analyze::raw_to_srgb(path, &params)
    }));

    if let Ok(Ok(img)) = result {
        return Ok(apply_exif_orientation(path, img));
    }

    let raw = rawler::decode_file(path).map_err(|e| format!("RAW: {}", e))?;
    let (w, h) = (raw.width as u32, raw.height as u32);
    if let rawler::RawImageData::Integer(data) = raw.data {
        // 位深自适应: 取数据实际最大值归一化(8-bit → 255, 12/14-bit → 4095/16383, 16-bit → 65535)
        // 固定 65535 会让 8-bit 数据全黑、12/14-bit 整体偏暗
        let max = data.iter().copied().max().unwrap_or(0).max(1) as f32;
        let expected = (w as usize) * (h as usize);
        let n = expected.min(data.len());
        let mut rgb = vec![0u8; n * 3];
        for (i, &pix) in data[..n].iter().enumerate() {
            let v = ((pix as f32 / max) * 255.0).clamp(0.0, 255.0) as u8;
            rgb[i * 3] = v;
            rgb[i * 3 + 1] = v;
            rgb[i * 3 + 2] = v;
        }
        image::RgbImage::from_raw(w, h, rgb)
            .map(image::DynamicImage::ImageRgb8)
            .map(|img| apply_exif_orientation(path, img))
            .ok_or_else(|| "Bad RGB buffer".into())
    } else {
        Err("Unsupported RAW format".into())
    }
}

/// Extract a frame from video using ffmpeg (if available)
fn extract_video_frame(path: &std::path::Path) -> Option<image::DynamicImage> {
    use std::process::Command;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jpeg_dimensions_reads_encoded_jpeg() {
        // 用 image crate 真编一张 JPEG, 验证能解析出正确宽高
        let img = image::RgbImage::from_fn(64, 48, |x, y| {
            image::Rgb([(x * 4) as u8, (y * 5) as u8, 128])
        });
        let mut buf: Vec<u8> = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, 85)
            .encode_image(&img)
            .expect("编码 JPEG 失败");

        assert_eq!(jpeg_dimensions(&buf), Some((64, 48)));
    }

    /// 回归护栏: 样本取自真实损坏缓存文件 `preview_v3/b19755bbbe7f2164_prev.jpg` 的开头字节
    /// (`ff d8 ff 7d ...`)。这个流里没有合法的段结构, 但**深处藏着一组像 SOF 的字节** ——
    /// 旧的"扫到 0xFF 就当段头"实现会把它当成 SOF 并解析出假的宽高, 于是坏数据被当成
    /// 合法预览缓存下来(查看器空白)且被分析器静默跳过。严格遍历必须拒绝。
    #[test]
    fn jpeg_dimensions_rejects_corrupt_stream_with_plausible_sof() {
        let mut buf = vec![0u8; 32 * 1024];
        let header = [
            0xFFu8, 0xD8, 0xFF, 0x7D, 0x60, 0x64, 0x4E, 0x4C, 0x2D, 0x20, 0x92, 0xAA,
        ];
        buf[..header.len()].copy_from_slice(&header);

        // 段长谎报为 0x6064 = 24676 字节 → 严格遍历跳过它后落在 0 字节上, 判定损坏;
        // 而"扫描式"实现会继续往后找 0xFF 并咬住下面这组假 SOF。
        let fake_sof_at = 25_000;
        buf[fake_sof_at] = 0xFF;
        buf[fake_sof_at + 1] = 0xC0;
        buf[fake_sof_at + 2] = 0x00;
        buf[fake_sof_at + 3] = 0x11; // 段长 17
        buf[fake_sof_at + 4] = 0x08; // 精度 8
        buf[fake_sof_at + 5] = 0x10; // 高 0x1000
        buf[fake_sof_at + 6] = 0x00;
        buf[fake_sof_at + 7] = 0x20; // 宽 0x2000
        buf[fake_sof_at + 8] = 0x00;

        assert_eq!(
            jpeg_dimensions(&buf),
            None,
            "损坏的 JPEG 流不应解析出宽高(否则会被当成合法预览缓存)"
        );
    }

    #[test]
    fn jpeg_dimensions_rejects_non_jpeg_and_tiny_input() {
        assert_eq!(jpeg_dimensions(&[]), None);
        assert_eq!(jpeg_dimensions(&[0xFF, 0xD8]), None);
        assert_eq!(jpeg_dimensions(b"\x89PNG\r\n\x1a\n"), None);
    }

    /// 探针(M0′, 默认忽略): RAW **内嵌预览的分辨率分布**。
    ///
    /// 它回答的不是"手机能不能读 RAW"（解码在电脑上），而是
    /// **"手机看到的预览有多大"** —— 即画质上界。规格 §5.2 明示:
    /// 默认预览的画质上界就是这张内嵌 JPEG, 不是 RAW 的画质。
    /// Sony ARW 的最大内嵌预览常见仅 1616×1080, 放大到 1:1 验对焦是不够的。
    ///
    /// 运行: $env:IMAGEFILTER_PROBE_DIR="D:\样张"; cargo test --lib probe_embedded_preview -- --ignored --nocapture
    #[test]
    #[ignore = "M0' probe — 需要真实 RAW 样张目录"]
    fn probe_embedded_preview() {
        let dir = match std::env::var("IMAGEFILTER_PROBE_DIR") {
            Ok(v) if !v.is_empty() => v,
            _ => {
                println!("未设置 IMAGEFILTER_PROBE_DIR, 跳过");
                return;
            }
        };

        // 按扩展名汇总: (张数, 命中数, 最大长边, 长边>=2048 的张数)
        let mut by_ext: std::collections::BTreeMap<String, (usize, usize, u32, usize)> =
            std::collections::BTreeMap::new();
        println!("{:<32} {:>7} {:>11} {:>8} {:>6}", "file", "MB", "embedded", "jpegMB", "ms");

        for entry in walkdir::WalkDir::new(&dir).into_iter().filter_map(|e| e.ok()) {
            if !entry.file_type().is_file() {
                continue;
            }
            let p = entry.path();
            let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
            if !crate::scanner::RAW_EXTENSIONS.contains(&ext.as_str()) {
                continue;
            }
            let size = std::fs::metadata(p).map(|m| m.len()).unwrap_or(0);
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            let t0 = std::time::Instant::now();
            let got = extract_largest_preview_full(p);
            let ms = t0.elapsed().as_millis();

            let slot = by_ext.entry(ext).or_insert((0, 0, 0, 0));
            slot.0 += 1;
            match got {
                Some((w, h, bytes)) => {
                    let long = w.max(h);
                    // 两条**与样本集无关**的真断言: 它们不判断"这个格式支不支持内嵌预览",
                    // 只判断"扫描逻辑有没有坏"。所以这个探针既保留了测量用途, 又能在
                    // 扫描回归时自己失败 —— 而不是打印一屏 MISS 让人以为是样张问题。
                    // （没有断言的话, 它在评审规则里就是"断言为空的测试", 属于缺陷。）
                    assert!(
                        bytes.len() >= 4 && bytes[0] == 0xFF && bytes[1] == 0xD8,
                        "{} 提取出的东西不是 JPEG(缺 SOI) —— 扫描逻辑出了问题", name
                    );
                    assert!(w > 0 && h > 0, "{} 解析出非法尺寸 {}x{}", name, w, h);
                    slot.1 += 1;
                    slot.2 = slot.2.max(long);
                    if long >= 2048 {
                        slot.3 += 1;
                    }
                    println!(
                        "{:<32} {:>7.1} {:>11} {:>8.2} {:>6}",
                        name, size as f64 / 1_048_576.0,
                        format!("{}x{}", w, h),
                        bytes.len() as f64 / 1_048_576.0, ms
                    );
                }
                None => println!(
                    "{:<32} {:>7.1} {:>11} {:>8} {:>6}",
                    name, size as f64 / 1_048_576.0, "MISS", "-", ms
                ),
            }
        }

        // 目录给错了、或样本扩展名不在 RAW_EXTENSIONS 里时, 下面那张表会全空 ——
        // 与其让人对着空表猜, 不如直接失败。
        assert!(
            !by_ext.is_empty(),
            "IMAGEFILTER_PROBE_DIR={} 下没找到任何 RAW 文件 —— 目录给错了, 或样本扩展名不在 RAW_EXTENSIONS 里",
            dir
        );

        println!("\n按扩展名汇总:");
        println!("{:<8} {:>6} {:>6} {:>10} {:>12}", "ext", "总数", "命中", "最大长边", ">=2048 张数");
        for (ext, (n, hit, maxlong, big)) in &by_ext {
            println!("{:<8} {:>6} {:>6} {:>10} {:>12}", ext, n, hit, maxlong, big);
        }
        println!(
            "\n结论提示: 若某扩展名的「最大长边」或「>=2048 张数」偏低, 该格式在手机上放大到 1:1 会不够看, 需要走 full=1。"
        );
    }
}
