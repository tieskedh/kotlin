package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.lower.ArrayConstructorLowering
import org.jetbrains.kotlin.backend.common.lower.KotlinNothingValueExceptionLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDelegatedPropertiesLowering
import org.jetbrains.kotlin.backend.common.lower.RedundantCastsRemoverLowering
import org.jetbrains.kotlin.backend.common.lower.RangeContainsLowering
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.lower.inline.InlineCallCycleCheckerLowering
import org.jetbrains.kotlin.backend.common.lower.inline.LocalClassesInInlineLambdasLowering
import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterInliningAllFunctionsOnTheSecondStagePhase
import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterInliningOnlyPrivateFunctionsPhase
import org.jetbrains.kotlin.backend.common.phaser.KlibIrValidationBeforeLoweringPhase
import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetAnonymousObjectSuperConstructorLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetAnnotationImplementationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCallableReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCompanionStaticsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializationFailureLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializationGraphLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetCovariantReturnBridgeLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultParameterCleaner
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetDefaultParameterInjector
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetEnumClassLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetEnumUsageLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetFlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersCleanupLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInterfaceDefaultArgumentsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassPhysicalizationLowering
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
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetPrimitiveRangeUntilLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetRenameFieldsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetReifiedFunctionLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetReturnableBlockLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetSharedVariablesLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticCallableReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetUpgradeCallableReferences
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetVarargLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetGenericInterfaceBridgeLowering
import org.jetbrains.kotlin.backend.dotnet.lower.inline.DotNetAllFunctionInlining
import org.jetbrains.kotlin.backend.dotnet.lower.inline.DotNetPrivateFunctionInlining
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.inline.OuterThisInInlineFunctionsSpecialAccessorLowering
import org.jetbrains.kotlin.ir.inline.SyntheticAccessorLowering
import org.jetbrains.kotlin.ir.inline.isConsideredAsPrivateForInlining
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IrTreeSymbolsVisitor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.isTypeOfIntrinsic
import org.jetbrains.kotlin.ir.visitors.acceptVoid

private class DotNetKotlinNothingValueExceptionLowering(context: DotNetBackendContext) :
    KotlinNothingValueExceptionLowering(context)

private fun createSyntheticAccessorGenerationPhase(context: DotNetBackendContext): SyntheticAccessorLowering =
    SyntheticAccessorLowering(context)

private fun createValidateIrAfterInliningOnlyPrivateFunctionsPhase(
    context: DotNetBackendContext,
): IrValidationAfterInliningOnlyPrivateFunctionsPhase<DotNetBackendContext> =
    IrValidationAfterInliningOnlyPrivateFunctionsPhase(
        context,
        checkInlineFunctionCallSites = { useSite ->
            !useSite.symbol.isConsideredAsPrivateForInlining()
        },
    )

private fun createValidateIrAfterInliningAllFunctionsPhase(
    context: DotNetBackendContext,
): IrValidationAfterInliningAllFunctionsOnTheSecondStagePhase<DotNetBackendContext> =
    IrValidationAfterInliningAllFunctionsOnTheSecondStagePhase(
        context,
        checkInlineFunctionCallSites = { useSite ->
            val function = useSite.symbol.owner
            function.symbol.isTypeOfIntrinsic() ||
                    function.body == null
        },
    )

private val dotNetInlineLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // BEGIN: Common Native/JS/Wasm inline prefix. `lateinit` stays parked for the target, so its
    // shared lowering is intentionally absent until .NET owns the required throw-helper symbol.
    ::KlibIrValidationBeforeLoweringPhase,
    ::InlineCallCycleCheckerLowering,
    ::DotNetUpgradeCallableReferences,
    ::DotNetSharedVariablesLowering,
    ::LocalClassesInInlineLambdasLowering,
    ::ArrayConstructorLowering,
    ::DotNetPrivateFunctionInlining,
    ::OuterThisInInlineFunctionsSpecialAccessorLowering,
    ::createSyntheticAccessorGenerationPhase,
    ::createValidateIrAfterInliningOnlyPrivateFunctionsPhase,
    ::DotNetAllFunctionInlining,
    ::RedundantCastsRemoverLowering,
    ::createValidateIrAfterInliningAllFunctionsPhase,
    // END: Common Native/JS/Wasm inline prefix.
)

