/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition

/** Monotone physical-declaration authority epochs. Value flow never advances this epoch. */
internal enum class DotNetGenericOwnerPhysicalAuthorityEpoch {
    EARLY_REPRESENTATION_PLAN,
    BOUND_DECLARATION_INDEX,
    SEALED_EMISSION_SIGNATURE_INDEX,
}

/** Stable compilation-local identity of one real or planned CLR TypeDef. */
internal sealed interface DotNetGenericOwnerPhysicalTypeDefIdentity {
    /** One logical interface can own canonical, natural, and exact physical TypeDefs. */
    data class Local(
        val owner: IrClassSymbol,
        val view: DotNetGenericInterfaceView?,
    ) : DotNetGenericOwnerPhysicalTypeDefIdentity

    class KotlinProducer(
        val artifact: DotNetLibraryArtifact,
        ownerPath: List<String>,
    ) : DotNetGenericOwnerPhysicalTypeDefIdentity {
        val ownerPath: List<String> = ownerPath.toList()

        init {
            require(this.ownerPath.isNotEmpty() && this.ownerPath.all(String::isNotEmpty)) {
                "a producer physical TypeDef identity requires its recorded path"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is KotlinProducer && artifact == other.artifact && ownerPath == other.ownerPath

        override fun hashCode(): Int = 31 * artifact.hashCode() + ownerPath.hashCode()

        override fun toString(): String = "KotlinProducer($artifact, $ownerPath)"
    }

    /** Exact imported identity retained from metadata; equality is deliberately by retained handles. */
    class ForeignClr private constructor(
        val source: DotNetClrImportedDeclarationSource,
    ) : DotNetGenericOwnerPhysicalTypeDefIdentity {
        override fun equals(other: Any?): Boolean =
            other is ForeignClr &&
                    source.assembly === other.source.assembly &&
                    source.declaringType === other.source.declaringType

        override fun hashCode(): Int =
            31 * System.identityHashCode(source.assembly) + System.identityHashCode(source.declaringType)

        override fun toString(): String =
            "ForeignClr(${source.presentableString.substringBefore(" MethodDef ")})"

        companion object {
            fun retained(source: DotNetClrImportedDeclarationSource) = ForeignClr(source)
        }
    }

    class CoreLibrary(
        ownerPath: List<String>,
    ) : DotNetGenericOwnerPhysicalTypeDefIdentity {
        val ownerPath: List<String> = ownerPath.toList()

        init {
            require(this.ownerPath.isNotEmpty() && this.ownerPath.all(String::isNotEmpty)) {
                "a core-library physical TypeDef identity requires its recorded path"
            }
        }

        override fun equals(other: Any?): Boolean = other is CoreLibrary && ownerPath == other.ownerPath

        override fun hashCode(): Int = ownerPath.hashCode()

        override fun toString(): String = "CoreLibrary($ownerPath)"
    }
}

internal fun DotNetGenericOwnerPhysicalTypeDefIdentity.Local.sameLocalTypeIdentityAs(
    other: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
): Boolean = owner === other.owner && view == other.view

/** Epoch-specific description candidate for one TypeDef identity. It never enters value flow. */
internal data class DotNetGenericOwnerPhysicalTypeDefReference(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val genericArity: Int,
    val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
    val supportsInlineNull: Boolean = false,
) {
    init {
        require(genericArity >= 0 &&
                (!supportsInlineNull || category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE)) {
            "a physical TypeDef description requires non-negative arity and coherent inline-null support"
        }
    }

    fun conflictsWith(other: DotNetGenericOwnerPhysicalTypeDefReference): Boolean =
        identity == other.identity && (genericArity != other.genericArity || category != other.category ||
                supportsInlineNull != other.supportsInlineNull)
}

/** Stable compilation-local identity of one real or planned CLR MethodDef. */
internal sealed interface DotNetGenericOwnerPhysicalMethodDefIdentity {

    data class Local(
        val function: IrSimpleFunctionSymbol,
        val role: DotNetGenericOwnerMemberFamilyRole?,
    ) : DotNetGenericOwnerPhysicalMethodDefIdentity

    data class KotlinProducer(
        val artifact: DotNetLibraryArtifact,
        val method: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    ) : DotNetGenericOwnerPhysicalMethodDefIdentity

    /** Exact imported MethodDef identity; names and enhanced Kotlin types are not consulted. */
    class ForeignClr private constructor(
        val source: DotNetClrImportedDeclarationSource,
        val method: DotNetClrMethodDefinition,
    ) : DotNetGenericOwnerPhysicalMethodDefIdentity {
        init {
            require(method.declaringType == source.declaringType.handle &&
                    source.assembly.metadata.methodDefinitions.any { candidate -> candidate === method }) {
                "a retained foreign MethodDef must belong to its retained declaring TypeDef"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ForeignClr && source.assembly === other.source.assembly && method === other.method

        override fun hashCode(): Int =
            31 * System.identityHashCode(source.assembly) + System.identityHashCode(method)

        override fun toString(): String = "ForeignClr(${source.presentableString}, ${method.name})"

        companion object {
            fun retained(
                source: DotNetClrImportedDeclarationSource,
                method: DotNetClrMethodDefinition,
            ) = ForeignClr(source, method)
        }
    }
}

internal fun DotNetGenericOwnerPhysicalMethodDefIdentity.Local.sameLocalMethodIdentityAs(
    other: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
): Boolean = function === other.function && role == other.role

private fun DotNetGenericOwnerPhysicalMethodDefIdentity.retainedGenericArityOrNull(): Int? = when (this) {
    is DotNetGenericOwnerPhysicalMethodDefIdentity.Local -> null
    is DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer -> method.signature.genericArity
    is DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr -> method.signature.genericParameterCount
}

private fun DotNetGenericOwnerPhysicalMethodDefIdentity.retainedDeclaringTypeOrNull():
        DotNetGenericOwnerPhysicalTypeDefIdentity? = when (this) {
    is DotNetGenericOwnerPhysicalMethodDefIdentity.Local -> null
    is DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer ->
        DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(artifact, method.physicalOwnerPath)
    is DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr ->
        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(source)
}

/** One logical value domain and its already selected symbolic physical carrier. */
internal data class DotNetGenericOwnerPhysicalCallableValueSlotReference(
    val domain: DotNetGenericOwnerPhysicalSlotDomain,
    val carrier: DotNetGenericOwnerSymbolicCarrierReference,
) {
    init {
        require(carrier != DotNetGenericOwnerSymbolicCarrierReference.voidCarrier()) {
            "a physical callable value slot cannot use void as a carrier"
        }
    }
}

internal enum class DotNetGenericOwnerPhysicalHiddenParameterPassing {
    VALUE,
    REF,
    OUT,
}

/** One physical parameter introduced solely by a calling convention, not by Kotlin source. */
internal data class DotNetGenericOwnerPhysicalHiddenParameterReference(
    val carrier: DotNetGenericOwnerSymbolicCarrierReference,
    val passing: DotNetGenericOwnerPhysicalHiddenParameterPassing,
) {
    init {
        require(carrier != DotNetGenericOwnerSymbolicCarrierReference.voidCarrier()) {
            "a hidden physical parameter cannot use void as a carrier"
        }
    }
}

/** Result calling convention, independent from every input-domain decision. */
internal sealed interface DotNetGenericOwnerPhysicalCallableResultLayoutReference {
    data object Void : DotNetGenericOwnerPhysicalCallableResultLayoutReference

    data class Direct(
        val slot: DotNetGenericOwnerPhysicalCallableValueSlotReference,
    ) : DotNetGenericOwnerPhysicalCallableResultLayoutReference

    /** [nullFlag] is the final physical parameter and is not a Kotlin value parameter. */
    data class SplitNullable(
        val payloadSlot: DotNetGenericOwnerPhysicalCallableValueSlotReference,
        val nullFlag: DotNetGenericOwnerPhysicalHiddenParameterReference =
            DotNetGenericOwnerPhysicalHiddenParameterReference(
                DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                DotNetGenericOwnerPhysicalHiddenParameterPassing.OUT,
            ),
    ) : DotNetGenericOwnerPhysicalCallableResultLayoutReference {
        init {
            require(nullFlag == DotNetGenericOwnerPhysicalHiddenParameterReference(
                DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                DotNetGenericOwnerPhysicalHiddenParameterPassing.OUT,
            )) { "a split-nullable result requires one trailing hidden [out] bool& parameter" }
        }
    }
}

/** Symbolic MethodDef signature selected before final emitter binding. */
internal class DotNetGenericOwnerPhysicalMethodSignatureReference(
    val isInstance: Boolean,
    val genericArity: Int,
    val resultLayout: DotNetGenericOwnerPhysicalCallableResultLayoutReference,
    parameterSlots: Iterable<DotNetGenericOwnerPhysicalCallableValueSlotReference>,
) {
    val parameterSlots: List<DotNetGenericOwnerPhysicalCallableValueSlotReference> =
        parameterSlots.toList()

    init {
        require(genericArity >= 0) { "a physical MethodDef signature requires non-negative arity" }
        require(parameterSlots.all { slot ->
            slot.domain in setOf(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
            )
        }) { "a physical MethodDef parameter has a non-input domain" }
        require(!isInstance || parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER
        }) { "an instance physical MethodDef cannot expose a separate exact receiver" }
        val resultDomain = when (resultLayout) {
            is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
                resultLayout.slot.domain
            is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
                resultLayout.payloadSlot.domain
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> null
        }
        require(resultDomain == null || resultDomain in setOf(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
        )) { "a physical MethodDef result has a non-output domain" }
    }

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalMethodSignatureReference &&
                isInstance == other.isInstance &&
                genericArity == other.genericArity &&
                resultLayout == other.resultLayout &&
                parameterSlots == other.parameterSlots

    override fun hashCode(): Int {
        var result = isInstance.hashCode()
        result = 31 * result + genericArity
        result = 31 * result + resultLayout.hashCode()
        result = 31 * result + parameterSlots.hashCode()
        return result
    }

    override fun toString(): String =
        "MethodSignature(instance=$isInstance, arity=$genericArity, " +
                "parameters=$parameterSlots, result=$resultLayout)"
}

/** Epoch-specific description candidate for one MethodDef identity. It never enters value flow. */
internal data class DotNetGenericOwnerPhysicalMethodDefReference(
    val identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
    val declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val visibility: DotNetGenericOwnerPhysicalMemberVisibility,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureReference,
) {
    fun conflictsWith(other: DotNetGenericOwnerPhysicalMethodDefReference): Boolean =
        identity == other.identity && this != other
}

/** Scope of a physical generic parameter; `!0` from different scopes is never the same fact. */
internal sealed interface DotNetGenericOwnerPhysicalGenericBinderReference {
    data class Type(
        val definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ) : DotNetGenericOwnerPhysicalGenericBinderReference

    data class Method(
        val definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
    ) : DotNetGenericOwnerPhysicalGenericBinderReference
}

/** One producer-recorded BaseType or InterfaceImpl target; it contains no logical-type evidence. */
internal data class DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
    val kind: DotNetGenericOwnerDirectSupertypeKind,
    val target: DotNetGenericOwnerSymbolicCarrierReference,
)

/**
 * Complete recorded direct-supertype rows for one physical TypeDef. Absence of this set remains
 * distinct from a recorded empty set, so an unavailable graph can never prove negative facts.
 */
internal class DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
    val source: DotNetGenericOwnerPhysicalTypeDefIdentity,
    edges: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
) {
    private val edgeList = edges.toList()
    val edges: Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference> = edgeList.toSet()

    init {
        require(edgeList.size == this.edges.size) {
            "a complete physical direct-supertype set cannot contain duplicate metadata rows"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet &&
                source == other.source && edges == other.edges

    override fun hashCode(): Int = 31 * source.hashCode() + edges.hashCode()

    override fun toString(): String = "DirectSupertypes(source=$source, edges=$edges)"
}

/**
 * One conflict-checked declaration-authority snapshot. Value provenance consumes only identities;
 * all arity/category descriptions are validated here before a symbolic carrier can be created.
 */
internal class DotNetGenericOwnerPhysicalDeclarationIndex private constructor(
    val epoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
    private val typeDefinitions:
            Map<DotNetGenericOwnerPhysicalTypeDefIdentity, DotNetGenericOwnerPhysicalTypeDefReference>,
    private val methodDefinitions:
            Map<DotNetGenericOwnerPhysicalMethodDefIdentity, DotNetGenericOwnerPhysicalMethodDefReference>,
    private val directSupertypeEdgeSets:
            Map<DotNetGenericOwnerPhysicalTypeDefIdentity, DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
) {
    fun advance(
        nextEpoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
        typeDefinitions: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
        methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
        directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
        if (nextEpoch.ordinal <= epoch.ordinal) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "physical declaration authority cannot regress or repeat epoch $epoch as $nextEpoch",
            )
        }
        return bind(
            nextEpoch,
            this.typeDefinitions.values + typeDefinitions,
            this.methodDefinitions.values + methodDefinitions,
            this.directSupertypeEdgeSets.values + directSupertypeEdgeSets,
        )
    }

    fun constructTypeOrError(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference.Constructed> =
        DotNetGenericOwnerSymbolicCarrierReference.Constructed.bind(this, definition, arguments)

    internal fun validateConstructionOrError(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        val description = typeDefinitions[definition]
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (arguments.size != description.genericArity) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "physical TypeDef expects ${description.genericArity} arguments but received ${arguments.size}",
            )
        }
        if (arguments.any { argument -> argument == voidCarrier() }) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a physical generic construction cannot contain void",
            )
        }
        for (argument in arguments) {
            when (val validation = validateCarrierOrError(argument)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
            }
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }

    fun typeParameterOrError(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        index: Int,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference.Parameter> =
        DotNetGenericOwnerSymbolicCarrierReference.Parameter.bindType(this, definition, index)

    fun methodParameterOrError(
        definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
        index: Int,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference.Parameter> =
        DotNetGenericOwnerSymbolicCarrierReference.Parameter.bindMethod(this, definition, index)

    fun typeDescriptionOrNull(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerPhysicalTypeDefReference? = typeDefinitions[definition]

    fun methodDescriptionOrNull(
        definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
    ): DotNetGenericOwnerPhysicalMethodDefReference? = methodDefinitions[definition]

    fun directSupertypeEdgesOrUnavailable(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerPhysicalBindingResult<Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>> =
        directSupertypeEdgeSets[definition]?.let { edgeSet ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(edgeSet.edges)
        } ?: DotNetGenericOwnerPhysicalBindingResult.Unavailable

    /**
     * Computes only interface views proven by recorded physical edges. A missing complete edge set
     * stops that branch and marks the positive result incomplete. It never consults Kotlin IR or
     * synthesizes an additional view from CLR/Kotlin variance.
     */
    fun physicalInterfaceViewClosureOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalInterfaceViewClosure> {
        when (val validation = validateConstructionOrError(construction.definition, construction.arguments)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }

        val interfaceViews = linkedSetOf<DotNetGenericOwnerPhysicalView>()
        var isComplete = true
        val visitedConstructions = mutableSetOf<DotNetGenericOwnerSymbolicCarrierReference.Constructed>()

        fun visit(
            current: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
            activeDefinitions: Set<DotNetGenericOwnerPhysicalTypeDefIdentity>,
        ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
            if (current.definition in activeDefinitions) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "cyclic recorded physical direct-supertype graph at ${current.definition}",
                )
            }
            if (!visitedConstructions.add(current)) {
                return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }

            val currentDescription = typeDefinitions[current.definition]
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (currentDescription.category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE) {
                interfaceViews += DotNetGenericOwnerPhysicalView(current)
            }

            val edgeSet = directSupertypeEdgeSets[current.definition]
            if (edgeSet == null) {
                isComplete = false
                return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }

            val nextActiveDefinitions = activeDefinitions + current.definition
            for (edge in edgeSet.edges) {
                val target = when (val substitution = substituteDirectSupertypeTargetOrError(
                    edge.target,
                    current,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> substitution.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return substitution
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                when (target) {
                    is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> when (
                        val targetResult = visit(target, nextActiveDefinitions)
                    ) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return targetResult
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return targetResult
                    }
                    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> check(
                        edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS &&
                                target == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                    ) { "validated direct-supertype leaf stopped being System.Object" }
                    is DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                    is DotNetGenericOwnerSymbolicCarrierReference.SzArray,
                    -> error("validated physical direct-supertype root stopped being named")
                }
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        }

        return when (val traversal = visit(construction, emptySet())) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerPhysicalInterfaceViewClosure(interfaceViews, isComplete),
                )
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(traversal.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }

    fun carrierOrError(
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> =
        DotNetGenericOwnerPhysicalCarrier.bind(this, type)

    internal fun validateParameterOrError(
        binder: DotNetGenericOwnerPhysicalGenericBinderReference,
        index: Int,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        val genericArity = when (binder) {
            is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
                typeDefinitions[binder.definition]?.genericArity
            is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
                methodDefinitions[binder.definition]?.signature?.genericArity
        }
        genericArity ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (index !in 0 until genericArity) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "physical generic parameter $index is outside binder arity $genericArity",
            )
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }

    private fun validateCarrierOrError(
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (carrier) {
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
            validateParameterOrError(carrier.binder, carrier.index)
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
            validateConstructionOrError(carrier.definition, carrier.arguments)
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
            validateCarrierOrError(carrier.element)
    }

    private fun validateDirectSupertypeEdgeSetOrError(
        edgeSet: DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        val sourceDescription = typeDefinitions[edgeSet.source]
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        val baseEdgeCount = edgeSet.edges.count { edge ->
            edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS
        }
        if (baseEdgeCount > 1) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a physical TypeDef cannot record more than one direct base class",
            )
        }
        if (sourceDescription.category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE &&
            baseEdgeCount != 0
        ) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a physical interface TypeDef cannot record a base-class row",
            )
        }
        if (sourceDescription.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE &&
            baseEdgeCount != 1
        ) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a complete physical class or value-type edge set requires one direct base class",
            )
        }

        for (edge in edgeSet.edges) {
            when (val carrierValidation = validateCarrierOrError(edge.target)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return carrierValidation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return carrierValidation
            }
            when (val scopeValidation = validateDirectSupertypeParameterScopesOrError(
                edge.target,
                edgeSet.source,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return scopeValidation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return scopeValidation
            }

            val targetDescription = (edge.target as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let { target ->
                typeDefinitions[target.definition]
            }
            val targetIsValid = when (edge.kind) {
                DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS ->
                    edge.target == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() ||
                            targetDescription?.category == DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
                DotNetGenericOwnerDirectSupertypeKind.INTERFACE ->
                    targetDescription?.category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
            }
            if (!targetIsValid) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a physical ${edge.kind.name.lowercase()} edge has an incompatible target carrier",
                )
            }
            if ((edge.target as? DotNetGenericOwnerSymbolicCarrierReference.Constructed)
                    ?.definition == edgeSet.source
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a physical TypeDef cannot directly inherit from itself",
                )
            }
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }

    private fun validateDirectSupertypeParameterScopesOrError(
        target: DotNetGenericOwnerSymbolicCarrierReference,
        source: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        return when (target) {
            is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
                if (target.binder == DotNetGenericOwnerPhysicalGenericBinderReference.Type(source)) {
                    DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                } else {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a physical direct-supertype target may reference only its source TypeDef parameters",
                    )
                }
            is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
                for (argument in target.arguments) {
                    when (val validation = validateDirectSupertypeParameterScopesOrError(argument, source)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
                    }
                }
                DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                validateDirectSupertypeParameterScopesOrError(target.element, source)
        }
    }

    private fun substituteDirectSupertypeTargetOrError(
        target: DotNetGenericOwnerSymbolicCarrierReference,
        source: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
        return when (target) {
            is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(target)
            is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
                if (target.binder != DotNetGenericOwnerPhysicalGenericBinderReference.Type(source.definition)) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a recorded direct-supertype edge escaped its source TypeDef binder",
                    )
                } else {
                    source.arguments.getOrNull(target.index)?.let {
                        DotNetGenericOwnerPhysicalBindingResult.Bound(it)
                    } ?: DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a recorded direct-supertype parameter is outside its constructed source arity",
                    )
                }
            }
            is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
                val substitutedArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (argument in target.arguments) {
                    when (val substitution = substituteDirectSupertypeTargetOrError(argument, source)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            substitutedArguments += substitution.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return substitution
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return substitution
                    }
                }
                constructTypeOrError(target.definition, substitutedArguments)
            }
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> when (
                val element = substituteDirectSupertypeTargetOrError(target.element, source)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> element
            }
        }
    }

    companion object {
        fun bind(
            epoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
            typeDefinitions: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
            methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
            directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
            val typesByIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity,
                    DotNetGenericOwnerPhysicalTypeDefReference,
                    >()
            for (candidate in typeDefinitions) {
                if ((candidate.identity as? DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary)
                        ?.ownerPath == listOf("System", "Object")
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "core System.Object must use the canonical object leaf carrier",
                    )
                }
                val existing = typesByIdentity[candidate.identity]
                if (existing != null && existing.conflictsWith(candidate)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "conflicting physical TypeDef descriptions for ${candidate.identity}",
                    )
                }
                typesByIdentity.putIfAbsent(candidate.identity, candidate)
            }

            val methodsByIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalMethodDefIdentity,
                    DotNetGenericOwnerPhysicalMethodDefReference,
                    >()
            for (candidate in methodDefinitions) {
                val retainedGenericArity = candidate.identity.retainedGenericArityOrNull()
                if (retainedGenericArity != null &&
                    retainedGenericArity != candidate.signature.genericArity
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical MethodDef description contradicts retained generic arity " +
                                "$retainedGenericArity for ${candidate.identity}",
                    )
                }
                val retainedDeclaringType = candidate.identity.retainedDeclaringTypeOrNull()
                if (retainedDeclaringType != null && retainedDeclaringType != candidate.declaringType) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical MethodDef description contradicts its retained declaring TypeDef " +
                                "for ${candidate.identity}",
                    )
                }
                if (candidate.identity !is DotNetGenericOwnerPhysicalMethodDefIdentity.Local) {
                    // A retained MethodDef already owns its complete physical signature. Until a
                    // producer/foreign adapter normalizes and cross-checks every signature and
                    // flag, accepting a caller-supplied partial description would invert authority.
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val existing = methodsByIdentity[candidate.identity]
                if (existing != null && existing.conflictsWith(candidate)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "conflicting physical MethodDef descriptions for ${candidate.identity}",
                    )
                }
                methodsByIdentity.putIfAbsent(candidate.identity, candidate)
            }

            val declarationsWithoutEdges = DotNetGenericOwnerPhysicalDeclarationIndex(
                epoch,
                typesByIdentity,
                methodsByIdentity,
                emptyMap(),
            )
            for (candidate in methodsByIdentity.values) {
                if (typesByIdentity[candidate.declaringType] == null) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val slotCarriers = buildList {
                    addAll(candidate.signature.parameterSlots.map { slot -> slot.carrier })
                    when (val result = candidate.signature.resultLayout) {
                        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
                            add(result.slot.carrier)
                        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
                            add(result.payloadSlot.carrier)
                        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> Unit
                    }
                }
                for (carrier in slotCarriers) {
                    val foreignBinder = carrier.firstGenericBinderNotOwnedByOrNull(
                        declaringType = candidate.declaringType,
                        method = candidate.identity,
                    )
                    if (foreignBinder != null) {
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                            "physical MethodDef signature references generic binder $foreignBinder " +
                                    "outside ${candidate.identity}",
                        )
                    }
                    when (val validation = declarationsWithoutEdges.validateCarrierOrError(carrier)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                val resultSlot = when (val result = candidate.signature.resultLayout) {
                    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct -> result.slot
                    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
                        result.payloadSlot
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> null
                }
                if (resultSlot?.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                    resultSlot.carrier.referencesTypeParameterOf(candidate.declaringType)
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "owner-dependent physical MethodDef result requires an owner slot domain",
                    )
                }
                if (candidate.signature.parameterSlots.any { slot ->
                        slot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                                slot.carrier.referencesTypeParameterOf(candidate.declaringType)
                    }
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "owner-dependent physical MethodDef parameter requires an owner slot domain",
                    )
                }
            }
            val edgesBySource = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity,
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet,
                    >()
            for (candidate in directSupertypeEdgeSets) {
                when (val validation = declarationsWithoutEdges.validateDirectSupertypeEdgeSetOrError(candidate)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val existing = edgesBySource[candidate.source]
                if (existing != null && existing != candidate) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "conflicting complete physical direct-supertype sets for ${candidate.source}",
                    )
                }
                edgesBySource.putIfAbsent(candidate.source, candidate)
            }

            val activeDefinitions = mutableSetOf<DotNetGenericOwnerPhysicalTypeDefIdentity>()
            val completedDefinitions = mutableSetOf<DotNetGenericOwnerPhysicalTypeDefIdentity>()
            fun validateAcyclicOrError(
                source: DotNetGenericOwnerPhysicalTypeDefIdentity,
            ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
                if (source in completedDefinitions) {
                    return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                }
                if (!activeDefinitions.add(source)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "cyclic recorded physical direct-supertype graph at $source",
                    )
                }
                for (edge in edgesBySource.getValue(source).edges) {
                    val target = (edge.target as?
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.definition
                        ?: continue
                    if (target !in edgesBySource) continue
                    when (val validation = validateAcyclicOrError(target)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
                    }
                }
                check(activeDefinitions.remove(source)) {
                    "physical direct-supertype validation lost its active source"
                }
                completedDefinitions += source
                return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
            for (source in edgesBySource.keys) {
                when (val validation = validateAcyclicOrError(source)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }

            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerPhysicalDeclarationIndex(
                    epoch,
                    typesByIdentity,
                    methodsByIdentity,
                    edgesBySource,
                ),
            )
        }
    }
}

