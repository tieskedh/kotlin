package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrTypeViewResolutionFailure {
    TYPE_RESOLUTION_FAILED,
    INVALID_TYPE_SPECIFICATION,
    TYPE_ARGUMENT_SUBSTITUTION_FAILED,
    METHOD_TYPE_PARAMETER_NOT_ALLOWED,
    NON_NOMINAL_TYPE_SPECIFICATION,
}

sealed interface DotNetClrTypeViewResolution {
    data class Resolved(
        val view: DotNetClrResolvedTypeView,
    ) : DotNetClrTypeViewResolution

    data class Invalid(
        val failure: DotNetClrTypeViewResolutionFailure,
        val handle: DotNetClrMetadataHandle,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
        val nonNominalSignature: DotNetClrResolvedTypeSignature? = null,
    ) : DotNetClrTypeViewResolution
}

/**
 * Resolves one TypeDefOrRef edge to its complete view in a declaring owner's instantiation.
 *
 * A TypeSpec edge is first resolved in the assembly which owns the row and then receives the
 * declaring owner's arguments. The result never erases a constructed type to its open TypeDef.
 */
class DotNetClrTypeViewResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
        ownerArguments: List<DotNetClrResolvedTypeSignature> = emptyList(),
    ): DotNetClrTypeViewResolution {
        if (handle.table != TYPE_SPEC_TABLE) {
            val type = when (
                val resolution = typeResolver.resolveTypeDefinition(assembly, handle)
            ) {
                is DotNetClrTypeResolution.Resolved -> resolution.type
                is DotNetClrTypeResolution.Unresolved ->
                    return invalid(
                        DotNetClrTypeViewResolutionFailure.TYPE_RESOLUTION_FAILED,
                        handle,
                        typeResolution = resolution,
                    )
            }
            val genericArity = type.genericArity()
            if (genericArity != 0) {
                return invalid(
                    DotNetClrTypeViewResolutionFailure.INVALID_TYPE_SPECIFICATION,
                    handle,
                    signatureResolution = DotNetClrResolvedSignatureResolution.Invalid(
                        DotNetClrResolvedSignatureFailure.GENERIC_ARITY_MISMATCH,
                        type,
                        genericArity,
                        0,
                    ),
                )
            }
            return DotNetClrTypeViewResolution.Resolved(
                DotNetClrResolvedTypeView(type, emptyList())
            )
        }

        val specification = assembly.typeSpecifications.singleOrNull { candidate ->
            candidate.handle == handle
        } ?: return when (
            val resolution = typeResolver.resolveTypeDefinition(assembly, handle)
        ) {
            is DotNetClrTypeResolution.Resolved ->
                invalid(
                    DotNetClrTypeViewResolutionFailure.INVALID_TYPE_SPECIFICATION,
                    handle,
                )

            is DotNetClrTypeResolution.Unresolved ->
                invalid(
                    DotNetClrTypeViewResolutionFailure.TYPE_RESOLUTION_FAILED,
                    handle,
                    typeResolution = resolution,
                )
        }
        val resolvedSignature = when (
            val resolution = signatureResolver.resolve(assembly, specification.signature)
        ) {
            is DotNetClrResolvedSignatureResolution.Resolved -> resolution.signature
            is DotNetClrResolvedSignatureResolution.UnresolvedType ->
                return invalid(
                    DotNetClrTypeViewResolutionFailure.TYPE_RESOLUTION_FAILED,
                    handle,
                    typeResolution = resolution.resolution,
                )

            is DotNetClrResolvedSignatureResolution.Invalid ->
                return invalid(
                    DotNetClrTypeViewResolutionFailure.INVALID_TYPE_SPECIFICATION,
                    handle,
                    signatureResolution = resolution,
                )
        }
        val substitutedSignature = when (
            val substitution =
                resolvedSignature.substituteClrTypeArguments(ownerArguments)
        ) {
            is DotNetClrResolvedSignatureSubstitution.Substituted ->
                substitution.signature

            is DotNetClrResolvedSignatureSubstitution.Invalid ->
                return invalid(
                    DotNetClrTypeViewResolutionFailure.TYPE_ARGUMENT_SUBSTITUTION_FAILED,
                    handle,
                    signatureSubstitution = substitution,
                )
        }
        if (substitutedSignature.containsClrMethodTypeParameter()) {
            return invalid(
                DotNetClrTypeViewResolutionFailure
                    .METHOD_TYPE_PARAMETER_NOT_ALLOWED,
                handle,
                nonNominalSignature = substitutedSignature,
            )
        }
        val view = when (substitutedSignature) {
            is DotNetClrResolvedTypeSignature.Named ->
                DotNetClrResolvedTypeView(
                    substitutedSignature.type,
                    emptyList(),
                )

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                DotNetClrResolvedTypeView(
                    substitutedSignature.genericType.type,
                    substitutedSignature.arguments,
                )

            else ->
                return invalid(
                    DotNetClrTypeViewResolutionFailure.NON_NOMINAL_TYPE_SPECIFICATION,
                    handle,
                    nonNominalSignature = substitutedSignature,
                )
        }
        val genericArity = view.type.genericArity()
        if (view.arguments.size != genericArity) {
            return invalid(
                DotNetClrTypeViewResolutionFailure.INVALID_TYPE_SPECIFICATION,
                handle,
                signatureResolution = DotNetClrResolvedSignatureResolution.Invalid(
                    DotNetClrResolvedSignatureFailure.GENERIC_ARITY_MISMATCH,
                    view.type,
                    genericArity,
                    view.arguments.size,
                ),
            )
        }
        return DotNetClrTypeViewResolution.Resolved(view)
    }

    private fun invalid(
        failure: DotNetClrTypeViewResolutionFailure,
        handle: DotNetClrMetadataHandle,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
        nonNominalSignature: DotNetClrResolvedTypeSignature? = null,
    ): DotNetClrTypeViewResolution.Invalid =
        DotNetClrTypeViewResolution.Invalid(
            failure,
            handle,
            typeResolution,
            signatureResolution,
            signatureSubstitution,
            nonNominalSignature,
        )

    private companion object {
        const val TYPE_SPEC_TABLE = 27
    }
}

