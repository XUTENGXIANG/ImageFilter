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

/// **缩略图**闸门的预算: 4。
///
/// 为什么是 4: 桌面端自己的 `batch_thumbnails` 早就用 `Semaphore::new(4)` 把"同时 4 张缩略图"
/// 定为对用户机器安全的负载 —— HTTP 路径沿用同一个数字, 就**不会比用户每天在跑的那条路径更凶**。
/// 缩略图本身很轻(长边 150/300/600, 命中缓存时只是一次文件读), 而手机端是**滚动列表**:
/// 缩略图晚一拍就是肉眼可见的卡顿, 所以它需要一条**自己的、宽的**闸门。
///
/// 这个 `Semaphore` 与 `batch_thumbnails` 里那个**不是同一个实例**(那个是每次调用新建的局部
/// `Arc`), 两边不共享任何状态 —— 收放这里的预算不会改变桌面端的并发(红线)。
const THUMBNAIL_CONCURRENCY: usize = 4;

/// **预览 / 全图**闸门的预算: 1。
///
/// 为什么是 1: 这条路上一次请求 = 一次全尺寸解码 + Lanczos3 重采样 + 重编码, 峰值常驻内存
/// 几十到几百 MB, 而机器是用户**正在用**的那台(本项目的头号红线)。规格对这一条的原话就是
/// "走 Semaphore(1) 闸门, UI 上表现为『加载中』, 绝不阻塞其他请求"。
/// 而且 `get_full_image` 对 RAW 传感器解码本来就有 `RAW_DECODE_SEM(1)` 串行化, 第二个许可
/// 几乎换不来吞吐, 只换来**第二份**解码结果常驻内存。
///
/// 最关键的一点: 这 1 个许可**绝不能**和缩略图共享。修复前两条路共用一个 `MEDIA_SEM(2)`,
/// 两个 `full=1` 请求就能把两个许可全部占住, 于是所有缩略图请求排在两次全解码之后 ——
/// 手机的滚动列表整整卡住, 而规格明确承诺"绝不阻塞其他请求"。
///
/// 死锁: 两条闸门互不嵌套(一次请求只进其中一条), 桌面端的 `RAW_DECODE_SEM(1)` 是**内层**,
/// 方向只有"外层 → 内层"一个, 无环。
///
/// 每个客户端各自的限额**不在这一层**: 那需要客户端身份, 属于路由层(Task 8)。
/// 这里只管"整个进程一共允许多少重活同时在跑"。
const PREVIEW_CONCURRENCY: usize = 1;

/// 缩略图闸门。**独立**预算 —— 与预览闸门不共享任何状态。
static THUMBNAIL_SEM: OnceLock<Semaphore> = OnceLock::new();
/// 预览/全图闸门。同上, 独立预算。
static PREVIEW_SEM: OnceLock<Semaphore> = OnceLock::new();

fn thumbnail_sem() -> &'static Semaphore {
    THUMBNAIL_SEM.get_or_init(|| Semaphore::new(THUMBNAIL_CONCURRENCY))
}

fn preview_sem() -> &'static Semaphore {
    PREVIEW_SEM.get_or_init(|| Semaphore::new(PREVIEW_CONCURRENCY))
}

/// 闸门种类。生产代码里它只是个被忽略的标记(进哪条闸门已经由调用哪个 `*_sem()` 决定),
/// 存在的唯一理由是让**测试探针**把两条闸门的在飞计数分开观测。
#[derive(Clone, Copy)]
enum GateKind {
    Thumbnail,
    Preview,
}

/// 并发闸门的**测试观测点**(生产编译里不存在): 每条闸门各自的实时在飞数 + 历史峰值。
///
/// 放在生产文件里而不是测试文件里, 是因为要观测的是"闸门**内**同时在跑的请求数" ——
/// 只有守卫生效的这段代码自己知道。集成测试拿不到它(lib 在集成测试里不带 cfg(test)),
/// 所以观测闸门的测试也在本文件里。
///
/// **为什么是线程局部而不是 `static AtomicUsize`**(修复前的写法): `#[tokio::test]` 默认是
/// current_thread 运行时, 一条测试的**全部**任务都在它自己那条线程上跑, 于是线程局部天然就是
/// "每条测试一份"。进程级 static 则会被同一个测试二进制里**并行**跑的其他测试搅动 ——
/// 别的测试在"清零"之后、收尾断言之前把在飞数加上去, "结束后必须归零"就会**假红**。
/// 前提写在这么显眼的地方: 只有 current_thread 的测试能观测这些探针; 多线程 flavor 的测试里
/// 任务会在多个 worker 线程上被轮询, 每条线程各记一份计数 —— 那时不要用它们。
#[cfg(test)]
mod gate_probe {
    use super::GateKind;
    use std::cell::Cell;

