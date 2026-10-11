// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.ViewerAction
import com.imagefilter.mobile.data.ViewerMessages
import com.imagefilter.mobile.data.WriteAction
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.XmpErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **两张码表**的纪律测试(规格 §5.3 的接口细节, M1′ Task 2 实测修正)。
 *
 * 为什么值得一整个套件: `readOnly` / `writeProtect` / `permission` / `encoding` /
 * `pathTooLong` **只存在于** XMP 表里, 而 `notXmp` / `unsupportedForm` / `tooLarge` /
 * `busy` / `diskFull` 两张表里都有。把两张表混成一张的后果不是崩溃, 而是
 * **这些失败显示不出任何文案**(用户看到一句没有动作的废话, 而他的分数真的没落盘);
 * 混表的另一种后果更坏: `unsupportedForm` 在写边车这条路上被显示成"电脑解不开这张图",
 * 于是用户跑去查一个根本不存在的解码器问题。
 *
 * 所以这里逐条钉: 每一档都得有**自己的**话、动作落在**对的那台设备**上。
 */
class WriteMessagesTest {

    private fun xmp(code: String) = WriteFailure(path = "D:\\p\\a.jpg", code = code, source = FailureSource.XMP)
    private fun http(code: String) = WriteFailure(path = "D:\\p\\a.jpg", code = code, source = FailureSource.HTTP)
    private fun client(code: String) = WriteFailure(path = "D:\\p\\a.jpg", code = code, source = FailureSource.CLIENT)

    @Test
    fun every_xmp_code_gets_its_own_sentence_never_the_fallback() {
        // 混表最容易造成的就是这条红: readOnly / permission / encoding / pathTooLong
        // 在 HTTP 表里没有对应项, 查错表 → 全部落到兜底句。
        //
        // ⚠️ 两个兜底句都要比: 只比 `xmpGeneric` 的话, "查错表"那种写法会**落地到另一个兜底句**
        // 从而骗过断言(这是变异 C 实测暴露出来的一个假绿, 别把它改回去)。
        for (code in XmpErrorCodes.ALL) {
            val text = WriteMessages.forFailure(xmp(code))
            assertNotEquals("XMP 码 $code 不许落到 XMP 兜底句", WriteMessages.xmpGeneric(code), text)
            assertNotEquals("XMP 码 $code 不许落到 HTTP 兜底句(那是查错了表)", WriteMessages.httpGeneric(code), text)
            assertNotEquals("XMP 码 $code 不许落到手机合成码的兜底句", WriteMessages.clientGeneric(code), text)
            assertTrue("XMP 码 $code 的话太短了, 看不出是给用户的", text.length > 20)
        }
    }

    @Test
    fun every_http_code_gets_its_own_sentence_never_the_fallback() {
        for (code in ApiErrorCodes.SERVER_CODES) {
            val text = WriteMessages.forFailure(http(code))
            assertNotEquals("HTTP 码 $code 不许落到 HTTP 兜底句", WriteMessages.httpGeneric(code), text)
            assertNotEquals("HTTP 码 $code 不许落到 XMP 兜底句(那是查错了表)", WriteMessages.xmpGeneric(code), text)
            assertNotEquals(WriteMessages.clientGeneric(code), text)
            assertTrue(text.length > 20)
        }
    }

    @Test
    fun every_client_synthesized_code_gets_its_own_sentence() {
        // ⚠️ 手机自己合成的码也要一起进这个循环: `nothingWritable` / `nothingChanged` 是**本轮**
        // 加的两档, 少了它们就会安静地落到兜底句上(那句"点重试写入再确认一次"对一条永远办不成
        // 事的意图来说是一句错话)。
        for (code in ApiErrorCodes.CLIENT_SYNTHESIZED_CODES +
            listOf(WriteMessages.UNCONFIRMED, WriteMessages.NOTHING_WRITABLE, WriteMessages.NOTHING_CHANGED)) {
            val text = WriteMessages.forFailure(client(code))
            assertNotEquals(WriteMessages.clientGeneric(code), text)
            assertNotEquals(WriteMessages.httpGeneric(code), text)
            assertNotEquals(WriteMessages.xmpGeneric(code), text)
            assertTrue(text.length > 20)
        }
    }

