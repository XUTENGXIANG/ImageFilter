# ImageFilter Mobile · M4′ 配对体验 / 打通引导 / 只读模式 / 发布 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"能用"变成"别人下载下来也能用"——扫码配对代替手输 64 位配对码、**局域网地址枚举与防火墙引导**把"同一 WiFi 怎么连"讲清楚（**不做隧道**，2026-10-07 决策）、补上只读模式与"谁在连我"，最后把发布流程钉死（固定签名 + CI 出 APK）。

**Architecture:** 唯一的跨端改动在这里：桌面端要**生成二维码**并**枚举本机私有网段地址、检测网络类别**，手机端要**扫码**并展示引导。只读模式与已连接来源是服务端的事，手机端只消费。

**Tech Stack:** 在 M1′–M3′ 之上**只允许一个新依赖**：`com.journeyapps:zxing-android-embedded`（Apache-2.0，离线可用，不依赖 Google Play 服务）。桌面端新增 `qrcode` crate（MIT/Apache-2.0）。

## Global Constraints

沿用 M1′–M3′ 的全部约束，**外加两条**：

- **本计划只允许两个新依赖**：手机端 `zxing-android-embedded`、桌面端 `qrcode`。**其他一律不加**；想加就报 NEEDS_CONTEXT。
- **发布签名必须固定**：同一个 release keystore 用到底。换签名会让新版本**无法覆盖安装**（只能卸载重装，丢本地缓存与待重试项）——这是自用侧载最容易忽略、后果最烦人的一条。
- **首启引导必须把 §1.3 的四条排查清单原样呈现**（电脑没睡眠 / ImageFilter 在运行 / 服务已开启 / **手机与电脑在同一个 WiFi**）——用户从手机这一侧看不出这四条里哪一条不成立。

---

### Task 1: 扫码配对（桌面端生成 + 手机端扫码）

**为什么值得加依赖**：配对码是 64 位十六进制。手输它不现实，而复制粘贴在"手机 × 电脑"之间并不存在。二维码是唯一顺手的通道。

**Files:**
- 桌面端：Modify `src-tauri/Cargo.toml`、`src-tauri/src/server/mod.rs`、`src/components/settings-dialog.tsx`
- 手机端：Modify `android/app/build.gradle.kts`、`AndroidManifest.xml`、`ui/PairingScreen.kt`

**Interfaces:**
- 桌面端 Produces：`server::pairing_qr_svg() -> Result<String, String>`，返回二维码的 **SVG 字符串**（配对内容 = `{"url":"http://…","token":"…"}`）
  - 用 SVG 而不是 PNG 的理由：前端直接 `dangerouslySetInnerHTML` 或 data-URI 显示，不需要引入 JS 二维码库、也不需要额外的 asset scope 放行
- 手机端 Consumes：扫码得到同一段 JSON

- [ ] **Step 1: 桌面端生成二维码**

`src-tauri/Cargo.toml` 加：

```toml
# 配对二维码: 只用来把 "地址 + 配对码" 编成能被手机扫的图形, 不参与其它逻辑。
qrcode = "0.14"
```

`server/mod.rs` 加：

```rust
/// 把"地址 + 配对码"编成二维码 SVG, 给设置面板直接显示。
///
/// 为什么是 SVG: 前端直接塞进 DOM 即可, 不必引 JS 二维码库、也不必把 PNG 放进
/// asset scope(那条路径在本项目是**收紧为空**的, 见交接文档 §4.6)。
pub fn pairing_qr_svg(url: &str, token: &str) -> Result<String, String> {
    let payload = serde_json::json!({ "url": url, "token": token }).to_string();
    let code = qrcode::QrCode::new(payload.as_bytes()).map_err(|e| e.to_string())?;
    Ok(code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(220, 220)
        .dark_color(qrcode::render::svg::Color("#000000"))
        .light_color(qrcode::render::svg::Color("#ffffff"))
        .build())
}
```

