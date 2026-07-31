/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.dotnet

import org.jetbrains.kotlin.fir.backend.Fir2IrExtensions
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.overrides.IrExternalOverridabilityCondition
import org.jetbrains.kotlin.ir.overrides.MemberWithOriginal
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.types.Variance

/** Target-specific FIR2IR rules, kept next to the JVM counterpart rather than in CIL codegen. */
object DotNetFir2IrExtensions : Fir2IrExtensions by Fir2IrExtensions.Default {
    override val externalOverridabilityConditions: List<IrExternalOverridabilityCondition> =
        listOf(DotNetClrFlexibleArrayOverridabilityCondition)
}

/**
 * Restores the override edge already accepted by FIR for an imported CLR array platform type.
 *
 * FIR2IR's target-neutral checker compares the rigid Kotlin implementation parameter with the
 * imported flexible upper view (`Array<E>` versus `Array<out E>?`) and otherwise leaves a fake
 * override behind. JVM has target-specific IR overridability rules for the same architectural
 * reason. This condition is deliberately narrower: it can report success only for a function
 * carrying an exact admitted CLR MethodDef, only when at least one physical parameter is an
 * ordinary SZARRAY, and only when every Kotlin implementation parameter has the classifier shape
 * dictated by that retained physical signature. Kotlin frontend override checking remains the
 * authority for source-level nullability and return covariance.
 */
private object DotNetClrFlexibleArrayOverridabilityCondition : IrExternalOverridabilityCondition {
    override val contract: IrExternalOverridabilityCondition.Contract =
        IrExternalOverridabilityCondition.Contract.SUCCESS_ONLY

    override fun isOverridable(
        superMember: MemberWithOriginal,
        subMember: MemberWithOriginal,
    ): IrExternalOverridabilityCondition.Result {
        val superFunction = superMember.original as? IrSimpleFunction
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val subFunction = subMember.original as? IrSimpleFunction
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val source = superFunction.containerSource as? DotNetClrImportedMethodSource
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val physicalParameters = source.method.signature.parameterTypes
        if (physicalParameters.none { it is DotNetClrTypeSignature.SzArray }) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        if (
            superFunction.name != subFunction.name ||
            superFunction.typeParameters.size != subFunction.typeParameters.size ||
            superFunction.typeParameters.isNotEmpty()
        ) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        val superParameters = superFunction.nonDispatchParameters
        val subParameters = subFunction.nonDispatchParameters
        if (
            superParameters.size != physicalParameters.size ||
            subParameters.size != physicalParameters.size ||
            superParameters.map { it.kind } != subParameters.map { it.kind } ||
            superParameters.any { it.varargElementType != null } ||
            subParameters.any { it.varargElementType != null }
        ) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        val hasExactParameterCarriers = physicalParameters.indices.all { index ->
            subParameters[index].type.hasImportedClrCarrier(physicalParameters[index])
        }
        return if (hasExactParameterCarriers) {
            IrExternalOverridabilityCondition.Result.OVERRIDABLE
        } else {
            IrExternalOverridabilityCondition.Result.UNKNOWN
        }
    }
}

private fun IrType.hasImportedClrCarrier(physicalType: DotNetClrTypeSignature): Boolean =
    when (physicalType) {
        is DotNetClrTypeSignature.Primitive ->
            classOrNull?.owner?.fqNameWhenAvailable?.asString() ==
                    physicalType.type.kotlinClassifierNameOrNull()
        is DotNetClrTypeSignature.SzArray -> {
            val simpleType = this as? IrSimpleType ?: return false
            if ((simpleType.classifier.owner as? IrClass)?.fqNameWhenAvailable?.asString() != "kotlin.Array") {
                return false
            }
            val elementProjection = simpleType.arguments.singleOrNull() as? IrTypeProjection
                ?: return false
            if (elementProjection.variance != Variance.INVARIANT) return false
            elementProjection.type.hasImportedClrCarrier(physicalType.elementType)
        }
        DotNetClrTypeSignature.Void,
        DotNetClrTypeSignature.TypedReference,
        is DotNetClrTypeSignature.Array,
        is DotNetClrTypeSignature.ByReference,
        is DotNetClrTypeSignature.FunctionPointer,
        is DotNetClrTypeSignature.GenericParameter,
        is DotNetClrTypeSignature.GenericInstance,
        is DotNetClrTypeSignature.Modified,
        is DotNetClrTypeSignature.Named,
        is DotNetClrTypeSignature.Pointer,
            -> false
    }

private fun DotNetClrPrimitiveType.kotlinClassifierNameOrNull(): String? = when (this) {
    DotNetClrPrimitiveType.BOOLEAN -> "kotlin.Boolean"
    DotNetClrPrimitiveType.CHAR -> "kotlin.Char"
    DotNetClrPrimitiveType.INT8 -> "kotlin.Byte"
    DotNetClrPrimitiveType.INT16 -> "kotlin.Short"
    DotNetClrPrimitiveType.INT32 -> "kotlin.Int"
    DotNetClrPrimitiveType.INT64 -> "kotlin.Long"
    DotNetClrPrimitiveType.FLOAT32 -> "kotlin.Float"
    DotNetClrPrimitiveType.FLOAT64 -> "kotlin.Double"
    DotNetClrPrimitiveType.STRING -> "kotlin.String"
    DotNetClrPrimitiveType.OBJECT -> "kotlin.Any"
    DotNetClrPrimitiveType.UINT8,
    DotNetClrPrimitiveType.UINT16,
    DotNetClrPrimitiveType.UINT32,
    DotNetClrPrimitiveType.UINT64,
    DotNetClrPrimitiveType.NATIVE_INT,
    DotNetClrPrimitiveType.NATIVE_UINT,
        -> null
}
