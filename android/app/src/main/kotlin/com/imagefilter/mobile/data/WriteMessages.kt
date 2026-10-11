// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 一条写入失败里的 `code` **来自哪张表** —— 决定查哪张本地化表(规格 §5.3 的接口细节)。
 *
 * 为什么必须显式带着它、而不是"看码长什么样去猜": 两张表的交集是
 * `notXmp` / `unsupportedForm` / `tooLarge` / `busy` / `diskFull` **五个**(M1′ 实测修正),
 * 光凭码本身根本分不出它是 HTTP 错误体里的还是 `WriteSummary.failures[]` 里的 ——
 * 而 `unsupportedForm` 在两条链路上的**意思完全不同**:
 *   · HTTP/看图那条 = "这个构建解不开这张图"(缺 HEIC/AVIF 解码器);
 *   · XMP/写边车这条 = "边车是元素形式, 无法安全合并"。
 * 混了表, 用户就会拿着"边车有问题"去查一个根本不存在的边车问题(计划 Task 2 Step 4 点名的坑)。
 */
enum class FailureSource {
    /** `WriteSummary.failures[].code` —— XMP 码集([XmpErrorCodes])。 */
    XMP,

    /** HTTP 错误体的 `{code}` —— 规格 §5.3 的码集([ApiErrorCodes])。 */
    HTTP,

    /** 手机端自己合成的码([WriteMessages.UNCONFIRMED], 或 [ApiErrorCodes.CLIENT_SYNTHESIZED_CODES])。 */
    CLIENT,
}

/**
 * **一条**写入失败 —— 逐项, 不是整批一个布尔值。
 *
 * [path] 是服务端回报的路径(逐字, 可能是规范化后的形态); [detail] 是服务端的中文诊断,
 * **只用于排查, 不进 UI 文案**(文案纪律: 文案在手机端按 [code] 本地化)。
 */
data class WriteFailure(
    val path: String,
    val code: String,
    /** [code] 来自哪张表 —— [WriteMessages] 只按它分派, 绝不按码去猜。 */
    val source: FailureSource,
    val detail: String = "",
)

/**
 * 一次写入失败之后, **用户能按的那一个按钮**。
 *
 * 与 [ViewerAction] 分开的理由和两张码表一样: 同一个码在"看这张图"和"把这张的分写进边车"
 * 两条链路上, 用户要做的事不一样(看图 404 = 刷新目录; 写 404 = 照片没了, 改动没落盘)。
 * 做成数据就能被测试钉住"不同动作给不同字"。
 */
enum class WriteAction {
    /** 同一批再写一次(电脑正忙 / 网络抖 / 结果没被确认)。 */
    RETRY,

    /** 这张照片在电脑上没了 → 回网格刷新这个目录。 */
    RELOAD_FOLDER,

    /** token 真的失效了 → 回目录页的「设置」重新配对。 */
    RECONNECT,

    /** 只有去电脑上改设置/文件属性才有用(只读、权限、磁盘满)。 */
    PC_SIDE,

    /** 手机上再做什么都没用(边车不是 XMP、超限、请求本身不合法)。 */
    DISMISS,
}

/**
 * 写边车这条链路的**中文文案表** —— 与 [PairingMessages] / [BrowseMessages] / [ViewerMessages]
 * 同一种形状, 也放在 Kotlin 里(理由逐条相同: 文案要能在 JVM 单测里被钉住, 安卓资源读不到)。
 *
 * ⚠️ **本表只认 `source` 指定的那一张码表**:
 *   · `FailureSource.XMP`  → [XmpErrorCodes](`readOnly` / `writeProtect` / `permission` /
 *     `encoding` / `pathTooLong` 只在它里面有);
 *   · `FailureSource.HTTP` → [ApiErrorCodes](规格 §5.3);
 *   · `FailureSource.CLIENT` → 手机自己合成的码。
 *
 * 服务端**只发 `code`**(§5.3 的实测修正: `IntoResponse` 只序列化一个字段), 所以每句话都得由
 * 本表按码给出 —— 也正是因此,"已知码不许落到兜底句"这条纪律在这里格外要紧:
 * 少配一档, 用户拿到的就是一句没有动作的废话(而他的分数确实没落盘)。
 */
