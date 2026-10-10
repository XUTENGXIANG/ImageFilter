// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 本机"手机可能能用的访问地址"探测 + 公用网络防护 —— **只做推荐, 不做保证**。
//!
//! ── 为什么值得做(以及为什么它现在是**安全**而不是"顺手") ──────────────────────
//! 手机端拿不到电脑的网卡信息, 而"填哪个地址"是同一 WiFi 下最容易劝退的一步; 桌面端看得见
//! 自己的网卡, 所以可以直接把候选地址列出来(规格 §4.2)。而 2026-10-07 的 LAN-only 决策把
//! 隧道那条路整体删掉之后, **"不做 TLS"的代价变高了**: 边界从"局域网或加密隧道"缩成"只有
//! 局域网", 于是在**不可信的 WiFi**(咖啡厅/办公室/酒店)上, 同一网段的任何设备不但能连上端口,
//! 还能明文嗅探到配对码。这就是规格 §4.3 把下面两条从"建议"升级成"硬约束"的原因:
//!   1. 网络类别是 `Public` 时**不自动启动**(`should_refuse_to_start`), 要用户**显式确认**
//!      且这次确认会被记进状态(`server::ServerStatus::public_ack_at`);
//!   2. 检测到非局域网的 IPv4 时**只警告** —— 本模块**不提供**任何端口转发/公网暴露/打洞
//!      能力, 也不提供"一键"入口(规格 §4.3 第二段)。
//!
//! ── 类型纪律(本项目已踩过的坑, 不是洁癖) ─────────────────────────────────────
//! 本模块必须保持**纯数据 + std/OS 调用**: 绝不许把 `tauri::AppHandle` / 窗口 / `Emitter`
//! 这类类型放进这里的任何结构体或函数签名。原因与 `mod.rs` 中 `Notify` 那段完全相同 ——
//! `#[cfg(test)]` 单测与 `tests/` 集成测试都会把 `server` 编进各自的**测试 exe**, 而测试 exe
//! 没有 Tauri 给正式程序嵌的 `Microsoft.Windows.Common-Controls` v6 清单: 一旦 Tauri 的
//! 窗口/WebView 层被链接进来, 测试进程会在**加载时**就死(`STATUS_ENTRYPOINT_NOT_FOUND`),
//! 而不是"测试变红"。所以这里只允许出现: 纯数据类型、`std::net`、以及 `windows` crate 的
//! Win32 调用(`windows` crate 本来就在链上, 见下)。
//!
//! ── 网络类别从哪来(以及**有没有**退化) ──────────────────────────────────────
//! 类别走 Windows `NetworkListManager`(NLM)COM 接口, 取 `NLM_NETWORK_CATEGORY`
//! (0=公用 Public, 1=专用 Private, 2=域 DomainAuthenticated)。**没有引入任何新 crate**:
//! 本仓库本来就直接依赖 `windows` 0.62.2(见 `Cargo.toml` 里 Windows 专属依赖那一段, Phase 7
//! 就在用它读注册表/找 LrC), 本次只是**多开了四个 feature**
//! (`Win32_Networking_NetworkListManager` + `Win32_NetworkManagement_IpHelper` +
//! `Win32_NetworkManagement_Ndis` + `Win32_Networking_WinSock`) —— 同一个 crate 的 feature,
//! 不是新依赖。所以**没有退化**: 类别是真的读出来的。
//!
//! 只有"读不出来"这一种情况才**没有"类别"这个事实** —— 而它的处理与"读到 Public"一样严:
//! `Unknown`(COM 不可用 / 查询失败 / netprofm 被停 / 一张网卡都没连)时, `should_refuse_to_start`
//! **同样要求用户显式确认**, 理由里说清是"读不到类别"。
//!
//! ⚠️ **这里曾经是 fail-open 的**(Fix 1, 本轮修复): 旧规则是"`Unknown` **且**本机有非局域网的
//! IPv4 才拒绝"。但咖啡馆/办公室/酒店那类共享 WiFi 发的**通常就是私有租约**(10.x / 192.168.x),
//! 所以"没有非局域网地址"根本不能证明这条网络可信 —— 于是"类别读不出来"这个组合(恰恰是
//! netprofm 停掉/COM 不可用时会出现的那个组合)会被静默放行, 而在**自动启动**那条路上没有人
//! 在场能看到警告。现在唯一的放行条件是**读到** `Private`(含域): 只有正面读数才算可信。
//!
//! ── 类别是"哪张网卡"的? (一个必须说清的语义) ────────────────────────────────
//! NLM 的类别是**每个网络**的, 而一台机器可以同时连着好几个网络(有线+无线+虚拟网卡)。
//! 这里取的是**最危险的那一个**: 只要有任何一张**已连接**的网卡属于 `Public`, `profile`
//! 就是 `Public`。理由是 fail-closed —— 服务绑的是 `0.0.0.0`, 只要有一条公用网段的路径能到达
//! 它, 就该让用户知道。代价是**可能误报**(例如某个虚拟网卡/VPN 的配置文件被 Windows 标成了
//! 公用, 而真正在用的无线是专用): 用户会看到一次警告并需要确认一次。这个方向是**故意**选的:
//! 多问一次的代价, 远小于"在咖啡厅里静默开着一个明文端口的服务"。
//!
//! ── 候选地址的边界(绝不把公网地址列进候选) ─────────────────────────────────
//! · `is_private_v4`(10/8、172.16/12、192.168/16)→ 进 `lan`, 配对时填的就是它;
//! · 回环 / 链路本地(169.254/16) / 组播 / 未指定 / 广播 / 240-4 保留段 →
//!   **两个列表都不进**: 它们既不是"手机能用来连我的地址", 也不是"局域网外能访问我的地址";
//! · 其余一切**非私有**地址(含真正的公网 IPv4, 也含 `100.64/10` 这一档 CGNAT/Tailscale 用的
//!   共享地址)→ 进 `public_ipv4`, **只用于警告**。为什么连 CGNAT 也算: 它不是公网可路由的,
//!   但它同样意味着"这台机器上有一条不是局域网的访问路径", 值得让用户看见 —— 所以界面文案
//!   说的是"不是局域网的地址", 而不是"公网 IP"(不把话说得比实际强)。
//!
//! ── 候选列表的**四条丢弃规则**(不是"只排序") ────────────────────────────────
//! 模块头的第一句是"只做推荐, 不做保证", 但那说的是**推荐**这一层: 界面不替用户挑那一条。
//! `build_hints` 自己**是有丢弃的**, 四条逐条列在这里(每条都有测试钉住), 因为"这条地址此刻
//! 不构成一条手机能用的访问路径":
//!   ① 网卡没 Up(`OperStatus != IfOperStatusUp`)—— 那个地址此刻连不上;
//!   ② 回环(`127/8`)—— 只有本机能用, 手机用不了;
//!   ③ 链路本地(169.254/16) / 组播 / 未指定 / 广播 / `240/4` 保留段 / `198.18-19` 基准段 ——
//!      两个列表都不进(见上面"候选地址的边界");
//!   ④ 同一个地址被重复报告 —— 只列一行(去重)。
//!
//! 虚拟网卡(VMware / VirtualBox / WSL / Hyper-V)的私有地址**不**在这四条里: 那多半不是用户该填
//! 的那个, 但这里**不猜** —— 网卡名一并给出("WLAN"/"以太网"/"vEthernet (WSL)"), 无线与有线排在
//! 前面, 让用户自己判断。规则④还有一条细则: 同一地址被两张网卡同时报告时, 留**更像真网卡**的
//! 那张的名字(无线 → 有线 → 其它), 地址仍然只出现一次、一行都不丢 —— 见 `if_type_rank`。

