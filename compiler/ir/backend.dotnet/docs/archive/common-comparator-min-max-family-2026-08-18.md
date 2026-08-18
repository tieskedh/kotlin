# Common comparator min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the completed generated comparator min/max collection family. Current rules
remain in `AGENTS.md`, `STATUS.md`, and the Common collections programme.

## Exact source closure

The owning stdlib generator extends both comparator aggregate groups from
Iterable to generic object arrays and all eight signed primitive-array
wrappers:

- `minWith`, `maxWith`, `minWithOrNull`, and `maxWithOrNull`; and
- `minOfWith`, `maxOfWith`, `minOfWithOrNull`, and `maxOfWithOrNull`.

This adds 72 declarations to the eight existing Iterable declarations and
completes 80 methods: eight source names over ten receivers. Boolean is valid
because the caller supplies the ordering. Map, CharSequence, and unsigned
variants remain separate. The selected bodies consume only the completed
Kotlin-owned Comparator, iterator, array, and inline foundations. No target
algorithm, Runtime surface, physical-name router, or generic-owner ABI change
was added.

The generated .NET collections output was byte-stable across the final owning-
generator rerun:

```text
B43658C78A58274ED13D776DC6EDE50E6CB6DF7C825113268D4F84F1756096D1 Collections
```

## Semantic evidence

The hostile box test proves throwing versus nullable empty behavior and exact
evaluation counts. Element selection performs no comparison for a singleton;
selector-result selection calls the selector once and performs no comparison.
Comparison-equal elements and selector results retain the first identity.
Comparator and selector failures retain identity and stop at their exact call
boundary.

The same oracle covers nullable selector results, a broad
`Comparator<Any?>` used through contravariance, generic object arrays, and all
eight primitive receivers. A caller-supplied natural Float comparator pins NaN
ordering and a Double comparator pins negative-zero selection. PSI and
LightTree execute the source on Framework CLR 4 and .NET 10.

## Metadata and C# boundary

Raw `Kotlin.Collections.CollectionsKt` metadata contains exactly ten MethodDefs
for each of the eight names. No result-only overload collision exists, so the
logical names are also the natural CLR names.

Installed Kotlin compiles and executes all 80 declarations. Its emitted IL
contains exactly ten calls to each ordinary `minWith`/`maxWith` fallback and no
calls to the four `@InlineOnly` `minOfWith`/`maxOfWith` fallbacks. A Roslyn
consumer implements the stable erased `Kotlin.Comparator` interface and
directly calls signed IntArray `minWith` and `maxWithOrNull`. A separate
negative consumer proves that the selector-result fallbacks remain assembly-
visible rather than public. This is truthful current C# interop without
claiming implicit lambda, delegate, or `IComparer<T>` conversion.

## Final verification

The final full target aggregate and explicit model-suite freshness rerun both
completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
./gradlew :dotnet:dotnet.ir:test --rerun -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,139 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,270 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
