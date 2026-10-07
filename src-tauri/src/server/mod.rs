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
    ///
    /// **这一份的语义是"下次开机还会不会尝试开启"**: 它永远是盘上那一份(`set_enabled`
    /// 在落盘失败时会把内存撤回成盘上的值)。所以界面不能只看它来决定"现在有没有在监听"。
    pub enabled: bool,
    /// 上一次**生命周期失败**的原因(**可见的理由**)。启动路径不许把桌面端搞崩, 所以失败
    /// 只能留在这里 + 一行 stderr; 界面据此给用户一句人话。启动成功后清空, `stop()` 也清空
    /// (用户亲手关了它, 还挂着旧理由是噪音); "关掉了但设置没能保存"会重新记一条。
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

/// 服务生命周期的**唯一**状态: 代数、在跑的端口、停止开关、在途 bind 的票据。
///
/// ── 为什么合成一个(本轮修复 · 复审 Critical 2) ──────────────────────────────
/// 这三个字段原先各自是一个静态量(`SHUTDOWN` / `RUNNING_PORT` / `SERVE_GEN`), 而
/// `start` 分三步**分别**写它们、`stop` 一次锁都不上。Tauri 把每条异步命令当成自己的任务
/// 丢在**多线程**运行时上, 所以两条交错的 enable/disable 命令是真的并行, 于是:
///   · stop 落在 bind 与"装停止开关"之间 → 信号发给了上一台(或没人), 新服务**一边
///     `enabled = false` 一边永久监听**;
///   · stop 落在"装开关"与"存端口"之间 → 端口被重新写回, 而 serve 任务因为代数已经推进
///     不再收尾 → **端口卡住**: 状态说"在跑"却没人监听, 之后每一次开启都在幂等检查上短路。
/// 两个窗口都是"状态被分成几份、各自上锁"造出来的。现在四个字段同住一把锁, `start` 与
/// `stop` 对它们的每一次读写都在**同一段临界区**里完成。
///
/// ── 不变量(改这一节之前先读三遍) ────────────────────────────────────────────
///   · `port == Some(p)` ⟺ 我们安装了一台监听 p 的服务(端口真的在 accept);
///   · `port == None` ⟹ 没有任何一台"我们以为在跑"的服务, **并且** `stop()` 已经把所有
///     在途安装作废(它在同一次临界区里推进代数), 所以端口不会卡住;
///   · 端口的每一次写入都在同一个临界区里伴随"这一代还被要着吗"的检查 —— 既不存在
///     "在监听但没人知道", 也不存在"没人监听却显示在跑"。
/// 由此得到本任务要求的那句: **任意交错之后, "到底有没有在监听"都答得出真话**
/// (`ServerStatus::running` 就是 `port.is_some()`, 见 `running_port`), **端口也不会卡住**。
struct Lifecycle {
    /// 服务的"代数"。安装一台服务 +1; `stop()`/自我取消也 +1 —— 后者是作废**在途**
    /// `start` 票据的唯一手段(bind 是 await, 期间完全可能有人把服务关掉)。
    gen: u64,
    /// 在跑的端口(`None` = 没在跑)。
    ///
    /// 为什么是进程级静态量而不是塞进 `ServerState`: `ServerStatus` 要能在**没有 app 状态**
    /// 的地方(单测、日志)回答"到底有没有在监听"。
    port: Option<u16>,
    /// 当前这一台的停止开关。
    ///
    /// 每次 `start` 造**新的一对** `watch` 通道装进这里: 复用同一个 sender 会让"上一次
    /// stop"的许可落到下一台上(新服务一启动就被关掉)。`stop()` 只关最近起的这一台。
    shutdown: Option<tokio::sync::watch::Sender<bool>>,
    /// 正在 bind 中途的那次 `start` 的票据(代号); 没有在途 start 时是 `None`。
    ///
    /// 生产逻辑只需要 `gen` 就能做判断, 这个字段存在的唯一理由是**可观测**: 没有它,
    /// "把 stop 插进 bind 与安装之间"这件事在测试里只能靠 sleep 猜时机 —— 要么假红
    /// (stop 先于取号跑完, 于是 start 合法地装上了服务), 要么假绿(窗口早过去了)。
    /// 有了它, 并发用例可以**等到**窗口真的开着再插进去(见 `mod tests` 的
    /// `concurrent_stop_during_bind_never_leaves_a_listener_behind`)。
    binding: Option<u64>,
}

static LIFECYCLE: StdMutex<Lifecycle> =
    StdMutex::new(Lifecycle { gen: 0, port: None, shutdown: None, binding: None });

