/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.types.makeNullable

internal class FirstNotNullOfConsumerStrategy(data: ConsumerData, expression: IrCall, val isOrNull: Boolean) :
    ConsumerStrategy(data, expression) {
    override val returnsElement: Boolean = true
    val resultVariable: IrVariable = data.builder.scope.createTemporaryVariable(
        data.builder.irNull(),
        "result",
        isMutable = true,
        irType = expression.type.makeNullable()
    )
    val skippedVariable: IrVariable = data.builder.scope.createTemporaryVariable(data.builder.irTrue(), "skipped", isMutable = true)
    val builder = data.builder
    override fun initializeState(): List<IrVariable> = listOf(resultVariable, skippedVariable)

    override fun getConsumerBuilder(): ConsumerBodyBuilder? {
        val transformFunction = (expression as IrCall).arguments.getOrNull(1) as? IrRichFunctionReference ?: return null
        with(builder) {
            return { sequenceElement ->
                val block = irReturnableBlock(context.irBuiltIns.booleanType) {}
                val transformResult = callRichFunctionReference(transformFunction, data.parent, irGet(sequenceElement))
                val transformResultVariable = scope.createTemporaryVariable(transformResult, "transformResult")
                block.statements.add(transformResultVariable)
                val isTransformNotNull = irNotEquals(irGet(transformResultVariable), irNull())
                val thenPart = irBlock {
                    +irSet(resultVariable, irGet(transformResultVariable))
                    +irSet(skippedVariable, irFalse())
                }
                val isFoundVariable = scope.createTemporaryVariable(isTransformNotNull, "isFound")
                block.statements.add(isFoundVariable)
                block.statements.add(irIfThen(irGet(isFoundVariable), thenPart))
                block.statements.add(irReturn(irNot(irGet(isFoundVariable))).apply { returnTargetSymbol = block.symbol })
                block
            }
        }
    }

    override fun finalizeResult(): IrExpression {
        return createFirstLastFinalResult(isOrNull, data.builder, resultVariable, skippedVariable, data)
    }
}