object WriteMessages {

    /**
     * 手机端合成的码: **服务端没有回报这一项的写入结果**。
     *
     * 什么情况会落到它: 200 的 `written + skipped + failed` **对不上**我们发出去的项数
     * (`xmp.rs::write_decisions` 逐项计数, 三者之和就是输入项数 —— 所以对不上就是有项的
     * 结果没被确认), 或者 `failed` 大于 `failures[]` 的长度(有失败没给出是哪一项)。
     *
     * 为什么值得单独一档而不是"就当成功了": 规格 §11 纪律③ "未同步 ≠ 已同步" ——
     * 没被确认的写入**不许**当成成功。宁可多标一张"待重试"(重试是幂等的: 值没变时服务端报
     * `changed=false` → `skipped`), 也不能让用户以为分数已经落到电脑上了。
     *
     * 它**刻意不放进任何服务端码表**: 服务端永远不会发它, 与
     * [ViewerMessages.UNCLASSIFIED] 同一条理由 —— "这个错到底从哪来"不许有第二种解释。
     */
    const val UNCONFIRMED = "unconfirmed"

    /** 写边车这条路上, 认不出来的码一律落到这一档(有动作, 但不猜原因)。 */
    fun xmpGeneric(code: String): String =
        "电脑没能把这次改动写进边车（原因这台手机不认识：$code）——你这台手机上的改动还留着。" +
            "点「重试写入」；若一直失败，把这条码和电脑上 ImageFilter 的日志一起反馈。"

    /** `XmpErrorCodes.UNKNOWN` 的文案 —— 它是服务端**真的会发**的一档, 与上面的"认不出来"不是一回事。 */
    const val XMP_UNKNOWN =
        "电脑写这张的边车时出了没预料到的错（unknown）——你这台手机上的改动还留着。" +
            "点「重试写入」；若一直失败，看电脑上 ImageFilter 的日志。"

    /** HTTP 码里认不出来的那一档(同样有动作, 不猜原因)。 */
    fun httpGeneric(code: String): String =
        "电脑拒绝了这次写入（原因这台手机不认识：$code）——你这台手机上的改动还留着。" +
            "点「重试写入」；若一直失败，把这条码反馈给开发者。"

    /** 手机合成码里认不出来的那一档。 */
    fun clientGeneric(code: String): String =
        "这次写入没能确认（$code）——你这台手机上的改动还留着，点「重试写入」再确认一次。"

    /**
     * 一次失败 → 一句**可执行**的话。
     *
     * ⚠️ 分派**只看 [WriteFailure.source]**: `notXmp` / `unsupportedForm` / `tooLarge` /
     * `busy` / `diskFull` 两张表里都有, 按"码在不在某张表里"去分派必然错一处。
     */
    fun forFailure(f: WriteFailure): String = when (f.source) {
        FailureSource.XMP -> forXmpCode(f.code)
        FailureSource.HTTP -> forHttpCode(f.code)
        FailureSource.CLIENT -> forClientCode(f.code)
    }

