// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.DecisionDto
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import com.imagefilter.mobile.ui.LABELS
import com.imagefilter.mobile.ui.PushResult
import com.imagefilter.mobile.ui.SyncState
import com.imagefilter.mobile.ui.afterAck
import com.imagefilter.mobile.ui.labelAfterTap
import com.imagefilter.mobile.ui.labelName
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
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
 * 写入链路的**真行为**测试: JVM + MockWebServer, 走真的 HTTP 与真的 [ApiClient]。
 *
 * 这里钉的是"一次写入到底算不算成功"的全部判据 —— 本任务最贵的一种失败就是**静默**:
 * 用户以为分数落到电脑上了, 而边车根本没动。所以逐条都是:
 *   ① **只有服务端说写了才算成功**([PushResult.Ok] 只在逐项回报里一条失败都没有时出现);
 *   ② `failures[].code` 是 **XMP 码集**, 各带各的码(不是整批一个布尔值);
 *   ③ 200 + 逐项失败**绝不能**报成成功; 计数对不上(有项没被回报)也**绝不能**报成成功;
 *   ④ `skipped`(changed=false 不写)与 `rating=0`(废弃)**都不是**失败、也都不是"不动";
 *   ⑤ 失败**不回滚**本地意图, 一次性命令 `clearLabel` 要留到重试成功才消费;
 *   ⑥ `null` = 不动这个属性 —— 只改星级时请求体里**不许**出现 label / clearLabel。
 *
 * ⚠️ 测试里注入的 client 一律是 [buildHttpClient]`(token)` —— 自己造 `OkHttpClient()`
 * 等于把鉴权头丢了, 那样"token 有没有发出去"就永远测不到(有一条专门断言它)。
 */
class DecisionStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val token = "d".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有一个**字面问号**, 路径里有空格、中文和反斜杠。 */
    private val folder = "\\\\?\\D:\\照片 2026"
    private val path = "$folder\\a.jpg"
    private val other = "$folder\\b.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        // 先把 baseUrl 固定下来: "电脑睡了/服务没开"那几条用例会真的把 server 关掉。
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun store() = DecisionStore(ApiClient(base, token, buildHttpClient(token)))

    /** 把真实路径转成它能出现在 JSON 字符串里的形态(每个反斜杠都要成对)。 */
    private fun jsonPath(p: String) = p.replace("\\", "\\\\")

    private fun summaryJson(
        written: Int,
        skipped: Int,
        failed: Int,
        failures: String = "[]",
        degraded: Boolean = false,
    ) = """{"written":$written,"skipped":$skipped,"failed":$failed,"failures":$failures,"degraded":$degraded}"""

    private fun failJson(p: String, code: String, detail: String = "") =
        """{"path":"${jsonPath(p)}","code":"$code","detail":"$detail"}"""

    private fun enqueueSummary(written: Int = 0, skipped: Int = 0, failed: Int = 0, failures: String = "[]") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryJson(written, skipped, failed, failures)))
    }

    // ── ① 成功只由服务端说 ───────────────────────────────────────────────────

    @Test
    fun the_server_saying_it_wrote_is_what_makes_a_write_successful() = runTest {
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        val r = s.push(listOf(path))

        assertEquals(PushResult.Ok(requested = 1, written = 1, skipped = 0, degraded = false), r)
        assertEquals(4, s.get(path).rating)
        assertEquals("服务端确认了, 这一项才算已同步", SyncState.SYNCED, s.get(path).sync)
        assertNull(s.get(path).lastError)
        assertTrue(s.unsynced().isEmpty())
    }

    @Test
    fun the_request_is_authed_and_only_carries_what_the_user_actually_changed() = runTest {
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(3).withLabel("green"))

        s.push(listOf(path))

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/decisions", req.requestUrl!!.encodedPath)
        // 共用那个带鉴权头的 client(规格 §4.3): 丢了这个头就是 401, 而"JSON 通、图片 401"
        // 那种分裂正是本项目反复吃过亏的地方。
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        // 往返回读一遍: 服务端拿到手的就是这些字段(不是"我拼了什么字符串")。
        val parsed = Json.decodeFromString<List<DecisionDto>>(body)
        assertEquals(1, parsed.size)
        assertEquals(path, parsed.single().path)
        assertEquals(3, parsed.single().rating)
        assertEquals("green", parsed.single().label)
        assertFalse("clearLabel 没要求清空时就是 false(缺字段 = false, 与服务端 #[serde(default)] 一致)",
            parsed.single().clearLabel)
    }

    @Test
    fun a_rating_only_change_never_sends_label_or_clear_label() = runTest {
        // 纪律①"绝不静默覆盖": 服务端的 `null` = **不动这个属性**。手机只改了星级却把 label
        // 一起发出去(哪怕发的是 null), 就是在替用户决定边车上的标签 —— 这条把那个口子钉死。
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(5))

        s.push(listOf(path))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":5"))
        assertFalse("只改星级时请求体里不许出现 label", body.contains("\"label\""))
        assertFalse("只改星级时请求体里不许出现 clearLabel", body.contains("\"clearLabel\""))
    }

    @Test
    fun zero_stars_is_a_real_value_not_a_do_not_touch() = runTest {
        // 0 = 废弃(桌面端 X 键、服务端 xmp:Rating=0)。客户端把它当成 null 丢掉的话,
        // "废弃这张"就成了一个永远发不出去的操作 —— 而且没有任何报错。
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(0))

        s.push(listOf(path))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"rating\":0"))
    }

    @Test
    fun clearing_a_label_is_sent_as_the_explicit_one_shot_command() = runTest {
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(3).withLabel(null))

        s.push(listOf(path))
        val body = server.takeRequest().body.readUtf8()
        assertTrue("清标签必须显式发 clearLabel=true(否则边车里的旧标签会在下次读盘时复活)",
            body.contains("\"clearLabel\":true"))
        assertFalse(body, body.contains("\"label\""))

        // 成功之后这条**一次性命令**必须被消费掉: 否则用户下一次改星级会把"删标签"再发一遍。
        assertFalse(s.get(path).clearLabel)
        assertEquals(SyncState.SYNCED, s.get(path).sync)
    }

    @Test
    fun skipped_means_nothing_to_do_and_is_not_a_failure() = runTest {
        // `changed=false` 不写盘(服务端幂等纪律): 电脑上本来就是这个值 —— **不是**失败。
        enqueueSummary(skipped = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        val r = s.push(listOf(path))

        assertEquals(PushResult.Ok(requested = 1, written = 0, skipped = 1, degraded = false), r)
        assertEquals(SyncState.SYNCED, s.get(path).sync)
        assertEquals(4, s.get(path).rating)
    }

    // ── ② 逐项失败: 各带各的 XMP 码 ─────────────────────────────────────────

    @Test
    fun a_per_item_failure_keeps_the_local_intent_and_carries_its_own_xmp_code() = runTest {
        enqueueSummary(failed = 1, failures = "[${failJson(path, "readOnly", "边车是只读的")}]")
        val s = store()
        s.applyLocal(Decision(path).withRating(5))

        val r = s.push(listOf(path))

        val f = r as PushResult.Failed
        assertEquals(1, f.requested)
        assertEquals(0, f.written)
        assertEquals(1, f.failures.size)
        val failure = f.failures.single()
        assertEquals(path, failure.path)
        assertEquals("readOnly", failure.code)
        assertEquals(
            "逐项失败的码来自 **XMP** 码集(readOnly 在 HTTP 那张表里根本不存在)",
            FailureSource.XMP,
            failure.source,
        )
        assertEquals("边车是只读的", failure.detail)
        assertTrue("服务端回了 WriteSummary, 所以这是逐项结论", f.summaryConfirmed)

        // 纪律③: 本地值**没有被回滚丢掉**, 只是标成未同步 + 记下这条失败。
        assertEquals(5, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
        assertEquals("readOnly", s.get(path).lastError?.code)
        assertEquals(1, s.unsynced().size)
        // 而且这条失败有**自己的**一句可执行的话(XMP 表, 不是 HTTP 表)。
        assertEquals(WriteMessages.forFailure(failure), WriteMessages.forFailure(s.get(path).lastError!!))
        assertTrue(WriteMessages.forFailure(failure).contains("只读"))
    }

    @Test
    fun a_200_with_a_partial_failure_is_never_reported_as_success() = runTest {
        // 一个批次可以**在 HTTP 层成功、在逐项层失败** —— 这正是规格 §5.2 那条接口细节。
        // 把它报成 Ok, 用户就会以为两张都写成了(而其中一张的边车一个字都没动)。
        enqueueSummary(written = 1, failed = 1, failures = "[${failJson(other, "encoding")}]")
        val s = store()
        s.applyLocal(Decision(path).withRating(5))
        s.applyLocal(Decision(other).withRating(2))

        val r = s.push(listOf(path, other))

        val f = r as PushResult.Failed
        assertEquals(2, f.requested)
        assertEquals("半成功态必须显式: 成了几张也要如实说", 1, f.written)
        assertEquals(1, f.failures.size)
        assertEquals("encoding", f.failures.single().code)
        // 成了的那张标已同步, 坏了的那张标未同步 —— 张冠李戴在这里是绝不能有的。
        assertEquals(SyncState.SYNCED, s.get(path).sync)
        assertEquals(SyncState.UNSYNCED, s.get(other).sync)
        assertEquals("encoding", s.get(other).lastError?.code)
        assertEquals(1, s.unsynced().size)
    }

    @Test
    fun an_unknown_per_item_code_degrades_safely_instead_of_crashing() = runTest {
        // 服务端将来加一档码: 老客户端必须还能说一句有动作的人话, 而且**保留原始码**便于排查。
        val future = "someFutureReason"
        enqueueSummary(failed = 1, failures = "[${failJson(path, future)}]")
        val s = store()
        s.applyLocal(Decision(path).withRating(1))

        val f = s.push(listOf(path)) as PushResult.Failed

        assertEquals(future, f.failures.single().code)
        assertEquals(FailureSource.XMP, f.failures.single().source)
        assertEquals(WriteMessages.xmpGeneric(future), WriteMessages.forFailure(f.failures.single()))
        assertTrue(WriteMessages.forFailure(f.failures.single()).contains(future))
        // 认不出原因也不许把本地改动弄丢。
        assertEquals(1, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
    }

    // ── ③ 计数对不上 = 没有被确认, 绝不算成功 ───────────────────────────────

    @Test
    fun a_summary_that_does_not_account_for_the_item_is_unconfirmed_not_success() = runTest {
        // 服务端逐项计数, written+skipped+failed 就是输入项数。对不上 = 有项的写入结果**没被回报**
        // (例如它写了一半就没了)。这时说"成功"就是替服务端保证了一件它没说过的事。
        enqueueSummary(written = 0, skipped = 0, failed = 0)
        val s = store()
        s.applyLocal(Decision(path).withRating(3))

        val f = s.push(listOf(path)) as PushResult.Failed

        assertEquals(WriteMessages.UNCONFIRMED, f.failures.single().code)
        assertEquals(FailureSource.CLIENT, f.failures.single().source)
        assertEquals("没被确认 ≠ 已同步", SyncState.UNSYNCED, s.get(path).sync)
        assertEquals(3, s.get(path).rating)
    }

    @Test
    fun a_failure_count_without_the_matching_entry_is_also_unconfirmed() = runTest {
        // failed=1 却给了 0 条 failures[]: 服务端说"有一项失败", 却没说**是哪一项** ——
        // 一样不许算成功(这一条正是"整批一个布尔值"式实现会踩的坑)。
        enqueueSummary(written = 1, failed = 1, failures = "[]")
        val s = store()
        s.applyLocal(Decision(path).withRating(3))

        val f = s.push(listOf(path)) as PushResult.Failed

        assertEquals(1, f.failures.size)
        assertEquals(WriteMessages.UNCONFIRMED, f.failures.single().code)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
    }

    @Test
    fun nothing_to_push_does_not_even_send_a_request() = runTest {
        // 手机上这些路径根本没有本地改动 —— 没有请求发出去, 就**没有**服务端的确认,
        // 所以它既不是成功也不是失败。把它算成 Ok, 界面就会在没人确认过的情况下说"已同步"。
        val s = store()

        val r = s.push(listOf(path))

        assertEquals(PushResult.NothingToPush(requested = 1), r)
        assertEquals(0, server.requestCount)
        assertEquals(SyncState.UNTOUCHED, s.get(path).sync)
        assertNull("没动过的路径不是未同步 —— 否则未同步角标全是假的", s.get(path).lastError)
    }

    @Test
    fun an_unrated_path_is_untouched_not_unsynced() {
        val s = store()
        assertEquals(SyncState.UNTOUCHED, s.get(path).sync)
        assertTrue(s.unsynced().isEmpty())
    }

    // ── ④ 网络 / HTTP 层失败 ────────────────────────────────────────────────

    @Test
    fun a_network_failure_keeps_the_local_intent_and_is_retryable() = runTest {
        val s = store()
        s.applyLocal(Decision(path).withRating(4).withLabel(null)) // 打分 + 清标签, 两条意图
        // 电脑睡了 / 服务没开: 根本没有进程在监听。
        server.shutdown()

        val f = s.push(listOf(path)) as PushResult.Failed

        assertEquals(FailureSource.CLIENT, f.failures.single().source)
        assertEquals(com.imagefilter.mobile.data.ApiErrorCodes.SERVICE_OFF, f.failures.single().code)
        assertFalse("整个请求没有被确认 —— 不是逐项结论", f.summaryConfirmed)
        assertEquals(0, f.written)
        // 本地意图一个字都不许丢: 分数还在, 清标签这条命令也还在(否则重试会退化成一次白跑)。
        assertEquals(4, s.get(path).rating)
        assertTrue("失败时 clearLabel 这条一次性命令必须留着", s.get(path).clearLabel)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
        assertEquals(
            com.imagefilter.mobile.data.WriteAction.RETRY,
            WriteMessages.actionFor(f.failures.single()),
        )
        assertTrue(WriteMessages.forFailure(f.failures.single()).contains("没连上电脑"))
    }

    @Test
    fun an_http_error_body_is_read_with_the_http_table_not_the_xmp_one() = runTest {
        // 423 busy 走的是**整个请求**失败(不是逐项) —— 它的码属于 §5.3 那张表。
        server.enqueue(MockResponse().setResponseCode(423).setBody("""{"code":"busy"}"""))
        val s = store()
        s.applyLocal(Decision(path).withRating(2))

        val f = s.push(listOf(path)) as PushResult.Failed

        assertEquals(FailureSource.HTTP, f.failures.single().source)
        assertEquals("busy", f.failures.single().code)
        assertFalse(f.summaryConfirmed)
        assertEquals(2, s.get(path).rating)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)
        assertTrue(WriteMessages.forFailure(f.failures.single()).contains("正忙"))
    }

    @Test
    fun the_retry_after_a_failure_sends_the_same_intent_again_and_then_settles() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        enqueueSummary(written = 1)
        val s = store()
        s.applyLocal(Decision(path).withRating(4))

        assertTrue(s.push(listOf(path)) is PushResult.Failed)
        assertEquals(SyncState.UNSYNCED, s.get(path).sync)

        val again = s.push(listOf(path))

        assertEquals(PushResult.Ok(1, 1, 0, false), again)
        assertEquals(SyncState.SYNCED, s.get(path).sync)
        assertNull(s.get(path).lastError)
        val retryBody = server.takeRequest().body.readUtf8()   // 第一次
        val secondBody = server.takeRequest().body.readUtf8()  // 重试
        assertTrue(retryBody, retryBody.contains("\"rating\":4"))
        assertEquals("重试必须重发**同一份意图**, 不是重发一个空请求", retryBody, secondBody)
    }

    // ── ⑤ 纯规则(飞行中又改过 / 色标闭集) ───────────────────────────────────

    @Test
    fun a_value_changed_while_the_write_was_in_flight_is_not_marked_as_synced() {
        // 服务端确认的是**发出去的那一版**; 用户在这期间又改了, 本地这一版就还没被确认。
        // 这时说"已同步"就是纪律③里最典型的谎话 —— 而它恰恰只能靠"逐字比对"才看得出来。
        val sent = Decision(path).withRating(3).toDto()
        val now = Decision(path).withRating(5)

        val next = afterAck(now, sent, failure = null)!!

        assertEquals(SyncState.UNSYNCED, next.sync)
        assertEquals(5, next.rating)
        assertNull(next.lastError)
    }

    @Test
    fun an_ack_for_the_value_that_was_sent_settles_it() {
        val current = Decision(path).withRating(3).withLabel(null)
        val next = afterAck(current, current.toDto(), failure = null)!!
        assertEquals(SyncState.SYNCED, next.sync)
        assertFalse("一次性命令被消费掉了", next.clearLabel)
    }

    @Test
    fun tapping_the_current_colour_clears_the_label_and_another_colour_sets_it() {
        assertEquals("green", labelAfterTap(null, "green"))
        assertEquals("blue", labelAfterTap("green", "blue"))
        assertNull("再点一次当前颜色 = 清掉(走 clearLabel=true)", labelAfterTap("green", "green"))
    }

    @Test
    fun the_label_set_is_the_desktop_five_and_nothing_else() {
        assertEquals(listOf("red", "yellow", "green", "blue", "purple"), LABELS)
        assertEquals("红", labelName("red"))
        assertEquals("无", labelName(null))
        // 闭集之外的颜色是**编程错误**, 不是用户能触发的路径 —— 当场炸掉好过悄悄发一个服务端不认的值。
        var threw = false
        try {
            Decision(path).withLabel("chartreuse")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("色标必须是闭集里的那个字符串", threw)
    }

    @Test
    fun the_stars_are_a_closed_range_too() {
        var threw = false
        try {
            Decision(path).withRating(6)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertNotNull(Decision(path).withRating(0))
    }
}
