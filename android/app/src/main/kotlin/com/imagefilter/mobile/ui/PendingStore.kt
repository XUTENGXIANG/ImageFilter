// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException

// ── 为什么要一个**持久**的待重试队列(规格 §9 的"手机端待重试: DataStore, 量很小") ──────
//
// 写失败的东西如果只活在内存里, 用户切后台/重启就丢了 —— 而它代表的是**用户已经做出的决定**
// (他关着电脑打了 3 张分), 丢了就是数据损失。所以这一份必须落盘, 而且必须落成
// "电脑确认写成之前不许出队列"。
//
// 落盘用**已有的那套模式**(与 `data/PairingStore.kt` 逐字同一种形状):
//   · 一个 [PendingQueueStore] 接口 —— DataStore 要 `Context`(JVM 单测里没有), 而"队列里到底
//     还剩几项、重试会不会假装成功"这类**判定**必须能在 JVM 上真的跑一遍;
//   · 一个 DataStore 实现(真机路径) + 单测里的内存假件;
//   · **不引 Room**(规格决策 #8): 量很小, 一个字符串键就够。
//
// 落盘的**不是** [Decision] 本身, 而是它的持久投影 [PendingItem] —— 理由见那个类的 KDoc。

/**
 * 一个字符串键的落盘后端(与 `PairingPrefsStore` 同一种形状)。
 *
 * 它只说"读回来的是什么字符串 / 把它写进去", **不认识**队列的语义 —— 语义全在 [PendingStore]
 * 与那几个纯函数里, 所以它们能在 JVM 单测里被直接钉住。
 */
interface PendingQueueStore {
    suspend fun load(): String?

    suspend fun save(json: String)

    suspend fun clear()
}

/** DataStore 委托必须是**文件级**属性 —— 一个文件一个实例, 多建会撞 DataStore 的单例约束。 */
private val Context.pendingDataStore by preferencesDataStore(name = "pending")

/** 真机实现: `pending.preferences_pb` 里的一个字符串键(JSON)。里面**没有机密**(只有路径与评分)。 */
class DataStorePendingQueueStore(context: Context) : PendingQueueStore {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("queue")

    override suspend fun load(): String? = appContext.pendingDataStore.data.first()[key]

    override suspend fun save(json: String) {
        appContext.pendingDataStore.edit { it[key] = json }
    }

    override suspend fun clear() {
        appContext.pendingDataStore.edit { it.remove(key) }
    }
}

/**
 * 待重试的一项**落盘形态** —— 只有**用户说过的那些**与**最近一次失败**。
 *
 * ⚠️ 为什么不是直接把 [Decision] 存下来(它每个字段都存得起): [Decision] 里有三个字段说的是
 * **这一次会话知道什么** —— [Decision.sync] / [Decision.desktopRating] / [Decision.lastError]。
 * 重启之后那份知识**并不存在**(新的 [DecisionStore] 手上的 `known` 是空的, 一台电脑到底
 * 写了没写谁也不知道)。把它们一起存下来再读回去, 就等于让一份**已经不成立的知识**冒充事实:
 *   · 存回来的 `desktopRating = Rated(5)` 会让"保留绝不降级"那一支([decideSwipe] 规矩 2′)
 *     判定"电脑上有 5 星, 不用写" —— 而这次会话**根本不知道**电脑上是几星。
 *     一次静默的"以为不用写", 就是用户以为保住了、其实什么都没发生;
 *   · 存回来的 `sync = SYNCED` 更致命: 它能进队列就说明电脑**没确认过**它。
 * 所以落盘的只有意图本身, 读回来的一律重建为 [SyncState.UNSYNCED] + [DesktopRating.Unknown]
 * (见 [toDecision])。
 */
@Serializable
data class PendingItem(
    val path: String,
    /** `null` = 不动星级(与服务端 `DecisionWrite` 逐字同义, **不是** 0 星)。 */
    val rating: Int? = null,
    val label: String? = null,
    val clearLabel: Boolean = false,
    /** 最近一次失败的码与来源 —— 重启之后面板还得说得出"为什么"和"下一步做什么"。 */
    val failure: WriteFailure? = null,
)

