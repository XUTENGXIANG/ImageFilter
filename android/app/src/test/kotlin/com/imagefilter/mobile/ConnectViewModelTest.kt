// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.DeviceIdStore
import com.imagefilter.mobile.data.DeviceIdentity
import com.imagefilter.mobile.data.DeviceNameSource
import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.data.DiscoveryEvent
import com.imagefilter.mobile.data.DiscoverySource
import com.imagefilter.mobile.data.PairRequestResult
import com.imagefilter.mobile.data.PairStatusResult
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingApi
import com.imagefilter.mobile.data.PairingPrefs
import com.imagefilter.mobile.data.PairingPrefsStore
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.data.SecretStore
import com.imagefilter.mobile.ui.ConnectState
import com.imagefilter.mobile.ui.ConnectViewModel
import com.imagefilter.mobile.ui.PairingSaver
import com.imagefilter.mobile.ui.asPairingSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectViewModel] 的完整状态机, 跑在 **JVM + 虚拟时间**上:
 * [FakeDiscoverySource] 控制发现事件, [FakePairingApi] 控制请求/轮询应答,
 * 轮询间隔/超时注入到毫秒级, 所以"等 60 秒"在测试里是瞬时的。
 *
 * 每个测试都钉一个用户可见的事实(哪一档状态、保存了没有、有没有多发请求),
 * 而不是内部实现细节。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    // ── 假件 ─────────────────────────────────────────────────────────────────

    private class FakeDiscoverySource : DiscoverySource {
        var onEvent: ((DiscoveryEvent) -> Unit)? = null
        var starts = 0
        var stops = 0

        override fun start(onEvent: (DiscoveryEvent) -> Unit) {
            starts++
            this.onEvent = onEvent
        }

        override fun stop() {
            stops++
            onEvent = null
        }

        fun emit(event: DiscoveryEvent) {
            onEvent?.invoke(event)
        }
    }

    private data class RequestCall(
        val device: DiscoveredDevice,
        val deviceId: String,
        val deviceName: String,
    )

    private class FakePairingApi : PairingApi {
        var requestResult: PairRequestResult = PairRequestResult.Ok("req-1", 0L)
        val statuses = ArrayDeque<PairStatusResult>()
        val requests = mutableListOf<RequestCall>()
        var statusCalls = 0

        override suspend fun request(
            device: DiscoveredDevice,
            deviceId: String,
            deviceName: String,
        ): PairRequestResult {
            requests += RequestCall(device, deviceId, deviceName)
            return requestResult
        }

        override suspend fun status(device: DiscoveredDevice, requestId: String): PairStatusResult {
            statusCalls++
            return statuses.removeFirstOrNull() ?: PairStatusResult.Pending
        }
    }

    private class FakeSaver(var fail: Boolean = false) : PairingSaver {
        val saved = mutableListOf<Pairing>()

        override suspend fun save(pairing: Pairing) {
            if (fail) throw IllegalStateException("Keystore 写不进去")
            saved += pairing
        }
    }

    private class FakeIdStore : DeviceIdStore {
        var value: String? = null

        override suspend fun load(): String? = value

        override suspend fun save(id: String) {
            value = id
        }
    }

    private fun testIdentity(name: String = "Pixel 8") = DeviceIdentity(
        idStore = FakeIdStore(),
        nameSource = DeviceNameSource { name },
        model = null,
    )

    private fun TestScope.newViewModel(
        source: FakeDiscoverySource = FakeDiscoverySource(),
        api: FakePairingApi = FakePairingApi(),
        saver: FakeSaver = FakeSaver(),
        pollTimeoutMs: Long = 50L,
    ): ConnectViewModel = ConnectViewModel(
        source = source,
        api = api,
        identity = testIdentity(),
        saver = saver,
        // 用 runTest 的 TestScope 本身, 不是 backgroundScope: advanceUntilIdle() 只推进
        // **前台**任务, backgroundScope 里的轮询会被它忽略(实测, 见测试失败信息)。
        scope = this,
        pollIntervalMs = 1L,
        pollTimeoutMs = pollTimeoutMs,
    )

    private fun device(name: String = "DESKTOP-96B8FMF") = DiscoveredDevice(
        serviceName = "ImageFilter $name",
        name = name,
        host = "192.168.1.20",
        port = 8765,
        pairOpen = true,
        desktopId = "desktop-$name",
    )

    private fun approvedPairing() =
        Pairing("http://192.168.1.20:8765", "a".repeat(64), "DESKTOP-96B8FMF")

    // ── 搜索 ─────────────────────────────────────────────────────────────────

    @Test
    fun start_is_idempotent_and_on_cleared_must_stop_discovery() = runTest {
        val source = FakeDiscoverySource()
        val vm = newViewModel(source = source)

        vm.start()
        vm.start()
        assertEquals("重复 start 不许把组播注销再注册一遍", 1, source.starts)

        vm.onCleared()
        assertEquals("onCleared 必须注销监听器(否则离开页面后一直耗电)", 1, source.stops)
    }

    @Test
    fun found_devices_merge_by_service_name_and_lost_removes_them() = runTest {
        val source = FakeDiscoverySource()
        val vm = newViewModel(source = source)
        val a = device("A")
        val b = device("B")
        vm.start()

        source.emit(DiscoveryEvent.Found(a))
        source.emit(DiscoveryEvent.Found(b))
        assertEquals(ConnectState.Devices(listOf(a, b)), vm.state.value)

        // 同一 serviceName 的再次 Found(TXT pair 从 closed 变 open)是原地更新, 不是第二台
        val bOpen = b.copy(name = "B-renamed", pairOpen = false)
        source.emit(DiscoveryEvent.Found(bOpen))
        val merged = (vm.state.value as ConnectState.Devices).devices
        assertEquals(2, merged.size)
        assertEquals("顺序必须稳定(第一次发现的位置)", bOpen, merged[1])

        source.emit(DiscoveryEvent.Lost(a.serviceName))
        assertEquals(ConnectState.Devices(listOf(bOpen)), vm.state.value)

        source.emit(DiscoveryEvent.Lost(b.serviceName))
        assertEquals("一台都不剩 = 继续搜索", ConnectState.Searching, vm.state.value)
    }

    @Test
    fun a_discovery_failure_with_no_devices_is_an_error_and_a_late_found_recovers() = runTest {
        val source = FakeDiscoverySource()
        val vm = newViewModel(source = source)
        vm.start()

        source.emit(DiscoveryEvent.Failed("无法开始搜索电脑（错误码 3）"))
        assertEquals(ConnectState.Error("无法开始搜索电脑（错误码 3）"), vm.state.value)

        // 单台设备解析失败后, 另一台解析成功: 界面必须回到设备列表, 而不是永远停在错误页
        val a = device("A")
        source.emit(DiscoveryEvent.Found(a))
        assertEquals(ConnectState.Devices(listOf(a)), vm.state.value)
    }

    @Test
    fun a_discovery_failure_does_not_wipe_devices_already_found() = runTest {
        val source = FakeDiscoverySource()
        val vm = newViewModel(source = source)
        val a = device("A")
        vm.start()
        source.emit(DiscoveryEvent.Found(a))

        source.emit(DiscoveryEvent.Failed("无法解析设备 ImageFilter B（错误码 0）"))
        assertEquals("已经找到、还能连的电脑不许被一条解析失败抹掉", ConnectState.Devices(listOf(a)), vm.state.value)
    }

    // ── 请求 + 轮询 ──────────────────────────────────────────────────────────

    @Test
    fun select_sends_the_request_then_polls_and_approval_saves_the_pairing() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val saver = FakeSaver()
        val vm = newViewModel(source = source, api = api, saver = saver)
        val a = device()
        val pairing = approvedPairing()
        vm.start()
        source.emit(DiscoveryEvent.Found(a))
        api.requestResult = PairRequestResult.Ok("req-1", 1792900000000L)
        api.statuses.addLast(PairStatusResult.Pending)
        api.statuses.addLast(PairStatusResult.Approved(pairing))

        vm.select(a)
        runCurrent() // 让请求跑完, 但不要推进轮询的 1ms 延迟
        assertEquals(ConnectState.Waiting(a, "req-1"), vm.state.value)

        advanceUntilIdle()
        assertEquals(ConnectState.Approved(pairing), vm.state.value)
        assertEquals("批准后必须走 PairingStore 那一半", listOf(pairing), saver.saved)

        val call = api.requests.single()
        assertEquals(a, call.device)
        // deviceId 来自 DeviceIdentity(首次即生成 32 位 hex), deviceName 来自系统名回退链
        assertEquals(32, call.deviceId.length)
        assertEquals("Pixel 8", call.deviceName)
    }

    @Test
    fun denied_leaves_the_list_untouched_and_shows_denied() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val saver = FakeSaver()
        val vm = newViewModel(source = source, api = api, saver = saver)
        vm.start()
        source.emit(DiscoveryEvent.Found(device()))
        api.statuses.addLast(PairStatusResult.Denied)

        vm.select(device())
        advanceUntilIdle()

        assertTrue("实际=${vm.state.value}", vm.state.value is ConnectState.Denied)
        assertTrue(saver.saved.isEmpty())
    }

    @Test
    fun expired_is_an_error_the_user_can_act_on() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        vm.start()
        api.statuses.addLast(PairStatusResult.Expired)

        vm.select(device())
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("实际=$state", state is ConnectState.Error)
        assertTrue((state as ConnectState.Error).message.contains("过期"))
    }

    @Test
    fun a_status_error_is_surfaced_with_its_own_message() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        vm.start()
        api.statuses.addLast(PairStatusResult.Error("电脑上已经没有这个配对请求了，请重新选择设备"))

        vm.select(device())
        advanceUntilIdle()

        assertEquals(
            ConnectState.Error("电脑上已经没有这个配对请求了，请重新选择设备"),
            vm.state.value,
        )
    }

    @Test
    fun a_closed_pairing_window_is_not_denied_it_is_an_actionable_error() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        vm.start()
        api.requestResult = PairRequestResult.Closed

        vm.select(device())
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("实际=$state", state is ConnectState.Error)
        assertTrue("文案必须告诉用户去电脑上开窗口", (state as ConnectState.Error).message.contains("配对窗口"))
        assertEquals("没有 202 就不该轮询", 0, api.statusCalls)
    }

    @Test
    fun rate_limiting_is_its_own_error_and_does_not_poll() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        vm.start()
        api.requestResult = PairRequestResult.RateLimited

        vm.select(device())
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("实际=$state", state is ConnectState.Error)
        assertTrue((state as ConnectState.Error).message.contains("频繁"))
        assertEquals(0, api.statusCalls)
    }

    @Test
    fun a_request_error_is_carried_through_verbatim() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        vm.start()
        api.requestResult = PairRequestResult.Error("无法连接电脑（超时）")

        vm.select(device())
        advanceUntilIdle()

        assertEquals(ConnectState.Error("无法连接电脑（超时）"), vm.state.value)
    }

    @Test
    fun polling_stops_at_the_injected_timeout() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api, pollTimeoutMs = 5L)
        vm.start()

        vm.select(device())
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("实际=$state", state is ConnectState.Error)
        assertTrue("超时文案必须说清在等什么", (state as ConnectState.Error).message.contains("超时"))
        assertTrue("必须在超时前真的轮询过", api.statusCalls >= 1)
        assertTrue("超时后不许继续轮询", api.statusCalls <= 5)
    }

    @Test
    fun the_defaults_are_the_frozen_contract_one_second_and_sixty_seconds() {
        assertEquals(1_000L, ConnectViewModel.DEFAULT_POLL_INTERVAL_MS)
        assertEquals(60_000L, ConnectViewModel.DEFAULT_POLL_TIMEOUT_MS)
    }

    // ── 取消 / 重试 ───────────────────────────────────────────────────────────

    @Test
    fun cancel_returns_to_the_device_list_and_stops_polling() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        val a = device()
        vm.start()
        source.emit(DiscoveryEvent.Found(a))
        api.statuses.clear() // 永远 pending

        vm.select(a)
        runCurrent()
        assertTrue(vm.state.value is ConnectState.Waiting)

        vm.cancel()
        assertEquals("取消回到列表, 而不是停在等待页", ConnectState.Devices(listOf(a)), vm.state.value)

        val callsAtCancel = api.statusCalls
        advanceUntilIdle()
        assertEquals("取消后迟到的轮询不许再发请求", callsAtCancel, api.statusCalls)
    }

    @Test
    fun select_is_ignored_while_already_waiting() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val vm = newViewModel(source = source, api = api)
        val a = device()
        vm.start()
        api.statuses.clear()

        vm.select(a)
        runCurrent()
        vm.select(a)
        advanceUntilIdle()

        assertEquals("Waiting 期间重复点击不许发第二个请求", 1, api.requests.size)
    }

    @Test
    fun retry_after_denied_reissues_the_request() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val saver = FakeSaver()
        val vm = newViewModel(source = source, api = api, saver = saver)
        val a = device()
        val pairing = approvedPairing()
        vm.start()
        api.statuses.addLast(PairStatusResult.Denied)

        vm.select(a)
        advanceUntilIdle()
        assertTrue(vm.state.value is ConnectState.Denied)

        // 用户在电脑上改了主意: 重试必须重新发请求(不能只重新轮询旧 requestId)
        api.statuses.addLast(PairStatusResult.Approved(pairing))
        vm.retry()
        advanceUntilIdle()

        assertEquals(ConnectState.Approved(pairing), vm.state.value)
        assertEquals(2, api.requests.size)
        assertEquals(listOf(pairing), saver.saved)
    }

    @Test
    fun retry_without_a_selected_device_restarts_discovery() = runTest {
        val source = FakeDiscoverySource()
        val vm = newViewModel(source = source)
        vm.start()
        source.emit(DiscoveryEvent.Failed("无法开始搜索电脑（错误码 3）"))
        assertTrue(vm.state.value is ConnectState.Error)

        vm.retry()

        // 搜索失败时"重试" = 重启搜索: 先 stop 旧监听器, 再 start 新的
        assertEquals(2, source.starts)
        assertTrue(source.stops >= 1)
        assertEquals(ConnectState.Searching, vm.state.value)
    }

    // ── 保存失败 / PairingStore 适配 ─────────────────────────────────────────

    @Test
    fun a_save_failure_is_never_reported_as_approved() = runTest {
        val source = FakeDiscoverySource()
        val api = FakePairingApi()
        val saver = FakeSaver(fail = true)
        val vm = newViewModel(source = source, api = api, saver = saver)
        vm.start()
        api.statuses.addLast(PairStatusResult.Approved(approvedPairing()))

        vm.select(device())
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("实际=$state", state is ConnectState.Error)
        assertNotEquals(ConnectState.Approved(approvedPairing()), state)
        assertTrue(
            "必须区分'配对成功'与'保存成功'",
            (state as ConnectState.Error).message.contains("保存"),
        )
    }

    @Test
    fun the_pairing_store_adapter_writes_both_halves_like_manual_pairing() = runTest {
        // approved 的保存路径与手输/扫码配对**同一条**: url/note 进 DataStore, token 进 Keystore
        val secret = object : SecretStore {
            var value: String? = null
            override fun putToken(token: String) { value = token }
            override fun getToken(): String? = value
            override fun clear() { value = null }
        }
        val prefs = object : PairingPrefsStore {
            var value: PairingPrefs? = null
            override suspend fun load(): PairingPrefs? = value
            override suspend fun save(prefs: PairingPrefs) { value = prefs }
            override suspend fun clear() { value = null }
        }
        val store = PairingStore(prefs, secret)
        val pairing = approvedPairing()

        store.asPairingSaver().save(pairing)

        assertEquals(pairing, store.load())
        assertEquals(pairing.token, secret.getToken())
        assertEquals(PairingPrefs(pairing.url, pairing.note), prefs.value)
        assertFalse("token 不许进 DataStore", prefs.value!!.url.contains(pairing.token))
    }
}
