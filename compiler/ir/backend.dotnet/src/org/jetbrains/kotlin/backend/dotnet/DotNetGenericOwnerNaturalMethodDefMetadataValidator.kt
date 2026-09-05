/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReferenceSignature
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

/** Exact PE rows authenticated for one producer-recorded implementation MethodDef `M`. */
data class DotNetGenericOwnerImplementationMethodDefMetadataBinding(
    val implementationMemberKey: String,
    val declaringType: DotNetClrTypeDefinition,
    val methodDefinition: DotNetClrMethodDefinition,
    val splitNullableOutParameter: DotNetClrParameterDefinition?,
)

/** Exact PE row authenticated for one producer-recorded generic-owner constructor `L`. */
data class DotNetGenericOwnerConstructorMethodDefMetadataBinding(
    val logicalConstructorKey: String,
    val declaringType: DotNetClrTypeDefinition,
    val methodDefinition: DotNetClrMethodDefinition,
)

/**
 * Authenticates a complete producer-recorded constructor header against objective CLR metadata.
 *
 * `F` is only a weak logical-to-name endpoint. The full `L` signature selects one overload, and
 * these flag checks prove that the selected row is in fact a normal instance constructor rather
 * than another same-named MethodDef with a constructor-looking signature.
 */
fun validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef,
    ownerDeclaration: DotNetPhysicalDeclaration.Class,
    assembly: DotNetClrAssemblyMetadata,
    producerTarget: DotNetTarget,
): DotNetGenericOwnerConstructorMethodDefMetadataBinding {
    require(ownerDeclaration.ownerPath == declaration.ownerPath &&
            ownerDeclaration.physicalClassVarianceKind == DotNetPhysicalClassVarianceKind.ORDINARY &&
            ownerDeclaration.physicalTypeParameterVariances.all { variance ->
                variance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
            }
    ) {
        "constructor '${declaration.logicalConstructorKey}' is joined to the wrong physical C owner"
    }
    val token = validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
        logicalMemberKey = declaration.logicalConstructorKey,
        physicalMethod = declaration.physicalMethod,
        assembly = assembly,
        producerTarget = producerTarget,
    )
    val method = token.methodDefinition
    val ownerParameters = assembly.requireContiguousGenericParameters(
        token.declaringType.handle,
        "TypeDef",
    )
    val directlyDerivesFromCoreValueTypeOrEnum = token.declaringType.baseType?.let { baseType ->
        assembly.matchesCoreLibraryTopLevelTypeReference(
            handle = baseType,
            expectedTypePath = listOf("System", "ValueType"),
            expectedGenericArity = 0,
            producerTarget = producerTarget,
        ) || assembly.matchesCoreLibraryTopLevelTypeReference(
            handle = baseType,
            expectedTypePath = listOf("System", "Enum"),
            expectedGenericArity = 0,
            producerTarget = producerTarget,
        )
    } == true
    require(!token.declaringType.isInterface &&
            !directlyDerivesFromCoreValueTypeOrEnum &&
            ownerParameters.size == ownerDeclaration.physicalTypeParameterCount &&
            ownerParameters.map { parameter -> parameter.variance } ==
            ownerDeclaration.physicalTypeParameterVariances.map { variance -> variance.toClrVariance() } &&
            ownerParameters.all { parameter ->
                parameter.variance == DotNetClrGenericParameterVariance.INVARIANT &&
                        !parameter.hasReferenceTypeConstraint &&
                        !parameter.hasNotNullableValueTypeConstraint &&
                        !parameter.hasDefaultConstructorConstraint && !parameter.allowsByRefLike &&
                        assembly.genericParameterConstraints.none { constraint ->
                            constraint.owner == parameter.handle
                        }
            }
    ) {
        "producer DLL '${assembly.identity.name}' has reference-class TypeDef category, arity, variance, or " +
                "constraints which disagree with constructor '${declaration.logicalConstructorKey}'"
    }
    val ownerChain = declaration.ownerPath.indices.map { depth ->
        assembly.requireTypeDefinition(declaration.ownerPath.take(depth + 1))
    }
    require(ownerChain.last() == token.declaringType && ownerChain.withIndex().all { entry ->
        entry.value.visibility == if (entry.index == 0) {
            DotNetClrTypeVisibility.PUBLIC
        } else {
            DotNetClrTypeVisibility.NESTED_PUBLIC
        }
    }) {
        "producer DLL '${assembly.identity.name}' has a non-public TypeDef in the owner chain of " +
                "constructor '${declaration.logicalConstructorKey}'"
    }
    require(token.splitNullableOutParameter == null &&
            method.name == ".ctor" &&
            method.visibility == declaration.visibility.toClrVisibility() &&
            !method.isStatic && !method.isVirtual && !method.isAbstract && !method.isFinal &&
            (method.attributes and NEW_SLOT_ATTRIBUTE) == 0 &&
            (method.attributes and HIDE_BY_SIG_ATTRIBUTE) != 0 &&
            method.isSpecialName && method.isRuntimeSpecialName &&
            assembly.requireContiguousGenericParameters(method.handle, "MethodDef").isEmpty()
    ) {
        "producer DLL '${assembly.identity.name}' has MethodDef flags which disagree with " +
                "constructor '${declaration.logicalConstructorKey}'"
    }
    return DotNetGenericOwnerConstructorMethodDefMetadataBinding(
        logicalConstructorKey = declaration.logicalConstructorKey,
        declaringType = token.declaringType,
        methodDefinition = method,
    )
}

