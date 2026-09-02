/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeAuthority
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrReferenceVariancePlan
import org.jetbrains.kotlin.load.dotnet.DotNetClrReferenceVarianceStep
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.planDotNetClrReferenceVariance

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
        val graph: DotNetClrImportedDeclarationGraph,
        val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
        val type: DotNetClrResolvedTypeDefinition,
    ) : DotNetGenericOwnerPhysicalTypeDefIdentity {
        init {
            require(graph.assemblyOrNull(type.assembly) === assembly &&
                    assembly.metadata.typeDefinitions.any { candidate ->
                        candidate === type.definition
                    }
            ) {
                "a retained foreign TypeDef must belong to its selected assembly graph"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ForeignClr &&
                    assembly === other.assembly &&
                    type.definition === other.type.definition

        override fun hashCode(): Int =
            31 * System.identityHashCode(assembly) + System.identityHashCode(type.definition)

        override fun toString(): String =
            "ForeignClr(${assembly.metadata.identity.name}, " +
                    "TypeDef 0x${type.definition.handle.token.toUInt().toString(16)})"

        companion object {
            fun retained(source: DotNetClrImportedTypeAuthority): ForeignClr =
                retained(source, source.declaringHierarchy.type.type)

            fun retained(
                source: DotNetClrImportedTypeAuthority,
                type: DotNetClrResolvedTypeDefinition,
            ): ForeignClr {
                val assembly = source.graph.assemblyOrNull(type.assembly)
                    ?: throw IllegalArgumentException(
                        "a retained foreign TypeDef must belong to the source's selected assembly graph",
                    )
                return ForeignClr(source.graph, assembly, type)
            }
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
internal class DotNetGenericOwnerPhysicalTypeDefReference(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    genericParameters: List<DotNetGenericOwnerPhysicalGenericParameterReference>,
    val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
    val supportsInlineNull: Boolean = false,
    /** Exact retained-metadata or producer-recorded proof of a sealed variant CLR delegate. */
    val supportsClrDelegateVariance: Boolean = false,
) {
    val genericParameters: List<DotNetGenericOwnerPhysicalGenericParameterReference> =
        genericParameters.toList()
    val genericArity: Int
        get() = genericParameters.size

    init {
        require(!supportsInlineNull || category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE) {
            "a physical TypeDef description requires coherent inline-null support"
        }
        require(!supportsClrDelegateVariance ||
                category == DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS &&
                genericParameters.any { parameter ->
                    parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                }
        ) {
            "CLR delegate-variance authority requires a variant class TypeDef"
        }
    }

    fun conflictsWith(other: DotNetGenericOwnerPhysicalTypeDefReference): Boolean =
        identity == other.identity && this != other

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalTypeDefReference &&
                identity == other.identity &&
                genericParameters == other.genericParameters &&
                category == other.category &&
                supportsInlineNull == other.supportsInlineNull &&
                supportsClrDelegateVariance == other.supportsClrDelegateVariance

    override fun hashCode(): Int {
        var result = identity.hashCode()
        result = 31 * result + genericParameters.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + supportsInlineNull.hashCode()
        result = 31 * result + supportsClrDelegateVariance.hashCode()
        return result
    }

    override fun toString(): String =
        "TypeDef(identity=$identity, genericParameters=$genericParameters, " +
                "category=$category, supportsInlineNull=$supportsInlineNull, " +
                "supportsClrDelegateVariance=$supportsClrDelegateVariance)"
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

/** Verifier-visible declaration-site variance on one CLR GenericParam row. */
enum class DotNetGenericOwnerPhysicalTypeParameterVariance {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

/**
 * One ordered, binder-relative CLR GenericParam row.
 *
 * The owning TypeDef or MethodDef supplies the parameter number through this row's position. The
 * constraints remain symbolic until the complete declaration index can validate both their
 * referenced declarations and their exact `!n`/`!!n` binder ownership.
 */
internal class DotNetGenericOwnerPhysicalGenericParameterReference(
    val variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
    constraints: List<DotNetGenericOwnerSymbolicCarrierReference>,
    val hasReferenceTypeConstraint: Boolean = false,
    val hasNotNullableValueTypeConstraint: Boolean = false,
    val hasDefaultConstructorConstraint: Boolean = false,
    val allowsByRefLike: Boolean = false,
) {
    val constraints: List<DotNetGenericOwnerSymbolicCarrierReference> = constraints.toList()

    init {
        require(this.constraints.size == this.constraints.toSet().size) {
            "a physical GenericParam cannot repeat a constraint row"
        }
        require(DotNetGenericOwnerSymbolicCarrierReference.voidCarrier() !in this.constraints) {
            "a physical GenericParam cannot have a void constraint"
        }
        require(!hasReferenceTypeConstraint || !hasNotNullableValueTypeConstraint) {
            "a physical GenericParam cannot require both reference- and value-type arguments"
        }
    }

    val isUnconstrained: Boolean
        get() = constraints.isEmpty() &&
                !hasReferenceTypeConstraint &&
                !hasNotNullableValueTypeConstraint &&
                !hasDefaultConstructorConstraint &&
                !allowsByRefLike

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalGenericParameterReference &&
                variance == other.variance &&
                constraints.toSet() == other.constraints.toSet() &&
                hasReferenceTypeConstraint == other.hasReferenceTypeConstraint &&
                hasNotNullableValueTypeConstraint == other.hasNotNullableValueTypeConstraint &&
                hasDefaultConstructorConstraint == other.hasDefaultConstructorConstraint &&
                allowsByRefLike == other.allowsByRefLike

    override fun hashCode(): Int {
        var result = variance.hashCode()
        result = 31 * result + constraints.toSet().hashCode()
        result = 31 * result + hasReferenceTypeConstraint.hashCode()
        result = 31 * result + hasNotNullableValueTypeConstraint.hashCode()
        result = 31 * result + hasDefaultConstructorConstraint.hashCode()
        result = 31 * result + allowsByRefLike.hashCode()
        return result
    }

    override fun toString(): String =
        "GenericParameter(variance=$variance, constraints=$constraints, " +
                "referenceType=$hasReferenceTypeConstraint, " +
                "nonNullableValueType=$hasNotNullableValueTypeConstraint, " +
                "defaultConstructor=$hasDefaultConstructorConstraint, " +
                "allowsByRefLike=$allowsByRefLike)"
}

internal fun dotNetInvariantUnconstrainedPhysicalGenericParameters(
    arity: Int,
): List<DotNetGenericOwnerPhysicalGenericParameterReference> {
    require(arity >= 0) { "a physical generic parameter list requires non-negative arity" }
    return List(arity) {
        DotNetGenericOwnerPhysicalGenericParameterReference(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            constraints = emptyList(),
        )
    }
}

/** Epoch-specific description candidate for one MethodDef identity. It never enters value flow. */
internal class DotNetGenericOwnerPhysicalMethodDefReference(
    val identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
    val declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val visibility: DotNetGenericOwnerPhysicalMemberVisibility,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureReference,
    genericParameters: List<DotNetGenericOwnerPhysicalGenericParameterReference>,
) {
    val genericParameters: List<DotNetGenericOwnerPhysicalGenericParameterReference> =
        genericParameters.toList()

    init {
        require(genericParameters.size == signature.genericArity) {
            "a physical MethodDef requires one complete GenericParam row per generic parameter"
        }
        require(genericParameters.all { parameter ->
            parameter.variance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        }) {
            "a physical MethodDef GenericParam must be invariant"
        }
    }

    fun conflictsWith(other: DotNetGenericOwnerPhysicalMethodDefReference): Boolean =
        identity == other.identity && this != other

    override fun equals(other: Any?): Boolean =
        other is DotNetGenericOwnerPhysicalMethodDefReference &&
                identity == other.identity &&
                declaringType == other.declaringType &&
                visibility == other.visibility &&
                dispatch == other.dispatch &&
                signature == other.signature &&
                genericParameters == other.genericParameters

    override fun hashCode(): Int {
        var result = identity.hashCode()
        result = 31 * result + declaringType.hashCode()
        result = 31 * result + visibility.hashCode()
        result = 31 * result + dispatch.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + genericParameters.hashCode()
        return result
    }

    override fun toString(): String =
        "MethodDef(identity=$identity, declaringType=$declaringType, visibility=$visibility, " +
                "dispatch=$dispatch, signature=$signature, genericParameters=$genericParameters)"
}

/** Stable compilation-local identity of one real or planned CLR FieldDef. */
internal sealed interface DotNetGenericOwnerPhysicalFieldDefIdentity {
    /** One Kotlin field owns at most one physical FieldDef in the one-state model. */
    data class Local(
        val field: IrFieldSymbol,
    ) : DotNetGenericOwnerPhysicalFieldDefIdentity
}

/**
 * Epoch-specific physical FieldDef description.
 *
 * This records the selected storage carrier, not a value-flow fact. A later local provenance proof
 * cannot specialize [carrier], and a logically widened view cannot rewrite it.
 */
internal data class DotNetGenericOwnerPhysicalFieldDefReference(
    val identity: DotNetGenericOwnerPhysicalFieldDefIdentity,
    val declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val visibility: DotNetGenericOwnerPhysicalMemberVisibility,
    val isStatic: Boolean,
    val isInitOnly: Boolean,
    val carrier: DotNetGenericOwnerSymbolicCarrierReference,
) {
    init {
        require(carrier != DotNetGenericOwnerSymbolicCarrierReference.voidCarrier()) {
            "a physical FieldDef cannot use void as its carrier"
        }
    }

    fun conflictsWith(other: DotNetGenericOwnerPhysicalFieldDefReference): Boolean =
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
 * Proof that one constrained construction inside an exact recorded direct-supertype carrier
 * satisfies its TypeDef's GenericParam rows in the source TypeDef's open declaration context.
 *
 * This is deliberately narrower than general construction authority. It cannot authorize the
 * same construction with caller-chosen arguments, and it remains tied to the exact metadata edge
 * whose source binder supplied the constraint implication. The default records the edge root;
 * nested constrained carriers name both that root and their exact subtree.
 */
internal data class DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof(
    val source: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val edgeTarget: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    val constrainedConstruction: DotNetGenericOwnerSymbolicCarrierReference.Constructed = edgeTarget,
)

/**
 * Selected-metadata authority which can revalidate one exact retained CLR construction.
 *
 * This is intentionally not general construction authority. The declaration index consults it
 * only while proving one CLR reference-variance conversion, and the validated construction is
 * never published as a reusable edge, carrier, or declaration fact.
 */
internal interface DotNetGenericOwnerPhysicalVarianceConstraintAuthority {
    val constrainedTypeDefinitions: Set<DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr>

    fun validateConstructionOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit>
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
    private val fieldDefinitions:
            Map<DotNetGenericOwnerPhysicalFieldDefIdentity, DotNetGenericOwnerPhysicalFieldDefReference>,
    private val directSupertypeEdgeSets:
            Map<DotNetGenericOwnerPhysicalTypeDefIdentity, DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
    private val directSupertypeConstraintProofs:
            Set<DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof>,
    private val varianceConstraintAuthorities:
            Map<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalVarianceConstraintAuthority,
                    >,
    private val retainedForeignTypeDefinitions:
            Set<DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr>,
    private val retainedForeignMethodDefinitions:
            Set<DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr>,
    private val producerRecordedDelegateTypeDefinitions:
            Set<DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    fun advance(
        nextEpoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
        typeDefinitions: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
        methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
        directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
        fieldDefinitions: Iterable<DotNetGenericOwnerPhysicalFieldDefReference> = emptyList(),
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
        if (nextEpoch.ordinal <= epoch.ordinal) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "physical declaration authority cannot regress or repeat epoch $epoch as $nextEpoch",
            )
        }
        return bindInternal(
            nextEpoch,
            this.typeDefinitions.values + typeDefinitions,
            this.methodDefinitions.values + methodDefinitions,
            this.directSupertypeEdgeSets.values + directSupertypeEdgeSets,
            this.fieldDefinitions.values + fieldDefinitions,
            directSupertypeConstraintProofs,
            varianceConstraintAuthorities.values.toSet(),
            retainedForeignTypeDefinitions,
            retainedForeignMethodDefinitions,
            producerRecordedDelegateTypeDefinitions,
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
        when (val shape = validateConstructionShapeOrError(definition, arguments)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return shape
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return shape
        }
        val description = typeDefinitions[definition]
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (description.genericParameters.any { parameter -> !parameter.isUnconstrained }) {
            // The declaration index records exact GenericParam authority, but this generic
            // construction helper does not yet prove nominal or special-constraint satisfaction.
            // A retained metadata construction may eventually carry separate proof; arbitrary
            // symbolic arguments must not become verifier truth merely because their arity fits.
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }

    private fun validateConstructionShapeOrError(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        when (val header = validateConstructionHeaderOrError(definition, arguments)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return header
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return header
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

    private fun validateConstructionHeaderOrError(
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

    fun fieldDescriptionOrNull(
        definition: DotNetGenericOwnerPhysicalFieldDefIdentity,
    ): DotNetGenericOwnerPhysicalFieldDefReference? = fieldDefinitions[definition]

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
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalInterfaceViewClosure> =
        physicalInterfaceViewClosureOrError(construction) { root ->
            validateConstructionOrError(root.definition, root.arguments)
        }

    private fun physicalInterfaceViewClosureForReferenceVarianceOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        state: PhysicalReferenceAssignmentState,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalInterfaceViewClosure> =
        physicalInterfaceViewClosureOrError(construction) { root ->
            validateConstructionForReferenceVarianceOrError(root, state)
        }

    private fun physicalInterfaceViewClosureOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        validateRoot: (
            DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        ) -> DotNetGenericOwnerPhysicalBindingResult<Unit>,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalInterfaceViewClosure> {
        when (val validation = validateRoot(construction)) {
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
                    edge,
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

    private enum class PhysicalReferenceShape {
        REFERENCE,
        VALUE,
    }

    private data class PhysicalReferenceAssignmentPair(
        val source: DotNetGenericOwnerSymbolicCarrierReference,
        val target: DotNetGenericOwnerSymbolicCarrierReference,
    )

    private class PhysicalReferenceAssignmentState(
        var visitedPairs: Int = 0,
        val activePairs: MutableSet<PhysicalReferenceAssignmentPair> = mutableSetOf(),
        val validatedConstructions:
                MutableSet<DotNetGenericOwnerSymbolicCarrierReference.Constructed> =
            mutableSetOf(),
    )

    /**
     * Proves one same-TypeDef CLR generic variance conversion from physical authority only.
     *
     * Recorded ancestry and variance deliberately remain different facts: this query never adds an
     * InterfaceImpl edge to [physicalInterfaceViewClosureOrError]. Differing arguments must occupy
     * variant rows, both sides must be physically reference-shaped, and their direction must be
     * assignment-compatible through exact recorded hierarchy plus this same rule recursively.
     * Value arguments therefore remain invariant and boxing is never considered.
     */
    fun proveClrReferenceVarianceConversionOrError(
        source: DotNetGenericOwnerPhysicalView,
        target: DotNetGenericOwnerPhysicalView,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        if (source.family != target.family) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        if (source == target) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        return proveSameDefinitionReferenceVarianceOrError(
            source.construction,
            target.construction,
            PhysicalReferenceAssignmentState(),
        )
    }

    private fun proveSameDefinitionReferenceVarianceOrError(
        source: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        target: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        state: PhysicalReferenceAssignmentState,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        if (source.definition != target.definition) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        for (construction in listOf(source, target)) {
            when (val validation =
                validateConstructionForReferenceVarianceOrError(construction, state)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
            }
        }
        val description = typeDefinitions[source.definition]
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (description.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE &&
            !description.supportsClrDelegateVariance
        ) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val plan = when (val candidate = planDotNetClrReferenceVariance(
            description.genericParameters.map { parameter ->
                when (parameter.variance) {
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ->
                        DotNetClrGenericParameterVariance.INVARIANT
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT ->
                        DotNetClrGenericParameterVariance.COVARIANT
                    DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT ->
                        DotNetClrGenericParameterVariance.CONTRAVARIANT
                }
            },
            source.arguments,
            target.arguments,
        )) {
            is DotNetClrReferenceVariancePlan.Planned -> candidate
            DotNetClrReferenceVariancePlan.InvalidGenericParameterLayout ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "physical variance conversion contradicts its TypeDef arity",
                )
        }

        for (step in plan.steps) {
            val assignment = when (step) {
                DotNetClrReferenceVarianceStep.InvariantMismatch ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                is DotNetClrReferenceVarianceStep.Assignment -> step
            }
            for (argument in listOf(assignment.actual, assignment.expected)) {
                when (val shape = physicalReferenceShapeOrError(argument)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        if (shape.value != PhysicalReferenceShape.REFERENCE) {
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(shape.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            when (val argumentAssignment = provePhysicalReferenceAssignmentOrError(
                assignment.source,
                assignment.destination,
                state,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        argumentAssignment.reason,
                    )
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }
        return if (plan.steps.isNotEmpty()) {
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        } else {
            DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }

    private fun provePhysicalReferenceAssignmentOrError(
        source: DotNetGenericOwnerSymbolicCarrierReference,
        target: DotNetGenericOwnerSymbolicCarrierReference,
        state: PhysicalReferenceAssignmentState,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        if (source == target) return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        if (++state.visitedPairs > MAX_PHYSICAL_REFERENCE_ASSIGNMENT_PAIRS) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val pair = PhysicalReferenceAssignmentPair(source, target)
        if (!state.activePairs.add(pair)) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "cyclic physical reference-assignability proof",
            )
        }
        return try {
            for (carrier in listOf(source, target)) {
                when (val shape = physicalReferenceShapeOrError(carrier)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        if (shape.value != PhysicalReferenceShape.REFERENCE) {
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(shape.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            if (target == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()) {
                return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
            if (source is DotNetGenericOwnerSymbolicCarrierReference.SzArray &&
                target is DotNetGenericOwnerSymbolicCarrierReference.SzArray
            ) {
                return provePhysicalReferenceAssignmentOrError(
                    source.element,
                    target.element,
                    state,
                )
            }
            val sourceConstruction = source as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val targetConstruction = target as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (sourceConstruction.definition == targetConstruction.definition) {
                return proveSameDefinitionReferenceVarianceOrError(
                    sourceConstruction,
                    targetConstruction,
                    state,
                )
            }
            val targetDescription = typeDefinitions[targetConstruction.definition]
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (targetDescription.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val closure = when (val result =
                physicalInterfaceViewClosureForReferenceVarianceOrError(
                    sourceConstruction,
                    state,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            for (candidate in closure.interfaceViews) {
                if (candidate.family != targetConstruction.definition) continue
                if (candidate.construction == targetConstruction) {
                    return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                }
                when (val conversion = proveSameDefinitionReferenceVarianceOrError(
                    candidate.construction,
                    targetConstruction,
                    state,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> return conversion
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conversion
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
                }
            }
            DotNetGenericOwnerPhysicalBindingResult.Unavailable
        } finally {
            check(state.activePairs.remove(pair)) {
                "physical reference-assignability proof lost its active pair"
            }
        }
    }

    /**
     * Validates every constrained subtree only for the active variance proof. An exact retained
     * edge proof is not consulted here, and success does not make [constructTypeOrError]
     * permissive for this or any sibling construction.
     */
    private fun validateConstructionForReferenceVarianceOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        state: PhysicalReferenceAssignmentState,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
        if (construction in state.validatedConstructions) {
            return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        }
        when (val header = validateConstructionHeaderOrError(
            construction.definition,
            construction.arguments,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return header
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return header
        }
        val description = typeDefinitions[construction.definition]
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (description.genericParameters.any { parameter -> !parameter.isUnconstrained }) {
            val foreignDefinition = construction.definition as?
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val authority = varianceConstraintAuthorities[foreignDefinition]
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            when (val validation = authority.validateConstructionOrError(construction)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
            }
        }
        for (argument in construction.arguments) {
            when (val validation = validateCarrierForReferenceVarianceOrError(argument, state)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
            }
        }
        state.validatedConstructions += construction
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }

    private fun validateCarrierForReferenceVarianceOrError(
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
        state: PhysicalReferenceAssignmentState,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (carrier) {
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
            validateParameterOrError(carrier.binder, carrier.index)
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
            validateConstructionForReferenceVarianceOrError(carrier, state)
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
            validateCarrierForReferenceVarianceOrError(carrier.element, state)
    }

    private fun physicalReferenceShapeOrError(
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalBindingResult<PhysicalReferenceShape> {
        return when (carrier) {
            is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> when (carrier.kind) {
                DotNetGenericOwnerPhysicalTypeKind.STRING,
                DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                -> DotNetGenericOwnerPhysicalBindingResult.Bound(PhysicalReferenceShape.REFERENCE)
                DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
                DotNetGenericOwnerPhysicalTypeKind.INT32,
                -> DotNetGenericOwnerPhysicalBindingResult.Bound(PhysicalReferenceShape.VALUE)
                DotNetGenericOwnerPhysicalTypeKind.VOID ->
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "void cannot participate in physical reference assignability",
                    )
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
                DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
                DotNetGenericOwnerPhysicalTypeKind.NAMED,
                DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
                -> error("a structural physical type kind cannot occur as a symbolic leaf")
            }
            is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
                val parameter = when (val binder = carrier.binder) {
                    is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
                        typeDefinitions[binder.definition]
                            ?.genericParameters?.getOrNull(carrier.index)
                    is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
                        methodDefinitions[binder.definition]
                            ?.genericParameters?.getOrNull(carrier.index)
                } ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                when {
                    parameter.hasReferenceTypeConstraint ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(
                            PhysicalReferenceShape.REFERENCE,
                        )
                    parameter.hasNotNullableValueTypeConstraint ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(
                            PhysicalReferenceShape.VALUE,
                        )
                    else -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
                val description = typeDefinitions[carrier.definition]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                when (description.category) {
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                        PhysicalReferenceShape.REFERENCE,
                    )
                    DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(
                            PhysicalReferenceShape.VALUE,
                        )
                }
            }
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(PhysicalReferenceShape.REFERENCE)
        }
    }

    fun carrierOrError(
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> =
        DotNetGenericOwnerPhysicalCarrier.bind(this, type)

    fun carrierWithinAuthenticatedViewOrError(
        type: DotNetGenericOwnerSymbolicCarrierReference,
        authority: DotNetGenericOwnerAuthenticatedPhysicalView,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> =
        DotNetGenericOwnerPhysicalCarrier.bindWithinAuthenticatedView(
            this,
            type,
            authority,
        )

    fun constructTypeWithinAuthenticatedViewOrError(
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
        authority: DotNetGenericOwnerAuthenticatedPhysicalView,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference.Constructed> {
        val construction = DotNetGenericOwnerSymbolicCarrierReference.Constructed
            .unboundTypeReference(definition, arguments)
        return when (val validation = validateCarrierWithinConstructionScopeOrError(
            construction,
            authority.construction,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(construction)
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }

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

    /**
     * Structural half of scoped carrier validation. This does not authenticate [scopeConstruction];
     * only [DotNetGenericOwnerAuthenticatedPhysicalView] may expose it to carrier binding.
     */
    internal fun validateCarrierWithinConstructionScopeOrError(
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
        scopeConstruction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> =
        validateCarrierWithConstraintAuthorityOrError(carrier) { construction ->
            scopeConstruction.containsConstruction(construction)
        }

    private fun validateCarrierWithConstraintAuthorityOrError(
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
        isConstrainedConstructionAuthorized: (
            DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        ) -> Boolean,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (carrier) {
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
            validateParameterOrError(carrier.binder, carrier.index)
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
            validateCarrierWithConstraintAuthorityOrError(
                carrier.element,
                isConstrainedConstructionAuthorized,
            )
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
            when (val header = validateConstructionHeaderOrError(
                carrier.definition,
                carrier.arguments,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return header
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return header
            }
            val description = typeDefinitions[carrier.definition]
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (description.genericParameters.any { parameter -> !parameter.isUnconstrained } &&
                !isConstrainedConstructionAuthorized(carrier)
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            for (argument in carrier.arguments) {
                when (val validation = validateCarrierWithConstraintAuthorityOrError(
                    argument,
                    isConstrainedConstructionAuthorized,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return validation
                }
            }
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        }
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
            val targetConstruction = edge.target as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed
            val carrierValidation = if (targetConstruction != null) {
                validateDirectSupertypeConstructionOrError(
                    targetConstruction,
                    edgeSet.source,
                    targetConstruction,
                )
            } else {
                validateCarrierOrError(edge.target)
            }
            when (carrierValidation) {
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

    private fun validateDirectSupertypeConstructionOrError(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        source: DotNetGenericOwnerPhysicalTypeDefIdentity,
        edgeTarget: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> =
        validateCarrierWithConstraintAuthorityOrError(construction) { constrained ->
            DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof(
                source,
                edgeTarget,
                constrained,
            ) in directSupertypeConstraintProofs
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
        edge: DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference,
        source: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> =
        substituteDirectSupertypeCarrierOrError(
            edge.target,
            source,
            edge.target as? DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        )

    private fun substituteDirectSupertypeCarrierOrError(
        target: DotNetGenericOwnerSymbolicCarrierReference,
        source: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
        edgeTarget: DotNetGenericOwnerSymbolicCarrierReference.Constructed?,
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
                val exactEdgeTarget = edgeTarget
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a constructed direct-supertype subtree requires a named edge root",
                    )
                val substitutedArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (argument in target.arguments) {
                    when (val substitution = substituteDirectSupertypeCarrierOrError(
                        argument,
                        source,
                        exactEdgeTarget,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                            substitutedArguments += substitution.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return substitution
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return substitution
                    }
                }
                when (val header = validateConstructionHeaderOrError(
                    target.definition,
                    substitutedArguments,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return header
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val description = typeDefinitions[target.definition]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                if (description.genericParameters.any { parameter -> !parameter.isUnconstrained } &&
                    DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof(
                        source.definition,
                        exactEdgeTarget,
                        target,
                    ) !in directSupertypeConstraintProofs
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed
                        .unboundTypeReference(target.definition, substitutedArguments),
                )
            }
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> when (
                val element = substituteDirectSupertypeCarrierOrError(
                    target.element,
                    source,
                    edgeTarget,
                )
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
        private const val MAX_PHYSICAL_REFERENCE_ASSIGNMENT_PAIRS = 65_536

        fun bind(
            epoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
            typeDefinitions: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
            methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
            directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
            fieldDefinitions: Iterable<DotNetGenericOwnerPhysicalFieldDefReference> = emptyList(),
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> =
            bindInternal(
                epoch,
                typeDefinitions,
                methodDefinitions,
                directSupertypeEdgeSets,
                fieldDefinitions,
                directSupertypeConstraintProofs = emptySet(),
                varianceConstraintAuthorities = emptySet(),
                retainedForeignTypeDefinitions = emptySet(),
                retainedForeignMethodDefinitions = emptySet(),
                producerRecordedDelegateTypeDefinitions = emptySet(),
            )

        /**
         * Binds exact variant delegate TypeDefs only after the producer ABI adapter authenticated
         * their sealed-delegate record. Caller-authored producer descriptions remain unavailable
         * through [bind].
         */
        internal fun bindProducerRecordedDelegates(
            declarations: DotNetProducerGenericDelegatePhysicalDeclarations,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> =
            bindInternal(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                declarations.typeDefinitions,
                methodDefinitions = emptyList(),
                directSupertypeEdgeSets = emptyList(),
                fieldDefinitions = emptyList(),
                directSupertypeConstraintProofs = emptySet(),
                varianceConstraintAuthorities = emptySet(),
                retainedForeignTypeDefinitions = emptySet(),
                retainedForeignMethodDefinitions = emptySet(),
                producerRecordedDelegateTypeDefinitions =
                    declarations.delegateTypeDefinitions.toSet(),
            )

        /**
         * Binds one retained CLR MethodDef only after its raw metadata has been normalized and
         * cross-checked by the exact foreign adapter. Caller-authored foreign descriptions remain
         * unavailable through [bind].
         */
        fun bindRetainedForeign(
            source: DotNetClrImportedMethodSource,
            method: DotNetClrMethodDefinition,
            target: DotNetTarget? = null,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
            val declarations = when (
                val candidate =
                    DotNetRetainedForeignGenericOwnerPhysicalDeclarations.build(
                        source,
                        method,
                        target,
                    )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> candidate.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(candidate.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return bindRetainedForeignDeclarations(declarations)
        }

        /** Adds one exact retained receiver TypeDef and its complete authenticated edge set. */
        fun bindRetainedForeignInheritedReceiver(
            source: DotNetClrImportedMethodSource,
            method: DotNetClrMethodDefinition,
            receiverSource: DotNetClrImportedTypeSource,
            target: DotNetTarget? = null,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
            val declarations = when (
                val candidate = DotNetRetainedForeignGenericOwnerPhysicalDeclarations
                    .buildInheritedReceiver(source, method, receiverSource, target)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> candidate.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(candidate.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return bindRetainedForeignDeclarations(declarations)
        }

        private fun bindRetainedForeignDeclarations(
            declarations: DotNetRetainedForeignGenericOwnerPhysicalDeclarations,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
            return bindInternal(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                declarations.typeDefinitions,
                declarations.methodDefinitions,
                declarations.directSupertypeEdgeSets,
                fieldDefinitions = emptyList(),
                directSupertypeConstraintProofs = declarations.directSupertypeConstraintProofs.toSet(),
                varianceConstraintAuthorities =
                    declarations.varianceConstraintAuthorities.toSet(),
                retainedForeignTypeDefinitions = declarations.typeDefinitions
                    .mapTo(linkedSetOf()) { definition ->
                        definition.identity as DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                    },
                retainedForeignMethodDefinitions = declarations.methodDefinitions
                    .mapTo(linkedSetOf()) { definition ->
                        definition.identity as DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr
                    },
                producerRecordedDelegateTypeDefinitions = emptySet(),
            )
        }

        private fun bindInternal(
            epoch: DotNetGenericOwnerPhysicalAuthorityEpoch,
            typeDefinitions: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
            methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
            directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
            fieldDefinitions: Iterable<DotNetGenericOwnerPhysicalFieldDefReference>,
            directSupertypeConstraintProofs:
                    Set<DotNetGenericOwnerPhysicalDirectSupertypeConstraintProof>,
            varianceConstraintAuthorities:
                    Set<DotNetGenericOwnerPhysicalVarianceConstraintAuthority>,
            retainedForeignTypeDefinitions: Set<DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr>,
            retainedForeignMethodDefinitions: Set<DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr>,
            producerRecordedDelegateTypeDefinitions:
                    Set<DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDeclarationIndex> {
            val typesByIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity,
                    DotNetGenericOwnerPhysicalTypeDefReference,
                    >()
            for (candidate in typeDefinitions) {
                val foreignIdentity = candidate.identity as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                val producerIdentity = candidate.identity as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer
                val hasVariantParameter = candidate.genericParameters.any { parameter ->
                    parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                }
                if (hasVariantParameter &&
                    candidate.category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a physical value TypeDef cannot declare CLR variance",
                    )
                }
                if (hasVariantParameter &&
                    candidate.category == DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS &&
                    !candidate.supportsClrDelegateVariance
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a variant physical class TypeDef requires sealed CLR delegate authority",
                    )
                }
                if (foreignIdentity != null && foreignIdentity !in retainedForeignTypeDefinitions) {
                    // The identity retains the row, but only the metadata adapter may describe it.
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                if (candidate.supportsClrDelegateVariance &&
                    foreignIdentity !in retainedForeignTypeDefinitions &&
                    producerIdentity !in producerRecordedDelegateTypeDefinitions
                ) {
                    // Only the retained selected/raw classifier or the exact producer ABI
                    // adapter may authenticate this orthogonal CLASS fact.
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
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
            for (identity in producerRecordedDelegateTypeDefinitions) {
                val description = typesByIdentity[identity]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                if (!description.supportsClrDelegateVariance ||
                    description.category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "producer delegate authority does not match a variant class TypeDef",
                    )
                }
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
                when (val identity = candidate.identity) {
                    is DotNetGenericOwnerPhysicalMethodDefIdentity.Local -> Unit
                    is DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr ->
                        if (identity !in retainedForeignMethodDefinitions) {
                            // A retained MethodDef already owns its complete physical signature.
                            // Caller-supplied descriptions may not invert that authority.
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    is DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer -> {
                        // Producer MethodDefs require their distinct artifact-plus-DLL adapter.
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                val existing = methodsByIdentity[candidate.identity]
                if (existing != null && existing.conflictsWith(candidate)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "conflicting physical MethodDef descriptions for ${candidate.identity}",
                    )
                }
                methodsByIdentity.putIfAbsent(candidate.identity, candidate)
            }

            val fieldsByIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalFieldDefIdentity,
                    DotNetGenericOwnerPhysicalFieldDefReference,
                    >()
            for (candidate in fieldDefinitions) {
                val existing = fieldsByIdentity[candidate.identity]
                if (existing != null && existing.conflictsWith(candidate)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "conflicting physical FieldDef descriptions for one local field",
                    )
                }
                fieldsByIdentity.putIfAbsent(candidate.identity, candidate)
            }

            val varianceAuthoritiesByDefinition = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                    DotNetGenericOwnerPhysicalVarianceConstraintAuthority,
                    >()
            for (authority in varianceConstraintAuthorities) {
                if (authority.constrainedTypeDefinitions.isEmpty()) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a variance constraint authority must name retained TypeDefs",
                    )
                }
                for (definition in authority.constrainedTypeDefinitions) {
                    if (definition !in retainedForeignTypeDefinitions ||
                        definition !in typesByIdentity
                    ) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    val existing = varianceAuthoritiesByDefinition[definition]
                    if (existing != null && existing !== authority) {
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                            "multiple selected constraint authorities describe one retained TypeDef",
                        )
                    }
                    varianceAuthoritiesByDefinition[definition] = authority
                }
            }

            val declarationsWithoutEdges = DotNetGenericOwnerPhysicalDeclarationIndex(
                epoch,
                typesByIdentity,
                methodsByIdentity,
                fieldsByIdentity,
                emptyMap(),
                directSupertypeConstraintProofs,
                varianceAuthoritiesByDefinition,
                retainedForeignTypeDefinitions,
                retainedForeignMethodDefinitions,
                producerRecordedDelegateTypeDefinitions,
            )
            for (candidate in typesByIdentity.values) {
                for (parameter in candidate.genericParameters) {
                    for (constraint in parameter.constraints) {
                        val foreignBinder = constraint.firstGenericBinderOutsideTypeOrNull(
                            candidate.identity,
                        )
                        if (foreignBinder != null) {
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                                "physical TypeDef GenericParam constraint references generic binder " +
                                        "$foreignBinder outside ${candidate.identity}",
                            )
                        }
                        when (val validation = declarationsWithoutEdges.validateCarrierOrError(constraint)) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    }
                }
            }
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
                    candidate.genericParameters.forEach { parameter ->
                        addAll(parameter.constraints)
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
            for (candidate in fieldsByIdentity.values) {
                if (typesByIdentity[candidate.declaringType] == null) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                val localIdentity = candidate.identity as? DotNetGenericOwnerPhysicalFieldDefIdentity.Local
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                val localDeclaringType = candidate.declaringType as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a local physical FieldDef requires a local declaring TypeDef",
                    )
                if (localIdentity.field.owner.parent != localDeclaringType.owner.owner ||
                    localDeclaringType.view != null
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a local physical FieldDef must belong to its exact declaring TypeDef",
                    )
                }
                val foreignBinder = candidate.carrier.firstGenericBinderOutsideTypeOrNull(
                    candidate.declaringType,
                )
                if (foreignBinder != null) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical FieldDef carrier references a generic binder outside its " +
                                "declaring TypeDef",
                    )
                }
                when (val validation = declarationsWithoutEdges.validateCarrierOrError(candidate.carrier)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return validation
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
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
            for (proof in directSupertypeConstraintProofs) {
                val source = proof.source as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof requires a retained foreign source",
                    )
                val edgeTarget = proof.edgeTarget.definition as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof requires a retained foreign edge target",
                    )
                val constrainedTarget = proof.constrainedConstruction.definition as?
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof requires a retained foreign construction",
                    )
                if (source !in retainedForeignTypeDefinitions ||
                    edgeTarget !in retainedForeignTypeDefinitions ||
                    constrainedTarget !in retainedForeignTypeDefinitions
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                if (typesByIdentity[constrainedTarget]?.genericParameters
                        ?.all(DotNetGenericOwnerPhysicalGenericParameterReference::isUnconstrained) != false
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof must name a constrained construction",
                    )
                }
                if (edgesBySource[source]?.edges?.any { edge ->
                        edge.target == proof.edgeTarget
                    } != true
                ) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof does not match a recorded edge",
                    )
                }
                if (!proof.edgeTarget.containsConstruction(proof.constrainedConstruction)) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a direct-supertype constraint proof names a construction outside its edge",
                    )
                }
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
                    fieldsByIdentity,
                    edgesBySource,
                    directSupertypeConstraintProofs,
                    varianceAuthoritiesByDefinition,
                    retainedForeignTypeDefinitions,
                    retainedForeignMethodDefinitions,
                    producerRecordedDelegateTypeDefinitions,
                ),
            )
        }
    }
}

private fun DotNetGenericOwnerSymbolicCarrierReference.containsConstruction(
    candidate: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
): Boolean = when (this) {
    candidate -> true
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        arguments.any { argument -> argument.containsConstruction(candidate) }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
        element.containsConstruction(candidate)
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf,
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter,
    -> false
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

            /**
             * Creates an unbound self-reference while a TypeDef candidate is being assembled.
             * The declaration index remains the authority which validates arity and ownership.
             */
            fun unboundTypeParameterReference(
                definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
                index: Int,
            ): Parameter = Parameter(
                DotNetGenericOwnerPhysicalGenericBinderReference.Type(definition),
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
            /**
             * Creates an unbound construction while a complete declaration graph is assembled.
             * [DotNetGenericOwnerPhysicalDeclarationIndex.bind] validates every referenced
             * TypeDef, argument, constraint, and binder before the graph becomes authority.
             */
            fun unboundTypeReference(
                definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
                arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
            ): Constructed = Constructed(definition, arguments.toList())

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

private fun DotNetGenericOwnerSymbolicCarrierReference.firstGenericBinderOutsideTypeOrNull(
    declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
): DotNetGenericOwnerPhysicalGenericBinderReference? = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> null
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
        binder.takeUnless { candidate ->
            candidate == DotNetGenericOwnerPhysicalGenericBinderReference.Type(declaringType)
        }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        arguments.firstNotNullOfOrNull { argument ->
            argument.firstGenericBinderOutsideTypeOrNull(declaringType)
        }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
        element.firstGenericBinderOutsideTypeOrNull(declaringType)
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
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> =
            bind(declarations, type, authenticatedConstruction = null)

        fun bindWithinAuthenticatedView(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            type: DotNetGenericOwnerSymbolicCarrierReference,
            authority: DotNetGenericOwnerAuthenticatedPhysicalView,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> =
            bind(declarations, type, authority.construction)

        private fun bind(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            type: DotNetGenericOwnerSymbolicCarrierReference,
            authenticatedConstruction: DotNetGenericOwnerSymbolicCarrierReference.Constructed?,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCarrier> {
            if (type == voidCarrier()) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "void is a result absence and cannot become a physical value carrier",
                )
            }
            val validation = if (authenticatedConstruction != null) {
                declarations.validateCarrierWithinConstructionScopeOrError(
                    type,
                    authenticatedConstruction,
                )
            } else when (type) {
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
    CLR_REFERENCE_VARIANCE_CONVERSION,
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
 * Joins two already-split values only when both name the same verifier-visible payload carrier.
 *
 * Unlike a general control-flow join, this query has no common-carrier selector: it cannot widen
 * `!n`, choose `object`, materialize nullable state, or turn another produced layout into a pair.
 * The null-state and provenance components still use the ordinary value-fact join once equality of
 * the complete split layout has been established.
 */
internal fun DotNetGenericOwnerProducedValueFact.joinAtIdenticalSplitNullablePayloadOrNull(
    other: DotNetGenericOwnerProducedValueFact,
): DotNetGenericOwnerProducedValueFact? {
    val left = layout as? DotNetGenericOwnerProducedValueLayout.SplitNullable ?: return null
    val right = other.layout as? DotNetGenericOwnerProducedValueLayout.SplitNullable ?: return null
    if (left.payloadCarrier != right.payloadCarrier) return null
    return join(other) { first, second -> first.takeIf { it == second } }
        .takeIf { joined -> joined.layout == left }
}

/** Collects only direct or recorded views rooted in already-guaranteed value evidence. */
private fun DotNetGenericOwnerProducedValueFact.recordedPhysicalSourceViewsOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
): DotNetGenericOwnerPhysicalBindingResult<Set<DotNetGenericOwnerPhysicalView>> {
    if (!nullState.canBeNonNull) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val knownViews = (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
        ?.views ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val sourceViews = linkedSetOf<DotNetGenericOwnerPhysicalView>()
    sourceViews += knownViews
    val sourceConstructions = linkedSetOf<
            DotNetGenericOwnerSymbolicCarrierReference.Constructed,
            >()
    ((layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier?.type as?
            DotNetGenericOwnerSymbolicCarrierReference.Constructed)
        ?.let { construction ->
            sourceConstructions += construction
            sourceViews += DotNetGenericOwnerPhysicalView(construction)
        }
    knownViews.mapTo(sourceConstructions) { view -> view.construction }
    for (sourceConstruction in sourceConstructions) {
        when (val closure = declarations.physicalInterfaceViewClosureOrError(sourceConstruction)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                sourceViews += closure.value.interfaceViews
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(closure.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
        }
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(sourceViews.toSet())
}

/**
 * Capability minted only after one value independently proves an already-selected physical view.
 * Merely naming a construction, including as selected lineage, can never create this authority.
 */
internal class DotNetGenericOwnerAuthenticatedPhysicalView private constructor(
    val view: DotNetGenericOwnerPhysicalView,
) {
    val construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed
        get() = view.construction

    companion object {
        fun prove(
            value: DotNetGenericOwnerProducedValueFact,
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            requiredView: DotNetGenericOwnerPhysicalView,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerAuthenticatedPhysicalView> {
            val sourceViews = when (val sources =
                value.recordedPhysicalSourceViewsOrError(declarations)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> sources.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(sources.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            var found = requiredView in sourceViews
            if (!found) {
                for (sourceView in sourceViews) {
                    if (sourceView.family != requiredView.family) continue
                    when (val conversion = declarations
                        .proveClrReferenceVarianceConversionOrError(sourceView, requiredView)
                    ) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                            found = true
                            break
                        }
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conversion
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
                    }
                }
            }
            if (!found) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            return when (val validation =
                declarations.validateCarrierWithinConstructionScopeOrError(
                    requiredView.construction,
                    requiredView.construction,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerAuthenticatedPhysicalView(requiredView),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }
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

/**
 * Joins two reaching values at one interface family already guaranteed by both physical graphs.
 *
 * An identical direct carrier survives without being weakened. Otherwise [family] is only a
 * selector: the replacement construction must occur in the recorded source-view closure of both
 * non-null values. A common selected lineage may disambiguate several shared constructions;
 * otherwise exactly one construction in the selected family is required. Ordinary ambiguity is
 * unavailable dataflow precision, not contradictory declaration authority.
 */
internal fun DotNetGenericOwnerProducedValueFact.joinAtRecordedPhysicalInterfaceFamilyOrError(
    other: DotNetGenericOwnerProducedValueFact,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    family: DotNetGenericOwnerPhysicalTypeDefIdentity,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerProducedValueFact> {
    if (!nullState.canBeNonNull || !other.nullState.canBeNonNull ||
        layout !is DotNetGenericOwnerProducedValueLayout.Direct ||
        other.layout !is DotNetGenericOwnerProducedValueLayout.Direct
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val leftCarrier = layout.carrier
    val rightCarrier = other.layout.carrier
    if (leftCarrier == rightCarrier) {
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            join(other) { first, second -> first.takeIf { it == second } },
        )
    }
    val leftViews = when (val sources = recordedPhysicalSourceViewsOrError(declarations)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> sources.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return sources
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val rightViews = when (val sources = other.recordedPhysicalSourceViewsOrError(declarations)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> sources.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return sources
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val sharedViews = (leftViews intersect rightViews).filter { view -> view.family == family }.toSet()
    val sharedLineage = provenance.selectedViewLineage[family]?.takeIf { selected ->
        other.provenance.selectedViewLineage[family] == selected && selected in sharedViews
    }
    val selectedView = sharedLineage ?: sharedViews.singleOrNull()
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val selectedCarrier = when (val carrier = declarations.carrierOrError(selectedView.construction)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> carrier.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return carrier
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (selectedCarrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }

    fun DotNetGenericOwnerProducedValueFact.selectSharedView():
            DotNetGenericOwnerProducedValueFact {
        val selected = provenance
            .guarantee(
                selectedView,
                DotNetGenericOwnerPhysicalViewEvidence.RECORDED_INTERFACE_EDGE,
            )
            .selectViewOrNull(selectedView)
            ?: error("a shared recorded physical view must remain selectable")
        return copy(provenance = selected)
    }

    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        selectSharedView().join(other.selectSharedView()) { _, _ -> selectedCarrier },
    )
}

/**
 * Records one verifier-valid CLR variance view without changing the produced reference carrier.
 * The target is derived only from a construction already guaranteed on this same non-null value;
 * logical Kotlin subtyping and selected lineage never enter the proof.
 */
internal fun DotNetGenericOwnerProducedValueFact.selectClrReferenceVarianceViewOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    desiredView: DotNetGenericOwnerPhysicalView,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerProducedValueFact> {
    if (!nullState.canBeNonNull) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val knownViews = (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
        ?.views ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (desiredView in knownViews) {
        val selected = provenance.selectViewOrNull(desiredView)
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        return DotNetGenericOwnerPhysicalBindingResult.Bound(copy(provenance = selected))
    }

    val sourceViews = when (val sources = recordedPhysicalSourceViewsOrError(declarations)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> sources.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(sources.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    for (sourceView in sourceViews) {
        if (sourceView.family != desiredView.family || sourceView == desiredView) continue
        when (val conversion = declarations.proveClrReferenceVarianceConversionOrError(
            sourceView,
            desiredView,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                val converted = provenance.guarantee(
                    desiredView,
                    DotNetGenericOwnerPhysicalViewEvidence.CLR_REFERENCE_VARIANCE_CONVERSION,
                ).selectViewOrNull(desiredView)
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a proven CLR variance view could not become selected lineage",
                    )
                return DotNetGenericOwnerPhysicalBindingResult.Bound(
                    copy(provenance = converted),
                )
            }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conversion
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
        }
    }
    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
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

/** Physical layout selected for one destination independently from any reaching definition. */
internal sealed interface DotNetGenericOwnerPhysicalStorageLayout {
    val primaryCarrier: DotNetGenericOwnerStorageCarrier.Fixed

    data class Direct(
        override val primaryCarrier: DotNetGenericOwnerStorageCarrier.Fixed,
    ) : DotNetGenericOwnerPhysicalStorageLayout

    /** The primary carrier stores the typed payload; a distinct Boolean slot stores logical null. */
    data class SplitNullable(
        override val primaryCarrier: DotNetGenericOwnerStorageCarrier.Fixed,
    ) : DotNetGenericOwnerPhysicalStorageLayout
}

/**
 * Selects the bounded owner-parameter pair layout only when the local, produced payload, and
 * enclosing split MethodDef all name the same physical owner parameter.
 */
internal fun splitNullableOwnerParameterStorageLayoutOrNull(
    produced: DotNetGenericOwnerProducedValueFact,
    localOwnerParameterIndex: Int,
    enclosingOwnerParameterIndex: Int,
    ownerParameterCarriers: List<DotNetGenericOwnerPhysicalCarrier>,
): DotNetGenericOwnerPhysicalStorageLayout.SplitNullable? {
    if (localOwnerParameterIndex != enclosingOwnerParameterIndex) return null
    val selectedCarrier = ownerParameterCarriers.getOrNull(localOwnerParameterIndex) ?: return null
    val payload = (produced.layout as? DotNetGenericOwnerProducedValueLayout.SplitNullable)
        ?.payloadCarrier ?: return null
    if (payload != selectedCarrier) return null
    return DotNetGenericOwnerPhysicalStorageLayout.SplitNullable(
        DotNetGenericOwnerStorageCarrier.Fixed(selectedCarrier),
    )
}

internal data class DotNetGenericOwnerPhysicalStorageFact(
    val storageLayout: DotNetGenericOwnerPhysicalStorageLayout,
    val contentsProvenance: DotNetGenericOwnerPhysicalValueProvenance,
    val contentsNullState: DotNetGenericOwnerPhysicalNullState,
) {
    init {
        require(storageLayout is DotNetGenericOwnerPhysicalStorageLayout.SplitNullable ||
                contentsNullState == DotNetGenericOwnerPhysicalNullState.NON_NULL ||
                storageLayout.primaryCarrier.carrier.canRepresentNull) {
            "nullable storage contents require a carrier which represents null"
        }
        require(contentsNullState.canBeNonNull ||
                contentsProvenance == DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()) {
            "definitely-null storage contents cannot guarantee a non-null physical view"
        }
    }

    fun read(): DotNetGenericOwnerPhysicalFlowFact.Reachable {
        val value = when (val layout = storageLayout) {
            is DotNetGenericOwnerPhysicalStorageLayout.Direct -> {
                val carrier = layout.primaryCarrier.carrier
                if (contentsNullState == DotNetGenericOwnerPhysicalNullState.NULL &&
                    carrier.acceptsCarrierlessNull
                ) {
                    DotNetGenericOwnerProducedValueFact(
                        DotNetGenericOwnerProducedValueLayout.Null,
                        DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                        DotNetGenericOwnerPhysicalNullState.NULL,
                    )
                } else {
                    val readProvenance = (carrier.type as?
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed)
                        ?.let { construction ->
                            contentsProvenance.guarantee(
                                DotNetGenericOwnerPhysicalView(construction),
                                DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ,
                            )
                        } ?: contentsProvenance
                    DotNetGenericOwnerProducedValueFact(
                        DotNetGenericOwnerProducedValueLayout.Direct(carrier),
                        if (contentsNullState.canBeNonNull) readProvenance
                        else DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                        contentsNullState,
                    )
                }
            }
            is DotNetGenericOwnerPhysicalStorageLayout.SplitNullable -> {
                val carrier = layout.primaryCarrier.carrier
                val readProvenance = (carrier.type as?
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed)
                    ?.let { construction ->
                        contentsProvenance.guarantee(
                            DotNetGenericOwnerPhysicalView(construction),
                            DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ,
                        )
                    } ?: contentsProvenance
                DotNetGenericOwnerProducedValueFact(
                    DotNetGenericOwnerProducedValueLayout.SplitNullable(
                        carrier,
                    ),
                    if (contentsNullState.canBeNonNull) readProvenance
                    else DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                    contentsNullState,
                )
            }
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
        val directStorage = storageLayout as? DotNetGenericOwnerPhysicalStorageLayout.Direct
            ?: return null
        val storageCarrier = directStorage.primaryCarrier
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
    ): DotNetGenericOwnerPhysicalStorageFact? {
        if (storageLayout !is DotNetGenericOwnerPhysicalStorageLayout.Direct) return null
        return value.placeInStorageOrNull(storageLayout, canStoreIdentityPreserving)
    }
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
): DotNetGenericOwnerPhysicalStorageFact? = placeInStorageOrNull(
    DotNetGenericOwnerPhysicalStorageLayout.Direct(storageCarrier),
    canStoreIdentityPreserving,
)

/** Validates one produced layout against an independently selected destination layout. */
internal fun DotNetGenericOwnerProducedValueFact.placeInStorageOrNull(
    storageLayout: DotNetGenericOwnerPhysicalStorageLayout,
    canStoreIdentityPreserving: (
        DotNetGenericOwnerPhysicalCarrier,
        DotNetGenericOwnerPhysicalCarrier,
    ) -> Boolean,
): DotNetGenericOwnerPhysicalStorageFact? = when (storageLayout) {
    is DotNetGenericOwnerPhysicalStorageLayout.Direct -> {
        val storageCarrier = storageLayout.primaryCarrier
        if (layout == DotNetGenericOwnerProducedValueLayout.Null) {
            if (!storageCarrier.carrier.acceptsCarrierlessNull) return null
            DotNetGenericOwnerPhysicalStorageFact(
                storageLayout,
                DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                DotNetGenericOwnerPhysicalNullState.NULL,
            )
        } else {
            val producedCarrier = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
                ?: return null
            if (!canStoreIdentityPreserving(producedCarrier, storageCarrier.carrier) ||
                nullState != DotNetGenericOwnerPhysicalNullState.NON_NULL &&
                !storageCarrier.carrier.canRepresentNull
            ) return null
            DotNetGenericOwnerPhysicalStorageFact(storageLayout, provenance, nullState)
        }
    }
    is DotNetGenericOwnerPhysicalStorageLayout.SplitNullable -> {
        val payload = (layout as? DotNetGenericOwnerProducedValueLayout.SplitNullable)
            ?.payloadCarrier ?: return null
        if (payload != storageLayout.primaryCarrier.carrier ||
            !canStoreIdentityPreserving(payload, storageLayout.primaryCarrier.carrier)
        ) return null
        DotNetGenericOwnerPhysicalStorageFact(storageLayout, provenance, nullState)
    }
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
