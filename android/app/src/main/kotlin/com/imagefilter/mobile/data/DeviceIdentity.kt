// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.security.SecureRandom

/**
 * 设备 id 的**持久化**面 —— 抽成接口的理由与 [PairingPrefsStore] 相同:
 * DataStore 是安卓组件(JVM 单测里没有 Context), 而"坏文件要不要重新生成、生成了要不要落盘"
 * 这类**判定**必须能在 JVM 上真的跑一遍。
 */
interface DeviceIdStore {
    /** 已落盘的 id; 没有则 `null`。实现可以对坏文件抛异常(调用方会当"没有")。 */
    suspend fun load(): String?

    suspend fun save(id: String)
}

/**
 * 读系统级设备名的**来源**。[DeviceIdentity] 只负责回退链, 读哪儿由它决定。
 *
 * 生产实现读 `Settings.Global.DEVICE_NAME`(用户在系统设置里改的名字);
 * 单测注入假件, 因为 JVM 单测里没有 `ContentResolver`。
 */
fun interface DeviceNameSource {
    fun read(): String?
}

/** 两条来源都空时用的名字。**只在这一处出现**, 测试据它断言回退到底。 */
const val FALLBACK_DEVICE_NAME = "Android"

/**
 * 设备名回退链: `Settings.Global.DEVICE_NAME` → [Build.MODEL] → [FALLBACK_DEVICE_NAME]。
 *
 * 纯函数, 每个分支都有 JVM 单测。空串/纯空白与"没有"同义 —— 把它当名字会让电脑端的
 * 待确认弹窗显示一行空白, 用户根本不知道自己批准的是哪台手机。
 */
fun resolveDeviceName(settingsName: String?, model: String?): String =
    listOf(settingsName, model)
        .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        ?: FALLBACK_DEVICE_NAME

/** 合法的设备 id: **恰好 32 位十六进制**(128 bit)。大小写都认, 出口统一小写。 */
internal val DEVICE_ID_PATTERN = Regex("^[0-9a-fA-F]{32}$")

/**
 * 本机的**稳定身份** —— mDNS 配对请求里的 `deviceId` / `deviceName`。
 *
 * `deviceId` 的语义是"同一台手机在多次配对之间保持不变": 桌面端用它做 10 秒限流与
 * 已连接设备列表。所以它在**第一次**读时生成并落盘, 之后一直用盘上的值。
 *
 * 三条纪律:
 *   · **坏文件重新生成, 绝不崩**: 读失败或盘上不是 32 位 hex 时生成新 id。代价是这台手机
 *     在电脑眼里"换了一台"(限流/设备列表多一条), 但让用户因为一个损坏的偏好文件而闪退更糟;
 *   · **写失败也返回新 id**: 那一次配对仍能进行, 只是下次可能再生成一次 —— 宁可身份偶有漂移,
 *     也不要配对流程因为磁盘问题整个停住;
 *   · **id 不是机密**: 它只是一个标识, token 才机密([SecretStore])。
 */
class DeviceIdentity(
    private val idStore: DeviceIdStore,
    /** 生产是 [AndroidDeviceNameSource]; 默认假件返回 `null`。 */
    private val nameSource: DeviceNameSource = DeviceNameSource { null },
    /** 生产是 [Build.MODEL]; 单测显式给值, 不依赖 mockable android.jar 的静态字段。 */
    private val model: String? = null,
) {

    /**
     * 读稳定的 deviceId; 第一次(或盘上坏了)时生成并尽量落盘。
     *
     * 这里**不做** `withContext(Dispatchers.IO)`: DataStore 的 `data`/`edit` 自己就在 IO
     * 线程上跑, 再包一层只会让测试的虚拟时间多一次真实线程切换。
     */
    suspend fun deviceId(): String {
        val stored = try {
            idStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 坏文件/读失败 = 没有 id, 走下面重新生成 —— 不把磁盘问题升级成崩溃
            null
        }

        if (stored != null && DEVICE_ID_PATTERN.matches(stored)) return stored.lowercase()

        val fresh = newDeviceId()
        try {
            idStore.save(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 落盘失败不阻断本次配对: 返回新 id, 让用户先把这次连上
        }
        return fresh
    }

    /** 设备名。同步读系统设置, 调用方本来就在主线程/协程里。 */
    fun deviceName(): String = resolveDeviceName(nameSource.read(), model)

    companion object {
        /** 真机路径。JVM 单测用假 [DeviceIdStore] 直接构造。 */
        fun create(context: Context): DeviceIdentity = DeviceIdentity(
            idStore = DataStoreDeviceIdStore(context.applicationContext),
            nameSource = AndroidDeviceNameSource(context.applicationContext),
            model = Build.MODEL,
        )
    }
}

/**
 * 生成 32 位小写十六进制 id。
 *
 * 用 [SecureRandom] 而不是时间戳/UUID 字符串: id 会进入桌面端的待确认弹窗与设备列表,
 * 可预测的 id 让同一局域网里的人能冒充/预占; 128 bit 随机没有这个性质。
 */
internal fun newDeviceId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    val out = StringBuilder(32)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * DataStore 委托必须是**文件级**属性 —— 一个文件一个实例, 多建会撞 DataStore 的单例约束。
 *
 * `corruptionHandler` 是第二道防线(第一道是 [DeviceIdentity.deviceId] 自己 catch):
 * DataStore 读到坏文件时直接换成一个空 Preferences, 而不是让每个读它的协程都抛异常。
 */
private val Context.deviceIdentityDataStore by preferencesDataStore(
    name = "device_identity",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 真机实现: `device_identity.preferences_pb`, 明文(id 不是机密)。 */
class DataStoreDeviceIdStore(context: Context) : DeviceIdStore {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("deviceId")

    override suspend fun load(): String? = appContext.deviceIdentityDataStore.data.first()[key]

    override suspend fun save(id: String) {
        appContext.deviceIdentityDataStore.edit { it[key] = id }
    }
}

/**
 * `Settings.Global.DEVICE_NAME`(API 25+)。读不到(厂商裁剪/权限异常)时返回 `null`,
 * 由 [resolveDeviceName] 回退到 [Build.MODEL]。
 */
class AndroidDeviceNameSource(context: Context) : DeviceNameSource {

    private val appContext = context.applicationContext

    override fun read(): String? = runCatching {
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()
}
