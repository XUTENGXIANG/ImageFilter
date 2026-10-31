// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! mDNS 广播: 让同一局域网里的安卓用系统 `NsdManager` 发现"这台电脑"。
//!
//! 契约(计划 §0.1, 三个工作流都按这一份写):
//!
//! | 项 | 值 |
//! |---|---|
//! | 服务类型 | `_imagefilter._tcp.local.` |
//! | 实例名 | `ImageFilter <hostname>` |
//! | host | `<hostname>.local.` |
//! | TXT | `v=1`、`id=<desktopId>`、`name=<hostname>`、`pair=open|closed` |
//!
//! ── 为什么 TXT 里**绝不能**有 token(这不是疏忽, 是边界) ──────────────────────
//! mDNS 是**完全公开**的: 同一局域网里任何设备都能查询并读到 TXT, 不需要任何凭证。token 一旦
//! 进了 TXT, "只有配对的手机能访问"这条边界当场消失 —— 而且不会有任何报错, 只是看起来一切
//! 正常。所以 `pair_txt` 的入参里**没有 token 这个位置**(`AdvertiseInfo` 也没有),
//! 想加也加不进去: "批准之后手机在 HTTP 里拿 token"(与今天扫码/手输同一边界)是唯一的通路。
//!
//! ── `pair=open|closed` 的语义 ─────────────────────────────────────────────
//! 它**只是提示**, 不是权限: 窗口关着时 `POST /api/v1/pair/request` 仍然会回
//! `409 pairing_closed`(真正的闸门在状态机里)。安卓据此把设备显示成"可配对"/"请先在电脑上
//! 点配对新设备"; 一台恶意设备完全可以无视它 —— 那没关系, 它得到的还是 409。
//!
//! ── 生命周期 ──────────────────────────────────────────────────────────────
//! 广播**只在服务真的监听时存在**(`server::start` bind 成功后开始, `server::stop` 停止)。
//! 应用退出 = 没有广播, 这是产品事实(计划 §8 第 8 条)。网络类别为公用/读不出来时
//! `network::should_refuse_to_start` 会给理由, 那种情况下**连广播也不做**(§0.1) ——
//! 服务本身若经用户确认后起来了, 手输/扫码那条路照旧可用。

use super::network;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::sync::Mutex as StdMutex;

/// 服务类型。mdns-sd 要带 `.local.` 后缀; 安卓 `NsdManager` 用的是 `_imagefilter._tcp.`。
pub const SERVICE_TYPE: &str = "_imagefilter._tcp.local.";
/// TXT 里 `v=` 的值(将来协议变更时加一, 安卓据此决定认不认)。
pub const TXT_VERSION: &str = "1";

/// 广播要用到的身份快照。**没有 token 字段**(见模块头)。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdvertiseInfo {
    /// mDNS TXT 的 `id=`。落盘的 `ServerConfig::desktop_id`, 重启后稳定。
    pub desktop_id: String,
    /// 主机名(已清洗成 DNS 标签安全的形状)。
    pub host: String,
    /// 本机的局域网 IPv4 候选(来自 `network::detect_hints().lan`, 复用同一份探测结果 ——
    /// 不在这里再写一套网卡枚举)。
    pub ips: Vec<String>,
}

impl AdvertiseInfo {
    /// 从网络探测快照拼一份广播身份。
    pub fn from_hints(desktop_id: &str, hints: &network::NetHints) -> Self {
        Self {
            desktop_id: desktop_id.to_string(),
            host: hostname(),
            ips: hints
                .lan
                .iter()
                .map(|l| l.address.trim().to_string())
                .filter(|a| !a.is_empty())
                .collect(),
        }
    }

    /// 实例名: `ImageFilter <hostname>`(§0.1)。
    pub fn instance(&self) -> String {
        format!("ImageFilter {}", self.host)
    }

    /// DNS 记录里的 host 名: `<hostname>.local.`(§0.1)。
    pub fn host_fqdn(&self) -> String {
        format!("{}.local.", self.host)
    }
}

