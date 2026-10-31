# mDNS 局域网发现与双设备确认配对 · 实施计划

> **给执行 agent：** REQUIRED SUB-SKILL：`superpowers:subagent-driven-development`。
> 每个任务只读「本合同 + 自己那一节 + 列出的文件」，不要通读全仓库。
> 任务里的 checkbox 用于执行时逐项勾选。

**Goal:** 把「手动输入 IP + 64 位配对码」从主路径降级为高级模式；默认在同一局域网内用 mDNS
发现电脑，手机点设备、电脑点「允许」完成配对。配对窗口 5 分钟。

**Architecture:** 桌面端在服务运行且网络为「专用」时广播 `_imagefilter._tcp.local.`；
安卓用系统 `NsdManager` 发现。手机发起 `POST /api/v1/pair/request`，桌面弹窗确认后，
手机轮询 `GET /api/v1/pair/status` 拿到 `{url, token}` 并复用现有 `PairingStore` 保存。
配对端点免 Bearer，但只在 5 分钟窗口内、限流、60 秒过期；token 不进 mDNS TXT。

**Tech Stack:** Rust（axum/tokio/`mdns-sd`/serde）、React 19 + TS + Tauri 2、
Kotlin + Compose + `NsdManager` + OkHttp + kotlinx.serialization。

## Global Constraints

- 仍然 **LAN-only、不做 TLS**；公用网络不启动服务的硬护栏保持不变。
- **token 绝不进 mDNS TXT、也绝不在批准前返回**。批准后只在局域网 HTTP 里返回（与今天手输/扫码同一边界）。
- 配对端点免 Bearer，但必须：5 分钟窗口内才可用、每设备 10 秒 1 次、最多 3 个待确认、请求 60 秒过期。
- 其余所有端点继续 fail-closed（无 token 一律 401）。`PROTECTED_PATHS` 不得减少。
- v1 **不做 per-device token**：「移除设备」只是忘记；真正吊销走「轮换配对码」（所有设备重连）。
- 桌面新增 Rust 依赖仅 `mdns-sd`；安卓不新增依赖（`NsdManager` 是系统 API）。
- 桌面文案走 `src/i18n/{zh,en}.ts`，中英必须同键；安卓文案直接中文（现有做法）。
- 现有 **491 个安卓 JVM 测试**、Rust `--lib` 与 `server_contract` 必须保持全绿。
- **Tauri 类型不得进入 `ServerState`**：沿用 `server::Notify` 的类型擦除 seam（见 `lib.rs::decisions_notifier`），
  否则测试 exe 会在加载期因 `comctl32.dll` 死掉。

---

## 0. 冻结的契约（三个 agent 都按这个写，不许自行改名）

### 0.1 mDNS

| 项 | 值 |
|---|---|
| 服务类型 | `_imagefilter._tcp.`（Android）/ `_imagefilter._tcp.local.`（mdns-sd） |
| 实例名 | `ImageFilter <hostname>` |
| host | `<hostname>.local.` |
| TXT | `v=1`、`id=<desktopId>`、`name=<hostname>`、`pair=open\|closed` |
| 广播条件 | 服务 `running == true` 且 `network::should_refuse_to_start` 为 `None` |
| 关闭 | `stop_server`、应用退出、网络类别变 Public（v1 只处理前两个） |

### 0.2 HTTP（桌面服务）

**`POST /api/v1/pair/request`**（免 Bearer）

```json
{"deviceId":"uuid","deviceName":"Pixel 8","appVersion":"1.2.1"}
```

| 情况 | 状态 | body |
|---|---|---|
| 接受 | 202 | `{"requestId":"uuid","expiresAt":1792900000000}` |
| 窗口没开 | 409 | `{"code":"pairing_closed"}` |
| 限流 | 429 | `{"code":"rate_limited"}` |
| 字段缺失/过长 | 400 | `{"code":"bad_request"}` |

**`GET /api/v1/pair/status?requestId=...`**（免 Bearer）

| 情况 | 状态 | body |
|---|---|---|
| 待确认 | 200 | `{"status":"pending"}` |
| 已允许 | 200 | `{"status":"approved","url":"http://192.168.1.20:8765","token":"<64hex>","desktopName":"DESKTOP-96B8FMF"}` |
| 已拒绝 | 200 | `{"status":"denied"}` |
| 已过期 | 200 | `{"status":"expired"}` |
| 未知 requestId | 404 | `{"code":"not_found"}` |

