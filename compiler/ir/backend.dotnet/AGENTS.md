# DotNet (CIL) backend — design rules and working notes

Prototype Kotlin → .NET CIL target. Code lives in `compiler/ir/backend.dotnet/` (backend) and
`compiler/cli/cli-dotnet/` (CLI, K2 phased pipeline only). IL-text golden tests are the primary
layout validation: test data in `compiler/testData/codegen/dotnet/ilText/`, runners generated from
`compiler/fir/fir2ir/testFixtures/.../codegen/AbstractDotNetIlTextTest.kt` (`./gradlew generateTests`).
When either supported ILAsm is available, every emitted golden module is also assembled by it;
the strict toolchain lane requires both Framework and modern ILAsm. CLI tests live in
`compiler/testData/cli/dotnet/`. The same box corpus compiles for `net10.0` and `net48` and
executes on real CoreCLR and Framework CLR 4 runtimes through signed system hosts (see "Box tests"
below).

The commit gate is
`./gradlew :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon`. It enables strict
toolchain enforcement and owns both the 780 FIR/IL/semantic tests and the 65 generated-CLI and
library-integration tests. Audit all 16 JUnit XML files under
`compiler/fir/fir2ir/build/test-results/dotNetTest/` and
`compiler/tests-integration/build/test-results/dn/`; the current baseline is 845 tests with zero
failures, errors, or skips. `dn` is an intentionally short private child-task name because the
Gradle convention embeds it in paths consumed by CLR4 and Framework ILAsm, which retain
`MAX_PATH` behavior. Do not replace the aggregate gate with only its FIR child.

## Architectural review and work ordering

- `docs/review/README.md` indexes the review evidence and the current way forward. Read it before
  adding an ABI-bearing feature, runtime type, interface member/view, exception mapping, public CLR
  name, or cross-module binding.
- `docs/review-2026-07-17.md` is a snapshot review of commit `8dd89907d`, not normative design law.
  Draft and accepted ADRs own decisions; `docs/review/way-forward.md` owns current sequencing and
  release gates. When they disagree, reverify the code and amend the relevant ADR rather than
  silently choosing a review conclusion.
- Any implementation change to a documented semantic, representation, or ABI rule must amend the
  affected ADR in the same feature commit. Pure validation infrastructure records fresh evidence
  in these working notes and the way forward; it must not manufacture ADR churn.
- Do not describe the current runtime or physical declaration schema as stable ABI 1. No external
  ABI publication is allowed until the way-forward Gate B requirements are satisfied.
- Nothing has shipped. Until an explicit freeze is recorded, break current prototype binaries,
  names, metadata, runtime types, and artifact layouts when architecture requires it. Move all
  producer/consumer/runtime pieces together, bump the prototype schema, and reject stale artifacts;
  do not build compatibility shims for an unpublished ABI.
- `net48`, `netstandard2.0`, and `net10.0` are required profiles. `netstandard2.0` is library-only;
  the other two produce applications and libraries. Select the profile before target lowerings and
  emit profile-specific code where CLR capabilities differ while preserving common Kotlin
  semantics.

## Design rules

- Before implementing DotNet backend behavior, inspect how the mature JVM/JS/WASM/Native targets
  solve the same problem, then make an explicit .NET-specific decision. Do not invent a separate
  approach unless the CLR platform model gives a concrete reason.
- When reporting an implemented DotNet backend feature, state which mature target it follows. If the
  implementation deviates, state the target it deviates from and the CLR-specific reason.
- If JVM uses an intrinsic registry for a behavior, DotNet wires that behavior through
  `DotNetIlIntrinsicMethods` too, unless there is a concrete platform reason not to. "Needed later"
  is not a valid reason to skip the registry shape; add the registry entry now and let unsupported
  cases fail explicitly.
- IL codegen fails on unsupported IR (`dotNetUnsupported()`) instead of emitting fallback IL such as
  empty strings or zero values. The POC emitter currently skips uncompilable functions to a
  fixpoint (callers of skipped functions are skipped too). This is developer diagnostics, not a
  library-publication model: any eviction must make library and stdlib production fail, and the
  endpoint is a located FIR diagnostic before backend codegen.

## Established decisions

The bullets below also record landed prototype behavior. Where an accepted ADR says that behavior
must be replaced, the ADR is authoritative even before implementation catches up; do not treat the
landed shape as a compatibility constraint.

- IL codegen split: `DotNetIlEmitter` (module orchestration), `DotNetIlClassCodegen` (class shell,
  method dispatch), `DotNetIlMethodCodegen` (bodies/statements), `DotNetIlExpressionCodegen`
  (expressions), `DotNetIlMethodContext` (slots/labels/maxstack/stack verification),
  `DotNetIlCodegenSupport` (type mapping, signatures, escaping), `DotNetIlType` (value types vs
  return types).
- POC assembly pipeline (argumentation:
  `docs/decisions/draft-adr-il-assembly-pipeline.md`): textual IL plus modern ILAsm remains the
  normal `net` assembly path while representation and runtime ABI are moving; Framework ILAsm is
  the Framework target assembler and an independent compatibility-floor oracle. This is not a
  permanent distribution commitment. Before productionization, interpose a structured
  compiler-owned CIL/metadata model and add a JVM-hosted direct PE writer behind it, while
  retaining deterministic text rendering and ILAsm conformance tests. Do not introduce a .NET
  sidecar merely to call `System.Reflection.Metadata`: the compiler is JVM-hosted, so that keeps
  the external-process boundary and adds another private protocol without solving the core writer
  ownership problem.
- String concatenation follows the mature target shape: `FlattenStringConcatenationLowering`, then
  `DotNetStringConcatenationLowering`, then IL codegen handles `String.plus`/`toString` intrinsics.
  Avoid ad-hoc IrWhen/boolean handling inside string emission.
- Lowerings run through `NamedCompilerPhase`/`PhaseEngine`, measured as `PhaseType.IrLowering`.
- Main selection uses `DotNetMainFunctionDetector`. When one file contains both supported shapes,
  the parameterized `main(Array<String>)` takes precedence over parameterless `main()`, following
  Kotlin's mature enhanced-main detector. No wrapper is generated when the selected Kotlin `main`
  shape already maps to a valid CLR `.entrypoint` method (ECMA-335 allows parameterless or
  `string[]` entry points); add a wrapper only when a supported source shape needs one.
- Runtime assembly foundation (probe series `runtimeprobe_s1`; follows the JVM separation between
  generated programs and a Kotlin-owned runtime, with CLR assembly identity replacing JVM jar
  identity): every assembled executable carries an AssemblyRef to, and is emitted beside,
  `Kotlin.Runtime.dll`. All artifacts in the current prototype matrix consistently use the
  culture-neutral candidate identity
  `Kotlin.Runtime, Version=1.0.0.0, PublicKeyToken=null`. This is a deterministic build/test
  identity, not a published ABI major: the version and unsigned status may change before Gate B.
  Before external publication, decide the first public assembly names, strong-name policy,
  AssemblyVersion compatibility policy, and package-version relationship together. Once an
  identity is published, changing its name, version binding policy, or strong-name key is an
  explicit CLR ABI transition rather than a silent retargeting. The runtime now emits one
  netstandard2.0 ECMA-335 definition and uses modern ILAsm as
  its portable writer; Framework ILAsm is not used for the canonical library because it injects
  an `mscorlib` AssemblyRef. The API remains within the audited netstandard2.0 surface so the same
  candidate surface runs on Framework 4.8 and modern CoreCLR. `runtimeprobe_s1` originally
  assembled the runtime and a type-resolving consumer with both
  modern 10.0.9 and Framework 4.8 ILAsm; all four same/cross-runtime pairings ran, while both
  runtime binaries reported the exact candidate identity above. Namespace ownership is reserved
  now:
  Kotlin language ABI types live under `Kotlin`, runtime services under `Kotlin.Runtime`, and
  compiler-only cross-assembly support under `Kotlin.Runtime.Internal`. The initial
  foundational type was the deliberately memberless static marker
  `Kotlin.Runtime.RuntimeInfo`; the callable ABI candidate added afterward is described below.
  Compiler-generated default constructors use the public-metadata, sealed
  `Kotlin.Runtime.Internal.DefaultConstructorMarker`, whose constructor is private and whose only
  emitted value is null. This follows the JVM's collision-marker boundary while keeping the type
  out of Kotlin-facing namespaces; it is a compiler/runtime ABI type, not a source API.
  Private-constructor synthetic accessors use the distinct public-metadata, sealed
  `Kotlin.Runtime.Internal.SyntheticConstructorMarker`, also private-constructed and null-only.
  Keeping the protocols physically distinct prevents a default-argument stub and a private-access
  bridge with otherwise equal parameter lists from acquiring the same CLR signature.
  CLR-public declarations that exist only for separately compiled Kotlin code are identified by
  `Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute` and hidden from ordinary completion with
  `EditorBrowsable(Never)`. The marker itself and compiler-helper types carry the same completion
  policy. This marker was the first runtime-surface-level-2 addition; public Kotlin declarations
  and explicit C# exports do not receive it. The attribute's physical scope is profile-owned:
  `[netstandard]` for portable libraries, `[System]` with the Framework 4.0 identity for `net48`,
  and `[System.Runtime]` with the .NET 10 contract identity for `net10.0`. Neither runtime profile
  defines `System.ComponentModel.EditorBrowsableAttribute` in its selected core reference. A user
  assembly emits the supplementary AssemblyRef only when its body carries the attribute;
  `Kotlin.Runtime` always emits it because the marker and compiler-helper types are annotated.
  Framework reflection pins the MemberRef as well as the visibility flags, and modern execution
  pins resolution through the .NET contract facade.
- Internal/friend/compiler ABI (decision:
  `docs/decisions/draft-adr-friend-assemblies-and-compiler-abi.md`): ordinary Kotlin `internal`
  declarations are genuine CLR assembly-internal declarations. A friend relationship is
  producer-authorized and consumer-declared: the producer emits `InternalsVisibleTo`, schema 4
  persists the structured unsigned/full-public-key identity, and the consumer supplies the bound
  KLIB as a friend dependency. The frontend validates producer authority for the actual output
  assembly before FIR grants source access. `@PublishedApi internal` declarations are instead
  CLR-public compiler ABI with `KotlinCompilerAbiAttribute` and `EditorBrowsable(Never)` while
  remaining internal in Kotlin metadata. Build-tool association must eventually wire both halves;
  the provisional CLI switches are not the public model. Unsigned names are not a security
  boundary, and signed friends require the full public key rather than a token.
  The runtime also owns `Kotlin.RuntimeException : System.Exception` as the dormant physical root
  for exact Kotlin-only exception identities and
  `Kotlin.NoWhenBranchMatchedException : Kotlin.RuntimeException` as its first child. The first
  source-visible exact types are `Kotlin.NumberFormatException : System.ArgumentException`,
  `Kotlin.NoSuchElementException : Kotlin.RuntimeException`, and `Kotlin.Error : System.Exception`.
  Their hybrid exception policy is described in the
  exception-model bullet below.
  The compiler reserves the runtime assembly name, creates no runtimeconfig for the library, and
  removes stale program outputs when either ILAsm path fails. Shared compiler support is emitted
  once in the runtime under `Kotlin.Runtime.Internal`, never copied into generated modules.
- Target stdlib bootstrap (argumentation:
  `docs/decisions/draft-adr-target-stdlib-bootstrap.md`; follows the JVM split between runtime ABI
  support and ordinary stdlib implementations, with CLR assemblies as the physical boundary):
  `Kotlin.Stdlib, Version=1.0.0.0, PublicKeyToken=null` is the second reserved prototype platform
  identity and is governed by the same pre-publication identity rule as `Kotlin.Runtime`.
  `Kotlin.Runtime` retains language identities and compiler/runtime services;
  `Kotlin.Stdlib` owns ordinary Kotlin library implementations. The first implementations are the
  generic `Kotlin.Collections.ArrayIterator<T>` and `ArrayIterable<T>`, compiled through the same
  class and split generic-interface bridge pipeline as user code, followed by the common
  `Iterable<T>`/`List<T>` `first()` and `last()` overloads on the current compiler/stdlib physical
  `Kotlin.Collections.CollectionsKt` facade. User calls cross the assembly edge; the function
  bodies run in the stdlib and use split Iterable/Iterator/List capabilities with canonical
  fallback for every implementation.
  The implementation classes are private in both Kotlin source and CLR metadata. Generated user
  assemblies call the Kotlin-internal, metadata-public generic `dotNetArrayIterator` and
  `dotNetArrayIterable` factories on `Kotlin.Collections.CollectionsKt`; those methods, rather
  than implementation class names or constructors, are the compiler/stdlib ABI. This follows the
  JVM/JS helper boundary for host-array iteration. The default bootstrap path still injects stdlib source into
  the same frontend/lowering run and `DotNetIlEmitter` partitions the lowered module into USER and
  STDLIB ownership scopes. A separate consumer can now resolve Kotlin declarations from a metadata
  `Kotlin.Stdlib.klib` and call implementations in its bound sibling `Kotlin.Stdlib.dll`, with no
  injected implementation source. The KLIB manifest binds the complete unsigned assembly
  identity, file, and selected library TFM; an arbitrary metadata KLIB never becomes a CLR
  reference. The POC-only `-Xdotnet-produce-stdlib -d <directory>` route now follows JS/Wasm's
  explicit KLIB-product selection and Native's dedicated `LIBRARY` pipeline: with no user source
  inputs, one resolved frontend/IR run serializes the compiler-owned declarations and emits the
  bound profile-specific KLIB/DLL pair. It is never an executable-build side effect. JVM's embedded
  class-file metadata is not the applicable lifecycle model because .NET currently has two
  physical companion artifacts. Every assembled executable still receives both platform dlls;
  same-run stdlib production remains bootstrap compatibility machinery. Repeated standalone builds
  must produce byte-identical packed KLIB, compiler-owned IL, and deterministic PE for each profile;
  variants are not required to be byte-identical to one another. Once a distribution-owned default pair is populated in Kotlin home, same-run production
  must disappear without moving ordinary implementations back into `Kotlin.Runtime`.
  Ordinary compilation prefers the complete selected-profile pair, then
  `<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.{klib,dll}` for either executable profile,
  and rejects a half-installed candidate; absence still falls back to injected sources. Repository production is deliberately
  opt-in: `:kotlin-compiler:produceDotNetStdlib` runs the compiler from the assembled distribution
  and writes all three bound variants plus diagnostic IL under `prepare/compiler/build/dotnet-stdlib/<profile>`, while
  `:kotlin-compiler:installDotNetStdlib` installs each KLIB/DLL pair into its Kotlin-home profile
  directory. Neither task is a dependency of ordinary `dist`/`distKotlinc`, so a normal build does
  not acquire either ILAsm requirement. Because `distKotlinc` is a whole-home `Sync`, a later
  standalone invocation intentionally removes the optional variants; run the install task afterward
  to restore them.
  The netstandard physical declaration index is an executable ABI floor for both runtime stdlib
  variants. `DotNetLibraryAbiCodec.portablePhysicalAbiDifferences` permits additional profile-owned
  entries but reports every missing portable logical key or changed CLR owner/member binding in
  deterministic key order. Integration production compares all three generated variants together
  with the logical-identity scheme, physical-name grammar, and runtime-surface floor. This is the
  structured KLIB/DLL binding audit; do not approximate it by diffing rendered IL text.
  The same integration test independently loads the assembled runtime/stdlib pairs in isolated
  CoreCLR contexts and compares their externally consumable reflection surfaces. Both executable
  variants must retain every portable public/protected type, base/interface edge, generic
  constraint, method, field, property, and event with compatible access and overridability. The
  verifier also compares normalized custom-attribute identities and constructor/named payloads on
  assemblies and the exposed surface; `TargetFrameworkAttribute` alone is excluded because its
  value is profile-specific. It is isolated C# test data, not a compiler sidecar; raw attribute-
  blob encoding, MethodImpl rows, resources, and friend-only internal surface remain outside this
  bounded audit.
  `Kotlin.Runtime` and `Kotlin.Stdlib` use the selected core-library profile and exact
  TargetFrameworkAttribute metadata. The portable variant has an exact `netstandard` AssemblyRef
  and no `mscorlib` MemberRefs. The complete
  profile was audited against the 2.0 reference assembly: 27 BCL types and 55 members, zero
  misses. Applications retain their executable profile. The POC
  `-Xdotnet-produce-library -d <directory>` mode now emits an ordinary source module as a bound
  `<module>.klib`/`<module>.dll` pair under the selected profile, with no entry point or
  runtimeconfig. `netstandard2.0` uses the portable modern PE writer; the runtime profiles use
  their corresponding assembler. Explicit CLR exports are callable across the resulting assembly
  edge. Kotlin cross-module calls now follow JS/Native's public `IdSignature` as their logical key;
  a versioned POC KLIB index adds only the CLR owner path, method name, and dispatch shape needed
  to bind that declaration to its sibling assembly. Signatures remain metadata-derived, arbitrary
  metadata KLIBs remain compile-time-only, and consumers never reconstruct a facade from a source
  filename. Executables copy directly referenced implementation DLLs beside their output. The
  manifest-property encoding is provisional pending a real KLIB component and signature-version
  compatibility policy. .NET Standard is a library target, never an executable runtime. See
  `docs/decisions/draft-adr-dotnet-library-target-profile.md`.
  The current stdlib generator has Common/JVM/JS/WASM/Native targets but no .NET target. `first()`
  and `last()` are traceable bootstrap extractions of common `Elements.f_first` and `f_last`,
  including their List overloads and runtime List dispatch. The generated `lastIndex` property is
  expanded to its exact `size - 1` body because generic extension properties remain deferred. Do not add a generator
  target that emits an uncompilable broad corpus. The durable endpoint is compiling generated
  common sources plus narrow .NET actuals once the backend can compile the required generated
  collection surface; the standalone producer already emits the bound KLIB/DLL pair from one
  source compilation.
