// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.ViewerMessages
import com.imagefilter.mobile.ui.GridItem
import com.imagefilter.mobile.ui.ViewerMode
import com.imagefilter.mobile.ui.ViewerQuality
import com.imagefilter.mobile.ui.applyPan
import com.imagefilter.mobile.ui.applyZoom
import com.imagefilter.mobile.ui.clampScale
import com.imagefilter.mobile.ui.classifyImageFailure
import com.imagefilter.mobile.ui.dragStep
import com.imagefilter.mobile.ui.isViewingMode
import com.imagefilter.mobile.ui.nextIndex
import com.imagefilter.mobile.ui.rawQualityHint
import com.imagefilter.mobile.ui.resetView
import com.imagefilter.mobile.ui.toggleDoubleTap
import com.imagefilter.mobile.ui.viewerPhotos
import com.imagefilter.mobile.ui.viewerTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 查看器的**纯状态**: 手势规则(规格 §8.2)、档位、错误码分类。
 *
 * 计划里第一步就要求先有这个文件(TDD): 它的每一条都对应一种"看起来在动、其实错了"的
 * 真实故障 —— 缩到小于 1x 露出黑边、查看态还在切图、到末尾绕回第一张、
 * 把 HTTP 403 说成"网络断了"、RAW 用户以为默认档就是 RAW 的画质。
 *
 * 这些都不是界面观感问题, 所以它们必须能在 JVM 上被**真的断言**(本项目不引 Compose
 * UI 测试依赖: 手势的**判定**全在纯函数与状态机里, 界面只负责把事件喂进来)。
 */
class ViewerStateTest {

    private fun item(
        path: String,
        name: String,
        mtime: Long,
        isRaw: Boolean = false,
    ) = GridItem(
        path = path,
        fileName = name,
        isRaw = isRaw,
        isVideo = false,
        modifiedAt = mtime,
        // 查看器永远不用缩略图 URL(它要的是 /preview), 这里给一个占位值即可
        thumbnailUrl = "http://pc:8765/api/v1/thumb?path=$path&size=300&v=$mtime",
    )

    // ── 缩放与查看态 ────────────────────────────────────────────────────────

    @Test
    fun scale_is_clamped_to_a_sane_range() {
        assertEquals("不许缩到比原始更小(会露出黑边)", 1f, clampScale(0.1f))
        assertEquals("上界 8x: 预览只有 2048px, 再放大只是插值", 8f, clampScale(99f))
        assertEquals(2.5f, clampScale(2.5f))
    }

    @Test
    fun viewing_mode_starts_above_natural_scale() {
        // 判定"是否进入查看态"的**唯一**依据是缩放比例 —— 它决定单指手势是切图还是平移
        assertFalse(isViewingMode(ViewerMode(1f, 0f, 0f)))
        assertTrue(isViewingMode(ViewerMode(1.01f, 0f, 0f)))
        assertTrue(isViewingMode(ViewerMode(3f, 10f, -10f)))
    }

    @Test
    fun zooming_out_to_natural_scale_also_recenters() {
        // 缩回 1x 必须**贴边归位**: 少了这一条, 图会飘在屏幕外, 用户只能重启 App 找回
        val zoomedOut = applyZoom(ViewerMode(4f, 300f, -200f), 0.2f)
        assertEquals(1f, zoomedOut.scale)
        assertEquals(0f, zoomedOut.offsetX)
        assertEquals(0f, zoomedOut.offsetY)
    }

    @Test
    fun zooming_in_keeps_the_existing_offset_and_pan_is_ignored_at_natural_scale() {
        val zoomed = applyZoom(resetView(), 3f)
        assertEquals(3f, zoomed.scale)
        // 原始比例下的平移一定是 0(否则就是"贴边归位"那条不变量被破了)
        assertEquals(0f, applyPan(resetView(), 500f, 500f).offsetX)
        assertEquals(0f, applyPan(resetView(), 500f, 500f).offsetY)
        // 查看态里平移才真的生效
        assertEquals(120f, applyPan(zoomed, 120f, -40f).offsetX)
        assertEquals(-40f, applyPan(zoomed, 120f, -40f).offsetY)
    }

    @Test
    fun zoom_out_below_natural_scale_cannot_happen_at_all() {
        // 0.1x 的捏合不许把图变成"比适应屏幕还小"
        assertEquals(1f, applyZoom(resetView(), 0.1f).scale)
        assertEquals(1f, applyZoom(ViewerMode(2f, 10f, 10f), 0.01f).scale)
    }

