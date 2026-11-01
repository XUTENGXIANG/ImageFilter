// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ImageFilter 的**主题 token**(深色 / 浅色两套)。
 *
 * 设计纪律见 `.agents/skills/ui-craft`:
 *   · 表面阶梯 S0–S3 只差 1–3% 明度, 相邻档才是"分组", 不要为了层次跳 20%;
 *   · 边界用发丝线(深色白 8% / 浅色黑 6%), 不用实心中灰;
 *   · emerald = 主操作 / 选中, amber = 星级 / 未同步, 红**只给真错误**;
 *   · 浅色下的强调色统一用 emerald-700 `#047857`：白字/链接在最亮的
 *     `#FFFFFF` 上约 5.5:1、在 `#F4F4F5` 上更高。计划表里的 `#009966`
 *     只有约 3.65:1，达不到 4.5:1 的正文要求。
 *
 * 这个类型只承载**语义色**, 不承载字号/圆角(那两样两套主题共用, 见 `Theme.kt`)。
 * 五色标签是**数据**不是主题色: 它们是桌面 `labels.ts` 的固定 Tailwind 500 档,
 * 不随深浅色变(见 [PhotoColors.LabelRed] 等)。
 */
@Immutable
data class PhotoPalette(
    /** 这套 token 是不是深色。给个别需要"照片优先/黑画布"的组件做分支用。 */
    val isDark: Boolean,

    /** 画布 / 根背景。 */
    val s0: Color,
    /** 面板: 顶栏、筛选栏、评分区。 */
    val s1: Color,
    /** 卡片 / 列表行。 */
    val s2: Color,
    /** 输入区、弹层、按下态。 */
    val s3: Color,

    /** 发丝线: 深色白 8% / 浅色黑 6%。1 个物理像素。 */
    val hairline: Color,
    /** 强一级发丝线: 浮层、输入域、选中卡片的边界。 */
    val hairlineStrong: Color,
    /** 次级填充: 次要按钮、输入框、分组控件底座。 */
    val fill: Color,
    /** 更深一档的填充: 按下态、分段控件底座。 */
    val fillStrong: Color,
    /** 主色的柔和底: 选中行、图标底。 */
    val accentSoft: Color,
    /** 错误的柔和底: 错误卡容器。 */
    val errorSoft: Color,
    /** 模态遮罩。 */
    val scrim: Color,
    /** 浅色模式才用的柔影色; 深色模式不用大阴影(会糊成一团)。 */
    val shadow: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,

    /** 主操作 / 选中(按钮底色)。 */
    val accent: Color,
    /** 强调文字 / 图标 / 链接(浅色下比 [accent] 更深, 保证正文对比度)。 */
    val accentText: Color,
    /** 主操作上的文字。 */
    val onAccent: Color,

    /** 星级。 */
    val star: Color,
    /** 未同步 / 待写回 —— **不是**错误。 */
    val pending: Color,
    /** 真正的错误: 请求失败、电脑拒收。 */
    val error: Color,

    /** 色标圆点的 1dp 描边(色点本身是数据色, 不随主题变)。 */
    val labelRing: Color,
)

/** 深色 token —— 与桌面 `zinc-950/900/800` + emerald 的身份对齐。 */
val DarkPalette = PhotoPalette(
    isDark = true,
    s0 = Color(0xFF09090B),
    s1 = Color(0xFF121214),
    s2 = Color(0xFF18181B),
    s3 = Color(0xFF27272A),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x1FFFFFFF),
    fill = Color(0xFF1C1C20),
    fillStrong = Color(0xFF2A2A2E),
    accentSoft = Color(0x2600BC7D),
    errorSoft = Color(0x26FF6467),
    scrim = Color(0xB3000000),
    shadow = Color(0x66000000),
    textPrimary = Color(0xFFF4F4F5),
    textSecondary = Color(0xFF9F9FA9),
    textMuted = Color(0xFF71717A),
    accent = Color(0xFF00BC7D),
    accentText = Color(0xFF00D492),
    onAccent = Color(0xFF00120B),
    star = Color(0xFFFFB900),
    pending = Color(0xFFFFB900),
    error = Color(0xFFFF6467),
    labelRing = Color(0x33FFFFFF),
)

/**
 * 浅色 token —— 画布 `#FAFAFA`、面板 `#F4F4F5`、卡片 `#FFFFFF`。
 *
 * 浅色不用纯白铺满屏幕(卡片白只是"比画布亮一档"), 也不用纯黑正文(`#18181B`)。
 */
val LightPalette = PhotoPalette(
    isDark = false,
    s0 = Color(0xFFFAFAFA),
    s1 = Color(0xFFF4F4F5),
    s2 = Color(0xFFFFFFFF),
    s3 = Color(0xFFFFFFFF),
    hairline = Color(0x0F000000),
    hairlineStrong = Color(0x1A000000),
    fill = Color(0xFFEFEFF1),
    fillStrong = Color(0xFFE4E4E7),
    accentSoft = Color(0x1F047857),
    errorSoft = Color(0xFFFEE2E2),
    scrim = Color(0x66000000),
    shadow = Color(0x1F000000),
    textPrimary = Color(0xFF18181B),
    textSecondary = Color(0xFF52525B),
    // #71717A 压在最亮的 #F4F4F5 上只有 4.40:1；抬一档到 4.79:1。
    textMuted = Color(0xFF6B6B75),
    // 白字 / 链接在 #FFFFFF 上 5.48:1；计划表的 #009966 只有 3.65:1。
    accent = Color(0xFF047857),
    accentText = Color(0xFF047857),
    onAccent = Color(0xFFFFFFFF),
    star = Color(0xFFB45309),
    pending = Color(0xFFB45309),
    error = Color(0xFFE7000B),
    labelRing = Color(0x26000000),
)

/**
 * 当前主题的 token。**UI 里不要写死 xxxx 颜色** —— 从这儿取, 或取 M3 角色。
 *
 * 默认给 [DarkPalette]: 查看器/滑卡等"照片优先"的页面会显式
 * `ImageFilterTheme(darkTheme = true)` 再取, 不会误用浅色 token。
 */
val LocalPhotoPalette = staticCompositionLocalOf { DarkPalette }
