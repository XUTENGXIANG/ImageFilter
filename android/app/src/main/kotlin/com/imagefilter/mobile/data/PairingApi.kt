// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * `POST /api/v1/pair/request` 的结论(计划 §0.4 冻结的四个分支)。
 *
 * 刻意**不**压成 (成功/失败): `Closed` / `RateLimited` 的用户动作完全不同 ——
 * 前者去电脑上点「配对新设备」, 后者等 10 秒重试。把它们混进一句"失败"等于让用户瞎试。
 */
sealed interface PairRequestResult {
    /** 202: 电脑接了请求, 等用户在电脑上点「允许」。 */
    data class Ok(val requestId: String, val expiresAt: Long) : PairRequestResult

    /** 409 `pairing_closed`: 电脑上的配对窗口没开。 */
    data object Closed : PairRequestResult

    /** 429 `rate_limited`: 同一设备 10 秒只能发一次。 */
    data object RateLimited : PairRequestResult

    /** 400/其他/网络失败。[message] 是**给用户看的一句话**, 不含 token(这里也拿不到)。 */
    data class Error(val message: String) : PairRequestResult
}

/** `GET /api/v1/pair/status` 的结论(计划 §0.4)。 */
sealed interface PairStatusResult {
    data object Pending : PairStatusResult

    /**
     * 200 `approved`。地址/配对码**只在这一刻**返回 —— 复用现有的 [Pairing],
     * 所以保存路径与手输/扫码配对完全同一条([PairingStore])。
     */
    data class Approved(val pairing: Pairing) : PairStatusResult

    /** 200 `denied`: 用户在电脑上点了「拒绝」。 */
    data object Denied : PairStatusResult

    /** 200 `expired`: 请求 60 秒没被处理。 */
    data object Expired : PairStatusResult

    /** 404(未知 requestId)/其他码/网络失败/应答无法解析。 */
    data class Error(val message: String) : PairStatusResult
}

/**
 * 配对用的 HTTP 面(免 Bearer)。抽成接口是为了让 [com.imagefilter.mobile.ui.ConnectViewModel]
 * 的状态机能在 JVM 上用假件跑完整条"请求 → 轮询 → 批准 → 保存"。
 *
 * [device] 提供 host/port; [deviceId]/[deviceName] 来自 [DeviceIdentity]。
 */
interface PairingApi {
    suspend fun request(
        device: DiscoveredDevice,
        deviceId: String,
        deviceName: String,
    ): PairRequestResult

    suspend fun status(device: DiscoveredDevice, requestId: String): PairStatusResult
}

/** 配对端点"不替用户干等"的超时: 电脑就在同一局域网, 5 秒不回就是有问题。 */
const val PAIRING_TIMEOUT_SECONDS = 5L

/**
 * 配对端点的 OkHttp 实现。
 *
 * 与图库接口的 [ApiClient] **刻意不共用** client:
 *   · 配对端点免鉴权, **绝不能**带上 `Authorization`(那会把 token 发给一台还没配对的电脑);
 *   · 图库接口的读超时是 60 秒(RAW 预览), 配对等 60 秒只会让用户以为卡死。
 * 所以这里单独造一个不带拦截器、全部 5 秒的 client。
 *
 * 状态解析:
 *   · 202 → [PairRequestResult.Ok](缺 `requestId` 视为无法识别, 不拿空串去轮询);
 *   · 409/429 → [PairRequestResult.Closed] / [PairRequestResult.RateLimited];
 *   · 200 `approved` → [Pairing]`(url, token, note = desktopName)`, 与手输配对同构;
 *   · 404 → [PairStatusResult.Error](电脑上已经没有这个请求, 重发一次才有意义);
 *   · 未知 status / 缺 url/token / 坏 JSON / 网络失败 → `Error`, **绝不许**猜成某一档真实状态。
 */
