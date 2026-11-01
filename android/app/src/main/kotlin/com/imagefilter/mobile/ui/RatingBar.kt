// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.components.appClickable
import com.imagefilter.mobile.ui.components.rememberAppHaptics
import com.imagefilter.mobile.ui.theme.AppMotion
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import com.imagefilter.mobile.ui.theme.PhotoColors

/**
 * 五色标签对应的颜色。**只在这里出现一次** —— 与 [LABELS] 的取值一一对应
 * (桌面端 `labels.ts` 的五色). 颜色不是协议的一部分, 协议只是那五个字符串。
 *
 * `internal` 而不是 `private`: 网格格子的色标角标([GridCells.kt])要画**同一个**颜色 ——
 * 两处各写一份调色板, 迟早有一处忘记跟着改, 而"红标"在筛选栏与格子上就不是一个红了。
 */
internal val LABEL_COLORS: Map<String, Color> = mapOf(
    "red" to PhotoColors.LabelRed,
    "yellow" to PhotoColors.LabelYellow,
    "green" to PhotoColors.LabelGreen,
    "blue" to PhotoColors.LabelBlue,
    "purple" to PhotoColors.LabelPurple,
)

/**
 * 查看器底部的**星级 + 色标条** —— M2′ 的价值点就是这一行上面的每一次点击。
 *
 * 这个 Composable 里**没有一条业务判断**: 星级/色标是什么来自状态(真相在 [DecisionStore]),
 * "再点一次当前颜色 = 清掉"来自 [labelAfterTap], "写成功了没有"来自服务端回报。
 * 它只做两件事: 把点击变成回调, 把状态画出来。
 *
 * 苹果风落点: 从"整宽 S1 条"改成一张**浮起来的圆角卡片**（18dp、S2、四周留 12dp）;
 * 星星换成自绘图标（实心/空心）, 每颗 44dp 命中; 色点外圈也是 44dp。打分/选色给一次
 * 触觉 —— 这一页用户的眼睛在看照片, 手指需要知道"点到了"。
 */
@Composable
fun RatingBar(
    /** 当前这张的星级; `null` = 还没定过, `0` = 明确废弃。 */
    rating: Int?,
    /** 当前这张的色标([LABELS] 里的一个或 null)。 */
    label: String?,
    /** **电脑上**这一张的星级(三态): 不知道时画未知角标并把话说清(见本函数 KDoc)。 */
    desktopRating: DesktopRating,
    /**
     * 上面那份知识是不是**刚被电脑确认写入**得到的([ViewerState.desktopRatingFromWrite])。
     *
     * **刻意没有默认值**: 忘了带上它, 用户刚打完一次成功的分就会重新看到"上次读到：N★"
     * (读起来就是"我这一下没生效") —— 那正是 FIX 3。编译器必须替我们记住这件事。
     */
    desktopRatingFromWrite: Boolean,
    /** 写入在飞时为 false —— 禁掉连点, 免得手机这边自己制造乱序。 */
    enabled: Boolean,
    /** 正在写给电脑(显示一行进度说明)。 */
    writing: Boolean,
    onRating: (Int) -> Unit,
    onLabel: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPhotoPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 底部要让开系统手势条: 否则那条半透明手势把手会横穿色标那一行。
            .bottomBarInsets()
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
    ) {
        AppCard(padding = AppSpacing.lg) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 0 = 废弃: 与桌面端 X 键、与服务端 xmp:Rating=0 同一件事。
                AppTextButton(
                    text = "✕ 废弃",
                    onClick = { onRating(0) },
                    enabled = enabled,
                    color = if (rating == 0) p.pending else p.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                StarRow(rating = rating, enabled = enabled, onRating = onRating)
            }

            Spacer(Modifier.size(4.dp))

            // 电脑上那一份知识: 三态各说各的(屏幕上那颗星星是合并之后的值, 不能代表电脑)。
            // 时态([desktopRatingFromWrite])也一起交给纯函数 —— 界面里不写这一条判断。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ratingKnowledgeLabel(desktopRating, desktopRatingFromWrite),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (desktopRating == DesktopRating.Unknown) {
                        p.pending
                    } else {
                        p.textMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                if (writing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = p.accentText,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "正在写到电脑…",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                }
            }

            Spacer(Modifier.size(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "色标",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                for (name in LABELS) {
                    LabelDot(
                        name = name,
                        selected = label == name,
                        enabled = enabled,
                        onClick = { onLabel(labelAfterTap(label, name)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                // 当前色标的中文名(肉眼能看出"点上去了没有")
                Text(
                    text = if (label == null) "无标签" else "标签：${labelName(label)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                )
            }
        }
    }
}

/** 1–5 星。用自绘图标而不是图标库: 本项目**不新增依赖**(material-icons-extended 不小)。 */
@Composable
private fun StarRow(rating: Int?, enabled: Boolean, onRating: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            StarButton(
                filled = rating != null && stars <= rating,
                enabled = enabled,
                label = "打 $stars 星",
                onClick = { onRating(stars) },
            )
        }
    }
}

@Composable
private fun StarButton(
    filled: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (filled) p.star else p.textMuted,
        animationSpec = tween(140),
        label = "star-tint",
    )
    val scale by animateFloatAsState(
        targetValue = if (filled) 1f else 0.9f,
        animationSpec = AppMotion.bouncy,
        label = "star-scale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .appClickable(
                interactionSource = source,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.select()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (filled) AppIcons.Star else AppIcons.StarOutline,
            contentDescription = label,
            tint = if (enabled) tint else p.textMuted.copy(alpha = 0.45f),
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** 一个色标圆点。44dp 命中区包着 22dp 的色点 —— 视觉不变, 手指不用瞄准。 */
@Composable
private fun LabelDot(
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    val haptics = rememberAppHaptics()
    val source = remember { MutableInteractionSource() }
    val color = LABEL_COLORS[name] ?: p.textMuted
    // 1dp 描边: 浅色 / 深色都让色点在照片与面板上有一条自己的边界 —— 颜色本身是数据,
    // 但"这是一个圆点"不该靠背景色碰巧相同来判断。选中时把描边加粗到 2dp(第二重信号)。
    val ringWidth = if (selected) 2.dp else 1.dp
    val ringColor = if (selected) p.textPrimary else p.labelRing
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .appClickable(
                interactionSource = source,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "色标 ${labelName(name)}",
                onClick = {
                    haptics.tick()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color)
                .border(ringWidth, ringColor, CircleShape),
        )
    }
}
