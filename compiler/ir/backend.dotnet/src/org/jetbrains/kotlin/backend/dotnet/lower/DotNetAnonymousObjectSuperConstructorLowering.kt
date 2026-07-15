/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlock
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.transformInPlace
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom

internal val DOTNET_OBJECT_SUPER_CONSTRUCTOR_PARAMETER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_OBJECT_SUPER_CONSTRUCTOR_PARAMETER")

/**
 * Moves anonymous-object super-constructor argument evaluation to the object-expression call site.
 * This is the CLR counterpart of the JVM lowering: the object constructor receives the already
 * evaluated values as ordinary parameters, preserving source evaluation order when later closure
 * conversion adds captured values to the same constructor (anonprobe_s2).
 */
internal class DotNetAnonymousObjectSuperConstructorLowering(
    private val context: DotNetBackendContext,
) : IrElementTransformerVoidWithContext(), FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid()
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        if (expression.origin != IrStatementOrigin.OBJECT_LITERAL) return super.visitBlock(expression)

        val objectConstructorCall = expression.statements.last() as? IrConstructorCall
            ?: error("Object literal does not end in a constructor call")
        val objectConstructor = objectConstructorCall.symbol.owner
        val objectConstructorBody = objectConstructor.body as? IrBlockBody
            ?: error("Object literal constructor body is not a block")

        val newArguments = mutableListOf<IrExpression>()
        fun addArgument(value: IrExpression): IrValueParameter {
            newArguments += value
            return objectConstructor.addValueParameter(
                "\$super_call_param\$${newArguments.size}",
                value.type,
                DOTNET_OBJECT_SUPER_CONSTRUCTOR_PARAMETER,
            )
        }

        fun IrExpression.replaceComplexValue(remapping: Map<IrVariable, IrValueParameter>): IrExpression =
            when (this) {
                is IrConst -> this
                is IrGetValue -> IrGetValueImpl(startOffset, endOffset, remapping[symbol.owner]?.symbol ?: symbol)
                is IrTypeOperatorCall -> IrTypeOperatorCallImpl(
                    startOffset,
                    endOffset,
                    type,
                    operator,
                    typeOperand,
                    argument.replaceComplexValue(remapping),
                )
                else -> IrGetValueImpl(startOffset, endOffset, addArgument(this).symbol)
            }

        fun IrDelegatingConstructorCall.liftArguments(temporaries: List<IrVariable>) = apply {
            val remapping = temporaries.associateWith { addArgument(it.initializer!!) }
            for (parameter in symbol.owner.parameters) {
                arguments[parameter] = arguments[parameter]?.replaceComplexValue(remapping)
            }
        }

        objectConstructorBody.statements.transformInPlace { statement ->
            when {
                statement is IrDelegatingConstructorCall -> statement.liftArguments(emptyList())
                statement is IrBlock &&
                        statement.origin == IrStatementOrigin.ARGUMENTS_REORDERING_FOR_CALL &&
                        statement.statements.last() is IrDelegatingConstructorCall -> {
                    val delegatingCall = statement.statements.last() as IrDelegatingConstructorCall
                    delegatingCall.liftArguments(statement.statements.filterIsInstance<IrVariable>())
                }
                else -> statement
            }
        }

        val classTypeParametersCount =
            objectConstructorCall.typeArguments.size - objectConstructorCall.symbol.owner.typeParameters.size
        context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).run {
            expression.statements[expression.statements.lastIndex] = irBlock(objectConstructorCall) {
                +IrConstructorCallImpl.fromSymbolOwner(
                    objectConstructorCall.startOffset,
                    objectConstructorCall.endOffset,
                    objectConstructorCall.type,
                    objectConstructorCall.symbol,
                    classTypeParametersCount,
                    objectConstructorCall.origin,
                ).apply {
                    arguments.assignFrom(objectConstructorCall.arguments)
                    arguments += newArguments.map { argument ->
                        irGet(irTemporary(argument.patchDeclarationParents(currentDeclarationParent)))
                    }
                }
            }
        }
        return super.visitBlock(expression)
    }
}
