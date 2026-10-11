// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.ReadMessages
import com.imagefilter.mobile.data.ReadSource
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.COUNT_UNKNOWN
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.Filters
import com.imagefilter.mobile.ui.GRID_COUNT_UNKNOWN
import com.imagefilter.mobile.ui.GRID_EMPTY
import com.imagefilter.mobile.ui.GridViewModel
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.filteredOutMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * **文件夹级决策读取**在网格状态机里的真行为: JVM + MockWebServer, 走真的 HTTP 与真的 [ApiClient]。
 *
 * 这里钉六件事, 每一件都对应一种真实会发生的坏结果:
 *   ① `loadAll` 真的按**这个文件夹的全部照片**去读(`paths` 是重复参数, 带鉴权头, 一条不漏);
 *   ② 读回来的值参与筛选与角标 —— 而**本地未同步的意图优先**那条规则在 store 里已经钉死
 *      (见 [DecisionMergeTest]), 这里钉它**一路传到界面上**;
 *   ③ **读失败不许吞**: 它落在 `state.readFailure` 上(带码 + 来源表), **且**把张数降级成"未统计";
 *   ④ **读得不全(200 但没全回报)也一样**: 已经回来的值照常采纳, 张数不敢说知道;
 *   ⑤ "有照片但全被筛掉"与"这个文件夹里没有照片"是两句不同的话;
 *   ⑥ 未同步角标与筛选栏的"只看未同步"用的是**同一份** store(查看器借的就是它)。
 *
 * ⚠️ 注入 client 时一律以 [buildHttpClient] 为底 —— 鉴权头**只**装在那里, 随手 `OkHttpClient()`
 * 会让"token 有没有发出去"这类断言变成假绿。
 */
class GridFilterTest {

    private lateinit var server: MockWebServer
    private val token = "f".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val verbatimFolder = "\\\\?\\D:\\照片 2026"
    private val folder = FolderEntryDto(path = verbatimFolder, name = "照片 2026")

