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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.imagefilter.mobile.data.ReadAction

/**
 * 网格页用到的两块小积木(一格 + 读取失败卡)。
 *
 * 单独一个文件的理由与 `BrowseCards.kt` / `PairingCards.kt` 相同: 让 [GridScreen] 留在
 * 单文件 ≲300 行以内(§12), 而且"角标画什么"这件事可以只在一处改。
 * 它们**没有自己的状态**, 全靠参数 —— 所以"这一格该显示什么"这种判断全在
 * [cellDescription] / [FilterCounts] 这类纯函数里(有 JVM 单测), 而不是散在 Composable 里。
 */

/**
 * 网格里的一格, 以及它上面的**角标**(计划 Task 3 Step 2)。
 *
 * 角标的三条信息各有各的位置(与桌面端一致):
 *   · **色标**(左上圆点)—— 颜色本身就是信息, 但色弱下不可靠, 所以读屏描述里也带上它;
 *   · **星级**(右下 `★4`)—— 数字比星星形状更省地方, 而且"0 星 = 废弃"能一眼读出来;
 *   · **未同步**(一圈细边框 + 描述里的"未同步")—— 规格 §11 纪律③要求它**看得见**;
 *     M3′ 会把它做成正式角标(计划里写明了"细边框"是这一轮的形态)。
 *
 * ⚠️ **真实观感没有真机验证**(本轮没有设备): 角标是文本 + Box 叠出来的, 不引入
 * material-icons; 具体在 96dp 的格子里挤不挤、边框在深色主题下够不够显眼, 只能真机看
 * (报告的 UNVERIFIED 清单)。
 */
@Composable
internal fun GridCell(
    item: GridItem,
    decision: Decision?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val unsynced = decision?.sync == SyncState.UNSYNCED
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            // 未同步: 一圈细边框。**只画边框是不够的** —— 读屏靠 contentDescription 里那个"未同步"
            .then(
                if (unsynced) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            imageLoader = imageLoader,
            // 读屏要能听懂"这是哪一张 + 几星 + 什么色标 + 有没有没写成"(纯函数, 有单测)
            contentDescription = cellDescription(item, decision),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxSize(),
        )

        val label = decision?.label
        if (label != null) {
            LabelDotBadge(label = label, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }

        val rating = decision?.rating
        if (rating != null) {
            // 「0 星」是桌面端的"废弃"语义, 在角标上写 `✕` 比写 `★0` 更少歧义
            StarBadge(
                text = if (rating == 0) "✕" else "★$rating",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

/** 右下角的星级角标: 一行短文本 + 半透明底 —— 亮图暗图上都要读得出来。 */
@Composable
private fun StarBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** 左上角的色标圆点。颜色只在 [LABEL_COLORS] 一处定义(与 [RatingBar] 的色标条共用)。 */
@Composable
private fun LabelDotBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(LABEL_COLORS[label] ?: MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * **决策读取失败**卡(一片文件的星级/色标没读到)。
 *
 * 三件事必须同时成立, 缺一件这张卡就是坏的:
 *   · 标题与正文都来自 [com.imagefilter.mobile.data.ReadMessages](界面里一个字都不拼);
 *   · 动作**由码决定**——403 要去电脑上改共享根, 手机上重试多少次都不会变;
 *   · 正文里那句"筛选栏上的张数说不出准确的数、而你这台手机上的改动一个都没丢"是固定尾部
 *     (见 `ReadMessages.COUNTS_UNKNOWN_TAIL`): 用户这时最可能误解的两件事就是这两件。
 */
@Composable
internal fun ReadFailureCard(
    head: String,
    text: String,
    action: ReadAction?,
    actionLabel: String,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // 只有"重试读取"需要一个额外的出口: 现在不方便重试的用户可以把这句话收起来。
                // **收起来不等于读到了** —— 张数照旧说"未统计"(与查看器的失败卡同一条)。
                if (action == ReadAction.RETRY) {
                    TextButton(onClick = onDismiss) { Text("知道了") }
                }
                TextButton(
                    onClick = when (action) {
                        ReadAction.RETRY -> onRetry
                        // 「回目录页重新配对」: 手机上做得了的只有"回目录页"这一步(重新配对在
                        // 那一页的「设置」里), 所以这个按钮就是返回。
                        ReadAction.RECONNECT -> onReconnect
                        // 其余两个动作在手机上都没有可做的事(去电脑上改共享根 / 请求本身不合法),
                        // 按下去就收起这句话 —— 张数仍然是"未统计"。
                        ReadAction.PC_SIDE, ReadAction.DISMISS, null -> onDismiss
                    },
                ) { Text(actionLabel.ifBlank { "知道了" }) }
            }
        }
    }
}
