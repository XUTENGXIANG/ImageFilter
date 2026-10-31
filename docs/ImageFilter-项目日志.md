# ImageFilter 项目日志

> 记录**做了什么、为什么这么做、踩了什么坑、怎么证明修好了**。
> 区别于「更新说明」（面向使用者，只讲可见变化）与「功能实施方案」（面向实现，是规格）：
> 这份是**工程侧的账本**，写给人回忆用。
>
> 最近更新：2026-10-31 · 对应 `HEAD 4190048` · 版本 **1.2.1** · 分支 `feat/android-gallery-service`
> 工作区另有未提交的 **mDNS 配对 + 浅色主题 + 高清提速 + 竖屏方向修复 + 目录计数口径**（见 §十一 / §十二 / §十三 / §十四）。

---

## 一、当前状态（一屏看完）

| 项 | 值 |
|---|---|
| 桌面端 | Tauri 2 + React 19 + Rust，**1.2.1**；Windows 安装包（NSIS + MSI）、macOS 通用包（Intel + Apple Silicon）|
| 手机端 | 原生 Kotlin + Compose（Material 3），**1.2.1**（`versionCode=3`），minSdk 26 / targetSdk 35 |
| 分支 | `feat/android-gallery-service`（**已推送**，与远端同指 `4190048`）|
| tag | `v1.0-beta` `v1.0` `v1.0.0` `v1.0.1` `v1.1.0` `v1.1.1` `v1.1.2` **`v1.2.0`** **`v1.2.1`** |
| 提交 | 自服务开工以来 **73** 个（仓库共 317 个跟踪文件）|
| 桌面端测试 | `cargo test --lib` **196/0/4** · `--test server_contract` **65/0/0** |
| 安卓测试 | JVM **545/0**（39 个测试类；服务开工前是 0）|
| 前端门禁 | `tsc --noEmit` 0 · `npm run build` ✓ · 中英文案 **354/354** 一致 |
| 1.2.1 本地构建哈希 | win setup `F1D717A2…25DA5F8` · win msi `1D96AD67…5FB9EAC4` · **CI 的 macOS dmg `258BF6CC…4B06A6`** |

---

## 二、时间线

### 2.1 之前（1.0 → 1.1.x）—— 桌面端导入工具

插卡 → 预览 → AI 筛片 → 一键导入 —— 这一段的历史细节见
[功能实施方案](ImageFilter-功能实施方案.md) 与
[架构与机制走查](ImageFilter-架构与机制走查.md)，以及 `docs/ImageFilter-1.1.*-更新说明.md`。
本日志不重述。

### 2.2 本次（2026-10-06 起）—— 加上「手机远程筛片」这条主线

一句话：**电脑仍是宿主，手机变成一台远端的取景/打分器。**
照片始终留在电脑上，手机只拉缩略图与预览；边车的读写全在电脑那侧完成。

| 里程碑 | 内容 | 验收 |
|---|---|---|
| 规格 | [安卓客户端设计规格](superpowers/specs/2026-10-06-imagefilter-android-design.md)（含 §4 局域网模式、§5 质量契约、§9 持久化）| — |
| **M0′** | 桌面端内嵌 axum 图库服务：共享根白名单 + Bearer 鉴权 → 浏览/扫描/缩略图/预览 → 决策读写 → 移动 → 生命周期/配置 → 设置面板 → 局域网地址枚举 + 公用网络护栏 | `server_contract` 54 条 HTTP 测试 + 51 项契约脚本 |
| **M1′–M4′** | 安卓：配对（含扫码）→ 目录树/网格/查看器 → 打分/色标/筛选 → 滑卡/撤销/待重试/移动 → 发布准备 | JVM 从 0 涨到 491 |
| **1.2.0** | 首次带上手机端能力的发布（10-17/18）| 见 §五 事敌簿的收尾 |
| **1.2.1** | macOS 实机反馈的 5 处修正（10-18 01:10）| 见 §四 |

---

## 三、关键决策（每条都带代价）