    thread_local! {
        static THUMB_IN_FLIGHT: Cell<usize> = Cell::new(0);
        static THUMB_PEAK_IN_FLIGHT: Cell<usize> = Cell::new(0);
        static PREVIEW_IN_FLIGHT: Cell<usize> = Cell::new(0);
        static PREVIEW_PEAK_IN_FLIGHT: Cell<usize> = Cell::new(0);
    }

    fn cells(
        kind: GateKind,
    ) -> (
        &'static std::thread::LocalKey<Cell<usize>>,
        &'static std::thread::LocalKey<Cell<usize>>,
    ) {
        match kind {
            GateKind::Thumbnail => (&THUMB_IN_FLIGHT, &THUMB_PEAK_IN_FLIGHT),
            GateKind::Preview => (&PREVIEW_IN_FLIGHT, &PREVIEW_PEAK_IN_FLIGHT),
        }
    }

    /// 把这条线程上的计数清零(测试开头调)。线程局部, 所以只影响本条测试。
    pub(super) fn reset(kind: GateKind) {
        let (now, peak) = cells(kind);
        now.with(|c| c.set(0));
        peak.with(|c| c.set(0));
    }

    pub(super) fn in_flight(kind: GateKind) -> usize {
        cells(kind).0.with(|c| c.get())
    }

    pub(super) fn peak_in_flight(kind: GateKind) -> usize {
        cells(kind).1.with(|c| c.get())
    }

    /// 进入闸门时构造, 离开(作用域结束)时析构 —— 计数区间与持锁区间严格一致。
    pub(super) struct Guard {
        kind: GateKind,
    }

    impl Guard {
        pub(super) fn enter(kind: GateKind) -> Self {
            let (now, peak) = cells(kind);
            let v = now.with(|c| {
                let v = c.get() + 1;
                c.set(v);
                v
            });
            peak.with(|c| {
                if v > c.get() {
                    c.set(v);
                }
            });
            Guard { kind }
        }
    }

