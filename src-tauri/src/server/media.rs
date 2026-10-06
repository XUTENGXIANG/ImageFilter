// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 缩略图与预览的字节化。
//!
//! 缓存逻辑**一行都不重写**: `get_thumbnail_path` / `get_preview_image` /
//! `get_full_image` 已经各自返回缓存文件路径, 这里只负责"读字节"或"读字节 + 降采样 + 重编码"。
//!
//! 画质契约(规格 §5.2): Lanczos3(`resize_max_edge`) + 显式 q88(`encode_jpeg_quality`)。
//!
//! **路径白名单不在这一层**: 三个公开函数都接受任意字符串路径, 调用方(路由层)必须先过
//! `paths::ensure_within`(规格 §3.2 第 2 条)。这是刻意的分工 —— 服务层只做"取字节" ——
//! 但它意味着**任何一次漏校验的挂载都等于"任意文件读取"**。新增端点时请先调 `ensure_within`,
//! 不要指望这里兜底。

use crate::server::ApiError;
use std::path::Path;
use std::sync::OnceLock;
use tokio::sync::Semaphore;

/// 服务端预览的编码质量。**必须是显式值** —— `img.save()` 会走 JPEG 默认质量 ≈75。
/// 这种差异尺寸断言完全看不出来, 所以它有一条专门的测试(见本文件 `mod tests`)。
const PREVIEW_JPEG_QUALITY: u8 = 88;

/// 缩略图允许的档位(长边像素)。
///
/// 为什么必须是有限集合而不是 `clamp(64, 1024)`: 缓存名把 size 编进去
/// (`{hash}_{size}.jpg`, 见 `thumb_single`), 而 `thumbnails_v2` 是**永不过期**的磁盘缓存
/// (本机实测已有 1257 个文件)。放开成自由参数就等于"每张照片最多 961 个缓存条目",
/// 一个写错的客户端能把用户的系统盘塞满。手机上真正需要的只有桌面端那一档(300),
/// 另外两档是给"小图列表/放大一格"的口子。
///
/// 契约: **长边 ≤ 请求值**。请求值不落在档位上时**向下取整到最近的档位**(绝不向上 ——
/// 向上会返回比客户端要的更大、更贵的图); 比最小档位还小则报 `badRequest`,
/// 因为那时"≤ 请求值"和"至少能看清"两条无法同时成立。
const THUMBNAIL_SIZES: &[u32] = &[150, 300, 600];

/// 预览 `max`(长边像素)的合法区间。
///
/// 上限 5000 与桌面端全图缓存的上限一致(`resize_max_edge(img, 5000)`): 再要更大也没有
/// 更多像素可给(`resize_max_edge` **绝不放大**), 只会白白多花 CPU 与内存。
/// 下限 64 是"再小就没有查看意义"的护栏。
/// 越界**一律报错, 不再 clamp 成别的值** —— 旧的 `clamp(256, 5000)` 会让"请求 64 却拿到 256",
/// 客户端据此算出来的缩放比全是错的。
const PREVIEW_MAX_MIN: u32 = 64;
const PREVIEW_MAX_MAX: u32 = 5000;

/// 请求驱动的媒体重活(解码 + Lanczos 重采样 + 重编码)并发上限。
///
/// 为什么是 2:
///   · 一次全尺寸解码是 CPU 密集 + 几十到几百 MB 常驻内存的操作。手机只要并发发 N 个请求,
///     就能在**桌面 App 的同一个进程里**堆起 N 份解码, 把用户正在用的机器顶掉 ——
///     这是本项目的头号红线(不许打扰桌面端);
///   · 手机端的真实用法是"滚动缩略图 + 偶尔开一张预览", 2 个并发已经够跑满一条连接,
///     再多也不会更快(瓶颈在 CPU/内存, 不在网络);
///   · 桌面端自己的 `batch_thumbnails` 有它自己的 `Semaphore::new(4)`, **不经过这个闸门**,
///     所以收紧这里**不会**改变桌面端的行为(红线: 桌面路径不许被拖慢)。
///   · RAW 全解码另有一道 `RAW_DECODE_SEM(1)`, 在本闸门**内层**获取; 两者只有
///     "外层 → 内层"一个方向, 不存在死锁环路。
const MEDIA_CONCURRENCY: usize = 2;

