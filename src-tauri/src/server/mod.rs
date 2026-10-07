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
use serde::Serialize;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex, RwLock};

/// `decisions-changed` 的发送端。**类型擦除的一层**: 函数签名里**没有** `tauri::AppHandle`。
///
/// 为什么值得多这一层(这是 Task 8 实测出来的, 不是洁癖):
/// `#[cfg(test)]` 的单测与 `tests/` 的集成测试都会把 `server` 编进各自的**测试 exe**。
/// 测试 exe **没有** Tauri 给正式程序嵌的 `Microsoft.Windows.Common-Controls` v6 清单,
/// 而 `tauri::AppHandle` 一旦真的出现在测试可达的代码里(哪怕只是被 `drop`), 链接器就会把
/// Tauri 的窗口/WebView 那一层拉进来 —— 那一层 import 了 `comctl32.dll` 的
/// `TaskDialogIndirect` / `SetWindowSubclass` 这些**只有 v6 才导出**的函数。没有 v6 清单 →
/// 加载期直接 `STATUS_ENTRYPOINT_NOT_FOUND (0xC0000139)`, **连 `cargo test` 都跑不起来**
/// (实测: 保留 `Option<AppHandle>` 字段 → 两个测试 exe 都起不来; 去掉之后正常)。
/// 类型擦除之后, 只有 `lib.rs` 里那个只在 `run()` 里被引用的闭包知道 Tauri 的存在。
pub type Notify = Arc<dyn Fn(&[String]) + Send + Sync>;

/// 服务运行期状态。
///
/// 为什么是 `Arc<RwLock<_>>` 而不是直接存一份 `ServerConfig`: 用户在桌面端改共享根、
/// 轮换 token 之后, 服务**不该**需要重启才生效 —— 端点每次请求现读一次配置即可,
/// 而 `Arc` 让路由与后台任务能共享同一个状态(规格 §5.2 的"轮换后所有手机需重新配对")。
#[derive(Clone)]
pub struct ServerState {
    pub config: Arc<RwLock<config::ServerConfig>>,
    /// 测试里是 `None`; 正式运行时由 `into_state_with_notify` 带上, 用于向桌面端 UI 发事件
    /// (`decisions-changed`, 见 `routes::decisions_put`)。
    ///
    /// 为什么是 `Option`: 集成/单测只需要路由与配置, 不该被迫造一个 Tauri app;
    /// 而且"没有通知端就跳过发事件"是一条**真实可达**的分支, 必须被测试覆盖到。
    pub notify: Option<Notify>,
}

impl config::ServerConfig {
    /// 把一份配置变成服务状态。**集成测试与单测共用这一条入口**(不带通知端, 事件分支跳过)。
    pub fn into_state(self) -> ServerState {
        ServerState {
            config: Arc::new(RwLock::new(self)),
            notify: None,
        }
    }

    /// 正式运行用: 带上通知端, 才能在手机改动后通知桌面端 UI 刷新(`lib.rs` 里构造)。
    pub fn into_state_with_notify(self, notify: Notify) -> ServerState {
        ServerState {
            config: Arc::new(RwLock::new(self)),
            notify: Some(notify),
        }
    }
}

/// 桌面端状态查询用的快照(`get_server_status` 的返回形状)。
///
/// `token` 是**故意**给前端的: 手机配对要靠它(二维码/手输)。它只在用户自己打开的
/// 设置面板里出现, 不落日志。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    /// 监听**真的**在跑(点开关失败、端口全被占时它是 false, 而 `enabled` 可能仍是 true)。
    pub running: bool,
    /// 在跑的端口; 没在跑时是配置里那个(界面照常显示"将要用的端口")。
    pub port: u16,
    pub url: String,
    pub token: String,
    pub shares: Vec<String>,
    /// 用户的选择(落盘的那一份), 与 `running` 分开 —— 两者不一致本身就是有用的信息。
    pub enabled: bool,
    /// 上一次启动失败的原因(**可见的理由**)。启动路径不许把桌面端搞崩, 所以失败只能
    /// 留在这里 + 一行 stderr; 界面据此给用户一句人话。启动成功后清空。
    pub last_error: Option<String>,
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

