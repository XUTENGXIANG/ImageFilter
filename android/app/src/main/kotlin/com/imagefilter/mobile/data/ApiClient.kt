// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 缩略图档位的**闭集**(规格 §5.2 / `media.rs::THUMBNAIL_SIZES`)。 */
enum class ThumbnailSize(val pixels: Int) {
    S150(150),
    S300(300),
    S600(600),
    ;

    companion object {
        val DEFAULT = S300

        /** 三个档位本身就是"合法取值"的全部 —— 用它做校验, 而不是维护第二份清单。 */
        val PIXELS: Set<Int> = entries.map { it.pixels }.toSet()

        /**
         * 与 `media.rs::thumbnail_size` **同一条规则**: 向下吸附到最近的合法档位;
         * 比最小档还小则返回 `null`(服务端对此报 `badRequest`)。
         */
        fun snapDown(requested: Int): ThumbnailSize? =
            entries.filter { it.pixels <= requested }.maxByOrNull { it.pixels }
    }
}

/** 预览长边的**闭集区间**(规格 §5.2 / `media.rs::PREVIEW_MAX_MIN..PREVIEW_MAX_MAX`)。 */
const val PREVIEW_MAX_MIN = 64
const val PREVIEW_MAX_MAX = 5000

/** 规格 §5.2 的默认档。 */
const val PREVIEW_MAX_DEFAULT = 2048

/**
 * 一次 401 到底是**什么**意思。
 *
 * 401 有两种完全不同的成因, 而用户动作相反(一个要重新配对, 一个大可不管),
 * 所以**绝不允许**在第一次 401 上就断言"token 被轮换了"。
 */
enum class UnauthorizedCause {
    /** 用**同一枚 token** 复探一个已知受保护端点, 它也是 401 → token 真的失效了。 */
    TOKEN_ROTATED,

    /** 复探打通了(且没被判 401) → token 没问题, 是这个构建不暴露那个路径。 */
    ENDPOINT_NOT_EXPOSED,

    /** 复探根本没打通(不在同一局域网/电脑睡了) → 什么都断言不了, 别把网络故障说成 token 问题。 */
    UNDETERMINED,
}

/**
 * 桌面端图库服务的瘦客户端。
 *
 * 只做三件事: 拼 URL、发请求、把 HTTP 错误映射成 [ApiException]。
 * **不做缓存、不做重试** —— 缓存归 OkHttp/Coil, 重试归待重试队列(M3′)。
 *
 * 路径一律**原样**进查询串(服务端返回的规范路径可能带 verbatim 前缀 `\\?\`, 里面有一个
 * **字面问号**) —— 所以编码交给 OkHttp 的 `addQueryParameter` 做完整百分号编码,
 * **绝不**自己拼字符串、也绝不自己拼路径。
 */
