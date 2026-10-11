// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 一次**文件夹级决策读取**的失败里的 `code` 来自哪张表。
 *
 * 与 [FailureSource] 是同一个概念(决定查哪张本地化表), 但这里**只有两档** —— 读取这条链路上
 * **不可能**出现 XMP 码:
 *   · XMP 码集是 `WriteSummary.failures[].code` 的东西(写边车**逐项**回报);
 *   · 而 `GET /decisions` 里单项"读不出来"(没有边车 / 权限 / 不是 XMP / 超限)在服务端
 *     **本来就降级成 `rating: null`**(见 `src-tauri/src/server/routes.rs::decisions_get` 的注释),
 *     它不是错误, 更不是一个码。
 *
 * 复用 [FailureSource] 会让 `XMP` 成为一个**能被构造出来**的状态: 要么为它写一条永远走不到的
 * 分支(死代码), 要么把它悄悄归到某张表的文案上 —— 后者会让用户拿着"边车是元素形式"去查一个
 * 根本不存在的读取问题。类型上不让它存在, 是这条纪律最硬的形态。
 */
enum class ReadSource {
    /** 服务端 HTTP 错误体(或状态码兜底)给的码 —— 查规格 §5.3 那张表([ApiErrorCodes])。 */
    HTTP,

    /** 手机自己合成的码([ApiErrorCodes.CLIENT_SYNTHESIZED_CODES])或本表自己的 [ReadMessages.INCOMPLETE]。 */
    CLIENT,
}

/**
 * 一次**文件夹级决策读取**的失败 —— 整批一条, 不是逐项。
 *
 * 与 [WriteFailure] 一样只装"码 + 来源", 不缓存句子: 文案随时按码取, 所以改文案不会留下旧句子。
 */
data class DecisionReadFailure(
    val code: String,
    /** [code] 来自哪张表 —— [ReadMessages] 只按它分派, **绝不**按码去猜。 */
    val source: ReadSource,
    /** 只有 401 才有资格非空(那是 [ApiClient.classifyUnauthorized] 的结论)。 */
    val cause: UnauthorizedCause? = null,
)

/**
 * 一次决策读取失败之后, **用户能按的那一个按钮**。
 *
 * 与 [WriteAction] / [ViewerAction] 分成三套的理由逐条相同: 同一个码在"读这个文件夹的星级"
 * 这条链路上, 用户要做的事与"写边车"不一样 —— `WriteAction.RETRY` 的按钮字是"重试**写入**",
 * 摆在一张读取失败卡上就是一句错话。
 */
enum class ReadAction {
    /** 再读一次这个文件夹的决策(网络抖 / 电脑忙 / 结果没被确认)。 */
    RETRY,

    /** token 真的失效了 → 回目录页的「设置」重新配对。 */
    RECONNECT,

    /** 只有去电脑上改设置才有用(共享根)。 */
    PC_SIDE,

    /** 手机上再做什么都没用(请求本身不合法)。 */
    DISMISS,
}

/**
 * 读这个文件夹的**星级/色标**这条链路的中文文案表 —— 与 [PairingMessages] / [BrowseMessages] /
 * [ViewerMessages] / [WriteMessages] 同一种形状, 也放在 Kotlin 里(理由逐条相同: 文案要能在
 * JVM 单测里被钉住, 而安卓资源在单测里读不到)。
 *
 * **为什么另起一张表**: 同一个码在"读决策"与"写边车"两条链路上, 用户要做的事**不一样**:
 *   · `forbiddenPath`(403) —— 写那条会说"可能开着只读模式"; 而**只读模式只拦写、不拦读**
 *     (`routes.rs::refuse_writes_in_read_only` 判的是 `is_write_method`), 读失败就只有一个原因:
 *     它不在共享根里。照抄写那条的表, 用户会去关一个与本次失败**无关**的开关;
 *   · 而且这一次失败还多一个后果必须说出来: **筛选栏上的张数说不出准确的数**
 *     (不是 0 —— 见 [COUNTS_UNKNOWN_TAIL])。
 *
 * ⚠️ 分派**只看 [DecisionReadFailure.source]**: 两张表的交集(`busy` / `diskFull` / `tooLarge` /
 * `notXmp` / `unsupportedForm`)光看码本身分不出来, 而读取这条路上服务端码与手机合成码
 * (超时/网络/服务没开)绝不许互相冒充。
 */
object ReadMessages {

    /**
     * 手机端合成的码: 服务端 200 了, 但**没有把问到的每一张都回报回来**(少一条就落这一档)。
     *
     * 与 [WriteMessages.UNCONFIRMED] 同一条理由: 这不是"失败的原因不明", 而是**没有结论** ——
     * 没被回报的那些照片, 电脑上到底是几星/什么色标, 这台手机此时**不知道**。所以张数只能说
     * "未统计"; 而已经回报回来的那些是真值, 照常采纳(见 `DecisionStore.pullRemote`)。
     *
     * 它**刻意不放进任何服务端码表**: 服务端永远不发它, "这个错从哪来"因此不会有第二种解释。
     */
    const val INCOMPLETE = "readIncomplete"

