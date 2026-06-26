/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.IrBuilderWithParent
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.SequenceData
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getBaseTypeFromSequenceScopeFunction
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irReturnableBlock
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name

private class YieldReplacer(
    val builderWithParent: IrBuilderWithParent,
    val consumeFunction: IrFunction,
    val returnableBlock: IrReturnableBlock,
) : IrElementTransformerVoid() {
    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.symbol.owner.name.asString() != "yield") return super.visitCall(expression)
        val yieldArgument = expression.arguments.getOrNull(1) ?: return super.visitCall(expression)
        // TODO: yieldAll can have one argument, or a vararg
        return yieldReplacement(builderWithParent, yieldArgument, consumeFunction)
    }

    private fun yieldReplacement(
        builderWithParent: IrBuilderWithParent,
        argument: IrExpression,
        consumeFunction: IrFunction,
    ): IrExpression {
        return with(builderWithParent.first) {
            val consumeCall = irCall(consumeFunction.symbol, type = context.irBuiltIns.booleanType).apply {
                arguments[0] = argument.deepCopyWithSymbols(builderWithParent.second)
            }
            val notConsumed = irNot(consumeCall)
            val returnStatement = irReturnUnit().apply {
                returnTargetSymbol = returnableBlock.symbol
            }
            irIfThen(context.irBuiltIns.unitType, notConsumed, returnStatement)
        }
    }

    private fun yieldAllReplacement() {
        // REFACTOR REST FIRST
    }
}

internal class SequenceConstructorStrategy(
    val sequenceScope: IrRichFunctionReference,
    val context: JvmBackendContext,
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

        val localConsumerFunction = buildLocalConsumerFunction(builder, parent, consumerBodyBuilder)

        val baseType = getBaseTypeFromSequenceScopeFunction(sequenceScope) ?: return null

        val innerBlock = builder.irReturnableBlock(context.irBuiltIns.unitType) {
            +sequenceScope.invokeFunction.body!!.deepCopyWithSymbols(parent).statements
        }
        innerBlock.statements.add(builder.irReturnUnit().apply { this.returnTargetSymbol = innerBlock.symbol })
        val outerBlock = builder.irBlock(resultType = baseType) {
            +initialDeclarations
            +localConsumerFunction
            +innerBlock
            +finalExpression
        }

        val yieldReplacer = YieldReplacer(
            builderWithParent,
            localConsumerFunction,
            innerBlock,
        )

        outerBlock.transformChildren(yieldReplacer, null)
        return outerBlock
    }

    private fun buildLocalConsumerFunction(
        builder: IrBuilderWithScope,
        parent: IrDeclarationParent,
        consumerBodyBuilder: (IrValueDeclaration) -> IrExpression
    ): IrFunction {
        val consumerTarget = context.irFactory.buildFun {
            name = Name.identifier("sequenceYieldConsumer")
            returnType = context.irBuiltIns.booleanType
            visibility = DescriptorVisibilities.LOCAL
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }.apply {
            this.parent = parent
        }

        val baseType = getBaseTypeFromSequenceScopeFunction(sequenceScope) ?: return consumerTarget
        val elementParam = consumerTarget.addValueParameter("element", baseType)

        consumerTarget.body = builder.irBlockBody {
            +irReturn(
                consumerBodyBuilder(elementParam)
            ).apply {
                this.returnTargetSymbol = consumerTarget.symbol
            }
        }

        return consumerTarget
    }
}
