package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.types.isStringClassType
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Replaces high-level [IrStringConcatenation] nodes with Kotlin String operations
 * that the .NET emitter handles as CLR string intrinsics.
 */
internal class DotNetStringConcatenationLowering(
    context: DotNetBackendContext,
) : FileLoweringPass, IrBuildingTransformer(context) {
    private val symbols = context.symbols

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation): IrExpression {
        expression.transformChildrenVoid(this)

        builder.at(expression)
        val arguments = expression.arguments
        return when {
            arguments.isEmpty() -> builder.irString("")
            else -> {
                var concatenatedString = arguments.first().coerceToStringForConcatenation()
                for (argument in arguments.drop(1)) {
                    concatenatedString = builder.irCall(symbols.memberStringPlus).apply {
                        this.arguments[0] = concatenatedString
                        this.arguments[1] = argument
                    }
                }
                concatenatedString
            }
        }
    }

    private fun IrExpression.coerceToStringForConcatenation(): IrExpression {
        if (type.isStringClassType() && !type.isNullable()) return this

        val toStringFunction =
            if (type.isNullable()) symbols.extensionToString
            else symbols.memberToString

        return builder.irCall(toStringFunction).apply {
            arguments[0] = this@coerceToStringForConcatenation
        }
    }
}
