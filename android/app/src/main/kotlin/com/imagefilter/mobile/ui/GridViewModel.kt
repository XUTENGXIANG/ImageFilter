// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiError
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.ScannedPhotoDto
import com.imagefilter.mobile.data.ThumbnailSize
import com.imagefilter.mobile.data.apiCodeFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

// ── 纯函数: 格子的推导(与 Compose 无关, 每个都有 JVM 单测) ─────────────────────
//
// 与 BrowseViewModel 同一条纪律: 安卓 UI 测试在本项目里没有依赖(也不该为 M1′ 引入),
// 所以"哪些格子、每格画哪个 URL、顶部写几个字、失败说什么"必须是**可以真的断言**的
// 纯函数, 而不是散在 Composable 里的一堆 when。

/**
 * 网格里的一格。
 *
 * **URL 在构造时就定下来**: 它由 [ApiClient.thumbnailUrl] 拼(路径的百分号编码、
 * `size` 闭集、`v=<mtime>` 三件事都在那一处, 见 Task 2 的单测) —— 格子里不许自己
 * 拼字符串, 否则 verbatim 前缀里的字面问号会在这里被弄丢。
 */
data class GridItem(
    /** 规范路径(不透明, 逐字来自服务端; 进 URL 与以后进查看器都用它)。 */
    val path: String,
    val fileName: String,
    val isRaw: Boolean,
    val isVideo: Boolean,
    /** 文件在电脑上的修改时间 —— 它就是 URL 上 `v=` 的来源(Coil 的缓存失效键)。 */
    val modifiedAt: Long,
    val thumbnailUrl: String,
) {
    /** 显示名: 服务端给了 `fileName` 就用它, 空则退回路径(绝不显示空白格子)。 */
    val displayName: String get() = fileName.ifBlank { path }
}

/** 网格请求的档位 —— 规格 §5.2 的默认档。**只有这一处**决定它。 */
val GRID_THUMBNAIL_SIZE: ThumbnailSize = ThumbnailSize.S300

/**
 * `scan` 的结果 → 格子列表。**顺序逐字保留服务端的顺序**(`scan_directory` 按文件名
 * 不区分大小写排过序), 手机端不重排 —— 用户看到的顺序必须与电脑上一致且稳定。
 *
 * 这里刻意**不过滤** RAW / 视频: `scan` 返回什么就能看什么。过滤是 M2′ 筛片纯函数的事,
 * M1′ 在客户端再筛一遍只会让"看到的东西"与"电脑上有的东西"对不上。
 */
fun gridItems(api: ApiClient, photos: List<ScannedPhotoDto>): List<GridItem> = photos.map { p ->
    GridItem(
        path = p.path,
        fileName = p.fileName,
        isRaw = p.isRaw,
        isVideo = p.isVideo,
        modifiedAt = p.modifiedAt,
        thumbnailUrl = api.thumbnailUrl(p.path, GRID_THUMBNAIL_SIZE, p.modifiedAt),
    )
}

/**
 * 顶部张数。
 *
 * ⚠️ **不要复用浏览页的 [photoCountLabel]**: 那个的 0 显示成"张数未统计", 因为
 * `browse` 的 `photoCount` 在这一层本来就没有权威值。而网格的张数来自 `scan` 的
 * **返回长度** —— 这里的 0 就是真的 0 张, 说成"未统计"才是撒谎。
 */
fun gridCountLabel(count: Int): String = "$count 张"

/**
 * 顶部的张数在**失败且一张都没读到**时的说法。
 *
 * 这一条是刻意的: 用 `items.size` 直接显示会在加载失败的那一屏写"0 张", 而那是
 * 把"没读到"说成"没有照片" —— 与浏览页不许把未统计说成"0 张"是同一条纪律的反面。
 */
const val GRID_COUNT_UNKNOWN = "张数没读到"

/** 200 但 `scan` 返回 0 张 —— 空文件夹/没有支持的文件**不是错误**, 但绝不能画一个空白网格。 */
const val GRID_EMPTY = "电脑扫描这个文件夹返回 0 张照片。可能它本来就没有照片，也可能里面的文件" +
    "格式不在支持范围（JPG / PNG / HEIC / RAW 与常见视频之外的文件不算），或者电脑读不动这个目录。" +
    "若电脑上明明有照片，确认它还在共享根里、并且能在电脑上打开，然后点「刷新」。"