- 两个路径加入 `routes::PUBLIC_PATHS`；**不要**加入 `PROTECTED_PATHS`。
- 批准后返回的 `url`：优先用请求的 `Host` 头，但必须校验它等于本机某个 `NetHints.lan` 的
  `ip:port`；否则回退到第一个 LAN 地址。不要信任意 Host。
- 错误 body 沿用现有 `{code}` 形状；安卓对未知 code 走通用文案。

### 0.3 Tauri 命令 / 事件

```ts
type PairingWindow = { expiresAt: number };            // unix ms
type PendingPairing = { requestId: string; deviceId: string; deviceName: string; ip: string; requestedAt: number };
type PairedDevice   = { deviceId: string; name: string; lastSeenAt: number };
type PairingStatus  = { open: boolean; expiresAt?: number; pending: PendingPairing[]; paired: PairedDevice[] };
```

| 命令 | 返回 |
|---|---|
| `open_pairing_window()` | `PairingWindow`（now + 5min） |
| `close_pairing_window()` | `void` |
| `pairing_status()` | `PairingStatus` |
| `approve_pairing(requestId)` | `void` |
| `deny_pairing(requestId)` | `void` |
| `forget_paired_device(deviceId)` | `void` |

| 事件 | 负载 |
|---|---|
| `pairing-request` | `PendingPairing` |
| `pairing-changed` | `PairingStatus`（批准/拒绝/过期/移除后） |

### 0.4 安卓类型

```kotlin
data class DiscoveredDevice(
    val serviceName: String, val name: String, val host: String,
    val port: Int, val pairOpen: Boolean, val desktopId: String,
)

sealed interface DiscoveryEvent {
    data class Found(val device: DiscoveredDevice) : DiscoveryEvent
    data class Lost(val serviceName: String) : DiscoveryEvent
    data class Failed(val message: String) : DiscoveryEvent
}

interface DiscoverySource { fun start(onEvent: (DiscoveryEvent) -> Unit); fun stop() }

sealed interface ConnectState {
    data object Searching : ConnectState
    data class Devices(val devices: List<DiscoveredDevice>) : ConnectState
    data class Waiting(val device: DiscoveredDevice, val requestId: String) : ConnectState
    data class Approved(val pairing: Pairing) : ConnectState
    data class Denied(val message: String) : ConnectState
    data class Error(val message: String) : ConnectState
}

interface PairingApi {
    suspend fun request(device: DiscoveredDevice, deviceId: String, deviceName: String): PairRequestResult
    suspend fun status(device: DiscoveredDevice, requestId: String): PairStatusResult
}
```

`PairRequestResult`：`Ok(requestId, expiresAt)` / `Closed` / `RateLimited` / `Error(message)`。
`PairStatusResult`：`Pending` / `Approved(pairing)` / `Denied` / `Expired` / `Error(message)`。

---

## 1. 文件所有权（并行时不许越界）

| 工作流 | 拥有 | 不碰 |
|---|---|---|
| **A · Rust 后端** | `src-tauri/Cargo.toml`、`src-tauri/src/server/**`、`src-tauri/src/lib.rs`、`src-tauri/src/server/config.rs`、Rust 测试 | `src/**`、`android/**` |
| **B · 桌面 UI** | `src/components/**`、`src/lib/pairing.ts`、`src/App.tsx`、`src/i18n/**` | `src-tauri/**`、`android/**` |
| **C · 安卓数据层** | `android/.../data/DiscoverySource.kt`、`NsdDiscoverySource.kt`、`PairingApi.kt`、`DeviceIdentity.kt`、`ui/ConnectViewModel.kt`、对应 JVM 测试 | Compose 屏幕、`MainActivity` |
| **D · 安卓 UI** | `ui/ConnectScreen.kt`、`ui/ConnectCards.kt`、`ui/PairingScreen.kt`、`MainActivity.kt` | 数据层（用 C 的接口） |
| **E · 集成验证** | `docs/**`、最终截图 | 各工作流的实现文件 |

依赖：A、B、C **可并行**；D 等 C；E 等 A/B/C/D。C 与 D 都碰 `android/.../ui/`，必须串行。

---

## 2. 工作流 A · Rust 后端

### Task A1：配对状态机（纯逻辑 + 单测）

**Files:**
- Create: `src-tauri/src/server/pairing.rs`
- Test: 同文件 `#[cfg(test)] mod tests`

