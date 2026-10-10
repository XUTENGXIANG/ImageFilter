// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.PREVIEW_MAX_DEFAULT
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.ViewerAction
import com.imagefilter.mobile.data.ViewerMessages
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.ViewerQuality
import com.imagefilter.mobile.ui.ViewerViewModel
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
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 查看器状态机的**真行为**测试: JVM + MockWebServer, 走真的 HTTP(只在 401 消歧那一步),
 * 用真的 [ApiClient] 拼 URL。
 *
 * 这里钉的六件事, 每一件都对应一种真实会发生的坏结果:
 *   ① **默认档 = `max=2048` 且不带 `full`**(默认档的图像源是相机的内嵌预览, 不是 RAW 的画质);
 *   ② **高清档 = 同一个 `max` + `full=true`** —— 服务端的 `full` 是 Rust 的 `bool`,
 *      `serde_urlencoded` 只认 "true"/"false", **`full=1` 是 400 badRequest**(M1′ 实测);
 *   ③ **预取永远是默认档, 且只预取左右两张**(高清预取 = 每滑一张都等一次全解码);
 *   ④ **高清失败退回默认档 + 一句可执行的话**, 绝不留白屏;
 *   ⑤ **切图一定回到默认档**(把高清态带过去, 每张都要等一次全解码);
 *   ⑥ **403/404/超时 说的话与要做的动作都不一样**, 且 401 仍走消歧(不许当场断言 token 失效)。
 *
 * ⚠️ **这里测不到的东西(不许假装测到了)**: `AsyncImage`/`detectTransformGestures` 这些
 * 需要 Android 运行时, 本节不引 Compose UI 测试依赖。所以"Coil 到底把哪张图画出来了"
 * 与"双指捏合在真机上不与单指拖动抢手势"只能靠状态机测试 + 真机清单(报告里列为 UNVERIFIED)。
 */
class ViewerViewModelTest {

    private lateinit var server: MockWebServer
    private val token = "v".repeat(64)

    /** 规范路径的典型形态: verbatim 前缀里有**字面问号**, 路径里有空格和中文。 */
    private val folder = "\\\\?\\D:\\照片 2026"
    private val paths = (1..4).map { "$folder\\p$it.jpg" }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun item(index: Int, isRaw: Boolean = false) = GridItem(
        path = paths[index],
        fileName = "p${index + 1}.jpg",
        isRaw = isRaw,
        isVideo = false,
        modifiedAt = 1_759_680_000_000L + index,
        thumbnailUrl = "http://pc/api/v1/thumb?path=x&size=300&v=0",
    )

    private fun items(count: Int = 4) = (0 until count).map { item(it) }

    /** Coil 的网络层对非 2xx 抛的就是它 —— 里面带着真正的 HTTP 状态码。 */
    private fun http(status: Int) =
        HttpException(NetworkResponse(code = status, headers = NetworkHeaders.EMPTY))

    private fun viewModel(
        items: List<GridItem> = items(),
        startIndex: Int = 0,
        thresholdPx: Float = 160f,
        prefetch: (List<String>) -> Unit = {},
        clientFactory: ((String, String) -> ApiClient)? = null,
    ) = if (clientFactory == null) {
        ViewerViewModel(
            pairing = Pairing(baseUrl(), token, note = "家里的台式机"),
            items = items,
            startIndex = startIndex,
            dragThresholdPx = thresholdPx,
            prefetch = prefetch,
        )
    } else {
        ViewerViewModel(
            pairing = Pairing(baseUrl(), token, note = "家里的台式机"),
            items = items,
            startIndex = startIndex,
            dragThresholdPx = thresholdPx,
            prefetch = prefetch,
            clientFactory = clientFactory,
        )
    }

    // ── ① 默认档的 URL ──────────────────────────────────────────────────────

    @Test
    fun the_default_step_asks_for_the_2048_cap_and_never_carries_full() {
        val vm = viewModel(startIndex = 1)
        val url = vm.state.value.defaultUrl.toHttpUrl()

        assertEquals("/api/v1/preview", url.encodedPath)
        assertEquals("这一张的规范路径必须逐字进 URL(verbatim 前缀里的字面问号由 OkHttp 编码)",
            paths[1], url.queryParameter("path"))
        assertEquals(PREVIEW_MAX_DEFAULT.toString(), url.queryParameter("max"))
        assertEquals("v=<mtime> 是 Coil 的缓存失效键", "1759680000001", url.queryParameter("v"))
        // 默认档**整个 full 参数都不出现**(服务端 #[serde(default)] = false)
        assertNull("默认档不许带 full: $url", url.queryParameter("full"))
    }

