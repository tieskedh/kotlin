# Common Sequence builder closure — 2026-08-15

This immutable checkpoint records the implementation and verification evidence
for completing Kotlin/.NET's Common Sequence builder/window dependency closure.
Current rules remain in `AGENTS.md`, `STATUS.md`, and the accepted Sequence ADR.

## Exact source closure

The owning generator projects all of Common `SequenceBuilder.kt` and
`SlidingWindow.kt`, the exact `Sequences.kt` `ifEmpty` and internal
`flatMapIndexed` declarations, and every generated Sequence member except the
two unsigned selector sums. Random-backed `shuffled` remains outside this
closure. No iterator, window, running-operation, or coroutine algorithm is
authored for .NET.

The generated/public closure includes:

- `sequence`, `iterator`, `SequenceScope`, `yield`, and all `yieldAll` routes;
- `ifEmpty` and both lazy `flatMapIndexed` overloads;
- running fold/reduce and their indexed aliases, plus scan aliases;
- `zipWithNext`, overlapping/gapped/partial/transformed `windowed`, and
  `chunked`; and
- generic resized `Array.copyOf`, generic `Array.fill`, and the exact Common
  `RingBuffer.toArray` dependency path.

The five relevant generated outputs were identical across the final owning-
generator rerun:

```text
418B40EFE01E0413372A7BB4FF9C88B0B4E1167D2B2555A7AB818E6042515AEB SequenceBuilder
F8DDB4F967185B6CF44EBD58EA0D7027E648F1A21C2D077A1E36559EEC855527 SequenceCore
AE30F055D3E95473D32307630A9613540B4198EFCE02F8863BF2A54D4C18F285 SlidingWindow
9BE8668116C7FA351A4AAA3549D719D5A77145D381D898F687788ACDCA671734 Sequences
3464DDB67EBEA9CBFF2DCC85E724B0A8E88B27B771AAE15A9ABD70AA3C466A90 Sorting dependencies
```

## Physical boundaries

`Sequence<T>` and `SequenceScope<T>` retain their accepted erased Kotlin owner
identities. Builders reuse the one Common continuation/sentinel and generated
state-machine representation. They acquire no `IEnumerable<T>`, LINQ,
delegate, `Task`, or second coroutine ABI.

Common `RingBuffer.toArray` conditionally selects a resized copy or the supplied
exact vector into one immutable local `Array<T?>`. Only that structural local
shape receives a `System.Array` carrier, and only a value with the original
logical `T` IR type may be written. Bare cast locals, nullable/widened writes,
fields, parameters, returns, and other invariant/input open-nullable arrays
remain rejected. The CLR vector retains identity and its component check.

Generic `Array.fill` evaluates receiver, value, and bounds in Kotlin order and
uses one Framework-compatible Runtime host operation over `System.Array`.
Runtime surface 37 owns that compiler/runtime contract. Negative/out-of-range
bounds map to `IndexOutOfBoundsException`; `fromIndex > toIndex` maps to
`IllegalArgumentException`.

The closure also exposed an ownership bug. A USER emitter classified private
top-level properties from a source-aligned Stdlib shard as user declarations,
so the six private Sequence state constants appeared in unrelated producer
DLLs. Stdlib source shards now own their complete top-level property closure,
matching their existing private/internal function ownership. A random enum
producer directly proves that neither the private facade nor its state fields
leak.

## Verification history

Focused evidence passed before the aggregate gate:

- PSI and LightTree Sequence box execution on Framework CLR 4.8 and .NET 10;
- generic fill value/reference/null and both range-failure categories;
- portable netstandard Stdlib production, then separate Kotlin consumption and
  execution on Framework CLR 4.8 and .NET 10;
- physical `SequenceScope` and facade-name metadata checks;
- unchanged rejection of bare/nullable open-array locals and three historical
  declaration-eviction goldens; and
- direct absence of private Stdlib state from an unrelated user producer.

The strict aggregate deliberately found two regressions before the final head:

1. After 1,898.3 seconds, one integration test found private Sequence state in
   an unrelated enum producer. Source-shard property ownership fixed it.
2. After 2,692.2 seconds, six PSI/LightTree golden failures showed that the
   first local-array carrier rule revived three previously rejected declaration
   families. The goldens were restored unchanged and the carrier was narrowed
   to the immutable conditional shape plus original-`T` writes.
3. The final unchanged candidate completed in 2,653.0 seconds with exit code 0.

Direct XML audit of the final three result roots:

```text
dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,085 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,216 tests
failures=0 errors=0 skipped=0
```

This checkpoint completes Sequence builders and windows. It does not select
Random/shuffle, unsigned selector sums, BCL enumeration identity, or idiomatic
typed C# builder export.
