/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance

/** Exact PE row selected by one producer-recorded ABI-63 natural MethodDef declaration. */
data class DotNetGenericOwnerNaturalMethodDefMetadataBinding(
    val logicalMemberKey: String,
    val declaringType: DotNetClrTypeDefinition,
    val methodDefinition: DotNetClrMethodDefinition,
    val splitNullableOutParameter: DotNetClrParameterDefinition?,
)

/**
 * Validates one ABI-63 natural MethodDef record against the objective metadata of its producer DLL.
 *
 * The declaration-index loader must call this before making the record available to a separately
 * compiled consumer. A match is structural and token-exact: logical IR types, source names, and
 * overload heuristics are never used to reconstruct the MethodDef. [coreLibraryAssemblyName] is
 * the physical core-library AssemblyRef selected by the producer's recorded target profile.
 */
fun validateDotNetGenericOwnerNaturalMethodDefAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef,
    assembly: DotNetClrAssemblyMetadata,
    coreLibraryAssemblyName: String,
): DotNetGenericOwnerNaturalMethodDefMetadataBinding {
    val publication = declaration.publication()
    require(publication.logicalOwnerKey == declaration.logicalOwnerKey &&
            publication.logicalMemberKey == declaration.logicalMemberKey &&
            publication.toPhysicalDeclaration() == declaration
    ) { "a natural MethodDef descriptor is not its canonical declaration-level producer seal" }
    val naturalType = publication.naturalType
    val naturalMethod = publication.naturalMethod.row
    val binding = validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
        logicalMemberKey = declaration.logicalMemberKey,
        physicalMethod = declaration.physicalMethod,
        assembly = assembly,
        coreLibraryAssemblyName = coreLibraryAssemblyName,
    )
    assembly.requireNaturalDeclarationMatchesProducerSeal(
        logicalMemberKey = declaration.logicalMemberKey,
        naturalType = naturalType,
        naturalMethod = naturalMethod,
        binding = binding,
    )
    return binding
}

/** Signature-level token binding used by focused metadata tests before the producer-seal join. */
internal fun validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
    logicalMemberKey: String,
    physicalMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    assembly: DotNetClrAssemblyMetadata,
    coreLibraryAssemblyName: String,
): DotNetGenericOwnerNaturalMethodDefMetadataBinding {
    require(logicalMemberKey.isNotEmpty()) {
        "a generic-owner natural MethodDef validation requires a logical member key"
    }
    require(coreLibraryAssemblyName.isNotEmpty()) {
        "a generic-owner natural MethodDef validation requires the selected core-library AssemblyRef"
    }

    val declaringType = assembly.requireTypeDefinition(physicalMethod.physicalOwnerPath)
    val ownerGenericArity = assembly.requireContiguousGenericParameters(
        declaringType.handle,
        "TypeDef",
    ).size
    val expected = physicalMethod.signature
    expected.requireSupportedForExternalMetadataValidation(ownerGenericArity)

    val namedCandidates = assembly.methodDefinitions.filter { method ->
        method.declaringType == declaringType.handle &&
                method.name == physicalMethod.physicalMethodName
    }
    require(namedCandidates.isNotEmpty()) {
        "producer DLL '${assembly.identity.name}' has no MethodDef " +
                "${physicalMethod.physicalOwnerPath.renderPhysicalPath()}::${physicalMethod.physicalMethodName} " +
                "recorded for '$logicalMemberKey'"
    }

    val matches = namedCandidates.mapNotNull { method ->
        assembly.matchPhysicalMethod(
            method = method,
            expected = expected,
            ownerGenericArity = ownerGenericArity,
            coreLibraryAssemblyName = coreLibraryAssemblyName,
        )
    }
    require(matches.size == 1) {
        val reason = if (matches.isEmpty()) "no full physical signature matches" else "the full signature is ambiguous"
        "producer DLL '${assembly.identity.name}' cannot bind recorded natural MethodDef " +
                "${physicalMethod.physicalOwnerPath.renderPhysicalPath()}::${physicalMethod.physicalMethodName} " +
                "for '$logicalMemberKey': $reason (${namedCandidates.size} same-name candidate(s), " +
                "${matches.size} full match(es))"
    }

    val match = matches.single()
    return DotNetGenericOwnerNaturalMethodDefMetadataBinding(
        logicalMemberKey = logicalMemberKey,
        declaringType = declaringType,
        methodDefinition = match.method,
        splitNullableOutParameter = match.splitNullableOutParameter,
    )
}

