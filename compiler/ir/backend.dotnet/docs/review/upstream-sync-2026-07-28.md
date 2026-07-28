# Upstream impact review — 2026-07-28

> **Old base:** `0349ed5cddbc203eeec2308e4d3026e789718da0` (2026-07-21)
>
> **New base:** `6fb64e0c0b0ee9a956b7bdadba3b524415c7dfe0` (2026-07-28)
>
> **Scope:** the 349 upstream commits between those bases and their effect on the unshipped
> Kotlin/.NET backend

This is review evidence and an implementation-impact record. Accepted ADRs remain normative for
ABI and representation decisions.

## Rebase facts

- A safety ref, `codex/pre-rebase-dotnet-20260728`, preserves the pre-rebase tip.
- `git range-diff` matched 245 target commits exactly and one target commit with a deliberate
  adaptation. No target commit was lost or added.
- The only textual conflict was
  `libraries/tools/kotlin-gradle-plugin/api/all/kotlin-gradle-plugin.api`, at the location where
  the target added `KotlinDotNetCompile`. The conflict hunk initially made the adjacent Native
  signature appear to have lost `UsesKotlinToolingDiagnostics`. Current `origin/master`, the
  current source declaration, and the generated API all retain that interface. The repository API
  generator is therefore authoritative: the resolution retains the .NET API entry, restores the
  exact upstream Native signature, and also removes the upstream-deleted `watchosArm32` overloads
  from the external target-container baseline.
- The repository now builds its sources with Kotlin 2.5, bootstrap compiler
  `2.5.0-dev-1759`, and Gradle 9.6.1. The pre-rebase target was already a
  `2.5.255-SNAPSHOT` product and KLIB ABI 2.5, but its repository sources still used language
  level 2.4 and bootstrap `2.5.0-dev-498`.

## Decision method

Every item below records:

1. the mature-target precedent;
2. the actual CLR difference;
3. the Kotlin Common invariant;
4. the applicable .NET/profile rule;
5. the resulting alignment decision; and
6. the core-team classification.

## 1. Explicit IR package-module ownership

1. JVM, JS, Native, and Wasm now consume the shared `IrPackageFragment.module` and
   `IrBuiltIns.moduleFragment` ownership model.
2. .NET additionally binds Kotlin declarations from an embedded KLIB to physical CLR assemblies
   and synthesizes runtime/stdlib declarations, but the CLR has no competing concept which should
   alter logical IR module ownership.
3. A declaration must belong to its producer module; dependency, built-ins, synthetic runtime,
   and consumer modules must not be conflated.
4. CLR `Assembly` identity is a later physical binding and must not be used as a replacement for
   the IR module link.
5. Adopt the common ownership change unchanged. The embedded-DLL loader retains the containing DLL
   as the physical library path while common KLIB infrastructure owns the logical module.
6. **Classification: Correct direction.** No .NET source divergence was required; backend and CLI
   compilation plus the full cross-module gate must verify the inherited change.

## 2. FIR2IR special-annotation provider construction

1. Native and Web pass `createSpecialAnnotationsProvider = null` to
   `convertToIrAndActualize`.
2. .NET has no target-specific special-annotation provider.
3. FIR2IR construction and actualization order must remain common across targets.
4. CLR custom attributes do not require changing this internal FIR2IR lifecycle; their mapping
   belongs at the importer/exporter or code-generation boundary.
5. .NET now uses the same named factory argument as Native and Web.
6. **Classification: Correct direction.**

## 3. Direct FIR/KLIB metadata bytes

1. The common metadata CLI now serializes each `ProtoBuf.PackageFragment` directly to
   `ByteArray`; the former public `SerializedFirFile` wrapper has been removed.
2. .NET packages the resulting KLIB inside the implementation DLL and therefore benefits from
   byte-for-byte reproducible resource construction. Other targets normally publish the KLIB
   container directly.
