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
import com.imagefilter.mobile.data.WriteAction
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
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
    // ── 决策(星级/色标)与它们的**同步状态** ────────────────────────────────
    //
    // 这一组字段全部来自 [DecisionStore](**唯一**的真相来源), 而不是界面各自缓存一份:
    // 一旦这页自己存一份"我以为的星级", 乐观更新、失败保留、未同步标记就会各自漂移。
    /** 当前这张在这台手机上的星级; `null` = 还没定过(`0` = 明确废弃)。 */
    val rating: Int? = null,
    /**
     * **电脑上**这一张的星级是三态([DesktopRating]), 不是一个 `Int?` —— 与 [rating] 是两件事:
     * [rating] 是"合并之后屏幕上显示的"(本地意图优先), 而这一个说的是"电脑上到底是什么/知不知"。
     *
     * 默认 [DesktopRating.Unknown] = 不知道(保守): 界面因此会把它画成"未知"并**拦下**一次
     * 1..5 星的绝对赋值, 而不是拿一排空星星冒充"没评过"。
     */
    val desktopRating: DesktopRating = DesktopRating.Unknown,
    /**
     * 上面那份知识是**刚被电脑确认写入**得到的(而不是"上一次读到"的) —— FIX 3 的时态。
     *
     * 数值一样、时态不同的两份知识必须说成两句不同的话: 打完分之后那句"上次读到：0★"
     * 会被读成"我这一下没生效", 而事实上电脑上**现在就是** 4★。判据只有一处
     * ([DecisionStore] 的 `written`), 这里只是把它带到界面上。
     */
    val desktopRatingFromWrite: Boolean = false,
    /**
     * 上一次**打星被拦下**时的那句说明([ratingGuardMessage])。
     *
     * 与 [swipeNote] / [undoNote] 同一种"最近一次操作说了什么"的格子: 被拦下也是**一次操作的结果**,
     * 不说出来用户只会看到"点了没反应" —— 那比拒绝更坏。任何别的操作会把它清掉。
     */
    val ratingGuardNote: String? = null,
    /**
     * 用户刚刚点过、但被 [ratingGuardNote] 拦下的那颗星(`null` = 没有武装)。
     *
     * 这就是"明确覆盖"的执法点: 第一下**只武装**(一个字节都不写), 再点**同一颗星**才算
     * 用户看过了那句警告并确认要覆盖(与 `AbandonConfirm` 的"两次确认"同一种形状 ——
     * 一次误触不许造成数据损失)。换个星级会重新武装, 换张照片会清掉它。
     */
    val blindRatingArmed: Int? = null,
    /** 当前这张的色标(五色闭集, 见 [LABELS])。 */
    val label: String? = null,
    /** 当前这张的意图有没有被电脑确认过([SyncState])。 */
    val sync: SyncState = SyncState.UNTOUCHED,
    /**
     * 当前这张**没能写进电脑**的那一条失败(逐项、带码、带来源表)。
     *
     * 非 null 时界面必须画出来并给一个动作 —— 这是规格 §11 纪律③在查看器里的落点。
     */
    val writeFailure: WriteFailure? = null,
    /**
     * 这次打开文件夹以来, 一共几张"改了但电脑还没确认"。
     *
     * 跨张可见是刻意的: 只标当前这张的话, 用户切走以后就再也想不起哪张还没写成
     * (纪律③: "未同步 ≠ 已同步"必须**看得见**, 而不是只在失败的那一秒看得见)。
     */
    val unsyncedCount: Int = 0,
    /** 正在把改动写给电脑(界面据此禁掉连点, 避免自己制造乱序)。 */
    val writing: Boolean = false,
    /**
     * 「重试全部未同步」那一次批量写入的**批次级**结论(N 张里错了几张)。
     *
     * 半成功态必须显式(纪律②): 一批 3 张成了 2 张时, 当前这张可能恰好是成的那张 ——
     * 只靠 per-item 的失败卡, 用户会以为"全成了"。没有话说时为 null。
     */
    val lastBatchNote: String? = null,
    /**
     * 「重试」用来把同一个 URL **重新发一次**。URL 不变时 Coil 的 painter 不会重发,
     * 所以界面用 [reloadNonce] 当 `key{}` 重建那个 AsyncImage(见 ViewerScreen)。
     */
    val reloadNonce: Int = 0,
    /**
     * 上一次**滑卡**结论的那句话(M3′): "已保留 · 保持 5★"这类 —— 规格 §8.2 要求命中
     * "不降级"那一支时**不写**但**仍要前进并给出明确反馈**, 这句话就是那个反馈。
     *
     * 它**不随换张清空**: 说完这句话正好滑到下一张, 那句话必须还在屏幕上, 否则用户看到的
     * 只是一张卡片无声地换了。下一次滑卡会覆盖它, 用手点星级/色标([ViewerViewModel.setRating])
     * 或撤销([ViewerViewModel.undo])会清掉它(那句话不再适用)。没有话说时为 null。
     */
    val swipeNote: String? = null,
    /**
     * 上一次**撤销**的结论(M3′ 规格 §8.2): "已撤销" / "撤销没能写进电脑…"([undoMessage])。
     *
     * 与 [swipeNote] 共用同一格"最近一次操作说了什么": 谁后发生谁在屏幕上, 另一次操作会把它清掉
     * (撤销清 [swipeNote], 滑卡清它)—— 两句话都在讲同一张照片刚刚发生了什么, 同时显示必然
     * 有一句是过期的。
     */
    val undoNote: String? = null,
    /**
     * 现在还能撤销几步(0 = 撤销按钮禁用)。
     *
     * 栈本身在 [ViewerViewModel] 里(**内存**, 不落盘): 它记的是"这一次会话里刚做的那几下",
     * 关掉这一页就没了 —— 与"未同步"不同, 后者由 [DecisionStore] 活着(纪律③)。
     */
    val undoCount: Int = 0,
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

    /** 当前这张有"手机上改了但电脑还没确认"的改动 —— 界面必须标出来(纪律③)。 */
    val hasUnsyncedChange: Boolean get() = sync == SyncState.UNSYNCED

    // 失败卡的标题/正文/动作**全部查 WriteMessages** —— 界面里一个字都不拼(与错误卡同一条纪律)。
    val writeHead: String? get() = writeFailure?.let { WriteMessages.headFor(it) }
    val writeText: String? get() = writeFailure?.let { WriteMessages.forFailure(it) }
    val writeAction: WriteAction? get() = writeFailure?.let { WriteMessages.actionFor(it) }
    val writeActionLabel: String? get() = writeAction?.let { WriteMessages.actionLabel(it) }

    /** 跨张的未同步角标(没有未同步项时为 null, 界面据此决定画不画)。 */
    val unsyncedLabel: String?
        get() = if (unsyncedCount > 0) WriteMessages.unsyncedLabel(unsyncedCount) else null

    /** 当前这张有未同步改动时的短提示(没用失败卡占屏, 但**必须**让人看见)。 */
    val unsyncedNote: String? get() = if (hasUnsyncedChange) WriteMessages.UNSYNCED_THIS_PHOTO else null
}

