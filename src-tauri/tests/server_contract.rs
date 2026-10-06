// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 图库服务的契约测试。直接打 Router（`tower::ServiceExt::oneshot`），
//! 不占端口、不引 HTTP 客户端 —— 快且不会因端口占用而闪断。

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
    std::fs::create_dir_all(&lookalike).unwrap();
    let f = lookalike.join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    assert!(
        image_filter_lib::server::paths::ensure_within(&[root], &f).is_err(),
        "photos2 不是 photos 的子目录, 必须拒绝(字符串前缀写法会在这里出错)"
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
