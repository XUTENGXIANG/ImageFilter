// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiError
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.DecisionReadFailure
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.ReadAction
import com.imagefilter.mobile.data.ReadMessages
import com.imagefilter.mobile.data.ReadSource
import com.imagefilter.mobile.data.ScannedPhotoDto
import com.imagefilter.mobile.data.ThumbnailSize
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.apiCodeFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

// ── 纯函数: 格子的推导(与 Compose 无关, 每个都有 JVM 单测) ─────────────────────
//
// 与 BrowseViewModel 同一条纪律: 安卓 UI 测试在本项目里没有依赖(也不该为 M1′ 引入),
// 所以"哪些格子、每格画哪个 URL、顶部写几个字、失败说什么"必须是**可以真的断言**的
// 纯函数, 而不是散在 Composable 里的一堆 when。

/**
 * 网格里的一格。
 *
 * **URL 在构造时就定下来**: 它由 [ApiClient.thumbnailUrl] 拼(路径的百分号编码、
 * `size` 闭集、`v=<mtime>` 三件事都在那一处, 见 Task 2 的单测) —— 格子里不许自己
 * 拼字符串, 否则 verbatim 前缀里的字面问号会在这里被弄丢。
 */
data class GridItem(
    /** 规范路径(不透明, 逐字来自服务端; 进 URL 与以后进查看器都用它)。 */
    val path: String,
    val fileName: String,
    val isRaw: Boolean,
    val isVideo: Boolean,
    /** 文件在电脑上的修改时间 —— 它就是 URL 上 `v=` 的来源(Coil 的缓存失效键)。 */
    val modifiedAt: Long,
    val thumbnailUrl: String,
) {
    /** 显示名: 服务端给了 `fileName` 就用它, 空则退回路径(绝不显示空白格子)。 */
    val displayName: String get() = fileName.ifBlank { path }
}

/** 网格请求的档位 —— 规格 §5.2 的默认档。**只有这一处**决定它。 */
val GRID_THUMBNAIL_SIZE: ThumbnailSize = ThumbnailSize.S300

/**
 * `scan` 的结果 → 格子列表。**顺序逐字保留服务端的顺序**(`scan_directory` 按文件名
 * 不区分大小写排过序), 手机端不重排 —— 用户看到的顺序必须与电脑上一致且稳定。
 *
 * 这里刻意**不过滤** RAW / 视频: `scan` 返回什么就能看什么。过滤是 [applyFilters] 的事 ——
 * 在客户端再筛一遍格式只会让"看到的东西"与"电脑上有的东西"对不上。
 */
fun gridItems(api: ApiClient, photos: List<ScannedPhotoDto>): List<GridItem> = photos.map { p ->
    GridItem(
        path = p.path,
        fileName = p.fileName,
        isRaw = p.isRaw,
        isVideo = p.isVideo,
        modifiedAt = p.modifiedAt,
        thumbnailUrl = api.thumbnailUrl(p.path, GRID_THUMBNAIL_SIZE, p.modifiedAt),
    )
}

/**
 * 顶部张数。
 *
 * 网格的张数来自 `scan` 的**返回长度** —— 这里的 0 就是真的 0 张。
 * 浏览页现在也显示 `browse` 给的直接照片数，不再有"0 = 未统计"那一层。
 */
fun gridCountLabel(count: Int): String = "$count 张"

/**
 * 顶部的张数在**失败且一张都没读到**时的说法。
 *
 * 这一条是刻意的: 用 `items.size` 直接显示会在加载失败的那一屏写"0 张", 而那是
 * 把"没读到"说成"没有照片" —— 与浏览页不许把未统计说成"0 张"是同一条纪律的反面。
 */
const val GRID_COUNT_UNKNOWN = "张数没读到"