    // ── ② 高清入口: 同一个 URL + full=true ──────────────────────────────────

    @Test
    fun the_high_res_entry_re_requests_the_same_photo_with_full_true_not_one() {
        val vm = viewModel(startIndex = 2)
        val before = vm.state.value
        assertTrue("高清按钮在默认档上是可点的", before.canRequestHighRes)

        vm.enterHighRes()

        val st = vm.state.value
        assertEquals(ViewerQuality.HIGH_RES, st.quality)
        assertTrue("高清必须给出明确反馈(电脑在做全解码)", st.highResLoading)
        assertFalse("已经在解码, 不许再给一次入口", st.canRequestHighRes)

        val url = requireNotNull(st.highResUrl).toHttpUrl()
        assertEquals("/api/v1/preview", url.encodedPath)
        assertEquals("还是同一张照片", paths[2], url.queryParameter("path"))
        assertEquals("max 不变(对 RAW 调大 max 毫无作用: resize_max_edge 绝不放大)",
            PREVIEW_MAX_DEFAULT.toString(), url.queryParameter("max"))
        // 这两条是 M1′ 实测钉死的: 服务端的 full 是 Rust bool, serde_urlencoded 只认 true/false
        assertEquals("true", url.queryParameter("full"))
        assertNotEquals("full=1 会被服务端判成类型错误 → 400 badRequest", "1", url.queryParameter("full"))
        assertTrue("线上拼写是契约的一部分: ${url.encodedQuery}", url.encodedQuery!!.contains("full=true"))
        assertFalse(url.encodedQuery!!.contains("full=1"))
        // 显示的就是高清那张
        assertEquals(st.highResUrl, st.displayUrl)
    }

    @Test
    fun the_high_res_entry_does_not_request_anything_twice() {
        val vm = viewModel(startIndex = 0)
        vm.enterHighRes()
        val first = vm.state.value.highResUrl

        // 再点一次(或长按)不许让电脑多做一次全解码: 50MB 线性 DNG 是秒级到十几秒级
        vm.enterHighRes()

        assertEquals(first, vm.state.value.highResUrl)
    }

    @Test
    fun high_res_is_never_what_gets_prefetched() {
        val prefetched = mutableListOf<List<String>>()
        val vm = viewModel(startIndex = 1, prefetch = { prefetched += it })

        vm.open()
        assertEquals("进查看器预取一次", 1, prefetched.size)

        vm.enterHighRes()
        assertEquals("高清只由用户显式触发, 绝不预取", 1, prefetched.size)

        // 高清态下切图 → 预取的还是默认档(见下一条测试, 这里只管"没有 full")
        vm.go(+1)
        val urls = prefetched.last()
        assertTrue(urls.isNotEmpty())
        for (u in urls) {
            assertNull("预取里出现了 full: $u", u.toHttpUrl().queryParameter("full"))
            assertFalse(u.contains("full=true"))
        }
    }

    // ── ③ 邻张预取(默认档, 只预取左右) ──────────────────────────────────────

    @Test
    fun the_neighbours_are_prefetched_once_each_at_the_default_step() {
        val prefetched = mutableListOf<List<String>>()
        val vm = viewModel(startIndex = 1, prefetch = { prefetched += it })

        vm.open()

        val urls = prefetched.single()
        assertEquals("计划: index+1 与 index-1 各一张", 2, urls.size)
        assertEquals(
            "预取的是**左右邻张**, 而且不含当前这张(它正在被显示)",
            listOf(paths[0], paths[2]),
            urls.map { it.toHttpUrl().queryParameter("path") },
        )
        for (u in urls) {
            val url = u.toHttpUrl()
            assertEquals("/api/v1/preview", url.encodedPath)
            assertEquals(PREVIEW_MAX_DEFAULT.toString(), url.queryParameter("max"))
            assertNull("预取不许带 full: $url", url.queryParameter("full"))
        }
    }

