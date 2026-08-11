# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: exact reviewed upstream commit `0e8c5f3f53`
- Last integration checkpoint: the complete reviewed 195-commit range was
  integrated by a pure rebase on 2026-08-07. All 409 target commits were
  retained: range-diff classified 408 patches as identical and one inline
  patch as context-only because upstream renamed its validator factory. The
  three shared paths, virtual-merge evidence, contract-level
  reverse-dependency/architecture audit, and post-rebase checks are
  recorded in
  [`docs/archive/upstream-impact-2026-08-07.md`](docs/archive/upstream-impact-2026-08-07.md)
- Last completed foundation: the generated Stdlib member catalog now makes all
  eight concrete Common scalar built-ins, mapped `kotlin.String`, all sixteen
  built-in collection interfaces, and the complete current Kotlin-owned
  collection implementation family executable through `KClass.members`. Scalar
  entries come directly from `IrBuiltIns`, preserving complete logical Kotlin
  scopes and signatures over their boxed/unboxed CLR value carriers while
  excluding CLR-only names. Their complete scopes exposed two ordinary backend
  gaps rather than reflection exceptions: eager `Boolean.and`/`or`/`xor` now
  follow JVM's primitive intrinsic registry, and physically retained deprecated
  signed-number `toChar` members preserve Byte/Short sign extension, Int/Long
  truncation, and Float/Double `toInt()` saturation when called reflectively.
  Mixed promotions and Kotlin Float/Double NaN/signed-zero total ordering execute
  through the existing callable pipeline. `Number` remains a separate family
  because its logical supertype uses a classified `object` carrier; it is not
  inferred from the eight concrete entries. Interface members likewise come
  directly from `IrBuiltIns`, preserving logical read-only/mutable and nested-
  entry identities over the canonical/exact split-interface representation.
  The implementation family
  covers the four read-only abstract bases, their four mutable counterparts,
  `ArrayList`, `HashMap`, and `HashSet`. `LinkedHashMap`/`LinkedHashSet` retain
  the same `KClass` and catalog identity as their actual typealias targets. The
  complete member sets come from the Kotlin built-ins/actualized Stdlib class
  scopes after KLIB serialization; arbitrary `System.String`/BCL members never
  enter the result. One Stdlib-owned catalog feeds the existing compact
  callable/property pipeline, while optional `Kotlin.Reflection.dll` owns
  lookup order and Runtime retains neither a Stdlib dependency nor catalog
  policy. Inherited fake overrides remain reflection identity and use their
  resolved real override only for execution, fixing the `Collection` versus
  `AbstractCollection` receiver boundary generically. Abstract skeletal-class
  members conversely require a real subclass receiver: reflection does not
  pretend that direct `MutableMap` implementor `HashMap` inherits
  `AbstractMutableMap`. One- and two-parameter owner graphs, concrete, abstract,
  and exact interface dispatch, Common annotations, mutation, nested entries,
  and per-`KClass` caching are covered.
  The selected collection members also pulled authoritative Common
  `ReturnValue.kt` into Stdlib, so
  runtime `@IgnorableReturnValue` applications are preserved rather than
  filtered. Library ABI/runtime surface 33 and provider `getMembersV2` version
  the cross-product change. Focused PSI/LightTree, Framework/CoreCLR, malformed
  provider, packaged-source, reproducibility, and installed-product checks are
  green.
  The preceding foundation, private member-factory protocol 2, emits one
  generated dispatcher TypeDef per reflected Kotlin producer class instead of
  one callable TypeDef per function/getter/setter. Shared Runtime carriers
  implement `KFunction` plus exactly one matching `Function0..22` capability,
  or `FunctionN` with its physical `arity` slot. The dispatcher contains
  direct ordinary-IR thunks and is completed only after Common suspend and
  masked-default lowering select their physical signatures; CLR reflection is
  never an invocation fallback. Existing property wrappers retain getter/
  setter identity and backlinks. The focused semantic matrix covers exact
  function-type invocation, defaults, empty and supplied varargs, suspend
  continuation forwarding, and stable suspend `callBy` rejection. Library
  ABI/runtime surface 32 and private factory method
  `<GetKotlinMembers-v2>` version the change. Producer opt-in remains required
  pending broader product/authority work.
  The preceding foundation moved common callable-reflection bodies once
  in Runtime's `FunctionReferenceBase`, following the JVM/Wasm runtime-base
  ownership direction. Generated direct/member references retain only
  invocation, bound-value, default, vararg, and suspend-specific hooks;
  property accessors inherit the same bodies. The base deliberately remains
  outside `KFunction`, so an adapted `FunctionN`-only reference does not gain
  reflective identity. The five affected generated-IL baselines lost 693
  repeated lines without changing callable behavior. Library ABI/runtime
  surface 31 rejects an old Runtime/new producer combination. This is a
  prerequisite reduction for the compact dispatcher above.
  The preceding feature completed JVM-shaped `KClass.members` for
  explicitly opted-in Kotlin-produced user/library classifiers through an
  optional `Kotlin.Reflection.dll`. Producers use `-Xdotnet-reflection` while
  this executable representation remains pre-ABI; ordinary compilation emits
  no member factory. Runtime owns the physical
  `KDeclarationContainer`/`KClass` slot, exact provider bootstrap, stable
  absence/mismatch failure, and per-`KClass` cache without a reverse static
  dependency. The optional product owns discovery; the backend emits only a
  private versioned producer factory from the post-KLIB logical class scope.
  Enumerated values are the established callable/property objects, so exact
  KLIB-derived annotations, parameters and type graphs, accessors, visibility,
  invocation, `callBy`, equality, mutation, dispatch, and exception identity
  retain one implementation. Separate compilation covers inheritance,
  interfaces/defaults, generic erasure, overloads, member extensions, nested
  classes, objects, and enums. Local/anonymous, foreign, and unadmitted
  mapped/Stdlib classifiers fail closed rather than exposing partial
  CLR-derived members.
  Library ABI/runtime surface 30 and private factory protocol 1 identify this
  pre-ABI opt-in closure. The actual packaged reflection source builds as
  `Kotlin.Reflection.dll` with only Runtime/Stdlib Kotlin dependencies; those
  base products have no reverse AssemblyRef. Reproducible stdlib production
  additionally pins upstream `IdSignature.visibleCrossFile` as the only
  cross-module callable-identity boundary, excluding absolute source paths.
  The private executable factory is a semantic proof, not a frozen compact
  encoding. General emission demonstrated material producer expansion; a
  compact KLIB-derived descriptor plus reflection-product decoder or equally
  compact shared thunks is therefore required before default enablement or ABI
  freeze.
  The preceding feature completed JVM-shaped direct property declaration facts
  and accessor objects for `KProperty0` through `KProperty2` and their mutable
  counterparts. One cached getter and optional setter call the owning
  property's established execution path, preserving bound receivers, virtual
  dispatch, mutation, exception identity, and separate compilation. Exact
  KLIB/importer IR owns `isConst`, `isLateinit`, accessor signatures,
  parameters, annotations, visibility, modality, and function flags; runtime
  CLR reflection owns none of them. A private callable-reference getter reads
  retained `const` literals without changing their ordinary field-only CLR
  ABI. Library ABI and runtime surface 29 own the payload, nested interfaces,
  and implementation classes. `getDelegate`, type-use annotation discovery,
  and the positive `isLateinit` path remain separate programmes. The preceding
  feature completed exact nominal constrained constructions and
  constructed-interface bounds for admitted foreign generic interfaces and
  methods. The FIR importer reuses the shared declaration-qualified CLR
  constraint resolver/validator; it does not reconstruct satisfaction from
  Kotlin types. Constrained members and InterfaceImpls, method and owner bounds,
  nested Roslyn nullability, Kotlin implementations, open generic bound dispatch,
  and the original TypeSpec/GenericParamConstraint slots agree on Framework CLR
  and CoreCLR. An intentionally invalid IL producer proves that an unbounded
  `T` cannot smuggle `KeyBox<T>` into lookup. The backend retains constructed
  foreign bounds as structural GenericInstance capabilities, and a non-null
  assertion on an open CLR parameter checks a boxed probe while preserving the
  original `!n`/`!!n` value. Special constraints, nullable constraint roots, and
  unsupported constructed shapes remain fail-closed. The preceding feature
  completed exact InterfaceImpl nullability and substitution for admitted
  foreign generic interfaces. The selected graph validates each
  retained InterfaceImpl row, implementing owner, target TypeDef, and assembly by
  identity. FIR consumes Roslyn's row-local preorder while retaining the original
  TypeSpec, so closed, reordered, mixed fixed/open, and inherited `T?` owner-
  parameter views agree across Kotlin supertypes, overrides, calls, MethodImpl
  slots, and CIL. Concrete oblivious reference arguments remain platform types;
  an oblivious owner parameter remains `T` rather than becoming a synthetic `T!`.
  Kotlin and C# implementations dispatch both ways on Framework CLR and CoreCLR.
  The preceding feature completed exact foreign `System.Nullable<V>` import for
  all eight signed Common primitive carriers. Only the selected core TypeDef retained in
  the shared declaration graph receives the Kotlin view `V?`; the original
  `Nullable<V>` signature remains authoritative for direct methods, mutable
  properties, nested `Box<V?>` constructions, overrides, and CIL. The nullable
  wrapper consumes no Roslyn reference-nullability flag and is never inferred
  from a namespace/name spelling or unconstrained annotated `T?`. Kotlin and C#
  implement and call the complete scalar family on Framework CLR and CoreCLR;
  unsupported nullable user structs remain fail-closed. The preceding feature
  completed exact constructed foreign CLR member types and open generic-interface
  inheritance. Resolved `Named` and recursively nested `GenericInstance` trees
  such as `Producer<Box<T>>` remain attached from selected metadata through FIR2IR
  and CIL; the backend never rebinds TypeRefs or TypeSpecs by name. Direct
  `Derived<T> : Base<T>`-style edges enter FIR and exact CLR interface metadata,
  including Kotlin implementations of inherited slots. All declaration carriers
  share one identity-indexed, compilation-local selected graph instead of
  retaining and validating the whole classpath per member. Roslyn nullable flags
  are consumed across the complete resolved type preorder. Callable reflection
  remains declaration-owned (`Box<T>`), while invocation on `NestedBox<String>`
  emits the substituted physical `Box<string>` carrier. C# calls into Kotlin and
  Kotlin calls into C# prove recursively constructed dispatch on Framework CLR
  and CoreCLR. Unsigned carriers, special constraints, nullable constraint roots,
  unsupported constructed bounds, and explicitly nullable generic leaves on
  declared members still reject the complete classifier. The preceding exact
  foreign generic-TypeDef slice retains
  public top-level generic interfaces as their selected native CLR identity,
  with declaration variance, admitted bounds, owner `!n` and method `!!n`
  substitution, properties, vectors, `params`, Kotlin implementations, reverse
  C# dispatch, and primitive-vararg MethodImpl adapters; imported owners never
  enter the Kotlin-owned erased/split-interface ABI. The preceding exact foreign
  method-generic feature emits
  ordinary MethodSpec calls and keeps declaration-owned callable type parameters
  on the same retained binding. The preceding feature supplied
  coroutine-specific physical-ABI evidence. One portable producer DLL proves
  through objective CLR metadata that public
  top-level and virtual suspend entries append the erased `Continuation` and
  return `Object`; non-tail bodies become private sealed
  `DotNetCoroutineImpl` subclasses with callable public-in-private-type
  constructors. Separate `net48` and `net10.0` consumers park and resume both
  top-level and member calls through that producer, so KLIB suspend semantics,
  physical binding, and execution agree across compilation and profile
  boundaries without freezing generated names, capture layout, fields, or
  state labels. The preceding selected runtime-surface authentication proves
  the actual monotone surface of each profile-paired `Kotlin.Runtime.dll`
  through exactly one standard CLR
  `AssemblyMetadata("Kotlin.RuntimeSurfaceLevel", "<level>")` value. The
  objective reader validates the assembly-level attribute parent, external
  constructor/type/assembly edge, exact `(string, string)` signature, and
  ECMA-335 blob without requiring a loaded BCL graph; the CLI then rejects
  missing, duplicate, malformed, stale, and future values before FIR. The
  target-profile core AssemblyRef name is configuration-owned and shared with
  CIL emission. KLIB remains authoritative for a library's required floor,
  while the C# implementation manifest deliberately does not duplicate the
  runtime's actual value. The preceding exhaustive final coroutine-IR
  validation extends Common's final validation with a target-specific phase
  placed after every .NET declaration/body producer. It rejects residual suspend
  declarations, calls, ordinary/raw/rich function references, suspension
  pseudo-expressions, and compiler-only coroutine/context intrinsic calls
  before CIL emission. Ordinary `Continuation` and `COROUTINE_SUSPENDED`
  runtime operations remain valid, and the emitter keeps its unconditional
  production guard for compilations without `-Xverify-ir`. The focused
  coroutine/bridge/callable-reference matrix covers 112 tests in 12 suites
  across both FIR parsers and both target profiles with zero failures, errors,
  or skips. The preceding shared Common/JVM suspend default, interface,
  and callable-reference closure executes unchanged
  upstream tests for member defaults, suspending default lambdas, generic
  interface default bodies and specialization, adapted default references,
  structure/name identity, direct execution views, and the full
  `KSuspendFunction`/`SuspendFunction`/`KFunction(N+1)`/`Function(N+1)` cast
  distinction. These tests consume the existing one continuation/state-machine
  pipeline and Common default/interface lowerings; no target-specific source
  copy or alternate suspend ABI was added. The preceding Common/JVM
  callable-arity closure spans fixed
  `Function0`/`KFunction0` through `Function22`/`KFunction22` and the vararg
  execution-arity-23-and-above `FunctionN` boundary. Big ordinary functions,
  explicit and transitive-interface implementations, callable references,
  arity-classified tests/casts, positional and named reflection, separate
  libraries, and logical suspend arity 22 now use one same-object erased
  capability. Reflective defaults use one Common-lowered dispatcher template,
  32-bit `IntArray` omission words, and a late linear exposed-to-physical mask
  translation; 33 dependent defaults cross the first word boundary without
  combinatorial helpers. Runtime surface and library ABI version 28 own the
  new `FunctionN`/multiword-mask contract; the physical-name grammar is
  unchanged. The executable Kotlin coroutine foundation remains complete through its current
  liveness/member/extension/context/primitive-carrier closure. Target-owned
  compiler performance reporting remains active
