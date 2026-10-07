// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.DriveDto
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.MoveOutcomeDto
import com.imagefilter.mobile.data.MoveStatus
import com.imagefilter.mobile.data.MoveSummaryDto
import com.imagefilter.mobile.data.ScannedPhotoDto
import com.imagefilter.mobile.data.WriteSummaryDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO 的**逐字节契约**: 下面每一段 JSON 都是从服务端源码里逐字抄来的线上形状,
 * 不是"我觉得应该长这样"。
 */
class DtoTest {

    @Test
    fun parses_scanned_photo_exactly_as_the_desktop_sends_it() {
        // 这段 JSON 逐字抄自桌面端 scanner::ScannedPhoto 的序列化结果(serde rename_all = "camelCase")
        val json = """
        {
          "path": "D:\\照片\\a.jpg",
          "fileName": "a.jpg",
          "fileSize": 123456,
          "isRaw": false,
          "isVideo": false,
          "modifiedAt": 1759680000000,
          "exif": { "fileSize": 123456, "cameraModel": "ILCE-7M4", "iso": 400 }
        }
        """.trimIndent()

        val p = Json.decodeFromString<ScannedPhotoDto>(json)
        assertEquals("a.jpg", p.fileName)
        assertEquals("D:\\照片\\a.jpg", p.path)
        assertEquals(1759680000000L, p.modifiedAt)
        assertFalse(p.isRaw)
        assertEquals("ILCE-7M4", p.exif.cameraModel)
        assertEquals(400, p.exif.iso)
        assertNull(p.exif.lensModel)
    }

    @Test
    fun tolerates_unknown_fields_and_missing_optional_exif() {
        // 服务端以后加字段不该让老客户端崩 —— ignoreUnknownKeys 是刻意的
        val json = """{"path":"p","fileName":"f","fileSize":1,"isRaw":true,"isVideo":false,"modifiedAt":0,"exif":{"fileSize":1},"futureField":42}"""
        val p = Json.decodeFromString<ScannedPhotoDto>(json)
        assertEquals("f", p.fileName)
        assertTrue(p.isRaw)
        assertNull(p.exif.iso)
    }

    @Test
    fun decodes_a_move_item_with_exactly_the_five_fields_the_server_sends() {
        // 逐字抄自 src-tauri/src/mover.rs 的线上形状: src / dest / status / detail / pendingSidecars
        val json = """
        {
          "src": "D:\\p\\a.jpg",
          "dest": "D:\\p\\dst\\a.jpg",
          "status": "halfSuccess",
          "detail": "照片已移动, 但边车 a.xmp 没能跟着走, 需要重试",
          "pendingSidecars": ["D:\\p\\a.xmp", "D:\\p\\a.jpg.xmp"]
        }
        """.trimIndent()

        // 夹具本身先钉一遍: 服务端发的是**恰好五个**字段(多一个少一个都是契约漂移)
        assertEquals(
            setOf("src", "dest", "status", "detail", "pendingSidecars"),
            Json.parseToJsonElement(json).jsonObject.keys,
        )

        val item = Json.decodeFromString<MoveOutcomeDto>(json)
        assertEquals("D:\\p\\a.jpg", item.src)
        assertEquals("D:\\p\\dst\\a.jpg", item.dest)
        assertEquals(MoveStatus.HALF_SUCCESS, item.moveStatus)
        assertEquals("照片已移动, 但边车 a.xmp 没能跟着走, 需要重试", item.detail)
        assertEquals(listOf("D:\\p\\a.xmp", "D:\\p\\a.jpg.xmp"), item.pendingSidecars)
    }

    @Test
    fun the_phone_encodes_a_move_item_with_the_same_five_field_names() {
        // 反方向: 手机自己序列化时也必须长成那五个 camelCase 字段 —— 少一个字段名
        // (例如把 pendingSidecars 写成 snake_case)在 Kotlin 侧编译得过, 却会让服务端读不到。
        val encoded = Json.encodeToString(
            MoveOutcomeDto(
                src = "D:\\p\\a.jpg",
                dest = "D:\\p\\dst\\a.jpg",
                status = "halfSuccess",
                detail = "d",
                pendingSidecars = listOf("D:\\p\\a.xmp"),
            ),
        )
        assertEquals(
            setOf("src", "dest", "status", "detail", "pendingSidecars"),
            Json.parseToJsonElement(encoded).jsonObject.keys,
        )
    }

