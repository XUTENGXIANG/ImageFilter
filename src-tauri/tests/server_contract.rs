// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 图库服务的契约测试。直接打 Router（`tower::ServiceExt::oneshot`），
//! 不占端口、不引 HTTP 客户端 —— 快且不会因端口占用而闪断。

// `dimensions()` / `get_pixel()` 来自 trait(`DynamicImage` 只自带 width/height), 显式引入。
use image::GenericImageView;

/// 测试专用: 从字节解出宽高（测试里不在乎这次解码开销）。
///
/// `image 0.25` 没有"从字节读尺寸"的公开 API, 所以这里真解一次内存图 —— 断言看的是
/// "服务端确实产出了这个尺寸的像素", 而不是它自称的尺寸。
fn image_dimensions_from_bytes(b: &[u8]) -> Option<(u32, u32)> {
    image::load_from_memory(b).ok().map(|i| i.dimensions())
}

#[test]
fn ensure_within_accepts_paths_inside_a_share() {
    let root = std::env::temp_dir().join(format!("ifshare_ok_{}", std::process::id()));
    std::fs::create_dir_all(root.join("sub")).unwrap();
    let f = root.join("sub").join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    let got = image_filter_lib::server::paths::ensure_within(&[root.clone()], &f).unwrap();
    assert_eq!(got, f.canonicalize().unwrap());
    std::fs::remove_dir_all(&root).ok();
}

#[test]
fn ensure_within_rejects_parent_escape() {
    let root = std::env::temp_dir().join(format!("ifshare_up_{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let outside = root.parent().unwrap().join("ifshare_secret.txt");
    std::fs::write(&outside, b"secret").unwrap();

    // root/../ifshare_secret.txt 在字符串前缀上"看起来"在 root 附近, 必须拒绝
    let sneaky = root.join("..").join("ifshare_secret.txt");
    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &sneaky).is_err(),
        ".. 逃逸必须被拒绝"
    );
    std::fs::remove_dir_all(&root).ok();
    std::fs::remove_file(&outside).ok();
}

#[test]
fn ensure_within_rejects_prefix_lookalike_directory() {
    let base = std::env::temp_dir().join(format!("ifshare_pre_{}", std::process::id()));
    let root = base.join("photos");
    let lookalike = base.join("photos2"); // 字符串前缀相同, 但 Path::starts_with 按组件比较

    // **共享根必须真的存在**。第一版只建了 photos2, 于是 root.canonicalize() 失败 →
    // 走 `continue` 跳过 → 拒绝其实来自"共享根不存在", 而不是"按组件比较"。
    // 那种写法把 starts_with 换成字符串前缀也照样绿 —— 测试因错误的原因通过。
    std::fs::create_dir_all(&root).unwrap();
    std::fs::create_dir_all(&lookalike).unwrap();
    let f = lookalike.join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &f).is_err(),
        "photos2 不是 photos 的子目录, 必须拒绝(字符串前缀写法会在这里出错)"
    );

    // 正对照: 同一个**已存在**的共享根里, 真子目录必须被接受。
    // 没有这一条, 上面那句 is_err 也可能只是"什么都拒绝"。
    let inside = root.join("a.jpg");
    std::fs::write(&inside, b"x").unwrap();
    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &inside).is_ok(),
        "共享根内的文件必须被接受"
    );

    std::fs::remove_dir_all(&base).ok();
}

/// 对方专门要求的一条: **确实存在**、但在任何共享根之外的路径, 必须是 403 而不是 404。
#[test]
fn ensure_within_rejects_an_existing_path_outside_every_share() {
    let base = std::env::temp_dir().join(format!("ifshare_out_{}", std::process::id()));
    let root = base.join("photos");
    std::fs::create_dir_all(&root).unwrap();
    let outside = base.join("outside.txt");
    std::fs::write(&outside, b"secret").unwrap();

    let err = image_filter_lib::server::paths::ensure_within(&[root.clone()], &outside)
        .expect_err("共享根外的路径必须被拒绝");
    assert_eq!(
        err.code(),
        "forbiddenPath",
        "路径存在但在共享根外 → 必须是 forbiddenPath, 不能是 notFound"
    );

    std::fs::remove_dir_all(&base).ok();
}

/// 共享根不存在(盘没插/被删)时不该让整个请求炸掉, 只应让该根失效。
#[test]
fn ensure_within_survives_a_missing_share_root() {
    let base = std::env::temp_dir().join(format!("ifshare_miss_{}", std::process::id()));
    std::fs::create_dir_all(&base).unwrap();
    let ghost = base.join("unplugged-drive"); // 从不创建
    let real = base.join("photos");
    std::fs::create_dir_all(&real).unwrap();
    let f = real.join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    // 第一个根不存在 → 跳过; 第二个根命中 → 接受
    assert!(
        image_filter_lib::server::paths::ensure_within(&[ghost, real.clone()], &f).is_ok(),
        "一个失效的共享根不应让其余共享根一起失效"
    );

    std::fs::remove_dir_all(&base).ok();
}

#[cfg(target_os = "windows")]
#[test]
fn ensure_within_rejects_symlink_escape() {
    let base = std::env::temp_dir().join(format!("ifshare_ln_{}", std::process::id()));
    let root = base.join("photos");
    let outside = base.join("outside");
    std::fs::create_dir_all(&root).unwrap();
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("a.jpg"), b"x").unwrap();

    // Windows 上创建符号链接需要开发者模式或管理员权限; 拿不到就跳过(不能因此让测试失败)
    if std::os::windows::fs::symlink_dir(&outside, root.join("link")).is_err() {
        println!("跳过: 本机无创建符号链接的权限");
        std::fs::remove_dir_all(&base).ok();
        return;
    }

    let via_link = root.join("link").join("a.jpg");
    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &via_link).is_err(),
        "符号链接逃逸必须被拒绝(canonicalize 后已不在共享根内)"
    );
    std::fs::remove_dir_all(&base).ok();
}

// 注意: 这里**不**放"token 是 64 位 hex / 常量时间比较"的测试。
// 鉴权原语的单测放在 auth.rs 自己的 `mod tests` 里（见 Step 4）—— 同一段断言在两个
// 文件里各写一份，在评审规则里就是"逐字重复的逻辑块"，属于缺陷。
// 集成测试只负责"必须用真实文件系统才能验"的那部分：越界判定。

// ── 媒体端点的**缓存根隔离** ─────────────────────────────────────────────────
//
// 为什么必须有这一段: `get_full_image` / `get_thumbnail_path` 会把结果写进
// `%LOCALAPPDATA%\image-filter\{full_v3,thumbnails_v2}` —— 那是**用户真实的应用数据**。
// 修复前 `full_raw_verbatim_cache_with_exif_is_oriented_exactly_once` 直接往那里写,
// 每跑一次就留下一个 6256 字节、带 EXIF 的条目(实测本机已堆了 10 个), 反复跑无限增长。
// 测试不许动用户的应用数据。
//
// 这里**没有新增生产代码**: `images::cache_dir` 读的本来就是平台变量
// (Windows `LOCALAPPDATA`, 其他平台 `XDG_CACHE_HOME`), 我们只是把它指到临时目录。
//
// 变量是**进程级**的, 所以所有会写缓存的测试都先拿 `CACHE_ROOT_LOCK` 串起来 —— 它们互不
// 干扰, 也绝不会写进用户的真实缓存。当前只有 `full=1` 的 RAW 测试与两个缩略图测试会写缓存。
static CACHE_ROOT_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// 平台缓存根变量名(见 `images::cache_dir`)。两边都指走, 哪个平台都不漏。
const CACHE_ROOT_ENV_VARS: &[&str] = &["LOCALAPPDATA", "XDG_CACHE_HOME"];

