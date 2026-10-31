// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useCallback, useEffect, useLayoutEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Moon, Sun } from "@icon-park/react";
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
// 取个别名: 同一个文件里 ServerPanel 的 `open` prop(对话框开着没有)会把它遮住。
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Toggle } from "@/components/ui/toggle";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Collapse } from "./collapse";
import {
  approvePairing,
  closePairingWindow,
  denyPairing,
  forgetPairedDevice,
  formatCountdown,
  onPairingChanged,
  onPairingRequest,
  openPairingWindow,
  pairingStatus,
  type PairingStatus,
} from "../lib/pairing";
import type { Lang } from "../i18n";
import { xmpErrKey, type XmpMode, type XmpStatus } from "../xmp";
import { isLrcModeUsable, type LrcSendMode } from "../lightroom";
import { checkForUpdate, type UpdateDetail, type UpdateState } from "../updater";
import type { LightroomProbe } from "../types";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  theme: "dark" | "light";
  onThemeChange: (t: "dark" | "light") => void;
  lang: Lang;
  onLangChange: (l: Lang) => void;
  preloadFull: boolean;
  onTogglePreloadFull: () => void;
  autoAdvance: boolean;
  onToggleAutoAdvance: () => void;
  /** Phase 4: 颜色标签用哪个修饰键(Ctrl / Alt)。见 docs 4.1 */
  labelModifier: "ctrl" | "alt";
  onLabelModifierChange: (v: "ctrl" | "alt") => void;
  /** Phase 5: XMP 边车三档开关 + 常驻状态行(见 docs §5.2/§5.3) */
  xmpMode: XmpMode;
  onXmpModeChange: (m: XmpMode) => void;
  xmpStatus: XmpStatus | null;
  /** Phase 7: Lightroom 衔接(探测结果 / 发送模式 / 重新检测) */
  lrcProbe: LightroomProbe | null;
  lrcMode: LrcSendMode;
  onLrcModeChange: (m: LrcSendMode) => void;
  onReprobeLightroom: () => void;
  transparentBg: boolean;
  onToggleTransparentBg: () => void;
  /** 系统不支持 Mica(Win10)时为真 */
  micaUnsupported: boolean;
  /** 来自 Rust `get_os_capabilities` 的 platform: macOS 上要隐藏不支持的功能 */
  platform: string;
  glassOpacity: number;
  onGlassOpacityChange: (v: number) => void;
  backgroundOpacity: number;
  onBackgroundOpacityChange: (v: number) => void;
}

/**
 * 图库服务(手机端)的状态快照 —— 与 `src-tauri/src/server/mod.rs` 的 `ServerStatus` 一一对应
 * (serde camelCase)。
 *
 * `enabled` 与 `running` 是**两件事**, 面板必须同时显示:
 *   · `enabled` = 用户的选择, 而且永远是"盘上此刻真正写着的那一份"(决定下次开机要不要拉起);
 *   · `running` = 此刻**真的**有 socket 在监听(端口被别的软件占着时它是 false);
 *   · `lastError` = 启动那一步失败的理由 —— 后端刻意**不**把开关回滚(Task 8), 所以
 *     "你要它开、它没在跑"是合法状态, 而界面必须把理由摆出来, 否则用户只会以为没点中。
 */
interface ServerStatus {
  running: boolean;
  port: number;
  url: string;
  token: string;
  shares: string[];
  enabled: boolean;
  lastError: string | null;
  /**
   * M4′ Task 2: 用户在"当前网络是公用"那条警告下**显式确认过**的时刻(unix 秒)。
   * `null` = 本次运行还没确认过。它只活在**本次运行**里 —— 重启后回到 null, 于是下次开机
   * 在公用网络上还会再拦一次(确认是一次动作, 不是永久豁免)。
   */
  publicAckAt: number | null;
  /**
   * M4′ Task 3: **只读模式**。`true` = 手机那边所有写操作都被服务端拒掉(403 `forbiddenPath`),
   * 读操作照旧。这一份是**盘上那一份**在内存里的镜像(`server::ServerStatus.read_only`),
   * 面板的开关状态只由它说了算 —— 前端不自己猜"点了应该开了吧"。
   */
  readOnly: boolean;
}

/**
 * 配对二维码(`server::PairingQr`, serde camelCase)—— M4′ Task 1。
 *
 * `svg` 是**后端自己生成**的 `<svg …>` 源码(`qrcode` crate), 不是任何外部输入,
 * 所以直接 `dangerouslySetInnerHTML` 塞进 DOM 是安全的(也是选 SVG 的理由: 不必引 JS
 * 二维码库, 也不必为一张 PNG 去放宽 asset scope)。
 *
 * `payload` 是二维码里装的那段 JSON —— 面板**不用**它(地址与配对码本来就都显示在下面),
 * 它存在只是为了"二维码里到底装了什么"这件事在协议层可被断言。
 */
interface PairingQr {
  svg: string;
  url: string;
  payload: string;
}

/**
 * "谁在连我"(`server::routes::ClientsSnapshot`, serde camelCase)—— M4′ Task 3。
 *
 * ⚠️ **这不是在线设备清单**: `clients` 里每一行是**一次请求**。`ip` 是 `null` 时表示这一层
 * 拿不到来源地址(诚实地说"不知道", 而不是填一个假地址); 这一层**没有**设备名与位置 ——
 * 那两样 HTTP 读不到, 所以后端一个字段都不编(见 `server::routes::ClientRequest` 的注释)。
 */
interface ClientsSnapshot {
  clients: { ip: string | null; method: string; path: string; at: number }[];
  /** 缓冲容量(最多留多少条)。 */
  capacity: number;
  /** 此刻缓冲里真正有多少条。 */
  recorded: number;
}

/**
 * 本机"手机可能能用的地址"快照 —— 与 `src-tauri/src/server/network.rs` 的 `NetHints`
 * 一一对应(serde camelCase)。
 *
 * `lan` 只包含**私有网段**地址(公网地址只在 `publicIpv4` 里, 只用于警告); `profile` 是
 * Windows 的网络类别; `firewallHint` 是一个**机器码**, 不是文案 —— 文案按当前语言在这里查表
 * (见下面 `FIREWALL_HINT_ALLOW_PRIVATE`)。
 */
interface NetHints {
  lan: { address: string; interface: string }[];
  publicIpv4: string[];
  profile: "private" | "public" | "unknown";
  firewallHint: string;
}

/**
 * 后端唯一会发的防火墙提示码(`network.rs` 的 `FIREWALL_HINT_ALLOW_INBOUND_PRIVATE`)。
 * 不认识的码落到 `server.firewallHintGeneric` —— 后端加档时面板不会变成空白。
 */
const FIREWALL_HINT_ALLOW_PRIVATE = "allowInboundPrivateProfile";

/**
 * 后端**网络防护的拒绝理由**里必定出现的一句(`network.rs::GUARD_REFUSAL_MARKER`)。
 *
 * 面板只有在探测快照拿不到(`hints === null`)时才用它: 那时 `profile` 无从得知, 但
 * `lastError` 里装着后端那句拒绝 —— 认出来就必须把二次确认入口摆出来(minor ④), 否则后端会拒、
 * 面板却没有按钮, 用户手上那个开关变成"点了就弹一句拒绝、怎么都开不起来"。
 *
 * 耦合方向是**单向可查的**: Rust 侧有一条测试钉住"两句拒绝理由都含这个标记"(改文案会红),
 * 所以这里不会因为后端改了句子而悄悄失效。
 */
const GUARD_REFUSAL_MARKER = "确认要在";

