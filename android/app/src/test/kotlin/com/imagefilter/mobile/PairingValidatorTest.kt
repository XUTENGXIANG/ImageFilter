// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.PairingCheck
import com.imagefilter.mobile.data.PairingField
import com.imagefilter.mobile.data.PairingInput
import com.imagefilter.mobile.data.PairingInputProblem
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.PairingValidator
import com.imagefilter.mobile.data.PingDto
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.apiCodeFor
import com.imagefilter.mobile.data.buildHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 配对状态机, 跑在 **JVM + MockWebServer** 上: 真的 HTTP、真的 [ApiClient]、真的 OkHttp 超时,
 * 只有安卓专有的两个存储实现被换掉(它们在 [PairingStoreTest] 里有说明)。
 *
 * 注入 client 时一律以 [buildHttpClient] 为底 —— 鉴权头**只**装在那里(Task 2 的纪律)。
 * 那条纪律在这里格外要紧: 本文件大量断言"token 被发出去了/没被发出去", 用一个裸
 * `OkHttpClient()` 会让这些断言全部变成假绿。
 */
class PairingValidatorTest {

    private lateinit var server: MockWebServer
    private val token = "a1b2c3d4".repeat(8)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    /** 生产路径(默认工厂) —— 大多数用例走的都是它。 */
    private fun validator() = PairingValidator()

