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
pub mod network;
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
    /// 最近的请求来源(M4′ Task 3 的"谁在连我")。**内存环形缓冲, 容量有界**(见
    /// `routes::CLIENTS_CAPACITY`), 不落盘 —— 它是"此刻与刚才", 不是历史。
    ///
    /// 为什么是状态里的一个字段、而不是又一个进程级 `static`(与 `LAST_ERROR`/`PUBLIC_ACK`
    /// 不同): 它**每一条请求**都要写。做成静态量的话, 同一个测试进程里所有用例会共用一份日志
    /// —— "这条请求被记下来了吗"会因为**别的用例恰好也请求过**而变绿, 也就是测试因为错误的
    /// 原因通过。挂在状态上之后, 每台 `into_state()` 出来的路由各有自己的一份, 断言是确定的。
    ///
    /// 类型纪律与 `notify` 完全一样: 里面只有 `std` 的东西(`Arc<StdMutex<…>>`), 绝不带
    /// Tauri 类型(见 `Notify` 上那段 —— 测试 exe 是**加载时**死, 不是变红)。
    pub clients: Arc<StdMutex<routes::ClientLog>>,
}

impl config::ServerConfig {
    /// 把一份配置变成服务状态。**集成测试与单测共用这一条入口**(不带通知端, 事件分支跳过)。
    pub fn into_state(self) -> ServerState {
        ServerState {
            config: Arc::new(RwLock::new(self)),
            notify: None,
            clients: Arc::new(StdMutex::new(routes::ClientLog::default())),
        }
    }

