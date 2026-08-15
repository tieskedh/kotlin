# ADR: Kotlin-owned primitive-array wrappers

- Status: **Accepted — pre-ABI**
- Date: 2026-07-17
- Scope: `Array<T>`, specialized primitive arrays, CLR vectors, identity, and
  C# projection

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

Primitive vector element spellings and boxed scalar behavior follow the
[primitive-scalar carrier ADR](primitive-scalars.md).

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

`Array<out T>` records its projection and bounded read result in KLIB while its
physical view is `System.Array`, as selected by the later bounded-output
projection decision. The original exact vector remains unchanged: an
`int32[]` and a `string[]` widen to that common base without copying, and reads
recover the declared bound at use. `in` projections remain unsupported where
no single truthful write token exists; stars follow their separate classified
`System.Array` decision with the fixed erased `Any?` read result.

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

### The signed Common family is complete

The selected wrapper set contains all eight non-unsigned Common primitive
arrays: `BooleanArray`, `ByteArray`, `ShortArray`, `IntArray`, `LongArray`,
`FloatArray`, `DoubleArray`, and `CharArray`. Common gives every family the
same constructor, initializer, indexed access, mutation, size, and specialized
iterator contract. The mature targets keep that family atomic:

- JVM uses the corresponding eight JVM primitive vectors;
- JS selects distinct primitive-array representations and runtime predicates;
- Wasm defines a Kotlin array class over the corresponding Wasm storage array
  for every family; and
- Native defines a distinct runtime type, specialized accessors, and iterator
  for every family.

.NET therefore does not leave `ByteArray`, `ShortArray`, or `FloatArray`
parked once the exact `Byte`, `Short`, and `Float` scalar carriers exist. They
use the same wrapper generator and compiler/runtime ABI as the first five:

| Kotlin array | private CLR storage | C# export view |
| --- | --- | --- |
| `ByteArray` | `System.SByte[]` (`int8[]`) | `sbyte[]` |
| `ShortArray` | `System.Int16[]` (`int16[]`) | `short[]` |
| `FloatArray` | `System.Single[]` (`float32[]`) | `float[]` |

The element carrier is exact. `ByteArray` is signed and must not become C#
`byte[]`/CLR `UInt8[]`; `Byte` and `Short` are not widened to `Int32` in
storage, and `Float` is not widened to `Double`. Evaluation-stack promotion
does not change the array element or signature type.

One registry owns the admitted wrapper family. Type mapping, runtime wrapper
generation, constructor/literal/member intrinsics, varargs, indexed for-loop
lowering, escaping specialized iterators, copy/content helpers, runtime type
tests/casts, nullable metadata, and explicit C# vector adapters must all derive
from that complete registry or carry an explicit family-complete companion
entry. A partial wrapper that constructs but cannot iterate, copy, cross a
library boundary, or round-trip through its exact C# vector is not complete.

### Concrete nullable primitive elements remain generic arrays

Common permits each primitive as a nullable element of invariant `Array<E>`.
The logical type is not a specialized primitive array: `Array<Int?>` remains
distinct from both `IntArray` and `Array<Int>`. Every mature target accepts the
shape while choosing its own physical nullable-element representation. JVM
uses boxed primitive references, JS uses its generic JavaScript array model,
and Wasm/Native preserve the same Common generic-array contract through their
target value/reference representations.

CLR supplies an exact, allocation-free typed slot for every concrete nullable
primitive. The .NET carrier is therefore a vector of the already-selected
closed `System.Nullable<T>` value, not `object[]`:

| Kotlin type | CLR element/vector | C# view |
| --- | --- | --- |
| `Array<Boolean?>` | `Nullable<Boolean>[]` | `bool?[]` |
| `Array<Byte?>` | `Nullable<SByte>[]` | `sbyte?[]` |
| `Array<Short?>` | `Nullable<Int16>[]` | `short?[]` |
| `Array<Int?>` | `Nullable<Int32>[]` | `int?[]` |
| `Array<Long?>` | `Nullable<Int64>[]` | `long?[]` |
| `Array<Float?>` | `Nullable<Single>[]` | `float?[]` |
| `Array<Double?>` | `Nullable<Double>[]` | `double?[]` |
| `Array<Char?>` | `Nullable<Char>[]` | `char?[]` |

