// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.ReadMessages
import com.imagefilter.mobile.data.ReadSource
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.DesktopRating
import com.imagefilter.mobile.ui.MergeOutcome
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.RemotePull
import com.imagefilter.mobile.ui.SyncState
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
import java.util.concurrent.TimeUnit

/**
 * **文件夹级远端读取的合并**的真行为测试: JVM + MockWebServer, 走真的 HTTP 与真的 [ApiClient]。
 *
 * 这是 M2′ Task 3 的核心不变量, 也是上一轮明确交接给本轮的那一条:
 *
 * > **本地没发出去的意图, 绝不被服务端的旧值静默盖掉。**
 *
 * 一个用户离线给某张打了 4 星(未同步)、而电脑上还是 2 星时, 界面必须继续显示 4 星 + 未同步角标。
 * 反过来(远端优先)会让星星自己变回 2, 而且**没有任何提示** —— 这是规格 §11 纪律③
 * ("未同步 ≠ 已同步")最贵的一种失败。
 *
 * ⚠️ 这一组测试**刻意不让"合并根本没跑"也能通过**: 每一条优先级用例里都同时有一张
 * "本地没动过"的照片, 它的断言要求**必须**采纳服务端的值。于是两种错法都会红:
 * 优先级反了 → 本地那张红; 合并是空操作 → 远端那张红。只断言"最后还是 4"的话, 后者会蒙过去。
 *
 * 注入的 client 一律是 [buildHttpClient]`(token)` —— 自己造 `OkHttpClient()` 等于把鉴权头丢了,
 * 那样"读请求有没有带 token"就永远测不到(有专门一条断言它)。
 */
class DecisionMergeTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "m".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有一个**字面问号**, 路径里有空格和中文。 */
    private val folder = "\\\\?\\D:\\照片 2026"
    private val path = "$folder\\a.jpg"
    private val other = "$folder\\b.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun store() = DecisionStore(ApiClient(base, token, buildHttpClient(token)))

    /** 把真实路径转成它能出现在 JSON 字符串里的形态(每个反斜杠都要成对)。 */
    private fun jsonPath(p: String) = p.replace("\\", "\\\\")

    /** `xmp.rs::DecisionRead` 的一项, 逐字按线上拼写写 JSON(不拿 DTO 反向编码)。 */
    private fun readJson(p: String, rating: Int? = null, label: String? = null): String {
        val r = rating?.toString() ?: "null"
        val l = label?.let { "\"$it\"" } ?: "null"
        return """{"path":"${jsonPath(p)}","rating":$r,"label":$l,"sidecar":null}"""
    }

    private fun enqueueRead(vararg items: String) = server.enqueue(
        MockResponse().setResponseCode(200).setBody("[${items.joinToString(",")}]"),
    )

    /**
     * 带**可选** `sidecarReadable` 的那一项(协议新增的信号)。
     *
     * `null` = **不发这个键** —— 老服务端就是这样, 而"缺字段"必须与 `true` 同义(兼容性)。
     */
    private fun readJsonWith(p: String, sidecarReadable: Boolean?): String {
        val jp = jsonPath(p)
        val signal = if (sidecarReadable == null) "" else ""","sidecarReadable":$sidecarReadable"""
        return """{"path":"$jp","rating":null,"label":null,"sidecar":"$jp.xmp"$signal}"""
    }

    private fun enqueueSummaryOk(written: Int = 1) = server.enqueue(
        MockResponse().setResponseCode(200)
            .setBody("""{"written":$written,"skipped":0,"failed":0,"failures":[],"degraded":false}"""),
    )

    // ── ① 核心不变量: 本地未同步的意图优先 ───────────────────────────────────

    @Test
    fun a_local_unsynced_rating_beats_the_servers_older_value() = runTest {
        // 电脑上这张还是 2 星(手机上的 4 星因为离线, 一个字节都没发出去)
        enqueueRead(readJson(path, rating = 2), readJson(other, rating = 3))
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        val pull = s.pullRemote(listOf(path, other))

        // ① **合并真的跑了**: 本地没动过的那张必须采纳服务端的值。
        //    (少了这一半, "最后还是 4" 可能只是因为整个合并是个空操作 —— 那就是假绿。)
        val merged = pull as RemotePull.Merged
        assertEquals(2, merged.requested)
        assertEquals("本地没动过的那张要被采纳", 1, merged.merge.adopted)
        assertEquals("本地动过的那张要被挡住", 1, merged.merge.keptLocal)
        assertEquals(3, s.get(other).rating)
        assertEquals(SyncState.UNTOUCHED, s.get(other).sync)

        // ② 本地这份**还没发出去**的意图一个字都没被改
        val kept = s.get(path)
        assertEquals("离线打的 4 星不许被电脑上的 2 星盖掉", 4, kept.rating)
        // 把所有可能的错值都点一遍, 而不是只跟 2 比: 远端值 / "没有"(null) / 0(废弃)
        assertNotEquals(2, kept.rating)
        assertNotNull("不许把这一张变成'没有决策'", kept.rating)
        assertNotEquals(0, kept.rating)
        assertEquals(
            "它仍然未同步 —— '还没写成'这件事不许被一次读取抹掉",
            SyncState.UNSYNCED,
            kept.sync,
        )
        assertEquals("未同步角标里必须还有它", listOf(path), s.unsynced().map { it.path })

        // ③ 客户端看这一页用的是 resolved(): 两份镜像合成之后也是 4/未同步
        assertEquals(4, s.resolved()[path]?.rating)
        assertEquals(SyncState.UNSYNCED, s.resolved()[path]?.sync)
    }

    @Test
    fun a_local_unsynced_label_is_not_overwritten_by_the_servers_older_label() = runTest {
        // 手机上刚点成红标(未同步), 电脑上还是绿标 —— 红标必须留着。
        enqueueRead(readJson(path, rating = 2, label = "green"))
        val s = store()
        s.applyLocal(Decision(path).withRating(4).withLabel("red"))

        s.pullRemote(listOf(path))

        assertEquals("red", s.get(path).label)
        assertNotEquals("green", s.get(path).label)
        assertNotNull(s.get(path).label)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
    }

    @Test
    fun a_synced_value_is_not_flipped_back_by_a_read_that_could_be_older_than_the_write() = runTest {
        // "刷新"(读)与"打分"(写)是并发的两条链: 一次**比我们的 PUT 更早发出**的 GET 回来时会
        // 带着旧值。采纳它就会让屏幕上已经落定的那张自己跳回旧值 —— 与"静默覆盖"是同一类事故,
        // 所以本地动过的(含已同步的)**一律**以本地为准。
        enqueueSummaryOk(written = 1)          // PUT 的回报
        enqueueRead(readJson(path, rating = 2)) // 之后的一次读取(可能是旧快照)
        val s = store()
        s.applyLocal(Decision(path).withRating(4))
        assertTrue(s.push(listOf(path)) is PushResult.Ok)
        assertEquals(SyncState.SYNCED, s.get(path).sync)

        s.pullRemote(listOf(path))

        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.SYNCED, s.get(path).sync)
    }

    // ── ② 本地没动过的: 照单全收(这一半让上一条不是空断言) ──────────────────

    @Test
    fun a_photo_the_phone_never_touched_adopts_what_the_pc_says() = runTest {
        // 计划 Task 2 Step 5 的第 4 条: 电脑上给某张打 2 星, 手机上重新进这个文件夹 → 显示 2 星。
        enqueueRead(readJson(path, rating = 2, label = "blue"))
        val s = store()

        val pull = s.pullRemote(listOf(path))

        assertEquals(RemotePull.Merged(1, MergeOutcome(adopted = 1, keptLocal = 0, cleared = 0)), pull)
        assertEquals(2, s.get(path).rating)
        assertEquals("blue", s.get(path).label)
        assertEquals(
            "电脑上的值不是这台手机上的意图 —— 它不该被标成未同步/已同步",
            SyncState.UNTOUCHED,
            s.get(path).sync,
        )
        assertTrue(s.unsynced().isEmpty())
    }

    @Test
    fun a_value_that_only_came_from_the_pc_is_never_pushed_back() = runTest {
        // 纪律①"绝不静默覆盖": 远端值要是混进"本地意图", 下一次推送就会把用户没动过的字段
        // 发回电脑(一次没有用户的写入)。这里钉住"远端值永远进不了推送"。
        val s = store()
        s.mergeRemote(listOf(DecisionReadDto(path = path, rating = 3, label = "green")))

        assertEquals(3, s.get(path).rating)
        assertTrue("电脑上的值不是本地意图", s.unsynced().isEmpty())
        assertEquals(PushResult.NothingToPush(1), s.push(listOf(path)))
        assertEquals("一个请求都不许发出去", 0, server.requestCount)
    }

    @Test
    fun a_remote_value_the_pc_no_longer_has_is_cleared_instead_of_lingering() = runTest {
        // 电脑上把评分清掉了(rating/label 都是 null)⇒ 这台手机上的角标也必须跟着消失。
        // 留着的话, 用户会在一张"电脑上已经没星"的照片上看到 ★★。
        enqueueRead(readJson(path, rating = 3))
        enqueueRead(readJson(path))
        val s = store()

        assertEquals(1, s.pullRemote(listOf(path)).let { (it as RemotePull.Merged).merge.adopted })
        assertEquals(3, s.get(path).rating)

        val second = s.pullRemote(listOf(path)) as RemotePull.Merged

        assertEquals(1, second.merge.cleared)
        assertEquals(0, second.merge.adopted)
        assertNull("电脑上清掉了, 手机上不许留着", s.get(path).rating)
        assertNull(s.resolved()[path])
    }

    @Test
    fun an_entry_without_a_path_is_ignored_because_it_cannot_be_traced_to_a_photo() {
        val s = store()
        val outcome = s.mergeRemote(
            listOf(DecisionReadDto(path = "", rating = 5), DecisionReadDto(path = path, rating = 2)),
        )
        assertEquals(1, outcome.adopted)
        assertNull("没有路径的条目归不到任何一张上, 不许凭空造一条记录", s.resolved()[""])
    }

    // ── ③ 读失败: 本地意图与**上一次**读到的值都不许被抹掉 ───────────────────

    @Test
    fun a_failed_read_touches_neither_the_local_intent_nor_what_was_read_before() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        val s = store()
        // 上一次读到过这张
        s.mergeRemote(listOf(DecisionReadDto(path = other, rating = 3)))
        // 用户在手机上改了另一张(还没发出去)
        s.applyLocal(Decision(path).withRating(4))

        val pull = s.pullRemote(listOf(path, other)) as RemotePull.Failed

        assertEquals(2, pull.requested)
        assertEquals(ApiErrorCodes.INTERNAL, pull.failure.code)
        assertEquals(ReadSource.HTTP, pull.failure.source)
        assertNull("500 不许去复探", pull.failure.cause)
        // 本地意图原样
        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
        // **上一次**读到的远端值也原样(把已知的东西抹掉比留在那儿更坏)
        assertEquals(3, s.get(other).rating)
        assertEquals(SyncState.UNTOUCHED, s.get(other).sync)
    }

    @Test
    fun a_dead_service_is_a_client_synthesized_code_and_keeps_the_local_intent() = runTest {
        val s = store()
        s.applyLocal(Decision(path).withRating(4))
        server.shutdown()

        val pull = s.pullRemote(listOf(path)) as RemotePull.Failed

        assertEquals(ReadSource.CLIENT, pull.failure.source)
        assertEquals(ApiErrorCodes.SERVICE_OFF, pull.failure.code)
        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
    }

    @Test
    fun a_401_is_disambiguated_with_the_shared_known_protected_probe() = runTest {
        // 与配对页/浏览页/网格同一条纪律: 第一次 401 **绝不**当成"token 被轮换"。
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        val pull = s.pullRemote(listOf(path)) as RemotePull.Failed

        assertEquals(ApiErrorCodes.UNAUTHORIZED, pull.failure.code)
        assertEquals(ReadSource.HTTP, pull.failure.source)
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, pull.failure.cause)
        // 本地意图照旧(读取失败绝不许碰它)
        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)

        assertEquals("/api/v1/decisions", server.takeRequest().requestUrl!!.encodedPath)
        val probe = server.takeRequest()
        assertEquals("/api/v1/shares", probe.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", probe.getHeader("Authorization"))
    }

    // ── ④ 没回报全 = 没有结论(与 WriteMessages.UNCONFIRMED 同一条) ──────────

    @Test
    fun a_read_that_does_not_report_every_requested_path_is_partial_not_success() = runTest {
        // 问了两张, 只回来一张 —— 没回来的那张在电脑上到底是几星, 这台手机**不知道**。
        enqueueRead(readJson(path, rating = 3))
        val s = store()

        val pull = s.pullRemote(listOf(path, other)) as RemotePull.Partial

        assertEquals(2, pull.requested)
        assertEquals(1, pull.mismatched)
        assertEquals("已经回来的那张是真值, 照常采纳", 3, s.get(path).rating)
        assertEquals(1, pull.merge.adopted)
        assertNull("没回来的那张仍然是'不知道', 不许编一个值", s.resolved()[other])
    }

    @Test
    fun a_read_that_reports_the_wrong_path_is_also_partial() = runTest {
        // 多报一条与少报一条同样"没对上": 这一份读回来的东西是我们对"电脑上这个文件夹现在是什么样"
        // 的全部依据, 对不上就不能说"知道"。
        enqueueRead(readJson(path, rating = 3), readJson("$folder\\ghost.jpg", rating = 5))
        val s = store()

        val pull = s.pullRemote(listOf(path)) as RemotePull.Partial

        assertEquals(1, pull.mismatched)
    }

    @Test
    fun reading_an_empty_list_sends_nothing_at_all() = runTest {
        // 空数组在服务端是 badRequest("你不知道自己要什么"), 而且"电脑上还有几张打了星"在一个空
        // 文件夹里本来就是没有意义的数 —— 一个请求都不发, 也就**没有**服务端的确认。
        val s = store()

        val pull = s.pullRemote(emptyList())

        assertEquals(RemotePull.NothingToRead(0), pull)
        assertEquals(0, server.requestCount)
    }

    // ── ⑤ 线上形态: 鉴权头 + 重复的 paths 参数 + verbatim 路径逐字往返 ────────

    @Test
    fun the_read_is_authed_and_sends_one_repeated_paths_parameter_per_photo() = runTest {
        enqueueRead(readJson(path, rating = 3), readJson(other, rating = 2))
        val s = store()

        s.pullRemote(listOf(path, other, path))   // 重复的路径只问一次

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/decisions", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        // `paths` 是**重复的同名参数**(?paths=a&paths=b), 不是逗号分隔、也不是数组
        assertEquals(listOf(path, other), req.requestUrl!!.queryParameterValues("paths"))
        // verbatim 前缀里那个**字面问号**必须逐字往返(自己拼字符串就会在这里丢)
        assertEquals(path, req.requestUrl!!.queryParameter("paths"))
        assertTrue("查询串里不许有裸反斜杠: ${req.path}", !req.path!!.substringAfter('?').contains('\\'))
    }

    @Test
    fun a_read_that_only_returns_blank_paths_is_reported_as_partial() = runTest {
        // 服务端回了一条没有路径的条目: 它归不到任何一张上, 于是"问到的那些被回报了没有"的
        // 答案是"没有" —— 不许因为"数组长度对上了"就说知道。
        enqueueRead("""{"path":"","rating":5,"label":null,"sidecar":null}""")
        val s = store()

        val pull = s.pullRemote(listOf(path))

        assertTrue("少了 path 这一条 ⇒ 没对上 ⇒ Partial", pull is RemotePull.Partial)
        // 少一条(问的那张没回来) **加上** 多一条(回来的那条归不到任何一张上)= 2
        assertEquals(2, (pull as RemotePull.Partial).mismatched)
        assertNull(s.get(path).rating)
    }

    @Test
    fun the_incomplete_code_is_the_one_the_ui_shows() {
        // 读取没被确认时界面看到的是哪一档: 手机合成码 + READ 那张表(不是 XMP, 也不是 HTTP)。
        assertEquals("readIncomplete", ReadMessages.INCOMPLETE)
        assertTrue(ReadMessages.INCOMPLETE in ReadMessages.REACHABLE)
    }

    @Test
    fun a_timeout_is_reported_with_a_bounded_wait_instead_of_hanging() = runTest {
        // 服务端接了连接却什么都不回(电脑卡死这种真实情形): 必须在读超时后落成合成码,
        // 而不是把这一页永远转下去。
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val s = DecisionStore(
            ApiClient(
                base,
                token,
                buildHttpClient(token).newBuilder()
                    .readTimeout(300, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build(),
            ),
        )
        s.applyLocal(Decision(path).withRating(4))

        val pull = s.pullRemote(listOf(path)) as RemotePull.Failed

        assertEquals(ReadSource.CLIENT, pull.failure.source)
        assertEquals(ApiErrorCodes.TIMEOUT, pull.failure.code)
        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
    }

    // ── ⑥ "知道 / 不知道"必须分得开(静默降级那个口子的**知识**本身) ─────────────
    //
    // `Decision.rating == null` 混了两种事实: "服务端明确说没有"与"这台手机没读到"。
    // 保留(右滑)只在**知道**时才允许写 3 星(否则可能把电脑上的 4/5 星降级), 所以这一组
    // 把"知道"这件事本身钉住 —— 它不依赖滑卡那一层, 于是两边不会互相掩盖。

    @Test
    fun a_reported_path_is_known_even_when_the_server_says_it_has_nothing() = runTest {
        enqueueRead(readJson(path), readJson(other, rating = 3))
        val s = store()
        assertFalse("还没读过 ⇒ 不知道", s.knows(path))

        s.pullRemote(listOf(path, other))

        assertTrue("服务端明确回报了这一张(哪怕没有星级)⇒ 知道", s.knows(path))
        assertEquals(
            "而且是'确实没有', 不是'不知道' —— 这两个状态必须分得开",
            DesktopRating.Unrated,
            s.get(path).desktopRating,
        )
        assertTrue(s.knows(other))
        assertEquals(DesktopRating.Rated(3), s.get(other).desktopRating)
    }

    @Test
    fun a_partial_read_only_makes_the_paths_it_reported_known() = runTest {
        // 问了两张, 只回来一张: 没回来的那张**回到不知道**(不许拿"上一次读到过"继续充数),
        // 而回来的那张照旧是真值。
        enqueueRead(readJson(path, rating = 4))
        enqueueRead(readJson(path, rating = 3))
        val s = store()
        s.pullRemote(listOf(path))
        assertTrue(s.knows(path))
        assertFalse(s.knows(other))

        val pull = s.pullRemote(listOf(path, other))

        assertTrue(pull is RemotePull.Partial)
        assertTrue("回报了的那张: 知道", s.knows(path))
        assertFalse("没回报的那张: 不知道", s.knows(other))
        assertEquals(DesktopRating.Rated(3), s.get(path).desktopRating)
        assertEquals(DesktopRating.Unknown, s.get(other).desktopRating)
    }

    @Test
    fun a_failed_read_takes_the_knowledge_back_but_keeps_showing_the_last_value() = runTest {
        // 刷新失败时: 屏幕上**照旧**显示上一次读到的 4 星(把已知的东西抹掉比留在那儿更坏),
        // 但"这是电脑现在的事实"这句话不许再说了 —— 保留那一支就是靠这条不写。
        enqueueRead(readJson(path, rating = 4))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        val s = store()
        s.pullRemote(listOf(path))
        assertEquals(DesktopRating.Rated(4), s.get(path).desktopRating)

        assertTrue(s.pullRemote(listOf(path)) is RemotePull.Failed)

        assertEquals("上一次读到的值照旧显示", 4, s.get(path).rating)
        assertFalse("但没被确认 ⇒ 不知道", s.knows(path))
        assertEquals(
            "不知道 ≠ 没有星级(右滑就是靠这个区别决定写不写)",
            DesktopRating.Unknown,
            s.get(path).desktopRating,
        )
    }

    @Test
    fun a_local_intent_does_not_invent_knowledge_about_the_pc() = runTest {
        // 这台手机上打了 4 星(还没发出去)完全不说明电脑上是几星 —— 本地意图与"知道"
        // 是两件事。混起来就会让"离线打过分"变成"可以拿 3 星去覆盖电脑"。
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        assertEquals(4, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
        assertFalse("本地意图不许冒充「电脑上是什么」的知识", s.knows(path))
        assertEquals(DesktopRating.Unknown, s.get(path).desktopRating)
    }

    // ── ⑦ 边车读不动: `null` 不是"没有值"的证据(FIX 4 消费 `sidecarReadable`) ─────
    //
    // 服务端在读不动边车时发的**也是** `rating: null`(`xmp.rs::read_one` 的注释明写了
    // "读失败一律当没有值"), 而"保留(右滑)"的全部判据就是"这个 null 说明电脑上没有值"。
    // 两者的代价不对称到极点: Lightroom 占着边车的那一秒里右滑一次, 电脑上的 5 星就被静默
    // 降成 3 星 —— 所以协议加了这一个可选的布尔信号, 手机这边必须真的按它分岔。

    @Test
    fun a_read_item_whose_sidecar_could_not_be_read_is_unknown_not_unrated() = runTest {
        enqueueRead(readJsonWith(path, sidecarReadable = false))
        val s = store()

        val pull = s.pullRemote(listOf(path))

        assertTrue("问到的都回报了, 但这一项没能变成'知道' ⇒ 没有结论", pull is RemotePull.Partial)
        assertEquals("边车读不动的那一项要**数**出来", 1, (pull as RemotePull.Partial).unreadable)
        assertEquals("两项都对上了(不是少报/多报)", 0, pull.mismatched)
        assertFalse("**不知道** —— 绝不许当成'电脑上没有星级'", s.knows(path))
        assertEquals(DesktopRating.Unknown, s.get(path).desktopRating)
        assertNull("也不许编一个值", s.get(path).rating)
    }

    @Test
    fun the_same_item_without_the_field_keeps_the_old_behaviour() = runTest {
        // **对照**(兼容性): 老服务端不发这个字段 ⇒ 按"读到了"处理, 也就是本字段出现之前的全部行为。
        // 没有这一条, "把缺字段也当成不知道"这种改坏会让每一次读取都变成"未统计"。
        enqueueRead(readJson(path))          // 没有 sidecarReadable 这个键
        val s = store()

        val pull = s.pullRemote(listOf(path))

        assertTrue("少报/多报都没有, 也没有读不动的项 ⇒ 照旧是 Merged", pull is RemotePull.Merged)
        assertEquals(0, (pull as RemotePull.Merged).merge.unreadable)
        assertTrue("缺字段 = 没有信号 ⇒ 按'读到了'算", s.knows(path))
        assertEquals(DesktopRating.Unrated, s.get(path).desktopRating)
    }

    @Test
    fun true_and_missing_are_the_same_signal_but_false_is_not() = runTest {
        // 三档一起钉: `true` 与"缺字段"是**同一个意思**(读到了), `false` 是另一个(不知道)。
        // 只测两档的话, "把 true 也当成不知道"这种改坏测不出来。
        enqueueRead(readJsonWith(path, sidecarReadable = true), readJsonWith(other, sidecarReadable = false))
        val s = store()

        val pull = s.pullRemote(listOf(path, other)) as RemotePull.Partial

        assertTrue("true = 读到了", s.knows(path))
        assertEquals(DesktopRating.Unrated, s.get(path).desktopRating)
        assertFalse("false = 不知道", s.knows(other))
        assertEquals(DesktopRating.Unknown, s.get(other).desktopRating)
        assertEquals(1, pull.unreadable)
        assertEquals("两张都回报了", 0, pull.mismatched)
    }

    @Test
    fun an_unreadable_sidecar_does_not_wipe_the_value_we_read_before() = runTest {
        // 读不动 ≠ "电脑上没有值": 上一次读到的 4 星照旧显示(把已知的东西抹掉比留在那儿更坏),
        // 但"这就是电脑现在的事实"这句话不许再说了 —— 于是保留那一支不会拿它当依据。
        enqueueRead(readJson(path, rating = 4))
        enqueueRead(readJsonWith(path, sidecarReadable = false))
        val s = store()
        s.pullRemote(listOf(path))
        assertEquals(DesktopRating.Rated(4), s.get(path).desktopRating)

        s.pullRemote(listOf(path))

        assertEquals("上一次读到的值照旧显示", 4, s.get(path).rating)
        assertFalse("但没被确认 ⇒ 不知道", s.knows(path))
        assertEquals(
            "不知道 ≠ 没有星级(保留那一支就是靠这个区别决定写不写)",
            DesktopRating.Unknown,
            s.get(path).desktopRating,
        )
    }
}
