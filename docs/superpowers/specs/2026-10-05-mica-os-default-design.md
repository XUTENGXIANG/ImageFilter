# ImageFilter 毛玻璃默认值随系统版本自适应

> 日期：2026-10-05
> 状态：待用户确认
> 目标仓库：本仓库（ImageFilter / imagefilter）

## 1. 背景与目标

应用的「透明毛玻璃背景」开关（`transparentBg`）驱动的是 **Windows Mica**（`src-tauri/src/lib.rs:25` 的 `set_glass_bg` → `Effect::MicaDark/MicaLight` → `DwmSetWindowAttribute` 的 `DWMWA_SYSTEMBACKDROP_TYPE`）。**Mica 是 Windows 11（build ≥ 22000）才有的效果。**

但当前默认值写的是：

```ts
// src/App.tsx:498
const [transparentBg, setTransparentBg] = useState<boolean>(
  () => localStorage.getItem("imagefilter-glass") !== "0"
);
```

即**对所有人默认开**。在 Windows 10 上，窗口拿到的是一个无法应用 Mica 的状态：窗口是 `transparent: true`，根元素又没有不透明底色，标题栏还按玻璃配色渲染 —— 得到一个没有玻璃的「玻璃界面」。

**目标**：首次启动时检测系统是否支持 Mica。支持（Win11）→ 默认开；不支持（Win10）→ 默认关，并在设置里把开关置灰、说明原因。

## 2. 需求决策（已与用户确认）

| 项 | 决定 |
|----|------|
| 检测方式 | **方案 A**：Rust 读注册表构建号，`build >= 22000` 判定支持 Mica |
| 「首次启动」语义 | 仅在 localStorage **没有** `imagefilter-glass` 这个 key 时套用 OS 默认值 |
| Win10 上的设置界面 | 开关**默认关 + 置灰 + 说明原因** |
| 老用户（已装、localStorage 已存旧默认值） | **不纠正**，只对全新安装生效 |
| 非 Windows 平台 | **不在本次范围**，行为保持现状（见 §6） |

## 3. 检测机制

### 3.1 为什么不用「产品名」

实测本机（真值 Windows 11 25H2 / build 26200）：

| 方式 | 结果 | 可信 |
|---|---|---|
| 注册表 `ProductName` | `Windows 10 Pro for Workstations` | ❌ **假** —— Win11 出于兼容性不更新此值 |
| 注册表 `CurrentBuildNumber` | `26200` | ✅ |
| `.NET Environment.OSVersion` | `10.0.26200.0` | ✅ 但只有 build 段有效（Win10/11 都报 `10.0`） |
| 前端 `navigator.userAgent` | `Windows NT 10.0; Win64; x64` | ❌ Win10/Win11 都是这个 |
| 前端 UA-CH `platformVersion` | `19.0.0` | ✅（≥13 = Win11）但判据绑在 Chromium 的版本映射上 |

**结论：判据必须用构建号，不能用产品名。**

### 3.2 为什么不用「能力探测」

直觉上更好的做法是「试着应用 Mica，看是否报错」。**但走 Tauri 现成 API 做不到**：

```rust
// tauri-2.11.5/src/window/mod.rs:2076
pub fn set_effects<...>(&self, effects: E) -> crate::Result<()> {
    let effects = effects.into();
    let window = self.clone();
    self.run_on_main_thread(move || {
      let _ = crate::vibrancy::set_window_effects(&window, effects);   // ← 错误被丢弃
    })
}
```

`set_effects` **永远返回 `Ok`**；再往下的 `vibrancy/windows.rs:16 apply_effects()` 返回 `()`，`window_vibrancy::apply_mica(...)` 的结果同样被丢弃。也就是说 Win10 上前端那个 `.catch(() => {})`（`title-bar.tsx:86`）永远不会触发。

要做真实能力探测，只能自己调 `DwmSetWindowAttribute` 并回读 `DWMWA_SYSTEMBACKDROP_TYPE`。代价是：探测走我们的 Win32 代码、应用走 Tauri 的代码，两条路径可能不一致；且**本机是 Win11，Win10 分支完全无法实测**。

### 3.3 采用的判据

```
supportsMica = (platform == "windows") && (build >= 22000)
```

边界核对：Win10 22H2 = 19045 ✓排除；Windows Server 2022 = 20348 ✓排除（无 Mica）；Win11 21H2 = 22000 ✓纳入；本机 26200 ✓纳入。

判据抽成**纯函数**，使 Win10 分支虽无法真机运行、但逻辑可单测。

## 4. 架构与数据流

```
main.tsx  ──await invoke("get_os_capabilities")──▶  Rust: get_os_capabilities()
   │                                                     └─ 读注册表 CurrentBuildNumber
   │  { platform, windowsBuild, supportsMica }
   ▼
<App osCapabilities={...} />
   ├─ src/App.tsx:498  transparentBg 初值：
   │     stored 存在            → 用 stored（永远尊重用户存下来的值）
   │     stored 为 null 且是 Windows → 用 supportsMica
   │     stored 为 null 且非 Windows → true（保持今天的行为，见 §6）
   └─ SettingsDialog：platform === "windows" && !supportsMica 时置灰 + 换说明文案
```

