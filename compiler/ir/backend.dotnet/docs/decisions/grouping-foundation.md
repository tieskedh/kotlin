# ADR: Kotlin-owned Grouping foundation

- Status: **Accepted — pre-ABI**
- Date: 2026-08-14
- Scope: `Grouping<T, out K>`, its complete Common aggregation source, the
  Common `eachCount` expect with Native/Wasm actual, and all four generated
  factories over admitted carriers
- Does not itself enable: primitive-array Grouping factories, random
  operations, concurrency, BCL grouping identity, or typed C# export. The
  later Sequence foundation independently enables Common sequence builders.

## Decision

Kotlin/.NET compiles the authoritative Common `Grouping<T, out K>` interface
and the complete executable declarations in `Grouping.kt`: `aggregate`,
`aggregateTo`, both `fold`/`foldTo` families, `reduce`, `reduceTo`, and
`eachCountTo`. The Common `eachCount` expect uses the Native/Wasm actual that
delegates to `eachCountTo(mutableMapOf())`. No algorithm is copied into target
source or replaced with LINQ.

The four Common generator factories are admitted together:

- `Iterable<T>.groupingBy`;
- `Sequence<T>.groupingBy`;
- `Array<out T>.groupingBy`; and
- `CharSequence.groupingBy`.

The array template has no primitive-array variants. The generator must select
the exact Common family inventory rather than infer variants from available
primitive wrappers. CharSequence grouping also admits the exact Common
`CharSequence.iterator` dependency; it does not add another CharSequence
classifier or string wrapper.

## Logical and physical identity

`Grouping<T, out K>` follows the accepted erased generic-interface ABI. KLIB
retains both type parameters, key covariance, relative bounds in consumers,
and every inline body. One non-generic CLR interface
`Kotlin.Collections.Grouping` owns two erased capabilities:

- source iteration returning the canonical Kotlin `Iterator`; and
- key selection accepting and returning the erased object carrier.

Anonymous factory results are ordinary Kotlin-owned classes implementing that
one interface. They retain the original source and key-selector callback, so
iterator acquisition, repeated traversal, callback order, exceptions, and
captured state follow Common source. They do not implement `IGrouping<K,V>`,
`IEnumerable<T>`, or another BCL collection identity.

Top-level aggregation declarations use
`Kotlin.Collections.GroupingKt`. Factories retain their source-aligned
facades: `CollectionsKt`, `SequencesKt`, and `StringsKt`. The current overloads
remain distinguishable after erasure; no Grouping-specific physical rename or
general .NET interpretation of `@JvmName` is introduced.

## Semantic boundaries

The Common map algorithm is authoritative, including the distinction between
an absent key and a present key whose accumulator is null, first-element
flags, pre-seeded destinations, encounter order, mutation, overflow, and
exception timing. Erased map state and CLR boxing are implementation details;
they do not permit a BCL grouping or dictionary algorithm with different
behavior.

Inline casts such as `acc as R` retain Kotlin's existing open method-generic
cast path. This tranche adds no broader explicit-cast, safe-cast, or stronger
CLR runtime-check policy. The Sequence foundation's narrowly proven recovery
from a physical upper-bound view remains independent.

## C# boundary

Raw C# may implement the erased `Grouping` interface and call the public
`GroupingKt` facade. That is a truthful low-level ABI, not idiomatic typed
interop. A future export may adapt a Kotlin grouping to
`IGrouping<TKey,TElement>` or another typed surface, but it must preserve
Kotlin source traversal, callback timing, map semantics, and one underlying
state. Kotlin-owned grouping objects do not acquire an implicit BCL interface.

## Cross-profile invariant

The same portable `netstandard2.0` Stdlib DLL and hostile Grouping consumer
must execute on the installed Framework 4.8 CLR and independently on .NET 10.
A CoreCLR result cannot stand in for CLR 4 behavior involving `System.Object`,
boxing/unboxing, interface dispatch, generic method instantiation, or map
state. Direct PSI and LightTree products execute on both runtime profiles in
addition to portable consumption.

## Rejected alternatives

### Map Grouping to LINQ or `IGrouping<TKey,TElement>`

Rejected. LINQ grouping is an eager/enumerable result model, while Kotlin
`Grouping` is a source plus key selector consumed later by Common aggregation
algorithms. Mapping would change identity, traversal, callback timing, and map
behavior.

### Admit only Iterable grouping

Rejected. All four factory carriers are already truthful, and selecting the
easy factory would leave the public identity and anonymous implementation
boundary to be revisited for Sequence, arrays, and CharSequence.

### Use the JVM boxed-counter `eachCount` actual

Rejected. It depends on JVM-private `Ref.IntRef` and in-place map-value
rewriting for a Java boxing optimization. Native/Wasm supplies the exact
platform-neutral algorithm over already admitted Kotlin maps.

## Completion and freeze conditions

Completion requires PSI and LightTree execution on Framework CLR and CoreCLR;
the same portable producer and consumer on both runtimes; direct, fallback,
installed, and separately compiled stdlib consumption; deterministic KLIB/IL/
DLL output; and Roslyn metadata/call evidence. Tests cover all four factories,
empty/singleton/multiple and repeated traversal, nullable keys and
accumulators, first flags, both fold families, reduction, seeded destination
identity, counting, callback/iterator counts, exceptions, primitive/reference/
widened elements, and absence of BCL grouping/enumeration identity.

Before ABI freeze, correct the Grouping TypeDef, erased method slots, facade,
and source ownership atomically across stdlib production, physical ABI
records, fallback sources, installed consumers, and C# evidence. Do not add
aliases for prototype names or expose a second typed Grouping identity.
