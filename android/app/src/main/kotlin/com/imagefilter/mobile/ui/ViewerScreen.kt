// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.ViewerAction
import com.imagefilter.mobile.data.WriteAction
import kotlinx.coroutines.launch

/** 单指水平拖动多少 dp 算"切一张"。像素换算只在这里做一次(状态机只认像素)。 */
private val VIEWER_DRAG_THRESHOLD = 56.dp

/**
 * 查看器(看大图)—— M2′ 的价值点之一。
 *
 * 逻辑全在 [ViewerViewModel] 里(纯状态, JVM 单测覆盖); 这里只做三件事: 收状态、
 * 把用户动作转成方法调用、把图和文案画出来。所以这个文件里**没有一条业务判断** ——
 * 连"这一下拖动算不算切图"都由状态机的 `dragStep` 决定。
 *
 * 手势规则(规格 §8.2, 与 [ViewerViewModel.onTransform] 的注释逐字对应):
 *   · **决策态(1x)**: 单指水平拖动 = 上一张/下一张; 双击 = 1x↔2x;
 *   · **双指捏合** → 进入查看态;
 *   · **查看态(>1x)**: 单指 = 平移(**不切图**); 缩回 1x 自动贴边归位。
 *
 * 四个"绝不"都对应一次真实的坏结果:
 *   · **绝不在这里拼 URL** —— 全部来自 [ViewerState.displayUrl](由 `ApiClient.previewUrl` 拼);
 *   · **绝不预取高清** —— 预取只发默认档(高清是电脑全解码, 预取它等于每滑一张卡一次);
 *   · **绝不给白屏** —— 默认档失败画错误卡(有话+有动作), 高清失败退回默认档 + 一句提示;
 *   · **绝不自己判"写成了"** —— 星级/色标的成败只由服务端的逐项回报决定(见 [DecisionStore]);
 *     写失败时**不回滚、不前进**, 而是把那句话与重试摆到用户眼前(规格 §11 纪律③)。
 *
 * ⚠️ **本文件的手势部分没有真机验证**(本轮没有设备): 三个检测器(`detectTransformGestures`
 * / `detectTapGestures` / Coil 的加载)都是 Compose/Coil 的标准用法, 但"双指捏合与单指拖动
 * 不抢手势"这条**只能在真机上确认**, 见报告里的 UNVERIFIED 清单。
 */
