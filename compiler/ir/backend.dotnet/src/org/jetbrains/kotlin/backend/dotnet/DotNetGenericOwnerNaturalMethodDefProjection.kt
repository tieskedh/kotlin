/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Projects the final, producer-sealed natural MethodDef into the portable ABI type grammar.
 *
 * This is deliberately IR-free.  A logical Kotlin signature, an earlier declaration plan, or an
 * emitted name cannot create this fact.  Only the exact rows which survived the final-emission
 * seal may be projected, and every family-local binder is checked before its numeric index loses
 * that local identity in the portable record.
 */
internal fun DotNetProducerGenericOwnerSealedFamilyPublication.naturalMethodDefPhysicalIdentity():
        DotNetGenericOwnerPhysicalMethodIdentityRecord {
    val naturalType = body.typeDefs.single { typeDef ->
        typeDef.role == DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE
    }.row
    val naturalMethod = body.methodDefs.single { methodDef ->
        methodDef.role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT
    }
    return naturalMethodDefPhysicalIdentity(
        key.logicalInterfaceMemberKey,
        naturalType,
        naturalMethod,
        body.typeDefs.map { typeDef -> typeDef.row },
    )
}

internal fun DotNetProducerGenericOwnerNaturalMethodDefPublication.naturalMethodDefPhysicalIdentity():
        DotNetGenericOwnerPhysicalMethodIdentityRecord = naturalMethodDefPhysicalIdentity(
    logicalMemberKey,
    naturalType,
    naturalMethod,
    listOf(naturalType),
)

private fun naturalMethodDefPhysicalIdentity(
    logicalMemberKey: String,
    naturalType: DotNetGenericOwnerSealedEmissionTypeDefRow,
    naturalMethod: DotNetProducerGenericOwnerSealedMethodDef,
    sealedTypes: List<DotNetGenericOwnerSealedEmissionTypeDefRow>,
): DotNetGenericOwnerPhysicalMethodIdentityRecord {
    val methodRow = naturalMethod.row
    val header = methodRow.structural.header
    require(header.owner == naturalType.structural.identityKey &&
            header.ownerGenericArity == naturalType.structural.genericArity &&
            header.ownerCategory == naturalType.structural.category
    ) {
        "producer-sealed natural MethodDef '$logicalMemberKey' has an incoherent owner binder"
    }
    require(naturalMethod.logicalParameterDomains.size == header.ordinaryParameterCarriers.size) {
        "producer-sealed natural MethodDef '$logicalMemberKey' has an incoherent parameter-domain vector"
    }

    val typeDefs = sealedTypes.associateBy { typeDef -> typeDef.structural.identityKey }

    fun carrier(
        shape: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ): DotNetGenericOwnerPhysicalTypeExpressionRecord = when (shape) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> when (shape.kind) {
            DotNetGenericOwnerPhysicalTypeKind.VOID ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType()
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.booleanType()
            DotNetGenericOwnerPhysicalTypeKind.INT32 ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()
            DotNetGenericOwnerPhysicalTypeKind.STRING ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()
            DotNetGenericOwnerPhysicalTypeKind.OBJECT ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
            else -> error("a bounded final-emission leaf has unsupported kind ${shape.kind}")
        }

        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
            require(shape.binder == naturalType.structural.identityKey &&
                    shape.index in 0 until naturalType.structural.genericArity
            ) {
                "producer-sealed natural MethodDef '$logicalMemberKey' has an unbound owner parameter"
            }
            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(shape.index)
        }

        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
            require(shape.binder == methodRow.structural.identityKey &&
                    shape.index in 0 until header.genericArity
            ) {
                "producer-sealed natural MethodDef '$logicalMemberKey' has an unbound method parameter"
            }
            DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(shape.index)
        }

        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
            val definition = typeDefs[shape.definition]
                ?: throw IllegalArgumentException(
                            "producer-sealed natural MethodDef '$logicalMemberKey' references " +
                            "a TypeDef outside its sealed family",
                )
            require(shape.arguments.size == definition.structural.genericArity) {
                "producer-sealed natural MethodDef '$logicalMemberKey' has an arity-mismatched construction"
            }
            DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                typePath = definition.physicalPath,
                category = definition.structural.category,
                arguments = shape.arguments.map(::carrier),
            )
        }

        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(carrier(shape.element))

        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
            throw IllegalArgumentException(
                "producer-sealed natural MethodDef '$logicalMemberKey' has an unsupported ordinary by-reference carrier",
            )

        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other ->
            throw IllegalArgumentException(
                "producer-sealed natural MethodDef '$logicalMemberKey' has an unsupported physical carrier",
            )
    }

    fun valueSlot(
        domain: DotNetGenericOwnerPhysicalSlotDomain?,
        shape: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        role: String,
    ): DotNetGenericOwnerPhysicalValueSlotRecord {
        val exactDomain = requireNotNull(domain) {
            "producer-sealed natural MethodDef '$logicalMemberKey' has no logical $role domain"
        }
        val type = carrier(shape)
        require(type.kind != DotNetGenericOwnerPhysicalTypeKind.VOID) {
            "producer-sealed natural MethodDef '$logicalMemberKey' uses void as a $role value carrier"
        }
        return DotNetGenericOwnerPhysicalValueSlotRecord(exactDomain, type)
    }

    val resultLayout = when (val result = header.result) {
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> {
            require(naturalMethod.logicalResultDomain == null) {
                "producer-sealed void natural MethodDef '$logicalMemberKey' has a logical result domain"
            }
            DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
            DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct(
                valueSlot(naturalMethod.logicalResultDomain, result.carrier, "result"),
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
            DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable(
                valueSlot(naturalMethod.logicalResultDomain, result.payload, "split-nullable payload"),
            )
    }

    return DotNetGenericOwnerPhysicalMethodIdentityRecord(
        physicalOwnerPath = naturalType.physicalPath,
        physicalMethodName = methodRow.physicalName,
        signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = header.isInstance,
            genericArity = header.genericArity,
            resultLayout = resultLayout,
            parameterSlots = header.ordinaryParameterCarriers.zip(naturalMethod.logicalParameterDomains) {
                    parameter, domain ->
                valueSlot(domain, parameter, "parameter")
            },
        ),
    )
}

