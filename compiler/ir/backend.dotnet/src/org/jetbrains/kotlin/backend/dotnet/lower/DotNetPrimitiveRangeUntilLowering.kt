/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom

/** Redirects surviving signed primitive `rangeUntil` builtins to authoritative Common `until`. */
internal class DotNetPrimitiveRangeUntilLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {
    private val primitiveOwners = setOf(
        context.irBuiltIns.byteClass,
        context.irBuiltIns.shortClass,
        context.irBuiltIns.intClass,
        context.irBuiltIns.longClass,
        context.irBuiltIns.charClass,
    )

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val function = expression.symbol.owner
                if (
                    function.name != OperatorNameConventions.RANGE_UNTIL ||
                    (function.parent as? IrClass)?.symbol !in primitiveOwners ||
                    function.parameters.size != 2 ||
                    expression.arguments.size != 2
                ) {
                    return expression
                }
                val replacement = context.symbols.signedRangeUntilFunctions[
                    function.parameters[0].type to function.parameters[1].type
                ] ?: dotNetUnsupported(
                    "no authoritative Common until overload for ${function.parameters[0].type} and " +
                            function.parameters[1].type
                )
                return IrCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    replacement,
                    expression.typeArguments.size,
                    expression.origin,
                ).apply {
                    copyTypeArgumentsFrom(expression)
                    arguments.assignFrom(expression.arguments)
                }
            }
        })
    }
}
