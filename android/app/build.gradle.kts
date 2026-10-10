// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// =============================================================================
// 1) 版本号: 单一事实来源 = android/version.properties
// =============================================================================
// 桌面端的版本号散在 3 个文件里(package.json / tauri.conf.json / Cargo.toml,
// 交接文档 §9.2)。安卓这边**不再**在 Kotlin DSL 里写第二份: 只读 version.properties,
// 并在 release 构建前校验它和桌面端一致 —— 不一致就**大声失败**, 绝不默默出包。

/** 版本文件解析结果。problems 非空 = release 构建必须停下(debug 不拦)。 */
data class ReleaseVersion(val name: String, val code: Int, val problems: List<String>)

fun parseReleaseVersion(
    vFile: File,
    desktopVersionFiles: List<Pair<String, File>>,
): ReleaseVersion {
    val problems = mutableListOf<String>()
    if (!vFile.isFile) {
        problems += "缺少版本文件: ${vFile.absolutePath} (应为 android/version.properties)"
        return ReleaseVersion("0.0.0-missing", 1, problems)
    }

    val props = Properties()
    vFile.reader(Charsets.UTF_8).use { props.load(it) }
    val rawName = props.getProperty("versionName")?.trim().orEmpty()
    val rawCode = props.getProperty("versionCode")?.trim().orEmpty()

    if (rawName.isEmpty()) {
        problems += "version.properties 缺少 versionName"
    } else if (!Regex("""^\d+\.\d+\.\d+$""").matches(rawName)) {
        problems += "versionName 必须是 x.y.z 三段数字, 实际是 \"$rawName\""
    }

    val code = rawCode.toIntOrNull()
    if (rawCode.isEmpty()) {
        problems += "version.properties 缺少 versionCode"
    } else if (code == null) {
        problems += "versionCode 必须是整数, 实际是 \"$rawCode\""
    } else if (code < 1) {
        problems += "versionCode 必须 >= 1, 实际是 $code"
    }

    // 与桌面端一致: 规格要求两边同版本号, 便于对照排障。
    if (Regex("""^\d+\.\d+\.\d+$""").matches(rawName)) {
        desktopVersionFiles.forEach { (label, f) ->
            if (f.isFile) {
                val found = Regex(""""version"\s*:\s*"([^"]+)"""")
                    .find(f.readText())
                    ?.groupValues?.get(1)
                    ?: Regex("""(?m)^version\s*=\s*"([^"]+)"""")
                        .find(f.readText())
                        ?.groupValues?.get(1)
                when {
                    found == null -> problems += "在 $label 里找不到 version 字段, 无法校验一致性"
                    found != rawName ->
                        problems += "版本号不一致: android/version.properties=$rawName, $label=$found" +
                            " —— 安卓与桌面端必须同版本(交接文档 §9.2)"
                }
            }
        }
    }

    return ReleaseVersion(
        name = rawName.ifEmpty { "0.0.0-missing" },
        code = code?.takeIf { it >= 1 } ?: 1,
        problems = problems,
    )
}

val versionFile: File = rootProject.file("version.properties")
val desktopVersionFiles: List<Pair<String, File>> = listOf(
    "package.json" to rootProject.file("../package.json"),
    "src-tauri/tauri.conf.json" to rootProject.file("../src-tauri/tauri.conf.json"),
    "src-tauri/Cargo.toml" to rootProject.file("../src-tauri/Cargo.toml"),
)
val releaseVersion: ReleaseVersion = parseReleaseVersion(versionFile, desktopVersionFiles)

// =============================================================================
// 2) 发布签名: 四个环境变量, 密钥**永不进仓库**
// =============================================================================
// 四个变量都给 -> 用发布 keystore 签名;
// 四个都不给   -> 退回 debug 签名 + **一行声音很大的警告**(贡献者 clone 下来就能跑
//                 assembleRelease 看产物, 不必先弄一把 keystore);
// 只给一部分   -> 直接报错(半配置几乎一定是失误, 默默退回才危险);
// 路径给了但文件不存在 -> 直接报错。
val signingEnvNames = listOf(
    "IMAGEFILTER_KEYSTORE",
    "IMAGEFILTER_KEYSTORE_PASSWORD",
    "IMAGEFILTER_KEY_ALIAS",
    "IMAGEFILTER_KEY_PASSWORD",
)
val signingEnv: Map<String, String?> = signingEnvNames.associateWith { key ->
    System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
}
val missingSigningEnv: List<String> = signingEnv.filterValues { it == null }.keys.toList()
val releaseSigningAvailable: Boolean = missingSigningEnv.isEmpty()
val releaseKeystoreFile: File? = signingEnv["IMAGEFILTER_KEYSTORE"]?.let { rootProject.file(it) }

if (!releaseSigningAvailable && missingSigningEnv.size < signingEnvNames.size) {
    throw GradleException(
        "发布签名环境变量只设置了一部分, 缺少: ${missingSigningEnv.joinToString(", ")}。" +
            "要么四个都给(正式签名), 要么四个都不给(退回 debug 签名)。",
    )
}
if (releaseSigningAvailable && releaseKeystoreFile?.isFile != true) {
    throw GradleException(
        "IMAGEFILTER_KEYSTORE 指向的文件不存在: ${releaseKeystoreFile?.absolutePath}。" +
            "生成步骤见 docs/ImageFilter-安卓发版与签名.md。",
    )
}
if (!releaseSigningAvailable) {
    val bar = "!".repeat(78)
    logger.warn(bar)
    logger.warn("!! 未检测到发布签名环境变量 -> release 变体将【使用 debug 签名】")
    logger.warn("!! 这样打出来的 app-release.apk **不是**正式发布包: 不能覆盖安装别人的正式版。")
    logger.warn("!! 缺失: ${missingSigningEnv.joinToString(", ")}")
    logger.warn("!! 正式签名步骤: docs/ImageFilter-安卓发版与签名.md")
    logger.warn(bar)
}

android {
    namespace = "com.imagefilter.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imagefilter.mobile"
        // minSdk 26: 硬件位图(Bitmap.Config.HARDWARE)要 26; 网格滚动的内存表现靠它。
        minSdk = 26
        targetSdk = 35
        // 版本号来自 android/version.properties(见文件顶部说明)。
        versionCode = releaseVersion.code
        versionName = releaseVersion.name
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingEnv["IMAGEFILTER_KEYSTORE_PASSWORD"]
                keyAlias = signingEnv["IMAGEFILTER_KEY_ALIAS"]
                keyPassword = signingEnv["IMAGEFILTER_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            // 首次发版先不开混淆: 出问题时的栈更好读。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigningAvailable) {
                signingConfigs.getByName("release")
            } else {
                // 退回 debug 签名 —— 上面的警告已经说明这不是正式包。
                signingConfigs.getByName("debug")
            }
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

// =============================================================================
// 3) 版本号校验: 只挂在 release 任务图上(debug 构建永不因它失败)
// =============================================================================
val verifyReleaseVersion by tasks.registering {
    group = "verification"
    description = "release 构建前校验 android/version.properties(单一事实来源 + 与桌面端一致)"
    val versionProblems = releaseVersion.problems
    val vFile = versionFile
    doLast {
        if (versionProblems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("版本号校验失败 —— release 构建必须停下:")
                    versionProblems.forEach { appendLine("  - $it") }
                    appendLine("单一事实来源: ${vFile.absolutePath}")
                    appendLine("校验规则与手工步骤: docs/ImageFilter-安卓发版与签名.md")
                },
            )
        }
        logger.lifecycle("版本号校验通过: versionName=${releaseVersion.name} versionCode=${releaseVersion.code}")
    }
}

// =============================================================================
// 4) 诚实标注: 在 APK 旁边写一份"这份包是谁签的"
// =============================================================================
val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
val writeReleaseSigningProvenance by tasks.registering {
    group = "build"
    description = "在 release 产物目录里写下签名来历(用了发布密钥还是退回 debug 签名)"
    val outDir = releaseApkDir
    val signedWithReleaseKey = releaseSigningAvailable
    val missing = missingSigningEnv
    val vName = releaseVersion.name
    val vCode = releaseVersion.code
    val ksPath = releaseKeystoreFile?.absolutePath
    doLast {
        val dir = outDir.get().asFile
        if (!dir.isDirectory) return@doLast
        val apks = dir.listFiles { f -> f.isFile && f.extension == "apk" }?.sortedBy { it.name }.orEmpty()
        if (apks.isEmpty()) return@doLast
        val text = buildString {
            appendLine("ImageFilter Mobile release 产物签名来历(由 Gradle 自动生成, 别手改)")
            appendLine("versionName=$vName")
            appendLine("versionCode=$vCode")
            if (signedWithReleaseKey) {
                appendLine("签名=发布密钥(IMAGEFILTER_KEYSTORE=$ksPath)")
                appendLine("用途=可正式发布")
            } else {
                appendLine("签名=debug 签名(回退) —— 因为缺少环境变量: ${missing.joinToString(", ")}")
                appendLine("用途=仅供本机验证; **不是**正式发布包, 不能覆盖安装正式版")
            }
            apks.forEach { appendLine("APK=${it.name} ${it.length()} bytes") }
            appendLine("自证命令: apksigner verify --print-certs <apk>")
        }
        File(dir, "SIGNING-PROVENANCE.txt").writeText(text)
        logger.lifecycle("签名来历已写入: ${File(dir, "SIGNING-PROVENANCE.txt").absolutePath}")
    }
}

afterEvaluate {
    // release 构建入口都先过版本校验; assembleRelease 依赖 preReleaseBuild,
    // 三个都挂上是为了将来有人直接调 bundleRelease/installRelease 也拦得住。
    tasks.matching { it.name in setOf("preReleaseBuild", "assembleRelease", "bundleRelease") }
        .configureEach { dependsOn(verifyReleaseVersion) }
    tasks.matching { it.name == "packageRelease" }
        .configureEach { finalizedBy(writeReleaseSigningProvenance) }
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
