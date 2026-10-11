// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.UNDO_LIMIT
import com.imagefilter.mobile.ui.UndoEntry
import com.imagefilter.mobile.ui.UndoResult
import com.imagefilter.mobile.ui.UndoStack
import com.imagefilter.mobile.ui.undoMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销**栈**本身(纯逻辑, 一条网络都不碰): 顺序 / 上限 / 清空 / 上限非法当场炸 /
 * 撤销那句话的诚实性。
 *
 * 为什么把它与"撤销那条链路"分开测: 栈是**结构**(后进先出、有上限), 链路是**语义**
 * (恢复哪一份意图、要不要发请求)。混在一个文件里, 两边都容易互相掩盖 —— 结构写错时
 * 语义那部分照旧绿, 反之亦然(见 [UndoTest] 的链路测试)。
 */
class UndoStackTest {

    private val p = "D:\\照片\\a.jpg"

    private fun entry(path: String = p, before: Int? = 1, after: Int? = 0) =
        UndoEntry(path, Decision(path, rating = before), Decision(path, rating = after))

    @Test
    fun pops_in_reverse_order() {
        val s = UndoStack()
        s.push(entry("a"))
        s.push(entry("b"))
        assertEquals("b", s.pop()!!.path)
        assertEquals("a", s.pop()!!.path)
        assertNull(s.pop())
        assertNull("弹空了之后再弹还是 null(不抛异常)", s.pop())
    }

    @Test
    fun is_bounded_so_a_long_session_cannot_grow_forever() {
        // 滑 2000 张不能把内存吃掉; 丢掉的是**最早**的那条, 不是最新的 ——
        // 用户按撤销时想撤回的一定是"刚刚那一下"。
        val s = UndoStack(max = 3)
        repeat(5) { s.push(entry("p$it")) }
        assertEquals(3, s.size)
        assertEquals("p4", s.pop()!!.path)
        assertEquals("p3", s.pop()!!.path)
        assertEquals("p2", s.pop()!!.path)
        assertNull(s.pop())
    }

    @Test
    fun the_default_limit_is_the_number_the_spec_names() {
        // 规格 §8.2: 内存栈上限 50。
        assertEquals(50, UNDO_LIMIT)
        val s = UndoStack()
        repeat(UNDO_LIMIT + 10) { s.push(entry("p$it")) }
        assertEquals(UNDO_LIMIT, s.size)
        assertEquals("留下的是最新的那一步", "p59", s.pop()!!.path)
    }

    @Test
    fun clear_empties_it() {
        val s = UndoStack()
        s.push(entry("a"))
        s.clear()
        assertEquals(0, s.size)
        assertNull(s.pop())
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_zero_limit_is_a_programming_error_not_a_silently_useless_control() {
        UndoStack(max = 0)
    }

    @Test
    fun an_entry_keeps_both_the_intent_before_and_after_the_change() {
        // 撤销要恢复的是 [UndoEntry.before] —— 存成"改完之后那份"就永远撤不回去。
        val before = Decision(p, rating = 4)
        val after = Decision(p, rating = 0)
        val e = UndoEntry(p, before, after)
        assertEquals(4, e.before.rating)
        assertEquals(0, e.after.rating)
        assertEquals(p, e.path)
    }

    @Test
    fun the_undo_sentence_never_claims_a_write_the_server_did_not_confirm() {
        // 与界面上的失败卡同一条纪律: 写没写进电脑由服务端说, 客户端不许自己判。
        assertEquals("已撤销", undoMessage(UndoResult.Push(PushResult.Ok(1, 1, 0, false))))

        val failed = undoMessage(
            UndoResult.Push(PushResult.Failed(1, 0, 0, emptyList(), summaryConfirmed = false)),
        )
        assertFalse("没被确认就绝不许说'已撤销': $failed", failed.contains("已撤销"))
        assertTrue(failed, failed.contains("没能写进电脑"))

        // "没发请求"与"发出去了"也必须分得清 —— 否则用户以为电脑刚被写了一次。
        assertTrue(undoMessage(UndoResult.Empty).contains("没有可撤销"))
        assertTrue(undoMessage(UndoResult.LocalOnly).contains("没有发送写入"))
    }
}
