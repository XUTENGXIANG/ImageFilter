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
}
