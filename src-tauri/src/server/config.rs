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
    ///
    /// 每个字段都写了 `#[serde(default ...)]`: 少一个字段**不许**把整份配置丢掉。
    /// 旧写法(容器上一个 default 都没有)的后果很具体 —— 用户照文档手改配置、漏了 `token`
    /// 这一行, 整份配置反序列化失败 → `load()` 回落 `default()` → 他的 `enabled` 与
    /// `shares` 一起人间蒸发, 服务从此默认关闭而用户不知道为什么。
    #[serde(default)]
    pub enabled: bool,
    /// ⚠️ 这里**不能**写裸的 `#[serde(default)]`: `u16` 的类型默认值是 **0**, 那会让
    /// "配置里漏了 port" 变成"端口 0 = 让系统随便挑一个" —— 手机上的二维码就永远对不上了。
    /// 字段默认值必须是 `DEFAULT_PORT`。
    #[serde(default = "default_port")]
    pub port: u16,
    /// 缺 `token` 时给空串 —— 空 token 是 **fail-closed** 的(`start` 拒绝启动、
    /// 鉴权层显式拒绝), 而且 `boot`/`ensure_token` 会在起服务前重新生成一枚并落盘。
    #[serde(default)]
    pub token: String,
    /// 共享根白名单。**空 = 谁都访问不了**（这是安全的默认值, 不是 bug）。
    #[serde(default)]
    pub shares: Vec<PathBuf>,
}

/// `ServerConfig::port` 的 serde 默认值。单独写成函数是因为 `#[serde(default)]` 用的是
/// **字段类型**的 `Default`(u16 → 0), 而这里要的是 `DEFAULT_PORT`。
fn default_port() -> u16 {
    DEFAULT_PORT
}

impl Default for ServerConfig {
    /// ⚠️ **这里绝不生成 token**（Task 8 的启动路径安全前提）。
    ///
    /// 旧实现在这里调 `auth::new_token()`，而它内部是 `getrandom(...).expect(...)`。
    /// `load()` 又会把"配置缺失/损坏"回落到 `default()` —— 于是**一次随机数失败就等于
    /// 整个桌面端崩在启动路径上**（用户第一约束: 别把桌面端弄坏）。
    ///
    /// 现在 token 只在**真正要起服务时**生成并落盘（`server::boot` / `set_server_enabled`），
    /// 走可失败的 `auth::try_new_token()`，失败往上抛 `Err` → 服务不启动、桌面端照常。
    /// 空 token 是 **fail-closed**：`start()` 拒绝空 token，鉴权层也显式拒绝（见
    /// `routes::check_auth`），所以"还没生成 token"绝不等于"关掉鉴权"。
    fn default() -> Self {
        Self { enabled: false, port: DEFAULT_PORT, token: String::new(), shares: Vec::new() }
    }
}

/// token 是否"可用"。
///
/// **原 `load()` 里那条 `.filter(|c| c.token.len() == 64)` 的语义搬到了这里** —— 那条
/// filter 当时的含义是"token 坏了就丢弃整份配置、回落到 `Default`，而 `Default` 会顺手
/// 重新生成一枚"。`Default` 不再生成 token 之后，"token 坏了怎么办"必须**显式问一次**，
/// 不能再靠那条 filter 静默兜住：它今天会把用户已经配好的 `shares` / `port` / `enabled`
/// 一起丢掉，而那正是"服务明明开着却起不来"的来源。
///
/// 判据与旧 filter 一致（64 字符），另加十六进制约束 —— 长度对但不是十六进制的东西
/// 只会让手机永远配不上对，早点重新生成比让用户去查更好。
pub fn token_is_valid(token: &str) -> bool {
    token.len() == 64 && token.bytes().all(|b| b.is_ascii_hexdigit())
}