| 决策 | 理由 | 代价 / 边界 |
|---|---|---|
| **桌面端就是图库宿主**，不引入 NAS/图床 | 用户就一台电脑；少一层同步就少一类 bug | 电脑必须开机且在同一局域网 |
| **手机不解析任何图片格式、不碰 .xmp** | 安卓解 RAW/EXIF 的坑太多；把复杂度留在电脑 | 所有"看"与"写"都要经过 HTTP，弱网下体验受带宽支配 |
| **写回由电脑执行**（手机只发"决定"） | `.xmp` 的格式与并发是电脑端既有能力 | 手机端"写成功"只能等于"服务端确认" |
| **只做局域网**（2026-10-07 用户决定）| 隧道/穿透的风险与维护成本远大于收益 | **不做 TLS**；公用网络上配对码可被嗅探 → 由此有了"公用网络不自动启动"的硬护栏 |
| **服务默认关闭**，关掉即停监听 | 一个默认开着的 HTTP 服务是事故 | 每次开机要手动开（公用网络还要确认一次）|
| **不做删除、不做新建文件夹** | 「筛片」不等于「删片」；误删不可逆 | 手机只能移进电脑上已存在的文件夹 |
| **质量契约**：只降不升 / 显式 q88 / 方向只校正一次 / 绝不 `img.save()` | 手机端拿到的画质要可预期，且不能"每次都不一样" | 需要成体系的回归测试（`server_contract` 里那批）|
| **保留绝不降级**（右滑不改 4–5 星） | 用户最不能接受的是"我什么都没做，分没了" | 滑卡的语义比"点赞"复杂：它只在一侧有写权限时才写 |

---

## 四、事故簿（这份日志最值得看的部分）

每条格式：**症状 → 根因 → 修法 → 怎么证明**。
下面这些都是"看起来能跑、实际会骗人"的那一类 —— 也是它们促成了 §六 的验证纪律。

### 4.1 滑卡"保留"把 4 星静默改成 3 星

- **症状**：文件夹级决策读取失败（或只读回一部分）时，右滑"保留"会发 `rating:3`，把电脑上 4–5 星覆盖掉，并且**报告成功**。
- **根因**：`current.rating == null` 同时表示"电脑上确实没打星"和"这台手机不知道"。滑卡层拿不到 `readFailure/decisionsKnown`，于是把"不知道"当成了"未评分"。
- **修法**：引入 `DesktopRating.Unknown` / `Unrated` / `Rated(n)` 三态，"未知"一律不写；服务端补了逐项 `sidecarReadable`（见 4.3）。
- **证明**：新回归用例在旧代码上是红的（断言覆盖 `assertNull(rating)` + 全部请求为空，而不是只对比某一个 fallback 值）。

### 4.2 撤销"撤销了"，但电脑上什么都没变

- **症状**：桌面端原本无星 → 右滑写入 3 星 → 按撤销 → 手机显示「已撤销」，边车仍是 3 星。
- **根因**：撤销发出的反向写是 `{rating:null, label:null}`，而协议里 `null` 的语义是"**别碰这个字段**" → 服务端直接 `skipped`、连文件都没打开；手机却把这个"跳过"当成了成功。
- **修法**：反向写改用项目本来就有的"清除"原语（`rating:0` / `clearLabel:true`）；服务端回报 `written=0 & skipped>0` 时**如实说"这次写入什么都没改"**，绝不显示"已撤销"。
- **证明**：往返验证 —— 4★ → 左滑 0★ → 撤销 → 边车 MD5 回到**基线那一份**。

### 4.3 手机改分，电脑端界面不刷新

