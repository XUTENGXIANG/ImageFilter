// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.WriteAction
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * **滑卡筛片**(M3′ 的主动作) —— 一次手势做完一张的取舍。
 *
 * 逻辑**一行都不在这里**: 手势 → 方向 → [decideSwipe] → 写入, 全在 [ViewerViewModel.swipe] 与
 * `Swipe.kt` 的纯函数里; 状态、预取、失败卡复用 M2′ 的查看器那一套。所以这个文件只做三件事:
 * 收状态、把手势翻译成"方向 + 松手", 把卡片与反馈画出来。
 *
 * 复用的四条(计划 Task 1 的硬要求):
 *   · **同一个 [DecisionStore]**(由网格持有、传进来): 筛选、角标、滑卡必须是**同一份**真相;
 *   · **同一个预取器**([ViewerViewModel.prefetchUrls], 只把前向窗口开到 2 张 —— 规格 §8.2
 *     "当前张 + 后 2 张") —— **绝不**另写一套, 而且预取**永远是默认档**(高清 = 电脑全解码,
 *     预取它等于每滑一张卡一次);
 *   · **同一个自动前进开关**([swipeViewModel] 里的 `advanceOnWriteSuccess = true`) ——
 *     写成功才前进, **失败绝不前进**(那会让用户以为打过了);
 *   · **同一张失败卡**([WriteFailureCard])与同一句"未同步 N 张"(纪律③)。
 *
 * 手势规则(规格 §8.2 写死, 判据与查看器同一处 [isViewingMode]):
 *   · 默认态: **单指水平拖动** = 滑卡, 卡片跟手; 拖过**屏宽 25%**松手才决策, 没过**回弹**;
 *   · **双指捏合** → 进入查看态, 此时单指 = 平移, **滑卡禁用**;
 *   · 缩回原始比例(1x)→ 恢复滑卡;
 *   · 长按**不做**特别的事: 色标面板**一直**在底部([RatingBar]), 少一个会跟滑卡抢手势的检测器。
 *
 * ⚠️ **本文件的手势部分没有真机验证**(本轮没有设备, 也不为本任务引入 Compose UI 测试依赖):
 * `awaitEachGesture` 那一段是标准用法, 但"单指不抖成捏合、双指不误触发滑卡、回弹跟手"只能
 * 在真机上确认, 见报告里的 UNVERIFIED 清单。**语义**那一段(这一滑到底写什么)有 100+ 条
 * JVM 用例钉着。
 *
 * M3′ Task 2 在这一页右上角加了**撤销**(多步, 内存栈上限 [UNDO_LIMIT], 见 [UndoStack]):
 * 滑卡是高速连续操作, 误操作的代价比点星级大得多 —— 撤销的语义(恢复哪一份意图、要不要写回
 * 电脑)全在 [ViewerViewModel.undo] 与 [DecisionStore.undo] 里, 这里只有按钮与"还剩几步"。
 */
