/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test.collections

import kotlin.test.*

class ByteDomainValueSetTest {

    @Test
    fun acceptsEveryByteDomainIndexExactlyOnce() {
        val set = ByteDomainValueSet()
        for (index in 0..255) {
            assertTrue(set.add(index), "first add of $index")
        }
        for (index in 0..255) {
            assertFalse(set.add(index), "second add of $index")
        }
    }

    @Test
    fun addsDistinctIndicesIndependently() {
        val set = ByteDomainValueSet()

        assertTrue(set.add(1), "first add of 1")
        assertTrue(set.add(70), "first add of 70")
        assertTrue(set.add(150), "first add of 150")
        assertTrue(set.add(220), "first add of 220")

        assertFalse(set.add(1), "second add of 1")
        assertFalse(set.add(150), "second add of 150")

        assertTrue(set.add(2), "first add of 2")
        assertTrue(set.add(151), "first add of 151")
    }
}
