// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.BrowseViewModel
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.TimeUnit

/**
 * 目录页状态机的**真行为**测试: JVM + MockWebServer, 走真的 HTTP, 用真的 [ApiClient]。
 *
 * 注入 client 时一律以 [buildHttpClient] 为底 —— 鉴权头**只**装在那里(Task 2 的纪律),
 * 随手 `OkHttpClient()` 会让"token 有没有发出去"这类断言变成假绿。
 *
 * 这里钉的五件事, 每一件都能用一条真实事故说明为什么要钉:
 *   ① **懒加载 / 单层** —— 服务端只给直接子目录, 点一个节点只许发一个请求
 *      (桌面端"点设备=整盘遍历"的坑);
 *   ② **不透明的路径逐字往返** —— verbatim 前缀里有字面问号, 自己拼一次就丢;
 *   ③ **空共享根是合法状态**, 不是错误;
 *   ④ **403 与 404 说两句不同的话**;
 *   ⑤ **401 必须走消歧** —— 第一次 401 不许当成 token 被轮换。
 */
class BrowseViewModelTest {

    private lateinit var server: MockWebServer
    private val token = "b".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val verbatimRoot = "\\\\?\\D:\\照片 2026"
    private val outside = "D:\\Windows"

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
     * 生产路径是默认工厂; 关掉连接重试 + 缩短读超时, 是为了让"复探打不通"这类
     * 路径能被**确定地**走一遍(与 PairingValidatorTest 同一条理由)。
     */
    private fun viewModel(noRetry: Boolean = false, readTimeoutMs: Long = 10_000) = BrowseViewModel(
        pairing = Pairing(baseUrl(), token, note = "家里的台式机"),
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

    private fun sharesOk(vararg paths: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""{"shares":[${paths.joinToString(",") { jq(it) }}]}""")

    private fun childJson(path: String, name: String, hasSubdirs: Boolean = false, nested: List<String> = emptyList()) =
        """{"path":${jq(path)},"name":${jq(name)},"photoCount":0,"hasSubdirs":$hasSubdirs,""" +
            """"subfolders":[${nested.joinToString(",")}]}"""

    private fun browseOk(path: String, name: String, vararg children: String) = MockResponse()
        .setResponseCode(200)
        .setBody(
            """{"path":${jq(path)},"name":${jq(name)},"photoCount":0,"hasSubdirs":${children.isNotEmpty()},""" +
                """"subfolders":[${children.joinToString(",")}]}""",
        )

    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}""")

    // ── 启动: 只拉共享根 ────────────────────────────────────────────────────

    @Test
    fun loads_the_share_roots_and_never_asks_for_drives() = runTest {
        server.enqueue(sharesOk(verbatimRoot, "E:\\图库"))

        val vm = viewModel()
        vm.loadRoots()

        val s = vm.state.value
        assertEquals(listOf(verbatimRoot, "E:\\图库"), s.roots.map { it.path })
        assertEquals(listOf("照片 2026", "图库"), s.roots.map { it.name })
        assertNull(s.rootsError)
        assertFalse(s.rootsLoading)
        // **启动时一层都不预取**(懒加载): index 必须是空的
        assertTrue("启动不该预取任何一层: ${s.index.keys}", s.index.isEmpty())

        val req = server.takeRequest()
        assertEquals("/api/v1/shares", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        // 设备/盘符(`/drives`)是给"电脑上插了卡"用的, M4′ 才暴露 —— 这里的默认入口不是它
        assertEquals("只许发这一个请求", 1, server.requestCount)
    }

    // ── 懒加载 + 不透明路径 ─────────────────────────────────────────────────

    @Test
    fun opening_a_node_fetches_only_that_one_level_with_the_path_byte_for_byte() = runTest {
        val childPath = "$verbatimRoot\\2026-01"
        val grandChildPath = "$childPath\\01-01"
        server.enqueue(sharesOk(verbatimRoot))
        // 服务端**单层**语义: 子目录项自己也带一个(空)subfolders。这里刻意塞一层孙辈进去,
        // 钉住"我们只吃这一层的直接子目录, 不去展开别人给我们的嵌套"。
        server.enqueue(
            browseOk(
                verbatimRoot,
                "照片 2026",
                childJson(childPath, "2026-01", hasSubdirs = true, nested = listOf(childJson(grandChildPath, "01-01"))),
            ),
        )

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)

        val s = vm.state.value
        assertEquals(verbatimRoot, s.selected)
        assertEquals(listOf("2026-01"), s.children(verbatimRoot).map { it.name })
        assertTrue(s.isLoaded(verbatimRoot))
        // 孙辈既不在索引里, 也没有被请求过 —— 它要等自己被点开
        assertEquals("只许加载被点开的那一层", setOf(verbatimRoot), s.index.keys)
        assertFalse(s.isLoaded(childPath))

        val shares = server.takeRequest()
        val browse = server.takeRequest()
        assertEquals("/api/v1/shares", shares.requestUrl!!.encodedPath)
        assertEquals("/api/v1/browse", browse.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", browse.getHeader("Authorization"))

        // 路径是**不透明**的: 逐字往返。verbatim 前缀里的字面问号若不编码, 会被当成查询串的开始
        assertEquals(verbatimRoot, browse.requestUrl!!.queryParameter("path"))
        assertTrue("字面问号必须编码, 实际=${browse.path}", browse.path!!.contains("%3F"))
        assertFalse("查询串里不许有裸反斜杠: ${browse.path}", browse.path!!.substringAfter('?').contains('\\'))
        assertFalse("查询串里不许有裸空格: ${browse.path}", browse.path!!.substringAfter('?').contains(' '))
    }

    @Test
    fun re_opening_an_already_loaded_folder_does_not_refetch_but_retry_does() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(browseOk(verbatimRoot, "照片 2026", childJson("$verbatimRoot\\a", "a")))

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)
        assertEquals(2, server.requestCount)

        // 再点一次同一个节点: 展开是幂等的, 不许变成一串重复请求(慢盘上很容易连点)
        vm.open(verbatimRoot)
        assertEquals("已加载的层不该重复请求", 2, server.requestCount)
        assertEquals(listOf("a"), vm.state.value.children(verbatimRoot).map { it.name })

        // 用户按「重试」= 他要新数据, 这一条必须**强制**重拉
        server.enqueue(browseOk(verbatimRoot, "照片 2026", childJson("$verbatimRoot\\b", "b")))
        vm.retry(verbatimRoot)
        assertEquals(3, server.requestCount)
        assertEquals("重试的结果必须替换掉旧的那一层", listOf("b"), vm.state.value.children(verbatimRoot).map { it.name })
    }

