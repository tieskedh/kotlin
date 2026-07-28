package org.jetbrains.kotlin.backend.dotnet

sealed interface DotNetClrResolvedCustomAttributeNamedMember {
    val declaringType: DotNetClrResolvedTypeView
    val effectiveType: DotNetClrResolvedTypeSignature

    data class Field(
        override val declaringType: DotNetClrResolvedTypeView,
        override val effectiveType: DotNetClrResolvedTypeSignature,
        val field: DotNetClrFieldDefinition,
    ) : DotNetClrResolvedCustomAttributeNamedMember

    data class Property(
        override val declaringType: DotNetClrResolvedTypeView,
        override val effectiveType: DotNetClrResolvedTypeSignature,
        val property: DotNetClrPropertyDefinition,
        val getter: DotNetClrMethodDefinition,
        val setter: DotNetClrMethodDefinition,
    ) : DotNetClrResolvedCustomAttributeNamedMember
}

data class DotNetClrValidatedCustomAttributeNamedArgument(
    val argument: DotNetClrCustomAttributeNamedArgument,
    val member: DotNetClrResolvedCustomAttributeNamedMember,
)

enum class DotNetClrCustomAttributeNamedArgumentValidationFailure {
    OPEN_GENERIC_ATTRIBUTE_TYPE,
    INVALID_GENERIC_PARAMETER_NUMBERING,
    TYPE_RESOLUTION_FAILED,
    INVALID_RESOLVED_SIGNATURE,
    GENERIC_SUBSTITUTION_FAILED,
    INVALID_BASE_TYPE,
    INHERITANCE_CYCLE,
    INHERITANCE_LIMIT_EXCEEDED,
    MEMBER_NOT_FOUND,
    AMBIGUOUS_MEMBER,
    MEMBER_TYPE_MISMATCH,
    FIELD_NOT_PUBLIC,
    FIELD_IS_STATIC,
    FIELD_IS_READ_ONLY,
    PROPERTY_IS_STATIC,
    PROPERTY_IS_INDEXED,
    PROPERTY_GETTER_MISSING,
    PROPERTY_SETTER_MISSING,
    PROPERTY_ACCESSOR_AMBIGUOUS,
    PROPERTY_ACCESSOR_INVALID,
    PROPERTY_ACCESSOR_NOT_PUBLIC,
    DUPLICATE_MEMBER,
}

sealed interface DotNetClrCustomAttributeNamedArgumentValidation {
    data class Valid(
        val attribute: DotNetClrDecodedCustomAttribute,
        val namedArguments: List<DotNetClrValidatedCustomAttributeNamedArgument>,
    ) : DotNetClrCustomAttributeNamedArgumentValidation

    data class Invalid(
        val failure: DotNetClrCustomAttributeNamedArgumentValidationFailure,
        val namedArgumentIndex: Int,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ) : DotNetClrCustomAttributeNamedArgumentValidation
}

/**
 * Validates decoded named arguments against the selected CLR attribute-class hierarchy.
 *
 * The encoded argument remains authoritative: validation never replaces its field/property kind,
 * name, declared value type, value, order, or multiplicity with a lookup result. The resolved
 * member is a separate selected-graph fact used by later Kotlin annotation projection.
 */