- **症状**：手机上打 4 星，边车确实写进去了，但电脑端界面不动；而且桌面端 localStorage 里悄悄多出一份 `\\?\` 开头的重复键。
- **根因**：服务端把路径 `canonicalize` 后发出（Windows 上是 `\\?\D:\…` 逐字路径），而桌面端的映射表用的是**从未规范化**的普通路径 → 键对不上，事件被合并到"不存在的那一行"。
- **修法**：加一个纯函数把事件路径**映射回桌面自己的键空间**（剥 `\\?\` 与 `\\?\UNC\`，普通路径原样返回 —— **绝不"顺手规范化"**，否则会触发下一个坑：合并规则是"边车优先"，一旦键错了会覆盖用户还没落盘的本地值）；同时尊重 `xmpMode` 闸与待写队列。
- **证明**：留了一个永久的 node 检查，**自带反例自检**（故意写错的期望必须被判为失败，证明断言器真的会判否）。

### 4.4 配对二维码可能编码一个"死端口"

- **症状**：服务端口被占时会顺延 0..+19，而二维码按**配置端口**生成 → 手机扫到的地址连不上，界面却显得很权威。
- **修法**：后端只在**真的在监听**时才出码，且编码**实际绑定的端口**；前端在"没在跑"时改说清原因，不再画图。
- **证明**：用占位监听逼出"配置 ≠ 绑定"的组合；变异回旧行为，两条用例各自变红（且互不代偿）。

### 4.5 空 token 竟然能通过鉴权

- **症状**：常量时间比较函数在**两边都是空串**时返回 true → 一旦 token 落盘失败变成空串，服务等于没有鉴权。
- **修法**：空 token 永不通过（fail-closed）。
- **证明**：直接测 `eq_constant_time("","")` 为 false。

### 4.6 多出一条"不带鉴权层"的路由入口

- **症状**：为了图省事，同一组端点被挂了两份 router，其中一份没有中间件 → **不带 token 也能 200**。
- **修法**：删除第二入口，只留一个 `routes::router(state)`，鉴权收成全路由 fail-closed 中间件。
- **证明**：契约脚本里"无 token → 401"覆盖**每一个**受保护路径（按枚举而不是手写清单）。

### 4.7 手机上"撤销"点不到（只有 10 像素能点）

- **症状**：查看器与滑卡页的顶栏画在**系统状态栏底下**（`[0,0]-[1080,128]`），`撤销`/`关闭` 的矩形是 `[750,12][917,138]` → 只有最下 ~10px 能点，实际不可用。
- **根因**：这两个页面没做 inset 避让（网格页做了，所以一路都点得动，反而掩盖了问题）。
- **修法**：给这些页面加系统栏/手势条 inset；**逐屏审计**后发现坏的是 4 个页面（查看器、滑卡、移动面板、配对页），不是 2 个。
- **证明**：修复前点 (833,75) 三次无效、点 (833,132) 立刻生效；修复后按钮矩形移到 `[765,140][917,266]`，点中心即可。

### 4.8 移动"半成功"之后，重试会把整批打成 404

- **症状**：照片移走了、边车没移走（被别的程序占着）。此时该项的 `src` 已经不存在 → 手机若"整批重发"，整批都会因为一条死路径而失败。
- **修法**：移动结果逐项返回 `pendingSidecars`（永远是数组），重试只发 `{paths: pendingSidecars, destDir: parent(dest)}`；"全部重试"只重发仍然待重试的项。
- **证明**：用独占锁造出半成功；再用重试通道补移成功，源目录清空、边车带着星级落到目的地。

### 4.9 macOS 上"玻璃默认 70"改不动

- **症状**：改了默认值，但两个滑块仍显示 0%。
- **根因**：1.2.0 已经在那台机器上跑过一次，把 `0` 写进了 `imagefilter-glass-opacity` / `imagefilter-background-opacity`（当时 0 就是默认值）→ **"没存过才用默认"的分支永远走不到**。
- **修法**：加**一次性迁移**（标记 `imagefilter-mac-defaults-v1`）：只在标记未写时把仍是 0 的键纠到 70；之后用户自己拖过的值一律优先。
- **证明**：UIA 读到两个滑块都是 `70`。

---

## 五、发布

| 版本 | 日期 | 内容 | 产物 |
|---|---|---|---|
| 1.2.0 | 2026-10-17/18 | 首次带手机端能力（局域网图库服务 + 安卓客户端）+ macOS 窗口外观 | dmg 21.11 MB · win setup 7.26 MB · msi 10.41 MB |
| 1.2.1 | 2026-10-18 | macOS 实机反馈 5 处修正（见 §7.2）| dmg 21.13 MB · win setup 7.26 MB · msi 10.41 MB |

> 体积按 **release 上的 CI 产物**记（`gh release view` 读到的 size）。本机另外打的那份 1.2.1
> （`release\windows\`）略大（setup 7.27 MB / msi 11.31 MB），因为打包环境与镜像源不同 —— 两者版本一致，内容等价。

发布流程（可复用，吃过亏的部分已标注）：

```powershell
# 1) 版本号四处统一（android release 构建会校验它们一致）+ Cargo.lock 里本地包那条
#    package.json / src-tauri/tauri.conf.json / src-tauri/Cargo.toml / android/version.properties
# 2) Windows 安装包
npx tauri build                       # 3min 左右增量
# 3) 推分支 + 打 annotated tag（build.yml 由 push tags v* 触发）
git push origin feat/android-gallery-service
git tag -a v1.2.1 -m "..."
git push origin v1.2.1
# 4) CI（windows-latest + macos-latest，mac 走 --target universal-apple-darwin）
# 5) 发布草稿 release：gh release edit v1.2.1 --draft=false
# 6) 取 dmg（两步都必要，见 §8 的坑）
```

---

## 六、验证纪律（事故换来的）

1. **文件是真相，界面不是。** 判断"这次操作成功没有"看盘上的 `.xmp`/目录，不看 toast。
2. **LevelDB/localStorage 是滞后真相**（攒批落盘，可滞后数秒）—— 只能当审计，用来判断"刚才那一下"必然误判。
3. **变异取证。** 每个修复都要能演示"把 bug 改回去，测试变红"。不能说"现在绿了"就算。
4. **一击一验。** 注入点击/按键后必须确认它真的生效（读元素矩形或读界面文案），否则后面全是错位的结论。
5. **假绿三型**（本项目都遇到过）：
   - 只对某一个 fallback 断言（换个值就漏）；
   - 夹具让关键分支不可达（例如用 `mergeRemote` 播种，"值已知"成了前提）；
   - 只覆盖对称分支的一侧（读失败有覆盖、读"部分成功"没覆盖）。
6. **不可达的分支也要标注。** 例如 `ApiError::Conflict`(409) 目前进不来 —— 写在注释和文档里，比假装它有用强。

---

## 七、macOS 适配（2026-10-18 那一轮）

### 7.1 背景

1.2.0 的 macOS 包第一次在真 Mac 上被使用，暴露出两类问题：**窗口外观是 Windows 的**（无圆角、右侧 Windows 按钮），以及**若干功能在 mac 上不可用却照常显示**。

### 7.2 5 处修正

| # | 要求 | 做法 |
|---|---|---|
| 1 | 帮助/设置/主题按钮移到左边，别压住三个圆点 | mac 顺序改为 圆点 → 产品名 → 三个图标（两组间留 8px）；Windows 侧渲染逐字不变 |
| 2 | 隐藏远程图库功能 | macOS 上 `<ServerPanel>` 整块不渲染（那套网络类别/路径语义是 Windows 形状的、未在 mac 验证）|
| 3 | 玻璃默认 70、去掉 "window" 标识 | 描述改「macOS 的透明玻璃背景」；两个滑块默认 70 + **一次性迁移**（见 4.9）|
| 4 | LrC 检测不到 | 写明「macOS 版暂不支持（仅 Windows）」并禁用两档按钮 —— 原先是"探不到但界面照常可点"|
| 5 | 首次启动引导给权限 | 新增一次性弹窗：讲清「文件与文件夹」反复询问的根因与「完全磁盘访问权限」位置，一键跳系统设置（新命令 `open_macos_privacy_settings`）|

### 7.3 这一轮的取舍

- **窗口圆角**走的是"自绘窗口 + CSS 裁圆角"，需要 `macOSPrivateApi`（Tauri 要求透明窗口必须开它）→ **这个包不能上 App Store**。本项目走 GitHub 直接分发，可接受。
- **没有**改用系统原生标题栏（`titleBarStyle: Overlay` 那条路）：它需要 `decorations: true`，而运行时改不了 `hiddenTitle`，且当时没有 Mac 可验证 —— 选了一条**能在这台机器上强制平台截图核对**的路。
- bundle identifier `com.imagefilter.app` **以 `.app` 结尾**，Tauri 会警告"与 macOS 的 .app 扩展名冲突"。**故意不改**：改它会让 Windows 现有 `%APPDATA%\com.imagefilter.app` 数据被孤立、需要写迁移；而 CI 用这个 ID 也确实成功出了 dmg。

---

## 八、环境与工具链（照抄即可复现）

| 项 | 值 |
|---|---|
| JDK | Temurin 21（`%LOCALAPPDATA%\Programs\jdk-21\jdk-21.0.12.1+1`，**必须给全路径**）|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`；build-tools 35.0.0、platform-tools 37.0.1、platforms;android-35 |
| Gradle | wrapper 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · Coil 3.0.4 |
| 模拟器 | AVD `imagefilter`(pixel_6/API 35/x86_64)；**AEHD 驱动可免重启**（`extras;google;Android_Emulator_Hypervisor_Driver` → `silent_install.bat` 自带 UAC 提权）；`emulator -accel-check` 应为 `AEHD is installed and usable` |
| 模拟器网络 | 客户机**没有默认路由** → 连不到宿主局域网 IP；用 `adb reverse tcp:8765 tcp:8765` + `http://127.0.0.1:8765` |
| 模拟器 GPU | **别用 `-gpu swiftshader_indirect`**：那会全程软件渲染（实测 EGL 平均 157 ms/帧），会让人误判"动效卡"。本机有 RTX 3060，用 `-gpu host` |
| 网络（本机）| `github.com` / `raw.githubusercontent.com` / `objects.githubusercontent.com` / `cdn.jsdelivr.net` / `developer.android.com` 均被解析到 `127.0.0.1` 或超时；**Maven Central、api.github.com、ghproxy.net、m3.material.io 通** |
| 下载 CI 产物 | `gh release download` 会重定向到被屏蔽的 CDN → 卡死；**可用路线**：先 `gh release edit … --draft=false` 发布，再 `ghproxy.net/https://github.com/…/releases/download/<tag>/<asset>` + `curl -C -` **循环续传**（每次约 +2 MB），直到字节数等于 release 记录的 size |
| UI 自动化 | 安卓：`uiautomator dump` 读矩形 + `input motionevent`（**`input swipe` 不被本项目的 Compose 拖拽识别**）；桌面：`gui.ps1`（会校验前台再截图）|
| 脚本编码 | Windows PowerShell 5.1 读无 BOM 的 `.ps1` 会按 ANSI/GBK 解码 → **含中文的脚本必须存成 UTF-8 带 BOM** |

