// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.KEEP_LABEL
import com.imagefilter.mobile.ui.KEEP_STARS
import com.imagefilter.mobile.ui.LABELS
import com.imagefilter.mobile.ui.REJECT_LABEL
import com.imagefilter.mobile.ui.REJECT_STARS
import com.imagefilter.mobile.ui.SWIPE_THRESHOLD_FRACTION
import com.imagefilter.mobile.ui.SwipeAction
import com.imagefilter.mobile.ui.SwipeConfig
import com.imagefilter.mobile.ui.SwipeDirection
import com.imagefilter.mobile.ui.SwipeMode
import com.imagefilter.mobile.ui.actionWord
import com.imagefilter.mobile.ui.decideSwipe
import com.imagefilter.mobile.ui.modeName
import com.imagefilter.mobile.ui.swipeDirectionFor
import com.imagefilter.mobile.ui.swipeLegend
import com.imagefilter.mobile.ui.swipeThresholdPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑卡**语义**的 JVM 单测 —— 本任务最要紧的一段。
 *
 * 为什么全押在这里: 这一页的手势在真机上很难复现(而且手势细节计划里明说可能调), 但语义
 * **一条都不许动**。所以把"这一滑到底写什么"做成纯函数, 在这儿把它钉死; 手势那一层只负责
 * 把像素翻译成 [SwipeDirection]。
 *
 * 三层断言, 每一层都**故意**用不同的角度, 免得某一条能因为别的原因恰好通过:
 *   ① **逐条点名**(下面那一大串): 期望值一个个手写在断言里, 不与实现共用任何辅助函数;
 *   ② **全矩阵不变量**(168 行 = 7 种起始星级 × 4 种起始色标 × 2 个动作 × 3 档模式):
 *      每一行都断言那四条规矩(只写星级不碰色标 / 只写色标不碰星级 / 保留绝不降级 /
 *      unchanged 与"真的什么都没变"必须互为充要);
 *   ③ **分支计数**(按模式数出 unchanged 的行数, 算术写在注释里): 这是防"假绿"的那一条 ——
 *      一个只跑得到一支的循环、或者一个空矩阵, 都会让前两层安静地通过。
 *
 * ⚠️ 这里测不到: 真实的手势跟手感、卡片动画、以及"双指捏合不与单指拖动抢事件"(没有设备,
 * 也不为它引 Compose UI 测试依赖) —— 见报告里的 UNVERIFIED 清单。
 */
class SwipeTest {

    /** 规范路径的典型形态: 反斜杠 + 中文(与其它测试同一份形状)。 */
    private val p = "D:\\照片\\a.jpg"

    /** 一张照片当前的意图(只给语义关心的三个字段)。 */
    private fun at(rating: Int? = null, label: String? = null, clearLabel: Boolean = false) =
        Decision(path = p, rating = rating, label = label, clearLabel = clearLabel)

    // ── ① 逐条点名: 规格 §8.2 那张表 ────────────────────────────────────────

    @Test
    fun the_default_mapping_is_left_reject_right_keep() {
        // 规格 §8.2: 左滑 = 废弃(0★), 右滑 = 保留(3★), 与桌面端 X / J 对齐。
        assertEquals(SwipeAction.Reject, SwipeConfig().actionFor(SwipeDirection.Left))
        assertEquals(SwipeAction.Keep, SwipeConfig().actionFor(SwipeDirection.Right))
        assertEquals(SwipeMode.StarsOnly, SwipeConfig().mode)
        assertEquals(3, KEEP_STARS)
        assertEquals(0, REJECT_STARS)
        assertEquals("green", KEEP_LABEL)
        assertEquals("red", REJECT_LABEL)
        // 色标是闭集: 滑卡写的两个颜色必须是那五个里的, 不许发明第六种
        assertTrue(KEEP_LABEL, KEEP_LABEL in LABELS)
        assertTrue(REJECT_LABEL, REJECT_LABEL in LABELS)
    }