    /**
     * 逐项失败的码查 **XMP 表**。这张表里的每一档都对应"边车文件本身的问题",
     * 所以话里必须说清"改动没有落盘" —— 用户最需要知道的就是这一点。
     */
    private fun forXmpCode(code: String): String = when (code) {
        XmpErrorCodes.READ_ONLY ->
            "电脑说这个边车是**只读**的（readOnly）——所以这次改动没有落盘。" +
                "去电脑上把这个 .xmp 文件的「只读」属性去掉，再点「重试写入」；你的评分还在这台手机上。"
        XmpErrorCodes.WRITE_PROTECT ->
            "电脑说这个位置被**写保护**（writeProtect）——可能是整个文件夹或整个盘。这次改动没有落盘。" +
                "去电脑上处理掉写保护，再点「重试写入」。"
        XmpErrorCodes.PERMISSION ->
            "电脑**没有权限**写这个边车（permission）——这次改动没有落盘。" +
                "去电脑上确认这个文件夹的权限，再点「重试写入」。"
        XmpErrorCodes.DISK_FULL ->
            "电脑**磁盘满了**（diskFull）——这次写没有落盘。去电脑上腾一点空间，再点「重试写入」；" +
                "你的评分还在这台手机上。"
        XmpErrorCodes.BUSY ->
            "电脑上正忙（busy）——这个边车正被另一个操作占着，或者这次改动已经被更新的改动取代了。" +
                "稍等一秒再点「重试写入」；你的改动还在这台手机上。"
        XmpErrorCodes.NOT_XMP ->
            "这个边车**不是标准的 XMP**（notXmp）——电脑上无法安全合并，所以这次改动没有落盘。" +
                "重试不会有帮助：去电脑上确认这个 .xmp 文件（或先把它移走），再回来打分。"
        XmpErrorCodes.UNSUPPORTED_FORM ->
            // ⚠️ 这条是**写边车**的语义(元素形式), 绝不是"电脑解不开这张图"。
            // 看图那条链路用的是 ViewerMessages 表里的**解码**说法, 两者绝不许互相借用。
            "这个边车是**元素形式**（unsupportedForm）——电脑上无法安全合并，所以这次改动没有落盘。" +
                "重试不会有帮助：去电脑上看一眼这个 .xmp 文件的内容。"
        XmpErrorCodes.TOO_LARGE ->
            "这个边车**超过了 1MB 上限**（tooLarge）——电脑拒绝写它，所以这次改动没有落盘。" +
                "重试不会有帮助。"
        XmpErrorCodes.ENCODING ->
            "这个边车的**编码不是 UTF-8**（encoding）——电脑上无法安全合并，所以这次改动没有落盘。" +
                "重试不会有帮助：去电脑上确认这个 .xmp 的编码。"
        XmpErrorCodes.PATH_TOO_LONG ->
            "这张照片的**路径太长**（pathTooLong）——Windows 的路径长度限制让电脑写不了同名的边车。" +
                "在电脑上把照片移到层次更浅的目录（例如 D:\\照片\\2026），再点「重试写入」。"
        XmpErrorCodes.UNKNOWN -> XMP_UNKNOWN
        else -> xmpGeneric(code)
    }

