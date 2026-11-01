// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.ui.components.AppDivider
import com.imagefilter.mobile.ui.components.AppEmptyState
import com.imagefilter.mobile.ui.components.AppErrorCard
import com.imagefilter.mobile.ui.components.AppHint
import com.imagefilter.mobile.ui.components.AppIconButton
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppLargeHeader
import com.imagefilter.mobile.ui.components.AppLoadingRow
import com.imagefilter.mobile.ui.components.AppPathText
import com.imagefilter.mobile.ui.components.AppStatusPill
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.components.AppTone
import com.imagefilter.mobile.ui.theme.AppRadius
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import kotlinx.coroutines.launch

/**
 * 目录浏览页(规格 §8.1 的"浏览"): 抽屉放共享根 + 目录树, 主区只是入口。
 *
 * **点一行文件夹 = 直接看这个文件夹的照片**; 想继续往下翻目录, 点行首的箭头展开。
 * 所以这一页不再有"选中目录 → 名字/路径/打开照片"的中间页 —— 那一步被删掉了。
 *
 * 逻辑全在 [BrowseViewModel] 里(纯状态, JVM 单测覆盖); 这里只做三件事: 收状态、
 * 把用户动作转成方法调用、把文案画出来。所以这个文件里**没有一条业务判断**。
 *
 * 三条来自契约的约束, 都在界面上看得见:
 *   · **懒加载** —— 点箭头才 `browse()`, 且只有这一层。加载中画占位行, 不假装已拿到;
 *   · **共享根为空是合法状态** —— 服务端的有意安全默认, 界面必须给出"去电脑上添加共享根"
 *     这条动作, 而不是转圈或空白;
 *   · **张数不在这一层装** —— `browse` 给的 `photoCount` 一律是 0(只有 `scan` 才有真数),
 *     所以 0 显示成"张数未统计", 绝不写成"0 张"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    pairing: Pairing,
    /** 由 AppRoot 持有: 从网格返回时目录树不塌回去。 */
    vm: BrowseViewModel,
    onOpenFolder: (FolderEntryDto) -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val p = LocalPhotoPalette.current

    LaunchedEffect(vm) {
        // VM 活得比这一页久(从网格返回时还是同一个): 已经有根就不再重复拉,
        // 保住已经展开的树。空共享根/上次失败时下一次进来会再试一次。
        if (!state.hasRoots && !state.rootsLoading && state.rootsError == null) {
            vm.loadRoots()
        }
    }

    // 从网格返回时, 树还开着(VM 持久化) —— 直接把抽屉打开, 用户回到刚才的位置,
    // 少点一次「打开目录」。首次进入时 expanded 为空, 不会自动弹。
    LaunchedEffect(Unit) {
        if (state.expanded.isNotEmpty() && !drawer.isOpen) drawer.open()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = p.scrim,
        drawerContent = {
            BrowseDrawer(
                pairing = pairing,
                state = state,
                onToggle = { scope.launch { vm.open(it) } },
                onOpenPhotos = onOpenFolder,
                onRetry = { scope.launch { vm.retry(it) } },
                onRefreshRoots = { scope.launch { vm.loadRoots() } },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(p.s0),
        ) {
            AppLargeHeader(
                title = "浏览",
                navigation = {
                    AppIconButton(
                        icon = AppIcons.Menu,
                        contentDescription = "目录",
                        onClick = { scope.launch { drawer.open() } },
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcons.Settings,
                        contentDescription = "设置",
                        onClick = onSettings,
                    )
                },
            )
            BrowseBody(
                state = state,
                modifier = Modifier.weight(1f),
                onRefreshRoots = { scope.launch { vm.loadRoots() } },
                onOpenDrawer = { scope.launch { drawer.open() } },
            )
        }
    }
}

