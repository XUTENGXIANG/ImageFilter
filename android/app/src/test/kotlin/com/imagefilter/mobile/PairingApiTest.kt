// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.data.HttpPairingApi
import com.imagefilter.mobile.data.PAIRING_TIMEOUT_SECONDS
import com.imagefilter.mobile.data.PairRequestResult
import com.imagefilter.mobile.data.PairStatusResult
import com.imagefilter.mobile.data.Pairing
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HttpPairingApi] 的契约测试: JVM + MockWebServer, 不需要设备也不需要真服务端。
 *
 * 重点钉住四件事:
 *   · **不带 Authorization**(配对端点免 Bearer; 带了就是把 token 发给一台还没配对的电脑);
 *   · 请求/状态各 **5 秒超时**;
 *   · 202/409/429 与 200 的四种 status **逐档映射**, 不合并、不猜;
 *   · `approved` 解析成**现有 [Pairing]**(url/token/note=desktopName), 保存路径与手输配对同构。
 */
class PairingApiTest {

    private lateinit var server: MockWebServer
    private val appVersion = "1.2.1"
    private val token = "a".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 设备地址就指向 MockWebServer —— 状态机拿到的 host/port 就是这么用的。 */
    private fun device() = DiscoveredDevice(
        serviceName = "ImageFilter DESKTOP-96B8FMF",
        name = "DESKTOP-96B8FMF",
        host = server.hostName,
        port = server.port,
        pairOpen = true,
        desktopId = "desktop-1",
    )

    /** 默认关掉重连: "网络失败"那条路径才能被确定地走一遍(与 ApiClientTest 同一做法)。 */
    private fun api(
        client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    ) = HttpPairingApi(appVersion = appVersion, client = client)

    // ── POST /api/v1/pair/request ────────────────────────────────────────────

    @Test
    fun request_202_is_ok_with_request_id_and_expiry() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"requestId":"req-1","expiresAt":1792900000000}"""),
        )

        val result = api().request(device(), deviceId = "device-abc", deviceName = "Pixel 8")

        assertEquals(PairRequestResult.Ok("req-1", 1792900000000L), result)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/pair/request", req.requestUrl!!.encodedPath)
        // 配对端点免 Bearer: 一个 Authorization 头都不许有
        assertNull("配对请求不许带 Authorization", req.getHeader("Authorization"))
        assertTrue(
            "Content-Type 必须是 JSON, 实际=${req.getHeader("Content-Type")}",
            req.getHeader("Content-Type")!!.startsWith("application/json"),
        )
        // body 的键名逐字对应计划 §0.2 的 {"deviceId","deviceName","appVersion"}
        assertEquals(
            """{"deviceId":"device-abc","deviceName":"Pixel 8","appVersion":"1.2.1"}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun request_409_is_closed_and_429_is_rate_limited() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"pairing_closed"}"""))
        assertEquals(PairRequestResult.Closed, api().request(device(), "d", "Pixel"))

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":"rate_limited"}"""))
        assertEquals(PairRequestResult.RateLimited, api().request(device(), "d", "Pixel"))
    }

    @Test
    fun request_400_and_other_failures_are_errors() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"bad_request"}"""))
        val bad = api().request(device(), "d", "Pixel")
        assertTrue("实际=$bad", bad is PairRequestResult.Error)
        assertTrue("400 的文案必须可读: ${(bad as PairRequestResult.Error).message}", bad.message.isNotBlank())

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        assertTrue(api().request(device(), "d", "Pixel") is PairRequestResult.Error)
    }

    @Test
    fun a_202_without_request_id_is_an_error_not_an_empty_id() = runTest {
        // 空 requestId 拿去轮询只会拿到 404, 在这里就如实说"应答无法识别"
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val result = api().request(device(), "d", "Pixel")
        assertTrue("实际=$result", result is PairRequestResult.Error)
    }

    @Test
    fun a_network_failure_on_request_is_an_error() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = api().request(device(), "d", "Pixel")
        assertTrue("实际=$result", result is PairRequestResult.Error)
    }

    // ── GET /api/v1/pair/status ──────────────────────────────────────────────

    @Test
    fun status_pending_denied_and_expired_map_one_to_one() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"pending"}"""))
        assertEquals(PairStatusResult.Pending, api().status(device(), "req-1"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"denied"}"""))
        assertEquals(PairStatusResult.Denied, api().status(device(), "req-1"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"expired"}"""))
        assertEquals(PairStatusResult.Expired, api().status(device(), "req-1"))

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/pair/status", req.requestUrl!!.encodedPath)
        assertEquals("req-1", req.requestUrl!!.queryParameter("requestId"))
        assertNull("配对状态也不许带 Authorization", req.getHeader("Authorization"))
    }

    @Test
    fun status_approved_becomes_the_existing_pairing_type() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"approved","url":"http://192.168.1.20:8765","token":"$token","desktopName":"DESKTOP-96B8FMF"}""",
            ),
        )

        val result = api().status(device(), "req-1")

        // note = desktopName: 保存后「已连接」页显示的仍是电脑名, 与手输配对的表现一致
        assertEquals(
            PairStatusResult.Approved(
                Pairing(url = "http://192.168.1.20:8765", token = token, note = "DESKTOP-96B8FMF"),
            ),
            result,
        )
    }

    @Test
    fun status_approved_without_url_or_token_is_an_error() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"approved","url":"http://192.168.1.20:8765"}"""),
        )
        assertTrue(api().status(device(), "req-1") is PairStatusResult.Error)

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"approved","token":"$token"}"""),
        )
        assertTrue(api().status(device(), "req-1") is PairStatusResult.Error)

        // 空串与"没有"同义: 拿它去构造 Pairing 等于存了一个连不上的配对
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"approved","url":"","token":""}"""),
        )
        assertTrue(api().status(device(), "req-1") is PairStatusResult.Error)

        assertEquals("上面三条各发了一次请求, 没有额外请求", 3, server.requestCount)
    }

    @Test
    fun status_404_is_an_error() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}"""))
        val result = api().status(device(), "gone")
        assertTrue("实际=$result", result is PairStatusResult.Error)
        assertTrue((result as PairStatusResult.Error).message.isNotBlank())
    }

    @Test
    fun an_unknown_status_value_is_an_error_not_pending() = runTest {
        // 猜成 pending 会让用户白等到超时; 猜成 approved 更糟(没有 token)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"weird"}"""))
        assertTrue(api().status(device(), "req-1") is PairStatusResult.Error)
    }

    @Test
    fun a_malformed_status_body_is_an_error() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        assertTrue(api().status(device(), "req-1") is PairStatusResult.Error)
    }

    @Test
    fun a_network_failure_on_status_is_an_error() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = api().status(device(), "req-1")
        assertTrue("实际=$result", result is PairStatusResult.Error)
    }

    // ── 超时 / client 形状 ───────────────────────────────────────────────────

    @Test
    fun every_pairing_call_has_a_five_second_timeout() {
        // 用默认 client(测试里不要注入关掉重连的那个, 它没有配超时)
        val client = HttpPairingApi(appVersion = appVersion).httpClient
        val expected = (PAIRING_TIMEOUT_SECONDS * 1_000).toInt()
        assertEquals(expected, client.connectTimeoutMillis)
        assertEquals(expected, client.readTimeoutMillis)
        assertEquals(expected, client.writeTimeoutMillis)
        assertEquals("计划要求: 请求/状态各 5 秒", 5_000, client.readTimeoutMillis)
    }
}
