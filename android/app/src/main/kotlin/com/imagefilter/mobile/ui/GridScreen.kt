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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    /**
     * **持久待重试队列**(M3′ Task 3) —— 与 [GridViewModel.decisions] 用的是**同一个实例**
     * (少了这一条, "电脑确认了"就没人把它从队列里去掉, 角标会一直挂着一个早就写成了的项)。
     *
     * `remember` 不带 key: DataStore 本来就是进程级单例, 换文件夹不该重建它 —— 重建只会多绕
     * 一圈读盘, 而且会让两页看到的队列不再是同一份。
     */
    val pending = remember { PendingStore.create(context) }
    // key 里带 folder.path: 换文件夹就是换一份状态, 不许把上一个文件夹的格子留在屏幕上
    val vm = remember(pairing, folder.path) { GridViewModel(pairing, folder, pending) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    // ImageLoader 与 ViewModel 共用同一枚 token 的**同一个** OkHttpClient(见它的 KDoc)
    val imageLoader = rememberAppImageLoader(pairing.token)
    // null = 没开查看器; 非 null = 正在看第几张。**不用任何文案判断查看器开没开**
    // (桌面端为此误判过两次: 文案是给人看的, 不能当状态)。
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    /** 待重试面板开着吗。它盖在网格**与**查看器/滑卡页之上([Box] 里的顺序)。 */
    var pendingOpen by remember { mutableStateOf(false) }
    /**
     * 滑卡那一批(**打开那一刻**筛出来的那些), null = 没开滑卡页。
     *
     * 为什么是**快照**而不是"每次重组都重新算": 滑卡会改星级/色标, 筛选条件(比如 ≥3★)会因此
     * 重新解释同一批照片 —— 若队列跟着变, 列表会在用户屁股底下缩短、下标会跳, 滑到一半就
     * 换了另一张。这一批就是"你现在要过的这一叠", 打开时定下, 关掉再进才重算。
     */
    var cullQueue by remember { mutableStateOf<List<GridItem>?>(null) }

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

    /**
     * **移动分类**(M3′ Task 4)的状态机 —— 与 [GridViewModel] 分开: 它管的是"选中的那几张"
     * 与"搬到哪、搬成了没有", 与"这个文件夹有什么照片"是两件事(混进 GridViewModel 只会让
     * 两个状态机的生命周期互相牵扯: 关掉移动流程不该丢掉网格的滚动与筛选)。
     *
     * `sourceDir` 传的是**当前文件夹**: 只用来说清"目标就是当前文件夹"那种白跑一趟的情形。
     */
    val moveVm = remember(pairing, folder.path) { MoveViewModel(pairing, sourceDir = folder.path) }
    val moveState by moveVm.state.collectAsState()

    /**
     * 目标文件夹选择器用的**浏览状态机** —— 就是浏览页那一个([BrowseViewModel])。
     *
     * 复用而不是另写一个"文件夹选择器": 目录树的口径只能有一份(另写一个迟早会出现
     * "浏览页点得开、选择器点不开"), 而且**选什么根本不该由手机决定** —— 服务端没有 Mkdir
     * 端点, 所以选择器里出现的每一个目标都必须是服务端真的回过的路径。
     */
    val pickerVm = remember(pairing) { BrowseViewModel(pairing) }
    val pickerState by pickerVm.state.collectAsState()

    /** 关掉整条移动流程: 真的搬过东西就先刷新这个文件夹(照片已经不在原处了)。 */
    fun closeMove() {
        if (moveState.summary != null) scope.launch { vm.loadAll() }
        moveVm.cancelSelecting()
    }

    // 系统返回键 = 回目录页。少这一条, 真机上按返回会直接退出 App(而界面里那个
    // 「返回」按钮还在)—— 这是这一页最容易漏掉的一条。
    BackHandler { onBack() }
    // 多选模式下返回键 = 退出多选(与顶栏那个「取消」同一件事)。它注册在上面那一条**之后**,
    // 所以在这一档里它优先(OnBackPressedDispatcher 是后注册者先收到)。
    BackHandler(enabled = moveState.selecting && moveState.stage == MoveStage.Idle) {
        moveVm.cancelSelecting()
    }

    // 进这个文件夹 = 文件列表 + **文件夹级的决策读取**(`GET /decisions?paths=…`)。
    // 只调一次 loadAll 是刻意的: 谁忘了第二步, 筛选栏就会安静地整页说"未统计"。
    LaunchedEffect(vm) { vm.loadAll() }

    // 关掉查看器/滑卡页回到网格那一刻: store 里可能刚多了"改了但没写成"或"已同步"的条目,
    // 抄一份进来(不碰网络) —— 否则角标与未同步边框会缺一块, 而那正是纪律③最怕的静默。
    LaunchedEffect(viewerIndex, cullQueue) {
        if (viewerIndex == null && cullQueue == null) {
            vm.refreshDecisions()
            // 那期间用户可能已经打出好几分(其中一些没写成、进了持久队列):
            // 角标数的必须是**那一刻**的事实, 不许拿进这一页时的旧数糊弄。
            vm.refreshPending()
        }
    }

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
            // 筛片队列 = **此刻**筛出来的那些(见 cullQueue 的 KDoc); 一张都没有时按钮是禁用的
            onOpenCull = { cullQueue = state.visibleItems },
            // 打开面板那一刻先解除武装(见 PendingPanel 的 KDoc)
            onOpenPending = { vm.cancelAbandon(); pendingOpen = true },
            // ── 移动(M3′ Task 4)────────────────────────────────────────────
            move = moveState,
            // 长按/点选: 没在多选里就先进入多选并选中这一格; 已经在多选里就切换它
            onCellSelect = { path ->
                if (!moveState.selecting) moveVm.startSelecting()
                moveVm.toggle(path)
            },
            onCellEnsureSelected = { path ->
                if (!moveState.selecting) moveVm.startSelecting()
                moveVm.select(path)
            },
            onStartMove = {
                moveVm.openPicker()
                // 懒加载: 第一次打开选择器才去读共享根(不用移动功能的用户一个请求都不多发);
                // 已经读到过的也不重拉 —— 电脑上新建了文件夹时由选择器里那个「刷新共享根」负责
                if (!pickerState.hasRoots && !pickerState.rootsLoading) {
                    scope.launch { pickerVm.loadRoots() }
                }
            },
            onCancelSelecting = { moveVm.cancelSelecting() },
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

        // 滑卡筛片(M3′ 的主动作): 同样是盖在网格上的一层。用的是**冻结的那一批**
        // ([cullQueue]), 与查看器同一个 store。
        val queue = cullQueue
        if (queue != null && queue.isNotEmpty()) {
            SwipeScreen(
                pairing = pairing,
                items = queue,
                startIndex = 0,
                decisions = decisions,
                // 滑卡页那个角标数的是**持久队列**里真的有的那些(它自己会一项一项地重数):
                // 关着电脑滑了三张, 角标必须当场变成 3 —— 而不是等关掉这一页才更新。
                pending = pending,
                onOpenPending = { vm.cancelAbandon(); pendingOpen = true },
                onClose = { cullQueue = null },
                onReloadFolder = { scope.launch { vm.loadAll() } },
            )
        }

        // 移动(挑目标 / 确认 / 结果)盖在网格之上。**注意**它只在真的开始挑目标之后才盖上来:
        // 多选模式下网格必须仍然可见可点(用户就是在网格上点照片的)。
        if (moveState.stage != MoveStage.Idle) {
            MoveSheet(
                MovePanel(
                    state = moveState,
                    picker = folderPickerModel(pickerState),
                    onPickRow = { path -> scope.launch { pickerVm.open(path) } },
                    onPickRetry = { path -> scope.launch { pickerVm.retry(path) } },
                    onRefreshRoots = { scope.launch { pickerVm.loadRoots() } },
                    // 目标只有一处来源: 选择器里那个**服务端回过的**路径(见 FolderPickerModel)
                    onPickThisFolder = {
                        pickerState.selected?.let { p -> moveVm.pickFolder(pickerState.entryFor(p)) }
                    },
                    onBackToPicker = { moveVm.backToPicker() },
                    // 顺序 = 网格顺序(与电脑上看到的一致); 服务端的逐项结果与请求一一对应。
                    // ⚠️ 给的是**这个文件夹的全部照片**的顺序, 不是筛出来的那些([GridState.visibleItems]):
                    // 筛选只是"看"的方式, 它没有资格把用户选中的照片从批次里减掉。发出去的是选中项
                    // **全体**(见 [MoveState.requestedPaths], FIX 1), 所以这里给全量顺序, 好让被筛选
                    // 挡住的那几张也按电脑上的次序排在请求里。
                    onConfirm = { scope.launch { moveVm.move(state.items.map { it.path }) } },
                    onRetryOne = { src -> scope.launch { moveVm.retryOne(src) } },
                    onRetryAll = { scope.launch { moveVm.retryAll() } },
                    onClose = { closeMove() },
                ),
            )
        }

        // 待重试面板: **最上面一层**(它盖住网格、查看器、滑卡页与移动流程)。放在最后画, 顺序就是层级。
        if (pendingOpen) {
            PendingSheet(
                panel = pendingPanel(state, vm, scope) {
                    // 关面板 = 解除武装(**下一次点「放弃本地改动」必须重新确认一次**)+ 真的收起来
                    vm.cancelAbandon()
                    pendingOpen = false
                },
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
    /** 打开**滑卡筛片**(M3′ 的主动作): 队列是此刻筛出来的那些, 由调用方冻结。 */
    onOpenCull: () -> Unit,
    /** 打开**待重试面板**([PendingSheet])。 */
    onOpenPending: () -> Unit,
    /** 移动那件事的界面状态(多选开没开、选了哪些)。 */
    move: MoveState,
    /** 点一格: 多选模式下 = 切换选中; 否则 = 打开查看器(那个分派就在 [PhotoGrid] 里一处)。 */
    onCellSelect: (String) -> Unit,
    /** 长按一格: **进入多选并选中它**(已经在多选里时是幂等的"选中", 不是"取消")。 */
    onCellEnsureSelected: (String) -> Unit,
    /** 顶栏「移动到…」。 */
    onStartMove: () -> Unit,
    /** 退出多选。 */
    onCancelSelecting: () -> Unit,
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
                    // **多选模式**下顶栏换成移动那几个动作(已选 N 张 / 移动到… / 取消) —— 筛片与
                    // 刷新在这个时候按下去只会让选中的那一批悄悄变样(筛片会改决策, 刷新会换列表)。
                    if (move.selecting) {
                        MoveSelectionActions(
                            state = move,
                            onStartMove = onStartMove,
                            onCancel = onCancelSelecting,
                        )
                    } else {
                        // 顶栏那**一个**角标(规格 §8.1): 显示什么与按下去做什么由 [topBarBadge]
                        // 一处决定 —— 待重试队列非空时它就是队列项数(按下去打开重试面板),
                        // 队列为空的窗口里退回"未同步 N 张·重试"(按下去把这一页未同步的推一遍)。
                        // 两句话**数的是同一个集合**, 所以不会出现"角标说 3、按下去重试 2 张"。
                        val badge = state.topBadge
                        if (badge != null) {
                            PendingBadgeButton(
                                badge = badge,
                                enabled = !state.pending.busy,
                                onOpenPanel = onOpenPending,
                                onRetryUnsynced = { scope.launch { vm.retryUnsynced() } },
                            )
                        }
                        // 滑卡筛片: **筛出来的那些**就是这一叠(队列在按下这一刻冻结, 见 GridScreen)。
                        // 一张可滑的都没有时禁用 —— 打开一个空队列只会给出一张白屏。
                        TextButton(onClick = onOpenCull, enabled = state.hasVisiblePhotos) { Text("筛片") }
                        TextButton(onClick = { scope.launch { vm.loadAll() } }) { Text("刷新") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 路径显示成**普通写法**(`F:\壁纸`, 去掉 Windows 的 verbatim 前缀)。
            // ⚠️ 只是**显示** —— 发出去/当 map 键用的永远是 folder.path 那份规范路径
            // (见 [displayPath] 的 KDoc)。少了这一行, 用户在自己的手机上会看到
            // 一个他只会在排障文档里见到的 `\\?\F:\壁纸`。
            Text(
                displayPath(folder.path),
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

            // **待重试记录读不回来**: 必须在页面上说出来。角标这时候是 0(读不到项), 而 0 会让
            // 用户以为"没有待重试的改动" —— 那是一句在数据可能已经丢了的时候最坏的安静。
            val pendingCorrupt = state.pendingCorruptedNotice
            if (pendingCorrupt != null) {
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    HintCard(body = pendingCorrupt)
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
                    // 星级"知不知道"的判据只有一处([GridState.ratingKnown]) —— 格子与读屏描述
                    // 都走它, 免得"读失败"在一个地方被画成"没评过"、在另一个地方被说成"未知"。
                    ratingKnown = state::ratingKnown,
                    imageLoader = imageLoader,
                    onCellClick = onOpenViewer,
                    // 多选模式: 点了是"选中/取消"而不是打开查看器(分派就在 PhotoGrid 里一处)
                    selecting = move.selecting,
                    selected = move.selected,
                    onCellSelect = onCellSelect,
                    onCellLongPress = onCellEnsureSelected,
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
 *
 * 多选(移动)那三件事在这里**只分派一次**: [selecting] 为 true 时"点"= 选中/取消,
 * 否则"点"= 打开查看器; 长按一律是"进入多选并选中"。分派散在调用方就会出现
 * "多选里点一下却打开了查看器"这种谁都没写、但一定会发生的事。
 */
@Composable
private fun ColumnScope.PhotoGrid(
    items: List<GridItem>,
    decisions: Map<String, Decision>,
    /** 这一格的星级知不知道([GridState.ratingKnown]) —— 判据在状态里, 这里只透传。 */
    ratingKnown: (String) -> Boolean,
    imageLoader: ImageLoader,
    onCellClick: (Int) -> Unit,
    /** 多选模式开着吗。 */
    selecting: Boolean = false,
    /** 选中的路径。 */
    selected: Set<String> = emptySet(),
    /** 多选里点一格(切换选中)。 */
    onCellSelect: (String) -> Unit = {},
    /** 长按一格(进入多选并选中)。 */
    onCellLongPress: (String) -> Unit = {},
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
                // "不知道"必须画得出来(它**不是**"没有星级") —— 判据只有一处。
                ratingKnown = ratingKnown(item.path),
                imageLoader = imageLoader,
                selecting = selecting,
                selected = item.path in selected,
                onClick = { if (selecting) onCellSelect(item.path) else onCellClick(index) },
                onLongClick = { onCellLongPress(item.path) },
            )
        }
    }
}
