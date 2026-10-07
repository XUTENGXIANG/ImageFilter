// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.XmpErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览页的码 → 文案映射(规格 §5.3 那条纪律: **每条码必须落到"用户下一步做什么"**)。
 *
 * 与 [PairingMessagesTest] 同一套三条要求, 但目标不同: 这里的码是**读目录**时来的,
 * 所以 `notFound` 的意思是"目录没了", 而不是配对页上的"端点没暴露" ——
 * 同一个码在两个屏幕上必须说两句不同的话, 这是本文件最要紧的一条。
 */
class BrowseMessagesTest {

    @Test
    fun every_code_reachable_while_browsing_gets_its_own_actionable_sentence() {
        for (code in BrowseMessages.REACHABLE) {
            val text = BrowseMessages.forFailure(code)
            assertNotEquals("$code 落到了兜底句上(等于没告诉用户原因)", BrowseMessages.GENERIC, text)
            assertTrue("$code 的文案太空, 不像一句可执行的话: $text", text.length >= 15)
        }
    }

    @Test
    fun the_reachable_set_is_a_subset_of_the_http_code_table_and_has_no_typos() {
        // 码是**字符串**, 打错一个字母时编译器不会说话 —— 这条把它按住
        assertTrue(
            "REACHABLE 里混进了 HTTP 码表以外的码: ${BrowseMessages.REACHABLE - ApiErrorCodes.ALL}",
            ApiErrorCodes.ALL.containsAll(BrowseMessages.REACHABLE),
        )
        // 读目录真会收到的五档(§5.3)一个都不许少
        for (code in listOf(
            ApiErrorCodes.UNAUTHORIZED,
            ApiErrorCodes.FORBIDDEN_PATH,
            ApiErrorCodes.NOT_FOUND,
            ApiErrorCodes.BUSY,
            ApiErrorCodes.INTERNAL,
        )) {
            assertTrue("$code 在浏览路径上真会收到, 必须有文案", code in BrowseMessages.REACHABLE)
        }
    }

    @Test
    fun a_forbidden_directory_and_a_missing_one_say_different_things_and_both_give_an_action() {
        val forbidden = BrowseMessages.forFailure(ApiErrorCodes.FORBIDDEN_PATH)
        val missing = BrowseMessages.forFailure(ApiErrorCodes.NOT_FOUND)

        assertNotEquals("403 与 404 的用户动作完全不同, 不许共用一句话", forbidden, missing)
        // 403 = 不在共享根内 → 动作是"去电脑上加共享根"(这是规格要求的那条区分)
        assertTrue("403 必须说到共享根: $forbidden", forbidden.contains("共享根"))
        assertTrue("403 必须给出动作: $forbidden", forbidden.contains("加进共享根") || forbidden.contains("添加"))
        // 404 = 目录没了 → 动作是"回上一层重选", 不许让用户去改共享根
        assertTrue("404 要说清目录没了: $missing", missing.contains("找不到"))
        assertTrue("404 要给动作: $missing", missing.contains("重新选"))
        assertFalse("404 不该让用户去动共享根: $missing", missing.contains("共享根"))
    }

    @Test
    fun an_unknown_code_degrades_to_the_generic_sentence_instead_of_a_crash_or_a_guess() {
        // 服务端将来加一档码时, 老客户端必须还能说一句人话(C1: 前向兼容)
        assertEquals(BrowseMessages.GENERIC, BrowseMessages.forFailure("someCodeFromAFutureServer"))
        assertEquals(BrowseMessages.GENERIC, BrowseMessages.forFailure(""))
        assertTrue("兜底句本身必须有动作", BrowseMessages.GENERIC.contains("重试"))
    }

    @Test
    fun only_codes_from_the_http_table_get_a_special_sentence() {
        // 两张表的纪律(§5.3): `writeProtect` / `readOnly` / `encoding` / `pathTooLong`
        // **只**出现在 XMP 码集里, 读目录的服务端路径产不出它们。它们落到兜底句是
        // **正确行为**, 而不是漏配 —— 反过来, 谁要是把 XMP 表混进来查, 这条会红。
        for (code in listOf(
            XmpErrorCodes.WRITE_PROTECT,
            XmpErrorCodes.READ_ONLY,
            XmpErrorCodes.PATH_TOO_LONG,
            XmpErrorCodes.ENCODING,
        )) {
            assertFalse("$code 不属于 HTTP 码表", code in ApiErrorCodes.ALL)
            assertEquals(
                "$code 是 XMP 的码, 浏览页不认识它 —— 应当安全降级",
                BrowseMessages.GENERIC,
                BrowseMessages.forFailure(code),
            )
        }
    }

    @Test
    fun the_401_causes_are_delegated_to_the_pairing_table_rather_than_reinvented() {
        // 鉴权层的动作与配对页**完全一样**(去重新配对 / 去更新电脑端 / 先查网络),
        // 所以是查同一张表。抄第二份的代价是: 将来只改一处, 两屏说法就漂移了。
        for (cause in UnauthorizedCause.entries) {
            assertEquals(
                "401 的文案必须与配对页逐字一致(cause=$cause)",
                PairingMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, cause),
                BrowseMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, cause),
            )
        }
        // 消歧的纪律跟着一起继承过来了: 复探打不通时**不许**让用户去换配对码
        val undetermined = BrowseMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED)
        assertFalse("复探打不通时不许说配对码不对: $undetermined", undetermined.contains("配对码不对"))
    }

    @Test
    fun the_empty_folder_and_the_empty_share_list_are_stated_as_states_not_as_errors() {
        // 空共享根是**服务端有意的安全默认**, 空目录是**正常结果** —— 两条都必须是
        // "状态说明 + 动作", 而不是一条错误码文案。
        assertTrue("空共享根要说清是有意默认: ${BrowseMessages.NO_SHARES}", BrowseMessages.NO_SHARES.contains("默认"))
        assertTrue("空共享根必须给动作: ${BrowseMessages.NO_SHARES}", BrowseMessages.NO_SHARES.contains("共享根"))
        assertTrue("空目录要说明白: ${BrowseMessages.EMPTY_DIR}", BrowseMessages.EMPTY_DIR.contains("没有子目录"))
        assertNotEquals(BrowseMessages.EMPTY_DIR, BrowseMessages.forFailure(ApiErrorCodes.NOT_FOUND))
        assertNotEquals(BrowseMessages.EMPTY_DIR, BrowseMessages.forFailure(ApiErrorCodes.FORBIDDEN_PATH))
        assertFalse("空目录不是错误, 不许出现码: ${BrowseMessages.EMPTY_DIR}", BrowseMessages.EMPTY_DIR.contains("404"))
        // 未选文件夹时的引导句是规格 §8.1 逐字
        assertEquals("选一个文件夹开始", BrowseMessages.PICK_A_FOLDER)
    }
}