- Maturity: high-quality pre-ABI prototype of an explicitly bounded Kotlin
  subset; no third-party binary compatibility is promised

This maturity statement measures the coherence and adversarial verification of
the admitted subset, not percentage completion of Kotlin as a language or
stdlib. The target is not close to 98% feature-complete: remaining
mapped/Stdlib and foreign member reflection, constructors and declared-member
APIs, the remaining
coroutine programme beyond its executable continuation/state-machine
foundation, multi-field value classes, Sequence and Grouping families,
sorting/random, and Gradle/KMP product integration remain substantial open
programmes.

## Current green gate

The complete current scalar/collection member-catalog head
passed every constituent of the strict target gate. Because another
development session shared the machine, this checkpoint records correctness
only and makes no duration or performance claim. The normal aggregate command
remains:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

The audited full-aggregate evidence covers 158 XML files and 1920 tests:

- 6 policy-free physical CLI model/serializer tests
- 1798 FIR, IL-text, and box tests
- 21 generated CLI tests
- 95 library-integration tests
- zero failures, errors, or skips

The aggregate exited successfully. Direct audit of its three final roots reports
the 158 files and 1,920 tests above with zero failures, errors, or skips. No
duration or performance comparison is retained because another compiler session
shared the machine throughout verification.

At the preceding coroutine head, test-only evidence was strengthened to require the
imported member entries to retain their CLR virtual slots and to dispatch a
base-typed consumer call to a distinct suspending override. The exact
portable-coroutine test repeated green in 49.3 seconds; all production
compiler/runtime code was unchanged from the aggregate head.

The final test-only strengthening also exposed an avoidable invalidation:
`compiler:tests-integration:dn` declares all of `compiler/testData/codegen` as
an input even though the selected .NET integration sources directly reference
only `dotnet/portableSurfaceVerifier.cs` from that root. Changing one .NET box
test therefore reran the complete 671-second `dn` suite. Narrow this only
through an upstream-compatible test-data input boundary that keeps every real
consumer tracked; do not hide the cost with an ad-hoc task exclusion.

The next profile after fixing the emitter showed a separate foreign-loading
cost: every 1/2/4-byte metadata-table value and every byte scanned from
`#Strings` performed a `RandomAccessFile.seek`. Buffering only the bounded CLI
metadata directory reduced the exact physical-metadata/signature testcase from
123.031 to 3.417 seconds. The two largest real importer cases changed from
208.84 to 18.953 seconds for cross-profile foreign interface binding and from
234.61 to 11.576 seconds for Common deprecation enhancement; a hostile
`NotNullWhen` case changed from 31.98 to 5.814 seconds. The complete gate kept
the same 1305-test matrix while wall time fell from 34m43s to 16m19s and `dn`
JUnit time from 1721.71 to 627.14 seconds. A live heap inspection during the
integration run showed 387 MB old generation and 868 MB young generation. That
single snapshot is consistent with a large committed/transient young heap and
provided no evidence that the full process working set was a retained assembly
graph; it is not a complete allocation or leak profile.

This is the CLR analogue of mature targets consuming bounded binary metadata
from memory, not permission for a compiler-wide cache. Selected graph identity,
file freshness, target profile, and compilation lifetime still require an
explicit shared .NET platform/import owner before any cross-read cache is
considered. Re-profile before adding string/blob memoization or test bundling.

The performance investigation distinguished a hotspot from a correctness
loop. In the baseline JFR, 521 of 1961 CPU samples (26.6%) ended in
`computeFqNameString`; a compilation-local `IrClass` identity cache reduced
that leaf count to 100 and the 60-second sampled TLAB allocation weight from
about 120 GB to 69.85 GB. The final instrumented producer observed 320,458
cache hits among 320,718 classifier queries over only 260 unique declarations
(99.92%). No mapped CLR type, target-profile decision, assembly-reference side
effect, or live emitability result is cached.

