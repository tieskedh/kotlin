package org.jetbrains.kotlin.backend.dotnet

/**
 * Supplies an already selected assembly edge.
 *
 * The CLR importer deliberately does not implement assembly binding here. The build frontend owns
 * framework/reference-pack selection and passes the exact target for each AssemblyRef row.
 * Implementations must return the same [DotNetClrAssemblyMetadata] instance for the same selected
 * assembly during one resolution operation.
 */
fun interface DotNetClrAssemblyReferenceBinder {
    fun bind(
        sourceAssembly: DotNetClrAssemblyMetadata,
        reference: DotNetClrAssemblyReference,
    ): DotNetClrAssemblyMetadata?
}

class DotNetClrResolvedTypeDefinition(
    val assembly: DotNetClrAssemblyMetadata,
    val definition: DotNetClrTypeDefinition,
) {
    fun hasSameIdentityAs(other: DotNetClrResolvedTypeDefinition): Boolean =
        assembly === other.assembly && definition.handle == other.definition.handle

    override fun equals(other: Any?): Boolean =
        other is DotNetClrResolvedTypeDefinition && hasSameIdentityAs(other)

    override fun hashCode(): Int =
        31 * System.identityHashCode(assembly) + definition.handle.hashCode()
}

enum class DotNetClrTypeResolutionFailure {
    INVALID_HANDLE,
    UNBOUND_ASSEMBLY_REFERENCE,
    TYPE_NOT_FOUND,
    AMBIGUOUS_TYPE,
    UNSUPPORTED_MULTI_MODULE_REFERENCE,
    TYPE_RESOLUTION_CYCLE,
    RESOLUTION_LIMIT_EXCEEDED,
    NON_NOMINAL_TYPE_SPECIFICATION,
}

sealed interface DotNetClrTypeResolution {
    data class Resolved(
        val type: DotNetClrResolvedTypeDefinition,
    ) : DotNetClrTypeResolution

    data class Unresolved(
        val failure: DotNetClrTypeResolutionFailure,
        val assemblyIdentity: DotNetManagedAssemblyIdentity,
        val handle: DotNetClrMetadataHandle? = null,
        val namespaceName: String? = null,
        val metadataName: String? = null,
    ) : DotNetClrTypeResolution
}

sealed interface DotNetClrTypeHierarchyResolution {
    data object Matches : DotNetClrTypeHierarchyResolution

    data object DoesNotMatch : DotNetClrTypeHierarchyResolution

    data class Unresolved(
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrTypeHierarchyResolution

    data object InheritanceCycle : DotNetClrTypeHierarchyResolution

    data object ResolutionLimitExceeded : DotNetClrTypeHierarchyResolution
}

enum class DotNetClrEnumStorageFailure {
    INVALID_BASE_TYPE_SHAPE,
    ENUM_IS_INTERFACE,
    ENUM_IS_NOT_SEALED,
    INVALID_INSTANCE_FIELD,
    INVALID_STORAGE_TYPE,
}

sealed interface DotNetClrEnumStorageResolution {
    data class Resolved(
        val storageType: DotNetClrPrimitiveType,
        val storageField: DotNetClrFieldDefinition,
    ) : DotNetClrEnumStorageResolution

    data object NotEnum : DotNetClrEnumStorageResolution