/**
 * Validates one ABI-63 natural MethodDef record against the objective metadata of its producer DLL.
 *
 * The declaration-index loader must call this before making the record available to a separately
 * compiled consumer. A match is structural and token-exact: logical IR types, source names, and
 * overload heuristics are never used to reconstruct the MethodDef. [producerTarget] selects the
 * bounded core-library AssemblyRef spellings admitted for the recorded producer profile.
 */
fun validateDotNetGenericOwnerNaturalMethodDefAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef,
    assembly: DotNetClrAssemblyMetadata,
    producerTarget: DotNetTarget,
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
        producerTarget = producerTarget,
    )
    assembly.requireNaturalDeclarationMatchesProducerSeal(
        logicalMemberKey = declaration.logicalMemberKey,
        naturalType = naturalType,
        naturalMethod = naturalMethod,
        binding = binding,
    )
    return binding
}

/**
 * Authenticates one implementation endpoint and its exact constructed natural InterfaceImpl.
 * The InterfaceImpl proves only implicit slot eligibility; `M` never claims a MethodImpl row.
 */
fun validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef,
    naturalDeclaration: DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef,
    assembly: DotNetClrAssemblyMetadata,
    producerTarget: DotNetTarget,
): DotNetGenericOwnerImplementationMethodDefMetadataBinding {
    require(declaration.logicalInterfaceMemberKey == naturalDeclaration.logicalMemberKey) {
        "an implementation MethodDef descriptor is joined to the wrong natural MethodDef"
    }
    val token = validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
        logicalMemberKey = declaration.implementationMemberKey,
        physicalMethod = declaration.physicalMethod,
        assembly = assembly,
        producerTarget = producerTarget,
    )
    val type = token.declaringType
    require(type.visibility == DotNetClrTypeVisibility.PUBLIC && !type.isInterface &&
            !type.isAbstract && !type.isSealed
    ) {
        "producer DLL '${assembly.identity.name}' has TypeDef flags which disagree with " +
                "implementation MethodDef '${declaration.implementationMemberKey}'"
    }
    val ownerParameters = assembly.requireContiguousGenericParameters(type.handle, "TypeDef")
    require(ownerParameters.size == declaration.ownerTypeParameterVariances.size &&
            declaration.ownerTypeParameterVariances.all { variance ->
                variance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
            }
    ) {
        "producer DLL '${assembly.identity.name}' has a TypeDef arity or variance which disagrees with " +
                "implementation MethodDef '${declaration.implementationMemberKey}'"
    }
    ownerParameters.forEach { parameter ->
        require(parameter.variance == DotNetClrGenericParameterVariance.INVARIANT &&
                !parameter.hasReferenceTypeConstraint &&
                !parameter.hasNotNullableValueTypeConstraint &&
                !parameter.hasDefaultConstructorConstraint && !parameter.allowsByRefLike &&
                assembly.genericParameterConstraints.none { constraint ->
                    constraint.owner == parameter.handle
                }
        ) {
            "producer DLL '${assembly.identity.name}' has unsupported implementation-owner GenericParam constraints"
        }
    }

    val method = token.methodDefinition
    require(method.visibility == DotNetClrMethodVisibility.PUBLIC && !method.isStatic &&
            method.isVirtual &&
            (method.attributes and NEW_SLOT_ATTRIBUTE != 0) == declaration.methodIntroducesSlot &&
            !method.isAbstract && !method.isFinal &&
            (method.attributes and HIDE_BY_SIG_ATTRIBUTE != 0) == declaration.methodIsHideBySig &&
            method.isSpecialName == declaration.methodIsSpecialName &&
            method.isRuntimeSpecialName == declaration.methodIsRuntimeSpecialName &&
            assembly.requireContiguousGenericParameters(method.handle, "MethodDef").isEmpty()
    ) {
        "producer DLL '${assembly.identity.name}' has MethodDef flags which disagree with " +
                "implementation MethodDef '${declaration.implementationMemberKey}'"
    }

    val expectedInterface = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
        typePath = naturalDeclaration.ownerPath,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        arguments = declaration.naturalInterfaceTypeArguments,
    )
    val expectedImplementationType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
        typePath = declaration.ownerPath,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        arguments = ownerParameters.indices.map { index ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(index)
        },
    )
    val naturalType = assembly.requireTypeDefinition(naturalDeclaration.ownerPath)
    val naturalInterfaces = assembly.interfaceImplementations
        .filter { implementation -> implementation.implementingType == type.handle }
        .mapNotNull { implementation ->
            val encodedSignature = assembly.typeSpecifications.singleOrNull { specification ->
                specification.handle == implementation.interfaceType
            }?.signature ?: DotNetClrTypeSignature.Named(
                type = implementation.interfaceType,
                isValueType = false,
            )
            val signature = assembly.canonicalizeExactLocalTypeReferences(encodedSignature)
            val named = when (signature) {
                is DotNetClrTypeSignature.GenericInstance -> signature.genericType
                is DotNetClrTypeSignature.Named -> signature
                else -> return@mapNotNull null
            }
            if (named.type != naturalType.handle) null else implementation to signature
        }
    require(naturalInterfaces.size == 1) {
        "producer DLL '${assembly.identity.name}' does not contain exactly one construction of " +
                "${naturalDeclaration.ownerPath.renderPhysicalPath()} on " +
                declaration.ownerPath.renderPhysicalPath()
    }
    require(assembly.matchesPhysicalType(
        expected = expectedInterface,
        actual = naturalInterfaces.single().second,
        ownerGenericArity = ownerParameters.size,
        methodGenericArity = 0,
        producerTarget = producerTarget,
    )) {
        "producer DLL '${assembly.identity.name}' has the wrong InterfaceImpl arguments for " +
                "${naturalDeclaration.ownerPath.renderPhysicalPath()} on " +
                declaration.ownerPath.renderPhysicalPath()
    }
    validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
        logicalMemberKey = naturalDeclaration.logicalMemberKey,
        physicalMethod = naturalDeclaration.physicalMethod,
        assembly = assembly,
        producerTarget = producerTarget,
    )
    fun memberReferenceParentNamesConstruction(
        parent: DotNetClrMetadataHandle,
        selectedType: DotNetClrMetadataHandle,
        expectedConstruction: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    ): Boolean {
        if (assembly.resolveExactLocalTypeDefinition(parent) == selectedType) return true
        val parentSignature = assembly.typeSpecifications.singleOrNull { specification ->
            specification.handle == parent
        }?.signature ?: return false
        return assembly.matchesPhysicalType(
            expected = expectedConstruction,
            actual = assembly.canonicalizeExactLocalTypeReferences(parentSignature),
            ownerGenericArity = ownerParameters.size,
            methodGenericArity = 0,
            producerTarget = producerTarget,
        )
    }
    fun declarationNamesNaturalConstruction(handle: DotNetClrMetadataHandle): Boolean = when (handle.table) {
        METHOD_DEF_TABLE -> assembly.methodDefinitions.singleOrNull { candidate ->
            candidate.handle == handle
        }?.declaringType == naturalType.handle
        MEMBER_REF_TABLE -> {
            val reference = assembly.memberReferences.singleOrNull { candidate ->
                candidate.handle == handle
            } ?: return false
            memberReferenceParentNamesConstruction(
                parent = reference.parent,
                selectedType = naturalType.handle,
                expectedConstruction = expectedInterface,
            )
        }
        else -> false
    }
    fun bodyNamesImplementationMethod(handle: DotNetClrMetadataHandle): Boolean = when (handle.table) {
        METHOD_DEF_TABLE -> handle == method.handle
        MEMBER_REF_TABLE -> {
            val reference = assembly.memberReferences.singleOrNull { candidate ->
                candidate.handle == handle
            } ?: return false
            val signature = (reference.signature as? DotNetClrMemberReferenceSignature.Method)
                ?.signature ?: return false
            if (reference.name != method.name ||
                !assembly.methodSignaturesMatchModuloExactLocalTypeReferences(signature, method.signature)
            ) {
                return false
            }
            memberReferenceParentNamesConstruction(
                parent = reference.parent,
                selectedType = type.handle,
                expectedConstruction = expectedImplementationType,
            )
        }
        else -> false
    }
    require(assembly.methodImplementations.none { implementation ->
        implementation.implementingType == type.handle &&
                (bodyNamesImplementationMethod(implementation.bodyMethod) ||
                        declarationNamesNaturalConstruction(implementation.declarationMethod))
    }) {
        "producer DLL '${assembly.identity.name}' explicitly redirects implementation MethodDef " +
                "'${declaration.implementationMemberKey}' or its natural interface slot"
    }
    return DotNetGenericOwnerImplementationMethodDefMetadataBinding(
        implementationMemberKey = declaration.implementationMemberKey,
        declaringType = type,
        methodDefinition = method,
        splitNullableOutParameter = token.splitNullableOutParameter,
    )
}

