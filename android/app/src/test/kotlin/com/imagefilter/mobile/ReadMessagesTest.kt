// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.DecisionReadFailure
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.ReadAction
import com.imagefilter.mobile.data.ReadMessages
import com.imagefilter.mobile.data.ReadSource
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.ViewerMessages
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.XmpErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读这个文件夹的星级/色标这条链路的**文案表**测试。
 *
 * 本任务点名的一条纪律是"两张码表的分派不许靠猜", 而读取这条链路上**只有两张表**:
 * §5.3 的 HTTP 码集与手机合成码(外加本表自己的 `readIncomplete`)。XMP 码集在这条路上
 * **不可能**出现(服务端把单项读不出来降级成 `rating: null`), 所以:
 *   · 分派的依据是显式的 [ReadSource], 同一个码字符串在两张表下给**两句不同的话**;
 *   · XMP 专有的码(`readOnly` / `writeProtect` / `encoding` / `pathTooLong`)落在这条链路上
 *     只能落**兜底句**, 而且兜底句里**不许出现边车那套语义**("边车 / 合并 / 元素形式")。
 *
 * ⚠️ "已知码不许落到兜底句"这条断言**跟每一张表的兜底句都比**(不只比本表那一个):
 * 只跟一个比的话, "查错表"那种实现会落到**另一张**兜底句上, 断言就被骗过去了 ——
 * task-2 的报告里记着这一次真实的假绿。
 */
class ReadMessagesTest {

    private fun http(code: String, cause: UnauthorizedCause? = null) =
        DecisionReadFailure(code = code, source = ReadSource.HTTP, cause = cause)

    private fun client(code: String) = DecisionReadFailure(code = code, source = ReadSource.CLIENT)

    /** 每一张表的兜底句 —— "已知码不许落到它们任何一个上"。 */
    private fun everyGenericSentence(code: String): List<Pair<String, String>> = listOf(
        "ReadMessages.httpGeneric" to ReadMessages.httpGeneric(code),
        "ReadMessages.clientGeneric" to ReadMessages.clientGeneric(code),
        "WriteMessages.xmpGeneric" to WriteMessages.xmpGeneric(code),
        "WriteMessages.httpGeneric" to WriteMessages.httpGeneric(code),
        "WriteMessages.clientGeneric" to WriteMessages.clientGeneric(code),
        "PairingMessages.GENERIC" to PairingMessages.GENERIC,
        "BrowseMessages.GENERIC" to BrowseMessages.GENERIC,
        "ViewerMessages.GENERIC" to ViewerMessages.GENERIC,
    )

    // ── ① 每一个真会收到的码都有自己的一句话 ─────────────────────────────────

    @Test
    fun every_reachable_code_gets_its_own_sentence_never_any_fallback() {
        for (code in ReadMessages.REACHABLE) {
            val f = if (code in ApiErrorCodes.SERVER_CODES) http(code) else client(code)
            val msg = ReadMessages.forFailure(f)
            assertTrue("$code 的话是空的", msg.isNotBlank())
            for ((name, generic) in everyGenericSentence(code)) {
                assertNotEquals("$code 落到了 $name 兜底句上", generic, msg)
            }
        }
    }

    @Test
    fun every_failure_says_the_counts_are_not_reliable_and_the_local_changes_are_safe() {
        // 这一条是本任务给用户的两句**后果**: 筛选栏的张数这次不可信(**不是 0**), 而你这台手机上
        // 打的分一个都没丢。少了后半句, 用户会以为自己的离线评分被这次失败弄没了。
        for (code in ReadMessages.REACHABLE) {
            val f = if (code in ApiErrorCodes.SERVER_CODES) http(code) else client(code)
            val msg = ReadMessages.forFailure(f)
            assertTrue("$code 的话里没说清张数不可信: $msg", msg.contains(ReadMessages.COUNTS_UNKNOWN_TAIL))
            assertTrue("$code 的话里必须说清本地改动没丢", msg.contains("一个都没丢"))
        }
        // 兜底句也必须有这个后果(认不出原因 **不等于** 这件事没有后果)
        assertTrue(ReadMessages.httpGeneric("futureCode").contains(ReadMessages.COUNTS_UNKNOWN_TAIL))
        assertTrue(ReadMessages.clientGeneric("futureCode").contains(ReadMessages.COUNTS_UNKNOWN_TAIL))
    }

