// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.WriteAction
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.ViewerViewModel
import com.imagefilter.mobile.ui.labelAfterTap
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 查看器里的**打分/标色 → 写回电脑**这条链路(本任务的核心)。
 *
 * 这里钉的是六件事, 每一件都对应一次真实的坏结果:
 *   ① **乐观更新**: 点一下界面立刻变(不等网络);
 *   ② **只有服务端能说"写成了"**: 200 + 逐项失败**绝不**报成成功, 而是逐项显示、各带各的码;
 *   ③ **失败不回滚**: 本地分数留着, 只是标"未同步"; 而且**可重试**(重发同一份意图);
 *   ④ **失败不前进**: 写不成绝不自动跳到下一张(那会让用户以为打过了);
 *      这一条之所以不是空话, 是因为同一份测试里"成功会前进"的对照**真的红过**(见变异 B);
 *   ⑤ **未同步可见**: 当前这张、以及跨张的计数都要看得见(纪律③);
 *   ⑥ **未知的逐项码安全降级**: 不崩、不猜, 原样留着码并给一句有动作的话。
 *
 * ⚠️ 测不到的: 星星/色标的**真实观感**与点击命中区(没有设备, 也不为 M2′ 引 Compose UI 测试依赖)。
 *
 * ⚠️ 夹具的前提: 这一组用例的 store **都读过这个文件夹**([store]), 所以"电脑上这一张没有星级"
 * 是**知道**的 —— 点击才会一次就写。没读过的那种状态(不知道)下, 一次 1..5 星会被拦下,
 * 那条链路在 [ViewerRatingGuardTest] 里单独钉。
 */
class ViewerDecisionTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "w".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有中文与反斜杠。 */
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

    private fun jsonPath(p: String) = p.replace("\\", "\\\\")

    private fun failJson(p: String, code: String) =
        """{"path":"${jsonPath(p)}","code":"$code","detail":"诊断用的中文"}"""

    private fun enqueueSummary(written: Int = 0, skipped: Int = 0, failed: Int = 0, failures: String = "[]") {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"written":$written,"skipped":$skipped,"failed":$failed,"failures":$failures,"degraded":false}""",
            ),
        )
    }

    /**
     * 查看器借用的那份 store。
     *
     * ⚠️ **生产路径上它已经读过这个文件夹**([GridViewModel.loadAll] 先读决策再进查看器), 所以
     * "这台手机**知道**电脑上这一张没有星级"([DesktopRating.Unrated]) —— 一次点击因此可以直接写。
     * 少了这个种子, 夹具描述的是"读取失败/还没读"那种状态: 那时的一次 1..5 星会被**拦下**
     * (那正是本轮修的"绝不静默覆盖一个没看见过的值"), 这一组用例测的就不是写入链路了。
     * 拦下那条链路的用例在 [ViewerRatingGuardTest]。
     */
    private fun store(): DecisionStore =
        DecisionStore(ApiClient(base, token, buildHttpClient(token)))
            .also { it.mergeRemote(paths.map { p -> DecisionReadDto(path = p) }) }

    /**
     * [advanceOnSuccess] 默认 false = M2′ 的查看器(手动切图); true = M3′ 滑卡决策页的规则。
     * [decisions] 可以注入 —— 用来验证"store 活得比查看器久"这条(见下面那条用例)。
     */
    private fun viewModel(
        startIndex: Int = 0,
        advanceOnSuccess: Boolean = false,
        decisions: DecisionStore? = null,
    ): ViewerViewModel = ViewerViewModel(
        pairing = Pairing(base, token, note = "家里的台式机"),
        items = items(),
        startIndex = startIndex,
        dragThresholdPx = 160f,
        prefetch = {},
        decisions = decisions ?: store(),
        advanceOnWriteSuccess = advanceOnSuccess,
    )

    // ── ① 乐观更新 + 服务端确认 ─────────────────────────────────────────────

    @Test
    fun a_tap_reaches_the_server_and_only_the_server_confirms_it() = runTest {
        enqueueSummary(written = 1)
        val vm = viewModel()

        val result = vm.setRating(4)

        assertEquals(PushResult.Ok(requested = 1, written = 1, skipped = 0, degraded = false), result)
        val st = vm.state.value
        assertEquals(4, st.rating)
        assertEquals(SyncState.SYNCED, st.sync)
        assertNull(st.writeFailure)
        assertFalse(st.hasUnsyncedChange)
        assertEquals(0, st.unsyncedCount)
        assertFalse("写完就不再是'正在写'", st.writing)
        // 发出去的确实是这一张、这个分数(URL 里没有一处是界面自己拼的)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":4"))
    }

    @Test
    fun a_200_with_a_per_item_failure_is_not_reported_as_success() = runTest {
        // WriteSummary 是"HTTP 成了, 但这一项没写成"。把它当成功 = 用户以为分数在电脑上了。
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "readOnly")}]")
        val vm = viewModel()

        val result = vm.setRating(5)

        val failed = result as PushResult.Failed
        assertEquals("readOnly", failed.failures.single().code)
        assertEquals(FailureSource.XMP, failed.failures.single().source)

        val st = vm.state.value
        assertEquals("乐观更新失败也不回滚: 分数还在", 5, st.rating)
        assertEquals(SyncState.UNSYNCED, st.sync)
        assertTrue(st.hasUnsyncedChange)
        assertEquals("readOnly", st.writeFailure?.code)
        assertEquals(1, st.unsyncedCount)
        assertNotNull("标题/正文/动作都查 WriteMessages, 界面一个字都不拼", st.writeText)
        assertNotEquals("必须查 XMP 表, 不是 HTTP 表", WriteMessages.xmpGeneric("readOnly"), st.writeText)
        assertTrue(st.writeText!!, st.writeText!!.contains("只读"))
        assertEquals(WriteAction.PC_SIDE, st.writeAction)
        assertEquals("知道了（要去电脑上改）", st.writeActionLabel)
        assertNotNull(st.unsyncedNote)
        assertEquals("1 张未同步·重试", st.unsyncedLabel)
    }

    @Test
    fun an_unknown_per_item_code_degrades_safely_and_stays_retryable() = runTest {
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "brandNewReason")}]")
        val vm = viewModel()

        vm.setRating(2)   // 不抛异常就是第一层"安全降级"

        val st = vm.state.value
        assertEquals("brandNewReason", st.writeFailure?.code)
        assertEquals(WriteMessages.xmpGeneric("brandNewReason"), st.writeText)
        assertEquals("认不出原因时, 重试是唯一无害的动作", WriteAction.RETRY, st.writeAction)
        assertEquals(2, st.rating)
        assertEquals(SyncState.UNSYNCED, st.sync)
    }

    // ── ② 失败不前进(且有非空的对照) ───────────────────────────────────────

    @Test
    fun a_failed_write_never_advances_to_the_next_photo() = runTest {
        // 这正是"以为打过了"的源头: 写没成却滑到下一张, 用户再也不会回来补这一次。
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "busy")}]")
        val vm = viewModel(advanceOnSuccess = true)

        vm.setRating(4)

        assertEquals("写失败绝不前进", 0, vm.state.value.index)
        assertEquals("这张的分数与失败提示都留在原地", 4, vm.state.value.rating)
        assertNotNull(vm.state.value.writeFailure)
        assertEquals(WriteAction.RETRY, vm.state.value.writeAction)
    }

    @Test
    fun a_confirmed_write_does_advance_when_the_rule_is_on() = runTest {
        // 上面那条用例的**对照**: 同一套配置下成功**会**前进 —— 于是"失败不前进"不是
        // 一句永远成立的空话(否则它可能只是因为"这个状态机根本不会前进"而通过)。
        enqueueSummary(written = 1)
        val vm = viewModel(advanceOnSuccess = true)

        vm.setRating(4)

        assertEquals(1, vm.state.value.index)
        assertNull("下一张还没打过分", vm.state.value.rating)
        assertEquals(SyncState.UNTOUCHED, vm.state.value.sync)
    }

    @Test
    fun the_m2_viewer_never_advances_on_its_own_even_when_the_write_succeeds() = runTest {
        // M2′ 的查看器是"看大图 + 手动切图": 打完分把人从正在看的图上踢走是坏事。
        // (M3′ 的滑卡决策页才会传 advanceOnWriteSuccess = true。)
        enqueueSummary(written = 1)
        val vm = viewModel(advanceOnSuccess = false)

        vm.setRating(4)

        assertEquals(0, vm.state.value.index)
        assertEquals(4, vm.state.value.rating)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
    }

    // ── ③ 失败保留 + 可重试 ─────────────────────────────────────────────────

    @Test
    fun a_network_failure_keeps_the_local_stars_and_offers_a_retry() = runTest {
        val vm = viewModel()
        server.shutdown()   // 电脑睡了 / 服务没开: 根本没有进程在监听

        val result = vm.setRating(3)

        val failed = result as PushResult.Failed
        assertEquals(FailureSource.CLIENT, failed.failures.single().source)
        assertEquals(ApiErrorCodes.SERVICE_OFF, failed.failures.single().code)
        val st = vm.state.value
        assertEquals("分数留在手机上", 3, st.rating)
        assertEquals(SyncState.UNSYNCED, st.sync)
        assertEquals(WriteAction.RETRY, st.writeAction)
        assertEquals("重试写入", st.writeActionLabel)
        assertEquals(1, st.unsyncedCount)
        assertTrue(st.writeText!!, st.writeText!!.contains("没连上电脑"))
    }

    @Test
    fun the_retry_resends_the_same_intent_and_then_settles() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        enqueueSummary(written = 1)
        val vm = viewModel()

        assertTrue(vm.setRating(4) is PushResult.Failed)
        assertEquals(SyncState.UNSYNCED, vm.state.value.sync)
        assertEquals(1, vm.state.value.unsyncedCount)

        val ok = vm.retryCurrent()

        assertEquals(PushResult.Ok(1, 1, 0, false), ok)
        val st = vm.state.value
        assertEquals(SyncState.SYNCED, st.sync)
        assertNull(st.writeFailure)
        assertEquals(0, st.unsyncedCount)
        assertEquals(4, st.rating)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first, first.contains("\"rating\":4"))
        assertEquals("重试重发的是**同一份意图**, 不是一次空请求", first, second)
    }

    @Test
    fun dismissing_the_failure_keeps_the_change_and_the_unsynced_mark() = runTest {
        // "知道了"只收起那句话 —— 绝不假装写成了(那是纪律③里最坏的谎话)。
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "busy")}]")
        val vm = viewModel()
        vm.setRating(4)

        vm.dismissWriteFailure()

        val st = vm.state.value
        assertNull(st.writeFailure)
        assertEquals(SyncState.UNSYNCED, st.sync)
        assertEquals(4, st.rating)
        assertEquals(1, st.unsyncedCount)
        assertNotNull("收起提示不等于已同步", st.unsyncedLabel)
    }

    // ── ④ 未同步可见(当前这张 + 跨张) ─────────────────────────────────────

    @Test
    fun the_unsynced_mark_spans_photos_and_comes_back_with_the_photo() = runTest {
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "permission")}]") // p1 没写成
        enqueueSummary(written = 1)                                                   // p2 写成了
        val vm = viewModel()

        vm.setRating(5)
        vm.go(+1)
        vm.setRating(3)

        assertEquals(1, vm.state.value.unsyncedCount)
        assertEquals("1 张未同步·重试", vm.state.value.unsyncedLabel)
        assertEquals(3, vm.state.value.rating)
        assertNull(vm.state.value.writeFailure)

        vm.go(-1)

        assertEquals("回到没写成的那张: 分数与那条失败都还在", 5, vm.state.value.rating)
        assertEquals("permission", vm.state.value.writeFailure?.code)
        assertTrue(vm.state.value.hasUnsyncedChange)
    }

    @Test
    fun a_rebuilt_view_model_on_the_same_store_still_knows_what_is_unsynced() = runTest {
        // 这条钉的是**寿命**: store 必须活得比查看器久(生产上由 GridScreen 持有)。
        // 建在查看器里的话, 用户一关掉查看器, "哪张改了没写成"就随它一起消失 —— 静默。
        val store = store()
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "diskFull")}]")
        viewModel(decisions = store).setRating(5)

        // 关掉查看器, 再打开(网格持有的是同一份 store)
        val reopened = viewModel(decisions = store)

        assertEquals(5, reopened.state.value.rating)
        assertEquals(SyncState.UNSYNCED, reopened.state.value.sync)
        assertEquals(1, reopened.state.value.unsyncedCount)
        assertEquals("diskFull", reopened.state.value.writeFailure?.code)
    }

    @Test
    fun a_partial_batch_retry_says_how_many_of_the_batch_failed() = runTest {
        // 一批 3 张成了 1 张: 当前这张**恰好是成的那张**时, 光看它的失败卡会以为全成了 ——
        // 所以批次级的结论必须单独说出来(纪律②: 半成功态显式化)。
        repeat(3) { enqueueSummary(failed = 1, failures = "[${failJson(paths[it], "busy")}]") }
        val vm = viewModel()
        vm.setRating(5)
        vm.go(+1)
        vm.setRating(4)
        vm.go(+1)
        vm.setRating(1)
        assertEquals(3, vm.state.value.unsyncedCount)

        enqueueSummary(
            written = 1,
            failed = 2,
            failures = "[${failJson(paths[1], "readOnly")},${failJson(paths[2], "readOnly")}]",
        )
        val result = vm.retryAllUnsynced()

        val failed = result as PushResult.Failed
        assertEquals(3, failed.requested)
        assertEquals("成了几张也要如实说", 1, failed.written)
        assertEquals(2, failed.failures.size)
        assertEquals(2, vm.state.value.unsyncedCount)
        assertEquals("这一批 3 张里有 2 张没写进电脑。", vm.state.value.lastBatchNote)
    }

    @Test
    fun retrying_an_untouched_photo_sends_nothing() = runTest {
        val vm = viewModel()

        val result = vm.retryCurrent()

        assertEquals(PushResult.NothingToPush(requested = 1), result)
        assertEquals("没有本地改动就不发请求 —— 也就没有服务端的确认可说", 0, server.requestCount)
        assertNull(vm.state.value.writeFailure)
    }

    // ── ⑤ 线上语义: 0 星 / 清标签 ──────────────────────────────────────────

    @Test
    fun zero_stars_travels_as_a_real_rating() = runTest {
        // 0 = 废弃。客户端把它当成 null(不动)丢掉的话, 这个操作会**静默失败**。
        enqueueSummary(written = 1)
        val vm = viewModel()

        vm.setRating(0)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":0"))
        assertEquals(0, vm.state.value.rating)
    }

    @Test
    fun tapping_the_current_colour_clears_the_label_on_the_wire() = runTest {
        enqueueSummary(written = 1)
        enqueueSummary(written = 1)
        val vm = viewModel()

        vm.setLabel("green")
        val set = server.takeRequest().body.readUtf8()
        assertTrue(set, set.contains("\"label\":\"green\""))
        assertFalse("设为某个颜色时不许带 clearLabel", set.contains("\"clearLabel\""))

        // 界面上的"再点一次当前颜色 = 清掉"(纯函数给出结论, 界面不自己判)
        vm.setLabel(labelAfterTap("green", "green"))
        val cleared = server.takeRequest().body.readUtf8()
        assertTrue("清标签必须显式发 clearLabel=true(否则旧标签会复活)", cleared.contains("\"clearLabel\":true"))
        assertFalse("清标签时不许带 label", cleared.contains("\"label\""))
        assertNull(vm.state.value.label)
    }
}
