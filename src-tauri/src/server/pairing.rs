// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 局域网配对状态机。**纯内存 + 注入时钟**, 里面没有任何 HTTP / Tauri / 网络类型。
//!
//! 为什么单独一层(而不是把状态塞进 `routes.rs` 的处理器里): 这一节的全部规则都是
//! **时间规则**, 而它们又恰恰是"看起来在跑、其实永远等不到"那类缺陷的来源:
//!   · 配对窗口只有 **5 分钟** —— 过期之后请求必须被拒(`pairing_closed`);
//!   · 单个请求 **60 秒**过期 —— 手机等不到确认就该看到"已过期", 而不是无限等待;
//!   · 每设备 **10 秒 1 次** —— 防止一台手机把桌面端刷成弹窗队列;
//!   · 最多 **3 个待确认** —— 桌面端弹窗队列的上界(计划 §0 的全局约束)。
//! 把时钟做成可注入的(`Clock`)之后, "60 秒后"这种断言就不必真的 `sleep(60s)`,
//! 于是这些规则能被**真的跑一遍**而不是靠读代码相信。
//!
//! ── token 从不经过这里(这是设计, 不是遗漏) ─────────────────────────────────
//! `PairingStore` 里**没有** token 字段, 也没有 URL / 桌面名 —— 它只回答"这个请求现在是什么
//! 状态"。批准之后手机在 `GET /api/v1/pair/status` 拿到的 `{url, token, desktopName}` 由
//! `routes::pair_status` 在**那一刻**从配置与网络探测里现拼。
//! 于是"token 绝不在批准前返回"不是一条要靠每个分支自觉遵守的纪律, 而是**类型上构造不出来**
//! 的事: 状态机手里根本没有那枚 token。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// 配对窗口长度: 桌面端点"配对新设备"之后 5 分钟内可用(计划 §0 的全局约束)。
pub const WINDOW: Duration = Duration::from_secs(5 * 60);
/// 单个配对请求的有效期: 手机发起后 60 秒内没被允许/拒绝就过期。
pub const REQUEST_TTL: Duration = Duration::from_secs(60);
/// 同一台设备的请求间隔: 10 秒 1 次。
pub const DEVICE_INTERVAL: Duration = Duration::from_secs(10);
/// 同时最多几个待确认请求。
pub const MAX_PENDING: usize = 3;
/// 过期记录**再多留这么久**: 过期后手机要能读到 `{"status":"expired"}`(200),
/// 而不是一个 404 —— 后者会让安卓把它显示成"未知错误", 用户不知道该重试还是该重新发现。
/// 保留 60 秒之后, 记录才真的被回收(内存有界)。
pub const EXPIRED_RETENTION: Duration = REQUEST_TTL;

/// 字段长度上限(超了回 400 `bad_request`, 见计划 §0.2)。
/// 这些值不是协议约束, 只是"任何正常的手机都在里面"的上界 —— 没有它, 一条超大字段就能
/// 把内存里的记录撑大(而配对端点**免鉴权**, 必须自己守住这一层)。
pub const MAX_DEVICE_ID_LEN: usize = 128;
pub const MAX_DEVICE_NAME_LEN: usize = 128;
pub const MAX_APP_VERSION_LEN: usize = 64;

/// 注入的单调时钟。测试里推快它, 生产里就是 `Instant::now`。
pub type Clock = Arc<dyn Fn() -> Instant + Send + Sync>;

/// `POST /api/v1/pair/request` 的请求体(计划 §0.2 的冻结形状)。
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub device_id: String,
    pub device_name: String,
    pub app_version: String,
}

/// 202 的响应体。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairChallenge {
    pub request_id: String,
    /// unix 毫秒 —— 与 §0.3 的 `PairingWindow.expiresAt` 同一口径。
    pub expires_at: u64,
}

/// 配对窗口(§0.3 `open_pairing_window` 的返回)。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingWindow {
    /// unix 毫秒。
    pub expires_at: u64,
}

