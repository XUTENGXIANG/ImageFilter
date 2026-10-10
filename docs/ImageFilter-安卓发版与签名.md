> 本文件由 M4′ Task 4(发布准备)整理。目标: 把"固定签名 + 出 APK"这件事变成
> **照着敲就能复现**的步骤, 并把版本号的规则钉死。

# ImageFilter 安卓发版与签名

## 0. 一句话版本

| 事项 | 结论 |
|---|---|
| 版本号 | **单一事实来源** = `android/version.properties`(`versionName` / `versionCode`) |
| 与桌面端的关系 | `versionName` 必须与桌面端 3 处**完全相同**, 否则 release 构建失败 |
| 签名材料 | keystore 放在**仓库之外**(本机 `%USERPROFILE%\.imagefilter-mobile\`), 四个环境变量喂给 Gradle |
| 没有环境变量时 | 退回 **debug 签名**并打印一行很大的警告 —— 普通构建永不因为缺密钥而挂掉 |
| CI | 有 secret → 出**发布密钥签名**的 release APK; 没有 secret → 只出 **debug 签名** APK 并在日志/产物名里明说 |

---

## 1. 本地构建 APK

前置: JDK 21 + Android SDK(`compileSdk 35`)。**两条环境变量必须先设**,
否则 Gradle 会用系统 Java(本机是 25, 直接失败):

```powershell
$env:JAVA_HOME   = "$env:LOCALAPPDATA\Programs\jdk-21\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd A:\tenent\android
```

```powershell
# 调试包(日常用这个)
.\gradlew.bat assembleDebug
# -> android\app\build\outputs\apk\debug\app-debug.apk

# 发布包(没设签名环境变量时会退回 debug 签名, 并打印警告)
.\gradlew.bat assembleRelease
# -> android\app\build\outputs\apk\release\app-release.apk
#    旁边还有 SIGNING-PROVENANCE.txt, 写明这份包到底是谁签的
```

单元测试(纯 JVM, 不需要模拟器/真机):

```powershell
.\gradlew.bat testDebugUnitTest
# 报告: android\app\build\reports\tests\testDebugUnitTest\index.html
```

> **注意**: 不要用 `| Select-Object -First N` 去截 Gradle 的长输出 —— 管道会提前
> 关掉 stdout, Gradle 可能因此报错/中断。要看尾巴就重定向到文件再读。

---

## 2. 生成固定签名 keystore(一次性, 永久保留)

**只做一次**。之后所有版本都必须用**同一把** keystore 签名, 否则老用户无法覆盖安装。

```powershell
$ks = "$env:USERPROFILE\.imagefilter-mobile\release.keystore"
New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
& "$env:LOCALAPPDATA\Programs\jdk-21\jdk-21.0.12.1+1\bin\keytool.exe" -genkeypair `
  -keystore $ks -alias imagefilter -keyalg RSA -keysize 4096 -validity 10000 `
  -storetype PKCS12 -dname "CN=ImageFilter Mobile, O=XUTENGXIANG, C=CN"
```

逐项说明:

| 参数 | 为什么 |
|---|---|
| `-keyalg RSA -keysize 4096` | Play/各市场都接受, 且比默认 2048 更抗未来 |
| `-validity 10000` | ≈27 年。**keystore 过期后无法再签新包**, 所以一次给足 |
| `-storetype PKCS12` | JDK 21 的默认与推荐格式, 单一文件 |
| `-alias imagefilter` | 后面环境变量 `IMAGEFILTER_KEY_ALIAS` 必须与它一致 |
| `-dname "CN=..., O=..., C=CN"` | 自签名证书的主体; 不进任何 CA, 只是标识 |

> ### 这把 keystore 是永久资产
> 丢了就**再也无法**给已安装的用户推送覆盖更新(只能让用户卸载重装)。
> 密码同样丢了就废。**立刻**把 `.keystore` 文件 + 三个密码**一起**备份到仓库之外
> (密码管理器 / 离线介质)。**绝不要**提交进 git —— `android/.gitignore` 已忽略
> `*.keystore` / `*.jks` / `*.p12`。
>
> 只记录指纹(可以公开), 不记录密码:
> ```powershell
> & "$env:LOCALAPPDATA\Programs\jdk-21\jdk-21.0.12.1+1\bin\keytool.exe" -list -v `
>   -keystore "$env:USERPROFILE\.imagefilter-mobile\release.keystore" -alias imagefilter
> ```

---

## 3. 用这把 keystore 签名构建

Gradle 从**四个环境变量**读签名, 一次都不用把密码写进任何文件:

| 环境变量 | 含义 |
|---|---|
| `IMAGEFILTER_KEYSTORE` | keystore 文件路径(**建议绝对路径**) |
| `IMAGEFILTER_KEYSTORE_PASSWORD` | keystore 口令(store password) |
| `IMAGEFILTER_KEY_ALIAS` | 密钥别名, 例如 `imagefilter` |
| `IMAGEFILTER_KEY_PASSWORD` | 该别名的口令(key password; PKCS12 下通常与 store 口令相同) |

```powershell
$env:JAVA_HOME    = "$env:LOCALAPPDATA\Programs\jdk-21\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:IMAGEFILTER_KEYSTORE          = "$env:USERPROFILE\.imagefilter-mobile\release.keystore"
$env:IMAGEFILTER_KEYSTORE_PASSWORD = Read-Host "keystore 口令"   # 只进当前进程环境, 不落盘
$env:IMAGEFILTER_KEY_ALIAS         = "imagefilter"
$env:IMAGEFILTER_KEY_PASSWORD      = $env:IMAGEFILTER_KEYSTORE_PASSWORD

cd A:\tenent\android
.\gradlew.bat assembleRelease
```

行为矩阵(实现在 `android/app/build.gradle.kts`):

| 四个变量 | 结果 |
|---|---|
| **全给** | 用发布 keystore 签名, 产物可正式发布 |
| **全不给** | 退回 debug 签名 + **大声警告**; 构建照常成功(贡献者友好) |
| **只给一部分** | **构建失败**, 明确列出缺哪几个(半配置几乎一定是失误) |
| 给了路径但文件不存在 | **构建失败** |

自证签名(别信文件名, 信 apksigner):

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  .\app\build\outputs\apk\release\app-release.apk
```

---

## 4. 侧载安装(adb / 直接拷文件)

**方式 A: adb(推荐, 能看到安装失败的真实原因)**

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices          # 手机要已开 USB 调试并授权
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r .\app\build\outputs\apk\release\app-release.apk
# -r = 覆盖安装(保留数据)。签名不一致时这里会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

**方式 B: 纯侧载(没有电脑/没有 adb)**

1. 把 `app-release.apk` 拷到手机(数据线 / 局域网共享 / 网盘都行)
2. 手机设置里允许"安装未知来源应用"(各厂商路径不同, 一般在 设置 → 应用 → 特殊权限)
3. 文件管理器里点这个 APK → 安装

**签名变了怎么办**: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "应用未安装" 意味着
新包的签名与手机上已装的旧包不是同一把钥匙。**这是保护机制在正常工作**, 不是 bug。
只能先卸载旧版(会丢本地记录, 但照片都在电脑上)再装 —— 这也正是
"keystore 必须固定、必须备份" 的原因。

---

## 5. 版本号规则

```
android/version.properties      <-- 唯一的事实来源
    versionName=1.1.2           语义化三段: major.minor.patch
    versionCode=1               整数, 每次发版 +1, 只增不减
```

* **`versionName` 必须与桌面端完全一致**(`package.json`、`src-tauri/tauri.conf.json`、
  `src-tauri/Cargo.toml`), 这样两边版本号能互相对照, 排障时不会张冠李戴。
* **`versionCode` 只增不减**: Android **拒绝**安装 `versionCode` 比已装版本低的包
  (降级保护)。想"退回旧版"必须卸载重装。
* release 构建前会跑 `verifyReleaseVersion`:
  * 文件缺失 / 键缺失 / `versionName` 不是 `x.y.z` / `versionCode` 不是 ≥1 的整数
    → **失败**
  * `versionName` 与桌面端任意一处不一致 → **失败**(并指出是哪个文件、什么值)
  * debug 构建**不受影响**(没版本号也要能跑测试)

发版清单:

1. 桌面端 3 处版本号 + `android/version.properties` 改成新版本(4 处, 同一版本号)
2. `versionCode` +1(安卓独有)
3. `.\gradlew.bat testDebugUnitTest` 全绿
4. 按 §3 用发布 keystore `assembleRelease`
5. `apksigner verify --print-certs` 确认签名人是 `CN=ImageFilter Mobile`
6. 真机覆盖安装(验证签名一致性) + 启动器里能看到图标
7. 打 tag → CI 出 APK(见 §6)

---

## 6. CI 出 APK

工作流: `.github/workflows/android.yml`(与桌面端的 `build.yml` 分开, 互不影响)。

**诚实条款**: CI 只有在仓库配置了发布密钥 secret 时才会产出**正式签名**的 release
APK; 没有 secret 时它**只出 debug 签名 APK**, 并在 job 摘要、日志 `::warning::`
和 artifact 名字里同时写明"这不是可发布的正式包"。CI 绝不假装签过名。

需要的 repository secrets(没有就自动降级为只出 debug 包):

| Secret | 内容 |
|---|---|
| `IMAGEFILTER_KEYSTORE_BASE64` | `release.keystore` 的 base64(见下) |
| `IMAGEFILTER_KEYSTORE_PASSWORD` | keystore 口令 |
| `IMAGEFILTER_KEY_ALIAS` | `imagefilter` |
| `IMAGEFILTER_KEY_PASSWORD` | 该别名的口令 |

把 keystore 转成 base64(在**本地**做, 只把输出粘进 GitHub secret):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.imagefilter-mobile\release.keystore"))
```

CI 里 keystore 被写到 `$RUNNER_TEMP`(临时目录), 用完即随 runner 销毁,
**不会**出现在仓库或 artifact 里。

---

## 7. 相关文件

| 文件 | 作用 |
|---|---|
| `android/version.properties` | 版本号单一事实来源 |
| `android/app/build.gradle.kts` | 读版本号 + 读签名环境变量 + release 版本校验 + 签名来历 |
| `android/.gitignore` | 挡住 `*.keystore` / `*.jks` / `*.p12` |
| `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 启动器图标(XML 自适应图标, 无二进制) |
| `.github/workflows/android.yml` | CI 出 APK(有 secret 才正式签名) |
| `docs/evidence/android-release/REPORT.txt` | 每次发版的实测证据(大小/签名指纹/覆盖安装结果) |
