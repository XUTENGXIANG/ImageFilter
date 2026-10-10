// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 照片网格(M1′ 的价值点 + M2′ 的筛选与角标): 选中文件夹 → 缩略图铺满屏幕。
 *
 * 逻辑全在 [GridViewModel] 里(纯状态, JVM 单测覆盖); 这里只做三件事: 收状态、
 * 把用户动作转成方法调用、把图和文案画出来。所以这个文件里**没有一条业务判断** ——
 * 连"顶部写几个字"都来自 [GridState.countLabel], "当前显示 N / 共 M 张"来自
 * [GridState.shownLabel], "哪些格子留下"来自 [GridState.visibleItems]。
 *
 * 五条来自契约的约束, 都在这里看得见:
 *   · **每个格子固定尺寸**(`aspectRatio(1f)` + `GridCells.Adaptive(96.dp)`):
 *     绝不让图片决定高度 —— 否则一屏格子随图片长宽比抖动, 滚动时行高乱跳;
 *   · **缩略图与 JSON 共用同一个带鉴权头的 OkHttpClient**(见 [rememberAppImageLoader]):
 *     图片端点同样要 Bearer, 各建一个客户端就是"JSON 通、图片 401";
 *   · **`v=<mtime>` 在 URL 上**(由 [GridItem.thumbnailUrl] 拼): 电脑上换掉照片后
 *     Coil 的缓存必须失效, 否则用户看到的是旧图;
 *   · **不手写预取**: Coil 对 `LazyGrid` 可见区已经做了该做的事, 桌面端那套
 *     IntersectionObserver 是 Web 的补偿手段, 在 Compose 里是多余的;
 *   · **筛选之后进查看器, 左右切图只在筛出来的那些里走**(查看器收的是
 *     [GridState.visibleItems], 不是全部照片) —— 否则用户会把被筛掉的照片一张张翻出来,
 *     而他明明刚说过"只想看 ≥3 星的"。
 *
 * 点击格子打开**查看器**(M2′): 它是盖在网格上的一层(下面的网格保持在场), 所以关掉查看器
 * 时滚动位置、已经取到的缩略图都还在。查看器自己的逻辑全在 [ViewerViewModel] 里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(
    pairing: Pairing,
    folder: FolderEntryDto,
    onBack: () -> Unit,
) {
    // key 里带 folder.path: 换文件夹就是换一份状态, 不许把上一个文件夹的格子留在屏幕上
    val vm = remember(pairing, folder.path) { GridViewModel(pairing, folder) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    // ImageLoader 与 ViewModel 共用同一枚 token 的**同一个** OkHttpClient(见它的 KDoc)
    val imageLoader = rememberAppImageLoader(pairing.token)
    // null = 没开查看器; 非 null = 正在看第几张。**不用任何文案判断查看器开没开**
    // (桌面端为此误判过两次: 文案是给人看的, 不能当状态)。
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    /**
     * 星级/色标的本地镜像 —— **由 ViewModel 持有, 由查看器借用**。
     *
     * 它必须活得比查看器久: 放进查看器的话, 用户一旦关掉查看器(或切了张)这份"哪张改了、
     * 哪张没写成"就随 ViewModel 一起没了 —— 而规格 §11 纪律③要的正是"未同步 ≠ 已同步"
     * **看得见**。放在 ViewModel 里, 它的寿命就是"这个文件夹开着"。
     *
     * 筛选、角标与查看器用的是**同一份**(这是本任务的一条硬要求): 建两份的话, 格子上显示
     * 4 星而查看器里显示 2 星只是时间问题。
     */
    val decisions = vm.decisions

    // 系统返回键 = 回目录页。少这一条, 真机上按返回会直接退出 App(而界面里那个
    // 「返回」按钮还在)—— 这是这一页最容易漏掉的一条。
    BackHandler { onBack() }

    // 进这个文件夹 = 文件列表 + **文件夹级的决策读取**(`GET /decisions?paths=…`)。
    // 只调一次 loadAll 是刻意的: 谁忘了第二步, 筛选栏就会安静地整页说"未统计"。
    LaunchedEffect(vm) { vm.loadAll() }

    // 关掉查看器回到网格那一刻: store 里可能刚多了"改了但没写成"或"已同步"的条目,
    // 抄一份进来(不碰网络) —— 否则角标与未同步边框会缺一块, 而那正是纪律③最怕的静默。
    LaunchedEffect(viewerIndex) { if (viewerIndex == null) vm.refreshDecisions() }

    // Box 是为了让查看器盖在网格之上(而不是替换掉网格 —— 替换会把滚动位置丢掉)
    Box(modifier = Modifier.fillMaxSize()) {
        GridContent(
            vm = vm,
            state = state,
            folder = folder,
            imageLoader = imageLoader,
            onBack = onBack,
            scope = scope,
            onOpenViewer = { index -> viewerIndex = index },
        )

        val index = viewerIndex
        // 查看器看的是**筛出来的**那些(不是全部照片): 左右切图不许切到被筛掉的照片
        val visible = state.visibleItems
        if (index != null && visible.isNotEmpty()) {
            ViewerScreen(
                pairing = pairing,
                items = visible,
                // 防御性夹一下: 打开期间若决策刷新让列表变了, 也不许越界崩掉
                startIndex = index.coerceIn(0, visible.lastIndex),
                decisions = decisions,
                onClose = { viewerIndex = null },
                // 「回网格刷新」这个动作只有网格做得了(它有 GridViewModel)
                onReloadFolder = { scope.launch { vm.loadAll() } },
            )
        }
    }
}

/** 网格本体(顶栏 + 筛选栏 + 错误卡 + 格子)。查看器是它的**上层**, 见 [GridScreen]。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridContent(
    vm: GridViewModel,
    state: GridState,
    folder: FolderEntryDto,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    scope: CoroutineScope,
    onOpenViewer: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            folder.name.ifBlank { folder.path },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 张数来自 scan 的返回长度 —— 失败且一张都没有时它说的是"没读到", 不是"0 张"
                        Text(state.countLabel, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    // 顶栏的**待重试角标**(规格 §8.1), 与查看器同一个入口、同一句文案。
                    // 它本身是按钮: 没有动作的角标等于让用户知道出事了却做不了什么。
                    val unsynced = state.unsyncedLabel
                    if (unsynced != null) {
                        TextButton(onClick = { scope.launch { vm.retryUnsynced() } }) {
                            Text(unsynced, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { scope.launch { vm.loadAll() } }) { Text("刷新") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 路径逐字显示(含 verbatim 前缀): 它是这个目录的**身份**, 也是排查时唯一对得上的东西。
            Text(
                folder.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            )

            // 筛选栏(星级 / 色标 / 未同步, 规格 §8.1)。三个维度上的张数由 FilterCounts 给 ——
            // 读不到电脑上原有的值时它说"未统计", 不拿 0 顶上。
            FilterBar(
                f = state.filters,
                counts = state.counts,
                shown = state.visibleItems.size,
                total = state.items.size,
                onChange = { vm.setFilters(it) },
            )
            Spacer(Modifier.height(4.dp))

            val error = state.error
            if (error != null) {
                // 失败**就画在当前内容之上**, 而且不清空已经拿到的格子(下面照画)
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ErrorCard(
                        head = "这个文件夹的照片没读出来",
                        text = error.text(),
                        onRetry = { scope.launch { vm.loadAll() } },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 决策读取失败: **单独一张卡**, 与上面的文件列表失败分开 —— 照片照样能看,
            // 受影响的是筛选栏上的张数与角标(所以正文必须说清这件事, 见 ReadMessages)。
            val readFailure = state.readFailure
            if (readFailure != null) {
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ReadFailureCard(
                        head = state.readFailureHead.orEmpty(),
                        text = state.readFailureText.orEmpty(),
                        action = state.readFailureAction,
                        actionLabel = state.readFailureActionLabel.orEmpty(),
                        onRetry = { scope.launch { vm.loadDecisions() } },
                        // 「回目录页重新配对」在网格里能做的那一步就是返回(重新配对在目录页的「设置」)
                        onReconnect = onBack,
                        onDismiss = { vm.clearReadFailure() },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 重新推送"未同步"那一批的**批次级**结论(半成功必须说出来, 纪律②)
            val batchNote = state.lastBatchNote
            if (batchNote != null) {
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    HintCard(body = batchNote)
                }
                Spacer(Modifier.height(8.dp))
            }

            // 全 RAW 的说明(不是错误: 有照片, 只是缩略图那条链路不一样)
            val notice = state.notice
            if (notice != null) {
                HintCard(body = notice, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(8.dp))
            }

            when {
                // 顺序要紧: 有错误时**不画**"正在读…"(否则用户以为还在转)
                state.loading && !state.hasPhotos -> Box(modifier = Modifier.padding(12.dp)) {
                    LoadingRow("正在读这个文件夹里的照片…")
                }

                state.isEmpty -> Box(modifier = Modifier.padding(12.dp)) {
                    HintCard(body = state.emptyMessage ?: GRID_EMPTY)
                }

                // **有照片但全被筛掉**是另一句话: 说成"没有照片"会让用户以为照片丢了
                state.allFilteredOut -> Box(modifier = Modifier.padding(12.dp)) {
                    HintCard(body = state.filteredOutNotice ?: filteredOutMessage(state.items.size))
                }

                state.hasVisiblePhotos -> PhotoGrid(
                    items = state.visibleItems,
                    decisions = state.decisions,
                    imageLoader = imageLoader,
                    onCellClick = onOpenViewer,
                )

                // 失败且一张都没有: 上面那张错误卡就是全部内容, 这里不再画第二句话
                else -> Unit
            }
        }
    }
}

/**
 * 照片网格本体。**不要**在这里加预取逻辑(见 [GridScreen] 的 KDoc)。
 *
 * 是 [ColumnScope] 的扩展, 只为一件事: `Modifier.weight(1f)` 只有在这个作用域里才存在
 * (与 [BrowseScreen] 的 `SelectedFolder` 同一个写法)。
 *
 * `onCellClick` 收的是**下标**(不是格子): 查看器需要"从第几张开始", 而格子本身的数据
 * 它自己会从同一个 `items` 里取 —— 那个下标因此是**筛出来的列表**里的下标, 与查看器拿到
 * 的列表是同一个, 这正是"筛选后切图不会切到被筛掉的照片"的实现方式。
 */
@Composable
private fun ColumnScope.PhotoGrid(
    items: List<GridItem>,
    decisions: Map<String, Decision>,
    imageLoader: ImageLoader,
    onCellClick: (Int) -> Unit,
) {
    LazyVerticalGrid(
        // Adaptive(96.dp): 窄屏两列、平板多列 —— 列数不写死, 但每格是正方形
        columns = GridCells.Adaptive(minSize = 96.dp),
        // weight(1f): 网格吃掉剩下的高度。少了它, 网格会按内容撑出屏幕(滚不动)
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // key = 路径: Coil 的缓存与滚动位置都跟着它, 刷新后同一张图不会被当成新格子
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            GridCell(
                item = item,
                // 决策来自**唯一**那份真相的快照(star/色标角标与未同步边框都靠它)
                decision = decisions[item.path],
                imageLoader = imageLoader,
                onClick = { onCellClick(index) },
            )
        }
    }
}
