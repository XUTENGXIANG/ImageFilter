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
use crate::xmp::{DecisionWrite, WriteSummary};
use axum::body::Body;
use axum::extract::{FromRequest, FromRequestParts, Query, Request, State};
use axum::http::request::Parts;
use axum::http::{header, HeaderMap, StatusCode};
use axum::middleware::{self, map_response, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::de::{DeserializeOwned, IgnoredAny, MapAccess, Visitor};
use serde::Deserialize;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::sync::OnceLock;
use tokio::sync::{Mutex, MutexGuard};

// ── 提取器失败的收口: 一律压成闭集错误码 ─────────────────────────────────────
//
// 为什么必须有这一层: 规格 §5.3 要求服务端**统一**回 `{ code }`(且只有 `code` 一个字段)。
// 但 axum 的提取器(`Query`/`Json`/…)在**处理器跑起来之前**就会失败 —— 它回的是自己的
// 拒绝响应: 400 + **纯文本** `Failed to deserialize query string`。也就是说:
// **提取器一失败, 契约就漏了**。
//
// 说明(上一轮的注释在这里说得过满, 已按实测更正): **不许**把这条写成"手机因此看到未知
// 错误"。按 M1′ 计划, 手机端的 `ApiError.fromHttp(status, bodyCode)` 是
// `bodyCode ?: when (status)` —— 状态码是**兜底**, 而 400 恰好在那张兜底表的 `badRequest`
// 一行。所以今天这口纯文本 400 到了手机上**仍然是 `badRequest`**, 用户行为碰巧是对的。
// 修它不是因为手机今天坏了, 而是因为"碰巧对"不是契约:
//   · 正确的映射依赖客户端兜底表**恰好**覆盖了这个状态 —— 那是巧合, 客户端一收紧解析器
//     (按计划要从响应体读 `code`), 或换一个不在兜底表里的状态(405/404 就没有兜底项),
//     纯文本立刻变成"未知错误/内部错误";
//   · 服务端自己的响应形状应该是自洽的, 不该靠对端猜出来。
//
// 收口位置选在**提取器类型**上, 而不是给每个处理器写 `Option<Query<T>>` / 手工取 body:
//   · 处理器写 `Query<T>` 是本能, 要求每个新处理器"记得多写一步"就一定会漏;
//   · 换成 `Q<T>`/`J<T>` 只是换一个类型名, 新端点照抄既有处理器即可;
//   · 万一有人直接用了 `Query<T>`/`Json<T>`, 缺参时漏出的是纯文本 —— 下面那层兜底
//     (`normalize_residual_errors`)会把它改写成同样的 `{code}`, **所以对外的契约仍然成立**。
//     代价是实测记录在案的一件事: 兜底层让"处理器用的是哪个提取器"在集成层**不可观测**
//     (把 `scan` 改回 `Query<PathQ>`, 整套 `tests/server_contract.rs` 依然全绿)。
//     纪律的变异靶子因此放在 `mod tests::production_handlers_return_code_bodies_even_without_the_safety_net`:
//     它在**没有网**的同一张生产路由表上打请求, 回退成裸 `Query<T>` 会当场红。
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

// ── 残余非 `{ code }` 响应的兜底: 路由级响应规范化 ───────────────────────────
//
// `Q<T>`/`J<T>` 收口的是**提取器**拒绝; 还有一类请求根本走不到任何处理器, 响应由 axum 的
// 路由表自己生成: 方法不对(405)、路径没挂(404)。实测(带**正** token):
//   · `POST /api/v1/browse` → 405, **没有 `content-type`, body 是空的**;
//   · 未挂载的 `/api/v1/nope` → 404, 同样空体。
// 这两条都违反 §5.3 的"统一回 `{ code }`"。手机端尤其吃亏的是 405: M1′ 的
// `ApiError.fromHttp` 兜底表里**没有** 405, 于是"你请求的方法不对"被翻译成 `internal`
// ——用户看到的提示是"服务端内部错误, 请报 bug"。
//
// 所以这里在**整台路由器**外面再加一层 `map_response`: 只重写
// "status >= 400 **且** `content-type` 不是 `application/json`"的响应。这条边界保证下面
// 两类响应**逐字节原样通过**(原对象直接 move 出去, 一个字节都不重建):
//   · 所有成功响应: 200 的 JSON 业务体, 以及 `image/jpeg` 的缩略图/预览字节;
//   · 所有已经是 JSON 的错误: `ApiError`(`Q<T>`/`J<T>`/处理器返回的 400/401/403/422/…)——
//     它们的状态与语义更准(422 到底是 `notXmp` 还是 `unsupportedForm`, 只有处理器知道),
//     状态码推不出来。
// 所以这层是**安全网, 不是主机制**: 主机制仍是 `Q<T>`/`J<T>` + `ApiError`。它只负责
// "将来某个处理器照本能写了 `Json<T>`/`Query<T>`"与"请求压根没到处理器"这两类残余。
//
// 这层**也会**看见提取器拒绝(提取器在处理器门口跑, 它生成的响应同样要穿过这一层)。
// 实测确认过, 于是有两个后果, 都记在这里:
//   · **好事**: 契约多了一层保障 —— 即使将来有人写了裸 `Query<T>`/`Json<T>`, 对外形状
//     依然是 `{ code }`;
//   · **代价**: "处理器用的是哪个提取器"在**集成层变得不可观测**。实测: 只把 `scan` 改回
//     `Query<PathQ>`, `tests/server_contract.rs` 从 25 条到 29 条**全绿**。所以提取器纪律的
//     变异靶子放在 `mod tests::production_handlers_return_code_bodies_even_without_the_safety_net`
//     —— 那台路由器上**没有这层网**, 回退成裸 `Query<T>` 会当场红。
// 即便如此, 主机制仍然是 `Q<T>`/`J<T>`: 它们的状态与语义更精确(`J<T>` 缺
// `content-type` 给的是 400, axum 原生 `Json<T>` 给 415), 而这层只能按状态码猜一档。

/// HTTP 状态 → 闭集码。**只用 `ApiError` 已有的码, 这里不新造任何码。**
///
/// §5.3 点名的那几档按表走; 表里没列出的状态按大类兜底(其余 4xx → `badRequest`,
/// 其余 5xx → `internal`)。兜底是刻意的: 调用方的成立条件是"每个 >= 400 的非 JSON 响应
/// 都必须拿到一个码", 只要漏一档, 契约就又有一个洞。
fn code_for_status(status: StatusCode) -> &'static str {
    match status {
        StatusCode::BAD_REQUEST
        | StatusCode::METHOD_NOT_ALLOWED
        | StatusCode::UNSUPPORTED_MEDIA_TYPE
        | StatusCode::UNPROCESSABLE_ENTITY => ApiError::BadRequest.code(),
        StatusCode::UNAUTHORIZED => ApiError::Unauthorized.code(),
        StatusCode::FORBIDDEN => ApiError::ForbiddenPath.code(),
        StatusCode::NOT_FOUND => ApiError::NotFound.code(),
        StatusCode::PAYLOAD_TOO_LARGE => ApiError::TooLarge.code(),
        StatusCode::LOCKED => ApiError::Busy.code(),
        StatusCode::INSUFFICIENT_STORAGE => ApiError::DiskFull.code(),
        s if s.is_client_error() => ApiError::BadRequest.code(),
        s if s.is_server_error() => ApiError::Internal.code(),
        // 调用方保证了 status >= 400, 所以上面两条至少命中一条; 这里只是不让 match 有洞。
        _ => ApiError::Internal.code(),
    }
}