---

## 九、已知限制与未验证项（诚实清单）

**未在真机验证**
- 安卓端全部验收只在 **emulator(API 35)** 上做过：真实手机、真实 WiFi（AP 隔离 / 访客网络 / 电脑休眠）、相机扫码、手势手感 —— 都没有。
- macOS 侧：窗口外观与 §7.2 的 5 处修正，都只做到"平台分支正确 + 在本机强制 `platform=macos` 渲染核对"；**真 Mac 上的最终观感没有亲眼确认**。`open_macos_privacy_settings` 是否真的跳到「完全磁盘访问权限」页，完全未验证。
- macOS 的 `transparent` 是否真的生效（圆角靠它）也未验证。

**已知缺口**
- **安卓 APK 仍是 1.2.0**（`versionName` 已升到 1.2.1 / `versionCode=3`，但那份包没重打）。
- `docs/` 里**缺 1.2.0 / 1.2.1 的更新说明**（只有 1.1.0–1.1.2）。
- 待重试队列**跨应用重启**的持久化未在真机验过；服务端 `409 conflict` 分支当前不可达。
- 部分边角：`%TEMP%` 夹具泄漏、标题栏 Toggle 缺可访问名、色标圆点点击区 < 48dp。

---

## 十、下一步

1. **真机验收**安卓端（说明页第四节那 9 条清单）—— 这是目前最大的不确定性来源。
2. macOS 上确认 §7.2 五项与 `open_macos_privacy_settings`。
3. 安卓 1.2.1 重打 APK；补 `docs/ImageFilter-1.2.*-更新说明.md`。
4. 若要让 macOS 分发"双击即开"：接 Apple Developer（$99/年）+ CI 里 `codesign` + `notarytool`。
5. 外观方向未定：Material 3 / Cupertino（iOS 风）/ 极简暗色 —— 相关组件与许可证调研结论见会话记录，未落地。

