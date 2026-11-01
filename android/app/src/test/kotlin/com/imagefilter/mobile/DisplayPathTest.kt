// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.ui.displayPath
import com.imagefilter.mobile.ui.secondaryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **路径的显示形态**(FIX 2): 把 Windows 的 verbatim 前缀 `\\?\` 去掉。
 *
 * 为什么这件事要有单测(它不是"一个字符串替换"): 这条链路上"显示"与"传输"必须是**两个**
 * 函数, 而两者的差别只有一个前缀 —— 一个写错的 `removePrefix` 放在"路径进来时"就会让
 * 桌面端那座桥收到的路径与它自己的共享根对不上(全部 403), 而界面上**看不出任何异常**。
 * 所以这里逐条钉住"显示成什么", 并在最后一条钉住"那份规范路径一个字都没被动过"。
 */
class DisplayPathTest {

    @Test
    fun a_verbatim_drive_path_is_shown_the_way_windows_shows_it() {
        // 真机上量到的那一条: 网格表头显示的是 `\\?\F:\壁纸`, 而用户在自己的电脑上看到的是 `F:\壁纸`。
        assertEquals("F:\\壁纸", displayPath("\\\\?\\F:\\壁纸"))
        assertEquals("D:\\", displayPath("\\\\?\\D:\\"))
        assertEquals("C:\\照片 2026", displayPath("\\\\?\\C:\\照片 2026"))
    }

    @Test
    fun a_path_without_the_prefix_is_untouched() {
        // 对照: 服务端也可能配置成不带前缀的共享根 —— 那种路径必须**逐字**照旧显示。
        assertEquals("F:\\壁纸", displayPath("F:\\壁纸"))
        assertEquals("E:\\图库\\a.jpg", displayPath("E:\\图库\\a.jpg"))
        assertEquals("/sdcard/DCIM", displayPath("/sdcard/DCIM"))
    }

    @Test
    fun a_verbatim_unc_path_gets_the_double_backslash_form_back() {
        // `\\?\UNC\server\share` 的普通写法是 `\\server\share` —— 只去掉前四个字符会得到
        // 一个 `UNC\server\share`(看起来就像坏掉的路径)。
        assertEquals("\\\\server\\share", displayPath("\\\\?\\UNC\\server\\share"))
        assertEquals("\\\\server\\share\\x", displayPath("\\\\?\\UNC\\server\\share\\x"))
    }

    @Test
    fun a_form_without_a_plain_equivalent_is_left_alone() {
        // 卷 GUID 路径(`\\?\Volume{…}\`)在普通写法里**存在不了**: 去掉前缀只会得到一个
        // 更看不懂、而且看起来坏掉的字符串。宁可原样显示。
        val volume = "\\\\?\\Volume{3f9a-11ed}\\照片"
        assertEquals(volume, displayPath(volume))
        // 前缀后面既不是盘符也不是 UNC 的, 一律不动(不猜)。
        assertEquals("\\\\?\\not-a-path", displayPath("\\\\?\\not-a-path"))
    }

    @Test
    fun a_root_rows_secondary_label_hides_the_verbatim_prefix() {
        // 抽屉里根行的第二行就是路径; 它必须与 `AppPathText` 走同一个显示函数,
        // 否则同一个目录在抽屉里是 `\\?\F:\`、在别处是 `F:\`。
        val root = FolderEntryDto(path = "\\\\?\\F:\\", name = "F:")
        assertEquals("F:\\", secondaryLabel(root, isRoot = true))
    }

    @Test
    fun the_display_transform_never_returns_the_canonical_form() {
        // 这条断言看着像废话, 但它钉住的是**这件事本身**: 界面拿到的与协议用的是**两个**值。
        // 少了它, 有人把 displayPath 换成 identity("反正只差四个字符")也不会有任何用例变红 ——
        // 而那时 FIX 2 就悄悄退回去了。
        val canonical = "\\\\?\\F:\\壁纸"
        assertNotEquals(canonical, displayPath(canonical))
        assertTrue("显示形态里不该再有 verbatim 前缀", !displayPath(canonical).startsWith("\\\\?\\"))
    }
}