    /**
     * 整个请求失败时的码查 **§5.3 的 HTTP 表**。
     */
    private fun forHttpCode(code: String): String = when (code) {
        ApiErrorCodes.UNAUTHORIZED ->
            "配对码已失效（401 unauthorized）——这次改动**没有**写到电脑上。" +
                "去电脑上重新复制一次配对码，在「设置」里重新配对；你这台手机上的改动还留着。"
        ApiErrorCodes.FORBIDDEN_PATH ->
            "电脑不允许写这个位置（403 forbiddenPath）——它不在共享根里，或者电脑上正开着**只读模式**。" +
                "去电脑上「设置 → 图库服务」把它的上级目录加进共享根、并关掉只读模式；" +
                "在这台手机上重试多少次都不会变。你的改动还留着。"
        ApiErrorCodes.NOT_FOUND ->
            "电脑上找不到这张照片了（404 notFound）——它可能被改名、移走或删除了，所以这次改动没有落盘。" +
                "回网格点「刷新」看一眼这个目录现在还有什么。"
        ApiErrorCodes.CONFLICT ->
            "电脑上说这个文件有冲突（409 conflict）——这次改动没有落盘。先点「重试写入」；" +
                "若一直失败，看电脑上这个 .xmp 文件是不是被别的程序占着。"
        ApiErrorCodes.BUSY ->
            "电脑上正忙（423 busy）——它可能正在解码另一张图，或者这次改动已经被更新的改动取代了。" +
                "稍等一秒再点「重试写入」；你的改动还在这台手机上。"
        ApiErrorCodes.DISK_FULL ->
            "电脑磁盘满了（507 diskFull）——这次写没有落盘。去电脑上腾一点空间，再点「重试写入」。"
        ApiErrorCodes.TOO_LARGE ->
            "这个边车超过了 1MB 上限（413 tooLarge）——电脑拒绝写它，重试不会有帮助。"
        ApiErrorCodes.NOT_XMP ->
            "这个边车不是标准的 XMP（422 notXmp）——电脑上无法安全合并，所以这次改动没有落盘。" +
                "重试不会有帮助。"
        ApiErrorCodes.UNSUPPORTED_FORM ->
            // 写边车这条路: 与上面的 notXmp 同一句语义(**元素形式**) —— 绝不借用"解不开这张图"。
            "这个边车是元素形式（422 unsupportedForm）——电脑上无法安全合并，所以这次改动没有落盘。" +
                "重试不会有帮助。"
        ApiErrorCodes.BAD_REQUEST ->
            // 规格 §5.3 + 计划 Task 2 Step 4: 400 说明**手机发出的参数不合法**, 是客户端 bug ——
            // 绝不让用户去"改路径后重试"(那是白折腾, 而且他改不了服务端认为不合法的东西)。
            "手机发出的请求不合法（400 badRequest）——**这是 App 的问题，不是你的路径有问题**。" +
                "同一个请求重试还是会被拒：请把这条反馈给开发者。"
        ApiErrorCodes.INTERNAL ->
            "电脑上的服务出错了（500 internal）——这次失败在电脑那一侧。" +
                "先点「重试写入」；若一直失败，看电脑上 ImageFilter 的日志。"
        else -> httpGeneric(code)
    }

    /** 手机合成的码(规格 §5.3 第二张表 + 本表自己的 [UNCONFIRMED])。 */
    private fun forClientCode(code: String): String = when (code) {
        UNCONFIRMED ->
            "电脑**没有回报这一项的写入结果**——它可能只写了一半，所以这次改动**不算写成**。" +
                "你这台手机上的改动全部留着，点「重试写入」再确认一次（值没变时电脑不会白摸一次盘）。"
        ApiErrorCodes.SERVICE_OFF ->
            "没连上电脑（它可能睡了，或者 ImageFilter 没在电脑上运行）——这次改动没有落到电脑上。" +
                "在同一个 WiFi 下打开电脑上的 ImageFilter 服务，再点「重试写入」；你的改动还在这台手机上。"
        ApiErrorCodes.TIMEOUT ->
            "电脑没有回应（超时）——它可能正在忙，或者网络不稳。这次改动没有落到电脑上，" +
                "稍等一下再点「重试写入」。"
        ApiErrorCodes.NETWORK ->
            "和电脑的连接断了——这次改动没有落到电脑上。网络恢复后点「重试写入」；" +
                "你的改动还在这台手机上。"
        else -> clientGeneric(code)
    }

    /** 一次失败 → **用户下一步做什么**。判不出来的一律给 [WriteAction.RETRY]（重试写入是幂等的）。 */
    fun actionFor(f: WriteFailure): WriteAction = when (f.source) {
        FailureSource.XMP -> when (f.code) {
            XmpErrorCodes.BUSY -> WriteAction.RETRY
            XmpErrorCodes.DISK_FULL,
            XmpErrorCodes.READ_ONLY,
            XmpErrorCodes.WRITE_PROTECT,
            XmpErrorCodes.PERMISSION,
            XmpErrorCodes.PATH_TOO_LONG -> WriteAction.PC_SIDE
            XmpErrorCodes.NOT_XMP,
            XmpErrorCodes.UNSUPPORTED_FORM,
            XmpErrorCodes.TOO_LARGE,
            XmpErrorCodes.ENCODING -> WriteAction.DISMISS
            // unknown 与"认不出来"都是"说不出所以然" → 重试(唯一的无害动作)
            else -> WriteAction.RETRY
        }
        FailureSource.HTTP -> when (f.code) {
            ApiErrorCodes.NOT_FOUND -> WriteAction.RELOAD_FOLDER
            ApiErrorCodes.UNAUTHORIZED -> WriteAction.RECONNECT
            ApiErrorCodes.FORBIDDEN_PATH,
            ApiErrorCodes.DISK_FULL -> WriteAction.PC_SIDE
            ApiErrorCodes.BAD_REQUEST,
            ApiErrorCodes.TOO_LARGE,
            ApiErrorCodes.NOT_XMP,
            ApiErrorCodes.UNSUPPORTED_FORM -> WriteAction.DISMISS
            else -> WriteAction.RETRY
        }
        FailureSource.CLIENT -> WriteAction.RETRY
    }

