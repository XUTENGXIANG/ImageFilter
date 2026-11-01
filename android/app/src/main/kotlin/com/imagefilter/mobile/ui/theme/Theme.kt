// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * ImageFilter 的**静态数据色**。
 *
 * 这里只剩五色标签: 它们与桌面 `labels.ts` 的 Tailwind 500 档一一对应, 是协议里的
 * "红/黄/绿/蓝/紫"这五个字符串的视觉身份。**数据色不随主题变** —— 浅色主题下也还是
 * 同一个红, 否则手机与电脑上"同一个标签"就不是同一个颜色了。
 *
 * 其余所有颜色(表面/文字/强调/星级/未同步/错误)都在 [PhotoPalette] 里按深浅两套取。
 * 这里的静态字段被刻意删掉, 编译器会逼着每个调用点变成主题感知的。
 */
object PhotoColors {
    /** 角色标签: 与桌面 `labels.ts` 的 Tailwind 500 档对齐。 */
    val LabelRed = Color(0xFFFB2C36)
    val LabelYellow = Color(0xFFF0B100)
    val LabelGreen = Color(0xFF00C950)
    val LabelBlue = Color(0xFF2B7FFF)
    val LabelPurple = Color(0xFFAD46FF)
}

/**
 * 深色 scheme。
 *
 * `surfaceTint` 刻意设成 S2: M3 的 `Surface(tonalElevation = …)` 默认会往 primary
 * 混色, 不设的话筛选栏、评分栏、滑卡背板都会泛一层绿。
 */
private val DarkColors = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = DarkPalette.onAccent,
    primaryContainer = Color(0xFF003D2A),
    onPrimaryContainer = Color(0xFF6EF7C6),
    secondary = DarkPalette.textSecondary,
    onSecondary = DarkPalette.s0,
    secondaryContainer = Color(0xFF1F2A26),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = DarkPalette.s0,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.s0,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.s3,
    onSurfaceVariant = DarkPalette.textSecondary,
    surfaceContainerLowest = DarkPalette.s0,
    surfaceContainerLow = DarkPalette.s1,
    surfaceContainer = DarkPalette.s2,
    surfaceContainerHigh = DarkPalette.s3,
    surfaceContainerHighest = DarkPalette.s3,
    surfaceTint = DarkPalette.s2,
    outline = Color(0xFF3F3F46),
    outlineVariant = DarkPalette.hairline,
    error = DarkPalette.error,
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF2A1215),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE4E4E7),
    inverseOnSurface = Color(0xFF18181B),
)

/**
 * 浅色 scheme —— 按计划表映射:
 * 画布/背景 `#FAFAFA`、面板 `#F4F4F5`、卡片 `#FFFFFF`、正文 `#18181B`、
 * 强调 `#047857`（emerald-700，白字 5.48:1）、发丝线 `#0F000000`。
 *
 * `surfaceTint = #FFFFFF`: 与深色同理, 防止 tonal elevation 把白卡染绿。
 */
private val LightColors = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = LightPalette.onAccent,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = LightPalette.textSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E7),
    onSecondaryContainer = Color(0xFF18181B),
    background = LightPalette.s0,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.s0,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = Color(0xFFE4E4E7),
    onSurfaceVariant = LightPalette.textSecondary,
    surfaceContainerLowest = LightPalette.s0,
    surfaceContainerLow = LightPalette.s1,
    surfaceContainer = LightPalette.s2,
    surfaceContainerHigh = LightPalette.s2,
    surfaceContainerHighest = LightPalette.s2,
    surfaceTint = LightPalette.s2,
    outline = Color(0xFFD4D4D8),
    outlineVariant = LightPalette.hairline,
    error = LightPalette.error,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color.Black,
    inverseSurface = Color(0xFF27272A),
    inverseOnSurface = Color(0xFFF4F4F5),
)

/**
 * 字号阶梯 —— 苹果 HIG 的"大标题 + 松正文"按中文重排过。
 *
 * 大标题（displayMedium 30/36）只给顶层页面；正文 15/22、说明 13/19，
 * 10–11sp 只留给角标。行高与字号**反向**：标题紧、正文松。
 *
 * 中文**不加负字距**：桌面那套 `-0.2sp` 是给拉丁字母的，汉字全角，收紧会挤。
 * 所以这里所有展示级字号也用 0 字距，靠字重与行高做层级。
 */
private fun appText(
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

private val PhotoTypography = Typography(
    displayLarge = appText(FontWeight.SemiBold, 34, 40),
    displayMedium = appText(FontWeight.SemiBold, 30, 36),
    displaySmall = appText(FontWeight.SemiBold, 26, 32),
    headlineLarge = appText(FontWeight.SemiBold, 24, 30),
    headlineMedium = appText(FontWeight.SemiBold, 21, 27),
    headlineSmall = appText(FontWeight.SemiBold, 19, 25),
    titleLarge = appText(FontWeight.SemiBold, 17, 23),
    titleMedium = appText(FontWeight.Medium, 16, 22),
    titleSmall = appText(FontWeight.Medium, 14, 20),
    bodyLarge = appText(FontWeight.Normal, 16, 24),
    bodyMedium = appText(FontWeight.Normal, 15, 22),
    bodySmall = appText(FontWeight.Normal, 13, 19),
    labelLarge = appText(FontWeight.Medium, 15, 20),
    labelMedium = appText(FontWeight.Medium, 13, 17),
    labelSmall = appText(FontWeight.SemiBold, 11, 14, letterSpacing = 0.3f),
)

/**
 * 圆角阶梯（与 [AppRadius] 同一套）：6 角标 / 10 行 / 14 控件 / 18 卡片 / 22 浮层。
 * sheet 的 28dp 顶部圆角在 `AppSheet` 里单独给（M3 的 Shapes 只有五档）。
 */
private val PhotoShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.badge),
    small = RoundedCornerShape(AppRadius.row),
    medium = RoundedCornerShape(AppRadius.control),
    large = RoundedCornerShape(AppRadius.card),
    extraLarge = RoundedCornerShape(AppRadius.panel),
)

/**
 * App 主题。
 *
 * [darkTheme] 由根页面根据「跟随系统 / 浅色 / 深色」偏好算出来; 查看器与滑卡页
 * 会在自己的根上再包一层 `ImageFilterTheme(darkTheme = true)`, 保证浅色模式下
 * 这两页仍是深色 chrome + 黑画布。
 *
 * 系统栏图标跟着**当前这一层**主题走: 浅色主题 = 深色图标, 深色主题 = 浅色图标。
 * 查看器强制深色时, 它那层主题会把图标切回浅色; 离开时用进入前的值恢复。
 */
@Composable
fun ImageFilterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    SystemBarIconAppearance(darkTheme = darkTheme)
    CompositionLocalProvider(
        LocalPhotoPalette provides if (darkTheme) DarkPalette else LightPalette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PhotoTypography,
            shapes = PhotoShapes,
            content = content,
        )
    }
}

/**
 * 系统栏图标明暗(状态栏 + 导航栏)。
 *
 * 用 [DisposableEffect] 而不是 `enableEdgeToEdge` 的静态样式: 主题可以在运行中切换
 * (设置里的三档), 而 `enableEdgeToEdge` 只在 `onCreate` 跑一次。离开组合时恢复进入前
 * 的值, 这样查看器/滑卡页那层 "强制深色" 退出后, 外层主题的图标状态又回来了。
 */
@Composable
private fun SystemBarIconAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, view, darkTheme) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousStatusBars = controller.isAppearanceLightStatusBars
        val previousNavigationBars = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatusBars
            controller.isAppearanceLightNavigationBars = previousNavigationBars
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
