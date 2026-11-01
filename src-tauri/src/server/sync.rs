// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 手机「同步」请求的**确认门**状态机。纯内存 + 注入时钟，没有 HTTP / Tauri 类型。
//!
//! 与 `pairing.rs` 同一条分工：手机先 `POST /api/v1/sync/request`，桌面端弹窗，
//! 用户点「允许 / 拒绝」，手机轮询 `GET /api/v1/sync/status` 后才真正推送/拉取。
//!
//! 为什么只有**一个**待确认请求：同步不像配对——配对可能同时有 3 台手机在等，
//! 而同步是单个用户在当前这台手机上的手动动作；同一时刻只显示一条，避免用户点错。
//! 重复请求（手机超时重发）幂等地返回同一张，不会在桌面端堆出第二个弹窗。

use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// 手机发起后 60 秒内没被允许/拒绝就过期。
pub const REQUEST_TTL: Duration = Duration::from_secs(60);
pub const MAX_DEVICE_NAME_LEN: usize = 128;
pub const MAX_PENDING_ITEMS: usize = 1_000_000;

/// 注入的单调时钟。测试里推快它；生产里就是 `Instant::now`。
pub type Clock = Arc<dyn Fn() -> Instant + Send + Sync>;

/// 桌面端/手机共享的三种结论 + 过期。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SyncDecision {
    Pending,
    Approved,
    Denied,
    Expired,
}

impl SyncDecision {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Approved => "approved",
            Self::Denied => "denied",
            Self::Expired => "expired",
        }
    }
}

/// 一条待确认的同步请求（桌面端弹窗的负载）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingSync {
    pub request_id: String,
    pub device_name: String,
    /// 手机认为有多少项要写回电脑（只是给用户看的量级，不是鉴权条件）。
    pub pending_items: usize,
    /// unix 毫秒。
    pub requested_at: u64,
    /// unix 毫秒。
    pub expires_at: u64,
}

/// `POST /api/v1/sync/request` 的请求体。
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncRequest {
    pub device_name: String,
    pub pending_items: usize,
}

/// 202 的响应体。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncChallenge {
    pub request_id: String,
    pub expires_at: u64,
}

/// `GET /api/v1/sync/status` 的响应体。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatus {
    pub request_id: String,
    pub status: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SyncError {
    BadRequest,
    NotFound,
    /// 系统随机数不可用（生成 requestId 失败）—— 不 panic。
    RandomUnavailable,
}

struct Record {
    pending: PendingSync,
    status: SyncDecision,
    expires_at: Instant,
}

/// 同步确认状态机。只保留**最近一条**记录：过期记录留着让手机读到 `expired`，
/// 新请求来了就整条替换（内存有界）。
pub struct SyncStore {
    clock: Clock,
    current: Option<Record>,
}

impl SyncStore {
    pub fn new() -> Self {
        Self::with_clock(Arc::new(Instant::now))
    }

    pub fn with_clock(clock: Clock) -> Self {
        Self {
            clock,
            current: None,
        }
    }

    fn now(&self) -> Instant {
        (self.clock)()
    }

    /// 收下一条同步请求。同一条还活着时幂等返回原记录；已决定/过期则开一条新的。
    pub fn request(&mut self, req: SyncRequest) -> Result<PendingSync, SyncError> {
        if req.device_name.trim().is_empty()
            || req.device_name.chars().count() > MAX_DEVICE_NAME_LEN
            || req.pending_items > MAX_PENDING_ITEMS
        {
            return Err(SyncError::BadRequest);
        }

        let now = self.now();
        if let Some(record) = &self.current {
            if record.status == SyncDecision::Pending && now < record.expires_at {
                // 手机超时重发 / 用户连点：原样返回，桌面端只弹一个框。
                return Ok(record.pending.clone());
            }
        }

        let request_id = super::auth::try_new_hex_id(16)
            .map_err(|_| SyncError::RandomUnavailable)?;
        let requested_at = unix_ms();
        let expires_at_ms = requested_at.saturating_add(REQUEST_TTL.as_millis() as u64);
        let pending = PendingSync {
            request_id,
            device_name: req.device_name.trim().to_string(),
            pending_items: req.pending_items,
            requested_at,
            expires_at: expires_at_ms,
        };
        self.current = Some(Record {
            pending: pending.clone(),
            status: SyncDecision::Pending,
            expires_at: now + REQUEST_TTL,
        });
        Ok(pending)
    }

    /// 某个 requestId 此刻的结论。`None` = 不认识（已被新请求替换或从未存在）。
    pub fn status(&mut self, request_id: &str) -> Option<SyncStatus> {
        let now = self.now();
        let record = self.current.as_mut()?;
        if record.pending.request_id != request_id {
            return None;
        }
        if record.status == SyncDecision::Pending && now >= record.expires_at {
            record.status = SyncDecision::Expired;
        }
        Some(SyncStatus {
            request_id: record.pending.request_id.clone(),
            status: record.status.as_str(),
        })
    }

