# ImageFilter Mobile · M1′ 安卓骨架 / 配对 / 浏览 / 网格 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零把安卓工程立起来（含免管理员工具链），做出"配对 → 选共享根 → 浏览目录 → 看到缩略图网格"这条最小闭环，并让它能在真机上跑起来。

**Architecture:** 原生 Kotlin + Jetpack Compose 的**瘦客户端**——没有 Rust、没有 NDK、不引 Room。网络层 OkHttp + kotlinx.serialization；图片层 Coil 3（共用同一个带鉴权头的 OkHttpClient）。所有 DTO 与服务端契约一一对应，**服务端的错误码闭集原样透传**，不新造。

**Tech Stack:** JDK 21 (Temurin) · Android cmdline-tools + platform-tools + platforms;android-35 + build-tools;35.0.0 · Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · OkHttp 4.12.0 · kotlinx.serialization 1.7.3 · Coil 3.0.4 · JUnit4 + MockWebServer

## Global Constraints

- **工具链装在用户目录，不碰系统目录、不需要管理员**：JDK → `%LOCALAPPDATA%\Programs\jdk-21`，Android SDK → `%LOCALAPPDATA%\Android\Sdk`，Gradle → `%LOCALAPPDATA%\Programs\gradle-8.11.1`。
- **所有下载必须有官方校验值**（见 Task 0 的 URL/校验表）。校验不过就删档换来源，**绝不解压**未经校验的包。
- **本仓库的铁律照旧**：源码文件头两行 `// Copyright (c) 2026 XUTENGXIANG` + `// SPDX-License-Identifier: MIT`（Kotlin 用 `//`，XML 用 `<!-- -->`）；注释与提交信息用**中文**；conventional commits；只 `git add` 明确路径。
- **桌面端一行都不许改**。本计划只新增 `android/` 与 `docs/`。
- **minSdk = 26 / targetSdk = 35 / compileSdk = 35**。理由：硬件位图要 26，AndroidKeyStore 的 AES-GCM 要 23，Coil 3 要 21；26 在 2026 年覆盖绝大多数在用机型。
- **不引 `androidx.security.crypto`**（`EncryptedSharedPreferences` 已不再推荐）——凭据用 **AndroidKeyStore + AES/GCM** 自己包一层。
- **DTO 字段名必须与服务端契约逐字一致**（规格 §5.1 与 `src/types.ts`）。服务端错误码闭集：`unauthorized | forbiddenPath | notFound | conflict | busy | diskFull | tooLarge | notXmp | unsupportedForm | badRequest | internal`——**原样映射，不新造**。
- **版本兼容以首次 `assembleDebug` 的实际报错为准**：本计划钉的版本组合是已知可用的一组，但 Android 工具链版本耦合很紧；若编译器/AGP 报版本不兼容，按它给的提示调整，**验收标准是"构建通过 + JVM 单测通过"，不是照抄版本号**。
- 规格出处：[2026-10-06-imagefilter-android-design.md](../specs/2026-10-06-imagefilter-android-design.md) §5 / §6 / §7 / §8 / §10.2 / §12。

---

## 范围说明：本计划 = Task 0（工具链）+ M1′

| | 内容 | 验收方式 |
|---|---|---|
| **Task 0** | 免管理员安装 JDK 21 + Android SDK + Gradle，全部官方校验 | `sdkmanager --list_installed`、`gradle -v`、`adb version` 三条命令有输出 |
| **M1′**（Task 1–5） | Gradle 骨架 → 数据层 → 配对 → 目录树 → 缩略图网格 | JVM 单测全绿 + `assembleDebug` 出 APK + **真机连上电脑看到网格** |

后续计划（另出）：**M2′** 查看器 + 星级/色标回写；**M3′** 滑卡 + 移动作业 + 待重试；**M4′** 二维码配对 / 打通引导 / 发布配置。

**依赖**：M1′ 需要 M0′ 的桌面端服务已经能跑（`/api/v1/ping` 与 `/api/v1/scan` 可用）。M0′ 的 Task 1–5 完成前，Task 4/5 的真机验收做不了——但那之前的 Task 0–3 全是本机可验证的，不受阻。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `android/settings.gradle.kts` | 仓库源（**含阿里云镜像**，见 Task 1）+ 模块声明 |
| `android/build.gradle.kts` | 根构建脚本（插件别名声明） |
| `android/gradle.properties` | JVM 参数、AndroidX 开关、**`android.nonTransitiveRClass`** |
| `android/gradle/libs.versions.toml` | 版本目录（**唯一的版本来源**） |
| `android/app/build.gradle.kts` | 应用模块（compileSdk/minSdk/Compose/依赖/签名占位） |
| `android/app/src/main/AndroidManifest.xml` | 权限（**只 INTERNET**）、`allowBackup=false`、`usesCleartextTraffic`（见 Task 1 说明） |
| `android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt` | 唯一 Activity，挂 Compose |
| `android/app/src/main/kotlin/com/imagefilter/mobile/ui/theme/Theme.kt` | 主题（少量色值，**不移植桌面端 index.css**） |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/Dto.kt` | 与服务端逐字对应的 DTO |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/ApiError.kt` | 错误码闭集 + 映射 |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/ApiClient.kt` | OkHttp + Bearer + 超时 + 错误映射 |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/Http.kt` | 共享的 `OkHttpClient`（鉴权拦截器只在这里装一次） |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/SecretStore.kt` | AndroidKeyStore(AES/GCM) 存 token |
| `android/app/src/main/kotlin/com/imagefilter/mobile/data/PairingStore.kt` | DataStore 存 url/shares 缓存 + token 委托给 SecretStore |
| `android/app/src/main/kotlin/com/imagefilter/mobile/ui/PairingScreen.kt` | 配对向导 |
| `android/app/src/main/kotlin/com/imagefilter/mobile/ui/BrowseScreen.kt` | 共享根列表 + 目录树抽屉 |
| `android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt` | `LazyVerticalGrid` 缩略图网格 |
| `android/app/src/test/kotlin/com/imagefilter/mobile/ApiClientTest.kt` | MockWebServer 契约测试（Task 2） |
| `android/app/src/test/kotlin/com/imagefilter/mobile/DtoTest.kt` | DTO 解析 + 未知字段容错（Task 2） |
| `android/app/src/test/kotlin/com/imagefilter/mobile/ApiErrorTest.kt` | 错误码映射（Task 2） |

---

### Task 0: 免管理员工具链（JDK 21 + Android SDK + Gradle）

**为什么单独成任务**：后面每一步都依赖它，而它是本计划唯一会碰"系统环境"的地方。全部装进用户目录、全部校验官方哈希。

> **本任务没有单测，这是有意的**：它不产出代码，产出的是"可用的工具链 + 一份实测证据"。验收线是 Step 6 的三条命令有正确输出、Step 7 的证据文件入库。评审时请按"环境任务"评判，**不要以"缺少测试"判缺陷**——那会逼出为环境步骤写假测试的坏结果。

**Files:**
- Create: `docs/evidence/android-toolchain/REPORT.txt`（记录实际版本与路径）

**Interfaces:**
- Produces: `JAVA_HOME`、`ANDROID_HOME`、`GRADLE_HOME`（用户级环境变量）；`adb` / `sdkmanager` / `gradle` 在 PATH 上

- [ ] **Step 1: 下载并校验 JDK 21（Temurin）**

```powershell
#Requires -Version 7
```
（若在 Windows PowerShell 5.1 下运行，先用 PS7 全路径执行；本任务所有脚本都按 PS7 写。）

```powershell
$ProgressPreference = 'SilentlyContinue'
$dst = "$env:LOCALAPPDATA\Downloads\android-toolchain"
New-Item -ItemType Directory -Force -Path $dst | Out-Null

