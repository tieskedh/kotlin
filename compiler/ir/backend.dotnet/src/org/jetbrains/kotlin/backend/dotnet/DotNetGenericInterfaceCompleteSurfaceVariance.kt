/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType

/** ECMA-335 input/output occurrence set for one physical TypeDef parameter. */
internal enum class DotNetGenericInterfaceCompleteSurfacePolarity(private val mask: Int) {
    NONE(0),
    OUT(1),
    IN(2),
    BOTH(3);

    fun join(other: DotNetGenericInterfaceCompleteSurfacePolarity):
            DotNetGenericInterfaceCompleteSurfacePolarity = fromMask(mask or other.mask)

    fun through(variance: DotNetGenericOwnerPhysicalTypeParameterVariance):
            DotNetGenericInterfaceCompleteSurfacePolarity = when {
        this == NONE -> NONE
        variance == DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT -> this
        variance == DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT -> when (this) {
            NONE -> NONE
            OUT -> IN
            IN -> OUT
            BOTH -> BOTH
        }
        else -> BOTH
    }

    fun containsInput(): Boolean = mask and IN.mask != 0

    fun containsOutput(): Boolean = mask and OUT.mask != 0

    private companion object {
        fun fromMask(mask: Int): DotNetGenericInterfaceCompleteSurfacePolarity = when (mask) {
            0 -> NONE
            1 -> OUT
            2 -> IN
            3 -> BOTH
            else -> error("invalid complete-surface polarity mask $mask")
        }
    }
}

/**
 * Owner-relative physical type expression used before a candidate natural TypeDef is emitted.
 *
 * This is deliberately smaller than Kotlin's type system. A caller must first resolve stars,
 * projections, nullability conventions, value classes, erased owners, and retained metadata to
 * one honest physical construction. Failing that resolution makes the caller's surface plan
 * unavailable; this vocabulary never fabricates a CLR construction from a logical Kotlin view.
 */
internal sealed interface DotNetGenericInterfaceCompleteSurfaceTypeReference {
    /** A physical carrier which contains no parameter of the owner being planned. */
    data object Independent : DotNetGenericInterfaceCompleteSurfaceTypeReference

    /** `!n` of the owner whose complete surface contains this expression. */
    data class OwnerParameter(
        val index: Int,
    ) : DotNetGenericInterfaceCompleteSurfaceTypeReference {
        init {
            require(index >= 0) { "a complete-surface owner parameter requires a non-negative index" }
        }
    }

    /** One already real or candidate CLR generic construction. */
    data class Constructed(
        val definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        val arguments: List<DotNetGenericInterfaceCompleteSurfaceTypeReference>,
    ) : DotNetGenericInterfaceCompleteSurfaceTypeReference {
        init {
            require(arguments.none { argument -> argument === this }) {
                "a complete-surface construction cannot directly contain itself"
            }
        }
    }

    /** A verifier-visible single-dimensional zero-based array. */
    data class SzArray(
        val element: DotNetGenericInterfaceCompleteSurfaceTypeReference,
    ) : DotNetGenericInterfaceCompleteSurfaceTypeReference
}

/** One result, input, method-generic constraint, inherited edge, or MethodImpl type. */
internal data class DotNetGenericInterfaceCompleteSurfacePosition(
    val polarity: DotNetGenericInterfaceCompleteSurfacePolarity,
    val type: DotNetGenericInterfaceCompleteSurfaceTypeReference,
) {
    init {
        require(polarity != DotNetGenericInterfaceCompleteSurfacePolarity.NONE) {
            "a complete-surface position must contribute an input, output, or invariant obligation"
        }
    }
}

/** One local candidate interface and the complete physical positions of its natural contract. */
internal data class DotNetGenericInterfaceCompleteSurfaceOwnerInput(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val logicalMaximumVariances: List<DotNetGenericOwnerPhysicalTypeParameterVariance>,
    val positions: List<DotNetGenericInterfaceCompleteSurfacePosition>,
) {
    init {
        val localIdentity = identity as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        require(localIdentity?.view == DotNetGenericInterfaceView.DECLARED) {
            "a complete-surface candidate must be one local natural interface TypeDef"
        }
        require(logicalMaximumVariances.isNotEmpty()) {
            "a complete-surface candidate requires at least one owner parameter"
        }
    }
}

/** Already authoritative physical GenericParam variance for a nested non-candidate TypeDef. */
internal data class DotNetGenericInterfaceCompleteSurfaceFixedTypeInput(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val physicalVariances: List<DotNetGenericOwnerPhysicalTypeParameterVariance>,
)