    @Test
    fun the_two_no_op_codes_do_not_masquerade_as_a_connection_problem() {
        // ⚠️ 这两档**不是**"没连上电脑": 一档是请求根本没发出去, 另一档是请求到了电脑而它什么都
        // 没改。说成网络问题会让用户去查 WiFi/电源, 而真正的问题在"这一条改动表达不出来"。
        for (code in listOf(WriteMessages.NOTHING_WRITABLE, WriteMessages.NOTHING_CHANGED)) {
            val f = client(code)
            assertEquals(
                "码 $code 的标题不许说成'没能连上电脑'",
                "这次写入没有让电脑上的值发生变化",
                WriteMessages.headFor(f),
            )
            assertNotEquals(WriteMessages.headFor(client(ApiErrorCodes.SERVICE_OFF)), WriteMessages.headFor(f))
            assertEquals("动作仍是重试(不猜原因)", WriteAction.RETRY, WriteMessages.actionFor(f))
            val text = WriteMessages.forFailure(f)
            assertTrue("必须说清这一版留在手机上: $text", text.contains("这台手机") || text.contains("留着"))
            assertTrue("必须说清电脑上的值没有发生变化: $text", text.contains("没有让电脑上的值发生变化") ||
                text.contains("不会让电脑上的值发生变化"))
        }
        // 两档说的**不是**同一件事: 一档连请求都没发, 另一档服务端确实看过了
        assertNotEquals(
            WriteMessages.forFailure(client(WriteMessages.NOTHING_WRITABLE)),
            WriteMessages.forFailure(client(WriteMessages.NOTHING_CHANGED)),
        )
    }

    @Test
    fun the_same_code_dispatches_by_table_and_says_two_different_things() {
        // 两张表的交集(5 个)。同一个字符串从不同来源来, 必须是两句不同的话 ——
        // 这就是 source 存在的理由(光看码本身分不出来)。
        val intersection = ApiErrorCodes.SERVER_CODES intersect XmpErrorCodes.ALL
        assertEquals(
            "交集是 5 个(M1′ 实测修正): notXmp/unsupportedForm/tooLarge/busy/diskFull",
            setOf("notXmp", "unsupportedForm", "tooLarge", "busy", "diskFull"),
            intersection,
        )
        for (code in intersection) {
            assertNotEquals(
                "码 $code 在两张表里必须给不同的话(unsupportedForm 的语义在两处完全不同)",
                WriteMessages.forFailure(http(code)),
                WriteMessages.forFailure(xmp(code)),
            )
        }
    }

    @Test
    fun unsupported_form_on_the_write_path_never_talks_about_decoding() {
        // 写边车这条路: **元素形式, 无法安全合并**。绝不是"电脑解不开这张图"(那是看图那条链路)。
        val writeText = WriteMessages.forFailure(xmp(XmpErrorCodes.UNSUPPORTED_FORM))
        assertTrue(writeText, writeText.contains("边车"))
        assertFalse("写这条路不许提解码/解码器", writeText.contains("解码"))
        assertFalse(writeText.contains("格式"))

        // 反过来: 看图那张表也不许借"边车/合并"的说法(它那条链路上根本没有边车)。
        val viewerText = ViewerMessages.forFailure(ApiErrorCodes.UNSUPPORTED_FORM)
        assertFalse(viewerText, viewerText.contains("边车"))
        assertFalse(viewerText, viewerText.contains("合并"))
        assertTrue(viewerText, viewerText.contains("解码"))
    }

    @Test
    fun bad_request_blames_the_app_not_the_users_path() {
        // 400 = **手机发出的参数不合法**(客户端 bug)。让用户"改个路径再试"是白折腾 ——
        // 而且他改不了服务端认为不合法的那个东西。
        val f = http(ApiErrorCodes.BAD_REQUEST)
        val text = WriteMessages.forFailure(f)
        assertTrue(text, text.contains("App 的问题"))
        assertTrue(text, text.contains("反馈"))
        assertFalse("不许让用户去改文件名/路径后重试", text.contains("改名"))
        assertEquals(WriteAction.DISMISS, WriteMessages.actionFor(f))
    }