    @Test
    fun prefetch_follows_the_index_and_drops_the_ends_instead_of_wrapping() {
        val prefetched = mutableListOf<List<String>>()
        val vm = viewModel(startIndex = 1, prefetch = { prefetched += it })

        vm.open()
        vm.go(+1)
        assertEquals(
            "第 3 张(下标 2)的两侧是下标 1 与 3",
            listOf(paths[1], paths[3]),
            prefetched.last().map { it.toHttpUrl().queryParameter("path") },
        )

        vm.go(+1)
        assertEquals("到末尾只有左边一张, **绝不绕回第一张**", listOf(paths[2]), prefetched.last().map { it.toHttpUrl().queryParameter("path") })

        // 到末尾再往右: 索引停住, 也不该再发一次预取(没有任何新东西可预取)
        val count = prefetched.size
        vm.go(+1)
        assertEquals("到末尾就停住", 3, vm.state.value.index)
        assertEquals(count, prefetched.size)
    }

    // ── ④ 高清失败: 退回默认档, 不许白屏 ────────────────────────────────────

    @Test
    fun a_failed_high_res_falls_back_to_the_default_image_with_an_actionable_sentence() = runTest {
        val vm = viewModel(startIndex = 1)
        val defaultUrl = vm.state.value.defaultUrl
        assertTrue("默认档的 URL 必须已经就位(退回时靠它, 不重新加载)", defaultUrl.isNotBlank())

        vm.enterHighRes()
        assertEquals(ViewerQuality.HIGH_RES, vm.state.value.quality)

        vm.onHighResFailed(http(403))

        val st = vm.state.value
        assertEquals("高清失败必须退回默认档", ViewerQuality.DEFAULT, st.quality)
        assertNull(st.highResUrl)
        assertFalse(st.highResLoading)
        // 屏幕上有图: displayUrl 退回默认档那张(**这就是"绝不白屏"的不变量**)
        assertTrue("高清失败后显示 URL 不许为空", st.displayUrl.isNotBlank())
        assertEquals(defaultUrl, st.displayUrl)
        assertNotEquals("不许还停在高清那张上", st.highResUrl, st.displayUrl)
        // 默认档本身没有失败 → 不画"这张图没能显示出来"的错误卡
        assertNull(st.error)
        // 但要有一句可执行的话 + 一个动作
        val err = requireNotNull(st.highResError)
        assertEquals(ApiErrorCodes.FORBIDDEN_PATH, err.code)
        assertEquals(ViewerAction.PC_SIDE, err.action)
        assertTrue(err.text().contains("共享根"))
        assertTrue(err.actionLabel.isNotBlank())
        // 用户可以直接再试一次高清(退回默认档后入口又要可用)
        assertTrue(st.canRequestHighRes)
    }

    @Test
    fun the_high_res_failure_sentence_can_be_dismissed_and_the_image_stays() = runTest {
        val vm = viewModel(startIndex = 0)
        vm.enterHighRes()
        vm.onHighResFailed(http(404))

        assertEquals(ApiErrorCodes.NOT_FOUND, vm.state.value.highResError!!.code)
        vm.dismissHighResError()

        val st = vm.state.value
        assertNull(st.highResError)
        assertTrue("图还在", st.displayUrl.isNotBlank())
        assertNull(st.error)
    }

    @Test
    fun a_successful_high_res_keeps_showing_the_high_res_url() {
        val vm = viewModel(startIndex = 0)
        val high = vm.state.value.defaultUrl
        vm.enterHighRes()
        val highResUrl = vm.state.value.highResUrl
        assertNotEquals(high, highResUrl)

        vm.onHighResLoaded()

        val st = vm.state.value
        assertFalse(st.highResLoading)
        assertNull(st.highResError)
        assertEquals(ViewerQuality.HIGH_RES, st.quality)
        assertEquals(highResUrl, st.displayUrl)
    }

    // ── ⑤ 切图一定回到默认档 ────────────────────────────────────────────────

