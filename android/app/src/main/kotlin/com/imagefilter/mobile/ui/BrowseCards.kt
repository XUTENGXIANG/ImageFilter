// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
 * `▸` 一直画着, **不按 `hasSubdirs` 隐藏** —— 那个字段只有 `browse` 之后才有,
 * 根节点这一层根本不知道(默认 false)。照它隐藏的话, 一个真有子目录的目录在树里
 * 会看起来点不开; 而点了之后服务端说"这一层是空的"也只是一句话, 不是谎。
 */
@Composable
internal fun TreeItemRow(
    row: TreeRow,
    expanded: Boolean,
    loading: Boolean,
    errorText: String?,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val entry = row.entry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (row.depth * 16).dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(20.dp),
            )
            Text(
                entry.name.ifBlank { entry.path },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            secondaryLabel(entry, row.isRoot),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp),
        )
        if (errorText != null) {
            // 失败**就画在这一行下面**: 用户是在这一行上点的, 错误出现在别处会让人找不到
            Text(
                errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp),
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 加载中的占位行 —— 这一层还没回来之前, 不许画成"空目录"。 */
@Composable
internal fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
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
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (collapsible) {
                QuietText(body, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(body.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 失败卡: 一句人话 + 一个动作。**绝不只显示一个码**。 */
@Composable
internal fun ErrorCard(head: String, text: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
