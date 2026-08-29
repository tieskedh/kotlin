/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Public diagnostic shape of one BOUND state FieldDef sealed by final emitter evidence. */
enum class DotNetGenericOwnerPhysicalStateEmissionCarrierKind {
    OWNER_TYPE_PARAMETER,
    OBJECT,
}

data class DotNetGenericOwnerPhysicalStateEmissionSnapshot(
    val scope: DotNetIlEmissionScope,
    val ownerName: String,
    val logicalFieldName: String,
    val physicalFieldName: String,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
    val carrierKind: DotNetGenericOwnerPhysicalStateEmissionCarrierKind,
    val ownerParameterIndex: Int?,
) {
    init {
        require(ownerName.isNotEmpty() && logicalFieldName.isNotEmpty() && physicalFieldName.isNotEmpty()) {
            "a sealed physical state FieldDef requires complete diagnostic names"
        }
        require((carrierKind == DotNetGenericOwnerPhysicalStateEmissionCarrierKind.OWNER_TYPE_PARAMETER) ==
                (ownerParameterIndex != null)) {
            "a sealed state FieldDef requires an owner index exactly for an owner-parameter carrier"
        }
    }
}

/**
 * Seals producer-wide state selection against the exact successful TypeDef/FieldDef render.
 *
 * Missing, duplicate, wrong-scope, or mismatched evidence is an internal conflict. It must never
 * inherit a BOUND carrier or fall back to the logical Kotlin field type.
 */
internal fun DotNetLocalGenericOwnerPhysicalAuthority.sealFinalStateFields(
    successfulEmissions: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
): List<DotNetGenericOwnerPhysicalStateEmissionSnapshot> = stateFamilies().flatMap { family ->
    val owner = family.owner.owner.owner
    val scope = DotNetIlEmissionScope.entries.single { candidate -> candidate.owns(owner) }
    val scopeEmission = successfulEmissions.singleOrNull { emission -> emission.scope == scope }
        ?: error("Internal .NET backend error: BOUND state owner has no unique successful emission scope")
    fun DotNetGenericOwnerPhysicalTypeDefEmissionObservation.claimsBoundStateOwnerOrField(): Boolean =
        claimedAliases.any(family.owner::sameLocalTypeIdentityAs) ||
                (physicalType as? DotNetGenericOwnerObservedMethodDefOwner.Local)
                    ?.typeDef
                    ?.aliases
                    ?.any(family.owner::sameLocalTypeIdentityAs) == true ||
                fieldDefinitions.any { field ->
                    field.physicalField in family.boundInstanceFields ||
                            family.states.any { state ->
                                field.physicalFieldIdentity == state.fieldDefinition.identity
                            }
                }
    val crossScopeDuplicates = successfulEmissions
        .asSequence()
        .filter { emission -> emission.scope != scope }
        .flatMap { emission -> emission.typeDefs.asSequence() }
        .filter { typeDef -> typeDef.claimsBoundStateOwnerOrField() }
        .toList()
    if (crossScopeDuplicates.isNotEmpty()) {
        error("Internal .NET backend error: BOUND state owner or FieldDef escaped its emission scope")
    }
    val observedOwner = scopeEmission.typeDefs
        .filter { typeDef -> typeDef.claimsBoundStateOwnerOrField() }
        .singleOrNull() ?: error(
        "Internal .NET backend error: BOUND state owner has no unique final TypeDef observation",
    )
    val physicalOwner = (observedOwner.physicalType as? DotNetGenericOwnerObservedMethodDefOwner.Local)
        ?.typeDef ?: error(
        "Internal .NET backend error: BOUND state owner resolved to an unbindable final TypeDef",
    )
    if (physicalOwner.aliases.none(family.owner::sameLocalTypeIdentityAs)) {
        error("Internal .NET backend error: final state owner lost its BOUND TypeDef identity")
    }
    val expectedOwner = checkNotNull(inputOrNull(family.owner)) {
        "Internal .NET backend error: BOUND state owner lost its declaration input"
    }
    if (physicalOwner.genericArity != expectedOwner.genericArity ||
        physicalOwner.category != expectedOwner.category
    ) {
        error("Internal .NET backend error: final state owner contradicts its BOUND TypeDef shape")
    }
    val observedInstanceFields = observedOwner.fieldDefinitions.filterNot { field -> field.isStatic }
    if (observedInstanceFields.size != family.boundInstanceFields.size ||
        observedInstanceFields.mapTo(linkedSetOf()) { field -> field.physicalField } != family.boundInstanceFields
    ) {
        error("Internal .NET backend error: final TypeDef changed its complete BOUND instance-field set")
    }
    family.states.map { state ->
        val expected = state.fieldDefinition
        val observed = observedOwner.fieldDefinitions.singleOrNull { field ->
            field.physicalField === state.field && field.physicalFieldIdentity == expected.identity
        } ?: error(
            "Internal .NET backend error: BOUND state field has no unique final FieldDef observation",
        )
        if (observed.visibility != DotNetIlRawMethodDefVisibility.PRIVATE ||
            observed.isStatic != expected.isStatic || observed.isInitOnly != expected.isInitOnly
        ) {
            error("Internal .NET backend error: final FieldDef flags contradict BOUND state authority")
        }
        val carrierKind: DotNetGenericOwnerPhysicalStateEmissionCarrierKind
        val ownerParameterIndex: Int?
        when (val expectedCarrier = expected.carrier) {
            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() -> {
                if (observed.carrier != DotNetGenericOwnerObservedMethodCarrier.Leaf(
                        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                    )) {
                    error("Internal .NET backend error: final object FieldDef contradicts BOUND state authority")
                }
                carrierKind = DotNetGenericOwnerPhysicalStateEmissionCarrierKind.OBJECT
                ownerParameterIndex = null
            }
            is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
                val actual = observed.carrier as? DotNetGenericOwnerObservedMethodCarrier.OwnerParameter
                    ?: error(
                        "Internal .NET backend error: final typed FieldDef lost its owner-parameter carrier",
                    )
                val expectedBinder = expectedCarrier.binder as?
                        DotNetGenericOwnerPhysicalGenericBinderReference.Type
                    ?: error("Internal .NET backend error: BOUND state used a MethodDef parameter")
                if (expectedBinder.definition != family.owner || actual.index != expectedCarrier.index ||
                    actual.binder != physicalOwner
                ) {
                    error("Internal .NET backend error: final typed FieldDef changed its exact binder")
                }
                carrierKind = DotNetGenericOwnerPhysicalStateEmissionCarrierKind.OWNER_TYPE_PARAMETER
                ownerParameterIndex = actual.index
            }
            is DotNetGenericOwnerSymbolicCarrierReference.Leaf,
            is DotNetGenericOwnerSymbolicCarrierReference.Constructed,
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray,
            -> error("Internal .NET backend error: unsupported BOUND state carrier '$expectedCarrier'")
        }
        DotNetGenericOwnerPhysicalStateEmissionSnapshot(
            scope = scope,
            ownerName = expectedOwner.logicalOwnerName,
            logicalFieldName = state.logicalFieldName,
            physicalFieldName = observed.physicalName,
            requirement = state.requirement,
            carrierKind = carrierKind,
            ownerParameterIndex = ownerParameterIndex,
        )
    }
}.sortedWith(compareBy(
    DotNetGenericOwnerPhysicalStateEmissionSnapshot::scope,
    DotNetGenericOwnerPhysicalStateEmissionSnapshot::ownerName,
    DotNetGenericOwnerPhysicalStateEmissionSnapshot::logicalFieldName,
))
