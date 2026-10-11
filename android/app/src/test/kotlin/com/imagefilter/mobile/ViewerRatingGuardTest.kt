// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.ViewerViewModel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **"这台手机不知道电脑上是几星"时, 一次点击不许静默覆盖**(纯规则在 [RatingKnowledgeTest])。
 *
 * 为什么这是一条数据损失而不是体验问题: `1..5` 星是**绝对赋值**, 而 [ViewerState.rating] 在读取
 * 失败时是 `null` —— 于是"一次读失败"把一张电脑上 4/5 星的照片画成一排空星星, 用户在空星星上点
 * 第 3 颗, 写下去的就是"这张 3 星"。电脑上那颗 4/5 星**静默消失**, 界面上没有任何异常
 * (这正是本项目最贵的那一类失败)。
 *
 * 本文件钉的是**执法点**([ViewerViewModel.setRating]), 因为它是唯一一处能把"点了之后到底
 * 发没发请求"变成可断言事实的地方(Compose 那层在本项目里**没有**测试依赖):
 *   · **不知道**: 第一下只武装(一个请求都不发、本地一个字节都不改、不进撤销栈), 再点同一颗星
 *     = 明确覆盖才真的写;
 *   · **确知没有星级**: 照旧一次点击就写(对照 —— 否则上面那条可能只是因为"这个状态机根本不会写"
 *     而通过);
 *   · **0 星(废弃)**: 明确的取舍, 任何一档都不拦(与左滑同一条规矩: 允许降级)。
 *
 * ⚠️ 测不到的: 星星的**真实观感**、那个"★?"未知角标与拦截说明在真机上的可读性(没有设备)。
 */
class ViewerRatingGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "g".repeat(64)

    private val folder = "\\\\?\\D:\\照片 2026"
    private val paths = (1..4).map { "$folder\\p$it.jpg" }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun item(index: Int) = GridItem(
        path = paths[index],
        fileName = "p${index + 1}.jpg",
        isRaw = false,
        isVideo = false,
        modifiedAt = 1_759_680_000_000L + index,
        thumbnailUrl = "http://pc/api/v1/thumb?path=x&size=300&v=0",
    )

    private fun items(count: Int = 4) = (0 until count).map { item(it) }

    /**
     * 这份 store 装的是"这台手机**知道**什么"。
     *
     * `seed` 为空 = 从来没读过这个文件夹(读取失败 / 还没读) = **不知道**;
     * 传 `DecisionReadDto(path = …)` = 服务端明确回报了"这一张没有星级" = **[DesktopRating.Unrated]**。
     * 两者的差别正是本文件要钉住的那个区别。
     */
    private fun store(seed: List<DecisionReadDto> = emptyList()): DecisionStore =
        DecisionStore(ApiClient(base, token, buildHttpClient(token)))
            .also { if (seed.isNotEmpty()) it.mergeRemote(seed) }

    private fun viewModel(decisions: DecisionStore): ViewerViewModel = ViewerViewModel(
        pairing = Pairing(base, token, note = "家里的台式机"),
        items = items(),
        startIndex = 0,
        dragThresholdPx = 160f,
        prefetch = {},
        decisions = decisions,
    )

    private fun enqueue(written: Int = 1) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"written":$written,"skipped":0,"failed":0,"failures":[],"degraded":false}""",
            ),
        )
    }

    // ── ① 不知道: 一次点击**一个字节都不写** ────────────────────────────────

    @Test
    fun a_single_tap_never_writes_over_a_pc_value_the_phone_does_not_know() = runTest {
        val vm = viewModel(store())   // 没读过 ⇒ Unknown

        val first = vm.setRating(3)

        assertEquals("一个请求都不发出去", 0, server.requestCount)
        assertEquals(PushResult.NothingToPush(1), first)
        assertNull("本地也不许写 —— 那会让'未同步'角标凭空多一张", vm.state.value.rating)
        assertEquals(SyncState.UNTOUCHED, vm.state.value.sync)
        assertEquals("也不许凭空占一步撤销", 0, vm.state.value.undoCount)
        assertEquals(DesktopRating.Unknown, vm.state.value.desktopRating)
        val note = vm.state.value.ratingGuardNote!!
        assertTrue("必须说清这一下没有写入: $note", note.contains("没有写入"))
        assertEquals("武装的是用户点的**那颗**星", 3, vm.state.value.blindRatingArmed)
    }

    @Test
    fun tapping_the_same_star_again_is_the_explicit_override_and_only_then_writes() = runTest {
        val vm = viewModel(store())
        vm.setRating(3)
        enqueue(written = 1)

        val second = vm.setRating(3)

        assertEquals(PushResult.Ok(1, 1, 0, false), second)
        assertEquals("只发了这一次写入", 1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("写下去的确实是那 3 星: $body", body.contains("\"rating\":3"))
        assertEquals(3, vm.state.value.rating)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertNull("写下去之后那句拦截说明不再适用", vm.state.value.ratingGuardNote)
        assertNull("武装也清掉", vm.state.value.blindRatingArmed)
    }

    @Test
    fun tapping_a_different_star_re_arms_instead_of_confirming_the_previous_one() = runTest {
        // ⚠️ 这一条防的是"把两次不同的点击凑成一次确认": 3 星被拦下之后点 4 星, 若算确认,
        // 用户就会在**没见过**那句话讲的是 4 星的情况下把 4 星写下去。
        val vm = viewModel(store())
        vm.setRating(3)

        vm.setRating(4)

        assertEquals("换一颗星 = 重新武装", 4, vm.state.value.blindRatingArmed)
        assertEquals("第二个请求也不许发", 0, server.requestCount)
        assertNull(vm.state.value.rating)
        assertTrue(vm.state.value.ratingGuardNote!!, vm.state.value.ratingGuardNote!!.contains("4 星"))
    }

    @Test
    fun changing_the_photo_clears_the_armed_star_so_it_cannot_confirm_the_wrong_photo() = runTest {
        // ⚠️ 武装状态**必须跟着照片走**: 不换张的话, 用户在第一张上的"再点一次"会在第二张上生效 ——
        // 那是拿一次点击去覆盖一张他从没看过电脑值的照片。
        val vm = viewModel(store())
        vm.setRating(3)
        assertEquals(3, vm.state.value.blindRatingArmed)

        vm.go(+1)

        assertNull("换了照片 ⇒ 武装清掉", vm.state.value.blindRatingArmed)
        assertNull("那句说明也清掉(它讲的是上一张)", vm.state.value.ratingGuardNote)
        assertEquals("第二张同样是未知", PushResult.NothingToPush(1), vm.setRating(3))
        assertEquals(0, server.requestCount)
    }

    // ── ② 对照: 确知"这一张没有星级"时照旧一次点击就写 ──────────────────────

    @Test
    fun a_tap_on_a_photo_the_pc_confirmed_as_unrated_still_writes_in_one_tap() = runTest {
        // **对照**: 同一套配置、同一次点击, 只差"这台手机知不知道"。于是上面那些用例不是
        // "这个状态机根本不会写"的空话(本项目撞过的假绿形状之一)。
        enqueue(written = 1)
        val vm = viewModel(store(listOf(DecisionReadDto(path = paths[0]))))
        assertEquals("写之前: 电脑上确实没有星级", DesktopRating.Unrated, vm.state.value.desktopRating)

        val r = vm.setRating(3)

        assertEquals(PushResult.Ok(1, 1, 0, false), r)
        assertEquals(1, server.requestCount)
        assertNull("没有被拦下 ⇒ 没有那句说明", vm.state.value.ratingGuardNote)
        assertNull("也没有武装", vm.state.value.blindRatingArmed)
        assertEquals(3, vm.state.value.rating)
        // FIX 3: 写**之后**电脑上现在就是我们刚写进去的 3 星(服务端确认了), 所以这一格
        // 不再是"确知没有星级" —— 也不是"上次读到"。
        assertEquals(DesktopRating.Rated(3), vm.state.value.desktopRating)
        assertTrue("而且它自称是刚写进去的那份", vm.state.value.desktopRatingFromWrite)
    }

    @Test
    fun a_tap_on_a_photo_whose_pc_value_is_known_also_writes_in_one_tap() = runTest {
        // 另一档"知道": 电脑上是 5 星。写 3 星是**用户明确的降级**(不是瞎写), 所以不拦。
        enqueue(written = 1)
        val vm = viewModel(store(listOf(DecisionReadDto(path = paths[0], rating = 5))))
        assertEquals("写之前: 上一次读到的是 5 星", DesktopRating.Rated(5), vm.state.value.desktopRating)

        val r = vm.setRating(3)

        assertEquals(PushResult.Ok(1, 1, 0, false), r)
        assertEquals(3, vm.state.value.rating)
        // FIX 3: 确认写成之后, "电脑上是几星"跟着这一次写入走(3), 而不是留着旧读数的 5 ——
        // 否则用户看到星星是 3、下面写着 5, 只能以为这次写入丢了。
        assertEquals(DesktopRating.Rated(3), vm.state.value.desktopRating)
        assertTrue(vm.state.value.desktopRatingFromWrite)
    }

    // ── ③ 0 星(废弃)是明确的取舍: 任何一档都不拦 ────────────────────────────

    @Test
    fun zero_stars_is_an_explicit_reject_and_is_never_blocked_even_when_unknown() = runTest {
        // 与左滑同一条规矩(允许降级): 把"我不要这张"也拦下来, 等于把用户唯一确定的意图挡在门外。
        enqueue(written = 1)
        val vm = viewModel(store())

        val r = vm.setRating(0)

        assertEquals(PushResult.Ok(1, 1, 0, false), r)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("0 = 废弃必须真的写出去: $body", body.contains("\"rating\":0"))
        assertEquals(0, vm.state.value.rating)
        assertNull("没有被拦下 ⇒ 没有那句说明", vm.state.value.ratingGuardNote)
    }

    // ── ④ 界面状态那一份知识本身 ─────────────────────────────────────────────

    @Test
    fun the_viewer_state_carries_the_pc_knowledge_and_refreshes_it_on_every_photo() = runTest {
        // 界面画的"未知角标"与 `setRating` 的拦截都读**同一格**状态([ViewerState.desktopRating]):
        // 两处各算一遍的话, 迟早出现"画着未知、却能一次点下去"这种分裂。
        val store = store(
            listOf(
                DecisionReadDto(path = paths[0], rating = 5),
                DecisionReadDto(path = paths[1]),
                DecisionReadDto(path = paths[2], rating = 0),
            ),
        )
        val vm = viewModel(store)

        assertEquals(DesktopRating.Rated(5), vm.state.value.desktopRating)
        vm.go(+1)
        assertEquals("确知没有星级", DesktopRating.Unrated, vm.state.value.desktopRating)
        vm.go(+1)
        assertEquals("Rated(0) 也是**知道**(读到的是 0, 不是读不到)", DesktopRating.Rated(0), vm.state.value.desktopRating)
        vm.go(+1)
        assertEquals("没落在这份回报里的那一张 = 不知道", DesktopRating.Unknown, vm.state.value.desktopRating)
        // 而"不知道"这一档**确实**被拦下(同一格状态驱动的两件事: 画什么 + 拦不拦)。
        assertEquals(PushResult.NothingToPush(1), vm.setRating(3))
        assertEquals(0, server.requestCount)
    }
}