/**
 * Compares complete ECMA-335 method signatures after resolving only exact same-module TypeRef
 * aliases. No logical type, assembly binding, or name-only fallback participates in this proof.
 */
internal fun DotNetClrAssemblyMetadata.methodSignaturesMatchModuloExactLocalTypeReferences(
    first: org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature,
    second: org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature,
): Boolean = canonicalizeExactLocalTypeReferences(first) == canonicalizeExactLocalTypeReferences(second)

internal fun DotNetClrAssemblyMetadata.canonicalizeExactLocalTypeReferences(
    signature: org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature,
): org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature = signature.copy(
    returnType = canonicalizeExactLocalTypeReferences(signature.returnType),
    parameterTypes = signature.parameterTypes.map(::canonicalizeExactLocalTypeReferences),
)

internal fun DotNetClrAssemblyMetadata.canonicalizeExactLocalTypeReferences(
    signature: DotNetClrTypeSignature,
): DotNetClrTypeSignature = when (signature) {
    DotNetClrTypeSignature.Void,
    DotNetClrTypeSignature.TypedReference,
    is DotNetClrTypeSignature.Primitive,
    is DotNetClrTypeSignature.GenericParameter,
    -> signature

    is DotNetClrTypeSignature.Named -> signature.copy(
        type = resolveExactLocalTypeDefinition(signature.type) ?: signature.type,
    )

    is DotNetClrTypeSignature.Pointer -> signature.copy(
        elementType = canonicalizeExactLocalTypeReferences(signature.elementType),
    )

    is DotNetClrTypeSignature.ByReference -> signature.copy(
        elementType = canonicalizeExactLocalTypeReferences(signature.elementType),
    )

    is DotNetClrTypeSignature.SzArray -> signature.copy(
        elementType = canonicalizeExactLocalTypeReferences(signature.elementType),
    )

    is DotNetClrTypeSignature.Array -> signature.copy(
        elementType = canonicalizeExactLocalTypeReferences(signature.elementType),
    )

    is DotNetClrTypeSignature.GenericInstance -> signature.copy(
        genericType = canonicalizeExactLocalTypeReferences(signature.genericType) as DotNetClrTypeSignature.Named,
        arguments = signature.arguments.map(::canonicalizeExactLocalTypeReferences),
    )

    is DotNetClrTypeSignature.FunctionPointer -> signature.copy(
        signature = canonicalizeExactLocalTypeReferences(signature.signature),
    )

    is DotNetClrTypeSignature.Modified -> signature.copy(
        modifiers = signature.modifiers.map { modifier ->
            modifier.copy(
                modifierType = resolveExactLocalTypeDefinition(modifier.modifierType)
                    ?: modifier.modifierType,
            )
        },
        unmodifiedType = canonicalizeExactLocalTypeReferences(signature.unmodifiedType),
    )
}

