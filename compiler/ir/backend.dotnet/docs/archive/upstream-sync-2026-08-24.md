# Upstream integration record — 2026-08-24

## Exact scope and preservation

The branch moved from the reviewed base
`d78e4a4c1465c00475b8019654b5905124dc30a6` to the pinned upstream commit
`f444263529ee3aaa7b657364979a5669030fbfa4`. That upstream range contains 461
commits. The pre-rebase .NET head was
`4420238923a2fccada4aadf255f65f4c17f1ea04`; it contains 603 target commits
above the old base and remains reachable through
`codex/pre-rebase-dotnet-20260823`.

The mechanical replay produced
`c6397a5326704cd991c83f2e41e99f3f05dd2e24`. A complete `git range-diff`
between the old and new target ranges contains 603 entries:

- 600 patches are identical;
- 3 patches are context-adjusted;
- no patch was dropped; and
- no patch was added.

The context-adjusted patches are the existing Common-and-actual bootstrap,
compiler test-domain, and split test-product changes. Their surrounding
shared owners changed upstream; their .NET intent remains present.

The pure rebase was pushed separately before any semantic adaptation. The
three bounded adaptations described below were then committed and pushed
normally, so the mechanical integration and its consequences remain
independently reviewable.

## Conflict and shared-contract audit

The rebase had one textual conflict, in `Fir2IrVisitor.kt`. Upstream now
resolves `throwNoWhenBranchMatchedException` through its standard callable ID,
while the .NET Common-and-actual bootstrap must prefer the actual declaration
over its expect peer. The resolution composes both facts: use the upstream ID,
prefer a non-expect symbol, and retain the established fallback.

The pre-rebase audit classified every upstream commit and every changed path.
It found 18 paths touched by both histories and identified the following
semantic hotspots even where Git merged cleanly:

- shared value-class implementation-source naming;
- non-linking inline deserialization and module ownership;
- Test Federation domain selection for FIR2IR tasks;
- fake overrides, retained additional supertypes, and the non-linear test
  pipeline;
- Kotlin equality in generated collection algorithms;
- callable-reference, default-argument, and enum lowerings;
- generated API/configuration owners and compiler-module registration; and
- Gradle 9.7 plus compiler performance-phase integration.

Focused inspection found no required .NET correction for the changed fake-
override, additional-supertype, non-linear-pipeline, equality, default-
argument, enum, module-registration, or performance contracts. Their existing
target coverage remains part of the strict aggregate. Three real adaptation
requirements did remain.

## Post-rebase adaptations

### Test Federation ownership

Commit `cd37010240` adds the explicit `DotNet` Test Federation domain and
assigns the .NET FIR2IR task to it. Generated domain sources and the domain
dump come from their owner. The end-to-end task output reports
`Current Domain: '[DotNet]'`; the target no longer inherits JVM ownership from
the shared FIR2IR helper.

### Value-class implementation sources

Commit `c48b6acdc4` removes the target's reconstruction of the former
value-class implementation names. The lowering now uses the exact source
implementation declarations supplied by Common and retargets a return only
when it targets that exact source. A separate-compilation regression covers
the changed path.

### KLIB inline dump compatibility

Commit `8a631dc9ef` completes the shared multi-file non-linking inline
deserializer contract for the KLIB dump tool. The tool now supplies the
complete module-deserializer inputs, while the deserializer exposes one
aggregate signature view over its file index. This preserves both the .NET
supporting-main-IR path and the upstream command-line dump consumer.

The Kotlin/Native KLIB tool compiles with the required native property. A
functional smoke test then loaded the built tool and inspected the real
JavaScript stdlib KLIB, reporting 2,998 inlinable functions. The temporary
runner remained outside the committed product.

## Verification

The strict target command completed successfully at the adapted head:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit records 191 suites and 2,321 tests:

- 1 `dotnet.ir` suite with 6 tests;
- 187 FIR2IR suites with 2,187 tests;
- 2 integration suites with 127 tests; and
- 1 backend resolver suite with 1 test.

All roots report zero failures, errors, or skips. The matrix includes PSI and
LightTree, Framework 4.8 and .NET 10, the Runtime/Stdlib products, Roslyn
interop, separate compilation, and the surface-55 CLR-generic-interface
rehearsal.

The focused native compilation also passes:

```text
.\gradlew.bat '-Pkotlin.native.enabled=true' :kotlin-native:klib:compileKotlin --stacktrace
```

## Remaining repository-wide environment gate

The repository lifecycle-model updater was run with Kotlin/Native enabled:

```text
.\gradlew.bat '-Pkotlin.native.enabled=true' :repo:codebase-tests:updateTestLifecycleTaskDump
```

It passed the corrected Kotlin compilation and progressed through about 1,697
tasks before reaching Native C/C++ compilation. The machine has the extracted
LLVM development package but no Visual Studio C++ Build Tools or Windows SDK,
so Clang could not find `cassert`, `stdlib.h`, or `time.h`. The official Visual
Studio 2022 Build Tools installer could not complete unattended because
Windows required an interactive administrative confirmation and returned
exit code 1602.

This is an external machine-toolchain gate, not a .NET target failure. Install
the Visual Studio 2022 **Desktop development with C++** workload, including
its recommended MSVC tools and Windows SDK, and rerun the updater command
above. No lifecycle dump was committed from the incomplete run.

Retain `codex/pre-rebase-dotnet-20260823` until that final machine-dependent
check has completed. The strict Kotlin/.NET gate and the adapted shared Kotlin
compilation paths are green independently.

## Consequence for current work

The integration changes no accepted generic-owner ordering or representation
decision. Surface 55 remains the last completed CLR-generic-interface family.
The next feature must still be selected by recomputing the smallest complete
Common dependency family from that head. `MutableList`, Map, defaults,
multiple owner parameters, and broader overload grammars are not selected by
name or convenience; the natural CLR route and typed state remain the default,
with semantic capability only where the complete family proves it necessary.
