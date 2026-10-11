// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.MoveMessages
import com.imagefilter.mobile.data.MoveStatus

/**
 * **移动分类**(M3′ Task 4)的界面: 挑目标文件夹 → 确认 → 逐项结果与重试。
 *
 * 逻辑一行都不在这里: 请求、重试计划、每一句话、能不能重试全在 [MoveViewModel] / `Move.kt` 的
 * 纯函数里(都有 JVM 用例)。所以这个文件只做三件事: 收状态、把用户动作转成方法调用、把字画出来。
 *
 * 四条来自契约的约束, 在界面上都看得见:
 *   · **目标只能从"电脑上真的存在的文件夹"里挑** —— 选择器用的是浏览页那棵树
 *     ([BrowseViewModel] + [visibleRows] + [TreeItemRow]), 一行都不是本地编的; 而
 *     「手机端不能新建文件夹」这条限制**常驻显示**([MoveMessages.NO_MKDIR]) —— 服务端没有
 *     Mkdir 端点, 装作支持只会让用户到处找那个不存在的按钮;
 *   · **确认句说清"绝不覆盖"**([moveConfirmText]): 目标已有同名文件时电脑报冲突、原样留在原地;
 *   · **逐项结果分组显示**, 半成功**绝不**被"成功"盖住([MoveRow.head] / [MoveRow.text]);
 *   · **重试只重发 `pendingSidecars`**(按钮的字与动作都由 [MoveRow.retryRequest] 决定 ——
 *     半成功项的照片已经搬走了, 它的 src 永远不会出现在任何请求里)。
 *
 * ⚠️ **本文件的观感没有真机验证**(本轮没有设备, 也不为此引 Compose UI 测试依赖):
 * 覆盖层的高度、长文案换行、深色主题下的对比只能真机看(报告里的 UNVERIFIED 清单)。
 * 但它画的每一个字都来自有 JVM 用例钉住的纯函数。
 */

/**
 * 面板要的全部输入与动作, 一个对象传完(与 [PendingPanel] 同一种做法: 少传一个参数就是某个
 * 动作安静地不生效)。
 */
class MovePanel(
    val state: MoveState,
    /** 目标选择器要看的一切(共享根 + 已展开的那一层 + 每一步的加载/失败)。 */
    val picker: FolderPickerModel,
    /** 点一行: 选中它就等于"展开它并把它当候选目标"([BrowseViewModel.open] 的语义)。 */
    val onPickRow: (String) -> Unit,
    /** 某一层读失败之后的重试。 */
    val onPickRetry: (String) -> Unit,
    /** 共享根没读出来时的重试。 */
    val onRefreshRoots: () -> Unit,
    /** 「移到这里」: 把当前选中的目录定成目标。 */
    val onPickThisFolder: () -> Unit,
    /** 从确认退回选择器(换个目标)。 */
    val onBackToPicker: () -> Unit,
    /** 「确认移动」。 */
    val onConfirm: () -> Unit,
    /** 重试**某一项**的边车。 */
    val onRetryOne: (String) -> Unit,
    /** 「重试全部仍然脱钩的」。 */
    val onRetryAll: () -> Unit,
    /** 关掉整条移动流程(退出多选模式)。 */
    val onClose: () -> Unit,
)

/**
 * 全屏覆盖层。
 *
 * 为什么是整屏而不是 [androidx.compose.material3.ModalBottomSheet]: 选择器里是一棵**目录树**,
 * 它需要高度(而且展开一层会长出新行) —— 与查看器/滑卡页同一种做法(它们也是盖在网格上的整屏层)。
 */
@Composable
fun MoveSheet(panel: MovePanel) {
    val state = panel.state
    // 系统返回键: 确认那一步退回选择器, 其余一律退出移动(与界面上那两个按钮同一套语义)
    BackHandler {
        if (state.stage == MoveStage.Confirming) panel.onBackToPicker() else panel.onClose()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(titleOf(state.stage), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.selectionLabel,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = panel.onClose) { Text("关闭") }
            }
            Spacer(Modifier.height(8.dp))

            // 批次级失败(整批 4xx/5xx / 断网): 单独一档, 与逐项结果分得清
            val error = state.error
            if (error != null) {
                ErrorCard(head = "电脑没有接受这次移动", text = error, onRetry = panel.onConfirm)
                Spacer(Modifier.height(8.dp))
            }

            when (state.stage) {
                MoveStage.Picking -> FolderPicker(panel)
                MoveStage.Confirming -> ConfirmMove(panel)
                MoveStage.Running -> LoadingRow("正在让电脑搬这些照片（先照片、后边车）…")
                MoveStage.Result -> MoveResult(panel)
                MoveStage.Idle -> HintCard(
                    body = "还没开始：先回网格点几张照片，再按「移动到…」。",
                )
            }
        }
    }
}

private fun titleOf(stage: MoveStage): String = when (stage) {
    MoveStage.Idle -> "移动照片"
    MoveStage.Picking -> "选一个已有的目标文件夹"
    MoveStage.Confirming -> "确认移动"
    MoveStage.Running -> "正在移动"
    MoveStage.Result -> "移动结果"
}

