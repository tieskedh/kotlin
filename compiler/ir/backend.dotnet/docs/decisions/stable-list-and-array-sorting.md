# ADR: List and signed-array sorting

- Status: **Accepted pre-ABI**
- Date: 2026-08-14
- Scope: whole-list `MutableList.sort`/`sortWith`; whole and range generic
  object-array sorting; whole and range sorting for the seven naturally
  ordered signed primitive-array wrappers; and the dependency-closed signed
  array/Iterable/MutableList ordering consumers
- Does not enable: unsigned-array sorting, Boolean natural sorting, Sequence
  ordering, binary search, random/shuffle, or a BCL collection or comparer
  identity

## Decision

Kotlin/.NET compiles the exact Common `expect` declarations for
`MutableList<T>.sort` and `sortWith`, and the Common-generator `expect`
declarations for whole and range generic object-array sorting plus whole and
range natural sorting of `ByteArray`, `ShortArray`, `IntArray`, `LongArray`,
`FloatArray`, `DoubleArray`, and `CharArray`. Their platform actuals reuse the
shared Native/Wasm implementation and algorithm lineage with fail-closed CLR
carrier adaptations:

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
  and buffer through the already classified `System.Array` read/write path;
  and
- the seven naturally ordered primitive wrappers execute the exact
  Native/Wasm per-wrapper partition and quicksort bodies over their existing
  private CLR vectors. `FloatArray` and `DoubleArray` retain explicit Kotlin
  `compareTo` partitioning, including NaN and signed-zero total order.

The sources remain fail-closed projections of authoritative repository
sources or the same generator templates used by mature targets. The .NET
bootstrap generator selects families and target variants and verifies every
exact carrier substitution; it owns no sorting algorithm body.

This admits the dependency-closed Common generated consumers:

- `MutableList.sortDescending`, `sortBy`, and `sortByDescending`; and
- `Iterable.sorted`, `sortedDescending`, `sortedWith`, `sortedBy`, and
  `sortedByDescending`; and
- applicable object/signed-primitive array `reverse`, range `reverse`,
  `reversed`, `reversedArray`, `sorted`, `sortedArray`, `sortDescending`,
  range `sortDescending`, `sortedDescending`, `sortedArrayDescending`,
  `sortedWith`, `sortedArrayWith`, selector ordering, and `isSorted*`
  variants. Boolean arrays participate only in operations whose authoritative
  template accepts an explicit comparator/selector or performs reversal; no
  natural Boolean sorting declaration is invented.

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

Every range operation first executes Common's
`AbstractList.checkRangeIndexes(fromIndex, toIndex, size)`. Invalid bounds
therefore fail before mutation. An empty or singleton valid range remains
unchanged. Object-array range sorting is stable inside the selected range and
does not touch values outside it. Primitive sorting uses the upstream
non-stable quicksort contract; returned `sorted*` snapshots are independent,
while in-place operations preserve the original wrapper and backing-vector
identity.

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

The Common `sortedArray*` snapshot path likewise calls the authoritative
`copyOf`. For a generic CLR vector whose element parameter may still be open
in the producer, the backend evaluates the source once, obtains its exact
runtime component type, allocates with `System.Array.CreateInstance`, and
copies with `System.Array.Copy` before narrowing only to the frontend-proven
array result. It never emits `newarr !T` or substitutes `object[]`. A primitive
array snapshot instead allocates the wrapper's fixed private vector and returns
one new wrapper over that vector. Empty `sortedArray*`/`reversedArray` paths
retain the Common same-instance fast path.

## Cross-target alignment

JVM delegates list ordering to Java's stable collection sort. JS copies a list
to an array, uses the selected platform/fallback stable array ordering, and
writes it back. Native and Wasm share the Kotlin snapshot-plus-stable-array
implementation selected here. Kotlin/.NET follows that algorithm and
evaluation structure; the CLR's reified vector element identity requires only
the two private-carrier adaptations above.

The BCL's in-place `List<T>.Sort` and `Array.Sort` contracts do not guarantee a
stable result. They therefore cannot implement the object-array/list Kotlin
API directly. Primitive arrays still retain the Native/Wasm quicksort lineage
instead of silently selecting a different host algorithm, comparison edge
case, or range-failure order.

## Physical and C# boundary

These are ordinary top-level Kotlin facade methods over the existing erased
Kotlin collection and classified array carriers. They introduce no
`System.Collections.Generic.List<T>`, `IList<T>`, or `IComparer<T>` identity.
C# can call these public facade methods directly with CLR reference/value
vectors for generic arrays, the exact Kotlin primitive-array wrappers for
specialized arrays, and the current Kotlin collection/Comparator identities.
Wrapper construction retains the supplied primitive vector by identity, so a
natural or range sort mutates the same C# storage and a `sortedArray*` call
returns independent wrapper/vector storage. That is a usable low-level compiler
ABI, not yet the ideal host surface: an idiomatic typed `IComparer<T>`/
collection view belongs to direct compatible foreign actualization or the
explicit C# export layer and cannot silently redefine Kotlin sorting or
ownership.

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

### Admit every array and Sequence overload together

Rejected because unsigned carriers and lazy Sequence products are independent
dependency closures. Signed primitive and object-array range operations are
now admitted only after their complete Common/Native generator graph and
existing exact wrapper carriers became available. Unsupported families remain
absent rather than receive approximate bodies.

## Completion evidence

The gate must cover both FIR parsers and both CLR profiles, compatible
unchanged upstream tests, and hostile target cases for:

- stable equal-key ordering, natural and reverse ordering, nullable/selector
  comparators, signed zero and NaN, empty/singleton inputs, and comparator call
  order;
- arbitrary mutable-list implementations and iterator-based writeback;
- comparator exception identity and the no-list-mutation-before-success rule;
- in-place object-array mutation and eager snapshot independence;
- all seven naturally ordered signed primitive wrappers, including byte/short
  carrier fidelity, Char ordering, Float/Double NaN and signed-zero ordering,
  whole/range mutation, snapshot aliasing, and exact invalid-range failure;
- Boolean reversal and explicit comparator/selector ordering without a
  fabricated natural sort;
- separately compiled producer/consumer calls; and
- open producer-generic `sortedArray()` snapshots closed separately to value
  and reference vectors, with exact runtime component type and independent
  storage; and
- facade/slot metadata plus the same portable Roslyn workload on Framework
  CLR 4 and .NET 10 over exact CLR reference/value vectors and primitive
  wrapper storage, proving the absence of an implicit BCL list, comparer, or
  `System.Array.Sort` substitution.