    @Test
    fun double_tap_toggles_between_natural_scale_and_2x() {
        val zoomed = toggleDoubleTap(resetView())
        assertEquals(2f, zoomed.scale)
        assertEquals(0f, zoomed.offsetX)
        // 再双击一次回到原始比例(**并且归位**)
        assertEquals(resetView(), toggleDoubleTap(ViewerMode(2f, 88f, 99f)))
    }

    // ── 单指拖动: 决策态切图 ────────────────────────────────────────────────

    @Test
    fun a_horizontal_drag_switches_photo_only_after_the_threshold() {
        // 手指向左 = 把下一张从右边拉进来
        assertEquals(+1, dragStep(totalDx = -200f, thresholdPx = 160f))
        // 手指向右 = 上一张
        assertEquals(-1, dragStep(totalDx = 200f, thresholdPx = 160f))
        // 没到阈值就是"没动" —— 否则每一次手指抖动都会切图
        assertEquals(0, dragStep(totalDx = -159f, thresholdPx = 160f))
        assertEquals(0, dragStep(totalDx = 0f, thresholdPx = 160f))
        // 边界: 正好等于阈值就算切(与实现里的 <=/>= 一致)
        assertEquals(+1, dragStep(totalDx = -160f, thresholdPx = 160f))
        assertEquals(-1, dragStep(totalDx = 160f, thresholdPx = 160f))
        // 阈值为 0(不该发生)不许变成"每一次都切"
        assertEquals(0, dragStep(totalDx = -1000f, thresholdPx = 0f))
    }

    @Test
    fun index_navigation_is_clamped_at_both_ends() {
        // 到末尾再滑应该"停住", 不能绕回第一张(那会让人以为照片丢了)
        assertEquals(0, nextIndex(0, -1, 10))
        assertEquals(9, nextIndex(9, +1, 10))
        assertEquals(5, nextIndex(4, +1, 10))
        assertEquals(0, nextIndex(0, +1, 0))   // 空列表不该越界
    }

    // ── 照片与标题 ──────────────────────────────────────────────────────────

    @Test
    fun grid_items_become_viewer_photos_without_losing_path_or_mtime() {
        val photos = viewerPhotos(
            listOf(
                item("\\\\?\\D:\\照片 2026\\a.jpg", "a.jpg", 1_759_680_000_000L),
                item("\\\\?\\D:\\照片 2026\\b.arw", "b.arw", 7L, isRaw = true),
            ),
        )
        assertEquals(2, photos.size)
        // path 是 URL 上 path= 的来源, mtime 是 v= 的来源 —— 两个都不许在这一层被改写
        assertEquals("\\\\?\\D:\\照片 2026\\a.jpg", photos[0].path)
        assertEquals(1_759_680_000_000L, photos[0].modifiedAt)
        assertFalse(photos[0].isRaw)
        assertTrue("RAW 标记必须跟着照片走(画质提示靠它)", photos[1].isRaw)
        assertEquals("b.arw", photos[1].displayName)
    }

    @Test
    fun the_title_carries_the_file_name_and_the_position() {
        val photo = viewerPhotos(listOf(item("p\\a.jpg", "a.jpg", 1L))).single()
        assertEquals("a.jpg · (1/1)", viewerTitle(photo, 0, 1))
        assertEquals("b.jpg · (3/12)", viewerTitle(photo.copy(fileName = "b.jpg"), 2, 12))
        // fileName 为空时退回路径 —— 绝不显示一个空白标题
        assertEquals("p\\a.jpg", photo.copy(fileName = "").displayName)
    }

    // ── 画质上界必须明示(规格 §5.2) ────────────────────────────────────────

    @Test
    fun the_raw_default_step_says_the_quality_ceiling_out_loud() {
        val raw = viewerPhotos(listOf(item("p\\a.arw", "a.arw", 1L, isRaw = true))).single()
        val hint = rawQualityHint(ViewerQuality.DEFAULT, raw)
        assertTrue("RAW 的默认档必须说清它不是 RAW 的画质: $hint", hint != null)
        assertTrue("默认档的提示要指向那个**高清**入口: $hint", hint!!.contains("高清"))
        // 高清档自己那句话必须与默认档不同 —— 否则用户不知道自己现在看的是哪一档
        val high = rawQualityHint(ViewerQuality.HIGH_RES, raw)
        assertNotEquals(hint, high)
        assertTrue(
            "高清档要说明它优先用内嵌大预览、不够才全解码: $high",
            high!!.contains("内嵌") && high.contains("全解码"),
        )
    }

