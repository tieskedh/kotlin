# Common selector min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated selector-order min/max collection family. Current
rules remain in `AGENTS.md`, `STATUS.md`, and the Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly 40 additional Common
declarations:

- `minBy`, `maxBy`, `minByOrNull`, and `maxByOrNull` over Iterable;
- the same four functions over generic object arrays; and
- all four functions over ByteArray, ShortArray, IntArray, LongArray,
  FloatArray, DoubleArray, CharArray, and BooleanArray.

Boolean is present because selector result `R : Comparable<R>`, not the
receiver element, supplies ordering. The corresponding Sequence declarations
were already published by the complete Sequence foundation. Map,
CharSequence, and unsigned receivers remain separately classified. No target
algorithm, Runtime surface, physical-name router, or ABI schema was added.

The generated .NET collections output was byte-stable across the final owning-
generator rerun:

```text
421E43BD3F42F377EA5AF2E615D9C135BAB532D26B9403977ECB658089E54A96 Collections
```

## Inline loop stack correction

The first hostile compile found a general CIL emitter defect. Common's
`minBy`/`maxBy` inline body returns the sole element without invoking the
selector; after inlining, that local return is represented by a break from a
synthetic do-while loop. If the inline call computed a later arithmetic/call
operand, an earlier operand was already pending on the CIL evaluation stack.
The break path incorrectly drained the whole stack, so its target joined an
empty branch stack with a one-value fall-through stack.

Loop registration now records its evaluation-stack depth as well as exception-
region depth. A same-region `break`/`continue` discards only values produced
after loop entry and preserves the earlier operand prefix. A cross-region
`leave` still requires an empty loop-entry stack because CLR `leave` discards
the stack. Normal statement loops enter at depth zero and retain their existing
shape. The original inline expression is the regression oracle; no Common body
or evaluation order was changed.

## Semantic and product evidence

The hostile box test proves zero selector calls for empty and singleton inputs,
throwing versus nullable empty behavior, one selector call per later visited
element, first identity for equal keys, callback exception identity/stopping,
generic object arrays, and all eight signed primitive classifiers. Float and
Double selector results pin generic Comparable NaN and signed-zero total
ordering on Framework CLR 4.8 and .NET 10.

Raw `Kotlin.Collections.CollectionsKt` metadata contains exactly ten methods
for each of the four logical names. Installed Kotlin calls all 40 declarations
and inlines every body. These functions are ordinary public inline fallbacks,
not `@InlineOnly`: Roslyn implements the existing erased `Kotlin.Function1`
interface and directly calls the IntArray `minBy` and `maxBy` fallbacks. This is
truthful current interop and does not claim implicit C# lambda conversion.

Focused evidence passed before the aggregate:

```text
:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testSelectorMinMaxAggregates
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testSelectorMinMaxAggregates

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate and explicit model-suite freshness rerun both
completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
./gradlew :dotnet:dotnet.ir:test --rerun -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,131 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,262 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