/** 全是 RAW 时的说明 —— 缩略图链路与 JPEG 不同(不是错误, 但用户会看到"慢"与灰块, 得先说清)。 */
const val GRID_ALL_RAW = "这里全是 RAW：缩略图优先用电脑上的缩略图缓存，取不到时才现场解码，" +
    "所以第一遍可能比较慢；个别取不到图的格子会显示成灰块。"

/**
 * 全 RAW 提示。**只看照片** —— 服务端对空文件夹给的是空数组, 那种情况走 [GRID_EMPTY],
 * 两句话不能混(一句说"没有照片", 另一句说"有照片但慢", 混了必然有一边是错的)。
 */
fun allRawNotice(items: List<GridItem>): String? =
    if (items.isNotEmpty() && items.all { it.isRaw }) GRID_ALL_RAW else null

// ── 状态 ─────────────────────────────────────────────────────────────────────

/**
 * 网格页的全部状态。**不含任何安卓/Compose 类型** —— 所以它能在 JVM 单测里被真的走一遍。
 *
 * 三个"空"必须分得清(这一页最容易犯的错就是把它们混成一个转圈或一张空白网格):
 *   · [loading] —— 还没回来, 画占位;
 *   · [error] —— 失败了, 说人话 + 动作(文案查 [BrowseMessages], 与浏览页同一套码);
 *   · [isEmpty] —— 真的 0 张, 说 [GRID_EMPTY]。
 */
data class GridState(
    /** 正在看哪个文件夹(路径是不透明的, 逐字来自服务端)。 */
    val folder: FolderEntryDto,
    val items: List<GridItem> = emptyList(),
    /** 初始就是 true: 进目录先画"正在读…", 而不是先给一个空白网格。 */
    val loading: Boolean = true,
    val error: FolderError? = null,
) {
    /** 顶部的张数 —— 来自 `scan` 的返回长度(失败且没有格子时说 [GRID_COUNT_UNKNOWN])。 */
    val countLabel: String
        get() = if (error != null && items.isEmpty()) GRID_COUNT_UNKNOWN else gridCountLabel(items.size)

    val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()

    val emptyMessage: String? get() = if (isEmpty) GRID_EMPTY else null

    /** 全 RAW 的说明(没有就不画这一条)。 */
    val notice: String? get() = allRawNotice(items)

    val hasPhotos: Boolean get() = items.isNotEmpty()
}

/**
 * 网格页的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 与 [BrowseViewModel] 同样是"持有 StateFlow 的普通类"(理由逐条相同: 不继承
 * `androidx.lifecycle.ViewModel`, 免得 JVM 单测要先摆一个主线程调度器)。
 *
 * 三条纪律:
 *   · **只发一次 `scan`**: 服务端是单层语义, 拿的是这个文件夹的**直接**照片;
 *   · **失败不清空已有格子** —— 刷新失败时用户眼前的照片还在, 错误单独摆出来;
 *   · **401 必须走消歧**: 与配对页/浏览页同一条, 第一次 401 绝不当成 token 被轮换。
 *
 * `scan` 的 `path` **逐字**用 [FolderEntryDto.path](可能是 `\\?\D:\...` 这种带字面问号的
 * 规范路径) —— 这一页没有任何"自己拼路径"的地方。
 */
class GridViewModel(
    private val pairing: Pairing,
    /** 要看的文件夹。 */
    private val folder: FolderEntryDto,
    /**
     * 注入点: 生产路径就是默认值 —— [ApiClient] 内部的 client 走
     * [com.imagefilter.mobile.data.SharedHttpClient.forToken], 与 Coil 是**同一个实例**
     * (缩略图网格的硬要求)。覆盖它时必须同样以 `buildHttpClient` 为底, 否则
     * "token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {
    private val _state = MutableStateFlow(GridState(folder = folder))
    val state: StateFlow<GridState> = _state.asStateFlow()

    private val api: ApiClient by lazy { clientFactory(pairing.url, pairing.token) }

    /**
     * 拉这个文件夹的照片列表。「刷新」/「重试」也是它 —— 用户按了就是要新数据, 不做缓存判断。
     */
    suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val photos = api.scan(folder.path)
            _state.update {
                it.copy(items = gridItems(api, photos), loading = false, error = null)
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = failure(e)) }
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把取消说成网络故障
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(loading = false, error = synthesized(e)) }
        }
    }

    /** 只有 401 有资格触发消歧复探(Task 2 的纪律), 其余码一律不许猜成因。 */
    private suspend fun failure(e: ApiException): FolderError {
        val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
        return FolderError(e.code, cause)
    }

    private fun synthesized(e: IOException) = FolderError(ApiError.synthesized(apiCodeFor(e)).code)
}
