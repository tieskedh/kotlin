/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.IrBuilderWithParent
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getBaseTypeFromSequence
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBreak
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions

internal class UnknownVariableStrategy(
    val newIteratorTarget: IrExpression
) : ProducerStrategy() {

    override fun fuseConsumer(
        builderWithParent: IrBuilderWithParent,
        sequenceData: SequenceData,
        consumerBodyBuilder: ConsumerBodyBuilder,
        initialDeclarations: List<IrVariable>,
        finalExpression: IrExpression,
    ): IrContainerExpression? {
        val builder = builderWithParent.first
        val parent = builderWithParent.second

        val baseType = getBaseTypeFromSequence(newIteratorTarget) ?: return null
        val iteratorType = builder.context.irBuiltIns.iteratorClass.typeWith(baseType)

        return builder.irBlock {
            val iteratorCall = builder.buildCallWithReceiver(newIteratorTarget, newIteratorTarget.type, "iterator", parent)
                ?: return null
            val iteratorDeclaration = scope.createTemporaryVariable(
                iteratorCall,
                isMutable = false,
                nameHint = "replacementIterator",
                irType = iteratorType,
            ).apply { markAsSynthetic() }

            +iteratorDeclaration
            +initialDeclarations

            val loopCondition = builder.buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, "hasNext", parent)
                ?: return null
            val loop = irWhile()
            val nextCall = builder.buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, "next", parent)!!
            val outerLoopVariable = scope.createTemporaryVariable(
                nextCall,
                isMutable = false,
                nameHint = "outerLoopVariable",
                irType = baseType,
            ).apply { markAsSynthetic() }

            val bodyBuilder = { currentElementVar: IrValueDeclaration ->
                irBlock {
                    val shouldContinueVar = irTemporary(consumerBodyBuilder(currentElementVar), nameHint = "shouldContinue")
                    +irIfThen(builder.context.irBuiltIns.unitType, irNot(irGet(shouldContinueVar)), irBreak(loop))
                }
            }

            val bodyWithTransformers = addTransformerReplacements(
                builderWithParent,
                bodyBuilder,
                sequenceData,
                irGet(outerLoopVariable),
                loop,
                null
            )
            loop.apply {
                origin = IrStatementOrigin.WHILE_LOOP
                condition = loopCondition

                body = irBlock {
                    +outerLoopVariable
                    +bodyWithTransformers
                }
            }
            +loop
            +finalExpression
        }
    }

    private fun IrBuilderWithScope.buildCallWithReceiver(
        receiver: IrExpression,
        receiverType: IrType,
        functionName: String,
        parent: IrDeclarationParent,
    ): IrCall? {
        val receiverCopy = receiver.deepCopyWithSymbols(parent)
        val function = receiverType.getClass()?.functions?.singleOrNull { function ->
            function.name.asString() == functionName && function.parameters.size == 1
        } ?: return null
        return irCall(function.symbol).apply {
            arguments[0] = receiverCopy
        }
    }
}
