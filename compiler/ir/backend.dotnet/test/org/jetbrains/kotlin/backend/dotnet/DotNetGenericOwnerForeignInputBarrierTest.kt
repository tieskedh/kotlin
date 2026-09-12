/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotNetGenericOwnerForeignInputBarrierTest {
    private val objects = listOf(DotNetIlValueType.Object)

    @Test
    fun bindsOnlyTheRecordedOwnerParameterAndBothObjectEntries() {
        val barrier = DotNetGenericOwnerForeignNullInputBarrier(1)
        val inputs = listOf(DotNetIlValueType.TypeParameter(1, isMethodParameter = false))
        assertTrue(barrier.binds(inputs, objects, objects, ownerArity = 2))
        assertFalse(barrier.binds(inputs, objects, objects, ownerArity = 1))
        assertFalse(barrier.binds(inputs, objects, objects, ownerArity = 0))
        assertFalse(barrier.binds(inputs, inputs, objects, ownerArity = 2))
        assertFalse(barrier.binds(inputs, objects, inputs, ownerArity = 2))
    }

    @Test
    fun rejectsChangedBindersAndUnprovedPhysicalConversions() {
        val barrier = DotNetGenericOwnerForeignNullInputBarrier(0)
        val inputs = listOf(DotNetIlValueType.TypeParameter(0, isMethodParameter = false))
        assertTrue(barrier.binds(inputs, objects, objects, ownerArity = 1))
        listOf(
            DotNetIlValueType.TypeParameter(0, isMethodParameter = true),
            DotNetIlValueType.TypeParameter(1, isMethodParameter = false),
            DotNetIlValueType.Object,
            DotNetIlValueType.Int32,
            DotNetIlValueType.String,
        ).forEach { other ->
            assertFalse(barrier.binds(listOf(other), objects, objects, ownerArity = 1), "$other is not the selected !0")
        }
        assertFalse(barrier.binds(emptyList(), objects, objects, ownerArity = 1))
        assertFalse(barrier.binds(inputs + inputs, objects, objects, ownerArity = 1))
        assertFalse(barrier.binds(inputs, objects + objects, objects, ownerArity = 1))
        assertFalse(barrier.binds(inputs, objects, objects + objects, ownerArity = 1))
        assertFalse(DotNetGenericOwnerForeignNullInputBarrier(-1).binds(inputs, objects, objects, 1))
    }
}
