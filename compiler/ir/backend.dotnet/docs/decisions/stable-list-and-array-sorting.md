# ADR: Stable list and object-array sorting

- Status: **Accepted pre-ABI**
- Date: 2026-08-12
- Scope: whole-list `MutableList.sort`/`sortWith`, whole generic object-array
  `sort`/`sortWith`, and their dependency-closed eager Iterable/MutableList
  ordering consumers
- Does not enable: primitive or unsigned-array sorting, range sorting,
  Sequence ordering, binary search, random/shuffle, or a BCL collection or
  comparer identity

## Decision

Kotlin/.NET compiles the exact Common `expect` declarations for
`MutableList<T>.sort` and `sortWith`, and the Common-generator `expect`
declarations for whole generic object-array `sort` and `sortWith`. Their
platform actuals reuse the shared Native/Wasm implementation and algorithm
lineage with fail-closed CLR carrier adaptations:

- `MutableList` sorting snapshots through the list iterator into an
  `Array<Any?>`, invokes the same object-array sort through the already erased
  physical Comparator slot, then narrows values only while writing them back
  through the same iterator; and
- the eager Common `Iterable.sorted`/`sortedWith` fast paths likewise retain
  their values in an `Array<Any?>` and narrow only the resulting read-only
  `List<T>` view, rather than casting a CLR `object[]` or `IComparable[]` to a
  more specific vector; and
- generic Kotlin arrays use the Native/Wasm stable merge sort, whose merge
  selects the left element when comparison is equal, but allocate each merge
  buffer from the input vector's runtime element type and traverse both input
  and buffer through the already classified `System.Array` read/write path.

The sources remain fail-closed projections of authoritative repository
sources or the same generator templates used by mature targets. The .NET
bootstrap generator selects families and target variants and verifies every
exact carrier substitution; it owns no sorting algorithm body.

This admits the dependency-closed Common generated consumers:

- `MutableList.sortDescending`, `sortBy`, and `sortByDescending`; and
- `Iterable.sorted`, `sortedDescending`, `sortedWith`, `sortedBy`, and
  `sortedByDescending`.

## Semantic contract

Sorting is stable. Natural order remains Kotlin `Comparable` order, including
the target's already established `String`, `Float`, and `Double` behavior.
Comparator sorting uses the ordinary Kotlin-owned `Comparator<in T>` identity
and slot. Lists and arrays of size zero or one do not invoke a comparator.

List sorting must work for arbitrary conforming `MutableList`
implementations; it may not assume `ArrayList`, contiguous storage, or indexed
writeback. The snapshot is completed before list mutation begins. A comparator
failure therefore leaves the original list untouched. A failure while the
sorted snapshot is written through a user list iterator has the ordinary
partial-mutation behavior of that iterator and is not rolled back.

Generic-array sorting is in-place. As on the selected Native/Wasm algorithm, a
comparator failure may leave an array partially rearranged; the exception
identity is preserved.

## CLR array constraint

Unlike Native/Wasm's uniform generic-array carrier and JVM's erased generic
array view, CLR vectors retain their runtime element type. Therefore neither
`object[] as Entry[]` nor a merge buffer allocated as `object[]` and cast to
`T[]` is physically valid. Those casts occur in the authoritative
Native/Wasm list snapshot and merge-buffer allocation and cannot be copied
verbatim.

The list and eager-result snapshots are private erased storage, just like the
accepted erased `ArrayList` storage: KLIB retains `T`, every stored value came
from the same logically typed source, and the ordinary Comparator, iterator,
and read-only List slots already perform their logical narrowing. The array
merge buffer instead has an observable vector carrier during array operations,
so it uses the input vector's runtime element type. The merge reads through
`System.Array.GetValue` and writes through `System.Array.SetValue`; the latter
retains the runtime vector's component-type check. This admits both a CLR
reference vector such as `Entry[]` and the target's exact value vector for
`Array<Int>` without a whole-vector `object[]` or open `T[]` cast. No
adaptation changes a public signature or introduces a second object identity.

## Cross-target alignment

JVM delegates list ordering to Java's stable collection sort. JS copies a list
to an array, uses the selected platform/fallback stable array ordering, and
writes it back. Native and Wasm share the Kotlin snapshot-plus-stable-array
implementation selected here. Kotlin/.NET follows that algorithm and
evaluation structure; the CLR's reified vector element identity requires only
the two private-carrier adaptations above.

The BCL's in-place `List<T>.Sort` and `Array.Sort` contracts do not guarantee a
stable result. They therefore cannot implement this Kotlin API directly.

## Physical and C# boundary

These are ordinary top-level Kotlin facade methods over the existing erased
Kotlin collection and classified array carriers. They introduce no
`System.Collections.Generic.List<T>`, `IList<T>`, or `IComparer<T>` identity.
C# can call these public facade methods directly with CLR reference/value
vectors and the current Kotlin collection/Comparator identities. That is a
usable low-level compiler ABI, not yet the ideal host surface: an idiomatic
typed `IComparer<T>`/collection view belongs to direct compatible foreign
actualization or the explicit C# export layer and cannot silently redefine
Kotlin sorting or ownership.

The tranche adds Stdlib declarations only. Runtime surface 36 and library ABI
codec 35 remain unchanged. Separate consumers resolve the logical functions
from the producer's embedded KLIB and the physical implementation from the
self-describing Stdlib product.

## Rejected alternatives

### Delegate directly to BCL sorting

Rejected because the relevant BCL sorts are not stable. Adding ordinal tie
indexes or wrappers would create a target-specific algorithm and extra
observable comparator/allocation behavior when a shared Kotlin implementation
already exists.

### Write a .NET-only merge sort

Rejected because it would fork an established repository algorithm without a
CLR constraint requiring different semantics.

### Cast an erased snapshot or merge buffer to `T[]`

Rejected. That is harmless on the selected Native/Wasm carrier but throws on
CLR for ordinary reference types such as `Entry`. Unchecked Kotlin source
casts do not make incompatible CLR vector identities interchangeable.

### Admit every array, range, and Sequence overload together

Rejected because primitive/unsigned carriers, range validation, and lazy
Sequence products are independent dependency closures. Unsupported families
must remain absent rather than receive approximate bodies.

## Completion evidence

The gate must cover both FIR parsers and both CLR profiles, compatible
unchanged upstream tests, and hostile target cases for:

- stable equal-key ordering, natural and reverse ordering, nullable/selector
  comparators, signed zero and NaN, empty/singleton inputs, and comparator call
  order;
- arbitrary mutable-list implementations and iterator-based writeback;
- comparator exception identity and the no-list-mutation-before-success rule;
- in-place object-array mutation and eager snapshot independence;
- separately compiled producer/consumer calls; and
- facade/slot metadata plus Roslyn calls which prove the absence of an
  implicit BCL list or comparer identity.
