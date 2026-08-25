/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerArchitecturePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalAuthorityEpoch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCarrier
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStorageFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalViewEvidence
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStorageCarrier
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerGuaranteedViews
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowCarrierKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowEvidence
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowFunctionRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowGuaranteeState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowNullState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.placeInStorageOrNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * First production-inert consumer of the generic-owner physical-value model.
 *
 * The shadow deliberately recognizes only immutable object-carrier aliases of a current physical
 * receiver or an already-object parameter. It records diagnostics after final routing has reached
 * its fixpoint, but it neither mutates IR nor publishes a fact to any routing/emission structure.
 */
internal class DotNetGenericOwnerPhysicalValueShadowAnalysis(
    private val context: DotNetBackendContext,
) {
    fun analyze(module: IrModuleFragment) {
        check(!context.genericOwnerPhysicalValueShadowAnalysisCompleted) {
            "generic-owner physical-value shadow analysis must run exactly once"
        }
        context.genericOwnerPhysicalValueShadowAnalysisCompleted = true

        val sourceBySemanticHook = context.genericOwnerSemanticHooks.entries.associate { entry ->
            entry.value to entry.key
        }
        val snapshots = mutableListOf<DotNetGenericOwnerPhysicalValueShadowSnapshot>()
        context.genericOwnerArchitecturePlans.values
            .asSequence()
            .filter(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            .filter { plan -> plan.owner.kind == ClassKind.CLASS }
            .filter { plan -> plan.owner.fileOrNull() in module.files }
            .sortedBy { plan -> plan.owner.stableShadowName() }
            .forEach { plan ->
                val authority = bindOwnerAuthorityOrNull(plan)
                plan.owner.declarations.filterIsInstance<IrSimpleFunction>()
                    .forEach { function ->
                        snapshots += analyzeFunction(
                            plan.owner,
                            function,
                            sourceBySemanticHook[function],
                            authority,
                        )
                    }
            }

        context.genericOwnerPhysicalValueShadows += snapshots
    }

    private fun analyzeFunction(
        owner: IrClass,
        function: IrSimpleFunction,
        semanticSource: IrSimpleFunction?,
        authority: OwnerAuthority?,
    ): List<DotNetGenericOwnerPhysicalValueShadowSnapshot> {
        val body = function.body as? IrBlockBody ?: return emptyList()
        val storageByValue = linkedMapOf<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>()
        if (authority != null) {
            function.parameters.forEach { parameter ->
                when {
                    parameter === function.dispatchReceiverParameter ->
                        storageByValue[parameter.symbol] = authority.receiverStorage
                    parameter.kind == IrParameterKind.Regular && parameter.type.isObjectShadowType() -> {
                        val nullState = if (parameter.type.isNullableAny()) {
                            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
                        } else {
                            DotNetGenericOwnerPhysicalNullState.NON_NULL
                        }
                        storageByValue[parameter.symbol] = DotNetGenericOwnerPhysicalStorageFact(
                            DotNetGenericOwnerStorageCarrier.Fixed(authority.objectCarrier),
                            DotNetGenericOwnerPhysicalValueProvenance(DotNetGenericOwnerGuaranteedViews.Unknown),
                            nullState,
                        )
                    }
                }
            }
        }

        val source = semanticSource ?: function
        val role = when {
            semanticSource != null -> DotNetGenericOwnerPhysicalValueShadowFunctionRole.SEMANTIC_HOOK
            function in context.genericOwnerSemanticHooks ->
                DotNetGenericOwnerPhysicalValueShadowFunctionRole.TYPED_ENTRY
            else -> DotNetGenericOwnerPhysicalValueShadowFunctionRole.OTHER
        }
        return body.statements.mapNotNull { statement ->
            val variable = statement as? IrVariable ?: return@mapNotNull null
            if (!variable.type.isObjectShadowType()) return@mapNotNull null
            val diagnostic = ShadowDiagnosticIdentity(owner, source, function, role, variable)
            if (authority == null) {
                return@mapNotNull diagnostic.unsupported("physical declaration authority unavailable")
            }
            if (variable.isVar) {
                return@mapNotNull diagnostic.unsupported("mutable local is outside the first shadow slice")
            }
            val initializer = variable.initializer
                ?: return@mapNotNull diagnostic.unsupported("immutable local has no initializer")
            val produced = evaluateInitializerOrNull(initializer, storageByValue)
                ?: return@mapNotNull diagnostic.unsupported("initializer is outside the first shadow grammar")
            val objectStorage = DotNetGenericOwnerStorageCarrier.Fixed(authority.objectCarrier)
            val placed = produced.placeInStorageOrNull(objectStorage, ::canStoreIdentityPreserving)
                ?: return@mapNotNull diagnostic.unsupported("initializer requires a non-identity storage conversion")
            storageByValue[variable.symbol] = placed
            diagnostic.analyzed(produced, placed, owner, authority.identity)
        }
    }

    private fun evaluateInitializerOrNull(
        expression: IrExpression,
        storageByValue: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
    ): DotNetGenericOwnerProducedValueFact? = when (expression) {
        is IrGetValue -> storageByValue[expression.symbol]?.read()?.value
        is IrTypeOperatorCall -> when (expression.operator) {
            IrTypeOperator.IMPLICIT_CAST -> {
                if (!expression.type.isObjectShadowType()) null
                else evaluateInitializerOrNull(expression.argument, storageByValue)
            }
            IrTypeOperator.IMPLICIT_NOTNULL -> {
                val value = evaluateInitializerOrNull(expression.argument, storageByValue)
                    ?: return null
                if (!value.nullState.canBeNonNull || value.layout == DotNetGenericOwnerProducedValueLayout.Null) {
                    null
                } else {
                    value.copy(nullState = DotNetGenericOwnerPhysicalNullState.NON_NULL)
                }
            }
            else -> null
        }
        else -> null
    }

    /**
     * Projects only the self construction selected by this admitted early representation plan.
     * It must not grow into an adapter for ancestry, foreign metadata, or a later authority epoch.
     */
    private fun bindOwnerAuthorityOrNull(plan: DotNetGenericOwnerArchitecturePlan): OwnerAuthority? {
        check(plan.isReifiedByGenericOwnerRehearsal && plan.owner.kind == ClassKind.CLASS) {
            "a physical-value self receiver requires an admitted local generic-class plan"
        }
        val owner = plan.owner
        val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner.symbol, view = null)
        val declarations = when (val result = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
            listOf(
                DotNetGenericOwnerPhysicalTypeDefReference(
                    identity,
                    owner.typeParameters.size,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
            ),
            emptyList(),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val arguments = owner.typeParameters.indices.map { index ->
            when (val result = declarations.typeParameterOrError(identity, index)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                -> return null
            }
        }
        val construction = when (val result = declarations.constructTypeOrError(identity, arguments)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val receiverCarrier = when (val result = declarations.carrierOrError(construction)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val objectCarrier = when (val result = declarations.carrierOrError(
            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val view = DotNetGenericOwnerPhysicalView(construction)
        val receiverProvenance = DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(
                mapOf(view to setOf(DotNetGenericOwnerPhysicalViewEvidence.CURRENT_PHYSICAL_RECEIVER)),
            ),
        )
        return OwnerAuthority(
            identity,
            objectCarrier,
            DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerStorageCarrier.Fixed(receiverCarrier),
                receiverProvenance,
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            ),
        )
    }

    private fun canStoreIdentityPreserving(
        produced: DotNetGenericOwnerPhysicalCarrier,
        storage: DotNetGenericOwnerPhysicalCarrier,
    ): Boolean = produced == storage ||
            storage.type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() &&
            produced.nullEncoding == org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE

    private data class OwnerAuthority(
        val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        val objectCarrier: DotNetGenericOwnerPhysicalCarrier,
        val receiverStorage: DotNetGenericOwnerPhysicalStorageFact,
    )

    private data class ShadowDiagnosticIdentity(
        val owner: IrClass,
        val source: IrSimpleFunction,
        val physical: IrSimpleFunction,
        val role: DotNetGenericOwnerPhysicalValueShadowFunctionRole,
        val variable: IrVariable,
    ) {
        fun unsupported(reason: String) = DotNetGenericOwnerPhysicalValueShadowSnapshot(
            ownerName = owner.stableShadowName(),
            sourceFunctionName = source.name.asString(),
            physicalFunctionName = physical.name.asString(),
            functionRole = role,
            variableName = variable.name.asString(),
            status = DotNetGenericOwnerPhysicalValueShadowStatus.UNSUPPORTED,
            initializerProducedCarrier = unknownCarrierSnapshot,
            storageCarrier = unknownCarrierSnapshot,
            guaranteeState = DotNetGenericOwnerPhysicalValueShadowGuaranteeState.UNKNOWN,
            guaranteedViews = emptyList(),
            selectedViewLineage = emptyList(),
            initializerNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
            contentsNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
            unsupportedReason = reason,
        )

        fun analyzed(
            initializer: DotNetGenericOwnerProducedValueFact,
            storage: DotNetGenericOwnerPhysicalStorageFact,
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowSnapshot {
            val provenance = storage.contentsProvenance
            val known = provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known
            val initializerCarrier = initializer.layout.toCarrierSnapshot(currentOwner, currentIdentity)
            val storageCarrier = storage.storageCarrier.carrier.toCarrierSnapshot(currentOwner, currentIdentity)
            if (initializerCarrier.kind == DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN ||
                storageCarrier.kind == DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN
            ) {
                return unsupported("a physical carrier is outside the first shadow snapshot vocabulary")
            }

            val snapshotsByView = linkedMapOf<
                    DotNetGenericOwnerPhysicalView,
                    DotNetGenericOwnerPhysicalValueShadowViewSnapshot,
                    >()
            known?.evidenceByView.orEmpty().forEach { entry ->
                val view = entry.key
                val evidence = entry.value
                snapshotsByView[view] = view.toSnapshotOrNull(evidence, currentOwner, currentIdentity)
                    ?: return unsupported(
                        "a guaranteed view is outside the first shadow snapshot vocabulary",
                    )
            }
            val views = snapshotsByView.values.sortedBy { snapshot ->
                snapshot.carrier.localOwnerName + ":" + snapshot.carrier.ownerParameterIndices.joinToString(",")
            }
            val lineage = mutableListOf<DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot>()
            provenance.selectedViewLineage.forEach { entry ->
                val family = entry.key
                val selectedView = entry.value
                val view = snapshotsByView[selectedView]
                    ?: return unsupported(
                        "selected lineage is outside the first shadow snapshot vocabulary",
                    )
                val familyOwnerName = (family as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local)
                    ?.owner?.owner?.stableShadowName()
                    ?: return unsupported(
                        "selected lineage family is outside the first shadow snapshot vocabulary",
                    )
                if (familyOwnerName != view.carrier.localOwnerName) {
                    return unsupported("selected lineage does not match its rendered physical family")
                }
                lineage += DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot(familyOwnerName, view)
            }
            lineage.sortBy(DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot::familyOwnerName)
            return DotNetGenericOwnerPhysicalValueShadowSnapshot(
                ownerName = owner.stableShadowName(),
                sourceFunctionName = source.name.asString(),
                physicalFunctionName = physical.name.asString(),
                functionRole = role,
                variableName = variable.name.asString(),
                status = DotNetGenericOwnerPhysicalValueShadowStatus.ANALYZED,
                initializerProducedCarrier = initializerCarrier,
                storageCarrier = storageCarrier,
                guaranteeState = if (known == null) {
                    DotNetGenericOwnerPhysicalValueShadowGuaranteeState.UNKNOWN
                } else {
                    DotNetGenericOwnerPhysicalValueShadowGuaranteeState.KNOWN
                },
                guaranteedViews = views,
                selectedViewLineage = lineage,
                initializerNullState = initializer.nullState.toSnapshot(),
                contentsNullState = storage.contentsNullState.toSnapshot(),
                unsupportedReason = null,
            )
        }
    }

    private companion object {
        val unknownCarrierSnapshot = DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN,
        )

        fun IrElement.fileOrNull(): IrFile? {
            var current: IrDeclarationParent? = when (this) {
                is IrDeclarationParent -> this
                else -> null
            }
            while (current != null) {
                if (current is IrFile) return current
                current = (current as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.parent
            }
            return null
        }

        fun IrClass.stableShadowName(): String {
            fqNameWhenAvailable?.asString()?.takeIf(String::isNotEmpty)?.let { return it }
            val components = mutableListOf<String>()
            var current: IrDeclarationParent? = this
            while (current != null) {
                when (current) {
                    is IrDeclarationWithName -> current.name.asString()
                        .takeIf(String::isNotEmpty)
                        ?.let(components::add)
                    is IrPackageFragment -> current.packageFqName.asString()
                        .takeIf(String::isNotEmpty)
                        ?.let(components::add)
                }
                current = (current as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.parent
            }
            return components.asReversed().joinToString(".").ifEmpty { "<anonymous-owner>" }
        }

        fun org.jetbrains.kotlin.ir.types.IrType.isObjectShadowType(): Boolean = isAny() || isNullableAny()

        fun DotNetGenericOwnerProducedValueLayout.toCarrierSnapshot(
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot = when (this) {
            is DotNetGenericOwnerProducedValueLayout.Direct ->
                carrier.toCarrierSnapshot(currentOwner, currentIdentity)
            DotNetGenericOwnerProducedValueLayout.Null,
            is DotNetGenericOwnerProducedValueLayout.SplitNullable,
            DotNetGenericOwnerProducedValueLayout.Unknown,
            -> unknownCarrierSnapshot
        }

        fun DotNetGenericOwnerPhysicalCarrier.toCarrierSnapshot(
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot = when {
            type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() ->
                DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
                )
            type is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
                type.toCarrierSnapshot(currentOwner, currentIdentity)
            else -> unknownCarrierSnapshot
        }

        fun DotNetGenericOwnerPhysicalView.toSnapshotOrNull(
            evidence: Set<DotNetGenericOwnerPhysicalViewEvidence>,
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowViewSnapshot? {
            val carrier = construction.toCarrierSnapshot(currentOwner, currentIdentity)
            if (carrier.kind != DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION) {
                return null
            }
            return DotNetGenericOwnerPhysicalValueShadowViewSnapshot(
                carrier,
                evidence.mapTo(linkedSetOf()) { item ->
                    DotNetGenericOwnerPhysicalValueShadowEvidence.valueOf(item.name)
                },
            )
        }

        fun DotNetGenericOwnerSymbolicCarrierReference.Constructed.toCarrierSnapshot(
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot {
            if (definition != currentIdentity) return unknownCarrierSnapshot
            val indices = arguments.mapNotNull { argument ->
                val parameter = argument as? DotNetGenericOwnerSymbolicCarrierReference.Parameter
                    ?: return@mapNotNull null
                parameter.index.takeIf {
                    parameter.binder == org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericBinderReference.Type(
                        currentIdentity,
                    )
                }
            }
            return if (indices.size == arguments.size) {
                DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
                    currentOwner.stableShadowName(),
                    indices,
                )
            } else {
                unknownCarrierSnapshot
            }
        }

        fun DotNetGenericOwnerPhysicalNullState.toSnapshot():
                DotNetGenericOwnerPhysicalValueShadowNullState =
            DotNetGenericOwnerPhysicalValueShadowNullState.valueOf(name)
    }
}
