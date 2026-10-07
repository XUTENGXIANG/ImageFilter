// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 手机端访问的图库服务。
//!
//! 三条纪律（规格 §3.2）:
//!   1. 服务层只做"校验路径 → 调既有函数 → 序列化", 不许另写一套缩略图/预览/边车逻辑;
//!   2. 所有路径必须过 `paths::ensure_within`（共享根白名单）, 没有例外;
//!   3. 不许绕过既有的并发闸门（交接文档 §4.2）。

pub mod auth;
pub mod config;
pub mod media;
pub mod paths;
pub mod routes;

use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;
use std::sync::{Arc, RwLock};

/// 服务运行期状态。
///
/// 为什么是 `Arc<RwLock<_>>` 而不是直接存一份 `ServerConfig`: 用户在桌面端改共享根、
/// 轮换 token 之后, 服务**不该**需要重启才生效 —— 端点每次请求现读一次配置即可,
/// 而 `Arc` 让路由与后台任务能共享同一个状态(规格 §5.2 的"轮换后所有手机需重新配对")。
#[derive(Clone)]
pub struct ServerState {
    pub config: Arc<RwLock<config::ServerConfig>>,
}

impl config::ServerConfig {
    /// 把一份配置变成服务状态。**集成测试与 Task 8 的启动路径共用这一条入口**。
    pub fn into_state(self) -> ServerState {
        ServerState {
            config: Arc::new(RwLock::new(self)),
        }
    }
}

/// 闭集错误码（规格 §5.3）。**每条都必须落到"用户下一步做什么"**。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApiError {
    Unauthorized,
    ForbiddenPath,
    NotFound,
    Conflict,
    Busy,
    DiskFull,
    TooLarge,
    NotXmp,
    UnsupportedForm,
    BadRequest,
    Internal,
}

