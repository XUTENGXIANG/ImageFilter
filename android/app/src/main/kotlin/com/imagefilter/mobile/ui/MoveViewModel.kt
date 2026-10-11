// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.MoveMessages
import com.imagefilter.mobile.data.MoveSummaryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.apiCodeFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * 移动这件事现在走到哪一步了。**五档, 不是"开着/没开"两态** ——
 * 它决定了屏幕上该画什么: 挑目标、确认、正在搬、结果是四件不同的事(而它们的下一步动作完全不同)。
 */
enum class MoveStage {
    /** 没开始(还在选照片)。 */
    Idle,

    /** 正在**已有**文件夹里挑一个目标(选择器=浏览页那棵树)。 */
    Picking,

    /** 目标挑好了, 等用户确认(确认句里说清"绝不覆盖")。 */
    Confirming,

    /** 请求在飞。 */
    Running,

    /** 电脑回了逐项结果(可能带半成功/冲突)。 */
    Result,
}

/**
 * 移动的界面状态。**不含任何安卓/Compose 类型** —— 所以它能在 JVM 单测里被真的走一遍
 * (与 [BrowseState] / [GridState] 同一条纪律)。
 */
data class MoveState(
    /** 现在正在看哪个文件夹 —— 只用来说清"这些照片本来就在这个目标里"这一种情形。 */
    val sourceDir: String? = null,
    /** 多选模式开着吗(长按进入, 与查看器/滑卡页互斥)。 */
    val selecting: Boolean = false,
    /** 选中的路径(**顺序不在这里** —— 请求的顺序由调用方按网格顺序给, 见 [requestedPaths])。 */
    val selected: Set<String> = emptySet(),
    val stage: MoveStage = MoveStage.Idle,
    /** 挑好的目标目录(逐字发回服务端; 绝不自己拼)。 */
    val destDir: String? = null,
    /** 目标目录的显示名(只用于给人看)。 */
    val destName: String = "",
    /** 电脑最近一次回的逐项结果。 */
    val summary: MoveSummaryDto? = null,
    /** 结果面板的行。 */
    val rows: List<MoveRow> = emptyList(),
    /** 批次结论 / 重试结论(**只转述电脑确认了什么**)。 */
    val note: String? = null,
    /** **批次级**失败的一句话(逐项结果里没有"整批失败"这件事, 两者必须分得清)。 */
    val error: String? = null,
    /** 正在重试(串行, 一项一批): 期间不许再点, 免得自己制造乱序。 */
    val retryBusy: Boolean = false,
) {
    val selectedCount: Int get() = selected.size

    val busy: Boolean get() = stage == MoveStage.Running

    /** 顶栏那句「已选 N 张」。 */
    val selectionLabel: String get() = com.imagefilter.mobile.ui.selectionLabel(selectedCount)

    val canStartMove: Boolean get() = com.imagefilter.mobile.ui.canStartMove(selectedCount)

    val tally: MoveTally? get() = summary?.let { tallyMove(it) }

    /** 还有几张需要重试边车(0 时不画那个按钮)。 */
    val retryableCount: Int get() = rows.count { it.canRetry }

    /**
     * **要发出去的路径** = 用户选中的**全部**, 顺序按调用方给的 [order](网格顺序)。
     *
     * ⚠️ **这里曾经是 `order.filter { it in selected }`** —— 而 [order] 在生产路径上是**筛过之后**
     * 的网格([GridState.visibleItems]), 于是"选 10 张 → 点一个筛选词条挡住 3 张 → 移动"会让确认句
     * 说"把这 10 张移到…"而请求里只有 7 条。什么都没丢, 但用户相信有 10 张搬走了, 其中 3 张其实还
     * 躺在原文件夹里 —— 正是本项目最怕的那种"看起来成了"。而且筛选栏在多选期间是**活的**
     * ([FilterBar] 一直在, 只有顶栏那几个动作会换成移动的), 所以这不是一个边角时序。
     *
     * 取舍:**选谁, 是用户在网格上点出来的** —— 顶栏那句"已选 N 张"([selectionLabel])、
     * [canStartMove]、确认句([confirmText]) 数的都是这个集合, 用户按「移动到…」时的意图就是它。
     * 筛选只是**看**的方式, 它没有资格替用户减掉照片。所以 [order] **只提供顺序**(在它里面出现的
     * 那些按它的次序排, 也就是电脑上的次序), 选中的、但不在 [order] 里的那些接在后面按路径排。
     *
     * 这样 `requestedPaths(order).size == selected.size` 是**构造上的恒等式** ——
     * "说的"与"发的"不可能是两个数, 不靠调用点记得改过来(读代码的人也不必先确认 GridScreen
     * 传的是 visibleItems 还是 items)。
     */
    fun requestedPaths(order: List<String>): List<String> {
        val inOrder = order.filter { it in selected }.distinct()
        val seen = inOrder.toSet()
        return inOrder + selected.filterNot { it in seen }.sorted()
    }

    /** 确认句(挑好目标之后才有)。 */
    val confirmText: String?
        get() = destDir?.let { moveConfirmText(selectedCount, destName, it, sourceDir) }
}

