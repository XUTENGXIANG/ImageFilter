// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** 与桌面端 `mdns.rs` 广播的服务类型**逐字一致**。Android 的服务类型必须带末尾的点。 */
const val IMAGEFILTER_SERVICE_TYPE = "_imagefilter._tcp."

/**
 * 系统 [NsdManager] 的发现适配层。
 *
 * 为什么自己写而不是引库: `NsdManager` 是系统 API, 计划明确"安卓不新增依赖"。
 *
 * 实现上的三个真实约束:
 *   1. **同一时刻只能解析一个服务** —— 框架的 `resolveService` 不允许并发。所以这里有一条
 *      解析队列, 逐个 resolve, 前一个回调后才发下一个; 否则连续发现两台电脑时第二台会
 *      `FAILURE_ALREADY_ACTIVE` 而永远不出现;
 *   2. **回调切到主线程** —— 调用方(状态机/Compose)默认自己在主线程; 这里统一 post 到
 *      主线程 Handler, 并在投递时**再查一次 `started`**, 保证 `stop()` 之后迟到的框架
 *      回调不会送给已经销毁的界面;
 *   3. **`stop()` 必须真的注销** —— 不注销的监听器会一直占着组播 socket, 用户离开页面后
 *      仍在耗电, 重进页面还会收到重复事件。
 *
 * ⚠️ **真机 mDNS 不在本轮自动验收范围**(模拟器过不了 NAT, 见计划 §8): 本类只有编译级
 * 验证 + 纯解析函数的 JVM 单测; 真实发现行为要真机 + 同一 WiFi 才能确认。
 */
