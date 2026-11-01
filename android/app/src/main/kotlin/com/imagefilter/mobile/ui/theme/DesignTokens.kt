// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * 间距阶梯 —— **4 的倍数**, 页面级只用 [page] / [section] 两档。
 *
 * 苹果风的留白不是"到处加大", 而是**分节清楚**: 连接、浏览、设置这类单列页面用
 * [page] 页边距与 [section] 分节距; 网格、滑卡、查看器仍保持照片优先的密度,
 * 不把留白硬塞进去(那会把可看的照片挤掉)。
 */
object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** 单列页面的左右页边距。 */
    val page = 20.dp
    /** 两个大分节之间的距离。 */
    val section = 28.dp
}

/**
 * 圆角阶梯 —— **同心**: 外圆角 = 内圆角 + 内边距。
 *
 * 6 角标 / 10 分组行 / 14 控件 / 18 卡片 / 22 浮层 / 28 底部 sheet。
 * 胶囊只留给分段控件与状态点, 不给普通按钮 —— 这也是桌面端 `rounded-xl` 的同一套比例。
 */
object AppRadius {
    val badge = 6.dp
    val row = 10.dp
    val control = 14.dp
    val card = 18.dp
    val panel = 22.dp
    val sheet = 28.dp
    val thumbnail = 8.dp
}

/**
 * 动效常数 —— 按**阻尼比**选, 不按感觉选。
 *
 * Compose 的 stiffness 与 CSS 不是一套单位, 所以这里只带走 ζ 与相对快慢;
 * 时长常驻 120–300ms 区间, 退场比入场更轻。系统"减少动态效果"由 Compose 的
 * `MotionDurationScale` 自动压到 ~0, 不要手写关闭分支。
 */
object AppMotion {
    /** 默认 UI: ζ≈0.78, 零超调。按钮、切换、落位都用它。 */
    val snappy = spring<Float>(dampingRatio = 0.78f, stiffness = 1200f)

    /** 面板 / 抽屉: 几乎零超调, 落位要稳。 */
    val settle = spring<Float>(dampingRatio = 0.9f, stiffness = 420f)

    /** 只给"点亮"这类情绪时刻: 轻微回弹(星级、选中章)。 */
    val bouncy = spring<Float>(dampingRatio = 0.58f, stiffness = 700f)

    /**
     * 拖拽松手回弹: ζ≈0.74, **一次**轻微过冲(约 3%)后尽快停稳。
     * 只作用于位移; 不做循环回弹。系统"减少动态效果"由 Compose 直接压到目标值。
     */
    val dragSettle = spring<Float>(dampingRatio = 0.74f, stiffness = 700f)

    /** 页面转场: 快出、稍慢进。 */
    const val enterMs = 260
    const val exitMs = 180

    /** 按下塌陷: 只做 60–100ms, 松手回弹交给弹簧。 */
    const val pressMs = 90

    /** 与桌面端 `cubic-bezier(.16,1,.3,1)` 同一条曲线。 */
    val emphasis = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}
