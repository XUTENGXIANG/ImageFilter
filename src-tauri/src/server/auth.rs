// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 配对 token 的生成与校验。
//!
//! 刻意不引 base64/hex/rand 之外的依赖: token 就是 32 字节随机数的十六进制表示,
//! 手写 hex 只需要 5 行, 不值得为它加一个 crate。

/// 生成 32 字节随机 token（64 个十六进制字符）。
pub fn new_token() -> String {
    let mut buf = [0u8; 32];
    getrandom::getrandom(&mut buf).expect("系统随机数不可用");
    let mut s = String::with_capacity(64);
    for b in buf {
        use std::fmt::Write as _;
        let _ = write!(s, "{:02x}", b);
    }
    s
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

    /// 空 token 绝不能通过鉴权 —— 包括"空对空"这种情况。
    /// 没有这一条, 一个空的配置 token 就等价于"关掉鉴权"。
    #[test]
    fn empty_never_authenticates() {
        assert!(!eq_constant_time("", ""), "空对空必须返回 false");
        assert!(!eq_constant_time("", "abc"));
        assert!(!eq_constant_time("abc", ""));
    }
}
