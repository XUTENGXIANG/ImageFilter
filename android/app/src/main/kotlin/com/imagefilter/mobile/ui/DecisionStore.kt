// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.DecisionDto
import com.imagefilter.mobile.data.DecisionReadDto
import com.imagefilter.mobile.data.DecisionReadFailure
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.ReadMessages
import com.imagefilter.mobile.data.ReadSource
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
 * **电脑上**这一张的星级 —— 三个状态, 不是一个 `Int?`。
 *
 * 这是本文件里最贵的一条教训: `rating = null` 把**两种截然不同的事实**混成了一个值 ——
 *   · 服务端明确回报"这一张没有星级"([Unrated]);
 *   · 这台手机**不知道**(读取失败 / 这一张没被回报 / 从来没读过)([Unknown])。
 *
 * 为什么非分不可: 右滑(保留)的语义是"维持现状, 没有星级才给 3 星", 而写出去的
 * `rating: 3` 是一次**绝对赋值** —— 在 [Unknown] 下执行它, 就是拿"不知道"去赌电脑上
 * 没有 4/5 星; 赌输的代价是把用户在电脑上给的星级**静默降级**成 3(数据损失)。
 * 所以在 [Unknown] 下保留那一支**一个字节都不许写**(判据在 `decideSwipe`)。
 *
 * 注意它描述的是**电脑**, 不是这台手机上的意图: 只有 [DecisionStore] 读回来的东西才能让它
 * 不是 [Unknown](本地打分/标色与它无关, 见 [Decision.desktopRating])。
 */
sealed interface DesktopRating {
    /** 服务端明确回报了这一张**没有**星级(读过, 而且读了它)。 */
    data object Unrated : DesktopRating

    /** **不知道**: 读失败 / 这一张没被回报 / 从来没读过。 */
    data object Unknown : DesktopRating

