/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.GenerateSequenceInitialValue
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.IrBuilderWithParent
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceSource
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBreak
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

internal class GenerateSequenceStrategy(
    val source: SequenceSource.GenerateSequence
) : ProducerStrategy() {

    override fun fuseConsumer(
        builderWithParent: IrBuilderWithParent,
        sequenceData: SequenceData,
        consumerBodyBuilder: ConsumerBodyBuilder,
        initialDeclarations: List<IrVariable>,
        finalExpression: IrExpression
    ): IrContainerExpression {
        val builder = builderWithParent.first
        val parent = builderWithParent.second
        val generatingFunction = source.generatingFunction

        val oneArgumentIteratingFunction: (IrVariable) -> IrExpression = { variable ->
            builder.callRichFunctionReference(
                generatingFunction,
                parent,
                builder.irAsNotNull(builder.irGet(variable))
            )
        }

        val zeroArgumentIteratingFunction: (IrVariable) -> IrExpression = { _ ->
            builder.callRichFunctionReference(generatingFunction, parent)
        }

        val initialExpression = when (val initialValue = source.initialValue) {
            is GenerateSequenceInitialValue.InitialValue -> initialValue.expression.deepCopyWithSymbols(parent)
            is GenerateSequenceInitialValue.InitialFunction -> builder.callRichFunctionReference(initialValue.function, parent)
            is GenerateSequenceInitialValue.NoInitialValue -> builder.callRichFunctionReference(generatingFunction, parent)
        }
        val evaluateNext = when (source.initialValue) {
            is GenerateSequenceInitialValue.InitialValue -> oneArgumentIteratingFunction
            is GenerateSequenceInitialValue.InitialFunction -> oneArgumentIteratingFunction
            is GenerateSequenceInitialValue.NoInitialValue -> zeroArgumentIteratingFunction
        }

        return with(builder) {
            val stateVariable = scope.createTemporaryVariable(
                initialExpression,
                isMutable = true,
                irType = source.sequenceElementType.makeNullable(),
                nameHint = "generateSequenceState",
                origin = IrDeclarationOrigin.FOR_LOOP_ITERATOR
            )
            val loop = irWhile()

            val loopCondition = irNotEquals(irGet(stateVariable), irNull())
            val bodyBuilder = { currentElementVar: IrValueDeclaration ->
                irBlock {
                    val shouldContinueVar = irTemporary(consumerBodyBuilder(currentElementVar), nameHint = "shouldContinue")
                    +irIfThen(context.irBuiltIns.unitType, irNot(irGet(shouldContinueVar)), irBreak(loop))
                }
            }
            val bodyWithTransformers =
                addTransformerReplacements(builderWithParent, bodyBuilder, sequenceData, irGet(stateVariable), loop, null)

            loop.apply {
                origin = IrStatementOrigin.WHILE_LOOP
                condition = loopCondition

                body = irBlock {
                    +bodyWithTransformers
                    +irSet(stateVariable, evaluateNext(stateVariable))
                }
            }
            irBlock {
                +stateVariable
                +initialDeclarations
                +loop
                +finalExpression
            }
        }
    }
}