class ApiClient(
    baseUrl: String,
    token: String,
    /**
     * ⚠️ 注入时**必须**用 [buildHttpClient] `(token)` —— 鉴权头装在那里面, 见它的 KDoc。
     * 默认值走 [SharedHttpClient.forToken]: 与 Coil(缩略图网格)拿到的是**同一个实例**,
     * 所以"JSON 通、图片 401"那种分裂在结构上就不成立。
     */
    private val client: OkHttpClient = SharedHttpClient.forToken(token),
) {
    private val root: HttpUrl =
        requireNotNull(baseUrl.trimEnd('/').toHttpUrlOrNull()) { "非法 baseUrl: $baseUrl" }

    /**
     * [ApiClient] **真正**用来发请求的那个 client。**测试可见**(`internal`)。
     *
     * 为什么必须能被断言, 而不是靠上面那句 KDoc: "Coil 与 ApiClient 共用同一个
     * `OkHttpClient`"这条不变量原先只钉住了**两头** ——
     *   ① 供给端: [SharedHttpClient.forToken] 会记忆(见 `SharedHttpClientTest`);
     *   ② 请求端: 默认构造出来的请求**带** Bearer 头(见 `ApiClientTest`)。
     * 中间那一步("默认的这个 client **就是**那一枚记忆实例")没有任何测试看得见 —— 实测把默认值
     * 从 `SharedHttpClient.forToken(token)` 换成 [buildHttpClient]`(token)` (同样是"带 Bearer 的
     * 新实例"), **169 条 Android 测试全绿**, 而生产上从此有两个连接池/线程池, 且 Coil 与 JSON
     * 接口拿着两个不同的客户端 —— 正是 Task 2 要消灭的那种分裂。所以把它变成机器可查的:
     * `SharedHttpClientTest.the_default_api_client_is_the_memoised_shared_client` 用 `assertSame`
     * 钉住身份(同一个对象, 不是"配置相同")。
     */
    internal val httpClient: OkHttpClient get() = client

    private val jsonMediaType = "application/json".toMediaType()

    /** 端点路径常量: 拼错端点在编译期看不出来, 所以每条只写一次。 */
    private object Endpoint {
        const val PING = "api/v1/ping"
        const val SHARES = "api/v1/shares"
        const val DRIVES = "api/v1/drives"
        const val BROWSE = "api/v1/browse"
        const val SCAN = "api/v1/scan"
        const val THUMB = "api/v1/thumb"
        const val PREVIEW = "api/v1/preview"
        const val DECISIONS = "api/v1/decisions"
        const val MOVE = "api/v1/move"
        const val SYNC_REQUEST = "api/v1/sync/request"
        const val SYNC_STATUS = "api/v1/sync/status"
    }

    private fun url(endpoint: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val b = root.newBuilder().addPathSegments(endpoint.trim('/'))
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    // ── JSON 通道 ────────────────────────────────────────────────────────────

    private suspend fun getJson(endpoint: String, query: List<Pair<String, String>> = emptyList()): String =
        executeForJson(Request.Builder().url(url(endpoint, query)).get().build())

    /**
     * 发请求并把失败统一压成 [ApiException]。
     *
     * 失败的码优先取响应体里的 `code`; 体不可解析(或空体)时由状态码兜底 ——
     * 服务端在"请求根本没走到处理器"时会回空体, 那时只能按状态码猜。
     */
    private suspend fun executeForJson(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFor(response.code, body)
            body
        }
    }

    private fun errorFor(status: Int, body: String): ApiException {
        val code = if (body.isBlank()) {
            null
        } else {
            runCatching { Json.decodeFromString<ErrorBody>(body).code }.getOrNull()
        }
        return ApiError.fromHttp(status, code)
    }

    // ── 端点 ────────────────────────────────────────────────────────────────

    /** 配对与"测试连接"。未配对也能调, 且不泄露任何路径。 */
    suspend fun ping(): PingDto = Json.decodeFromString(getJson(Endpoint.PING))

    /** 共享根列表 —— 手机只能在这些根里活动。 */
    suspend fun shares(): List<String> = Json.decodeFromString<SharesDto>(getJson(Endpoint.SHARES)).shares

    suspend fun drives(): List<DriveDto> = Json.decodeFromString(getJson(Endpoint.DRIVES))

    suspend fun browse(path: String): FolderEntryDto =
        Json.decodeFromString(getJson(Endpoint.BROWSE, listOf("path" to path)))

    suspend fun scan(path: String): List<ScannedPhotoDto> =
        Json.decodeFromString(getJson(Endpoint.SCAN, listOf("path" to path)))

    /**
     * 读边车(星级/色标)。`paths` 是**重复的同名参数**(`?paths=a&paths=b`), 不是逗号分隔。
     *
     * 空数组在服务端是 `badRequest`("你不知道自己要什么"), 所以本地先挡住 ——
     * 白跑一趟只会让用户多等一次超时。
     */
    suspend fun readDecisions(paths: List<String>): List<DecisionReadDto> {
        require(paths.isNotEmpty()) { "readDecisions: paths 不能为空(服务端对空数组报 badRequest)" }
        return Json.decodeFromString(getJson(Endpoint.DECISIONS, paths.map { "paths" to it }))
    }

    /**
     * 写边车。**写边车的永远是桌面端**, 手机只是发起者。
     *
     * 返回 [WriteSummaryDto] 而不是一句 ok/fail: 一个批次里"三张写成了、一张不是 XMP"
     * 必须如实报出来(逐项 `failures[].code` 是 **XMP 码集**, 见 [XmpErrorCodes])。
     */
    suspend fun writeDecisions(items: List<DecisionDto>): WriteSummaryDto {
        require(items.isNotEmpty()) { "writeDecisions: items 不能为空" }
        val body = Json.encodeToString(items).toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url(Endpoint.DECISIONS)).put(body).build()
        return Json.decodeFromString(executeForJson(request))
    }

    /**
     * 手机「同步」的确认门: 先请求, 电脑弹窗允许/拒绝, 再由调用方轮询 [syncStatus]。
     *
     * 这一步**不发任何决策写入**；真正推送仍然必须等 [syncStatus] 回 `approved`。
     * 202 Accepted 是正常成功响应（队列已排上），`executeForJson` 只认 2xx。
     */
    suspend fun requestSync(deviceName: String, pendingItems: Int): SyncChallengeDto {
        val body = Json.encodeToString(SyncRequestDto(deviceName, pendingItems))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url(Endpoint.SYNC_REQUEST)).post(body).build()
        return Json.decodeFromString(executeForJson(request))
    }

    /** 轮询电脑对同步请求的结论；`status` 四态闭集见 [SyncStatusDto]。 */
    suspend fun syncStatus(requestId: String): SyncStatusDto =
        Json.decodeFromString(getJson(Endpoint.SYNC_STATUS, listOf("requestId" to requestId)))

    /**
     * 移动照片 + 同名边车。返回逐项结果 —— **半成功态必须显式**, 不许被压成一个布尔值。
     *
     * 空批次同样是 `badRequest`(与 [readDecisions] 同一条), 本地先挡。
     */
    suspend fun move(paths: List<String>, destDir: String): MoveSummaryDto {
        require(paths.isNotEmpty()) { "move: paths 不能为空(服务端对空批次报 badRequest)" }
        val payload = Json.encodeToString(MoveReqDto(paths = paths, destDir = destDir))
        val request = Request.Builder()
            .url(url(Endpoint.MOVE))
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        return Json.decodeFromString(executeForJson(request))
    }

    // ── 图片 URL(交给 Coil 加载, 不走上面的 JSON 通道) ─────────────────────

    /**
     * 缩略图 URL。档位是**闭集**, 所以非法值在类型上就构造不出来。
     *
     * `v`(mtime)查询参数**服务端目前不读它** —— 它的作用是让 Coil 的缓存键随文件变化,
     * 是一个纯客户端约定。
     */
    fun thumbnailUrl(path: String, size: ThumbnailSize = ThumbnailSize.DEFAULT, mtime: Long): String =
        url(
            Endpoint.THUMB,
            listOf("path" to path, "size" to size.pixels.toString(), "v" to mtime.toString()),
        ).toString()

    /**
     * 从**任意整数**构造缩略图 URL: 与 `media.rs::thumbnail_size` 一样向下吸附,
     * 比最小档(150)还小则**本地**就抛错 —— 那个请求服务端一定回 `badRequest`,
     * 没必要白跑一趟(设置项里的任意整数走这一条)。
     */
    fun thumbnailUrl(path: String, requestedSize: Int, mtime: Long): String {
        val size = requireNotNull(ThumbnailSize.snapDown(requestedSize)) {
            "缩略图 size=$requestedSize 越界: 服务端只接受 ${ThumbnailSize.PIXELS.sorted()}" +
                "(向下吸附, 比最小档还小报 badRequest)"
        }
        return thumbnailUrl(path, size, mtime)
    }

    /**
     * 预览 URL。
     *
     * `max` 必须落在 [PREVIEW_MAX_MIN]..[PREVIEW_MAX_MAX] —— 越界是**本地的非法参数错误**
     * (不是 [ApiException]): 服务端对越界 `max` 报 `badRequest`(**不再静默钳制**),
     * 所以这里先抛 `IllegalArgumentException`, 请求根本不会发出去。
     *
     * `full = true` 时**必须发 `full=true`**: 服务端那个字段是 Rust 的 `bool`, 而
     * `serde_urlencoded` 解析 bool 走的是 `str::parse::<bool>()` —— **只认 "true"/"false"**,
     * `full=1` / `full=0`(规格 §5.1 表格里的示例写法)会被判成类型错误 → 400 `badRequest`。
     * 不请求全图时整个参数**不出现**(服务端 `#[serde(default)]` = false)。
     */
    fun previewUrl(
        path: String,
        max: Int = PREVIEW_MAX_DEFAULT,
        mtime: Long,
        full: Boolean = false,
    ): String {
        require(max in PREVIEW_MAX_MIN..PREVIEW_MAX_MAX) {
            "preview max=$max 越界: 服务端只接受 [$PREVIEW_MAX_MIN, $PREVIEW_MAX_MAX], 越界是 badRequest"
        }
        val query = mutableListOf(
            "path" to path,
            "max" to max.toString(),
            "v" to mtime.toString(),
        )
        if (full) query.add("full" to "true")
        return url(Endpoint.PREVIEW, query).toString()
    }

    // ── 401 的消歧 ───────────────────────────────────────────────────────────

    /**
     * 401 的**消歧** —— 第一次 401 绝不当成"token 被轮换"。
     *
     * 规则(逐字照计划): 用**同一枚 token** 复探一个**已知受保护**的端点
     * ([KNOWN_PROTECTED_PROBE], 即 §5.1 的 `shares` —— 服务端一定挂载它, 而且它要求 token)。
     *   · 复探也是 401 → 真的是 token 失效([UnauthorizedCause.TOKEN_ROTATED]);
     *   · 复探打通了(哪怕回 500) → token 没问题, 是这个桌面端构建**不暴露那个路径**
     *     ([UnauthorizedCause.ENDPOINT_NOT_EXPOSED])。鉴权层罩住整张路由表, 所以
     *     "打到了处理器"本身就证明请求带着一枚被接受的 token;
     *   · 复探**根本没打通** → [UnauthorizedCause.UNDETERMINED]。把网络故障说成
     *     "去重新配对"会让用户为一个网络问题去扫码轮换。
     *
     * 结果里带三个值而不是两个, 是因为"探不动"和"探通了"是两个不同的事实:
     * 只有前者不允许下任何结论。
     */
    suspend fun classifyUnauthorized(): UnauthorizedCause = withContext(Dispatchers.IO) {
        val probe = Request.Builder().url(url(KNOWN_PROTECTED_PROBE)).get().build()
        try {
            client.newCall(probe).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val httpCode = if (response.isSuccessful) null else errorFor(response.code, body).code
                if (httpCode == ApiErrorCodes.UNAUTHORIZED) {
                    UnauthorizedCause.TOKEN_ROTATED
                } else {
                    UnauthorizedCause.ENDPOINT_NOT_EXPOSED
                }
            }
        } catch (_: IOException) {
            UnauthorizedCause.UNDETERMINED
        }
    }

    companion object {
        /**
         * 复探用的**已知受保护**端点。
         *
         * 为什么是 `shares`: 它由规格 §5.1 保证存在(不是可选端点), 要求 token, 而且
         * 不带路径参数 —— 复探不会因为路径白名单/存在性多出一个失败维度。
         */
        const val KNOWN_PROTECTED_PROBE = "api/v1/shares"
    }
}
