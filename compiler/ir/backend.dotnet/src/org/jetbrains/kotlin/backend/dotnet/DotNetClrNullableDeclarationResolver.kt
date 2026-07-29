/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

sealed interface DotNetClrNullableDeclarationTarget {
    data class MethodReturn(
        val method: DotNetClrMethodDefinition,
    ) : DotNetClrNullableDeclarationTarget

    data class MethodParameter(
        val method: DotNetClrMethodDefinition,
        val index: Int,
    ) : DotNetClrNullableDeclarationTarget

    data class Field(
        val field: DotNetClrFieldDefinition,
    ) : DotNetClrNullableDeclarationTarget

    data class Property(
        val property: DotNetClrPropertyDefinition,
    ) : DotNetClrNullableDeclarationTarget

    data class GenericParameter(
        val parameter: DotNetClrGenericParameterDefinition,
    ) : DotNetClrNullableDeclarationTarget

    data class GenericParameterConstraint(
        val constraint: DotNetClrGenericParameterConstraint,
    ) : DotNetClrNullableDeclarationTarget
}

enum class DotNetClrNullablePublicPolicy {
    ALL,
    PUBLIC,
    PUBLIC_AND_INTERNAL,
}

enum class DotNetClrNullableEvidenceSource {
    LOCAL_ATTRIBUTE,
    CONTEXT_ATTRIBUTE,
}

enum class DotNetClrNullableDeclarationFailure {
    DECLARATION_NOT_FOUND,
    INVALID_OWNER,
    PARAMETER_INDEX_OUT_OF_RANGE,
    AMBIGUOUS_PARAMETER_ROW,
    INVALID_PUBLIC_ONLY,
    INVALID_ACCESSIBILITY,
    INVALID_LOCAL_TRANSFORM,
    INVALID_CONTEXT,
    TYPE_NESTING_CYCLE,
    TYPE_NESTING_LIMIT_EXCEEDED,
}

sealed interface DotNetClrNullableDeclarationEvidence {
    data class Selected(
        val transform: DotNetClrNullableTransform,
        val source: DotNetClrNullableEvidenceSource,
        val attribute: DotNetClrMetadataHandle,
        val contextOwner: DotNetClrMetadataHandle? = null,
        val accessibility: DotNetClrEffectiveAccessibility,
        val publicPolicy: DotNetClrNullablePublicPolicy,
    ) : DotNetClrNullableDeclarationEvidence

    data class Oblivious(
        val accessibility: DotNetClrEffectiveAccessibility,
        val publicPolicy: DotNetClrNullablePublicPolicy,
    ) : DotNetClrNullableDeclarationEvidence

    data class Suppressed(
        val accessibility: DotNetClrEffectiveAccessibility,
        val publicPolicy: DotNetClrNullablePublicPolicy,
    ) : DotNetClrNullableDeclarationEvidence

    data class Invalid(
        val failure: DotNetClrNullableDeclarationFailure,
        val declaration: DotNetClrMetadataHandle,
        val owner: DotNetClrMetadataHandle? = null,
        val parameterIndex: Int? = null,
        val limit: Int? = null,
        val metadataResolution: DotNetClrNullableMetadataResolution<*>? = null,
        val accessibilityResolution: DotNetClrEffectiveAccessibilityResolution.Invalid? = null,
    ) : DotNetClrNullableDeclarationEvidence
}

/**
 * Selects local Roslyn nullable evidence or the nearest method/type context for one physical CLR
 * declaration site.
 *
 * NullablePublicOnly is applied before local/context values are interpreted. Evidence excluded by
 * that module policy is suppressed rather than diagnosed or projected. This class still does not
 * construct a Kotlin type.
 */