加一个单测：`pairing_qr_svg` 返回的字符串以 `<svg` 开头且包含 `</svg>`；同一输入两次结果相同（二维码是确定的）。

- [ ] **Step 2: 桌面端设置面板显示二维码**

在服务面板里：显示二维码 + 一行"用手机上的 ImageFilter 扫这个码" + 仍然保留文本形式的地址与配对码（**扫码失败时的退路**）。

- [ ] **Step 3: 手机端扫码**

`android/app/build.gradle.kts` 加 `implementation("com.journeyapps:zxing-android-embedded:4.3.0")`；清单加：

```xml
<!-- 扫码配对。**只用于扫二维码**, 不申请存储权限(不需要)。 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

配对页加"扫码"按钮 → `ScanContract` 启动 → 解析返回的 JSON：

```kotlin
// 扫码结果就是桌面端 pairing_qr_svg 里的那段 JSON。解析失败要给明确提示,
// 不要把"扫到了别的二维码"报成"配对码错误" —— 那是两回事。
@Serializable
private data class PairingPayload(val url: String, val token: String)

fun parsePairingPayload(raw: String): Result<PairingPayload> = runCatching {
    com.imagefilter.mobile.data.Json.decodeFromString<PairingPayload>(raw)
}.recoverCatching { throw IllegalArgumentException("这不是 ImageFilter 的配对码") }
```

补 JVM 单测：合法 JSON 能解析；一段随便的文本（如某个网址）解析失败并给出"这不是 ImageFilter 的配对码"。

- [ ] **Step 4: 真机验证**

1. 桌面端点"显示二维码" → 手机扫码 → **自动填好地址与配对码** → 测试连接通过
2. 扫一个别的二维码（如某个网址）→ 明确提示"这不是 ImageFilter 的配对码"，不崩
3. 拒绝相机权限 → 仍能用手输完成配对（扫码是增强，不是唯一路径）
4. 电脑上轮换配对码 → 旧二维码失效 → 手机测试连接报 `unauthorized` 并提示"去电脑上重新扫码"

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add src-tauri/Cargo.toml src-tauri/Cargo.lock src-tauri/src/server/mod.rs src/components/settings-dialog.tsx
git commit -m "feat(server): 设置面板显示配对二维码(qrcode SVG)"
```
```powershell
git add android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/PairingScreen.kt
git commit -m "feat(android): 扫码配对(zxing, 保留手输退路)"
```

---

### Task 2: 局域网地址枚举 + 公网网络防护（LAN-only）

**为什么这仍是"别人下载下来也能用"的关键**：现在只剩局域网一种模式，于是**"填哪个地址"成了配对里唯一的技术门槛**，而**"防火墙拦了"成了第一大失败原因**。规格 §4.2 只要求三件事：列私有地址、醒目说明防火墙、二维码。

> **决策（2026-10-07，使用者拍板）**：隧道/组网工具引导**整体删除**——原来的 EasyTier / Tailscale / Cloudflare Tunnel 三条指引**不复存在**。理由见规格 §4 与附录 A #4/#5。

**Files:**
- 桌面端：Create `src-tauri/src/server/network.rs`、Modify `src/components/settings-dialog.tsx`、Modify `src-tauri/src/server/mod.rs`（暴露探测入口与启动前防护）
- 手机端：Modify `ui/PairingScreen.kt`（**只改文案**：去掉"隧道"字样，第一条改成"手机和电脑要在同一个 WiFi"）

**Interfaces:**
- Produces:
  - `#[derive(Serialize)] pub struct NetHints { pub lan: Vec<LanAddress>, pub public_ipv4: Vec<String>, pub profile: NetworkProfile, pub firewall_hint: String }`
  - `pub struct LanAddress { pub address: String, pub interface: String }`
  - `pub enum NetworkProfile { Private, Public, Unknown }`
  - `server::network::detect_hints() -> NetHints`
  - `server::network::should_refuse_to_start(&NetHints) -> Option<String>`（`None` = 可启动；`Some(理由)` = 拒绝并要求用户确认）