A later profile of the heaviest library-publication testcase found a separate
repeated-identity cost in the consumer's Kotlin-to-CLR binding index. Shared
KLIB serialization computes one public `IdSignature` per IR declaration in a
declaration table; the .NET consumer instead rebuilt and rendered that same
signature for repeated class, function, enum-entry, interface-default, generic
bridge, and covariant-bridge queries. `DotNetExternalDeclarations` now retains
the final kind-prefixed binding key by IR identity for the lifetime of that one
binding index, including negative results, and rejects inconsistent kind reuse.
It is deliberately not shared across lowering phases because local IR can still
change, matching the JVM bridge-cache warning against retaining a physical
signature beyond the IR shape from which it was derived.

On the same JFR-instrumented publication case, recording duration changed from
67 to 62 seconds and total sampled allocation weight from about 15.30 to 14.42
GB. More importantly, the directly attributable external-binder ABI-key stacks
changed from three samples/53.9 MB to zero: the previous samples came from
interface-default class forwarders, generic-interface view bridges, and
covariant-return bridges. Overall wall time and sampled totals remain noisy and
are not a promise of a fixed five-second gain. Remaining ABI-key samples belong
to producer-index construction and canonical-interface slot naming and must be
profiled as separate owners before either is changed.

The shared compiler performance reporter now recognizes `DotNet` as its own
`PlatformType`; `-Xreport-perf` and JSON performance dumps no longer fail with
`Unexpected platform DotNet (dotnet)`. The correction also exposed and removed
overlapping phase accounting. In-memory KLIB metadata/IR serialization is
reported as `IR SERIALIZATION`, backend lowerings as `IR LOWERING`, and CIL
emission plus assembly as `BACKEND`. Packing the self-describing KLIB resource
is a dynamic backend subphase because its physical declaration index exists
only during emission; it is not misreported as an overlapping top-level KLIB
writer.

Two isolated installed-stdlib scale series exercised generic interfaces,
default bodies, inheritance, method generics, function types, properties, and
publication at 25/50/100 and 100/200/400 declaration families. In the larger
series, 1,404/2,804/5,604 user lines changed backend time from 809 to 983 to
1,252 ms and IR lowering from 385 to 554 to 820 ms. The sum of the measured
top-level compiler phases changed from about 5.50 to 7.11 to 9.67 seconds.
Four-times the source therefore produced roughly linear variable cost and no
quadratic emitter or lowering signal. A 20 ms process observation deliberately
distorted wall time and is not a benchmark, but established that the heaviest
publication test starts five ILAsm processes; inspection confirmed five
different producer/profile products rather than repeated assembly of one
fixture. Ordinary codegen tests already consume the reusable profile products
selected by the test-product ADR. Direct PE writing, emitter parallelism,
broader caches, and cross-test product sharing remain unjustified without a new
profile showing a material cost and a preserved isolation/freshness proof.

That cache did not explain the apparent multi-hour product emission. A nested
Stdlib declaration removed from the live codegen set was repeatedly
reconstructed by a resolution-only fallback, so the diagnostic fixpoint made
thousands of rounds without changing state and never reached ILAsm. One
diagnostic run was stopped after nearly four hours; it was not a completed
timing baseline. Making the selected local declaration set authoritative and
requiring monotonic fixpoint progress reduced an exact cold net48
Runtime+Stdlib producer to 23.6 seconds including assembly; the corresponding
net10 producer completed in 65.6 seconds. The next performance work remains
profile-guided and should measure per-test product, ILAsm, and CLR process
counts rather than infer another emitter hotspot from aggregate wall time.

An earlier structured-CLI review replaced the serializer input's
ILAsm-shaped version string
with the equivalent dotted CLR assembly-identity value; emitted CIL is
unchanged. That head repeated all 6 model tests, the backend
compile, and the two exact affected end-to-end consumers: portable
Kotlin-library `AssemblyRef` production and foreign-CLR reference production
across both runtime profiles. Those focused checks completed green in 18.5s
and 4m21s respectively.

The FIR/IL/box cumulative JUnit suite time fell from 3713.33 seconds on the
callable-parameter head to 490.85 seconds on that structured-CLI head. Ordinary
test modules no longer rebuild `Kotlin.Stdlib`; two exact-profile fixture
producers do so once.
The retained explicit source-product case still validates the complete emitted
stdlib IL. Moving modern ILAsm compatibility to its eight-shape class removes
318 redundant external writer invocations without dropping canonical assembly
of any golden. Compiling `arrayIterators` through the normal DLL consumer path
also exposed and now pins the required erased `Iterator.Next(): object` bridge
for an `IntIterator` subclass, which the former same-run bootstrap path hid.

Before the aggregate, the function-declaration-flags candidate separately
passed an explicitly typed `KFunction0`, inline, inherited operator, infix,
constructor, and ordinary negative shapes across both parsers and runtime
profiles; the complete IL suite on both parsers; and separate
portable-KLIB/imported-CLR/Roslyn/C# boundaries. It also passed producer- and
consumer-created references, stale-library-ABI and runtime-surface rejection,
and complete source-product publication. The preceding named-call candidate
passed eight semantic function and property lanes across both parsers and
runtime profiles, ordinary and generic-interface default boundaries, and its
corrected publication matrix. The earlier structured-CLI head passed the
packed-KLIB loader owner suite (8 tests), the
BTA API-dump and FIR2IR test-generation owners without tracked generated
churn, focused callable-parameter execution across both parsers and profiles,
twelve updated callable/property IL golden cases across both parsers and every
available compatible assembler, the separate KLIB/Roslyn/C# boundary test,
and five focused stdlib-source/product/portable-ABI tests.

The target now compiles the authoritative Common `ClosedRange`,
`OpenEndRange`, floating/comparable range, signed `Char`/`Int`/`Long`
range/progression, progression-utility, and primitive-iterator sources. The
repository's shared `RangeContainsLowering` and `ForLoopsLowering` replace the
former counted-loop matcher; materialized ranges and optimized loops therefore
share the same Kotlin model used by the mature targets. Primitive arrays and
progressions return the real eight Common primitive-iterator base classes
rather than aliases of erased `Iterator`.

The generator-owned product includes signed non-random range operations,
array `lastIndex`/`indices`, exact `Char.code`, and Common `repeat`; private
`until`, `downTo`, and counted-loop bootstrap markers are gone. Adversarial
tests cover empty/extreme ranges, positive and negative steps, iteration and
exhaustion, nullable/mixed contains, non-local return, exact physical fields
and signatures, separate and installed consumers, portable execution, and a
truthful raw C# concrete-class view on Framework CLR and CoreCLR. A general
array-intrinsic result-type correction preserves exact concrete/method-generic
vector elements while treating a star-projection capture as its fixed
`Any?`/`object` read; both IL text and execution pin the absence of an invalid
free CLR `!n` token.

`Set`, `MutableSet`, `Map`, `MutableMap`, `Map.Entry`, and
`MutableMap.MutableEntry` now use one declaration-erased CLR TypeDef and one
virtual slot family per Kotlin declaration. Runtime surface level 16 owns that
public contract. Kotlin `HashMap` follows the shared Native/Wasm open-addressed
algorithm with erased private object-vector key/value storage; `HashSet` is its
set facade. Null keys/values, collisions, resize and upstream compaction, live
views, mutable entries, fail-fast iteration, ordering and builder sealing run
on Framework CLR and CoreCLR. Logical `LinkedHashMap`/`LinkedHashSet` remain
aliases over the insertion-ordered implementation and do not invent extra CLR
TypeDefs.

The bootstrap generator now owns separate Common `MapsKt` and `SetsKt`
facades beside `CollectionsKt`, mirroring source ownership and avoiding CLR
generic-receiver erasure clashes. It admits exact Common factories,
conversions, Map filters/transforms/plus/minus and generated Iterable/object-
array association, eager grouping, distinct, snapshots and Set algebra. A
staged Common `Kotlin.Test.dll` compiles `IteratorsTest.kt` and
`HashMapCompactTest.kt` unchanged against one portable stdlib and executes the
product on both runtimes.

The same tranche closes compiler-wide boundaries exposed by those Common
bodies: a final inherited method can satisfy a newly declared interface
through one private forwarding MethodImpl; a terminal literal `while (true)`
has no verifier-visible fallthrough; Common AbstractMap cache accesses emit
the exact CLR `volatile.` prefix; and stdlib emission binds admitted helpers
locally without a self AssemblyRef. The volatile annotation is resolution-
only and contributes no TypeDef or physical ABI row. The gate covers both
frontends, both runtime profiles, exact IL, portable/separate/installed
products, C# metadata/calls, stale runtime rejection, and hostile callback,
iterator, collision, nullable and non-local-control-flow cases.

All four PSI/LightTree and Framework/CoreCLR runners execute the target-owned
contracts/scope corpus plus three selected upstream contract tests. The test
pipeline now runs the same shared pre-serialization KLIB lowerings as the CLI;
Common `SharedVariableBox` therefore replaces the obsolete test-only mutable
capture cell, and Common non-JVM `ThrowHelpers.kt` is a real stdlib compiler-ABI
dependency. Direct and installed stdlib products, including a separate local-
delegate consumer, prove that source closure without enabling the separately
parked `lateinit` lowering.

