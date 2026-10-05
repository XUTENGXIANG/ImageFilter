# ImageFilter · Phase 7 与 Lightroom Classic 衔接（模式 2）

> 这份是 Phase 7 的记录与手测清单。总方案的 Phase 1–6 见 [ImageFilter-功能实施方案.md](ImageFilter-功能实施方案.md)（那份已按会话 ⑤ 收尾，本文不重复其内容）。
>
> 基线：`e164de6` → 本 Phase 提交 `248545a` · 版本 `1.0.1`
> 静态验收：`cargo test --lib` **63 passed / 0 failed / 3 ignored** · `npx tsc --noEmit` exit 0 · `vite build` exit 0 · i18n zh/en 各 **263** 叶子 key 全对齐 · `src/lightroom.ts` 纯逻辑 **15/15** 断言通过

---

## 0. 这个功能到底做什么（一句话）

导入栏多一个「发送到 LrC」按钮：点一下 → **打开 Lightroom Classic 的导入对话框，源定位到"选中照片所在的那个文件夹"** → 用户在 LrC 里按「完成」即完成导入。

**它不做的**：不在 LrC 里做任何操作、不发按键、不驱动 UI、不等结果、不代替用户点「完成」。原因见 §2。

---

## 1. 实机探路：三个反直觉的结论（决定了全部实现）

在做任何设计之前先实测了这台机器上的 LrC。结论与"想当然"差距很大，所以这里是**证据**而不是推测（原始证据在 `_probe/`，见 §6）。

### 1.1 ✅ `Lightroom.exe "<文件夹>"` 会打开导入对话框

用一个含单张 120 KB 图片的临时目录冷启动 LrC，UIA 在窗口里抓到：

```
导入 / 导入行为 / 添加「将照片添加到目录而不移动」/ 源 / 包含子文件夹
新照片 / 文件处理 / 导入预设: 无 / 完成 / 取消
1 张照片 / 120 KB          ← 正是那张测试图的大小
```

→ **模式 2 成立**，这是本 Phase 的地基。

### 1.2 ⚠️ 主窗口标题永远是「图库」，导入不改标题

导入是**模态对话框**，主窗口标题始终是 `Lightroom Catalog - Adobe Photoshop Lightroom Classic - 图库 - `。

→ **任何"靠窗口标题判断导入是否打开"的写法都是死路。** 所以本模块**不做任何窗口/UI 探测**，只做"探测安装位置 + 启动进程"。要在运行时判断导对话框状态，只能靠 UIA 找 `完成` + `取消` + `张照片 /` 这组元素——那是测试手段，不该进产品。

### 1.3 ⚠️ 安装路径不能只扫 Program Files

本机实测：

| 探测方式 | 结果 |
|---|---|
| `Program Files\Adobe\*Lightroom*` | ❌ 落空 |
| `App Paths\Lightroom.exe` | ❌ 落空 |
| `HKLM/HKCU\SOFTWARE\Adobe\Lightroom` 的版本子键 | ❌ **不存在**（只有 `language` / `Locale` 两个值） |
| 卸载项 `InstallLocation` | ✅ `A:\lrc`（但这只是安装根，exe 在下一层） |
| **`.lrcat` 文件关联** | ✅ **唯一直接命中**：`A:\lrc\Adobe Lightroom Classic\Lightroom.exe "%1"` |

→ 所以探测顺序把**文件关联放第一位**，而不是当兜底。按版本号拼路径这条路也被证否。
→ 顺带确认 LrC 版本 **15.2.1**，界面语言中文（`language=zh_cn`），目录库在 `C:\Users\11\Pictures\Lightroom\Lightroom Catalog.lrcat`。

### 1.4 模式 1 的前提没满足：这台机器从未配过"自动导入"

LrC 的偏好文件 `%APPDATA%\Adobe\Lightroom\Preferences\Lightroom Classic CC 7 Preferences.agprefs`（42 KB）**不是二进制，是纯文本**（首 4 字节 `pref`，前 2 KB 无 NUL，可按 UTF-8 读），里面搜 `AutoImport` / `WatchFolder` / `autoImport` → **0 命中**。

→ 模式 1（送进 LrC 的自动导入监听文件夹）在这台机器上**无法使用**，详见 §3。

---

## 2. 设计决定

