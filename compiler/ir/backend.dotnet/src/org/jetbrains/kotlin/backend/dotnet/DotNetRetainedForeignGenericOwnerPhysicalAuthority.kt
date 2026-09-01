/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrArrayRuntimeTypesResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrArrayRuntimeTypesResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrByRefLikeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrConstructedTypeConstraintResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrConstructedTypeConstraintResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrCustomAttributeDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateRuntimeTypes
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateRuntimeTypesResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateRuntimeTypesResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateTypeClassification
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateTypeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrDelegateTypeFailure
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationCarrierVersion
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrNominalConstraintSatisfaction
import org.jetbrains.kotlin.load.dotnet.DotNetClrNominalConstraintValidator
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassification
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveTypeCatalogResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveTypeCatalogResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedConstructedTypeConstraints
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericConstraintType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContextBinding
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignatureResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeHierarchy
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeView
import org.jetbrains.kotlin.load.dotnet.DotNetClrSelectedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrSpecialConstraintSatisfaction
import org.jetbrains.kotlin.load.dotnet.DotNetClrSpecialConstraintValidator
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.load.dotnet.resolveDotNetClrCustomAttributeCoreTypes
import org.jetbrains.kotlin.load.dotnet.resolveDotNetClrSystemType

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
 * The inherited-receiver extension authenticates a resource-bounded acyclic graph of retained
 * memberless interfaces in the same selected graph. Every visited TypeDef, GenericParam binder,
 * and complete direct InterfaceImpl edge set is re-resolved from raw metadata before it becomes
 * physical authority. The shared physical-interface closure, rather than this adapter, performs
 * construction substitution. Row order never selects the callable owner, and two distinct exact
 * constructions of that owner require already-proven selected lineage at the operation boundary.
 * Each admitted graph interface has a resource-bounded ordered binder vector whose exact CLR
 * variance and supported nominal constraints are retained. An exact nominal carrier may be a
 * public interface, ordinary reference class, or value type whose selected hierarchy agrees with
 * raw metadata; a generic auxiliary carrier additionally requires a complete supported binder
 * vector. Actual signatures must agree with the retained TypeDef's class/value kind. A bare
 * TypeDef/TypeRef constraint row has no such signature marker, so only that row may infer the kind
 * from the selected definition. Non-nullable and nullable value carriers preserve their distinct
 * null encodings through the shared physical classifier.
 * Recursive constructed carriers are depth- and node-bounded, and an unretained auxiliary edge
 * set remains unknown. A constrained construction inside a direct edge requires its own shared-
 * validator proof scoped to source, edge root, and exact subtree. Nominal and CLR special
 * constraints compose through the shared validators. Open special flags remain declaration
 * authority without a target, but validating a construction which depends on them requires an
 * explicit target profile; implicit by-ref-like eligibility is checked for every admitted
 * construction which might carry a by-ref-like argument. Variant non-interface TypeDefs remain
 * unavailable until delegates are classified separately. Declared graph members, MethodImpls,
 * and carrier shapes outside the bounded grammar remain unavailable.
 */
