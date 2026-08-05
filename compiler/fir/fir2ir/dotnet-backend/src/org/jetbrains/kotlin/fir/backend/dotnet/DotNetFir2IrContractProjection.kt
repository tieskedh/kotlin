/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.dotnet

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.contracts.description.ConeBooleanExpression
import org.jetbrains.kotlin.fir.contracts.description.ConeBooleanValueParameterReference
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalReturnsDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeContractConstantValues
import org.jetbrains.kotlin.fir.contracts.description.ConeEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeIsNullPredicate
import org.jetbrains.kotlin.fir.contracts.description.ConeLogicalNot
import org.jetbrains.kotlin.fir.contracts.description.ConeReturnsEffectDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isBoolean
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.isUnsignedTypeOrNullableUnsignedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractEffect
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractProjection

/**
 * Derives the exact CLR-export subset from resolved FIR while the authoritative Common contract
 * and the corresponding IR declaration are both available. The returned carrier contains no FIR
 * or backend model and may be discarded without affecting Kotlin semantics.
 */
fun collectDotNetExactContractProjections(
    firFiles: List<FirFile>,
    components: Fir2IrComponents,
): Map<IrSimpleFunction, DotNetExactContractProjection> = buildMap {
    for (file in firFiles) {
        val topLevelFunctions = buildList {
            file.acceptChildren(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) = Unit

                override fun visitNamedFunction(namedFunction: FirNamedFunction) {
                    add(namedFunction)
                }
            })
        }
        for (function in topLevelFunctions) {
            if (function.isExpect) continue
            val projection = function.exactContractProjectionOrNull() ?: continue
            val irFunction = components.declarationStorage
                .getIrFunctionSymbol(function.symbol)
                .owner as? IrSimpleFunction
                ?: continue
            val previous = put(irFunction, projection)
            check(previous == null || previous == projection) {
                "Conflicting exact CLR contract projections for '${function.symbol.callableId}'"
            }
        }
    }
}

private fun FirNamedFunction.exactContractProjectionOrNull(): DotNetExactContractProjection? {
    val effects = linkedSetOf<DotNetExactContractEffect>()
    if (symbol.resolvedReturnTypeRef.coneType.isNothing) {
        effects += DotNetExactContractEffect.DoesNotReturn
    }
    symbol.resolvedContractDescription?.effects?.forEach { declaration ->
        declaration.effect.exactContractEffectOrNull(this)?.let(effects::add)
    }
    val normalizedEffects = effects.normalizedExactContractEffects()
    if (normalizedEffects.isEmpty()) return null
    return DotNetExactContractProjection(normalizedEffects.sortedBy(DotNetExactContractEffect::sortKey))
}

/** Keeps one valid standard-attribute instance per non-repeatable parameter target. */
private fun Set<DotNetExactContractEffect>.normalizedExactContractEffects(): Set<DotNetExactContractEffect> {
    val normalized = toMutableSet()
    val unconditionalNotNullParameters = filterIsInstance<DotNetExactContractEffect.ParameterNotNull>()
        .mapTo(hashSetOf(), DotNetExactContractEffect.ParameterNotNull::valueParameterIndex)
    val conditionalNotNullByParameter = filterIsInstance<DotNetExactContractEffect.ParameterNotNullWhen>()
        .groupBy(DotNetExactContractEffect.ParameterNotNullWhen::valueParameterIndex)
    for (entry in conditionalNotNullByParameter.entries) {
        val returnValues = entry.value.mapTo(hashSetOf(), DotNetExactContractEffect.ParameterNotNullWhen::returnValue)
        if (entry.key in unconditionalNotNullParameters || returnValues.size == 2) {
            normalized.removeAll(entry.value.toSet())
        }
        if (entry.key !in unconditionalNotNullParameters && returnValues.size == 2) {
            normalized += DotNetExactContractEffect.ParameterNotNull(entry.key)
        }
    }

    val doesNotReturnIfEffects = filterIsInstance<DotNetExactContractEffect.DoesNotReturnIf>()
    if (DotNetExactContractEffect.DoesNotReturn in this) {
        normalized.removeAll(doesNotReturnIfEffects.toSet())
    } else {
        val conditionalNonReturnByParameter = doesNotReturnIfEffects.groupBy(
            DotNetExactContractEffect.DoesNotReturnIf::valueParameterIndex
        )
        for (entry in conditionalNonReturnByParameter.entries) {
            val parameterValues = entry.value.mapTo(hashSetOf(), DotNetExactContractEffect.DoesNotReturnIf::parameterValue)
            if (parameterValues.size == 2) normalized.removeAll(entry.value.toSet())
        }
    }
    return normalized
}

