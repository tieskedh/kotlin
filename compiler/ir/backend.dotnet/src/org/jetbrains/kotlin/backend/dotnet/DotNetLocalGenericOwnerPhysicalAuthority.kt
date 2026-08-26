/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.defaultType

/** Bounded compilation-local role of one selected CLR TypeDef. */
internal enum class DotNetLocalGenericOwnerPhysicalTypeRole {
    GENERIC_CLASS,
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY,
}

/**
 * One physical TypeDef selected by lowering, plus only the diagnostic name needed by the shadow.
 * The name does not participate in identity, ancestry, construction, or placement decisions.
 */
internal data class DotNetLocalGenericOwnerPhysicalTypeInput(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val logicalOwnerName: String,
    val genericArity: Int,
    val role: DotNetLocalGenericOwnerPhysicalTypeRole,
) {
    init {
        require(logicalOwnerName.isNotEmpty() && when (role) {
            DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS ->
                identity.view == null && genericArity > 0
            DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE ->
                identity.view == DotNetGenericInterfaceView.DECLARED && genericArity > 0
            DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY ->
                identity.view == null && genericArity == 0
        }) { "a local physical TypeDef input has an incoherent role, identity, or arity" }
    }

    val category: DotNetGenericOwnerPhysicalNamedTypeCategory
        get() = when (role) {
            DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS ->
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
            DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
            DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
            -> DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
        }

    fun asReference() = DotNetGenericOwnerPhysicalTypeDefReference(
        identity = identity,
        genericArity = genericArity,
        category = category,
    )
}

/** One admitted InterfaceImpl target expressed only in the source TypeDef's physical parameters. */
internal data class DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput(
    val target: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val sourceParameterIndices: List<Int>,
) {
    init {
        require(sourceParameterIndices.all { index -> index >= 0 }) {
            "a local physical InterfaceImpl edge requires non-negative source-parameter mappings"
        }
    }
}

/**
 * Complete bounded BaseType/InterfaceImpl selection for one local generic class.
 *
 * The current slice admits only the canonical System.Object base. Absence of this record is
 * unavailable authority; it must never be interpreted as a recorded empty interface list.
 */
internal data class DotNetLocalGenericOwnerPhysicalClassEdgePlan(
    val source: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val interfaces: List<DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput>,
) {
    init {
        require(source.view == null && interfaces.distinct().size == interfaces.size) {
            "a local physical class edge plan requires one source and unique InterfaceImpl rows"
        }
    }
}

/**
 * One forwarding MethodDef selected while a concrete class is bound to a reified interface's
 * semantic capability. The relation is recorded at creation time: later authority must not
 * rediscover this member from a generated name or IR origin.
 */
internal data class DotNetLocalGenericOwnerPhysicalInterfaceCapabilityDispatcherSelection(
    val logicalInterfaceMember: IrSimpleFunctionSymbol,
    val implementationMember: IrSimpleFunctionSymbol,
    val interfaceCapabilityMember: IrSimpleFunctionSymbol,
    val dispatcher: IrSimpleFunctionSymbol,
)

/** The four physical TypeDefs in the first complete implementation-family liveness slice. */
internal enum class DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind {
    NATURAL_INTERFACE,
    INTERFACE_SEMANTIC_CAPABILITY,
    IMPLEMENTATION_CLASS,
    CLASS_SEMANTIC_CAPABILITY,
}

/** Verifier-visible declaration-site variance on one CLR GenericParam row. */
internal enum class DotNetGenericOwnerPhysicalTypeParameterVariance {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

/** Complete bounded GenericParam metadata attached to one selected TypeDef. */
internal data class DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference(
    val variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
    val constraints: List<DotNetGenericOwnerSymbolicCarrierReference>,
) {
    init {
        require(constraints.size == constraints.toSet().size) {
            "a complete local emission-family GenericParam cannot repeat a constraint row"
        }
    }
}

/** Every MethodDef structurally owned by the admitted direct-producer implementation family. */
internal enum class DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind {
    NATURAL_INTERFACE_SLOT,
    INTERFACE_SEMANTIC_CAPABILITY_SLOT,
    IMPLEMENTATION_TYPED_ENTRY,
    CLASS_SEMANTIC_CAPABILITY_SLOT,
    CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
    INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
}

/** Every explicit MethodImpl row structurally owned by that same implementation family. */
internal enum class DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind {
    CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
    INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
}

internal data class DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
    val kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
    /** The concrete IR MethodDef which final emission must observe. */
    val emittedFunction: IrSimpleFunctionSymbol,
    /** The lowering-selected logical MethodDef identity carried by that emission instance. */
    val identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
)

