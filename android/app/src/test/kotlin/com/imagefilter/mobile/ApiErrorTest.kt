// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiError
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.ErrorBody
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.XmpErrorCodes
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两张**不同的**码表 + HTTP 状态兜底。
 *
 * 这里刻意把"HTTP 闭集"与"XMP 闭集"分开测: 混成一张的代价是客户端去处理永远收不到的码,
 * 同时漏掉真会收到的码(规格 §5.3 的 Task 6 评审结论)。
 */
class ApiErrorTest {

    @Test
    fun maps_every_closed_set_code_the_server_can_send() {
        // 这份清单与规格 §5.3 及 server/mod.rs 的 ApiError::code() 一一对应。
        // 少一个就会让手机端把可处理的错误当成未知错误。
        val expected = mapOf(
            401 to "unauthorized", 403 to "forbiddenPath", 404 to "notFound",
            409 to "conflict", 423 to "busy", 507 to "diskFull", 413 to "tooLarge",
            422 to "notXmp", 400 to "badRequest", 500 to "internal",
        )
        for ((status, code) in expected) {
            assertEquals("状态 $status 的兜底码", code, ApiError.fromHttp(status, null).code)
            assertEquals("httpStatus 必须原样保留", status, ApiError.fromHttp(status, null).httpStatus)
        }
    }

    @Test
    fun prefers_the_body_code_over_the_http_status() {
        // 服务端 422 既可能是 notXmp 也可能是 unsupportedForm —— 以 body 里的 code 为准
        assertEquals("unsupportedForm", ApiError.fromHttp(422, "unsupportedForm").code)
        assertEquals("notXmp", ApiError.fromHttp(422, "notXmp").code)
    }

    @Test
    fun unknown_status_becomes_internal_not_a_crash() {
        assertEquals("internal", ApiError.fromHttp(418, null).code)
        assertEquals("internal", ApiError.fromHttp(599, null).code)
    }

    @Test
    fun the_http_table_is_exactly_the_servers_closed_set() {
        assertEquals(
            setOf(
                "unauthorized", "forbiddenPath", "notFound", "conflict", "busy",
                "diskFull", "tooLarge", "notXmp", "unsupportedForm", "badRequest", "internal",
            ),
            ApiErrorCodes.SERVER_CODES,
        )
        assertEquals(11, ApiErrorCodes.SERVER_CODES.size)
    }

    @Test
    fun the_xmp_table_is_exactly_the_xmp_closed_set() {
        // xmp.rs::XmpError 的 11 档(规格 §5.3 的注释只列了 10 个, 漏了 unsupportedForm ——
        // 以 xmp.rs 的实现为准)
        assertEquals(
            setOf(
                "writeProtect", "readOnly", "permission", "diskFull", "busy",
                "notXmp", "unsupportedForm", "tooLarge", "encoding", "pathTooLong", "unknown",
            ),
            XmpErrorCodes.ALL,
        )
        assertEquals(11, XmpErrorCodes.ALL.size)
    }

    @Test
    fun the_two_tables_overlap_only_on_the_codes_both_sides_really_define() {
        // 规格 §5.3 的散文说"交集只有 notXmp / tooLarge / busy / diskFull 四个" —— 那句话
        // 漏了 **unsupportedForm**: `xmp.rs::XmpError` 有它(是"元素形式的边车"那一档),
        // `server/mod.rs::ApiError` 也有它("这个构建解不开这张图"), 所以真实交集是**五个**。
        // 以两边源码为准(测试第一次跑就红在这里, 值就是下面这五个)。
        assertEquals(
            setOf("notXmp", "tooLarge", "busy", "diskFull", "unsupportedForm"),
            ApiErrorCodes.ALL intersect XmpErrorCodes.ALL,
        )
        // 交集是"混起来也能跑"的那部分 —— 所以只有这几条可以容忍, 其余必须泾渭分明。
        // 两张表**都**认识 unsupportedForm, 但语义不同(HTTP: 解不开这张图; XMP: 属性写法不支持),
        // 文案 key 前缀不同正是为了让这两句话不会被混成一句。
        assertEquals("api.error.unsupportedForm", ApiErrorCodes.messageKey("unsupportedForm"))
        assertEquals("xmp.error.unsupportedForm", XmpErrorCodes.messageKey("unsupportedForm"))
    }