/// 描述"原来那个 body"的头 —— 换了 body 就必须丢掉, 否则新体上的头是假的。
fn is_body_bound_header(name: &header::HeaderName) -> bool {
    matches!(
        name.as_str(),
        "content-type" | "content-length" | "content-encoding" | "transfer-encoding"
    )
}

/// 兜底规范化: 只动"status >= 400 且不是 JSON"的响应, 其余原样返回。
async fn normalize_residual_errors(res: Response) -> Response {
    // 成功响应(含 `image/jpeg` 的二进制体)**在这里就返回**, 连 header 都不读第二遍 ——
    // "不许误伤成功响应"是靠这个早退分支结构上保证的, 不是靠后面的判断恰好为真。
    if res.status() < StatusCode::BAD_REQUEST {
        return res;
    }
    let is_json = res
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        // `application/json; charset=utf-8` 这类带参数的值同样算 JSON。
        .map(|v| {
            v.split(';')
                .next()
                .unwrap_or("")
                .trim()
                .eq_ignore_ascii_case("application/json")
        })
        .unwrap_or(false);
    if is_json {
        return res;
    }

    let status = res.status();
    let mut out = Json(serde_json::json!({ "code": code_for_status(status) })).into_response();
    // 保留原来的状态码(405 仍是 405 —— §5.3 只规定 body 的形状, 不改状态语义),
    // 顺带保留 `Allow` 这类描述**这次请求**的头; 只丢描述旧 body 的那几个。
    *out.status_mut() = status;
    for (name, value) in res.headers() {
        if !is_body_bound_header(name) {
            out.headers_mut().append(name.clone(), value.clone());
        }
    }
    out
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
    "/api/v1/decisions",
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
        .route("/api/v1/decisions", get(decisions_get).put(decisions_put))
}

