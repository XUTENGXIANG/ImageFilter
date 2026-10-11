// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/**
 * 「显示配对码」—— FIX 5: 它本来是一个 `TextButton`, 也就是**一行看起来像链接的字**,
 * 而它实际上是个开关(按下之后那个框里显示的还是隐藏的配对码)。一行字说不出"我现在是什么状态"。
 *
 * 所以改成真正的开关:
 *   · `Checkbox`(勾选框的图形 + 勾没勾的对比);
 *   · 整行 `toggleable(role = Role.Checkbox)` —— 点字与点框是**同一下**, 而且读屏会把它念成
 *     "复选框, 已勾选/未勾选"(`stateDescription`), 而不是一个没有状态的按钮;
 *   · `Checkbox(onCheckedChange = null)`: 点击由整行那一个 [toggleable] 处理,
 *     两处都处理会让一次点击翻两次(等于按了没反应)。
 *
 * [PairingScreen] 的 KDoc 说"配对码是机密": 这个开关只是把它显示/隐藏, **不落盘、不进日志**,
 * 而且旋转屏幕后仍然回到隐藏态(那个字段刻意没有进 saved instance state)。
 */
@Composable
internal fun ShowTokenToggle(show: Boolean, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = show,
                role = Role.Checkbox,
                onValueChange = { onChange() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "显示配对码"
                stateDescription = if (show) "已显示" else "已隐藏"
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = show, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text("显示配对码", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            if (show) "（现在能看见它）" else "（默认遮起来）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