    // ── ② 分派只看 source, 绝不按码猜表 ──────────────────────────────────────

    @Test
    fun the_same_code_string_in_two_tables_gives_two_different_sentences() {
        // `timeout` 是**手机合成**的码: 它在 CLIENT 那张表里有一句"电脑没有回应"的话;
        // 而如果它作为 HTTP 层的码进来, HTTP 那张表根本不该认识它 —— 两句话必须不同。
        val asClient = ReadMessages.forFailure(client(ApiErrorCodes.TIMEOUT))
        val asHttp = ReadMessages.forFailure(http(ApiErrorCodes.TIMEOUT))
        assertNotEquals("同一个码串在两张表下必须是两句话(分派不许按码猜)", asClient, asHttp)
        assertTrue(asClient.contains("电脑没有回应") || asClient.contains("超时"))
        assertEquals(ReadMessages.httpGeneric(ApiErrorCodes.TIMEOUT), asHttp)
    }

    @Test
    fun a_code_that_only_exists_in_the_xmp_table_gets_the_plain_http_fallback() {
        // readOnly / writeProtect / encoding / pathTooLong 在 §5.3 那张表里**根本不存在**,
        // 它们只属于写边车的逐项回报。读取这条链路上它们不可能出现 —— 真出现了只能安全降级,
        // 而且**绝不许**借着 XMP 那套语义说话(那会让用户去查一个不存在的边车问题)。
        for (code in listOf(
            XmpErrorCodes.READ_ONLY,
            XmpErrorCodes.WRITE_PROTECT,
            XmpErrorCodes.ENCODING,
            XmpErrorCodes.PATH_TOO_LONG,
        )) {
            val msg = ReadMessages.forFailure(http(code))
            assertEquals(ReadMessages.httpGeneric(code), msg)
            assertTrue("兜底句必须带着码, 便于排查: $msg", msg.contains(code))
            for (word in listOf("边车", "合并", "元素形式")) {
                assertFalse("XMP 专有码 $code 的兜底句里不许出现边车语义($word): $msg", msg.contains(word))
            }
        }
    }

    @Test
    fun the_two_read_sources_are_exactly_http_and_client_there_is_no_xmp_branch() {
        // 类型上只有两档 ⇒ 不可能为"永远走不到的分支"写文案, 也不可能把 XMP 悄悄归到某张表上。
        assertEquals(listOf(ReadSource.HTTP, ReadSource.CLIENT), ReadSource.entries.toList())
        // readIncomplete 是手机自己合成的码: 服务端永远不会发它(与 WriteMessages.UNCONFIRMED 同一条)。
        assertFalse(ReadMessages.INCOMPLETE in ApiErrorCodes.ALL)
        assertFalse(ReadMessages.INCOMPLETE in XmpErrorCodes.ALL)
        assertTrue(ReadMessages.INCOMPLETE in ReadMessages.REACHABLE)
    }

    @Test
    fun the_bad_request_sentence_blames_the_app_not_the_users_path() {
        // 400 说明**手机发出的参数不合法** —— 让用户去"改路径后重试"是白折腾(计划 Task 2 Step 4 的坑)。
        val msg = ReadMessages.forFailure(http(ApiErrorCodes.BAD_REQUEST))
        assertTrue(msg, msg.contains("App 的问题"))
        assertFalse("不许把 400 说成用户的路径有问题: $msg", msg.contains("换个路径"))
        assertEquals(ReadAction.DISMISS, ReadMessages.actionFor(http(ApiErrorCodes.BAD_REQUEST)))
    }

    @Test
    fun a_read_forbidden_path_never_blames_the_read_only_mode() {
        // ⚠️ 这一条是**读**这条链路与**写**那条的关键差别: 只读模式拦的是**写方法**
        // (`routes.rs::refuse_writes_in_read_only` 判的是 is_write_method), 读取不受它影响。
        // 照抄写那条表的话, 用户会去关一个与本次失败**无关**的开关。
        val msg = ReadMessages.forFailure(http(ApiErrorCodes.FORBIDDEN_PATH))
        assertTrue("要说清正确原因(不在共享根里): $msg", msg.contains("共享根"))
        assertTrue("要明确纠正'只读模式'这个错因: $msg", msg.contains("只读模式拦的是写、不拦读"))
        assertEquals(ReadAction.PC_SIDE, ReadMessages.actionFor(http(ApiErrorCodes.FORBIDDEN_PATH)))
    }

