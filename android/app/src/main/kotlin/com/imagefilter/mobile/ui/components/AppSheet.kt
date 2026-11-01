// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 统一的底部 sheet —— 22dp 面板圆角之上再给 28dp 的顶部圆角。
 *
 * 与 PendingSheet 原来那套 M3 默认值（surfaceContainerLow、默认拖拽把手）分开:
 * 这里容器用 S1、把手用强一级发丝线、遮罩用 token。**只给浮层**用, 不叠第二层玻璃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPhotoPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = AppRadius.sheet, topEnd = AppRadius.sheet),
        containerColor = p.s1,
        contentColor = p.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = p.scrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(p.hairlineStrong),
                )
            }
        },
    ) {
        Column(content = content)
    }
}

/**
 * 确认框 —— 只给真正不可逆 / 代价高的动作（重新配对）。
 *
 * 形状 22dp、容器 S2、按钮走 [AppTextButton] 的 44dp 命中区。苹果 HIG 的
 * "少用确认框"同样成立: 可恢复的动作用撤销, 不在这里排队。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val p = LocalPhotoPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.panel),
        containerColor = p.s2,
        tonalElevation = 0.dp,
        titleContentColor = p.textPrimary,
        textContentColor = p.textSecondary,
        title = {
            Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            AppTextButton(
                text = confirmLabel,
                onClick = onConfirm,
                destructive = destructive,
            )
        },
        dismissButton = {
            AppTextButton(
                text = "取消",
                onClick = onDismiss,
                color = p.textSecondary,
            )
        },
    )
}

/** 带内边距的 sheet 内容容器：左右 20dp，底部 24dp。 */
@Composable
fun AppSheetBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.page, vertical = AppSpacing.sm),
        content = content,
    )
}
