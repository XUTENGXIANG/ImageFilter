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
pub mod paths;

use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use serde_json::json;

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

/// 组装路由。Task 4–8 会往这里挂更多端点。
pub fn router() -> Router {
    Router::new().route("/api/v1/ping", get(ping))
}

/// 配对与"测试连接"用。**不泄露任何路径信息**。
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

    #[tokio::test]
    async fn ping_returns_product_and_version_without_token() {
        let res = router()
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