/**
 * Symbolic verifier carrier. It is bound once against live emission indexes and is never cached as
 * a [DotNetIlValueType] across emitter eviction/fixpoint rounds.
 */
internal sealed interface DotNetGenericOwnerSymbolicCarrierReference {
    data class Leaf(
        val kind: DotNetGenericOwnerPhysicalTypeKind,
    ) : DotNetGenericOwnerSymbolicCarrierReference {
        init {
            require(kind !in setOf(
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
                DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
                DotNetGenericOwnerPhysicalTypeKind.NAMED,
                DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
            )) { "a symbolic leaf cannot contain structural physical type data" }
        }
    }

    @ConsistentCopyVisibility
    data class Parameter private constructor(
        val binder: DotNetGenericOwnerPhysicalGenericBinderReference,
        val index: Int,
    ) : DotNetGenericOwnerSymbolicCarrierReference {
        init {
            require(index >= 0) { "a symbolic physical parameter requires a non-negative index" }
        }

        companion object {
            fun bindType(
                declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
                definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
                index: Int,
            ): DotNetGenericOwnerPhysicalBindingResult<Parameter> = bind(
                declarations,
                DotNetGenericOwnerPhysicalGenericBinderReference.Type(definition),
                index,
            )

            fun bindMethod(
                declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
                definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
                index: Int,
            ): DotNetGenericOwnerPhysicalBindingResult<Parameter> = bind(
                declarations,
                DotNetGenericOwnerPhysicalGenericBinderReference.Method(definition),
                index,
            )

            /**
             * Creates an unbound self-reference while a MethodDef candidate is being assembled.
             * [DotNetGenericOwnerPhysicalDeclarationIndex.bind] remains the only authority that
             * can validate the referenced MethodDef, its generic arity, and binder ownership.
             */
            fun methodParameterReference(
                definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
                index: Int,
            ): Parameter = Parameter(
                DotNetGenericOwnerPhysicalGenericBinderReference.Method(definition),
                index,
            )

            private fun bind(
                declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
                binder: DotNetGenericOwnerPhysicalGenericBinderReference,
                index: Int,
            ): DotNetGenericOwnerPhysicalBindingResult<Parameter> {
                return when (val validation = declarations.validateParameterOrError(binder, index)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(Parameter(binder, index))
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
        }
    }

    @ConsistentCopyVisibility
    data class Constructed private constructor(
        val definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        val arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ) : DotNetGenericOwnerSymbolicCarrierReference {
        init {
            require(arguments.none { argument -> argument == voidCarrier() }) {
                "a symbolic physical construction requires non-void arguments"
            }
        }

        companion object {
            fun bind(
                declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
                definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
                arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
            ): DotNetGenericOwnerPhysicalBindingResult<Constructed> {
                return when (val validation = declarations.validateConstructionOrError(definition, arguments)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(
                            Constructed(definition, arguments.toList()),
                        )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
        }
    }

    data class SzArray(
        val element: DotNetGenericOwnerSymbolicCarrierReference,
    ) : DotNetGenericOwnerSymbolicCarrierReference {
        init {
            require(element != voidCarrier()) { "a symbolic SZ-array requires a non-void element" }
        }
    }

    companion object {
        fun voidCarrier() = Leaf(DotNetGenericOwnerPhysicalTypeKind.VOID)
        fun booleanCarrier() = Leaf(DotNetGenericOwnerPhysicalTypeKind.BOOLEAN)
        fun int32Carrier() = Leaf(DotNetGenericOwnerPhysicalTypeKind.INT32)
        fun stringCarrier() = Leaf(DotNetGenericOwnerPhysicalTypeKind.STRING)
        fun objectCarrier() = Leaf(DotNetGenericOwnerPhysicalTypeKind.OBJECT)
    }
}

private fun DotNetGenericOwnerSymbolicCarrierReference.firstGenericBinderNotOwnedByOrNull(
    declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    method: DotNetGenericOwnerPhysicalMethodDefIdentity,
): DotNetGenericOwnerPhysicalGenericBinderReference? = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> null
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> when (val parameterBinder = binder) {
        is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
            parameterBinder.takeUnless { it.definition == declaringType }
        is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
            parameterBinder.takeUnless { it.definition == method }
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        arguments.firstNotNullOfOrNull { argument ->
            argument.firstGenericBinderNotOwnedByOrNull(declaringType, method)
        }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
        element.firstGenericBinderNotOwnedByOrNull(declaringType, method)
}

private fun DotNetGenericOwnerSymbolicCarrierReference.referencesTypeParameterOf(
    declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
): Boolean = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> false
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
        binder == DotNetGenericOwnerPhysicalGenericBinderReference.Type(declaringType)
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        arguments.any { argument -> argument.referencesTypeParameterOf(declaringType) }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
        element.referencesTypeParameterOf(declaringType)
}

private fun voidCarrier() = DotNetGenericOwnerSymbolicCarrierReference.voidCarrier()

/** How one physical carrier represents null before any explicit materialization. */
internal enum class DotNetGenericOwnerPhysicalNullEncoding {
    NON_NULL_ONLY,
    NULL_REFERENCE,
    INLINE_NULLABLE_VALUE,
    SUBSTITUTION_DEPENDENT,
    ;

    val canRepresentNull: Boolean
        get() = this != NON_NULL_ONLY

    val acceptsCarrierlessNull: Boolean
        get() = this == NULL_REFERENCE
}

/** A symbolic verifier type and its authority-selected null representation. */
@ConsistentCopyVisibility
internal data class DotNetGenericOwnerPhysicalCarrier private constructor(
    val type: DotNetGenericOwnerSymbolicCarrierReference,
    val nullEncoding: DotNetGenericOwnerPhysicalNullEncoding,
) {
    init {
        require(type != voidCarrier() || nullEncoding == DotNetGenericOwnerPhysicalNullEncoding.NON_NULL_ONLY) {
            "a void physical carrier cannot represent a value or null"
        }
    }

    val canRepresentNull: Boolean
        get() = nullEncoding.canRepresentNull

    val acceptsCarrierlessNull: Boolean
        get() = nullEncoding.acceptsCarrierlessNull

    companion object {
        fun bind(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            type: DotNetGenericOwnerSymbolicCarrierReference,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> {
            if (type == voidCarrier()) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "void is a result absence and cannot become a physical value carrier",
                )
            }
            val validation = when (type) {
                is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
                    declarations.validateConstructionOrError(type.definition, type.arguments)
                is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
                    declarations.validateParameterOrError(type.binder, type.index)
                is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                    declarations.carrierOrError(type.element).mapToUnit()
                is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
            when (validation) {
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            }

            val nullEncoding = when (type) {
                is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> when (type.kind) {
                    DotNetGenericOwnerPhysicalTypeKind.STRING,
                    DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                    -> DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
                    else -> DotNetGenericOwnerPhysicalNullEncoding.NON_NULL_ONLY
                }
                is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
                    DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT
                is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
                    val description = declarations.typeDescriptionOrNull(type.definition)
                        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    when (description.category) {
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        -> DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
                        DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE ->
                            if (description.supportsInlineNull) {
                                DotNetGenericOwnerPhysicalNullEncoding.INLINE_NULLABLE_VALUE
                            } else {
                                DotNetGenericOwnerPhysicalNullEncoding.NON_NULL_ONLY
                            }
                    }
                }
                is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                    DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerPhysicalCarrier(type, nullEncoding),
            )
        }
    }
}

private fun DotNetGenericOwnerPhysicalBindingResult<*>.mapToUnit():
        DotNetGenericOwnerPhysicalBindingResult<Unit> = when (this) {
    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
        DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
        DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)
    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
        DotNetGenericOwnerPhysicalBindingResult.Unavailable
}

/** One truthful constructed CLR view of a non-null value. */
internal data class DotNetGenericOwnerPhysicalView(
    val construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
) {
    val family: DotNetGenericOwnerPhysicalTypeDefIdentity
        get() = construction.definition
}

/** Positive interface views found through recorded CLR edges and whether every branch was recorded. */
internal class DotNetGenericOwnerPhysicalInterfaceViewClosure(
    interfaceViews: Iterable<DotNetGenericOwnerPhysicalView>,
    val isComplete: Boolean,
) {
    val interfaceViews: Set<DotNetGenericOwnerPhysicalView> = interfaceViews.toSet()

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalInterfaceViewClosure &&
                interfaceViews == other.interfaceViews && isComplete == other.isComplete

    override fun hashCode(): Int = 31 * interfaceViews.hashCode() + isComplete.hashCode()

    override fun toString(): String =
        "PhysicalInterfaceViewClosure(views=$interfaceViews, complete=$isComplete)"
}

/** Auditable source of one guaranteed view; none of these is inferred from a logical type alone. */
internal enum class DotNetGenericOwnerPhysicalViewEvidence {
    CURRENT_PHYSICAL_RECEIVER,
    FROZEN_PARAMETER_OR_RESULT,
    FROZEN_FIELD,
    CONSTRUCTOR_ALLOCATION,
    RECORDED_INTERFACE_EDGE,
    PRODUCER_ABI,
    RETAINED_FOREIGN_METADATA,
    CHECKED_RUNTIME_BARRIER,
    IDENTITY_PRESERVING_TRANSFER,
    STORAGE_READ,
}

/**
 * Real views guaranteed on every reaching non-null value. Unknown evidence differs from a known
 * empty intersection. Evidence values are diagnostics; only the map's keys participate in flow.
 */
internal sealed interface DotNetGenericOwnerGuaranteedViews {
    data object Unknown : DotNetGenericOwnerGuaranteedViews