---

## 十一、2026-10-25 · mDNS 配对与浅色主题（工作区，未提交）

### 11.1 连接方式改为「发现 + 双设备确认」

旧路径（手输 IP + 64 位配对码）从主路径降级为「高级模式」。默认路径：

1. 桌面端在服务运行、网络为「专用」时广播 `_imagefilter._tcp.local.`；TXT 只有
   `v / id / name / pair`，**不含 token**。
2. 安卓 `NsdManager` 发现设备；点一下设备 → `POST /api/v1/pair/request`。
3. 桌面弹出「手机 xxx 请求连接」→ 用户点「允许」。
4. 手机轮询 `GET /api/v1/pair/status` 拿到 `{url, token, desktopName}`，存入
   `PairingStore`，与手输/扫码走同一条保存路径。

边界：配对窗口 **5 分钟**（点「配对新设备」才开）、请求 **60 秒**过期、
每设备 **10 秒 1 次**、最多 **3 个**待确认；两个端点免 Bearer，但只在窗口内可用，
其余端点继续 fail-closed。查看器/滑卡保持近黑画布，浅色主题只改浏览/网格/连接/设置。

### 11.2 浅色主题

- `PhotoPalette` + `lightColorScheme`：画布 `#FAFAFA`、面板 `#F4F4F5`、卡片 `#FFFFFF`、
  黑 6% 发丝线；浅色主色用 emerald-700 `#047857`（白字 5.48:1），弱文字 `#6B6B75`
  （在 `#F4F4F5` 上 4.79:1）。