impl ServerConfig {
    /// 读配置。**任何失败都只回落到 `default()`（enabled=false, token 为空），绝不 panic。**
    ///
    /// 不再过滤 token：坏 token 由调用方（`server::ensure_token`）在起服务前重新生成并落盘，
    /// 这样用户的 `shares` / `port` 不会被一次 token 损坏连带清空。
    pub fn load(app_data_dir: &Path) -> Self {
        let p = app_data_dir.join(CONFIG_FILE);
        std::fs::read_to_string(&p)
            .ok()
            .and_then(|s| serde_json::from_str::<Self>(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, app_data_dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(app_data_dir).map_err(|e| e.to_string())?;
        let s = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(app_data_dir.join(CONFIG_FILE), s).map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 每个测试一个独立目录。**绝不碰 `%APPDATA%`** —— 那是用户真实的配置。
    fn tmp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("ifcfg_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    /// **"服务默认关闭"的第一道钉子**: 默认配置必须是 enabled=false。
    ///
    /// 同时钉住"默认配置**不在启动路径上摸随机数**"—— 旧实现这里会 `new_token()`
    /// （内部 `getrandom(...).expect(...)`），于是配置缺失就能把桌面端崩在启动上。
    /// 把 `enabled` 翻成 true 或者把 `token` 改回 `auth::new_token()`，这条都会红。
    #[test]
    fn default_is_disabled_and_carries_no_token() {
        let cfg = ServerConfig::default();
        assert!(!cfg.enabled, "服务必须默认关闭(规格 §4.3/§11)");
        assert_eq!(cfg.port, DEFAULT_PORT);
        assert!(cfg.token.is_empty(), "默认配置不许生成 token —— 启动路径不许碰随机数");
        assert!(!token_is_valid(&cfg.token), "空 token 必须被判定为不可用(fail-closed)");
        assert!(cfg.shares.is_empty(), "共享根默认空 = 谁都访问不了(安全默认值)");
    }

    /// 全新安装(配置文件不存在)走的就是 `default()`：不 panic、不开服务、token 为空。
    #[test]
    fn missing_config_file_is_disabled_and_never_panics() {
        let dir = tmp_dir("missing");
        let cfg = ServerConfig::load(&dir);
        assert!(!cfg.enabled);
        assert!(cfg.token.is_empty());
        assert!(!token_is_valid(&cfg.token));
    }

    /// 配置文件是坏 JSON 时同理 —— `load` 只有回落, 没有 panic。
    #[test]
    fn corrupt_config_file_falls_back_to_default() {
        let dir = tmp_dir("corrupt");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        std::fs::write(dir.join(CONFIG_FILE), b"{ this is not json").expect("写坏配置");
        let cfg = ServerConfig::load(&dir);
        assert!(!cfg.enabled);
        assert!(cfg.token.is_empty());
    }

    /// **"坏 token 不该连带丢掉用户的配置"** —— 这条钉住的是从 `load()` 里搬走的那条
    /// `.filter(token.len() == 64)` 的语义: 坏 token 仍要被识别为"不可用"(从而在起服务前
    /// 重新生成), 但 `enabled` / `port` / `shares` 必须原样留下。
    #[test]
    fn short_token_is_flagged_but_the_rest_of_the_config_survives() {
        let dir = tmp_dir("shorttoken");
        std::fs::create_dir_all(&dir).expect("建临时目录");
        let raw = serde_json::json!({
            "enabled": true,
            "port": 8123,
            "token": "abc",
            "shares": ["C:\\照片"],
        });
        std::fs::write(dir.join(CONFIG_FILE), raw.to_string()).expect("写配置");

        let cfg = ServerConfig::load(&dir);
        assert!(!token_is_valid(&cfg.token), "短 token 必须被判为不可用");
        assert!(cfg.enabled, "用户的开关不该因为 token 坏了被清掉");
        assert_eq!(cfg.port, 8123);
        assert_eq!(cfg.shares, vec![PathBuf::from("C:\\照片")]);
    }

    /// **手改配置漏字段不许把整份配置丢掉**(本轮复审的第 3 条 minor)。
    ///
    /// 现场是这样的: 文档让用户手改 `server-config.json`, 而他漏了(或写错)某一行。
    /// 没有 `#[serde(default)]` 时整份反序列化失败 → `load()` 回落 `default()` →
    /// `enabled` 与 `shares` 一起消失, 服务从此默认关闭, 用户完全看不出原因。
    ///
    /// 三个字段各缺一次, 每个都断言"剩下的原样保留 + 缺的那个拿到**正确的**默认值"。
    /// 特别是 `port`: 裸 `#[serde(default)]` 会给 0(让系统随便挑端口) —— 这条就是它的靶子。
    #[test]
    fn a_config_missing_any_single_field_keeps_the_rest() {
        let dir = tmp_dir("missingfield");
        std::fs::create_dir_all(&dir).expect("建临时目录");

        // ① 缺 `token`(文档建议手改的那一行): enabled/shares/port 必须原样保留。
        std::fs::write(
            dir.join(CONFIG_FILE),
            serde_json::json!({
                "enabled": true,
                "port": 8123,
                "shares": ["C:\\照片"],
            })
            .to_string(),
        )
        .expect("写配置");
        let cfg = ServerConfig::load(&dir);
        assert!(cfg.enabled, "漏了 token 不许把用户的开关一起丢掉");
        assert_eq!(cfg.port, 8123);
        assert_eq!(cfg.shares, vec![PathBuf::from("C:\\照片")]);
        assert!(cfg.token.is_empty(), "缺 token → 空串(fail-closed, 起服务前会重新生成)");

        // ② 缺 `port`: 必须是 DEFAULT_PORT, **不是 0**(0 = 让系统随便挑端口)。
        std::fs::write(
            dir.join(CONFIG_FILE),
            serde_json::json!({ "enabled": true, "token": "a".repeat(64) }).to_string(),
        )
        .expect("写配置");
        let cfg = ServerConfig::load(&dir);
        assert_eq!(cfg.port, DEFAULT_PORT, "漏了 port 必须回落到默认端口, 不能是 0");
        assert!(cfg.enabled);

        // ③ 缺 `enabled`: 默认关闭(安全默认值)。一个空对象也不许 panic。
        std::fs::write(dir.join(CONFIG_FILE), "{}").expect("写配置");
        let cfg = ServerConfig::load(&dir);
        assert!(!cfg.enabled, "缺 enabled 必须默认关闭");
        assert_eq!(cfg.port, DEFAULT_PORT);
        assert!(cfg.token.is_empty());
        assert!(cfg.shares.is_empty());
    }

    /// `token_is_valid` 的边界。长度对但不是十六进制 → 不可用
    /// (否则手机永远配不上对, 而用户看不出哪里错)。
    #[test]
    fn token_validity_boundaries() {
        assert!(!token_is_valid(""));
        assert!(!token_is_valid(&"a".repeat(63)));
        assert!(!token_is_valid(&"a".repeat(65)));
        assert!(!token_is_valid(&"z".repeat(64)), "非十六进制字符必须判为不可用");
        assert!(token_is_valid(&"a".repeat(64)));
        assert!(token_is_valid(&"AB".repeat(32)), "大写十六进制也是合法的(生成端给的是小写)");
    }

    /// 落盘/读回是同一条 `CONFIG_FILE`（`server::persist` 与 `load` 必须对上）。
    #[test]
    fn save_then_load_round_trips() {
        let dir = tmp_dir("roundtrip");
        let cfg = ServerConfig {
            enabled: true,
            port: 9001,
            token: "b".repeat(64),
            shares: vec![PathBuf::from("C:\\pics")],
        };
        cfg.save(&dir).expect("落盘");
        let back = ServerConfig::load(&dir);
        assert!(back.enabled);
        assert_eq!(back.port, 9001);
        assert_eq!(back.token, "b".repeat(64));
        assert_eq!(back.shares, vec![PathBuf::from("C:\\pics")]);
    }
}
