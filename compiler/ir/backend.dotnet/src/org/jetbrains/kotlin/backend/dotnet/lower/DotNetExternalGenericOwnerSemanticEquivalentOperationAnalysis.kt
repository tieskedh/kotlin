/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerGuaranteedViews
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCarrier
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNullState
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteRequest
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueLocalPlacementAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalViewEvidence
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSealedTypeDefRole
import org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.declarationIndependentLeafCarrierOrNull
import org.jetbrains.kotlin.backend.dotnet.genericOwnerDeclarationIndependentLeafPrototypeOrNull
import org.jetbrains.kotlin.backend.dotnet.genericOwnerDirectNonNullOwnerResultParameterIndexOrNull
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance
import java.util.IdentityHashMap

/**
 * First external consumer of a PE-stamped generic-owner semantic-equivalence certificate.
 *
 * This deliberately small final-IR transfer starts at one exact, non-null, producer-recorded
 * implementation parameter, preserves that already-produced carrier through one immutable
 * identity alias, and routes one arity-zero broad-result call through the producer-recorded
 * natural MethodDef. The `K` certificate contributes only declaration equivalence: it never
 * creates the implementation construction, its natural view, or the alias storage fact.
 *
 * Mutable locals, control-flow joins, stars/projections, broad parameters, method generics,
 * constructors, fields, captures, and object/capability-carried roots remain semantic. They need
 * independent transfer rules rather than a wider interpretation of this certificate.
 */
