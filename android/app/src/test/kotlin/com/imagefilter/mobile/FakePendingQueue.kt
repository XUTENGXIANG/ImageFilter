// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.PendingQueueStore

/**
 * 内存版 [PendingQueueStore] —— 只在 JVM 单测里用(与 `PairingStoreTest.FakePrefs` 同一种东西)。
 *
 * 它**逐字保存那个 JSON 字符串**(而不是保存一个 Map): 这样"写下去的是不是真的能读回来"就
 * 走的是与真机完全相同的那条编解码链路, 而不是测试自己搭的一份近似物。
 *
 * [yieldOnTouch] 默认 false; 打开它之后每次读/写都会挂起一次 —— 那正是"两个并发的 add 会不会
 * 互相覆盖"能被测出来的条件(单线程调度器只在挂起点才让出)。
 */
class FakePendingQueue(initial: String? = null, private val yieldOnTouch: Boolean = false) :
    PendingQueueStore {

    var value: String? = initial
    val saves = mutableListOf<String>()
    var cleared = false

    override suspend fun load(): String? {
        if (yieldOnTouch) kotlinx.coroutines.yield()
        return value
    }

    override suspend fun save(json: String) {
        if (yieldOnTouch) kotlinx.coroutines.yield()
        value = json
        saves += json
    }

    override suspend fun clear() {
        value = null
        cleared = true
    }
}
