/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getPredicateArgument
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.dump

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

    override fun getConsumerBuilder(): ConsumerBodyBuilder? {
        val expression = expression as IrCall
        val predicate = getPredicateArgument(expression, 1) ?: return null
        with(data.builder) {
            val predicateCall: (IrValueDeclaration) -> IrExpression = when (predicate) {
                is IrRichFunctionReference -> { sequenceElement ->
                    callRichFunctionReference(
                        predicate,
                        data.parent,
                        irGet(sequenceElement)
                    )
                }
                else -> { sequenceElement ->
                    val invokeSymbol = expression.arguments.getOrNull(1)?.type?.classOrNull?.owner?.declarations
                        ?.filterIsInstance<IrSimpleFunction>()
                        ?.first { it.name.asString() == "invoke" }?.symbol
                    invokeSymbol?.let {
                        irCall(it).apply {
                            dispatchReceiver = predicate
                            arguments[1] = irGet(sequenceElement)
                        }
                    } ?: error("Didn't find invoke for the predicate argument of find: ${predicate.dump()}")
                }
            }
            return { sequenceElement ->
                val wasFoundVariable =
                    scope.createTemporaryVariable(predicateCall(sequenceElement))
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
                irReturnableBlock(context.irBuiltIns.booleanType) {
                    +wasFoundVariable
                    +irIfThen(
                        context.irBuiltIns.unitType,
                        irGet(wasFoundVariable),
                        irSet(resultVariable, irGet(sequenceElement))
                    )
                    val result = if (isFirst) irNot(irGet(wasFoundVariable)) else irTrue()
                    +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        result
                    )
                }
            }
        }
    }

    override fun finalizeResult(): IrExpression = data.builder.irGet(resultVariable)
}