data class DotNetClrResolvedInterfaceImplementation(
    val row: DotNetClrInterfaceImplementation,
    val interfaceType: DotNetClrResolvedTypeView,
)

data class DotNetClrResolvedTypeHierarchy(
    val type: DotNetClrResolvedTypeView,
    val baseType: DotNetClrResolvedTypeView?,
    val interfaces: List<DotNetClrResolvedInterfaceImplementation>,
)

enum class DotNetClrTypeHierarchyViewResolutionFailure {
    OWNER_GENERIC_ARITY_MISMATCH,
    INTERFACE_HAS_BASE_TYPE,
    BASE_TYPE_RESOLUTION_FAILED,
    BASE_TYPE_IS_INTERFACE,
    INTERFACE_TYPE_RESOLUTION_FAILED,
    INTERFACE_TARGET_IS_NOT_INTERFACE,
}

sealed interface DotNetClrTypeHierarchyViewResolution {
    data class Resolved(
        val hierarchy: DotNetClrResolvedTypeHierarchy,
    ) : DotNetClrTypeHierarchyViewResolution

    data class Invalid(
        val failure: DotNetClrTypeHierarchyViewResolutionFailure,
        val expectedGenericArity: Int? = null,
        val actualGenericArity: Int? = null,
        val interfaceImplementation: DotNetClrInterfaceImplementation? = null,
        val typeViewResolution: DotNetClrTypeViewResolution.Invalid? = null,
        val invalidTarget: DotNetClrResolvedTypeView? = null,
    ) : DotNetClrTypeHierarchyViewResolution
}

/**
 * Resolves the immediate physical superclass and interface views of one CLR type view.
 *
 * This layer checks physical hierarchy shape but does not yet walk assignability, apply generic
 * variance, or project a Kotlin supertype.
 */