/// 取生命周期锁。**中毒也照用**(`unwrap_or_else(|e| e.into_inner())`): 这个锁里放的是
/// 普通状态, 不是"可能被写坏的不变量"; 而一旦因为别处 panic 就再也拿不到这把锁,
/// 用户便**永远关不掉服务** —— 那正是本轮修复要消灭的那种失败。
fn lifecycle() -> std::sync::MutexGuard<'static, Lifecycle> {
    LIFECYCLE.lock().unwrap_or_else(|e| e.into_inner())
}

/// 起服务这条路的串行闸门。**只管"两次开启不许各绑一个端口"** ——
/// 它与 `LIFECYCLE` 是两件事, 别混:
///   · 这一把(`tokio::sync::Mutex`)串行**两次 start**: 并发的两次"开启"(开机拉起与用户
///     点开关撞在一起、或连点两下)会各绑一个端口, 先绑的那台从此没人知道它在监听;
///   · `LIFECYCLE`(std, 短临界区)才是 `start` 与 `stop` 之间的互斥 —— 后者可能在
///     bind 的 `.await` 中途发生, 所以它不能靠这把锁(拿了就没人能叫停了)。
/// 用 `tokio::sync::Mutex` 是因为要在 `.await`(建监听)之间持锁, `std::sync::Mutex`
/// 的守卫不是 `Send`, 会让 Tauri 命令的 future 编译不过。
static START_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 上一次**生命周期失败**的原因(start 失败, 或"关掉了但设置没能保存")。只为一件事:
/// 让界面能给出**可见的理由**(见 `ServerStatus::last_error`)。
///
/// 由 `stop()` 清空 —— 用户已经亲手把服务关了, 还挂着一句上次启动失败的理由是噪音,
/// 也会让"关了但它还在报错"看起来像没关掉。
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

    // 幂等 + 取票据。**这两件事必须在同一段临界区里** —— "有没有在跑"和"我要开始 bind 了"
    // 是同一个原子事实, 否则两次并发的开启之间还会漏出第二台服务。
    //
    // 票据(`gen`)的用法: bind 是 `.await`, 期间完全可能有人把服务关掉。`stop()` 会推进
    // 代数, 于是 bind 成功之后的那次安装会看到"我这一代已经不是当前这一代"并**把监听器
    // 丢掉** —— 这就是"stop 落在 bind 与安装之间"那个窗口的解药。
    let ticket = {
        let mut lc = lifecycle();
        if let Some(p) = lc.port {
            return Ok(p);
        }
        lc.gen += 1;
        lc.binding = Some(lc.gen);
        lc.gen
    };

    // 端口被占用就 +1 重试, 最多 20 次 —— 用户机器上 8765 被别的软件占了很常见
    let mut last_err = String::new();
    for offset in 0..20u16 {
        let port = cfg.port.saturating_add(offset);
        match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => {
                // 实际绑到的端口。与 `port` 不同的唯一情形是配置里写了 0(让系统挑) ——
                // 那时把 0 存进状态, 界面会显示一个谁都不该去连的端口。
                let bound = listener.local_addr().map(|a| a.port()).unwrap_or(port);
                let (tx, mut rx) = tokio::sync::watch::channel(false);
                // **安装这一步是整段代码的心脏。** 三个动作 —— 判断还能不能装、装停止开关、
                // 存端口 —— 必须在**同一次**临界区里完成, 中间不许有任何 `.await`/放锁的点:
                // Critical 2 的那两个交错窗口, 就是从把它们拆开的地方钻出来的。
                {
                    let mut lc = lifecycle();
                    // 还能装吗? 两个条件都在锁里读:
                    //   ① 代数没变 —— bind 期间没有人叫停(`stop()` 或另一个 start 的自取消);
                    //   ② 用户还要着它 —— 期间没有并发跑完一次"关闭"。少了②, 一次乱序的
                    //      "先点关、再点开"就能造出"enabled=false 却还在监听"。
                    //      `wants_start` 在读锁中毒时给 false, 也就是**不装**(fail-closed)。
                    if lc.gen != ticket || !wants_start(&state) {
                        // 我这次 bind 结束了(以"取消"结束): 状态里**不许**留着这张票据。
                        // 留着它, `binding` 就不再等于"有一次 start 正在 bind" —— 它会让
                        // 下一个人(或下一条并发用例)以为窗口还开着, 从而在错误的时刻插进来。
                        // 这正是本任务要消灭的那类"账与实际不符", 所以这里也照同一把尺子办。
                        if lc.binding == Some(ticket) {
                            lc.binding = None;
                        }
                        drop(lc);
                        // 没装上去的监听器一个字节都不留: 端口在这一行就放开了。
                        drop(listener);
                        let msg = "服务在启动过程中被关闭, 未开始监听(端口已放开)".to_string();
                        record_start_error(msg.clone());
                        return Err(msg);
                    }
                    lc.shutdown = Some(tx);
                    lc.port = Some(bound);
                    lc.binding = None;
                }
                clear_start_error();
                // **整张路由表只有一个入口** `routes::router`, 鉴权层罩住全部(见模块头)。
                let app = routes::router(state.clone());
                let gen = ticket;
                tauri::async_runtime::spawn(async move {
                    let served = axum::serve(listener, app)
                        .with_graceful_shutdown(async move {
                            // `watch` 是**有状态**的: 即使 stop 发生在服务还没开始等之前,
                            // `changed()` 也会立刻返回(不像 `Notify` 会漏掉那一次喊话)。
                            let _ = rx.changed().await;
                        })
                        .await;
                    // 只有"我这一代"才有资格清状态: 否则上一台的收尾会把**新一台**的端口
                    // 抹掉, 状态说"没在跑"而实际上在监听。
                    {
                        let mut lc = lifecycle();
                        if lc.gen == gen {
                            lc.port = None;
                            lc.shutdown = None;
                        }
                    }
                    if let Err(e) = served {
                        eprintln!("[server] 服务结束: {}", e);
                    }
                });
                return Ok(bound);
            }
            Err(e) => last_err = e.to_string(),
        }
    }
    // 20 个端口全绑不上。**这里天然不会留下陈旧状态**: `port` 只在上面那次安装里被写过,
    // 一次都没走到的路径不可能留下端口; binding 也清掉, 免得让并发用例以为还有一次
    // bind 在途。以后谁想把端口/状态的写入提前到 bind 之前, 先把这句话读一遍 ——
    // "绑不上却显示在跑"正是本轮要消灭的第二种谎。
    {
        let mut lc = lifecycle();
        if lc.binding == Some(ticket) {
            lc.binding = None;
        }
    }
    let msg = format!(
        "从 {} 起的 20 个端口都绑定失败(可能被其它软件占用): {}",
        cfg.port, last_err
    );
    record_start_error(msg.clone());
    Err(msg)
}