@Composable
fun ViewerScreen(
    pairing: Pairing,
    /** 网格里的照片(`GridItem`), 顺序逐字保留。 */
    items: List<GridItem>,
    startIndex: Int,
    /**
     * 星级/色标的本地镜像与推送。**由网格建、由网格持有**(见 [GridScreen]) —— 它必须活得比
     * 查看器久: 关掉查看器就丢掉"哪张改了还没写成", 正是规格 §11 纪律③最怕的静默。
     */
    decisions: DecisionStore,
    onClose: () -> Unit,
    /**
     * 「回网格刷新」这个动作 —— 由网格提供(它有 `GridViewModel`)。查看器自己刷新不了目录。
     */
    onReloadFolder: () -> Unit,
    /** 当前看到第几张(给网格同步用; M2′ 的网格还没有"滚回那一格"的需求)。 */
    onIndexChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    // 与网格、与 ApiClient 共用同一枚 token 的**同一个** OkHttpClient(见 rememberAppImageLoader)
    val imageLoader = rememberAppImageLoader(pairing.token)
    val density = LocalDensity.current
    val thresholdPx = with(density) { VIEWER_DRAG_THRESHOLD.toPx() }
    val scope = rememberCoroutineScope()

    // key 里有 items: 换目录(items 变了)必须换一份状态, 不许把上一个目录的照片留在屏幕上。
    // List 的 equals 是结构比较 → "刷新后内容没变"不会重建状态机。
    val vm = remember(pairing, items, startIndex, decisions) {
        ViewerViewModel(
            pairing = pairing,
            items = items,
            startIndex = startIndex,
            dragThresholdPx = thresholdPx,
            // 预取走 Coil 的 enqueue(没有 target 的请求 = 只把图备进缓存)。
            // 它拿到的 URL 由状态机给, **一律是默认档**。
            prefetch = { urls ->
                urls.forEach { url ->
                    imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            },
            decisions = decisions,
        )
    }
    val state by vm.state.collectAsState()

    // 系统返回键 = 关掉查看器(它比网格那个 BackHandler 后注册, 所以先吃到返回键)
    BackHandler { onClose() }

    // 进查看器就预取邻张(默认档)
    LaunchedEffect(vm) { vm.open() }
    LaunchedEffect(state.index) { onIndexChange(state.index) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ViewerTopBar(
                title = state.title,
                canRequestHighRes = state.canRequestHighRes,
                highResLoading = state.highResLoading,
                onHighRes = { vm.enterHighRes() },
                // 跨张的"未同步 N 张"**带动作**: 没有动作的角标等于让用户知道出事了却做不了什么
                unsyncedLabel = state.unsyncedLabel,
                onRetryUnsynced = { scope.launch { vm.retryAllUnsynced() } },
                onClose = onClose,
            )

            // 画质上界必须明示(RAW 的默认档只是相机内嵌的预览)
            val hint = state.hint
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 平移出界时露出背景, 而不是让图飘到别的控件上
                    .clipToBounds()
                    // ① 缩放/平移。`detectTransformGestures` 对单指拖动也会回调(zoom=1),
                    //    所以"这一下算切图还是平移"的判定在状态机里(见 onTransform)。
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            vm.onTransform(zoom = zoom, panX = pan.x, panY = pan.y)
                        }
                    }
                    // ② 单击 = 关闭, 双击 = 1x↔2x; `onPress` 的边界正好是"一次手势"的边界,
                    //    用它把上一轮未达阈值的拖动清零(两次半程拖动不许加起来切一张)。
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                vm.onGestureStart()
                                tryAwaitRelease()
                                vm.onGestureEnd()
                            },
                            onTap = { onClose() },
                            onDoubleTap = { vm.toggleZoom() },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = state.mode.scale
                            scaleY = state.mode.scale
                            translationX = state.mode.offsetX
                            translationY = state.mode.offsetY
                        },
                ) {
                    // 默认档**永远画着**: 高清只是盖在它上面的一层。高清失败时这一层还在,
                    // 所以"退回默认档"不需要重新加载, 也不会闪一下白屏。
                    key(state.reloadNonce) {
                        AsyncImage(
                            model = state.defaultUrl,
                            imageLoader = imageLoader,
                            contentDescription = state.current?.displayName,
                            contentScale = ContentScale.Fit,
                            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                            error = ColorPainter(MaterialTheme.colorScheme.errorContainer),
                            onSuccess = { vm.onDefaultLoaded() },
                            onError = { scope.launch { vm.onDefaultFailed(it.result.throwable) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    val highResUrl = state.highResUrl
                    if (state.quality == ViewerQuality.HIGH_RES && highResUrl != null) {
                        AsyncImage(
                            model = highResUrl,
                            imageLoader = imageLoader,
                            contentDescription = state.current?.displayName,
                            contentScale = ContentScale.Fit,
                            onSuccess = { vm.onHighResLoaded() },
                            onError = { scope.launch { vm.onHighResFailed(it.result.throwable) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                val highResError = state.highResError
                when {
                    // 高清在解码: 明确告诉用户电脑在忙(实测最重的样本是个 50MB 线性 DNG)
                    state.highResLoading -> ViewerNotice(
                        loading = true,
                        text = "正在让电脑解码这一张…（RAW / 大 DNG 可能要十几秒，默认档的图还在）",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // 高清失败: 图已退回默认档, 这句话只是提示 + 一个动作
                    highResError != null -> ViewerNotice(
                        loading = false,
                        text = highResError.head() + "，" + highResError.text(),
                        modifier = Modifier.align(Alignment.TopCenter),
                        actionLabel = "再试一次高清",
                        onAction = { vm.enterHighRes() },
                        onDismiss = { vm.dismissHighResError() },
                    )

                    // 默认档在加载
                    state.loading && state.error == null -> ViewerNotice(
                        loading = true,
                        text = "正在取这张图的预览…",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                // 默认档**真的没图**: 错误卡(有话 + 有动作), 绝不是白屏
                val error = state.error
                if (error != null) {
                    ViewerErrorCard(
                        error = error,
                        onRetry = { vm.retryDefault() },
                        onReloadFolder = {
                            onReloadFolder()
                            onClose()
                        },
                        onClose = onClose,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
            }

            // ── 决策(星级/色标)的写入结果 ───────────────────────────────────
            //
            // 纪律③("未同步 ≠ 已同步")在查看器里的落点就在这几行: 失败要说清 + 给动作,
            // 批量重试的半成功要说清, 其余时候也要有一句"这台手机上的改动还没写进电脑"。
            val writeFailure = state.writeFailure
            val batchNote = state.lastBatchNote
            val guardNote = state.ratingGuardNote
            val unsyncedNote = state.unsyncedNote
            when {
                writeFailure != null -> WriteFailureCard(
                    head = state.writeHead.orEmpty(),
                    text = state.writeText.orEmpty(),
                    action = state.writeAction,
                    actionLabel = state.writeActionLabel.orEmpty(),
                    onRetry = { scope.launch { vm.retryCurrent() } },
                    onReloadFolder = {
                        onReloadFolder()
                        onClose()
                    },
                    onReconnect = onClose,
                    onDismiss = { vm.dismissWriteFailure() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                batchNote != null -> ViewerNotice(loading = false, text = batchNote)

                // 一次 1..5 星的点击被**拦下**了(不知道电脑上是几星) —— 必须说出来,
                // 否则用户只看到"点了没反应", 那比拒绝更坏。
                guardNote != null -> Text(
                    guardNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )

                unsyncedNote != null -> Text(
                    unsyncedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }

            // 底部: 星级 + 色标。**写完不自动前进**(M2′ 的查看器是手动切图的那一种),
            // 失败更不前进 —— 见 ViewerViewModel.commit 的注释。
            RatingBar(
                rating = state.rating,
                label = state.label,
                // 电脑上是几星(三态): 画在条上, 并且由 setRating 用来拦下一次瞎写。
                desktopRating = state.desktopRating,
                enabled = !state.writing,
                writing = state.writing,
                onRating = { stars -> scope.launch { vm.setRating(stars) } },
                onLabel = { label -> scope.launch { vm.setLabel(label) } },
            )
        }
    }
}

/**
 * 写入失败的卡: 一行标题 + 服务端那条码对应的话 + **那一个**动作。
 *
 * 动作字由 [ViewerState.writeActionLabel] 给(它查 `WriteMessages`), 这里一个字都不拼 ——
 * "重试写入"与"知道了（要去电脑上改）"要做的事不在一台设备上, 字必须不一样。
 */
@Composable
internal fun WriteFailureCard(
    head: String,
    text: String,
    action: WriteAction?,
    actionLabel: String,
    onRetry: () -> Unit,
    onReloadFolder: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // 只有"重试"需要一个额外的出口: 现在不方便重试的用户可以把这句话收起来,
                // **改动本身与"未同步"标记都留着**(dismiss 不会假装写成了)。
                if (action == WriteAction.RETRY) {
                    TextButton(onClick = onDismiss) { Text("知道了") }
                }
                TextButton(
                    onClick = when (action) {
                        WriteAction.RETRY -> onRetry
                        WriteAction.RELOAD_FOLDER -> onReloadFolder
                        // 手机上做不了重新配对(那要去目录页的「设置」)→ 关掉查看器, 那是唯一的下一步
                        WriteAction.RECONNECT -> onReconnect
                        WriteAction.PC_SIDE, WriteAction.DISMISS, null -> onDismiss
                    },
                ) { Text(actionLabel.ifBlank { "知道了" }) }
            }
        }
    }
}

/** 顶部: 文件名 · (i/N) + 「未同步 N 张」+「高清」+「关闭」。**高清是显式入口, 不是预取**。 */
@Composable
private fun ViewerTopBar(
    title: String,
    canRequestHighRes: Boolean,
    highResLoading: Boolean,
    onHighRes: () -> Unit,
    /** 非 null = 有"改了但电脑还没写成"的改动(纪律③的可见性; 跨张计数, 不只是当前这张)。 */
    unsyncedLabel: String?,
    onRetryUnsynced: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (highResLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
            }
            // 未同步角标**本身就是按钮**: 点一下把这一批重试一遍(没有动作的警告等于没警告)。
            if (unsyncedLabel != null) {
                TextButton(onClick = onRetryUnsynced) {
                    Text(unsyncedLabel, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onHighRes, enabled = canRequestHighRes) { Text("高清") }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}

/**
 * 顶部的一行状态(加载中 / 高清失败提示)。
 *
 * 高清失败那条**必须带动作**(用户的第一反应是"再点一次"), 而进度条那条不需要。
 */
@Composable
internal fun ViewerNotice(
    loading: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null || onDismiss != null) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("知道了") }
                    }
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}

/**
 * 默认档失败的卡: 一句话 + **那一个**动作。
 *
 * 动作字由 [ViewerError.actionLabel] 给(403 与 404 的字**不一样** —— 它们要做的事在
 * 两台不同的设备上), 所以这里不能用 `ErrorCard` 那个写死"重试"的版本。
 */
@Composable
internal fun ViewerErrorCard(
    error: ViewerError,
    onRetry: () -> Unit,
    onReloadFolder: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                error.head(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(error.text(), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = when (error.action) {
                    ViewerAction.RETRY -> onRetry
                    ViewerAction.RELOAD_FOLDER -> onReloadFolder
                    // 其余动作手机上都没有可做的事(去电脑上改 / 回目录页重新配对 / 放弃这一张),
                    // 按下去就回网格 —— 那是唯一能做下一步的地方。
                    ViewerAction.PC_SIDE, ViewerAction.RECONNECT, ViewerAction.DISMISS -> onClose
                },
            ) { Text(error.actionLabel) }
        }
    }
}
