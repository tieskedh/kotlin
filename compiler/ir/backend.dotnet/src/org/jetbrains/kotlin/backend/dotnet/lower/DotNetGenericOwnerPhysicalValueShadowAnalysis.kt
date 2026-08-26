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
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullEncoding
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
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowPhase
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowTypeDefView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowViewSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowRecord
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
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
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance

/**
 * First production-inert consumer of the generic-owner physical-value model.
 *
 * The shadow recognizes only exact current-receiver flow, broad object parameters, and immutable
 * single-definition object/generic aliases. It observes both the moved pre-remap body and the final
 * routing fixpoint, but it neither mutates IR nor publishes a fact to any routing/emission structure.
 */
internal class DotNetGenericOwnerPhysicalValueShadowAnalysis(
    private val context: DotNetBackendContext,
) {
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
            function = physical,
            semanticSource = source,
            authority = bindOwnerAuthorityOrNull(plan),
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
                val authority = bindOwnerAuthorityOrNull(plan)
                plan.owner.declarations.filterIsInstance<IrSimpleFunction>()
                    .forEach { function ->
                        records += analyzeFunction(
                            plan.owner,
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

            authority?.let { ownerAuthority ->
                physical.parameters.forEach { parameter ->
                    when {
                        parameter === physical.dispatchReceiverParameter ->
                            storageByValue[parameter.symbol] = ownerAuthority.receiverStorage
                        parameter.kind == IrParameterKind.Regular && parameter.type.isObjectShadowType() -> {
                            val nullState = if (parameter.type.isNullableAny()) {
                                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
                            } else {
                                DotNetGenericOwnerPhysicalNullState.NON_NULL
                            }
                            storageByValue[parameter.symbol] = DotNetGenericOwnerPhysicalStorageFact(
                                DotNetGenericOwnerStorageCarrier.Fixed(ownerAuthority.objectCarrier),
                                DotNetGenericOwnerPhysicalValueProvenance(
                                    DotNetGenericOwnerGuaranteedViews.Unknown,
                                ),
                                nullState,
                            )
                        }
                    }
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
                    is IrVariable -> if (statement.type.isPhysicalValueShadowCandidateType()) {
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
            if (!variable.type.isPhysicalValueShadowCandidateType()) return false
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
            val produced = evaluateInitializerOrNull(initializer, storage)
            if (produced == null) {
                records += diagnostic.unsupported("initializer is outside the shadow transfer grammar")
                return false
            }
            val selectedStorage = selectStorageCarrierOrNull(variable, produced, ownerAuthority)
            if (selectedStorage == null) {
                records += diagnostic.unsupported(
                    "deferred storage has no independently proven direct reference carrier",
                )
                return false
            }
            val placed = produced.placeInStorageOrNull(selectedStorage, ::canStoreIdentityPreserving)
            if (placed == null) {
                records += diagnostic.unsupported("initializer requires a non-identity storage conversion")
                return false
            }
            storage[variable.symbol] = placed
            records += diagnostic.analyzed(produced, placed, owner, ownerAuthority.identity)
            return true
        }

        private fun selectStorageCarrierOrNull(
            variable: IrVariable,
            produced: DotNetGenericOwnerProducedValueFact,
            ownerAuthority: OwnerAuthority,
        ): DotNetGenericOwnerStorageCarrier.Fixed? {
            val requestedCarrier: DotNetGenericOwnerStorageCarrier = when {
                variable.type.isObjectShadowType() ->
                    DotNetGenericOwnerStorageCarrier.Fixed(ownerAuthority.objectCarrier)
                variable.type.isDeferredGenericShadowType() -> DotNetGenericOwnerStorageCarrier.Deferred
                else -> DotNetGenericOwnerStorageCarrier.Unknown
            }
            if (requestedCarrier is DotNetGenericOwnerStorageCarrier.Fixed) return requestedCarrier
            if (requestedCarrier != DotNetGenericOwnerStorageCarrier.Deferred) return null

            // The logical generic destination contributes only Deferred storage. Selection comes
            // from the already-produced verifier carrier and its independent physical guarantee.
            val carrier = (produced.layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
                ?: return null
            if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
            val construction = carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
                ?: return null
            val guaranteed = (produced.provenance.guaranteedViews as?
                    DotNetGenericOwnerGuaranteedViews.Known)?.views.orEmpty()
            if (DotNetGenericOwnerPhysicalView(construction) !in guaranteed) return null
            return DotNetGenericOwnerStorageCarrier.Fixed(carrier)
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
            is IrBlock -> evaluateContainerOrNull(expression.statements, storage)
            is IrComposite -> evaluateContainerOrNull(expression.statements, storage)
            else -> null
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
                initializerProducedCarrier = unknownCarrierSnapshot,
                storageCarrier = unknownCarrierSnapshot,
                guaranteeState = DotNetGenericOwnerPhysicalValueShadowGuaranteeState.UNKNOWN,
                guaranteedViews = emptyList(),
                selectedViewLineage = emptyList(),
                initializerNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
                contentsNullState = DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN,
                unsupportedReason = reason,
            ),
            predictedStorage = null,
        )

        fun analyzed(
            initializer: DotNetGenericOwnerProducedValueFact,
            storage: DotNetGenericOwnerPhysicalStorageFact,
            currentOwner: IrClass,
            currentIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        ): DotNetGenericOwnerPhysicalValueShadowRecord {
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
                ),
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

        fun org.jetbrains.kotlin.ir.types.IrType.isPhysicalValueShadowCandidateType(): Boolean =
            isObjectShadowType() || isDeferredGenericShadowType()

        fun org.jetbrains.kotlin.ir.types.IrType.hasUnsupportedProjection(): Boolean {
            val simple = this as? IrSimpleType ?: return false
            return simple.arguments.any { argument ->
                val projection = argument as? IrTypeProjection ?: return@any true
                projection.variance != Variance.INVARIANT || projection.type.hasUnsupportedProjection()
            }
        }

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
                    kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
                    localOwnerName = currentOwner.stableShadowName(),
                    ownerParameterIndices = indices,
                    localTypeDefView = currentIdentity.view?.let { view ->
                        DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(view.name)
                    },
                    parameterBinderOwnerName = currentOwner.stableShadowName(),
                    parameterBinderTypeDefView = currentIdentity.view?.let { view ->
                        DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(view.name)
                    },
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
