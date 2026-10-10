// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.DecisionDto
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.WriteSummaryDto
import com.imagefilter.mobile.data.apiCodeFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * 五色标签 —— 取值与桌面端 `src/labels.ts` 完全一致(服务端只认这五个字符串)。
 * 桌面端的色标是**闭集**, 所以手机端也不许发明第六种颜色。
 */
val LABELS: List<String> = listOf("red", "yellow", "green", "blue", "purple")

/** 点一个色标: 点**当前这个** = 清掉它(走 `clearLabel`), 点别的 = 设为它。 */
fun labelAfterTap(current: String?, tapped: String): String? = if (current == tapped) null else tapped

/** 色标的中文名(只用于界面上的气泡/读屏, 不是协议的一部分)。 */
fun labelName(label: String?): String = when (label) {
    "red" -> "红"
    "yellow" -> "黄"
    "green" -> "绿"
    "blue" -> "蓝"
    "purple" -> "紫"
    null -> "无"
    else -> label
}

/**
 * 一张照片在**这台手机上**的决策意图。
 *
 * 两个"不动"必须分得清(与服务端 `DecisionWrite` 逐字对应):
 *   · `rating = null` / `label = null` + `clearLabel = false` = **不动这个属性**;
 *   · `clearLabel = true` = 明确要把边车里的标签**删掉**。
 * 混成一个(null 当成"清空"), 用户撤销标签后边车里的旧值会在下次读盘时复活。
 */
data class Decision(
    val path: String,
    /** `null` = 这台手机上还没定过星级; `0` = 明确废弃(桌面端 X 键的语义), **不是**"不动"。 */
    val rating: Int? = null,
    val label: String? = null,
    /**
     * 一次性的**命令**: true = 让电脑删掉边车里的标签。
     *
     * 它是命令而不是状态, 所以: 写成功后必须被消费掉(否则下一次改星级会把"删标签"重发一遍);
     * 写失败时必须留着(否则"重试"会退化成一次什么都没干的请求, 用户的意图就丢了)。
     */
    val clearLabel: Boolean = false,
    /** 这台手机上的这份意图有没有被电脑确认过(纪律③的"可见性"就是靠它)。 */
    val sync: SyncState = SyncState.UNTOUCHED,
    /** 最近一次写入失败(逐项)。非 null 时界面**必须**画出来 + 给重试。 */
    val lastError: WriteFailure? = null,
) {
    /** 打星(0..=5)。`0` 是"废弃", 与服务端 `xmp:Rating` 的取值一致。 */
    fun withRating(stars: Int): Decision {
        require(stars in 0..5) { "星级只能是 0..5(0 = 废弃), 收到的是 $stars" }
        return copy(rating = stars, sync = SyncState.UNSYNCED, lastError = null)
    }

    /** 标色(或 `null` = 清掉)。取值是 [LABELS] 这个闭集, 与服务端一致。 */
    fun withLabel(label: String?): Decision {
        require(label == null || label in LABELS) {
            "色标只能是 ${LABELS.joinToString("/")} 之一, 收到的是 $label"
        }
        return if (label == null) {
            copy(label = null, clearLabel = true, sync = SyncState.UNSYNCED, lastError = null)
        } else {
            copy(label = label, clearLabel = false, sync = SyncState.UNSYNCED, lastError = null)
        }
    }

    /** 发给电脑的那一项。字段名逐字对应服务端 `DecisionWrite`(camelCase)。 */
    fun toDto(): DecisionDto = DecisionDto(path = path, rating = rating, label = label, clearLabel = clearLabel)
}

/**
 * 这份意图和电脑的关系。
 *
 * [UNTOUCHED] 与 [UNSYNCED] 必须分开: 把"没动过"也算进未同步, "未同步 N 张"这个角标立刻
 * 变成一句假话(整个文件夹都是未同步), 纪律③要求的那条可见性也就废了。
 */
enum class SyncState {
    /** 这台手机上没动过它 —— **不是**"未同步"。 */
    UNTOUCHED,

    /** 改了, 但电脑还没确认写成(还没发 / 失败 / 结果没被回报)。**界面上必须看得见**。 */
    UNSYNCED,

    /** 电脑确认过了(`written`, 或者 `skipped` = 电脑上本来就是这个值)。 */
    SYNCED,
}

/**
 * 一次批量写入的结果。
 *
 * 三个变体而不是一个布尔值, 因为这三件事**完全不同**, 混起来必然撒谎:
 *   · [Ok] —— 服务端逐项回报里**一条失败都没有**。"写成功"只由服务端说了算, 客户端不许自己判;
 *   · [Failed] —— 至少一项没写成(可能整批没成, 也可能一批里成了几张、坏了几张);
 *   · [NothingToPush] —— 手机上这些路径**根本没有本地改动**, 一个请求都没发出去。
 *     它既不是成功也不是失败; 把它算成 [Ok], 界面就会在没有服务端确认的情况下说"已同步"。
 *
 * [Failed.written] / [Failed.skipped] 如实转述服务端的计数(半成功态显式化, 纪律②)。
 */