| # | 决定 | 理由 |
|---|---|---|
| 1 | 只做"打开导入对话框"，**不驱动 LrC** | 实测（§1.1）已足够；驱动 UI 需要 UI 自动化，脆弱且每次 LrC 更新都可能失效 |
| 2 | Rust 只做两件事：探测安装 + `CreateProcess` | 与 `xmp.rs` 同款：职责单一、可单测、无副作用 |
| 3 | 探测顺序：文件关联 → 卸载项(+ 再探一层) → App Paths → Program Files | §1.3 的实测排序；本机只有第 1 条命中 |
| 4 | `is_running` 用 ToolHelp 按 exe 名判断，**不用窗口标题** | §1.2 |
| 5 | **只发一个文件夹**，跨文件夹时取"包含最多选中照片"的那个 | 实测只验证过 `exe "<单个文件夹>"`；多路径行为无证据 |
| 6 | 但**必须如实告知漏掉了别的文件夹** | 静默只发一部分 = 用户以为全发了，这是最坏的一种"看起来正确" |
| 7 | 错误码走闭集 + 前端白名单，文案在 i18n | `xmp.ts` / `xmp.rs` 的既有契约 |
| 8 | 提示复用 `xmpNotice` 的"ref 请求位 + 渲染期搬运"写法 | 不在渲染期 setState（StrictMode 会跑两次） |
| 9 | 失败走一次性 toast(4s)，**成功走导入栏常驻行** | 成功要同时说清"发的哪个文件夹"+"漏了哪些"，一句话 toast 装不下 |
| 10 | LrC 未探测到时**整块隐藏**按钮 | 不给一个点了只会报错的入口 |
| 11 | 设置新增一行：打开导入 / 静默导入 / 重新检测；**"静默导入"禁用** | 见 §3 |
| 12 | 为守住"设置一屏放下"，行距 3→2 + 该行 `py-1.5`→`py-1` | 会话 ③ 第 28 项 / 会话 ④ 第 20 项把"两对话框不出现滚动条"升格为前置约束 9 |

### 被否决的方案（重要，别重复提议）

| 方案 | 否决原因 |
|---|---|
| 靠**窗口标题**判断导入对话框是否打开 | §1.2 实测证否：标题永远是"图库" |
| 用 **UI 自动化**(SendKeys / UIA)去点「完成」实现真·静默导入 | 脆弱：LrC 每次更新、语言切换、窗口位置变化都可能失效；且会与用户自己的操作抢焦点。产品里不做，只作为测试手段 |
| **按版本号子键**拼安装路径 | §1.3：注册表里根本没有版本子键 |
| 只扫 Program Files 就当找到/找不到 | §1.3：本机装在 `A:\lrc`，只扫标准目录会 100% 漏掉 |
| 探测"导入对话框是否已打开"并据此禁用按钮 | 无可靠方法（同 §1.2）；且 LrC 会自行复用已有实例，重复点击无害 |
| 一次把**多个文件夹**都传给 LrC | 无实测证据；且会让 LrC 弹出源选择而不是直接可用 |
| 跨文件夹时**静默只发一个** | 见决定 6 |
| 把"静默导入"做成**能点但报错** | 骗人。要么能跑，要么明确禁用并写清原因 |
| 为模式 1 顺手去**解析 `.agprefs`** 找监听文件夹键名 | 该键在本机不存在（从未配过），无从验证；且解析别人的私有偏好格式风险高。将来要做得先让用户手动配一次再观察键名 |
| 引入 `winreg` crate 读注册表 | 已有 `windows` crate（同文件里 `drives.rs`/`win_wic.rs` 就是裸 FFI 路线），加 `Win32_System_Registry` feature 即可，不新增第三方依赖 |
| 用 `reg.exe query` 走 `std::process` | 要解析文本输出、依赖命令行工具、还有编码坑；直接调 API 更稳 |
| 启动 LrC 后**等待/校验**导入结果 | 没有可靠信号（§1.2），只能骗自己；要验证得读 `.lrcat`（见 §5 的"将来可做"） |

---

## 3. 为什么"静默导入"这一档是禁用的

需求原本是"两种模式在设置里切换"。**模式 2 已实现且实测可用，模式 1 无法实现**，因为：

1. LrC 的**插件 SDK 不暴露**"把照片加入目录"这个动作。这是查证过的（社区里做 LrC 自动化的两个最完整项目——`lightroom-cli` 的模块列表是 `system/catalog/develop/selection/preview/plugin`，唯独没有 import；`lightroom-py` 的 README 也把一堆能力列进"Adobe SDK 做不到"），两份独立实现互相印证。
2. 唯一官方可行的路是 LrC 自带的**自动导入**：监听一个固定文件夹，文件落进去就自动导入。但它**需要用户先在 LrC 里配好**（File → 自动导入），而本机实测**从未配过**（§1.4），且监听文件夹与"移动到哪/套什么预设"是 LrC 的一整套偏好，**不能每次导入换目标**。

