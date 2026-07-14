package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.hasShape

internal class DotNetMainFunctionDetector {
    /**
     * Returns every top-level `main` candidate in the module so the caller can distinguish
     * the no-main, single-main, and ambiguous-main cases.
     */
    fun getMainFunctions(module: IrModuleFragment): List<IrSimpleFunction> =
        module.files.flatMap { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>().filter { it.isDotNetMainCandidate() }
        }

    private fun IrSimpleFunction.isDotNetMainCandidate(): Boolean {
        return parent is IrFile &&
                name.asString() == "main" &&
                typeParameters.isEmpty() &&
                returnType.isUnit() &&
                hasDotNetEntryPointParameters()
    }

    /** ECMA-335 entry points admit either no parameters or one `string[]` parameter. */
    private fun IrSimpleFunction.hasDotNetEntryPointParameters(): Boolean {
        if (hasShape(regularParameters = 0)) return true
        if (!hasShape(regularParameters = 1)) return false
        val parameter = parameters.singleOrNull { it.kind == IrParameterKind.Regular } ?: return false
        return !parameter.type.isMarkedNullable() &&
                parameter.type.dotNetInvariantArrayElementTypeOrNull()?.isString() == true
    }
}
