/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower.coroutines

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrContinueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

/** Ordinary IR construction used only by the explicit .NET coroutine state-machine lowering. */
internal object DotNetCoroutineOrigins {
    val SYNTHESIZED_STATEMENT = IrStatementOriginImpl("DOTNET_COROUTINE_SYNTHESIZED")
    val STATEMENT_ORIGIN_COROUTINE_IMPL = IrStatementOriginImpl("DOTNET_COROUTINE_IMPL")
    val COROUTINE_SWITCH = IrStatementOriginImpl("DOTNET_COROUTINE_SWITCH")
    val COROUTINE_ROOT_LOOP = IrStatementOriginImpl("DOTNET_COROUTINE_ROOT_LOOP")
}

internal object DotNetCoroutineIrBuilder {
    private val synthesizedDeclaration by IrDeclarationOriginImpl.Regular

    fun buildCall(
        target: IrSimpleFunctionSymbol,
        type: IrType? = null,
        typeArguments: List<IrType>? = null,
        origin: IrStatementOrigin = DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
        superQualifierSymbol: IrClassSymbol? = null,
        startOffset: Int = UNDEFINED_OFFSET,
        endOffset: Int = UNDEFINED_OFFSET,
    ): IrCall {
        val owner = target.owner
        return IrCallImpl(
            startOffset,
            endOffset,
            type ?: owner.returnType,
            target,
            superQualifierSymbol = superQualifierSymbol,
            typeArgumentsCount = owner.typeParameters.size,
            origin = origin,
        ).apply {
            typeArguments?.forEachIndexed { index, argument -> this.typeArguments[index] = argument }
        }
    }

    fun buildReturn(target: IrFunctionSymbol, value: IrExpression, type: IrType) =
        IrReturnImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, target, value)

    fun buildThrow(type: IrType, value: IrExpression) =
        IrThrowImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, value)

    fun buildGetObjectValue(type: IrType, classSymbol: IrClassSymbol) =
        IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, classSymbol)

    fun buildGetValue(
        symbol: IrValueSymbol,
        origin: IrStatementOrigin = DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
    ) = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol.owner.type, symbol, origin)

    fun buildSetValue(
        symbol: IrValueSymbol,
        value: IrExpression,
        startOffset: Int = UNDEFINED_OFFSET,
        endOffset: Int = UNDEFINED_OFFSET,
    ) = IrSetValueImpl(
        startOffset,
        endOffset,
        symbol.owner.type,
        symbol,
        value,
        DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
    )

    fun buildSetVariable(symbol: IrVariableSymbol, value: IrExpression, type: IrType) =
        IrSetValueImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            symbol,
            value,
            DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
        )

    fun buildBlock(type: IrType, statements: List<IrStatement> = emptyList()) =
        IrBlockImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
            statements,
        )

    fun buildComposite(type: IrType, statements: List<IrStatement> = emptyList()) =
        IrCompositeImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
            statements,
        )

    fun buildVar(
        type: IrType,
        parent: IrDeclarationParent?,
        name: String = "tmp",
        isVar: Boolean = false,
        initializer: IrExpression? = null,
        origin: IrDeclarationOrigin = synthesizedDeclaration,
    ): IrVariable = buildVariable(
        parent,
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        origin,
        Name.identifier(name),
        type,
        isVar,
        isConst = false,
        isLateinit = false,
    ).also { it.initializer = initializer }

    fun buildContinue(type: IrType, loop: IrLoop) =
        IrContinueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, loop)

    fun buildIfElse(
        type: IrType,
        condition: IrExpression,
        thenBranch: IrExpression,
        elseBranch: IrExpression? = null,
    ): IrWhen = IrWhenImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        type,
        DotNetCoroutineOrigins.SYNTHESIZED_STATEMENT,
    ).apply {
        branches += IrBranchImpl(thenBranch.startOffset, thenBranch.endOffset, condition, thenBranch)
        if (elseBranch != null) {
            branches += IrElseBranchImpl(
                elseBranch.startOffset,
                elseBranch.endOffset,
                IrConstImpl.constTrue(UNDEFINED_OFFSET, UNDEFINED_OFFSET, condition.type),
                elseBranch,
            )
        }
    }

    fun buildTypeOperator(type: IrType, operator: IrTypeOperator, argument: IrExpression, toType: IrType) =
        IrTypeOperatorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, operator, toType, argument)

    fun buildImplicitCast(value: IrExpression, toType: IrType) =
        buildTypeOperator(toType, IrTypeOperator.IMPLICIT_CAST, value, toType)

    fun buildBoolean(type: IrType, value: Boolean) =
        IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, value)

    fun buildInt(type: IrType, value: Int) =
        IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, value)

    fun buildCatch(exception: IrVariable, block: IrBlockImpl): IrCatch =
        IrCatchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, exception, block)
}
