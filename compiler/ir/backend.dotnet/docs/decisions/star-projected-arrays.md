# ADR: classified `Array<*>` erased view

- Status: **Accepted — pre-ABI**
- Date: 2026-08-01
- Scope: `Array<*>` storage, operations, runtime classification, and CLR interop

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

The exact invariant-vector and specialized-array identities remain owned by
the [primitive-array ADR](primitive-arrays.md). KLIB metadata remains the
authoritative Kotlin declaration identity.

## Common contract

Common declares invariant `Array<T>`. Star projection erases the unknown
element for use: an `Array<*>` retains array identity, exposes `size`, returns
`Any?` from indexed reads and iteration, and does not permit a caller to write
an arbitrary value. The view must include every `Array<E>`, including value
and nullable-value element instantiations, without copying or changing
reference identity. A later checked cast to an exact `Array<E>` tests the
original array.

This is distinct from every specialized primitive-array class. `IntArray is
Array<*>` is false even though both declarations provide indexed storage.

## Cross-target evidence

- JVM erases `Array<*>` to `Object[]`. That is complete there because generic
  primitive elements are boxed; JVM primitive vectors belong to the distinct
  specialized-array declarations.
- JS gives generic arrays the untagged JavaScript-array identity and excludes
  its tagged or typed primitive-array representations in the `Array`
  predicate.
- Native exports one `theArrayTypeInfo` for the reference-slot generic array
  and separate type information and storage layouts for specialized arrays.
- Wasm represents each Kotlin array declaration as a Kotlin class over its
  selected Wasm array storage. Runtime checks therefore retain the generic
  `Array` class identity rather than classifying every Wasm array storage.

All mature targets erase the element argument while retaining Kotlin generic-
array identity and excluding specialized arrays. Their physical mechanisms
differ because their generic arrays have one common target carrier.

## CLR constraint

The selected exact .NET mapping deliberately has no single vector element
token: `Array<String>` is `string[]`, `Array<Int>` is `int32[]`, and
`Array<Int?>` is `Nullable<Int32>[]`. `object[]` is therefore not a supertype
of the latter two. CLR does provide one identity-preserving base class for all
vectors: `System.Array`.

`System.Array` is physically broader than Kotlin `Array<*>`: it also admits
rectangular and non-zero-based arrays. It is consequently a truthful storage
view but not, on its own, a truthful Kotlin runtime classifier.

## Decision

### Physical erased view

`Array<*>` uses `System.Array` in CLR signatures, locals, fields, and returns.
Every exact generic vector widens to that view without an instruction,
allocation, wrapper, copy, or identity change. The star projection remains in
KLIB metadata; consumers must not infer it merely from a `System.Array`
signature.

The natural C# view is likewise `System.Array`. This is an intentionally broad
foreign-language surface paired with a narrower Kotlin-only contract, in the
same sense that CLR nullable attributes do not replace authoritative KLIB
nullability. C# callers may pass any `System.Array`; Kotlin-originated calls
and metadata-aware tooling retain the declared `Array<*>` contract.

### One runtime classifier

Runtime tests and casts do not use bare `isinst System.Array` as their final
answer. One versioned runtime helper classifies a value as a Kotlin generic
array exactly when it is a CLR single-dimensional, zero-based vector (an SZ
array):

1. the value is a `System.Array`;
2. its rank is one; and
3. its runtime type equals the SZ-array type produced from its runtime element
   type.

The third condition distinguishes `T[]` from rank-one `T[*]` on profiles that
do not expose a direct `Type.IsSZArray` API. It also works on Framework and
modern CoreCLR. Foreign SZ vectors are admitted because they are the natural
CLR representation of an imported Kotlin generic array. Rectangular and
non-zero-based arrays are rejected. Specialized Kotlin primitive arrays stay
excluded because their public identity is a Kotlin-owned wrapper, not their
private vector storage.

Checked and safe casts use that classifier before returning the original
`System.Array` reference or null/throwing. No side table or marker is attached
to foreign objects.

### Star-projected operations

- `size` calls `System.Array.Length`.
- indexed `get` calls `System.Array.GetValue(Int32)` and returns its `object`
  result as Common `Any?`. Value elements box and `Nullable<V>` elements use
  the CLR's existing box-or-null behavior; this is required by the erased
  Common result and does not change array storage.
- explicit `iterator()` and `asIterable()` use runtime-owned erased adapters
  over the same `System.Array` and `GetValue` path.
- a later cast to an exact `Array<E>` uses the exact CLR vector token against
  the unchanged original reference.
- star-projected `set` remains projected out by the frontend. Reaching its
  intrinsic is an internal error, never a `SetValue` fallback.

No operation copies or wraps the vector. Aliasing, mutation by an exact view,
and reference equality remain observable through every star view.

## Scope boundary

This decision does not admit:

- input projections such as `Array<in Base>`;
- open nullable elements such as `Array<T?>`;
- bounded output projections, which are governed by the separate
  [read-only projection decision](bounded-output-projected-arrays.md);
- rectangular/non-zero-based CLR arrays as Kotlin generic arrays; or
- star-projected Kotlin-owned generic classes other than `Array`.

Those shapes have different read/write, nested-carrier, or declaration-erased
identity requirements. The later bounded-output decision reuses `System.Array`
only after separately specifying its stronger typed-read recovery; this star
decision alone did not establish that rule.

## Design attack

- **Use `object[]` as on JVM.** Rejected. `int32[]` and
  `Nullable<Int32>[]` are not CLR `object[]`; the Common check would fail for
  supported Kotlin arrays.
- **Use bare `System.Array` for RTTI.** Rejected. It would accept rectangular
  and non-zero-based foreign arrays that cannot later satisfy any exact
  Kotlin `Array<E>` carrier.
- **Wrap every generic vector in a Kotlin class or interface.** Rejected for
  this pre-ABI direction. It would replace the already selected natural C#
  vectors, add allocation/indirection, and make ordinary foreign SZ arrays
  require adapters.
- **Copy value vectors to `object[]` when erased.** Rejected. It changes
  identity, aliasing, mutation visibility, element runtime type, and cost.
- **Tag Kotlin-created vectors in a side table.** Rejected. It would make an
  equivalent foreign SZ vector fail Kotlin interop, add lifetime/concurrency
  state, and still require the `System.Array` operation path.
- **Generalize all projections now.** Rejected. A star read has the fixed
  erased result `Any?`; input and bounded-output projections require separate
  typed write/read rules.

## Completion gate

The feature is complete only when executable tests cover reference, value,
nullable-value, nested, and empty vectors; size/get, explicit and loop
iteration, `asIterable`, aliasing after exact mutation, checked/safe casts,
nullable tests, single evaluation, and rejection of specialized wrappers.
Portable-library and Roslyn consumers must observe `System.Array` signatures
without copying on Framework CLR and CoreCLR. Foreign SZ arrays must be
accepted while rectangular and non-zero-based arrays are rejected by Kotlin
RTTI/casts. Existing open-nullable, input-projection, and covariance negative
sentinels must remain negative.
