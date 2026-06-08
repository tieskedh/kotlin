/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.strategies

import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.IrBuilderWithParent
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceData
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin.Companion.FOR_LOOP_INNER_WHILE
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions

private const val ITERATOR = "iterator"
private const val HAS_NEXT = "hasNext"
private const val NEXT = "next"

/**
 * We cannot fuse if we iterate over some transformation of a variable, for example,
 * ```
 * fun myFun(sequence: Sequence<Int>) {
 *     val seq2 = sequence.map { it * 2 }
 *     for (el in seq2.map { it + 1 }) {
 *         println(el)
 *     }
 * }
 * ```
 * cannot be lowered, because there is no way of applying { it * 2 } before { it + 1 } without changing the declaration of seq2.
 * But
 * ```
 * fun myFun(sequence: Sequence<Int>) {
 *     val seq2 = sequence.map { it * 2 }
 *     for (el in seq2) {
 *         println(el)
 *     }
 * }
 * ```
 * can be lowered into
 * ```
 * fun myFun(sequence: Sequence<Int>) {
 *     val seq2 = sequence.map { it * 2 }
 *     for (el in seq) {
 *         println({ it * 2 }(el))
 *     }
 * }
 * ```
 * */
internal class UnknownVariableStrategy(val newIteratorTarget: IrExpression) : LoweringStrategy() {
    override fun lowerLoop(
        builderWithParent: IrBuilderWithParent,
        loopBody: (IrVariable) -> IrContainerExpression,
        sequenceData: SequenceData,
        newLoop: IrLoop,
        loopVariable: IrVariable?,
    ): IrContainerExpression? {
        val bodyCreator = { iteratorReplacement: IteratorReplacement ->
            val builder = builderWithParent.first
            val newBody = builder.irBlock {
                +iteratorReplacement.iteratorNextStatement
                +addReplacementsToBody(
                    builderWithParent,
                    loopBody,
                    sequenceData,
                    irGet(iteratorReplacement.outerLoopVariable),
                    FOR_LOOP_INNER_WHILE,
                    newLoop,
                    loopVariable,
                )
            }
            createLoweredLoop(
                iteratorReplacement.iteratorVariable,
                iteratorReplacement.outerLoopVariable,
                iteratorReplacement.condition,
                builder,
                newBody,
                sequenceData,
                newLoop
            )
        }
        return lowerBody(builderWithParent, bodyCreator)
    }

    private fun lowerBody(
        builderWithParent: IrBuilderWithParent,
        bodyCreator: (IteratorReplacement) -> IrContainerExpression
    ): IrContainerExpression? {
        val iteratorReplacement = createIteratorReplacement(builderWithParent) ?: return null
        return bodyCreator(iteratorReplacement)
    }

    override fun createIteratorReplacement(builderWithParent: IrBuilderWithParent): IteratorReplacement? {
        with(builderWithParent.first) {
            val parent = builderWithParent.second
            val baseType = (newIteratorTarget.type as? IrSimpleType)?.arguments?.getOrNull(0)?.typeOrNull ?: return null
            val iteratorType = context.irBuiltIns.iteratorClass.typeWith(baseType)
            val iteratorCall = buildCallWithReceiver(newIteratorTarget, newIteratorTarget.type, ITERATOR, parent) ?: return null
            val iteratorDeclaration = scope.createTemporaryVariable(
                iteratorCall,
                isMutable = true,
                nameHint = "replacementIterator",
                inventUniqueName = true,
                irType = iteratorType,
            )
            val nextCall = buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, NEXT, parent) ?: return null
            val hasNextCall = buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, HAS_NEXT, parent) ?: return null
            val outerLoopVariable = scope.createTemporaryVariable(
                nextCall,
                isMutable = true,
                nameHint = "outerLoopVariable",
                inventUniqueName = true,
                irType = baseType,
            )
            iteratorDeclaration.markAsSynthetic()
            outerLoopVariable.markAsSynthetic()
            // we return unit as the next statement here, because the outer loop variable declaration already includes the next call
            return IteratorReplacement(iteratorDeclaration, outerLoopVariable, irUnit(), hasNextCall)
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