private fun DotNetClrAssemblyMetadata.requireNaturalDeclarationMatchesProducerSeal(
    logicalMemberKey: String,
    naturalType: DotNetGenericOwnerSealedEmissionTypeDefRow,
    naturalMethod: DotNetGenericOwnerSealedEmissionMethodDefRow,
    binding: DotNetGenericOwnerNaturalMethodDefMetadataBinding,
) {
    val actualType = binding.declaringType
    val expectedType = naturalType.structural
    require(actualType == requireTypeDefinition(naturalType.physicalPath) &&
            actualType.visibility == naturalType.flags.visibility.toClrVisibility() &&
            actualType.isInterface == naturalType.flags.isInterface &&
            actualType.isAbstract == naturalType.flags.isAbstract &&
            actualType.isSealed == naturalType.flags.isSealed &&
            actualType.attributes and TYPE_LAYOUT_MASK == naturalType.flags.layout.toClrMask() &&
            actualType.attributes and TYPE_STRING_FORMAT_MASK ==
            naturalType.flags.stringFormat.toClrMask() &&
            (actualType.attributes and BEFORE_FIELD_INIT_ATTRIBUTE != 0L) ==
            naturalType.flags.isBeforeFieldInit
    ) {
        "producer DLL '${identity.name}' has TypeDef flags which disagree with the sealed natural owner " +
                "for '$logicalMemberKey'"
    }
    require(expectedType.directEdges.isEmpty() && actualType.baseType == null &&
            interfaceImplementations.none { implementation ->
                implementation.implementingType == actualType.handle
            }
    ) {
        "producer DLL '${identity.name}' has TypeDef ancestry which disagrees with the sealed natural owner " +
                "for '$logicalMemberKey'"
    }
    val ownerParameters = requireContiguousGenericParameters(actualType.handle, "TypeDef")
    require(ownerParameters.size == expectedType.genericArity &&
            expectedType.genericParameters.size == ownerParameters.size
    ) {
        "producer DLL '${identity.name}' has a TypeDef GenericParam arity which disagrees with " +
                "the sealed natural owner for '$logicalMemberKey'"
    }
    expectedType.genericParameters.zip(ownerParameters).forEach { [expected, actual] ->
        require(expected.constraints.isEmpty() &&
                actual.variance == expected.variance.toClrVariance() &&
                !actual.hasReferenceTypeConstraint &&
                !actual.hasNotNullableValueTypeConstraint &&
                !actual.hasDefaultConstructorConstraint &&
                !actual.allowsByRefLike &&
                genericParameterConstraints.none { constraint -> constraint.owner == actual.handle }
        ) {
            "producer DLL '${identity.name}' has TypeDef GenericParam variance or constraints which " +
                    "disagree with the sealed natural owner for '$logicalMemberKey'"
        }
    }

    val actualMethod = binding.methodDefinition
    val expectedMethod = naturalMethod.structural
    require(actualMethod.visibility == naturalMethod.visibility.toClrVisibility() &&
            actualMethod.isStatic == !naturalMethod.dispatch.isInstance &&
            actualMethod.isVirtual == naturalMethod.dispatch.isVirtual &&
            (actualMethod.attributes and NEW_SLOT_ATTRIBUTE != 0) == naturalMethod.dispatch.isNewSlot &&
            actualMethod.isAbstract == naturalMethod.dispatch.isAbstract &&
            actualMethod.isFinal == naturalMethod.dispatch.isFinal &&
            (actualMethod.attributes and HIDE_BY_SIG_ATTRIBUTE != 0) == naturalMethod.isHideBySig &&
            actualMethod.isSpecialName == naturalMethod.isSpecialName &&
            actualMethod.isRuntimeSpecialName == naturalMethod.isRuntimeSpecialName
    ) {
        "producer DLL '${identity.name}' has MethodDef flags which disagree with the sealed natural slot " +
                "for '$logicalMemberKey'"
    }
    val methodParameters = requireContiguousGenericParameters(actualMethod.handle, "MethodDef")
    require(methodParameters.size == expectedMethod.header.genericArity &&
            expectedMethod.genericParameters.size == methodParameters.size &&
            naturalMethod.physicalGenericParameterNames == methodParameters.map { parameter -> parameter.name }
    ) {
        "producer DLL '${identity.name}' has MethodDef GenericParam rows which disagree with " +
                "the sealed natural slot for '$logicalMemberKey'"
    }
    expectedMethod.genericParameters.zip(methodParameters).forEach { [expected, actual] ->
        require(expected.constraints.isEmpty() &&
                actual.variance == DotNetClrGenericParameterVariance.INVARIANT &&
                !actual.hasReferenceTypeConstraint &&
                !actual.hasNotNullableValueTypeConstraint &&
                !actual.hasDefaultConstructorConstraint &&
                !actual.allowsByRefLike &&
                genericParameterConstraints.none { constraint -> constraint.owner == actual.handle }
        ) {
            "producer DLL '${identity.name}' has MethodDef GenericParam constraints which disagree with " +
                    "the sealed natural slot for '$logicalMemberKey'"
        }
    }
}

