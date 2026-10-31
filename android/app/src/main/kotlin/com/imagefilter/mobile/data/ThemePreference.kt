// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 外观偏好的三档。
 *
 * [SYSTEM] 是默认值 —— 不猜用户偏好, 跟随系统深浅色。用户显式选浅/深后, 即使系统切了
 * 主题, App 也保持用户选的那一档, 直到他改回"跟随系统"。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /**
         * 从磁盘上的字符串还原。
         *
         * 读不懂的值(旧版本/手改文件/写坏一半)一律当 [SYSTEM]: 一个坏值不该让 App
         * 起不来, 也不该把用户锁在一个他没选过的主题里。
         */
        fun fromStored(raw: String?): ThemeMode {
            if (raw == null) return SYSTEM
            return entries.firstOrNull { it.name == raw } ?: SYSTEM
        }
    }
}

/** DataStore 委托必须是**文件级**属性 —— 一个文件一个实例, 多建会撞单例约束。 */
private val Context.themeDataStore by preferencesDataStore(name = "theme")

/**
 * 外观偏好的持久化。文件名就是计划要求的 `theme.preferences_pb`(由 `name = "theme"` 得来)。
 *
 * 与 [PairingStore] 同一套理由: 落盘的只有"跟随系统/浅色/深色"这三档, 里面没有任何
 * 机密, 所以用明文 DataStore, 不走 KeyStore。
 *
 * **读不回来时不崩**: DataStore 文件坏了会抛 [IOException](实际是 `CorruptionException`),
 * [mode] 在流上把它接住并回 [ThemeMode.SYSTEM] —— 与"从没设置过"是同一个安全默认。
 * 不接住的话, 一个坏文件会让根组合在收集偏好时直接崩掉, 用户连设置界面都进不去。
 */
class ThemePreference(private val dataStore: DataStore<Preferences>) {

    /** 当前偏好。冷启动第一次读盘, 之后每次写入都会发新值。 */
    val mode: Flow<ThemeMode> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> ThemeMode.fromStored(prefs[KEY_MODE]) }

    /** 写入偏好。选中即写, 不攒"保存"按钮。 */
    suspend fun set(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[KEY_MODE] = mode.name }
    }

    companion object {
        private val KEY_MODE = stringPreferencesKey("mode")

        /** 真机入口。JVM 单测直接构造并注入内存/临时文件的 DataStore。 */
        fun create(context: Context): ThemePreference =
            ThemePreference(context.applicationContext.themeDataStore)
    }
}