/// mDNS TXT 的属性表。**唯一**允许的四个键: `v` / `id` / `name` / `pair`。
///
/// 返回值里只有公开信息(计划 §0.1): 版本、桌面端 id、主机名、窗口开关状态。
/// 任何"顺手多带一个字段"的想法都请先读模块头那段 —— 局域网里每个设备都能读到它。
pub fn pair_txt(desktop_id: &str, name: &str, pair_open: bool) -> Vec<(String, String)> {
    vec![
        ("v".to_string(), TXT_VERSION.to_string()),
        ("id".to_string(), txt_value(desktop_id)),
        ("name".to_string(), txt_value(name)),
        ("pair".to_string(), if pair_open { "open" } else { "closed" }.to_string()),
    ]
}

/// TXT 值的清洗: 去掉控制字符, 并按 DNS TXT 单条 255 字节的上限再留足余量。
///
/// 主机名理论上可能包含空格/点(用户改过机器名), 而这两个在 DNS 标签里有特殊含义 ——
/// 清成 `-` 比让 `ServiceInfo::new` 在运行时抛错好: 广播是尽力而为, 不该因为一个怪主机名
/// 就让配对窗口打不开。
fn txt_value(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_control())
        .take(120)
        .collect::<String>()
}

/// 本机主机名, 已清洗成 DNS 标签安全的形状。
///
/// Windows 用 `COMPUTERNAME`(系统总是设置它); macOS/Linux 试 `HOSTNAME`(shell 里通常有,
/// GUI 进程可能没有); 都拿不到就回 `imagefilter` —— **不 panic、不阻塞**。
/// 这与 `network.rs` 的纪律一致: 探测失败只该降级, 不该把桌面端弄坏。
pub fn hostname() -> String {
    let raw = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_default();
    normalize_hostname(&raw)
}

/// 把任意主机名收成 `[A-Za-z0-9-]` 组成的 DNS 标签(<= 63 字节)。
fn normalize_hostname(raw: &str) -> String {
    let mut out = String::new();
    for c in raw.trim().chars() {
        if c.is_ascii_alphanumeric() || c == '-' {
            out.push(c);
        } else if c == ' ' || c == '_' || c == '.' {
            out.push('-');
        }
        // 其余(非 ASCII / 标点)直接丢掉 —— DNS 标签只认这些。
    }
    let out = out.trim_matches('-').to_string();
    if out.is_empty() {
        return "imagefilter".to_string();
    }
    // 只含 ASCII, 按字节截断是安全的; DNS 单个标签的上限是 63。
    out[..out.len().min(63)].to_string()
}

/// 广播端。**类型擦除的一层**: 与 `server::Notify` 同一条纪律 —— 签名里没有 Tauri 类型,
/// 而且测试注入 `RecordingAdvertiser` 时不会真的起组播。
///
/// 所有方法都是"尽力而为": 返回 `Err` 只表示**这一次没广播成**, 调用方(`server::start`)
/// 会忽略它并继续提供服务。mDNS 是增强, 不是服务能否工作的前提。
pub trait Advertiser: Send + Sync {
    /// 开始广播。`port` 是**真的绑上的**那个端口(`server::start` 的返回值)。
    /// 已在广播时再次调用 = 用新信息重新注册(同一实例名)。
    fn start(&self, port: u16, info: &AdvertiseInfo, pair_open: bool) -> Result<(), String>;
    /// 只更新 TXT 里的 `pair=open|closed`。没在广播时是 no-op。
    fn set_pair_open(&self, open: bool) -> Result<(), String>;
    /// 停止广播。幂等。
    fn stop(&self);
}

/// 测试 / 无广播场景用的空实现: 每个方法都成功且什么都不做。
///
/// `ServerConfig::into_state()` 默认用它 —— 于是单测与契约测试**永远不会**真的起组播。
/// 生产路径由 `into_state_with_notifiers` 换成 [`MdnsAdvertiser`]。
#[derive(Debug, Default)]
pub struct DisabledAdvertiser;

