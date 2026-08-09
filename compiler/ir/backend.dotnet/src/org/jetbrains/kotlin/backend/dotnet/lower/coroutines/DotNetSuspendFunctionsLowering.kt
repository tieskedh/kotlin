/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower.coroutines

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.lower.AbstractSuspendFunctionsLowering
import org.jetbrains.kotlin.backend.common.lower.FinallyBlocksLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_LAMBDA_IMPL
import org.jetbrains.kotlin.backend.common.lower.ReturnableBlockTransformer
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.optimizations.LivenessAnalysis
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.assertedCast

/**
 * Transforms suspend function into a `CoroutineImpl` instance and builds a state machine.
 */
internal class DotNetSuspendFunctionsLowering(
    private val context: DotNetBackendContext,
) : BodyLoweringPass {
    private val delegate by lazy { DotNetSuspendFunctionsLoweringImpl(context) }

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrSimpleFunction && container.isSuspend) {
            delegate.lower(irBody, container)
        }
    }
}

private class DotNetSuspendFunctionsLoweringImpl(
    context: DotNetBackendContext,
) : AbstractSuspendFunctionsLowering<DotNetBackendContext>(context), BodyLoweringPass {
    private val coroutineImplExceptionPropertyGetter = context.symbols.coroutineImplExceptionPropertyGetter.owner
    private val coroutineImplExceptionPropertySetter = context.symbols.coroutineImplExceptionPropertySetter.owner
    private val coroutineImplExceptionStatePropertyGetter = context.symbols.coroutineImplExceptionStatePropertyGetter.owner
    private val coroutineImplExceptionStatePropertySetter = context.symbols.coroutineImplExceptionStatePropertySetter.owner
    private val coroutineImplLabelPropertySetter = context.symbols.coroutineImplLabelPropertySetter.owner
    private val coroutineImplLabelPropertyGetter = context.symbols.coroutineImplLabelPropertyGetter.owner
    private val coroutineImplResultSymbolGetter = context.symbols.coroutineImplResultSymbolGetter.owner

    override val stateMachineMethodName = Name.identifier("doResume")

    override fun getCoroutineBaseClass(function: IrFunction) = context.symbols.coroutineImpl

    override fun nameForCoroutineClass(function: IrFunction) = "${function.name}COROUTINE\$".synthesizedName


    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrSimpleFunction && container.isSuspend) {
            transformSuspendFunction(container, irBody)?.let {
                val dc = container.parent as IrDeclarationContainer
                dc.addChild(it)
            }
        }
    }

    private fun transformSuspendFunction(function: IrSimpleFunction, body: IrBody): IrClass? {
        assert(function.isSuspend)

        return when (val functionKind = getSuspendFunctionKind(context, function, body)) {
            is SuspendFunctionKind.NO_SUSPEND_CALLS -> {
                null                                                            // No suspend function calls - just an ordinary function.
            }

            is SuspendFunctionKind.DELEGATING -> {                              // Calls another suspend function at the end.
                removeReturnIfSuspendedCallAndSimplifyDelegatingCall(function, functionKind.delegatingCall)
                null                                                            // No need in state machine.
            }

            is SuspendFunctionKind.NEEDS_STATE_MACHINE -> {
                val isLoweredSuspendLambda = function.isOperator &&
                        function.name == OperatorNameConventions.INVOKE &&
                        function.parentClassOrNull?.let { it.origin === DOTNET_LAMBDA_IMPL } == true
                val coroutine = buildCoroutine(function, isLoweredSuspendLambda)      // Coroutine implementation.
                if (!isLoweredSuspendLambda) {
                    // Common gives the generated constructor the source function's visibility.
                    // That is sufficient for JS, but a CLR file facade and its top-level state-
                    // machine class are distinct TypeDefs: a private constructor is inaccessible
                    // even though both types are compiler-generated in one assembly. Keep the
                    // state-machine type private and follow the existing .NET local-class policy:
                    // its constructor is public in metadata, which exposes no callable surface
                    // while the declaring type itself remains inaccessible.
                    coroutine.primaryConstructor!!.visibility = DescriptorVisibilities.PUBLIC
                }
                if (isLoweredSuspendLambda)             // Suspend lambdas are called through factory method <create>,
                    null
                else
                    coroutine
            }
        }
    }

    /**
     * A single tail suspend call already speaks the continuation/sentinel ABI and therefore
     * needs no state-machine object. Preserve the direct call after unwrapping the Common
     * `returnIfSuspended` marker. JS/Wasm make the same semantic optimization in their explicit
     * state-machine path; on .NET it is ordinary CIL call/return code rather than a generator.
     */
    private fun removeReturnIfSuspendedCallAndSimplifyDelegatingCall(irFunction: IrFunction, delegatingCall: IrCall) {
        val returnValue =
            if (delegatingCall.isReturnIfSuspendedCall(context))
                delegatingCall.arguments[0]!!
            else delegatingCall

        val body = irFunction.body as IrBlockBody
        val statements = body.statements
        val lastStatement = statements.last()

        context.createIrBuilder(
            irFunction.symbol,
            startOffset = lastStatement.startOffset,
            endOffset = lastStatement.endOffset
        ).run {
            assert(lastStatement == delegatingCall || lastStatement is IrReturn) { "Unexpected statement $lastStatement" }

            // Instead of returning right away, we save the value to a temporary variable and after that return that variable.
            // This is done solely to improve the debugging experience. Otherwise, a breakpoint set to the closing brace of the function
            // cannot be hit.
            val tempVar = scope.createTemporaryVariable(
                irExpression = returnValue,
                irType = context.irBuiltIns.anyType,
            )
            statements[statements.lastIndex] = tempVar
            statements.add(irReturn(irGet(tempVar)))
        }
    }

    override fun buildStateMachine(
        stateMachineFunction: IrFunction,
        transformingFunction: IrFunction,
        argumentToPropertiesMap: Map<IrValueParameter, IrField>
    ) {
        // Common's coroutine builder creates propertyless fields after callable lowering has
        // already classified closure storage. Give those argument/state fields the same target-
        // neutral captured-value origin used by JVM suspend lambdas and ordinary .NET closures;
        // they are private state-machine storage, not a new public field category.
        argumentToPropertiesMap.values.forEach { field ->
            field.origin = LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE
        }
        val returnableBlockTransformer = ReturnableBlockTransformer(context)
        val finallyBlockTransformer = FinallyBlocksLowering(context, context.irBuiltIns.throwableType)
        val simplifiedFunction =
            transformingFunction.transform(finallyBlockTransformer, null).transform(returnableBlockTransformer, null) as IrFunction

        val originalBody = simplifiedFunction.body as IrBlockBody

        val body = IrBlockImpl(
            simplifiedFunction.startOffset,
            simplifiedFunction.endOffset,
            context.irBuiltIns.unitType,
            DotNetCoroutineOrigins.STATEMENT_ORIGIN_COROUTINE_IMPL,
            originalBody.statements
        )

        val coroutineClass = stateMachineFunction.parent as IrClass
        val suspendResult = DotNetCoroutineIrBuilder.buildVar(
            context.irBuiltIns.anyNType,
            stateMachineFunction,
            "suspendResult",
            true,
            initializer = DotNetCoroutineIrBuilder.buildCall(coroutineImplResultSymbolGetter.symbol).apply {
                dispatchReceiver = DotNetCoroutineIrBuilder.buildGetValue(stateMachineFunction.dispatchReceiverParameter!!.symbol)
            }
        )

        val suspendState = DotNetCoroutineIrBuilder.buildVar(coroutineImplLabelPropertyGetter.returnType, stateMachineFunction, "suspendState", true)

        val unit = context.irBuiltIns.unitType

        val switch = IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, unit, DotNetCoroutineOrigins.COROUTINE_SWITCH)
        val stateVar = DotNetCoroutineIrBuilder.buildVar(context.irBuiltIns.intType, stateMachineFunction)
        val switchBlock = IrBlockImpl(switch.startOffset, switch.endOffset, switch.type).apply {
            statements += stateVar
            statements += switch
        }
        val rootTry = IrTryImpl(body.startOffset, body.endOffset, unit).apply { tryResult = switchBlock }
        val rootLoop = IrDoWhileLoopImpl(
            body.startOffset,
            body.endOffset,
            unit,
            DotNetCoroutineOrigins.COROUTINE_ROOT_LOOP,
        ).also {
            it.condition = DotNetCoroutineIrBuilder.buildBoolean(context.irBuiltIns.booleanType, true)
            it.body = rootTry
            it.label = "\$sm"
        }

        val suspendableNodes = collectSuspendableNodes(body)
        val thisReceiver = (stateMachineFunction.dispatchReceiverParameter as IrValueParameter).symbol
        stateVar.initializer = DotNetCoroutineIrBuilder.buildCall(coroutineImplLabelPropertyGetter.symbol).apply {
            dispatchReceiver = DotNetCoroutineIrBuilder.buildGetValue(thisReceiver)
        }

        val stateMachineBuilder = StateMachineBuilder(
            suspendableNodes,
            context,
            stateMachineFunction.symbol,
            rootLoop,
            coroutineImplExceptionPropertyGetter,
            coroutineImplExceptionPropertySetter,
            coroutineImplExceptionStatePropertyGetter,
            coroutineImplExceptionStatePropertySetter,
            coroutineImplLabelPropertySetter,
            thisReceiver,
            getSuspendResultAsType = { type ->
                DotNetCoroutineIrBuilder.buildImplicitCast(
                    DotNetCoroutineIrBuilder.buildGetValue(suspendResult.symbol),
                    type
                )
            },
            setSuspendResultValue = { value ->
                DotNetCoroutineIrBuilder.buildSetVariable(
                    suspendResult.symbol,
                    DotNetCoroutineIrBuilder.buildImplicitCast(
                        value,
                        context.irBuiltIns.anyNType
                    ),
                    unit
                )
            }
        )

        body.acceptVoid(stateMachineBuilder)

        stateMachineBuilder.finalizeStateMachine()

        rootTry.catches += stateMachineBuilder.globalCatch

        assignStateIds(stateMachineBuilder.entryState, stateVar.symbol, switch, rootLoop)

        // Set exceptionState to the global catch block
        stateMachineBuilder.entryState.entryBlock.run {
            val receiver = DotNetCoroutineIrBuilder.buildGetValue(coroutineClass.thisReceiver!!.symbol)
            val exceptionTrapId = stateMachineBuilder.rootExceptionTrap.id
            check(exceptionTrapId >= 0)
            val id = DotNetCoroutineIrBuilder.buildInt(context.irBuiltIns.intType, exceptionTrapId)
            statements.add(0, DotNetCoroutineIrBuilder.buildCall(coroutineImplExceptionStatePropertySetter.symbol).also { call ->
                call.arguments[0] = receiver
                call.arguments[1] = id
            })
        }

        val functionBody = context.irFactory.createBlockBody(
            stateMachineFunction.startOffset,
            stateMachineFunction.endOffset,
            stateMachineBuilder.allTheIntermediateLocals + suspendResult + rootLoop
        )

        stateMachineFunction.body = functionBody

        // Move return targets to new function
        functionBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitReturn(expression: IrReturn): IrExpression {
                expression.transformChildrenVoid(this)

                return if (expression.returnTargetSymbol != simplifiedFunction.symbol)
                    expression
                else
                    DotNetCoroutineIrBuilder.buildReturn(stateMachineFunction.symbol, expression.value, context.irBuiltIns.nothingType)
            }
        })

        val liveLocals = LivenessAnalysis.run(functionBody, { it is IrCall && it.isSuspend })
            .values.flatten().toSet()

        val localToPropertyMap = hashMapOf<IrValueSymbol, IrFieldSymbol>()
        var localCounter = 0
        // Keep one property per live local. Sharing slots is a representation-only optimization
        // and remains on hold until the broader spill matrix has stable evidence.
        liveLocals.forEach {
            if (it !== suspendState && it !== suspendResult && it !== stateVar) {
                localToPropertyMap.getOrPut(it.symbol) {
                    coroutineClass.addField(Name.identifier("${it.name}${localCounter++}"), it.type, it.isVar)
                        .also { field ->
                            field.origin = LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE
                        }
                        .symbol
                }
            }
        }
        val isSuspendLambda = transformingFunction.parent === coroutineClass
        val parameters = if (isSuspendLambda) simplifiedFunction.nonDispatchParameters else simplifiedFunction.parameters
        for (parameter in parameters) {
            localToPropertyMap.getOrPut(parameter.symbol) {
                argumentToPropertiesMap.getValue(parameter).symbol
            }
        }

        // The Common-shaped construction can retain parents from before the body moved to the
        // state-machine method; restore the final declaration tree before replacing live locals.
        stateMachineFunction.body!!.patchDeclarationParents(stateMachineFunction)
        stateMachineFunction.transform(LiveLocalsTransformer(localToPropertyMap, { DotNetCoroutineIrBuilder.buildGetValue(thisReceiver) }, unit), null)
    }

    private fun assignStateIds(entryState: SuspendState, subject: IrVariableSymbol, switch: IrWhen, rootLoop: IrLoop) {
        val visited = mutableSetOf<SuspendState>()

        val sortedStates = DFS.topologicalOrder(listOf(entryState), { it.successors }, { visited.add(it) })
        sortedStates.withIndex().forEach { it.value.id = it.index }

        val eqeqeqInt = context.irBuiltIns.eqeqeqSymbol

        for (state in sortedStates) {
            val condition = DotNetCoroutineIrBuilder.buildCall(eqeqeqInt).apply {
                arguments[0] = DotNetCoroutineIrBuilder.buildGetValue(subject)
                arguments[1] = DotNetCoroutineIrBuilder.buildInt(context.irBuiltIns.intType, state.id)
            }

            switch.branches += IrBranchImpl(state.entryBlock.startOffset, state.entryBlock.endOffset, condition, state.entryBlock)
        }

        val dispatchPointTransformer = DispatchPointTransformer {
            assert(it.id >= 0)
            DotNetCoroutineIrBuilder.buildInt(context.irBuiltIns.intType, it.id)
        }

        rootLoop.transformChildrenVoid(dispatchPointTransformer)
    }

    override fun IrBlockBodyBuilder.generateCoroutineStart(invokeSuspendFunction: IrFunction, receiver: IrExpression) =
        +irReturn(irCall(invokeSuspendFunction.symbol).apply { arguments[0] = receiver })
}

