// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * App 自带的线性图标 —— **不新增图标依赖**。
 *
 * 项目原本用 `▸ / › / ✓ / ★` 这些文本字形当图标, 好处是零依赖, 坏处是不同 OEM
 * 的字体回退会把同一套界面的图标画成不同粗细、不同基线。这里用 24×24 的路径
 * 画出同一套 1.8 单位的圆头线, 由 `Icon(tint = …)` 上色, 与 SF Symbols 的观感
 * 接近, 但仍然是 Compose 原生路径, 不搬 Cupertino 的资产。
 *
 * 一条纪律: 线性图标的描边**只有这一种** (1.8 / 圆头 / 圆角连接)。要加粗只加尺寸,
 * 不要在这个文件里出现第二套描边宽度 —— 同一屏里两种线宽看起来就是两个图标库。
 */
private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

private fun filledIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            pathBuilder = block,
        )
    }.build()

/** 两个半圆拼成一个整圆; 只给状态点/滑块头用。 */
private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    moveTo(cx + radius, cy)
    arcTo(radius, radius, 0f, false, true, cx - radius, cy)
    arcTo(radius, radius, 0f, false, true, cx + radius, cy)
    close()
}

object AppIcons {
    val Menu: ImageVector = lineIcon("Menu") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }

    val Settings: ImageVector = lineIcon("Settings") {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17.5f); lineTo(20f, 17.5f)
        circle(9f, 6.5f, 2.1f)
        circle(15f, 12f, 2.1f)
        circle(7f, 17.5f, 2.1f)
    }

    val Back: ImageVector = lineIcon("Back") {
        moveTo(15f, 4.5f); lineTo(7.5f, 12f); lineTo(15f, 19.5f)
    }

    val Close: ImageVector = lineIcon("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val ChevronRight: ImageVector = lineIcon("ChevronRight") {
        moveTo(9f, 4.5f); lineTo(16.5f, 12f); lineTo(9f, 19.5f)
    }

    val ChevronDown: ImageVector = lineIcon("ChevronDown") {
        moveTo(4.5f, 9f); lineTo(12f, 16.5f); lineTo(19.5f, 9f)
    }

    val Check: ImageVector = lineIcon("Check") {
        moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f)
    }

    val Refresh: ImageVector = lineIcon("Refresh") {
        moveTo(19.5f, 12f)
        arcTo(7.5f, 7.5f, 0f, true, true, 12f, 4.5f)
        moveTo(8.8f, 7.8f); lineTo(12f, 4.5f); lineTo(15.2f, 7.8f)
    }

    val Folder: ImageVector = lineIcon("Folder") {
        moveTo(3.5f, 7f)
        curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
        lineTo(9f, 5f)
        lineTo(11f, 7f)
        lineTo(18.5f, 7f)
        curveTo(19.6f, 7f, 20.5f, 7.9f, 20.5f, 9f)
        lineTo(20.5f, 18f)
        curveTo(20.5f, 19.1f, 19.6f, 20f, 18.5f, 20f)
        lineTo(5.5f, 20f)
        curveTo(4.4f, 20f, 3.5f, 19.1f, 3.5f, 18f)
        close()
    }

    val Image: ImageVector = lineIcon("Image") {
        moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        circle(9f, 9.5f, 1.6f)
        moveTo(4f, 16.5f); lineTo(9f, 11.5f); lineTo(13f, 15.5f)
        lineTo(16f, 12.5f); lineTo(20f, 16.5f)
    }

    /** 实心星: 打分明细与角标。 */
    val Star: ImageVector = filledIcon("Star") {
        moveTo(12f, 2.5f)
        lineTo(14.47f, 8.6f)
        lineTo(21.03f, 9.06f)
        lineTo(15.99f, 13.3f)
        lineTo(17.58f, 19.69f)
        lineTo(12f, 16.2f)
        lineTo(6.42f, 19.69f)
        lineTo(8.01f, 13.3f)
        lineTo(2.97f, 9.06f)
        lineTo(9.53f, 8.6f)
        close()
    }

    /** 空心星: 未点亮的档位。 */
    val StarOutline: ImageVector = lineIcon("StarOutline") {
        moveTo(12f, 2.5f)
        lineTo(14.47f, 8.6f)
        lineTo(21.03f, 9.06f)
        lineTo(15.99f, 13.3f)
        lineTo(17.58f, 19.69f)
        lineTo(12f, 16.2f)
        lineTo(6.42f, 19.69f)
        lineTo(8.01f, 13.3f)
        lineTo(2.97f, 9.06f)
        lineTo(9.53f, 8.6f)
        close()
    }

    val Computer: ImageVector = lineIcon("Computer") {
        moveTo(3.5f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 16.5f); lineTo(3.5f, 16.5f); close()
        moveTo(9f, 20f); lineTo(15f, 20f)
        moveTo(12f, 16.5f); lineTo(12f, 20f)
    }

    val Wifi: ImageVector = lineIcon("Wifi") {
        moveTo(3.5f, 9f)
        curveTo(6.3f, 6.2f, 9.1f, 4.8f, 12f, 4.8f)
        curveTo(14.9f, 4.8f, 17.7f, 6.2f, 20.5f, 9f)
        moveTo(6.5f, 12.5f)
        curveTo(8.3f, 10.7f, 10.1f, 9.8f, 12f, 9.8f)
        curveTo(13.9f, 9.8f, 15.7f, 10.7f, 17.5f, 12.5f)
        moveTo(9.5f, 16f)
        curveTo(10.5f, 15f, 11.2f, 14.5f, 12f, 14.5f)
        curveTo(12.8f, 14.5f, 13.5f, 15f, 14.5f, 16f)
    }

    val Camera: ImageVector = lineIcon("Camera") {
        moveTo(3.5f, 8.5f)
        lineTo(8f, 8.5f)
        lineTo(9.5f, 5.5f)
        lineTo(14.5f, 5.5f)
        lineTo(16f, 8.5f)
        lineTo(20.5f, 8.5f)
        lineTo(20.5f, 19.5f)
        lineTo(3.5f, 19.5f)
        close()
        circle(12f, 13.5f, 3.4f)
    }

    val Undo: ImageVector = lineIcon("Undo") {
        moveTo(8.5f, 4.5f); lineTo(4f, 9f); lineTo(8.5f, 13.5f)
        moveTo(4f, 9f); lineTo(14f, 9f)
        curveTo(17.3f, 9f, 20f, 11.7f, 20f, 15f)
        curveTo(20f, 18.3f, 17.3f, 21f, 14f, 21f)
        lineTo(10f, 21f)
    }

    /** 移动分类: 文件夹 + 右箭头。 */
    val Move: ImageVector = lineIcon("Move") {
        moveTo(3.5f, 7f)
        curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
        lineTo(9f, 5f)
        lineTo(11f, 7f)
        lineTo(18.5f, 7f)
        curveTo(19.6f, 7f, 20.5f, 7.9f, 20.5f, 9f)
        lineTo(20.5f, 18f)
        curveTo(20.5f, 19.1f, 19.6f, 20f, 18.5f, 20f)
        lineTo(5.5f, 20f)
        curveTo(4.4f, 20f, 3.5f, 19.1f, 3.5f, 18f)
        close()
        moveTo(13f, 12f); lineTo(20f, 12f)
        moveTo(17f, 9f); lineTo(20f, 12f); lineTo(17f, 15f)
    }

    val More: ImageVector = filledIcon("More") {
        circle(6f, 12f, 1.4f)
        circle(12f, 12f, 1.4f)
        circle(18f, 12f, 1.4f)
    }
}