/// 一个**待确认**请求。桌面端弹窗与 `pairing_status()` 都用这一份(§0.3)。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingPairing {
    pub request_id: String,
    pub device_id: String,
    pub device_name: String,
    pub ip: String,
    pub requested_at: u64,
}

/// 一台已配对的设备。**v1 只是记录**: 移除它不会吊销 token(平台约束, 见计划 §8 第 5 条),
/// 真正吊销要走"轮换配对码"。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub device_id: String,
    pub name: String,
    pub last_seen_at: u64,
}

/// `pairing_status()` 的返回(§0.3)。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingStatus {
    pub open: bool,
    /// 只有窗口开着时才有值(unix 毫秒) —— 关着的窗口没有"到期时刻"可言。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<u64>,
    pub pending: Vec<PendingPairing>,
    pub paired: Vec<PairedDevice>,
}

/// 一个请求此刻的状态。**它不携带任何秘密**(见模块头)。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairStatus {
    Pending,
    Approved,
    Denied,
    Expired,
}

/// 状态机拒绝一次操作的理由。HTTP 状态码/码值由 `routes.rs` 映射, 这里只表达语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairError {
    /// 配对窗口没开(或已经过期)。
    Closed,
    /// 同一设备 10 秒内重复请求。
    RateLimited,
    /// 字段缺失之外的不合法(空/过长)。
    BadRequest,
    /// 待确认已经满 3 个。
    TooManyPending,
    /// `requestId` 不存在(或已被回收)。
    NotFound,
    /// 请求已过期, 不能再批准/拒绝。
    Expired,
    /// 系统随机数不可用(生成 requestId 失败)。
    RandomUnavailable,
}

/// `sweep` 的结果。调用方据此决定要不要更新 mDNS 的 `pair=open|closed` 与发事件 ——
/// **只在真的变了的时候发**, 否则桌面端会每秒收到一条无意义的 `pairing-changed`。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SweepReport {
    /// 窗口这一次从"开"变成了"关"(到期)。
    pub window_closed: bool,
    /// 这一次有多少条**待确认**请求变成了"已过期"。
    pub expired: usize,
}

/// 桌面端要处理的两类配对事件(§0.3)。Tauri 那一层通过 `PairingNotify` 把它翻成 emit,
/// 本模块不知道 Tauri 的存在。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingEvent {
    /// 新请求到达 → 桌面端弹确认框。
    Request(PendingPairing),
    /// 状态整体变了(批准/拒绝/过期/移除) → 桌面端刷新列表。
    Changed(PairingStatus),
}

/// 类型擦除的通知端 —— 与 `server::Notify` 完全同一条纪律: 签名里没有 Tauri 类型,
/// 于是测试 exe 不会在加载期被 `comctl32.dll` 拖死(见 `super::Notify` 上那段)。
pub type PairingNotify = Arc<dyn Fn(PairingEvent) + Send + Sync>;

/// 一条内部记录。`expires_at` 是**单调时钟**上的截止点(判定用), `expires_at_ms` 是给界面/
/// 手机看的 unix 毫秒(展示用) —— 两者必须一起更新, 否则会出现"状态说没过期、界面说已过期"。
struct Record {
    request: PairRequest,
    ip: String,
    requested_at_ms: u64,
    expires_at: Instant,
    expires_at_ms: u64,
    state: RecordState,
    /// 这条记录"由待确认变成已过期"这件事是否**已经**被 `sweep` 报过。
    /// 没有它, 每秒钟的 sweep 都会把同一条过期再算一次, 桌面端会被无意义事件刷屏。
    expired_reported: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RecordState {
    Pending,
    Approved,
    Denied,
}

/// 配对状态机。所有方法都是**同步**的(只有内存操作) —— 于是它不会把锁带过 `await`。
pub struct PairingStore {
    clock: Clock,
    /// 窗口截止点(`None` = 窗口关着)。单调时钟: 用户改系统时间不会把窗口变长。
    window_until: Option<Instant>,
    /// 窗口截止的 unix 毫秒(展示用)。
    window_expires_at_ms: Option<u64>,
    /// requestId → 记录。**过期记录仍留 [`EXPIRED_RETENTION`]**, 见常量注释。
    records: HashMap<String, Record>,
    /// deviceId → 上一次**被接受**的请求时刻(限流用)。
    last_request: HashMap<String, Instant>,
    /// 已配对设备。**由 `ServerConfig::paired_devices` 播种**, 批准时追加, 移除时删掉 ——
    /// 落盘由 `server` 模块负责(本模块不碰文件系统)。
    paired: Vec<PairedDevice>,
}

impl Default for PairingStore {
    fn default() -> Self {
        Self::new()
    }
}

impl PairingStore {
    pub fn new() -> Self {
        Self::with_clock(Arc::new(Instant::now))
    }

