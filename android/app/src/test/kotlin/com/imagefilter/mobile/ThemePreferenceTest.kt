// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.imagefilter.mobile.data.ThemeMode
import com.imagefilter.mobile.data.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ThemePreference] 的落盘行为 —— 跑在一个**真的 DataStore** 上(临时文件)。
 *
 * 为什么不用假件代替: 这三条要验的恰恰是 DataStore 自己的行为 ——
 * "默认值是什么"、"写下去再开一次还在不在"、"文件坏掉时会不会把 App 带崩"。
 * `PreferenceDataStoreFactory` 的 JVM 变体不依赖 `Context`, 所以无需 Robolectric,
 * 也不用为了测试新增依赖。
 *
 * 每个用例用独立的临时目录: DataStore 对同一个文件只允许一个活跃实例, 目录隔离比
 * 手工清理单例状态可靠。
 */
class ThemePreferenceTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("theme-preference").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val prefsFile: File
        get() = File(dir, "theme.preferences_pb")

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun newStore(scope: CoroutineScope, file: File = prefsFile): ThemePreference =
        ThemePreference(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            ),
        )

    /** 取消并等 DataStore 真正收尾 —— 文件锁/单例登记是在 Job 完成回调里释放的。 */
    private suspend fun CoroutineScope.shutdown() {
        cancel()
        coroutineContext[Job]?.join()
    }

    @Test
    fun absent_file_defaults_to_system() = runBlocking {
        val scope = newScope()
        try {
            assertEquals(ThemeMode.SYSTEM, newStore(scope).mode.first())
        } finally {
            scope.shutdown()
        }
    }

    @Test
    fun every_mode_round_trips_through_the_flow() = runBlocking {
        val scope = newScope()
        try {
            val store = newStore(scope)
            for (mode in ThemeMode.entries) {
                store.set(mode)
                assertEquals(mode, store.mode.first())
            }
        } finally {
            scope.shutdown()
        }
    }

    @Test
    fun set_persists_across_a_reopened_store() = runBlocking {
        val firstScope = newScope()
        newStore(firstScope).set(ThemeMode.DARK)
        // 关掉第一份(释放 DataStore 对文件的占用), 再从磁盘重新开一份。
        firstScope.shutdown()

        val secondScope = newScope()
        try {
            assertEquals(ThemeMode.DARK, newStore(secondScope).mode.first())
        } finally {
            secondScope.shutdown()
        }
    }

    @Test
    fun corrupted_file_falls_back_to_system_instead_of_crashing() = runBlocking {
        // 不是合法的 Preferences protobuf: DataStore 读的时候会抛 CorruptionException
        // (IOException 的子类)。偏好读取必须把它接住并回默认值, 否则根组合直接崩。
        prefsFile.writeBytes("this is not a preferences protobuf".toByteArray())

        val scope = newScope()
        try {
            assertEquals(ThemeMode.SYSTEM, newStore(scope).mode.first())
        } finally {
            scope.shutdown()
        }
    }

    @Test
    fun unknown_stored_value_is_treated_as_system() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("PURPLE"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("DARK"))
    }
}
