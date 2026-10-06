// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 服务配置持久化: `<app_data_dir>/server-config.json`。
//!
//! 刻意**不落进 SQLite**（`db.rs`）: 那会动到与本案无关的文件,
//! 而这里只有四个字段, 一个 JSON 文件足够。

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const CONFIG_FILE: &str = "server-config.json";
/// 默认端口。被占用时由服务层自增（见 `mod.rs`）。
pub const DEFAULT_PORT: u16 = 8765;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConfig {
    /// **默认 false**: 服务必须由用户显式开启。规格 §4.3/§11。
    pub enabled: bool,
    pub port: u16,
    pub token: String,
    /// 共享根白名单。**空 = 谁都访问不了**（这是安全的默认值, 不是 bug）。
    pub shares: Vec<PathBuf>,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self { enabled: false, port: DEFAULT_PORT, token: super::auth::new_token(), shares: Vec::new() }
    }
}

impl ServerConfig {
    pub fn load(app_data_dir: &Path) -> Self {
        let p = app_data_dir.join(CONFIG_FILE);
        std::fs::read_to_string(&p)
            .ok()
            .and_then(|s| serde_json::from_str::<Self>(&s).ok())
            .filter(|c| c.token.len() == 64) // token 坏了就重新生成, 不要让服务起不来
            .unwrap_or_default()
    }

    pub fn save(&self, app_data_dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(app_data_dir).map_err(|e| e.to_string())?;
        let s = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(app_data_dir.join(CONFIG_FILE), s).map_err(|e| e.to_string())
    }
}