    /// 注入时钟的构造。测试用它把 5 分钟 / 60 秒 / 10 秒这些规则**真的**跑一遍。
    pub fn with_clock(clock: Clock) -> Self {
        Self {
            clock,
            window_until: None,
            window_expires_at_ms: None,
            records: HashMap::new(),
            last_request: HashMap::new(),
            paired: Vec::new(),
        }
    }

    /// 从落盘的已配对设备列表播种(服务启动时由 `ServerState` 的构造调用)。
    pub fn with_paired(paired: Vec<PairedDevice>) -> Self {
        let mut store = Self::new();
        store.paired = paired;
        store
    }

    fn now(&self) -> Instant {
        (self.clock)()
    }

    // ── 窗口 ─────────────────────────────────────────────────────────────────

    /// 打开 5 分钟窗口。**重复打开只是把到期时间往后推**(与"再点一次配对新设备"的直觉一致)。
    pub fn open_window(&mut self) -> PairingWindow {
        let now = self.now();
        let expires_at_ms = unix_ms().saturating_add(WINDOW.as_millis() as u64);
        self.window_until = Some(now + WINDOW);
        self.window_expires_at_ms = Some(expires_at_ms);
        PairingWindow { expires_at: expires_at_ms }
    }

    /// 关窗口。**还挂着的待确认请求一并作废**(桌面端点了"关闭", 那些请求就不该还能被批准),
    /// 已批准/已拒绝的记录保持原样 —— 已经拿到 token 的手机不该因为窗口关闭而"配对失败"。
    pub fn close_window(&mut self) {
        let now = self.now();
        self.window_until = None;
        self.window_expires_at_ms = None;
        for rec in self.records.values_mut() {
            if rec.state == RecordState::Pending {
                rec.expires_at = now;
                rec.expires_at_ms = unix_ms();
            }
        }
    }

    /// 窗口此刻开着吗? 到期由单调时钟判定, 不依赖系统时间。
    pub fn window_open(&self) -> bool {
        self.window_open_at(self.now())
    }

    fn window_open_at(&self, now: Instant) -> bool {
        self.window_until.map(|t| now < t).unwrap_or(false)
    }

    // ── 请求 ─────────────────────────────────────────────────────────────────

