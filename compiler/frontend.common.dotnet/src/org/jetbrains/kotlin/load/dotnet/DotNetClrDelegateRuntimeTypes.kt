package org.jetbrains.kotlin.load.dotnet

class DotNetClrDelegateRuntimeTypes internal constructor(
    val systemMulticastDelegate: DotNetClrResolvedTypeDefinition,
)

enum class DotNetClrDelegateTypeFailure {
    DELEGATE_IS_NOT_SEALED,
}

sealed interface DotNetClrDelegateTypeClassification {
    data object Delegate : DotNetClrDelegateTypeClassification

    data object NotDelegate : DotNetClrDelegateTypeClassification

    data class Invalid(
        val failure: DotNetClrDelegateTypeFailure,
    ) : DotNetClrDelegateTypeClassification
}

/**
 * Classifies one already-resolved CLR hierarchy by exact delegate-root identity.
 *
 * Names and `Invoke`-shaped members are not evidence. A non-interface TypeDef is a delegate only
 * when its immediate selected base is the selected `System.MulticastDelegate` TypeDef and the
 * derived TypeDef is sealed, matching the CLR variance boundary.
 */
class DotNetClrDelegateTypeClassifier(
    private val runtimeTypes: DotNetClrDelegateRuntimeTypes,
) {
    fun classify(
        hierarchy: DotNetClrResolvedTypeHierarchy,
    ): DotNetClrDelegateTypeClassification {
        val definition = hierarchy.type.type.definition
        if (definition.isInterface ||
            hierarchy.baseType?.type?.hasSameIdentityAs(
                runtimeTypes.systemMulticastDelegate,
            ) != true
        ) {
            return DotNetClrDelegateTypeClassification.NotDelegate
        }
        return if (definition.isSealed) {
            DotNetClrDelegateTypeClassification.Delegate
        } else {
            DotNetClrDelegateTypeClassification.Invalid(
                DotNetClrDelegateTypeFailure.DELEGATE_IS_NOT_SEALED,
            )
        }
    }
}

enum class DotNetClrDelegateRuntimeTypeFailure {
    SYSTEM_MULTICAST_DELEGATE_IS_INTERFACE,
    SYSTEM_MULTICAST_DELEGATE_IS_NOT_ABSTRACT,
    SYSTEM_MULTICAST_DELEGATE_GENERIC_ARITY,
}

sealed interface DotNetClrDelegateRuntimeTypesResolution {
    data class Resolved(
        val types: DotNetClrDelegateRuntimeTypes,
    ) : DotNetClrDelegateRuntimeTypesResolution

    data class Unresolved(
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrDelegateRuntimeTypesResolution

    data class Invalid(
        val failure: DotNetClrDelegateRuntimeTypeFailure,
        val type: DotNetClrResolvedTypeDefinition,
        val expectedGenericArity: Int? = null,
        val actualGenericArity: Int? = null,
    ) : DotNetClrDelegateRuntimeTypesResolution
}

/**
 * Resolves the physical delegate root through one selected reference-assembly graph.
 *
 * Delegate recognition consumes only the resulting TypeDef identity. It never infers a delegate
 * from `Func`/`Action` names or from the presence of a conventionally named `Invoke` method.
 */
class DotNetClrDelegateRuntimeTypesResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    fun resolve(
        selectedCoreAssembly: DotNetClrAssemblyMetadata,
    ): DotNetClrDelegateRuntimeTypesResolution {
        val systemMulticastDelegate = when (
            val resolution =
                typeResolver.resolveTopLevelType(
                    selectedCoreAssembly,
                    "System",
                    "MulticastDelegate",
                )
        ) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved ->
                return DotNetClrDelegateRuntimeTypesResolution.Unresolved(
                    resolution
                )
        }
        if (systemMulticastDelegate.definition.isInterface) {
            return DotNetClrDelegateRuntimeTypesResolution.Invalid(
                DotNetClrDelegateRuntimeTypeFailure
                    .SYSTEM_MULTICAST_DELEGATE_IS_INTERFACE,
                systemMulticastDelegate,
            )
        }
        if (!systemMulticastDelegate.definition.isAbstract) {
            return DotNetClrDelegateRuntimeTypesResolution.Invalid(
                DotNetClrDelegateRuntimeTypeFailure
                    .SYSTEM_MULTICAST_DELEGATE_IS_NOT_ABSTRACT,
                systemMulticastDelegate,
            )
        }
        val genericArity = systemMulticastDelegate.genericArity()
        if (genericArity != 0) {
            return DotNetClrDelegateRuntimeTypesResolution.Invalid(
                DotNetClrDelegateRuntimeTypeFailure
                    .SYSTEM_MULTICAST_DELEGATE_GENERIC_ARITY,
                systemMulticastDelegate,
                expectedGenericArity = 0,
                actualGenericArity = genericArity,
            )
        }
        return DotNetClrDelegateRuntimeTypesResolution.Resolved(
            DotNetClrDelegateRuntimeTypes(systemMulticastDelegate)
        )
    }
}