Source-defined exception subclasses now inherit standard `Throwable.message`
and `cause` through the universal `System.Exception` virtual slots. The
structural fallback accepts only fake overrides whose real chain remains in
the mapped standard exception hierarchy; a derived user-defined `message`
override remains ordinary Kotlin virtual dispatch. PSI, LightTree, Framework
CLR, CoreCLR, and an installed cross-assembly consumer cover both sides.

Ordinary Kotlin enums are one reference-class hierarchy, never CLR
`System.Enum` value types. The one erased physical `Kotlin.Enum` base now lives
in `Kotlin.Runtime`; Runtime has no upward Stdlib reference, while concrete
Stdlib/user enums retain their own owners. Runtime now also owns the physical
member-free erased `EnumEntries` interface needed by its `KVisibility` enum;
the authoritative Common declaration, `EnumEntriesList` implementation,
factories, and algorithms remain Stdlib-owned, and Stdlib emits no duplicate
interface TypeDef. Entry fields retain
singleton identity and source order; private entry subclasses implement bodies
and abstract members; `values()` is fresh, `entries` is stable, and `valueOf`
uses exact Kotlin names and failure semantics. Both frontend paths execute the
complete adversarial corpus on Framework CLR and CoreCLR. A portable library
is separately consumed by Kotlin and C# on both runtimes, including exact
nested TypeRefs, entry-field metadata, marker attributes, virtual dispatch,
static initialization, arrays, widened `Enum<*>`/`EnumEntries<*>` views, and
same-declaration ordering across distinct entry subclasses. A different enum
presented through an unchecked Kotlin generic view or C# `IComparable` fails at
comparison with the classified `InvalidCastException`/`ClassCastException`.
Reified enum helpers remain fail-closed.

The final gate additionally covers generic-interface erasure on both runtime
profiles. Both owner-dependent `C<T> : I<T>` and closed `C<T> : I<String>`
implement the one erased Kotlin-owned `I`. A modern default lives on that
erased DIM; portable profiles use its recorded helper/forwarder path. Neither
case publishes an implicit CLR `I<T>` sibling. PSI and LightTree agree on
every updated physical shape.

The formerly red generic-class probes are now positive regressions. Widened
Common `containsAll` compares a wrong-shaped element and returns `false`
without premature narrowing, while mutation through an unchecked
`Box<String> as Box<Int>` changes the same object and fails only when the
result is later consumed as `String`. The same corpus covers direct and nested
owner-type inputs, argument identity/evaluation, and a three-level widened
override chain on both frontends and runtime profiles.

All compiler-argument, API, configuration-key, Gradle-option, and test-runner
generators owned by the affected upstream range were rerun through their
owning tasks and produced no tracked output changes. Upstream Test Federation
now treats the .NET FIR, IL-text, and box runners as compiler-domain tests
through the same shared target-specific runner pattern used by JVM, JS, Wasm,
Native, and JKLIB.

Focused evidence additionally covers component-complete packed-KLIB loading,
same- and cross-library inlining from prepared and main IR, all three KLIB
inliner modes, mutable capture and non-local control flow, compiler ABI and
friend access, stdlib-free diagnostics, reproducible direct/fallback stdlib
IR, explicit reified/suspend rejection, and every target/runtime profile. The
selected-graph closure additionally proves that an inline body from library A
binds exact public declarations and a nested inline body from explicitly
selected library B without a general linker; surviving B calls use B's exact
physical assembly while fully inlined A disappears as a runtime dependency.
Both prepared-IR and main-IR consumers reject an omitted B with named unbound
signatures before target lowering and leave no artifact. The
collection product now also proves empty Collection fast paths, exact
short-circuit and traversal counts, nullable/widened predicates, reverse List
search, inlined separate consumers, and direct CIL execution of all six
physical fallback methods on Framework CLR and CoreCLR. The classified
`CharSequence` carrier additionally proves unchanged `System.String` and
custom-implementation identity, shared operation/cast/type-test
classification, erased physical CLR bounds with authoritative KLIB bounds,
portable Kotlin-library consumption on both runtimes, and handwritten C#
implementation through the runtime manifest. The collection-to-array closure
additionally proves exact Common iteration, erased and typed results, nullable
and value elements, undersized allocation, oversized and empty destination
identity, non-Java tail preservation, covariant runtime vector identity,
negative-size failure, and hostile inaccurate-size behavior on Framework CLR
and CoreCLR.

The complete signed Common primitive-array family additionally proves exact
Kotlin-owned `BooleanArray`, `ByteArray`, `ShortArray`, `IntArray`,
`LongArray`, `FloatArray`, `DoubleArray`, and `CharArray` identities over their
private CLR vectors. Constructors, initializer order, literals, varargs and
spreads, direct and escaping specialized iteration, copies/content operations,
RTTI/casts, generic-array separation, bounds failures, portable Kotlin
libraries, and copy-free exact C# vector adapters execute on both frontends and
runtime profiles. In particular, Kotlin `ByteArray` projects as signed
`sbyte[]`/`int8[]`, never C# `byte[]`.

Concrete nullable primitive elements are now complete as ordinary invariant
generic arrays. `Array<Boolean?>` through `Array<Char?>` use exact closed CLR
`Nullable<V>[]` vectors and retain identity through literals, constructors,
generic substitution, nullable varargs, iteration, copies/content operations,
nested arrays, and exact casts on both frontends and runtimes. A portable
netstandard2.0 Kotlin library is consumed separately by Kotlin and Roslyn on
Framework CLR and CoreCLR; all eight natural C# `V?[]` signatures preserve
aliasing. Backend-reachable sentinels continue to reject open `Array<T?>`,
input projections, and value-vector covariance.

Star-projected generic arrays are now complete through one classified erased
view. Every exact reference, value, and nullable-value SZ vector widens to
`System.Array` without copying and retains identity for size, reads, iteration,
reference equality, and later exact casts. Runtime tests and checked/safe casts
share one SZ-array classifier; specialized Kotlin primitive-array wrappers,
rectangular CLR arrays, and rank-one non-zero-based CLR arrays remain outside
the Kotlin `Array<*>` identity. Portable netstandard2.0 Kotlin libraries are
consumed separately by Kotlin and Roslyn on Framework CLR and CoreCLR, while
input projections, open nullable elements, and value-vector covariance remain
negative.

Every Kotlin-owned ordinary generic class now has one canonical non-generic
owner, one erased runtime/virtual ABI, and one authoritative state. KLIB
remains authoritative for type parameters, bounds, variance, arguments,
projections, and nullability. Public/protected owner-dependent positions and
the current baseline storage use an accepted erased carrier, an erased upper
bound, or `object`; reads narrow only at their logical use site. Ordinary CLR
`castclass`/`isinst` over the one owner supplies Kotlin's declaration-erased
identity, including inherited and separate-library cases. The baseline private
layout is not an ABI freeze: removable measured specialization may later use
more exact CLR helpers or storage without changing any semantic observation.

Physical ABI 20 records that one erased generic owner plus producer-owned enum
entry fields. It retains the removal of class capability paths, class-member
bridge records, canonical class interfaces, ancestry classifiers, and typed-
dispatch probes. Imported CLR generics remain reified. Typed C# generic-class
export is a separate fail-closed product rather than an implicit second
implementation ABI.

An erased generic class also no longer fabricates a typed generic-interface
edge: both `C<T> : I<T>` and `C<T> : I<String>` implement the one erased `I`
when `I` is Kotlin-owned. Only an explicitly mapped host capability or imported
CLR interface may retain a separate typed edge. KLIB preserves `I<T>` and
method bounds such as `<R : T>`; the latter omit an impossible CLR
owner-relative constraint.

The complete collection-facing accumulator-fold family now uses the generated
Common bodies for `Iterable.fold`/`foldIndexed` and
`List.foldRight`/`foldRightIndexed`. Adversarial execution pins empty behavior,
nullable and widened values, left/right order, exact iterator protocols, index
association, capture, exception identity/timing, and non-local return. Separate
and installed consumers inline the packaged KLIB bodies, while handwritten CIL
executes every physical fallback on Framework CLR and CoreCLR. A discarded
cross-library fold result retains the authoritative erased-accumulator
`IMPLICIT_CAST`; no failed-cast optimization or new classifier was introduced.

The complete collection-facing receiver-seeded reduction family now likewise
uses the exact generated Common bodies for `Iterable.reduce`, `reduceIndexed`,
their nullable empty variants, and all four corresponding right-reduction List
forms. Physical methods preserve the Common `S, T : S` bound as a real CLR
generic-parameter constraint; open `S?` uses the accepted boxed-or-null fallback
slot while embedded KLIB remains authoritative. Adversarial execution pins exact
empty exception messages or null, singleton no-callback behavior, widened and
nullable accumulators, left/right order and index association, hostile iterator
protocols, operation-failure identity, and non-local return. Separate consumers
inline every packaged body, and handwritten CIL executes all eight fallbacks on
Framework CLR and CoreCLR. Binary inlining's explicit `Nothing?` nullable branch
reuses the existing bottom/null-carrier emission rather than introducing a cast
or classifier.