所以现在的处理是：档位按钮渲染出来但**禁用**，`title` 写明原因；同时 `useScanner` 里再挡一次（档位可能来自旧版本写下的 localStorage），返回 `notImplemented` 错误码。设置页与 hook 共用 `src/lightroom.ts::isLrcModeUsable` 一处判断，避免两边各写一份"哪个能用"。

**要做模式 1，缺的不是代码，是前提**：
1. 先在 LrC 里手动配一次自动导入，确定监听文件夹；
2. 决定冲突策略——`A:\lrc` 与照片归档同在 A 盘，若监听文件夹设在归档附近会混在一起；
3. 想清楚"LrC 会移动/复制"与"本项目的绝不覆盖 + 双端 MD5"两套契约如何共存（建议：先按模板导到归档目录，再把文件送进监听文件夹）。

---

## 4. 落地位置（改动清单）

### Rust

| 文件 | 改动 |
|---|---|
| `src-tauri/src/lightroom.rs` | **新增**。文件头写明 §1 的三条实测结论与两条不变式；`LrcError` 闭集错误码；`expand_env_vars` / `unquote` / `exe_from_command_line` 三个纯函数 + 5 个单测；`find_exe` 五级探测；`is_running`(ToolHelp)；两条命令 `probe_lightroom` / `send_to_lightroom` |
| `src-tauri/src/lib.rs` | 注册 `mod lightroom` + 两条命令 |
| `src-tauri/Cargo.toml` | windows feature 增 `Win32_System_Registry`、`Win32_System_Diagnostics_ToolHelp`（注释写明各自用途） |

### 前端

| 文件 | 改动 |
|---|---|
| `src/lightroom.ts` | **新增**。纯逻辑（零 React/零 Tauri）：`LRC_ERR_CODES` 闭集、`lrcErrKey` 白名单、模式读写（key `imagefilter-lrc-mode`，缺省 `dialog`）、`isLrcModeUsable`、`pickFolderForLightroom` |
| `src/types.ts` | 新增 `LightroomProbe` |
| `src/useScanner.ts` | LrC 状态块：probe（启动一次、不轮询）、`sendToLightroom`、notice 请求位、导出 8 个成员 |
| `src/App.tsx` | `lrcNoticeText` + toast effect + 两处 prop 透传 |
| `src/components/import-bar.tsx` | 「发送到 LrC」按钮（按 `found` 显隐）+ 成功常驻行（含"另有 N 个文件夹未发送"） |
| `src/components/settings-dialog.tsx` | 第 10 行：两档 + 重新检测；行距与内边距同步收紧 |
| `src/components/title-bar.tsx` | 4 个 prop 透传 |
| `src/i18n/{zh,en}.ts` | 各 +22 key（`lrc.*` + `settings.lrc*`）→ 263 叶子 key 对齐 |

**窗口标题/类名的实测记录**（测试脚本可能用到）：主窗口类名 `AgWinMainFrame`；标题 `Lightroom Catalog - Adobe Photoshop Lightroom Classic - 图库 - `；导入对话框内的稳定元素组合是 `完成` + `取消` + `* 张照片 / *`，可据此判断对话框是否打开。

---

## 5. 已知限制与未做的事

| # | 限制 | 说明 |
|---|---|---|
| 1 | **一次只能发一个文件夹** | 实测只验证过单文件夹形式。跨文件夹时发"包含最多选中照片"的那个，并提示漏了几个 |
| 2 | **未在运行时会怎样，没测** | 只实测了冷启动（LrC 未运行 → 传文件夹 → 开导入对话框）。LrC 已在运行时再传参数的行为**无证据**（预期是复用实例并弹导入，但没验过） |
| 3 | GUI 内点按钮的完整链路**未实机验证** | 需要起 `tauri dev` 并允许启动 LrC；本 Phase 只做到"纯逻辑断言 + Rust 单测 + 静态验收" |
| 4 | 探不到 LrC 时没有"手动指定路径"的兜底 | 五级探测在本机命中，但非标准安装的机器可能全落空。可考虑在设置里加一个手填 exe 路径（未做） |
| 5 | 不做 macOS | 非 Windows 返回 `notSupported`（macOS 用 `open -a`，未验证故不写） |
| 6 | 不校验"LrC 是否真的导入了" | 没有可靠信号（§1.2） |
| 7 | 不驱动 LrC、不代点「完成」 | 见 §2 决定 1 与其否决项 |

