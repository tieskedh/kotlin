/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableValueSlotReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericBinderReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullEncoding
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationActualRouteSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteKindSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowRelation
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStorageFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalViewEvidence
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerGuaranteedViews
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowPhase
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCallableEntryKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalValueStableName
import org.jetbrains.kotlin.backend.dotnet.isDotNetParameterlessDirectResultPlacementCall
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError
import org.jetbrains.kotlin.backend.dotnet.requiresSemanticOperation
import org.jetbrains.kotlin.backend.dotnet.unknownPhysicalValueCarrierSnapshot
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Compares one BOUND callable/provenance query with the existing final router.
 *
 * The query runs synchronously after the routing fixpoint and reads its stable final context maps.
 * A BOUND exact-natural operation may remove an older conservative local semantic target when
 * the logical route does not mandate a semantic-result contract. This is the first authoritative
 * operation consumer: declaration authority still chooses the MethodDef, value provenance only
 * proves its receiver and arguments, and no IR or carrier is rewritten. Only final regular-
 * parameter facts whose typed and current physical prototypes agree on one fixed declaration-
 * independent leaf cross argument boundaries. A parameterless receiver may additionally use one
 * exact natural construction made solely from the current owner's TypeDef parameters. Calls
 * without one unique successful POST local or one of those bounded entry facts are omitted.
 */