@Composable
fun SwipeScreen(
    pairing: Pairing,
    /** 要滑的那些照片 —— **筛出来的**那一批(顺序逐字保留)。 */
    items: List<GridItem>,
    startIndex: Int = 0,
    /** 星级/色标的本地镜像。**由网格建、由网格持有**(它必须活得比这一页久)。 */
    decisions: DecisionStore,
    onClose: () -> Unit,
    /**
     * 「回网格刷新」这个动作 —— 由网格提供(它有 `GridViewModel`)。这一页自己刷新不了目录。
     */
    onReloadFolder: () -> Unit,
) {
    val context = LocalContext.current
    // 与网格、与 ApiClient 共用同一枚 token 的**同一个** OkHttpClient(见 rememberAppImageLoader)
    val imageLoader = rememberAppImageLoader(pairing.token)
    val density = LocalDensity.current
    // 阈值 = 屏宽 25%(规格 §8.2); 换算只在这一处做一次(纯状态里拿不到屏幕宽度)
    val thresholdPx = with(density) {
        swipeThresholdPx(LocalConfiguration.current.screenWidthDp.dp.toPx())
    }
    val scope = rememberCoroutineScope()

    /** 手势语义(左 = 废弃 / 右 = 保留 / 只写星级是**默认**; 用户可在这一页改)。 */
    var config by remember { mutableStateOf(DEFAULT_SWIPE_CONFIG) }

    /** 手指带动的水平偏移(卡片跟手画它; 松手清零 = 回弹)。 */
    var dragX by remember { mutableStateOf(0f) }

    // key 里有 items: 换了这一批(网格里换了筛选条件)必须换一份状态, 不许把上一批留在屏幕上。
    val vm = remember(pairing, items, startIndex, decisions, thresholdPx) {
        // 参数**只在这一处定**(见 Swipe.kt 的 swipeViewModel): 自动前进 + 后 2 张默认档预取。
        // 那份配置本身有 JVM 单测盯着, 所以它不是"只有真机才知道对不对"的一段。
        swipeViewModel(
            pairing = pairing,
            items = items,
            startIndex = startIndex,
            thresholdPx = thresholdPx,
            decisions = decisions,
            // 预取走 Coil 的 enqueue(没有 target 的请求 = 只把图备进缓存)。
            // URL 由状态机给, **一律是默认档**。
            prefetch = { urls ->
                urls.forEach { url ->
                    imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            },
        )
    }
    val state by vm.state.collectAsState()

    // 系统返回键 = 关掉滑卡页(它比网格那个 BackHandler 后注册, 所以先吃到返回键)
    BackHandler { onClose() }

    // 进来就把下一张(默认档)拉起来, 免得第一滑之后要等图
    LaunchedEffect(vm) { vm.open() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SwipeTopBar(
                title = state.title,
                unsyncedLabel = state.unsyncedLabel,
                undoCount = state.undoCount,
                // 撤销 = 把上一步决策恢复回去(多步, 内存栈上限 UNDO_LIMIT, 见 UndoStack)。
                // ⚠️ 写入在飞时它会被禁用([SwipeTopBar] 里判 writing): 撤销与写入并发会让
                // "电脑上到底是哪一版"变成一句话说不清的事。
                onUndo = { scope.launch { vm.undo() } },
                writing = state.writing,
                onRetryUnsynced = { scope.launch { vm.retryAllUnsynced() } },
                onClose = onClose,
            )

            SwipeConfigRow(config = config, onConfig = { config = it })

            // ── 卡片栈(手势区) ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    // 单指水平拖动 = 滑卡; 双指捏合 = 进查看态(此后单指平移, 滑卡禁用)。
                    // **一个**检测器管完整场手势(从按下到松手): "松手才决策"是规格要求,
                    // 而两个检测器叠在一起必然互相抢事件(那正是 §8.2 点名要避免的事)。
                    // key 用 `vm`: 它换了(换了这一批)才重建手势; `dragX` 每帧都在变,
                    // 绝不能把它放进 key —— 那会在拖动中途把这一场手势掐掉。
                    .pointerInput(vm) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = true)
                            var dragged = 0f
                            var pinched = false
                            var released = false
                            while (!released) {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                when {
                                    // ① 双指(或它残留的比例变化)→ 缩放 / 进出查看态
                                    zoom != 1f -> {
                                        pinched = true
                                        dragged = 0f
                                        dragX = 0f
                                        vm.onTransform(zoom = zoom, panX = 0f, panY = 0f)
                                    }
                                    // ② 查看态: 单指**平移**, 绝不滑卡(缩回 1x 时 applyZoom 自动归位)
                                    pinched || vm.state.value.isViewing ->
                                        vm.onTransform(zoom = 1f, panX = pan.x, panY = pan.y)
                                    // ③ 决策态: 卡片跟手
                                    else -> {
                                        dragged += pan.x
                                        dragX = dragged
                                    }
                                }
                                if (event.changes.none { it.pressed }) {
                                    released = true
                                } else {
                                    // 这一下由我们管: 全部吃掉, 免得别的控件跟着动
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            // 松手: 过阈值才决策, 没过就回弹(卡片跟手回到原位)
                            val direction = if (pinched || vm.state.value.isViewing) {
                                null
                            } else {
                                swipeDirectionFor(dragged, thresholdPx)
                            }
                            dragX = 0f
                            // 写入在飞的时候不再收一次决策(与底部按钮同一条纪律: 别自己制造乱序)
                            if (direction != null && !vm.state.value.writing) {
                                scope.launch { vm.swipe(direction, config) }
                            }
                        }
                    },
            ) {
                val next = state.photos.getOrNull(state.index + 1)
                if (next != null) {
                    // 后面那张: 只画个轮廓(它在 Coil 里已经被预取, 所以滑过去是瞬时的)
                    SwipeCardBack(photo = next, url = vm.defaultUrlAt(state.index + 1).orEmpty(), imageLoader = imageLoader)
                }
                val current = state.current
                if (current != null) {
                    SwipeCard(
                        photo = current,
                        url = state.displayUrl,
                        imageLoader = imageLoader,
                        retryNonce = state.reloadNonce,
                        onLoaded = { vm.onDefaultLoaded() },
                        onFailed = { scope.launch { vm.onDefaultFailed(it) } },
                        modifier = Modifier.graphicsLayer {
                            translationX = dragX
                            // 轻微旋转: 纯观感。没有它, 卡片更像"平移的图片"而不是"被甩出去的卡片"
                            rotationZ = dragX / 60f
                        },
                    )
                }

                // 拖到哪一侧就预告那一侧的动作(过阈值前后都看得见"松手会发生什么")
                val dragging = swipeDirectionFor(dragX, thresholdPx) ?: when {
                    dragX > 0f -> SwipeDirection.Right
                    dragX < 0f -> SwipeDirection.Left
                    else -> null
                }
                if (dragging != null && thresholdPx > 0f) {
                    SwipeBadge(
                        direction = dragging,
                        action = config.actionFor(dragging),
                        fade = (abs(dragX) / thresholdPx).coerceIn(0f, 1f),
                    )
                }

                // 这一张的图**真的没取到**: 有话 + 有动作, 绝不是白屏(与查看器同一张卡)
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
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }

                if (state.loading && error == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }

            // 画质上界必须明示(与查看器同一句话: RAW 的默认档只是相机内嵌的那张小预览)
            val hint = state.hint
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }

            // ── 写入结果: 失败卡 > 滑卡那句话 > 撤销那句话 > "未同步"短提示 ──
            // (滑卡与撤销两句话**共用同一格**: 后发生的那次操作会清掉前一句 —— 两句话都在讲
            //  同一张照片刚刚发生了什么, 同时显示必然有一句是过期的。)
            val writeFailure = state.writeFailure
            val swipeNote = state.swipeNote
            val undoNote = state.undoNote
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

                // "已保留 · 保持 5★" —— 命中"不降级"那一支时**没有写入**, 但必须给出反馈
                swipeNote != null -> Text(
                    swipeNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )

                // "已撤销" —— 只有服务端确认了才会说这句(见 UndoStack.undoMessage)
                undoNote != null -> Text(
                    undoNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )

                unsyncedNote != null -> Text(
                    unsyncedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }

            // 底部: 星级 + 色标(滑卡做三态取舍, 星级做细分) —— 与查看器同一个组件。
            // ⚠️ 这一页开了 advanceOnWriteSuccess, 所以**手点**星级/色标也会在写成功后前进:
            // 那是 M2′ 为滑卡页装的规则("打完一张自动到下一张"), 真机上顺不顺手见 UNVERIFIED。
            RatingBar(
                rating = state.rating,
                label = state.label,
                enabled = !state.writing,
                writing = state.writing,
                onRating = { stars -> scope.launch { vm.setRating(stars) } },
                onLabel = { label -> scope.launch { vm.setLabel(label) } },
            )
        }
    }
}