    class Known(
        evidenceByView: Map<DotNetGenericOwnerPhysicalView, Set<DotNetGenericOwnerPhysicalViewEvidence>>,
    ) : DotNetGenericOwnerGuaranteedViews {
        val evidenceByView: Map<DotNetGenericOwnerPhysicalView, Set<DotNetGenericOwnerPhysicalViewEvidence>> =
            evidenceByView.mapValues { entry -> entry.value.toSet() }.toMap()

        init {
            require(this.evidenceByView.values.all(Set<DotNetGenericOwnerPhysicalViewEvidence>::isNotEmpty)) {
                "a guaranteed physical view requires explicit evidence"
            }
        }

        val views: Set<DotNetGenericOwnerPhysicalView>
            get() = evidenceByView.keys

        /** Evidence is diagnostic side data; only the guaranteed view set is a lattice dimension. */
        override fun equals(other: Any?): Boolean = other is Known && views == other.views

        override fun hashCode(): Int = views.hashCode()

        override fun toString(): String = "Known(views=$views, evidence=$evidenceByView)"
    }
}

/** Lineage selects one already guaranteed view; it is never an independent proof source. */
internal data class DotNetGenericOwnerPhysicalValueProvenance(
    val guaranteedViews: DotNetGenericOwnerGuaranteedViews,
    val selectedViewLineage:
            Map<DotNetGenericOwnerPhysicalTypeDefIdentity, DotNetGenericOwnerPhysicalView> = emptyMap(),
) {
    init {
        val knownViews = (guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)?.views
        require(selectedViewLineage.all { entry ->
            entry.value.family == entry.key && knownViews?.contains(entry.value) == true
        }) { "selected physical view lineage must select an independently guaranteed view" }
    }

    fun selectViewOrNull(view: DotNetGenericOwnerPhysicalView): DotNetGenericOwnerPhysicalValueProvenance? {
        val knownViews = (guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)?.views
            ?: return null
        if (view !in knownViews) return null
        return copy(selectedViewLineage = selectedViewLineage + (view.family to view))
    }

    fun guarantee(
        view: DotNetGenericOwnerPhysicalView,
        evidence: DotNetGenericOwnerPhysicalViewEvidence,
    ): DotNetGenericOwnerPhysicalValueProvenance {
        val existing = (guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
            ?.evidenceByView.orEmpty()
        return copy(
            guaranteedViews = DotNetGenericOwnerGuaranteedViews.Known(
                existing + (view to (existing[view].orEmpty() + evidence)),
            ),
        )
    }

    fun join(other: DotNetGenericOwnerPhysicalValueProvenance): DotNetGenericOwnerPhysicalValueProvenance {
        val joinedViews = when {
            guaranteedViews is DotNetGenericOwnerGuaranteedViews.Unknown ||
                    other.guaranteedViews is DotNetGenericOwnerGuaranteedViews.Unknown ->
                DotNetGenericOwnerGuaranteedViews.Unknown
            else -> {
                val left = (guaranteedViews as DotNetGenericOwnerGuaranteedViews.Known).evidenceByView
                val right = (other.guaranteedViews as DotNetGenericOwnerGuaranteedViews.Known).evidenceByView
                DotNetGenericOwnerGuaranteedViews.Known(
                    (left.keys intersect right.keys).associateWith { view ->
                        checkNotNull(left[view]) + checkNotNull(right[view])
                    },
                )
            }
        }
        val knownJoinedViews = (joinedViews as? DotNetGenericOwnerGuaranteedViews.Known)?.views.orEmpty()
        val joinedLineage = selectedViewLineage.entries.mapNotNull { entry ->
            (entry.key to entry.value).takeIf {
                other.selectedViewLineage[entry.key] == entry.value && entry.value in knownJoinedViews
            }
        }.toMap()
        return DotNetGenericOwnerPhysicalValueProvenance(joinedViews, joinedLineage)
    }

    companion object {
        fun noNonNullViews() = DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(emptyMap()),
        )
    }
}

internal enum class DotNetGenericOwnerPhysicalNullState {
    NON_NULL,
    NULL,
    MAYBE_NULL,
    ;