class DotNetClrCustomAttributeNamedArgumentValidator(
    private val typeResolver: DotNetClrTypeResolver,
    private val coreTypes: DotNetClrCustomAttributeCoreTypes,
    private val inheritanceLimit: Int = DEFAULT_INHERITANCE_LIMIT,
) {
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)

    init {
        require(inheritanceLimit in 1..MAX_INHERITANCE_LIMIT) {
            "CLR attribute inheritance limit must be in 1..$MAX_INHERITANCE_LIMIT"
        }
    }

    fun validate(
        attribute: DotNetClrDecodedCustomAttribute,
    ): DotNetClrCustomAttributeNamedArgumentValidation {
        if (attribute.namedArguments.isEmpty()) {
            return DotNetClrCustomAttributeNamedArgumentValidation.Valid(
                attribute,
                emptyList(),
            )
        }
        val attributeType = attribute.constructor.attributeType
        val attributeParameters = genericParameters(attributeType)
            ?: return invalid(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .INVALID_GENERIC_PARAMETER_NUMBERING,
                0,
            )
        if (attributeParameters.isNotEmpty()) {
            // Constructor resolution does not yet admit a TypeSpec parent, so it cannot carry the
            // closed arguments required to validate a generic attribute declaration.
            return invalid(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .OPEN_GENERIC_ATTRIBUTE_TYPE,
                0,
            )
        }

        val initialView = DotNetClrResolvedTypeView(attributeType, emptyList())
        val validated = ArrayList<DotNetClrValidatedCustomAttributeNamedArgument>(
            attribute.namedArguments.size
        )
        val seenMembers = mutableSetOf<ResolvedMemberKey>()
        attribute.namedArguments.forEachIndexed { index, argument ->
            val member = try {
                resolveMember(initialView, argument)
            } catch (failure: NamedArgumentValidationException) {
                return invalid(
                    failure.failure,
                    index,
                    failure.typeResolution,
                    failure.signatureResolution,
                    failure.signatureSubstitution,
                )
            }
            val memberKey = ResolvedMemberKey(member)
            if (!seenMembers.add(memberKey)) {
                return invalid(
                    DotNetClrCustomAttributeNamedArgumentValidationFailure.DUPLICATE_MEMBER,
                    index,
                )
            }
            validated += DotNetClrValidatedCustomAttributeNamedArgument(argument, member)
        }
        return DotNetClrCustomAttributeNamedArgumentValidation.Valid(
            attribute,
            validated.toList(),
        )
    }

    private fun resolveMember(
        initialView: DotNetClrResolvedTypeView,
        argument: DotNetClrCustomAttributeNamedArgument,
    ): DotNetClrResolvedCustomAttributeNamedMember {
        var view = initialView
        val visited = mutableSetOf<ResolvedTypeKey>()
        repeat(inheritanceLimit) {
            if (!visited.add(ResolvedTypeKey(view.type))) {
                fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.INHERITANCE_CYCLE)
            }
            when (argument.kind) {
                DotNetClrCustomAttributeNamedArgumentKind.FIELD ->
                    resolveDeclaredField(view, argument)?.let { return it }

                DotNetClrCustomAttributeNamedArgumentKind.PROPERTY ->
                    resolveDeclaredProperty(view, argument)?.let { return it }
            }
            view = resolveBaseView(view) ?: fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure.MEMBER_NOT_FOUND
            )
        }
        fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.INHERITANCE_LIMIT_EXCEEDED)
    }

    private fun resolveDeclaredField(
        view: DotNetClrResolvedTypeView,
        argument: DotNetClrCustomAttributeNamedArgument,
    ): DotNetClrResolvedCustomAttributeNamedMember.Field? {
        val fields = view.type.assembly.fieldDefinitions.filter { field ->
            field.declaringType == view.type.definition.handle && field.name == argument.name
        }
        if (fields.isEmpty()) return null
        if (fields.size != 1) {
            fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.AMBIGUOUS_MEMBER)
        }
        val field = fields.single()
        val effectiveType = resolveAndSubstitute(
            view,
            field.signature.fieldType,
        )
        if (!matches(argument.type, effectiveType)) {
            fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.MEMBER_TYPE_MISMATCH)
        }
        when {
            field.visibility != DotNetClrFieldVisibility.PUBLIC ->
                fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.FIELD_NOT_PUBLIC)

            field.isStatic ->
                fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.FIELD_IS_STATIC)

            field.isInitOnly || field.isLiteral ->
                fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.FIELD_IS_READ_ONLY)
        }
        return DotNetClrResolvedCustomAttributeNamedMember.Field(
            view,
            effectiveType,
            field,
        )
    }

    private fun resolveDeclaredProperty(
        view: DotNetClrResolvedTypeView,
        argument: DotNetClrCustomAttributeNamedArgument,
    ): DotNetClrResolvedCustomAttributeNamedMember.Property? {
        val properties = view.type.assembly.propertyDefinitions.filter { property ->
            property.declaringType == view.type.definition.handle &&
                    property.name == argument.name
        }
        if (properties.isEmpty()) return null

        val candidates = properties.map { property ->
            val effectiveType = resolveAndSubstitute(
                view,
                property.signature.propertyType,
            )
            PropertyCandidate(property, effectiveType)
        }
        val typedCandidates = candidates.filter { candidate ->
            matches(argument.type, candidate.effectiveType)
        }
        if (typedCandidates.isEmpty()) {
            fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.MEMBER_TYPE_MISMATCH)
        }
        val nonIndexedCandidates = typedCandidates.filter { candidate ->
            candidate.property.signature.hasThis &&
                    candidate.property.signature.indexParameterTypes.isEmpty()
        }
        if (nonIndexedCandidates.isEmpty()) {
            val failure = if (typedCandidates.all { candidate ->
                    !candidate.property.signature.hasThis
                }
            ) {
                DotNetClrCustomAttributeNamedArgumentValidationFailure.PROPERTY_IS_STATIC
            } else {
                DotNetClrCustomAttributeNamedArgumentValidationFailure.PROPERTY_IS_INDEXED
            }
            fail(failure)
        }
        if (nonIndexedCandidates.size != 1) {
            fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.AMBIGUOUS_MEMBER)
        }
        val candidate = nonIndexedCandidates.single()
        val accessors = view.type.assembly.methodSemantics.filter { semantics ->
            semantics.association == candidate.property.handle
        }
        val getterSemantics = accessors.filter { semantics ->
            semantics.kind == DotNetClrMethodSemanticsKind.GETTER
        }
        val setterSemantics = accessors.filter { semantics ->
            semantics.kind == DotNetClrMethodSemanticsKind.SETTER
        }
        if (getterSemantics.isEmpty()) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .PROPERTY_GETTER_MISSING
            )
        }
        if (setterSemantics.isEmpty()) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .PROPERTY_SETTER_MISSING
            )
        }
        if (getterSemantics.size != 1 || setterSemantics.size != 1) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .PROPERTY_ACCESSOR_AMBIGUOUS
            )
        }
        val getter = method(view, getterSemantics.single().method)
        val setter = method(view, setterSemantics.single().method)
        if (getter.visibility != DotNetClrMethodVisibility.PUBLIC ||
            setter.visibility != DotNetClrMethodVisibility.PUBLIC
        ) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .PROPERTY_ACCESSOR_NOT_PUBLIC
            )
        }
        if (!validGetter(view, getter, candidate.effectiveType) ||
            !validSetter(view, setter, candidate.effectiveType)
        ) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .PROPERTY_ACCESSOR_INVALID
            )
        }
        return DotNetClrResolvedCustomAttributeNamedMember.Property(
            view,
            candidate.effectiveType,
            candidate.property,
            getter,
            setter,
        )
    }

    private fun method(
        view: DotNetClrResolvedTypeView,
        handle: DotNetClrMetadataHandle,
    ): DotNetClrMethodDefinition =
        view.type.assembly.methodDefinitions.singleOrNull { method ->
            method.handle == handle && method.declaringType == view.type.definition.handle
        } ?: fail(
            DotNetClrCustomAttributeNamedArgumentValidationFailure
                .PROPERTY_ACCESSOR_INVALID
        )

    private fun validGetter(
        view: DotNetClrResolvedTypeView,
        getter: DotNetClrMethodDefinition,
        propertyType: DotNetClrResolvedTypeSignature,
    ): Boolean {
        if (getter.isStatic) return false
        val signature = resolveAndSubstitute(view, getter.signature)
        return signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                signature.hasThis &&
                !signature.hasExplicitThis &&
                signature.genericParameterCount == 0 &&
                signature.parameterTypes.isEmpty() &&
                signature.varargParameterStart == null &&
                sameTypeIgnoringModifiers(signature.returnType, propertyType)
    }

    private fun validSetter(
        view: DotNetClrResolvedTypeView,
        setter: DotNetClrMethodDefinition,
        propertyType: DotNetClrResolvedTypeSignature,
    ): Boolean {
        if (setter.isStatic) return false
        val signature = resolveAndSubstitute(view, setter.signature)
        return signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                signature.hasThis &&
                !signature.hasExplicitThis &&
                signature.genericParameterCount == 0 &&
                signature.varargParameterStart == null &&
                unmodified(signature.returnType) == DotNetClrResolvedTypeSignature.Void &&
                signature.parameterTypes.size == 1 &&
                sameTypeIgnoringModifiers(signature.parameterTypes.single(), propertyType)
    }

    private fun resolveAndSubstitute(
        view: DotNetClrResolvedTypeView,
        signature: DotNetClrTypeSignature,
    ): DotNetClrResolvedTypeSignature {
        val resolved = when (
            val resolution = signatureResolver.resolve(view.type.assembly, signature)
        ) {
            is DotNetClrResolvedSignatureResolution.Resolved -> resolution.signature
            is DotNetClrResolvedSignatureResolution.UnresolvedType ->
                fail(
                    DotNetClrCustomAttributeNamedArgumentValidationFailure
                        .TYPE_RESOLUTION_FAILED,
                    typeResolution = resolution.resolution,
                )

            is DotNetClrResolvedSignatureResolution.Invalid ->
                fail(
                    DotNetClrCustomAttributeNamedArgumentValidationFailure
                        .INVALID_RESOLVED_SIGNATURE,
                    signatureResolution = resolution,
                )
        }
        return when (
            val substitution = resolved.substituteClrTypeArguments(view.arguments)
        ) {
            is DotNetClrResolvedSignatureSubstitution.Substituted ->
                substitution.signature

            is DotNetClrResolvedSignatureSubstitution.Invalid ->
                fail(
                    DotNetClrCustomAttributeNamedArgumentValidationFailure
                        .GENERIC_SUBSTITUTION_FAILED,
                    signatureSubstitution = substitution,
                )
        }
    }

    private fun resolveAndSubstitute(
        view: DotNetClrResolvedTypeView,
        signature: DotNetClrMethodSignature,
    ): DotNetClrResolvedMethodSignature {
        val resolvedReturn = resolveAndSubstitute(view, signature.returnType)
        val resolvedParameters = signature.parameterTypes.map { parameter ->
            resolveAndSubstitute(view, parameter)
        }
        return DotNetClrResolvedMethodSignature(
            callingConvention = signature.callingConvention,
            hasThis = signature.hasThis,
            hasExplicitThis = signature.hasExplicitThis,
            genericParameterCount = signature.genericParameterCount,
            returnType = resolvedReturn,
            parameterTypes = resolvedParameters,
            varargParameterStart = signature.varargParameterStart,
        )
    }

    private fun resolveBaseView(
        view: DotNetClrResolvedTypeView,
    ): DotNetClrResolvedTypeView? {
        val baseHandle = view.type.definition.baseType ?: return null
        val resolvedBase = if (baseHandle.table == TYPE_SPEC_TABLE) {
            val specification = view.type.assembly.typeSpecifications.singleOrNull { candidate ->
                candidate.handle == baseHandle
            } ?: fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure.TYPE_RESOLUTION_FAILED
            )
            resolveAndSubstitute(view, specification.signature)
        } else {
            when (
                val resolution =
                    typeResolver.resolveTypeDefinition(view.type.assembly, baseHandle)
            ) {
                is DotNetClrTypeResolution.Resolved ->
                    DotNetClrResolvedTypeSignature.Named(
                        resolution.type,
                        isValueType = false,
                    )

                is DotNetClrTypeResolution.Unresolved ->
                    fail(
                        DotNetClrCustomAttributeNamedArgumentValidationFailure
                            .TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution,
                    )
            }
        }
        val nominalBase = unmodified(resolvedBase)
        val baseView = when (nominalBase) {
            is DotNetClrResolvedTypeSignature.Named ->
                DotNetClrResolvedTypeView(nominalBase.type, emptyList())

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                DotNetClrResolvedTypeView(
                    nominalBase.genericType.type,
                    nominalBase.arguments,
                )

            else ->
                fail(DotNetClrCustomAttributeNamedArgumentValidationFailure.INVALID_BASE_TYPE)
        }
        val parameters = genericParameters(baseView.type)
            ?: fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .INVALID_GENERIC_PARAMETER_NUMBERING
            )
        if (parameters.size != baseView.arguments.size) {
            fail(
                DotNetClrCustomAttributeNamedArgumentValidationFailure
                    .INVALID_RESOLVED_SIGNATURE
            )
        }
        return baseView
    }

    private fun genericParameters(
        type: DotNetClrResolvedTypeDefinition,
    ): List<DotNetClrGenericParameterDefinition>? {
        val parameters = type.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == type.definition.handle }
            .sortedBy(DotNetClrGenericParameterDefinition::number)
        return parameters.takeIf { sorted ->
            sorted.map(DotNetClrGenericParameterDefinition::number) == sorted.indices.toList()
        }
    }

    private fun matches(
        expected: DotNetClrCustomAttributeValueType,
        actual: DotNetClrResolvedTypeSignature,
    ): Boolean {
        val unmodifiedActual = unmodified(actual)
        return when (expected) {
            is DotNetClrCustomAttributeValueType.Primitive ->
                matchesPrimitive(expected.type, unmodifiedActual)

            DotNetClrCustomAttributeValueType.TaggedObject ->
                matchesPrimitive(DotNetClrPrimitiveType.OBJECT, unmodifiedActual)

            DotNetClrCustomAttributeValueType.SystemType ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.Named &&
                        !unmodifiedActual.isValueType &&
                        unmodifiedActual.type.hasSameIdentityAs(coreTypes.systemType)

            is DotNetClrCustomAttributeValueType.EnumType -> {
                val enumDefinition = nominalDefinition(unmodifiedActual) ?: return false
                val isValueType = when (unmodifiedActual) {
                    is DotNetClrResolvedTypeSignature.Named ->
                        unmodifiedActual.isValueType

                    is DotNetClrResolvedTypeSignature.GenericInstance ->
                        unmodifiedActual.genericType.isValueType

                    else -> false
                }
                if (!isValueType ||
                    !matchesSerializedType(expected.type, unmodifiedActual)
                ) {
                    false
                } else {
                    when (
                        val storage =
                            typeResolver.resolveEnumStorage(
                                enumDefinition,
                                coreTypes.systemEnum,
                            )
                    ) {
                        is DotNetClrEnumStorageResolution.Resolved ->
                            storage.storageType == expected.storageType

                        DotNetClrEnumStorageResolution.NotEnum,
                        is DotNetClrEnumStorageResolution.Invalid,
                        is DotNetClrEnumStorageResolution.UnresolvedBaseType,
                        -> false
                    }
                }
            }

            is DotNetClrCustomAttributeValueType.SzArray ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.SzArray &&
                        matches(expected.elementType, unmodifiedActual.elementType)
        }
    }

    private fun matchesPrimitive(
        primitive: DotNetClrPrimitiveType,
        actual: DotNetClrResolvedTypeSignature,
    ): Boolean {
        if (actual == DotNetClrResolvedTypeSignature.Primitive(primitive)) return true
        if (actual !is DotNetClrResolvedTypeSignature.Named) return false
        val primitiveType = when (
            val resolution = typeResolver.resolveTopLevelType(
                coreTypes.systemType.assembly,
                "System",
                primitive.systemTypeMetadataName,
            )
        ) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved ->
                fail(
                    DotNetClrCustomAttributeNamedArgumentValidationFailure
                        .TYPE_RESOLUTION_FAILED,
                    typeResolution = resolution,
                )
        }
        return actual.type.hasSameIdentityAs(primitiveType) &&
                actual.isValueType == primitive.isSystemValueType
    }

    private fun matchesSerializedType(
        expected: DotNetClrResolvedSerializedType,
        actual: DotNetClrResolvedTypeSignature,
    ): Boolean {
        val unmodifiedActual = unmodified(actual)
        return when (expected) {
            is DotNetClrResolvedSerializedType.Named ->
                when (unmodifiedActual) {
                    is DotNetClrResolvedTypeSignature.Named ->
                        unmodifiedActual.type.hasSameIdentityAs(expected.type)

                    is DotNetClrResolvedTypeSignature.Primitive ->
                        matchesPrimitive(unmodifiedActual.type, expected.asResolvedSignature())

                    else -> false
                }

            is DotNetClrResolvedSerializedType.GenericInstance ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.GenericInstance &&
                        unmodifiedActual.genericType.type.hasSameIdentityAs(
                            expected.genericType.type
                        ) &&
                        unmodifiedActual.arguments.size == expected.arguments.size &&
                        expected.arguments.zip(unmodifiedActual.arguments).all {
                                [expectedArgument, actualArgument] ->
                            matchesSerializedType(expectedArgument, actualArgument)
                        }

            is DotNetClrResolvedSerializedType.Pointer ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.Pointer &&
                        matchesSerializedType(expected.elementType, unmodifiedActual.elementType)

            is DotNetClrResolvedSerializedType.ByReference ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.ByReference &&
                        matchesSerializedType(expected.elementType, unmodifiedActual.elementType)

            is DotNetClrResolvedSerializedType.SzArray ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.SzArray &&
                        matchesSerializedType(expected.elementType, unmodifiedActual.elementType)

            is DotNetClrResolvedSerializedType.MdArray ->
                unmodifiedActual is DotNetClrResolvedTypeSignature.Array &&
                        unmodifiedActual.shape.rank == expected.rank &&
                        matchesSerializedType(expected.elementType, unmodifiedActual.elementType)
        }
    }

    private fun DotNetClrResolvedSerializedType.Named.asResolvedSignature():
            DotNetClrResolvedTypeSignature.Named =
        DotNetClrResolvedTypeSignature.Named(
            type,
            isValueType = type.definition.metadataName !in REFERENCE_PRIMITIVE_TYPE_NAMES,
        )

    private fun nominalDefinition(
        signature: DotNetClrResolvedTypeSignature,
    ): DotNetClrResolvedTypeDefinition? =
        when (signature) {
            is DotNetClrResolvedTypeSignature.Named -> signature.type
            is DotNetClrResolvedTypeSignature.GenericInstance ->
                signature.genericType.type

            else -> null
        }

    private fun sameTypeIgnoringModifiers(
        left: DotNetClrResolvedTypeSignature,
        right: DotNetClrResolvedTypeSignature,
    ): Boolean =
        left.withoutCustomModifiers() == right.withoutCustomModifiers()

    private fun unmodified(
        type: DotNetClrResolvedTypeSignature,
    ): DotNetClrResolvedTypeSignature {
        var current = type
        while (current is DotNetClrResolvedTypeSignature.Modified) {
            current = current.unmodifiedType
        }
        return current
    }

    private fun invalid(
        failure: DotNetClrCustomAttributeNamedArgumentValidationFailure,
        namedArgumentIndex: Int,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ): DotNetClrCustomAttributeNamedArgumentValidation.Invalid =
        DotNetClrCustomAttributeNamedArgumentValidation.Invalid(
            failure,
            namedArgumentIndex,
            typeResolution,
            signatureResolution,
            signatureSubstitution,
        )

    private fun fail(
        failure: DotNetClrCustomAttributeNamedArgumentValidationFailure,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ): Nothing =
        throw NamedArgumentValidationException(
            failure,
            typeResolution,
            signatureResolution,
            signatureSubstitution,
        )

    private data class PropertyCandidate(
        val property: DotNetClrPropertyDefinition,
        val effectiveType: DotNetClrResolvedTypeSignature,
    )

    private class ResolvedTypeKey(
        private val type: DotNetClrResolvedTypeDefinition,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ResolvedTypeKey && type.hasSameIdentityAs(other.type)

        override fun hashCode(): Int = type.hashCode()
    }

    private class ResolvedMemberKey(
        member: DotNetClrResolvedCustomAttributeNamedMember,
    ) {
        private val assembly = member.declaringType.type.assembly
        private val handle = when (member) {
            is DotNetClrResolvedCustomAttributeNamedMember.Field ->
                member.field.handle

            is DotNetClrResolvedCustomAttributeNamedMember.Property ->
                member.property.handle
        }

        override fun equals(other: Any?): Boolean =
            other is ResolvedMemberKey &&
                    assembly === other.assembly &&
                    handle == other.handle

        override fun hashCode(): Int =
            31 * System.identityHashCode(assembly) + handle.hashCode()
    }

    private companion object {
        const val TYPE_SPEC_TABLE = 27
        const val DEFAULT_INHERITANCE_LIMIT = 256
        const val MAX_INHERITANCE_LIMIT = 4096

        val REFERENCE_PRIMITIVE_TYPE_NAMES = setOf("Object", "String")
    }
}