/**
 * 设置里的「图库服务」面板(Phase 8)。**全部长在设置对话框里** —— 不新增窗口、不动布局。
 *
 * 它只做四件事, 每件都对着一个用户真的会问的问题:
 *   ① 现在到底是开是关、**有没有真的在监听**(开 ≠ 在跑, 这两件事分开显示);
 *   ② 配对码: 看得见、能复制、能轮换(轮换两段确认 —— 它会让所有手机重新配对);
 *   ③ 共享目录的增删(空 = 服务对所有路径回"无权限", 这是有意的安全默认, 面板要说清);
 *   ④ 起不来时给出**可行动**的理由 + 一张排查清单;
 *   ⑤ M4′ Task 2: 手机该填哪个地址(私有地址 + 网卡名, 一键复制)、**防火墙**怎么放行、
 *      "当前网络是公用"时的**警告 + 二次确认入口**、检测到非局域网地址时的警告。
 *   ⑥ 2026-10-25 mDNS 配对: 配对窗口(5 分钟倒计时)、待确认请求(允许/拒绝)、已连接设备
 *      (移除)。扫码 / 地址 / 配对码整体收进「高级 / 手动配对」折叠区 —— 层级变了,
 *      但行为与每一句诚实文案都原样保留(它们仍是配对窗口用不了时的退路)。
 *
 * 纪律: 状态永远以后端为准(`get_server_status`) —— 每次动作之后再读一次, 面板开着时每 4 秒
 * 再读一次。前端**不**自己猜"应该开着了吧": `enabled`/`running`/`lastError` 三个字段合起来
 * 才是真相, 而真相只有 `server::status_of_state` 说得清。
 *
 * **本面板不提供任何公网暴露 / 端口转发 / 隧道入口**(规格 §4.3 明确不做): 检测到不是局域网的
 * 地址时**只警告** —— 不做成一键, 也不给引导。
 */
