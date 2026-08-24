# Follow-up upstream integration record — 2026-08-24

## Exact scope and preservation

The branch moved from the reviewed upstream base
`f444263529ee3aaa7b657364979a5669030fbfa4` to
`f9a1706ce08c497554ee47fde7c9e7e89508152c`. The five-commit range contains:

- two platform-library tests for removed companion-object linkage;
- one external-Android-target Gradle integration-test addition;
- one shared JDK 8 test policy change selecting a larger heap and Parallel
  GC; and
- one performance-report normalization which removes the selected GC name.

The pre-rebase .NET head was
`e81799a81b9a7e21cfbe9adc3a0da0a9ecf72e0b` and remains reachable through
`codex/pre-rebase-dotnet-20260824-followup`. A virtual merge completed without
conflicts and produced tree `87c3d3cb4d9b9d93b87455f11af75be67eea5f7c`.
The real rebase then replayed all 610 target commits without a conflict or a
dropped commit and produced head
`479121eafb00b2260f8f2cd8ad5a0104172c02a5`.

A complete range-diff between the old and new target ranges contains 610
entries:

- 608 patches are identical;
- 2 patches are context-adjusted;
- no old target patch is missing; and
- no new target patch was introduced by the replay.

The two context-adjusted patches are the existing strict-test aggregation and
split test-product changes. Both touch the integration-test Gradle file around
the new upstream JDK 8 test policy; their .NET behavior remains present. The
pure rebased history was pushed with force-with-lease before the target-owned
adaptation below, keeping replay and semantic adaptation independently
reviewable.

## Shared-contract and reverse-dependency audit

Only two paths are touched by both the five upstream commits and the .NET
history:

- `compiler/tests-integration/build.gradle.kts`; and
- `compiler/tests-integration/testFixtures/org/jetbrains/kotlin/cli/AbstractCliTest.java`.

The platform-library changes add test data only and have no .NET owner. The
external Android target helper does not enter the .NET test graph. The .NET
CLI suite inherits `AbstractCliTest`, but its performance assertion checks the
phase and platform records, not the newly normalized GC name; the .NET CLI
corpus also has no `.perf.log` golden file. The upstream reporting change
therefore needs no target adaptation.

The target-owned `dn` integration task does use JDK 8, but it is separate from
the shared test task changed upstream. Leaving it unchanged would silently
diverge from the new repository policy. The task now selects
`testMaxHeapSizeLarge` and `GarbageCollector.Parallel`, matching the shared
JDK 8 task without changing its suite selection or target ownership.

## Verification

The target-owned integration task was rebuilt explicitly after the rebase:

```text
.\gradlew.bat :compiler:tests-integration:dn --rerun -q
```

Its direct JUnit XML audit records two suites and 127 tests with zero failures,
errors, or skips. The FIR product and both smaller roots were likewise rebuilt
explicitly, after which the public aggregate task completed successfully:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -q
.\gradlew.bat :compiler:backend.dotnet:test :dotnet:dotnet.ir:test --rerun -q
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit records 191 freshly written suites and 2,333 tests:

- 1 `dotnet.ir` suite with 6 tests;
- 187 FIR2IR suites with 2,199 tests;
- 2 integration suites with 127 tests; and
- 1 backend resolver suite with 1 test.

All roots report zero failures, errors, or skips. This covers both FIR
frontends, Framework 4.8 and .NET 10, Runtime/Stdlib products, C# interop,
separate compilation, the surface-58 CLR-generic-interface rehearsal, and its
production-erased inverse.

## Consequence for current work

This follow-up changes no accepted generic-owner representation, ordering, or
production-switch decision. Surface 58 remains the last completed CLR-generic
interface family. The next feature is still selected by recomputing the
smallest complete Common dependency family from that head; no declaration is
selected merely because the upstream boundary moved.