    @Test
    fun a_jpeg_default_step_gets_no_quality_warning_because_it_is_the_original_file() {
        // 非 RAW 的默认档拿到的就是原文件 —— 那时候说"画质有上界"是假话
        val jpeg = viewerPhotos(listOf(item("p\\a.jpg", "a.jpg", 1L))).single()
        assertNull(rawQualityHint(ViewerQuality.DEFAULT, jpeg))
        assertNull("没有照片时没有提示(界面根本不会打开)", rawQualityHint(ViewerQuality.DEFAULT, null))
    }

    // ── 失败分类: 403/404/401/超时 必须分开 ──────────────────────────────────

    /** Coil 的网络层对非 2xx 抛的就是它 —— 里面带着真正的 HTTP 状态码。 */
    private fun http(status: Int) =
        HttpException(NetworkResponse(code = status, headers = NetworkHeaders.EMPTY))

    @Test
    fun an_http_status_becomes_the_matching_server_code() {
        assertEquals(ApiErrorCodes.FORBIDDEN_PATH, classifyImageFailure(http(403)))
        assertEquals(ApiErrorCodes.NOT_FOUND, classifyImageFailure(http(404)))
        assertEquals(ApiErrorCodes.UNAUTHORIZED, classifyImageFailure(http(401)))
        assertEquals(ApiErrorCodes.BUSY, classifyImageFailure(http(423)))
        assertEquals(ApiErrorCodes.INTERNAL, classifyImageFailure(http(500)))
        // 服务端的 400: 请求本身不合法(例如 max 越界) —— 重试同一个请求必然再失败
        assertEquals(ApiErrorCodes.BAD_REQUEST, classifyImageFailure(http(400)))
    }

    @Test
    fun a_transport_failure_never_gets_an_http_code() {
        // 这三档是**手机端合成**的码(服务端永远不会发): 用户动作相同(查网络/开服务), 所以
        // 它们与 403 那种"电脑明确拒绝了"必须分开 —— 混起来用户会去修错的那一侧
        assertEquals(ApiErrorCodes.TIMEOUT, classifyImageFailure(SocketTimeoutException()))
        assertEquals(ApiErrorCodes.SERVICE_OFF, classifyImageFailure(ConnectException("refused")))
        assertEquals(ApiErrorCodes.SERVICE_OFF, classifyImageFailure(UnknownHostException("pc.local")))
        assertEquals(ApiErrorCodes.NETWORK, classifyImageFailure(IOException("unexpected end of stream")))
    }

    @Test
    fun a_failure_we_cannot_explain_is_not_dressed_up_as_a_server_code() {
        // Coil 连异常都没给 → 一个**刻意不放进任何服务端码表**的键
        val unclassified = classifyImageFailure(null)
        assertEquals(ViewerMessages.UNCLASSIFIED, unclassified)
        assertFalse(
            "说不清就往服务端码上归, 等于替服务端认领一个它没发的错",
            unclassified in ApiErrorCodes.ALL,
        )
        // 它落到兜底句上(有动作, 但不猜原因)
        assertEquals(ViewerMessages.GENERIC, ViewerMessages.forFailure(unclassified))
    }

    @Test
    fun a_decode_failure_uses_the_media_meaning_of_unsupported_form() {
        // 字节拿到了、图出不来(Coil 的解码器失败就是这样) → 规格 §5.3 的**媒体**语义
        assertEquals(ApiErrorCodes.UNSUPPORTED_FORM, classifyImageFailure(IllegalStateException("decode")))
        val text = ViewerMessages.forFailure(ApiErrorCodes.UNSUPPORTED_FORM)
        assertTrue("要说清是「解不开这张图」: $text", text.contains("解不开"))
        // 而**不是**边车那条"无法安全合并" —— 看图这条链路上根本没有边车
        for (word in listOf("边车", "XMP", "xmp", "合并")) {
            assertFalse("媒体语境下不许出现边车的说法('$word'): $text", text.contains(word))
        }
    }
}
