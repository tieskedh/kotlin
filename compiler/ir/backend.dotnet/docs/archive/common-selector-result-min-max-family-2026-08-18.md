# Common selector-result min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated selector-result min/max collection family. Current
rules remain in `AGENTS.md`, `STATUS.md`, and the Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly 120 additional Common
declarations. `minOf`, `maxOf`, `minOfOrNull`, and `maxOfOrNull` each contribute
generic Comparable, Float, and Double selector-result forms over:

- Iterable;
- generic object arrays; and
- ByteArray, ShortArray, IntArray, LongArray, FloatArray, DoubleArray,
  CharArray, and BooleanArray.

Boolean is present because the selector result supplies ordering. The
corresponding Sequence declarations were already published. Comparator,
Map/CharSequence, Random, and unsigned families remain separately classified.
No target-authored algorithm, Runtime surface, public naming annotation, or
generic-owner ABI change was introduced.

The generated .NET collections output was byte-stable across the final owning-
generator rerun:

```text
9FE8BA48CEB95AEF50DE72F631CDC97F0432F8040F81D88B233DBDACBAFFF907 Collections
```

## Nullable generic-result recovery

The first hostile compile exposed a general result-representation gap. The
generic `R?` fallback is physically exposed through its reference-shaped
Comparable upper bound. For `R = Int`, a non-null result therefore arrives as
a boxed Int and an empty result arrives as null, while the frontend consumer
expects the exact `Nullable<Int>` value shape.

The existing implicit generic-substitution recovery handled only non-null
value results. Its nullable counterpart now emits `unbox.any Nullable<T>` when
FIR has inserted an `IMPLICIT_CAST` from a reference-shaped upper bound to a
supported concrete nullable scalar. CLR unboxing recovers both boxed `T` and
null exactly. The rule does not apply to explicit `as` or `as?`, does not infer
a generic argument from CLR metadata, and does not broaden arbitrary reference-
to-value conversions.

## Physical names and product boundary

CLR signatures cannot distinguish the generic, Float, and Double overloads by
return type. The bounded compiler-owned projection therefore uses twelve
physical names: the four generic logical names plus `...Float` and `...Double`
siblings. Raw `Kotlin.Collections.CollectionsKt` metadata contains exactly ten
MethodDefs under each name, one for every supported receiver.

Every source declaration is `@InlineOnly`. An installed Kotlin consumer calls
all 120 logical overloads and its emitted IL contains no call to any of the
twelve fallback names. The MethodDefs remain assembly-visible for inlining and
are deliberately inaccessible as direct C# API; a Roslyn negative consumer
pins that boundary. This is neither a public `DotNetName` contract nor a
partial migration of the erased physical Sequence/generic-owner model.

## Semantic and runtime evidence

The hostile box test proves throwing versus nullable empty behavior, zero
selector calls for empty inputs, one call for singleton inputs, first-result
identity for comparison-equal keys, callback exception identity/stopping,
generic object arrays, and all eight signed primitive receivers. Specialized
Float and Double results pin NaN propagation and signed-zero ordering. The
nullable value cases include both boxed Int and null recovery.

Focused box evidence executed on Framework CLR 4 and .NET 10. Separate
integration evidence verified exact raw metadata, an installed product
consumer, absence of inline-only fallback calls, and the C# visibility
boundary. The final full target aggregate and explicit model-suite freshness
rerun both completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
./gradlew :dotnet:dotnet.ir:test --rerun -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,135 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,266 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