3. Package names, fragment bytes, module metadata, and Kotlin declaration identities must be the
   same as common KLIB serialization. `expect` declarations must not be assumed to survive in
   serialized IR where upstream no longer permits them.
4. A managed resource is an ordinary CLR container mechanism and imposes no different Kotlin
   metadata schema.
5. .NET now passes protobuf bytes directly to `SerializedMetadata`, like the common metadata
   pipeline. It locally retains source-name sorting before resource packing; this is a
   reproducibility policy, not a second declaration identity or metadata format.
6. **Classification: Reasonable platform-specific divergence** for deterministic DLL-resource
   ordering; **Correct direction** for the shared serialization model.

## 4. `StrictEquals` and `@EqualityBound`

1. The feature is implemented in common FIR and FIR2IR. FIR2IR prepends ordinary reference-
   identity and `NOT_INSTANCEOF` IR guards to the Kotlin `equals` body.
2. CLR reference identity and runtime type checks have different instructions from JVM/JS/Native,
   but .NET already code-generates the common IR operations.
3. The identity fast path, equality-bound rejection, body-call count, inheritance, and smart-cast
   semantics must be target-independent.
4. Both CLR 4 and CoreCLR 10 can implement these operations without an ABI-specific feature
   representation.
5. Do not add a .NET-specific equality lowering. A new box test executes the common prologue
   through PSI and LightTree on `net48` and `net10.0`.
6. **Classification: Correct direction.**

## 5. Inlinable lambda array constructors

1. FIR now treats Kotlin array lambda constructors as inlinable uses inside inline function
   bodies. The change is a frontend permission rule; it does not choose a target representation.
2. .NET deliberately represents `Array<T>` as natural CLR `T[]` and primitive arrays as
   Kotlin-owned wrappers. That physical distinction starts after the common frontend decision.
3. The inline parameter must be evaluated with normal Kotlin array-constructor semantics
   regardless of the eventual array representation.
4. Both target profiles support the loop/delegate operations needed by the existing target array
   lowerings.
5. Adopt the frontend feature unchanged and test both `Array<String>` and `IntArray`. General
   generic/reified inlining remains separately parked; this feature does not require implementing
   it.
6. **Classification: Correct direction.**

## 6. Interface companion properties and static-initialization CFG

1. Common FIR now permits private properties, mutable properties, and private setters in interface
   companion blocks. JVM retains separate `@JvmField` applicability rules. FIR also has a
   dedicated static-initialization CFG for enum entries, companion objects, and companion blocks.
2. .NET places generic-owner and interface companion state on a non-generic compiler holder
   because CLR static state on constructed generic types would duplicate Kotlin state. This
   remains a real CLR reason to differ physically.
3. Visibility, mutability, once-only initialization, and Kotlin source order must remain common.
4. `net48` and `net10.0` can use the same holder contract even though other interface features,
   such as DIMs, differ by profile.
5. Keep the accepted holder lowering. A new box test covers private `val`/`var`, public mutation,
   a private setter, and private-state accessors on both profiles and both FIR parsers. Enum
   interaction remains untested because enum code generation is explicitly parked and must fail
   loudly.
6. **Classification: Correct direction** for properties; **Deferred problem that must be recorded
   before the ABI becomes stable** for enum participation.

## 7. Kotlin-owned static-initializer failure semantics

1. JVM exposes its established `ExceptionInInitializerError`/`NoClassDefFoundError` behavior.
   Native, JS, and Wasm now use the common non-JVM `staticInitializationFailure` contract:
   an original Kotlin `Error` is rethrown, another first failure is wrapped in
   `ExceptionInInitializerError(cause)`, and later access throws `NoClassDefFoundError`.
2. CLR automatically wraps an exception escaping `.cctor` in `TypeInitializationException` and
   permanently poisons that physical type. It cannot directly provide the Kotlin distinction or
   preserve a Kotlin `Error` object.
3. Kotlin-owned initializers must observe the common Kotlin contract. Foreign CLR type
   initialization remains a foreign exception boundary and must preserve the original CLR
   exception object under the accepted exception-classification design.
