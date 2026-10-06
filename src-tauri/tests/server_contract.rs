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

// ── media(缩略图/预览字节)的画质契约 ─────────────────────────────────────────
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

    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn thumbnail_returns_jpeg_bytes() {
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

    std::fs::remove_dir_all(&dir).ok();
}

/// size 契约的**对外**形态: 长边恒 ≤ 请求值(旧实现 `clamp(64,1024)` 会让 size=32 变成 64),
/// 比最小档位还小的请求报 `badRequest`(而不是偷偷给一张更大的图)。
#[tokio::test]
async fn thumbnail_never_returns_more_than_the_requested_size() {
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
