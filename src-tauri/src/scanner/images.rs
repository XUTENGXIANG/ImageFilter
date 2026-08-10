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

fn resize_max_edge(img: image::DynamicImage, max_edge: u32) -> image::DynamicImage {
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
    let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(file, quality);
    img.write_with_encoder(encoder)
        .map_err(|e| format!("Encode: {}", e))
}

fn read_exif_orientation(path: &std::path::Path) -> Option<u16> {
    let exif_reader = crate::exif_common::open_exif(path)?;
    for field in exif_reader.fields() {
        if field.tag == exif::Tag::Orientation {
            return field.value.get_uint(0).map(|v| v as u16);
        }
    }
    None
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

fn apply_exif_orientation(
    path: &std::path::Path,
    img: image::DynamicImage,
) -> image::DynamicImage {
    let Some(orientation) = read_exif_orientation(path) else {
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

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("preview_v3");
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
        let orientation = read_exif_orientation(src);
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

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("full_v3");
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
                if let Ok(img) = decode_raw_slow(std::path::Path::new(&fp)) {
                    if save_full_res_image(img, std::path::Path::new(&fp), &cc).is_ok() {
                        return Some(cc.to_string_lossy().to_string());
                    }
                }
                if let Some(img) = crate::tinydng::decode_dng_tinydng(&fp) {
                    if save_full_res_image(img, std::path::Path::new(&fp), &cc).is_ok() {
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
fn extract_largest_preview_full(path: &std::path::Path) -> Option<(u32, u32, Vec<u8>)> {
    let file = std::fs::File::open(path).ok()?;
    let map = unsafe { memmap2::Mmap::map(&file).ok()? };
    let data = &map[..];

    let mut best: Option<(u32, u32, usize, usize)> = None;
    let mut i = 0;
    while i + 3 < data.len() {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            let mut j = i + 2;
            let mut eoi = None;
            while j + 1 < data.len() {
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
    let mut i = 2;
    while i + 4 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        if marker == 0xC0 || marker == 0xC2 {
            if i + 9 < data.len() {
                let h = u16::from_be_bytes([data[i + 5], data[i + 6]]) as u32;
                let w = u16::from_be_bytes([data[i + 7], data[i + 8]]) as u32;
                return Some((w, h));
            }
        }
        let len = u16::from_be_bytes([data[i + 2], data[i + 3]]) as usize;
        if len < 2 { return None; }
        i += 2 + len;
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

    let cache_dir = cache_dir().ok_or("No cache dir")?.join("pixel-flow").join("thumbnails_v2");
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
fn extract_raw_preview(path: &std::path::Path) -> Result<image::DynamicImage, String> {
    let data = std::fs::read(path).map_err(|e| format!("Read RAW: {}", e))?;

    let mut jpeg_candidates: Vec<usize> = Vec::new();
    let mut i = 0;
    while i < data.len().saturating_sub(3) {
        if data[i] == 0xFF && data[i + 1] == 0xD8 && data[i + 2] == 0xFF {
            jpeg_candidates.push(i);
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
        let max = 65535u16;
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