/// 完整路由: **整台路由器**外面套一层鉴权中间件, 再套一层响应规范化。
///
/// 免鉴权的只有 `PUBLIC_PATHS` 里那几个(即 `ping`)。未挂载的路径也走这一层
/// (axum 的 `layer` 会罩住兜底路由), 所以不存在的路径在未鉴权时同样是 401 ——
/// 失败方向是"更严", 顺带也不向未配对的调用方泄露端点清单。带上正 token 时
/// 未挂载路径仍然是 404, 手机据此区分"服务没有这个功能"。
///
/// 两层各管一件事, 顺序是刻意的(`map_response` 在**最外层**, 所以它也能看见鉴权层自己
/// 生成的响应):
///   · `require_bearer`: 谁能进来(白名单在 `PUBLIC_PATHS`);
///   · `normalize_residual_errors`: 出去的东西必须长成 `{ code }`
///     (只碰 405/404 这类没走到处理器的残余, 见上方长注释)。
pub fn router(state: ServerState) -> Router {
    endpoint_routes()
        .layer(middleware::from_fn_with_state(state.clone(), require_bearer))
        .layer(map_response(normalize_residual_errors))
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

// ── 决策端点: 星级/色标(边车) ────────────────────────────────────────────────
//
// 三条边界, 都**不是**本层能自己规定的:
//   · **写边车的永远是桌面端**: 这里只把请求转交给 `crate::xmp::write_decisions`,
//     一个字节的 XMP 都不在本层拼 —— 于是"changed=false 不写"、"绝不静默覆盖"、
//     1MB 上限、`XmpError` 的闭集码、`WriteSummary` 的形状全部**原样继承**,
//     手机看到的码与桌面端自己的错误码是同一套。
//   · **批次的半成功态必须显式**: `write_decisions` 的返回是逐项的
//     (`written`/`skipped`/`failed` + `failures[].path`), 所以一个批次里"三张写成了、
//     一张的边车不是 XMP"会如实报出来, 而不是一句 ok/fail。**不许**在本层把它压成
//     一个布尔值, 也不许自己造一个 Summary 形状。
//   · **白名单先于一切**: 越界路径必须在**写出第一个边车之前**变成 403(见 `decisions_put`)。

/// 写决策时"同一路径排队"用的条带数。
///
/// 为什么是固定条带而不是"每条路径一个锁": 后者要为每条被写过的路径留一个锁对象、
/// 活到进程结束(内存随写过的路径数无界增长)。64 条固定条带最坏只是**多串行**
/// (两条不相干的路径撞进同一条带), 绝不会漏串行 —— 失败方向是"多等一会儿",
/// 而不是"丢更新"。
const WRITE_STRIPES: usize = 64;

/// 按路径排队的锁池(懒初始化, 进程内唯一)。
///
/// 为什么需要这一层(以及为什么它**不是**重复实现 `xmp.rs` 的写规则):
/// `xmp.rs` 里已有一个进程内闸门 `WRITING`(见 `write_one`), 它按**边车目标**加锁,
/// 而且**失败方向是拒绝**(第二个并发请求拿到 `XmpError::Busy`) —— 它挡住的是
/// "同一目标并发"。规格 §5.2 对**服务端**的要求是另一句话: "同一路径的写请求在服务端
/// **排队**"。两者的差别是可见的: 一个批次里只要有一项撞上别人的写, 拒绝式的做法会把
/// 整批(含没冲突的那些项)一起丢掉, 而契约要求逐项结果。
///
/// 已知的残留: 这一层按**照片路径**排队, 而两张不同的照片可能共用一个边车
/// (`a.jpg` 与 `a.arw` 都写 `a.xmp`) —— 那种情况仍然只由 `xmp.rs` 的**目标级**闸门兜住
/// (它会给出逐项的 `busy`)。两层互补, 谁都不是多余的。
static WRITE_QUEUES: OnceLock<Vec<Mutex<()>>> = OnceLock::new();

fn write_queues() -> &'static [Mutex<()>] {
    WRITE_QUEUES
        .get_or_init(|| (0..WRITE_STRIPES).map(|_| Mutex::new(())).collect())
        .as_slice()
}

/// 路径 → 条带。`DefaultHasher` 的种子是固定的, 所以同一路径在同一进程里恒同一条带。
fn stripe_of(path: &str) -> usize {
    let mut h = DefaultHasher::new();
    path.hash(&mut h);
    (h.finish() as usize) % WRITE_STRIPES
}

