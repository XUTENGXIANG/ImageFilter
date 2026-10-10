// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 一次失败之后,**用户能按的那一个按钮**。
 *
 * 为什么把它做成一个显式的枚举而不是"每处各写一个 TextButton": 规格 §5.3 的纪律是
 * "每条码必须落到**用户下一步做什么**", 而 403 与 404 的下一步**不在同一台设备上**
 * (403 要去电脑上加共享根, 404 要在手机上刷新目录)。把动作也变成可断言的数据,
 * "403 与 404 的话和动作都不一样"才是一条**能被测试钉住**的契约, 而不是一句注释。
 */
enum class ViewerAction {
    /** 同一张图再要一次(网络抖动 / 电脑正忙)。 */
    RETRY,

    /** 这张照片在电脑上没了 → 回网格刷新这个目录(手机上能做的事)。 */
    RELOAD_FOLDER,

    /** 只有去电脑上改设置才有用(共享根 / 只读模式 / 换个版本的 ImageFilter)。 */
    PC_SIDE,

    /** token 真的失效了 → 回目录页进「设置」重新配对。 */
    RECONNECT,

    /** 手机上没有可做的动作(同一个请求重试必然再失败)。 */
    DISMISS,
}

/**
 * 看大图(查看器)的**中文文案表** —— 与 [PairingMessages] / [BrowseMessages] 同一种形状,
 * 也放在 Kotlin 里(理由逐条相同: 文案要能在 JVM 单测里被钉住, 而安卓资源读不到)。
 *
 * **为什么另起一张表**: 同一个码在"看大图"这条链路上的成因与动作, 与配对页/目录页都不同。
 * 两条最要紧的:
 *
 *   1. `forbiddenPath`(403, 不在共享根里)**与** `notFound`(404, 电脑上找不到这个文件)
 *      —— 规格 §5.3 已经明确**接受**这两个码可区分(存在性预言机那条取舍), 手机端就必须
 *      把这个区分**用起来**: 两句话说清两件事, 两个动作落在两台设备上。合成一句
 *      "读取失败, 请重试", 403 的用户会一直点重试(而正确动作在电脑上), 404 的用户会去
 *      电脑上改共享根(而照片真的没了)。
 *   2. `unsupportedForm` 在**媒体语境**里的意思是"这个构建解不开这张图"(规格 §5.3 的实测
 *      扩充, 例如缺 HEIC/AVIF 解码器), 与边车那条"无法安全合并"**不是一回事**。
 *      看图这条链路上根本没有边车, 所以这一档只许用**解码**的说法 —— 这张表里刻意
 *      **不出现**"边车 / XMP / 合并"这些词, 由 `ViewerMessagesTest` 钉住。
 *
 * 两张错误码表的纪律照旧: 本表只认 §5.3 的 HTTP 码集([ApiErrorCodes]) + 手机合成码。
 * `XmpErrorCodes` 那套(`writeProtect` / `readOnly` / `encoding` / `pathTooLong` …)在看图
 * 路径上**根本不会出现**, 落到 [GENERIC] 是正确行为, 不是漏配。
 */
object ViewerMessages {

    /**
     * 认不出来的码一律落到这里 —— **安全降级**: 服务端将来新加一档码, 老客户端要能说一句
     * 有动作的人话, 而不是崩、白屏, 或瞎猜原因。
     */
    const val GENERIC = "这张图没能显示出来（原因这台手机不认识）——点「重试」；" +
        "若一直失败，回网格刷新一次这个目录，并把电脑上的 ImageFilter 更新到最新版。"

    /**
     * 手机端**连异常都没拿到**的一次失败(Coil 的 `ErrorResult` 里 `throwable` 为 null)。
     *
     * 刻意**不放进任何服务端码表**: 它不是服务端说的码, 也不是"根本没有 HTTP 响应"那三档之一
     * ([ApiErrorCodes.CLIENT_SYNTHESIZED_CODES]), 所以 [ApiError.synthesized] 那个 `require`
     * 也永远造不出它 —— "这个错误到底从哪来"因此不会有第二种解释。它落到 [GENERIC] 那张
     * 兜底句上(有动作, 但不猜原因)。
     */
    const val UNCLASSIFIED = "viewerUnclassified"

    /**
     * 看图这条链路上**真会收到**的码(§5.3 的服务端码 + 手机合成码)。
     *
     * 逐个都必须拿到一句**不是兜底句**的话(测试逐条钉住)。这里没有
     * `conflict` / `diskFull` / `tooLarge` / `notXmp`: 前三个在预览这条路上产不出来
     * (规格 §5.3 明说本 build 里 409 不可达), `notXmp` 是**边车格式**的码 ——
     * 为永远走不到的分支编文案, 只会掩盖真会收到的那几个。
     */
    val REACHABLE: Set<String> = setOf(
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.FORBIDDEN_PATH,
        ApiErrorCodes.NOT_FOUND,
        ApiErrorCodes.BUSY,
        ApiErrorCodes.UNSUPPORTED_FORM,
        ApiErrorCodes.BAD_REQUEST,
        ApiErrorCodes.INTERNAL,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF,
    )

