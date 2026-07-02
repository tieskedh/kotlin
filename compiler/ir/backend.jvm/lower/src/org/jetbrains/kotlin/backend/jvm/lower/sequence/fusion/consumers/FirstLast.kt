/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrReturnableBlockSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.dump

internal class FirstLastConsumerStrategy(data: ConsumerData, expression: IrCall, val isFirst: Boolean, val isOrNull: Boolean) :
    ConsumerStrategy(data, expression) {
    override val returnsElement: Boolean = true
    val resultVariable: IrVariable = data.builder.scope.createTemporaryVariable(
        data.builder.irNull(),
        "result",
        isMutable = true,
        irType = expression.type.makeNullable()
    )
    val skippedVariable: IrVariable = data.builder.scope.createTemporaryVariable(data.builder.irTrue(), "skipped", isMutable = true)
    override fun initializeState(): List<IrVariable> = listOf(skippedVariable, resultVariable)

    override fun getConsumerBuilder(): ConsumerBodyBuilder? {
        val predicate = (expression as IrCall).arguments.getOrNull(1)
        if (predicate is IrCall) return null
        return when (predicate) {
            is IrRichFunctionReference -> { sequenceElement ->
                data.builder.irReturnableBlock(data.context.irBuiltIns.booleanType) {
                    val predicateCall = callRichFunctionReference(
                        predicate,
                        data.parent,
                        irGet(sequenceElement)
                    )
                    +buildFirstLastBody(data.builder, sequenceElement, predicateCall, returnableBlockSymbol)
                }
            }
            null -> { sequenceElement ->
                data.builder.irReturnableBlock(data.context.irBuiltIns.booleanType) {
                    +irSet(resultVariable, irGet(sequenceElement))
                    +irSet(skippedVariable, irFalse())
                    if (isFirst) +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        value = irFalse()
                    )
                    else +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        value = irTrue()
                    )
                }
            }
            else -> { sequenceElement ->
                val invokeSymbol = expression.arguments.getOrNull(1)?.type?.classOrNull?.owner?.declarations
                    ?.filterIsInstance<IrSimpleFunction>()
                    ?.first { it.name.asString() == "invoke" }?.symbol
                val predicateCall = invokeSymbol?.let {
                    data.builder.irCall(it).apply {
                        dispatchReceiver = predicate
                        arguments[1] = data.builder.irGet(sequenceElement)
                    }
                } ?: error("Didn't find invoke for the predicate argument of first: ${predicate.dump()}")
                data.builder.irReturnableBlock(data.context.irBuiltIns.booleanType) {
                    +buildFirstLastBody(data.builder, sequenceElement, predicateCall, returnableBlockSymbol)
                }
            }
        }
    }

    private fun buildFirstLastBody(
        builder: IrBuilderWithScope,
        sequenceElement: IrValueDeclaration,
        predicateCall: IrExpression,
        returnableBlockSymbol: IrReturnableBlockSymbol
    ): IrExpression = with(builder) {
        val predicateResult =
            scope.createTemporaryVariable(
                predicateCall,
                nameHint = "predicateResult"
            )
        val thenPart = irBlock {
            +irSet(resultVariable, irGet(sequenceElement))
            +irSet(skippedVariable, irFalse())
        }
        irBlock {
            +predicateResult
            +irIfThen(irGet(predicateResult), thenPart)
            if (isFirst) +IrReturnImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = context.irBuiltIns.nothingType,
                returnTargetSymbol = returnableBlockSymbol,
                value = irNot(irGet(predicateResult))
            )
            else +IrReturnImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = context.irBuiltIns.nothingType,
                returnTargetSymbol = returnableBlockSymbol,
                value = irTrue()
            )
        }
    }

    override fun finalizeResult(): IrExpression {
        return createFirstLastFinalResult(isOrNull, data.builder, resultVariable, skippedVariable, data)
    }
}

internal fun createFirstLastFinalResult(
    isOrNull: Boolean,
    builder: IrBuilderWithScope,
    resultVariable: IrVariable,
    skippedVariable: IrVariable,
    data: ConsumerData,
): IrExpression = builder.irBlock {
    if (isOrNull) {
        +irGet(resultVariable)
    } else {
        val wasSkipped = irGet(skippedVariable)
        val throwException = irThrow(
            irCallConstructor(data.context.symbols.noSuchElementExceptionCtorString, emptyList()).apply {
                arguments[0] = irString("Sequence is empty.")
            }
        )
        +irIfThen(wasSkipped, throwException)
        +irGet(resultVariable)
    }
}