    /// 正式运行用: 带上通知端, 才能在手机改动后通知桌面端 UI 刷新(`lib.rs` 里构造)。
    pub fn into_state_with_notify(self, notify: Notify) -> ServerState {
        ServerState {
            config: Arc::new(RwLock::new(self)),
            notify: Some(notify),
            clients: Arc::new(StdMutex::new(routes::ClientLog::default())),
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
    /// **这一份的语义是"下次开机还会不会尝试开启"**: 它永远是**盘上此刻真正写着的那一份**
    /// (`set_enabled` 落盘失败时会把内存对齐到盘上, 而不是写回一个进入时的旧快照 ——
    /// 见 `realign_from_disk`)。所以界面不能只看它来决定"现在有没有在监听"。
    pub enabled: bool,
    /// 上一次**生命周期失败**的原因(**可见的理由**)。启动路径不许把桌面端搞崩, 所以失败
    /// 只能留在这里 + 一行 stderr; 界面据此给用户一句人话。启动成功后清空, `stop()` 也清空
    /// (用户亲手关了它, 还挂着旧理由是噪音); "关掉了但设置没能保存"会重新记一条。
    pub last_error: Option<String>,
    /// 用户在"当前网络是公用"这条警告下**显式确认过**这件事(仅**本次运行**有效)。
    ///
    /// `Some(unix 秒)` = 确认过, 值是确认发生的时刻; `None` = 没确认过。它存在的唯一理由是
    /// M4′ Task 2 的那条硬约束: **公用网络下开启服务绝不能静默发生** —— 界面因此有据可查地
    /// 显示"这次开启是有人点过确认的"。
    ///
    /// 两条语义都是刻意的:
    ///   · **记录不参与判定**: 它**不会**让下一次开启自动放行 —— 每一次开启都要**当场**拿到
    ///     `confirm_public = true`(见 `set_enabled`)。"上次确认过"永远不当成"这次的许可"。
    ///   · **只活在本次运行里**: 进程重启后必然回到 `None`, 于是下次开机在公用网络上**还会
    ///     再拦一次**。确认是一次动作, 不是一份永久豁免(把它落盘就会变成后者)。
    pub public_ack_at: Option<u64>,
    /// **只读模式**(M4′ Task 3): `true` 时所有**写端点**都回 `403 forbiddenPath`, 读端点照常。
    ///
    /// 这一份就是配置里的那一份(`config::ServerConfig::read_only`), 不做任何推断 —— 面板的
    /// 开关状态由它说了算, 前端不自己猜"点了应该开了吧"。
    pub read_only: bool,
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
/// 先把两件事分开说 —— "我们装了一台服务" 与 "socket 此刻真的在 LISTEN" 是**最终一致**,
/// 不是同一时刻。这一点必须说清, 因为用户问的恰恰是"它关了没有?":
///   · `port == Some(p)` ⟹ 我们安装了一台绑定 p 的 serve 任务, 而且它**还没有收尾完毕**;
///     在它还活着的时候, p 上真的有一个 LISTEN 的 socket 在 accept
///     (accept 由 axum 的循环做: `impl Listener for TcpListener` 的 accept 是 `loop`,
///     连接类错误直接重试, **不会**把错误抛出来 —— 所以这台服务不会因为一次失败的 accept
///     就悄悄死掉)。
///   · `port == None` ⟹ 没有任何一台"我们还要着"的服务: `stop()` 在**同一次临界区**里
///     清端口、推进代数、发出停止信号, 于是在途的 bind 一定自我取消, 端口不会被卡住。
///     **但这不等于"socket 已经关了"**: 停止信号走 `watch` 通道, serve 任务要在**它自己的
///     那一次调度**里才会看到, 然后才 `drop(listener)`; 在那之前内核 backlog 仍能完成
///     三次握手, 也就是说 `connect()` 还可能成功一小会儿(见下面的"误差方向")。
///   · 端口的每一次写入都在同一个临界区里伴随"这一代还被要着吗"的检查 —— 不会出现
///     "在监听但没人知道"。
///
/// ── 误差的方向(改这句话之前请先读 `after_stop_the_port_stops_accepting_within_a_bounded_window`) ──
///   · `None` 之后那个窗口是**短暂地偏乐观**: 状态说"没在跑", 而端口还能连一小会儿。
///     上界 = "serve 任务被调度到 + drop 掉监听器", 也就是**毫秒**量级:
///     实测(10 轮, 探针自带 50ms 上界, 三次跑都是同一个数)从 `stop()` 到"端口不再接受连接"
///     最坏 63ms —— 其中还含着探针自己最多 50ms 的等待, 所以真实窗口比这更小;
///     而窗口**确实出现过**: 有一轮 `stop()` 返回后 193µs 的裸探针把握手做成功了
///     (那一轮 serve 任务还没被调度到)。
///     方向上它是安全的一侧: 它只会让人**早**几毫秒看到"已关闭", 绝不会造成"关不掉"。
///     axum 0.8.9 的收尾顺序对我们有利 —— 先 `break` 出 accept 循环、**紧接着**就
///     `drop(listener)`(`src/serve/mod.rs:284-297`), 之后才去等在建连接收尾,
///     所以手机上正在下载一个大文件**不会**把这个窗口拖长。
///   · 反方向(`Some(p)` 而 socket 已经关了)在今天的代码里**不可达**: 带 graceful shutdown
///     的 `axum::serve` 只在停止信号之后才返回(`WithGracefulShutdown::run` 的循环只有
///     这一个出口), 而唯一的信号发送者是 `stop()`, 它已经清掉端口并推进代数 —— 所以 serve
///     任务返回之后那段清状态是**防御性**的 no-op。`start` 里 bind 成功到
///     `lc.port = Some(bound)` 之间也没有 `.await`, 只有几条指令。
/// 由此得到本任务要求的那句: **任意交错之后, "我们还要不要它跑"答得出真话, 端口也不会卡住**;
/// 而"socket 到底关没关"在 `None` 之后还有一个**有界**的收敛时间 —— 这句话的边界就是上面两条。
/// (`ServerStatus::running` 就是 `port.is_some()`, 见 `running_port`。)
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

/// **所有会写 `server-config.json` 的命令**的串行闸门 —— Fix 2 加的那一把, Task 10 把
/// 范围从"开关命令"放宽到"整份配置的每一条写者"。**与 `START_LOCK` 是两件事**:
/// `START_LOCK` 只保证"两次开启不会各绑一个端口"; 这一把保证"两条改配置的命令不交错"。
///
/// ── 它取消掉的第一个窗(Fix 2) ─────────────────────────────────────────────
/// `prev = read_enabled(st)` … `stop()` … 落盘 … 失败就把 `prev` 写回去。`prev` 是**进入时**
/// 的快照, 而回滚在它之后、隔着一整个 `stop()` 与一次文件写才发生。这段时间里任何人成功改了
/// 开关**并且把盘写成功**, 都会被那句回滚覆盖 —— 复审用一个并发成功的 `set_enabled(true)`
/// 把它变成了可复现的现场: 开关显示"关"、socket 却在监听、理由里还写着"已停止监听",
/// 而下次开机照着盘上那一份**会**监听。
///
/// ── 它取消掉的第二个窗(Task 10 的残留缺陷) ────────────────────────────────
/// 同样的一类谎, 只是换了字段: `rotate_server_token` / `add_share` / `remove_share` 原先
/// 直接 `persist(&app, &state)?` —— 落盘失败时**内存已经改了、盘上还是旧的**, 于是界面显示
/// 一枚盘上不存在的 token / 一个重启后就消失的共享根("看起来改了, 重启后没改")。
/// 修法与开关那条完全一样(按盘对齐), 而**按盘对齐只有在"读盘 → 对齐"之间没有别的写者
/// 成功落盘时才是对的** —— 所以这三条命令和开关必须共用这一把锁, 而不是各拿一把。
///
/// 为什么必须是 `tokio::sync::Mutex` 而不是 std: 临界区里要 `await` `stop()`/`start()`
/// (后者拿 `START_LOCK`、还要 bind), 而 std 守卫不是 `Send` —— 那会让 Tauri 命令的 future
/// 变成非 `Send`, 直接编译不过。锁序固定为 `CONFIG_WRITE_LOCK → START_LOCK → LIFECYCLE`:
/// 没有任何一条路径在持有后面两把时回头拿它, 所以不会死锁(`boot` 只走 `START_LOCK`)。
static CONFIG_WRITE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 上一次**生命周期失败**的原因(start 失败, 或"关掉了但设置没能保存")。只为一件事:
/// 让界面能给出**可见的理由**(见 `ServerStatus::last_error`)。
///
/// 由 `stop()` 清空 —— 用户已经亲手把服务关了, 还挂着一句上次启动失败的理由是噪音,
/// 也会让"关了但它还在报错"看起来像没关掉。
static LAST_ERROR: StdMutex<Option<String>> = StdMutex::new(None);

/// 记一条**用户可见**的失败理由。**中毒也照用**(与 `lifecycle()` 同一条纪律)。
///
/// 为什么不能写成 `if let Ok(...)`(本轮 minor ①): 那样别处一次 panic 就会让这个槽**永远
/// 写不进去**, 于是"服务起不来"这件事对用户变成**静默**的 —— 界面上一句理由都没有,
/// 比说错更糟(用户连"该重试还是该改端口"都无从判断)。这个槽里放的是一句给人看的话,
/// 不是"可能被写坏的不变量", 拿得起就该接着用。
fn record_start_error(msg: String) {
    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = Some(msg);
}

fn clear_start_error() {
    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

fn last_start_error() -> Option<String> {
    LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

// ── M4′ Task 2 · 公用网络防护的状态 ─────────────────────────────────────────
//
// 三件东西: ①一份探测结果(测试可注入); ②那道拒绝规则; ③"用户确认过"这条记录。
// 探测的**实现**全在 `network.rs`(纯数据 + Win32), 这里只做"什么时候问它、问了怎么办"。

/// "公用网络风险"最近一次被用户**显式确认**的时刻(unix 秒)。
///
/// 与 `LAST_ERROR` 同一条纪律: 中毒也照用 —— 这里放的是一句给人看的事实, 不是"可能被写坏的
/// 不变量"; 一旦拿不到锁就整个丢掉, 用户会看到"确认了却什么都没记", 那正是本任务要消灭的
/// "静默放行"。
static PUBLIC_ACK: StdMutex<Option<u64>> = StdMutex::new(None);

/// 记下"用户在公用网络警告下点了确认"。**只在用户显式确认的那一次调用里被调用**
/// (见 `set_enabled` 的 `confirm_public`), 没有任何自动路径会碰它。
fn record_public_ack() {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        // 系统时钟被设到 1970 之前时给 0(不 panic、也不假装知道时刻)。
        // 界面把 0 显示成"已确认"(不带时间), 见 `settings-dialog.tsx`。
        .unwrap_or(0);
    *PUBLIC_ACK.lock().unwrap_or_else(|e| e.into_inner()) = Some(now);
}

fn public_ack_at() -> Option<u64> {
    *PUBLIC_ACK.lock().unwrap_or_else(|e| e.into_inner())
}

/// 清掉确认记录。**只在测试里用**(每个用例从干净状态出发) —— 生产里这条记录活到进程退出,
/// 那正是"每次开机都要重新确认"这个语义的实现方式。
#[cfg(test)]
fn clear_public_ack() {
    *PUBLIC_ACK.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

/// 启动前防护要用的那份本机探测结果。
///
/// 生产路径就是 `network::detect_hints()`; 测试可以注入一份假快照 —— **本机的真实网络类别
/// 没法在测试里改**(把系统网络设成"公用"会影响这台机器上别的一切, 而 NLM 的
/// `SetSimulatedProfileInfo` 是动系统状态的东西, 更不该在测试里碰), 所以"公用网络"这种
/// 现场只能靠注入造出来。注入点与 `persist_config` 那个一样: 只在 `#[cfg(test)]` 存在。
fn current_hints() -> network::NetHints {
    #[cfg(test)]
    if let Some(h) = seams::hints_override() {
        return h;
    }
    network::detect_hints()
}

/// **开启路径上的那道防护**。`Some(理由)` = 这一次不许开; `None` = 可以开。
///
/// 判定本身在 `network::should_refuse_to_start`, 两条规则都是 fail-closed: `Public` 要确认,
/// **读不出类别(`Unknown`)同样要确认** —— 只有**读到** `Private`/域才允许静默开启(见那里的
/// Fix 1 说明: 共享 WiFi 发的通常就是私有租约, 所以"没有非局域网地址"证明不了可信)。
///
/// `confirm_public` 是**这一次**调用带来的用户确认(不是历史记录 —— 见 `public_ack_at`)。
/// 它在用户确认后记一次 `PUBLIC_ACK`, 于是"这次开启有人确认过"进得了状态。
///
/// ⚠️ 会被调用**两次**(拿配置写锁之前一次, 落盘之后、`start` 之前再一次 —— minor ③):
/// 第二次是为了让"落盘期间网络变了"漏不过去。两次都带同一个 `confirm_public`, 所以重复调用
/// 只是把确认时刻更新到最近一次, 判定结果与记录语义都不变。
///
/// 关闭路径**绝不**经过这里: 用户必须永远关得掉服务(与本模块其它纪律同源)。
fn guard_refusal(confirm_public: bool) -> Option<String> {
    match network::should_refuse_to_start(&current_hints()) {
        None => None,
        Some(_) if confirm_public => {
            record_public_ack();
            None
        }
        Some(reason) => Some(reason),
    }
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
///
/// ⚠️ **M4′ Task 2 之后, 别再从别的地方直接调它**: 启动前的公用网络防护长在
/// `set_enabled`(用户开关)与 `boot`(开机自动拉起)上, 而这两条都在这条原语**之上**。
/// 新增一条"自己想开服务"的路时, 请走那两个入口 —— 否则新路会**绕过**规格 §4.3 的防护
/// (这正是本模块头部记过的"两个安全属性不同的入口 = 迟早有人挂错那个", 同源)。
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
                    // M4′ Task 3: `GET /api/v1/clients` 要回答"谁在连我", 而客户端身份在 HTTP
                    // 这一层**只有来源地址**这一项 —— 它只能由 `ConnectInfo` 提供。
                    // `axum::serve(listener, app)` 不会往请求里塞它, 于是路由层拿到的永远是
                    // `None`(客户端列表从此只剩路径与时间)。所以这里换成"带连接信息"的那一版。
                    //
                    // **不用 `X-Forwarded-For`**: 本进程直连、没有反代, 那个头可以由任何客户端
                    // 随便写。信它等于让日志里出现**伪造**的来源 IP —— 那比"不知道 IP"更糟,
                    // 因为它会骗人(而 §5.3 那条"存在性预言机"式的取舍在这里不成立: 前者不骗人)。
                    //
                    // 这一步只是把 `SocketAddr` 塞进 request extensions, 不改变任何路由/鉴权
                    // 行为, 也不引入 panic 点。
                    let app = app.into_make_service_with_connect_info::<std::net::SocketAddr>();
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

// ── M4′ Task 1 · 配对二维码(桌面端生成) ──────────────────────────────────────
//
// 为什么要有它: 配对码是 64 位十六进制, 手输不现实, 而"手机 × 电脑"之间没有复制粘贴。
// 二维码是唯一顺手的通道(规格 §4.2 第 3 条)。
//
// ── 为什么是 SVG 而不是 PNG(这是与"随便挑一种"不同的一处决定) ───────────────
// 前端把它直接塞进 DOM 即可, 不必引 JS 二维码库、也不必把 PNG 放进 asset scope ——
// 那条路径在本项目是**收紧为空**的(交接文档 §4.6), 为一张配对图去放宽它等于为了省几行
// 前端代码去动一条安全边界。
//
// ── 没有私有地址时怎么办(**这是一个明确决定, 不是遗漏**) ─────────────────────
// [`pairing_of`] 返回 `None` —— **不生成二维码**。既不回落到公网地址, 也不回落到
// `127.0.0.1`(那是 `primary_url` 的兜底, 只用于在桌面上显示一个"能自测"的地址):
//   · 二维码里装一个公网地址 = 把手机的请求指向门外, 而本设计明确不做公网暴露(§4.3);
//   · 装 `127.0.0.1` 更糟 —— 手机扫到之后连的是**手机自己**, 用户得到的是一句莫名其妙的
//     "连接被拒绝", 而真正的原因是电脑此刻没有局域网地址。这类"看起来能用、其实指向别处"
//     的配对信息正是本项目一直在消灭的东西。
// 于是: 面板上**没有二维码**, 同时把原因说出来(局域网列表本来就空 → `server.lanEmpty`;
// 有地址但还没有配对码 → `server.qrNoToken`)。文本形式的地址与配对码**照旧全部保留**
// (M4′ Task 1 Step 2: 扫码是增强, 手输是退路)。

/// 二维码里装的那段 JSON。**手机扫到的就是这一个字符串** —— 桌面端与手机端共用它,
/// 所以它只有这一处构造(`pairing_of` 连 SVG 带 JSON 一起交出去, 不各写一份)。
///
/// 形状是 M4′ Task 1 定的 `{"url":"http://…","token":"…"}`(手机端 `PairingPayload` 两个字段
/// 都是必填)。键序无所谓: 手机用 kotlinx.serialization 按字段名读, 不依赖顺序。
pub fn pairing_payload(url: &str, token: &str) -> String {
    json!({ "url": url, "token": token }).to_string()
}

/// 把任意一段文本编成二维码 SVG。**失败只回 `Err`**(不 panic) —— 这条函数在"打开设置面板"
/// 那条路上可达, 而那条路不许把桌面端弄出任何异常。
///
/// `min_dimensions` 给一个下限(而不是固定尺寸): 短内容不会被放大成一堆马赛克, 长内容也能
/// 保证扫码距离。颜色写死黑白 —— 二维码的对比度是它的**功能**, 不该跟着主题走。
///
/// 出来的字符串**以 `<svg` 开头**(见 [`strip_xml_prolog`])。
fn qr_svg_of(text: &str) -> Result<String, String> {
    let code = qrcode::QrCode::new(text.as_bytes()).map_err(|e| e.to_string())?;
    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(220, 220)
        // 静区(quiet zone): 二维码规范要求四周留白, 少一圈白边扫码率会明显下降 —— 而这
        // 张图是给**另一台设备的摄像头**看的, 不是给人看的。默认是关的, 必须显式打开。
        .quiet_zone(true)
        .dark_color(qrcode::render::svg::Color("#000000"))
        .light_color(qrcode::render::svg::Color("#ffffff"))
        .build();
    Ok(strip_xml_prolog(&svg))
}

/// 去掉开头那句 `<?xml version="1.0" standalone="yes"?>`。
///
/// 为什么值得单独一步(qrcode 0.14 的 SVG 渲染器确实会带上它): 这段字符串的归宿是
/// `dangerouslySetInnerHTML`(**M4′ Task 1 选 SVG 就是为了这个**), 而 HTML 解析器把
/// `<?xml …?>` 当成一个 **bogus comment**, 于是面板那格子里会多出一个注释节点。更要紧的是
/// 这条功能的契约本来就是"返回 `<svg …</svg>`"(见 `pairing_qr_svg`), 带着一段 XML 声明
/// 出去会让每个调用方都得自己再剥一次。
///
/// 找不到 `?>` 时**原样返回**: 宁可多一句声明, 也不许把图切坏(这条函数在"打开设置面板"
/// 那条路上可达, 不许 panic —— 所以这里没有 `unwrap`/切片越界)。
fn strip_xml_prolog(svg: &str) -> String {
    if let Some(rest) = svg.strip_prefix("<?xml") {
        if let Some(end) = rest.find("?>") {
            return rest[end + 2..].trim_start().to_string();
        }
    }
    svg.to_string()
}

/// 把"地址 + 配对码"编成二维码 SVG。**M4′ Task 1 Step 1 点名的那条接口, 逐字保留。**
pub fn pairing_qr_svg(url: &str, token: &str) -> Result<String, String> {
    qr_svg_of(&pairing_payload(url, token))
}

/// 设置面板要的那一份。三个字段都是**同一段内容**的三种呈现, 一起交出去是为了让
/// "二维码里到底装了什么"可被测试直接断言(`payload` 就是喂给编码器的那个字节串)。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingQr {
    /// `<svg …></svg>` 源码, 前端直接塞进 DOM。
    pub svg: String,
    /// 二维码里装的地址(`http://<私有地址>:<端口>`)。
    pub url: String,
    /// 二维码里装的那段 JSON —— 与 `svg` 逐字同源, 面板不用它, 但协议测试用它。
    pub payload: String,
}

/// 从一份网络探测快照挑一个地址, 生成二维码。**没有可用的私有地址、或 token 不可用 = `None`**
/// (见上面"没有私有地址时怎么办")。
///
/// 抽成纯函数(不碰 OS、不碰配置)是刻意的: "该给哪个地址"与"什么时候不该给二维码"是这条功能里
/// **唯二**值得争论的规则, 而纯函数才能用假数据把两种现场都真的跑一遍。
pub fn pairing_of(hints: &network::NetHints, port: u16, token: &str) -> Option<PairingQr> {
    // ① token 不可用就没有二维码。编出来的一定配不上对(鉴权层面对空/坏 token 一律 401) ——
    //    那正是"看起来能用、实际用不了"的假。真实可达: 配置里 token 为空(默认配置), 或
    //    手改配置把它改坏了。
    if !config::token_is_valid(token) {
        return None;
    }
    // ② 地址必须**真的是私有网段**。这里用 `network::is_private_v4` 再判一次, 而不是"相信
    //    `hints.lan` 的构造": 二维码是用户唯一会照着扫的东西, 它装进一个公网地址就等于把手机
    //    指向门外(规格 §4.2: 候选里绝不出现公网地址)。判一次的成本是几个字节比较。
    let address = hints.lan.iter().find_map(|l| {
        let a = l.address.trim();
        match a.parse::<std::net::Ipv4Addr>() {
            Ok(ip) if network::is_private_v4(ip) => Some(a.to_string()),
            _ => None,
        }
    })?;
    let url = format!("http://{}:{}", address, port);
    let payload = pairing_payload(&url, token);
    // 编码失败(理论上到不了)也只当"这次没有二维码", 绝不让面板打开这件事失败。
    let svg = qr_svg_of(&payload).ok()?;
    Some(PairingQr { svg, url, payload })
}

/// Tauri 命令用的那一版: 端口取与 [`ServerStatus::port`] **同一个口径**(在跑的端口优先,
/// 没在跑时用配置里那个), token 取配置里那一枚。
///
/// **先拿端口、再读配置**是刻意的: `start` 那条路上有一段是"持 `LIFECYCLE` 时读配置"
/// (`wants_start`), 这里反过来"持配置读锁时拿 `LIFECYCLE`"就会凑成一对 ABBA 死锁。
/// 锁序与 `start` 保持一致。
pub fn pairing_qr(st: &ServerState, hints: &network::NetHints) -> Option<PairingQr> {
    let port = running_port();
    let cfg = st.config.read().ok()?;
    pairing_of(hints, port.unwrap_or(cfg.port), &cfg.token)
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

/// 一次配置写入**改过哪些字段** —— 落盘失败后"按盘对齐"只许撤这些字段(见
/// `realign_from_disk`)。
///
/// 为什么要一个集合而不是一个 bool: 撤多了会把并发那条命令已经落盘的成果抹掉(例如
/// `add_share` 刚写进去的共享根), 撤少了就留下"界面变了、重启后没了"的那个谎。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChangedFields {
    pub enabled: bool,
    pub token: bool,
    pub shares: bool,
    /// M4′ Task 3: `set_server_read_only` 改过的字段。
    pub read_only: bool,
}

impl ChangedFields {
    /// 开关命令改过的字段: `enabled` 一定算; token 只在这次**真的**换了一枚时才算
    /// (没换却撤 token, 会把并发换上的那枚抹掉)。
    pub fn switch(token_replaced: bool) -> Self {
        Self { enabled: true, token: token_replaced, shares: false, read_only: false }
    }

    /// `rotate_server_token` 改过的字段。
    pub const TOKEN: Self =
        Self { enabled: false, token: true, shares: false, read_only: false };

    /// `add_share` / `remove_share` 改过的字段。
    pub const SHARES: Self =
        Self { enabled: false, token: false, shares: true, read_only: false };

    /// `set_server_read_only` 改过的字段(M4′ Task 3)。
    pub const READ_ONLY: Self =
        Self { enabled: false, token: false, shares: false, read_only: true };
}

/// 落盘; **失败就按盘对齐**(`changed` 里的字段)并回一句给用户看的理由。
///
/// 这是四条"会写 `server-config.json`"的命令共用的那一条纪律(Task 8 为开关立的规矩,
/// Task 10 把它铺到 `rotate_server_token` / `add_share` / `remove_share`)。原先这三条用的是
/// `persist(&app, &state)?` —— 那个 `?` 让"内存改了、盘上没改"直接漏给用户: 界面上一枚新
/// token(手机照着它配对, 重启后 401)、或一个共享根(重启后消失), 而**没有任何迹象**说明
/// 它没保存下来。`save_config`/`persist_config` 本身不该被判罪 —— 判罪的是"失败了却不对齐"。
///
/// 调用方**必须**已经持有 `CONFIG_WRITE_LOCK`: 按盘对齐的正确性建立在"读盘与对齐之间没有
/// 别的写者成功落盘"之上。
pub fn persist_or_realign(
    st: &ServerState,
    app_data_dir: &Path,
    changed: ChangedFields,
    what: &str,
) -> Result<(), String> {
    // 测试专用窗口(生产构建里这一行不存在, 也不留分支): 与 `set_enabled` 里那一行同一个用途 ——
    // "另一条写配置的命令在这个瞬间插进来"必须是**可观测、可复现**的时刻, 而不是靠 sleep 猜。
    #[cfg(test)]
    seams::fire_window(st, app_data_dir);
    match persist_config(st, app_data_dir) {
        Ok(()) => Ok(()),
        Err(e) => {
            realign_from_disk(st, app_data_dir, changed);
            Err(config_persist_failure_reason(what, e))
        }
    }
}

/// 非开关命令落盘失败时给界面的那句理由。
///
/// 与 `persist_failure_reason` 同一条纪律: **按实际留下的状态现拼**。这里能说的实话只有
/// 一句 —— 内存刚被对齐到盘上, 所以界面显示的已经等于"重启后会读到的那一份"。
fn config_persist_failure_reason(what: &str, err: String) -> String {
    format!(
        "{}没能保存 —— {}。界面上显示的已回到盘上此刻真正写着的那一份(重启后仍是它)。",
        what, err
    )
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
        public_ack_at: public_ack_at(),
        read_only: cfg.read_only,
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
/// ── 落盘失败时用户看到什么(本轮 Fix 2 重定的决定) ──────────────────────────
///   · **"关"这个动作的第一保证是端口被放开**: `stop()` 无条件执行, 与落盘成败无关。
///   · **盘上那一份赢 —— 而且是"盘上此刻真正写着的那一份"**: 落盘失败意味着这一次的写入
///     一个字节都没进去, 所以内存里**被这次调用改过**的字段必须按盘对齐(见
///     `realign_from_disk`)。旧实现写回的是**进入时**读到的快照 `prev`, 而回滚发生在它
///     之后一段没有上界的时间(隔着一整个 `stop()` 与一次文件写): 这段时间里任何人成功改了
///     开关并把盘写成功, 都会被那句回滚**覆盖**。复审就是用一个并发成功的 `set_enabled(true)`
///     把它变成可复现的谎 —— 开关显示"关"、socket 却在监听、理由里还写着"已停止监听"。
///   · **理由按实际留下的状态现拼**: 见 `persist_failure_reason` —— 先读"撤回之后"的真实值
///     与"此刻到底有没有在监听", 再拼句子。不许硬编码"下次启动仍会尝试开启"这类承诺,
///     因为盘上的值恰恰是这条路上唯一不确定的东西。
///   · **整条命令串行**: `CONFIG_WRITE_LOCK` 让两条开关命令互斥, 于是那个窗在 `set_enabled`
///     之间**不再存在**; 按盘对齐则保证连不经过这条路的写者(那几条直接 `persist` 的命令)
///     造成的盘面变化也不会被回滚覆盖。
///   · 于是状态可能是 `enabled: true, running: false, lastError: "…没能保存…"`: 服务真的已经
///     停止监听(端口放开了), 但"关掉"这个设置没保存下来, 下次启动仍会尝试开启。界面必须把
///     `lastError` 显示出来, 否则用户会以为自己只是没点中。
///   · 开启路径**对称**: 落盘失败同样把开关(以及这一次换过的 token)按盘对齐, 而且保证一个
///     监听都没起 —— 顺序是"先落盘、后 start", 见下。
///
/// ── M4′ Task 2: `confirm_public`(公用网络防护) ─────────────────────────────
/// 开启之前先过 `guard_refusal`: 当前网络被 Windows 标为**公用**, **或者类别根本读不出来**
/// (`network::should_refuse_to_start` 的两条规则, 都是 fail-closed)时, **没有
/// `confirm_public = true` 就直接 `Err` 返回, 一个字节都不动**(不写内存、不落盘、不绑端口、
/// 不记 `enabled`)。理由同时被记进 `LAST_ERROR`, 于是界面看得见"为什么这次没开",
/// 并给出一个二次确认入口。
///
/// 这道判定会被做**两次**(minor ③): 拿配置写锁之前一次(上面这段), 以及**落盘之后、`start`
/// 之前**再一次 —— 后者是给"落盘期间用户换了 WiFi"那个窗口用的(见下面那一段的注释)。
///
/// `confirm_public` 是**这一次调用**带来的用户确认(面板上那一下点击), 不是历史记录:
/// `ServerStatus::public_ack_at` 只是**记录**(供界面显示"这次是有人确认过的"),
/// **不参与判定** —— 所以"上次确认过"永远不会变成"这次自动放行"。
///
/// 关闭路径(`enabled == false`)**绝不**过这道闸: 无论当前是什么网络, 用户必须永远关得掉它。
pub async fn set_enabled(
    st: &ServerState,
    app_data_dir: &Path,
    enabled: bool,
    confirm_public: bool,
) -> Result<ServerStatus, String> {
    // 防护放在拿 `CONFIG_WRITE_LOCK` **之前**: 它不碰配置, 也不该占着那条串行闸门去问网络。
    // (被拒时直接返回: 开关保持原样 —— 通常就是 `false`, 也就是"这次没开成"。)
    if enabled {
        if let Some(reason) = guard_refusal(confirm_public) {
            // 用户可见的理由不许静默丢掉: 与启动失败同一条路, 界面从 `lastError` 读它。
            record_start_error(reason.clone());
            return Err(reason);
        }
    }

    // **整条命令串行**(见 `CONFIG_WRITE_LOCK`)。拿在手上直到本次返回 —— 包括 `start().await`:
    // 那个 await 正是旧实现里"窗口"能开到没有上界的原因。
    let _serialized = CONFIG_WRITE_LOCK.lock().await;

    if enabled {
        // 顺序是刻意的: ①先把 token 备好(**只在内存里**; 随机数不可用 → 直接 Err, 配置一个
        // 字节都不动); ②再打开开关; ③**一次**落盘把这两件事一起写下去; ④最后尽力起服务。
        // 任何一步失败都只是 Err。
        //
        // ③ 失败时**不**留着这次调用改过的值(开关、以及这次换过的 token) —— 按盘上那一份
        // 对齐。此刻**一个监听都没起**(start 在落盘之后), 所以这里没有端口要放开 ——
        // 与关闭路径的"无条件 stop"对应, 那边才是会留下端口的一侧。
        // ④ 失败时**不**回滚开关: 用户的选择照旧留着(下次开机还会再试一次), 而服务并没有
        // 在监听 —— `running` 与 `enabled` 在状态里是分开的, 界面据实显示即可。
        let token_replaced = token_ready_in_memory(st)?;
        write_enabled(st, true)?;
        // 测试专用窗口(生产构建里这一行不存在, 也不留分支): 复审复现那个谎用的插入点。
        #[cfg(test)]
        seams::fire_window(st, app_data_dir);
        if let Err(e) = persist_config(st, app_data_dir) {
            realign_from_disk(st, app_data_dir, ChangedFields::switch(token_replaced));
            let msg = persist_failure_reason(st, "服务未启动", e);
            record_start_error(msg.clone());
            return Err(msg);
        }
        // ── M4′ Task 2 · minor ③: **起监听之前再问一次网络** ─────────────────────
        // 上面那一次判定发生在拿 `CONFIG_WRITE_LOCK` **之前**, 而落盘(dirs 创建 + 写文件 +
        // 可能的杀软扫描)可能不是零耗时 —— 用户完全可能在这段时间里换到咖啡厅的 WiFi
        // (或者 netprofm 在这段时间里被停掉)。类别是一次**快照**, 拿旧快照去开端口, 正是
        // "WiFi 切换从缝里漏过去"。所以这里就地把判定重做一遍, 而且位置**紧贴** `start()`。
        //
        // 成本: `current_hints()` 会真的做一次网卡枚举 + 一次 NLM COM 查询(探针实测约 19 ms),
        // 而且是**同步**的 —— 这一段没有 `await`, 所以它不会把锁交出去(CONFIG_WRITE_LOCK 不横跨
        // await 是本模块的硬纪律); 代价是这条命令多花约 19 ms, 以及并发的 stop 最多被推迟同样
        // 的时间。这个代价换的是"落盘期间换了网络也绝不会静默开起来", 值。
        //
        // 被拒时**不回滚盘上那一次写入**: 用户的"我要开服务"这个选择是真的(与下面 start 失败
        // 那条路同一条纪律 —— ④ 失败时不回滚开关), 而"它会不会真的起来"由下一次判定说了算;
        // 自动启动那条路自己也会过同一道闸(`boot_with`), 所以盘上写着"开"**不等于**会静默监听。
        if let Some(reason) = guard_refusal(confirm_public) {
            record_start_error(reason.clone());
            return Err(reason);
        }
        match start(st.clone()).await {
            Ok(_) => status_of_state(st),
            Err(e) => {
                record_start_error(e.clone());
                Err(e)
            }
        }
    } else {
        // **先写内存、再停、最后落盘** —— 三步的顺序都是刻意的:
        //   ① `enabled = false` 必须**先于** `stop()`: 在途的 `start` 会在安装前读到
        //      "用户不要了" 从而自我取消(见 `start` 的安装检查)。反过来的话, 一次乱序的
        //      "先点关、再点开" 就能造出"关着却还听着"。
        //   ② `stop()` **无条件**执行: 端口必须在这一步放开, 与落盘的成败无关(Critical 1)。
        //      落盘失败这条路上, 旧代码的 `?` 会在 stop 之前提前返回 —— 于是监听一直留着,
        //      而内存已经说"关"、盘上还说"开": 用户**再也关不掉它**。
        //   ③ 落盘。失败就把内存按**盘上真正写着的那一份**对齐(盘是唯一真相), 并把理由交给界面。
        //
        // 注意①与②之间**不许**出现 `?`: 写内存失败(配置读锁中毒)也绝不早退, 否则端口
        // 就留下来了 —— "停"这个动作不许被任何别的事情挡在前面。
        let wrote = write_enabled(st, false);
        stop();
        wrote.map_err(|e| {
            let msg = format!("服务已停止监听, 但设置没能保存(内部状态不可用): {}", e);
            // 上一句 `stop()` 刚把旧理由清掉, 这里不记的话界面会看到"关掉了却什么都没说" ——
            // 与 minor ① 是同一条纪律: 用户可见的理由不许静默丢掉。
            record_start_error(msg.clone());
            msg
        })?;
        // 测试专用窗口(生产构建里这一行不存在): 就在"内存已改、服务已停"与"落盘"之间 ——
        // 那正是 `prev` 会变陈旧的那段时间。
        #[cfg(test)]
        seams::fire_window(st, app_data_dir);
        if let Err(e) = persist_config(st, app_data_dir) {
            // **不是**把进入时的那个值写回去 —— 见 `realign_from_disk`。
            realign_from_disk(st, app_data_dir, ChangedFields::switch(false));
            let msg = persist_failure_reason(st, "服务已停止监听", e);
            record_start_error(msg.clone());
            return Err(msg);
        }
        status_of_state(st)
    }
}

// ── Task 10 · 另外三条会写配置的命令 ─────────────────────────────────────────
//
// 它们原先各自长在 `lib.rs` 里, 直接 `server::persist(&app, &state)?` —— **落盘失败时内存
// 已经改了、盘上还是旧的**, 而那个 `?` 只回一句 Err: 用户看到一枚新 token(手机照着它配对,
// 重启后 401)/ 一个共享根(重启后消失), 却没有任何东西说明"它没保存下来"。这与 Task 8 修掉的
// 开关那条是**同一类谎**(界面 ≠ 重启后的现实), 所以修法也必须是同一条: 按盘对齐。
//
// 为什么把实现搬到 `server` 模块(而不是留在 `lib.rs` 里修): "落盘失败"要能被**真的走一遍**
// 就必须把目录当参数(与 `set_enabled` 同一个理由), 而 `lib.rs` 里的命令签名只有
// `AppHandle` —— 那条路只有真机上点得动, 单测够不着。搬过来之后, `lib.rs` 里只剩"把 app 数据
// 目录翻译成一个 `&Path`", 与 `set_server_enabled` 完全对称。

/// 轮换配对 token(规格 §5.2: 换完之后所有手机需重新配对)。
///
/// **失败时内存按盘对齐** —— 否则界面会显示一枚盘上根本不存在的 token: 用户照它抄给手机,
/// 配对当场成功(服务端此刻读的是内存), **重启后立刻 401**, 而且找不出原因。
pub async fn rotate_token(st: &ServerState, app_data_dir: &Path) -> Result<ServerStatus, String> {
    // 整条命令串行(见 `CONFIG_WRITE_LOCK`): "读盘对齐"必须与其它写者互斥。
    let _serialized = CONFIG_WRITE_LOCK.lock().await;

    // 用可失败的那一版: 随机数不可用只该变成一句 `Err`, 不该 panic(见 `auth::try_new_token`)。
    let fresh = auth::try_new_token()?;
    st.config.write().map_err(|e| e.to_string())?.token = fresh;
    persist_or_realign(st, app_data_dir, ChangedFields::TOKEN, "新的配对码")?;
    status_of_state(st)
}

/// 加入一个共享根。**只接受已存在的目录**(空 = 谁都访问不了, 是安全默认值)。
///
/// 落盘失败时内存里的 `shares` 按盘对齐(否则用户会以为目录加上了, 重启后发现没了 ——
/// 而 `shares` 空 = 服务对所有路径都回"无权限", 那是一个看起来像"服务坏了"的现场)。
pub async fn add_share(
    st: &ServerState,
    app_data_dir: &Path,
    path: &str,
) -> Result<Vec<String>, String> {
    let _serialized = CONFIG_WRITE_LOCK.lock().await;

    let p = PathBuf::from(path);
    if !p.is_dir() {
        return Err("不是目录".into());
    }
    {
        let mut cfg = st.config.write().map_err(|e| e.to_string())?;
        if !cfg.shares.iter().any(|s| s == &p) {
            cfg.shares.push(p);
        }
    }
    persist_or_realign(st, app_data_dir, ChangedFields::SHARES, "共享目录")?;
    share_strings(st)
}

/// 移除一个共享根(按用户看到的那个字符串比较)。
pub async fn remove_share(
    st: &ServerState,
    app_data_dir: &Path,
    path: &str,
) -> Result<Vec<String>, String> {
    let _serialized = CONFIG_WRITE_LOCK.lock().await;

    {
        let mut cfg = st.config.write().map_err(|e| e.to_string())?;
        cfg.shares.retain(|s| s.to_string_lossy() != path);
    }
    persist_or_realign(st, app_data_dir, ChangedFields::SHARES, "共享目录")?;
    share_strings(st)
}

/// **开/关只读模式**(M4′ Task 3)。只改配置里的 `read_only` 并落盘 —— **不**碰服务生命周期:
/// 只读是一条"这条路此刻怎么走"的规则, 由路由中间件在**每一次请求**上现读配置(`routes.rs`
/// 的 `refuse_writes_in_read_only`), 所以开与关都是**立刻生效、不用重启服务**的。这也是它
/// 不在这里 `stop()`/`start()` 的理由: 为了一个拦截规则去重启监听, 会让正在下载缩略图的手机
/// 无谓地断一次线。
///
/// 落盘失败时按盘对齐(`persist_or_realign`): 与另外四条会写配置的命令同一条纪律 ——
/// 否则面板显示"已开只读"、重启后却是可写, 用户会以为那条开关是坏的。
pub async fn set_read_only(
    st: &ServerState,
    app_data_dir: &Path,
    read_only: bool,
) -> Result<ServerStatus, String> {
    // 整条命令串行(见 `CONFIG_WRITE_LOCK`): "读盘对齐"必须与其它写者互斥。
    // 临界区里**没有 `await`** —— 这条函数只做一次内存写与一次文件写。
    let _serialized = CONFIG_WRITE_LOCK.lock().await;

    st.config.write().map_err(|e| e.to_string())?.read_only = read_only;
    persist_or_realign(st, app_data_dir, ChangedFields::READ_ONLY, "只读模式")?;
    status_of_state(st)
}

/// 读内存里的开关。**读不到(锁中毒)或 false**。
///
/// 现在的用途只有一个: 落盘失败之后**给界面拼那句理由**(`persist_failure_reason`) —— 那时
/// 内存刚刚按盘对齐过, 所以读到的就是盘上那一份。它**不再**被用来做回滚: 旧实现拿它当
/// "进入时的快照"写回去, 而在那段时间里盘可能已经被别人改成别的值了(见 `realign_from_disk`)。
/// 读不到就当作没开, 方向仍然是 fail-closed: 绝不会把服务说成"开着"。
fn read_enabled(st: &ServerState) -> bool {
    st.config.read().map(|c| c.enabled).unwrap_or(false)
}

/// 写内存里的开关。
fn write_enabled(st: &ServerState, v: bool) -> Result<(), String> {
    st.config.write().map_err(|e| e.to_string())?.enabled = v;
    Ok(())
}

/// 落盘失败之后, 把**这次调用改过的字段**重新对齐到**盘上此刻真正写着的那一份**。
///
/// ── 为什么不是"把进入时的那个值写回去"(`prev`) ──────────────────────────────
/// `prev` 是进入时的快照, 而回滚发生在它之后一段**没有上界**的时间(中间隔着一整个
/// `stop()`/`start()` 与一次文件写)。任何人在这段时间里成功改了开关**并且把盘写成功**,
/// 都会被那句回滚覆盖 —— 复审复现的正是这一下: 开关显示"关"(`prev` 是关闭命令进入时的
/// `false`)、socket 却在监听、理由里还写着"已停止监听", 而盘上是"开", 下次开机照旧监听。
///
/// 盘才是唯一真相 —— **下次开机读的就是它**。所以这里按盘对齐, 而不是按任何内存快照。
/// (`CONFIG_WRITE_LOCK` 已经把四条会写配置的命令串起来了, 所以"窗口内被另一条命令插队"在今天
/// 构造不出来; 这一层守的是窗口外的写者: 启动路径上的 `ensure_token` 自己那次落盘, 以及
/// "进程里还有别人在改这份配置"这一整类情况。两道一起才是'盘赢'。)
///
/// 只撤**这次调用动过的**字段(`changed` = 这次改了哪些; 见 `ChangedFields`)。撤多了会把
/// 并发那条命令已经落盘的成果一起抹掉(例如 `add_share` 刚写进去的共享根)。
///
/// 调用方**必须**已经持有 `CONFIG_WRITE_LOCK`(见 `persist_or_realign`)。
fn realign_from_disk(st: &ServerState, app_data_dir: &Path, changed: ChangedFields) {
    let disk = config::ServerConfig::load(app_data_dir);
    // 配置锁中毒也照用(`lifecycle()` 同一条纪律): 这里要做的恰恰是**把话说回真话**,
    // 拿不到锁就放弃会把界面停在那个假值上 —— 那正是本轮要消灭的东西。
    let mut cfg = st.config.write().unwrap_or_else(|e| e.into_inner());
    if changed.enabled {
        cfg.enabled = disk.enabled;
    }
    if changed.token {
        cfg.token = disk.token;
    }
    if changed.shares {
        cfg.shares = disk.shares;
    }
    if changed.read_only {
        cfg.read_only = disk.read_only;
    }
}

/// 落盘失败之后给界面的那句理由 —— **按实际留下的状态现拼**, 不许按预期写死。
///
/// 旧文案把"盘上那一份是开"当成必然, 硬编码成"…下次启动仍会尝试开启; 界面里的开关会回到
/// '开'"。而盘上的值恰恰是这条路上唯一不确定的东西(正是它让回滚走岔了): 一次并发成功的
/// 落盘之后内存里是 `false`, 理由却还在说"开关会回到开" —— 用户看到的是一句与界面自相矛盾
/// 的话。所以这里先读**撤回之后**的真实状态(内存此刻已等于盘上), 以及"此刻到底有没有在
/// 监听", 再拼句子。
///
/// `not_running` 是"这一步本来想让服务处于什么状态"的措辞("服务未启动" / "服务已停止监听"),
/// 只在**确实没有监听**时才会被用上; 一旦发现还有一台在监听, 无论哪条路径都如实说那一台。
fn persist_failure_reason(st: &ServerState, not_running: &str, err: String) -> String {
    let listening = match running_port() {
        Some(p) => format!("服务此刻仍在 {} 上监听", p),
        None => not_running.to_string(),
    };
    let on_disk = read_enabled(st);
    format!(
        "{}: 设置没能保存 —— {}。盘上的开关仍是'{}'({})。",
        listening,
        err,
        if on_disk { "开" } else { "关" },
        if on_disk { "下次启动仍会尝试开启" } else { "下次启动不会开启" }
    )
}

/// 落盘的**唯一入口**。生产里就是 `save_config` 的一层薄包装 —— **不含任何分支**。
#[cfg(not(test))]
fn persist_config(st: &ServerState, app_data_dir: &Path) -> Result<(), String> {
    save_config(st, app_data_dir)
}

/// 测试构建里的同一个入口: 多一条"这一次写失败"的一次性注入。
///
/// 为什么非要有这个注入点: 复审复现那个谎需要**不对称**的失败 —— 关闭自己的那次写失败,
/// 而窗口里另一条命令的写成功(真实的成因是杀软/OneDrive/备份恰好锁住了刚写完的
/// `server-config.json`)。真文件系统上这两件事的先后不可控, 靠它才能把交错变成确定性的。
#[cfg(test)]
fn persist_config(st: &ServerState, app_data_dir: &Path) -> Result<(), String> {
    match seams::take_persist_failure() {
        Some(e) => Err(e),
        None => save_config(st, app_data_dir),
    }
}

/// **只在测试构建里存在的注入点。** 生产构建里这个模块整个不存在, 那些调用点也被
/// `#[cfg(test)]` 抹掉 —— 所以**没有给生产加任何分支, 也没有多一个生产分支条件**。
#[cfg(test)]
mod seams {
    use super::*;

    /// 一次性的"窗口"钩子: 在 `set_enabled` 落盘之前的那一瞬间执行。
    pub(super) type WindowHook = Box<dyn FnOnce(&ServerState, &Path) + Send>;

    static WINDOW: StdMutex<Option<WindowHook>> = StdMutex::new(None);
    /// 一次性注入的落盘失败(模拟"这个文件此刻被别的程序锁着")。
    static PERSIST_FAILURE: StdMutex<Option<String>> = StdMutex::new(None);
    /// 注入的"本机网络探测结果"(见 `current_hints`)。**粘性**: 一个用例里可能要走好几步
    /// (先被拒、再确认后开启), 所以它是"读到就一直在", 由 `clear()` 清掉 —— 与 `WINDOW` 同款,
    /// 而不是 `take_persist_failure` 那种一次性。
    static HINTS: StdMutex<Option<network::NetHints>> = StdMutex::new(None);

    pub(super) fn set_window(hook: WindowHook) {
        *WINDOW.lock().unwrap_or_else(|e| e.into_inner()) = Some(hook);
    }

    /// 取走并执行窗口钩子。**先 `take` 再调用**(guard 在那条语句结束时就已经释放) ——
    /// 否则钩子里再碰一次这个槽就会自己死锁。
    pub(super) fn fire_window(st: &ServerState, app_data_dir: &Path) {
        let hook = WINDOW.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(h) = hook {
            h(st, app_data_dir);
        }
    }

    pub(super) fn fail_next_persist(msg: &str) {
        *PERSIST_FAILURE.lock().unwrap_or_else(|e| e.into_inner()) = Some(msg.to_string());
    }

    pub(super) fn take_persist_failure() -> Option<String> {
        PERSIST_FAILURE.lock().unwrap_or_else(|e| e.into_inner()).take()
    }

    pub(super) fn set_hints_override(h: network::NetHints) {
        *HINTS.lock().unwrap_or_else(|e| e.into_inner()) = Some(h);
    }

    /// `None` = 没注入 → 走真实探测(`network::detect_hints`)。
    pub(super) fn hints_override() -> Option<network::NetHints> {
        HINTS.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// 把三个槽清空。用例从干净状态出发(与前一个用例中途 panic 时不把钩子带进下一个)。
    pub(super) fn clear() {
        *WINDOW.lock().unwrap_or_else(|e| e.into_inner()) = None;
        *PERSIST_FAILURE.lock().unwrap_or_else(|e| e.into_inner()) = None;
        *HINTS.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
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

/// 保证配置里有一枚可用的 token 并落盘(独立入口: "只需要一个能用的 token" 的调用方走它)。
///
/// 失败(随机数不可用 / 落盘失败)只返回 `Err` —— 调用方直接把它交给界面。
///
/// ⚠️ **`set_enabled` 刻意不用它**(本轮 minor ②): 这一条自己会落一次盘, 所以"先调它、再为
/// 别的东西落一次盘"就是两次写, 中间任何一次失败都会在盘上留下半成品。要一次写完的调用方
/// 用 `token_ready_in_memory`(只改内存), 然后自己落**一次**。
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
    // ── M4′ Task 2 · **自动启动**上的同一道防护 ────────────────────────────────
    // 位置是刻意的(在 `ensure_token` 之后、`start` 之前): token 那一步是"配置完整性", 与
    // 网络无关, 先让它把话说完; 而这里问的才是"现在到底该不该开始监听"。
    //
    // 这条路上**没有** `confirm_public` 这个口子, 而且是故意的: 开机自动拉起时**没有人**在
    // 场可以确认。于是"公用网络上它自己起来了"在结构上构造不出来 —— 用户必须在设置面板里
    // 当场点一次确认(走 `set_enabled(.., true)`)才会开始监听。
    // 失败只留一条可见理由 + Err, 桌面端照常可用(见 `boot` 的返回语义)。
    if let Some(reason) = guard_refusal(false) {
        record_start_error(reason.clone());
        return Err(reason);
    }
    match start(st).await {
        Ok(p) => Ok(Some(p)),
        Err(e) => {
            record_start_error(e.clone());
            Err(e)
        }
    }
}

/// 配置里的 token 可用吗? 读不出配置(锁中毒)按**失败**处理 —— 调用方只该往上抛 `Err`,
/// 不许在"读不出来"的时候当它可用。
fn token_is_ready(st: &ServerState) -> Result<bool, String> {
    Ok(config::token_is_valid(&st.config.read().map_err(|e| e.to_string())?.token))
}

/// **只把 token 备进内存**, 不落盘; 返回"这次是不是真的换了一枚"。
///
/// 为什么要有这一层(本轮 minor ②): `set_enabled(true)` 的旧实现先调
/// `ensure_token_persisted`(它自己落一次盘, 而那时开关还是旧的 `false`), 紧接着开关分支
/// 再落第二次 —— 于是"首次开启"(token 还没生成过)这条路上配置会被写**两遍**, 两遍之间
/// 任何一次失败都会在盘上留下"新 token + 旧开关"这种半成品。现在调用方把这次要写的东西
/// 凑齐, **一次**写下去。
fn token_ready_in_memory(st: &ServerState) -> Result<bool, String> {
    if token_is_ready(st)? {
        return Ok(false);
    }
    let fresh = auth::try_new_token()?;
    st.config.write().map_err(|e| e.to_string())?.token = fresh;
    Ok(true)
}

/// 保证服务手上有一枚**可用**的 token, 并把新生成的那一枚落盘(`boot` 走这一条)。
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
    if token_is_ready(st)? {
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
                read_only: false,
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
        // 注入点也是全局状态: 上一个用例若在装好钩子之后 panic, 钩子会活到下一个用例里
        // (那时它会在**别人**的 `set_enabled` 里开火)。与 `stop()` 同一个理由, 一起清干净。
        seams::clear();
        // "用户确认过公用网络风险"也是全局状态(见 `PUBLIC_ACK`): 不清的话, 一个用例留下的
        // 确认会让下一个用例看不到"没确认过"那个起点。
        clear_public_ack();
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
            read_only: false,
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
                read_only: false,
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
            read_only: false,
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
            read_only: false,
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
    ///
    /// `confirm_public` 原样透传: 用例要么显式确认(等于"用户在面板上点了确认"), 要么注入
    /// 一份探测结果来造出"公用网络"那个现场 —— 两条路都不依赖跑测试这台机器的真实网络。
    fn set_enabled_on_tauri_runtime(
        st: &ServerState,
        dir: &Path,
        enabled: bool,
        confirm_public: bool,
    ) -> Result<ServerStatus, String> {
        let st = st.clone();
        let dir = dir.to_path_buf();
        std::thread::spawn(move || {
            tauri::async_runtime::block_on(set_enabled(&st, &dir, enabled, confirm_public))
        })
        .join()
        .expect("set_enabled 线程不许 panic")
    }

    /// 端口此刻真的在接受连接吗?
    fn accepts_connections(port: u16) -> bool {
        std::net::TcpStream::connect(("127.0.0.1", port)).is_ok()
    }

    /// 端口在 `within` 之内接受了连接吗? **有界等待**的一版探针。
    ///
    /// 为什么必须有界(实测出来的, 不是洁癖): 这台机器上"连一个没人监听的端口"**不是**立刻
    /// 被 RST, 而是**丢包丢到约 2.05 秒才超时**(裸 connect 三次探测分别 2.047s / 2.051s /
    /// 2.051s 才返回 Err)。于是裸 connect 量不出"这台服务还开着多久" —— 它量的是**探针自己的
    /// 超时**: 一个早就关掉的端口也要 2 秒才说"连不上"。`wait_until_refused` 的语义是
    /// "最终会不会不再接受连接", 有 5/10 秒的余量, 所以那里够用; 要**量窗口**就必须给探针
    /// 自己定一个上界, 否则量出来的是探针的常数。
    fn accepting_within(port: u16, within: std::time::Duration) -> bool {
        let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
        std::net::TcpStream::connect_timeout(&addr, within).is_ok()
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
            read_only: false,
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

        let err = set_enabled_on_tauri_runtime(&st, &dir, false, false).expect_err("落盘失败必须回 Err");
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
        // ③ **盘上那一份赢 —— 而且是"盘上此刻真正写着的那一份"**(本轮 Fix 2 的核心)。
        //    断言是从盘上**读出来**的, 不是硬编码常量: 这一份夹具把配置文件名占成了目录,
        //    所以 `load()` 什么都读不到 → 回落默认(enabled=false), 而下次开机读到的正是
        //    这个默认值 —— 也就是说"盘上那一份"就是 false。
        //    (上一版把**进入时**的内存快照 `prev`(=true, 因为夹具是起着一台服务再关的)
        //    写回去, 内存会停在 true, 与本行不符: 这条当场红。这正是它从"证明"变成
        //    "靶子"的地方 —— 旧断言写死了 `enabled == true`, 而那个值恰好是 `prev`,
        //    所以它证明不了"回滚看的是盘"。)
        let on_disk = config::ServerConfig::load(&dir).enabled;
        assert_eq!(status.enabled, on_disk, "落盘失败后内存里的开关必须等于盘上那一份");
        assert!(!status.enabled, "这份夹具里盘上读不出配置 → 盘上那一份是默认的 false");
        assert!(
            status.last_error.as_deref().unwrap_or("").contains("没能保存"),
            "界面必须能拿到一句可见的理由: {:?}",
            status.last_error
        );
        // ④ 理由里那句"盘上的开关仍是…"必须与内存/盘上的**实际值**一致 —— 用户就是靠它
        //    判断"下次开机还会不会开"。硬编码的承诺(旧文案写死"会回到开")在这里是谎。
        assert!(
            status.last_error.as_deref().unwrap_or("").contains("盘上的开关仍是'关'"),
            "理由必须描述实际留下的状态: {:?}",
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
            read_only: false,
            enabled: false,
            port: config::DEFAULT_PORT,
            token: "b".repeat(64), // token 已可用 → `token_ready_in_memory` 不换 token
            shares: Vec::new(),
        }
        .into_state();

        let err = set_enabled_on_tauri_runtime(&st, &dir, true, true).expect_err("落盘失败必须回 Err");
        assert!(err.contains("没能保存"), "理由要能读: {}", err);
        assert_eq!(running_port(), None, "落盘失败时一个监听都不许起");
        let status = status_of_state(&st).unwrap();
        // 断言从盘上读出来(不是硬编码 false): 夹具里配置文件名被占成了目录, 于是 `load()`
        // 回落默认 —— 盘上那一份是 false, 内存也必须停在 false。
        let on_disk = config::ServerConfig::load(&dir).enabled;
        assert_eq!(status.enabled, on_disk, "内存必须按盘上那一份对齐");
        assert!(!status.enabled, "盘上读不到配置 → 盘上那一份是默认的 false");
        assert!(!status.running);
        // 开启路径的理由也必须是"实际留下的状态": 这里服务确实一台都没起。
        assert!(
            status.last_error.as_deref().unwrap_or("").contains("服务未启动"),
            "理由要说明服务没有起来: {:?}",
            status.last_error
        );
    }

    /// **minor ② 的钉子: "首次开启"(token 还没有)这条路上配置只许写一次。**
    ///
    /// 旧实现写两次: 先 `ensure_token_persisted`(把新 token 与**旧的** `enabled=false` 一起
    /// 写进盘), 再为开关写第二次。两次之间的任何失败都会在盘上留下"新 token + 旧开关"这种
    /// 半成品 —— 用户刚点了开启、界面以为在开着, 而下次开机读到的是一份"关着但顺手换了
    /// token"的配置(手机上那枚 token 也就配不上了)。
    ///
    /// 契约: 这一次要写的两件事(token 与开关)**要么一起落盘, 要么一个字节都不落**。
    /// 这条用"落盘失败"来看这个性质 —— 夹具里盘上**连配置文件都还没有**, 所以只要这次调用
    /// 在失败之前偷偷写过一次, 盘上就会多出东西来。
    ///
    /// 变异靶子: 把 `token_ready_in_memory(st)?` 换回 `ensure_token_persisted(st, dir)?`
    /// (旧的双写)→ 第一次写就成功了, 盘上出现配置文件, 第一条断言红(实测过)。
    #[test]
    fn enabling_with_a_fresh_token_writes_the_config_exactly_once() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("single_persist");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        assert!(
            !dir.join(config::CONFIG_FILE).exists(),
            "夹具前提: 盘上不许有配置文件(这条要看的正是'这次调用写了几个字节')"
        );

        // "首次开启"的真实起点: 开关关着, token 还没有(空 token 是 fail-closed 的状态)。
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: String::new(),
            shares: Vec::new(),
        }
        .into_state();
        seams::fail_next_persist("模拟: 这一次写失败了");

        let err = set_enabled_on_tauri_runtime(&st, &dir, true, true).expect_err("落盘失败必须回 Err");
        assert!(err.contains("没能保存"), "理由要能读: {}", err);

        assert!(
            !dir.join(config::CONFIG_FILE).exists(),
            "首次开启只许写一次: 落盘失败之后盘上不该出现任何半成品配置"
        );
        let disk = config::ServerConfig::load(&dir);
        assert!(!disk.enabled && disk.token.is_empty(), "盘上读到的必须还是默认(什么都没写)");
        // 内存按盘对齐: 这次换上的那枚 token 也没落盘, 所以它也必须跟着回退 ——
        // 否则界面会显示一枚盘上不存在的 token(手机照着它配对, 重启后作废)。
        let status = status_of_state(&st).unwrap();
        assert!(!status.enabled, "内存必须等于盘上那一份(false)");
        assert!(status.token.is_empty(), "这次换上的 token 没落盘 → 内存里也不许留着它");
        assert_eq!(running_port(), None, "落盘失败时一个监听都不许起");
    }

    /// **本轮 Fix 2 的核心钉子: 落盘失败之后的"撤回"必须按盘上那一份, 而不是进入时的旧快照。**
    ///
    /// 复审复现的现场(这里把它做成确定性的): "关闭"这条命令在自己的窗口里被另一条
    /// **成功落盘**的开关命令插了进去, 然后关闭自己的落盘失败。旧实现把 `prev` ——
    /// 也就是关闭命令**进入时**读到的内存值(那时是 `false`)—— 写回去, 于是四句话互相打架:
    ///   · 界面开关显示"关"(`enabled=false`),
    ///   · 盘上是"开"(并发那条已经写成功了),
    ///   · 同时有一台服务在监听(那是并发那条开启起的 —— 监听这一半由 Critical 1/2 的用例
    ///     守着, 这条只做"改内存 + 成功落盘"那一半: 也就是让 `prev` 变陈旧的那一下),
    ///   · 理由里还写着"服务已停止监听 / 开关会回到开"。
    /// 而下次开机读的是盘 —— 它会**照旧监听**, 与界面上那个"关"完全相反。
    ///
    /// 契约(本轮定死): **盘是唯一真相**。落盘失败后内存里的开关必须等于盘上那一份;
    /// 理由必须描述**实际留下**的状态。
    ///
    /// 为什么这里不直接并发调一次 `set_enabled(true)`: 那正是修复本身 —— 两条开关命令现在被
    /// `CONFIG_WRITE_LOCK` 串行, 从窗口里再调一次会**死锁**(按设计, 见
    /// `a_second_toggle_cannot_enter_while_the_first_is_inside_its_window`)。所以这里用测试
    /// 专用窗口直接做"另一条命令做过的那两件事"(改内存 + 成功落盘)。这是**等价且更强**的
    /// 干扰源: 连不经过 `set_enabled` 的写者(那几条直接 `persist` 的命令)造成的盘面变化,
    /// 也不许被回滚覆盖 —— 按盘对齐正是为了这一整类情况。
    ///
    /// 变异靶子: 把 `realign_from_disk` 换回"写回 `prev`"(`let prev = read_enabled(st)` …
    /// `let _ = write_enabled(st, prev)`)→ 内存停在 `false`、盘上是 `true`,
    /// 第一条断言当场红(实测过, 见报告)。
    #[test]
    fn a_persist_failure_never_stomps_a_concurrent_successful_toggle_on_disk() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("stale_prev_stomp");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        // 盘上先落一份 enabled=false 的配置, 内存也一致(用户此刻关着服务)。
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "9".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        save_config(&st, &dir).expect("先落一份 盘上=false 的配置");

        // 窗口: 另一条命令在这个瞬间改内存 + **成功落盘**。它就是把 `prev` 变陈旧的那一下。
        seams::set_window(Box::new(|st: &ServerState, dir: &Path| {
            write_enabled(st, true).expect("窗口里的内存写入");
            save_config(st, dir).expect("窗口里的落盘必须成功 —— 不对称故障的另一半");
        }));
        // 关闭**自己**这一次的落盘失败(真实成因: 杀软/OneDrive 恰好锁住了刚写完的配置文件)。
        seams::fail_next_persist("模拟: server-config.json 此刻被别的程序占着");

        let err = set_enabled_on_tauri_runtime(&st, &dir, false, false).expect_err("落盘失败必须回 Err");

        // 夹具自检: 窗口里那次落盘必须真的把 true 写进了盘, 否则这条什么都没证明。
        let on_disk = config::ServerConfig::load(&dir).enabled;
        assert!(on_disk, "夹具前提: 窗口里那次落盘必须真的把 enabled=true 写进盘");

        let status = status_of_state(&st).unwrap();
        // 契约①: 内存 == 盘上那一份。
        assert_eq!(
            status.enabled, on_disk,
            "落盘失败后内存里的开关必须等于盘上那一份 —— 旧实现的 `prev` 会把它写成 false"
        );
        assert!(status.enabled, "盘上是'开', 内存就必须是'开'");
        // 契约②: 理由描述**实际留下**的状态, 不许承诺一个不存在的"回到开/关"。
        assert!(err.contains("没能保存"), "理由要能读: {}", err);
        assert!(
            err.contains("盘上的开关仍是'开'"),
            "理由必须描述实际留下的状态(盘上是开): {}",
            err
        );
        // 契约③: 这一路没有留下监听(stop() 无条件执行过), 理由也必须这么说。
        assert_eq!(running_port(), None);
        assert!(!status.running, "状态必须说'没在跑'");
        assert!(
            err.contains("服务已停止监听"),
            "理由要说明服务确实停了(它没有在别人的窗口里偷偷留下监听): {}",
            err
        );
    }

    /// **Task 10 的残留缺陷钉子: 三条"只改配置"的命令落盘失败后, 内存必须等于盘上那一份。**
    ///
    /// 这三条(`rotate_server_token` / `add_share` / `remove_share`)原先长在 `lib.rs` 里,
    /// 各自都是"改内存 → `server::persist(&app, &state)?`" —— 盘写不进去时那个 `?` 只回一句
    /// Err, 而内存里留着**盘上不存在的新值**。用户看到的现场:
    ///   · 一枚新配对码: 照它抄给手机, 配对当场成功(服务端读的是内存), **重启后立刻 401**;
    ///   · 一个新共享根: 界面上加上了, 重启后消失(而 `shares` 空 = 服务对所有路径都回
    ///     "无权限", 那看起来像"服务坏了")。
    /// 与 Task 8 修掉的开关那条是**同一类谎**(界面 ≠ 重启后的现实), 修法也是同一条: 按盘对齐。
    ///
    /// 契约: **盘是唯一真相 —— 落盘失败后内存必须等于盘上那一份。**
    ///
    /// 为什么夹具里盘上先落一份**旧的**配置: 这样"内存 == 盘"与"内存 == 这次想写的新值"是
    /// 两个可分辨的断言。变异靶子(把 `persist_or_realign` 里那次 `realign_from_disk` 删掉)
    /// 会让内存停在这回想写的新值上, 三条子用例的主断言同时红。
    ///
    /// 为什么用 `seams::fail_next_persist` 而不是"把配置文件名占成目录"(开关那两条用的招):
    /// 这两条命令改的字段在盘上**有旧值**(token=旧的 64 位 hex / shares=[一个目录]), 而占目录
    /// 会让 `load()` 回落到默认空配置 —— 那就分不清"按盘对齐"与"把内存清空"了。
    #[test]
    fn config_commands_keep_memory_equal_to_disk_when_persisting_fails() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();

        // ① 轮换 token: 内存里那枚新 token 没落盘 → 必须被撤回成盘上的旧 token。
        {
            let dir = tmp_dir("rotate_realign");
            std::fs::create_dir_all(&dir).expect("建临时目录");
            let old = "d".repeat(64);
            let st = config::ServerConfig {
                read_only: false,
                enabled: true,
                port: 0,
                token: old.clone(),
                shares: Vec::new(),
            }
            .into_state();
            save_config(&st, &dir).expect("先落一份'旧 token'的配置");

            seams::fail_next_persist("模拟: server-config.json 此刻被别的程序占着");
            let err = tauri::async_runtime::block_on(rotate_token(&st, &dir))
                .expect_err("落盘失败必须回 Err");
            assert!(err.contains("没能保存"), "理由要能读: {}", err);
            assert!(err.contains("新的配对码"), "理由要说清是哪一件事没保存: {}", err);

            let on_disk = config::ServerConfig::load(&dir);
            assert_eq!(on_disk.token, old, "夹具前提: 盘上那一份必须还是旧 token");
            let in_memory = status_of_state(&st).unwrap().token;
            assert_eq!(
                in_memory, on_disk.token,
                "落盘失败后内存里的 token 必须等于盘上那一份 —— 否则用户会拿一枚重启后就作废的配对码"
            );
        }

        // ② 加共享根: 内存里那个新目录没落盘 → 必须被撤回。
        {
            let dir = tmp_dir("addshare_realign");
            let (old_share, new_share) = (dir.join("old"), dir.join("new"));
            std::fs::create_dir_all(&old_share).expect("建旧共享根");
            std::fs::create_dir_all(&new_share).expect("建新共享根");
            let st = config::ServerConfig {
                read_only: false,
                enabled: true,
                port: 0,
                token: "5".repeat(64),
                shares: vec![old_share.clone()],
            }
            .into_state();
            save_config(&st, &dir).expect("先落一份'只有一个旧共享根'的配置");

            seams::fail_next_persist("模拟: server-config.json 此刻被别的程序占着");
            let err = tauri::async_runtime::block_on(add_share(
                &st,
                &dir,
                &new_share.to_string_lossy(),
            ))
            .expect_err("落盘失败必须回 Err");
            assert!(err.contains("没能保存"), "理由要能读: {}", err);

            let on_disk = config::ServerConfig::load(&dir);
            assert_eq!(on_disk.shares, vec![old_share.clone()], "夹具前提: 盘上只有旧共享根");
            assert_eq!(
                share_strings(&st).unwrap(),
                vec![old_share.to_string_lossy().to_string()],
                "落盘失败后内存里的共享根必须等于盘上那一份(新加的那个不许留着)"
            );
        }

        // ③ 删共享根: 删除没落盘 → 内存里那个目录必须回来(否则界面显示已删、重启后又出现)。
        {
            let dir = tmp_dir("rmshare_realign");
            let (a, b) = (dir.join("a"), dir.join("b"));
            std::fs::create_dir_all(&a).expect("建共享根 a");
            std::fs::create_dir_all(&b).expect("建共享根 b");
            let st = config::ServerConfig {
                read_only: false,
                enabled: true,
                port: 0,
                token: "6".repeat(64),
                shares: vec![a.clone(), b.clone()],
            }
            .into_state();
            save_config(&st, &dir).expect("先落一份'两个共享根'的配置");

            seams::fail_next_persist("模拟: server-config.json 此刻被别的程序占着");
            let err = tauri::async_runtime::block_on(remove_share(&st, &dir, &a.to_string_lossy()))
                .expect_err("落盘失败必须回 Err");
            assert!(err.contains("没能保存"), "理由要能读: {}", err);

            let on_disk = config::ServerConfig::load(&dir);
            assert_eq!(on_disk.shares.len(), 2, "夹具前提: 盘上仍是两个共享根");
            let in_memory = share_strings(&st).unwrap();
            assert_eq!(
                in_memory,
                vec![a.to_string_lossy().to_string(), b.to_string_lossy().to_string()],
                "落盘失败后内存里的共享根必须等于盘上那一份(删掉的那个必须回来)"
            );
        }

        // 收尾: 注入点是全局状态, 还回去(与 `reset_server_state` 同一条纪律)。
        seams::clear();
    }

    /// **串行那半边的钉子: 一条写配置的命令在它的窗口里时, 第二条进不来。**
    ///
    /// 与 `a_second_toggle_cannot_enter_while_the_first_is_inside_its_window` 同形, 守的是
    /// Task 10 残留缺陷修复的**前提**: "落盘失败 → 读盘对齐"只有在"读盘与对齐之间没有别的写者
    /// 成功落盘"时才说得通(否则那次对齐会把别人刚写成功的成果盖掉 —— 与 Task 8 那个陈旧的
    /// `prev` 回滚是同一个坑, 只是换了字段)。所以这四条会写 `server-config.json` 的命令共用
    /// 同一把 `CONFIG_WRITE_LOCK`。
    ///
    /// 变异靶子: 删掉 `rotate_token` / `add_share` / `remove_share` 头上那句
    /// `CONFIG_WRITE_LOCK.lock().await` → 第二条(加共享根)在窗口里一路跑完, `second_done`
    /// 变 true, 这条红。
    #[test]
    fn a_second_config_write_cannot_enter_while_the_first_is_inside_its_window() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::mpsc;

        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("config_write_serialized");
        let share_dir = dir.join("photos");
        std::fs::create_dir_all(&share_dir).expect("建共享根");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "3".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        save_config(&st, &dir).expect("先落一份合法配置");

        let (entered_tx, entered_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        seams::set_window(Box::new(move |_st: &ServerState, _dir: &Path| {
            entered_tx.send(()).expect("通知测试线程: 第一条已经进到窗口里了");
            release_rx.recv().expect("等测试线程放行");
        }));

        // 第一条: 轮换 token。它会卡在窗口里(落盘之前), 并且**一直持有** `CONFIG_WRITE_LOCK`。
        let (st_first, dir_first) = (st.clone(), dir.clone());
        let first = std::thread::spawn(move || {
            tauri::async_runtime::block_on(rotate_token(&st_first, &dir_first))
        });
        entered_rx
            .recv_timeout(std::time::Duration::from_secs(10))
            .expect("第一条写配置的命令必须进到窗口里(否则夹具本身失效了)");

        // 第二条: 加一个共享根。它必须先被挡在门外。
        let second_done = Arc::new(AtomicBool::new(false));
        let (st_second, dir_second) = (st.clone(), dir.clone());
        let share_second = share_dir.to_string_lossy().to_string();
        let flag = second_done.clone();
        let second = std::thread::spawn(move || {
            let r = tauri::async_runtime::block_on(add_share(&st_second, &dir_second, &share_second));
            flag.store(true, Ordering::SeqCst);
            r
        });
        // 给它足够的时间跑完(真要放行的话, 它那条路上全是快操作: 判目录、改内存、一次文件写)。
        std::thread::sleep(std::time::Duration::from_millis(500));
        assert!(
            !second_done.load(Ordering::SeqCst),
            "第二条写配置的命令不许在第一条的窗口里跑完 —— `CONFIG_WRITE_LOCK` 没能把它们串起来"
        );

        release_tx.send(()).expect("放行第一条");
        first.join().expect("第一条不许 panic").expect("第一条应成功");
        second.join().expect("第二条不许 panic").expect("第二条应成功");
        assert!(second_done.load(Ordering::SeqCst), "放行之后第二条必须能完成");

        // 收尾: 注入点是全局状态, 还回去。
        seams::clear();
    }

    /// **`CONFIG_WRITE_LOCK` 的钉子: 一条开关命令在它的窗口里时, 第二条进不来。**
    ///
    /// 这正是"窗口"被取消的证明: 旧实现里第二条(并发成功的 `set_enabled(true)`)能整条跑完,
    /// 于是它的成果被第一条的回滚覆盖(见上一条用例)。现在第二条只能等第一条出去。
    ///
    /// 确定性来自窗口钩子把第一条**卡住**: 钩子往测试线程发信号然后等放行 —— 于是"第一条
    /// 正处在落盘之前的窗口里"是一个可以被测试线程观测并依赖的时刻, 不靠 sleep 猜。
    /// 测试线程在这个窗口里起第二条, 等一会儿, 断言它**还没结束**。
    ///
    /// 变异靶子: 把 `set_enabled` 头上那句 `CONFIG_WRITE_LOCK.lock().await` 删掉 → 第二条在窗口里
    /// 一路跑完(它那条路上没有任何阻塞点), `second_done` 变 true, 这条红(实测过)。
    #[test]
    fn a_second_toggle_cannot_enter_while_the_first_is_inside_its_window() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::mpsc;

        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("toggle_serialized");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "4".repeat(64),
            shares: Vec::new(),
        }
        .into_state();
        save_config(&st, &dir).expect("先落一份合法配置");

        let (entered_tx, entered_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        seams::set_window(Box::new(move |_st: &ServerState, _dir: &Path| {
            entered_tx.send(()).expect("通知测试线程: 第一条已经进到窗口里了");
            release_rx.recv().expect("等测试线程放行");
        }));

        // 第一条: 开启。它会卡在窗口里, 并且**一直持有** `CONFIG_WRITE_LOCK`。
        let (st_first, dir_first) = (st.clone(), dir.clone());
        let first = std::thread::spawn(move || {
            tauri::async_runtime::block_on(set_enabled(&st_first, &dir_first, true, true))
        });
        entered_rx
            .recv_timeout(std::time::Duration::from_secs(10))
            .expect("第一条开关命令必须进到窗口里(否则夹具本身失效了)");

        // 第二条: 关闭。它必须先被挡在门外。
        let second_done = Arc::new(AtomicBool::new(false));
        let (st_second, dir_second) = (st.clone(), dir.clone());
        let flag = second_done.clone();
        let second = std::thread::spawn(move || {
            let r = tauri::async_runtime::block_on(set_enabled(&st_second, &dir_second, false, false));
            flag.store(true, Ordering::SeqCst);
            r
        });
        // 给它足够的时间跑完(真要放行的话, 它那条路上全是快操作: 写内存、stop、一次文件写)。
        std::thread::sleep(std::time::Duration::from_millis(500));
        assert!(
            !second_done.load(Ordering::SeqCst),
            "第二条开关命令不许在第一条的窗口里跑完 —— `CONFIG_WRITE_LOCK` 没能把两条命令串起来"
        );

        release_tx.send(()).expect("放行第一条");
        let first_status = first.join().expect("第一条不许 panic").expect("第一条应成功");
        second.join().expect("第二条不许 panic").expect("第二条应成功");
        assert!(second_done.load(Ordering::SeqCst), "放行之后第二条必须能完成");

        // 收尾: 第二条(关闭)必须真的把第一条起的那台服务放开 —— 别把监听留给别的用例。
        assert!(
            wait_until_refused(first_status.port, std::time::Duration::from_secs(10)),
            "第二条(关闭)之后端口 {} 必须不再接受连接",
            first_status.port
        );
        assert_eq!(running_port(), None);
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
            read_only: false,
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

    /// **这张网是"端口没卡住 / 状态与 socket 一致", 不是 Critical 2 的证明。**
    ///
    /// 它在防什么(以及它确实是这里的价值): 打的是**真实入口** `set_enabled`, 也就是
    /// enable/disable 两条命令真的并行跑, 逐轮断言:
    ///   · 状态说在跑 ⟹ 那个端口**此刻真的能连上**(不是"端口的账"被写坏了);
    ///   · 全部轮次结束后干净地关一次: 状态必须说"没在跑", 且所有报告过在跑的端口都必须
    ///     真的不再接受连接;
    ///   · 再来一次干净的开启: 它必须**真的绑上**(旧代码的第二种交错让端口永远非 0,
    ///     于是每一次 enable 都在幂等检查上短路 —— 状态说在跑, 却没人监听)。
    ///
    /// ── 它**不**证明什么(务必别把它当成 Critical 2 的钉子) ────────────────────
    /// 把 `stop()` 里那句 `lc.gen += 1` 删掉, 这条**照样是绿的**: `start` 安装那一步的
    /// "用户还要着它吗"复查(`wants_start`)会在命令层把那次错序的安装挡掉, 于是"stop 插进
    /// bind 窗口"这个交错在这条用例里根本走不到它的靶心。也就是说:
    ///   · 它绿 ⟹ 用户能观察到的那个谎(说在跑却没监听 / 说没跑却还听着)**没有出现**;
    ///   · 它绿 ⟹/⟸ **不能**推出"stop 与在途 bind 的序列化是对的"。
    /// 那一条由确定性用例 (A) `concurrent_stop_during_bind_never_leaves_a_listener_behind`
    /// 承担(它用可观测的 `binding` 票据**等到**窗口真的开着再插 `stop()`, 并带夹具自检)。
    /// 另外: "状态与连接一致"只有在交错**真的发生**的那一轮才有杀伤力 —— 它是一张网,
    /// 不是证明。
    #[test]
    fn concurrent_enable_disable_keeps_status_truthful_and_the_port_unstuck() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("concurrent_enable_disable");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        let st = config::ServerConfig {
            read_only: false,
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
                std::thread::spawn(move || {
                    tauri::async_runtime::block_on(set_enabled(&a, &da, true, true))
                });
            let disable =
                std::thread::spawn(move || {
                    tauri::async_runtime::block_on(set_enabled(&b, &db, false, false))
                });
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
        set_enabled_on_tauri_runtime(&st, &dir, false, false).expect("干净关闭必须成功");
        assert_eq!(running_port(), None, "干净关闭之后状态必须说'没在跑'");
        // **去重是必需的, 不是优化**: 每轮都用同一个基端口(31876), 而这一版并发用例里
        // "开启"经常是**后**拿到锁的那一个(先被并发关闭挡住、再自己开起来), 于是同一轮结束
        // 时留下的端口就是它 —— 25 轮会把同一个端口记 25 遍。去重之后断言的信息量一模一样
        // (一个端口有没有被放开, 与它被记了几遍无关), 而**不去重就要多等 25 次探测**:
        // 这台机器上"连一个已经没人监听的端口"要 ~2.05 秒才返回 Err(见 `accepting_within`
        // 的注释), 25 次就是 50 秒的纯等待。
        reported.sort_unstable();
        reported.dedup();
        for p in &reported {
            assert!(
                wait_until_refused(*p, std::time::Duration::from_secs(10)),
                "端口 {} 在关闭之后仍然接受连接(端口被卡住了)",
                p
            );
        }

        // 收尾②: 后续的开启必须**真的**绑上。
        let status = set_enabled_on_tauri_runtime(&st, &dir, true, true).expect("再次开启必须成功");
        assert!(status.running, "开启之后状态必须说'在跑'");
        let p = running_port().expect("开启之后状态必须说'在跑'");
        assert!(accepts_connections(p), "开启之后的端口 {} 必须真的接受连接", p);

        // 把全局状态还给别的用例。
        set_enabled_on_tauri_runtime(&st, &dir, false, false).expect("关闭");
        assert_eq!(running_port(), None);
        assert!(wait_until_refused(p, std::time::Duration::from_secs(10)));
    }

    /// **`Lifecycle` 不变量里那句"误差方向"的可测部分**: `stop()` 返回时状态**立刻**说
    /// "没在跑"(那一条由 `real_listener_serves_with_auth_and_stop_actually_closes_the_port`
    /// 与上面几条用例钉着), 而 socket 的真正关闭在 serve 任务看到停止信号之后的收尾里。
    /// 两者之间有一个**有界**的窗口 —— 期间内核 backlog 仍能完成握手, 也就是 `connect()`
    /// 还可能成功。读 axum 0.8.9 的源码得到的顺序(`src/serve/mod.rs:284-297`):
    ///   `select!` 到 `signal_tx.closed()` → `break` 出 accept 循环 → **紧接着**
    ///   `drop(listener)` → 之后才去等在建连接收尾。
    /// 所以这个窗口 = "serve 任务被调度到 + drop 掉监听器", 与"手机上是不是正在下载一个大
    /// 文件"**无关**(等在建连接那一段在 `drop(listener)` 之后)。
    ///
    /// 这条用例量它、把实测值打出来(不变量注释里那两个数就是从这来的), 并把收敛上界钉住。
    /// 它**不**断言窗口一定 > 0: 那取决于调度, 断它会变成看运气(实测里 10 轮中只抓到过一次,
    /// 另一次 10 轮一次都没抓到); 它钉的是用户能感知的那部分: **最终一致, 而且有界**。
    ///
    /// 探针必须**自带上界**(`accepting_within`), 原因见那个函数: 这台机器上"连一个没人监听
    /// 的端口"要丢包约 2 秒才失败, 裸 connect 量出来的是探针自己的超时, 不是这个窗口。
    ///
    /// 变异靶子: 让 `stop()` 不再发停止信号(或让 serve 任务忽略它)→ 端口永远不会停止接受
    /// 连接, 这条红。
    #[test]
    fn after_stop_the_port_stops_accepting_within_a_bounded_window() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        const ROUNDS: u32 = 10;
        // 单次探针的有界等待: 活着的时候 loopback 握手是**微秒**级(实测 193µs), 所以
        // 50ms 足够松; 而它同时把"没人监听"那一侧的成本压到 50ms, 不让探针自己变成常数。
        const PROBE: std::time::Duration = std::time::Duration::from_millis(50);
        let mut worst = std::time::Duration::ZERO;
        let mut open_at_return = 0u32; // `stop()` 刚返回时探针仍连得上的轮次 = 窗口的**证据**
        for round in 0..ROUNDS {
            let st = config::ServerConfig {
                read_only: false,
                enabled: true,
                // **固定端口**: 不能让系统从动态端口段里挑 —— 那个端口被关掉之后又空了出来,
                // 而探针自己的出向连接**也可能**被系统分配到同一个端口上(自己连上自己),
                // 于是"还在监听"变成一个假阳性。固定端口(在动态段之外)不会当源端口用。
                port: 27_876,
                token: "5".repeat(64),
                shares: Vec::new(),
            }
            .into_state();
            let port = start_on_tauri_runtime(st).expect("应能起服务");
            assert!(
                accepting_within(port, PROBE),
                "第 {} 轮夹具前提: {} 必须真的在监听",
                round,
                port
            );
            stop();
            if accepting_within(port, PROBE) {
                open_at_return += 1;
            }
            // 从 `stop()` 返回那一刻起量到"不再接受连接": 探针每 1ms 一次, 每次自带上界。
            let t0 = std::time::Instant::now();
            let deadline = t0 + std::time::Duration::from_secs(5);
            while accepting_within(port, PROBE) {
                assert!(
                    std::time::Instant::now() < deadline,
                    "第 {} 轮: stop() 之后 5 秒内 {} 仍然在接受连接(端口没被放开)",
                    round,
                    port
                );
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            worst = worst.max(t0.elapsed());
        }
        println!(
            "[test] stop() → 端口不再接受连接: 最坏 {:?} / {} 轮(探针粒度 {}ms); stop() 刚返回时探针仍连得上的轮次 {}/{}",
            worst, ROUNDS, PROBE.as_millis(), open_at_return, ROUNDS
        );
    }

    /// **minor ②: `stop()` 要清掉上一次的理由** —— 用户已经亲手把服务关了, 界面不该
    /// 还挂着那句旧的启动失败。
    #[test]
    fn stop_clears_a_stale_lifecycle_error() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let st = config::ServerConfig {
            read_only: false,
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
            read_only: false,
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
            public_ack_at: None,
            read_only: false,
        })
        .unwrap();
        assert_eq!(v["running"], false);
        assert_eq!(v["port"], 8765);
        assert_eq!(v["url"], "http://127.0.0.1:8765");
        assert_eq!(v["token"], "t".repeat(64));
        assert_eq!(v["shares"][0], "C:\\照片");
        assert_eq!(v["enabled"], false);
        assert!(v["lastError"].is_null());
        assert!(v["publicAckAt"].is_null(), "确认记录也要用 camelCase 出去");
        assert_eq!(v["readOnly"], false, "只读模式也要用 camelCase 出去");
        assert!(v.get("last_error").is_none(), "字段名必须是 camelCase");
    }

    // ── M4′ Task 2 · 公用网络防护 ───────────────────────────────────────────
    //
    // 这一批用例守的是规格 §4.3 的两条硬约束:
    //   ① 当前网络被 Windows 标为**公用**(或者类别**根本读不出来** —— Fix 1 之后这两档同级)
    //      时, 服务**不许自动启动**(`boot`);
    //   ② 用户显式确认之后才允许开, 而且**这次确认要进状态**(不能静默放行)。
    //
    // 「公用网络」这个现场只能靠**注入探测结果**造出来: 本机的真实类别没法在测试里改
    // (改系统网络设置会波及这台机器上别的一切; NLM 的 `SetSimulatedProfileInfo` 是动系统
    // 状态的东西, 更不该碰)。所以这里注入的是 `current_hints()` 那一层的**输入**,
    // 判定与落点(start/端口/状态)全是真的 —— 这不是"假跑一遍"。
    //
    // 反过来说清一件事: **"这台机器的真实类别读得对不对"没法被单测证明**。`detect_hints`
    // 只被钉住"分类的不变量"(见 network.rs 的 `real_snapshot_respects_the_classification_invariants`),
    // 那是刻意的 —— 断言"本机此刻是 Private"这种话只会变成看运气的红。

    /// 一份"当前网络是公用"的假探测结果(其余字段照真实形状给)。
    fn public_hints() -> network::NetHints {
        network::NetHints {
            lan: vec![network::LanAddress {
                address: "192.168.3.10".into(),
                interface: "以太网".into(),
            }],
            public_ipv4: Vec::new(),
            profile: network::NetworkProfile::Public,
            firewall_hint: network::FIREWALL_HINT_ALLOW_INBOUND_PRIVATE.to_string(),
        }
    }

    /// 一份"当前网络是专用"的假探测结果(**成对的对照**, 用来证明防护不是"一律拒绝")。
    fn private_hints() -> network::NetHints {
        network::NetHints { profile: network::NetworkProfile::Private, ..public_hints() }
    }

    /// **自动启动这条路: 公用网络下必须不起服务**, 只留一句可见的理由。
    ///
    /// 变异靶子: 把 `boot_with` 里那句 `guard_refusal(false)` 删掉(或让
    /// `network::should_refuse_to_start` 对 `Public` 返回 `None`)→ 这条当场红: `boot_with`
    /// 会返回 `Ok`, 而且 `port: 0` 意味着它**真的**会绑上一个端口(不是靠"绑不上"蒙混过去)。
    #[tokio::test]
    async fn public_profile_does_not_auto_start_and_leaves_a_visible_reason() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(public_hints());
        let dir = tmp_dir("guard_boot");
        let st = config::ServerConfig {
            read_only: false,
            enabled: true,
            port: 0,
            token: "7".repeat(64), // token 是好的 → 唯一的拦路者只能是那道防护
            shares: Vec::new(),
        }
        .into_state();

        let err = boot_with(st.clone(), &dir, || panic!("token 已经可用, 不该走生成这条路"))
            .await
            .expect_err("公用网络上不许自动启动");
        assert!(err.contains("公用"), "理由要能读、且说清是哪一档: {err}");
        assert_eq!(running_port(), None, "被拒的服务不许留下'在跑'的痕迹");
        assert_eq!(
            status_of_state(&st).unwrap().last_error.as_deref(),
            Some(err.as_str()),
            "界面必须能拿到这句理由(否则用户只看到'开着但没在跑')"
        );
        assert_eq!(
            status_of_state(&st).unwrap().public_ack_at,
            None,
            "自动启动这条路没有人可以确认, 因此也不该有确认记录"
        );
    }

    /// 对照组: **专用网络下自动启动照常**(防护不许变成"一律拒绝")。
    #[tokio::test]
    async fn private_profile_still_auto_starts() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(private_hints());
        let dir = tmp_dir("guard_boot_private");
        let st = config::ServerConfig {
            read_only: false,
            enabled: true,
            port: 0,
            token: "8".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        let port = boot_with(st.clone(), &dir, || panic!("token 可用"))
            .await
            .expect("专用网络上应当照常自动启动")
            .expect("必须真的起来了一个端口");
        assert_eq!(running_port(), Some(port));
        assert!(accepts_connections(port));
        stop();
    }

    /// **开关这条路: 没确认 → 拒绝, 而且一个字节都不动; 确认之后 → 真的开, 且确认进状态。**
    ///
    /// 三件事一起钉住(缺一条, "静默放行"就还活着):
    ///   ① 被拒时 `enabled` 不许被留在"开"(否则下次开机它会自己再试一次);
    ///   ② 被拒时不许落盘(落盘的语义是"下次开机还会尝试开启");
    ///   ③ 确认之后 `public_ack_at` 必须有值 —— 这就是"这次开启不是静默发生的"那条证据。
    ///
    /// 变异靶子: 把 `set_enabled` 里 `guard_refusal` 那一段删掉/改成不看 `confirm_public`
    /// → 第①条当场红(它会在公用网络上直接开起来)。
    #[tokio::test]
    async fn public_profile_refuses_the_switch_until_the_user_confirms_then_records_it() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(public_hints());
        let dir = tmp_dir("guard_switch");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "9".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        // ① 没确认: 拒绝, 而且开关/盘/端口三处都不许留下"开过"的痕迹。
        let err = set_enabled_on_tauri_runtime(&st, &dir, true, false)
            .expect_err("公用网络上不许静默开启");
        assert!(err.contains("公用"), "理由要能读、且说清是哪一档: {err}");
        assert_eq!(running_port(), None, "被拒的开启不许留下监听");
        let refused = status_of_state(&st).unwrap();
        assert!(!refused.enabled, "被拒的开启不许把开关留在'开'");
        assert_eq!(refused.public_ack_at, None, "没确认过就不该有确认记录");
        assert_eq!(refused.last_error.as_deref(), Some(err.as_str()));
        assert!(!dir.exists(), "被拒的开启不许落盘(盘上写着'开'= 下次开机还会再试)");

        // ② 确认之后: 真的起来, 而且这次确认被记进状态。
        let started = set_enabled_on_tauri_runtime(&st, &dir, true, true)
            .expect("用户显式确认之后应当能开");
        assert!(started.enabled && started.running, "确认后应当真的在监听: {started:?}");
        assert!(
            started.public_ack_at.is_some(),
            "这次确认必须被记进状态(绝不静默放行): {started:?}"
        );
        assert!(accepts_connections(started.port));

        // ③ 关掉这条路**绝不**过防护(无论当前是什么网络, 用户必须永远关得掉它):
        //    这里连确认都不带。
        let stopped = set_enabled_on_tauri_runtime(&st, &dir, false, false)
            .expect("关闭永远不许被网络类别挡住");
        assert!(!stopped.running && !stopped.enabled);
        assert_eq!(running_port(), None);
    }

    /// 开关这条路上的**对照组**: 专用网络下不带确认也能开(否则"防护"就变成了"谁都开不了")。
    #[tokio::test]
    async fn private_profile_switch_needs_no_confirmation() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(private_hints());
        let dir = tmp_dir("guard_switch_private");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "a".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        let s = set_enabled_on_tauri_runtime(&st, &dir, true, false)
            .expect("专用网络上不需要任何确认");
        assert!(s.enabled && s.running);
        assert_eq!(s.public_ack_at, None, "没发生过确认, 就不该有记录");
        set_enabled_on_tauri_runtime(&st, &dir, false, false).expect("关闭");
    }