function ServerPanel({ open }: { open: boolean }) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<ServerStatus | null>(null);
  /** 本机探测快照(私有地址 / 网络类别 / 防火墙码)。`null` = 没读到 → 那两块整个不渲染。 */
  const [hints, setHints] = useState<NetHints | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  /** 至少成功读到过一次状态 —— 决定一条失败信息该说"读不到状态"还是"这个操作失败了"。 */
  const [loadedOnce, setLoadedOnce] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  /** 刚从地址列表里复制走的那一条(按钮高亮 1.5 秒), 与配对码那个 `copied` 分开。 */
  const [copiedAddr, setCopiedAddr] = useState<string | null>(null);
  const [confirmRotate, setConfirmRotate] = useState(false);
  const [draftPath, setDraftPath] = useState("");
  const [showTips, setShowTips] = useState(false);
  /** 配对二维码(M4′ Task 1)。`null` = 这次没有二维码; `qrKnown` 才区分"没有"与"还没问到"。 */
  const [qr, setQr] = useState<PairingQr | null>(null);
  /**
   * 是否**问过**一次二维码(`get_server_pairing_qr` 回来了)。为什么需要一个单独的布尔:
   * 后端在"没有私有地址"或"还没有配对码"时回的就是 `null` —— 与"这次 IPC 没成功"是两件事。
   * 少了它, 一次失败的调用会被面板说成"还没有配对码", 那就成了一句假话。
   */
  const [qrKnown, setQrKnown] = useState(false);
  /** 最近连接(M4′ Task 3)。**不进 4 秒轮询** —— 手动刷新即可(计划 §Task 3 Step 2)。 */
  const [clients, setClients] = useState<ClientsSnapshot | null>(null);
  /**
   * mDNS 配对状态(计划 2026-10-25 §0.3, 契约见 `src/lib/pairing.ts`)。
   * `null` = 还没读到(或后端还没实现) —— 那时**不**画"窗口已关闭", 因为"读不到"不是"关着"。
   */
  const [pair, setPair] = useState<PairingStatus | null>(null);
  const [pairError, setPairError] = useState<string | null>(null);
  /** 「高级 / 手动配对」折叠区。默认收起: 扫码与手输是退路, 不是主路径(计划 Task B3)。 */
  const [showAdvanced, setShowAdvanced] = useState(false);
  /** 配对窗口倒计时的"现在"。只在窗口开着时每秒走一次(见下面的 effect)。 */
  const [pairNow, setPairNow] = useState(() => Date.now());

  const load = useCallback(async () => {
    try {
      setStatus(await invoke<ServerStatus>("get_server_status"));
      setProblem(null);
      setLoadedOnce(true);
    } catch (err) {
      // 拿不到状态就如实说拿不到(纯浏览器探针里没有 Tauri 注入, 这里必然走到) ——
      // 绝不能用一份猜测的状态把面板撑起来。
      setProblem(String(err));
    }
  }, []);

  /**
   * 读本机探测快照。**只在打开面板与每次动作之后读**, 不进 4 秒轮询 —— 它要枚举网卡并向
   * Windows 的网络列表问一次, 没必要每 4 秒做一遍; "网络刚换了"这件事用户自己会点"重新检测"。
   *
   * 拿不到**不设 `problem`**: 后端那一步不会失败(任何失败都降级成"这一项没有值"), 走到这里
   * 只可能是 IPC 不通(浏览器探针)—— 那时状态那块已经报了同一件事。拿不到就 `null` → 地址块
   * 整个不渲染, 绝不拿一份猜的列表撑场面。
   */
  const loadHints = useCallback(async () => {
    try {
      setHints(await invoke<NetHints>("get_server_network_hints"));
    } catch {
      setHints(null);
    }
  }, []);

  /**
   * 读配对二维码。与 `loadHints` 同一档代价(要枚举网卡 + 问一次 Windows 的网络类别),
   * 所以同样**不进 4 秒轮询**, 只在打开面板、每次动作之后与"重新检测"时读。
   *
   * 失败时把 `qrKnown` 置回 `false`: 拿不到就什么都不说, 绝不把一次 IPC 失败说成
   * "还没有配对码"。
   */
  const loadQr = useCallback(async () => {
    try {
      setQr(await invoke<PairingQr | null>("get_server_pairing_qr"));
      setQrKnown(true);
    } catch {
      setQr(null);
      setQrKnown(false);
    }
  }, []);

  /**
   * 读最近连接。**纯内存读**(不碰磁盘、不碰网络), 所以它在打开面板与每次动作之后都读一次
   * 是廉价的; 但它**不做定时刷新** —— 这个列表晚几秒对用户没有损失, 为它搭一条推送通道
   * 不划算(计划 §Task 3 Step 2)。
   */
  const loadClients = useCallback(async () => {
    try {
      setClients(await invoke<ClientsSnapshot>("get_server_clients"));
    } catch {
      setClients(null);
    }
  }, []);

  /**
   * 读配对状态(纯内存读, 与 `pairing_status` 一一对应)。同样不进 4 秒轮询:
   * 配对的变化只有两种来源 —— 用户在这个面板上动作, 或手机发来请求(有事件)。
   *
   * 失败时**不清掉上一份**: 一次 IPC 抖动不该把已有的设备清单说成"没有设备";
   * 失败本身由 `pairError` 如实报出来(`pairing.unavailable`)。
   */
  const loadPairing = useCallback(async () => {
    try {
      setPair(await pairingStatus());
      setPairError(null);
    } catch (err) {
      setPairError(String(err));
    }
  }, []);

  useEffect(() => {
    if (!open) return; // 关着的时候不轮询: 一次 invoke 也不该白花
    void load();
    void loadHints();
    void loadQr();
    void loadClients();
    void loadPairing();
    const id = window.setInterval(() => void load(), 4000);
    return () => window.clearInterval(id);
  }, [open, load, loadHints, loadQr, loadClients, loadPairing]);

  /**
   * 面板开着时跟随配对事件:
   *   · `pairing-request` 只带**一条**新请求 —— 重读一次权威状态即可(不自己拼列表);
   *   · `pairing-changed` 本身就是一份完整 `PairingStatus`(批准/拒绝/过期/移除之后),
   *     直接覆盖。
   * 事件通道在浏览器探针 / 尚未实现的后端里不存在; 那不是错误 —— 面板靠每次动作后的
   * 重读照旧工作, 只是不会自己跳出新请求。
   */
  useEffect(() => {
    if (!open) return;
    let alive = true;
    let offRequest: (() => void) | undefined;
    let offChanged: (() => void) | undefined;
    void (async () => {
      try {
        const off = await onPairingRequest(() => { if (alive) void loadPairing(); });
        if (alive) offRequest = off; else off();
      } catch { /* 没有事件通道: 忽略 */ }
      try {
        const off = await onPairingChanged((s) => { if (alive) setPair(s); });
        if (alive) offChanged = off; else off();
      } catch { /* 同上 */ }
    })();
    return () => {
      alive = false;
      offRequest?.();
      offChanged?.();
    };
  }, [open, loadPairing]);

  /**
   * 5 分钟倒计时。只在窗口真的开着时每秒走一次; 打开窗口那一下先把"现在"对一次表
   * (初始 state 可能是几分钟前挂载时取的, 直接拿来算会多出好几分钟)。
   * `useLayoutEffect`: 在 paint 前对表, 避免窗口刚读到 / 刚打开的那一帧显示一个错的剩余时间。
   */
  useLayoutEffect(() => {
    if (!open || !pair?.open) return;
    setPairNow(Date.now());
    const id = window.setInterval(() => setPairNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [open, pair?.open, pair?.expiresAt]);

  /**
   * 所有动作共用同一条收尾: 忙 → 做 → 失败就显示后端那句人话 → **无论如何**重读一次权威状态。
   *
   * 重读是必需的, 不是保险: 开启失败时后端**刻意不回滚开关**(开关仍是"开"、服务没在跑、
   * 理由在 `lastError`), 只有重读才能让界面显示这个真实组合。探测结果一起重读: 一次动作之后
   * 网络类别可能已经变了(用户刚换 WiFi 再点开关)。
   */
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setProblem(null);
    try {
      await fn();
    } catch (err) {
      setProblem(String(err));
    } finally {
      setBusy(false);
      void load();
      void loadHints();
      // 二维码也要重读(Fix 1): 它就是"服务到底在不在监听"的另一面, 而**端口可能在这次动作里
      // 变过**(被占就顺延)。不重读, 上一次那份 `qr` 会在服务重新起来之后被画出来 ——
      // 那正好是这次要消灭的那种"图上是 8765、真正绑的是 8766"。
      void loadQr();
      // 一次动作之后"最近连接"里多半刚多了一行(例如刚被只读拦下的那次写), 顺带读一次。
      void loadClients();
    }
  };

  /**
   * 配对动作的收尾: 与 `run` 同一套纪律(忙 → 做 → 失败显示后端原话 → 重读权威状态),
   * 但**只重读配对状态** —— 开窗 / 批准 / 拒绝 / 移除都不会改变服务的监听状态,
   * 没必要连带重新枚举网卡。
   */
  const runPairing = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setProblem(null);
    try {
      await fn();
    } catch (err) {
      setProblem(String(err));
    } finally {
      setBusy(false);
      void loadPairing();
    }
  };

  /** 打开配对窗口。命令已经把 expiresAt 回来了, 先点亮倒计时, 权威状态随后覆盖。 */
  const openPairing = () => {
    // 服务没在监听时开窗口没有意义：不会广播，手机也发现不到这台电脑。
    if (!status?.running) return;
    runPairing(async () => {
      const window_ = await openPairingWindow();
      setPair((prev) => ({
        open: true,
        expiresAt: window_.expiresAt,
        pending: prev?.pending ?? [],
        paired: prev?.paired ?? [],
      }));
      setPairNow(Date.now());
    });
  };

  const closePairing = () =>
    runPairing(async () => {
      await closePairingWindow();
      setPair((prev) => (prev ? { ...prev, open: false, expiresAt: undefined } : prev));
    });

  /** 允许 / 拒绝共用一条路径: 成功后先把这一条移出列表, 事件与重读会再校准一次。 */
  const resolvePairing = (requestId: string, allow: boolean) =>
    runPairing(async () => {
      if (allow) await approvePairing(requestId);
      else await denyPairing(requestId);
      setPair((prev) =>
        prev ? { ...prev, pending: prev.pending.filter((p) => p.requestId !== requestId) } : prev,
      );
    });

  /** v1 的「移除」只是忘记; 不吊销 token(要吊销走轮换, 见 pairing.forgetNote)。 */
  const forgetDevice = (deviceId: string) =>
    runPairing(async () => {
      await forgetPairedDevice(deviceId);
      setPair((prev) =>
        prev ? { ...prev, paired: prev.paired.filter((d) => d.deviceId !== deviceId) } : prev,
      );
    });

  const copyToken = async () => {
    const token = status?.token ?? "";
    if (!token) return;
    const done = () => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    };
    try {
      await navigator.clipboard.writeText(token);
      done();
    } catch {
      // WebView 里 clipboard API 可能不可用(权限/非安全上下文): 用"选中 + execCommand"兜底。
      // 为一次复制去引 clipboard 插件不值当。
      const ta = document.createElement("textarea");
      ta.value = token;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.top = "-1000px";
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch { ok = false; }
      ta.remove();
      if (ok) done();
      else setProblem(t("server.copyFailed"));
    }
  };

  const rotateToken = async () => {
    // 两段式确认: 轮换会让**所有**手机重新配对, 一次误点代价不小; 而 WebView 里
    // window.confirm 的行为不一致, 走对话框插件又要多一次 IPC —— 就地两段最稳、也最可读。
    if (!confirmRotate) {
      setConfirmRotate(true);
      window.setTimeout(() => setConfirmRotate(false), 5000);
      return;
    }
    setConfirmRotate(false);
    await run(() => invoke("rotate_server_token"));
  };

  const addShare = async () => {
    const path = draftPath.trim();
    if (!path) return;
    await run(async () => {
      await invoke<string[]>("add_share", { path });
      setDraftPath(""); // 只有成功才清空: 失败时留着输入, 用户改一改就能重试
    });
  };

  const removeShare = (path: string) => run(() => invoke<string[]>("remove_share", { path }));

  const browseForShare = async () => {
    try {
      const picked = await openDialog({ directory: true, title: t("server.sharesLabel") });
      if (typeof picked === "string" && picked) setDraftPath(picked);
    } catch (err) {
      setProblem(String(err));
    }
  };

  const token = status?.token ?? "";
  const shares = status?.shares ?? [];
  const disabled = busy || !status;

  /** 把一条候选地址拼成手机要填的完整 URL(端口取状态里那个)。 */
  const lanUrl = (address: string) => `http://${address}:${status?.port ?? 0}`;

  const copyAddress = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      setCopiedAddr(url);
      window.setTimeout(() => setCopiedAddr((v) => (v === url ? null : v)), 1500);
    } catch {
      // 复制不了不是致命的: 地址本身就是可见、可选的文本(见那段 <code> 的 select-all)。
      setProblem(t("server.copyFailed"));
    }
  };

  /** 用户点了"我了解风险，仍要开启" —— **只有这一个动作**会带 `confirmPublic: true`。 */
  const confirmAndEnable = () =>
    run(() => invoke("set_server_enabled", { enabled: true, confirmPublic: true }));

  /**
   * 什么时候显示"警告 + 二次确认入口": 当前网络**不是"专用"**那一档的时候。
   *   · `public` —— 后端一定会拒(网络被 Windows 标为公用);
   *   · `unknown` —— 后端**也**一定会拒(Fix 1 之后: 读不出类别同样要求确认, fail-closed)。
   *
   * ⚠️ 这一条**不是**判定本身: 能不能开只由后端的 `network::should_refuse_to_start` 说了算。
   * 前端这一份最多让入口早显示或晚显示一会儿, **绝不会**让一次公用网络上的开启静默发生 ——
   * 那才是规格 §4.3 要的那条保证。反过来也成立: 这里少显示了一次, 用户点开关时后端仍会拒并
   * 把理由放进 `lastError`(下一块就会显示出来)。
   *
   * ── minor ④ · 快照拿不到时的兜底 ──────────────────────────────────────────
   * `hints === null`(探测那一次 IPC 失败)时上面这条推出 `null` → 整块(含那个确认按钮)消失,
   * 而后端照样会拒 —— 用户被卡在"开关点不动、也没有确认入口"。所以快照拿不到时改看**理由**:
   * `lastError` 里装着那道防护的拒绝, 就按 `unknown` 渲染(标题那句"读不到当前网络的类别"
   * 对"快照拿不到"这个现场也是实话; 究竟是公用还是读不出来, 上面那块 `lastError` 已经写明)。
   * 快照**读到了**就以它为准 —— 那时理由可能是上一次动作留下的旧话, 不该拿它盖住新事实。
   */
  const guardRefusedByBackend = (status?.lastError ?? "").includes(GUARD_REFUSAL_MARKER);
  const guardProfile: "public" | "unknown" | null = hints
    ? hints.profile !== "private"
      ? hints.profile
      : null
    : guardRefusedByBackend
      ? "unknown"
      : null;

  const firewallHintText =
    hints?.firewallHint === FIREWALL_HINT_ALLOW_PRIVATE
      ? t("server.firewallHint")
      : t("server.firewallHintGeneric");

  /** "这次确认过"那条记录(后端 `ServerStatus.publicAckAt`)。 */
  const ackText = (() => {
    const at = status?.publicAckAt ?? null;
    if (at === null) return null;
    // 0 = 确认过, 但系统时钟读不出来(见后端 `record_public_ack`)。
    return at > 0
      ? t("server.publicAck", { time: new Date(at * 1000).toLocaleString() })
      : t("server.publicAckNoTime");
  })();

  /**
   * 配对窗口是否**还**开着。`expiresAt` 已过就按"关了"渲染: 倒计时走到 0 之后
   * 还画着「已打开 · 剩余 0:00」是句假话。后端下次被问到时也会给出 open=false
   * (重新开门时会拿到新的 expiresAt, 所以这里不会挡住正常操作)。
   */
  const pairingWindowActive =
    !!pair && pair.open && (pair.expiresAt === undefined || pair.expiresAt > pairNow);
  const pairingRemaining =
    pair?.expiresAt !== undefined ? formatCountdown(pair.expiresAt - pairNow) : null;

  // 状态行 —— 三句话各自对应一个真实状态, 不合并、不含糊:
  //   · 开着且在跑 → 把地址念出来(用户就是拿它填手机的);
  //   · 开着但没在跑 → 直说"你要它开, 它没在跑"(理由在下面的错误块里);
  //   · 没开 → 说"已关闭", 并**带上那句有界窗口的说明** —— "关"不等于"信号发出的一瞬间
  //     所有连接都断了"(serve 任务收敛前端口还能完成握手, 实测最坏 63ms)。
  const stateText = !status
    ? null
    : status.enabled && status.running
      ? t("server.listening", { url: status.url })
      : status.enabled
        ? t("server.wantedNotRunning")
        : t("server.offNote");

  return (
    <div className="min-w-0 border-t border-border pt-3 space-y-2">
      {/* 标题行: 状态点 + 标题 + 开/关胶囊 + 开关。开关是**用户的选择**, 胶囊是"这一份选择
          在盘上写着什么", 状态行是"此刻的真相" —— 三者一起才说清, 因为"开着但没在跑"正是最
          需要被看见的那个组合。 */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className={`inline-block size-2 rounded-full shrink-0 ${status?.running ? "bg-emerald-500" : "bg-muted-foreground/40"}`}
            />
            <p className="text-sm text-foreground">{t("server.title")}</p>
            <span className="text-xs text-muted-foreground">
              {status ? (status.enabled ? t("server.on") : t("server.off")) : ""}
            </span>
          </div>
          <p className="text-xs text-muted-foreground break-words">{stateText ?? t("server.switchDesc")}</p>
        </div>
        <Toggle
          checked={status?.enabled ?? false}
          disabled={disabled}
          onChange={() =>
            void run(() =>
              invoke("set_server_enabled", {
                enabled: !(status?.enabled ?? false),
                // 开关本身**永不**带确认: 公用网络下它会被后端拒(理由进 lastError), 要开就
                // 必须走下面那个显式确认按钮。于是"静默开启"在结构上不成立。
                confirmPublic: false,
              }),
            )
          }
        />
      </div>

      {/* ── M4′ Task 2 · 网络防护: 警告 + 二次确认 ──
          「公用」正是"不可信局域网"那一档: 明文 HTTP 下配对码可能被同网段设备嗅探(规格 §4.3)。
          「读不到类别」(unknown)在后端是**同一档严格程度**(Fix 1: 只有读到"专用"才允许静默
          启动), 所以这里也只给标题 + 同一个确认入口 —— 两档都靠 `guardProfile` 驱动, 见上。
          确认**只对这一次开启有效** —— 它由后端记进状态(`publicAckAt`), 重启后重新问一次。 */}
      {guardProfile !== null && (
        <div className="rounded-md border border-amber-500/50 bg-amber-500/10 px-2 py-1.5 text-xs text-muted-foreground space-y-1">
          <p className="font-medium text-foreground">
            {guardProfile === "public" ? t("server.publicTitle") : t("server.profileUnknown")}
          </p>
          {guardProfile === "public" && (
            <>
              <p className="break-words">{t("server.publicWarn")}</p>
              <p className="break-words">{t("server.publicOffer")}</p>
            </>
          )}
          {/* 二次确认按钮只在"没在跑"时出现: 已经在跑说明这一次开启**已经**确认过了(状态里那条
              `publicAckAt` 就是它), 再摆一个"仍要开启"只会让人以为哪里没点上。 */}
          {!status?.running && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void confirmAndEnable()}
              className="px-2 py-1 rounded-md border border-amber-500/60 text-xs text-foreground hover:bg-amber-500/20 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {t("server.publicConfirm")}
            </button>
          )}
        </div>
      )}

      {/* "这次开启是有人确认过的"那条记录(仅本次运行)。它不是豁免凭证 —— 每次开启都要当场确认。 */}
      {ackText && <p className="text-xs text-muted-foreground">{ackText}</p>}

      {/* 检测到不是局域网的地址: **只警告**。这里刻意没有任何"端口转发 / 公网暴露"入口
          (规格 §4.3 明确不做, 也不做成一键)。 */}
      {hints && hints.publicIpv4.length > 0 && (
        <div className="rounded-md border-l-2 border-amber-500/70 bg-muted/40 px-2 py-1.5 text-xs text-muted-foreground space-y-0.5">
          <p className="font-medium text-foreground">{t("server.publicIpTitle")}</p>
          <p className="break-words">
            {t("server.publicIpWarn", { list: hints.publicIpv4.join("、") })}
          </p>
        </div>
      )}

      {/* 安全警告: 左侧那条琥珀色竖线是"这是条警告"的唯一视觉信号 —— 文字色仍用
          muted-foreground(两个主题下都过对比度, 不为一句文案另造一个颜色)。 */}
      <p className="rounded-md border-l-2 border-amber-500/70 bg-muted/40 px-2 py-1.5 text-xs text-muted-foreground">
        {t("server.warn")}
      </p>

      {(problem || status?.lastError) && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 px-2 py-1.5 text-xs text-destructive space-y-0.5">
          {status?.lastError && (
            <p className="break-words">
              <span className="font-medium">{t("server.lastError")}: </span>
              {status.lastError}
            </p>
          )}
          {problem && (
            <p className="break-words">
              {loadedOnce
                ? t("server.actionFailed", { reason: problem })
                : t("server.unavailable", { reason: problem })}
            </p>
          )}
        </div>
      )}

      {/* ── 2026-10-25 · mDNS 配对(主路径) ──
          手机在同一 WiFi 下自动发现这台电脑, 用户在电脑上点「允许」才算配对成功。
          这一块只做三件事, 每一件都对应 §0.3 的一条契约:
            · 配对窗口: 开 / 关 + 5 分钟倒计时(`open_pairing_window` / `close_pairing_window`);
            · 待确认请求: 允许 / 拒绝(`approve_pairing` / `deny_pairing`) —— 请求只活 60 秒;
            · 已连接设备: 移除(`forget_paired_device`) —— v1 里移除**不吊销** token,
              文案(pairing.forgetNote)必须把这件事说清, 否则用户会以为已经踢下线。
          `pair === null`(还没读到 / 后端还没有这些命令)时**不**画"窗口已关闭": 读不到 ≠ 关着。
          扫码 / 地址 / 配对码整块收进下面的「高级 / 手动配对」, 行为与文案原样保留。 */}
      <div className="space-y-1">
        <p className="text-sm text-foreground">{t("pairing.sectionTitle")}</p>
        <p className="text-xs text-muted-foreground break-words">{t("pairing.windowHint")}</p>

        {pairError && (
          <p className="text-xs text-muted-foreground break-words">
            {t("pairing.unavailable", { reason: pairError })}
          </p>
        )}

        {pair && (
          <>
            {pairingWindowActive ? (
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs text-emerald-600 dark:text-emerald-400 break-words">
                  {t("pairing.windowOpen")}
                  {pairingRemaining !== null
                    ? ` · ${t("pairing.remaining", { time: pairingRemaining })}`
                    : ""}
                </p>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void closePairing()}
                  className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {t("pairing.closeWindow")}
                </button>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs text-muted-foreground break-words">
                  {status?.running ? t("pairing.windowClosed") : t("pairing.serviceOff")}
                </p>
                <button
                  type="button"
                  disabled={busy || !status?.running}
                  onClick={() => void openPairing()}
                  className="shrink-0 px-2 py-1 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {t("pairing.openWindow")}
                </button>
              </div>
            )}

            {pair.pending.length > 0 && (
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">{t("pairing.pendingTitle")}</p>
                <ul className="space-y-1 min-w-0">
                  {pair.pending.map((p) => (
                    <li key={p.requestId} className="flex items-center gap-2 min-w-0">
                      <div className="flex-1 min-w-0">
                        <p className="truncate text-xs text-foreground" title={p.deviceName}>
                          {p.deviceName}
                        </p>
                        <p className="truncate text-xs text-muted-foreground" title={p.ip}>
                          {p.ip}
                        </p>
                      </div>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void resolvePairing(p.requestId, true)}
                        className="shrink-0 px-2 py-1 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {t("pairing.allow")}
                      </button>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void resolvePairing(p.requestId, false)}
                        className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {t("pairing.deny")}
                      </button>
                    </li>
                  ))}
                </ul>
                <p className="text-xs text-muted-foreground break-words">{t("pairing.pendingDesc")}</p>
              </div>
            )}

            <div className="space-y-1">
              <p className="text-xs text-muted-foreground">{t("pairing.pairedDevices")}</p>
              {pair.paired.length === 0 ? (
                <p className="text-xs text-muted-foreground">{t("pairing.noDevices")}</p>
              ) : (
                <ul className="space-y-1 min-w-0">
                  {pair.paired.map((d) => (
                    <li key={d.deviceId} className="flex items-center gap-2 min-w-0">
                      <span className="flex-1 min-w-0 truncate text-xs text-foreground" title={d.name}>
                        {d.name}
                      </span>
                      {d.lastSeenAt > 0 && (
                        <span className="shrink-0 text-xs text-muted-foreground">
                          {t("pairing.lastSeen", {
                            time: new Date(d.lastSeenAt).toLocaleString(),
                          })}
                        </span>
                      )}
                      <button
                        type="button"
                        disabled={busy}
                        aria-label={`${t("pairing.forget")}: ${d.name}`}
                        onClick={() => void forgetDevice(d.deviceId)}
                        className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {t("pairing.forget")}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <p className="text-xs text-muted-foreground break-words">{t("pairing.forgetNote")}</p>
            </div>
          </>
        )}
      </div>

      {/* ── M4′ Task 3 · 只读模式 ──
          它是一条**能力开关**, 与总开关并列但管的是不同的东西: 总开关决定"手机连不连得上",
          这一条决定"连上之后能不能改"。开了之后所有写端点回 403 `forbiddenPath`(复用既有码,
          规格 §5.3), 读端点照常 —— 所以"读图不受影响"这句必须写出来, 否则用户会以为手机废了。
          状态永远以后端为准(`status.readOnly`), 前端不自己猜。 */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <p className="text-xs text-muted-foreground">{t("server.readOnly")}</p>
          <p className="text-xs text-muted-foreground break-words">{t("server.readOnlyDesc")}</p>
        </div>
        <Toggle
          checked={status?.readOnly ?? false}
          disabled={disabled}
          onChange={() =>
            void run(() =>
              invoke("set_server_read_only", { readOnly: !(status?.readOnly ?? false) }),
            )
          }
        />
      </div>
      {status?.readOnly && (
        <p className="text-xs text-muted-foreground break-words">{t("server.readOnlyNote")}</p>
      )}

      {/* ── M4′ Task 2 · 防火墙(规格 §4.2 第 2 条: 局域网模式下"连不上"的第一大原因) ──
          文案按后端给的**机器码**查表(未知码落到通用那句), 所以它跟着界面语言走 ——
          后端不把中文塞进这里。
          它刻意留在「高级 / 手动配对」**外面**: mDNS 发现同样要过防火墙这一关,
          收进默认收起的折叠区就等于把"连不上的第一大原因"藏起来。 */}
      {hints && (
        <div className="space-y-0.5">
          <p className="text-xs text-muted-foreground">{t("server.firewallHintTitle")}</p>
          <p className="rounded-md border-l-2 border-amber-500/70 bg-muted/40 px-2 py-1.5 text-xs text-muted-foreground">
            {firewallHintText}
          </p>
        </div>
      )}

      {/* ── 2026-10-25 · 高级 / 手动配对(默认收起) ──
          配对窗口是主路径; 扫码 / 局域网地址 / 配对码原样保留在这里 —— 它们仍是
          "配对窗口用不了"时唯一能走的路(规格 §4.2 第 1/3 条), 只是不再第一眼就看到。
          **只折叠层级**: 每一句说明、每个按钮的行为都没变。 */}
      <div>
        <button
          type="button"
          aria-expanded={showAdvanced}
          onClick={() => setShowAdvanced((v) => !v)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <span aria-hidden="true">{showAdvanced ? "▾" : "▸"}</span>
          {t("pairing.advanced")}
        </button>
        <Collapse open={showAdvanced}>
          <div className="space-y-2 pt-1">
            <p className="text-xs text-muted-foreground break-words">{t("pairing.advancedHint")}</p>

      {/* ── M4′ Task 2 · 局域网地址: 手机配对时填的就是这里的地址(规格 §4.2 第 1 条) ──
          只列**私有网段**的地址(公网地址一个都不在这里, 只在上面的警告里); 每条后面是网卡名
          —— 虚拟网卡(VMware/WSL/虚拟机)的地址多半连不上, 所以后端**只排序不丢弃**, 让用户
          照着网卡名自己判断。"重新检测"是给"刚换了 WiFi"那个现场用的(它不进 4 秒轮询);
          二维码与它同源(同一份探测), 所以这个按钮把两样一起重读。 */}
      {hints && (
        <div className="space-y-1">
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs text-muted-foreground">{t("server.lanTitle")}</p>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                void loadHints();
                void loadQr();
              }}
              className="px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {t("server.rescan")}
            </button>
          </div>
          {hints.lan.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("server.lanEmpty")}</p>
          ) : (
            <ul className="space-y-1 min-w-0">
              {hints.lan.map((l) => {
                const url = lanUrl(l.address);
                return (
                  <li key={l.address} className="flex items-center gap-2 min-w-0">
                    <code
                      className="flex-1 min-w-0 truncate rounded-md bg-muted/40 px-2 py-1 font-mono text-xs text-foreground select-all"
                      title={url}
                    >
                      {url}
                    </code>
                    <span
                      className="shrink-0 max-w-28 truncate text-xs text-muted-foreground"
                      title={l.interface}
                    >
                      {l.interface}
                    </span>
                    <button
                      type="button"
                      disabled={busy}
                      aria-label={`${t("server.copy")}: ${url}`}
                      onClick={() => void copyAddress(url)}
                      className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {copiedAddr === url ? t("server.copied") : t("server.copy")}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          <p className="text-xs text-muted-foreground">{t("server.lanDesc")}</p>
        </div>
      )}

      {/* ── M4′ Task 1 · 配对二维码: 手机扫一下, 地址与配对码自动填好(规格 §4.2 第 3 条) ──
          它与下面那两样是**同一份信息**的两种形态, 顺序也是刻意的: 先二维码(顺手的那条路),
          紧接着就是文本形式的地址与配对码(扫码失败时的退路, 计划 Step 2 明确要求保留)。

          四种状态各自说实话, 不合并:
            · **服务没在监听** → 不画图, 说清原因(`qrNotRunning`)。这一条必须在最前面:
              二维码**看起来**比一句话权威, 而 `status.running` 才回答"手机到底连不连得上"。
              没这道闸, 服务关着时面板会画一张装**配置端口**的码 —— 那个端口此刻没人听,
              甚至可能因为端口被占而与实际绑定的端口不同(Fix 1);
            · `qr` 有值 → 画图 + 一行"用手机扫这个码" + 一行"扫不出来就手输";
            · `qrKnown` 且没有图、但**有**局域网地址 → 只可能是"还没有配对码"(新装/轮换中),
              由 `qrNoToken` 说明;**绝不**画一个空框;
            · 局域网地址本来就没有 → 上面那块 `lanEmpty` 已经说清了(后端也不会回落到公网地址
              或 127.0.0.1 —— 理由见 `server::pairing_of`), 这里一个字都不加, 免得说两遍。

          `dangerouslySetInnerHTML` 在这里是安全的: 这段 SVG 是**后端自己用 `qrcode` 编出来的**,
          内容里只有地址与配对码, 不是任何外部输入。 */}
      {status && !status.running ? (
        // `status === null`(状态还没读到)时不走到这里: 那是"不知道", 不是"没在跑",
        // 拿它当"没在跑"会在面板刚打开的那一瞬间说一句没根据的话。
        <p className="text-xs text-muted-foreground break-words">{t("server.qrNotRunning")}</p>
      ) : qr ? (
        <div className="space-y-1">
          <p className="text-xs text-muted-foreground">{t("server.qrTitle")}</p>
          <div
            // 尺寸只在这里定: 后端给的是**矢量**, 放大不糊, 所以显示盒子可以放心放大到
            // 面板宽度(最多 256px)。`h-auto` + `w-full` 靠 SVG 自带的 viewBox 保持正方形。
            // 白底是给静区兜底: 后端已经开了 quiet_zone, 这里只是不让深色主题把边距染灰。
            className="w-full max-w-64 rounded-md bg-white p-1 [&>svg]:block [&>svg]:h-auto [&>svg]:w-full"
            dangerouslySetInnerHTML={{ __html: qr.svg }}
          />
          <p className="text-xs text-muted-foreground break-words">{t("server.qrScanHint")}</p>
          <p className="text-xs text-muted-foreground break-words">{t("server.qrFallback")}</p>
        </div>
      ) : qrKnown && hints && hints.lan.length > 0 ? (
        <p className="text-xs text-muted-foreground break-words">{t("server.qrNoToken")}</p>
      ) : null}

      {/* 配对码: 单行等宽 + 可选中; 复制与轮换各一个按钮(轮换要按两下)。 */}
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">{t("server.tokenLabel")}</p>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={!token || busy}
              onClick={() => void copyToken()}
              className="px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {copied ? t("server.copied") : t("server.copy")}
            </button>
            <button
              type="button"
              disabled={!token || busy}
              onClick={() => void rotateToken()}
              className={`px-2 py-1 rounded-md border text-xs disabled:opacity-50 disabled:cursor-not-allowed ${
                confirmRotate
                  ? "border-destructive/50 text-destructive hover:bg-destructive/10"
                  : "border-border text-muted-foreground hover:bg-muted hover:text-foreground"
              }`}
            >
              {confirmRotate ? t("server.rotateConfirm") : t("server.rotate")}
            </button>
          </div>
        </div>
        <code className="block max-h-20 overflow-auto no-scrollbar rounded-md bg-muted/40 px-2 py-1 font-mono text-xs leading-4 text-foreground break-all select-all">
          {token || t("server.tokenEmpty")}
        </code>
        <p className="text-xs text-muted-foreground">{t("server.tokenDesc")}</p>
      </div>
          </div>
        </Collapse>
      </div>

      {/* 共享目录: 列表可删 + 一行"输入 / 浏览 / 添加"。空列表不是"没问题", 而是
          "谁都访问不了" —— 所以说的是 shareEmpty 那句话, 而不是一个空框。 */}
      <div className="space-y-1">
        <p className="text-xs text-muted-foreground">{t("server.sharesLabel")}</p>
        {shares.length === 0 ? (
          <p className="text-xs text-muted-foreground">{t("server.shareEmpty")}</p>
        ) : (
          <ul className="space-y-1 min-w-0">
            {shares.map((sh) => (
              <li key={sh} className="flex items-center gap-2 min-w-0">
                <span className="flex-1 min-w-0 truncate text-xs text-foreground" title={sh}>{sh}</span>
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`${t("server.remove")}: ${sh}`}
                  onClick={() => void removeShare(sh)}
                  className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {t("server.remove")}
                </button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex items-center gap-1.5">
          <Input
            value={draftPath}
            disabled={busy}
            placeholder={t("server.pathPlaceholder")}
            onChange={(e) => setDraftPath(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                void addShare();
              }
            }}
            className="h-7 flex-1 min-w-0 text-xs"
          />
          <button
            type="button"
            disabled={busy}
            onClick={() => void browseForShare()}
            className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("server.browse")}
          </button>
          <button
            type="button"
            disabled={disabled || !draftPath.trim()}
            onClick={() => void addShare()}
            className="shrink-0 px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("server.add")}
          </button>
        </div>
        <p className="text-xs text-muted-foreground">{t("server.sharesDesc")}</p>
      </div>

      {/* ── M4′ Task 3 · 最近连接("谁在连我") ──
          **手动刷新, 不做实时刷新**(计划 §Task 3 Step 2): 为一条低频信息搭推送通道不划算,
          而这个列表晚几秒对用户没有损失。打开面板与每次动作之后会各读一次。

          这里**只有** HTTP 这一层真的知道的三样: 来源 IP、请求的端点、时间。后端一个字段都
          没编(没有设备名、没有位置), 所以下面的说明文字必须把这件事讲清楚 —— 否则用户会拿
          一行 IP 当"设备清单"来读, 那比不显示更糟。查询串(里面是用户的文件路径)刻意不记。 */}
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">{t("server.clientsTitle")}</p>
          <button
            type="button"
            disabled={busy}
            onClick={() => void loadClients()}
            className="px-2 py-1 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {t("server.clientsRefresh")}
          </button>
        </div>
        {clients && clients.clients.length === 0 && (
          <p className="text-xs text-muted-foreground">{t("server.clientsEmpty")}</p>
        )}
        {clients && clients.clients.length > 0 && (
          <ul className="space-y-1 min-w-0">
            {clients.clients.map((c, i) => (
              // `at` 会撞(同一秒内好几条请求), 所以下标一起进 key —— 用得着"稳定且唯一"。
              <li key={`${c.at}-${i}`} className="flex items-center gap-2 min-w-0">
                <code className="shrink-0 rounded-md bg-muted/40 px-2 py-1 font-mono text-xs text-foreground">
                  {/* `ip` 是 null = 这一层真的不知道来源(后端不编假值), 所以这里说"未知"。 */}
                  {c.ip ?? t("server.clientsUnknownIp")}
                </code>
                <span
                  className="flex-1 min-w-0 truncate text-xs text-muted-foreground"
                  title={`${c.method} ${c.path}`}
                >
                  {c.method} {c.path}
                </span>
                <span className="shrink-0 text-xs text-muted-foreground">
                  {/* 0 = 系统时钟读不出来(后端给 0), 显示成 1970 年会是句假话。 */}
                  {c.at > 0
                    ? new Date(c.at * 1000).toLocaleString()
                    : t("server.clientsUnknownTime")}
                </span>
              </li>
            ))}
          </ul>
        )}
        {clients && clients.clients.length > 0 && (
          <p className="text-xs text-muted-foreground">
            {t("server.clientsCount", {
              recorded: clients.recorded,
              capacity: clients.capacity,
              shown: clients.clients.length,
            })}
          </p>
        )}
        <p className="text-xs text-muted-foreground break-words">{t("server.clientsDesc")}</p>
      </div>

      {/* 排查清单默认收起: 它不是每天都看的东西, 但连不上的时候要在同一屏里能找到
          (所以标题一直在, 点开就是那三条)。 */}
      <div>
        <button
          type="button"
          aria-expanded={showTips}
          onClick={() => setShowTips((v) => !v)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <span aria-hidden="true">{showTips ? "▾" : "▸"}</span>
          {t("server.tipsTitle")}
        </button>
        <Collapse open={showTips} className="pt-1">
          <ul className="space-y-0.5 pl-4 text-xs text-muted-foreground list-disc">
            <li>{t("server.sleepTip")}</li>
            <li>{t("server.firewallTip")}</li>
            <li>{t("server.appRunningTip")}</li>
          </ul>
        </Collapse>
      </div>
    </div>
  );
}

/** 设置项行: 标题 + 说明 + 右侧控件 */
function SettingRow({
  title, desc, children, dimmed = false,
}: {
  title: string; desc: string; children: React.ReactNode; dimmed?: boolean;
}) {
  return (
    <div className={`flex items-start justify-between gap-3 ${dimmed ? "opacity-50" : ""}`}>
      <div className="flex-1 min-w-0">
        <p className="text-sm text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{desc}</p>
      </div>
      <div className="flex items-center gap-2 shrink-0">{children}</div>
    </div>
  );
}

/** 透明度滑块 */
function OpacitySlider({
  value, onChange, disabled,
}: {
  value: number; onChange: (v: number) => void; disabled?: boolean;
}) {
  return (
    <>
      <input
        type="range" min={0} max={100} value={value} disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="flex-1 thumb-slider"
      />
      <span className="text-xs text-muted-foreground w-8 text-right">{value}%</span>
    </>
  );
}

export function SettingsDialog({
  open, onOpenChange, theme, onThemeChange, lang, onLangChange,
  preloadFull, onTogglePreloadFull, transparentBg, onToggleTransparentBg, micaUnsupported, platform,
  autoAdvance, onToggleAutoAdvance,
  labelModifier, onLabelModifierChange,
  xmpMode, onXmpModeChange, xmpStatus,
  lrcProbe, lrcMode, onLrcModeChange, onReprobeLightroom,
  glassOpacity, onGlassOpacityChange, backgroundOpacity, onBackgroundOpacityChange,
}: Props) {
  const { t } = useTranslation();
  // macOS 的能力与 Windows 不同(图库服务未验证 / LrC 衔接种不了) —— 按平台分支, 不只置灰
  const isMac = platform === "macos";

  // 版本号从**二进制**里读(getVersion), 不再写死在源码里 —— 原先那行字面量 "1.1.0"
  // 在 1.1.1 发布之后就变成了假话。__APP_VERSION__ 是构建期注入的兜底, 只为
  // 浏览器里(纯 vite / 探针)也能显示出版本, 真机上永远走 getVersion()。
  const [version, setVersion] = useState<string>(__APP_VERSION__);
  const [update, setUpdate] = useState<UpdateState>({ kind: "idle" });

  useEffect(() => {
    let alive = true;
    getVersion()
      .then((v) => { if (alive && v) setVersion(v); })
      .catch(() => { /* 拿不到就留兜底值, 不打扰用户 */ });
    return () => { alive = false; };
  }, []);

  function updateDetailText(d: UpdateDetail): string {
    switch (d.code) {
      case "network": return t("settings.updateErrNetwork");
      case "timeout": return t("settings.updateErrTimeout");
      case "http": return t("settings.updateErrHttp", { code: d.status });
      default: return t("settings.updateErrUnexpected");
    }
  }

  // 五种状态 → 同一个按钮的字面与悬停说明。
  // 刻意**不新增行、不换布局**: 结果替换按钮自己的字面, 这一行的几何和原来那行
  // 版本号逐像素相同 —— 否则设置面板会多出一条滚动条(见下方 max-h 的注释)。
  const { updateLabel, updateHint } = (() => {
    switch (update.kind) {
      case "checking":
        return { updateLabel: t("settings.checkingUpdate"), updateHint: undefined };
      case "latest":
        return { updateLabel: t("settings.updateLatest"), updateHint: t("settings.updateLatestHint") };
      case "available":
        return {
          updateLabel: t("settings.updateAvailable", { v: update.version }),
          updateHint: t("settings.updateAvailableHint"),
        };
      case "error":
        return {
          updateLabel: t("settings.updateFailed"),
          updateHint: t("settings.updateFailedHint", { reason: updateDetailText(update.detail) }),
        };
      default:
        return { updateLabel: t("settings.checkUpdate"), updateHint: t("settings.checkUpdateHint") };
    }
  })();

  const updateBusy = update.kind === "checking";

  // 有新版时这一下是"去下载页", 其余情况是"查一次"。
  // openUrl 走系统默认浏览器(不引第二个 http 权限, 也不在 webview 里开新窗口);
  // 兜底的 window.open 只在 non-Tauri 上下文(浏览器探针)里会被用到。
  const onUpdateClick = () => {
    if (updateBusy) return;
    if (update.kind === "available") {
      openUrl(update.url).catch(() => { window.open(update.url, "_blank", "noopener"); });
      return;
    }
    setUpdate({ kind: "checking" });
    checkForUpdate(version).then(setUpdate);
  };

  // 边车状态: 拼进上面那一行的 desc, **不新增整行** —— 加行要让对话框重新算总高(会话 ③ 第 28 项)。
  // 注意顺序: "不可写"的判断必须排在档位判断**之前** —— 自动降级会把档位改回 off,
  // 若先看档位, 用户就再也看不到"为什么被降级"了。
  const xmpStatusText = (() => {
    if (xmpStatus && !xmpStatus.writable) {
      return t("settings.xmpStatusUnwritable", {
        dir: xmpStatus.dir,
        reason: t(`xmp.err.${xmpErrKey(xmpStatus.code)}`),
      });
    }
    if (xmpMode !== "on") return t("settings.xmpStatusOff");
    if (!xmpStatus) return "";
    return t("settings.xmpStatusWritable", { dir: xmpStatus.dir }) + (xmpStatus.degraded ? t("settings.xmpStatusDegraded") : "");
  })();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* max-h 从 80vh 抬到 88vh + 行距 4→3: 让 9 行设置尽量一屏放下(会话 ③ 加了
          "颜色标签快捷键"那一行后, 内容超过 80vh → 右侧滚动条回来了)。
          no-scrollbar 兜底: 万一窗口太矮还是要滚, 滚轮/触控板照旧可用, 但不显示侧边滚动条。 */}
      <DialogContent className="w-[420px] max-h-[88vh] overflow-auto no-scrollbar">
        <DialogHeader>
          <DialogTitle>{t("settings.title")}</DialogTitle>
          <DialogDescription>{t("settings.subtitle")}</DialogDescription>
        </DialogHeader>
        {/* Phase 7 加了第 10 行(Lightroom), 而那一段又比其他行高一档(三档按钮 + 重新检测)。
            为守住"一屏放下、不出现滚动条"这条前置约束, 行距 3→2 并同步把这一行的
            py-1.5 收到 py-1(见该行): 两处必须一起改, 只收其中一处仍会超。 */}
        {/* min-w-0 **必须**在: DialogContent 是一张 grid, 而 grid item 的 `min-width` 默认是
            auto —— 于是这里面任何一个"不会换行的长串"(共享目录的完整路径就是)都会把 grid 轨道
            撑到超过对话框的 420px, 内容横向溢出(滚动条又被 no-scrollbar 藏掉), 右侧的"移除"
            按钮直接跑到框外。给它一个 min-w-0, 轨道就老老实实按 420px 算, 里面的 truncate /
            break-words 才真的生效。 */}
        <div className="min-w-0 space-y-2 py-2">
          <SettingRow title={t("settings.language")} desc={t("settings.languageDesc")}>
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {(["zh", "en"] as Lang[]).map((l) => (
                <button
                  key={l}
                  type="button"
                  onClick={() => onLangChange(l)}
                  className={`px-3 py-1.5 transition-colors ${lang === l ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {l === "zh" ? "中文" : "EN"}
                </button>
              ))}
            </div>
          </SettingRow>

          <SettingRow title={t("settings.theme")} desc={t("settings.themeDesc")}>
            <button
              type="button"
              onClick={() => onThemeChange(theme === "dark" ? "light" : "dark")}
              className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted flex items-center gap-1.5"
            >
              {theme === "dark"
                ? <Moon theme="filled" size="15" strokeWidth={3} />
                : <Sun theme="filled" size="15" strokeWidth={3} />}
              {theme === "dark" ? t("settings.dark") : t("settings.light")}
            </button>
          </SettingRow>

          <SettingRow title={t("settings.preload")} desc={t("settings.preloadDesc")}>
            <Toggle checked={preloadFull} onChange={onTogglePreloadFull} />
          </SettingRow>

          <SettingRow title={t("settings.autoAdvance")} desc={t("settings.autoAdvanceDesc")}>
            <Toggle checked={autoAdvance} onChange={onToggleAutoAdvance} />
          </SettingRow>

          {/* Phase 4: 颜色标签的修饰键。分段按钮与上面的"语言"同一套写法。
              之所以让用户选: Tauri 的 WebView2 可能把 Ctrl+数字当浏览器加速键吃掉 */}
          <SettingRow title={t("settings.labelKeys")} desc={t("settings.labelKeysDesc")}>
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {(["ctrl", "alt"] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => onLabelModifierChange(m)}
                  className={`px-3 py-1.5 transition-colors ${labelModifier === m ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {m === "ctrl" ? "Ctrl" : "Alt"}
                </button>
              ))}
            </div>
          </SettingRow>

          {/* Phase 5: XMP 边车三档。默认"询问"(会话 ⑥ 由"关闭"改来, 见 src/xmp.ts::DEFAULT_XMP_MODE) */}
          <SettingRow
            title={t("settings.xmpMode")}
            desc={`${t("settings.xmpModeDesc")}${xmpStatusText ? ` · ${xmpStatusText}` : ""}`}
          >
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {([
                ["off", t("settings.xmpModeOff")],
                ["ask", t("settings.xmpModeAsk")],
                ["on", t("settings.xmpModeOn")],
              ] as [XmpMode, string][]).map(([m, label]) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => onXmpModeChange(m)}
                  className={`px-3 py-1.5 transition-colors ${xmpMode === m ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                >
                  {label}
                </button>
              ))}
            </div>
          </SettingRow>

          {/* Phase 7: Lightroom 衔接。三段式与上面的 XMP 行同一套写法。
              探不到 LrC → 整行显示"未检测到"并给一个"重新检测"(用户可能刚装上)。
              "静默导入"这一档**刻意禁用**: 它需要用户先在 LrC 里配好自动导入的监听
              文件夹(本机实测从未配过), 现在给个能点但会报错的选项是骗人。 */}
          <SettingRow
            title={t("settings.lrcMode")}
            desc={(() => {
              if (isMac) return t("settings.lrcUnsupportedMac");
              if (!lrcProbe) return t("settings.lrcProbing");
              if (!lrcProbe.found) return t("settings.lrcNotFound");
              const where = lrcProbe.running ? t("settings.lrcRunning") : t("settings.lrcNotRunning");
              return `${lrcProbe.exe ?? ""} · ${where}`;
            })()}
          >
            <div className="flex rounded-md border border-border overflow-hidden text-sm">
              {([
                ["dialog", t("settings.lrcModeDialog")],
                ["silent", t("settings.lrcModeSilent")],
              ] as [LrcSendMode, string][]).map(([m, label]) => {
                const blocked = isMac || !isLrcModeUsable(m);
                return (
                  <button
                    key={m}
                    type="button"
                    disabled={blocked}
                    title={blocked ? t("settings.lrcModeSilentWhy") : undefined}
                    onClick={() => onLrcModeChange(m)}
                    className={`px-3 py-1 transition-colors ${
                      blocked
                        ? "text-muted-foreground/40 cursor-not-allowed"
                        : lrcMode === m
                          ? "bg-foreground text-background"
                          : "hover:bg-muted text-muted-foreground"
                    }`}
                  >
                    {label}
                  </button>
                );
              })}
              <button
                type="button"
                onClick={onReprobeLightroom}
                className="px-2 py-1 border-l border-border hover:bg-muted text-muted-foreground"
              >
                {t("settings.lrcReprobe")}
              </button>
            </div>
          </SettingRow>

          {/* Win10 置灰: Mica 在 build < 22000 上不存在, 这个开关打开也没有效果。
              说明文案也一起换掉 —— 只置灰不解释, 用户会以为坏了。 */}
          <SettingRow
            title={t("settings.transparentBg")}
            desc={isMac ? t("settings.transparentBgDescMac") : micaUnsupported ? t("settings.transparentBgUnsupported") : t("settings.transparentBgDesc")}
          >
            <Toggle checked={transparentBg} onChange={onToggleTransparentBg} disabled={micaUnsupported} />
          </SettingRow>

          <SettingRow title={t("settings.transparentBgOpacity")} desc={t("settings.transparentBgOpacityDesc")} dimmed={!transparentBg}>
            <div className="w-40 flex items-center gap-2">
              <OpacitySlider value={glassOpacity} onChange={onGlassOpacityChange} disabled={!transparentBg} />
            </div>
          </SettingRow>

          <SettingRow title={t("settings.backgroundOpacity")} desc={t("settings.backgroundOpacityDesc")} dimmed={!transparentBg}>
            <div className="w-40 flex items-center gap-2">
              <OpacitySlider value={backgroundOpacity} onChange={onBackgroundOpacityChange} disabled={!transparentBg} />
            </div>
          </SettingRow>

          {/* Phase 8 · 图库服务(手机端)。放在最下面一行分隔线之上: 它是"整块"而不是一行设置,
              所以自带 border-t 与自己的小标题(不动 App.tsx 的布局 —— 面板全部长在这里)。 */}
          {/* macOS: 图库服务(手机端)在 macOS 上没有验证过 —— 按用户要求整块隐藏 */}
          {isMac ? null : <ServerPanel open={open} />}

          {/* 版本信息 + 检查更新。
              只比对版本号: 本机版本(二进制里的) vs GitHub 最新正式版的 tag。
              不下载、不静默安装 —— 有新版时按钮变成"去发布页", 剩下的交给浏览器。
              按钮 h-6(24px) 是点击目标下限, -my-1 把多出来的 8px 从行高里扣掉,
              所以这一行仍然只有 16px 高。 */}
          <div className="border-t border-border pt-3">
            <div className="flex items-center justify-center gap-1.5 pt-2 text-xs">
              <span className="text-muted-foreground">{t("settings.version", { v: version })}</span>
              <span aria-hidden="true" className="text-muted-foreground/40">·</span>
              {/* role="status" 包着按钮: 字面一变(检查中 → 已是最新 / 有新版本),
                  读屏会把新状态念出来。可视文案就是可访问名, 不再另加 aria-label
                  (WCAG 2.5.3 名字里要含可见文本)。 */}
              <span role="status">
                <Button
                  type="button"
                  variant="ghost"
                  size="xs"
                  onClick={onUpdateClick}
                  disabled={updateBusy}
                  title={updateHint}
                  aria-busy={updateBusy || undefined}
                  className={`-my-1 text-muted-foreground hover:text-foreground ${
                    update.kind === "available" ? "text-foreground font-medium underline underline-offset-2" : ""
                  }`}
                >
                  {updateLabel}
                </Button>
              </span>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