internal class DotNetGenericOwnerPhysicalOperationRouteShadowAnalysis(
    private val context: DotNetBackendContext,
) {
    fun analyze(module: IrModuleFragment) {
        check(context.genericOwnerPhysicalValueShadowFinalAnalysisCompleted) {
            "the physical-operation shadow requires completed final value provenance"
        }
        check(!context.genericOwnerPhysicalOperationRouteShadowAnalysisCompleted) {
            "generic-owner physical-operation shadow analysis must run exactly once"
        }
        val authority = when (val binding = context.localGenericOwnerPhysicalAuthority) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> {
                context.genericOwnerPhysicalOperationRouteShadowAnalysisCompleted = true
                return
            }
        }
        val declarations = authority.boundDeclarations
        if (declarations == null) {
            context.genericOwnerPhysicalOperationRouteShadowAnalysisCompleted = true
            return
        }
        val moduleFiles = module.files.toSet()
        val postRecords = context.genericOwnerPhysicalValueShadowRecords.filter { record ->
            record.snapshot.phase == DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING &&
                    record.physicalFunction.owner.fileOrNull in moduleFiles
        }
        val recordsByFunction = IdentityHashMap<
                IrSimpleFunctionSymbol,
                MutableList<DotNetGenericOwnerPhysicalValueShadowRecord>,
                >()
        for (record in postRecords) {
            recordsByFunction.getOrPut(record.physicalFunction, ::mutableListOf) += record
        }
        val snapshots = mutableListOf<DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot>()
        for (entry in recordsByFunction) {
            val functionSymbol = entry.key
            val records = entry.value
            val function = functionSymbol.owner
            val owner = function.parent as? IrClass ?: continue
            val storageByValue = IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>()
            val entryStorageByValue =
                context.genericOwnerPhysicalValueEntryStorageByFunction[functionSymbol].orEmpty()
            val conflictingValues = Collections.newSetFromMap(IdentityHashMap<IrValueSymbol, Boolean>())
            context.genericOwnerPhysicalValueFixedLeafEntryStorageByFunction[functionSymbol]
                ?.forEach { entryStorage ->
                    storageByValue[entryStorage.key] = entryStorage.value
                }
            for (record in records) {
                val storage = record.predictedStorage ?: continue
                val existing = storageByValue.put(record.variable, storage)
                if (existing != null && existing != storage) conflictingValues += record.variable
            }
            function.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) = Unit

                override fun visitFunction(declaration: IrFunction) = Unit

                override fun visitCall(expression: IrCall) {
                    expression.acceptChildrenVoid(this)
                    observeCallOrNull(
                        expression,
                        owner,
                        function,
                        storageByValue,
                        entryStorageByValue,
                        conflictingValues,
                        authority,
                    )?.let { observation ->
                        snapshots += observation.snapshot
                        observation.authoritativeExactNaturalRoute?.let { route ->
                            check(
                                context.genericOwnerAuthoritativePhysicalOperationRoutes.put(
                                    expression,
                                    route,
                                ) == null,
                            ) {
                                "one final generic-owner call received multiple authoritative " +
                                        "physical operation routes"
                            }
                            observation.semanticEquivalenceEmitterWitness?.let { witness ->
                                check(witness.route === route &&
                                        context.genericOwnerSemanticEquivalentOperationEmitterWitnesses.put(
                                            expression,
                                            witness,
                                        ) == null
                                ) {
                                    "one final generic-owner call received multiple semantic-equivalence " +
                                            "emitter witnesses"
                                }
                            }
                        }
                    }
                }
            })
        }
        context.genericOwnerPhysicalOperationRouteShadows += snapshots.sortedWith(
            compareBy(
                DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot::ownerName,
                DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot::physicalFunctionName,
                DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot::receiverVariableName,
                { snapshot -> snapshot.logicalSelector.ordinal },
            ),
        )
        context.genericOwnerPhysicalOperationRouteShadowAnalysisCompleted = true
    }

    private fun observeCallOrNull(
        call: IrCall,
        owner: IrClass,
        function: IrSimpleFunction,
        storageByValue: IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        entryStorageByValue: Map<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        conflictingValues: Set<IrValueSymbol>,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): OperationObservation? {
        val source = call.symbol.owner.let { candidate ->
            candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
        }
        val naturalMethod = authority.callableMethodOrNull(
            source.symbol,
            DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
        ) ?: return null
        val semanticMethod = authority.callableMethodOrNull(
            source.symbol,
            DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT,
        )
        val logicalReceiverType = call.dispatchReceiver?.type ?: return null
        val receiver = call.dispatchReceiver.identityGetValueOrNull() ?: return null
        if (receiver.symbol in conflictingValues) return null
        val declarations = authority.boundDeclarations ?: return null
        val initialSelection = selectLogicalReceiver(
            logicalReceiverType,
            owner,
            naturalMethod,
            semanticMethod,
            authority,
        )
        if (initialSelection is LogicalReceiverSelectionResult.Unsupported) return null
        val selection = if (initialSelection is LogicalReceiverSelectionResult.Selected) {
            selectSemanticallyEquivalentNaturalReceiverOrNull(
                initialSelection,
                source,
                storageByValue[receiver.symbol]?.read()?.value,
                naturalMethod,
                authority,
            ) ?: initialSelection
        } else {
            initialSelection
        }
        val selector = selection.selector
        val requiredView = (selection as? LogicalReceiverSelectionResult.Selected)?.requiredView
        val diagnostic = OperationDiagnostic(
            owner = owner,
            function = function,
            receiver = receiver.symbol,
            source = source,
            selector = selector,
            requiredView = requiredView,
            actual = actualRoute(call),
            authority = authority,
        )
        when (selection) {
            is LogicalReceiverSelectionResult.Conflict ->
                return OperationObservation(diagnostic.conflict(selection.reason))
            is LogicalReceiverSelectionResult.Unavailable ->
                return OperationObservation(diagnostic.unavailable())
            is LogicalReceiverSelectionResult.Selected -> Unit
            LogicalReceiverSelectionResult.Unsupported -> error("handled above")
        }
        val parameterlessExactEntryStorage = entryStorageByValue[receiver.symbol]?.takeIf {
            call.isDotNetParameterlessDirectResultPlacementCall() &&
                    selection.selectedEntry ==
                    DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE &&
                    selection.selector ==
                    DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.EXACT_NATURAL &&
                    selection.requiredView.isDirectCurrentOwnerParameterConstruction(owner)
        }
        val operationStorageByValue = if (
            receiver.symbol !in storageByValue && parameterlessExactEntryStorage != null
        ) {
            IdentityHashMap(storageByValue).apply {
                put(receiver.symbol, parameterlessExactEntryStorage)
            }
        } else {
            storageByValue
        }
        if (operationStorageByValue[receiver.symbol] == null) return null
        val prediction = bindDotNetLocalGenericOwnerPhysicalOperationRouteOrError(
            call = call,
            physicalFunction = function,
            source = source,
            selectedEntry = selection.selectedEntry,
            requiredView = selection.requiredView,
            authority = authority,
        ) { expression ->
            val value = expression.identityGetValueOrNull()
                ?: return@bindDotNetLocalGenericOwnerPhysicalOperationRouteOrError null
            if (value.symbol in conflictingValues) {
                return@bindDotNetLocalGenericOwnerPhysicalOperationRouteOrError null
            }
            operationStorageByValue[value.symbol]?.read()?.value
        } ?: return null
        return when (prediction) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                var semanticEquivalenceEmitterWitness:
                        DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness? = null
                val hasDirectNaturalReceiverCarrier =
                    call.dispatchReceiver.hasEmitterVisibleIdentityStorageRead() &&
                            receiver.hasOperationIndependentDirectCarrier(
                                owner,
                                selection.requiredView,
                                selection.semanticEquivalenceFamily,
                                operationStorageByValue,
                                conflictingValues,
                                authority,
                            )
                val isNaturalCandidate =
                    selection.selectedEntry ==
                            DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE &&
                            (selection.selector ==
                                    DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.EXACT_NATURAL ||
                                    selection.semanticEquivalenceFamily != null)
                val replacedConservativeTarget = if (selection.selectedEntry ==
                    DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE &&
                    isNaturalCandidate &&
                    hasDirectNaturalReceiverCarrier &&
                    mayReplaceConservativeSemanticTarget(call, prediction.value)
                ) {
                    selection.semanticEquivalenceFamily?.let { family ->
                        val directReceiverCarrier = ((operationStorageByValue[receiver.symbol]
                            ?.read()?.value?.layout as?
                                DotNetGenericOwnerProducedValueLayout.Direct)?.carrier)
                            ?: error(
                                "Internal .NET backend error: a certified direct route lost its " +
                                        "exact receiver carrier",
                            )
                        val implementationType = family.types.getValue(
                            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
                        )
                        semanticEquivalenceEmitterWitness =
                            DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness(
                                prediction.value,
                                directReceiverCarrier,
                                implementationType,
                            )
                        context.genericOwnerSemanticEquivalenceEmissionObligations +=
                            family.logicalMember to family.implementationMember
                    }
                    context.genericOwnerCapabilityCallTargets.remove(call)
                    context.genericOwnerForeignDispatchCallTargets.remove(call)
                    diagnostic.actual = actualRoute(call)
                    true
                } else false
                val isAuthorizedNaturalSelection =
                    selection.selectedEntry ==
                            DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE &&
                            (selection.selector ==
                                    DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.EXACT_NATURAL ||
                                    selection.semanticEquivalenceFamily != null &&
                                    replacedConservativeTarget)
                val snapshot = diagnostic.bound(prediction.value, call, selection.selectedEntry)
                check(!replacedConservativeTarget ||
                        snapshot.actualRoute ==
                        DotNetGenericOwnerPhysicalOperationActualRouteSnapshot.DIRECT_NATURAL &&
                        snapshot.relation ==
                        DotNetGenericOwnerPhysicalOperationRouteShadowRelation.MATCH
                ) {
                    "a certified natural-route replacement did not match its final operation route"
                }
                OperationObservation(
                    snapshot,
                    prediction.value.takeIf {
                        isAuthorizedNaturalSelection &&
                                hasDirectNaturalReceiverCarrier &&
                                snapshot.actualRoute ==
                                DotNetGenericOwnerPhysicalOperationActualRouteSnapshot.DIRECT_NATURAL &&
                                snapshot.relation ==
                                DotNetGenericOwnerPhysicalOperationRouteShadowRelation.MATCH
                    },
                    semanticEquivalenceEmitterWitness,
                )
            }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                OperationObservation(diagnostic.conflict(prediction.reason))
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                OperationObservation(diagnostic.unavailable())
        }
    }

    private data class OperationObservation(
        val snapshot: DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot,
        val authoritativeExactNaturalRoute: DotNetGenericOwnerPhysicalOperationRoute? = null,
        val semanticEquivalenceEmitterWitness:
                DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness? = null,
    )

    /**
     * Provenance may establish that an object supports a natural view, but it cannot describe the
     * verifier-visible receiver pushed by codegen. Object-carried receivers and identity wrappers
     * around foreign-dispatch declarations are still claimed by inferred semantic emitters, so
     * publication additionally requires an emitter-visible identity storage read at the call
     * site. Only the exact current natural construction may publish a direct-operation witness
     * until emitter selection itself becomes one shared query.
     */
    private fun DotNetGenericOwnerProducedValueFact.hasDirectCarrierFor(
        view: DotNetGenericOwnerPhysicalView,
    ): Boolean {
        val carrier = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
            ?: return false
        return carrier.nullEncoding == DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE &&
                carrier.type == view.construction
    }

    /**
     * A semantic-equivalence candidate can change operation policy only for the exact concrete
     * construction whose final family it describes. The construction must already be the
     * verifier-visible produced/storage carrier and must carry independent value provenance;
     * neither the logically widened destination nor selected-view lineage can create it.
     */
    private fun DotNetGenericOwnerProducedValueFact.hasCertifiedImplementationCarrierFor(
        view: DotNetGenericOwnerPhysicalView,
        family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): Boolean {
        val declarations = authority.boundDeclarations ?: return false
        val carrier = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
            ?: return false
        if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return false
        val construction = carrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed ?: return false
        val implementation = family.types.getValue(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
        )
        if (construction.definition != implementation) return false
        val constructionView = DotNetGenericOwnerPhysicalView(construction)
        val evidence = (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
            ?.evidenceByView
            ?.get(constructionView)
            ?: return false
        if (evidence.none { item -> item in setOf(
                DotNetGenericOwnerPhysicalViewEvidence.CURRENT_PHYSICAL_RECEIVER,
                DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT,
                DotNetGenericOwnerPhysicalViewEvidence.CONSTRUCTOR_ALLOCATION,
            )
        }) return false
        return when (val closure = declarations.physicalInterfaceViewClosureOrError(construction)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                closure.value.isComplete && view in closure.value.interfaceViews
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> false
        }
    }

    private fun DotNetGenericOwnerProducedValueFact.hasOperationIndependentCarrierFor(
        view: DotNetGenericOwnerPhysicalView,
        semanticEquivalenceFamily: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily?,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): Boolean = hasDirectCarrierFor(view) ||
            semanticEquivalenceFamily?.let { family ->
                hasCertifiedImplementationCarrierFor(view, family, authority)
            } == true

    /**
     * This bounded entry route admits only a construction whose complete argument vector is made
     * from the current physical owner's TypeDef parameters. A fixed `object` argument, a foreign
     * binder, a nested construction, or a MethodDef parameter needs a separate entry proof.
     */
    private fun DotNetGenericOwnerPhysicalView.isDirectCurrentOwnerParameterConstruction(
        physicalOwner: IrClass,
    ): Boolean {
        if (construction.arguments.isEmpty()) return false
        val ownerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            physicalOwner.symbol,
            view = null,
        )
        val ownerParameterIndices = physicalOwner.typeParameters.indices
        return construction.arguments.all { argument ->
            val parameter = argument as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return@all false
            parameter.index in ownerParameterIndices &&
                    parameter.binder ==
                    DotNetGenericOwnerPhysicalGenericBinderReference.Type(ownerIdentity)
        }
    }

    /** Mirrors the emitter's object-preserving identity-wrapper boundary without mapping types. */
    private fun IrExpression?.hasEmitterVisibleIdentityStorageRead(): Boolean = when (this) {
        is IrGetValue -> true
        is IrTypeOperatorCall ->
            (operator == IrTypeOperator.IMPLICIT_CAST ||
                    operator == IrTypeOperator.IMPLICIT_NOTNULL) &&
                    !argument.readsForeignDispatchIdentityDeclaration() &&
                    argument.hasEmitterVisibleIdentityStorageRead()
        else -> false
    }

    private fun IrExpression.readsForeignDispatchIdentityDeclaration(): Boolean = when (this) {
        is IrGetValue -> symbol.owner in context.genericOwnerForeignDispatchDeclarations
        is IrTypeOperatorCall -> when (operator) {
            IrTypeOperator.IMPLICIT_CAST,
            IrTypeOperator.IMPLICIT_NOTNULL,
            -> argument.readsForeignDispatchIdentityDeclaration()
            else -> false
        }
        else -> false
    }

    /**
     * Rejects the placement/operation cycle in which one predicted call-result local would prove
     * the route of a second call before either local has late emitter authority. The bounded first
     * route may cross only identity-preserving local aliases. A parameter boundary must retain
     * producer-recorded entry provenance (or current-receiver provenance); a non-alias local may
     * terminate only at one direct constructor allocation. Arbitrary call-free control flow is
     * not an independent exactness root because its reaching values may include foreign dispatch.
     */
    private fun IrGetValue.hasOperationIndependentDirectCarrier(
        physicalOwner: IrClass,
        view: DotNetGenericOwnerPhysicalView,
        semanticEquivalenceFamily: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily?,
        storageByValue: IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalStorageFact>,
        conflictingValues: Set<IrValueSymbol>,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<IrValueSymbol, Boolean>())
        var read = this
        while (visited.add(read.symbol)) {
            val value = storageByValue[read.symbol]?.read()?.value
            if (read.symbol in conflictingValues ||
                value?.hasOperationIndependentCarrierFor(
                    view,
                    semanticEquivalenceFamily,
                    authority,
                ) != true
            ) return false
            when (val declaration = read.symbol.owner) {
                is IrValueParameter -> return value.hasOperationIndependentEntryEvidence(
                    view,
                    declaration,
                )
                is IrVariable -> {
                    if (declaration.isVar) return false
                    val source = declaration.initializer.identityGetValueOrNull()
                    if (source != null) {
                        val parameter = source.symbol.owner as? IrValueParameter
                        if (parameter != null && storageByValue[source.symbol] == null) {
                            return value.hasOperationIndependentEntryEvidence(view, parameter) ||
                                    semanticEquivalenceFamily != null &&
                                    parameter.kind == IrParameterKind.DispatchReceiver &&
                                    value.hasCertifiedImplementationCarrierFor(
                                        view,
                                        semanticEquivalenceFamily,
                                        authority,
                                    )
                        }
                        read = source
                        continue
                    }
                    if (!declaration.initializer.hasDirectConstructorResultTail()) return false
                    if (semanticEquivalenceFamily != null &&
                        value.hasCertifiedImplementationCarrierFor(
                            view,
                            semanticEquivalenceFamily,
                            authority,
                        )
                    ) return true
                    return when (val exact = bindExactLocalGenericOwnerNaturalViewOrError(
                        declaration.type,
                        physicalOwner,
                        authority,
                    )) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> exact.value == view
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                        -> false
                    }
                }
                else -> return false
            }
        }
        return false
    }

    /**
     * Replaces a broad semantic selector only when declaration authority and an already-produced
     * exact final implementation carrier independently identify the same natural MethodDef.
     * OPEN_NULLABLE remains outside this first proof because its logical materialization has a
     * separate calling-convention obligation.
     */
    private fun selectSemanticallyEquivalentNaturalReceiverOrNull(
        selected: LogicalReceiverSelectionResult.Selected,
        source: IrSimpleFunction,
        receiver: DotNetGenericOwnerProducedValueFact?,
        naturalMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): LogicalReceiverSelectionResult.Selected? {
        if (selected.selector !=
            DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.BROAD_UNIVERSAL ||
            selected.selectedEntry !=
            DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT
        ) return null
        val value = receiver ?: return null
        val construction = ((value.layout as? DotNetGenericOwnerProducedValueLayout.Direct)
            ?.carrier?.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed)
            ?: return null
        val implementation = construction.definition as?
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        if (implementation.view != null) return null
        val family = authority.semanticEquivalenceCandidateOrNull(
            source.symbol,
            implementation.owner,
        ) ?: return null
        if (family.types.getValue(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
            ) != implementation
        ) return null
        val familyNaturalMethod = family.methods.getValue(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT,
        ).second.identity
        if (familyNaturalMethod != naturalMethod) return null
        val declarations = authority.boundDeclarations ?: return null
        val method = declarations.methodDescriptionOrNull(naturalMethod) ?: return null
        val requiredView = when (val binding =
            value.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
                declarations,
                method.declaringType,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return null
        }
        if (!value.hasCertifiedImplementationCarrierFor(requiredView, family, authority)) return null
        return selected.copy(
            selectedEntry = DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
            requiredView = requiredView,
            semanticEquivalenceFamily = family,
        )
    }

    private fun DotNetGenericOwnerProducedValueFact.hasOperationIndependentEntryEvidence(
        view: DotNetGenericOwnerPhysicalView,
        parameter: IrValueParameter,
    ): Boolean {
        if (parameter in context.genericOwnerForeignDispatchDeclarations) return false
        val evidenceByView = (provenance.guaranteedViews as?
                DotNetGenericOwnerGuaranteedViews.Known)?.evidenceByView ?: return false
        if (view !in evidenceByView) return false
        return if (parameter.kind == IrParameterKind.DispatchReceiver) {
            val isCurrentReceiver = evidenceByView.values.any { evidence ->
                DotNetGenericOwnerPhysicalViewEvidence.CURRENT_PHYSICAL_RECEIVER in evidence
            }
            val requiredViewIsRecordedOnThatReceiver = evidenceByView.getValue(view).any { evidence ->
                evidence == DotNetGenericOwnerPhysicalViewEvidence.CURRENT_PHYSICAL_RECEIVER ||
                        evidence == DotNetGenericOwnerPhysicalViewEvidence.RECORDED_INTERFACE_EDGE
            }
            isCurrentReceiver && requiredViewIsRecordedOnThatReceiver
        } else {
            DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT in
                    evidenceByView.getValue(view)
        }
    }

    private fun IrExpression?.hasDirectConstructorResultTail(): Boolean = when (this) {
        is IrConstructorCall -> true
        is IrTypeOperatorCall ->
            (operator == IrTypeOperator.IMPLICIT_CAST ||
                    operator == IrTypeOperator.IMPLICIT_NOTNULL) &&
                    argument.hasDirectConstructorResultTail()
        is IrBlock -> (statements.lastOrNull() as? IrExpression).hasDirectConstructorResultTail()
        is IrComposite -> (statements.lastOrNull() as? IrExpression).hasDirectConstructorResultTail()
        else -> false
    }

    /**
     * A declaration's semantic input/result policy remains logical authority even when a natural
     * receiver happens to be available. Ordinary semantic-capability selection may instead be an
     * imprecise legacy value-flow fallback; a complete BOUND exact operation is stronger positive
     * evidence only when the operation contract itself has no object-domain boundary.
     */
    private fun mayReplaceConservativeSemanticTarget(
        call: IrCall,
        route: DotNetGenericOwnerPhysicalOperationRoute,
    ): Boolean {
        if (call.superQualifierSymbol != null ||
            call !in context.genericOwnerCapabilityCallTargets
        ) return false
        val selectedCapability = context.genericOwnerCapabilityCallTargets.getValue(call)
        val plannedSource = context.genericOwnerCapabilitySlots.entries
            .singleOrNull { entry -> entry.value === selectedCapability }
            ?.key
            ?: context.genericOwnerDefaultCapabilitySlots.entries
                .singleOrNull { entry -> entry.value === selectedCapability }
                ?.key
        plannedSource?.let { source ->
            val owner = source.parent as? IrClass ?: return false
            if (context.publishedGenericInterfaceMemberContracts[source]
                    ?.role
                    ?.requiresSemanticOperation == true
            ) {
                return false
            }
            context.genericOwnerArchitecturePlans[owner]
                ?.memberFamilies
                ?.get(source)
                ?.let { family ->
                    if (family.requiresSemanticResultCapability ||
                        family.requiresSemanticInterfaceInputCapability
                    ) {
                        return false
                    }
                }
        }
        val source = (route.method.identity as?
                DotNetGenericOwnerPhysicalMethodDefIdentity.Local)
            ?.function
            ?.owner
            ?: return false
        val owner = source.parent as? IrClass ?: return false
        context.genericOwnerArchitecturePlans[owner]
            ?.memberFamilies
            ?.get(source)
            ?.let { family ->
                return !family.requiresSemanticResultCapability &&
                        !family.requiresSemanticInterfaceInputCapability
            }

        // Published generic interfaces do not own generic-class architecture plans. Their H
        // member role is nevertheless declaration authority for both semantic results and
        // semantic inputs; an exact receiver cannot narrow either independent value position.
        val memberRole = context.publishedGenericInterfaceMemberContracts[source]?.role
            ?: return false
        return !memberRole.requiresSemanticOperation
    }

    private fun selectLogicalReceiver(
        type: IrType,
        owner: IrClass,
        naturalMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
        semanticMethod: DotNetGenericOwnerPhysicalMethodDefIdentity?,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): LogicalReceiverSelectionResult {
        val declarations = authority.boundDeclarations
            ?: return LogicalReceiverSelectionResult.Unsupported
        val natural = declarations.methodDescriptionOrNull(naturalMethod)
            ?: return LogicalReceiverSelectionResult.Unsupported
        val naturalOwner = natural.declaringType as?
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local
            ?: return LogicalReceiverSelectionResult.Unsupported
        val logicalInterface = naturalOwner.owner.owner
        val simple = type as? IrSimpleType ?: return LogicalReceiverSelectionResult.Unsupported
        if (simple.classifier != logicalInterface.symbol) {
            return LogicalReceiverSelectionResult.Unsupported
        }

        val exactSelector = DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.EXACT_NATURAL
        when (val required = bindExactLocalGenericOwnerNaturalViewOrError(
            type,
            owner,
            authority,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                return if (required.value.family != natural.declaringType) {
                    LogicalReceiverSelectionResult.Conflict(
                        exactSelector,
                        "logical callable and exact local view select different natural TypeDefs",
                    )
                } else {
                    LogicalReceiverSelectionResult.Selected(
                        exactSelector,
                        DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
                        required.value,
                    )
                }
            }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return LogicalReceiverSelectionResult.Conflict(exactSelector, required.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
        }

        // A semantic view may widen only those individual arguments whose declaration-site
        // variance permits it. Other arguments must remain direct current-owner parameters. This
        // classifies the logical view; it never proves or fabricates a natural CLR construction.
        if (simple.arguments.size != logicalInterface.typeParameters.size ||
            owner.typeParameters.isEmpty() || owner.typeParameters.any { parameter ->
                parameter.superTypes.any { bound ->
                    !bound.isAny() && !bound.isNullableAny()
                }
            }
        ) return LogicalReceiverSelectionResult.Unsupported

        fun semanticSelection(
            selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
        ): LogicalReceiverSelectionResult {
            val semantic = semanticMethod?.let(declarations::methodDescriptionOrNull)
                ?: return LogicalReceiverSelectionResult.Unavailable(selector)
            return when (val required = declarations.constructTypeOrError(
                semantic.declaringType,
                emptyList(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    LogicalReceiverSelectionResult.Selected(
                        selector,
                        DotNetLocalGenericOwnerPhysicalCallableEntryKind
                            .SEMANTIC_CAPABILITY_INTERFACE_SLOT,
                        DotNetGenericOwnerPhysicalView(required.value),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    LogicalReceiverSelectionResult.Conflict(selector, required.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    LogicalReceiverSelectionResult.Unavailable(selector)
            }
        }

        var hasBroadUniversalArgument = false
        var hasOpenNullableArgument = false
        simple.arguments.forEachIndexed { index, typeArgument ->
            val projection = typeArgument as? IrTypeProjection
                ?: return LogicalReceiverSelectionResult.Unsupported
            if (projection.variance != Variance.INVARIANT) {
                return LogicalReceiverSelectionResult.Unsupported
            }
            val interfaceParameter = logicalInterface.typeParameters[index]
            val argument = projection.type
            if (argument.isNullableAny()) {
                if (interfaceParameter.variance != Variance.OUT_VARIANCE) {
                    return LogicalReceiverSelectionResult.Unsupported
                }
                hasBroadUniversalArgument = true
                return@forEachIndexed
            }
            val argumentType = argument as? IrSimpleType
                ?: return LogicalReceiverSelectionResult.Unsupported
            val parameter = argumentType.classifier as? IrTypeParameterSymbol
                ?: return LogicalReceiverSelectionResult.Unsupported
            val isOwnerParameter = owner.typeParameters.any { candidate ->
                candidate.symbol == parameter
            }
            if (!isOwnerParameter) return LogicalReceiverSelectionResult.Unsupported
            if (argumentType.isMarkedNullable()) {
                if (interfaceParameter.variance != Variance.OUT_VARIANCE) {
                    return LogicalReceiverSelectionResult.Unsupported
                }
                hasOpenNullableArgument = true
            }
        }
        if (hasBroadUniversalArgument) {
            return semanticSelection(
                DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.BROAD_UNIVERSAL,
            )
        }
        if (hasOpenNullableArgument) {
            return semanticSelection(
                DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.OPEN_NULLABLE,
            )
        }
        return LogicalReceiverSelectionResult.Unavailable(exactSelector)
    }

    private fun actualRoute(call: IrCall): DotNetGenericOwnerPhysicalOperationActualRouteSnapshot {
        val capability = context.genericOwnerCapabilityCallTargets[call]
        val foreign = context.genericOwnerForeignDispatchCallTargets[call]
        return when {
            capability == null && foreign == null ->
                DotNetGenericOwnerPhysicalOperationActualRouteSnapshot.DIRECT_NATURAL
            capability != null && foreign == null ->
                DotNetGenericOwnerPhysicalOperationActualRouteSnapshot.DIRECT_SEMANTIC_CAPABILITY_SLOT
            capability != null && foreign === capability ->
                DotNetGenericOwnerPhysicalOperationActualRouteSnapshot
                    .GUARDED_SEMANTIC_CAPABILITY_WITH_NATURAL_FALLBACK
            else -> DotNetGenericOwnerPhysicalOperationActualRouteSnapshot
                .PARTIAL_OR_INCONSISTENT_SEMANTIC_ROUTE
        }
    }

    private fun IrExpression?.identityGetValueOrNull(): IrGetValue? = when (this) {
        is IrGetValue -> this
        is IrTypeOperatorCall -> when (operator) {
            IrTypeOperator.IMPLICIT_CAST,
            IrTypeOperator.IMPLICIT_NOTNULL,
            -> argument.identityGetValueOrNull()
            else -> null
        }
        else -> null
    }

    private sealed interface LogicalReceiverSelectionResult {
        val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot

        data object Unsupported : LogicalReceiverSelectionResult {
            override val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot
                get() = error("an unsupported logical receiver has no selected policy")
        }

        data class Unavailable(
            override val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
        ) : LogicalReceiverSelectionResult

        data class Conflict(
            override val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
            val reason: String,
        ) : LogicalReceiverSelectionResult

        data class Selected(
            override val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
            val selectedEntry: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
            val requiredView: DotNetGenericOwnerPhysicalView,
            val semanticEquivalenceFamily:
                DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily? = null,
        ) : LogicalReceiverSelectionResult
    }

    private inner class OperationDiagnostic(
        val owner: IrClass,
        val function: IrSimpleFunction,
        val receiver: IrValueSymbol,
        val source: IrSimpleFunction,
        val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
        val requiredView: DotNetGenericOwnerPhysicalView?,
        var actual: DotNetGenericOwnerPhysicalOperationActualRouteSnapshot,
        val authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ) {
        fun bound(
            route: DotNetGenericOwnerPhysicalOperationRoute,
            call: IrCall,
            selectedEntry: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
        ): DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot {
            val expectedTarget = (route.method.identity as?
                    DotNetGenericOwnerPhysicalMethodDefIdentity.Local)?.function
            val matches = when (selectedEntry) {
                DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE ->
                    expectedTarget === source.symbol &&
                            context.genericOwnerCapabilityCallTargets[call] == null &&
                            context.genericOwnerForeignDispatchCallTargets[call] == null
                DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT ->
                    expectedTarget != null &&
                            context.genericOwnerCapabilityCallTargets[call]?.symbol ===
                            expectedTarget &&
                            context.genericOwnerForeignDispatchCallTargets[call]?.symbol.let { foreign ->
                                foreign == null || foreign === expectedTarget
                            }
            }
            val result = route.instantiatedSignature.resultLayout.toSnapshot(authority)
            val methodArguments = route.methodArguments.map { argument ->
                when (val carrier = checkNotNull(authority.boundDeclarations)
                    .carrierOrError(argument)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        authority.carrierSnapshotOrNull(carrier.value)
                            ?: error(
                                "a BOUND physical operation has an unrenderable " +
                                        "MethodSpec argument carrier",
                            )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    -> error("a BOUND physical operation contains an unbound MethodSpec argument")
                }
            }
            return snapshot(
                status = DotNetGenericOwnerPhysicalOperationRouteShadowStatus.BOUND,
                predictedKind = selectedEntry.toSnapshot(),
                methodArguments = methodArguments,
                result = result,
                relation = if (matches) {
                    DotNetGenericOwnerPhysicalOperationRouteShadowRelation.MATCH
                } else {
                    DotNetGenericOwnerPhysicalOperationRouteShadowRelation.DIFFERENT
                },
                diagnostic = if (matches) null else
                    "the BOUND MethodDef route differs from the existing final-routing maps",
            )
        }

        fun unavailable() = snapshot(
            status = DotNetGenericOwnerPhysicalOperationRouteShadowStatus.UNAVAILABLE,
            predictedKind = null,
            methodArguments = emptyList(),
            result = null,
            relation = DotNetGenericOwnerPhysicalOperationRouteShadowRelation.PREDICTION_UNAVAILABLE,
            diagnostic = "the selected physical operation route is not proven by BOUND authority",
        )

        fun conflict(reason: String) = snapshot(
            status = DotNetGenericOwnerPhysicalOperationRouteShadowStatus.CONFLICT,
            predictedKind = null,
            methodArguments = emptyList(),
            result = null,
            relation = DotNetGenericOwnerPhysicalOperationRouteShadowRelation.DECLARATION_CONFLICT,
            diagnostic = reason,
        )

        private fun snapshot(
            status: DotNetGenericOwnerPhysicalOperationRouteShadowStatus,
            predictedKind: DotNetGenericOwnerPhysicalOperationRouteKindSnapshot?,
            methodArguments: List<DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot>,
            result: ResultSnapshot?,
            relation: DotNetGenericOwnerPhysicalOperationRouteShadowRelation,
            diagnostic: String?,
        ): DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot {
            val declarations = checkNotNull(authority.boundDeclarations)
            val requiredCarrier = requiredView?.let { view ->
                when (val carrier = declarations.carrierOrError(view.construction)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        authority.carrierSnapshotOrNull(carrier.value)
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    -> null
                }
            } ?: unknownPhysicalValueCarrierSnapshot
            return DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot(
                ownerName = owner.dotNetPhysicalValueStableName(),
                physicalFunctionName = function.name.asString(),
                receiverVariableName = receiver.owner.name.asString(),
                logicalMemberName = source.name.asString(),
                logicalSelector = selector,
                status = status,
                predictedRouteKind = predictedKind,
                requiredReceiverCarrier = requiredCarrier,
                methodArgumentCarriers = methodArguments,
                resultLayout = result?.layout,
                resultSlotDomain = result?.slot?.domain,
                resultCarrierKind = result?.slot?.carrierKind,
                resultCarrierParameterBinderOwnerName = result?.slot?.parameterBinderOwnerName,
                resultCarrierParameterIndex = result?.slot?.parameterIndex,
                actualRoute = actual,
                relation = relation,
                diagnostic = diagnostic,
            )
        }
    }

    private fun DotNetGenericOwnerPhysicalCallableResultLayoutReference.toSnapshot(
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): ResultSnapshot = when (this) {
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
            ResultSnapshot(
                DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot.DIRECT,
                slot.toSnapshot(authority),
            )
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            ResultSnapshot(
                DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot.SPLIT_NULLABLE,
                payloadSlot.toSnapshot(authority),
            )
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void ->
            ResultSnapshot(DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot.VOID, null)
    }

    private fun DotNetGenericOwnerPhysicalCallableValueSlotReference.toSnapshot(
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): ResultSlotSnapshot {
        if (carrier == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()) {
            return ResultSlotSnapshot(
                domain,
                DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot.OBJECT,
            )
        }
        val parameter = carrier as? DotNetGenericOwnerSymbolicCarrierReference.Parameter
        val binder = parameter?.binder as? org.jetbrains.kotlin.backend.dotnet
            .DotNetGenericOwnerPhysicalGenericBinderReference.Type
        val localBinder = binder?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        val binderName = localBinder?.let(authority::inputOrNull)?.logicalOwnerName
        return if (parameter != null && binderName != null) {
            ResultSlotSnapshot(
                domain,
                DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot.OWNER_PARAMETER,
                binderName,
                parameter.index,
            )
        } else {
            ResultSlotSnapshot(
                domain,
                DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot.OTHER,
            )
        }
    }

    private data class ResultSnapshot(
        val layout: DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot,
        val slot: ResultSlotSnapshot?,
    )

    private data class ResultSlotSnapshot(
        val domain: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain,
        val carrierKind: DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot,
        val parameterBinderOwnerName: String? = null,
        val parameterIndex: Int? = null,
    )
}

private fun DotNetLocalGenericOwnerPhysicalCallableEntryKind.toSnapshot():
        DotNetGenericOwnerPhysicalOperationRouteKindSnapshot =
    DotNetGenericOwnerPhysicalOperationRouteKindSnapshot.valueOf(name)