use serde::Serialize;
use std::net::Ipv4Addr;

/// `NetHints::firewall_hint` 今天唯一可能的取值(见下面的字段说明)。
pub const FIREWALL_HINT_ALLOW_INBOUND_PRIVATE: &str = "allowInboundPrivateProfile";

/// **两道拒绝理由里都必定出现的一句** —— 面板据此认出"这一次启动失败是网络防护拦的"
/// (而不是端口被占、token 坏了之类别的失败), 从而在**探测快照整个拿不到**时仍然摆出二次确认
/// 入口。没有它, 用户手上的开关会变成"点了就被拒、却找不到那个确认按钮"(见 minor ④)。
///
/// 为什么用"理由里的一句人话"当标记, 而不是给 `ServerStatus` 另加一个只给前端看的布尔字段:
/// 理由是用户唯一能据以行动的东西, 而它**已经**送到面板了。再加一个字段等于同一件事有两份真相
/// (而且两份会各自漂移)。代价是这句话改字时必须同步面板 —— 所以下面有一条测试把
/// "两句拒绝理由都含这个标记"钉住(改文案就会红, 而不是悄悄让那个开关点不动)。
pub const GUARD_REFUSAL_MARKER: &str = "确认要在";

/// 本机"手机可能能用的地址"与网络类别的一次快照。
///
/// 字段形状按 M4′ Task 2 的约定(camelCase 序列化给设置面板)。
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetHints {
    /// **私有网段**地址(10./172.16-31./192.168.), 每条带网卡名 —— 配对时要填的就是它。
    ///
    /// 只包含**已连接(OperStatus=Up)**网卡的地址; 顺序是"无线 → 有线 → 其它网卡"。
    pub lan: Vec<LanAddress>,
    /// 公网 IPv4 地址: **只用于警告, 绝不进候选列表**(见模块头"候选地址的边界")。
    pub public_ipv4: Vec<String>,
    /// 当前网络类别(Windows NetworkListManager; 多网络时取最危险的那一个, 见模块头)。
    pub profile: NetworkProfile,
    /// 防火墙提示的**机器码**, 不是一句中文 —— 面板把它翻成当前语言。
    ///
    /// 为什么是码而不是句子(这是与"M4′ Task 2 原文注释"的一处**有意偏离**, 理由写在这里):
    /// 交接文档 §5 第 7 条是一条硬纪律 —— **界面切到英文时, 新功能的文案不许露出中文**。
    /// 防火墙提示是新加的用户可见文案, 所以它必须像别的文案一样走 `src/i18n` 的 zh/en 双写,
    /// 而不能把中文硬编码进 Rust。面板对**未知码**有一条兜底文案(见 `settings-dialog.tsx`),
    /// 所以以后在后端加档也不会把面板弄成空白。
    ///
    /// 今天只有一档: [`FIREWALL_HINT_ALLOW_INBOUND_PRIVATE`] = 让用户在 Windows 防火墙里
    /// 允许 ImageFilter 通过**专用**网络(局域网模式下"连不上"的第一大原因)。
    pub firewall_hint: String,
}

/// 一条候选地址 + 它所在的网卡名。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanAddress {
    /// 点分十进制 IPv4(**字符串**, 因为手机要照着抄/二维码要装它)。
    pub address: String,
    /// 网卡名(Windows 的 `FriendlyName`, 例如 "WLAN" / "以太网" / "vEthernet (WSL)")。
    pub interface: String,
}

/// 当前网络类别。三档里 `Unknown` 是"读不出来", **不是**"中性" —— 见
/// `should_refuse_to_start`: 它和 `Public` 一样要求用户显式确认(fail-closed)。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum NetworkProfile {
    /// 专用(家庭/可信局域网) —— Windows 的 `NLM_NETWORK_CATEGORY_PRIVATE`, **也包含**
    /// `NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED`(域): 域的可信级别不低于专用, 而面板只有
    /// 三档可显示, 所以域归到这一档(见 `Category::profile`)。
    ///
    /// 这是**唯一**允许静默启动的读数(见 `should_refuse_to_start`)。
    Private,
    /// 公用 —— 咖啡厅/办公室/酒店那类共享网络, 也正是"不可信局域网"那一档。
    Public,
    /// 读不出来(COM 不可用 / 查询失败 / 网络列表服务被停 / 一张网卡都没连)。
    #[default]
    Unknown,
}

/// NLM 的原始类别值。**只在平台层与纯函数之间传递**, 不往外露(对外只有三档 `NetworkProfile`)。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    /// `NLM_NETWORK_CATEGORY_PUBLIC` = 0
    Public,
    /// `NLM_NETWORK_CATEGORY_PRIVATE` = 1
    Private,
    /// `NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED` = 2
    Domain,
}

impl Category {
    /// 把 NLM 的原始整数翻成类别。**不认识的值得 `None`**(将来 Windows 加档时宁可说"读不出来",
    /// 也不要瞎猜成一档)。
    pub fn from_raw(v: i32) -> Option<Self> {
        match v {
            0 => Some(Category::Public),
            1 => Some(Category::Private),
            2 => Some(Category::Domain),
            _ => None,
        }
    }

    /// 这一档在对外三档里显示成什么。域 → `Private`(见 `NetworkProfile::Private` 的注释)。
    fn profile(self) -> NetworkProfile {
        match self {
            Category::Public => NetworkProfile::Public,
            Category::Private | Category::Domain => NetworkProfile::Private,
        }
    }

    /// 合并两个类别时谁更危险(公用的优先级最高)。多网络时用它取"最危险的那一个"。
    fn worst(self, other: Category) -> Category {
        match (self, other) {
            (Category::Public, _) | (_, Category::Public) => Category::Public,
            (Category::Domain, _) | (_, Category::Domain) => Category::Domain,
            (Category::Private, Category::Private) => Category::Private,
        }
    }
}