private class NamedArgumentValidationException(
    val failure: DotNetClrCustomAttributeNamedArgumentValidationFailure,
    val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
    val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
    val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
) : RuntimeException()

private fun DotNetClrResolvedTypeSignature.withoutCustomModifiers():
        DotNetClrResolvedTypeSignature =
    when (this) {
        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Primitive,
        is DotNetClrResolvedTypeSignature.Named,
        is DotNetClrResolvedTypeSignature.GenericParameter,
        -> this

        is DotNetClrResolvedTypeSignature.Pointer ->
            DotNetClrResolvedTypeSignature.Pointer(
                elementType.withoutCustomModifiers()
            )

        is DotNetClrResolvedTypeSignature.ByReference ->
            DotNetClrResolvedTypeSignature.ByReference(
                elementType.withoutCustomModifiers()
            )

        is DotNetClrResolvedTypeSignature.SzArray ->
            DotNetClrResolvedTypeSignature.SzArray(
                elementType.withoutCustomModifiers()
            )

        is DotNetClrResolvedTypeSignature.Array ->
            DotNetClrResolvedTypeSignature.Array(
                elementType.withoutCustomModifiers(),
                shape,
            )

        is DotNetClrResolvedTypeSignature.GenericInstance ->
            DotNetClrResolvedTypeSignature.GenericInstance(
                genericType,
                arguments.map { argument -> argument.withoutCustomModifiers() },
            )

        is DotNetClrResolvedTypeSignature.FunctionPointer ->
            DotNetClrResolvedTypeSignature.FunctionPointer(
                signature.copy(
                    returnType = signature.returnType.withoutCustomModifiers(),
                    parameterTypes = signature.parameterTypes.map { parameter ->
                        parameter.withoutCustomModifiers()
                    },
                )
            )

        is DotNetClrResolvedTypeSignature.Modified ->
            unmodifiedType.withoutCustomModifiers()
    }
