// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.ratingGuardMessage
import com.imagefilter.mobile.ui.ratingKnowledgeLabel
import com.imagefilter.mobile.ui.ratingTapIsBlind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "电脑上这一张是几星"这条**知识**在界面上的三态(纯规则, 一条网络都不碰)。
 *
 * 为什么把它与"界面那条链路"([ViewerRatingGuardTest])分开测: 这里是**规则**(哪一档该画成未知、
 * 一次点击该不该被拦下), 那里是**链路**(点了之后到底发没发请求、本地变没变)。混在一个文件里
 * 两边都会互相掩盖 —— 规则写错时链路那部分照旧绿, 反之亦然(与 [UndoStackTest]/[UndoTest]
 * 的分法逐字同一条理由)。
 *
 * 三条不变量, 每条都对应一种"看起来能用、其实在骗人"的实现:
 *   ① **不知道 ≠ 没有星级**: 两个状态必须说成两句不同的话(否则一次读失败会把一张 4/5 星的照片
 *      画成"没评过", 而用户的一次点击是**绝对赋值**);
 *   ② **1..5 星在"不知道"时一律拦下**, 而"确知没有""确知是几星"照旧一次点击就能写;
 *   ③ **0 星(废弃)任何一档都不拦** —— 那是用户明确的取舍, 与左滑同一条规矩(允许降级)。
 */
class RatingKnowledgeTest {

    @Test
    fun a_one_to_five_tap_is_blind_exactly_when_the_pc_value_is_unknown() {
        // ⚠️ 三档 × {1..5} 的**完整矩阵**: 只测一支的话, "把 Unrated 也当成不知道"或者
        // "把 Rated 也当成不知道"这两种改坏都测不出来(本项目撞过的"对称分支只测一边")。
        for (stars in 1..5) {
            assertTrue("不知道 ⇒ $stars 星是一次瞎写", ratingTapIsBlind(DesktopRating.Unknown, stars))
            assertFalse(
                "确知'电脑上没有星级' ⇒ $stars 星可以写",
                ratingTapIsBlind(DesktopRating.Unrated, stars),
            )
            assertFalse(
                "确知'电脑上是 4 星' ⇒ $stars 星可以写",
                ratingTapIsBlind(DesktopRating.Rated(4), stars),
            )
        }
    }

    @Test
    fun zero_stars_is_an_explicit_reject_and_is_never_blocked() {
        // 0 = 废弃 = "我不要这张" —— 与左滑(允许降级)同一条规矩: 把它也拦下来就是
        // 把用户唯一确定的那个意图挡在门外。
        for (d in listOf(DesktopRating.Unknown, DesktopRating.Unrated, DesktopRating.Rated(5))) {
            assertFalse("$d 下 0 星都不许被拦", ratingTapIsBlind(d, 0))
        }
    }

    @Test
    fun the_three_states_get_three_different_sentences() {
        val unknown = ratingKnowledgeLabel(DesktopRating.Unknown)
        val unrated = ratingKnowledgeLabel(DesktopRating.Unrated)
        val rated = ratingKnowledgeLabel(DesktopRating.Rated(4))

        assertEquals("三档必须说成三句不同的话", 3, setOf(unknown, unrated, rated).size)
        assertNotEquals("'不知道'与'没有星级'混成一句就是本轮要修的那个静默", unknown, unrated)
        assertTrue("'不知道'要说清是**没读到**: $unknown", unknown.contains("没读到"))
        assertTrue("'确实没有'要说清是**没有星级**: $unrated", unrated.contains("没有星级"))
        assertTrue("有值的那一档要把数说对: $rated", rated.contains("4"))
        assertFalse("有值的那一档不许说'没读到': $rated", rated.contains("没读到"))
    }

    @Test
    fun a_known_desktop_value_is_framed_as_the_last_read_not_as_the_current_one() {
        // 有值的那两档在**只读到过**的时候必须自报家门说清时态: 读取是它们唯一的来源,
        // 而一次写入之后这个数就会过时(用户会把"电脑上：2★"读成"我这一下没生效")。
        val rated = ratingKnowledgeLabel(DesktopRating.Rated(4))
        val unrated = ratingKnowledgeLabel(DesktopRating.Unrated)

        assertTrue("有值那一档要说清是**上次读到**: $rated", rated.startsWith("上次读到"))
        assertTrue("'确知没有星级'同样会过时, 也要说清: $unrated", unrated.startsWith("上次读到"))
        assertFalse("上一次读到的值不许冒充电脑现在的事实: $rated", rated.startsWith("电脑上："))
        assertFalse("同上: $unrated", unrated.startsWith("电脑上："))
        // 数值本身照旧要对(换口径不是把信息换没)
        assertTrue("4 这个数不许丢: $rated", rated.contains("4"))
    }

    @Test
    fun a_confirmed_write_is_framed_as_the_current_value_and_never_as_the_last_read() {
        // FIX 3 的正题: 服务端确认过这次写入 ⇒ 电脑上**现在就是**这个值。
        // 这一档必须与"上次读到"那句**不一样**, 而且不能说成"上次读到" ——
        // 否则用户刚点完第 4 颗星, 屏幕上 4 颗实心、下面写着旧值, 读起来就是"没生效"。
        val read = ratingKnowledgeLabel(DesktopRating.Rated(2))
        val written = ratingKnowledgeLabel(DesktopRating.Rated(4), fromWrite = true)

        assertNotEquals("读到的 2 与写进去的 4 不许说成同一句", read, written)
        assertTrue("要说是电脑上**现在**的值: $written", written.startsWith("电脑上："))
        assertFalse("绝不许再说'上次读到': $written", written.startsWith("上次读到"))
        assertFalse("也不许说'没读到'(那是真的不知道): $written", written.contains("没读到"))
        assertTrue("4 这个数不许丢: $written", written.contains("4"))
        // 与上面那排星星**不矛盾**才是这一档存在的全部理由: 数必须与刚写下去的一致。
        assertTrue("要说清它来自一次被确认的写入: $written", written.contains("确认"))
    }

    @Test
    fun the_guard_sentence_says_that_nothing_was_written_and_how_to_override() {
        val msg = ratingGuardMessage(3)

        assertTrue("必须说清这一下**没有写入**: $msg", msg.contains("没有写入"))
        assertTrue("必须说清为什么(不知道电脑上是几星): $msg", msg.contains("不知道"))
        assertTrue("必须给出下一步(再点一次同一颗星): $msg", msg.contains("再点一次"))
        assertTrue("要说清写下去的是哪一档: $msg", msg.contains("3 星"))
    }
}
