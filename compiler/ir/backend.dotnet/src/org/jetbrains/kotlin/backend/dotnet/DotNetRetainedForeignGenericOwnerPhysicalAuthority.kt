/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationCarrierVersion
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericConstraintType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContextBinding
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignatureResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeHierarchy
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrSelectedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility

/**
 * Exact retained-metadata input for one bounded foreign generic-interface MethodDef.
 *
 * This is deliberately not a second importer. It consumes only the assembly graph and resolved
 * declaration carrier already selected by FIR, re-resolves the raw MethodDef signature inside
 * that same graph, and normalizes the result into the shared physical declaration vocabulary.
 * Unsupported but valid CLR shapes are unavailable; disagreement between retained and raw
 * metadata is a declaration conflict.
 *
 * The first slice binds one public abstract instance MethodDef from a public, top-level abstract
 * interface without direct parents. Its carrier grammar is structural: the shared primitive
 * leaves, exact owner/method parameters, SZ arrays, and recursive uses of the same interface.
 * These restrictions are proof boundaries, not declaration- or member-name policy.
 *
 * The first inherited-receiver extension admits one retained interface with zero or one
 * unconstrained type parameter in the same selected graph. Its exact TypeDef carrier is
 * independent of declared members, so a genuinely memberless child can participate, including
 * when a raw InterfaceImpl reaches the selected MethodDef owner through an exact AssemblyRef. One
 * additional exact edge to a non-generic root interface is admitted and retained as part of the
 * receiver's complete edge set; row order never selects the callable owner. Two distinct exact
 * constructions of the callable owner may coexist and require already-proven selected lineage at
 * the operation boundary. An owner edge may close that owner or forward the receiver parameter
 * through supported carriers. Its exact CLR variance is retained. Multiple binders, deeper
 * auxiliary hierarchies, constraints, MethodImpls, and classes remain unavailable.
 */
