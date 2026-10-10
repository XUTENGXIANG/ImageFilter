// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.serialization.Serializable

/**
 * 服务端错误体 —— **恰好一个字段**(规格 §5.3 的实测修正)。
 *
 * 曾经设计过 `{ code, detail }`, 但实现只发 `code`(`server/mod.rs` 的 `IntoResponse`
 * 实测只序列化一个字段), 而方向也定在只发 `code`: 把中文文案塞进 HTTP 响应等于把文案
 * 硬编码进 Rust, 与"文案在前端、按 key 本地化"的纪律相反。
 *
 * 所以这个类型只有一个可空字段, 且**没有** `detail`。多出来的键由
 * [Json] 的 `ignoreUnknownKeys` 吃掉, 老客户端不会因此崩。
 */
@Serializable
internal data class ErrorBody(val code: String? = null)

/**
 * HTTP 层失败。
 *
 * [code] 是**本地化用的 key**(不是给人看的句子); [httpStatus] 保留原始状态码,
 * 便于日志与"这个码是从 body 来的还是从状态码兜底来的"这类排查。
 */
class ApiException(val code: String, val httpStatus: Int, message: String) : Exception(message) {
    /**
     * 是不是"鉴权没过"。
     *
     * 调用方据此决定要不要走 [ApiClient.classifyUnauthorized] —— **别自己判 `httpStatus == 401`**:
     * 服务端在 401 上回的 body code 是 `unauthorized`, 而手机端合成的码也可能落在这一档语义上,
     * 判定只写在这里才不会两处漂移。
     */
    val isUnauthorized: Boolean
        get() = httpStatus == 401 || code == ApiErrorCodes.UNAUTHORIZED

    override fun toString(): String = "ApiException(code=$code, httpStatus=$httpStatus)"
}

/**
 * 闭集错误码(规格 §5.3)。
 *
 * 这张表是**服务端会返回的码**。手机端**合成**的码(服务没开/超时/网络)刻意分成
 * [CLIENT_SYNTHESIZED_CODES] 单独一组 —— 照着混在一起的表实现, 客户端会去处理
 * **永远收不到**的码, 却漏掉真会收到的 `badRequest` / `internal`(Task 3 独立评审发现)。
 */
object ApiErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN_PATH = "forbiddenPath"
    const val NOT_FOUND = "notFound"
    const val CONFLICT = "conflict"
    const val BUSY = "busy"
    const val DISK_FULL = "diskFull"
    const val TOO_LARGE = "tooLarge"
    const val NOT_XMP = "notXmp"
    const val UNSUPPORTED_FORM = "unsupportedForm"
    const val BAD_REQUEST = "badRequest"
    const val INTERNAL = "internal"

    /** 手机端在"根本没有 HTTP 响应"时合成的码 —— 服务端**永远不会**发这几个。 */
    const val TIMEOUT = "timeout"
    const val NETWORK = "network"
    const val SERVICE_OFF = "serviceOff"

    /** 服务端会返回的码, 与 `server/mod.rs::ApiError::code()` 一一对应(11 个)。 */
    val SERVER_CODES: Set<String> = setOf(
        UNAUTHORIZED, FORBIDDEN_PATH, NOT_FOUND, CONFLICT, BUSY,
        DISK_FULL, TOO_LARGE, NOT_XMP, UNSUPPORTED_FORM, BAD_REQUEST, INTERNAL,
    )

    /** 手机端自己合成的码(规格 §5.3 第二张表)。 */
    val CLIENT_SYNTHESIZED_CODES: Set<String> = setOf(TIMEOUT, NETWORK, SERVICE_OFF)

    val ALL: Set<String> = SERVER_CODES + CLIENT_SYNTHESIZED_CODES

    /** 认不出来的码一律落到这个 key —— **宁可说"出错了"也不要崩, 也不要瞎猜具体原因**。 */
    const val UNKNOWN_KEY = "api.error.unknown"

    /**
     * 本地化 key。已知码 → `api.error.<code>`; 未知码 → [UNKNOWN_KEY]。
     *
     * 未知码**不是异常路径**: 服务端将来加一档码, 老客户端必须还能给出一句人话。
     */
    fun messageKey(code: String): String = if (code in ALL) "api.error.$code" else UNKNOWN_KEY
}