/** 目标选择器: **浏览页那棵树**, 一行都不是本地编的。 */
@Composable
private fun ColumnScope.FolderPicker(panel: MovePanel) {
    val picker = panel.picker

    // 这条限制**常驻**: 服务端没有 Mkdir, 手机端就是不能新建文件夹。
    HintCard(body = picker.limitation)
    Spacer(Modifier.height(4.dp))
    Text(MoveMessages.NO_DELETE, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))

    val rootsError = picker.rootsError
    when {
        // 已有根时刷新失败只提示, **不清空**眼前这棵树(与浏览页同一条)
        rootsError != null && !picker.hasRoots -> {
            ErrorCard(head = "共享根没读出来", text = rootsError.text(), onRetry = panel.onRefreshRoots)
            return
        }

        picker.rootsLoading && !picker.hasRoots -> {
            LoadingRow("正在读电脑上的共享根…")
            return
        }

        !picker.hasRoots -> {
            HintCard(body = BrowseMessages.NO_SHARES)
            Spacer(Modifier.height(12.dp))
            Button(onClick = panel.onRefreshRoots) { Text("刷新共享根") }
            return
        }
    }

    if (rootsError != null) {
        ErrorCard(
            head = "共享根没刷新出来（下面这份是上次读到的）",
            text = rootsError.text(),
            onRetry = panel.onRefreshRoots,
        )
        Spacer(Modifier.height(8.dp))
    }

    // weight: 树吃掉剩下的高度(少了它, LazyColumn 会按内容撑出屏幕)
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(picker.rows, key = { it.entry.path }) { row ->
            TreeItemRow(
                row = row,
                expanded = row.entry.path in picker.expanded,
                loading = row.entry.path in picker.loading,
                errorText = picker.errors[row.entry.path]?.text(),
                // 点一行 = 选中并展开它(懒加载由 BrowseViewModel 负责, 只拉这一层)
                onClick = { panel.onPickRow(row.entry.path) },
                onRetry = { panel.onPickRetry(row.entry.path) },
            )
        }
    }

    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val pickPath = picker.pickPath
        Text(
            if (pickPath == null) "点上面任意一行选目标" else "目标：${picker.pickName}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = panel.onPickThisFolder, enabled = picker.canPick) { Text("移到这里") }
    }
    val pickPath = picker.pickPath
    if (pickPath != null) {
        Text(
            pickPath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 确认那一步: 说清"多少张、去哪、绝不覆盖"。 */
@Composable
private fun ColumnScope.ConfirmMove(panel: MovePanel) {
    val state = panel.state
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(state.confirmText.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = panel.onBackToPicker, enabled = !state.busy) { Text("换一个文件夹") }
        Button(onClick = panel.onConfirm, enabled = !state.busy) { Text("确认移动") }
    }
}

/** 逐项结果 + 重试。**半成功自己一档**, 绝不折进"成功"里。 */
@Composable
private fun ColumnScope.MoveResult(panel: MovePanel) {
    val state = panel.state
    val note = state.note
    if (note != null) {
        HintCard(body = note)
        Spacer(Modifier.height(8.dp))
    }

    // 只有真的还有待补边车时才画「重试全部」—— 一个永远摆着的按钮会让人以为还有事没做完
    if (state.retryableCount > 1) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = panel.onRetryAll, enabled = !state.retryBusy) {
                Text("重试全部仍然脱钩的（一次一项）")
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(state.rows, key = { it.src }) { row ->
            MoveResultRow(row = row, busy = state.retryBusy, onRetry = { panel.onRetryOne(row.src) })
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = panel.onClose) { Text("完成") }
    }
}

/** 结果里的一行: 状态 + 现在在哪 + 重试边车。 */
@Composable
private fun MoveResultRow(row: MoveRow, busy: Boolean, onRetry: () -> Unit) {
    val half = row.displayStatus == MoveStatus.HALF_SUCCESS
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.head,
                style = MaterialTheme.typography.titleSmall,
                color = if (half) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(row.fileName, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.size(2.dp))
        Text(row.src, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val dest = row.dest
        if (dest != null) {
            Text(
                "→ $dest",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(row.text, style = MaterialTheme.typography.bodySmall)
        val retryNote = row.retryNote
        if (retryNote != null) {
            Spacer(Modifier.size(2.dp))
            Text(retryNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (row.canRetry) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRetry, enabled = !busy) { Text("重试边车") }
            }
        }
        HorizontalDivider()
    }
}

/**
 * 顶栏在多选模式下的那几个动作。
 *
 * 与 [PendingBadgeButton] 同一条纪律: **显示什么 = 按下去做什么**(数量与动作都在这里一处决定),
 * 免得出现"角标说 3、按下去只移 2 张"这种分裂。
 */
@Composable
fun MoveSelectionActions(
    state: MoveState,
    onStartMove: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.selectionLabel, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(4.dp))
        TextButton(onClick = onStartMove, enabled = state.canStartMove) { Text("移动到…") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}