internal data class DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference(
    val kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind,
    val implementingType: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val body: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val declarationOwner: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    val declaration: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
)

/** Selection handles needed to bind one complete family only after BOUND declarations exist. */
internal data class DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput(
    val logicalMember: IrSimpleFunctionSymbol,
    val implementationMember: IrSimpleFunctionSymbol,
    val types: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            >,
    /** Every logical identity which lowering expects to alias each one physical TypeDef. */
    val typeAliases: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            List<DotNetGenericOwnerPhysicalTypeDefIdentity.Local>,
            >,
    val typeParameters: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            List<DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference>,
            >,
    val methods: List<DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput>,
    val methodImpls: List<DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference>,
) {
    init {
        require(types.keys == DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.entries.toSet() &&
                typeAliases.keys == types.keys && typeParameters.keys == types.keys &&
                methods.map { method -> method.kind }.toSet() ==
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.entries.toSet() &&
                methods.size == DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.entries.size &&
                methods.map { method -> method.emittedFunction }.toSet().size == methods.size &&
                methods.map { method -> method.identity }.toSet().size == methods.size &&
                methodImpls.map { methodImpl -> methodImpl.kind }.toSet() ==
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.entries.toSet() &&
                methodImpls.size == DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.entries.size) {
            "a complete local emission-family input requires every bounded physical row exactly once"
        }
    }
}

/**
 * Opaque BOUND manifest for one complete direct-producer implementation family.
 *
 * "Complete" is deliberately scoped to the selected logical member family. Other Kotlin members
 * on the implementation TypeDef are outside this structural projection, while all four selected
 * TypeDefs' direct edges and every MethodDef/MethodImpl belonging to this family are included.
 */
