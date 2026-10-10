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
 * ⚠️ **不要复用浏览页的 [photoCountLabel]**: 那个的 0 显示成"张数未统计", 因为
 * `browse` 的 `photoCount` 在这一层本来就没有权威值。而网格的张数来自 `scan` 的
 * **返回长度** —— 这里的 0 就是真的 0 张, 说成"未统计"才是撒谎。
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
) {
    /** 顶部的张数 —— 来自 `scan` 的返回长度(失败且没有格子时说 [GRID_COUNT_UNKNOWN])。 */
    val countLabel: String
        get() = if (error != null && items.isEmpty()) GRID_COUNT_UNKNOWN else gridCountLabel(items.size)

    /** 筛选之后真正画出来的格子(**顺序逐字保留**)。 */
    val visibleItems: List<GridItem>
        get() = applyFilters(items, decisions, filters) { it.path }

    /** 筛选栏上的"当前显示 N / 共 M 张"。 */
    val shownLabel: String get() = shownCountLabel(visibleItems.size, items.size)

    /** 三个维度上的张数(不知道时给 [COUNT_UNKNOWN], 绝不拿 0 顶上)。 */
    val counts: FilterCounts get() = filterCounts(items.map { it.path }, decisions, decisionsKnown)

    /** 这个文件夹里"改了但电脑还没确认"的张数 —— 纪律③的可见性。 */
    val unsyncedCount: Int get() = counts.unsynced

    /** 顶栏的**待重试角标**(规格 §8.1); 没有未同步项时为 null。文案与查看器共用一处。 */
    val unsyncedLabel: String?
        get() = if (unsyncedCount > 0) WriteMessages.unsyncedLabel(unsyncedCount) else null

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
     */
    val decisions: DecisionStore by lazy { DecisionStore(api) }

    /**
     * 进这个文件夹(或点「刷新」)时要读的**全部**东西: 文件列表 + 文件夹级的决策。
     *
     * 做成一个入口是刻意的: 分成两步让界面自己记得调两次, 迟早有一处只调了第一步 ——
     * 那时筛选栏会安静地拿着"未统计"过完这一页(或者更糟: 拿上一次的决策筛新列表)。
     */
    suspend fun loadAll() {
        load()
        // 文件列表本身没读到(例如 404/断网)时**不去读决策**: 那些路径是旧的, 读回来的值对应的是
        // 一个我们已经在说"没读到"的目录 —— 读它只会让张数看起来可信, 而它并不可信。
        if (_state.value.error == null) loadDecisions()
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
                // 但"问到的每一张都回报了没有"这个问题的答案是"没有" ⇒ 张数只能说"未统计"。
                it.copy(
                    decisionsKnown = false,
                    readFailure = DecisionReadFailure(
                        code = ReadMessages.INCOMPLETE,
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
                lastBatchNote = (result as? PushResult.Failed)
                    ?.let { f -> WriteMessages.batchHead(f.requested, f.failures.size) },
            ).withDecisions()
        }
        return result
    }

    /** 把 store 里那份**唯一**的真相抄进界面状态(网格不自己缓存第二份决策)。 */
    private fun GridState.withDecisions(): GridState =
        copy(decisions = this@GridViewModel.decisions.resolved())

    /** 只有 401 有资格触发消歧复探(Task 2 的纪律), 其余码一律不许猜成因。 */
    private suspend fun failure(e: ApiException): FolderError {
        val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
        return FolderError(e.code, cause)
    }

    private fun synthesized(e: IOException) = FolderError(ApiError.synthesized(apiCodeFor(e)).code)
}
