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
        "手机与电脑连在同一个 WiFi",
    )

    /**
     * 那份清单在界面上**常驻**卡片的标题。**只在这里写一次** ——
     * 配对向导里那张失败卡要指着它说"看下面这份", 两处各写一句就迟早对不上。
     */
    const val CHECKLIST_HEAD = "连不上？逐条确认"

    // ── 两个输入框的**常驻**格式提示(FIX 4)────────────────────────────────────
    //
    // 为什么不能只靠 placeholder: Material 的 placeholder 只在框**为空且聚焦**时才出现 ——
    // 而第一次配对的人恰恰是看着一个空的、没聚焦的框在猜"要填主机名还是 IP? 要带端口吗?"。
    // 所以形状提示做成 `supportingText`(**一直**看得见), placeholder 只作为例子重复一次。

    /** 地址的**例子**。它同时出现在 placeholder 与 [ADDRESS_HINT] 里, 所以只能有一份。 */
    const val URL_EXAMPLE = "http://192.168.3.10:8765"

    /** 地址框下面那一行, 一直看得见。 */
    const val ADDRESS_HINT =
        "要填成一整个地址：$URL_EXAMPLE —— 照电脑上「设置 → 图库服务」里显示的那一串抄（局域网 IP + 端口）"

    /** 配对码框下面那一行, 一直看得见。 */
    const val TOKEN_HINT =
        "64 位十六进制字符（0-9 / a-f 共 64 个，形如 3f9a…），从电脑「设置 → 图库服务」里复制，" +
            "或者直接扫那个二维码"

    // ── 扫码配对(M4′ Task 1)────────────────────────────────────────────────
    //
    // ⚠️ 这几句**加在既有这张表里**, 不另起一张: 它们说的是同一个屏幕上的同一件事
    // (「怎么把地址与配对码填进去」), 而另起一张表的唯一后果是"同一个词在两处各写一遍"。
    // 相机**不可用/被拒**那两句同样属于这张表 —— 它们的存在意义就是"扫码不行, 手动输入照旧"。

    /**
     * 扫到的东西**不是**配对二维码(普通文本 / 别的二维码 / 类型不对的 JSON)。
     *
     * 刻意**不**说"配对码不对": 那是两件完全不同的事, 后者会把人赶到电脑上去换一枚好好的 token。
     */
    const val SCAN_NOT_PAIRING_QR =
        "这不是 ImageFilter 的配对二维码（扫到的内容里读不出电脑地址与配对码）——" +
            "请扫电脑上「设置 → 图库服务」里显示的那一个码，或者用下面的**手动输入**。"

    /** 是配对载荷的形状, 但里面**没有地址** —— 多半是扫到了别的程序的码。 */
    const val SCAN_NO_ADDRESS =
        "这个二维码里**没有电脑地址**（可能是别的程序生成的码）——" +
            "用下面的**手动输入**填上电脑上显示的地址与配对码。"

    /** 有地址, 但**没有配对码**。 */
    const val SCAN_NO_TOKEN =
        "这个二维码里**没有配对码**（只有地址）——请核对是不是扫了半个码，或者用下面的**手动输入**补上配对码。"

    /** 有地址, 但手机读不出来(非 http/https 的 scheme 之类)。 */
    const val SCAN_BAD_ADDRESS =
        "这个二维码里的**地址读不出来**（只认 `http://主机:端口` 这种形式）——" +
            "用下面的**手动输入**，照电脑上显示的地址填。"

    /** 相机权限被拒时那句 —— 说清"配对没被挡住"。 */
    const val SCAN_CAMERA_DENIED =
        "没有相机权限，扫码用不了。**这不影响配对**：用下面的手动输入填地址与配对码，" +
            "和扫码填进去的是同一份东西。想用扫码就去系统设置里给 ImageFilter 打开相机权限。"

    /** 这台设备上根本拉不起扫码(没有相机 / 没有扫码组件)。 */
    const val SCAN_UNAVAILABLE =
        "这台设备上打不开扫码（没有相机或缺少扫码组件）。**这不影响配对**：用下面的手动输入一样可以配对。"

    /** 「填不了」的四种原因 → 四句**各不相同**的话(每句都以"用下面的手动输入"收尾)。 */
    fun forScanProblem(problem: PairingScanProblem): String = when (problem) {
        PairingScanProblem.NOT_JSON -> SCAN_NOT_PAIRING_QR
        PairingScanProblem.NO_URL -> SCAN_NO_ADDRESS
        PairingScanProblem.NO_TOKEN -> SCAN_NO_TOKEN
        PairingScanProblem.BAD_URL -> SCAN_BAD_ADDRESS
    }

    /** 扫到的地址不是私有网段时那句提醒(**不阻塞**配对, 只是说出来)。 */
    fun notLanAddress(host: String): String =
        "扫到的地址 $host 不是局域网私有地址（规格只做同一局域网：10.x / 172.16-31.x / 192.168.x）。" +
            "电脑端也绝不会把公网地址列进候选，所以这个码多半不是它的。已经填进去了，可以照常「测试连接」。"

    /** 扫码成功填进去之后那句反馈。 */
    const val SCAN_FILLED = "已从二维码填入地址与配对码。点「测试连接」验证。"

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
     * 一次失败 → 一句**可执行**的话(**不含** §1.3 那张清单)。
     *
     * [cause] 只有 [ApiErrorCodes.UNAUTHORIZED] 时才该非空; 三种 401 成因给**三句不同的话**,
     * 这正是 Task 2 那条消歧存在的意义 —— 三句都写成"配对码不对"的话, 消歧就白做了,
     * 而网络断了的用户会照着它去白换一遍配对码。
     *
     * ⚠️ **清单被拆出去了([forFailure] / [forFailureInWizard]), 理由见 [carriesChecklist]**。
     */
    fun forFailureHead(code: String, cause: UnauthorizedCause? = null): String = when (code) {
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
        ApiErrorCodes.SERVICE_OFF -> "连不上电脑（没有任何进程在应答）。"
        ApiErrorCodes.TIMEOUT -> "电脑没有回应（超时）。"
        ApiErrorCodes.NETWORK -> "连接中途断了（网络不稳）。"
        ApiErrorCodes.FORBIDDEN_PATH ->
            // ⚠️ 这一条在这条链路里是**读**(配对校验只发 GET /shares), 而只读模式拦的是写
            // (`routes.rs::refuse_writes_in_read_only` 判的是 `is_write_method`)—— 它造不出一个
            // 读请求的 403。所以这里不许把只读模式列成因(与 [ReadMessages] / [ViewerMessages]
            // 的 403 逐字同一条纪律), 要说清真正的原因并给出真正能做的动作。
            // (这一档在配对流程里走不到 —— 未配对先回 401, 而已配对时 /shares 不会 403;
            //  它仍然要写对: 表是**按码**公开的, 任何一条链路复用到这里都不该看到一句错话。)
            "电脑上的服务拒绝了这次请求（403 forbiddenPath）——这个位置不在电脑的共享根里" +
                "（只读模式拦的是写、不拦读，与这一条无关）：" +
                "去电脑上「设置 → 图库服务」把它的上级目录加进共享根"
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

    /**
     * 这一档码的文案里**是否嵌着** §1.3 那张四条清单。
     *
     * 判据只有这一处, 因为同一个问题在界面上有两种**正确**答法, 用错一种就会说话说两遍:
     *   · **页面上没有那份常驻清单**的调用方(浏览页/查看器/… 用的 [forFailure])——文案必须
     *     自带清单, 否则用户拿到的是一句"连不上电脑"而没有下一步;
     *   · **配对向导**([forFailureInWizard])——那张清单就在失败卡**正下方**常驻,
     *     再说一遍就是同一屏上把四件事列两次(FIX 6 要修的就是它)。
     */
    fun carriesChecklist(code: String): Boolean = code in CHECKLIST_CODES

    /**
     * 一次失败 → 一句**可执行**的话 + §1.3 那张清单(**整份**)。
     *
     * 给"页面上没有常驻清单"的调用方用。少列一条, 就少一条用户能自己排掉的原因。
     */
    fun forFailure(code: String, cause: UnauthorizedCause? = null): String {
        val head = forFailureHead(code, cause)
        if (!carriesChecklist(code)) return head
        return head + "逐条确认：" + checklist() + "。"
    }

    /**
     * **配对向导那张失败卡**的话(FIX 6): 那张清单就在它下面常驻, 所以这里**一个条目不重复**,
     * 只给出"去哪看"。
     *
     * 两件事都保住: 可执行的那句话(哪一档失败、下一步做什么)照旧在卡里, 而清单一处一份。
     */
    fun forFailureInWizard(code: String, cause: UnauthorizedCause? = null): String {
        val head = forFailureHead(code, cause)
        if (!carriesChecklist(code)) return head
        return head + "按下面「$CHECKLIST_HEAD」里的四条逐一排查。"
    }

    /** 圈子数字编号。写死四个, 与 [PREREQUISITES] 的四条一一对应(测试钉住条数)。 */
    private val NUMBERING = listOf("①", "②", "③", "④")

    /** 文案里嵌着那份清单的码: 传输层的三档(连不上 / 超时 / 中途断) —— 用户动作相同。 */
    private val CHECKLIST_CODES = setOf(
        ApiErrorCodes.SERVICE_OFF,
        ApiErrorCodes.TIMEOUT,
        ApiErrorCodes.NETWORK,
    )

    private fun checklist(): String =
        PREREQUISITES.mapIndexed { i, text -> "${NUMBERING[i]} $text" }.joinToString("；")
}
