/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrEffectiveAccessibility {
    PUBLIC,
    INTERNAL,
    PRIVATE,
}

enum class DotNetClrEffectiveAccessibilityFailure {
    DECLARATION_NOT_FOUND,
    INVALID_OWNER,
    INVALID_TYPE_VISIBILITY,
    TYPE_NESTING_CYCLE,
    TYPE_NESTING_LIMIT_EXCEEDED,
}

sealed interface DotNetClrEffectiveAccessibilityResolution {
    data class Resolved(
        val accessibility: DotNetClrEffectiveAccessibility,
    ) : DotNetClrEffectiveAccessibilityResolution

    data class Invalid(
        val failure: DotNetClrEffectiveAccessibilityFailure,
        val declaration: DotNetClrMetadataHandle,
        val owner: DotNetClrMetadataHandle? = null,
        val limit: Int? = null,
    ) : DotNetClrEffectiveAccessibilityResolution
}

/**
 * Computes Roslyn's public/internal/private nullable-accessibility category for a physical CLR declaration
 * together with all containing types.
 *
 * The categories match the distinction used by Roslyn's nullable-public-only policy. They are
 * not Kotlin visibility and do not account for one particular caller or friend assembly.
 */
class DotNetClrNullableEffectiveAccessibilityResolver(
    private val nestingLimit: Int = DEFAULT_NESTING_LIMIT,
) {
    init {
        require(nestingLimit > 0) { "CLR accessibility nesting limit must be positive" }
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        type: DotNetClrTypeDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.typeDefinitions.none { definition -> definition == type }) {
            return notFound(type.handle)
        }
        return resolveType(assembly, type)
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMethodDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.methodDefinitions.none { definition -> definition == method }) {
            return notFound(method.handle)
        }
        return combineWithDeclaringType(
            assembly,
            method.handle,
            method.declaringType,
            method.visibility.toEffectiveAccessibility(),
        )
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        field: DotNetClrFieldDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.fieldDefinitions.none { definition -> definition == field }) {
            return notFound(field.handle)
        }
        return combineWithDeclaringType(
            assembly,
            field.handle,
            field.declaringType,
            field.visibility.toEffectiveAccessibility(),
        )
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        property: DotNetClrPropertyDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.propertyDefinitions.none { definition -> definition == property }) {
            return notFound(property.handle)
        }
        // Property rows have no CLR accessibility. Roslyn's nullable-public-only contract uses
        // the containing type as the access symbol, not the property's accessor MethodDefs.
        return resolveOwnerType(assembly, property.handle, property.declaringType)
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrParameterDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.parameterDefinitions.none { definition -> definition == parameter }) {
            return notFound(parameter.handle)
        }
        val method = assembly.methodDefinitions.singleOrNull { definition ->
            definition.handle == parameter.declaringMethod
        } ?: return invalidOwner(parameter.handle, parameter.declaringMethod)
        return resolve(assembly, method)
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrGenericParameterDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.genericParameterDefinitions.none { definition -> definition == parameter }) {
            return notFound(parameter.handle)
        }
        return resolveGenericParameterOwner(assembly, parameter.handle, parameter)
    }

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        constraint: DotNetClrGenericParameterConstraint,
    ): DotNetClrEffectiveAccessibilityResolution {
        if (assembly.genericParameterConstraints.none { row -> row == constraint }) {
            return notFound(constraint.handle)
        }
        val parameter = assembly.genericParameterDefinitions.singleOrNull { definition ->
            definition.handle == constraint.owner
        } ?: return invalidOwner(constraint.handle, constraint.owner)
        return resolveGenericParameterOwner(assembly, constraint.handle, parameter)
    }

    private fun resolveGenericParameterOwner(
        assembly: DotNetClrAssemblyMetadata,
        declaration: DotNetClrMetadataHandle,
        parameter: DotNetClrGenericParameterDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        return when (parameter.owner.table) {
            TYPE_DEFINITION_TABLE -> {
                val type = assembly.typeDefinitions.singleOrNull { definition ->
                    definition.handle == parameter.owner
                } ?: return invalidOwner(declaration, parameter.owner)
                resolve(assembly, type)
            }
            METHOD_DEFINITION_TABLE -> {
                val method = assembly.methodDefinitions.singleOrNull { definition ->
                    definition.handle == parameter.owner
                } ?: return invalidOwner(declaration, parameter.owner)
                resolve(assembly, method)
            }
            else -> invalidOwner(declaration, parameter.owner)
        }
    }

    private fun resolveType(
        assembly: DotNetClrAssemblyMetadata,
        initialType: DotNetClrTypeDefinition,
    ): DotNetClrEffectiveAccessibilityResolution {
        var effective = DotNetClrEffectiveAccessibility.PUBLIC
        var current = initialType
        val visited = HashSet<DotNetClrMetadataHandle>()
        var depth = 0
        while (true) {
            if (!visited.add(current.handle)) {
                return DotNetClrEffectiveAccessibilityResolution.Invalid(
                    failure = DotNetClrEffectiveAccessibilityFailure.TYPE_NESTING_CYCLE,
                    declaration = initialType.handle,
                    owner = current.handle,
                )
            }
            if (++depth > nestingLimit) {
                return DotNetClrEffectiveAccessibilityResolution.Invalid(
                    failure =
                        DotNetClrEffectiveAccessibilityFailure.TYPE_NESTING_LIMIT_EXCEEDED,
                    declaration = initialType.handle,
                    owner = current.handle,
                    limit = nestingLimit,
                )
            }

            val local = current.visibility.toEffectiveAccessibility(
                isNested = current.declaringType != null
            ) ?: return DotNetClrEffectiveAccessibilityResolution.Invalid(
                failure = DotNetClrEffectiveAccessibilityFailure.INVALID_TYPE_VISIBILITY,
                declaration = current.handle,
                owner = current.declaringType,
            )
            effective = effective.combine(local)
            val declaringType = current.declaringType
                ?: return DotNetClrEffectiveAccessibilityResolution.Resolved(effective)
            current = assembly.typeDefinitions.singleOrNull { definition ->
                definition.handle == declaringType
            } ?: return invalidOwner(current.handle, declaringType)
        }
    }

    private fun combineWithDeclaringType(
        assembly: DotNetClrAssemblyMetadata,
        declaration: DotNetClrMetadataHandle,
        declaringType: DotNetClrMetadataHandle,
        local: DotNetClrEffectiveAccessibility,
    ): DotNetClrEffectiveAccessibilityResolution {
        val owner = resolveOwnerType(assembly, declaration, declaringType)
        return when (owner) {
            is DotNetClrEffectiveAccessibilityResolution.Resolved ->
                DotNetClrEffectiveAccessibilityResolution.Resolved(
                    local.combine(owner.accessibility)
                )
            is DotNetClrEffectiveAccessibilityResolution.Invalid -> owner
        }
    }

    private fun resolveOwnerType(
        assembly: DotNetClrAssemblyMetadata,
        declaration: DotNetClrMetadataHandle,
        declaringType: DotNetClrMetadataHandle,
    ): DotNetClrEffectiveAccessibilityResolution {
        val owner = assembly.typeDefinitions.singleOrNull { definition ->
            definition.handle == declaringType
        } ?: return invalidOwner(declaration, declaringType)
        return resolve(assembly, owner)
    }

    private fun DotNetClrEffectiveAccessibility.combine(
        other: DotNetClrEffectiveAccessibility,
    ): DotNetClrEffectiveAccessibility =
        when {
            this == DotNetClrEffectiveAccessibility.PRIVATE ||
                    other == DotNetClrEffectiveAccessibility.PRIVATE ->
                DotNetClrEffectiveAccessibility.PRIVATE

            this == DotNetClrEffectiveAccessibility.INTERNAL ||
                    other == DotNetClrEffectiveAccessibility.INTERNAL ->
                DotNetClrEffectiveAccessibility.INTERNAL

            else -> DotNetClrEffectiveAccessibility.PUBLIC
        }

    private fun DotNetClrMethodVisibility.toEffectiveAccessibility():
            DotNetClrEffectiveAccessibility =
        when (this) {
            DotNetClrMethodVisibility.PUBLIC,
            DotNetClrMethodVisibility.FAMILY,
            DotNetClrMethodVisibility.FAMILY_OR_ASSEMBLY,
            -> DotNetClrEffectiveAccessibility.PUBLIC

            DotNetClrMethodVisibility.ASSEMBLY,
            DotNetClrMethodVisibility.FAMILY_AND_ASSEMBLY,
            -> DotNetClrEffectiveAccessibility.INTERNAL

            DotNetClrMethodVisibility.COMPILER_CONTROLLED,
            DotNetClrMethodVisibility.PRIVATE,
            -> DotNetClrEffectiveAccessibility.PRIVATE
        }

    private fun DotNetClrFieldVisibility.toEffectiveAccessibility():
            DotNetClrEffectiveAccessibility =
        when (this) {
            DotNetClrFieldVisibility.PUBLIC,
            DotNetClrFieldVisibility.FAMILY,
            DotNetClrFieldVisibility.FAMILY_OR_ASSEMBLY,
            -> DotNetClrEffectiveAccessibility.PUBLIC

            DotNetClrFieldVisibility.ASSEMBLY,
            DotNetClrFieldVisibility.FAMILY_AND_ASSEMBLY,
            -> DotNetClrEffectiveAccessibility.INTERNAL

            DotNetClrFieldVisibility.COMPILER_CONTROLLED,
            DotNetClrFieldVisibility.PRIVATE,
            -> DotNetClrEffectiveAccessibility.PRIVATE
        }

    private fun DotNetClrTypeVisibility.toEffectiveAccessibility(
        isNested: Boolean,
    ): DotNetClrEffectiveAccessibility? =
        if (isNested) {
            when (this) {
                DotNetClrTypeVisibility.NESTED_PUBLIC,
                DotNetClrTypeVisibility.NESTED_FAMILY,
                DotNetClrTypeVisibility.NESTED_FAMILY_OR_ASSEMBLY,
                -> DotNetClrEffectiveAccessibility.PUBLIC

                DotNetClrTypeVisibility.NESTED_ASSEMBLY,
                DotNetClrTypeVisibility.NESTED_FAMILY_AND_ASSEMBLY,
                -> DotNetClrEffectiveAccessibility.INTERNAL

                DotNetClrTypeVisibility.NESTED_PRIVATE ->
                    DotNetClrEffectiveAccessibility.PRIVATE

                DotNetClrTypeVisibility.NOT_PUBLIC,
                DotNetClrTypeVisibility.PUBLIC,
                -> null
            }
        } else {
            when (this) {
                DotNetClrTypeVisibility.PUBLIC ->
                    DotNetClrEffectiveAccessibility.PUBLIC

                DotNetClrTypeVisibility.NOT_PUBLIC ->
                    DotNetClrEffectiveAccessibility.INTERNAL

                DotNetClrTypeVisibility.NESTED_PUBLIC,
                DotNetClrTypeVisibility.NESTED_PRIVATE,
                DotNetClrTypeVisibility.NESTED_FAMILY,
                DotNetClrTypeVisibility.NESTED_ASSEMBLY,
                DotNetClrTypeVisibility.NESTED_FAMILY_AND_ASSEMBLY,
                DotNetClrTypeVisibility.NESTED_FAMILY_OR_ASSEMBLY,
                -> null
            }
        }

    private fun notFound(
        declaration: DotNetClrMetadataHandle,
    ): DotNetClrEffectiveAccessibilityResolution.Invalid =
        DotNetClrEffectiveAccessibilityResolution.Invalid(
            DotNetClrEffectiveAccessibilityFailure.DECLARATION_NOT_FOUND,
            declaration,
        )

    private fun invalidOwner(
        declaration: DotNetClrMetadataHandle,
        owner: DotNetClrMetadataHandle,
    ): DotNetClrEffectiveAccessibilityResolution.Invalid =
        DotNetClrEffectiveAccessibilityResolution.Invalid(
            DotNetClrEffectiveAccessibilityFailure.INVALID_OWNER,
            declaration,
            owner,
        )

    private companion object {
        const val TYPE_DEFINITION_TABLE = 2
        const val METHOD_DEFINITION_TABLE = 6
        const val DEFAULT_NESTING_LIMIT = 256
    }
}
