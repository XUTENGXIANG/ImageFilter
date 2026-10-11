// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 移动分类(M3′ Task 4)的中文文案表 —— 与 [PairingMessages] / [BrowseMessages] / [ReadMessages] /
 * [WriteMessages] 同一种形状, 也放在 Kotlin 里(理由逐条相同: 文案要能在 JVM 单测里被钉住,
 * 而安卓资源在单测里读不到)。
 *
 * ⚠️ **本表只写两件既有表里没有的东西**, 别的一个字都不重复:
 *
 *   ① **`MoveStatus` 四值闭集的说法**。规格 §5.3 的实测修正点名过这件事: `/move` 的逐项结果
 *      里**既没有** HTTP 的 `code`, **也没有** XMP 码集 —— 它只有 `status`(mover.rs 的四值闭集)
 *      加一句人类可读的 `detail`。所以移动这条链路需要的是**第三张小表**, 而不是把
 *      [ApiErrorCodes] 或 [XmpErrorCodes] 硬套上去(套上去就会出现"用写边车的码解释一次移动"这种
 *      谁都不认识的分支)。
 *   ② **「手机端不能新建文件夹」这条如实说明的限制**(M0′ 的端点表里**没有** Mkdir, 而
 *      `ensure_within` 只能校验已存在的路径 —— 规格 §5.2)。UI 必须把它说出来, 不许假装支持。
 *
 * **批次级的失败一个字都不另写**: 移动是**写**操作, 403/400/404… 的成因(不在共享根 /
 * 只读模式 / 目标不存在)与用户动作跟写边车那条链路完全一样, 所以逐字复用 [WriteMessages];
 * 传输层与鉴权层复用 [PairingMessages](与 [BrowseMessages] / [ReadMessages] 的处理逐字相同)。
 * 抄第三份映射的代价是固定的: 迟早只改一处。
 */
object MoveMessages {

    // ── ① MoveStatus(四值闭集 + 一档"这台手机不认识") ──────────────────────

    /**
     * 结果面板里那一行的标题。
     *
     * `null` = 服务端发了一个**这个构建不认识**的 status —— 说"结果未知"而不是猜成某一档
     * (mover.rs 的注释记着那条教训: 把 `HalfSuccess` 改成 `Half` 在 Rust 侧编译得过,
     * 手机那边会安静地落进未知状态)。
     */
    fun headFor(status: MoveStatus?): String = when (status) {
        MoveStatus.MOVED -> "已移动"
        MoveStatus.HALF_SUCCESS -> "照片移走了，边车没跟上"
        MoveStatus.CONFLICT -> "目标位置已有同名文件（未覆盖）"
        MoveStatus.FAILED -> "没有移动"
        null -> "结果未知"
    }

    /**
     * 结果面板里那一行的正文。**绝不解析服务端的 `detail`** —— 那是本地化文案, 不是数据通道
     * (规格 §5.2 逐字要求); 判据只有 `status`, 而重试的判据只有 `pendingSidecars`。
     */
    fun forStatus(status: MoveStatus?, pendingSidecars: Int = 0, rawStatus: String = ""): String =
        when (status) {
            MoveStatus.MOVED -> "照片与它的 .xmp 边车都到了目标文件夹。" + SIDECAR_RULE

            MoveStatus.HALF_SUCCESS -> "照片**已经搬走**了, 但还有 $pendingSidecars 个边车留在原来的位置 —— " +
                "电脑上的评分/色标现在与这张照片的新位置**脱钩**了。" +
                "点这一行的「重试边车」把留在原地的边车再搬一次($SIDECAR_RULE)"

            MoveStatus.CONFLICT -> "目标位置**已经有同名文件, 未覆盖** —— 电脑不会覆盖任何东西, " +
                "这一张原样留在原地。在电脑上把那个文件改名、或者（在这里）换一个目标文件夹再试。"

            MoveStatus.FAILED -> "这一张**没有移动**, 原样留在原地（电脑上它还在原来的文件夹里）。"

            // 认不出来的 status: 说清"不知道", 并给出唯一能查明真相的动作(刷新)
            null -> "电脑回报的这一档结果（${rawStatus.ifBlank { "空" }}）这台手机不认识 —— " +
                "请回网格点「刷新」，确认这张照片现在到底在哪。"
        }

    /** 重试把边车搬过去之后, 那一行上多出来的一句话。 */
    const val RETRY_CAUGHT_UP = "边车这次跟上了：照片与它的 .xmp 都在目标文件夹里了。"

    /** 一次重试**没有结论**时那句话(请求没打通 / 被拒) —— 不许说成成功, 也不许说成"边车还在原地"。 */
    fun retryFailed(code: String?, cause: UnauthorizedCause? = null): String =
        "这一次重试**没能确认结果**（" + batchFailure(code ?: ApiErrorCodes.NETWORK, cause) + "）"