> 注意「OS 默认值只对 Windows 生效」这一条：`supportsMica` 在 macOS/Linux 上恒为 `false`，
> 若直接拿它当默认值，会把 macOS 的默认从「开」改成「关」——那是本次范围外的行为变更。

**为什么在 `main.tsx` 里 await**：`useState` 的初始化函数是同步的，而 `invoke` 是异步的。若先用「支持」再异步纠正，Win10 全新安装会出现一帧「玻璃开着」的错误状态（闪烁）。本地 IPC 调用延迟在毫秒级，先 await 再 render 的代价可以接受。

**超时兜底**：`invoke` 失败或超时（500ms）时，返回
`{ platform: "unknown", windowsBuild: null, supportsMica: false }`。
因为 `platform !== "windows"`，`osDefaultGlass` 会返回 `true` 且不触发置灰 —— 即**完整保持今天的行为**，不引入新的启动阻塞，也不会在探测失败时误关玻璃。

**派生的 UI 标记**：`micaUnsupported = caps.platform === "windows" && !caps.supportsMica`。
往下传这一个布尔值即可（`TitleBar` → `SettingsDialog`），不必把整个能力对象穿过组件树。

## 5. 改动点

| 文件 | 改动 |
|---|---|
| `src-tauri/src/lib.rs` | 新增 `#[tauri::command] fn get_os_capabilities()`；新增纯函数 `fn mica_supported_by_build(build: u32) -> bool` + `#[cfg(test)]` 单测；注册到 `invoke_handler` |
| `src-tauri/Cargo.toml` | 无新依赖（`windows` crate 已含 `Win32_System_Registry` 特性） |
| `src/os-capability.ts`（新） | `loadOsCapabilities()`：调 invoke、带超时、缓存；导出类型 `OsCapabilities` = `{ platform: string; windowsBuild: number \| null; supportsMica: boolean }`；以及纯函数 `osDefaultGlass(caps, storedValue)` 供单测 |
| `src/main.tsx` | render 前 await 能力探测，把结果作为 prop 传给 `<App>` |
| `src/App.tsx` | `transparentBg` 初值改为 `osDefaultGlass(caps, localStorage.getItem("imagefilter-glass"))`；算出 `micaUnsupported` 并透传给 `SettingsDialog` |
| `src/components/settings-dialog.tsx` | 新增 `micaUnsupported` prop；为真时 `Toggle` 加 `disabled`、`SettingRow` 的 desc 换成不支持说明 |
| `src/components/title-bar.tsx` | 透传 `micaUnsupported` 给 `SettingsDialog`（它已经持有 `transparentBg` 与 `set_glass_bg`） |
| `src/i18n/zh.ts` / `en.ts` | 新增 `settings.transparentBgUnsupported` 文案（中英各一条） |
| `src/components/ui/toggle.tsx` | **无需改动** —— 已支持 `disabled` 与 `disabled:opacity-50` |

## 6. 非目标

- **不动** `glass-opacity` / `background-opacity`（它们只在 Mica 可用时才有意义，设置里已按 `dimmed` 降级）
- **不动**窗口的 `transparent: true`
- **不纠正**老用户已存的 `imagefilter-glass` 值
- **不处理** macOS / Linux：`get_os_capabilities` 在这两个平台返回 `supportsMica: false` 且 `platform` 非 `"windows"`，而置灰逻辑只对 `platform == "windows"` 生效 —— 即行为与今天完全一致。**注**：macOS 上大概率存在与 Win10 同样的问题（`set_glass_bg` 在其上是空操作），但那是一个独立的决定，不在本次范围。

## 7. 验证计划

1. **Rust 单测**（`mica_supported_by_build`）：19045 / 20348 / 21999 / 22000 / 22621 / 26200 六个输入，覆盖 Win10、Server 2022、边界值各一。
2. **前端纯函数单测**（`osDefaultGlass`）：stored=`"1"`/`"0"`/`null` × caps={Win10, Win11, macOS, 探测失败} 的组合，断言「stored 优先」「非 Windows 不变」「探测失败不变」。
3. **本机端到端（Win11）**：清空 localStorage → 启动 → 断言 `transparentBg === true`、设置里开关可点。
4. **模拟 Win10 的前端分支**：用 `.design-audit/harness.html` 的 Tauri IPC mock 让 `get_os_capabilities` 返回 `{ platform: "windows", windowsBuild: 19045, supportsMica: false }` → 断言初始化值为 false、设置里开关为 `disabled` 且文案已替换。截图存证。
5. **老用户路径**：预置 `localStorage["imagefilter-glass"] = "1"` + mock 返回不支持 → 断言仍为 `true`（不被覆盖）。
6. **控制台**：零 error / warning。

## 8. 已知限制

- **Win10 真机的端到端行为无法验证** —— 本机是 Win11。判据逻辑有单测覆盖，前端置灰分支有 mock 覆盖，但「Win10 上窗口实际长什么样」这一步没做过，交付时会明确标注。
- **版本 ≠ 能力**：Win11 上若用户在系统设置里关了「透明效果」，Mica 会渲染为纯色。此时判据仍判「支持」，界面会显示开关为开、但实际没有玻璃。这与今天的行为一致，属于「开了但没效果」，不是崩溃。
- `macOS` / `Linux` 的同类问题未处理（见 §6）。