/** 意图 → 落盘形态。 */
fun Decision.toPendingItem(): PendingItem =
    PendingItem(path = path, rating = rating, label = label, clearLabel = clearLabel, failure = lastError)

/**
 * 落盘形态 → 意图。
 *
 * 三个字段被**刻意重建**而不是沿用(见 [PendingItem] 的 KDoc): 能进队列的项一律是"电脑还没
 * 确认过的", 而"电脑上这一张是几星"在重启之后是**不知道**。
 */
fun PendingItem.toDecision(): Decision = Decision(
    path = path,
    rating = rating,
    label = label,
    clearLabel = clearLabel,
    sync = SyncState.UNSYNCED,
    lastError = failure,
    desktopRating = DesktopRating.Unknown,
)

/**
 * 队列 → 一个 JSON 字符串([PendingStore] 落盘时写的就是它)。
 *
 * 抽成纯函数的理由与 [DecisionStore.mergeRemote] 一样: "一份意图能不能原样往返"必须是
 * **可以真的断言**的事实, 而不是"我调了两个 API, 应该没问题"。
 */
fun encodePending(items: Map<String, Decision>): String =
    Json.encodeToString(items.mapValues { it.value.toPendingItem() })

/** [decodePending] 的三个结论 —— 空队列与"读不回来"**必须分得清**。 */
sealed interface PendingDecode {
    data class Ok(val items: Map<String, Decision>) : PendingDecode

    /** 从来没写过(全新的手机 / 队列一直是空的)。 */
    data object NotStored : PendingDecode

    /**
     * 写了, 但**读不回来**(解析不了 / 版本对不上 / 文件坏了)。
     *
     * 它与 [NotStored] 是两件完全不同的事: 前者是"没有待重试的改动", 后者是"**可能丢了**
     * 一部分待重试的改动" —— 混成一个空队列, 界面就会在数据可能已经丢了的时候说
     * "现在没有待重试的改动"(一句安静的假话)。
     */
    data object Corrupt : PendingDecode
}

/**
 * [encodePending] 的逆运算。**绝不抛**: 一个坏掉的队列文件不该让 App 在启动时崩掉,
 * 但它也**不许**被当成"队列是空的"(见 [PendingDecode.Corrupt])。
 */
fun decodePending(text: String?): PendingDecode {
    if (text.isNullOrBlank()) return PendingDecode.NotStored
    return try {
        val raw = Json.decodeFromString<Map<String, PendingItem>>(text)
        // 空路径归不到任何一张照片上(与 `mergeRemote` 同一条): 收下它只会让面板多一条
        // 没人能重试的行。
        PendingDecode.Ok(raw.filterKeys { it.isNotBlank() }.mapValues { it.value.toDecision() })
    } catch (e: SerializationException) {
        PendingDecode.Corrupt
    } catch (e: IllegalArgumentException) {
        PendingDecode.Corrupt
    }
}

/**
 * 队列的一次快照。**逐字是磁盘上那一份** —— 角标与面板都只能用它, 不许拿"本地未同步数"去猜
 * (两者在重启之后完全不同: 本地镜像是空的, 而队列里还有昨晚那三张)。
 */
data class PendingSnapshot(
    val items: Map<String, Decision>,
    /**
     * true = 队列**没能读回来**(解析不了 / 读不动)。面板与角标必须把这件事说出来 ——
     * 一次读失败不许被当成"没有待重试的改动"。
     */
    val corrupted: Boolean = false,
) {
    val count: Int get() = items.size
}

/**
 * **同一张照片只留最后一次意图**(本任务点名要钉住的一条)。
 *
 * 为什么非去重不可: 用户对同一张改了三次(3★ → 0★ → 5★+绿), 三条都留在队列里的话,
 * "全部重试"会发出**三个互相覆盖的请求** —— 最终落到电脑上的是哪一版取决于谁后到,
 * 而且那三次里前两次是用户已经改主意的旧决定。队列的货币是"**现在**要写什么", 不是流水账。
 *
 * 三条细节:
 *   · 同路径覆盖时**保持原来的位置**(`LinkedHashMap` 的语义) —— 面板的顺序因此是稳定的,
 *     不会因为重试一次就跳来跳去;
 *   · 空路径**不收**(与 `mergeRemote` 同一条: 归不到任何一张照片上的东西没法重试);
 *   · 纯函数: 覆盖的判据是"路径相同", 与"值相同"无关 —— 又点了一次同一颗星, 队列里仍然是
 *     要有那一条(它确实还没被电脑确认), 只是值一样而已。
 */
