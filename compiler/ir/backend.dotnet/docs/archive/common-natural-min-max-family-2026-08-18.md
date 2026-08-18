# Common natural min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated natural-order min/max family over every currently
supported classifier. Current rules remain in `AGENTS.md`, `STATUS.md`, and the
Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly 52 additional Common
declarations:

- `min`, `max`, `minOrNull`, and `maxOrNull` over Iterable for generic
  Comparable, Float, and Double elements (12);
- the same generic/Float/Double matrix over generic object arrays (12); and
- all four functions over each of ByteArray, ShortArray, IntArray, LongArray,
  FloatArray, DoubleArray, and CharArray (28).

The corresponding Sequence declarations were already published by the
complete Sequence foundation. The upstream generator defines no natural-order
BooleanArray template, and unsigned receivers remain outside the selected
value-class/range product. No target algorithm, Runtime surface, or bridge
protocol was added.

The generated .NET collections output was byte-stable across the final owning-
generator rerun:

```text
A17BCD007BE004F4B780E1FB5ADBDB52A43AFE857F13227EF65D2D68D7516BC3 Collections
```

## Physical naming boundary

KLIB retains the exact logical Kotlin declarations. CLR erasure gives Iterable
and object-array generic, Float, and Double siblings identical parameter lists,
so their return type alone cannot distinguish MethodDefs or C# calls. The
backend therefore derives a bounded physical name from the already proven
logical element type, sharing the Sequence mapping:

```text
min/max                 primitive-array methods
min/maxOrNull           primitive-array and generic nullable methods
min/maxOrThrow          generic throwing methods
...OfFloat              dedicated Float element methods
...OfDouble             dedicated Double element methods
```

Raw `Kotlin.Collections.CollectionsKt` metadata contains exactly 52 methods:
seven each named `min` and `max`; nine each named `minOrNull` and `maxOrNull`;
and two each for every generic/Float/Double throwing and dedicated nullable
physical name. This compiler-owned stdlib implementation mapping is not a
general `@JvmName`, public `DotNetName`, or authority for a partial physical
generic-owner migration.

## Semantic and product evidence

The hostile box test proves empty throwing and nullable behavior, one traversal
per call, and first-object identity for comparison-equal minima and maxima. It
covers generic object arrays and all seven signed primitive classifiers.
Dedicated Float and Double templates are tested for NaN propagation and the
Kotlin signed-zero total order in both primitive and boxed/object-array forms.

Installed Kotlin calls all 52 physical fallbacks. Roslyn directly calls the
IntArray `min` and `max` overloads. One portable netstandard Stdlib product and
consumer execute on Framework CLR 4.8 and .NET 10. Focused evidence passed
before the aggregate:

```text
:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testNaturalMinMaxAggregates
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testNaturalMinMaxAggregates

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate and explicit model-suite freshness rerun both
completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
./gradlew :dotnet:dotnet.ir:test --rerun -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,127 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,258 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
