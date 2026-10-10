// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import coil3.network.HttpException
import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiError
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.PREVIEW_MAX_DEFAULT
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.ViewerAction
import com.imagefilter.mobile.data.ViewerMessages
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// ── 纯函数: 手势规则与档位(与 Compose 无关, 每个都有 JVM 单测) ───────────────────
//
// 与 GridViewModel / BrowseViewModel 同一条纪律: 安卓 UI 测试在本项目里没有依赖(也不该为
// M2′ 引入), 所以"缩放钳到哪、什么时候算查看态、单指这一下是切图还是平移、两端怎么夹住"
// 必须是**可以真的断言**的纯函数, 而不是散在 Composable 里的一堆 when。
//
// 手势规则由规格 §8.2 定死(不定死的话缩放与切图一定会互相抢手势):
//
// | 状态 | 单指 | 双指 |
// |---|---|---|
// | 决策态(1x) | 水平拖动 = 上一张/下一张 | 捏合 → 进入查看态 |
// | 查看态(>1x) | 平移(不切图) | 继续缩放; 缩回 1x → 回到决策态 |

/** 查看器的视图状态。`scale = 1f` = 原始比例(整张图适配屏幕)。 */
data class ViewerMode(val scale: Float, val offsetX: Float, val offsetY: Float)

/** 不允许缩到比原始更小 —— 那只会露出黑边, 用户以为"图变小了"。 */
private const val MIN_SCALE = 1f

/**
 * 上界 8x: 默认档的预览最长边只有 2048px(服务端已 Lanczos3 降采样), 再放大只是插值,
 * **没有新信息** —— 要看细节只能走「高清」(`full=true`, 见 [ViewerViewModel.enterHighRes])。
 */
private const val MAX_SCALE = 8f

/** 判定的容差: 浮点乘法后的 1.0000001 不该被当成"进入了查看态"。 */
private const val VIEWING_EPSILON = 1.0001f

fun clampScale(s: Float): Float = s.coerceIn(MIN_SCALE, MAX_SCALE)

fun resetView(): ViewerMode = ViewerMode(MIN_SCALE, 0f, 0f)

/**
 * 是否处于**查看态**。
 *
 * 这是**唯一**决定单指手势含义的判据(查看态里单指是平移, 否则是切图)。
 * 不做成显式开关的理由: 开关会与用户的双指动作脱节 —— 缩回 1x 却还停在查看态。
 */
fun isViewingMode(m: ViewerMode): Boolean = m.scale > VIEWING_EPSILON

/**
 * 缩放之后的视图状态。**缩到 ≤1x 一律回到原始比例并贴边归位**(偏移清零) ——
 * 少了这一条, 用户把图缩小后它会飘在屏幕外, 只能靠重启 App 找回。
 */
fun applyZoom(m: ViewerMode, zoom: Float): ViewerMode {
    val scaled = ViewerMode(clampScale(m.scale * zoom), m.offsetX, m.offsetY)
    return if (isViewingMode(scaled)) scaled else resetView()
}

/** 平移 —— **只在查看态有意义**; 原始比例下的偏移必须是 0(同一个"贴边归位"的理由)。 */
fun applyPan(m: ViewerMode, dx: Float, dy: Float): ViewerMode =
    if (isViewingMode(m)) m.copy(offsetX = m.offsetX + dx, offsetY = m.offsetY + dy) else resetView()

/** 双击: 在原始比例与 2x 之间切(2x 是"看清楚一点"的常用档, 8x 要留给捏合)。 */
fun toggleDoubleTap(m: ViewerMode): ViewerMode =
    if (isViewingMode(m)) resetView() else ViewerMode(2f, 0f, 0f)

/**
 * 决策态里单指水平拖动的**结论**: `0` = 不动, `+1` = 下一张, `-1` = 上一张。
 *
 * 手指向左(`dx < 0`)是把**下一张**从右边拉进来 —— 与照片浏览器的普遍直觉一致。
 * 阈值存在的理由: 没有它, 每一次轻微的手指抖动都会切图。
 */
fun dragStep(totalDx: Float, thresholdPx: Float): Int = when {
    thresholdPx <= 0f -> 0
    totalDx <= -thresholdPx -> +1
    totalDx >= thresholdPx -> -1
    else -> 0
}

