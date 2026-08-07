# Pending upstream integration review — 2026-08-07

## Scope and accounting

This snapshot reviews the exact pending range:

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

This file records review and integration evidence. The branch has not yet been
rebased onto this range, and no conclusion below claims post-rebase test
success.

## Mechanical integration facts

- The branch changes 941 paths and upstream changes 2,665 paths from the
  common base.
- No upstream commit directly changes a Kotlin/.NET-owned source path.
- Only three paths are changed by both sides.
- `git merge-tree --write-tree HEAD origin/master` completed without a
  textual conflict and produced tree
  `1501546406e1652da4e20dca07e0e2370656af87`.

| Shared path | Upstream change | Required treatment |
| --- | --- | --- |
| `compiler/build-tools/kotlin-build-tools-api/api/kotlin-build-tools-api.api` | Adds the JVM `EXPAND_TYPE_ALIASES` operation option | Retain the independent `MetadataTargetPlatform.DOTNET` entry and regenerate the baseline through its owner |
| `compiler/fir/fir2ir/src/org/jetbrains/kotlin/fir/backend/Fir2IrVisitor.kt` | Reworks receiver/intersection casts and precise `Nothing?` smart casts | Retain the .NET prefer-actual exhaustive-when lookup beside the upstream cast calls |
| `compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/CommonLoweringPhases.kt` | Renames/reorders shared validation phases | Retain `includeLateinitLowering` while adopting the new phase identities and order |

The virtual-merge content was inspected, not merely its exit code. It contains
both sides of all three edits and preserves their independent semantics.

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

Java Direct continues separating objective source facts from FIR policy,
removing IDE/VFS dependence, eliminating duplicate supertype resolution, and
using lazy annotation lists to break cycles. It is architectural precedent for
the existing CLR loader/FIR boundary, not reusable Java importer code.

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

## Rebase checklist

1. Create a safety ref and rebase only onto the exact reviewed head
   `0e8c5f3f53`; do not silently chase a newer `origin/master`.
2. Inspect the three shared paths against the verified virtual-merge content.
3. Regenerate the BTA API baseline and FIR2IR test runners through their
   owning tasks; never hand-edit generated output.
4. Compile with the new bootstrap and inspect all generated churn.
5. Run focused checks for receiver/`Nothing?` casts, exhaustive-when
   expect/actual selection, all KLIB inliner modes, callable references with
   multiple type parameters, second-stage scope/offset/field validation,
   canonical embedded-KLIB loading, default/export separation, and both
   frontends/profiles.
6. Run and XML-audit the strict `dotNetTest --rerun -q` aggregate before any
   semantic cleanup.
7. Keep the pure rebase separate from optional shared-test adoption, task
   partitioning, or other follow-up changes.
