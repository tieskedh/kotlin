/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

/** Routes reflective callable invocation through the unchanged erased FunctionN execution slot. */
internal class DotNetKFunctionInvokeLowering(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : IrVisitorVoid(), FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.acceptChildrenVoid(this)
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitCall(expression: IrCall) {
        expression.acceptChildren(this, null)

        val callee = expression.symbol.owner
        if (callee.name != OperatorNameConventions.INVOKE) return

        val parentClass = callee.parent as? IrClass ?: return
        if (!parentClass.defaultType.isKFunction() && !parentClass.defaultType.isKSuspendFunction()) {
            castKFunctionReceiverToFunction(expression, parentClass)
            return
        }

        expression.symbol = callee.overriddenSymbols.single()
        expression.dispatchReceiver = expression.dispatchReceiver?.let { receiver ->
            val functionType = executionType(expression.symbol.owner.parentAsClass, receiver)
            IrTypeOperatorCallImpl(
                expression.startOffset,
                expression.endOffset,
                functionType,
                IrTypeOperator.IMPLICIT_CAST,
                functionType,
                receiver,
            )
        }
    }

    private fun castKFunctionReceiverToFunction(expression: IrCall, parentClass: IrClass) {
        val receiver = expression.dispatchReceiver ?: return
        if (!receiver.type.isKFunction() && !receiver.type.isKSuspendFunction()) return
        val functionType = executionType(parentClass, receiver)
        expression.dispatchReceiver = IrTypeOperatorCallImpl(
            expression.startOffset,
            expression.endOffset,
            functionType,
            IrTypeOperator.IMPLICIT_CAST,
            functionType,
            receiver,
        )
    }

    /** Keeps KFunctionN's logical P.../R arguments on its FunctionN execution view. */
    private fun executionType(functionClass: IrClass, receiver: IrExpression) =
        (receiver.type as? IrSimpleType)
            ?.arguments
            ?.takeIf { arguments -> arguments.size == functionClass.typeParameters.size }
            ?.let(functionClass.symbol::typeWithArguments)
            ?: functionClass.defaultType
}