/**
 * 邻张导航, **两端夹住不绕回**。到末尾再滑应该停住 —— 绕回第一张会让人以为照片丢了。
 */
fun nextIndex(current: Int, delta: Int, count: Int): Int {
    if (count <= 0) return 0
    return (current + delta).coerceIn(0, count - 1)
}

/**
 * Coil 报回来的一次"这张图没显示出来" → 错误码(§5.3 的闭集, 或 [ViewerMessages.UNCLASSIFIED])。
 *
 * 分支顺序是刻意的, 每一条都有出处:
 *   1. `HttpException` —— Coil 的网络层把**非 2xx** 包成它(它继承 `RuntimeException`,
 *      所以**不吃**下面 `IOException` 那一支)。`response.code` 就是真正的 HTTP 状态码,
 *      403/404/401 全靠它才分得开 —— 这正是"403 与 404 必须给不同的话和动作"的前提。
 *   2. 三种"根本没有 HTTP 响应"的 IO 错(与 `apiCodeFor` 同一套判据): 超时 / 连不上 / 路由不通。
 *   3. 其余 `IOException`(连接中途断、对端重置)→ `network`。
 *   4. `null`(Coil 连异常都没给)→ [ViewerMessages.UNCLASSIFIED]**刻意不用**服务端码:
 *      说不出所以然就别编一个具体原因(与 `ApiError.synthesized` 的那条 `require` 同一个理由)。
 *   5. 其余(Coil 的解码器失败就是这样: 字节拿到了、图出不来)→ `unsupportedForm` 的
 *      **媒体语义**"这个构建解不开这张图"(规格 §5.3 的实测扩充)。
 *
 * 这里**没有** `ApiException` 分支: 图片这条链路完全由 Coil 走 OkHttp, 不经过 [ApiClient],
 * 所以那个类型在这里不可能出现 —— 不为永远走不到的分支写代码。
 */
fun classifyImageFailure(t: Throwable?): String = when (t) {
    is HttpException -> ApiError.codeForStatus(t.response.code)
    is SocketTimeoutException -> ApiErrorCodes.TIMEOUT
    is ConnectException, is UnknownHostException, is NoRouteToHostException -> ApiErrorCodes.SERVICE_OFF
    is IOException -> ApiErrorCodes.NETWORK
    null -> ViewerMessages.UNCLASSIFIED
    else -> ApiErrorCodes.UNSUPPORTED_FORM
}

// ── 照片与档位 ───────────────────────────────────────────────────────────────

/** 查看器里的一张照片。只有显示真正需要的四个字段(缩略图 URL 在查看器里没有用处)。 */
data class ViewerPhoto(
    /** 规范路径(不透明, 逐字来自服务端; 它是 URL 上 `path=` 的来源)。 */
    val path: String,
    val fileName: String,
    /** 电脑上的修改时间 —— URL 上 `v=` 的来源(Coil 的缓存失效键)。 */
    val modifiedAt: Long,
    val isRaw: Boolean,
) {
    /** 显示名: 服务端给了 `fileName` 就用它, 空则退回路径(绝不显示空白标题)。 */
    val displayName: String get() = fileName.ifBlank { path }
}

/**
 * 格子 → 查看器的照片列表。**顺序逐字保留**(用户看到的顺序必须与电脑上一致)。
 *
 * 计划里 [ViewerScreen] 的参数写的是 `List<ScannedPhotoDto>`, 但网格手里只有
 * [GridItem](`scan` 的结果在上面加了 `isRaw`、并拼好了缩略图 URL), 而"RAW 的默认档是
 * 内嵌预览"这条提示**必须**知道 `isRaw`。让网格为此再存一份原始 DTO, 只会多一个会漂移的副本。
 */
fun viewerPhotos(items: List<GridItem>): List<ViewerPhoto> = items.map {
    ViewerPhoto(
        path = it.path,
        fileName = it.fileName,
        modifiedAt = it.modifiedAt,
        isRaw = it.isRaw,
    )
}

/** 正在显示的档位。默认档 = 相机内嵌预览/原图; 高清档 = 让电脑做**全解码**(秒级到十几秒级)。 */
enum class ViewerQuality { DEFAULT, HIGH_RES }