internal sealed class SuspendFunctionKind {
    object NO_SUSPEND_CALLS : SuspendFunctionKind()
    class DELEGATING(val delegatingCall: IrCall) : SuspendFunctionKind()
    object NEEDS_STATE_MACHINE : SuspendFunctionKind()
}

internal fun getSuspendFunctionKind(
    context: CommonBackendContext,
    function: IrSimpleFunction,
    body: IrBody,
    includeSuspendLambda: Boolean = true,
    suspensionIntrinsic: IrSimpleFunctionSymbol? = null
): SuspendFunctionKind {

    fun IrSimpleFunction.isSuspendLambda() =
        name.asString() == "invoke" && parentClassOrNull?.let { it.origin === DOTNET_LAMBDA_IMPL } == true

    if (function.isSuspendLambda() && includeSuspendLambda)
        return SuspendFunctionKind.NEEDS_STATE_MACHINE            // Suspend lambdas always need coroutine implementation.

    var numberOfSuspendCalls = 0
    body.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            expression.acceptChildrenVoid(this)
            if (expression.isSuspend || expression.symbol == suspensionIntrinsic)
                ++numberOfSuspendCalls
        }
    })
    // It is important to optimize the case where there is only one suspend call and it is the last statement
    // because we don't need to build a fat coroutine class in that case.
    // This happens a lot in practice because of suspend functions with default arguments.
    // Retain the established single-tail-call test. General tail-call collection is a later
    // refactor and must not change which bodies receive an observable state-machine class.
    val lastCall = when (val lastStatement = (body as IrBlockBody).statements.lastOrNull()) {
        is IrCall ->
            // Delegation to call without return can only be performed to Unit-returning function call from Unit-returning function
            if (lastStatement.type == context.irBuiltIns.unitType && function.returnType == context.irBuiltIns.unitType)
                lastStatement
            else
                null
        is IrReturn -> {
            fun IrTypeOperatorCall.isImplicitCast(): Boolean {
                return this.operator == IrTypeOperator.IMPLICIT_CAST || this.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
            }

            var value: IrElement = lastStatement
            /*
             * Check if matches this pattern:
             * block/return {
             *     block/return {
             *         .. suspendCall()
             *     }
             * }
             */
            loop@ while (true) {
                value = when {
                    value is IrBlock && value.statements.size == 1 -> value.statements.first()
                    value is IrReturn -> value.value
                    value is IrTypeOperatorCall && value.isImplicitCast() -> value.argument
                    else -> break@loop
                }
            }
            value as? IrCall
        }
        else -> null
    }
    val suspendCallAtEnd = lastCall != null && lastCall.isSuspend    // Suspend call.
    return when {
        numberOfSuspendCalls == 0 -> SuspendFunctionKind.NO_SUSPEND_CALLS
        numberOfSuspendCalls == 1
                && suspendCallAtEnd -> SuspendFunctionKind.DELEGATING(lastCall)
        else -> SuspendFunctionKind.NEEDS_STATE_MACHINE
    }
}

// Suppress since it is used in native
@Suppress("MemberVisibilityCanBePrivate")
internal fun IrCall.isReturnIfSuspendedCall(context: DotNetBackendContext) =
    symbol == context.symbols.returnIfSuspended