    // ── 空是状态, 不是错误 ──────────────────────────────────────────────────

    @Test
    fun an_empty_share_list_is_a_legitimate_state_not_a_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":[]}"""))

        val vm = viewModel()
        vm.loadRoots()

        val s = vm.state.value
        assertFalse(s.hasRoots)
        assertNull("空共享根是服务端有意的安全默认, 不是错误", s.rootsError)
        assertFalse(s.rootsLoading)
        assertTrue(s.roots.isEmpty())
    }

    @Test
    fun an_empty_folder_is_loaded_and_is_not_an_error() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(browseOk(verbatimRoot, "照片 2026"))

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)

        val s = vm.state.value
        // "加载过且是空的" 与 "还没加载" 是两件事: UI 靠它决定说"空目录"还是画占位行
        assertTrue(s.isLoaded(verbatimRoot))
        assertTrue(s.children(verbatimRoot).isEmpty())
        assertNull("空目录不是错误(与 403/404 必须分开)", s.errorAt(verbatimRoot))
    }

    // ── 403 / 404 分开说 ────────────────────────────────────────────────────

    @Test
    fun a_403_on_a_path_becomes_an_action_to_fix_the_shares() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(browseOk(verbatimRoot, "照片 2026", childJson(outside, "Windows")))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)
        vm.open(outside)

        val err = vm.state.value.errorAt(outside)!!
        assertEquals(ApiErrorCodes.FORBIDDEN_PATH, err.code)
        assertNull("401 的消歧只对 401 生效, 403 不许去复探", err.cause)
        assertTrue("403 必须说到共享根: ${err.text()}", err.text().contains("共享根"))
        assertEquals("403 只需要 shares + 两次 browse, 复探一次都不该发", 3, server.requestCount)
        // 失败不该把已加载的那一层弄丢(用户还能看到别的目录)
        assertEquals(listOf("Windows"), vm.state.value.children(verbatimRoot).map { it.name })
    }

    @Test
    fun a_404_says_the_folder_is_gone_rather_than_that_it_is_unshared() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(browseOk(verbatimRoot, "照片 2026", childJson(outside, "Windows")))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"notFound"}"""))

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)
        vm.open(outside)

        val err = vm.state.value.errorAt(outside)!!
        assertEquals(ApiErrorCodes.NOT_FOUND, err.code)
        assertTrue("404 要说目录没了: ${err.text()}", err.text().contains("找不到"))
        assertFalse("404 不该让用户去动共享根: ${err.text()}", err.text().contains("共享根"))
        assertFalse("加载失败不许留下半份假数据", vm.state.value.isLoaded(outside))
    }

    // ── 401: 消歧 ───────────────────────────────────────────────────────────

    @Test
    fun a_401_is_disambiguated_before_any_text_is_shown() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(unauthorized())   // browse
        server.enqueue(unauthorized())   // 复探(同一个受保护端点)

        val vm = viewModel()
        vm.loadRoots()
        vm.open(verbatimRoot)

        val err = vm.state.value.errorAt(verbatimRoot)!!
        assertEquals(ApiErrorCodes.UNAUTHORIZED, err.code)
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, err.cause)
        assertTrue("确认 token 失效才让用户去重新配对: ${err.text()}", err.text().contains("配对码"))

        server.takeRequest()
        assertEquals("/api/v1/browse", server.takeRequest().requestUrl!!.encodedPath)
        // 复探打的是**同一个**已知受保护端点(§5.1 的 shares), 不带路径参数
        assertEquals("/api/v1/shares", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun a_401_whose_probe_cannot_be_completed_never_blames_the_token() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val vm = viewModel(noRetry = true, readTimeoutMs = 300)
        vm.loadRoots()
        vm.open(verbatimRoot)

        val err = vm.state.value.errorAt(verbatimRoot)!!
        assertEquals(UnauthorizedCause.UNDETERMINED, err.cause)
        assertFalse(
            "复探打不通时**绝不许**说配对码不对(那会把网络故障说成 token 问题): ${err.text()}",
            err.text().contains("配对码不对"),
        )
        assertTrue("要说清是网络/电脑那一侧: ${err.text()}", err.text().contains("网络"))
    }

    // ── 刷新失败不清空 ──────────────────────────────────────────────────────

    @Test
    fun a_failed_refresh_keeps_the_roots_already_on_screen_and_reports_the_failure() = runTest {
        server.enqueue(sharesOk(verbatimRoot))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"internal"}"""))

        val vm = viewModel()
        vm.loadRoots()
        vm.loadRoots()

        val s = vm.state.value
        assertEquals("刷新失败不许把用户眼前的树清空", listOf(verbatimRoot), s.roots.map { it.path })
        assertEquals(ApiErrorCodes.INTERNAL, s.rootsError!!.code)
        assertFalse(s.rootsLoading)
    }

    // ── 服务根本没在应答 ────────────────────────────────────────────────────

    @Test
    fun a_dead_service_is_service_off_not_an_unknown_error() = runTest {
        server.shutdown()

        val vm = viewModel(noRetry = true)
        vm.loadRoots()

        val err = vm.state.value.rootsError!!
        assertEquals(ApiErrorCodes.SERVICE_OFF, err.code)
        // 合成码走的是配对页那张表(用户动作一样: 去电脑上把服务开起来), 不是兜底句
        assertEquals(PairingMessages.forFailure(ApiErrorCodes.SERVICE_OFF), err.text())
        assertFalse(err.text() == BrowseMessages.GENERIC)
        assertTrue("要说清去哪一侧找原因: ${err.text()}", err.text().contains("连不上电脑"))
    }
}