fun mergePending(prev: Map<String, Decision>, d: Decision): Map<String, Decision> {
    if (d.path.isBlank()) return prev
    val next = LinkedHashMap(prev)
    next[d.path] = d
    return next
}

/**
 * **持久化的待重试队列**(M3′ Task 3)。
 *
 * 唯一的真相来源是 [PendingQueueStore](磁盘); 内存里不缓存第二份 —— 缓一份就要处理"两份谁新"
 * 的问题, 而它正是这个项目反复吃过亏的地方。
 *
 * **并发**: 每一次读-改-写都在 [mutex] 里。这不是洁癖: 队列的整体形状就是一个字符串键,
 * 两个并发的 `add` 各自"读→合并→写"必然丢掉一条(用户的两张照片只留下一张)。
 *
 * 写入时机**不在这里**, 而在 [DecisionStore](每一次推送落定之后由它对齐, 见
 * `DecisionStore.persistPending`) —— 那是唯一知道"电脑确认了什么"的地方。
 */
class PendingStore(private val backend: PendingQueueStore) {

    private val mutex = Mutex()

    /** 读一份快照(**不碰网络**)。角标、面板、以及"重试谁"都以它为准。 */
    suspend fun snapshot(): PendingSnapshot = mutex.withLock { readLocked() }

    /** 队列里**此刻**待重试的那些, 按路径。 */
    suspend fun all(): Map<String, Decision> = snapshot().items

    /** 待重试的项数(角标就用它 —— 数的是"真的还没被电脑确认的", 不是猜的)。 */
    suspend fun count(): Int = snapshot().count

    /**
     * 记下一条**电脑还没确认**的意图(同路径以最新为准, 见 [mergePending])。
     *
     * 返回合并之后的队列, 让调用方能立刻把结果用上(而不必再读一次盘)。
     */
    suspend fun add(d: Decision): Map<String, Decision> = mutex.withLock {
        val next = mergePending(readLocked().items, d)
        backend.save(encodePending(next))
        next
    }

    /** 这一条**已经不需要重试了**(电脑确认了 / 用户放弃了)。 */
    suspend fun remove(path: String) = mutex.withLock {
        val cur = readLocked()
        if (cur.items.containsKey(path)) backend.save(encodePending(cur.items - path))
        Unit
    }

    /** 清空(目前只有测试与"重新配对"这类显式重置会用到)。 */
    suspend fun clear() = mutex.withLock { backend.clear() }

    /**
     * 把队列与"这台手机上还没被电脑确认的本地意图"对齐 —— **队列唯一的写入口**。
     *
     * 两条规则, 每一条都建立在**正面事实**上:
     *   · 本地这份意图是 [SyncState.UNSYNCED] ⇒ 进队列(同路径最新为准);
     *   · 本地这份意图是 [SyncState.SYNCED](电脑确认过) ⇒ 出队列。
     *
     * ⚠️ **队列里有、而 [local] 里根本没有那个路径时, 什么也不做**。把"没见到"当成"电脑确认了"
     * 是最容易写出来、也最贵的一种: 重启之后 [DecisionStore] 是空的, 那样一来整个队列会被
     * 一次推送静默清空 —— 用户昨晚关着电脑打的分就这么没了(而角标会显示 0, 一句假话)。
     *
     * [SyncState.UNTOUCHED] 的条目(理论上 `local` 里不会有, 但接口上可能出现)一并不动。
     */
    suspend fun reconcile(local: Collection<Decision>): PendingSnapshot = mutex.withLock {
        val cur = readLocked()
        var next = cur.items
        var changed = false
        for (d in local) {
            when (d.sync) {
                SyncState.UNSYNCED -> {
                    val merged = mergePending(next, d)
                    if (merged != next) {
                        next = merged
                        changed = true
                    }
                }

                SyncState.SYNCED -> if (next.containsKey(d.path)) {
                    next = next - d.path
                    changed = true
                }

                SyncState.UNTOUCHED -> Unit
            }
        }
        if (changed) backend.save(encodePending(next))
        // 写下去了就已经不是原来那份读不回来的内容了(丢掉的项找不回来, 但"现在盘上是什么"是明确的)。
        PendingSnapshot(next, corrupted = cur.corrupted && !changed)
    }