    @Test
    fun a_half_success_item_retries_with_pending_sidecars_as_paths_and_the_parent_of_dest() {
        // 规格 §5.2 的重试通道: POST /api/v1/move { paths: item.pendingSidecars, destDir: parent(item.dest) }
        val json = """
        {
          "src": "D:\\p\\a.jpg",
          "dest": "D:\\p\\dst\\a.jpg",
          "status": "halfSuccess",
          "detail": "…",
          "pendingSidecars": ["D:\\p\\a.xmp", "D:\\p\\a.jpg.xmp"]
        }
        """.trimIndent()
        val items = Json.decodeFromString<MoveSummaryDto>("""{"moved":0,"halfSuccess":1,"conflict":0,"failed":0,"items":[$json]}""").items

        val retry = items.single().pendingSidecarRetry()
        assertNotNull("半成功项必须能构造出重试请求", retry)
        val req = requireNotNull(retry)
        assertEquals(listOf("D:\\p\\a.xmp", "D:\\p\\a.jpg.xmp"), req.paths)
        assertEquals("D:\\p\\dst", req.destDir)
        // 半成功项的**照片已经搬走了**: 把 src 再发一遍会让整批 404, 所以它绝不许出现在重试里
        assertFalse("重试请求不许带已搬走的 src: ${req.paths}", req.paths.contains("D:\\p\\a.jpg"))
        // 手机**不推导**边车命名规则: 重试的 paths 逐字等于服务端给的 pendingSidecars
        assertEquals(items.single().pendingSidecars, req.paths)
    }

    @Test
    fun the_retry_body_uses_the_camel_case_dest_dir_the_server_deserializes() {
        val retry = MoveOutcomeDto(
            src = "D:\\p\\a.jpg",
            dest = "D:\\p\\dst\\a.jpg",
            status = "halfSuccess",
            pendingSidecars = listOf("D:\\p\\a.xmp"),
        ).pendingSidecarRetry()

        assertEquals(
            """{"paths":["D:\\p\\a.xmp"],"destDir":"D:\\p\\dst"}""",
            Json.encodeToString(retry!!),
        )
    }

    @Test
    fun a_verbatim_prefixed_dest_still_yields_the_verbatim_parent_directory() {
        // Windows 的 canonicalize 会把共享根下的真实路径变成 \\?\D:\... —— 那个前缀里有一个
        // **字面问号**。凡是要把它放进查询串的地方都必须完整百分号编码(见 ApiClientTest)。
        val item = MoveOutcomeDto(
            src = "D:\\p\\a.jpg",
            dest = "\\\\?\\D:\\p\\dst\\a.jpg",
            status = "halfSuccess",
            pendingSidecars = listOf("\\\\?\\D:\\p\\a.xmp"),
        )
        assertEquals("\\\\?\\D:\\p\\dst", item.pendingSidecarRetry()!!.destDir)    }

    @Test
    fun a_dest_directly_in_the_volume_root_keeps_the_root_separator() {
        // `D:\a.jpg` 的父目录是 `D:\`(不是 `D:` —— 少了这个反斜杠, 服务端会把它当成
        // "当前目录下的 D:" 这种东西, 白名单直接挡下)
        val item = MoveOutcomeDto(
            dest = "D:\\a.jpg",
            status = "halfSuccess",
            pendingSidecars = listOf("D:\\a.xmp"),
        )
        assertEquals("D:\\", item.pendingSidecarRetry()!!.destDir)

        val verbatim = MoveOutcomeDto(
            dest = "\\\\?\\D:\\a.jpg",
            status = "halfSuccess",
            pendingSidecars = listOf("\\\\?\\D:\\a.xmp"),
        )
        assertEquals("\\\\?\\D:\\", verbatim.pendingSidecarRetry()!!.destDir)
    }

    @Test
    fun pending_sidecars_null_degrades_to_an_empty_list_instead_of_crashing() {
        // 服务端契约说它**永远是数组**, 而 mover.rs 的注释明写"手机端拿到 null 会当场崩在
        // for (p in it.pendingSidecars) 上"。这一条钉住那层兜底真的在。
        val json = """{"src":"D:\\p\\a.jpg","dest":"D:\\p\\dst\\a.jpg","status":"moved","detail":null,"pendingSidecars":null}"""
        val item = Json.decodeFromString<MoveOutcomeDto>(json)
        assertTrue(item.pendingSidecars.isEmpty())
        assertNull(item.pendingSidecarRetry())
    }