/// 一张网卡上的一条 IPv4 —— **纯数据**, 由平台层填(见 `platform::snapshot`)。
///
/// 为什么把它和 `LanAddress` 分开: 这一层带着分类与排序要用的原始事实(`if_type`/`oper_up`),
/// 而 `LanAddress` 是给界面看的最终形状。分开之后 `build_hints` 是**纯函数** —— 分类规则与
/// 拒绝规则都能用假数据真的跑一遍, 不需要一台真机器, 也不需要动本机的网络设置。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdapterSnapshot {
    pub address: Ipv4Addr,
    /// 网卡名(Windows `FriendlyName`)。
    pub interface: String,
    /// Windows 的 `IfType`(IANA ifType): 6=以太网, 71=无线, 其它=虚拟/隧道/回环等。
    /// 只用于**排序**(无线/有线优先), 不用于丢弃任何地址。
    pub if_type: u32,
    /// 网卡是否 Up(`OperStatus == IfOperStatusUp`)。没 up 的网卡的地址连不上, 两个列表都不进。
    pub oper_up: bool,
}

/// 探测本机。**只读本机状态, 不发起任何外部请求**(连 DNS/连路由表都不碰)。
///
/// 全程没有 `unwrap`/`expect`/`panic`: 任何一步失败都只是"这一项没有值", 落到 `Unknown` /
/// 空列表 —— 调用方(设置面板、启动前防护)拿到的永远是一份**可用**的快照。
///
/// ⚠️ **会做一次 COM 初始化 + 若干 Win32 调用**(实测在本机是毫秒级), 所以它是同步阻塞函数:
/// 调用方在异步上下文里调用它之前, 先想清楚这一下阻塞会不会挡住别的活(见 `lib.rs` 里
/// `get_server_network_hints` 的 `spawn_blocking`)。
pub fn detect_hints() -> NetHints {
    let adapters = platform::snapshot();
    let categories = platform::connected_categories();
    build_hints(&adapters, &categories)
}

/// 由"网卡快照 + 已连接网络的类别"算出提示。**纯函数**: 不碰 OS, 因此可以被单测用假数据
/// 真的跑一遍(含一台"公用网络"的机器, 那种机器没法在测试里变出来)。
pub fn build_hints(adapters: &[AdapterSnapshot], categories: &[Category]) -> NetHints {
    let mut lan: Vec<(LanAddress, u32)> = Vec::new();
    let mut public_ipv4: Vec<String> = Vec::new();
    // 地址 → 它在 `lan` 里的下标。用下标而不是"见过没有", 是为了在**同一个地址被两张网卡同时
    // 报告**时还能改掉那一行的网卡名(见下面步骤 ④ 的细则): 只换标签, 不换行数。
    let mut seen_lan: Vec<(Ipv4Addr, usize)> = Vec::new();
    let mut seen_public: Vec<Ipv4Addr> = Vec::new();

    for ad in adapters {
        // 没 up 的网卡: 那个地址此刻连不上, 既不是候选也不是警告。
        if !ad.oper_up || ad.address.is_loopback() {
            continue;
        }
        if is_private_v4(ad.address) {
            match seen_lan.iter().find(|(a, _)| *a == ad.address).map(|(_, i)| *i) {
                // 第一次见到这个地址: 新起一行。
                None => {
                    seen_lan.push((ad.address, lan.len()));
                    lan.push((
                        LanAddress {
                            address: ad.address.to_string(),
                            interface: ad.interface.clone(),
                        },
                        ad.if_type,
                    ));
                }
                // 这个地址又来了一次(同一地址挂在两张网卡上, 例如真无线 + `vEthernet (WSL)`):
                // **地址只占一行**, 但标签要与"更像真网卡"的那张对齐 —— 否则用户会看到
                // `http://192.168.1.7:8765` 旁边写着 `vEthernet (WSL)`, 而真正能用的是 WLAN。
                // 网卡名与排序档一起换, 二者不许互相矛盾。
                //
                // "名字不能越换越差"也是条件的一半: 三级兜底(见 `adapter_name`)理论上可能全空,
                // 那就不许拿一个空名字顶掉一个有名有姓的行(那会让用户连猜都没得猜)。
                Some(i) => {
                    let more_physical = if_type_rank(ad.if_type) < if_type_rank(lan[i].1);
                    let has_usable_name = !ad.interface.is_empty() || lan[i].0.interface.is_empty();
                    if more_physical && has_usable_name {
                        lan[i].0.interface = ad.interface.clone();
                        lan[i].1 = ad.if_type;
                    }
                }
            }
        } else if !is_neither_private_nor_public(ad.address) {
            if !seen_public.contains(&ad.address) {
                seen_public.push(ad.address);
                public_ipv4.push(ad.address.to_string());
            }
        }
    }

    // 排序(稳定): 手机最可能用到的排前面 —— 无线(71) → 有线(6) → 其它网卡。
    // **只改顺序, 不丢行**: 虚拟网卡的私有地址照样列出来, 靠网卡名让用户自己判断(模块头)。
    lan.sort_by_key(|(_, if_type)| if_type_rank(*if_type));

    // 多网络取最危险的那一个(见模块头"类别是哪张网卡的")。
    let worst = categories.iter().copied().reduce(Category::worst);

    NetHints {
        lan: lan.into_iter().map(|(a, _)| a).collect(),
        public_ipv4,
        profile: worst.map(Category::profile).unwrap_or(NetworkProfile::Unknown),
        firewall_hint: FIREWALL_HINT_ALLOW_INBOUND_PRIVATE.to_string(),
    }
}