    /** 真的读一次盘。读不动 / 解析不了都落到 [PendingSnapshot.corrupted], **绝不往上抛**。 */
    private suspend fun readLocked(): PendingSnapshot = try {
        when (val decoded = decodePending(backend.load())) {
            is PendingDecode.Ok -> PendingSnapshot(decoded.items, corrupted = false)
            PendingDecode.NotStored -> PendingSnapshot(emptyMap(), corrupted = false)
            PendingDecode.Corrupt -> PendingSnapshot(emptyMap(), corrupted = true)
        }
    } catch (e: IOException) {
        // DataStore 读文件本身失败: 同样是"没读到", **不许**说成"没有待重试的改动"。
        PendingSnapshot(emptyMap(), corrupted = true)
    }

    companion object {
        /** 真机路径(与 [com.imagefilter.mobile.data.PairingStore.create] 同一种写法)。 */
        fun create(context: Context): PendingStore =
            PendingStore(DataStorePendingQueueStore(context.applicationContext))
    }
}

// ── 重试: 串行、一次一项、只发仍然待重试的 ─────────────────────────────────────────
//
// ⚠️ **为什么一次一项而不是把队列合成一批发出去**: 本项目在 M0′ Task 7 实测过一个坑 ——
// 半成功的那一项**源路径已经不存在**了, 把它一起塞进一批会让**整批 404**, 连本来能成的那几张
// 也一起废掉。这一批是决策(不是移动), 但同一个道理成立且更简单: 一张照片没了(改名/移走),
// 一次坏路径不该毒掉别人。一次一项是**串行**的, 也正好让"这一项成了没有"逐项落地。
//
// 移动那条链路(M0′ 的 pendingSidecars)本任务**一个字节都不碰** —— 它的重试通道
// (`MoveOutcomeDto.pendingSidecarRetry`)有自己的规则, 见那里的 KDoc。

/** 一项重试的结论 —— 逐项, 不是整批一个布尔值(与 [PushResult] 同一条形状)。 */
data class RetryAttempt(
    val path: String,
    val result: PushResult,
    /** true = 电脑**确认**写成了(只有那时这一项才真的从队列里出去了)。 */
    val cleared: Boolean,
)

/**
 * 一次「重试」/「全部重试」的结论。
 *
 * 四个桶各有各的意思, **一个都不许混**(混了就会有一边在撒谎):
 *   · [attempted] —— 真的发了请求的项数;
 *   · [synced] —— 电脑**确认**写成的路径([PushResult.Ok] **且**本地那一版就是被确认的那一版);
 *   · [failures] —— 发了但**没被确认**的逐项失败(200 但逐项失败 / 计数对不上 / 断网);
 *   · [inconclusive] —— **没有结论**的项: 一个请求都没发出去([PushResult.NothingToPush]),
 *     或者服务端确认的是**上一版**(写入还在飞的时候用户又改了一次) —— 它仍在队列里;
 *   · [notAttempted] —— 调用方点名要重试、但它**已经不在队列里**了(所以一个请求都没发)。
 *
 * 「还剩几项」不由这个报告算, 由 [PendingStore.snapshot] 如实数 —— 报告只转述这一轮发生了什么。
 */
data class RetryReport(
    val attempted: Int,
    val synced: List<String> = emptyList(),
    val failures: List<WriteFailure> = emptyList(),
    val inconclusive: List<String> = emptyList(),
    val notAttempted: List<String> = emptyList(),
) {
    /** 这一轮**没被电脑确认**、因此仍然待在队列里的项数。 */
    val stillQueued: Int get() = failures.size + inconclusive.size

    /** 这一轮真的把事情办完了吗(只有确认写成才算)。 */
    val allSynced: Boolean get() = attempted > 0 && failures.isEmpty() && inconclusive.isEmpty()
}