sealed class PushResult {
    abstract val requested: Int

    /** 服务端逐项回报里没有任何失败。 */
    data class Ok(
        override val requested: Int,
        val written: Int,
        val skipped: Int,
        /** true = 至少有一次写入退化成"非原子直写"(SMB/CIFS 不支持 rename 覆盖)。 */
        val degraded: Boolean,
    ) : PushResult()

    /** 至少一项没写成 —— 逐项失败都在 [failures] 里, **各带各的码**。 */
    data class Failed(
        override val requested: Int,
        val written: Int,
        val skipped: Int,
        val failures: List<WriteFailure>,
        /**
         * true = 服务端回了一个 `WriteSummary`(HTTP 200), 逐项失败来自它;
         * false = 整个请求没被确认(HTTP 错误码 / 网络断了), 一台电脑到底写没写不知道。
         */
        val summaryConfirmed: Boolean,
    ) : PushResult()

    /** 没有本地改动可发(请求没有发出去)。 */
    data class NothingToPush(override val requested: Int) : PushResult()
}

/**
 * 服务端回报之后, 这一项该是什么状态。
 *
 * 抽成纯函数是为了**能被直接钉住**: 下面两条规则都只在"写入还在飞"的时序里才看得出来,
 * 拿假的多线程去测只会得到一个会抖的测试。
 *   · **飞行中又被改过的项不许标记为已同步** —— 用户在你写第 1 版的时候改成了第 2 版,
 *     服务端确认的是**第 1 版**; 这时说"已同步"就是撒谎(纪律③)。判据是"发出去的那份 DTO
 *     与现在本地这份是否逐字相同";
 *   · 失败时**保留本地值**(绝不回滚丢弃)并记下这条失败, 让界面能显示、能重试。
 */
internal fun afterAck(current: Decision?, sent: DecisionDto, failure: WriteFailure?): Decision? {
    if (current == null) return null
    if (failure != null) return current.copy(sync = SyncState.UNSYNCED, lastError = failure)
    return if (current.toDto() == sent) {
        // 成功: 落成"已同步", 并把一次性命令 clearLabel 消费掉。
        current.copy(sync = SyncState.SYNCED, clearLabel = false, lastError = null)
    } else {
        // 飞出去的那一版成了, 但本地已经有更新的一版 —— 那一版还没被确认。
        current.copy(sync = SyncState.UNSYNCED, lastError = null)
    }
}

/**
 * 本地决策镜像 + 推送(规格 §4.3 / §11 三条纪律)。
 *
 * 1. **乐观更新**: 用户点星后界面立刻变 —— 网络往返不该让人等;
 * 2. **失败不回滚丢弃**: 保留本地值并**明确报告**失败。静默回滚会让用户以为"白打了一次分",
 *    静默吞掉更糟; 与规格的"保留本地改动 + 标未同步 + 手动重试"一致;
 * 3. **同一时刻只有一次推送在飞**(Mutex): 连点星级不该产生多个乱序请求, 否则服务端可能按
 *    乱序落盘(最终值取决于谁后到)。
 *
 * 这个类**不做重试队列的持久化**(那是 M3′ 的待重试面板): 它只保证"这一次改动有没有落盘"
 * 在同一会话里可见、可重试。
 */
class DecisionStore(private val api: ApiClient) {
    private val mutex = Mutex()

    /**
     * 本地意图, 按路径。**只用 `applyLocal` / 推送回报来写** —— 界面不许直接改它,
     * 否则"本地意图"与"同步状态"会各自漂移。
     */
    private val local = LinkedHashMap<String, Decision>()

    /** 没动过的路径也要能回答(**不是** null): 界面拿到的永远是一份完整的意图。 */
    fun get(path: String): Decision = local[path] ?: Decision(path)

    fun all(): List<Decision> = local.values.toList()

    /** 改过但电脑还没确认的那些 —— 纪律③的"未同步"就是这个集合。 */
    fun unsynced(): List<Decision> = local.values.filter { it.sync == SyncState.UNSYNCED }

    /**
     * 用户改了一下(星级/色标): 立即生效(乐观更新), 并把它标成**未同步**。
     *
     * 即使值没变(又点了一次同一颗星)也标未同步: 本地这份意图**确实**还没被电脑确认过。
     * 随后的推送里服务端会回 `skipped`(changed=false 不写), 那时再落成已同步。
     */
    fun applyLocal(d: Decision) {
        local[d.path] = d.copy(sync = SyncState.UNSYNCED, lastError = null)
    }

    /** 用户看过了那条失败提示(本地改动与"未同步"标记都留着, 只是不再重复唠叨)。 */
    fun clearError(path: String) {
        local[path]?.let { local[path] = it.copy(lastError = null) }
    }