internal class DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily private constructor(
    val logicalMember: IrSimpleFunctionSymbol,
    val implementationMember: IrSimpleFunctionSymbol,
    val types: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            >,
    val typeAliases: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            List<DotNetGenericOwnerPhysicalTypeDefIdentity.Local>,
            >,
    val typeParameters: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            List<DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference>,
            >,
    val methods: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
            Pair<IrSimpleFunctionSymbol, DotNetGenericOwnerPhysicalMethodDefReference>,
            >,
    val methodImpls: Map<
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference,
            >,
) {
    companion object {
        fun bindOrError(
            input: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput,
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            inputsByIdentity: Map<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                    DotNetLocalGenericOwnerPhysicalTypeInput,
                    >,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily> {
            val expectedRoles = mapOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to
                        DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to
                        DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to
                        DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to
                        DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
            )
            if (input.types.size != input.types.values.toSet().size || input.types.any { entry ->
                    inputsByIdentity[entry.value]?.role != expectedRoles.getValue(entry.key)
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family requires four distinct BOUND TypeDefs with exact roles",
                )
            }
            if (input.typeAliases.any { entry ->
                    val kind = entry.key
                    val aliases = entry.value
                    val primary = input.types.getValue(kind)
                    aliases.isEmpty() || aliases.distinctBy { alias -> alias.view }.size != aliases.size ||
                            aliases.none(primary::sameLocalTypeIdentityAs) ||
                            aliases.any { alias -> alias.owner !== primary.owner } ||
                            when (kind) {
                                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE ->
                                    aliases.mapTo(linkedSetOf()) { alias -> alias.view } != setOf(
                                        DotNetGenericInterfaceView.CANONICAL,
                                        DotNetGenericInterfaceView.DECLARED,
                                    )
                                else -> aliases.size != 1
                            }
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family has an incoherent physical TypeDef alias set",
                )
            }
            if (input.typeParameters.any { entry ->
                    entry.value.size != inputsByIdentity.getValue(input.types.getValue(entry.key)).genericArity
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family has an incoherent physical GenericParam set",
                )
            }
            val methods = linkedMapOf<
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
                    Pair<IrSimpleFunctionSymbol, DotNetGenericOwnerPhysicalMethodDefReference>,
            >()
            for (method in input.methods) {
                val reference = declarations.methodDescriptionOrNull(method.identity)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                methods[method.kind] = method.emittedFunction to reference
            }
            val expectedMethodIdentities = mapOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            input.logicalMember,
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            methods.getValue(
                                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                                    .INTERFACE_SEMANTIC_CAPABILITY_SLOT,
                            ).first,
                            role = null,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            input.implementationMember,
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            methods.getValue(
                                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                                    .CLASS_SEMANTIC_CAPABILITY_SLOT,
                            ).first,
                            role = null,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            input.implementationMember,
                            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                    .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER to
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                            methods.getValue(
                                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                                    .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                            ).first,
                            role = null,
                        ),
            )
            if (methods.any { entry ->
                    val identity = entry.value.second.identity as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local
                        ?: return@any true
                    !identity.sameLocalMethodIdentityAs(expectedMethodIdentities.getValue(entry.key))
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family assigned one MethodDef an incoherent physical identity",
                )
            }
            if (methods.getValue(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT,
                ).first !== input.logicalMember || methods.getValue(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
                ).first !== input.implementationMember
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family assigned a source MethodDef to the wrong emission instance",
                )
            }
            val expectedDeclaringTypes = mapOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
                        ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER to
                        input.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
                        ),
            )
            if (methods.any { entry -> entry.value.second.declaringType != expectedDeclaringTypes.getValue(entry.key) }) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family assigned one MethodDef to the wrong physical owner",
                )
            }
            if (methods.values.any { selected -> selected.second.signature.genericArity != 0 }) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the current complete local emission-family grammar requires non-generic MethodDefs",
                )
            }
            val methodImpls = input.methodImpls.associateBy { methodImpl -> methodImpl.kind }
            val implementationType = input.types.getValue(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
            )
            val expectedMethodImplEndpoints = mapOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind
                    .CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION to Pair(
                        DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                            .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                        DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                            .CLASS_SEMANTIC_CAPABILITY_SLOT,
                    ),
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind
                    .INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION to Pair(
                        DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                            .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                        DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                            .INTERFACE_SEMANTIC_CAPABILITY_SLOT,
                    ),
            )
            for (methodImpl in methodImpls.values) {
                val expectedEndpoints = expectedMethodImplEndpoints.getValue(methodImpl.kind)
                val expectedBody = methods.getValue(expectedEndpoints.first).second.identity as
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local
                val expectedDeclaration = methods.getValue(expectedEndpoints.second).second.identity as
                        DotNetGenericOwnerPhysicalMethodDefIdentity.Local
                if (!methodImpl.implementingType.sameLocalTypeIdentityAs(implementationType) ||
                    !methodImpl.body.sameLocalMethodIdentityAs(expectedBody) ||
                    !methodImpl.declaration.sameLocalMethodIdentityAs(expectedDeclaration)
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a complete local emission family assigned a MethodImpl to incoherent endpoints",
                    )
                }
                val implementingType = declarations.typeDescriptionOrNull(methodImpl.implementingType)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                val body = declarations.methodDescriptionOrNull(methodImpl.body)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                val declaration = declarations.methodDescriptionOrNull(methodImpl.declaration)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                if (implementingType.category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS ||
                    body.declaringType != methodImpl.implementingType ||
                    declaration.declaringType != methodImpl.declarationOwner.definition
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a complete local emission family contains an incoherent MethodImpl row",
                    )
                }
            }
            val edgeBindings = DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.entries.associateWith { kind ->
                declarations.directSupertypeEdgesOrUnavailable(input.types.getValue(kind))
            }
            edgeBindings.values.filterIsInstance<DotNetGenericOwnerPhysicalBindingResult.Conflict>()
                .firstOrNull()?.let { conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(conflict.reason)
                }
            if (edgeBindings.values.any { binding ->
                    binding == DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            ) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            fun edges(kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind):
                    Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference> =
                when (val binding = edgeBindings.getValue(kind)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    -> error("complete emission-family edge bindings were not fail-closed")
                }
            fun containsMethodParameter(carrier: DotNetGenericOwnerSymbolicCarrierReference): Boolean =
                when (carrier) {
                    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> false
                    is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
                        carrier.binder is DotNetGenericOwnerPhysicalGenericBinderReference.Method
                    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
                        carrier.arguments.any(::containsMethodParameter)
                    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                        containsMethodParameter(carrier.element)
                }
            if (input.typeParameters.values.flatten().any { parameter ->
                    parameter.constraints.any(::containsMethodParameter)
                } || DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.entries.any { kind ->
                    edges(kind).any { edge -> containsMethodParameter(edge.target) }
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "complete TypeDef metadata cannot refer to a MethodDef generic parameter",
                )
            }
            val naturalEdges = edges(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE,
            )
            val interfaceCapabilityEdges = edges(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY,
            )
            val implementationEdges = edges(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
            )
            val classCapabilityEdges = edges(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
            )
            val implementationParameter = when (val binding = declarations.typeParameterOrError(
                implementationType,
                0,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val naturalConstruction = when (val binding = declarations.constructTypeOrError(
                input.types.getValue(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE,
                ),
                listOf(implementationParameter),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            fun nonGenericConstruction(
                kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference.Constructed> =
                declarations.constructTypeOrError(input.types.getValue(kind), emptyList())
            val classCapabilityConstruction = when (val binding = nonGenericConstruction(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val interfaceCapabilityConstruction = when (val binding = nonGenericConstruction(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val expectedMethodImplDeclarationOwners = mapOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION to
                        classCapabilityConstruction,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION to
                        interfaceCapabilityConstruction,
            )
            if (methodImpls.any { entry ->
                    entry.value.declarationOwner != expectedMethodImplDeclarationOwners.getValue(entry.key)
                }
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family assigned a MethodImpl to the wrong construction",
                )
            }
            val expectedClassCapabilityEdges = setOf(
                DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    interfaceCapabilityConstruction,
                ),
            )
            val expectedImplementationEdges = setOf(
                DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                ),
                DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    naturalConstruction,
                ),
                DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    classCapabilityConstruction,
                ),
                DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    interfaceCapabilityConstruction,
                ),
            )
            if (naturalEdges.isNotEmpty() || interfaceCapabilityEdges.isNotEmpty() ||
                classCapabilityEdges != expectedClassCapabilityEdges ||
                implementationEdges != expectedImplementationEdges
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a complete local emission family has an incompatible direct TypeDef edge set",
                )
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily(
                    input.logicalMember,
                    input.implementationMember,
                    input.types.toMap(),
                    input.typeAliases.mapValues { entry -> entry.value.toList() },
                    input.typeParameters.mapValues { entry -> entry.value.toList() },
                    methods,
                    methodImpls,
                ),
            )
        }
    }
}

