// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.ui.childrenOf
import com.imagefilter.mobile.ui.displayNameOf
import com.imagefilter.mobile.ui.mergeChildren
import com.imagefilter.mobile.ui.photoCountLabel
import com.imagefilter.mobile.ui.rootEntries
import com.imagefilter.mobile.ui.secondaryLabel
import com.imagefilter.mobile.ui.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录树的**纯逻辑**: 合并、摊平、显示名、次级信息。
 *
 * 为什么值得整份单测: 这些函数是"用户看到什么顺序、什么字"的唯一来源, 而它们
 * **与 Compose 无关** —— 所以能在 JVM 上真的断言一遍, 不需要 UI 测试依赖
 * (M1′ 不引 Compose UI 测试; 这也是桌面端 `mergeChildren` 的既有做法)。
 *
 * 三条断言各自防一件真会发生的事:
 *   ① **最新覆盖** —— 慢盘上两次响应乱序回来, 旧数据不许盖掉新的;
 *   ② **排序稳定** —— 顺序不许随文件系统的返回顺序抖动;
 *   ③ **不透明路径逐字保留** —— 服务端给的路径(可能带 verbatim 前缀)只能原样带走,
 *      任何"重新拼一遍"的写法都会把那个字面问号弄丢。
 */
class BrowseMergeTest {

    private fun child(name: String) = FolderEntryDto(path = "D:\\p\\$name", name = name)

    private fun childOf(parent: String, name: String) = FolderEntryDto(path = "$parent\\$name", name = name)

    // ── 合并 ────────────────────────────────────────────────────────────────

    @Test
    fun merge_replaces_the_previous_children_of_the_same_parent() {
        val first = mergeChildren(emptyMap(), "D:\\p", listOf(child("b"), child("a")))
        // 子目录按名字排序 —— 用户看到的顺序应当是稳定的, 不随文件系统返回顺序抖动
        assertEquals(listOf("a", "b"), first["D:\\p"]!!.map { it.name })

        val second = mergeChildren(first, "D:\\p", listOf(child("c")))
        assertEquals(listOf("c"), second["D:\\p"]!!.map { it.name })
        assertEquals("同一父节点只应留一份", 1, second.size)
    }

    @Test
    fun merge_keeps_other_parents_untouched() {
        var idx = mergeChildren(emptyMap(), "D:\\p", listOf(child("a")))
        idx = mergeChildren(idx, "D:\\p\\a", listOf(child("x")))
        assertEquals(2, idx.size)
        assertEquals(listOf("a"), idx["D:\\p"]!!.map { it.name })
        assertEquals(listOf("x"), idx["D:\\p\\a"]!!.map { it.name })
    }

    @Test
    fun merge_sorts_case_insensitively_and_never_mutates_the_input_map() {
        // 大小写不敏感: 按原样比大小写的话 "Banana" < "apple"(大写字母的码点更小),
        // 用户会看到一个"像没排序"的列表。
        val first = mergeChildren(emptyMap(), "D:\\p", listOf(child("Banana"), child("apple"), child("Cherry")))
        assertEquals(listOf("apple", "Banana", "Cherry"), first["D:\\p"]!!.map { it.name })

        val second = mergeChildren(first, "D:\\p", listOf(child("z")))
        assertEquals(listOf("z"), second["D:\\p"]!!.map { it.name })
        // 合并是"新表": 上一次的结果不许被就地改掉(状态流的旧快照会被读到的)
        assertEquals(
            "合并改到了上一份索引",
            listOf("apple", "Banana", "Cherry"),
            first["D:\\p"]!!.map { it.name },
        )
    }

    @Test
    fun an_unloaded_layer_is_empty_but_that_is_not_the_same_as_an_empty_folder() {
        val idx = mergeChildren(emptyMap(), "D:\\p", emptyList())
        // 加载过、但服务端说这一层是空的 —— 键在
        assertTrue(idx.containsKey("D:\\p"))
        assertTrue(childrenOf(idx, "D:\\p").isEmpty())
        // 压根没加载过 —— 键不在。BrowseState.isLoaded 就是靠这个区分的
        assertFalse(idx.containsKey("D:\\q"))
        assertTrue(childrenOf(idx, "D:\\q").isEmpty())
    }

    // ── 共享根 → 根节点 ──────────────────────────────────────────────────────