    @Test
    fun unauthorized_says_the_change_did_not_land_and_to_re_pair() {
        val f = http(ApiErrorCodes.UNAUTHORIZED)
        val text = WriteMessages.forFailure(f)
        assertTrue(text, text.contains("配对"))
        assertTrue("要说清改动没落盘", text.contains("没有"))
        assertEquals(WriteAction.RECONNECT, WriteMessages.actionFor(f))
    }

    @Test
    fun every_transport_code_tells_the_user_the_change_is_still_on_the_phone() {
        for (code in ApiErrorCodes.CLIENT_SYNTHESIZED_CODES) {
            val f = client(code)
            val text = WriteMessages.forFailure(f)
            assertTrue("码 $code: 必须说清改动还在手机上", text.contains("这台手机") || text.contains("重试"))
            assertEquals("码 $code 的动作是重试写入", WriteAction.RETRY, WriteMessages.actionFor(f))
        }
        assertTrue(WriteMessages.forFailure(client(WriteMessages.UNCONFIRMED)).contains("没有回报"))
        assertEquals(WriteAction.RETRY, WriteMessages.actionFor(client(WriteMessages.UNCONFIRMED)))
    }

    @Test
    fun unconfirmed_is_not_a_server_code_so_nobody_can_mistake_where_it_came_from() {
        assertFalse(WriteMessages.UNCONFIRMED in ApiErrorCodes.ALL)
        assertFalse(WriteMessages.UNCONFIRMED in XmpErrorCodes.ALL)
        assertEquals(WriteMessages.clientGeneric("whatever"), WriteMessages.forFailure(client("whatever")))
    }

    @Test
    fun the_server_side_unknown_code_is_not_the_forward_compat_fallback() {
        // `unknown` 是服务端**真会发**的一档; "认不出来的码"是前向兼容。两者不是一回事。
        assertNotEquals(WriteMessages.XMP_UNKNOWN, WriteMessages.xmpGeneric(XmpErrorCodes.UNKNOWN))
        assertEquals(WriteMessages.XMP_UNKNOWN, WriteMessages.forFailure(xmp(XmpErrorCodes.UNKNOWN)))
        assertEquals(WriteAction.RETRY, WriteMessages.actionFor(xmp(XmpErrorCodes.UNKNOWN)))
    }

    @Test
    fun actions_land_on_the_right_device_and_have_distinct_labels() {
        assertEquals(WriteAction.RETRY, WriteMessages.actionFor(xmp(XmpErrorCodes.BUSY)))
        // 只读/写保护/权限/磁盘满: 手机上再点也没用, 必须去电脑上 —— 文案与按钮字都要看得出来。
        for (code in listOf(
            XmpErrorCodes.READ_ONLY,
            XmpErrorCodes.WRITE_PROTECT,
            XmpErrorCodes.PERMISSION,
            XmpErrorCodes.DISK_FULL,
            XmpErrorCodes.PATH_TOO_LONG,
        )) {
            assertEquals("码 $code 的动作在电脑那一侧", WriteAction.PC_SIDE, WriteMessages.actionFor(xmp(code)))
        }
        for (code in listOf(XmpErrorCodes.NOT_XMP, XmpErrorCodes.TOO_LARGE, XmpErrorCodes.ENCODING)) {
            assertEquals("码 $code 重试不会有帮助", WriteAction.DISMISS, WriteMessages.actionFor(xmp(code)))
        }
        assertEquals(WriteAction.RELOAD_FOLDER, WriteMessages.actionFor(http(ApiErrorCodes.NOT_FOUND)))

        val labels = WriteAction.entries.map { WriteMessages.actionLabel(it) }
        assertEquals("每个动作的按钮字必须互不相同", labels.size, labels.toSet().size)
        assertEquals(5, WriteAction.entries.size)
    }

    @Test
    fun the_three_backgrounds_each_say_where_the_problem_is() {
        assertTrue(WriteMessages.headFor(xmp(XmpErrorCodes.READ_ONLY)).contains("边车"))
        assertTrue(WriteMessages.headFor(http(ApiErrorCodes.BUSY)).contains("拒绝"))
        assertTrue(WriteMessages.headFor(client(ApiErrorCodes.SERVICE_OFF)).contains("电脑"))
        assertEquals(WriteMessages.headFor(client(WriteMessages.UNCONFIRMED)), "这次写入没能确认")
    }