internal data class DotNetGenericInterfaceCompleteSurfaceParameterDecision(
    val index: Int,
    val logicalMaximumVariance: DotNetGenericOwnerPhysicalTypeParameterVariance,
    val requiredPolarity: DotNetGenericInterfaceCompleteSurfacePolarity,
    val selectedPhysicalVariance: DotNetGenericOwnerPhysicalTypeParameterVariance,
) {
    init {
        require(index >= 0) { "a complete-surface parameter decision requires a non-negative index" }
        require(
            logicalMaximumVariance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ||
                    selectedPhysicalVariance == logicalMaximumVariance ||
                    selectedPhysicalVariance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
        ) { "complete-surface variance may only weaken a logical variant parameter to invariant" }
        require(
            logicalMaximumVariance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ||
                    selectedPhysicalVariance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
        ) { "a logically invariant parameter cannot become physically variant" }
        require(
            selectedPhysicalVariance != DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT ||
                    !requiredPolarity.containsInput(),
        ) { "a covariant physical parameter cannot occur in an input position" }
        require(
            selectedPhysicalVariance != DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT ||
                    !requiredPolarity.containsOutput(),
        ) { "a contravariant physical parameter cannot occur in an output position" }
    }
}

internal data class DotNetGenericInterfaceCompleteSurfaceOwnerDecision(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    val parameters: List<DotNetGenericInterfaceCompleteSurfaceParameterDecision>,
) {
    init {
        require(parameters.map { parameter -> parameter.index } == parameters.indices.toList()) {
            "a complete-surface owner decision requires ordered parameter decisions"
        }
    }
}

/** Exact getter/setter membership of one property in the analyzed pre-split surface. */
internal data class DotNetGenericInterfaceCompleteSurfacePropertyAccessorInventory(
    val property: IrPropertySymbol,
    val getter: IrSimpleFunctionSymbol?,
    val setter: IrSimpleFunctionSymbol?,
) {
    init {
        require(getter != null || setter != null) {
            "a complete-surface property inventory requires at least one accessor"
        }
    }
}

/**
 * Frozen IR-bound inventory from which one pre-split complete surface was calculated.
 *
 * [directCallableMembers] is the exact ordered function/accessor vector analyzed by the planner;
 * [directPropertyAccessors] additionally preserves property grouping instead of asking a later
 * consumer to reconstruct it from mutable IR. Parent types retain their complete logical
 * constructions. This inventory is evidence of what was analyzed, not admission authority.
 */
internal class DotNetGenericInterfaceCompleteSurfaceInventory(
    directCallableMembers: List<IrSimpleFunctionSymbol>,
    directPropertyAccessors: List<DotNetGenericInterfaceCompleteSurfacePropertyAccessorInventory>,
    directParentTypes: List<IrType>,
) {
    val directCallableMembers: List<IrSimpleFunctionSymbol> = directCallableMembers.toList()
    val directPropertyAccessors: List<DotNetGenericInterfaceCompleteSurfacePropertyAccessorInventory> =
        directPropertyAccessors.toList()
    val directParentTypes: List<IrType> = directParentTypes.toList()

    init {
        require(this.directCallableMembers.size == this.directCallableMembers.toSet().size) {
            "a complete-surface inventory cannot repeat a direct callable"
        }
        require(this.directPropertyAccessors.map { accessor -> accessor.property }.toSet().size ==
                this.directPropertyAccessors.size) {
            "a complete-surface inventory cannot repeat a direct property"
        }
        val callableSet = this.directCallableMembers.toSet()
        require(this.directPropertyAccessors.all { accessor ->
            listOfNotNull(accessor.getter, accessor.setter).all(callableSet::contains)
        }) {
            "every inventoried property accessor must occur in the analyzed callable surface"
        }
    }
}

/**
 * Early, IR-bound physical-variance plan for one possible complete natural interface.
 *
 * This is stronger than the public diagnostic shadow because it retains exact symbols, the typed
 * planner input, and the typed decision. It remains only EARLY_REPRESENTATION_PLAN evidence: a
 * later lowering must independently apply a bounded admission grammar before routing or emission
 * may consume it. In particular, presence in the all-plans context map is not admission.
 */
internal class DotNetGenericInterfaceCompleteNaturalAuthorityPlan(
    val owner: IrClassSymbol,
    val inventory: DotNetGenericInterfaceCompleteSurfaceInventory,
    surfaceInput: DotNetGenericInterfaceCompleteSurfaceOwnerInput,
    surfaceDecision: DotNetGenericInterfaceCompleteSurfaceOwnerDecision,
) {
    val surfaceInput: DotNetGenericInterfaceCompleteSurfaceOwnerInput = surfaceInput.frozen()
    val surfaceDecision: DotNetGenericInterfaceCompleteSurfaceOwnerDecision = surfaceDecision.frozen()
    val selectedPhysicalVariances: List<DotNetGenericOwnerPhysicalTypeParameterVariance> =
        this.surfaceDecision.parameters.map { parameter -> parameter.selectedPhysicalVariance }

    init {
        val localIdentity = this.surfaceInput.identity as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        require(localIdentity?.owner === owner && localIdentity.view == DotNetGenericInterfaceView.DECLARED) {
            "a complete-natural authority plan must bind its exact local natural TypeDef"
        }
        require(this.surfaceDecision.identity == this.surfaceInput.identity) {
            "a complete-natural authority plan has different input and decision TypeDefs"
        }
        require(this.surfaceDecision.parameters.map { parameter -> parameter.logicalMaximumVariance } ==
                this.surfaceInput.logicalMaximumVariances) {
            "a complete-natural authority plan changed its ordered logical variance vector"
        }
    }
}