- Callable ABI candidate (argumentation: `docs/decisions/draft-adr-erased-callable-abi.md`; probe
  series `callableabi_s2`, `captureabi_s3`, `kfunction_s1`, and `callableexact_s1`; follows the JVM split between logical generic
  function types and erased
  executable descriptors, with CLR `object` replacing JVM Object):
  Kotlin-to-Kotlin callable storage uses the public non-generic runtime interfaces
  `Kotlin.Function`, `Kotlin.Function0`, `Kotlin.Function1`, `Kotlin.Function2`, and
  `Kotlin.Function3`; it never uses `System.Func`/`System.Action`. The fixed interfaces expose
  exactly `object Invoke(...)`, with one `object` parameter per logical argument. Source
  `kotlin.Function<R>` and `Function<*>` map to the
  non-invokable `Kotlin.Function` marker without changing identity. Nullable callable references
  keep the same physical interface. Kotlin's `in`/`out` type arguments remain compiler-level
  type information rather than CLR-reified interface identity, so every legal variance conversion
  — including `() -> Int` to `() -> Any` and `(Any) -> String` to `(Int) -> Any` — is an
  instruction-free copy of the same object and preserves `===`. Invoke entry casts reference
  arguments and uses `unbox.any` for primitives, nullable primitives, and open `T`; invoke exit
  boxes values into the canonical object result. A Unit result executes the ordinary CLR-void
  body and then returns the singleton `Kotlin.Unit.INSTANCE`. As a prototype implementation policy,
  constructor-empty non-capturing lambda/reference classes cache one instance in their own
  `INSTANCE` field; stateful callable classes are always freshly constructed. The
  field is created after initializer cleanup and the normal static-initializer sweep emits its
  `.cctor`. Extension receivers occupy
  the first ordinary erased argument, and explicit user classes implementing a function type emit
  the same erased override. FIR keeps a direct reference expression typed as `KFunctionN` even
  when consumed through `FunctionN`. The orthogonal reflection view follows the JVM mapping:
  the runtime exposes non-generic `Kotlin.KCallable` with only `string get_name()` and the
  memberless `Kotlin.KFunction : KCallable, Function` marker. A direct function-reference object
  implements that marker AND exactly one erased `Kotlin.FunctionN`; lambdas and adapted references
  without a KFunction source type remain FunctionN-only. KFunction declares no invocation member.
  Source `KFunction0`/`KFunction1`/`KFunction2`/`KFunction3` storage maps to the non-generic
  KFunction view, and invocation or widening to FunctionN performs a checked interface view change
  on the SAME object before calling the unchanged erased `Invoke`. This is reflection capability,
  not a second
  callable execution/identity ABI and never creates a wrapper. Erasure makes overloads differing only in logical
  function arguments collide; the existing CLR method-identity gate rejects both overloads and
  lets unrelated declarations survive, matching the JVM platform-clash category. `callableabi_s2`
  assembled an erased consumer/runtime pair with modern 10.0.9 and Framework 4.8 ILAsm and all
  four same/cross-runtime pairings ran with identity and boxed-Int invocation intact.
  Capturing lambda and bound-reference classes keep exactly that callable identity. Immutable
  captured values and bound receivers are private fields of the generated class, never delegate
  wrappers or additional callable ABI shapes. `SharedVariablesLowering` runs before local
  declaration closure conversion and replaces each captured mutable variable with one shared,
  invariant `[Kotlin.Runtime]Kotlin.Runtime.Internal.MutableRef<T>` cell. Generated callables
  capture the cell reference, so sibling closures and later outer writes share storage without
  boxing the cell's primitive, nullable-primitive, reference, or open-`T` element. The cell and
  generated fields are compiler/runtime layout details, not Kotlin callable identity.
  Direct function references additionally follow Native's structural `Any` semantics without
  adding another callable interface. Every rich reference with a real declaration target extends
  metadata-public, compiler-internal `Kotlin.Runtime.Internal.FunctionReferenceBase`; lambdas
  still extend System.Object directly. Its constructor and bound-value hook are CLR `family`, so
  generated subclasses in consumer assemblies can use them without making either hook public. The
  base compares the target's serialized Kotlin signature (or a deterministic module-local
  fallback), arity, adaptation flags, and structurally compared bound values, and renders
  `function <name>`/`constructor`. Equivalent references from different source sites therefore
  compare equal and hash equally; overloads, different adaptations, and unequal bound receivers do
  not. The base class is generated implementation machinery, never a field/parameter/return ABI,
  execution interface, or source API. Explicit user function implementations retain their own Any
  behavior. Fun-interface constructor references remain rejected with the existing no-SAM-model
  boundary; their mature identity rule belongs with that future feature.
  Common closure conversion adds captured type arguments to constructor calls even when their IR
  class type is bare; codegen reconstructs that generated generic instance from the constructor
  type arguments. An erased Unit `Invoke` whose lowered block falls through materializes
  `Kotlin.Unit.INSTANCE` before returning its mandatory object result. `captureabi_s3` assembled
  compiler-produced capturing consumers/runtimes with modern 10.0.9 and Framework 4.8 ILAsm; all
  four same/cross-runtime pairings executed immutable, mutable, Unit, generic-cell, and bound
  receiver cases. `kfunction_s1` assembled a runtime plus a dual-interface reference object under
  modern 10.0.9 and Framework 4.8 ILAsm; all four same/cross-runtime pairings observed the same
  object through KCallable, KFunction, and Function1 and invoked the erased slot. Pins:
  `ilText/callableObjects.kt`, `ilText/callableCaptures.kt`, `ilText/callableReferences.kt`,
  `ilText/callableObjectsRejected.kt`, `box/callableObjects.kt`, and
  `box/callableReferences.kt`.
  Generated non-Unit Function0/1/2/3 callables now follow the JVM typed-body-plus-erased-bridge
  pattern while retaining the sole FunctionN identity. The original typed body is `InvokeExact`;
  the erased bridge calls it. The CLR-specific discovery mechanism is one optional, variant
  `[Kotlin.Runtime]Kotlin.Runtime.Internal.ExactFunctionN<P..., R>` interface on that same object.
  It is metadata-public only because generated modules consume it across the runtime assembly
  boundary; it is neither a Kotlin source declaration nor a storage/interface identity. A
  statically shaped FunctionN call evaluates receiver and arguments once, probes the closed exact
  call-site interface, invokes it without argument/result boxing on a hit, and otherwise uses the
  stable erased slot. When an immutable local's initializer chain retains a different original
  FunctionN/KFunctionN shape, codegen may probe that exact interface second and apply only legal
  argument/result widenings. Thus `(Int) -> Int` stored locally as `(Int) -> Any` invokes
  `ExactFunction1<Int, Int>` and boxes only its result; a discarded non-Unit invocation uses the
  same guarded path. The second probe is omitted when CLR reference variance already makes the
  first probe sufficient. Mutable locals and parameter/field/return boundaries have no such
  provenance, but a second optional execution capability now covers the measured
  provenance-free value-argument case without changing storage identity:
  `[Kotlin.Runtime]Kotlin.Runtime.Internal.TypedArgumentsFunction1<P0>` and
  `TypedArgumentsFunction2<P0, P1>` expose `object InvokeTyped(P...)`. Generated non-Unit
  Function1/2 objects with at least one concrete primitive or nullable-primitive parameter
  implement that interface and box only their result. A call whose logical result is `Any`/`Any?`
  and which has such a parameter probes the closed typed-arguments view first, then the existing
  exact shape(s), then erased Invoke. Exact primitive-result calls therefore stay on ExactFunctionN.
  Explicit user implementations and older modules remain valid because every optional path is
  guarded by `isinst`. Unit stays erased because void cannot close a generic result slot and the
  Framework benchmark did not justify an Action-like partial capability.
  Function3 has the exact capability but deliberately no TypedArgumentsFunction3: the partial
  interface is a metadata-public runtime contract, and the cross-module allocation/throughput
  evidence that justified only Function1/2 has not been repeated for arity 3. The CLR export
  boundary separately remains capped at Function0/1/2 and has no Func/Action3 adapters.
  `callableexact_s1` assembled and ran identical, erased-only, reference-variant, and value-variant
  cases with both ILAsm versions and all four runtime pairings. `callable_capability_s1` first
  proved the partial shape on both runtimes. The separately compiled
  `typed_arguments_crossmodule_s1` producer/consumer benchmark then measured 2,000,000 widened
  `(Int) -> Any` calls: erased invocation allocated 96,000,040 bytes and typed-arguments invocation
  48,000,040 bytes on CoreCLR, exactly one 24-byte argument box saved per call. Stable partial-first
  timings improved from 40–43 ms to 30–31 ms on CoreCLR and 200–213 ms to 141–142 ms on Framework;
  each producer bridge was 13–17 IL bytes plus one InterfaceImpl row (PE size stayed within the
  existing 512-byte alignment). The Unit variant removed allocation but regressed Framework time,
  which is why Unit remains outside the implemented scope.
  Repository pins cover ordinary, capturing, bound, KFunction, local, array-initializer, nullable,
  generic, evaluation-order, explicit-fallback, and immutable-provenance shapes on CoreCLR;
  `ilText/callableInvocationProvenance.kt` distinguishes exact primitives, primitive-result
  widening, parameter widening, mutable/boundary capability dispatch, and explicit fallback.
  This is an execution capability only: never use it in
  fields, parameters, returns, ordinary Kotlin subtype conversion, or as a CLR delegate identity.
  The explicit export helper may probe the same-object capability solely to bind a typed Func;
  the generated facade exposes the delegate, never ExactFunctionN.
  The explicit CLR export slice gives host-facing naming and delegate projection/adaptation an
  explicit owner without a Kotlin source annotation or automatic whole-module policy. The
  unchanged-declaration plus host-wrapper boundary follows JVM naming/default and Wasm/JS export
  patterns semantically. The textual selector does NOT: it is provisional POC control-plane
  machinery needed only because no source-bound annotation exists. Repeatable configuration
  `-Xdotnet-export=<kotlin-selector>=<clr-method-name>` selects exactly one public, non-generic
  top-level function. A unique name may use the legacy `pkg.name` selector. An overloaded name
  must use `pkg.name(kotlin.Int,kotlin.String?)`: fully qualified, whitespace-free, expanded Kotlin
  parameter types; nested types use canonical forms such as
  `kotlin.Function1<kotlin.Int,kotlin.Int>`, and an extension receiver is the first parameter.
  Return types are excluded because Kotlin has no return-only overloads. These are Kotlin logical
  signatures, never CLR/IL tokens or declaration-order indexes; multiple overloads may therefore
  be exported independently, while a bare overloaded name fails loudly. Do not promote this
  spelling to a public ABI: a future declaration-bound export mechanism should make the textual
  disambiguator unnecessary. The canonical Kotlin
  method remains unchanged; a user-named static method is added to the SAME file facade. Ordinary
  positions retain their mapped CLR shapes. Only that
  explicit surface replaces Function0/1/2 positions with typed Func/Action (Unit -> CLR void). An
  ordinary function with no callable position is valid and gains the same explicit CLR name,
  nullability metadata, collision policy, and Kotlin-default overloads. The metadata-public
  `Kotlin.Runtime.Internal.DelegateProjection` helper projects returns and adapts delegate
  parameters into private runtime-owned classes implementing the canonical FunctionN interface.
  Func adapters additionally expose the optional ExactFunctionN capability; Func1/2 adapters also
  expose their TypedArgumentsFunctionN view, while Action adapters stay erased because Unit has no
  generic void representation. Projecting one of these adapters back to
  the same closed delegate shape returns its stored ORIGINAL delegate object. Different closed
  shapes have no identity promise, and Kotlin-callable -> delegate -> Kotlin-callable identity is
  not promised. Existing Kotlin-to-CLR projection still binds exact Func directly to InvokeExact,
  falls back through closed generic box/unbox thunks, and uses a void thunk for Action. Repeated
  projection of the same Kotlin object/shape compares delegate-equal without caching. Adapters and
  projections add no catches; a null delegate at a non-null exported parameter throws
  ArgumentNullException, while a nullable callable position maps null in both directions. Every
  explicit export carries Roslyn-compatible `NullableAttribute` metadata on each non-empty return
  and parameter type shape. The compiler synthesizes the reserved attribute only when at least one
  exported shape needs it; a primitive-only export neither emits nor reserves that class. It emits
  deterministic explicit attributes instead of NullableContext compression and
  encodes reference/generic/array nesting in preorder (`0` oblivious, `1` non-null, `2` nullable),
  skipping value types per Roslyn's contract. The metadata comes from source IR, never physically
  erased FunctionN. A contiguous suffix of source-default parameters creates progressively shorter
  overloads on this explicit facade only. Each overload supplies physical zero/null placeholders,
  sets the existing masked-dispatch bits, and calls the Kotlin `$default` helper, so arbitrary and
  parameter-dependent expressions remain callee-evaluated. Nullable value placeholders use
  initialized locals. No `[opt]`/constant metadata is emitted: Roslyn otherwise copies constants
  into callers or substitutes `default(T)`, neither of which is Kotlin's general contract.
  Non-trailing defaults create no overload; any generated-signature collision fails the requested
  export as a whole. Missing or ambiguous selectors, facade-name/exported-signature clashes,
  generic or suspend functions, KFunction/suspend callable positions, and arities above 2 fail
  loudly. Member functions, member properties, constructors, classes, and automatic whole-module
  export remain out. No
  projection or adaptation occurs in ordinary Kotlin fields,
  parameters, returns, subtyping, or calls. `delegateexport_s1` and `delegateadapter_s1` ran every
  Func/Action arity under modern and Framework ILAsm; compiler-produced facades plus the landed
  runtime ran both directions, invocation, and same-shape round-trip identity on both runtimes.
  `nullableexport_s1` additionally validated explicit scalar/vector metadata blobs and nullable
  delegate round trips on both runtimes; CoreCLR's NullabilityInfoContext reads the compiler output
  as the intended nested nullable states. `defaultexport_s1` validated CLR optional-constant
  behavior, overload preference, generated masked calls, and C# consumers on both runtimes. Pins:
  `ilText/callableExports.kt`, `ilText/callableParameters.kt`,
  `ilText/callableExportDefaults.kt`, `ilText/plainFunctionExports.kt`, CLI
  `dotnet/callableExport.args`, and both reserved-attribute and default-overload collision fixtures.
  `plainfunctionexport_s1` assembled ordinary aliases with both ILAsm versions and Roslyn consumers
  executed primitive, reference, default-overload, and extension-receiver calls on both runtimes;
  the primitive-only reserved-name CLI pin proves nullable metadata is demand-driven.
  `overloadedexport_s1` assembled signature-selected aliases under both ILAsm versions; Roslyn
  5.6.0 consumers executed primitive/reference overloads, typed callable adaptation, a defaulted
  extension, and a nested nullable generic argument on CoreCLR and Framework. The selector is now
  FROZEN: it remains compiler-only, no later feature may depend on its textual type grammar, and
  documentation must keep calling it provisional.
  A separate minimal `-Xdotnet-export-property=<kotlin-fq-name>=<clr-property-name>` path selects
  one unique public, non-extension, non-const top-level property without adding any type grammar.
  The durable output is a real static CLR `.property` plus public `specialname` wrapper accessors
  on the same file facade. `val` and a non-public Kotlin setter produce a getter-only alias; public
  `var` produces both wrappers. Ordinary types retain their mapped shape, Function0/1/2 uses the
  established Func/Action projection and adaptation, and nullable metadata is repeated on the
  property row, getter return, and setter value following Roslyn. Property, accessor, method, and
  const-field collisions are loud whole-export errors; function exports reciprocally reject an
  occupied property/const-field name. Extension/indexer policy, const-property wrapping, member
  properties, and all additional selector grammar remain deferred. `propertyexport_s1` assembled
  with both ILAsm versions; Roslyn consumers ran mutability, nullability, callable identity and
  invocation, read-only access, and reflection checks on CoreCLR and Framework. Detailed
  decisions are in the callable, CLR-default, and CLR-property draft ADRs.
  STAYS REJECTED, loudly: suspend callables, Kotlin callable arity above 3, explicit CLR callable
  export arity above 2,
  KCallable metadata beyond `name`, property-reference reflection beyond direct get/set/invoke,
  reflective lookup/call APIs,
  implicit delegate conversion outside an explicit export, Unit exact entry points, and Kotlin
  metadata serialization. Later .NET-facing export slices must preserve the logical function arguments;
  the canonical interface encodes none of those arguments, so CLR reflection alone cannot
  reconstruct the Kotlin type even if later optimization members are visible. Promotion of the
  candidate requires both a measured exact-shape non-boxing execution path and representative
  typed CLR exports. Those are mandatory validation layers around the erased identity/fallback,
  not a reason to split the canonical Kotlin representation in this first POC slice.
  Final raw library IL declares the exact runtime AssemblyRef whenever an emitted signature or
  body contains a `[Kotlin.Runtime]` token; it never relies on ILAsm assembly autodetection.
- Property-reference ABI candidate (argumentation:
  `docs/decisions/draft-adr-erased-property-reference-abi.md`; follows the Native/Wasm wrapper
  model): a source property reference becomes one runtime-owned object storing its name and
  lowered getter/optional setter FunctionN objects. The CLR-specific deviation is physical
  erasure: public non-generic `Kotlin.KProperty0/1/2` and `KMutableProperty0/1/2` identities expose
  object-shaped `Get` arguments/results and void `Set`, because CLR generic variance cannot
  preserve one identity through value-type instantiations. Each KPropertyN inherits the existing
  erased FunctionN interface; invocation therefore uses the same callable ABI rather than a
  delegate or second callable shape. Bound receiver storage remains in the generated getter and
  setter classes, and common property-reference lowering evaluates a non-trivial bound receiver
  once before sharing it. Private runtime wrappers are created through metadata-public
  `Kotlin.Runtime.Internal.PropertyReferenceFactory` methods, a compiler/runtime contract rather
  than source API. Immutable and mutable arities 0..2 are constructed. Mutable arity 2 uses the
  coherently extended erased Function3 family for its ordinary Unit-returning setter object; it
  does not introduce a property-specific callable shape. Direct
  `get`/`set`, FunctionN invocation, primitive result variance, bound mutation, and explicit user
  implementations use the universal erased slots. Following Native/Wasm, runtime-owned wrappers
  compare their exact private wrapper kind, name, structurally equal getter, and optional setter;
  bound receivers participate through those callable references. Equal wrappers hash the same and
  render `property <name> (Kotlin reflection is not available)`. User implementations keep their
  own Any behavior. JVM-shaped getter/setter accessor objects are not exposed: the common expect
  declaration and Native/Wasm/JS actuals omit them, while JVM adds a target-specific surface whose
  Accessor points back to its property and is also a KFunction. Returning the wrapper's stored
  FunctionN would be semantically false. Local delegated-property tokens follow Native/Wasm
  separately: private name-only KProperty0/KMutableProperty0 wrappers expose truthful name,
  mutability, and rendering, but Get/Invoke/Set throw the exact mapped
  `UnsupportedOperationException("Not supported for local property reference.")` and retain object
  identity equality. Two compiler-internal factories construct them; no getter/setter callable or
  new reflection metadata is invented. Common `LocalDelegatedPropertiesLowering` flattens their
  declarations before closure conversion, whose eligible local-function set includes delegated
  accessors. An IR-only throw-helper symbol lets common callable-reference upgrade build temporary
  unsupported accessor bodies; property-reference lowering discards them, so the helper is never
  emitted as runtime or module ABI. Full reflection, accessor objects, and exact property-access
  capabilities remain deferred.
  Pins: `ilText/propertyReferences.kt` and `box/propertyReferences.kt`.
- Iteration ABI candidate (argumentation: `docs/decisions/draft-adr-erased-iterator-abi.md`; the
  CLR-specific application of the general split generic-interface ABI): source `Iterator<T>` uses
  canonical `[Kotlin.Runtime]Kotlin.Collections.Iterator` (`bool HasNext()`, `object Next()`) for
  every Kotlin ABI position, plus the optional same-object covariant
  `[Kotlin.Runtime]Kotlin.Collections.Iterator<T>` capability (`bool HasNext()`, `T Next()`).
  `ListIterator<T>` follows the same two-view rule and extends both matching Iterator views;
  canonical `Next`/`Previous` return `object`, while the covariant declared view returns `T`.
  It has no exact view because its element type occurs only in result positions. Both physical
  views redeclare `HasNext` and `Next`, matching the common declaration and giving its logical
  slots real CLR owners. A derived ListIterator whose inherited base supplies those two methods
  receives ListIterator MethodImpls that still dispatch through the base's virtual source slots.
  `Iterable<T>` likewise uses canonical `Kotlin.Collections.Iterable` plus covariant
  `Iterable<T>`; both `GetIterator()` slots return canonical Iterator because a nested typed
  capability is not universally guaranteed. Kotlin's logical covariance remains IR/metadata, so
  primitive and reference widening preserves object/cursor identity without CLR generic
  conversion or adapters. Exact calls probe the closed typed capability and avoid primitive
  boxing; a miss calls the canonical object slot and casts or `unbox.any`s the result.
  `DotNetGenericInterfaceBridgeLowering` is the ONLY Iterator/ListIterator/Iterable bridge engine. It emits
  canonical and declared MethodImpl forwarders for ordinary stdlib and user implementations;
  the former collection contract table, bridge origins, emitter branch, and generic collection
  call intrinsics are removed. The stable runtime slot names remain `HasNext`, `Next`,
  `HasPrevious`, `Previous`, `NextIndex`, `PreviousIndex`, and `GetIterator`. A bridge-owning base supplies the complete set to descendants and forwards
  through virtual source members; an abstract obligation-only base defers ownership to the first
  concrete implementation. Generic subinterfaces receive their own canonical/declared views and
  may redeclare execution members under the same general physical-slot rules. Imported CLR
  interfaces never enter this representation.
  The five primitive iterator classes still alias canonical Iterator and retain narrow call
  intrinsics until the target stdlib produces those ordinary classes. Explicit `iterator()` over
  supported vectors calls the metadata-public, Kotlin-internal generic factory
  `[Kotlin.Stdlib]Kotlin.Collections.CollectionsKt.dotNetArrayIterator<T>(T[])`. The factory
  constructs the private ordinary Kotlin `ArrayIterator<T>` implementation, which stores an exact
  `T[]`, observes mutations, implements both runtime views, and boxes only in its canonical
  `object Next()` bridge. Exhaustion throws runtime-owned
  `Kotlin.NoSuchElementException`; direct array `for` loops remain allocation-free indexed loops.
  The common stdlib's array `asIterable()` surface calls the corresponding generic
  `dotNetArrayIterable<T>(T[])` factory for the five established primitive vectors, concrete
  reference arrays, and open invariant `Array<T>`. Its private `ArrayIterable<T>` view stores the
  original exact vector, so later mutations are observed, and each `iterator()` constructs a fresh
  private stdlib `ArrayIterator<T>` through ordinary compiled Kotlin code. The view preserves
  canonical Iterable covariance identity. Empty generic and supported primitive vectors now take
  the common `emptyList()` path and return the one target-stdlib EmptyList singleton; non-empty
  vectors retain the ordinary view object.
  The common `EmptyIterator : ListIterator<Nothing>` and `EmptyList : List<Nothing>` shapes are
  compiled as ordinary Kotlin objects in `Kotlin.Stdlib`, through the same object and generic-
  interface bridge lowerings as user code. All `emptyList<T>()` views are therefore one canonical
  reference, not `EmptyList<T>` constructions or adapters. EmptyList implements the public inert
  Kotlin `RandomAccess` marker and the internal inert `kotlin.io.Serializable` target marker; the
  latter deliberately does not claim the BCL serialization protocol.
  The first top-level collection implementations are the `Iterable<T>` and `List<T>` overloads of
  `first()` and `last()` in
  `Kotlin.Stdlib`. Calls use real generic method instantiations on
  `Kotlin.Collections.CollectionsKt`, not intrinsic copies of the algorithms in each user
  assembly. The Iterable overloads retain the common runtime List dispatch; Lists use indexed
  access without calling `iterator()`, including after primitive widening, while other Iterables
  use the universal iterator algorithm. Empty Lists throw the exact runtime-owned
  NoSuchElementException with `List is empty.`; other empty Iterables use `Collection is empty.`
  Open invariant `Array<T>.iterator()` passes its exact `!n[]`/`!!n[]` vector through the generic
  factory instantiated at `!n`/`!!n`; canonical Next narrows through
  `unbox.any !n`/`!!n`. The array mapper still rejects `Array<T?>`, projections, concrete
  primitive-element generic arrays, and nested arrays before the intrinsic runs. The contract is
  Kotlin-owned: imported CLR generic interfaces,
  `IEnumerable<T>`/`IEnumerator<T>`, and any future foreign variance views remain a separate interop
  decision and may not alter these canonical identities. STAYS REJECTED, loudly: interface
  bodies, primitive-specialized iterator subclasses, collection/sequence iteration, mutable
  iterators, and CLR enumeration adapters. Pins: `ilText/arrayIterators.kt`, `box/arrayIterators.kt`,
  `ilText/iterables.kt`, `box/iterables.kt`, `ilText/listIterators.kt`,
  `box/listIterators.kt`, and the iterator-family negatives in
  `ilText/genericArraysRejected.kt`.
- Read-only Collection ABI candidate (the first stdlib validation of
  `docs/decisions/draft-adr-variant-interface-abi.md`): source `Collection<out E>` uses canonical
  `[Kotlin.Runtime]Kotlin.Collections.Collection` for Kotlin identity, covariant
  `Collection<E>` for the CLR-valid `Size`, `IsEmpty`, `GetIterator`, and `ContainsAll` members,
  and invariant `Collection__KotlinExact<E>` for typed `Contains(E)`. The exact view inherits the
  declared view and every newly compiled Kotlin implementation supplies canonical plus exact on
  the same object. All Kotlin storage and variance conversions remain canonical reference copies.
  The canonical input slot is deliberately named `ContainsErased(object)`, not
  `Contains(object)`: an inherited same-named instance member would prevent C# from considering a
  future typed extension helper. Exact calls probe `Collection__KotlinExact<E>`; declaration-safe
  calls probe `Collection<E>`; every miss uses the canonical slot.
  `Collection.contains` uses common `SpecialBridgeMethods` policy. Its canonical bridge tests the
  implementation parameter's actual runtime shape before narrowing and returns `false` for a
  mismatch, including primitive, reference, nullable, and open `T` implementations. This is not an
  `@UnsafeVariance` rule: an ordinary user unsafe member still casts/unboxes and can throw. The
  generated open-parameter test uses `isinst !n` plus a boxed `default(!n)` nullability test, a
  shape probed on modern CoreCLR and .NET Framework. `containsAll(Collection<E>)` carries canonical
  Collection physically because nested Kotlin-owned split interfaces never promise a typed
  capability. Imported CLR collections remain outside this mapping. Pins: `ilText/collections.kt`,
  `box/collections.kt`, `DotNetLibraryIntegrationTest.testGenericInterfacesAcrossLibraryBoundary`,
  and `testForeignGenericInterfaceBarriers`. The latter compiles one ordinary C# exact-view
  implementor against a portable Kotlin library and executes it with both profile runtimes; it
  also proves that a user `@UnsafeVariance` interface retains normal CLR cast failure rather than
  receiving the collection barrier.
