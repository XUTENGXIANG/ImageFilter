// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import com.imagefilter.mobile.ui.theme.AppMotion

/**
 * 触觉反馈 —— 苹果 HIG 里"按下即有回应"的另一半。
 *
 * 全部走 `View.performHapticFeedback`, 所以系统设置里关掉触觉时它自然静音;
 * 不引新依赖, 也不自己判断用户偏好。**只用四档**, 不要发明更多:
 *   · [tick] 轻拨: 滑卡过阈值、分段控件换档;
 *   · [select] 确认: 打分、选中、撤销成功;
 *   · [reject] 拒绝: 写入失败、被拦截的绝对赋值;
 *   · [threshold] 手势过线: 滑卡刚越过 25% 的那一刻(只响一次, 不是每帧)。
 *
 * 注意: 这些调用**必须**发生在主线程的点击/手势回调里; 在 LaunchedEffect 里补
 * 一次"成功"触觉是不对的 —— 那时用户的手已经离开了。
 */
class AppHaptics(private val view: View) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun select() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )

    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    fun threshold() = perform(
        if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )

    private fun perform(type: Int) {
        if (!view.isAttachedToWindow) return
        view.performHapticFeedback(type)
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) { AppHaptics(view) }
}

/**
 * 按下塌陷 + 松手回弹。
 *
 * 与 Material 的 ripple 二选一: 走这个 modifier 的控件自己把 `indication` 设成 null,
 * 否则会出现"缩放 + 涟漪"两套反馈叠在一起。值只到 0.97 —— 再小就像整块界面被按塌了。
 */
@Composable
fun Modifier.appPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = AppMotion.snappy,
        label = "app-press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 无涟漪的可点区域。
 *
 * `indication = null` 是刻意的: 视觉反馈由 [appPress] / 行底色负责。这样桌面端
 * "按下去更深一档"的手感在手机上也成立, 而不是 Material 的圆形水波。
 */
fun Modifier.appClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = onClick,
)
