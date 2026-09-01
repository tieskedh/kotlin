/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRoute
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOperationRouteRequest
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerProducedValueFact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
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
    physicalOwner: IrClass,
    source: IrSimpleFunction,
    selectedEntry: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
    requiredView: DotNetGenericOwnerPhysicalView,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    evaluateValue: (IrExpression) -> DotNetGenericOwnerProducedValueFact?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute>? {
    val declarations = authority.boundDeclarations
        ?: return null
    val selectedMethod = authority.callableMethodOrNull(source.symbol, selectedEntry)
        ?: return null
    val selectedDescription = declarations.methodDescriptionOrNull(selectedMethod)
        ?: return null
    val methodArguments = when (val binding = bindDotNetLocalGenericOwnerMethodArgumentsOrError(
        call,
        physicalOwner,
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

/** Binds only current-TypeDef parameters; other MethodSpec forms remain explicit future grammar. */
private fun bindDotNetLocalGenericOwnerMethodArgumentsOrError(
    call: IrCall,
    physicalOwner: IrClass,
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
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(arguments)
}