    /// 收下一个配对请求。成功时返回 202 的响应体; 失败时返回**闭集**理由。
    ///
    /// 顺序是刻意的(每一步都对应 §0.2 表里的一行):
    ///   ① 字段不合法 → `BadRequest`(400);
    ///   ② 窗口没开 → `Closed`(409) —— 窗口之外一切免谈;
    ///   ③ 同设备 10 秒内重复 → `RateLimited`(429);
    ///   ④ 已有**同一个设备**的活请求 → 原样返回那一张(幂等): 手机重试不该在桌面端
    ///      变成第二个弹窗;
    ///   ⑤ 待确认满 3 个 → `TooManyPending`(映射为 429, 见 `routes.rs` 的说明);
    ///   ⑥ 生成 requestId(随机数不可用 → `RandomUnavailable`, 不 panic)。
    pub fn request(&mut self, req: PairRequest, ip: &str) -> Result<PairChallenge, PairError> {
        if !valid_field(&req.device_id, MAX_DEVICE_ID_LEN)
            || !valid_field(&req.device_name, MAX_DEVICE_NAME_LEN)
            || !valid_field(&req.app_version, MAX_APP_VERSION_LEN)
        {
            return Err(PairError::BadRequest);
        }

        let now = self.now();
        if !self.window_open_at(now) {
            return Err(PairError::Closed);
        }

        if let Some(last) = self.last_request.get(&req.device_id) {
            if now.saturating_duration_since(*last) < DEVICE_INTERVAL {
                return Err(PairError::RateLimited);
            }
        }

        // 同一设备的活请求: 原样返回(幂等)。放在限流之后 —— 于是"10 秒内两次"仍然回 429
        // (计划 A3 点名的那条测试), 而 10 秒之后的重试不会堆出第二个弹窗。
        if let Some((id, expires_at_ms)) = self
            .records
            .iter()
            .find(|(_, r)| {
                r.state == RecordState::Pending
                    && now < r.expires_at
                    && r.request.device_id == req.device_id
            })
            .map(|(id, r)| (id.clone(), r.expires_at_ms))
        {
            // 幂等的这一支也算"一次请求": 同样刷新 10 秒限流, 否则过了第一个 10 秒之后
            // 手机可以无限重试(虽然不会多弹窗, 但违背"每设备 10 秒 1 次"的契约)。
            self.last_request.insert(req.device_id.clone(), now);
            return Ok(PairChallenge { request_id: id, expires_at: expires_at_ms });
        }

        if self.pending_count(now) >= MAX_PENDING {
            return Err(PairError::TooManyPending);
        }

        let request_id =
            super::auth::try_new_hex_id(16).map_err(|_| PairError::RandomUnavailable)?;
        let expires_at_ms = unix_ms().saturating_add(REQUEST_TTL.as_millis() as u64);
        self.last_request.insert(req.device_id.clone(), now);
        self.records.insert(
            request_id.clone(),
            Record {
                request: req,
                ip: ip.to_string(),
                requested_at_ms: unix_ms(),
                expires_at: now + REQUEST_TTL,
                expires_at_ms,
                state: RecordState::Pending,
                expired_reported: false,
            },
        );
        Ok(PairChallenge { request_id, expires_at: expires_at_ms })
    }

    /// 这台设备**此刻**有没有还活着的待确认请求。
    ///
    /// HTTP 层用它决定要不要发 `pairing-request`: 同一条请求被重试(客户端超时重发)时,
    /// `request` 会幂等地返回原来那张, 但桌面端**不该再弹第二个框**。
    /// 判据与 `request` 的幂等分支逐字一致(pending + 未过期 + 同一 deviceId)。
    pub fn pending_request_for_device(&self, device_id: &str) -> Option<String> {
        let now = self.now();
        self.records
            .iter()
            .find(|(_, r)| {
                r.state == RecordState::Pending
                    && now < r.expires_at
                    && r.request.device_id == device_id
            })
            .map(|(id, _)| id.clone())
    }

    /// 一个 requestId 此刻的状态。`None` = 不认识(或已被回收) → HTTP 404。
    ///
    /// **没有副作用**: 过期只是"读出来是 Expired", 记录本身由 `sweep` 清理。
    pub fn status(&self, request_id: &str) -> Option<PairStatus> {
        let rec = self.records.get(request_id)?;
        if self.now() >= rec.expires_at {
            return Some(PairStatus::Expired);
        }
        Some(match rec.state {
            RecordState::Pending => PairStatus::Pending,
            RecordState::Approved => PairStatus::Approved,
            RecordState::Denied => PairStatus::Denied,
        })
    }

