# Common eager Iterable windowing closure — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for completing Kotlin/.NET's generated eager Iterable window/chunk classifier
family. Current rules remain in `AGENTS.md`, `STATUS.md`, and the Common
collections programme.

## Exact source closure

The owning stdlib generator now selects exactly these four Common templates for
the Iterable classifier:

- `Iterable<T>.windowed(size, step, partialWindows)`;
- transforming `Iterable<T>.windowed(size, step, partialWindows, transform)`;
- `Iterable<T>.chunked(size)`; and
- transforming `Iterable<T>.chunked(size, transform)`.

The first complete compile exposed one real missing prerequisite rather than a
backend defect: Common's indexed RandomAccess path calls the public
`List(size, init)` factory, which delegates through `MutableList(size, init)`.
The generator therefore projects both exact upstream declarations. It does not
replace them with a .NET factory or target-authored loop.

The two final generated outputs were byte-stable across an owning-generator
rerun:

```text
C6E5EE555A96C465CF36571064B80BEC46C0888649D227705ADBC9A23DA2930D Collections
1AE9D7997B1749B5A962F730C59C5909C12789FBE2E8484BA1E9D523C9C9AB81 CollectionFactories
```

## Semantic and physical boundaries

The exact Common bodies retain both source-selected routes. List plus
RandomAccess receivers use indexed traversal, ordinary snapshot windows, and a
reused moving-sublist view for transforms. Other Iterables call the already
selected Common `windowedIterator`, which uses RingBuffer for overlap and
partial windows. The eager methods add no `IEnumerable<T>`, LINQ, BCL window,
or second collection identity.

The KLIB owns all logical generic declarations. The physical product publishes
exactly two `windowed`, two `chunked`, one `List`, and one `MutableList`
MethodDef on `Kotlin.Collections.CollectionsKt`. Installed Kotlin consumers
emit external facade calls to the ordinary methods; both transform overloads
are separately executed. This publication is not a `DotNetName` or idiomatic
C# export decision.

CharSequence and array windowing, Sequence-valued eager operations, Random,
unsigned, reified, comparison/all-equality, BCL adapters, and typed C# export
remain independently selected closures.

## Adversarial evidence

The dedicated box test executes through PSI and LightTree on Framework CLR 4.8
and .NET 10. It covers RandomAccess snapshots, iterator/RingBuffer traversal,
overlap, gaps, partial windows, empty inputs, transform-view reuse, exact
iterator counts, callback exception identity and stopping point, and exact
invalid size/step messages.

The portable netstandard test produces one stdlib and separate consumer, then
executes it on both real runtime profiles. It also inspects the exact physical
MethodDef counts. An installed Kotlin library consumes all four overloads and
the generated IL is pinned to the external ordinary `windowed` and `chunked`
fallbacks.

Focused evidence passed before the aggregate:

```text
:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testIterableWindowing
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testIterableWindowing

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,111 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,242 tests
failures=0 errors=0 skipped=0
```

The FIR and integration XML roots were freshly written by the final candidate;
the unchanged `dotnet.ir` unit root was up-to-date from its preceding green
checkpoint. The four-test increase is exactly the new box in PSI/LightTree on
Framework CLR and CoreCLR.
