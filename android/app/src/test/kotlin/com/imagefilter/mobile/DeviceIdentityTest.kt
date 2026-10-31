// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.DeviceIdStore
import com.imagefilter.mobile.data.DeviceIdentity
import com.imagefilter.mobile.data.DeviceNameSource
import com.imagefilter.mobile.data.FALLBACK_DEVICE_NAME
import com.imagefilter.mobile.data.newDeviceId
import com.imagefilter.mobile.data.resolveDeviceName
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [DeviceIdentity] 的**判定逻辑**, 跑在 JVM 上(假 DataStore 代替安卓文件)。
 *
 * 真机 DataStore/`Settings.Global` 本身没有 JVM 单测(与 `PairingStoreTest` 同一条取舍):
 * 这里断言的是"什么时候生成、什么时候复用、坏文件怎么办、名字回退到哪一档"。
 */
class DeviceIdentityTest {

    /** 内存版 DataStore; 可以模拟"文件坏了/读不动/写不进去"。 */
    private class FakeIdStore(
        var value: String? = null,
        var failOnLoad: Boolean = false,
        var failOnSave: Boolean = false,
    ) : DeviceIdStore {
        val saves = mutableListOf<String>()
        var loads = 0

        override suspend fun load(): String? {
            loads++
            if (failOnLoad) throw IOException("Corrupted preferences file")
            return value
        }

        override suspend fun save(id: String) {
            if (failOnSave) throw IOException("磁盘写不进去")
            value = id
            saves += id
        }
    }

    private fun identity(
        store: FakeIdStore = FakeIdStore(),
        name: String? = null,
        model: String? = null,
    ) = DeviceIdentity(store, DeviceNameSource { name }, model)

    // ── deviceId ─────────────────────────────────────────────────────────────

    @Test
    fun the_first_read_generates_and_persists_exactly_one_32_hex_id() = runTest {
        val store = FakeIdStore()
        val id = identity(store).deviceId()

        assertEquals("必须是 32 位小写十六进制: $id", 32, id.length)
        assertTrue("必须是 32 位小写十六进制: $id", Regex("^[0-9a-f]{32}$").matches(id))
        // 生成一次就要落盘一次 —— 不落盘的话每次启动/每次配对都是新身份
        assertEquals(listOf(id), store.saves)
        assertEquals(id, store.value)
    }

    @Test
    fun a_second_read_reuses_the_persisted_id_without_generating_another() = runTest {
        val store = FakeIdStore()
        val first = identity(store).deviceId()
        val second = identity(store).deviceId()

        assertEquals("同一台手机跨进程/跨次读必须稳定", first, second)
        assertEquals("第二次不许再写一次盘", 1, store.saves.size)
        assertEquals("第二次不许再生成", first, store.saves.single())
    }

    @Test
    fun a_corrupt_value_is_regenerated_and_persisted_instead_of_crashing() = runTest {
        // 盘上不是 32 位 hex(截断/乱码/未来换了格式) → 当作没有, 重新生成
        val store = FakeIdStore(value = "not-a-hex-id")
        val id = identity(store).deviceId()

        assertTrue("必须是 32 位小写十六进制: $id", Regex("^[0-9a-f]{32}$").matches(id))
        assertNotEquals("not-a-hex-id", id)
        assertEquals("重新生成后必须覆盖坏值", listOf(id), store.saves)
        assertEquals(id, store.value)
    }

    @Test
    fun an_unreadable_file_is_regenerated_and_does_not_crash() = runTest {
        // DataStore 读到坏文件时可能直接抛 CorruptionException(IOException 子类)
        val store = FakeIdStore(failOnLoad = true)
        val id = identity(store).deviceId()

        assertTrue("必须是 32 位小写十六进制: $id", Regex("^[0-9a-f]{32}$").matches(id))
        assertEquals("读失败也要把新 id 写下去, 下次才稳定", listOf(id), store.saves)
    }

    @Test
    fun a_failed_write_still_returns_a_usable_id() = runTest {
        // 磁盘问题不该让整个配对流程停住: 这一次用新 id, 将来可能再生成一次 —— 允许漂移,
        // 但不许崩溃/不许返回空串。
        val store = FakeIdStore(failOnSave = true)
        val id = identity(store).deviceId()

        assertTrue("必须是 32 位小写十六进制: $id", Regex("^[0-9a-f]{32}$").matches(id))
        assertTrue(store.saves.isEmpty())
        assertEquals(null, store.value)
    }

    @Test
    fun a_stored_uppercase_hex_id_is_accepted_and_normalized_to_lowercase() = runTest {
        val upper = "ABCDEF0123456789ABCDEF0123456789"
        val store = FakeIdStore(value = upper)
        val id = identity(store).deviceId()

        assertEquals(upper.lowercase(), id)
        assertTrue("已是合法 id, 不该覆盖", store.saves.isEmpty())
    }

    @Test
    fun the_generator_produces_distinct_32_hex_values() {
        val a = newDeviceId()
        val b = newDeviceId()
        assertEquals(32, a.length)
        assertEquals(32, b.length)
        assertNotEquals("两次随机生成不该撞(概率上等于 0)", a, b)
    }

    // ── deviceName ───────────────────────────────────────────────────────────

    @Test
    fun device_name_prefers_settings_then_model_then_android() {
        // ① 系统设置里的名字优先, 并去掉两头空白
        assertEquals(
            "小明的手机",
            DeviceIdentity(FakeIdStore(), DeviceNameSource { " 小明的手机 " }, "Pixel 8").deviceName(),
        )
        // ② 设置名为空/纯空白 → 型号
        assertEquals(
            "Pixel 8",
            DeviceIdentity(FakeIdStore(), DeviceNameSource { "   " }, "Pixel 8").deviceName(),
        )
        // ③ 设置名读不到 → 型号(型号也去空白)
        assertEquals(
            "Pixel 8",
            DeviceIdentity(FakeIdStore(), DeviceNameSource { null }, " Pixel 8 ").deviceName(),
        )
        // ④ 两者都没有 → 固定回退
        assertEquals(
            FALLBACK_DEVICE_NAME,
            DeviceIdentity(FakeIdStore(), DeviceNameSource { null }, "  ").deviceName(),
        )
    }

    @Test
    fun resolve_device_name_is_the_single_source_of_the_fallback_chain() {
        assertEquals("设置名", resolveDeviceName("设置名", "型号"))
        assertEquals("型号", resolveDeviceName("", "型号"))
        assertEquals("型号", resolveDeviceName(null, "型号"))
        assertEquals("Android", resolveDeviceName(null, null))
        assertEquals("Android", resolveDeviceName("  ", "  "))
    }
}
