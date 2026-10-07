// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 配对 token 的生成与校验。
//!
//! 刻意不引 base64/hex/rand 之外的依赖: token 就是 32 字节随机数的十六进制表示,
//! 手写 hex 只需要 5 行, 不值得为它加一个 crate。

/// 生成 32 字节随机 token（64 个十六进制字符）。
///
/// ⚠️ **随机数不可用时本函数会 panic**。因此它**绝不允许**出现在桌面端的启动路径上 ——
/// 启动路径一律用下面的 `try_new_token()`（Task 8 之前 `ServerConfig::default()` 会调它，
/// 而 `load()` 在配置缺失/损坏时回落到 `default()`，于是"一次随机数失败"就能让整个桌面端
/// 崩在启动上；现在 `default()` 不再生成 token，见 `config.rs`）。
///
/// 签名与实现保留的理由: `auth.rs` 自己的单测与既有调用点依赖 `-> String`;
/// 生产路径（`boot` / `set_server_enabled` / `rotate_server_token`）一律走 `try_new_token`。
pub fn new_token() -> String {
    try_new_token().expect("系统随机数不可用")
}

/// `new_token` 的**可失败**版本: 随机数不可用只返回 `Err`, 不 panic。
///
/// 这是"服务起不来"与"桌面端崩掉"之间唯一的分界线: 调用方（`server::boot` /
/// `ensure_token` / Tauri 命令）把 `Err` 往上抛, 服务不启动、界面给一句提示,
/// **桌面端照常可用**（用户第一约束）。
///
/// 实现只有这一份: `new_token` 委托给它, 免得两处十六进制编码慢慢漂移。
pub fn try_new_token() -> Result<String, String> {
    let mut buf = [0u8; 32];
    getrandom::getrandom(&mut buf).map_err(|e| format!("系统随机数不可用: {}", e))?;
    let mut s = String::with_capacity(64);
    for b in buf {
        use std::fmt::Write as _;
        let _ = write!(s, "{:02x}", b);
    }
    Ok(s)
}

/// 常量时间比较。长度不同直接返回 false —— token 长度是固定且公开的, 不算泄露。
pub fn eq_constant_time(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    // **空串永不匹配, 包括空对空。** 这是防御性的一条: 两边长度都是 0 时下面的循环
    // 一次都不跑, diff 保持 0, 于是 `eq_constant_time("", "")` 会返回 true —— 一旦
    // 配置里的 token 因为任何原因变成空串(fail-closed 路径、配置损坏、将来重构),
    // 任何不带 token 的请求都会通过鉴权。宁可在最底层显式拒绝。
    if a.is_empty() || b.is_empty() {
        return false;
    }
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_shape_and_compare() {
        let t = new_token();
        assert_eq!(t.len(), 64);
        assert!(t.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(eq_constant_time(&t, &t));
        assert!(!eq_constant_time(&t, &new_token()));
        assert!(!eq_constant_time(&t, ""));
    }

    /// Task 8: **启动路径专用**的可失败构造函数。形状必须与 `new_token` 逐字一致,
    /// 否则"修掉一个 panic"会顺手换来一枚手机不认的 token。
    #[test]
    fn try_new_token_has_the_same_shape_and_is_unpredictable() {
        let a = try_new_token().expect("本机随机数可用时不该失败");
        let b = new_token();
        for t in [&a, &b] {
            assert_eq!(t.len(), 64);
            assert!(t.chars().all(|c| c.is_ascii_hexdigit()));
        }
        assert_ne!(a, b, "两次生成不该撞上同一枚");
    }

    /// 空 token 绝不能通过鉴权 —— 包括"空对空"这种情况。
    /// 没有这一条, 一个空的配置 token 就等价于"关掉鉴权"。
    #[test]
    fn empty_never_authenticates() {
        assert!(!eq_constant_time("", ""), "空对空必须返回 false");
        assert!(!eq_constant_time("", "abc"));
        assert!(!eq_constant_time("abc", ""));
    }
}
