package org.jetbrains.kotlin.load.dotnet

/**
 * Binds a textual AssemblyName edge to an assembly already selected for the target profile.
 *
 * The implementation owns version unification, retargeting, facade selection, and unqualified
 * custom-attribute type-name policy. It must not probe or load an assembly as a side effect.
 */
fun interface DotNetClrSerializedAssemblyBinder {
    fun bind(
        sourceAssembly: DotNetClrAssemblyMetadata,
        unqualifiedContextAssembly: DotNetClrAssemblyMetadata,
        assemblyName: DotNetClrSerializedAssemblyName?,
    ): DotNetClrAssemblyMetadata?
}

sealed interface DotNetClrResolvedSerializedType {
    data class Named(
        val type: DotNetClrResolvedTypeDefinition,
    ) : DotNetClrResolvedSerializedType

    data class GenericInstance(
        val genericType: Named,
        val arguments: List<DotNetClrResolvedSerializedType>,
    ) : DotNetClrResolvedSerializedType

    data class Pointer(
        val elementType: DotNetClrResolvedSerializedType,
    ) : DotNetClrResolvedSerializedType

    data class ByReference(
        val elementType: DotNetClrResolvedSerializedType,
    ) : DotNetClrResolvedSerializedType

    data class SzArray(
        val elementType: DotNetClrResolvedSerializedType,
    ) : DotNetClrResolvedSerializedType

    data class MdArray(
        val elementType: DotNetClrResolvedSerializedType,
        val rank: Int,
    ) : DotNetClrResolvedSerializedType
}

sealed interface DotNetClrSerializedTypeResolution {
    data class Resolved(
        val type: DotNetClrResolvedSerializedType,
    ) : DotNetClrSerializedTypeResolution

    data class InvalidTypeName(
        val parsing: DotNetClrSerializedTypeNameParsing.Invalid,
    ) : DotNetClrSerializedTypeResolution

    data class UnsupportedTypeName(
        val parsing: DotNetClrSerializedTypeNameParsing.Unsupported,
    ) : DotNetClrSerializedTypeResolution

    data class InvalidAssemblyName(
        val displayName: String,
        val parsing: DotNetClrSerializedAssemblyNameParsing.Invalid,
        val genericArgumentPath: List<Int>,
    ) : DotNetClrSerializedTypeResolution

    data class UnboundAssembly(
        val assemblyName: DotNetClrSerializedAssemblyName?,
        val genericArgumentPath: List<Int>,
    ) : DotNetClrSerializedTypeResolution

    data class UnresolvedNamedType(
        val resolution: DotNetClrTypeResolution.Unresolved,
        val genericArgumentPath: List<Int>,
    ) : DotNetClrSerializedTypeResolution

    data class GenericArityMismatch(
        val type: DotNetClrResolvedTypeDefinition,
        val encodedArity: Int,
        val metadataParameterCount: Int,
        val genericArgumentPath: List<Int>,
    ) : DotNetClrSerializedTypeResolution
}

/**
 * Resolves parsed reflection type names only through a selected target-profile assembly graph.
 */
