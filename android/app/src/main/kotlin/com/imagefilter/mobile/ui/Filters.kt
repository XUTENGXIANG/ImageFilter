// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ScannedPhotoDto

// ── 筛选(纯函数: 与 Compose 无关, 每个都有 JVM 单测) ──────────────────────────
//
// 与 GridViewModel / BrowseViewModel / ViewerState 同一条纪律: 安卓 UI 测试在本项目里没有依赖
// (也不该为 M2′ 引入), 所以"哪些照片留下、每个维度上有几张、空的时候说什么"必须是**可以真的
// 断言**的纯函数, 而不是散在 Composable 里的一堆 when。

/**
 * 筛选的三个维度 —— 规格 §8.1: **只有星级、色标、待重试三个**(没有分析标记这一维, 因为不做 AI)。
 *
 * 维度之间是**与**关系(三个都满足才留下); [labels] 内部是**或**关系(命中任意一个色标即可)。
 * 这两条语义各有测试钉住(计划 Task 3 Step 1 的两条用例)。
 */
data class Filters(
    /**
     * 星级下限。
     *
     * ⚠️ `0` 的语义是**"不按星级筛"**, 不是"≥0 星": **没评分的照片不算命中任何一个下限**
     * (与桌面端语义一致 —— 否则"筛 ≥3 星"会把没看过的照片全带进来, 筛选就没意义了)。
     */
    val minStars: Int = 0,
    /** 色标(多选, 或关系); 空集 = 不按色标筛。取值是五色闭集 [LABELS]。 */
    val labels: Set<String> = emptySet(),
    /** 只看"这台手机改了但电脑还没确认"的那些(规格 §11 纪律③的第三个维度)。 */
    val onlyUnsynced: Boolean = false,
) {
    init {
        // 闭集之外的颜色在数据里根本不存在(服务端只认那五个字符串), 传进来只能是编程错误 ——
        // 与 `Decision.withLabel` 的 require 同一条: 当场炸掉好过一个悄悄筛不出任何东西的条件。
        require(labels.all { it in LABELS }) {
            "色标只能是 ${LABELS.joinToString("/")} 之一, 收到的是 $labels"
        }
        require(minStars in 0..5) { "星级下限只能是 0..5, 收到的是 $minStars" }
    }

    /** 一个维度都没开 —— 网格据此决定画不画"清除筛选", 以及空网格该说哪一句话。 */
    val isEmpty: Boolean get() = minStars == 0 && labels.isEmpty() && !onlyUnsynced

    /**
     * 顶栏的**筛选条件摘要**(规格 §8.1 的顶栏里有这一项); 没有筛选时是 `null`。
     *
     * 色标按 [LABELS] 的**闭集顺序**列(不是 Set 的迭代顺序): 同一个筛选条件必须永远画出同一句话,
     * 否则"摘要"会自己抖动, 也没法被测试钉住。
     */
    val summary: String?
        get() {
            val parts = mutableListOf<String>()
            if (minStars > 0) parts.add("≥$minStars★")
            if (labels.isNotEmpty()) {
                parts.add("色标：" + LABELS.filter { it in labels }.joinToString("/") { labelName(it) })
            }
            if (onlyUnsynced) parts.add("仅未同步")
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }
}

/**
 * 一张照片在这套筛选条件下**是否命中**。
 *
 * [decision] 是"合并之后的"决策(本地意图优先) —— 没有(`null`)就等于"这台手机没动过、电脑上
 * 也没读到", 于是除了"不按星级/色标筛"以外它什么都不命中(它不是"0 星", 是**不知道**)。
 */
fun matchesFilters(decision: Decision?, f: Filters): Boolean {
    if (f.minStars > 0) {
        val rating = decision?.rating ?: return false
        if (rating < f.minStars) return false
    }
    if (f.labels.isNotEmpty()) {
        val label = decision?.label ?: return false
        if (label !in f.labels) return false
    }
    if (f.onlyUnsynced && decision?.sync != SyncState.UNSYNCED) return false
    return true
}

/**
 * 按筛选条件留下照片。**顺序逐字保留**(与 `scan` 的返回顺序一致, 手机端不重排)。
 *
 * 这个重载是计划里逐字约定的那个签名(`List<ScannedPhotoDto>`), 也是筛选语义的**唯一**实现。
 * 网格手里的是 [GridItem](`scan` 的结果上面拼好了缩略图 URL), 走下面那个四参数重载 ——
 * 两个 JVM 签名不同(一个 3 参、一个 4 参), 所以 Kotlin 的类型擦除不会让它们打架。
 */
fun applyFilters(
    photos: List<ScannedPhotoDto>,
    decisions: Map<String, Decision>,
    f: Filters,
): List<ScannedPhotoDto> = applyFilters(photos, decisions, f) { it.path }

/**
 * 任意"有路径的东西"的筛选(网格用 [GridItem])。
 *
 * 没有筛选条件时**原样返回同一个列表**: 少一次遍历, 也少一次"顺序被无意改动"的机会。
 */
fun <T> applyFilters(
    items: List<T>,
    decisions: Map<String, Decision>,
    f: Filters,
    pathOf: (T) -> String,
): List<T> = if (f.isEmpty) items else items.filter { matchesFilters(decisions[pathOf(it)], f) }

// ── 三个维度上的张数(诚实: 不知道就说不知道) ─────────────────────────────────

/**
 * 一个维度上的张数**没有权威值**时的说法。
 *
 * 刻意**就是**浏览页那句 [NOT_COUNTED]: 同一个"不知道"在两页上说成两个词, 用户会以为是两件事。
 * 有一处测试钉住两者必须一致(它们是同一个常量, 改一处就会让另一处也变, 这条一致性因此不会被绕过)。
 */
