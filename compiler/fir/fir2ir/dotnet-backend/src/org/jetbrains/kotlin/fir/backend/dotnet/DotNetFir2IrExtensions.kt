/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.dotnet

import org.jetbrains.kotlin.fir.backend.Fir2IrExtensions
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.overrides.IrExternalOverridabilityCondition
import org.jetbrains.kotlin.ir.overrides.MemberWithOriginal
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.types.Variance

/** Target-specific FIR2IR rules, kept next to the JVM counterpart rather than in CIL codegen. */
object DotNetFir2IrExtensions : Fir2IrExtensions by Fir2IrExtensions.Default {
    override val externalOverridabilityConditions: List<IrExternalOverridabilityCondition> =
        listOf(DotNetClrImportedSignatureOverridabilityCondition)
}

/**
 * Restores an override edge already accepted by FIR for one retained CLR method signature.
 *
 * FIR2IR's target-neutral checker can lose the frontend-approved edge when a rigid Kotlin
 * implementation is compared with an imported flexible array view, or when method-owned type
 * parameters originate in different declaration objects. JVM has target-specific IR
 * overridability rules for the same architectural reason. This success-only condition is
 * deliberately narrower: it requires an exact admitted CLR MethodDef, equal method arity and
 * parameter kinds, and the complete rigid classifier shape dictated by the retained physical
 * signature. Kotlin frontend override checking remains authoritative for source-level
 * nullability, bounds, and return covariance.
 */
private object DotNetClrImportedSignatureOverridabilityCondition : IrExternalOverridabilityCondition {
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
        val importedBase = sequenceOf(superFunction)
            .plus(superFunction.allOverridden().asSequence())
            .mapNotNull { candidate ->
                val source = candidate.containerSource as? DotNetClrImportedMethodSource
                    ?: return@mapNotNull null
                candidate to source
            }
            .distinctBy { pair -> pair.first.symbol }
            .singleOrNull()
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val importedFunction = importedBase.first
        val source = importedBase.second
        val physicalParameters = source.resolvedSignature.parameterTypes
        if (
            physicalParameters.none {
                it is DotNetClrResolvedTypeSignature.SzArray ||
                        it is DotNetClrResolvedTypeSignature.GenericParameter ||
                        it is DotNetClrResolvedTypeSignature.Named ||
                        it is DotNetClrResolvedTypeSignature.GenericInstance
            } &&
            source.resolvedSignature.genericParameterCount == 0
        ) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        if (
            importedFunction.name != subFunction.name ||
            superFunction.typeParameters.size != subFunction.typeParameters.size ||
            superFunction.typeParameters.size != source.resolvedSignature.genericParameterCount
        ) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        val superParameters = superFunction.nonDispatchParameters
        val subParameters = subFunction.nonDispatchParameters
        if (
            superParameters.size != physicalParameters.size ||
            subParameters.size != physicalParameters.size ||
            superParameters.map { it.kind } != subParameters.map { it.kind } ||
            superParameters.map { it.varargElementType != null } !=
                    subParameters.map { it.varargElementType != null }
        ) {
            return IrExternalOverridabilityCondition.Result.UNKNOWN
        }
        val importedOwner = importedFunction.parent as? IrClass
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val implementationOwner = subFunction.parent as? IrClass
            ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
            importedOwner.symbol,
            implementationOwner.defaultType,
        ) ?: return IrExternalOverridabilityCondition.Result.UNKNOWN
        val ownerTypeArguments = importedOwner.typeParameters.map { parameter ->
            ownerSubstitutor.substitute(parameter.typeParameterDefaultType)
        }
        val hasExactParameterCarriers = physicalParameters.indices.all { index ->
            val physicalType = physicalParameters[index]
            val subParameter = subParameters[index]
            val logicalType = if (
                physicalType is DotNetClrResolvedTypeSignature.SzArray &&
                subParameter.varargElementType != null
            ) {
                subParameter.varargElementType!!
            } else {
                subParameter.type
            }
            val logicalPhysicalType = if (subParameter.varargElementType != null) {
                (physicalType as? DotNetClrResolvedTypeSignature.SzArray)?.elementType
                    ?: return@all false
            } else {
                physicalType
            }
            logicalType.hasImportedClrCarrier(
                logicalPhysicalType,
                ownerTypeArguments,
                subFunction.typeParameters,
            )
        }
        return if (hasExactParameterCarriers) {
            IrExternalOverridabilityCondition.Result.OVERRIDABLE
        } else {
            IrExternalOverridabilityCondition.Result.UNKNOWN
        }
    }
}

