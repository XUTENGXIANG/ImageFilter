// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.KEEP_BLIND_MESSAGE
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SwipeDirection
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.UndoEntry
import com.imagefilter.mobile.ui.UndoResult
import com.imagefilter.mobile.ui.ViewerViewModel
import com.imagefilter.mobile.ui.swipeViewModel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **撤销那条链路**(M3′ Task 2)的真行为测试: JVM + MockWebServer, 走真的 [ApiClient] 与真的
 * [DecisionStore]; 界面层那几条用**生产同一份工厂**([swipeViewModel])起步。
 *
 * 三条不变量, 每条都对应一种"看起来能用、其实在撒谎"的实现:
 *
 *   ① **恢复的是"之前那份本地意图"** —— 电脑上**从没收到过**那次改动时, 撤销只把本地意图恢复
 *      回去, 而且**一个请求都不发**(发一次反向写入就是一次"服务端没被请求过的写入"), 也不留
 *      幻影"未同步"条目; 电脑上**确实收到过**时, 必须把改动前的值送回去 —— 否则界面显示的值
 *      与电脑上的值会长期不一致(那正是"撤销"这件事的全部意义);
 *   ② **不许拿远程快照冒充"之前那份意图"**: [UndoEntry.before] 是远端读回来的值(当时这台手机
 *      没动过它)时, 恢复的是"没动过它", 不是"把读到的那个值当成一条新意图";
 *   ③ **写没写成只由服务端说** —— 撤销失败时绝不说"已撤销", 本地值留着、标未同步、带码可重试
 *      (与打分那条链路逐字同一条纪律③)。
 *
 * ⚠️ 测不到的: 撤销按钮的**点击命中区与观感**(没有设备, 也不为本任务引 Compose UI 测试依赖)。
 */
class UndoTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "u".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格与中文。 */
    private val folderPath = "\\\\?\\D:\\照片 2026"
    private val paths = (1..4).map { "$folderPath\\p$it.jpg" }

    private val pairing get() = Pairing(base, token, note = "家里的台式机")

    private val factory: (String, String) -> ApiClient = { url, tkn ->
        ApiClient(url, tkn, buildHttpClient(tkn))
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun item(index: Int) = GridItem(
        path = paths[index],
        fileName = "p${index + 1}.jpg",
        isRaw = false,
        isVideo = false,
        modifiedAt = 1_759_680_000_000L + index,
        thumbnailUrl = "http://pc/api/v1/thumb?path=x&size=300&v=0",
    )

    private fun items(count: Int = 4) = (0 until count).map { item(it) }

    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

    private fun failJson(p: String, code: String) =
        """{"path":${jq(p)},"code":"$code","detail":"诊断用的中文"}"""

    /** `xmp.rs::DecisionRead` 的一项, 逐字按线上拼写写 JSON(不拿 DTO 反向编码)。 */
    private fun decision(path: String, rating: Int? = null, label: String? = null) =
        """{"path":${jq(path)},"rating":${rating ?: "null"},""" +
            """"label":${if (label == null) "null" else jq(label)},"sidecar":null}"""

    private fun enqueueRead(vararg items: String) = server.enqueue(
        MockResponse().setResponseCode(200).setBody("[${items.joinToString(",")}]"),
    )

    private fun enqueueSummary(
        written: Int = 0,
        skipped: Int = 0,
        failed: Int = 0,
        failures: String = "[]",
    ) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"written":$written,"skipped":$skipped,"failed":$failed,"failures":$failures,"degraded":false}""",
            ),
        )
    }

    /** 网格手里那份 store(撤销走的就是它: "未同步"的寿命问题全在它身上)。 */
    private fun store(): DecisionStore = DecisionStore(factory(base, token))

    /** 滑卡页那份状态机(**生产同一份工厂**): `advanceOnWriteSuccess = true`。 */
    private fun culler(decisions: DecisionStore): ViewerViewModel = swipeViewModel(
        pairing = pairing,
        items = items(),
        startIndex = 0,
        thresholdPx = 250f,
        decisions = decisions,
        prefetch = {},
    )

    // ── ① store 层: 该写才写, 不该写一个字节都不发 ──────────────────────────

    @Test
    fun undo_of_an_unsynced_decision_drops_the_local_intent_and_sends_nothing() = runTest {
        // 电脑上 4 星; 用户左滑(废弃)但**写失败**了 ⇒ 本地是 0/未同步, 电脑上仍然是 4 星。
        // 这件事的撤销就是"把本地那条意图撤掉": 电脑上本来就还是老样子, 发一次反向写入
        // 就是一次**服务端没有被请求过**的写入(而且它会把"未同步"变成一个幻影)。
        enqueueRead(decision(paths[0], rating = 4))
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "readOnly")}]")
        // 这一次 summary 是给"**万一**撤销真的发了一次写入"准备的: 有了它, 那一次写入会拿到
        // 响应, 于是本用例红在下面那条**请求数**断言上(一句看得懂的话), 而不是因为没人应答
        // 挂到超时 —— 红要红在"多发了一次服务端没被请求过的写入"这件事上。
        enqueueSummary(written = 1)
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val before = store.get(paths[0])                 // 改之前: 电脑上的 4 星
        val after = before.withRating(0)
        store.applyLocal(after)
        assertTrue(store.push(listOf(paths[0])) is PushResult.Failed)
        val requests = server.requestCount

        val result = store.undo(UndoEntry(paths[0], before, after))

        assertEquals(UndoResult.LocalOnly, result)
        assertEquals("电脑从没收到过那次改动 ⇒ 一个请求都不发", requests, server.requestCount)
        assertTrue("本地那条'未同步'的意图必须撤掉(不留幻影)", store.all().isEmpty())
        assertEquals("电脑上那份 4 星重新是当前值", 4, store.get(paths[0]).rating)
        assertEquals(DesktopRating.Rated(4), store.get(paths[0]).desktopRating)
        assertEquals(SyncState.UNTOUCHED, store.get(paths[0]).sync)
        assertTrue(store.unsynced().isEmpty())
    }

    @Test
    fun undo_of_a_confirmed_decision_writes_the_previous_value_back() = runTest {
        // 反过来: 左滑**写成**了(电脑上现在是 0 星)。撤销必须把 4 送回去 —— 只改本地的话,
        // 屏幕上显示 4 而电脑上是 0, 那就是一个会一直骗人的界面。
        enqueueRead(decision(paths[0], rating = 4))
        enqueueSummary(written = 1)   // 左滑
        enqueueSummary(written = 1)   // 撤销
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val before = store.get(paths[0])
        val after = before.withRating(0)
        store.applyLocal(after)
        assertTrue(store.push(listOf(paths[0])) is PushResult.Ok)
        assertEquals(SyncState.SYNCED, store.get(paths[0]).sync)

        val result = store.undo(UndoEntry(paths[0], before, after))

        assertEquals(UndoResult.Push(PushResult.Ok(1, 1, 0, false)), result)
        server.takeRequest()   // 读
        val rejected = server.takeRequest().body.readUtf8()
        assertTrue("左滑发出去的是 0 星: $rejected", rejected.contains("\"rating\":0"))
        val undone = server.takeRequest().body.readUtf8()
        assertTrue("撤销必须把改动前的 4 星送回去: $undone", undone.contains("\"rating\":4"))
        assertTrue("送的是**这一张**: $undone", undone.contains(jq(paths[0])))
        assertEquals(4, store.get(paths[0]).rating)
        assertEquals(SyncState.SYNCED, store.get(paths[0]).sync)
        assertNull("服务端确认了 ⇒ 没有失败可留", store.get(paths[0]).lastError)
        assertTrue(store.unsynced().isEmpty())
    }

    @Test
    fun undo_does_not_send_a_write_the_server_did_not_need() = runTest {
        // 电脑上就是 5 星, 手机上又点了一次 5 星(值没变, 只是重新标成未同步), 服务端判 skipped。
        // 撤销要恢复的是"这台手机上没动过它", 而不是"把 5 再写一遍" —— 后者是一次谁都没要求的
        // 写入, 而且会在本地留下一条本不该存在的意图。
        enqueueRead(decision(paths[0], rating = 5))
        enqueueSummary(skipped = 1)
        // 同上: 一次谁都没要求的写入若真发出去, 会红在请求数断言上(而不是挂住)。
        enqueueSummary(written = 1)
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val before = store.get(paths[0])            // {5★, 这台手机上没动过}
        val after = before.withRating(5)            // 值逐字相同
        store.applyLocal(after)
        assertTrue(store.push(listOf(paths[0])) is PushResult.Ok)
        val requests = server.requestCount

        val result = store.undo(UndoEntry(paths[0], before, after))

        assertEquals(UndoResult.LocalOnly, result)
        assertEquals("电脑上已经是这个值 ⇒ 不必再写一遍", requests, server.requestCount)
        assertTrue("恢复成'这台手机上没动过它'", store.all().isEmpty())
        assertEquals(5, store.get(paths[0]).rating)
        assertEquals(SyncState.UNTOUCHED, store.get(paths[0]).sync)
    }

    @Test
    fun undo_restores_the_previous_local_intent_verbatim_including_its_sync_state() = runTest {
        // 之前那份意图**还没发出去**(未同步), 后来又改成 5 星但**写失败**了。撤销要恢复的
        // 是那份未同步的 4 星 —— 既不许把它说成"已同步"(那时电脑上什么都没有), 也不许发请求。
        enqueueRead(decision(paths[0]))                                    // 电脑上: 没有星级
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "busy")}]")
        enqueueSummary(written = 1)   // 同上: 万一多发了一次写入, 红在请求数断言上
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val before = store.get(paths[0]).withRating(4)
        store.applyLocal(before)
        val after = store.get(paths[0]).withRating(5)
        store.applyLocal(after)
        assertTrue(store.push(listOf(paths[0])) is PushResult.Failed)
        val requests = server.requestCount

        val result = store.undo(UndoEntry(paths[0], before, after))

        assertEquals(UndoResult.LocalOnly, result)
        assertEquals("电脑上一次都没收到过 ⇒ 不写", requests, server.requestCount)
        assertEquals("恢复的是之前那份意图", 4, store.get(paths[0]).rating)
        assertEquals(
            "同步状态逐字恢复 —— 不许把'未同步'说成'已同步', 也不许反过来",
            SyncState.UNSYNCED,
            store.get(paths[0]).sync,
        )
        assertNull("之前那份意图上没有失败, 恢复之后也不该有", store.get(paths[0]).lastError)
    }

    // ── ② 界面层: 撤销回到那一张, 不前进也不后退 ──────────────────────────────

    @Test
    fun undo_brings_back_the_photo_that_was_just_swiped_and_keeps_the_pc_in_step() = runTest {
        enqueueRead(decision(paths[0], rating = 4))
        enqueueSummary(written = 1)   // 左滑(废弃)成功
        enqueueSummary(written = 1)   // 撤销把 4 写回去
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)
        assertEquals("一开始没有可撤销的", 0, vm.state.value.undoCount)

        vm.swipe(SwipeDirection.Left)            // 0 星, 写成功 ⇒ 自动前进

        assertEquals("写成功才前进", 1, vm.state.value.index)
        assertEquals("多了一步可撤销的", 1, vm.state.value.undoCount)

        val result = vm.undo()

        assertEquals(UndoResult.Push(PushResult.Ok(1, 1, 0, false)), result)
        assertEquals("撤销的语义就是'把刚刚那张拿回来'", 0, vm.state.value.index)
        assertEquals("已撤销", vm.state.value.undoNote)
        assertEquals(4, vm.state.value.rating)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertEquals("撤销本身不再进撤销栈(没有重做)", 0, vm.state.value.undoCount)
        assertNull("撤销成功之后不该再有失败卡", vm.state.value.writeFailure)
        // 三个请求: 读 / 左滑的 PUT / 撤销的 PUT
        server.takeRequest()
        server.takeRequest()
        val put = server.takeRequest().body.readUtf8()
        assertTrue(put, put.contains("\"rating\":4"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun undoing_a_failed_swipe_sends_nothing_and_clears_the_unsynced_mark() = runTest {
        enqueueRead(decision(paths[0], rating = 4))
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "readOnly")}]")
        enqueueSummary(written = 1)   // 同上: 万一多发了一次写入, 红在请求数断言上
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)

        vm.swipe(SwipeDirection.Left)            // 写失败 ⇒ 不前进, 本地 0/未同步

        assertEquals(0, vm.state.value.index)
        assertEquals(1, vm.state.value.unsyncedCount)
        assertEquals("readOnly", vm.state.value.writeFailure?.code)
        val requests = server.requestCount

        val result = vm.undo()

        assertEquals(UndoResult.LocalOnly, result)
        assertEquals("电脑上从来没变过 ⇒ 一个请求都不发", requests, server.requestCount)
        assertEquals("电脑上那份 4 星重新是当前值", 4, vm.state.value.rating)
        assertEquals("幻影'未同步'必须消失(电脑上本来就是 4 星)", 0, vm.state.value.unsyncedCount)
        assertNull("那条失败也跟着走(它属于一条已经不存在的意图)", vm.state.value.writeFailure)
        assertTrue(vm.state.value.undoNote!!, vm.state.value.undoNote!!.contains("没有发送写入"))
        assertEquals("不前进也不后退(这一张本来就没被改动过)", 0, vm.state.value.index)
    }

    @Test
    fun undoing_a_manual_rating_returns_the_photo_to_the_pcs_value() = runTest {
        enqueueRead(decision(paths[0], rating = 2))
        enqueueSummary(written = 1)   // 手点 5 星
        enqueueSummary(written = 1)   // 撤销把 2 写回去
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)                   // 这一页写成功会前进(规格 §8.2)

        vm.setRating(5)
        assertEquals(1, vm.state.value.index)
        assertEquals(1, vm.state.value.undoCount)

        vm.undo()

        assertEquals("撤销把那张拿回来", 0, vm.state.value.index)
        assertEquals("恢复成电脑上原来的 2 星", 2, vm.state.value.rating)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertEquals("已撤销", vm.state.value.undoNote)
    }

    @Test
    fun a_failed_undo_never_says_it_succeeded_and_keeps_the_intent_retryable() = runTest {
        enqueueRead(decision(paths[0], rating = 4))
        enqueueSummary(written = 1)   // 左滑成功
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "permission")}]")   // 撤销失败
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)
        vm.swipe(SwipeDirection.Left)

        val result = vm.undo()

        val pushed = result as UndoResult.Push
        assertTrue(pushed.push is PushResult.Failed)
        val note = vm.state.value.undoNote!!
        assertFalse("没被服务端确认就绝不许说'已撤销': $note", note.contains("已撤销"))
        assertTrue(note, note.contains("没能写进电脑"))
        assertEquals("要撤销成的那个值留着(不是回滚丢掉)", 4, vm.state.value.rating)
        assertEquals(SyncState.UNSYNCED, vm.state.value.sync)
        assertEquals("permission", vm.state.value.writeFailure?.code)
        assertEquals("1 张未同步·重试", vm.state.value.unsyncedLabel)
        assertEquals("失败了也不许再占一步撤销(那一步已经用掉了)", 0, vm.state.value.undoCount)
    }

    @Test
    fun undo_with_an_empty_stack_does_nothing_and_says_so() = runTest {
        val vm = culler(store())

        val result = vm.undo()

        assertEquals(UndoResult.Empty, result)
        assertEquals("一个请求都不发", 0, server.requestCount)
        assertEquals("没有可撤销的操作", vm.state.value.undoNote)
        assertEquals(0, vm.state.value.undoCount)
    }

    @Test
    fun a_blocked_keep_leaves_nothing_to_undo() = runTest {
        // 被"不知道电脑上是几星"拦住的保留**什么都没改**(见 SwipeCullingTest 那一组),
        // 所以撤销栈里也不该多一步 —— 否则用户按撤销会得到一句"已撤销"却发现什么都没变。
        val store = store()                     // 没读过 ⇒ 不知道
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right)

        assertEquals(KEEP_BLIND_MESSAGE, vm.state.value.swipeNote)
        assertEquals("什么都没改 ⇒ 没有可撤销的", 0, vm.state.value.undoCount)
        assertEquals(UndoResult.Empty, vm.undo())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_decision_whose_value_did_not_change_does_not_take_an_undo_step() = runTest {
        // 在电脑上已经是 5 星的那张上**又点一次 5 星**: 本地值一个字节都没变(只是重新标成
        // 未同步)。为它占一步撤销, 用户按下去只会看到"已撤销"而屏幕上什么都没变。
        enqueueRead(decision(paths[0], rating = 5))
        enqueueSummary(skipped = 1)
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)

        vm.setRating(5)

        assertEquals("值没变 ⇒ 不占撤销步", 0, vm.state.value.undoCount)
        assertEquals("本地值就是 5(与电脑上那个值逐字相同)", 5, store.get(paths[0]).rating)
        assertEquals("服务端确认过 ⇒ 已同步", SyncState.SYNCED, store.get(paths[0]).sync)
        assertEquals("写成功照旧前进(与撤销无关)", 1, vm.state.value.index)
    }
}