// ── Task 8 · 服务生命周期 ────────────────────────────────────────────────────
//
// 这一节的每一条都服从同一条用户约束: **别把桌面端弄坏**。具体到代码上就是三件事 ——
//   ① 默认不启动(`boot` 见到 enabled=false 立刻返回, 什么都不做);
//   ② 任何失败都只是 `Err` + 一个可见的理由, 绝不 panic/阻塞启动
//      (随机数不可用 → `auth::try_new_token` 给 Err; 端口全被占 → `start` 给 Err);
//   ③ 空/坏 token **fail-closed**: 服务宁可不起, 也不许出现"空 token = 关掉鉴权"。

/// 在跑的端口。0 = 没在跑。
///
/// 为什么用静态量而不是把句柄塞进 `ServerState`: `ServerStatus` 要能在**没有 app 状态**
/// 的地方(单测、日志)回答"到底有没有在监听", 而这是**进程级**事实。
static RUNNING_PORT: AtomicU16 = AtomicU16::new(0);

/// 服务的"代数"。每次 `start` 递增一次。
///
/// 只为一件事: 上一台服务的收尾任务**不许**把下一台的端口抹成 0(否则状态里会显示
/// "没在跑"而实际上还在监听)。收尾前对一下代数即可 —— 见 `start` 里的 spawn。
static SERVE_GEN: AtomicU64 = AtomicU64::new(0);

/// 当前这一台服务的停止开关。
///
/// 每次 `start` 造**新的一对** `watch` 通道装进这里: 复用同一个 sender 会让"上一次 stop"
/// 的许可落到下一台上(新服务一启动就被关掉)。`stop()` 只关最近起的这一台。
static SHUTDOWN: StdMutex<Option<tokio::sync::watch::Sender<bool>>> = StdMutex::new(None);

/// 起服务这条路的串行闸门。
///
/// 并发的两次"开启"(开机拉起与用户点开关撞在一起、或连点两下)会**各绑一个端口**,
/// 后绑的那台把 `RUNNING_PORT` 覆盖掉 —— 先绑的那台从此没人知道它在监听, 也停不掉。
/// 用 `tokio::sync::Mutex` 是因为要在 `.await`(建监听、拿锁)之间持锁, `std::sync::Mutex`
/// 的守卫不是 `Send`, 会让 Tauri 命令的 future 编译不过。
static START_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 上一次启动失败的原因。只为一件事: 让界面能给出**可见的理由**(见 `ServerStatus`)。
static LAST_ERROR: StdMutex<Option<String>> = StdMutex::new(None);

fn record_start_error(msg: String) {
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = Some(msg);
    }
}

fn clear_start_error() {
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = None;
    }
}

fn last_start_error() -> Option<String> {
    LAST_ERROR.lock().ok().and_then(|s| s.clone())
}

