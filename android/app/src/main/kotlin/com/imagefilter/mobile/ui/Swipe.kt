// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.Pairing

// ── 滑卡语义(纯函数: 与 Compose / 安卓运行时无关, 每个都有 JVM 单测) ───────────────
//
// 与 Filters.kt / ViewerState.kt 同一条纪律: 安卓 UI 测试在本项目里**没有依赖**(也不该为
// M3′ 引一个), 所以"右滑到底写什么、左滑能不能降级、只写星级那一档会不会顺手把色标覆盖掉"
// 必须是**可以真的断言**的纯函数。这一页的手势在真机上很难复现(见计划:"手势细节可能因真机
// 手感调整"), 而语义**一条都不许动** —— 所以语义放在这里, 手势只负责把像素翻译成方向。
//
// 与桌面端对齐(README 快捷键表: `J` = 保留(3 星)、`X` = 废弃(0 星)): 同一个 `xmp:Rating`,
// 所以电脑上"≥N★"的筛选能直接看到手机上的取舍。

/**
 * 一次滑卡的**方向**。
 *
 * 只在与手势有关的地方用: "方向 → 动作"的映射是数据([SwipeConfig]), 所以纯函数
 * [decideSwipe] 不必知道像素, 测试也不必构造手势。
 */
enum class SwipeDirection { Left, Right }

/** 一次滑卡是"废弃"还是"保留"。与桌面端快捷键同义: `X` = [Reject]、`J` = [Keep]。 */
enum class SwipeAction { Reject, Keep }

/**
 * 这一滑**写哪些属性**(规格 §8.2 的设置项「滑卡写入」)。
 *
 * 三档是**闭集**, 每一档都是一条"不许越权"的约束:
 *   · [StarsOnly](默认) —— 只写星级, **绝不碰色标**: 用户精心标的色标不该被一次滑卡覆盖;
 *   · [LabelsOnly] —— 只写色标(左红右绿), **绝不碰星级**;
 *   · [Both] —— 两个都写, 但星级那一半仍然守"保留绝不降级"。
 */
enum class SwipeMode { StarsOnly, LabelsOnly, Both }

/** 保留(右滑)写入的星级 —— 与桌面端 `J` 键一致(README 快捷键表)。 */
const val KEEP_STARS = 3

/** 废弃(左滑)写入的星级 —— 与桌面端 `X` 键、与 `xmp:Rating` 的 0 一致(0 = 废弃, 不是"没评")。 */
const val REJECT_STARS = 0

/** 保留 / 废弃写入的色标(五色闭集 [LABELS] 里的两个)。 */
const val KEEP_LABEL = "green"
const val REJECT_LABEL = "red"

/** 手势阈值: 水平拖过**屏宽的这个比例**松手才算一次决策(规格 §8.2 的"约屏宽 25%")。 */
const val SWIPE_THRESHOLD_FRACTION = 0.25f

/** 按屏宽算出这一下要拖多远才算数(屏宽非正时给 0, 由 [swipeDirectionFor] 兜底成"不决策")。 */
fun swipeThresholdPx(widthPx: Float): Float =
    if (widthPx <= 0f) 0f else widthPx * SWIPE_THRESHOLD_FRACTION

/**
 * 一次水平拖动**松手**之后的结论: 过了阈值给方向, 没过给 null(卡片回弹, 这一次不做决策)。
 *
 * 判据与 [dragStep] 同一套(含等号): 恰好等于阈值算决策 —— 两处若一宽一严,
 * "看着已经滑过去了却没反应"就会出现。
 */
fun swipeDirectionFor(totalDx: Float, thresholdPx: Float): SwipeDirection? = when {
    thresholdPx <= 0f -> null
    totalDx <= -thresholdPx -> SwipeDirection.Left
    totalDx >= thresholdPx -> SwipeDirection.Right
    else -> null
}

/**
 * 滑卡的**手势语义** —— 可配置, 默认左 = 废弃(0★)、右 = 保留(3★)、只写星级(规格 §8.2)。
 *
 * 为什么方向映射也是数据: 左/右与"废弃/保留"的绑定是**用户习惯**, 不是本项目的语义。
 * 把两侧配成同一个动作属于误配置(滑卡就丢了"取舍"这件事), 所以在这里当场炸掉。
 */
data class SwipeConfig(
    val left: SwipeAction = SwipeAction.Reject,
    val right: SwipeAction = SwipeAction.Keep,
    val mode: SwipeMode = SwipeMode.StarsOnly,
) {
    init {
        require(left != right) {
            "两个方向映射到了同一个动作($left): 那样左滑右滑都是同一件事, 取舍就丢了"
        }
    }

    /** 方向 → 动作。**唯一**一处绑定, [decideSwipe] 与界面都走它。 */
    fun actionFor(direction: SwipeDirection): SwipeAction = when (direction) {
        SwipeDirection.Left -> left
        SwipeDirection.Right -> right
    }

    /** 左右互换(给左撇子/反直觉的用户): 只换方向, 不换别的。 */
    fun reversed(): SwipeConfig = copy(left = right, right = left)
}