/**
 * 查看器的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 与 [GridViewModel] 同样是"持有 StateFlow 的普通类"(理由相同: 不继承 `androidx.lifecycle.
 * ViewModel`, 免得 JVM 单测要先摆一个主线程调度器)。
 *
 * 六条纪律(每一条都有一个测试盯着):
 *   1. **URL 全部由 [ApiClient.previewUrl] 拼** —— 界面里没有一处字符串拼接(verbatim 前缀里
 *      那个字面问号会在这里被弄丢);
 *   2. **默认档是 `max=2048` + 不带 `full`**; 高清是同一个 `max` + `full=true`
 *      (**必须是 `true`**: 服务端的 `full` 是 Rust 的 `bool`, `serde_urlencoded` 只认
 *      "true"/"false", `full=1` 会被判成类型错误 → 400 badRequest);
 *   3. **高清只由用户显式触发, 且预取永远是默认档** —— 高清意味着电脑做全解码
 *      (实测样本里有一个 50.6MB 的线性 DNG, 秒级到十几秒级), 预取它就等于每滑一张都卡一次;
 *   4. **高清失败退回默认档 + 一句可执行的话**, 绝不留白屏;
 *   5. **切图一定回到默认档** —— 把高清态带到下一张, 每张都要等一次全解码;
 *   6. **打完分/标完色: 乐观更新 + 失败可见 + 可重试**([setRating] / [setLabel]) ——
 *      星级色标**只由服务端确认**(客户端不许自己判成功), 失败**不回滚**本地值,
 *      并把失败与"未同步 N 张"摆到界面上(规格 §11 纪律③)。
 *
 * M3′ 在同一个状态机上加了**滑卡**([swipe]): 手势 → 纯函数 [decideSwipe] → 写入。语义
 * (保留不降级 / 只写星级不碰色标)全在那个纯函数里, 这里只做三件事: 不写就不发请求、
 * 写成功才前进([advanceOnWriteSuccess])、失败把结论与失败卡都留在屏幕上。
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
     * 决策(星级/色标)的本地镜像与推送。**刻意没有默认值, 也刻意不从 [api] 现造一个**:
     * 它必须活得比查看器久(见 [ViewerScreen] 的 KDoc)—— 关掉查看器就丢掉"哪张还没写成",
     * 正是纪律③最怕的那种静默。生产路径由 `GridScreen` 建一份、传给查看器。
     */
    private val decisions: DecisionStore,
    /**
     * 写下一次决策成功后要不要自动前进到下一张(规格 §8.2: "写成功才自动前进、失败不前进")。
     *
     * M2′ 默认 **false**: 这一页是"看大图 + 手动切图", 打完分把人从正在看的图上踢走是坏事。
     * M3′ 的滑卡决策页会传 `true` —— 那时"打完一张自动到下一张"才是预期动作。
     * **无论它是什么, 失败一律不前进**: 那正是本字段存在的意义(它让"失败不前进"这条
     * 不变量有一个**非空**的对照 —— 成功会前进, 失败不会)。
     */
    private val advanceOnWriteSuccess: Boolean = false,
    /**
     * 注入点: 生产路径就是默认值 —— [ApiClient] 内部的 client 走
     * [com.imagefilter.mobile.data.SharedHttpClient.forToken], 与 Coil 是**同一个实例**。
     * 覆盖它时必须同样以 `buildHttpClient` 为底, 否则"token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
    /**
     * 向后(下一张方向)预取几张,**默认 1**(与 M2′ 的查看器逐字一致: 左右邻张)。
     *
     * M3′ 的滑卡页传 `2`: 规格 §8.2 要的是"当前张 + **后 2 张**预取" —— 它用的是**同一个**
     * 预取器(见 [prefetchUrls]), 只是把前向窗口开大一张; **绝不**另写一套预取
     * (那正是"预取高清"这类错误最容易溜进来的地方)。
     */
    private val prefetchAhead: Int = 1,
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
        // 决策字段一开始就要从 [decisions] 读进来: 关掉查看器再打开(同一份 store)时,
        // 刚才那张的星级与"未同步"必须还在(纪律③)。
        refresh(
            ViewerState(
                photos = photos,
                index = start,
                defaultUrl = urlFor(start, full = false).orEmpty(),
            ),
        ),
    )
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    /** 当前手势里累计的水平位移(决策态切图用)。**每次手势开始清零**(见 [onGestureStart])。 */
    private var dragAccum = 0f

    /**
     * 撤销栈(规格 §8.2: 多步, 内存上限 [UNDO_LIMIT])。
     *
     * 放在这里(而不是 [DecisionStore] 里)的理由: 它是**这一页的操作史** —— 关掉这一页、
     * 换一批照片之后再撤销"上一批的某一张"没有意义(那张可能已经不在队列里)。而"未同步"
     * 那件事的寿命问题由 store 负责, 两者是不同的东西, 所以不放在一起。
     */
    private val undoStack = UndoStack(UNDO_LIMIT)

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
     * 向后几张由 [prefetchAhead] 决定(M2′ 的查看器是 1; M3′ 的滑卡页是 2, 规格 §8.2
     * "当前张 + 后 2 张")。当前这张不发 —— 它正在被 [ViewerState.defaultUrl] 加载。
     */
    fun prefetchUrls(): List<String> {
        val st = _state.value
        if (st.photos.isEmpty()) return emptyList()
        val indices = listOf(st.index - 1) + (1..prefetchAhead).map { st.index + it }
        return indices
            .filter { it in st.photos.indices }
            .mapNotNull { urlFor(it, full = false) }
    }

    /** 没有邻张可预取时**不打扰预取器**(空列表调一次只会白白走一趟 Coil)。 */
    private fun prefetchNeighbours() {
        val urls = prefetchUrls()
        if (urls.isEmpty()) return
        prefetch(urls)
    }

    /**
     * 任意一张的**默认档** URL(`null` = 列表里没有这一张)。
     *
     * 给滑卡页的"后面那张卡"用: 它必须与显示用的那张**同源**([urlFor]) —— 自己另拼一个
     * URL(哪怕只差一个参数)就等于让 Coil 把同一张图拉两次, 而预取也就白预取了。
     */
    fun defaultUrlAt(index: Int): String? = urlFor(index, full = false)

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
            // refresh: 换了一张就把那一张自己的星级/色标/未同步状态读进来(真相在 store 里)
            refresh(
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
                ),
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

    // ── 决策: 打星 / 标色 / 重试(乐观更新 + 失败可见) ──────────────────────

    /**
     * 打星(0 = 废弃)。**乐观更新**: 先本地生效(界面立刻变), 再写给电脑。
     *
     * 失败时**不回滚、不前进**: 本地值留着、标记成未同步、把服务端给的那条失败摆出来
     * (带 [WriteMessages] 的文案与动作), 用户能点「重试写入」。
     * 静默回滚会让人以为"白打了一次分"; 静默吞掉更糟 —— 那正是本项目最贵的一种失败。
     *
     * ⚠️ **不知道电脑上是几星时, 1..5 星这一下不写**([ratingTapIsBlind]): 它是一次**绝对赋值**,
     * 落在一张电脑上其实是 5 星的照片上就是静默降级 —— 与滑卡"保留"那一支同一条理由。
     * 这一下只**武装**并说清原因([ViewerState.blindRatingArmed] / [ratingGuardNote]);
     * 用户再点**同一颗星** = 明确要求覆盖, 那时才真的写。0(废弃)不受这条管。
     *
     * 返回 [PushResult.NothingToPush] 表示"这一次一个请求都没发出去"(被拦下也算: 它确实是
     * 一次没有发出的写入), 界面据此**绝不**前进到下一张。
     */
    suspend fun setRating(stars: Int): PushResult {
        val path = currentPath() ?: return PushResult.NothingToPush(0)
        val blind = ratingTapIsBlind(decisions.get(path).desktopRating, stars)
        if (blind && _state.value.blindRatingArmed != stars) {
            // 第一下: **只武装**, 一个字节都不写。界面必须把这句话画出来(否则用户只看到"点了没反应")。
            _state.update {
                it.copy(
                    blindRatingArmed = stars,
                    ratingGuardNote = ratingGuardMessage(stars),
                    swipeNote = null,
                    undoNote = null,
                )
            }
            return PushResult.NothingToPush(1)
        }
        // 第二下(同一颗星): 用户看过那句警告了 ⇒ 明确覆盖。别的动作照旧走 commit。
        _state.update { it.copy(blindRatingArmed = null, ratingGuardNote = null) }
        return commit { it.withRating(stars) }
    }

    /**
     * 标色(`null` = 清掉)。清掉走的是显式的 `clearLabel = true`, **不是** `label = null`
     * (`null` 的语义是"不动这个属性", 混起来用户的撤销会在下次读盘时复活)。
     *
     * 界面上的"再点一次当前颜色 = 清掉"由 [labelAfterTap] 决定, 这里只收结论。
     */
    suspend fun setLabel(label: String?): PushResult = commit { it.withLabel(label) }

    /**
     * 一次**滑卡**(M3′ 的主动作): 手势 → [decideSwipe] → 写入。
     *
     * 三条分支, 每一条都对应一次用户可能会被误导的机会:
     *   · **没什么要写**([SwipeOutcome.unchanged], 例如对一张已经 5 星的照片右滑) ——
     *     **一个请求都不发**, 但**照样前进**并显示那句 [SwipeOutcome.message]
     *     ("已保留 · 保持 5★")。不发请求是硬要求: 无条件写 3 会把电脑上的 5 星降成 3 星;
     *   · **要写** —— 先`applyLocal`(乐观更新: 界面立刻变, 且**留下**这份意图),
     *     再推送; 送去的是 [SwipeOutcome.applyTo] 落下来之后的那一份意图(整份, 逐字,
     *     与用户手点星级走的是**同一条**写入链路);
     *   · **写失败** —— 本地意图与失败卡都留着, 而且**绝不前进**(那会让用户以为打过了)。
     *
     * 返回的是服务端说了什么([PushResult]); `unchanged` 那一支回 [PushResult.NothingToPush]
     * (确实一个字节都没发出去 —— 与"发了但服务端没回报"必须分得清)。
     */
    suspend fun swipe(
        direction: SwipeDirection,
        config: SwipeConfig = DEFAULT_SWIPE_CONFIG,
    ): PushResult {
        val path = currentPath() ?: return PushResult.NothingToPush(0)
        val before = decisions.get(path)
        val outcome = decideSwipe(before, direction, config)
        if (outcome.unchanged) {
            // 不产生写入 ⇒ 不碰本地意图(凭空标一条"未同步"会让角标撒谎), 也不发请求。
            // (这一支不走 refresh(), 所以那句"打星被拦下"的话要在这里自己清掉: 一次滑卡之后
            //  它讲的已经不是刚刚发生的事了。)
            _state.update {
                it.copy(
                    swipeNote = outcome.message,
                    undoNote = null,
                    ratingGuardNote = null,
                    blindRatingArmed = null,
                )
            }
            // ⚠️ 但"被拦住"([SwipeOutcome.blocked] = 不知道电脑上是几星)**不许前进**:
            // 前进会让用户以为这一下记下了, 而它一个字节都没记下(见 decideSwipe 规矩 2′)。
            if (advanceOnWriteSuccess && !outcome.blocked) go(+1)
            return PushResult.NothingToPush(1)
        }
        val after = outcome.applyTo(before)
        // 真的改了什么才进撤销栈("没什么要写"的那两支持续在栈里的空步只会让用户按一次撤销
        // 却什么都没发生)。存的是**改之前**那份意图(见 UndoEntry)。
        undoStack.push(UndoEntry(path, before, after))
        decisions.applyLocal(after)
        _state.update { refresh(it).copy(swipeNote = outcome.message, undoNote = null, undoCount = undoStack.size) }
        val result = push(listOf(path))
        // 与手点星级同一条纪律(规格 §8.2): **写成功才前进**。失败留在这一张, 失败卡可见、可重试。
        if (result is PushResult.Ok && advanceOnWriteSuccess) go(+1)
        return result
    }

    /**
     * **撤销一步**(规格 §8.2 的多步撤销)。
     *
     * 语义与三条纪律全在 [DecisionStore.undo] 里(恢复"之前那份本地意图"; 只有电脑上确实收到过
     * 那次改动时才写一次反向写入; 写没写成由服务端说)。这里只做两件界面的事:
     *   · **回到被撤销的那一张** —— 撤销的语义就是"把刚刚那张拿回来"。写成功的那一支本来已经
     *     自动前进了, 不回到它的话用户根本看不到自己撤销了什么(而且失败卡会长在另一张照片上);
     *   · 把结论逐字翻成一句话([undoMessage]), 而且**绝不在没被确认时说"已撤销"**。
     *
     * 撤销**不产生新的撤销步**(没有重做): 栈只减不增, 所以按撤销不会自己把自己越撤越乱。
     */
    suspend fun undo(): UndoResult {
        val entry = undoStack.pop() ?: run {
            _state.update { it.copy(undoNote = undoMessage(UndoResult.Empty), undoCount = 0) }
            return UndoResult.Empty
        }
        _state.update {
            it.copy(writing = true, swipeNote = null, undoNote = null, lastBatchNote = null, undoCount = undoStack.size)
        }
        val result = decisions.undo(entry)
        // 队列里还有那张就回到它(索引来自**这一页**的照片列表, 不是服务端)。
        val idx = _state.value.photos.indexOfFirst { it.path == entry.path }
        if (idx >= 0 && idx != _state.value.index) go(idx - _state.value.index)
        _state.update { refresh(it).copy(writing = false, undoNote = undoMessage(result)) }
        return result
    }

    /**
     * 「重试写入」: 把**当前这张**的本地意图再推一次。
     *
     * 重试不产生新决策, 所以**不会**触发自动前进(那是"用户刚打了一张"才该发生的事)。
     */
    suspend fun retryCurrent(): PushResult {
        val path = currentPath() ?: return PushResult.NothingToPush(0)
        return push(listOf(path))
    }

    /**
     * 「N 张未同步·重试」: 把这次会话里所有改了但没写成的推一遍。
     *
     * 一批里可能只成一部分 —— [PushResult.Failed] 会如实带上成了几张、坏了几张以及
     * 每一项自己的码(纪律②: 半成功态必须显式)。
     */
    suspend fun retryAllUnsynced(): PushResult {
        val paths = decisions.unsynced().map { it.path }
        if (paths.isEmpty()) return PushResult.NothingToPush(0)
        val result = push(paths)
        // 一批里的半成功必须说出来(纪律②): 当前这张可能是成的那张, 光看它的失败卡会误以为全成了。
        _state.update {
            it.copy(
                // 逐条转述**每一项自己的结论**: 「没能确认」的那些不许被数成"没写进电脑"
                // (它们的逐项文案说的是"没有结论" —— 批次句必须与它一致)。
                lastBatchNote = (result as? PushResult.Failed)
                    ?.let { f -> WriteMessages.batchHead(f.requested, f.failures) },
            )
        }
        return result
    }

    /** 用户看过了那条失败提示(本地改动与"未同步"标记都留着, 只是不再重复唠叨)。 */
    fun dismissWriteFailure() {
        val path = currentPath() ?: return
        decisions.clearError(path)
        _state.update { refresh(it) }
    }

    private fun currentPath(): String? = _state.value.current?.path

    /**
     * 一次决策: 本地立刻生效(乐观更新) → 写给电脑 → **成功了才**按 [advanceOnWriteSuccess] 前进。
     *
     * `applyLocal` 在第一个挂起点之前执行, 所以"界面立刻变"不依赖网络。
     */
    private suspend fun commit(mutate: (Decision) -> Decision): PushResult {
        val path = currentPath() ?: return PushResult.NothingToPush(0)
        val before = decisions.get(path)
        val after = mutate(before)
        // 值真的变了才进撤销栈: 又点了一次同一颗星(值没变, 只是重新标成未同步)不该占一步撤销
        // —— 撤销它等于"什么都没发生"。判据是**逐字比对要发出去的那一份**(与 afterAck 同一条)。
        if (after.toDto() != before.toDto()) undoStack.push(UndoEntry(path, before, after))
        decisions.applyLocal(after)
        // 手点了星级/色标 ⇒ 上一句滑卡/撤销的结论不再适用(它说的是"这一滑没写"), 清掉。
        _state.update {
            refresh(it).copy(swipeNote = null, undoNote = null, undoCount = undoStack.size)
        }
        val result = push(listOf(path))
        // 规格 §8.2: 写成功才自动前进。失败**绝不**前进 —— 那会让用户以为打过了,
        // 而实际上那张在电脑上还是旧的(本任务最不能出的一种错)。
        if (result is PushResult.Ok && advanceOnWriteSuccess) go(+1)
        return result
    }

    private suspend fun push(paths: List<String>): PushResult {
        // 上一次批量重试的结论不再适用(用户又动了手) —— 先清掉, 免得界面上留着一句过期的话。
        _state.update { it.copy(writing = true, lastBatchNote = null) }
        val result = decisions.push(paths)
        _state.update { refresh(it).copy(writing = false) }
        return result
    }

    /**
     * 把 [DecisionStore] 里那一份**唯一**的真相抄进界面状态(星级、色标、同步状态、
     * 这条失败、跨张的未同步计数)。
     *
     * 每次换张/每次写完都过一遍这里, 是为了让"界面看到的"与"store 里的"不可能漂移 ——
     * [writing] 由调用方决定, 所以这里原样保留。
     */
    private fun refresh(st: ViewerState): ViewerState {
        val path = st.current?.path
        val decision = path?.let { decisions.get(it) }
        return st.copy(
            rating = decision?.rating,
            label = decision?.label,
            sync = decision?.sync ?: SyncState.UNTOUCHED,
            writeFailure = decision?.lastError,
            unsyncedCount = decisions.unsynced().size,
            // "电脑上是几星"的**唯一**来源(本地意图与远端值的合并结论都在 store 里算)。
            desktopRating = decision?.desktopRating ?: DesktopRating.Unknown,
            // 时态与数值来自**同一次** get(): 分开取就会出现"数值是新的、时态是旧的"。
            desktopRatingFromWrite = decision?.desktopRatingFromWrite ?: false,
            // 换张 / 写完 / 别的操作之后, "刚刚那一下被拦下"这件事不再适用 —— 一起清掉,
            // 否则用户会在另一张照片上看到一句讲上一张的话(与 swipeNote/undoNote 同一条)。
            ratingGuardNote = null,
            blindRatingArmed = null,
        )
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
