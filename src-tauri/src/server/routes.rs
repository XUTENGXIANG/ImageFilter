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
use axum::extract::{FromRequest, FromRequestParts, Query, Request, State};
use axum::http::request::Parts;
use axum::http::{header, HeaderMap, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::{de::DeserializeOwned, Deserialize};
use std::path::PathBuf;

// ── 提取器失败的收口: 一律压成闭集错误码 ─────────────────────────────────────
//
// 为什么必须有这一层: 规格 §5.3 要求服务端**统一**回 `{ code }`(且只有 `code` 一个字段)。
// 但 axum 的提取器(`Query`/`Json`/…)在**处理器跑起来之前**就会失败 —— 它回的是自己的
// 拒绝响应: 400 + **纯文本** `Failed to deserialize query string`。手机端的 `ApiError`
// 只读 `code`, 拿到纯文本只能当成"未知错误", 而用户真正该做的是"换一个请求"(§5.3 的
// `badRequest` 一行)。也就是说: **提取器一失败, 契约就漏了**。
//
// 收口位置选在**提取器类型**上, 而不是给每个处理器写 `Option<Query<T>>` / 手工取 body:
//   · 处理器写 `Query<T>` 是本能, 要求每个新处理器"记得多写一步"就一定会漏;
//   · 换成 `Q<T>`/`J<T>` 只是换一个类型名, 新端点照抄既有处理器即可;
//   · 万一有人直接用了 `Query<T>`/`Json<T>`, 缺参时漏出的是**纯文本** —— 这会被契约测试
//     当场打红(见 `tests/server_contract.rs` 的提取器用例), 而不是在线上悄悄发纯文本。
// 所以本模块的纪律是: **处理器一律用 `Q<T>`(查询串)/ `J<T>`(JSON 请求体) 取参数,
// 不许直接用 axum 的 `Query<T>` / `Json<T>`。**
//
// Task 6 的 `PUT /api/v1/decisions` **直接用 `J<DecisionWrite>` 即可**: 坏 JSON / 缺必填
// 字段 / 没有 `content-type: application/json` 这三种拒绝都会变成
// `{"code":"badRequest"}`(与 GET 类端点同一个错误面), 手机端一行都不用特判。
// 下面是这种包装器的**唯一**写法, 新增别的提取器(比如 `Path<T>`)照着抄一份即可。

/// 查询串提取器: 拒绝 → `ApiError::BadRequest`(而不是 axum 的 400 + 纯文本)。
pub struct Q<T>(pub T);

impl<T, S> FromRequestParts<S> for Q<T>
where
    T: DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        // 复用 axum 自己的解析(`Query::try_from_uri`), 只把**拒绝**换掉 ——
        // 参数语义(缺必填字段 / 类型不对 / 百分号编码)与直接用 `Query<T>` 完全一致。
        Query::<T>::try_from_uri(&parts.uri)
            .map(|Query(v)| Q(v))
            .map_err(|_| ApiError::BadRequest)
    }
}

/// JSON 请求体提取器: 拒绝 → `ApiError::BadRequest`(Task 6 的写端点用这个, 见上方长注释)。
pub struct J<T>(pub T);

impl<T, S> FromRequest<S> for J<T>
where
    T: DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        // 注意 `Json` 的拒绝里有"缺 `content-type: application/json`"这一条, 同样归
        // `badRequest` —— 一个内容类型不对的写请求就是坏请求, 不该混进 `internal`。
        <Json<T> as FromRequest<S>>::from_request(req, state)
            .await
            .map(|Json(v)| J(v))
            .map_err(|_| ApiError::BadRequest)
    }
}

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
    let got = bearer_token(headers);
    // `eq_constant_time` 对空串一律 false(fail-closed), 所以"配置里 token 变空"不会
    // 变成"关掉鉴权"—— 这条不变量在 auth.rs 里有自己的单测。
    if auth::eq_constant_time(got, &cfg.token) {
        Ok(())
    } else {
        Err(ApiError::Unauthorized)
    }
}

/// 从 `Authorization` 头里取出 Bearer 凭证; 取不到就返回 `""`(交由 `eq_constant_time` 拒绝)。
///
/// RFC 7235 §2.1 的 `credentials = auth-scheme [ 1*SP ( token68 / #auth-param ) ]` 决定了
/// **scheme 大小写不敏感**, 且 scheme 与凭证之间允许**一个或多个**空格。空白与大小写的
/// 处理在这里是显式的:
///   · 头值**两侧**的空格/Tab 先剥掉 —— 与 HTTP 解析器对 field-value 的处理一致(hyper 会剥;
///     单测里手工构造的请求不会, 所以这里自己再剥一次, 两条路径行为完全相同);
///   · scheme 按 ASCII 大小写不敏感比较(`bearer` / `BEARER` / `BeArEr` 都认);
///   · scheme 与凭证之间、以及凭证两侧的空格/Tab 都容忍;
///   · **凭证内部一个字符都不动**: 比较仍是整串常量时间比较。放宽空白不可能把错 token
///     变成对的(64 位十六进制 token 里本来就没有空白), 所以这纯粹是"别让格式差异冒充鉴权失败"。
///
/// 为什么值得改: 旧实现用 `strip_prefix("Bearer ")` 只认这一种精确写法, 于是
/// `bearer <token>` 或多一个空格都会被判成 `unauthorized` —— 而手机把 `unauthorized`
/// 映射成"**去重新配对**", 用户会为一个纯格式差异去扫码/轮换 token, 真正的原因只是大小写。
fn bearer_token(headers: &HeaderMap) -> &str {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(split_bearer)
        .unwrap_or("")
}

