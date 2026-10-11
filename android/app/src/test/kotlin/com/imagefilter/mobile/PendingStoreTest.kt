// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.XmpErrorCodes
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.AbandonConfirm
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.GridViewModel
import com.imagefilter.mobile.ui.PENDING_CORRUPT_NOTICE
import com.imagefilter.mobile.ui.PendingDecode
import com.imagefilter.mobile.ui.PendingItem
import com.imagefilter.mobile.ui.PendingStore
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.RetryReport
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.abandonNote
import com.imagefilter.mobile.ui.abandonPending
import com.imagefilter.mobile.ui.decodePending
import com.imagefilter.mobile.ui.encodePending
import com.imagefilter.mobile.ui.fileNameOf
import com.imagefilter.mobile.ui.mergePending
import com.imagefilter.mobile.ui.pendingBadgeText
import com.imagefilter.mobile.ui.pendingTargetText
import com.imagefilter.mobile.ui.rehydratePending
import com.imagefilter.mobile.ui.retryNote
import com.imagefilter.mobile.ui.retryPending
import com.imagefilter.mobile.ui.toDecision
import com.imagefilter.mobile.ui.toPendingItem
import com.imagefilter.mobile.ui.toPendingRows
import com.imagefilter.mobile.ui.topBarBadge
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **持久待重试队列**(M3′ Task 3)的真行为测试: JVM + MockWebServer, 走真的 HTTP 与真的
 * [ApiClient]。
 *
 * 这一份钉的是"用户已经做出的决定会不会丢"这条**半离线承诺**, 逐条对应一种真实的坏结果:
 *   ① **重启之后还在**([PendingStore] 的读-改-写真的往返一趟 JSON, 而不是"调了两个 API");
 *   ② **同一张只留最后一次意图**(三条会发出三个互相覆盖的请求);
 *   ③ **重试串行、只发仍然待重试的**、而且**只由服务端确认**才算写成;
 *   ④ **失败 / 没回报的项留在队列里**(不产生"假成功");
 *   ⑤ **放弃本地改动要二次确认**, 而且一个请求都不发;
 *   ⑥ **角标数的是队列里真的有的那些**;
 *   ⑦ **重启之后"电脑上是几星"重新变成不知道**(否则"保留不降级"会拿一份过期知识去决定不写)。
 *
 * ⚠️ 假绿防线(本项目已经撞过两次的两种形状):
 *   · 每一条断言都对着**具体的数**, 而且至少有一条会数的不同来源打架
 *     (例如"队列里剩 1 项"与"服务端只收到 1 个请求"必须同时成立);
 *   · **不让 fixture 把要看的那条路堵死**: 重试的用例一律从**空的** [DecisionStore] 开始
 *     (重启之后就是这样), 所以"把队列里的意图装回本地镜像"那一步是**必须真的发生**的 ——
 *     少了它 push 会回 NothingToPush, 队列永远清不掉, 那几条会立刻红。
 *
 * ⚠️ 这里**测不到**的东西(不许假装测到了): 真机上 `DataStorePendingQueueStore` 到底有没有把
 * 那个字符串刷到磁盘(要 `Context`, JVM 单测里没有, 而且本项目**不为它引 Robolectric**)——
 * 所以真机步骤列在报告的 UNVERIFIED 清单里。下面测的是**它会写下去的那个字符串**:
 * [FakePendingQueue] 逐字保存它, 与真机存的东西一模一样。
 */
class PendingStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "p".repeat(64)

    private val folder = "\\\\?\\D:\\照片 2026"
    private val a = "$folder\\a.jpg"
    private val b = "$folder\\b.jpg"
    private val c = "$folder\\c.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun jsonPath(p: String) = p.replace("\\", "\\\\")

    private fun summaryJson(written: Int, skipped: Int, failed: Int, failures: String = "[]") =
        """{"written":$written,"skipped":$skipped,"failed":$failed,"failures":$failures,"degraded":false}"""

    private fun failJson(p: String, code: String) =
        """{"path":"${jsonPath(p)}","code":"$code","detail":""}"""

    /** 一次"电脑确认写成了"的回报。 */
    private fun enqueueOk(written: Int = 1, skipped: Int = 0) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryJson(written, skipped, 0)))
    }

    /** 200 但**没有回报每一项**: `failed = 1` 却没有 `failures[]` —— 结构上就是"没有结论"。 */
    private fun enqueueUnconfirmed() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryJson(0, 0, 1)))
    }

    private fun store(queue: PendingStore? = null) =
        DecisionStore(ApiClient(base, token, buildHttpClient(token)), queue)

    private fun queueIn(fake: FakePendingQueue = FakePendingQueue()) = PendingStore(fake)

    // ── ① 同一张照片只留最后一次意图(去重) ──────────────────────────────────

    @Test
    fun the_same_photo_changed_three_times_leaves_exactly_one_intent_the_last_one() {
        // 三条会让"全部重试"发出三个互相覆盖的请求, 而前两次是用户已经改主意的旧决定。
        var m = mergePending(emptyMap(), Decision(a, rating = 3))
        m = mergePending(m, Decision(a, rating = 0))
        m = mergePending(m, Decision(a, rating = 5, label = "green"))

        assertEquals(1, m.size)
        assertEquals(5, m[a]!!.rating)
        assertEquals("green", m[a]!!.label)
        // 覆盖时**保持原来的位置**: 面板的顺序因此是稳定的(重试一次不会跳来跳去)
        assertEquals(listOf(a), m.keys.toList())
    }

    @Test
    fun different_photos_are_independent_and_a_blank_path_is_not_queued() {
        var m = mergePending(emptyMap(), Decision(a, rating = 3))
        m = mergePending(m, Decision(b, rating = 0))
        assertEquals(2, m.size)

        // 空路径归不到任何一张照片上(与 mergeRemote 同一条): 收下它只会多一条没人能重试的行
        assertEquals(m, mergePending(m, Decision("", rating = 4)))
    }

    // ── ② 落盘: 重启之后还在(真的走一遍 JSON 与"新实例") ─────────────────────

    @Test
    fun the_queue_survives_a_restart_with_the_intent_intact_and_the_knowledge_reset() = runTest {
        val fake = FakePendingQueue()
        // —— 会话一: 电脑关着, 用户打了分, 三条都失败 ——
        val first = PendingStore(fake)
        first.add(Decision(a, rating = 5, label = "green", clearLabel = false).withRating(5))
        first.add(Decision(b, rating = 0))
        assertEquals(2, first.count())

        // —— 重启(App 被杀掉): 新的 PendingStore 实例, 只认盘上那个字符串 ——
        val afterRestart = PendingStore(fake)
        val items = afterRestart.all()

        assertEquals("待重试必须在重启之后仍然是 2 项", 2, items.size)
        assertEquals(5, items[a]!!.rating)
        assertEquals("green", items[a]!!.label)
        assertEquals(0, items[b]!!.rating)
        assertEquals("能进队列就说明电脑没确认过它", SyncState.UNSYNCED, items[a]!!.sync)
        // ⚠️ 这条是**诚实性**, 不是洁癖: 重启之后"电脑上是几星"重新变成**不知道**。
        // 把上次会话的知识(`Rated(5)`)读回来, 会让 decideSwipe 的"保留绝不降级"那一支
        // 判定"不用写" —— 一次静默的什么都没发生。
        assertEquals(DesktopRating.Unknown, items[a]!!.desktopRating)
    }

    @Test
    fun the_persisted_shape_never_carries_session_knowledge_even_if_it_is_set() {
        // 直接把"这次会话知道的事"塞进一条意图(在内存里它是合法的), 落盘之后**必须**被丢掉。
        val loaded = Decision(
            path = a,
            rating = 4,
            sync = SyncState.SYNCED,
            desktopRating = DesktopRating.Rated(5),
        ).toPendingItem().toDecision()

        assertEquals(4, loaded.rating)
        assertEquals(SyncState.UNSYNCED, loaded.sync)
        assertEquals(DesktopRating.Unknown, loaded.desktopRating)
    }

    @Test
    fun the_encoded_string_really_round_trips_and_an_empty_or_broken_one_is_never_a_queue() {
        val m = mapOf(a to Decision(a, rating = 4, label = "blue"), b to Decision(b, clearLabel = true))
        val text = encodePending(m)
        assertTrue("落盘的必须是一个 JSON 对象", text.startsWith("{"))

        val back = decodePending(text)
        assertTrue(back is PendingDecode.Ok)
        // 往返对**持久的那部分**逐字相等(sync/desktopRating 是会话知识, 见 PendingItem 的 KDoc)
        assertEquals(
            m.mapValues { it.value.copy(sync = SyncState.UNSYNCED, desktopRating = DesktopRating.Unknown) },
            (back as PendingDecode.Ok).items,
        )
        // clearLabel 是一次性**命令**, 往返之后必须还在(丢了它, 重试就退化成一次什么都没干的请求)
        assertTrue(back.items[b]!!.clearLabel)

        // 从来没写过 ≠ 读不回来 —— 两者混成一个空队列, 界面就会在**可能已经丢了数据**的时候
        // 说"没有待重试的改动"。
        assertEquals(PendingDecode.NotStored, decodePending(null))
        assertEquals(PendingDecode.NotStored, decodePending("   "))
        assertEquals(PendingDecode.Corrupt, decodePending("这不是 JSON"))
        assertEquals(PendingDecode.Corrupt, decodePending("[]"))
    }

    @Test
    fun a_queue_that_cannot_be_read_is_reported_as_such_instead_of_looking_empty() = runTest {
        val broken = FakePendingQueue(initial = "{坏掉的")
        val queue = PendingStore(broken)

        val snap = queue.snapshot()

        assertTrue("读不回来必须说出来", snap.corrupted)
        assertEquals(0, snap.count)
        // 页面上那句提示必须存在(角标这时是 0, 而 0 与"不知道有多少"是两件事)
        assertTrue(PENDING_CORRUPT_NOTICE.contains("没能读出来"))
    }

    @Test
    fun the_failure_reason_survives_the_restart_so_the_panel_can_still_explain_it() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(
            Decision(a, rating = 4).copy(
                lastError = WriteFailure(a, XmpErrorCodes.READ_ONLY, FailureSource.XMP, "只读"),
            ),
        )

        val restored = PendingStore(fake).all()[a]!!

        assertEquals(XmpErrorCodes.READ_ONLY, restored.lastError!!.code)
        // source 必须一起活下来: 它决定查哪张码表(靠码本身猜会在两张表的交集上分错)
        assertEquals(FailureSource.XMP, restored.lastError!!.source)
    }

    @Test
    fun two_writes_that_interleave_do_not_lose_one_of_the_two_photos() = runTest {
        // 读-改-写不加锁时, 两个并发的 add 会在挂起点交错, 后写的那个把先写的**整条覆盖掉**。
        // (fake 每次读写都 yield 一次 —— 这正是让那个交错真的发生所需要的条件。)
        val queue = PendingStore(FakePendingQueue(yieldOnTouch = true))

        val one = async { queue.add(Decision(a, rating = 5)) }
        val two = async { queue.add(Decision(b, rating = 1)) }
        one.await()
        two.await()

        assertEquals("两张照片都要在", setOf(a, b), queue.all().keys)
    }

    // ── ③ 重试: 串行、只发仍然待重试的、只认服务端的确认 ──────────────────────

    @Test
    fun retry_sends_one_request_per_item_and_only_clears_what_the_server_confirmed() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))
        // 重启之后的真实起点: **本地镜像是空的**(fixture 不许先把意图塞进 store, 那样就把
        // "装回本地镜像"那一步变成不可达的代码)
        val decisions = store(queue)
        assertEquals(emptyList<Decision>(), decisions.all())

        enqueueOk(written = 1)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(summaryJson(0, 0, 1, failures = "[${failJson(b, XmpErrorCodes.READ_ONLY)}]")),
        )

        val report = retryPending(queue, decisions)

        assertEquals("两项都是真的发了请求的", 2, report.attempted)
        assertEquals(listOf(a), report.synced)
        assertEquals(1, report.failures.size)
        assertEquals(XmpErrorCodes.READ_ONLY, report.failures.single().code)
        assertFalse(report.allSynced)

        // **串行**: 两个请求, 一个接一个(不是并发), 而且请求体里只有那一项
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        assertTrue(first.body.readUtf8().contains(jsonPath(a)))
        val second = server.takeRequest()
        assertTrue(second.body.readUtf8().contains(jsonPath(b)))

        // 队列里只剩失败的那一项 —— 角标数的就是它
        assertEquals(setOf(b), queue.all().keys)
    }

    @Test
    fun a_retry_that_was_not_confirmed_by_the_server_never_clears_the_queue() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        val decisions = store(queue)

        // 200 但**没有回报这一项的写入结果**(failed=1 却没有 failures[]): 结构上就是"没有结论"。
        enqueueUnconfirmed()

        val report = retryPending(queue, decisions)

        assertEquals(1, report.attempted)
        assertTrue("没有结论的一个都不许算成写成", report.synced.isEmpty())
        assertEquals(1, report.stillQueued)
        assertEquals(setOf(a), queue.all().keys)
        assertEquals(WriteMessages.UNCONFIRMED, report.failures.single().code)
        // 那句话也不许说"写成"或"清空"
        val note = retryNote(report)
        assertFalse("没能确认时不许说清空: $note", note.contains("清空"))
        assertTrue(note.contains("没能确认"))
    }

    @Test
    fun a_dead_pc_leaves_every_item_queued_and_no_item_is_reported_as_written() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))
        val decisions = store(queue)
        // 电脑关着: 服务不在了(这不是"写失败", 是"根本没连上")
        server.shutdown()

        val report = retryPending(queue, decisions)

        assertEquals(2, report.attempted)
        assertTrue(report.synced.isEmpty())
        assertEquals(2, report.stillQueued)
        assertEquals("两条都还得在队列里", setOf(a, b), queue.all().keys)
        assertTrue(decisions.unsynced().size == 2)
        assertFalse(retryNote(report).contains("清空"))
    }

    @Test
    fun a_path_that_is_no_longer_pending_is_not_resend_at_all() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        val decisions = store(queue)
        // 点名要重试的三条里, 两条**已经不在队列里**了(上一次成了 / 用户放弃了)
        enqueueOk(written = 1)

        val report = retryPending(queue, decisions, listOf(a, b, c))

        assertEquals("只发仍然待重试的那一项", 1, report.attempted)
        assertEquals(listOf(a), report.synced)
        assertEquals(listOf(b, c), report.notAttempted)
        assertEquals("一个请求都不该为不在队列里的路径发出去", 1, server.requestCount)
    }

    @Test
    fun one_dead_path_does_not_stop_the_next_item_from_being_written() = runTest {
        // 这是 M0′ Task 7 那个坑的决策版: 半成功/已不存在的路径混在一批里会让**整批** 404。
        // 一次一项, 所以 a 的 404 毒不到 b。
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))
        val decisions = store(queue)

        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"code":"notFound"}"""),
        )
        enqueueOk(written = 1)

        val report = retryPending(queue, decisions)

        assertEquals(2, report.attempted)
        assertEquals("第二项照样写成了", listOf(b), report.synced)
        assertEquals(listOf(a), report.failures.map { it.path })
        assertEquals(setOf(a), queue.all().keys)
    }

    @Test
    fun retrying_an_empty_queue_sends_nothing_and_says_so() = runTest {
        val queue = queueIn()
        val decisions = store(queue)

        val report = retryPending(queue, decisions)

        assertEquals(0, report.attempted)
        assertEquals(0, server.requestCount)
        assertEquals("没有待重试的改动。", retryNote(report))
    }

    // ── ④ 本地镜像与队列的关系(写入链路自动对齐) ────────────────────────────

    @Test
    fun a_failed_write_lands_in_the_queue_and_a_confirmed_one_leaves_it() = runTest {
        val queue = queueIn()
        val s = store(queue)

        // ① 关着电脑打分 → 失败 → **进队列**(这是"半离线承诺"的落点)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"serviceOff"}"""))
        s.applyLocal(Decision(a).withRating(5))
        val failed = s.push(listOf(a))
        assertTrue(failed is PushResult.Failed)
        assertEquals(setOf(a), queue.all().keys)

        // ② 电脑回来了, 重试成功 → **出队列**
        enqueueOk(written = 1)
        s.push(listOf(a))
        assertEquals(emptySet<String>(), queue.all().keys)
        assertEquals(SyncState.SYNCED, s.get(a).sync)
    }

    @Test
    fun three_failed_changes_to_one_photo_leave_one_row_in_the_queue_and_one_in_the_ledger() = runTest {
        // 真行为版的去重: 三次改动**都失败**, 队列里只能有一条(最后那次的意图)。
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        val s = store(queue)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"serviceOff"}""")) }

        s.applyLocal(Decision(a).withRating(3))
        s.push(listOf(a))
        s.applyLocal(Decision(a).withRating(0))
        s.push(listOf(a))
        s.applyLocal(Decision(a).withRating(5).withLabel("green"))
        s.push(listOf(a))

        val items = queue.all()
        assertEquals("三条会发出三个互相覆盖的请求", 1, items.size)
        assertEquals(5, items[a]!!.rating)
        assertEquals("green", items[a]!!.label)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun a_local_intent_still_in_memory_is_never_overwritten_by_the_queued_older_one() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 1))
        val s = store(queue)
        // 用户刚刚又改过一次(比队列里那条新) —— 装回来的时候**不许**覆盖它
        s.applyLocal(Decision(a).withRating(4))

        val restored = rehydratePending(queue, s)

        assertEquals(0, restored)
        assertEquals("用户最新的决定必须赢", 4, s.get(a).rating)
    }

    @Test
    fun rehydrating_puts_last_nights_intents_back_so_the_grid_can_show_them() = runTest {
        val queue = queueIn()
        queue.add(Decision(a, rating = 5, label = "green"))
        val s = store(queue)

        val restored = rehydratePending(queue, s)

        assertEquals(1, restored)
        assertEquals(5, s.get(a).rating)
        assertEquals(SyncState.UNSYNCED, s.get(a).sync)
        assertTrue(s.get(a).hasLocalIntent)
        // 装回来的是**意图**, 不是"电脑上的值": 电脑上是什么仍然不知道(见 PendingItem 的 KDoc)
        assertEquals(DesktopRating.Unknown, s.get(a).desktopRating)
    }

    @Test
    fun the_queue_is_never_wiped_by_a_push_that_the_store_knows_nothing_about() = runTest {
        // 重启之后本地镜像是空的; 这时的一次推送**只**关系到它自己那一张。
        // 把"没见到"当成"电脑确认了"会把整个队列清掉 —— 用户昨晚的分就这么没了。
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))
        val s = store(queue)

        enqueueOk(written = 1)
        s.applyLocal(Decision(c).withRating(2))
        s.push(listOf(c))

        // c 被电脑确认写成了 ⇒ 它**不该**留在队列里; 而 a / b 一个字都不许动。
        assertEquals("只有被确认的那一项出队列", setOf(a, b), queue.all().keys)
    }

    // ── ⑤ 放弃本地改动: 二次确认, 而且一个请求都不发 ──────────────────────────

    @Test
    fun one_tap_never_abandons_anything() {
        val fresh = AbandonConfirm()
        assertFalse("没武装时永远不许动手", fresh.confirms(a))
        assertEquals(null, fresh.armed)

        val armed = fresh.arm(a)
        assertTrue(armed.confirms(a))
        // 点了**别的一张**是"改主意了", 不是"把这一张丢掉"
        assertFalse(armed.confirms(b))
        // 取消(关面板/去重试)之后又回到"一次误触不许丢东西"
        assertFalse(armed.cancel().confirms(a))
    }

    @Test
    fun abandoning_drops_the_local_intent_and_the_queue_entry_without_any_request() = runTest {
        val queue = queueIn()
        val s = store(queue)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"serviceOff"}"""))
        s.applyLocal(Decision(a).withRating(5))
        s.push(listOf(a))
        assertEquals(setOf(a), queue.all().keys)
        val requestsBefore = server.requestCount

        val result = abandonPending(queue, s, a)

        assertTrue(result.wasQueued)
        assertTrue(result.droppedLocal)
        assertEquals(emptySet<String>(), queue.all().keys)
        assertFalse("本地意图必须真的没了", s.get(a).hasLocalIntent)
        assertEquals("放弃**一个请求都不发**", requestsBefore, server.requestCount)
        assertTrue(abandonNote(result).contains("一个请求都没发"))
    }

    @Test
    fun abandoning_never_drops_a_local_intent_the_pc_already_confirmed() = runTest {
        val queue = queueIn()
        val s = store(queue)
        enqueueOk(written = 1)
        s.applyLocal(Decision(a).withRating(5))
        s.push(listOf(a))
        assertEquals(SyncState.SYNCED, s.get(a).sync)
        // 队列里塞一条陈旧的项(例如上一次成功之后进程被杀在保存队列之前)
        queue.add(Decision(a, rating = 5))

        val result = abandonPending(queue, s, a)

        assertTrue(result.wasQueued)
        assertFalse("电脑确认过的本地意图不许丢(丢了就是把一次成功的写入说成没发生)", result.droppedLocal)
        assertEquals(5, s.get(a).rating)
        assertEquals(SyncState.SYNCED, s.get(a).sync)
    }

    // ── ⑥ 角标与面板(数出来的每一张都必须是**真的**待重试的) ────────────────

    @Test
    fun the_badge_counts_the_queue_and_is_hidden_when_there_is_nothing_pending() {
        assertNull("0 时不许画一个写着 0 的角标", pendingBadgeText(0))
        assertNull(pendingBadgeText(-1))
        assertEquals("待重试 1", pendingBadgeText(1))
        assertEquals("待重试 3", pendingBadgeText(3))
    }

    @Test
    fun the_badge_label_and_its_action_always_describe_the_same_set() {
        val unsynced = WriteMessages.unsyncedLabel(3)

        // 队列非空 ⇒ 数队列、按下去开面板
        val pending = topBarBadge(pendingCount = 3, unsyncedLabel = unsynced)
        assertEquals("待重试 3", pending!!.label)
        assertTrue(pending.opensPanel)

        // 队列空(那一瞬间)但这次会话里还有未同步的 ⇒ 沿用那句话、按下去重试
        val fallback = topBarBadge(pendingCount = 0, unsyncedLabel = unsynced)
        assertEquals(unsynced, fallback!!.label)
        assertFalse(fallback.opensPanel)

        assertNull("两边都没有时不许画角标", topBarBadge(pendingCount = 0, unsyncedLabel = null))
    }

    @Test
    fun a_panel_row_reuses_the_one_message_table_and_never_invents_a_reason() {
        // 有失败原因: 按**码**查出那句可执行的话(与查看器/网格同一张表)
        val readOnly = Decision(a, rating = 4).copy(
            lastError = WriteFailure(a, XmpErrorCodes.READ_ONLY, FailureSource.XMP),
        )
        assertEquals(WriteMessages.forFailure(readOnly.lastError!!), toPendingRows(listOf(readOnly)).single().reason)

        // 没有失败原因 ⇒ 那是**"没有结论"**, 不是"未知错误": 用 unconfirmed 那句, 不另编一句
        val noReason = Decision(b, rating = 0)
        val row = toPendingRows(listOf(noReason)).single()
        val unconfirmed = WriteFailure(b, WriteMessages.UNCONFIRMED, FailureSource.CLIENT)
        assertEquals(WriteMessages.forFailure(unconfirmed), row.reason)
        assertEquals(WriteMessages.actionLabel(WriteMessages.actionFor(unconfirmed)), row.actionLabel)

        assertEquals("b.jpg", row.fileName)
        assertEquals("废弃", row.target)
    }

    @Test
    fun the_target_text_and_the_file_name_are_readable_for_every_shape_of_intent() {
        assertEquals("5★", pendingTargetText(Decision(a, rating = 5)))
        assertEquals("废弃", pendingTargetText(Decision(a, rating = 0)))
        assertEquals("绿色标", pendingTargetText(Decision(a, label = "green")))
        assertEquals("删掉色标", pendingTargetText(Decision(a, clearLabel = true)))
        assertEquals("5★ · 绿色标", pendingTargetText(Decision(a, rating = 5, label = "green")))
        assertEquals("没有要写的改动", pendingTargetText(Decision(a)))

        // 规范路径带 verbatim 前缀(里面有一个**字面问号**): 只切最后一个分隔符, 不自己拼路径
        assertEquals("a.jpg", fileNameOf(a))
        assertEquals("c.jpg", fileNameOf(c))
        assertEquals("没有分隔符时原样返回", "a.jpg", fileNameOf("a.jpg"))
    }

    // ── ⑦ 网格那一层: 角标 / 装回 / 二次确认的入口 / 重试全部 ──────────────────

    private val gridFolder = FolderEntryDto(path = folder, name = "照片 2026")

    private fun grid(queue: PendingStore): GridViewModel = GridViewModel(
        pairing = Pairing(base, token, note = "家里的台式机"),
        folder = gridFolder,
        pending = queue,
        clientFactory = { url, tkn -> ApiClient(url, tkn, buildHttpClient(tkn)) },
    )

    private fun scanJson(vararg paths: String) = "[" + paths.joinToString(",") { p ->
        """{"path":"${jsonPath(p)}","fileName":"${fileNameOf(p)}","fileSize":1,"isRaw":false,""" +
            """"isVideo":false,"modifiedAt":1,"exif":{}}"""
    } + "]"

    private fun decisionsJson(vararg paths: String) = "[" + paths.joinToString(",") { p ->
        """{"path":"${jsonPath(p)}","rating":null,"label":null,"sidecar":null}"""
    } + "]"

    @Test
    fun the_grid_shows_the_badge_rehydrates_and_toggles_exactly_one_item() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5, label = "green"))
        queue.add(Decision(b, rating = 0))

        server.enqueue(MockResponse().setResponseCode(200).setBody(scanJson(a, b, c)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(decisionsJson(a, b, c)))

        val vm = grid(queue)
        vm.loadAll()

        // 角标 = 队列里真的有的那 2 项
        assertEquals(2, vm.state.value.pendingCount)
        assertEquals("待重试 2", vm.state.value.pending!!.badge)
        assertEquals("待重试 2", vm.state.value.topBadge!!.label)
        assertTrue(vm.state.value.topBadge!!.opensPanel)
        // 装回来的意图**当场**在格子上(不是等下一次刷新): 5★ / 绿
        assertEquals(5, vm.state.value.decisions[a]!!.rating)
        assertEquals("green", vm.state.value.decisions[a]!!.label)
        assertEquals(SyncState.UNSYNCED, vm.state.value.decisions[a]!!.sync)
        // 这个文件夹里没待重试项的**那张照片不受影响**
        assertNull(vm.state.value.decisions[c])
        assertEquals(2, vm.state.value.pending!!.rows.size)
    }

    @Test
    fun the_grid_abandon_needs_two_taps_on_the_same_photo() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))

        server.enqueue(MockResponse().setResponseCode(200).setBody(scanJson(a, b)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(decisionsJson(a, b)))
        val vm = grid(queue)
        vm.loadAll()
        val requestsAfterLoad = server.requestCount

        // ① 第一次点: **只武装** —— 队列一项都不许少
        assertNull(vm.requestAbandon(a))
        assertEquals(a, vm.state.value.pending!!.abandonArmed)
        assertEquals(setOf(a, b), queue.all().keys)
        assertEquals("武装不是丢弃", 2, vm.state.value.pendingCount)

        // ② 点了**别的一张**: 换个武装对象, 照样什么都不丢
        assertNull(vm.requestAbandon(b))
        assertEquals(b, vm.state.value.pending!!.abandonArmed)
        assertEquals(setOf(a, b), queue.all().keys)

        // ③ 关掉面板/取消之后, 那一次武装作废: 再点一次仍然只是武装
        vm.cancelAbandon()
        assertNull("取消之后第一次点不算确认", vm.requestAbandon(a))
        assertEquals(setOf(a, b), queue.all().keys)

        // ④ 第二次点在**同一张**上才真的丢, 而且只丢那一张; 一个请求都没发
        val result = vm.requestAbandon(a)
        assertNotNull(result)
        assertTrue(result!!.droppedLocal)
        assertEquals(setOf(b), queue.all().keys)
        assertEquals(1, vm.state.value.pendingCount)
        assertEquals("待重试 1", vm.state.value.topBadge!!.label)
        assertEquals("放弃不发请求", requestsAfterLoad, server.requestCount)
        assertNull(vm.state.value.pending!!.abandonArmed)
    }

    @Test
    fun the_grid_retry_all_only_sends_what_is_still_pending_and_reports_honestly() = runTest {
        val fake = FakePendingQueue()
        val queue = PendingStore(fake)
        queue.add(Decision(a, rating = 5))
        queue.add(Decision(b, rating = 1))

        server.enqueue(MockResponse().setResponseCode(200).setBody(scanJson(a, b)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(decisionsJson(a, b)))
        val vm = grid(queue)
        vm.loadAll()

        // a 写成, b 的边车只读(去电脑上改) —— 半成功必须说出来
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryJson(1, 0, 0)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(summaryJson(0, 0, 1, failures = "[${failJson(b, XmpErrorCodes.READ_ONLY)}]")),
        )

        val report = vm.retryAllPending()

        assertEquals(2, report.attempted)
        assertEquals(listOf(a), report.synced)
        assertEquals(setOf(b), queue.all().keys)
        assertEquals(1, vm.state.value.pendingCount)
        assertFalse(vm.state.value.pending!!.busy)

        // 那句话: 说清了"确认写成几项 / 还有几项没写进电脑", **绝不说清空**
        val note = vm.state.value.pending!!.note!!
        assertTrue(note, note.contains("电脑确认写成 1 项"))
        assertTrue(note, note.contains("1 项没写进电脑"))
        assertFalse(note, note.contains("清空"))
        // 逐项文案来自既有那张表(不写第二份映射)
        assertTrue(vm.state.value.pending!!.rows.single().reason.contains("只读"))
    }

    @Test
    fun an_unreadable_queue_is_visible_on_the_page_not_silently_zero() = runTest {
        val fake = FakePendingQueue(initial = "{坏掉的")
        val queue = PendingStore(fake)
        server.enqueue(MockResponse().setResponseCode(200).setBody(scanJson(a)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(decisionsJson(a)))

        val vm = grid(queue)
        vm.loadAll()

        assertEquals(0, vm.state.value.pendingCount)
        assertEquals(PENDING_CORRUPT_NOTICE, vm.state.value.pendingCorruptedNotice)
        assertTrue(vm.state.value.pending!!.corrupted)
    }

    // ── ⑧ 重试报告那句话(逐条转述, 不许替服务端多说一个字) ────────────────────

    @Test
    fun the_retry_note_says_what_the_server_confirmed_and_what_is_still_waiting() {
        val all = RetryReport(attempted = 2, synced = listOf(a, b))
        assertTrue(retryNote(all).contains("2 项"))
        assertTrue(retryNote(all).contains("清空"))

        val partial = RetryReport(
            attempted = 3,
            synced = listOf(a),
            failures = listOf(WriteFailure(b, XmpErrorCodes.DISK_FULL, FailureSource.XMP)),
            inconclusive = listOf(c),
        )
        val note = retryNote(partial)
        assertTrue(note, note.contains("确认写成 1 项"))
        assertTrue(note, note.contains("1 项没写进电脑"))
        assertTrue(note, note.contains("1 项没能确认"))
        assertFalse("半成功不许说清空: $note", note.contains("清空"))

        assertEquals("没有待重试的改动。", retryNote(RetryReport(attempted = 0)))
        assertTrue(retryNote(RetryReport(attempted = 0, notAttempted = listOf(a))).contains("没有发出任何请求"))
    }
}