    @Test
    fun switching_photos_always_returns_to_the_default_step() {
        val vm = viewModel(startIndex = 0)
        vm.enterHighRes()
        assertEquals(ViewerQuality.HIGH_RES, vm.state.value.quality)

        vm.go(+1)

        val st = vm.state.value
        assertEquals(1, st.index)
        assertEquals("高清态绝不许带到下一张", ViewerQuality.DEFAULT, st.quality)
        assertNull(st.highResUrl)
        assertFalse(st.highResLoading)
        assertNull(st.highResError)
        assertNull(st.error)
        assertTrue("换图必须重新进入加载态", st.loading)
        val url = st.defaultUrl.toHttpUrl()
        assertEquals(paths[1], url.queryParameter("path"))
        assertNull(url.queryParameter("full"))
        assertEquals("换图要回到原始比例(否则下一张会莫名地是放大的)", 1f, st.mode.scale)
    }

    @Test
    fun switching_drops_a_high_res_failure_notice_from_the_previous_photo() = runTest {
        val vm = viewModel(startIndex = 1)
        vm.enterHighRes()
        vm.onHighResFailed(http(500))
        assertTrue(vm.state.value.highResError != null)

        vm.go(-1)

        assertEquals(0, vm.state.value.index)
        assertNull("换到下一张时, 上一张的高清提示必须清掉", vm.state.value.highResError)
    }

    // ── ⑥ 失败: 403 / 404 / 超时 / 401 ──────────────────────────────────────

    @Test
    fun a_403_and_a_404_lead_to_different_sentences_and_different_actions() = runTest {
        val forbidden = viewModel(startIndex = 0)
        forbidden.onDefaultFailed(http(403))
        val e403 = requireNotNull(forbidden.state.value.error)

        val missing = viewModel(startIndex = 0)
        missing.onDefaultFailed(http(404))
        val e404 = requireNotNull(missing.state.value.error)

        assertEquals(ApiErrorCodes.FORBIDDEN_PATH, e403.code)
        assertEquals(ApiErrorCodes.NOT_FOUND, e404.code)
        assertNotEquals("两句话必须不一样", e403.text(), e404.text())
        assertNotEquals("两个动作必须在两台设备上", e403.action, e404.action)
        assertNotEquals(e403.actionLabel, e404.actionLabel)
        assertEquals(ViewerAction.PC_SIDE, e403.action)
        assertEquals(ViewerAction.RELOAD_FOLDER, e404.action)
        // 默认档失败 = 屏幕上没有图 → 必须结束加载(否则界面永远在转圈)
        assertFalse(forbidden.state.value.loading)
        assertFalse(missing.state.value.loading)
        // 403/404 **不是** 401: 一个请求都不该发(只有 401 有资格复探)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_timeout_is_synthesized_locally_and_never_probes_the_server() = runTest {
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(SocketTimeoutException("read timed out"))

        val err = requireNotNull(vm.state.value.error)
        assertEquals(ApiErrorCodes.TIMEOUT, err.code)
        assertEquals(ViewerAction.RETRY, err.action)
        // 没有 HTTP 响应就**没有任何东西可以复探** —— 发出去的那一次请求本身就说明
        // "分类走了网络"这条错路(只有 401 才有资格复探)
        assertEquals("超时这一档一个请求都不该发", 0, server.requestCount)
    }

    @Test
    fun a_401_is_disambiguated_before_any_text_is_shown() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(http(401))

        val err = requireNotNull(vm.state.value.error)
        assertEquals(ApiErrorCodes.UNAUTHORIZED, err.code)
        assertEquals(UnauthorizedCause.TOKEN_ROTATED, err.cause)
        assertTrue("确认 token 失效才让用户去重新配对: ${err.text()}", err.text().contains("配对码"))
        assertEquals(ViewerAction.RECONNECT, err.action)
        // 复探打的是同一个**已知受保护**端点(§5.1 的 shares), 而且带着同一枚 token
        val probe = server.takeRequest()
        assertEquals("/api/v1/shares", probe.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", probe.getHeader("Authorization"))
        assertEquals("复探只发一次", 1, server.requestCount)
    }