/// **启动前的拒绝规则**(规格 §4.3 的硬约束)。`None` = 可以启动; `Some(理由)` = 拒绝, 并且
/// 这个理由要能直接摆给用户看("返回一个可读理由"是 M4′ Task 2 的原话)。
///
/// 规则只有两条, 都是 fail-closed:
///   1. `profile == Public` → 拒绝。这正是"不可信局域网"那一档。
///   2. `profile == Unknown` → 拒绝。类别读不出来(COM 不可用/查询失败/netprofm 被停/没有已连接
///      的网络)时, 我们**没有"这条网络可信"这个事实** —— 那就按最坏情况办, 要求用户显式确认。
///
/// ── Fix 1(本轮修复)· 规则 2 为什么不再看"有没有非局域网地址" ─────────────────
/// 旧规则 2 是 `Unknown && !public_ipv4.is_empty()`, 而它**恰好在最需要它的场景里 fail-open**:
/// 共享 WiFi(咖啡厅/办公室/酒店)发的通常就是**私有**租约(10.x / 192.168.x), 于是类别读不出来时
/// `public_ipv4` 是空的、判定放行。而"类别读不出来"与"这条网络是共享 WiFi"是**独立**的两件事:
/// netprofm 被停掉/COM 不可用时, 类别恰恰读不出来。自动启动那条路上没有人在场, 所以那个组合
/// 就是"在不可信网络上静默开着一个明文端口"。现在唯一的静默放行条件是**读到** `Private`
/// (含域) —— 一个**正面**读数。
///
/// 明确**不**拒绝的只有一种组合(有测试钉住, 免得以后有人"顺手"改严):
///   · `Private` + 有非局域网地址 → 只警告, 不拒绝。地址层面的暴露不一定来自当前网络类别
///     (例如一根直连的网线), 规格 §4.3 对这种情形的要求是"警告", 不是"不许开"。
///
/// 理由必须让用户看出**是哪一种**, 因为下一步动作不同: "网络是公用"→ 回可信 WiFi 再开, 或者
/// 确认一次; "读不到类别"→ 先看 Windows 的网络列表服务是不是被停了 / 是不是根本没连网。
/// 两句都含 [`GUARD_REFUSAL_MARKER`](面板据此在探测快照拿不到时仍摆出确认入口)。
pub fn should_refuse_to_start(h: &NetHints) -> Option<String> {
    match h.profile {
        NetworkProfile::Public => Some(format!(
            "当前网络被 Windows 标为「公用」（咖啡厅 / 办公室 / 酒店这类共享网络）。在这种网络上,\
             配对码是明文 HTTP 传输的, 同一网段的设备可能嗅探到它并读到你的共享目录。\
             确认要在这种网络上开启服务吗？{}",
            public_suffix(&h.public_ipv4)
        )),
        // 读不出来 = **没有"可信"这个事实** → 按不可信办。注意这里**不看** `public_ipv4`:
        // 它空不空与"当前网络可不可信"是两件事(见上面 Fix 1 那一段)。
        NetworkProfile::Unknown => Some(format!(
            "读不到 Windows 的网络类别（网络列表服务不可用 / 查询失败 / 没有已连接的网络）—— \
             无法确认当前网络是否可信, 因此按不可信处理。在这种状态下开启服务, 配对码与共享目录\
             可能被同一网段的设备嗅探到, 而这条判定此刻**替你把不了关**。\
             确认要在无法确认网络可信性的情况下开启服务吗？{}",
            public_suffix(&h.public_ipv4)
        )),
        // 唯一允许静默启动的读数。
        NetworkProfile::Private => None,
    }
}

/// 网卡"像不像真网卡"的档位: 无线(71) → 有线(6) → 其它(虚拟/隧道/回环等)。数字越小越优先。
///
/// 两处用到, 而且是**同一个**判据(不许两处各写一份):
///   · `build_hints` 的排序 —— 手机最可能用到的排前面;
///   · 同一地址被多张网卡报告时留哪张网卡的**名字** —— 真无线压过 `vEthernet (WSL)`。
///
/// 为什么不能分得更细: 虚拟网卡(Hyper-V/WSL 的 `vEthernet`)报的 `IfType` **就是 6(以太网)**,
/// 与真网卡同档 —— 所以这里只能做到"无线压过以太网", 做不到"以太网压过 vEthernet"。方向是
/// 保守的: 分不出高下时保留系统先给的那一条(稳定), 绝不因此丢掉任何地址。
fn if_type_rank(if_type: u32) -> u8 {
    match if_type {
        71 => 0, // IF_TYPE_IEEE80211
        6 => 1,  // IF_TYPE_ETHERNET_CSMACD
        _ => 2,
    }
}

/// 拒绝理由末尾附一句"还检测到了哪些非局域网地址"(没有就不加)。
fn public_suffix(public_ipv4: &[String]) -> String {
    if public_ipv4.is_empty() {
        String::new()
    } else {
        format!("另外, 本机还检测到不是局域网的地址：{}。", public_ipv4.join("、"))
    }
}

/// 私有网段(RFC 1918): `10/8`、`172.16/12`、`192.168/16`。**只有这些进候选列表**。
pub fn is_private_v4(a: Ipv4Addr) -> bool {
    let o = a.octets();
    o[0] == 10 || (o[0] == 172 && (16..=31).contains(&o[1])) || (o[0] == 192 && o[1] == 168)
}

/// "既不是私有, 也不该被叫作公网"的那一类 —— 它们**两个列表都不进**(见模块头)。
///
/// 判据是"这个地址能不能是一条访问路径": 回环只有本机能用; 链路本地(APIPA)是"没拿到地址"
/// 的标记; 组播/广播/未指定根本不是主机地址; `240/4` 与 `198.18/15` 是保留/基准测试段。
fn is_neither_private_nor_public(a: Ipv4Addr) -> bool {
    let o = a.octets();
    a.is_loopback()
        || a.is_link_local()
        || a.is_multicast()
        || a.is_unspecified()
        || a.is_broadcast()
        || o[0] >= 240
        || (o[0] == 198 && (o[1] == 18 || o[1] == 19))
}

// ── 平台层 ──────────────────────────────────────────────────────────────────
//
// 这一层是**唯一**允许 unsafe 的地方, 而且只做两件事: 枚举网卡地址、问 NLM 要网络类别。
// 所有分类/判断都回到上面的纯函数里 —— 那些才是能被测试钉住的部分。
mod platform {
    use super::{AdapterSnapshot, Category};

    /// 取一个能给人看的网卡名: `FriendlyName` → `Description` → `AdapterName`(网卡 GUID 串)。
    ///
    /// 为什么要三级兜底 + 每一级都先查空指针: Win32 的 `P*STR::to_string()` **不做空指针检查**
    /// (它内部就是 `wcslen`/`strlen`), 而这里在**启动路径**上可达 —— 一个空指针就是一次访问
    /// 违例(整进程崩), 不是"少个名字"。`FriendlyName` 按 MSDN 总是有值, 但这条路上不值得赌。
    /// 三级都取不到时给空串: 地址照旧可用, 只是这一行没有网卡名。
    #[cfg(target_os = "windows")]
    fn adapter_name(ad: &windows::Win32::NetworkManagement::IpHelper::IP_ADAPTER_ADDRESSES_LH) -> String {
        for ptr in [ad.FriendlyName, ad.Description] {
            if !ptr.is_null() {
                if let Ok(s) = unsafe { ptr.to_string() } {
                    if !s.is_empty() {
                        return s;
                    }
                }
            }
        }
        if !ad.AdapterName.is_null() {
            if let Ok(s) = unsafe { ad.AdapterName.to_string() } {
                if !s.is_empty() {
                    return s;
                }
            }
        }
        String::new()
    }