private val dotNetReifiedInlineCompletionLowerings:
        List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // The first KLIB stage deliberately preserves bodyless .NET reified intrinsics. Complete the
    // remaining Kotlin-owned reified substitutions after KLIB serialization without repeating
    // the non-idempotent shared prefix that already handled ordinary inline declarations.
    ::DotNetAllFunctionInlining,
    ::createValidateIrAfterInliningAllFunctionsPhase,
)

internal val dotNetLowerings: List<NamedCompilerPhase<DotNetBackendContext, IrModuleFragment, IrModuleFragment>> = createModulePhases(
    // Native precedent: a reified declaration is Kotlin compiler input, not an independently
    // callable host generic. Every Kotlin call has disappeared through the shared inliner above;
    // replace the physical remainder before any target lowering can mistake it for ordinary code.
    ::DotNetReifiedFunctionLowering,
    // JS/Wasm/Native precedent: the shared inliner has substituted T, so bind the three enum
    // intrinsics to the concrete enum's ordinary synthetic values/valueOf/entries declarations.
    ::DotNetEnumUsageLowering,
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
    // JS/Common precedent adapted to the CLR constraint: use the original parameterless marker
    // as its concrete System.Attribute implementation and generate Common value members before
    // varargs or constructor bodies are normalized. No synthetic second annotation identity.
    ::DotNetAnnotationImplementationLowering,
    // Match the mature backends before closure conversion and default stubs: normalize concrete
    // vararg parameters to their vector ABI, materialize omitted arguments, and lower spread
    // copies to ordinary array operations. Open `vararg T` becomes the CLR's truthful method-
    // generic `T[]` vector without changing Kotlin-owned class identity.
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
    // JVM constructor/entry lowering with a CLR reference-class endpoint. KLIB retains enum
    // identity; physical entry fields and ordinary synthetic bodies must exist before interface
    // bridges and static-initializer ownership inspect the final class graph.
    ::DotNetEnumClassLowering,
    // JVM DefaultImpls ownership without CLR DIM: keep interface slots abstract, move their
    // masked dispatchers into a compiler-reserved nested helper, and redirect calls to its static
    // methods with the interface receiver explicit. This preserves the Framework 4.8 floor.
    ::DotNetInterfaceDefaultArgumentsLowering,
    // Apply the erased-identity/typed-member split uniformly to module, library, and runtime-owned
    // generic interfaces, including Iterator/Iterable. Every implementation receives its
    // erased MethodImpl bridge from this one lowering. An independently truthful mapped host
    // capability, such as IComparable<T>, may receive an additional bridge without changing
    // Kotlin object identity.
    // Imported CLR interfaces never enter this lowering and retain their native variance rules.
    ::DotNetGenericInterfaceBridgeLowering,
    // CLR method-slot identity includes the return type on every supported profile. Preserve
    // Kotlin covariant overrides with one exact virtual implementation plus private final
    // MethodImpl adapters for each wider ordinary class/interface slot. Erased generic-interface
    // slots and explicit mapped host capabilities remain owned by the preceding lowering.
    ::DotNetCovariantReturnBridgeLowering,
    // Follow the common/JVM inner-class pipeline before initializer merging: first make a generic
    // outer's implicit type arguments explicit on the independent CLR nested type, then add the
    // outer field/constructor argument, rewrite outer-this reads into field chains, and move
    // constructor-call dispatch receivers into the new leading argument. The CLR accepts the
    // common pre-base-call outer-field store unchanged (innerprobe_s1/s2, genericinner_s1-s3).
    ::DotNetInnerClassTypeParametersLowering,
    ::DotNetInnerClassesLowering,
    ::DotNetInnerClassesMemberBodyLowering,
    ::DotNetInnerClassConstructorCallsLowering,
    // The common passes no longer need lexical `inner` identity. Outer state and type parameters
    // are now explicit CLR members/slots, so stop general IR substitution from counting both the
    // physical copies and the original enclosing type-constructor parameters.
    ::DotNetInnerClassPhysicalizationLowering,
    // Initializer merging first — a stated deviation from the JVM phase order for a CLR-neutral
    // reason: the shared ForLoopsLowering is a body pass, so a `for` loop inside an `init {}`
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
    ::DotNetStaticInitializationGraphLowering,
    // The JVM obtains first-Error identity, ExceptionInInitializerError, and later
    // NoClassDefFoundError from the VM. CLR would instead expose TypeInitializationException.
    // Catch Kotlin-owned `.cctor` failures into per-owner state, then guard every Kotlin
    // active-use site through the stable entry while leaving foreign CLR initializers alone.
    ::DotNetStaticInitializationFailureLowering,
    // For-loops next: the rewrite produces plain calls/whens the later phases treat like any
    // other code (string concatenations inside loop bodies are still ahead of their lowerings).
    // JVM, JS, Wasm, and Native first erase supported range-membership expressions into primitive
    // comparisons. Keep that shared order so generated Common bodies such as List.getOrNull do not
    // require a materialized IntRange or a target-specific source rewrite.
    ::RangeContainsLowering,
    ::ForLoopsLowering,
    // JS precedent: loop-only `..<` has already been consumed above; materialized signed
    // primitive ranges now redirect from the bodyless builtin member to Common `until`.
    ::DotNetPrimitiveRangeUntilLowering,
    // The DotNet subclass keeps floating-point constants unfolded; see
    // DotNetFlattenStringConcatenationLowering for the CLR rendering reason.
    ::DotNetFlattenStringConcatenationLowering,
    ::DotNetStringConcatenationLowering,
    // JVM/JS/Wasm/Native invariant: if a call statically returning Nothing somehow returns
    // (for example from foreign CLR code), throw the dedicated Kotlin exception immediately.
    // Run after every body-producing lowering so calls introduced by bridges/helpers receive it.
    ::DotNetKotlinNothingValueExceptionLowering,
    // JVM precedent: after every target lowering has created its physical fields, reserve
    // public/compiler-ABI names and deterministically suffix only later private storage. CLR
    // metadata can distinguish same-named fields by type, but C# and common reflection tooling
    // cannot author or consume that shape naturally, so type-distinct private fields are renamed
    // too. The emitter retains its field-identity gate for unrenamable public ABI collisions.
    ::DotNetRenameFieldsLowering,
)