private fun IrType.hasImportedClrCarrier(
    physicalType: DotNetClrResolvedTypeSignature,
    ownerTypeArguments: List<IrType>,
    methodTypeParameters: List<IrTypeParameter>,
): Boolean =
    when (physicalType) {
        is DotNetClrResolvedTypeSignature.Primitive ->
            classOrNull?.owner?.fqNameWhenAvailable?.asString() ==
                    physicalType.type.kotlinClassifierNameOrNull()
        is DotNetClrResolvedTypeSignature.SzArray -> {
            val simpleType = this as? IrSimpleType ?: return false
            if ((simpleType.classifier.owner as? IrClass)?.fqNameWhenAvailable?.asString() != "kotlin.Array") {
                return false
            }
            val elementProjection = simpleType.arguments.singleOrNull() as? IrTypeProjection
                ?: return false
            if (elementProjection.variance != Variance.INVARIANT) return false
            elementProjection.type.hasImportedClrCarrier(
                physicalType.elementType,
                ownerTypeArguments,
                methodTypeParameters,
            )
        }
        is DotNetClrResolvedTypeSignature.GenericParameter -> {
            val simpleType = this as? IrSimpleType
            when (physicalType.kind) {
                DotNetClrGenericParameterKind.TYPE ->
                    ownerTypeArguments.getOrNull(physicalType.index)
                        ?.let { ownerArgument ->
                            hasSameImportedClrCarrierAs(ownerArgument)
                        } == true
                DotNetClrGenericParameterKind.METHOD ->
                    simpleType?.isMarkedNullable() == false &&
                            simpleType.classifier ==
                            methodTypeParameters.getOrNull(physicalType.index)?.symbol
            }
        }
        is DotNetClrResolvedTypeSignature.Named ->
            (this as? IrSimpleType)?.classifier?.owner
                .let { owner -> owner as? IrClass }
                ?.hasImportedClrIdentity(physicalType.type) == true
        is DotNetClrResolvedTypeSignature.GenericInstance -> {
            val simpleType = this as? IrSimpleType ?: return false
            val owner = simpleType.classifier.owner as? IrClass ?: return false
            if (!owner.hasImportedClrIdentity(physicalType.genericType.type) ||
                simpleType.arguments.size != physicalType.arguments.size
            ) {
                return false
            }
            simpleType.arguments.indices.all { index ->
                val projection = simpleType.arguments[index] as? IrTypeProjection
                    ?: return@all false
                projection.variance == Variance.INVARIANT &&
                        projection.type.hasImportedClrCarrier(
                            physicalType.arguments[index],
                            ownerTypeArguments,
                            methodTypeParameters,
                        )
            }
        }
        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Array,
        is DotNetClrResolvedTypeSignature.ByReference,
        is DotNetClrResolvedTypeSignature.FunctionPointer,
        is DotNetClrResolvedTypeSignature.Modified,
        is DotNetClrResolvedTypeSignature.Pointer,
            -> false
    }

private fun IrClass.hasImportedClrIdentity(
    expected: DotNetClrResolvedTypeDefinition,
): Boolean = declarations.asSequence()
    .mapNotNull { declaration ->
        (declaration as? IrSimpleFunction)?.containerSource as?
                DotNetClrImportedDeclarationSource
    }
    .any { source ->
        source.declaringHierarchy.type.type.hasSameIdentityAs(expected)
    }

private fun IrType.hasSameImportedClrCarrierAs(other: IrType): Boolean {
    val left = this as? IrSimpleType ?: return this == other
    val right = other as? IrSimpleType ?: return false
    if (
        left.classifier != right.classifier ||
        left.isMarkedNullable() != right.isMarkedNullable() ||
        left.arguments.size != right.arguments.size
    ) {
        return false
    }
    return left.arguments.indices.all { index ->
        val leftArgument = left.arguments[index] as? IrTypeProjection ?: return@all false
        val rightArgument = right.arguments[index] as? IrTypeProjection ?: return@all false
        leftArgument.variance == rightArgument.variance &&
                leftArgument.type.hasSameImportedClrCarrierAs(rightArgument.type)
    }
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