/**
 * XMP 的闭集错误码(`xmp.rs::XmpError::code()`)。
 *
 * ⚠️ **这张表与 [ApiErrorCodes] 是两回事, 绝不许混**:
 *   · §5.3 的 HTTP `{code}` 体查 [ApiErrorCodes];
 *   · `WriteSummary.failures[].code` 查**这一张**(`readOnly` / `writeProtect` /
 *     `permission` / `encoding` / `pathTooLong` 在 HTTP 表里**根本不存在**)。
 * 两张表的交集只有 `notXmp` / `tooLarge` / `busy` / `diskFull` 四个 —— 交集就是
 * "混起来也能跑"的那部分, 所以两边的文案 key 前缀刻意不同。
 */
object XmpErrorCodes {
    const val WRITE_PROTECT = "writeProtect"
    const val READ_ONLY = "readOnly"
    const val PERMISSION = "permission"
    const val DISK_FULL = "diskFull"
    const val BUSY = "busy"
    const val NOT_XMP = "notXmp"
    const val UNSUPPORTED_FORM = "unsupportedForm"
    const val TOO_LARGE = "tooLarge"
    const val ENCODING = "encoding"
    const val PATH_TOO_LONG = "pathTooLong"
    const val UNKNOWN = "unknown"

    /** `xmp.rs::XmpError` 的全部 11 档。 */
    val ALL: Set<String> = setOf(
        WRITE_PROTECT, READ_ONLY, PERMISSION, DISK_FULL, BUSY,
        NOT_XMP, UNSUPPORTED_FORM, TOO_LARGE, ENCODING, PATH_TOO_LONG, UNKNOWN,
    )

    const val UNKNOWN_KEY = "xmp.error.unknown"

    /** 本地化 key。已知码 → `xmp.error.<code>`; 未知码 → [UNKNOWN_KEY]。 */
    fun messageKey(code: String): String = if (code in ALL) "xmp.error.$code" else UNKNOWN_KEY
}

/**
 * HTTP 状态码 → [ApiException]。
 *
 * 两条规则, 顺序不能反:
 *   1. **响应体里的 `code` 更精确**: 422 既可能是 `notXmp` 也可能是 `unsupportedForm`,
 *      光看状态码推不出来 —— 所以 body 里的码优先;
 *   2. 没有可用的 body 码时才按状态码兜底(这张兜底表与规格 §5.3 的状态列一一对应)。
 *
 * 兜底是**刻意的**: 未列出的状态一律 `internal`, 宁可说"出错了"也不要崩。
 */
object ApiError {
    /** 没有经过 HTTP 的合成码用这个状态(不是合法的 HTTP 状态码)。 */
    const val NO_HTTP_STATUS = 0

    fun fromHttp(status: Int, bodyCode: String?): ApiException {
        // 空字符串与 null 同义: 服务端在 405/404 这类"没走到处理器"的响应上可能给空体,
        // 把 `""` 当成一个码会让用户看到一句空话。
        val code = bodyCode?.takeIf { it.isNotBlank() } ?: codeForStatus(status)
        return ApiException(code, status, "HTTP $status ($code)")
    }

    /** 状态码兜底表(规格 §5.3 的 HTTP 列)。 */
    fun codeForStatus(status: Int): String = when (status) {
        401 -> ApiErrorCodes.UNAUTHORIZED
        403 -> ApiErrorCodes.FORBIDDEN_PATH
        404 -> ApiErrorCodes.NOT_FOUND
        409 -> ApiErrorCodes.CONFLICT
        423 -> ApiErrorCodes.BUSY
        507 -> ApiErrorCodes.DISK_FULL
        413 -> ApiErrorCodes.TOO_LARGE
        422 -> ApiErrorCodes.NOT_XMP
        400 -> ApiErrorCodes.BAD_REQUEST
        else -> ApiErrorCodes.INTERNAL
    }

    /**
     * 手机端**自己合成**的错误(连接被拒、超时、网络断了)。
     *
     * `require` 是这条边界的关键: 合成一个服务端也会发的码(例如 `unauthorized`)会让
     * "这个错误到底有没有 HTTP 响应"永远查不清, 而两者的用户动作完全不同
     * (重新配对 vs 去电脑上开服务)。
     */
    fun synthesized(code: String): ApiException {
        require(code in ApiErrorCodes.CLIENT_SYNTHESIZED_CODES) {
            "只能合成手机端的码(${ApiErrorCodes.CLIENT_SYNTHESIZED_CODES}), 收到的是 $code"
        }
        return ApiException(code, NO_HTTP_STATUS, code)
    }
}