class HttpPairingApi(
    /** `POST /pair/request` 的 `appVersion` 字段。生产用 [create] 从包信息读。 */
    private val appVersion: String,
    private val client: OkHttpClient = defaultPairingClient(),
) : PairingApi {

    /** 测试可见: 用来断言 5 秒超时与"没有 Authorization 拦截器"。 */
    internal val httpClient: OkHttpClient get() = client

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun request(
        device: DiscoveredDevice,
        deviceId: String,
        deviceName: String,
    ): PairRequestResult = withContext(Dispatchers.IO) {
        val url = endpoint(device, "request")
            ?: return@withContext PairRequestResult.Error("设备地址无效，请重新搜索")
        val payload = Json.encodeToString(
            PairRequestBody(deviceId = deviceId, deviceName = deviceName, appVersion = appVersion),
        )
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when (response.code) {
                    in 200..299 -> {
                        val ok = runCatching { Json.decodeFromString<PairRequestOkBody>(body) }.getOrNull()
                        if (ok == null || ok.requestId.isBlank()) {
                            PairRequestResult.Error("电脑的应答无法识别（缺少 requestId）")
                        } else {
                            PairRequestResult.Ok(ok.requestId, ok.expiresAt)
                        }
                    }

                    // 409 在 §5.3 里是通用的 conflict, 但在配对端点上只有一种含义: 窗口没开。
                    409 -> PairRequestResult.Closed
                    429 -> PairRequestResult.RateLimited
                    else -> PairRequestResult.Error(errorMessage(response.code, body))
                }
            }
        } catch (e: IOException) {
            PairRequestResult.Error("无法连接电脑（${networkReason(e)}）")
        }
    }

    override suspend fun status(
        device: DiscoveredDevice,
        requestId: String,
    ): PairStatusResult = withContext(Dispatchers.IO) {
        val url = endpoint(device, "status")
            ?.newBuilder()
            ?.addQueryParameter("requestId", requestId)
            ?.build()
            ?: return@withContext PairStatusResult.Error("设备地址无效，请重新搜索")
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> PairStatusResult.Error(
                        "电脑上已经没有这个配对请求了，请重新选择设备",
                    )

                    !response.isSuccessful -> PairStatusResult.Error(
                        errorMessage(response.code, body),
                    )

                    else -> parseStatus(body)
                }
            }
        } catch (e: IOException) {
            PairStatusResult.Error("无法连接电脑（${networkReason(e)}）")
        }
    }

    /** 200 的 `{status: ...}`。未知值一律 `Error` —— 不许猜成 pending 让用户白等。 */
    private fun parseStatus(body: String): PairStatusResult {
        val dto = runCatching { Json.decodeFromString<PairStatusBody>(body) }.getOrNull()
            ?: return PairStatusResult.Error("电脑的应答无法识别")
        return when (dto.status) {
            "pending" -> PairStatusResult.Pending
            "approved" -> {
                val url = dto.url?.trim()?.takeIf { it.isNotEmpty() }
                val token = dto.token?.trim()?.takeIf { it.isNotEmpty() }
                if (url == null || token == null) {
                    PairStatusResult.Error("电脑批准了配对，但没有给出地址或配对码")
                } else {
                    PairStatusResult.Approved(
                        Pairing(url = url.trimEnd('/'), token = token, note = dto.desktopName.orEmpty()),
                    )
                }
            }

            "denied" -> PairStatusResult.Denied
            "expired" -> PairStatusResult.Expired
            else -> PairStatusResult.Error("电脑返回了未知的配对状态（${dto.status}）")
        }
    }

    /**
     * `http://<host>:<port>/api/v1/pair/<path>`。
     *
     * IPv6 字面量必须加方括号, 否则冒号会被当成端口分隔符。`NsdServiceInfo.host.hostAddress`
     * 在真机上可能是 `fe80::...%wlan0` 这种带 scope 的形态 —— [toHttpUrlOrNull] 对它返回
     * `null`, 上层如实报"地址无效"(**未在真机验证**, 见计划 §8)。
     */
    private fun endpoint(device: DiscoveredDevice, path: String): HttpUrl? {
        val host = device.host.trim()
        if (host.isEmpty()) return null
        val encodedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$encodedHost:${device.port}/api/v1/pair/$path".toHttpUrlOrNull()
    }

    /** 服务端错误体沿用 `{code}`; 未知码只报"电脑返回了错误", 不把原始码当句子。 */
    private fun errorMessage(status: Int, body: String): String {
        val code = runCatching { Json.decodeFromString<ErrorBody>(body).code }.getOrNull()
        return when (status) {
            400 -> "配对请求无效，请确认两台设备的 ImageFilter 版本"
            404 -> "电脑上已经没有这个配对请求了，请重新选择设备"
            else -> if (code.isNullOrBlank()) {
                "电脑返回了错误（HTTP $status）"
            } else {
                "电脑返回了错误（$code）"
            }
        }
    }

    companion object {
        /** 真机路径: 从包信息读 appVersion。 */
        fun create(context: Context): HttpPairingApi = HttpPairingApi(appVersion = appVersionOf(context))

        /** 包名版本号; `BuildConfig` 在 AGP 8 默认不生成, 所以走 PackageManager。 */
        fun appVersionOf(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}

/** 配对端点专用的 client: **不带 Authorization**, 全部 5 秒。 */
private fun defaultPairingClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

/** "根本没有 HTTP 响应"时的中文原因。用户动作不同, 所以分开说。 */
private fun networkReason(e: IOException): String = when (e) {
    is SocketTimeoutException -> "超时"
    is ConnectException -> "连接被拒绝，请确认电脑上的服务已启动"
    is UnknownHostException -> "找不到电脑地址"
    else -> "网络错误"
}

// ── 线上 JSON 形状(计划 §0.2, serde camelCase) ───────────────────────────────

@Serializable
internal data class PairRequestBody(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
)

@Serializable
internal data class PairRequestOkBody(
    val requestId: String = "",
    val expiresAt: Long = 0L,
)

@Serializable
internal data class PairStatusBody(
    val status: String = "",
    val url: String? = null,
    val token: String? = null,
    val desktopName: String? = null,
)
