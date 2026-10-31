// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.DeviceIdentity
import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.data.DiscoveryEvent
import com.imagefilter.mobile.data.DiscoverySource
import com.imagefilter.mobile.data.PairRequestResult
import com.imagefilter.mobile.data.PairStatusResult
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingApi
import com.imagefilter.mobile.data.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「连接电脑」页的状态(计划 §0.4 冻结的形状)。
 *
 * **不含任何 Compose/Android 类型** —— 与 [BrowseState] 同一条理由: 状态机必须在 JVM 上
 * 能被真的走一遍, 而不是靠 UI 测试。
 */
sealed interface ConnectState {
    /** 正在搜索, 且**一台都还没找到**。 */
    data object Searching : ConnectState

    /** 找到了至少一台。[devices] 按发现顺序排列(同一 serviceName 的更新**原地替换**)。 */
    data class Devices(val devices: List<DiscoveredDevice>) : ConnectState

    /**
     * 已向 [device] 发起配对, 等用户在电脑上点「允许」。
     *
     * [requestId] 为**空串**表示"请求还在路上"(202 还没回来) —— 界面此时只显示等待文案,
     * 不该把空 id 当数据展示。202 回来后原地更新成真实的 requestId。
     */
    data class Waiting(val device: DiscoveredDevice, val requestId: String) : ConnectState

    /** 电脑批准且 [Pairing] 已经**保存成功**。保存失败走 [Error], 绝不假装成功。 */
    data class Approved(val pairing: Pairing) : ConnectState

    /** 用户在电脑上点了「拒绝」。 */
    data class Denied(val message: String) : ConnectState

    /** 任何失败(搜索失败/窗口没开/限流/过期/网络/保存失败)。[message] 可直接展示。 */
    data class Error(val message: String) : ConnectState
}

/**
 * 保存配对的**窄接口** —— 生产是 [PairingStore] 的适配([asPairingSaver]), 测试是内存假件。
 *
 * 为什么不直接依赖 [PairingStore]: 它是 final 类且需要两个假件才能构造; 状态机只关心
 * "保存成功/抛异常", 一个函数就能把这条边界说清楚。保存必须**成功之后**才进入
 * [ConnectState.Approved] —— 否则用户会看到"已连接", 而重启 App 又回到配对页。
 */
fun interface PairingSaver {
    suspend fun save(pairing: Pairing)
}

/** [PairingStore] → [PairingSaver]。方向刻意单向: 状态机不知道 token 存在哪儿。 */
fun PairingStore.asPairingSaver(): PairingSaver = PairingSaver { pairing ->
    save(pairing.url, pairing.token, pairing.note)
}

/**
 * mDNS 发现 + 请求/轮询配对的**纯状态机**(无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 一次配对的时序(计划 §0.2 / §0.4):
 *   1. [start] → 订阅 [DiscoverySource], Found/Lost 合并成 [ConnectState.Devices];
 *   2. [select] → [ConnectState.Waiting](requestId 先为空) → `POST /pair/request`;
 *   3. 202 → 每 [pollIntervalMs] 轮询 `GET /pair/status`, 最长 [pollTimeoutMs];
 *   4. `approved` → **[PairingStore] 保存成功** → [ConnectState.Approved];
 *      `denied`/`expired`/`Error` 各自落到对应状态, 绝不猜;
 *   5. [cancel] 取消轮询回到设备列表; [retry] 重新发起(或重启搜索);
 *   6. [onCleared] 必须 [DiscoverySource.stop](D 在 ViewModel 的 `onCleared()` 里调)。
 *
 * 所有等待都用可取消的 `delay`/`withTimeoutOrNull`: [cancel]/[stop] 一调用, 在飞的请求
 * 与轮询不会再改状态(generation 代次号兜住协程与用户操作之间的竞态)。
 *
 * [scope] 由调用方注入(`viewModelScope` 或测试的 `backgroundScope`) —— 本类**不**继承
 * `androidx.lifecycle.ViewModel`, 与 [BrowseViewModel] 的取舍相同。
 */