    private val a = "$verbatimFolder\\a.jpg"
    private val b = "$verbatimFolder\\b.jpg"
    private val c = "$verbatimFolder\\c.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun viewModel() = GridViewModel(
        pairing = Pairing(baseUrl(), token, note = "家里的台式机"),
        folder = folder,
        clientFactory = { url, tkn ->
            ApiClient(
                url,
                tkn,
                buildHttpClient(tkn).newBuilder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(600, TimeUnit.MILLISECONDS)
                    .build(),
            )
        },
    )

    // ── 服务端响应(逐字写 JSON, 不拿 DTO 反向编码 —— 那样字段名写错会两侧一起错) ──

    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

    private fun photo(path: String, fileName: String, modifiedAt: Long = 1L) =
        """{"path":${jq(path)},"fileName":${jq(fileName)},"fileSize":4096,"isRaw":false,""" +
            """"isVideo":false,"modifiedAt":$modifiedAt,"exif":{}}"""

    private fun scanOk(vararg items: String) =
        MockResponse().setResponseCode(200).setBody("[${items.joinToString(",")}]")

    private fun decision(path: String, rating: Int? = null, label: String? = null) =
        """{"path":${jq(path)},"rating":${rating ?: "null"},"label":${label?.let { "\"$it\"" } ?: "null"},"sidecar":null}"""

    private fun readOk(vararg items: String) =
        MockResponse().setResponseCode(200).setBody("[${items.joinToString(",")}]")

    private fun summaryOk(written: Int = 1) = MockResponse().setResponseCode(200).setBody(
        """{"written":$written,"skipped":0,"failed":0,"failures":[],"degraded":false}""",
    )

    // ── ① 读的是这个文件夹的全部照片 ─────────────────────────────────────────

    @Test
    fun load_all_reads_the_decisions_for_every_photo_in_this_folder_with_the_auth_header() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg"), photo(c, "c.jpg")))
        server.enqueue(readOk(decision(a, rating = 5), decision(b, rating = 2), decision(c, rating = 4)))

        val vm = viewModel()
        vm.loadAll()

        val s = vm.state.value
        assertEquals(3, s.items.size)
        assertTrue("读全了 ⇒ 张数可以是真的数", s.decisionsKnown)
        assertEquals(5, s.decisions[a]?.rating)
        assertEquals(SyncState.UNTOUCHED, s.decisions[a]?.sync)
        assertNull("读成功时不该有失败状态", s.readFailure)

        assertEquals("/api/v1/scan", server.takeRequest().requestUrl!!.encodedPath)
        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/api/v1/decisions", read.requestUrl!!.encodedPath)
        // 决策 store 与 scan/thumb 是**同一个**带鉴权头的 client(生产路径上就是共享的那一枚)
        assertEquals("Bearer $token", read.getHeader("Authorization"))
        assertEquals("一条都不许漏(顺序逐字: 服务端按参数顺序回报)", listOf(a, b, c), read.requestUrl!!.queryParameterValues("paths"))
        // verbatim 路径里的字面问号必须逐字往返
        assertEquals(a, read.requestUrl!!.queryParameter("paths"))
    }

    @Test
    fun a_successful_read_turns_the_counts_into_real_numbers() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg"), photo(c, "c.jpg")))
        server.enqueue(
            readOk(decision(a, rating = 5, label = "green"), decision(b, rating = 2), decision(c)),
        )

        val vm = viewModel()
        vm.loadAll()

        val counts = vm.state.value.counts
        assertEquals("2 张 ≥2 星(a 5 / b 2)", "2", counts.starText(2))
        assertEquals("1 张 ≥5 星", "1", counts.starText(5))
        assertEquals("1 张绿标", "1", counts.labelText("green"))
        assertEquals("c 在电脑上也没有决策 ⇒ 它不在任何色标里", "0", counts.labelText("red"))
        assertEquals(0, counts.unsynced)
    }

    // ── ② 本地未同步的意图一路传到界面 ───────────────────────────────────────

    @Test
    fun a_local_unsynced_rating_survives_the_folder_read_and_still_shows_in_the_grid() = runTest {
        // 查看器(借的就是同一份 store)先打了 4 星 —— 还没发出去。然后网格读了一遍电脑上的值(2 星)。
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))
        server.enqueue(readOk(decision(a, rating = 2), decision(b, rating = 3)))

        val vm = viewModel()
        vm.decisions.applyLocal(Decision(a).withRating(4))   // 离线打的 4 星
        vm.loadAll()

        val s = vm.state.value
        assertEquals("离线打的 4 星不许被电脑上的 2 星盖掉", 4, s.decisions[a]?.rating)
        assertEquals(SyncState.UNSYNCED, s.decisions[a]?.sync)
        assertEquals("另一张没动过 ⇒ 采纳电脑上的 3 星", 3, s.decisions[b]?.rating)

        // 角标与筛选栏的第三维都在说这件事(纪律③的可见性)
        assertEquals(1, s.unsyncedCount)
        assertEquals("1 张未同步·重试", s.unsyncedLabel)

        // 只看未同步 ⇒ 只剩 a
        vm.setFilters(Filters(onlyUnsynced = true))
        assertEquals(listOf(a), vm.state.value.visibleItems.map { it.path })

        // 而且它是**同一份** store: 网格的角标、筛选栏的第三维与查看器写的必须是同一个对象
        // (把 `by lazy` 改成"每次取都新建一个", 这一条就会红 —— 那时格子上显示 4 星、
        //  查看器里显示 2 星只是时间问题)。
        assertSame(vm.decisions, vm.decisions)
        assertEquals(4, vm.decisions.get(a).rating)
    }

    @Test
    fun the_unsynced_badge_retries_through_the_same_store_and_disappears_when_the_pc_confirms() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))
        server.enqueue(readOk(decision(a), decision(b)))
        server.enqueue(summaryOk(written = 1))

        val vm = viewModel()
        vm.loadAll()
        vm.decisions.applyLocal(Decision(a).withRating(4))
        vm.refreshDecisions()   // 关掉查看器回到网格那一刻会做这件事
        assertEquals("1 张未同步·重试", vm.state.value.unsyncedLabel)

        val result = vm.retryUnsynced()

        assertTrue(result is PushResult.Ok)
        assertNull("服务端确认了 ⇒ 角标必须消失", vm.state.value.unsyncedLabel)
        assertEquals(0, vm.state.value.unsyncedCount)
        assertEquals(SyncState.SYNCED, vm.state.value.decisions[a]?.sync)

        assertEquals("/api/v1/scan", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/v1/decisions", server.takeRequest().requestUrl!!.encodedPath)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/decisions", put.requestUrl!!.encodedPath)
        assertTrue("发出去的就是那一张的意图", put.body.readUtf8().contains("\"rating\":4"))
    }

    @Test
    fun a_partial_retry_says_how_many_of_the_batch_did_not_land() = runTest {
        // 半成功态必须显式(纪律②): 角标上少两张, 用户会以为它们已经落到电脑上了。
        val failA = """{"path":${jq(a)},"code":"readOnly","detail":""}"""
        val failB = """{"path":${jq(b)},"code":"readOnly","detail":""}"""
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))
        server.enqueue(readOk(decision(a), decision(b)))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"written":0,"skipped":0,"failed":2,"failures":[$failA,$failB],"degraded":false}""",
            ),
        )

        val vm = viewModel()
        vm.loadAll()
        vm.decisions.applyLocal(Decision(a).withRating(4))
        vm.decisions.applyLocal(Decision(b).withRating(5))
        vm.refreshDecisions()

        val result = vm.retryUnsynced()

        assertTrue(result is PushResult.Failed)
        assertEquals("这一批 2 张里有 2 张没写进电脑。", vm.state.value.lastBatchNote)
        assertEquals("没写成的两张仍然标着未同步", 2, vm.state.value.unsyncedCount)
        assertEquals(2, vm.decisions.unsynced().size)
    }

    // ── ③ 读失败: 显示成状态 + 张数降级 ──────────────────────────────────────

    @Test
    fun a_failed_decision_read_is_shown_as_state_and_never_claims_zero() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))

        val vm = viewModel()
        vm.loadAll()

        val s = vm.state.value
        // 照片照旧能看(读决策失败**不影响**文件列表)
        assertEquals(2, s.items.size)
        assertEquals(2, s.visibleItems.size)
        assertNull("文件列表本身没出错", s.error)

        // 失败**没有被吞掉**
        val f = s.readFailure
        assertNotNull("读取失败必须是一个状态, 不是一句 log", f)
        assertEquals(ApiErrorCodes.INTERNAL, f!!.code)
        assertEquals(ReadSource.HTTP, f.source)
        assertEquals(ReadMessages.headFor(f), s.readFailureHead)
        assertEquals(ReadMessages.forFailure(f), s.readFailureText)

        // 张数降级成"未统计" —— 不是 0, 也不是"按已知的那些数一数"
        assertFalse(s.decisionsKnown)
        assertEquals(COUNT_UNKNOWN, s.counts.starText(3))
        assertNotEquals("0", s.counts.starText(3))
        assertTrue(s.readFailureText!!.contains(ReadMessages.COUNTS_UNKNOWN_TAIL))
    }

    @Test
    fun dismissing_the_read_failure_card_does_not_pretend_the_counts_were_read() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg")))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))

        val vm = viewModel()
        vm.loadAll()
        vm.clearReadFailure()

        val s = vm.state.value
        assertNull(s.readFailure)
        assertFalse("收起来 ≠ 读到了: 张数照旧说'未统计'", s.decisionsKnown)
        assertEquals(COUNT_UNKNOWN, s.counts.starText(3))
        assertEquals("而且'重试读取'还能再试一次(状态还在)", 1, s.items.size)
    }

    @Test
    fun a_401_on_the_decision_read_is_disambiguated_and_the_photos_still_show() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg")))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))

        val vm = viewModel()
        vm.loadAll()

        val f = vm.state.value.readFailure!!
        assertEquals(ApiErrorCodes.UNAUTHORIZED, f.code)
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, f.cause)
        assertEquals(1, vm.state.value.visibleItems.size)

        assertEquals("/api/v1/scan", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/v1/decisions", server.takeRequest().requestUrl!!.encodedPath)
        // 复探打的是同一个已知受保护端点
        assertEquals("/api/v1/shares", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun a_dead_service_on_the_decision_read_is_a_client_code_and_the_photos_still_show() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg")))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))

        val vm = viewModel()
        vm.loadAll()

        val f = vm.state.value.readFailure!!
        assertEquals(ReadSource.CLIENT, f.source)
        assertEquals(ApiErrorCodes.TIMEOUT, f.code)
        assertEquals(1, vm.state.value.visibleItems.size)
        assertEquals(COUNT_UNKNOWN, vm.state.value.counts.starText(1))
    }

    // ── ④ 读得不全(200 但没全回报) ──────────────────────────────────────────

    @Test
    fun a_partial_read_adopts_what_came_back_but_still_says_the_counts_are_unknown() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg"), photo(c, "c.jpg")))
        // 问了三张, 只回来两张
        server.enqueue(readOk(decision(a, rating = 5), decision(b, rating = 2)))

        val vm = viewModel()
        vm.loadAll()

        val s = vm.state.value
        assertEquals("已经回来的那些是真值, 照常采纳", 5, s.decisions[a]?.rating)
        assertEquals(2, s.decisions[b]?.rating)
        assertNull("没回来的那张不许编一个值", s.decisions[c])

        val f = s.readFailure
        assertNotNull("没回报全 = 没有结论, 必须看得见", f)
        assertEquals(ReadMessages.INCOMPLETE, f!!.code)
        assertEquals(ReadSource.CLIENT, f.source)
        assertFalse(s.decisionsKnown)
        // 同一件事的**另一个粒度**: 网格说"这个文件夹的张数不可信", store 说"这两张知道、
        // 那一张不知道" —— 两者都由同一次回报决定, 所以在这里一起断言(只钉一边的话,
        // 改坏另一边不会有任何测试红)。
        assertTrue("回报了的那张: 手机知道电脑上是什么", vm.decisions.knows(a))
        assertTrue(vm.decisions.knows(b))
        assertFalse("没回报的那张: 不知道 —— 保留那一支就是靠这条不写", vm.decisions.knows(c))
        // 只数已知的那两张会得到 "1"(≥5 星) —— 那是**偏小的下界**, 不许当成答案
        assertEquals(COUNT_UNKNOWN, s.counts.starText(5))
        assertNotEquals("1", s.counts.starText(5))
        assertNotEquals("0", s.counts.starText(5))
    }

    @Test
    fun loading_only_the_file_list_leaves_the_counts_unknown_until_the_decisions_arrive() = runTest {
        // 只调 load()(文件列表)时, 张数**必须**说"未统计" —— 拿 0 顶上就是替电脑保证了一件
        // 它还没说过的事。这一条也是"两步都要有人调"的可测形态。
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))

        val vm = viewModel()
        vm.load()

        val s = vm.state.value
        assertEquals(2, s.items.size)
        assertFalse(s.decisionsKnown)
        assertEquals(COUNT_UNKNOWN, s.counts.starText(1))
        assertEquals(
            "一个决策请求都不该发出去(那是 loadAll/loadDecisions 的事)",
            1,
            server.requestCount,
        )
    }

    @Test
    fun an_empty_folder_is_truly_zero_and_does_not_ask_the_pc_for_decisions() = runTest {
        // 空文件夹的张数是**真的 0**(与浏览页的"未统计"相反: 没有照片就没有"电脑上还有几张打了星"),
        // 而且一个请求都不发(readDecisions 对空数组是 badRequest)。
        server.enqueue(scanOk())

        val vm = viewModel()
        vm.loadAll()

        val s = vm.state.value
        assertTrue(s.isEmpty)
        assertEquals("0 张", s.countLabel)
        assertEquals(GRID_EMPTY, s.emptyMessage)
        assertTrue("空文件夹的筛选栏张数是真的 0", s.decisionsKnown)
        assertEquals("0", s.counts.starText(1))
        assertNull(s.readFailure)
        assertEquals("只发了 scan 这一次", 1, server.requestCount)
    }

    @Test
    fun a_failed_scan_does_not_ask_for_decisions_and_never_claims_zero_photos() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"notFound"}"""))

        val vm = viewModel()
        vm.loadAll()

        val s = vm.state.value
        assertEquals(ApiErrorCodes.NOT_FOUND, s.error!!.code)
        assertEquals(GRID_COUNT_UNKNOWN, s.countLabel)
        assertNotEquals("0 张", s.countLabel)
        assertFalse("连有哪些照片都不知道, 更不该说决策是知道的", s.decisionsKnown)
        assertEquals(COUNT_UNKNOWN, s.counts.starText(1))
        assertNull("文件列表都没读到, 不去读决策(那些路径是旧的)", s.readFailure)
        assertEquals("404 只需要一次 scan", 1, server.requestCount)
    }

    // ── ⑤ 筛选: 三个维度 + "全被筛掉"与"没有照片"是两句话 ────────────────────

    @Test
    fun the_filters_decide_which_cells_are_drawn_and_the_counts_are_real_when_the_read_succeeded() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg"), photo(c, "c.jpg")))
        server.enqueue(
            readOk(
                decision(a, rating = 5, label = "green"),
                decision(b, rating = 2, label = "red"),
                decision(c),
            ),
        )

        val vm = viewModel()
        vm.loadAll()

        assertTrue("三条都回报了 ⇒ 张数是真的数", vm.state.value.decisionsKnown)

        vm.setFilters(Filters(minStars = 3))
        assertEquals(listOf(a), vm.state.value.visibleItems.map { it.path })
        assertEquals("当前显示 1 / 共 3 张", vm.state.value.shownLabel)
        assertEquals("≥3★", vm.state.value.filters.summary)

        vm.setFilters(Filters(minStars = 3, labels = setOf("red")))
        assertEquals("与关系: b 是红标但只有 2 星 ⇒ 空", 0, vm.state.value.visibleItems.size)
        assertTrue(vm.state.value.allFilteredOut)
        assertFalse("有照片才算'全被筛掉', 不能与'空文件夹'混", vm.state.value.isEmpty)

        vm.clearFilters()
        assertEquals(3, vm.state.value.visibleItems.size)
        assertFalse(vm.state.value.allFilteredOut)
        assertNull(vm.state.value.filters.summary)
    }

    @Test
    fun a_fully_filtered_out_folder_says_so_instead_of_claiming_there_are_no_photos() = runTest {
        server.enqueue(scanOk(photo(a, "a.jpg"), photo(b, "b.jpg")))
        server.enqueue(readOk(decision(a, rating = 5), decision(b, rating = 2)))

        val vm = viewModel()
        vm.loadAll()
        vm.setFilters(Filters(minStars = 5, labels = setOf("purple")))

        val s = vm.state.value
        assertTrue(s.hasPhotos)
        assertFalse("有照片, 只是被筛掉了", s.isEmpty)
        assertNull("不许说成'电脑扫描返回 0 张照片'", s.emptyMessage)
        assertEquals(filteredOutMessage(2), s.filteredOutNotice)
        assertNotEquals(GRID_EMPTY, s.filteredOutNotice)
        assertFalse(s.hasVisiblePhotos)
    }

    @Test
    fun the_all_raw_notice_only_talks_about_the_cells_that_are_actually_drawn() = runTest {
        val raw = """{"path":${jq(a)},"fileName":"a.arw","fileSize":4096,"isRaw":true,""" +
            """"isVideo":false,"modifiedAt":1,"exif":{}}"""
        val jpg = photo(b, "b.jpg")
        server.enqueue(scanOk(raw, jpg))
        server.enqueue(readOk(decision(a, rating = 5), decision(b, rating = 2)))

        val vm = viewModel()
        vm.loadAll()
        assertNull("混着 JPEG 时不该有全 RAW 提示", vm.state.value.notice)

        // 筛到只剩那张 RAW ⇒ 现在画出来的**全是** RAW, 提示才有意义
        vm.setFilters(Filters(minStars = 5))
        assertEquals(listOf(a), vm.state.value.visibleItems.map { it.path })
        assertNotNull("画出来的全是 RAW 时才提示", vm.state.value.notice)
    }
}
