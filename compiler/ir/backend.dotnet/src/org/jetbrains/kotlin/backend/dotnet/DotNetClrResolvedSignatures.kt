package org.jetbrains.kotlin.backend.dotnet

/**
 * A CLR signature whose nominal handles have been resolved through the selected assembly graph.
 *
 * Raw [DotNetClrTypeSignature] nodes are meaningful only together with the PE image that owns
 * their TypeDefOrRef handles. This representation makes that context explicit at every nominal
 * node, so a substituted signature can safely combine types originating in different assemblies.
 */
sealed interface DotNetClrResolvedTypeSignature {
    data object Void : DotNetClrResolvedTypeSignature

    data object TypedReference : DotNetClrResolvedTypeSignature

    data class Primitive(
        val type: DotNetClrPrimitiveType,
    ) : DotNetClrResolvedTypeSignature

    data class Named(
        val type: DotNetClrResolvedTypeDefinition,
        val isValueType: Boolean,
    ) : DotNetClrResolvedTypeSignature

    data class GenericParameter(
        val kind: DotNetClrGenericParameterKind,
        val index: Int,
    ) : DotNetClrResolvedTypeSignature

    data class Pointer(
        val elementType: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedTypeSignature

    data class ByReference(
        val elementType: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedTypeSignature

    data class SzArray(
        val elementType: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedTypeSignature

    data class Array(
        val elementType: DotNetClrResolvedTypeSignature,
        val shape: DotNetClrArrayShape,
    ) : DotNetClrResolvedTypeSignature

    data class GenericInstance(
        val genericType: Named,
        val arguments: List<DotNetClrResolvedTypeSignature>,
    ) : DotNetClrResolvedTypeSignature

    data class FunctionPointer(
        val signature: DotNetClrResolvedMethodSignature,
    ) : DotNetClrResolvedTypeSignature

    data class Modified(
        val modifiers: List<DotNetClrResolvedCustomModifier>,
        val unmodifiedType: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedTypeSignature
}

data class DotNetClrResolvedCustomModifier(
    val isRequired: Boolean,
    val modifierType: DotNetClrResolvedTypeDefinition,
)

data class DotNetClrResolvedMethodSignature(
    val callingConvention: DotNetClrSignatureCallingConvention,
    val hasThis: Boolean,
    val hasExplicitThis: Boolean,
    val genericParameterCount: Int,
    val returnType: DotNetClrResolvedTypeSignature,
    val parameterTypes: List<DotNetClrResolvedTypeSignature>,
    val varargParameterStart: Int?,
)

data class DotNetClrResolvedTypeView(
    val type: DotNetClrResolvedTypeDefinition,
    val arguments: List<DotNetClrResolvedTypeSignature>,
)

enum class DotNetClrResolvedSignatureFailure {
    GENERIC_ARITY_MISMATCH,
}

sealed interface DotNetClrResolvedSignatureResolution {
    data class Resolved(
        val signature: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedSignatureResolution

    data class UnresolvedType(
        val resolution: DotNetClrTypeResolution.Unresolved,
    ) : DotNetClrResolvedSignatureResolution

    data class Invalid(
        val failure: DotNetClrResolvedSignatureFailure,
        val type: DotNetClrResolvedTypeDefinition,
        val expectedGenericArity: Int,
        val actualGenericArity: Int,
    ) : DotNetClrResolvedSignatureResolution
}

/**
 * Resolves physical signature identity without applying Kotlin or C# type policy.
 *
 * This is deliberately separate from [DotNetClrTypeResolver.resolveTypeDefinition], whose result
 * identifies only the nominal TypeDef. Importer consumers which compare or substitute signatures
 * must retain the complete resolved tree rather than discarding generic arguments.
 */
class DotNetClrSignatureResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        signature: DotNetClrTypeSignature,
    ): DotNetClrResolvedSignatureResolution =
        try {
            DotNetClrResolvedSignatureResolution.Resolved(
                resolveType(assembly, signature)
            )
        } catch (failure: UnresolvedSignatureType) {
            DotNetClrResolvedSignatureResolution.UnresolvedType(failure.resolution)
        } catch (failure: InvalidGenericArity) {
            DotNetClrResolvedSignatureResolution.Invalid(
                DotNetClrResolvedSignatureFailure.GENERIC_ARITY_MISMATCH,
                failure.type,
                failure.expected,
                failure.actual,
            )
        }

    private fun resolveType(
        assembly: DotNetClrAssemblyMetadata,
        signature: DotNetClrTypeSignature,
    ): DotNetClrResolvedTypeSignature =
        when (signature) {
            DotNetClrTypeSignature.Void -> DotNetClrResolvedTypeSignature.Void
            DotNetClrTypeSignature.TypedReference ->
                DotNetClrResolvedTypeSignature.TypedReference

            is DotNetClrTypeSignature.Primitive ->
                DotNetClrResolvedTypeSignature.Primitive(signature.type)

            is DotNetClrTypeSignature.Named ->
                resolveNamed(assembly, signature)

            is DotNetClrTypeSignature.GenericParameter ->
                DotNetClrResolvedTypeSignature.GenericParameter(
                    signature.kind,
                    signature.index,
                )

            is DotNetClrTypeSignature.Pointer ->
                DotNetClrResolvedTypeSignature.Pointer(
                    resolveType(assembly, signature.elementType)
                )

            is DotNetClrTypeSignature.ByReference ->
                DotNetClrResolvedTypeSignature.ByReference(
                    resolveType(assembly, signature.elementType)
                )

            is DotNetClrTypeSignature.SzArray ->
                DotNetClrResolvedTypeSignature.SzArray(
                    resolveType(assembly, signature.elementType)
                )

            is DotNetClrTypeSignature.Array ->
                DotNetClrResolvedTypeSignature.Array(
                    resolveType(assembly, signature.elementType),
                    signature.shape,
                )

            is DotNetClrTypeSignature.GenericInstance -> {
                val genericType = resolveNamed(assembly, signature.genericType)
                val expectedArity = genericType.type.assembly.genericParameterDefinitions.count {
                    parameter -> parameter.owner == genericType.type.definition.handle
                }
                if (signature.arguments.size != expectedArity) {
                    throw InvalidGenericArity(
                        genericType.type,
                        expectedArity,
                        signature.arguments.size,
                    )
                }
                DotNetClrResolvedTypeSignature.GenericInstance(
                    genericType,
                    signature.arguments.map { argument ->
                        resolveType(assembly, argument)
                    },
                )
            }

            is DotNetClrTypeSignature.FunctionPointer ->
                DotNetClrResolvedTypeSignature.FunctionPointer(
                    resolveMethod(assembly, signature.signature)
                )

            is DotNetClrTypeSignature.Modified ->
                DotNetClrResolvedTypeSignature.Modified(
                    signature.modifiers.map { modifier ->
                        val modifierType = when (
                            val resolution =
                                typeResolver.resolveTypeDefinition(
                                    assembly,
                                    modifier.modifierType,
                                )
                        ) {
                            is DotNetClrTypeResolution.Resolved -> resolution.type
                            is DotNetClrTypeResolution.Unresolved ->
                                throw UnresolvedSignatureType(resolution)
                        }
                        DotNetClrResolvedCustomModifier(
                            modifier.isRequired,
                            modifierType,
                        )
                    },
                    resolveType(assembly, signature.unmodifiedType),
                )
        }

    private fun resolveNamed(
        assembly: DotNetClrAssemblyMetadata,
        signature: DotNetClrTypeSignature.Named,
    ): DotNetClrResolvedTypeSignature.Named {
        val type = when (
            val resolution = typeResolver.resolveTypeDefinition(assembly, signature.type)
        ) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved ->
                throw UnresolvedSignatureType(resolution)
        }
        return DotNetClrResolvedTypeSignature.Named(type, signature.isValueType)
    }

    private fun resolveMethod(
        assembly: DotNetClrAssemblyMetadata,
        signature: DotNetClrMethodSignature,
    ): DotNetClrResolvedMethodSignature =
        DotNetClrResolvedMethodSignature(
            callingConvention = signature.callingConvention,
            hasThis = signature.hasThis,
            hasExplicitThis = signature.hasExplicitThis,
            genericParameterCount = signature.genericParameterCount,
            returnType = resolveType(assembly, signature.returnType),
            parameterTypes = signature.parameterTypes.map { parameter ->
                resolveType(assembly, parameter)
            },
            varargParameterStart = signature.varargParameterStart,
        )
}

private class UnresolvedSignatureType(
    val resolution: DotNetClrTypeResolution.Unresolved,
) : RuntimeException()

private class InvalidGenericArity(
    val type: DotNetClrResolvedTypeDefinition,
    val expected: Int,
    val actual: Int,
) : RuntimeException()

enum class DotNetClrResolvedSignatureSubstitutionFailure {
    TYPE_ARGUMENT_OUT_OF_RANGE,
}

sealed interface DotNetClrResolvedSignatureSubstitution {
    data class Substituted(
        val signature: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedSignatureSubstitution

    data class Invalid(
        val failure: DotNetClrResolvedSignatureSubstitutionFailure,
        val parameterIndex: Int,
        val argumentCount: Int,
    ) : DotNetClrResolvedSignatureSubstitution
}

/**
 * Substitutes an owner TypeSpec's reified arguments through a resolved physical signature.
 *
 * Method generic parameters remain untouched. A later constructed-method resolver can compose a
 * method-argument substitution without changing the assembly identity retained by nominal nodes.
 */
fun DotNetClrResolvedTypeSignature.substituteClrTypeArguments(
    typeArguments: List<DotNetClrResolvedTypeSignature>,
): DotNetClrResolvedSignatureSubstitution =
    try {
        DotNetClrResolvedSignatureSubstitution.Substituted(
            substituteClrTypeArgumentsUnchecked(typeArguments)
        )
    } catch (failure: MissingClrTypeArgument) {
        DotNetClrResolvedSignatureSubstitution.Invalid(
            DotNetClrResolvedSignatureSubstitutionFailure.TYPE_ARGUMENT_OUT_OF_RANGE,
            failure.index,
            typeArguments.size,
        )
    }

private fun DotNetClrResolvedTypeSignature.substituteClrTypeArgumentsUnchecked(
    typeArguments: List<DotNetClrResolvedTypeSignature>,
): DotNetClrResolvedTypeSignature =
    when (this) {
        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Primitive,
        is DotNetClrResolvedTypeSignature.Named,
        -> this

        is DotNetClrResolvedTypeSignature.GenericParameter ->
            when (kind) {
                DotNetClrGenericParameterKind.TYPE ->
                    typeArguments.getOrNull(index) ?: throw MissingClrTypeArgument(index)

                DotNetClrGenericParameterKind.METHOD -> this
            }

        is DotNetClrResolvedTypeSignature.Pointer ->
            DotNetClrResolvedTypeSignature.Pointer(
                elementType.substituteClrTypeArgumentsUnchecked(typeArguments)
            )

        is DotNetClrResolvedTypeSignature.ByReference ->
            DotNetClrResolvedTypeSignature.ByReference(
                elementType.substituteClrTypeArgumentsUnchecked(typeArguments)
            )

        is DotNetClrResolvedTypeSignature.SzArray ->
            DotNetClrResolvedTypeSignature.SzArray(
                elementType.substituteClrTypeArgumentsUnchecked(typeArguments)
            )

        is DotNetClrResolvedTypeSignature.Array ->
            DotNetClrResolvedTypeSignature.Array(
                elementType.substituteClrTypeArgumentsUnchecked(typeArguments),
                shape,
            )

        is DotNetClrResolvedTypeSignature.GenericInstance ->
            DotNetClrResolvedTypeSignature.GenericInstance(
                genericType,
                arguments.map { argument ->
                    argument.substituteClrTypeArgumentsUnchecked(typeArguments)
                },
            )

        is DotNetClrResolvedTypeSignature.FunctionPointer ->
            DotNetClrResolvedTypeSignature.FunctionPointer(
                signature.copy(
                    returnType =
                        signature.returnType
                            .substituteClrTypeArgumentsUnchecked(typeArguments),
                    parameterTypes = signature.parameterTypes.map { parameter ->
                        parameter.substituteClrTypeArgumentsUnchecked(typeArguments)
                    },
                )
            )

        is DotNetClrResolvedTypeSignature.Modified ->
            DotNetClrResolvedTypeSignature.Modified(
                modifiers,
                unmodifiedType.substituteClrTypeArgumentsUnchecked(typeArguments),
            )
    }

private class MissingClrTypeArgument(
    val index: Int,
) : RuntimeException()

internal val DotNetClrPrimitiveType.systemTypeMetadataName: String
    get() =
        when (this) {
            DotNetClrPrimitiveType.BOOLEAN -> "Boolean"
            DotNetClrPrimitiveType.CHAR -> "Char"
            DotNetClrPrimitiveType.INT8 -> "SByte"
            DotNetClrPrimitiveType.UINT8 -> "Byte"
            DotNetClrPrimitiveType.INT16 -> "Int16"
            DotNetClrPrimitiveType.UINT16 -> "UInt16"
            DotNetClrPrimitiveType.INT32 -> "Int32"
            DotNetClrPrimitiveType.UINT32 -> "UInt32"
            DotNetClrPrimitiveType.INT64 -> "Int64"
            DotNetClrPrimitiveType.UINT64 -> "UInt64"
            DotNetClrPrimitiveType.FLOAT32 -> "Single"
            DotNetClrPrimitiveType.FLOAT64 -> "Double"
            DotNetClrPrimitiveType.STRING -> "String"
            DotNetClrPrimitiveType.NATIVE_INT -> "IntPtr"
            DotNetClrPrimitiveType.NATIVE_UINT -> "UIntPtr"
            DotNetClrPrimitiveType.OBJECT -> "Object"
        }

internal val DotNetClrPrimitiveType.isSystemValueType: Boolean
    get() = this != DotNetClrPrimitiveType.STRING && this != DotNetClrPrimitiveType.OBJECT
