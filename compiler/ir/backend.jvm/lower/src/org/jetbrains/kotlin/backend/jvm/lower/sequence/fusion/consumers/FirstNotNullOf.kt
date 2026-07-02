/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getPredicateArgument
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNotEquals
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
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.dump

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
        val transform = getPredicateArgument(expression as IrCall, 1) ?: return null
        val transformCall: (IrValueDeclaration) -> IrExpression = when (transform) {
            is IrRichFunctionReference -> { sequenceElement ->
                builder.callRichFunctionReference(
                    transform,
                    data.parent,
                    builder.irGet(sequenceElement)
                )
            }
            else -> { sequenceElement ->
                val invokeSymbol = expression.arguments.getOrNull(1)?.type?.classOrNull?.owner?.declarations
                    ?.filterIsInstance<IrSimpleFunction>()
                    ?.first { it.name.asString() == "invoke" }?.symbol
                invokeSymbol?.let {
                    builder.irCall(it).apply {
                        dispatchReceiver = transform
                        arguments[1] = builder.irGet(sequenceElement)
                    }
                }
                    ?: error("Didn't find invoke for the predicate argument of firstNotNullOf${if (isOrNull) "OrNull" else ""}: ${transform.dump()}")
            }
        }
        with(builder) {
            return { sequenceElement ->
                irReturnableBlock(context.irBuiltIns.booleanType) {
                    val transformResult = transformCall(sequenceElement)
                    val transformResultVariable = scope.createTemporaryVariable(transformResult, "transformResult")
                    +transformResultVariable
                    val isTransformNotNull = irNotEquals(irGet(transformResultVariable), irNull())
                    val thenPart = irBlock {
                        +irSet(resultVariable, irGet(transformResultVariable))
                        +irSet(skippedVariable, irFalse())
                    }
                    val isFoundVariable = scope.createTemporaryVariable(isTransformNotNull, "isFound")
                    +isFoundVariable
                    +irIfThen(irGet(isFoundVariable), thenPart)
                    +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        value = irNot(irGet(isFoundVariable))
                    )
                }
            }
        }
    }

    override fun finalizeResult(): IrExpression {
        return createFirstLastFinalResult(isOrNull, data.builder, resultVariable, skippedVariable, data)
    }
}
