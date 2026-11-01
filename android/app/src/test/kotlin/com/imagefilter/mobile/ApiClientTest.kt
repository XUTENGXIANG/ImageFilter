// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.DecisionDto
import com.imagefilter.mobile.data.MoveStatus
import com.imagefilter.mobile.data.ThumbnailSize
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.XmpErrorCodes
import com.imagefilter.mobile.data.buildHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 契约测试: 全部跑在 **JVM + MockWebServer** 上, 不需要设备也不需要真服务端。
 *
 * 注入 client 时一律用 [buildHttpClient] —— 鉴权头**只**装在那里(见它的 KDoc),
 * 随手 `OkHttpClient()` 会让"Bearer 头有没有发出去"这件事变成假绿。
 */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private val token = "t".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val verbatimPath = "\\\\?\\D:\\照片 2026\\a.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun client() = ApiClient(baseUrl = baseUrl(), token = token, client = buildHttpClient(token))

    // ── 鉴权 ─────────────────────────────────────────────────────────────────

    @Test
    fun sends_bearer_token_on_protected_endpoints() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":["D:\\照片"]}"""))
        val shares = client().shares()
        assertEquals(listOf("D:\\照片"), shares)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/shares", req.path)
        // token 必须以 Bearer 形式发出 —— 这是服务端鉴权的唯一凭据
        assertEquals("Bearer $token", req.getHeader("Authorization"))
    }

    @Test
    fun the_shared_http_client_is_where_the_bearer_header_comes_from() {
        // ApiClient 与 Coil(M1′ Task 5)共用这一个工厂, 所以鉴权头只许装在这里。
        // 空 token 一个字都不发 —— 宁可让服务端按"没配对"处理, 也不发一个假凭据。
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val url = "${baseUrl()}/api/v1/ping"

        buildHttpClient(token).newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))

        buildHttpClient(null).newCall(Request.Builder().url(url).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ── 查询串编码(路径是不透明的) ────────────────────────────────────────────

    @Test
    fun percent_encodes_a_verbatim_windows_path_completely() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client().scan(verbatimPath)
        val req = server.takeRequest()

        val raw = req.path!!
        // 完整百分号编码(实测 OkHttp): 反斜杠/字面问号/冒号/空格/中文全部编码。
        // 未编码的反斜杠会让服务端解析出错误路径; 未编码的字面问号会被当成查询串的开始。
        assertTrue(
            "verbatim 前缀与分隔符必须编码, 实际=$raw",
            raw.contains("path=%5C%5C%3F%5CD%3A%5C"),
        )
        assertTrue("空格必须编码成 %20, 实际=$raw", raw.contains("%202026"))
        assertTrue("非 ASCII 必须编码, 实际=$raw", raw.contains("%E7%85%A7%E7%89%87"))
        val query = raw.substringAfter('?')
        assertFalse("查询串里不许有裸反斜杠: $raw", query.contains('\\'))
        assertFalse("查询串里不许有裸空格: $raw", query.contains(' '))
        assertFalse("查询串里不许有裸中文: $raw", query.contains('照'))

        // 最强的断言: 服务端解出来的必须**逐字节**等于我们给的路径
        assertEquals(verbatimPath, req.requestUrl!!.queryParameter("path"))
    }

    @Test
    fun repeats_the_paths_parameter_once_per_path_for_decisions() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client().readDecisions(listOf(verbatimPath, "D:\\p\\b.jpg"))

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/decisions", req.requestUrl!!.encodedPath)
        // §5.1 的线上格式是**同名键重复**(?paths=a&paths=b), 不是逗号分隔 —— axum 的
        // serde_urlencoded 读不了逗号分隔的 Vec(见 routes.rs::DecisionsQ 的实测)
        assertEquals(
            listOf(verbatimPath, "D:\\p\\b.jpg"),
            req.requestUrl!!.queryParameterValues("paths"),
        )
    }

    // ── 缩略图 / 预览 URL ────────────────────────────────────────────────────

    @Test
    fun the_thumb_size_parameter_only_ever_carries_a_closed_set_member() {
        assertEquals(setOf(150, 300, 600), ThumbnailSize.PIXELS)
        assertEquals(ThumbnailSize.S300, ThumbnailSize.DEFAULT)

        val url = client().thumbnailUrl("D:\\a.jpg", ThumbnailSize.S600, mtime = 1759680000000L).toHttpUrl()
        assertEquals("/api/v1/thumb", url.encodedPath)
        assertEquals("D:\\a.jpg", url.queryParameter("path"))
        assertEquals("600", url.queryParameter("size"))
        // `v`(mtime)服务端不读, 它是给 Coil 用的缓存键 —— 但必须真的在 URL 上
        assertEquals("1759680000000", url.queryParameter("v"))
        assertEquals("闭集之外的档位在类型上就构造不出来", 0, server.requestCount)
    }

    @Test
    fun an_integer_thumb_size_snaps_down_like_the_server_and_below_150_is_a_local_error() {
        val c = client()
        // 与 media.rs::thumbnail_size 同一条规则: 向下吸附
        assertEquals("150", c.thumbnailUrl("D:\\a.jpg", 150, 1L).toHttpUrl().queryParameter("size"))
        assertEquals("150", c.thumbnailUrl("D:\\a.jpg", 299, 1L).toHttpUrl().queryParameter("size"))
        assertEquals("300", c.thumbnailUrl("D:\\a.jpg", 300, 1L).toHttpUrl().queryParameter("size"))
        assertEquals("600", c.thumbnailUrl("D:\\a.jpg", 5000, 1L).toHttpUrl().queryParameter("size"))

        // 比最小档还小 → 服务端一定报 badRequest, 所以在本地就抛错, 请求不发出去
        val e = assertThrows(IllegalArgumentException::class.java) {
            c.thumbnailUrl("D:\\a.jpg", 149, 1L)
        }
        assertTrue("本地错误要说清合法档位: ${e.message}", e.message!!.contains("150"))
        assertThrows(IllegalArgumentException::class.java) { c.thumbnailUrl("D:\\a.jpg", 0, 1L) }
        assertThrows(IllegalArgumentException::class.java) { c.thumbnailUrl("D:\\a.jpg", -1, 1L) }
        assertEquals("非法档位不该发请求", 0, server.requestCount)
    }

    @Test
    fun preview_max_bounds_are_enforced_locally() {
        val c = client()
        assertEquals("64", c.previewUrl("D:\\a.jpg", 64, 1L).toHttpUrl().queryParameter("max"))
        assertEquals("5000", c.previewUrl("D:\\a.jpg", 5000, 1L).toHttpUrl().queryParameter("max"))

        // 服务端对越界 max **不再静默钳制**, 一律 badRequest → 本地先挡, 白跑一趟没意义
        val e = assertThrows(IllegalArgumentException::class.java) { c.previewUrl("D:\\a.jpg", 63, 1L) }
        assertTrue("本地错误要说清区间: ${e.message}", e.message!!.contains("64"))
        assertThrows(IllegalArgumentException::class.java) { c.previewUrl("D:\\a.jpg", 5001, 1L) }
        assertThrows(IllegalArgumentException::class.java) { c.previewUrl("D:\\a.jpg", -1, 1L) }
        assertEquals("非法 max 不该发请求", 0, server.requestCount)
    }

    @Test
    fun preview_sends_full_as_a_boolean_literal_and_omits_it_otherwise() {
        val plain = client().previewUrl("D:\\a.jpg", mtime = 1L).toHttpUrl()
        assertEquals("2048", plain.queryParameter("max"))
        assertNull("不请求全图时 full 整个参数不出现(服务端 #[serde(default)] = false)", plain.queryParameter("full"))

        val full = client().previewUrl("D:\\a.jpg", mtime = 1L, full = true)
        // full 在服务端是 Rust 的 `bool`, 而 serde_urlencoded 解析 bool 走 str::parse::<bool>(),
        // **只认 "true"/"false"** —— 发 full=1 / full=0(规格 §5.1 表格里的示例)会被判成
        // 类型错误 → 400 badRequest。这一条钉住"发的是能解析的那一个字面量"。
        assertEquals("true", full.toHttpUrl().queryParameter("full"))
        assertTrue("实际=$full", full.contains("full=true"))
    }

    // ── 错误映射 ─────────────────────────────────────────────────────────────

    @Test
    fun maps_403_body_code_to_forbiddenPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))
        val e = runCatching { client().scan("C:\\Windows") }.exceptionOrNull()
        assertTrue("实际=$e", e is ApiException)
        assertEquals("forbiddenPath", (e as ApiException).code)
        assertEquals(403, e.httpStatus)
    }

    @Test
    fun maps_401_without_body_to_unauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = runCatching { client().shares() }.exceptionOrNull()
        assertEquals("unauthorized", (e as ApiException).code)
    }

    @Test
    fun a_batch_level_403_on_move_is_an_http_code_not_a_per_item_status() = runTest {
        // 规格 §5.2: "目标已存在" 走 200 的逐项 `status:"conflict"`, 而**批次级**失败
        // (路径越界/不存在)走 §5.3 的 HTTP 码。两处来源必须分得清。
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))
        val e = runCatching { client().move(listOf("C:\\Windows\\a.jpg"), "D:\\dst") }.exceptionOrNull()
        assertEquals("forbiddenPath", (e as ApiException).code)
    }

    @Test
    fun an_empty_batch_is_rejected_locally_without_a_request() = runTest {
        val decisions = runCatching { client().readDecisions(emptyList()) }.exceptionOrNull()
        assertTrue("实际=$decisions", decisions is IllegalArgumentException)
        val move = runCatching { client().move(emptyList(), "D:\\dst") }.exceptionOrNull()
        assertTrue("实际=$move", move is IllegalArgumentException)
        assertEquals("本地就挡住了, 不该白跑一趟", 0, server.requestCount)
    }

    // ── 401 消歧(绝不在第一次 401 上断言"token 被轮换") ──────────────────────

    @Test
    fun a_401_is_token_rotation_only_when_a_known_protected_probe_also_401s() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, client().classifyUnauthorized())

        val probe = server.takeRequest()
        // 复探必须打**已知受保护**的端点, 而且用的还是**同一枚** token
        assertEquals("/api/v1/shares", probe.path)
        assertEquals("Bearer $token", probe.getHeader("Authorization"))
    }

    @Test
    fun a_reachable_probe_that_is_not_401_means_the_path_is_not_exposed() = runTest {
        // 鉴权层罩住整张路由表, 所以"打到了处理器"本身就证明 token 是被接受的
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":[]}"""))
        assertEquals(UnauthorizedCause.ENDPOINT_NOT_EXPOSED, client().classifyUnauthorized())

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))
        assertEquals(
            "复探回 500 也说明 token 过了鉴权(处理器已经跑起来了)",
            UnauthorizedCause.ENDPOINT_NOT_EXPOSED,
            client().classifyUnauthorized(),
        )
    }

    @Test
    fun an_unreachable_probe_leaves_the_cause_undetermined() = runTest {
        // 网络不通/电脑睡了: 复探根本没打通 —— 什么都不许断言。把网络故障说成"去重新配对"
        // 会让用户为一个网络问题去扫码。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val noRetry = buildHttpClient(token).newBuilder().retryOnConnectionFailure(false).build()
        val c = ApiClient(baseUrl = baseUrl(), token = token, client = noRetry)
        assertEquals(UnauthorizedCause.UNDETERMINED, c.classifyUnauthorized())
    }

    // ── 端点往返 ─────────────────────────────────────────────────────────────

    @Test
    fun write_decisions_puts_the_array_and_reads_back_the_per_item_summary() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"written":1,"skipped":0,"failed":1,"failures":[{"path":"D:\\p\\b.jpg","code":"encoding","detail":"边车不是 UTF-8 编码"}],"degraded":false}""",
            ),
        )
        val summary = client().writeDecisions(
            listOf(
                DecisionDto(path = "D:\\p\\a.jpg", rating = 3),
                DecisionDto(path = "D:\\p\\b.jpg", clearLabel = true),
            ),
        )

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/decisions", req.requestUrl!!.encodedPath)
        assertTrue("实际=${req.getHeader("Content-Type")}", req.getHeader("Content-Type")!!.startsWith("application/json"))
        // 值为 null 的字段**不写出去**: 服务端把"缺字段"与 null 同等对待(Option 的 serde 默认),
        // 而 clearLabel 缺字段 = false 与 #[serde(default)] 一致 —— 少写就是"不动这个属性"。
        assertEquals(
            """[{"path":"D:\\p\\a.jpg","rating":3},{"path":"D:\\p\\b.jpg","clearLabel":true}]""",
            req.body.readUtf8(),
        )

        assertEquals(1, summary.written)
        assertEquals(1, summary.failed)
        assertFalse(summary.degraded)
        // 逐项失败的码查 **XMP 表**(readOnly/permission/encoding…在 HTTP 表里不存在)
        assertEquals("encoding", summary.failures.single().code)
        assertEquals("xmp.error.encoding", XmpErrorCodes.messageKey(summary.failures.single().code))
        assertEquals(ApiErrorCodes.UNKNOWN_KEY, ApiErrorCodes.messageKey(summary.failures.single().code))
    }

    @Test
    fun move_posts_paths_and_dest_dir_and_returns_the_five_field_items() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"moved":1,"halfSuccess":1,"conflict":0,"failed":0,"items":[
                  {"src":"D:\\p\\a.jpg","dest":"D:\\p\\dst\\a.jpg","status":"halfSuccess",
                   "detail":"照片已移动, 但边车 a.xmp 没能跟着走, 需要重试",
                   "pendingSidecars":["D:\\p\\a.xmp"]},
                  {"src":"D:\\p\\b.jpg","dest":"D:\\p\\dst\\b.jpg","status":"moved","detail":null,"pendingSidecars":[]}
                ]}
                """.trimIndent(),
            ),
        )
        val summary = client().move(listOf("D:\\p\\a.jpg", "D:\\p\\b.jpg"), "D:\\p\\dst")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/move", req.requestUrl!!.encodedPath)
        // destDir 的键名逐字对应服务端 MoveReq 的 rename_all = "camelCase"
        assertEquals(
            """{"paths":["D:\\p\\a.jpg","D:\\p\\b.jpg"],"destDir":"D:\\p\\dst"}""",
            req.body.readUtf8(),
        )

        assertEquals(1, summary.moved)
        assertEquals(1, summary.halfSuccess)
        assertEquals(MoveStatus.HALF_SUCCESS, summary.items[0].moveStatus)
        assertEquals(MoveStatus.MOVED, summary.items[1].moveStatus)
        // 半成功项的重试只带 pendingSidecars(手机不推导边车命名规则, 也不重发已搬走的 src)
        assertEquals(listOf("D:\\p\\a.xmp"), summary.items[0].pendingSidecarRetry()!!.paths)
        assertEquals("D:\\p\\dst", summary.items[0].pendingSidecarRetry()!!.destDir)
        assertNull("moved 项没有待重试的东西", summary.items[1].pendingSidecarRetry())
    }

    @Test
    fun ping_parses_the_public_endpoint_shape() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"ImageFilter","version":"1.1.2","requiresToken":true}"""))
        val ping = client().ping()
        assertEquals("ImageFilter", ping.name)
        assertEquals("1.1.2", ping.version)
        assertTrue(ping.requiresToken)
        assertEquals("/api/v1/ping", server.takeRequest().path)
    }

    @Test
    fun request_sync_posts_device_and_pending_items() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"requestId":"r1","expiresAt":1700000000000}"""),
        )
        val challenge = client().requestSync("Pixel 9", 3)
        assertEquals("r1", challenge.requestId)
        assertEquals(1700000000000L, challenge.expiresAt)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/sync/request", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertEquals(
            """{"deviceName":"Pixel 9","pendingItems":3}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun sync_status_gets_the_request_id_and_reads_the_closed_status() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"requestId":"r1","status":"approved"}"""),
        )
        val status = client().syncStatus("r1")
        assertEquals("r1", status.requestId)
        assertEquals("approved", status.status)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/sync/status", req.requestUrl!!.encodedPath)
        assertEquals("r1", req.requestUrl!!.queryParameter("requestId"))
    }
}
