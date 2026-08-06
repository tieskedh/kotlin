/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Replaces the physical remainder of every reified inline declaration after all Kotlin call sites
 * have been substituted. This mirrors Native's `ReifiedFunctionLowering`: the embedded KLIB body
 * remains authoritative, while the CLR MethodDef is a deterministic, non-public throwing stub.
 */
internal class DotNetReifiedFunctionLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass, IrElementTransformerVoid() {
    override fun lower(irModule: IrModuleFragment) {
        irModule.transformChildrenVoid(this)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        if (
            declaration.isFakeOverride ||
            !declaration.isInline ||
            declaration.typeParameters.none { it.isReified } ||
            declaration.body == null
        ) return visitDeclaration(declaration)

        val throwUnsupportedOperationException = context.symbols.throwUnsupportedOperationException
        val builder = context.createIrBuilder(declaration.symbol).at(declaration)

        fun IrBuilderWithScope.throwUnsupportedReifiedCall(): IrExpression =
            irCall(throwUnsupportedOperationException.owner).apply {
                arguments[0] =
                    "unsupported call of reified inline function `${declaration.fqNameForIrSerialization}`"
                        .toIrConst(this@DotNetReifiedFunctionLowering.context.irBuiltIns.stringType)
            }

        declaration.body = builder.irBlockBody {
            +builder.throwUnsupportedReifiedCall()
        }
        for (parameter in declaration.parameters) {
            if (parameter.defaultValue != null) {
                parameter.defaultValue = builder.irExprBody(builder.throwUnsupportedReifiedCall())
            }
        }
        return declaration
    }
}