/**
 * Resolves only a TypeRef whose complete ResolutionScope chain proves it aliases a TypeDef in this
 * module. AssemblyRef, ModuleRef, nil-scope, ambiguous, malformed, and cyclic references remain
 * unresolved, even when their namespace and metadata name happen to match a local definition.
 */
internal fun DotNetClrAssemblyMetadata.resolveExactLocalTypeDefinition(
    handle: DotNetClrMetadataHandle,
): DotNetClrMetadataHandle? = resolveExactLocalTypeDefinition(
    handle = handle,
    activeTypeReferences = mutableSetOf(),
    depth = 1,
)

private fun DotNetClrAssemblyMetadata.resolveExactLocalTypeDefinition(
    handle: DotNetClrMetadataHandle,
    activeTypeReferences: MutableSet<DotNetClrMetadataHandle>,
    depth: Int,
): DotNetClrMetadataHandle? = when (handle.table) {
    TYPE_DEF_TABLE -> typeDefinitions.singleOrNull { definition ->
        definition.handle == handle
    }?.handle

    TYPE_REF_TABLE -> {
        require(depth <= MAX_EXACT_LOCAL_TYPE_REFERENCE_DEPTH) {
            "exact local CLR TypeRef scope nesting is too deep"
        }
        require(activeTypeReferences.add(handle)) {
            "exact local CLR TypeRef scope chain is cyclic"
        }
        try {
            val reference = typeReferences.singleOrNull { candidate ->
                candidate.handle == handle
            } ?: return null
            when (val scope = reference.resolutionScope) {
                null -> null
                else -> when (scope.table) {
                    MODULE_TABLE -> if (scope.row == 1) {
                        typeDefinitions.singleOrNull { definition ->
                            definition.declaringType == null &&
                                    definition.namespaceName == reference.namespaceName &&
                                    definition.metadataName == reference.metadataName
                        }?.handle
                    } else {
                        null
                    }

                    TYPE_REF_TABLE -> {
                        val enclosing = resolveExactLocalTypeDefinition(
                            handle = scope,
                            activeTypeReferences = activeTypeReferences,
                            depth = depth + 1,
                        )
                            ?: return null
                        typeDefinitions.singleOrNull { definition ->
                            definition.declaringType == enclosing &&
                                    definition.metadataName == reference.metadataName
                        }?.handle
                    }

                    else -> null
                }
            }
        } finally {
            activeTypeReferences.remove(handle)
        }
    }

    else -> null
}