/// 按路径取锁, 返回的守卫**拖住锁直到被 Drop**。
///
/// 三个刻意的细节:
///   · **先排序再取**: 两批路径若各自有序, 就不可能出现"A 拿着 1 等 2、B 拿着 2 等 1";
///   · **去重**: `[a, a]` 这类批次若取两次同一条带就是自锁(自己等自己, 永久挂住);
///   · **RAII 而不是"写完手工释放"**: 手机端中途断开会让 axum **丢掉**处理器的 future,
///     手工写在末尾的 `release` 那一行就永远不会执行 —— 那条路径会被**永久**锁死
///     (后续请求全部排队等待)。守卫随 future 一起 Drop 才是取消安全的。
async fn lock_write_stripes(paths: &[String]) -> Vec<MutexGuard<'static, ()>> {
    let mut stripes: Vec<usize> = paths.iter().map(|p| stripe_of(p)).collect();
    stripes.sort_unstable();
    stripes.dedup();

    let mut guards = Vec::with_capacity(stripes.len());
    for i in stripes {
        guards.push(write_queues()[i].lock().await);
    }
    guards
}

/// `GET /api/v1/decisions?paths=a&paths=b` 的查询串。
///
/// **为什么手写 `Deserialize` 而不用 `#[derive]`**: axum 的 `Query` 底下是
/// `serde_urlencoded`, 它**不支持"同名键重复 → Vec"** —— 实测读它的实现
/// (`serde_urlencoded 0.7.1`: `Part::deserialize_seq` 被 `forward_to_deserialize_any!`
/// 转发到 `deserialize_any`, 即拿单个值走 `visit_str`), 于是 `#[derive]` 出来的
/// `paths: Vec<String>` 连 `?paths=a` 这一个值都读不进来("invalid type: string,
/// expected a sequence") → 规格 §5.1 写的 `?paths=a&paths=b` 会整体变成 400。
/// 手写的 `visit_map` 逐对累积同名键, 才是这个线上格式的真身。
///
/// 未知参数**忽略**而不是报错(前向兼容: 手机端将来加 `?since=…` 之类不该被 400 挡住),
/// 但值必须消费掉 —— 不消费就会让 `MapAccess` 的游标错位。
struct DecisionsQ {
    paths: Vec<String>,
}

impl<'de> Deserialize<'de> for DecisionsQ {
    fn deserialize<D>(d: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        struct V;

        impl<'de> Visitor<'de> for V {
            type Value = DecisionsQ;

            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("查询串 paths=…(可重复)")
            }

            fn visit_map<A>(self, mut map: A) -> Result<DecisionsQ, A::Error>
            where
                A: MapAccess<'de>,
            {
                let mut paths: Vec<String> = Vec::new();
                while let Some(key) = map.next_key::<String>()? {
                    if key == "paths" {
                        paths.push(map.next_value::<String>()?);
                    } else {
                        map.next_value::<IgnoredAny>()?;
                    }
                }
                Ok(DecisionsQ { paths })
            }
        }

        d.deserialize_map(V)
    }
}

/// 读边车(星级/色标)。与桌面端 `read_decisions` **同一个实现**, 本层只做白名单 + 序列化。
///
/// 不加写锁(与 `decisions_put` 不对称是刻意的): 边车的替换是"同目录临时文件 + rename",
/// 读到的要么是旧文本、要么是新文本, 不可能读到半截。而"读到的还是旧值"恰好就是
/// 参数未生效时的**真实状态**(§5.2 的第三条纪律: 未同步 ≠ 已同步), 不该被锁藏起来。
async fn decisions_get(
    State(st): State<ServerState>,
    Q(q): Q<DecisionsQ>,
) -> Result<Response, ApiError> {
    // 缺参数 = 请求本身不合法(与 `browse`/`scan`/`thumb`/`preview` 缺 `path` 同一档:
    // 它们都由 `Q<T>` 直接给 400 badRequest)。空数组不是"什么都没要求", 而是"你不知道
    // 自己要什么" —— 所以这里显式挡一道, 而不是安静地回一个空列表。
    if q.paths.is_empty() {
        return Err(ApiError::BadRequest);
    }

    let shares = shares_of(&st)?;
    let mut checked: Vec<String> = Vec::with_capacity(q.paths.len());
    for requested in &q.paths {
        // 与写端点同一条规矩: 只用 `ensure_within` 返回的**规范化路径**去调既有函数。
        // 任何一条越界 → 整条请求 403: 读也不该"读一半"。
        checked.push(
            paths::ensure_within(&shares, std::path::Path::new(requested))?
                .to_string_lossy()
                .to_string(),
        );
    }

    // `read_decisions` 唯一会失败的地方是 `spawn_blocking` 的 JoinError(任务 panic)。
    // 单项"读不出来"(权限/超限/不是 XMP/没有边车)在 `xmp.rs` 里本来就降级成
    // `rating: null`(前端保留本地值), 不是错误 —— 这条语义同样原样继承。
    let out = crate::xmp::read_decisions(checked)
        .await
        .map_err(|_| ApiError::Internal)?;
    Ok(Json(out).into_response())
}

