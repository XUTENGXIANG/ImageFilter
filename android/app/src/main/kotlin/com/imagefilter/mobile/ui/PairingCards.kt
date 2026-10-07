// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.PairingCheck

/**
 * 配对向导里的两张结果卡。单独一个文件只是为了让 [PairingScreen] 留在 §12 的
 * "单文件 ≲300 行"以内; 它们没有自己的状态, 全靠参数。
 */

/** 「测试连接」成功。共享根为空时**照样允许继续**(那是服务端的安全默认, 不是故障)。 */
@Composable
internal fun ConnectedCard(
    result: PairingCheck.Connected,
    busy: Boolean,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("连接成功", style = MaterialTheme.typography.titleMedium)
            Text(
                "${result.ping.name} ${result.ping.version}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (result.hasShares) {
                Text("共享根（${result.shares.size}）", style = MaterialTheme.typography.titleSmall)
                for (root in result.shares) {
                    Text("· $root", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // 服务端的安全默认: 没配共享根 = 谁都没有权限。这不拦着用户 ——
                // 拦着他反而得抱着手机去电脑上改完再回来重扫一遍配对码。
                Text(
                    "电脑上还没共享任何目录 —— 服务对所有路径都会返回无权限。这是有意的安全默认，" +
                        "不是故障：去电脑上「设置 → 图库服务」里添加共享根即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart, enabled = !busy) {
                Text(if (result.hasShares) "开始使用" else "仍然继续")
            }
        }
    }
}

/** 失败/本地输入问题/保存失败共用的卡片。文案由 [com.imagefilter.mobile.data.PairingMessages] 给。 */
@Composable
internal fun MessageCard(head: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