- Read-only List ABI candidate: source `List<out E>` extends the corresponding Collection views.
  Canonical `[Kotlin.Runtime]Kotlin.Collections.List` owns object-shaped `Get`,
  `IndexOfErased(object)`, `LastIndexOfErased(object)`, canonical nested `GetListIterator` and
  `SubList` results, and the members List redeclares from Collection. Covariant `List<E>` owns
  typed `Get(int): E` plus the declaration-variance-safe members. Invariant
  `List__KotlinExact<E>` extends both `List<E>` and `Collection__KotlinExact<E>` and owns typed
  `Contains(E)`, `IndexOf(E)`, and `LastIndexOf(E)`. Every view is the same object; `List<Int>` to
  `List<Any?>` is an instruction-free canonical reference copy. A widened primitive call may miss
  the closed capability and use the canonical bridge, but it never allocates an adapter.
  The erased search names deliberately leave `Contains`, `IndexOf`, and `LastIndexOf` available
  for typed capabilities and future C# helpers. Common `SpecialBridgeMethods` policy returns
  `false` for a wrong-shaped canonical Contains argument and `-1` for wrong-shaped IndexOf or
  LastIndexOf; this policy does not apply to arbitrary user `@UnsafeVariance` members. Nested
  ListIterator/List returns remain canonical because capability presence is not universal. Pins:
  `ilText/lists.kt`, `box/lists.kt`, and
  `DotNetLibraryIntegrationTest.testGenericInterfacesAcrossLibraryBoundary` (separate KLIB/DLL
  consumer plus raw CLR calls).
- Bottom-type carrier: both `Nothing` and `Nothing?` map physically to the public sealed,
  private-constructor reference class `[Kotlin.Runtime]Kotlin.Nothing`, the CLR counterpart of
  JVM `java.lang.Void` and Native/Wasm's uninstantiable class. Nullability remains Kotlin
  metadata; legal `Nothing?` widening is an explicit codegen coercion and never a claim that the
  carrier is CLR-assignable to every reference/value type. Plain `Nothing` calls are followed by
  the shared mature-backend `KotlinNothingValueException` guard, emitted as an inline throw of the
  runtime-owned exception if foreign or malformed code returns. This carrier is what permits
  ordinary `ListIterator<Nothing>`/`List<Nothing>` typed capabilities without using `System.Void`
  or collapsing them to `object`.
- Kotlin `Unit` is not an IL value type. CLR `void` is only a return encoding; Unit-returning
  functions are emitted as `void`, and `IMPLICIT_COERCION_TO_UNIT` discards values with `pop`.
  The erased callable `Invoke` boundary is the one exception: it materializes
  `Kotlin.Unit.INSTANCE` into its mandatory `object` result slot.
- Local `val`/`var` follows the JVM/WASM model conceptually: the method context maps IR value
  symbols to slots. CLR keeps argument slots (`ldarg`) separate from `.locals init` slots
  (`ldloc`/`stloc`).
- `if`/`when` follows JVM/WASM `IrWhen` handling: evaluate conditions, `brfalse` to next branch,
  `br` to the end label after a matched branch.
- Primitive-array model (`docs/decisions/draft-adr-kotlin-primitive-array-wrappers.md`; original
  probe series `arrprobe_s1`; JVM intrinsic-registry precedent and backend.common
  `IndexedGetLoopHeader` loop precedent): the five element types already supported as scalar
  values (`Int`, `Long`, `Double`, `Boolean`, `Char`) use Kotlin-owned sealed reference wrappers
  (`Kotlin.IntArray`, and peers) around private CLR zero-based vector storage. Canonical Kotlin ABI
  always names the wrapper, preserving the nominal, overload, reflection, and identity distinction
  from `Array<Int>`, which naturally specializes to `int32[]` (and likewise for the other scalar
  types). Nullable and non-null primitive arrays share the wrapper representation; `===`, null
  checks, and identity-based `==` use `ceq`, while `contentEquals` and related content operations
  explicitly consume the backing vector. Widening to `Any`/`Any?` preserves wrapper identity.
  Explicit C# export facades alone project a primitive-array boundary to its natural CLR vector,
  without copying; the canonical Kotlin declaration remains wrapper-typed. Each wrapper type owns
  a `ConditionalWeakTable<vector, wrapper>` used only at exported boundaries. Inbound projection
  retrieves or creates the stable live wrapper for a vector, and outbound projection registers the
  canonical wrapper before returning storage. Thus duplicate arguments, later calls, and
  wrapper-vector-wrapper round trips preserve `===` without permanently retaining dead arrays.
  A monitor protects compound lookup/create/register operations on Framework and CoreCLR; ordinary
  Kotlin construction bypasses the table. `arrayinternprobe_s1` assembles and executes the exact
  generic CWT/monitor IL with Framework and modern ILAsm/runtimes. The runtime wrapper
  constructor/access/storage methods are marked public compiler ABI rather than source API.
  The registry owns the builtin surface: unary size constructors allocate a guarded vector and
  wrap it; literal `intArrayOf`/`longArrayOf`/`doubleArrayOf`/`booleanArrayOf`/`charArrayOf`
  initialize vector storage in source order and wrap once; `size`, `get`, and `set` call the typed
  wrapper ABI. Both modern and .NET Framework assemblers accept the wrapper/vector signatures and
  exact element instructions. CoreCLR and Framework both throw `System.IndexOutOfRangeException`
  for a vector bounds failure, which is already the mapped `IndexOutOfBoundsException`. Both
  instead throw `System.OverflowException` for a negative `newarr` length; exposing that raw fault
  would wrongly make `catch (ArithmeticException)` catch a Kotlin negative-array-size failure.
  Constructors therefore branch on a negative size and throw compiler-owned
  `Kotlin.NegativeArraySizeException : Kotlin.RuntimeException`. Common Kotlin promises the
  RuntimeException parent but exposes no portable source class; JVM's exact child is a Java
  platform type. The CLR child is consequently metadata-public for generated consumers but absent
  from the fake stdlib and source mapping. It preserves Exception/Throwable, the future exact
  RuntimeException edge, a null default message, and no arithmetic/argument/state edge.
  `negativearray_s1` validates both ILAsm implementations and all four runtime pairings.
  Direct `for (x in array)` iteration is lowered without iterator allocation: evaluate the array
  expression once into `indexedObject`, initialize `inductionVariable = 0`, cache immutable
  `last = indexedObject.size`, then `while (inductionVariable < last)` load
  `indexedObject[inductionVariable]`, increment before the user body, and run the body. Increment
  before the body preserves `continue`, and the lowering retargets every `break`/`continue` from
  the removed iterator loop. Explicit escaping iterator values use the erased runtime iterator
  ABI described above. STAYS REJECTED, loudly: `ByteArray`/`ShortArray`/`FloatArray` (scalar
  elements are unsupported), and unsigned arrays.
  The literal/get/set temporaries are mandatory for general expressions: CLR protected
  regions require an empty stack at entry, so an element/index/value containing `try` cannot be
  evaluated with vector/index operands left underneath it. Pins: `ilText/primitiveArrays.kt`,
  `ilText/primitiveArraysRejected.kt`; runtime: `box/primitiveArrays.kt`.