    /** 没有可重试的东西时的短句(面板顶部那句结论用)。 */
    const val RETRY_NOTHING_PENDING = "现在没有需要重试的东西。"

    /** 用户点名要重试、但那一项**根本没有重试通道**时的说法。 */
    fun retryNoChannel(count: Int): String =
        "这 $count 项没有可重试的东西：只有「照片移走了、边车没跟上」的那些才需要重试，" +
            "而它们的重试通道是电脑给的 `pendingSidecars`（手机不推导边车的文件名）。"

    // ── ② 如实说明的限制(M0′ 没有 Mkdir 端点) ───────────────────────────────

    /**
     * 「只能移动到已有文件夹」这条限制。**必须**显示在选择器上 ——
     * 服务端的端点表里没有 Mkdir, 而 `ensure_within` 只能校验**已存在**的路径(规格 §5.2),
     * 所以"新建"这件事在手机上根本做不到。假装支持只会让用户找不到那个按钮。
     */
    const val NO_MKDIR =
        "只能移动到电脑上**已有**的文件夹：这个版本手机端**不能新建**文件夹（电脑上的服务没有新建端点）。" +
            "要新建请到电脑上操作（在资源管理器里建好那个文件夹），然后回到选择器里点「刷新共享根」。"

    /** 手机端也不删除文件(规格 §1.2 的非目标) —— 用户会问, 所以先说清。 */
    const val NO_DELETE =
        "这个版本手机端也不删除文件：移动只是把照片换一个文件夹, 一个字节都不会被删掉。"

    /** 边车由谁搬、谁不碰 —— 每一处提到边车的地方都带上它。 */
    const val SIDECAR_RULE =
        "照片与它的 .xmp 边车由电脑一起搬；手机不读也不写边车, 更不推导边车的文件名。"

    /** 结果面板的标题。 */
    fun resultHead(moved: Int, halfSuccess: Int, conflict: Int, failed: Int): String = when {
        halfSuccess > 0 -> "移动完成，但有 $halfSuccess 张的边车没跟上"
        conflict > 0 && moved == 0 -> "没有移动：目标位置已有同名文件"
        failed > 0 && moved == 0 && conflict == 0 -> "没有移动"
        else -> "移动完成"
    }

    // ── ③ 批次级失败: 逐字复用既有表 ────────────────────────────────────────

    /**
     * 一次**批次级**失败(整批 4xx/5xx 或传输故障) → 一句可执行的话。
     *
     * 分派规则只有两条, 而且都建立在"用户下一步做什么"上:
     *   · 鉴权与传输故障(`401` / `timeout` / `network` / `serviceOff`) —— 与本项目其它三页
     *     **完全一样**, 所以查 [PairingMessages](消歧结论也一并传进去);
     *   · 其余(含 `403 forbiddenPath`) —— 移动是**写**操作, 所以查 [WriteMessages]:
     *     只有它那一句会说清 403 的两个成因里还有"电脑上正开着只读模式"这一档
     *     (只读模式拦的是写, 而 `/move` 是 POST)。它同时覆盖所有认不出来的码(有兜底句)。
     *
     * 逐字相等在这些调用点上是**可以断言**的事实(见 `MoveTest`), 所以"又抄了一份映射"
     * 这种漂移不会靠人眼发现。
     */
    fun forBatchFailure(code: String, cause: UnauthorizedCause? = null): String = when (code) {
        ApiErrorCodes.UNAUTHORIZED,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
        ApiErrorCodes.SERVICE_OFF,
        ApiErrorCodes.INTERNAL -> PairingMessages.forFailure(code, cause)

        else -> WriteMessages.forFailure(
            WriteFailure(path = "", code = code, source = FailureSource.HTTP),
        )
    }

    /** [retryFailed] 用的那个短句: 只要"为什么没确认"的那半句, 不要整段建议。 */
    private fun batchFailure(code: String, cause: UnauthorizedCause?): String = when (code) {
        ApiErrorCodes.SERVICE_OFF -> "没连上电脑"
        ApiErrorCodes.TIMEOUT -> "电脑没有回应（超时）"
        ApiErrorCodes.NETWORK -> "连接断了"
        ApiErrorCodes.UNAUTHORIZED -> when (cause) {
            UnauthorizedCause.ENDPOINT_NOT_EXPOSED -> "电脑上这个版本不提供该端点"
            UnauthorizedCause.UNDETERMINED -> "配对码是否还有效没查清"
            else -> "配对码已失效"
        }
        ApiErrorCodes.FORBIDDEN_PATH -> "电脑不允许写这个位置"
        ApiErrorCodes.BAD_REQUEST -> "手机发出的请求不合法"
        else -> "原因这台手机不认识：$code"
    }
}