    /// 本机所有网卡的 IPv4(地址 + 网卡名 + IfType + 是否 Up)。失败 = 空列表(不 panic)。
    #[cfg(target_os = "windows")]
    pub(super) fn snapshot() -> Vec<AdapterSnapshot> {
        use windows::Win32::Foundation::ERROR_BUFFER_OVERFLOW;
        use windows::Win32::NetworkManagement::IpHelper::{
            GetAdaptersAddresses, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER,
            GAA_FLAG_SKIP_MULTICAST, IP_ADAPTER_ADDRESSES_LH,
        };
        use windows::Win32::Networking::WinSock::{AF_INET, AF_UNSPEC, SOCKADDR_IN};

        let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
        // 先猜一个缓冲区大小, 不够就按系统回填的 `need` 重来。**重试有上界(3 次)**: 网卡在两次
        // 调用之间被插拔时, 系统回填的大小会一直变, 没有上界就是一个死循环 —— 这条函数是
        // 启动路径可达的, 死循环等于把"服务开关"卡死。
        let mut size: u32 = 16 * 1024;
        let mut buf: Vec<u8> = Vec::new();
        let mut ok = false;
        for _ in 0..3 {
            buf = vec![0u8; size as usize];
            let mut need = size;
            let ret = unsafe {
                GetAdaptersAddresses(
                    AF_UNSPEC.0 as u32,
                    flags,
                    None,
                    Some(buf.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH),
                    &mut need,
                )
            };
            if ret == 0 {
                ok = true;
                break;
            }
            if ret != ERROR_BUFFER_OVERFLOW.0 {
                // 其它错误(含"没有数据"): 一个地址都不给 → 上层 `lan` 空、类别另算。
                break;
            }
            size = need.max(1024);
        }
        if !ok {
            return Vec::new();
        }

        let mut out: Vec<AdapterSnapshot> = Vec::new();
        // 遍历**有上界**: 链表是系统填的, 但这段代码在**启动路径**上可达 —— 万一真出现一张
        // 环状链表, 用户看到的是"点了开关再也没反应"(比 panic 更难查)。上界让这件事最多多花
        // 几微秒就结束, 而不是把服务开关卡死。
        const MAX_ADAPTERS: usize = 256;
        const MAX_UNICAST_PER_ADAPTER: usize = 256;
        let mut node = buf.as_ptr() as *const IP_ADAPTER_ADDRESSES_LH;
        let mut adapter_count = 0usize;
        while !node.is_null() && adapter_count < MAX_ADAPTERS {
            adapter_count += 1;
            // SAFETY: `node` 来自 `GetAdaptersAddresses` 填好的链表; 链接在 `buf` 的生命周期内
            // 一直有效(`buf` 在本函数返回前不会被移动或释放)。
            let ad = unsafe { &*node };
            let interface = adapter_name(ad);
            let if_type = ad.IfType;
            let oper_up = ad.OperStatus == windows::Win32::NetworkManagement::Ndis::IfOperStatusUp;
            let mut uni = ad.FirstUnicastAddress;
            let mut uni_count = 0usize;
            while !uni.is_null() && uni_count < MAX_UNICAST_PER_ADAPTER {
                uni_count += 1;
                let ua = unsafe { &*uni };
                let sa = ua.Address;
                if !sa.lpSockaddr.is_null() {
                    // SAFETY: 非空指针且 `sa_family == AF_INET` 时, `lpSockaddr` 指向
                    // `SOCKADDR_IN`(Win32 的约定: 家族字段决定结构体类型)。
                    let sin = sa.lpSockaddr as *const SOCKADDR_IN;
                    let family = unsafe { (*sa.lpSockaddr).sa_family };
                    if family == AF_INET {
                        // `S_addr` 是**网络字节序**的 u32: 在内存里它就是 a.b.c.d 四个字节。
                        // `from_be` 之后按主机序解释, 于是 x86 与 arm64 上都是对的。
                        let raw = unsafe { (*sin).sin_addr.S_un.S_addr };
                        out.push(AdapterSnapshot {
                            address: std::net::Ipv4Addr::from(u32::from_be(raw)),
                            interface: interface.clone(),
                            if_type,
                            oper_up,
                        });
                    }
                }
                uni = ua.Next;
            }
            node = ad.Next;
        }
        out
    }

    /// 非 Windows: 平台层给不出任何东西 → 上层落 `Unknown` + 空列表(不 panic)。
    ///
    /// 本项目只出 Windows 版; 这一条存在的唯一理由是**让 crate 在 macOS/Linux 上仍然编译得过**
    /// (`windows` crate 本身就被 target 门控)。它**不是**一个被验证过的平台行为。
    #[cfg(not(target_os = "windows"))]
    pub(super) fn snapshot() -> Vec<AdapterSnapshot> {
        Vec::new()
    }

    /// 所有**已连接**网络的类别。读不出来 = 空列表(上层落 `Unknown`), 绝不 panic。
    #[cfg(target_os = "windows")]
    pub(super) fn connected_categories() -> Vec<Category> {
        use windows::core::IUnknown;
        use windows::Win32::Networking::NetworkListManager::{
            INetwork, INetworkListManager, NetworkListManager, NLM_ENUM_NETWORK_CONNECTED,
        };
        use windows::Win32::System::Com::{
            CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_ALL, COINIT_APARTMENTTHREADED,
        };

        /// 这次调用**自己**初始化了 COM 吗? 是的话离开时必须撤掉。
        ///
        /// 为什么这么计较: 这个函数可能跑在 tokio 的工作线程上, 而那些线程是**共用**的 ——
        /// 撤掉别人(例如 Tauri 自己的窗口/OLE 那一层)的 COM 状态, 或者把某个线程永久留在
        /// STA, 都是"为了一个提示去弄坏别的东西"。所以: 只有 `CoInitializeEx` **成功**
        /// (含 S_FALSE"已经初始化过") 才配对 `CoUninitialize`; `RPC_E_CHANGED_MODE`
        /// (线程已经是 MTA)不算失败也不需要撤 —— NLM 的线程模型是 Both, 在 MTA 里照样能用。
        /// 另外整段代码是**同步**的(没有 await), 所以"短暂把某个工作线程变成 STA"这件事
        /// 不会横跨到别的任务上去。
        struct ComGuard(bool);
        impl Drop for ComGuard {
            fn drop(&mut self) {
                if self.0 {
                    unsafe { CoUninitialize() };
                }
            }
        }

        let hr = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
        let _com = ComGuard(hr.is_ok());

        // SAFETY: CLSID 是 NLM 的注册类(`windows` crate 给的是 GUID 常量), 返回的接口指针由
        // `INetworkListManager` 这个 RAII 包装持有, 离开作用域时自动 Release。
        let mgr: windows::core::Result<INetworkListManager> = unsafe {
            CoCreateInstance(&NetworkListManager, None::<&IUnknown>, CLSCTX_ALL)
        };
        let Ok(mgr) = mgr else { return Vec::new() };
        let Ok(nets) = (unsafe { mgr.GetNetworks(NLM_ENUM_NETWORK_CONNECTED) }) else {
            return Vec::new();
        };

        let mut out: Vec<Category> = Vec::new();
        // 一次最多取 4 条, 取到空为止。`Next` 在"剩的比要的少"时返回 S_FALSE, 仍算成功
        // (windows-rs 只把负 HRESULT 当失败), 所以我们按 `fetched` 判断, 而不是按返回值。
        let mut buf: [Option<INetwork>; 4] = [None, None, None, None];
        loop {
            let mut fetched: u32 = 0;
            if unsafe { nets.Next(&mut buf, Some(&mut fetched as *mut u32)) }.is_err() {
                break;
            }
            if fetched == 0 {
                break;
            }
            for slot in buf.iter_mut().take(fetched as usize) {
                let Some(net) = slot.take() else { continue };
                if let Ok(raw) = unsafe { net.GetCategory() } {
                    if let Some(c) = Category::from_raw(raw.0) {
                        out.push(c);
                    }
                }
            }
        }
        out
    }