**Interfaces:**
- Produces: `PairingStore::{new, open_window, close_window, window_open, request, status, approve, deny, sweep}`；
  `PairRequest`、`PairStatusBody`、`PendingPairing`、`PairedDevice`。

- [ ] 写失败测试：`request_rejected_when_window_closed`、`request_is_rate_limited_per_device`、
      `pending_expires_after_60s`、`approve_returns_token_only_after_approval`、
      `unknown_request_is_none`、`window_expires_after_5_min`、`max_three_pending`。
- [ ] 运行 `cargo test --lib pairing::tests`，确认红。
- [ ] 实现 `PairingStore`：窗口 `Instant`、待确认 `HashMap<RequestId, Pending>`、
      `HashMap<DeviceId, Instant>` 限流、`Vec<PairedDevice>`；所有时间参数注入（便于测试）。
- [ ] 运行测试，确认绿。
- [ ] 提交：`feat(server): add in-memory pairing state machine`。

### Task A2：mDNS 广播

**Files:**
- Create: `src-tauri/src/server/mdns.rs`
- Modify: `src-tauri/Cargo.toml`（`mdns-sd = "0.13"`；以实际可用的最新稳定版为准）
- Test: 纯函数测试

**Interfaces:**
- Consumes: `network::detect_hints().lan`、`hostname`（用 `std::env` + 平台 API 或已有 helper）。
- Produces: `Advertiser::{start, set_pair_open, stop}`；`pair_txt(id, name, open) -> Vec<(String,String)>`。

- [ ] 写失败测试：`txt_has_no_token`、`txt_sets_pair_open_and_closed`、`txt_has_version_and_id`。
- [ ] 运行 `cargo test --lib mdns::tests`，确认红。
- [ ] 实现 `pair_txt` 与 `Advertiser`；`set_pair_open` 重新注册同实例（TXT 更新）。
- [ ] 运行测试，确认绿。
- [ ] 提交：`feat(server): advertise ImageFilter over mDNS`。

### Task A3：HTTP 端点 + 限流 + 鉴权边界

**Files:**
- Modify: `src-tauri/src/server/routes.rs`（`PUBLIC_PATHS`、两个 handler、`PROTECTED_PATHS` 测试）
- Modify: `src-tauri/src/server/mod.rs`（`ServerState` 增加 pairing/advertiser 字段；启动/停止时广播）
- Test: `src-tauri/tests/server_contract.rs`

**Interfaces:**
- Consumes: A1 的 `PairingStore`、A2 的 `Advertiser`。
- Produces: 0.2 的两个端点；`open_pairing_window` 等通过 Tauri 命令调用。

- [ ] 在 `server_contract.rs` 写失败测试：
      `pair_request_works_without_bearer_when_window_open`、
      `pair_request_returns_409_when_window_closed`、
      `pair_request_rate_limited_after_one_per_10s`、
      `pair_status_returns_pending_then_approved`、
      `pair_status_unknown_is_404`、
      `protected_paths_still_401_without_token`（逐条枚举 `PROTECTED_PATHS`）。
- [ ] 运行 `cargo test --test server_contract`，确认红。
- [ ] 在 `routes.rs` 加 `"/api/v1/pair/request"`、`"/api/v1/pair/status"` 到 `PUBLIC_PATHS`；
      实现 handler；`ServerState` 持 `Arc<StdMutex<PairingStore>>`，不加 Tauri 类型。
- [ ] 运行全部 Rust 测试，确认绿。
- [ ] 提交：`feat(server): add LAN pairing request/status endpoints`。

### Task A4：Tauri 命令、事件 seam、配置持久化

**Files:**
- Modify: `src-tauri/src/lib.rs`（命令注册、`pairing_notifier`、`open_pairing_window` 等）
- Modify: `src-tauri/src/server/config.rs`（`desktop_id`、`paired_devices`，serde default）
- Test: 纯函数/状态测试（不链接 Tauri）

**Interfaces:**
- Produces: 0.3 的命令与事件；`PairingNotify = Arc<dyn Fn(PairingEvent) + Send + Sync>`。

- [ ] 写测试：`desktop_id_is_stable_across_loads`、`paired_devices_round_trip`、
      `open_window_sets_five_minutes`。
