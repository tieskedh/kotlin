/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Records a final emitter signature without remapping its logical Kotlin declaration. The
 * producer category callback must use observed TypeDef facts, not a logical classifier guess.
 * Slot domains here describe structural owner dependence only; this signature authenticates a
 * MethodDef and does not by itself authorize a semantic operation.
 */
internal fun DotNetIlMethodSignature.toGenericOwnerPhysicalMethodSignatureRecord(
    coreLibrary: DotNetCoreLibraryProfile,
    producerTypeCategory: (DotNetIlClassInfo) -> DotNetGenericOwnerPhysicalNamedTypeCategory,
): DotNetGenericOwnerPhysicalMethodSignatureRecord {
    require(!hasThis || parameterTypes.isNotEmpty()) {
        "an instance emitter signature requires its implicit receiver slot"
    }
    val recorder = PhysicalMethodSignatureTypeRecorder(coreLibrary, producerTypeCategory)
    fun slot(type: DotNetIlValueType, result: Boolean): DotNetGenericOwnerPhysicalValueSlotRecord {
        val physicalType = recorder.record(type)
        val domain = if (physicalType.containsOwnerParameter()) {
            if (result) DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
            else DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
        } else {
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
        }
        return DotNetGenericOwnerPhysicalValueSlotRecord(domain, physicalType)
    }
    val resultLayout = when (val result = returnType) {
        DotNetIlReturnType.Void -> {
            require(!hasSplitNullableResult) { "a void emitter signature cannot have a split-nullable result" }
            DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void
        }
        is DotNetIlReturnType.Value -> {
            val resultSlot = slot(result.type, result = true)
            if (hasSplitNullableResult) DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable(resultSlot)
            else DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct(resultSlot)
        }
    }
    return DotNetGenericOwnerPhysicalMethodSignatureRecord(
        isInstance = hasThis,
        genericArity = methodGenericParameterCount,
        resultLayout = resultLayout,
        parameterSlots = parameterTypes.drop(if (hasThis) 1 else 0).map { type -> slot(type, result = false) },
    ).also(DotNetGenericOwnerPhysicalFamilyCodec::requireBoundedPhysicalMethodSignature)
}

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.containsOwnerParameter(): Boolean =
    kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER || arguments.any { it.containsOwnerParameter() }