/** 顶部标题: `文件名 · (i+1)/N` —— 张数带序号, 用户才知道自己在哪一张。 */
fun viewerTitle(photo: ViewerPhoto, index: Int, count: Int): String =
    "${photo.displayName} · (${index + 1}/$count)"

/**
 * 画质上界**必须明示**(规格 §5.2):
 *   · 默认档对 RAW 拿到的是**相机塞进 RAW 的那张内嵌 JPEG**(机内色彩/锐化/降噪), 与电脑上
 *     "全解码 RAW"**不是同一个东西**; 已实测 8 张真实样张: ARW 全部 1616×1080, 索尼增强型 DNG
 *     只有 1024×683(HDR 那张仅 256×171)—— **没有一张到 2048**。所以放大到 1:1 验对焦在默认档
 *     根本不够, 这不是"优化项"。
 *   · 广色域(Adobe RGB / Display P3)可能偏色(降采样不处理 ICC) —— 这是与桌面端一致的已知限制。
 *
 * 非 RAW 不提示: 那边默认档拿到的就是原文件, 说"画质有上界"反而是假话。
 */
fun rawQualityHint(quality: ViewerQuality, photo: ViewerPhoto?): String? = when {
    photo == null -> null
    quality == ViewerQuality.HIGH_RES ->
        "高清档：这是电脑全解码出来的图，不是相机内嵌的那张小预览。"
    photo.isRaw ->
        "RAW 的默认档是相机内嵌的预览图（实测这批样张都没到 2048px，最小的只有 256×171），" +
            "放大到 1:1 验对焦不够 —— 点右上「高清」让电脑全解码（秒级到十几秒）。"
    else -> null
}

// ── 状态 ─────────────────────────────────────────────────────────────────────

/** 一次失败。**只装码与消歧结论, 不缓存句子** —— 文案随时按 [code] 取(与 [FolderError] 同一形状)。 */
data class ViewerError(val code: String, val cause: UnauthorizedCause? = null) {
    fun text(): String = ViewerMessages.forFailure(code, cause)
    fun head(): String = ViewerMessages.headFor(code)
    val action: ViewerAction get() = ViewerMessages.actionFor(code, cause)
    val actionLabel: String get() = ViewerMessages.actionLabel(action)
}

/**
 * 查看器的全部状态。**不含任何安卓/Compose 类型** —— 所以它能在 JVM 单测里被真的走一遍。
 *
 * 四个"空"必须分得清(这一页最容易犯的错就是把它们混成一个白屏):
 *   · [loading] —— 默认档还没回来, 画占位;
 *   · [error] —— 默认档**真的没图**, 说人话 + 给动作(文案查 [ViewerMessages]);
 *   · [highResLoading] —— 默认档的图**在屏幕上**, 高清还在解码(明确告诉用户电脑在忙);
 *   · [highResError] —— 高清失败了, **图退回默认档继续显示**, 这句话只是提示。
 */
data class ViewerState(
    val photos: List<ViewerPhoto> = emptyList(),
    val index: Int = 0,
    val mode: ViewerMode = resetView(),
    val quality: ViewerQuality = ViewerQuality.DEFAULT,
    /** 默认档的预览 URL(由 [ApiClient.previewUrl] 拼, 界面绝不自己拼)。 */
    val defaultUrl: String = "",
    /** 高清档的 URL; 只在高清"在飞/已成"时非空。为空 = 显示的是默认档。 */
    val highResUrl: String? = null,
    val loading: Boolean = true,
    val highResLoading: Boolean = false,
    val error: ViewerError? = null,
    val highResError: ViewerError? = null,
    /**
     * 「重试」用来把同一个 URL **重新发一次**。URL 不变时 Coil 的 painter 不会重发,
     * 所以界面用 [reloadNonce] 当 `key{}` 重建那个 AsyncImage(见 ViewerScreen)。
     */
    val reloadNonce: Int = 0,
) {
    val current: ViewerPhoto? get() = photos.getOrNull(index)
    val count: Int get() = photos.size

    /** 顶部标题(空列表时是空串 —— 界面那一层根本不会打开查看器)。 */
    val title: String get() = current?.let { viewerTitle(it, index, count) } ?: ""

    /** 查看态 = 单指是平移而不是切图(判据只在 [isViewingMode] 一处)。 */
    val isViewing: Boolean get() = isViewingMode(mode)

    /**
     * 这一帧要显示的 URL。
     *
     * 高清失败后 [quality] 会被退回 [ViewerQuality.DEFAULT] 且 [highResUrl] 清空, 于是这里
     * **仍然**是默认档那张图 —— "高清失败不许白屏"这条就是靠这个不变量成立的。
     */
    val displayUrl: String
        get() = if (quality == ViewerQuality.HIGH_RES && highResUrl != null) highResUrl else defaultUrl

    /** 高清入口是否给按钮(已经在高清档、或正在解码时不重复给)。 */
    val canRequestHighRes: Boolean
        get() = quality == ViewerQuality.DEFAULT && !highResLoading && highResUrl == null

    val hint: String? get() = rawQualityHint(quality, current)
}