    @Test
    fun root_entries_keep_the_configured_order_and_the_path_verbatim() {
        val shares = listOf("\\\\?\\D:\\照片 2026", "E:\\图库", "D:\\照片 2026")
        val roots = rootEntries(shares)

        // 顺序 = 电脑上的配置顺序(用户排过序, 手机不替他重排); 近重复的路径也照收
        assertEquals(shares, roots.map { it.path })
        assertEquals(listOf("照片 2026", "图库", "照片 2026"), roots.map { it.name })
        // 根节点这一层**拿不到**张数(browse 才给, 而且是 0), 所以不许瞎填
        assertEquals(0, roots[0].photoCount)
        assertTrue("空共享根不该变出一行点不开的东西", rootEntries(listOf("", "  ")).isEmpty())
    }

    @Test
    fun display_name_cuts_at_the_last_separator_and_never_rewrites_the_path() {
        assertEquals("照片 2026", displayNameOf("\\\\?\\D:\\照片 2026"))
        assertEquals("照片 2026", displayNameOf("D:\\照片 2026\\"))
        assertEquals("图库", displayNameOf("E:/图库"))
        assertEquals("D:", displayNameOf("D:\\"))
        assertEquals("照片", displayNameOf("照片"))
        assertEquals("", displayNameOf(""))

        // 显示名只是显示: 它切完就完, **绝不用它拼回路径**
        val verbatim = "\\\\?\\D:\\照片 2026"
        assertEquals(verbatim, rootEntries(listOf(verbatim)).single().path)
    }

    // ── 次级信息: 子目录数 / 照片数 / 视频数 ────────────────────────────────

    @Test
    fun the_secondary_line_reports_folders_photos_and_videos() {
        // browse 现在给**直接**照片数, 所以 0 是一句诚实的话; 负数按 0 处理。
        assertEquals("0 张", photoCountLabel(0))
        assertEquals("7 张", photoCountLabel(7))
        assertEquals("0 张", photoCountLabel(-1))

        val leaf = FolderEntryDto(path = "D:\\p\\a", name = "a", photoCount = 7, videoCount = 2)
        assertEquals("7 张 · 2 视频", secondaryLabel(leaf, isRoot = false))

        val parent = FolderEntryDto(
            path = "D:\\p\\b", name = "b",
            hasSubdirs = true, subfolderCount = 3, videoCount = 1,
        )
        assertEquals("3 个文件夹 · 1 视频", secondaryLabel(parent, isRoot = false))

        assertEquals(
            "共享根的尾段名不足以说明是哪个根, 次级信息给的必须是原路径",
            "D:\\p",
            secondaryLabel(FolderEntryDto(path = "D:\\p", name = "p"), isRoot = true),
        )
    }

    // ── 摊平成可见行 ────────────────────────────────────────────────────────

    @Test
    fun visible_rows_only_walk_the_layers_the_user_actually_expanded() {
        val root = FolderEntryDto(path = "D:\\r", name = "r")
        var index = mergeChildren(emptyMap(), "D:\\r", listOf(childOf("D:\\r", "b"), childOf("D:\\r", "a")))
        index = mergeChildren(index, "D:\\r\\a", listOf(childOf("D:\\r\\a", "x")))

        // 一层都没展开 → 只有共享根。**懒加载**: 没点过的层不许出现在树里
        assertEquals(listOf("r"), visibleRows(listOf(root), index, emptySet()).map { it.entry.name })

        // 展开根 → 根 + 它的**直接**子目录(排序过); 孙辈不出现, 因为 a 没被展开
        val oneLevel = visibleRows(listOf(root), index, setOf("D:\\r"))
        assertEquals(listOf("r", "a", "b"), oneLevel.map { it.entry.name })
        assertEquals(listOf(0, 1, 1), oneLevel.map { it.depth })
        assertTrue(oneLevel[0].isRoot)
        assertFalse(oneLevel[1].isRoot)

        // 展开 a → 孙辈插在 a 下面, 而不是跑到树尾
        val twoLevels = visibleRows(listOf(root), index, setOf("D:\\r", "D:\\r\\a"))
        assertEquals(listOf("r", "a", "x", "b"), twoLevels.map { it.entry.name })
        assertEquals(listOf(0, 1, 2, 1), twoLevels.map { it.depth })
    }

    @Test
    fun visible_rows_terminates_even_if_the_server_returns_a_path_cycle() {
        // 服务端回一条指回自己的子目录时, 摊平必须停下来 —— 不能把界面栈打爆。
        // (桌面端"点设备=整盘遍历"是同一类事故的近亲, 所以这里宁可多一层保险。)
        val self = FolderEntryDto(path = "D:\\r", name = "r")
        val rows = visibleRows(listOf(self), mapOf("D:\\r" to listOf(self)), setOf("D:\\r"))
        assertEquals("自环只该画出一行", 1, rows.size)
        assertEquals("D:\\r", rows.single().entry.path)
    }
}