/// 停止当前这一台服务。**幂等**: 没在跑时什么都不做(但代数照样推进, 见下)。
///
/// 为什么这一条必须在 M0′ 就有: `set_server_enabled(false)` 的语义是"用户关掉了它"。
/// 关掉之后还继续监听, 就是在对用户撒谎 —— 而且是安全上最难察觉的那种。
///
/// **与 `start` 的互斥**: 下面的每一次读写都在 `LIFECYCLE` 那一把锁里, 于是两种交错都
/// 有确定结果:
///   · stop 落在 bind 之后、安装之前 → 代数被推进, 那次安装自己取消并**把监听器丢掉**;
///   · stop 落在安装之后 → 端口在同一次临界区里就被清掉, 不可能再被写回来。
/// 端口立刻清掉(不等 serve 未来收尾): 状态查询马上能看到"没在跑"; 而推进代数是为了
/// 让即将收尾的那台任务不去清**下一台**的状态。
///
/// 顺带清 `LAST_ERROR`: 用户已经把它关了, 还挂着一句上一次的启动失败理由是噪音。
/// (关闭路径自己若在落盘上失败, 会在 `stop()` **之后**记一条新的理由 —— 见 `set_enabled`。)
pub fn stop() {
    let mut lc = lifecycle();
    if let Some(tx) = lc.shutdown.take() {
        let _ = tx.send(true);
    }
    lc.port = None;
    // 代数推进: **作废所有在途 start 的票据** —— 这是"stop 卡在 bind 中途"唯一可靠的解药。
    lc.gen += 1;
    drop(lc);
    clear_start_error();
}

/// 在跑的端口(`None` = 没在跑)。
///
/// **与 `start`/`stop` 同一把锁**: 它回答的是"我们到底安装了一台服务没有", 而不是去猜。
pub fn running_port() -> Option<u16> {
    lifecycle().port
}