/** 200 但 `scan` 返回 0 张 —— 空文件夹/没有支持的文件**不是错误**, 但绝不能画一个空白网格。 */
const val GRID_EMPTY = "电脑扫描这个文件夹返回 0 张照片。可能它本来就没有照片，也可能里面的文件" +
    "格式不在支持范围（JPG / PNG / HEIC / RAW 与常见视频之外的文件不算），或者电脑读不动这个目录。" +
    "若电脑上明明有照片，确认它还在共享根里、并且能在电脑上打开，然后点「刷新」。"

/** 全是 RAW 时的说明 —— 缩略图链路与 JPEG 不同(不是错误, 但用户会看到"慢"与灰块, 得先说清)。 */
const val GRID_ALL_RAW = "这里全是 RAW：缩略图优先用电脑上的缩略图缓存，取不到时才现场解码，" +
    "所以第一遍可能比较慢；个别取不到图的格子会显示成灰块。"

/**
 * 全 RAW 提示。**只看照片** —— 服务端对空文件夹给的是空数组, 那种情况走 [GRID_EMPTY],
 * 两句话不能混(一句说"没有照片", 另一句说"有照片但慢", 混了必然有一边是错的)。
 */
fun allRawNotice(items: List<GridItem>): String? =
    if (items.isNotEmpty() && items.all { it.isRaw }) GRID_ALL_RAW else null

// ── 状态 ─────────────────────────────────────────────────────────────────────

/**
 * 网格页里那一份**待重试队列**的界面状态(M3′ Task 3)。
 *
 * 数出来的每一张都是"电脑还没确认过的"([PendingStore] 里真的有的那些), **不是猜的**:
 * 重启之后本地镜像是空的, 而队列里还躺着昨晚关着电脑打的那三张 —— 角标必须以队列为准。
 */
data class PendingState(
    val items: List<Decision> = emptyList(),
    /**
     * true = 队列**没能读回来**(解析不了 / 读不动)。面板必须把这件事说出来 ——
     * 一次读失败不许被当成"没有待重试的改动"(见 [PendingSnapshot.corrupted])。
     */
    val corrupted: Boolean = false,
    /**
     * 「放弃本地改动」武装在哪一张上([AbandonConfirm])。武装 ≠ 丢弃: 第一次点只是把按钮变成
     * 「确认放弃」, 真正丢掉用户的决定要第二次点在同一张上。
     */
    val abandonArmed: String? = null,
    /** 正在重试(串行, 一次一项): 期间不许再点, 免得自己制造乱序。 */
    val busy: Boolean = false,
    /** 上一次重试/放弃的结论(如实转述电脑说了什么)。 */
    val note: String? = null,
) {
    /** **待重试的项数** —— 角标与面板数的是同一个数。 */
    val count: Int get() = items.size

    val badge: String? get() = pendingBadgeText(count)

    /** 面板的每一行(文案全来自 [com.imagefilter.mobile.data.WriteMessages])。 */
    val rows: List<PendingRow> get() = toPendingRows(items)
}

/**
 * 网格页的全部状态。**不含任何安卓/Compose 类型** —— 所以它能在 JVM 单测里被真的走一遍。
 *
 * 三个"空"必须分得清(这一页最容易犯的错就是把它们混成一个转圈或一张空白网格):
 *   · [loading] —— 还没回来, 画占位;
 *   · [error] —— 失败了, 说人话 + 动作(文案查 [BrowseMessages], 与浏览页同一套码);
 *   · [isEmpty] —— 真的 0 张, 说 [GRID_EMPTY];
 *   · [allFilteredOut] —— **有**照片, 只是全被筛掉了, 说 [filteredOutMessage]
 *     (与 [GRID_EMPTY] 是两句不同的话: 混了用户会以为照片丢了)。
 *
 * 决策(星级/色标)与它的同步状态来自 [DecisionStore](**唯一**的真相来源): 这里只存一份
 * **快照**([decisions]), 由 [GridViewModel] 在每次读/写之后抄进来 —— 网格不自己缓存第二份,
 * 否则"角标上的星"与"查看器里的星"会各自漂移。
 */
