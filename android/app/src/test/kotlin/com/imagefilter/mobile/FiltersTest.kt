// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.PhotoExifDto
import com.imagefilter.mobile.data.ScannedPhotoDto
import com.imagefilter.mobile.ui.COUNT_UNKNOWN
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.Filters
import com.imagefilter.mobile.ui.GRID_EMPTY
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.LABELS
import com.imagefilter.mobile.ui.NOT_COUNTED
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.applyFilters
import com.imagefilter.mobile.ui.cellDescription
import com.imagefilter.mobile.ui.countText
import com.imagefilter.mobile.ui.filterCounts
import com.imagefilter.mobile.ui.filteredOutMessage
import com.imagefilter.mobile.ui.matchesFilters
import com.imagefilter.mobile.ui.shownCountLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 筛选与张数的**真行为**测试(纯函数, 与 Compose 无关)。
 *
 * 这里钉四件事, 每一件都对应一种真实会发生的坏结果:
 *   ① **未评分的照片不算命中任何一个星级下限** —— 否则"筛 ≥3 星"会把没看过的照片全带进来;
 *   ② 三个维度之间是**与**、色标内部是**或**(两条语义各有独立用例, 混了必然有一边是错的);
 *   ③ **张数在没有权威值时说"未统计", 不拿 0 / 不拿"已知的那些"顶上** —— 这是本任务
 *      点名的一条纪律("not measured" vs "0"), 而且它必须与浏览页用**同一个词**;
 *   ④ "有照片但全被筛掉"与"这个文件夹里没有照片"是**两句不同的话**。
 *
 * ⚠️ 断言里刻意把**所有可能的错值**都列出来(0 / 已知的那几张 / 整个文件夹的张数),
 * 而不是只跟一个对照比 —— 只比一个的话, 实现换一种错法就能骗过测试(task-2 报告里记的
 * 那次假绿就是这么来的)。
 */
class FiltersTest {

    private fun p(n: String) = ScannedPhotoDto(path = n, fileName = n, modifiedAt = 0, exif = PhotoExifDto())

    private fun item(n: String) = GridItem(
        path = n,
        fileName = n,
        isRaw = false,
        isVideo = false,
        modifiedAt = 0,
        thumbnailUrl = "http://192.168.1.5:8765/api/v1/thumb?path=$n&size=300&v=0",
    )

    private val photos = listOf(p("a.jpg"), p("b.jpg"), p("c.jpg"))

    private val decisions = mapOf(
        "a.jpg" to Decision("a.jpg", rating = 5, label = "green"),
        "b.jpg" to Decision("b.jpg", rating = 2, label = "red"),
        // c.jpg 没有任何决策 —— 这是关键边界
    )

    private fun names(r: List<ScannedPhotoDto>) = r.map { it.fileName }

    // ── ① 三个维度的语义 ────────────────────────────────────────────────────