internal class DotNetRetainedForeignGenericOwnerPhysicalDeclarations private constructor(
    val typeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefReference>,
    val methodDefinitions: List<DotNetGenericOwnerPhysicalMethodDefReference>,
    val directSupertypeEdgeSets: List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
) {
    companion object {
        fun build(
            source: DotNetClrImportedMethodSource,
            method: DotNetClrMethodDefinition,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetRetainedForeignGenericOwnerPhysicalDeclarations> =
            Builder(source, method).build()

        fun buildInheritedReceiver(
            source: DotNetClrImportedMethodSource,
            method: DotNetClrMethodDefinition,
            receiverSource: DotNetClrImportedTypeSource,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetRetainedForeignGenericOwnerPhysicalDeclarations> =
            InheritedReceiverBuilder(source, method, receiverSource).build()
    }

    /** Bounded hierarchy slice: one complete exact edge set from a retained zero/one-binder interface. */
    private class InheritedReceiverBuilder(
        private val source: DotNetClrImportedMethodSource,
        private val method: DotNetClrMethodDefinition,
        private val receiverSource: DotNetClrImportedTypeSource,
    ) {
        private val selectedMetadata = source.graph.assemblies.map { assembly -> assembly.metadata }
        private val typeResolver =
            DotNetClrTypeResolver(DotNetClrSelectedAssemblyBinder(selectedMetadata))
        private val genericContextResolver =
            DotNetClrGenericParameterContextResolver(typeResolver)

        fun build(): DotNetGenericOwnerPhysicalBindingResult<
                DotNetRetainedForeignGenericOwnerPhysicalDeclarations,
                > {
            val methodDeclarations = when (val binding = Builder(source, method).build()) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return binding
            }
            when (receiverSource.carrierVersion) {
                DotNetClrImportedDeclarationCarrierVersion.V3 -> Unit
            }
            if (receiverSource.graph !== source.graph) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }

            val retainedHierarchy = receiverSource.declaringHierarchy
            val rawHierarchy = when (
                val resolution = DotNetClrTypeHierarchyViewResolver(typeResolver).resolve(
                    retainedHierarchy.type,
                )
            ) {
                is DotNetClrTypeHierarchyViewResolution.Resolved -> resolution.hierarchy
                is DotNetClrTypeHierarchyViewResolution.Invalid ->
                    return conflict("retained foreign receiver has an invalid raw hierarchy")
            }
            if (rawHierarchy != retainedHierarchy) {
                return conflict("retained foreign receiver hierarchy contradicts raw metadata")
            }
            val receiverContext = when (
                val resolution = genericContextResolver.resolve(retainedHierarchy.type)
            ) {
                is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                is DotNetClrGenericParameterContextResolution.Invalid ->
                    return conflict("retained foreign receiver has an invalid generic-parameter context")
            }
            if (receiverContext.method != null ||
                receiverContext.declaringType != retainedHierarchy.type
            ) {
                return conflict("retained foreign receiver generic context changed its declaring TypeDef")
            }
            if (!hasSupportedReceiverShape(rawHierarchy, receiverContext.typeParameters.size)) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val receiverParameters = when (
                val translation = translateReceiverParameters(receiverContext)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return translation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }

            val methodOwner = source.declaringHierarchy.type.type
            if (rawHierarchy.interfaces.none { implementation ->
                    implementation.interfaceType.type.hasSameIdentityAs(methodOwner)
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val receiverIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
            val parentIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(source)
            val parentDescription = methodDeclarations.typeDefinitions.singleOrNull { candidate ->
                candidate.identity == parentIdentity
            } ?: return conflict("retained MethodDef authority omitted its declaring TypeDef")
            if (receiverIdentity == parentIdentity) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val additionalDefinitions = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalTypeDefReference,
                    >()
            val additionalEdgeSets = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
                    >()
            val receiverEdges = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>()
            for (implementation in rawHierarchy.interfaces) {
                val targetType = implementation.interfaceType.type
                val targetIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                val targetDescription: DotNetGenericOwnerPhysicalTypeDefReference
                if (targetType.hasSameIdentityAs(methodOwner)) {
                    targetIdentity = parentIdentity
                    targetDescription = parentDescription
                } else {
                    val auxiliary = when (val binding = buildAuxiliaryRootInterface(targetType)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    targetIdentity = auxiliary.identity
                    targetDescription = auxiliary.definition
                    additionalDefinitions[targetIdentity] = targetDescription
                    additionalEdgeSets[targetIdentity] = auxiliary.edgeSet
                }

                val targetArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (argument in implementation.interfaceType.arguments) {
                    when (val translation = translateInheritedCarrier(
                        argument,
                        receiverIdentity,
                        receiverContext,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            targetArguments += translation.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return translation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                if (targetArguments.size != targetDescription.genericArity) {
                    return conflict("retained InterfaceImpl contradicts its target TypeDef arity")
                }
                receiverEdges += DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                        targetIdentity,
                        targetArguments,
                    ),
                )
            }
            if (receiverEdges.size != receiverEdges.toSet().size) {
                return conflict("retained receiver contains duplicate physical InterfaceImpl edges")
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetRetainedForeignGenericOwnerPhysicalDeclarations(
                    typeDefinitions = methodDeclarations.typeDefinitions +
                            additionalDefinitions.values +
                            DotNetGenericOwnerPhysicalTypeDefReference(
                                identity = receiverIdentity,
                                genericParameters = receiverParameters,
                                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                            ),
                    methodDefinitions = methodDeclarations.methodDefinitions,
                    directSupertypeEdgeSets = methodDeclarations.directSupertypeEdgeSets +
                            additionalEdgeSets.values +
                            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                                receiverIdentity,
                                receiverEdges,
                            ),
                )
            )
        }

        private fun hasSupportedReceiverShape(
            hierarchy: DotNetClrResolvedTypeHierarchy,
            genericArity: Int,
        ): Boolean {
            val definition = receiverSource.declaringType
            val openArguments = List(genericArity) { index ->
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    index,
                )
            }
            return genericArity <= 1 &&
                    hierarchy.type.arguments == openArguments &&
                    definition.isInterface &&
                    definition.isAbstract &&
                    !definition.isSealed &&
                    definition.declaringType == null &&
                    definition.visibility == DotNetClrTypeVisibility.PUBLIC &&
                    definition.baseType == null &&
                    hierarchy.baseType == null &&
                    hierarchy.interfaces.size in 1..2 &&
                    receiverSource.assembly.metadata.methodImplementations.none { implementation ->
                        implementation.implementingType == definition.handle
                    }
        }

        private fun buildAuxiliaryRootInterface(
            type: DotNetClrResolvedTypeDefinition,
        ): DotNetGenericOwnerPhysicalBindingResult<AuxiliaryRootInterface> {
            val retainedHierarchy = source.graph.hierarchyOrNull(type)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val rawHierarchy = when (
                val resolution = DotNetClrTypeHierarchyViewResolver(typeResolver).resolve(
                    retainedHierarchy.type,
                )
            ) {
                is DotNetClrTypeHierarchyViewResolution.Resolved -> resolution.hierarchy
                is DotNetClrTypeHierarchyViewResolution.Invalid ->
                    return conflict("auxiliary retained interface has an invalid raw hierarchy")
            }
            if (rawHierarchy != retainedHierarchy) {
                return conflict("auxiliary retained interface hierarchy contradicts raw metadata")
            }
            val context = when (
                val resolution = genericContextResolver.resolve(retainedHierarchy.type)
            ) {
                is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                is DotNetClrGenericParameterContextResolution.Invalid ->
                    return conflict("auxiliary retained interface has an invalid generic context")
            }
            val definition = type.definition
            if (context.method != null ||
                context.declaringType != retainedHierarchy.type
            ) {
                return conflict("auxiliary retained interface changed its declaring TypeDef")
            }
            if (context.typeParameters.isNotEmpty() ||
                retainedHierarchy.type.arguments.isNotEmpty() ||
                !definition.isInterface ||
                !definition.isAbstract ||
                definition.isSealed ||
                definition.declaringType != null ||
                definition.visibility != DotNetClrTypeVisibility.PUBLIC ||
                definition.baseType != null ||
                retainedHierarchy.baseType != null ||
                retainedHierarchy.interfaces.isNotEmpty()
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val identity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource, type)
            val physicalDefinition = DotNetGenericOwnerPhysicalTypeDefReference(
                identity = identity,
                genericParameters = emptyList(),
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            )
            return bound(
                AuxiliaryRootInterface(
                    identity = identity,
                    definition = physicalDefinition,
                    edgeSet = DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                        identity,
                        emptyList(),
                    ),
                )
            )
        }

        private fun translateReceiverParameters(
            context: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<
                List<DotNetGenericOwnerPhysicalGenericParameterReference>,
                > {
            val translated = ArrayList<DotNetGenericOwnerPhysicalGenericParameterReference>(
                context.typeParameters.size,
            )
            for (index in context.typeParameters.indices) {
                val binding = context.typeParameters[index]
                if (binding.kind != DotNetClrGenericParameterKind.TYPE ||
                    binding.parameter.owner != receiverSource.declaringType.handle ||
                    binding.parameter.number != index
                ) {
                    return conflict("retained foreign receiver GenericParam changed binder or numbering")
                }
                val attributes = binding.parameter.attributes
                if (attributes and GENERIC_PARAMETER_VARIANCE_MASK ==
                    GENERIC_PARAMETER_VARIANCE_MASK
                ) {
                    return conflict("retained foreign receiver GenericParam declares incompatible variance")
                }
                if (binding.parameter.hasReferenceTypeConstraint &&
                    binding.parameter.hasNotNullableValueTypeConstraint
                ) {
                    return conflict(
                        "retained foreign receiver GenericParam requires reference and value arguments",
                    )
                }
                if (attributes and GENERIC_PARAMETER_VARIANCE_MASK.inv() != 0 ||
                    binding.constraints.isNotEmpty()
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val variance = when (binding.parameter.variance) {
                    DotNetClrGenericParameterVariance.INVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                    DotNetClrGenericParameterVariance.COVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
                    DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
                }
                translated += DotNetGenericOwnerPhysicalGenericParameterReference(
                    variance,
                    constraints = emptyList(),
                )
            }
            return bound(translated.toList())
        }

        private fun translateInheritedCarrier(
            type: DotNetClrResolvedTypeSignature,
            receiverIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            context: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
            return when (type) {
                DotNetClrResolvedTypeSignature.Void ->
                    conflict("void appeared in a retained InterfaceImpl argument")
                is DotNetClrResolvedTypeSignature.Primitive -> when (type.type) {
                    DotNetClrPrimitiveType.BOOLEAN -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                    )
                    DotNetClrPrimitiveType.INT32 -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                    )
                    DotNetClrPrimitiveType.STRING -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                    )
                    DotNetClrPrimitiveType.OBJECT -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                    )
                    else -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                is DotNetClrResolvedTypeSignature.GenericParameter -> {
                    val binding = context.binding(type)
                        ?: return conflict("retained InterfaceImpl escaped its receiver binder")
                    if (type.kind != DotNetClrGenericParameterKind.TYPE ||
                        binding.parameter.owner != receiverSource.declaringType.handle ||
                        binding.parameter.number != type.index
                    ) {
                        return conflict("retained InterfaceImpl changed its receiver binder")
                    }
                    bound(
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter
                            .unboundTypeParameterReference(receiverIdentity, type.index),
                    )
                }
                is DotNetClrResolvedTypeSignature.SzArray -> when (
                    val element = translateInheritedCarrier(
                        type.elementType,
                        receiverIdentity,
                        context,
                    )
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                    )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> element
                }
                is DotNetClrResolvedTypeSignature.Named,
                is DotNetClrResolvedTypeSignature.GenericInstance,
                DotNetClrResolvedTypeSignature.TypedReference,
                is DotNetClrResolvedTypeSignature.Pointer,
                is DotNetClrResolvedTypeSignature.ByReference,
                is DotNetClrResolvedTypeSignature.Array,
                is DotNetClrResolvedTypeSignature.FunctionPointer,
                is DotNetClrResolvedTypeSignature.Modified,
                -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }

        private fun <T> bound(value: T): DotNetGenericOwnerPhysicalBindingResult.Bound<T> =
            DotNetGenericOwnerPhysicalBindingResult.Bound(value)

        private fun conflict(
            reason: String,
        ): DotNetGenericOwnerPhysicalBindingResult.Conflict =
            DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)

        private companion object {
            const val GENERIC_PARAMETER_VARIANCE_MASK = 0x0003
        }

        private data class AuxiliaryRootInterface(
            val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            val definition: DotNetGenericOwnerPhysicalTypeDefReference,
            val edgeSet: DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
        )
    }

    private class Builder(
        private val source: DotNetClrImportedMethodSource,
        private val method: DotNetClrMethodDefinition,
    ) {
        private val ownerType: DotNetClrResolvedTypeDefinition =
            source.declaringHierarchy.type.type
        private val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(source)
        private val methodIdentity by lazy(LazyThreadSafetyMode.NONE) {
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(source, method)
        }
        private val selectedMetadata = source.graph.assemblies.map { assembly -> assembly.metadata }
        private val typeResolver =
            DotNetClrTypeResolver(DotNetClrSelectedAssemblyBinder(selectedMetadata))
        private val signatureResolver = DotNetClrSignatureResolver(typeResolver)
        private val genericContextResolver =
            DotNetClrGenericParameterContextResolver(typeResolver)

        fun build(): DotNetGenericOwnerPhysicalBindingResult<
                DotNetRetainedForeignGenericOwnerPhysicalDeclarations,
                > {
            when (source.carrierVersion) {
                DotNetClrImportedDeclarationCarrierVersion.V3 -> Unit
            }
            if (method.declaringType != source.declaringType.handle ||
                source.assembly.metadata.methodDefinitions.none { candidate -> candidate === method }
            ) {
                return conflict("retained foreign MethodDef escaped its selected TypeDef")
            }
            when (val validation = validateRetainedHierarchy()) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return conflict(validation.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (!hasSupportedOwnerShape() || !hasSupportedMethodShape()) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }

            val retainedSignature = source.resolvedSignature.takeIf {
                source.method === method
            } ?: return conflict("retained foreign declaration carrier does not own the selected MethodDef")
            val resolvedSignature = when (
                val resolution = signatureResolver.resolve(source.assembly.metadata, method.signature)
            ) {
                is DotNetClrResolvedMethodSignatureResolution.Resolved -> resolution.signature
                is DotNetClrResolvedMethodSignatureResolution.Invalid ->
                    return conflict("raw retained MethodDef signature is invalid in its selected assembly graph")
                is DotNetClrResolvedMethodSignatureResolution.UnresolvedType ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (resolvedSignature != retainedSignature) {
                return conflict("retained resolved MethodDef signature contradicts its raw metadata")
            }

            val ownerContext = when (
                val resolution = genericContextResolver.resolve(source.declaringHierarchy.type)
            ) {
                is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                is DotNetClrGenericParameterContextResolution.Invalid ->
                    return conflict("retained foreign TypeDef has an invalid generic-parameter context")
            }
            val methodContext = when (
                val resolution = genericContextResolver.resolve(source.declaringHierarchy.type, method)
            ) {
                is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                is DotNetClrGenericParameterContextResolution.Invalid ->
                    return conflict("retained foreign MethodDef has an invalid generic-parameter context")
            }
            if (methodContext.typeParameters != ownerContext.typeParameters ||
                methodContext.declaringType != ownerContext.declaringType ||
                methodContext.method !== method
            ) {
                return conflict("retained MethodDef context contradicts its declaring TypeDef context")
            }

            val ownerParameters = when (
                val translation = translateGenericParameters(
                    ownerContext.typeParameters,
                    DotNetClrGenericParameterKind.TYPE,
                    methodContext,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conflict(translation.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val methodParameters = when (
                val translation = translateGenericParameters(
                    methodContext.methodParameters,
                    DotNetClrGenericParameterKind.METHOD,
                    methodContext,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conflict(translation.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }

            val parameterSlots = mutableListOf<DotNetGenericOwnerPhysicalCallableValueSlotReference>()
            for (parameterType in resolvedSignature.parameterTypes) {
                val carrier = when (val translation = translateType(parameterType, methodContext)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conflict(translation.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                parameterSlots += DotNetGenericOwnerPhysicalCallableValueSlotReference(
                    domain = if (carrier.referencesOwnerParameter()) {
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                    } else {
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    },
                    carrier = carrier,
                )
            }
            val resultLayout = when (val returnType = resolvedSignature.returnType) {
                DotNetClrResolvedTypeSignature.Void ->
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void
                else -> {
                    val carrier = when (val translation = translateType(returnType, methodContext)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conflict(translation.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                        DotNetGenericOwnerPhysicalCallableValueSlotReference(
                            domain = if (carrier.referencesOwnerParameter()) {
                                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
                            } else {
                                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                            },
                            carrier = carrier,
                        )
                    )
                }
            }

            val typeDefinition = DotNetGenericOwnerPhysicalTypeDefReference(
                identity = ownerIdentity,
                genericParameters = ownerParameters,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            )
            val methodDefinition = DotNetGenericOwnerPhysicalMethodDefReference(
                identity = methodIdentity,
                declaringType = ownerIdentity,
                visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
                dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
                signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                    isInstance = true,
                    genericArity = methodParameters.size,
                    resultLayout = resultLayout,
                    parameterSlots = parameterSlots,
                ),
                genericParameters = methodParameters,
            )
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetRetainedForeignGenericOwnerPhysicalDeclarations(
                    typeDefinitions = listOf(typeDefinition),
                    methodDefinitions = listOf(methodDefinition),
                    directSupertypeEdgeSets = listOf(
                        DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                            ownerIdentity,
                            emptyList(),
                        )
                    ),
                )
            )
        }

        private fun validateRetainedHierarchy(): DotNetGenericOwnerPhysicalBindingResult<Unit> {
            val retained = source.declaringHierarchy
            if ((source.declaringType.baseType == null) != (retained.baseType == null)) {
                return conflict("retained foreign TypeDef hierarchy contradicts its raw base row")
            }
            val rawInterfaces = source.assembly.metadata.interfaceImplementations.filter { row ->
                row.implementingType == source.declaringType.handle
            }
            if (rawInterfaces.size != retained.interfaces.size ||
                rawInterfaces.any { raw ->
                    retained.interfaces.count { implementation -> implementation.row === raw } != 1
                }
            ) {
                return conflict("retained foreign TypeDef hierarchy omits or duplicates raw InterfaceImpl rows")
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        }

        private fun hasSupportedOwnerShape(): Boolean {
            val definition = source.declaringType
            val openArguments = source.assembly.metadata.genericParameterDefinitions
                .filter { parameter -> parameter.owner == definition.handle }
                .sortedBy { parameter -> parameter.number }
                .indices
                .map { index ->
                    DotNetClrResolvedTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        index,
                    )
                }
            return ownerType.assembly === source.assembly.metadata &&
                    ownerType.definition === definition &&
                    source.declaringHierarchy.type.arguments == openArguments &&
                    definition.isInterface &&
                    definition.isAbstract &&
                    !definition.isSealed &&
                    definition.declaringType == null &&
                    definition.visibility == DotNetClrTypeVisibility.PUBLIC &&
                    definition.baseType == null &&
                    source.declaringHierarchy.baseType == null &&
                    source.declaringHierarchy.interfaces.isEmpty() &&
                    source.assembly.metadata.methodImplementations.none { implementation ->
                        implementation.implementingType == definition.handle
                    }
        }

        private fun hasSupportedMethodShape(): Boolean {
            val signature = method.signature
            return method.visibility == DotNetClrMethodVisibility.PUBLIC &&
                    !method.isStatic &&
                    method.isAbstract &&
                    method.isVirtual &&
                    !method.isFinal &&
                    method.relativeVirtualAddress == 0L &&
                    method.implementationAttributes == 0 &&
                    !method.isSpecialName &&
                    !method.isRuntimeSpecialName &&
                    signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                    signature.hasThis &&
                    !signature.hasExplicitThis &&
                    signature.varargParameterStart == null
        }

        private fun translateGenericParameters(
            bindings: List<DotNetClrResolvedGenericParameterContextBinding>,
            expectedKind: DotNetClrGenericParameterKind,
            context: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<
                List<DotNetGenericOwnerPhysicalGenericParameterReference>,
                > {
            val translated = ArrayList<DotNetGenericOwnerPhysicalGenericParameterReference>(bindings.size)
            for (index in bindings.indices) {
                val binding = bindings[index]
                if (binding.kind != expectedKind ||
                    binding.parameter.number != index ||
                    binding.parameter.owner != when (expectedKind) {
                        DotNetClrGenericParameterKind.TYPE -> source.declaringType.handle
                        DotNetClrGenericParameterKind.METHOD -> method.handle
                    }
                ) {
                    return conflict("retained foreign GenericParam binder or numbering changed")
                }
                val attributes = binding.parameter.attributes
                if (attributes and GENERIC_PARAMETER_VARIANCE_MASK ==
                    GENERIC_PARAMETER_VARIANCE_MASK
                ) {
                    return conflict("retained foreign GenericParam declares incompatible CLR variance")
                }
                if (attributes and SUPPORTED_GENERIC_PARAMETER_ATTRIBUTES.inv() != 0) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val variance = when (binding.parameter.variance) {
                    DotNetClrGenericParameterVariance.INVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                    DotNetClrGenericParameterVariance.COVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
                    DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                        DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
                }
                if (expectedKind == DotNetClrGenericParameterKind.METHOD &&
                    variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                ) {
                    return conflict("retained foreign MethodDef GenericParam declares CLR variance")
                }
                if (binding.parameter.hasReferenceTypeConstraint &&
                    binding.parameter.hasNotNullableValueTypeConstraint
                ) {
                    return conflict(
                        "retained foreign GenericParam requires both reference- and value-type arguments",
                    )
                }
                val constraints = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (constraint in binding.constraints) {
                    val carrier = when (val type = constraint.type) {
                        is DotNetClrResolvedGenericConstraintType.Nominal ->
                            translateNominalConstraint(type.type, context)
                        is DotNetClrResolvedGenericConstraintType.Specification ->
                            translateType(type.type, context)
                    }
                    when (carrier) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            constraints += carrier.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return conflict(carrier.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                if (constraints.size != constraints.toSet().size) {
                    return conflict("retained foreign GenericParam repeats a constraint carrier")
                }
                translated += DotNetGenericOwnerPhysicalGenericParameterReference(
                    variance = variance,
                    constraints = constraints,
                    hasReferenceTypeConstraint = binding.parameter.hasReferenceTypeConstraint,
                    hasNotNullableValueTypeConstraint =
                        binding.parameter.hasNotNullableValueTypeConstraint,
                    hasDefaultConstructorConstraint =
                        binding.parameter.hasDefaultConstructorConstraint,
                    allowsByRefLike = binding.parameter.allowsByRefLike,
                )
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(translated.toList())
        }

        private fun translateNominalConstraint(
            type: DotNetClrResolvedTypeDefinition,
            context: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
            if (!type.hasSameIdentityAs(ownerType)) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (context.typeParameters.isNotEmpty()) {
                // A generic constraint must retain its TypeSpec arguments. A bare generic TypeDef
                // is not an honest construction and cannot be repaired from its metadata name.
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                    ownerIdentity,
                    emptyList(),
                )
            )
        }

        private fun translateType(
            type: DotNetClrResolvedTypeSignature,
            context: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
            return when (type) {
                DotNetClrResolvedTypeSignature.Void -> conflict(
                    "void appeared in a retained foreign value carrier",
                )
                is DotNetClrResolvedTypeSignature.Primitive -> when (type.type) {
                    DotNetClrPrimitiveType.BOOLEAN -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                    )
                    DotNetClrPrimitiveType.INT32 -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                    )
                    DotNetClrPrimitiveType.STRING -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                    )
                    DotNetClrPrimitiveType.OBJECT -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                    )
                    else -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                is DotNetClrResolvedTypeSignature.GenericParameter -> {
                    val binding = context.binding(type)
                        ?: return conflict("retained foreign signature escaped its generic binder")
                    if (binding.parameter.number != type.index) {
                        return conflict("retained foreign signature changed its generic-parameter index")
                    }
                    bound(
                        when (type.kind) {
                            DotNetClrGenericParameterKind.TYPE ->
                                DotNetGenericOwnerSymbolicCarrierReference.Parameter
                                    .unboundTypeParameterReference(ownerIdentity, type.index)
                            DotNetClrGenericParameterKind.METHOD ->
                                DotNetGenericOwnerSymbolicCarrierReference.Parameter
                                    .methodParameterReference(methodIdentity, type.index)
                        }
                    )
                }
                is DotNetClrResolvedTypeSignature.SzArray -> when (
                    val element = translateType(type.elementType, context)
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                    )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> element
                }
                is DotNetClrResolvedTypeSignature.Named -> {
                    if (type.isValueType ||
                        !type.type.hasSameIdentityAs(ownerType) ||
                        context.typeParameters.isNotEmpty()
                    ) {
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    } else {
                        bound(
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed
                                .unboundTypeReference(ownerIdentity, emptyList()),
                        )
                    }
                }
                is DotNetClrResolvedTypeSignature.GenericInstance -> {
                    if (type.genericType.isValueType ||
                        !type.genericType.type.hasSameIdentityAs(ownerType) ||
                        type.arguments.size != context.typeParameters.size
                    ) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                    for (argument in type.arguments) {
                        when (val translated = translateType(argument, context)) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                                arguments += translated.value
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return translated
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return translated
                        }
                    }
                    bound(
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed
                            .unboundTypeReference(ownerIdentity, arguments),
                    )
                }
                DotNetClrResolvedTypeSignature.TypedReference,
                is DotNetClrResolvedTypeSignature.Pointer,
                is DotNetClrResolvedTypeSignature.ByReference,
                is DotNetClrResolvedTypeSignature.Array,
                is DotNetClrResolvedTypeSignature.FunctionPointer,
                is DotNetClrResolvedTypeSignature.Modified,
                -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }

        private fun DotNetGenericOwnerSymbolicCarrierReference.referencesOwnerParameter(): Boolean =
            when (this) {
                is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> false
                is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
                    binder == DotNetGenericOwnerPhysicalGenericBinderReference.Type(ownerIdentity)
                is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
                    arguments.any { argument -> argument.referencesOwnerParameter() }
                is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                    element.referencesOwnerParameter()
            }

        private fun <T> bound(value: T): DotNetGenericOwnerPhysicalBindingResult.Bound<T> =
            DotNetGenericOwnerPhysicalBindingResult.Bound(value)

        private fun conflict(
            reason: String,
        ): DotNetGenericOwnerPhysicalBindingResult.Conflict =
            DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)

        private companion object {
            const val GENERIC_PARAMETER_VARIANCE_MASK = 0x0003
            const val SUPPORTED_GENERIC_PARAMETER_ATTRIBUTES = 0x003f
        }
    }
}
