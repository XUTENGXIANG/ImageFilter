// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** 骨架冒烟: 证明 JVM 单测链路是通的(不需要设备、不需要模拟器)。 */
class SmokeTest {
    @Test
    fun toolchain_runs_jvm_tests() {
        assertEquals(4, 2 + 2)
    }
}
