// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **待重试面板**(M3′ Task 3)与它那个角标。
 *
 * 三件事在这里定死(与整个项目同一条纪律):
 *   · **一行一个事实**: 这一项要写什么([PendingRow.target]) + 为什么没写成([PendingRow.reason])
 *     + 现在最该按什么([PendingRow.actionLabel])。三句全部来自既有那张文案表
 *     ([com.imagefilter.mobile.data.WriteMessages]) —— 这里**一个字都不另写**;
 *   · **三个动作**: 单项重试 / 全部重试 / 放弃本地改动。放弃要**二次确认**, 而且那一步的判定
 *     不在这个文件里(它是数据损失, 由 [GridViewModel.requestAbandon] 那个可被 JVM 测的状态机
 *     执行): 这里只画"现在是第一次(放弃本地改动)还是第二次(确认放弃)";
 *   · 全部重试**串行、一次一项**(见 [retryPending] 的 KDoc): 半成功/已经不存在的路径不能
 *     毒掉整批。界面上那句"一次一项"是**说出来**的 —— 用户会看到列表一项一项地变。
 *
 * ⚠️ **本文件的观感没有真机验证**(本轮没有设备, 也不为此引 Compose UI 测试依赖):
 * 面板的高度、长文案的换行、深色主题下的对比只能真机看(报告里的 UNVERIFIED 清单)。
 * 但**它画的每一个字**都来自有 JVM 用例钉住的纯函数([toPendingRows] / [pendingBadgeText] /
 * [topBarBadge] / [retryNote] / [abandonNote]) —— 所以"数对不对、话说得对不对"不是靠眼睛。
 */

/**
 * 面板要的全部输入与动作, 一个对象传完。
 *
 * 做成一个数据类是为了让网格页与滑卡页**用同一份**(它们各有一个顶栏角标, 但面板只有一个):
 * 传 12 个参数会让"哪一页少传了一个"变成迟早的事, 而少传一个就是某个动作安静地不生效。
 */
class PendingPanel(
    /** 面板上每一行(文案已在构造时定稿)。 */
    val rows: List<PendingRow>,
    /** 「放弃本地改动」武装在哪一张上; 等于某一行的 path 时那一行显示「确认放弃」。 */
    val armed: String?,
    /** 队列读不回来(可能已经丢了一部分待重试的改动)—— 必须说出来。 */
    val corrupted: Boolean,
    /** 正在重试(串行): 期间所有动作禁用, 免得自己制造乱序。 */
    val busy: Boolean,
    /** 上一次重试/放弃的结论。 */
    val note: String?,
    /** 单项重试(那一项的路径)。 */
    val onRetry: (String) -> Unit,
    /** 全部重试(串行, 一次一项)。 */
    val onRetryAll: () -> Unit,
    /**
     * 「放弃本地改动」——第一次调用只把这一行武装成「确认放弃」, 第二次(同一张)才真的丢。
     * 判定在 ViewModel 里, 这里只负责把点击转过去。
     */
    val onAbandon: (String) -> Unit,
    /** 解除武装(「取消」)。 */
    val onCancelAbandon: () -> Unit,
    /** 关掉面板(同时解除武装)。 */
    val onClose: () -> Unit,
)

/**
 * 从网格那一份状态与动作里拼出面板(界面里不写业务判断)。
 *
 * [onClose] 由调用方给: 关面板这件事有**两半** —— 解除武装(免得下次进来一按就丢)与**真的把面板
 * 收起来**(`if (open) PendingSheet(...)` 的那一个开关)。少了后半句, 用户按「关闭」之后面板会
 * 一直挂在屏幕上(ModalBottomSheet 只负责把"要关"这件事报上来, 收不收由调用方决定)。
 */