/// **只给测试**: 在途 bind 的票据(`None` = 没有 start 正在 bind)。
///
/// 生产代码不用它 —— 见 `Lifecycle::binding` 上那段说明: 它的价值是让"stop 插进 bind
/// 窗口"这件事**可被观测**, 于是那条并发用例是确定性的, 而不是靠 sleep 猜时机。
#[cfg(test)]
pub(crate) fn binding_ticket_for_test() -> Option<u64> {
    lifecycle().binding
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
///
/// ⚠️ **`running` 与 `enabled` 是两件事, 界面必须两个都看**(见 `ServerStatus` 的字段注释):
/// `running` 由 `LIFECYCLE` 那一把锁给出, 任何时刻都说真话; `enabled` 是用户的选择。
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

/// **开/关服务的完整实现**。Tauri 命令 `set_server_enabled` 只负责把 app 数据目录翻译成
/// 一个 `&Path` 再调这里。
///
/// 为什么参数是 `&Path` 而不是 `AppHandle`: "落盘失败时端口也必须照样放开"是本轮修复的
/// 核心(Critical 1), 而"盘写不进去"只有把目录当参数才**测得出来** —— 测试可以把配置路径
/// 指到一个写不进去的位置, 让这条路径被真的走一遍, 而不是靠看代码相信它。
///
/// ── 落盘失败时用户看到什么(本轮明确做出的决定) ──────────────────────────────
///   · **"关"这个动作的第一保证是端口被放开**: `stop()` 无条件执行, 与落盘成败无关。
///   · **盘上那一份赢**: 落盘失败意味着盘上的 `enabled` 一个字节都没变, 所以内存里那个
///     开关必须**撤回**成盘上仍然写着的值。理由是安全的 —— 下次开机读的是盘, 内存跟着盘
///     走才不会出现"界面说已关闭、下次开机却照旧监听"(那正是 Critical 1 的一半)。
///   · 于是状态是 `enabled: true, running: false, lastError: "…没能保存…"`: **服务真的已经
///     停止监听**(端口放开了), 但"关掉"这个设置没保存下来, 下次启动仍会尝试开启。
///     界面必须把 `lastError` 显示出来, 否则用户会以为自己只是没点中。
///   · 开启路径**对称**: 落盘失败同样把 `enabled` 撤回成盘上那一份, 而且保证一个监听都没起
///     (顺序是"先落盘、后 start", 见下)。
pub async fn set_enabled(
    st: &ServerState,
    app_data_dir: &Path,
    enabled: bool,
) -> Result<ServerStatus, String> {
    if enabled {
        // 顺序是刻意的: ①先把 token 备好(随机数不可用 → 直接 Err, 配置一个字节都不动);
        // ②再打开开关并落盘; ③最后尽力起服务。任何一步失败都只是 Err。
        //
        // ③ 失败时**不**把 enabled 回滚: 用户的选择照旧留着(下次开机还会再试一次), 而服务
        // 并没有在监听 —— `running` 与 `enabled` 在状态里是分开的, 界面据实显示即可。
        ensure_token_persisted(st, app_data_dir)?;
        let prev = read_enabled(st);
        write_enabled(st, true)?;
        if let Err(e) = save_config(st, app_data_dir) {
            // 撤回成盘上那一份。此刻**一个监听都没起**(start 在落盘之后), 所以这里没有
            // 端口要放开 —— 与关闭路径的"无条件 stop"对应, 那边才是会留下端口的一侧。
            let _ = write_enabled(st, prev);
            let msg = format!(
                "服务未启动: 设置没能保存(重启后仍是之前的状态)。{}",
                e
            );
            record_start_error(msg.clone());
            return Err(msg);
        }
        match start(st.clone()).await {
            Ok(_) => status_of_state(st),
            Err(e) => {
                record_start_error(e.clone());
                Err(e)
            }
        }
    } else {
        let prev = read_enabled(st);
        // **先写内存、再停、最后落盘** —— 三步的顺序都是刻意的:
        //   ① `enabled = false` 必须**先于** `stop()`: 在途的 `start` 会在安装前读到
        //      "用户不要了" 从而自我取消(见 `start` 的安装检查)。反过来的话, 一次乱序的
        //      "先点关、再点开" 就能造出"关着却还听着"。
        //   ② `stop()` **无条件**执行: 端口必须在这一步放开, 与落盘的成败无关(Critical 1)。
        //      落盘失败这条路上, 旧代码的 `?` 会在 stop 之前提前返回 —— 于是监听一直留着,
        //      而内存已经说"关"、盘上还说"开": 用户**再也关不掉它**。
        //   ③ 落盘。失败就撤回①(盘上那一份赢), 并把理由交给界面。
        //
        // 注意①与②之间**不许**出现 `?`: 写内存失败(配置读锁中毒)也绝不早退, 否则端口
        // 就留下来了 —— "停"这个动作不许被任何别的事情挡在前面。
        let wrote = write_enabled(st, false);
        stop();
        wrote.map_err(|e| format!("服务已停止监听, 但设置没能保存(内部状态不可用): {}", e))?;
        if let Err(e) = save_config(st, app_data_dir) {
            let _ = write_enabled(st, prev);
            let msg = format!(
                "已停止监听, 但设置没能保存(下次启动仍会尝试开启; 界面里的开关会回到'开')。{}",
                e
            );
            record_start_error(msg.clone());
            return Err(msg);
        }
        status_of_state(st)
    }
}

/// 读内存里的开关。**读不到(锁中毒)或 false** —— 调用方用它做撤回, 而"读不到就当作
/// 没开"是 fail-closed 的方向: 绝不会把服务撤回到"开着"。
fn read_enabled(st: &ServerState) -> bool {
    st.config.read().map(|c| c.enabled).unwrap_or(false)
}

/// 写内存里的开关。
fn write_enabled(st: &ServerState, v: bool) -> Result<(), String> {
    st.config.write().map_err(|e| e.to_string())?.enabled = v;
    Ok(())
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

    /// 本轮新增: **走生产入口 `set_enabled`**(Tauri 命令体里那一句)的那一层包装。
    ///
    /// 与 `start_on_tauri_runtime` 同一条纪律: `start` 把监听器注册在**调用方**的运行时上,
    /// 所以必须照生产的调用方式(Tauri 运行时)进来 —— 否则表现为"起来了但请求超时"。
    fn set_enabled_on_tauri_runtime(
        st: &ServerState,
        dir: &Path,
        enabled: bool,
    ) -> Result<ServerStatus, String> {
        let st = st.clone();
        let dir = dir.to_path_buf();
        std::thread::spawn(move || tauri::async_runtime::block_on(set_enabled(&st, &dir, enabled)))
            .join()
            .expect("set_enabled 线程不许 panic")
    }

    /// 端口此刻真的在接受连接吗?
    fn accepts_connections(port: u16) -> bool {
        std::net::TcpStream::connect(("127.0.0.1", port)).is_ok()
    }

    /// 轮询到端口真的不再接受连接(或超时)。
    ///
    /// **必须有这一层**: 端口不是瞬间就关的 —— `stop()` 放下状态并把叫停信号发出去之后,
    /// serve 任务要走到干净的收尾才真的关掉 socket。直接 connect 一次就断言会得到一个
    /// 随机的红。
    fn wait_until_refused(port: u16, within: std::time::Duration) -> bool {
        let deadline = std::time::Instant::now() + within;
        loop {
            if !accepts_connections(port) {
                return true;
            }
            if std::time::Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
    }

    /// 占住从 base 起的 **19** 个连续端口, 并确认第 20 个(base+19)是空的。
    ///
    /// 用途只有一个: 让 `start` 的 bind 循环**必须**连撞 19 次失败才会成功 —— 那个窗口
    /// 足够长, 并发用例才插得进去(见 `concurrent_stop_during_bind_...`)。找不到就给 None
    /// (本机端口太挤), 由调用方**大声**失败, 而不是悄悄跳过。
    fn hold_nineteen_so_the_twentieth_bind_is_the_one(
    ) -> Option<(u16, Vec<std::net::TcpListener>)> {
        for candidate in (30_000u16..40_000).step_by(20) {
            let mut held = Vec::new();
            let mut ok = true;
            for p in candidate..candidate + 20 {
                match std::net::TcpListener::bind(("0.0.0.0", p)) {
                    Ok(l) => held.push(l),
                    Err(_) => {
                        ok = false;
                        break;
                    }
                }
            }
            if ok {
                // 第 20 个(start 真正会绑上的那个)还回去, 其余 19 个继续占着。
                drop(held.pop().expect("刚刚绑上了 20 个"));
                return Some((candidate, held));
            }
        }
        None
    }

    /// **Critical 1 的钉子: 关服务这条路上落盘失败, 也绝不许把监听留下来。**
    ///
    /// 旧代码的顺序是 `enabled=false` → `persist(...)?` → `stop()`: 落盘一旦失败, 那个 `?`
    /// 就在 `stop()` **之前**返回了。后果不是"少关一次", 而是三种谎叠在一起 ——
    /// 内存说关(界面显示关闭)、盘上还说开(下次开机照旧监听)、而端口一直在 accept,
    /// 并且**没有任何命令能再关掉它**。
    ///
    /// 落盘失败在这儿是**真的**发生的, 不是注入的假错误: 把配置文件名占成一个目录,
    /// 于是 `create_dir_all` 成功、紧接着那句 `fs::write(<目录>)` 被文件系统拒掉
    /// (Windows: os error 5 拒绝访问)。
    ///
    /// 变异靶子: 把关闭分支改回 "先 `persist(...)?` 再 `stop()`" → 端口不会被放开, 这条红。
    #[test]
    fn disable_releases_the_port_even_when_persisting_fails() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("disable_persist_fail");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        let st = config::ServerConfig {
            enabled: true,
            port: 29_876,
            token: "c".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        // 先起一台**真的**在监听的服务(否则这条用例什么都没证明)。
        let port = start_on_tauri_runtime(st.clone()).expect("应能起服务");
        assert!(accepts_connections(port), "夹具前提: {} 必须真的在监听", port);
        assert!(http_get(port, "/api/v1/ping", None).contains("200"));

        // 把配置路径占成一个目录 —— 落盘**必然**失败(真实文件系统的 Err)。
        std::fs::create_dir(dir.join(config::CONFIG_FILE)).expect("把配置文件名占成目录");

        let err = set_enabled_on_tauri_runtime(&st, &dir, false).expect_err("落盘失败必须回 Err");
        assert!(err.contains("没能保存"), "理由要能读: {}", err);

        // ① 端口**真的**放开了(不是"状态清零"而已)。
        assert!(
            wait_until_refused(port, std::time::Duration::from_secs(10)),
            "落盘失败也**必须**把 {} 放开 —— 否则 service 关不掉(旧代码的 `?` 正是这样)",
            port
        );
        // ② 状态里 running 说实话。
        assert_eq!(running_port(), None, "stop 之后状态必须立刻是'没在跑'");
        let status = status_of_state(&st).unwrap();
        assert!(!status.running, "状态必须说'没在跑'");
        // ③ **盘上那一份赢**: 内存里的开关被撤回成盘上仍然写着的 true, 并给出可见的理由。
        //    下次开机读的是盘, 所以这一份才是"接下来会发生什么"的诚实描述。
        assert!(status.enabled, "落盘失败时内存必须撤回成盘上那一份(true)");
        assert!(
            status.last_error.as_deref().unwrap_or("").contains("没能保存"),
            "界面必须能拿到一句可见的理由: {:?}",
            status.last_error
        );
    }

    /// **开启路径的对称性**: 落盘失败时不许留下"界面说开着、盘上没开"的错位,
    /// 而且**一个监听都不许起**(start 在落盘之后, 顺序本身就是保证)。
    #[test]
    fn enable_rolls_the_flag_back_and_starts_nothing_when_persisting_fails() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("enable_persist_fail");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        std::fs::create_dir(dir.join(config::CONFIG_FILE)).expect("把配置文件名占成目录");

        let st = config::ServerConfig {
            enabled: false,
            port: config::DEFAULT_PORT,
            token: "b".repeat(64), // token 已可用 → `ensure_token_persisted` 不动盘
            shares: Vec::new(),
        }
        .into_state();

        let err = set_enabled_on_tauri_runtime(&st, &dir, true).expect_err("落盘失败必须回 Err");
        assert!(err.contains("没能保存"), "理由要能读: {}", err);
        assert_eq!(running_port(), None, "落盘失败时一个监听都不许起");
        let status = status_of_state(&st).unwrap();
        assert!(!status.enabled, "内存必须撤回成盘上那一份(false)");
        assert!(!status.running);
    }

    /// **Critical 2 的钉子(A): `stop()` 必须能作废一次"正在 bind 中途"的 `start()`。**
    ///
    /// 场景就是复审点名的**第一个交错**: stop 落在 bind 与"装停止开关/存端口"之间。
    /// 旧代码里那一枪打给了上一台(或没人), 于是新服务**一边 `enabled = false`
    /// 一边永久监听** —— 用户关不掉它。
    ///
    /// 怎么做到**确定性**(不靠 sleep 猜时机): `Lifecycle::binding` 在 `start` 取票据时
    /// 立刻可见。用例先**等到**那个窗口真的开着, 再插 `stop()`; 为了让窗口足够长, 预先
    /// 占住前 19 个候选端口, 于是 `start` 必须连撞 19 次失败的 bind 才绑得上第 20 个。
    ///
    /// 这条用例自己带**夹具自检**: 一轮都没命中窗口(即 `start` 全都在 `stop()` 之前就装好)
    /// 就直接红 —— 否则它会变成一条"永远绿、什么都没证明"的用例。
    ///
    /// 变异靶子: 把 `stop()` 里那句 `lc.gen += 1` 删掉(等价于旧代码"stop 不与在途 start
    /// 序列化": 信号发给旧 sender, 新服务照常安装) → 那次安装不再自我取消, 这条红。
    #[test]
    fn concurrent_stop_during_bind_never_leaves_a_listener_behind() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let (base, held) = hold_nineteen_so_the_twentieth_bind_is_the_one()
            .expect("找不到 20 个连续空闲端口 —— 测试机器太挤了");

        let st = config::ServerConfig {
            enabled: true, // 这一条只打 start/stop 的交错: 开关始终"要着", 别让其它机制替它兜底
            port: base,
            token: "7".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        const ROUNDS: u32 = 40;
        let mut hits = 0u32; // 真的命中"stop 插进 bind 窗口"的轮次
        let mut leaks: Vec<(u32, u16)> = Vec::new();
        for round in 0..ROUNDS {
            assert_eq!(running_port(), None, "第 {} 轮开始前不该有监听", round);
            // 夹具前提: 没有**上一次**遗留的"在途 bind"标记 —— 否则下面的等待会立刻返回,
            // 于是 `stop()` 可能跑在取票据**之前**, 那是一种合法的顺序(then 服务该装上去),
            // 这条用例就会把正确行为判成红(实测: 这正是 `binding` 没在取消路径上清掉的后果)。
            assert!(
                binding_ticket_for_test().is_none(),
                "第 {} 轮开始前不该有遗留的在途票据({:?})",
                round,
                binding_ticket_for_test()
            );
            let st_start = st.clone();
            let starter =
                std::thread::spawn(move || tauri::async_runtime::block_on(start(st_start)));
            // **等到窗口真的开着**: 取票据 → binding 立刻可见。自旋等, 把探测延迟压到最小。
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            while binding_ticket_for_test().is_none() {
                assert!(
                    std::time::Instant::now() < deadline,
                    "等不到 start 进入 bind 窗口(第 {} 轮)",
                    round
                );
                std::hint::spin_loop();
            }
            stop();
            let outcome = starter.join().expect("start 线程不许 panic");
            // 命中窗口的证据: 安装那一步自己取消了(而不是"start 早就装完、stop 事后收拾")。
            if outcome
                .as_ref()
                .err()
                .map(|e| e.contains("启动过程中被关闭"))
                .unwrap_or(false)
            {
                hits += 1;
            }
            if let Some(p) = running_port() {
                leaks.push((round, p));
                // 收尾: 把这台"偷活下来"的服务关掉再继续, 免得后面的轮次被幂等检查短路。
                stop();
                let _ = wait_until_refused(p, std::time::Duration::from_secs(5));
            }
        }
        drop(held);
        assert!(
            leaks.is_empty(),
            "stop 插进 bind 窗口之后仍然留下了监听(轮次, 端口): {:?}",
            leaks
        );
        println!("[test] 命中 bind 窗口 {}/{} 轮(其余轮次是 stop 后到, 那也合法)", hits, ROUNDS);
        assert!(
            hits > 0,
            "夹具自检失败: {} 轮里一次都没命中 'stop 与 bind 交错' 的窗口 —— 这条用例什么都没证明",
            ROUNDS
        );
    }

    /// **Critical 2 的钉子(B): 并发开启/关闭之后, "在跑吗"必须与"真的连得上吗"一致,
    /// 而且端口不许被卡住(后续的开启必须真的能绑上)。**
    ///
    /// 与钉子(A)的分工: (A) 用可观测的 bind 窗口把"stop 与 start 交错"变成确定性的;
    /// (B) 打的是**真实入口** `set_enabled`, 也就是 enable/disable 两条命令真的并行跑,
    /// 逐轮断言不变量:
    ///   · 状态说在跑 ⟹ 那个端口**此刻真的能连上**(不是"端口的账"被写坏了);
    ///   · 全部轮次结束后干净地关一次: 状态必须说"没在跑", 且所有报告过在跑的端口都必须
    ///     真的不再接受连接;
    ///   · 再来一次干净的开启: 它必须**真的绑上**(旧代码的第二种交错让端口永远非 0,
    ///     于是每一次 enable 都在幂等检查上短路 —— 状态说在跑, 却没人监听)。
    ///
    /// 诚实标注: (B) 的"状态与连接一致"这条断言只有在交错**真的发生**时才有杀伤力 ——
    /// 它是一张网, 不是证明。确定性那部分由 (A) 承担。
    #[test]
    fn concurrent_enable_disable_keeps_status_truthful_and_the_port_unstuck() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("concurrent_enable_disable");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        let st = config::ServerConfig {
            enabled: false,
            // 挑一个不常见的基端口: 被占了 start 会自己 +1, 断言用的始终是返回值。
            port: 31_876,
            token: "8".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        save_config(&st, &dir).expect("先把一份合法的配置落盘");

        let mut reported: Vec<u16> = Vec::new();
        for round in 0..25 {
            let (a, b) = (st.clone(), st.clone());
            let (da, db) = (dir.clone(), dir.clone());
            let enable =
                std::thread::spawn(move || tauri::async_runtime::block_on(set_enabled(&a, &da, true)));
            let disable =
                std::thread::spawn(move || tauri::async_runtime::block_on(set_enabled(&b, &db, false)));
            let r_enable = enable.join().expect("enable 线程不许 panic");
            let r_disable = disable.join().expect("disable 线程不许 panic");

            // 不变量①: 状态说在跑 ⟹ 此刻真的连得上。
            if let Some(p) = running_port() {
                assert!(
                    accepts_connections(p),
                    "第 {} 轮: 状态说 {} 在跑, 但它不接受连接(端口的账被写坏了)",
                    round,
                    p
                );
                reported.push(p);
            }
            // 不变量②: 干净的目录上"关闭"永远该成功 —— 它失败就说明 stop 那条路被别的东西
            // 挡住了(本轮修复的核心之一)。
            assert!(
                r_disable.is_ok(),
                "第 {} 轮: 关闭失败了(不该发生): {:?}",
                round,
                r_disable.err()
            );
            let _ = r_enable; // 开启可能被并发的关闭取消 —— 那是**合法**结果(报错而不监听)
        }

        // 收尾①: 干净关闭 → 状态说没在跑, 且**所有**报告过在跑的端口最终都必须被拒。
        set_enabled_on_tauri_runtime(&st, &dir, false).expect("干净关闭必须成功");
        assert_eq!(running_port(), None, "干净关闭之后状态必须说'没在跑'");
        for p in &reported {
            assert!(
                wait_until_refused(*p, std::time::Duration::from_secs(10)),
                "端口 {} 在关闭之后仍然接受连接(端口被卡住了)",
                p
            );
        }

        // 收尾②: 后续的开启必须**真的**绑上。
        let status = set_enabled_on_tauri_runtime(&st, &dir, true).expect("再次开启必须成功");
        assert!(status.running, "开启之后状态必须说'在跑'");
        let p = running_port().expect("开启之后状态必须说'在跑'");
        assert!(accepts_connections(p), "开启之后的端口 {} 必须真的接受连接", p);

        // 把全局状态还给别的用例。
        set_enabled_on_tauri_runtime(&st, &dir, false).expect("关闭");
        assert_eq!(running_port(), None);
        assert!(wait_until_refused(p, std::time::Duration::from_secs(10)));
    }

    /// **minor ②: `stop()` 要清掉上一次的理由** —— 用户已经亲手把服务关了, 界面不该
    /// 还挂着那句旧的启动失败。
    #[test]
    fn stop_clears_a_stale_lifecycle_error() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let st = config::ServerConfig {
            enabled: true,
            port: 0,
            token: String::new(), // token 不可用 → start 拒绝并记下理由
            shares: Vec::new(),
        }
        .into_state();
        let _ = start_on_tauri_runtime(st.clone()).expect_err("空 token 必须被拒");
        assert!(
            status_of_state(&st).unwrap().last_error.is_some(),
            "夹具前提: 拒绝启动必须留下一条可见的理由"
        );
        stop();
        assert_eq!(
            status_of_state(&st).unwrap().last_error,
            None,
            "stop() 必须清掉旧理由"
        );
    }

    /// 配置里写 `port: 0`(= 让系统随便挑一个)时, 状态里那个端口**必须是真的那个**。
    ///
    /// 旧代码把请求的 `0` 存进 `RUNNING_PORT`, 而 `running_port()` 把 0 当成"没在跑" ——
    /// 于是状态说"没在跑"而实际上在监听。这是同一类谎(lifecycle 的账与实际不符),
    /// 所以顺手钉住。
    #[test]
    fn a_zero_configured_port_is_reported_as_the_port_that_is_really_listening() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let st = config::ServerConfig {
            enabled: true,
            port: 0,
            token: "6".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        let port = start_on_tauri_runtime(st.clone()).expect("应能起服务");
        assert_ne!(port, 0, "系统挑的端口不可能是 0");
        assert_eq!(running_port(), Some(port), "状态必须报出**真的**那个端口");
        assert!(accepts_connections(port));
        stop();
        assert!(wait_until_refused(port, std::time::Duration::from_secs(10)));
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