/** Signature-level token binding used by focused metadata tests before the producer-seal join. */
internal fun validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
    logicalMemberKey: String,
    physicalMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    assembly: DotNetClrAssemblyMetadata,
    producerTarget: DotNetTarget,
): DotNetGenericOwnerNaturalMethodDefMetadataBinding {
    require(logicalMemberKey.isNotEmpty()) {
        "a generic-owner natural MethodDef validation requires a logical member key"
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
            producerTarget = producerTarget,
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

private fun DotNetGenericOwnerPhysicalConstructorVisibility.toClrVisibility():
        DotNetClrMethodVisibility = when (this) {
    DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC -> DotNetClrMethodVisibility.PUBLIC
    DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY -> DotNetClrMethodVisibility.FAMILY
    DotNetGenericOwnerPhysicalConstructorVisibility.ASSEMBLY -> DotNetClrMethodVisibility.ASSEMBLY
    DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY_AND_ASSEMBLY ->
        DotNetClrMethodVisibility.FAMILY_AND_ASSEMBLY
    DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY_OR_ASSEMBLY ->
        DotNetClrMethodVisibility.FAMILY_OR_ASSEMBLY
    DotNetGenericOwnerPhysicalConstructorVisibility.PRIVATE -> DotNetClrMethodVisibility.PRIVATE
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
    producerTarget: DotNetTarget,
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
            producerTarget = producerTarget,
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
                producerTarget = producerTarget,
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
    producerTarget: DotNetTarget,
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
        producerTarget = producerTarget,
    )
    DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY -> {
        val array = actual as? DotNetClrTypeSignature.SzArray ?: return false
        matchesPhysicalType(
            expected = expected.arguments.single(),
            actual = array.elementType,
            ownerGenericArity = ownerGenericArity,
            methodGenericArity = methodGenericArity,
            producerTarget = producerTarget,
        )
    }
}

private fun DotNetClrAssemblyMetadata.matchesNamedPhysicalType(
    expected: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    actual: DotNetClrTypeSignature,
    ownerGenericArity: Int,
    methodGenericArity: Int,
    producerTarget: DotNetTarget,
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

        DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY -> matchesCoreLibraryTopLevelTypeReference(
            handle = actualNamed.type,
            expectedTypePath = expected.typePath,
            expectedGenericArity = expected.genericArity,
            producerTarget = producerTarget,
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
            producerTarget = producerTarget,
        )
    }
}

