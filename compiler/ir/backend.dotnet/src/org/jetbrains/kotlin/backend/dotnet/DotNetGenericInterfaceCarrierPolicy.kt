/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.types.Variance

/**
 * Existing bounded negative policy for an independently selected natural interface declaration.
 * A false result proves neither a TypeDef nor a physical argument carrier. The caller owns that
 * authority and recursively checks nested constructions separately. Positive producer/MethodDef
 * evidence may retain a natural view before consulting this conservative fallback.
 *
 * Constructor planning refines only reference contravariance with this policy; its separate
 * output-view hazard remains in force.
 */
internal fun IrSimpleType.requiresDotNetSemanticInterfaceCarrier(
    declaredVariances: List<Variance>,
    physicalVariances: List<DotNetGenericOwnerPhysicalTypeParameterVariance>,
): Boolean {
    if (arguments.size != declaredVariances.size || physicalVariances.size != declaredVariances.size) return true
    return declaredVariances.indices.any { index ->
        val variance = declaredVariances[index]
        val projection = arguments[index] as? IrTypeProjection ?: return@any true
        if (projection.variance != Variance.INVARIANT) return@any true
        if (variance != Variance.INVARIANT &&
            physicalVariances[index] == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        ) return@any true
        val argumentClassifier = (projection.type as? IrSimpleType)?.classifier
        when (variance) {
            // A reference-looking Kotlin argument is not complete natural-view authority:
            // Source<String> also admits Source<Nothing>, which is not CLR Source<string>.
            // This is negative evidence only; an already-proven exact producer still wins.
            Variance.OUT_VARIANCE -> true
            Variance.IN_VARIANCE ->
                argumentClassifier is IrTypeParameterSymbol || projection.type.hasClrValueGenericArgumentCarrier()
            Variance.INVARIANT -> argumentClassifier is IrTypeParameterSymbol && projection.type.isMarkedNullable()
        }
    }
}

private fun IrType.hasClrValueGenericArgumentCarrier(): Boolean {
    if (isPrimitiveType() || isPrimitiveType(nullable = true)) return true
    val valueClassCarrier = dotNetUnboxedValueClassTypeOrNull() ?: return false
    return valueClassCarrier.isPrimitiveType() || valueClassCarrier.isPrimitiveType(nullable = true)
}
