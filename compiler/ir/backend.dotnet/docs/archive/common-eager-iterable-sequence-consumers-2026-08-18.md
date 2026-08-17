# Common eager Iterable Sequence consumers — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated eager Iterable family released by the Kotlin-owned
Sequence foundation. Current rules remain in `AGENTS.md`, `STATUS.md`, and the
Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly seven additional Common
declarations for the Iterable classifier:

- `flatMap`, `flatMapIndexed`, `flatMapTo`, and `flatMapIndexedTo` whose
  transform returns `Sequence<R>`;
- `Iterable<T>.minus(Sequence<T>)`; and
- `Iterable<T>.plus(Sequence<T>)` plus
  `Collection<T>.plus(Sequence<T>)`.

Their helper graph was already complete: the Kotlin-owned Sequence and iterator
capability, `MutableCollection.addAll(Sequence)`, Sequence-to-list, filtering,
snapshots, collection sizing, and index-overflow checks. No target algorithm,
LINQ operation, `IEnumerable<T>`, Runtime surface, or new bridge protocol was
added.

The generated collections output was byte-stable across the final owning-
generator rerun:

```text
43C96F79D4CFD9B99083011EA69FCDFC35A7F1BF0A02D86F8DB92CAAA3092B11 Collections
```

## Physical overload names

The four new flatMap declarations collide with their existing Iterable-result
siblings after both lambda types become the same physical Function carrier.
The projection preserves all existing method names and examines the exact IR
selector-result classifier only on `Kotlin.Collections.CollectionsKt`. A
Sequence result receives one of:

```text
flatMapSequence
flatMapIndexedSequence
flatMapIndexedSequenceTo
flatMapSequenceTo
```

An Iterable result returns to the unchanged ordinary name. Any other result
fails closed. The allocation is therefore independent of declaration order and
cannot rename old consumers when a new overload is appended. It mirrors the
authoritative logical distinction and source-aligned suffixes, but is neither a
general interpretation of `@JvmName` nor a public `DotNetName` annotation.

Installed Kotlin libraries inline all four Common bodies from KLIB; their IL
contains no external call to these fallback names. The three non-inline
operators remain public physical calls. Raw metadata and the physical
declaration catalog pin every new name and the increased plus/minus overload
counts.

## Sequence owner boundary

KLIB retains `Sequence<T>`, declaration-site covariance, and every generic
signature. The current canonical physical owner remains one erased non-generic
Kotlin interface. That representation is not selected merely because an older
compiler could not emit generics: it currently preserves one Kotlin identity
and widened value-type views such as `Sequence<Int> -> Sequence<Any?>`, which
CLR generic variance alone cannot express for value-type substitutions.

This feature adds no new erased assumption to logical IR or KLIB. It does not
authorize changing only the Sequence TypeDef to `Sequence<T>` while iterator
returns/state remain object-carried. A canonical physical migration must be
part of the atomic generic-owner/interface cutover and prove typed iterator and
state paths, primitive covariance bridges, casts, reflection, overrides, C#
implementation, and separate compilation together. An additive typed C#
adapter/export may be selected earlier without changing canonical identity.

## Adversarial and product evidence

The dedicated box test executes through PSI and LightTree on Framework CLR 4.8
and .NET 10. It proves lambda-return overload selection, exact output and
destination identity, eager transform/inner-Sequence ordering, callback
exception identity and stopping, one RHS traversal, `minus` materialization
before receiver traversal, empty snapshots, both plus receiver declarations,
and nullable/widened values.

The portable netstandard product inspects the exact MethodDefs, compiles an
installed Kotlin library which consumes all seven declarations, and executes
that library on both real runtime profiles. The existing Roslyn boundary
implements the erased Kotlin Sequence interface directly and calls Iterable
plus, Collection plus, and Iterable minus without a bridge or BCL identity.

Focused evidence passed before the aggregate:

```text
:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testIterableSequenceOperations
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testIterableSequenceOperations

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,115 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,246 tests
failures=0 errors=0 skipped=0
```

The FIR and integration roots were freshly written by the final candidate; the
unchanged `dotnet.ir` unit root remained up-to-date from its preceding green
checkpoint. The four-test increase is exactly the new box under PSI/LightTree
on Framework CLR and CoreCLR.