Common `Iterable.forEach` and `forEachIndexed` use their exact generated inline
loops, and the completed `apply`/contracts product now composes them into the
exact generated `onEach`/`onEachIndexed` same-receiver pair. The embedded KLIB
retains `forEach`'s binary `HidesMembers` directive: a separate hostile consumer
with a same-signature member still resolves and inlines the Common extension,
without requiring a CLR runtime attribute. Adversarial execution pins empty,
singleton, nullable/value, mutation, identity, order/index, exception identity,
stopping point, and non-local-return behavior. Both indexed bodies retain the
Common overflow helper, while handwritten CIL executes all four physical
fallbacks and checks full callback traces on Framework CLR and CoreCLR.

Common `Iterable.first(predicate)` and `firstOrNull(predicate)` now use their
exact generated first-match loops. Adversarial execution pins empty and
no-match behavior, the exact Common `NoSuchElementException` message,
short-circuit traversal, nullable match versus absence, widened elements,
capture, predicate-failure identity/timing, and non-local return. Separate and
installed consumers inline only the predicate overloads while existing no-arg
fallback calls remain; handwritten CIL executes both new physical overloads on
Framework CLR and CoreCLR. Open `T` and boxed-or-null `T?` reuse their existing
physical slots.

Common last-match predicates now use all four exact generated bodies for
`Iterable.last`/`lastOrNull` and their `List` overloads. Iterable receivers scan
forward to exhaustion and preserve a separate found flag where Common requires
one; List receivers request `listIterator(size)` and short-circuit in reverse.
Adversarial execution pins both exact no-match exception messages, empty and
nullable-match behavior, full versus reverse traversal, hostile iterator
protocols, widened/value elements, capture, predicate-failure identity/timing,
and non-local return. Separate and installed consumers inline all four bodies,
while handwritten CIL executes all four physical fallbacks on Framework CLR
and CoreCLR. The open-`T` cast uses the existing checked generic result barrier;
failed typed uses remain an exceptional correctness path and are not optimized.

The first Common `@InlineOnly` batch now publishes 14 exact generated
declarations: List components 1 through 5, List `elementAt` and
`elementAtOrNull`, Iterable `find`, Iterable/List `findLast`, the two
first-non-null transforms, Collection `count`, and Iterable `asIterable`.
Their public logical declarations and bodies remain authoritative in embedded
KLIB, while each physical CLR MethodDef is assembly-visible and unavailable as
C# or cross-assembly fallback API. Separate, packaged, and installed Kotlin
consumers must inline every call. Tests prove direct/reverse access without
the wrong iterator, traversal and callback order, nullable and exception
behavior, object identity, non-local return, physical visibility, C#
inaccessibility, and absence of external inline-only calls on Framework CLR
and CoreCLR. The producer/consumer tests use an actual self-describing KLIB;
the same-frontend bootstrap box harness is not misrepresented as an external
library boundary.

Common `Iterable<T>.sumOf` now publishes its complete signed selector family:
`Int`, `Long`, and `Double`. Their assembly-visible CLR fallbacks use the exact
generator-owned `sumOfInt`, `sumOfLong`, and `sumOfDouble` spellings because the
three logical overloads erase to the same CLR `Function1` parameter shape.
KLIB retains logical `sumOf`; separate and installed consumers inline every
body and cannot call the fallbacks, while C# cannot bind them. The exact Common
`ExperimentalTypeInference` and `OverloadResolutionByLambdaReturnType` marker
declarations now participate in the stdlib source product and receive truthful
physical TypeDefs, but their BINARY applications remain KLIB-only rather than
CLR custom attributes. Adversarial portable execution pins empty zero, `Int`
and `Long` wrapping overflow, ordered IEEE `Double` addition and NaN,
nullable/widened inputs, traversal and callback order, failure identity, and
non-local return across Framework CLR and CoreCLR. UInt and ULong remain
outside this closure because their scalar/runtime and generated-stdlib product
has not yet been admitted on top of the completed single-field value-class
foundation.

Common `Iterable.single(predicate)` and `singleOrNull(predicate)` now use their
exact generated bodies; Common defines no distinct List predicate overload.
Both retain the first match with a separate found flag and stop at the second
match. Adversarial execution pins zero, unique, and multiple-match behavior,
the exact Common no-match and multiple-match exceptions, second-match stopping,
nullable unique null, widened/value elements, capture, predicate-failure
identity/timing, and non-local return. Separate and installed consumers inline
both bodies, while handwritten CIL executes both physical fallbacks on Framework
CLR and CoreCLR. The existing open-`T` cast and boxed-or-null slot remain the
only physical adaptation; LINQ defaults and target-authored traversal were not
introduced.

Common `Iterable.none(predicate)` and `count(predicate)` now complete the
selected predicate-aggregate family alongside `all` and `any`. Exact Common
empty-Collection fast paths avoid iterator construction; `none` stops at the
first match, while predicate `count` consumes the receiver and calls Common
`checkCountOverflow` after each matching increment. Adversarial execution pins
fast paths, traversal/stopping counts, nullable and widened predicates, capture,
predicate-failure identity/timing, and non-local return. Separate and installed
consumers inline both bodies and retain exactly the required count-overflow
helper call; handwritten CIL executes both physical fallbacks on Framework CLR
and CoreCLR. No LINQ quantifier/count rewrite or target-owned loop was added.

The complete signed Common `Iterable.average` family now uses all six exact
generated bodies for Byte, Short, Int, Long, Float, and Double receivers. Each
physical fallback accumulates into `Double` in encounter order, increments an
`Int` count through Common `checkCountOverflow`, and returns `Double.NaN` for an
empty receiver. The logical overloads bind to the bounded Common/JVM platform
names `averageOfByte` through `averageOfDouble`; embedded KLIB remains the
authoritative logical `average` contract and no general .NET meaning was given
to `@JvmName`. Adversarial execution pins all six conversions, empty NaN,
floating-point order, full traversal, and iterator-failure identity. Separate
and installed consumers bind all six physical names, while handwritten CIL
executes every fallback on Framework CLR and CoreCLR. LINQ `Average`, wider or
checked counters, reordered summation, and target-owned bodies were not added.

The post-substitution reified-array audit now proves that every admitted
ordinary array carrier remains truthful after the shared inliner has replaced
a type parameter with a concrete type. The adversarial matrix covers reference,
scalar, nullable-scalar, classified `CharSequence`, generic-class, split-
interface, nested, star-element, primitive-array-wrapper, and `Throwable`
elements; empty, nullable, initialized, negative-size, vararg, and spread
operations execute on both FIR frontends and runtime profiles. These operations
reuse the ordinary `Array<E>` mapper and intrinsics. No reified-only token,
wrapper, or `object[]` fallback was added. The later complete reified tranche
now consumes this carrier matrix without changing it.

The Common `KClassifier`/`KClass` floor and class literals are now complete
without equating Kotlin reflection identity with `System.Type`. Static
`C::class` and single-evaluation dynamic `value::class` produce one nominal
Kotlin runtime value whose classifier is exact where CLR identity is exact and
classified where Kotlin already has a broader or erased relation. This covers
signed scalars, `String`, `Any`, `Unit`, `Nothing`, primitive and generic
arrays, `CharSequence`, `Number`, mapped and custom exceptions, ordinary and
generic Kotlin classes/interfaces, local/anonymous names, Common `cast` and
`safeCast`, and declaration-erased generic-class ancestry. Equality and hashes
use normalized classifier identity rather than names; two same-named CLR
classes from distinct assemblies remain distinct. Exact Kotlin exception
constructor identity reuses weak identity-associated throwable state and never
wraps or mutates foreign `Exception.Data`. Portable Kotlin libraries are
consumed separately by Kotlin and Roslyn, installed stdlib products expose only
the public Common surface, and the retained `System.Type` bridge remains
compiler ABI. This floor now supports substituted `T::class`; member and
annotation reflection remain separate programmes.

The Common logical `KType`/`typeOf` graph is now complete as a layer above that
nominal floor. A post-inlining lowering builds classifiers, nested arguments,
stars, variance, nullability, declaration parameters, and recursive bounds in
two phases, so recursive parameter identity is preserved without using CLR
generic instantiations as Kotlin identity. Exact CLR classifiers reuse their
`KClass` evidence; logical classifiers without a truthful `System.Type` carry a
separate KLIB-mangled identity key and never compare by display name. Runtime
surface 17 pins that compiler/runtime construction ABI.

The selected upstream matrix runs on both FIR frontends and both runtime
profiles. It covers nested and reified types, projections, equality and hashes,
recursive and nullable relative bounds, and a real self-describing portable
library consumed independently by Kotlin on Framework CLR/CoreCLR and by
Roslyn. A bound such as `X : Y?` remains exact in KLIB and `KType`, while its
unrepresentable CLR `GenericParamConstraint` is deliberately omitted rather
than strengthened to `X : Y`.

Common `Comparable<in T>` now retains its full logical identity and recursive
bounds in KLIB while one object exposes the profile-selected canonical
`System.IComparable` and truthful typed `System.IComparable<T>` views. Kotlin
implementations fill both slots through the explicit Comparable mapping bridge
lowering; ordinary C# consumes either interface without an adapter. Logical
interface and type-parameter calls use one versioned semantic helper so String
comparison remains ordinal and Float/Double retain Kotlin NaN and signed-zero
ordering. Direct exact primitive operations keep their unboxed intrinsics.
Tests cover every selected Common scalar, custom and inherited implementations,
recursive bounds, contravariance, type tests, checked/safe/unchecked casts,
delayed mismatches, exact carrier preservation through inferred array common
types, physical MethodImpl rows, portable Kotlin consumers, and both canonical-
only and typed CLR foreign boundaries on Framework CLR and CoreCLR. Runtime
surface level 12 owns the helper; typed polymorphic fast paths remain unselected.