private class PhysicalMethodSignatureTypeRecorder(
    private val coreLibrary: DotNetCoreLibraryProfile,
    private val producerTypeCategory: (DotNetIlClassInfo) -> DotNetGenericOwnerPhysicalNamedTypeCategory,
) {
    fun record(type: DotNetIlValueType): DotNetGenericOwnerPhysicalTypeExpressionRecord = when (type) {
        DotNetIlValueType.Boolean -> DotNetGenericOwnerPhysicalTypeExpressionRecord.booleanType()
        DotNetIlValueType.Int32 -> DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()
        DotNetIlValueType.String -> DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()
        DotNetIlValueType.Object -> DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
        DotNetIlValueType.Int8 -> coreValueType("SByte")
        DotNetIlValueType.Int16 -> coreValueType("Int16")
        DotNetIlValueType.Int64 -> coreValueType("Int64")
        DotNetIlValueType.Float32 -> coreValueType("Single")
        DotNetIlValueType.Float64 -> coreValueType("Double")
        DotNetIlValueType.Char -> coreValueType("Char")
        is DotNetIlValueType.TypeParameter -> if (type.isMethodParameter) {
            DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(type.index)
        } else {
            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(type.index)
        }
        is DotNetIlValueType.GenericArray -> DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(record(type.elementType))
        is DotNetIlValueType.NullableValue -> {
            require(type.coreLibraryReference == coreLibrary.reference) {
                "a nullable emitter carrier refers to a different core-library profile"
            }
            coreValueType("Nullable", listOf(record(type.elementType)))
        }
        is DotNetIlValueType.ErasedGenericArray -> {
            require(type.coreLibraryReference == coreLibrary.reference) {
                "an erased-array emitter carrier refers to a different core-library profile"
            }
            DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                listOf("System", "Array"),
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            )
        }
        is DotNetIlValueType.PrimitiveArray -> externalReference(
            DotNetRuntimeLibrary.ASSEMBLY_NAME,
            listOf("Kotlin.${type.abi.wrapperSimpleName}"),
            emptyList(),
        )
        is DotNetIlValueType.UserClass -> named(type.classInfo, emptyList())
        is DotNetIlValueType.GenericInstance -> named(type.classInfo, type.arguments.map(::record))
        is DotNetIlValueType.MappedClass -> mappedReference(type.ilTypeRef)
        is DotNetIlValueType.ByReference -> error(
            "an ordinary emitter value signature cannot contain a managed pointer; " +
                    "split-nullable results use their explicit result layout"
        )
    }

    private fun coreValueType(
        name: String,
        arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
    ): DotNetGenericOwnerPhysicalTypeExpressionRecord = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
        listOf("System", name),
        DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
        arguments,
    )

    private fun named(
        classInfo: DotNetIlClassInfo,
        arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord>,
    ): DotNetGenericOwnerPhysicalTypeExpressionRecord {
        require(classInfo.typeParameterCount == arguments.size) {
            "an emitter named carrier must retain its exact physical generic arity"
        }
        val assemblyName = classInfo.containingAssemblyName
        if (assemblyName == null) {
            return DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                classInfo.physicalPathComponents(),
                producerTypeCategory(classInfo),
                arguments,
            )
        }
        return externalReference(assemblyName, classInfo.physicalPathComponents(), arguments)
    }

    private fun externalReference(
        assemblyName: String,
        physicalPath: List<String>,
        arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord>,
    ): DotNetGenericOwnerPhysicalTypeExpressionRecord {
        val topLevelName = physicalPath.first()
        val namespaceName = topLevelName.substringBeforeLast('.', "")
        val topLevelMetadataName = topLevelName.substringAfterLast('.')
        val metadataName = if (physicalPath.size == 1) {
            val aritySuffix = if (arguments.isEmpty()) "" else "`${arguments.size}"
            require(aritySuffix.isEmpty() || topLevelMetadataName.endsWith(aritySuffix)) {
                "an external emitter carrier name disagrees with its physical generic arity"
            }
            topLevelMetadataName.removeSuffix(aritySuffix)
        } else {
            // The slash is an explicit nesting marker. Every component retains its emitted
            // metadata arity; genericArity below is the complete signature argument vector.
            (listOf(topLevelMetadataName) + physicalPath.drop(1)).joinToString("/")
        }
        val path = namespaceName.takeIf(String::isNotEmpty)?.split('.').orEmpty() + metadataName
        val isCoreLibrary = assemblyName == coreLibrary.assemblyName
        return DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = if (isCoreLibrary) DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY
            else DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
            assemblyName = assemblyName.takeUnless { isCoreLibrary },
            typePath = path,
            genericArity = arguments.size,
            namedTypeCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            arguments = arguments,
        )
    }

    /** MappedClass is the legacy physical carrier whose named identity exists only as an IL reference. */
    private fun mappedReference(typeReference: String): DotNetGenericOwnerPhysicalTypeExpressionRecord {
        require(typeReference.startsWith('[') && typeReference.indexOf(']') > 1) {
            "a mapped emitter carrier requires an assembly-qualified physical type reference"
        }
        val assemblyEnd = typeReference.indexOf(']')
        val assemblyName = typeReference.substring(1, assemblyEnd)
        val identifier = typeReference.substring(assemblyEnd + 1)
        val metadataName = if (identifier.startsWith('\'')) {
            require(identifier.endsWith('\'')) { "a mapped emitter carrier has an incomplete quoted identifier" }
            buildString {
                var index = 1
                while (index < identifier.lastIndex) {
                    val character = identifier[index++]
                    if (character == '\\') {
                        require(index < identifier.lastIndex) { "a mapped emitter carrier has an incomplete identifier escape" }
                        append(identifier[index++])
                    } else {
                        require(character != '\'') { "a mapped emitter carrier has an invalid quoted identifier" }
                        append(character)
                    }
                }
            }
        } else {
            require(identifier.none { it == '\'' || it == '/' || it == '<' || it == '>' }) {
                "a mapped emitter carrier has a non-nominal physical type reference"
            }
            identifier
        }
        return externalReference(assemblyName, listOf(metadataName), emptyList())
    }
}