struct TempCacheRoot {
    _lock: std::sync::MutexGuard<'static, ()>,
    prev: Vec<(&'static str, Option<std::ffi::OsString>)>,
    root: std::path::PathBuf,
}

impl TempCacheRoot {
    /// 缓存根 = 一个**新建的临时目录**(正常的隔离)。
    fn new(tag: &str) -> Self {
        let root = std::env::temp_dir().join(format!("ifcache_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&root); // 清掉上次崩溃留下的同名目录
        std::fs::create_dir_all(&root).expect("建临时缓存根");
        Self::point_at(root)
    }

    /// 缓存根 = 一个**普通文件**。`create_dir_all(文件\image-filter\...)` 必然失败,
    /// 用来制造 `images.rs` 的 `Mkdir: ...` 内部故障 —— 这正是"源文件好好的、只是磁盘/权限
    /// 有问题"那种被旧实现错报成 `unsupportedForm` 的情况。
    fn blocked_by_a_file(tag: &str) -> Self {
        let root = std::env::temp_dir().join(format!("ifcachefile_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let _ = std::fs::remove_file(&root);
        std::fs::write(&root, b"not a directory").expect("建占位文件");
        Self::point_at(root)
    }

    fn point_at(root: std::path::PathBuf) -> Self {
        // 中毒也要继续: 这个锁只用来串行化, 不保护任何不变量。
        let lock = CACHE_ROOT_LOCK.lock().unwrap_or_else(|e| e.into_inner());
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

    /// 隔离出来的缓存根 —— 断言"缓存真的写在隔离位置而不是用户目录"用得上。
    fn root(&self) -> &std::path::Path {
        &self.root
    }
}

impl Drop for TempCacheRoot {
    fn drop(&mut self) {
        // 先把环境还原, 再删: 删的过程中若还有别的线程读它, 读到的也已经是原值。
        for (k, old) in &self.prev {
            match old {
                Some(v) => std::env::set_var(k, v),
                None => std::env::remove_var(k),
            }
        }
        // 目录和"占位文件"两种形态都清掉, 反复跑不留任何东西。
        std::fs::remove_dir_all(&self.root).ok();
        std::fs::remove_file(&self.root).ok();
    }
}

/// 隔离根里某个缓存子目录下的 jpg 条目数(断言隔离真的生效用)。
fn cached_jpg_count(root: &std::path::Path, subdir: &str) -> usize {
    std::fs::read_dir(root.join("image-filter").join(subdir))
        .map(|rd| {
            rd.filter_map(|e| e.ok())
                .filter(|e| e.path().extension().and_then(|x| x.to_str()) == Some("jpg"))
                .count()
        })
        .unwrap_or(0)
}

// media(缩略图/预览字节)的画质契约 ─────────────────────────────────────────
//
// 为什么这几条必须用**真文件 + 真解码**而不是 mock: 三条契约里最贵的两条
// (不放大 / 方向只上一次)只有在真走完"取缓存路径 → 解码 → 重采样"之后才看得见。
// 尤其是"方向只上一次"—— 它取决于服务端把**哪一个文件**当成方向的来源(原图 /
// 逐字节拷来的 RAW 缓存 / 我们编码器写的缓存), 假数据根本构造不出来。
//
// 另外: 断言一律从**产出的字节**再解一次尺寸, 不看服务端自报的数字,
// 否则"服务端说自己缩到了 2048"这种谎话会被原样放过。

// 四个互不相同的参考色, 亮度也拉得足够开 —— 这样"按最近色分类"能扛住 JPEG 有损压缩。
const Q_TL: [u8; 3] = [220, 30, 30];
const Q_TR: [u8; 3] = [30, 220, 30];
const Q_BL: [u8; 3] = [30, 30, 220];
const Q_BR: [u8; 3] = [240, 240, 240];

/// 源图 (x,y) 处应有的参考色: 四象限四色。
///
/// 为什么不用单色/渐变: **尺寸断言分辨不出"转置(5/7)"和"转 90°(6/8)"** —— 两者宽高
/// 完全一样。四色象限让"结果图某个位置应当来自源图哪个象限"变成可判定的。
fn quadrant_color(w: u32, h: u32, x: u32, y: u32) -> [u8; 3] {
    match (x * 2 < w, y * 2 < h) {
        (true, true) => Q_TL,
        (false, true) => Q_TR,
        (true, false) => Q_BL,
        (false, false) => Q_BR,
    }
}

/// 四个参考色里最接近 `px` 的那个(JPEG 有损, 不比逐像素相等)。
fn nearest_quadrant(px: [u8; 3]) -> [u8; 3] {
    let refs = [Q_TL, Q_TR, Q_BL, Q_BR];
    let dist2 = |c: [u8; 3]| -> i32 {
        (0..3)
            .map(|i| {
                let d = c[i] as i32 - px[i] as i32;
                d * d
            })
            .sum()
    };
    *refs.iter().min_by_key(|c| dist2(**c)).unwrap()
}

/// 往 JPEG 字节里插一个只含 Orientation 的 APP1(EXIF)段。
fn with_exif_orientation(jpeg: Vec<u8>, o: u16) -> Vec<u8> {
    let mut app1: Vec<u8> = Vec::new();
    app1.extend_from_slice(b"Exif\0\0");
    app1.extend_from_slice(b"MM\x00\x2a"); // 大端 TIFF
    app1.extend_from_slice(&8u32.to_be_bytes()); // IFD0 偏移
    app1.extend_from_slice(&1u16.to_be_bytes()); // 1 个条目
    app1.extend_from_slice(&0x0112u16.to_be_bytes()); // Orientation
    app1.extend_from_slice(&3u16.to_be_bytes()); // SHORT
    app1.extend_from_slice(&1u32.to_be_bytes()); // count
    app1.extend_from_slice(&o.to_be_bytes());
    app1.extend_from_slice(&0u16.to_be_bytes()); // padding 到 4 字节
    app1.extend_from_slice(&0u32.to_be_bytes()); // next IFD = 0

    let mut out = vec![0xFFu8, 0xD8, 0xFF, 0xE1];
    out.extend_from_slice(&((app1.len() + 2) as u16).to_be_bytes());
    out.extend_from_slice(&app1);
    out.extend_from_slice(&jpeg[2..]); // 去掉原 SOI 后拼接
    out
}

/// "四象限四色"的真 JPEG 字节, 可选写入 EXIF Orientation。
fn quadrant_jpeg_bytes(w: u32, h: u32, orientation: Option<u16>) -> Vec<u8> {
    let img = image::RgbImage::from_fn(w, h, |x, y| image::Rgb(quadrant_color(w, h, x, y)));
    let mut buf: Vec<u8> = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, 92)
        .encode_image(&img)
        .unwrap();
    match orientation {
        Some(o) => with_exif_orientation(buf, o),
        None => buf,
    }
}

/// 造一张真 JPEG（指定尺寸），并可选地写入 EXIF Orientation。
fn write_test_jpeg(path: &std::path::Path, w: u32, h: u32, orientation: Option<u16>) {
    std::fs::write(path, quadrant_jpeg_bytes(w, h, orientation)).unwrap();
}

/// EXIF Orientation 的**标准**语义, 在测试里独立写一份(不引用任何生产实现):
/// 结果图 (x,y) 处应当取自源图的哪个坐标。
/// 结果尺寸: 1-4 保持 (W,H), 5-8 交换成 (H,W)。
fn exif_source_coord(o: u16, w: u32, h: u32, x: u32, y: u32) -> (u32, u32) {
    match o {
        1 => (x, y),                     // 正常
        2 => (w - 1 - x, y),             // 水平镜像
        3 => (w - 1 - x, h - 1 - y),     // 旋转 180°
        4 => (x, h - 1 - y),             // 垂直镜像
        5 => (y, x),                     // 转置
        6 => (y, h - 1 - x),             // 顺时针 90°
        7 => (w - 1 - y, h - 1 - x),     // 镜像转置
        8 => (w - 1 - y, x),             // 顺时针 270°
        other => panic!("不是合法的 Orientation 值: {}", other),
    }
}

/// 断言结果图确实等于"按 Orientation=o 恰好变换过一次的源图"。
///
/// 取结果图的四个象限中心, 用标准语义反推它们应当来自源图哪个坐标, 再比颜色。
/// 这一条同时能抓住三件事: 0 次(尺寸就不对)、2 次(累积旋转: 6 转两次是 180°,
/// **尺寸会回到原样**, 只有颜色/象限会错)、以及"永远用转置"这类同形错变换。
fn assert_orientation_transform(jpeg: &[u8], src_w: u32, src_h: u32, o: u16, label: &str) {
    let img = image::load_from_memory(jpeg).expect("返回的必须是可解码的图");
    let (rw, rh) = img.dimensions();
    let expected = if o <= 4 { (src_w, src_h) } else { (src_h, src_w) };
    assert_eq!(
        (rw, rh), expected,
        "{}: Orientation={} 的结果尺寸不对(源图 {}x{})",
        label, o, src_w, src_h
    );

    for (name, rx, ry) in [
        ("左上", rw / 4, rh / 4),
        ("右上", rw * 3 / 4, rh / 4),
        ("左下", rw / 4, rh * 3 / 4),
        ("右下", rw * 3 / 4, rh * 3 / 4),
    ] {
        let (sx, sy) = exif_source_coord(o, src_w, src_h, rx, ry);
        let want = quadrant_color(src_w, src_h, sx, sy);
        let px = img.get_pixel(rx, ry).0;
        assert_eq!(
            nearest_quadrant([px[0], px[1], px[2]]),
            want,
            "{}: Orientation={} 时结果图{}象限中心 ({},{}) 应来自源图 ({},{}) [期望 {:?}], 实际像素 {:?}",
            label, o, name, rx, ry, sx, sy, want, px
        );
    }
}

// media 的三个 API 都是 async 的, 所以用 `#[tokio::test]` 而不是 `#[test]`(Future 没有 `unwrap`)。
#[tokio::test]
async fn preview_downsamples_to_max_and_never_upscales() {
    let dir = std::env::temp_dir().join(format!("ifmedia_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let big = dir.join("big.jpg");
    write_test_jpeg(&big, 4000, 3000, None);
    let small = dir.join("small.jpg");
    write_test_jpeg(&small, 800, 600, None);

    let got = image_filter_lib::server::media::preview_bytes(big.to_str().unwrap(), 2048, false)
        .await
        .unwrap();
    let (w, h) = image_dimensions_from_bytes(&got).unwrap();
    assert_eq!(w.max(h), 2048, "超限必须降到 max");
    assert_eq!((w, h), (2048, 1536), "必须保持宽高比");

    let got2 = image_filter_lib::server::media::preview_bytes(small.to_str().unwrap(), 2048, false)
        .await
        .unwrap();
    let (w2, h2) = image_dimensions_from_bytes(&got2).unwrap();
    assert_eq!((w2, h2), (800, 600), "小于 max 时绝不放大");

    std::fs::remove_dir_all(&dir).ok();
}

/// 方向矩阵: 8 种 Orientation 都必须按标准语义**恰好变换一次**。
///
/// 这一条专治旧测试看不见的东西:
///   · 0 次(完全不校正) → 6/8 的宽高没互换, 尺寸就不对;
///   · 2 次(累积旋转) → 6 转两次是 180°, **尺寸又回到 1000x500** —— 只读尺寸的断言
///     对此完全无感, 只有象限颜色能分开;
///   · "永远转置"这类同形错变换 → 6 和 5 的尺寸一样, 同样只有象限颜色能分开。
///
/// 走的是 `source == requested`(非 RAW 原图)分支。
#[tokio::test]
async fn preview_orientation_matrix_matches_the_eight_exif_transforms() {
    let dir = std::env::temp_dir().join(format!("iforient8_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();

    for o in 1u16..=8 {
        let p = dir.join(format!("o{}.jpg", o));
        write_test_jpeg(&p, 200, 100, Some(o));
        let got = image_filter_lib::server::media::preview_bytes(p.to_str().unwrap(), 2048, false)
            .await
            .unwrap();
        assert_orientation_transform(&got, 200, 100, o, "source == requested(原图分支)");
    }

    std::fs::remove_dir_all(&dir).ok();
}

/// **Fix 1 的回归护栏 —— 唯一会走到"源路径 ≠ 请求路径"分支的方向测试。**
///
/// 构造: 一个 `.arw` 假 RAW, 里面装一张 3000x64 的内嵌 JPEG, 且**带 EXIF Orientation=6**。
/// `get_full_image` 对"内嵌图长边 >= 3000"的 RAW 是把这个 JPEG **逐字节**拷进 `full_v3`
/// 缓存(`extract_largest_preview_full` 的那个分支), 既不物理旋转也不剥掉 EXIF。
/// 于是服务端拿到的是 `source != requested` 的、**方向根本没校正过**的文件。
/// 旧实现把"路径相同"当作"已经校正过"的代理, 在这个分支上**一次都不校正**:
/// 手机拿到躺着的 3000x64, 而桌面端因为 CSS `image-orientation: from-image` 显示是对的。
///
/// 连查两次(第一次写缓存、第二次命中缓存)都断言尺寸与象限位置 —— 0 次或 2 次校正都会红。
/// 注意本测试依赖 `images.rs` 里 `>= 3000` 这个阈值来命中那个逐字节拷贝分支;
/// 如果将来阈值变了, 这条仍然是有意义的方向测试, 但需要重新构造夹具才能继续覆盖该分支。
#[tokio::test]
async fn full_raw_verbatim_cache_with_exif_is_oriented_exactly_once() {
    // Fix B: 这条测试会驱动 `get_full_image`, 它的缓存目录是**用户真实的**应用数据
    // (`%LOCALAPPDATA%\image-filter\full_v3`)。修复前它直接往那里写, 每跑一次留一个
    // 6256 字节、带 EXIF 的条目(实测已堆 10 个, 每次跑测试再涨一个)。
    // 现在缓存根指向临时目录, Drop 时还原 + 删干净。
    let cache = TempCacheRoot::new("rawfull");
    let cache_root = cache.root().to_path_buf();

    let dir = std::env::temp_dir().join(format!("ifrawfull_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("fake.arw");
    std::fs::write(&p, quadrant_jpeg_bytes(3000, 64, Some(6))).unwrap();
    let path = p.to_str().unwrap().to_string();

    for label in [
        "第 1 次(full=1 首次, 逐字节写入 full_v3 缓存)",
        "第 2 次(同一缓存命中)",
    ] {
        let got = image_filter_lib::server::media::preview_bytes(&path, 5000, true)
            .await
            .unwrap();
        assert_orientation_transform(&got, 3000, 64, 6, label);
    }

    // 隔离必须**真的生效**: 缓存条目要落在临时根里。没有这条断言的话, 万一哪天
    // `cache_dir()` 不再看平台变量, 这条测试会**静默**回去写用户目录。
    assert_eq!(
        cached_jpg_count(&cache_root, "full_v3"),
        1,
        "这次 full=1 应当只在隔离根里写出一条 full_v3 条目"
    );

    // 而且用完必须不留东西。
    drop(cache);
    assert!(
        !cache_root.exists(),
        "隔离的缓存根必须在测试结束后被删干净 —— 反复跑测试不许留下任何文件"
    );
    assert_eq!(cached_jpg_count(&cache_root, "full_v3"), 0);

    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn thumbnail_returns_jpeg_bytes() {
    // 缩略图同样会写用户的 `thumbnails_v2` 缓存 → 一起隔离。
    let cache = TempCacheRoot::new("thumb");
    let cache_root = cache.root().to_path_buf();

    let dir = std::env::temp_dir().join(format!("ifthumb_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("t.jpg");
    write_test_jpeg(&p, 2000, 1500, None);

    let bytes = image_filter_lib::server::media::thumbnail_bytes(p.to_str().unwrap(), 300)
        .await
        .unwrap();
    assert_eq!(&bytes[..2], &[0xFF, 0xD8], "必须是 JPEG(SOI)");
    let (w, h) = image_dimensions_from_bytes(&bytes).unwrap();
    assert!(w.max(h) <= 300, "缩略图长边不得超过请求的 size");
    assert_eq!(cached_jpg_count(&cache_root, "thumbnails_v2"), 1);

    drop(cache);
    std::fs::remove_dir_all(&dir).ok();
}

/// size 契约的**对外**形态: 长边恒 ≤ 请求值(旧实现 `clamp(64,1024)` 会让 size=32 变成 64),
/// 比最小档位还小的请求报 `badRequest`(而不是偷偷给一张更大的图)。
#[tokio::test]
async fn thumbnail_never_returns_more_than_the_requested_size() {
    let cache = TempCacheRoot::new("thumbsize");
    let cache_root = cache.root().to_path_buf();

    let dir = std::env::temp_dir().join(format!("ifthumbsize_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("t.jpg");
    write_test_jpeg(&p, 2000, 1500, None);
    let path = p.to_str().unwrap();

    for req in [150u32, 300, 600, 1024] {
        let bytes = image_filter_lib::server::media::thumbnail_bytes(path, req)
            .await
            .unwrap();
        let (w, h) = image_dimensions_from_bytes(&bytes).unwrap();
        assert!(
            w.max(h) <= req,
            "请求 size={} 却拿到 {}x{} —— 比请求值更大就是违约",
            req,
            w,
            h
        );
    }

    let err = image_filter_lib::server::media::thumbnail_bytes(path, 32)
        .await
        .unwrap_err();
    assert_eq!(
        err.code(),
        "badRequest",
        "比最小档位还小的 size 必须报错, 不许返回比请求值更大的图"
    );

    // 档位只有 150/300/600 三档 → 1024 归到 600, 一共 3 个条目(而且都在隔离根里)
    assert_eq!(cached_jpg_count(&cache_root, "thumbnails_v2"), 3);

    drop(cache);
    std::fs::remove_dir_all(&dir).ok();
}

/// 存在的、本 build 解不了的文件 → `unsupportedForm`(不是 `notFound`)。
/// 不存在的路径仍然必须是 `notFound` —— 两条都断言, 否则"所有错误都报 unsupportedForm"
/// 也能让这条通过, 而那样手机就分不清"照片被删了"和"格式打不开"。
#[tokio::test]
async fn preview_distinguishes_undecodable_from_missing() {
    let dir = std::env::temp_dir().join(format!("ifunsup_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();

    let txt = dir.join("note.txt");
    std::fs::write(&txt, b"definitely not an image").unwrap();
    let err = image_filter_lib::server::media::preview_bytes(txt.to_str().unwrap(), 2048, false)
        .await
        .unwrap_err();
    assert_eq!(
        err.code(),
        "unsupportedForm",
        "文件存在但解不了 → unsupportedForm"
    );

    let missing = dir.join("ghost.jpg");
    let err2 = image_filter_lib::server::media::preview_bytes(missing.to_str().unwrap(), 2048, false)
        .await
        .unwrap_err();
    assert_eq!(err2.code(), "notFound", "文件不存在 → notFound");

    std::fs::remove_dir_all(&dir).ok();
}

/// **Fix D 的对外证明(一): 内部/IO 故障不许报成"格式不支持"。**
///
/// 旧实现 `source_error` 只看"请求的路径在不在": 只要源文件在, **任何**失败都是
/// `unsupportedForm`。于是"缓存目录建不出来"(磁盘满/权限/路径被占)也会被手机翻译成
/// "这个格式在电脑上也打不开", 让用户拿着一个完全没问题的文件反复重试。
///
/// 这条测试用**同一个源文件**、只换缓存环境, 逼出两个不同的码 —— 这样"源文件存在"就不是
/// 判定依据了:
///   · 缓存根被一个普通文件占住 → `create_dir_all` 失败 → 必须 `internal`;
///   · 缓存一切正常, 是文件本身解不了 → `unsupportedForm`(对照, 防止"一律报 internal")。
#[tokio::test]
async fn a_broken_cache_is_reported_as_internal_not_as_a_format_problem() {
    let dir = std::env::temp_dir().join(format!("ifinternal_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    // 假 RAW: 里面**没有**任何内嵌 JPEG(`extract_largest_preview_bytes` 找不到候选),
    // 所以缓存正常时它就是一个"解不了的真 RAW"。
    let raw = dir.join("fake.arw");
    std::fs::write(&raw, b"not a raw file at all").unwrap();
    let path = raw.to_str().unwrap();

    // (1) 缓存目录建不出来 —— 磁盘/权限问题, 与照片格式无关
    {
        let _blocked = TempCacheRoot::blocked_by_a_file("blocked");
        let err = image_filter_lib::server::media::preview_bytes(path, 2048, false)
            .await
            .unwrap_err();
        assert_eq!(
            err.code(),
            "internal",
            "缓存目录建不出来是磁盘/权限问题, 不许报 unsupportedForm —— 那会让用户以为格式有问题"
        );

        // 缩略图那条路(走的是 thumb_single 的 `Mkdir: ...`)必须同样诚实
        let terr = image_filter_lib::server::media::thumbnail_bytes(path, 300)
            .await
            .unwrap_err();
        assert_eq!(terr.code(), "internal", "缩略图路径的内部故障也必须是 internal");
    }

    // (2) 同一个文件、缓存正常 → 它是**真的**解不了 → unsupportedForm
    {
        let _cache = TempCacheRoot::new("healthy");
        let err = image_filter_lib::server::media::preview_bytes(path, 2048, false)
            .await
            .unwrap_err();
        assert_eq!(
            err.code(),
            "unsupportedForm",
            "缓存没问题时, RAW 里没有可用内嵌预览就是内容/格式问题 —— 不能一律报 internal"
        );
    }

    std::fs::remove_dir_all(&dir).ok();
}

/// **Fix D 的对外证明(二): 源文件在、但**读不出来**, 也要报 `internal`。**
///
/// 这一条走的是 media 层自己那次解码(`image::open`), 那里的判据不再是字符串, 而是
/// `image::ImageError` 的类别: `IoError` = 文件在但读不动 → `internal`;
/// 其余(Decoding/...) = 内容坏了 → `unsupportedForm`。
///
/// 用一个**目录**冒充 `.jpg`: 它 `Path::exists()` 为真(所以旧实现的分支条件满足),
/// 但 `File::open` 在 Windows 报"访问被拒"、在类 Unix 报 EISDIR —— 两边都是 `IoError`。
#[tokio::test]
async fn an_unreadable_source_is_reported_as_internal_not_as_a_format_problem() {
    let dir = std::env::temp_dir().join(format!("ifunread_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();

    let as_dir = dir.join("locked.jpg");
    std::fs::create_dir_all(&as_dir).unwrap();
    assert!(as_dir.exists(), "夹具的前提: 这个路径必须'存在'(旧实现的判据)");
    let err = image_filter_lib::server::media::preview_bytes(as_dir.to_str().unwrap(), 2048, false)
        .await
        .unwrap_err();
    assert_eq!(
        err.code(),
        "internal",
        "存在但读不出来的源(权限/被锁/是目录)是 IO 问题, 不是不支持这个格式"
    );

    // 对照一: 同一目录里一个**真的解不了**的普通文件 → unsupportedForm(不是 internal)
    let junk = dir.join("note.jpg");
    std::fs::write(&junk, b"definitely not an image").unwrap();
    let err2 = image_filter_lib::server::media::preview_bytes(junk.to_str().unwrap(), 2048, false)
        .await
        .unwrap_err();
    assert_eq!(
        err2.code(),
        "unsupportedForm",
        "内容坏了才是 unsupportedForm —— 两个码必须真的分得开"
    );

    // 对照二: 不存在 → notFound(这条判据不能被上面的改动弄丢)
    let ghost = dir.join("ghost.jpg");
    let err3 =
        image_filter_lib::server::media::preview_bytes(ghost.to_str().unwrap(), 2048, false)
            .await
            .unwrap_err();
    assert_eq!(err3.code(), "notFound", "不存在仍然是 notFound");

    std::fs::remove_dir_all(&dir).ok();
}

// ════════════════════════════════════════════════════════════════════════════
// 路由层: 鉴权中间件 + 共享根白名单(**必须在真 HTTP 栈上验**)
//
// 为什么这一段非要有: 上面那些 `ensure_within` 的单测证明的是"这个函数判得对",
// 而这一段要证明的是"**端点真的调了它**"。两者的差别就是漏洞本身 ——
// 一个没接白名单的端点(尤其"只读元数据"的那几个)在函数级测试里完全看不出来。
// 所以这里一律通过 `routes::router` 打真请求, 不看任何内部函数。
//
// 鉴权同理: 中间件若只挂在部分路由上, 函数级测试也发现不了, 只有逐条路径打请求才能证明。
// ════════════════════════════════════════════════════════════════════════════

use axum::body::Body;
use axum::http::{Request, StatusCode};
use image_filter_lib::server::routes::{PROTECTED_PATHS, PUBLIC_PATHS};
use tower::ServiceExt;

/// 测试用共享根。**真在磁盘上建出来**。
///
/// 这里的两句 `assert!` 不是装饰: 本仓库出过一次"测试因错误的原因通过" —— `ensure_within`
/// 对**不存在**的共享根会 `continue` 跳过, 于是"越界被拒"看起来成立, 其实拒绝来自
/// "一个可用的共享根都没有"。夹具自己先把根建出来并证明它可 canonicalize, 后面的
/// "根内成功/根外拒绝"两条断言才真的落在白名单逻辑上。
fn route_root(tag: &str) -> std::path::PathBuf {
    let root = std::env::temp_dir().join(format!("ifroute_{}_{}", tag, std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(root.join("sub")).unwrap();
    // 非 ASCII 目录名: 中文路径在真实用户那里是常态, 而它要经过百分号编码 + UTF-8 解码
    std::fs::create_dir_all(root.join("子目录")).unwrap();
    std::fs::write(root.join("a.jpg"), b"jpegbytes").unwrap();
    std::fs::write(root.join("sub").join("b.jpg"), b"jpegbytes").unwrap();
    std::fs::write(root.join("子目录").join("c.jpg"), b"jpegbytes").unwrap();
    std::fs::write(root.join("note.txt"), b"notaphoto").unwrap();

    assert!(root.is_dir(), "夹具前提: 共享根必须真的建出来");
    assert!(
        root.canonicalize().is_ok(),
        "夹具前提: 共享根必须可 canonicalize —— 否则 ensure_within 会跳过它, 白名单测试就测了个空"
    );
    root
}

/// 打真 `routes::router` 的 app + 固定 token + 共享根。
fn route_app(tag: &str) -> (axum::Router, String, std::path::PathBuf) {
    let root = route_root(tag);
    let token = "t".repeat(64); // 与真实 token 同形(64 字符)
    let cfg = image_filter_lib::server::ServerConfig {
        enabled: true,
        port: 0,
        token: token.clone(),
        shares: vec![root.clone()],
    };
    (
        image_filter_lib::server::routes::router(cfg.into_state()),
        token,
        root,
    )
}

/// 完整百分号编码(只放行 unreserved 字符)。**不能只替换反斜杠**:
/// Windows 路径里的 `\` `:` 空格, 以及中文目录名, 都必须编码后才是合法查询串。
fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn get_req(uri: &str, token: Option<&str>) -> Request<Body> {
    let b = Request::builder().uri(uri);
    let b = match token {
        Some(t) => b.header("Authorization", format!("Bearer {}", t)),
        None => b,
    };
    b.body(Body::empty()).unwrap()
}

async fn json_of(res: axum::response::Response) -> serde_json::Value {
    let bytes = axum::body::to_bytes(res.into_body(), 8 << 20)
        .await
        .expect("响应体应当可读");
    serde_json::from_slice(&bytes).expect("错误/正常响应都必须是 JSON")
}

/// 错误体只许有 `code` 一个字段。
///
/// 这条是**规格 §5.3 的实测修正**: 一度设计过 `{ code, detail }`, 但 `detail` 等于把中文
/// 文案硬编码进 Rust, 与"文案在前端、按 key 本地化"的纪律相反。这里显式钉住字段数,
/// 免得将来有人"顺手"把 detail 加回来。
fn assert_code_only(v: &serde_json::Value, want: &str) {
    assert_eq!(v["code"], want, "错误码不对: {}", v);
    assert_eq!(
        v.as_object().map(|o| o.len()),
        Some(1),
        "错误体只许有 code 一个字段(不许加 detail): {}",
        v
    );
}

/// 这条路径是否需要 `?path=`。
fn takes_path(base: &str) -> bool {
    matches!(
        base,
        "/api/v1/browse" | "/api/v1/scan" | "/api/v1/thumb" | "/api/v1/preview"
    )
}

/// 一条**必须是 JSON 错误体**的响应: 状态码对、`content-type` 是 JSON、体恰好 `{ code }`。
///
/// 提取器拒绝(下面 `extractor_...` 那条)与路由级兜底(405/404 那一组)期望的是**同一个
/// 形状**, 所以共用这一个断言 —— 两处各写一份就会漂移, 而这一层要防的恰好是"某一类错误
/// 悄悄不遵守契约"。
async fn assert_code_contract_error(
    res: axum::response::Response,
    want_status: StatusCode,
    want_code: &str,
    label: &str,
) {
    assert_eq!(res.status(), want_status, "{} 的状态码不对", label);
    assert_eq!(
        res.headers().get("content-type").and_then(|v| v.to_str().ok()),
        Some("application/json"),
        "{} 的错误体必须是 JSON(axum 的原生拒绝是 400 + 纯文本)",
        label
    );
    // `json_of` 在纯文本上会直接 panic —— 修复前那一轮就是靠这一句变红的。
    // (现在 `router()` 外面还有一层 `normalize_residual_errors`, 它会把纯文本也改写成
    //  同样的 `{code}`, 所以"提取器类型用错"在**这个文件里**已观测不到; 分工见下面
    //  `extractor_rejections_obey_the_code_contract` 的注释。)
    assert_code_only(&json_of(res).await, want_code);
}

/// **Fix 1 的对外证明: 提取器拒绝也必须落在 `{ code }` 契约里。**
///
/// 修复前 axum 在**处理器跑起来之前**就把请求挡回去了, 回的是 400 + **纯文本**
/// (`Failed to deserialize query string`), 而规格 §5.3 给这种情况的动作很明确:
/// `badRequest` = "换一个请求"。
///
/// 手机端**今天**为什么没有因此出问题(上一轮注释在这里说得过满, 已按实测更正): M1′ 的
/// `ApiError.fromHttp(status, bodyCode)` 是 `bodyCode ?: when (status)` —— 状态码是兜底,
/// 而 400 恰好在那张兜底表的 `badRequest` 一行, 所以一口纯文本 400 到了手机上**仍然是
/// `badRequest`**。这条测试守的不是"手机今天会不会炸", 而是"服务端的响应形状自己成立":
/// 兜底表里没有 405/404 的项, 且客户端一按计划收紧解析器(从响应体读码), 纯文本立刻变成
/// 未知错误 —— 契约不该靠对端的兜底表恰好覆盖。
///
/// 覆盖分三段, 缺一段就有一个洞:
///   (1) **所有吃 `path` 的端点** —— 清单从生产代码的 `PROTECTED_PATHS` 枚举(配 `takes_path`),
///       缺 `path` → 400 + `{code:badRequest}`。上一轮的漏洞正在这里: `scan` 漏在清单外,
///       只把它一条改回 `Query<PathQ>` 时整套契约测试仍然 25/25 全绿;
///   (2) 其余受保护端点(不吃 path)发同一个请求: 只许 200 的 JSON, 或被 (1) 覆盖的 400。
///       这一段**不依赖 `takes_path` 是否同步更新** —— 将来往 `PROTECTED_PATHS` 加一条吃
///       path 的端点却忘了改 `takes_path` 时, 它照样会红;
///   (3) `size`/`max`/`full` 的**类型错误** —— 与"缺字段"是不同的拒绝路径, 必须单独测。
///
/// **诚实标注一句(实测)**: 自从 `router()` 最外层有了 `normalize_residual_errors`(见下面
/// "残余非 `{ code }` 响应"那一组), 这三段断言**同时也被那层安全网满足** —— 裸 `Query<T>`
/// 的 400 纯文本会被它改写成形状逐字节相同的 `{code:badRequest}`。所以"某个处理器回退成裸
/// 提取器"这件事在本文件里**已经观测不到**了(实测: 只把 `scan` 改回 `Query<PathQ>`, 全套
/// 依然全绿);真正把它钉住的是 lib 单测
/// `server::routes::tests::production_handlers_return_code_bodies_even_without_the_safety_net`
/// (同一张生产路由表, 但没有那层网)。这里保留三段覆盖, 是因为它们仍然是端到端的契约断言:
/// 两层都退化时它们会红。
#[tokio::test]
async fn extractor_rejections_obey_the_code_contract() {
    let (app, token, root) = route_app("extract");
    let enc = urlencode(&root.to_string_lossy());

    // (1) 吃 path 的端点: 缺 path 必须 400 + 恰好 { code: badRequest }
    let mut path_endpoints = 0usize;
    for base in PROTECTED_PATHS.iter().filter(|b| takes_path(b)) {
        path_endpoints += 1;
        let res = app
            .clone()
            .oneshot(get_req(base, Some(&token)))
            .await
            .unwrap();
        assert_code_contract_error(res, StatusCode::BAD_REQUEST, "badRequest", base).await;
    }
    // 数一下"枚举真的抓到了东西": 没有这一句, `takes_path` 哪天被改窄(比如漏掉 scan),
    // 上面那个循环会安静地少跑几条, 测试依然全绿。
    assert!(
        path_endpoints >= 4,
        "吃 path 的端点至少要有 4 条(browse/scan/thumb/preview), 实际只枚举到 {} 条 —— \
         `takes_path` 被改窄了, 这段覆盖就是空的",
        path_endpoints
    );
    assert!(
        takes_path("/api/v1/scan"),
        "scan 吃 path —— 它上一轮正是漏在这份清单里(裸回退到 Query<PathQ> 时全套测试仍绿), \
         所以单独钉一句"
    );

    // (2) 盲扫生产清单: 缺 path 只许两种结果 —— 200 的 JSON, 或 400 + {code:badRequest}。
    // 不许出现第三种(axum 的 400 + 纯文本)。这一段不看 `takes_path`, 所以它能抓住
    // "新端点进了 PROTECTED_PATHS 但没进 takes_path"。
    for base in PROTECTED_PATHS {
        let res = app
            .clone()
            .oneshot(get_req(base, Some(&token)))
            .await
            .unwrap();
        if res.status() == StatusCode::BAD_REQUEST {
            assert_code_contract_error(res, StatusCode::BAD_REQUEST, "badRequest", base).await;
        } else {
            assert_eq!(
                res.status(),
                StatusCode::OK,
                "{} 缺 path 的响应既不是 400 也不是 200 —— 契约里没有第三种",
                base
            );
            let _ = json_of(res).await; // 200 也必须是 JSON 体
        }
    }

    // (3) 类型错误(不是缺字段)
    for (label, uri) in [
        ("size 不是数字", format!("/api/v1/thumb?path={}&size=abc", enc)),
        ("max 不是数字", format!("/api/v1/preview?path={}&max=-1", enc)),
        ("full 不是布尔", format!("/api/v1/preview?path={}&full=abc", enc)),
    ] {
        let res = app
            .clone()
            .oneshot(get_req(&uri, Some(&token)))
            .await
            .unwrap();
        assert_code_contract_error(
            res,
            StatusCode::BAD_REQUEST,
            "badRequest",
            &format!("{} ({})", label, uri),
        )
        .await;
    }

    std::fs::remove_dir_all(&root).ok();
}

/// 逐条挂载路径证明: **不带 token 一律 401**, 带正 token 才真的到达处理器。
#[tokio::test]
async fn every_mounted_endpoint_requires_a_bearer_token() {
    // thumb/preview 在带正 token 那一轮会真的走到 media 层 → 可能写缩略图缓存,
    // 所以把缓存根指到临时目录(绝不动用户真实的应用数据)。
    let _cache = TempCacheRoot::new("routeauth");
    let (app, token, root) = route_app("auth");
    let enc = urlencode(&root.to_string_lossy());

    for base in PROTECTED_PATHS {
        let uri = if takes_path(base) {
            format!("{}?path={}", base, enc)
        } else {
            (*base).to_string()
        };

        // (1) 完全没有 Authorization 头
        let res = app.clone().oneshot(get_req(&uri, None)).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::UNAUTHORIZED,
            "{} 没有 token 必须 401(漏挂中间件会在这里现形)",
            uri
        );
        assert_code_only(&json_of(res).await, "unauthorized");

        // (2) 错 token: 空串 / 乱码 / 长度短一位 / 长一位 / 只给 scheme 不给值
        for bad in ["", "zzz", &"t".repeat(63), &"t".repeat(65), "Bearer"] {
            let res = app
                .clone()
                .oneshot(get_req(&uri, Some(bad)))
                .await
                .unwrap();
            assert_eq!(
                res.status(),
                StatusCode::UNAUTHORIZED,
                "{} 的错 token {:?} 必须 401",
                uri,
                bad
            );
        }

        // (3) 正 token: 这条路径必须**真的挂载着**。
        // 没有这一句, 上面那些 401 可能只是"这个路径根本不存在"—— 那测试就白写了。
        let res = app
            .clone()
            .oneshot(get_req(&uri, Some(&token)))
            .await
            .unwrap();
        assert!(
            !matches!(
                res.status(),
                StatusCode::NOT_FOUND | StatusCode::METHOD_NOT_ALLOWED
            ),
            "{} 带正 token 却是 {} —— 说明路径没挂上, 上面 401 的断言没有意义",
            uri,
            res.status()
        );
    }

    std::fs::remove_dir_all(&root).ok();
}

/// 免鉴权的路径**只有** `ping` 一条 —— 连"不存在的路径"都不许在未鉴权时被区分出来。
///
/// 为什么未知路径也断言 401: 鉴权层套在整张路由表(含 axum 的兜底)外面, 这是刻意的
/// fail-closed 方向。做成 404 就等于给未配对的调用方一份"端点清单"探测手段;
/// 而且"更严"永远不会变成漏洞 —— 反过来(该要 token 却不要)才是。
/// 带正 token 时未挂载路径仍然是 404, 手机据此区分"服务没有这个功能"。
#[tokio::test]
async fn only_ping_is_public_and_unknown_paths_fail_closed() {
    let (app, token, root) = route_app("public");

    let res = app
        .clone()
        .oneshot(get_req("/api/v1/ping", None))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::OK,
        "ping 是配对入口, 必须不需要 token —— 中间件若把它也罩住, 配对就永远做不成"
    );

    let res = app
        .clone()
        .oneshot(get_req("/api/v1/nope", None))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::UNAUTHORIZED,
        "未挂载的路径在无 token 时也必须是 401(fail-closed, 不泄露端点清单)"
    );
    assert_code_only(&json_of(res).await, "unauthorized");

    let res = app
        .clone()
        .oneshot(get_req("/api/v1/nope", Some(&token)))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::NOT_FOUND,
        "带正 token 时未挂载路径才应是 404(否则手机分不清'没有这个功能'与'token 不对')"
    );

    std::fs::remove_dir_all(&root).ok();
}

/// **本任务的核心安全断言**: 共享根之外的**真实存在**路径, 每个吃路径的端点都必须是
/// `forbiddenPath`/403 —— 包括只读元数据的 `browse`/`scan`。
#[tokio::test]
async fn every_path_endpoint_refuses_a_real_path_outside_every_share() {
    let _cache = TempCacheRoot::new("routeforbid");
    let (app, token, root) = route_app("forbid");

    // 越界目标必须**真实存在**: 403 与 404 的区别只有在"存在但不在共享根内"时才唯一
    // 指向白名单。用一个不存在的路径, 403 可能只是"canonicalize 失败"的另一种表现。
    let outside_dir = std::env::temp_dir().join(format!("ifroute_out_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&outside_dir);
    std::fs::create_dir_all(&outside_dir).unwrap();
    let outside_file = outside_dir.join("secret.jpg");
    std::fs::write(&outside_file, b"secret bytes").unwrap();
    assert!(outside_file.is_file(), "夹具前提: 越界文件必须真的存在");
    assert!(
        !outside_file.starts_with(&root),
        "夹具前提: 越界文件不许落在共享根里"
    );

    for (base, target) in [
        ("/api/v1/browse", outside_dir.to_string_lossy().to_string()),
        ("/api/v1/scan", outside_dir.to_string_lossy().to_string()),
        ("/api/v1/thumb", outside_file.to_string_lossy().to_string()),
        ("/api/v1/preview", outside_file.to_string_lossy().to_string()),
    ] {
        let uri = format!("{}?path={}", base, urlencode(&target));
        let res = app
            .clone()
            .oneshot(get_req(&uri, Some(&token)))
            .await
            .unwrap();
        assert_eq!(
            res.status(),
            StatusCode::FORBIDDEN,
            "{} 对共享根外的既有路径必须 403(而不是 404/200)",
            base
        );
        assert_code_only(&json_of(res).await, "forbiddenPath");
    }

    // 缩略图/预览还有一条额外要求: 拒绝时**一个字节的图片都不许给**。
    let uri = format!(
        "/api/v1/preview?path={}",
        urlencode(&outside_file.to_string_lossy())
    );
    let res = app.oneshot(get_req(&uri, Some(&token))).await.unwrap();
    let bytes = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    assert!(
        !bytes.starts_with(&[0xFF, 0xD8]),
        "被拒的越界请求不许返回 JPEG 字节"
    );

    let _ = std::fs::remove_dir_all(&outside_dir);
    std::fs::remove_dir_all(&root).ok();
}

/// 鉴权必须**先于**路径校验: 未配对的调用方不该通过 403/404 的差异探测磁盘上有什么。
#[tokio::test]
async fn auth_is_checked_before_the_path_whitelist() {
    let (app, _token, root) = route_app("order");
    // 一个明知越界的路径 + 明知不存在的路径: 无 token 时两者都必须是 401
    for target in [
        "C:\\Windows\\System32".to_string(),
        std::env::temp_dir().join("ifroute_ghost_never").to_string_lossy().to_string(),
    ] {
        let uri = format!("/api/v1/scan?path={}", urlencode(&target));
        let res = app.clone().oneshot(get_req(&uri, None)).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::UNAUTHORIZED,
            "未鉴权的请求必须先被 401 挡下, 不许泄露路径是否存在({})",
            target
        );
        assert_code_only(&json_of(res).await, "unauthorized");
    }
    std::fs::remove_dir_all(&root).ok();
}

/// 共享根**之内**: 三个浏览类端点必须正常工作, 并且字段名与 `src/types.ts` 逐字一致
/// (前端按 camelCase 的名字读, 序列化属性一旦丢了这里就红)。
#[tokio::test]
async fn paths_inside_a_share_are_served_with_the_frontend_field_names() {
    let (app, token, root) = route_app("inside");

    // ── /shares: 只吐配置里的共享根 ──
    let res = app
        .clone()
        .oneshot(get_req("/api/v1/shares", Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let v = json_of(res).await;
    assert_eq!(
        v["shares"].as_array().map(|a| a.len()),
        Some(1),
        "共享根列表必须恰好是配置里那一个: {}",
        v
    );
    assert_eq!(
        v["shares"][0].as_str().unwrap(),
        root.to_string_lossy(),
        "手机拿到的必须是桌面端配置的那个根(它只被允许在这里面活动)"
    );

    // ── /drives: 结构原样透出(可移动优先是既有实现的事) ──
    let res = app
        .clone()
        .oneshot(get_req("/api/v1/drives", Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let v = json_of(res).await;
    assert!(v.is_array(), "drives 必须是数组: {}", v);
    // Windows 上至少有一个卷(测试进程自己就跑在某个卷上)
    #[cfg(target_os = "windows")]
    assert!(
        !v.as_array().unwrap().is_empty(),
        "Windows 上盘符列表不该为空"
    );

    // ── /browse: 与 types.ts 的 FolderEntry 同形 ──
    let uri = format!("/api/v1/browse?path={}", urlencode(&root.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let v = json_of(res).await;
    assert_eq!(v["photoCount"], 1, "note.txt 不是照片, 只有 a.jpg 该被计入");
    assert_eq!(v["hasSubdirs"], true);
    let names: Vec<String> = v["subfolders"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["name"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(names, vec!["sub".to_string(), "子目录".to_string()]);
    assert_eq!(
        v["subfolders"][0]["photoCount"], 0,
        "与桌面端同语义: 子目录计数不递归(保持 0)"
    );

    // ── /scan: 只列照片, 且有稳定的字段名 ──
    let res = app
        .clone()
        .oneshot(get_req(&uri.replace("/browse", "/scan"), Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let v = json_of(res).await;
    let arr = v.as_array().unwrap();
    assert_eq!(arr.len(), 1, "note.txt 不该出现在照片列表里: {}", v);
    assert!(arr[0]["fileName"]
        .as_str()
        .unwrap()
        .eq_ignore_ascii_case("a.jpg"));
    assert_eq!(arr[0]["isRaw"], false);
    assert_eq!(arr[0]["isVideo"], false);
    assert!(arr[0]["exif"].is_object(), "exif 必须是对象(前端无条件读它)");
    // 不直接比字符串: 服务端返回的是 `ensure_within` 给的 **canonicalize 后**的路径,
    // Windows 上它带 `\\?\` 逐字前缀。真正的要求是"它指向共享根里那个文件"。
    assert_eq!(
        std::path::Path::new(arr[0]["path"].as_str().unwrap())
            .canonicalize()
            .unwrap(),
        root.join("a.jpg").canonicalize().unwrap(),
        "path 必须指向共享根内的那个文件: {}",
        arr[0]["path"]
    );

    // ── /scan 子目录: 非 ASCII 路径要能过查询串 ──
    let sub = root.join("子目录");
    let uri = format!("/api/v1/scan?path={}", urlencode(&sub.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK, "中文目录名必须能被请求");
    let v = json_of(res).await;
    assert_eq!(v.as_array().unwrap().len(), 1);
    assert!(v[0]["fileName"].as_str().unwrap().eq_ignore_ascii_case("c.jpg"));

    std::fs::remove_dir_all(&root).ok();
}

/// 端到端往返: 手机从 `/scan` 拿到的 `path` 必须能被**原样**送回服务端。
///
/// 这条专治一类很容易漏的真实缺陷: 服务端返回的是 `canonicalize` 后的路径(Windows 上带
/// `\\?\` 逐字前缀)。如果它只能当展示字段用 —— 手机下一步拿着它请求缩略图就被判越界 ——
/// 列表能显示、点开却是 403/404。函数级测试看不见这个问题, 只有真往返一次才会现形。
#[tokio::test]
async fn a_path_from_scan_can_be_sent_straight_back() {
    let _cache = TempCacheRoot::new("routeround");
    let (app, token, root) = route_app("round");
    let photo = root.join("real.jpg");
    std::fs::write(&photo, quadrant_jpeg_bytes(600, 400, None)).unwrap();

    // 1) 先 scan, 拿服务端自己给出的 path
    let uri = format!("/api/v1/scan?path={}", urlencode(&root.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    let v = json_of(res).await;
    let arr = v.as_array().unwrap();
    let echoed = arr
        .iter()
        .find(|p| p["fileName"] == "real.jpg")
        .expect("real.jpg 必须在列表里")["path"]
        .as_str()
        .unwrap()
        .to_string();
    assert!(echoed.ends_with("real.jpg"), "回送的应当是那个文件: {}", echoed);

    // 2) 原样回送 → 缩略图必须出图(白名单 + media 两边都得认这个路径)
    let uri = format!("/api/v1/thumb?path={}", urlencode(&echoed));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::OK,
        "服务端给出的 path 必须能原样回送, 否则手机列表点不开图: {}",
        echoed
    );
    let bytes = axum::body::to_bytes(res.into_body(), 8 << 20).await.unwrap();
    assert_eq!(&bytes[..2], &[0xFF, 0xD8], "回送请求也必须真的拿到 JPEG");

    // 3) 同一个回送路径也不许被判成越界(403 才是"白名单不认它")
    let uri = format!("/api/v1/browse?path={}", urlencode(&echoed));
    let res = app.oneshot(get_req(&uri, Some(&token))).await.unwrap();
    assert_ne!(
        res.status(),
        StatusCode::FORBIDDEN,
        "回送路径不许被判成越界(它是一个文件 → browse 报 notFound 才对)"
    );

    std::fs::remove_dir_all(&root).ok();
}

/// `scan_directory` **没有 Result**(对不存在的目录返回空 Vec), 所以"空文件夹"和
/// "路径不存在/是个文件"必须由路由层自己分开 —— 否则手机上两者长得一模一样。
#[tokio::test]
async fn scan_distinguishes_empty_from_missing_and_from_a_file() {
    let (app, token, root) = route_app("empty");

    // 根内一个**真实存在**的空目录 → 200 + 空数组(这是"这里没照片", 不是错误)
    let empty = root.join("sub");
    std::fs::remove_file(empty.join("b.jpg")).unwrap();
    let uri = format!("/api/v1/scan?path={}", urlencode(&empty.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(
        json_of(res).await.as_array().unwrap().is_empty(),
        "空目录必须是 200 + [](手机据此显示'这个文件夹没有照片')"
    );

    // 共享根内、但不存在 → 404 notFound(不是 200 + 空数组)
    let ghost = root.join("no_such_dir");
    let uri = format!("/api/v1/scan?path={}", urlencode(&ghost.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
    assert_code_only(&json_of(res).await, "notFound");

    // 共享根内、但给的是**文件** → 404(不能报成"空文件夹")
    let file = root.join("a.jpg");
    let uri = format!("/api/v1/scan?path={}", urlencode(&file.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::NOT_FOUND,
        "把文件当目录 scan 必须报错 —— scan_directory 会安静地返回空数组"
    );

    std::fs::remove_dir_all(&root).ok();
}

/// 缩略图/预览端点: 共享根内的真照片必须出真 JPEG, 且参数契约(闭集/区间)原样透出。
/// 顺带证明路由层**没有**把源文件原样发出去(缓存条目数就是证据)。
#[tokio::test]
async fn thumb_and_preview_endpoints_return_jpeg_and_keep_the_size_contract() {
    let cache = TempCacheRoot::new("routejpeg");
    let cache_root = cache.root().to_path_buf();
    let (app, token, root) = route_app("jpeg");

    let photo = root.join("real.jpg");
    std::fs::write(&photo, quadrant_jpeg_bytes(1200, 900, None)).unwrap();
    let enc = urlencode(&photo.to_string_lossy());

    // thumb: 默认 size=300 → 真 JPEG, 长边 ≤ 300
    let uri = format!("/api/v1/thumb?path={}", enc);
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert_eq!(
        res.headers().get("content-type").and_then(|v| v.to_str().ok()),
        Some("image/jpeg")
    );
    let bytes = axum::body::to_bytes(res.into_body(), 8 << 20).await.unwrap();
    assert_eq!(&bytes[..2], &[0xFF, 0xD8], "必须是 JPEG(SOI)");
    let (w, h) = image_dimensions_from_bytes(&bytes).unwrap();
    assert!(
        w.max(h) <= 300,
        "缩略图长边 {} 超过默认档位 300",
        w.max(h)
    );
    assert_eq!(
        cached_jpg_count(&cache_root, "thumbnails_v2"),
        1,
        "端点必须走 thumbnails_v2 缓存, 而不是把源文件直接发出去"
    );

    // preview: 真 JPEG, 长边 ≤ max
    let uri = format!("/api/v1/preview?path={}&max=600", enc);
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(res.into_body(), 8 << 20).await.unwrap();
    let (w, h) = image_dimensions_from_bytes(&bytes).unwrap();
    assert_eq!(w.max(h), 600, "超限必须降到 max 且保持比例");

    // 参数契约由 media.rs 判定, 但**必须经路由真的透出来**(400 + badRequest):
    let uri = format!("/api/v1/thumb?path={}&size=32", enc);
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    assert_code_only(&json_of(res).await, "badRequest");

    let uri = format!("/api/v1/preview?path={}&max=99999", enc);
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(
        res.status(),
        StatusCode::BAD_REQUEST,
        "max 越界是 400 badRequest, 不是被钳成别的值"
    );
    assert_code_only(&json_of(res).await, "badRequest");

    drop(cache);
    std::fs::remove_dir_all(&root).ok();
}

/// **次要修复的对外证明: RFC 7235 的 scheme 大小写不敏感。**
///
/// 旧实现用 `strip_prefix("Bearer ")` 只认这一种精确写法, 于是 `bearer <token>` 或多一个
/// 空格都会被判成 `unauthorized` —— 而手机把 `unauthorized` 映射成"**去重新配对**",
/// 用户会为一个纯格式差异去扫码/轮换 token, 真正的原因只是大小写。
///
/// 反向断言同样重要: 放宽空白**不能**把错 token 放进来(那是把 fail-closed 松成了漏洞)。
#[tokio::test]
async fn bearer_scheme_is_case_insensitive_and_tolerates_ows() {
    let (app, token, root) = route_app("bearer");

    let authorized = |raw: String| {
        Request::builder()
            .uri("/api/v1/shares")
            .header("Authorization", raw)
            .body(Body::empty())
            .unwrap()
    };

    // (1) 这些写法都表示"带了正确的 token" → 200
    for raw in [
        format!("Bearer {}", token),
        format!("bearer {}", token),
        format!("BEARER {}", token),
        format!("BeArEr {}", token),
        format!("Bearer  {}", token),   // 多一个空格(RFC 的 `1*SP` = 一个或多个)
        format!("Bearer\t{}", token),   // Tab
        format!("Bearer {} ", token),   // 尾随 OWS(真实 HTTP 解析器本就会剥掉)
        format!(" bearer {}", token),   // 前导 OWS(同上)
        format!("\tbearer\t{}\t", token),
    ] {
        let res = app.clone().oneshot(authorized(raw.clone())).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::OK,
            "{:?} 应当被接受(scheme 大小写不敏感 + 容忍 OWS)",
            raw
        );
    }

    // (2) 放宽空白**不许**变成放行: 错 token / 错 scheme / 只有 scheme
    for raw in [
        "bearer".to_string(),                       // 没有凭证
        "bearer ".to_string(),                      // 只有空白
        "Bearer    ".to_string(),                   // 只有空白
        "Basic ".to_string() + &token,              // 别的 scheme
        "Bearerx ".to_string() + &token,            // scheme 前缀像但不是
        format!("bearer {}", &token[..63]),         // 少一位
        format!("bearer {}x", token),               // 多一位
        format!("bearer {}", token.to_uppercase()), // 大小写不同的 token(凭证本身不许模糊)
    ] {
        let res = app.clone().oneshot(authorized(raw.clone())).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::UNAUTHORIZED,
            "{:?} 必须仍然 401 —— 放宽的是空白与 scheme 大小写, 不是凭证本身",
            raw
        );
        assert_code_only(&json_of(res).await, "unauthorized");
    }

    std::fs::remove_dir_all(&root).ok();
}

/// **鉴权面: 公开的恰好是 `PUBLIC_PATHS`, 其余每条已挂载路径都在同一个鉴权层之后。**
///
/// 这条测试**能证明什么、不能证明什么**(上一版的名字与注释把它写成了"全仓库只有一个路由
/// 入口", 而那是它看不见的性质):
///   · **能证明(行为级)**: 从 `routes::router(state)` 逐条打请求 —— 公开面恰好
///     `PUBLIC_PATHS`(即 `ping`)、两份清单零交集、每条受保护路径无 token 一律
///     401 + `{code:unauthorized}`。三条都是"请求进来之后发生了什么";
///   · **不能证明(源码级)**: "全仓库不存在第二台 Router"。本测试只跟
///     `routes::router(state)` 说话, 第二台路由器只要没人调用它就观测不到 —— 实测: 把
///     `routes.rs`/`mod.rs` 回退到修复前、只留下这个(新的)测试文件, 这条测试**依然 `ok`**
///     (那一轮 23 passed / 2 failed, 红的是提取器与越界那几条)。第二台路由器的**删除**由
///     `mod.rs` 顶部的说明 + 代码评审负责, 不由测试负责。
///
/// (历史: 确实存在过一台**不带鉴权层**的 `server::router()`, 只挂 `ping`; 它已被整体删除,
/// 因为它是一条"谁往上挂端点谁就不需要 token"的后门。要强调的是: 删除它的**依据**从来不是
/// 这条测试 —— 这条测试在它存在时也是绿的。)
#[tokio::test]
async fn the_public_surface_is_exactly_ping_and_every_mounted_path_sits_behind_the_auth_layer() {
    let (app, _token, root) = route_app("oneentry");

    assert_eq!(PUBLIC_PATHS, &["/api/v1/ping"], "免鉴权路径只许有 ping 一条");
    for p in PROTECTED_PATHS {
        assert!(
            !PUBLIC_PATHS.contains(p),
            "{} 同时出现在两份清单里 —— 那意味着它在无 token 时可达",
            p
        );
    }

    for p in PUBLIC_PATHS {
        let res = app.clone().oneshot(get_req(p, None)).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::OK,
            "{} 是公开端点, 无 token 也必须 200(否则配对永远做不成)",
            p
        );
    }

    for p in PROTECTED_PATHS {
        let res = app.clone().oneshot(get_req(p, None)).await.unwrap();
        assert_eq!(
            res.status(),
            StatusCode::UNAUTHORIZED,
            "{} 无 token 必须 401 —— 鉴权层必须罩住**每一条**已挂载的受保护路径",
            p
        );
        assert_code_only(&json_of(res).await, "unauthorized");
    }

    std::fs::remove_dir_all(&root).ok();
}

// ════════════════════════════════════════════════════════════════════════════
// 残余非 `{ code }` 响应: 由 `router()` 最外层的 `map_response` 兜底
//
// 上面 `extractor_...` 那条测的是**提取器**拒绝(请求到了处理器门口才失败)。
// 这一组测的是另一种: 请求**根本没走到处理器** —— 方法不对(405)或路径没挂(404)。
// 实测(修复前, 带**正** token): 两种响应都是**空体 + 没有 `content-type`**。
// 手机端最吃亏的是 405: M1′ 的 `ApiError.fromHttp` 兜底表里没有 405, 于是"方法不对"
// 被翻译成 `internal`, 用户被告知"服务端内部错误, 请报 bug"。
//
// 兜底层只重写"status >= 400 **且** `content-type` 不是 `application/json`"的响应,
// 所以下面四条要一起证明的不是"错误被改写了", 而是**改写的边界在哪**:
// (a) 405 → JSON + badRequest; (b) 404 → JSON + notFound;
// (c) 已经是 JSON 的业务错误**保持自己的码**(状态码推不出来的那种, 例如 422 的
//     `unsupportedForm`); (d) 成功响应**逐字节**不变(二进制 JPEG / JSON 成功体)。
// ════════════════════════════════════════════════════════════════════════════

/// **(a) 已挂载端点上的错误方法 + 正 token → JSON 体, 恰好 `{ code: badRequest }`。**
///
/// 状态码仍然是 **405**(§5.3 只规定 body 的形状, 不改状态语义): 手机按体里的码决定行为,
/// 按状态码做日志/观测, 两者都不该丢。`Allow` 头也必须还在 —— 那是"正确的方法是哪个",
/// 换 body 不该把它吃掉。
#[tokio::test]
async fn a_wrong_method_on_a_mounted_endpoint_becomes_a_code_body() {
    let (app, token, root) = route_app("method405");
    let enc = urlencode(&root.to_string_lossy());

    // browse 只挂了 GET; 用 POST 打它(带正 token, 所以不是被鉴权层挡下的)
    let req = Request::builder()
        .method("POST")
        .uri(format!("/api/v1/browse?path={}", enc))
        .header("Authorization", format!("Bearer {}", token))
        .body(Body::empty())
        .unwrap();
    let res = app.oneshot(req).await.unwrap();

    assert_eq!(
        res.headers().get("allow").and_then(|v| v.to_str().ok()),
        Some("GET,HEAD"),
        "405 的 `Allow` 头必须保留(它描述的是这次请求, 不是被换掉的 body)"
    );
    assert_code_contract_error(res, StatusCode::METHOD_NOT_ALLOWED, "badRequest", "POST /browse")
        .await;

    std::fs::remove_dir_all(&root).ok();
}

/// **(b) 未挂载路径 + 正 token → `{ code: notFound }`(不是空体)。**
///
/// 这条与现有 `only_ping_is_public_and_unknown_paths_fail_closed` 里那句状态码断言是
/// 两件事: 那里证明的是"带正 token 时确实是 404", 这里证明的是"404 也得有 `{ code }` 体"。
#[tokio::test]
async fn an_unmounted_path_with_a_valid_token_still_returns_a_code_body() {
    let (app, token, root) = route_app("unmounted");

    let res = app
        .clone()
        .oneshot(get_req("/api/v1/nope", Some(&token)))
        .await
        .unwrap();
    assert_code_contract_error(res, StatusCode::NOT_FOUND, "notFound", "/api/v1/nope").await;

    // 对照组: 同一台路由器上, 已挂载的端点带正 token 不会变成 404 —— 免得上面那句
    // 只是因为"什么都 404"而成立。
    let res = app
        .oneshot(get_req("/api/v1/shares", Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);

    std::fs::remove_dir_all(&root).ok();
}

/// **(c) 已经是 JSON 的业务错误带着自己的码通过, 不许被状态码改写。**
///
/// 用的是一条**状态码推不出来**的样本: 共享根内一个存在但解不了的 `note.txt` → media 层
/// 给 422 + `unsupportedForm`。若兜底层按状态码改写, 422 那一档会变成 `badRequest` ——
/// 也就是把"这个格式打不开"说成"换个请求", 那是两种完全不同的用户动作。所以这条能真的
/// 把"透传"和"按状态码重写"两种实现分开。
///
/// 再补一条 403: 共享根外**真实存在**的路径 → `forbiddenPath`(§5.3 的语义, 不是 `notFound`)。
#[tokio::test]
async fn a_json_business_error_keeps_its_own_code_not_the_status_derived_one() {
    let _cache = TempCacheRoot::new("routenorm");
    let (app, token, root) = route_app("norm");

    // 422 + unsupportedForm
    let junk = root.join("note.txt");
    let uri = format!("/api/v1/preview?path={}", urlencode(&junk.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_code_contract_error(
        res,
        StatusCode::UNPROCESSABLE_ENTITY,
        "unsupportedForm",
        "preview 一个解不了的 .txt",
    )
    .await;

    // 403 + forbiddenPath
    let outside_dir = std::env::temp_dir().join(format!("ifroute_normout_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&outside_dir);
    std::fs::create_dir_all(&outside_dir).unwrap();
    let outside_file = outside_dir.join("secret.jpg");
    std::fs::write(&outside_file, b"secret bytes").unwrap();
    assert!(outside_file.is_file(), "夹具前提: 越界文件必须真的存在");
    assert!(!outside_file.starts_with(&root), "夹具前提: 它不许在共享根里");

    let uri = format!(
        "/api/v1/preview?path={}",
        urlencode(&outside_file.to_string_lossy())
    );
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_code_contract_error(res, StatusCode::FORBIDDEN, "forbiddenPath", "越界 preview").await;

    let _ = std::fs::remove_dir_all(&outside_dir);
    std::fs::remove_dir_all(&root).ok();
}

/// **(d) 成功响应逐字节原样通过 —— 兜底层不许碰它们。**
///
/// 两种代表, 断言的都是**字节**, 不是"字段值相等"(后者会被"重新序列化一遍"放过):
///   · **二进制体**: `thumb` 的响应体必须**逐字节等于它这次写进隔离缓存的那个文件**
///     (缓存根就在本测试手里, 直接从磁盘读出来比)。只要兜底层动了成功响应 —— 把 body 换成
///     JSON、或重编码一遍 —— 这里立刻不相等。比"和另一次 media 调用对比"更硬: 那条路会走到
///     `\\?\` 规范化路径 / WIC 外壳缩略图, 两次调用的缓存键与编码来源未必相同, 比出来的是
///     "两次生成是否巧合一致", 而不是"这次响应有没有被改";
///   · **JSON 成功体**: `/ping` 与 `/shares` 的字节必须与独立序列化出来的期望值完全相同。
///
/// 这条是变异验证的靶子: 把兜底层里的"status >= 400 才处理"这个早退拿掉, 缩略图会被换成
/// 一个 JSON 错误体, 这里就红。
#[tokio::test]
async fn successful_responses_pass_through_byte_for_byte() {
    let cache = TempCacheRoot::new("routenormok");
    let cache_root = cache.root().to_path_buf();
    let (app, token, root) = route_app("normok");

    // (1) 二进制: 缩略图 —— 响应体必须等于缓存里那个文件本身
    let photo = root.join("real.jpg");
    std::fs::write(&photo, quadrant_jpeg_bytes(1200, 900, None)).unwrap();
    let uri = format!("/api/v1/thumb?path={}", urlencode(&photo.to_string_lossy()));
    let res = app
        .clone()
        .oneshot(get_req(&uri, Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert_eq!(
        res.headers().get("content-type").and_then(|v| v.to_str().ok()),
        Some("image/jpeg")
    );
    let bytes = axum::body::to_bytes(res.into_body(), 8 << 20).await.unwrap();
    assert_eq!(&bytes[..2], &[0xFF, 0xD8], "必须仍然是 JPEG(SOI)");

    let thumbs = cache_root.join("image-filter").join("thumbnails_v2");
    let mut entries: Vec<std::path::PathBuf> = std::fs::read_dir(&thumbs)
        .expect("缩略图必须写进隔离的缓存根")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("jpg"))
        .collect();
    assert_eq!(entries.len(), 1, "这次请求应当只产出一个缩略图条目");
    let on_disk = std::fs::read(entries.pop().unwrap()).unwrap();
    assert_eq!(
        bytes.as_ref(),
        on_disk.as_slice(),
        "缩略图的响应体必须逐字节等于缓存里的那个文件(兜底层改写/重编码了成功响应?)"
    );

    // (2) JSON 成功体: /ping(公开) 与 /shares(受保护)—— 字节层面完全一致
    let res = app
        .clone()
        .oneshot(get_req("/api/v1/ping", None))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert_eq!(
        res.headers().get("content-type").and_then(|v| v.to_str().ok()),
        Some("application/json")
    );
    let bytes = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let want = serde_json::to_vec(&serde_json::json!({
        "name": "ImageFilter",
        "version": env!("CARGO_PKG_VERSION"),
        "requiresToken": true,
    }))
    .unwrap();
    assert_eq!(
        bytes.as_ref(),
        want.as_slice(),
        "/ping 的字节必须与独立序列化的期望值完全相同"
    );

    let res = app
        .clone()
        .oneshot(get_req("/api/v1/shares", Some(&token)))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let want = serde_json::to_vec(&serde_json::json!({
        "shares": [root.to_string_lossy()],
    }))
    .unwrap();
    assert_eq!(
        bytes.as_ref(),
        want.as_slice(),
        "/shares 的字节必须与独立序列化的期望值完全相同"
    );

    drop(cache);
    std::fs::remove_dir_all(&root).ok();
}