impl ApiError {
    pub fn code(self) -> &'static str {
        match self {
            ApiError::Unauthorized => "unauthorized",
            ApiError::ForbiddenPath => "forbiddenPath",
            ApiError::NotFound => "notFound",
            ApiError::Conflict => "conflict",
            ApiError::Busy => "busy",
            ApiError::DiskFull => "diskFull",
            ApiError::TooLarge => "tooLarge",
            ApiError::NotXmp => "notXmp",
            ApiError::UnsupportedForm => "unsupportedForm",
            ApiError::BadRequest => "badRequest",
            ApiError::Internal => "internal",
        }
    }

    fn status(self) -> StatusCode {
        match self {
            ApiError::Unauthorized => StatusCode::UNAUTHORIZED,
            ApiError::ForbiddenPath => StatusCode::FORBIDDEN,
            ApiError::NotFound => StatusCode::NOT_FOUND,
            ApiError::Conflict => StatusCode::CONFLICT,
            ApiError::Busy => StatusCode::LOCKED,
            ApiError::DiskFull => StatusCode::INSUFFICIENT_STORAGE,
            ApiError::TooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            ApiError::NotXmp | ApiError::UnsupportedForm => StatusCode::UNPROCESSABLE_ENTITY,
            ApiError::BadRequest => StatusCode::BAD_REQUEST,
            ApiError::Internal => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

/// 把既有的 XMP 闭集错误码映射到 HTTP 层。**码值原样对应**, 前端只需认一套码。
impl From<crate::xmp::XmpError> for ApiError {
    fn from(e: crate::xmp::XmpError) -> Self {
        use crate::xmp::XmpError::*;
        match e {
            NotXmp => ApiError::NotXmp,
            UnsupportedForm => ApiError::UnsupportedForm,
            TooLarge => ApiError::TooLarge,
            DiskFull => ApiError::DiskFull,
            Busy => ApiError::Busy,
            // 其余(WriteProtect/ReadOnly/Permission/Encoding/PathTooLong/Unknown)
            // 对手机端都表现为"这个位置写不了" → 归到 Internal 会让用户无从下手,
            // 因此统一映射为 Conflict: "换位置/改名/看详情" 是用户能做的动作。
            _ => ApiError::Conflict,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = Json(json!({ "code": self.code() }));
        (self.status(), body).into_response()
    }
}

/// 对外沿用 `server::ServerConfig` 这个名字（集成测试与 `into_state` 都用它）。
pub use config::ServerConfig;

// Fix 2（本轮修复）: 这里曾经有一个**不带鉴权层**的 `pub fn router() -> Router`, 只挂
// `/api/v1/ping`。它今天无害(ping 本来就是公开的 §5.1), 但它的存在本身就是一条后门:
// 谁把端点挂到它上面, 那条端点就**完全不需要 token**, 而且任何函数级测试都发现不了
// (与 `routes.rs` 顶部记的那次实测同源)。两个安全属性不同的入口 = 迟早有人挂错那个。
//
// 已**整体删除**: 现在全仓库只有 `routes::router(state)` 一个路由入口, 鉴权层罩住
// **整张**路由表(含兜底路由), 所以"新增端点漏挂鉴权"这件事在类型/结构上构造不出来。
// `ping` 不再需要一条独立入口 —— 它是 `PUBLIC_PATHS` 里的公开端点, 走同一个 router
// 同样免鉴权(见下面 `mod tests` 里那条测试, 以及 `routes::PUBLIC_PATHS`)。
//
// 注意"只有一个入口"是**源码级事实**, 只能靠评审维持: 任何路由级测试都只能证明"打进这台
// 路由器的请求都过了鉴权层", 观测不到另一台**没人调用**的路由器是否存在。`server_contract.rs`
// 里那条鉴权面测试的注释记了实测(旧代码 + 新测试 = 那条测试依然 ok)。
//
// **Task 8 请把路由接线全部收敛到 `routes::router(state)`; 不许再长出第二个 Router。**

/// 配对与"测试连接"用。**不泄露任何路径信息**。
///
/// 刻意**不加** `pub`: `routes.rs` 是 `server` 的子模块, 按 Rust 的可见性规则本来就能
/// 访问父模块的私有项。能不动既有代码就不动。
async fn ping() -> Json<serde_json::Value> {
    Json(json!({
        "name": "ImageFilter",
        "version": env!("CARGO_PKG_VERSION"),
        "requiresToken": true,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    /// `ping` 是唯一免鉴权的端点 —— 这条测试**从唯一入口** `routes::router(state)` 打请求。
    ///
    /// Fix 2 之前它调用的是那个不带鉴权层的 `server::router()`(已删除)。改成走这里之后,
    /// 这条测试钉住的是: **公开端点也在这台受鉴权的路由器上**(无 token 拿得到 200)。
    /// 它**不能**证明"没有第二台路由器" —— 另一个入口只要没人调用就观测不到(见
    /// `tests/server_contract.rs` 那条鉴权面测试的注释)。
    #[tokio::test]
    async fn ping_returns_product_and_version_without_token() {
        let app = routes::router(
            config::ServerConfig {
                enabled: true,
                port: 0,
                token: "t".repeat(64),
                shares: Vec::new(),
            }
            .into_state(),
        );
        let res = app
            .oneshot(Request::builder().uri("/api/v1/ping").body(Body::empty()).unwrap())
            .await
            .expect("router 应能处理请求");
        assert_eq!(res.status(), StatusCode::OK);

        let body = axum::body::to_bytes(res.into_body(), 64 * 1024).await.unwrap();
        let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(v["name"], "ImageFilter");
        assert_eq!(v["requiresToken"], true);
        // 不泄露路径: 整个响应里不该出现盘符或反斜杠
        assert!(!String::from_utf8_lossy(&body).contains('\\'));
    }

    #[test]
    fn every_error_code_is_unique_and_nonempty() {
        let all = [
            ApiError::Unauthorized, ApiError::ForbiddenPath, ApiError::NotFound,
            ApiError::Conflict, ApiError::Busy, ApiError::DiskFull, ApiError::TooLarge,
            ApiError::NotXmp, ApiError::UnsupportedForm, ApiError::BadRequest, ApiError::Internal,
        ];
        let mut codes: Vec<&str> = all.iter().map(|e| e.code()).collect();
        codes.sort_unstable();
        let n = codes.len();
        codes.dedup();
        assert_eq!(codes.len(), n, "错误码必须互不重复");
        assert!(codes.iter().all(|c| !c.is_empty()));
    }
}