The builder, contracts, and Common abstract-collection tranches publish the
authoritative Common `Appendable`, complete `StringBuilder` including both
`buildString` declarations, generated `Iterable.joinTo`/`joinToString`, Common
`AbstractCollection`/`AbstractList`, the public contracts DSL/effect model, and
the complete Common `Standard.kt`, including `repeat` over the real signed
range/progression closure.
The Kotlin-owned builder wraps private profile-selected BCL storage without
exposing `System.Text.StringBuilder` in public or protected metadata; its
colliding `Any?` overloads have the stable physical names `appendAny`,
`insertAny`, and `appendLineAny`, while KLIB retains the Kotlin names.
`ArrayAsList` inherits the Common base and owns only its retained array, size,
and indexed access. Logical class covariance remains in KLIB while each
Kotlin-owned generic class has its single erased CLR owner.
Source and actualized/deserialized inner generic declarations normalize to the
same outer-first TypeDef arity, closing the separate-library path exercised by
the Common iterator and sublist implementations. The exact Common bodies also
closed general backend gaps for `Int`/`Long` bitwise shifts, Unit-valued effects
in value positions, smartcasts from open type parameters, and projected generic
array reads and writes; none is encoded as a builder-specific rewrite.

FIR consumes Common contracts for data flow, and embedded KLIB retains their
effects across library boundaries. The backend executes neither the DSL nor a
target-authored approximation: `contract` has an assembly-visible fail-safe
physical body under the existing inline-only ABI, while executable consumers
contain no DSL call. The complete Common `run`, `with`, `apply`, `also`, `let`,
`takeIf`, and `takeUnless` bodies preserve calls-in-place analysis, receiver
identity, exceptions, and non-local returns. `InvocationKind` uses the ordinary
Kotlin enum representation.

The exact first CLR contract projection is now complete as an additive export
view. FIR2IR derives a versioned neutral five-effect carrier from resolved
Common contracts; only an explicitly selected export consumes it. `net10.0`
emits the exact `System.Diagnostics.CodeAnalysis` TypeDefs supplied by
`System.Runtime`, while `net48` and `netstandard2.0` omit them because their
selected contracts do not contain those identities. The gate proves both FIR
parsers, every admitted attribute target and constructor payload, Roslyn
nullable flow with warnings as errors, overlap normalization, default-overload
parameter omission, absence on ordinary Kotlin MethodDefs and compound
effects, and a Kotlin consumer of a reassembled DLL after every derived
CodeAnalysis row was stripped. KLIB remains the independent authority.

## Current architecture

- `:core:language.targets.dotnet` owns the logical .NET platform and the
  `net48`, `netstandard2.0`, and `net10.0` target vocabulary.
- `:compiler:config.dotnet` owns generated compiler keys and target-policy
  validation without depending on FIR, IR, backend, or CLI code.
- `:compiler:frontend.common.dotnet` owns objective PE/ECMA-335 facts and
  physical CLR validation; FIR owns Kotlin interpretation.
- `:compiler:dotnet.imports` owns versioned, self-validating neutral carriers
  for selected foreign CLR linkage and the already-derived exact contract
  export subset.
- `:compiler:fir:fir-dotnet` owns foreign Kotlin projection and lazy FIR symbol
  construction without depending on backend or CLI implementation packages.
- `:compiler:fir:fir2ir:dotnet-backend` owns narrow success-only IR
  overridability rules for retained flexible CLR array and method-generic
  declarations and derives the neutral exact-contract projection while resolved
  FIR and IR coexist.
- `:compiler:ir:backend.dotnet` owns Kotlin-to-CLR representation policy,
  target-profile legalization, IR lowering, physical-form construction, and
  backend product orchestration.
- `:dotnet:dotnet.ir` owns migrated policy-free physical ECMA-335 vocabulary,
  structural validation, deterministic CIL serialization, and eventually the
  already-selected JVM-hosted direct PE sink. Its first production-owned form
  is external `AssemblyRef` metadata.
- `:compiler:ir:serialization.dotnet` owns .NET KLIB IR serialization and the
  logical IR mangler shared with backend identity mapping.
- `cli-base` owns the .NET content-root carrier; .NET compilation no longer
  represents CLR roots as JVM classpath roots.
- Common and generated stdlib sources remain semantically authoritative.
  .NET supplies narrow actuals and irreducible CLR operations.
- Kotlin-produced libraries are self-describing DLLs. KLIB remains the exact
  Kotlin declaration contract; CLR metadata and standard attributes provide
  the truthful physical and foreign-language view.

## Active state

No implementation slice is half-landed. The complete Common signed-selector
`sumOf` family is published under its generated logical declarations and the
pinned `sumOfInt`, `sumOfLong`, and `sumOfDouble` physical spellings. Its exact
type-inference markers are present as Kotlin declaration TypeDefs while their
BINARY applications remain KLIB-only. The Common `@InlineOnly` physical ABI
remains selected and
its first 14-declaration generated collection batch remains published with
assembly-visible physical bodies and mandatory external KLIB inlining. The
generated Common signed numeric averages remain published with
all six bounded physical names and exact KLIB bodies. The generated Common
first-match predicate pair remains published with
both physical fallbacks and inlinable KLIB bodies. The preceding iteration-
action family now includes generated `forEach`/`forEachIndexed` and
`onEach`/`onEachIndexed`; the latter pair preserves its open method-owned `C`
through one erased `Iterable` constraint and same-object result. The void pair
retains the authoritative `HidesMembers` compiler directive. The
receiver-seeded reduction family remains published with all eight fallbacks;
an inlined empty nullable branch uses the existing nullable-bottom carrier
path. The accumulator-fold family likewise remains published, and a discarded
substituted generic fold result performs its existing checked recovery before
being discarded. Kotlin-owned generic classes and interfaces use physical ABI
20's one erased owner; the superseded bounded typed-dispatch experiment
remains only as Git history and design evidence. Ordinary
non-reified inline bodies now
bind exact signatures throughout the complete frontend-selected dependency
graph. Resolution remains non-linking, and an incomplete graph fails at the
post-inline/pre-target-lowering boundary instead of crashing an arbitrary
lowering. The non-reified Common collection-to-array closure uses the exact
shared loops. Its narrow CLR actual reproduces a supplied vector's runtime
element type, retains sufficiently large destination identity without JVM's
Java-specific tail terminator. Public reified `toTypedArray` now composes that
same loop with shared call-site substitution. The backend's
explicit erased-object cast to an open type parameter uses `unbox.any`; safe
generic casts remain unsupported. The Kotlin-owned builder, exact generated
joins, Common abstract bases, and migrated array-backed list are published.
The public Common contracts DSL/effects, ordinary `InvocationKind`, scope
functions through `takeUnless`, and both `buildString` declarations are now in
the same self-describing stdlib product. Runtime surface level 16 owns the
erased compiler mutable cell and the complete admitted erased Kotlin
collection-interface surface, including Set/Map and both nested entry
interfaces.
The Common Map/Set source and generated families are published through their
own source-aligned facades. The Native/Wasm-derived `HashMap`/`HashSet`
implementation keeps one erased state, while Roslyn sees only truthful
non-generic low-level Kotlin types. External BCL generic collection identity
and future typed exports remain separate interop products.
The exact Common-contract export subset is additive and complete for
`NotNull`, `NotNullWhen`, `NotNullIfNotNull`, `DoesNotReturnIf`, and
`DoesNotReturn`. Its neutral carrier contains neither FIR/IR nodes nor the
authoritative contract graph; ordinary Kotlin declarations and profiles
without the exact standard TypeDefs remain physically unchanged.
Valued annotation classes are admitted generally; ordinary enums, the
non-reified `EnumEntries` core, and the reified Common enum helpers are now
published. The classified `CharSequence` carrier, Common collection
predicates, and complete ordinary/reified inline boundary remain intact;
suspend inline now composes with the continuation/state-machine foundation.
The nominal `KClass` floor and
logical `KType`/`typeOf` graph are selected and published; they do not imply
member reflection.

Foreign CLR method generics now enter the Kotlin model through the same
frontend-first boundary as JVM foreign generics. One method-owned type-parameter
graph supplies inference, bounds, overloads, calls, overrides, callable types,
and reflection, while the selected MethodDef supplies exact `!!n`/MethodSpec
binding. The admitted grammar is intentionally closed; unsupported special or
constructed constraints and explicitly nullable unconstrained generic leaves
evict the complete interface rather than creating a partially truthful API.

The coroutine continuation ABI now has an objective portable-library proof in
addition to semantic execution and final-IR validation. Public top-level and
virtual suspend MethodDefs keep ordinary parameters followed by the one erased
`Continuation` and return `Object`; private non-tail state machines extend the
one Stdlib base without publishing their layout. Both runtime profiles consume
the same portable producer and execute delayed top-level and member resumption.
`Task`/`ValueTask` remain export adapters rather than an alternate lowering.

