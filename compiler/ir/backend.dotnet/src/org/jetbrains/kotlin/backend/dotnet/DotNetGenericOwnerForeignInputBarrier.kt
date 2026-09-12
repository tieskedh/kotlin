/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Common's one-argument NULL-default barrier, attached to a selected foreign override route.
 * This is permission to check an input, not evidence of its physical carrier. The emitter must
 * bind the actual owner parameter and both object-domain entries before issuing a conversion.
 */
internal data class DotNetGenericOwnerForeignNullInputBarrier(
    val ownerParameterIndex: Int,
) {
    fun binds(
        naturalInputs: List<DotNetIlValueType>,
        semanticInputs: List<DotNetIlValueType>,
        dispatcherInputs: List<DotNetIlValueType>,
        ownerArity: Int,
    ): Boolean = ownerParameterIndex in 0 until ownerArity &&
            naturalInputs == listOf(DotNetIlValueType.TypeParameter(ownerParameterIndex, isMethodParameter = false)) &&
            semanticInputs == listOf(DotNetIlValueType.Object) &&
            dispatcherInputs == semanticInputs
}