data class GridState(
    /** 正在看哪个文件夹(路径是不透明的, 逐字来自服务端)。 */
    val folder: FolderEntryDto,
    val items: List<GridItem> = emptyList(),
    /**
     * 合并之后的决策快照(本地意图优先), 按路径。
     *
     * 只有"本地动过或远端读到过"的路径在里面 —— 查不到的路径用 `null` 表示"不知道",
     * **不是**"0 星/没有色标"。
     */
    val decisions: Map<String, Decision> = emptyMap(),
    /**
     * 服务端**回报过**的路径([DecisionStore.knownPaths] 的快照)。
     *
     * [decisions] 里"没有值"的路径有两种, 而它们必须分得开: "服务端说这一张没有星级"与
     * "这台手机不知道"。少了这一份知识, 一次失败的读取会让一张 4/5 星的照片画成空星星
     * (与"没评过"无法分辨), 而用户在手机上的一次打分是一次**绝对赋值**。
     */
    val knownPaths: Set<String> = emptySet(),
    /**
     * 这个文件夹的决策**知不知道**。
     *
     * false 的三种情形(它们都会让筛选栏的张数说"未统计", 而不是拿已知的那些去冒充全部):
     *   · 还没读过(刚开始加载);
     *   · 读失败了(网络/HTTP 错误) —— 见 [readFailure];
     *   · 服务端 200 但**没把问到的每一张都回报回来**(见 [ReadMessages.INCOMPLETE]);
     * 还有第四种: 文件列表本身没读到([error] 非空)时, 我们连"这个文件夹现在有哪些照片"都不知道。
     */
    val decisionsKnown: Boolean = false,
    /** 当前筛选条件(三个维度, 规格 §8.1)。 */
    val filters: Filters = Filters(),
    /** 初始就是 true: 进目录先画"正在读…", 而不是先给一个空白网格。 */
    val loading: Boolean = true,
    val error: FolderError? = null,
    /**
     * **文件夹级决策读取**的失败 —— 单独一档, 与文件列表的 [error] 分开。
     *
     * 分开的理由: 这两件事的用户动作不同(文件列表读不到要刷新目录; 决策读不到只影响筛选与角标,
     * 照片照样能看), 而且**绝不吞掉** —— 读失败必须看得见, 因为它让筛选栏上的张数变得不可信。
     */
    val readFailure: DecisionReadFailure? = null,
    /**
     * 「N 张未同步」那个角标按下去之后的**批次级**结论(半成功必须说出来, 纪律②)。
     */
    val lastBatchNote: String? = null,
    /** **待重试队列**(持久的那一份, 规格 §9)的界面镜像。 */
    val pending: PendingState = PendingState(),
) {
    /** 顶部的张数 —— 来自 `scan` 的返回长度(失败且没有格子时说 [GRID_COUNT_UNKNOWN])。 */
    val countLabel: String
        get() = if (error != null && items.isEmpty()) GRID_COUNT_UNKNOWN else gridCountLabel(items.size)

    /** 筛选之后真正画出来的格子(**顺序逐字保留**)。 */
    val visibleItems: List<GridItem>
        get() = applyFilters(items, decisions, filters) { it.path }

    /**
     * 这一格的星级**知不知道** —— "确知没有星级"与"不知道"必须分得开的那条判据的**唯一**执法点。
     *
     * 有值就是知道(那个值就是屏幕上显示的); 没有值时才看知识: 服务端回报过这一张
     * ([knownPaths], 两个 null 也是明确的回答)⇒ 确知没有; 否则 ⇒ **不知道**(读失败 /
     * 这一张没被回报 / 边车读不动 / 还没读)。
     */
    fun ratingKnown(path: String): Boolean = decisions[path]?.rating != null || path in knownPaths

    /** 筛选栏上的"当前显示 N / 共 M 张"。 */
    val shownLabel: String get() = shownCountLabel(visibleItems.size, items.size)

    /** 三个维度上的张数(不知道时给 [COUNT_UNKNOWN], 绝不拿 0 顶上)。 */
    val counts: FilterCounts get() = filterCounts(items.map { it.path }, decisions, decisionsKnown)

    /** 这个文件夹里"改了但电脑还没确认"的张数 —— 纪律③的可见性。 */
    val unsyncedCount: Int get() = counts.unsynced

    /** 顶栏的**待重试角标**(规格 §8.1); 没有未同步项时为 null。文案与查看器共用一处。 */
    val unsyncedLabel: String?
        get() = if (unsyncedCount > 0) WriteMessages.unsyncedLabel(unsyncedCount) else null

    /**
     * 顶栏那一个角标(显示什么 + 按下去做什么, 见 [topBarBadge]): 待重试队列优先,
     * 队列为空的窗口里退回"未同步 N 张·重试"。
     */
    val topBadge: TopBarBadge?
        get() = topBarBadge(pendingCount = pending.count, unsyncedLabel = unsyncedLabel)

    /** 待重试的项数 —— **队列里真的有的那些**(不是本地看起来改过的)。 */
    val pendingCount: Int get() = pending.count

    /**
     * 待重试记录**读不回来**时页面上那句话(读得到时是 null)。
     *
     * 它与角标上的 0 是两件必须分开的事: 0 是"没有待重试的改动", 而读不回来是"**不知道**有多少,
     * 而且可能已经丢了一部分"。少了这一句, 用户在数据可能已经丢失的时候只会看到一个安静的 0。
     */
    val pendingCorruptedNotice: String? get() = if (pending.corrupted) PENDING_CORRUPT_NOTICE else null

    val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()

    /** 有照片, 但当前筛选条件一张都不剩 —— 与 [isEmpty] 是两件事。 */
    val allFilteredOut: Boolean
        get() = !loading && error == null && items.isNotEmpty() && visibleItems.isEmpty()

    val emptyMessage: String? get() = if (isEmpty) GRID_EMPTY else null

    /** 全被筛掉时的说明(不是"没有照片")。 */
    val filteredOutNotice: String? get() = if (allFilteredOut) filteredOutMessage(items.size) else null

    /** 全 RAW 的说明(没有就不画这一条)。只看**当前画出来的**那些照片。 */
    val notice: String? get() = allRawNotice(visibleItems)

    val hasPhotos: Boolean get() = items.isNotEmpty()

    /** 有没有格子要画 —— 筛完之后。 */
    val hasVisiblePhotos: Boolean get() = visibleItems.isNotEmpty()

    /** 读取失败卡的标题/正文/动作 —— **全部**来自 [ReadMessages], 界面里一个字都不拼。 */
    val readFailureHead: String? get() = readFailure?.let { ReadMessages.headFor(it) }
    val readFailureText: String? get() = readFailure?.let { ReadMessages.forFailure(it) }
    val readFailureAction: ReadAction? get() = readFailure?.let { ReadMessages.actionFor(it) }
    val readFailureActionLabel: String? get() = readFailureAction?.let { ReadMessages.actionLabel(it) }
}