# 从官方 release 元数据里取"最新 JDK21 zip 资产名"与其 sha256 —— 不硬编码版本号
$rel = Invoke-RestMethod 'https://api.github.com/repos/adoptium/temurin21-binaries/releases/latest'
$zipAsset = $rel.assets | Where-Object { $_.name -match '^OpenJDK21U-jdk_x64_windows_hotspot_.*\.zip$' } | Select-Object -First 1
$shaAsset = $rel.assets | Where-Object { $_.name -eq ($zipAsset.name + '.sha256.txt') } | Select-Object -First 1
Write-Output "JDK 资产: $($zipAsset.name)  ($([math]::Round($zipAsset.size/1MB,1)) MB)"

$zipPath = Join-Path $dst $zipAsset.name
# 国内镜像加速 GitHub 大文件(与 PS7 安装同一套); 校验不过就换来源
$mirror = 'https://ghfast.top/'
& curl.exe -L --fail --silent --show-error --max-time 900 -o $zipPath "$mirror$($zipAsset.browser_download_url)"
if ($LASTEXITCODE -ne 0) { throw "下载失败" }

$shaPath = Join-Path $dst $shaAsset.name
& curl.exe -L --fail --silent --show-error -o $shaPath $shaAsset.browser_download_url
$expect = ([System.IO.File]::ReadAllText($shaPath) -split '\s+')[0].Trim().ToLower()
$actual = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()
Write-Output "期望 sha256 = $expect"
Write-Output "实得 sha256 = $actual"
if ($expect -ne $actual) { Remove-Item $zipPath -Force; throw "SHA256 校验失败 —— 已删档并拒绝该来源" }
Write-Output "✅ JDK 校验通过"
```

- [ ] **Step 2: 解压 JDK 到用户目录并设 `JAVA_HOME`**

```powershell
$jdkDest = "$env:LOCALAPPDATA\Programs\jdk-21"
New-Item -ItemType Directory -Force -Path $jdkDest | Out-Null
& tar.exe -xf $zipPath -C $jdkDest       # zip 内有一层 jdk-21.0.x+y 目录
$inner = Get-ChildItem $jdkDest -Directory | Select-Object -First 1
Write-Output "JDK 根: $($inner.FullName)"
& (Join-Path $inner.FullName 'bin\java.exe') -version