static MEDIA_SEM: OnceLock<Semaphore> = OnceLock::new();

fn media_sem() -> &'static Semaphore {
    MEDIA_SEM.get_or_init(|| Semaphore::new(MEDIA_CONCURRENCY))
}

/// 并发闸门的**测试观测点**(生产编译里不存在): 闸门内的实时并发数 + 历史峰值。
///
/// 放在生产文件里而不是测试文件里, 是因为要观测的是"闸门**内**同时在跑的请求数" ——
/// 只有守卫生效的这段代码自己知道。集成测试拿不到它(lib 在集成测试里不带 cfg(test)),
/// 所以证明闸门真的闸住了的那条测试也在本文件里。
#[cfg(test)]
mod gate_probe {
    use std::sync::atomic::{AtomicUsize, Ordering};

    pub(super) static IN_FLIGHT: AtomicUsize = AtomicUsize::new(0);
    pub(super) static PEAK_IN_FLIGHT: AtomicUsize = AtomicUsize::new(0);

    /// 进入闸门时构造, 离开(作用域结束)时析构 —— 计数区间与持锁区间严格一致。
    pub(super) struct Guard;

    impl Guard {
        pub(super) fn enter() -> Self {
            let now = IN_FLIGHT.fetch_add(1, Ordering::SeqCst) + 1;
            PEAK_IN_FLIGHT.fetch_max(now, Ordering::SeqCst);
            Guard
        }
    }

