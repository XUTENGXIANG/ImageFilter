// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.ViewerAction
import com.imagefilter.mobile.data.ViewerMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器文案表的契约(规格 §5.3: 每条码必须落到"用户下一步做什么")。
 *
 * 三条最要紧的:
 *   ① `forbiddenPath`(403) 与 `notFound`(404) **话不一样, 动作也不一样** ——
 *      一个要去电脑上改共享根, 一个是"照片真的没了, 回网格刷新";
 *   ② 看图链路上真会收到的每个码都有**不是兜底句**的话;
 *   ③ `unsupportedForm` 用的是**媒体**语义("解不开这张图"), 不是边车那条。
 */
class ViewerMessagesTest {

    @Test
    fun only_codes_the_viewer_can_actually_receive_are_declared_reachable() {
        // 声明"可达"的码必须都是服务端或手机合成码表里真有的东西 —— 打错一个字母
        // 就会变成一句永远走不到的文案(而且没人会发现)
        for (code in ViewerMessages.REACHABLE) {
            assertTrue("$code 不在任何码表里", code in ApiErrorCodes.ALL)
        }
        assertTrue(
            "401/403/404/超时 这几条是查看器一定会遇到的",
            ViewerMessages.REACHABLE.containsAll(
                listOf(
                    ApiErrorCodes.UNAUTHORIZED,
                    ApiErrorCodes.FORBIDDEN_PATH,
                    ApiErrorCodes.NOT_FOUND,
                    ApiErrorCodes.TIMEOUT,
                    ApiErrorCodes.SERVICE_OFF,
                ),
            ),
        )
    }

    @Test
    fun every_reachable_code_gets_a_sentence_of_its_own_not_the_fallback() {
        for (code in ViewerMessages.REACHABLE) {
            val text = ViewerMessages.forFailure(code)
            assertTrue("$code 落到了兜底句上", text != ViewerMessages.GENERIC)
            assertTrue("$code 只有一句话, 用户看不出下一步做什么: $text", text.length >= 20)
        }
    }

    @Test
    fun an_unknown_code_falls_back_without_guessing() {
        val text = ViewerMessages.forFailure("someFutureCode")
        assertEquals(ViewerMessages.GENERIC, text)
        // 兜底也要有动作, 但不许编一个具体原因
        assertEquals(ViewerAction.RETRY, ViewerMessages.actionFor("someFutureCode"))
        assertFalse(text.contains("403"))
        assertFalse(text.contains("404"))
    }

    // ── ① 403 与 404: 两句话, 两个动作 ──────────────────────────────────────

    @Test
    fun a_403_and_a_404_say_different_things_and_ask_for_different_actions() {
        val forbidden = ViewerMessages.forFailure(ApiErrorCodes.FORBIDDEN_PATH)
        val notFound = ViewerMessages.forFailure(ApiErrorCodes.NOT_FOUND)

        assertNotEquals("403 与 404 合成一句话, 必然有一边是错的", forbidden, notFound)
        // 403: 照片在电脑上存在, 只是**不在共享根里**(或只读模式) → 动作在电脑上
        assertTrue("403 要说共享根/只读模式: $forbidden", forbidden.contains("共享根"))
        assertEquals(ViewerAction.PC_SIDE, ViewerMessages.actionFor(ApiErrorCodes.FORBIDDEN_PATH))
        // 404: 照片真的没了 → 动作是"回网格刷新"
        assertTrue("404 要说明它没了: $notFound", notFound.contains("找不到"))
        assertEquals(ViewerAction.RELOAD_FOLDER, ViewerMessages.actionFor(ApiErrorCodes.NOT_FOUND))

        // 动作不同, 按钮上的字也必须不同 —— 否则"不同动作"在界面上根本看不出来
        assertNotEquals(
            ViewerMessages.actionLabel(ViewerAction.PC_SIDE),
            ViewerMessages.actionLabel(ViewerAction.RELOAD_FOLDER),
        )
        // 错误卡的标题也要分开(标题是用户第一眼看到的东西)
        assertNotEquals(
            ViewerMessages.headFor(ApiErrorCodes.FORBIDDEN_PATH),
            ViewerMessages.headFor(ApiErrorCodes.NOT_FOUND),
        )
    }

    @Test
    fun a_403_tells_the_user_that_retrying_on_the_phone_is_pointless() {
        val forbidden = ViewerMessages.forFailure(ApiErrorCodes.FORBIDDEN_PATH)
        // 这一条是刻意的: 403 上"重试"是个陷阱动作(在手机上做什么都没用)
        assertTrue("403 必须说清手机这一侧没用: $forbidden", forbidden.contains("手机"))
        assertNotEquals(ViewerAction.RETRY, ViewerMessages.actionFor(ApiErrorCodes.FORBIDDEN_PATH))
    }

    // ── ② unsupportedForm: 媒体语义, 不是边车语义 ───────────────────────────

    @Test
    fun unsupported_form_in_a_viewer_never_talks_about_sidecars() {
        val text = ViewerMessages.forFailure(ApiErrorCodes.UNSUPPORTED_FORM)
        assertTrue("这一档在查看器里就是「解不开这张图」: $text", text.contains("解不开"))
        for (word in listOf("边车", "XMP", "xmp", "合并", "星级", "色标")) {
            assertFalse("媒体语境不许出现边车的说法('$word'): $text", text.contains(word))
        }
    }

    @Test
    fun not_xmp_is_not_a_viewer_code_at_all() {
        // notXmp 是**边车格式**的码: 预览这条链路上产不出来 → 它落到兜底句上是正确的,
        // 而不是漏配(为永远走不到的分支编文案会掩盖真会收到的那几个)
        assertFalse(ApiErrorCodes.NOT_XMP in ViewerMessages.REACHABLE)
        assertEquals(ViewerMessages.GENERIC, ViewerMessages.forFailure(ApiErrorCodes.NOT_XMP))
    }

    // ── ③ 401 的三种成因给三个动作 ──────────────────────────────────────────

    @Test
    fun the_three_401_causes_lead_to_three_different_next_steps() {
        assertEquals(
            ViewerAction.RECONNECT,
            ViewerMessages.actionFor(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.TOKEN_ROTATED),
        )
        assertEquals(
            ViewerAction.RETRY,
            ViewerMessages.actionFor(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED),
        )
        assertEquals(
            "这个版本的电脑端没有那个端点 —— 手机上改什么都没用",
            ViewerAction.PC_SIDE,
            ViewerMessages.actionFor(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.ENDPOINT_NOT_EXPOSED),
        )
        // 三句话也必须不同(与配对页同一套: 复用的就是 PairingMessages)
        val rotated = ViewerMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.TOKEN_ROTATED)
        val undetermined = ViewerMessages.forFailure(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED)
        assertNotEquals(rotated, undetermined)
        assertTrue("UNDETERMINED 不许把网络故障说成配对码问题: $undetermined", undetermined.contains("网络"))
    }

    @Test
    fun every_action_has_a_label_that_says_what_happens() {
        for (action in ViewerAction.entries) {
            val label = ViewerMessages.actionLabel(action)
            assertTrue("$action 的按钮没有字", label.isNotBlank())
        }
        // 五个动作五个字, 不许有两个动作共用一个按钮文案
        assertEquals(
            "动作与按钮文案必须一一对应",
            ViewerAction.entries.size,
            ViewerAction.entries.map { ViewerMessages.actionLabel(it) }.toSet().size,
        )
    }
}