    /**
     * 手机端合成的码: 服务端把问到的**每一张都回报了**, 但其中一些项的边车**读不动**
     * (`sidecarReadable = false`, 最典型的是边车正被 Lightroom 之类的程序占着)。
     *
     * 为什么它也必须让张数降级: 那几张在电脑上是几星**不知道** —— 它们既不是"没有星级", 也没有
     * 被数进任何一档。拿这样一个下界当"筛选栏上的数"与 [INCOMPLETE] 是同一句谎话。
     * (已经读到的那些照常采纳: 角标、格子、滑动都按真值走, 没读到的那几张在格子上画的是"未知"。)
     */
    const val SIDECAR_UNREADABLE = "sidecarUnreadable"

    /**
     * **每一次**读取失败都必须说清的那件事。
     *
     * 读者是"已经在这台手机上打完分、正准备看筛选栏"的用户, 他此刻最可能误解的两件事:
     *   ① 筛选栏上那些张数是不是"就是这些"(其实电脑上原有的星级/色标这次没读到, 数偏小);
     *   ② 我刚才在这台手机上打的分是不是没了(**没有** —— 读取失败绝不碰本地意图)。
     */
    const val COUNTS_UNKNOWN_TAIL =
        "在这之前，筛选栏上的张数说不出准确的数（它不会拿 0 顶上）：电脑上原有的星级/色标这次" +
            "没读到，而**你这台手机上的改动一个都没丢**。"

    /**
     * 读取这条链路上**真会收到**的码(§5.3 的服务端码 + 手机合成码 + 本表自己的 [INCOMPLETE])。
     *
     * 每一个都必须拿到一句**不是兜底句**的话(测试逐条钉住)。这里没有
     * `conflict` / `diskFull` / `tooLarge` / `notXmp` / `unsupportedForm`: 读边车那条服务端实现
     * (`xmp.rs::read_decisions`)产不出它们 —— 为永远走不到的分支编文案, 只会掩盖真会收到的几个。
     */
    val REACHABLE: Set<String> = setOf(
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.FORBIDDEN_PATH,
        ApiErrorCodes.NOT_FOUND,
        ApiErrorCodes.BAD_REQUEST,
        ApiErrorCodes.INTERNAL,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF,
        INCOMPLETE,
        SIDECAR_UNREADABLE,
    )

    /** 读取这条链路**认不出来**的 HTTP 码(有动作, 但不猜原因)。 */
    fun httpGeneric(code: String): String = line(
        "这个文件夹的星级/色标没读出来（原因这台手机不认识：$code）——点「重试读取」；" +
            "若一直失败，把这条码反馈给开发者",
    )

    /** 读取这条链路上认不出来的**手机合成码**那一档。 */
    fun clientGeneric(code: String): String = line(
        "这个文件夹的星级/色标没能确认读到了什么（$code）——点「重试读取」再确认一次",
    )

    /**
     * 一次失败 → 一句**可执行**的话。
     *
     * ⚠️ 分派**只看 [DecisionReadFailure.source]** —— 见本对象的 KDoc。
     */
    fun forFailure(f: DecisionReadFailure): String = when (f.source) {
        ReadSource.HTTP -> forHttpCode(f)
        ReadSource.CLIENT -> forClientCode(f.code)
    }

    /**
     * 读取失败卡的标题。**403 与 404 各有各的说法** —— 它们要做的事在两台不同的设备上
     * (403 去电脑上加共享根; 404 在手机上重试, 那会顺手重扫一遍目录)。
     */
    fun headFor(f: DecisionReadFailure): String = when {
        f.source == ReadSource.HTTP && f.code == ApiErrorCodes.FORBIDDEN_PATH -> "电脑不允许读这个文件夹"
        f.source == ReadSource.HTTP && f.code == ApiErrorCodes.NOT_FOUND -> "电脑上找不到这个文件夹里的照片"
        f.source == ReadSource.CLIENT && f.code == INCOMPLETE -> "这个文件夹的星级/色标只读回来一部分"
        f.source == ReadSource.CLIENT && f.code == SIDECAR_UNREADABLE -> "有几张的边车在电脑上读不动"
        else -> "这个文件夹的星级/色标没读出来"
    }

