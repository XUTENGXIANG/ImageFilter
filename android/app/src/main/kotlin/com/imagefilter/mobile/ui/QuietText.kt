// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 一行摘要 + 「详情」的说明文字。
 *
 * 为什么需要它：这个项目的文案很诚实（每一种失败都写清原因与下一步），但把整段
 * 说明常驻在屏幕上会让照片/网格变成配角。这里**不删信息**，只把它收起来 ——
 * 默认一行，点「详情」展开全文。
 *
 * 只在文本确实较长（> [COLLAPSE_AFTER] 字）时才出现「详情」；短句不装。
 */
@Composable
internal fun QuietText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 1,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = LocalPhotoPalette.current.textSecondary,
    linkColor: Color = LocalPhotoPalette.current.accentText,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    // 文案里有 Markdown 风格的 **强调**；Compose 不渲染它。显示前去掉星号，
    // 不改原文（测试与数据层仍按原字符串断言）。
    val display = text.replace("**", "")
    val canExpand = display.length > COLLAPSE_AFTER

    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(
            text = display,
            style = style,
            color = color,
            maxLines = if (expanded || !canExpand) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canExpand) {
            Text(
                text = if (expanded) "收起" else "详情",
                style = MaterialTheme.typography.labelSmall,
                color = linkColor,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            )
        }
    }
}

private const val COLLAPSE_AFTER = 48
