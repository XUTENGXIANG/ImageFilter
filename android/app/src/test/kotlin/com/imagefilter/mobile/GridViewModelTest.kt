// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.ThumbnailSize
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.GRID_COUNT_UNKNOWN
import com.imagefilter.mobile.ui.GRID_EMPTY
import com.imagefilter.mobile.ui.GridViewModel
import com.imagefilter.mobile.ui.gridCountLabel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 网格状态机的**真行为**测试: JVM + MockWebServer, 走真的 HTTP, 用真的 [ApiClient]。
 *
 * 注入 client 时一律以 [buildHttpClient] 为底 —— 鉴权头**只**装在那里(Task 2 的纪律),
 * 随手 `OkHttpClient()` 会让"token 有没有发出去"这类断言变成假绿。
 *
 * ⚠️ **这里测不到的东西(不许假装测到了)**: `rememberAppImageLoader` 那个 `@Composable`
 * 需要 Android `Context`, 本节不引 Robolectric, 所以"Coil 到底拿的是哪个 client"
 * 在 JVM 上断言不了 —— 那一条只能靠 `SharedHttpClientTest` 钉住**供给端**(同一枚 token
 * 只有一个实例、那个实例带 Bearer), 加上真机清单(报告里列为 UNVERIFIED)。
 *
 * 这里钉的五件事, 每一件都对应一种真实会发生的坏结果:
 *   ① **格子来自 `scan` 的返回, 顺序逐字保留**(服务端排过序, 手机重排只会与电脑上不一致);
 *   ② **每格 URL 是 `size=300` + `v=<mtime>`**(闭集档位、缓存失效键);
 *   ③ **空文件夹不是错误, 也不是空白网格**(有话说, 且不许说成"张数未统计");
 *   ④ **失败时不许把"没读到"说成"0 张"**, 且文案要是可执行的那句;
 *   ⑤ **401 走消歧**、**失败不清空已有格子**。
 */
class GridViewModelTest {

    private lateinit var server: MockWebServer
    private val token = "g".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val verbatimFolder = "\\\\?\\D:\\照片 2026"

    private val folder = FolderEntryDto(path = verbatimFolder, name = "照片 2026")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    /**
     * 注入的工厂: 关掉连接重试 + 可调超时, 让"服务死了"这类路径能被**确定地**走一遍
     * (与 BrowseViewModelTest / PairingValidatorTest 同一条理由)。
     */
    private fun viewModel(noRetry: Boolean = false, readTimeoutMs: Long = 10_000) = GridViewModel(
        pairing = Pairing(baseUrl(), token, note = "家里的台式机"),
        folder = folder,
        clientFactory = { url, tkn ->
            ApiClient(
                url,
                tkn,
                buildHttpClient(tkn).newBuilder()
                    .apply { if (noRetry) retryOnConnectionFailure(false) }
                    .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                    .build(),
            )
        },
    )

    // ── 服务端响应(逐字写 JSON, 不拿 DTO 反向编码 —— 那样字段名写错会两侧一起错) ──

    /** JSON 字符串字面量: 路径里的反斜杠必须转义, 手写太容易错, 所以只写这一处。 */
    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

    private fun photo(
        path: String,
        fileName: String,
        modifiedAt: Long,
        isRaw: Boolean = false,
        isVideo: Boolean = false,
    ) = """{"path":${jq(path)},"fileName":${jq(fileName)},"fileSize":4096,""" +
        """"isRaw":$isRaw,"isVideo":$isVideo,"modifiedAt":$modifiedAt,"exif":{}}"""

    private fun scanOk(vararg items: String) = MockResponse()
        .setResponseCode(200)
        .setBody("[${items.joinToString(",")}]")

    // ── ① 格子来自 scan, 顺序不改 ───────────────────────────────────────────

