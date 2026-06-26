/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.LoopData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers.updateLoopVariableInBody
import org.jetbrains.kotlin.ir.builders.irReturnFalse
import org.jetbrains.kotlin.ir.builders.irReturnTrue
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop

internal class ForLoopConsumerStrategy(data: ConsumerData, val loopData: LoopData, expression: IrBlock) :
    ConsumerStrategy(data, expression) {
    override val returnsElement: Boolean = false
    var loop: IrLoop? = null
    override fun initializeState(): List<IrVariable> = emptyList()

    override fun getConsumerBuilder(): ConsumerBodyBuilder {
        return { sequenceElement ->
            val results = updateLoopVariableInBody(data.builder, loopData.loopVariable, loopData.loopBody, loopData.loop, data.parent)
            val preparedBody = results.first(sequenceElement)
            loop = results.second
            val block = data.builder.irReturnableBlock(data.context.irBuiltIns.booleanType) {}
            loop?.let {
                preparedBody.rebindJumps(
                    it,
                    { data.builder.irReturnFalse().apply { returnTargetSymbol = block.symbol } },
                    { data.builder.irReturnTrue().apply { returnTargetSymbol = block.symbol } })
            }
            block.statements.add(preparedBody)
            block.statements.add(data.builder.irReturnTrue().apply { returnTargetSymbol = block.symbol })
            block
        }
    }

    override fun finalizeResult(): IrExpression = data.builder.irUnit()
}