private fun DotNetIlRawTypeDefVisibility.toClrVisibility(): DotNetClrTypeVisibility = when (this) {
    DotNetIlRawTypeDefVisibility.PUBLIC -> DotNetClrTypeVisibility.PUBLIC
    DotNetIlRawTypeDefVisibility.NOT_PUBLIC -> DotNetClrTypeVisibility.NOT_PUBLIC
    DotNetIlRawTypeDefVisibility.NESTED_PUBLIC -> DotNetClrTypeVisibility.NESTED_PUBLIC
    DotNetIlRawTypeDefVisibility.NESTED_PRIVATE -> DotNetClrTypeVisibility.NESTED_PRIVATE
    DotNetIlRawTypeDefVisibility.NESTED_ASSEMBLY -> DotNetClrTypeVisibility.NESTED_ASSEMBLY
    DotNetIlRawTypeDefVisibility.NESTED_FAMILY -> DotNetClrTypeVisibility.NESTED_FAMILY
}

private fun DotNetIlRawTypeDefLayout.toClrMask(): Long = when (this) {
    DotNetIlRawTypeDefLayout.AUTO -> 0L
}

private fun DotNetIlRawTypeDefStringFormat.toClrMask(): Long = when (this) {
    DotNetIlRawTypeDefStringFormat.ANSI -> 0L
}

private fun DotNetIlRawMethodDefVisibility.toClrVisibility(): DotNetClrMethodVisibility = when (this) {
    DotNetIlRawMethodDefVisibility.PUBLIC -> DotNetClrMethodVisibility.PUBLIC
    DotNetIlRawMethodDefVisibility.FAMILY -> DotNetClrMethodVisibility.FAMILY
    DotNetIlRawMethodDefVisibility.ASSEMBLY -> DotNetClrMethodVisibility.ASSEMBLY
    DotNetIlRawMethodDefVisibility.FAMILY_OR_ASSEMBLY -> DotNetClrMethodVisibility.FAMILY_OR_ASSEMBLY
    DotNetIlRawMethodDefVisibility.FAMILY_AND_ASSEMBLY -> DotNetClrMethodVisibility.FAMILY_AND_ASSEMBLY
    DotNetIlRawMethodDefVisibility.PRIVATE -> DotNetClrMethodVisibility.PRIVATE
}

private fun DotNetGenericOwnerPhysicalTypeParameterVariance.toClrVariance():
        DotNetClrGenericParameterVariance = when (this) {
    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ->
        DotNetClrGenericParameterVariance.INVARIANT
    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT ->
        DotNetClrGenericParameterVariance.COVARIANT
    DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT ->
        DotNetClrGenericParameterVariance.CONTRAVARIANT
}

private data class MethodMatch(
    val method: DotNetClrMethodDefinition,
    val splitNullableOutParameter: DotNetClrParameterDefinition?,
)