    data class UnresolvedBaseType(
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrEnumStorageResolution

    data class Invalid(
        val failure: DotNetClrEnumStorageFailure,
    ) : DotNetClrEnumStorageResolution
}

/**
 * Resolves physical CLR type identity without applying Kotlin or C# import policy.
 *
 * Resolution is cycle-safe and bounded. TypeRef scopes and ExportedType forwarders are followed,
 * but File/ModuleRef multi-module edges remain explicit unsupported results until the importer has
 * a selected module graph. Accessibility, nullability, variance, and profile legality are later
 * layers.
 */
class DotNetClrTypeResolver(
    private val assemblyReferenceBinder: DotNetClrAssemblyReferenceBinder,
    private val resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    init {
        require(resolutionLimit in 1..MAX_RESOLUTION_LIMIT) {
            "CLR type resolution limit must be in 1..$MAX_RESOLUTION_LIMIT"
        }
    }

    fun resolveTypeDefinition(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
    ): DotNetClrTypeResolution =
        resolveHandle(assembly, handle, ResolutionContext())

    fun resolveTopLevelType(
        assembly: DotNetClrAssemblyMetadata,
        namespaceName: String,
        metadataName: String,
    ): DotNetClrTypeResolution =
        resolveTopLevelType(
            assembly = assembly,
            namespaceName = namespaceName,
            metadataName = metadataName,
            includeDefinitions = true,
            context = ResolutionContext(),
        )

    fun resolveEnumStorage(
        type: DotNetClrResolvedTypeDefinition,
        systemEnum: DotNetClrResolvedTypeDefinition,
    ): DotNetClrEnumStorageResolution {
        val baseHandle = type.definition.baseType ?: return DotNetClrEnumStorageResolution.NotEnum
        val baseType = when (val resolution = resolveTypeDefinition(type.assembly, baseHandle)) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved ->
                return DotNetClrEnumStorageResolution.UnresolvedBaseType(resolution)
        }
        if (!baseType.hasSameIdentityAs(systemEnum)) return DotNetClrEnumStorageResolution.NotEnum
        if (baseHandle.table != TYPE_DEF_TABLE && baseHandle.table != TYPE_REF_TABLE) {
            return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.INVALID_BASE_TYPE_SHAPE
            )
        }
        if (type.definition.isInterface) {
            return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.ENUM_IS_INTERFACE
            )
        }
        if (!type.definition.isSealed) {
            return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.ENUM_IS_NOT_SEALED
            )
        }
        val instanceFields = type.assembly.fieldDefinitions.filter { field ->
            field.declaringType == type.definition.handle && !field.isStatic
        }
        val storageField = instanceFields.singleOrNull()
            ?: return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.INVALID_INSTANCE_FIELD
            )
        if (storageField.name != "value__" ||
            !storageField.isSpecialName ||
            !storageField.isRuntimeSpecialName
        ) {
            return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.INVALID_INSTANCE_FIELD
            )
        }
        val storageType =
            (storageField.signature.fieldType as? DotNetClrTypeSignature.Primitive)?.type
        if (storageType !in ENUM_STORAGE_TYPES) {
            return DotNetClrEnumStorageResolution.Invalid(
                DotNetClrEnumStorageFailure.INVALID_STORAGE_TYPE
            )
        }
        return DotNetClrEnumStorageResolution.Resolved(
            storageType = checkNotNull(storageType),
            storageField = storageField,
        )
    }

    fun isSameOrDerivedFrom(
        type: DotNetClrResolvedTypeDefinition,
        expectedBaseType: DotNetClrResolvedTypeDefinition,
    ): DotNetClrTypeHierarchyResolution {
        var current = type
        val visited = mutableSetOf<ResolvedTypeKey>()
        repeat(resolutionLimit) {
            if (current.hasSameIdentityAs(expectedBaseType)) {
                return DotNetClrTypeHierarchyResolution.Matches
            }
            if (!visited.add(ResolvedTypeKey(current))) {
                return DotNetClrTypeHierarchyResolution.InheritanceCycle
            }
            val baseHandle =
                current.definition.baseType ?: return DotNetClrTypeHierarchyResolution.DoesNotMatch
            current = when (val resolution = resolveTypeDefinition(current.assembly, baseHandle)) {
                is DotNetClrTypeResolution.Resolved -> resolution.type
                is DotNetClrTypeResolution.Unresolved ->
                    return DotNetClrTypeHierarchyResolution.Unresolved(resolution)
            }
        }
        return DotNetClrTypeHierarchyResolution.ResolutionLimitExceeded
    }

    private fun resolveHandle(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
        context: ResolutionContext,
    ): DotNetClrTypeResolution =
        context.withKey(ResolutionKey.handle(assembly, handle)) {
            when (handle.table) {
                TYPE_DEF_TABLE -> {
                    val definitions = assembly.typeDefinitions.filter { definition ->
                        definition.handle == handle
                    }
                    when (definitions.size) {
                        1 -> resolved(assembly, definitions.single())
                        0 -> unresolved(
                            DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                            assembly,
                            handle,
                        )

                        else -> unresolved(
                            DotNetClrTypeResolutionFailure.AMBIGUOUS_TYPE,
                            assembly,
                            handle,
                        )
                    }
                }

                TYPE_REF_TABLE -> resolveTypeReference(assembly, handle, context)
                TYPE_SPEC_TABLE -> resolveTypeSpecification(assembly, handle, context)
                else -> unresolved(
                    DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                    assembly,
                    handle,
                )
            }
        }

    private fun resolveTypeReference(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
        context: ResolutionContext,
    ): DotNetClrTypeResolution {
        val reference = assembly.typeReferences.singleOrNull { candidate ->
            candidate.handle == handle
        } ?: return unresolved(
            DotNetClrTypeResolutionFailure.INVALID_HANDLE,
            assembly,
            handle,
        )
        val scope = reference.resolutionScope
        return when (scope?.table) {
            null -> resolveTopLevelType(
                assembly = assembly,
                namespaceName = reference.namespaceName,
                metadataName = reference.metadataName,
                includeDefinitions = false,
                context = context,
            )

            MODULE_TABLE -> resolveTopLevelType(
                assembly = assembly,
                namespaceName = reference.namespaceName,
                metadataName = reference.metadataName,
                includeDefinitions = true,
                context = context,
            )

            MODULE_REF_TABLE -> unresolved(
                DotNetClrTypeResolutionFailure.UNSUPPORTED_MULTI_MODULE_REFERENCE,
                assembly,
                scope,
                reference.namespaceName,
                reference.metadataName,
            )

            ASSEMBLY_REF_TABLE -> {
                val assemblyReference = assembly.assemblyReferences.singleOrNull { candidate ->
                    candidate.handle == scope
                } ?: return unresolved(
                    DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                    assembly,
                    scope,
                    reference.namespaceName,
                    reference.metadataName,
                )
                val target = assemblyReferenceBinder.bind(assembly, assemblyReference)
                    ?: return unresolved(
                        DotNetClrTypeResolutionFailure.UNBOUND_ASSEMBLY_REFERENCE,
                        assembly,
                        scope,
                        reference.namespaceName,
                        reference.metadataName,
                    )
                resolveTopLevelType(
                    assembly = target,
                    namespaceName = reference.namespaceName,
                    metadataName = reference.metadataName,
                    includeDefinitions = true,
                    context = context,
                )
            }

            TYPE_REF_TABLE -> {
                val enclosing = when (val result = resolveHandle(assembly, scope, context)) {
                    is DotNetClrTypeResolution.Resolved -> result.type
                    is DotNetClrTypeResolution.Unresolved -> return result
                }
                resolveNestedType(
                    enclosing = enclosing,
                    metadataName = reference.metadataName,
                    context = context,
                )
            }

            else -> unresolved(
                DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                assembly,
                scope,
                reference.namespaceName,
                reference.metadataName,
            )
        }
    }

    private fun resolveTypeSpecification(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
        context: ResolutionContext,
    ): DotNetClrTypeResolution {
        val specification = assembly.typeSpecifications.singleOrNull { candidate ->
            candidate.handle == handle
        } ?: return unresolved(
            DotNetClrTypeResolutionFailure.INVALID_HANDLE,
            assembly,
            handle,
        )
        return resolveNominalSignature(assembly, specification.signature, handle, context)
    }

    private fun resolveNominalSignature(
        assembly: DotNetClrAssemblyMetadata,
        signature: DotNetClrTypeSignature,
        sourceHandle: DotNetClrMetadataHandle,
        context: ResolutionContext,
    ): DotNetClrTypeResolution =
        when (signature) {
            is DotNetClrTypeSignature.Named ->
                resolveHandle(assembly, signature.type, context)

            is DotNetClrTypeSignature.GenericInstance ->
                resolveHandle(assembly, signature.genericType.type, context)

            is DotNetClrTypeSignature.Modified ->
                resolveNominalSignature(
                    assembly,
                    signature.unmodifiedType,
                    sourceHandle,
                    context,
                )

            else -> unresolved(
                DotNetClrTypeResolutionFailure.NON_NOMINAL_TYPE_SPECIFICATION,
                assembly,
                sourceHandle,
            )
        }

    private fun resolveTopLevelType(
        assembly: DotNetClrAssemblyMetadata,
        namespaceName: String,
        metadataName: String,
        includeDefinitions: Boolean,
        context: ResolutionContext,
    ): DotNetClrTypeResolution =
        context.withKey(
            ResolutionKey.topLevel(
                assembly,
                namespaceName,
                metadataName,
                includeDefinitions,
            )
        ) {
            val definitions = if (includeDefinitions) {
                assembly.typeDefinitions.filter { definition ->
                    definition.declaringType == null &&
                            definition.namespaceName == namespaceName &&
                            definition.metadataName == metadataName
                }
            } else {
                emptyList()
            }
            val exported = assembly.exportedTypes.filter { candidate ->
                candidate.implementation.table != EXPORTED_TYPE_TABLE &&
                        candidate.namespaceName == namespaceName &&
                        candidate.metadataName == metadataName
            }
            when {
                definitions.size + exported.size > 1 ->
                    unresolved(
                        DotNetClrTypeResolutionFailure.AMBIGUOUS_TYPE,
                        assembly,
                        namespaceName = namespaceName,
                        metadataName = metadataName,
                    )

                definitions.size == 1 -> resolved(assembly, definitions.single())
                exported.size == 1 -> resolveExportedType(assembly, exported.single(), context)
                else -> unresolved(
                    DotNetClrTypeResolutionFailure.TYPE_NOT_FOUND,
                    assembly,
                    namespaceName = namespaceName,
                    metadataName = metadataName,
                )
            }
        }

    private fun resolveExportedType(
        assembly: DotNetClrAssemblyMetadata,
        exportedType: DotNetClrExportedType,
        context: ResolutionContext,
    ): DotNetClrTypeResolution =
        context.withKey(ResolutionKey.handle(assembly, exportedType.handle)) {
            when (exportedType.implementation.table) {
                ASSEMBLY_REF_TABLE -> {
                    val reference = assembly.assemblyReferences.singleOrNull { candidate ->
                        candidate.handle == exportedType.implementation
                    } ?: return@withKey unresolved(
                        DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                        assembly,
                        exportedType.implementation,
                        exportedType.namespaceName,
                        exportedType.metadataName,
                    )
                    val target = assemblyReferenceBinder.bind(assembly, reference)
                        ?: return@withKey unresolved(
                            DotNetClrTypeResolutionFailure.UNBOUND_ASSEMBLY_REFERENCE,
                            assembly,
                            exportedType.implementation,
                            exportedType.namespaceName,
                            exportedType.metadataName,
                        )
                    resolveTopLevelType(
                        assembly = target,
                        namespaceName = exportedType.namespaceName,
                        metadataName = exportedType.metadataName,
                        includeDefinitions = true,
                        context = context,
                    )
                }

                EXPORTED_TYPE_TABLE -> {
                    val enclosingExportedType =
                        assembly.exportedTypes.singleOrNull { candidate ->
                            candidate.handle == exportedType.implementation
                        } ?: return@withKey unresolved(
                            DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                            assembly,
                            exportedType.implementation,
                            exportedType.namespaceName,
                            exportedType.metadataName,
                        )
                    val enclosing =
                        when (val result = resolveExportedType(assembly, enclosingExportedType, context)) {
                            is DotNetClrTypeResolution.Resolved -> result.type
                            is DotNetClrTypeResolution.Unresolved -> return@withKey result
                        }
                    resolveNestedType(enclosing, exportedType.metadataName, context)
                }

                FILE_TABLE -> unresolved(
                    DotNetClrTypeResolutionFailure.UNSUPPORTED_MULTI_MODULE_REFERENCE,
                    assembly,
                    exportedType.implementation,
                    exportedType.namespaceName,
                    exportedType.metadataName,
                )

                else -> unresolved(
                    DotNetClrTypeResolutionFailure.INVALID_HANDLE,
                    assembly,
                    exportedType.implementation,
                    exportedType.namespaceName,
                    exportedType.metadataName,
                )
            }
        }

    private fun resolveNestedType(
        enclosing: DotNetClrResolvedTypeDefinition,
        metadataName: String,
        context: ResolutionContext,
    ): DotNetClrTypeResolution =
        context.withKey(
            ResolutionKey.nested(
                enclosing.assembly,
                enclosing.definition.handle,
                metadataName,
            )
        ) {
            val definitions = enclosing.assembly.typeDefinitions.filter { definition ->
                definition.declaringType == enclosing.definition.handle &&
                        definition.metadataName == metadataName
            }
            when (definitions.size) {
                1 -> resolved(enclosing.assembly, definitions.single())
                0 -> unresolved(
                    DotNetClrTypeResolutionFailure.TYPE_NOT_FOUND,
                    enclosing.assembly,
                    enclosing.definition.handle,
                    metadataName = metadataName,
                )

                else -> unresolved(
                    DotNetClrTypeResolutionFailure.AMBIGUOUS_TYPE,
                    enclosing.assembly,
                    enclosing.definition.handle,
                    metadataName = metadataName,
                )
            }
        }

    private fun resolved(
        assembly: DotNetClrAssemblyMetadata,
        definition: DotNetClrTypeDefinition,
    ): DotNetClrTypeResolution.Resolved =
        DotNetClrTypeResolution.Resolved(
            DotNetClrResolvedTypeDefinition(assembly, definition)
        )

    private fun unresolved(
        failure: DotNetClrTypeResolutionFailure,
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle? = null,
        namespaceName: String? = null,
        metadataName: String? = null,
    ): DotNetClrTypeResolution.Unresolved =
        DotNetClrTypeResolution.Unresolved(
            failure = failure,
            assemblyIdentity = assembly.identity,
            handle = handle,
            namespaceName = namespaceName,
            metadataName = metadataName,
        )

    private inner class ResolutionContext {
        private val active = mutableSetOf<ResolutionKey>()
        private var steps = 0

        fun withKey(
            key: ResolutionKey,
            action: () -> DotNetClrTypeResolution,
        ): DotNetClrTypeResolution {
            if (steps++ >= resolutionLimit) {
                return unresolved(
                    DotNetClrTypeResolutionFailure.RESOLUTION_LIMIT_EXCEEDED,
                    key.assembly,
                    key.handle,
                    key.namespaceName,
                    key.metadataName,
                )
            }
            if (!active.add(key)) {
                return unresolved(
                    DotNetClrTypeResolutionFailure.TYPE_RESOLUTION_CYCLE,
                    key.assembly,
                    key.handle,
                    key.namespaceName,
                    key.metadataName,
                )
            }
            return try {
                action()
            } finally {
                active.remove(key)
            }
        }
    }

    private class ResolutionKey(
        val assembly: DotNetClrAssemblyMetadata,
        val kind: Int,
        val handle: DotNetClrMetadataHandle?,
        val namespaceName: String?,
        val metadataName: String?,
        val includeDefinitions: Boolean,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ResolutionKey &&
                    assembly === other.assembly &&
                    kind == other.kind &&
                    handle == other.handle &&
                    namespaceName == other.namespaceName &&
                    metadataName == other.metadataName &&
                    includeDefinitions == other.includeDefinitions

        override fun hashCode(): Int {
            var result = System.identityHashCode(assembly)
            result = 31 * result + kind
            result = 31 * result + (handle?.hashCode() ?: 0)
            result = 31 * result + (namespaceName?.hashCode() ?: 0)
            result = 31 * result + (metadataName?.hashCode() ?: 0)
            result = 31 * result + includeDefinitions.hashCode()
            return result
        }

        companion object {
            fun handle(
                assembly: DotNetClrAssemblyMetadata,
                handle: DotNetClrMetadataHandle,
            ): ResolutionKey =
                ResolutionKey(assembly, HANDLE_KIND, handle, null, null, false)

            fun topLevel(
                assembly: DotNetClrAssemblyMetadata,
                namespaceName: String,
                metadataName: String,
                includeDefinitions: Boolean,
            ): ResolutionKey =
                ResolutionKey(
                    assembly,
                    TOP_LEVEL_KIND,
                    null,
                    namespaceName,
                    metadataName,
                    includeDefinitions,
                )

            fun nested(
                assembly: DotNetClrAssemblyMetadata,
                enclosingType: DotNetClrMetadataHandle,
                metadataName: String,
            ): ResolutionKey =
                ResolutionKey(
                    assembly,
                    NESTED_KIND,
                    enclosingType,
                    null,
                    metadataName,
                    false,
                )

            private const val HANDLE_KIND = 0
            private const val TOP_LEVEL_KIND = 1
            private const val NESTED_KIND = 2
        }
    }

    private class ResolvedTypeKey(
        private val type: DotNetClrResolvedTypeDefinition,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ResolvedTypeKey &&
                    type.assembly === other.type.assembly &&
                    type.definition.handle == other.type.definition.handle

        override fun hashCode(): Int =
            31 * System.identityHashCode(type.assembly) + type.definition.handle.hashCode()
    }

    private companion object {
        const val MODULE_TABLE = 0
        const val TYPE_REF_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val MODULE_REF_TABLE = 26
        const val ASSEMBLY_REF_TABLE = 35
        const val FILE_TABLE = 38
        const val EXPORTED_TYPE_TABLE = 39
        const val TYPE_SPEC_TABLE = 27
        const val DEFAULT_RESOLUTION_LIMIT = 256
        const val MAX_RESOLUTION_LIMIT = 4096

        val ENUM_STORAGE_TYPES = setOf(
            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.UINT8,
            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.UINT16,
            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.UINT32,
            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.UINT64,
        )
    }
}