    @Test
    fun an_unknown_move_status_stays_unknown_and_is_never_mapped_to_a_real_outcome() {
        // mover.rs 的实测担心: 把状态的线上拼写改掉, 手机**不崩溃** —— 但也绝不该把它
        // 悄悄算成 moved/failed 中的某一档, 那会让"照片到底动了没有"变成一句假话。
        val json = """{"src":"D:\\p\\a.jpg","dest":null,"status":"renamed","detail":null,"pendingSidecars":[]}"""
        val item = Json.decodeFromString<MoveOutcomeDto>(json)
        assertEquals("renamed", item.status)
        assertNull(item.moveStatus)
        assertNull(item.pendingSidecarRetry())
    }

    @Test
    fun retry_is_only_offered_for_a_half_success_that_really_has_pending_sidecars() {
        val base = MoveOutcomeDto(dest = "D:\\p\\dst\\a.jpg", status = "halfSuccess", pendingSidecars = listOf("D:\\p\\a.xmp"))
        assertNotNull(base.pendingSidecarRetry())

        assertNull("moved 项不该有重试", base.copy(status = "moved").pendingSidecarRetry())
        assertNull("conflict 项不该有重试", base.copy(status = "conflict").pendingSidecarRetry())
        assertNull("failed 项不该有重试", base.copy(status = "failed").pendingSidecarRetry())
        assertNull(
            "服务端的不变量是 halfSuccess ⇔ pendingSidecars 非空; 空的重试请求会被服务端按空批次挡下",
            base.copy(pendingSidecars = emptyList()).pendingSidecarRetry(),
        )
        assertNull("没有 dest 就取不到目标目录", base.copy(dest = null).pendingSidecarRetry())
    }

    @Test
    fun decodes_a_write_summary_whose_failure_carries_an_xmp_code() {
        // 逐字抄自 xmp.rs::WriteSummary / WriteFailure 的线上形状
        val json = """
        {
          "written": 1,
          "skipped": 0,
          "failed": 2,
          "failures": [
            { "path": "D:\\p\\b.jpg", "code": "readOnly", "detail": "文件/目录带只读属性" },
            { "path": "D:\\p\\c.jpg", "code": "pathTooLong", "detail": "路径过长" }
          ],
          "degraded": true
        }
        """.trimIndent()

        val s = Json.decodeFromString<WriteSummaryDto>(json)
        assertEquals(1, s.written)
        assertEquals(0, s.skipped)
        assertEquals(2, s.failed)
        assertTrue(s.degraded)
        assertEquals(2, s.failures.size)
        // 逐项失败的码是 **XMP 码集**: readOnly / pathTooLong 在 §5.3 的 HTTP 表里根本不存在
        assertEquals("readOnly", s.failures[0].code)
        assertEquals("D:\\p\\b.jpg", s.failures[0].path)
        assertEquals("pathTooLong", s.failures[1].code)
    }

    @Test
    fun decodes_decisions_including_the_sidecar_path_the_server_reports() {
        val json = """[{"path":"D:\\p\\a.jpg","rating":3,"label":"red","sidecar":"D:\\p\\a.xmp"},{"path":"D:\\p\\b.jpg","rating":null,"label":null,"sidecar":null}]"""
        val out = Json.decodeFromString<List<DecisionReadDto>>(json)
        assertEquals(3, out[0].rating)
        assertEquals("red", out[0].label)
        assertEquals("D:\\p\\a.xmp", out[0].sidecar)
        assertNull(out[1].rating)
        assertNull(out[1].sidecar)
    }

    @Test
    fun folder_and_drive_shapes_match_the_server_field_names() {
        val folder = Json.decodeFromString<FolderEntryDto>(
            """{"path":"D:\\照片","name":"照片","photoCount":12,"hasSubdirs":true,"subfolders":[{"path":"D:\\照片\\子","name":"子","photoCount":0,"hasSubdirs":false,"subfolders":[]}]}""",
        )
        assertEquals("照片", folder.name)
        assertEquals(12, folder.photoCount)
        assertTrue(folder.hasSubdirs)
        assertEquals("D:\\照片\\子", folder.subfolders.single().path)

        val drives = Json.decodeFromString<List<DriveDto>>(
            """[{"mountPoint":"D:\\","driveType":"fixed","label":"CANON_DC","available":true}]""",
        )
        assertEquals("D:\\", drives.single().mountPoint)
        assertEquals("CANON_DC", drives.single().label)
        assertTrue(drives.single().available)
    }
}