    impl Drop for Guard {
        fn drop(&mut self) {
            IN_FLIGHT.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

/// 请求驱动媒体工作的闸门守卫。存活期间占用一个许可 —— 所以它必须在**重活期间**
/// 一直活着(声明成局部变量直到函数结束), 不能只包住 `await` 那一行。
struct MediaGate {
    _permit: Option<tokio::sync::SemaphorePermit<'static>>,
    #[cfg(test)]
    _probe: gate_probe::Guard,
}

impl MediaGate {
    fn new(permit: Option<tokio::sync::SemaphorePermit<'static>>) -> Self {
        #[cfg(not(test))]
        {
            MediaGate { _permit: permit }
        }
        #[cfg(test)]
        {
            MediaGate {
                _permit: permit,
                _probe: gate_probe::Guard::enter(),
            }
        }
    }
}

/// 进入闸门。这个信号量从不 `close()`, 所以拿不到许可只可能发生在进程收尾阶段;
/// 那时"少一道闸门"也比在**桌面端同一个进程里** panic 好。
async fn media_gate() -> MediaGate {
    MediaGate::new(media_sem().acquire().await.ok())
}

/// 缩略图档位: 向下取整到最近的允许档位(契约见 `THUMBNAIL_SIZES`)。
fn thumbnail_size(requested: u32) -> Result<u32, ApiError> {
    THUMBNAIL_SIZES
        .iter()
        .copied()
        .filter(|s| *s <= requested)
        .max()
        .ok_or(ApiError::BadRequest)
}

/// 源图解析/解码失败 → 闭集错误码(规格 §5.3)。
///
/// 区分"没有这个文件"和"有文件但本 build 解不了"是**必须的**: 手机端对前者要提示
/// "照片可能被移动或删除", 对后者要提示"这个格式电脑上的 App 也打不开"。
/// 两者都退回 `notFound` 会让用户去重插硬盘, 而问题其实是格式。
fn source_error(requested: &str, msg: &str) -> ApiError {
    // `get_full_image` 在"更新的请求已经到来"时返回 `superseded`(它会被后来的请求取代)。
    // 那不是解码失败 —— 告诉手机"忙, 稍后重试"才是诚实的。
    // 注意: 这里只能比字符串, 因为 `images.rs` 的错误类型是 `String`; 改成类型化错误
    // 属于 images.rs 的改动(桌面端共用), 不在本次修复范围内。
    if msg.contains("superseded") {
        return ApiError::Busy;
    }
    if Path::new(requested).exists() {
        ApiError::UnsupportedForm
    } else {
        ApiError::NotFound
    }
}

/// 降采样 + 用**共享编码器**编成内存 JPEG。
///
/// 编码参数只允许有这一处: 掉回 `img.save()` 会走 crate 默认质量(≈75), 手机上的预览
/// 立刻变糙, 而尺寸/方向断言对此完全无感 —— 所以有专门的量化表测试盯着它。
/// 顺序: 调用方先按 `source` 校方向, 这里才降采样(长边 ≤ max 是对**校正后**的图算的)。
fn encode_preview(img: image::DynamicImage, max: u32) -> Result<Vec<u8>, ApiError> {
    let img = crate::scanner::images::resize_max_edge(img, max);
    let mut out = Vec::with_capacity(256 * 1024);
    crate::scanner::images::encode_jpeg_quality(&img, &mut out, PREVIEW_JPEG_QUALITY)
        .map_err(|_| ApiError::Internal)?;
    Ok(out)
}

/// 缩略图字节。直接复用桌面端的 `thumbnails_v2` 缓存。
///
/// **不做路径白名单校验** —— 由路由层负责(见模块文档)。
pub async fn thumbnail_bytes(path: &str, size: u32) -> Result<Vec<u8>, ApiError> {
    // 闸门必须罩住 `get_thumbnail_path`: 它是 `thumb_single` 的同步外壳(cache miss 时
    // 直接在调用线程上解码 + 编码)。桌面端走 `batch_thumbnails` 时有自己的 `Semaphore(4)`,
    // 但 HTTP 请求走这条路径时**原先完全没有闸门**。
    let _gate = media_gate().await;
    let size = thumbnail_size(size)?;
    let cached = crate::scanner::images::get_thumbnail_path(path.to_string(), size)
        .await
        .map_err(|e| source_error(path, &e))?;
    tokio::task::spawn_blocking(move || std::fs::read(&cached))
        .await
        .map_err(|_| ApiError::Internal)?
        .map_err(|_| ApiError::NotFound)
}

/// 预览字节。`full = true` 时走全解码链（`get_full_image`）。
///
/// **不做路径白名单校验** —— 由路由层负责(见模块文档)。
pub async fn preview_bytes(path: &str, max: u32, full: bool) -> Result<Vec<u8>, ApiError> {
    // 参数校验放在拿许可之前: 注定要 400 的请求不该占着闸门。
    if !(PREVIEW_MAX_MIN..=PREVIEW_MAX_MAX).contains(&max) {
        return Err(ApiError::BadRequest);
    }
    // 闸门罩住整段: 取源图路径(RAW 可能触发全解码) + 解码 + 重采样 + 重编码。
    let _gate = media_gate().await;
    let requested = path.to_string();

    // 1) 借既有命令拿到"源图路径"（RAW → 缓存过的内嵌 JPEG; 非 RAW → 原图路径）
    let source = if full {
        crate::scanner::images::get_full_image(requested.clone())
            .await
            .map_err(|e| source_error(&requested, &e))?
    } else {
        crate::scanner::images::get_preview_image(requested.clone())
            .await
            .map_err(|e| source_error(&requested, &e))?
    };

    // 2) 解码 → 校方向 → 降采样 → 编进内存。**重活必须离开 async 线程。**
    tokio::task::spawn_blocking(move || {
        let img = image::open(&source).map_err(|_| {
            if Path::new(&source).exists() {
                ApiError::UnsupportedForm
            } else {
                ApiError::NotFound
            }
        })?;

        // 方向只能上一次 —— 但"上没上过"**不能靠比路径**。旧实现把
        // `source == requested` 当作"还是原图, 所以方向没校正过"的代理, 这个代理是错的:
        //   · `get_full_image` 对内嵌预览长边 >= 3000 的 RAW, 是把内嵌 JPEG **逐字节**
        //     拷进 `full_v3` 缓存(`extract_largest_preview_full` 那个分支), 既不物理旋转
        //     也不剥掉 EXIF Orientation —— 于是 `source != requested` 也可能是"没校正过"。
        //     (补一句为什么不动 images.rs: 桌面端 `<img>` 靠 CSS
        //      `image-orientation: from-image`(src/index.css) 自动应用这个标签, 在 images.rs
        //      里物理旋转会让桌面端**转两次** —— 那才是改桌面行为。)
        //   · 而我们自己的编码器(`encode_jpeg_quality`)写出的缓存**不携带 EXIF**(有测试证明)。
        // 正确的判据是**文件本身**: `apply_exif_orientation` 读的就是将要解码的这个文件的
        // Orientation 标签, 没有标签(或标签为 1/非法值)时原样返回, 有标签才动像素。
        // 于是"原图 / 逐字节拷来的 RAW 缓存 / 编码器写的缓存"三条分支共用同一次调用就是对的,
        // 路径比较可以整个删掉 —— 也就不会因为将来某个函数改返回规范化路径而**静默**丢掉校正。
        let img = crate::scanner::images::apply_exif_orientation(Path::new(&source), img);
        encode_preview(img, max)
    })
    .await
    .map_err(|_| ApiError::Internal)?
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::GenericImageView;
    use std::sync::atomic::Ordering;

    // ── 测试用小工具 ────────────────────────────────────────────────────────────

    fn plain_image(w: u32, h: u32) -> image::DynamicImage {
        image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(w, h, |x, y| {
            image::Rgb([(x % 256) as u8, (y % 256) as u8, ((x + y) % 256) as u8])
        }))
    }

    /// 写一张**不带 EXIF** 的普通 JPEG。故意不复用集成测试里的助手:
    /// lib 单测与集成测试是两个编译单元, 共享助手需要把它们提到生产 API 里。
    fn write_plain_jpeg(path: &std::path::Path, w: u32, h: u32) {
        let file = std::fs::File::create(path).expect("建文件");
        crate::scanner::images::encode_jpeg_quality(&plain_image(w, h), file, 90).expect("编码");
    }

    fn tmp_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("ifmediaunit_{}_{}", tag, std::process::id()));
        std::fs::create_dir_all(&dir).expect("建临时目录");
        dir
    }

