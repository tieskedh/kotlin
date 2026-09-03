/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericBinderReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteRequest
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceView
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCallableEntryKind
import org.jetbrains.kotlin.backend.dotnet.selectDotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Binds one final IR call to the MethodDef already selected by logical-family policy.
 *
 * Logical receiver classification remains outside this function: callers supply both the selected
 * entry and its required physical view. This binder then obtains the authority-owned MethodDef,
 * binds its complete MethodSpec vector, evaluates the receiver and ordinary arguments as physical
 * values, and delegates the actual proof to the shared IR-free operation query. It never infers a
 * MethodDef arity or CLR construction from the call's logical Kotlin result type. A null result
 * means that the IR call site is outside this bounded binding grammar; `Unavailable` is reserved
 * for a complete physical operation query which could not prove a route.
 */
internal fun bindDotNetLocalGenericOwnerPhysicalOperationRouteOrError(
    call: IrCall,
    physicalFunction: IrSimpleFunction,
    source: IrSimpleFunction,
    selectedEntry: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
    requiredView: DotNetGenericOwnerPhysicalView,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    evaluateValue: (IrExpression) -> DotNetGenericOwnerProducedValueFact?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute>? {
    val physicalOwner = physicalFunction.parent as? IrClass ?: return null
    val declarations = authority.boundDeclarations
        ?: return null
    val selectedMethod = authority.callableMethodOrNull(source.symbol, selectedEntry)
        ?: return null
    val selectedDescription = declarations.methodDescriptionOrNull(selectedMethod)
        ?: return null
    val methodArguments = when (val binding = bindDotNetLocalGenericOwnerMethodArgumentsOrError(
        call,
        physicalOwner,
        physicalFunction,
        selectedDescription,
        selectedEntry,
        selectedDescription.signature.genericArity,
        authority,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return null
    }
    val receiver = call.dispatchReceiver?.let(evaluateValue)
        ?: return null
    val arguments = mutableListOf<DotNetGenericOwnerProducedValueFact>()
    source.parameters.forEach { parameter ->
        if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
        val argument = call.arguments.getOrNull(parameter.indexInParameters)?.let(evaluateValue)
            ?: return null
        arguments += argument
    }
    return selectDotNetGenericOwnerPhysicalOperationRoute(
        declarations = declarations,
        selectedMethod = selectedMethod,
        request = DotNetGenericOwnerPhysicalOperationRouteRequest(
            requiredView,
            methodArguments,
        ),
        receiver = receiver,
        arguments = arguments,
    )
}

/**
 * Binds bare current-TypeDef parameters and the first exact current-MethodDef operation form.
 *
 * A caller MethodDef parameter is admitted only as the sole MethodSpec argument of a natural
 * `<R>(R): T` producer. This keeps mixed, split, nested, constrained, semantic, and multiple-
 * binder operations outside the gate even though the shared IR-free model can represent them.
 */
private fun bindDotNetLocalGenericOwnerMethodArgumentsOrError(
    call: IrCall,
    physicalOwner: IrClass,
    physicalFunction: IrSimpleFunction,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefReference,
    selectedEntry: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
    expectedArity: Int,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<List<DotNetGenericOwnerSymbolicCarrierReference>> {
    if (call.typeArguments.size != expectedArity) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "selected physical MethodDef has generic arity $expectedArity, " +
                    "but final IR supplies ${call.typeArguments.size} type arguments",
        )
    }
    if (expectedArity == 0) {
        return DotNetGenericOwnerPhysicalBindingResult.Bound(emptyList())
    }
    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
    var currentMethodArgumentCount = 0
    for (type in call.typeArguments) {
        type ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        when (val binding = bindExactLocalGenericOwnerParameterCarrierOrError(
            type,
            physicalOwner,
            authority,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> when (val current =
                bindExactLocalCurrentMethodParameterCarrierOrError(
                    type,
                    physicalFunction,
                    authority,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                    arguments += current.value
                    currentMethodArgumentCount++
                }
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(current.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }
    }
    if (currentMethodArgumentCount != 0 &&
        (selectedEntry != DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE ||
                currentMethodArgumentCount != 1 || arguments.size != 1 ||
                call.superQualifierSymbol != null ||
                !selectedMethod.isDirectCallerMethodParameterProducer(authority.boundDeclarations))
    ) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    return DotNetGenericOwnerPhysicalBindingResult.Bound(arguments)
}

/** Exact open callee grammar for the first current-MethodDef MethodSpec consumer. */
private fun DotNetGenericOwnerPhysicalMethodDefReference
        .isDirectCallerMethodParameterProducer(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
        ): Boolean {
    declarations ?: return false
    val ownerIdentity = declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return false
    if (ownerIdentity.view != DotNetGenericInterfaceView.DECLARED) return false
    val owner = declarations.typeDescriptionOrNull(ownerIdentity) ?: return false
    if (owner.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        owner.genericParameters.singleOrNull()?.isUnconstrained != true
    ) return false
    if (!signature.isInstance || signature.genericArity != 1 ||
        genericParameters.singleOrNull()?.isUnconstrained != true
    ) return false
    val input = signature.parameterSlots.singleOrNull() ?: return false
    if (input.domain != DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) return false
    val inputParameter = input.carrier as?
            DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return false
    val inputBinder = inputParameter.binder as?
            DotNetGenericOwnerPhysicalGenericBinderReference.Method ?: return false
    if (inputBinder.definition != identity || inputParameter.index != 0) return false

    val result = signature.resultLayout as?
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ?: return false
    if (result.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT) return false
    val resultParameter = result.slot.carrier as?
            DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return false
    val resultBinder = resultParameter.binder as?
            DotNetGenericOwnerPhysicalGenericBinderReference.Type ?: return false
    return resultBinder.definition == declaringType && resultParameter.index == 0
}