- Generic-array model (current POC; the primitive-substitution restriction is superseded by
  `docs/decisions/draft-adr-kotlin-primitive-array-wrappers.md`; probe series `genarrayprobe_s1`;
  JVM `IrIntrinsicMethods.arrayMethods`
  registry precedent plus the same backend.common indexed-loop shape as primitive arrays): an
  invariant Kotlin `Array<E>` maps structurally to a CLR zero-based vector when `E` is a supported
  reference-shaped type or an open `!n`/`!!n` parameter. Outer nullability is erased because the
  vector is itself a reference. Backend assignability remains EXACT and invariant even though CLR
  arrays are covariant; this follows the JVM precedent (Kotlin's type checker prevents source
  widening, while an invalid store supplied through an external covariant view fails with the
  runtime's store check). Both CoreCLR and Framework throw `ArrayTypeMismatchException` for that
  probe shape. Concrete supported primitive elements are permitted: `Array<Int>` is `int32[]`
  while `IntArray` is the nominal `Kotlin.IntArray` wrapper. Concrete nullable value elements such
  as `Array<Int?>` remain rejected until their representation and boxing semantics are implemented.
  An OPEN `Array<T>` remains supported because its declaration is `!0[]`/`!!0[]`; CLR generic arity
  and token identity keep the ABI distinct and reify a value-type instantiation safely.
  `genarrayprobe_s1` reflection confirms the open element in metadata, and both runtimes execute
  `newarr`, `ldelem`, and `stelem` with `!n`/`!!n` tokens for reference and value instantiations.
  Known string/class/instantiated-generic elements use the same typed-token instructions. The
  registry owns `arrayOf`, `emptyArray`, reference-element `arrayOfNulls`, `size`, `get`, and
  `set`; allocation/store operands spill exactly like primitive arrays, and dynamic sizes share
  the negative-size guard. Direct `for` iteration shares the indexed lowering. Array identity
  equality/null tests use `ceq`, and widening to `Any`/`Any?` is instruction-free. STAYS REJECTED,
  loudly: ordinary use-site projections/star projections (the concrete vararg-only normalization
  below is the sole exception; never erase Kotlin invariance into CLR covariance), concrete
  primitive/nullable-primitive elements, `Array<T?>`, nested/jagged arrays
  including arrays of primitive arrays, array casts/type checks,
  resized/open-generic copying, and content APIs other than the shallow `contentEquals` slice
  below. Concrete reference-array iterator values use the erased runtime iterator ABI above.
  Pins: `ilText/genericArrays.kt`, `ilText/genericArraysRejected.kt`; runtime:
  `box/genericArrays.kt`.
- Concrete array initializer constructors reuse the same backend.common
  `ArrayConstructorLowering` as JVM/JS/Wasm/Native; IL emission has no initializer-specific path.
  The phase runs while rich direct lambdas/references can still be inlined, before generated
  callable classes and closure conversion. A non-direct function value or explicit concrete class
  implementing `(Int) -> E` uses the unchanged erased `Kotlin.Function1.Invoke(object)` ABI. The
  common indexed fill loop evaluates the size and then any callable/bound-value expression before
  performing one existing guarded allocation, and invokes/stores in ascending index order. Even a
  `Nothing`-typed initializer retains the preceding size evaluation. Its inlined returnable blocks
  go through the mature `ReturnableBlockTransformer`, so local returns become ordinary blocks or
  `do/while(false)` plus `break`, not a new IL control-flow case. The common loop retains
  `Int.inc()` for mature built-ins and falls back to the equivalent `Int.plus(1)` only for a minimal
  built-ins surface without that member; concrete invokable-class arguments derive their result
  from their actual `invoke` declaration instead of assuming the receiver itself is a parameterized
  function-interface type.
  The five supported primitive vectors and concrete reference, nullable-reference, user-class,
  and instantiated-generic `Array<E>` families preserve zero/negative sizes, size/initializer
  single evaluation, captures, local returns, and exception timing. `ilText/arrayInitializers.kt`
  and `box/arrayInitializers.kt` pin direct/local lambdas, direct/local references, function-typed
  parameters, and explicit callable objects; modern 10.0.9 and Framework 4.8 ILAsm accept the
  exact output, and both parser boxes execute with both assembler selections. STAYS REJECTED,
  loudly: initializer constructors for mapper-rejected primitive/element/array families, nested
  arrays, and open/reified `Array<T>` construction (non-reified source is rejected by the frontend;
  inline-reified generics remain outside this backend's inlining model). Negative pins remain in
  `ilText/primitiveArraysRejected.kt` and `ilText/genericArraysRejected.kt`.
- Concrete array copying (probe series `arraycopyprobe_s1`; JVM stdlib platform-operation
  precedent plus the DotNet intrinsic registry) is introduced through resolution-only external
  declarations in the temporary `kotlin.collections` source, because this POC has no real .NET
  stdlib or IR inliner yet. The registry excludes those declarations from emitted facades.
  `copyOf()` allocates the exact known vector element type and calls `System.Array.Copy`;
  `copyOf(newSize)` reuses the guarded negative-size boundary, copies
  `min(oldSize, newSize)`, and leaves CLR zero/null initialization to supply padding. The five
  supported primitive vectors and concrete reference/nullable-reference/user/instantiated-generic
  arrays therefore return fresh, independent arrays for both ordinary and resized copies.
  `copyInto` evaluates receiver, destination, and explicit arguments once in
  Kotlin source order, materializes omitted zero/size defaults from the already-spilled receiver,
  returns the exact destination object, and preserves overlapping self-copies through
  `System.Array.Copy`.
  Raw `System.Array.Copy` cannot own `copyInto` validation: both CoreCLR and Framework report
  negative indexes as `ArgumentOutOfRangeException` and oversized source/destination ranges as
  `ArgumentException`, which would expose destination failures as Kotlin
  `IllegalArgumentException`. One metadata-public compiler/runtime helper,
  `Kotlin.Runtime.Internal.Intrinsics.ArrayCopyInto(System.Array, System.Array, Int, Int, Int)`,
  validates non-negative/ordered/in-bounds source and destination ranges without overflow and
  throws `System.IndexOutOfRangeException`, the existing Kotlin `IndexOutOfBoundsException`
  mapping, before delegating the move. The helper does not erase Kotlin array invariance: the
  frontend still checks the `Array<out T>`/`Array<T>` source API and generated signatures remain
  exact vectors. A concrete `Array<String?>`-like result shares its exact reference vector with
  `Array<String>`; only open `Array<T?>` remains unrepresentable because `T` may be a value type.
  STAYS REJECTED, loudly: copying an open `Array<T>`, `copyOfRange`, mapper-rejected array families,
  and content operations other than `contentEquals`/`contentDeepEquals`. Pins: `ilText/arrayCopying.kt`,
  `box/arrayCopying.kt`, and the negative additions in `ilText/genericArraysRejected.kt`. The exact
  golden assembles under modern 10.0.9 and Framework 4.8 ILAsm; both parser boxes execute with
  modern and Framework-selected ILAsm.
- Shallow array content equality (probe series `arraycontent_s1`; JVM `java.util.Arrays.equals`
  contract plus the JS target's element-loop precedent) is a registry-owned operation exposed by
  resolution-only external `kotlin.collections.contentEquals` declarations. Array `==`/`===`
  remain identity operations. Nullable receivers and arguments compare true only when both are
  null; non-null vectors require equal lengths and Kotlin-equal elements. One metadata-public
  runtime helper traverses `System.Array` and passes each boxed element pair through the existing
  Kotlin-owned `Intrinsics.AreEqual` boundary. That deliberately avoids raw numeric `ceq` and CLR
  collection helpers: `Double` content equality canonicalizes NaNs and distinguishes signed zero,
  matching the common stdlib contract. Reference elements retain null-safe virtual Kotlin
  equality. Nested array elements remain identity-compared because this operation is shallow;
  recursive traversal belongs only to `contentDeepEquals`.
  The five supported primitive vectors, concrete/projected reference vectors, and open invariant
  `Array<T>` consumers all call the same helper without changing their exact CLR storage types.
  Receiver and argument are evaluated once in source order. The helper is internal by namespace,
  public only for cross-assembly access, and creates no new Kotlin array ABI shape.
  `arraycontent_s1` assembled and ran null, primitive, NaN, signed-zero, and nested-identity cases
  on modern CoreCLR and Framework. Pins: `ilText/arrayContentEquals.kt` and
  `box/arrayContentEquals.kt`.
- Recursive array content equality (probe series `arraydeep_s1`; common/JVM `contentDeepEquals`
  contract) is a separate registry-owned operation exposed only for nullable generic `Array`
  receivers. It keeps the outer array ABI exact, evaluates both expressions once in source order,
  and delegates traversal to one runtime-owned `ArrayContentDeepEquals(System.Array,
  System.Array)` helper. Nested reference vectors recurse; matching supported primitive-vector
  pairs use the shallow helper; other elements use `Intrinsics.AreEqual`. Mixed primitive kinds
  fail rather than acquiring CLR numeric coercion. This preserves common-stdlib null, nested-array,
  reference-equality, NaN, and signed-zero semantics for concrete/projected arrays and open
  invariant `Array<T>` consumers. Same-reference arrays return before traversal. The common stdlib
  explicitly leaves behavior undefined for self-containing arrays, so the runtime adds neither a
  cycle detector nor a stronger cross-target contract. The helper remains public only for
  cross-assembly access in the reserved internal namespace and creates no new array ABI shape.
  `arraydeep_s1` assembled and ran nested reference/primitive, mixed-kind, NaN, signed-zero, and
  null cases on modern CoreCLR and Framework; the exact golden assembles with both ILAsm versions.
  Pins: `ilText/arrayContentDeepEquals.kt` and `box/arrayContentDeepEquals.kt`.
- Shallow and recursive content hashing (probe series `arrayhash_s1`; JVM `Arrays.hashCode`/
  `deepHashCode` and common/Native 31-fold precedent) reuse one Kotlin-owned element-hash
  boundary. Nullable arrays hash to zero; non-null arrays start at one and fold each element as
  `31 * result + elementHash`, with normal unchecked Int overflow. `contentHashCode` covers the
  five supported primitive arrays plus generic arrays and deliberately gives nested arrays their
  ordinary identity hash. Generic-array `contentDeepHashCode` instead recurses into reference
  vectors and shallow-hashes each supported nested primitive vector. Both paths use
  `Intrinsics.HashCode` for scalar elements, preserving Kotlin null, Boolean, Char, Double NaN,
  and signed-zero hashes rather than CLR `GetHashCode` divergences. Open invariant `Array<T>` and
  projected/concrete reference vectors keep exact storage and call the same `System.Array` helper;
  the receiver is evaluated once. Data-class array hashing now delegates to the general shallow
  helper while its existing cross-assembly entry point stays as a compatibility wrapper. Deep
  self-containing arrays remain undefined and gain no cycle detector, matching the stdlib
  contract. `arrayhash_s1` assembled and ran on modern CoreCLR and Framework, and the exact golden
  assembles with both ILAsm versions. Pins: `ilText/arrayContentHashCode.kt` and
  `box/arrayContentHashCode.kt`.
- Shallow and recursive content rendering (probe series `arraystring_s1`; common/JVM
  `contentToString`/`contentDeepToString` contract) reuse the Kotlin-owned `StringValueOf`
  boundary so null, Boolean, Char, Double, and user `toString` semantics do not leak raw CLR
  formatting. Nullable arrays render as `"null"`; non-null arrays use List-compatible brackets
  and `", "` separators. `contentToString` covers the five supported primitive arrays plus generic
  arrays and deliberately renders nested arrays through their ordinary identity-based `toString`.
  Generic-array `contentDeepToString` recursively renders reference vectors and each supported
  primitive vector. Its Framework-compatible `ArrayList` stores only the active recursion path:
  encountering an active array appends `"[...]"`, while a shared non-cyclic child is removed after
  its branch and renders fully again later. Array identity makes `ArrayList.Contains` the correct
  path predicate; this is not an equality/content lookup. Open invariant `Array<T>` and projected/
  concrete reference vectors keep exact storage and evaluate the receiver once. Data-class array
  rendering delegates to the general shallow helper through its existing compatibility wrapper.
  `arraystring_s1` assembled and ran null, primitive, nested, repeated-child, and cyclic cases on
  modern CoreCLR and Framework; the exact golden assembles with both ILAsm versions. Pins:
  `ilText/arrayContentToString.kt` and `box/arrayContentToString.kt`. STAYS REJECTED, loudly:
  unsigned arrays and mapper-rejected array families.
- Concrete varargs follow the mature JVM/Native/Wasm lowering boundary rather than a separate
  delegate or runtime ABI. `DotNetVarargLowering` runs before closure conversion and default
  stubs, normalizes the source-only `Array<out E>` view of a CONCRETE reference vararg to the
  invariant vector ABI already used for `Array<E>`, and updates parameter/local aliases and
  captures consistently. Primitive `Int`/`Long`/`Double`/`Boolean`/`Char` varargs use their exact
  primitive vectors; supported concrete reference, nullable-reference, object, user-class, and
  instantiated-generic elements use typed reference vectors. No `ParamArrayAttribute` is emitted:
  Kotlin permits a non-final vararg and this slice is Kotlin-to-Kotlin ABI/codegen, not a public
  C# `params` export policy. Omitted non-default varargs allocate an empty vector. Expanded calls
  allocate a fresh vector; spread and ordinary expressions are evaluated once in source order,
  spread sizes are cached, and ordinary array `get`/`set` loops copy into the destination without
  aliasing the source. A vararg with its own default uses the JVM-style physical null placeholder
  plus the existing default mask. Existing no-spread `arrayOf`/supported `*ArrayOf` calls keep
  their compact literal intrinsic, while spread-bearing forms go through the general lowering.
  Top-level, member, extension, constructor, interface, local/captured, non-final, default-adjacent,
  multiple/empty-spread, evaluation/exception-order, and aliasing shapes are pinned by
  `ilText/varargs.kt` and `box/varargs.kt`; both modern 10.0.9 and Framework 4.8 ILAsm accept the
  exact output and both runtimes execute it. STAYS REJECTED, loudly: `vararg T` or an element type
  containing an open type parameter (the projected generic-array ABI remains undecided), concrete
  nullable-primitive elements, nested/array elements, and every scalar/array family the mapper
  already rejects. Negative pins remain in `ilText/genericRejected.kt`,
  `ilText/genericArraysRejected.kt`, and `ilText/primitiveArraysRejected.kt`.
- Kotlin `Any` foundation (draft ADR
  `docs/decisions/draft-adr-system-object-any-foundation.md`; probe series `dotnet-any_s1`; JVM
  `kotlin.Any -> java.lang.Object` precedent): `kotlin.Any`/`Any?` have no standalone CLR type.
  Their physical root is `[mscorlib]System.Object`, which already includes generated classes,
  strings, arrays, boxed primitives, mapped exceptions, and foreign CLR objects in one hierarchy.
  Generated overrides map Kotlin `equals`/`hashCode`/`toString` to the existing CLR
  `Equals(object)`/`GetHashCode()`/`ToString()` slots and emit `virtual` without `newslot`;
  ordinary calls dispatch through those System.Object signatures and `super` stays a non-virtual
  `call`. The cross-assembly runtime owns metadata-public, compiler-internal
  `Kotlin.Runtime.Internal.Intrinsics.AreEqual(object, object)` (null-safe, left-biased virtual
  equality), `HashCode(object)`, and `StringValueOf(object)` (`"null"` or virtual ToString).
  Primitive-aware branches are required at that boundary: both CLRs equate boxed signed zero,
  collapse its hashes, render boxed Double/Boolean with CLR text, and give boxed Char a duplicated-
  bits hash instead of Kotlin's numeric code; Framework also hashes NaN payloads differently. The
  helpers restore Kotlin/JVM object semantics (canonical Double bits, Boolean hash constants/
  lowercase text, numeric Char hashes, invariant integer text, shared Double formatting) before
  virtual fallback. Values and open type parameters box only at that universal object boundary.
  Both ILAsm implementations accept the exact runtime/library/consumer shapes, and all four
  modern/Framework consumer-dependency
  pairings run identically. STAYS REJECTED, loudly: interface redeclarations of Any members,
  unsupported data-class shapes described below, and `T : Any` generic constraints
  (CLR `class` would wrongly exclude value instantiations; erasing the constraint would admit
  null). Kotlin-owned exceptions and foreign-object import policy remain later consumers of this
  foundation. Pins:
  `ilText/inheritanceAnyOverride.kt`, `ilText/interfaceEqualityWidening.kt`,
  `ilText/nullableRejected.kt`, `ilText/genericRejected.kt`; runtime: `box/anyMembers.kt`.
- Data-class slices consume the System.Object Any foundation without adding
  another object identity or a backend-specific member generator. Fir2ir's shared
  `DataClassMembersGenerator` already supplies `equals`, `hashCode`, `toString`, `componentN`,
  and `copy` bodies. For a non-generic top-level or named nested class whose primary-constructor
  properties have supported mapped types, those bodies compile through ordinary class machinery:
  `Equals(object)` reuses the existing System.Object virtual slot and uses the checked
  `isinst`/`castclass` path above; property equality and hash/string conversion reuse the
  established Any/nullable helpers; `componentN` is a field read; and `copy` calls the primary
  constructor. No generated member may fail independently: the shape gate rejects excluded data
  classes before registration, so the class disappears whole rather than exposing a partial
  generated API.
  Defaults use the common/JVM masked-default algorithm after local declaration lifting. A generated
  function helper receives the original values plus one `int32` mask per 32 value parameters,
  tests bits with the intrinsic-registry `Int.and` -> CLR `and`, resolves omitted values with
  `starg`, and calls the original function. The same model supports ordinary top-level and
  non-interface class-member functions, including data-class `copy`. Stated CLR prototype
  deviation from the JVM's later staticization: a member helper remains a non-virtual instance
  compiler helper, so its receiver stays the ordinary CLR `this` and the original virtual call
  still owns dispatch. A default constructor stub likewise repeats the original parameters, then
  carries the masks and a final nullable runtime-owned `DefaultConstructorMarker`; omitted calls
  pass typed placeholders, the masks, and null before invoking that synthetic `.ctor`. The marker
  keeps a valid stub distinct from user constructors whose trailing `Int` parameters resemble
  masks. CLR constructor identity is still only the mapped parameter list, so the member pre-pass
  rejects a class whole when two original or generated constructors erase to one identity (for
  example `String` versus `String?`) instead of letting map insertion or ILAsm choose a survivor.
  Interface-owned argument defaults use the profile-aware `DefaultImpls` ownership model described
  in the interface section below. Mask decoding lives in a nested helper on every profile,
  independently of whether the source slot is a portable abstract slot or a net10 DIM.
  A named nested data class follows the established JVM-static-nested CLR model unchanged. It
  captures no outer instance and owns only its own type parameters, so a non-generic data class
  inside `Outer<T>` remains the independently non-generic metadata type
  `'Outer`1'/'Entry'`: its generated `isinst`/`castclass`, fields, constructor, members, and
  `copy$default` tokens carry no outer `!0`. The same shape composes below supported classes,
  interfaces, objects, companions, data classes, and deeper named metadata parents.
  A generic data class follows the CLR-specific split recorded in
  `docs/decisions/draft-adr-generic-data-class-equality.md`. Ordinary class identity, storage,
  signatures, construction, `componentN`, and copying stay the real reified `C<T>` model. Only
  compiler-generated equality needs an erased class view: each declaration owns one private,
  non-generic nested interface with an object-returning slot per primary-constructor property.
  Every `C<T>` implements those slots through private final methods plus explicit CLR MethodImpl
  `.override` entries. Generated `Equals(object)` tests that declaration-unique view, reads the
  other components through it, and compares boxed/widened values via `Intrinsics.AreEqual`.
  Different instantiations of one class therefore retain Kotlin/JVM's erased equality identity,
  while different data-class declarations remain distinct and no public runtime protocol is
  added. A user-written `equals` is left untouched and receives no view. Generic member calls also
  expose their substituted CLR result to coercion decisions: common default lowering leaves a
  `copy$default` call's IR result open as `C<T>`, but a receiver `C<Int>` really produces
  `C<Int>` and must not trigger an invalid `C<!0>`-to-`C<int32>` cast.
  A local data class composes with the common local-declaration lowering rather than introducing
  another CLR identity. Closure conversion lifts it to a private generated class and prepends
  bound receiver/value state to its constructor. Those synthetic capture parameters remain real
  fields and are propagated by constructor/default/copy paths, but are excluded from
  `componentN`, equality, hash, and text because they are not source primary-constructor
  properties. Mutable captures therefore keep their shared reference cell, while two instances
  from the same lifted declaration compare only their data properties even when their captured
  state differs. A generic local data class keeps the ordinary reified class plus its private
  erased equality view; the generic-data lowering filters `BOUND_RECEIVER_PARAMETER` and
  `BOUND_VALUE_PARAMETER` before constructing that view. Omitted generic arguments use the
  common/JVM default-injector marker rather than a data-class special case. The injector's
  `DEFAULT_VALUE` null composite is unobservable whenever its mask bit is set, but an open CLR
  type parameter cannot always carry the reference-shaped null found in that IR. Call emission
  therefore materializes the resolved parameter's physical default: zero for a known primitive,
  null for a reference, an empty `Nullable<V>`, or a synthetic local initialized with
  `initobj !n`/`!!n` for an open class/method type parameter. This shared call path covers
  functions, members, constructors, and generated `copy$default` calls while preserving explicit
  argument evaluation. It does not legalize observable `T?` in a generic declaration; the
  nullable-open-type rejection below remains unchanged.
  Array properties preserve the JVM asymmetry deliberately: generated equality remains ordinary
  array reference identity, while only hashCode/toString inspect content. Fir2ir emits its
  `dataClassArrayMemberHashCode`/`dataClassArrayMemberToString` builtins; the intrinsic registry
  maps them to compiler-internal runtime helpers accepting `System.Array`. `GetValue(int32)` boxes
  each vector element into the established `HashCode(object)`/`StringValueOf(object)` boundary, so
  all five supported primitive vectors and supported invariant reference-element `Array<E>` share
  one semantic implementation. Null arrays hash to 0 and render `null`; empty arrays hash to 1 and
  render `[]`. The same boundary exposed the CLR Char hash difference, now normalized centrally
  for ordinary Any calls as well as array content.
  A data object reuses the ordinary CLR singleton representation unchanged: a sealed class with a
  private constructor, one public static initonly `INSTANCE`, and recursive initialization through
  its `.cctor`. Fir2ir's shared generated bodies make `Equals(object)` true for every instance of
  the same declaration after the reference fast path and `isinst`/`castclass`, not merely for
  `INSTANCE`; this preserves the specified hostile-reflection/serialization behavior without a
  delegate-like wrapper or another identity ABI. `GetHashCode()` returns the compile-time
  `FqName.hashCode()` constant and `ToString()` returns the simple declaration name. Named data
  objects below classes, generic classes, objects, and deeper supported metadata parents use the
  existing static-nested model and capture no outer instance or type argument. This slice does not
  broaden the separate object-supertype boundary: a data object with a proper class/interface
  supertype is rejected by the same owning gate as an ordinary object with that supertype.
  STAYS REJECTED, whole-class: array shapes rejected by the primitive/generic vector mapper.
  Pins:
  `ilText/dataClasses.kt`, `ilText/nestedDataClasses.kt`, `ilText/dataClassArrays.kt`,
  `ilText/genericDataClasses.kt`, `ilText/genericDataClassDefaults.kt`,
  `ilText/constructorDefaultArguments.kt`, `ilText/dataObjects.kt`,
  `ilText/localDataClasses.kt`,
  `ilText/dataClassesRejected.kt`, and `ilText/defaultArgumentsRejected.kt`; runtime:
  `box/dataClasses.kt`, `box/nestedDataClasses.kt`, `box/dataClassArrays.kt`,
  `box/genericDataClasses.kt`, `box/genericDataClassArrays.kt`,
  `box/genericDataClassShapes.kt`, `box/genericDataClassMultipleTypeParameters.kt`,
  `box/genericDataClassDefaults.kt`, `box/defaultArguments.kt`,
  `box/constructorDefaultArguments.kt`, `box/dataObjects.kt`, `box/localDataClasses.kt`, and
  `box/anyMembers.kt`.
  `dataclass_s1`, `dataclass_s2`, `dataclass_array_probe_s1`, and `ctor_default_probe_s1`
  assembled the relevant shapes under modern 10.0.9 and Framework 4.8 ILAsm; the array helper
  probe ran identically on both runtimes, and all four constructor consumer/runtime ILAsm
  pairings preserved the default and real-overload paths. `generic_data_probe_s1` assembled both
  generic equality goldens with both ILAsm versions; Framework-selected boxes executed every new
  runtime shape on CoreCLR, and reflection found only private view/bridge metadata while the public
  property remained open `T`. The data-object exact golden assembles under both ILAsm versions;
  reflection over each assembly confirms the private-constructor singleton surface and that a
  second reflectively constructed instance has the same equality, declaration hash, and text.
  Both parsers execute the source pin with modern and Framework-selected ILAsm. The fresh full
  two-parser matrix is 440/0/0/0 across eight suites. The local-data exact golden likewise
  assembles under both ILAsm implementations; its runtime pin covers immutable, mutable,
  receiver, local-function, generic, array, and defaulted capture paths in both parsers and with
  Framework-selected ILAsm.
- Equality follows JVM's intrinsic-registry shape: `Int`/`Boolean` use `ceq`, `String ==` uses
  `System.String::op_Equality`, `String ===` uses reference `ceq`. On user-class instances, `===`
  and `==` against the `null` literal are a reference `ceq` (Kotlin defines `x == null` as a pure
  reference check that never calls `equals`; JVM precedent: the `Equals` intrinsic's `isNullConst`
  special case); base/derived-typed operand pairs of the inheritance model widen to the
  ancestor type — the reference `ceq` is type-agnostic, the user-class analogue of the
  mapped-exception common-supertype arm; sibling-typed pairs sharing a supertype (a common
  base class or, since the interface model, a common implemented interface — the shape a
  positive-identity smartcast routinely produces) widen to the FIRST common supertype of the
  left operand's breadth-first supertype walk (deterministic:
  `DotNetIlClassInfo.allSupertypes` — direct base class, then direct interfaces in
  declaration order, then the same per level, NOT the whole base chain first). Two FINAL
  sibling classes are not expressible operands — the frontend rejects them with
  EQUALITY_NOT_APPLICABLE (empty intersection type) — so the user-reachable sibling shape is
  two sibling INTERFACE views of an object. Pairs with no module-local common supertype (for
  example two unrelated interface views) widen instruction-free to System.Object. Identity stays
  `ceq`; structural reference/`Any?` equality calls the runtime's null-safe `AreEqual` helper.
  Open type parameters use that helper after `box !!n`; identity on open `T` remains rejected
  because value-type instantiations have no stable reference identity. Exact primitive, string,
  array, and nullable-primitive paths remain specialized. Pins:
  `ilText/interfaceEqualityWidening.kt`, `ilText/inheritanceAnyOverride.kt`; runtime:
  `box/interfaceHierarchy.kt`, `box/anyMembers.kt`.
- Nullability model (hybrid representation; probe series `boxprobe_s1`–`_s7`, `nullprobe_s8` —
  a deliberate user-decided design): a concrete nullable CLR VALUE type uses
  `System.Nullable<T>` in exact typed positions and converts to boxed-underlying-or-null when
  widened to `Any?`/object-shaped contexts; a nullable REFERENCE type is the same IL type as its
  non-null flavor (a stated no-op at the type-mapping layer — `String?`/`C?`/`I?`/mapped
  exceptions all keep their reference type, `ldnull` is the null literal, `== null` is the
  existing reference `ceq`). Precedents: Roslyn (C# `int?` IS `Nullable<int32>` in typed
  positions, CLR-collapsed at `object` boundaries) and Kotlin's own inline value classes
  (context-sensitive representation is established Kotlin policy); the JVM's blanket boxing of
  `Int?` is a platform limitation, not language policy. Generic `T?` is a SEPARATE future ABI
  problem and deliberately stays unmapped — it must not force concrete nullable primitives into
  `object`. Representation table (`DotNetIlValueType.NullableValue`; the five concrete
  primitives Int/Long/Double/Boolean/Char):
  - EXACT positions (params, returns, locals, instance/static fields, ctor params):
    `valuetype [mscorlib]System.Nullable`1<int32|int64|float64|bool|char>` — the
    `nameInSignature` doubles as the operand spelling of every instruction touching the type
    (probe-verified in every position, boxprobe_s1). `T -> T?` is
    `newobj instance void valuetype ...Nullable`1<t>::.ctor(!0)`; the null literal / empty value
    is `ldloca`+`initobj`+`ldloc` through a synthetic local (also for returns and arguments);
    null tests are `call instance bool ...::get_HasValue()`, extraction is
    `GetValueOrDefault()` — never `get_Value`, whose InvalidOperationException would surface as
    the wrong Kotlin exception, so every extraction branches on HasValue first.
  - WIDENED positions (`kotlin.Any`/`Any?` maps to CLR `object` as pure STORAGE): `T? -> Any?`
    is `box valuetype ...Nullable`1<t>`, which the CLR COLLAPSES to boxed-`T`-or-null
    (boxprobe_s3, all five instantiations nullprobe_s8); `T -> Any?` is a plain
    `box [mscorlib]System.<Boxed>`; reference types widen to `object` instruction-free
    (`isDotNetAssignableTo`'s third widening). Conversions are emitted by a widening-coercion
    layer in `DotNetIlExpressionCodegen.emitExpression` (JVM precedent: StackValue coercion —
    boxing is never an IR node) plus the IMPLICIT_CAST arms; narrowing exists only as the
    `T? -> T` smartcast IMPLICIT_CAST / narrowed-slot read, emitted as the CHECKED unwrap
    (HasValue branch + mapped-NPE throw + GetValueOrDefault — JVM parity: CHECKCAST+`intValue()`
    NPEs on null there).
  - MANDATORY SPILL RULE (boxprobe_s2): every instance-member access on a `Nullable<T>` receiver
    needs a HOME ADDRESS — a freshly computed stack value MUST be spilled `stloc`+`ldloca`; the
    unspilled call assembles cleanly but is a FATAL, uncatchable CLR error (0x80131506), a
    poison shape codegen may never emit. Spilling costs a local and zero maxstack.
  `!!`/IMPLICIT_NOTNULL (JVM precedent: the checkNotNull intrinsic, registered via
  `checkNotNullSymbol` in the intrinsic registry): references get `dup`/`brtrue` past a
  `newobj instance void [mscorlib]System.NullReferenceException::.ctor()` + `throw`
  (boxprobe_s4; message-less, like `Intrinsics.checkNotNull`), Nullable<T> gets the HasValue
  branch + same throw + extraction — catchable as `catch (e: NullPointerException)` through the
  existing registry mapping (`box/nullableNotNullThrows.kt`). Null-aware structural `==`
  (JVM precedent: the `Intrinsics.areEqual` specializations) is the Roslyn lifted-equality
  shape, NO boxing (boxprobe_s5, incl. the (none, some(0)) corner): `T? == T?` compares
  GetValueOrDefault values ANDed with HasValue flags; `T? == T`/`T == T?` AND the value `ceq`
  with the nullable side's HasValue; `T? == null` is a negated HasValue; `Double?` arrives via
  the ieee754equals symbols and the extracted-`float64` `ceq` IS the JVM's
  areEqual(Double, Double) semantics (NaN? != NaN?, -0.0? == 0.0? — runtime-pinned). A null-only
  operand (`Nothing?` maps to the `Kotlin.Nothing` carrier — the frontend narrows known-null
  when-subjects to it)
  against a plain primitive is statically false with operands still evaluated. Safe calls and
  elvis are the frontend's block+when shape — value-position `IrBlock` dispatches through the
  same statement-scope hook as `try` expressions, and a `?:` whose branches are `Int` and `Int?`
  unifies through the branch-level coercions; string templates of nullable primitives render
  through a HasValue branch selecting `"null"` or the existing per-type rendering
  (boxprobe_s7), nullable strings keep the existing dup/brtrue coalesce. A non-Unit `try`
  DISCARDED in statement position keeps the statement form when its type maps to `object`:
  branch types that merely LUB to `Any` (an Int-typed try branch next to a Unit-typed catch
  branch ending in an assignment — a routine statement shape) mix value- and statement-shaped
  branch results, and a discarded `Any` value is never materialized, so `emitDiscardedTry`
  treats `object` like an unmapped type (the exact pre-hybrid behavior; runtime-pinned by
  `box/tryDiscardedValue.kt`) — mapped NON-`object` types imply every branch result is
  value-shaped and keep the expression form. GATE INTERACTIONS
  (pinned by `ilText/nullableOverrides.kt`): `Int?`/`Int` are now DISTINCT IL types, so the
  Kotlin-covariant `Producer.value(): Int?` overridden by `value(): Int` uses an exact `int32`
  virtual slot plus a private MethodImpl bridge which wraps the result for the inherited
  `Nullable<int32>` slot. `f(Int)`/`f(Int?)` overloads become two legal IL methods, while
  `g(String)`/`g(String?)` still clash (reference nullability erases). The
  member-clash gate has a FACADE analogue (`ilText/facadeMethodClash.kt`): top-level functions
  and property accessors of one file render into one facade class, so top-level
  `g(String)`/`g(String?)` (same IL `string`), `h(Any)`/`h(Any?)` (same IL `object`) and
  `val x` vs `fun get_x()` (accessor mangling) each produce duplicate IL method declarations
  ilasm rejects; the facade gate evicts EVERY callable of a clashing identity (keeping one
  half would be an arbitrary pick between legal Kotlin overloads) at facade granularity — a
  plain function per-function, an accessor with its whole property, a backing-field-bearing
  property with the file's whole property group. The System.Object Any foundation now supports
  Any member calls, Any/string-template conversion, general structural reference equality, and
  nullable-primitive-to-Any equality by boxing to the CLR boxed-underlying-or-null boundary.
  Open `T?` is the declaration-stable boxed-or-null `object` carrier fixed by
  `docs/decisions/adr-hybrid-generic-nullability-and-covariant-returns.md`; concrete nullable
  primitives remain `Nullable<T>` and non-null `T` remains reified. This ordering precedes the
  legacy string-bound shortcut: method-level `T : String` maps `T?` to `object`, keeps non-null
  `T` as `string`, and recovers it after `!!` with `castclass`. STAYS REJECTED, loudly:
  nested open-nullable arguments of invariant reified carriers without an erased view, identity
  between two nullable-primitive values (the
  operands would box; identity of separately boxed values is unrelated to value equality — `ceq`
  on two boxed equal int32s is False, boxprobe_s6 — and Kotlin deprecates boxed identity; identity
  against null is supported as a HasValue test). Cross-primitive `Int? == Long?` needs no backend
  gate: the FRONTEND rejects it with EQUALITY_NOT_APPLICABLE, like `==` between unrelated final
  classes — not expressible in compilable Kotlin, so not pinnable in an ilText test),
  `object -> T?` narrowing (`unbox.any` accepts null and boxed T,
  boxprobe_s3, but no supported IR shape produces the cast — rejected like every downcast),
  runtime tests/casts of nullable primitive or type-parameter shapes (the canonical split-generic-
  interface reference exception is described below), `const val` of nullable type (defensive;
  frontend-rejected anyway). Exhaustive `when` without a source
  `else` over nullable or plain Boolean subjects is supported by the registered intrinsic
  described in the exception-model bullets below. Pins: `ilText/nullablePrimitives.kt`
  (every declaration-position spelling, wrap/initobj/HasValue/extraction incl. the
  address-taking `ldloca`, elvis/safe-call shapes, BOTH `!!` throw shapes — the Nullable<T>
  HasValue branch and the reference `dup`/`brtrue`/`newobj NullReferenceException`/`throw`
  spelling), `ilText/nullableEquality.kt` (every `==` sequence), `ilText/nullableBoxing.kt`
  (box-collapse widenings, `object` storage positions, templates), `ilText/nullableRejected.kt`
  (the rejection warnings' source shapes),
  `ilText/nullableOverrides.kt` (gate interactions), `ilText/facadeMethodClash.kt` (the facade
  clash gate, all three flavors); runtime: `box/nullableBasic.kt`, `box/nullableEquality.kt`,
  `box/nullableBoxing.kt`, `box/nullableNotNullThrows.kt`, `box/nullableObjectMembers.kt`
  (nullable primitives through the object/companion singleton and static-initializer
  machinery), `box/tryDiscardedValue.kt` (the discarded Any-LUB try statement shape).
- Class model (JVM precedent: the CLR has real classes, so like `JvmLoweringPhases` there is NO
  vtable/class lowering machinery): top-level plain classes — final, open, abstract, or sealed;
  non-generic or, since the generics model (below), with reified type parameters — plus the
  named nested, inner, and lifted local types described below, and, since the interface/generics
  models (below), top-level or named nested non-generic/generic all-abstract interfaces pass the shape gate
  (`DotNetIlEmitter.checkClassShapeSupported` / `checkInterfaceShapeSupported`); objects and
  companions stay final-only with sole supertype `kotlin.Any`.
  Rejection granularity is the failing class's metadata subtree — a failing member (signature,
  body, or IL method- or field-identity clash) removes its owning class and every recursively
  nested declaration, because CLR nested metadata cannot outlive its parent. A failing ordinary
  nested class does NOT remove independent enclosing classes or siblings. The live type/function
  maps then cascade to actual users of the removed class. Companion failures are owner-sensitive:
  the singleton field and `.cctor` live on the immediate enclosing type, so that owner subtree is
  the minimum safe eviction boundary.
- Named nested-type model (probe series `nestedprobe_s1`–`_s4`, `nestedifaceprobe_s1`–`_s3`,
  `nestedownerprobe_s1`–`_s2`;
  JVM precedent: a Kotlin named nested declaration is static unless it is an `inner` class,
  represented by JVM `ACC_STATIC`; the CLR analogue is a real nested metadata type): a final,
  open, abstract, or sealed plain `ClassKind.CLASS`, an all-abstract interface, or a final named
  object declared directly inside any supported named class, interface, object, or companion
  passes the shape gate recursively. Each nested declaration owns no outer instance and only its
  OWN generic parameters; a named class inside generic `Outer<T>` is therefore
  referenced as `'Outer`1'/'Nested'` with no outer instantiation, while an independently generic
  nested class is `'Outer`1'/'Nested`1'<U>`. Arbitrary depth composes by slash-separated quoted
  simple names. A companion of a non-generic plain class or interface is also supported.
  `DotNetStaticInitializersLowering`
  matches the common/JVM `ClassLoweringPass.runOnFilePostfix` recursion: it visits every class
  child before its metadata parent, moving a named object's `INSTANCE` initializer into the
  object's own `.cctor` and a companion initializer into its immediate owner's `.cctor`. A
  companion's immediate class/interface container must be non-generic because its field would be
  per constructed generic owner. An interface owner may legally carry that static singleton field
  and `.cctor` while retaining the interface flag rule that omits `beforefieldinit`
  (`nestedifaceprobe_s2`). Named objects may recursively contain named classes/objects, and a
  named class below an object/companion may own its own companion. A named object's `INSTANCE`
  field is owned by the independently
  non-generic object type, so it stays singular even under an immediately generic metadata parent
  (`nestedprobe_s4`, `nestedownerprobe_s1`).
  Registration and type/member resolution use a separate `DotNetIlClassInfo` per declaration, but
  rendering remains recursive inside the enclosing `.class` block. Public/private/internal/
  protected source visibility maps to `nested public`/`nested private`/`nested assembly`/
  `nested family`. Modality follows the top-level class model after that prefix: final carries CLR
  `sealed`, open omits it, and abstract/sealed Kotlin classes carry CLR `abstract`. A top-level or
  nested class may extend any module-local top-level or nested class, including a forward sibling,
  its metadata parent, a deeper family member, an independently generic instantiation under a
  generic outer, or a class in another top-level family. A class may implement, and an interface
  may extend, any recursively declared module-local interface. All
  spellings, construction, member/field references, virtual/interface dispatch, forward links,
  cross-depth links, singleton initialization, and family shapes assemble and run on CoreCLR and
  Framework. The CLR grants nested→enclosing private access, but not the reverse; the Kotlin
  frontend does not allow an enclosing declaration to call a private MEMBER of an ordinary nested
  class, while a private nested TYPE's public constructor remains callable by its enclosing type
  (`nestedprobe_s2`, `nestedownerprobe_s2`). Companion-private members retain their established
  IL-`assembly` widening. A failure OF a companion remains owner-sensitive because its field and
  `.cctor`
  live on the immediate owner; a separate failing child below a valid companion is its own
  metadata subtree. STAYS REJECTED, per rejected metadata subtree: value/enum/annotation classes,
  data objects and the data-class shapes excluded by the bounded data-class model above, an
  `inner` class whose immediate outer is generic, and a companion whose immediate class/interface
  container is generic. Named local classes and anonymous object expressions follow their
  separate closure-converted model below.
  Recursive render failures preserve the deepest declaration tag while
  unwinding, then subtree eviction removes that declaration and its descendants; independent
  metadata ancestors/siblings survive and live-map re-rendering removes only real dependents.
  Pins: `ilText/nestedClasses.kt`,
  `ilText/nestedInheritance.kt`, `ilText/nestedSingletons.kt`,
  `ilText/nestedInterfaces.kt`, `ilText/nestedInterfacesRejected.kt`,
  `ilText/nestedObjectDeclarations.kt`, `ilText/nestedObjectDeclarationsRejected.kt`,
  `ilText/nestedClassesRejected.kt`; runtime: `box/nestedClasses.kt`,
  `box/nestedInheritance.kt`, `box/nestedSingletons.kt`, `box/nestedInterfaces.kt`,
  `box/nestedObjectDeclarations.kt`.
- Inner-class model (probe series `innerprobe_s1`–`_s2`, `genericinner_s1`–`_s3`; common/JVM
  precedent): a Kotlin `inner` class uses the common backend's explicit-outer representation on
  real CLR nested metadata. `DotNetInnerClassesLowering` adds one private
  `this$0` field and replaces each constructor's dispatch receiver with a leading regular outer
  argument; `DotNetInnerClassesMemberBodyLowering` rewrites outer-`this` reads into field chains;
  `DotNetInnerClassConstructorCallsLowering` moves the source call's dispatch receiver into that
  argument. All three run before `DotNetInitializersLowering`, matching the common/JVM pipeline.
  Both CoreCLR 10.0.9 and Framework 4.8 accept the common lowering's `stfld this$0` before the
  base `.ctor` call (`innerprobe_s1`); no CLR-specific reorder is needed. Each inner subclass owns
  its own outer field and forwards the same outer argument to an inner base constructor. Arbitrary
  inner depth composes through field chains, and an inner class may own independent generic
  parameters. When the immediate outer is generic, `DotNetInnerClassTypeParametersLowering` first
  appends copies of its COMPLETE parameter list after the inner's own parameters and remaps the
  inner subtree: `Outer<T>.First<U>.Second<V>` owns `Second<V, U, T>`. Processing is outer-first,
  so each deeper level copies an already-complete immediate-outer space. FIR use-site types already
  carry that `own, outer...` argument order. The retained copy map types the later synthetic outer
  field/constructor parameter, missing constructor/super-call arguments are expanded before the
  common call rewrite, and synthetic multi-level field reads substitute through their receiver.
  Non-generic inners take none of these generic-only repairs. Duplicate copied/own parameter names
  are legal positional CLR metadata; generic inner inheritance and generic non-inner bases retain
  their fully substituted links (`genericinner_s2`–`_s3`). Primary constructors, classes with only
  secondary constructors, and delegating secondary constructors all preserve the outer argument;
  a delegating constructor lets its target perform the single outer-field store. Source visibility
  uses the existing nested-type mapping, and an unsupported inner member evicts only that inner
  metadata subtree and real users.
  Pins: `ilText/innerClasses.kt`, `ilText/genericInnerClasses.kt`,
  `ilText/classShapeRejected.kt`, `ilText/nestedClassesRejected.kt`; runtime:
  `box/innerClasses.kt` and `box/genericInnerClasses.kt`.
- Local-class and anonymous-object model (probe series `localprobe_s1`–`_s2` and
  `anonprobe_s1`–`_s2`; common/JVM precedent): the DotNet wrappers run
  `InventNamesForLocalClasses`, `DotNetAnonymousObjectSuperConstructorLowering`,
  `InventNamesForLocalFunctions`, callable-reference lowering, `SharedVariablesLowering`,
  `LocalDeclarationsLowering`, and
  `LocalDeclarationPopupLowering` before inner classes and initializer merging. Closure conversion
  handles named local classes, anonymous objects, explicit named local functions, and lowered
  lambda/function-reference callable classes in the same phase order used by the common/JVM
  pipeline. A local in
  a top-level function or property becomes a module-private top-level CLR type; a local in a member
  or initializer becomes a private nested CLR type. Constructors are widened to public metadata
  inside that inaccessible type so the facade/enclosing type can instantiate them
  (`localprobe_s1`–`_s2`, `anonprobe_s1`); source visibility is unchanged because the type itself is
  inaccessible.
  Immutable parameters, locals, and receivers become explicit constructor parameters and private
  fields when a member body needs storage. Captured type parameters are duplicated on the local
  type by the common lowering, so a local below `Outer<T>` owns an independent `!n` space and its
  captured receiver is typed as `Outer<!n>` (`localprobe_s2`). Own and captured type parameters,
  local inheritance/interface dispatch, initializer-local classes, and multiple same-named locals
  compose through the existing class/generic model. Invented names use the enclosing JVM-style
  path plus a per-base-name collision counter (`$1` only when needed); registration gives user
  metadata names priority and defensively disambiguates a colliding local.
  Anonymous object expressions use the same metadata and capture model: bare `Any` objects,
  supported interfaces, and supported module-local class supertypes are valid, including generic
  bases and recursively nested object expressions. Each expression constructs a fresh instance;
  it does NOT enter the named-object singleton lowering. Mirroring the JVM phase, complex and
  named/reordered base-constructor arguments move to temporaries at the expression call site and
  become explicit constructor parameters before closure conversion. This preserves source
  evaluation order relative to object initializers while the constructor separately receives
  immutable captures (`anonprobe_s2`). Captured type parameters are duplicated and substituted in
  the anonymous base link and lifted parameter types.
  Explicit local functions become static methods on the nearest CLR metadata owner
  (`localfunprobe_s1`–`_s3`). A file-facade local is `assembly` because a lifted sibling class/object
  may call it; a class-owned local is `private`, which CLR nested→enclosing access permits. Value,
  extension-receiver, and dispatch-receiver captures become ordinary parameters. Captured type
  parameters are duplicated into the method's independent `!!n` space before its own parameters;
  a static local under a generic class still uses an INSTANTIATED owner token, derived from a
  captured owner argument or the caller's open `Owner<!n>` view. Direct/nested recursion,
  initializer locals, extensions, named-class/anonymous-object callers, and generic function/class
  scopes compose. Common name invention supplies readable paths; the metadata gates reserve user
  methods/accessors first and append the smallest `$n` suffix only to a colliding generated local.
  Shared-variable lowering rewrites captured mutable locals to one invariant runtime cell before
  closure conversion, preventing value copies from breaking aliasing; a raw mutable capture that
  somehow survives that phase is still rejected defensively. Crossinline, inline, and suspend
  locals likewise stay rejected without their respective lowering models. Unsupported anonymous
  supertypes reject that metadata subtree and real users through
  the ordinary class gates. Pins: `ilText/localClasses.kt`, `ilText/localClassesRejected.kt`,
  `ilText/anonymousObjects.kt`, `ilText/anonymousObjectsRejected.kt`, `ilText/localFunctions.kt`,
  and `ilText/localFunctionCallables.kt`; runtime: `box/localClasses.kt`,
  `box/anonymousObjects.kt`, and `box/localFunctions.kt`.
- Inheritance/abstract-class model (probe series `inheritprobe_s1`–`_s3`,
  `abstractprobe_s1`–`_s2`, `nestedprobe_s3`; JVM precedent: real CLR classes = real platform
  inheritance, no vtable lowering — the same argument as the class-model bullet): a top-level,
  named nested, or lifted local plain class may be `open` (drops `sealed` from the `.class` flags), `abstract`, or
  `sealed` (both emit ordinary CLR
  `abstract`, never CLR `sealed`). A sealed class's source-protected constructor is emitted
  `famandassem`: same-assembly Kotlin subclasses remain legal, while foreign C# subclasses cannot
  construct the base. A source-private sealed constructor remains CLR private. This enforces the
  class hierarchy at its only construction boundary without misusing CLR `sealed`, and is pinned
  by Framework C# positive/negative compilation. Such a class may extend EXACTLY ONE base class when the supertype
  resolves to another recursively declared class of the compiled module. The gate checks only the
  structural shape; whether the base itself compiles is
  re-resolved from the live class map at the top of every render round, so a failing base
  cascades whole-class down the chain, each derived class warned with a reason carrying the
  base's reason (the chain analogue of the companion pair warnings) — pinned by
  `ilText/inheritanceBaseEvicted.kt`. Member flags (order probe-verified; ilasm treats them as
  an unordered keyword set, the emitter standardizes on the s2 spellings): an `open` member
  introducing a slot is `hidebysig newslot virtual` (`specialname newslot virtual` for
  accessors); a new abstract function/accessor is `newslot abstract virtual` with an empty body;
  an abstract or concrete Kotlin `override` REUSES a base-class slot — `abstract virtual` or
  `virtual` with NO `newslot`; and a `final override` is `virtual final` (still dispatching under
  `callvirt`, the Roslyn `sealed override` shape). An abstract member implementing only an
  interface uses `newslot abstract virtual`. An abstract class may instead carry a pure abstract
  interface obligation only as a fake override and emit NO method; a concrete descendant then
  introduces or reuses the implementing slot (`abstractprobe_s2`, accepted and dispatching on
  both runtimes). An `open` member of a FINAL class stays non-virtual (nothing can override it;
  `isDotNetVirtual` is the single predicate both the declaration flags and the call sites
  consult). Constructors, concrete state, companions, generic owners/methods, constraints, and
  base/interface links reuse their existing machinery unchanged. CALL SITES: virtual callees use
  `callvirt` — a stated widening of the established
  call-for-final deviation from Roslyn: final members keep the plain null-check-free `call`,
  but virtual dispatch has no non-virtual substitute, and `callvirt` with the operand token
  naming the DECLARING class dispatches correctly even through base-typed values and to final
  overrides (probe-verified). Calls through fake overrides (inherited members referenced via
  the derived class) resolve to the real declaration first (`resolveFakeOverride`, the
  `findSuperDeclaration` analogue), so the operand names the declaring class — valid for both
  `call` (inherited final member, derived receiver) and `callvirt`. A `super.f()` call
  (`IrCall.superQualifierSymbol != null`) is a plain non-virtual `call` on the resolved target
  — the CLR runs the base implementation with the `this` receiver, exactly the JVM's
  invokevirtual/invokespecial split (probe-verified from inside both plain and final
  overrides). Constructor chaining to the base reuses the delegating-call shape (`ldarg.0`,
  args, `call instance void 'Base'::.ctor(...)`) — identical IL for `this(...)` and base
  delegation. UPCASTS are pure reference widenings needing NO instruction (probe-verified:
  base-typed locals, parameters, returns): `isDotNetAssignableTo` walks the
  `DotNetIlClassInfo.baseType` chain (linked in a pre-pass AFTER all registrations — forward
  references are legal IL and legal Kotlin), and expression-position `IMPLICIT_CAST` that is
  such an upcast emits just its operand. A runtime type test against a non-generic module class
  boxes/widens its operand to object, uses `isinst class 'C'`, then compares the returned
  reference with null (`cgt.un` for `is`, `ceq` for `!is`); the checked IMPLICIT_CAST produced by
  its positive smartcast is `castclass class 'C'`. A Kotlin-owned split generic interface uses
  that same classifier-only rule: every concrete argument, use-site projection, and star maps to
  its non-generic canonical identity for `is`/`!is`, `as`, and `as?`; nullable `is D<*>?` handles
  null before `isinst`, non-null hard casts add the mapped-NPE guard which CLR `castclass` lacks,
  and safe casts are one `isinst` yielding canonical-or-null. This follows JVM raw-descriptor,
  JS interface-symbol, and Native TypeInfo semantics. Declared/exact capability siblings never
  decide a Kotlin cast. Reified generic classes and foreign CLR generic interfaces are deliberately
  not broadened to this path: a closed `C<T>` token would strengthen Kotlin's erased check, while
  foreign interfaces retain CLR construction/variance rules. IMPLICIT_NOTNULL keeps its
  established checked nullability path. Explicit casts outside the canonical split-interface
  slice, value-type tests, and every other non-upcast IMPLICIT_CAST stay rejected loudly. STAYS
  REJECTED, whole-class: exception supertypes (existing message; interface supertypes are
  SUPPORTED since the interface model, see its bullet), out-of-module bases,
  objects/companions with any supertype, and data-object plus unsupported data-class generated-
  member shapes. Covariant-return overrides are supported by the uniform floor-compatible design
  in `docs/decisions/adr-hybrid-generic-nullability-and-covariant-returns.md`: the precise method
  is a distinct virtual slot when its direct class slot has a different mapped return, and a
  private final MethodImpl bridge adapts that immediate wider class slot
  (`ilText/covariantClassReturns.kt`, `box/covariantReturns.kt`). Inherited bridge chains route
  transitive class slots; interface slots remain explicit. Same-carrier `String?`-to-`String`
  covariance reuses the ordinary base slot. Overrides of `kotlin.Any`
  (`toString`/`equals`/`hashCode`) are supported by mapping to System.Object's reused virtual
  slots, pinned by `ilText/inheritanceAnyOverride.kt` and `box/anyMembers.kt`; detection walks
  `allOverridden()` against the TYPE-based `isAny`, because
  `IrClass.isAny`/`findOverriddenMethodOfAny` compare IdSignatures and this pipeline's symbols
  carry none. Kotlin `protected` methods render as CLR `family`; this follows the JVM access
  boundary while allowing compiler/runtime subclasses in other assemblies to reach protected
  hooks without making them public. End-to-end: `box/inheritanceBasic.kt` (three-level
  chain: polymorphic dispatch through base-typed values, super chains, final override,
  inherited state/methods, upcast positions) and `box/inheritanceInitOrder.kt` (base init runs
  before derived init; `beforefieldinit` semantics unchanged — instance init order is a
  constructor-chain property, not a `.cctor` one). Abstract metadata, new/reused slots, explicit
  and fake interface obligations, generic methods/constraints, sealed-class dispatch, companion
  construction, and constructor state are pinned by `ilText/abstractClasses.kt` and
  `box/abstractClasses.kt`; `ilText/classShapeRejected.kt` retains the neighboring variance and
  nested-class rejection boundaries.
- Interface model (probe series `ifaceprobe_s1`–`_s10`, `genifaceprobe_s1`,
  `genmemberprobe_s1`, `ifaceredeclareprobe_s1`, `delegationprobe_s1`,
  `nestedifaceprobe_s1`–`_s3`; JVM precedent: real CLR
  interface types = no vtable/interface lowering, the same argument as the class and inheritance
  bullets): a
  top-level or named nested Kotlin `interface` whose callable members are ALL abstract (abstract
  functions and abstract `val`/`var` properties; empty interfaces included) is emitted as
  `.class interface public abstract auto ansi` — no `extends` line, no `sealed`, no
  `beforefieldinit` (s1), or with the corresponding `nested public/private/assembly/family`
  accessibility prefix (`nestedifaceprobe_s1`–`_s3`). A `sealed interface` is deliberately
  ACCEPTED and emitted as the same plain interface. Unlike a class, an interface has no
  constructor boundary at which `famandassem` can enforce Kotlin sealing, and CLR metadata has no
  permitted-implementor table. Kotlin compilation still enforces the sealed hierarchy, but C# or
  other foreign CLR code can implement the interface; exhaustive Kotlin code must retain its
  ordinary no-branch failure behavior for such a foreign value. This is a deliberate CLR
  interoperability limitation, not a claim that the metadata is sealed (pinned initially by
  `ilText/interfaceEqualityWidening.kt`; a foreign-implementor runtime test remains required).
  Abstract members are
  `.method public hidebysig [specialname ]newslot abstract virtual instance ... cil managed`
  with an EMPTY `{ }` block (s1/s2; the emitter keeps its established specialname-first flag
  order — ilasm treats the flags as an unordered keyword set); abstract accessors are bound by
  ordinary `.property` blocks targeting the interface's own accessor methods (s2). An abstract
  redeclaration of an inherited function/accessor deliberately introduces another
  `newslot abstract virtual` slot; one exact-signature class member implicitly fills the original
  and every redeclared slot, including diamonds and open/composed generic views
  (`ifaceredeclareprobe_s1`). Calls name the interface that owns the selected declaration.
  A mapped covariant-return redeclaration introduces a distinct abstract CLR slot. Each concrete
  implementing class receives explicit MethodImpl adapters for every wider slot it satisfies, so
  Kotlin override resolution does not depend on CLR implicit matching. KOTLIN-OWNED
  GENERIC interfaces use the split ABI in
  `docs/decisions/draft-adr-variant-interface-abi.md`: non-generic `Producer` is the sole Kotlin
  storage/cast identity and owns deterministic erased slots; declaration-variant
  `'Producer`1'<+ 'T'> : Producer` is the same object's C#-friendly typed capability; and an
  all-invariant exact sibling is emitted only when member placement or a superedge requires it.
  The emitter reserves the canonical, declared, and optional exact TypeDef identities as one
  atomic physical-name set. A non-local collision evicts the offending logical declaration; a
  published library treats any such eviction as fatal. Declared- and exact-view members are
  independently clash-checked after their physical signature mapping, with return types excluded
  from CLR overload identity. `testLibraryPublicationFailsWhenADeclarationIsEvicted` pins a
  property/user-accessor clash on each typed view, a distinct inherited method and local property
  accessor colliding on the declared view, and a user TypeDef occupying a generated exact name;
  all four produce diagnostics and no KLIB/DLL pair. The inherited gate follows the emitted
  physical capability graph and exempts a genuine Kotlin override. Whole-declaration rejection is
  temporary until stable typed-slot disambiguation is implemented.
  Declaration-site `out`/`in` remains `+`/`-` metadata on the declared sibling, invariant
  parameters stay unmarked, and direct supported constraints compose as
  `<+ (class 'Base') 'T'>` (genifaceprobe_s1). All concrete Kotlin implementations receive
  explicit canonical and typed MethodImpl bridges to one source body. A generic or non-generic
  class may implement an open or closed typed capability, while Kotlin ABI fields, parameters,
  returns, projections, and stars use canonical identity. A generic interface member may
  independently declare method parameters (`!!n`), including supported constraints; generated
  bridges copy those method parameters and forward their type arguments (`genericMembers`).
  NESTING uses the JVM static-nested model: an interface may appear inside
  any supported named class, interface, object, or companion, and may itself contain named
  classes, interfaces, objects, and a companion. Every child has an independent generic-parameter
  space; a generic interface's named object is safe because `INSTANCE` lives on the independently
  non-generic object type. A non-generic interface's companion singleton field and `.cctor` live
  on the interface owner and assemble/run on both runtimes. A generic interface companion is
  rejected because CLR statics are per constructed owner (`nestedifaceprobe_s2`/`_s3`). A class
  lists its DIRECT physical interfaces comma-separated on an `implements` line after `extends`
  (`extends 'Base'` / `implements 'A', 'B'`, s3). A Kotlin-owned generic logical edge expands to
  canonical identity plus its most-specific representable declared/exact capability, e.g.
  `implements 'Producer', class 'Producer`1'<!0>`; a typed capability's owner token is always the
  full construction (`genifaceprobe_s1`). Interface-extends-interface uses the same physical
  `implements` list, and transitively implied super-interfaces are never repeated (s6). MEMBER
  FLAGS: `isDotNetVirtual` widens — every interface member and
  every member overriding one is virtual even in a FINAL class, because a non-virtual
  implementation assembles cleanly but load-poisons the type (TypeLoadException at first JIT of
  a using method, s1b). A Kotlin override of ONLY interface members introduces a fresh class
  slot: `newslot virtual`, `newslot virtual final` for a Kotlin-`final` member (the Roslyn
  implicit-implementation shape); an override of a base-class member keeps the slot-reuse
  spelling `virtual` (no newslot) even when it also implements an interface — CLR interface
  mapping follows the class vtable slot (s4). Stated deviation from Roslyn: `final` follows
  Kotlin modality alone, so an implicit implementation in a final class whose member is not
  `final override` stays `newslot virtual` where Roslyn would seal it — s4 shows the non-final
  spelling dispatches identically (goldens + box pin it). CALL SITES: interface-typed receivers
  always use `callvirt` with the operand naming the DECLARING interface — `resolveFakeOverride`
  handles inherited members, with a maybe-abstract fallback because the plain resolution
  ignores abstract targets (a fake override whose only real declarations are abstract interface
  members resolves to null otherwise); naming a sub-interface that merely inherits the member
  is a runtime MissingMethodException (s6) — NO fake-override leniency, unlike the class-side
  rule. One class member implicitly fills every same-signature interface slot (the
  diamond/merge shape, s9). For an ordinary non-generic interface, a base-class member satisfying
  a derived class's interface works iff the inherited member is VIRTUAL (s5a — Kotlin
  fake-override semantics for free) AND its mapped IL signature matches the interface slot
  EXACTLY, return type included: ECMA-335 interface mapping matches the full signature. The
  non-virtual variant is gated whole-class at compile time with a message citing s5b, while the
  inherited covariant-return variant (`class Combo : Factory(), Maker` where
  `Factory.make(): Bottom` fills `Maker.make(): Top`) receives a private final MethodImpl adapter
  for the wider interface slot. The non-virtual poison shape assembles without an ilasm diagnostic
  and throws TypeLoadException at first JIT; the covariant shape is executable through both class
  and interface views (`ilText/interfaceNonVirtualImplRejected.kt`,
  `ilText/covariantInterfaceReturns.kt`, and `box/covariantReturns.kt`). Same-IL-type Kotlin
  covariance (`String?` filled by an inherited `String` member) needs no adapter. Split
  generic interfaces are the exception: their lowering always emits explicit canonical and typed
  MethodImpl bridges, including the boxing/widening bridge needed for refined returns. UPCASTS:
  class→interface and
  interface→super-interface are free reference widenings in every position (field, parameter,
  return, local — s7); `isDotNetAssignableTo` walks the supertype DAG (`baseType` plus physical
  `interfaces` links, BFS with substitution/dedup — diamonds are legal). Two logical
  instantiations of one Kotlin-owned generic interface use the same canonical CLR identity in
  Kotlin ABI positions. Thus reference, value, open, and use-site-projected conversions—including
  `Producer<Int> -> Producer<Any>`—are instruction-free copies preserving `===`; only a transient
  typed call probe depends on a closed declared/exact construction. Imported CLR interfaces keep
  CLR reference-only variance, and generic classes remain invariant.
  The reference `ceq` is type-agnostic across interface-typed and class-typed views (s7; sibling
  widening — see the equality bullet).
  INTERFACE DELEGATION follows the JVM frontend-owned model: FIR supplies ordinary forwarding
  functions/accessors with origin `DELEGATED_MEMBER`, and codegen renders their existing bodies
  through the normal member path. A constructor-property delegate uses that property's private
  backing field; a plain parameter, expression, bounded type parameter, or `var` delegate gets a
  separate private instance field with origin `DELEGATE` (`$$delegate_n`). The shared
  `InitializersLowering` merges its initializer into the constructor after the base-constructor
  call and before later member initializers, preserving JVM evaluation order and one-time capture
  (reassigning a `var` property does not retarget forwarding). Calls use the delegate expression's
  exact static owner — interface, concrete class, generic instantiation, or constrained `!n` — so
  existing `callvirt`/`constrained.` machinery handles dispatch without a delegation-specific
  call emitter. Explicit overrides suppress only their corresponding forwarding member; multiple
  delegates, mutable properties, generic owners/methods, inherited interface redeclarations, and
  a delegated member overriding an inherited virtual class member compose with the existing slot
  model. The private-field/forwarding shape and generic method dispatch assemble and run on
  CoreCLR 10.0.9 and Framework 4.8 (`delegationprobe_s1`); both FIR source spellings, the exact IL,
  runtime capture/order/dispatch, and an evicted-interface cascade are pinned by
  `ilText/interfaceDelegation.kt`, `box/interfaceDelegation.kt`, and
  `ilText/interfaceDelegationRejected.kt`.
  INTERFACE ARGUMENT DEFAULTS use a producer-owned masked dispatcher in the compiler-reserved
  nested `<DefaultImpls>` class. The static `$default` dispatcher takes the interface receiver as
  its first ordinary parameter, evaluates defaults and masks in source order, and calls the
  interface slot through `callvirt`, so the implementing override still owns dispatch on every
  profile. Common Kotlin forbids omitted arguments in a super call
  (`SUPER_CALL_WITH_DEFAULT_PARAMETERS`); .NET does not publish a second dispatcher for that
  invalid construct, and lowering must not reinterpret escaped malformed IR as an ordinary virtual
  call. `DotNetExpressionCheckers` registers the existing FIR checker through the metadata session
  factory's real DotNet platform branch; the target must not impersonate JVM merely to inherit the
  diagnostic. The helper is not a Kotlin source declaration or independent callable identity;
  separately compiled consumers bind its physical name from structured ABI metadata. A generic
  interface's owner parameters become invariant generic METHOD parameters on every helper method
  (including copied constraints); the non-generic nested helper therefore captures no enclosing
  CLR construction. Calls derive those arguments from the receiver's instantiated interface view,
  then append ordinary method arguments. Direct class/interface, inherited, constrained,
  delegated, variant, bounded, generic-method, member-extension, and nested-interface shapes all
  share that path. `interfacedefaultprobe_s1` assembled and ran the generic nested-helper shape
  under modern 10.0.9 and Framework 4.8 ILAsm. Pins: `ilText/interfaceDefaultArguments.kt`,
  `box/interfaceDefaultArguments.kt`, `ilText/defaultArgumentsRejected.kt`, and
  `ilText/interfaceDefaultBodiesPortable.kt`.
  EVICTION: an evicted interface cascades whole-class to every
  implementing class and every sub-interface — the `implements` list is re-resolved from the
  LIVE class map at the top of every render round with chained reasons, the interface arm of
  the base-class cascade (pinned by `ilText/interfaceEvicted.kt`); evicting an implementer never
  affects the interface. A rejected nested declaration removes only its metadata subtree and real
  dependents; the interface parent and independent siblings survive.
  PROFILE-AWARE INTERFACE DEFAULTS follow
  `docs/decisions/adr-profile-aware-interface-default-implementations.md`. `net48` and
  `netstandard2.0` emit abstract CLR slots, one marked public compiler-ABI
  `<DefaultImpls>` body, and physically required hidden class MethodImpl forwarders.
  `net10.0` emits one canonical DIM body and retains the same helper identity for portable
  compatibility and exact calls. Qualified `super<I>.f()` always lowers to exactly `I`'s helper:
  portable helpers own the moved body, while modern helpers use a plain nonvirtual `call` to
  their owning DIM. Ordinary calls remain virtual. External helper identities, derived-interface
  promotions, covariant-return MethodImpls, and hidden class MethodImpls are consumed from the
  structured physical ABI; never
  infer them from target profile or generated names. A class inherits an existing MethodImpl for
  the same selected default, but receives a resolver bridge when an ancestor-default MethodImpl
  would mask a more-specific Kotlin choice.
  External masked default-argument bindings follow the recorded logical dispatch shape. Instance
  members copy the selected declaring interface receiver and declaration-level type parameters
  before method type parameters; physically static companion and top-level declarations copy no
  false dispatch context. A mismatch between the IR stub receiver and `isInstance` record is an
  internal compiler error, never a guessed helper signature.
  GENERIC INTERFACE DEFAULTS preserve one logical member, one canonical semantic body, and one
  stable helper ABI identity across erased, declared-variance, and exact CLR views. On portable
  profiles the helper owns the body. On `net10.0` one strongly typed DIM owns it; the exact view
  is the normal strongly typed C# surface, and less-precise views virtually adapt to that DIM.
  Helpers select it nonvirtually. No view adapter, promotion, class bridge, or class forwarder owns
  an independently lowered body. A concrete non-generic net10 interface override owns one complete
  final canonical/declared/exact adapter bundle whose bodies dispatch virtually to its DIM.
  Implementors inherit that bundle, including across assemblies: unpublished physical ABI schema
  13 retains each adapter as a structured `B` entry keyed by owner, inherited logical member, and
  physical view (the entry was introduced in schema 12). Never rediscover it from generated
  declarations, method names, or IL text.
  A generic capability call resolves its canonical fallback name from the bound physical function
  record before deriving a Kotlin-owned hash. Runtime and external split interfaces may retain
  established CLR names such as `MoveNext`, `Current`, or `Next`; inventing a hashed fallback for
  such a member produces valid-looking IL that fails lazily with `MissingMethodException`.
  Owner-relative method constraints remain logical KLIB constraints
  but are omitted from executable CLR views: CLR variant types reject them at load time and an
  invariant exact-DIM constraint rejects valid widened Kotlin calls. Exact arguments/results remain
  typed, all other representable constraints remain physical, and any future constrained C# facade
  is an export adapter rather than a Kotlin dispatch slot.
  STAYS REJECTED, loudly, whole-interface/whole-class: private callable interface members,
  companions on generic interfaces, `fun interface` (no SAM-conversion model),
  interfaces imported from arbitrary CLR metadata without a bound Kotlin KLIB/physical-ABI index,
  and local/anonymous interfaces,
  explicit `as`/`as?` on non-generic interfaces and on foreign/reified CLR generic interface
  constructions (Kotlin-owned split generic interfaces instead cast only their canonical identity
  as described above), and interface members redeclaring `kotlin.Any` members (the System.Object
  class slots exist, but the exact CLR interface contract/MethodImpl policy is not yet audited).
  Failure-mode calibration: every interface-mapping mistake (missing virtual, wrong operand
  interface) assembles CLEAN and fails only lazily at runtime, so box coverage is mandatory per
  dispatch shape — `box/interfaceBasic.kt` (dispatch through interface-typed values, abstract property
  access, multiple interfaces + base class, final override, derived override via interface
  dispatch, the s5a inherited-member shape, interface-typed fields/returns, identity and
  null checks) and `box/interfaceHierarchy.kt` (inherited members through sub-interface
  receivers, the diamond, super-interface widening, sibling-interface identity); goldens
  `ilText/interfaces.kt` (including the `newslot virtual final` / `specialname newslot virtual
  final` spellings of a Kotlin `final override` implementing only interface members) and
  `ilText/interfaceHierarchy.kt` pin every new spelling,
  `ilText/interfaceNonVirtualImplRejected.kt` pins the s5b gate,
  `ilText/interfaceCovariantImplRejected.kt` the s10 gate; nested metadata, visibility, generic
  independence, interface-owned singleton initialization, dispatch, forward references, and
  narrow rejection are pinned by `ilText/nestedInterfaces.kt`,
  `ilText/nestedInterfacesRejected.kt`, and `box/nestedInterfaces.kt`.
  Sibling widening, System.Object fallback for unrelated interface views, and sealed-interface
  acceptance are pinned by `ilText/interfaceEqualityWidening.kt`. Abstract redeclaration metadata,
  owner-token dispatch, mutable properties, generic composition, diamonds, and mapped-covariance
  rejection are pinned by `ilText/interfaceRedeclarations.kt`,
  `ilText/interfaceRedeclarationsRejected.kt`, and `box/interfaceRedeclarations.kt`. Generic
  metadata, open/closed/permuted edges,
  owner-token dispatch, reference/value variance, canonical erased casts/type tests, nullable
  cast semantics, and same-object identity are pinned by `ilText/genericInterfaces.kt` and
  `box/genericInterfaces.kt`; `ilText/genericInterfacesRejected.kt` pins the remaining open-`T?`
  slot boundary, unsupported instantiated interface bounds, capability edges involving an evicted
  argument type, and the rejection of a strengthened closed generic-class cast.
- Properties use the CLR's first-class property model: private backing fields, `get_x`/`set_x`
  `specialname` accessor methods, and a `.property` metadata block binding them (spellings
  ilasm-probe-verified) — a stated deviation from the JVM's `PropertiesLowering`, which the CLR
  makes unnecessary. Because of the accessor mangling, the member pre-pass rejects (whole-class)
  IL method-identity clashes such as `val x` vs a user-declared `fun get_x(): Int` — ilasm fails
  on the duplicate method declaration (probed on 10.0.9); the JVM analogue is the frontend
  `PLATFORM_DECLARATION_CLASH` diagnostic for `val x` vs `fun getX()`. The same identity gate
  runs over each file facade's top-level callables, at facade granularity (see the nullability
  bullet's facade analogue; pinned by `ilText/facadeMethodClash.kt`).
- Instance members of the final-class model are invoked with plain non-virtual `call`
  (probe-verified) — a stated deviation from Roslyn, which emits `callvirt` purely for its
  implicit null check.
- Top-level properties follow the JVM facade-statics shape (`StaticInitializersLowering`'s
  `<clinit>`): `DotNetStaticInitializersLowering` moves the backing-field initializers of each
  file into one synthetic file-parented `<clinit>` function, in declaration order, which the
  emitter renders as the facade's `.cctor`; the fields become `private static` facade fields
  (accessed with `ldsfld`/`stsfld`) with static `get_x`/`set_x` accessors and static `.property`
  blocks — all spellings probe-verified (`statprobe_s1`/`_s2`, including a user-class-typed
  static field). Stated deviation from the JVM lowering: it is a `ClassLoweringPass` over the
  facade class `FileClassLowering` created earlier, while this backend builds facades at
  emission time, so the pass is per-`IrFile` and the `<clinit>` is file-parented. The `.cctor`
  is lowered IR (not emission-time text) so initializer bodies pass through the later
  `for`-loop/string-concat phases like any other body.
- `beforefieldinit` is omitted exactly on classes that receive a `.cctor` and kept everywhere
  else. Decider (probe): with the flag the CLR defers the `.cctor` past static method calls, so
  calling only a top-level *function* of a facade would silently skip the file's
  property-initializer side effects; without it the `.cctor` runs before the first active use
  (`statprobe_s1`: `cctor-start` prints before `main-start`) = Kotlin/JVM first-active-use
  class-initialization parity, pinned end-to-end by `box/topLevelPropertyInitOrder.kt`.
  Accepted JVM-shared delta: a re-entrant initialization cycle observes default field values
  (CLR `.cctor` re-entrancy = JVM `<clinit>` behavior) — documented, not enforced.
- `const val` is a CLR `literal` field (`.field public static literal <t> 'C' = <literal>`) —
  the ConstantValue-attribute analogue of the JVM's `constantValue()` exclusion from `<clinit>`
  and `JvmPropertiesLowering`'s `!isConst` accessor suppression: no accessors, no `.property`
  block, no `.cctor` entry; every read is inlined by the frontend (golden-verified), and an
  exotic surviving accessor call fails loudly via the `availableFunctions` miss. All literal
  spellings probe-verified (`statprobe_s1`/`_s3`: int32, string incl. the `bytearray` fallback,
  bool, char, int64 incl. MIN_VALUE, float64 decimal and raw-bit forms). A `literal` field has
  no storage, so codegen rejects any direct backing-field access.
- Failing-initializer granularity is the whole per-file property group: declaration-order init
  interleaving cannot be partially preserved, so a failure anywhere in a file's `<clinit>` (or
  in any backing-field-bearing property) removes ALL backing-field-bearing top-level properties
  of that file together — fields, accessors, `.property` blocks and the `.cctor` — each warned
  with a shared reason carrying the original one. This is the facade-stateful analogue of the
  whole-class rejection granularity. Accessor-only (custom-getter) properties fail per-function;
  const `literal` fields are independent of the group.
- Plain top-level `object` declarations and named objects inside plain classes follow
  the JVM singleton model: `DotNetObjectClassLowering` synthesizes a static `INSTANCE` field on
  every supported module-declared non-companion object (origin
  `FIELD_FOR_OBJECT_INSTANCE`, the object's own type, initializer = a call to the primary
  constructor, which is private from the frontend) and rewrites every `IrGetObjectValue`
  targeting a module-declared object into an `IrGetField(INSTANCE)`; `kotlin.Unit` is guarded
  FIRST (the existing no-op/rejection paths stay authoritative) and out-of-module objects stay
  untouched for the existing loud failures. Stated packaging deviation: the JVM's three
  cooperating pieces (`ObjectClassLowering`, `SingletonReferencesLowering`, and the
  field-creation slice of `CachedFieldsForObjectInstances`) are merged into this one module pass
  — no intermediate producers of singleton references exist in this backend.
  `DotNetStaticInitializersLowering` recursively sweeps static class fields (today exactly
  `INSTANCE`) into a class-parented `<clinit>` rendered as the class's `.cctor` — that slice
  matches the JVM `ClassLoweringPass` postfix precedent even more directly than the facade slice.
  The IL shape is
  probe-verified (objprobe_s1): `.field public static initonly class 'C' 'INSTANCE'`, a private
  `.ctor` (`.ctor` visibility now follows the Kotlin declaration; a public one would let other
  .NET code mint second instances) `newobj`'d from the same class's own `.cctor`, and use sites
  are a bare `ldsfld` + the existing plain `call instance`. A bare object reference in statement
  position is `ldsfld` + `pop`, never a no-op — Kotlin makes it a first-active-use trigger, and
  the `.cctor` fires on a bare cross-class `ldsfld` (objprobe_s2). `beforefieldinit` is dropped
  on object classes by the existing decider, and the parity argument is exact here: every object
  use is an `ldsfld`, so CLR first-active-use semantics equal Kotlin/JVM object initialization
  (`box/objectInitOrder.kt` pins laziness + declaration order end-to-end). Non-const object
  state stays on the instance, initialized by the merged private constructor — a stated
  deviation from the JVM's static-state hoist (`MoveOrCopyCompanionObjectFieldsLowering` makes
  every object-parented property field static), which the CLR-side real singleton makes
  unnecessary. Rejections ride the existing gates, whole-class: `data object` (its generated
  singleton equality/hash/string contract remains unaudited), local named objects, named
  objects inside an object/companion/interface, and IL accessor-identity clashes; `==` between
  objects stays rejected while `===` works via
  the existing reference `ceq`. The member pre-pass additionally gates IL FIELD-identity
  clashes, whole-class: the backing field of a user property named `INSTANCE` whose type maps to
  the object's own class (`val INSTANCE: A? = null` — nullability erases) collides with the
  synthesized singleton field in both name and field signature (staticness/visibility are flags,
  not identity), which ilasm rejects as a duplicate field declaration (probed on 10.0.9,
  fieldprobe); the identity key is name + mapped IL type, so a differently-typed `INSTANCE`
  property is a legal CLR shape and stays supported (same probe; pinned by
  `ilText/objectInstanceFieldClash.kt`). Stated deviation from the JVM backend, which RENAMES
  the clashing private backing field (`RenameFieldsLowering` → `INSTANCE$1`): this backend has
  no field-renaming machinery, so the clash is rejected like the accessor-identity clash.
- `const val` in an `object` is the same CLR `literal` field as on a facade, emitted on the
  object class (coexistence of `literal` fields with a `.cctor` and an `initonly` field on one
  class is probe-verified, objprobe_s9a): no accessors, no `.property` block, no `.cctor` entry,
  reads inlined by the frontend, exotic surviving accessor calls fail via the availableFunctions
  miss. One JVM-precedented slice of the static-state hoist IS needed for this:
  `DotNetInitializersLowering` marks object-parented const backing fields static before the
  shared initializer merge (the JVM does it in `MoveOrCopyCompanionObjectFieldsLowering` /
  `JvmCachedDeclarations.getStaticBackingField`), because the shared merge's `!isStatic` filter
  would otherwise copy the const initializer into the constructor as a write to a storage-less
  `literal` field.
- Object probe deltas, documented not enforced: CoreCLR 10 did NOT enforce `initonly` — an
  outside `stsfld` to the INSTANCE field succeeded at runtime (objprobe_s3), so `initonly` is
  declarative metadata only, kept for JVM-`final` parity of intent; self-reference during
  initialization observes a null INSTANCE (objprobe_s4 — the same `.cctor` re-entrancy delta the
  JVM shares, see the `beforefieldinit` bullet); a mutual A↔B object cycle resolves without
  deadlock, the first-touched object's constructor sees the other fully initialized while the
  other sees null (objprobe_s5 — exact JVM parity; only the acyclic shape is box-tested,
  `box/objectCrossReference.kt`).
- `companion object`s are real CLR nested types: `.class nested public auto ansi sealed` inside
  the enclosing class's body (spelling probe-verified in every operand position — field type,
  `newobj`, `ldsfld`/`stsfld`, `call`, method param/return signatures, `.locals` — as
  `'demo.Outer'/'Companion'`, slash OUTSIDE the quoted identifiers, enclosing name first;
  objprobe_s6; the spelling lives in exactly one place, `DotNetIlClassInfo.ilTypeRef`). The
  singleton field lives on the ENCLOSING class, named after the companion (default `Companion`;
  NAMED companions keep their own name — JVM precedent:
  `CachedFieldsForObjectInstances.getFieldForObjectInstance`'s not-mapped-companion branch
  parents the field to `singleton.parent`), and the enclosing class's `.cctor` does the
  `newobj`/`stsfld` — so the enclosing class drops `beforefieldinit` while the companion itself
  has NO `.cctor` and keeps it. Init-order parity is preserved: every companion access goes
  through the field on the enclosing class and triggers ITS `.cctor` (touching a companion
  initializes the enclosing class, like the JVM), and the enclosing `.cctor` runs exactly once
  before the first `newobj` of the enclosing class or first companion access (objprobe_s8,
  pinned end-to-end by `box/companionInitOrder.kt`). Stated deviation from the JVM's
  `MoveOrCopyCompanionObjectFieldsLowering`: companion backing fields and `init {}` blocks are
  NOT hoisted to the enclosing class — they stay on the companion instance, compiled by the
  unchanged existing machinery (`DotNetInitializersLowering` merges them into the companion's
  constructor), and no `RemapObjectFieldAccesses` analogue exists; the JVM hoist serves
  JVM-ABI/interop needs that the CLR's real nested type makes moot. Accepted delta (documented,
  not enforced): companion state initializes in the companion constructor invoked FROM the
  enclosing `.cctor` rather than as enclosing-class statics — indistinguishable except under
  initialization re-entrancy, the already-documented delta. VISIBILITY (probe-decided): the CLR
  grants nested→enclosing private access (objprobe_s7a) but NOT enclosing→nested — an IL-private
  companion `.ctor` `newobj`'d from the enclosing `.cctor` throws TypeInitializationException
  wrapping MethodAccessException, and a throwing `.cctor` permanently poisons the type
  (objprobe_s7b); an isolated enclosing→nested private field read throws FieldAccessException
  (objprobe_s7c). Kotlin-private companion constructors, methods, and property accessors therefore
  remain IL `private`; after object lowering, `DotNetPrivateNestedAccessLowering` uses the
  common KLIB synthetic-accessor generator to redirect only calls that cross out of the
  companion's CLR nesting subtree. The same phase redirects an enclosing read of a private nested
  object's private `INSTANCE` field through a generated getter; this includes compiler-generated
  callable/delegate singletons. The source field stays private. The generated `access$...` method,
  field getter, or constructor overload is
  narrowed from common-public to Kotlin `internal`, hence IL `assembly`. Constructor accessors
  carry a final nullable `SyntheticConstructorMarker`, distinct from the default-argument marker.
  This preserves source reflection and minimizes physical exposure while accommodating the CLR's
  asymmetric nesting rule (pinned end-to-end by `box/companionPrivateAccess.kt`, both directions
  and every member-kind slice; the exact private-source/assembly-bridge shape is pinned by
  `ilText/companionObject.kt`). `const val` in a companion is a `literal` field on the NESTED class (literal
  fields on a nested class probe-verified, objprobe_s9b), the same no-copy-to-enclosing
  deviation as objects. The enclosing type and its companion are separate `availableClasses`
  entries (the companion needs its own identity for type mapping and member resolution), but a
  companion failure removes its immediate owner subtree — the singleton field on the enclosing
  type is typed as the companion and the enclosing `.cctor` news it. Metadata ancestors and
  siblings outside that owner remain independent. The warnings attribute a
  failure to the declaration that actually failed: a
  companion failure surfaces out of the enclosing type's render (the companion renders only
  recursively inside it), so the render fixpoint re-tags it with the companion
  (`DotNetIlUnsupportedClassException`) before evicting. `box/nestedSingletons.kt` and
  `box/propertyReferences.kt` execute the two singleton-field cases on CoreCLR; their IL goldens
  pin the private field plus assembly bridge. A separate invalid declaration nested
  below an otherwise valid companion is not companion state and is therefore omitted as its own
  subtree while the companion and owner survive. Companion eviction is pinned in both
  phases: the member pre-pass by `ilText/companionMemberClash.kt`, the render fixpoint (a
  companion member body failing only after its callee's round-one eviction, plus the extra
  round that re-fails an already-rendered user of the evicted companion owner) by
  `ilText/companionFixpointEviction.kt`. The companion gate accepts the direct companion of any
  non-generic plain class or interface, including an ordinary named nested class, recursively
  validated with the same constraint chain (sole supertype `Any`, final, non-generic, not data).
  A companion may contain static-style named classes, interfaces, and objects; each is validated
  and evicted independently. The recursive postfix static-initializer sweep puts the companion
  field initialization on that actual owner (`nestedprobe_s4`, pinned by
  `ilText/nestedSingletons.kt` and
  `box/nestedSingletons.kt`). For an interface companion, that field and `.cctor` live on the
  interface owner (`nestedifaceprobe_s2`, `ilText/nestedInterfaces.kt`). A companion whose
  immediate owner is generic remains rejected, and a companion inside an `object` cannot reach
  the gate (frontend-rejected).
  The companion singleton field participates in the ENCLOSING type's field-identity gate, but
  the colliding source shape (a user property named after the companion) is itself a frontend
  REDECLARATION — a companion also occupies the value namespace — so that slice is
  defense-in-depth; a same-named FIELD legally coexists with the nested TYPE (objprobe_s6,
  pinned by `ilText/companionObject.kt`). Companions are nested, not top-level, so they reserve
  no file-facade name (`buildFileClassNames` only seeds top-level classes — verified unchanged).
- Top-level extension properties emit their accessors as plain static methods with the receiver
  as a regular parameter but NO `.property` block: a CLR property with parameters is an indexer,
  which is out of scope.
- No top-level declaration is dropped silently: after gathering (classes, functions, properties
  — delegated and lateinit properties are rejected with specific messages), a closing sweep
  warns about any remaining top-level declaration kind. Typealiases are deliberately ignored
  WITHOUT a warning (the JVM backend emits no bytecode for typealiases either). The injected
  stdlib declarations stay exempt: `println`/`val Char.code` are intrinsic-excluded
  (`excludesDeclarationFromCodegen`, now also honored for property accessors) and the exception
  classes are registry-excluded.
- Initializer merging is `DotNetInitializersLowering`/`DotNetInitializersCleanupLowering`, the
  same one-line subclasses of the shared backend.common lowerings the JVM uses, plus a
  .NET-specific guard that turns the shared lowering's local-class `AssertionError` into the
  fail-loud diagnostic. The guard covers exactly what the shared lowering merges — non-static,
  class-parented fields and `init {}` blocks — never top-level property initializers, which no
  constructor merge can reach. The pair runs BEFORE `DotNetForLoopLowering` — a stated deviation
  from the JVM phase order, because the loop rewrite's builder only exists inside functions, so a
  `for` inside `init {}` must already have been inlined into a constructor.
- User-class type mapping is emission-scoped: one `DotNetIlTypeMapper` over the emitter's live
  `availableClasses` map per `DotNetIlEmitter.emit` call, no global class registry — removing a
  class during the render fixpoint automatically fails every declaration whose types mention it.
- File facade names are precomputed pre-gate (`DotNetIlEmitter.buildFileClassNames`): every
  declared top-level class reserves its IL name even when it is later skipped, so facade naming
  depends only on what the module declares, never on which classes survive support gates.
  Injected resolution-only stdlib declarations and the executable stdlib implementation are
  excepted — they are not user-module declarations and reserve no user facade name
  (`DotNetMappedExceptions.isExceptionStdlibDeclaration` and the emitter ownership scope filter
  them out).
- The bootstrap stdlib (`DotNetStdlibSource`) is a map of injected source files, one per package.
  Resolution-only declarations such as `println`, `Char.code`, and array operations are filtered
  through the intrinsic/exception registries and never emitted into a facade. The ordinary Kotlin
  `ArrayIterator<T>`/`ArrayIterable<T>` declarations and the Iterable/List `first()`/`last()` bodies are
  different: STDLIB-scoped emission owns them and USER-scoped emission excludes them, yielding the
  classes and stable `Kotlin.Collections.CollectionsKt` facade only in `Kotlin.Stdlib.dll`. USER
  codegen's stdlib operation intrinsics select those external generic methods; they do not inline
  the bodies. Injected declarations must compile without any diagnostics, including warnings: the
  FIR test infrastructure maps every reported diagnostic back to a test file and crashes on
  diagnostics in injected files (suppress e.g. deprecations locally).
  Metadata-only KLIB dependencies use the existing FIR metadata-library path. Imported built-in
  collection classifiers may have minimal external IR owners whose declaration-side type-parameter
  list is unpopulated, so runtime identity recognition uses the stable FqName and validates arity
  on the actual `IrSimpleType` use site. The target stdlib is accepted as executable only when its
  manifest binds the assembly name, version, neutral culture, null public-key token, file, and
  runtime target to the reserved artifact; the compiler then packages the sibling DLL for an
  executable consumer. This is not yet general physical-member metadata for arbitrary libraries.
- Classified CLR exception model (decision:
  `docs/decisions/draft-adr-classified-clr-exception-model.md`; the hybrid ADR is superseded):
  every catchable value remains its original `System.Exception` object. The mapping registry owns
  separate physical-carrier, construction/subclass-base, exact-catch, and logical-classifier roles;
  do not collapse those roles back into one CLR type map.
  `Throwable`, `Exception`, and broad logical categories use `System.Exception` at physical
  boundaries. Narrow logical catches and type tests use the single runtime-owned, side-effect-free
  `Kotlin.Runtime.Internal.ExceptionClassifier.IsKotlinExceptionInstance` predicate. Catch filters
  preserve Kotlin source order and classification without wrapping foreign objects. Unknown C#
  exception subclasses classify as Kotlin `Exception`; mapped CLR program faults additionally
  classify under their Kotlin categories, and fatal CLR faults classify as `Error` without becoming
  Kotlin `Exception`.
  Exact Kotlin-owned classes remain ordinary CLR exception classes where physical identity is part
  of the contract. `Kotlin.RuntimeException : System.Exception` and `Kotlin.Error :
  System.Exception` are truthful roots for Kotlin-owned subclasses; exact mapped children such as
  `Kotlin.NumberFormatException : System.ArgumentException` retain the required Kotlin parent edge.
  User exception inheritance, reflection ancestry, typed exact catches, and cross-module
  subclassing follow those physical chains. Exact roots supplement the foreign exception universe;
  they never replace it.
  Curated BCL mappings keep native CLR division, null, bounds, cast, state, cancellation, and
  argument faults catchable under the corresponding Kotlin types: `IllegalArgumentException` ->
  `ArgumentException`; `IllegalStateException` uses the shared `System.Exception` carrier,
  constructs `InvalidOperationException`, and classifies both that type and cancellation children;
  `UnsupportedOperationException` -> `NotSupportedException`, `ArithmeticException` ->
  `ArithmeticException`, `IndexOutOfBoundsException` -> `IndexOutOfRangeException`,
  `NullPointerException` -> `NullReferenceException`, and `ClassCastException` ->
  `InvalidCastException`. Kotlin metadata retains the logical signature type where the physical
  carrier is broader. A callable with a non-dispatch parameter that directly or transitively
  contains `Throwable`, `Exception`, `RuntimeException`, or `Error` receives the proactive stable
  physical name `<ordinary-name>__KotlinException__<logical-signature-digest>`. The digest uses
  the owner-independent Kotlin signature and an override's selected logical slot declaration, so
  overloads remain distinct after CLR erasure, a `Base<T>.f(T)` override at `T = Throwable` stays
  on the unmangled generic base slot, and adding a later overload cannot rename an existing
  method. One override satisfying differently named physical views requires an explicit
  `MethodImpl`, plus an adapter only when signatures differ; never choose a slot arbitrarily.
  Producers record ordinary physical names in the KLIB index; generic-interface typed views apply
  the same deterministic rule. Explicit export facades own C#-facing names. CLR constructors
  cannot use this mechanism and colliding exception-category constructors remain unsupported
  pending a recorded compiler-ABI factory design. Physical-name grammar version 2 owns this rule;
  stale prototype libraries are rejected rather than bridged.
  `kotlin.coroutines.cancellation.CancellationException` is exactly
  `System.OperationCanceledException`; `TaskCanceledException` and other CLR subclasses retain
  identity and classify under Cancellation/IllegalState/Runtime/Exception/Throwable, never Error.
  This exact cancellation root plus the classified `IllegalStateException` carrier resolves the
  CLR sibling-root conflict without a wrapper. Classifier id 14, runtime surface level 7, and
  physical ABI schema 13 own the change on all three profiles.
  C# admission to a broad `Throwable`/`Exception`
  boundary accepts any `System.Exception`; narrower export admission remains an explicit deferred
  classifier-guard design rather than a fabricated CLR hierarchy.
  `IrThrow` evaluates and throws the original reference. Kotlin source `throw e`, including from a
  catch variable, emits CLR `throw`, never bare `rethrow`; object identity, exact type, message,
  `InnerException`, and `Data` survive, while the CLR stack trace truthfully exposes the new throw
  site. `IrTry` uses CLR protected regions plus generated filters where logical classification is
  required. Raw C# integration executes classification, catch/return, and catch/rethrow on both
  application profiles. It also directly invokes the runtime predicate for null, foreign, mapped,
  exact Kotlin, and fatal values across every assigned numeric id plus hostile ids; every call
  returns `bool` without throwing on both runtimes. A separately compiled `netstandard2.0` library also catches exact,
  mapped-runtime, broad-Exception, and Error objects supplied by both application profiles through
  the same classifier. Portable direct and nested-generic exception overloads, ordinary and
  generic-base class overrides, interface implementations, and a differently named class/
  interface multi-slot override execute cross-module on both application profiles. Portable
  exception returns and mutable properties preserve logical classification and reference identity;
  a nested generic return retains its logical runtime category. Exception arrays and function
  types preserve overload selection and callback identity across the same portable boundary.
  Narrow/foreign export admission, constructor collisions, remaining foreign/generic boundary
  coverage, the complete built-in mapping table, and any non-physical hierarchy metadata remain
  open before Gate B.
- Exhaustive `when` without a source `else` follows the JVM intrinsic-registry model: fir2ir's
  synthetic `noWhenBranchMatchedException` call is registered in `DotNetIlIntrinsicMethods` and
  emits an inline parameterless exception construction + `throw`, in both value and statement
  positions. Exception choice deliberately deviates from Roslyn: C# uses
  `System.Runtime.CompilerServices.SwitchExpressionException`, but `whenprobe_s1` proved that the
  type requires the `[System.Runtime]` scope on CoreCLR 10.0.9 (`[mscorlib]` assembles but fails
  with `TypeLoadException`), and the .NET Framework `System.Runtime` facade does not contain it.
  Emitted IL must stay target-independent, so the intrinsic now constructs the exact runtime-owned
  `[Kotlin.Runtime]Kotlin.NoWhenBranchMatchedException : Kotlin.RuntimeException`. This preserves
  the supported `Throwable`/`Exception` catch edges without falsely making the exception a mapped
  `IllegalStateException`. The open runtime class follows JVM/Native and exposes the same four
  constructor forms as its root. `exceptionabi_s1` assembled the hierarchy and consumers with
  modern 10.0.9 and Framework 4.8 ILAsm; all four same/cross-runtime pairings preserved the exact
  catch, the RuntimeException parent edge, null default message, cause identity, and the boundary
  from a foreign `InvalidOperationException`. `ilText/exhaustiveWhen.kt` pins both emission
  positions and the two-handler boundary; `box/exhaustiveWhen.kt` runs all reachable
  Boolean/Boolean? arms on CoreCLR. `whenprobe_s2` links against that exact golden and
  passes the noncanonical CLR `bool` value `2` to force the otherwise unreachable fallthrough in
  a second call-argument position (a prior string remains below the exception on the evaluation
  stack), runtime-pinning both the throw and cross-target catchability.
- try/catch follows the JVM model: `IrTry` maps 1:1 onto the CLR exception table — one `.try`
  block plus consecutive typed `catch` handlers in Kotlin source order (the CLR matches strictly
  first-to-last, probe-verified; the frontend owns unreachable-catch diagnostics) — with no
  lowering machinery. Regions are exited only via `leave` (a `ret` or `br` crossing a region
  boundary assembles but fails at runtime), and `leave` discards the evaluation stack, so a
  `try` expression drains its branch values into a synthetic result local reloaded at the join
  label; returns crossing protected regions drain into a synthetic return local and `leave` to a
  shared return-join epilogue (the Roslyn shape), and `break`/`continue` crossing regions emit
  `leave` straight to the loop labels — legal toward any enclosing-scope label, forward or
  backward, crossing nested regions in one hop (all probe-verified, `excprobe_s2`). One stated
  deviation from the JVM backend: the CLR requires an empty evaluation stack at `.try` entry
  (ECMA-335 I.12.4.2), so a `try` expression with operands already on the evaluation stack
  (e.g. a non-first call argument) is rejected rather than spilled.
- `finally` uses real CLR `leave`-driven finally handlers with NO JVM-style finally
  inlining/duplication — a CLR-forced deviation from the JVM backend: the CLR runs the finally
  automatically on every `leave` out of the region (normal completion, `break`/`continue`,
  return-join leaves) and on the exceptional path, inner-then-outer for nested regions
  (probe-verified, `excprobe_s3`). A `.try` carries either catch handlers or ONE `finally`,
  never both — combining them assembles silently but throws `InvalidProgramException` at
  runtime — so Kotlin `try`/`catch`/`finally` nests the try/catch construct inside an outer
  `.try { } finally { }`; catch-less `try`/`finally` is a single region. The finally body is
  emitted as void and exits only through `endfinally`, so `return`/`break`/`continue` crossing
  OUT of a finally body are rejected (`dotNetUnsupported`) — even `leave` may not exit a
  finally handler; exits within it (a loop or try/catch declared inside the finally body) work
  normally.
- Generics stance: generic classes and generic methods stay structural and target real CLR
  reified generics (Roslyn shape). Kotlin-owned generic interfaces are the deliberate exception:
  their Kotlin storage/cast identity is a non-generic canonical interface, while reified declared
  and exact siblings are same-object execution/C# capabilities. This follows Kotlin's erased
  interface cast/projection semantics and is not a fallback erasure for unsupported generic
  classes. Unsupported shapes outside that selected interface ABI are rejected, never silently
  erased.
- Generics model (stages 1-6) (probe series `genprobe_s1`–`_s9`, `genconstraintprobe_s1`–`_s2`,
  `genarrayprobe_s1`, `genifaceprobe_s1`, `genmemberprobe_s1`, `geninheritprobe_s1`; precedent:
  Roslyn — the CLR has REAL reified generics,
  so generic classes and methods use no erasure or monomorphization machinery: the type mapper and
  emitters learn generic declarations, type-parameter references and instantiation tokens, and
  the frontend owns all type checking. Kotlin-owned generic interfaces additionally use the
  canonical/declared/exact split in
  `docs/decisions/draft-adr-variant-interface-abi.md`):
  - SUPPORTED: generic FUNCTIONS (top-level, or members of any otherwise-supported class, object,
    companion, or all-abstract interface; non-inline; invariant method parameters, either
    unconstrained or directly constrained by non-null, non-generic, module-local classes and
    all-abstract interfaces), plus the pre-existing `T : String`/`String?` erosion of the
    string-concat lowering, kept for compatibility and pinned by the borrowed
    `box/strings/kt50140.kt`: such a `T` still declares its real arity but its SLOTS map to
    `string`); generic TOP-LEVEL OR NAMED NESTED PLAIN CLASSES (final, open, abstract, or sealed;
    the same direct module-local constraint rule without the String exception), with nested
    classes owning an independent `!n` space even inside generic outers (the outer's parameters are
    not captured); generic TOP-LEVEL ALL-ABSTRACT
    INTERFACES with invariant, `out`, or `in` parameters under that same constraint rule, emitted
    as one non-generic canonical Kotlin identity plus a declaration-variant generic sibling and,
    where required by member placement or superedges, an invariant exact sibling; generic and non-generic
    classes implementing open or closed generic-interface instantiations; generic-interface
    inheritance with composed arguments; any supported class extending a module-local plain or
    generic base through a mapped closed/open instantiation — including permuted, nested,
    generic-array, concrete nullable-value and constrained arguments — across arbitrary chains,
    optionally ALSO implementing interfaces (`ilText/genericInheritance.kt`,
    `ilText/genericInheritanceChains.kt` and their box tests); what an
    unconstrained `T`-typed value supports exactly store/load (locals, params, returns, fields of
    the declaring class) and passing to another `T` position. A constrained `T` additionally
    supports calls to members exposed by any direct/transitive bound and widening to a bound or
    `Any`/`Any?` through `box !n`/`box !!n`. Invariant `Array<T>` composes as a real CLR
    `!n[]`/`!!n[]` in those same positions, with typed element access and indexed iteration.
  - SPELLING CANON (all probe-verified): a generic class is ONE quoted identifier with the CLS
    backtick-arity suffix INSIDE the quotes (`.class ... 'demo.Box`1'<'T'>` — suffix outside the
    quotes is an ilasm syntax error, genprobe_s2c; the suffix is CLS convention, not CLR-required,
    genprobe_s2b — emitted for Roslyn/interop parity, which also makes plain-`Box`/generic-`Box`
    IL collisions impossible); quoted type-parameter NAMES assemble and run (genprobe_s8) and are
    decorative — CLR identity is positional (`!n` class / `!!n` method vars). A generic method is
    `.method ... !!0 'id'<'T'>(!!0 'x')`; on a generic owner its `!!n` method space remains
    independent from the owner's `!n` class space, and both tokens compose on member references
    (`class 'Picker`1'<string>::'pick'<int32>(!0, !!0)`, `genmemberprobe_s1`).
    constrained formal stays inline in that same list: one base constraint first, then interface
    constraints, e.g. `<(class 'Base', class 'Mark') 'T'>`; reflection confirms both constraints
    are present in metadata (`genconstraintprobe_s1`). On an interface, `+`/`-` precedes the
    constraint list (`<+ (class 'Base') 'T'>`); the full direct edge is retained on `implements`
    (`class 'Producer`1'<!0>`), and both spellings assemble and execute on CoreCLR and Framework
    (`genifaceprobe_s1`). EVERY member reference on a generic class or interface carries an
    instantiation on the OWNER token while its
    signature slots stay OPEN (`!0`/`!!0` verbatim): closed externally
    (`newobj instance void class 'Box`1'<string>::.ctor(!0)`,
    `call instance !0 class 'Box`1'<string>::'get'()`, `ldfld !0 class 'Box`1'<string>::'value'`),
    the OPEN self-instantiation `class 'Box`1'<!0>` inside the class's own bodies (genprobe_s2/_s7);
    generic-method call sites substitute only the `<inst>` list (`call !!0 ...::'id'<string>(!!0)`;
    `!!0` is itself a legal instantiation argument at generic→generic pass-through sites, and
    `class 'Box`1'<!!0>` composes inside generic methods, genprobe_s9). EXCEPTION: `.property`
    accessor references use the bare class name with NO type-args list (genprobe_s2). The `extends`
    line of an instantiated generic base is closed `extends class 'Box`1'<int32>` (genprobe_s5)
    or open/permuted `extends class 'Base`2'<!1, !0>`; its base-ctor operand carries that same
    owner token while keeping the base's formal parameter slots open (`geninheritprobe_s1`).
    Nullable composes verbatim as an argument
    (`class 'Box`1'<valuetype [mscorlib]System.Nullable`1<int32>>`) in every operand position, and
    the mandatory home-address spill rule extends to `!0`-returning calls — spill to a local typed
    with the CLOSED substituted type, then `ldloca` (genprobe_s4). Reification is real:
    `Box`1<int32>` stores a raw int32, zero box/unbox, instantiations coexist and nest (genprobe_s3).
    Generic arrays likewise keep the element token open (`!!0[]`; `newarr !!0`; `ldelem !!0`;
    `stelem !!0`) and substitute structurally at call sites (`genarrayprobe_s1`).
  - CODEGEN MODEL: mapped signatures stay OPEN (`TypeParameter`/`GenericInstance` arms of
    `DotNetIlValueType`); call sites derive the owner token from the RECEIVER's mapped type walked
    to the declaring class (`dotNetViewAsGenericOwner` — inherited members and super-calls through a
    derived receiver name the instantiated BASE, genprobe_s5) and emit argument VALUES against the
    IL-level SUBSTITUTED types (`substituteDotNetTypeParameters`). Base/interface edges substitute
    recursively at every hop, so a chain such as `Leaf<P,Q> : Mid<Q,P> : Base<P,Q>` recovers the
    exact open declaring-owner view (`geninheritprobe_s1`). Generic-class assignability stays
    structural and invariant. Ordinary Kotlin-owned interface storage, projections, and casts use
    the canonical non-generic identity, so value/open/reference variance conversions preserve the
    same object without asking CLR generic variance to implement Kotlin variance. A call may probe
    the structurally mapped declared/exact sibling transiently; imported CLR interfaces instead
    retain CLR reference-only generic assignability. The supertype walk carries the base
    instantiation plus the physical interface views needed for calls and overrides, substituting
    at every edge. CLR method identity includes
    generic ARITY, so the member/facade identity gates key on it (`fun <T> pick(x: String)` and
    `fun pick(x: String)` are legal coexisting overloads, pinned by `ilText/genericArity.kt`).
    OVERRIDES in a class with a generic base MUST be spelled with the SUBSTITUTED types — that
    spelling reuses the base slot for returns (genprobe_s5) AND parameters (genprobe_s8), and falls
    out of mapping the derived member's own concrete Kotlin types; the OPEN `!0` spelling in a
    non-generic derived class is a POISON SHAPE (assembles warning-free, silently splits the slot,
    base-typed `callvirt` runs the BASE body — the covariant-return failure family; genprobe_s5b)
    that this codegen can never emit implicitly. The covariant-return lowering compares the
    overridden return under the receiver's structural owner view and emits an exact new slot plus
    explicit MethodImpl adapters, so substituted base/interface overrides reuse matching slots and
    real covariance dispatches through generated bridges. Eviction rides
    the existing fixpoint: instantiations
    map arguments through the LIVE class map, so an instantiation mentioning an evicted class fails
    its USE, and the `extends` re-resolution carries a type-argument-eviction reason down the chain
    (pinned by `ilText/genericEvicted.kt` — the generics analogue of `inheritanceBaseEvicted.kt`:
    an evicted class used as a function's instantiation argument evicts per-function, used as a
    generic-base argument evicts the derived class whole-class, while a sibling instantiation of
    the same base survives untouched). Stage 2 carries mapped upper bounds on each structural
    `TypeParameter`: a virtual/interface call evaluates and spills the receiver and every
    argument, reloads the receiver's home address plus those arguments, then emits
    `constrained. !n`/`!!n` immediately before `callvirt`; this is the CLR
    reified counterpart of JVM erasing to the first bound and inserting `checkcast` for secondary
    interface bounds. A non-virtual class-bound call and a widening from `T` to a bound or
    `object` instead emit `box !n`/`!!n`; CoreCLR and Framework both preserve reference values
    without allocation while remaining valid for an external value-type implementation of an
    interface constraint (`genconstraintprobe_s2`). The spills also keep the CLR stack empty when
    an argument contains `try`. Constraint types re-resolve through the LIVE
    class map while rendering, so an evicted bound cascades to a top-level function individually
    or to a constrained class whole-class.
  - STAYS REJECTED, loudly (each with a specific message; pinned by `ilText/genericRejected.kt`
    and `ilText/genericInterfacesRejected.kt`): declaration-site variance on CLASSES
    (`out`/`in` — ECMA-335 II.10.1.7 allows variance only on interfaces and delegates; emitting
    invariant would silently change assignability). Kotlin-owned generic-interface value/open
    variance is supported through the canonical identity, never by treating a closed CLR
    `castclass D<T>` operation as a variance conversion.
    Imported CLR generic interfaces remain at the foreign CLR boundary: reference variance is
    accepted only when the CLR proves reference-shaped arguments; value-shaped and conservatively
    open cases require an explicit identity-changing adapter. Also rejected: constraints whose bounds are nullable,
    generic instantiations, other type
    parameters, builtins/mapped types, or anything outside the module-local supported class and
    interface model, `T?` ANYWHERE in a generic declaration (NO uniform CLR representation for
    the supported interface-bound case: a CLR caller can instantiate it with a value type needing
    `Nullable<T>` or a reference type needing nothing; the declaration is rejected, never given an
    ad-hoc representation), identity `===` on `T` operands (a value-type instantiation has no
    stable reference identity, and boxing would manufacture unrelated references), other member
    calls on an unconstrained `T` or outside its declared bounds, `is`/`as` on `T` or reified
    generic classes (Kotlin-owned split generic interfaces are the canonical-erased exception
    described above), inline/reified generic functions (no inlining model), declared
    varargs of `T` (their projected-array ABI and general vararg lowering remain unsupported),
    generic (extension) properties (the property metadata/accessor binding model does not cover
    generic accessors), and a companion declared directly in a generic class (its field would be
    per constructed owner; a named object owns its `INSTANCE` on its independent non-generic type
    and is supported below a generic metadata parent). Structural `==`/`== null`, string
    templates/`toString`, and widening an unconstrained `T` to `Any?` are supported by boxing at
    the System.Object boundary and using the Any runtime helpers. A
    generic base instantiation whose argument does not map (`T?`, an unsupported external
    generic/primitive/array shape, or an evicted class) rejects the whole derived chain;
    an unrelated valid instantiation of the same base survives.
  - Pins: `ilText/genericFunctions.kt` (declaration + call-site spellings for every mapped
    type-arg kind incl. `<!!0>` pass-through, a nested instantiation as a generic-method type
    argument — `id<Box<String>>` carries `class 'Box`1'<string>` in the `<inst>` list — and the
    genprobe_s9 composition: `wrap` returning `class 'Box`1'<!!0>` with the
    `newobj instance void class 'Box`1'<!!0>::.ctor(!0)` body spelling), `ilText/genericClasses.kt`
    (full class shape, open
    self-instantiation, closed external operands, nested instantiation, `.property` bare-name
    accessor refs), `ilText/genericInheritance.kt` (instantiated-base extends/ctor-chain/override/
    super-call spellings + generic-extends-plain + the instantiated-base-with-`implements`
    combination), `ilText/genericArity.kt` (arity overloads +
    suffix coexistence), `ilText/genericRejected.kt` (every rejection above),
    `ilText/genericEvicted.kt` (the eviction cascade), `ilText/genericConstraints.kt` (formal
    constraint spelling plus virtual/interface/non-virtual calls and bound widening),
    `ilText/genericConstraintsRejected.kt` (constraint shapes outside the stage-2 boundary),
    `ilText/genericArrays.kt` (open/concrete vector signatures, construction and typed element
    access), `ilText/genericArraysRejected.kt` (array shapes outside the invariant reference/open
    element boundary), `ilText/genericInterfaces.kt` (variance metadata, constrained formals,
    open/closed/permuted interface edges, owner tokens, reference conversions, and canonical-only
    `isinst`/`castclass` spellings),
    `ilText/genericInterfacesRejected.kt` (open-`T?` slots, unsupported instantiated interface
    bounds, and evicted interface arguments),
    `ilText/genericMembers.kt` (independent owner/method parameter spaces, nested generic owners,
    constrained members, generic interface slots and implementations, instantiated-base
    overrides/super calls, object/companion owners, member extensions and arity overloads),
    `ilText/genericMembersRejected.kt` (inline/reified methods, nullable method slots and
    unsupported method bounds),
    `ilText/genericInheritanceChains.kt` (open/permuted multi-hop extends and ctor tokens,
    substituted generic/non-generic overrides and super calls, inherited interface/member owner
    views, open upcasts, nested/array/nullable/constrained arguments),
    `ilText/genericInheritanceChainsRejected.kt` (invalid argument and base-eviction cascades with
    an unrelated surviving open instantiation),
    `ilText/classShapeRejected.kt` (the
    variance flavor in the class-shape gate); runtime:
    `box/genericFunctions.kt` (every type-arg kind incl. both `Int?` flavors through `!!0`,
    multi-param, T pass-through, `wrap(x).v` round-trips of the `Box<!!0>` composition, the
    nested-instantiation type-arg), `box/genericClasses.kt` (state, coexisting instantiations,
    nesting, permuted self-instantiation `Pair2<B, A>`), `box/genericInheritance.kt` (dispatch
    through instantiated-base views, substituted overrides, super chains, inherited mutation,
    cross-view identity, generic-extends-plain dispatch, and the combined
    generic-base-plus-interface flavor: interface-view dispatch, inherited state and mutation
    through all views — interface-mapping mistakes fail only at JIT time, so the dispatch shape
    carries its own runtime pin), `box/genericConstraints.kt` (multiple bounds, virtual override
    dispatch, interface properties/methods, non-virtual class members, bound/Any widening, class
    type parameters and multiple generic instantiations), `box/genericArrays.kt` (construction,
    fields, open and constrained element access, indexed iteration, identity/null behavior and
    multiple reference element shapes), `box/genericInterfaces.kt` (reference/value covariance
    and contravariance, transitive/permuted interface inheritance, nested variance, generic
    implementers, exact value-type instantiations, canonical hard/safe casts, erased logical
    arguments, nullable type tests, failure categories, and single operand evaluation), plus a
    separate `netstandard2.0` 65-parameter interface consumed by Kotlin and C# on both application
    profiles (canonical/declared/exact arity, high-index variance, exact-capability metadata,
    identity-preserving widening, canonical fallback, and wrong-shape failure). The same portable
    producer completes the one-through-four matrix with a four-parameter `in`/`out`/invariant/`out`
    interface, a primitive result, a separately widened concrete `Int?` result, an exact-only
    unsafe member, and open generic pass-through. A raw canonical-only provider executes a
    producer-recorded erased slot from one portable Kotlin reader on both Framework CLR 4 and
    CoreCLR 10 without declared/exact capabilities. Remaining pins: `box/genericMembers.kt` (method
    pass-through, nullable method instantiations, inherited interface implementation, generic
    virtual/super dispatch, constrained interface calls, arity overloads, objects, companions and
    member extensions), `box/genericInheritanceChains.kt` (multi-hop constructor/state flow,
    generic virtual/super dispatch, inherited generic-interface mapping, open base/interface
    upcasts, cross-view identity and every supported composite base-argument family).
- Shared compiler support (currently Kotlin-parity `Double.toString` rendering) is hand-written IL
  in `Kotlin.Runtime`. Generated modules call the public CLR member
  `Kotlin.Runtime.Internal.DoubleFormatting.DoubleToString`; metadata visibility must be public for
  cross-assembly access, while the reserved namespace keeps it outside the Kotlin-facing API.
  `runtimehelper_s1` assembled the runtime and a calling consumer with modern 10.0.9 and Framework
  4.8 ILAsm; all four same-target/cross-runtime pairings printed identical Kotlin-shaped values.
  Generated modules contain neither the helper body nor a synthetic `<KotlinIl>` type, and the old
  per-method/per-class helper-requirement bookkeeping no longer exists. Every mscorlib member
  signature used in helper IL must still be verified by assembling and running an ILAsm probe
  before it lands in codegen.

## Box tests

- Like every mature target, box tests execute on real runtimes (JVM in-process, JS under Node,
  Native via its runner). Both FIR parsers run the same corpus through two generated lanes in
  `AbstractDotNetIlTextTest.kt`: `net10.0` emits a dll plus its runtime config and uses signed
  `dotnet exec`; `net48` emits an exe, which the signed Windows PowerShell CLR 4 host loads before
  invoking its exact managed entry point. Neither lane directly activates an unsigned executable.
- A signed host only avoids SAC for direct `.exe` *execution*; it does NOT stop SAC from blocking
  the CLR from *loading* the freshly assembled unsigned assembly. On a machine with Smart App
  Control ON, SAC makes a per-file cloud-reputation call the first time each unsigned file is loaded
  and fails-closed on a negative verdict (`FileLoadException`, HRESULT `0x800711C7`, Code Integrity
  policy `VerifiedAndReputableDesktop`). Measured behavior (2026-07, SAC-enforced Win 11 host): the
  SmartScreen verdict is derived from the assembly CONTENT, not just its hash. At measurement
  time, reassembling identical IL produced differing hashes yet the affected programs were blocked
  again; current writers use `/det`, but that does not weaken the content-derived conclusion. The
  block is effectively permanent per affected program on that machine, and re-running the suite
  does NOT clear it (an earlier "transient burst" theory is disproved).
  Concretely, of the 11 dotnet box programs existing at measurement time, 2 (`booleanShortCircuit`,
  `forLoopEdges`) were always blocked; the other 9 usually loaded but were occasionally blocked
  transiently too when a whole-suite run loads many fresh dlls in a burst (e.g. `charOperations`
  blocked in one parser variant and loaded in the other within the same run). The corpus has since
  grown well past those 11 programs; the newer programs have no measured SAC verdicts. The trigger
  is an opaque whole-file ML threshold, not a specific instruction pattern: each half of the
  flagged `booleanShortCircuit` assembly (helpers with the Int.MIN_VALUE-guarded `div` pattern
  alone, or the string-comparison half alone) passes when assembled separately; only the
  complete program is flagged, and the equally div-guard-heavy
  `intMinValueDivision` program passes.
- The shared profile-aware box runner retries a blocked load with a short delay to absorb a genuinely
  in-flight verdict, then normally aborts the test as SKIPPED (JUnit `TestAbortedException`) with a
  diagnostic that names SAC (any other non-zero exit fails immediately). Rationale (user decision,
  2026-07): a host whose OS refuses to load the assembly cannot execute the test — the same
  environmental-inability contract as a missing toolchain — and the test still executes on hosts
  without SAC. A block is never a silent pass. In a strict required-toolchain lane
  (`KOTLIN_DOTNET_REQUIRE_TOOLCHAIN=1` or `true`) it is a test failure, so CI cannot report a green
  semantic-execution gate which the host refused to execute. Do NOT work around SAC by
  perturbing the artifact's hash — and do NOT rewrite or restructure a test program's content to
  dodge the classifier's false positive; both are reputation bypasses and out of bounds. SAC has no
  per-file or per-directory exclusion mechanism (Defender exclusions do not apply to it) and can
  only be turned off wholesale by the user, irreversibly. To execute the affected tests, the
  legitimate options are: run the gate on a host without Smart App Control, sign the test
  assemblies with a certificate SAC trusts, or have the user turn SAC off.
- When a lane's toolchain is missing (modern ILAsm + dotnet host, or Framework ILAsm + Windows
  PowerShell CLR 4 host), its box tests normally SKIP via a JUnit 5 assumption before compiling. With
  `KOTLIN_DOTNET_REQUIRE_TOOLCHAIN=1` (or `true`) missing tooling fails instead; use that setting
  for mandatory runtime-execution lanes. Provision the toolchain with
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`. The ilText suite always keeps
  its text-comparison coverage and stays on the `NET48` default so its goldens' `.module`
  directives are unchanged. It additionally assembles every emitted executable or library with
  each available Framework and modern ILAsm. The modern pass is a compatibility oracle over the
  net48 IL, not net10 profile validation. Strict toolchain runs require both assemblers.
- The dotnet-owned box corpus lives in `compiler/testData/codegen/dotnet/box/`; a few borrowed JVM
  box files are additionally registered by pattern in `TestGeneratorForFir2IrTests.kt`.

## Target selection (`-Xdotnet-target`)

- `-Xdotnet-target={net48|netstandard2.0|net10.0}` (default `net48`) selects the target-framework/API
  profile, carried as the `DotNetTarget` enum in `DotNetConfigurationKeys.TARGET`. Product kind is
  independent: `net48` and `net10.0` produce applications or libraries; `netstandard2.0` is
  library-only. Invalid values and executable Standard products are a `COMPILER_ARGUMENTS_ERROR`
  from `DotNetConfigurationUpdater` before FIR/code generation.
- The profile is selected before IR lowerings and controls core/member references, target metadata,
  runtime/stdlib generation and selection, dependency compatibility, assembly writing, and
  packaging. Kotlin common semantics remain invariant; emitted code may differ when CLR
  capabilities differ. Exact IL tests default to `net48`; profile-specific integration pins own
  the Standard and modern headers and behavior.
- `net48`: `-d foo.exe` → Framework ilasm (`ILASM` env, PATH, then
  `C:\Windows\Microsoft.NET\Framework*\v4.0.30319\ilasm.exe`) assembles a directly runnable `.exe`.
- `netstandard2.0`: only `-Xdotnet-produce-library`/`-Xdotnet-produce-stdlib` are valid. Modern
  ILAsm writes the portable DLL with the exact `netstandard, Version=2.0.0.0` reference and
  `.NETStandard,Version=v2.0` target metadata.
- `net10.0`: both `-d foo.exe` and `-d foo.dll` are executable requests; the artifact is always
  `foo.dll` plus `foo.runtimeconfig.json` (an `.exe` request is remapped to `.dll` with an INFO
  diagnostic naming the actual artifact — modern ilasm-produced exes have no self-hosting story,
  the runnable form is `dotnet exec foo.dll`). The modern ilasm is discovered per the contract
  below; when it is missing, a single ERROR names the provisioning script. The runtimeconfig
  framework version is the `<major>.<minor>.0` family of the newest runtime under the discovered
  dotnet root's `shared/Microsoft.NETCore.App` with `rollForward: LatestMinor` (fallback
  `net10.0`/`10.0.0` when no host is found — the dll may be run on another machine).