internal fun DotNetProducerGenericOwnerSealedFamilyPublication.toNaturalMethodDefPhysicalDeclaration(
    logicalOwnerKey: String,
) = toNaturalMethodDefPublication(logicalOwnerKey).toPhysicalDeclaration()

internal fun DotNetProducerGenericOwnerSealedFamilyPublication.toNaturalMethodDefPublication(
    logicalOwnerKey: String,
): DotNetProducerGenericOwnerNaturalMethodDefPublication =
    body.typeDefs.single { typeDef ->
        typeDef.role == DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE
    }.let { naturalType ->
        val naturalMethod = body.methodDefs.single { methodDef ->
            methodDef.role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT
        }
        DotNetProducerGenericOwnerNaturalMethodDefPublication(
            logicalOwnerKey = logicalOwnerKey,
            logicalMemberKey = key.logicalInterfaceMemberKey,
            naturalType = naturalType.row,
            naturalMethod = naturalMethod,
        )
    }

internal fun DotNetProducerGenericOwnerNaturalMethodDefPublication.toPhysicalDeclaration():
        DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef =
    DotNetLibraryAbiCodec.producerGenericOwnerNaturalMethodDefDeclaration(this)

/**
 * Joins a producer-recorded physical declaration with the logical KLIB projection available to a
 * separately compiled consumer. The direct owner result is intentionally supplied separately:
 * logical `T?` maps to the semantic object domain, while its admitted physical layout is `!T` plus
 * the recorded split-nullable bit. Declaration-independent ordinary inputs must still agree
 * exactly; otherwise same-name/same-arity overload records could be cross-wired.
 */
internal fun inspectDotNetExternalNaturalMethodLogicalProjection(
    logicalParameterTypes: List<DotNetIlValueType>,
    logicalResultOwnerParameterIndex: Int,
    logicalSplitNullableResult: Boolean,
    recordedInfo: DotNetIlFunctionInfo,
): DotNetGenericOwnerPhysicalBindingResult<Unit> {
    if (!recordedInfo.signature.hasThis) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the producer-recorded natural MethodDef is not an instance slot",
        )
    }
    val recordedParameterTypes = recordedInfo.signature.parameterTypes.drop(1)
    if (recordedParameterTypes != logicalParameterTypes) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "physical ordinary parameters $recordedParameterTypes disagree with KLIB $logicalParameterTypes",
        )
    }
    val expectedResultType = DotNetIlValueType.TypeParameter(
        logicalResultOwnerParameterIndex,
        isMethodParameter = false,
    )
    val recordedResultType =
        (recordedInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
    if (recordedResultType != expectedResultType ||
        recordedInfo.signature.hasSplitNullableResult != logicalSplitNullableResult
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "physical result $recordedResultType " +
                    "(split=${recordedInfo.signature.hasSplitNullableResult}) disagrees with KLIB " +
                    "$expectedResultType (split=$logicalSplitNullableResult)",
        )
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
}