- [ ] 实现：`desktop_id` 首次生成并落盘；`paired_devices` 持久化；命令只做参数校验与转发；
      `pairing_notifier(app)` 用 `app.emit`，**只在 `run()` 里引用**（复用 `decisions_notifier` 的模式）。
- [ ] 运行 `cargo test --lib` + `cargo test --test server_contract`。
- [ ] 提交：`feat(tauri): expose pairing window and device commands`。

### Task A5：启停时广播

- [ ] 写测试：`server_start_registers_mdns` / `server_stop_unregisters_mdns`（用可注入的
      `Advertiser` trait 或 seam，不要真起组播）。
- [ ] 在 `server::start` bind 成功后启动广播；`stop` 时停止。窗口开/关时更新 TXT。
- [ ] 运行 Rust 全部测试。
- [ ] 提交：`feat(server): tie mDNS advertisement to service lifecycle`。

**A 的验收：** `cargo test --lib` 与 `cargo test --test server_contract` 全绿；
`PROTECTED_PATHS` 仍逐条 401；mDNS TXT 无 token；`ServerState` 无 Tauri 类型。

---

## 3. 工作流 B · 桌面 UI

> 只接 0.3 的命令/事件；命令后端未完成时 `invoke` 仍是泛型，TS 不受影响。

### Task B1：类型与封装

**Files:**
- Create: `src/lib/pairing.ts`
- Test: 无测试框架；以 `tsc` 为准

- [ ] 定义 0.3 的 TS 类型与 `invoke` 封装：
      `openPairingWindow`、`closePairingWindow`、`pairingStatus`、`approvePairing`、`denyPairing`、
      `forgetPairedDevice`、`onPairingRequest`、`onPairingChanged`。
- [ ] `npx tsc --noEmit` 通过。
- [ ] 提交：`feat(ui): add pairing API types and wrappers`。

### Task B2：全局配对确认弹窗

**Files:**
- Create: `src/components/pairing-dialog.tsx`
- Modify: `src/App.tsx` 或 `src/components/title-bar.tsx`（挂全局监听）
- Modify: `src/i18n/zh.ts`、`src/i18n/en.ts`

- [ ] 弹窗显示 `deviceName`、`ip`、请求时间；「允许」「拒绝」；请求过期/事件 `pairing-changed` 时关闭。
- [ ] 在应用根挂 `onPairingRequest`；多个请求按队列显示（最多 3 个）。
- [ ] 新增 i18n 键：`pairing.requestTitle`、`pairing.allow`、`pairing.deny`、`pairing.expired`、
      `pairing.deviceOn`、`pairing.advanced`、`pairing.openWindow`、`pairing.windowOpen`、
      `pairing.windowClosed`、`pairing.pairedDevices`、`pairing.forget`、`pairing.noDevices`。
- [ ] `npx tsc --noEmit && npm run build` 通过。
- [ ] 提交：`feat(ui): add desktop pairing confirmation dialog`。

### Task B3：服务面板改造

**Files:**
- Modify: `src/components/settings-dialog.tsx`（仅图库服务面板区域）
- Modify: `src/i18n/{zh,en}.ts`

- [ ] 新增「配对新设备」按钮 + 5 分钟倒计时 +「关闭配对窗口」。
- [ ] 新增「待确认」列表（可允许/拒绝）与「已连接设备」列表（可移除）。
- [ ] 现有二维码、token、地址、轮换按钮整体收进「高级 / 手动配对」折叠区；行为不变。
- [ ] 所有长说明保持现有诚实文案；只做层级折叠，不删信息。
- [ ] `npx tsc --noEmit && npm run build` 通过；中英键数一致。
- [ ] 提交：`feat(ui): simplify gallery service panel around pairing window`。

**B 的验收：** `tsc`/`build` 绿；手动打开设置能看到折叠后的高级区；弹窗能被 `pairing-request` 触发
（可用临时 `emit` 或后端联调验证）。

---

## 4. 工作流 C · 安卓数据层

### Task C1：设备身份

**Files:**
- Create: `android/app/src/main/kotlin/com/imagefilter/mobile/data/DeviceIdentity.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/DeviceIdentityTest.kt`

- [ ] 写测试：首次读生成 32 位 hex 并持久化；再次读稳定；文件坏时**重新生成**并返回，不崩。
- [ ] 用 DataStore 实现 `deviceId`；`deviceName` 优先 `Settings.Global.DEVICE_NAME`，回退 `Build.MODEL`，
      再回退 `"Android"`。
