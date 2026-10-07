// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.PairingInputProblem
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.UnauthorizedCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 码 → 文案的映射。这是规格 §5.3 那条纪律("每条码必须落到用户下一步做什么")的可测形态。
 *
 * 三条要求, 每条都在下面钉住:
 *   ① **已知码不许落到兜底句**上(兜底句没有动作, 用户看了还是不知道干什么);
 *   ② **未知码必须安全降级** —— 服务端将来加一档码, 老客户端要能说一句人话, 而不是崩或空白;
 *   ③ **401 的三种成因必须说三句不同的话** —— 否则 Task 2 的消歧就白做了。
 */
class PairingMessagesTest {

    @Test
    fun every_code_the_server_or_the_phone_can_produce_gets_its_own_actionable_text() {
        for (code in ApiErrorCodes.ALL) {
            val text = PairingMessages.forFailure(code)
            assertNotEquals("$code 落到了兜底文案上(等于没有告诉用户原因)", PairingMessages.GENERIC, text)
            assertTrue("$code 的文案太空, 不像一句可执行的话: $text", text.length >= 10)
        }
    }

    @Test
    fun the_three_synthesized_codes_do_not_share_one_sentence() {
        // 超时 / 连不上 / 中途断 —— 用户动作不同(等一等 vs 去电脑上开服务 vs 重试),
        // 合成一档就必然有一档是错的。
        val three = listOf(
            ApiErrorCodes.TIMEOUT,
            ApiErrorCodes.SERVICE_OFF,
            ApiErrorCodes.NETWORK,
        ).map { PairingMessages.forFailure(it) }
        assertEquals("三档合成码必须是三句不同的话", 3, three.toSet().size)
    }

    @Test
    fun an_unknown_code_degrades_to_the_generic_sentence_instead_of_a_crash_or_a_guess() {
        assertEquals(PairingMessages.GENERIC, PairingMessages.forFailure("someCodeFromAFutureServer"))
        assertEquals(PairingMessages.GENERIC, PairingMessages.forFailure(""))
        assertTrue(
            "兜底句本身必须有动作",
            PairingMessages.GENERIC.isNotBlank() && PairingMessages.GENERIC.length >= 10,
        )
    }

    @Test
    fun only_the_token_rejected_cause_tells_the_user_to_re_pair() {
        val rejected = PairingMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.TOKEN_ROTATED)
        val notExposed =
            PairingMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.ENDPOINT_NOT_EXPOSED)
        val undetermined =
            PairingMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED)

        // ③ 的直接检验: "复探打不通"那句**不许**让用户去换配对码 ——
        // 那会把一个网络故障说成 token 问题, 用户白换一遍还是连不上。
        assertFalse(
            "复探打不通时不许说配对码不对: $undetermined",
            undetermined.contains("配对码不对"),
        )
        assertTrue("要说清是网络/电脑那一侧: $undetermined", undetermined.contains("网络"))
        assertNotEquals(rejected, undetermined)
        assertNotEquals(rejected, notExposed)
        assertNotEquals(notExposed, undetermined)
    }

    @Test
    fun the_unreachable_service_sentence_carries_the_whole_four_item_checklist() {
        // §1.3: 这四条都会表现为"手机连不上", 少列一条就少一条用户能自己排掉的原因。
        assertEquals("规格 §1.3 是四条", 4, PairingMessages.PREREQUISITES.size)
        val text = PairingMessages.forFailure(ApiErrorCodes.SERVICE_OFF)
        for (item in PairingMessages.PREREQUISITES) {
            assertTrue("连不上的文案里少了这一条: $item\n实际=$text", text.contains(item))
        }
        // 超时也给同一份清单(用户动作相同), 但**说法不同**(那是"没回应", 不是"没人应答")
        val timeout = PairingMessages.forFailure(ApiErrorCodes.TIMEOUT)
        assertNotEquals(text, timeout)
        assertTrue(timeout.contains(PairingMessages.PREREQUISITES.first()))
    }

    @Test
    fun every_local_input_problem_has_a_distinct_sentence_naming_the_fix() {
        val texts = PairingInputProblem.entries.map { PairingMessages.forInputProblem(it) }
        assertEquals("四个问题四句话", PairingInputProblem.entries.size, texts.toSet().size)
        for (t in texts) assertTrue("本地提示也是给人看的: $t", t.length >= 10)
        assertTrue(
            "形状不对时要告诉用户正确的形状",
            PairingMessages.forInputProblem(PairingInputProblem.BAD_TOKEN_SHAPE).contains("64"),
        )
    }
}