/** Exact equality across two independently computed EARLY_REPRESENTATION_PLAN epochs. */
internal fun DotNetGenericInterfaceCompleteNaturalAuthorityPlan.hasSameFrozenAuthorityAs(
    other: DotNetGenericInterfaceCompleteNaturalAuthorityPlan,
): Boolean = owner === other.owner &&
        inventory.directCallableMembers == other.inventory.directCallableMembers &&
        inventory.directPropertyAccessors == other.inventory.directPropertyAccessors &&
        inventory.directParentTypes == other.inventory.directParentTypes &&
        surfaceInput == other.surfaceInput &&
        surfaceDecision == other.surfaceDecision

private fun DotNetGenericInterfaceCompleteSurfaceOwnerInput.frozen() = copy(
    logicalMaximumVariances = logicalMaximumVariances.toList(),
    positions = positions.map { position ->
        position.copy(type = position.type.frozen())
    },
)

private fun DotNetGenericInterfaceCompleteSurfaceOwnerDecision.frozen() = copy(
    parameters = parameters.toList(),
)

private fun DotNetGenericInterfaceCompleteSurfaceTypeReference.frozen():
        DotNetGenericInterfaceCompleteSurfaceTypeReference = when (this) {
    DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent -> this
    is DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter -> copy()
    is DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed -> copy(
        arguments = arguments.map { argument -> argument.frozen() },
    )
    is DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray -> copy(element = element.frozen())
}

internal class DotNetGenericInterfaceCompleteSurfaceVariancePlan(
    owners: Iterable<DotNetGenericInterfaceCompleteSurfaceOwnerDecision>,
) {
    private val ownerList = owners.toList()
    private val ownersByIdentity = ownerList.associateBy { owner -> owner.identity }

    init {
        require(ownersByIdentity.size == ownerList.size) {
            "a complete-surface variance plan cannot contain duplicate owner identities"
        }
    }

    fun ownerOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericInterfaceCompleteSurfaceOwnerDecision? = ownersByIdentity[identity]

    fun owners(): List<DotNetGenericInterfaceCompleteSurfaceOwnerDecision> =
        ownersByIdentity.values.toList()
}

/**
 * Selects the strongest CLR-legal variance no stronger than each Kotlin declaration parameter.
 *
 * Candidate parameters form a finite monotone domain: their logical maximum or invariant. A
 * nested candidate may therefore weaken another candidate, but no iteration can regain precision.
 * Missing physical variance for a relevant nested construction is unavailable authority. An arity
 * contradiction or an escaped `!n` is an authority conflict, not ordinary variance pressure.
 * The surface builder supplies method-generic constraints with their ECMA polarity; constraints
 * on the owner's own GenericParam rows are declaration metadata and deliberately are not positions.
 */