    /// 批准一个请求。返回被批准的设备(调用方据此发事件 / 落盘)。
    ///
    /// 三条前置条件: 记录存在、还没过期、**窗口还开着** —— 最后一条是安全边界: 窗口关闭之后
    /// 桌面端不该还能"补批"一个旧请求(那等于窗口形同虚设)。
    ///
    /// 批准会把有效期**延长一个 `REQUEST_TTL`**: 手机可能在下一次轮询之前刚好错过原截止点,
    /// 而"电脑已经点了允许、手机却看到 expired"是这条链路上最冤的一种失败。
    pub fn approve(&mut self, request_id: &str) -> Result<PairedDevice, PairError> {
        let now = self.now();
        if !self.window_open_at(now) {
            return Err(PairError::Closed);
        }
        let rec = self.records.get_mut(request_id).ok_or(PairError::NotFound)?;
        if now >= rec.expires_at || rec.state != RecordState::Pending {
            return Err(PairError::Expired);
        }
        rec.state = RecordState::Approved;
        rec.expires_at = now + REQUEST_TTL;
        rec.expires_at_ms = unix_ms().saturating_add(REQUEST_TTL.as_millis() as u64);
        let seen = unix_ms();
        let device = PairedDevice {
            device_id: rec.request.device_id.clone(),
            name: rec.request.device_name.clone(),
            last_seen_at: seen,
        };
        match self.paired.iter_mut().find(|d| d.device_id == device.device_id) {
            Some(existing) => *existing = device.clone(),
            None => self.paired.push(device.clone()),
        }
        Ok(device)
    }

    /// 拒绝一个请求。同样要求"记录还没过期"; **不要求窗口开着** —— 拒绝永远比批准安全,
    /// 用户点了"拒绝"就该生效(哪怕窗口刚好过期)。
    pub fn deny(&mut self, request_id: &str) -> Result<(), PairError> {
        let now = self.now();
        let rec = self.records.get_mut(request_id).ok_or(PairError::NotFound)?;
        if now >= rec.expires_at || rec.state != RecordState::Pending {
            return Err(PairError::Expired);
        }
        rec.state = RecordState::Denied;
        rec.expires_at = now + REQUEST_TTL;
        rec.expires_at_ms = unix_ms().saturating_add(REQUEST_TTL.as_millis() as u64);
        Ok(())
    }

    /// 清理: 关掉到期的窗口(并把还挂着的待确认一并作废)、回收过期够久的记录、清掉过期的限流项。
    ///
    /// 窗口到期时**一并作废待确认请求**是刻意的: 那之后 `approve` 已经不可能成功(窗口检查),
    /// 让它们继续显示 "pending" 只会让桌面端弹窗排着一个点了也没用的队。
    pub fn sweep(&mut self) -> SweepReport {
        let now = self.now();
        let mut report = SweepReport::default();

        if let Some(t) = self.window_until {
            if now >= t {
                self.window_until = None;
                self.window_expires_at_ms = None;
                report.window_closed = true;
                for rec in self.records.values_mut() {
                    if rec.state == RecordState::Pending && now < rec.expires_at {
                        rec.expires_at = now;
                        rec.expires_at_ms = unix_ms();
                    }
                }
            }
        }

        for rec in self.records.values_mut() {
            if rec.state == RecordState::Pending
                && now >= rec.expires_at
                && !rec.expired_reported
            {
                rec.expired_reported = true;
                report.expired += 1;
            }
        }

        // 回收: 过期满 `EXPIRED_RETENTION` 之后, 这个 requestId 就该回 404 了
        // (手机这时也早已放弃轮询)。`retain` 保证内存不随请求数无限增长。
        self.records
            .retain(|_, r| now < r.expires_at + EXPIRED_RETENTION);
        // 限流表同理: 超过 10 秒的旧记录不再有任何判定价值。
        self.last_request
            .retain(|_, t| now.saturating_duration_since(*t) < DEVICE_INTERVAL);
        report
    }

    // ── 快照(给桌面端) ──────────────────────────────────────────────────────

    /// 桌面端要的整份状态(§0.3)。`pending` 只列**还活着**的待确认, 按发起时间升序
    /// (弹窗队列按到达顺序显示)。
    pub fn snapshot(&self) -> PairingStatus {
        let now = self.now();
        let open = self.window_open_at(now);
        let mut pending: Vec<PendingPairing> = self
            .records
            .iter()
            .filter(|(_, r)| r.state == RecordState::Pending && now < r.expires_at)
            .map(|(id, r)| PendingPairing {
                request_id: id.clone(),
                device_id: r.request.device_id.clone(),
                device_name: r.request.device_name.clone(),
                ip: r.ip.clone(),
                requested_at: r.requested_at_ms,
            })
            .collect();
        pending.sort_by_key(|p| p.requested_at);
        PairingStatus {
            open,
            expires_at: if open { self.window_expires_at_ms } else { None },
            pending,
            paired: self.paired.clone(),
        }
    }