    val canBeNonNull: Boolean
        get() = this != NULL

    fun join(other: DotNetGenericOwnerPhysicalNullState): DotNetGenericOwnerPhysicalNullState =
        if (this == other) this else MAYBE_NULL
}

/** Physical value layout produced by one definition before destination placement. */
internal sealed interface DotNetGenericOwnerProducedValueLayout {
    data class Direct(
        val carrier: DotNetGenericOwnerPhysicalCarrier,
    ) : DotNetGenericOwnerProducedValueLayout

    /** `ldnull` has no object carrier and therefore does not narrow or contaminate a join. */
    data object Null : DotNetGenericOwnerProducedValueLayout

    data class SplitNullable(
        val payloadCarrier: DotNetGenericOwnerPhysicalCarrier,
    ) : DotNetGenericOwnerProducedValueLayout

    data object Unknown : DotNetGenericOwnerProducedValueLayout
}

internal data class DotNetGenericOwnerProducedValueFact(
    val layout: DotNetGenericOwnerProducedValueLayout,
    val provenance: DotNetGenericOwnerPhysicalValueProvenance,
    val nullState: DotNetGenericOwnerPhysicalNullState,
) {
    init {
        require(when (layout) {
            is DotNetGenericOwnerProducedValueLayout.Direct -> layout.carrier.type != voidCarrier()
            is DotNetGenericOwnerProducedValueLayout.SplitNullable -> layout.payloadCarrier.type != voidCarrier()
            DotNetGenericOwnerProducedValueLayout.Null,
            DotNetGenericOwnerProducedValueLayout.Unknown,
            -> true
        }) { "a produced physical value requires a non-void carrier" }
        require(layout !is DotNetGenericOwnerProducedValueLayout.Direct ||
                nullState == DotNetGenericOwnerPhysicalNullState.NON_NULL ||
                layout.carrier.canRepresentNull) {
            "a nullable direct value requires a carrier which represents null"
        }
        require(layout != DotNetGenericOwnerProducedValueLayout.Null ||
                nullState == DotNetGenericOwnerPhysicalNullState.NULL) {
            "the carrierless null layout must be definitely null"
        }
        require(nullState.canBeNonNull ||
                provenance == DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()) {
            "a definitely-null value cannot guarantee a non-null physical view"
        }
        val directConstruction = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)
            ?.carrier?.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
        require(!nullState.canBeNonNull || directConstruction == null ||
                DotNetGenericOwnerPhysicalView(directConstruction) in
                (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)?.views.orEmpty()) {
            "a direct constructed carrier must guarantee its own physical view"
        }
    }