    @Test
    fun the_not_found_sentence_says_the_retry_will_also_rescan_the_folder() {
        val msg = ReadMessages.forFailure(http(ApiErrorCodes.NOT_FOUND))
        assertTrue(msg, msg.contains("找不到"))
        assertTrue("要让用户知道重试读取会顺手重扫目录: $msg", msg.contains("重新扫一遍"))
        assertEquals(ReadAction.RETRY, ReadMessages.actionFor(http(ApiErrorCodes.NOT_FOUND)))
    }

    // ── ③ 动作: 落在对的那台设备上 ───────────────────────────────────────────

    @Test
    fun a_401_is_split_by_the_disambiguation_result_the_same_way_as_everywhere_else() {
        assertEquals(
            ReadAction.RECONNECT,
            ReadMessages.actionFor(http(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.TOKEN_ROTATED)),
        )
        assertEquals(
            "还没消歧时也只能照最可能的成因说: 去重新配对",
            ReadAction.RECONNECT,
            ReadMessages.actionFor(http(ApiErrorCodes.UNAUTHORIZED)),
        )
        assertEquals(
            "这个构建不暴露那个端点 ⇒ 去电脑上更新, 重新配对没用",
            ReadAction.PC_SIDE,
            ReadMessages.actionFor(http(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.ENDPOINT_NOT_EXPOSED)),
        )
        assertEquals(
            "复探也没打通 ⇒ 什么都断言不了, 只能重试(别让用户为网络问题去扫码)",
            ReadAction.RETRY,
            ReadMessages.actionFor(http(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED)),
        )
    }

    @Test
    fun retryable_things_are_retryable_and_unknown_codes_default_to_retry() {
        for (f in listOf(
            http(ApiErrorCodes.INTERNAL),
            http("someFutureCode"),
            client(ApiErrorCodes.TIMEOUT),
            client(ApiErrorCodes.NETWORK),
            client(ApiErrorCodes.SERVICE_OFF),
            client(ReadMessages.INCOMPLETE),
            client("someFutureClientCode"),
        )) {
            assertEquals(
                "读取失败时'再读一次'是唯一无害且常常有效的动作: ${f.code}",
                ReadAction.RETRY,
                ReadMessages.actionFor(f),
            )
        }
    }

    @Test
    fun every_action_has_its_own_words() {
        // 动作不同就必须字不同 —— 否则"不同动作"在界面上看不出来(与 WriteMessages/ViewerMessages 同一条)。
        val labels = ReadAction.entries.map { ReadMessages.actionLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("重试读取", ReadMessages.actionLabel(ReadAction.RETRY))
        assertTrue(ReadMessages.actionLabel(ReadAction.RECONNECT).contains("重新配对"))
    }

    @Test
    fun the_head_says_which_thing_went_wrong() {
        assertEquals(
            "电脑不允许读这个文件夹",
            ReadMessages.headFor(http(ApiErrorCodes.FORBIDDEN_PATH)),
        )
        assertEquals(
            "电脑上找不到这个文件夹里的照片",
            ReadMessages.headFor(http(ApiErrorCodes.NOT_FOUND)),
        )
        assertEquals(
            "这个文件夹的星级/色标只读回来一部分",
            ReadMessages.headFor(client(ReadMessages.INCOMPLETE)),
        )
        assertEquals(
            "这个文件夹的星级/色标没读出来",
            ReadMessages.headFor(http(ApiErrorCodes.INTERNAL)),
        )
        // 三个标题必须各不相同(否则用户分不清"没读到"与"只读到一部分")
        assertEquals(
            3,
            setOf(
                ReadMessages.headFor(http(ApiErrorCodes.FORBIDDEN_PATH)),
                ReadMessages.headFor(http(ApiErrorCodes.NOT_FOUND)),
                ReadMessages.headFor(client(ReadMessages.INCOMPLETE)),
            ).size,
        )
    }

    @Test
    fun the_incomplete_sentence_does_not_pretend_nothing_was_read() {
        // 200 但没回报全时, 已经回来的那些**照样算数** —— 话里必须说清这一点,
        // 否则用户会以为"一个都没读到", 而界面上明明有角标。
        val msg = ReadMessages.forFailure(client(ReadMessages.INCOMPLETE))
        assertTrue(msg, msg.contains("一部分"))
        assertTrue("要说清已经回来的那些照常显示: $msg", msg.contains("照样算数"))
    }
}
