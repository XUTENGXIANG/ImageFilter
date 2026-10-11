// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

// ── "电脑上这一张是几星" 在界面上的三态(纯函数: 与 Compose 无关, 每个都有 JVM 单测) ────────
//
// 与 Filters.kt / Swipe.kt / ViewerState.kt 同一条纪律: 安卓 UI 测试在本项目里**没有依赖**,
// 所以"哪几种状态要画得不一样、一次点击该不该被拦下、拦下来说什么"必须是**可以真的断言**的
// 纯函数, 而不是散在 Composable 里的一堆 if。
//
// 为什么这一组函数非有不可: [DesktopRating] 把"这台手机不知道"与"电脑上确实没有"分开了, 但
// **光有类型分不开是不够的** —— 每一个消费方都要把它**画**出来, 否则:
//   · 一次失败的读取会让一张电脑上是 4/5 星的照片在手机上显示成一排空星星(与"没评过"一模一样),
//     而**一次点击**(1..5 星是**绝对赋值**)就会把那颗 4/5 星**静默覆盖**成 3 星;
//   · 用户在毫无提示的情况下损失自己在电脑上给出的取舍。

/**
 * 网格格子上"星级未知"的角标文字。
 *
 * 它必须与"什么都没画"(= 确知没有星级/确实没评过)**长得不一样**: 一次读失败时, 一排空格子
 * 与"这一格没有任何信息"看起来完全相同, 而它们的意思正相反。
 */
const val RATING_UNKNOWN_MARK = "★?"

/**
 * 电脑上那一份知识的一句话 —— 星级条上**一直**画着它。
 *
 * 三态各有各的说法(见 [DesktopRating]): 屏幕上那颗星星是**合并之后**的值(本地意图优先),
 * 所以它**不能**代表电脑上是几星; 想知道电脑上是什么, 只能看这一行。
 */
fun ratingKnowledgeLabel(desktopRating: DesktopRating): String = when (desktopRating) {
    DesktopRating.Unknown -> "电脑上：没读到（未知）"
    DesktopRating.Unrated -> "电脑上：没有星级"
    is DesktopRating.Rated -> "电脑上：${desktopRating.stars}★"
}

/**
 * 一次**打星**是不是"瞎写" —— 与 `decideSwipe` 的保留那一支([keepIsBlind])同一条理由。
 *
 * `1..5` 星是**绝对赋值**: 在"不知道电脑上是几星"时执行它, 就是拿"不知道"去赌电脑上没有
 * 4/5 星 —— 赌输的代价是静默的数据损失。所以这一下要被**拦下**(不写、并且说清为什么)。
 *
 * `0`(废弃)**不受它管**: 那是用户明确的取舍, 与左滑同一条规矩(允许降级)——
 * 把"我不要这张"也拦下来, 等于把用户唯一确定的意图挡在门外。
 */
fun ratingTapIsBlind(desktopRating: DesktopRating, stars: Int): Boolean =
    stars >= 1 && desktopRating == DesktopRating.Unknown

/**
 * 被拦下的那一下要说的那句话。
 *
 * 必须说清三件事: 这一下**没有写入**(否则用户以为打上了)、**为什么**(不知道电脑上是几星,
 * 直接写就是覆盖)、以及**下一步怎么做到**(再点一次同一颗星 = 用户明确要求覆盖)。
 */
fun ratingGuardMessage(stars: Int): String =
    "这台手机不知道电脑上这一张是几星（那次读取没成功，或者边车正被别的程序占着）——" +
        "直接打 $stars 星是一次**绝对赋值**，会把它覆盖掉，所以这一下没有写入。" +
        "再点一次同一颗星（$stars 星）就会真的写下去。"