/// 起服务。**用 Tauri 的运行时 spawn**(Task 1 已验证可行), 不另建 runtime。
///
/// 失败一律是 `Err`, 绝不 panic:
///   · token 不可用 → `Err`(fail-closed, 见下);
///   · 20 个端口全绑不上 → `Err`(用户机器上 8765 被别的软件占了很常见)。
///
/// 已经在跑时**原样返回当前端口**(幂等): 再起一台只会多一个"状态里看不见、也停不掉"的
/// 监听者。
///
/// ⚠️ **调用方的运行时**: 监听器在**调用方**的运行时上注册(tokio 的 I/O 资源归属), 而 serve
/// 任务 spawn 在 **Tauri 的运行时**上。生产路径(`setup` 里 spawn 的 `boot`、Tauri 命令
/// `set_server_enabled` 里的 `start`)**本来就跑在 Tauri 的运行时上**, 所以两者是同一个。
/// 从别的运行时(例如 `#[tokio::test]` 的 current_thread 运行时)直接 await 它, 监听器会
/// 注册错地方 —— accept 永远等不到就绪事件, 表现为"起来了但所有请求超时"(实测 10060)。
/// `mod tests` 里的 `start_on_tauri_runtime` 就是按生产调用方式包的一层。
pub async fn start(state: ServerState) -> Result<u16, String> {
    let _serialized = START_LOCK.lock().await;

    let cfg = state.config.read().map_err(|e| e.to_string())?.clone();

    // **fail-closed 的第一道**(第二道在 `routes::check_auth`)。
    //
    // `ServerConfig::default()` 不再生成 token(Task 8), 所以"配置里 token 为空"是一个
    // **真实可达**的状态。万一这样也把服务起起来, 鉴权层面对的就是一枚空 token ——
    // 而 `eq_constant_time("", "")` 的历史语义是 true。宁可返回 `Err` 让服务不启动。
    //
    // 位置在幂等检查**之前**是刻意的: token 不合法时, 无论有没有在跑的实例, 这一条都要
    // 先说话 —— 否则"已经在跑"会把一个本该报错的状态悄悄吞掉(实测: 那会让这条不变量
    // 在测试里变成看运气的)。
    if !config::token_is_valid(&cfg.token) {
        let msg = "配对 token 不可用, 服务未启动(请在设置里重新开启一次, 会重新生成)".to_string();
        record_start_error(msg.clone());
        return Err(msg);
    }

    // 已经在跑就别再起一台: 否则第二次调用会绑到下一个端口, 而 RUNNING_PORT 被覆盖,
    // 旧的那台从此没人知道它在监听(状态里看不见, 也停不掉)。
    if let Some(p) = running_port() {
        return Ok(p);
    }

    // 端口被占用就 +1 重试, 最多 20 次 —— 用户机器上 8765 被别的软件占了很常见
    let mut last_err = String::new();
    for offset in 0..20u16 {
        let port = cfg.port.saturating_add(offset);
        match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => {
                let gen = SERVE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
                let (tx, mut rx) = tokio::sync::watch::channel(false);
                // 装停止开关。**这里不 await**, 所以用 `std::sync::Mutex` 是安全的
                // (守卫在同一段同步代码里就释放了)。
                match SHUTDOWN.lock() {
                    Ok(mut slot) => *slot = Some(tx),
                    Err(_) => return Err("内部状态不可用(停止开关), 服务未启动".into()),
                }
                RUNNING_PORT.store(port, Ordering::SeqCst);
                clear_start_error();
                // **整张路由表只有一个入口** `routes::router`, 鉴权层罩住全部(见模块头)。
                let app = routes::router(state.clone());
                tauri::async_runtime::spawn(async move {
                    let served = axum::serve(listener, app)
                        .with_graceful_shutdown(async move {
                            // `watch` 是**有状态**的: 即使 stop 发生在服务还没开始等之前,
                            // `changed()` 也会立刻返回(不像 `Notify` 会漏掉那一次喊话)。
                            let _ = rx.changed().await;
                        })
                        .await;
                    // 只有"我这一代"才有资格清端口: 否则上一台的收尾会把新一台抹成 0。
                    if SERVE_GEN.load(Ordering::SeqCst) == gen {
                        RUNNING_PORT.store(0, Ordering::SeqCst);
                    }
                    if let Err(e) = served {
                        eprintln!("[server] 服务结束: {}", e);
                    }
                });
                return Ok(port);
            }
            Err(e) => last_err = e.to_string(),
        }
    }
    let msg = format!(
        "从 {} 起的 20 个端口都绑定失败(可能被其它软件占用): {}",
        cfg.port, last_err
    );
    record_start_error(msg.clone());
    Err(msg)
}

/// 停止当前这一台服务。**幂等**: 没在跑时什么都不做。
///
/// 为什么这一条必须在 M0′ 就有: `set_server_enabled(false)` 的语义是"用户关掉了它"。
/// 关掉之后还继续监听, 就是在对用户撒谎 —— 而且是安全上最难察觉的那种。
///
/// 端口立刻置 0(不等 serve 未来收尾): 状态查询马上能看到"没在跑"; 同时把代数推进一格,
/// 免得即将收尾的那台任务把**下一台**的端口清掉。
pub fn stop() {
    if let Ok(slot) = SHUTDOWN.lock() {
        if let Some(tx) = slot.as_ref() {
            let _ = tx.send(true);
        }
    }
    SERVE_GEN.fetch_add(1, Ordering::SeqCst);
    RUNNING_PORT.store(0, Ordering::SeqCst);
}

