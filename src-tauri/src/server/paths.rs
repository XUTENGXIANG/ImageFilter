// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! **共享根白名单** —— 本设计唯一的安全红线。
//!
//! 服务默认关闭、token 强制, 但这些还不够: 没有白名单, 任何拿到 token 的人
//! 就能读整块磁盘。所有端点(含缩略图/预览/移动)都必须先过 `ensure_within`。

use std::path::{Path, PathBuf};

/// 路径必须落在某个共享根**之内**。返回 canonicalize 后的路径。
///
/// 两个必须记住的点:
///   1. **先 canonicalize 再比**: 否则 `root/../secret` 与符号链接都能逃逸;
///   2. **用 `Path::starts_with` 而不是字符串前缀**: 后者会让 `D:\photos2`
///      被当成 `D:\photos` 的子目录（`starts_with` 按路径组件比较, 不会）。
pub fn ensure_within(shares: &[PathBuf], requested: &Path) -> Result<PathBuf, super::ApiError> {
    let canon = requested
        .canonicalize()
        .map_err(|_| super::ApiError::NotFound)?;

    for root in shares {
        let Ok(root_canon) = root.canonicalize() else {
            continue; // 共享根自己不存在(盘没插/被删) → 跳过而不是整体报错
        };
        if canon == root_canon || canon.starts_with(&root_canon) {
            return Ok(canon);
        }
    }
    Err(super::ApiError::ForbiddenPath)
}
