# Common Map min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated Map min/max adapter family. Current rules remain in
`AGENTS.md`, `STATUS.md`, and the Common collections programme.

## Exact source closure

The owning stdlib generator projects exactly 24 Common declarations into the
source-aligned `_DotNetBootstrapMaps.kt`/`Kotlin.Collections.MapsKt` product:

- `minBy`, `maxBy`, and their nullable forms;
- generic Comparable, Float, and Double `minOf`/`maxOf` throwing and nullable
  forms;
- `minWith`, `maxWith`, and their nullable forms; and
- comparator-result `minOfWith`/`maxOfWith` throwing and nullable forms.

Map has no natural entry `min`/`max` family. Every selected declaration is the
authoritative Common `@InlineOnly` adapter and delegates to the map's `entries`
view; no target algorithm, eager copy, or BCL map operation was introduced.
The generated Maps output was byte-stable across the final owning-generator
rerun:

```text
FE0417C410404B28E6CE53F7027CCCC25966098FE0DE95D1B65CCF25042C5EF2 Maps
```

## Semantics and physical surface

The hostile Kotlin oracle proves all 24 forms, empty throwing versus nullable
behavior, selector elision for empty and singleton element selection, exactly
one singleton selector call for result selection, first-tie/result identity,
nullable comparator results, callback failure identity and stopping, and
Float/Double NaN and signed-zero ordering. Both FIR parsers execute the same
source on Framework CLR 4 and .NET 10.

Generic, Float, and Double selector-result overloads differ only by return type
after their selector parameter is erased. The bounded stdlib router therefore
adds only the exact `MapsKt`/`kotlin.collections`/`Map` admission alongside the
existing CollectionsKt and StringsKt admissions. This is not a public
`DotNetName` annotation.

Raw metadata contains all 24 MethodDefs and every one remains assembly-visible
because the Common declarations are `@InlineOnly`. An installed separate
Kotlin consumer inlines all Map adapters. The four comparator-element adapters
then make their source-prescribed call to the corresponding public
`CollectionsKt` entries fallback; integration evidence accounts for that
transitive eleventh call in addition to the ten direct supported receivers.
Roslyn is explicitly rejected from directly calling the assembly-visible Map
`minOf` helper. No public C# API was invented for an inline-only Kotlin adapter.

## Generic-owner boundary

KLIB retains the logical `Map<K, V>` receiver and generic method types, but the
production CLR Map owner remains the accepted erased TypeDef. This tranche has
no owner state or fields and does not advance or forbid the true CLR-generic
owner migration. Recompiling the same Common declarations against a future
atomic `Map<K, V>` owner remains part of that cutover.

This checkpoint ends the selected erased-owner stdlib breadth interlude. The
next major work is the complete Kotlin-emitter and exact inverse-rollback
rehearsal required by the generic-owner atomic checkpoint, not another leaf
stdlib family or a per-owner Sequence/Map switch.

## Final verification

The final aggregate command succeeded:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

The explicit model-suite freshness command also succeeded:

```text
.\gradlew.bat :dotnet:dotnet.ir:test --rerun -q
```

The three freshly written roots contain 190 XML suites and 2,278 tests with
zero failures, errors, or skips:

- `dotnet/dotnet.ir/build/test-results/test`: 1 XML / 6 tests;
- `compiler/fir/fir2ir/build/test-results/dotNetTest`: 187 XML / 2,147 tests;
- `compiler/tests-integration/build/test-results/dn`: 2 XML / 125 tests.