    /**
     * 一次失败 → **用户下一步做什么**。
     *
     * 顺序要紧: 401 先按消歧的结论分(同一个 401 有三种成因), 其余一对一。
     * 判不出来的码给 [ReadAction.RETRY] —— 重试读取是幂等的, 是唯一"不会有坏结果"的动作。
     */
    fun actionFor(f: DecisionReadFailure): ReadAction = when (f.source) {
        ReadSource.HTTP -> when (f.code) {
            ApiErrorCodes.UNAUTHORIZED -> when (f.cause) {
                UnauthorizedCause.ENDPOINT_NOT_EXPOSED -> ReadAction.PC_SIDE
                UnauthorizedCause.UNDETERMINED -> ReadAction.RETRY
                // TOKEN_ROTATED 与"还没有消歧结论"都只能照最可能的成因说: 去重新配对
                UnauthorizedCause.TOKEN_ROTATED, null -> ReadAction.RECONNECT
            }
            ApiErrorCodes.FORBIDDEN_PATH -> ReadAction.PC_SIDE
            ApiErrorCodes.BAD_REQUEST -> ReadAction.DISMISS
            // 404 / busy / internal / 认不出来的码: 再读一次是唯一无害且常常有效的动作
            else -> ReadAction.RETRY
        }
        ReadSource.CLIENT -> ReadAction.RETRY
    }

    /** 动作用按钮上那几个字。**动作不同就字不同** —— 否则"不同动作"在界面上看不出来。 */
    fun actionLabel(action: ReadAction): String = when (action) {
        ReadAction.RETRY -> "重试读取"
        ReadAction.RECONNECT -> "回目录页重新配对"
        ReadAction.PC_SIDE -> "知道了（要去电脑上改）"
        ReadAction.DISMISS -> "知道了"
    }

    /** HTTP 码查 **§5.3 那张表**。 */
    private fun forHttpCode(f: DecisionReadFailure): String = when (f.code) {
        ApiErrorCodes.UNAUTHORIZED ->
            // 401 的三种成因在三句话上分的理由与配对页完全相同 —— 复用, 不抄第二份, 只把
            // "筛选栏张数不可信"这个后果接在后面。
            line(PairingMessages.forFailure(f.code, f.cause))
        ApiErrorCodes.FORBIDDEN_PATH -> line(
            "电脑不允许读这个位置（403 forbiddenPath）——它不在电脑的共享根里。" +
                // 这一句是刻意的: 只读模式拦的是**写**(服务端判的是写方法), 拿它当读取失败的原因
                // 会让用户去关一个与本次失败无关的开关。
                "**只读模式拦的是写、不拦读**，所以这一条只能去电脑上「设置 → 图库服务」把它的" +
                "上级目录加进共享根；在这台手机上重试多少次都不会变",
        )
        ApiErrorCodes.NOT_FOUND -> line(
            "电脑上找不到这个文件夹里的照片了（404 notFound）——它可能被改名、移走或删除了。" +
                "点「重试读取」会顺手重新扫一遍这个文件夹",
        )
        ApiErrorCodes.BAD_REQUEST -> line(
            "手机发出的读取请求不合法（400 badRequest）——**这是 App 的问题，不是你的路径有问题**。" +
                "同一个请求重试还是会被拒：请把这条反馈给开发者",
        )
        ApiErrorCodes.INTERNAL -> line(
            "电脑上的服务出错了（500 internal）——这次失败在电脑那一侧。" +
                "先点「重试读取」；若一直失败，看电脑上 ImageFilter 的日志",
        )
        else -> httpGeneric(f.code)
    }

    /** 手机合成的码([ApiErrorCodes.CLIENT_SYNTHESIZED_CODES] + 本表自己的 [INCOMPLETE] / [SIDECAR_UNREADABLE])。 */
    private fun forClientCode(code: String): String = when (code) {
        INCOMPLETE -> line(
            "电脑只回报了问到的**一部分**照片的星级/色标——有的没回来。" +
                "已经回来的那些照样算数（该显示的星级/色标角标都会显示），但**张数**不敢替你数",
        )
        SIDECAR_UNREADABLE -> line(
            "电脑把问到的每一张都回报了，但其中**有几张的边车读不动**（最典型的是它正被别的程序" +
                "占着，例如 Lightroom 正在写它）。那几张贴在电脑上是几星，这台手机现在**不知道**——" +
                "它们不会被当成「没有星级」（所以「保留」那一滑不会去覆盖它们），格子上会显示成未知。" +
                "已经读到的那些照样算数（角标照常显示），但**张数**不敢替你数；" +
                "等那个程序松开边车之后点「重试读取」",
        )
        ApiErrorCodes.SERVICE_OFF,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK ->
            // 三条传输故障的用户动作与配对页一模一样(去电脑上把服务开起来 / 看网络), 所以查同一张表。
            line(PairingMessages.forFailure(code))
        else -> clientGeneric(code)
    }

    /** 每一句话后面都必须带上"张数不可信、本地改动没丢"这个后果 —— 由 [COUNTS_UNKNOWN_TAIL] 统一给。 */
    private fun line(base: String): String = base.trimEnd('。', '；', '，', '、') + "。" + COUNTS_UNKNOWN_TAIL
}