- Both ilasm flavors are invoked deterministically with the same legacy flag spelling
  (`/nologo /quiet /det /exe|/dll /output:...`); the modern ilasm accepts it (probed on 10.0.9).
- Integration tests pin the compatibility matrix, exact manifest/header metadata, repeat-build
  determinism, target-specific stdlib variants, and real execution of one portable stdlib pair on
  both runtime profiles. A net48 assembler-pairing matrix also writes the same compiler-produced
  application, stdlib, and runtime IL with Framework and modern ILAsm, then executes all eight
  artifact-writer combinations on both runtimes. The Framework lane uses the signed Windows
  PowerShell CLR 4 host to load and invoke the exact managed entry point rather than directly
  launching an unsigned exe. A separate net10 boundary test proves that Framework ILAsm rejects a
  real DIM body while the profile-selected modern writer executes it on CoreCLR. The modern profile
  therefore has no artificial cross-writer requirement. Explicit-writer hooks are test-only;
  production always selects its writer from the profile. Any failed ILAsm invocation removes its
  partial PE and runtimeconfig before the compiler reports failure.

## Modern .NET toolchain

- A durable, per-user (no admin) modern toolchain lives at `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\`:
  `dotnet\dotnet.exe` hosts the pinned 10.0.9 runtime and 10.0.100 SDK; the SDK supplies
  `Roslyn\bincore\csc.dll` plus the net10 reference pack for C# integration tests.
  `ilasm\ilasm.exe` is the self-contained modern CoreCLR assembler from the NuGet package
  `runtime.win-x64.microsoft.netcore.ilasm`, pinned 10.0.9. Production Kotlin IL assembly does not
  depend on Roslyn. Provision or repair the toolchain with the idempotent script
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1` (parameters: `-InstallDir`,
  `-RuntimeVersion`, `-SdkVersion`, `-IlasmVersion`).
