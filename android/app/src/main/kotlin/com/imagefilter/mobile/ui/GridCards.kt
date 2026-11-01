// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.imagefilter.mobile.data.ReadAction
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 网格页用到的两块小积木(一格 + 读取失败卡)。
 *
 * 单独一个文件的理由与 `BrowseCards.kt` / `PairingCards.kt` 相同: 让 [GridScreen] 留在
 * 单文件 ≲300 行以内(§12), 而且"角标画什么"这件事可以只在一处改。
 * 它们**没有自己的状态**, 全靠参数 —— 所以"这一格该显示什么"这种判断全在
 * [cellDescription] / [FilterCounts] 这类纯函数里(有 JVM 单测), 而不是散在 Composable 里。
 *
 * 苹果风落点: 格子 8dp 圆角（比之前的 6dp 与桌面端 12px 折中）, 选中改用 3dp 主色边 +
 * 一层柔和主色遮罩 + 圆形勾选章; 角标仍是黑底白字, 因为照片本身颜色不受控。
 */

/**
 * 网格里的一格, 以及它上面的**角标**(计划 Task 3 Step 2)。
 *
 * 角标的三条信息各有各的位置(与桌面端一致):
 *   · **色标**(左上圆点)—— 颜色本身就是信息, 但色弱下不可靠, 所以读屏描述里也带上它;
 *   · **星级**(右下 `★4`)—— 数字比星星形状更省地方, 而且"0 星 = 废弃"能一眼读出来;
 *   · **未同步**(一圈细边框 + 描述里的"未同步")—— 规格 §11 纪律③要求它**看得见**;
 *   · **未知**([RATING_UNKNOWN_MARK], 右下 `★?`)—— 这一次**没读到**电脑上这一张是几星。
 *     它与"确知没有星级"(什么都不画)**必须画得不一样**: 一次失败的读取会让一张 4/5 星的
 *     照片看起来与"没评过"完全相同, 而用户在手机上的一次打分就是一次**绝对赋值**。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GridCell(
    item: GridItem,
    decision: Decision?,
    /**
     * 这一格的星级**知不知道**(判据在 `GridState.ratingKnown` 一处)。
     *
     * **刻意没有默认值**: 忘了带上它的后果是"读失败被画成没评过", 而那正是本轮要修的那种
     * 静默 —— 编译器必须替我们记住这件事。
     */
    ratingKnown: Boolean,
    imageLoader: ImageLoader,
    /**
     * 多选模式开着吗(M3′ Task 4 的移动入口): 开着时**点 = 选中/取消**, 而不是打开查看器。
     *
     * 这条必须在格子上、而不是在网格里用 if 包一层: 两种模式下"点一下会发生什么"完全不同,
     * 而用户看到的必须是同一种格子(否则选择模式下点开查看器会让选中的那一批消失)。
     */
    selecting: Boolean = false,
    /** 这一格被选中了吗(只在 [selecting] 为 true 时有意义)。 */
    selected: Boolean = false,
    onClick: () -> Unit,
    /** 长按 = 进入多选模式并选中这一格。 */
    onLongClick: () -> Unit = {},
) {
    val p = LocalPhotoPalette.current
    val unsynced = decision?.sync == SyncState.UNSYNCED
    val shape = RoundedCornerShape(AppRadius.thumbnail)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            // 未同步: 一圈细边框。**只画边框是不够的** —— 读屏靠 contentDescription 里那个"未同步"
            .then(
                if (unsynced) {
                    Modifier.border(1.5.dp, p.pending, shape)
                } else {
                    Modifier
                },
            )
            // 已选中: 一圈更粗的主色边框 + 一层柔和主色遮罩 —— 与"未同步"那圈**不同色也不同粗细**,
            // 免得两种状态分不开; 遮罩让亮照片上的选中也看得出来。
            .then(
                if (selected) {
                    Modifier.border(3.dp, p.accent, shape)
                } else {
                    Modifier
                },
            )
            // "不知道电脑上是几星"用**线型**编码：虚线，与"未同步"的实线琥珀边区分。
            // 色弱/灰度截图下也能靠线型读出来。
            .then(
                if (!ratingKnown && !unsynced) {
                    Modifier.dashedOutline(p.pending.copy(alpha = 0.65f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            imageLoader = imageLoader,
            // 读屏要能听懂"这是哪一张 + 几星 + 什么色标 + 有没有没写成"(纯函数, 有单测);
            // 多选模式下再补一句"选中了没有" —— 那个粗边框色弱/读屏用户看不见。
            contentDescription = cellDescription(item, decision, ratingKnown) +
                if (selecting) (if (selected) "，已选中" else "，未选中") else "",
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(p.fill),
            error = ColorPainter(p.errorSoft),
            modifier = Modifier.fillMaxSize(),
        )

        // 选中遮罩: 只压一层 12% 主色, 不把照片压暗 —— 选中的照片仍要能判断内容。
        if (selected) {
            Box(Modifier.fillMaxSize().background(p.accent.copy(alpha = 0.12f)))
        }

        // 0 星 = 明确废弃：压一层遮罩，让"我筛掉的"看得见（而不是只有筛选栏才知道）。
        if (decision?.rating == 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        val label = decision?.label
        if (label != null) {
            LabelDotBadge(
                label = label,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
            )
        }

        // 选中勾: 画在**右上角**(色标在左上、星级在右下, 三个角各司其职)
        if (selecting) {
            SelectionBadge(
                selected = selected,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            )
        }

        val rating = decision?.rating
        when {
            // 「0 星」是桌面端的"废弃"语义, 在角标上写 `✕` 比写 `★0` 更少歧义
            rating != null -> StarBadge(
                text = if (rating == 0) "✕" else "★$rating",
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            )
            // ⚠️ 「不知道」: 画成**警告色**的未知角标 —— 与"确知没有星级"(不画)分开。
            !ratingKnown -> StarBadge(
                text = RATING_UNKNOWN_MARK,
                warning = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            )
        }
    }
}

/** 右下角的星级角标: 一行短文本 + 半透明底 —— 亮图暗图上都要读得出来。 */
@Composable
private fun StarBadge(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val p = LocalPhotoPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.badge))
            .background(
                if (warning) {
                    p.pending.copy(alpha = 0.92f)
                } else {
                    Color.Black.copy(alpha = 0.62f)
                },
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum",
            ),
            // 普通角标压在黑 chip 上 → 恒白(照片上的黑底与主题无关);
            // 未知角标压在 amber 上 → onAccent 跟着主题走(深色近黑 / 浅色白), 两种都读得清。
            color = if (warning) p.onAccent else Color.White,
        )
    }
}

