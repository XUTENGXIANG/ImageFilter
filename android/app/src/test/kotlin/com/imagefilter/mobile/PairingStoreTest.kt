// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingPrefs
import com.imagefilter.mobile.data.PairingPrefsStore
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.data.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairingStore] 的**判定逻辑**, 跑在 JVM 上(两个假件代替 DataStore 与 AndroidKeyStore)。
 *
 * 哪些**没有**被测、为什么不测: 真机上那两个实现本身 ——
 *   · `DataStorePairingPrefsStore` 要 `Context`(JVM 单测里没有);
 *   · `KeystoreSecretStore` 要 AndroidKeyStore(JVM 里根本不存在; 引 Robolectric 与
 *     "不新增依赖"的约束冲突 —— 这是规格 §10.1 已经划过的取舍)。
 * 所以这里一个字符都不假装验过加解密: 下面每个断言针对的都是**"哪一半写到哪儿、失败了留什么"**
 * 这类判定, 而它正是出错时最难查的那部分。
 */
class PairingStoreTest {

    private val token = "a1b2c3d4".repeat(8)

    /** 内存版 [SecretStore] —— 记录写入顺序, 也能按需失败。 */
    private class FakeSecret(
        var value: String? = null,
        var failOnPut: Boolean = false,
    ) : SecretStore {
        val puts = mutableListOf<String>()
        var cleared = false

        override fun putToken(token: String) {
            if (failOnPut) throw IllegalStateException("Keystore 写不进去")
            value = token
            puts += token
        }

        override fun getToken(): String? = value

        override fun clear() {
            value = null
            cleared = true
        }
    }

    /** 内存版 DataStore。 */
    private class FakePrefs(var value: PairingPrefs? = null) : PairingPrefsStore {
        val saves = mutableListOf<PairingPrefs>()
        var cleared = false

        override suspend fun load(): PairingPrefs? = value

        override suspend fun save(prefs: PairingPrefs) {
            value = prefs
            saves += prefs
        }

        override suspend fun clear() {
            value = null
            cleared = true
        }
    }

    @Test
    fun save_then_load_round_trips_and_normalizes_the_url() = runTest {
        val secret = FakeSecret()
        val prefs = FakePrefs()
        val store = PairingStore(prefs, secret)

        store.save("http://192.168.1.5:8765/", token, " 家里的台式机 ")

        val loaded = store.load()
        assertEquals(Pairing("http://192.168.1.5:8765", token, "家里的台式机"), loaded)
        // 关键的一半各自落到**对的地方**: token 进加密存储, 地址/备注进非机密存储。
        // 写反了(把 token 塞进 DataStore)会让这台机器的钥匙以明文躺在磁盘上, 而
        // 上面那条 round-trip 断言**照样是绿的** —— 所以这一条必须单独钉。
        assertEquals(listOf(token), secret.puts)
        assertEquals(listOf(PairingPrefs("http://192.168.1.5:8765", "家里的台式机")), prefs.saves)
    }

    @Test
    fun a_half_pairing_is_not_a_pairing() = runTest {
        // ① 有地址没 token: 真实成因是"Keystore 的密钥没了(换机/恢复出厂)" —— 拿一个空 token
        //    去发请求, 用户看到的将是莫名其妙的 401, 而不是"你还没配对"。
        val urlOnly = FakePrefs(PairingPrefs("http://192.168.1.5:8765"))
        assertNull(PairingStore(urlOnly, FakeSecret(value = null)).load())

        // ② 有 token 没地址: 不知道该连哪儿。
        val tokenOnly = FakeSecret(value = token)
        assertNull(PairingStore(FakePrefs(null), tokenOnly).load())
    }

    @Test
    fun the_token_is_written_before_the_url_so_a_failure_leaves_nothing_paired() = runBlocking {
        val secret = FakeSecret(failOnPut = true)
        val prefs = FakePrefs()
        val store = PairingStore(prefs, secret)

        // 保存失败必须**往上抛**: 界面据此说"没保存成功", 而不是回调"配对成功"
        val e = runCatching { store.save("http://192.168.1.5:8765", token) }.exceptionOrNull()
        assertTrue("实际=$e", e is IllegalStateException)

        // 顺序的意义: 地址还没写 → 盘上什么都没留下 → load() 回 null → 用户还是在配对页,
        // 绝不会出现"地址在、token 不在"那种一进门就 401 的状态。
        assertTrue("token 写失败后不许再写地址", prefs.saves.isEmpty())
        assertNull(store.load())
    }

    @Test
    fun a_second_save_replaces_the_token_without_touching_the_address() = runTest {
        val secret = FakeSecret()
        val prefs = FakePrefs()
        val store = PairingStore(prefs, secret)
        val rotated = "f".repeat(64)

        store.save("http://192.168.1.5:8765", token)
        store.save("http://192.168.1.5:8765", rotated)

        // 轮换配对码不必动地址(两者生命周期不同) —— 这正是把两半拆开放的理由
        assertEquals(rotated, store.load()!!.token)
        assertEquals("http://192.168.1.5:8765", store.load()!!.url)
        assertEquals(listOf(token, rotated), secret.puts)
    }

    @Test
    fun clear_wipes_both_halves() = runTest {
        val secret = FakeSecret()
        val prefs = FakePrefs()
        val store = PairingStore(prefs, secret)
        store.save("http://192.168.1.5:8765", token, "家里")

        store.clear()

        assertNull(store.load())
        assertTrue(secret.cleared)
        assertTrue("只清一半就会留下半配对状态", prefs.cleared)
        assertNull(secret.value)
        assertNull(prefs.value)
    }
}
