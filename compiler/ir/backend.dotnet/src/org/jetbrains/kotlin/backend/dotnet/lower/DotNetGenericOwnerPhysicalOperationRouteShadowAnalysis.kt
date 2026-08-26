/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableValueSlotReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationActualRouteSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteKindSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteRequest
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowRelation
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStorageFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowPhase
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCallableEntryKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalValueStableName
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.backend.dotnet.unknownPhysicalValueCarrierSnapshot
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
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
import org.jetbrains.kotlin.ir.types.makeNotNull
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
 * It does not populate a call-target map, alter IR, choose a carrier, or authorize emission. Calls
 * without one unique successful POST storage fact are deliberately omitted from this bounded
 * shadow rather than reported as covered.
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
            val conflictingValues = Collections.newSetFromMap(IdentityHashMap<IrValueSymbol, Boolean>())
            for (record in records) {
                val storage = record.predictedStorage ?: continue
                val existing = storageByValue.put(record.variable, storage)
                if (existing != null) conflictingValues += record.variable
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
                        conflictingValues,
                        authority,
                    )?.let(snapshots::add)
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
        conflictingValues: Set<IrValueSymbol>,
        authority: DotNetLocalGenericOwnerPhysicalAuthority,
    ): DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot? {
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
        val storage = storageByValue[receiver.symbol] ?: return null
        val declarations = authority.boundDeclarations ?: return null
        val selection = selectLogicalReceiver(
            logicalReceiverType,
            owner,
            naturalMethod,
            semanticMethod,
            authority,
        )
        val actual = actualRoute(call)
        if (selection is LogicalReceiverSelectionResult.Unsupported) return null
        val selector = selection.selector
        val requiredView = (selection as? LogicalReceiverSelectionResult.Selected)?.requiredView
        val diagnostic = OperationDiagnostic(
            owner = owner,
            function = function,
            receiver = receiver.symbol,
            source = source,
            selector = selector,
            requiredView = requiredView,
            actual = actual,
            authority = authority,
        )
        when (selection) {
            is LogicalReceiverSelectionResult.Conflict ->
                return diagnostic.conflict(selection.reason)
            is LogicalReceiverSelectionResult.Unavailable ->
                return diagnostic.unavailable()
            is LogicalReceiverSelectionResult.Selected -> Unit
            LogicalReceiverSelectionResult.Unsupported -> error("handled above")
        }
        val selectedMethod = authority.callableMethodOrNull(
            source.symbol,
            selection.selectedEntry,
        ) ?: return diagnostic.unavailable()
        val prediction = selectDotNetGenericOwnerPhysicalOperationRoute(
            declarations = declarations,
            selectedMethod = selectedMethod,
            request = DotNetGenericOwnerPhysicalOperationRouteRequest(
                selection.requiredView,
            ),
            receiver = storage.read().value,
            arguments = emptyList(),
        )
        return when (prediction) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                diagnostic.bound(prediction.value, call, selection.selectedEntry)
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                diagnostic.conflict(prediction.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                diagnostic.unavailable()
        }
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
        val interfaceParameter = logicalInterface.typeParameters.singleOrNull()
            ?: return LogicalReceiverSelectionResult.Unsupported
        val simple = type as? IrSimpleType ?: return LogicalReceiverSelectionResult.Unsupported
        if (simple.classifier != logicalInterface.symbol ||
            simple.arguments.size != 1 || owner.typeParameters.size != 1 ||
            owner.typeParameters.single().superTypes.any { bound ->
                !bound.isAny() && !bound.isNullableAny()
            }
        ) return LogicalReceiverSelectionResult.Unsupported
        val projection = simple.arguments.single() as? IrTypeProjection
            ?: return LogicalReceiverSelectionResult.Unsupported
        if (projection.variance != Variance.INVARIANT) {
            return LogicalReceiverSelectionResult.Unsupported
        }
        val argument = projection.type

        fun semanticSelection(
            selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
        ): LogicalReceiverSelectionResult {
            if (interfaceParameter.variance != Variance.OUT_VARIANCE) {
                return LogicalReceiverSelectionResult.Unsupported
            }
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

        if (argument.isNullableAny()) {
            return semanticSelection(
                DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.BROAD_UNIVERSAL,
            )
        }
        val parameter = (argument as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
            ?: return LogicalReceiverSelectionResult.Unsupported
        val isOwnerParameter = owner.typeParameters.any { candidate ->
            candidate.symbol == parameter &&
                    (argument == candidate.defaultType ||
                            argument.isMarkedNullable() &&
                            argument.makeNotNull() == candidate.defaultType)
        }
        if (!isOwnerParameter) return LogicalReceiverSelectionResult.Unsupported
        if (argument.isMarkedNullable()) {
            return semanticSelection(
                DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.OPEN_NULLABLE,
            )
        }
        val selector = DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot.EXACT_NATURAL
        return when (val required = bindExactLocalGenericOwnerNaturalViewOrError(
            type,
            owner,
            authority,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                if (required.value.family != natural.declaringType) {
                    LogicalReceiverSelectionResult.Conflict(
                        selector,
                        "logical callable and exact local view select different natural TypeDefs",
                    )
                } else {
                    LogicalReceiverSelectionResult.Selected(
                        selector,
                        DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
                        required.value,
                    )
                }
            }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                LogicalReceiverSelectionResult.Conflict(selector, required.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                LogicalReceiverSelectionResult.Unavailable(selector)
        }
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
        ) : LogicalReceiverSelectionResult
    }

    private inner class OperationDiagnostic(
        val owner: IrClass,
        val function: IrSimpleFunction,
        val receiver: IrValueSymbol,
        val source: IrSimpleFunction,
        val selector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
        val requiredView: DotNetGenericOwnerPhysicalView?,
        val actual: DotNetGenericOwnerPhysicalOperationActualRouteSnapshot,
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
            return snapshot(
                status = DotNetGenericOwnerPhysicalOperationRouteShadowStatus.BOUND,
                predictedKind = selectedEntry.toSnapshot(),
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
            result = null,
            relation = DotNetGenericOwnerPhysicalOperationRouteShadowRelation.PREDICTION_UNAVAILABLE,
            diagnostic = "the selected physical operation route is not proven by BOUND authority",
        )

        fun conflict(reason: String) = snapshot(
            status = DotNetGenericOwnerPhysicalOperationRouteShadowStatus.CONFLICT,
            predictedKind = null,
            result = null,
            relation = DotNetGenericOwnerPhysicalOperationRouteShadowRelation.DECLARATION_CONFLICT,
            diagnostic = reason,
        )

        private fun snapshot(
            status: DotNetGenericOwnerPhysicalOperationRouteShadowStatus,
            predictedKind: DotNetGenericOwnerPhysicalOperationRouteKindSnapshot?,
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
