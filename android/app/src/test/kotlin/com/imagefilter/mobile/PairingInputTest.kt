// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.LocalCheck
import com.imagefilter.mobile.data.PairingField
import com.imagefilter.mobile.data.PairingInput
import com.imagefilter.mobile.data.PairingInputFormat
import com.imagefilter.mobile.data.PairingInputProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配对输入框的**本地**归一化与校验(纯函数, 不需要设备也不需要网络)。
 *
 * 每一条断言都对着一条真会发生的用户操作 —— 从设置页复制过来的那一串、手抄漏一位、
 * 从聊天窗口转发时带上的换行。这些是"手机连不上"里最常见的一类, 而它们的正确处理
 * 只有靠本地校验能做到(丢给服务端就只剩一句 401)。
 */
class PairingInputTest {

    /** 64 位十六进制 —— 与 `server/config.rs::token_is_valid` 同形。 */
    private val token = "a1b2c3d4".repeat(8)

    private fun check(url: String = "http://192.168.1.5:8765", tkn: String = token) =
        PairingInputFormat.check(PairingInput(url = url, token = tkn))

    // ── 地址 ────────────────────────────────────────────────────────────────

    @Test
    fun a_missing_scheme_gets_http_and_a_trailing_slash_is_dropped() {
        // 桌面端设置页显示的是 http://ip:端口, 而用户抄下来的常常是没有 scheme 的那一串
        assertEquals("http://192.168.1.5:8765", PairingInputFormat.normalizeUrl("192.168.1.5:8765"))
        assertEquals("http://192.168.1.5:8765", PairingInputFormat.normalizeUrl("  http://192.168.1.5:8765/  "))
        // 只补缺省 scheme, **端口一个字都不猜** —— 不写端口就是 http 的默认端口
        assertEquals("http://nas", PairingInputFormat.normalizeUrl("nas"))
        // https 形式的地址本身不被本地拒绝(URL 形状不归这一层管): §4.3 说服务端不做 TLS,
        // 而 LAN-only 之后配对填的就是 http://局域网地址:端口 —— 这条钉的只是"不误拒"。
        assertEquals("https://gallery.example.com", PairingInputFormat.normalizeUrl("https://gallery.example.com/"))
    }

    @Test
    fun an_address_that_is_not_http_is_rejected_locally() {
        assertNull(PairingInputFormat.normalizeUrl(""))
        assertNull(PairingInputFormat.normalizeUrl("   "))
        assertNull(PairingInputFormat.normalizeUrl("http://"))
        assertNull("手机端只会说 HTTP", PairingInputFormat.normalizeUrl("ftp://host/x"))
        assertNull("端口越界", PairingInputFormat.normalizeUrl("http://host:99999"))
    }

    // ── 配对码 ──────────────────────────────────────────────────────────────

    @Test
    fun the_token_shape_is_exactly_the_servers_own_rule() {
        assertEquals(token, PairingInputFormat.normalizeToken(token))
        assertNull("少一位", PairingInputFormat.normalizeToken("a".repeat(63)))
        assertNull("多一位", PairingInputFormat.normalizeToken("a".repeat(65)))
        assertNull("非十六进制", PairingInputFormat.normalizeToken("z".repeat(64)))
        // 服务端 token_is_valid 接受大写(is_ascii_hexdigit), 但比较是**逐字节**的
        // (auth::eq_constant_time) —— 所以客户端必须原样保留大小写, 不许自作聪明地折叠。
        assertEquals("AB".repeat(32), PairingInputFormat.normalizeToken("AB".repeat(32)))
    }

    @Test
    fun a_pasted_token_with_line_breaks_is_cleaned() {
        // 从聊天窗口转发、或从被折行的终端里复制, 都会带出换行。
        // 这一步不只是"顺手": 带换行的值一旦进了 Authorization 头, OkHttp 会当场抛
        // IllegalArgumentException(头值里不许有控制字符) —— 那是个用户完全无法理解的崩溃。
        val pasted = token.chunked(32).joinToString("\r\n")
        assertEquals(token, PairingInputFormat.normalizeToken(pasted))
    }

    @Test
    fun a_token_made_of_non_ascii_digits_is_not_accepted() {
        // Char.isDigit() 对 '٣'(阿拉伯-印度数字)也返回 true —— 用 isDigit() 写形状检查
        // 会让一枚服务端必然拒绝的 token 在这里通过。这条钉住用的是 ASCII 区间。
        assertNull(PairingInputFormat.normalizeToken("٣".repeat(64)))
    }

    // ── 三个框的联合校验 ────────────────────────────────────────────────────

    @Test
    fun check_says_which_field_is_bad_and_why() {
        assertEquals(
            LocalCheck.Bad(PairingField.URL, PairingInputProblem.EMPTY_URL),
            check(url = "   "),
        )
        assertEquals(
            LocalCheck.Bad(PairingField.URL, PairingInputProblem.BAD_URL),
            check(url = "ftp://host"),
        )
        assertEquals(
            LocalCheck.Bad(PairingField.TOKEN, PairingInputProblem.EMPTY_TOKEN),
            check(tkn = ""),
        )
        assertEquals(
            LocalCheck.Bad(PairingField.TOKEN, PairingInputProblem.BAD_TOKEN_SHAPE),
            check(tkn = "abc"),
        )
    }

    @Test
    fun check_returns_the_normalized_values_and_a_trimmed_note() {
        // 界面与存储都该用**这一份**, 不许再拿输入框里的原串去发请求或落盘
        assertEquals(
            LocalCheck.Ok("http://192.168.1.5:8765", token, "家里的台式机"),
            PairingInputFormat.check(
                PairingInput(url = " 192.168.1.5:8765/ ", token = " $token ", note = "  家里的台式机  "),
            ),
        )
    }
}