class NsdDiscoverySource(
    context: Context,
    private val manager: NsdManager = requireNotNull(
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager,
    ) { "本机没有 NsdManager" },
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : DiscoverySource {

    private val lock = Any()

    private var onEvent: ((DiscoveryEvent) -> Unit)? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var started = false

    /** 待解析队列 + "有没有一次解析在飞"。二者都由 [lock] 保护。 */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun start(onEvent: (DiscoveryEvent) -> Unit) {
        val listener: NsdManager.DiscoveryListener
        synchronized(lock) {
            // 已在搜索时只换回调: 反复 start 不该把组播注销再注册一遍。
            this.onEvent = onEvent
            if (started) return
            started = true
            resolveQueue.clear()
            resolving = false
            listener = createDiscoveryListener()
            discoveryListener = listener
        }

        try {
            manager.discoverServices(IMAGEFILTER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            // 同步抛出说明框架当场拒绝了这次搜索(参数/系统服务异常), 此时不会有任何回调:
            // 必须自己报告, 并把状态复位好让用户能重试。
            synchronized(lock) {
                started = false
                discoveryListener = null
                this.onEvent = null
            }
            onEvent(DiscoveryEvent.Failed("无法开始搜索电脑：${e.message ?: "系统服务异常"}"))
        }
    }

    override fun stop() {
        val listener = synchronized(lock) {
            if (!started && discoveryListener == null) return
            started = false
            onEvent = null
            resolveQueue.clear()
            resolving = false
            discoveryListener.also { discoveryListener = null }
        }
        // 注销必须容错: stop 时框架已经自己停了(例如 onStartDiscoveryFailed)会抛
        // FAILURE_ALREADY_ACTIVE, 那不是用户需要知道的错误。
        if (listener != null) runCatching { manager.stopServiceDiscovery(listener) }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // 我们再查服务类型一次: 系统按类型过滤, 但模拟器/厂商实现出现过带子类型的回调。
                if (!serviceTypeMatches(serviceInfo.serviceType)) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                emit(DiscoveryEvent.Lost(serviceInfo.serviceName))
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 这里**不能**用 emit(): emit 是 post 到主线程的, 而下面马上把 started
                // 置 false, 投递时会被"stop 之后不许回调"那道检查吃掉 —— 失败就永远
                // 不会到达用户。所以先取回调、清状态, 再同步通知一次。
                val callback = synchronized(lock) {
                    if (discoveryListener !== this) return
                    started = false
                    discoveryListener = null
                    onEvent.also { onEvent = null }
                }
                // 框架文档要求失败后主动 stop 以清理内部状态, 否则下次 start 可能直接失败。
                runCatching { manager.stopServiceDiscovery(this) }
                callback?.invoke(DiscoveryEvent.Failed("无法开始搜索电脑（错误码 $errorCode）"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                emit(DiscoveryEvent.Failed("停止搜索失败（错误码 $errorCode）"))
            }
        }

    /**
     * 把一次解析排队。**绝不在发现回调里直接 resolve** —— 那正是并发 resolve 的来源。
     */
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        val shouldResolve = synchronized(lock) {
            if (!started) return
            resolveQueue.addLast(serviceInfo)
            if (resolving) {
                false
            } else {
                resolving = true
                true
            }
        }
        if (shouldResolve) resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            if (!started) {
                resolving = false
                return
            }
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return

        @Suppress("DEPRECATION") // resolveService 在 API 34 才被回调式 API 取代; minSdk 26 仍用它
        val accepted = runCatching { manager.resolveService(next, resolveListener) }.isSuccess
        if (!accepted) {
            emit(DiscoveryEvent.Failed("无法解析设备 ${next.serviceName}"))
            resolveNext()
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            emit(DiscoveryEvent.Failed("无法解析设备 ${serviceInfo.serviceName}（错误码 $errorCode）"))
            resolveNext()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            @Suppress("DEPRECATION") // host 在 API 34 被 hostAddresses 取代; minSdk 26 仍用它
            val hostAddress = serviceInfo.host?.hostAddress
            val device = discoveredDeviceFrom(
                serviceName = serviceInfo.serviceName,
                host = hostAddress,
                port = serviceInfo.port,
                attributes = serviceInfo.attributes.orEmpty(),
            )
            if (device == null) {
                // TXT 缺 id / 地址没解析出来: 诚实报告"这台电脑的信息不完整", 不猜一个地址。
                emit(DiscoveryEvent.Failed("设备信息不完整：${serviceInfo.serviceName}"))
            } else {
                emit(DiscoveryEvent.Found(device))
            }
            resolveNext()
        }
    }

    /** 投递到主线程, 并在真正调用时**再确认一次**没有 stop(迟到的回调不许漏出去)。 */
    private fun emit(event: DiscoveryEvent) {
        handler.post {
            val callback = synchronized(lock) { if (started) onEvent else null }
            callback?.invoke(event)
        }
    }

    internal fun serviceTypeMatches(type: String?): Boolean =
        type == IMAGEFILTER_SERVICE_TYPE || type == IMAGEFILTER_SERVICE_TYPE.trimEnd('.')
}

/**
 * `NsdServiceInfo` → [DiscoveredDevice] 的**纯解析规则**, 单独抽出来才有 JVM 单测。
 *
 * TXT 契约(计划 §0.1): `v=1`、`id=<desktopId>`、`name=<hostname>`、`pair=open|closed`。
 *   · `id` 缺失 → `null`(不是 ImageFilter v1 服务, 或广播坏了 —— 发请求也没用);
 *   · `name` 缺失 → 退回实例名(实例名本身就含 hostname);
 *   · `pair` 只有恰好 `open` 才算开放; 其余(含缺失)一律 `false` —— 对"能不能配对"
 *     永远做保守判断, 不让界面把一台关了窗口的电脑画成可点;
 *   · `host` 空 / `port` 非法 → `null`, 不编造地址。
 *
 * `v` 目前不参与判定: 未来桌面端升到 v2 时, 老手机至少还能把设备显示出来走既有端点,
 * 而不是因为一个不认识的版本号整台电脑消失。
 */
internal fun discoveredDeviceFrom(
    serviceName: String,
    host: String?,
    port: Int,
    attributes: Map<String, ByteArray>,
): DiscoveredDevice? {
    val id = attributes.textValue("id") ?: return null
    if (host.isNullOrBlank()) return null
    if (port !in 1..65535) return null
    return DiscoveredDevice(
        serviceName = serviceName,
        name = attributes.textValue("name") ?: serviceName,
        host = host,
        port = port,
        pairOpen = attributes.textValue("pair") == "open",
        desktopId = id,
    )
}

private fun Map<String, ByteArray>.textValue(key: String): String? =
    get(key)?.let { String(it, Charsets.UTF_8).trim() }?.takeIf { it.isNotEmpty() }
