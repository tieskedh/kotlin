package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetAnonymousObjectSuperConstructorLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetFlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetForLoopLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersCleanupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInventNamesForLocalClasses
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetLocalDeclarationPopupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetLocalDeclarationsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStringConcatenationLowering
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal val dotNetLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // Common/JVM local-class closure conversion: invent collision-resistant CLR names, move
    // anonymous-super arguments to the call site, make immutable value/type captures explicit,
    // then move only transformed declarations to the nearest metadata container. This precedes
    // inner classes and initializer merging, as on the JVM (localprobe_s1/s2, anonprobe_s1/s2).
    ::DotNetInventNamesForLocalClasses,
    ::DotNetAnonymousObjectSuperConstructorLowering,
    ::DotNetLocalDeclarationsLowering,
    ::DotNetLocalDeclarationPopupLowering,
    // Follow the common/JVM inner-class pipeline before initializer merging: add the explicit
    // outer field/constructor argument, rewrite outer-this reads into field chains, then move
    // constructor-call dispatch receivers into the new leading argument. The CLR accepts the
    // common pre-base-call outer-field store unchanged (innerprobe_s1/s2).
    ::DotNetInnerClassesLowering,
    ::DotNetInnerClassesMemberBodyLowering,
    ::DotNetInnerClassConstructorCallsLowering,
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
    // class fields — object INSTANCE and companion fields at any supported nesting depth — into
    // the owning class's `<clinit>`) before the loop/concat rewrites for the same reason the
    // instance pair runs first: a `for` or a string concatenation inside an initializer must sit
    // inside a real function body before those function-scoped rewrites run.
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