- 设置弹窗「外观」三档：跟随系统 / 浅色 / 深色，存 DataStore，默认跟随系统。
- **查看器与滑卡始终深色**（与桌面 `viewer.tsx` 的 `bg-black/95` 一致）；
  其余页面跟随主题。启动窗口底色由 `values/` 与 `values-night/` 两套 XML 决定。

### 11.3 验证与证据

- Rust：`cargo test --lib` **188/0/4**、`--test server_contract` **65/0/0**。
- 安卓：JVM **545/0**、`assembleDebug` 通过。
- 桌面：`tsc --noEmit` 0、`npm run build` 通过。
- 截图：`docs/evidence/mdns-pairing/`（发现/空态/等待/高级模式/桌面设置面板）、
  `docs/evidence/light-theme/`（深/浅连接页、目录、网格、设置外观、查看器）。

### 11.4 未验证（这次最大的不确定性）

- **真实 mDNS 已验证发现**：手机在同一 WiFi 下能搜到电脑（2026-10-31）。曾遇到一次
  Edge/Steam 占用 UDP 5353 导致该次启动未广播，端口释放/重启后恢复。
  仍需真机验证：桌面「允许」→ 手机进入的完整配对、AP 隔离/访客网络、
  Android 13+ `NEARBY_WIFI_DEVICES` / Android 16 本地网络权限。
- 桌面端 `pairing-request` 弹窗未被真实手机触发过；Tauri 命令只做了编译与面板渲染验证。
- 浅色主题只验证了模拟器；真机户外可读性、OEM 手势条未验。
- 「移除设备」不吊销 token；要吊销走「轮换」（所有设备重连）。
- 待确认满 3 个返回 429 `rate_limited`，安卓会提示"稍后再试"，不是"电脑上待确认太多"。
- 只读模式下仍允许配对请求（只发放已有 token、不写照片）—— 这是有意的策略选择。

