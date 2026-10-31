// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 网格顶部的**筛选栏**(规格 §8.1)—— M2′ 的另一半价值点: 打完分不能筛, 等于没做完。
 *
 * 这个 Composable 里**没有一条业务判断**(与 [RatingBar] 同一条纪律):
 *   · "哪些照片留下"在 [applyFilters] 里(有 JVM 单测);
 *   · "每个维度上有几张、不知道时说什么"在 [FilterCounts] / [countText] 里;
 *   · "当前显示 N / 共 M 张"在 [shownCountLabel] 里。
 * 它只做三件事: 收状态、把点击变成 `onChange(新条件)`、把字画出来。
 *
 * 三个维度**就是**规格里那三个(星级 / 色标 / 仅未同步); 每个维度上带自己的张数 —— 而张数会
 * 如实说"未统计"([COUNT_UNKNOWN]), 因为电脑上原有的星级/色标可能这次没读到(见 [FilterCounts])。
 *
 * ⚠️ **本组件的真实观感没有真机验证**(本轮没有设备): 三个维度各占一行并可以横向滚动
 * (`horizontalScroll`), 不引入任何新依赖(没有 FlowRow / material-icons)。设备步骤在报告的
 * UNVERIFIED 清单里。
 */
@Composable
fun FilterBar(
    f: Filters,
    counts: FilterCounts,
    /** 当前筛选之后还剩几张。 */
    shown: Int,
    /** 这个文件夹一共几张(`scan` 的返回长度)。 */
    total: Int,
    onChange: (Filters) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 默认收起: 网格页的主角是照片, 三行 chips 常驻会吃掉接近四成的高度。
    // 摘要行仍然说清"现在在按什么筛", 点「筛选」才展开。
    var open by remember { mutableStateOf(false) }
    val p = LocalPhotoPalette.current

    Surface(
        color = p.s1,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                // 底部发丝线：让筛选栏和画布分开，但不用一条实心灰边。
                drawLine(
                    color = p.hairline,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f,
                )
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 顶栏的"筛选条件摘要"(规格 §8.1): 没开筛选时说明这里能做什么
                        f.summary ?: "星级 · 色标 · 未同步",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (f.isEmpty) p.textSecondary else p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 一行说清"现在看到几张 / 一共几张"；完整句子留给筛选展开后的 chips。
                    Text(
                        "$shown/$total",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = p.textMuted,
                    )
                }
                TextButton(
                    onClick = { open = !open },
                    colors = ButtonDefaults.textButtonColors(contentColor = p.textSecondary),
                ) { Text(if (open) "收起" else "筛选") }
            }

            if (open) {
                HorizontalDivider(color = p.hairline)
                // 星级: 1..5 各一个上限按钮(点同一个 = 取消)。张数是"有多少张 ≥ 这个下限"。
                ChipRow(label = "星级") {
                    for (n in 1..5) {
                        FilterChip(
                            selected = f.minStars == n,
                            onClick = { onChange(f.copy(minStars = if (f.minStars == n) 0 else n)) },
                            label = { Text("${chipMark(f.minStars == n)}≥$n★ ${counts.starText(n)}") },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }

                // 色标: 五色闭集, 内部是**或**关系(可以多选)。
                ChipRow(label = "色标") {
                    for (name in LABELS) {
                        FilterChip(
                            selected = name in f.labels,
                            onClick = {
                                val next = if (name in f.labels) f.labels - name else f.labels + name
                                onChange(f.copy(labels = next))
                            },
                            label = { Text("${chipMark(name in f.labels)}${labelName(name)} ${counts.labelText(name)}") },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("未同步", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = f.onlyUnsynced,
                        onClick = { onChange(f.copy(onlyUnsynced = !f.onlyUnsynced)) },
                        label = {
                            // 这一维的张数**永远是确定的**: "改了但没写成"是纯本地的事实。
                            Text("${chipMark(f.onlyUnsynced)}只看未同步 ${counts.unsynced}")
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    // 「清除筛选」只在真的开了筛选时出现 —— 没有条件可清的时候给一个按钮只会让人乱按。
                    if (!f.isEmpty) {
                        TextButton(onClick = { onChange(Filters()) }) { Text("清除筛选") }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 一个维度的标题 + 一行可以横向滚动的按钮。
 *
 * 不引入 `FlowRow`(它在本项目的 compose 版本里是实验 API), 也不把三个维度挤成网格:
 * 横向滚动在窄屏上是稳定的, 而且每个按钮的字都能完整显示。
 */
@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/**
 * 选中的标记。
 *
 * 为什么还要一个"✓": 光靠 chip 的底色表示"选中"在色弱/强光下不可靠, 而且读屏读到的是
 * 那串文字 —— 没有这个字, 读屏用户根本听不出这个条件开没开。
 */
private fun chipMark(selected: Boolean): String = if (selected) "✓ " else ""
