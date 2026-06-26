/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference

internal class FindConsumerStrategy(data: ConsumerData, expression: IrCall, val isFirst: Boolean) : ConsumerStrategy(data, expression) {
    override val returnsElement: Boolean = true
    val resultVariable: IrVariable = data.builder.scope.createTemporaryVariable(
        data.builder.irNull(),
        isMutable = true,
        irType = expression.type
    )

    override fun initializeState(): List<IrVariable> {
        return listOf(resultVariable)
    }

    override fun getConsumerBuilder(): ConsumerBodyBuilder {
        with(data.builder) {
            return { sequenceElement ->
                val expression = expression as IrCall
                val findPredicate = expression.arguments.getOrNull(1) as? IrRichFunctionReference ?: error("No predicate argument for find")
                /*
                for find:
                ```
                val wasFound = findPredicate(sequenceElement)
                if (wasFound) result = sequenceElement
                return !wasFound
                ```
                for findLast:
                ```
                val wasFound = findPredicate(sequenceElement)
                if (wasFound) result = sequenceElement
                return true // always tell the producer to check all the elements
                ```
                 */
                val block = irReturnableBlock(context.irBuiltIns.booleanType) {}
                val wasFoundVariable =
                    scope.createTemporaryVariable(callRichFunctionReference(findPredicate, data.parent, irGet(sequenceElement)))
                block.statements.add(wasFoundVariable)
                block.statements.add(
                    irIfThen(
                        context.irBuiltIns.unitType,
                        irGet(wasFoundVariable),
                        irSet(resultVariable, irGet(sequenceElement))
                    )
                )
                val result = if (isFirst) irNot(irGet(wasFoundVariable)) else irTrue()
                block.statements.add(irReturn(result).apply { returnTargetSymbol = block.symbol })
                block
            }
        }
    }

    override fun finalizeResult(): IrExpression = data.builder.irGet(resultVariable)
}