class ConnectViewModel(
    private val source: DiscoverySource,
    private val api: PairingApi,
    private val identity: DeviceIdentity,
    private val saver: PairingSaver,
    private val scope: CoroutineScope,
    /** 轮询间隔。测试注入 1ms 让虚拟时间瞬间走完。 */
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /** 请求发出后等电脑确认的总时限(计划: 60 秒)。 */
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
) {

    init {
        require(pollIntervalMs > 0) { "pollIntervalMs 必须 > 0, 实际=$pollIntervalMs" }
        require(pollTimeoutMs > 0) { "pollTimeoutMs 必须 > 0, 实际=$pollTimeoutMs" }
    }

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Searching)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val lock = Any()

    /** 发现缓存的设备(serviceName → 最新一次 Found)。按插入顺序保留, 更新不改变位置。 */
    private val devices = LinkedHashMap<String, DiscoveredDevice>()

    private var discoveryStarted = false
    private var pairingJob: Job? = null
    private var lastSelected: DiscoveredDevice? = null

    /**
     * 当前 [ConnectState.Error] 是不是**发现失败**造成的。
     *
     * 用来区分两种 Error: 搜索失败后迟到的 Found 应该把界面救回设备列表;
     * 而配对失败(窗口没开/被拒绝/保存失败)后的 Error 是用户动作的结果, 不许被
     * 一条无关的 Found 顶掉。
     */
    private var discoveryFailed = false

    /**
     * 代次号: 每次 select/cancel/stop/retry 自增。在飞的协程只在自己那一代仍是当前代时
     * 才允许写状态 —— 否则"取消之后迟到的 approved"会把用户刚点的取消覆盖掉。
     */
    private var generation = 0L

    // ── 发现 ────────────────────────────────────────────────────────────────

    /** 开始搜索(幂等)。已处于 [ConnectState.Approved] 时不会把成功页顶掉。 */
    fun start() {
        synchronized(lock) {
            if (discoveryStarted) return
            discoveryStarted = true
            if (_state.value !is ConnectState.Approved) _state.value = devicesState()
        }
        source.start(::onDiscoveryEvent)
    }

    /** 停止搜索并取消在飞的配对。[onCleared] 就是调它 —— 不注销监听器会一直耗电。 */
    fun stop() {
        synchronized(lock) {
            generation++
            pairingJob?.cancel()
            pairingJob = null
            discoveryStarted = false
        }
        source.stop()
    }

    /** D 的 ViewModel `onCleared()` 必须转发到这里(计划 C4 的硬要求)。 */
    fun onCleared() = stop()

    private fun onDiscoveryEvent(event: DiscoveryEvent) {
        synchronized(lock) {
            when (event) {
                is DiscoveryEvent.Found -> {
                    // 同一 serviceName 再次 Found(例如 TXT 的 pair 从 closed 变 open)是
                    // **更新**, 不是新设备 —— 原地替换, 列表里绝不出现两行同一台电脑。
                    val recovered = discoveryFailed
                    discoveryFailed = false
                    devices[event.device.serviceName] = event.device
                    if (showsDeviceList() || recovered) _state.value = devicesState()
                }

                is DiscoveryEvent.Lost -> {
                    devices.remove(event.serviceName)
                    if (showsDeviceList()) _state.value = devicesState()
                }

                is DiscoveryEvent.Failed -> {
                    // 单台设备解析失败不该抹掉已经找到的列表(那些还能连)。
                    // 一台都没找到时, 失败就是搜索失败, 如实报出来。
                    discoveryFailed = true
                    if (devices.isEmpty()) _state.value = ConnectState.Error(event.message)
                }
            }
        }
    }

    /** 只有"列表态"才随发现事件刷新; Waiting/Approved/Denied/Error 是用户动作的结果。 */
    private fun showsDeviceList(): Boolean =
        _state.value is ConnectState.Searching || _state.value is ConnectState.Devices

    private fun devicesState(): ConnectState {
        val list = devices.values.toList()
        return if (list.isEmpty()) ConnectState.Searching else ConnectState.Devices(list)
    }

    // ── 配对 ────────────────────────────────────────────────────────────────

    /** 用户点了一台电脑: 进入 Waiting 并立刻发配对请求。Waiting 期间重复点被忽略。 */
    fun select(device: DiscoveredDevice) {
        val gen = synchronized(lock) {
            if (_state.value is ConnectState.Waiting) return
            lastSelected = device
            discoveryFailed = false
            val g = ++generation
            pairingJob?.cancel()
            _state.value = ConnectState.Waiting(device, requestId = "")
            g
        }
        val job = scope.launch { attempt(device, gen) }
        synchronized(lock) { if (gen == generation) pairingJob = job }
    }

    /** 取消本次配对, 回到已找到的设备列表(列表为空的极端情况回 Searching)。 */
    fun cancel() {
        synchronized(lock) {
            if (_state.value !is ConnectState.Waiting) return
            generation++
            pairingJob?.cancel()
            pairingJob = null
            lastSelected = null
            _state.value = devicesState()
        }
    }

    /**
     * 「重试」: 选过设备就**重新发起配对**; 还没选过(搜索失败/空列表)就**重启搜索**。
     */
    fun retry() {
        val device = synchronized(lock) { lastSelected }
        if (device != null) {
            select(device)
        } else {
            restartDiscovery()
        }
    }

    private fun restartDiscovery() {
        synchronized(lock) {
            generation++
            pairingJob?.cancel()
            pairingJob = null
            discoveryStarted = false
            discoveryFailed = false
            devices.clear()
            _state.value = ConnectState.Searching
        }
        source.stop()
        start()
    }

    private suspend fun attempt(device: DiscoveredDevice, gen: Long) {
        if (!isCurrent(gen)) return
        val request = try {
            api.request(
                device = device,
                deviceId = identity.deviceId(),
                deviceName = identity.deviceName(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairRequestResult.Error("配对请求失败：${e.message ?: "未知错误"}")
        }
        if (!isCurrent(gen)) return

        when (request) {
            is PairRequestResult.Ok -> poll(device, request.requestId, gen)

            PairRequestResult.Closed -> updateIfCurrent(
                gen,
                ConnectState.Error("电脑上的配对窗口没有打开，请先在电脑上点「配对新设备」"),
            )

            PairRequestResult.RateLimited -> updateIfCurrent(
                gen,
                ConnectState.Error("请求太频繁，请等 10 秒后再试"),
            )

            is PairRequestResult.Error -> updateIfCurrent(gen, ConnectState.Error(request.message))
        }
    }

    private suspend fun poll(device: DiscoveredDevice, requestId: String, gen: Long) {
        // 202 回来了: 把真实 requestId 填进同一个 Waiting(界面仍是"等待电脑确认")。
        updateIfCurrent(gen, ConnectState.Waiting(device, requestId))
        if (!isCurrent(gen)) return

        // 用 withTimeoutOrNull 而不是自己记 System.nanoTime(): 虚拟时间可测, 且父协程
        // 取消时只会传播 CancellationException(不会被误当成"超时")。
        val completed = withTimeoutOrNull(pollTimeoutMs) {
            while (true) {
                delay(pollIntervalMs)
                if (!isCurrent(gen)) return@withTimeoutOrNull

                val status = try {
                    api.status(device, requestId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PairStatusResult.Error("查询配对状态失败：${e.message ?: "未知错误"}")
                }
                if (!isCurrent(gen)) return@withTimeoutOrNull

                when (status) {
                    PairStatusResult.Pending -> Unit // 继续等
                    is PairStatusResult.Approved -> {
                        approve(status.pairing, gen)
                        return@withTimeoutOrNull
                    }

                    PairStatusResult.Denied -> {
                        updateIfCurrent(gen, ConnectState.Denied("电脑拒绝了配对请求"))
                        return@withTimeoutOrNull
                    }

                    PairStatusResult.Expired -> {
                        updateIfCurrent(gen, ConnectState.Error("配对请求已过期，请重新选择设备"))
                        return@withTimeoutOrNull
                    }

                    is PairStatusResult.Error -> {
                        updateIfCurrent(gen, ConnectState.Error(status.message))
                        return@withTimeoutOrNull
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Unit
        }

        // null ⇔ 超时(终端分支都会返回 Unit)。只有还在 Waiting 时才报超时,
        // 否则会把刚拿到的 Approved/Denied 覆盖成"超时"。
        if (completed == null && isCurrent(gen) && _state.value is ConnectState.Waiting) {
            updateIfCurrent(gen, ConnectState.Error("等待电脑确认超时，请重试"))
        }
    }

    /** 保存成功才 Approved。保存抛异常时状态必须说清"配对成功但没存下来"。 */
    private suspend fun approve(pairing: Pairing, gen: Long) {
        if (!isCurrent(gen)) return
        try {
            saver.save(pairing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateIfCurrent(
                gen,
                ConnectState.Error("配对成功，但保存失败：${e.message ?: "未知错误"}"),
            )
            return
        }
        updateIfCurrent(gen, ConnectState.Approved(pairing))
    }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { gen == generation }

    private fun updateIfCurrent(gen: Long, next: ConnectState) {
        synchronized(lock) { if (gen == generation) _state.value = next }
    }

    companion object {
        /** 计划: 每 1 秒轮询一次。 */
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L

        /** 计划: 请求 60 秒过期。 */
        const val DEFAULT_POLL_TIMEOUT_MS = 60_000L
    }
}