- Discovery contract (for the assembler/test runner; implement lookups in this order):
  1. `KOTLIN_DOTNET_ILASM` — full path to an `ilasm.exe`; takes precedence for the assembler.
  2. `KOTLIN_DOTNET_ROOT` — a toolchain root containing `dotnet\` and `ilasm\` subdirs
     (i.e. `<root>\ilasm\ilasm.exe`, `<root>\dotnet\dotnet.exe`).
  3. The default durable location above.
  4. Legacy .NET Framework ilasm (`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\ilasm.exe`).
  Modern C# tests discover `csc.dll` and `packs\Microsoft.NETCore.App.Ref\...\ref\net10.0` from
  the same toolchain root (or a system-wide SDK root). They invoke Roslyn through the discovered
  `dotnet` host with `/nostdlib+` and the complete reference pack.
- The modern ilasm accepts both the legacy flag spelling (`/nologo /quiet /exe /output:x.exe`) and
  the modern one (`-DLL -OUTPUT=x.dll`; quote `-OUTPUT=...` when calling from PowerShell, which
  otherwise mangles the `=`). It reads UTF-8 IL with or without BOM, so existing emitter output
  assembles unchanged.
- Running an assembled dll on CoreCLR requires `x.runtimeconfig.json` next to it:
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  then `<toolchain>\dotnet\dotnet.exe exec x.dll`. Without the runtimeconfig, `dotnet exec` fails
  with a hostpolicy.dll error. Prefer dll + `dotnet exec` over direct `.exe` execution: the signed
  `dotnet.exe` host avoids the Smart App Control blocking of freshly assembled unsigned exes.
- Known semantic delta vs. .NET Framework: raw .NET formatting renders `-0.0` as `"-0"` on CoreCLR
  but `"0"` on Framework. The backend's own `DoubleToString` helper makes this moot for compiled
  Kotlin programs, but raw formatting probes differ.