/**
 * 查看器的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 与 [GridViewModel] 同样是"持有 StateFlow 的普通类"(理由相同: 不继承 `androidx.lifecycle.
 * ViewModel`, 免得 JVM 单测要先摆一个主线程调度器)。
 *
 * 五条纪律(每一条都有一个测试盯着):
 *   1. **URL 全部由 [ApiClient.previewUrl] 拼** —— 界面里没有一处字符串拼接(verbatim 前缀里
 *      那个字面问号会在这里被弄丢);
 *   2. **默认档是 `max=2048` + 不带 `full`**; 高清是同一个 `max` + `full=true`
 *      (**必须是 `true`**: 服务端的 `full` 是 Rust 的 `bool`, `serde_urlencoded` 只认
 *      "true"/"false", `full=1` 会被判成类型错误 → 400 badRequest);
 *   3. **高清只由用户显式触发, 且预取永远是默认档** —— 高清意味着电脑做全解码
 *      (实测样本里有一个 50.6MB 的线性 DNG, 秒级到十几秒级), 预取它就等于每滑一张都卡一次;
 *   4. **高清失败退回默认档 + 一句可执行的话**, 绝不留白屏;
 *   5. **切图一定回到默认档** —— 把高清态带到下一张, 每张都要等一次全解码。
 */