/// 在跑的端口(`None` = 没在跑)。
pub fn running_port() -> Option<u16> {
    match RUNNING_PORT.load(Ordering::SeqCst) {
        0 => None,
        p => Some(p),
    }
}

/// 用本机 IPv4 拼一个手机可填的地址。检测不到就回落到 localhost。
pub fn primary_url(port: u16) -> String {
    format!("http://{}:{}", local_ipv4().unwrap_or_else(|| "127.0.0.1".into()), port)
}

/// 取本机第一个非回环 IPv4。**不引第三方 crate** —— 用 UDP connect 技巧拿默认出口地址。
///
/// 全程 `?`/`ok`, 没有一处 unwrap: 取不到只是回落到 127.0.0.1(手机连不上时用户
/// 至少能看到一个能自测的地址), 不是错误。
fn local_ipv4() -> Option<String> {
    use std::net::UdpSocket;
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("223.5.5.5:80").ok()?; // 不真的发包, 只为让系统选路
    Some(s.local_addr().ok()?.ip().to_string())
}

/// 桌面端的 app 数据目录(`server-config.json` 就落在这儿)。
pub fn app_data_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    use tauri::Manager as _;
    app.path().app_data_dir().map_err(|e| e.to_string())
}

/// 启动时要不要拉起服务。**"默认关闭"的唯一判据**, 做成纯函数是为了能被单测钉住。
///
/// 读锁中毒(RwLock poisoned)时返回 false —— 读不出配置就当"没开启"。这是 fail-closed:
/// 服务宁可不启动, 也不能在"配置读不出来"时默默监听。
pub fn wants_start(st: &ServerState) -> bool {
    st.config.read().map(|c| c.enabled).unwrap_or(false)
}

/// 把内存里的配置落盘。
pub fn save_config(st: &ServerState, app_data_dir: &Path) -> Result<(), String> {
    let cfg = st.config.read().map_err(|e| e.to_string())?.clone();
    cfg.save(app_data_dir)
}

/// 落盘(取 app 数据目录的那一版, 给 Tauri 命令用)。
pub fn persist(app: &tauri::AppHandle, st: &ServerState) -> Result<(), String> {
    save_config(st, &app_data_dir(app)?)
}

/// 共享根的字符串形式(前端要的就是字符串)。
pub fn share_strings(st: &ServerState) -> Result<Vec<String>, String> {
    Ok(st
        .config
        .read()
        .map_err(|e| e.to_string())?
        .shares
        .iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect())
}

/// 状态快照。**任何失败都只是 `Err`**(锁中毒), 不会 panic。
///
/// 与 `status_of` 的差别只有一个 app 句柄: 状态本身今天是纯的(端口是进程级静态量),
/// 所以把它单独留一个**不需要 Tauri app**的入口, 单测才能真的断言它。
pub fn status_of_state(st: &ServerState) -> Result<ServerStatus, String> {
    let cfg = st.config.read().map_err(|e| e.to_string())?.clone();
    let running = running_port();
    let port = running.unwrap_or(cfg.port);
    Ok(ServerStatus {
        running: running.is_some(),
        port,
        url: primary_url(port),
        token: cfg.token.clone(),
        shares: cfg.shares.iter().map(|p| p.to_string_lossy().to_string()).collect(),
        enabled: cfg.enabled,
        last_error: last_start_error(),
    })
}

/// Tauri 命令用的那一版(目前不需要 app, 留着是为了将来加"已连接来源")。
pub fn status_of(app: &tauri::AppHandle, st: &ServerState) -> Result<ServerStatus, String> {
    let _ = app;
    status_of_state(st)
}

