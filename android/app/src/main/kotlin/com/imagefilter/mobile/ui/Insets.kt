// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 系统栏避让 —— **只有这一份**, 各页不许自己写一遍。
 *
 * 为什么非有不可(真机上量到的一次):
 *   · 本 App 的 `targetSdk = 35`, Android 15 起**强制边到边**(窗口铺满整屏), 于是
 *     状态栏 / 显示挖孔 / 手势导航条都**画在应用内容之上**, 而"应用内容要让开哪里"变成
 *     应用自己的责任;
 *   · `GridScreen` / `BrowseScreen` 用的是 `Scaffold` + `TopAppBar`, 两者自带
 *     `windowInsets` 默认值, 所以它们**本来就对**;
 *   · 而查看器、滑卡页、移动流程、配对向导都是自己搭的整屏 `Surface` + `Column`,
 *     `TopAppBar` 那套默认值一个都没有 —— 它们把顶栏画在了状态栏底下。
 *
 * 实测(1080x2400 的模拟器, 状态栏 `frame=[0,0][1080,128]`): 查看器/滑卡页的
 * 「撤销」可点矩形是 `[750,12]-[917,138]`, 也就是说它**除了最底下那条 10px 的缝,
 * 整个都在状态栏的触摸区里** —— 用户在按钮中间点一百次都没反应(那一下被系统状态栏吃掉了),
 * 而状态栏的时钟/电量正好压在文件名上。网格页顶栏在 y≈214 就没这个问题 —— 差别只有这一条。
 *
 * 两条纪律:
 *   · **避让加在"栏"上, 不加在整屏容器上**: 顶栏的底色必须照旧铺到屏幕最上沿
 *     (否则状态栏后面会露出一条别的东西), 动的只是栏**里面**内容的位置;
 *   · **用 `safeDrawing` 的顶边而不是 `statusBars`**: 这台设备的挖孔高度恰好等于状态栏高度,
 *     但别处不一定 —— 取两者的大者才是"内容不会被挡住"的那一条。
 *
 * 这不是审美问题: 少了它, 顶栏上的按钮**在真机上按不动**, 而且屏幕上看不出异常。
 */
@Composable
internal fun Modifier.topBarInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/**
 * 底部那一份避让(手势导航条 / 三键导航栏)。
 *
 * 与 [topBarInsets] 是**同一类错误的上下两半**: 页面底部的内容一旦落在系统手势条下面,
 * 那条半透明的**手势把手**就会横穿过去 —— 截图上看就是某个控件上多了一道深色横杠。
 * 查看器/滑卡页底部那条色标刚好撞上它: 把手里左边那 5 个色点只有**最后一个(紫)**被穿过,
 * 看上去就像"紫色被选中了", 而这张照片其实一个色标都没有(`无标签`、紫的张数是 0)。
 *
 * 用 `navigationBars` 而不是 `safeDrawing`: 底部还有输入法([WindowInsets.ime]),
 * 而这两个页面的底栏**不该**被键盘顶起来(它们没有输入框)。
 */
@Composable
internal fun Modifier.bottomBarInsets(): Modifier = navigationBarsPadding()