    /** 动作用按钮上那几个字。**动作不同就字不同** —— 否则"不同动作"在界面上看不出来。 */
    fun actionLabel(action: WriteAction): String = when (action) {
        WriteAction.RETRY -> "重试写入"
        WriteAction.RELOAD_FOLDER -> "回网格刷新"
        WriteAction.RECONNECT -> "回目录页重新配对"
        WriteAction.PC_SIDE -> "知道了（要去电脑上改）"
        WriteAction.DISMISS -> "知道了"
    }

    /** 失败卡的标题: 三档来源各说各的(用户一眼就该知道是"边车的问题"还是"没连上")。 */
    fun headFor(f: WriteFailure): String = when (f.source) {
        FailureSource.XMP -> "电脑没能把这张的评分写进边车"
        FailureSource.HTTP -> "电脑拒绝了这次写入"
        FailureSource.CLIENT -> when (f.code) {
            UNCONFIRMED -> "这次写入没能确认"
            else -> "没能连上电脑"
        }
    }

    /**
     * 一批里有多项失败时, 先说清"**几项里错了几项**"——半成功态必须显式(纪律②)。
     * 只有一项失败时返回 `null`(那句话本身就是全部)。
     *
     * ⚠️ **"没能确认"的那些不许被算进"没写进电脑"**。逐项码 [UNCONFIRMED] 的意思是
     * **没有结论**(服务端没回报这一项的写入结果), 它自己的逐项文案就是这么说的; 批次句把它
     * 数进"没写进去"就是替服务端保证了一件它没说过的事 —— 与纪律③"未同步 ≠ 已同步"同一条。
     * 所以两句分开数、分开说: 确认失败几张、结果不明几张。
     *
     * 判据只看 `source`/`code` 这两件**逐字**的事实(**不猜**): 只有手机合成的
     * `CLIENT`+[UNCONFIRMED] 算"结果不明"; 传输层失败(没连上/超时/断线)照旧算"没写进电脑"
     * —— 它们的逐项文案本来就是这么说的, 两处必须一致。
     */
    fun batchHead(requested: Int, failures: List<WriteFailure>): String? {
        // 只有一项**有话说**时不重复: 那张卡的逐项文案就是全部(与原来 `failed <= 1` 同一条)。
        if (failures.size <= 1) return null
        val unconfirmed = failures.count { it.source == FailureSource.CLIENT && it.code == UNCONFIRMED }
        val failed = failures.size - unconfirmed
        val parts = buildList {
            if (failed > 0) add("$failed 张没写进电脑")
            if (unconfirmed > 0) add("$unconfirmed 张没能确认（结果不明）")
        }
        return "这一批 $requested 张里有 " + parts.joinToString("，") + "。"
    }

    /**
     * "改过但电脑还没确认"的角标文案(纪律③: 未同步 ≠ 已同步)。
     *
     * 跨张也要看得见 —— 只标当前这一张, 用户切走之后就再也想不起哪张还没写成了。
     */
    fun unsyncedLabel(count: Int): String = if (count == 1) "1 张未同步·重试" else "$count 张未同步·重试"

    /** 当前这张有未同步改动时的短提示(与上面的角标一起用)。 */
    const val UNSYNCED_THIS_PHOTO = "这台手机上的改动还没写进电脑"
}