The same carrier rule applies when a non-null value vector becomes nullable
as the result of Common `Array<V>.copyOf(newSize): Array<V?>`. Retaining the
source `V[]` is incorrect: its padded suffix contains default `V`, not Kotlin
null, and the vector cannot be cast to `Nullable<V>[]`. The backend allocates
the truthful nullable vector, loads each copied prefix element from `V[]`,
constructs `Nullable<V>` directly, and stores it with typed array opcodes.
There is no boxing, reflective `SetValue`, or whole-vector `object[]`
conversion. Reference-element arrays keep their runtime-component-preserving
copy path because reference nullability does not change their CLR vector type.

This is a concrete closed-carrier rule. It does not map nested open `Array<T?>`
to `object[]`: the accepted hybrid-nullability ADR keeps that shape rejected
because one invariant vector signature cannot become `Nullable<V>[]` for a
value substitution and a reference vector for a reference substitution.
Likewise, a projection from a value-element vector to `Array<out Any?>` does
not become `object[]`; it retains the exact vector through the bounded-output
`System.Array` view. Exact invariant use, nesting, and generic substitution
continue to use their ordinary precise vector carriers.

#### Design attack

- **Use `object[]` like a blanket JVM boxing translation.** Rejected. It would
  collapse `Array<Int?>` with `Array<Any?>`, discard the CLR's exact nullable
  signature, and lose natural C# interop.
- **Treat `Nullable<Int32>[]` as `IntArray`.** Rejected. Nullable slots and
  Kotlin generic-array identity are observable; the specialized wrapper has a
  different declaration and storage contract.
- **Admit `Array<T?>` at the same time.** Rejected. A closed exact carrier does
  not solve the accepted open-nested-carrier problem.
- **Claim covariance through boxing.** Rejected. Boxing every element into a
  replacement vector would lose array identity and mutation aliasing.
- **Support only `Int?`.** Rejected. Common and the selected scalar model are
  symmetric across all eight concrete primitive families.

Implementation evidence covers all eight literal families, null/default
construction, initializer order, generic function and class substitution,
nullable varargs/spreads, direct and escaping iteration, copies and content
operations, nesting, exact casts, negative sizes, and same-element aliasing on
Framework CLR and CoreCLR. A portable netstandard2.0 Kotlin library exposes
the exact eight `Nullable<V>[]` signatures; separate Kotlin consumers and one
Roslyn consumer execute them without copying on both runtimes. The remaining
negative sentinels use frontend-legal open/projection forms so admitting this
closed family cannot silently broaden their boundary.

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

## Design attack: completing the last three signed wrappers

- Mapping the specialized arrays directly to raw CLR vectors is rejected: it
  would collapse `ByteArray` with `Array<Byte>`, `ShortArray` with
  `Array<Short>`, and `FloatArray` with `Array<Float>`.
- Using `byte[]` for Kotlin `ByteArray` is rejected: C# `byte` is unsigned,
  while Kotlin `Byte` is the already-selected signed `System.SByte` carrier.
- Widening narrow or float storage is rejected: stack promotion is not source
  identity, and widened storage would change signatures, aliasing, reflection,
  and C# projection.
- Adding only constructor/get/set intrinsics is rejected: Common also requires
  literals/varargs, initializer order, specialized iteration, copying,
  content operations, type tests, and separate compilation.
- Reusing a raw vector only for C# convenience is rejected as Kotlin ABI. The
  existing explicit aliasing adapter remains the sole foreign projection.

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

Unsigned arrays, debugger projections, enumerator shape, modern span adapters,
implicit-conversion policy, and optimization strategy remain open. None may
collapse Kotlin wrapper identity or expose a profile-specific representation
in Common KLIB.