/** 生产默认值(规格 §8.2 那张表)。**只在这里出现一次**。 */
val DEFAULT_SWIPE_CONFIG = SwipeConfig()

/**
 * 滑卡页向前预取几张 —— 规格 §8.2 的"当前张 + **后 2 张**"。
 *
 * 抽成常量是为了让"界面上到底配了几张"**可被机器检查**: 界面与测试取同一个值,
 * 测试就不必自己搭一份"看起来一样"的配置(那正是假绿的来源)。用的是**同一个**预取器
 * ([ViewerViewModel.prefetchUrls]), 绝不另写一套, 也**永远不预取高清**。
 */
const val SWIPE_PREFETCH_AHEAD = 2

// ── 接线: 滑卡页要的那份状态机(参数**只在这一处定**, 界面与测试都走它) ──────────────
//
// 放在这里而不是 Compose 文件里, 是为了让这几条配置**在 JVM 单测里真的被走一遍**:
// 界面那一行 `advanceOnWriteSuccess = true` 若被谁改掉, 只有真机才看得出来 —— 那太贵了。

/**
 * 滑卡页的 [ViewerViewModel]:
 *   · `advanceOnWriteSuccess = true` —— 写成功才自动到下一张(规格 §8.2), **失败绝不前进**;
 *   · `prefetchAhead = `[SWIPE_PREFETCH_AHEAD] —— 当前张 + 后 2 张, 默认档;
 *   · [DecisionStore] 由调用方注入(网格持有, 必须活得比这一页久 —— 纪律③)。
 *
 * @param thresholdPx 松手判决策的水平阈值(界面按屏宽 25% 算好传进来; 纯状态里拿不到密度)。
 * @param prefetch 邻张预取(生产路径是 Coil 的 enqueue; 测试注入一个记录器)。
 */
fun swipeViewModel(
    pairing: Pairing,
    items: List<GridItem>,
    startIndex: Int,
    thresholdPx: Float,
    decisions: DecisionStore,
    prefetch: (List<String>) -> Unit,
): ViewerViewModel = ViewerViewModel(
    pairing = pairing,
    items = items,
    startIndex = startIndex,
    dragThresholdPx = thresholdPx,
    prefetch = prefetch,
    decisions = decisions,
    advanceOnWriteSuccess = true,
    prefetchAhead = SWIPE_PREFETCH_AHEAD,
)

/**
 * 一次滑卡的**结论**。
 *
 * ⚠️ [rating] / [label] 的 `null` 是**"不动这个属性"**(与服务端 `DecisionWrite` 的语义逐字一致),
 * **不是** "0 星"、也不是"清空"。把它当成"写一个空值"会立刻出两种错: 左滑什么都没干,
 * 以及只写星级的那一档顺手把用户的色标覆盖掉。
 *
 * [unchanged] = 这一滑**一个字节都不写**: 调用方据此**跳过整个写入**(不发请求、不标未同步),
 * 但仍然要**自动前进**并显示 [message] —— 否则用户会以为这一下没生效(规格 §8.2)。
 *
 * ⚠️ 变"不变"时**不要**顺手 `push` 一次: 这条路径上可能挂着一份**以前**写失败、还没重试的
 * 意图([SyncState.UNSYNCED]), 重试它是「未同步·重试」那个角标的活, 不是滑卡的活。
 */
data class SwipeOutcome(
    val rating: Int? = null,
    val label: String? = null,
    val unchanged: Boolean = false,
    val message: String? = null,
) {
    /**
     * 把这次结论落到**这一张当前的意图**上。
     *
     * 只写 [rating] / [label] 里非 null 的那些 —— 另一维**原样留着**, 连 `clearLabel` 这条
     * 一次性命令也留着: 它是用户之前在色标面板上做的决定, 一次滑卡没有资格把它取消
     * (取消掉就是静默丢掉用户已经做出的意图)。
     */
    fun applyTo(current: Decision): Decision {
        var next = current
        if (label != null) next = next.withLabel(label)
        if (rating != null) next = next.withRating(rating)
        return next
    }
}