internal fun planDotNetGenericInterfaceCompleteSurfaceVariance(
    owners: List<DotNetGenericInterfaceCompleteSurfaceOwnerInput>,
    fixedTypes: List<DotNetGenericInterfaceCompleteSurfaceFixedTypeInput>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericInterfaceCompleteSurfaceVariancePlan> {
    val ownersByIdentity = linkedMapOf<
            DotNetGenericOwnerPhysicalTypeDefIdentity,
            DotNetGenericInterfaceCompleteSurfaceOwnerInput,
            >()
    for (owner in owners) {
        if (ownersByIdentity.putIfAbsent(owner.identity, owner) != null) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a complete-surface variance input repeats one candidate TypeDef identity",
            )
        }
    }

    val fixedByIdentity = linkedMapOf<
            DotNetGenericOwnerPhysicalTypeDefIdentity,
            DotNetGenericInterfaceCompleteSurfaceFixedTypeInput,
            >()
    for (fixed in fixedTypes) {
        if (fixed.identity in ownersByIdentity) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "one complete-surface TypeDef is both candidate and fixed physical authority",
            )
        }
        val existing = fixedByIdentity.putIfAbsent(fixed.identity, fixed)
        if (existing != null && existing.physicalVariances != fixed.physicalVariances) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "conflicting fixed physical variance for one complete-surface TypeDef",
            )
        }
    }

    val selected = ownersByIdentity.mapValuesTo(linkedMapOf()) { entry ->
        entry.value.logicalMaximumVariances.toMutableList()
    }

    fun DotNetGenericInterfaceCompleteSurfaceTypeReference.referencesOwnerParameter(): Boolean =
        when (this) {
            DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent -> false
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter -> true
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed ->
                arguments.any { argument -> argument.referencesOwnerParameter() }
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray ->
                element.referencesOwnerParameter()
        }

    fun requiredPolarities(
        owner: DotNetGenericInterfaceCompleteSurfaceOwnerInput,
    ): DotNetGenericOwnerPhysicalBindingResult<List<DotNetGenericInterfaceCompleteSurfacePolarity>> {
        val required = MutableList(owner.logicalMaximumVariances.size) {
            DotNetGenericInterfaceCompleteSurfacePolarity.NONE
        }

        fun validateOwnerParameterBinders(
            type: DotNetGenericInterfaceCompleteSurfaceTypeReference,
        ): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (type) {
            DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter ->
                if (type.index in required.indices) {
                    DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                } else {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a complete-surface position references owner parameter ${type.index} " +
                                "outside arity ${required.size}",
                    )
                }
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray ->
                validateOwnerParameterBinders(type.element)
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed -> {
                for (argument in type.arguments) {
                    when (val result = validateOwnerParameterBinders(argument)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            error("structural complete-surface binder validation became unavailable")
                    }
                }
                DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
        }

        // Binder scope is structural authority. Validate it before a missing nested TypeDef can
        // make the same position unavailable and thereby mask an escaped or out-of-range `!n`.
        for (position in owner.positions) {
            when (val result = validateOwnerParameterBinders(position.type)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    error("structural complete-surface binder validation became unavailable")
            }
        }

        fun collect(
            type: DotNetGenericInterfaceCompleteSurfaceTypeReference,
            polarity: DotNetGenericInterfaceCompleteSurfacePolarity,
        ): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (type) {
            DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter -> {
                if (type.index !in required.indices) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a complete-surface position references owner parameter ${type.index} " +
                                "outside arity ${required.size}",
                    )
                } else {
                    required[type.index] = required[type.index].join(polarity)
                    DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                }
            }
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray ->
                collect(
                    type.element,
                    polarity.through(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT),
                )
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed -> {
                val variances = selected[type.definition]
                    ?: fixedByIdentity[type.definition]?.physicalVariances
                if (variances == null) {
                    if (type.referencesOwnerParameter()) {
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    } else {
                        DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                    }
                } else if (variances.size != type.arguments.size) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "a complete-surface construction supplies ${type.arguments.size} arguments " +
                                "to physical arity ${variances.size}",
                    )
                } else {
                    for (index in type.arguments.indices) {
                        when (val result = collect(
                            type.arguments[index],
                            polarity.through(variances[index]),
                        )) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return result
                        }
                    }
                    DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                }
            }
        }

        for (position in owner.positions) {
            when (val result = collect(position.type, position.polarity)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(required)
    }

    fun selectedVariance(
        logicalMaximum: DotNetGenericOwnerPhysicalTypeParameterVariance,
        required: DotNetGenericInterfaceCompleteSurfacePolarity,
    ): DotNetGenericOwnerPhysicalTypeParameterVariance = when (logicalMaximum) {
        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ->
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT ->
            if (required.containsInput()) {
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
            } else {
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
            }
        DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT ->
            if (required.containsOutput()) {
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
            } else {
                DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
            }
    }

    var changed: Boolean
    do {
        changed = false
        for (owner in owners) {
            val required = when (val result = requiredPolarities(owner)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val ownerSelected = selected.getValue(owner.identity)
            for (index in ownerSelected.indices) {
                val next = selectedVariance(owner.logicalMaximumVariances[index], required[index])
                if (ownerSelected[index] != next) {
                    check(ownerSelected[index] != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT) {
                        "complete-surface variance attempted to strengthen an invariant fixpoint decision"
                    }
                    ownerSelected[index] = next
                    changed = true
                }
            }
        }
    } while (changed)

    val decisions = mutableListOf<DotNetGenericInterfaceCompleteSurfaceOwnerDecision>()
    for (owner in owners) {
        val required = when (val result = requiredPolarities(owner)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        decisions += DotNetGenericInterfaceCompleteSurfaceOwnerDecision(
            owner.identity,
            owner.logicalMaximumVariances.indices.map { index ->
                DotNetGenericInterfaceCompleteSurfaceParameterDecision(
                    index,
                    owner.logicalMaximumVariances[index],
                    required[index],
                    selected.getValue(owner.identity)[index],
                )
            },
        )
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericInterfaceCompleteSurfaceVariancePlan(decisions),
    )
}