private fun DotNetClrAssemblyMetadata.matchPhysicalMethod(
    method: DotNetClrMethodDefinition,
    expected: DotNetGenericOwnerPhysicalMethodSignatureRecord,
    ownerGenericArity: Int,
    coreLibraryAssemblyName: String,
): MethodMatch? {
    val actual = method.signature
    if (actual.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT ||
        actual.hasThis != expected.isInstance ||
        actual.hasExplicitThis ||
        method.isStatic == expected.isInstance ||
        actual.genericParameterCount != expected.genericArity ||
        actual.varargParameterStart != null
    ) {
        return null
    }
    val methodGenericArity = requireContiguousGenericParameters(method.handle, "MethodDef").size
    if (methodGenericArity != expected.genericArity) return null

    val expectedReturnType = when (val layout = expected.resultLayout) {
        DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType()

        is DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct -> layout.slot.type
        is DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable -> layout.payloadSlot.type
    }
    if (!matchesPhysicalType(
            expected = expectedReturnType,
            actual = actual.returnType,
            ownerGenericArity = ownerGenericArity,
            methodGenericArity = methodGenericArity,
            coreLibraryAssemblyName = coreLibraryAssemblyName,
        )
    ) {
        return null
    }

    val ordinaryParameterTypes = expected.parameterSlots.map { slot -> slot.type }
    val splitNullable = expected.resultLayout is DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable
    val expectedPhysicalParameterCount = ordinaryParameterTypes.size + if (splitNullable) 1 else 0
    if (actual.parameterTypes.size != expectedPhysicalParameterCount) return null
    if (!ordinaryParameterTypes.indices.all { index ->
            matchesPhysicalType(
                expected = ordinaryParameterTypes[index],
                actual = actual.parameterTypes[index],
                ownerGenericArity = ownerGenericArity,
                methodGenericArity = methodGenericArity,
                coreLibraryAssemblyName = coreLibraryAssemblyName,
            )
        }
    ) {
        return null
    }

    val parameterRows = parameterDefinitions.filter { parameter -> parameter.declaringMethod == method.handle }
    if (parameterRows.map { parameter -> parameter.sequence }.toSet().size != parameterRows.size ||
        parameterRows.any { parameter -> parameter.sequence !in 0..actual.parameterTypes.size }
    ) {
        return null
    }
    if (!splitNullable) return MethodMatch(method, splitNullableOutParameter = null)

    val hiddenParameterIndex = actual.parameterTypes.lastIndex
    if (actual.parameterTypes[hiddenParameterIndex] != DotNetClrTypeSignature.ByReference(
            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN),
        )
    ) {
        return null
    }
    val hiddenParameter = parameterRows.singleOrNull { parameter ->
        parameter.sequence == hiddenParameterIndex + 1
    } ?: return null
    if (hiddenParameter.attributes != OUT_PARAMETER_ATTRIBUTE) return null
    return MethodMatch(method, hiddenParameter)
}

private fun DotNetGenericOwnerPhysicalMethodSignatureRecord.requireSupportedForExternalMetadataValidation(
    ownerGenericArity: Int,
) {
    fun requireSupported(type: DotNetGenericOwnerPhysicalTypeExpressionRecord) {
        when (type.kind) {
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER -> require(
                checkNotNull(type.parameterIndex) < ownerGenericArity
            ) {
                "a recorded natural MethodDef references missing owner GenericParam !${type.parameterIndex}"
            }

            DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER -> require(
                checkNotNull(type.parameterIndex) < genericArity
            ) {
                "a recorded natural MethodDef references missing method GenericParam !!${type.parameterIndex}"
            }

            DotNetGenericOwnerPhysicalTypeKind.NAMED -> when (type.scope) {
                DotNetGenericOwnerPhysicalTypeScope.PRODUCER -> Unit
                DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY,
                DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
                -> require(type.typePath.last().none { character -> character == '`' }) {
                    "an external named carrier records generic arity separately from its metadata name"
                }

                DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION ->
                    throw IllegalArgumentException(
                        "a separately compiled natural MethodDef cannot retain a CURRENT_COMPILATION carrier"
                    )

                null -> throw IllegalArgumentException("a named natural MethodDef carrier has no physical scope")
            }

            DotNetGenericOwnerPhysicalTypeKind.VOID,
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
            DotNetGenericOwnerPhysicalTypeKind.INT32,
            DotNetGenericOwnerPhysicalTypeKind.STRING,
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
            -> Unit
        }
        type.arguments.forEach(::requireSupported)
    }

    resultLayout.valueSlotOrNull?.type?.let(::requireSupported)
    parameterSlots.forEach { slot -> requireSupported(slot.type) }
}