    /// 单个待确认请求的快照(HTTP 处理器发 `pairing-request` 事件时用)。
    pub fn pending_pairing(&self, request_id: &str) -> Option<PendingPairing> {
        let now = self.now();
        let r = self.records.get(request_id)?;
        if r.state != RecordState::Pending || now >= r.expires_at {
            return None;
        }
        Some(PendingPairing {
            request_id: request_id.to_string(),
            device_id: r.request.device_id.clone(),
            device_name: r.request.device_name.clone(),
            ip: r.ip.clone(),
            requested_at: r.requested_at_ms,
        })
    }

    pub fn paired_devices(&self) -> Vec<PairedDevice> {
        self.paired.clone()
    }

    /// 按盘对齐用(落盘失败之后, 让内存回到盘上那一份)。
    pub fn set_paired_devices(&mut self, devices: Vec<PairedDevice>) {
        self.paired = devices;
    }

    /// "忘记"一台设备。**只是不再显示** —— v1 不吊销 token(见 `PairedDevice` 的注释)。
    pub fn forget(&mut self, device_id: &str) -> bool {
        let before = self.paired.len();
        self.paired.retain(|d| d.device_id != device_id);
        self.paired.len() != before
    }

    fn pending_count(&self, now: Instant) -> usize {
        self.records
            .values()
            .filter(|r| r.state == RecordState::Pending && now < r.expires_at)
            .count()
    }
}

/// 字段合法性: 非空、不超长、没有控制字符。
///
/// 控制字符那一条不是洁癖: `deviceName` 会被原样塞进桌面端的弹窗(以及 mDNS TXT 之外的
/// 事件负载), 一个带 `\n` 的名字会在日志/界面上伪造出一行新内容。
fn valid_field(v: &str, max: usize) -> bool {
    !v.is_empty() && v.len() <= max && !v.chars().any(|c| c.is_control())
}

/// unix 毫秒。系统时钟被设到 1970 之前时给 0 —— 不 panic, 也不假装知道时刻
/// (与 `server::routes::unix_seconds` 同一个处理)。
fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    /// 可推进的假时钟。返回 (把手, 注入给 store 的那一份)。
    fn test_clock() -> (Arc<StdMutex<Instant>>, Clock) {
        let handle = Arc::new(StdMutex::new(Instant::now()));
        let inner = handle.clone();
        (handle, Arc::new(move || *inner.lock().unwrap_or_else(|e| e.into_inner())))
    }

    fn advance(handle: &Arc<StdMutex<Instant>>, d: Duration) {
        *handle.lock().unwrap_or_else(|e| e.into_inner()) += d;
    }

    fn req(device: &str) -> PairRequest {
        PairRequest {
            device_id: device.to_string(),
            device_name: "Pixel 8".to_string(),
            app_version: "1.2.1".to_string(),
        }
    }

    fn store_with_window() -> (PairingStore, Arc<StdMutex<Instant>>) {
        let (handle, clock) = test_clock();
        let mut store = PairingStore::with_clock(clock);
        store.open_window();
        (store, handle)
    }

    #[test]
    fn request_rejected_when_window_closed() {
        let (_handle, clock) = test_clock();
        let mut store = PairingStore::with_clock(clock);
        assert_eq!(store.request(req("d1"), "192.168.1.9"), Err(PairError::Closed));
        // 拒绝必须发生在**生成 requestId 之前**: 关着窗口的端点不许碰随机数。
        assert!(store.snapshot().pending.is_empty());
    }

    #[test]
    fn request_is_rate_limited_per_device() {
        let (mut store, _h) = store_with_window();
        assert!(store.request(req("d1"), "192.168.1.9").is_ok());
        assert_eq!(
            store.request(req("d1"), "192.168.1.9"),
            Err(PairError::RateLimited),
            "同一台设备 10 秒内第二次必须被限流"
        );
        // 另一台设备不受影响 —— 限流是按 deviceId 的, 不是全局限流。
        assert!(store.request(req("d2"), "192.168.1.10").is_ok());
    }

