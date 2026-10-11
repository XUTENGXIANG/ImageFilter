// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

/**
 * 撤销栈的上限(规格 §8.2 的"撤销(多步, 内存栈上限 50)")。
 *
 * 为什么**必须有上限**: 滑卡是高速连续操作, 一次会话滑几千张是正常的。无上限的栈会把内存
 * 吃掉, 而用户真正想撤回的一定是"刚刚那一下" —— 所以丢掉的是**最早**的那条([UndoStack.push])。
 */
const val UNDO_LIMIT = 50

/**
 * 一次**可以撤销的决策**: 改**之前**那份意图与改完那份, 以及它们属于哪一张。
 *
 * 为什么两条都留着: 撤销要恢复的是 [before](用户之前那份本地意图), 而 [after] 用来判断
 * "这一次改动到底有没有发生"(没有发生就不该进栈)。两者都不是"当前值"的别名 ——
 * 当前值可能在它们之后又被改过(那种情况下栈顶已经不是它了, 见 [DecisionStore.undo])。
 *
 * 判据 [before].[Decision.hasLocalIntent]: `before` 是**远端读回来的值**时它没有本地意图,
 * 撤销要恢复的就是"这台手机上没动过它", 而不是"把读到的那个值当成一条新意图再写回电脑"。
 */
data class UndoEntry(
    val path: String,
    val before: Decision,
    val after: Decision,
)

/**
 * **内存里的多步撤销栈**(规格 §8.2)。
 *
 * 只存内存(**刻意不落盘**): 它记的是"这一次会话里刚做的那几下", 跨会话没有意义 ——
 * 重启 App 之后用户想撤回的已经不是上一次的那一下了, 而"未同步"的意图本来就由 [DecisionStore]
 * 活着(纪律③), 不靠这个栈。
 *
 * **不做重做(redo)**: 撤销再撤销等于重做, 而滑卡的场景里"再滑一次"更快; 多一个 redo 栈就多
 * 一份会与撤销栈互相掩盖的状态。这条取舍是**明说的**, 不是漏了。
 */
class UndoStack(private val max: Int = UNDO_LIMIT) {

    init {
        // 上限为 0 的"撤销栈"是个永远撤不了东西的假控件 —— 当场炸掉好过安静地退化。
        require(max >= 1) { "撤销栈的上限必须 ≥ 1, 收到的是 $max" }
    }

    private val entries = ArrayDeque<UndoEntry>()

    /** 现在还能撤销几步(界面上的按钮与角标就用它)。 */
    val size: Int get() = entries.size

    /** 入栈。满了就丢掉**最早**的那条(用户想撤回的一定是刚刚那一下)。 */
    fun push(e: UndoEntry) {
        if (entries.size >= max) entries.removeFirst()
        entries.addLast(e)
    }

    /** 弹出**最后**那条(没有则 null)。调用方拿到它才决定"要不要真的写回电脑"。 */
    fun pop(): UndoEntry? = entries.removeLastOrNull()

    /** 清空(换一批照片/关掉这一页时用: 撤销栈里的路径可能已经不在队列里了)。 */
    fun clear() {
        entries.clear()
    }
}

/**
 * 一次撤销的结论 —— **三个变体**, 因为这三件事完全不同(与 [PushResult] 同一条纪律:
 * 混成一个布尔值必然有一边在撒谎):
 *
 *   · [Empty] —— 栈是空的: 一个字节都没动;
 *   · [LocalOnly] —— 电脑上**从来没收到过**那一次改动 ⇒ 只把本地意图恢复成改动前那一份,
 *     **一个请求都没发**。把它说成"已经撤销到电脑上了"就是替服务端保证了一件它没做过的事;
 *   · [Push] —— 电脑上确实是改动后的值, 所以把 [UndoEntry.before] 送了回去; [push] 如实
 *     转述**服务端说了什么**(没被确认就不许说成功, 与打分那条链路逐字同一条纪律③)。
 */
sealed interface UndoResult {
    /** 撤销栈是空的(没有可撤销的操作)。 */
    data object Empty : UndoResult

    /** 只撤回了本地意图, **一个请求都没发**(电脑上本来就还是老样子)。 */
    data object LocalOnly : UndoResult

    /** 把改动前的值送回了电脑; [push] 就是服务端对那一次写入的回报。 */
    data class Push(val push: PushResult) : UndoResult

    /**
     * 反向写入**发出去了, 但服务端回报这一项什么都没改**(`written = 0` 且 `skipped > 0`)。
     *
     * 为什么它必须单独一档: 撤销的全部意义就是"让电脑上变回改动前那个值", 而一个没让电脑发生
     * 任何变化的写入**证明不了**这件事(它要么说明电脑上已经是那个值, 要么说明这一份请求什么
     * 都没要求写 —— 老实现就是后者, 而且服务端**连文件都没打开**)。把它算进 [Push] 的成功
     * 里就是本项目最贵的那种失败: 界面说"已撤销", 而边车里的值原封不动。
     *
     * 这一支下本地那一版**留在 [SyncState.UNSYNCED]**(见 `DecisionStore.undo`), 所以分歧看得见、
     * 角标数得着、失败卡说得出为什么。
     */
    data class NotApplied(val push: PushResult.Ok) : UndoResult
}

/**
 * 撤销之后给用户的那句话。**只有服务端确认了才说"已撤销"** —— 与界面上的失败卡同一条纪律:
 * 写没写进电脑由服务端说, 客户端不许自己判。
 */
fun undoMessage(result: UndoResult): String = when (result) {
    UndoResult.Empty -> "没有可撤销的操作"
    // 说清"没发请求"这件事: 否则用户会以为电脑上刚被写了一次(而它一个字节都没动)。
    UndoResult.LocalOnly -> "已撤销（电脑上还没收到过那次改动，没有发送写入）"
    // 发是发出去了, 但电脑上什么都没改 —— **绝不说撤销成功**: 撤销的判据是"电脑上真的变回去了",
    // 而这一支恰恰证明不了。本地那一版还在(标未同步), 所以分歧看得见、能重试、能放弃。
    is UndoResult.NotApplied ->
        "撤销没能让电脑上的值发生变化（服务端回报这一项 skipped）—— 这一次写入什么都没改，" +
            "所以不能说撤销成功。这一版留在手机上并标着未同步，分歧看得见。"
    is UndoResult.Push -> when (result.push) {
        is PushResult.Ok -> "已撤销"
        is PushResult.NothingToPush -> "已撤销（没有需要发送的改动）"
        // 写不成时**绝不说"已撤销"**: 本地已经回到改动前那份(还标着未同步), 而电脑上还是
        // 改动后的值 —— 这句话就是给用户的重试依据(失败卡上有码与动作)。
        is PushResult.Failed -> "撤销没能写进电脑 —— 要撤销成的那个值还留在这台手机上"
    }
}
