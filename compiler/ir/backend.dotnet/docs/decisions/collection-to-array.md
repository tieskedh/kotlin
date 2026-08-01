# ADR: Runtime-typed collection-to-array allocation

- Status: **Accepted — pre-ABI**
- Date: 2026-08-01
- Scope: Common collection-to-array helpers, CLR vector element identity,
  destination reuse, and termination behavior

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Common `AbstractCollection` exposes two protected array operations. One
returns `Array<Any?>`; the other fills a supplied `Array<T>` or creates a
larger array of the same runtime type. Their algorithms are the shared
`collectionToArrayCommonImpl` declarations. Common delegates only the array
allocation and post-fill termination policy to platform actuals.

The public `Collection<T>.toTypedArray()` operation is a distinct reified
inline expect declaration. It is not needed by `AbstractCollection` and does
not belong to this non-reified closure.

The mature targets preserve these boundaries:

- JVM delegates its collection operations to the Java collection helper,
  allocates through the supplied array's runtime component class, and writes
  a Java `Collection.toArray(T[])` null terminator when the destination is
  larger than the collection.
- JavaScript calls the Common loop, uses its uniform host-array
  representation, and performs no termination write.
- Native and Wasm call the same Common loop. Their array actual can allocate
  through the target's uniform generic-array representation, and their
  termination actual is a no-op.
- Wasm and JavaScript implement public `toTypedArray` separately from these
  helpers. JVM uses its reified element token. None makes that public reified
  operation a prerequisite for `AbstractCollection`'s protected methods.

CLR single-dimensional vectors carry a runtime element type and reference
vectors are covariant. A statically typed `Array<Base>` can therefore hold a
physical `Derived[]` at a foreign or unchecked boundary. Allocating merely
from the generic method token would produce `Base[]` and violate Common's
same-runtime-array-type contract.

## Decision

### Keep the Common loops authoritative

The temporary .NET Common-source generator extracts the complete expect and
implementation declarations from `CollectionsH.kt` and `Collections.kt`.
The extraction is fail-closed on unique declaration headers and balanced
function bodies. It does not own or rewrite either loop. An upstream source
or signature change therefore requires regeneration and review rather than
leaving a target fork silently stale.

The .NET actuals delegate both overloads to
`collectionToArrayCommonImpl`. Empty checks, allocation timing, iterator
creation, visit order, casts, stores, exception order, destination reuse, and
the treatment of an inaccurate collection size remain exactly Common.

The typed Common loop contains the non-reified unchecked cast
`iterator.next() as T`. The backend represents that open type-parameter cast
as `unbox.any !!T` after widening the erased iterator result to `object`.
That one CLR instruction unboxes value instantiations and performs the
checked reference conversion for reference instantiations. It does not admit
`as? T`: a failed `unbox.any` throws, so safe generic casts remain in their
own feature boundary.

### Reproduce the supplied vector's runtime element type

The target actual of `arrayOfNulls(reference, size)` uses one narrow,
declaration-suppressing CLR intrinsic. It:

1. evaluates and retains `reference`, then evaluates `size` exactly once;
2. rejects a negative size with Kotlin `NegativeArraySizeException` before
   entering the BCL;
3. obtains the physical vector's runtime element type;
4. calls `System.Array.CreateInstance(elementType, size)`; and
5. casts the result back to the requested `T[]` carrier.

This is the CLR analogue of JVM's reflective same-component allocation. It
works for reference, value, nullable-reference, and open generic vector
elements without erasing the replacement to `object[]`. The intrinsic owns
only the irreducible host allocation operation; the stdlib continues to own
the collection algorithm.

The actual and Common helpers remain Kotlin-internal in KLIB. The current
stdlib producer emits them as CLR-public compiler ABI, records their physical
bindings, and marks them `KotlinCompilerAbi` plus `EditorBrowsable(Never)`;
ordinary Kotlin and C# source surfaces still do not expose them. The
target-private external mechanism has no emitted CLR method or physical
binding.

### Do not import Java's termination convention

The .NET `terminateCollectionToArray` actual returns the supplied array
unchanged. Common explicitly leaves elements following the collection
unspecified. JVM's write at `array[collectionSize]` exists to satisfy Java's
`Collection.toArray(T[])` contract, not Kotlin Common semantics. Kotlin/.NET
collections do not implement or delegate to a BCL collection interface in
this programme, and CLR supplies no corresponding foreign contract that
would justify the extra observable mutation.

### Keep public reified conversion separate

This decision does not admit `Collection<T>.toTypedArray()`. That declaration
requires Kotlin reified-inline substitution and belongs to the reified
language programme. The non-reified helpers are complete and useful without
publishing a substitute whose ordinary CLR generic token would bypass the
Kotlin inline contract.

## Alternatives rejected

### Allocate `T[]` from the static generic token

Rejected. It usually produces the desired vector, but not when a covariant
foreign or unchecked boundary supplies a more specific runtime array. Common
requires the replacement to have the same array type as the reference.

### Allocate `object[]` and cast it

Rejected. The cast is invalid for `string[]`, value vectors, and most other
requested carriers. Even where a consumer only observes `Array<Any?>`, it
would erase the typed overload's physical promise.

### Copy the Common loop into the .NET actual

Rejected. The CLR changes allocation mechanics, not iteration semantics. A
target copy would make upstream Common behavior advisory and could drift in
evaluation, exception, or hostile-collection behavior.

### Delegate to LINQ or a BCL collection

Rejected. Kotlin collection identity is not a BCL interface, and such a
delegation would change enumeration, casts, size inconsistencies, exceptions,
and dependency ownership. BCL adapters remain a separate interop programme.

### Null-terminate like JVM

Rejected. That mutation implements a Java foreign-interface contract. Adding
it on .NET would not improve Common conformance and would make an unspecified
tail observably JVM-shaped without a CLR cause.

### Implement `toTypedArray` with an ordinary CLR generic method

Rejected. CLR generic type availability is not Kotlin reified-inline
substitution. It would publish a target-specific non-inline substitute and
weaken the explicit reified feature boundary.

## Ownership

- Common stdlib: expect declarations, collection loops, destination reuse,
  iterator/store order, and observable failures.
- Temporary .NET Common-source generator: exact fail-closed projection of the
  selected declarations until the full Common file can be compiled.
- .NET stdlib actual: internal delegation and the non-Java termination policy.
- .NET backend: open type-parameter cast, exact runtime-vector allocation
  intrinsic, negative-size boundary, and compiler-ABI binding.
- CLR/BCL: runtime element-type evidence and raw vector construction only.
- Reified-inline programme: future public `toTypedArray` support.

## Consequences and freeze conditions

The abstract collection bases can depend on truthful non-reified array
helpers without prematurely admitting reified inline. Replacement arrays
retain their requested physical element identity, while sufficiently large
destinations retain reference identity and their unspecified tail.

Before this surface freezes, tests must cover:

- empty, exact-size, undersized, and oversized destinations;
- nullable/reference, value, and open generic element types;
- a covariantly supplied more-specific runtime reference vector;
- hostile collections with inaccurate sizes, throwing iterators, and exact
  traversal/store order;
- negative allocation sizes at the intrinsic boundary;
- Framework CLR and CoreCLR behavior and physical reflection evidence;
- direct and packaged-source stdlib identity; and
- continued explicit rejection of public reified `toTypedArray` until its
  owning programme is complete.

This ADR does not admit the Common abstract bases themselves, the
`Appendable`/`StringBuilder` closure needed by their rendering, mutable
collections, BCL adapters, general reflection, or general reified functions.