### 11.5 下一步

1. **真机 + 同一 WiFi** 跑完整链路：开服务 → 配对新设备 → 允许/拒绝 → 断开重连。
2. Windows 防火墙放行 UDP 5353；确认「公用网络」提示与专用网络切换。
3. 真机看浅色主题的户外可读性与系统栏。
4. 决定是否给安卓加「移除设备即吊销」的 per-device token（当前是单 token）。

---

## 十二、2026-10-31 · 高清预览提速

### 12.1 症状

安卓查看器点「高清」要等很久；默认档很快。不是 WiFi 问题——默认档 80–140ms，
高清档主要耗在电脑侧。

### 12.2 根因

- 安卓高清请求是 `max=2048` + `full=true`；服务端 `full=true` 直接进 `get_full_image`。
- **DNG 分支无条件全解码**（`decode_raw_slow` / tinydng / WIC），完全不看内嵌预览。
- 非 DNG 也要内嵌预览长边 ≥3000 才走快路径，而手机只要 2048。
- 全解码先缩到 5000 落 `full_v3`，再 `image::open` + Lanczos3 缩到 2048：两次缩放。
- 预览/全图共用 `Semaphore(1)`，一次全解码会堵住下一张默认预览。

### 12.3 修法

1. `full=true` 先取内嵌预览；长边 **≥ 请求尺寸的 90%** 就直接用，不够才全解码。
   DNG 也走这条。阈值有单测（1920/2048 通过，1600/2048 不通过）。
2. `encode_preview` 的缩放换成 `fast_image_resize`（SIMD Lanczos3，Apache-2.0）；
   失败时回退原 `image` 实现。桌面缩略图/全图缓存的 `resize_max_edge` 不动。
3. 普通预览闸门 `2`、高清全解码闸门 `1`，全解码不再堵住默认预览。
4. debug 构建输出 `[preview] source=embedded|full … open_ms/encode_ms/total_ms`；
   DNG 分支输出 rawler/tinydng/WIC 各自耗时。release 不输出。

### 12.4 实测（用户真实照片）

| 场景 | source | 输出 | 总耗时 |
|---|---|---|---|
| 默认 RAW 预览（1616×1080 内嵌） | `preview` | ~180–250KB | **80–140ms** |
| 高清 ARW | `full`（内嵌只有 1616，<90%×2048） | 2048 / ~260–280KB | **~2.6s** |
| 高清 50MB 线性 DNG | `full`（rawler 全解码） | 2048 / ~640KB | **3.4–3.8s** |

结论：这批 ARW 的内嵌预览不到 1844，所以 P0 的内嵌快路径没命中；体感变快主要来自
SIMD 缩放 + 双闸门 + 全图缓存。用户验收“差不多了挺快的”。

### 12.5 仍未解决

- **mDNS 曾遇到一次 5353 被 Edge/Steam 占用**：那次启动 ImageFilter 没绑上 5353，
  手机搜索不到；端口释放/重启后恢复，用户已实测能搜索到。仍建议做启动重试/兜底。
- 内嵌预览只有 1616 的 ARW，高清仍要 ~2.4s 全解码；再快需要 LibRaw/rawspeed，
  或导入时预生成 2048/4096 预览。
- 高清输出仍是 2048；真要 1:1 验焦需要更大的 `max` 和更重的解码，暂未做。

### 12.6 产物

- 新 APK：`release/android/ImageFilter-Mobile-1.2.1.apk`
  （`versionCode=3`，SHA-256 `80c5dc62…5c65de2c`；未设置签名环境变量时仍是 debug 签名回退）。
- 高清文案已改成“内嵌大预览优先，不够才全解码”。

---

## 十三、2026-10-31 · 竖屏照片方向修复

### 13.1 症状