    @Test
    fun keep_on_an_unrated_photo_writes_three_stars() {
        val o = decideSwipe(at(), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertEquals(3, o.rating)
        assertNull("只写星级: 色标一个字节都不带", o.label)
        assertFalse(o.unchanged)
        assertNull("没有需要解释的事", o.message)
    }

    @Test
    fun keep_upgrades_a_previously_rejected_photo() {
        // 0 星是**明确的废弃**(桌面端 X 键)。用户改主意右滑, 语义上该变回"保留",
        // 而不是继续 0 星 —— 否则右滑就成了"什么都不做"。
        val o = decideSwipe(at(rating = 0), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertEquals(3, o.rating)
        assertFalse(o.unchanged)
    }

    @Test
    fun keep_never_downgrades_a_five_star_photo() {
        // 本设计里最容易写错、后果最严重的一条: 无条件写 3 会把桌面端给的 5 星**静默降成** 3 星。
        val o = decideSwipe(at(rating = 5), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertNull("已有 ≥1 星时, 连一个星级值都不许发出去(发了就是把 5 改成 3)", o.rating)
        assertTrue("必须告诉用户这一滑没有改星级", o.unchanged)
        assertEquals("已保留 · 保持 5★", o.message)
    }

    @Test
    fun keep_never_downgrades_a_four_star_photo() {
        val o = decideSwipe(at(rating = 4), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertNull(o.rating)
        assertTrue(o.unchanged)
        assertEquals("已保留 · 保持 4★", o.message)
    }

    @Test
    fun keep_does_not_rewrite_one_or_two_stars_either() {
        // "保留"不是"改成 3 星": 只有 0 星(明确的废弃)才会被右滑改回 3 星。
        for (r in listOf(1, 2)) {
            val o = decideSwipe(at(rating = r), SwipeAction.Keep, SwipeMode.StarsOnly)
            assertNull("$r 星不许被改写成任何值", o.rating)
            assertTrue("$r 星这一滑不产生写入", o.unchanged)
            assertEquals("已保留 · 保持 $r★", o.message)
        }
    }

    @Test
    fun reject_sets_zero_stars_even_when_it_was_five() {
        // 左滑是**用户显式动作**, 允许降级 —— 与"保留不降级"不矛盾:
        // 后者是含糊的"留着", 前者是明确地说"我不要这张"。
        val o = decideSwipe(at(rating = 5), SwipeAction.Reject, SwipeMode.StarsOnly)
        assertEquals(0, o.rating)
        assertFalse(o.unchanged)
    }

    @Test
    fun reject_on_an_unrated_photo_still_writes_zero() {
        // null 与 0 是两件事: null = 手机上还没定过。左滑是明确的"废弃", 必须写下 0
        // (把它当成"已经是 0 了"会让这个操作静默失败)。
        val o = decideSwipe(at(rating = null), SwipeAction.Reject, SwipeMode.StarsOnly)
        assertEquals(0, o.rating)
        assertFalse(o.unchanged)
    }

    @Test
    fun reject_on_an_already_rejected_photo_writes_nothing_and_says_so() {
        val o = decideSwipe(at(rating = 0), SwipeAction.Reject, SwipeMode.StarsOnly)
        assertNull(o.rating)
        assertTrue(o.unchanged)
        assertEquals("已废弃 · 本来就是 0★", o.message)
        assertNotNull("不写就必须说清为什么(否则用户以为这一下没生效)", o.message)
    }

    @Test
    fun stars_only_never_touches_an_existing_label() {
        // 滑卡不该顺手覆盖用户精心标的色标 —— 这是"只写星级"这一档存在的**全部理由**。
        val current = at(rating = null, label = "purple")
        val o = decideSwipe(current, SwipeAction.Keep, SwipeMode.StarsOnly)
        assertEquals(3, o.rating)
        assertNull("色标这一维一个字节都不带", o.label)
        val after = o.applyTo(current)
        assertEquals("本地色标也必须原样", "purple", after.label)
        assertFalse("也不许偷偷把'删色标'这条命令置上", after.clearLabel)
    }

    @Test
    fun stars_only_keeps_a_pending_clear_label_command_instead_of_cancelling_it() {
        // clearLabel 是用户之前在色标面板上做的决定(还没发出去)。一次滑卡没有资格把它取消 ——
        // 取消掉就是**静默丢掉用户已经做出的意图**。
        val current = at(rating = null, label = null, clearLabel = true)
        val o = decideSwipe(current, SwipeAction.Reject, SwipeMode.StarsOnly)
        assertEquals(0, o.rating)
        assertNull(o.label)
        assertTrue("那条'删色标'的命令必须原样留着, 由它的写入链路去消费", o.applyTo(current).clearLabel)
    }

    @Test
    fun labels_only_never_touches_the_stars() {
        val current = at(rating = 4, label = null)
        val keep = decideSwipe(current, SwipeAction.Keep, SwipeMode.LabelsOnly)
        assertNull("只写色标: 星级一个字节都不带", keep.rating)
        assertEquals("green", keep.label)
        assertEquals("本地星级原样", 4, keep.applyTo(current).rating)

        val reject = decideSwipe(current, SwipeAction.Reject, SwipeMode.LabelsOnly)
        assertNull(reject.rating)
        assertEquals("red", reject.label)
        assertEquals(4, reject.applyTo(current).rating)
    }

    @Test
    fun labels_only_on_a_photo_that_already_has_that_colour_writes_nothing() {
        val o = decideSwipe(at(rating = 4, label = "green"), SwipeAction.Keep, SwipeMode.LabelsOnly)
        assertNull(o.label)
        assertNull(o.rating)
        assertTrue(o.unchanged)
        assertEquals("已保留 · 色标本来就是绿", o.message)
    }

    @Test
    fun both_mode_writes_the_colour_and_still_never_downgrades() {
        // 两个都写: 色标照写, 但星级那一半仍然守"保留不降级" —— 而且**仍然要告诉用户**。
        val o = decideSwipe(at(rating = 5, label = null), SwipeAction.Keep, SwipeMode.Both)
        assertEquals("5 星不许被改成 3", null, o.rating)
        assertEquals("green", o.label)
        assertFalse("色标要写 ⇒ 这一滑确实有写入", o.unchanged)
        assertEquals("已保留 · 保持 5★", o.message)

        val reject = decideSwipe(at(rating = null, label = null), SwipeAction.Reject, SwipeMode.Both)
        assertEquals(0, reject.rating)
        assertEquals("red", reject.label)
        assertFalse(reject.unchanged)
    }

    @Test
    fun both_mode_reports_both_reasons_when_it_writes_nothing_at_all() {
        val o = decideSwipe(at(rating = 5, label = "green"), SwipeAction.Keep, SwipeMode.Both)
        assertTrue(o.unchanged)
        assertEquals("已保留 · 保持 5★、色标本来就是绿", o.message)
    }

    // ── ② 全矩阵不变量 ──────────────────────────────────────────────────────

    private data class Row(val rating: Int?, val label: String?, val action: SwipeAction, val mode: SwipeMode)

    private val startRatings = listOf<Int?>(null, 0, 1, 2, 3, 4, 5)
    private val startLabels = listOf<String?>(null, "purple", "green", "red")

    /**
     * 7 × 4 × 2 × 3 = 168 行。
     *
     * `null`(从没评过)与 `0`(明确废弃)都必须在里面 —— 本仓库的教训里有一条正是
     * "计数测试的夹具里没有未评分的照片, 于是它什么都没测到"。
     */
    private val matrix: List<Row> = startRatings.flatMap { r ->
        startLabels.flatMap { l ->
            SwipeAction.entries.flatMap { a ->
                SwipeMode.entries.map { m -> Row(r, l, a, m) }
            }
        }
    }

    @Test
    fun the_matrix_is_big_enough_to_mean_something() {
        assertEquals("7 星级 × 4 色标 × 2 动作 × 3 模式", 168, matrix.size)
        assertTrue("夹具里必须有未评分的照片", matrix.any { it.rating == null })
        assertTrue("夹具里必须有已评分的照片", matrix.count { (it.rating ?: 0) >= 1 } >= 50)
    }

    @Test
    fun every_row_of_the_matrix_obeys_the_four_rules() {
        for (row in matrix) {
            val current = at(rating = row.rating, label = row.label)
            val o = decideSwipe(current, row.action, row.mode)
            val where = "rating=${row.rating} label=${row.label} action=${row.action} mode=${row.mode}"
            val after = o.applyTo(current)

            // 规矩三之一: 只写星级 ⇒ 色标一个字节都不带, 本地也一个字节都没变
            if (row.mode == SwipeMode.StarsOnly) {
                assertNull("$where: 只写星级时不许带色标", o.label)
                assertEquals("$where: 本地色标不许变", current.label, after.label)
                assertFalse("$where: 不许偷偷置上'删色标'", after.clearLabel)
            }

            // 规矩三之二: 只写色标 ⇒ 星级一个字节都不带, 本地星级不变
            if (row.mode == SwipeMode.LabelsOnly) {
                assertNull("$where: 只写色标时不许带星级", o.rating)
                assertEquals("$where: 本地星级不许变", current.rating, after.rating)
            }

            val writeStars = row.mode != SwipeMode.LabelsOnly
            val before = row.rating

            // 规矩二: **保留绝不降级**。已有 ≥1 星时不许写任何星级值; 写了就只能是把
            // "没评过/已废弃"抬到 3 星 —— 两种情况都不会让评分变小。
            if (row.action == SwipeAction.Keep) {
                if (before != null && before >= 1) {
                    assertNull("$where: 保留不许给已有 ≥1 星的照片写任何星级值", o.rating)
                }
                if (o.rating != null) {
                    assertEquals("$where: 保留要写就只写 3 星", KEEP_STARS, o.rating)
                    assertTrue("$where: 保留只可能把空/0 星抬起来", before == null || before < KEEP_STARS)
                }
                assertTrue(
                    "$where: 保留之后的有效星级不许比原来低",
                    (o.rating ?: before ?: 0) >= (before ?: 0),
                )
            }

            // 规矩一: 左滑(废弃) = 0 星 —— 只要这一维开着, 除非它**已经**是 0 星(那就不写)
            if (row.action == SwipeAction.Reject && writeStars) {
                if (before == REJECT_STARS) {
                    assertNull("$where: 已经是 0 星就不必再写一遍", o.rating)
                } else {
                    assertEquals("$where: 废弃必须写 0 星", REJECT_STARS, o.rating)
                }
                assertEquals("$where: 废弃之后有效星级就是 0", REJECT_STARS, o.rating ?: before)
            }

            // 星级取值只能是 0..5(与服务端 xmp:Rating 的闭集一致)
            if (o.rating != null) {
                assertTrue("$where: 星级越界 ${o.rating}", o.rating!! in 0..5)
            }

            // 规矩四: `unchanged` 与"落到意图上真的什么都没变"必须**互为充要** ——
            // 这一条同时挡住两种相反的错: 说了不写却改了, 说了要写却写了个空。
            assertEquals("$where: unchanged 必须严格等于'落到意图上没有任何变化'", o.unchanged, after == current)
            if (o.unchanged) {
                assertNotNull("$where: 不写就必须给一句话(规格 §8.2 的'仍要给出明确反馈')", o.message)
            }
            if (!o.unchanged) {
                assertTrue("$where: 说要写就必须真的带了一个值出去", o.rating != null || o.label != null)
            }
        }
    }

    @Test
    fun the_matrix_exercises_both_branches_by_a_known_count() {
        // 防"假绿"的第三条: 一个只跑得到一支的循环(或空矩阵)会让上面那条安静地通过。
        // 手算的算术写在每一行上 —— 它与实现**不是**同一段代码。
        //  StarsOnly: 保留且星级 ∈1..5 → 5 种; 废弃且星级 ==0 → 1 种; 每种 4 个起始色标
        //             ⇒ (5 + 1) × 4 = 24
        //  LabelsOnly: 保留且色标已是绿 → 7 种星级; 废弃且色标已是红 → 7 种 ⇒ 14
        //  Both: 保留: 星级 ∈1..5 且色标已是绿 ⇒ 5×1 = 5; 废弃: 星级 ==0 且色标已是红 ⇒ 1×1 = 1 ⇒ 6
        fun unchangedIn(mode: SwipeMode): Int = matrix.count { row ->
            row.mode == mode && decideSwipe(at(row.rating, row.label), row.action, mode).unchanged
        }
        assertEquals(24, unchangedIn(SwipeMode.StarsOnly))
        assertEquals(14, unchangedIn(SwipeMode.LabelsOnly))
        assertEquals(6, unchangedIn(SwipeMode.Both))
        assertEquals(
            "三档加起来必须小于总行数(否则这条循环根本没走到'要写'那一支)",
            44,
            unchangedIn(SwipeMode.StarsOnly) + unchangedIn(SwipeMode.LabelsOnly) + unchangedIn(SwipeMode.Both),
        )
    }

    // ── 方向 × 配置(手势语义是**可配置**的) ──────────────────────────────────

    @Test
    fun reversing_the_config_swaps_only_the_directions() {
        val reversed = SwipeConfig().reversed()
        assertEquals(SwipeAction.Keep, reversed.actionFor(SwipeDirection.Left))
        assertEquals(SwipeAction.Reject, reversed.actionFor(SwipeDirection.Right))
        assertEquals("互换方向不该动模式", SwipeMode.StarsOnly, reversed.mode)
        // 同一个起始状态, 互换之后"右滑"的结论必须等于互换之前"左滑"的结论
        assertEquals(
            decideSwipe(at(rating = 5), SwipeAction.Reject, SwipeMode.StarsOnly),
            decideSwipe(at(rating = 5), SwipeDirection.Right, reversed),
        )
    }

    @Test
    fun the_direction_overload_goes_through_the_same_mapping() {
        val current = at(rating = null)
        assertEquals(
            decideSwipe(current, SwipeAction.Keep, SwipeMode.StarsOnly),
            decideSwipe(current, SwipeDirection.Right, SwipeConfig()),
        )
        assertEquals(
            decideSwipe(current, SwipeAction.Reject, SwipeMode.Both),
            decideSwipe(current, SwipeDirection.Left, SwipeConfig(mode = SwipeMode.Both)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_config_that_maps_both_directions_to_the_same_action_is_rejected_loudly() {
        // 两侧同义 ⇒ 左滑右滑是同一件事, "取舍"就丢了。误配置必须当场炸, 不许安静退化。
        SwipeConfig(left = SwipeAction.Keep, right = SwipeAction.Keep)
    }

    @Test
    fun the_legend_and_names_say_what_each_direction_does() {
        // 配置可改就必须看得见 —— 这几句话直接画在滑卡页上。
        assertEquals("左滑 = 废弃(0★) · 右滑 = 保留(3★)", swipeLegend(SwipeConfig()))
        assertEquals("左滑 = 保留(3★) · 右滑 = 废弃(0★)", swipeLegend(SwipeConfig().reversed()))
        assertEquals("只写星级", modeName(SwipeMode.StarsOnly))
        assertEquals("只写色标", modeName(SwipeMode.LabelsOnly))
        assertEquals("星级+色标", modeName(SwipeMode.Both))
        assertEquals("保留(3★)", actionWord(SwipeAction.Keep))
        assertEquals("废弃(0★)", actionWord(SwipeAction.Reject))
    }

    // ── 手势算术(阈值 = 屏宽 25%, 松手才决策) ────────────────────────────────

    @Test
    fun the_threshold_is_a_quarter_of_the_screen_width() {
        assertEquals(0.25f, SWIPE_THRESHOLD_FRACTION)
        assertEquals(250f, swipeThresholdPx(1000f))
        assertEquals(0f, swipeThresholdPx(0f))
        assertEquals("屏宽算不出来时给 0 = 这一下不判决策, 而不是无限敏感", 0f, swipeThresholdPx(-5f))
    }

    @Test
    fun the_release_conclusion_needs_the_full_threshold_and_is_symmetric() {
        val t = swipeThresholdPx(1000f)   // 250
        assertEquals(SwipeDirection.Left, swipeDirectionFor(-250f, t))
        assertEquals(SwipeDirection.Right, swipeDirectionFor(250f, t))
        assertNull("差一点点就回弹(不做决策)", swipeDirectionFor(-249.9f, t))
        assertNull(swipeDirectionFor(249.9f, t))
        assertNull("手指几乎没动", swipeDirectionFor(2f, t))
        assertNull("阈值非法时绝不决策", swipeDirectionFor(-9000f, 0f))
        assertNull(swipeDirectionFor(9000f, -1f))
    }
}