Kotlin annotation classes use the shared Common annotation-member generator on
one concrete sealed CLR `System.Attribute` subtype. Ordinary Kotlin
construction, defaults, nested values, arrays, equality/hash/string behavior,
NaNs, signed zero, and separate KLIB consumption therefore share one runtime
identity. KLIB remains authoritative for declaration identity, complete
values, targets, retention, and applications. Runtime-retained applications
receive an additional CLR row only when the complete parent, constructor, and
fixed-argument blob are exact; unsupported `KClass`, Kotlin enum, nested
annotation, primitive-array-wrapper, open, generic, or non-constant shapes
remain KLIB-only. Source/binary applications remain absent from CLR reflection.
The gate directly adopts seven compatible upstream annotation-instance tests
in all four .NET runners, adds exact IL blobs and nested metadata parents, and
proves portable Kotlin defaults plus bidirectional typed C# application and
reflection on Framework CLR and CoreCLR.

Class- and callable-level runtime annotation discovery are now selected as
JVM-shaped platform extensions above Common `KClass` and `KCallable`.
Kotlin-produced values are reconstructed from private executable factories
derived from the KLIB-authoritative IR, so exact and KLIB-only values share the
existing annotation objects and projected CLR rows never become a duplicate
authority. Classifiers retain the producer-assembly marker and foreign-only
class path. Function, constructor, and property references carry their
declaration annotations on the existing executable object; imported CLR
methods and properties use the retained declaring type plus exact metadata
token and read only that direct row. Property applications are never merged
with getter/setter applications. Runtime surface level 18 owns this callable
transport; the physical declaration-index schema remains unchanged.
Adversarial coverage exercises defaults, nested values, arrays, enums,
`KClass`, repetition, retention, local/generic/interface classes, empty and
bound/unbound callable references, invocation/mutation identity,
property/accessor separation, read-only list behavior, separate KLIB
consumption, exact foreign CLR method/property attributes, both runtime
profiles, and `-no-stdlib` compilation. Member enumeration/invocation and
type-use annotation owners remain separate reflection decisions.

`KCallable.returnType` now follows Native's declaration-target boundary rather
than the generated invocation adapter. Functions and constructors use the rich
reference's reflection target; properties use the original getter return type;
local delegated properties retain their declared value type. All paths reuse
the `typeOf` graph producer, including nested arguments, projections, stars,
nullability, method-owned parameters, and recursive bounds. Kotlin libraries
derive that target from embedded KLIB, while supported foreign declarations use
the importer-enhanced IR type; the runtime never reopens CLR reflection or
nullable attributes to reconstruct a signature.

The typed `KCallable` slot exposed an assembly cycle: callable interfaces live
in Runtime while `KType` previously lived physically in Stdlib. Runtime surface
level 19 therefore owns only the minimal physical `KType` interface beside
`KClass` and `KCallable`; Common behavior, `KTypeImpl`, projections, parameter
objects, equality, hashing, and rendering remain in Stdlib. Separate Kotlin
and C# consumers prove one type identity without an object bridge, wrapper, or
Runtime-to-Stdlib dependency. Target-owned adversarial coverage and two
unchanged upstream override tests execute across both FIR parsers and CLR
profiles; exact IL pins the additional graph and getter shape.

`KCallable.typeParameters` now deliberately extends that Native-shaped floor
with JVM's declaration-owned rule. Function and generic-extension-property
references expose only their own parameters in declaration order; constructors
expose the constructed class's own parameters. Enclosing parameters remain
reachable through return types and recursive bounds without leaking into the
own list. Return types, exposed parameters, and bounds are allocated in one
graph, so a classifier is the exact same object across every public view.
Bound and unbound references retain the unbound declaration owner. Runtime
surface level 20 transports that graph as one erased compiler/runtime value;
physical CLR generic parameters and runtime reflection remain non-authoritative.

`KCallable.parameters` and the JVM-shaped `KParameter` surface now extend the
same declaration graph. Unbound references expose instance, future context,
extension, and value positions in JVM order; bound receivers are omitted and
the remainder is reindexed. Types share the callable's exact type-parameter
objects, inherited Kotlin defaults remain optional, varargs retain their array
type, and equality/hashing use the actual callable object plus exposed index.
Kotlin parameter annotations come from their KLIB-derived declaration target;
foreign names, `ParamArray`, and annotations come only from exact CLR Param
rows, while CLR optional flags do not invent Kotlin default-call semantics.
Runtime surface level 21 passes one erased Stdlib factory into the Runtime-owned
callable and caches the resulting read-only list, preserving the one-way
Runtime/Stdlib dependency. Direct member-extension references remain rejected
by the Common frontend and wait for member enumeration rather than a .NET-only
syntax exception.

Positional `KCallable.call` now follows JVM's public invocation contract above
that Common reflection floor. Runtime surface level 22 checks the exact exposed
parameter count and invokes the callable's already generated erased `FunctionN`
capability; it never rediscovers a CLR member by reflection, name, token, or
signature. Defaults remain required positions, one vararg array is one
argument, a property call invokes its getter, bound receivers stay omitted,
and the original target exception propagates unchanged. The logical return
type remains KLIB-authoritative while the physical runtime slot returns
`object`. Functions, constructors, properties, virtual dispatch, generic and
extension references, wrong arity/type, separate KLIB consumption, imported
CLR declaration references, and direct C# invocation are covered independently
of the named/default invocation layer described below.

Named `KCallable.callBy` originally completed that invocation pair at runtime
surface level 23; surface 27 extended the fixed `KFunction0` to `KFunction22`
range, and surface 28 adds the big-arity `FunctionN` path plus multiword
omission masks. Exact parameter-object
presence distinguishes
explicit null from absence; omitted optional values select Kotlin defaults;
omitted varargs receive fresh arrays of the exact substituted physical type;
missing required parameters use JVM's failure contract; and unknown map keys
remain inert. Runtime owns only exposed-position interpretation. Each generated
reference makes one ordinary IR call with every optional absent; shared Common
and class/interface default lowerings select the authoritative dispatcher and
placeholder layout, after which one late .NET pass translates the runtime mask
words and selects supplied values. Generated size is therefore linear rather
than one helper per omission combination; 22 fixed and 33 big-arity dependent
defaults are covered, including supplied values on both sides of bit 31/32.
The separate-library proof also
normalized ordinary Kotlin class `$default` dispatchers to one static compiler
ABI with the receiver explicit. Kotlin-owned class parameters stay physically
erased, while genuine method parameters retain their CLR generic slots. Normal
source calls and reflection now share that helper, including inherited
defaults, virtual overrides, erased
generic owners, constructors, and both runtime profiles. Foreign CLR optional
metadata still does not invent Kotlin defaults, while foreign `ParamArray`
omission creates its truthful invariant vector carrier. No System.Reflection,
name lookup, target-exception wrapping, or second reflection-default ABI is
introduced.

The five JVM-shaped `KFunction` declaration properties now use the exact
KLIB/importer-IR target for inline, external, operator, infix, and suspend
status. They are declared once on `KFunction` and inherited by every admitted
`KFunction` arity; the physical view remains one non-generic
`Kotlin.KFunction` interface. The existing private function-reference flag
carrier supplies five inherited virtual-final getters; its base does not
implement `KFunction`, so internal adapters gain no reflection identity.
Constructors and ordinary imported CLR interface methods report false, while
resolved inherited operator status survives KLIB boundaries. Generated invoke
adapters and runtime CLR reflection never become flag authority. Publishing
`isSuspend` and `isExternal` does not itself define suspend execution or
external linkage. Library ABI 23 rejects old references without declaration
bits, and runtime surface 24 gates the five new physical getters before
execution.

Direct callable visibility and modality now follow the same JVM-shaped
declaration-fact rule. Public, protected, internal, private, final, open, and
abstract functions, properties, and constructors retain their exact logical
FIR/IR/KLIB facts across producer- and consumer-created references; local
function and delegated-property tokens return null visibility and final
modality. Admitted foreign CLR interface functions and properties obtain
public/abstract from importer IR rather than from backend-selected MethodDefs.
One shared reference payload serves functions and properties, and property
factory arguments are now bound by parameter name so later payload extensions
cannot silently corrupt the annotation-factory slot. Runtime surface 26 owns
the typed getters and ordinary `KVisibility` enum; library ABI 25 rejects old
materialized references. Separate Kotlin/C#/Roslyn and physical metadata tests
also pin Runtime's lack of a Stdlib reference and Stdlib's implementation of
the one Runtime-owned `EnumEntries` interface.

Direct property reflection now publishes JVM-shaped `isConst`, `isLateinit`,
getter, and mutable setter capabilities for the fixed property arities. Each
property wrapper creates one cached accessor object whose `KFunction`
signature and declaration facts come from the exact accessor IR and whose
execution delegates to the owning property's existing `Get`/`Set` path.
Getter/setter invocation therefore cannot diverge from property invocation in
bound-receiver handling, virtual dispatch, mutation, exception identity, or
separate libraries. A `const` reference reads its retained literal in the
private reference body and does not add a public CLR accessor MethodDef.
Runtime surface and library ABI 29 pin the new interfaces and factory payload.
Positive `isLateinit` behavior remains unavailable until the parked language
feature admits such declarations; broad member discovery, `getDelegate`, and
type-use annotation discovery remain independent.