一部分竖屏 RAW 在桌面查看器里会先显示正确（内嵌预览），约 600ms 后后台全图替换上去，
照片**翻成横的**。

### 13.2 根因

- 非 DNG、内嵌预览长边 ≥3000 的 RAW：`get_full_image` 把内嵌 JPEG **逐字节**拷进
  `full_v3`，不物理旋转；桌面 `<img>` 靠 CSS `image-orientation: from-image` 读缓存里的
  EXIF Orientation。
- 有些相机的内嵌 JPEG **不带** Orientation 标签（方向只写在 RAW 容器的 TIFF 里）→
  缓存既没有转向、也没有可以读的 EXIF → 竖图横着。
- DNG 与 RAW 的全解码兜底分支也没有 `apply_exif_orientation`，同一类问题。

### 13.3 修法

- 全图缓存文件名加 `_full_v2` 后缀 → 旧的不带方向的缓存自然失效。
- 内嵌分支：RAW Orientation ≠ 1 时解码 → `apply_exif_orientation` → `resize_max_edge(5000)`
  → **q95** 重编码（缓存不带 EXIF，桌面端不会二次旋转）；Orientation=1/缺失时保留
  逐字节拷贝（内嵌 JPEG 自带 EXIF 时 CSS 照旧生效）。
- DNG 与 RAW 的全解码兜底分支都补 `apply_exif_orientation`。
- 预览/缩略图路径原本已经校正，不动。

### 13.4 证明

- 新增集成测试 `full_raw_cache_is_physically_oriented_and_server_does_not_double_rotate`：
  3000×64 + Orientation 6 的假 `.arw` → 断言 `full_v3` 缓存尺寸 **64×3000**
  （旧实现是 3000×64）；同时 HTTP 输出仍然只旋转一次。
- Rust：`cargo test --lib` **196/0/4**、`--test server_contract` **65/0/0**。
- 用户实机待验：打开原先会横过来的竖屏照片；第一次会重建 `_full_v2` 缓存，可能慢一次。

---

## 十四、2026-10-31 · 目录计数口径 + 开发期规则

### 14.1 计数规则

- **有子目录的文件夹**：显示**直接子目录数**（`N 文件夹`）。
- **没有子目录的文件夹**：显示**直接照片数**（`N 张`）。
- **视频数**：单独显示（`Movie` 图标 + 数字），接口保留在 `FolderEntry.videoCount` /
  `FolderNode.videoCount` / `FolderEntryDto.videoCount`。
- 计数来自 `browse_directory` 的**一层扫描**：每个子目录直接读一次，数子目录/照片/视频。
  不再使用 `count_folders` 的递归照片总数（C:\Users 那种目录要跑 18 秒，且展示口径不对）。

### 14.2 开发期规则（用户明确要求）

- 当前项目处于**开发阶段**：**禁止为旧历史数据写兼容代码分支**。
- 旧数据/旧缓存可以直接丢弃，用新数据测试；需要失效就改缓存版本/文件名，不写迁移。
- Git 提交按修改量自行判断；确保功能不丢失，但不要无脑跑全套测试。

### 14.3 改动与验证

- Rust `FolderEntry` 增加 `video_count`/`subfolder_count`；`photo_count` 只算照片；
  `browse_directory` 对每个子目录做一层计数；`is_video_ext` 与 `scan_directory` 共用。
- 桌面：`FolderNode`/`FolderTreeItem` 按上述规则显示；根目录行也显示；
  视频数用 `Movie` 图标 + 数字。`count_folders` 的前端调用与 `counting` 状态移除。
- 安卓：`FolderEntryDto` 增加 `videoCount`/`subfolderCount`；`secondaryLabel` 同步规则，
  有视频追加 `· N 视频`。
- 设置 → **显示目录数量**：控制左侧目录树与根目录的数量角标（默认开，存
  `imagefilter-show-counts`）；只影响显示，不影响扫描/计数。
- 验证：Rust **196 + 65**；桌面 `tsc`/`build`；安卓 `--tests '*Browse*'` + `assembleDebug`。
