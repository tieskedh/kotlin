/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.strategies.createSequenceWhile
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

internal class ForEachBodyReplacementCreator(
    val context: JvmBackendContext,
    val builder: IrBuilderWithScope,
    val parent: IrDeclarationParent,
    val sequenceData: SequenceData
) : ConsumerBodyReplacementCreator() {
    override fun create(expression: IrCall): IrExpression? {
        val function = expression.arguments.getOrNull(1) as? IrRichFunctionReference ?: return null
        val copiedFunction = function.deepCopyWithSymbols(parent)
        val strategy = sequenceData.sequenceSource.createStrategy(builder)
        val iteratorReplacement =
            strategy.createIteratorReplacement(builder to parent) ?: return null
        val newLoop = builder.createSequenceWhile()
        val newBody = builder.irBlock {
            +iteratorReplacement.iteratorNextStatement
            +strategy.addReplacementsToForEachCall(
                builder to parent,
                copiedFunction,
                sequenceData,
                builder.irGet(iteratorReplacement.outerLoopVariable),
                newLoop
            )
        }
        return strategy.createLoweredLoop(
            iteratorReplacement.iteratorVariable,
            iteratorReplacement.outerLoopVariable,
            iteratorReplacement.condition,
            builder,
            newBody,
            sequenceData,
            newLoop,
        )
    }
}
