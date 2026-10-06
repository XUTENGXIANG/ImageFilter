// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 端点实现。**每个处理器只做三件事**: 校验路径 → 调既有函数 → 序列化。
//!
//! 本模块把规格 §3.2 的第 2 条("所有路径必须过共享根白名单, 没有例外")与 §5.2 的
//! 鉴权落地成**两处、且只有两处**的收口, 都不依赖"记得写":
//!
//!   1. **鉴权是一层真中间件**, 由 `router()` 套在**整张路由表**外面, 免鉴权的只有
//!      `PUBLIC_PATHS`(即 `ping`)。端点自己**不许**再判一次 token —— 判两次的代码
//!      会在修复一次漏洞时只改一处。路由表与鉴权层分在两个函数里, 所以"新增端点忘了
//!      挂鉴权"这件事**构造不出来**(实测: 层与路由写在同一条链子里时, 层之后追加的
//!      路由无 token 会直接 200)。
//!   2. **吃调用方路径的端点一律先过 `resolve_within_shares`**(内部即
//!      `paths::ensure_within`), 并且**只用它返回的规范化路径**去调既有函数。
//!      连只读元数据的 `browse`/`scan` 也一样: 白名单是"能不能看"的红线, 不是"能不能改"。

use super::{auth, media, paths, ApiError, ServerState};
use axum::body::Body;
use axum::extract::{Query, Request, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::Deserialize;
use std::path::PathBuf;

/// 受保护端点的路径清单。
///
/// 存在的理由: 集成测试要**逐条挂载路径**证明"无 token 一律 401"。清单放在路由旁边,
/// 新增端点时一眼能看见"这里也要加一行"; 只把清单写进测试文件就会两边各写一份而悄悄漂移。
pub const PROTECTED_PATHS: &[&str] = &[
    "/api/v1/shares",
    "/api/v1/drives",
    "/api/v1/browse",
    "/api/v1/scan",
    "/api/v1/thumb",
    "/api/v1/preview",
];

/// Bearer 校验。规格 §5.2: token 长度固定且公开, 长度不同直接拒不算泄露。
fn check_auth(state: &ServerState, headers: &HeaderMap) -> Result<(), ApiError> {
    let cfg = state.config.read().map_err(|_| ApiError::Internal)?;
    let got = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or("");
    // `eq_constant_time` 对空串一律 false(fail-closed), 所以"配置里 token 变空"不会
    // 变成"关掉鉴权"—— 这条不变量在 auth.rs 里有自己的单测。
    if auth::eq_constant_time(got, &cfg.token) {
        Ok(())
    } else {
        Err(ApiError::Unauthorized)
    }
}

/// 公开端点白名单(规格 §5.1: 只有配对用的 `ping` 免鉴权)。
///
/// 为什么写成**白名单**而不是给受保护的端点逐条加层 —— 两者的失败方向相反:
///   · 白名单漏了一条 → 那条端点**仍然要求 token**(多要一次, 测试立刻红, 手机重配对即可);
///   · 逐条加层漏了一条 → 那条端点**完全不需要 token**, 而且函数级测试看不出来。
/// 后者已经实测过: 在 `route_layer` 之后再 `.route(...)` 一条, 无 token 直接 200。
/// 鉴权这种红线只许往"更严"的方向失败。
pub const PUBLIC_PATHS: &[&str] = &["/api/v1/ping"];

/// 鉴权中间件。**本模块唯一的鉴权入口, 且罩住整台路由器**(见 `router`)。
async fn require_bearer(
    State(st): State<ServerState>,
    headers: HeaderMap,
    request: Request,
    next: Next,
) -> Result<Response, ApiError> {
    if PUBLIC_PATHS.contains(&request.uri().path()) {
        return Ok(next.run(request).await);
    }
    check_auth(&st, &headers)?;
    Ok(next.run(request).await)
}

/// 共享根快照(克隆一份, 避免持锁跑文件 IO)。
fn shares_of(state: &ServerState) -> Result<Vec<PathBuf>, ApiError> {
    Ok(state.config.read().map_err(|_| ApiError::Internal)?.shares.clone())
}

/// **路径白名单的唯一入口**: 读配置里的共享根 → `ensure_within` → 返回规范化后的路径。
///
/// 调用方必须把返回值**用起来**(去调既有函数), 而不是继续用自己手里的字符串:
/// `ensure_within` 返回的是 `canonicalize` 过的路径, 它消掉了 `..` 与符号链接, 于是
/// "被校验的路径"和"被打开的路径"是同一个值 —— 否则校验与使用之间就有缝。
fn resolve_within_shares(state: &ServerState, requested: &str) -> Result<PathBuf, ApiError> {
    paths::ensure_within(&shares_of(state)?, std::path::Path::new(requested))
}

/// 端点路由表。**新增端点只加在这里。**
///
/// 这里**不挂**鉴权层: 层由 `router()` 套在整个返回值外面。这个分工是刻意的 ——
/// 实测确认过 `layer`/`route_layer` 都只罩"调用它之前已经挂上的路由", 所以只要把
/// `.layer(...)` 写在同一条链子里, 后来追加的一条 `.route(...)` 就会**完全不需要 token**
/// (实测: 无 token 直接 200)。把路由表和鉴权层分成两个函数之后, 这种漏挂就构造不出来了:
/// 这个函数里无论怎么加路由, 返回值都会被 `router()` 包上鉴权。
fn endpoint_routes() -> Router<ServerState> {
    Router::new()
        .route("/api/v1/ping", get(super::ping))
        .route("/api/v1/shares", get(shares))
        .route("/api/v1/drives", get(drives))
        .route("/api/v1/browse", get(browse))
        .route("/api/v1/scan", get(scan))
        .route("/api/v1/thumb", get(thumb))
        .route("/api/v1/preview", get(preview))
}

/// 完整路由: **整台路由器**外面套一层鉴权中间件。
///
/// 免鉴权的只有 `PUBLIC_PATHS` 里那几个(即 `ping`)。未挂载的路径也走这一层
/// (axum 的 `layer` 会罩住兜底路由), 所以不存在的路径在未鉴权时同样是 401 ——
/// 失败方向是"更严", 顺带也不向未配对的调用方泄露端点清单。带上正 token 时
/// 未挂载路径仍然是 404, 手机据此区分"服务没有这个功能"。
pub fn router(state: ServerState) -> Router {
    endpoint_routes()
        .layer(middleware::from_fn_with_state(state.clone(), require_bearer))
        .with_state(state)
}

/// 手机只能在这几个根里活动(规格 §5.1)。共享根为空数组是**安全的默认值**: 谁都访问不了。
async fn shares(State(st): State<ServerState>) -> Result<Response, ApiError> {
    let list: Vec<String> = shares_of(&st)?
        .into_iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect();
    Ok(Json(serde_json::json!({ "shares": list })).into_response())
}

/// 盘符/设备列表。`detect_drives` 是既有函数(`scanner/drives.rs`), 一律原样复用。
///
/// 它内部是同步的(逐盘 `metadata`), 但每个盘一次轻量系统调用、至多 26 次,
/// 与既有 `browse_directory`/`scan_directory` 在 async 里直接读目录是同一个量级 ——
/// 为它单开 `spawn_blocking` 只会多一次线程往返。
async fn drives() -> Result<Response, ApiError> {
    Ok(Json(crate::scanner::drives::detect_drives()).into_response())
}

#[derive(Deserialize)]
struct PathQ {
    path: String,
}

/// 单层目录(与桌面端 `browse_directory` 同一语义, 不做递归)。
async fn browse(
    State(st): State<ServerState>,
    Query(q): Query<PathQ>,
) -> Result<Response, ApiError> {
    let dir = resolve_within_shares(&st, &q.path)?;
    let entry = crate::scanner::browse::browse_directory(dir.to_string_lossy().to_string())
        .await
        .map_err(|e| {
            // `browse_directory` 只有两类失败, 必须分开报:
            //   · "Not a directory" = 调用方给了一个文件 → 请求问题;
            //   · "Read dir: ..."   = 目录在、但读不动(权限/被占) → IO 故障。
            // 把后者说成 notFound 会让手机提示"目录不存在, 刷新一下", 而用户真正该做的是
            // 去看权限 —— 与 `media.rs` 里"内部故障不许报成格式问题"是同一条纪律。
            if e.starts_with("Not a directory") {
                ApiError::NotFound
            } else {
                ApiError::Internal
            }
        })?;
    Ok(Json(entry).into_response())
}

/// 照片列表(含 EXIF 字段, 由既有结构体直接序列化)。
async fn scan(
    State(st): State<ServerState>,
    Query(q): Query<PathQ>,
) -> Result<Response, ApiError> {
    let dir = resolve_within_shares(&st, &q.path)?;
    // `scan_directory` **没有 Result**: 对不存在的目录它返回空 Vec 而不是错误。
    // 所以这里必须自己挡一道 —— 否则手机分不清"空文件夹"与"路径不存在/是个文件",
    // 而"照片被删了"和"这里本来就没照片"对用户的提示完全不同(规格 §5.3)。
    if !dir.is_dir() {
        return Err(ApiError::NotFound);
    }
    let photos = crate::scanner::browse::scan_directory(dir.to_string_lossy().to_string()).await;
    Ok(Json(photos).into_response())
}

/// 图片响应。**只声明 `image/jpeg`**: 不额外发 cache 指令 —— 规格 §11 定的失效方式是
/// 手机端在 URL 上带 `?v=<mtime>`, 由手机端的图片库(Coil)自己缓存; 服务端若发 `no-store`
/// 会把那条缓存策略整个废掉(每个 URL 重下一次), 发长期 `max-age` 又会在没有 `?v=` 时
/// 供应旧图。两种都是替客户端做它已经做过的决定。
fn jpeg(bytes: Vec<u8>) -> Response {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "image/jpeg")],
        Body::from(bytes),
    )
        .into_response()
}

