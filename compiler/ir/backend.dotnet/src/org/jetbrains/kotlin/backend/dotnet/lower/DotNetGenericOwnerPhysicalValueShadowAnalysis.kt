/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerArchitecturePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerAuthenticatedPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallReceiverProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRoutePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRouteRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCarrier
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDirectResultInitializerPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericBinderReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullEncoding
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStorageLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStorageFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalViewEvidence
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStorageCarrier
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerGuaranteedViews
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCallableEntryKind
import org.jetbrains.kotlin.backend.dotnet.bindExactLocalGenericOwnerDependentCarrierOrError
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowCarrierKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowFunctionRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowGuaranteeState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueLayoutKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowNullState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowPhase
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalDirectResultInitializerPlanOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetFlatExhaustiveSplitOperationCallsOrNull
import org.jetbrains.kotlin.backend.dotnet.declarationIndependentLeafCarrierOrNull
import org.jetbrains.kotlin.backend.dotnet.genericOwnerDeclarationIndependentLeafPrototypeOrNull
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.isDeclarationIndependentLeafCarrier
import org.jetbrains.kotlin.backend.dotnet.hasOnlyUnprotectedDirectFunctionReturnUsesIn
import org.jetbrains.kotlin.backend.dotnet.joinAtIdenticalSplitNullablePayloadOrNull
import org.jetbrains.kotlin.backend.dotnet.joinAtRecordedPhysicalInterfaceFamilyOrError
import org.jetbrains.kotlin.backend.dotnet.placeInStorageOrNull
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError
import org.jetbrains.kotlin.backend.dotnet.selectRecordedPhysicalInterfaceViewOrNull
import org.jetbrains.kotlin.backend.dotnet.isDotNetDirectResultPlacementCall
import org.jetbrains.kotlin.backend.dotnet.isDotNetParameterlessDirectResultPlacementCall
import org.jetbrains.kotlin.backend.dotnet.splitNullableOwnerParameterStorageLayoutOrNull
import org.jetbrains.kotlin.backend.dotnet.splitLocalUseSummaryIn
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
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance
import java.util.IdentityHashMap

private data class DotNetGenericOwnerPhysicalEntryPrototypeParameters(
    val currentTypes: Map<IrValueSymbol, IrType>,
    val fixedLeafCarriers: Map<
            IrValueSymbol,
            DotNetGenericOwnerSymbolicCarrierReference,
            >,
    val methodParameterCarriers: Map<
            IrValueSymbol,
            DotNetGenericOwnerPhysicalCarrier,
            >,
)

private data class DotNetGenericOwnerExactConstructorInputCarrier(
    val carrier: DotNetGenericOwnerPhysicalCarrier,
    val view: DotNetGenericOwnerPhysicalView?,
)

/**
 * Production-inert generic-owner physical-value transfer analysis.
 *
 * Diagnostic snapshots remain read-only. Final IR-bound records may be consumed only through an
 * explicit authority adapter after this analysis completes; the analysis itself neither mutates IR
 * nor supplies a logical type as physical evidence.
 */
