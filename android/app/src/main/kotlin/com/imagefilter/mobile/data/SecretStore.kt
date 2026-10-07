// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对 token 的落盘点 —— **薄接口, 三个方法**。
 *
 * 为什么是接口而不是计划里写的一个具体类: 真正难的那一层([KeystoreSecretStore] 用的
 * AndroidKeyStore)**在 JVM 单测里根本不存在**(规格 §10.1 的取舍 —— 要测它就得引
 * Robolectric, 那与本计划"不新增依赖"的约束直接冲突)。把它收成三个方法之后:
 *   · [PairingStore] 的逻辑("先写 token 再写地址"、“半配对一律当没配对”)可以用**内存假件**
 *     在 JVM 上真的跑一遍;
 *   · 剩下没被单测覆盖的, 就只剩**这一层加解密本身**, 它的验收是真机配对流程
 *     (Task 3 的设备清单, 本机没有设备 → 报告里记为 UNVERIFIED)。
 * 两处诚实: 不假装测过 Keystore; 也不因为测不了就把逻辑跟它焊死在一起。
 *
 * 三个方法都是**阻塞式**的(文件 + Keystore IO), 所以调用方一律要挪出主线程 ——
 * [PairingStore] 已经在 `Dispatchers.IO` 上调用它们。
 */
interface SecretStore {
    fun putToken(token: String)

    /** 读不到(没存过 / 密钥换了机器 / 密文坏了)一律返回 `null` —— 当作"没配对", 不许崩。 */
    fun getToken(): String?

    fun clear()
}

/**
 * 真机上的实现: **AndroidKeyStore 里的 AES/GCM 密钥** + 应用私有目录里的密文文件。
 *
 * 为什么不用 `androidx.security.crypto` 的 EncryptedSharedPreferences: 该库已不再推荐,
 * 而我们需要的只是"一个密钥 + 一次 AES/GCM", 直接写反而更少黑盒(计划 §Task 3 的安全要点)。
 *
 * 为什么值得加密: 配对 token = 你电脑上共享目录的完整读写凭据。它只存在**应用私有目录**里
 * (已受沙箱与文件级加密保护), 再叠一层 Keystore 密钥是纵深防御 —— 备份/取证的场景下
 * 拿到的只是密文。
 *
 * 格式: `IV(12 字节) || AES-GCM 密文(含 128 位 tag)`。IV 与密文一起存是因为
 * **GCM 的 IV 绝不允许复用**, 而 AndroidKeyStore 每次加密都会给一枚新的随机 IV
 * (`setRandomizedEncryptionRequired` 默认就是 true, 所以我们也塞不进自己的 IV)。
 * IV 长度写死 12: 那是 AndroidKeyStore 的 GCM IV 长度; 万一将来变了, 解密会失败 →
 * [getToken] 返回 `null` → 用户重新配对一次, 是**安全**的那一侧失败。
 */
class KeystoreSecretStore(private val context: Context) : SecretStore {

    private companion object {
        const val KEY_ALIAS = "imagefilter.mobile.secret"
        const val FILE = "pairing.bin"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * 加密并覆盖落盘。
     *
     * 失败**往上抛**(不像 [getToken] 那样吞掉): 写不进就是"配对没保存成功", 界面必须
     * 如实告诉用户, 而不是留一个"看起来配好了"的假象。
     */
    override fun putToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        file().writeBytes(out)
    }

    override fun getToken(): String? = runCatching {
        val raw = file().readBytes()
        if (raw.size <= GCM_IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, GCM_IV_BYTES)),
            )
        }
        String(cipher.doFinal(raw.copyOfRange(GCM_IV_BYTES, raw.size)), Charsets.UTF_8)
    }.getOrNull() // 密钥被清(换机/恢复出厂/应用数据被清) → 解密失败 → 当作"没配对"让用户重配

    override fun clear() {
        // 只删密文, **不删 Keystore 里的密钥**: 删了下次还得重建, 而重建对用户没有任何好处。
        file().delete()
    }

    private fun file(): File = File(context.filesDir, FILE)
}