class DotNetClrSerializedTypeResolver(
    private val typeResolver: DotNetClrTypeResolver,
    private val assemblyBinder: DotNetClrSerializedAssemblyBinder,
) {
    fun resolve(
        sourceAssembly: DotNetClrAssemblyMetadata,
        serializedName: String,
    ): DotNetClrSerializedTypeResolution {
        val parsed = when (
            val parsing = DotNetClrSerializedTypeNameParser.parse(serializedName)
        ) {
            is DotNetClrSerializedTypeNameParsing.Parsed -> parsing.type
            is DotNetClrSerializedTypeNameParsing.Invalid ->
                return DotNetClrSerializedTypeResolution.InvalidTypeName(parsing)

            is DotNetClrSerializedTypeNameParsing.Unsupported ->
                return DotNetClrSerializedTypeResolution.UnsupportedTypeName(parsing)
        }
        return resolve(
            sourceAssembly,
            unqualifiedContextAssembly = sourceAssembly,
            serializedName = parsed,
            genericArgumentPath = emptyList(),
        )
    }

    fun resolve(
        sourceAssembly: DotNetClrAssemblyMetadata,
        serializedName: DotNetClrSerializedTypeName,
    ): DotNetClrSerializedTypeResolution =
        resolve(
            sourceAssembly,
            unqualifiedContextAssembly = sourceAssembly,
            serializedName = serializedName,
            genericArgumentPath = emptyList(),
        )

    private fun resolve(
        sourceAssembly: DotNetClrAssemblyMetadata,
        unqualifiedContextAssembly: DotNetClrAssemblyMetadata,
        serializedName: DotNetClrSerializedTypeName,
        genericArgumentPath: List<Int>,
    ): DotNetClrSerializedTypeResolution {
        val parsedAssemblyName = serializedName.assemblyDisplayName?.let { displayName ->
            when (val parsing = DotNetClrSerializedAssemblyNameParser.parse(displayName)) {
                is DotNetClrSerializedAssemblyNameParsing.Parsed -> parsing.assemblyName
                is DotNetClrSerializedAssemblyNameParsing.Invalid ->
                    return DotNetClrSerializedTypeResolution.InvalidAssemblyName(
                        displayName,
                        parsing,
                        genericArgumentPath,
                    )
            }
        }
        val selectedAssembly = assemblyBinder.bind(
            sourceAssembly,
            unqualifiedContextAssembly,
            parsedAssemblyName,
        )
            ?: return DotNetClrSerializedTypeResolution.UnboundAssembly(
                parsedAssemblyName,
                genericArgumentPath,
            )

        var namedType = when (
            val topLevel = typeResolver.resolveTopLevelType(
                selectedAssembly,
                serializedName.namedType.namespaceName,
                serializedName.namedType.topLevelType.metadataName,
            )
        ) {
            is DotNetClrTypeResolution.Resolved -> topLevel.type
            is DotNetClrTypeResolution.Unresolved ->
                return DotNetClrSerializedTypeResolution.UnresolvedNamedType(
                    topLevel,
                    genericArgumentPath,
                )
        }
        for (nestedPart in serializedName.namedType.nestedTypes) {
            namedType = when (
                val nested = typeResolver.resolveNestedType(
                    namedType,
                    nestedPart.metadataName,
                )
            ) {
                is DotNetClrTypeResolution.Resolved -> nested.type
                is DotNetClrTypeResolution.Unresolved ->
                    return DotNetClrSerializedTypeResolution.UnresolvedNamedType(
                        nested,
                        genericArgumentPath,
                    )
            }
        }

        val metadataParameterCount =
            namedType.assembly.genericParameterDefinitions.count { parameter ->
                parameter.owner == namedType.definition.handle
            }
        val encodedArity = serializedName.namedType.totalGenericArity
        if (encodedArity != metadataParameterCount) {
            return DotNetClrSerializedTypeResolution.GenericArityMismatch(
                namedType,
                encodedArity,
                metadataParameterCount,
                genericArgumentPath,
            )
        }

        val resolvedArguments =
            ArrayList<DotNetClrResolvedSerializedType>(serializedName.genericArguments.size)
        serializedName.genericArguments.forEachIndexed { index, argument ->
            when (
                val resolution = resolve(
                    sourceAssembly = sourceAssembly,
                    unqualifiedContextAssembly = selectedAssembly,
                    serializedName = argument,
                    genericArgumentPath = genericArgumentPath + index,
                )
            ) {
                is DotNetClrSerializedTypeResolution.Resolved ->
                    resolvedArguments += resolution.type

                else -> return resolution
            }
        }

        var resolvedType: DotNetClrResolvedSerializedType =
            if (resolvedArguments.isEmpty()) {
                DotNetClrResolvedSerializedType.Named(namedType)
            } else {
                DotNetClrResolvedSerializedType.GenericInstance(
                    DotNetClrResolvedSerializedType.Named(namedType),
                    resolvedArguments.toList(),
                )
            }
        for (modifier in serializedName.modifiers) {
            resolvedType = when (modifier) {
                DotNetClrSerializedTypeModifier.Pointer ->
                    DotNetClrResolvedSerializedType.Pointer(resolvedType)

                DotNetClrSerializedTypeModifier.ByReference ->
                    DotNetClrResolvedSerializedType.ByReference(resolvedType)

                DotNetClrSerializedTypeModifier.SzArray ->
                    DotNetClrResolvedSerializedType.SzArray(resolvedType)

                is DotNetClrSerializedTypeModifier.MdArray ->
                    DotNetClrResolvedSerializedType.MdArray(resolvedType, modifier.rank)
            }
        }
        return DotNetClrSerializedTypeResolution.Resolved(resolvedType)
    }
}