/**
 * 后面那张卡的**轮廓**: 同一张图、缩一点、压暗 —— 让"下面还有"这件事看得见,
 * 也让下一张提前进 Coil 的缓存(它的 URL 与显示用的那张**逐字同源**, 所以不会多拉一次)。
 */
@Composable
private fun SwipeCardBack(photo: ViewerPhoto, url: String, imageLoader: ImageLoader) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .graphicsLayer {
                scaleX = 0.94f
                scaleY = 0.94f
            },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            // 读屏只念当前这张(下面那张是"还有一张"的视觉暗示, 不是内容)
            contentDescription = null,
            contentScale = ContentScale.Fit,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 当前这张卡: 图 + 文件名(读屏念得到是哪一张)。 */
@Composable
private fun SwipeCard(
    photo: ViewerPhoto,
    url: String,
    imageLoader: ImageLoader,
    retryNonce: Int,
    onLoaded: () -> Unit,
    onFailed: (Throwable?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // key(retryNonce): 「重试」要真的重建这一张(URL 不变时 Coil 不会自己重发)
            key(retryNonce) {
                AsyncImage(
                    model = url,
                    imageLoader = imageLoader,
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Fit,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.errorContainer),
                    onSuccess = { onLoaded() },
                    onError = { onFailed(it.result.throwable) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                photo.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 拖动方向上的那枚角标(保留 = 绿 / 废弃 = 红)。
 *
 * 位置跟着**手指方向**走(与照片浏览器的普遍直觉一致): 向右拖时章盖在左上, 向左拖盖在右上。
 * 章上的字来自 [SwipeAction], 所以配成"左右互换"时它会跟着换 —— **绝不**写死"右滑=保留"。
 */
@Composable
private fun BoxScope.SwipeBadge(direction: SwipeDirection, action: SwipeAction, fade: Float) {
    val keep = action == SwipeAction.Keep
    Surface(
        color = if (keep) Color(0xFF2E7D32) else Color(0xFFC62828),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .align(if (direction == SwipeDirection.Right) Alignment.TopStart else Alignment.TopEnd)
            .padding(20.dp)
            .graphicsLayer { alpha = fade },
    ) {
        Text(
            actionWord(action),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 顶部: 文件名 · (i/N) + 「撤销 N」+「未同步 N 张」+「关闭」。 */
@Composable
private fun SwipeTopBar(
    title: String,
    unsyncedLabel: String?,
    undoCount: Int,
    onUndo: () -> Unit,
    writing: Boolean,
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
            // 撤销: **步数看得见**(为 0 / 正在写时禁用)—— 一个永远可点却什么都不做的按钮
            // 比没有这个按钮更坏。
            TextButton(onClick = onUndo, enabled = undoCount > 0 && !writing) {
                Text(if (undoCount > 0) "撤销 $undoCount" else "撤销")
            }
            // 未同步角标**本身就是按钮**: 点一下把这一批重试一遍(没有动作的警告等于没警告)
            if (unsyncedLabel != null) {
                TextButton(onClick = onRetryUnsynced) {
                    Text(unsyncedLabel, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}

/**
 * 手势语义那一排: 当前映射的一句话 + 三档写入方式 + 左右互换。
 *
 * 配置**可改**就必须**看得见**: 改了不显示的话, 用户只能靠试才知道现在右滑写什么。
 */
@Composable
private fun SwipeConfigRow(config: SwipeConfig, onConfig: (SwipeConfig) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            swipeLegend(config),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (mode in SwipeMode.entries) {
                val selected = mode == config.mode
                TextButton(onClick = { onConfig(config.copy(mode = mode)) }) {
                    Text(if (selected) "● ${modeName(mode)}" else modeName(mode))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onConfig(config.reversed()) }) { Text("左右互换") }
        }
    }
}
