# Upstream integration record — 2026-08-07

## Scope and accounting

This snapshot reviews and records integration of the exact range:

```text
76ca9aa1af7247c4f091f2f0d10c6f25b6fa80b9..0e8c5f3f53f0ed2af01c6165d5a5ec7d8f58ba54
```

The range contains 195 commits. Every subject and changed-path set was
accounted for, and every patch touching shared compiler, FIR, IR, KLIB,
reflection, export, Build Tools API, Gradle, test infrastructure, stdlib, or
tooling concerns relevant to Kotlin/.NET was inspected. The exhaustive working
ledger contained all 195 short hashes exactly once with no omissions; it was
then normalized into the current owners listed below. Git and the exact range
above remain the reproducible per-commit ledger.

The branch was subsequently rebased onto the reviewed head without semantic
cleanup. Verification state is recorded explicitly below; review evidence is
not presented as test evidence.

## Mechanical integration facts

- The branch changes 941 paths and upstream changes 2,665 paths from the
  common base.
- No upstream commit directly changes a Kotlin/.NET-owned source path.
- Only three paths are changed by both sides.
- Before the documentation normalization commit,
  `git merge-tree --write-tree HEAD origin/master` completed without a textual
  conflict and produced tree
  `1501546406e1652da4e20dca07e0e2370656af87`.

| Shared path | Upstream change | Required treatment |
| --- | --- | --- |
| `compiler/build-tools/kotlin-build-tools-api/api/kotlin-build-tools-api.api` | Adds the JVM `EXPAND_TYPE_ALIASES` operation option | Retain the independent `MetadataTargetPlatform.DOTNET` entry and regenerate the baseline through its owner |
| `compiler/fir/fir2ir/src/org/jetbrains/kotlin/fir/backend/Fir2IrVisitor.kt` | Reworks receiver/intersection casts and precise `Nothing?` smart casts | Retain the .NET prefer-actual exhaustive-when lookup beside the upstream cast calls |
| `compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/CommonLoweringPhases.kt` | Renames/reorders shared validation phases | Retain `includeLateinitLowering` while adopting the new phase identities and order |

The virtual-merge content was inspected, not merely its exit code. It contains
both sides of all three edits and preserves their independent semantics.

The pure rebase then established `0e8c5f3f53` as the exact merge base and
replayed all 409 target commits. Range-diff reported 408 patch-identical
commits and one context-only change: the ordinary-inline commit now applies
the same `includeLateinitLowering` addition around upstream's renamed
`createIrValidationAfterInliningPrivateFunctionsKlibPhase` factory. The
rebased tree differs from the virtual-merge tree only by the already reviewed
nine-file documentation normalization patch; that patch is byte-for-byte
identical before and after the rebase. The safety ref
`refs/backup/dotnet-before-upstream-rebase-20260807` preserves the old tip.

## Architecture and reverse-dependency second pass

The initial commit/path review found the upstream `Klib.canonicalPath` change
but did not trace it to the branch-added packed-KLIB implementation. The review
was therefore repeated at the contract and owner level rather than treating a
clean virtual merge as complete impact evidence. The second pass inspected the
source owners behind every retained compiler/import/reflection/export/inliner
theme and searched the 628 branch-changed Kotlin/Java production and test
sources for the relevant changed shared contracts.

The concrete outcomes are:

- `PackedKlib` was the one target-owned `Klib` implementation missing the new
  contract; it now keeps the physical DLL as both path and resolved canonical
  identity. The changed `KlibLibraryProvider` callback has no target-owned
  implementation.
- The renamed/strengthened IR validation phases are target composition points;
  `.NET` now uses the same second-stage phase identities and checker sets as JS,
  Wasm, Native, and the shared inliner. No checker is suppressed.
- Analysis API's diagnostics query and FIR's postponed-atom/type-variable
  changes have no target-owned implementation. The target continues consuming
  shared FIR inference, and a future IDE component must consume the shared
  diagnostics query rather than invent a `.NET` endpoint.
- JVM descriptor-less callable reconstruction remains in
  `core:reflection.jvm`, outside the JVM backend. This confirms that future
  runtime KLIB decoding, member enumeration, and reflective lookup need a
  reflection/runtime owner outside `backend.dotnet`; current compile-time
  callable-reference graph lowerings remain backend work.
