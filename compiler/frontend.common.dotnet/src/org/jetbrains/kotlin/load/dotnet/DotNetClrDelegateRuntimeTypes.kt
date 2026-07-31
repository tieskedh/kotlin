package org.jetbrains.kotlin.load.dotnet

class DotNetClrDelegateRuntimeTypes internal constructor(
    val systemMulticastDelegate: DotNetClrResolvedTypeDefinition,
)

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
