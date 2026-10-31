// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 「连接电脑」页的积木(计划 §5 D2)。
 *
 * 与 `BrowseCards.kt` / `PairingCards.kt` 同一条分工: 这里是**没有状态**的 Composable,
 * "哪个状态画什么"的判断留在 [ConnectScreen]; 拆出来只是为了让屏幕文件留在
 * §12 的"单文件 ≲300 行"以内。
 *
 * 样式沿用 `Theme.kt` 的 token: 卡片 S2、发丝线、10dp 按钮圆角(不是胶囊)、
 * 15sp 主文字 / 13sp 次要文字、可点区域至少 48dp。
 *
 * 浅色下卡片是白底压在 `#FAFAFA` 画布上, 只靠明度差几乎看不出边界 —— 所以卡片加
 * **一圈发丝线**(黑 6%), 而不是加实心中灰边框或阴影。卡片内的次级按钮因此不再画
 * 第二圈边框(单边框纪律), 改用 `surfaceVariant` 填充区分。
 */

/** 按钮圆角 —— 与「开始筛片」「测试连接」同一档, 刻意不用 Material 的胶囊。 */
private val ButtonShape = RoundedCornerShape(10.dp)

/** 标题下面那一行状态; `busy` 时前面带一个小转圈。整个页面**只有这一行**状态文案。 */
@Composable
internal fun ConnectStatusLine(status: String, busy: Boolean) {
    val p = LocalPhotoPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = p.accentText,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
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
 * 去哪儿打开窗口。行仍然可点 —— 电脑上真开了窗口而 TXT 还没刷新时, 点下去会得到
 * 服务端那句准确的错误, 比一行点不动的灰字有用。
 */
@Composable
internal fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    val p = LocalPhotoPalette.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = p.s2),
        border = BorderStroke(1.dp, p.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (device.pairOpen) "可配对" else "请先在电脑上点「配对新设备」",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.pairOpen) p.accentText else p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = p.textMuted,
            )
        }
    }
}

/** 空状态: 一句结论 + 一句可执行的提示 + 两个动作。**不**在这里抄四条排查清单。 */
@Composable
internal fun NoDeviceCard(onRescan: () -> Unit, onAdvanced: () -> Unit) {
    val p = LocalPhotoPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = p.s2),
        border = BorderStroke(1.dp, p.hairline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "没有找到同一 Wi-Fi 下的电脑",
                style = MaterialTheme.typography.bodyLarge,
                color = p.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "请确认电脑上的 ImageFilter 已开启图库服务，并且和手机在同一个 Wi-Fi。",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectPrimaryButton(text = "重新搜索", onClick = onRescan)
                ConnectSecondaryButton(text = "高级模式", onClick = onAdvanced)
            }
        }
    }
}

/** 等待电脑确认。设备名是第一行 —— 用户要能确认自己点的是哪一台。 */
@Composable
internal fun WaitingCard(device: DiscoveredDevice, onCancel: () -> Unit) {
    val p = LocalPhotoPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = p.s2),
        border = BorderStroke(1.dp, p.hairline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleMedium,
                color = p.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = p.accentText,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "等待电脑确认…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.textPrimary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "请在这台电脑上点「允许」",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
            )
            Spacer(Modifier.height(16.dp))
            ConnectSecondaryButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    val p = LocalPhotoPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = p.s2),
        border = BorderStroke(1.dp, p.hairline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                head,
                style = MaterialTheme.typography.titleSmall,
                color = p.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = p.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectPrimaryButton(text = "重试", onClick = onRetry)
                ConnectSecondaryButton(text = "高级模式", onClick = onAdvanced)
            }
        }
    }
}

/** 主操作: emerald 底 + 48dp 高 + 10dp 圆角。 */
@Composable
internal fun ConnectPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPhotoPalette.current
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = p.accent,
            contentColor = p.onAccent,
        ),
        modifier = modifier.height(48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 次要动作: `surfaceVariant` 填充 + 与主操作同一尺寸(48dp), 视觉上明确退后一档。
 *
 * 刻意**不用** `OutlinedButton`: 卡片本身已经有一条发丝线, 内部再套一圈闭合边框就是
 * 两层边框(单边框纪律); 用一层填充区分即可, 浅色(#E4E4E7)与深色(#27272A)都稳。
 */
@Composable
internal fun ConnectSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPhotoPalette.current
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = p.textSecondary,
        ),
        modifier = modifier.height(48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 常驻底部的「找不到电脑？高级模式」。用发丝线与正文分开, 不抢主角。 */
@Composable
internal fun ConnectAdvancedToggle(onClick: () -> Unit) {
    val p = LocalPhotoPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = p.hairline)
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = p.textSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("找不到电脑？高级模式", style = MaterialTheme.typography.bodySmall)
        }
    }
}