    /** 关掉 OkHttp 的连接重试: 让"复探打不通"这条路径能被**确定地**走一遍。 */
    private fun noRetryValidator(readTimeoutMs: Long = 10_000) = PairingValidator { url, tkn ->
        ApiClient(
            url,
            tkn,
            buildHttpClient(tkn).newBuilder()
                .retryOnConnectionFailure(false)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private fun input(url: String = baseUrl(), tkn: String = token) = PairingInput(url = url, token = tkn)

    private fun pingOk() = MockResponse().setResponseCode(200)
        .setBody("""{"name":"ImageFilter","version":"1.1.2","requiresToken":true}""")

    /**
     * 共享根响应**逐字**写出来(不拿 DTO 反向编码): 用 DTO 编码的话, 字段名写错时
     * 两侧会一起错, 这条契约测试就变成了同义反复。
     * 注意这里是原始字符串 —— `\\` 不会被转义, 正好是 JSON 里表示一个反斜杠的写法。
     */
    private fun sharesOk(rawJsonArray: String) = MockResponse().setResponseCode(200)
        .setBody("""{"shares":$rawJsonArray}""")

    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}""")

    // ── 通了 ────────────────────────────────────────────────────────────────

    @Test
    fun a_good_address_and_token_reach_share_roots() = runTest {
        server.enqueue(pingOk())
        server.enqueue(sharesOk("""["D:\\照片","E:\\图库"]"""))

        val check = validator().check(input())

        assertEquals(
            PairingCheck.Connected(
                PingDto("ImageFilter", "1.1.2", true),
                listOf("D:\\照片", "E:\\图库"),
            ),
            check,
        )
        // 两个请求的顺序与端点: ping 是公开的(先证明服务在跑), shares 才是验 token 的那个
        val first = server.takeRequest()
        assertEquals("/api/v1/ping", first.path)
        assertEquals("Bearer $token", first.getHeader("Authorization"))
        val second = server.takeRequest()
        assertEquals("/api/v1/shares", second.path)
        assertEquals("Bearer $token", second.getHeader("Authorization"))
        assertEquals("一次配只发这两个请求", 2, server.requestCount)
    }

    @Test
    fun a_service_with_no_share_roots_is_connected_but_says_so() = runTest {
        // 电脑上一个共享根都没配 —— 这是服务端有意的安全默认, **不是配对失败**:
        // 配对信息是好的, 用户可以先去电脑上加共享根, 不必为此重扫一遍配对码。
        server.enqueue(pingOk())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":[]}"""))

        val check = validator().check(input())

        assertTrue("实际=$check", check is PairingCheck.Connected)
        assertFalse((check as PairingCheck.Connected).hasShares)
        assertTrue(check.shares.isEmpty())
    }

    // ── 401: 必须走 Task 2 的消歧 ────────────────────────────────────────────

    @Test
    fun a_401_that_also_401s_on_the_probe_is_a_rejected_token() = runTest {
        server.enqueue(pingOk())
        server.enqueue(unauthorized()) // shares
        server.enqueue(unauthorized()) // 复探(同一枚 token, 同一端点)

        val check = validator().check(input())

        assertEquals(PairingCheck.Failed(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.TOKEN_ROTATED), check)
        assertEquals("/api/v1/ping", server.takeRequest().path)
        assertEquals("/api/v1/shares", server.takeRequest().path)
        val probe = server.takeRequest()
        assertEquals("复探必须打已知受保护的端点", "/api/v1/shares", probe.path)
        assertEquals("复探必须用同一枚 token", "Bearer $token", probe.getHeader("Authorization"))
    }

    @Test
    fun a_401_whose_probe_reaches_the_handler_is_not_a_token_problem() = runTest {
        server.enqueue(pingOk())
        server.enqueue(unauthorized())
        server.enqueue(sharesOk("""["D:\\照片"]""")) // 复探通了: token 其实是被接受的

        val check = validator().check(input())

        assertEquals(
            PairingCheck.Failed(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.ENDPOINT_NOT_EXPOSED),
            check,
        )
    }

    @Test
    fun a_401_whose_probe_cannot_be_completed_is_undetermined_and_never_blames_the_token() = runTest {
        server.enqueue(pingOk())
        server.enqueue(unauthorized())
        // 复探**没能打通**: 连接建起来了但一直没有回应 → SocketTimeoutException(IOException)。
        //
        // 这里刻意**不用** DISCONNECT_AT_START: MockWebServer 会在"读请求之前"就把那个
        // socket policy 取走(peek 通路), 连接被关掉, 于是 OkHttp 换成新连接重发一次,
        // 而那时队列已空 → MockWebServer 的 failFast 回一个 503 —— 那会走进
        // ENDPOINT_NOT_EXPOSED(处理器其实"到了"), 与本用例要验的路径**不是一回事**。
        // 这条是第一次跑就红着发现的(实际得到 ENDPOINT_NOT_EXPOSED), 记在这里免得后人踩。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val check = noRetryValidator(readTimeoutMs = 300).check(input())

        assertEquals(
            PairingCheck.Failed(ApiErrorCodes.UNAUTHORIZED, UnauthorizedCause.UNDETERMINED),
            check,
        )
        // 这条是整条消歧的存在理由:**绝不许**在复探打不通时断言"配对码被轮换了"。
        val text = PairingMessages.forFailure(
            (check as PairingCheck.Failed).code,
            check.unauthorizedCause,
        )
        assertFalse("网络故障不许说成 token 问题: $text", text.contains("配对码不对"))
    }

    // ── 没有 HTTP 响应 ───────────────────────────────────────────────────────

    @Test
    fun a_dead_service_is_service_off_not_a_wrong_token() = runTest {
        val dead = MockWebServer().also { it.start() }
        val url = dead.url("/").toString().trimEnd('/')
        dead.shutdown() // 端口上没人听了 —— 这就是"服务没开 / 电脑睡了"的真实形态

        val check = validator().check(input(url = url))

        assertEquals(PairingCheck.Failed(ApiErrorCodes.SERVICE_OFF), check)
        assertNull("服务没开时不许编一个 401 的成因", (check as PairingCheck.Failed).unauthorizedCause)
    }

    @Test
    fun a_service_that_never_answers_is_a_timeout() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val check = noRetryValidator(readTimeoutMs = 300).check(input())

        assertEquals(PairingCheck.Failed(ApiErrorCodes.TIMEOUT), check)
    }

    @Test
    fun the_io_classification_keeps_the_three_causes_apart() {
        // 纯函数那一半: 三档分开的理由是**用户动作不同**(见 PairingValidator 的 KDoc)。
        assertEquals(ApiErrorCodes.TIMEOUT, apiCodeFor(SocketTimeoutException()))
        assertEquals(ApiErrorCodes.SERVICE_OFF, apiCodeFor(ConnectException()))
        assertEquals(ApiErrorCodes.SERVICE_OFF, apiCodeFor(UnknownHostException()))
        // 说不清的一律落 network(最保守的一档), **绝不落 unauthorized**
        assertEquals(ApiErrorCodes.NETWORK, apiCodeFor(IOException()))
    }

    // ── 其它 HTTP 失败 ───────────────────────────────────────────────────────

    @Test
    fun the_server_code_is_passed_through_untouched() = runTest {
        server.enqueue(pingOk())
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))

        assertEquals(
            PairingCheck.Failed(ApiErrorCodes.FORBIDDEN_PATH),
            validator().check(input()),
        )
    }

    @Test
    fun a_ping_that_fails_stops_right_there_and_does_not_try_shares() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))

        assertEquals(PairingCheck.Failed(ApiErrorCodes.INTERNAL), validator().check(input()))
        // ping 都不通就没必要再去试受保护端点: 少一次白跑的请求 + 少一次消歧复探
        assertEquals(1, server.requestCount)
    }

    // ── 本地就挡住 ───────────────────────────────────────────────────────────

    @Test
    fun an_empty_or_short_token_never_reaches_the_network() = runTest {
        // 这是最常见的失败(手抄漏一位), 让它退化成一次连接超时再回一句 401 是设计事故。
        assertEquals(
            PairingCheck.BadInput(PairingField.TOKEN, PairingInputProblem.EMPTY_TOKEN),
            validator().check(input(tkn = "   ")),
        )
        assertEquals(
            PairingCheck.BadInput(PairingField.TOKEN, PairingInputProblem.BAD_TOKEN_SHAPE),
            validator().check(input(tkn = token.dropLast(1))),
        )
        assertEquals(
            PairingCheck.BadInput(PairingField.URL, PairingInputProblem.BAD_URL),
            validator().check(input(url = "ftp://host")),
        )
        assertEquals("本地挡住就不该有任何请求", 0, server.requestCount)
    }

    @Test
    fun a_pasted_token_with_a_line_break_is_cleaned_before_it_becomes_a_header() = runTest {
        // 带换行的值直接进 Authorization 头会被 OkHttp 抛 IllegalArgumentException
        // (头值里不许有控制字符)。这条同时证明发出去的是**归一化后的**那一份。
        server.enqueue(pingOk())
        server.enqueue(sharesOk("""["D:\\照片"]"""))

        val pasted = token.chunked(32).joinToString("\r\n")
        assertEquals(
            "归一化后应当能正常配对",
            PairingCheck.Connected(
                PingDto("ImageFilter", "1.1.2", true),
                listOf("D:\\照片"),
            ),
            validator().check(input(tkn = pasted)),
        )
        server.takeRequest()
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
    }
}