/**
 * 把队列里的意图**装回**本地镜像(队列是持久的那一份, [DecisionStore] 是会话内的那一份)。
 *
 * 两个调用点, 同一个实现:
 *   · 进文件夹时(网格要显示"你昨晚关着电脑打的那些分" —— 否则它会显示电脑上的旧值);
 *   · 重试某一项**之前**(否则 [DecisionStore.push] 会回 [PushResult.NothingToPush]:
 *     重启之后 store 是空的, 一条都发不出去 —— "全部重试"会变成一个什么都不做的按钮)。
 *
 * ⚠️ **本地已经有一份更新的意图时不动它**: 队列里那条是"之前那次写失败的那一版", 而 store 里
 * 这一份可能是用户刚刚又改过的 —— 用旧版覆盖新版就是把用户最新的决定丢掉。
 *
 * 返回真的装回去了几项(可断言, 也方便界面说"恢复了 N 项")。
 */
suspend fun rehydratePending(
    queue: PendingStore,
    decisions: DecisionStore,
    paths: List<String>? = null,
): Int {
    val pending = queue.snapshot().items
    val targets = paths?.distinct() ?: pending.keys.toList()
    var restored = 0
    for (path in targets) {
        val d = pending[path] ?: continue
        if (decisions.get(path).hasLocalIntent) continue
        decisions.applyLocal(d)
        restored++
    }
    return restored
}

/**
 * 重试待重试的那些。**串行**(一次一项, 见上面的说明), 而且**只发仍然待重试的**。
 *
 * "成不成功"只由服务端说了算, 判据是**落定之后的同步状态**([SyncState.SYNCED]) 而不是
 * [PushResult] —— 两者在这里只可能因为一件事不同: 请求发出去了、服务端也确认了, 但本地在
 * 这期间又被改过一次(写入还在飞的时候用户改的主意)。那时电脑上是**上一版**, 这一项**不算写成**、
 * 仍然留在队列里([RetryReport.inconclusive])。
 *
 * ⚠️ 从队列里去掉这件事由 [DecisionStore] 做(它才知道"被确认的是不是现在这一版") ——
 * 所以**传进来的队列必须与 [DecisionStore] 用的是同一个**(生产路径见 `GridViewModel`:
 * 同一个 [PendingStore] 实例同时交给两者)。
 */
suspend fun retryPending(
    queue: PendingStore,
    decisions: DecisionStore,
    /** 要重试的路径; `null` = 队列里**此刻**全部待重试的项。 */
    paths: List<String>? = null,
    /** 每试完一项回调一次 —— 界面用它**一项一项地**刷新列表(失败的不许等到最后才出现)。 */
    onItemDone: suspend (RetryAttempt) -> Unit = {},
): RetryReport {
    val pending = queue.snapshot().items
    val targets = paths?.distinct() ?: pending.keys.toList()
    val attempted = mutableListOf<String>()
    val synced = mutableListOf<String>()
    val failures = mutableListOf<WriteFailure>()
    val inconclusive = mutableListOf<String>()
    val notAttempted = mutableListOf<String>()

    for (path in targets) {
        // **只重发仍然待重试的**: 队列里已经没有它了(上一次成了 / 用户放弃了)就一个请求都不发。
        if (!pending.containsKey(path)) {
            notAttempted += path
            continue
        }
        // 把这一项装回本地镜像 —— 重启之后 store 是空的, 少了这一步 push 会回 NothingToPush
        // (队列于是永远清不掉, 而"全部重试"看起来像成功)。
        rehydratePending(queue, decisions, listOf(path))
        val result = decisions.push(listOf(path))
        if (result !is PushResult.NothingToPush) attempted += path
        when {
            // 电脑确认的就是**现在这一版** ⇒ 这一项办完了(队列已由 store 那一侧去掉)。
            decisions.get(path).sync == SyncState.SYNCED -> synced += path
            result is PushResult.Failed ->
                failureFor(path, result)?.let { failures += it } ?: inconclusive.add(path)

            // Ok 但本地又改过 / NothingToPush: **没有结论**, 不许算成成功。
            else -> inconclusive += path
        }
        onItemDone(RetryAttempt(path, result, cleared = synced.contains(path)))
    }
    return RetryReport(
        attempted = attempted.size,
        synced = synced,
        failures = failures,
        inconclusive = inconclusive,
        notAttempted = notAttempted,
    )
}