private fun DotNetClrAssemblyMetadata.matchesPhysicalType(
    expected: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    actual: DotNetClrTypeSignature,
    ownerGenericArity: Int,
    methodGenericArity: Int,
    coreLibraryAssemblyName: String,
): Boolean = when (expected.kind) {
    DotNetGenericOwnerPhysicalTypeKind.VOID -> actual == DotNetClrTypeSignature.Void
    DotNetGenericOwnerPhysicalTypeKind.BOOLEAN ->
        actual == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
    DotNetGenericOwnerPhysicalTypeKind.INT32 ->
        actual == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
    DotNetGenericOwnerPhysicalTypeKind.STRING ->
        actual == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
    DotNetGenericOwnerPhysicalTypeKind.OBJECT ->
        actual == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)
    DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ->
        checkNotNull(expected.parameterIndex) < ownerGenericArity &&
                actual == DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    expected.parameterIndex,
                )
    DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER ->
        checkNotNull(expected.parameterIndex) < methodGenericArity &&
                actual == DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    expected.parameterIndex,
                )
    DotNetGenericOwnerPhysicalTypeKind.NAMED -> matchesNamedPhysicalType(
        expected = expected,
        actual = actual,
        ownerGenericArity = ownerGenericArity,
        methodGenericArity = methodGenericArity,
        coreLibraryAssemblyName = coreLibraryAssemblyName,
    )
    DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY -> {
        val array = actual as? DotNetClrTypeSignature.SzArray ?: return false
        matchesPhysicalType(
            expected = expected.arguments.single(),
            actual = array.elementType,
            ownerGenericArity = ownerGenericArity,
            methodGenericArity = methodGenericArity,
            coreLibraryAssemblyName = coreLibraryAssemblyName,
        )
    }
}

private fun DotNetClrAssemblyMetadata.matchesNamedPhysicalType(
    expected: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    actual: DotNetClrTypeSignature,
    ownerGenericArity: Int,
    methodGenericArity: Int,
    coreLibraryAssemblyName: String,
): Boolean {
    val expectedValueType = expected.namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE
    val actualNamed: DotNetClrTypeSignature.Named
    val actualArguments: List<DotNetClrTypeSignature>
    if (expected.arguments.isEmpty()) {
        actualNamed = actual as? DotNetClrTypeSignature.Named ?: return false
        actualArguments = emptyList()
    } else {
        val instance = actual as? DotNetClrTypeSignature.GenericInstance ?: return false
        actualNamed = instance.genericType
        actualArguments = instance.arguments
    }
    if (actualNamed.isValueType != expectedValueType || actualArguments.size != expected.arguments.size) return false

    val identityMatches = when (expected.scope) {
        DotNetGenericOwnerPhysicalTypeScope.PRODUCER -> {
            if (actualNamed.type.table != TYPE_DEF_TABLE) return false
            val definition = typeDefinitions.singleOrNull { candidate -> candidate.handle == actualNamed.type }
                ?: return false
            if (definition != runCatching { requireTypeDefinition(expected.typePath) }.getOrNull()) return false
            if (requireContiguousGenericParameters(definition.handle, "TypeDef").size !=
                expected.genericArity
            ) {
                return false
            }
            when (expected.namedTypeCategory) {
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE -> definition.isInterface
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                -> !definition.isInterface
                null -> false
            }
        }

        DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY -> matchesExternalTopLevelTypeReference(
            handle = actualNamed.type,
            expectedTypePath = expected.typePath,
            expectedGenericArity = expected.genericArity,
            expectedAssemblyName = coreLibraryAssemblyName,
        )

        DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY -> matchesExternalTopLevelTypeReference(
            handle = actualNamed.type,
            expectedTypePath = expected.typePath,
            expectedGenericArity = expected.genericArity,
            expectedAssemblyName = checkNotNull(expected.assemblyName),
        )

        DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION,
        null,
        -> false
    }
    return identityMatches && expected.arguments.indices.all { index ->
        matchesPhysicalType(
            expected = expected.arguments[index],
            actual = actualArguments[index],
            ownerGenericArity = ownerGenericArity,
            methodGenericArity = methodGenericArity,
            coreLibraryAssemblyName = coreLibraryAssemblyName,
        )
    }
}