fun pendingPanel(
    state: GridState,
    vm: GridViewModel,
    scope: CoroutineScope,
    onClose: () -> Unit,
): PendingPanel = PendingPanel(
    rows = state.pending.rows,
    armed = state.pending.abandonArmed,
    corrupted = state.pending.corrupted,
    busy = state.pending.busy,
    note = state.pending.note,
    onRetry = { path -> scope.launch { vm.retryPendingOne(path) } },
    onRetryAll = { scope.launch { vm.retryAllPending() } },
    onAbandon = { path -> scope.launch { vm.requestAbandon(path) } },
    onCancelAbandon = { vm.cancelAbandon() },
    onClose = onClose,
)

/**
 * 顶栏那个角标的按钮。
 *
 * 它**本身是按钮**(与查看器/网格的"未同步"角标同一条): 一个只报数、按不动的角标等于让用户
 * 知道出事了却做不了什么。显示什么与按下去做什么由 [topBarBadge] 一处决定(见那里的 KDoc)。
 */
@Composable
fun PendingBadgeButton(
    badge: TopBarBadge,
    enabled: Boolean,
    onOpenPanel: () -> Unit,
    onRetryUnsynced: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    TextButton(
        onClick = if (badge.opensPanel) onOpenPanel else onRetryUnsynced,
        enabled = enabled,
    ) {
        Text(badge.label, color = p.pending)
    }
}

/** 面板本体。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingSheet(panel: PendingPanel) {
    ModalBottomSheet(onDismissRequest = panel.onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // 队列一般不长, 但"一般"不是"一定": 给一个上界, 免得面板盖满整屏
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("待重试（${panel.rows.size} 项）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "这些是这台手机上**还没被电脑确认**的改动。关着电脑（或断网）时打的星级、标过的色标" +
                    "都在这里，重启 App 也还在。",
                style = MaterialTheme.typography.bodySmall,
            )

            // 队列读不回来: 这一句必须在列表**之上** —— 它说的是"这个列表可能不完整"。
            if (panel.corrupted) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "这台手机上的待重试记录**没能读出来**（文件坏了或版本对不上）：下面这份列表可能" +
                        "不完整，之前没写进电脑的改动**可能已经丢了**。点「重试」只会重发还读得到的那些。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val note = panel.note
            if (note != null) {
                Spacer(Modifier.size(8.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()

            if (panel.rows.isEmpty()) {
                Spacer(Modifier.size(12.dp))
                Text("现在没有待重试的改动。", style = MaterialTheme.typography.bodyMedium)
            }

            for (row in panel.rows) {
                PendingRowCard(
                    row = row,
                    armed = panel.armed == row.path,
                    busy = panel.busy,
                    onRetry = { panel.onRetry(row.path) },
                    onAbandon = { panel.onAbandon(row.path) },
                    onCancelAbandon = panel.onCancelAbandon,
                )
            }

            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 只有一项时"全部重试"与那一项的「重试」是同一件事 —— 不画第二个同样的按钮。
                if (panel.rows.size > 1) {
                    TextButton(onClick = panel.onRetryAll, enabled = !panel.busy) {
                        Text("全部重试（一次一项）")
                    }
                }
                TextButton(onClick = panel.onClose) { Text("关闭") }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

/** 面板里的一行: 文件名 + 要写什么 + 为什么没写成 + 两个动作(放弃要二次确认)。 */
@Composable
private fun PendingRowCard(
    row: PendingRow,
    armed: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    onCancelAbandon: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.target, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.size(2.dp))
        // 完整路径逐字显示(规范路径里的 verbatim 前缀是排查时唯一对得上的东西)
        Text(
            row.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        Text(row.reason, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
            // 放弃本地改动 = 丢掉用户自己的决定: 第一次只把按钮变成「确认放弃」并说明后果,
            // 第二次(同一张)才真的丢。判定在 ViewModel 里(见 [GridViewModel.requestAbandon])。
            TextButton(onClick = onAbandon, enabled = !busy) {
                Text(
                    if (armed) "确认放弃" else "放弃本地改动",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (armed) {
            Text(
                "再点一次「确认放弃」就会把这一张在这台手机上的改动丢掉（电脑上不会有它的记录，" +
                    "一个请求都不会发）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onCancelAbandon) { Text("取消") }
        }
        HorizontalDivider()
    }
}