    /**
     * 把这些路径上的本地意图推给电脑。
     *
     * 返回**服务端说了什么**, 而不是"请求发出去了没有" —— 这里的每一次分支都对应一次
     * 用户可能被误导的机会, 所以逐条都在测试里钉住。
     */
    suspend fun push(paths: List<String>): PushResult = mutex.withLock {
        val requested = paths.distinct()
        if (requested.isEmpty()) return@withLock PushResult.NothingToPush(0)
        val items = requested.mapNotNull { local[it] }
        if (items.isEmpty()) return@withLock PushResult.NothingToPush(requested.size)
        send(requested, items)
    }

    private suspend fun send(requested: List<String>, items: List<Decision>): PushResult {
        // 发出去的那一份**逐字留档**: 回报回来时要拿它比对本地有没有又改过(见 afterAck)。
        val sent = items.map { it.path to it.toDto() }
        return try {
            settle(requested, sent, api.writeDecisions(sent.map { it.second }))
        } catch (e: ApiException) {
            // HTTP 错误体的 code 查 §5.3 那张表(由 FailureSource.HTTP 指定, 不靠猜)。
            wholeBatchFailed(requested, sent, e.code, FailureSource.HTTP)
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把"用户退出了这一页"说成网络故障。
            currentCoroutineContext().ensureActive()
            wholeBatchFailed(requested, sent, apiCodeFor(e), FailureSource.CLIENT)
        }
    }

    /**
     * 服务端回了一个 `WriteSummary` 之后, 把逐项结果落到状态上。
     *
     * 三处**绝不能算成功**的情形都在这里挡住(它们都是"服务端没确认每一项"的不同形态):
     *   ① `failed > failures[].size` —— 说有几项失败, 却没说是哪几项;
     *   ② `written + skipped + failed < 发出去的项数` —— 有几项的结果根本没被回报
     *      (服务端逐项计数, 三项之和就是输入项数, 所以对不上就是漏报);
     *   ③ 请求里有**手机上根本没有本地改动**的路径 —— 它们一个字节都没发出去。
     * 这三处一律记成 [WriteMessages.UNCONFIRMED]: 不是失败的原因不明, 而是**没有结论**。
     */
    private fun settle(
        requested: List<String>,
        sent: List<Pair<String, DecisionDto>>,
        summary: WriteSummaryDto,
    ): PushResult {
        val reported = summary.failures.map {
            WriteFailure(
                path = it.path,
                code = it.code,
                // ⚠️ 逐项失败的码是 **XMP 码集**, 不是 §5.3 的 HTTP 码集。
                source = FailureSource.XMP,
                detail = it.detail,
            )
        }
        val accounted = summary.written + summary.skipped + summary.failed
        val unsent = requested.filter { p -> sent.none { it.first == p } }
        val unexplained = (summary.failed - summary.failures.size).coerceAtLeast(0) +
            (sent.size - accounted).coerceAtLeast(0) + unsent.size

        // 归到具体路径上: 先排除服务端已经点名的那几个, 剩下的按请求顺序取。
        // (排除不掉时把 requested 再接一遍, 保证取够, 且不重复。)
        val named = reported.map { it.path }.toSet()
        val suspects = (requested.filter { it !in named } + requested).distinct().take(unexplained)
        val failures = reported + suspects.map {
            WriteFailure(path = it, code = WriteMessages.UNCONFIRMED, source = FailureSource.CLIENT)
        }

        applyAck(requested, sent, failures, reported)

        return if (failures.isEmpty()) {
            PushResult.Ok(
                requested = requested.size,
                written = summary.written,
                skipped = summary.skipped,
                degraded = summary.degraded,
            )
        } else {
            PushResult.Failed(
                requested = requested.size,
                written = summary.written,
                skipped = summary.skipped,
                failures = failures,
                summaryConfirmed = true,
            )
        }
    }

    /** 整个请求没被确认(HTTP 错误码 / 网络断了): 每一项都算**没写成**, 各带同一个码。 */
    private fun wholeBatchFailed(
        requested: List<String>,
        sent: List<Pair<String, DecisionDto>>,
        code: String,
        source: FailureSource,
    ): PushResult {
        val failures = requested.map { WriteFailure(path = it, code = code, source = source) }
        applyAck(requested, sent, failures, failures)
        return PushResult.Failed(
            requested = requested.size,
            written = 0,
            skipped = 0,
            failures = failures,
            summaryConfirmed = false,
        )
    }

    private fun applyAck(
        requested: List<String>,
        sent: List<Pair<String, DecisionDto>>,
        failures: List<WriteFailure>,
        reported: List<WriteFailure>,
    ) {
        for ((path, dto) in sent) {
            val failure = failures.firstOrNull { it.path == path }
                // 服务端回报的路径是**规范化后**的形态(verbatim 前缀 `\\?\`、大小写),
                // 可能与手机发出去的字符串不同。一批只有一张时才能安全归位;
                // 一批多张时宁可少标一张, 也不能把失败记到别的照片头上。
                ?: if (requested.size == 1) failures.firstOrNull() ?: reported.firstOrNull() else null
            val next = afterAck(local[path], dto, failure) ?: continue
            local[path] = next
        }
    }
}
