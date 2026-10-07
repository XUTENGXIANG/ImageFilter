// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 目录浏览页的**中文文案表** —— 与 [PairingMessages] 同一种形状, 也放在 Kotlin 里
 * (理由逐条相同: 文案要能在 JVM 单测里被钉住, 而安卓资源读不到)。
 *
 * **为什么另起一张表而不是给 [PairingMessages] 加分支**: 同一个码在两个屏幕上的
 * 用户动作不同。最明显的是 `notFound` —— 配对页上它只可能是"端点没暴露", 而浏览页上
 * 它几乎总是"这个目录被改名/移走/删除了"。把两者合成一句话, 必然有一边是错的
 * (Task 3 独立评审对"文案按场景说人话"的同一要求)。
 *
 * **两张错误码表的纪律照旧**: 本表只认 §5.3 的 HTTP 码集([ApiErrorCodes])。
 * `XmpErrorCodes` 里那套码(`writeProtect` / `readOnly` / `encoding` / `pathTooLong` …)
 * 在浏览路径上**根本不会出现**, 所以它们落到 [GENERIC] 是正确行为, 不是漏配 ——
 * 见 `BrowseMessagesTest.only_codes_from_the_http_table_get_a_special_sentence`。
 */
object BrowseMessages {

    /**
     * 认不出来的码一律落到这里 —— **安全降级**: 服务端将来新加一档码, 老客户端要能
     * 说一句有动作的人话, 而不是崩、空白, 或瞎猜原因。
     */
    const val GENERIC = "这个目录没读出来（原因这台手机不认识）——点「重试」；" +
        "若一直失败，回配对页重新连一次，并把电脑上的 ImageFilter 更新到最新版。"

    /**
     * 浏览路径上**真会收到**的码(§5.3 的服务端码 + 手机合成码)。
     *
     * 这是"每条码必须落到用户下一步做什么"(§5.3)的可测形态: 这九个码每一个都必须拿到
     * 一句**不是兜底句**的话(测试逐条钉住)。`conflict` / `diskFull` / `tooLarge` /
     * `notXmp` / `unsupportedForm` 不在这里 —— 读目录的服务端路径**产不出**它们,
     * 编文案只会是"给永远走不到的分支写文案"。
     */
    val REACHABLE: Set<String> = setOf(
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.FORBIDDEN_PATH,
        ApiErrorCodes.NOT_FOUND,
        ApiErrorCodes.BAD_REQUEST,
        ApiErrorCodes.BUSY,
        ApiErrorCodes.INTERNAL,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF,
    )

    /** 未选文件夹时主区的引导句(规格 §8.1 逐字)。 */
    const val PICK_A_FOLDER = "选一个文件夹开始"

    /** 电脑上一个共享根都没配 —— 服务端的**有意安全默认**, 不是故障, 更要给出动作。 */
    const val NO_SHARES = "电脑上还没有共享根。这是电脑端有意的安全默认（没共享的目录谁都读不到）：" +
        "去电脑上「设置 → 图库服务」里添加要共享的目录，然后回到这里点「刷新」。"

    /** 200 但 `subfolders` 为空 —— 空目录**不是错误**(与 403/404 必须分开说)。 */
    const val EMPTY_DIR = "这个目录下没有子目录。照片可能就放在这一层（看照片的网格在 M1′ Task 5 接入）。"

    /**
     * 一次失败 → 一句**可执行**的话。
     *
     * [cause] 只有 [ApiErrorCodes.UNAUTHORIZED] 时才该非空 —— 那是 Task 2 消歧的结论;
     * 401 的三种成因在三句话上分的理由与配对页完全相同, 所以**复用**
     * [PairingMessages.forFailure], 不抄第二份(抄一份就迟早只改一处)。
     */
    fun forFailure(code: String, cause: UnauthorizedCause? = null): String = when (code) {
        ApiErrorCodes.FORBIDDEN_PATH ->
            "这个目录不在电脑的共享根里（403 forbiddenPath）。去电脑上「设置 → 图库服务」" +
                "把它的上级目录加进共享根，或者回去换一个目录。"
        ApiErrorCodes.NOT_FOUND ->
            "电脑上找不到这个目录了（404 notFound）——它可能被改名、移走或删除了。回上一层重新选一个。"
        ApiErrorCodes.BAD_REQUEST ->
            "电脑说这个路径不合法（400 badRequest）——回上一层重新选一个目录，" +
                "别拿同一个路径反复重试。"
        ApiErrorCodes.BUSY ->
            "电脑正忙（423 busy）——它可能正在处理另一个请求，稍等一下再展开。"
        // 传输层与鉴权层: 用户动作与配对页**一模一样**, 所以查同一张表(Task 2/3 的文案纪律)
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF,
        ApiErrorCodes.INTERNAL -> PairingMessages.forFailure(code, cause)
        else -> GENERIC
    }
}
