// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

/**
 * 一台被 mDNS 发现的电脑。
 *
 * 字段语义(与计划 §0.1 的 TXT 契约逐字对应):
 *   · [serviceName] —— mDNS 实例名(`ImageFilter <hostname>`), **Lost 事件按它配对**;
 *   · [name] —— TXT 的 `name=<hostname>`, 给用户看的电脑名;
 *   · [host] / [port] —— 电脑上 HTTP 服务的地址(配对请求打这里);
 *   · [pairOpen] —— TXT 的 `pair=open`。**`false` 不代表不能连**, 而是"电脑上还没点
 *     「配对新设备」"——界面该提示用户去电脑上打开配对窗口, 而不是把设备藏掉;
 *   · [desktopId] —— TXT 的 `id`。v1 只用于显示/排障, 不参与鉴权。
 */
data class DiscoveredDevice(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    val pairOpen: Boolean,
    val desktopId: String,
)

/** 发现层的三种事件。**没有"找到了一半"** —— 只报告能拿去发请求的完整设备。 */
sealed interface DiscoveryEvent {
    data class Found(val device: DiscoveredDevice) : DiscoveryEvent

    /** 按 [DiscoveredDevice.serviceName] 配对 —— 与 Found 用的键是同一个。 */
    data class Lost(val serviceName: String) : DiscoveryEvent

    /** 搜索/解析失败。[message] 是给用户看的一句话(不再拆错误码: 这里没有可行动的细分)。 */
    data class Failed(val message: String) : DiscoveryEvent
}

/**
 * 局域网发现源。真机是 [NsdDiscoverySource]; 测试/预览可注入假件。
 *
 * `start` 可以重复调用(实现要么幂等、要么替换回调);`stop` 之后必须不再回调。
 */
interface DiscoverySource {
    fun start(onEvent: (DiscoveryEvent) -> Unit)

    fun stop()
}
