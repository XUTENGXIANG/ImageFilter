# 毛玻璃默认值随系统版本自适应 · 实测证据

日期：2026-10-05 · 规格：`docs/superpowers/specs/2026-10-05-mica-os-default-design.md`

## 1. 这件事在解决什么

「透明毛玻璃背景」开关驱动的是 **Windows Mica**（`src-tauri/src/lib.rs` 的 `set_glass_bg`
→ `Effect::MicaDark/MicaLight`）。**Mica 是 Windows 11（build ≥ 22000）才有的效果。**
而改动前这个开关对所有人默认开 —— 在 Win10 上，窗口是 `transparent: true`、根元素又没有不透明
底色，得到的是一个「没有玻璃的玻璃界面」。

## 2. 判据：必须用构建号

| 方式 | 本机实测 | 可信 |
|---|---|---|
| 注册表 `ProductName` | `Windows 10 Pro for Workstations` | ❌ 假 —— Win11 出于兼容性不更新此值（本机真值是 Win11 25H2 / 26200） |
| 注册表 `CurrentBuildNumber` | `26200` | ✅ |
| 前端 `navigator.userAgent` | `Windows NT 10.0` | ❌ Win10/Win11 都报这个 |

判据：`supportsMica = (platform == "windows") && (build >= 22000)`

边界：Win10 22H2 = 19045 排除 · Windows Server 2022 = 20348 排除 · Win11 21H2 = 22000 纳入。

**不用能力探测**：走 Tauri 现成 API 做不到 —— `set_effects` 永远返回 `Ok`（内部
`let _ = ...set_window_effects(...)` 把错误丢了），所以前端那个 `.catch(() => {})` 永远不会触发。
详见规格 §3.2。

## 3. 对规格的一处收紧（本次实现与规格文本不同，理由在此）

规格 §4 写的置灰条件是 `platform === "windows" && !supportsMica`。
**实现里多加了一个条件：`windowsBuild !== null`。**

理由：`windowsBuild` 为 null 的语义是「**探测失败**」，不是「不支持」。按规格原文，一次注册表
读取失败就会把 Win11 用户的玻璃**默认关掉，还把设置里的开关置灰** —— 用户看到的是一个被锁死
且关着的开关，而他的系统完全支持。加了这一条之后，探测失败一律退回「保持今天的行为」。

三处保持一致：
- `osDefaultGlass`：只有 `platform === "windows" && windowsBuild !== null` 时才拿 `supportsMica` 当默认值
- `micaUnsupported`：同样两个条件都满足才置灰
- 超时/异常兜底：`platform: "unknown"` → 两个条件都不满足 → 默认开、不置灰

## 4. 单测：`os-default-glass.test.ts`（17 条，全过）

跑法见文件头注释。覆盖 stored × 四种系统能力的组合：

| | stored=null | stored="1" | stored="0" |
|---|---|---|---|
| Win11 | 开 | 开 | 关 |
| Win10 | **关** | **开（老用户不被纠正）** | 关 |
| macOS | **开** | 开 | 关 |
| 探测失败 | **开** | — | 关 |
| Windows 但读不到构建号 | **开** | — | — |

加粗的五条是「差一点就写错」的那些：macOS 上 `supportsMica` 恒为 false，直接拿它当默认值
会把 macOS 的默认从开改成关；`windowsBuild` 为 null 那两条见 §3。

`micaUnsupported` 另测 5 条：Win10 置灰；Win11 / macOS / 探测失败 / 读不到构建号 都不置灰。

## 5. 端到端：`verify-os-default.mjs`（14 条，全过）

读的是设置面板里那个开关的**真实 DOM 状态**（`aria-pressed` / `disabled` / 说明文案），
不是 React 内部状态。

| 场景 | `aria-pressed` | `disabled` | 说明文案 |
|---|---|---|---|
| 本机 Win11 · 全新安装 | true | false | 用 Windows Mica 玻璃背景 |
| 模拟 Win10 · 全新安装 | **false** | **true** | **当前系统不支持（需要 Windows 11）** |
| 模拟 Win10 · 老用户已存 "1" | **true** | true | 当前系统不支持（需要 Windows 11） |
| 探测失败(`?osfail=1`) · 全新安装 | true | false | 用 Windows Mica 玻璃背景 |

四个场景控制台都是零 error / warning。截图（未入库）：`os-win11.png` / `os-win10.png`。

Win10 的模拟走桩：`.design-audit/_probe/mock-tree.js` 的 `get_os_capabilities` 认
`?win10=1`（19045 / 不支持）和 `?osfail=1`（返回 null，模拟读不到构建号）。

## 6. 已知限制（诚实列出）

1. **Win10 真机的端到端行为没有验证过。** 本机是 Win11。判据逻辑有 Rust 单测（含 22000 边界），
   前端分支有桩模拟覆盖，但「Win10 上窗口实际长什么样」这一步没做过 —— 交付时如实标注。
2. **版本 ≠ 能力。** Win11 上若用户在系统设置里关了「透明效果」，Mica 会渲染成纯色；此时判据
   仍判「支持」，界面显示开关为开但实际没有玻璃。这与改动前的行为一致，属于「开了但没效果」，
   不是崩溃。
3. **macOS / Linux 未处理。** `get_os_capabilities` 在这两个平台返回 `supportsMica: false`
   且 `platform` 非 `"windows"`，于是默认值与置灰逻辑都不变 —— 行为与今天完全一致。
   注：macOS 上大概率存在与 Win10 同样的问题（`set_glass_bg` 在其上是空操作），
   但那是一个独立的决定。
4. **`lightroom.rs` 的可见性被放大了一处。** `mod win` 与其中的 `reg_read_string` 从私有改成
   `pub(crate)`，好让 `lib.rs` 复用 —— 读注册表字符串是 Windows 通用管道，不值得为它再写第二份
   unsafe 的句柄/缓冲区处理。这是本次唯一的 Rust 侧重构（逻辑未动）。
5. **`lightroom.rs` 里那段 `mod win` 的归属**（一个 Lightroom 模块里放着通用注册表读取）没有搬。
   搬到独立模块更干净，但那要动一个已有验证的 Phase 7 文件，收益不抵风险，留给以后。

## 7. 跑法

```
先决条件: npm run tauri dev 在跑 (Vite 在 localhost:1420)
          .design-audit/harness.html 存在 —— Tauri IPC 的浏览器桩, 按设计不入库

浏览器端到端(4 个场景):
  node docs/evidence/scripts/verify-os-default.mjs

纯函数单测(不需要 dev server):
  npx esbuild docs/evidence/scripts/os-default-glass.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/os-default-glass.mjs
  node .design-audit/_probe/os-default-glass.mjs

Rust 侧判据单测(含 22000 边界):
  cargo test --manifest-path src-tauri/Cargo.toml --lib mica_boundary
```

三个的退出码都是 0 = 通过、1 = 失败。
