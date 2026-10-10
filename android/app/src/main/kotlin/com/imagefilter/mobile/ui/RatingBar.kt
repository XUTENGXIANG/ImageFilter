// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 五色标签对应的颜色。**只在这里出现一次** —— 与 [LABELS] 的取值一一对应
 * (桌面端 `labels.ts` 的五色). 颜色不是协议的一部分, 协议只是那五个字符串。
 */
private val LABEL_COLORS: Map<String, Color> = mapOf(
    "red" to Color(0xFFE53935),
    "yellow" to Color(0xFFFDD835),
    "green" to Color(0xFF43A047),
    "blue" to Color(0xFF1E88E5),
    "purple" to Color(0xFF8E24AA),
)

/**
 * 查看器底部的**星级 + 色标条** —— M2′ 的价值点就是这一行上面的每一次点击。
 *
 * 这个 Composable 里**没有一条业务判断**: 星级/色标是什么来自状态(真相在 [DecisionStore]),
 * "再点一次当前颜色 = 清掉"来自 [labelAfterTap], "写成功了没有"来自服务端回报。
 * 它只做两件事: 把点击变成回调, 把状态画出来。
 *
 * 键位语义与桌面端对齐(README 快捷键表): 0 星用「✕ 废弃」表示 —— 与服务端
 * `xmp:Rating = 0` 是同一件事; 拖动/打分本身不触发切图(切图是手势那一层的事)。
 *
 * ⚠️ **本组件的真实观感没有真机验证**(本轮没有设备): 星星是文本字形(★/☆)、
 * 色标是圆点, 布局在两个 Row 里, 不依赖任何新依赖(没有引入 material-icons)。
 */
@Composable
fun RatingBar(
    /** 当前这张的星级; `null` = 还没定过, `0` = 明确废弃。 */
    rating: Int?,
    /** 当前这张的色标([LABELS] 里的一个或 null)。 */
    label: String?,
    /** 写入在飞时为 false —— 禁掉连点, 免得手机这边自己制造乱序。 */
    enabled: Boolean,
    /** 正在写给电脑(显示一行进度说明)。 */
    writing: Boolean,
    onRating: (Int) -> Unit,
    onLabel: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 0 = 废弃: 与桌面端 X 键、与服务端 xmp:Rating=0 同一件事。
                TextButton(onClick = { onRating(0) }, enabled = enabled) { Text("✕ 废弃") }
                Spacer(Modifier.weight(1f))
                StarRow(rating = rating, enabled = enabled, onRating = onRating)
            }

            Spacer(Modifier.width(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色标", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                for (name in LABELS) {
                    LabelDot(
                        name = name,
                        selected = label == name,
                        enabled = enabled,
                        onClick = { onLabel(labelAfterTap(label, name)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (writing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("正在写到电脑…", style = MaterialTheme.typography.bodySmall)
                } else {
                    // 当前色标的中文名(肉眼能看出"点上去了没有")
                    Text(
                        if (label == null) "无标签" else "标签：${labelName(label)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 1–5 星。用文本字形而不是图标库: 本项目**不新增依赖**(material-icons-extended 不小)。 */
@Composable
private fun StarRow(rating: Int?, enabled: Boolean, onRating: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            val filled = rating != null && stars <= rating
            Text(
                text = if (filled) "★" else "☆",
                fontSize = 26.sp,
                color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = "打 $stars 星",
                    ) { onRating(stars) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 一个色标圆点。选中的那个加一圈描边 —— 颜色本身在色弱下不可靠, 描边是第二重信号。 */
@Composable
private fun LabelDot(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = LABEL_COLORS[name] ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "色标 ${labelName(name)}") {
                onClick()
            },
    )
}
