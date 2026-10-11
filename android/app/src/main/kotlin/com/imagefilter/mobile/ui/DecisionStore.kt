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
)

/**
 * 一次**文件夹级决策读取**(`GET /api/v1/decisions?paths=…`)的结论。
 *
 * 与 [PushResult] 同一种形状、同一条理由: 三件不同的事不许混成一个布尔值 ——
 *   · [Merged] —— 服务端把问到的**每一张**都回报了;
 *   · [Partial] —— 200 但**没回报全**(少一条就算; 已经回来的那些是真值, 照常采纳, 但张数只能说
 *     "未统计" —— 与 [WriteMessages.UNCONFIRMED] 同一条"没有被确认就不许说知道"的纪律);
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

    /** 200 但没对上报回来的条目: [mismatched] 是**少报 + 多报**的条数之和。 */
    data class Partial(
        override val requested: Int,
        val merge: MergeOutcome,
        val mismatched: Int,
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
 * 这个类**不做重试队列的持久化**(那是 M3′ 的待重试面板): 它只保证"这一次改动有没有落盘"
 * 在同一会话里可见、可重试。
 *
 * ⚠️ 本类里**有两个镜像**, 它们的分工是本任务最容易出错的地方:
 *   · [local] —— **这台手机上的意图**(只有 `applyLocal` 与推送回报写它);
 *   · [remote] —— 电脑上**上一次读到的**值(只有 [mergeRemote] 写它, 且只在本地没动过时)。
 *   [get] 把两者合成一份"界面该看到什么", 顺序是 [local] 优先 —— 见 [mergeRemote] 的 KDoc。
 */
class DecisionStore(private val api: ApiClient) {
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
     * 没动过的路径也要能回答(**不是** null): 界面拿到的永远是一份完整的意图。
     *
     * ⚠️ 优先级仍然**只在这一行**: `local[path] ?: remote[path]`。这里只多做一件事 —— 把
     * [Decision.desktopRating] 如实算出来(它描述的是**电脑**, 与本地意图无关, 所以不能沿用
     * 传进来的那一份: 那是别人构造 Decision 时的默认值, 不是事实)。
     */
    fun get(path: String): Decision =
        (local[path] ?: remote[path] ?: Decision(path)).copy(desktopRating = desktopRatingOf(path))

    /** 电脑上这一张的星级 —— 只有**被服务端确认过**的路径才有资格给出结论(见 [known])。 */
    private fun desktopRatingOf(path: String): DesktopRating = when {
        path !in known -> DesktopRating.Unknown
        // `known` 里有它但 `remote` 里没有值 = 服务端**明确回报**了这一张没有星级。
        else -> remote[path]?.rating?.let { DesktopRating.Rated(it) } ?: DesktopRating.Unrated
    }

    /**
     * 这一张在电脑上是什么, **知道不知道**。
     *
     * 与 [get] 的 `rating == null` 是两件事: `null` 既可能是"服务端说没有"也可能是"不知道"。
     * 滑卡那一层就是靠它把两者分开的(见 `decideSwipe` 的保留那一支)。
     */
    fun knows(path: String): Boolean = path in known

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
        for (dto in read) {
            val path = dto.path
            // 没有路径的条目归不到任何一张照片上, 采纳它只会让 resolved() 多一条没人能查到的记录。
            if (path.isBlank()) continue
            // 本地对这张有没有意图 —— 只用来**数**, 不用来决定存不存(见类 KDoc 与 resolved()):
            // 优先级只在 get 那一处执法, 这里再挡一道就会变成"两个实现互相掩盖"。
            val localWins = local.containsKey(path)
            // 服务端回报了这一张 ⇒ 不管它有没有值, "电脑上这张是什么"从此刻起是**知道**的。
            // (少写这一行会让下面"两个 null"的那条分支变回"不知道", 降级口子就回来了。)
            known.add(path)
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
        return MergeOutcome(adopted = adopted, keptLocal = keptLocal, cleared = cleared)
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
        if (mismatched == 0) return RemotePull.Merged(requested.size, outcome)
        // 没回报到的那些路径同样**重新变成"不知道"**: 问了两张只回来一张时, 没回来的那张在
        // 电脑上是几星谁也不知道 —— 正是这种"部分回报"最容易让保留那一支赌输。
        forget(requested.filter { it !in got })
        return RemotePull.Partial(requested.size, outcome, mismatched)
    }

    /**
     * 这些路径**重新变成"不知道"**(这次读取没被确认)。
     *
     * 刻意只动知识([known]), **不动**本地意图、也**不动**上一次读到的远端值([remote]):
     * 读取失败没有资格改动用户的评分, 把已知的东西抹掉比留在那儿更坏 —— 只是"这是现在的事实"
     * 这句话不许再说了(见 [desktopRatingOf])。
     */
    private fun forget(paths: Collection<String>) {
        for (p in paths) known.remove(p)
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
        if (!desktopHoldsChange || now.toDto() == e.before.toDto()) {
            if (e.before.hasLocalIntent) local[e.path] = e.before else local.remove(e.path)
            return@withLock UndoResult.LocalOnly
        }
        // 电脑上已经是改动后的值 ⇒ 必须把 before 送回去。`before` 当时不是本地意图时, 它得先
        // 成为一条**未同步**的意图才发得出去(它是"我们现在要写的东西", 不是电脑上已有的值)。
        val target = if (e.before.hasLocalIntent) e.before else e.before.copy(sync = SyncState.UNSYNCED)
        local[e.path] = target
        UndoResult.Push(send(listOf(e.path), listOf(target)))
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