/**
 * 一项失败里**属于这一张**的那条码。
 *
 * 一批只有一项时它必然能归位; 归不上时宁可用第一条(逐项失败里至少有一个是真的),
 * 也不编一个码出来 —— 面板上的话是按码查出来的([WriteMessages.forFailure])。
 */
private fun failureFor(path: String, failed: PushResult.Failed): WriteFailure? =
    failed.failures.firstOrNull { it.path == path } ?: failed.failures.firstOrNull()

// ── 放弃本地改动: 两步确认, 而且**一个请求都不发** ────────────────────────────────

/**
 * 放弃本地改动的**第一步确认状态**(规格 §8.1 的"放弃本地改动")。
 *
 * 为什么非要两步: 它丢掉的是**用户自己做出的决定** —— 按错一下就是数据损失, 而且没有任何
 * "撤销"能把它找回来(那份意图只在这台手机上存在过)。所以:
 *   · 第一次点**只武装**, 一个字节都不动([confirms] 在没武装时永远是 false);
 *   · 第二次必须点在**同一个路径**上 —— 点了别的一张是"改主意了, 换这一张", 绝不执行;
 *   · 别的动作(重试 / 关面板 / 重新载入)一律 [cancel] —— 免得下次进来一按就丢。
 *
 * 做成纯数据是为了能在 JVM 上真的断言"按一次不会丢东西"(这条如果只写在 Composable 里,
 * 就只能靠真机点了才知道)。
 */
data class AbandonConfirm(val armed: String? = null) {

    /** 武装某一张(**不做任何丢弃动作**)。 */
    fun arm(path: String): AbandonConfirm = AbandonConfirm(path)

    /** 解除武装(关面板 / 取消 / 去重试时调)。 */
    fun cancel(): AbandonConfirm = AbandonConfirm(null)

    /**
     * 这一次点击**要不要真的放弃** —— 只有"已经武装过、而且点的就是那一张"才是 true。
     * 未武装(null)时**永远** false: 一次误触不许丢掉用户的决定。
     */
    fun confirms(path: String): Boolean = armed != null && armed == path
}

/** 一次放弃的结论(三个事实分开说: 它本来在不在队列里 / 本地那份意图丢没丢)。 */
data class AbandonResult(
    val path: String,
    /** 它本来待在队列里吗(不在 = 这次放弃其实什么也没丢)。 */
    val wasQueued: Boolean,
    /** 本地镜像里那份"还没被电脑确认"的意图**真的被丢掉了**吗。 */
    val droppedLocal: Boolean,
)

/**
 * 放弃这一张在这台手机上的改动。
 *
 * **一个请求都不发** —— 电脑上从来没有过这份改动(它还待在待重试里就说明没写成), 所以"放弃"
 * 就是把它从这台手机上抹掉。这与"撤销"([DecisionStore.undo])是相反的两种动作: 撤销是恢复
 * **之前那一份**意图, 放弃是"这一张**没有**本地意图"。
 *
 * ⚠️ **只丢"电脑还没确认"的那一份**([SyncState.UNSYNCED])。已被服务端确认过
 * ([SyncState.SYNCED])的本地意图**不丢** —— 丢掉它只会让界面退回一个电脑上早已不是那个值的
 * 旧值, 也就是把一次**已经成功的写入**说成没发生。
 */
suspend fun abandonPending(
    queue: PendingStore,
    decisions: DecisionStore,
    path: String,
): AbandonResult {
    val wasQueued = queue.snapshot().items.containsKey(path)
    queue.remove(path)
    val local = decisions.get(path)
    val dropped = local.hasLocalIntent && local.sync == SyncState.UNSYNCED
    if (dropped) decisions.discardLocal(path)
    return AbandonResult(path, wasQueued = wasQueued, droppedLocal = dropped)
}