internal class DotNetRetainedForeignGenericOwnerPhysicalDeclarations private constructor(
    val typeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefReference>,
    val methodDefinitions: List<DotNetGenericOwnerPhysicalMethodDefReference>,
    val directSupertypeEdgeSets: List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
    val directSupertypeConstraintProofs:
            List<DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof>,
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
            target: DotNetTarget? = null,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetRetainedForeignGenericOwnerPhysicalDeclarations> =
            InheritedReceiverBuilder(source, method, receiverSource, target).build()
    }

    private data class RetainedAuxiliaryNominalTypeKind(
        val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        val supportsInlineNull: Boolean,
    )

    /** Retained/raw-authenticated memberless graph; construction substitution stays in the index. */
    private class InheritedReceiverBuilder(
        private val source: DotNetClrImportedMethodSource,
        private val method: DotNetClrMethodDefinition,
        private val receiverSource: DotNetClrImportedTypeSource,
        private val target: DotNetTarget?,
    ) {
        private val selectedMetadata = source.graph.assemblies.map { assembly -> assembly.metadata }
        private val selectedAssemblyBinder = DotNetClrSelectedAssemblyBinder(selectedMetadata)
        private val typeResolver = DotNetClrTypeResolver(selectedAssemblyBinder)
        private val typeHierarchyResolver =
            DotNetClrTypeHierarchyViewResolver(typeResolver)
        private val genericContextResolver =
            DotNetClrGenericParameterContextResolver(typeResolver)
        private val constructedConstraintResolver =
            DotNetClrConstructedTypeConstraintResolver(typeResolver)
        private val auxiliaryNominalTypeDefinitions = linkedMapOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                DotNetGenericOwnerPhysicalTypeDefReference,
                >()
        private val activeAuxiliaryNominalTypeDefinitions = mutableSetOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                >()
        private val genericRowsReservedFor = mutableSetOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                >()
        private var retainedConstraintRowCount = 0
        private var translatedCarrierNodeCount = 0
        private val physicalTypeClassifier by lazy(LazyThreadSafetyMode.NONE) {
            source.graph.physicalCoreTypes?.let { coreTypes ->
                DotNetClrPhysicalTypeClassifier(typeResolver, coreTypes)
            }
        }
        private val nominalConstraintValidator by lazy(LazyThreadSafetyMode.NONE) {
            createNominalConstraintValidator()
        }
        private val specialConstraintValidator by lazy(LazyThreadSafetyMode.NONE) {
            createSpecialConstraintValidator()
        }

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

            val methodOwner = source.declaringHierarchy.type.type
            val receiverIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
            val parentIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(source)
            val parentDescription = methodDeclarations.typeDefinitions.singleOrNull { candidate ->
                candidate.identity == parentIdentity
            } ?: return conflict("retained MethodDef authority omitted its declaring TypeDef")
            if (receiverIdentity == parentIdentity) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val inheritedGraph = when (
                val binding = buildMemberlessInterfaceGraph(
                    receiverSource.declaringHierarchy.type.type,
                    methodOwner,
                    parentIdentity,
                    parentDescription,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetRetainedForeignGenericOwnerPhysicalDeclarations(
                    typeDefinitions = methodDeclarations.typeDefinitions +
                            inheritedGraph.typeDefinitions,
                    methodDefinitions = methodDeclarations.methodDefinitions,
                    directSupertypeEdgeSets = methodDeclarations.directSupertypeEdgeSets +
                            inheritedGraph.directSupertypeEdgeSets,
                    directSupertypeConstraintProofs =
                        inheritedGraph.directSupertypeConstraintProofs,
                )
            )
        }

        private fun buildMemberlessInterfaceGraph(
            receiverType: DotNetClrResolvedTypeDefinition,
            methodOwner: DotNetClrResolvedTypeDefinition,
            parentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            parentDescription: DotNetGenericOwnerPhysicalTypeDefReference,
        ): DotNetGenericOwnerPhysicalBindingResult<InheritedInterfaceGraph> {
            val definitions = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalTypeDefReference,
                    >()
            val edgeSets = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
                    >()
            val constraintProofs = linkedSetOf<
                    DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof,
                    >()
            val active = mutableSetOf<DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr>()
            var edgeCount = 0
            var reachesMethodOwner = false

            fun visit(
                type: DotNetClrResolvedTypeDefinition,
                depth: Int,
            ): DotNetGenericOwnerPhysicalBindingResult<InheritedInterfaceDeclaration> {
                if (depth > MAX_RETAINED_INTERFACE_GRAPH_DEPTH) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val retainedHierarchy = source.graph.hierarchyOrNull(type)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                val identity =
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource, type)
                if (identity in active) {
                    return conflict("retained foreign interface graph contains a cycle")
                }
                definitions[identity]?.let { definition ->
                    return bound(
                        InheritedInterfaceDeclaration(
                            identity,
                            definition,
                            edgeSets.getValue(identity),
                        )
                    )
                }
                if (definitions.size + active.size + auxiliaryNominalTypeDefinitions.size >=
                    MAX_RETAINED_INTERFACE_GRAPH_NODES
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                when (val reservation = reserveGenericRows(type, identity)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return conflict(reservation.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }

                val rawHierarchy = when (
                    val resolution = typeHierarchyResolver.resolve(retainedHierarchy.type)
                ) {
                    is DotNetClrTypeHierarchyViewResolution.Resolved -> resolution.hierarchy
                    is DotNetClrTypeHierarchyViewResolution.Invalid ->
                        return conflict("retained foreign interface has an invalid raw hierarchy")
                }
                if (rawHierarchy != retainedHierarchy) {
                    return conflict("retained foreign interface hierarchy contradicts raw metadata")
                }
                val context = when (
                    val resolution = genericContextResolver.resolve(retainedHierarchy.type)
                ) {
                    is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                    is DotNetClrGenericParameterContextResolution.Invalid ->
                        return conflict("retained foreign interface has an invalid generic context")
                }
                if (context.method != null || context.declaringType != retainedHierarchy.type) {
                    return conflict("retained foreign interface changed its declaring TypeDef")
                }
                if (!hasSupportedMemberlessInterfaceShape(type, rawHierarchy, context)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val parameters = when (
                    val translation = translateTypeParameters(
                        context,
                        identity,
                        type.definition,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        allowsVariantParameters = true,
                        subject = "retained foreign interface",
                    )
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return translation
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val definition = DotNetGenericOwnerPhysicalTypeDefReference(
                    identity = identity,
                    genericParameters = parameters,
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                )

                active += identity
                val edges = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>()
                for (implementation in rawHierarchy.interfaces) {
                    if (edgeCount >= MAX_RETAINED_INTERFACE_GRAPH_EDGES) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    edgeCount++
                    val targetType = implementation.interfaceType.type
                    val targetIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                    val targetDescription: DotNetGenericOwnerPhysicalTypeDefReference
                    if (targetType.hasSameIdentityAs(methodOwner)) {
                        targetIdentity = parentIdentity
                        targetDescription = parentDescription
                        reachesMethodOwner = true
                    } else {
                        val target = when (val binding = visit(targetType, depth + 1)) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                        targetIdentity = target.identity
                        targetDescription = target.definition
                    }

                    val targetArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                    val constrainedArgumentConstructions = linkedSetOf<
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                            >()
                    for (argument in implementation.interfaceType.arguments) {
                        when (val translation = translateInheritedCarrier(
                            argument,
                            identity,
                            context,
                            type.definition,
                            "retained foreign InterfaceImpl",
                            constrainedConstructions = constrainedArgumentConstructions,
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
                    val targetConstruction =
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                            targetIdentity,
                            targetArguments,
                        )
                    when (val validation = validateConstructionConstraints(
                        implementation.interfaceType,
                        context,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    if (targetDescription.genericParameters.any { parameter ->
                            !parameter.isUnconstrained
                        }
                    ) {
                        constraintProofs +=
                            DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof(
                                identity,
                                targetConstruction,
                            )
                    }
                    for (construction in constrainedArgumentConstructions) {
                        constraintProofs +=
                            DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof(
                                identity,
                                targetConstruction,
                                construction,
                            )
                    }
                    edges += DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                        DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                        targetConstruction,
                    )
                }
                if (edges.size != edges.toSet().size) {
                    return conflict("retained interface contains duplicate physical InterfaceImpl edges")
                }
                active -= identity
                val edgeSet = DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(identity, edges)
                definitions[identity] = definition
                edgeSets[identity] = edgeSet
                return bound(InheritedInterfaceDeclaration(identity, definition, edgeSet))
            }

            when (val receiver = visit(receiverType, 0)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return receiver
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (!reachesMethodOwner) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val allDefinitions = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalTypeDefReference,
                    >()
            for (candidates in listOf(definitions, auxiliaryNominalTypeDefinitions)) {
                for (entry in candidates) {
                    val identity = entry.key
                    val candidate = entry.value
                    val existing = allDefinitions[identity]
                    if (existing != null && existing.conflictsWith(candidate)) {
                        return conflict(
                            "retained auxiliary TypeDef contradicts inherited graph authority",
                        )
                    }
                    if (existing == null &&
                        allDefinitions.size >= MAX_RETAINED_INTERFACE_GRAPH_NODES
                    ) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    allDefinitions.putIfAbsent(identity, candidate)
                }
            }
            return bound(
                InheritedInterfaceGraph(
                    typeDefinitions = allDefinitions.values.toList(),
                    directSupertypeEdgeSets = edgeSets.values.toList(),
                    directSupertypeConstraintProofs = constraintProofs.toList(),
                )
            )
        }

        private fun reserveGenericRows(
            type: DotNetClrResolvedTypeDefinition,
            identity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
        ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
            if (identity in genericRowsReservedFor) return bound(Unit)
            val parameterHandles = linkedSetOf<DotNetClrMetadataHandle>()
            var parameterRowCount = 0
            for (parameter in type.assembly.genericParameterDefinitions) {
                if (parameter.owner != type.definition.handle) continue
                if (parameterRowCount >= MAX_RETAINED_INTERFACE_BINDERS_PER_TYPE) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                parameterRowCount++
                parameterHandles += parameter.handle
            }
            var constraintRowsForType = 0
            for (constraint in type.assembly.genericParameterConstraints) {
                if (constraint.owner !in parameterHandles) continue
                if (retainedConstraintRowCount + constraintRowsForType >=
                    MAX_RETAINED_INTERFACE_GRAPH_CONSTRAINT_ROWS
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                constraintRowsForType++
            }
            genericRowsReservedFor += identity
            retainedConstraintRowCount += constraintRowsForType
            return bound(Unit)
        }

        private fun hasSupportedMemberlessInterfaceShape(
            type: DotNetClrResolvedTypeDefinition,
            hierarchy: DotNetClrResolvedTypeHierarchy,
            context: DotNetClrResolvedGenericParameterContext,
        ): Boolean {
            val definition = type.definition
            val genericArity = context.typeParameters.size
            if (genericArity > MAX_RETAINED_INTERFACE_BINDERS_PER_TYPE) return false
            val openArguments = List(genericArity) { index ->
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    index,
                )
            }
            return hierarchy.type.type.hasSameIdentityAs(type) &&
                    hierarchy.type.arguments == openArguments &&
                    definition.isInterface &&
                    definition.isAbstract &&
                    !definition.isSealed &&
                    definition.declaringType == null &&
                    definition.visibility == DotNetClrTypeVisibility.PUBLIC &&
                    definition.baseType == null &&
                    hierarchy.baseType == null &&
                    type.assembly.methodImplementations.none { implementation ->
                        implementation.implementingType == definition.handle
                    } &&
                    type.assembly.methodDefinitions.none { candidate ->
                        candidate.declaringType == definition.handle
                    } &&
                    type.assembly.propertyDefinitions.none { candidate ->
                        candidate.declaringType == definition.handle
                    } &&
                    type.assembly.fieldDefinitions.none { candidate ->
                        candidate.declaringType == definition.handle
                    }
        }

        private fun validateConstructionConstraints(
            target: DotNetClrResolvedTypeView,
            sourceContext: DotNetClrResolvedGenericParameterContext,
        ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
            val constraints = when (val resolution = constructedConstraintResolver.resolve(target)) {
                is DotNetClrConstructedTypeConstraintResolution.Resolved -> resolution.constraints
                is DotNetClrConstructedTypeConstraintResolution.Invalid ->
                    return conflict("retained InterfaceImpl has invalid GenericParam constraints")
            }
            if (constraints.parameters.any { binding -> binding.constraints.isNotEmpty() }) {
                val validator = when (val services = nominalConstraintValidator) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> services.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return services
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val validation = validator.validate(constraints, sourceContext)
                for (parameter in validation.parameters) {
                    for (constraint in parameter.constraints) {
                        when (constraint.satisfaction) {
                            DotNetClrNominalConstraintSatisfaction.Satisfied -> Unit
                            DotNetClrNominalConstraintSatisfaction.Violated ->
                                return conflict(
                                    "retained InterfaceImpl violates a target GenericParam constraint",
                                )
                            is DotNetClrNominalConstraintSatisfaction.Unsupported ->
                                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                            is DotNetClrNominalConstraintSatisfaction.InvalidAssignability ->
                                return conflict(
                                    "retained InterfaceImpl constraint assignability is invalid",
                                )
                        }
                    }
                }
            }
            if (constraints.requiresSpecialConstraintValidation(sourceContext)) {
                val specialValidator = when (val services = specialConstraintValidator) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> services.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return services
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val specialValidation = specialValidator.validate(constraints, sourceContext)
                for (parameter in specialValidation.parameters) {
                    for (constraint in parameter.constraints) {
                        when (constraint.satisfaction) {
                            DotNetClrSpecialConstraintSatisfaction.Satisfied -> Unit
                            is DotNetClrSpecialConstraintSatisfaction.Violated ->
                                return conflict(
                                    "retained InterfaceImpl violates a target special GenericParam constraint",
                                )
                            is DotNetClrSpecialConstraintSatisfaction.Unsupported ->
                                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                            is DotNetClrSpecialConstraintSatisfaction.InvalidClassification ->
                                return conflict(
                                    "retained InterfaceImpl special-constraint classification is invalid",
                                )
                        }
                    }
                }
            }
            return bound(Unit)
        }

        private fun DotNetClrResolvedConstructedTypeConstraints.requiresSpecialConstraintValidation(
            sourceContext: DotNetClrResolvedGenericParameterContext,
        ): Boolean = parameters.any { binding ->
            binding.parameter.hasReferenceTypeConstraint ||
                    binding.parameter.hasNotNullableValueTypeConstraint ||
                    binding.parameter.hasDefaultConstructorConstraint ||
                    binding.parameter.allowsByRefLike ||
                    binding.argument.mayBeByRefLike(sourceContext)
        }

        private fun DotNetClrResolvedTypeSignature.mayBeByRefLike(
            sourceContext: DotNetClrResolvedGenericParameterContext,
        ): Boolean = when (this) {
            is DotNetClrResolvedTypeSignature.GenericParameter ->
                sourceContext.binding(this)?.parameter?.allowsByRefLike == true
            is DotNetClrResolvedTypeSignature.Named -> isValueType
            is DotNetClrResolvedTypeSignature.GenericInstance -> genericType.isValueType
            is DotNetClrResolvedTypeSignature.Modified ->
                unmodifiedType.mayBeByRefLike(sourceContext)
            else -> false
        }

        private fun createNominalConstraintValidator():
                DotNetGenericOwnerPhysicalBindingResult<DotNetClrNominalConstraintValidator> {
            val coreTypes = source.graph.physicalCoreTypes
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val classifier = physicalTypeClassifier
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val coreAssembly = coreTypes.systemValueType.assembly
            val primitiveTypes = when (
                val resolution = DotNetClrPrimitiveTypeCatalogResolver(typeResolver)
                    .resolve(coreAssembly)
            ) {
                is DotNetClrPrimitiveTypeCatalogResolution.Resolved -> resolution.catalog
                is DotNetClrPrimitiveTypeCatalogResolution.Unresolved ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val arrayRuntimeTypes = when (
                val resolution = DotNetClrArrayRuntimeTypesResolver(typeResolver)
                    .resolve(coreAssembly)
            ) {
                is DotNetClrArrayRuntimeTypesResolution.Resolved -> resolution.types
                is DotNetClrArrayRuntimeTypesResolution.Unresolved ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                is DotNetClrArrayRuntimeTypesResolution.Invalid ->
                    return conflict("selected CLR array runtime metadata is invalid")
            }
            val delegateRuntimeTypes = when (val resolution = resolveDelegateRuntimeTypes()) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> resolution.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return resolution
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return bound(
                DotNetClrNominalConstraintValidator(
                    typeResolver,
                    primitiveTypes,
                    classifier,
                    arrayRuntimeTypes,
                    delegateRuntimeTypes,
                )
            )
        }

        private fun resolveDelegateRuntimeTypes():
                DotNetGenericOwnerPhysicalBindingResult<DotNetClrDelegateRuntimeTypes> {
            val coreAssembly = source.graph.physicalCoreTypes?.systemValueType?.assembly
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            return when (
                val resolution = DotNetClrDelegateRuntimeTypesResolver(typeResolver)
                    .resolve(coreAssembly)
            ) {
                is DotNetClrDelegateRuntimeTypesResolution.Resolved -> bound(resolution.types)
                is DotNetClrDelegateRuntimeTypesResolution.Unresolved ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
                is DotNetClrDelegateRuntimeTypesResolution.Invalid ->
                    conflict("selected CLR delegate runtime metadata is invalid")
            }
        }

        private fun createSpecialConstraintValidator():
                DotNetGenericOwnerPhysicalBindingResult<DotNetClrSpecialConstraintValidator> {
            val selectedTarget = target
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val coreTypes = source.graph.physicalCoreTypes
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val classifier = physicalTypeClassifier
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val primitiveTypes = when (
                val resolution = DotNetClrPrimitiveTypeCatalogResolver(typeResolver)
                    .resolve(coreTypes.systemValueType.assembly)
            ) {
                is DotNetClrPrimitiveTypeCatalogResolution.Resolved -> resolution.catalog
                is DotNetClrPrimitiveTypeCatalogResolution.Unresolved ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val customAttributeCoreTypes = resolveDotNetClrCustomAttributeCoreTypes(
                selectedMetadata,
                typeResolver,
            ) ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val serializedTypeResolver = DotNetClrSerializedTypeResolver(
                typeResolver,
                DotNetClrSerializedAssemblyBinder {
                        _,
                        unqualifiedContextAssembly,
                        assemblyName,
                    ->
                    if (assemblyName == null) {
                        unqualifiedContextAssembly
                    } else {
                        selectedAssemblyBinder.bind(assemblyName)
                    }
                },
            )
            val attributeDecoder = DotNetClrCustomAttributeDecoder(
                typeResolver,
                serializedTypeResolver,
                customAttributeCoreTypes,
            )
            val isByRefLikeAttribute = resolveDotNetClrSystemType(
                selectedMetadata,
                typeResolver,
                "System.Runtime.CompilerServices",
                "IsByRefLikeAttribute",
            )
            return bound(
                DotNetClrSpecialConstraintValidator(
                    selectedTarget,
                    DotNetClrByRefLikeClassifier(
                        classifier,
                        attributeDecoder,
                        isByRefLikeAttribute,
                    ),
                    coreTypes,
                    primitiveTypes,
                )
            )
        }

        private fun translateTypeParameters(
            context: DotNetClrResolvedGenericParameterContext,
            declaringIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            declaringType: DotNetClrTypeDefinition,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            allowsVariantParameters: Boolean,
            subject: String,
        ): DotNetGenericOwnerPhysicalBindingResult<
                List<DotNetGenericOwnerPhysicalGenericParameterReference>,
                > {
            val translated = ArrayList<DotNetGenericOwnerPhysicalGenericParameterReference>(
                context.typeParameters.size,
            )
            for (index in context.typeParameters.indices) {
                val binding = context.typeParameters[index]
                if (binding.kind != DotNetClrGenericParameterKind.TYPE ||
                    binding.parameter.owner != declaringType.handle ||
                    binding.parameter.number != index
                ) {
                    return conflict("$subject GenericParam changed binder or numbering")
                }
                val attributes = binding.parameter.attributes
                if (attributes and GENERIC_PARAMETER_VARIANCE_MASK ==
                    GENERIC_PARAMETER_VARIANCE_MASK
                ) {
                    return conflict("$subject GenericParam declares incompatible variance")
                }
                if (binding.parameter.hasReferenceTypeConstraint &&
                    binding.parameter.hasNotNullableValueTypeConstraint
                ) {
                    return conflict(
                        "$subject GenericParam requires reference and value arguments",
                    )
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
                if (category == DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS &&
                    variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT &&
                    !allowsVariantParameters
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                if (category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE &&
                    variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                ) {
                    return conflict("$subject value-type GenericParam declares variance")
                }
                val constraints = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (constraint in binding.constraints) {
                    val carrier = when (val type = constraint.type) {
                        is DotNetClrResolvedGenericConstraintType.Nominal ->
                            translateExactNominalCarrier(type.type)
                        is DotNetClrResolvedGenericConstraintType.Specification ->
                            translateInheritedCarrier(
                                type.type,
                                declaringIdentity,
                                context,
                                declaringType,
                                "$subject GenericParam constraint",
                                depth = 1,
                            )
                    }
                    when (carrier) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            constraints += carrier.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return carrier
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                if (constraints.size != constraints.toSet().size) {
                    return conflict("$subject GenericParam repeats a constraint carrier")
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
            return bound(translated.toList())
        }

        private fun translateExactNominalCarrier(
            type: DotNetClrResolvedTypeDefinition,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
            val description = when (
                val retention = retainExactAuxiliaryNominalTypeDefinition(
                    type,
                    encodedAsValueType = null,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> retention.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return retention
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (description.genericArity != 0) {
                // A direct TypeDef/TypeRef constraint carries no generic arguments. Treating it
                // as an open construction would invent verifier-visible type arguments.
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return bound(
                DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                    description.identity,
                    emptyList(),
                )
            )
        }

        private fun retainExactAuxiliaryNominalTypeDefinition(
            type: DotNetClrResolvedTypeDefinition,
            encodedAsValueType: Boolean?,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalTypeDefReference> {
            val identity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource, type)
            auxiliaryNominalTypeDefinitions[identity]?.let { existing ->
                if (encodedAsValueType != null &&
                    (existing.category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE) !=
                    encodedAsValueType
                ) {
                    return conflict(
                        "retained nominal carrier signature kind contradicts its TypeDef",
                    )
                }
                return bound(existing)
            }
            if (auxiliaryNominalTypeDefinitions.size +
                activeAuxiliaryNominalTypeDefinitions.size >=
                MAX_RETAINED_INTERFACE_GRAPH_NODES
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (!activeAuxiliaryNominalTypeDefinitions.add(identity)) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val result = retainExactAuxiliaryNominalTypeDefinitionWhileActive(
                type,
                identity,
                encodedAsValueType,
            )
            check(activeAuxiliaryNominalTypeDefinitions.remove(identity)) {
                "retained nominal carrier lost its active TypeDef"
            }
            return result
        }

        private fun retainExactAuxiliaryNominalTypeDefinitionWhileActive(
            type: DotNetClrResolvedTypeDefinition,
            identity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            encodedAsValueType: Boolean?,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalTypeDefReference> {
            when (val reservation = reserveGenericRows(type, identity)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return reservation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val retainedHierarchy = source.graph.hierarchyOrNull(type)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val rawHierarchy = when (
                val resolution = typeHierarchyResolver.resolve(retainedHierarchy.type)
            ) {
                is DotNetClrTypeHierarchyViewResolution.Resolved -> resolution.hierarchy
                is DotNetClrTypeHierarchyViewResolution.Invalid ->
                    return conflict("retained nominal carrier has an invalid raw hierarchy")
            }
            if (rawHierarchy != retainedHierarchy) {
                return conflict(
                    "retained nominal carrier hierarchy contradicts raw metadata",
                )
            }
            val context = when (
                val resolution = genericContextResolver.resolve(retainedHierarchy.type)
            ) {
                is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
                is DotNetClrGenericParameterContextResolution.Invalid ->
                    return conflict("retained nominal carrier has an invalid generic context")
            }
            if (context.method != null || context.declaringType != retainedHierarchy.type) {
                return conflict("retained nominal carrier changed its declaring TypeDef")
            }
            val definition = type.definition
            val openArguments = List(context.typeParameters.size) { index ->
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    index,
                )
            }
            if (!retainedHierarchy.type.type.hasSameIdentityAs(type) ||
                retainedHierarchy.type.arguments != openArguments ||
                definition.declaringType != null ||
                definition.visibility != DotNetClrTypeVisibility.PUBLIC
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val typeKind = when {
                definition.isInterface -> {
                    if (encodedAsValueType == true) {
                        return conflict(
                            "retained nominal interface is encoded as a value type",
                        )
                    }
                    if (!definition.isAbstract || definition.isSealed ||
                        definition.baseType != null || rawHierarchy.baseType != null
                    ) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    RetainedAuxiliaryNominalTypeKind(
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        supportsInlineNull = false,
                    )
                }
                else -> when (val classification = classifyAuxiliaryNominalType(
                    type,
                    openArguments,
                    encodedAsValueType,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> classification.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return classification
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            val allowsVariantParameters = when (val allowance = variantParameterAllowance(
                context,
                typeKind.category,
                retainedHierarchy,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> allowance.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return allowance
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val parameters = when (val translation = translateTypeParameters(
                context,
                identity,
                definition,
                typeKind.category,
                allowsVariantParameters,
                "retained auxiliary nominal TypeDef",
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> translation.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return translation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val description = DotNetGenericOwnerPhysicalTypeDefReference(
                identity,
                genericParameters = parameters,
                category = typeKind.category,
                supportsInlineNull = typeKind.supportsInlineNull,
            )
            if (auxiliaryNominalTypeDefinitions.size >=
                MAX_RETAINED_INTERFACE_GRAPH_NODES
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            auxiliaryNominalTypeDefinitions[identity] = description
            return bound(description)
        }

        private fun variantParameterAllowance(
            context: DotNetClrResolvedGenericParameterContext,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            hierarchy: DotNetClrResolvedTypeHierarchy,
        ): DotNetGenericOwnerPhysicalBindingResult<Boolean> {
            if (category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE) {
                return bound(true)
            }
            if (category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS ||
                context.typeParameters.none { binding ->
                    val variance = binding.parameter.attributes and
                            GENERIC_PARAMETER_VARIANCE_MASK
                    variance != 0 && variance != GENERIC_PARAMETER_VARIANCE_MASK
                }
            ) {
                return bound(false)
            }
            val runtimeTypes = when (val resolution = resolveDelegateRuntimeTypes()) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> resolution.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return resolution
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return when (
                val classification = DotNetClrDelegateTypeClassifier(runtimeTypes)
                    .classify(hierarchy)
            ) {
                DotNetClrDelegateTypeClassification.Delegate -> bound(true)
                DotNetClrDelegateTypeClassification.NotDelegate -> conflict(
                    "retained variant non-interface TypeDef is not a CLR delegate",
                )
                is DotNetClrDelegateTypeClassification.Invalid -> conflict(
                    when (classification.failure) {
                        DotNetClrDelegateTypeFailure.DELEGATE_IS_NOT_SEALED ->
                            "retained CLR delegate TypeDef is not sealed"
                    }
                )
            }
        }

        private fun classifyAuxiliaryNominalType(
            type: DotNetClrResolvedTypeDefinition,
            openArguments: List<DotNetClrResolvedTypeSignature>,
            encodedAsValueType: Boolean?,
        ): DotNetGenericOwnerPhysicalBindingResult<RetainedAuxiliaryNominalTypeKind> {
            val classifier = physicalTypeClassifier
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable

            fun signature(isValueType: Boolean): DotNetClrResolvedTypeSignature =
                if (openArguments.isEmpty()) {
                    DotNetClrResolvedTypeSignature.Named(type, isValueType)
                } else {
                    DotNetClrResolvedTypeSignature.GenericInstance(
                        DotNetClrResolvedTypeSignature.Named(type, isValueType),
                        openArguments,
                    )
                }

            var classification = classifier.classify(
                signature(encodedAsValueType ?: false),
            )
            if (encodedAsValueType == null &&
                classification is DotNetClrPhysicalTypeClassification.Invalid &&
                classification.definitionIsValueType == true
            ) {
                // A TypeDef/TypeRef GenericParamConstraint row has no signature-side class/value
                // marker. Retry with the kind established by the selected TypeDef hierarchy;
                // actual signatures never receive this inference path.
                classification = classifier.classify(signature(isValueType = true))
            }
            return when (classification) {
                is DotNetClrPhysicalTypeClassification.Classified -> bound(
                    when (classification.kind) {
                        DotNetClrPhysicalTypeKind.REFERENCE ->
                            RetainedAuxiliaryNominalTypeKind(
                                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                                supportsInlineNull = false,
                            )
                        DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE ->
                            RetainedAuxiliaryNominalTypeKind(
                                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                                supportsInlineNull = false,
                            )
                        DotNetClrPhysicalTypeKind.NULLABLE_VALUE ->
                            RetainedAuxiliaryNominalTypeKind(
                                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                                supportsInlineNull = true,
                            )
                    }
                )
                is DotNetClrPhysicalTypeClassification.Unsupported ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
                is DotNetClrPhysicalTypeClassification.Invalid -> conflict(
                    "retained nominal carrier has an invalid physical kind " +
                            "(${classification.failure})",
                )
                is DotNetClrPhysicalTypeClassification.InvalidHierarchy ->
                    // The shared classifier groups invalid, unsupported, and resource-limited
                    // assignability results here. None proves a nominal carrier kind.
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }

        private fun translateInheritedCarrier(
            type: DotNetClrResolvedTypeSignature,
            declaringIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            context: DotNetClrResolvedGenericParameterContext,
            declaringType: DotNetClrTypeDefinition,
            subject: String,
            constrainedConstructions:
                    MutableCollection<DotNetGenericOwnerSymbolicCarrierReference.Constructed>? = null,
            depth: Int = 1,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
            if (depth > MAX_RETAINED_INTERFACE_CARRIER_DEPTH ||
                translatedCarrierNodeCount >= MAX_RETAINED_INTERFACE_CARRIER_NODES
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            translatedCarrierNodeCount++
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
                        ?: return conflict("$subject escaped its declaring binder")
                    if (type.kind != DotNetClrGenericParameterKind.TYPE ||
                        binding.parameter.owner != declaringType.handle ||
                        binding.parameter.number != type.index
                    ) {
                        return conflict("$subject changed its declaring binder")
                    }
                    bound(
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter
                            .unboundTypeParameterReference(declaringIdentity, type.index),
                    )
                }
                is DotNetClrResolvedTypeSignature.SzArray -> when (
                    val element = translateInheritedCarrier(
                        type.elementType,
                        declaringIdentity,
                        context,
                        declaringType,
                        subject,
                        constrainedConstructions,
                        depth + 1,
                    )
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> bound(
                        DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                    )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> element
                }
                is DotNetClrResolvedTypeSignature.Named ->
                    when (val retention = retainExactAuxiliaryNominalTypeDefinition(
                        type.type,
                        type.isValueType,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            bound<DotNetGenericOwnerSymbolicCarrierReference>(
                                DotNetGenericOwnerSymbolicCarrierReference.Constructed
                                    .unboundTypeReference(
                                        retention.value.identity,
                                        emptyList(),
                                    )
                            )
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            conflict(retention.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                is DotNetClrResolvedTypeSignature.GenericInstance -> {
                    val description = when (
                        val retention = retainExactAuxiliaryNominalTypeDefinition(
                            type.genericType.type,
                            type.genericType.isValueType,
                        )
                    ) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> retention.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return retention
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    if (type.arguments.size != description.genericArity) {
                        return conflict("$subject contradicts its nominal TypeDef arity")
                    }
                    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                    for (argument in type.arguments) {
                        when (val translation = translateInheritedCarrier(
                            argument,
                            declaringIdentity,
                            context,
                            declaringType,
                            subject,
                            constrainedConstructions,
                            depth + 1,
                        )) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                                arguments += translation.value
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                                return translation
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                                return translation
                        }
                    }
                    val construction =
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed
                            .unboundTypeReference(description.identity, arguments)
                    when (val validation = validateConstructionConstraints(
                        DotNetClrResolvedTypeView(type.genericType.type, type.arguments),
                        context,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    if (description.genericParameters.any { parameter ->
                            !parameter.isUnconstrained
                        }
                    ) {
                        // Only an exact InterfaceImpl edge currently owns a scope in which this
                        // construction can be validated and recorded. Other metadata positions
                        // must add their own authority key instead of borrowing an edge proof.
                        val proofSink = constrainedConstructions
                            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        proofSink += construction
                    }
                    bound(construction)
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

        private fun <T> bound(value: T): DotNetGenericOwnerPhysicalBindingResult.Bound<T> =
            DotNetGenericOwnerPhysicalBindingResult.Bound(value)

        private fun conflict(
            reason: String,
        ): DotNetGenericOwnerPhysicalBindingResult.Conflict =
            DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)

        private companion object {
            const val GENERIC_PARAMETER_VARIANCE_MASK = 0x0003
            const val SUPPORTED_GENERIC_PARAMETER_ATTRIBUTES = 0x003f
            const val MAX_RETAINED_INTERFACE_GRAPH_DEPTH =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_TYPE_DEPTH
            const val MAX_RETAINED_INTERFACE_GRAPH_NODES =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE
            const val MAX_RETAINED_INTERFACE_GRAPH_EDGES =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE
            const val MAX_RETAINED_INTERFACE_BINDERS_PER_TYPE =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE
            const val MAX_RETAINED_INTERFACE_GRAPH_CONSTRAINT_ROWS =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE
            const val MAX_RETAINED_INTERFACE_CARRIER_DEPTH =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_TYPE_DEPTH
            const val MAX_RETAINED_INTERFACE_CARRIER_NODES =
                DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_TYPE_NODES
        }

        private data class InheritedInterfaceDeclaration(
            val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            val definition: DotNetGenericOwnerPhysicalTypeDefReference,
            val edgeSet: DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
        )

        private data class InheritedInterfaceGraph(
            val typeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefReference>,
            val directSupertypeEdgeSets: List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
            val directSupertypeConstraintProofs:
                    List<DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof>,
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
                    directSupertypeConstraintProofs = emptyList(),
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
