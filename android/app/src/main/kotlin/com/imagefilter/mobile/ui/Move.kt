// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.MoveMessages
import com.imagefilter.mobile.data.MoveOutcomeDto
import com.imagefilter.mobile.data.MoveReqDto
import com.imagefilter.mobile.data.MoveStatus
import com.imagefilter.mobile.data.MoveSummaryDto
import com.imagefilter.mobile.data.UnauthorizedCause

// ── 纯函数: 移动这条链路上所有的**判定**与**每一句话**(与 Compose 无关, 每条都有 JVM 单测) ─
//
// 与 BrowseViewModel / GridViewModel 同一条纪律: 安卓 UI 测试在本项目里没有依赖(也不该为它引入),
// 所以"这一行说什么、能不能重试、重试要发什么、还剩几项"必须是**可以真的断言**的纯函数,
// 而不是散在 Composable 里的 when。

/**
 * 结果面板里的**一行**(一张照片这一次移动的结论)。
 *
 * ⚠️ 包的是**服务端给的**那个不可变 [MoveOutcomeDto], 而不是把它的字段抄一遍:
 *   · 重试通道只有一处实现([MoveOutcomeDto.pendingSidecarRetry]) —— 抄一份出来就会有两处
 *     "边车怎么重试"的规则, 而其中一处迟早只改一半;
 *   · 重试之后这里**换掉的是同一张 DTO 的 `pendingSidecars`**(电脑刚回报的那一份就是新事实),
 *     所以"还能不能重试"仍然由同一个 [MoveRow.retryRequest] 回答。
 */
data class MoveRow(
    val item: MoveOutcomeDto,
    /** 这台手机上重试过之后的结论([applyRetryReport] 填), 没有就是 null。 */
    val retryNote: String? = null,
) {
    val src: String get() = item.src
    val fileName: String get() = fileNameOf(item.src)
    val dest: String? get() = item.dest

    /** 服务端**逐字**给的 status 字符串(认不出来时 [status] 是 null, 这里仍然是原文)。 */
    val rawStatus: String get() = item.status

    /** `null` = 这个构建不认识那一档 status —— UI 必须说"结果未知", 不许猜。 */
    val status: MoveStatus? get() = item.moveStatus

    /** 现在还没搬走的边车(重试之后就是电脑刚回报的那一份)。永远是数组。 */
    val pendingSidecars: List<String> get() = item.pendingSidecars

    /**
     * 这一行**现在**该怎么显示。
     *
     * 半成功项在"待补边车已经空了"之后就不再是半成功了 —— 那只可能来自一次成功的边车重试
     * (`moved ⇒ pendingSidecars = []` 是服务端的不变量), 所以此时照片与它的边车都在目标目录,
     * 说成"仍然脱钩"就是一句过时的谎话。
     */
    val displayStatus: MoveStatus?
        get() = if (status == MoveStatus.HALF_SUCCESS && pendingSidecars.isEmpty()) {
            MoveStatus.MOVED
        } else {
            status
        }

    /** 这一行现在能不能重试 —— 判据只有服务端给的 `pendingSidecars`(见 [MoveOutcomeDto])。 */
    val retryRequest: MoveReqDto? get() = item.pendingSidecarRetry()

    /** 这一行现在**还有没有可做的事**。 */
    val canRetry: Boolean get() = retryRequest != null

    val head: String get() = MoveMessages.headFor(displayStatus)

    /**
     * 正文。判据只有 status 与 `pendingSidecars`; **`item.detail` 一个字都不用**
     * (规格 §5.2: 那是本地化文案, 不是数据通道)。
     */
    val text: String get() = MoveMessages.forStatus(displayStatus, pendingSidecars.size, rawStatus)
}

/** 服务端逐项结果 → 面板的行(**顺序逐字保留**服务端的顺序, 与请求顺序一致)。 */
fun toMoveRows(items: List<MoveOutcomeDto>): List<MoveRow> = items.map { MoveRow(it) }

// ── 计数与批次句 ─────────────────────────────────────────────────────────────

/**
 * 计数。
 *
 * ⚠️ 四个数**逐字是服务端给的**(计划里的批次句就是按它们说的: `moved = 3` 那一批里只回了 1 条
 * 逐项结果, 句子仍然说的是"成功 3 张"), 但**同时**把"逐项到底回了几条"记下来([itemCount])。
 * 两者对不上时 [unaccounted] 就是 true —— 那时界面必须把"有几项的结果没被确认"**说出来**,
 * 而不是拿一句"成功 N 张"把不知道的那些一起盖过去(与 `WriteMessages.UNCONFIRMED` 同一条纪律)。
 *
 * [unknown] = 认不出来的 status 的项数: 既不算成功, 也不算失败 —— 服务端把一档结果改了线上拼写
 * 时, 这几项必须看得见, 而不是安静地归进某一档真实结果(mover.rs 的注释记着这条教训)。
 */
