// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.PREVIEW_MAX_DEFAULT
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.Filters
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.GridViewModel
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SWIPE_PREFETCH_AHEAD
import com.imagefilter.mobile.ui.SwipeConfig
import com.imagefilter.mobile.ui.SwipeDirection
import com.imagefilter.mobile.ui.SwipeMode
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.ViewerViewModel
import com.imagefilter.mobile.ui.swipeViewModel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * 滑卡**走真链路**的行为测试: JVM + MockWebServer, 用真的 [ApiClient] 拼 URL、真的发请求,
 * 写入真的经过 [DecisionStore](乐观更新 / 未同步 / 失败可见 / 重试)。
 *
 * 这里钉的五件事(每一件都对应一种真实会发生的坏结果, 而且**每一条都有非空的对照**):
 *   ① **保留绝不降级**: 对一张电脑上已有 5 星的照片右滑 → **一个请求都不发**, 本地也不许
 *      凭空多一条"未同步"意图; 同一份测试里"左滑 = 明确废弃, 允许降级"是它的对照;
 *   ② **右滑没评过的给 3 星**, 而且默认档**只写星级**, 请求体里连 `label` / `clearLabel` 都没有;
 *   ③ **只写星级这一档绝不碰色标**: 有一张紫标的照片右滑, 线上带出去的是**原来那个紫**,
 *      绝不是滑卡的红/绿; "只写色标"那一档左滑同理**绝不写 0 星**;
 *   ④ **写失败不前进、决定还在、可重试**: 失败后索引不动、失败卡有话说、重开这一页(同一份
 *      store)也还在 —— 而同一套配置下**成功会前进**(对照组, 于是"失败不前进"不是一句空话);
 *   ⑤ **滑的是筛出来的那一批**: 队列由网格的 `visibleItems` 给出, 被筛掉的照片一个请求都不发;
 *      预取用的是**同一个**预取器、**默认档**、向前 2 张([SWIPE_PREFETCH_AHEAD])。
 *
 * ⚠️ 测不到的: 真实手势(松手阈值、回弹、双指捏合不与单指抢事件)、卡片动画与真机手感 ——
 * 没有设备, 也不为本任务引 Compose UI 测试依赖; 见报告里的 UNVERIFIED 清单。
 */
class SwipeCullingTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "s".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格与中文。 */
    private val folderPath = "\\\\?\\D:\\照片 2026"
    private val paths = (1..4).map { "$folderPath\\p$it.jpg" }

    private val pairing get() = Pairing(base, token, note = "家里的台式机")
    private val folder get() = FolderEntryDto(path = folderPath, name = "照片 2026")

    /** 注入的工厂一律以 [buildHttpClient] 为底 —— 鉴权头只装在那里(与其它测试同一条纪律)。 */
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

    /** JSON 字符串字面量: 路径里的反斜杠必须转义, 手写太容易错, 所以只写这一处。 */
    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

    private fun failJson(p: String, code: String) =
        """{"path":${jq(p)},"code":"$code","detail":"诊断用的中文"}"""

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

    /**
     * 这一份 store 就是网格手里那一份(滑卡页与网格**共用**它)。
     *
     * [seed] 是"电脑上已经有的值"(走真的 `mergeRemote` 那条本地/远端优先级入口), 不是
     * `applyLocal` —— 那一条差别很要紧: 远端值**不是**这台手机上的意图, 也不该带"未同步"。
     */
    private fun store(seed: List<DecisionReadDto> = emptyList()): DecisionStore {
        val s = DecisionStore(factory(base, token))
        if (seed.isNotEmpty()) s.mergeRemote(seed)
        return s
    }

    /**
     * 滑卡页的那份状态机 —— **走生产同一份工厂**([swipeViewModel]), 所以这里断言的是
     * "滑卡页真的被配成这样", 不是"测试自己搭了一份看起来一样的配置"。
     */
    private fun culler(
        decisions: DecisionStore,
        startIndex: Int = 0,
        prefetch: (List<String>) -> Unit = {},
    ): ViewerViewModel = swipeViewModel(
        pairing = pairing,
        items = items(),
        startIndex = startIndex,
        thresholdPx = 250f,
        decisions = decisions,
        prefetch = prefetch,
    )

    // ── ① 保留绝不降级(本任务最贵的一条) ────────────────────────────────────

    @Test
    fun right_swipe_on_a_five_star_photo_writes_nothing_at_all() = runTest {
        // 桌面端给过 5 星, 这台手机一个字节都没改过。右滑**不许**把它改成 3 星 ——
        // 那是静默的数据损失, 比"什么都没发生"坏得多。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 5)))
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Right)

        assertEquals("一个请求都不发出去", 0, server.requestCount)
        assertEquals(PushResult.NothingToPush(1), result)
        assertEquals(
            "本地也不许凭空多一条意图 —— 那会让'未同步 N 张'这个角标撒谎",
            emptyList<Decision>(),
            store.all(),
        )
        assertEquals("电脑上那份 5 星原封不动", 5, store.get(paths[0]).rating)
        assertEquals("但卡片照样前进(规格 §8.2: 不写也要前进)", 1, vm.state.value.index)
        assertEquals("而且要说清为什么没写", "已保留 · 保持 5★", vm.state.value.swipeNote)
        assertNull("下一张还没打过分", vm.state.value.rating)
        assertEquals(SyncState.UNTOUCHED, vm.state.value.sync)
    }

    @Test
    fun right_swipe_on_a_four_star_photo_also_writes_nothing() = runTest {
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 4)))
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right)

        assertEquals(0, server.requestCount)
        assertEquals(4, store.get(paths[0]).rating)
        assertEquals("已保留 · 保持 4★", vm.state.value.swipeNote)
        assertEquals(1, vm.state.value.index)
    }

    @Test
    fun left_swipe_is_an_explicit_reject_and_is_allowed_to_downgrade_a_five() = runTest {
        // 上面两条的**对照**: 左滑是用户明确说"我不要这张", 允许降级。
        // 没有它, "不降级"就可能是因为"这个状态机根本不会降级"而通过。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 5)))
        enqueueSummary(written = 1)
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Left)

        assertEquals(PushResult.Ok(requested = 1, written = 1, skipped = 0, degraded = false), result)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":0"))
        assertEquals(0, store.get(paths[0]).rating)
        assertEquals(SyncState.SYNCED, store.get(paths[0]).sync)
        assertEquals(1, vm.state.value.index)
    }

    // ── ② 右滑没评过的 = 3 星, 而且只写星级 ──────────────────────────────────

    @Test
    fun right_swipe_on_an_unrated_photo_writes_exactly_three_stars_and_nothing_else() = runTest {
        enqueueSummary(written = 1)
        val store = store()
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Right)

        assertTrue(result is PushResult.Ok)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":3"))
        assertFalse("默认档(只写星级)不许带色标: $body", body.contains("\"label\""))
        assertFalse("更不许顺手清色标: $body", body.contains("\"clearLabel\""))
        assertEquals(3, store.get(paths[0]).rating)
        assertEquals(SyncState.SYNCED, store.get(paths[0]).sync)
        assertEquals("写成功才前进", 1, vm.state.value.index)
    }

    @Test
    fun right_swipe_brings_a_previously_rejected_photo_back_to_three() = runTest {
        // 0 星 = 明确废弃。用户改主意右滑, 该变回"保留" —— 否则右滑就成了"什么都不做"。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 0)))
        enqueueSummary(written = 1)
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":3"))
        assertEquals(3, store.get(paths[0]).rating)
    }

    // ── ③ 模式: 只写星级不碰色标 / 只写色标不碰星级 ──────────────────────────

    @Test
    fun stars_only_never_writes_the_swipe_colour_even_when_the_photo_has_a_label() = runTest {
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = null, label = "purple")))
        enqueueSummary(written = 1)
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right, SwipeConfig(mode = SwipeMode.StarsOnly))

        val body = server.takeRequest().body.readUtf8()
        assertTrue("星级照写: $body", body.contains("\"rating\":3"))
        assertFalse("只写星级这一档**绝不**写绿标: $body", body.contains("\"green\""))
        assertFalse("也不许写红标: $body", body.contains("\"red\""))
        assertFalse("更不许把色标清掉: $body", body.contains("\"clearLabel\""))
        assertTrue(
            "带出去的是它**原来那个**值(store 逐字发整份意图), 不是滑卡的目标色: $body",
            body.contains("\"label\":\"purple\""),
        )
        assertEquals("本地色标一个字节都没变", "purple", store.get(paths[0]).label)
        assertEquals(3, store.get(paths[0]).rating)
    }

    @Test
    fun stars_only_writes_nothing_when_the_stars_need_no_write_either() = runTest {
        // 已有 3 星 + 紫标: "只写星级"这一档右滑**没有任何东西要写** —— 所以一个请求都不发,
        // 色标也就绝无被碰到的机会。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 3, label = "purple")))
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Right, SwipeConfig(mode = SwipeMode.StarsOnly))

        assertEquals(PushResult.NothingToPush(1), result)
        assertEquals("一个字节都不发", 0, server.requestCount)
        assertEquals(3, store.get(paths[0]).rating)
        assertEquals("purple", store.get(paths[0]).label)
        assertEquals(1, vm.state.value.index)
    }

    @Test
    fun labels_only_never_sends_the_reject_stars() = runTest {
        // 电脑上给的 4 星: "只写色标"这一档左滑**不许**把它变成 0 星。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 4)))
        enqueueSummary(written = 1)
        val vm = culler(store)

        vm.swipe(SwipeDirection.Left, SwipeConfig(mode = SwipeMode.LabelsOnly))

        val body = server.takeRequest().body.readUtf8()
        assertTrue("色标照写: $body", body.contains("\"label\":\"red\""))
        assertFalse("只写色标这一档**绝不**写 0 星: $body", body.contains("\"rating\":0"))
        assertTrue("带出去的是原来那个 4 星: $body", body.contains("\"rating\":4"))
        assertEquals(4, store.get(paths[0]).rating)
        assertEquals("red", store.get(paths[0]).label)
    }

    @Test
    fun both_mode_writes_the_colour_and_still_keeps_the_five_stars() = runTest {
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 5)))
        enqueueSummary(written = 1)
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right, SwipeConfig(mode = SwipeMode.Both))

        val body = server.takeRequest().body.readUtf8()
        assertTrue("色标要写: $body", body.contains("\"label\":\"green\""))
        assertFalse("但星级绝不许被写成 3: $body", body.contains("\"rating\":3"))
        assertEquals(5, store.get(paths[0]).rating)
        // 命中"不降级"那一支时**照样**要给出反馈(用户得知道星级没被改动)
        assertEquals("已保留 · 保持 5★", vm.state.value.swipeNote)
    }

    @Test
    fun the_swipe_config_is_honoured_end_to_end_when_the_directions_are_swapped() = runTest {
        // 手势语义是**可配置**的: 换过方向之后, 右滑就是废弃。
        val store = store(listOf(DecisionReadDto(path = paths[0], rating = 5)))
        enqueueSummary(written = 1)
        val vm = culler(store)

        vm.swipe(SwipeDirection.Right, SwipeConfig().reversed())

        val body = server.takeRequest().body.readUtf8()
        assertTrue("互换之后右滑 = 废弃(0★): $body", body.contains("\"rating\":0"))
        assertEquals(0, store.get(paths[0]).rating)
    }

    // ── ④ 失败: 决定留着、不前进、可重试 ────────────────────────────────────

    @Test
    fun a_failed_swipe_keeps_the_decision_visible_and_does_not_advance() = runTest {
        // 写没成却滑到下一张 = 用户以为打过了, 而电脑上还是旧的。本任务最不能出的一种错。
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "busy")}]")
        val store = store()
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Left)

        val failed = result as PushResult.Failed
        assertEquals("busy", failed.failures.single().code)
        val st = vm.state.value
        assertEquals("写失败绝不前进", 0, st.index)
        assertEquals("用户刚才那个决定必须留着", 0, st.rating)
        assertEquals(SyncState.UNSYNCED, st.sync)
        assertTrue(st.hasUnsyncedChange)
        assertNotNull("失败卡要有话说(界面一个字都不拼)", st.writeText)
        assertEquals("busy", st.writeFailure?.code)
        assertEquals(1, st.unsyncedCount)
        assertEquals("1 张未同步·重试", st.unsyncedLabel)
    }

    @Test
    fun a_failed_swipe_survives_leaving_and_reopening_the_culling_page() = runTest {
        // 纪律③: "未同步 ≠ 已同步"必须活得比这一页久 —— store 由网格持有(生产上是这样)。
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "readOnly")}]")
        val store = store()
        culler(store).swipe(SwipeDirection.Left)

        val reopened = culler(store)

        assertEquals(0, reopened.state.value.rating)
        assertEquals(SyncState.UNSYNCED, reopened.state.value.sync)
        assertEquals(1, reopened.state.value.unsyncedCount)
        assertEquals("readOnly", reopened.state.value.writeFailure?.code)
    }

    @Test
    fun a_retry_after_a_failed_swipe_resends_the_same_intent_and_settles() = runTest {
        enqueueSummary(failed = 1, failures = "[${failJson(paths[0], "busy")}]")
        enqueueSummary(written = 1)
        val store = store()
        val vm = culler(store)
        vm.swipe(SwipeDirection.Left)
        assertEquals(0, vm.state.value.index)

        val retry = vm.retryCurrent()

        assertEquals(PushResult.Ok(1, 1, 0, false), retry)
        assertEquals("重试不产生新决策, 所以不前进", 0, vm.state.value.index)
        assertEquals(SyncState.SYNCED, vm.state.value.sync)
        assertEquals(0, vm.state.value.unsyncedCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertEquals("重试重发的是**同一份**意图, 不是一次空请求", first, second)
    }

    @Test
    fun a_network_failure_keeps_the_decision_and_does_not_advance() = runTest {
        // 电脑睡了 / 服务没开: 也是失败, 也必须留着决定且不前进。
        val store = store()
        val vm = culler(store)
        server.shutdown()

        val result = vm.swipe(SwipeDirection.Right)

        val failed = result as PushResult.Failed
        assertEquals(FailureSource.CLIENT, failed.failures.single().source)
        assertEquals(0, vm.state.value.index)
        assertEquals(3, vm.state.value.rating)
        assertEquals(SyncState.UNSYNCED, vm.state.value.sync)
        assertNotNull(vm.state.value.writeText)
    }

    @Test
    fun swiping_the_last_card_writes_it_but_has_nowhere_to_advance_to() = runTest {
        enqueueSummary(written = 1)
        val store = store()
        val vm = culler(store, startIndex = 3)

        val result = vm.swipe(SwipeDirection.Left)

        assertEquals(PushResult.Ok(1, 1, 0, false), result)
        assertEquals("到末尾就停住(**绝不**绕回第一张)", 3, vm.state.value.index)
        assertEquals(0, store.get(paths[3]).rating)
    }

    // ── ⑤ 滑的是"筛出来的那一批" + 预取(同一个预取器, 默认档, 向前 2 张) ──────

    @Test
    fun the_swipe_only_ever_touches_the_filtered_queue() = runTest {
        // 网格里有 3 张, 但用户把筛选设成"只要绿标", 于是只有 p2 在队列里。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + listOf(
                    photo(paths[0], "p1.jpg", 1_759_680_000_000L),
                    photo(paths[1], "p2.jpg", 1_759_680_001_000L),
                    photo(paths[2], "p3.jpg", 1_759_680_002_000L),
                ).joinToString(",") + "]",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + listOf(
                    decision(paths[0], null, null),
                    decision(paths[1], null, "green"),
                    decision(paths[2], null, null),
                ).joinToString(",") + "]",
            ),
        )
        enqueueSummary(written = 1)

        val grid = GridViewModel(pairing = pairing, folder = folder, clientFactory = factory)
        grid.loadAll()
        grid.setFilters(Filters(labels = setOf("green")))

        val queue = grid.state.value.visibleItems
        assertEquals("队列 = **筛出来的**那些(不是全部照片)", listOf(paths[1]), queue.map { it.path })
        assertEquals("当前显示 1 / 共 3 张", grid.state.value.shownLabel)

        // 滑卡页拿到的就是这一批(生产上由 GridScreen 冻结同一份 visibleItems 传进来)
        val vm = swipeViewModel(
            pairing = pairing,
            items = queue,
            startIndex = 0,
            thresholdPx = 250f,
            decisions = grid.decisions,
            prefetch = {},
        )
        vm.swipe(SwipeDirection.Left, SwipeConfig(mode = SwipeMode.Both))

        // 前两个请求是 scan 与 decisions(loadAll), 第三个才是这一次写入
        val scan = server.takeRequest()
        val decisions = server.takeRequest()
        val put = server.takeRequest()
        assertEquals("/api/v1/scan", scan.requestUrl!!.encodedPath)
        assertEquals("/api/v1/decisions", decisions.requestUrl!!.encodedPath)
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/decisions", put.requestUrl!!.encodedPath)
        val body = put.body.readUtf8()
        assertTrue(body, body.contains(jq(paths[1])))
        assertFalse("被筛掉的 p1 绝不许被滑到: $body", body.contains("p1.jpg"))
        assertFalse("被筛掉的 p3 绝不许被滑到: $body", body.contains("p3.jpg"))
        assertEquals("一共只有三个请求", 3, server.requestCount)
    }

    @Test
    fun the_next_cards_are_prefetched_at_the_default_step_and_never_high_res() = runTest {
        val prefetched = mutableListOf<List<String>>()
        val vm = culler(store(), prefetch = { prefetched += it })

        vm.open()

        assertEquals("规格 §8.2: 当前张 + 后 2 张", 2, SWIPE_PREFETCH_AHEAD)
        val urls = prefetched.single()
        assertEquals("第 0 张时没有左边那张, 所以是 +1 与 +2 两张", 2, urls.size)
        assertEquals(listOf(paths[1], paths[2]), urls.map { it.toHttpUrl().queryParameter("path") })
        for (u in urls) {
            val url = u.toHttpUrl()
            assertEquals("/api/v1/preview", url.encodedPath)
            assertEquals(
                "预取一律是**默认档** —— 高清是电脑全解码, 预取它等于每滑一张卡一次",
                PREVIEW_MAX_DEFAULT.toString(),
                url.queryParameter("max"),
            )
            assertNull("预取里不许出现 full: $u", url.queryParameter("full"))
        }

        vm.go(+1)

        assertEquals(
            "前进一步: 左边那张 + 后 2 张 —— 用的还是**同一个**预取器(绝不另写一套)",
            listOf(paths[0], paths[2], paths[3]),
            prefetched.last().map { it.toHttpUrl().queryParameter("path") },
        )
    }

    // ── 逐字写 JSON 的两个小工具(与 GridViewModelTest 同一套写法) ────────────

    private fun photo(path: String, name: String, mtime: Long) =
        """{"path":${jq(path)},"fileName":${jq(name)},"fileSize":4096,""" +
            """"isRaw":false,"isVideo":false,"modifiedAt":$mtime,"exif":{}}"""

    private fun decision(path: String, rating: Int?, label: String?) =
        """{"path":${jq(path)},"rating":${rating ?: "null"},""" +
            """"label":${if (label == null) "null" else jq(label)},"sidecar":null}"""
}
