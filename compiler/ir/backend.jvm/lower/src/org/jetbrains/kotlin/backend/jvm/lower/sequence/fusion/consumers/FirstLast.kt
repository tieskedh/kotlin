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
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.types.makeNullable

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

    override fun getConsumerBuilder(): ConsumerBodyBuilder {
        val possiblePredicate = (expression as IrCall).arguments.getOrNull(1)
        val containsPredicate = possiblePredicate != null
        val predicate = if (containsPredicate)
            possiblePredicate as? IrRichFunctionReference
                ?: error("The predicate argument for first/last is not a function reference")
        else null
        with(data.builder) {
            return { sequenceElement ->
                val block = irReturnableBlock(context.irBuiltIns.booleanType) {}
                var predicateResult: IrVariable? = null
                if (containsPredicate) {
                    predicateResult =
                        scope.createTemporaryVariable(
                            callRichFunctionReference(predicate!!, data.parent, irGet(sequenceElement)),
                            nameHint = "predicateResult"
                        )
                    val thenPart = irBlock {
                        +irSet(resultVariable, irGet(sequenceElement))
                        +irSet(skippedVariable, irFalse())
                    }
                    block.statements.add(predicateResult)
                    block.statements.add(irIfThen(irGet(predicateResult), thenPart))
                } else {
                    block.statements.add(irSet(resultVariable, irGet(sequenceElement)))
                    block.statements.add(irSet(skippedVariable, irFalse()))
                }
                val result = if (isFirst) if (containsPredicate) irNot(irGet(predicateResult!!)) else irFalse() else irTrue()
                block.statements.add(irReturn(result).apply { returnTargetSymbol = block.symbol })
                block
            }
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