@Composable
private fun BrowseBody(
    state: BrowseState,
    modifier: Modifier = Modifier,
    onRefreshRoots: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.page),
    ) {
        val rootsError = state.rootsError
        // 顺序要紧: 已有根时刷新失败只提示错误, **不清空**用户眼前的树。
        if (rootsError != null && state.hasRoots) {
            AppErrorCard(
                head = "共享根没刷新出来（下面这份是上次读到的）",
                text = rootsError.text(),
                onRetry = onRefreshRoots,
            )
            Spacer(Modifier.height(AppSpacing.md))
        }
        when {
            rootsError != null && !state.hasRoots -> {
                AppErrorCard(
                    head = "共享根没读出来",
                    text = rootsError.text(),
                    onRetry = onRefreshRoots,
                )
            }

            state.rootsLoading && !state.hasRoots -> {
                AppLoadingRow("正在读电脑上的共享根…")
            }

            !state.hasRoots -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AppEmptyState(
                    icon = AppIcons.Folder,
                    title = "电脑上还没有共享目录",
                    body = BrowseMessages.NO_SHARES,
                    actionLabel = "刷新",
                    onAction = onRefreshRoots,
                )
            }

            // 主区只留一个入口: 选目录在抽屉里完成, 点一行就直接进照片。
            else -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AppEmptyState(
                    icon = AppIcons.Folder,
                    title = "选择一个文件夹",
                    actionLabel = "打开目录",
                    onAction = onOpenDrawer,
                )
            }
        }
    }
}

/**
 * 左侧抽屉 —— 共享根 + 目录树。
 *
 * 它是**浮层**（有遮罩、从左侧进），所以用 S0 + 右侧 28dp 圆角; 里面不再套卡片,
 * 行本身靠 44dp 行高与旋转箭头表达层级。`windowInsets = WindowInsets(0)` 是刻意的:
 * 系统栏避让由里面的 [topBarInsets]/[bottomBarInsets] 各做一次, 两层都做会多出一倍留白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseDrawer(
    pairing: Pairing,
    state: BrowseState,
    /** 点箭头: 展开/收起这一层(只加载这一层)。 */
    onToggle: (String) -> Unit,
    /** 点行: 直接打开这个文件夹的照片。 */
    onOpenPhotos: (FolderEntryDto) -> Unit,
    onRetry: (String) -> Unit,
    onRefreshRoots: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 340.dp),
        drawerShape = RoundedCornerShape(
            topEnd = AppRadius.sheet,
            bottomEnd = AppRadius.sheet,
        ),
        drawerContainerColor = p.s0,
        drawerContentColor = p.textPrimary,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .topBarInsets()
                .bottomBarInsets(),
        ) {
            DrawerHeader(pairing = pairing)
            AppDivider()

            if (state.hasRoots) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    // 左右各 4dp: 行自己的 40dp 箭头仍然从 12dp 开始, 不贴边。
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    val rows = visibleRows(state.roots, state.index, state.expanded)
                    items(rows, key = { it.entry.path }) { row ->
                        TreeItemRow(
                            row = row,
                            expanded = row.entry.path in state.expanded,
                            loading = state.isLoading(row.entry.path),
                            errorText = state.errorAt(row.entry.path)?.text(),
                            onClick = {
                                // 有照片就直进网格; 一张照片都没有、只有子文件夹时, 点行 = 展开
                                // —— 否则用户会掉进一个必然空白的网格, 还得再退回来。
                                if (row.entry.photoCount == 0 && row.entry.subfolderCount > 0) {
                                    onToggle(row.entry.path)
                                } else {
                                    onOpenPhotos(row.entry)
                                }
                            },
                            onRetry = { onRetry(row.entry.path) },
                            onToggle = { onToggle(row.entry.path) },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
                ) {
                    if (state.rootsLoading) {
                        AppLoadingRow("正在读共享根…")
                    } else {
                        AppHint(BrowseMessages.NO_SHARES)
                    }
                }
            }

            AppDivider()
            AppTextButton(
                text = "刷新共享根",
                onClick = onRefreshRoots,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(AppSpacing.sm))
        }
    }
}

/** 抽屉抬头: 已配对 + 备注 + 地址。token 是机密, 一个字都不显示。 */
@Composable
private fun DrawerHeader(pairing: Pairing) {
    val p = LocalPhotoPalette.current
    Column(
        modifier = Modifier.padding(
            start = AppSpacing.page,
            end = AppSpacing.lg,
            top = AppSpacing.xl,
            bottom = AppSpacing.lg,
        ),
    ) {
        AppStatusPill(text = "已配对", tone = AppTone.Accent)
        Spacer(Modifier.height(AppSpacing.md))
        Text(
            text = pairing.note.ifBlank { "这台电脑" },
            style = MaterialTheme.typography.headlineSmall,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        AppPathText(path = pairing.url, maxLines = 1)
    }
}
