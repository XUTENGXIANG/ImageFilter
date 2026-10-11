// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.LocalCheck
import com.imagefilter.mobile.data.PairingField
import com.imagefilter.mobile.data.PairingInput
import com.imagefilter.mobile.data.PairingInputFormat
import com.imagefilter.mobile.data.PairingInputProblem
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.PairingScan
import com.imagefilter.mobile.data.PairingScanProblem
import com.imagefilter.mobile.data.scanPairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码配对(M4′ Task 1 手机端)的**载荷解析**测试 —— 纯 JVM, 没有相机也没有设备。
 *
 * ⚠️ **相机那一半在这里测不到**(本轮没有设备, 也不为本任务引 Compose UI 测试依赖):
 * "扫出来的内容真的会填进那两个框""拒绝相机权限之后手输照样能用"只能在真机上走
 * (报告的 UNVERIFIED 清单)。这里钉的是**拿到那串文本之后的每一条判定**, 而它恰恰是最容易
 * 写错的一半: 把"扫到了别的二维码"报成"配对码不对"、把没有地址的载荷当成合法的、
 * 或者干脆自己再写一套校验(那就是第二条配对路径了 —— 规格 §8.3 明令不许)。
 *
 * 两个刻意的成对断言(本项目栽过的那三个坑里, 有两个正是"只钉一支"):
 *   · **私网地址**填进去 + **公网地址**也填进去但**明确提示**(不是静默接受, 也不是拒收);
 *   · 四种"填不了"的原因给**四句不同的话**(一个"所有失败都回同一句"的实现会被这一条钉死)。
 */
class PairingScanTest {

    private val hex64 = "0123456789abcdef".repeat(4)
    private val privateUrl = "http://192.168.1.5:8765"