    /// 抽出一张 JPEG 里**全部 DQT 段**的载荷。画质参数只体现在量化表里, 所以这就是
    /// "q88 还是默认 q75"最直接的可观测量。
    fn dqt_segments(jpeg: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut i = 2usize; // 跳过 SOI
        while i + 3 < jpeg.len() {
            if jpeg[i] != 0xFF {
                break;
            }
            let marker = jpeg[i + 1];
            if marker == 0xDA || marker == 0xD9 {
                break; // 到 SOS/EOI 就没有表段了
            }
            if marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
                i += 2;
                continue;
            }
            let len = u16::from_be_bytes([jpeg[i + 2], jpeg[i + 3]]) as usize;
            if len < 2 {
                break;
            }
            if marker == 0xDB {
                out.extend_from_slice(&jpeg[i + 4..i + 2 + len]);
            }
            i += 2 + len;
        }
        out
    }

    // ── 参数契约 ────────────────────────────────────────────────────────────────

    /// size 必须是**向下取整到档位**或报错: 旧实现 `clamp(64, 1024)` 会让"请求 32"拿到 64,
    /// 直接违反"长边 ≤ 请求值"。
    #[test]
    fn thumbnail_size_snaps_down_and_rejects_below_the_smallest_step() {
        assert_eq!(thumbnail_size(150).unwrap(), 150);
        assert_eq!(thumbnail_size(300).unwrap(), 300);
        assert_eq!(thumbnail_size(600).unwrap(), 600);
        assert_eq!(thumbnail_size(599).unwrap(), 300, "不到 600 就不许给 600(那份更大)");
        assert_eq!(thumbnail_size(5000).unwrap(), 600);
        assert_eq!(
            thumbnail_size(32).unwrap_err(),
            ApiError::BadRequest,
            "比最小档位还小 → 报错, 不许偷偷给 150"
        );
        assert_eq!(thumbnail_size(0).unwrap_err(), ApiError::BadRequest);
    }

    /// max 越界必须是 400, 而不是被 clamp 成另一个值(客户端会据此算出错误的缩放比)。
    #[tokio::test]
    async fn preview_rejects_out_of_range_max() {
        let dir = tmp_dir("maxrange");
        let p = dir.join("a.jpg");
        write_plain_jpeg(&p, 400, 300);
        let path = p.to_str().unwrap();

        assert_eq!(preview_bytes(path, 63, false).await.unwrap_err(), ApiError::BadRequest);
        assert_eq!(preview_bytes(path, 5001, false).await.unwrap_err(), ApiError::BadRequest);
        // 正对照: 边界上的值必须放行
        assert!(preview_bytes(path, 64, false).await.is_ok());
        assert!(preview_bytes(path, 5000, false).await.is_ok());

        std::fs::remove_dir_all(&dir).ok();
    }

    /// 存在的、解不了的文件必须是 unsupportedForm; 不存在的才是 notFound。
    /// 两条都断言, 否则"所有错误都变 unsupportedForm"也能过。
    #[test]
    fn source_error_distinguishes_missing_from_undecodable() {
        let dir = tmp_dir("srcerr");
        let real = dir.join("note.txt");
        std::fs::write(&real, b"not an image").unwrap();

        assert_eq!(
            source_error(real.to_str().unwrap(), "Open: unsupported"),
            ApiError::UnsupportedForm
        );
        assert_eq!(
            source_error(dir.join("ghost.jpg").to_str().unwrap(), "File not found"),
            ApiError::NotFound
        );
        // get_full_image 的 superseded 不是解码失败, 报"忙"让手机重试
        assert_eq!(
            source_error(real.to_str().unwrap(), "superseded"),
            ApiError::Busy,
            "被更新的请求取代 ≠ 格式不支持"
        );

        std::fs::remove_dir_all(&dir).ok();
    }

    // ── 画质契约: q88 ───────────────────────────────────────────────────────────

    /// 规格 §5.2 钉死的预览编码质量。测试里**故意写死字面量**(而不是引用常量):
    /// 改常量必须在这里红一次, 逼改动者显式面对"这会让手机上的预览变糙"。
    const SPEC_Q88: u8 = 88;

    /// 端点产出的字节必须就是**共享编码器 q88** 的字节。
    /// 把 88 换成默认质量(≈75)或换成 `img.save()` 都会让这条立刻红。
    #[test]
    fn encode_preview_is_the_shared_q88_encoder_byte_for_byte() {
        let img = plain_image(64, 48);
        let got = encode_preview(img.clone(), 2048).expect("编码应成功");

        let mut want: Vec<u8> = Vec::new();
        crate::scanner::images::encode_jpeg_quality(&img, &mut want, SPEC_Q88).unwrap();
        assert_eq!(got, want, "不放大时端点字节必须与共享编码器 q88 逐字节相同");
        assert!(
            !dqt_segments(&got).is_empty(),
            "DQT 解析器读不到量化表的话, 下面的比较就是空的"
        );

        // 让"量化表不同 = 画质不同"这件事本身有区分力
        let mut q75: Vec<u8> = Vec::new();
        crate::scanner::images::encode_jpeg_quality(&img, &mut q75, 75).unwrap();
        assert_ne!(
            dqt_segments(&got),
            dqt_segments(&q75),
            "q88 与 q75 的量化表必须不同 —— 这正是这条断言的能量来源"
        );
    }

    /// 同上, 但走**完整端点**: 挡住"在 preview_bytes 里绕开 encode_preview 直接用
    /// `img.save()`"(默认质量 ≈75)这类回归 —— 只测 encode_preview 是看不见它的。
    #[tokio::test]
    async fn preview_bytes_output_quantization_tables_are_q88() {
        let dir = tmp_dir("q88e2e");
        let p = dir.join("q.jpg");
        write_plain_jpeg(&p, 320, 240);

        let got = preview_bytes(p.to_str().unwrap(), 2048, false).await.unwrap();

        // 参考: 用同一个共享编码器, 在**同一份解码结果**上编 q88
        let src = image::open(&p).unwrap();
        let mut want: Vec<u8> = Vec::new();
        crate::scanner::images::encode_jpeg_quality(&src, &mut want, SPEC_Q88).unwrap();
        assert_eq!(
            dqt_segments(&got),
            dqt_segments(&want),
            "端点返回的量化表必须与共享编码器 q88 一致(默认质量 ≈75 的表与它不同)"
        );

        let mut q75: Vec<u8> = Vec::new();
        crate::scanner::images::encode_jpeg_quality(&image::open(&p).unwrap(), &mut q75, 75)
            .unwrap();
        assert_ne!(dqt_segments(&got), dqt_segments(&q75), "必须明显不是 q75");

        std::fs::remove_dir_all(&dir).ok();
    }

    // ── 方向规则的承重假设 ──────────────────────────────────────────────────────

    /// **"无条件校正方向"这个修法的承重假设**, 两条一起断言:
    ///   1. 我们自己的编码器写出的 JPEG **不携带** EXIF Orientation;
    ///   2. `apply_exif_orientation` 对没有标签的文件**原样返回像素**。
    /// 任一条不成立, 无条件调用就会把正常的图转 90° —— 那时这条测试必须红。
    #[test]
    fn encoder_written_cache_carries_no_orientation_so_reapplying_is_a_noop() {
        let dir = tmp_dir("noexif");
        let p = dir.join("enc.jpg");
        let img = plain_image(48, 32);
        let file = std::fs::File::create(&p).unwrap();
        crate::scanner::images::encode_jpeg_quality(&img, file, 92).unwrap();

        assert_eq!(
            crate::exif_common::orientation(&p),
            None,
            "共享编码器写出的 JPEG 不许带 EXIF Orientation"
        );
        let bytes = std::fs::read(&p).unwrap();
        assert!(
            !bytes.windows(4).any(|w| w == b"Exif"),
            "字节里也不该出现 APP1 的 Exif 头"
        );

        let out = crate::scanner::images::apply_exif_orientation(&p, img.clone());
        assert_eq!(out.dimensions(), img.dimensions(), "没有标签就不许换宽高");
        assert_eq!(
            out.to_rgb8().as_raw(),
            img.to_rgb8().as_raw(),
            "没有标签时必须逐像素原样返回"
        );

        std::fs::remove_dir_all(&dir).ok();
    }

    // ── 并发闸门 ────────────────────────────────────────────────────────────────

    /// 闸门必须**真的**闸住: 8 个并发请求, 观测闸门内的实时并发峰值。
    /// 只调一次证明不了任何事 —— 这条测试的存在理由就是"多个请求同时进来时不能出现第 3 个"。
    /// 峰值 ≥ 2 也必须断言, 否则一个"根本没让请求重叠"的测试也能绿(空断言)。
    #[tokio::test]
    async fn preview_gate_caps_concurrency() {
        gate_probe::PEAK_IN_FLIGHT.store(0, Ordering::SeqCst);
        gate_probe::IN_FLIGHT.store(0, Ordering::SeqCst);

        let dir = tmp_dir("gate");
        let p = dir.join("big.jpg");
        write_plain_jpeg(&p, 1600, 1200);
        let path = p.to_str().unwrap().to_string();

        let mut set = tokio::task::JoinSet::new();
        for _ in 0..(MEDIA_CONCURRENCY * 4) {
            let path = path.clone();
            set.spawn(async move { preview_bytes(&path, 1024, false).await });
        }
        while let Some(joined) = set.join_next().await {
            joined.expect("任务不该 panic").expect("预览应成功");
        }

        let peak = gate_probe::PEAK_IN_FLIGHT.load(Ordering::SeqCst);
        assert_eq!(
            gate_probe::IN_FLIGHT.load(Ordering::SeqCst),
            0,
            "全部结束后在飞计数必须归零(守卫是 Drop 的, 泄漏会掩盖真实的并发)"
        );
        assert!(
            peak <= MEDIA_CONCURRENCY,
            "闸门内并发峰值 {} > 上限 {} —— 手机可以这样把桌面进程的 CPU/内存打满",
            peak,
            MEDIA_CONCURRENCY
        );
        assert!(
            peak >= 2,
            "峰值只有 {} —— 这些请求根本没重叠, 这条测试没测到任何东西",
            peak
        );

        std::fs::remove_dir_all(&dir).ok();
    }
}