    @Test
    fun xmp_only_codes_are_invisible_to_the_http_table_and_vice_versa() {
        // 逐条钉死"混表会静默出错"的那几个: 它们在另一张表里根本不存在。
        for (code in listOf("readOnly", "writeProtect", "permission", "encoding", "pathTooLong")) {
            assertFalse("$code 是 XMP 码, 不该出现在 HTTP 表里", code in ApiErrorCodes.ALL)
            assertEquals("xmp.error.$code", XmpErrorCodes.messageKey(code))
            assertEquals(ApiErrorCodes.UNKNOWN_KEY, ApiErrorCodes.messageKey(code))
        }
        for (code in listOf("forbiddenPath", "notFound", "badRequest", "internal", "unauthorized")) {
            assertFalse("$code 是 HTTP 码, 不该出现在 XMP 表里", code in XmpErrorCodes.ALL)
            assertEquals("api.error.$code", ApiErrorCodes.messageKey(code))
            assertEquals(XmpErrorCodes.UNKNOWN_KEY, XmpErrorCodes.messageKey(code))
        }
    }

    @Test
    fun an_unknown_code_degrades_to_the_generic_key_instead_of_crashing_or_guessing() {
        val future = "someCodeFromAFutureServerBuild"
        // body 里的码原样保留(前向兼容: 老客户端不假装自己认识它)
        assertEquals(future, ApiError.fromHttp(422, future).code)
        assertFalse(future in ApiErrorCodes.ALL)
        assertFalse(future in XmpErrorCodes.ALL)
        // 但**本地化**必须落到一句通用人话, 而不是抛异常/显示空字符串
        assertEquals("api.error.unknown", ApiErrorCodes.messageKey(future))
        assertEquals("xmp.error.unknown", XmpErrorCodes.messageKey(future))
        assertEquals("api.error.unknown", ApiErrorCodes.messageKey(""))
    }

    @Test
    fun a_blank_or_broken_body_code_falls_back_to_the_status_table() {
        // 服务端在"请求没走到处理器"时可以回空体(405/404) —— 空码必须走状态兜底,
        // 否则用户看到的是一句空话。
        assertEquals("forbiddenPath", ApiError.fromHttp(403, "").code)
        assertEquals("forbiddenPath", ApiError.fromHttp(403, "   ").code)
        assertEquals("notFound", ApiError.fromHttp(404, null).code)
    }

    @Test
    fun an_error_body_with_exactly_one_field_round_trips() {
        // 规格 §5.3 的实测修正: 响应体**只有** `code`(没有 detail)。
        val decoded = Json.decodeFromString<ErrorBody>("""{"code":"notXmp"}""")
        assertEquals("notXmp", decoded.code)
        assertEquals("""{"code":"notXmp"}""", Json.encodeToString(ErrorBody("notXmp")))

        // 少了 code 的体(不该发生, 但要能兜住) → null, 由状态码兜底
        assertNull(Json.decodeFromString<ErrorBody>("""{"detail":"旧构建的写法"}""").code)
        assertNull(Json.decodeFromString<ErrorBody>("{}").code)
    }

    @Test
    fun an_api_exception_knows_whether_it_is_an_auth_failure() {
        // 调用方据此决定要不要走"复探已知受保护端点"那条消歧路 —— 判定只写在一处。
        assertTrue(ApiError.fromHttp(401, null).isUnauthorized)
        assertTrue(ApiError.fromHttp(401, "unauthorized").isUnauthorized)
        assertFalse(ApiError.fromHttp(403, "forbiddenPath").isUnauthorized)
        assertFalse(ApiError.fromHttp(500, null).isUnauthorized)
        // 手机端合成的码没有 HTTP 状态, 不能被误判成"收到的 401"
        assertFalse(ApiError.synthesized(ApiErrorCodes.SERVICE_OFF).isUnauthorized)
    }

    @Test
    fun the_phone_synthesized_codes_are_a_separate_closed_set() {
        // 服务端永远不会发这几个(服务没开时根本没有进程在监听), 所以它们与收到的码
        // 必须分开: 混在一起客户端就会去处理永远收不到的码。
        assertEquals(setOf("timeout", "network", "serviceOff"), ApiErrorCodes.CLIENT_SYNTHESIZED_CODES)
        assertTrue(ApiErrorCodes.SERVER_CODES.intersect(ApiErrorCodes.CLIENT_SYNTHESIZED_CODES).isEmpty())

        val off = ApiError.synthesized(ApiErrorCodes.SERVICE_OFF)
        assertEquals("serviceOff", off.code)
        assertEquals(ApiError.NO_HTTP_STATUS, off.httpStatus)

        // 合成一个服务端也会发的码 = 把"到底有没有 HTTP 响应"这件事变得不可查 → 直接拒
        assertThrows(IllegalArgumentException::class.java) {
            ApiError.synthesized(ApiErrorCodes.UNAUTHORIZED)
        }
    }
}