- Swift Export separates Analysis-API/SIR admission and model construction from
  Native backend bridge binding. The current emitter-local C# export model is
  therefore recorded as provisional debt: reusable selector/admission/model
  work moves to an export/interop owner before generic, member, or inheritance
  export expands; physical CIL emission remains in the backend.
- Java Direct continues to place its source model in a dedicated importer
  module and its Kotlin projection in FIR/JVM. It is dependency-role precedent
  for the existing CLR-loader/FIR split, not proof that Java's model or its
  FIR-session resolution mechanics should be copied.

No accepted `.NET` deviation was added by this pass. The two placement guards
above tighten future architecture; they do not alter current Kotlin ABI.

## Lasting directions

### Cross-module inline and KLIB validation

Upstream now builds stdlib and `kotlin-test` with cross-module IR inlining as
the normal mode and removes JS runners whose only purpose was forcing that mode
on. Kotlin/.NET keeps its disabled/intra-module/full matrix because those
compiler options and physical fallback paths are currently supported, but
ordinary Common semantic tests should use the production default instead of
duplicating the full corpus.

The second KLIB stage now applies offset, field-visibility, unbound-symbol, and
type-parameter-scope checks more consistently. This is immediately relevant to
the selected non-linking inliner, erased generics, and callable signature
graphs. A failure after integration must be repaired at the producing or
deserializing boundary; disabling a shared checker is not an acceptable target
compatibility policy.

Owners:

- [inline programme](../programmes/inline-functions.md);
- [callable type-parameter ADR](../decisions/callable-type-parameters.md); and
- [way forward](../programmes/way-forward.md).

### Reflection and callable ownership

JVM reflection continues removing descriptors and reconstructing Kotlin
functions from metadata, while adding Java-only builtin members through a
separate path. That aligns with the selected .NET model: embedded KLIB owns
Kotlin declarations, exact foreign CLR rows own foreign declarations, and
member functions and properties are not flattened into one physical-reflection
enumeration.

New shared KLIB/IR tests cover member extension properties with multiple
receiver type parameters and nested properties with multiple context type
parameters. The former is direct evidence for the current callable graph; the
latter belongs to the next `KParameter` tranche after context parameters are
admitted.

Owners:

- [callable type-parameter ADR](../decisions/callable-type-parameters.md);
- [compiler architecture programme](../programmes/compiler-architecture.md);
  and
- [way forward](../programmes/way-forward.md).

### Foreign-language export remains a projection

Swift Export now:

- selects reverse-bridge override slots by complete function signature;
- projects throwing/error conventions without replacing Kotlin throwable
  identity;
- applies unsupported generic-input checks to functions, properties, setters,
  and constructors; and
- admits host vararg/closure and annotation shapes only through explicit
  supported export paths.

JS likewise admits annotation and nested-interface export shapes only when
checker and exporter support agree. These changes strengthen the existing C#
direction rather than selecting a new representation: export admission is
declaration-family complete and fail-closed, reverse bridges use complete
producer-recorded identities, and host exception/default/generic conveniences
never redefine Kotlin runtime ABI.

