/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.lower.SingleAbstractMethodLowering
import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.render

/** Reuses Common's Kotlin fun-interface wrapper model over the ordinary .NET class pipeline. */
internal class DotNetSingleAbstractMethodLowering(context: DotNetBackendContext) :
    SingleAbstractMethodLowering(context) {

    // .NET serializes authoritative inline bodies to KLIB before target lowering. A consumer
    // therefore materializes its own private wrapper after inlining; the producer's physical
    // remainder never needs a second public wrapper ABI. Reuse the ordinary per-file cache so a
    // file containing both inline and non-inline conversions cannot create duplicate private
    // TypeDefs with Common's otherwise identical wrapper name.
    override val inInlineFunctionScope: Boolean
        get() = false

    override fun getWrapperVisibility(
        expression: IrTypeOperatorCall,
        scopes: List<ScopeWithIr>,
    ) = DescriptorVisibilities.INTERNAL

    override fun getSuperTypeForWrapper(typeOperand: IrType): IrType =
        typeOperand.classOrNull?.defaultType
            ?: error("Unsupported SAM conversion: ${typeOperand.render()}")

    override val IrType.needEqualsHashCodeMethods: Boolean
        get() = true

    override fun getSuspendFunctionWithoutContinuation(function: IrSimpleFunction): IrSimpleFunction =
        function.suspendFunction ?: function
}