/** 左上角的色标圆点。颜色只在 [LABEL_COLORS] 一处定义(与 [RatingBar] 的色标条共用)。 */
@Composable
private fun LabelDotBadge(label: String, modifier: Modifier = Modifier) {
    val p = LocalPhotoPalette.current
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(LABEL_COLORS[label] ?: p.textMuted)
            .border(1.dp, p.labelRing, CircleShape),
    )
}

/** 选中章: 实心圆 + 勾; 未选中是半透明黑底的空心圆。 */
@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPhotoPalette.current
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) p.accent else Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = p.onAccent,
                modifier = Modifier.size(13.dp),
            )
        }
    }
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
    val p = LocalPhotoPalette.current
    val shape = RoundedCornerShape(AppRadius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.errorSoft)
            .border(Dp.Hairline, p.error.copy(alpha = 0.28f), shape)
            .padding(AppSpacing.lg),
    ) {
        Text(
            text = head,
            style = MaterialTheme.typography.titleSmall,
            color = p.error,
        )
        Spacer(Modifier.size(6.dp))
        // 长解释默认收成一行：这张卡出现时用户最需要的是「哪几张、怎么办」，
        // 不是一整段成因分析。全文仍在，点「详情」展开。
        QuietText(
            text = text,
            collapsedLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = p.textPrimary,
            linkColor = p.error,
        )
        Spacer(Modifier.size(4.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 只有"重试读取"需要一个额外的出口: 现在不方便重试的用户可以把这句话收起来。
            // **收起来不等于读到了** —— 张数照旧说"未统计"(与查看器的失败卡同一条)。
            if (action == ReadAction.RETRY) {
                AppTextButton(
                    text = "知道了",
                    onClick = onDismiss,
                    color = p.textSecondary,
                )
            }
            AppTextButton(
                text = actionLabel.ifBlank { "知道了" },
                onClick = when (action) {
                    ReadAction.RETRY -> onRetry
                    // 「回目录页重新配对」: 手机上做得了的只有"回目录页"这一步(重新配对在
                    // 那一页的「设置」里), 所以这个按钮就是返回。
                    ReadAction.RECONNECT -> onReconnect
                    // 其余两个动作在手机上都没有可做的事(去电脑上改共享根 / 请求本身不合法),
                    // 按下去就收起这句话 —— 张数仍然是"未统计"。
                    ReadAction.PC_SIDE, ReadAction.DISMISS, null -> onDismiss
                },
                color = p.error,
            )
        }
    }
}

/**
 * 虚线描边 —— "这一次没读到电脑上是几星"的线型编码。
 *
 * 不用 `Modifier.border`：它不支持 dash；用 drawBehind 画一圈 1.5dp 的虚线，
 * 与"未同步"的实线琥珀边在灰度下也能分开。
 */
private fun Modifier.dashedOutline(
    color: Color,
    radius: Dp = AppRadius.thumbnail,
): Modifier = drawWithContent {
    drawContent()
    val w = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = w,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
            ),
        ),
    )
}
