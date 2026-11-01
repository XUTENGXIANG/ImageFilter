// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.ui.components.AppButton
import com.imagefilter.mobile.ui.components.AppButtonStyle
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppDivider
import com.imagefilter.mobile.ui.components.AppEmptyState
import com.imagefilter.mobile.ui.components.AppErrorCard
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppStatusPill
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.components.AppTone
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 「连接电脑」页的积木(计划 §5 D2)。
 *
 * 与 `BrowseCards.kt` / `PairingCards.kt` 同一条分工: 这里是**没有状态**的 Composable,
 * "哪个状态画什么"的判断留在 [ConnectScreen]; 拆出来只是为了让屏幕文件留在
 * §12 的"单文件 ≲300 行"以内。
 *
 * 苹果风落点: 设备是一张 18dp 圆角卡（电脑图标 + 名字 + 状态胶囊 + 右侧箭头）,
 * 空态是"图标 + 一句结论 + 一个主动作", 等待确认是一张只放**当前这台电脑**的卡。
 */

/**
 * 标题下面那一行状态; `busy` 时前面带一个小转圈。整个页面**只有这一行**状态文案。
 * `liveRegion` 让读屏在"正在搜索 → 找到 N 台 → 等待确认"变化时主动播报。
 */
@Composable
internal fun ConnectStatusLine(status: String, busy: Boolean) {
    val p = LocalPhotoPalette.current
    Row(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = p.accentText,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一台被发现的电脑。
 *
 * 第二行是**动作提示**而不是状态码: `pair=open` 时「可配对」, 否则明确告诉用户
 * 去哪儿打开窗口。整张卡可点 —— 电脑上真开了窗口而 TXT 还没刷新时, 点下去会得到
 * 服务端那句准确的错误, 比一张点不动的灰卡有用。
 */
@Composable
internal fun DeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    val p = LocalPhotoPalette.current
    AppCard(onClick = onClick, padding = AppSpacing.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(p.fill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Computer,
                    contentDescription = null,
                    tint = p.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (device.pairOpen) {
                    AppStatusPill(text = "可配对", tone = AppTone.Accent)
                } else {
                    // 这是一句动作提示, 不是状态标签 —— 用正文色小字, 不要拉成一条长胶囊。
                    Text(
                        text = "请先在电脑上点「配对新设备」",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(AppSpacing.sm))
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = p.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 空状态: 一句结论 + 一句可执行的提示 + 两个动作。**不**在这里抄四条排查清单。 */
@Composable
internal fun NoDeviceCard(onRescan: () -> Unit, onAdvanced: () -> Unit) {
    AppEmptyState(
        icon = AppIcons.Wifi,
        title = "没有找到电脑",
        body = "请确认电脑上的 ImageFilter 已开启图库服务，并且和手机在同一个 Wi-Fi。",
        actionLabel = "重新搜索",
        onAction = onRescan,
        secondaryLabel = "高级模式",
        onSecondary = onAdvanced,
    )
}

/** 等待电脑确认。设备名是第一行 —— 用户要能确认自己点的是哪一台。 */
@Composable
internal fun WaitingCard(device: DiscoveredDevice, onCancel: () -> Unit) {
    val p = LocalPhotoPalette.current
    AppCard(padding = AppSpacing.xl) {
        AppStatusPill(text = "等待确认", tone = AppTone.Warning)
        Spacer(Modifier.height(AppSpacing.md))
        Text(
            text = device.name,
            style = MaterialTheme.typography.headlineSmall,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(AppSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = p.accentText,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "请在这台电脑上点「允许」",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textPrimary,
            )
        }
        Spacer(Modifier.height(AppSpacing.xl))
        AppButton(
            text = "取消",
            onClick = onCancel,
            style = AppButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 拒绝/失败共用: 一句人话 + 重试 + 高级模式。**绝不只显示一个错误码**。 */
@Composable
internal fun ConnectErrorCard(
    head: String,
    message: String,
    onRetry: () -> Unit,
    onAdvanced: () -> Unit,
) {
    Column {
        AppErrorCard(head = head, text = message, onRetry = onRetry)
        Spacer(Modifier.height(AppSpacing.xs))
        AppTextButton(
            text = "高级模式",
            onClick = onAdvanced,
            color = LocalPhotoPalette.current.textSecondary,
        )
    }
}

/** 常驻底部的「找不到电脑？高级模式」。用发丝线与正文分开, 不抢主角。 */
@Composable
internal fun ConnectAdvancedToggle(onClick: () -> Unit) {
    val p = LocalPhotoPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        AppDivider()
        AppTextButton(
            text = "找不到电脑？高级模式",
            onClick = onClick,
            color = p.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = AppSpacing.md),
        )
    }
}