    /** 服务端明确回报了这一张的星级(`0` = 废弃)。 */
    data class Rated(val stars: Int) : DesktopRating
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
    /**
     * 电脑上这一张的星级([DesktopRating])。
     *
     * 默认 [DesktopRating.Unknown] = **不知道**, 而不是"没有星级" —— 这样"忘了带上这个信息"
     * 的后果是"少写一次"(保守), 而不是"静默降级"(数据损失)。
     * [DecisionStore.get] 每次都会按它**唯一**的那份知识重算这一个字段(见那里的 KDoc)。
     */
    val desktopRating: DesktopRating = DesktopRating.Unknown,
    /**
     * 上面那份知识是不是**刚被服务端确认写入**得到的(而不是"上一次读到"的)。
     *
     * FIX 3: 这两件事在数值上可能一样, 但**时态不同** —— 打完分之后那句话必须说
     * "电脑上：4★(刚被确认写成)", 而不是继续说"上次读到：0★"(用户会读成"我这一下没生效")。
     * 判据仍然只有一处([DecisionStore] 的 `written`), 界面一个字节都不自己拼。
     */
    val desktopRatingFromWrite: Boolean = false,
) {
    /**
     * 这一份是不是**用户在这台手机上明确说过的意图**。
     *
     * 判据是同步状态: 远端读回来的那份永远是 [SyncState.UNTOUCHED], 而本地意图只可能是
     * [SyncState.UNSYNCED] / [SyncState.SYNCED](由 [DecisionStore.applyLocal] 与回报落定)。
     * 撤销要恢复的是"之前那份本地意图" —— 之前**没有**本地意图与之前有一份(哪怕它是
     * [SyncState.SYNCED])是两种不同的恢复动作(见 [DecisionStore.undo])。
     */
    val hasLocalIntent: Boolean get() = sync != SyncState.UNTOUCHED

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

    /**
     * 这一份意图**有没有能写出去的字段** —— 与服务端 `write_one` 的短路口逐字对应:
     * `rating.is_none() && label.is_none() && !clear_label` ⇒ 服务端直接回 `Skipped`,
     * **连边车文件都不会打开**(`src-tauri/src/xmp.rs` 的第一行)。
     *
     * ⚠️ 为什么它必须是一个显式判据: 服务端的 `skipped` 在本项目里被读成"电脑上已经是这个值"
     * (那是**幂等成功**)。于是一条"什么都没要求写"的意图会安静地变成"已同步"、出待重试队列、
     * 角标清 0 —— 一次**从没被解决**的本地/电脑分歧就这样消失了, 而界面上说它成功了。
     * 所以这种项: 不发([DecisionStore.push])、面板上如实说([pendingTargetText])。
     */
    val hasWritableField: Boolean get() = rating != null || label != null || clearLabel
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
 * 一次撤销**要发给电脑**的那一份 —— 把电脑上"改动后的值"([now])改回改动前那一份([before])。
 *
 * 为什么不能直接把 `before` 发出去: 协议里 `rating = null` / `label = null` 是**"不动这个属性"**,
 * 不是"清掉它"(见 [DecisionDto])。所以"撤销一次绝对赋值"与"撤销一个加上去的标签"都必须换成
 * **能表达出目标状态的**写法, 否则服务端判"什么都没要求写"而**整条跳过** —— 边车一个字节都不动,
 * 界面上却写着"已撤销"。两条替换用的都是本项目**已有**的表示法, 出处逐条可查:
 *
 *   · **星级 → `0`**: 项目里"没有星级"的可表示形态就是 `0`。服务端契约写着 "0 即清除星级"
 *     (`src-tauri/src/xmp.rs::DecisionWrite`); 桌面端读到 `0` 就把本地那个键删掉
 *     (`src/xmp.ts::mergeRemoteRatings`), 而桌面端自己的 Ctrl+Z 走的正是这条
 *     (`src/undo.ts::applyRatingPatch`, `prev` 是 `number`, "之前没评过"就是 `0`);
 *   · **色标 → `clearLabel = true`**: 删掉标签是显式的一次性命令(`[Decision.clearLabel]`,
 *     与 [Decision.withLabel] 同一条), 光发 `label = null` 只会让旧标签在下次读盘时复活。
 *
 * ⚠️ 判据是"**现在**电脑上带着一个 `before` 表达不出来的值", 与 `before` 是不是本地意图无关:
 * 一份 `{rating = null}` 的本地意图(例如"只加过标签"的那一份)后来被抬到 3 星, 电脑上同样是我们
 * 放上去的 3 星 —— 撤销它同样必须用 0 把那颗星拿掉, 否则那 3 星会在下次读盘时"复活"到屏幕上。
 *
 * 替换之后这一份**必然是可写的**([Decision.hasWritableField]): [DecisionStore.undo] 只在
 * "现在这份与 before 逐字不同"时才走到这里, 而 `sync = SYNCED` 的意图里 `clearLabel` 一定是 false
 * ([afterAck] 成功时会消费掉那条一次性命令)—— 所以差异必然落在 rating 或 label 上, 上面两条
 * 替换把这两种差异都变成了能写出去的形态。
 */
internal fun reverseTarget(now: Decision, before: Decision): Decision {
    var target = before
    if (now.rating != null && before.rating == null) target = target.copy(rating = REJECT_STARS)
    if (now.label != null && before.label == null && !before.clearLabel) target = target.copy(clearLabel = true)
    return target
}

/**
 * 一次**文件夹级**远端读取的合并结果。
 *
 * 为什么把三个计数做成返回值而不是"合完就算": **"本地意图赢了没有"必须是一个可断言的事实**。
 * 只断言"最后看到的星级是 4"是不够的 —— 那可能是"远端值被采纳了恰好也是 4"或者"整个合并根本没跑"
 * 蒙对的。三个计数说清每一类路径各归了谁。
 */
data class MergeOutcome(
    /** 远端值被采纳(本地对这张**没有任何意图**)。 */
    val adopted: Int,
    /** 远端值被**本地意图**挡住 —— 本任务核心不变量的落点, 这个数不许为 0 当本地有未同步改动时。 */
    val keptLocal: Int,
    /** 远端说这张什么都没有(rating/label 都是 null)且本地没动过 → 把之前合并进来的那条**清掉**。 */
    val cleared: Int,
    /**
     * 服务端**回报了这一张, 但它的边车读不动**([DecisionReadDto.sidecarReadable] = false)的条数。
     *
     * 它与上面三个数**不重叠**: 那几条既没被采纳、也没被清掉、也不算"本地赢了" —— 它们的值是
     * **不知道**, 所以 [mergeRemote] 会把它们从"知道"里去掉(见那里的 KDoc)。
     * 界面靠这个数把张数降级成"未统计": 拿一个不含它们的下界当"这个文件夹有几张 ≥3★"是一句谎话。
     */
    val unreadable: Int = 0,
)

/**
 * 一次**文件夹级决策读取**(`GET /api/v1/decisions?paths=…`)的结论。
 *
 * 与 [PushResult] 同一种形状、同一条理由: 三件不同的事不许混成一个布尔值 ——
 *   · [Merged] —— 服务端把问到的**每一张**都回报了;
 *   · [Partial] —— 200 但**没能把问到的每一张都变成"知道"**: 少回报一条算, 有项的边车读不动
 *     ([DecisionReadDto.sidecarReadable] = false)也算 —— 没变成知道的那几张, 电脑上是几星这台
 *     手机不知道, 所以张数只能说"未统计"(与 [WriteMessages.UNCONFIRMED] 同一条"没有被确认就
 *     不许说知道"的纪律); 已经回来的那些是真值, 照常采纳;
 *   · [Failed] —— 整个请求没被确认(HTTP 错误 / 网络断了): 远端值**一个都不采纳**, 而本地意图
 *     一个字节都不动(读取失败绝不允许碰它);
 *   · [NothingToRead] —— 手机上这些路径**没有照片**(空文件夹), 一个请求都没发出去。
 *
 * [requested] 逐字是**手机上问出去的张数**(不减去服务端报回来的), 与 [PushResult] 一致。
 */
sealed class RemotePull {
    abstract val requested: Int

    data class Merged(
        override val requested: Int,
        val merge: MergeOutcome,
    ) : RemotePull()

    /** 200 但**没能把每一张都读成事实**: [mismatched] 是少报 + 多报的条数之和, [unreadable] 是边车读不动的项数。 */
    data class Partial(
        override val requested: Int,
        val merge: MergeOutcome,
        val mismatched: Int,
        /** 服务端回报了、但它的边车读不动(`sidecarReadable = false`)的项数 —— 那些值**不知道**。 */
        val unreadable: Int = 0,
    ) : RemotePull()

    data class Failed(
        override val requested: Int,
        val failure: DecisionReadFailure,
    ) : RemotePull()

    data class NothingToRead(override val requested: Int) : RemotePull()
}

/**
 * 本地决策镜像 + 推送 + **文件夹级远端读取的合并**(规格 §4.3 / §11 三条纪律)。
 *
 * 1. **乐观更新**: 用户点星后界面立刻变 —— 网络往返不该让人等;
 * 2. **失败不回滚丢弃**: 保留本地值并**明确报告**失败。静默回滚会让用户以为"白打了一次分",
 *    静默吞掉更糟; 与规格的"保留本地改动 + 标未同步 + 手动重试"一致;
 * 3. **同一时刻只有一次推送在飞**(Mutex): 连点星级不该产生多个乱序请求, 否则服务端可能按
 *    乱序落盘(最终值取决于谁后到)。
 *
 * 这个类**不做重试队列的持久化** —— 那是 [PendingStore](M3′ Task 3): 它只保证"这一次改动有没有
 * 落盘"在同一会话里可见、可重试, 而**队列**负责"重启之后还在"。两者由 [pending] 这一根线连起来,
 * 连接点只有一处([persistPending]): 它们是同一件事的两半, 不是两套账。
 *
 * ⚠️ 本类里**有两个镜像**, 它们的分工是本任务最容易出错的地方:
 *   · [local] —— **这台手机上的意图**(只有 `applyLocal` 与推送回报写它);
 *   · [remote] —— 电脑上**上一次读到的**值(只有 [mergeRemote] 写它, 且只在本地没动过时)。
 *   [get] 把两者合成一份"界面该看到什么", 顺序是 [local] 优先 —— 见 [mergeRemote] 的 KDoc。
 */
class DecisionStore(
    private val api: ApiClient,
    /**
     * **持久化的待重试队列**(M3′ Task 3)。它把"用户已经做出的决定"从"这个进程的内存"里
     * 搬到了磁盘上: 关着电脑打的分, 明天重启 App 还在。
     *
     * 默认 `null` 只是给**不需要落盘的单测**留的口子(那些用例测的是"这一次写成了没有",
     * 与磁盘无关)。⚠️ 生产路径**必须**传(见 [GridViewModel]): 少了它, 队列永远是空的,
     * 而界面上那个角标会显示 0 —— 一句安静的假话。
     */
    private val pending: PendingStore? = null,
) {
    private val mutex = Mutex()

    /**
     * 本地意图, 按路径。**只用 `applyLocal` / 推送回报来写** —— 界面不许直接改它,
     * 否则"本地意图"与"同步状态"会各自漂移。
     */
    private val local = LinkedHashMap<String, Decision>()

    /**
     * 电脑上读到的值, 按路径。**只有 [mergeRemote] 写它**。
     *
     * 与 [local] 分开存的理由不是洁癖, 是三条真实的行为差异:
     *   · [push] 只读 [local] —— 远端值**永远不会**被当成"用户改过的东西"再发回电脑
     *     (那会是一次没有用户的写入, 纪律①"绝不静默覆盖"最怕的就是它);
     *   · [unsynced] 只读 [local] —— 远端值不可能混进"未同步 N 张"这个角标里;
     *   · [get] 的 `local[path] ?: remote[path]` 是**唯一**的优先级执法点(见 [mergeRemote])。
     *
     * ⚠️ 远端值**照存不误**, 哪怕本地对同一张有意图(那时它被 [get] 挡住)。刻意如此:
     * 优先级若在"存"与"读"两处各执法一遍, 两处就会互相掩盖 —— 任何单点修改都测不出来。
     */
    private val remote = LinkedHashMap<String, Decision>()

    /**
     * **服务端确认过**的路径(回报里有它, 哪怕它没有星级/色标)。
     *
     * 这是"电脑上这一张是什么"的知识本身, 而 [remote] 只是那份知识**有值时**的镜像 ——
     * 两者必须分开, 否则"服务端回报了这一张没有星级"与"这台手机没读到这一张"又会混成一个
     * "查不到"([remote] 里两者都没有条目):
     *   · [mergeRemote] 把回报到的路径**加进来**(有值没值都算知道);
     *   · [forget] 把"读取没被确认"的路径**去掉**(失败 / 部分回报里没回报的那些)——
     *     没被确认就不许继续拿上一次的结论充数(与纪律③"未同步 ≠ 已同步"同一条)。
     *
     * [remote] 里的值**照旧不因为 [forget] 被抹掉**(把已知的东西抹掉比留在那儿更坏):
     * 界面照样显示上一次读到的值, 但 [Decision.desktopRating] 会如实变成
     * [DesktopRating.Unknown] —— "显示上一次的值"与"知道现在是什么"是两件事。
     */
    private val known = LinkedHashSet<String>()

    /**
     * **服务端确认写入过**的星级: 路径 → 我们写进去的那个值。
     *
     * FIX 3 的那条知识。它与 [remote] 分开存, 因为它们的**时态不同**:
     *   · [remote] 说的是"上一次**读到**电脑上是什么";
     *   · 这一份说的是"上一次**写进去**的是什么, 而且服务端回执确认了"。
     *
     * 为什么它算"知道"(而不是"我们自己猜的"): 一次 `WriteSummary` 里 `written + skipped`
     * 把这一项**记在了账上**(`skipped` = 电脑上本来就是它), 而 [settle] 会把"没被记上账"的
     * 那些项逐条标成 [WriteMessages.UNCONFIRMED] —— 只有真的落了账的路径才会走到
     * [confirmWritten]。这与"读到的值"是同一等级的**被确认过的事实**, 所以:
     *   · 界面可以说"电脑上：4★"(时态由 [Decision.desktopRatingFromWrite] 带出去);
     *   · 保留(右滑)那一支不再把"我们刚写过 0 星"当成"不知道"而拦下来。
     *
     * 它被**更晚的、被确认过的事实**改写, 两条路:
     *   · [mergeRemote]: 一次成功的读取是更晚的事实(哪怕读到的值与它不同);
     *   · [confirmWritten]: 又一次被确认的写入。
     * 而**没被确认**的事(读取失败 / 边车读不动)会把它连同 [known] 一起清掉
     * ([forget]) —— "没被确认就不许继续拿上一次的结论充数"这条纪律对写入同样成立。
     */
    private val written = LinkedHashMap<String, Int>()

    /**
     * 没动过的路径也要能回答(**不是** null): 界面拿到的永远是一份完整的意图。
     *
     * ⚠️ 优先级仍然**只在这一行**: `local[path] ?: remote[path]`。这里只多做一件事 —— 把
     * [Decision.desktopRating] 与它的时态([Decision.desktopRatingFromWrite])如实算出来
     * (它描述的是**电脑**, 与本地意图无关, 所以不能沿用传进来的那一份: 那是别人构造
     * Decision 时的默认值, 不是事实)。
     */
    fun get(path: String): Decision =
        (local[path] ?: remote[path] ?: Decision(path)).copy(
            desktopRating = desktopRatingOf(path),
            // 时态与数值分开带出去: 数值一样的两份知识("读到 4"与"写进去 4")必须说成两句不同的话。
            desktopRatingFromWrite = written.containsKey(path),
        )

    /**
     * 电脑上这一张的星级 —— 只有**被服务端确认过**的路径才有资格给出结论(见 [known] / [written])。
     *
     * 顺序要紧: **被确认的写入比更早的读取新**, 所以它先说; 两者都没有才轮到 [remote] 里
     * 上一次读到的值。
     */
    private fun desktopRatingOf(path: String): DesktopRating {
        written[path]?.let { return DesktopRating.Rated(it) }
        return when {
            path !in known -> DesktopRating.Unknown
            // `known` 里有它但 `remote` 里没有值 = 服务端**明确回报**了这一张没有星级。
            else -> remote[path]?.rating?.let { DesktopRating.Rated(it) } ?: DesktopRating.Unrated
        }
    }

    /**
     * 这一张在电脑上是什么, **知道不知道**。
     *
     * 与 [get] 的 `rating == null` 是两件事: `null` 既可能是"服务端说没有"也可能是"不知道"。
     * 滑卡那一层就是靠它把两者分开的(见 `decideSwipe` 的保留那一支)。
     */
    fun knows(path: String): Boolean = path in known

    /**
     * 服务端**回报过**的路径(有值没值都算"知道") —— 界面用它把"确知没有星级"与"不知道"分开画。
     *
     * 界面手里只有 [resolved] 那份合成结果, 而那里**没有值**的路径与**不知道**的路径看起来
     * 一模一样(都是查不到): 一次失败的读取因此会把一张 4/5 星的照片画成一排空星星
     * (与"没评过"无法分辨)。这一份知识是那个区别的**唯一**来源(见 `GridState.ratingKnown`)。
     */
    fun knownPaths(): Set<String> = known.toSet()

    fun all(): List<Decision> = local.values.toList()

    /** 改过但电脑还没确认的那些 —— 纪律③的"未同步"就是这个集合。 */
    fun unsynced(): List<Decision> = local.values.filter { it.sync == SyncState.UNSYNCED }

    /**
     * 合并之后的**每一张**(本地意图优先, 其余用远端值) —— 筛选与张数统计的输入。
     *
     * ⚠️ 刻意**逐条走 [get]**: 优先级只在 `get` 那一行里决定, 这里不再写第二遍 ——
     * 两处各写一遍的话, 改错一处就会让"网格里显示 4 星、查看器里显示 2 星"这种分裂复活,
     * 而且**任何单点修改都测不出来**(两个实现互相掩盖)。这也是本任务核心不变量的执法点。
     *
     * 只包含"本地动过或远端读到过"的路径: 两者都没有的路径本来就没有决策可言
     * ([get] 会给一份空的 [Decision])。
     */
    fun resolved(): Map<String, Decision> {
        val paths = LinkedHashSet<String>().apply {
            addAll(remote.keys)
            addAll(local.keys)
        }
        return paths.associateWith { get(it) }
    }

    /**
     * 把一次**文件夹级**远端读取合并进本地。
     *
     * ⚠️ **本地意图优先, 这条规则没有例外** —— 只要用户在这台手机上对某一张说过话
     * ([local] 里有它), 远端值就**不许**覆盖它:
     *
     *   · `UNSYNCED`(**本任务的核心不变量**): 用户离线打了 4 星, 电脑上还是 2 星。采纳远端
     *     就等于把用户**还没发出去**的决定静默删掉 —— 这是纪律③("未同步 ≠ 已同步")最贵的
     *     一种失败: 他会看到星星自己变回 2, 而且没有任何提示。
     *   · `SYNCED`: 服务端确认过的那一版同样不许被一次**更早发出的**读取翻回去。"刷新"(读)与
     *     "打分"(写)是并发的两条链, 旧快照回来时会把屏幕上已经落定的值改成旧的 —— 同一件事的
     *     另一面, 所以一并挡住。
     *   · `UNTOUCHED` 的本地条目不存在([applyLocal] 一律标 [SyncState.UNSYNCED]), 所以"没动过"
     *     就只有"远端有值"这一种形态。
     *
     * **执法点在 [get] 那一行**(`local[path] ?: remote[path]`); 这里只负责如实**数**出
     * 挡住/采纳/清掉各几条([MergeOutcome])—— 那三个数就是"本地赢了没有"的可断言形态。
     * (若在"存"这一侧再挡一道, 两个实现就会互相掩盖: 任何单点修改都测不出来。)
     *
     * 代价是**明说的**: 电脑上后来改过、而这台手机上也改过的那一张, 在同一个 store 活着的时候
     * 会以本地为准; 想拿电脑上的最新值就重新进这个文件夹(store 会重建并重新读一次)。
     *
     * 远端报 `rating`/`label` 都是 `null`(电脑上确实没有星级/色标)时**不是**"采纳一个空值",
     * 而是把之前合并进来的那条清掉 —— 否则电脑上清掉的评分会在这台手机的角标上留着。
     */
    fun mergeRemote(read: List<DecisionReadDto>): MergeOutcome {
        var adopted = 0
        var keptLocal = 0
        var cleared = 0
        var unreadable = 0
        for (dto in read) {
            val path = dto.path
            // 没有路径的条目归不到任何一张照片上, 采纳它只会让 resolved() 多一条没人能查到的记录。
            if (path.isBlank()) continue
            // ⚠️ **边车读不动** ⇒ 这一项里的两个 null **不是**"电脑上没有值"的证据(它们是
            // "不知道")。这一条与"读取失败"必须走**同一条**路: 把那一路径从"知道"里去掉,
            // 于是 [get] 出来的 desktopRating 是 [DesktopRating.Unknown] —— 保留(右滑)不写、
            // 界面画"未知"、一次打星要先确认, 全都不用另写一套判据。
            // (远端上一次读到的值**照旧留着显示**: 把已知的东西抹掉比留在那儿更坏。)
            if (dto.sidecarReadable == false) {
                forget(listOf(path))
                unreadable++
                continue
            }
            // 本地对这张有没有意图 —— 只用来**数**, 不用来决定存不存(见类 KDoc 与 resolved()):
            // 优先级只在 get 那一处执法, 这里再挡一道就会变成"两个实现互相掩盖"。
            val localWins = local.containsKey(path)
            // 服务端回报了这一张 ⇒ 不管它有没有值, "电脑上这张是什么"从此刻起是**知道**的。
            // (少写这一行会让下面"两个 null"的那条分支变回"不知道", 降级口子就回来了。)
            known.add(path)
            // ⚠️ **一次成功的读取是比一次写入更晚的事实** ⇒ 它作废"上一次写进去的那份"。
            // 少了这一行, 用户在电脑上改过的值会被我们上一次写入的回声一直盖住(那就是
            // FIX 3 里"另一个方向"的那半句: 这句话不许往两边任何一边过期)。
            written.remove(path)
            if (dto.rating == null && dto.label == null) {
                if (remote.remove(path) != null) cleared++
                continue
            }
            remote[path] = Decision(
                path = path,
                rating = dto.rating,
                label = dto.label,
                // 电脑上的值**不是**这台手机上的意图: 它没有"未同步"这回事(纪律③),
                // 也不该让界面去标一个未同步角标。
                sync = SyncState.UNTOUCHED,
            )
            if (localWins) keptLocal++ else adopted++
        }
        return MergeOutcome(adopted = adopted, keptLocal = keptLocal, cleared = cleared, unreadable = unreadable)
    }

    /**
     * 读一次**文件夹级**的决策并合并(规格 §5.1 的 `GET /decisions?paths=…`)。
     *
     * 失败**绝不清空本地意图**(读取是只读操作, 它没有任何资格改动用户的评分), 也绝不清空
     * 上一次读到的远端值 —— 把已知的东西抹掉比留在那儿更坏。失败本身由返回值如实带出去,
     * 由界面显示成状态([DecisionReadFailure])。
     *
     * 401 与其它链路同一条纪律: **第一次 401 绝不当成"token 被轮换"**, 必须复探消歧
     * ([ApiClient.classifyUnauthorized])。
     */
    suspend fun pullRemote(paths: List<String>): RemotePull {
        val requested = paths.distinct()
        if (requested.isEmpty()) return RemotePull.NothingToRead(0)
        return try {
            merge(requested, api.readDecisions(requested))
        } catch (e: ApiException) {
            // 整个请求没被确认 ⇒ 这些路径**重新变成"不知道"**(上一次读到的值照旧留着显示,
            // 但不再冒充"现在是什么" —— 保留那一支就是靠这条不许写)。
            forget(requested)
            RemotePull.Failed(
                requested = requested.size,
                // HTTP 层的码查 §5.3 那张表(由 ReadSource.HTTP 指定, 不靠猜)。
                failure = DecisionReadFailure(
                    code = e.code,
                    source = ReadSource.HTTP,
                    cause = if (e.isUnauthorized) api.classifyUnauthorized() else null,
                ),
            )
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把"用户退出了这一页"说成网络故障。
            currentCoroutineContext().ensureActive()
            forget(requested)
            RemotePull.Failed(
                requested = requested.size,
                failure = DecisionReadFailure(
                    code = apiCodeFor(e),
                    source = ReadSource.CLIENT,
                ),
            )
        }
    }

    private fun merge(requested: List<String>, read: List<DecisionReadDto>): RemotePull {
        val outcome = mergeRemote(read)
        val asked = requested.toSet()
        val got = read.map { it.path }.toSet()
        // 逐条对账(与 WriteSummary 的"三项之和必须等于输入项数"同一条纪律): 多一条少一条都算
        // "没对上" —— 这一份读回来的东西是我们对"电脑上这个文件夹现在是什么样"的**全部**依据。
        val mismatched = requested.count { it !in got } + got.count { it !in asked }
        if (mismatched == 0 && outcome.unreadable == 0) return RemotePull.Merged(requested.size, outcome)
        // 没回报到的那些路径同样**重新变成"不知道"**: 问了两张只回来一张时, 没回来的那张在
        // 电脑上是几星谁也不知道 —— 正是这种"部分回报"最容易让保留那一支赌输。
        if (mismatched > 0) forget(requested.filter { it !in got })
        return RemotePull.Partial(requested.size, outcome, mismatched, outcome.unreadable)
    }

    /**
     * 这些路径**重新变成"不知道"**(这次读取没被确认)。
     *
     * 刻意只动知识([known] 与 [written]), **不动**本地意图、也**不动**上一次读到的远端值
     * ([remote]): 读取失败没有资格改动用户的评分, 把已知的东西抹掉比留在那儿更坏 ——
     * 只是"这是现在的事实"这句话不许再说了(见 [desktopRatingOf])。
     *
     * [written] 一起清掉的理由与 [known] 完全一样: 一次没被确认的读取**证明不了**电脑上现在
     * 还是我们上次写进去的那个值(边车可能被别的程序动过), 所以宁可口径退回"不知道",
     * 也不许继续拿上一次的结论充数。
     */
    private fun forget(paths: Collection<String>) {
        for (p in paths) {
            known.remove(p)
            written.remove(p)
        }
    }

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
     * **放弃**这一张的本地意图(M3′ Task 3 的「放弃本地改动」, 界面上必须**二次确认**)。
     *
     * 一个请求都不发: 电脑上从来没有过这份改动(它还待在待重试里就说明没写成), 所以"放弃"就是
     * 把它从这台手机上抹掉。与 [undo] 的差别是**相反**的两种动作: 撤销恢复**之前那一份**意图,
     * 放弃是"这一张在这台手机上没有意图"。
     *
     * ⚠️ 正在飞的写入落定时会发现 `local[path]` 已经不在了([afterAck] 对 `null` 直接跳过),
     * 所以它**绝不会**把刚刚被放弃的东西复活 —— 那条路径上不会留下任何"半个意图"。
     */
    fun discardLocal(path: String) {
        local.remove(path)
    }

    /**
     * **撤销一步**: 把"改动之前那份本地意图"恢复回去(规格 §8.2 的多步撤销)。
     *
     * 三条纪律, 每一条都对应一种会撒谎的实现:
     *
     *   ① **恢复的是"之前那份本地意图", 而且是逐字的** —— 连同它当时的同步状态一起。
     *      把一条 [SyncState.SYNCED] 的旧意图重新标成"未同步"就是凭空造一个待重试项;
     *      把一条"之前根本没有本地意图"恢复成一条本地意图, 就是凭空给角标加一张。
     *      (判据 [Decision.hasLocalIntent]: 远端读回来的那份永远是 [SyncState.UNTOUCHED]。)
     *   ② **只有电脑上确实收到过那一次改动时, 才需要一次反向写入**。判据是那条本地意图
     *      当前**被服务端确认过**([SyncState.SYNCED]) —— 只有那时电脑上才是"改动后的值",
     *      不写回去就等于让界面(与角标)继续显示一个与电脑不一致的值。
     *      否则(写失败 / 还没发出去)电脑上**本来就还是老样子**, 发一次反向写入就是一次
     *      **服务端没有被请求过**的写入 —— 一个字节都不发。
     *   ②′ **反向写入必须表达得出目标状态, 而且要真的让电脑发生变化**([reverseTarget] 与
     *      [UndoResult.NotApplied]): 协议里 `null` = "不动这个属性", 所以"撤销一次绝对赋值"
     *      必须显式写 `0`、"撤销一个加上去的标签"必须显式发 `clearLabel = true`; 而被服务端
     *      **整条跳过**的反向写入(written = 0, skipped > 0)证明不了撤销成功, 不许说"已撤销"。
     *   ③ 写没写成**只由服务端说了算**: 反向写入走的是与打分同一条 [PushResult] 链路,
     *      失败时本地值留着、标未同步、带码可见(与 [push] 逐字同一套)。
     *
     * 撤销**本身不再进撤销栈**(没有重做): 滑卡的场景里"再滑一次"比"撤销再撤销"快
     * (见 [UndoStack] 的 KDoc)。
     */
    suspend fun undo(e: UndoEntry): UndoResult = mutex.withLock {
        val now = local[e.path]
        // 电脑上那份"改动后的值"被服务端确认过吗 —— 只有它才能说明"电脑上真的被改过"。
        val desktopHoldsChange = now != null && now.sync == SyncState.SYNCED
        // "电脑上已经是 before 这个值"时也不必写(例如在 5 星上又点了一次 5 星, 服务端判 skipped):
        // 逐字比对**要发出去的那一份**, 而不是"值看起来差不多"。
        if (now == null || !desktopHoldsChange || now.toDto() == e.before.toDto()) {
            if (e.before.hasLocalIntent) local[e.path] = e.before else local.remove(e.path)
            // 本地意图刚变了(可能是"恢复成之前那份未同步的", 也可能是"这一张没有本地意图了")
            // ⇒ 持久队列跟着对齐。少了这一行, 撤销之后的队列会与屏幕上看到的对不上。
            persistPending()
            return@withLock UndoResult.LocalOnly
        }
        // 电脑上已经是改动后的值 ⇒ 必须把 before 送回去。
        //
        // ⚠️ 送回去的必须是**能表达出目标状态**的反向写入([reverseTarget]): 协议里的 `null` 是
        // "不动这个属性", 拿 `rating: null` 去撤销一次绝对赋值, 服务端会**连文件都不打开**地跳过它
        // (`xmp.rs::write_one` 的短路口) —— 边车里的 3 星原封不动, 而界面说"已撤销"。
        //
        // 目标一律标**未同步**: 这一次反向写入还没被任何回报确认过。`before` 自己那份 sync 说的是
        // 它**过去**的状态, 照抄它会让写失败时本地冒充"已同步"、而电脑上还是改动后的值。
        val target = reverseTarget(now, e.before).copy(sync = SyncState.UNSYNCED)
        local[e.path] = target
        val result = send(listOf(e.path), listOf(target))
        // ⚠️ 服务端回报这一项**什么都没改**(written = 0 且 skipped > 0): 电脑上一个字节都没变。
        // 撤销的全部意义就是"让电脑上变回改动前那个值", 所以一个没让电脑发生任何变化的写入
        // **证明不了**撤销成功 —— 它既不许落成已同步, 也不许说"已撤销", 而必须把分歧留在界面上
        // (本地这一版留着、标着未同步、带一条说清原因的码, 于是角标与失败卡都看得见)。
        if (result is PushResult.Ok && result.written == 0 && result.skipped > 0) {
            val settled = local[e.path]
            // 写入在飞的时候用户又改过这一张 ⇒ 那由 afterAck 处理, 这里不许覆盖更新的那一版。
            if (settled != null && settled.sync == SyncState.SYNCED) {
                local[e.path] = target.copy(
                    sync = SyncState.UNSYNCED,
                    lastError = WriteFailure(
                        path = e.path,
                        code = WriteMessages.NOTHING_CHANGED,
                        source = FailureSource.CLIENT,
                    ),
                )
                persistPending()
            }
            return@withLock UndoResult.NotApplied(result)
        }
        UndoResult.Push(result)
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
        // 请求**发出去之前**先把意图落盘: 请求还在飞的时候进程被系统杀掉, 用户的决定也不该丢
        // (这是"半离线承诺"的落点 —— 决定一做出就有一份持久的副本)。
        persistPending()
        // ⚠️ **一个可写字段都没有**的那些([Decision.hasWritableField])一个字节都不发:
        // 服务端收到它们只会**整条跳过**(连边车都不打开), 而"跳过"在本类里等于"电脑上已经是这个值"
        // ⇒ 标已同步、出队列、角标清 0 —— 一次**从没被解决**的本地/电脑分歧就这么消失了。
        // 所以: 不写它、也不替它说成功, 如实记一条带码的失败并把它留在队列里(可重试, 可放弃)。
        val (dead, live) = items.partition { !it.hasWritableField }
        val deadFailures = dead.map {
            WriteFailure(path = it.path, code = WriteMessages.NOTHING_WRITABLE, source = FailureSource.CLIENT)
        }
        for (f in deadFailures) {
            local[f.path] = local.getValue(f.path).copy(sync = SyncState.UNSYNCED, lastError = f)
        }
        if (live.isEmpty()) {
            persistPending()
            return@withLock PushResult.Failed(
                requested = requested.size,
                written = 0,
                skipped = 0,
                failures = deadFailures,
                // 一个请求都没发出去 ⇒ 没有服务端的 WriteSummary 可转述("没被确认"的字面意思)。
                summaryConfirmed = false,
            )
        }
        // 调用方点名了、而 store 里**连本地意图都没有**的路径照旧交给 [send] 去算:
        // 它们会走 settle 那条"没发出去"的口子, 记成 [WriteMessages.UNCONFIRMED]
        // (与本次改动之前逐字同一条 —— 少报一条就是"这一项的结果没人说得出")。
        val notLocal = requested.filter { local[it] == null }
        withDeadFailures(send(live.map { it.path } + notLocal, live), deadFailures, total = requested.size)
    }

    /**
     * 把"表达不出来"的那几条如实接在一次写入结论后面。
     *
     * 它们既不是成功, 也不是传输失败 —— 它们**根本没被发出去**。所以一律并成
     * [PushResult.Failed] 的逐项失败(各带 [WriteMessages.NOTHING_WRITABLE]), 而且
     * [PushResult.Failed.requested] 报**用户点名的那一批**的项数(不是实际发出去的项数)。
     */
    private fun withDeadFailures(
        result: PushResult,
        extra: List<WriteFailure>,
        total: Int,
    ): PushResult {
        if (extra.isEmpty()) return result
        val failed = result as? PushResult.Failed
        return PushResult.Failed(
            requested = total,
            written = failed?.written ?: (result as? PushResult.Ok)?.written ?: 0,
            skipped = failed?.skipped ?: (result as? PushResult.Ok)?.skipped ?: 0,
            failures = failed?.failures.orEmpty() + extra,
            summaryConfirmed = failed?.summaryConfirmed ?: (result is PushResult.Ok),
        )
    }

    /**
     * 把**持久待重试队列**与这里的本地意图对齐 —— 队列唯一的写入口(实现在
     * [PendingStore.reconcile])。
     *
     * 为什么放在这个类里: 只有它知道"电脑确认了什么"([applyAck] 是唯一改同步状态的地方)。
     * 放在界面那一层就意味着每一条写入链路(打星 / 标色 / 滑卡 / 撤销 / 重试)都得自己记得调一次
     * —— 漏一处就是"那一种操作在离线时不被持久化", 而且**看不出来**。
     *
     * 调用点三处, 覆盖全部写入链路: [push] 发请求之前、[send] 落定之后、[undo] 的"没发请求"
     * 那一支。没有接队列时(pending = null, 只给单测)整段是空操作。
     */
    private suspend fun persistPending() {
        pending?.reconcile(local.values)
    }

    private suspend fun send(requested: List<String>, items: List<Decision>): PushResult {
        // 发出去的那一份**逐字留档**: 回报回来时要拿它比对本地有没有又改过(见 afterAck)。
        val sent = items.map { it.path to it.toDto() }
        val result = try {
            settle(requested, sent, api.writeDecisions(sent.map { it.second }))
        } catch (e: ApiException) {
            // HTTP 错误体的 code 查 §5.3 那张表(由 FailureSource.HTTP 指定, 不靠猜)。
            wholeBatchFailed(requested, sent, e.code, FailureSource.HTTP)
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把"用户退出了这一页"说成网络故障。
            // (注意 ensureActive 会**往外抛**: 取消时下面那次对齐不会跑, 而那是**对的** ——
            //  这次写入没有结论, 队列里那一份必须留着。)
            currentCoroutineContext().ensureActive()
            wholeBatchFailed(requested, sent, apiCodeFor(e), FailureSource.CLIENT)
        }
        // 服务端说了什么之后, 队列跟着本地意图走: **确认了的出队列, 没确认的留在里面**
        // (留在里面的是用户还没落到电脑上的决定 —— 它必须活过一次重启)。
        persistPending()
        return result
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
            // 电脑确认了这**一版**意图([SyncState.SYNCED] 意味着服务端把这一项记在了账上:
            // `written` 或 `skipped`) ⇒ "电脑上这一张是几星"从此知道, 而且它比任何一次更早的
            // 读取都新(FIX 3)。写失败/没被回报的那些项一个字都不记 —— 那正是"没被确认就不许
            // 说知道"。
            if (next.sync == SyncState.SYNCED) confirmWritten(path, dto)
        }
    }

    /**
     * 记下"这一张的星级就是我们刚写进去的那个值, 而且服务端确认了"。
     *
     * ⚠️ **只在这一批真的碰了星级时才记**([DecisionDto.rating] 非 null): 一次只改色标的写入
     * 关于星级**什么都没告诉我们** —— 拿它去更新星级知识, 就是无中生有。
     *
     * [known] 一起加进来: [written] 里的路径本来就必须同时"知道", 两个集合各写一半会让
     * `knows()` 与 `desktopRating` 说出互相矛盾的话(一个说不认识、一个说 4 星)。
     */
    private fun confirmWritten(path: String, sent: DecisionDto) {
        val stars = sent.rating ?: return
        written[path] = stars
        known.add(path)
    }
}