- [ ] `./gradlew :app:testDebugUnitTest --tests '*DeviceIdentityTest'` 绿。
- [ ] 提交：`feat(android): add stable device identity`。

### Task C2：`NsdManager` 发现适配层

**Files:**
- Create: `data/DiscoverySource.kt`、`data/NsdDiscoverySource.kt`
- Modify: `AndroidManifest.xml`（必要时加 `NEARBY_WIFI_DEVICES`，带 `neverForLocation`）

- [ ] `NsdDiscoverySource(context)`：`discoverServices("_imagefilter._tcp.", PROTOCOL_DNS_SD, listener)`；
      `onServiceFound` → `resolveService`；`onServiceLost` → `Lost`；`onResolveFailed` → `Failed`。
- [ ] 从 `NsdServiceInfo.attributes` 读 `id`、`name`、`pair`；`host` 取 `host.hostAddress`；`port` 取 `port`。
- [ ] 回调切到主线程/注入的 scope；`stop()` 要 `unregisterService` 且置空 listener。
- [ ] 无 JVM 单测（框架 API）；用 `assembleDebug` 确认编译。
- [ ] 提交：`feat(android): add NsdManager discovery source`。

### Task C3：`PairingApi`

**Files:**
- Create: `data/PairingApi.kt`
- Test: `test/.../PairingApiTest.kt`（MockWebServer）

- [ ] 写失败测试：202 → `Ok`；409 → `Closed`；429 → `RateLimited`；200 pending/approved/denied/expired；
      404 → `Error`；网络失败 → `Error`。
- [ ] 用 OkHttp + kotlinx.serialization；请求/状态各 5s 超时；不带 Authorization；
      `approved` 的 body 解析成现有 `Pairing`（`url/token/note=desktopName`）。
- [ ] 测试绿。
- [ ] 提交：`feat(android): add pairing HTTP client`。

### Task C4：`ConnectViewModel` 状态机

**Files:**
- Create: `ui/ConnectViewModel.kt`
- Test: `test/.../ConnectViewModelTest.kt`

- [ ] 写失败测试：搜索 → Found/Lost 合并；select → Waiting；approved → `PairingStore.save` + `Approved`；
      denied/expired/error；cancel；60 秒超时；重复 Found 去重。
- [ ] 用 `FakeDiscoverySource` + `FakePairingApi` + fake store；轮询间隔注入（测试用 1ms）。
- [ ] 实现状态机；`onCleared()` 必须 `source.stop()`；轮询用 `delay` 可取消。
- [ ] 测试绿；现有 491 个测试不动。
- [ ] 提交：`feat(android): add pairing discovery state machine`。

**C 的验收：** 新增测试全绿；`assembleDebug` 通过；不碰 Compose 屏幕。

---

## 5. 工作流 D · 安卓 UI（C 完成后）

### Task D1：拆分现有配对页

**Files:**
- Modify: `ui/PairingScreen.kt` → 保留实现，重命名入口为 `ManualPairingScreen`
- Modify: `MainActivity.kt`

- [ ] `MainActivity` 未配对时渲染 `ConnectScreen`；`ConnectScreen` 内部切「发现」/「高级」两种模式。
- [ ] 高级模式展开后就是现有 `ManualPairingScreen` 的完整表单与扫码；成功后走同一个 `onPaired`。
- [ ] `assembleDebug` 通过；现有行为不变。

### Task D2：`ConnectScreen` 与卡片

**Files:**
- Create: `ui/ConnectScreen.kt`、`ui/ConnectCards.kt`
- Modify: `ui/PairingScreen.kt`（只改入口名与高级模式包装）

- [ ] 默认态：标题「连接电脑」+ 搜索动画 + 设备行（名称 / `可配对` / `请先在电脑上点配对新设备`）。
- [ ] 点击设备 → Waiting：「等待电脑确认…」+ 取消；approved → 进入主界面。
- [ ] 空态：一行「没有找到同一 Wi-Fi 下的电脑」+「重新搜索」+「高级模式」。
- [ ] 高级模式：底部一行「找不到电脑？高级模式」，展开现有表单。
- [ ] 所有按钮 10dp 圆角；次要按钮中性灰；主操作 emerald；48dp 触达。
- [ ] `assembleDebug` + 全量 `testDebugUnitTest` 通过。
- [ ] 提交：`feat(android): add LAN discovery connect screen with advanced fallback`。

### Task D3：模拟器可验的部分