    fun join(
        other: DotNetGenericOwnerProducedValueFact,
        selectTruthfulCommonCarrier: (
            DotNetGenericOwnerPhysicalCarrier,
            DotNetGenericOwnerPhysicalCarrier,
        ) -> DotNetGenericOwnerPhysicalCarrier?,
    ): DotNetGenericOwnerProducedValueFact {
        val joinedProvenance = when {
            !nullState.canBeNonNull && !other.nullState.canBeNonNull ->
                DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()
            !nullState.canBeNonNull -> other.provenance
            !other.nullState.canBeNonNull -> provenance
            else -> provenance.join(other.provenance)
        }
        return DotNetGenericOwnerProducedValueFact(
            layout = layout.join(other.layout, selectTruthfulCommonCarrier),
            provenance = joinedProvenance,
            nullState = nullState.join(other.nullState),
        )
    }
}

/**
 * Selects one interface construction only when recorded physical ancestry already guarantees it.
 *
 * [desiredView] is a selector, never an authority source: removing the corresponding CLR edge
 * must make this query fail even when a logical destination still names that construction. A
 * positive direct view remains usable when ancestry below that interface is unavailable; the
 * closure's completeness bit is evidence about negative queries, not a veto on an observed row.
 */
internal fun DotNetGenericOwnerProducedValueFact.selectRecordedPhysicalInterfaceViewOrNull(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    desiredView: DotNetGenericOwnerPhysicalView,
): DotNetGenericOwnerProducedValueFact? {
    if (!nullState.canBeNonNull) return null
    val producedConstruction = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)
        ?.carrier?.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
        ?: return null
    val closure = when (val result = declarations.physicalInterfaceViewClosureOrError(producedConstruction)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict,
        DotNetGenericOwnerPhysicalBindingResult.Unavailable,
        -> return null
    }
    if (desiredView !in closure.interfaceViews) return null

    var selectedProvenance = provenance
    closure.interfaceViews.forEach { view ->
        selectedProvenance = selectedProvenance.guarantee(
            view,
            DotNetGenericOwnerPhysicalViewEvidence.RECORDED_INTERFACE_EDGE,
        )
    }
    selectedProvenance = selectedProvenance.selectViewOrNull(desiredView) ?: return null
    return copy(provenance = selectedProvenance)
}

