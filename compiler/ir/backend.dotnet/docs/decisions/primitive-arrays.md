# ADR: Kotlin-owned primitive-array wrappers

- Status: **Accepted — pre-ABI**
- Date: 2026-07-17
- Scope: `Array<T>`, specialized primitive arrays, CLR vectors, identity, and
  C# projection

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Kotlin gives `Array<Int>` and `IntArray` different nominal types, overload
identities, reflection identities, and generic behavior. On CLR, `T[]` at
`T = System.Int32` is physically `int32[]`. Mapping `IntArray` to the same
vector would collapse two legal Kotlin types.

Rejecting `Array<Int>` is invalid because a separately compiled generic
producer may instantiate `Array<T>` with `Int`. Erasing every generic array to
an object carrier would preserve the distinction but sacrifice the CLR's
natural generic-vector representation and interop.

## Decision

### Generic arrays use CLR vectors

Kotlin `Array<T>` uses `T[]`, including `int32[]` for `Array<Int>`. The
frontend never bans a legal Kotlin substitution to protect a backend mapping.

Kotlin invariance remains a source and KLIB rule even though CLR reference
vectors are covariant. The importer does not infer Kotlin covariance from the
carrier. Foreign stores may therefore throw CLR `ArrayTypeMismatchException`;
that is an interop hazard rather than a reason to weaken Kotlin variance.

`Array<out T>` keeps the same vector carrier and records its projection in
KLIB. Closed reference widening may use truthful CLR covariance. A value
vector cannot widen to `object[]`; until an identity-preserving carrier exists,
that boundary is rejected rather than copied or silently changed. `in` and
star projections remain unsupported where no single truthful element token
exists.

### Specialized arrays are Kotlin-owned wrappers

Every specialized primitive-array type is a sealed Kotlin-owned reference
wrapper with:

- its own CLR runtime type and Kotlin metadata identity;
- one primitive CLR vector as mutable storage;
- reference identity on the wrapper rather than its storage;
- identity `equals`/`hashCode` unless an explicit Kotlin content operation is
  requested;
- Kotlin-defined construction, indexing, iteration, copying, bounds, and
  string behavior; and
- nullable representation as a nullable wrapper reference.

The runtime owns wrapper definitions and a versioned internal storage/access
ABI. Compiler intrinsics and stdlib operations use that ABI; ordinary Kotlin
signatures never substitute the storage vector for the wrapper.

### C# vector projection is an explicit adapter

Kotlin-to-Kotlin ABI exposes `Kotlin.IntArray` and its peer wrappers, not
`System.Int32[]`. An explicit C# export facade may project a wrapper as a
primitive vector.

The selected adapter is aliasing rather than copying:

- wrapper-to-vector returns live backing storage;
- vector-to-wrapper obtains a wrapper for that live storage;
- an exported function returning its primitive-array argument round-trips the
  same vector reference; and
- the projection does not make the vector a Kotlin primitive-array identity.

A runtime-owned weak, ephemeron-style association maps each live vector to one
wrapper of the corresponding type. Compound lookup/creation and outbound
registration are atomic. This preserves `===` for repeated parameters, later
calls, and Kotlin-wrapper round trips without turning the table into a
permanent leak. Ordinary Kotlin construction does not pay this association
cost until an interop projection occurs.

Implicit conversion operators remain disabled. Whether implicit allocation is
an appropriate C# surface is an exporter decision; explicit adapters remain
valid even if implicit conversion is rejected. Any copying projection must be
named and documented as a copy.

### Profiles preserve one Kotlin identity

The wrapper's public Kotlin identity, metadata, overload behavior, mutation,
and aliasing are the same on `net48`, `netstandard2.0`, and `net10.0`.
Profile-specific bodies or helpers may differ without changing that contract.

Array-bearing physical signatures are recorded in the self-describing DLL.
Artifacts carrying an incompatible raw-vector representation are rejected,
not adapted.

## Ownership

- Common frontend/type system: nominal distinction, invariance, projections,
  and legal substitutions.
- .NET backend: wrapper-aware lowering, physical signatures, intrinsics,
  boxing, and vector operations.
- Kotlin runtime: wrapper identities and internal storage/access ABI.
- Standard library: ordinary array algorithms and content operations.
- Import/export tooling: vector projection, nullable attributes, conversions,
  and foreign covariance boundaries.

## Consequences

- `Array<Int>` and `IntArray` remain distinct in overloads, reflection, type
  checks, and generic storage.
- Generic arrays retain an efficient idiomatic CLR carrier.
- Specialized arrays pay a canonical wrapper allocation/indirection unless an
  optimization proves identity and aliasing unobservable.
- C# can receive primitive vectors without dictating Kotlin's type model.
- Unpublished raw-vector primitive-array ABI is intentionally replaceable.

## Freeze conditions and open decisions

Before array-bearing ABI freezes, validation must cover:

- separate-module generic substitution and nested arrays;
- overloads differing only by `Array<Int>` and `IntArray`;
- identity, equality, hashing, type tests, nullability, boxing, and generic
  storage;
- mutation, bounds, negative sizes, copying, iteration, varargs, and content
  operations;
- identical Kotlin behavior across all profiles; and
- C# aliasing, repeated-conversion identity, round trips, and stale-artifact
  rejection.

Remaining scalar wrappers, unsigned arrays, debugger projections, enumerator
shape, modern span adapters, implicit-conversion policy, and optimization
strategy remain open. None may collapse Kotlin wrapper identity or expose a
profile-specific representation in Common KLIB.