/**
 * 滑卡语义 —— 与桌面端 `J`/`X` 对齐(README: `J` = 保留(3 星)、`X` = 废弃(0 星)), 共用
 * `xmp:Rating` 这一个字段。
 *
 * 四条规矩, 每条都有测试盯着:
 *   1. **左滑(废弃) = [REJECT_STARS] 星, 允许降级** —— 那是用户**明确**说"我不要这张",
 *      与下面第 2 条不矛盾: 一个是明确的取舍, 一个是含糊的"留着";
 *   2. **右滑(保留)绝不降级**: 无星级(空或 0)才写 [KEEP_STARS]; **已有 ≥1 星时一个字节都不写**
 *      —— 无条件写 3 会把桌面端给的 4/5 星**静默降成** 3 星, 那是数据损失, 不是"统一口径";
 *      (1★/2★ 同样不动: "保留"不是"改成 3 星", 只有 0 星 —— 明确的废弃 —— 才会被改回 3 星)
 *   3. **什么模式写什么字段**: [SwipeMode.StarsOnly] 的结论里 `label` **必然是 null**,
 *      [SwipeMode.LabelsOnly] 的结论里 `rating` **必然是 null** —— 另一维连一个值都不带;
 *   4. **没什么要写时就不写, 但要说清为什么**: [SwipeOutcome.unchanged] +
 *      [SwipeOutcome.message]("已保留 · 保持 5★"这类话)。
 *
 * `unchanged` 的判据是"这一维**本来就已经是这个值**", 于是它同时覆盖两种撞车:
 * 保留那一支撞上已有星级(保持 N★), 与废弃那一支撞上已经是 0 星。
 */
fun decideSwipe(current: Decision, action: SwipeAction, mode: SwipeMode): SwipeOutcome {
    val writeStars = mode != SwipeMode.LabelsOnly
    val writeLabel = mode != SwipeMode.StarsOnly

    // ── 星级那一半 ──────────────────────────────────────────────────────────
    var rating: Int? = null
    var starNote: String? = null
    /** 星级这一维**没有要写的东西**吗(没开这一维, 或本来就已经是目标值)。 */
    var starDone = true
    if (writeStars) {
        when (action) {
            SwipeAction.Keep -> {
                val r = current.rating
                if (r == null || r == REJECT_STARS) {
                    // 没评过 → 给 3 星; 0 星是**明确的废弃**, 用户改主意右滑就该变回"保留"。
                    rating = KEEP_STARS
                    starDone = false
                } else {
                    // ← 本任务最要紧的一行: 已有 ≥1 星就**一个字节都不写**(尤其不许写 3)。
                    starNote = "保持 $r★"
                }
            }

            SwipeAction.Reject -> if (current.rating == REJECT_STARS) {
                starNote = "本来就是 $REJECT_STARS★"
            } else {
                rating = REJECT_STARS
                starDone = false
            }
        }
    }

    // ── 色标那一半 ──────────────────────────────────────────────────────────
    var label: String? = null
    var labelNote: String? = null
    var labelDone = true
    if (writeLabel) {
        val target = if (action == SwipeAction.Keep) KEEP_LABEL else REJECT_LABEL
        // 已经就是这个色标、而且没有一条待发的"删色标"命令 → 不必再写一次。
        if (current.label == target && !current.clearLabel) {
            labelNote = "色标本来就是${labelName(target)}"
        } else {
            label = target
            labelDone = false
        }
    }

    val notes = listOfNotNull(starNote, labelNote)
    return SwipeOutcome(
        rating = rating,
        label = label,
        unchanged = starDone && labelDone,
        message = if (notes.isEmpty()) {
            null
        } else {
            val word = if (action == SwipeAction.Keep) "已保留" else "已废弃"
            "$word · " + notes.joinToString("、")
        },
    )
}

/** 手势(方向 + 当前配置) → 结论。方向与动作的绑定只在 [SwipeConfig.actionFor] 一处。 */
fun decideSwipe(current: Decision, direction: SwipeDirection, config: SwipeConfig): SwipeOutcome =
    decideSwipe(current, config.actionFor(direction), config.mode)

// ── 界面要用到的几句话(同样是纯函数: 免得界面里各拼一份, 配置改了也对不上) ──────────

/** 一个动作的中文名(带上它会写的星级, 用户才知道"保留"到底写了什么)。 */
fun actionWord(action: SwipeAction): String = when (action) {
    SwipeAction.Keep -> "保留(${KEEP_STARS}★)"
    SwipeAction.Reject -> "废弃(${REJECT_STARS}★)"
}

/** 一档写入模式的中文名(设置项/滑卡页上那排按钮就用它)。 */
fun modeName(mode: SwipeMode): String = when (mode) {
    SwipeMode.StarsOnly -> "只写星级"
    SwipeMode.LabelsOnly -> "只写色标"
    SwipeMode.Both -> "星级+色标"
}

/**
 * 当前配置下"左滑做什么、右滑做什么"的一句话 —— **必须画在滑卡页上**: 配置是可改的,
 * 改完不显示的话用户只能靠试。
 */
fun swipeLegend(config: SwipeConfig): String =
    "左滑 = ${actionWord(config.left)} · 右滑 = ${actionWord(config.right)}"