// ── 界面要用的那几句话(纯函数: 角标数什么、面板每一行说什么, 都在这里定死) ───────────

/**
 * 顶栏**待重试角标**的文案。为 0 时是 `null`(= 不画) —— 一个写着 `待重试 0` 的角标是句假话,
 * 而且会让用户盯着一个不存在的队列。
 *
 * ⚠️ 数的是 [PendingStore] 里**真的还没被电脑确认的**那些, 不是"本地看起来改过的"。
 * 重启之后本地镜像是空的, 而队列里还有昨晚那三张 —— 那一份才是事实。
 */
fun pendingBadgeText(count: Int): String? = if (count <= 0) null else "待重试 $count"

/**
 * 顶栏那个角标: **显示什么**(label)与**按下去做什么**(opensPanel)必须是同一件事。
 *
 * 两档来源:
 *   · 待重试队列非空 ⇒ 显示队列项数, 按下去打开**重试面板**(里面能逐项看原因、逐项重试);
 *   · 队列空但这次会话里还有未同步的 ⇒ 沿用 [WriteMessages.unsyncedLabel] 那句与本页的重试
 *     (队列为空的窗口只在"改动还没落定的那一瞬", 但那时也不许把角标藏起来)。
 * 一句话配一个动作, 是为了不出现"角标数的是 3、按下去重试的是别的 2 张"这种分裂。
 */
data class TopBarBadge(val label: String, val opensPanel: Boolean)

fun topBarBadge(pendingCount: Int, unsyncedLabel: String?): TopBarBadge? = when {
    pendingCount > 0 -> pendingBadgeText(pendingCount)?.let { TopBarBadge(it, opensPanel = true) }
    unsyncedLabel != null -> TopBarBadge(unsyncedLabel, opensPanel = false)
    else -> null
}

/** 面板里的一行。文案全部来自既有那张表([WriteMessages]), 这里一个字都不另写。 */
data class PendingRow(
    val path: String,
    /** 显示名(只有路径可用 —— 待重试的项可能连电脑都还没读到过)。 */
    val fileName: String,
    /** 这一项要写什么("5★"、"绿色标"、"删掉色标"……)。 */
    val target: String,
    /** 失败原因的可执行文案(**复用** [WriteMessages.forFailure], 不写第二份映射)。 */
    val reason: String,
    /** 这一项现在最该按的那个按钮([WriteMessages.actionLabel])。 */
    val actionLabel: String,
)

/**
 * 路径 → 显示名。**切最后一个分隔符**, 不自己拼路径(规范路径可能带 verbatim 前缀
 * `\\?\D:\…`, 里面有一个**字面问号** —— 任何"自己拼一个 D:\…"的写法都会把它弄丢)。
 */
internal fun fileNameOf(path: String): String {
    val sep = maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'))
    val name = if (sep >= 0) path.substring(sep + 1) else path
    return name.ifBlank { path }
}

/**
 * 这一项要写什么。
 *
 * `0` 星是桌面端"废弃"的语义, 所以写"废弃"两个字而不是 `0★`(与网格角标画 `✕` 同一个意思,
 * 这里是面板, 地方够, 就用说得更清的词)。
 */