    impl Drop for Guard {
        fn drop(&mut self) {
            cells(self.kind).0.with(|c| c.set(c.get().saturating_sub(1)));
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
    /// `_kind` 只在测试编译里被用(喂给探针); 生产编译里两条闸门的区别已经体现在传进来的
    /// `permit` 上, 所以这里刻意用下划线前缀, 两种编译都不产生 warning。
    fn new(permit: Option<tokio::sync::SemaphorePermit<'static>>, _kind: GateKind) -> Self {
        #[cfg(not(test))]
        {
            MediaGate { _permit: permit }
        }
        #[cfg(test)]
        {
            MediaGate {
                _permit: permit,
                _probe: gate_probe::Guard::enter(_kind),
            }
        }
    }

    /// 进**缩略图**闸门。信号量从不 `close()`, 所以拿不到许可只可能发生在进程收尾阶段;
    /// 那时"少一道闸门"也比在**桌面端同一个进程里** panic 好。
    async fn thumbnail() -> Self {
        Self::new(thumbnail_sem().acquire().await.ok(), GateKind::Thumbnail)
    }

    /// 进**预览/全图**闸门。同上。
    async fn preview() -> Self {
        Self::new(preview_sem().acquire().await.ok(), GateKind::Preview)
    }
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

/// images.rs 的错误字符串里**明确表示内部/IO 故障**的前缀(闭集, 见 images.rs 里那些 `map_err`)。
///
/// 为什么需要这张表: 这些消息讲的是"缓存目录建不出来 / 缓存写不进去 / 源文件读不出来",
/// 与照片格式毫无关系。旧实现只看"请求的源文件在不在", 于是**任何**这类内部故障都被报成
/// `unsupportedForm`, 手机就会告诉用户"这个格式在电脑上也打不开" —— 而真正的问题是磁盘或权限。
///
/// 为什么用前缀而不是类型化错误: `images.rs` 与桌面端共用, 把它改成类型化错误的风险远大于这个
/// 缺陷本身(本轮红线)。所以这里只做**保守**的加表: 认不出来的消息一律退回原来的 `exists()` 判定,
/// 绝不猜成"内部故障"。代价写下来备查: `Open: <io error>`(thumb_single 对非 jpg/RAW 走
/// `image::open`)如果其实是权限问题, 仍会被报成 `unsupportedForm` —— 要彻底分开必须动
/// `images.rs` 的错误格式化, 不在本轮范围。
const INTERNAL_FAULT_PREFIXES: &[&str] = &[
    "No cache dir", // 拿不到缓存根(LOCALAPPDATA 缺失)
    "Mkdir:",       // 缓存目录建不出来(磁盘满 / 权限 / 路径被占)
    "Save:",        // 缓存写不进去
    "Join:",        // spawn_blocking 任务没跑起来(运行时收尾)
    "Read:",        // decode_jpeg_fast: 源文件读不出来
    "Read RAW:",    // extract_raw_preview: RAW 读不出来
];

fn is_internal_fault(msg: &str) -> bool {
    INTERNAL_FAULT_PREFIXES.iter().any(|p| msg.starts_with(p))
}

/// 源图解析/解码失败 → 闭集错误码(规格 §5.3)。
///
/// 区分"没有这个文件"和"有文件但本 build 解不了"是**必须的**: 手机端对前者要提示
/// "照片可能被移动或删除", 对后者要提示"这个格式电脑上的 App 也打不开"。
/// 两者都退回 `notFound` 会让用户去重插硬盘, 而问题其实是格式。
///
/// 还要区分**第三种**: 源文件好好的, 只是缓存目录/磁盘出了问题 —— 那是 `internal`,
/// 报成 `unsupportedForm` 就是让用户拿着一个没问题的文件反复重试(见
/// `INTERNAL_FAULT_PREFIXES`)。
fn source_error(requested: &str, msg: &str) -> ApiError {
    // `get_full_image` 在"更新的请求已经到来"时返回 `superseded`(它会被后来的请求取代)。
    // 那不是解码失败 —— 告诉手机"忙, 稍后重试"才是诚实的。
    // 注意: 这里只能比字符串, 因为 `images.rs` 的错误类型是 `String`; 改成类型化错误
    // 属于 images.rs 的改动(桌面端共用), 不在本次修复范围内。
    if msg.contains("superseded") {
        return ApiError::Busy;
    }
    // 内部/IO 故障优先于"源文件在不在" —— 与格式无关的问题不许报成格式问题。
    if is_internal_fault(msg) {
        return ApiError::Internal;
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
    // 用的是**缩略图自己的**预算 —— 全图解码占满预览闸门时, 这里照样能进(规格: 绝不阻塞其他请求)。
    let _gate = MediaGate::thumbnail().await;
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
    // `full` 与非 full 都走**预览/全图**闸门: 两者都是全尺寸解码 + Lanczos + 重编码, 一样重;
    // 这条闸门与缩略图那条**不共享状态**, 所以它满着也不会挡住手机的缩略图列表。
    let _gate = MediaGate::preview().await;
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
        let img = image::open(&source).map_err(|e| {
            // "读不出来"和"解不了"是两码事, 而且这一次**不用猜**: `image::ImageError` 自带类别。
            //   · 源文件已经不在(取完路径后被删/被移走) → notFound, 这条信息对手机最有用;
            //   · IoError → 文件在, 但读不动(权限 / 被独占锁 / 是个目录) → internal, 与格式无关;
            //   · 其余(Decoding / Unsupported / Limits) → 真的是内容/格式问题 → unsupportedForm。
            if !Path::new(&source).exists() {
                ApiError::NotFound
            } else if matches!(e, image::ImageError::IoError(_)) {
                ApiError::Internal
            } else {
                ApiError::UnsupportedForm
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

    /// 把**平台缓存根**临时指到临时目录的守卫。
    ///
    /// 为什么需要它: 只要真的缩略图一次, `thumb_single` 就会把结果写进
    /// `%LOCALAPPDATA%\image-filter\thumbnails_v2` —— 那是**用户真实的应用数据**。
    /// 测试不许往那里写(Fix B 治的就是这个), 所以这里把根指走, Drop 时还原并删干净,
    /// 反复跑什么都不留下。
    ///
    /// 这里**没有新增任何生产代码**: `images::cache_dir` 读的本来就是平台变量
    /// (Windows: `LOCALAPPDATA`, 其他: `XDG_CACHE_HOME`), 我们只是把它指到别处。
    ///
    /// 变量是**进程级**的, 所以凡是用到它的测试都先拿 `CACHE_ROOT_LOCK` 串起来;
    /// 并且当前**只有**这些测试会碰缓存目录(其余 lib 测试走的都是非 RAW 预览 ——
    /// `get_preview_image` 对非 RAW 直接返回原路径, 根本不碰缓存)。将来新增会写缓存的
    /// lib 测试请一起用这个守卫。
    static CACHE_ROOT_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// 平台缓存根变量名(见 `images::cache_dir`)。两边都指走, 哪个平台都不漏。
    const CACHE_ROOT_ENV_VARS: &[&str] = &["LOCALAPPDATA", "XDG_CACHE_HOME"];

    struct TempCacheRoot {
        _lock: std::sync::MutexGuard<'static, ()>,
        prev: Vec<(&'static str, Option<std::ffi::OsString>)>,
        root: std::path::PathBuf,
    }

    impl TempCacheRoot {
        fn new(tag: &str) -> Self {
            // 中毒也要继续: 这个锁只用来串行化, 不保护任何不变量。
            let lock = CACHE_ROOT_LOCK.lock().unwrap_or_else(|e| e.into_inner());
            let root =
                std::env::temp_dir().join(format!("iflibcache_{}_{}", tag, std::process::id()));
            std::fs::create_dir_all(&root).expect("建临时缓存根");
            let prev = CACHE_ROOT_ENV_VARS
                .iter()
                .map(|k| {
                    let old = std::env::var_os(k);
                    std::env::set_var(k, &root);
                    (*k, old)
                })
                .collect();
            TempCacheRoot {
                _lock: lock,
                prev,
                root,
            }
        }
    }

    impl Drop for TempCacheRoot {
        fn drop(&mut self) {
            for (k, old) in &self.prev {
                match old {
                    Some(v) => std::env::set_var(k, v),
                    None => std::env::remove_var(k),
                }
            }
            std::fs::remove_dir_all(&self.root).ok();
        }
    }

    /// 把 n 个请求用 `Barrier` **同时**放出去, 而不是让它们顺序进场。
    ///
    /// 这是"峰值"类断言的非空前提: 没有它, "峰值为 1"可能只是"任务压根没重叠"。
    /// Barrier 要求 n 个参与者全部到齐才放行, 所以放行那一刻 n 个请求**都在飞**。
    /// `make` 拿到的序号让每个任务能用不同的源图(同一路径会被多任务同时写同一个缓存文件)。
    async fn drive_concurrently<F, Fut>(n: usize, make: F)
    where
        F: Fn(usize) -> Fut,
        Fut: std::future::Future<Output = ()> + Send + 'static,
    {
        let barrier = std::sync::Arc::new(tokio::sync::Barrier::new(n));
        let mut set = tokio::task::JoinSet::new();
        for i in 0..n {
            let barrier = std::sync::Arc::clone(&barrier);
            let fut = make(i);
            set.spawn(async move {
                barrier.wait().await;
                fut.await;
            });
        }
        while let Some(joined) = set.join_next().await {
            joined.expect("任务不该 panic");
        }
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
    ///
    /// 再加上第三种: 源文件好好的、只是缓存/磁盘出了内部故障 —— 那不是格式问题, 必须是
    /// `internal`。旧实现只看"源文件在不在", 于是**任何**带源文件的失败都说成
    /// `unsupportedForm`, 手机会让用户拿着一个没问题的文件反复重试。
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

        // **源文件存在**的内部故障: 这几条以前全都落到 unsupportedForm
        for msg in [
            "No cache dir",
            "Mkdir: Access is denied. (os error 5)",
            "Save: There is not enough space on the disk. (os error 112)",
            "Join: task failed",
            "Read: The process cannot access the file because it is being used by another process",
            "Read RAW: device is not ready",
        ] {
            assert_eq!(
                source_error(real.to_str().unwrap(), msg),
                ApiError::Internal,
                "内部/IO 故障({})必须报 internal, 不许报成'这个格式打不开'",
                msg
            );
        }
        // 反面对照: 认不出来的消息**不许**被猜成 internal —— 原来那套 exists() 判定必须保持不变,
        // 否则就把"解不了"错报成"磁盘坏了"(同样误导用户)。
        assert_eq!(
            source_error(real.to_str().unwrap(), "RAW: unknown maker note"),
            ApiError::UnsupportedForm,
            "真正的解码失败仍是 unsupportedForm"
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

    /// **Fix A 的证明: 缩略图不排在预览/全图后面。**
    ///
    /// 做法: 用**生产的入口**(`MediaGate::preview`, 与 `preview_bytes` 用的是同一个)把预览/全图
    /// 预算**全部占住**, 确认探针看到它满着, 然后在这期间跑 8 个**真实**的并发缩略图请求
    /// (Barrier 同时放行)。
    ///
    /// 三条断言缺一不可:
    ///   1. 8 个缩略图**全都跑完**了 —— 若两条闸门共享状态(修复前是一个 `MEDIA_SEM(2)`),
    ///      它们会一直等在预览闸门后面 → 超时 → 红;
    ///   2. 预览闸门在整段期间**一直满着** —— 否则"没排队"可能只是因为它先放开了;
    ///   3. 缩略图自己的峰值 ≥ 2 且 ≤ `THUMBNAIL_CONCURRENCY` —— 它们真的**并行**进了
    ///      自己的闸门, 而不是被串行化(非空断言), 同时自己的上限还在。
    ///
    /// 为什么不是"各调一次"式测试: 那种写法在共享闸门的实现下**照样绿**(顺序调用根本不会撞上
    /// 闸门), 证明不了任何事。
    #[tokio::test]
    async fn thumbnails_do_not_queue_behind_a_fully_busy_preview_gate() {
        // 真实缩略图会写磁盘缓存 → 先把缓存根指到临时目录(见 TempCacheRoot)
        let _cache = TempCacheRoot::new("isolation");
        gate_probe::reset(GateKind::Preview);
        gate_probe::reset(GateKind::Thumbnail);

        // 1) 占满预览/全图预算, 并确认探针看到它确实在闸门内
        let mut held = Vec::new();
        for _ in 0..PREVIEW_CONCURRENCY {
            held.push(MediaGate::preview().await);
        }
        assert_eq!(
            gate_probe::in_flight(GateKind::Preview),
            PREVIEW_CONCURRENCY,
            "预览闸门必须已经被占满, 否则这条测试什么都没测"
        );

        // 2) 预览闸门满着的时候并发跑真实缩略图
        let dir = tmp_dir("isolation");
        // 8 张**各不相同**的源图: 同一路径会被多任务同时写同一个缓存文件, 那是另一码事
        let paths: Vec<String> = (0..8)
            .map(|i| {
                let p = dir.join(format!("s{}.jpg", i));
                write_plain_jpeg(&p, 1600, 1200);
                p.to_str().unwrap().to_string()
            })
            .collect();

        let paths_ref = std::sync::Arc::new(paths);
        let timed_out = tokio::time::timeout(
            std::time::Duration::from_secs(30),
            drive_concurrently(8, |i| {
                let path = paths_ref[i].clone();
                async move {
                    thumbnail_bytes(&path, 300).await.expect("缩略图应成功");
                }
            }),
        )
        .await;
        timed_out.expect(
            "缩略图不该排在预览/全图后面: 预览闸门占满时它必须照样能跑\
             (两条闸门若共享状态, 这里会死等到超时)",
        );

        // 3) 全程断言
        assert_eq!(
            gate_probe::in_flight(GateKind::Preview),
            PREVIEW_CONCURRENCY,
            "预览闸门在这期间必须一直满着(否则'没排队'可能是因为它先放开了)"
        );
        assert_eq!(
            gate_probe::in_flight(GateKind::Thumbnail),
            0,
            "缩略图跑完后它自己的在飞计数必须归零"
        );
        let thumb_peak = gate_probe::peak_in_flight(GateKind::Thumbnail);
        assert!(
            thumb_peak >= 2,
            "缩略图闸门峰值只有 {} —— 8 个请求根本没重叠, 这条测试没测到隔离性",
            thumb_peak
        );
        assert!(
            thumb_peak <= THUMBNAIL_CONCURRENCY,
            "缩略图闸门峰值 {} 超过了自己的预算 {}",
            thumb_peak,
            THUMBNAIL_CONCURRENCY
        );

        drop(held);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 预览/全图闸门必须**真的**闸住: 8 个请求被 `Barrier` 同时放出去, 观测闸门内的实时并发峰值。
    /// 只调一次证明不了任何事 —— 这条测试的存在理由就是"多个请求同时进来时不能出现第二个"。
    ///
    /// 非空断言: Barrier 保证放行那一刻 8 个请求**全部**在飞, 所以峰值不是"运气不好没重叠"得来的。
    /// 另一头, 把闸门拿掉(守卫不再进入)峰值会变成 0, 把预算调大则会变成 >1 —— 两种改动都会红。
    #[tokio::test]
    async fn preview_gate_caps_concurrency() {
        gate_probe::reset(GateKind::Preview);

        let dir = tmp_dir("gate");
        let p = dir.join("big.jpg");
        write_plain_jpeg(&p, 1600, 1200);
        let path = p.to_str().unwrap().to_string();

        let n = PREVIEW_CONCURRENCY * 8;
        drive_concurrently(n, |_| {
            let path = path.clone();
            async move {
                preview_bytes(&path, 1024, false)
                    .await
                    .expect("预览应成功");
            }
        })
        .await;

        assert_eq!(
            gate_probe::in_flight(GateKind::Preview),
            0,
            "全部结束后在飞计数必须归零(守卫是 Drop 的, 泄漏会掩盖真实的并发)"
        );
        assert_eq!(
            gate_probe::peak_in_flight(GateKind::Preview),
            PREVIEW_CONCURRENCY,
            "闸门内并发峰值必须正好等于预算 {} —— 小于它说明请求没真的撞在一起(或闸门被拿掉了), \
             大于它就是漏了",
            PREVIEW_CONCURRENCY
        );

        std::fs::remove_dir_all(&dir).ok();
    }

    /// **Fix C 的证明: 探针是"每条测试一份"的, 别的线程站在闸门里不许影响本条测试的读数。**
    ///
    /// 修复前探针是**进程级** `static AtomicUsize`: 同一个测试二进制里并行跑的另一条测试
    /// (它自己也可能在调 `preview_bytes`)只要在"清零"之后、收尾断言之前把在飞数加上去,
    /// 收尾那句"必须归零"就会**假红**。20 次没撞上不等于没有这颗地雷。
    ///
    /// 这里把那颗地雷显式造出来: 另一个线程 + 另一个 current_thread 运行时 + 一个真的站在
    /// 预览闸门里的守卫。本线程的读数必须仍然是 0。
    #[test]
    fn gate_probe_cannot_be_disturbed_by_another_thread() {
        gate_probe::reset(GateKind::Preview);
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();

        let other = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("建运行时");
            rt.block_on(async move {
                let _gate = MediaGate::preview().await;
                entered_tx.send(()).unwrap();
                // 一直站在闸门里, 直到主线程读完数
                release_rx.recv().unwrap();
            });
        });

        entered_rx.recv().expect("另一个线程应当进到闸门里");

        assert_eq!(
            gate_probe::in_flight(GateKind::Preview),
            0,
            "别的线程(别的测试)站在闸门里时, 本线程的在飞计数必须仍是 0 —— \
             进程级 static 会在这里把并行跑的测试互相搅成假红"
        );
        assert_eq!(
            gate_probe::peak_in_flight(GateKind::Preview),
            0,
            "峰值同理: 别的测试的并发不许记到本条测试头上"
        );

        release_tx.send(()).unwrap();
        other.join().expect("另一个线程不该 panic");
    }
}
