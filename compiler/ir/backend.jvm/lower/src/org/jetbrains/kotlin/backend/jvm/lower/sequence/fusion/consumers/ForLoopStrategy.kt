/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.LoopData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers.updateLoopVariableInBody
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl

internal class ForLoopStrategy(data: ConsumerData, val loopData: LoopData, expression: IrBlock) :
    ConsumerStrategy(data, expression) {
    var loop: IrLoop? = null
    override fun initialDeclarations(): List<IrVariable> = emptyList()

    override fun getConsumerBuilder(): ConsumerBodyBuilder {
        return { sequenceElement ->
            val results = updateLoopVariableInBody(data.builder, loopData.loopVariable, loopData.loopBody, loopData.loop, data.parent)
            val preparedBody = results.first(sequenceElement)
            loop = results.second
            data.builder.irReturnableBlock(data.context.irBuiltIns.booleanType) {
                loop?.let {
                    preparedBody.rebindJumps(
                        it,
                        { IrReturnImpl(startOffset, endOffset, context.irBuiltIns.nothingType, returnableBlockSymbol, irFalse()) },
                        { IrReturnImpl(startOffset, endOffset, context.irBuiltIns.nothingType, returnableBlockSymbol, irTrue()) })
                }
                +preparedBody
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

    override fun createResult(): IrExpression = data.builder.irUnit()
}
