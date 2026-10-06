// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 图库服务的契约测试。直接打 Router（`tower::ServiceExt::oneshot`），
//! 不占端口、不引 HTTP 客户端 —— 快且不会因端口占用而闪断。

/// 测试专用: 从字节解出宽高（测试里不在乎这次解码开销）。
///
/// `image 0.25` 没有"从字节读尺寸"的公开 API, 所以这里真解一次内存图 —— 断言看的是
/// "服务端确实产出了这个尺寸的像素", 而不是它自称的尺寸。
fn image_dimensions_from_bytes(b: &[u8]) -> Option<(u32, u32)> {
    // `dimensions()` 来自 trait(`DynamicImage` 只自带 width/height), 所以要显式引入
    use image::GenericImageView;
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
// 尤其是"方向只上一次"—— 它取决于源路径与缓存路径的分工, 假数据根本构造不出来。
//
// 另外: 断言一律从**产出的字节**再解一次尺寸, 不看服务端自报的数字,
// 否则"服务端说自己缩到了 2048"这种谎话会被原样放过。

/// 造一张真 JPEG（指定尺寸），并可选地写入 EXIF Orientation=6（旋转 90°）。
fn write_test_jpeg(path: &std::path::Path, w: u32, h: u32, orientation: Option<u16>) {
    let img = image::RgbImage::from_fn(w, h, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 90]));
    let mut buf: Vec<u8> = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, 90)
        .encode_image(&img)
        .unwrap();
    // 简易 EXIF(仅 Orientation): APP1 段
    if let Some(o) = orientation {
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
        let mut out = vec![0xFFu8, 0xD8];
        out.extend_from_slice(&[0xFF, 0xE1]);
        out.extend_from_slice(&((app1.len() + 2) as u16).to_be_bytes());
        out.extend_from_slice(&app1);
        out.extend_from_slice(&buf[2..]); // 去掉 SOI 后拼接
        buf = out;
    }
    std::fs::write(path, &buf).unwrap();
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

#[tokio::test]
async fn preview_applies_exif_orientation_exactly_once() {
    let dir = std::env::temp_dir().join(format!("iforient_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("rot.jpg");
    write_test_jpeg(&p, 1000, 500, Some(6)); // Orientation=6 → 应旋转成 500x1000

    let a = image_filter_lib::server::media::preview_bytes(p.to_str().unwrap(), 2048, false)
        .await
        .unwrap();
    let (w1, h1) = image_dimensions_from_bytes(&a).unwrap();
    assert_eq!((w1, h1), (500, 1000), "Orientation=6 必须让宽高互换(只转一次)");

    // 再请求一次（走同一条路径）: 结果必须完全一致, 证明没有累积旋转
    let b = image_filter_lib::server::media::preview_bytes(p.to_str().unwrap(), 2048, false)
        .await
        .unwrap();
    let (w2, h2) = image_dimensions_from_bytes(&b).unwrap();
    assert_eq!((w1, h1), (w2, h2), "重复请求不能再次旋转");

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