    /// 限流窗口过去之后的重试是**幂等**的: 返回同一张 requestId, 待确认里仍然只有一条。
    ///
    /// `pending_request_for_device` 是给 HTTP 层判断"这次是重试, 不要再发 `pairing-request`"
    /// 用的 —— 没有它, 手机每 10 秒重发一次就会在桌面端堆出一个弹窗队列。
    #[test]
    fn a_retry_after_the_rate_limit_returns_the_same_pending_request() {
        let (mut store, h) = store_with_window();
        let first = store.request(req("d1"), "192.168.1.9").expect("第一次请求");
        assert_eq!(
            store.pending_request_for_device("d1"),
            Some(first.request_id.clone())
        );

        advance(&h, DEVICE_INTERVAL);
        let again = store
            .request(req("d1"), "192.168.1.9")
            .expect("10 秒后重试应当成功(幂等)");
        assert_eq!(again.request_id, first.request_id, "重试不许生成第二个弹窗");
        assert_eq!(store.snapshot().pending.len(), 1, "待确认里只许有一条");
        assert_eq!(store.pending_request_for_device("d2"), None, "别的设备没有待确认");
    }

    #[test]
    fn pending_expires_after_60s() {
        let (mut store, h) = store_with_window();
        let ch = store.request(req("d1"), "192.168.1.9").expect("窗口开着, 应当接受");
        assert_eq!(store.status(&ch.request_id), Some(PairStatus::Pending));

        advance(&h, Duration::from_secs(59));
        assert_eq!(store.status(&ch.request_id), Some(PairStatus::Pending));

        advance(&h, Duration::from_secs(2));
        assert_eq!(
            store.status(&ch.request_id),
            Some(PairStatus::Expired),
            "60 秒之后必须读到 expired(200), 不是 404"
        );
        let report = store.sweep();
        assert_eq!(report.expired, 1, "第一次 sweep 要报告这条过期");
        assert_eq!(store.sweep().expired, 0, "同一条过期不许每次 sweep 都重复报告");
        assert_eq!(store.snapshot().pending.len(), 0, "过期的不再出现在待确认列表里");

        // 保留期过后才真的回收(此后回 404)。
        advance(&h, EXPIRED_RETENTION + Duration::from_secs(1));
        store.sweep();
        assert_eq!(store.status(&ch.request_id), None);
    }

    #[test]
    fn approve_returns_token_only_after_approval() {
        let (mut store, _h) = store_with_window();
        let ch = store.request(req("d1"), "192.168.1.9").expect("接受");
        assert_eq!(store.status(&ch.request_id), Some(PairStatus::Pending));
        // 状态机**手里根本没有 token** —— 序列化出来的快照里也不可能有。
        let wire = serde_json::to_string(&store.snapshot()).unwrap();
        assert!(!wire.contains("token"), "批准之前不许出现任何 token 字段: {wire}");
        assert!(store.paired_devices().is_empty());

        let device = store.approve(&ch.request_id).expect("批准");
        assert_eq!(device.device_id, "d1");
        assert_eq!(store.status(&ch.request_id), Some(PairStatus::Approved));
        assert_eq!(store.paired_devices().len(), 1, "批准即进入已配对列表");
        // 批准之后这里依然没有 token —— 它只由 HTTP 层在**这一刻**从配置里现拼。
        let wire = serde_json::to_string(&store.snapshot()).unwrap();
        assert!(!wire.contains("token"), "状态机永远不携带 token: {wire}");
    }

    #[test]
    fn unknown_request_is_none() {
        let (store, _h) = store_with_window();
        assert_eq!(store.status("no-such-request"), None);
    }