private fun ConeEffectDeclaration.exactContractEffectOrNull(
    function: FirNamedFunction,
): DotNetExactContractEffect? = when (this) {
    is ConeConditionalEffectDeclaration -> exactConditionalEffectOrNull(function)
    is ConeConditionalReturnsDeclaration -> exactConditionalReturnOrNull(function)
    else -> null
}

private fun ConeConditionalEffectDeclaration.exactConditionalEffectOrNull(
    function: FirNamedFunction,
): DotNetExactContractEffect? {
    val returns = effect as? ConeReturnsEffectDeclaration ?: return null
    val returnedConstant = returns.value.name
    val notNullParameter = condition.nonNullValueParameterIndexOrNull(function)
    if (notNullParameter != null) {
        return when (returnedConstant) {
            ConeContractConstantValues.WILDCARD.name ->
                DotNetExactContractEffect.ParameterNotNull(notNullParameter)
            ConeContractConstantValues.TRUE.name -> if (function.symbol.resolvedReturnTypeRef.coneType.isBoolean) {
                DotNetExactContractEffect.ParameterNotNullWhen(notNullParameter, returnValue = true)
            } else {
                null
            }
            ConeContractConstantValues.FALSE.name -> if (function.symbol.resolvedReturnTypeRef.coneType.isBoolean) {
                DotNetExactContractEffect.ParameterNotNullWhen(notNullParameter, returnValue = false)
            } else {
                null
            }
            else -> null
        }
    }
    if (returnedConstant != ConeContractConstantValues.WILDCARD.name) return null
    val booleanCondition = condition.booleanValueParameterConditionOrNull(function) ?: return null
    return DotNetExactContractEffect.DoesNotReturnIf(
        valueParameterIndex = booleanCondition.valueParameterIndex,
        parameterValue = !booleanCondition.normalReturnValue,
    )
}

private fun ConeConditionalReturnsDeclaration.exactConditionalReturnOrNull(
    function: FirNamedFunction,
): DotNetExactContractEffect? {
    val returns = returnsEffect as? ConeReturnsEffectDeclaration ?: return null
    if (returns.value.name != ConeContractConstantValues.NOT_NULL.name) return null
    if (!function.symbol.resolvedReturnTypeRef.coneType.isExactClrReferenceContractType(function)) return null
    val parameterIndex = argumentsCondition.nonNullValueParameterIndexOrNull(function) ?: return null
    return DotNetExactContractEffect.ReturnNotNullIfParameterNotNull(parameterIndex)
}

private fun ConeBooleanExpression.nonNullValueParameterIndexOrNull(function: FirNamedFunction): Int? {
    val predicate = this as? ConeIsNullPredicate ?: return null
    if (!predicate.isNegated) return null
    val index = predicate.arg.parameterIndex
    val parameter = function.valueParameters.getOrNull(index) ?: return null
    if (!parameter.symbol.resolvedReturnTypeRef.coneType.isExactClrReferenceContractType(function)) return null
    return index
}

private data class BooleanValueParameterCondition(
    val valueParameterIndex: Int,
    val normalReturnValue: Boolean,
)

private fun ConeBooleanExpression.booleanValueParameterConditionOrNull(
    function: FirNamedFunction,
): BooleanValueParameterCondition? {
    val reference: ConeBooleanValueParameterReference
    val normalReturnValue: Boolean
    when (this) {
        is ConeBooleanValueParameterReference -> {
            reference = this
            normalReturnValue = true
        }
        is ConeLogicalNot -> {
            reference = arg as? ConeBooleanValueParameterReference ?: return null
            normalReturnValue = false
        }
        else -> return null
    }
    val parameter = function.valueParameters.getOrNull(reference.parameterIndex) ?: return null
    if (!parameter.symbol.resolvedReturnTypeRef.coneType.isBoolean) return null
    return BooleanValueParameterCondition(reference.parameterIndex, normalReturnValue)
}

private fun ConeKotlinType.isExactClrReferenceContractType(function: FirNamedFunction): Boolean {
    val expandedType = fullyExpandedType(function.moduleData.session)
    return expandedType is ConeClassLikeType &&
            !expandedType.isPrimitiveOrNullablePrimitive &&
            !expandedType.isUnsignedTypeOrNullableUnsignedType
}

private fun DotNetExactContractEffect.sortKey(): String = when (this) {
    DotNetExactContractEffect.DoesNotReturn -> "0"
    is DotNetExactContractEffect.ParameterNotNull -> "1:$valueParameterIndex"
    is DotNetExactContractEffect.ParameterNotNullWhen ->
        "2:$valueParameterIndex:${if (returnValue) 1 else 0}"
    is DotNetExactContractEffect.ReturnNotNullIfParameterNotNull -> "3:$valueParameterIndex"
    is DotNetExactContractEffect.DoesNotReturnIf ->
        "4:$valueParameterIndex:${if (parameterValue) 1 else 0}"
}