    /// 桌面端点「允许」。
    pub fn approve(&mut self, request_id: &str) -> Result<(), SyncError> {
        self.decide(request_id, SyncDecision::Approved)
    }

    /// 桌面端点「拒绝」。
    pub fn deny(&mut self, request_id: &str) -> Result<(), SyncError> {
        self.decide(request_id, SyncDecision::Denied)
    }

    fn decide(&mut self, request_id: &str, decision: SyncDecision) -> Result<(), SyncError> {
        let now = self.now();
        let record = self.current.as_mut().ok_or(SyncError::NotFound)?;
        if record.pending.request_id != request_id {
            return Err(SyncError::NotFound);
        }
        if record.status == SyncDecision::Pending && now >= record.expires_at {
            record.status = SyncDecision::Expired;
        }
        match record.status {
            // 已允许/已拒绝时重复点一下是幂等的（桌面端双击不该报错）。
            SyncDecision::Approved | SyncDecision::Denied => Ok(()),
            SyncDecision::Expired => Err(SyncError::NotFound),
            SyncDecision::Pending => {
                record.status = decision;
                Ok(())
            }
        }
    }

    /// 桌面端挂载时补读一次：只返回**还活着**的待确认请求。
    pub fn current(&mut self) -> Option<PendingSync> {
        let now = self.now();
        let record = self.current.as_mut()?;
        if record.status == SyncDecision::Pending && now >= record.expires_at {
            record.status = SyncDecision::Expired;
        }
        if record.status == SyncDecision::Pending {
            Some(record.pending.clone())
        } else {
            None
        }
    }
}

impl Default for SyncStore {
    fn default() -> Self {
        Self::new()
    }
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn store_with(clock: Arc<Mutex<Instant>>) -> SyncStore {
        SyncStore::with_clock(Arc::new(move || *clock.lock().unwrap()))
    }

    fn req(name: &str, items: usize) -> SyncRequest {
        SyncRequest {
            device_name: name.to_string(),
            pending_items: items,
        }
    }

    #[test]
    fn a_request_is_idempotent_while_pending() {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let mut store = store_with(clock);
        let first = store.request(req("Pixel", 3)).unwrap();
        let again = store.request(req("Pixel", 3)).unwrap();
        assert_eq!(first.request_id, again.request_id, "重发不许开第二个弹窗");
    }

    #[test]
    fn approve_and_deny_are_visible_to_the_phone() {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let mut store = store_with(clock);
        let p = store.request(req("Pixel", 1)).unwrap();
        assert_eq!(store.status(&p.request_id).unwrap().status, "pending");
        store.approve(&p.request_id).unwrap();
        assert_eq!(store.status(&p.request_id).unwrap().status, "approved");

        let p2 = store.request(req("Pixel", 1)).unwrap();
        store.deny(&p2.request_id).unwrap();
        assert_eq!(store.status(&p2.request_id).unwrap().status, "denied");
    }

    #[test]
    fn a_request_expires_after_the_ttl() {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let mut store = store_with(clock.clone());
        let p = store.request(req("Pixel", 1)).unwrap();
        *clock.lock().unwrap() += REQUEST_TTL + Duration::from_secs(1);
        assert_eq!(store.status(&p.request_id).unwrap().status, "expired");
        assert!(store.approve(&p.request_id).is_err(), "过期后不能再允许");
        assert!(store.current().is_none(), "过期请求不该继续弹窗");
    }

    #[test]
    fn a_new_request_replaces_an_expired_one() {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let mut store = store_with(clock.clone());
        let first = store.request(req("Pixel", 1)).unwrap();
        *clock.lock().unwrap() += REQUEST_TTL + Duration::from_secs(1);
        let second = store.request(req("Pixel", 2)).unwrap();
        assert_ne!(first.request_id, second.request_id);
        assert_eq!(store.status(&first.request_id), None);
        assert_eq!(store.status(&second.request_id).unwrap().status, "pending");
    }

    #[test]
    fn bad_input_is_rejected_before_touching_randomness() {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let mut store = store_with(clock);
        assert_eq!(store.request(req("", 1)), Err(SyncError::BadRequest));
        assert_eq!(
            store.request(req(&"x".repeat(MAX_DEVICE_NAME_LEN + 1), 1)),
            Err(SyncError::BadRequest)
        );
        assert_eq!(
            store.request(req("Pixel", MAX_PENDING_ITEMS + 1)),
            Err(SyncError::BadRequest)
        );
    }
}
