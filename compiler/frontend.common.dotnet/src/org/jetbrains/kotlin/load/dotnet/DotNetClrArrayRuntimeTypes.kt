package org.jetbrains.kotlin.load.dotnet

enum class DotNetClrVectorGenericInterface(
    val namespaceName: String,
    val metadataName: String,
) {
    LIST("System.Collections.Generic", "IList`1"),
    COLLECTION("System.Collections.Generic", "ICollection`1"),
    ENUMERABLE("System.Collections.Generic", "IEnumerable`1"),
    READ_ONLY_LIST("System.Collections.Generic", "IReadOnlyList`1"),
    READ_ONLY_COLLECTION(
        "System.Collections.Generic",
        "IReadOnlyCollection`1",
    ),
}

class DotNetClrArrayRuntimeTypes internal constructor(
    val systemArray: DotNetClrResolvedTypeDefinition,
    vectorInterfaces: Map<
        DotNetClrVectorGenericInterface,
        DotNetClrResolvedTypeDefinition,
    >,
) {
    private val vectorInterfaces = vectorInterfaces.toMap()

    init {
        require(
            this.vectorInterfaces.keys ==
                    DotNetClrVectorGenericInterface.entries.toSet()
        ) {
            "A CLR array runtime type catalog must contain every vector interface"
        }
    }

    fun vectorInterface(
        type: DotNetClrResolvedTypeDefinition,
    ): DotNetClrVectorGenericInterface? =
        vectorInterfaces.entries.singleOrNull { entry ->
            entry.value.hasSameIdentityAs(type)
        }?.key
}

enum class DotNetClrArrayRuntimeTypeFailure {
    SYSTEM_ARRAY_IS_INTERFACE,
    SYSTEM_ARRAY_GENERIC_ARITY,
    VECTOR_TYPE_IS_NOT_INTERFACE,
    VECTOR_TYPE_GENERIC_ARITY,
}

sealed interface DotNetClrArrayRuntimeTypesResolution {
    data class Resolved(
        val types: DotNetClrArrayRuntimeTypes,
    ) : DotNetClrArrayRuntimeTypesResolution

    data class Unresolved(
        val vectorInterface: DotNetClrVectorGenericInterface?,
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrArrayRuntimeTypesResolution

    data class Invalid(
        val failure: DotNetClrArrayRuntimeTypeFailure,
        val type: DotNetClrResolvedTypeDefinition,
        val vectorInterface: DotNetClrVectorGenericInterface? = null,
        val expectedGenericArity: Int? = null,
        val actualGenericArity: Int? = null,
    ) : DotNetClrArrayRuntimeTypesResolution
}

/**
 * Resolves the VES/BCL array surface through one selected reference-assembly graph.
 *
 * The assignability layer consumes only the resulting TypeDef identities. It never recognizes
 * `System.Array` or a generic vector interface from a namespace/name pair supplied by an arbitrary
 * foreign assembly.
 */
class DotNetClrArrayRuntimeTypesResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    fun resolve(
        selectedCoreAssembly: DotNetClrAssemblyMetadata,
    ): DotNetClrArrayRuntimeTypesResolution {
        val systemArray = when (
            val resolution =
                typeResolver.resolveTopLevelType(
                    selectedCoreAssembly,
                    "System",
                    "Array",
                )
        ) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved ->
                return DotNetClrArrayRuntimeTypesResolution.Unresolved(
                    vectorInterface = null,
                    resolution,
                )
        }
        if (systemArray.definition.isInterface) {
            return DotNetClrArrayRuntimeTypesResolution.Invalid(
                DotNetClrArrayRuntimeTypeFailure.SYSTEM_ARRAY_IS_INTERFACE,
                systemArray,
            )
        }
        val systemArrayArity = systemArray.genericArity()
        if (systemArrayArity != 0) {
            return DotNetClrArrayRuntimeTypesResolution.Invalid(
                DotNetClrArrayRuntimeTypeFailure.SYSTEM_ARRAY_GENERIC_ARITY,
                systemArray,
                expectedGenericArity = 0,
                actualGenericArity = systemArrayArity,
            )
        }

        val vectorInterfaces = linkedMapOf<
            DotNetClrVectorGenericInterface,
            DotNetClrResolvedTypeDefinition,
        >()
        for (kind in DotNetClrVectorGenericInterface.entries) {
            val type = when (
                val resolution =
                    typeResolver.resolveTopLevelType(
                        selectedCoreAssembly,
                        kind.namespaceName,
                        kind.metadataName,
                    )
            ) {
                is DotNetClrTypeResolution.Resolved -> resolution.type
                is DotNetClrTypeResolution.Unresolved ->
                    return DotNetClrArrayRuntimeTypesResolution.Unresolved(
                        kind,
                        resolution,
                    )
            }
            if (!type.definition.isInterface) {
                return DotNetClrArrayRuntimeTypesResolution.Invalid(
                    DotNetClrArrayRuntimeTypeFailure
                        .VECTOR_TYPE_IS_NOT_INTERFACE,
                    type,
                    kind,
                )
            }
            val arity = type.genericArity()
            if (arity != 1) {
                return DotNetClrArrayRuntimeTypesResolution.Invalid(
                    DotNetClrArrayRuntimeTypeFailure
                        .VECTOR_TYPE_GENERIC_ARITY,
                    type,
                    kind,
                    expectedGenericArity = 1,
                    actualGenericArity = arity,
                )
            }
            vectorInterfaces[kind] = type
        }
        return DotNetClrArrayRuntimeTypesResolution.Resolved(
            DotNetClrArrayRuntimeTypes(
                systemArray,
                vectorInterfaces,
            )
        )
    }
}