internal class DotNetGenericOwnerPhysicalValueShadowAnalysis(
    private val context: DotNetBackendContext,
) {
    private val callRoutesByCall = IdentityHashMap<IrCall, DotNetGenericOwnerCallRoutePlan>().apply {
        context.genericOwnerCallRoutes.forEach { route ->
            check(put(route.call, route) == null) {
                "one generic-owner call site received more than one logical route plan"
            }
        }
    }

    /** Captures the moved authoritative body before any owner-dependent semantic type remap. */
    fun captureBeforeSemanticRemap(
        owner: IrClass,
        source: IrSimpleFunction,
        physical: IrSimpleFunction,
        body: IrBody,
    ) {
        check(context.configuration.dotNetGenericOwnerRehearsal) {
            "the pre-remap physical-value shadow is rehearsal-only"
        }
        val plan = context.genericOwnerArchitecturePlans[owner]
            ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            ?.takeIf { candidate -> candidate.owner.kind == ClassKind.CLASS }
            ?: return
        val records = analyzeFunction(
            owner = owner,
            plan = plan,
            function = physical,
            semanticSource = source,
            authority = bindOwnerAuthorityOrNull(
                plan,
                DotNetGenericOwnerPhysicalValueShadowPhase.PRE_SEMANTIC_REMAP,
            ),
            phase = DotNetGenericOwnerPhysicalValueShadowPhase.PRE_SEMANTIC_REMAP,
            body = body,
        )
        context.genericOwnerPhysicalValueShadowRecords += records
        context.genericOwnerPhysicalValueShadows += records.map { record -> record.snapshot }
    }

    fun analyze(module: IrModuleFragment) {
        check(!context.genericOwnerPhysicalValueShadowFinalAnalysisCompleted) {
            "generic-owner physical-value shadow analysis must run exactly once"
        }

        val sourceBySemanticHook = context.genericOwnerSemanticHooks.entries.associate { entry ->
            entry.value to entry.key
        }
        val records = mutableListOf<DotNetGenericOwnerPhysicalValueShadowRecord>()
        context.genericOwnerArchitecturePlans.values
            .asSequence()
            .filter(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            .filter { plan -> plan.owner.kind == ClassKind.CLASS }
            .filter { plan -> plan.owner.fileOrNull() in module.files }
            .sortedBy { plan -> plan.owner.stableShadowName() }
            .forEach { plan ->
                val authority = bindOwnerAuthorityOrNull(
                    plan,
                    DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING,
                )
                plan.owner.declarations.filterIsInstance<IrSimpleFunction>()
                    .forEach { function ->
                        records += analyzeFunction(
                            plan.owner,
                            plan,
                            function,
                            sourceBySemanticHook[function],
                            authority,
                            DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING,
                            function.body,
                        )
                    }
            }

        context.genericOwnerPhysicalValueShadowRecords += records
        context.genericOwnerPhysicalValueShadows += records.map { record -> record.snapshot }
        context.genericOwnerPhysicalValueShadowFinalAnalysisCompleted = true
    }

    private fun analyzeFunction(
        owner: IrClass,
        plan: DotNetGenericOwnerArchitecturePlan,
        function: IrSimpleFunction,
        semanticSource: IrSimpleFunction?,
        authority: OwnerAuthority?,
        phase: DotNetGenericOwnerPhysicalValueShadowPhase,
        body: IrBody?,
    ): List<DotNetGenericOwnerPhysicalValueShadowRecord> {
        val blockBody = body as? IrBlockBody ?: return emptyList()
        val source = semanticSource ?: function
        val role = when {
            semanticSource != null -> DotNetGenericOwnerPhysicalValueShadowFunctionRole.SEMANTIC_HOOK
            function in context.genericOwnerSemanticHooks ->
                DotNetGenericOwnerPhysicalValueShadowFunctionRole.TYPED_ENTRY
            else -> DotNetGenericOwnerPhysicalValueShadowFunctionRole.OTHER
        }
        return FunctionShadowEngine(
            owner,
            plan,
            source,
            function,
            role,
            phase,
            authority,
            blockBody,
        ).analyze()
    }

    /**
     * One phase-independent transfer engine. Its only physical seeds are declaration authority;
     * neither a source type nor a destination type can manufacture an exact construction.
     */
    private inner class FunctionShadowEngine(
        private val owner: IrClass,
        private val plan: DotNetGenericOwnerArchitecturePlan,
        private val source: IrSimpleFunction,
        private val physical: IrSimpleFunction,
        private val role: DotNetGenericOwnerPhysicalValueShadowFunctionRole,
        private val phase: DotNetGenericOwnerPhysicalValueShadowPhase,
        private val authority: OwnerAuthority?,
        private val body: IrBlockBody,
    ) {
        private val storageByValue = linkedMapOf<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>()
        private val setValueCountBySymbol = java.util.IdentityHashMap<IrValueSymbol, Int>()
        private val records = mutableListOf<DotNetGenericOwnerPhysicalValueShadowRecord>()
        private val currentMethodIdentity =
            if (phase == DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING) {
                authority?.physicalAuthority?.currentMethodOrNull(physical.symbol)
            } else {
                null
            }

        fun analyze(): List<DotNetGenericOwnerPhysicalValueShadowRecord> {
            body.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitSetValue(expression: IrSetValue) {
                    setValueCountBySymbol[expression.symbol] =
                        (setValueCountBySymbol[expression.symbol] ?: 0) + 1
                    expression.acceptChildrenVoid(this)
                }
            })

            val fixedLeafEntryStorage = IdentityHashMap<
                    IrValueSymbol,
                    DotNetGenericOwnerPhysicalStorageFact,
                    >()
            authority?.let { ownerAuthority ->
                val prototypeParameters = entryPrototypeParametersOrNull()
                physical.parameters.forEach { parameter ->
                    when {
                        parameter === physical.dispatchReceiverParameter ->
                            storageByValue[parameter.symbol] = ownerAuthority.receiverStorage
                        parameter.kind == IrParameterKind.Regular -> {
                            val storage = plannedEntryStorageOrNull(
                                prototypeParameters?.currentTypes?.get(parameter.symbol),
                                prototypeParameters?.methodParameterCarriers?.get(parameter.symbol),
                                ownerAuthority,
                            )
                            if (storage != null) {
                                storageByValue[parameter.symbol] = storage
                                val fixedLeaf = prototypeParameters
                                    ?.fixedLeafCarriers
                                    ?.get(parameter.symbol)
                                if (fixedLeaf != null &&
                                    storage.storageLayout is
                                        DotNetGenericOwnerPhysicalStorageLayout.Direct &&
                                    storage.storageLayout.primaryCarrier.carrier.type == fixedLeaf
                                ) {
                                    fixedLeafEntryStorage[parameter.symbol] = storage
                                }
                            } else if (parameter.type.isObjectShadowType()) {
                                // Object is a conservative fallback for generated helpers which
                                // have no detached member prototype. It cannot manufacture an
                                // exact view or narrow an independently broad value.
                                storageByValue[parameter.symbol] = objectEntryStorage(
                                    parameter.type,
                                    ownerAuthority,
                                )
                            }
                        }
                    }
                }
            }
            if (phase == DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING) {
                val entryStorage = physical.parameters.mapNotNull { parameter ->
                    storageByValue[parameter.symbol]?.let { storage -> parameter.symbol to storage }
                }.toMap(IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>())
                check(
                    context.genericOwnerPhysicalValueEntryStorageByFunction.put(
                        physical.symbol,
                        entryStorage,
                    ) == null,
                ) {
                    "Internal .NET backend error: one physical function received multiple " +
                            "final entry-storage vectors"
                }
                check(
                    context.genericOwnerPhysicalValueFixedLeafEntryStorageByFunction.put(
                        physical.symbol,
                        fixedLeafEntryStorage,
                    ) == null,
                ) {
                    "Internal .NET backend error: one physical function received multiple " +
                            "final fixed-leaf entry-storage vectors"
                }
            }

            processStatements(body.statements, storageByValue)
            return records
        }

        private fun processStatements(
            statements: List<IrStatement>,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ) {
            statements.forEach { statement ->
                when (statement) {
                    is IrVariable -> if (statement.type.isPhysicalValueShadowCandidateType(
                        owner,
                        physical,
                        currentMethodIdentity != null,
                    )) {
                        processVariable(statement, storage)
                    } else {
                        statement.initializer?.let { initializer ->
                            processNestedExpressionContainers(initializer, storage)
                        }
                    }
                    is IrBlock -> processNestedContainer(statement.statements, storage)
                    is IrComposite -> processNestedContainer(statement.statements, storage)
                    is IrReturn -> processNestedExpressionContainers(statement.value, storage)
                }
            }
        }

        /**
         * Compiler aliases can live inside the sequential container which computes a non-candidate
         * result. Observe those nested definitions without treating an arbitrary branch/call tree
         * as transparent or propagating its storage back into the enclosing scope.
         */
        private fun processNestedExpressionContainers(
            expression: IrExpression,
            outerStorage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ) {
            when (expression) {
                is IrBlock -> processNestedContainer(expression.statements, outerStorage)
                is IrComposite -> processNestedContainer(expression.statements, outerStorage)
                is IrTypeOperatorCall -> processNestedExpressionContainers(expression.argument, outerStorage)
            }
        }

        private fun processNestedContainer(
            statements: List<IrStatement>,
            outerStorage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ) {
            processStatements(statements, LinkedHashMap(outerStorage))
        }

        private fun processVariable(
            variable: IrVariable,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): Boolean {
            if (!variable.type.isPhysicalValueShadowCandidateType(
                owner,
                physical,
                currentMethodIdentity != null,
            )) return false
            val diagnostic = ShadowDiagnosticIdentity(owner, source, physical, role, phase, variable)
            val ownerAuthority = authority
            if (ownerAuthority == null) {
                records += diagnostic.unsupported("physical declaration authority unavailable")
                return false
            }
            if (setValueCountBySymbol[variable.symbol].orZero() != 0) {
                records += diagnostic.unsupported(
                    "multiply-defined local is outside the shadow transfer grammar",
                )
                return false
            }
            if (variable.isVar) {
                records += diagnostic.unsupported("mutable local is outside the shadow transfer grammar")
                return false
            }
            if (variable.type.hasUnsupportedProjection()) {
                records += diagnostic.unsupported(
                    "star or non-invariant projected local storage is outside the shadow transfer grammar",
                )
                return false
            }
            val initializer = variable.initializer
            if (initializer == null) {
                records += diagnostic.unsupported("immutable local has no initializer")
                return false
            }
            val orderedPrefixPlan = initializer.dotNetPhysicalDirectResultInitializerPlanOrNull()
                ?.takeIf { plan -> plan.hasSequentialReceiverAndInputPrefixPair }
            val produced = if (orderedPrefixPlan == null) {
                evaluateInitializerOrNull(initializer, storage)
            } else {
                evaluateOrderedPrefixResultOrNull(orderedPrefixPlan, storage)
            }
            if (produced == null) {
                records += diagnostic.unsupported(
                    "initializer is outside the shadow transfer grammar: " +
                            initializer.physicalValueTransferShape(),
                )
                return false
            }
            val selection = selectStorageCarrierOrNull(variable, produced, ownerAuthority)
            if (selection == null) {
                val splitDiagnostic = if (
                    produced.layout is DotNetGenericOwnerProducedValueLayout.SplitNullable &&
                    variable.type.nullableCurrentOwnerParameterIndexOrNull(owner) != null
                ) {
                    val use = variable.splitLocalUseSummaryIn(physical)
                    "; split use reads=${use.readCount}, direct function returns=" +
                            "${use.directFunctionReturnCount}, direct other returns=" +
                            "${use.directOtherReturnCount}, protected returns=" +
                            "${use.protectedRegionReturnCount}, return values=${use.returnValueKinds}, " +
                            "enclosing split=${physical in context.splitNullableResultPayloadTypes}"
                } else {
                    ""
                }
                records += diagnostic.unsupported(
                    "deferred storage has no independently proven direct reference carrier" +
                            splitDiagnostic,
                )
                return false
            }
            val placed = selection.produced.placeInStorageOrNull(selection.storage) { producedCarrier, storageCarrier ->
                canStoreIdentityPreserving(
                    producedCarrier,
                    storageCarrier,
                    selection.produced.provenance,
                    ownerAuthority,
                )
            }
            if (placed == null) {
                records += diagnostic.unsupported("initializer requires a non-identity storage conversion")
                return false
            }
            storage[variable.symbol] = placed
            records += diagnostic.analyzed(selection.produced, placed, ownerAuthority)
            return true
        }

        private fun selectStorageCarrierOrNull(
            variable: IrVariable,
            produced: DotNetGenericOwnerProducedValueFact,
            ownerAuthority: OwnerAuthority,
        ): SelectedStorage? {
            variable.type.nullableCurrentOwnerParameterIndexOrNull(owner)?.let { index ->
                if ((variable.initializer !is IrCall && variable.initializer !is IrWhen) ||
                    !variable.hasOnlyUnprotectedDirectFunctionReturnUsesIn(physical)
                ) return null
                val enclosingPayloadIndex = context.splitNullableResultPayloadTypes[physical]
                    ?.exactCurrentOwnerParameterIndexOrNull(owner) ?: return null
                val splitStorage = splitNullableOwnerParameterStorageLayoutOrNull(
                    produced,
                    localOwnerParameterIndex = index,
                    enclosingOwnerParameterIndex = enclosingPayloadIndex,
                    ownerParameterCarriers = ownerAuthority.ownerParameterCarriers,
                ) ?: return null
                return SelectedStorage(
                    produced,
                    splitStorage,
                )
            }
            val requestedCarrier: DotNetGenericOwnerStorageCarrier = when {
                variable.type.isObjectShadowType() ->
                    DotNetGenericOwnerStorageCarrier.Fixed(ownerAuthority.objectCarrier)
                variable.type.exactCurrentOwnerParameterIndexOrNull(owner) != null -> {
                    val index = checkNotNull(
                        variable.type.exactCurrentOwnerParameterIndexOrNull(owner),
                    )
                    DotNetGenericOwnerStorageCarrier.Fixed(
                        ownerAuthority.ownerParameterCarriers[index],
                    )
                }
                variable.type.exactCurrentMethodParameterIndexOrNull(physical) != null &&
                        currentMethodIdentity != null -> {
                    val index = checkNotNull(
                        variable.type.exactCurrentMethodParameterIndexOrNull(physical),
                    )
                    val parameter = when (val binding = ownerAuthority.declarations
                        .methodParameterOrError(currentMethodIdentity, index)
                    ) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            error("Internal .NET backend error: ${binding.reason}")
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                    }
                    val carrier = when (val binding = ownerAuthority.declarations
                        .carrierOrError(parameter)
                    ) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            error("Internal .NET backend error: ${binding.reason}")
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                    }
                    DotNetGenericOwnerStorageCarrier.Fixed(carrier)
                }
                variable.type.isDeferredGenericShadowType() -> DotNetGenericOwnerStorageCarrier.Deferred
                else -> DotNetGenericOwnerStorageCarrier.Unknown
            }
            if (requestedCarrier is DotNetGenericOwnerStorageCarrier.Fixed) {
                return SelectedStorage(
                    produced,
                    DotNetGenericOwnerPhysicalStorageLayout.Direct(requestedCarrier),
                )
            }
            if (requestedCarrier != DotNetGenericOwnerStorageCarrier.Deferred) return null

            // The destination may select a natural interface only after recorded InterfaceImpl
            // authority has guaranteed that exact construction. A logically widened compiler
            // alias contributes no such selector and may still retain its concrete produced
            // carrier; it can never manufacture I<object> from that fallback.
            val carrier = (produced.layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
                ?: return null
            if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
            val construction = carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
                ?: return null
            exactNaturalDestinationSelectorOrNull(variable.type, ownerAuthority)?.let { desiredView ->
                val selected = produced.selectRecordedPhysicalInterfaceViewOrNull(
                    ownerAuthority.declarations,
                    desiredView,
                ) ?: return null
                val selectedCarrier = when (val result = ownerAuthority.declarations.carrierOrError(
                    desiredView.construction,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    -> return null
                }
                return SelectedStorage(
                    selected,
                    DotNetGenericOwnerPhysicalStorageLayout.Direct(
                        DotNetGenericOwnerStorageCarrier.Fixed(selectedCarrier),
                    ),
                )
            }
            val guaranteed = (produced.provenance.guaranteedViews as?
                    DotNetGenericOwnerGuaranteedViews.Known)?.views.orEmpty()
            if (DotNetGenericOwnerPhysicalView(construction) !in guaranteed) return null
            return SelectedStorage(
                produced,
                DotNetGenericOwnerPhysicalStorageLayout.Direct(
                    DotNetGenericOwnerStorageCarrier.Fixed(carrier),
                ),
            )
        }

        private fun exactNaturalDestinationSelectorOrNull(
            type: IrType,
            ownerAuthority: OwnerAuthority,
        ): DotNetGenericOwnerPhysicalView? = when (val binding =
            bindExactLocalGenericOwnerNaturalViewOrError(
                type,
                owner,
                ownerAuthority.physicalAuthority,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> null
        }

        private fun evaluateInitializerOrNull(
            expression: IrExpression,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? = when (expression) {
            is IrGetValue -> storage[expression.symbol]?.read()?.value
            is IrTypeOperatorCall -> when (expression.operator) {
                IrTypeOperator.IMPLICIT_CAST ->
                    transferIdentityPreservingReferenceOperatorOrNull(expression, storage, forceNonNull = false)
                IrTypeOperator.IMPLICIT_NOTNULL ->
                    transferIdentityPreservingReferenceOperatorOrNull(expression, storage, forceNonNull = true)
                else -> null
            }
            is IrConstructorCall -> evaluateConstructorResultOrNull(expression, storage)
            is IrGetField -> evaluateFieldReadOrNull(expression, storage)
            is IrCall -> evaluateCallResultOrNull(expression, storage)
            is IrWhen -> evaluateWhenResultOrNull(expression, storage)
            is IrBlock -> evaluateContainerOrNull(expression.statements, storage)
            is IrComposite -> evaluateContainerOrNull(expression.statements, storage)
            else -> null
        }

        private fun IrExpression.physicalValueTransferShape(depth: Int = 0): String {
            if (depth >= 3) return javaClass.simpleName
            fun IrStatement.nestedShape(): String =
                (this as? IrExpression)?.physicalValueTransferShape(depth + 1)
                    ?: javaClass.simpleName
            return when (this) {
                is IrWhen -> "IrWhen[" + branches.joinToString { branch ->
                    branch.result.physicalValueTransferShape(depth + 1)
                } + "]"
                is IrTypeOperatorCall ->
                    "IrTypeOperatorCall($operator, ${argument.physicalValueTransferShape(depth + 1)})"
                is IrBlock -> "IrBlock[" + statements.joinToString { it.nestedShape() } + "]"
                is IrComposite -> "IrComposite[" + statements.joinToString { it.nestedShape() } + "]"
                else -> javaClass.simpleName
            }
        }

        /**
         * Produces the result fixed by one already-selected natural MethodDef.
         *
         * A recorded logical route may veto a semantic call but is not required for an ordinary
         * natural call. Declaration authority selects the natural MethodDef; value provenance
         * selects only a construction of its owner which the receiver already guarantees. The
         * shared physical operation query then instantiates the recorded result layout. Super and
         * semantic routes remain excluded. Direct results retain the first parameterless,
         * non-MethodSpec boundary. Split results additionally admit a complete zero-or-more
         * `STRICT_OWNER_INPUT` vector with no MethodSpec, or the bounded `<R>(K, R): V?`
         * composition whose open `!n`/`!!n` slots, exact owner-bound MethodSpec and instantiated
         * arguments are independently proven. Broader/declaration-independent inputs and every
         * other MethodSpec shape remain operation-only.
         */
        private fun evaluateCallResultOrNull(
            expression: IrCall,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
            allowCallerMethodParameterProducer: Boolean = false,
        ): DotNetGenericOwnerProducedValueFact? {
            val route = callRoutesByCall[expression]
            if (route != null &&
                (route.receiverProvenance !=
                        DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION ||
                        route.routeRequirement !=
                        DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY)
            ) return null
            val source = route?.callee ?: expression.symbol.owner.let { candidate ->
                candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract()
                ?: candidate
            }
            if (expression.superQualifierSymbol != null) return null
            val receiverExpression = expression.dispatchReceiver ?: return null
            val receiver = evaluateInitializerOrNull(receiverExpression, storage) ?: return null
            val ownerAuthority = authority ?: return null
            val selectedMethod = ownerAuthority.physicalAuthority.callableMethodOrNull(
                source.symbol,
                DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
            ) ?: return null
            val method = ownerAuthority.declarations.methodDescriptionOrNull(selectedMethod)
                ?: return null
            val requiredView = when (val selection =
                receiver.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
                    ownerAuthority.declarations,
                    method.declaringType,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> selection.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${selection.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
            }
            val selectedRoute = when (val selection =
                bindDotNetLocalGenericOwnerPhysicalOperationRouteOrError(
                    call = expression,
                    physicalFunction = physical,
                    source = source,
                    selectedEntry = DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
                    requiredView = requiredView,
                    authority = ownerAuthority.physicalAuthority,
                    evaluateValue = { value -> evaluateInitializerOrNull(value, storage) },
                ) ?: return null) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> selection.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${selection.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
            }
            val result = selectedRoute.producedResult ?: return null
            return result.takeIf { produced ->
                when (produced.layout) {
                    is DotNetGenericOwnerProducedValueLayout.Direct ->
                        expression.isDotNetParameterlessDirectResultPlacementCall() ||
                                allowCallerMethodParameterProducer &&
                                expression.isDotNetDirectResultPlacementCall(
                                    selectedRoute,
                                    currentMethodIdentity,
                                    ownerAuthority.declarations,
                                )
                    is DotNetGenericOwnerProducedValueLayout.SplitNullable -> {
                        val ordinaryParameters = source.parameters.filter { parameter ->
                            parameter.kind != IrParameterKind.DispatchReceiver
                        }
                        val declaredSlots = selectedRoute.method.signature.parameterSlots
                        val slots = selectedRoute.instantiatedSignature.parameterSlots
                        val declaredResult = selectedRoute.method.signature.resultLayout as?
                                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable
                        val instantiatedResult =
                            selectedRoute.instantiatedSignature.resultLayout as?
                                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable
                        val hasExactOwnerSplitResult =
                            declaredResult?.payloadSlot?.domain ==
                                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT &&
                                    declaredResult.payloadSlot.carrier.isTypeParameterOf(
                                        selectedRoute.method.declaringType,
                                    ) &&
                                    instantiatedResult?.payloadSlot?.domain ==
                                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT &&
                                    ownerAuthority.ownerParameterCarriers.any { carrier ->
                                        carrier.type == instantiatedResult.payloadSlot.carrier
                                    } && instantiatedResult.payloadSlot.carrier ==
                                produced.layout.payloadCarrier.type
                        val hasExactNonMethodInputVector =
                            ordinaryParameters.size == declaredSlots.size &&
                                    declaredSlots.size == slots.size &&
                                    source.typeParameters.isEmpty() &&
                                    selectedRoute.method.signature.genericArity == 0 &&
                                    selectedRoute.instantiatedSignature.genericArity == 0 &&
                                    selectedRoute.method.genericParameters.isEmpty() &&
                                    selectedRoute.methodArguments.isEmpty() &&
                                    declaredSlots.zip(slots).all { pair ->
                                        val declared = pair.first
                                        val instantiated = pair.second
                                        if (declared.domain != instantiated.domain) {
                                            false
                                        } else when (declared.domain) {
                                            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT ->
                                                declared.carrier.isTypeParameterOf(
                                                    selectedRoute.method.declaringType,
                                                ) && ownerAuthority.ownerParameterCarriers.any { carrier ->
                                                    carrier.type == instantiated.carrier
                                                }
                                            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT ->
                                                declared.carrier.isDeclarationIndependentLeafCarrier() &&
                                                        instantiated.carrier == declared.carrier
                                            DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
                                            DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
                                            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                                            -> false
                                        }
                                    }
                        val methodArgument = selectedRoute.methodArguments.singleOrNull()
                        val hasBoundedOwnerMethodInputVector =
                            ordinaryParameters.size == 2 && declaredSlots.size == 2 &&
                                    slots.size == 2 && source.typeParameters.size == 1 &&
                                    selectedRoute.method.signature.genericArity == 1 &&
                                    selectedRoute.instantiatedSignature.genericArity == 1 &&
                                    selectedRoute.method.genericParameters.singleOrNull()
                                        ?.isUnconstrained == true &&
                                    declaredSlots.map { slot -> slot.domain } == listOf(
                                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                                    ) && slots.map { slot -> slot.domain } ==
                                declaredSlots.map { slot -> slot.domain } &&
                                    declaredSlots[0].carrier.isTypeParameterOf(
                                        selectedRoute.method.declaringType,
                                    ) && declaredSlots[1].carrier.isMethodParameterOf(
                                        selectedRoute.method.identity,
                                        index = 0,
                                    ) && methodArgument != null &&
                                    ownerAuthority.ownerParameterCarriers.any { carrier ->
                                        carrier.type == methodArgument
                                    } && ownerAuthority.ownerParameterCarriers.any { carrier ->
                                        carrier.type == slots[0].carrier
                                    } && slots[1].carrier == methodArgument
                        hasExactOwnerSplitResult &&
                                (hasExactNonMethodInputVector || hasBoundedOwnerMethodInputVector)
                    }
                    DotNetGenericOwnerProducedValueLayout.Null,
                    DotNetGenericOwnerProducedValueLayout.Unknown,
                    -> false
                }
            }
        }

        private fun DotNetGenericOwnerSymbolicCarrierReference.isTypeParameterOf(
            definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        ): Boolean {
            val parameter = this as? DotNetGenericOwnerSymbolicCarrierReference.Parameter
                ?: return false
            val binder = parameter.binder as?
                    DotNetGenericOwnerPhysicalGenericBinderReference.Type ?: return false
            return binder.definition == definition
        }

        private fun DotNetGenericOwnerSymbolicCarrierReference.isMethodParameterOf(
            definition: DotNetGenericOwnerPhysicalMethodDefIdentity,
            index: Int,
        ): Boolean {
            val parameter = this as? DotNetGenericOwnerSymbolicCarrierReference.Parameter
                ?: return false
            val binder = parameter.binder as?
                    DotNetGenericOwnerPhysicalGenericBinderReference.Method ?: return false
            return binder.definition == definition && parameter.index == index
        }

        /**
         * Produces one exact allocation only from already admitted declaration authority and
         * independently proven inputs.
         *
         * The result type of [expression] is structural input to the constructor-use resolver,
         * never physical evidence by itself. The target TypeDef must already occur in the current
         * declaration epoch, the exact constructor must occur in its admitted architecture plan,
         * and every parameter which determines an owner-dependent class argument must receive a
         * value whose produced carrier or authenticated recorded view proves the substituted
         * parameter carrier. A phantom class parameter needs no artificial argument proof: the
         * already-bound `newobj C<!T>` TypeSpec is its physical binder.
         */
        private fun evaluateConstructorResultOrNull(
            expression: IrConstructorCall,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            val ownerAuthority = authority ?: return null
            val use = expression.dotNetExactGenericOwnerConstructorUseOrNull(owner) ?: return null
            val constructedPlan = context.genericOwnerArchitecturePlans[use.constructedClass]
                ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
                ?.takeIf { candidate -> candidate.owner.kind == ClassKind.CLASS }
                ?: return null
            val constructorPlan = constructedPlan.constructors.singleOrNull { candidate ->
                candidate.source === expression.symbol.owner
            } ?: return null
            if (constructorPlan.parameterSlotDomains.size != expression.symbol.owner.parameters.size) {
                return null
            }

            val view = bindExactOwnerDependentViewOrNull(
                use.constructedType,
                ownerAuthority,
            ) ?: return null
            val constructedIdentity = ownerAuthority.physicalAuthority
                .genericClassIdentityOrNull(use.constructedClass.symbol)
                ?: return null
            if (view.family != constructedIdentity) return null

            for (determiningUse in use.determiningUses) {
                val parameterIndex = determiningUse.parameterIndex
                if (parameterIndex in constructorPlan.semanticObjectParameterIndices) {
                    // The frozen constructor MethodDef accepts this capture through object. It may
                    // affect the generated object's state and later semantic operations, but it
                    // cannot invalidate the separately bound `newobj Generated<!T>` TypeSpec.
                    if (expression.arguments.getOrNull(parameterIndex) == null) return null
                    continue
                }
                if (constructorPlan.parameterSlotDomains.getOrNull(parameterIndex) !=
                    DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                ) {
                    return null
                }
                val expectedDependencies = determiningUse.substitutedParameterType
                    .dotNetGenericOwnerParameterDependencies(owner)
                if (expectedDependencies != determiningUse.requiredOwnerParameters) return null
                val expected = bindExactOwnerDependentCarrierOrNull(
                    determiningUse.substitutedParameterType,
                    ownerAuthority,
                ) ?: return null
                val argument = expression.arguments.getOrNull(parameterIndex) ?: return null
                val produced = evaluateInitializerOrNull(argument, storage) ?: return null
                if (!produced.provesExactConstructorInput(expected, ownerAuthority)) return null
            }

            val carrier = when (val binding = ownerAuthority.declarations.carrierOrError(
                view.construction,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${binding.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
            }
            if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) {
                return null
            }
            return DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(carrier),
                DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews().guarantee(
                    view,
                    DotNetGenericOwnerPhysicalViewEvidence.CONSTRUCTOR_ALLOCATION,
                ),
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            )
        }

        /** One carrier selected recursively from the current declaration epoch, never a mapper. */
        private fun bindExactOwnerDependentCarrierOrNull(
            type: IrType,
            ownerAuthority: OwnerAuthority,
        ): DotNetGenericOwnerExactConstructorInputCarrier? {
            return when (val binding = bindExactLocalGenericOwnerDependentCarrierOrError(
                type,
                owner,
                ownerAuthority.identity,
                ownerAuthority.declarations,
            ) { classifier ->
                ownerAuthority.physicalAuthority.genericClassIdentityOrNull(classifier)
                    ?: ownerAuthority.physicalAuthority.naturalInterfaceIdentityOrNull(classifier)
            }) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerExactConstructorInputCarrier(
                        binding.value.carrier,
                        binding.value.view,
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${binding.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> null
            }
        }

        private fun bindExactOwnerDependentViewOrNull(
            type: IrType,
            ownerAuthority: OwnerAuthority,
        ): DotNetGenericOwnerPhysicalView? =
            bindExactOwnerDependentCarrierOrNull(type, ownerAuthority)?.view

        private fun DotNetGenericOwnerProducedValueFact.provesExactConstructorInput(
            expected: DotNetGenericOwnerExactConstructorInputCarrier,
            ownerAuthority: OwnerAuthority,
        ): Boolean {
            if (!nullState.canBeNonNull) return false
            val direct = layout as? DotNetGenericOwnerProducedValueLayout.Direct ?: return false
            if (direct.carrier == expected.carrier) return true
            val expectedView = expected.view ?: return false
            return when (val proof = DotNetGenericOwnerAuthenticatedPhysicalView.prove(
                this,
                ownerAuthority.declarations,
                expectedView,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> true
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${proof.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> false
            }
        }

        /**
         * Reads only a BOUND field on the exact current physical receiver. This transfer cannot
         * specialize object state: its result carrier is the producer-wide FieldDef carrier, so
         * a semantic/object field naturally fails any later `!T` constructor-input proof.
         */
        private fun evaluateFieldReadOrNull(
            expression: IrGetField,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            val ownerAuthority = authority ?: return null
            val state = ownerAuthority.physicalAuthority.stateOrNull(expression.symbol) ?: return null
            if (state.fieldDefinition.declaringType != ownerAuthority.identity ||
                state.fieldDefinition.isStatic
            ) {
                return null
            }
            val receiverExpression = expression.receiver ?: return null
            val receiver = evaluateInitializerOrNull(receiverExpression, storage) ?: return null
            val receiverView = ((ownerAuthority.receiverStorage.storageLayout.primaryCarrier.carrier.type as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let(
                ::DotNetGenericOwnerPhysicalView,
            )) ?: return null
            when (val proof = DotNetGenericOwnerAuthenticatedPhysicalView.prove(
                receiver,
                ownerAuthority.declarations,
                receiverView,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${proof.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
            }
            val carrier = when (val binding = ownerAuthority.declarations.carrierOrError(
                state.fieldDefinition.carrier,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    error("Internal .NET backend error: ${binding.reason}")
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
            }
            var provenance = DotNetGenericOwnerPhysicalValueProvenance(
                DotNetGenericOwnerGuaranteedViews.Unknown,
            )
            (carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let { construction ->
                provenance = DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews().guarantee(
                    DotNetGenericOwnerPhysicalView(construction),
                    DotNetGenericOwnerPhysicalViewEvidence.FROZEN_FIELD,
                )
            }
            val nullState = if (expression.type.isMarkedNullable() ||
                carrier.nullEncoding == DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT
            ) {
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
            } else {
                DotNetGenericOwnerPhysicalNullState.NON_NULL
            }
            return DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(carrier),
                provenance,
                nullState,
            )
        }

        private fun evaluateWhenResultOrNull(
            expression: IrWhen,
            storage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            if (expression.type.nullableCurrentOwnerParameterIndexOrNull(owner) != null) {
                return evaluateSplitNullableWhenResultOrNull(expression, storage)
            }
            val ownerAuthority = authority ?: return null
            val logicalOwner = ((expression.type as? IrSimpleType)?.classifier as? IrClassSymbol)
                ?: return null
            val selectedFamily = ownerAuthority.physicalAuthority
                .naturalInterfaceIdentityOrNull(logicalOwner)
                ?: return null
            val reaching = mutableListOf<DotNetGenericOwnerProducedValueFact>()
            var hasElse = false
            for (branch in expression.branches) {
                if (branch.condition.isFalseConst()) continue
                val result = evaluateInitializerOrNull(
                    branch.result,
                    LinkedHashMap(storage),
                ) ?: return null
                reaching += result
                if (branch.condition.isTrueConst()) {
                    hasElse = true
                    break
                }
            }
            if (!hasElse || reaching.size < 2) return null
            var joined = reaching.first()
            for (next in reaching.drop(1)) {
                joined = when (val result = joined.joinAtRecordedPhysicalInterfaceFamilyOrError(
                    next,
                    ownerAuthority.declarations,
                    selectedFamily,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        error("Internal .NET backend error: ${result.reason}")
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                }
            }
            return joined
        }

        /**
         * Joins the first flat split-result control-flow shape without selecting a carrier from the
         * logical `T?` result. Every statically reachable arm must remain one bare call, optionally
         * inside FIR2IR's single-expression braced-arm block, whose final exact-natural operation
         * already produced the identical split payload. Null, bottom, non-transparent containers,
         * nested control flow, and ordinary/materialized results remain unavailable.
         */
        private fun evaluateSplitNullableWhenResultOrNull(
            expression: IrWhen,
            storage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            val calls = expression.dotNetFlatExhaustiveSplitOperationCallsOrNull() ?: return null
            var joined: DotNetGenericOwnerProducedValueFact? = null
            for (call in calls) {
                val result = evaluateCallResultOrNull(call, LinkedHashMap(storage)) ?: return null
                if (result.layout !is DotNetGenericOwnerProducedValueLayout.SplitNullable) return null
                val previous = joined
                joined = if (previous == null) {
                    result
                } else {
                    previous.joinAtIdenticalSplitNullablePayloadOrNull(result) ?: return null
                }
            }
            return joined
        }

        private fun transferIdentityPreservingReferenceOperatorOrNull(
            expression: IrTypeOperatorCall,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
            forceNonNull: Boolean,
        ): DotNetGenericOwnerProducedValueFact? {
            if (expression.type.hasUnsupportedProjection() ||
                !expression.type.isKnownNonValueReferenceTarget()
            ) return null
            val value = evaluateInitializerOrNull(expression.argument, storage) ?: return null
            val carrier = (value.layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
                ?: return null
            if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
            if (forceNonNull) {
                if (!value.nullState.canBeNonNull) return null
                return value.copy(nullState = DotNetGenericOwnerPhysicalNullState.NON_NULL)
            }
            if (!expression.type.isMarkedNullable() &&
                value.nullState != DotNetGenericOwnerPhysicalNullState.NON_NULL
            ) return null
            return value
        }

        /**
         * This proves only that an implicit logical target is reference-shaped. It does not add
         * the target construction to guaranteed views or authorize a call through that view.
         */
        private fun IrType.isKnownNonValueReferenceTarget(): Boolean {
            if (isObjectShadowType()) return true
            val simple = this as? IrSimpleType ?: return false
            val target = (simple.classifier as? IrClassSymbol)?.owner ?: return false
            if (target == owner) return !target.isValue
            // A declaration in the current IR module has authoritative Kotlin class/interface
            // reference semantics. External/foreign classifiers need their retained physical
            // category before the shadow may make the same statement.
            return target.fileOrNull() != null && !target.isValue
        }

        /**
         * Exact-root-only composition for the first ordered caller-MethodDef result gate.
         * Prefix locals receive ordinary independent facts; only their enclosing result needs
         * the later all-or-nothing operation/emission obligation.
         */
        private fun evaluateOrderedPrefixResultOrNull(
            plan: DotNetGenericOwnerPhysicalDirectResultInitializerPlan,
            outerStorage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            val nestedStorage = LinkedHashMap(outerStorage)
            if (!plan.sequentialPrefixVariablesInEvaluationOrder.all { variable ->
                    processVariable(variable, nestedStorage)
                }
            ) return null
            return evaluateCallResultOrNull(
                plan.physicalResultAfterSequentialPrefixes as IrCall,
                nestedStorage,
                allowCallerMethodParameterProducer = true,
            )
        }

        private fun evaluateContainerOrNull(
            statements: List<IrStatement>,
            outerStorage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): DotNetGenericOwnerProducedValueFact? {
            val nestedStorage = LinkedHashMap(outerStorage)
            val last = statements.lastOrNull() ?: return null
            if (!processTransparentStatements(statements.dropLast(1), nestedStorage)) return null
            return when (last) {
                is IrExpression -> evaluateInitializerOrNull(last, nestedStorage)
                is IrVariable -> {
                    processVariable(last, nestedStorage)
                    null
                }
                else -> null
            }
        }

        private fun processTransparentStatements(
            statements: List<IrStatement>,
            storage: MutableMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): Boolean = statements.all { statement ->
            when (statement) {
                is IrVariable -> processVariable(statement, storage)
                is IrBlock -> processTransparentNestedContainer(statement.statements, storage)
                is IrComposite -> processTransparentNestedContainer(statement.statements, storage)
                else -> false
            }
        }

        private fun processTransparentNestedContainer(
            statements: List<IrStatement>,
            outerStorage: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        ): Boolean = processTransparentStatements(statements, LinkedHashMap(outerStorage))

        private fun Int?.orZero(): Int = this ?: 0

        /**
         * Aligns the live physical parameters with one role-specific RepresentationPlan member.
         * The prototype is physical signature authority for this early epoch; source Kotlin types
         * and parameter names are deliberately not consulted. A missing or changed vector yields
         * no exact seed and will later fail closed.
         */
        private fun entryPrototypeParametersOrNull():
                DotNetGenericOwnerPhysicalEntryPrototypeParameters? {
            val familyRole = when (role) {
                DotNetGenericOwnerPhysicalValueShadowFunctionRole.TYPED_ENTRY,
                DotNetGenericOwnerPhysicalValueShadowFunctionRole.OTHER,
                -> DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
                DotNetGenericOwnerPhysicalValueShadowFunctionRole.SEMANTIC_HOOK ->
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
            }
            if (role != DotNetGenericOwnerPhysicalValueShadowFunctionRole.SEMANTIC_HOOK &&
                physical !== source
            ) return null
            val prototypes = plan.prototypeMembers[source] ?: return null
            val prototype = prototypes[familyRole] ?: return null
            val typedPrototype = prototypes[DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY]
                ?: return null
            if (prototype.source !== source || prototype.function.parent !== owner) return null
            if (typedPrototype.source !== source || typedPrototype.function.parent !== owner) return null
            val physicalParameters = physical.parameters.filter { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver
            }
            val plannedParameters = prototype.function.parameters.filter { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver
            }
            val typedParameters = typedPrototype.function.parameters.filter { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver
            }
            if (physicalParameters.size != plannedParameters.size ||
                physicalParameters.size != typedParameters.size ||
                physicalParameters.indices.any { index ->
                    physicalParameters[index].kind != plannedParameters[index].kind ||
                            physicalParameters[index].kind != typedParameters[index].kind
                }
            ) return null
            val currentTypes = physicalParameters.indices.associateTo(
                IdentityHashMap<IrValueSymbol, IrType>(),
            ) { index -> physicalParameters[index].symbol to plannedParameters[index].type }
            val regularIndices = physicalParameters.indices.filter { index ->
                physicalParameters[index].kind == IrParameterKind.Regular
            }
            val parameterDomains = plan.memberFamilies[source]
                ?.parameterSlotDomains
                ?.takeIf { domains -> domains.size == regularIndices.size }
            val fixedLeafCarriers = regularIndices.mapIndexedNotNull { regularIndex, index ->
                if (parameterDomains?.get(regularIndex) !=
                    DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                ) return@mapIndexedNotNull null
                val currentLeaf = plannedParameters[index].type
                    .genericOwnerDeclarationIndependentLeafPrototypeOrNull()
                    ?.declarationIndependentLeafCarrierOrNull()
                    ?: return@mapIndexedNotNull null
                val typedLeaf = typedParameters[index].type
                    .genericOwnerDeclarationIndependentLeafPrototypeOrNull()
                    ?.declarationIndependentLeafCarrierOrNull()
                    ?: return@mapIndexedNotNull null
                (physicalParameters[index].symbol to currentLeaf)
                    .takeIf { currentLeaf == typedLeaf }
            }.toMap(IdentityHashMap<IrValueSymbol, DotNetGenericOwnerSymbolicCarrierReference>())
            val currentMethod = currentMethodIdentity
            val methodParameterCarriers = if (currentMethod == null) {
                emptyMap()
            } else {
                val boundAuthority = authority ?: return null
                val methodDescription = boundAuthority.declarations
                    .methodDescriptionOrNull(currentMethod) ?: return null
                if (physical.typeParameters.size != 1 || prototype.function.typeParameters.size != 1 ||
                    typedPrototype.function.typeParameters.size != 1 ||
                    !physical.typeParameters.single().isUnconstrainedInvariantMethodParameter() ||
                    !prototype.function.typeParameters.single().isUnconstrainedInvariantMethodParameter() ||
                    !typedPrototype.function.typeParameters.single().isUnconstrainedInvariantMethodParameter() ||
                    methodDescription.signature.genericArity != 1 ||
                    methodDescription.genericParameters.singleOrNull()?.isUnconstrained != true ||
                    currentMethod.function !== physical.symbol
                ) return null
                val boundParameter = when (val binding = boundAuthority.declarations
                    .methodParameterOrError(currentMethod, 0)
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        error("Internal .NET backend error: ${binding.reason}")
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                }
                val carrier = when (val binding = boundAuthority.declarations.carrierOrError(boundParameter)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        error("Internal .NET backend error: ${binding.reason}")
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                }
                regularIndices.mapIndexedNotNull { regularIndex, index ->
                    val plannedIndex = plannedParameters[index].type
                        .exactCurrentMethodParameterIndexOrNull(prototype.function)
                    val typedIndex = typedParameters[index].type
                        .exactCurrentMethodParameterIndexOrNull(typedPrototype.function)
                    val physicalIndex = physicalParameters[index].type
                        .exactCurrentMethodParameterIndexOrNull(physical)
                    val slot = methodDescription.signature.parameterSlots.getOrNull(regularIndex)
                    (physicalParameters[index].symbol to carrier).takeIf {
                        plannedIndex == 0 && typedIndex == 0 && physicalIndex == 0 &&
                                slot?.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                                slot.carrier == boundParameter
                    }
                }.toMap(IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalCarrier>())
            }
            return DotNetGenericOwnerPhysicalEntryPrototypeParameters(
                currentTypes,
                fixedLeafCarriers,
                methodParameterCarriers,
            )
        }

        private fun plannedEntryStorageOrNull(
            plannedType: IrType?,
            methodParameterCarrier: DotNetGenericOwnerPhysicalCarrier?,
            ownerAuthority: OwnerAuthority,
        ): DotNetGenericOwnerPhysicalStorageFact? {
            plannedType ?: return null
            if (methodParameterCarrier != null) {
                return DotNetGenericOwnerPhysicalStorageFact(
                    DotNetGenericOwnerPhysicalStorageLayout.Direct(
                        DotNetGenericOwnerStorageCarrier.Fixed(methodParameterCarrier),
                    ),
                    DotNetGenericOwnerPhysicalValueProvenance(
                        DotNetGenericOwnerGuaranteedViews.Unknown,
                    ),
                    // An unconstrained CLR !!R may be a nullable reference substitution.
                    DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
                )
            }
            if (plannedType.isObjectShadowType()) {
                return objectEntryStorage(plannedType, ownerAuthority)
            }
            val declarationIndependentLeaf = plannedType
                .genericOwnerDeclarationIndependentLeafPrototypeOrNull()
                ?.declarationIndependentLeafCarrierOrNull()
            if (declarationIndependentLeaf != null) {
                val carrier = when (val binding = ownerAuthority.declarations.carrierOrError(
                    declarationIndependentLeaf,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        error("Internal .NET backend error: ${binding.reason}")
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                }
                return DotNetGenericOwnerPhysicalStorageFact(
                    DotNetGenericOwnerPhysicalStorageLayout.Direct(
                        DotNetGenericOwnerStorageCarrier.Fixed(carrier),
                    ),
                    DotNetGenericOwnerPhysicalValueProvenance(
                        DotNetGenericOwnerGuaranteedViews.Unknown,
                    ),
                    if (carrier.canRepresentNull) {
                        DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
                    } else {
                        DotNetGenericOwnerPhysicalNullState.NON_NULL
                    },
                )
            }
            if ((plannedType as? IrSimpleType)?.arguments?.isNotEmpty() == true) {
                val naturalView = when (val binding = bindExactLocalGenericOwnerNaturalViewOrError(
                    plannedType,
                    owner,
                    ownerAuthority.physicalAuthority,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        error("Internal .NET backend error: ${binding.reason}")
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> null
                }
                if (naturalView != null) {
                    val carrier = when (val binding = ownerAuthority.declarations.carrierOrError(
                        naturalView.construction,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            error("Internal .NET backend error: ${binding.reason}")
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
                    }
                    if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) {
                        return null
                    }
                    return DotNetGenericOwnerPhysicalStorageFact(
                        DotNetGenericOwnerPhysicalStorageLayout.Direct(
                            DotNetGenericOwnerStorageCarrier.Fixed(carrier),
                        ),
                        DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews().guarantee(
                            naturalView,
                            DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT,
                        ),
                        if (plannedType.isMarkedNullable()) {
                            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
                        } else {
                            DotNetGenericOwnerPhysicalNullState.NON_NULL
                        },
                    )
                }
            }
            val parameterIndex = plannedType.exactCurrentOwnerParameterIndexOrNull(owner)
                ?: return null
            return DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerPhysicalStorageLayout.Direct(
                    DotNetGenericOwnerStorageCarrier.Fixed(
                        ownerAuthority.ownerParameterCarriers[parameterIndex],
                    ),
                ),
                DotNetGenericOwnerPhysicalValueProvenance(
                    DotNetGenericOwnerGuaranteedViews.Unknown,
                ),
                // An unconstrained CLR !T can be a nullable reference substitution. The exact
                // entry carrier is known; its current null state is intentionally not narrowed.
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
            )
        }

        private fun objectEntryStorage(
            plannedType: IrType,
            ownerAuthority: OwnerAuthority,
        ) = DotNetGenericOwnerPhysicalStorageFact(
            DotNetGenericOwnerPhysicalStorageLayout.Direct(
                DotNetGenericOwnerStorageCarrier.Fixed(ownerAuthority.objectCarrier),
            ),
            DotNetGenericOwnerPhysicalValueProvenance(
                DotNetGenericOwnerGuaranteedViews.Unknown,
            ),
            if (plannedType.isNullableAny()) {
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
            } else {
                DotNetGenericOwnerPhysicalNullState.NON_NULL
            },
        )
    }

    /** Binds one value-flow epoch to the context-owned immutable declaration-authority lineage. */
    private fun bindOwnerAuthorityOrNull(
        plan: DotNetGenericOwnerArchitecturePlan,
        phase: DotNetGenericOwnerPhysicalValueShadowPhase,
    ): OwnerAuthority? {
        check(plan.isReifiedByGenericOwnerRehearsal && plan.owner.kind == ClassKind.CLASS) {
            "a physical-value self receiver requires an admitted local generic-class plan"
        }
        val owner = plan.owner
        val physicalAuthority = when (val binding = context.localGenericOwnerPhysicalAuthority) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val declarations = when (phase) {
            DotNetGenericOwnerPhysicalValueShadowPhase.PRE_SEMANTIC_REMAP ->
                physicalAuthority.earlyDeclarations
            DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING ->
                physicalAuthority.boundDeclarations ?: return null
        }
        val identity = physicalAuthority.genericClassIdentityOrNull(owner.symbol) ?: return null
        val arguments = owner.typeParameters.indices.map { index ->
            when (val result = declarations.typeParameterOrError(identity, index)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                -> return null
            }
        }
        val ownerParameterCarriers = arguments.map { parameter ->
            when (val result = declarations.carrierOrError(parameter)) {
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
            declarations,
            physicalAuthority,
            objectCarrier,
            ownerParameterCarriers,
            DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerPhysicalStorageLayout.Direct(
                    DotNetGenericOwnerStorageCarrier.Fixed(receiverCarrier),
                ),
                receiverProvenance,
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            ),
        )
    }

    private fun canStoreIdentityPreserving(
        produced: DotNetGenericOwnerPhysicalCarrier,
        storage: DotNetGenericOwnerPhysicalCarrier,
        provenance: DotNetGenericOwnerPhysicalValueProvenance,
        authority: OwnerAuthority,
    ): Boolean = produced == storage ||
            storage.type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() &&
            produced.nullEncoding == org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE ||
            (storage.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let { construction ->
                val view = DotNetGenericOwnerPhysicalView(construction)
                val producedConstruction = produced.type as?
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed ?: return@let false
                val recordedViews = when (val result = authority.declarations
                    .physicalInterfaceViewClosureOrError(producedConstruction)
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value.interfaceViews
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    -> return@let false
                }
                view in recordedViews && provenance.selectedViewLineage[view.family] == view &&
                        view in (provenance.guaranteedViews as?
                        DotNetGenericOwnerGuaranteedViews.Known)?.views.orEmpty()
            } == true

    private data class OwnerAuthority(
        val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        val declarations: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex,
        val physicalAuthority: DotNetLocalGenericOwnerPhysicalAuthority,
        val objectCarrier: DotNetGenericOwnerPhysicalCarrier,
        val ownerParameterCarriers: List<DotNetGenericOwnerPhysicalCarrier>,
        val receiverStorage: DotNetGenericOwnerPhysicalStorageFact,
    )

    private data class SelectedStorage(
        val produced: DotNetGenericOwnerProducedValueFact,
        val storage: DotNetGenericOwnerPhysicalStorageLayout,
    )

    private data class ShadowDiagnosticIdentity(
        val owner: IrClass,
        val source: IrSimpleFunction,
        val physical: IrSimpleFunction,
        val role: DotNetGenericOwnerPhysicalValueShadowFunctionRole,
        val phase: DotNetGenericOwnerPhysicalValueShadowPhase,
        val variable: IrVariable,
    ) {
        fun unsupported(reason: String) = DotNetGenericOwnerPhysicalValueShadowRecord(
            physicalFunction = physical.symbol,
            variable = variable.symbol,
            snapshot = DotNetGenericOwnerPhysicalValueShadowSnapshot(
                ownerName = owner.stableShadowName(),
                sourceFunctionName = source.name.asString(),
                physicalFunctionName = physical.name.asString(),
                functionRole = role,
                phase = phase,
                variableName = variable.name.asString(),
                status = DotNetGenericOwnerPhysicalValueShadowStatus.UNSUPPORTED,
                initializerProducedLayout = DotNetGenericOwnerPhysicalValueLayoutKind.UNKNOWN,
                initializerProducedCarrier = unknownCarrierSnapshot,
                storageLayout = DotNetGenericOwnerPhysicalValueLayoutKind.UNKNOWN,
                storageCarrier = unknownCarrierSnapshot,
                guaranteeState = DotNetGenericOwnerPhysicalValueShadowGuaranteeState.UNKNOWN,
                guaranteedViews = emptyList(),
                selectedViewLineage = emptyList(),
                initializerNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
                contentsNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
                unsupportedReason = reason,
            ),
            predictedProducedValue = null,
            predictedStorage = null,
        )

        fun analyzed(
            initializer: DotNetGenericOwnerProducedValueFact,
            storage: DotNetGenericOwnerPhysicalStorageFact,
            authority: OwnerAuthority,
        ): DotNetGenericOwnerPhysicalValueShadowRecord {
            val provenance = storage.contentsProvenance
            val known = provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known
            val initializerCarrier = initializer.layout.toCarrierSnapshot(authority)
            val storageCarrier = authority.physicalAuthority
                .carrierSnapshotOrNull(storage.storageLayout.primaryCarrier.carrier)
                ?: unknownCarrierSnapshot
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
                snapshotsByView[view] = authority.physicalAuthority.viewSnapshotOrNull(view, evidence)
                    ?: return unsupported(
                        "a guaranteed view is outside the first shadow snapshot vocabulary",
                    )
            }
            val views = snapshotsByView.values.sortedWith(
                compareBy(
                    { snapshot -> snapshot.carrier.kind.ordinal },
                    { snapshot -> snapshot.carrier.localOwnerName },
                    { snapshot -> snapshot.carrier.localTypeDefView?.ordinal },
                    { snapshot -> snapshot.carrier.parameterBinderOwnerName },
                    { snapshot -> snapshot.carrier.parameterBinderTypeDefView?.ordinal },
                    { snapshot -> snapshot.carrier.ownerParameterIndices.joinToString(",") },
                ),
            )
            val lineage = mutableListOf<DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot>()
            provenance.selectedViewLineage.forEach { entry ->
                val family = entry.key
                val selectedView = entry.value
                val view = snapshotsByView[selectedView]
                    ?: return unsupported(
                        "selected lineage is outside the first shadow snapshot vocabulary",
                    )
                val familySnapshot = authority.physicalAuthority.familySnapshotOrNull(family)
                    ?: return unsupported(
                        "selected lineage family is outside the first shadow snapshot vocabulary",
                    )
                lineage += DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot(familySnapshot, view)
            }
            lineage.sortWith(
                compareBy(
                    { selection: DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot ->
                        selection.family.kind.ordinal
                    },
                    { selection -> selection.family.localOwnerName },
                    { selection -> selection.family.localTypeDefView?.ordinal },
                ),
            )
            return DotNetGenericOwnerPhysicalValueShadowRecord(
                physicalFunction = physical.symbol,
                variable = variable.symbol,
                snapshot = DotNetGenericOwnerPhysicalValueShadowSnapshot(
                    ownerName = owner.stableShadowName(),
                    sourceFunctionName = source.name.asString(),
                    physicalFunctionName = physical.name.asString(),
                    functionRole = role,
                    phase = phase,
                    variableName = variable.name.asString(),
                    status = DotNetGenericOwnerPhysicalValueShadowStatus.ANALYZED,
                    initializerProducedLayout = initializer.layout.toSnapshotLayout(),
                    initializerProducedCarrier = initializerCarrier,
                    storageLayout = storage.storageLayout.toSnapshotLayout(),
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
                ),
                predictedProducedValue = initializer,
                predictedStorage = storage,
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

        fun org.jetbrains.kotlin.ir.types.IrType.isDeferredGenericShadowType(): Boolean =
            (this as? IrSimpleType)?.arguments?.isNotEmpty() == true

        fun org.jetbrains.kotlin.ir.types.IrType.isPhysicalValueShadowCandidateType(
            owner: IrClass,
            method: IrSimpleFunction,
            hasCurrentMethodAuthority: Boolean,
        ): Boolean = isObjectShadowType() || isDeferredGenericShadowType() ||
                exactCurrentOwnerParameterIndexOrNull(owner) != null ||
                nullableCurrentOwnerParameterIndexOrNull(owner) != null ||
                hasCurrentMethodAuthority && exactCurrentMethodParameterIndexOrNull(method) != null

        fun IrType.exactCurrentOwnerParameterIndexOrNull(owner: IrClass): Int? {
            val simple = this as? IrSimpleType ?: return null
            if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) return null
            val parameter = simple.classifier as? IrTypeParameterSymbol ?: return null
            return owner.typeParameters.indexOfFirst { candidate ->
                candidate.symbol === parameter
            }.takeIf { index -> index >= 0 }
        }

        fun IrType.nullableCurrentOwnerParameterIndexOrNull(owner: IrClass): Int? {
            val simple = this as? IrSimpleType ?: return null
            if (!simple.isMarkedNullable() || simple.arguments.isNotEmpty()) return null
            val parameter = simple.classifier as? IrTypeParameterSymbol ?: return null
            return owner.typeParameters.indexOfFirst { candidate ->
                candidate.symbol === parameter
            }.takeIf { index -> index >= 0 }
        }

        fun IrType.exactCurrentMethodParameterIndexOrNull(method: IrSimpleFunction): Int? {
            val simple = this as? IrSimpleType ?: return null
            if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) return null
            val parameter = simple.classifier as? IrTypeParameterSymbol ?: return null
            return method.typeParameters.indexOfFirst { candidate ->
                candidate.symbol === parameter
            }.takeIf { index -> index >= 0 }
        }

        fun org.jetbrains.kotlin.ir.declarations.IrTypeParameter
                .isUnconstrainedInvariantMethodParameter(): Boolean =
            variance == Variance.INVARIANT && !isReified &&
                    superTypes.all { bound -> bound.isAny() || bound.isNullableAny() }

        fun org.jetbrains.kotlin.ir.types.IrType.hasUnsupportedProjection(): Boolean {
            val simple = this as? IrSimpleType ?: return false
            return simple.arguments.any { argument ->
                val projection = argument as? IrTypeProjection ?: return@any true
                projection.variance != Variance.INVARIANT || projection.type.hasUnsupportedProjection()
            }
        }

        fun DotNetGenericOwnerProducedValueLayout.toCarrierSnapshot(
            authority: OwnerAuthority,
        ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot = when (this) {
            is DotNetGenericOwnerProducedValueLayout.Direct ->
                authority.physicalAuthority.carrierSnapshotOrNull(carrier) ?: unknownCarrierSnapshot
            is DotNetGenericOwnerProducedValueLayout.SplitNullable ->
                authority.physicalAuthority.carrierSnapshotOrNull(payloadCarrier)
                    ?: unknownCarrierSnapshot
            DotNetGenericOwnerProducedValueLayout.Null,
            DotNetGenericOwnerProducedValueLayout.Unknown,
            -> unknownCarrierSnapshot
        }

        fun DotNetGenericOwnerProducedValueLayout.toSnapshotLayout():
                DotNetGenericOwnerPhysicalValueLayoutKind = when (this) {
            is DotNetGenericOwnerProducedValueLayout.Direct ->
                DotNetGenericOwnerPhysicalValueLayoutKind.DIRECT
            is DotNetGenericOwnerProducedValueLayout.SplitNullable ->
                DotNetGenericOwnerPhysicalValueLayoutKind.SPLIT_NULLABLE
            DotNetGenericOwnerProducedValueLayout.Null ->
                DotNetGenericOwnerPhysicalValueLayoutKind.NULL
            DotNetGenericOwnerProducedValueLayout.Unknown ->
                DotNetGenericOwnerPhysicalValueLayoutKind.UNKNOWN
        }

        fun DotNetGenericOwnerPhysicalStorageLayout.toSnapshotLayout():
                DotNetGenericOwnerPhysicalValueLayoutKind = when (this) {
            is DotNetGenericOwnerPhysicalStorageLayout.Direct ->
                DotNetGenericOwnerPhysicalValueLayoutKind.DIRECT
            is DotNetGenericOwnerPhysicalStorageLayout.SplitNullable ->
                DotNetGenericOwnerPhysicalValueLayoutKind.SPLIT_NULLABLE
        }

        fun DotNetGenericOwnerPhysicalNullState.toSnapshot():
                DotNetGenericOwnerPhysicalValueShadowNullState =
            DotNetGenericOwnerPhysicalValueShadowNullState.valueOf(name)
    }
}