    @Test
    fun the_endpoint_not_exposed_case_says_the_phone_cannot_fix_it() = runTest {
        // 复探打通了(200) → token 没问题, 是这个构建不暴露那个路径
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":[]}"""))
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(http(401))

        val err = requireNotNull(vm.state.value.error)
        assertEquals(UnauthorizedCause.ENDPOINT_NOT_EXPOSED, err.cause)
        assertEquals(ViewerAction.PC_SIDE, err.action)
    }

    @Test
    fun a_disconnect_during_the_401_probe_asserts_nothing_about_the_token() = runTest {
        val vm = viewModel(startIndex = 0, clientFactory = { url, tkn ->
            ApiClient(
                url,
                tkn,
                buildHttpClient(tkn).newBuilder().retryOnConnectionFailure(false).build(),
            )
        })
        server.shutdown() // 复探根本打不通

        vm.onDefaultFailed(http(401))

        val err = requireNotNull(vm.state.value.error)
        assertEquals(ApiErrorCodes.UNAUTHORIZED, err.code)
        assertEquals(UnauthorizedCause.UNDETERMINED, err.cause)
        assertEquals(ViewerAction.RETRY, err.action)
        assertTrue("探不动就不许下任何结论: ${err.text()}", err.text().contains("网络"))
    }

    @Test
    fun a_decode_failure_of_the_bytes_we_did_get_uses_the_media_wording() = runTest {
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(IllegalStateException("Failed to decode image"))

        val err = requireNotNull(vm.state.value.error)
        assertEquals(ApiErrorCodes.UNSUPPORTED_FORM, err.code)
        assertEquals("这不是手机能修的事(换一张 / 更新电脑端)", ViewerAction.DISMISS, err.action)
        for (word in listOf("边车", "XMP", "合并")) {
            assertFalse("媒体语境不许出现边车的说法('$word'): ${err.text()}", err.text().contains(word))
        }
    }

    @Test
    fun a_retry_re_issues_the_same_url_through_a_new_key_and_clears_the_error() = runTest {
        val vm = viewModel(startIndex = 0)
        vm.onDefaultFailed(http(500))
        assertTrue(vm.state.value.error != null)

        vm.retryDefault()

        val st = vm.state.value
        assertNull(st.error)
        assertTrue(st.loading)
        // URL 不变时 Coil 的 painter 不会自己重发 → 界面拿 reloadNonce 当 key{} 重建它
        assertEquals("重试要真的换一次 key", 1, st.reloadNonce)
    }

    @Test
    fun a_late_success_clears_a_previous_error() = runTest {
        val vm = viewModel(startIndex = 0)
        vm.onDefaultFailed(http(500))
        vm.onDefaultLoaded()

        val st = vm.state.value
        assertNull(st.error)
        assertFalse(st.loading)
    }

    // ── 手势规则(规格 §8.2) ────────────────────────────────────────────────

    @Test
    fun a_pinch_enters_the_viewing_state_and_then_a_single_finger_drag_pans_instead_of_switching() {
        val vm = viewModel(startIndex = 1)

        vm.onGestureStart()
        vm.onTransform(zoom = 2f, panX = 0f, panY = 0f)
        assertTrue("双指捏合 → 查看态", vm.state.value.isViewing)
        assertEquals(2f, vm.state.value.mode.scale)

        // 查看态里, 一次**远超阈值**的水平拖动只能平移, 索引一动不动(这是最容易写错的一条)
        vm.onTransform(zoom = 1f, panX = -900f, panY = 20f)
        assertEquals(1, vm.state.value.index)
        assertEquals(-900f, vm.state.value.mode.offsetX)
        assertEquals(20f, vm.state.value.mode.offsetY)

        // 缩回 1x → 回到决策态 + 贴边归位
        vm.onTransform(zoom = 0.4f, panX = 0f, panY = 0f)
        assertFalse(vm.state.value.isViewing)
        assertEquals(0f, vm.state.value.mode.offsetX)

        // 回到决策态后, 单指拖动又能切图了
        vm.onTransform(zoom = 1f, panX = -900f, panY = 0f)
        assertEquals(2, vm.state.value.index)
    }

    @Test
    fun two_half_drags_in_separate_gestures_do_not_add_up_to_a_switch() {
        val vm = viewModel(startIndex = 1, thresholdPx = 160f)

        vm.onGestureStart()
        vm.onTransform(zoom = 1f, panX = -100f, panY = 0f)
        vm.onGestureEnd()
        vm.onGestureStart()
        vm.onTransform(zoom = 1f, panX = -100f, panY = 0f)
        vm.onGestureEnd()

        assertEquals("两次半程拖动不算切一张图", 1, vm.state.value.index)

        vm.onGestureStart()
        vm.onTransform(zoom = 1f, panX = -170f, panY = 0f)
        assertEquals("一次够长的拖动才切", 2, vm.state.value.index)
    }

    @Test
    fun one_gesture_switches_at_most_one_photo() {
        val vm = viewModel(startIndex = 1, thresholdPx = 160f)

        vm.onGestureStart()
        // 一次手势里累计 800px: 只许切一张。切图没有"图跟着手指走"的动画, 一次长拖动
        // 连跳三张谁也看不懂 —— 想连看几张就再拖几次(下一次 onGestureStart 才解禁)。
        repeat(4) { vm.onTransform(zoom = 1f, panX = -200f, panY = 0f) }

        assertEquals(2, vm.state.value.index)

        // 新的手势里又能切一张
        vm.onGestureStart()
        vm.onTransform(zoom = 1f, panX = -200f, panY = 0f)
        assertEquals(3, vm.state.value.index)
    }

    @Test
    fun double_tap_toggles_and_resets_the_offset() {
        val vm = viewModel(startIndex = 0)
        vm.onTransform(zoom = 3f, panX = 40f, panY = 40f)
        assertEquals(3f, vm.state.value.mode.scale)

        vm.toggleZoom()
        assertFalse(vm.state.value.isViewing)
        assertEquals(0f, vm.state.value.mode.offsetX)

        vm.toggleZoom()
        assertEquals(2f, vm.state.value.mode.scale)
    }

    @Test
    fun the_scale_never_goes_past_8x_no_matter_how_hard_you_pinch() {
        val vm = viewModel(startIndex = 0)
        repeat(10) { vm.onTransform(zoom = 4f, panX = 0f, panY = 0f) }
        assertEquals(8f, vm.state.value.mode.scale)
    }

    // ── 边界: 空列表 / 生产默认客户端 ────────────────────────────────────────

    @Test
    fun an_empty_photo_list_is_not_a_crash() {
        val prefetched = mutableListOf<List<String>>()
        val vm = viewModel(items = emptyList(), startIndex = 0, prefetch = { prefetched += it })

        val st = vm.state.value
        assertEquals(0, st.count)
        assertNull(st.current)
        assertEquals("", st.title)
        assertEquals("", st.displayUrl)
        vm.open()
        assertTrue("没有邻张可以预取", prefetched.isEmpty())
        vm.go(+1)
        assertEquals(0, vm.state.value.index)
    }

    @Test
    fun the_production_default_client_carries_the_bearer_token() = runTest {
        // 这一条**不注入** clientFactory: 走的就是 ViewerViewModel 的默认路径
        // (ApiClient → SharedHttpClient.forToken, 与 Coil 拿到的是同一个实例)。
        // 它红掉只有两种可能: 默认 client 没装鉴权头, 或者根本没带 token。
        val prodToken = "s".repeat(64)
        val vm = ViewerViewModel(
            pairing = Pairing(baseUrl(), prodToken, note = "家里的台式机"),
            items = items(2),
            startIndex = 0,
            prefetch = {},
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))

        vm.onDefaultFailed(http(401))

        val probe = server.takeRequest()
        assertEquals("/api/v1/shares", probe.requestUrl!!.encodedPath)
        assertEquals("Bearer $prodToken", probe.getHeader("Authorization"))
        assertTrue(vm.state.value.defaultUrl.startsWith(baseUrl()))
        assertTrue(vm.state.value.defaultUrl.contains("/api/v1/preview?"))
    }

    @Test
    fun an_unclassifiable_failure_is_not_dressed_up_as_a_server_code() = runTest {
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(null)

        val err = requireNotNull(vm.state.value.error)
        assertEquals(ViewerMessages.UNCLASSIFIED, err.code)
        assertEquals(ViewerMessages.GENERIC, err.text())
        assertEquals("一个请求都不该发(包括复探)", 0, server.requestCount)
    }

    @Test
    fun a_broken_connection_mid_body_is_a_network_failure_not_a_403() = runTest {
        val vm = viewModel(startIndex = 0)

        vm.onDefaultFailed(IOException("unexpected end of stream"))

        assertEquals(ApiErrorCodes.NETWORK, requireNotNull(vm.state.value.error).code)
        assertEquals(0, server.requestCount)
    }
}