class DotNetClrNullableDeclarationResolver(
    private val metadataDecoder: DotNetClrNullableMetadataDecoder,
    private val accessibilityResolver: DotNetClrNullableEffectiveAccessibilityResolver =
        DotNetClrNullableEffectiveAccessibilityResolver(),
    private val nestingLimit: Int = DEFAULT_NESTING_LIMIT,
) {
    init {
        require(nestingLimit > 0) { "CLR nullable-context nesting limit must be positive" }
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        target: DotNetClrNullableDeclarationTarget,
    ): DotNetClrNullableDeclarationEvidence {
        val site = when (val resolution = resolveSite(assembly, target)) {
            is SiteResolution.Resolved -> resolution.site
            is SiteResolution.Invalid -> return resolution.evidence
        }
        val policy = when (val publicOnly = metadataDecoder.decodePublicOnly(assembly)) {
            DotNetClrNullableMetadataResolution.Absent ->
                DotNetClrNullablePublicPolicy.ALL

            is DotNetClrNullableMetadataResolution.Decoded ->
                if (publicOnly.value) {
                    DotNetClrNullablePublicPolicy.PUBLIC_AND_INTERNAL
                } else {
                    DotNetClrNullablePublicPolicy.PUBLIC
                }

            is DotNetClrNullableMetadataResolution.Invalid ->
                return DotNetClrNullableDeclarationEvidence.Invalid(
                    failure = DotNetClrNullableDeclarationFailure.INVALID_PUBLIC_ONLY,
                    declaration = site.declaration,
                    metadataResolution = publicOnly,
                )
        }
        val accessibility = when (val resolution = site.accessibility) {
            is DotNetClrEffectiveAccessibilityResolution.Resolved ->
                resolution.accessibility

            is DotNetClrEffectiveAccessibilityResolution.Invalid ->
                return DotNetClrNullableDeclarationEvidence.Invalid(
                    failure = DotNetClrNullableDeclarationFailure.INVALID_ACCESSIBILITY,
                    declaration = site.declaration,
                    accessibilityResolution = resolution,
                )
        }
        if (!policy.includes(accessibility)) {
            return DotNetClrNullableDeclarationEvidence.Suppressed(
                accessibility,
                policy,
            )
        }

        site.annotationParent?.let { parent ->
            when (val local = metadataDecoder.decodeTransform(assembly, parent)) {
                DotNetClrNullableMetadataResolution.Absent -> Unit
                is DotNetClrNullableMetadataResolution.Decoded ->
                    return DotNetClrNullableDeclarationEvidence.Selected(
                        transform = local.value,
                        source = DotNetClrNullableEvidenceSource.LOCAL_ATTRIBUTE,
                        attribute = local.attribute,
                        accessibility = accessibility,
                        publicPolicy = policy,
                    )
                is DotNetClrNullableMetadataResolution.Invalid ->
                    return DotNetClrNullableDeclarationEvidence.Invalid(
                        failure = DotNetClrNullableDeclarationFailure.INVALID_LOCAL_TRANSFORM,
                        declaration = site.declaration,
                        metadataResolution = local,
                    )
            }
        }

        for (contextOwner in site.contextOwners) {
            when (val context = metadataDecoder.decodeContext(assembly, contextOwner)) {
                DotNetClrNullableMetadataResolution.Absent -> Unit
                is DotNetClrNullableMetadataResolution.Decoded ->
                    return DotNetClrNullableDeclarationEvidence.Selected(
                        transform = DotNetClrNullableTransform.Uniform(context.value),
                        source = DotNetClrNullableEvidenceSource.CONTEXT_ATTRIBUTE,
                        attribute = context.attribute,
                        contextOwner = contextOwner,
                        accessibility = accessibility,
                        publicPolicy = policy,
                    )
                is DotNetClrNullableMetadataResolution.Invalid ->
                    return DotNetClrNullableDeclarationEvidence.Invalid(
                        failure = DotNetClrNullableDeclarationFailure.INVALID_CONTEXT,
                        declaration = site.declaration,
                        owner = contextOwner,
                        metadataResolution = context,
                    )
            }
        }
        return DotNetClrNullableDeclarationEvidence.Oblivious(
            accessibility,
            policy,
        )
    }

    private fun resolveSite(
        assembly: DotNetClrAssemblyMetadata,
        target: DotNetClrNullableDeclarationTarget,
    ): SiteResolution =
        when (target) {
            is DotNetClrNullableDeclarationTarget.MethodReturn ->
                resolveMethodSite(
                    assembly,
                    target.method,
                    isReturn = true,
                    parameterIndex = null,
                )

            is DotNetClrNullableDeclarationTarget.MethodParameter ->
                resolveMethodSite(
                    assembly,
                    target.method,
                    isReturn = false,
                    parameterIndex = target.index,
                )

            is DotNetClrNullableDeclarationTarget.Field -> {
                if (assembly.fieldDefinitions.none { field -> field == target.field }) {
                    invalid(
                        DotNetClrNullableDeclarationFailure.DECLARATION_NOT_FOUND,
                        target.field.handle,
                    )
                } else {
                    resolveTypeContexts(
                        assembly,
                        target.field.handle,
                        target.field.declaringType,
                    ) { contexts ->
                        Site(
                            declaration = target.field.handle,
                            annotationParent = target.field.handle,
                            contextOwners = contexts,
                            accessibility =
                                accessibilityResolver.resolve(assembly, target.field),
                        )
                    }
                }
            }

            is DotNetClrNullableDeclarationTarget.Property -> {
                if (assembly.propertyDefinitions.none { property ->
                        property == target.property
                    }
                ) {
                    invalid(
                        DotNetClrNullableDeclarationFailure.DECLARATION_NOT_FOUND,
                        target.property.handle,
                    )
                } else {
                    resolveTypeContexts(
                        assembly,
                        target.property.handle,
                        target.property.declaringType,
                    ) { contexts ->
                        Site(
                            declaration = target.property.handle,
                            annotationParent = target.property.handle,
                            contextOwners = contexts,
                            accessibility =
                                accessibilityResolver.resolve(assembly, target.property),
                        )
                    }
                }
            }

            is DotNetClrNullableDeclarationTarget.GenericParameter ->
                resolveGenericParameterSite(assembly, target.parameter)

            is DotNetClrNullableDeclarationTarget.GenericParameterConstraint ->
                resolveGenericParameterConstraintSite(assembly, target.constraint)
        }

    private fun resolveMethodSite(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMethodDefinition,
        isReturn: Boolean,
        parameterIndex: Int?,
    ): SiteResolution {
        if (assembly.methodDefinitions.none { definition -> definition == method }) {
            return invalid(
                DotNetClrNullableDeclarationFailure.DECLARATION_NOT_FOUND,
                method.handle,
            )
        }
        if (!isReturn &&
            (parameterIndex == null || parameterIndex !in method.signature.parameterTypes.indices)
        ) {
            return invalid(
                failure = DotNetClrNullableDeclarationFailure.PARAMETER_INDEX_OUT_OF_RANGE,
                declaration = method.handle,
                parameterIndex = parameterIndex,
            )
        }
        val sequence = if (isReturn) 0 else checkNotNull(parameterIndex) + 1
        val rows = assembly.parameterDefinitions.filter { parameter ->
            parameter.declaringMethod == method.handle && parameter.sequence == sequence
        }
        if (rows.size > 1) {
            return invalid(
                failure = DotNetClrNullableDeclarationFailure.AMBIGUOUS_PARAMETER_ROW,
                declaration = method.handle,
                parameterIndex = parameterIndex,
            )
        }
        return resolveTypeContexts(
            assembly,
            method.handle,
            method.declaringType,
        ) { contexts ->
            Site(
                declaration = method.handle,
                annotationParent = rows.singleOrNull()?.handle,
                contextOwners = listOf(method.handle) + contexts,
                accessibility = accessibilityResolver.resolve(assembly, method),
            )
        }
    }

    private fun resolveGenericParameterSite(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrGenericParameterDefinition,
    ): SiteResolution {
        if (assembly.genericParameterDefinitions.none { definition ->
                definition == parameter
            }
        ) {
            return invalid(
                DotNetClrNullableDeclarationFailure.DECLARATION_NOT_FOUND,
                parameter.handle,
            )
        }
        return resolveGenericParameterOwnerSite(
            assembly,
            parameter,
            declaration = parameter.handle,
            annotationParent = parameter.handle,
            accessibility = accessibilityResolver.resolve(assembly, parameter),
        )
    }

    private fun resolveGenericParameterConstraintSite(
        assembly: DotNetClrAssemblyMetadata,
        constraint: DotNetClrGenericParameterConstraint,
    ): SiteResolution {
        if (assembly.genericParameterConstraints.none { row -> row == constraint }) {
            return invalid(
                DotNetClrNullableDeclarationFailure.DECLARATION_NOT_FOUND,
                constraint.handle,
            )
        }
        val parameter = assembly.genericParameterDefinitions.singleOrNull { definition ->
            definition.handle == constraint.owner
        } ?: return invalid(
            DotNetClrNullableDeclarationFailure.INVALID_OWNER,
            constraint.handle,
            constraint.owner,
        )
        return resolveGenericParameterOwnerSite(
            assembly,
            parameter,
            declaration = constraint.handle,
            annotationParent = constraint.handle,
            accessibility = accessibilityResolver.resolve(assembly, constraint),
        )
    }

    private fun resolveGenericParameterOwnerSite(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrGenericParameterDefinition,
        declaration: DotNetClrMetadataHandle,
        annotationParent: DotNetClrMetadataHandle,
        accessibility: DotNetClrEffectiveAccessibilityResolution,
    ): SiteResolution {
        return when (parameter.owner.table) {
            TYPE_DEFINITION_TABLE -> {
                val type = assembly.typeDefinitions.singleOrNull { definition ->
                    definition.handle == parameter.owner
                } ?: return invalid(
                    DotNetClrNullableDeclarationFailure.INVALID_OWNER,
                    declaration,
                    parameter.owner,
                )
                resolveTypeContexts(
                    assembly,
                    declaration,
                    type.handle,
                ) { contexts ->
                    Site(
                        declaration = declaration,
                        annotationParent = annotationParent,
                        contextOwners = contexts,
                        accessibility = accessibility,
                    )
                }
            }
            METHOD_DEFINITION_TABLE -> {
                val method = assembly.methodDefinitions.singleOrNull { definition ->
                    definition.handle == parameter.owner
                } ?: return invalid(
                    DotNetClrNullableDeclarationFailure.INVALID_OWNER,
                    declaration,
                    parameter.owner,
                )
                resolveTypeContexts(
                    assembly,
                    declaration,
                    method.declaringType,
                ) { contexts ->
                    Site(
                        declaration = declaration,
                        annotationParent = annotationParent,
                        contextOwners = listOf(method.handle) + contexts,
                        accessibility = accessibility,
                    )
                }
            }
            else -> invalid(
                DotNetClrNullableDeclarationFailure.INVALID_OWNER,
                declaration,
                parameter.owner,
            )
        }
    }

    private fun resolveTypeContexts(
        assembly: DotNetClrAssemblyMetadata,
        declaration: DotNetClrMetadataHandle,
        initialType: DotNetClrMetadataHandle,
        createSite: (List<DotNetClrMetadataHandle>) -> Site,
    ): SiteResolution {
        val contexts = ArrayList<DotNetClrMetadataHandle>()
        val visited = HashSet<DotNetClrMetadataHandle>()
        var currentHandle = initialType
        var depth = 0
        while (true) {
            if (!visited.add(currentHandle)) {
                return invalid(
                    DotNetClrNullableDeclarationFailure.TYPE_NESTING_CYCLE,
                    declaration,
                    currentHandle,
                )
            }
            if (++depth > nestingLimit) {
                return SiteResolution.Invalid(
                    DotNetClrNullableDeclarationEvidence.Invalid(
                        failure =
                            DotNetClrNullableDeclarationFailure
                                .TYPE_NESTING_LIMIT_EXCEEDED,
                        declaration = declaration,
                        owner = currentHandle,
                        limit = nestingLimit,
                    )
                )
            }
            val type = assembly.typeDefinitions.singleOrNull { definition ->
                definition.handle == currentHandle
            } ?: return invalid(
                DotNetClrNullableDeclarationFailure.INVALID_OWNER,
                declaration,
                currentHandle,
            )
            contexts += type.handle
            currentHandle = type.declaringType ?: return SiteResolution.Resolved(
                createSite(contexts)
            )
        }
    }

    private fun DotNetClrNullablePublicPolicy.includes(
        accessibility: DotNetClrEffectiveAccessibility,
    ): Boolean =
        when (this) {
            DotNetClrNullablePublicPolicy.ALL -> true
            DotNetClrNullablePublicPolicy.PUBLIC ->
                accessibility == DotNetClrEffectiveAccessibility.PUBLIC
            DotNetClrNullablePublicPolicy.PUBLIC_AND_INTERNAL ->
                accessibility != DotNetClrEffectiveAccessibility.PRIVATE
        }

    private fun invalid(
        failure: DotNetClrNullableDeclarationFailure,
        declaration: DotNetClrMetadataHandle,
        owner: DotNetClrMetadataHandle? = null,
        parameterIndex: Int? = null,
    ): SiteResolution.Invalid =
        SiteResolution.Invalid(
            DotNetClrNullableDeclarationEvidence.Invalid(
                failure = failure,
                declaration = declaration,
                owner = owner,
                parameterIndex = parameterIndex,
            )
        )

    private data class Site(
        val declaration: DotNetClrMetadataHandle,
        val annotationParent: DotNetClrMetadataHandle?,
        val contextOwners: List<DotNetClrMetadataHandle>,
        val accessibility: DotNetClrEffectiveAccessibilityResolution,
    )

    private sealed interface SiteResolution {
        data class Resolved(
            val site: Site,
        ) : SiteResolution

        data class Invalid(
            val evidence: DotNetClrNullableDeclarationEvidence.Invalid,
        ) : SiteResolution
    }

    private companion object {
        const val TYPE_DEFINITION_TABLE = 2
        const val METHOD_DEFINITION_TABLE = 6
        const val DEFAULT_NESTING_LIMIT = 256
    }
}