data class MoveTally(
    val moved: Int,
    val halfSuccess: Int,
    val conflict: Int,
    val failed: Int,
    /** 电脑**真的回报了**几条逐项结果。 */
    val itemCount: Int,
    val unknown: Int,
    val unaccounted: Boolean,
) {
    val total: Int get() = moved + halfSuccess + conflict + failed
}

fun tallyMove(s: MoveSummaryDto): MoveTally {
    val counts = s.moved + s.halfSuccess + s.conflict + s.failed
    val unknown = s.items.count { it.moveStatus == null }
    return MoveTally(
        moved = s.moved,
        halfSuccess = s.halfSuccess,
        conflict = s.conflict,
        failed = s.failed,
        itemCount = s.items.size,
        unknown = unknown,
        // 条数与计数对不上, 或者有条目的 status 这个构建不认识 ⇒ 有项的结果**没有结论**
        unaccounted = s.items.size != counts || unknown > 0,
    )
}

/**
 * 一次移动的批次结论(一句话)。**半成功绝不被"成功"盖住**(规格 §11 纪律②)。
 *
 * 每一档都在句子里, 而且空白档不占位(0 张失败时不会出现"0 张没移动"这种噪音);
 * 计数与逐项结果对不上时**明说**, 而不是让"成功 N 张"把没被确认的那些一起说成既成事实。
 */
fun summarizeMove(s: MoveSummaryDto): String {
    val t = tallyMove(s)
    if (s.items.isEmpty() && t.total == 0) {
        return "电脑没有回报任何逐项结果 —— 请回网格点「刷新」，确认这些照片现在在哪。"
    }
    val parts = buildList {
        if (t.moved > 0) add("成功 ${t.moved} 张")
        if (t.halfSuccess > 0) {
            add("${t.halfSuccess} 张照片移走了但边车没跟上（评分与新位置暂时脱钩，可以在下面重试边车）")
        }
        if (t.conflict > 0) add("${t.conflict} 张目标位置已有同名文件, 未覆盖（原样留在原地）")
        if (t.failed > 0) add("${t.failed} 张没有移动（原样留在原地）")
        if (t.unknown > 0) add("${t.unknown} 张的结果这台手机不认识（请刷新确认它们在哪）")
        if (t.unaccounted) {
            add("电脑只回报了 ${t.itemCount} 条逐项结果, 和它给的计数对不上 —— 有几项**没被确认**（别把它们当成成功）")
        }
    }
    return parts.joinToString("；") + "。"
}

// ── 重试: 只重发仍然待补的边车(规格 §5.2 的重试通道) ────────────────────────

/** 一项重试: 哪一行(照片 src) + 要发出去的那个请求。 */
data class MoveRetryRequest(val src: String, val req: MoveReqDto)

/**
 * 一次「重试」/「全部重试」**要发的东西** —— 这是重试通道的**唯一**构造点。
 *
 * 三条纪律逐字落在 [MoveRetryRequest.req] 上(每一条都有测试盯着):
 *   · `paths` 逐字是服务端给的 `pendingSidecars` —— **手机不推导边车命名规则**(规格 §3.2 第一条);
 *   · 每一批**只有一项**(不把多张的待补边车合成一批): 批次是全有或全无校验的, 一条坏路径会
 *     让整批 404 —— M0′ Task 7 实测过的那个坑;
 *   · **半成功项的 `src`(照片)已经被搬走了**, 带上它整批 404。所以它绝不出现在 `paths` 里 ——
 *     这里不是"顺手过滤一下", 而是"从构造上就取不到它"。
 *
 * [only] = `null` ⇒ 「全部重试」(此刻仍然有待补边车的那些);
 * 否则是用户点名的那些 src(点了一项没有重试通道的, 落到 [notRetryable], **一个请求都不发**)。
 */
data class MoveRetryPlan(
    val requests: List<MoveRetryRequest> = emptyList(),
    /** 点名要重试、但**没有重试通道**的项(不是半成功 / 没有待补边车 / 拿不到目标目录)。 */
    val notRetryable: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = requests.isEmpty()

    /** 会被发出去的**全部**路径 —— 测试用它钉住"死掉的照片 src 一个都不在里面"。 */
    val allPaths: List<String> get() = requests.flatMap { it.req.paths }
}