/** Unreachable is flow bottom and contributes neither a carrier nor provenance. */
internal sealed interface DotNetGenericOwnerPhysicalFlowFact {
    data object Unreachable : DotNetGenericOwnerPhysicalFlowFact

    data class Reachable(
        val value: DotNetGenericOwnerProducedValueFact,
    ) : DotNetGenericOwnerPhysicalFlowFact

    fun join(
        other: DotNetGenericOwnerPhysicalFlowFact,
        selectTruthfulCommonCarrier: (
            DotNetGenericOwnerPhysicalCarrier,
            DotNetGenericOwnerPhysicalCarrier,
        ) -> DotNetGenericOwnerPhysicalCarrier?,
    ): DotNetGenericOwnerPhysicalFlowFact = when {
        this is Unreachable -> other
        other is Unreachable -> this
        this is Reachable && other is Reachable -> Reachable(
            value.join(other.value, selectTruthfulCommonCarrier),
        )
        else -> error("unhandled generic-owner physical flow fact")
    }
}

private fun DotNetGenericOwnerProducedValueLayout.join(
    other: DotNetGenericOwnerProducedValueLayout,
    selectTruthfulCommonCarrier: (
        DotNetGenericOwnerPhysicalCarrier,
        DotNetGenericOwnerPhysicalCarrier,
    ) -> DotNetGenericOwnerPhysicalCarrier?,
): DotNetGenericOwnerProducedValueLayout = when {
    this == DotNetGenericOwnerProducedValueLayout.Null &&
            other == DotNetGenericOwnerProducedValueLayout.Null -> DotNetGenericOwnerProducedValueLayout.Null
    this == DotNetGenericOwnerProducedValueLayout.Null &&
            other is DotNetGenericOwnerProducedValueLayout.Direct ->
        other.takeIf { other.carrier.acceptsCarrierlessNull } ?: DotNetGenericOwnerProducedValueLayout.Unknown
    other == DotNetGenericOwnerProducedValueLayout.Null &&
            this is DotNetGenericOwnerProducedValueLayout.Direct ->
        this.takeIf { carrier.acceptsCarrierlessNull } ?: DotNetGenericOwnerProducedValueLayout.Unknown
    this is DotNetGenericOwnerProducedValueLayout.Unknown ||
            other is DotNetGenericOwnerProducedValueLayout.Unknown -> DotNetGenericOwnerProducedValueLayout.Unknown
    this is DotNetGenericOwnerProducedValueLayout.Direct &&
            other is DotNetGenericOwnerProducedValueLayout.Direct ->
        selectTruthfulCommonCarrier(carrier, other.carrier)
            ?.let(DotNetGenericOwnerProducedValueLayout::Direct)
            ?: DotNetGenericOwnerProducedValueLayout.Unknown
    this is DotNetGenericOwnerProducedValueLayout.SplitNullable &&
            other is DotNetGenericOwnerProducedValueLayout.SplitNullable ->
        selectTruthfulCommonCarrier(payloadCarrier, other.payloadCarrier)
            ?.let(DotNetGenericOwnerProducedValueLayout::SplitNullable)
            ?: DotNetGenericOwnerProducedValueLayout.Unknown
    else -> DotNetGenericOwnerProducedValueLayout.Unknown
}