    @Test
    fun a_successful_scan_becomes_one_cell_per_photo_in_the_servers_order() = runTest {
        server.enqueue(
            scanOk(
                photo("$verbatimFolder\\a.jpg", "a.jpg", 1_759_680_000_000L),
                photo("$verbatimFolder\\b.arw", "b.arw", 1_759_680_001_000L, isRaw = true),
                photo("$verbatimFolder\\c.mp4", "c.mp4", 1_759_680_002_000L, isVideo = true),
            ),
        )

        val vm = viewModel()
        vm.load()

        val s = vm.state.value
        assertEquals(
            "格子的顺序**逐字**是服务端给的顺序(scan_directory 按文件名排过序, 手机不重排)",
            listOf("a.jpg", "b.arw", "c.mp4"),
            s.items.map { it.displayName },
        )
        assertEquals("3 张", s.countLabel)
        assertTrue("有照片就是有照片", s.hasPhotos)
        assertFalse(s.loading)
        assertNull(s.error)
        assertFalse("有照片时不该说空", s.isEmpty)
        assertNull(s.emptyMessage)
        // 服务端给的 path 逐字进格子(它是这个文件在电脑上的身份)
        assertEquals("$verbatimFolder\\b.arw", s.items[1].path)
        assertTrue("RAW 标记要跟着格子走(全 RAW 的提示靠它)", s.items[1].isRaw)
        assertTrue(s.items[2].isVideo)

        val req = server.takeRequest()
        assertEquals("/api/v1/scan", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        // 路径是不透明的: 逐字往返(verbatim 前缀里的字面问号若不编码会被当成查询串的开始)
        assertEquals(verbatimFolder, req.requestUrl!!.queryParameter("path"))
        assertFalse("查询串里不许有裸反斜杠: ${req.path}", req.path!!.substringAfter('?').contains('\\'))
        assertEquals("只许发这一个请求", 1, server.requestCount)
    }

    // ── ② 每格的 URL: 闭集档位 + mtime ──────────────────────────────────────

    @Test
    fun every_cell_asks_for_the_300_step_and_carries_the_mtime_as_the_cache_key() = runTest {
        val mtimes = listOf(1_759_680_000_000L, 1_759_680_001_000L)
        server.enqueue(
            scanOk(
                photo("$verbatimFolder\\a.jpg", "a.jpg", mtimes[0]),
                photo("$verbatimFolder\\b.jpg", "b.jpg", mtimes[1]),
            ),
        )

        val vm = viewModel()
        vm.load()

        val items = vm.state.value.items
        assertEquals(2, items.size)
        for ((i, item) in items.withIndex()) {
            val url = item.thumbnailUrl.toHttpUrl()
            assertEquals("/api/v1/thumb", url.encodedPath)
            // size 只能取自闭集 {150,300,600}, 而网格的档位是 300(规格 §5.2 的默认档)
            assertTrue(
                "size=${url.queryParameter("size")} 不在闭集里",
                url.queryParameter("size")!!.toInt() in ThumbnailSize.PIXELS,
            )
            assertEquals("网格请求的档位", "300", url.queryParameter("size"))
            // v=<mtime>: 电脑上换掉照片后 Coil 的磁盘缓存必须失效(服务端不读它, 纯客户端约定)
            assertEquals(mtimes[i].toString(), url.queryParameter("v"))
            // 必须与格子的 path 是同一个值(不是"看起来像"的另一个字符串)
            assertEquals(item.path, url.queryParameter("path"))
        }
    }

    // ── ③ 空文件夹: 有话说, 而且不许说成"张数未统计" ────────────────────────

    @Test
    fun an_empty_scan_says_so_instead_of_drawing_a_blank_grid() = runTest {
        server.enqueue(scanOk())

        val vm = viewModel()
        vm.load()

        val s = vm.state.value
        assertTrue("200 + 0 张是**状态**, 不是错误", s.isEmpty)
        assertNull(s.error)
        assertFalse(s.loading)
        assertEquals("0 张", s.countLabel)
        assertEquals("空文件夹的文案只有一处出处", GRID_EMPTY, s.emptyMessage)
        assertTrue("空文件夹也要给一句人话: $GRID_EMPTY", GRID_EMPTY.length >= 30)
        // 这一条是刻意的: 浏览页的 photoCountLabel 把 0 说成"张数未统计"(那里 0 本来就没有权威值),
        // 而网格的张数来自 scan 的**返回长度** —— 这里的 0 就是真的 0 张。
        assertNotEquals(
            "scan 的 0 是真的 0 张, 不许照抄浏览页那句",
            "张数未统计",
            s.countLabel,
        )
        assertNull("空文件夹不是全 RAW 提示的场合(那是「有照片」)", s.notice)
        assertEquals("空文件夹也要真的问过服务端", 1, server.requestCount)
    }

    // ── ④ 全 RAW: 有照片, 但要说清这条链路不一样 ────────────────────────────

    @Test
    fun a_folder_of_raw_only_is_still_shown_and_says_why_it_can_be_slow() = runTest {
        server.enqueue(
            scanOk(
                photo("$verbatimFolder\\a.arw", "a.arw", 1L, isRaw = true),
                photo("$verbatimFolder\\b.dng", "b.dng", 2L, isRaw = true),
            ),
        )

        val vm = viewModel()
        vm.load()

        val s = vm.state.value
        assertTrue("全 RAW 也是照片, 网格照画(不许显示成空文件夹)", s.hasPhotos)
        assertFalse(s.isEmpty)
        assertNull(s.emptyMessage)
        val notice = s.notice
        assertTrue("全 RAW 必须说明白: $notice", notice != null && notice.contains("RAW"))
        assertNotEquals("全 RAW 与「没有照片」是两句话", GRID_EMPTY, notice)
    }

    @Test
    fun a_mixed_folder_gets_no_raw_notice_because_it_would_be_noise() = runTest {
        server.enqueue(
            scanOk(
                photo("$verbatimFolder\\a.arw", "a.arw", 1L, isRaw = true),
                photo("$verbatimFolder\\b.jpg", "b.jpg", 2L),
            ),
        )

        val vm = viewModel()
        vm.load()

        assertNull("只有**全是** RAW 才给提示", vm.state.value.notice)
        assertEquals(2, vm.state.value.items.size)
    }

    // ── ⑤ 失败: 说人话、有动作、而且不许把"没读到"说成"0 张" ────────────────

    @Test
    fun a_failed_scan_gives_the_actionable_sentence_and_never_claims_zero_photos() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"notFound"}"""))

        val vm = viewModel()
        vm.load()

        val s = vm.state.value
        val err = s.error!!
        assertEquals(ApiErrorCodes.NOT_FOUND, err.code)
        assertTrue("404 要说目录没了: ${err.text()}", err.text().contains("找不到"))
        assertNull("401 的消歧只对 401 生效, 404 不许去复探", err.cause)
        assertFalse("失败必须结束 loading, 否则界面会一直转圈", s.loading)
        assertFalse(s.isEmpty)
        assertNull(s.emptyMessage)
        // "没读到" ≠ "0 张": 顶部唯一显示的那个字符串必须说实话
        assertEquals(GRID_COUNT_UNKNOWN, s.countLabel)
        assertNotEquals("0 张", s.countLabel)
        assertEquals("404 只需要一次 scan, 复探一次都不该发", 1, server.requestCount)
    }

    @Test
    fun a_dead_service_is_service_off_and_stops_the_spinner() = runTest {
        server.shutdown()

        val vm = viewModel(noRetry = true, readTimeoutMs = 300)
        vm.load()

        val s = vm.state.value
        val err = s.error!!
        assertEquals(ApiErrorCodes.SERVICE_OFF, err.code)
        assertFalse(s.loading)
        assertFalse(s.hasPhotos)
        // 合成码走的是配对页那张表(用户动作一样: 去电脑上把服务开起来), 不是兜底句
        assertEquals(PairingMessages.forFailure(ApiErrorCodes.SERVICE_OFF), err.text())
        assertTrue("要说清去哪一侧找原因: ${err.text()}", err.text().contains("连不上电脑"))
    }

    @Test
    fun a_401_is_disambiguated_before_any_text_is_shown() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))

        val vm = viewModel()
        vm.load()

        val err = vm.state.value.error!!
        assertEquals(ApiErrorCodes.UNAUTHORIZED, err.code)
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, err.cause)
        assertTrue("确认 token 失效才让用户去重新配对: ${err.text()}", err.text().contains("配对码"))

        assertEquals("/api/v1/scan", server.takeRequest().requestUrl!!.encodedPath)
        // 复探打的是**同一个**已知受保护端点(§5.1 的 shares), 不带路径参数
        val probe = server.takeRequest()
        assertEquals("/api/v1/shares", probe.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", probe.getHeader("Authorization"))
    }

    @Test
    fun a_failed_refresh_keeps_the_photos_already_on_the_screen() = runTest {
        server.enqueue(scanOk(photo("$verbatimFolder\\a.jpg", "a.jpg", 1L)))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))

        val vm = viewModel()
        vm.load()
        assertEquals(1, vm.state.value.items.size)

        vm.load()

        val s = vm.state.value
        assertEquals("刷新失败不许把用户眼前的照片清空", 1, s.items.size)
        assertEquals(ApiErrorCodes.INTERNAL, s.error!!.code)
        assertFalse(s.loading)
        // 有格子时顶部说的还是格子数(不是"没读到")
        assertEquals("1 张", s.countLabel)
    }

    // ── 生产路径: 默认工厂 = ApiClient(SharedHttpClient.forToken) ────────────

    @Test
    fun the_production_default_scans_through_the_shared_authenticated_client() = runTest {
        // 这一条**不注入** clientFactory: 走的就是 GridViewModel 的默认路径。
        // 它红掉只有两种可能: 默认 client 没装鉴权头, 或者根本没带 token。
        val prodToken = "s".repeat(64)
        server.enqueue(scanOk(photo("$verbatimFolder\\a.jpg", "a.jpg", 7L)))

        val vm = GridViewModel(
            pairing = Pairing(baseUrl(), prodToken, note = "家里的台式机"),
            folder = folder,
        )
        vm.load()

        assertEquals(1, vm.state.value.items.size)
        val req = server.takeRequest()
        assertEquals("Bearer $prodToken", req.getHeader("Authorization"))
        assertEquals("/api/v1/scan", req.requestUrl!!.encodedPath)
        assertEquals("300", vm.state.value.items.single().thumbnailUrl.toHttpUrl().queryParameter("size"))
        assertEquals("7", vm.state.value.items.single().thumbnailUrl.toHttpUrl().queryParameter("v"))
    }

    @Test
    fun the_count_label_is_a_plain_number_of_photos() {
        // 纯函数: 网格的张数没有"未统计"这一档(与浏览页的 photoCountLabel 刻意不同)
        assertEquals("0 张", gridCountLabel(0))
        assertEquals("1 张", gridCountLabel(1))
        assertEquals("412 张", gridCountLabel(412))
    }
}