- [ ] **Step 1: 实现探测（纯本机、不联网）**

三条探测，**都只读本机状态**，不发起任何外部请求：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 本机"可能可用的访问地址"探测 —— 只做推荐, 不做保证。
//!
//! 为什么值得做: 手机端拿不到电脑的网卡信息, 而"填哪个地址"是跨网使用里最容易劝退
//! 的一步。桌面端能看见自己的网卡, 于是可以**直接把候选地址列出来**。
//! Immich 在这个问题上只给了一篇文档 —— 这是我们能做得更好的地方(规格 §4.2)。

use serde::Serialize;

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetHints {
    /// **私有网段**地址(10./172.16-31./192.168.), 每条带网卡名 —— 配对时要填的就是它
    pub lan: Vec<LanAddress>,
    /// 公网 IPv4 地址: **只用于警告, 绝不进候选列表**
    pub public_ipv4: Vec<String>,
    /// 当前网络类别(Windows NetworkListManager)
    pub profile: NetworkProfile,
    /// 怎么在「专用网络」上放行入站(局域网模式下第一大"连不上"的原因)
    pub firewall_hint: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanAddress { pub address: String, pub interface: String }

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum NetworkProfile { Private, Public, #[default] Unknown }
```

实现要点：枚举本机 IPv4 并按网段归类（**公网地址只进 `public_ipv4`，绝不进 `lan`**）；网络类别走 Windows `NetworkListManager` 的 COM 接口，**若取类别要引入依赖或大量 unsafe，就退化为"只检测是否有公网 IPv4"并把这个退化写进 KDoc**——宁可少一个信号，也不引入一个平台 crate。**KDoc 还要写明**：本模块必须保持**纯数据类型**，绝不能把 `AppHandle`/窗口类型带进来——本项目已踩过这个坑：那会让测试可执行文件在**加载时**就死（缺 Common-Controls v6 清单），而不是测试变红。

- [ ] **Step 2: 启动前的安全防护（规格 §4.3 的硬约束）**

- `profile == Public` 时**不自动启动**：`start()` 返回一个可读理由，UI 显示"当前网络被 Windows 标为『公用』。共享网络（咖啡厅/办公室/酒店）上配对码可能被明文嗅探——确认要继续吗？"，**用户确认后才启动，且这次确认要记进状态**（不能静默放行）。
- 检测到**公网 IPv4** 时给出警告。**仍然不提供任何端口转发/公网暴露功能**，也不做成一键。
- **默认行为不变**：服务依旧**默认关闭**；这条防护的作用是让"开了但它本不该开"无法悄悄发生。

- [ ] **Step 3: 桌面端 UI**

服务面板里显示：
- **私有地址列表**，每条标明网卡（"同一 WiFi 用这个"）——**这就是配对时要填的地址**
- **防火墙提示**（在「专用网络」上放行的步骤）——局域网模式下"连不上"的第一大原因
- `Public` 类别时的**警告 + 二次确认**入口
- 检测到公网 IPv4 时的警告
- **面板里不应再出现任何"隧道 / EasyTier / Tailscale"字样**

- [ ] **Step 4: 手机端配对页文案同步**

配对失败且错误像"连不上"时，展开一段可折叠引导（与桌面端文案一致，**别写第二份说法**），包含 §1.3 的四条清单，且**第一条是"手机和电脑要在同一个 WiFi"**——**不再有"同一隧道"这个选项**。

- [ ] **Step 5: 验证**

1. 同一 WiFi：候选里的**私有地址**能直接配对成功
2. 把网络类别改成 `Public`（或在 `Public` 网络上）→ 服务**不自动启动**并给出可确认的提示；确认后能启动
3. 临时移除防火墙放行规则 → 面板提示能指向"放行专用网络"
4. 关掉 WiFi 用流量：私有地址连不上 → 手机端给出引导而不是干巴巴的"网络错误"，且引导里**没有隧道字样**
5. **不去验证公网暴露**（本设计明确不支持，只警告）

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server/network.rs src-tauri/src/server/mod.rs src/components/settings-dialog.tsx
git commit -m "feat(server): 局域网地址枚举 + Public 网络类别不自动启动(LAN-only, 明确不做公网暴露)"
```
```powershell
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/PairingScreen.kt
git commit -m "feat(android): 配对页加入打通引导与四条排查清单"
```

---

### Task 3: 只读模式 + 已连接来源

**为什么**：给陌生人下载的软件，需要"我能随时把它的能力收窄"和"我知道谁在连我"。

**Files:**
- 桌面端：Modify `src-tauri/src/server/config.rs`、`auth.rs`、`routes.rs`、`src/components/settings-dialog.tsx`
- 手机端：Modify `ui/GridScreen.kt` / `ViewerScreen.kt`（只读时隐藏写入口）

**Interfaces:**
- Produces:
  - `ServerConfig` 加 `read_only: bool`（默认 `false`）
  - 只读时 `PUT /decisions`、`POST /move` 返回 `403 forbiddenPath`（**复用既有码，不新造**）。**body 只有 `{ code }`，不带 `detail`** —— 见规格 §5.3：文案由手机端按 code 本地化，不把中文塞进 Rust
  - `GET /api/v1/clients` → 最近 N 次请求的来源 IP 与时间（内存环形缓冲，最多 50 条）

- [ ] **Step 1: 实现只读模式（服务端）**

- 中间件层统一拦：对 `PUT /decisions` 与 `POST /move` 两个路由加一层检查
- **错误码复用 `forbiddenPath`**：手机端已经有对应的可执行文案（"这个目录不在电脑的共享根里"），**且不靠 `detail` 区分场景**（服务端压根不发 `detail`，见规格 §5.3）——不要把场景差异塞进响应体。手机端把 `forbiddenPath` 的文案改成"电脑上不允许这个操作（可能是只读模式，或路径不在共享根）"，一句覆盖两种，避免新造码。
- 加单测：只读时两个写端点都返回 403；读取端点仍正常

- [ ] **Step 2: 已连接来源（内存环形缓冲）**

- 记录 `{ ip, method, path, at }`，最多 50 条；`GET /clients` 返回最近 20 条
- 服务面板显示"最近连接"，**不做实时刷新**（手动刷新即可，避免为它做推送）
- 加单测：超过容量时丢最旧的

- [ ] **Step 3: 手机端在只读时隐藏写入口**

- 只读判断从哪来？**不要额外加端点**：手机端在首次 `PUT` 收到 403 时把界面切成只读并给出提示；同时在设置里显示"电脑上当前是只读模式（如果刚在电脑上改过，重新连一次即可）"
- 星级/色标/滑卡/移动入口置灰，并有一行说明原因

- [ ] **Step 4: 验证**

1. 电脑上开只读 → 手机打分 → 提示"电脑上不允许这个操作"（**不是**"网络错误"）；界面切成只读
2. 电脑上关只读 → 手机重进 → 能打分
3. 电脑面板能看到手机的来源 IP 与它请求过的路径

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server src/components/settings-dialog.tsx
git commit -m "feat(server): 只读模式 + 已连接来源(内存环形缓冲)"
```
```powershell
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui
git commit -m "feat(android): 只读模式下的界面收敛与提示"
```

---

### Task 4: 发布（固定签名 + CI 出 APK）

**Files:**
- Create: `android/app/src/main/res/values/ic_launcher_background.xml`、`android/app/src/main/res/drawable/ic_launcher_foreground.xml`、`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `android/app/build.gradle.kts`、`android/app/src/main/AndroidManifest.xml`、`.github/workflows/build.yml`、`android/.gitignore`

- [ ] **Step 0: 补应用图标（M1′ Task 1 刻意留空的那一项）**

M1′ 的 manifest **故意没有声明 `android:icon`**——当时项目里没有任何 mipmap 资源，而声明一个不存在的 `@mipmap` 会让资源链接失败、`assembleDebug` 直接报错。发版前必须补上：

1. 用 **XML 自适应图标**，不要引入二进制图片（保持仓库轻，也便于在纯文本里评审）：
   - `android/app/src/main/res/values/ic_launcher_background.xml`：**必须包在 `<resources>` 根元素里**（`res/values` 下的文件都要有它，漏掉会编译失败）：
     ```xml
     <?xml version="1.0" encoding="utf-8"?>
     <!-- Copyright (c) 2026 XUTENGXIANG / SPDX-License-Identifier: MIT -->
     <resources>
         <color name="ic_launcher_background">#101014</color>
     </resources>
     ```
   - `android/app/src/main/res/drawable/ic_launcher_foreground.xml`：一个简单矢量（`viewportWidth/Height="108"`）
   - `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：
     ```xml
     <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
         <background android:drawable="@color/ic_launcher_background" />
         <foreground android:drawable="@drawable/ic_launcher_foreground" />
     </adaptive-icon>
     ```
2. 在 manifest 的 `<application>` 上加回 `android:icon="@mipmap/ic_launcher"`，并**删掉那句"刻意不声明"的注释**（它已经完成使命了）。
3. 验证：`assembleRelease` 成功 + 装到手机上，启动器里能看到图标（不是系统默认的灰块）。

- [ ] **Step 1: 生成固定签名 keystore（一次，永久保留）**

```powershell
$ks = "$env:USERPROFILE\.imagefilter-mobile\release.keystore"
New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
& "$env:LOCALAPPDATA\Programs\jdk-21\jdk-21.0.12.1+1\bin\keytool.exe" -genkeypair `
  -keystore $ks -alias imagefilter -keyalg RSA -keysize 4096 -validity 10000 `
  -storetype PKCS12 -dname "CN=ImageFilter Mobile, O=XUTENGXIANG, C=CN"
```

> **这把 keystore 是永久资产**：丢了就再也无法覆盖安装更新（只能卸载重装）。**必须备份到仓库之外**（密码管理器/离线介质）。`android/.gitignore` 里已经有 `*.keystore` / `*.jks` 的忽略（若没有，补上）——**绝不能进 git**。

- [ ] **Step 2: Gradle 读取签名（从环境变量，缺失时退回 debug 签名并给出警告）**

```kotlin
// 签名从环境变量读 —— 密钥永远不进仓库。
// 缺失时**不报错**, 而是退回 debug 签名并打印一行警告: 这样贡献者 clone 下来就能
// 跑 assembleRelease 来看产物, 而不必先弄一把 keystore; CI 上则由 secret 提供。
val ksPath = System.getenv("IMAGEFILTER_KEYSTORE")
val ksPass = System.getenv("IMAGEFILTER_KEYSTORE_PASSWORD")
val ksAlias = System.getenv("IMAGEFILTER_KEY_ALIAS")
val ksKeyPass = System.getenv("IMAGEFILTER_KEY_PASSWORD")

signingConfigs {
    if (ksPath != null && ksPass != null && ksAlias != null && ksKeyPass != null) {
        create("release") {
            storeFile = file(ksPath); storePassword = ksPass
            keyAlias = ksAlias; keyPassword = ksKeyPass
        }
    }
}
buildTypes {
    release {
        signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        // ...
    }
}
```

- [ ] **Step 3: CI 出 APK**

在 `.github/workflows/build.yml` 里**新增一个 job**（不动现有的 Windows/macOS 桌面端 job）：

```yaml
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: android-actions/setup-android@v3
      - name: Build APK
        working-directory: android
        env:
          IMAGEFILTER_KEYSTORE: ${{ github.workspace }}/release.keystore
          IMAGEFILTER_KEYSTORE_PASSWORD: ${{ secrets.IMAGEFILTER_KEYSTORE_PASSWORD }}
          IMAGEFILTER_KEY_ALIAS: ${{ secrets.IMAGEFILTER_KEY_ALIAS }}
          IMAGEFILTER_KEY_PASSWORD: ${{ secrets.IMAGEFILTER_KEY_PASSWORD }}
        run: |
          # keystore 从 secret(base64) 还原 —— 绝不存在仓库里
          echo "${{ secrets.IMAGEFILTER_KEYSTORE_BASE64 }}" | base64 -d > release.keystore
          ./gradlew --no-daemon assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: imagefilter-mobile-apk
          path: android/app/build/outputs/apk/release/*.apk
```

- [ ] **Step 4: 版本号**

首个 APK：`versionName = "1.1.2"`、`versionCode = 1`（与桌面端同版本号，便于对照）。
后续发版：桌面端 4 处（交接文档 §9.2）+ 安卓 `build.gradle.kts` 的 `versionName`/`versionCode` = **5 处手工同步**。"改成单一来源生成"是独立小步，**不在本计划**。

- [ ] **Step 5: 端到端发布验证**

1. 本地 `assembleRelease` 出 APK → 装到手机上 → 能覆盖安装上一次的（**签名一致性的实证**）
2. 推 tag → CI 跑起来 → artifact 里能下到 APK → 装到手机验证
3. 故意把 `versionCode` 改回 1 再装 → **应当安装失败**（Android 拒绝降级）——这反过来证明签名与版本号都在生效

- [ ] **Step 6: 落证据并提交**

`docs/evidence/android-release/REPORT.txt`：keystore 指纹（`keytool -list` 的 SHA-256，**只记指纹，不记密码**）、APK 大小、覆盖安装结果。

```powershell
cd A:\tenent
git add android/app/build.gradle.kts android/.gitignore .github/workflows/build.yml `
        docs/evidence/android-release/REPORT.txt
git commit -m "chore(android): 固定签名与 CI 出 APK(签名走 secret, 首次发版手工同步 5 处版本号)"
```

---

## 附录 A · 规格覆盖对照（M4′ 部分）

| 规格条目 | 由哪个任务实现 |
|---|---|
| §4.2 L1 打通引导 + **自动探测**本机已装工具 | Task 2 |
| §4.2 私有地址枚举 + 防火墙引导（LAN-only） | Task 2 |
| §4.3 不提供公网暴露模式；`Public` 类别不自动启动 | Task 2 |
| §1.3 四条排查清单 | Task 2 |
| §5.1 `GET /clients`（已连接来源） | Task 3 |
| §11 权衡里的"只读模式" | Task 3 |
| §13 交付：CI 出 APK、**签名必须固定** | Task 4 |
| §13 版本号 5 处手工同步 | Task 4 |
| 二维码配对（规格 §8.3 把它列为打磨项） | Task 1 |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| 手机端显示二维码给电脑扫 | 电脑侧没有摄像头；方向反了 |
| 短配对码（6–8 位）代替二维码 | 需要服务端加"限时配对会话"，是新的状态机；二维码用熟练的库更省事 |
| 公网暴露 / 一键端口转发 | 规格 §4.3 明确不做；检测到公网 IPv4 时警告，且 **`Public` 网络类别下不自动启动** |
| 自建中继 | 规格决策 #4：连 Immich 都不做 |
| 单一来源版本号生成 | 独立小步；本计划只做"手工同步 5 处"并把清单写进文档 |
| 把 `GET /events`（SSE）做到手机端 | 手机端刷新靠"重进文件夹"已够用（M2′ 验收过）；等有真实需求再加 |
