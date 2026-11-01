// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppErrorCard
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppLoadingRow
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.components.appClickable
import com.imagefilter.mobile.ui.components.rememberAppHaptics
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * [BrowseScreen] 用到的四块小积木(行 / 加载 / 提示 / 错误)。
 *
 * 拆出来的理由与 `PairingCards.kt` 相同: 让 [BrowseScreen] 留在单文件 ≲300 行以内(§12)。
 * 它们**没有自己的状态**, 全靠参数 —— 所以"这一行显示什么"这种判断全在
 * [secondaryLabel] 这类纯函数里(有 JVM 单测), 而不是散在 Composable 里。
 */

/**
 * 树里/列表里的一行。点击 = 选中并展开(懒加载由 [BrowseViewModel] 负责)。
 *
 * 苹果风落点: 56dp 行高、左箭头**旋转**而不是换字形(能过渡)、文件夹图标、
 * 名称 16/24、说明 13/19。`▸` 一直画着, **不按 `hasSubdirs` 隐藏** —— 那个字段
 * 只有 `browse` 之后才有, 根节点这一层根本不知道(默认 false)。照它隐藏的话,
 * 一个真有子目录的目录在树里会看起来点不开。
 */
@Composable
internal fun TreeItemRow(
    row: TreeRow,
    expanded: Boolean,
    loading: Boolean,
    errorText: String?,
    /** 点行（名字 / 文件夹图标 / 右侧空白）: **直接打开这个文件夹的照片**。 */
    onClick: () -> Unit,
    onRetry: () -> Unit,
    /**
     * 点左侧箭头: 只展开/收起，**不**导航。
     *
     * 默认与 [onClick] 相同（移动选择器里点哪里都是"选中这一层"）；
     * 浏览抽屉里显式传入展开动作，于是"点一行看照片、点箭头翻目录"两件事可以分开。
     */
    onToggle: () -> Unit = onClick,
) {
    val entry = row.entry
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) p.fill else Color.Transparent,
        animationSpec = tween(130),
        label = "tree-row-press",
    )
    val caretAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(170),
        label = "tree-caret",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(AppRadius.row))
            .background(background)
            .appClickable(
                interactionSource = source,
                role = Role.Button,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .padding(
                start = (row.depth * 16).dp + 8.dp,
                end = 8.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 箭头自己是一颗 40×44 的按钮: 点它只展开/收起, 点行其他地方才打开照片。
            // 40dp 已经是手指能稳定点中的宽度, 高度由 44dp 行高补齐。
            val toggleSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .appClickable(
                        interactionSource = toggleSource,
                        role = Role.Button,
                        onClick = {
                            haptics.tick()
                            onToggle()
                        },
                    )
                    .semantics {
                        contentDescription = if (expanded) "收起这个文件夹" else "展开这个文件夹"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = p.textMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(caretAngle),
                )
            }
            Icon(
                imageVector = AppIcons.Folder,
                contentDescription = null,
                tint = if (expanded) p.accentText else p.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.name.ifBlank { displayPath(entry.path) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = p.accentText,
                )
            }
        }
        Text(
            text = secondaryLabel(entry, row.isRoot),
            style = MaterialTheme.typography.bodySmall,
            color = p.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 54.dp, top = 2.dp),
        )
        if (errorText != null) {
            // 失败**就画在这一行下面**: 用户是在这一行上点的, 错误出现在别处会让人找不到。
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = p.error,
                modifier = Modifier.padding(start = 54.dp, top = 4.dp),
            )
            AppTextButton(
                text = "重试",
                onClick = onRetry,
                color = p.error,
                modifier = Modifier.padding(start = 46.dp),
            )
        }
    }
}

/** 加载中的占位行 —— 这一层还没回来之前, 不许画成"空目录"。 */
@Composable
internal fun LoadingRow(text: String) {
    AppLoadingRow(text = text, modifier = Modifier.padding(horizontal = AppSpacing.xs))
}

/**
 * 状态说明卡(空共享根 / 空目录 / RAW 说明)。
 *
 * `collapsible = true` 时用 [QuietText]：默认一行 + 「详情」，把长说明收起来。
 * **关键警告不要用它**（例如待重试记录读不回来）—— 那种话必须整段可见。
 */
@Composable
internal fun HintCard(
    body: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
) {
    AppCard(modifier = modifier, padding = AppSpacing.lg) {
        if (collapsible) {
            QuietText(body, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text = body.replace("**", ""),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalPhotoPalette.current.textSecondary,
            )
        }
    }
}

/** 失败卡: 一句人话 + 一个动作。**绝不只显示一个码**。 */
@Composable
internal fun ErrorCard(head: String, text: String, onRetry: () -> Unit) {
    AppErrorCard(head = head, text = text, onRetry = onRetry)
}