/**
 * 网格页的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 与 [BrowseViewModel] 同样是"持有 StateFlow 的普通类"(理由逐条相同: 不继承
 * `androidx.lifecycle.ViewModel`, 免得 JVM 单测要先摆一个主线程调度器)。
 *
 * 五条纪律:
 *   · **只发一次 `scan`**: 服务端是单层语义, 拿的是这个文件夹的**直接**照片;
 *   · **失败不清空已有格子** —— 刷新失败时用户眼前的照片还在, 错误单独摆出来;
 *   · **401 必须走消歧**: 与配对页/浏览页同一条, 第一次 401 绝不当成 token 被轮换;
 *   · **决策读失败不许吞**: 它落在 [GridState.readFailure] 上, 并把张数降级成"未统计";
 *   · **决策只有一份**(这个类持有的 [DecisionStore]): 查看器借它写回, 网格用它画角标与筛选。
 *
 * `scan` 的 `path` **逐字**用 [FolderEntryDto.path](可能是 `\\?\D:\...` 这种带字面问号的
 * 规范路径) —— 这一页没有任何"自己拼路径"的地方。
 */
class GridViewModel(
    private val pairing: Pairing,
    /** 要看的文件夹。 */
    private val folder: FolderEntryDto,
    /**
     * **持久化的待重试队列**(M3′ Task 3)。生产路径**必须**传
     * `PendingStore.create(context)`(见 [GridScreen]) —— 它是"用户已经做出的决定"落盘的那一份,
     * 也是顶栏角标数出来的那个数。
     *
     * 刻意**没有默认值**: 一个"默认不落盘"的 GridViewModel 会让"关着电脑打的分重启后还在"
     * 这条承诺**静默失效**, 而那正是本任务唯一的价值点。
     */
    private val pending: PendingStore,
    /**
     * 注入点: 生产路径就是默认值 —— [ApiClient] 内部的 client 走
     * [com.imagefilter.mobile.data.SharedHttpClient.forToken], 与 Coil 是**同一个实例**
     * (缩略图网格的硬要求)。覆盖它时必须同样以 `buildHttpClient` 为底, 否则
     * "token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {
    private val _state = MutableStateFlow(GridState(folder = folder))
    val state: StateFlow<GridState> = _state.asStateFlow()

    private val api: ApiClient by lazy { clientFactory(pairing.url, pairing.token) }

    /**
     * 星级/色标的本地镜像 + 文件夹级远端读取 —— **由网格持有, 由查看器借用**。
     *
     * 放在这里(而不是查看器里)的理由与 Task 2 相同、并且更强: 它的寿命必须比查看器长。
     * 查看器一旦关掉, "哪张改了、哪张没写成"不能跟着消失(规格 §11 纪律③); 而筛选与角标
     * 也**必须**看同一份决策 —— 建两份的话, 格子上显示 4 星、查看器里显示 2 星只是时间问题。
     *
     * 它用的 `api` 与 `scan`/`thumb` 是**同一个** OkHttpClient(生产路径上就是
     * `SharedHttpClient.forToken(token)` 那一枚), 所以"JSON 通、图片 401"那种分裂在结构上不成立。
     *
     * ⚠️ 传进去的 [pending] **就是**这个网格读角标用的那一个实例: 每一次推送落定之后由 store
     * 把队列对齐(确认了的出队列、没确认的留在里面)。两处必须是同一个对象, 否则"电脑确认了"
     * 这件事没人把它从队列里去掉 —— 角标会一直挂着一个早就写成了的项。
     */
    val decisions: DecisionStore by lazy { DecisionStore(api, pending) }

    /**
     * 进这个文件夹(或点「刷新」)时要读的**全部**东西: 文件列表 + **持久待重试队列** +
     * 文件夹级的决策。
     *
     * 做成一个入口是刻意的: 分成几步让界面自己记得调几次, 迟早有一处只调了第一步 ——
     * 那时筛选栏会安静地拿着"未统计"过完这一页(或者更糟: 拿上一次的决策筛新列表)。
     *
     * ⚠️ **顺序也是刻意的**(M3′ Task 3): 队列**先**装回本地镜像, 再去读电脑上的值。
     * 反过来的话, 电脑上的旧值会先落进 `remote`, 而装回来的本地意图要等下一次刷新才看得见 ——
     * 用户会先看到星星"变回旧的"再变回来。
     */
    suspend fun loadAll() {
        load()
        // 文件列表本身没读到(例如 404/断网)时**不去读决策**: 那些路径是旧的, 读回来的值对应的是
        // 一个我们已经在说"没读到"的目录 —— 读它只会让张数看起来可信, 而它并不可信。
        if (_state.value.error == null) {
            // 队列里的意图只装回**这个文件夹里**的那些: 别的文件夹的待重试项由面板列出来
            // (它们照样能重试), 但不该混进这一页的张数与角标里。
            loadPending()
            loadDecisions()
        } else {
            refreshPending()
        }
    }

    /**
     * 读持久队列: 一条条装回本地镜像(网格上"你昨晚关着电脑打的分"就是这么回来的),
     * 并刷新角标数。
     *
     * 装回的动作只有一处实现([rehydratePending]): 本地**已经有更新的一版意图时不动它**
     * (队列里那条是旧的失败版本, 覆盖新版就是丢掉用户最新的决定)。
     */
    suspend fun loadPending() {
        refreshPending()
        val restored = rehydratePending(pending, decisions, _state.value.items.map { it.path })
        if (restored > 0) {
            // 装回来的意图立刻进界面(角标、格子上那颗星、筛选): 不许等到下一次刷新才出现。
            _state.update { it.withDecisions() }
        }
    }

    /**
     * 只把队列**数**一遍并抄进界面状态(不装回本地镜像、不碰网络)。
     *
     * 从查看器/滑卡页回到网格那一刻调它: 那期间用户可能已经打出好几分(其中一些没写成),
     * 队列是那时候变的。
     */
    suspend fun refreshPending(): PendingSnapshot {
        val snap = pending.snapshot()
        _state.update {
            it.copy(
                pending = it.pending.copy(items = snap.items.values.toList(), corrupted = snap.corrupted),
            )
        }
        return snap
    }

    /**
     * 拉这个文件夹的照片列表。「刷新」/「重试」也是它 —— 用户按了就是要新数据, 不做缓存判断。
     */
    suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val photos = api.scan(folder.path)
            val items = gridItems(api, photos)
            _state.update {
                it.copy(
                    items = items,
                    loading = false,
                    error = null,
                    // 新的文件列表 ⇒ 决策要**重新读**过才对得上: 里面可能有刚出现的照片, 我们对
                    // 它们一无所知。读完之前张数说"未统计"; 空文件夹是真的 0 张(没有照片就没有
                    // "电脑上还有星级"这种东西), 所以它直接是"知道"。
                    decisionsKnown = items.isEmpty(),
                ).withDecisions()
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = failure(e), decisionsKnown = false) }
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把取消说成网络故障
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(loading = false, error = synthesized(e), decisionsKnown = false) }
        }
    }

    /**
     * 读这个文件夹的**决策**(`GET /api/v1/decisions?paths=…`)并合并进 [decisions]。
     *
     * 三件事都在这里定死:
     *   · **合并规则在 [DecisionStore.mergeRemote]** —— 本地意图优先, 那一条的关键测试在那边;
     *   · **失败不影响文件列表**: 它落在 [GridState.readFailure] 上(**不吞**), 并把张数降级成
     *     "未统计" —— 因为电脑上原有的星级/色标这次没见到;
     *   · **不读空列表**: 没有照片时一个请求都不发(`readDecisions` 对空数组是 `badRequest`,
     *     而且"电脑上还有几张打了星"在空文件夹里本来就是个没有意义的数)。
     */
    suspend fun loadDecisions() {
        val paths = _state.value.items.map { it.path }
        if (paths.isEmpty()) {
            _state.update {
                it.copy(readFailure = null, decisionsKnown = true).withDecisions()
            }
            return
        }
        _state.update { it.copy(readFailure = null) }
        when (val pull = decisions.pullRemote(paths)) {
            is RemotePull.Merged -> _state.update {
                it.copy(decisionsKnown = true).withDecisions()
            }

            is RemotePull.Partial -> _state.update {
                // 已经回来的那些**照样采纳**(它们是服务端给的真值, 角标该显示就显示);
                // 但"问到的每一张都变成了知道"这个问题的答案是"没有" ⇒ 张数只能说"未统计"。
                //
                // 两种"没变成知道"的原因给**两句不同的话**(它们的下一步动作不一样):
                //   · 少报/多报 ⇒ 有的没回来(INCOMPLETE);
                //   · 每一项都回报了, 但有的边车**读不动** ⇒ 等那个程序松开边车再重试
                //     (SIDECAR_UNREADABLE)。混成一句话会让用户按错的方向去修。
                it.copy(
                    decisionsKnown = false,
                    readFailure = DecisionReadFailure(
                        code = if (pull.mismatched > 0) ReadMessages.INCOMPLETE else ReadMessages.SIDECAR_UNREADABLE,
                        source = ReadSource.CLIENT,
                    ),
                ).withDecisions()
            }

            is RemotePull.Failed -> _state.update {
                it.copy(decisionsKnown = false, readFailure = pull.failure).withDecisions()
            }

            // 空列表不该走到这里(上面已经 return 了), 但真走到了也**不许**说"知道":
            // 没有请求发出去就**没有**服务端的确认(与 PushResult.NothingToPush 同一条)。
            is RemotePull.NothingToRead -> _state.update {
                it.copy(decisionsKnown = false).withDecisions()
            }
        }
    }

    /** 改筛选条件(三个维度)。**不触发任何网络请求** —— 筛选全在本地那份快照上做。 */
    fun setFilters(f: Filters) {
        _state.update { it.copy(filters = f) }
    }

    /**
     * 用户看过了那条读取失败提示。
     *
     * ⚠️ **张数照旧说"未统计"**: 把这句话收起来不等于读到了 —— [GridState.decisionsKnown] 不动,
     * 所以筛选栏仍然不会拿已经知道的那些去冒充全部(与查看器里 dismiss 失败卡同一条纪律)。
     */
    fun clearReadFailure() {
        _state.update { it.copy(readFailure = null) }
    }

    /** 「清除筛选」。 */
    fun clearFilters() = setFilters(Filters())

    /**
     * 不碰网络, 只把 [decisions] 里**这台手机上的**改动抄进界面状态。
     *
     * 用在"查看器里打完分/写完边车、关掉查看器回到网格"那一刻: 那时 store 已经变了(乐观更新
     * 或服务端回报), 而网格手里那份快照还是旧的 —— 不抄一次的话, 角标与"未同步"边框会缺一块,
     * 而那正是纪律③最怕的静默。
     */
    fun refreshDecisions() {
        _state.update { it.copy(lastBatchNote = null).withDecisions() }
    }

    // ── 待重试队列: 重试 / 全部重试 / 放弃本地改动(M3′ Task 3) ────────────────

    /**
     * 重试**某一项**(面板上那一行的「重试」)。
     *
     * **一次一项**([retryPending] 是串行的): 一批里有半成功/已经不存在的路径时, 整批会被
     * 404 掉(M0′ Task 7 实测的坑)—— 一次一项, 一个坏路径毒不到别的项。
     */
    suspend fun retryPendingOne(path: String): RetryReport = runRetry(listOf(path))

    /** 「全部重试」: `null` = 队列里**此刻**仍然待重试的全部(已经不在里面的一个请求都不发)。 */
    suspend fun retryAllPending(): RetryReport = runRetry(paths = null)

    /**
     * 真的跑一轮(界面状态的前后处理都在这里)。
     *
     * 名字刻意**不叫** `retryPending`: 那样会与 [retryPending] 那个顶层纯逻辑同名, 读代码的人
     * 得先看清是成员还是顶层才知道走的是哪一条 —— 这正是本项目一直在避免的那种"两个实现互相
     * 掩盖"的形状。
     */
    private suspend fun runRetry(paths: List<String>?): RetryReport {
        _state.update {
            it.copy(pending = it.pending.copy(busy = true, note = null, abandonArmed = null))
        }
        val report = retryPending(pending, decisions, paths) {
            // **一项一项地**把最新事实抄进界面: 串行重试期间, 成的那项要立刻从列表里消失、
            // 失败的那项要立刻还在 —— 不许等全部跑完才更新一次(用户会以为卡住了)。
            // 列表以**队列本身**为准(不拿这一项的结论去猜), 所以别的项的变化也不会被漏掉。
            _state.update { st ->
                st.copy(pending = st.pending.copy(items = pending.snapshot().items.values.toList()))
                    .withDecisions()
            }
        }
        val snap = pending.snapshot()
        _state.update {
            it.copy(
                pending = it.pending.copy(
                    items = snap.items.values.toList(),
                    corrupted = snap.corrupted,
                    busy = false,
                    // 如实转述电脑确认了什么(没确认的一个字都不说成"已同步")
                    note = retryNote(report),
                ),
            ).withDecisions()
        }
        return report
    }

    /**
     * 「放弃本地改动」按下去的那**一下**。
     *
     * 两次确认的执法点就在这一行([AbandonConfirm.confirms]): 第一次调用**只武装**(返回 `null`,
     * 队列与本地意图一个字节都不动), 第二次必须点在**同一张**上才真的丢。放在这里而不是
     * Composable 里, 是因为"按一次会不会丢东西"必须有 JVM 用例钉住 —— 那是一条数据损失。
     *
     * 放弃**一个请求都不发**(电脑上从来没有过这份改动), 所以它不走 [DecisionStore.push]。
     */
    suspend fun requestAbandon(path: String): AbandonResult? {
        if (!AbandonConfirm(_state.value.pending.abandonArmed).confirms(path)) {
            // 第一次(或点了别的一张): 只武装。**什么都不丢** —— 连队列都不碰。
            _state.update { it.copy(pending = it.pending.copy(abandonArmed = path, note = null)) }
            return null
        }
        val result = abandonPending(pending, decisions, path)
        val snap = pending.snapshot()
        _state.update {
            it.copy(
                pending = it.pending.copy(
                    items = snap.items.values.toList(),
                    corrupted = snap.corrupted,
                    abandonArmed = null,
                    note = abandonNote(result),
                ),
            ).withDecisions()
        }
        return result
    }

    /** 关面板 / 去重试 / 点「取消」时解除武装(免得下次进来一按就丢)。 */
    fun cancelAbandon() {
        _state.update { it.copy(pending = it.pending.copy(abandonArmed = null)) }
    }

    /** 用户看过了那条结论。 */
    fun clearPendingNote() {
        _state.update { it.copy(pending = it.pending.copy(note = null)) }
    }

    /**
     * 「N 张未同步」那个角标按下去做的事: 把 store 里所有未同步的再推一遍(与查看器同一个入口)。
     *
     * 一批里可能只成一部分 —— 批次级结论([GridState.lastBatchNote])必须说出来(纪律②):
     * 角标上少一张, 用户会以为那一张已经落到电脑上了。
     */
    suspend fun retryUnsynced(): PushResult {
        val paths = decisions.unsynced().map { it.path }
        if (paths.isEmpty()) return PushResult.NothingToPush(0)
        val result = decisions.push(paths)
        _state.update {
            it.copy(
                // 逐条转述**每一项自己的结论**: 「没能确认」的那些不许被数成"没写进电脑"
                // (它们的逐项文案说的是"没有结论" —— 批次句必须与它一致)。
                lastBatchNote = (result as? PushResult.Failed)
                    ?.let { f -> WriteMessages.batchHead(f.requested, f.failures) },
            ).withDecisions()
        }
        // 这一批里成了的那些已经出队列、没成的留在里面 —— 角标必须跟着动。
        refreshPending()
        return result
    }

    /** 把 store 里那份**唯一**的真相抄进界面状态(网格不自己缓存第二份决策)。 */
    private fun GridState.withDecisions(): GridState =
        copy(
            decisions = this@GridViewModel.decisions.resolved(),
            // 知识的快照与决策的快照必须**同一次**抄过来: 分开抄会让"这一格有没有值"与
            // "这一格知不知道"来自两个时刻, 而那正是本轮要修的那种分不清。
            knownPaths = this@GridViewModel.decisions.knownPaths(),
        )

    /** 只有 401 有资格触发消歧复探(Task 2 的纪律), 其余码一律不许猜成因。 */
    private suspend fun failure(e: ApiException): FolderError {
        val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
        return FolderError(e.code, cause)
    }

    private fun synthesized(e: IOException) = FolderError(ApiError.synthesized(apiCodeFor(e)).code)
}
