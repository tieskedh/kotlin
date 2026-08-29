/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.types.Variance

/** Input/output polarity of one owner-parameter occurrence in a physical callable shape. */
internal enum class TypePolarity {
    OUT,
    IN,
    BOTH;

    fun through(variance: Variance): TypePolarity = when {
        this == BOTH || variance == Variance.INVARIANT -> BOTH
        variance == Variance.OUT_VARIANCE -> this
        else -> if (this == OUT) IN else OUT
    }
}

/** Whether this type contains a parameter physically captured by [owner]. */
internal fun IrType.referencesGenericOwnerParameter(owner: IrClass): Boolean {
    var current: IrClass? = owner
    while (current != null) {
        if (referencesTypeParameterOf(current)) return true
        current = if (current.isInner) current.parent as? IrClass else null
    }
    return false
}

/** Kotlin declaration/use-site variance, including the variance Kotlin permits on classes. */
internal fun IrType.isLegalAtOwnerVariance(owner: IrClass, polarity: TypePolarity): Boolean {
    val simpleType = this as? IrSimpleType ?: return true
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) {
        return when (polarity) {
            TypePolarity.OUT -> parameter.variance != Variance.IN_VARIANCE
            TypePolarity.IN -> parameter.variance != Variance.OUT_VARIANCE
            TypePolarity.BOTH -> parameter.variance == Variance.INVARIANT
        }
    }

    val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return true
    return simpleType.arguments.withIndex().all { indexedArgument ->
        val index = indexedArgument.index
        val argument = indexedArgument.value
        val projection = argument as? IrTypeProjection ?: return@all true
        val declarationVariance = classifier.typeParameters.getOrNull(index)?.variance
            ?: Variance.INVARIANT
        val effectiveVariance = if (projection.variance == Variance.INVARIANT) {
            declarationVariance
        } else {
            projection.variance
        }
        projection.type.isLegalAtOwnerVariance(owner, polarity.through(effectiveVariance))
    }
}
