// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.SharedHttpClient
import com.imagefilter.mobile.data.ThumbnailSize
import com.imagefilter.mobile.data.buildHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 缩略图网格的**供给端**契约: ApiClient 与 Coil 拿到的必须是**同一个** OkHttpClient。
 *
 * 为什么值得单测: 网格那一侧(Coil)在 JVM 上摸不到(要 Android Context), 但"谁供给
 * client"是可测的 —— 而真实事故正是从这里来的: 各建一个客户端 → 图片那条路没有
 * Bearer 头 → **JSON 接口一切正常、每一格缩略图都 401**(极难查)。
 *
 * 这里钉两件事:
 *   ① 同一枚 token 永远拿到**同一个实例**(连接池/线程池不翻倍, 且 Coil 与 ApiClient 是同一个);
 *   ② 那个实例的鉴权头**真的**会出现在缩略图请求上(不是"应该会")。
 */
class SharedHttpClientTest {

    private lateinit var server: MockWebServer
    private val token = "h".repeat(64)
    private val otherToken = "k".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val verbatimPath = "\\\\?\\D:\\照片 2026\\a.jpg"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun the_same_token_always_yields_the_same_instance() {
        val first = SharedHttpClient.forToken(token)
        val second = SharedHttpClient.forToken(token)

        // 同一个**实例**(不是"配置相同的新实例"): 这条就是"Coil 与 ApiClient 共用一个客户端"
        assertSame("同一枚 token 必须复用同一个实例", first, second)

        // 换 token(重新配对)必须换实例 —— 否则新 token 会继续用旧拦截器里的旧凭据
        val other = SharedHttpClient.forToken(otherToken)
        assertNotSame("token 变了就必须换一个客户端", first, other)
    }

    @Test
    fun a_thumbnail_request_through_the_shared_client_carries_the_bearer_header() {
        server.enqueue(MockResponse().setResponseCode(200))

        // 与网格完全同一条路径: URL 由 ApiClient 拼(编码/闭集/v 都在那一处), 请求由共用 client 发
        val api = ApiClient(baseUrl = baseUrl(), token = token)
        val url = api.thumbnailUrl(verbatimPath, ThumbnailSize.S300, mtime = 1_759_680_000_000L)
        SharedHttpClient.forToken(token).newCall(Request.Builder().url(url).build()).execute().use { }

        val req = server.takeRequest()
        assertEquals("/api/v1/thumb", req.requestUrl!!.encodedPath)
        assertEquals("缩略图端点同样要 Bearer —— 漏了这一条就是「JSON 通、图片 401」", "Bearer $token", req.getHeader("Authorization"))
        assertEquals("300", req.requestUrl!!.queryParameter("size"))
        assertEquals("1759680000000", req.requestUrl!!.queryParameter("v"))
        // 路径是不透明的: 逐字往返(自己拼一次就会丢掉 verbatim 前缀里的字面问号)
        assertEquals(verbatimPath, req.requestUrl!!.queryParameter("path"))
    }

    @Test
    fun each_token_keeps_its_own_header_so_the_memo_never_leaks_a_credential() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val url = "${baseUrl()}/api/v1/thumb"

        SharedHttpClient.forToken(token)
            .newCall(Request.Builder().url(url).build()).execute().use { }
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))

        // 重新配对(换了 token)之后: 旧 token 绝不许还挂在客户端上
        SharedHttpClient.forToken(otherToken)
            .newCall(Request.Builder().url(url).build()).execute().use { }
        assertEquals("Bearer $otherToken", server.takeRequest().getHeader("Authorization"))
    }

    /**
     * **Task 2 的核心不变量, 机器可查**: `ApiClient` **默认**用的 client **就是**
     * [SharedHttpClient.forToken] 记忆的那一枚 —— 也就是 Coil(缩略图网格)拿到的同一枚。
     *
     * 为什么单独立一条: 上面两条只钉住两头 —— 供给端(同一枚 token 同一个实例)与请求端
     * (默认构造出来的请求带 Bearer)。中间那一步原先只是一句 KDoc, 于是把 `ApiClient` 的默认值
     * 从 `SharedHttpClient.forToken(token)` 换成 [buildHttpClient]`(token)` 时, **169 条 Android
     * 测试全绿** —— 而生产上 Coil 与 JSON 接口从此各拿一个客户端(连接池/线程池翻倍), 正是
     * Task 2 要消灭的"JSON 通、图片 401"那类分裂的温床。
     *
     * `assertSame` 而不是 `assertEquals`: `OkHttpClient` 没有值相等语义, 这条要的就**是**同一个
     * 对象(共享连接池/线程池/拦截器的前提)。
     *
     * 变异靶子: 把 `ApiClient` 的默认值换成 `buildHttpClient(token)` → 这条当场红。
     */
    @Test
    fun the_default_api_client_is_the_memoised_shared_client() {
        val api = ApiClient(baseUrl = baseUrl(), token = token)

        assertSame(
            "ApiClient 默认用的必须是 SharedHttpClient 记忆的那一枚(Coil 也是它)",
            SharedHttpClient.forToken(token),
            api.httpClient,
        )

        // 反向也钉住: 注入点必须仍然能换掉 client(测试要关 retryOnConnectionFailure 就得能换),
        // 而且**换掉之后就不算共用** —— 这条防的是把访问器写成"永远返回那枚 memo"的常量,
        // 那会让上面那句 assertSame 变成一句空转。
        val injected = buildHttpClient(token)
        assertNotSame(
            "注入了自己的 client 之后就不该再是共用那一枚",
            SharedHttpClient.forToken(token),
            ApiClient(baseUrl(), token, injected).httpClient,
        )
    }
}