[Environment]::SetEnvironmentVariable('JAVA_HOME', $inner.FullName, 'User')
```

- [ ] **Step 3: 下载并校验 Android cmdline-tools**

校验值取自 **`dl.google.com` 的官方机器可读清单** `repository2-3.xml`（HTTPS），**不是**从镜像取：

```powershell
$xml = (Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-3.xml' -UseBasicParsing -TimeoutSec 60).Content
if ($xml -is [byte[]]) { $xml = [Text.Encoding]::UTF8.GetString($xml) }

# 取 cmdline-tools 里 windows 那份的 url / size / sha1
$m = [regex]::Match($xml, '(?s)<url>(commandlinetools-win-\d+_latest\.zip)</url>\s*</complete>\s*<host-os>windows</host-os>')
if (-not $m.Success) { throw "在官方清单里没找到 windows 版 cmdline-tools" }
$file = $m.Groups[1].Value
# 同一条 <complete> 里往前找 checksum 与 size
$win = $xml.Substring([Math]::Max(0, $m.Index - 600), 700)
$sha1 = ([regex]::Match($win, '<checksum type="sha1">([0-9a-f]{40})</checksum>')).Groups[1].Value
$size = ([regex]::Match($win, '<size>(\d+)</size>')).Groups[1].Value
Write-Output "cmdline-tools: $file  size=$size  sha1=$sha1"

$zip = Join-Path $dst $file
& curl.exe -L --fail --silent --show-error --max-time 900 -o $zip "https://dl.google.com/android/repository/$file"
if ((Get-Item $zip).Length -ne [long]$size) { throw "大小不符, 期望 $size" }
$got = (Get-FileHash $zip -Algorithm SHA1).Hash.ToLower()
if ($got -ne $sha1) { Remove-Item $zip -Force; throw "SHA1 校验失败 —— 已删档" }
Write-Output "✅ cmdline-tools 校验通过"
```

> **为什么 SHA-1 也可以**：Google 对 SDK 归档只发布 SHA-1。URL 与校验值都来自 `dl.google.com` 的 HTTPS 官方清单，要伪造必须构造 SHA-1 碰撞——不可行。**这不是"降级接受"，而是官方唯一可选项**；如果哪天清单里出现 `type="sha256"`，优先用它。

- [ ] **Step 4: 解压 cmdline-tools，装 SDK 包**

cmdline-tools 的 zip 解压后是 `cmdline-tools/` 一层，`sdkmanager` **要求它位于 `<sdk>/cmdline-tools/latest/`**：

```powershell
$sdk    = "$env:LOCALAPPDATA\Android\Sdk"
$ctDest = Join-Path $sdk 'cmdline-tools\latest'
New-Item -ItemType Directory -Force -Path $ctDest | Out-Null
& tar.exe -xf (Join-Path $dst $file) -C (Join-Path $sdk 'cmdline-tools')
# 若解压出的是 cmdline-tools/bin 而目标要求 latest/bin, 把内容搬进 latest
if (-not (Test-Path (Join-Path $ctDest 'bin\sdkmanager.bat'))) {
    $srcDir = Get-ChildItem (Join-Path $sdk 'cmdline-tools') -Directory | Where-Object { $_.Name -ne 'latest' } | Select-Object -First 1
    if ($srcDir) { Get-ChildItem $srcDir.FullName | Move-Item -Destination $ctDest -Force }
}

$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')
$sdkmanager = Join-Path $ctDest 'bin\sdkmanager.bat'
& $sdkmanager --sdk_root=$sdk --version

# 只装必要的四个包(不装 emulator 与系统镜像 —— 真机验收, 省 1.5GB)
& $sdkmanager --sdk_root=$sdk 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
# 接受许可(逐条 y)
'y' * 20 -split '' | Out-Null
1..20 | ForEach-Object { 'y' } | & $sdkmanager --sdk_root=$sdk --licenses
```

> **若 `dl.google.com` 太慢**：按你所用镜像站**自己给的用法**配置（常见做法是给 `sdkmanager` 传 `--no_https --proxy=http --proxy_host=<镜像> --proxy_port=80`）。**别照抄我这里的参数**——镜像的用法会变，以镜像站当期说明为准。校验仍然走 Step 3 的官方清单。

- [ ] **Step 5: 装 Gradle 并生成 wrapper**

Gradle 没在工具链里（cmdline-tools 不含它），单独装：

```powershell
$gv = '8.11.1'
$gzip = Join-Path $dst "gradle-$gv-bin.zip"
& curl.exe -L --fail --silent --show-error --max-time 900 -o $gzip "https://services.gradle.org/distributions/gradle-$gv-bin.zip"
$gsha = (Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gv-bin.zip.sha256" -UseBasicParsing -TimeoutSec 60).Content
if ($gsha -is [byte[]]) { $gsha = [Text.Encoding]::UTF8.GetString($gsha) }
$expect = $gsha.Trim().Split(' ')[0].ToLower()
$actual = (Get-FileHash $gzip -Algorithm SHA256).Hash.ToLower()
if ($expect -ne $actual) { Remove-Item $gzip -Force; throw "Gradle SHA256 校验失败" }

$gdest = "$env:LOCALAPPDATA\Programs\gradle-$gv"
New-Item -ItemType Directory -Force -Path $gdest | Out-Null
& tar.exe -xf $gzip -C $gdest
$gradleHome = (Get-ChildItem $gdest -Directory | Select-Object -First 1).FullName
& (Join-Path $gradleHome 'bin\gradle.bat') -v
[Environment]::SetEnvironmentVariable('GRADLE_HOME', $gradleHome, 'User')
```

- [ ] **Step 6: 写用户级环境变量并验证**

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$gradleHome = [Environment]::GetEnvironmentVariable('GRADLE_HOME','User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')

$add = @("$sdk\platform-tools", "$sdk\cmdline-tools\latest\bin", "$gradleHome\bin")
$cur = [Environment]::GetEnvironmentVariable('Path','User')
foreach ($p in $add) { if ($cur -notlike "*$p*") { $cur = $cur.TrimEnd(';') + ';' + $p } }
[Environment]::SetEnvironmentVariable('Path', $cur, 'User')
Write-Output "已写入 PATH: $($add -join ' | ')"
```

**新开一个终端**（环境变量不会进已运行的进程）后验证三条：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" --list_installed
& "$env:LOCALAPPDATA\Programs\gradle-8.11.1\gradle-8.11.1\bin\gradle.bat" -v
```

Expected: `adb` 报版本；`sdkmanager --list_installed` 列出 platform-tools / platforms;android-35 / build-tools;35.0.0；`gradle -v` 显示 **Gradle 8.11.1 + JVM 21**（若显示 JVM 25，说明 `JAVA_HOME` 没生效——那会让 AGP 失败，必须解决）。

- [ ] **Step 7: 落证据并提交**

把三条命令的真实输出写进 `docs/evidence/android-toolchain/REPORT.txt`，顶部三行结论（实际版本 / 安装路径 / 是否用了镜像）。

```powershell
cd A:\tenent
git add docs/evidence/android-toolchain/REPORT.txt
git commit -m "chore(android): 记录免管理员工具链实测结果(JDK21/SDK/Gradle 官方校验通过)"
```

> 注意 `.gitignore`：**不要**把 `android/` 下的 `local.properties`、`.gradle/`、`build/` 提交。Task 1 会补 `.gitignore`。

---

### Task 1: Gradle 工程骨架（能出 APK、能跑 JVM 单测）

**为什么先做**：把构建立起来之前写任何 Kotlin 都无法验证。这一步的验收是"`assembleDebug` 出 APK + 一个平凡 JVM 单测通过"，与业务无关。

**Files:**
- Create: `android/settings.gradle.kts`、`android/build.gradle.kts`、`android/gradle.properties`、`android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`、`android/app/src/main/AndroidManifest.xml`、`android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt`、`android/app/src/main/kotlin/com/imagefilter/mobile/ui/theme/Theme.kt`、`android/app/src/test/kotlin/com/imagefilter/mobile/SmokeTest.kt`、`android/.gitignore`
- Modify: 仓库根 `.gitignore`（追加 `android/` 的忽略项）

**Interfaces:**
- Produces: `./gradlew assembleDebug`、`./gradlew testDebugUnitTest`；`applicationId = "com.imagefilter.mobile"`

- [ ] **Step 1: 建目录与版本目录**

创建 `android/gradle/libs.versions.toml`：

```toml
# 版本**唯一来源**。改版本只改这里。
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
composeBom = "2024.12.01"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
okhttp = "4.12.0"
serialization = "1.7.3"
coroutines = "1.9.0"
coil = "3.0.4"
datastore = "1.1.1"
junit = "4.13.2"
mockwebserver = "4.12.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: 仓库源走国内镜像（构建可靠性的关键）**

创建 `android/settings.gradle.kts`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
pluginManagement {
    repositories {
        // 国内镜像优先: dl.google.com / repo1.maven.org 在境内不稳定, 会把构建卡在下载上。
        // 顺序刻意如此 —— 镜像命中就完全不碰境外源; 缺包时自动回落到官方源。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ImageFilterMobile"
include(":app")
```

- [ ] **Step 3: 根构建脚本与 gradle.properties**

`android/build.gradle.kts`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`android/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 4: 应用模块**

`android/app/build.gradle.kts`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.imagefilter.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imagefilter.mobile"
        // minSdk 26: 硬件位图(Bitmap.Config.HARDWARE)要 26; 我们靠它保证网格滚动的内存表现。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 首次发版先不开混淆: 出问题时的栈更好读
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 5: 清单与最小 Activity**

`android/app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Copyright (c) 2026 XUTENGXIANG / SPDX-License-Identifier: MIT -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 只要网络。**不要加存储权限** —— 本 App 的所有照片都在电脑上, 手机不落原始文件。 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- 用于"当前是否在 WiFi 下"的判断(决定要不要预取预览图), 供 M3′ 使用 -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="ImageFilter"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.ImageFilterMobile">
        <!-- usesCleartextTraffic=true 的理由: 服务端故意不做 TLS(规格 §4.3),
             加密由 EasyTier/Tailscale 隧道或局域网边界提供。**这是有意为之, 不是疏忽**;
             等 M4′ 做"打通引导"时会在 UI 上把风险讲清楚。 -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/themes.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Copyright (c) 2026 XUTENGXIANG / SPDX-License-Identifier: MIT -->
<resources>
    <style name="Theme.ImageFilterMobile" parent="android:Theme.Material.NoActionBar" />
</resources>
```

`android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("ImageFilter") }
            }
        }
    }
}
```

`android/app/src/test/kotlin/com/imagefilter/mobile/SmokeTest.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** 骨架冒烟: 证明 JVM 单测链路是通的(不需要设备)。 */
class SmokeTest {
    @Test
    fun toolchain_runs_jvm_tests() {
        assertEquals(4, 2 + 2)
    }
}
```

`android/.gitignore`：

```gitignore
.gradle/
build/
local.properties
*.iml
.idea/
.cxx/
captures/
```

并在**仓库根** `.gitignore` 追加：

```gitignore
# 安卓工程(构建产物与本地配置不入库)
android/.gradle/
android/build/
android/app/build/
android/local.properties
```

- [ ] **Step 6: 生成 wrapper 并构建**

```powershell
cd A:\tenent\android
$gradle = "$env:LOCALAPPDATA\Programs\gradle-8.11.1\gradle-8.11.1\bin\gradle.bat"
& $gradle wrapper --gradle-version 8.11.1 --distribution-type bin
# wrapper 生成后一律用 gradlew, 保证 CI 与人用的是同一个 Gradle 版本
.\gradlew.bat --no-daemon testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
```

Expected:
- `testDebugUnitTest`：`SmokeTest` 1 个测试通过
- `assembleDebug`：产出 `android/app/build/outputs/apk/debug/app-debug.apk`
- **无版本不兼容报错**。若报 AGP/Gradle/Kotlin 版本不匹配，按报错提示调整 `libs.versions.toml`，**调完必须重新跑通这两条命令**。

- [ ] **Step 7: 真机装上（第一次接触真机）**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices          # 手机需开"开发者选项 → USB 调试", 并在手机上点"允许"
& $adb install -r A:\tenent\android\app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`，手机上出现名为 **ImageFilter** 的应用，打开显示 "ImageFilter"。

- [ ] **Step 8: Commit**

```powershell
cd A:\tenent
git add android .gitignore
git commit -m "feat(android): Gradle 骨架(Kotlin 2.0 + Compose + 阿里云镜像源), 可出 debug APK 并跑 JVM 单测"
```

---

### Task 2: 数据层 —— DTO + ApiClient + 错误码映射

**为什么要 TDD**：这是整个手机端与桌面端之间**唯一的契约层**。它错了，后面所有界面都会错，而且错得很难查（"图不显示"可能是 DTO 字段名写错）。

**Files:**
- Create: `android/app/src/main/kotlin/com/imagefilter/mobile/data/Dto.kt`、`ApiError.kt`、`ApiClient.kt`、`Http.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/DtoTest.kt`、`ApiClientTest.kt`、`ApiErrorTest.kt`

**Interfaces:**
- Consumes: 服务端契约（规格 §5.1/§5.3）
- Produces:
  - `@Serializable data class PingDto(val name: String, val version: String, val requiresToken: Boolean)`
  - `@Serializable data class SharesDto(val shares: List<String>)`
  - `@Serializable data class DriveDto(val mountPoint: String, val driveType: String, val label: String, val available: Boolean)`
  - `@Serializable data class FolderEntryDto(val path: String, val name: String, val photoCount: Int, val hasSubdirs: Boolean, val subfolders: List<FolderEntryDto>)`
  - `@Serializable data class PhotoExifDto(...)`（字段与 `src/types.ts` 的 `PhotoExif` 一致，全部可空）
  - `@Serializable data class ScannedPhotoDto(val path: String, val fileName: String, val fileSize: Long, val isRaw: Boolean, val isVideo: Boolean, val modifiedAt: Long, val exif: PhotoExifDto)`
  - `@Serializable data class DecisionDto(val path: String, val rating: Int? = null, val label: String? = null, val clearLabel: Boolean = false)`
  - `@Serializable data class DecisionReadDto(val path: String, val rating: Int?, val label: String?)`
  - `sealed class ApiException(val code: String, val httpStatus: Int, message: String) : Exception(message)`
  - `ApiError.fromHttp(status: Int, bodyCode: String?): ApiException`
  - `class ApiClient(baseUrl: String, token: String, client: OkHttpClient)`，方法 `ping()` / `shares()` / `drives()` / `browse(path)` / `scan(path)`

- [ ] **Step 1: 先写会失败的测试**

`DtoTest.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.ScannedPhotoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoTest {
    @Test
    fun parses_scanned_photo_exactly_as_the_desktop_sends_it() {
        // 这段 JSON 逐字抄自桌面端 scan_directory 的序列化结果(serde rename_all = "camelCase")
        val json = """
        {
          "path": "D:\\照片\\a.jpg",
          "fileName": "a.jpg",
          "fileSize": 123456,
          "isRaw": false,
          "isVideo": false,
          "modifiedAt": 1759680000000,
          "exif": { "fileSize": 123456, "cameraModel": "ILCE-7M4", "iso": 400 }
        }
        """.trimIndent()

        val p = Json.decodeFromString<ScannedPhotoDto>(json)
        assertEquals("a.jpg", p.fileName)
        assertEquals(1759680000000L, p.modifiedAt)
        assertEquals("ILCE-7M4", p.exif.cameraModel)
        assertEquals(400, p.exif.iso)
        assertNull(p.exif.lensModel)
    }

    @Test
    fun tolerates_unknown_fields_and_missing_optional_exif() {
        // 服务端以后加字段不该让老客户端崩 —— ignoreUnknownKeys 是刻意的
        val json = """{"path":"p","fileName":"f","fileSize":1,"isRaw":true,"isVideo":false,"modifiedAt":0,"exif":{"fileSize":1},"futureField":42}"""
        val p = Json.decodeFromString<ScannedPhotoDto>(json)
        assertEquals("f", p.fileName)
        assertNull(p.exif.iso)
    }
}
```

`ApiErrorTest.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorTest {
    @Test
    fun maps_every_closed_set_code_the_server_can_send() {
        // 这份清单与规格 §5.3 及 server/mod.rs 的 ApiError::code() 一一对应。
        // 少一个就会让手机端把可处理的错误当成未知错误。
        val expected = mapOf(
            401 to "unauthorized", 403 to "forbiddenPath", 404 to "notFound",
            409 to "conflict", 423 to "busy", 507 to "diskFull", 413 to "tooLarge",
            422 to "notXmp", 400 to "badRequest", 500 to "internal",
        )
        for ((status, code) in expected) {
            assertEquals(code, ApiError.fromHttp(status, null).code)
        }
    }

    @Test
    fun prefers_the_body_code_over_the_http_status() {
        // 服务端 422 既可能是 notXmp 也可能是 unsupportedForm —— 以 body 里的 code 为准
        assertEquals("unsupportedForm", ApiError.fromHttp(422, "unsupportedForm").code)
        assertEquals("notXmp", ApiError.fromHttp(422, "notXmp").code)
    }

    @Test
    fun unknown_status_becomes_internal_not_a_crash() {
        assertEquals("internal", ApiError.fromHttp(418, null).code)
    }
}
```

`ApiClientTest.kt`（MockWebServer，**不需要设备**）：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = ApiClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "t".repeat(64),
        client = OkHttpClient(),
    )

    @Test
    fun sends_bearer_token_on_protected_endpoints() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":["D:\\照片"]}"""))
        val shares = client().shares()
        assertEquals(listOf("D:\\照片"), shares)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/shares", req.path)
        // token 必须以 Bearer 形式发出 —— 这是服务端鉴权的唯一凭据
        assertEquals("Bearer " + "t".repeat(64), req.getHeader("Authorization"))
    }

    @Test
    fun percent_encodes_windows_paths_in_query() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client().scan("D:\\照片 2026\\a")
        val req = server.takeRequest()
        // 反斜杠必须被编码; 未编码会让服务端解析出错误路径
        assertTrue("实际 path=${req.path}", req.path!!.contains("%5C"))
    }

    @Test
    fun maps_403_body_code_to_forbiddenPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))
        val e = runCatching { client().scan("C:\\Windows") }.exceptionOrNull()
        assertTrue("实际=$e", e is ApiException)
        assertEquals("forbiddenPath", (e as ApiException).code)
    }

    @Test
    fun maps_401_without_body_to_unauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = runCatching { client().shares() }.exceptionOrNull()
        assertEquals("unauthorized", (e as ApiException).code)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon testDebugUnitTest
```

Expected: 编译失败（`data.Json` / `ScannedPhotoDto` / `ApiClient` 都不存在）。

- [ ] **Step 3: 实现 DTO 与 Json**

创建 `data/Dto.kt`（字段名**逐字**对应服务端；`ignoreUnknownKeys = true` 让服务端加字段不会炸老客户端）：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KxJson

/**
 * 与桌面端 serde(rename_all = "camelCase") 的输出逐字对应。
 *
 * `ignoreUnknownKeys = true` 是**刻意**的: 服务端以后加字段时, 已经装在手机上的
 * 老客户端不该因为多了个键就崩。反过来(服务端删字段)会走默认值, 所以我们给所有
 * 非必需字段都留了默认值。
 */
val Json = KxJson {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class PingDto(val name: String, val version: String, val requiresToken: Boolean = true)

@Serializable
data class SharesDto(val shares: List<String> = emptyList())

@Serializable
data class DriveDto(
    val mountPoint: String = "",
    val driveType: String = "",
    val label: String = "",
    val available: Boolean = false,
)

@Serializable
data class FolderEntryDto(
    val path: String = "",
    val name: String = "",
    val photoCount: Int = 0,
    val hasSubdirs: Boolean = false,
    val subfolders: List<FolderEntryDto> = emptyList(),
)

/** 对应 src/types.ts 的 PhotoExif —— 全部可空, 因为桌面端只有在能读到时才填。 */
@Serializable
data class PhotoExifDto(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: Int? = null,
    val dateTaken: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fileSize: Long = 0,
)

@Serializable
data class ScannedPhotoDto(
    val path: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val isRaw: Boolean = false,
    val isVideo: Boolean = false,
    val modifiedAt: Long = 0,
    val exif: PhotoExifDto = PhotoExifDto(),
)

/** PUT /api/v1/decisions 的单项。null = **不动**这个属性(不是清空) —— 与桌面端 DecisionWrite 同义。 */
@Serializable
data class DecisionDto(
    val path: String,
    val rating: Int? = null,
    val label: String? = null,
    val clearLabel: Boolean = false,
)

@Serializable
data class DecisionReadDto(val path: String = "", val rating: Int? = null, val label: String? = null)
```

- [ ] **Step 4: 实现错误码映射**

创建 `data/ApiError.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.serialization.Serializable

/** 服务端错误体: `{"code":"..."}`（规格 §5.3）。 */
@Serializable
internal data class ErrorBody(val code: String? = null)

class ApiException(val code: String, val httpStatus: Int, message: String) : Exception(message)

/**
 * 闭集错误码 —— 与 `server/src-tauri` 的 `ApiError::code()` 一一对应。
 * **不新造码**: 手机端只需认这一套。
 */
object ApiError {
    fun fromHttp(status: Int, bodyCode: String?): ApiException {
        // body 里的 code 更精确(422 既可能是 notXmp 也可能是 unsupportedForm), 优先用它
        val code = bodyCode ?: when (status) {
            401 -> "unauthorized"
            403 -> "forbiddenPath"
            404 -> "notFound"
            409 -> "conflict"
            423 -> "busy"
            507 -> "diskFull"
            413 -> "tooLarge"
            422 -> "notXmp"
            400 -> "badRequest"
            else -> "internal"   // 未列出的状态一律归 internal —— 宁可说"出错了"也不要崩
        }
        return ApiException(code, status, "HTTP $status ($code)")
    }
}
```

- [ ] **Step 5: 实现共享 OkHttp 与 ApiClient**

创建 `data/Http.kt`（**鉴权拦截器只在这里装一次**，Coil 也复用它）：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的 OkHttpClient。
 *
 * 为什么只有一个: Coil 与 ApiClient 必须共用它 —— 否则鉴权头要装两遍(迟早漏一处),
 * 而且连接池/线程池会翻倍。给 Coil 的是**同一个实例**(见 ui/GridScreen.kt)。
 *
 * 超时按"最慢的真实操作"定: 服务端首次预览 RAW 要做全解码(maybe 数秒) + 降采样,
 * 所以 readTimeout 给到 60s; 连接超时短(隧道断了要尽快报错, 别让用户干等)。
 */
fun buildHttpClient(token: String?): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val b = chain.request().newBuilder()
        if (!token.isNullOrEmpty()) b.header("Authorization", "Bearer $token")
        chain.proceed(b.build())
    }
    .build()
```

创建 `data/ApiClient.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 桌面端图库服务的瘦客户端。
 *
 * 只做三件事: 拼 URL、发请求、把 HTTP 错误映射成 [ApiException]。
 * **不做缓存、不做重试** —— 缓存归 OkHttp/Coil, 重试归待重试队列(M3′)。
 */
class ApiClient(
    private val baseUrl: String,
    token: String,
    private val client: OkHttpClient = buildHttpClient(token),
) {
    private val root: HttpUrl =
        requireNotNull(baseUrl.trimEnd('/').toHttpUrlOrNull()) { "非法 baseUrl: $baseUrl" }

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = root.newBuilder().addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun getJson(path: String, query: Map<String, String?> = emptyMap()): String {
        val resp = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url(path, query)).get().build()).execute()
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val code = runCatching { Json.decodeFromString<ErrorBody>(body).code }.getOrNull()
                throw ApiError.fromHttp(it.code, code)
            }
            return body
        }
    }

    suspend fun ping(): PingDto = Json.decodeFromString(getJson("api/v1/ping"))
    suspend fun shares(): List<String> = Json.decodeFromString<SharesDto>(getJson("api/v1/shares")).shares
    suspend fun drives(): List<DriveDto> = Json.decodeFromString(getJson("api/v1/drives"))
    suspend fun browse(path: String): FolderEntryDto =
        Json.decodeFromString(getJson("api/v1/browse", mapOf("path" to path)))
    suspend fun scan(path: String): List<ScannedPhotoDto> =
        Json.decodeFromString(getJson("api/v1/scan", mapOf("path" to path)))

    /** 缩略图/预览的 URL —— 交给 Coil 加载，不走上面的 JSON 通道。 */
    fun thumbnailUrl(path: String, size: Int, mtime: Long): String =
        url("api/v1/thumb", mapOf("path" to path, "size" to size.toString(), "v" to mtime.toString())).toString()

    fun previewUrl(path: String, max: Int, mtime: Long, full: Boolean = false): String =
        url(
            "api/v1/preview",
            mapOf("path" to path, "max" to max.toString(), "v" to mtime.toString(),
                  "full" to if (full) "1" else null),
        ).toString()

    suspend fun writeDecisions(items: List<DecisionDto>): String {
        val body = Json.encodeToString(items).toRequestBody("application/json".toMediaType())
        val resp = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url("api/v1/decisions")).put(body).build()).execute()
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val code = runCatching { Json.decodeFromString<ErrorBody>(text).code }.getOrNull()
                throw ApiError.fromHttp(it.code, code)
            }
            return text
        }
    }
}
```

> 需要 `import okhttp3.MediaType.Companion.toMediaType` 与 `kotlinx.serialization.encodeToString`。
> 注意 `v`（mtime）查询参数：**服务端目前不读它**，它的作用是让 Coil 的缓存键随文件变化——这是一个纯客户端约定，写在 `thumbnailUrl` 的 KDoc 里。

- [ ] **Step 6: 跑测试直到全绿**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon testDebugUnitTest
```

Expected: `SmokeTest` + `DtoTest`(2) + `ApiErrorTest`(3) + `ApiClientTest`(4) 全过。

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/data android/app/src/test
git commit -m "feat(android): DTO/ApiClient/闭集错误码映射(MockWebServer 契约测试)"
```

---

### Task 3: 凭据存储 + 配对向导

**安全要点**：token 用 **AndroidKeyStore 里的 AES/GCM 密钥**加密后落盘，**不引 `androidx.security.crypto`**（`EncryptedSharedPreferences` 已不再推荐）。URL 与共享根缓存放 DataStore（非机密）。

> **哪些能做 JVM 单测、哪些不能，这里先划清**（否则评审会如实指出"没有测试"，而我们没有好的补法）：
> - **能测**：一切纯逻辑 —— DTO 解析与未知字段容错、错误码映射、URL 拼接与百分号编码、配对载荷解析、待重试合并。这些是本计划单测的主体。
> - **不能测**：`SecretStore` 的加解密与 `PairingStore` 的 DataStore 读写 —— **AndroidKeyStore 在 JVM 单测里根本不存在**，要测就得引 Robolectric，那与本计划"不新增依赖"的约束直接冲突。
> - **取舍**：这两处的验收是 Task 3 Step 4 的**真机配对流程**（含"配对码错一位应给出 unauthorized 的可执行提示"）。这是有意的，不是遗漏。

**Files:**
- Create: `data/SecretStore.kt`、`data/PairingStore.kt`、`ui/PairingScreen.kt`
- Modify: `MainActivity.kt`（挂导航：未配对 → PairingScreen）

**Interfaces:**
- Produces:
  - `class SecretStore(context: Context)`：`fun putToken(t: String)` / `fun getToken(): String?` / `fun clear()`
  - `data class Pairing(val url: String, val token: String)`
  - `class PairingStore(context: Context)`：`suspend fun load(): Pairing?` / `suspend fun save(url: String, token: String)` / `suspend fun clear()`
  - `@Composable fun PairingScreen(onPaired: (Pairing) -> Unit)`

- [ ] **Step 1: 实现 SecretStore（AndroidKeyStore + AES/GCM）**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对 token 的本地存储。
 *
 * 为什么不用 `androidx.security.crypto` 的 EncryptedSharedPreferences: 该库已不再推荐,
 * 而我们需要的只是"一个密钥 + 一次 AES/GCM", 直接写反而更少黑盒。
 *
 * 为什么值得加密: 配对 token = 你电脑上共享目录的完整读写凭据。它只存在
 * **应用私有目录**里(已受沙箱与文件级加密保护), 再叠一层 Keystore 密钥是纵深防御 ——
 * 备份/取证的场景下拿到的只是密文。
 *
 * IV 与密文一起存(前 12 字节是 IV) —— GCM 的 IV 绝不允许复用。
 */
class SecretStore(private val context: Context) {
    private companion object {
        const val KEY_ALIAS = "imagefilter.mobile.secret"
        const val FILE = "pairing.bin"
        const val GCM_TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun putToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        file().writeBytes(out)
    }

    fun getToken(): String? = runCatching {
        val raw = file().readBytes()
        if (raw.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, 12)))
        }
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }.getOrNull()   // 密钥被清(换机/恢复) → 解密失败 → 当作"没配对", 让用户重新配对而不是崩

    fun clear() { file().delete() }

    private fun file(): File = File(context.filesDir, FILE)
}
```

> `Base64` 在这个实现里其实用不到（存二进制）——导入可以删掉，别留未使用的 import。

- [ ] **Step 2: 实现 PairingStore**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.pairingDataStore by preferencesDataStore(name = "pairing")

data class Pairing(val url: String, val token: String)

/**
 * 配对信息: URL 放 DataStore(非机密), token 放 [SecretStore](加密)。
 *
 * 拆成两处的理由: DataStore 是明文文件, 而 token 是"你电脑共享目录的读写凭据"。
 * 两者生命周期也不同 —— 轮换 token 不必动 URL, 换电脑不必动加密密钥。
 */
class PairingStore(private val context: Context) {
    private val keyUrl = stringPreferencesKey("url")
    private val secret = SecretStore(context)

    suspend fun load(): Pairing? {
        val url = context.pairingDataStore.data.first()[keyUrl] ?: return null
        val token = secret.getToken() ?: return null
        return Pairing(url, token)
    }

    suspend fun save(url: String, token: String) {
        context.pairingDataStore.edit { it[keyUrl] = url.trimEnd('/') }
        secret.putToken(token)
    }

    suspend fun clear() {
        context.pairingDataStore.edit { it.remove(keyUrl) }
        secret.clear()
    }
}
```

- [ ] **Step 3: 配对向导 UI**

`ui/PairingScreen.kt` 的**要求**（布局细节由实现者定，但以下必须成立）：

- 三个输入：**地址**（`http://192.168.x.x:8765` 或隧道虚拟网地址）、**配对码**（64 位 hex）、可选备注
- 一个 **「测试连接」**按钮：调 `ApiClient(url, token).ping()` 与 `.shares()`
  - `ping` 失败 → 按 `ApiException.code` 给**可执行提示**（规格 §5.3 的要求）：
    - `unauthorized` → "配对码不对，或已在电脑上轮换过——去电脑上重新复制"
    - 网络异常 → 给 §1.3 的四条排查清单：① 电脑没睡眠 ② ImageFilter 正在运行 ③ 服务已开启 ④ 手机与电脑在同一网络（或都加入了同一个 EasyTier/Tailscale 网络）
  - `shares` 返回**空列表** → 明确提示"电脑上还没共享任何目录，服务对所有路径都会返回无权限"（这是有意的安全默认，不是 bug）
  - `shares` 有内容 → 显示共享根列表，允许"开始使用"
- 成功后调 `PairingStore.save(url, token)`，再回调 `onPaired`
- **不提供"忽略证书错误"之类的开关**（规格 §4.3：服务端不做 TLS，加密交给隧道）

- [ ] **Step 4: 手工验证（真机）**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon installDebug
```

真机上：输入电脑的监听地址与配对码 → 「测试连接」→ 应显示共享根列表；故意把配对码改错一位 → 应显示 `unauthorized` 的可执行提示（而不是"出错了"）。

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/data/SecretStore.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/data/PairingStore.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/PairingScreen.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt
git commit -m "feat(android): 配对向导 + Keystore 加密的配对码存储"
```

---

### Task 4: 共享根 → 目录树

**Files:**
- Create: `ui/BrowseScreen.kt`、`ui/BrowseViewModel.kt`
- Modify: `MainActivity.kt`（配对后进入 BrowseScreen）

**Interfaces:**
- Consumes: `ApiClient.browse(path)`、`.shares()`、`.drives()`
- Produces: `@Composable fun BrowseScreen(pairing: Pairing, onOpenFolder: (FolderEntryDto) -> Unit, onSettings: () -> Unit)`

**要求**（规格 §8.1）：

- 启动时拉 `shares()` 作为根节点列表；**不调 `drives()` 做默认入口**（drives 是给"电脑上插了卡"用的，M4′ 再暴露）
- 逐层展开：点节点 → `browse(path)` → 把返回的 `subfolders` 合并进树
  - **单层语义**：服务端只返回直接子目录（`browse_directory` 的既有语义），所以**必须懒加载**，不能假设一次拿全。这与桌面端文件夹树同源，桌面端为此踩过"点设备=整盘遍历"的坑（交接文档 §5 第 9 条），手机端同理。
- 侧滑抽屉放树；主区在未选文件夹时显示"选一个文件夹开始"
- 列表项显示 `name`，次级信息显示 `photoCount`（服务端 `browse` 给的是 0，只有 `scan` 才有真数——**别在这层假装有计数**）
- 空文件夹 / 无权限文件夹要有明确区分（`ApiException.code` = `notFound` vs `forbiddenPath`）

- [ ] **Step 1: 实现 ViewModel（可 JVM 测的纯状态逻辑）**

把"树的合并"抽成**纯函数**，与 Compose 无关，这样能在 JVM 单测里覆盖（这也是桌面端 `mergeChildren` 的既有做法）：

```kotlin
/** 把某个节点的子目录合并进扁平索引(以 path 为键)。纯函数, 便于单测。 */
fun mergeChildren(
    index: Map<String, List<FolderEntryDto>>,
    parentPath: String,
    children: List<FolderEntryDto>,
): Map<String, List<FolderEntryDto>> = index + (parentPath to children.sortedBy { it.name.lowercase() })
```

补一个 JVM 单测：同一父节点重复合并时以最新为准（避免慢盘上两次响应乱序导致旧数据覆盖新的）。
> 若要防乱序，给每次请求带一个代次号（桌面端 `xmpGenRef` / `COUNT_GENERATION` 就是这个模式，交接文档 §4.2）。**M1′ 先用"最新覆盖"并写测试钉住**，代次号等出现真实乱序再加。

- [ ] **Step 2: 实现 BrowseScreen**

- `LazyColumn` 显示共享根下的目录；抽屉里同一棵树
- 懒加载：点开节点时才 `browse()`，加载中显示占位行
- 失败时按 `ApiException.code` 提示（`forbiddenPath` → "这个目录不在电脑的共享根里"）

- [ ] **Step 3: 真机验证**

连上电脑 → 看到共享根 → 逐层点进照片目录 → 不崩、不卡（大目录下逐层展开要流畅）。

- [ ] **Step 4: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/BrowseScreen.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/BrowseViewModel.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt
git commit -m "feat(android): 共享根与懒加载目录树"
```

---

### Task 5: 缩略图网格（Coil 3 + 共用鉴权 OkHttp）

**这是 M1′ 的价值点**：真机上看到电脑里的照片缩略图。

**Files:**
- Create: `ui/GridScreen.kt`、`ui/ImageLoader.kt`
- Modify: `MainActivity.kt`

**Interfaces:**
- Consumes: `ApiClient.scan(path)`、`ApiClient.thumbnailUrl(path, size, mtime)`、`buildHttpClient(token)`
- Produces: `@Composable fun GridScreen(pairing: Pairing, folder: FolderEntryDto)`；`fun rememberAppImageLoader(token: String): ImageLoader`

**要求**（规格 §4.2 / §5.2 / §9）：

- **Coil 与 ApiClient 共用同一个 `OkHttpClient` 实例**（`ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }`）——否则鉴权头要装两遍，迟早漏一处
- URL 必须带 `v=<modifiedAt>`：文件在电脑上被换掉后，Coil 的磁盘缓存要能失效
- `LazyVerticalGrid` 的**单元格固定尺寸**（不要让图片撑开高度），列数用 `GridCells.Adaptive(minSize = 96.dp)`；缩略图请求 `size=300`
- **预取策略先保持简单**：Coil 自己的 `LazyGrid` 可见区加载已经够用；**不要手写预取**（桌面端有 IntersectionObserver 预加载，那是 Web 的补偿手段，Compose 里 LazyGrid 已经做了）
- 空文件夹、全是 RAW、服务端返回 0 张 —— 三种都要有明确文案，别显示空白网格
- 顶部显示当前文件夹名与张数（来自 `scan` 的返回长度）；点击格子暂时只弹出一个"查看器在 M2′ 实现"的占位提示（**不要**在 M1′ 里顺手把查看器做掉）

- [ ] **Step 1: 实现 ImageLoader**

```kotlin
/**
 * 全局唯一的 ImageLoader。
 *
 * **必须与 ApiClient 共用同一个 OkHttpClient** —— 缩略图端点需要同一个 Bearer 头,
 * 各建一个客户端会出现"JSON 接口能通、图片 401"这种极难查的分裂。
 */
fun rememberAppImageLoader(token: String): ImageLoader {
    val context = LocalContext.current
    val client = remember(token) { buildHttpClient(token) }
    return remember(client) {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }
}
```

- [ ] **Step 2: 实现网格**

要点：`AsyncImage` + `ContentScale.Crop` + 固定 `aspectRatio(1f)`；`placeholder` 给一个纯色块（**不要**做 shimmer 动画——M1′ 不要为观感引入额外依赖与算力）。

- [ ] **Step 3: 真机验证（M1′ 的验收线）**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon installDebug
```

逐项打勾：
1. 配对成功 → 选一个真实照片文件夹 → **网格里出现缩略图**（这是 M1′ 的完成标志）
2. 快速上下滚动几百张：不崩、不明显掉帧
3. 断网（开飞行模式）后进文件夹 → 有明确的网络错误提示，**不是**一直转圈
4. 把电脑上的服务关掉 → 提示 `serviceOff` 类错误（或连接失败）并给出"去电脑上开启服务"
5. 在电脑上把某张照片换掉（或改 mtime）→ 手机上重新进该文件夹 → 该缩略图刷新（验证 `v=` 参数生效）

- [ ] **Step 4: 落证据**

把上述 5 项的实际结果（含失败项）写进 `docs/evidence/android-m1/REPORT.txt`；截图放 `docs/evidence/android-m1/`（**注意**：本仓库有"探针截图不入库"的惯例，若截图较大就只入库 REPORT.txt 并在报告里给出文件名）。

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/ImageLoader.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/MainActivity.kt `
        docs/evidence/android-m1/REPORT.txt
git commit -m "feat(android): 缩略图网格(Coil 3 与 ApiClient 共用鉴权 OkHttp)"
```

---

## 附录 A · 规格覆盖对照（M1′ 部分）

| 规格条目 | 由哪个任务实现 |
|---|---|
| §7 技术栈（Kotlin + Compose + OkHttp + kotlinx.serialization + Coil 3） | Task 1 |
| §5.1 `/ping` `/shares` `/drives` `/browse` `/scan` | Task 2 |
| §5.1 `/thumb`（经 Coil 拉取） | Task 5 |
| §5.3 闭集错误码原样映射 | Task 2 |
| §7 `PairingStore` + Keystore（**不用 security-crypto**） | Task 3 |
| §1.3 四条排查清单（首启引导） | Task 3 |
| §8.1 共享根 + 懒加载目录树 + 三 Tab 布局的"浏览"页 | Task 4 |
| §8.1 `LazyVerticalGrid` 网格 + 列数 | Task 5 |
| §9 缓存由 Coil/OkHttp 提供 + `?v=<mtime>` 失效 | Task 5 |
| §12 Token 纪律（不引 Room、不引 SubsamplingScaleImageView、单文件 ≤300 行） | 全任务 |
| §5.1 `/preview` `/decisions` `/move` | **不在本计划** → M2′/M3′ |
| §8.2 滑卡 / §8.3 未同步 | **不在本计划** → M3′ |
| §4.2 EasyTier/Tailscale 自动探测与打通引导 | **不在本计划** → M4′ |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| 查看器（缩放/平移） | M2′。M1′ 只到"看得见缩略图" |
| 星级 / 色标 / 回写 | M2′ |
| 滑卡 / 移动分类 / 待重试 | M3′ |
| Room | 规格决策 #8：B′ 下本地状态极少，照片列表每次从服务端拉 |
| 手写图片预取 | Compose 的 LazyGrid + Coil 已经做了，桌面端那套 IntersectionObserver 是 Web 的补偿手段 |
| 模拟器与系统镜像 | 真机验收即可，省 1.5GB 下载；模拟器留到需要自动化回归时再装 |
| 混淆（minify） | 首次发版先不开：出问题时的栈更好读；等接口稳定再打开 |
| 二维码配对 | M4′（要决定用 JS 库还是纯 Kotlin 生成，是独立决策） |
| 存放原始照片到手机 | 本 App 的设计前提就是照片留在电脑上，手机只拉缩略图与预览 |