private fun DotNetClrAssemblyMetadata.matchesExternalTopLevelTypeReference(
    handle: DotNetClrMetadataHandle,
    expectedTypePath: List<String>,
    expectedGenericArity: Int,
    expectedAssemblyName: String,
): Boolean {
    if (handle.table != TYPE_REF_TABLE) return false
    val reference = typeReferences.singleOrNull { candidate -> candidate.handle == handle } ?: return false
    val scope = reference.resolutionScope ?: return false
    if (scope.table != ASSEMBLY_REF_TABLE) return false
    val assemblyReference = assemblyReferences.singleOrNull { candidate -> candidate.handle == scope } ?: return false
    if (!assemblyReference.name.equals(expectedAssemblyName, ignoreCase = true)) return false

    val expectedNamespace = expectedTypePath.dropLast(1).joinToString(".")
    val expectedMetadataName = expectedTypePath.last() +
            if (expectedGenericArity == 0) "" else "`$expectedGenericArity"
    return reference.namespaceName == expectedNamespace && reference.metadataName == expectedMetadataName
}

private fun DotNetClrAssemblyMetadata.requireTypeDefinition(
    physicalPath: List<String>,
): DotNetClrTypeDefinition {
    require(physicalPath.isNotEmpty() && physicalPath.all(String::isNotEmpty)) {
        "a physical TypeDef path must be non-empty"
    }
    val topLevelName = physicalPath.first()
    val namespaceSeparator = topLevelName.lastIndexOf('.')
    val namespaceName = if (namespaceSeparator < 0) "" else topLevelName.substring(0, namespaceSeparator)
    val metadataName = topLevelName.substring(namespaceSeparator + 1)
    var current = typeDefinitions.filter { definition ->
        definition.declaringType == null &&
                definition.namespaceName == namespaceName &&
                definition.metadataName == metadataName
    }.singleOrNull() ?: throw IllegalArgumentException(
        "producer DLL '${identity.name}' does not contain exactly one TypeDef '${physicalPath.first()}'"
    )
    for (nestedName in physicalPath.drop(1)) {
        current = typeDefinitions.filter { definition ->
            definition.declaringType == current.handle && definition.metadataName == nestedName
        }.singleOrNull() ?: throw IllegalArgumentException(
            "producer DLL '${identity.name}' does not contain exactly one TypeDef '${physicalPath.renderPhysicalPath()}'"
        )
    }
    return current
}

private fun DotNetClrAssemblyMetadata.requireContiguousGenericParameters(
    owner: DotNetClrMetadataHandle,
    ownerKind: String,
): List<DotNetClrGenericParameterDefinition> {
    val parameters = genericParameterDefinitions
        .filter { parameter -> parameter.owner == owner }
        .sortedBy { parameter -> parameter.number }
    require(parameters.map { parameter -> parameter.number }.sorted() == parameters.indices.toList()) {
        "producer DLL '${identity.name}' has incomplete or duplicate $ownerKind GenericParam rows for token " +
                "0x${owner.token.toUInt().toString(16).padStart(8, '0')}"
    }
    return parameters
}

private fun List<String>.renderPhysicalPath(): String = joinToString("/")

private const val TYPE_REF_TABLE = 1
private const val TYPE_DEF_TABLE = 2
private const val ASSEMBLY_REF_TABLE = 35
private const val OUT_PARAMETER_ATTRIBUTE = 0x0002
private const val NEW_SLOT_ATTRIBUTE = 0x0100
private const val HIDE_BY_SIG_ATTRIBUTE = 0x0080
private const val TYPE_LAYOUT_MASK = 0x0000_0018L
private const val TYPE_STRING_FORMAT_MASK = 0x0003_0000L
private const val BEFORE_FIELD_INIT_ATTRIBUTE = 0x0010_0000L