    /** 桌面端 `pairing_qr_svg` 的载荷形状: `{"url":"…","token":"…"}`(缺哪一半就不写哪个键)。 */
    private fun payload(url: String?, token: String?): String {
        val parts = buildList {
            if (url != null) add(""""url":"$url"""")
            if (token != null) add(""""token":"$token"""")
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun filled(raw: String): PairingScan.Filled {
        val result = scanPairingPayload(raw)
        assertTrue("这一条本该能填: $result", result is PairingScan.Filled)
        return result as PairingScan.Filled
    }

    private fun rejected(raw: String): PairingScan.Rejected {
        val result = scanPairingPayload(raw)
        assertTrue("这一条本该被拒: $result", result is PairingScan.Rejected)
        return result as PairingScan.Rejected
    }

    // ── 能填的那一支 ─────────────────────────────────────────────────────────

    @Test
    fun a_private_lan_payload_fills_both_fields_with_no_warning() {
        val f = filled(payload(privateUrl, hex64))

        assertEquals(privateUrl, f.url)
        assertEquals(hex64, f.token)
        assertNull("桌面端只会把私有网段地址放进二维码: 这一档没有可提示的", f.notice)
    }

    @Test
    fun the_filled_values_are_validated_by_the_same_code_the_keyboard_path_uses() {
        // 规格 §8.3 / 计划: 扫码**不是**第二条配对路径 —— 它只是把那两个框填上,
        // 校验仍然走手输用的同一个 [PairingInputFormat.check]。
        val f = filled(payload(privateUrl, hex64))

        val local = PairingInputFormat.check(PairingInput(url = f.url, token = f.token))
        assertTrue("填进去的那一份必须能过同一个本地校验, 实际=$local", local is LocalCheck.Ok)
        assertEquals(privateUrl, (local as LocalCheck.Ok).url)
        assertEquals("归一化一个字都不该改动这个值", hex64, local.token)
    }

    @Test
    fun the_scanner_does_not_hold_a_second_validation_path_for_the_token() {
        // 配对码形状不对时, **扫描器不自己下结论**(那会是第二条路径) —— 它照样填,
        // 由手输那条链路的 [PairingInputFormat] 报「配对码必须是 64 位十六进制」。
        val f = filled(payload(privateUrl, "abc"))

        assertEquals("abc", f.token)
        assertEquals(
            LocalCheck.Bad(PairingField.TOKEN, PairingInputProblem.BAD_TOKEN_SHAPE),
            PairingInputFormat.check(PairingInput(url = f.url, token = f.token)),
        )
    }

    @Test
    fun a_bearer_of_whitespace_around_the_token_is_trimmed_like_a_paste_would_be() {
        // 二维码内容可能被别的程序加上换行 —— 修剪不可能把一枚错的 token 变成对的
        // (十六进制里本来就没有空白字符)。
        val f = filled(payload(privateUrl, "  $hex64\\n"))

        assertEquals(hex64, f.token)
    }

    @Test
    fun a_public_address_is_still_filled_but_says_out_loud_it_is_not_a_lan_address() {
        // 对称的另一支(与上面那条「私网无提示」成对): 公网地址**不静默接受**, 也不拒收 ——
        // 规格 §4.2 明令桌面端绝不把公网地址列进候选, 所以这一档只可能来自别的来源;
        // 说出来, 然后让用户自己决定。
        val f = filled(payload("http://8.8.8.8:8765", hex64))

        assertEquals("http://8.8.8.8:8765", f.url)
        assertEquals(hex64, f.token)
        assertTrue("必须提示这不是局域网地址: ${f.notice}", f.notice!!.contains("局域网"))
    }

    @Test
    fun a_missing_scheme_is_filled_the_way_the_keyboard_path_would_normalize_it() {
        // 桌面端设置页显示的是 `http://<ip>:<端口>`; 但补齐 scheme 用的是**同一个**
        // normalizeUrl(补缺省值, 端口与路径一个字都不猜)。
        val f = filled(payload("192.168.1.5:8765", hex64))

        assertEquals(privateUrl, f.url)
        assertNull("10/172.16-31/192.168 段都是私有网段, 这一档没有可提示的", f.notice)
    }

    // ── 填不了的那一支 ───────────────────────────────────────────────────────

    @Test
    fun junk_text_is_rejected_as_not_a_pairing_code() {
        // 扫到别的二维码(一个网址、一段随便的文本)时, 必须说「这不是配对二维码」——
        // 说成「配对码不对」会让用户去电脑上重新复制一枚根本没问题的 token。
        for (junk in listOf("https://example.com/", "随便一段文本", "1234567890", "")) {
            val r = rejected(junk)
            assertEquals("输入=<$junk>", PairingScanProblem.NOT_JSON, r.problem)
            assertTrue("输入=<$junk> 文案=${r.message}", r.message.contains("配对二维码"))
        }
    }

    @Test
    fun json_that_is_not_an_object_or_has_wrong_types_is_rejected_as_junk_too() {
        // 「是合法 JSON」不等于「是配对载荷」: 数组、数字、裸字符串、类型不对的字段都必须落到
        // 同一档, 而不是崩掉(kotlinx.serialization 在这些输入上会抛)。
        val junkInputs = listOf(
            "[1,2,3]",
            "42",
            "\"http://192.168.1.5:8765\"",
            """{"url":123,"token":"$hex64"}""",
            """{"url":"$privateUrl","token":[1,2]}""",
        )
        for (junk in junkInputs) {
            assertEquals("输入=<$junk>", PairingScanProblem.NOT_JSON, rejected(junk).problem)
        }
    }

    @Test
    fun a_payload_without_an_address_is_rejected_and_says_the_address_is_missing() {
        val r = rejected(payload(null, hex64))

        assertEquals(PairingScanProblem.NO_URL, r.problem)
        assertTrue("必须点明缺的是地址: ${r.message}", r.message.contains("地址"))
        assertFalse("不许把「缺地址」说成「配对码不对」: ${r.message}", r.message.contains("配对码不"))
    }

    @Test
    fun a_payload_with_an_empty_address_is_the_same_rejection_as_a_missing_one() {
        // 空串与「键不存在」是同一件事(都填不出地址)。
        assertEquals(PairingScanProblem.NO_URL, rejected(payload("", hex64)).problem)
        assertEquals(PairingScanProblem.NO_URL, rejected(payload("   ", hex64)).problem)
    }

    @Test
    fun a_payload_without_a_token_is_rejected_and_says_the_token_is_missing() {
        val r = rejected(payload(privateUrl, null))

        assertEquals(PairingScanProblem.NO_TOKEN, r.problem)
        assertTrue("必须点明缺的是配对码: ${r.message}", r.message.contains("配对码"))
    }

    @Test
    fun an_address_that_cannot_be_read_is_rejected_as_a_bad_address() {
        // 有地址但读不出来(非 http/https 的 scheme): 与「缺地址」必须分开说 ——
        // 一个是「这个码里没有」, 一个是「这个码里的东西手机连不了」。
        val r = rejected(payload("ftp://192.168.1.5:8765", hex64))

        assertEquals(PairingScanProblem.BAD_URL, r.problem)
        assertTrue("必须说清是地址读不出来: ${r.message}", r.message.contains("地址"))
    }

    @Test
    fun the_four_rejections_are_four_different_sentences() {
        // ⚠️ 这一条专治本项目栽过的那个坑: 「一个循环对着同一句兜底文案断言」。
        // 四种原因必须给出**四句不同**的话。
        val messages = listOf(
            rejected("https://example.com/").message,
            rejected(payload(null, hex64)).message,
            rejected(payload(privateUrl, null)).message,
            rejected(payload("ftp://192.168.1.5:8765", hex64)).message,
        )
        assertEquals("四种原因必须是四句不同的话: $messages", 4, messages.toSet().size)
        for (m in messages) assertTrue("每句话都必须是可执行的人话: <$m>", m.isNotBlank())
    }

    @Test
    fun every_rejection_carries_the_manual_entry_fallback() {
        // 扫码是**增强**, 不是唯一路径(计划 Step 4 第 3 条): 填不了的时候必须把用户引回手输。
        val all = listOf(
            rejected("https://example.com/"),
            rejected(payload(null, hex64)),
            rejected(payload(privateUrl, null)),
            rejected(payload("ftp://192.168.1.5:8765", hex64)),
        )
        for (r in all) {
            assertTrue("problem=${r.problem} 文案=${r.message}", r.message.contains("手动输入"))
            // 文案来自**既有那张表**([PairingMessages]), 不是扫描器自己藏的第二份
            assertEquals(PairingMessages.forScanProblem(r.problem), r.message)
        }
    }

    @Test
    fun extra_keys_in_the_payload_do_not_break_the_parse() {
        // 桌面端以后给这枚二维码加字段时, 已经装在手机上的老客户端不该因此扫不了。
        val f = filled("""{"url":"$privateUrl","token":"$hex64","future":"x"}""")

        assertEquals(privateUrl, f.url)
        assertEquals(hex64, f.token)
    }
}