/**
 * 移动分类的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 与 [BrowseViewModel] / [GridViewModel] 同样是"持有 StateFlow 的普通类"(理由相同: 不继承
 * `androidx.lifecycle.ViewModel`, 免得 JVM 单测要先摆一个主线程调度器)。
 *
 * 五条纪律:
 *   · **移动是全有或全无校验的批次, 重试不是** —— 重试**一项一批、串行**(见 [MoveRetryPlan]);
 *   · **只重发仍然待补的边车**([moveRetryPlan]), 已经搬走的照片 src 一个字节都不发;
 *   · **冲突不覆盖**(服务端的事), 手机这边的责任是**如实说出来**而不是把它折进"失败"里;
 *   · **失败不许清空结果**: 批次级失败回到"挑目标"那一步(可以换个目录再试), 面板上的旧结果
 *     只在**新的一次移动开始**时才清;
 *   · **401 走消歧**: 与配对页/浏览页/网格同一条, 第一次 401 绝不当成 token 被轮换。
 */
class MoveViewModel(
    private val pairing: Pairing,
    /**
     * 现在正在看的那个文件夹(可选)。只用来说清"目标就是当前文件夹"那种白跑一趟的情形 ——
     * 少了它, 用户会看到一句"把这 3 张移到「照片」？", 然后电脑对每一张都报冲突。
     */
    sourceDir: String? = null,
    /**
     * 注入点: 生产路径就是默认值([ApiClient] 内部用
     * [com.imagefilter.mobile.data.SharedHttpClient.forToken], 与 Coil 是**同一个实例**)。
     * 覆盖它时必须同样以 `buildHttpClient` 为底, 否则"token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {
    private val _state = MutableStateFlow(MoveState(sourceDir = sourceDir))
    val state: StateFlow<MoveState> = _state.asStateFlow()

    private val api: ApiClient by lazy { clientFactory(pairing.url, pairing.token) }

    // ── 多选 ────────────────────────────────────────────────────────────────

    /** 进入多选模式(**清掉上一次的结论** —— 它说的是上一批照片)。 */
    fun startSelecting() {
        _state.update {
            it.copy(
                selecting = true,
                selected = emptySet(),
                stage = MoveStage.Idle,
                destDir = null,
                destName = "",
                summary = null,
                rows = emptyList(),
                note = null,
                error = null,
                retryBusy = false,
            )
        }
    }

    /** 退出多选模式(结果面板上的「完成」也走它)。 */
    fun cancelSelecting() {
        _state.update {
            MoveState(sourceDir = it.sourceDir, selecting = false)
        }
    }

    /** 点一张格子: 切换选中。没在多选模式里就什么都不做(点=打开查看器, 那件事在网格那边)。 */
    fun toggle(path: String) {
        _state.update { st ->
            if (!st.selecting) return@update st
            st.copy(
                selected = if (path in st.selected) st.selected - path else st.selected + path,
                // 选择一变, 上一次的结论就过期了(它说的是上一批照片)
                summary = null,
                rows = emptyList(),
                note = null,
                error = null,
                stage = if (st.stage == MoveStage.Result) MoveStage.Idle else st.stage,
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet(), summary = null, rows = emptyList(), note = null, error = null, stage = MoveStage.Idle) }
    }

    /**
     * **选中**这一格(不加切换) —— 长按走它: "长按 = 进入多选并选中这一格"在任何状态下都成立,
     * 包括长按一张**已经选中**的格子(那时它不该被取消, 取消由再点一下负责)。
     */
    fun select(path: String) {
        _state.update { st ->
            if (!st.selecting || path in st.selected) return@update st
            st.copy(
                selected = st.selected + path,
                summary = null,
                rows = emptyList(),
                note = null,
                error = null,
                stage = if (st.stage == MoveStage.Result) MoveStage.Idle else st.stage,
            )
        }
    }

    // ── 挑目标 ──────────────────────────────────────────────────────────────

    fun openPicker() {
        _state.update { it.copy(stage = MoveStage.Picking, error = null) }
    }

    /** 从确认那一步退回选择器(换个目标)。 */
    fun backToPicker() {
        _state.update { it.copy(stage = MoveStage.Picking, error = null) }
    }

    /**
     * 选中一个目标文件夹。
     *
     * ⚠️ 这里**只接受服务端回过的目录**([FolderEntryDto.path] 逐字来自 `shares`/`browse`)——
     * 手机不拼路径、也不能新建(规格 §3.2 第一条 + M0′ 没有 Mkdir)。
     */
    fun pickFolder(entry: FolderEntryDto) {
        _state.update {
            it.copy(
                stage = MoveStage.Confirming,
                destDir = entry.path,
                destName = entry.name.ifBlank { displayNameOf(entry.path) },
                error = null,
            )
        }
    }

    // ── 真的移动 ────────────────────────────────────────────────────────────

    /**
     * 把选中的那些移到挑好的目标。
     *
     * [order] 由调用方给(网格顺序): 请求里的 `paths` 逐字按它排 —— 服务端的逐项结果与请求顺序
     * 一一对应, 所以顺序稳定下来, 结果面板才对得上眼睛看到的那一叠。
     *
     * ⚠️ **[order] 只决定顺序, 不决定"发哪几张"**: 发出去的是**选中项全体**
     * (见 [MoveState.requestedPaths], FIX 1 —— 被当前筛选挡住的那几张照发)。
     *
     * 返回批次结果(`null` = 一个请求都没发, 或者批次级失败)。
     */
    suspend fun move(order: List<String>): MoveSummaryDto? {
        val st = _state.value
        val destDir = st.destDir ?: return null
        val paths = st.requestedPaths(order)
        // 空批次在服务端是 badRequest(与 readDecisions 同一条), 本地先挡 —— 白跑一趟只会多等一次超时
        if (paths.isEmpty()) return null

        _state.update {
            it.copy(
                stage = MoveStage.Running,
                summary = null,
                rows = emptyList(),
                note = null,
                error = null,
            )
        }
        return try {
            val summary = api.move(paths, destDir)
            _state.update {
                it.copy(
                    stage = MoveStage.Result,
                    summary = summary,
                    rows = toMoveRows(summary.items),
                    // 半成功/冲突/计数对不上都在这一句里(纪律②: 半成功必须显式)
                    note = summarizeMove(summary),
                    error = null,
                )
            }
            summary
        } catch (e: ApiException) {
            val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
            _state.update {
                // **回到"挑目标"这一步**: 批次级失败(路径越界/不存在/只读模式)最可能的修法是换个目录再试
                it.copy(stage = MoveStage.Confirming, error = MoveMessages.forBatchFailure(e.code, cause))
            }
            null
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把取消说成网络故障
            currentCoroutineContext().ensureActive()
            _state.update {
                it.copy(stage = MoveStage.Confirming, error = MoveMessages.forBatchFailure(apiCodeFor(e)))
            }
            null
        }
    }

    // ── 重试待补的边车 ──────────────────────────────────────────────────────

    /** 「全部重试」: 只重发**此刻**仍然有待补边车的那些。 */
    suspend fun retryAll(): MoveRetryReport = runRetries(moveRetryPlan(_state.value.rows))

    /** 某一行的「重试边车」。 */
    suspend fun retryOne(src: String): MoveRetryReport =
        runRetries(moveRetryPlan(_state.value.rows, only = listOf(src)))

    /**
     * 真的跑一轮重试。**串行、一项一批**。
     *
     * 名字刻意不叫 `retryAll`(与 `GridViewModel.runRetry` 同一条理由): 两个同名实现互相掩盖是
     * 本项目一直在避免的形状 —— 读代码的人得先看清是成员还是顶层才知道走的是哪一条。
     *
     * 每跑完一项就把那一行更新一次(用户会看到列表一项一项地变, 而不是等整批跑完)——
     * 而且**列表以电脑的回应为准**, 不拿这一项的结论去猜别的项。
     */
    private suspend fun runRetries(plan: MoveRetryPlan): MoveRetryReport {
        if (plan.isEmpty) {
            val report = MoveRetryReport(plan)
            _state.update { it.copy(note = moveRetryNote(plan, report), retryBusy = false) }
            return report
        }

        _state.update { it.copy(retryBusy = true, note = null, error = null) }
        val attempts = mutableListOf<MoveRetryAttempt>()
        for (request in plan.requests) {
            val attempt = try {
                MoveRetryAttempt(
                    src = request.src,
                    req = request.req,
                    summary = api.move(request.req.paths, request.req.destDir),
                )
            } catch (e: ApiException) {
                val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
                MoveRetryAttempt(request.src, request.req, errorCode = e.code, cause = cause)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                MoveRetryAttempt(request.src, request.req, errorCode = apiCodeFor(e))
            }
            attempts += attempt
            val partial = MoveRetryReport(plan, attempts.toList())
            _state.update {
                it.copy(rows = applyRetryReport(it.rows, partial), note = moveRetryNote(plan, partial))
            }
        }
        val report = MoveRetryReport(plan, attempts.toList())
        _state.update { it.copy(retryBusy = false, note = moveRetryNote(plan, report)) }
        return report
    }
}