internal class DotNetExternalGenericOwnerSemanticEquivalentOperationAnalysis(
    private val context: DotNetBackendContext,
) {
    private val externalDeclarations: DotNetExternalDeclarations =
        context.externalDeclarationsForLowering()

    fun analyze(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment) {
        check(context.genericOwnerPhysicalOperationRouteShadowAnalysisCompleted) {
            "external semantic-equivalence routing requires the completed local operation analysis"
        }
        module.files.forEach { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>().forEach(::analyzeTopLevelFunction)
        }
    }

    private fun analyzeTopLevelFunction(function: IrSimpleFunction) {
        if (function.typeParameters.isNotEmpty() || function.body == null) return
        val calls = mutableListOf<IrCall>()
        function.body?.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) = Unit

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitCall(expression: IrCall) {
                calls += expression
                expression.acceptChildrenVoid(this)
            }
        })
        calls.forEach { call -> admitCallOrNull(function, call) }
    }

    private fun admitCallOrNull(function: IrSimpleFunction, call: IrCall) {
        if (call.superQualifierSymbol != null || call.typeArguments.isNotEmpty()) return
        val source = call.symbol.owner.let { candidate ->
            candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
        }
        if (source.fileOrNull != null || source.typeParameters.isNotEmpty() ||
            source.parameters.any { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
        ) return
        val logicalInterface = source.parent as? IrClass ?: return
        if (!logicalInterface.isInterface) return
        if (source.genericOwnerDirectNonNullOwnerResultParameterIndexOrNull() == null) return

        val receiverRead = call.dispatchReceiver.identityGetValueOrNull() ?: return
        val alias = receiverRead.symbol.owner as? IrVariable ?: return
        if (alias.isVar || !alias.hasOnlyReceiverReadAt(function, receiverRead)) return
        if (!alias.type.isBroadUniversalViewOf(logicalInterface)) return
        val parameterRead = alias.initializer.identityGetValueOrNull() ?: return
        val parameter = parameterRead.symbol.owner as? IrValueParameter ?: return
        if (parameter.kind != IrParameterKind.Regular || parameter.parent !== function ||
            parameter in context.genericOwnerForeignDispatchDeclarations
        ) return

        val parameterType = parameter.type as? IrSimpleType ?: return
        if (parameterType.isMarkedNullable()) return
        val implementationOwner = parameterType.classifier.owner as? IrClass ?: return
        if (implementationOwner.fileOrNull != null || implementationOwner.isInterface ||
            implementationOwner.modality != Modality.FINAL
        ) return
        val fixedArguments = parameterType.fixedExternalImplementationArgumentsOrNull() ?: return

        val endpoint = externalDeclarations
            .genericOwnerSemanticEquivalenceEndpointOrNull(source, implementationOwner)
            ?: return
        val certificate = endpoint.certificate
        val implementationMember = endpoint.implementationMember

        val physicalAuthority = when (
            val binding = DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority.bind(
                certificate,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        val declarations = physicalAuthority.declarations
        val implementationType = physicalAuthority.typeDefinition(
            DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        )
        val implementationConstruction = when (
            val binding = declarations.constructTypeOrError(implementationType, fixedArguments)
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        val directCarrier = when (
            val binding = DotNetGenericOwnerPhysicalCarrier.bind(
                declarations,
                implementationConstruction,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }

        var provenance = DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(
                mapOf(
                    DotNetGenericOwnerPhysicalView(implementationConstruction) to setOf(
                        DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT,
                    ),
                ),
            ),
        )
        val closure = when (
            val binding = declarations.physicalInterfaceViewClosureOrError(
                implementationConstruction,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        if (!closure.isComplete) return
        closure.interfaceViews.forEach { view ->
            provenance = provenance.guarantee(
                view,
                DotNetGenericOwnerPhysicalViewEvidence.RECORDED_INTERFACE_EDGE,
            )
        }
        val receiverValue = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(directCarrier),
            provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
        val naturalMethod = declarations.methodDescriptionOrNull(
            physicalAuthority.naturalMethodDefinition,
        ) ?: return
        val requiredView = when (
            val binding = receiverValue.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
                declarations,
                naturalMethod.declaringType,
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        val route = when (
            val binding = selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations = declarations,
                selectedMethod = physicalAuthority.naturalMethodDefinition,
                request = DotNetGenericOwnerPhysicalOperationRouteRequest(requiredView),
                receiver = receiverValue,
                arguments = emptyList(),
            )
        ) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                error("Internal .NET backend error: ${binding.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        if (route.instantiatedSignature.genericArity != 0 ||
            route.instantiatedSignature.parameterSlots.isNotEmpty() ||
            route.instantiatedSignature.resultLayout !is
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
        ) return

        val semanticTarget = context.genericOwnerCapabilityCallTargets[call] ?: return
        val expectedSemanticTarget = context.externalReifiedGenericInterfaceCapabilitySlots[source]
            ?: return
        if (semanticTarget !== expectedSemanticTarget) return
        val foreignTarget = context.genericOwnerForeignDispatchCallTargets[call]
        if (foreignTarget != null && foreignTarget !== semanticTarget) return

        val placement = DotNetGenericOwnerPhysicalValueLocalPlacementAuthority
            .externalExactStorageRead(directCarrier, implementationOwner.symbol)
        val witness = DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness.external(
            route,
            directCarrier,
            source.symbol,
            implementationOwner.symbol,
            implementationMember.symbol,
            certificate,
            physicalAuthority,
        )
        val placements = context.genericOwnerExternalSemanticEquivalentReceiverPlacements
            .getOrPut(function.symbol) { IdentityHashMap() }
        if (call in context.genericOwnerAuthoritativePhysicalOperationRoutes ||
            call in context.genericOwnerSemanticEquivalentOperationEmitterWitnesses ||
            alias.symbol in placements
        ) {
            error("Internal .NET backend error: duplicate external semantic-equivalence authority")
        }

        check(context.genericOwnerCapabilityCallTargets.remove(call) === semanticTarget) {
            "external semantic-equivalence routing lost its conservative semantic target"
        }
        if (foreignTarget != null) {
            check(context.genericOwnerForeignDispatchCallTargets.remove(call) === foreignTarget) {
                "external semantic-equivalence routing lost its conservative foreign target"
            }
        }
        context.genericOwnerAuthoritativePhysicalOperationRoutes[call] = route
        context.genericOwnerSemanticEquivalentOperationEmitterWitnesses[call] = witness
        placements[alias.symbol] = placement
    }

    private fun IrSimpleType.fixedExternalImplementationArgumentsOrNull():
            List<DotNetGenericOwnerSymbolicCarrierReference>? {
        val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
        for (argument in this.arguments) {
            val projection = argument as? IrTypeProjection ?: return null
            if (projection.variance != Variance.INVARIANT || projection.type.isMarkedNullable()) {
                return null
            }
            val carrier = projection.type.genericOwnerDeclarationIndependentLeafPrototypeOrNull()
                ?.declarationIndependentLeafCarrierOrNull()
                ?: return null
            if (carrier != DotNetGenericOwnerSymbolicCarrierReference.int32Carrier() &&
                carrier != DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
            ) return null
            arguments += carrier
        }
        return arguments.takeIf { it.size == 1 }
    }

    private fun org.jetbrains.kotlin.ir.types.IrType.isBroadUniversalViewOf(
        logicalInterface: IrClass,
    ): Boolean {
        val simple = this as? IrSimpleType ?: return false
        if (simple.classifier != logicalInterface.symbol) return false
        val projection = simple.arguments.singleOrNull() as? IrTypeProjection ?: return false
        return projection.variance == Variance.INVARIANT && projection.type.isNullableAny()
    }

    private fun IrVariable.hasOnlyReceiverReadAt(
        function: IrSimpleFunction,
        expectedRead: IrGetValue,
    ): Boolean {
        var readCount = 0
        var hasExpectedRead = false
        function.body?.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) = Unit

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol === symbol) {
                    readCount++
                    hasExpectedRead = hasExpectedRead || expression === expectedRead
                }
                expression.acceptChildrenVoid(this)
            }
        })
        return readCount == 1 && hasExpectedRead
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
}
