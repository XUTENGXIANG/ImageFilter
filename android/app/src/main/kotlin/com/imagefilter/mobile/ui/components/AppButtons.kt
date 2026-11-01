// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.AppMotion
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 主按钮 / 次要按钮 / 文字按钮 —— **一套尺寸, 一套圆角**。
 *
 * Material 的 `Button` 默认是胶囊, 而桌面端那套是 12px 圆角; 这里统一成 14dp,
 * 高度 52dp(触屏主操作), 次要动作 52dp、文字动作 44dp。三个变体只差底色与前景,
 * 尺寸一致 —— 同一屏里两个"看起来一样高"的按钮必须真的一样高。
 */
enum class AppButtonStyle {
    /** 主操作: emerald 实底。一屏最多一个。 */
    Primary,

    /** 次要操作: 填充底 + 发丝线。 */
    Secondary,

    /** 文字动作: 无底色, 只用于行尾/顶栏。 */
    Ghost,

    /** 破坏性动作: 柔和红底 + 红字。 */
    Destructive,
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val p = LocalPhotoPalette.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(AppRadius.control)

    val background = when (style) {
        AppButtonStyle.Primary -> if (enabled) p.accent else p.fill
        AppButtonStyle.Secondary -> p.fill
        AppButtonStyle.Ghost -> Color.Transparent
        AppButtonStyle.Destructive -> p.errorSoft
    }
    val content = when (style) {
        AppButtonStyle.Primary -> if (enabled) p.onAccent else p.textMuted
        AppButtonStyle.Secondary -> if (enabled) p.textPrimary else p.textMuted
        AppButtonStyle.Ghost -> if (enabled) p.accentText else p.textMuted
        AppButtonStyle.Destructive -> if (enabled) p.error else p.textMuted
    }
    val height = if (style == AppButtonStyle.Ghost) 44.dp else 52.dp

    Box(
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .then(
                if (style == AppButtonStyle.Primary && enabled && !p.isDark) {
                    Modifier.shadow(
                        elevation = 10.dp,
                        shape = shape,
                        ambientColor = p.shadow,
                        spotColor = p.shadow,
                    )
                } else {
                    Modifier
                },
            )
            // 先缩放再画底: graphicsLayer 只影响它**之后**的绘制, 放在 background 后面
            // 会变成"字缩小了、底色没动"。
            .appPress(source, pressedScale = if (style == AppButtonStyle.Ghost) 0.98f else 0.97f)
            .background(background)
            .then(
                if (style == AppButtonStyle.Secondary) {
                    Modifier.border(Dp.Hairline, p.hairlineStrong, shape)
                } else {
                    Modifier
                },
            )
            .appClickable(
                interactionSource = source,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = content,
                    strokeWidth = 2.dp,
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (leadingIcon != null || loading) Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

/**
 * 行尾/顶栏的文字动作。
 *
 * 44dp 高度 + 12dp 横向内边距: 文字本身可以小, 但命中区不能小。按下时垫一层
 * [com.imagefilter.mobile.ui.theme.PhotoPalette.fill], 不用 ripple。
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    color: Color? = null,
) {
    val p = LocalPhotoPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed && enabled) p.fill else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "text-button-press",
    )
    val content = color ?: when {
        !enabled -> p.textMuted
        destructive -> p.error
        else -> p.accentText
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(AppRadius.row))
            .background(background)
            .appClickable(
                interactionSource = source,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * 图标按钮 —— 顶栏与卡片右上角。
 *
 * 48dp 命中区、22dp 图标、圆形按下底。`contentDescription` **必填**: 图标按钮
 * 没有可见文字, 读屏只能靠它; 传 null 只允许用在"旁边已经有同样动作的文字"时。
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val p = LocalPhotoPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed && enabled) p.fill else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "icon-button-press",
    )
    val iconTint = tint ?: if (enabled) p.textPrimary else p.textMuted
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = AppMotion.snappy,
        label = "icon-button-scale",
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .appPress(source, pressedScale = 0.92f)
            .background(background)
            .appClickable(
                interactionSource = source,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
    }
}