/// 桌面端 `setup` 的唯一入口: 按配置决定是否拉起服务。
///
/// 返回值语义:
///   · `Ok(None)` —— 配置里 `enabled == false`(**默认**)。**什么都没做**: 不生成 token、
///     不落盘、不绑端口、不 spawn 服务任务。这是绝大多数用户每天走的路径。
///   · `Ok(Some(port))` —— 服务已监听。
///   · `Err(_)` —— 起不来。调用方只需记一行日志: 桌面端必须照常可用, 而且这个错误已经
///     被记进 `LAST_ERROR`, 界面能拿到一句可见的理由(`ServerStatus.last_error`)。
pub async fn boot(st: ServerState, app_data_dir: &Path) -> Result<Option<u16>, String> {
    boot_with(st, app_data_dir, auth::try_new_token).await
}

/// 保证配置里有一枚可用的 token 并落盘。**开启服务 / 轮换 token 的路径走这一条。**
///
/// 失败(随机数不可用 / 落盘失败)只返回 `Err` —— 调用方直接把它交给界面。
pub fn ensure_token_persisted(st: &ServerState, app_data_dir: &Path) -> Result<(), String> {
    ensure_token(st, app_data_dir, auth::try_new_token)
}

/// `boot` 的实现体。**token 生成器是参数** —— 只为了让"随机数不可用"这条路径能被
/// **真的走一遍**: `getrandom` 在真机上不会失败, 没有注入点就只能靠"看代码"保证"不 panic",
/// 那不算保证。生产路径只有 `boot` 一个调用方, 传的就是 `auth::try_new_token`。
async fn boot_with<F>(
    st: ServerState,
    app_data_dir: &Path,
    gen_token: F,
) -> Result<Option<u16>, String>
where
    F: FnOnce() -> Result<String, String>,
{
    // **默认路径必须最先返回。** 这一行的存在与否, 就是"服务默认关闭"这句承诺的全部:
    // enabled=false 时不许生成 token(启动路径不许摸随机数)、不许落盘、不许绑端口。
    if !wants_start(&st) {
        return Ok(None);
    }
    if let Err(e) = ensure_token(&st, app_data_dir, gen_token) {
        // 随机数不可用 / 落盘失败: 服务不启动, 但**理由要留给界面**(见 `ServerStatus`)。
        record_start_error(e.clone());
        return Err(e);
    }
    match start(st).await {
        Ok(p) => Ok(Some(p)),
        Err(e) => {
            record_start_error(e.clone());
            Err(e)
        }
    }
}

