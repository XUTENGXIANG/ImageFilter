// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 缩略图与预览的字节化。
//!
//! 缓存逻辑**一行都不重写**: `get_thumbnail_path` / `get_preview_image` /
//! `get_full_image` 已经返回缓存文件路径（`images.rs:104/113/160`），
//! 这里只负责"读字节"或"读字节 + 降采样"。
//!
//! 画质契约(规格 §5.2): Lanczos3(`resize_max_edge`) + 显式 q88(`encode_jpeg_quality`)。

use crate::server::ApiError;
use std::path::Path;

/// 服务端预览的编码质量。**必须是显式值** —— `img.save()` 会走 JPEG 默认质量 ≈75。
const PREVIEW_JPEG_QUALITY: u8 = 88;

/// 缩略图字节。直接复用桌面端的 `thumbnails_v2` 缓存。
pub async fn thumbnail_bytes(path: &str, size: u32) -> Result<Vec<u8>, ApiError> {
    let size = size.clamp(64, 1024); // 防止手机要一张 20000px 的"缩略图"
    let cached = crate::scanner::images::get_thumbnail_path(path.to_string(), size)
        .await
        .map_err(|_| ApiError::NotFound)?;
    tokio::task::spawn_blocking(move || std::fs::read(&cached))
        .await
        .map_err(|_| ApiError::Internal)?
        .map_err(|_| ApiError::NotFound)
}

/// 预览字节。`full = true` 时走全解码链（`get_full_image`）。
pub async fn preview_bytes(path: &str, max: u32, full: bool) -> Result<Vec<u8>, ApiError> {
    let max = max.clamp(256, 5000);
    let requested = path.to_string();

    // 1) 借既有命令拿到"源图路径"（RAW → 缓存过的内嵌 JPEG; 非 RAW → 原图路径）
    let source = if full {
        crate::scanner::images::get_full_image(requested.clone())
            .await
            .map_err(|_| ApiError::NotFound)?
    } else {
        crate::scanner::images::get_preview_image(requested.clone())
            .await
            .map_err(|_| ApiError::NotFound)?
    };

    // spawn_blocking 要求闭包 'static: `requested` 后面还要用来比对, 所以显式克隆一份带进去
    // （直接借用 `&requested` 过不了编译）。
    let requested_for_block = requested.clone();

    // 2) 解码 → 降采样 → 编进内存。**重活必须离开 async 线程。**
    tokio::task::spawn_blocking(move || {
        let img = image::open(&source).map_err(|_| ApiError::NotFound)?;

        // 方向只能上一次（规格 §5.2 / 交接文档 §5 第 10 条）:
        //   · source == requested  → 拿到的是**原图**, 方向还没物理校正 → 这里校;
        //   · source != requested  → 拿到的是**缓存**(RAW 路径已在 images.rs:145-147 校过)
        //                            → 绝不能再校, 否则转 90°。
        let img = if source == requested_for_block {
            crate::scanner::images::apply_exif_orientation(Path::new(&requested_for_block), img)
        } else {
            img
        };

        let img = crate::scanner::images::resize_max_edge(img, max);
        let mut out = Vec::with_capacity(256 * 1024);
        crate::scanner::images::encode_jpeg_quality(&img, &mut out, PREVIEW_JPEG_QUALITY)
            .map_err(|_| ApiError::Internal)?;
        Ok(out)
    })
    .await
    .map_err(|_| ApiError::Internal)?
}
