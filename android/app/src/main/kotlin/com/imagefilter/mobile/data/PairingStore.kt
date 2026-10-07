// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 一次成功的配对。**token 是机密**, 别把它塞进日志/崩溃上报/界面文本。
 *
 * [note] 是用户自己起的名字(例如"家里的台式机"), 可选, 不参与任何协议。
 */
data class Pairing(val url: String, val token: String, val note: String = "")

/** 落盘时**非机密**的那一半: 地址与备注。token 不在这里(见 [SecretStore])。 */
data class PairingPrefs(val url: String, val note: String = "")

/**
 * 非机密那一半的存取 —— 抽成接口的理由与 [SecretStore] 完全相同:
 * DataStore 是安卓组件(JVM 单测里没有 Context), 而"半配对算不算配对"这类**判定**
 * 必须能在 JVM 上真的跑一遍。
 */
interface PairingPrefsStore {
    suspend fun load(): PairingPrefs?
    suspend fun save(prefs: PairingPrefs)
    suspend fun clear()
}

/** DataStore 委托必须是**文件级**属性 —— 一个文件一个实例, 多建会撞 DataStore 的单例约束。 */
private val Context.pairingDataStore by preferencesDataStore(name = "pairing")

/** 真机实现: `pairing.preferences_pb`, 明文(里面没有机密)。 */
class DataStorePairingPrefsStore(context: Context) : PairingPrefsStore {

    private val appContext = context.applicationContext
    private val keyUrl = stringPreferencesKey("url")
    private val keyNote = stringPreferencesKey("note")

    override suspend fun load(): PairingPrefs? {
        val prefs = appContext.pairingDataStore.data.first()
        // 空串与"没有"同义: 一个空地址不构成配对, 不许把它当成"地址在这儿"。
        val url = prefs[keyUrl]?.takeIf { it.isNotBlank() } ?: return null
        return PairingPrefs(url, prefs[keyNote].orEmpty())
    }

    override suspend fun save(prefs: PairingPrefs) {
        appContext.pairingDataStore.edit {
            it[keyUrl] = prefs.url.trimEnd('/')
            it[keyNote] = prefs.note
        }
    }

    override suspend fun clear() {
        appContext.pairingDataStore.edit {
            it.remove(keyUrl)
            it.remove(keyNote)
        }
    }
}

/**
 * 配对信息的**唯一入口** —— 地址/备注走 DataStore(非机密), token 走 [SecretStore](加密)。
 *
 * 拆成两处的理由: DataStore 是明文文件, 而 token 是"你电脑共享目录的读写凭据";
 * 两者的生命周期也不同 —— 轮换 token 不必动地址, 换电脑不必动加密密钥。
 *
 * **半配对一律当作没配对**: [load] 在"只有地址没有 token"或"只有 token 没有地址"时
 * 都返回 `null`。这不是洁癖 —— 半配对状态下 `ApiClient` 会拿一个空 token 去发请求,
 * 用户看到的将是莫名其妙的 401, 而不是"你还没配对"。
 */
class PairingStore(
    private val prefsStore: PairingPrefsStore,
    private val secret: SecretStore,
) {

    suspend fun load(): Pairing? = withContext(Dispatchers.IO) {
        val prefs = prefsStore.load() ?: return@withContext null
        val token = secret.getToken() ?: return@withContext null
        Pairing(prefs.url, token, prefs.note)
    }

    /**
     * 保存一次配对。
     *
     * **顺序刻意: 先写 token, 再写地址。** 任何一步失败都只会留下"什么都没有"或
     * "只有 token 没有地址"(地址不在 → [load] 回 `null` → 当作没配对, 用户重新配对一次),
     * 绝不留下"地址在、token 不在"那种一进门就 401 的状态。
     *
     * 失败**往上抛**: 保存不下来就是没保存成功, 界面必须如实说, 而不是回调"配对成功"。
     */
    suspend fun save(url: String, token: String, note: String = "") {
        withContext(Dispatchers.IO) {
            secret.putToken(token)
            prefsStore.save(PairingPrefs(url.trimEnd('/'), note.trim()))
        }
    }

    /** 取消配对(界面上的"重新配对")。两半都清 —— 只清一半就是上面那种半配对状态。 */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            secret.clear()
            prefsStore.clear()
        }
    }

    companion object {
        /** 真机路径。JVM 单测不经过它(用两个假件直接构造 [PairingStore])。 */
        fun create(context: Context): PairingStore = PairingStore(
            DataStorePairingPrefsStore(context.applicationContext),
            KeystoreSecretStore(context.applicationContext),
        )
    }
}
