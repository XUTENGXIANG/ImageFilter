// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 扫码配对(M4′ Task 1 手机端)的**载荷解析**。纯逻辑, 全部可 JVM 单测。
 *
 * 桌面端 `server::pairing_qr_svg` 把 `{"url":"http://<私有局域网地址>:<端口>","token":"…"}` 编成
 * 二维码; 手机扫到的那串文本就是它。这里负责把文本变成**两个输入框的值**。
 *
 * ⚠️ **扫码不是第二条配对路径**(计划 Step 3 逐字要求): 它只填那两个框, 校验仍然由
 * [PairingInputFormat] → [PairingValidator] 那条**手输也在走**的链路做。所以:
 *   · [PairingScan.Filled.token] **刻意不在扫描器里校验形状** —— 形状不对就让手输那条链路的
 *     [PairingInputFormat.check] 去报"配对码必须是 64 位十六进制"(用户看到的两种入口说同一句话,
 *     而不是"扫码时说一套、手输时又说一套");
 *   · 解析失败要给**明确且各不相同**的提示 —— 把"扫到了别的二维码"报成"配对码不对"是这里最
 *     容易犯的错: 用户会跑去电脑上换一枚根本没问题的 token。
 */
sealed interface PairingScan {
    /** 能填: 地址与配对码都拿到了。 */
    data class Filled(
        /** 已按 [PairingInputFormat.normalizeUrl] 归一化的地址 —— 它就是该进地址框的那一份。 */
        val url: String,
        /** 配对码(**只去了两端空白**); 形状由上手输那条链路的 [PairingInputFormat] 判断。 */
        val token: String,
        /**
         * 非 null = 要提醒用户的一句话(**不阻塞**: 照样填进去)。
         *
         * 目前只有一档: 地址不是私有网段。规格 §4.2 明令桌面端绝不把公网地址列进候选,
         * 所以这一档只可能来自别的来源 —— 说出来, 让用户自己决定。
         */
        val notice: String? = null,
    ) : PairingScan

    /** 填不了: [message] 是一句可执行的话, 而且**两个框一个都不动**(用户接着手输)。
     *
     * 文案来自**既有那张表**([PairingMessages.forScanProblem]) —— 扫描器不自己藏第二份文案。 */
    data class Rejected(val problem: PairingScanProblem, val message: String) : PairingScan
}

/**
 * 「填不了」的四种原因。**必须分开**, 因为它们的下一步动作不同:
 *   · [NOT_JSON] —— 扫到的不是配对二维码: 换一个码来扫, 或者手输;
 *   · [NO_URL] —— 是这个 App 的载荷形状, 但没有地址: 这个码是坏的/是别的东西, 手输;
 *   · [NO_TOKEN] —— 有地址没有配对码: 手输补上;
 *   · [BAD_URL] —— 地址在, 但手机读不出来(例如 `ftp://`): 手输。
 *
 * 做成枚举(而不是"返回一句字符串")就是为了让"四句不同的话"成为一条**可断言**的契约
 * —— 见 `PairingScanTest.the_four_rejections_are_four_different_sentences`。
 */
enum class PairingScanProblem { NOT_JSON, NO_URL, NO_TOKEN, BAD_URL }

/**
 * 桌面端那段 JSON 的**宽松**投影: 两个字段都可空。
 *
 * 为什么不用 `data class PairingPayload(val url: String, val token: String)`(计划里的写法):
 * 缺字段时 kotlinx.serialization 抛 `MissingFieldException`, 于是"没有地址"与"没有配对码"
 * 会落成**同一句话** —— 而这两件事的用户动作不同(前者多半是扫错了码, 后者是补一个码),
 * 而且规格要求每条提示都落到"用户下一步做什么"。所以先宽松地接下来, 再逐条判。
 */
@Serializable
private data class PairingPayloadDto(val url: String? = null, val token: String? = null)

/**
 * 扫到的一串文本 → [PairingScan]。**绝不抛**(扫到任何东西都不该让 App 崩)。
 */
fun scanPairingPayload(raw: String): PairingScan {
    val text = raw.trim()
    // 先按"看起来像不像一个 JSON 对象"粗筛: 一段普通文本(某个网址、随便什么)在这里就分出来了,
    // 而它恰恰是最常见的一档(用户的相册里/别人的二维码一大堆)。
    if (text.isEmpty() || !text.startsWith("{")) return reject(PairingScanProblem.NOT_JSON)

    val dto = try {
        Json.decodeFromString<PairingPayloadDto>(text)
    } catch (_: Exception) {
        // 是 `{` 开头但读不出来(类型不对 / 不是合法 JSON): 与"扫到普通文本"同一档 ——
        // 对用户来说两件事都一样: 这不是配对二维码。
        return reject(PairingScanProblem.NOT_JSON)
    }

    val rawUrl = dto.url?.trim().orEmpty()
    if (rawUrl.isEmpty()) return reject(PairingScanProblem.NO_URL)

    // 归一化用的是**同一个** normalizeUrl(补缺省 scheme / 去末尾斜杠, 端口与路径一个字都不猜);
    // 读不出来(例如 ftp://)就是这一档 —— 与"没有地址"分开说。
    val url = PairingInputFormat.normalizeUrl(rawUrl) ?: return reject(PairingScanProblem.BAD_URL)

    val token = dto.token?.trim().orEmpty()
    if (token.isEmpty()) return reject(PairingScanProblem.NO_TOKEN)

    return PairingScan.Filled(url = url, token = token, notice = lanNotice(url))
}

private fun reject(problem: PairingScanProblem): PairingScan.Rejected =
    PairingScan.Rejected(problem = problem, message = PairingMessages.forScanProblem(problem))

/**
 * 地址不是私有网段时的一句提醒(是的话返回 `null`)。
 *
 * 私有网段逐字照规格 §4.2: `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`, 另加环回
 * `127.0.0.0/8`(它同样是"不是公网地址")。主机名(不是点分四段)一律提醒 —— 桌面端只会发
 * 私有网段的字面 IP。
 */
internal fun lanNotice(url: String): String? {
    val host = url.toHttpUrlOrNull()?.host ?: return null
    if (isPrivateLanHost(host)) return null
    return PairingMessages.notLanAddress(host)
}

/** 点分四段的私有/环回地址判定。非点分四段(主机名)一律 `false`。 */
internal fun isPrivateLanHost(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val nums = parts.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    return when (nums[0]) {
        10, 127 -> true
        192 -> nums[1] == 168
        172 -> nums[1] in 16..31
        else -> false
    }
}