impl Advertiser for DisabledAdvertiser {
    fn start(&self, _port: u16, _info: &AdvertiseInfo, _pair_open: bool) -> Result<(), String> {
        Ok(())
    }
    fn set_pair_open(&self, _open: bool) -> Result<(), String> {
        Ok(())
    }
    fn stop(&self) {}
}

/// 真实现: `mdns-sd` 的 `ServiceDaemon`。
///
/// `ServiceDaemon` 在**第一次 `start`** 时才创建(构造 `MdnsAdvertiser` 本身不碰网络),
/// 这一点很重要: `ServerState` 在设置面板打开、默认关闭服务时就会被构造出来, 那一步
/// 不该开任何一个 socket。
pub struct MdnsAdvertiser {
    running: StdMutex<Option<Running>>,
}

struct Running {
    daemon: ServiceDaemon,
    fullname: String,
    port: u16,
    info: AdvertiseInfo,
    pair_open: bool,
}

impl Default for MdnsAdvertiser {
    fn default() -> Self {
        Self::new()
    }
}

impl MdnsAdvertiser {
    pub fn new() -> Self {
        Self { running: StdMutex::new(None) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Running>> {
        // 中毒也照用(与 `server::lifecycle()` 同一条纪律): 里面是一个"要不要广播"的开关,
        // 拿不到锁就永远停不掉广播 —— 那比拿到一个可能不完整的状态更糟。
        self.running.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// 已停止的收尾: 先注销(让局域网尽快收到 goodbye), 再关守护线程。
    /// 两个返回值都**忽略**: 广播的收尾失败没有可执行的补救动作, 也不该 panic。
    fn shutdown_locked(slot: &mut Option<Running>) {
        if let Some(run) = slot.take() {
            let _ = run.daemon.unregister(&run.fullname);
            let _ = run.daemon.shutdown();
        }
    }
}

impl Advertiser for MdnsAdvertiser {
    fn start(&self, port: u16, info: &AdvertiseInfo, pair_open: bool) -> Result<(), String> {
        let mut slot = self.lock();
        // 先停掉旧的一台: 端口/身份变了还留着旧记录, 局域网会看到两条指向不同端口的服务。
        Self::shutdown_locked(&mut slot);
        // 没有任何局域网地址可公布时不广播: 手机即使发现了也连不上, 那比"发现不到"更糟
        // (用户会照着点, 然后得到一句莫名其妙的连接失败)。
        if info.ips.is_empty() || info.host.is_empty() {
            return Ok(());
        }
        let daemon = ServiceDaemon::new().map_err(|e| format!("mDNS 守护线程启动失败: {e}"))?;
        let service = service_info(info, port, pair_open)?;
        let fullname = service.get_fullname().to_string();
        if let Err(e) = daemon.register(service) {
            let _ = daemon.shutdown();
            return Err(format!("mDNS 注册失败: {e}"));
        }
        *slot = Some(Running {
            daemon,
            fullname,
            port,
            info: info.clone(),
            pair_open,
        });
        Ok(())
    }

    fn set_pair_open(&self, open: bool) -> Result<(), String> {
        let mut slot = self.lock();
        let Some(run) = slot.as_mut() else {
            return Ok(());
        };
        if run.pair_open == open {
            return Ok(());
        }
        // mdns-sd 的文档: 用同一份 `ServiceInfo`(同实例名)再 `register` 一次就是"重新广播"。
        // 这正是 TXT 更新的做法 —— 不必先 unregister(那会在局域网里闪出一段"服务消失")。
        let service = service_info(&run.info, run.port, open)?;
        run.daemon.register(service).map_err(|e| format!("mDNS TXT 更新失败: {e}"))?;
        run.pair_open = open;
        Ok(())
    }

    fn stop(&self) {
        let mut slot = self.lock();
        Self::shutdown_locked(&mut slot);
    }
}

/// 由身份快照 + 端口 + 窗口状态构造一条 `ServiceInfo`。
fn service_info(
    info: &AdvertiseInfo,
    port: u16,
    pair_open: bool,
) -> Result<ServiceInfo, String> {
    let txt = pair_txt(&info.desktop_id, &info.host, pair_open);
    ServiceInfo::new(
        SERVICE_TYPE,
        &info.instance(),
        &info.host_fqdn(),
        info.ips.as_slice(),
        port,
        txt.as_slice(),
    )
    .map_err(|e| format!("mDNS 服务信息不合法: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// **TXT 里绝不能有 token。** 这条是安全边界(见模块头): 局域网里任何设备都能读到 TXT。
    ///
    /// 断言分两层: 键集合恰好是 `v/id/name/pair`; 以及没有任何一个值长得像一枚 token
    /// (64 位十六进制)。后者是给"将来有人往 TXT 里塞别的东西"准备的 —— 只查键名的话,
    /// 换一个键名就绕过去了。
    #[test]
    fn txt_has_no_token() {
        let txt = pair_txt(&"ab".repeat(16), "DESKTOP-96B8FMF", true);
        let keys: Vec<&str> = txt.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(keys, ["v", "id", "name", "pair"], "TXT 只许有这四个键");
        for (k, v) in &txt {
            assert!(!k.eq_ignore_ascii_case("token"), "TXT 里不许出现 token 键");
            assert!(
                !(v.len() == 64 && v.chars().all(|c| c.is_ascii_hexdigit())),
                "TXT 的值 {k}={v} 长得像一枚 token —— token 绝不进 mDNS"
            );
        }
    }

    #[test]
    fn txt_sets_pair_open_and_closed() {
        let open = pair_txt("id", "host", true);
        let closed = pair_txt("id", "host", false);
        assert_eq!(open.iter().find(|(k, _)| k == "pair").map(|(_, v)| v.as_str()), Some("open"));
        assert_eq!(
            closed.iter().find(|(k, _)| k == "pair").map(|(_, v)| v.as_str()),
            Some("closed")
        );
    }

    #[test]
    fn txt_has_version_and_id() {
        let txt = pair_txt("0123456789abcdef0123456789abcdef", "DESKTOP-1", false);
        assert_eq!(txt.iter().find(|(k, _)| k == "v").map(|(_, v)| v.as_str()), Some("1"));
        assert_eq!(
            txt.iter().find(|(k, _)| k == "id").map(|(_, v)| v.as_str()),
            Some("0123456789abcdef0123456789abcdef")
        );
        assert_eq!(
            txt.iter().find(|(k, _)| k == "name").map(|(_, v)| v.as_str()),
            Some("DESKTOP-1")
        );
    }

    /// 实例名与 host 的形状(§0.1): `ImageFilter <hostname>` / `<hostname>.local.`。
    #[test]
    fn instance_and_host_follow_the_contract() {
        let info = AdvertiseInfo {
            desktop_id: "id".into(),
            host: "DESKTOP-96B8FMF".into(),
            ips: vec!["192.168.1.20".into()],
        };
        assert_eq!(info.instance(), "ImageFilter DESKTOP-96B8FMF");
        assert_eq!(info.host_fqdn(), "DESKTOP-96B8FMF.local.");
    }

    /// 主机名清洗: 怪名字不许让广播构造失败, 空名字必须有兜底。
    #[test]
    fn hostname_is_normalized_to_a_dns_label() {
        assert_eq!(normalize_hostname("DESKTOP-96B8FMF"), "DESKTOP-96B8FMF");
        assert_eq!(normalize_hostname(" My PC "), "My-PC");
        assert_eq!(normalize_hostname("a.b_c"), "a-b-c");
        assert_eq!(normalize_hostname(""), "imagefilter");
        assert_eq!(normalize_hostname("!!!"), "imagefilter");
        assert!(normalize_hostname(&"x".repeat(200)).len() <= 63);
    }

    /// 空实现必须真的什么都不做, 且所有方法成功 —— 测试路径依赖这一点。
    #[test]
    fn disabled_advertiser_is_a_no_op() {
        let a = DisabledAdvertiser;
        let info = AdvertiseInfo { desktop_id: "id".into(), host: "h".into(), ips: vec![] };
        assert!(a.start(8765, &info, true).is_ok());
        assert!(a.set_pair_open(false).is_ok());
        a.stop();
    }
}