internal data class DotNetLocalGenericOwnerPhysicalCallableFamilyInput(
    val logicalMember: IrSimpleFunctionSymbol,
    val semanticCapabilityMember: IrSimpleFunctionSymbol,
)

internal enum class DotNetLocalGenericOwnerPhysicalCallableEntryKind {
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY_INTERFACE_SLOT,
}

/**
 * Opaque logical-member-to-MethodDef relation admitted only by the BOUND local authority.
 * Physical operation proof consumes one already selected endpoint and cannot invent a family.
 */
internal class DotNetLocalGenericOwnerPhysicalCallableFamily private constructor(
    private val naturalMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    private val semanticCapabilityMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
) {
    fun selectedMethod(kind: DotNetLocalGenericOwnerPhysicalCallableEntryKind):
            DotNetGenericOwnerPhysicalMethodDefIdentity = when (kind) {
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE -> naturalMethod
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT ->
            semanticCapabilityMethod
    }

    companion object {
        fun bindDirectProducerOrError(
            input: DotNetLocalGenericOwnerPhysicalCallableFamilyInput,
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            inputsByIdentity: Map<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                    DotNetLocalGenericOwnerPhysicalTypeInput,
                    >,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalCallableFamily> {
            val logicalMember = input.logicalMember.owner
            val logicalOwner = logicalMember.parent as? IrClass
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family requires an interface-owned logical member",
                )
            val semanticMember = input.semanticCapabilityMember.owner
            val semanticOwner = semanticMember.parent as? IrClass
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family requires an interface-owned capability member",
                )
            if (logicalMember.isSuspend || semanticMember.isSuspend) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a suspend callable cannot use the ordinary direct-producer MethodDef grammar",
                )
            }
            val naturalOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                logicalOwner.symbol,
                DotNetGenericInterfaceView.DECLARED,
            )
            val semanticOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                semanticOwner.symbol,
                view = null,
            )
            if (inputsByIdentity[naturalOwnerIdentity]?.role !=
                DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE ||
                inputsByIdentity[semanticOwnerIdentity]?.role !=
                DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family must use its selected natural and capability TypeDefs",
                )
            }
            val resultParameterIndex = logicalOwner.typeParameters.indexOfFirst { parameter ->
                logicalMember.returnType == parameter.defaultType
            }.takeIf { index -> index >= 0 }
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded direct-producer family must return one exact owner parameter",
                )
            val naturalIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                input.logicalMember,
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            )
            val semanticIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                input.semanticCapabilityMember,
                role = null,
            )
            if (naturalIdentity == semanticIdentity) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "natural and semantic callable entries require distinct MethodDefs",
                )
            }
            val natural = declarations.methodDescriptionOrNull(naturalIdentity)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val semantic = declarations.methodDescriptionOrNull(semanticIdentity)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            fun hasDirectProducerShape(
                method: DotNetGenericOwnerPhysicalMethodDefReference,
                owner: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            ): Boolean = method.declaringType == owner &&
                    method.visibility == DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC &&
                    method.dispatch == DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT &&
                    method.signature.isInstance &&
                    method.signature.genericArity == 0 &&
                    method.signature.parameterSlots.isEmpty()
            if (!hasDirectProducerShape(natural, naturalOwnerIdentity) ||
                !hasDirectProducerShape(semantic, semanticOwnerIdentity)
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a bounded local callable family must contain public abstract instance producer slots",
                )
            }
            val naturalResult = natural.signature.resultLayout as?
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded natural producer requires a direct result",
                )
            val semanticResult = semantic.signature.resultLayout as?
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded semantic producer requires a direct result",
                )
            val expectedNaturalCarrier = when (val binding = declarations.typeParameterOrError(
                naturalOwnerIdentity,
                resultParameterIndex,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (naturalResult.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                naturalResult.slot.carrier != expectedNaturalCarrier ||
                semanticResult.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                semanticResult.slot.carrier != DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a bounded local producer family has incompatible natural or semantic result authority",
                )
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalCallableFamily(naturalIdentity, semanticIdentity),
            )
        }
    }
}