    @Test
    fun no_filter_returns_everything() {
        assertEquals(3, applyFilters(photos, decisions, Filters()).size)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names(applyFilters(photos, decisions, Filters())))
    }

    @Test
    fun min_stars_excludes_unrated_photos() {
        // **未评分的照片不算命中** —— 与桌面端语义一致(桌面端 docs 4.2 明确写过这一点),
        // 否则"筛 ≥3 星"会把没看过的照片全带进来, 筛选就没意义了。
        val r = applyFilters(photos, decisions, Filters(minStars = 3))
        assertEquals(listOf("a.jpg"), names(r))
    }

    @Test
    fun a_photo_the_phone_knows_nothing_about_matches_no_rating_floor() {
        // c.jpg **没有决策** —— 它不是"0 星", 是"不知道"。任何 ≥1 的下限都不许把它带进来。
        for (n in 1..5) {
            assertFalse(
                "没有决策 = 不知道, 不是 0 星(下限 $n)",
                names(applyFilters(photos, decisions, Filters(minStars = n))).contains("c.jpg"),
            )
        }
    }

    @Test
    fun zero_stars_is_a_real_value_and_a_floor_of_one_excludes_it() {
        // 0 = 明确废弃(桌面端 X 键)。它**不是**"未评分", 但在"≥1 星"的条件下一律不命中 ——
        // 两者在 ≥1 时的结论相同, 而这条用例保证实现不是靠"null 和 0 混成一个默认值"蒙对的:
        // 0 星与未评分在"不按星级筛"时必须**都留下**(下面那条用例)。
        val d = mapOf("a.jpg" to Decision("a.jpg", rating = 0, sync = SyncState.SYNCED))
        assertEquals(emptyList<String>(), names(applyFilters(listOf(p("a.jpg")), d, Filters(minStars = 1))))
        assertEquals(1, applyFilters(listOf(p("a.jpg")), d, Filters()).size)
    }

    @Test
    fun label_filter_is_an_or_union() {
        assertEquals(2, applyFilters(photos, decisions, Filters(labels = setOf("green", "red"))).size)
        assertEquals(1, applyFilters(photos, decisions, Filters(labels = setOf("red"))).size)
        assertEquals(listOf("b.jpg"), names(applyFilters(photos, decisions, Filters(labels = setOf("red")))))
    }

    @Test
    fun a_photo_without_a_label_matches_no_label_filter() {
        // c.jpg 没有任何决策, b.jpg 的色标不在集合里 —— 两个都不许因为"没有标签"而漏进来。
        assertEquals(
            listOf("a.jpg"),
            names(applyFilters(photos, decisions, Filters(labels = setOf("green", "blue")))),
        )
    }

    @Test
    fun star_and_label_filters_combine_with_and() {
        val r = applyFilters(photos, decisions, Filters(minStars = 3, labels = setOf("red")))
        assertEquals(0, r.size)   // b 是红标但只有 2 星 → 两个条件同时满足的没有
    }

    @Test
    fun the_unsynced_dimension_keeps_only_what_the_phone_has_not_written_yet() {
        // 纪律③的第三个维度: 只看"这台手机改了但电脑还没确认"的那些。
        // 远端读到的值(UNTOUCHED)与已经写成的(SYNCED)**都不算** —— 把它们算进来,
        // 这个筛选立刻变成整页都是"未同步"。
        val d = mapOf(
            "a.jpg" to Decision("a.jpg", rating = 4, sync = SyncState.UNSYNCED),
            "b.jpg" to Decision("b.jpg", rating = 4, sync = SyncState.SYNCED),
            "c.jpg" to Decision("c.jpg", rating = 4, sync = SyncState.UNTOUCHED),
        )
        assertEquals(
            listOf("a.jpg"),
            names(applyFilters(photos, d, Filters(onlyUnsynced = true))),
        )
        assertEquals(3, applyFilters(photos, d, Filters()).size)
    }

    @Test
    fun all_three_dimensions_are_anded_together() {
        val d = mapOf(
            "a.jpg" to Decision("a.jpg", rating = 5, label = "green", sync = SyncState.UNSYNCED),
            "b.jpg" to Decision("b.jpg", rating = 5, label = "green", sync = SyncState.SYNCED),
            "c.jpg" to Decision("c.jpg", rating = 4, label = "green", sync = SyncState.UNSYNCED),
        )
        val f = Filters(minStars = 5, labels = setOf("green"), onlyUnsynced = true)
        assertEquals(listOf("a.jpg"), names(applyFilters(photos, d, f)))
    }

    @Test
    fun the_order_of_the_surviving_photos_is_the_servers_order() {
        val many = listOf(p("z.jpg"), p("a.jpg"), p("m.jpg"))
        val d = many.associate { it.path to Decision(it.path, rating = 3, sync = SyncState.SYNCED) }
        assertEquals(
            "筛选不许重排: 用户看到的顺序必须与电脑上一致",
            listOf("z.jpg", "a.jpg", "m.jpg"),
            names(applyFilters(many, d, Filters(minStars = 3))),
        )
    }

    @Test
    fun grid_items_can_be_filtered_without_turning_them_back_into_dtos() {
        // 网格手里是 GridItem(已经拼好缩略图 URL); 用同一个语义(四参数重载)筛它,
        // 结果必须与 DTO 那条路一致 —— 否则"筛选栏看到 3 张、网格画出 5 张"这种分裂就来了。
        val items = photos.map { item(it.path) }
        val viaItems = applyFilters(items, decisions, Filters(minStars = 3)) { it.path }
        assertEquals(listOf("a.jpg"), viaItems.map { it.fileName })
        assertEquals(
            names(applyFilters(photos, decisions, Filters(minStars = 3))),
            viaItems.map { it.fileName },
        )
    }

    @Test
    fun matches_filters_is_the_single_place_that_decides_a_single_photo() {
        // 纯函数被单独钉住: 一个格子与一整页用的是同一条判断(没有第二套实现)。
        assertTrue(matchesFilters(decisions["a.jpg"], Filters(minStars = 3)))
        assertFalse(matchesFilters(decisions["b.jpg"], Filters(minStars = 3)))
        assertFalse("没有决策就是不知道, 什么都不命中", matchesFilters(null, Filters(minStars = 1)))
        assertTrue(matchesFilters(null, Filters()))
    }

    // ── ② 条件本身的合法性(闭集 / 范围) ─────────────────────────────────────

    @Test
    fun the_label_set_is_the_desktop_five_and_a_sixth_colour_is_a_programming_error() {
        var threw = false
        try {
            Filters(labels = setOf("chartreuse"))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("闭集之外的颜色传进来只能是编程错误(与 Decision.withLabel 同一条)", threw)
        assertEquals(5, LABELS.size)
    }

    @Test
    fun the_star_floor_is_a_closed_range_too() {
        var threw = false
        try {
            Filters(minStars = 6)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(Filters(minStars = 0).isEmpty)
        assertFalse(Filters(minStars = 1).isEmpty)
    }

    // ── ③ 顶栏摘要 / "当前显示 N / 共 M 张" ──────────────────────────────────

    @Test
    fun the_summary_names_every_dimension_and_is_null_when_nothing_is_filtered() {
        assertNull("没有筛选时不许显示一句空话", Filters().summary)
        assertEquals("≥3★", Filters(minStars = 3).summary)
        assertEquals("仅未同步", Filters(onlyUnsynced = true).summary)
        // 色标按**闭集顺序**列(不是 Set 的迭代顺序): 同一个条件必须永远画出同一句话
        assertEquals("色标：红/绿", Filters(labels = setOf("green", "red")).summary)
        assertEquals(
            "≥3★ · 色标：红/紫 · 仅未同步",
            Filters(minStars = 3, labels = setOf("purple", "red"), onlyUnsynced = true).summary,
        )
    }

    @Test
    fun the_shown_line_says_how_many_of_the_folder_are_left() {
        assertEquals("当前显示 3 / 共 12 张", shownCountLabel(3, 12))
        assertEquals("当前显示 0 / 共 0 张", shownCountLabel(0, 0))
    }

    // ── ④ 张数: 不知道就说不知道 ────────────────────────────────────────────

    @Test
    fun the_unknown_count_uses_the_same_word_as_the_browse_page() {
        // 同一个"不知道"在两页上说成两个词, 用户会以为是两件事 —— 这条钉住它们必须是同一个词。
        assertEquals(NOT_COUNTED, COUNT_UNKNOWN)
        assertEquals(NOT_COUNTED, countText(null))
        assertEquals("7", countText(7))
        assertEquals("0", countText(0))
    }

    @Test
    fun known_counts_are_exact_for_every_dimension() {
        val d = decisions + mapOf(
            "c.jpg" to Decision("c.jpg", rating = 3, sync = SyncState.UNSYNCED),
        )
        val paths = photos.map { it.path }

        val counts = filterCounts(paths, d, decisionsKnown = true)

        assertEquals("3 张有 ≥2 星(a 5 星 / b 2 星 / c 3 星)", "3", counts.starText(2))
        assertEquals("2 张有 ≥3 星(a 5 星 / c 3 星)", "2", counts.starText(3))
        assertEquals("1 张有 ≥5 星(a)", "1", counts.starText(5))
        assertEquals("1 张绿标", "1", counts.labelText("green"))
        assertEquals("1 张红标", "1", counts.labelText("red"))
        assertEquals("0 张蓝标是真的 0(此时它是有权威值的)", "0", counts.labelText("blue"))
        assertEquals("未同步的张数只数 UNTOUCHED/UNSYNCED 里的 UNSYNCED", "1", counts.unsynced.toString())
    }

    @Test
    fun an_operational_count_of_zero_is_a_real_zero_not_the_unknown_word() {
        // 关键区别: **有权威值的 0** 就是 0(说成"未统计"才是撒谎), 而**没有权威值**才说"未统计"。
        // 这一条与下一条合起来把"两个 0"分得清清楚楚。
        val counts = filterCounts(listOf("a.jpg"), emptyMap(), decisionsKnown = true)
        assertEquals("0", counts.starText(3))
        assertEquals("0", counts.labelText("green"))
        assertEquals(0, counts.unsynced)
        assertNotEquals(COUNT_UNKNOWN, counts.starText(3))
    }

    @Test
    fun an_unrated_photo_is_not_counted_at_any_floor_not_even_at_one_star() {
        // "≥1★" 与"有星级"是同一件事: 未评分的照片**不算任何一档的命中**。
        // 数错这一个格子, 筛选栏会在一个几乎全是未评分照片的文件夹上写"≥1★ 200" ——
        // 而那正好是"没看过的照片"最多的那一档。
        val d = mapOf("a.jpg" to Decision("a.jpg", rating = 3, sync = SyncState.SYNCED))
        val counts = filterCounts(photos.map { it.path }, d, decisionsKnown = true)

        assertEquals("三张里只有 a 有星级", "1", counts.starText(1))
        assertEquals("1", counts.starText(3))
        assertEquals("0", counts.starText(4))
        // 0 星(明确废弃)同样不算"有星级"
        val zero = mapOf("a.jpg" to Decision("a.jpg", rating = 0, sync = SyncState.SYNCED))
        assertEquals("0", filterCounts(listOf("a.jpg"), zero, decisionsKnown = true).starText(1))
    }

    @Test
    fun unknown_counts_say_so_instead_of_guessing_a_number() {
        // 电脑上原有的星级/色标这次没读到 ⇒ 手机上数出来的只是**下界**。拿它当答案比"不知道"更坏:
        // 用户会照着"只有 1 张 4 星"去筛, 而电脑上可能还有 200 张。
        val d = mapOf("a.jpg" to Decision("a.jpg", rating = 4, sync = SyncState.SYNCED))
        val paths = photos.map { it.path }

        val counts = filterCounts(paths, d, decisionsKnown = false)

        assertEquals(COUNT_UNKNOWN, counts.starText(4))
        // 把所有"可能被错误地数出来"的数都列出来 —— 拿 0 顶上 / 只数已知的那 1 张 / 数整个文件夹。
        for (wrong in listOf("0", "1", "2", "3")) {
            assertNotEquals("未统计不许变成任何一个数(至少不是 $wrong)", wrong, counts.starText(4))
        }
        assertEquals(COUNT_UNKNOWN, counts.labelText("green"))
        assertEquals(COUNT_UNKNOWN, counts.starText(1))
    }

    @Test
    fun the_unsynced_count_is_always_known_even_when_the_read_failed() {
        // "这台手机改了但没写成"是**纯本地**的事实, 与远端读没读到无关 —— 它永远是一个确定的数,
        // 而且这个数在读取失败时**照旧**要能看见(否则用户的离线评分就静默了)。
        val d = mapOf(
            "a.jpg" to Decision("a.jpg", rating = 4, sync = SyncState.UNSYNCED),
            "b.jpg" to Decision("b.jpg", rating = 2, sync = SyncState.UNSYNCED),
        )
        val counts = filterCounts(photos.map { it.path }, d, decisionsKnown = false)
        assertEquals(2, counts.unsynced)
        assertEquals("2", counts.unsynced.toString())
    }

    @Test
    fun a_photo_whose_decision_is_only_on_the_pc_is_not_counted_as_unsynced() {
        // 远端读到的那一条 sync 是 UNTOUCHED —— 它绝不许混进"未同步"这个角标里
        // (混了的话整个文件夹都会显示成未同步, 纪律③要的那条可见性立刻变成一句假话)。
        val d = mapOf("a.jpg" to Decision("a.jpg", rating = 5, sync = SyncState.UNTOUCHED))
        assertEquals(0, filterCounts(photos.map { it.path }, d, decisionsKnown = true).unsynced)
    }

    // ── ⑤ 空网格的两种说法 ──────────────────────────────────────────────────

    @Test
    fun a_filtered_out_folder_says_so_and_never_claims_the_photos_are_gone() {
        val msg = filteredOutMessage(total = 12)
        assertNotEquals("有照片但被筛掉 ≠ 没有照片", GRID_EMPTY, msg)
        assertTrue("要说清总共几张, 用户才知道被筛掉了多少: $msg", msg.contains("12"))
        assertTrue("要给出下一步: $msg", msg.contains("清除筛选"))
        assertFalse("不许说成'文件夹里没有照片'", msg.contains("返回 0 张"))
    }

    // ── ⑥ 角标的无障碍描述 ─────────────────────────────────────────────────

    @Test
    fun the_cell_description_carries_the_rating_the_colour_and_the_unsynced_marker() {
        val cell = item("a.jpg")
        assertEquals("没有决策时只说文件名", "a.jpg", cellDescription(cell, null, ratingKnown = true))
        assertEquals(
            "星/色标/未同步都要能被读出来(边框对读屏完全不可见)",
            "a.jpg，4 星，红色标，未同步",
            cellDescription(
                cell,
                Decision("a.jpg", rating = 4, label = "red", sync = SyncState.UNSYNCED),
                ratingKnown = true,
            ),
        )
        assertEquals(
            "0 星是桌面端的'废弃', 读屏要说人话",
            "a.jpg，已废弃",
            cellDescription(cell, Decision("a.jpg", rating = 0, sync = SyncState.SYNCED), ratingKnown = true),
        )
        assertEquals(
            "已同步/远端读到的项不许读出'未同步'",
            "a.jpg，5 星，绿色标",
            cellDescription(
                cell,
                Decision("a.jpg", rating = 5, label = "green", sync = SyncState.UNTOUCHED),
                ratingKnown = true,
            ),
        )
    }

    @Test
    fun an_unknown_desktop_rating_reads_differently_from_a_confirmed_empty_one() {
        // ⚠️ 这一条钉的是本轮修的那个"看不见的区别": 一次失败的读取(不知道)与"电脑上确实没有星级"
        // 在读屏里**必须分得开** —— 否则一张电脑上 4/5 星的照片会读起来像"没评过", 而用户在手机上
        // 的一次打分(1..5 星)是一次**绝对赋值**, 会把它静默覆盖。
        val cell = item("a.jpg")
        val unrated = Decision("a.jpg", rating = null, desktopRating = DesktopRating.Unrated)
        val unknown = Decision("a.jpg", rating = null, desktopRating = DesktopRating.Unknown)

        val knownText = cellDescription(cell, unrated, ratingKnown = true)
        val unknownText = cellDescription(cell, unknown, ratingKnown = false)

        assertEquals("确知没有星级: 屏幕上没有星级可显示, 读屏也不额外说什么", "a.jpg", knownText)
        assertEquals("不知道: 必须读得出来", "a.jpg，电脑上的星级没读到", unknownText)
        assertNotEquals("'不知道'与'确实没有'绝不许说成同一句话", knownText, unknownText)
        // 反过来也要钉住: 有值可显示时, 那条"没读到"的说明**不许**冒出来(vacuous 的假绿最容易
        // 出在这里: 只测一面, 另一面永远绿)。
        assertEquals(
            "有值的时候不许混进'没读到'",
            "a.jpg，4 星",
            cellDescription(cell, Decision("a.jpg", rating = 4, desktopRating = DesktopRating.Rated(4)), ratingKnown = true),
        )
    }
}
