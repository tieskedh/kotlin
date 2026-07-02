/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.transformers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.IrBuilderWithParent
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceReplacement
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceTransformer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irReturnFalse
import org.jetbrains.kotlin.ir.builders.irReturnTrue
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock

internal abstract class TransformerReplacementCreator {
    abstract fun addTransformerToBodyBuilder(
        sequenceReplacement: SequenceReplacement,
        shouldCheckShortCircuit: Boolean,
        builderWithParent: IrBuilderWithParent,
    ): SequenceReplacement

    abstract val doesShortCircuit: Boolean

    companion object {
        fun create(sequenceTransformer: SequenceTransformer): TransformerReplacementCreator = when (sequenceTransformer) {
            is SequenceTransformer.Map -> MapReplacementCreator(sequenceTransformer)
            is SequenceTransformer.Filter -> FilterReplacementCreator(sequenceTransformer)
            is SequenceTransformer.Take -> TakeReplacementCreator(sequenceTransformer)
        }
    }

    internal fun addShortCircuitCheck(
        sequenceReplacement: SequenceReplacement,
        shouldCheckShortCircuit: Boolean,
        block: IrReturnableBlock,
        sequenceVariable: IrValueDeclaration,
        builder: IrBuilderWithScope
    ): IrReturnableBlock = with(builder) {
        val innerBody = sequenceReplacement.mainBodyBuilder(sequenceVariable)
        if (shouldCheckShortCircuit) {
            block.statements.add(
                irIfThen(
                    context.irBuiltIns.unitType,
                    irNot(innerBody),
                    irReturnFalse().apply { this.returnTargetSymbol = block.symbol })
            )
        } else {
            block.statements.add(innerBody)
        }
        block.statements.add(irReturnTrue().apply { this.returnTargetSymbol = block.symbol })
        return block
    }
}