Owner:
[explicit C# export draft](../decisions/draft-adr-explicit-csharp-export-surface.md).

### Build Tools API and incremental state

The JVM snapshotter can now opt into expanded typealias tracking, and BTA
incremental buffering preserves structured diagnostic IDs. A future .NET
incremental protocol needs the analogous facts but not the JVM operation:
embedded-KLIB typealias expansions, selected DLL/KLIB identity, physical ABI
bindings, friend authorization, and inline bodies are one target-owned
invalidation input. BTA/daemon transport must preserve diagnostic identity.

Owner:
[compiler and Gradle integration ADR](../decisions/compiler-and-gradle-integration.md).

### Test topology

Wasm split one growing test task into disjoint JUnit-tagged tasks while keeping
a complete local task and changing CI wiring in the same rollout. The .NET
aggregate already has separate FIR/IL/box and CLI/library-integration tasks.
Further splitting of the short-path `dn` task is on hold until it has an
independent consumer, measurable cache or scheduling value, disjoint exhaustive
groups, repository lifecycle coverage, and a cross-process answer for the
Framework ILAsm/CLR4 resource.

This is not evidence that more tasks or unbounded parallelism make the complete
local gate faster.

Owners:

- [way forward](../programmes/way-forward.md); and
- [agent verification contract](../../AGENTS.md).

### Importer, tooling, and parked coroutines

Java Direct keeps its source model/finder in `compiler:java-direct` and its
Kotlin conversion in FIR/JVM while removing IDE/VFS dependence, eliminating
duplicate supertype resolution, and using lazy annotation lists to break
cycles. Its finder still participates in FIR-session resolution, so the useful
precedent is the ownership split—not a claim that Java Direct is a pure
target-neutral evidence layer or reusable CLR importer code.

Analysis API converges on one diagnostics query and PSI/decompiler stubs retain
more initializer, body, and default-expression facts. A future .NET IDE
component should use those shared surfaces while reconstructing Kotlin binary
declarations from embedded KLIB plus the shared physical ABI model; PSI/IL
inference is not compiler authority.

Wasm coroutine refactoring keeps canonical Kotlin symbols stable while
choosing stack-switching/state-machine implementation in backend-owned
resolvers and lowerings. This supports the existing decision to keep
coroutines and `Task`/`ValueTask` export as one later coherent programme;
it does not unpark them.

Owners:

- [compiler architecture programme](../programmes/compiler-architecture.md);
- [CLR importer draft](../decisions/draft-adr-clr-importer-boundary.md); and
- [way forward](../programmes/way-forward.md).

## Integration-sensitive repository changes

- The bootstrap compiler advances to `2.5.0-dev-3513`.
- The Gradle daemon receives a repository-wide 4 GiB heap cap; this is
  independent of the 12 GiB integration-test worker.
- KLIB objects retain canonical paths, so embedded-DLL extraction/loading must
  not create duplicate logical libraries.
- KLIB ABI dump comparison is strict again.
- Test federation and the repository test-lifecycle dump change shared
  selection/accounting infrastructure.

These are rebase and verification inputs, not new Kotlin/.NET ABI decisions.

## Screened directions with no current target action

The range also contains Kotlin/Native runtime-module/build cleanup; ObjC-only
tests; JVM descriptor-loader, Java-source, JDK 25, KAPT, Lombok, Parcelize,
Compose, PowerAssert, serialization-plugin, scripting, Android/AGP, NPM,
Playwright, JS AST/parser/optimizer/polyfill, Wasm runtime-specific, Native
cache/framework, Analysis API Java-module, repository dump, and dependency
metadata changes. They were screened by subject and paths. None changes an
accepted .NET exception, generic, array, enum, annotation, initialization,
runtime/stdlib, self-describing-DLL, or target-profile decision.

## Integration and verification outcome

Completed mechanical checks:

1. The safety ref was created and the branch was rebased only onto the exact
   reviewed head `0e8c5f3f53`.
2. All three shared paths were inspected against the verified virtual-merge
   content and retain both owners' semantics.
3. The 409-commit range-diff and complete tree comparison found no dropped or
   additional target patch.
4. The pure rebase was force-pushed separately from this documentation update.

The first owner build exposed three indirect source adaptations which the
path-overlap audit could not reveal:

- the shared packed-KLIB adapter now resolves and retains the physical DLL's
  `Klib.canonicalPath`, as required by upstream's extended `Klib` contract;
- three foreign-CLR FIR builders and one FIR2IR test helper state their
  inferred result types explicitly under the stricter compiler bootstrap; and
- the .NET inline pipeline uses upstream's renamed private/all-functions and
  before-lowerings KLIB second-stage validators. It therefore admits the new
  type-parameter-scope, field-visibility, offset, and unbound-symbol checks
  instead of bypassing them.

The missed packed-KLIB implementation establishes a review-process correction:
an upstream interface, abstract-base, sealed-hierarchy, constructor, or factory
contract change requires a repository-wide reverse-dependency audit of
target-owned implementors and consumers. Commit/path accounting and a clean
virtual merge cannot establish that property on their own.

The BTA `apiDump` owner and FIR2IR `generateTests` owner then completed without
tracked generated churn. Focused verification covered eight packed-KLIB loader
unit tests, twelve callable-type-parameter/exhaustive-when FIR, IL, Framework,
and CoreCLR tests, and six embedded-library/inliner integration tests; all
reported zero failures, errors, or skips. The strict XML-audited
`dotNetTest --rerun -q` aggregate then completed at 2026-08-07 12:42 local:
50 FIR/IL/box XML suites reported 1,174 tests and two CLI/library-integration
suites reported 113 tests, for 52 suites and 1,287 tests total with zero
failures, errors, or skips. No optional shared-test adoption or task
restructuring is part of the mechanical integration.