- [ ] 用 `FakeDiscoverySource`（仅 debug/测试）或在 ViewModel 注入假设备，截三张：
      搜索中、设备列表、等待确认。
- [ ] 真机 mDNS 不在本轮验收（模拟器过不了 NAT）；写进「未验证」。
- [ ] 提交截图到 `docs/evidence/mdns-pairing/`。

**D 的验收：** 编译 + 全量测试；模拟器截图；高级模式仍能手动配对。

---

## 6. 工作流 E · 集成验证（我执行）

- [ ] `cargo test --lib`、`cargo test --test server_contract`
- [ ] `npx tsc --noEmit && npm run build`
- [ ] `./gradlew :app:testDebugUnitTest`（491+）、`:app:assembleDebug`
- [ ] 模拟器：高级模式配对 → 目录/网格；假发现的搜索/等待页截图
- [ ] 更新 `docs/ImageFilter-项目日志.md`：新增功能、协议、未验证项
- [ ] 记录未验证：真机 mDNS、Windows 防火墙 UDP 5353、AP 隔离、Android 13/16 权限、
      「移除设备不吊销 token」

---

## 7. Agent 派发（省 token 的关键）

**通用规则：** 每个 agent 只读「本计划 §0 + 自己那一节 + 列出的文件」；不改别的目录；
不跑全量构建；完成后报告：改了哪些文件、跑了哪些命令、结果、未决问题。

### A（Rust 后端）

> 读 `docs/superpowers/plans/2026-10-25-imagefilter-mdns-pairing.md` 的 §0、§2。
> 先读 `src-tauri/src/server/{mod,routes,network,config}.rs`、`src-tauri/src/lib.rs`、`Cargo.toml`、
> `src-tauri/tests/server_contract.rs`。实现 Task A1–A5。只碰 `src-tauri/**`。
> 必须沿用 `Notify` 的类型擦除 seam；`ServerState` 不得出现 Tauri 类型。
> 跑 `cargo test --lib` 与 `cargo test --test server_contract`。报告结果。

### B（桌面 UI）

> 读本计划 §0.3、§3。先读 `src/components/settings-dialog.tsx` 的图库服务面板、
> `src/App.tsx`、`src/i18n/{zh,en}.ts`。实现 Task B1–B3。只碰 `src/**`。
> 跑 `npx tsc --noEmit && npm run build`。报告结果。

### C（安卓数据层）

> 读本计划 §0.4、§4。先读 `data/ApiClient.kt`、`data/PairingStore.kt`、`data/Dto.kt`、
> `ui/BrowseViewModel.kt` 与现有测试风格。实现 Task C1–C4。只碰 `data/**`、`ui/ConnectViewModel.kt`、测试。
> 跑 `./gradlew :app:testDebugUnitTest --tests '*Discovery*' --tests '*PairingApi*' --tests '*ConnectViewModel*'`
> 与 `:app:assembleDebug`。报告结果。

### D（安卓 UI，等 C）

> 读本计划 §5 + C 产出的 `ConnectViewModel`/`ConnectState`。先读 `ui/PairingScreen.kt`、
> `MainActivity.kt`、`ui/Insets.kt`。实现 Task D1–D3。只碰 `ui/**`、`MainActivity.kt`。
> 跑 `./gradlew :app:assembleDebug :app:testDebugUnitTest`。报告结果。

---

## 8. 风险与未验证（诚实清单）

1. **模拟器发现不到宿主机**：mDNS 组播过不了模拟器 NAT。真机 + 同一 WiFi 是唯一可信验收。
2. **Windows 防火墙**：UDP 5353 入站可能被拦；服务面板要给出提示。
3. **Android 权限**：Android 13+ 的 `NEARBY_WIFI_DEVICES`、Android 16 的本地网络权限要实机确认。
4. **免鉴权端点**：新增 `PUBLIC_PATHS` 是本项目历史上出过事故的地方（§4.6）；必须逐条测试。
5. **token 撤销**：v1 移除设备不吊销 token，只能轮换；文档必须写清。
6. **网络切换**：v1 只在服务启停时更新广播；切换 Wi-Fi 后可能短暂发现不到，重开服务恢复。
7. **Host 头**：配对返回的 URL 必须校验是本机 LAN 地址，防 Host 注入。
8. **桌面应用必须开着**：服务嵌入 Tauri，应用关闭即无广播；这是产品事实，不是 bug。