Library ABI version 23 also retains the version-22 static ordinary-class
default-dispatch shape; runtime surface level 24 includes the version-23
`KCallable.CallBy` slot and helpers. A consumer therefore rejects both an old
library callable ABI and an old runtime instead of discovering a missing
method at execution.

The general Common Comparable mapping is independently published and the enum
product consumes the same KLIB identity, canonical classifier, typed C# view,
and semantic operation boundary rather than an enum-private substitute.

Reified inline functions now use shared IR call-site substitution as their only
semantic mechanism. The target-stage completion consumes selected KLIB bodies
after pre-serialization has preserved bodyless compiler intrinsics. Substituted
type tests/casts, nullable and bottom types, arrays, `T::class`, nested calls,
erased Kotlin generic classes/interfaces, and Common enum helpers all reuse
their ordinary runtime paths. Truthfully representable declarations receive
assembly-visible throwing remainders; signatures without one truthful open CLR
shape are omitted. Neither form enters the physical Kotlin declaration index
or explicit C# export, and cross-library calls disappear in all three KLIB
inliner modes. The completed `KType`/`typeOf` graph composes this same
substitution path; annotation discovery, future classifier families,
and coroutine-aware reflection/export remain separate programmes.
Physically exact non-generic reference casts are
complete for Kotlin classes/interfaces, imported CLR interfaces, strings,
`Any`, primitive-array wrappers, and exact CLR vectors without admitting closed
generic instances.
Boxed-scalar casts are now complete for all eight selected Common primitives:
exact boxed identity, nullable unboxing for checked nullable casts, and
`isinst` plus nullable unboxing for safe casts, with no numeric-conversion or
value-class widening. Ordinary runtime type tests now have an explicit
exact-carrier admission boundary and an adversarial matrix across both FIR
frontends and runtime profiles. Exact scalars, classes/interfaces, strings,
supported primitive-array wrappers, imported CLR interfaces, nullable forms,
smart-cast use, and single evaluation are covered; classified exceptions,
`CharSequence`, and erased generic interfaces retain their dedicated paths.
Closed `GenericInstance` checks remain forbidden as Kotlin identity; ordinary
Kotlin-owned generic-class tests instead use the one producer-recorded erased
TypeDef and return the same object.

All eight signed Common primitive-array wrappers are now complete through one
runtime registry and the symmetric .NET stdlib declaration surface. The new
three families retain exact `SByte[]`, `Int16[]`, and `Single[]` private
storage, remain distinct from `Array<Byte>`, `Array<Short>`, and
`Array<Float>`, and cross portable Kotlin-library and explicit C# export
boundaries without copying. Unsigned arrays remain deliberately outside this
specialized-wrapper closure; `Array<*>` now sees the wrappers only as non-array
objects and never exposes their private vectors.

The separate generic-array closure now admits all eight concrete nullable
primitive element types as exact `Nullable<V>[]` vectors and admits
`Array<*>` through their classified `System.Array` base without changing the
specialized-wrapper identities above. It does not admit open nullable
elements, input projections, or value-vector covariance. Those parked shapes
still require successful typed-use carriers rather than inference from the
star read-only view.

The erased generic-class closure covers final/open/abstract/sealed,
nested/inner, data, inherited, nullable/scalar, bounded/multiple owner
parameters, generic members, default arguments, projected and erased-overload
shapes. It additionally covers widened direct and nested generic-bearing
inputs, multi-level portable overrides, same-object mutation after an
unchecked cast, delayed incompatible reads, one physical owner, and absence of
an implicit CLR `C<T>` surface.

Single-field Kotlin value classes now follow the same box-plus-contextual-
carrier architecture as the mature targets rather than becoming CLR value
types. Common's declaration and usage lowerings own constructor/member
semantics. One late .NET representation pass, ordered after loop and string
body rewrites, inserts every explicit box/unbox transition. Exact non-null
uses calculate with the recursively substituted underlying carrier; erased,
interface, nullable-collision, runtime-test, callable, generic-method, and
array/vararg generic positions use the one nominal non-generic box owner.
Generic value-class implementation helpers remain genuine CLR generic methods,
without creating a generic class owner. `T : Int` uses its sole primitive
carrier; `T : Int?` preserves a generic helper token while the erased owner
stores the boxed-or-null universal carrier.

Logical-signature mangling prevents underlying-carrier overload collisions,
generated floating equality retains Kotlin's total-order rule, and producer
ABI 26 records primary-constructor, box, and unbox MethodDef identities for
separate consumers. The selected upstream Common/JVM root matrix currently
executes 45 adversarial scenarios on both FIR parsers and both CLR profiles:
180 executions with zero failures, errors, or skips. Multi-field value classes,
unsigned stdlib/runtime publication, typed .NET export, and private
specialization remain separate consumers rather than being inferred from this
foundation. See [`docs/decisions/value-classes.md`](docs/decisions/value-classes.md).

## Open architectural blockers

- A true CLR-generic Kotlin-owned class owner with a complete erased Kotlin
  capability ABI and early failure of physically incompatible unchecked casts
  is explicitly on hold. Early failure may remove the incompatible-mutation
  storage contradiction, but the route still changes ABI, runtime identity,
  cast timing, reflection, inheritance, and dispatch. The current erased owner
  remains binding. This blocks only reintroducing or freezing CLR `C<T>` as the
  Kotlin implementation owner; it does not block current stdlib, reflection,
  CLI-IR, foreign-generics, generic-method, export-facade, or private
  optimization work. See
  [`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).
- Typed .NET export for Kotlin-owned generics remains a separate product
  programme. It may publish a facade, read-only interface, adapter, or
  same-object CLR subtype for export-created instances, but it must not
  reintroduce a second Kotlin runtime identity, competing state, or virtual
  ABI. Arbitrary existing instances require an adapter. The concrete export
  surface and identity policy remain open; the erased Kotlin runtime ABI does
  not.
- Private generic specialization remains on hold until core feature coverage,
  representative boxing/allocation/JIT/AOT measurements, and the concurrency
  and memory model can justify it. Scalar replacement and immutable/private
  shapes precede any escaped mutable typed-storage/deoptimization system.
- Open nullable projected arrays remain unsupported. This blocks the exact
  `Array<out T?>` signatures used by `setOfNotNull(vararg T?)` and the
  object-array `filterNotNullTo` variant; the singleton Set overload is
  published. This is a bounded compiler-representation closure, not an open
  product-design decision, and must not be approximated with `object[]`.
- KLIB-in-DLL and physical ABI codecs still need neutral serialization owners
  as those additional compiler/tooling consumers appear.
- Broad CLR property/member-state enhancement, `ref`/`out`, events, and
  collection-shaped params each require separate Kotlin-stability decisions.
- Foreign CLR generic methods and TypeDefs beyond their exact admitted grammars
  remain fail-closed. Unsigned carriers, special constraints, nullable constraint
  roots, constructed shapes outside the admitted interface grammar, and explicit
  nullable generic leaves on declared members require complete semantic, binding,
  override, and reflection mappings rather than backend exceptions or private
  reflection decoders.
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Continue the generated catalog by complete classifier families, not by
   handwritten members. The concrete Common scalar, built-in collection-
   interface, and Kotlin-owned implementation families are complete. Treat
   `Number` as a separate next candidate: first prove its classified `object`
   carrier can dispatch the complete Common conversion surface for every
   admitted boxed numeric scalar, rather than inferring it from concrete scalar
   entries. Then select later mapped/Stdlib families from Kotlin declaration
   scopes and foreign classifiers from exact importer identities and
   enhancement. Reuse the
   established callable/property objects
   and never expose a partial CLR MethodDef/Property scan. Constructors and
   declared-member convenience APIs remain separate selections.
2. Treat type-use annotation discovery as its own reflection tranche over the
   established logical type graph and exact foreign metadata owners.
3. Keep `Task`/`ValueTask` and C# `async` as a future explicit export product;
   they may adapt the Kotlin continuation boundary but never replace its
   internal ABI or create a second state-machine representation.
4. Keep coroutine scheduling, `kotlinx.coroutines`, sequence builders,
   debugger metadata, and coroutine-aware reflection outside this completed
   continuation/state-machine foundation until selected independently.

The post-rebase callable-reference probe found that common IR's new
`addBoundValueAtOverride` helper cannot directly replace the .NET lowering:
the shared helper discovers Kotlin-named `boundValueAt`, while the established
CLR runtime ABI deliberately exposes `BoundValueAt` as `protected final`.
Retain the local implementation unless the shared helper is separately
generalized to accept the exact override identity and member flags, with IL,
runtime-identity, and separate-library evidence proving no ABI change.

## Navigation

- Current sequencing and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Documentation and evidence index:
  [`docs/README.md`](docs/README.md)
- Collections programme:
  [`docs/programmes/common-collections.md`](docs/programmes/common-collections.md)
- Architecture ownership audit:
  [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md)
- Durable representation decisions: [`docs/decisions`](docs/decisions)

Update this file when branch state, the latest full gate, active work, blockers,
or the next bounded items change. Put rationale in ADRs, future ordering in the
way forward, chronological history in Git, and executable evidence in tests.
