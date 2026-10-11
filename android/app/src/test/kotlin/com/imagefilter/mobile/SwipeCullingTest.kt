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
import com.imagefilter.mobile.ui.KEEP_BLIND_MESSAGE
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.RemotePull
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

    /** 一次文件夹级决策读取的响应体(逐字拼 JSON, 与线上拼写一致)。 */
    private fun enqueueRead(vararg items: String) = server.enqueue(
        MockResponse().setResponseCode(200).setBody("[${items.joinToString(",")}]"),
    )

    /**
     * 取走 `consumedBefore` 之后**新收到**的那些请求, 并逐条说清"方法 + 路径 + 请求体"。
     *
     * 用在"**一个请求都不该发**"的断言上: 只断言请求数的话, 红的时候只看到一句
     * "expected 1 but was 2", 还得回去猜它到底发了什么 —— 而这里要证明的恰恰是
     * "它发了一次**降级写入**"。正确实现下这里返回空列表(所以它不会阻塞)。
     */
    private fun newRequests(consumedBefore: Int): List<String> =
        (0 until (server.requestCount - consumedBefore)).map {
            val r = server.takeRequest()
            "${r.method} ${r.path} ${r.body.readUtf8()}"
        }

    /**
     * 这一份 store 就是网格手里那一份(滑卡页与网格**共用**它)。
     *
     * [seed] 是"电脑上已经有的值"(走真的 `mergeRemote` 那条本地/远端优先级入口), 不是
     * `applyLocal` —— 那一条差别很要紧: 远端值**不是**这台手机上的意图, 也不该带"未同步"。
     *
     * ⚠️ 它同时决定"这台手机**知不知道**电脑上是什么"([DecisionStore.knows]): 右滑(保留)
     * 只在"知道"时才写 3 星, 所以**"没评过 ⇒ 写 3 星"这种用例必须先让服务端回报过它**
     * (传一条 `DecisionReadDto(path = …)` 就行 —— 两个 null 也是服务端的明确回答)。
     * 少了这一步的夹具测的是"读取失败"那种情形, 而它**不该**写(见下面 ①·2 那一组)。
     */
    private fun store(seed: List<DecisionReadDto> = emptyList()): DecisionStore {
        val s = DecisionStore(factory(base, token))
        if (seed.isNotEmpty()) s.mergeRemote(seed)
        return s
    }

    /** 服务端明确回报"这一张没有星级/色标"的种子 —— "没评过 ⇒ 右滑给 3 星"那条的前提。 */
    private fun unrated(vararg paths: String) = paths.map { DecisionReadDto(path = it) }

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

    // ── ①·2 读取失败 / 部分回报: 保留**一个字节都不许写**(本轮修的静默降级口子) ──────

    @Test
    fun a_keep_swipe_writes_nothing_when_the_decisions_read_failed() = runTest {
        // 电脑上这张是 4 星 —— 下面那次**成功**的读取会把它逐字读回来, 那就是"真值"。
        // 但这一次进这个文件夹, 决策读取**失败**了: 这台手机不知道电脑上是几星。
        // 老实现这里会发 `rating: 3`, 把电脑上的 4 星**静默降级**(本任务最贵的一种失败)。
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        // 后面这条 summary 是给"**万一**真的发了写入"准备的(修复前就会发一次): 有了它, 那一次
        // 写入会拿到响应, 于是本用例红在下面那条**没有新请求**的断言上(一句看得懂的话, 而且带着
        // 那一次请求的方法/路径/请求体), 而不是因为没人应答挂到超时。
        enqueueSummary(written = 1)
        val store = store()
        assertTrue(store.pullRemote(paths) is RemotePull.Failed)
        assertFalse("失败的读取没有让手机知道任何一张是什么", store.knows(paths[0]))
        val read = server.takeRequest()          // 把那次失败的读走, 下面只对账**新**请求
        assertEquals("GET", read.method)
        assertEquals("/api/v1/decisions", read.requestUrl!!.encodedPath)
        val consumed = server.requestCount
        val vm = culler(store)

        val result = vm.swipe(SwipeDirection.Right)

        val stray = newRequests(consumed)
        assertEquals(
            "不知道电脑上是几星 ⇒ 保留**一个请求都不发**(否则桌面端的 4 星会被降成 3 星)",
            emptyList<String>(),
            stray,
        )
        assertEquals(PushResult.NothingToPush(1), result)
        assertTrue("本地也不许凭空多一条意图 —— 那会让'未同步 N 张'这个角标撒谎", store.all().isEmpty())
        assertEquals(KEEP_BLIND_MESSAGE, vm.state.value.swipeNote)
        assertEquals("被拦住时**不前进**: 前进会让用户以为这一下记下了", 0, vm.state.value.index)
        assertNull("下一张还没打过分", vm.state.value.rating)
        // 注: 队列里那次 summary 一直没被用掉 —— 正确实现下这一滑一个请求都不发。
    }

    @Test
    fun a_successful_read_of_a_four_star_photo_also_writes_nothing() = runTest {
        // 上面那条的**对照**, 而且这里的"4 星"是**服务端读回来的真值**(不是测试自己塞进去的
        // 一句声明): 同一张、同一次右滑, 只是这次**知道** ⇒ 一个请求都不发, 但说的话不一样、
        // 而且**要前进**(规格 §8.2: 命中"不降级"那一支时不写也要前进)。
        enqueueRead(decision(paths[0], rating = 4))
        val store = store()
        assertTrue(store.pullRemote(listOf(paths[0])) is RemotePull.Merged)
        assertEquals("电脑上就是 4 星 —— 读取失败那条用例里的那一滑本来会把它毁掉", 4, store.get(paths[0]).rating)
        val vm = culler(store)
        val reads = server.requestCount

        vm.swipe(SwipeDirection.Right)

        assertEquals("知道是 4 星 ⇒ 同样一个请求都不发(不降级)", reads, server.requestCount)
        assertEquals("已保留 · 保持 4★", vm.state.value.swipeNote)
        assertEquals("这一支要前进", 1, vm.state.value.index)
        assertEquals("电脑上那份 4 星原封不动", 4, store.get(paths[0]).rating)
    }

    @Test
    fun a_keep_swipe_writes_nothing_for_a_path_the_partial_read_did_not_report() = runTest {
        // 问了两张, 回来的只有第 2 张(服务端**明确**回报它没有星级)。第 1 张在电脑上是 4 星
        // —— 它没被回报, 这台手机就**不知道**; 老实现会把它降成 3 星。
        enqueueRead(decision(paths[1]))
        // 同上: 后面这次 summary 在**修复后**的代码里会被下面那次"对照"的写入用掉; 在修复前
        // 的代码里它会被"没被回报那张"误发的那次写入用掉, 于是本用例红在请求数断言上。
        enqueueSummary(written = 1)
        val store = store()

        val pull = store.pullRemote(listOf(paths[0], paths[1]))

        assertTrue("少回报一条 ⇒ 没有结论", pull is RemotePull.Partial)
        assertFalse("没回报的那张: 不知道(它在电脑上是 4 星)", store.knows(paths[0]))
        assertTrue("回报了的那张: 知道", store.knows(paths[1]))
        val read = server.takeRequest()                  // 把那次读走, 下面只对账**新**请求
        assertEquals("/api/v1/decisions", read.requestUrl!!.encodedPath)
        val consumed = server.requestCount

        val vm = culler(store)
        val blocked = vm.swipe(SwipeDirection.Right)      // 第 0 张 = 没被回报的那张

        val stray = newRequests(consumed)
        assertEquals(
            "没被回报 ⇒ 一个请求都不发" +
                if (stray.isEmpty()) "" else "；实际发了: $stray",
            emptyList<String>(),
            stray,
        )
        assertEquals(PushResult.NothingToPush(1), blocked)
        assertEquals(KEEP_BLIND_MESSAGE, vm.state.value.swipeNote)
        assertEquals(0, vm.state.value.index)

        // **对照**(同一份读回来的东西): 第 2 张是服务端明确说"没有星级"的 ⇒ 右滑照写 3 星。
        // 两张的差别**只有**"被回报了没有", 于是这一组用例把"知道/不知道"钉成了可断言的事实。
        // (上面那次 summary 一直排在队列里没被用过 —— 正确实现下这一滑一个请求都不发。)
        vm.go(+1)
        val result = vm.swipe(SwipeDirection.Right)

        assertTrue(result is PushResult.Ok)
        val put = server.takeRequest()      // 读已经被取走了, 这里第一个就是这一次写入
        assertEquals("PUT", put.method)
        val body = put.body.readUtf8()
        assertTrue(body, body.contains(jq(paths[1])))
        assertTrue("明确回报'没有星级' ⇒ 照写 3 星: $body", body.contains("\"rating\":3"))
        assertEquals(3, store.get(paths[1]).rating)
    }

    // ── ② 右滑没评过的 = 3 星, 而且只写星级 ──────────────────────────────────

    @Test
    fun right_swipe_on_an_unrated_photo_writes_exactly_three_stars_and_nothing_else() = runTest {
        enqueueSummary(written = 1)
        // 前提: 服务端**回报过**这一张(两个 null = "电脑上确实没有星级")。少了这一步,
        // 这台手机就是"不知道", 而"不知道"那一支**不许**写 3 —— 那是本轮修的降级口子。
        val store = store(unrated(paths[0]))
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
        // (前提同上: 先把"电脑上没有星级"读回来 —— 否则这一滑会被"不知道"那道闸拦住,
        //  测到的就不是"写失败"这条链路了。)
        val store = store(unrated(paths[0]))
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

    /** `xmp.rs::DecisionRead` 的一项, 逐字按线上拼写写 JSON(不拿 DTO 反向编码)。 */
    private fun decision(path: String, rating: Int? = null, label: String? = null) =
        """{"path":${jq(path)},"rating":${rating ?: "null"},""" +
            """"label":${if (label == null) "null" else jq(label)},"sidecar":null}"""
}
