// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.displayPath
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 卡片 —— 卡片最多到 S2, 一圈发丝线, **不再套第二层边框**。
 *
 * 默认内边距 20dp（卡片 18dp 圆角 − 4dp 内边距 ≈ 14dp 同心圆角）; 分组列表传
 * `padding = 0.dp`, 由 [AppRow] 自己给行内边距。浅色下不加深阴影: 白卡压在
 * 近白画布上, 靠发丝线分界, 阴影会把密集列表压脏。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = AppSpacing.xl,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPhotoPalette.current
    val shape = RoundedCornerShape(AppRadius.card)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.appPress(source) else Modifier)
            .background(p.s2)
            .border(Dp.Hairline, p.hairline, shape)
            .then(
                if (onClick != null) {
                    Modifier.appClickable(
                        interactionSource = source,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(padding),
        content = content,
    )
}

/**
 * 分组列表里的一行 —— 连接页的设备行、浏览页的子目录、设置页的条目都用它。
 *
 * 56dp 最小高度（触屏 44dp 底线之上）, 标题 15/22、副标题 13/19; 选中态用
 * [com.imagefilter.mobile.ui.theme.PhotoPalette.accentSoft] 垫底, **不是**整行主色实底 ——
 * 深色下实底会把行文字压没。
 */
@Composable
fun AppRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    showChevron: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    /** 破坏性行（重新配对）用错误色; 默认沿用主题的标题色。 */
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> p.accentSoft
            pressed && enabled -> p.fill
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "row-background",
    )
    val resolvedTitleColor = when {
        !enabled -> p.textMuted
        titleColor != null -> titleColor
        selected -> p.accentText
        else -> p.textPrimary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(AppRadius.row))
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.appClickable(
                        interactionSource = source,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (selected) p.accentText else p.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = resolvedTitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) p.accentText.copy(alpha = 0.8f) else p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = p.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 分组标题。13sp 中灰, 不抢内容; 只在分组列表上方出现。
 */
@Composable
fun AppSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalPhotoPalette.current.textMuted,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/**
 * 空态 —— **一个重点 + 一句说明 + 一个主动作**。
 *
 * 图标放在柔和主色圆底里, 不画插画（没有素材就别用装饰掩盖内容）。正文默认居中、
 * 最多两行观感; 主动作只在真的有事可做时出现。
 */
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.xxl, vertical = AppSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(p.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = p.accentText,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(AppSpacing.xl))
        Text(
            text = title.replace("**", ""),
            style = MaterialTheme.typography.headlineSmall,
            color = p.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                text = body.replace("**", ""),
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AppSpacing.xxl))
            AppButton(text = actionLabel, onClick = onAction)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(AppSpacing.xs))
            AppTextButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

/**
 * 状态胶囊 —— 只给"可配对 / 未同步 / 错误"这类短状态, 不是按钮。
 * 胶囊在这里是允许的: 它表达的是标签, 不是可点控件。
 */
enum class AppTone { Neutral, Accent, Warning, Danger }

@Composable
fun AppStatusPill(text: String, tone: AppTone = AppTone.Neutral, modifier: Modifier = Modifier) {
    val p = LocalPhotoPalette.current
    val (background, content) = when (tone) {
        AppTone.Neutral -> p.fill to p.textSecondary
        AppTone.Accent -> p.accentSoft to p.accentText
        AppTone.Warning -> p.pending.copy(alpha = 0.16f) to p.pending
        AppTone.Danger -> p.errorSoft to p.error
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** 发丝线分隔 —— 1 个物理像素, 不用 1dp 实心灰。 */
@Composable
fun AppDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = Dp.Hairline,
        color = LocalPhotoPalette.current.hairline,
    )
}

/** 一组行之间的分隔（首行前不画）。 */
@Composable
fun AppRowDivider(index: Int, startIndent: Dp = 0.dp) {
    if (index > 0) AppDivider(startIndent = startIndent)
}

/** 分组列表的容器: 卡片内边距 0, 行自己负责内边距。 */
@Composable
fun AppGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier, padding = 0.dp) {
        Column(content = content)
    }
}

/** 说明性小字（不是错误）。 */
@Composable
fun AppHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPhotoPalette.current.textMuted,
        modifier = modifier,
    )
}

/** 行内的一组动作, 右对齐。 */
@Composable
fun AppRowActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * 失败卡 —— 标题、人话、**一个**动作。
 *
 * 用柔和红底而不是整块 errorContainer: 密集页面里一整块高饱和红会把别的信息压掉;
 * 红色只出现在标题与动作上, 正文仍是正文色, 读得下去。
 */
@Composable
fun AppErrorCard(
    head: String,
    text: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actionLabel: String = "重试",
) {
    val p = LocalPhotoPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.card))
            .background(p.errorSoft)
            .border(
                Dp.Hairline,
                p.error.copy(alpha = 0.28f),
                RoundedCornerShape(AppRadius.card),
            )
            .padding(AppSpacing.lg),
    ) {
        Text(
            text = head,
            style = MaterialTheme.typography.titleSmall,
            color = p.error,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Text(
            text = text.replace("**", ""),
            style = MaterialTheme.typography.bodyMedium,
            color = p.textPrimary,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(AppSpacing.xs))
            AppTextButton(
                text = actionLabel,
                onClick = onRetry,
                color = p.error,
            )
        }
    }
}

/**
 * 路径 —— 给人看的形态: 等宽、次要色、去掉 `\\?\` 前缀。
 *
 * **只影响显示**; 发回服务端的路径仍然是原始规范形态(见 `ui.displayPath` 的 KDoc)。
 */
@Composable
fun AppPathText(path: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text = displayPath(path),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = LocalPhotoPalette.current.textMuted,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