/// 写边车。请求体是 `DecisionWrite` 的数组(与桌面端同形, 见规格 §5.2)。
async fn decisions_put(
    State(st): State<ServerState>,
    J(items): J<Vec<DecisionWrite>>,
) -> Result<Response, ApiError> {
    let shares = shares_of(&st)?;

    // ① **先全量校验、再动手**。绝不允许"写了一半才发现有越界路径": 那种半成功状态只剩
    //    一句 403 可以报, 手机无从知道前几张到底写成了没有(纪律②被静默吞掉)。所以越界是
    //    **整批拒绝, 且一个字节都不写**。
    let mut checked: Vec<DecisionWrite> = Vec::with_capacity(items.len());
    for item in items {
        let canon = paths::ensure_within(&shares, std::path::Path::new(&item.path))?;
        checked.push(DecisionWrite {
            path: canon.to_string_lossy().to_string(),
            ..item
        });
    }

    // ② 按路径排队(见 `WRITE_QUEUES`), 守卫活到本函数返回为止。
    let paths: Vec<String> = checked.iter().map(|it| it.path.clone()).collect();
    let _queued = lock_write_stripes(&paths).await;

    // ③ 一个字节的 XMP 都不在这里拼: 整批交给既有的 `write_decisions`。
    //    `XmpError` → 逐项 `failures[].code` 的映射也由它自己的 `code()` 给出。
    let summary: WriteSummary = crate::xmp::write_decisions(checked)
        .await
        .map_err(|_| ApiError::Internal)?;
    Ok(Json(summary).into_response())
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

    /// 兜底规范化器的**状态 → 码**表逐档钉住。
    ///
    /// 表本身只从 `ApiError::code()` 取值(所以"不新造码"是结构上成立的), 这条测试管的是
    /// 另一件事: **哪一档归哪个码**。写错一档的后果很具体 —— 例如把 405 归成 `internal`,
    /// 手机就把"方法不对"显示成"服务端内部错误, 请报 bug"(那正是加这层的原因)。
    #[test]
    fn residual_status_codes_match_the_documented_table() {
        for (status, want) in [
            (StatusCode::BAD_REQUEST, "badRequest"),
            (StatusCode::METHOD_NOT_ALLOWED, "badRequest"),
            (StatusCode::UNSUPPORTED_MEDIA_TYPE, "badRequest"),
            (StatusCode::UNPROCESSABLE_ENTITY, "badRequest"),
            (StatusCode::UNAUTHORIZED, "unauthorized"),
            (StatusCode::FORBIDDEN, "forbiddenPath"),
            (StatusCode::NOT_FOUND, "notFound"),
            (StatusCode::PAYLOAD_TOO_LARGE, "tooLarge"),
            (StatusCode::LOCKED, "busy"),
            (StatusCode::INSUFFICIENT_STORAGE, "diskFull"),
            // 表里没列出的按大类兜底: 4xx 是"请求的问题", 5xx 是"服务端的问题"
            (StatusCode::CONFLICT, "badRequest"),
            (StatusCode::TOO_MANY_REQUESTS, "badRequest"),
            (StatusCode::INTERNAL_SERVER_ERROR, "internal"),
            (StatusCode::BAD_GATEWAY, "internal"),
        ] {
            assert_eq!(code_for_status(status), want, "{} 这一档归错了码", status);
        }
    }

    /// `is_body_bound_header` 只丢"描述旧 body"的头 —— `Allow` 这类描述**这次请求**的头
    /// 必须留下, 否则 405 的响应会比 axum 原生给的还少一个语义头。
    #[test]
    fn only_body_bound_headers_are_dropped() {
        for keep in [header::ALLOW, header::RETRY_AFTER, header::WWW_AUTHENTICATE] {
            assert!(!is_body_bound_header(&keep), "{} 不该被丢掉", keep);
        }
        for drop in [
            header::CONTENT_TYPE,
            header::CONTENT_LENGTH,
            header::CONTENT_ENCODING,
        ] {
            assert!(is_body_bound_header(&drop), "{} 必须被丢掉", drop);
        }
    }

    /// **"没有安全网"的那台路由器**: 生产路由表 + 生产鉴权层, 但**不加** `normalize_residual_errors`。
    ///
    /// 它只是 `router()` 接线的前两行(见 `router` 的注释), **不是第二个入口**: 私有、只被
    /// 下面那条单测使用, 也不会被任何生产路径调用。存在的唯一理由是让"处理器自己有没有回
    /// `{ code }`"这件事重新变得**可观测** —— 兜底层会把裸 `Query<T>` 的 400 纯文本也改写成
    /// 形状逐字节相同的 `{code:badRequest}`, 在 `routes::router` 上根本分不出来(实测见下)。
    fn routes_without_the_safety_net(state: ServerState) -> Router {
        endpoint_routes()
            .layer(middleware::from_fn_with_state(state.clone(), require_bearer))
            .with_state(state)
    }

    /// **提取器纪律的变异靶子: 生产处理器必须自己回 `{ code }`, 不许靠兜底层。**
    ///
    /// 为什么要在"无网"的路由器上测: 实测过 —— **只**把 `scan` 改回 `Query<PathQ>`, 整套
    /// 集成契约测试(含 `extractor_rejections_obey_the_code_contract` 里逐条端点的用例)
    /// **依然全绿**, 因为兜底层把它的 400 + 纯文本改写成了与 `Q<T>` 逐字节相同的
    /// `{code:badRequest}`。也就是说: 网让"某个处理器回退成裸提取器"在**对外行为**上无害,
    /// 但也让它在**集成层不可观测**。
    ///
    /// 所以把纪律钉在这里 —— 同一张生产路由表, 少掉的只有那层网。谁把 `browse`/`scan`/
    /// `thumb`/`preview` 中任何一个改回裸 `Query<T>`, 这条单测当场红(缺 `path` 的 400 会变成
    /// 纯文本, `json_body` 在纯文本上直接 panic)。覆盖走 `PROTECTED_PATHS` 全表, 所以将来
    /// 新增一条吃 `path` 的端点会自动被覆盖。
    #[tokio::test]
    async fn production_handlers_return_code_bodies_even_without_the_safety_net() {
        let token = "t".repeat(64);
        let app = routes_without_the_safety_net(
            crate::server::ServerConfig {
                enabled: true,
                port: 0,
                token: token.clone(),
                shares: Vec::new(),
            }
            .into_state(),
        );

        let mut path_endpoints = 0usize;
        for base in PROTECTED_PATHS {
            let req = Request::builder()
                .uri(*base)
                .header(header::AUTHORIZATION, format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap();
            let res = app.clone().oneshot(req).await.unwrap();

            if res.status() == StatusCode::BAD_REQUEST {
                // 缺**必填参数**的端点(`browse`/`scan`/`thumb`/`preview` 缺 `path`,
                // Task 6 的 `decisions` 缺 `paths`)。**这台路由器上没有兜底层**, 所以能给出
                // JSON 的只有处理器自己用的 `Q<T>`。
                path_endpoints += 1;
                assert_eq!(
                    res.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()),
                    Some("application/json"),
                    "{} 的提取器拒绝必须是 JSON —— 没有兜底层时只有 `Q<T>` 做得到",
                    base
                );
                assert_eq!(
                    json_body(res).await,
                    serde_json::json!({ "code": "badRequest" }),
                    "{} 必须自己回 {{ code: badRequest }}",
                    base
                );
            } else {
                assert_eq!(
                    res.status(),
                    StatusCode::OK,
                    "{} 缺必填参数只许 400(吃参数的端点)或 200(不吃参数的端点)",
                    base
                );
            }
        }
        assert!(
            path_endpoints >= 4,
            "至少要有 browse/scan/thumb/preview 四条吃 path 的端点, 实际 {} 条 —— 这条测试的覆盖是空的",
            path_endpoints
        );
    }

    // ── Task 6: 决策端点的**进程内**保证 ─────────────────────────────────────
    //
    // 对外契约(白名单/401/400/往返/逐项失败)在 `tests/server_contract.rs` 里打真 HTTP 栈验;
    // 这里放两件只有在本模块内部才验得了的事: 写端点的**请求体提取器纪律**(靶子必须在一台
    // 没有兜底层的路由器上, 集成层观测不到), 以及**按路径排队**(它要拿到私有的条带锁)。

    /// 一个**真建出来**的共享根 + 里面一张**真存在**的照片。
    ///
    /// 两句断言不是装饰: `ensure_within` 只能校验已存在的路径(不存在 → 直接 `NotFound`,
    /// 于是"越界被拒"会因为错误的原因成立), 而 `write_one` 的 `validate_photo_path` 还要求
    /// `is_file()` —— 夹具缺了这一张, 写请求会安静地变成一条逐项 `unknown` 失败。
    fn decision_root(tag: &str) -> std::path::PathBuf {
        let root = std::env::temp_dir().join(format!("ifdec_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let photo = root.join("a.jpg");
        std::fs::write(&photo, b"jpegbytes").unwrap();

        assert!(photo.is_file(), "夹具前提: 决策端点要的照片必须真的存在");
        assert!(
            root.canonicalize().is_ok(),
            "夹具前提: 共享根必须可 canonicalize, 否则 ensure_within 会跳过它"
        );
        root
    }

    /// 打真 `router()`(生产入口)的 app + token + 共享根。
    fn decision_app(tag: &str) -> (Router, String, std::path::PathBuf) {
        let root = decision_root(tag);
        let token = "t".repeat(64);
        let cfg = crate::server::ServerConfig {
            enabled: true,
            port: 0,
            token: token.clone(),
            shares: vec![root.clone()],
        };
        (router(cfg.into_state()), token, root)
    }

    /// `PUT` 的请求构造器(与既有 `post_body` 同形, 只多一个 Authorization)。
    fn put_body(
        uri: &str,
        token: Option<&str>,
        content_type: Option<&str>,
        body: &str,
    ) -> Request<Body> {
        let b = Request::builder().method("PUT").uri(uri);
        let b = match token {
            Some(t) => b.header(header::AUTHORIZATION, format!("Bearer {}", t)),
            None => b,
        };
        let b = match content_type {
            Some(ct) => b.header(header::CONTENT_TYPE, ct),
            None => b,
        };
        b.body(Body::from(body.to_string())).unwrap()
    }

    /// 一个"把 a.jpg 打成 n 星"的合法请求体。
    fn rating_body(photo: &std::path::Path, rating: u8) -> String {
        serde_json::to_string(&serde_json::json!([{
            "path": photo.to_string_lossy(),
            "rating": rating,
            "label": null,
            "clearLabel": false,
        }]))
        .unwrap()
    }

    /// **写端点的提取器靶子: `PUT /api/v1/decisions` 的请求体拒绝也必须是 `{ code }`。**
    ///
    /// 照本能写 axum 的 `Json<Vec<DecisionWrite>>` 会在两条路上漏出契约:
    ///   · 坏 JSON → 400 + **纯文本**;
    ///   · 缺 `content-type: application/json` → **415** + 纯文本 —— 而 415 在手机端
    ///     M1′ 的 `ApiError.fromHttp` 兜底表里**没有**对应项, 于是"内容类型不对"会被显示成
    ///     "服务端内部错误, 请报 bug"(与那个 405 的坑同源)。
    /// `J<T>` 把两种拒绝都压成 400 + `{"code":"badRequest"}` —— 手机端一行都不用特判。
    ///
    /// 为什么钉在**没有兜底层**的同一张生产路由表上: 生产 `router()` 最外层的
    /// `normalize_residual_errors` 会按状态码把上面两种纯文本也改写成形状相同的
    /// `{code:badRequest}`, 所以"处理器到底用了哪个提取器"在集成层**观测不到**。
    #[tokio::test]
    async fn decisions_body_rejections_return_code_bodies_without_the_safety_net() {
        let root = decision_root("bodyreject");
        let token = "t".repeat(64);
        let app = routes_without_the_safety_net(
            crate::server::ServerConfig {
                enabled: true,
                port: 0,
                token: token.clone(),
                shares: vec![root.clone()],
            }
            .into_state(),
        );
        let photo = root.join("a.jpg");

        // 正对照: 合法请求体必须真的走到处理器并写盘。没有这一条, "把所有请求都判成
        // badRequest" 也能让下面的断言全绿。
        let res = app
            .clone()
            .oneshot(put_body(
                "/api/v1/decisions",
                Some(&token),
                Some("application/json"),
                &rating_body(&photo, 4),
            ))
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        assert_eq!(json_body(res).await["written"], 1, "合法请求体必须真的写出边车");
        assert!(root.join("a.xmp").is_file(), "边车必须落在共享根里");

        for (label, ct, raw) in [
            ("语法坏 JSON", Some("application/json"), r#"[{"path":"#),
            ("项里缺必填字段 path", Some("application/json"), r#"[{"rating":4}]"#),
            ("请求体不是数组", Some("application/json"), r#"{"path":"x"}"#),
            ("没有 content-type: application/json", None, r#"[{"path":"x"}]"#),
        ] {
            let res = app
                .clone()
                .oneshot(put_body("/api/v1/decisions", Some(&token), ct, raw))
                .await
                .unwrap();
            assert_eq!(
                res.status(),
                StatusCode::BAD_REQUEST,
                "{} 必须是 400(裸 `Json<T>` 在缺 content-type 时给的是 415)",
                label
            );
            assert_eq!(
                res.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()),
                Some("application/json"),
                "{} 的拒绝必须是 JSON —— 没有兜底层时只有 `J<T>` 做得到",
                label
            );
            assert_eq!(
                json_body(res).await,
                serde_json::json!({ "code": "badRequest" }),
                "{} 的响应体必须恰好是 {{ code: badRequest }}",
                label
            );
        }

        std::fs::remove_dir_all(&root).ok();
    }

    /// **同一路径的第二个写必须排队等待, 而不是与第一个交错(丢更新的窗口)。**
    ///
    /// 为什么必须从 `router()` 打真请求: "排队"是**端点**的性质, 不是锁函数的性质 ——
    /// 删掉 `decisions_put` 里那行 `lock_write_stripes(...)`, 只测锁函数的单测照样全绿。
    ///
    /// 判据是"**不完成**"而不是"不写": 锁被占着时, 这个请求无论在什么机器上都不可能完成,
    /// 所以 `assert!(timeout.is_err())` 不会因为机器慢而假红; 反过来, 未排队时它会在真实
    /// 写盘耗时内完成 —— 那个耗时是**先量出来的**(正对照), 等待窗口取它的 10 倍 + 100ms,
    /// 于是变异检测不依赖任何"猜一个足够大的数字"。
    ///
    /// 顺带覆盖取消安全: 中间那次请求是被超时**丢掉**的(真实场景就是手机断开), 之后
    /// 同一个请求必须还能正常完成 —— 那正好说明守卫是随 future 一起 Drop 的。
    ///
    /// 三处都套超时(取锁、被挡住的写、解锁后的写), 因为"守卫被留在锁池里"这种缺陷的
    /// 症状是**永远等下去**: 一个挂住 CI 直到全局超时的测试不是好测试 —— 它应该是一次
    /// 明确的失败。真实场景里等价于"手机断开 → 那条路径再也写不进去"。
    #[tokio::test]
    async fn decisions_writes_to_the_same_path_queue_instead_of_interleaving() {
        let (app, token, root) = decision_app("queue");
        let photo = root.join("a.jpg");
        let body = rating_body(&photo, 4);

        // 正对照(同时量一次真实写盘耗时): 无人持锁时, 这个请求必须真的写成。
        let t0 = std::time::Instant::now();
        let res = app
            .clone()
            .oneshot(put_body(
                "/api/v1/decisions",
                Some(&token),
                Some("application/json"),
                &body,
            ))
            .await
            .unwrap();
        let wrote = t0.elapsed();
        let wait = wrote * 10 + std::time::Duration::from_millis(100);
        assert_eq!(res.status(), StatusCode::OK);
        assert_eq!(json_body(res).await["written"], 1, "正对照必须真的写成功");

        // 把这张照片的条带拿在手里。处理器用的是 `ensure_within` 返回的**规范化路径**
        // (也是它交给 `write_decisions` 的那个值), 所以这里也先 canonicalize —— 两边算出来
        // 的必须是同一条带, 否则下面那句会变成"没锁也过"的假绿。
        // 这一次取锁本身就是一条断言: 刚刚那次请求**已经结束**, 条带必须是空的
        // (守卫随 future 结束而 Drop, 而不是被留在锁池里)。
        let canon = photo.canonicalize().unwrap().to_string_lossy().to_string();
        let held = tokio::time::timeout(wait, lock_write_stripes(&[canon]))
            .await
            .expect("上一次请求结束后条带必须已经空出来 —— 取不到就说明守卫没有被释放");

        let blocked = tokio::time::timeout(
            wait,
            app.clone().oneshot(put_body(
                "/api/v1/decisions",
                Some(&token),
                Some("application/json"),
                &body,
            )),
        )
        .await;
        assert!(
            blocked.is_err(),
            "同一路径的第二个写必须排在第一个后面 —— 它在锁被占着时就完成了(等待窗口 {:?}), \
             那说明处理器根本没排队, 两个读-改-写会交错",
            wait
        );
        drop(held);

        // 锁一放开, 同一个请求必须照常完成。这里也套超时: 若某次请求的守卫被**留在**
        // 锁池里(手工 `release` 的写法在被取消时就是这样), 这条路径会被永久锁死 ——
        // 那应该是一次**失败**而不是一次挂住整个测试进程。
        let done = tokio::time::timeout(
            wait,
            app.oneshot(put_body(
                "/api/v1/decisions",
                Some(&token),
                Some("application/json"),
                &body,
            )),
        )
        .await
        .expect("锁必须在持有它的那次请求结束后被释放(否则这条路径再也写不进去)");
        let res = done.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        // 第二次是同一个星级 → `apply_decision` 报 changed=false → skipped(不白摸一次卡)。
        assert_eq!(
            json_body(res).await["skipped"],
            1,
            "排队结束后必须还能正常写(且幂等的那次报 skipped)"
        );

        std::fs::remove_dir_all(&root).ok();
    }
}
