package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.lower.ArrayConstructorLowering
import org.jetbrains.kotlin.backend.common.lower.KotlinNothingValueExceptionLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDelegatedPropertiesLowering
import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetAnonymousObjectSuperConstructorLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCallableReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCompanionStaticsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCompanionInitializationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultParameterCleaner
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultParameterInjector
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetFlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetForLoopLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetGenericDataClassLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersCleanupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInterfaceDefaultArgumentsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassTypeParametersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInventNamesForLocalClasses
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInventNamesForLocalFunctions
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetKFunctionInvokeLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetLocalDeclarationPopupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetLocalDeclarationsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetPropertyReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetPrivateNestedAccessLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetReturnableBlockLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetSharedVariablesLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticCallableReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetUpgradeCallableReferences
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetVarargLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetGenericInterfaceBridgeLowering
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

private class DotNetKotlinNothingValueExceptionLowering(context: DotNetBackendContext) :
    KotlinNothingValueExceptionLowering(context)

internal val dotNetLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // Normalize companion-block backing fields before any shared lowering can classify state.
    // The receiver-free accessor pair is the FIR2IR semantic marker; later phases may synthesize
    // additional static functions which are not companion declarations.
    ::DotNetCompanionStaticsLowering,
    // Common/JVM local-class closure conversion: invent collision-resistant CLR names, move
    // anonymous-super arguments to the call site, make immutable value/type captures explicit,
    // then move only transformed declarations to the nearest metadata container. This precedes
    // inner classes and initializer merging, as on the JVM (localprobe_s1/s2, anonprobe_s1/s2).
    ::DotNetUpgradeCallableReferences,
    // Native/Wasm precedent: split KProperty values into a runtime wrapper around rich getter
    // and optional setter references while their bound values can still be shared exactly once.
    // The following callable lowering turns those references into the established FunctionN
    // objects; the wrapper does not create another callable execution identity.
    ::DotNetPropertyReferenceLowering,
    // Native/Wasm/JS precedent: after local property tokens have become dedicated name-only
    // wrappers, flatten IrLocalDelegatedProperty into its ordinary getter/setter/delegate
    // declarations before local closure conversion sees those accessors.
    ::LocalDelegatedPropertiesLowering,
    // Match the mature backends before closure conversion and default stubs: normalize concrete
    // vararg parameters to their vector ABI, materialize omitted arguments, and lower spread
    // copies to ordinary array operations. Open `vararg T` keeps its unsupported projection.
    ::DotNetVarargLowering,
    // Reuse the common JVM/JS/Wasm/Native fill-loop shape while rich direct lambdas and callable
    // references can still be inlined. Non-direct initializers retain the erased Function1 ABI.
    ::ArrayConstructorLowering,
    ::DotNetReturnableBlockLowering,
    ::DotNetInventNamesForLocalClasses,
    ::DotNetAnonymousObjectSuperConstructorLowering,
    ::DotNetCallableReferenceLowering,
    ::DotNetKFunctionInvokeLowering,
    // JVM/common ordering: mutable locals become shared reference cells before closure conversion
    // captures the cell object in local classes and generated callable classes.
    ::DotNetSharedVariablesLowering,
    ::DotNetInventNamesForLocalFunctions,
    ::DotNetLocalDeclarationsLowering,
    ::DotNetLocalDeclarationPopupLowering,
    // JVM/common masked-default dispatch after local declarations are lifted: generated
    // `$default` functions see final metadata owners, and every call with omitted ordinary
    // function or constructor arguments is redirected before later lowerings inspect its body.
    // Constructor stubs carry the runtime-owned collision marker used by the JVM-shaped ABI.
    ::DotNetDefaultArgumentStubGenerator,
    ::DotNetDefaultParameterInjector,
    ::DotNetDefaultParameterCleaner,
    // JVM DefaultImpls ownership without CLR DIM: keep interface slots abstract, move their
    // masked dispatchers into a compiler-reserved nested helper, and redirect calls to its static
    // methods with the interface receiver explicit. This preserves the Framework 4.8 floor.
    ::DotNetInterfaceDefaultArgumentsLowering,
    // Apply the erased-identity/typed-member split uniformly to module, library, and runtime-owned
    // generic interfaces, including Iterator/Iterable. Every implementation receives its
    // same-object canonical and typed bridges from this one lowering. Invariant declarations are
    // included because Kotlin use-site projections and stars also change the logical view without
    // changing object identity.
    // Imported CLR interfaces never enter this lowering and retain their native variance rules.
    ::DotNetGenericInterfaceBridgeLowering,
    // CLR generics reify C<T>, unlike the erased class identity used by generated data-class
    // equality on the mature targets. Preserve reified storage/signatures, but give each generic
    // data class a private non-generic equality view before later lowerings inspect its members.
    ::DotNetGenericDataClassLowering,
    // Follow the common/JVM inner-class pipeline before initializer merging: first make a generic
    // outer's implicit type arguments explicit on the independent CLR nested type, then add the
    // outer field/constructor argument, rewrite outer-this reads into field chains, and move
    // constructor-call dispatch receivers into the new leading argument. The CLR accepts the
    // common pre-base-call outer-field store unchanged (innerprobe_s1/s2, genericinner_s1-s3).
    ::DotNetInnerClassTypeParametersLowering,
    ::DotNetInnerClassesLowering,
    ::DotNetInnerClassesMemberBodyLowering,
    ::DotNetInnerClassConstructorCallsLowering,
    // Initializer merging first — a stated deviation from the JVM phase order for a CLR-neutral
    // reason: DotNetForLoopLowering is an IrBuildingTransformer whose builder only exists inside
    // functions (LowerUtils installs it in visitFunction), so a `for` loop inside an `init {}`
    // block must already have been inlined into a constructor before the loop rewrite runs.
    ::DotNetInitializersLowering,
    ::DotNetInitializersCleanupLowering,
    // Object and callable singletons after initializer cleanup — each private `.ctor` must be
    // merged/complete before a `.cctor` calls it, and cleanup nulls pre-existing field
    // initializers indiscriminately. Create both singleton fields only now, immediately before
    // the static-initializer sweep moves them into their owning classes' `<clinit>` functions.
    // This matches the JVM shape: singleton caching precedes StaticInitializersLowering.
    ::DotNetObjectClassLowering,
    // Cache non-capturing callable/reference classes before private-access repair: these caches
    // are singleton fields too, and a callable class nested under its lexical owner is CLR-private.
    ::DotNetStaticCallableReferenceLowering,
    // CLR nesting permits nested→enclosing private access but not the reverse. Keep companion
    // declarations and private nested-object singleton fields private, then redirect CLR-illegal
    // enclosing→nested calls/reads through assembly-visible common-shaped synthetic accessors.
    // This includes the singleton constructor call and field reads introduced by both singleton
    // lowerings immediately above.
    ::DotNetPrivateNestedAccessLowering,
    // Top-level property initializers move into the synthetic per-file `<clinit>` (and static
    // class fields — object INSTANCE and companion fields at any supported nesting depth — into
    // the owning class's `<clinit>`) before the loop/concat rewrites for the same reason the
    // instance pair runs first: a `for` or a string concatenation inside an initializer must sit
    // inside a real function body before those function-scoped rewrites run.
    ::DotNetStaticInitializersLowering,
    // Kotlin companion initialization is a classifier graph, while CLR type initialization is
    // physical-owner based. After every own initializer has become a real `.cctor`, prepend the
    // selected superclass/default-bearing-interface edges and publish one stable entry per
    // participating classifier. Generic owners enter their non-generic static holder.
    ::DotNetCompanionInitializationLowering,
    // For-loops next: the rewrite produces plain calls/whens the later phases treat like any
    // other code (string concatenations inside loop bodies are still ahead of their lowerings).
    ::DotNetForLoopLowering,
    // The DotNet subclass keeps floating-point constants unfolded; see
    // DotNetFlattenStringConcatenationLowering for the CLR rendering reason.
    ::DotNetFlattenStringConcatenationLowering,
    ::DotNetStringConcatenationLowering,
    // JVM/JS/Wasm/Native invariant: if a call statically returning Nothing somehow returns
    // (for example from foreign CLR code), throw the dedicated Kotlin exception immediately.
    // Run last so calls introduced by every earlier bridge/helper lowering receive the guard.
    ::DotNetKotlinNothingValueExceptionLowering,
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
