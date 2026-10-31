// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.IMAGEFILTER_SERVICE_TYPE
import com.imagefilter.mobile.data.discoveredDeviceFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NsdDiscoverySource] 里**纯解析规则**的 JVM 单测。
 *
 * `NsdManager` 本身与发现流程没有 JVM 单测(框架 API, 模拟器也过不了 NAT —— 计划 §8);
 * 能测且最值得测的是"一条 TXT 什么算完整设备、什么算不可用", 因为它是这个适配层里
 * 唯一会安静出错的判定: 猜错一个地址, 用户看到的是"连不上", 而不是"解析失败"。
 */
class NsdDiscoverySourceTest {

    private fun attrs(vararg pairs: Pair<String, String>): Map<String, ByteArray> =
        pairs.associate { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }

    private fun parse(
        serviceName: String = "ImageFilter DESKTOP-96B8FMF",
        host: String? = "192.168.1.20",
        port: Int = 8765,
        attributes: Map<String, ByteArray> = attrs(
            "v" to "1",
            "id" to "desktop-id-1",
            "name" to "DESKTOP-96B8FMF",
            "pair" to "open",
        ),
    ) = discoveredDeviceFrom(serviceName, host, port, attributes)

    @Test
    fun a_complete_txt_record_becomes_a_discovered_device() {
        assertEquals(
            com.imagefilter.mobile.data.DiscoveredDevice(
                serviceName = "ImageFilter DESKTOP-96B8FMF",
                name = "DESKTOP-96B8FMF",
                host = "192.168.1.20",
                port = 8765,
                pairOpen = true,
                desktopId = "desktop-id-1",
            ),
            parse(),
        )
    }

    @Test
    fun a_missing_or_blank_id_is_not_a_device() {
        // 没有 id 说明不是 ImageFilter v1 服务(或广播坏了): 拿它去发配对请求没有意义
        assertNull(parse(attributes = attrs("v" to "1", "name" to "PC", "pair" to "open")))
        assertNull(parse(attributes = attrs("id" to "   ", "name" to "PC", "pair" to "open")))
    }

    @Test
    fun a_missing_name_falls_back_to_the_service_instance_name() {
        val device = parse(attributes = attrs("id" to "d", "pair" to "open"))
        assertEquals("ImageFilter DESKTOP-96B8FMF", device!!.name)
    }

    @Test
    fun only_the_exact_value_open_means_the_pairing_window_is_open() {
        assertTrue(parse(attributes = attrs("id" to "d", "pair" to "open"))!!.pairOpen)
        // 保守判断: 其余一律关闭 —— 界面宁可提示"去电脑上点配对新设备", 也不许把
        // 一台窗口没开的电脑画成可点(failed 请求只会给用户一次 409)
        assertFalse(parse(attributes = attrs("id" to "d", "pair" to "closed"))!!.pairOpen)
        assertFalse(parse(attributes = attrs("id" to "d", "pair" to "OPEN"))!!.pairOpen)
        assertFalse(parse(attributes = attrs("id" to "d"))!!.pairOpen)
    }

    @Test
    fun a_missing_or_invalid_host_and_port_is_not_a_device() {
        assertNull("没有地址就发不了请求", parse(host = null))
        assertNull(parse(host = "  "))
        assertNull(parse(port = 0))
        assertNull(parse(port = -1))
        assertNull(parse(port = 70000))
    }

    @Test
    fun utf8_names_are_decoded_as_utf8_not_platform_charset() {
        // 电脑名可能是中文(Windows 允许), TXT 按 UTF-8 存 —— 用默认字符集会解成乱码
        val device = parse(attributes = attrs("id" to "d", "name" to "客厅的电脑", "pair" to "open"))
        assertEquals("客厅的电脑", device!!.name)
    }

    @Test
    fun an_unknown_txt_version_does_not_hide_the_device() {
        // v2 的桌面端仍应被老手机看见(端点没变), 否则升级电脑 = 手机上一夜之间找不到设备
        assertEquals("desktop-id-1", parse(attributes = attrs("v" to "2", "id" to "desktop-id-1"))!!.desktopId)
    }

    @Test
    fun the_android_service_type_is_the_frozen_wire_value() {
        // 计划 §0.1: Android 的服务类型必须逐字是 _imagefilter._tcp.(末尾的点不能丢)
        assertEquals("_imagefilter._tcp.", IMAGEFILTER_SERVICE_TYPE)
    }
}