fun moveRetryPlan(rows: List<MoveRow>, only: List<String>? = null): MoveRetryPlan {
    val requests = mutableListOf<MoveRetryRequest>()
    val notRetryable = mutableListOf<String>()
    if (only == null) {
        // 「全部重试」= 只重发**仍然有待补边车**的项。已经成功的一个字节都不重发。
        for (row in rows) {
            val req = row.retryRequest ?: continue
            requests += MoveRetryRequest(row.src, req)
        }
    } else {
        for (src in only.distinct()) {
            val req = rows.firstOrNull { it.src == src }?.retryRequest
            if (req != null) requests += MoveRetryRequest(src, req) else notRetryable += src
        }
    }
    return MoveRetryPlan(requests, notRetryable)
}

/** 一项重试的结果: 电脑回了什么, 或者为什么没有回。 */
data class MoveRetryAttempt(
    val src: String,
    val req: MoveReqDto,
    /** 电脑的逐项回应; `null` 表示这次重试**没有结论**(请求没打通/被拒)。 */
    val summary: MoveSummaryDto? = null,
    val errorCode: String? = null,
    val cause: UnauthorizedCause? = null,
) {
    /**
     * 这次重试把那些边车**搬过去**了吗 —— 只有电脑逐项说 `moved` 才算。
     *
     * 判据刻意不是"回应里没有待补边车": 一次**冲突/失败**的回应同样没有 `pendingSidecars`
     * (不变量: `moved`/`conflict`/`failed` ⇒ `[]`), 把那种回应当成"跟上了"就是一次
     * 把失败说成成功的静默 —— 而这正是本任务最不能出的错。
     */
    val caughtUp: Boolean
        get() = summary?.let { s -> s.items.isNotEmpty() && s.items.all { it.moveStatus == MoveStatus.MOVED } } == true
}

/**
 * 一次「重试」/「全部重试」的结论 —— 三个桶**分开数**(与 `RetryReport` 同一条纪律):
 *   · [caughtUp] —— 电脑确认边车搬过去了;
 *   · [stillOff] —— 电脑回了, 但边车没搬动(目标位置有同名文件 / 失败): 仍然脱钩;
 *   · [notConfirmed] —— **没有结论**(请求没打通 / 被拒): 不许算成成功, 也不许算成"边车还在原地"
 *     —— 那两句话都是替服务端保证一件它没说过的事。
 */
data class MoveRetryReport(
    val plan: MoveRetryPlan,
    val attempts: List<MoveRetryAttempt> = emptyList(),
) {
    val attempted: Int get() = attempts.size
    val caughtUp: List<String> get() = attempts.filter { it.caughtUp }.map { it.src }
    val stillOff: List<String> get() = attempts.filter { !it.caughtUp && it.summary != null }.map { it.src }
    val notConfirmed: List<String> get() = attempts.filter { it.summary == null }.map { it.src }
}

/** 把一次重试的结果并回结果面板 —— **只动被重试的那一行**。 */
fun applyRetryReport(rows: List<MoveRow>, report: MoveRetryReport): List<MoveRow> {
    if (report.attempts.isEmpty()) return rows
    val bySrc = report.attempts.associateBy { it.src }
    return rows.map { row -> bySrc[row.src]?.let { rowAfterRetry(row, it) } ?: row }
}

/** 一行 + 一次重试的结果 → 这一行现在的事实(见 [MoveRow.displayStatus])。 */
fun rowAfterRetry(row: MoveRow, attempt: MoveRetryAttempt): MoveRow {
    val summary = attempt.summary
        ?: return row.copy(retryNote = MoveMessages.retryFailed(attempt.errorCode, attempt.cause))
    if (attempt.caughtUp) {
        return row.copy(
            item = row.item.copy(pendingSidecars = emptyList()),
            retryNote = MoveMessages.RETRY_CAUGHT_UP,
        )
    }
    // 没搬动: 电脑新回报的待补边车优先(它是最新的事实); 一条都没有(冲突/失败都是这样)时
    // **保留原来那一份** —— 不然这一行的重试按钮会消失, 而边车其实还留在原地。
    val remaining = summary.items.flatMap { it.pendingSidecars }
    return row.copy(
        item = row.item.copy(pendingSidecars = remaining.ifEmpty { row.pendingSidecars }),
        retryNote = summarizeMove(summary),
    )
}

/** 一次重试之后面板顶部那句话。**只转述电脑确认了什么**(纪律③: 未同步 ≠ 已同步)。 */
fun moveRetryNote(plan: MoveRetryPlan, report: MoveRetryReport): String = when {
    report.attempted == 0 && plan.notRetryable.isNotEmpty() -> MoveMessages.retryNoChannel(plan.notRetryable.size)
    report.attempted == 0 -> MoveMessages.RETRY_NOTHING_PENDING
    else -> buildString {
        append("重试了 ${report.attempted} 项")
        if (report.caughtUp.isNotEmpty()) {
            append("，${report.caughtUp.size} 项的边车这次跟上了（照片与边车都在目标文件夹里）")
        }
        if (report.stillOff.isNotEmpty()) {
            append("，${report.stillOff.size} 项**仍然脱钩**（边车没搬动：目标位置可能有同名文件，改名后再试）")
        }
        if (report.notConfirmed.isNotEmpty()) {
            append("，${report.notConfirmed.size} 项**没能确认**（请求没打通或没被接受）—— 它们还是脱钩的")
        }
        append("。")
    }
}

