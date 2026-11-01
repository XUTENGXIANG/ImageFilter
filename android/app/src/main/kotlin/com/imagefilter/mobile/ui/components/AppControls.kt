// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 分段控件 —— 苹果 HIG 的 Segmented Control。
 *
 * 底座是填充胶囊, 选中项是一枚**浮起来的实色胶囊**; 只用于"互斥的少量选项"
 * （外观三档、排序等）。不做多选, 多选用 [AppFilterChip]。
 *
 * 48dp 总高、44dp 项高: 视觉不臃肿, 命中区仍然过 44。切换时给一次轻触觉。
 */
@Composable
fun <T> AppSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(p.fill)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val source = remember(option) { MutableInteractionSource() }
            val background by animateColorAsState(
                // 深色下选中项要**更亮**而不是更暗: s2(#18181B) 压在同色底座上会看不出选中。
                targetValue = if (isSelected) (if (p.isDark) p.fillStrong else p.s2) else Color.Transparent,
                animationSpec = tween(170),
                label = "segment-background",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .then(
                        if (isSelected && !p.isDark) {
                            Modifier.shadow(
                                elevation = 4.dp,
                                shape = CircleShape,
                                ambientColor = p.shadow,
                                spotColor = p.shadow,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .background(background)
                    .appClickable(
                        interactionSource = source,
                        role = Role.Button,
                        onClick = {
                            if (!isSelected) {
                                haptics.tick()
                                onSelect(option)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) p.textPrimary else p.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 筛选胶囊 —— 多选条件（星级 / 色标 / 未同步）。
 *
 * 选中时用柔和主色底 + 1dp 主色边 + 勾图标, **三种信号同时出现**:
 * 色弱、灰度截图、读屏都能分辨。未选中是填充底、无边框 —— 单边框纪律。
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(AppRadius.row)
    val background by animateColorAsState(
        targetValue = if (selected) p.accentSoft else p.fill,
        animationSpec = tween(140),
        label = "filter-chip-background",
    )
    Row(
        modifier = modifier
            // 44dp 命中区: 胶囊看起来可以小, 手指不能瞄准。
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(background)
            .then(
                if (selected) Modifier.border(Dp.Hairline, p.accent, shape) else Modifier,
            )
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = p.accentText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        } else if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) p.accentText else p.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * 填充式输入框 —— 不用 M3 默认的 4dp 方角 + 下划线。
 *
 * 14dp 圆角、填充底、无指示线、错误时柔和红底; 标签与辅助文字都在框内/框下,
 * 一处只说一件事。配对页的三只输入框都用它。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    val placeholderText = placeholder
    val hint = supportingText
    val placeholderContent: (@Composable () -> Unit)? = if (placeholderText != null) {
        { Text(placeholderText, style = MaterialTheme.typography.bodyLarge) }
    } else {
        null
    }
    val supportingContent: (@Composable () -> Unit)? = when {
        hint != null -> { { Text(hint, style = MaterialTheme.typography.bodySmall) } }
        isError -> { { Text("请检查这一项", style = MaterialTheme.typography.bodySmall) } }
        else -> null
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        shape = RoundedCornerShape(AppRadius.control),
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder = placeholderContent,
        supportingText = supportingContent,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = p.fill,
            unfocusedContainerColor = p.fill,
            disabledContainerColor = p.fill.copy(alpha = 0.6f),
            errorContainerColor = p.errorSoft,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            cursorColor = p.accent,
            errorCursorColor = p.error,
            focusedTextColor = p.textPrimary,
            unfocusedTextColor = p.textPrimary,
            disabledTextColor = p.textMuted,
            errorTextColor = p.textPrimary,
            focusedLabelColor = p.textMuted,
            unfocusedLabelColor = p.textMuted,
            disabledLabelColor = p.textMuted,
            errorLabelColor = p.error,
            focusedSupportingTextColor = p.textMuted,
            unfocusedSupportingTextColor = p.textMuted,
            errorSupportingTextColor = p.error,
            focusedPlaceholderColor = p.textMuted,
            unfocusedPlaceholderColor = p.textMuted,
        ),
    )
}

/**
 * 勾选行 —— "显示配对码"这类开关。
 *
 * 整行 `toggleable`: 点字与点框是同一下; 读屏会读成"复选框, 已勾选/未勾选"。
 * 自绘 22dp 圆角方框, 选中时主色实底 + 勾, 与 M3 默认复选框的方角区分。
 */
@Composable
fun AppCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    val fill by animateColorAsState(
        targetValue = if (checked) p.accent else Color.Transparent,
        animationSpec = tween(150),
        label = "check-fill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) p.accent else p.hairlineStrong,
        animationSpec = tween(150),
        label = "check-border",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(AppRadius.row))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = {
                    haptics.tick()
                    onCheckedChange(it)
                },
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(fill)
                .border(1.5.dp, borderColor, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = p.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = p.textPrimary,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                )
            }
        }
    }
}

/** 加载行 —— 一句话 + 一个小转圈, 不假装已经拿到内容。 */
@Composable
fun AppLoadingRow(text: String, modifier: Modifier = Modifier) {
    val p = LocalPhotoPalette.current
    Row(
        modifier = modifier.padding(vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = p.accentText,
        )
        Spacer(Modifier.width(AppSpacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
    }
}