    @Test
    fun the_batch_line_only_speaks_when_more_than_one_item_failed() {
        // 半成功态要显式, 但一条失败不需要"1 张里有 1 张"这种废话。
        assertNull(WriteMessages.batchHead(requested = 1, failures = listOf(xmp(XmpErrorCodes.READ_ONLY))))
        assertNull(WriteMessages.batchHead(requested = 3, failures = emptyList()))
        assertEquals(
            "这一批 3 张里有 2 张没写进电脑。",
            WriteMessages.batchHead(
                requested = 3,
                failures = listOf(xmp(XmpErrorCodes.READ_ONLY), xmp(XmpErrorCodes.BUSY)),
            ),
        )
    }

    @Test
    fun the_batch_line_never_counts_an_unconfirmed_item_as_not_written() {
        // ⚠️ 本条钉的是一个**会撒谎的句子**: 逐项码 `unconfirmed` 的意思是"服务端没回报这一项的
        // 写入结果"(**没有结论**), 它的逐项文案就是这么说的; 批次句把它数进"没写进电脑"
        // 就是替服务端保证了一件它没说过的事 —— 与纪律③"未同步 ≠ 已同步"同一条。
        val a = xmp(XmpErrorCodes.READ_ONLY)                 // 确认失败
        val u1 = client(WriteMessages.UNCONFIRMED)           // 结果不明
        val u2 = client(WriteMessages.UNCONFIRMED)

        // 两种混在一批: 分开数、分开说(3 张里 1 张确认没写成、1 张没有结论)
        assertEquals(
            "这一批 3 张里有 1 张没写进电脑，1 张没能确认（结果不明）。",
            WriteMessages.batchHead(requested = 3, failures = listOf(a, u1)),
        )
        // 全是"没有结论"时**一个字都不许提"没写进电脑"**(那正是老句子的谎话)
        val allUnknown = WriteMessages.batchHead(requested = 4, failures = listOf(u1, u2))!!
        assertFalse("不许说'没写进电脑': $allUnknown", allUnknown.contains("没写进电脑"))
        assertTrue(allUnknown, allUnknown.contains("2 张没能确认"))
        assertTrue(allUnknown, allUnknown.contains("4 张里"))

        // 传输层失败(没连上/超时/断线)照旧算"没写进电脑" —— 它们的逐项文案本来就是这么说的,
        // 两处必须一致(判据只看 source/code 这两件逐字的事实, 不靠猜)。
        assertEquals(
            "这一批 2 张里有 2 张没写进电脑。",
            WriteMessages.batchHead(
                requested = 2,
                failures = listOf(
                    client(ApiErrorCodes.SERVICE_OFF),
                    client(ApiErrorCodes.TIMEOUT),
                ),
            ),
        )
    }

    @Test
    fun every_unconfirmed_sentence_agrees_that_there_is_no_conclusion() {
        // 批次句与逐项句必须说同一件事 —— 一边"没有结论"、另一边"没写进去"就是自相矛盾。
        val text = WriteMessages.forFailure(client(WriteMessages.UNCONFIRMED))
        assertTrue(text, text.contains("没有回报"))
        assertFalse("不许替服务端保证'没写进去': $text", text.contains("没有落到电脑上"))
    }

    @Test
    fun the_unsynced_badge_is_a_sentence_with_an_action() {
        assertEquals("1 张未同步·重试", WriteMessages.unsyncedLabel(1))
        assertEquals("4 张未同步·重试", WriteMessages.unsyncedLabel(4))
        assertTrue(WriteMessages.UNSYNCED_THIS_PHOTO.contains("没写进电脑"))
    }

    @Test
    fun the_viewer_table_still_does_not_handle_xmp_only_codes() {
        // 反向确认两张表**没有被合并**: 看图的表里没有 readOnly 这一档, 它落到兜底句是**正确行为**。
        assertEquals(ViewerMessages.GENERIC, ViewerMessages.forFailure(XmpErrorCodes.READ_ONLY))
        assertEquals(ViewerAction.RETRY, ViewerMessages.actionFor(XmpErrorCodes.READ_ONLY))
    }
}