/** All declaration facts selected together for the one BOUND authority epoch. */
internal class DotNetLocalGenericOwnerPhysicalBoundInput(
    methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
    callableFamilies: Iterable<DotNetLocalGenericOwnerPhysicalCallableFamilyInput>,
    directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
    completeEmissionFamilies: Iterable<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput> = emptyList(),
) {
    val methodDefinitions = methodDefinitions.toList()
    val callableFamilies = callableFamilies.toList()
    val directSupertypeEdgeSets = directSupertypeEdgeSets.toList()
    val completeEmissionFamilies = completeEmissionFamilies.toList()
}

/**
 * One context-owned declaration-authority lineage for compilation-local generic owners.
 *
 * PRE analysis consumes [earlyDeclarations]. Later lowering advances that same immutable input to
 * [boundDeclarations]. Value flow may choose an epoch but can never advance or mutate one. The
 * emitter remains independent during this production-inert migration; a later checkpoint must
 * structurally cross-check or consume this index before it can become authoritative codegen data.
 */
internal class DotNetLocalGenericOwnerPhysicalAuthority private constructor(
    val earlyDeclarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    val boundDeclarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
    inputsByIdentity: Map<DotNetGenericOwnerPhysicalTypeDefIdentity.Local, DotNetLocalGenericOwnerPhysicalTypeInput>,
    callableFamiliesByLogicalMember:
            Map<IrSimpleFunctionSymbol, DotNetLocalGenericOwnerPhysicalCallableFamily>,
    completeEmissionFamilies: List<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily>,
) {
    private val inputsByIdentity = inputsByIdentity.toMap()
    private val callableFamiliesByLogicalMember = callableFamiliesByLogicalMember.toMap()
    private val completeEmissionFamilies = completeEmissionFamilies.toList()

    fun inputOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    ): DotNetLocalGenericOwnerPhysicalTypeInput? = inputsByIdentity[identity]

    fun genericClassIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, view = null)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }

    fun naturalInterfaceIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, DotNetGenericInterfaceView.DECLARED)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE }

    fun callableMethodOrNull(
        logicalMember: IrSimpleFunctionSymbol,
        kind: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
    ): DotNetGenericOwnerPhysicalMethodDefIdentity? =
        callableFamiliesByLogicalMember[logicalMember]?.selectedMethod(kind)

    internal fun completeEmissionFamilies(): List<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily> =
        completeEmissionFamilies

    /**
     * Compares only the opaque families already admitted by BOUND authority with one successful
     * final emitter scope. This is diagnostic and deliberately does not advance or mutate the
     * declaration index: absent emission evidence must remain absent rather than inheriting the
     * corresponding BOUND MethodDef through the index's additive epoch transition.
     */
    fun compareFinalMethodDefHeaders(
        scope: DotNetIlEmissionScope,
        observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
        otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation> = emptyList(),
    ): List<DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot> =
        callableFamiliesByLogicalMember.entries.mapNotNull { entry ->
            compareDotNetGenericOwnerPhysicalMethodDefEmissionFamily(
                authority = this,
                scope = scope,
                logicalMember = entry.key,
                family = entry.value,
                observations = observations,
                otherScopeObservations = otherScopeObservations,
            )
        }.sortedWith(compareBy(
            DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot::ownerName,
            DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot::logicalMemberName,
        ))

    internal fun compareFinalCompleteEmissionFamilies(
        successfulEmissions: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
    ): List<DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot> =
        completeEmissionFamilies.map { family ->
            compareDotNetGenericOwnerCompleteEmissionFamily(this, family, successfulEmissions)
        }.sortedWith(compareBy(
            { comparison: DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot ->
                comparison.scope.ordinal
            },
            DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot::ownerName,
            DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot::logicalMemberName,
            DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot::implementationOwnerName,
        ))

    fun advanceBound(
        additionalInputs: Iterable<DotNetLocalGenericOwnerPhysicalTypeInput>,
        buildBoundInput: (
            DotNetGenericOwnerPhysicalDeclarationIndex,
        ) -> DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalBoundInput>,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalAuthority> {
        if (boundDeclarations != null) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "local physical declaration authority was bound more than once",
            )
        }
        val stableAdditionalInputs = additionalInputs.toList()
        val mergedInputs = linkedMapOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                DotNetLocalGenericOwnerPhysicalTypeInput,
                >().apply {
            putAll(inputsByIdentity)
            for (candidate in stableAdditionalInputs) {
                val existing = putIfAbsent(candidate.identity, candidate)
                if (existing != null && existing != candidate) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "one local physical TypeDef received conflicting role descriptions",
                    )
                }
            }
        }
        val additionalReferences = stableAdditionalInputs.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference)
        val provisional = when (val binding = earlyDeclarations.advance(
            nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = additionalReferences,
            methodDefinitions = emptyList(),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val boundInput = when (val binding = buildBoundInput(provisional)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val bound = when (val binding = earlyDeclarations.advance(
            nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = additionalReferences,
            methodDefinitions = boundInput.methodDefinitions,
            directSupertypeEdgeSets = boundInput.directSupertypeEdgeSets,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val mergedCallableFamilies = linkedMapOf<
                IrSimpleFunctionSymbol,
                DotNetLocalGenericOwnerPhysicalCallableFamily,
                >()
        mergedCallableFamilies.putAll(callableFamiliesByLogicalMember)
        val seenCandidates = linkedSetOf<IrSimpleFunctionSymbol>()
        for (candidate in boundInput.callableFamilies) {
            if (!seenCandidates.add(candidate.logicalMember) ||
                mergedCallableFamilies.containsKey(candidate.logicalMember)
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "one logical callable received duplicate physical MethodDef family authority",
                )
            }
            val family = when (val binding =
                DotNetLocalGenericOwnerPhysicalCallableFamily.bindDirectProducerOrError(
                    candidate,
                    bound,
                    mergedInputs,
                )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            mergedCallableFamilies[candidate.logicalMember] = family
        }
        val completeEmissionFamilies = mutableListOf<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily>()
        val seenCompleteFamilies = linkedSetOf<Pair<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>>()
        for (candidate in boundInput.completeEmissionFamilies) {
            if (!seenCompleteFamilies.add(candidate.logicalMember to candidate.implementationMember)) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "one implementation received duplicate complete physical emission-family authority",
                )
            }
            when (val binding = DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily.bindOrError(
                candidate,
                bound,
                mergedInputs,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    completeEmissionFamilies += binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            DotNetLocalGenericOwnerPhysicalAuthority(
                earlyDeclarations = earlyDeclarations,
                boundDeclarations = bound,
                inputsByIdentity = mergedInputs,
                callableFamiliesByLogicalMember = mergedCallableFamilies,
                completeEmissionFamilies = completeEmissionFamilies,
            ),
        )
    }

    fun carrierSnapshotOrNull(
        carrier: DotNetGenericOwnerPhysicalCarrier,
    ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot? = when {
        carrier.type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() ->
            DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
            )
        carrier.type is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
            constructionSnapshotOrNull(carrier.type)
        else -> null
    }

    fun viewSnapshotOrNull(
        view: DotNetGenericOwnerPhysicalView,
        evidence: Set<DotNetGenericOwnerPhysicalViewEvidence>,
    ): DotNetGenericOwnerPhysicalValueShadowViewSnapshot? =
        constructionSnapshotOrNull(view.construction)?.let { carrier ->
            DotNetGenericOwnerPhysicalValueShadowViewSnapshot(
                carrier = carrier,
                evidence = evidence.mapTo(linkedSetOf()) { item ->
                    DotNetGenericOwnerPhysicalValueShadowEvidence.valueOf(item.name)
                },
            )
        }

    fun familySnapshotOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerPhysicalValueShadowFamilySnapshot? {
        val localIdentity = identity as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        val input = inputsByIdentity[localIdentity] ?: return null
        return DotNetGenericOwnerPhysicalValueShadowFamilySnapshot(
            kind = when (input.role) {
                DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
                DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
                -> DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION
                DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY ->
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY
            },
            localOwnerName = input.logicalOwnerName,
            localTypeDefView = localIdentity.view?.toShadowView(),
        )
    }

    private fun constructionSnapshotOrNull(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot? {
        val identity = construction.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        val input = inputsByIdentity[identity] ?: return null
        if (input.role == DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY) {
            if (construction.arguments.isNotEmpty()) return null
            return DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY,
                localOwnerName = input.logicalOwnerName,
            )
        }
        val parameters = construction.arguments.map { argument ->
            argument as? DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
        }
        val binders = parameters.map { parameter ->
            (parameter.binder as? DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        }.distinct()
        val binder = binders.singleOrNull() ?: return null
        val binderInput = inputsByIdentity[binder] ?: return null
        if (binderInput.role == DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY) return null
        return DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
            kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
            localOwnerName = input.logicalOwnerName,
            ownerParameterIndices = parameters.map { parameter -> parameter.index },
            localTypeDefView = identity.view?.toShadowView(),
            parameterBinderOwnerName = binderInput.logicalOwnerName,
            parameterBinderTypeDefView = binder.view?.toShadowView(),
        )
    }

    companion object {
        fun bindEarly(
            inputs: Iterable<DotNetLocalGenericOwnerPhysicalTypeInput>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalAuthority> {
            val stableInputs = inputs.toList()
            if (stableInputs.isEmpty()) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (stableInputs.any { input -> input.role != DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "early local physical authority may contain only selected generic classes",
                )
            }
            val byIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                    DotNetLocalGenericOwnerPhysicalTypeInput,
                    >()
            for (candidate in stableInputs) {
                val existing = byIdentity.putIfAbsent(candidate.identity, candidate)
                if (existing != null && existing != candidate) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "one early local TypeDef received conflicting descriptions",
                    )
                }
            }
            val declarations = when (val binding = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
                typeDefinitions = byIdentity.values.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference),
                methodDefinitions = emptyList(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalAuthority(
                    earlyDeclarations = declarations,
                    boundDeclarations = null,
                    inputsByIdentity = byIdentity,
                    callableFamiliesByLogicalMember = emptyMap(),
                    completeEmissionFamilies = emptyList(),
                ),
            )
        }
    }
}

private fun DotNetGenericInterfaceView.toShadowView(): DotNetGenericOwnerPhysicalValueShadowTypeDefView =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)