const val COUNT_UNKNOWN: String = NOT_COUNTED

/** 一个张数的说法: 有权威值就是数字, 没有就是 [COUNT_UNKNOWN](**绝不拿 0 顶上**)。 */
fun countText(count: Int?): String = count?.toString() ?: COUNT_UNKNOWN

/**
 * 筛选栏三个维度上的张数。
 *
 * [stars] / [labels] 的类型是 `Int?`: `null` = 这个数**没有权威值** —— 电脑上原有的星级/色标这次
 * 没读到, 于是手机这边数出来的只是**下界**(电脑上可能还有 200 张 4 星)。拿这个下界当答案比
 * "不知道"更坏: 用户会照着它去筛, 然后以为剩下的照片都没打过分。
 *
 * [unsynced] 是**唯一**永远确定的数: "这台手机改了但没写成"是纯本地的事实, 与远端读没读到无关。
 */
data class FilterCounts(
    /** 星级下限(1..5) → "有多少张 ≥ 这个下限"(未评分与 0 星都不算命中)。 */
    val stars: Map<Int, Int?>,
    /** 色标(五色闭集) → 有多少张是它(没打色标的不在任何一档里)。 */
    val labels: Map<String, Int?>,
    /** 这个文件夹里"改了但电脑还没确认"的张数 —— 永远确定。 */
    val unsynced: Int,
) {
    /** 某个星级下限上的张数说法(筛选栏的按钮上写它)。 */
    fun starText(minStars: Int): String = countText(stars[minStars])

    /** 某个色标上的张数说法。 */
    fun labelText(label: String): String = countText(labels[label])

    companion object {
        /**
         * 星级与色标两维都"不知道"的那一份(读回来之前 / 读失败之后 / 读得不全时用它)。
         *
         * 注意 [unsynced] **照常给确定值**: 一次失败的读取不会让"我刚才在手机上打的分还没发出去"
         * 这件事变得不确定。
         */
        fun unknown(unsynced: Int): FilterCounts = FilterCounts(
            stars = (1..5).associateWith { null },
            labels = LABELS.associateWith { null },
            unsynced = unsynced,
        )
    }
}

/**
 * 数出三个维度上的张数。
 *
 * ⚠️ [decisionsKnown] 为 false 时, 星级与色标两维一律 [COUNT_UNKNOWN] —— **不是 0,
 * 也不是"按已知的那些数一数"**: 后者会得到一个偏小的数, 而界面上它看起来与真值一模一样。
 * (与浏览页"张数未统计"、与写边车的 `unconfirmed` 是同一条纪律的三个落点。)
 */
fun filterCounts(
    paths: List<String>,
    decisions: Map<String, Decision>,
    decisionsKnown: Boolean,
): FilterCounts {
    val unsynced = paths.count { decisions[it]?.sync == SyncState.UNSYNCED }
    if (!decisionsKnown) return FilterCounts.unknown(unsynced)
    return FilterCounts(
        stars = (1..5).associateWith { n -> paths.count { atLeastStars(decisions[it], n) } },
        labels = LABELS.associateWith { c -> paths.count { decisions[it]?.label == c } },
        unsynced = unsynced,
    )
}

/** "这张至少有 n 星吗" —— 未评分(`null`)与 0 星(明确废弃)都不算。 */
private fun atLeastStars(d: Decision?, n: Int): Boolean {
    val rating = d?.rating ?: return false
    return rating >= n
}

/**
 * 筛选栏上那一行"当前显示 N / 共 M 张"(计划 Task 3 Step 2 逐字要求)。
 *
 * 两个数都来自**手里真的有的东西**: N 是过滤之后的格子数, M 是 `scan` 的返回长度 —— 所以这一行
 * 不会像"电脑上一共有几张"那样撒谎(后者手机根本没有权威值)。
 */
fun shownCountLabel(shown: Int, total: Int): String = "当前显示 $shown / 共 $total 张"

/**
 * **全被筛掉了**时网格说的话(与 [GRID_EMPTY]"电脑扫描返回 0 张照片"是两句不同的话)。
 *
 * 这两句绝不能混: [GRID_EMPTY] 说的是"这个文件夹里没有照片", 而这里说的是"有 M 张, 只是都被
 * 你现在的筛选条件挡住了"。混了的话, 用户会以为照片丢了 —— 而它们就在那儿, 只是被筛掉了。
 */
fun filteredOutMessage(total: Int): String =
    "现在这个筛选条件下一张都不剩（这个文件夹一共 $total 张）——**照片没丢，只是被筛掉了**。" +
        "把条件放宽，或者点「清除筛选」看全部。"

// ── 格子角标(读屏也要能听懂) ──────────────────────────────────────────────────

/**
 * 一个格子的无障碍描述 —— 星级/色标/未同步**都要能被读出来**。
 *
 * 为什么要有这个纯函数: 角标是**画**出来的(右下角星级、左上角色点、未同步加一圈边框),
 * 而"画出来"这件事在读屏里等于不存在; 未同步只靠一圈边框更是完全不可见。测试钉住的是这段话,
 * 不是像素(像素只能真机看, 见报告的 UNVERIFIED)。
 */
fun cellDescription(item: GridItem, decision: Decision?): String {
    val parts = mutableListOf(item.displayName)
    val rating = decision?.rating
    if (rating != null) parts.add(if (rating == 0) "已废弃" else "$rating 星")
    decision?.label?.let { parts.add("${labelName(it)}色标") }
    if (decision?.sync == SyncState.UNSYNCED) parts.add("未同步")
    return parts.joinToString("，")
}