fun pendingTargetText(d: Decision): String {
    val parts = buildList {
        d.rating?.let { add(if (it == REJECT_STARS) "废弃" else "$it★") }
        if (d.clearLabel) add("删掉色标") else d.label?.let { add("${labelName(it)}色标") }
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    // 空手的一条有**两种**, 而它们正相反, 所以不许说成同一句话:
    //   · 没有本地意图(远端读回来的那份) = "这台手机上没动过它" —— 本来就没什么要写;
    //   · 有本地意图 = 用户动过, 但那一下**什么都没要求写**([Decision.hasWritableField])。
    //     这一条发出去只会被服务端**整条跳过**(连文件都不打开), 所以它永远办不成事 ——
    //     把它说成"没有要写的改动"就是告诉用户"没什么事了", 而它其实是一条**卡住的**分歧。
    return if (d.hasLocalIntent) PENDING_TARGET_UNWRITABLE else "没有要写的改动"
}

/**
 * 待重试队列里一条"**一个可写字段都没有**"的项([Decision.hasWritableField] 为 false)在面板上的说法。
 *
 * 它不是"没有要写的改动"(那是"没什么事了"), 而是"这一条写不出去" —— 用户看到它才知道
 * 自己该在这一张上重新打一次分, 或者用「放弃本地改动」把它去掉。
 */
const val PENDING_TARGET_UNWRITABLE = "这一条没有可写的字段（电脑不会改动任何东西）"

/**
 * 队列 → 面板的行。**顺序逐字保留队列的顺序**(它是稳定的, 见 [mergePending])。
 *
 * 没有记下失败原因时用 `unconfirmed` 那条码 —— 那是**"没有结论"**那句话本身, 与
 * "写失败了"分得清; 绝不为了排版好看编一句"未知错误"。
 */
fun toPendingRows(items: Collection<Decision>): List<PendingRow> = items.map { d ->
    val failure = d.lastError
        ?: WriteFailure(path = d.path, code = WriteMessages.UNCONFIRMED, source = FailureSource.CLIENT)
    PendingRow(
        path = d.path,
        fileName = fileNameOf(d.path),
        target = pendingTargetText(d),
        reason = WriteMessages.forFailure(failure),
        actionLabel = WriteMessages.actionLabel(WriteMessages.actionFor(failure)),
    )
}

/**
 * 一次重试之后那句话。**只转述电脑确认了什么** —— 没被确认的一个字都不许说成"已同步"
 * (与 [undoMessage] / `WriteMessages.batchHead` 同一条纪律)。
 *
 * ⚠️ **"没能确认"不与"没写进电脑"混着数**: 判据用同一个 [WriteMessages.isUnconfirmed]
 * (批次句与这句话必须给出同一套数, 否则同一批失败在界面上会有两种说法)。
 */
fun retryNote(report: RetryReport): String = when {
    report.attempted == 0 && report.notAttempted.isEmpty() -> "没有待重试的改动。"
    report.attempted == 0 -> "这些已经不在待重试里了（没有发出任何请求）。"
    report.allSynced -> "电脑确认写成了 ${report.synced.size} 项，待重试队列已清空。"
    else -> buildString {
        append("电脑确认写成 ${report.synced.size} 项")
        // ① 确实失败了(带码) ② 没有结论(服务端没回报 / 一个字节都没发出去) —— 分开数、分开说
        val unconfirmed = report.failures.count { WriteMessages.isUnconfirmed(it) }
        val failed = report.failures.size - unconfirmed
        val noConclusion = unconfirmed + report.inconclusive.size
        if (failed > 0) append("，$failed 项没写进电脑")
        if (noConclusion > 0) append("，$noConclusion 项没能确认（结果不明）")
        append(" —— 没被电脑确认的都还留在待重试里。")
        WriteMessages.batchHead(report.attempted, report.failures)?.let { append(" ").append(it) }
    }
}

/** 一次放弃之后那句话(说清"发了没有"与"丢的是哪一份")。 */
fun abandonNote(result: AbandonResult): String = when {
    !result.wasQueued && !result.droppedLocal -> "这一张已经不在待重试里，本地也没有它的改动。"
    result.droppedLocal -> "已放弃这一张在这台手机上的改动（电脑上从来没有过它，一个请求都没发）。"
    else -> "已把这一张从待重试里去掉（电脑上已经是现在的值，本地没有要丢的改动）。"
}

/**
 * 待重试记录**读不回来**时页面上那句话。角标这时候只会显示 0, 而 0 与"不知道有多少"是
 * 两件不同的事 —— 不说出来就是拿一句安静的 0 去掩盖可能已经丢失的数据。
 */
const val PENDING_CORRUPT_NOTICE = "这台手机上的**待重试记录没能读出来**（文件坏了或版本对不上）：" +
    "之前关着电脑做出的那些改动**可能已经丢了**，现在这个 0 不等于「没有待重试」。"

