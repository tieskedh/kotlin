/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getPredicateArgument
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.dump

internal class ForEachConsumerStrategy(data: ConsumerData, expression: IrCall) : ConsumerStrategy(data, expression) {
    override val returnsElement: Boolean = false
    override fun initializeState(): List<IrVariable> = emptyList()

    override fun getConsumerBuilder(): ConsumerBodyBuilder? {
        val expression = expression as IrCall
        val predicate = getPredicateArgument(expression, 1) ?: return null
        with(data.builder) {
            val forEachPredicateCall: (IrValueDeclaration) -> IrExpression = when (predicate) {
                is IrRichFunctionReference -> { sequenceElement ->
                    callRichFunctionReference(predicate, data.parent, irGet(sequenceElement))
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
                    } ?: error("Didn't find invoke for the predicate argument of forEach: ${predicate.dump()}")
                }
            }
            return { sequenceElement ->
                irReturnableBlock(data.context.irBuiltIns.booleanType) {
                    +forEachPredicateCall(sequenceElement)
                    +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        value = irTrue()
                    )
                }
            }
        }
    }

    override fun finalizeResult(): IrExpression = data.builder.irUnit()
}
