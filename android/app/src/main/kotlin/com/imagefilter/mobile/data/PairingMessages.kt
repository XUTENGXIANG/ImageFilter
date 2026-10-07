// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 配对与错误码的**中文文案表**。
 *
 * 为什么文案写在 Kotlin 里而不是 `res/values/strings.xml`:
 *   · 规格 §5.3 的纪律是"文案按 code 本地化", 而"哪个 code 落到哪句话"是一条**必须能测**
 *     的契约(含未知码的安全降级)。安卓资源在 JVM 单测里读不到(那要引 Robolectric,
 *     与本计划"不新增依赖"的约束冲突), 所以文案必须放在纯 Kotlin 里才能被钉住;
 *   · 将来真要做多语言时, 这个对象就是"key → 文案"的表本身, 迁到资源文件是机械的。
 *
 * 纪律:**已知码不许落到兜底句上**。兜底句是给"服务端将来新加一档码"用的,
 * 它一旦被某个已知码用上, 用户拿到的就是一句没有动作的废话。
 */
object PairingMessages {

    /** 认不出这个码时的兜底句 —— 有动作(重试), 但**不猜**原因。 */
    const val GENERIC = "配对没成功，请重试。若反复失败，把下面这份排查清单逐条走一遍。"

    /**
     * 规格 §1.3 的四条硬前提。首启引导**必须**把它列出来 ——
     * 这四件事都会表现为"手机连不上", 而用户从手机这一侧看不出是哪一条。
     */
    val PREREQUISITES: List<String> = listOf(
        "电脑没有睡眠（睡眠后服务就断了）",
        "电脑上 ImageFilter 正在运行",
        "设置里的「图库服务」已开启",
        "手机与电脑在同一网络（或都加入了同一个 EasyTier / Tailscale 网络）",
    )

    /** 本地输入问题的文案。 */
    fun forInputProblem(problem: PairingInputProblem): String = when (problem) {
        PairingInputProblem.EMPTY_URL ->
            "请填电脑上显示的监听地址，例如 http://192.168.1.5:8765"
        PairingInputProblem.BAD_URL ->
            "这个地址读不出来：要 http://主机:端口 的形式（只填主机与端口也行，会按 http:// 处理）"
        PairingInputProblem.EMPTY_TOKEN ->
            "请填电脑设置页里的配对码（64 位十六进制字符）"
        PairingInputProblem.BAD_TOKEN_SHAPE ->
            "配对码必须是 64 位十六进制字符（0-9 / a-f）——检查是不是少抄、多抄，或粘进了别的内容"
    }

    /**
     * 一次失败 → 一句**可执行**的话(规格 §5.3: 每条码必须落到"用户下一步做什么")。
     *
     * [cause] 只有 [ApiErrorCodes.UNAUTHORIZED] 时才该非空; 三种 401 成因给**三句不同的话**,
     * 这正是 Task 2 那条消歧存在的意义 —— 三句都写成"配对码不对"的话, 消歧就白做了,
     * 而隧道断了的用户会照着它去白换一遍配对码。
     */
    fun forFailure(code: String, cause: UnauthorizedCause? = null): String = when (code) {
        ApiErrorCodes.UNAUTHORIZED -> when (cause) {
            // 没有消歧结论时也只能照最可能的成因说 —— 但那种组合在生产路径上不会出现:
            // ApiClient 只在 401 上抛 unauthorized, 而 401 一定会被消歧。
            null, UnauthorizedCause.TOKEN_ROTATED ->
                "配对码不对，或已在电脑上轮换过——去电脑上重新复制一次"
            UnauthorizedCause.ENDPOINT_NOT_EXPOSED ->
                "服务是通的，但这个版本的 ImageFilter 不提供 /api/v1/shares——请更新电脑上的 ImageFilter"
            UnauthorizedCause.UNDETERMINED ->
                "刚收到一次 401，但复验没能打通（网络中断或电脑睡了）——先检查网络与电脑再重试，" +
                    "先别急着换配对码"
        }
        ApiErrorCodes.SERVICE_OFF -> withPrerequisites(
            "连不上电脑（没有任何进程在应答）。逐条确认：",
        )
        ApiErrorCodes.TIMEOUT -> withPrerequisites(
            "电脑没有回应（超时）。逐条确认：",
        )
        ApiErrorCodes.NETWORK -> withPrerequisites(
            "连接中途断了（网络不稳）。逐条确认：",
        )
        ApiErrorCodes.FORBIDDEN_PATH ->
            "电脑上的服务拒绝了这次请求（403 forbiddenPath）——去设置里确认共享根，以及服务是否处在只读模式"
        ApiErrorCodes.NOT_FOUND ->
            "电脑上的服务说没有这个端点（404）——确认地址填的是 ImageFilter 的图库服务，而不是别的程序"
        ApiErrorCodes.CONFLICT ->
            "电脑上的服务报了冲突（409）——稍等片刻再试一次"
        ApiErrorCodes.BUSY ->
            "电脑上的服务正忙（423）——它可能正在处理另一个请求，稍等再试"
        ApiErrorCodes.DISK_FULL ->
            "电脑磁盘满了（507 diskFull）——在电脑上清理出空间再试"
        ApiErrorCodes.TOO_LARGE ->
            "请求内容超出电脑上的上限（413 tooLarge）——这通常是服务端配置问题，请反馈"
        ApiErrorCodes.NOT_XMP ->
            "电脑上的服务说这不是它认的边车格式（422 notXmp）——配对本身没成功，请反馈"
        ApiErrorCodes.UNSUPPORTED_FORM ->
            "电脑上的这个构建解不开这种格式（422 unsupportedForm）——请更新电脑上的 ImageFilter"
        ApiErrorCodes.BAD_REQUEST ->
            "电脑上的服务说这个请求不合法（400 badRequest）——检查地址是不是多写/少写了一段路径"
        ApiErrorCodes.INTERNAL ->
            "电脑上的服务出错了（500 internal）——看电脑上 ImageFilter 的日志"
        else -> GENERIC
    }

    /** 圈子数字编号。写死四个, 与 [PREREQUISITES] 的四条一一对应(测试钉住条数)。 */
    private val NUMBERING = listOf("①", "②", "③", "④")

    /** 把 §1.3 的四条硬前提**整份**嵌进错误文案 —— 少列一条, 就少一条用户能自己排掉的原因。 */
    private fun withPrerequisites(head: String): String =
        head + PREREQUISITES.mapIndexed { i, text -> "${NUMBERING[i]} $text" }.joinToString("；") + "。"
}