/// 保证服务手上有一枚**可用**的 token, 并把新生成的那一枚落盘。
///
/// 这条替代了 `config.rs` 里那条 `.filter(|c| c.token.len() == 64)`: 旧写法在 token 坏了
/// 的时候把**整份配置**丢回 `Default`(顺带把用户的 `shares`/`port`/`enabled` 一起清掉,
/// 而 `Default` 又会去碰随机数)。现在坏 token 只触发**重新生成 + 落盘**, 其余字段原样保留。
///
/// 随机数不可用(`gen_token` 给 `Err`)或落盘失败 → 只返回 `Err`: 服务不启动, 桌面端照常。
fn ensure_token<F>(st: &ServerState, app_data_dir: &Path, gen_token: F) -> Result<(), String>
where
    F: FnOnce() -> Result<String, String>,
{
    if config::token_is_valid(&st.config.read().map_err(|e| e.to_string())?.token) {
        return Ok(());
    }
    let fresh = gen_token()?;
    st.config.write().map_err(|e| e.to_string())?.token = fresh;
    save_config(st, app_data_dir)
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

    // ── Task 8 · 服务生命周期 ────────────────────────────────────────────────
    //
    // 这一批测试守的是**用户的唯一硬约束**: 桌面端不许被服务弄坏。三件事各自有钉子:
    //   ① 默认关闭 —— 不生成 token、不落盘、不绑端口(默认路径根本没走到随机数);
    //   ② 起不来只是 `Err` —— 随机数不可用、20 个端口全被占, 都不许 panic;
    //   ③ 空/坏 token 一律 fail-closed —— 服务不起, 更不许变成"关掉鉴权"。

    /// 碰进程级静态量(端口/停止开关/上次错误)的用例必须串行 —— 它们共享一份全局状态,
    /// 并行跑会互相把对方的断言搅红(端口被另一个用例占着、错误槽被清掉)。
    static GLOBAL_STATE_LOCK: StdMutex<()> = StdMutex::new(());

    /// 把"没有在跑"作为每个用例的起点。`stop()` 是幂等的。
    ///
    /// 为什么需要: 全局状态是**跨用例共享**的, 上一个用例中途 panic(例如断言失败)就会把它
    /// 带进下一个 —— 实测过一次: 监听器没收尾, 后面两条用例连着红, 报的还是"端口全被占
    /// 也起成功了"这种看不懂的理由。用例从干净状态出发, 红才是自己的红。
    fn reset_server_state() {
        stop();
    }

    /// 在 **Tauri 的运行时**上跑 `start` —— 与生产路径完全相同的调用方式。
    ///
    /// 不是洁癖, 是必需的: `start` 在**调用方**的运行时上注册监听器(tokio 的 I/O 资源归属),
    /// 却把 serve 任务 spawn 到 Tauri 的运行时上。`#[tokio::test]` 是 current_thread 运行时,
    /// 监听器若注册在那里, 另一条运行时上的 accept 永远等不到就绪事件 —— 实测表现是
    /// "起来了, 但所有请求读到超时(10060)"。理由详见 `start` 的文档。
    fn start_on_tauri_runtime(st: ServerState) -> Result<u16, String> {
        std::thread::spawn(move || tauri::async_runtime::block_on(start(st)))
            .join()
            .expect("start 线程不许 panic")
    }

    /// 每个用例一个独立目录。**绝不碰 `%APPDATA%`**(那是用户真实的配置与数据库)。
    fn tmp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("ifsrv_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    /// **"服务默认关闭"的端到端钉子(在最靠近 `setup` 的那条接线上)。**
    ///
    /// 默认配置 + 一个"一旦被调用就炸"的 token 生成器: 返回 `Ok(None)` 说明
    /// **默认路径上一个字都没干** —— 没生成 token、没绑端口、没起服务任务。
    ///
    /// 变异靶子: 把 `Default::default()` 的 `enabled` 翻成 `true`(或让 `boot_with`
    /// 无视 `enabled`), 生成器立刻被调用, 这条当场红。
    #[tokio::test]
    async fn default_config_boots_to_nothing_and_touches_no_randomness() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = tmp_dir("default_boot");
        let st = config::ServerConfig::default().into_state();
        assert!(!wants_start(&st), "默认配置必须是'不要起'");

        let outcome = boot_with(st, &dir, || panic!("默认关闭时绝不许生成 token/碰随机数")).await;
        assert_eq!(outcome, Ok(None), "默认配置下 boot 必须什么都不做");
        assert!(!dir.exists(), "默认路径不许创建 app 数据目录(更不许落盘)");
    }

    /// `enabled = true` 但**随机数不可用**: 起不来, 但只是 `Err`, 而且理由可读。
    /// 生成器作为参数注入, 所以这条路径是被**真的走了一遍**, 不是"看代码觉得不会崩"。
    #[tokio::test]
    async fn token_generation_failure_is_an_error_not_a_panic() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = tmp_dir("rng_fail");
        let st = config::ServerConfig {
            enabled: true,
            port: config::DEFAULT_PORT,
            token: String::new(), // Default 不再生成 token, 所以这是真实可达的状态
            shares: Vec::new(),
        }
        .into_state();

        let err = boot_with(st.clone(), &dir, || Err("模拟: 系统随机数不可用".into()))
            .await
            .expect_err("随机数不可用时必须返回 Err");
        assert!(err.contains("随机数"), "理由要能读: {}", err);
        assert!(st.config.read().unwrap().token.is_empty(), "失败后 token 仍是空的");
        assert!(!dir.exists(), "生成失败时不许落盘(避免留下'开着但没 token'的配置)");
        assert_eq!(
            status_of_state(&st).unwrap().last_error.as_deref(),
            Some("模拟: 系统随机数不可用"),
            "界面必须能拿到一句可见的理由"
        );
    }

    /// **空/坏 token 一律不许起服务**(fail-closed 的第一道; 第二道在鉴权层)。
    ///
    /// 没有这一条, `Default` 不再生成 token 之后, 一次"配置丢了 token"就能起出一台
    /// 谁都能进的服务器。变异靶子: 删掉 `start` 里那个 `token_is_valid` 判断。
    #[tokio::test]
    async fn start_refuses_empty_or_malformed_token_without_panicking() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        for bad in ["", "abc", &"z".repeat(64)] {
            let st = config::ServerConfig {
                enabled: true,
                // 0 会让绑定走到系统分配的临时端口上 —— 也就是说: 如果 fail-closed 那一行
                // 被删掉, 这台服务**真的会起起来**(于是这条测试红得有意义), 而不是
                // 靠"端口绑不上"蒙混过去。
                port: 0,
                token: bad.to_string(),
                shares: Vec::new(),
            }
            .into_state();
            let err = tokio::time::timeout(std::time::Duration::from_secs(10), start(st))
                .await
                .expect("起服务这条路不许卡住")
                .expect_err("token 不可用时必须拒绝启动");
            assert!(err.contains("token"), "理由里要有 token 二字: {}", err);
            assert_eq!(running_port(), None, "被拒的服务不许留下'在跑'的痕迹");
        }
    }

    /// **端口全被占**: 20 次自增都失败 → `Err`, 不是 panic、不是挂死。
    ///
    /// 这条对应的是"用户机器上 8765 被别的软件占了"这个很常见的现场。测试自己先占住
    /// 连续 20 个端口(从系统临时端口区里挑一段), 再让 `start` 去撞。
    #[tokio::test]
    async fn start_reports_error_when_every_candidate_port_is_taken() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        // 找一段 20 个连续可绑的端口: 绑上就先占着, 全占住之后 start 必然 20 连败。
        let mut held = Vec::new();
        let mut base = None;
        for candidate in (30_000u16..40_000).step_by(20) {
            let mut ok = true;
            let mut tmp = Vec::new();
            for p in candidate..candidate + 20 {
                match std::net::TcpListener::bind(("0.0.0.0", p)) {
                    Ok(l) => tmp.push(l),
                    Err(_) => {
                        ok = false;
                        break;
                    }
                }
            }
            if ok {
                held = tmp;
                base = Some(candidate);
                break;
            }
        }
        let base = base.expect("找不到 20 个连续空闲端口 —— 测试机器太挤了");

        let st = config::ServerConfig {
            enabled: true,
            port: base,
            token: "d".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        let err = tokio::time::timeout(std::time::Duration::from_secs(30), start(st))
            .await
            .expect("绑不上端口时必须立刻返回, 不许卡住")
            .expect_err("20 个端口全被占时必须返回 Err");
        assert!(err.contains("20 个端口"), "理由要指明是端口问题: {}", err);
        assert_eq!(running_port(), None);
        drop(held); // 端口还回去
    }

    /// 裸 TCP 打一条 HTTP/1.1 请求。**不引 HTTP 客户端**(与 Task 4 的契约测试同一条纪律)。
    fn http_get(port: u16, path: &str, token: Option<&str>) -> String {
        use std::io::{Read as _, Write as _};
        let mut s = std::net::TcpStream::connect(("127.0.0.1", port))
            .unwrap_or_else(|e| panic!("连不上刚起好的服务 {}: {}", port, e));
        s.set_read_timeout(Some(std::time::Duration::from_secs(10))).unwrap();
        let auth = token
            .map(|t| format!("Authorization: Bearer {}\r\n", t))
            .unwrap_or_default();
        let req = format!(
            "GET {} HTTP/1.1\r\nHost: 127.0.0.1\r\n{}Connection: close\r\n\r\n",
            path, auth
        );
        s.write_all(req.as_bytes()).expect("发请求");
        let mut out = String::new();
        s.read_to_string(&mut out).expect("读响应");
        out
    }

    fn status_line(res: &str) -> &str {
        res.lines().next().unwrap_or("")
    }

    /// **真起一台监听**: 证明 `start` 走的是真的 axum serve + 真的鉴权层, 并且 `stop`
    /// 真的把端口关掉。
    ///
    /// 为什么值得起真端口(而不是只打 `Router::oneshot`): 契约测试打的是
    /// `routes::router(state)` 这台**内存里的**路由器; "服务能在真端口上监听、能在被叫停时
    /// 放开端口"是这条接线独有的性质, 打 router 永远测不到。
    #[tokio::test]
    async fn real_listener_serves_with_auth_and_stop_actually_closes_the_port() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let token = "e".repeat(64);
        let st = config::ServerConfig {
            enabled: true,
            // 挑一个不常见的基端口; 真被占了 start 会自己 +1, 测试用的仍是返回值。
            port: 28_876,
            token: token.clone(),
            shares: Vec::new(),
        }
        .into_state();

        let port = start_on_tauri_runtime(st.clone()).expect("应能起服务");
        assert_eq!(running_port(), Some(port), "起来之后状态必须说'在跑'");
        assert!(status_of_state(&st).unwrap().running);

        // ① 免鉴权端点照常(规格 §5.1)
        let ping = http_get(port, "/api/v1/ping", None);
        assert!(status_line(&ping).contains("200"), "ping 免鉴权: {}", status_line(&ping));
        // ② 没 token 的受保护端点: 401(鉴权层真的罩住了整张表)
        let anon = http_get(port, "/api/v1/shares", None);
        assert!(status_line(&anon).contains("401"), "无 token 必须 401: {}", status_line(&anon));
        // ③ 正 token: 200
        let authed = http_get(port, "/api/v1/shares", Some(&token));
        assert!(
            status_line(&authed).contains("200"),
            "带正 token 必须 200: {} / {}",
            status_line(&authed),
            authed
        );

        // ④ 幂等: 已经在跑时再 start 一次不该再起第二台(端口不变)
        assert_eq!(start(st.clone()).await.expect("幂等"), port);

        // ⑤ stop 之后: 状态立刻是"没在跑", 端口真的放开(连接被拒)
        stop();
        assert_eq!(running_port(), None, "stop 之后状态必须立刻是'没在跑'");
        assert!(!status_of_state(&st).unwrap().running);
        let mut refused = false;
        for _ in 0..100 {
            if std::net::TcpStream::connect(("127.0.0.1", port)).is_err() {
                refused = true;
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        assert!(refused, "stop 之后端口 {} 必须真的不再接受连接", port);
    }

    /// `ServerStatus` 的线上形状(前端 Task 10 要按这些字段名取值)。
    #[test]
    fn server_status_serializes_as_camel_case() {
        let v = serde_json::to_value(ServerStatus {
            running: false,
            port: 8765,
            url: "http://127.0.0.1:8765".into(),
            token: "t".repeat(64),
            shares: vec!["C:\\照片".into()],
            enabled: false,
            last_error: None,
        })
        .unwrap();
        assert_eq!(v["running"], false);
        assert_eq!(v["port"], 8765);
        assert_eq!(v["url"], "http://127.0.0.1:8765");
        assert_eq!(v["token"], "t".repeat(64));
        assert_eq!(v["shares"][0], "C:\\照片");
        assert_eq!(v["enabled"], false);
        assert!(v["lastError"].is_null());
        assert!(v.get("last_error").is_none(), "字段名必须是 camelCase");
    }

    /// `local_ipv4` 不许 panic(取不到就是 `None`, 由 `primary_url` 回落到 127.0.0.1)。
    #[test]
    fn primary_url_never_panics_and_always_has_a_port() {
        assert!(primary_url(8765).ends_with(":8765"));
        assert!(primary_url(8765).starts_with("http://"));
        assert!(local_ipv4().map(|ip| !ip.is_empty()).unwrap_or(true));
    }
}