/** Destination carrier selected independently from any one reaching producer. */
internal sealed interface DotNetGenericOwnerStorageCarrier {
    data object Deferred : DotNetGenericOwnerStorageCarrier
    data object Unknown : DotNetGenericOwnerStorageCarrier

    data class Fixed(
        val carrier: DotNetGenericOwnerPhysicalCarrier,
    ) : DotNetGenericOwnerStorageCarrier {
        init {
            require(carrier.type != voidCarrier()) { "a physical storage slot requires a non-void carrier" }
        }
    }
}

internal data class DotNetGenericOwnerPhysicalStorageFact(
    val storageCarrier: DotNetGenericOwnerStorageCarrier.Fixed,
    val contentsProvenance: DotNetGenericOwnerPhysicalValueProvenance,
    val contentsNullState: DotNetGenericOwnerPhysicalNullState,
) {
    init {
        require(contentsNullState == DotNetGenericOwnerPhysicalNullState.NON_NULL ||
                storageCarrier.carrier.canRepresentNull) {
            "nullable storage contents require a carrier which represents null"
        }
        require(contentsNullState.canBeNonNull ||
                contentsProvenance == DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()) {
            "definitely-null storage contents cannot guarantee a non-null physical view"
        }
    }

    fun read(): DotNetGenericOwnerPhysicalFlowFact.Reachable {
        val value = if (contentsNullState == DotNetGenericOwnerPhysicalNullState.NULL &&
            storageCarrier.carrier.acceptsCarrierlessNull
        ) {
            DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Null,
                DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                DotNetGenericOwnerPhysicalNullState.NULL,
            )
        } else {
            val readProvenance = (storageCarrier.carrier.type as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let { construction ->
                contentsProvenance.guarantee(
                    DotNetGenericOwnerPhysicalView(construction),
                    DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ,
                )
            } ?: contentsProvenance
            DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(storageCarrier.carrier),
                if (contentsNullState.canBeNonNull) readProvenance
                else DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                contentsNullState,
            )
        }
        return DotNetGenericOwnerPhysicalFlowFact.Reachable(value)
    }

    /** Joins alternative reaching writes; it is not the transfer for a killed sequential write. */
    fun joinAlternativeWrite(
        value: DotNetGenericOwnerProducedValueFact,
        canStoreIdentityPreserving: (
            DotNetGenericOwnerPhysicalCarrier,
            DotNetGenericOwnerPhysicalCarrier,
        ) -> Boolean,
    ): DotNetGenericOwnerPhysicalStorageFact? {
        if (!value.nullState.canBeNonNull) {
            val canPlaceNull = when (val layout = value.layout) {
                DotNetGenericOwnerProducedValueLayout.Null ->
                    storageCarrier.carrier.acceptsCarrierlessNull
                is DotNetGenericOwnerProducedValueLayout.Direct ->
                    canStoreIdentityPreserving(layout.carrier, storageCarrier.carrier) &&
                            storageCarrier.carrier.canRepresentNull
                is DotNetGenericOwnerProducedValueLayout.SplitNullable,
                DotNetGenericOwnerProducedValueLayout.Unknown,
                -> false
            }
            if (!canPlaceNull) return null
            return copy(contentsNullState = contentsNullState.join(value.nullState))
        }
        val producedCarrier = (value.layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
            ?: return null
        if (!canStoreIdentityPreserving(producedCarrier, storageCarrier.carrier) ||
            value.nullState != DotNetGenericOwnerPhysicalNullState.NON_NULL &&
            !storageCarrier.carrier.canRepresentNull
        ) return null
        val joinedProvenance = if (contentsNullState.canBeNonNull) {
            contentsProvenance.join(value.provenance)
        } else {
            value.provenance
        }
        return copy(
            contentsProvenance = joinedProvenance,
            contentsNullState = contentsNullState.join(value.nullState),
        )
    }

    /** A sequential write kills the prior contents and places only its new value. */
    fun overwriteOrNull(
        value: DotNetGenericOwnerProducedValueFact,
        canStoreIdentityPreserving: (
            DotNetGenericOwnerPhysicalCarrier,
            DotNetGenericOwnerPhysicalCarrier,
        ) -> Boolean,
    ): DotNetGenericOwnerPhysicalStorageFact? = value.placeInStorageOrNull(
        storageCarrier,
        canStoreIdentityPreserving,
    )
}

