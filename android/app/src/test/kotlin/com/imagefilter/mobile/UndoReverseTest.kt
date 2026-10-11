// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.DecisionDto
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.RemotePull
import com.imagefilter.mobile.ui.SwipeDirection
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.UndoEntry
import com.imagefilter.mobile.ui.UndoResult
import com.imagefilter.mobile.ui.ViewerViewModel
import com.imagefilter.mobile.ui.reverseTarget
import com.imagefilter.mobile.ui.swipeViewModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **撤销到底有没有把电脑改回去**(本轮修的那个 CRITICAL)。
 *
 * 复现的正是这一串: 电脑上**没有**星级 → 用户右滑(保留) → 写上去 3 星 → 用户按撤销。
 * 老实现把 `before` 原样发出去(`{rating:null, label:null, clearLabel:false}`) —— 而协议里
 * `null` 的意思是"**别动这个属性**", 服务端因此**连边车文件都不打开**地回一个 `Skipped`
 * (`src-tauri/src/xmp.rs::write_one` 的第一行)。于是: 没有失败 ⇒ 本地标"已同步"、界面说
 * "已撤销", 而边车里的 `xmp:Rating="3"` 一个字节都没变。下一层楼再打开这个文件夹, 3 星又回来了。
 *
 * 这一组用例与 [UndoTest] 的区别是**夹具**: 这里挂的是一台行为与真服务端逐字对得上的假电脑
 * ([FakePc]) —— 它会拒绝空请求、会把真的写落下来、并且**读回来的就是它现在真的持有的值**。
 * 于是"撤销有没有生效"变成**可断言的事实**, 而不是我自己在测试里写的一句声明:
 *   · 只断言"请求体里有 `rating:0`"是不够的(那可能被服务端跳过);
 *   · 只断言"界面说已撤销"更是**正好**被老实现骗过去的那一句。
 *
 * ⚠️ 测不到的: 真服务端的原子写/SMB 降级/文件锁 —— 那部分在 Rust 侧的测试里(本任务不碰)。
 */
class UndoReverseTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var pc: FakePc
    private val token = "r".repeat(64)

    private val folderPath = "\\\\?\\D:\\照片 2026"
    private val paths = (1..4).map { "$folderPath\\p$it.jpg" }

    private val pairing get() = Pairing(base, token, note = "家里的台式机")

    private val factory: (String, String) -> ApiClient = { url, tkn ->
        ApiClient(url, tkn, buildHttpClient(tkn))
    }

    @Before
    fun setUp() {
        pc = FakePc()
        server = MockWebServer().also {
            it.dispatcher = pc
            it.start()
        }
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── 那台"假电脑": 与 `xmp.rs` 的两条真规则逐字对得上 ─────────────────────

    /**
     * 一台**行为与真服务端对得上**的假电脑。
     *
     * 它只有两条规则, 而这两条正是本轮那个 CRITICAL 的全部来源:
     *   ① **一个可写字段都没有的请求 ⇒ `skipped`**, 而且**不碰** [desktopRating]
     *      (`xmp.rs::write_one` 的短路口: 连文件都不打开);
     *   ② 其余的请求真的把值落下来: 值变了 = `written`, 没变 = `skipped`。
     *
     * `rating = 0` 按桌面端的读法归一成"**没有星级**"(`src/xmp.ts::mergeRemoteRatings`:
     * 读到 0 就删掉那个键) —— 所以 `desktopRating` 为 null 就是"电脑上确实没有星级",
     * 与 [fakeRead] 回报的 `rating: null` 是同一件事。这两处**必须**用同一份状态, 否则
     * "读回来的值"与"写下去的值"可以各说各话, 整个夹具就失去意义了。
     */
    private inner class FakePc : Dispatcher() {
        /** 电脑上**现在**真的持有的值(0 已归一成 null —— 见上)。 */
        var desktopRating: Int? = null
        var desktopLabel: String? = null

        /** 这台电脑真的收到过的那些写入(逐字, 供断言"到底发了什么")。 */
        val writes = mutableListOf<DecisionDto>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "GET") {
                return MockResponse().setResponseCode(200).setBody("[${fakeRead(paths[0])}]")
            }
            val item = Json.decodeFromString<List<DecisionDto>>(request.body.readUtf8()).single()
            writes += item
            if (item.rating == null && item.label == null && !item.clearLabel) {
                // ① 短路口: 什么都没要求写 ⇒ 跳过, **不碰**电脑上的值。
                return MockResponse().setResponseCode(200).setBody(summary(skipped = 1))
            }
            val nextRating = when {
                item.rating == null -> desktopRating
                item.rating == 0 -> null // 服务端契约: 0 即"清除星级"
                else -> item.rating
            }
            val nextLabel = when {
                item.clearLabel -> null
                item.label != null -> item.label
                else -> desktopLabel
            }
            val changed = nextRating != desktopRating || nextLabel != desktopLabel
            desktopRating = nextRating
            desktopLabel = nextLabel
            return MockResponse().setResponseCode(200).setBody(
                if (changed) summary(written = 1) else summary(skipped = 1),
            )
        }

        /** `xmp.rs::DecisionRead` 的一项, 逐字按线上拼写写 JSON(不拿 DTO 反向编码)。 */
        fun fakeRead(path: String): String =
            """{"path":${jq(path)},"rating":${desktopRating ?: "null"},""" +
                """"label":${desktopLabel?.let { jq(it) } ?: "null"},"sidecar":${jq("$path.xmp")}}"""
    }

    private fun summary(written: Int = 0, skipped: Int = 0, failed: Int = 0, failures: String = "[]") =
        """{"written":$written,"skipped":$skipped,"failed":$failed,"failures":$failures,"degraded":false}"""

    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

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

    private fun store(): DecisionStore = DecisionStore(factory(base, token))

    private fun culler(decisions: DecisionStore): ViewerViewModel = swipeViewModel(
        pairing = pairing,
        items = items(),
        startIndex = 0,
        thresholdPx = 250f,
        decisions = decisions,
        prefetch = {},
    )

    // ── ① 本项目点名的那一串: 没有星级 → 保留写 3 → 撤销 ────────────────────

    @Test
    fun undoing_a_keep_swipe_that_wrote_three_stars_over_nothing_really_reverses_it() = runTest {
        val store = store()
        assertTrue(store.pullRemote(listOf(paths[0])) is RemotePull.Merged)
        assertNull("前提: 电脑上这一张**没有**星级", pc.desktopRating)
        assertEquals(DesktopRating.Unrated, store.get(paths[0]).desktopRating)
        val vm = culler(store)

        // 右滑(保留): 电脑上确实没有星级 ⇒ 写 3 星。
        assertEquals(PushResult.Ok(1, 1, 0, false), vm.swipe(SwipeDirection.Right))
        assertEquals("前提: 那 3 星真的落到了电脑上", 3, pc.desktopRating)

        // 撤销: 目标状态是"电脑上又没有星级"。
        val result = vm.undo()

        val undoBody = pc.writes.last()
        val note = vm.state.value.undoNote
        // ⚠️ 这一组**一起**检查, 而不是一条一条 assert: 老实现下第一条就会红, 但红的时候要一眼
        // 看到**全部**坏掉的不变量(否则修好一条才会红下一条, 而每一条都是独立的一种撒谎方式)。
        val broken = buildList {
            if (undoBody.rating == null && undoBody.label == null && !undoBody.clearLabel) {
                add("撤销的请求体是一个纯空请求(服务端**连文件都不会打开**地跳过它)")
            }
            if (undoBody.rating != 0) {
                add("'没有星级'没有被写成 0(项目里那个可表示的形态), 实际 rating=${undoBody.rating}")
            }
            if (pc.desktopRating != null) add("电脑上还留着 ${pc.desktopRating} 星")
            if (pc.desktopRating == 3 && note == "已撤销") {
                add("电脑上那 3 星**还在**(手机上已经没有)却报了'已撤销' —— 本轮修的那个假成功")
            }
        }
        assertEquals("撤销没有真的把电脑改回去: $broken", emptyList<String>(), broken)

        // 修好之后这几条也必须成立(正向的那一半, 免得上面只证明了"没撒谎"而没证明"真做了")
        assertEquals("这一次服务端真的改了电脑上的值 ⇒ 那时才说'已撤销'", "已撤销", note)
        assertNull("电脑上那 3 星真的没了", pc.desktopRating)
        assertEquals(
            "手机上显示的是 0(项目里'没有星级'的可表示形态) —— 不是那 3 星",
            0,
            vm.state.value.rating,
        )
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertTrue("而且它是一次真的写入(不是被跳过的那一种)", result is UndoResult.Push)
    }

    @Test
    fun undoing_an_added_label_clears_it_on_the_pc_instead_of_leaving_it_behind() = runTest {
        // 标签那一半**更坏**: 老实现发 `label: null`, 而那是"别动这个属性" ——
        // 边车里的 `xmp:Label` 会活下来, 下次读盘把手机上已经清掉的标签**复活**。
        val store = store()
        store.pullRemote(listOf(paths[0]))
        assertNull(pc.desktopLabel)
        val vm = culler(store)

        vm.setLabel("green")
        assertEquals("前提: 绿标真的落到了电脑上", "green", pc.desktopLabel)

        vm.undo()

        val undoBody = pc.writes.last()
        assertTrue(
            "撤销一个**加上去的**标签必须发显式的 `clearLabel=true`(光发 label=null 只是'别动'): $undoBody",
            undoBody.clearLabel,
        )
        assertNull("电脑上的那个绿标必须真的被删掉", pc.desktopLabel)
        assertEquals("已撤销", vm.state.value.undoNote)
        assertNull("手机上也不再显示那个绿标", vm.state.value.label)
    }

    // ── ② 服务端把反向写入整条跳过时: 绝不说"已撤销" ────────────────────────

    @Test
    fun a_reverse_write_the_server_skipped_is_not_reported_as_undone_and_keeps_the_divergence() = runTest {
        // 真实会发生的时序: 反向写入发出去的时候, 电脑上**已经**是目标值了(用户在电脑上刚把这张改
        // 回来, 或者另一次写入先到)。服务端回 `written=0, skipped=1` —— 那**没有**让电脑上发生任何
        // 变化, 所以它证明不了"撤销成功了": 老实现把它当成功, 界面上说"已撤销"、本地标已同步、
        // 待重试队列被清空, 而我们其实什么都不知道。
        pc.desktopRating = 5                              // 电脑上: 5 星
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)
        assertEquals(PushResult.Ok(1, 1, 0, false), vm.setRating(0)) // 手点废弃
        assertNull("电脑上现在是我们写的 0(= 没有星级)", pc.desktopRating)

        // 电脑上**已经被改回目标值**了 ⇒ 我们这一次反向写入会被判 changed=false。
        pc.desktopRating = 5

        val result = vm.undo()

        assertEquals(
            "服务端回报 written=0 / skipped=1 ⇒ 这一次写入什么都没改",
            UndoResult.NotApplied(PushResult.Ok(1, 0, 1, false)),
            result,
        )
        val note = vm.state.value.undoNote!!
        assertNotEquals("一个什么都没改的写入不许被说成'已撤销'", "已撤销", note)
        assertTrue("要说清是服务端回报 skipped: $note", note.contains("skipped"))
        assertEquals("本地那一版留着(未同步) —— 分歧必须看得见", SyncState.UNSYNCED, vm.state.value.sync)
        assertEquals("1 张未同步·重试", vm.state.value.unsyncedLabel)
        assertEquals(WriteMessages.NOTHING_CHANGED, vm.state.value.writeFailure?.code)
        assertFalse("失败卡要有话说(界面一个字都不拼)", vm.state.value.writeText.isNullOrBlank())
    }

    @Test
    fun the_same_undo_without_the_skipped_write_does_reverse_the_pc_and_says_undone() = runTest {
        // 上面那条的**对照**(同一串操作, 只差"服务端到底改没改"): 这一次它真的把 5 星写回去了,
        // 于是"已撤销"是真的、本地也真的落成已同步。少了这一条, 上面那条可能只是因为
        // "这个状态机永远说没生效"而通过 —— 本项目已经撞过两次这种假绿。
        pc.desktopRating = 5                              // 电脑上: 5 星
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val vm = culler(store)
        assertEquals(PushResult.Ok(1, 1, 0, false), vm.setRating(0))
        assertNull(pc.desktopRating)

        val result = vm.undo()

        assertEquals(UndoResult.Push(PushResult.Ok(1, 1, 0, false)), result)
        assertEquals("已撤销", vm.state.value.undoNote)
        assertEquals("电脑上真的变回 5 星了", 5, pc.desktopRating)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertNull("没有失败可言", vm.state.value.writeFailure)
        assertNull("没有未同步的了", vm.state.value.unsyncedLabel)
    }

    // ── ③ 纯规则: `before` 表达不出来的那个值必须换成能表达的那一种 ──────────

    @Test
    fun the_reverse_target_expresses_a_missing_value_as_zero_and_a_missing_label_as_clear() {
        val p = paths[0]
        // 电脑上现在是 3(我们写的), 改动前是"没有星级" ⇒ 反向写入必须是 0, 不是 null。
        val restored = reverseTarget(
            now = Decision(p, rating = 3),
            before = Decision(p, rating = null, desktopRating = DesktopRating.Unrated),
        )
        assertEquals(0, restored.rating)
        assertFalse(restored.clearLabel)

        // 电脑上现在有一个绿标, 改动前没有 ⇒ 反向写入必须发 clearLabel。
        val cleared = reverseTarget(
            now = Decision(p, label = "green"),
            before = Decision(p, label = null),
        )
        assertNull(cleared.label)
        assertTrue(cleared.clearLabel)

        // 两个都要: 一次保留(星级+色标两档)的撤销
        val both = reverseTarget(
            now = Decision(p, rating = 3, label = "green"),
            before = Decision(p),
        )
        assertEquals(0, both.rating)
        assertTrue(both.clearLabel)

        // **对照**: 有值可恢复时**不许**被换成 0/clearLabel(那会把电脑上的 4 星抹掉)
        val real = reverseTarget(now = Decision(p, rating = 0), before = Decision(p, rating = 4))
        assertEquals(4, real.rating)
        assertFalse(real.clearLabel)
    }

    @Test
    fun the_reverse_target_is_always_something_the_server_will_actually_act_on() {
        // 这一条钉的是 FIX 3 的"要么不可能, 要么诚实"里那个"不可能": [DecisionStore.undo] 走到
        // 反向写入那一支时, 目标**必然**有可写字段 —— 于是"一条永远办不成事的队列项"不会被它造出来。
        //
        // 两个集合刻意不同, 因为**能走到那一支的 `now` 是有约束的**: `now.sync = SYNCED`, 而
        // [afterAck] 成功时会把一次性命令 `clearLabel` 消费掉 ⇒ `now.clearLabel` 必为 false。
        // (`before` 不受这个约束: 它就是用户改动前那一份, 什么形态都可能。)
        val p = paths[0]
        val beforeShapes = listOf<Decision>(
            Decision(p, rating = null),
            Decision(p, rating = 0),
            Decision(p, rating = 3),
            Decision(p, rating = 5, label = "green"),
            Decision(p, label = "red"),
            Decision(p, clearLabel = true),
        )
        val nowShapes = listOf<Decision>(
            Decision(p, rating = null),
            Decision(p, rating = 0),
            Decision(p, rating = 3),
            Decision(p, rating = 5, label = "green"),
            Decision(p, label = "red"),
        )
        var covered = 0
        for (now in nowShapes) {
            for (before in beforeShapes) {
                // 逐字相同的那一支根本不会走到反向写入(LocalOnly, 一个请求都不发) ——
                // 拿它来断言"目标可写"是错的, 而**跳过**它必须是因为这条逐字判据, 不是因为夹具凑巧。
                if (now.toDto() == before.toDto()) continue
                covered++
                assertTrue(
                    "now=$now / before=$before 的反向写入是空的 —— 服务端会整条跳过它",
                    reverseTarget(now, before).hasWritableField,
                )
            }
        }
        // 夹具不许把要看的那条路堵死: 覆盖到的组合必须够多(否则这条用例可能因为"一个都没跑"而绿)。
        // 5 个 `now` × 6 个 `before` 里有 5 对逐字相同(LocalOnly, 不进反向写入) ⇒ 25 对。
        assertEquals("应当覆盖到全部 25 个 DTO 不同的组合", 25, covered)
    }

    @Test
    fun an_undo_entry_whose_before_cannot_express_anything_still_writes_something_real() = runTest {
        // 纯函数那一条的**链路形态**: `before` 是一份"没有本地意图 + 什么都没有"的意图
        // (老实现正是把它 `copy(sync = UNSYNCED)` 之后发出去, 造出了一条永远办不成事的队列项)。
        val store = store()
        store.pullRemote(listOf(paths[0]))
        val before = store.get(paths[0])
        assertEquals("前提: 改动前电脑上什么都没有", DesktopRating.Unrated, before.desktopRating)
        val after = before.withRating(3).copy(sync = SyncState.SYNCED)
        store.applyLocal(after)
        store.push(listOf(paths[0])).let { assertEquals(PushResult.Ok(1, 1, 0, false), it) }

        val result = store.undo(UndoEntry(paths[0], before, after))

        assertEquals(UndoResult.Push(PushResult.Ok(1, 1, 0, false)), result)
        assertNull("电脑上那 3 星真的没了", pc.desktopRating)
        assertEquals(
            "本地这一版是 0(可表示), **不是**'一个可写字段都没有'的空意图 —— " +
                "老实现留下的正是那样一条永远办不成事的队列项",
            0,
            store.get(paths[0]).rating,
        )
        assertTrue("它现在是有东西可写的", store.get(paths[0]).hasWritableField)
    }
}
