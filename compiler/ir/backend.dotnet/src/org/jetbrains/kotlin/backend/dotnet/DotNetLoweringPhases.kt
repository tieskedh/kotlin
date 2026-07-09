package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetFlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetForLoopLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersCleanupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStringConcatenationLowering
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal val dotNetLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // Initializer merging first — a stated deviation from the JVM phase order for a CLR-neutral
    // reason: DotNetForLoopLowering is an IrBuildingTransformer whose builder only exists inside
    // functions (LowerUtils installs it in visitFunction), so a `for` loop inside an `init {}`
    // block must already have been inlined into a constructor before the loop rewrite runs.
    ::DotNetInitializersLowering,
    ::DotNetInitializersCleanupLowering,
    // Object singletons after the initializer merge — the object's private `.ctor` must be
    // merged/complete before the `.cctor` calls it — and before the static-initializer sweep,
    // so the synthesized singleton-field initializer (INSTANCE, or the companion field on the
    // enclosing class) exists when the sweep moves it into the owning class's `<clinit>`.
    // Matches the JVM phase order (the singleton passes run before StaticInitializersLowering).
    ::DotNetObjectClassLowering,
    // Top-level property initializers move into the synthetic per-file `<clinit>` (and static
    // class fields — the object INSTANCE, the companion field — into the per-class one) before the loop/concat
    // rewrites for the same reason the instance pair runs first: a `for` or a string
    // concatenation inside a top-level initializer must sit inside a real function body before
    // those function-scoped rewrites run.
    ::DotNetStaticInitializersLowering,
    // For-loops next: the rewrite produces plain calls/whens the later phases treat like any
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
