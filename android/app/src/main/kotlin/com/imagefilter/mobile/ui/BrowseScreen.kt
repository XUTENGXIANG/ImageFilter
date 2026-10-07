// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import kotlinx.coroutines.launch

/**
 * 目录浏览页(规格 §8.1 的"浏览"): 抽屉放共享根 + 目录树, 主区放当前目录。
 *
 * 逻辑全在 [BrowseViewModel] 里(纯状态, JVM 单测覆盖); 这里只做三件事: 收状态、
 * 把用户动作转成方法调用、把文案画出来。所以这个文件里**没有一条业务判断**。
 *
 * 三条来自契约的约束, 都在界面上看得见:
 *   · **懒加载** —— 点开才 `browse()`, 且只有这一层。加载中画占位行, 不假装已拿到;
 *   · **共享根为空是合法状态** —— 服务端的有意安全默认, 界面必须给出"去电脑上添加共享根"
 *     这条动作, 而不是转圈或空白;
 *   · **张数不在这一层装** —— `browse` 给的 `photoCount` 一律是 0(只有 `scan` 才有真数),
 *     所以 0 显示成"张数未统计", 绝不写成"0 张"。
 *
 * 主区在 M1′ Task 5 会被缩略图网格替换(同一个 `selected` 目录), 现在先把"选目录"
 * 这条链路走通。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    pairing: Pairing,
    onOpenFolder: (FolderEntryDto) -> Unit,
    onSettings: () -> Unit,
) {
    val vm = remember(pairing) { BrowseViewModel(pairing) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(vm) { vm.loadRoots() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("电脑上的目录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        // 备注/地址只是"我在连哪台电脑"; token 是机密, 一个字都不显示。
                        Text(
                            pairing.note.ifBlank { pairing.url },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                    if (state.hasRoots) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            val rows = visibleRows(state.roots, state.index, state.expanded)
                            items(rows, key = { it.entry.path }) { row ->
                                TreeItemRow(
                                    row = row,
                                    expanded = row.entry.path in state.expanded,
                                    loading = state.isLoading(row.entry.path),
                                    errorText = state.errorAt(row.entry.path)?.text(),
                                    onClick = { scope.launch { vm.open(row.entry.path) } },
                                    onRetry = { scope.launch { vm.retry(row.entry.path) } },
                                )
                            }
                        }
                    } else {
                        HintCard(
                            body = BrowseMessages.NO_SHARES,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = { scope.launch { vm.loadRoots() } },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text("刷新共享根")
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titleOf(state)) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawer.open() } }) { Text("目录") }
                    },
                    actions = { TextButton(onClick = onSettings) { Text("设置") } },
                )
            },
        ) { padding ->
            MainRegion(
                state = state,
                modifier = Modifier.padding(padding),
                onOpen = { scope.launch { vm.open(it) } },
                onRetry = { scope.launch { vm.retry(it) } },
                onRefreshRoots = { scope.launch { vm.loadRoots() } },
                onOpenFolder = onOpenFolder,
            )
        }
    }
}

private fun titleOf(state: BrowseState): String =
    state.selected?.let { displayNameOf(it) } ?: "浏览"

@Composable
private fun MainRegion(
    state: BrowseState,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRefreshRoots: () -> Unit,
    onOpenFolder: (FolderEntryDto) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        val rootsError = state.rootsError
        when {
            // 顺序要紧: 已有根时刷新失败只提示错误, **不清空**用户眼前的树
            rootsError != null && !state.hasRoots -> {
                ErrorCard(
                    head = "共享根没读出来",
                    text = rootsError.text(),
                    onRetry = onRefreshRoots,
                )
            }

            state.rootsLoading && !state.hasRoots -> LoadingRow("正在读电脑上的共享根…")

            !state.hasRoots -> {
                HintCard(body = BrowseMessages.NO_SHARES)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefreshRoots) { Text("刷新") }
            }

            state.selected == null -> {
                Text(BrowseMessages.PICK_A_FOLDER, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点左上角「目录」，展开电脑上共享的目录树，再点一个目录看它的内容。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> SelectedFolder(
                state = state,
                onOpen = onOpen,
                onRetry = onRetry,
                onOpenFolder = onOpenFolder,
            )
        }

        if (rootsError != null && state.hasRoots) {
            Spacer(Modifier.height(12.dp))
            ErrorCard(
                head = "共享根没刷新出来（下面这份是上次读到的）",
                text = rootsError.text(),
                onRetry = onRefreshRoots,
            )
        }
    }
}

/** 已选中的目录: 标题 + 原路径 + 进网格的入口 + 它的**直接**子目录。 */
@Composable
private fun ColumnScope.SelectedFolder(
    state: BrowseState,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenFolder: (FolderEntryDto) -> Unit,
) {
    val path = state.selected ?: return
    val entry = state.entryFor(path)

    Text(entry.name, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    // 路径逐字显示(含 verbatim 前缀): 它是这个目录的**身份**, 也是排查时唯一对得上的东西。
    Text(
        path,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = { onOpenFolder(entry) }) { Text("打开这个文件夹的照片") }
    Spacer(Modifier.height(16.dp))

    val error = state.selectedError
    when {
        error != null -> ErrorCard(head = "这个目录没读出来", text = error.text(), onRetry = { onRetry(path) })

        state.selectedLoading -> LoadingRow("正在读这个目录…")

        // 200 + 空数组: 空目录**不是错误**, 与 403/404 分开说(规格要求的那条区分)
        state.selectedChildren.isEmpty() -> Text(
            BrowseMessages.EMPTY_DIR,
            style = MaterialTheme.typography.bodyMedium,
        )

        else -> {
            Text("子目录（${state.selectedChildren.size}）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            // weight: 子目录列表吃掉剩下的高度。少了它, LazyColumn 会按内容撑出去
            // (它拿到的是 Column 的整个 maxHeight, 不是"剩下的那部分")。
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.selectedChildren, key = { it.path }) { child ->
                    TreeItemRow(
                        row = TreeRow(child, depth = 0, isRoot = false),
                        expanded = child.path in state.expanded,
                        loading = state.isLoading(child.path),
                        errorText = state.errorAt(child.path)?.text(),
                        onClick = { onOpen(child.path) },
                        onRetry = { onRetry(child.path) },
                    )
                }
            }
        }
    }
}
