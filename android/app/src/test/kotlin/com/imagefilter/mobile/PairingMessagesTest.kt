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
    fun a_403_on_the_read_path_never_blames_read_only_mode() {
        // FIX 2(一致性): 这一档在配对流程里走不到(未配对先回 401; 已配对时 /shares 不会 403),
        // 但它是**按码公开**的表 —— 读这条链路上的 403 只可能是"不在共享根里", 只读模式拦的是
        // 写方法(`routes.rs::is_write_method`), 造不出它。旧文案让用户"确认服务是否处在只读模式",
        // 那就是把人赶到一个与本次失败无关的开关上(与 ReadMessages / ViewerMessages 同一条纪律)。
        val text = PairingMessages.forFailure(ApiErrorCodes.FORBIDDEN_PATH)

        assertTrue("要逐字纠正这个错因: $text", text.contains("只读模式拦的是写"))
        assertFalse(
            "不许把只读模式列成本次失败的原因: $text",
            text.contains("是否处在只读模式") || text.contains("正开着只读模式"),
        )
        assertTrue("要说清真正的原因: $text", text.contains("共享根"))
        assertTrue("要给出电脑上的动作: $text", text.contains("电脑上"))
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

    // ── FIX 4: 两个框**常驻**的格式提示 ──────────────────────────────────────
    //
    // 原来两个框只有一个字符集占位符(而且 placeholder 只在"空 + 聚焦"时才出现),
    // 第一次配对的人只能猜"填主机名还是 IP? 要带端口吗? 配对码多长?"。

    @Test
    fun the_address_hint_shows_a_concrete_address_with_a_port() {
        val hint = PairingMessages.ADDRESS_HINT

        assertTrue("要给一个**具体的**例子: $hint", hint.contains(PairingMessages.URL_EXAMPLE))
        assertTrue("例子必须带 scheme: $hint", PairingMessages.URL_EXAMPLE.startsWith("http://"))
        assertTrue("例子必须带端口(少了它这条提示就没解决任何一个问题)", PairingMessages.URL_EXAMPLE.contains(":8765"))
        assertTrue("要说清去哪儿抄: $hint", hint.contains("设置"))
    }

    @Test
    fun the_token_hint_says_the_shape_and_the_length_of_the_code() {
        val hint = PairingMessages.TOKEN_HINT

        assertTrue("要说清是十六进制字符: $hint", hint.contains("十六进制"))
        assertTrue("要说清多少个字符(用户就是靠它判断有没有粘全): $hint", hint.contains("64"))
        assertTrue("要说清去哪儿拿: $hint", hint.contains("设置") || hint.contains("二维码"))
    }

    // ── FIX 6: 失败卡不再把那张四条清单抄第二遍 ──────────────────────────────

    @Test
    fun the_wizard_failure_card_says_it_once_and_points_at_the_standing_card() {
        // 配对向导里那张清单**一直**挂在失败卡正下方。失败卡再说一遍就是同一屏上把四件事
        // 列两次 —— 用户会以为是两组不同的排查项。
        for (code in ApiErrorCodes.ALL) {
            val inWizard = PairingMessages.forFailureInWizard(code)
            assertTrue("向导里那句话不能空: $code", inWizard.length >= 10)
            if (!PairingMessages.carriesChecklist(code)) continue
            for (item in PairingMessages.PREREQUISITES) {
                assertFalse("向导里**不许**再抄一遍清单($code): $item\n实际=$inWizard", inWizard.contains(item))
            }
            assertTrue(
                "但要指着下面那张常驻卡说去哪看: $inWizard",
                inWizard.contains(PairingMessages.CHECKLIST_HEAD),
            )
            // 可执行的那一句必须还在(去掉的只许是重复的那四条):
            val head = PairingMessages.forFailureHead(code)
            assertTrue("可执行的那句话必须留着: $inWizard", inWizard.startsWith(head))
        }
    }

    @Test
    fun the_standing_card_head_is_shared_by_both_sides() {
        // 卡里那句"按下面「…」里的四条逐一排查"与那张卡的标题取自同一个常量 ——
        // 各写一份就迟早出现"卡里指着一个屏幕上不存在的标题"。
        val text = PairingMessages.forFailureInWizard(ApiErrorCodes.SERVICE_OFF)
        assertTrue("常量本身就是常驻卡的标题", PairingMessages.CHECKLIST_HEAD.isNotBlank())
        assertTrue(text.contains("「${PairingMessages.CHECKLIST_HEAD}」"))
    }

    @Test
    fun the_other_screens_still_carry_the_whole_checklist() {
        // 对照: 浏览页/查看器/… 那些页面上**没有**这张常驻卡, 所以它们拿到的文案
        // (forFailure)必须自带清单 —— "去掉重复"绝不许变成"把清单删掉"。
        for (code in ApiErrorCodes.ALL) {
            val text = PairingMessages.forFailure(code)
            if (!PairingMessages.carriesChecklist(code)) continue
            for (item in PairingMessages.PREREQUISITES) {
                assertTrue("$code 的文案里少了这一条: $item", text.contains(item))
            }
        }
        assertEquals(
            "三档传输层失败才带清单",
            setOf(ApiErrorCodes.SERVICE_OFF, ApiErrorCodes.TIMEOUT, ApiErrorCodes.NETWORK),
            ApiErrorCodes.ALL.filter { PairingMessages.carriesChecklist(it) }.toSet(),
        )
    }
}