#[derive(Deserialize)]
struct ThumbQ {
    path: String,
    #[serde(default = "default_thumb_size")]
    size: u32,
}

/// 与规格 §5.2 的默认档一致。**档位闭集与越界判定不在这里** —— 那是 `media.rs` 的契约,
/// 本层只负责把参数原样递进去。
fn default_thumb_size() -> u32 {
    300
}

async fn thumb(
    State(st): State<ServerState>,
    Query(q): Query<ThumbQ>,
) -> Result<Response, ApiError> {
    let p = resolve_within_shares(&st, &q.path)?;
    let bytes = media::thumbnail_bytes(&p.to_string_lossy(), q.size).await?;
    Ok(jpeg(bytes))
}

#[derive(Deserialize)]
struct PreviewQ {
    path: String,
    #[serde(default = "default_preview_max")]
    max: u32,
    #[serde(default)]
    full: bool,
}

/// 规格 §5.2 的默认长边。
fn default_preview_max() -> u32 {
    2048
}

async fn preview(
    State(st): State<ServerState>,
    Query(q): Query<PreviewQ>,
) -> Result<Response, ApiError> {
    let p = resolve_within_shares(&st, &q.path)?;
    let bytes = media::preview_bytes(&p.to_string_lossy(), q.max, q.full).await?;
    Ok(jpeg(bytes))
}