    /** 403 与 404 必须说清两件不同的事(见本对象的 KDoc 第 1 条)。 */
    const val FORBIDDEN_HEAD = "电脑不允许读这张照片"
    const val NOT_FOUND_HEAD = "电脑上找不到这张照片"

    /**
     * 一次失败 → 一句**可执行**的话。
     *
     * [cause] 只有 [ApiErrorCodes.UNAUTHORIZED] 时才该非空 —— 那是 401 消歧
     * ([ApiClient.classifyUnauthorized]) 的结论; 401 的三种成因在三句话上分的理由与配对页
     * 完全相同, 所以**复用** [PairingMessages.forFailure], 不抄第二份(抄一份就迟早只改一处)。
     */
    fun forFailure(code: String, cause: UnauthorizedCause? = null): String = when (code) {
        ApiErrorCodes.FORBIDDEN_PATH ->
            "电脑上的服务拒绝了这次读取（403 forbiddenPath）——这张照片不在电脑的共享根里，" +
                "或者电脑上正开着只读模式。去电脑上「设置 → 图库服务」把它的上级目录加进共享根；" +
                "在这台手机上无论重试多少次都不会变。"
        ApiErrorCodes.NOT_FOUND ->
            "电脑上找不到这张照片了（404 notFound）——它可能被改名、移走或删除了。" +
                "回网格点「刷新」看一眼这个目录现在还有什么，再决定看哪一张。"
        ApiErrorCodes.BUSY ->
            "电脑正忙（423 busy）——它可能正在解码另一张图，或者这次请求已经被更新的请求取代了" +
                "（换到手的那张图优先）。稍等一下再点「重试」。"
        ApiErrorCodes.BAD_REQUEST ->
            "电脑说这个请求不合法（400 badRequest）——同一个请求重试还是会被拒。" +
                "请把电脑上的 ImageFilter 更新到最新版；若已是最新版，请把这张照片的格式反馈一下。"
        ApiErrorCodes.UNSUPPORTED_FORM ->
            "电脑上这个构建解不开这张图（422 unsupportedForm）——例如缺这种格式的解码器" +
                "（HEIC / AVIF / 某些视频），或者这张文件本身不是它能读的图片。" +
                "换一张，或把电脑上的 ImageFilter 更新到最新版。"
        ApiErrorCodes.INTERNAL ->
            "电脑上的服务出错了（500 internal）——这次失败在电脑那一侧。" +
                "先点「重试」；若一直失败，看电脑上 ImageFilter 的日志。"
        // 传输层与鉴权层: 用户动作与配对页**一模一样**, 所以查同一张表(文案纪律)
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF -> PairingMessages.forFailure(code, cause)
        else -> GENERIC
    }

    /**
     * 一次失败 → **用户下一步做什么**。
     *
     * 顺序要紧: 401 先按消歧的结论分(同一个 401 有三种成因), 其余码一对一。
     * 判不出来的码给 [ViewerAction.RETRY] —— 它是唯一"重试不会有坏结果"的动作。
     */
    fun actionFor(code: String, cause: UnauthorizedCause? = null): ViewerAction = when (code) {
        ApiErrorCodes.FORBIDDEN_PATH -> ViewerAction.PC_SIDE
        ApiErrorCodes.NOT_FOUND -> ViewerAction.RELOAD_FOLDER
        ApiErrorCodes.BUSY,
        ApiErrorCodes.INTERNAL,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF -> ViewerAction.RETRY
        ApiErrorCodes.BAD_REQUEST,
        ApiErrorCodes.UNSUPPORTED_FORM -> ViewerAction.DISMISS
        ApiErrorCodes.UNAUTHORIZED -> when (cause) {
            UnauthorizedCause.TOKEN_ROTATED, null -> ViewerAction.RECONNECT
            UnauthorizedCause.ENDPOINT_NOT_EXPOSED -> ViewerAction.PC_SIDE
            UnauthorizedCause.UNDETERMINED -> ViewerAction.RETRY
        }
        else -> ViewerAction.RETRY
    }

    /** 动作用按钮上那几个字。**动作不同就字不同** —— 否则"不同动作"在界面上看不出来。 */
    fun actionLabel(action: ViewerAction): String = when (action) {
        ViewerAction.RETRY -> "重试"
        ViewerAction.RELOAD_FOLDER -> "回网格刷新"
        ViewerAction.PC_SIDE -> "回网格（要去电脑上改）"
        ViewerAction.RECONNECT -> "回目录页重新配对"
        ViewerAction.DISMISS -> "关闭"
    }

    /** 失败卡的标题: 403/404 各有各的说法, 其余用码本身当副标题。 */
    fun headFor(code: String): String = when (code) {
        ApiErrorCodes.FORBIDDEN_PATH -> FORBIDDEN_HEAD
        ApiErrorCodes.NOT_FOUND -> NOT_FOUND_HEAD
        else -> "这张图没能显示出来"
    }
}
