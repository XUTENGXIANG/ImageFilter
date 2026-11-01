// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.PairingCheck
import com.imagefilter.mobile.ui.components.AppButton
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppErrorCard
import com.imagefilter.mobile.ui.components.AppPathText
import com.imagefilter.mobile.ui.components.AppStatusPill
import com.imagefilter.mobile.ui.components.AppTone
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette

/**
 * 配对向导里的两张结果卡。单独一个文件只是为了让 [PairingScreen] 留在 §12 的
 * "单文件 ≲300 行"以内; 它们没有自己的状态, 全靠参数。
 *
 * 苹果风落点: "成功"是一个状态胶囊 + 产品名, 共享根逐条列出; 空共享根用琥珀色
 * **提示**而不是红色**错误** —— 它是服务端有意的安全默认, 不是故障。
 */

/** 「测试连接」成功。共享根为空时**照样允许继续**(那是服务端的安全默认, 不是故障)。 */
@Composable
internal fun ConnectedCard(
    result: PairingCheck.Connected,
    busy: Boolean,
    onStart: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    AppCard(padding = AppSpacing.xl) {
        AppStatusPill(text = "连接成功", tone = AppTone.Accent)
        Spacer(Modifier.height(AppSpacing.md))
        Text(
            text = "${result.ping.name} ${result.ping.version}",
            style = MaterialTheme.typography.titleLarge,
            color = p.textPrimary,
        )
        Spacer(Modifier.height(AppSpacing.md))
        if (result.hasShares) {
            Text(
                text = "共享根（${result.shares.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = p.textPrimary,
            )
            for (root in result.shares) {
                AppPathText(
                    path = root,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            // 服务端的安全默认: 没配共享根 = 谁都没有权限。这不拦着用户 ——
            // 拦着他反而得抱着手机去电脑上改完再回来重扫一遍配对码。
            AppStatusPill(text = "有意的安全默认", tone = AppTone.Warning)
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                text = "电脑上还没共享任何目录 —— 服务对所有路径都会返回无权限。这不是故障：" +
                    "去电脑上「设置 → 图库服务」里添加共享根即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
        }
        Spacer(Modifier.height(AppSpacing.xl))
        AppButton(
            text = if (result.hasShares) "开始使用" else "仍然继续",
            onClick = onStart,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 失败/本地输入问题/保存失败共用的卡片。文案由 [com.imagefilter.mobile.data.PairingMessages] 给。 */
@Composable
internal fun MessageCard(head: String, body: String) {
    AppErrorCard(head = head, text = body)
}
