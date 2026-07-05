package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetFlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetForLoopLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStringConcatenationLowering
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal val dotNetLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // For-loops first: the rewrite produces plain calls/whens the later phases treat like any
    // other code (string concatenations inside loop bodies are still ahead of their lowerings).
    ::DotNetForLoopLowering,
    // The DotNet subclass keeps floating-point constants unfolded; see
    // DotNetFlattenStringConcatenationLowering for the CLR rendering reason.
    ::DotNetFlattenStringConcatenationLowering,
    ::DotNetStringConcatenationLowering,
)

internal object DotNetLoweringPhases {
    fun lower(irModuleFragment: IrModuleFragment, context: DotNetBackendContext) {
        val phaseConfig = context.configuration.phaseConfig ?: PhaseConfig()
        val engine = PhaseEngine(phaseConfig, PhaserState(), context)
        for (lowering in dotNetLowerings) {
            engine.runPhase(lowering, irModuleFragment)
        }
    }
}