/**
 * Matches only the architecture record's neutral `CORE_LIBRARY` scope. The profile-specific
 * facade policy must never be reused for an explicit assembly-scoped or retained foreign identity.
 */
internal fun DotNetClrAssemblyMetadata.matchesCoreLibraryTopLevelTypeReference(
    handle: DotNetClrMetadataHandle,
    expectedTypePath: List<String>,
    expectedGenericArity: Int,
    producerTarget: DotNetTarget,
): Boolean = matchesTopLevelTypeReference(
    handle = handle,
    expectedTypePath = expectedTypePath,
    expectedGenericArity = expectedGenericArity,
    acceptsAssemblyName = { assemblyName ->
        producerTarget.acceptsCoreLibraryPeAssemblyName(assemblyName)
    },
)

internal fun DotNetClrAssemblyMetadata.matchesExternalTopLevelTypeReference(
    handle: DotNetClrMetadataHandle,
    expectedTypePath: List<String>,
    expectedGenericArity: Int,
    expectedAssemblyName: String,
): Boolean = matchesTopLevelTypeReference(
    handle = handle,
    expectedTypePath = expectedTypePath,
    expectedGenericArity = expectedGenericArity,
    acceptsAssemblyName = { assemblyName ->
        assemblyName.equals(expectedAssemblyName, ignoreCase = true)
    },
)

private fun DotNetClrAssemblyMetadata.matchesTopLevelTypeReference(
    handle: DotNetClrMetadataHandle,
    expectedTypePath: List<String>,
    expectedGenericArity: Int,
    acceptsAssemblyName: (String) -> Boolean,
): Boolean {
    if (handle.table != TYPE_REF_TABLE) return false
    val reference = typeReferences.singleOrNull { candidate -> candidate.handle == handle } ?: return false
    val scope = reference.resolutionScope ?: return false
    if (scope.table != ASSEMBLY_REF_TABLE) return false
    val assemblyReference = assemblyReferences.singleOrNull { candidate -> candidate.handle == scope } ?: return false
    if (!acceptsAssemblyName(assemblyReference.name)) return false

    val expectedNamespace = expectedTypePath.dropLast(1).joinToString(".")
    val expectedMetadataName = expectedTypePath.last() +
            if (expectedGenericArity == 0) "" else "`$expectedGenericArity"
    return reference.namespaceName == expectedNamespace && reference.metadataName == expectedMetadataName
}

internal fun DotNetClrAssemblyMetadata.requireTypeDefinition(
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

internal fun DotNetClrAssemblyMetadata.requireContiguousGenericParameters(
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

internal fun List<String>.renderPhysicalPath(): String = joinToString("/")

private const val MODULE_TABLE = 0
private const val TYPE_REF_TABLE = 1
private const val TYPE_DEF_TABLE = 2
private const val METHOD_DEF_TABLE = 6
private const val MAX_EXACT_LOCAL_TYPE_REFERENCE_DEPTH = 64
private const val MEMBER_REF_TABLE = 10
private const val ASSEMBLY_REF_TABLE = 35
private const val OUT_PARAMETER_ATTRIBUTE = 0x0002
private const val NEW_SLOT_ATTRIBUTE = 0x0100
private const val HIDE_BY_SIG_ATTRIBUTE = 0x0080
private const val TYPE_LAYOUT_MASK = 0x0000_0018L
private const val TYPE_STRING_FORMAT_MASK = 0x0003_0000L
private const val BEFORE_FIELD_INIT_ATTRIBUTE = 0x0010_0000L
