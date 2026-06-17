package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.lower.FlattenStringConcatenationLowering
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal object DotNetLoweringPhases {
    fun lower(irModuleFragment: IrModuleFragment, context: DotNetBackendContext) {
        FlattenStringConcatenationLowering(context).lower(irModuleFragment)
    }
}