    /// **读不出类别也要走同一道闸**(`network::should_refuse_to_start` 的第二条规则, 这里钉住它
    /// 真的接到了开关上): 有非局域网地址 → 拒绝。
    #[tokio::test]
    async fn unknown_profile_with_a_public_address_also_refuses_the_switch() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(network::NetHints {
            public_ipv4: vec!["203.0.113.9".into()],
            // 类别**读不出来**(COM 不可用那一档) —— 这正是要测的退化输入。
            profile: network::NetworkProfile::Unknown,
            ..private_hints()
        });
        let dir = tmp_dir("guard_degraded");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "b".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        let err = set_enabled_on_tauri_runtime(&st, &dir, true, false)
            .expect_err("退化判定也要拦在同一个地方");
        assert!(err.contains("203.0.113.9"), "理由里要带上那个地址: {err}");
        assert_eq!(running_port(), None);
        assert!(!status_of_state(&st).unwrap().enabled);
    }

    /// **Fix 1 · 新规则: `Unknown` + 没有任何非局域网地址 —— 开关这条路也必须拒绝。**
    ///
    /// 这就是本轮要堵的 fail-open: 咖啡厅/办公室/酒店那类共享 WiFi 发的**通常就是私有租约**,
    /// 所以 `public_ipv4` 为空根本不能证明这条网络可信; 而 netprofm 停掉/COM 不可用时, 类别
    /// **恰恰**读不出来。旧规则下这个组合会被静默放行。
    ///
    /// 变异靶子: 把 `network::should_refuse_to_start` 的 `Unknown` 分支改回"只看有没有非局域网
    /// 地址"(`Unknown if !h.public_ipv4.is_empty()`)→ 这里 `expect_err` 当场红(它会把服务开起来)。
    #[tokio::test]
    async fn unknown_profile_without_any_public_address_still_refuses_the_switch() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(network::NetHints {
            // **空的**: 面板上一条"不是局域网的地址"都没有 —— 旧规则就是在这里放行的。
            public_ipv4: Vec::new(),
            profile: network::NetworkProfile::Unknown,
            ..private_hints()
        });
        let dir = tmp_dir("guard_unknown_no_public");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "c".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        let err = set_enabled_on_tauri_runtime(&st, &dir, true, false)
            .expect_err("读不出类别时不许静默开启(即使没有任何非局域网地址)");
        assert!(err.contains("读不到"), "理由要说清是「读不出来」: {err}");
        assert_eq!(running_port(), None, "被拒的开启不许留下监听");
        let refused = status_of_state(&st).unwrap();
        assert!(!refused.enabled, "被拒的开启不许把开关留在'开'");
        assert!(!dir.exists(), "被拒的开启不许落盘");
        assert_eq!(refused.last_error.as_deref(), Some(err.as_str()));

        // 用户显式确认之后**仍然能开**(fail-closed ≠ 永久锁死): 与 Public 那条同一条出路。
        let started = set_enabled_on_tauri_runtime(&st, &dir, true, true)
            .expect("显式确认之后应当能开");
        assert!(started.enabled && started.running);
        assert!(started.public_ack_at.is_some(), "这次确认必须进状态");
        set_enabled_on_tauri_runtime(&st, &dir, false, false).expect("关闭");
    }

    /// **自动启动这条路也一样**: `Unknown` + 没有非局域网地址 → 不许自己起来。
    ///
    /// 这条比开关那条更贴近本轮的成因: 自动启动时**没有人**在场, "静默开在共享 WiFi 上"正是
    /// 这道防护存在的全部理由。变异靶子同上。
    #[tokio::test]
    async fn unknown_profile_without_any_public_address_does_not_auto_start() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        seams::set_hints_override(network::NetHints {
            public_ipv4: Vec::new(),
            profile: network::NetworkProfile::Unknown,
            ..private_hints()
        });
        let dir = tmp_dir("guard_unknown_boot");
        let st = config::ServerConfig {
            read_only: false,
            enabled: true,
            port: 0,
            token: "d".repeat(64), // token 是好的 → 唯一的拦路者只能是那道防护
            shares: Vec::new(),
        }
        .into_state();

        let err = boot_with(st.clone(), &dir, || panic!("token 已经可用, 不该走生成这条路"))
            .await
            .expect_err("读不出类别时不许自动启动");
        assert!(err.contains("读不到"), "理由要能读、且说清是哪一档: {err}");
        assert_eq!(running_port(), None, "被拒的服务不许留下'在跑'的痕迹");
        assert_eq!(
            status_of_state(&st).unwrap().public_ack_at,
            None,
            "自动启动这条路没有人可以确认, 因此也不该有确认记录"
        );
    }

    /// **minor ③: 落盘期间换了网络, 不许再凭旧快照把端口开起来。**
    ///
    /// 夹具用测试专用的"窗口"钩子 —— 它跑在 `write_enabled` 与 `persist_config` **之间**,
    /// 也就是那道判定(**拿配置写锁之前**做的)与 `start()` 之间。钩子里把注入的快照从
    /// `Private` 换成 `Public`, 模拟"用户在这段时间里走进了咖啡厅"。没有落盘之后那次复查的话,
    /// 这一次开启会**绑定端口并开始监听** —— 而它凭的是一份已经过期的"专用网络"读数。
    ///
    /// 变异靶子: 删掉 `set_enabled` 里 `start` 之前那次 `guard_refusal` → `expect_err` 当场红,
    /// 而且 `running_port()` 会留下 Some(真的监听起来了)。
    #[tokio::test]
    async fn a_network_switch_during_the_persist_step_cannot_slip_through() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        // 进入时是"专用网络": 第一次判定放行, 于是才会走到落盘那一步。
        seams::set_hints_override(private_hints());
        let dir = tmp_dir("guard_switch_between");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 0,
            token: "e".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        // 窗口 = "落盘这一步里, 机器换到了公用网络"(netprofm 也可以是在这里被停掉的)。
        seams::set_window(Box::new(|_st: &ServerState, _dir: &Path| {
            seams::set_hints_override(public_hints());
        }));

        let err = set_enabled_on_tauri_runtime(&st, &dir, true, false)
            .expect_err("落盘期间换成公用网络之后不许再开起来");
        assert!(err.contains("公用"), "理由要能读、且说清新的事实: {err}");
        assert_eq!(
            running_port(),
            None,
            "旧快照不许把端口开起来 —— 这条就是那次复查存在的理由"
        );
        assert!(
            status_of_state(&st).unwrap().last_error.as_deref() == Some(err.as_str()),
            "界面必须能拿到这句理由"
        );
        // 开关留在"开"是**有意的**(与 start 失败那条路同一条纪律: 用户的选择不回滚),
        // 但盘上写着"开"并不等于会静默监听 —— 自动启动那条路自己也要过同一道闸。
        assert!(status_of_state(&st).unwrap().enabled, "这次不滚回开关(用户确实点了开)");
    }

    /// `local_ipv4` 不许 panic(取不到就是 `None`, 由 `primary_url` 回落到 127.0.0.1)。
    #[test]
    fn primary_url_never_panics_and_always_has_a_port() {
        assert!(primary_url(8765).ends_with(":8765"));
        assert!(primary_url(8765).starts_with("http://"));
        assert!(local_ipv4().map(|ip| !ip.is_empty()).unwrap_or(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    // M4′ Task 1 · 配对二维码
    //
    // 这一批只钉一件事: **二维码里到底装了什么**。它是用户唯一会照着扫的东西, 装错地址的
    // 后果是"手机连到门外"或"连到手机自己" —— 两者都比"没有二维码"难查得多。
    // ════════════════════════════════════════════════════════════════════════

    /// M4′ Task 1 Step 1 的原话: 以 `<svg` 开头、含 `</svg>`; **同一输入两次结果相同**。
    ///
    /// 最后那句 `assert_ne!` 不是装饰: 少了它, 一个"永远返回同一张固定图"的实现也能让前三条
    /// 全绿 —— 也就是说"确定"这条断言会因为**内容根本没被编进去**而通过。
    #[test]
    fn pairing_qr_svg_is_deterministic_and_is_an_svg() {
        let token = "a".repeat(64);
        let a = pairing_qr_svg("http://192.168.1.7:8765", &token).expect("编码");
        let b = pairing_qr_svg("http://192.168.1.7:8765", &token).expect("编码");
        assert!(a.starts_with("<svg"), "必须是 SVG 源码: {}", &a[..a.len().min(60)]);
        assert!(a.contains("</svg>"), "必须是一个完整的 svg 元素");
        // XML 声明必须已经剥掉: 留在开头会让 `dangerouslySetInnerHTML` 多出一个注释节点,
        // 而且这条接口的契约就是"以 `<svg` 开头"(见 `strip_xml_prolog`)。
        assert!(!a.contains("<?xml"), "开头的 XML 声明应当已被剥掉");
        assert_eq!(a, b, "同一输入必须给出同一张图(二维码是确定的)");

        // ── 它必须真的**画了东西**, 不是一具空壳 ──────────────────────────────
        // 这一组挡的是"SVG 被切坏了却仍然以 `<svg` 开头/以 `</svg>` 结尾"这类残缺:
        //   · `viewBox` 是前端那个 `w-full h-auto` 等比缩放所依赖的东西;
        //   · 黑/白两色都在 —— 二维码的对比度是它的**功能**, 丢了色就扫不出来;
        //   · `M…h…v…H…V` 是 `qrcode` 画**每一个黑模块**用的子路径, 所以 `M` 的个数
        //     = 黑模块数(一个真二维码有几百个)。少于 100 个说明图是空的或被截断了。
        assert!(a.contains("viewBox="), "SVG 必须带 viewBox(前端按它等比缩放)");
        assert!(
            a.contains("#ffffff") && a.contains("#000000"),
            "必须同时有白底与黑模块(对比度就是这张图的功能)"
        );
        let modules = a.matches('M').count();
        assert!(modules > 100, "只数到 {modules} 个黑模块 —— 这张图是空的或残缺的");

        let c = pairing_qr_svg("http://192.168.1.7:8766", &token).expect("编码");
        assert_ne!(a, c, "端口变了图就必须跟着变 —— 否则上面那条'确定'是假的");
        let d = pairing_qr_svg("http://192.168.1.7:8765", &"b".repeat(64)).expect("编码");
        assert_ne!(a, d, "配对码变了图也必须跟着变");
    }

    /// 二维码里装的那段 JSON 的形状(M4′ Task 1 定的 `{"url":…,"token":…}`, 两个字段都必填)。
    #[test]
    fn pairing_payload_is_the_documented_json_shape() {
        let token = "b".repeat(64);
        let payload = pairing_payload("http://192.168.1.7:8765", &token);
        let v: serde_json::Value = serde_json::from_str(&payload).expect("必须是 JSON");
        assert_eq!(v["url"], "http://192.168.1.7:8765");
        assert_eq!(v["token"], token);
        assert_eq!(v.as_object().map(|o| o.len()), Some(2), "只有这两个字段: {payload}");
    }

    /// **面板要的那一份**: 地址必须是私有网段、配对码一并带上, 而且三段内容同源。
    ///
    /// `assert_eq!(qr.payload, pairing_payload(&qr.url, &token))` 是关键的一条 —— 它把
    /// "我们报出来的 payload"与"喂给编码器的那段内容"钉成同一个值, 所以下面那些对
    /// `payload` 的断言才真的是在说**二维码里的内容**, 而不是"我们打算装什么"。
    #[test]
    fn pairing_qr_carries_a_private_address_and_the_token() {
        let token = "c".repeat(64);
        let qr = pairing_of(&public_hints(), 8765, &token).expect("有私有地址就该有二维码");

        assert_eq!(qr.payload, pairing_payload(&qr.url, &token), "报出来的就是编进去的那段");
        assert!(qr.svg.starts_with("<svg") && qr.svg.contains("</svg>"));

        let v: serde_json::Value = serde_json::from_str(&qr.payload).expect("payload 是 JSON");
        let url = v["url"].as_str().expect("url 是字符串");
        assert_eq!(url, "http://192.168.3.10:8765", "地址 + **端口**都要在里面");
        assert_eq!(v["token"], token, "配对码必须在二维码里(否则扫了也配不上对)");

        // 地址那一节必须是**私有网段**(规格 §4.2: 候选里绝不出现公网地址)。
        let host = url.trim_start_matches("http://");
        let host = host.split(':').next().unwrap_or("");
        let ip: std::net::Ipv4Addr = host.parse().expect("主机必须是点分十进制 IPv4");
        assert!(network::is_private_v4(ip), "二维码里只许装私有网段地址: {url}");

        // 端口用的是**调用方给的那个**, 不是写死的默认值(`ServerStatus.port` 会跟着走)。
        let other = pairing_of(&public_hints(), 9999, &token).expect("同一条地址换端口");
        assert!(other.url.ends_with(":9999"), "端口必须来自状态: {}", other.url);
        assert_ne!(other.svg, qr.svg, "端口不同 → 图也不同");
    }

    /// **没有私有地址就不生成二维码**, 而且**绝不**回落到公网地址或 `127.0.0.1`
    /// (决定与理由见 `pairing_of` 上方那段"没有私有地址时怎么办")。
    ///
    /// 四个现场: ①一个候选都没有; ②候选里**先**出现一条公网地址(守"别处放松了 `lan` 的
    /// 构造"这一整类情况 —— 一个只看 `lan[0]` 的实现会在这里把公网地址编进去); ③整份都没有
    /// 私有地址; ④token 不可用(编出来也一定配不上对)。
    #[test]
    fn pairing_qr_is_not_produced_without_a_private_address() {
        let token = "d".repeat(64);

        // ① 没有候选地址(电脑没连 WiFi/有线): 没有二维码, 也就没有'手机连到手机自己'这种事。
        let mut no_lan = public_hints();
        no_lan.lan.clear();
        assert_eq!(pairing_of(&no_lan, 8765, &token), None, "没有候选地址就不该有二维码");

        // ② 第一条是公网地址: 必须**跳过**它去取后面那条私有的。
        let mut mixed = public_hints();
        mixed.lan = vec![
            network::LanAddress { address: "203.0.113.9".into(), interface: "以太网".into() },
            network::LanAddress { address: "10.1.2.3".into(), interface: "WLAN".into() },
        ];
        let picked = pairing_of(&mixed, 8765, &token).expect("后面那条是私有的, 应当被选中");
        assert!(picked.url.contains("10.1.2.3"), "应当取私有的那条: {}", picked.url);
        assert!(!picked.url.contains("203.0.113.9"), "公网地址不许进二维码: {}", picked.url);

        // ③ 整份都没有私有地址(公网 + 回环): 一张图都不给。回环尤其不许兜底 ——
        //    手机扫到 127.0.0.1 连的是**它自己**, 用户只会得到一句莫名其妙的"连接被拒绝"。
        let mut only_public = public_hints();
        only_public.lan = vec![
            network::LanAddress { address: "203.0.113.9".into(), interface: "以太网".into() },
            network::LanAddress { address: "127.0.0.1".into(), interface: "回环".into() },
        ];
        assert_eq!(
            pairing_of(&only_public, 8765, &token),
            None,
            "公网/回环地址绝不许进二维码(会把手机指向门外或指向它自己)"
        );

        // ④ token 不可用: 空串 与 长度对但非十六进制, 两种都一定 401, 所以宁可不给图。
        assert_eq!(pairing_of(&public_hints(), 8765, ""), None, "空 token 不该出二维码");
        assert_eq!(
            pairing_of(&public_hints(), 8765, &"z".repeat(64)),
            None,
            "非十六进制 token 不该出二维码"
        );

        // 对照: 同一份 hints 换一个**可用**的 token 就有图 —— 证明上面四条不是"一律 None"。
        assert!(pairing_of(&public_hints(), 8765, &token).is_some());
    }

    /// `pairing_qr`(命令用那一版)取的是**状态里那个端口与 token**, 不是别的。
    ///
    /// 端口这一条特意用一个**非默认**端口: 配置里写 8123, 状态与二维码都必须是 8123 ——
    /// 一个写死 `DEFAULT_PORT` 的实现会在这里红。
    #[test]
    fn pairing_qr_reads_the_port_and_token_from_the_state() {
        let token = "e".repeat(64);
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: 8123,
            token: token.clone(),
            shares: Vec::new(),
        }
        .into_state();

        let qr = pairing_qr(&st, &public_hints()).expect("配置里有可用的 token 与私有地址");
        assert!(qr.url.ends_with(":8123"), "端口必须来自配置: {}", qr.url);
        assert!(qr.payload.contains(&token));

        // 配置里 token 不可用(空 = 全新的默认配置, 是**真实可达**的状态) → 没有二维码。
        let empty = config::ServerConfig { token: String::new(), ..Default::default() }.into_state();
        assert_eq!(pairing_qr(&empty, &public_hints()), None);
    }

    // ════════════════════════════════════════════════════════════════════════
    // M4′ Task 3 · 只读模式(配置侧)
    //
    // 拦截行为本身是**路由层**的事, 用例在 `routes.rs` 与 `tests/server_contract.rs`
    // (要打真请求才证得了)。这里只钉配置这一半: 开关真的落盘、状态如实报出来。
    // ════════════════════════════════════════════════════════════════════════

    /// 开/关只读 → 内存与**盘上**都变, 而且状态里报出来(面板那个开关看的就是它)。
    ///
    /// 变异靶子: 把 `set_read_only` 里那次 `persist_or_realign` 删掉(只改内存) →
    /// "重启后仍是盘上那一份"那条断言当场红。
    #[tokio::test]
    async fn set_read_only_persists_and_is_reported_in_the_status() {
        let _serialized = GLOBAL_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        reset_server_state();
        let dir = tmp_dir("readonly_cfg");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        let st = config::ServerConfig {
            read_only: false,
            enabled: false,
            port: config::DEFAULT_PORT,
            token: "f".repeat(64),
            shares: Vec::new(),
        }
        .into_state();

        let on = set_read_only(&st, &dir, true).await.expect("开启只读");
        assert!(on.read_only, "状态必须如实报出只读已开");
        assert!(
            config::ServerConfig::load(&dir).read_only,
            "必须落盘 —— 否则重启就悄悄变回可写, 而那正是'设置没保存'那类谎"
        );

        let off = set_read_only(&st, &dir, false).await.expect("关闭只读");
        assert!(!off.read_only);
        assert!(!config::ServerConfig::load(&dir).read_only, "关闭同样要落盘");
    }
}