// ── 多选与确认句 ─────────────────────────────────────────────────────────────

/** 顶栏那句"选了几张"。0 张时说下一步做什么, 而不是显示一个没有动作的"已选 0 张"。 */
fun selectionLabel(count: Int): String = if (count <= 0) "点照片选择" else "已选 $count 张"

/** 选择模式下顶栏那个「移动到…」按钮能不能按。 */
fun canStartMove(count: Int): Boolean = count > 0

/**
 * 移动前的**确认句**。
 *
 * 三件事必须同时说清(缺一件这个确认就没有意义):
 *   · 多少张、移到哪个文件夹([destName] 是给人认的名字, [destDir] 是逐字发回服务端的身份);
 *   · **绝不覆盖** —— 目标已有同名文件时报冲突、原样留在原地;
 *   · 目标就是当前文件夹时**单独一句**(那只会让每一张都报冲突, 白跑一趟)。
 */
fun moveConfirmText(count: Int, destName: String, destDir: String, sourceDir: String? = null): String {
    val where = "「${destName.ifBlank { destDir }}」"
    if (sourceDir != null && sourceDir == destDir) {
        return "这 $count 张**本来就在**$where 里 —— 移到这里只会让电脑对每一张都报「目标位置已有同名文件」，" +
            "不会覆盖任何东西, 也不会发生别的事。请换一个目标文件夹。"
    }
    return "把这 $count 张移到 $where？\n" +
        "电脑会先搬照片、再搬同名的 .xmp 边车。目标位置**已经有同名文件的不会被覆盖**：" +
        "那几张会报冲突并原样留在原地（你可以在结果里逐张看到）。"
}

// ── 目标文件夹选择器: **复用**浏览页那棵树, 不写第二个浏览器 ────────────────

/**
 * 选择器要用的一切 —— 全部来自 [BrowseState](共享根 + 已经加载过的那一层 + 展开状态)。
 *
 * 为什么是"复用"而不是"另写一个"([BrowseViewModel] 与 [visibleRows] 都是现成的):
 *   · 目录树的口径只能有一份 —— 另写一个就会出现"浏览页点得开、选择器点不开"这种漂移;
 *   · 而"能选什么"这件事**根本不该由手机决定**: 端点表里没有 Mkdir, 所以选择器里出现的每一个
 *     目标都必须是服务端真的回过的路径([FolderPickerModel.rows] 逐字来自 `shares` 与 `browse`)。
 *     [limitation] 就是这条限制的原话, UI 必须显示它。
 */
data class FolderPickerModel(
    /** 可见行(共享根 + 已展开的层)。**没有**任何本地编出来的节点。 */
    val rows: List<TreeRow> = emptyList(),
    /** 已展开的节点(交给 [TreeItemRow] 画 ▾/▸)。 */
    val expanded: Set<String> = emptySet(),
    /** 正在飞的加载(UI 画占位, 不许说成"空目录")。 */
    val loading: Set<String> = emptySet(),
    /** 逐层的失败(那一行下面画一句人话 + 重试)。 */
    val errors: Map<String, FolderError> = emptyMap(),
    /** 当前选中的目录(它就是"移到这里"的目标); 还没点过就是 null。 */
    val pickPath: String? = null,
    /** 只能选已有文件夹 —— 必须显示(见 [MoveMessages.NO_MKDIR])。 */
    val limitation: String = MoveMessages.NO_MKDIR,
    // ── 根这一层的三件事实(直接来自 [BrowseState], 选择器里**不另算一遍**)──
    /** 正在读共享根。 */
    val rootsLoading: Boolean = false,
    /** 共享根没读出来(空共享根**不是**错误, 它是服务端有意的安全默认)。 */
    val rootsError: FolderError? = null,
    /** 电脑上配了共享根没有(空 ⇒ 哪里都移不了, 得先去电脑上加)。 */
    val hasRoots: Boolean = false,
) {
    val pickName: String? get() = pickPath?.let { displayNameOf(it) }

    val canPick: Boolean get() = pickPath != null
}

fun folderPickerModel(state: BrowseState): FolderPickerModel = FolderPickerModel(
    rows = visibleRows(state.roots, state.index, state.expanded),
    expanded = state.expanded,
    loading = state.loading,
    errors = state.errors,
    pickPath = state.selected,
    rootsLoading = state.rootsLoading,
    rootsError = state.rootsError,
    hasRoots = state.hasRoots,
)
