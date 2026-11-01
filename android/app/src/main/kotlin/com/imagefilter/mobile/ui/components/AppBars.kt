// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import com.imagefilter.mobile.ui.topBarInsets

/**
 * 顶层页面的大标题 —— 苹果 HIG 的 Large Title。
 *
 * 标题 30/36, 与内容同处 S0 画布, **不画一条顶栏底色**: 大标题本身就是页面结构。
 * 导航与动作各占一个 48dp 圆钮; 没有导航也没有动作时整行不画, 免得顶部多出一块空白。
 *
 * 副标题只放"一句话事实"（张数、连接状态）; 长说明交给下面的卡片/空态, 不在标题下堆教程。
 */
@Composable
fun AppLargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 副标题是路径时用等宽小字: 路径的每个字符都是排查时的身份, 不能按标题字重读。 */
    subtitleMonospace: Boolean = false,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .topBarInsets()
            .padding(
                start = AppSpacing.lg,
                end = AppSpacing.sm,
                top = AppSpacing.xs,
                bottom = AppSpacing.md,
            ),
    ) {
        if (navigation != null || actions != null) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation?.invoke()
                Spacer(Modifier.weight(1f))
                actions?.invoke(this)
            }
            Spacer(Modifier.height(AppSpacing.xs))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                text = subtitle,
                style = if (subtitleMonospace) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontFamily = if (subtitleMonospace) FontFamily.Monospace else FontFamily.Default,
                color = p.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 内联顶栏 —— 网格、查看器、滑卡、移动流程。
 *
 * 这些页面的主角是照片/卡片, 标题只占一行 17sp, 高度 56dp; 背景色由调用方给
 * （查看器传纯黑, 网格传 S0）。`showDivider` 只在需要把顶栏和滚动内容分开时打开。
 */
@Composable
fun AppInlineTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    container: Color = LocalPhotoPalette.current.s0,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val p = LocalPhotoPalette.current
    Column(modifier = modifier.fillMaxWidth().background(container).topBarInsets()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = AppSpacing.xs, end = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation?.invoke()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sm),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions?.invoke(this)
        }
        if (showDivider) AppDivider()
    }
}

/**
 * 底部 sheet 的抬头。标题 19/25 + 右上关闭钮; 正文说明由调用方放在下面。
 */
@Composable
fun AppSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val p = LocalPhotoPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.page, end = AppSpacing.sm, top = AppSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(top = AppSpacing.sm)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = p.textPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    text = subtitle.replace("**", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
            }
        }
        if (onClose != null) {
            AppIconButton(
                icon = AppIcons.Close,
                contentDescription = "关闭",
                onClick = onClose,
                tint = p.textSecondary,
            )
        }
    }
}