class DotNetClrTypeHierarchyViewResolver(
    typeResolver: DotNetClrTypeResolver,
) {
    private val viewResolver = DotNetClrTypeViewResolver(typeResolver)

    fun resolve(
        view: DotNetClrResolvedTypeView,
    ): DotNetClrTypeHierarchyViewResolution {
        val expectedGenericArity = view.type.genericArity()
        if (view.arguments.size != expectedGenericArity) {
            return invalid(
                DotNetClrTypeHierarchyViewResolutionFailure
                    .OWNER_GENERIC_ARITY_MISMATCH,
                expectedGenericArity = expectedGenericArity,
                actualGenericArity = view.arguments.size,
            )
        }

        val baseHandle = view.type.definition.baseType
        if (view.type.definition.isInterface && baseHandle != null) {
            return invalid(
                DotNetClrTypeHierarchyViewResolutionFailure.INTERFACE_HAS_BASE_TYPE
            )
        }
        val baseType = baseHandle?.let { handle ->
            when (
                val resolution =
                    viewResolver.resolve(view.type.assembly, handle, view.arguments)
            ) {
                is DotNetClrTypeViewResolution.Resolved -> {
                    if (resolution.view.type.definition.isInterface) {
                        return invalid(
                            DotNetClrTypeHierarchyViewResolutionFailure
                                .BASE_TYPE_IS_INTERFACE,
                            invalidTarget = resolution.view,
                        )
                    }
                    resolution.view
                }

                is DotNetClrTypeViewResolution.Invalid ->
                    return invalid(
                        DotNetClrTypeHierarchyViewResolutionFailure
                            .BASE_TYPE_RESOLUTION_FAILED,
                        typeViewResolution = resolution,
                    )
            }
        }

        val resolvedInterfaces = ArrayList<DotNetClrResolvedInterfaceImplementation>()
        for (
            row in view.type.assembly.interfaceImplementations.filter { implementation ->
                implementation.implementingType == view.type.definition.handle
            }
        ) {
            val interfaceType = when (
                val resolution =
                    viewResolver.resolve(
                        view.type.assembly,
                        row.interfaceType,
                        view.arguments,
                    )
            ) {
                is DotNetClrTypeViewResolution.Resolved -> resolution.view
                is DotNetClrTypeViewResolution.Invalid ->
                    return invalid(
                        DotNetClrTypeHierarchyViewResolutionFailure
                            .INTERFACE_TYPE_RESOLUTION_FAILED,
                        interfaceImplementation = row,
                        typeViewResolution = resolution,
                    )
            }
            if (!interfaceType.type.definition.isInterface) {
                return invalid(
                    DotNetClrTypeHierarchyViewResolutionFailure
                        .INTERFACE_TARGET_IS_NOT_INTERFACE,
                    interfaceImplementation = row,
                    invalidTarget = interfaceType,
                )
            }
            resolvedInterfaces += DotNetClrResolvedInterfaceImplementation(
                row,
                interfaceType,
            )
        }
        return DotNetClrTypeHierarchyViewResolution.Resolved(
            DotNetClrResolvedTypeHierarchy(
                view,
                baseType,
                resolvedInterfaces.toList(),
            )
        )
    }

    private fun invalid(
        failure: DotNetClrTypeHierarchyViewResolutionFailure,
        expectedGenericArity: Int? = null,
        actualGenericArity: Int? = null,
        interfaceImplementation: DotNetClrInterfaceImplementation? = null,
        typeViewResolution: DotNetClrTypeViewResolution.Invalid? = null,
        invalidTarget: DotNetClrResolvedTypeView? = null,
    ): DotNetClrTypeHierarchyViewResolution.Invalid =
        DotNetClrTypeHierarchyViewResolution.Invalid(
            failure,
            expectedGenericArity,
            actualGenericArity,
            interfaceImplementation,
            typeViewResolution,
            invalidTarget,
        )
}

private fun DotNetClrResolvedTypeDefinition.genericArity(): Int =
    assembly.genericParameterDefinitions.count { parameter ->
        parameter.owner == definition.handle
    }

private fun DotNetClrResolvedTypeSignature.containsClrMethodTypeParameter(): Boolean =
    when (this) {
        is DotNetClrResolvedTypeSignature.GenericParameter ->
            kind == DotNetClrGenericParameterKind.METHOD

        is DotNetClrResolvedTypeSignature.Pointer ->
            elementType.containsClrMethodTypeParameter()

        is DotNetClrResolvedTypeSignature.ByReference ->
            elementType.containsClrMethodTypeParameter()

        is DotNetClrResolvedTypeSignature.SzArray ->
            elementType.containsClrMethodTypeParameter()

        is DotNetClrResolvedTypeSignature.Array ->
            elementType.containsClrMethodTypeParameter()

        is DotNetClrResolvedTypeSignature.GenericInstance ->
            arguments.any(DotNetClrResolvedTypeSignature::containsClrMethodTypeParameter)

        is DotNetClrResolvedTypeSignature.FunctionPointer ->
            signature.returnType.containsClrMethodTypeParameter() ||
                    signature.parameterTypes.any(
                        DotNetClrResolvedTypeSignature::containsClrMethodTypeParameter
                    )

        is DotNetClrResolvedTypeSignature.Modified ->
            unmodifiedType.containsClrMethodTypeParameter()

        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Primitive,
        is DotNetClrResolvedTypeSignature.Named,
        -> false
    }