/// `bearer_token` 的纯函数部分: `Authorization` 头值 → 凭证。
fn split_bearer(value: &str) -> Option<&str> {
    let (scheme, rest) = value.trim_matches(is_ows).split_once(is_ows)?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    let token = rest.trim_matches(is_ows);
    // `""` 也走 None: 空凭证由 `eq_constant_time` 拒, 这里不必再区分。
    (!token.is_empty()).then_some(token)
}

/// OWS(RFC 9110: 只有空格与水平制表符)。
fn is_ows(c: char) -> bool {
    c == ' ' || c == '\t'
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
    Q(q): Q<PathQ>,
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
    Q(q): Q<PathQ>,
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

/// 图片响应。响应头**只有** `content-type: image/jpeg` —— 不发任何 cache 指令。
///
/// 失效方式由规格 **§9(缓存与离线)** 定: 手机端在 URL 上带 `?v=<mtime>`, 由手机端的图片库
/// (Coil)自己缓存; 服务端若发 `no-store` 会把那条缓存策略整个废掉(每个 URL 重下一次),
/// 发长期 `max-age` 又会在没有 `?v=` 时供应旧图。两种都是替客户端做它已经做过的决定。
/// (这条规则在 §9, 不在 §11 —— §11 是"风险与缓解"。)
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
    Q(q): Q<ThumbQ>,
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
    Q(q): Q<PreviewQ>,
) -> Result<Response, ApiError> {
    let p = resolve_within_shares(&st, &q.path)?;
    let bytes = media::preview_bytes(&p.to_string_lossy(), q.max, q.full).await?;
    Ok(jpeg(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::routing::post;
    use tower::ServiceExt;

    /// Task 6 的 `PUT /api/v1/decisions` 请求体的最小同形替身。
    #[derive(Deserialize)]
    struct DecisionWrite {
        path: String,
        #[serde(default)]
        rating: Option<u8>,
    }

    async fn echo_decision(J(d): J<DecisionWrite>) -> Json<serde_json::Value> {
        Json(serde_json::json!({ "path": d.path, "rating": d.rating }))
    }

    fn post_body(uri: &str, content_type: Option<&str>, body: &str) -> Request<Body> {
        let b = Request::builder().method("POST").uri(uri);
        let b = match content_type {
            Some(ct) => b.header(header::CONTENT_TYPE, ct),
            None => b,
        };
        b.body(Body::from(body.to_string())).unwrap()
    }

    async fn json_body(res: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
        serde_json::from_slice(&bytes).expect("错误/正常响应都必须是 JSON")
    }

    /// **Fix 1 的第二种形态: 请求体的拒绝也必须回 `{ code }`。**
    ///
    /// 这条测试的真正价值在 Task 6: `PUT /api/v1/decisions` 若照本能写 `Json<DecisionWrite>`,
    /// 那么坏 JSON / 缺必填字段 / 缺 `content-type` 三种情况下, axum 会回 400 + **纯文本**
    /// (`Failed to deserialize the JSON body…`), 手机端 `ApiError` 解析不出 `code`。
    /// 用 `J<T>` 则统一成 `{"code":"badRequest"}` —— 与 GET 类端点同一个错误面。
    ///
    /// 断言的写法是**整个对象相等**, 所以"顺手加个 detail 字段"也会在这里红。
    #[tokio::test]
    async fn json_body_rejections_become_bad_request_codes() {
        let app = Router::new().route("/decisions", post(echo_decision));

        // 正对照: 合法请求体必须照常通过 —— 没有这一条, "把所有请求都判成 badRequest"
        // 也能让下面的断言全绿。
        let res = app
            .clone()
            .oneshot(post_body(
                "/decisions",
                Some("application/json"),
                r#"{"path":"D:\\照片\\a.jpg","rating":4}"#,
            ))
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let v = json_body(res).await;
        assert_eq!(v["path"], "D:\\照片\\a.jpg");
        assert_eq!(v["rating"], 4);

        for (label, req) in [
            (
                "语法就是坏 JSON",
                post_body("/decisions", Some("application/json"), r#"{"path":"#),
            ),
            (
                "缺必填字段 path",
                post_body("/decisions", Some("application/json"), r#"{"rating":4}"#),
            ),
            (
                "没有 content-type: application/json",
                post_body("/decisions", None, r#"{"path":"x"}"#),
            ),
        ] {
            let res = app.clone().oneshot(req).await.unwrap();
            assert_eq!(res.status(), StatusCode::BAD_REQUEST, "{} 必须是 400", label);
            // 修复前这里是 400 + 纯文本 → serde_json 解析失败, 这条就是"会变红"的那一句。
            assert_eq!(
                json_body(res).await,
                serde_json::json!({ "code": "badRequest" }),
                "{} 的响应体必须恰好是 {{ code: badRequest }}(旧行为是 400 + 纯文本)",
                label
            );
        }
    }
}