/**
 * Validates placement into an already selected storage carrier. It never selects that carrier;
 * split results require explicit result materialization and cannot silently become state.
 */
internal fun DotNetGenericOwnerProducedValueFact.placeInStorageOrNull(
    storageCarrier: DotNetGenericOwnerStorageCarrier.Fixed,
    canStoreIdentityPreserving: (
        DotNetGenericOwnerPhysicalCarrier,
        DotNetGenericOwnerPhysicalCarrier,
    ) -> Boolean,
): DotNetGenericOwnerPhysicalStorageFact? {
    if (layout == DotNetGenericOwnerProducedValueLayout.Null) {
        if (!storageCarrier.carrier.acceptsCarrierlessNull) return null
        return DotNetGenericOwnerPhysicalStorageFact(
            storageCarrier,
            DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
            DotNetGenericOwnerPhysicalNullState.NULL,
        )
    }
    val producedCarrier = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
        ?: return null
    if (!canStoreIdentityPreserving(producedCarrier, storageCarrier.carrier) ||
        nullState != DotNetGenericOwnerPhysicalNullState.NON_NULL &&
        !storageCarrier.carrier.canRepresentNull
    ) return null
    return DotNetGenericOwnerPhysicalStorageFact(storageCarrier, provenance, nullState)
}

/** Pure one-way binding result; unavailable facts never authorize a semantic reinterpretation. */
internal sealed interface DotNetGenericOwnerPhysicalBindingResult<out T> {
    data class Bound<T>(val value: T) : DotNetGenericOwnerPhysicalBindingResult<T>
    data object Unavailable : DotNetGenericOwnerPhysicalBindingResult<Nothing>
    data class Conflict(val reason: String) : DotNetGenericOwnerPhysicalBindingResult<Nothing> {
        init {
            require(reason.isNotEmpty()) { "a physical authority conflict requires a reason" }
        }
    }
}
