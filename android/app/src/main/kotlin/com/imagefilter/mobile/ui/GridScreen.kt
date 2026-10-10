// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 照片网格(M1′ 的价值点): 选中文件夹 → 缩略图铺满屏幕。
 *
 * 逻辑全在 [GridViewModel] 里(纯状态, JVM 单测覆盖); 这里只做三件事: 收状态、
 * 把用户动作转成方法调用、把图和文案画出来。所以这个文件里**没有一条业务判断** ——
 * 连"顶部写几个字"都来自 [GridState.countLabel]。
 *
 * 四条来自契约的约束, 都在这里看得见:
 *   · **每个格子固定尺寸**(`aspectRatio(1f)` + `GridCells.Adaptive(96.dp)`):
 *     绝不让图片决定高度 —— 否则一屏格子随图片长宽比抖动, 滚动时行高乱跳;
 *   · **缩略图与 JSON 共用同一个带鉴权头的 OkHttpClient**(见 [rememberAppImageLoader]):
 *     图片端点同样要 Bearer, 各建一个客户端就是"JSON 通、图片 401";
 *   · **`v=<mtime>` 在 URL 上**(由 [GridItem.thumbnailUrl] 拼): 电脑上换掉照片后
 *     Coil 的缓存必须失效, 否则用户看到的是旧图;
 *   · **不手写预取**: Coil 对 `LazyGrid` 可见区已经做了该做的事, 桌面端那套
 *     IntersectionObserver 是 Web 的补偿手段, 在 Compose 里是多余的。
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
     * 星级/色标的本地镜像 —— **建在这里, 由网格持有**。
     *
     * 为什么不是建在查看器里: 它的寿命必须比查看器长。放进查看器的话, 用户一旦关掉查看器
     * (或切了张)这份"哪张改了、哪张没写成"就随 ViewModel 一起没了 —— 而规格 §11 纪律③
     * 要的正是"未同步 ≠ 已同步"**看得见**。放在网格这一层, 它就与"这个文件夹开着"同寿。
     *
     * 它自己那个 [ApiClient] 与网格/查看器/Coil 共用**同一个 OkHttpClient**
     * ([ApiClient] 的默认参数走 `SharedHttpClient.forToken`), 所以"JSON 通、图片 401"那种
     * 分裂在结构上不成立。M2′ Task 3 的筛选与网格角标直接用它。
     */
    val decisions = remember(pairing) {
        DecisionStore(ApiClient(pairing.url, pairing.token))
    }

    // 系统返回键 = 回目录页。少这一条, 真机上按返回会直接退出 App(而界面里那个
    // 「返回」按钮还在)—— 这是这一页最容易漏掉的一条。
    BackHandler { onBack() }

    LaunchedEffect(vm) { vm.load() }

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
        if (index != null && state.items.isNotEmpty()) {
            ViewerScreen(
                pairing = pairing,
                items = state.items,
                startIndex = index,
                decisions = decisions,
                onClose = { viewerIndex = null },
                // 「回网格刷新」这个动作只有网格做得了(它有 GridViewModel)
                onReloadFolder = { scope.launch { vm.load() } },
            )
        }
    }
}

/** 网格本体(顶栏 + 错误卡 + 格子)。查看器是它的**上层**, 见 [GridScreen]。 */
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
                    TextButton(onClick = { scope.launch { vm.load() } }) { Text("刷新") }
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
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )

            val error = state.error
            if (error != null) {
                // 失败**就画在当前内容之上**, 而且不清空已经拿到的格子(下面照画)
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ErrorCard(
                        head = "这个文件夹的照片没读出来",
                        text = error.text(),
                        onRetry = { scope.launch { vm.load() } },
                    )
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

                state.hasPhotos -> PhotoGrid(
                    items = state.items,
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
 * 它自己会从同一个 `items` 里取。
 */
@Composable
private fun ColumnScope.PhotoGrid(
    items: List<GridItem>,
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
            GridCell(item = item, imageLoader = imageLoader, onClick = { onCellClick(index) })
        }
    }
}

/**
 * 一格。固定正方形 + `ContentScale.Crop` —— 缩略图是服务端按长边降采样出来的,
 * 宽高比各不相同, 不 Crop 就会在格子里留白或变形。
 *
 * 占位是**纯色块**(Coil 的 `placeholder`/`error` 两个 painter): M1′ 不为观感引入
 * shimmer 动画与额外算力。
 */
@Composable
private fun GridCell(item: GridItem, imageLoader: ImageLoader, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            imageLoader = imageLoader,
            // 读屏时要能听懂这是哪一张(文件名就是用户在电脑上看到的那个名字)
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