### 将来可做（有证据支持，本轮不做）

- **用 `.lrcat` 验证导入结果**：目录库是 SQLite（`Adobe_images.rating` / `Adobe_images.pick` / `AgLibraryFile.md5`，格式文档在 [`lrcat-extractor` 的 `lrcat_format.md`](https://docs.rs/crate/lrcat-extractor/0.4.0/source/doc/lrcat_format.md)）。Rust 侧已有 `sqlx`+sqlite，**只读**连库就能确认"星级有没有被 LrC 读到"。
  ⚠️ **只读，绝不写**：写进去是直接改 Adobe 的私有库，会让 LrC 的内存状态与库不一致。这条要作为红线写进注释。
  ⚠️ LrC 运行时 `.lrcat` 可能被锁；crate 自称支持目录格式 v2/v4/v6，现代 LrC 的 `Adobe_DBVersion` 是另一套编号，**必须在本机实测**。
- **模式 1**：前提见 §3。
- **手动指定 Lightroom.exe 路径**：见限制 4。

---

## 6. 证据文件（`_probe/`，未入库）

| 文件 | 内容 |
|---|---|
| `lrc-reg.txt` | 注册表：`.lrcat` 文件关联、Adobe 键、卸载项（**最关键的一份**） |
| `lrc-reg2.txt` | 确认没有版本子键 |
| `lrc-user.txt` | 用户目录、`.agprefs` 位置、`.lrcat` 位置 |
| `lrc-prefs.txt` | `.agprefs` 的文本性验证 + Auto Import 键 0 命中 |
| `lrc-uia.txt` | **导入对话框元素树（§1.1 的证据）** |
| `lrc-sel.txt` / `lrc-import-dialog.txt` / `lrc-filearg-uia.txt` | 选中项探测与窗口类名 `AgWinMainFrame` |
| `lrc-after-launch.png` | 启动后截图（未做视觉判读，当时的模型不支持读图） |
| `lrc-catalog-check.txt` | 确认目录库里**没有**混入任何探针文件 |
| `lrc-teardown.txt` | 收尾记录：LrC 已关、测试目录已删 |
| `probe-lrc.ps1` | 探测脚本本体（可复用；注：Windows PowerShell 5.1 下用 `powershell -File`，没有 `pwsh`） |
| `lrc-logic.test.ts` / `lrc-logic.mjs` | `src/lightroom.ts` 的 15 条纯逻辑断言 |
| `i18n-parity.cjs` | zh/en 叶子 key 对齐检查 |

> `_probe/` 里另有 Phase 5 的 XMP 证据（`EVIDENCE.md`、`*.xmp`、界面截图），是上一轮留下的。

---

## 7. Phase 7 手测清单（GUI，未做）

前置：`npx tauri dev`（Rust 改动会自动重编译并重启窗口）。

### A. 探测与显示
1. 启动后打开设置 → 「Lightroom 衔接」这一行显示 `A:\lrc\Adobe Lightroom Classic\Lightroom.exe · 未运行`（若 LrC 开着则显示"运行中"）。
2. 该行「静默导入」按钮**是灰的、点不动**，悬停能看到原因文案。
3. 点「重新检测」→ 文案不报错、结果不变。
4. 确认设置对话框**仍不用滚动**就能看到底部版本号（这是前置约束 9）。

### B. 未勾选 / 无文件夹
5. 一张都没勾、且已浏览某个文件夹 → 按钮可点，点击后提示"已交给 Lightroom：<当前文件夹>（当前文件夹）"。
6. 一张都没勾、也没浏览任何文件夹 → 按钮**置灰**。

### C. 正常发送（核心）
7. 在某个文件夹勾 3 张 → 点「发送到 LrC」→ 按钮短暂显示"启动中…" → LrC 启动并**打开导入对话框**。
8. 目视确认导入对话框的"源"面板落在该文件夹（或至少其所在卷）；按「取消」，**不要点「完成」**。
9. ImageFilter 里出现常驻行"已交给 Lightroom：<目录>（选中 3 张）"。
10. LrC 已在运行时再点一次 → 观察是否复用实例并再次弹出导入对话框（**限制 2**，本轮没测过，这里正好补上，把结果记进本文档）。

### D. 跨文件夹（不变式 2）
11. 勾选**跨两个文件夹**的照片（例如 A 目录 2 张 + B 目录 1 张）→ 点发送 → 应发出**A 目录**（张数多的那个），并额外显示"另有 1 个文件夹里的选中照片未发送"。
12. 反向再试（B 目录 2 张 + A 目录 1 张）→ 这次应发出 B 目录。确认它选的是"张数多的"而不是"先勾的"。

### E. 失败路径
13. 把 `A:\lrc\Adobe Lightroom Classic\Lightroom.exe` 临时改名 → 点发送 → 出现 toast"发送到 Lightroom 失败：未找到 Lightroom，请在设置里点"重新检测""。**验完立刻改回来。**
14. LrC 若被卸载/未安装 → 导入栏**不出现**该按钮（需先清掉探测缓存或重启应用）。

### F. 回归（这几条历史坑必须不破）
15. 查看器内按 `1`–`5` 后，网格里"被选中但没在看"的那张星级**不能**被改动。
16. `Ctrl+Z` 撤销仍正常，且**不会**触发发送。
17. 导入（写真归档）功能照旧：成功/改名/跳过/失败四行明细与导入历史都还能用。
18. 切到英文 → 「Send to LrC」等文案**不能**露出中文或 key。

---

## 8. 决策日志 · 会话 ⑥（Phase 7）

- **代码状态**：HEAD `248545a` · 工作区干净（仅 `_probe/` 与不属于本项目的 `task-7-review-package.decoded.txt` 未跟踪）· 基线 `e164de6`
- **已定决定**：见 §2 的 12 条
- **被否决方案**：见 §2 表格（11 条）
- **验收结果**：静态全绿（63 Rust 测试 / tsc / vite build / i18n 263 对齐 / 15 条纯逻辑断言）；**§7 的 18 项 GUI 手测全部未做**
- **遗留 / 本次不做**：模式 1（§3）、用 `.lrcat` 验证导入结果（§5）、手动指定 exe 路径（§5 限制 4）、macOS（返回 notSupported）、运行中实例的行为未测（§5 限制 2）

---

## 9. 会话 ⑥ 追加：XMP 缺省档位由「关闭」改为「询问」

用户要求：`src/xmp.ts::DEFAULT_XMP_MODE = "ask"`（原为 `"off"`）。

**这不是一个小改动，它推翻了一条既有决定** —— 原文（本仓 [实施方案](ImageFilter-功能实施方案.md) 会话 ⓪ 决策日志）写的是"Phase 5（XMP）**默认关闭**……保持 v1.0.x 的零副作用行为"。两处已加"已失效"标注，避免后续会话据此回退。

**语义变化要说清**：

| | 改之前（默认 `off`） | 改之后（默认 `ask`） |
|---|---|---|
| 刚装好、没动过设置 | 永不写盘，**也永不询问** | 首次将要写盘时**问一次** |
| 用户点"只写本机" | — | 落成 `off`，此后与旧行为完全一致 |
| 用户点"写入 .xmp" | — | 落成 `on`，此后自动写 |
| 没作答（Esc/关闭） | — | 仍是 `ask` 但**本会话不再问**；本地评分照常 |

**所以保守的不再是"默认不写"，而是"永不静默写"** —— 任何路径都不会在用户不知情的情况下往卡上写字节。`src/xmp.ts` 的不变式 1 已按这个新表述重写。

**两个容易被漏掉的点（都处理了）**：

1. **已经显式选过档位的老用户不受影响。** `readXmpMode()` 只在读不到合法值时才回落到缺省；一旦用户点过档位，`writeXmpMode` 就把 `imagefilter-xmp-mode` 落成 `off`/`ask`/`on`，此后永远读到那个值。只有"从没碰过设置"的用户会看到新的询问弹窗。
2. **顺带纠正了一处文档内自相矛盾**：`实施方案` 里会话 ④ 的启动提示还写着"默认 **off**，不碰用户的卡"，那是当时的约束快照。已就地加失效标注（保留原文），而不是改写历史。

**为什么这样做是对的**：星级/色标只留在这台电脑上价值有限，而绝大多数用户不会主动去设置里翻三档开关；`ask` 是"既不静默碰卡、也不至于让用户永远发现不了这个功能"的折中。

**验证**：`npx tsc --noEmit` exit 0；i18n zh/en 各 263 叶子 key 仍对齐（本次只改注释与一个常量，不动 UI 文案）。