class ViewerViewModel(
    private val pairing: Pairing,
    items: List<GridItem>,
    startIndex: Int = 0,
    /**
     * 单指水平拖动多少**像素**算"切一张"。像素由界面按密度换算后传进来
     * (纯状态里拿不到 density), 测试直接给具体数字。
     */
    private val dragThresholdPx: Float = 160f,
    /**
     * 邻张预取。**刻意没有默认值**: 给一个 `{}` 的默认值, 生产路径忘了注入就会安静地
     * 少掉整个预取(而且没有任何测试会红)。生产实现见 [ViewerScreen](走 Coil 的 enqueue)。
     */
    private val prefetch: (List<String>) -> Unit,
    /**
     * 注入点: 生产路径就是默认值 —— [ApiClient] 内部的 client 走
     * [com.imagefilter.mobile.data.SharedHttpClient.forToken], 与 Coil 是**同一个实例**。
     * 覆盖它时必须同样以 `buildHttpClient` 为底, 否则"token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {
    /**
     * ⚠️ **必须声明在 [_state] 之前**: `_state` 的初值里就要拼默认档的 URL, 而 Kotlin 的
     * `by lazy` 代理字段是在声明处才初始化的 —— 放在后面会拿到一个 null 代理(构造期 NPE)。
     */
    private val api: ApiClient by lazy { clientFactory(pairing.url, pairing.token) }

    private val photos: List<ViewerPhoto> = viewerPhotos(items)

    /** 起点夹在合法范围内 —— 网格传进来的下标一定是合法的, 但这不花什么代价。 */
    private val start: Int = if (photos.isEmpty()) 0 else startIndex.coerceIn(0, photos.lastIndex)

    private val _state = MutableStateFlow(
        ViewerState(
            photos = photos,
            index = start,
            defaultUrl = urlFor(start, full = false).orEmpty(),
        ),
    )
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    /** 当前手势里累计的水平位移(决策态切图用)。**每次手势开始清零**(见 [onGestureStart])。 */
    private var dragAccum = 0f

    /**
     * 这一次手势**已经切过一张了**。
     *
     * 为什么要有它: 切图没有"图跟着手指走"的动画, 一次长拖动里连切三张会让人莫名其妙
     * (而且看不出是怎么发生的)。所以约定: **一次手势只切一张**, 想连看几张就再拖几次。
     */
    private var dragConsumed = false

    /**
     * 这一张的预览 URL —— **唯一**拼 URL 的地方。`max` 用规格 §5.2 的默认档 2048:
     * 服务端 `resize_max_edge` **绝不放大**, 所以对 RAW(内嵌预览都 <2048)调大 `max` 毫无作用,
     * 想更清楚只能 `full=true`。
     */
    private fun urlFor(index: Int, full: Boolean): String? {
        val photo = photos.getOrNull(index) ?: return null
        return api.previewUrl(
            path = photo.path,
            max = PREVIEW_MAX_DEFAULT,
            mtime = photo.modifiedAt,
            full = full,
        )
    }

    /**
     * 进查看器: 把邻张(默认档)拉起来, 左右切换是瞬时的。
     *
     * **不带 `full`**: 高清只由用户显式触发(理由见类 KDoc 第 3 条)。
     * 计划写的是 `index+1` 与 `index-1`(§8.2 里"当前张 + 后 2 张"是 M3′ 滑卡那一页的事);
     * 当前这张不发 —— 它正在被 [ViewerState.defaultUrl] 加载。
     */
    fun prefetchUrls(): List<String> {
        val st = _state.value
        if (st.photos.isEmpty()) return emptyList()
        return listOf(st.index - 1, st.index + 1)
            .filter { it in st.photos.indices }
            .mapNotNull { urlFor(it, full = false) }
    }

    /** 没有邻张可预取时**不打扰预取器**(空列表调一次只会白白走一趟 Coil)。 */
    private fun prefetchNeighbours() {
        val urls = prefetchUrls()
        if (urls.isEmpty()) return
        prefetch(urls)
    }

    fun open() {
        prefetchNeighbours()
    }

    // ── 手势(规则见文件头的表) ───────────────────────────────────────────────

    /** 一次手势开始: 清掉上一轮的累计拖动(两次"半程"拖动不许加起来切一张图)。 */
    fun onGestureStart() {
        dragAccum = 0f
        dragConsumed = false
    }

    /** 一次手势结束。缩回 1x 的贴边归位已经在 [applyZoom]/[applyPan] 里做完, 这里只清累计。 */
    fun onGestureEnd() {
        dragAccum = 0f
        dragConsumed = false
    }

    /**
     * 一次 transform 事件(来自 `detectTransformGestures`)。
     *
     * 三条分支的顺序就是手势规则本身, 所以它能被 JVM 单测走一遍:
     *   ① `zoom != 1f`(双指捏合) → 按缩放处理, **并且这个手势不再算拖动**(双指手势里
     *      两指平行移动会算出 zoom≈1 —— 那时它已经在查看态, 走 ②) ;
     *   ② 查看态 → 单指平移, **绝不切图**;
     *   ③ 决策态 → 单指水平拖动累计到阈值就切一张; 切完清零, 而且**这一次手势不再切第二张**
     *      ([dragConsumed] —— 没有"图跟着手指走"的动画时, 一次长拖动连跳三张谁也看不懂)。
     */
    fun onTransform(zoom: Float, panX: Float, panY: Float) {
        if (zoom != 1f) {
            _state.update { it.copy(mode = applyZoom(it.mode, zoom)) }
            dragAccum = 0f
            return
        }
        if (_state.value.isViewing) {
            _state.update { it.copy(mode = applyPan(it.mode, panX, panY)) }
            return
        }
        if (dragConsumed) return
        dragAccum += panX
        val step = dragStep(dragAccum, dragThresholdPx)
        if (step == 0) return
        dragAccum = 0f
        dragConsumed = true
        go(step)
    }

    /** 双击: 1x ↔ 2x。 */
    fun toggleZoom() {
        _state.update { it.copy(mode = toggleDoubleTap(it.mode)) }
    }

    // ── 导航与档位 ───────────────────────────────────────────────────────────

    /**
     * 切到上/下一张(`delta = +1` 下一张, `-1` 上一张)。到两端**停住**(见 [nextIndex])。
     *
     * 无论从哪一档切过去, **一律回到默认档**: 把高清态带到下一张, 每滑一张都要等一次
     * 全解码(实测最重的一张是 50MB 线性 DNG)。
     */
    fun go(delta: Int) {
        val st = _state.value
        val next = nextIndex(st.index, delta, st.photos.size)
        if (next == st.index) return
        val url = urlFor(next, full = false).orEmpty()
        _state.update {
            it.copy(
                index = next,
                mode = resetView(),
                quality = ViewerQuality.DEFAULT,
                defaultUrl = url,
                highResUrl = null,
                highResLoading = false,
                highResError = null,
                error = null,
                loading = true,
            )
        }
        prefetchNeighbours()
    }

    /**
     * 高清入口(按钮或长按): 用**同一个 `max` + `full=true`** 重新要同一张图, 并替换显示。
     *
     * 已经在高清档 / 正在解码时不重复请求(重复点等于让电脑多做一次全解码)。
     * 这里**不预取**任何东西 —— 高清只由这一次显式动作触发。
     */
    fun enterHighRes() {
        val st = _state.value
        if (!st.canRequestHighRes) return
        val url = urlFor(st.index, full = true) ?: return
        _state.update {
            it.copy(
                quality = ViewerQuality.HIGH_RES,
                highResUrl = url,
                highResLoading = true,
                highResError = null,
                error = null,
            )
        }
    }

    // ── 加载结果(界面把 Coil 的结果回报进来) ────────────────────────────────

    /** 默认档的图出来了。 */
    fun onDefaultLoaded() {
        _state.update { it.copy(loading = false, error = null) }
    }

    /**
     * 默认档**没有图可显示** → 错误卡(有话说、有动作)。
     *
     * 这是"白屏"最容易发生的那一处: 所以 [ViewerState.error] 非空时界面必须画那张卡,
     * 而**不是**画一个空 Box。
     */
    suspend fun onDefaultFailed(t: Throwable?) {
        val err = classify(t)
        _state.update { it.copy(loading = false, error = err) }
    }

    /** 高清档的图出来了。 */
    fun onHighResLoaded() {
        _state.update { it.copy(highResLoading = false, highResError = null) }
    }

    /**
     * 高清档失败了 → **退回默认档**并给一句可执行的话(**绝不**把屏幕清空)。
     *
     * 退回的动作是 [ViewerState.displayUrl] 那条不变量: `quality = DEFAULT` + `highResUrl = null`
     * → 显示的还是原来那张默认档预览(它本来就画在下面, 所以连闪一下都不会有)。
     */
    suspend fun onHighResFailed(t: Throwable?) {
        val err = classify(t)
        _state.update {
            it.copy(
                quality = ViewerQuality.DEFAULT,
                highResUrl = null,
                highResLoading = false,
                highResError = err,
            )
        }
    }

    /** 「重试」: 同一个 URL 再要一次(URL 不变时 Coil 不会自己重发, 所以靠 [ViewerState.reloadNonce])。 */
    fun retryDefault() {
        _state.update { it.copy(loading = true, error = null, reloadNonce = it.reloadNonce + 1) }
    }

    /** 高清失败的那句提示看完了(图还在, 只是退回默认档)。 */
    fun dismissHighResError() {
        _state.update { it.copy(highResError = null) }
    }

    /** 只有 401 有资格触发消歧复探(与配对页/浏览页/网格同一条纪律)。 */
    private suspend fun classify(t: Throwable?): ViewerError {
        val code = classifyImageFailure(t)
        val cause = if (code == ApiErrorCodes.UNAUTHORIZED) probeUnauthorized() else null
        return ViewerError(code, cause)
    }

    /**
     * 401 的消歧复探。协程被取消时 OkHttp 抛的也是 IOException —— 先 `ensureActive()`,
     * 别把"用户退出了这一页"说成网络故障。
     */
    private suspend fun probeUnauthorized(): UnauthorizedCause = try {
        api.classifyUnauthorized()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        UnauthorizedCause.UNDETERMINED
    }
}