4. A Kotlin-owned implementation may catch inside `.cctor`, store logical failure state, complete
   the physical initializer, and let the compiler-owned ensure entry perform the first/later
   classification. Because a completed `.cctor` no longer blocks later CLR activity, constructors,
   static methods/accessors, direct singleton loads, generated adapters, and cross-module calls
   must all pass the logical failure barrier before user code runs. The exact state and
   synchronization protocol must work on both CLR 4 and CoreCLR 10; a net10-only shortcut cannot
   redefine common behavior.
5. Do not accept raw `TypeInitializationException` leakage as final Kotlin behavior. Extend the
   existing `<EnsureCompanionInitialized>` design and runtime classifier rather than inventing a
   parallel exception hierarchy. Cover classes, companions, objects, top-level files, inherited
   failures, original `Error` identity, repeated access, and cross-module access.
6. **Classification: Deferred problem that must be recorded before the ABI becomes stable.**
   Treating the current raw CLR consequence as final would be **Architecturally wrong and should
   be changed**.

## 8. Build, test, and Gradle API changes

1. Upstream uses Gradle 9.6.1, continues the JUnit 5 migration, and owns generated public KGP API
   baselines.
2. .NET adds a target, compilation/task types, profile attributes, and a strict multi-runtime test
   aggregate, but those additions do not justify older Gradle or JUnit conventions. Its Framework
   ILAsm and Windows PowerShell CLR 4 host are nevertheless external process-wide resources:
   unbounded parallel test fan-out produced changing missing-PE and empty non-zero-host failures,
   while every affected test passed in isolation.
3. Test discovery, generated-runner discipline, public API validation, and configuration-cache
   behavior should match the repository.
4. Profile attributes and product tasks remain .NET-specific Gradle model content. Serializing
   access to the physical Framework test toolchain does not change Kotlin or .NET execution
   semantics; modern .NET tests and ordinary compiler work remain parallel.
5. Adopt upstream infrastructure. Resolve generated API baselines by retaining both upstream
   removals and the target's real public API, then verify with the repository API tasks. Use one
   inherited JUnit 5 `ResourceLock` for the net48 box and IL-text bases rather than adding retries,
   muting tests, or putting concurrency policy in production code.
6. **Classification: Correct direction.**

## 9. Upstream changes with no .NET action

Java Direct, TypeScript export, SwiftPM/watchOS, Wasmtime runner, and target-specific JVM, Native,
JS, or Wasm optimizations do not change Kotlin/.NET semantics or CLR constraints. They should not
be copied. Shared partial-linkage fixes become relevant only when the .NET frontend enables the
corresponding common partial-linkage phase.

**Classification: Reasonable platform-specific divergence** to leave unrelated target machinery
out of the .NET backend.

## Result

The rebase does not invalidate the self-describing-DLL, embedded-KLIB, profile-pair, primitive-
array, exception-classification, interface-default, or C# authoring directions. It strengthens
their dependency on correct logical module ownership and exposes one foundational follow-up:
Kotlin-owned initializer failures must be classified above the CLR `.cctor` mechanism before ABI
stability.

## Verification

- `:compiler:backend.dotnet:compileKotlin` and `:compiler:cli-dotnet:compileKotlin` pass.
- `:kotlin-gradle-plugin:apiDump` followed by `:kotlin-gradle-plugin:apiCheck` passes.
- The targeted KGP unit/functional lane passes 10 tests in 3 suites; the DLL-only
  `KotlinDotNetTargetIT` lane passes 2 tests.
- Before the Framework resource lock, two full JUnit 5 runs failed 3 and 1 changing net48/PSI
  tests through absent output or empty non-zero host exits; all four observations passed when
  repeated in isolation. After applying the inherited lock, the strict aggregate passes
  **862 tests across 16 suites with 0 failures, 0 errors, and 0 skips**.
