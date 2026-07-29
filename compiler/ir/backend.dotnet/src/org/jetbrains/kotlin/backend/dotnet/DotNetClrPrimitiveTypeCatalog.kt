package org.jetbrains.kotlin.backend.dotnet

class DotNetClrPrimitiveTypeCatalog internal constructor(
    definitions: Map<DotNetClrPrimitiveType, DotNetClrResolvedTypeDefinition>,
) {
    private val definitions = definitions.toMap()

    init {
        require(this.definitions.keys == DotNetClrPrimitiveType.entries.toSet()) {
            "A CLR primitive type catalog must contain every primitive type"
        }
    }

    operator fun get(
        primitive: DotNetClrPrimitiveType,
    ): DotNetClrResolvedTypeDefinition =
        definitions.getValue(primitive)
}

sealed interface DotNetClrPrimitiveTypeCatalogResolution {
    data class Resolved(
        val catalog: DotNetClrPrimitiveTypeCatalog,
    ) : DotNetClrPrimitiveTypeCatalogResolution

    data class Unresolved(
        val primitive: DotNetClrPrimitiveType,
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrPrimitiveTypeCatalogResolution
}

/**
 * Resolves compact signature primitive codes to their exact definitions in one selected core
 * assembly graph. The JVM host runtime and display-name matching are never consulted.
 */
class DotNetClrPrimitiveTypeCatalogResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    fun resolve(
        selectedCoreAssembly: DotNetClrAssemblyMetadata,
    ): DotNetClrPrimitiveTypeCatalogResolution {
        val definitions =
            LinkedHashMap<DotNetClrPrimitiveType, DotNetClrResolvedTypeDefinition>()
        for (primitive in DotNetClrPrimitiveType.entries) {
            val definition = when (
                val resolution =
                    typeResolver.resolveTopLevelType(
                        selectedCoreAssembly,
                        "System",
                        primitive.systemTypeMetadataName,
                    )
            ) {
                is DotNetClrTypeResolution.Resolved -> resolution.type
                is DotNetClrTypeResolution.Unresolved ->
                    return DotNetClrPrimitiveTypeCatalogResolution.Unresolved(
                        primitive,
                        resolution,
                    )
            }
            definitions[primitive] = definition
        }
        return DotNetClrPrimitiveTypeCatalogResolution.Resolved(
            DotNetClrPrimitiveTypeCatalog(definitions)
        )
    }
}