    /// 非 Windows: 没有 NLM → 空列表(上层落 `Unknown`)。同样只为"编译得过"。
    #[cfg(not(target_os = "windows"))]
    pub(super) fn connected_categories() -> Vec<Category> {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ad(address: &str, interface: &str, if_type: u32, oper_up: bool) -> AdapterSnapshot {
        AdapterSnapshot {
            address: address.parse().expect("测试数据必须是合法 IPv4"),
            interface: interface.to_string(),
            if_type,
            oper_up,
        }
    }

    // ── 地址分类(纯函数, 边界逐个钉) ─────────────────────────────────────────

    /// 私有网段的**边界**必须逐条钉住: 差一位就会把公网地址列进候选(或者把 192.168 漏掉)。
    #[test]
    fn private_v4_boundaries_are_exact() {
        for a in [
            "10.0.0.0", "10.0.0.1", "10.255.255.255",
            "172.16.0.0", "172.16.0.1", "172.31.255.255",
            "192.168.0.0", "192.168.1.5", "192.168.255.255",
        ] {
            assert!(is_private_v4(a.parse().unwrap()), "{a} 应当是私有网段");
        }
        for a in [
            "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0", "8.8.8.8", "100.64.0.1",
        ] {
            assert!(!is_private_v4(a.parse().unwrap()), "{a} 不应当是私有网段");
        }
    }

    /// **公网地址绝不进候选列表** —— 这条是规格 §4.2 的原话, 也是这个模块存在的理由。
    #[test]
    fn public_addresses_never_become_candidates() {
        let adapters = vec![
            ad("192.168.1.7", "WLAN", 71, true),
            ad("203.0.113.9", "以太网", 6, true),
            ad("100.64.3.4", "Tailscale", 6, true),
        ];
        let h = build_hints(&adapters, &[Category::Private]);

        assert_eq!(h.lan.len(), 1, "只有私有地址能进候选: {:?}", h.lan);
        assert_eq!(h.lan[0].address, "192.168.1.7");
        assert_eq!(h.lan[0].interface, "WLAN");
        let mut pubv4 = h.public_ipv4.clone();
        pubv4.sort();
        assert_eq!(pubv4, vec!["100.64.3.4".to_string(), "203.0.113.9".to_string()]);
        assert!(
            !h.lan.iter().any(|l| l.address.starts_with("203.") || l.address.starts_with("100.")),
            "公网/共享地址一个都不许出现在候选里"
        );
    }

    /// 回环 / 链路本地 / 组播 / 保留段:**两个列表都不进**(它们不是"一门访问路径")。
    #[test]
    fn non_path_addresses_are_dropped_from_both_lists() {
        let adapters = vec![
            ad("127.0.0.1", "回环", 24, true),
            ad("169.254.13.37", "以太网", 6, true),
            ad("224.0.0.1", "以太网", 6, true),
            ad("0.0.0.0", "以太网", 6, true),
            ad("198.18.0.1", "基准测试", 6, true),
            ad("250.1.2.3", "保留", 6, true),
        ];
        let h = build_hints(&adapters, &[Category::Private]);
        assert!(h.lan.is_empty(), "不该有候选: {:?}", h.lan);
        assert!(h.public_ipv4.is_empty(), "也不该被叫作公网: {:?}", h.public_ipv4);
    }

    /// 没 up 的网卡: 地址连不上, 两个列表都不进(插着网线但没连上时最容易看到这个现场)。
    #[test]
    fn down_adapters_contribute_nothing() {
        let adapters = vec![
            ad("192.168.1.7", "WLAN", 71, false),
            ad("203.0.113.9", "以太网", 6, false),
        ];
        let h = build_hints(&adapters, &[Category::Public]);
        assert!(h.lan.is_empty());
        assert!(h.public_ipv4.is_empty());
    }

    /// 排序: 无线 → 有线 → 其它(隧道/虚拟等)。**只排序**, 一条都不许丢。
    ///
    /// 注意虚拟网卡(Hyper-V/WSL 的 `vEthernet`)报的 `IfType` **就是 6(以太网)**, 与真网卡
    /// 同档 —— 同一档内保持系统给出的顺序(稳定排序)。这正是"不丢弃任何地址"的理由: 我们
    /// 没有可靠办法把虚拟网卡和真网卡分开, 所以宁可靠网卡名让用户自己判断。
    #[test]
    fn candidates_are_ordered_wifi_first_but_nothing_is_dropped() {
        let adapters = vec![
            ad("172.17.0.1", "vEthernet (WSL)", 6, true),
            ad("192.168.1.7", "WLAN", 71, true),
            ad("10.9.9.9", "隧道", 131, true),
            ad("10.0.0.5", "以太网", 6, true),
        ];
        let h = build_hints(&adapters, &[Category::Private]);
        let addrs: Vec<&str> = h.lan.iter().map(|l| l.address.as_str()).collect();
        assert_eq!(addrs, vec!["192.168.1.7", "172.17.0.1", "10.0.0.5", "10.9.9.9"]);
        assert_eq!(h.lan[1].interface, "vEthernet (WSL)", "虚拟网卡也要带上网卡名");
    }

    /// 同一个地址出现两次(同一张网卡的多个地址条目)只列一次。
    #[test]
    fn duplicate_addresses_are_listed_once() {
        let adapters = vec![
            ad("192.168.1.7", "WLAN", 71, true),
            ad("192.168.1.7", "WLAN", 71, true),
        ];
        assert_eq!(build_hints(&adapters, &[]).lan.len(), 1);
    }

    /// minor ①: **同一地址挂在两张网卡上**时, 留下来的那一行要带"更像真网卡"的那张的名字。
    ///
    /// 现场: 真无线拿到 `192.168.1.7`, 而 `vEthernet (WSL)` 报告了同一个地址 —— 旧的"按地址去重"
    /// 会看谁先来, 于是面板可能显示 `http://192.168.1.7:8765` 旁边写着 `vEthernet (WSL)`。
    /// 地址仍然**只占一行**(一个地址都不许因此被丢掉), 只换标签与排序档。
    ///
    /// 变异靶子: 把 `build_hints` 里那次 `if_type_rank` 比较删掉(退回"先到先得")→ 下面第一段
    /// 断言当场红。
    #[test]
    fn a_shared_address_keeps_the_more_physical_interface_label() {
        let virtual_first = vec![
            ad("192.168.1.7", "vEthernet (WSL)", 6, true),
            ad("192.168.1.7", "WLAN", 71, true),
        ];
        let h = build_hints(&virtual_first, &[Category::Private]);
        assert_eq!(h.lan.len(), 1, "同一个地址只占一行(一行都不许丢): {:?}", h.lan);
        assert_eq!(h.lan[0].address, "192.168.1.7");
        assert_eq!(h.lan[0].interface, "WLAN", "冲突时要留真无线的名字, 不是虚拟网卡的");

        // 顺序反过来(真无线先来)必须得到**同一个**结果 —— 判定与系统给出的顺序无关。
        let physical_first = vec![
            ad("192.168.1.7", "WLAN", 71, true),
            ad("192.168.1.7", "vEthernet (WSL)", 6, true),
        ];
        let h2 = build_hints(&physical_first, &[Category::Private]);
        assert_eq!(h2.lan.len(), 1);
        assert_eq!(h2.lan[0].interface, "WLAN", "先来的真无线不许被后来的虚拟网卡顶掉");

        // 同一档(两张都报 6, 例如真以太网 + vEthernet)分不出高下: 保留先到的那一条(稳定),
        // 而且**绝不**因此丢掉地址。
        let same_rank = vec![
            ad("10.0.0.5", "以太网", 6, true),
            ad("10.0.0.5", "vEthernet (Default Switch)", 6, true),
        ];
        assert_eq!(build_hints(&same_rank, &[Category::Private]).lan[0].interface, "以太网");

        // 名字也不能越换越差: 真无线那条的网卡名(三级兜底)**全**取不到时, 不许拿一个空串顶掉
        // 一个有名有姓的行 —— 用户至少还有名字可看。
        let nameless_wifi = vec![
            ad("192.168.1.7", "vEthernet (WSL)", 6, true),
            ad("192.168.1.7", "", 71, true),
        ];
        assert_eq!(
            build_hints(&nameless_wifi, &[Category::Private]).lan[0].interface,
            "vEthernet (WSL)",
            "空名字不许顶掉有名有姓的那一行"
        );
    }

    // ── 类别 → 三档 ─────────────────────────────────────────────────────────

    /// 多网络取**最危险**的那一个; 域归到 `Private`; 一个都没有 = `Unknown`。
    #[test]
    fn profile_takes_the_worst_connected_category() {
        let adapters = vec![ad("192.168.1.7", "WLAN", 71, true)];
        let p = |cats: &[Category]| build_hints(&adapters, cats).profile;
        assert_eq!(p(&[Category::Private]), NetworkProfile::Private);
        assert_eq!(p(&[Category::Domain]), NetworkProfile::Private, "域的可信级别不低于专用");
        assert_eq!(p(&[Category::Private, Category::Domain]), NetworkProfile::Private);
        assert_eq!(
            p(&[Category::Private, Category::Public]),
            NetworkProfile::Public,
            "有一张公用网卡就够了(fail-closed)"
        );
        assert_eq!(p(&[]), NetworkProfile::Unknown, "一张网卡都没连 = 读不出来");
    }

    /// 未知的原始值不许被猜成一档(将来 Windows 加档时宁可说"读不出来")。
    #[test]
    fn unknown_raw_category_value_is_not_guessed() {
        assert_eq!(Category::from_raw(0), Some(Category::Public));
        assert_eq!(Category::from_raw(1), Some(Category::Private));
        assert_eq!(Category::from_raw(2), Some(Category::Domain));
        assert_eq!(Category::from_raw(3), None);
        assert_eq!(Category::from_raw(-1), None);
    }

    // ── 拒绝规则 ────────────────────────────────────────────────────────────

    fn hints(profile: NetworkProfile, public_ipv4: &[&str]) -> NetHints {
        NetHints {
            lan: Vec::new(),
            public_ipv4: public_ipv4.iter().map(|s| s.to_string()).collect(),
            profile,
            firewall_hint: FIREWALL_HINT_ALLOW_INBOUND_PRIVATE.to_string(),
        }
    }

    /// **Public 必须拒绝** —— 这是规格 §4.3 的第一条硬约束。
    ///
    /// 变异靶子: 把 `should_refuse_to_start` 里 `NetworkProfile::Public` 那条分支删掉
    /// (或改成 `None`), 这条当场红。
    #[test]
    fn public_profile_must_refuse_with_a_readable_reason() {
        let reason = should_refuse_to_start(&hints(NetworkProfile::Public, &[]))
            .expect("公用网络必须拒绝自动启动");
        assert!(reason.contains("公用"), "理由要说清是哪一档: {reason}");
        assert!(reason.contains("配对码"), "理由要说清代价: {reason}");
    }

    /// **Private 不许拒绝** —— 与上一条成对: 少了它, 一个"永远返回 Some"的实现也能过测试。
    /// 这也是**唯一**允许静默启动的读数。
    #[test]
    fn private_profile_must_not_refuse() {
        assert_eq!(should_refuse_to_start(&hints(NetworkProfile::Private, &[])), None);
    }

    /// `Private` + 检测到非局域网地址 → **只警告不拒绝**(规格 §4.3 对这一情形的要求是警告)。
    /// 这条把"地址层面的暴露"与"当前网络类别"两件事分开, 免得以后有人顺手改严。
    #[test]
    fn private_profile_with_a_public_address_warns_but_still_allows() {
        assert_eq!(
            should_refuse_to_start(&hints(NetworkProfile::Private, &["203.0.113.9"])),
            None
        );
    }

    /// **Fix 1 · 读不出类别一律要求确认**(`Unknown` 与 `Public` 同级 fail-closed)。
    ///
    /// 变异靶子: 把 `Unknown` 分支改回旧规则(`Unknown if !h.public_ipv4.is_empty()` ——
    /// 也就是"没有非局域网地址就放行")→ 下面第二段断言当场红。
    ///
    /// 为什么第二段才是关键: 咖啡厅/办公室/酒店那类共享 WiFi 发的**通常就是私有租约**,
    /// 所以 `public_ipv4` 为空根本不能证明这条网络可信; 而 netprofm 停掉/COM 不可用时
    /// 类别**恰恰**读不出来。旧的 fail-open 组合正好落在这里, 而且自动启动那条路上没人看到警告。
    #[test]
    fn unknown_profile_always_requires_confirmation() {
        // ① 有非局域网地址: 照旧拒绝, 而且理由里要带上那个地址(原来的行为一个字都不许丢)。
        let with_addr = should_refuse_to_start(&hints(NetworkProfile::Unknown, &["203.0.113.9"]))
            .expect("读不出类别且有非局域网地址时必须要求确认");
        assert!(with_addr.contains("203.0.113.9"), "理由里要带上那个地址: {with_addr}");
        assert!(with_addr.contains("读不到"), "理由要说清是「读不出来」: {with_addr}");

        // ② **本条就是 Fix 1**: 没有任何非局域网地址也照拒。
        let bare = should_refuse_to_start(&hints(NetworkProfile::Unknown, &[]))
            .expect("读不出类别时, 没有非局域网地址也必须要求确认(fail-closed)");
        assert!(bare.contains("读不到"), "理由要说清为什么: {bare}");
        assert!(
            !bare.contains("公用"),
            "两种拒绝的理由必须能区分开(用户的下一步动作不同): {bare}"
        );
        assert!(
            bare.contains("确认"),
            "拒绝之后要给出「确认」这条出路(否则用户的服务被永久锁死): {bare}"
        );
    }

    /// 面板靠 [`GUARD_REFUSAL_MARKER`] 在**探测快照拿不到**时仍然摆出二次确认入口 ——
    /// 所以"两句拒绝理由都含这个标记"必须机器可查: 改文案时会红, 而不是让用户手上那个开关
    /// 变成"点了就被拒、却找不到确认按钮"。
    #[test]
    fn both_refusal_reasons_carry_the_panel_marker() {
        for h in [
            hints(NetworkProfile::Public, &[]),
            hints(NetworkProfile::Unknown, &[]),
            hints(NetworkProfile::Unknown, &["203.0.113.9"]),
        ] {
            let reason = should_refuse_to_start(&h).expect("这两种都该拒绝");
            assert!(
                reason.contains(GUARD_REFUSAL_MARKER),
                "拒绝理由必须含面板用的标记 {GUARD_REFUSAL_MARKER:?}: {reason}"
            );
        }
        // 放行的那两种组合一个都不许含它(否则面板会凭空摆出一个"仍要开启"的入口)。
        for h in [
            hints(NetworkProfile::Private, &[]),
            hints(NetworkProfile::Private, &["203.0.113.9"]),
        ] {
            assert_eq!(should_refuse_to_start(&h), None);
        }
    }

    /// 拒绝理由里出现的地址, 必须来自那个**只用于警告**的列表(不是候选列表)。
    #[test]
    fn refuse_reason_mentions_the_public_addresses_only() {
        let mut h = hints(NetworkProfile::Public, &["203.0.113.9", "100.64.3.4"]);
        h.lan = vec![LanAddress { address: "192.168.1.7".into(), interface: "WLAN".into() }];
        let reason = should_refuse_to_start(&h).unwrap();
        assert!(reason.contains("203.0.113.9") && reason.contains("100.64.3.4"));
        assert!(!reason.contains("192.168.1.7"), "候选地址不该出现在拒绝理由里");
    }

    // ── 防火墙提示码 ────────────────────────────────────────────────────────

    /// 防火墙提示是一个**机器码**(面板翻成当前语言), 不是中文句子 —— 见字段注释。
    /// 这条把码钉住: 面板按它查表, 换了码而没同步面板就会变成兜底文案(而不是空白)。
    #[test]
    fn firewall_hint_is_the_documented_machine_code() {
        assert_eq!(detect_hint_code(), "allowInboundPrivateProfile");
        assert!(!FIREWALL_HINT_ALLOW_INBOUND_PRIVATE.contains(' '), "码里不该有空格");
    }

    fn detect_hint_code() -> String {
        // 只用纯函数这一半: 不碰本机网卡(测试不许依赖运行机器的网络状况)。
        build_hints(&[], &[]).firewall_hint
    }

    // ── 真机快照的**不变量** ────────────────────────────────────────────────
    //
    /*
     * 这一条故意**不断言**类别与地址的具体值 —— 那取决于跑测试的机器此刻的网络,
     * 那样的断言要么是空转, 要么是"看运气"的红。它只钉住**分类的不变量**:
     * 候选全是私有、警告列表里没有私有、两边都是合法点分十进制、没有重复。
     * 另外打一行快照, 方便人工核对(`cargo test --lib -- --nocapture`)。
     */
    #[test]
    fn real_snapshot_respects_the_classification_invariants() {
        let started = std::time::Instant::now();
        let h = detect_hints();
        // 打一行耗时: 这个探测在**开启服务**那条路上是同步跑的(用户点一下开关就要等它),
        // 所以它的耗时是一件随时该看得见的事(`--nocapture` 下才显示)。
        println!(
            "[network] detect_hints took {:?} → profile={:?} lan={:?} public={:?}",
            started.elapsed(),
            h.profile,
            h.lan,
            h.public_ipv4
        );
        for l in &h.lan {
            let a: Ipv4Addr = l.address.parse().expect("候选必须是合法 IPv4 字符串");
            assert!(is_private_v4(a), "候选里混进了非私有地址: {}", l.address);
            assert!(!l.interface.is_empty(), "候选必须带网卡名: {}", l.address);
        }
        for p in &h.public_ipv4 {
            let a: Ipv4Addr = p.parse().expect("警告项必须是合法 IPv4 字符串");
            assert!(!is_private_v4(a), "私有地址不许进公网警告: {}", p);
            assert!(!is_neither_private_nor_public(a), "这类地址两个列表都不该进: {}", p);
        }
        let mut lan = h.lan.iter().map(|l| l.address.clone()).collect::<Vec<_>>();
        let n = lan.len();
        lan.sort();
        lan.dedup();
        assert_eq!(lan.len(), n, "候选列表不该有重复地址");
        assert_eq!(h.firewall_hint, FIREWALL_HINT_ALLOW_INBOUND_PRIVATE);
    }
}