internal object DotNetLoweringPhases {
    fun lower(irModuleFragment: IrModuleFragment, context: DotNetBackendContext) {
        val phaseConfig = context.configuration.phaseConfig ?: PhaseConfig()
        val engine = PhaseEngine(phaseConfig, PhaserState(), context)
        // Unlike the mature linking backends, a .NET library serializes and emits the same IR
        // module in one pipeline. The first-stage KLIB prefix has therefore already transformed
        // this module when the modern intra-module inliner is enabled; several of those phases
        // deliberately reject a second visit. The pre-serialization resolver also links selected
        // dependency bodies from the main graph, so no second prefix is required in that mode.
        if (!context.configuration.languageVersionSettings.supportsFeature(LanguageFeature.IrIntraModuleInlinerBeforeKlibSerialization)) {
            for (lowering in dotNetInlineLowerings) {
                engine.runPhase(lowering, irModuleFragment)
            }
        } else {
            for (lowering in dotNetReifiedInlineCompletionLowerings) {
                engine.runPhase(lowering, irModuleFragment)
            }
        }
        rejectIncompleteSelectedDependencyGraph(irModuleFragment)
        for (lowering in dotNetLowerings) {
            engine.runPhase(lowering, irModuleFragment)
        }
    }

    /**
     * Kotlin/.NET deliberately deserializes only inline bodies and does not run a whole-program IR
     * linker. Every public symbol reached from those bodies must therefore bind through the
     * frontend-selected library graph before target lowering starts. Mature linking backends can
     * defer this check until their linkage step; this backend has no such later owner.
     */
    private fun rejectIncompleteSelectedDependencyGraph(irModuleFragment: IrModuleFragment) {
        val unresolvedDeclarations = linkedSetOf<IrSymbol>()
        irModuleFragment.acceptVoid(object : IrTreeSymbolsVisitor() {
            override fun visitSymbol(container: IrElement, symbol: IrSymbol) {
                if (!symbol.isBound) unresolvedDeclarations += symbol
            }
        })
        if (unresolvedDeclarations.isEmpty()) return

        val renderedSignatures = unresolvedDeclarations
            .map { symbol -> symbol.signature?.render() ?: symbol.toString() }
            .sorted()
            .joinToString()
        dotNetUnsupported(
            "the selected Kotlin/.NET dependency graph is incomplete after non-linking inline resolution; " +
                    "unresolved declarations: $renderedSignatures. Add every Kotlin/.NET library that provides " +
                    "those declarations to the compilation classpath"
        )
    }
}