    #[test]
    fn window_expires_after_5_min() {
        let (mut store, h) = store_with_window();
        advance(&h, WINDOW - Duration::from_secs(1));
        assert!(store.window_open(), "4:59 时窗口还开着");
        advance(&h, Duration::from_secs(2));
        assert!(!store.window_open(), "5:01 时窗口必须关");
        let report = store.sweep();
        assert!(report.window_closed, "sweep 要报告窗口这一次关闭(调用方据此改 mDNS)");
        assert_eq!(store.request(req("d1"), "192.168.1.9"), Err(PairError::Closed));
    }

    #[test]
    fn max_three_pending() {
        let (mut store, _h) = store_with_window();
        for i in 0..MAX_PENDING {
            store
                .request(req(&format!("d{i}")), "192.168.1.9")
                .expect("前三个应当接受");
        }
        assert_eq!(
            store.request(req("d4"), "192.168.1.9"),
            Err(PairError::TooManyPending),
            "第 4 个待确认必须被拒(桌面端弹窗队列有上界)"
        );

        // 拒绝一个之后位置空出来 —— 上界是"同时待确认", 不是"一辈子最多三个"。
        let first = store.snapshot().pending[0].request_id.clone();
        store.deny(&first).expect("拒绝");
        assert!(store.request(req("d4"), "192.168.1.9").is_ok());
    }

    #[test]
    fn close_window_invalidates_pending_but_keeps_approved() {
        let (mut store, _h) = store_with_window();
        let a = store.request(req("d1"), "192.168.1.9").expect("接受");
        let b = store.request(req("d2"), "192.168.1.9").expect("接受");
        store.approve(&b.request_id).expect("批准 d2");

        store.close_window();
        assert!(!store.window_open());
        assert_eq!(store.status(&a.request_id), Some(PairStatus::Expired), "还在等的请求作废");
        assert_eq!(
            store.status(&b.request_id),
            Some(PairStatus::Approved),
            "已批准的不能因为关窗口而变成失败(手机可能正准备取 token)"
        );
        assert_eq!(store.approve(&a.request_id), Err(PairError::Closed));
    }

    #[test]
    fn approve_requires_an_open_window() {
        let (mut store, h) = store_with_window();
        let ch = store.request(req("d1"), "192.168.1.9").expect("接受");
        advance(&h, WINDOW + Duration::from_secs(1));
        assert_eq!(store.approve(&ch.request_id), Err(PairError::Closed));
    }

    #[test]
    fn bad_fields_are_rejected() {
        let (mut store, _h) = store_with_window();
        assert_eq!(
            store.request(PairRequest { device_id: String::new(), ..req("d1") }, "ip"),
            Err(PairError::BadRequest)
        );
        assert_eq!(
            store.request(
                PairRequest { device_name: "x".repeat(MAX_DEVICE_NAME_LEN + 1), ..req("d1") },
                "ip"
            ),
            Err(PairError::BadRequest)
        );
        assert_eq!(
            store.request(PairRequest { device_name: "a\nb".into(), ..req("d1") }, "ip"),
            Err(PairError::BadRequest),
            "控制字符会伪造界面/日志里的换行"
        );
    }

    #[test]
    fn forget_removes_only_that_device() {
        let (mut store, _h) = store_with_window();
        let a = store.request(req("d1"), "ip").expect("接受");
        let b = store.request(req("d2"), "ip").expect("接受");
        store.approve(&a.request_id).expect("批准");
        store.approve(&b.request_id).expect("批准");
        assert!(store.forget("d1"));
        assert_eq!(store.paired_devices().len(), 1);
        assert_eq!(store.paired_devices()[0].device_id, "d2");
        assert!(!store.forget("d1"), "重复移除是幂等的 false");
    }

    #[test]
    fn open_window_sets_five_minutes() {
        let (_h, clock) = test_clock();
        let mut store = PairingStore::with_clock(clock);
        let before = unix_ms();
        let win = store.open_window();
        let after = unix_ms();
        assert!(
            win.expires_at >= before + 299_000 && win.expires_at <= after + 301_000,
            "窗口必须是 5 分钟: {win:?}"
        );
        assert_eq!(store.snapshot().expires_at, Some(win.expires_at));
    }
}
