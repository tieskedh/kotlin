# Draft ADR: Kotlin-owned primitive-array wrappers

- Status: **Accepted pre-ABI direction; foundational slice implemented**
- Date: 2026-07-17
- Scope: `Array<T>`, specialized primitive arrays, CLR vectors, identity, and C# projection

This is the selected repository direction for the experimental backend. Nothing has shipped, so
the current raw-vector primitive-array representation may be replaced without compatibility
adapters. It is not a public KEEP or an official Kotlin target commitment.

## Context

Kotlin gives `Array<Int>` and `IntArray` different nominal types, overload identities, reflection
identities, and generic behavior. On the CLR, a generic vector `T[]` instantiated with
`T = System.Int32` is physically `int32[]`. Mapping `IntArray` to the same CLR vector collapses two
legal Kotlin types and makes their overloads physically indistinguishable.

Rejecting direct `Array<Int>` does not solve the problem because a separately compiled generic
producer can instantiate `Array<T>` with `Int`. Erasing all generic arrays to an object carrier
would preserve nominal distinction at the cost of natural CLR representation, allocation, and C#
interop.

## Decision

### Generic arrays

Kotlin `Array<T>` uses the natural CLR vector representation `T[]`. Generic substitution is
honored, so `Array<Int>` is physically `int32[]`. The frontend must not ban a legal Kotlin type to
protect an accidental backend representation choice.

Kotlin invariance remains a source and metadata rule even though CLR vectors are covariant for
reference elements. The importer must not infer Kotlin covariance from the CLR carrier. Foreign
stores can still trigger CLR `ArrayTypeMismatchException`; that is an interop-boundary hazard to
document and test, not a reason to change Kotlin variance.

An output-projected `Array<out T>` retains the same `T[]` carrier and records the projection only
in Kotlin metadata. At a closed reference boundary, CLR's own vector covariance truthfully
implements the Kotlin widening (`Derived[] -> Base[]`). At a value boundary it cannot:
`Array<Int>` is `int32[]`, which is not assignable to the `object[]` carrier of
`Array<out Any>`. The backend rejects that widening until an identity-preserving boxed carrier is
designed; it never copies or silently changes the vector. Exact value instantiations such as
`Array<Int>.asList()` remain `int32[]` end to end. `in` and star projections remain rejected
because they do not provide one truthful CLR element token.

### Specialized primitive arrays

Every specialized primitive-array type (`IntArray`, `LongArray`, `BooleanArray`, and the remaining
Kotlin primitive arrays) is a sealed Kotlin-owned reference wrapper with:

- its own CLR runtime type and Kotlin metadata identity;
- one primitive CLR vector as mutable storage;
- reference identity belonging to the wrapper, not the storage vector;
- identity-based `equals`/`hashCode` unless a Kotlin API explicitly requests content operations;
- Kotlin-defined construction, indexing, iteration, copy, bounds, and string behavior; and
- a nullable representation as a nullable wrapper reference, with no primitive boxing.

The runtime owns the wrapper definitions. Compiler intrinsics and stdlib operations target a
documented internal storage/access ABI; user Kotlin signatures never substitute the storage vector
for the wrapper.

### C# projection

Ordinary Kotlin-to-Kotlin ABI exposes `Kotlin.IntArray` (and corresponding wrapper types), not
`System.Int32[]`.

A deliberate C# export facade may expose an `IntArray` parameter or result as `int[]`. The wrapper
type may also provide standard CLR `op_Implicit` conversion operators. These are generated or
runtime-owned interop adapters:

- wrapper to vector may return the live backing storage without a copy;
- vector to wrapper constructs or retrieves a wrapper that aliases that vector;
- mutation aliasing and conversion allocation must be documented on the exported API; and
- the conversion does not make `int[]` a Kotlin `IntArray` or change Kotlin overload identity.

The exporter must choose and pin repeated-conversion and round-trip identity behavior before
enabling an inbound implicit conversion. If it cannot provide a defensible identity contract, it
must use an explicit facade conversion instead of `op_Implicit`. Silent copying is forbidden unless
the exported API is explicitly documented as a copy operation.

The implemented explicit-export facade uses aliasing adapters: inbound `int[]` storage is wrapped
without copying for the duration of the Kotlin call, and an outbound wrapper returns its live
storage. Therefore an export that returns its primitive-array argument round-trips the same vector
reference to C#. This is an export contract, not canonical Kotlin type equivalence.

Repeated conversion uses a runtime-owned weak association from each live storage vector to one
wrapper of the corresponding specialized-array type. Inbound facade adaptation retrieves or
creates that wrapper; outbound projection registers the canonical wrapper before returning its
storage. Consequently, passing one vector to two exported parameters preserves `===`, adapting it
again in a later call recovers the same live wrapper, and a Kotlin-created wrapper exported then
passed back is recovered rather than replaced. Distinct vector references remain distinct wrappers.
The association uses ephemeron/`ConditionalWeakTable` semantics, so the wrapper-to-storage reference
does not turn the runtime table into a permanent array leak. A short monitor-protected compound
operation makes lookup/creation and outbound registration atomic on every supported runtime.
`arrayinternprobe_s1` assembled and executed that exact generic CWT/monitor IL with both Framework
and modern ILAsm/runtimes before it entered the generated runtime.

Ordinary Kotlin construction does not consult or populate the table. Only an inbound or outbound
interop projection pays the association cost. Runtime-owned `op_Implicit` conversions remain
disabled: stable identity makes them technically possible, but whether implicit allocation is an
appropriate C# API surface is a separate exporter design decision.

### ABI and profiles

The wrapper's public Kotlin identity is the same for `net48`, `netstandard2.0`, and `net10.0`.
Target variants may emit different method bodies or use profile-specific implementation helpers,
but public Kotlin metadata, overload resolution, mutation behavior, and wrapper identity do not
change.

Array-bearing physical signatures are profile-bound in the declaration index embedded in the DLL. A stale
module that used the old raw-vector mapping is rejected by the prototype schema rather than adapted.

## Ownership

- Common frontend/type system: Kotlin nominal distinction, invariance, and legal substitutions.
- .NET backend: wrapper-aware lowering, physical signatures, intrinsics, boxing decisions, and CLR
  vector operations.
- Kotlin runtime: wrapper classes and stable internal storage/access surface.
- Standard library: ordinary array algorithms and content operations where they can be authored in
  Kotlin.
- Exporter/importer: `int[]` projections, conversion operators, nullability attributes, and foreign
  covariance boundaries.

## Consequences

- `Array<Int>` and `IntArray` remain distinct in overloads, reflection, type checks, and generics.
- Generic arrays retain efficient, idiomatic CLR vectors.
- Specialized primitive arrays pay one wrapper allocation and one indirection in their canonical
  Kotlin representation. Escape analysis or target-specific optimization may remove costs only
  when observable identity and aliasing are preserved.
- C# can receive the expected primitive vector surface without dictating Kotlin's canonical type
  model.
- Existing raw-vector primitive-array IL and runtime helpers are intentionally replaceable.

## Implemented foundation

The current foundational slice implements the following for the five scalar types already
supported by this backend (`Int`, `Long`, `Double`, `Boolean`, and `Char`):

1. sealed public `Kotlin.*Array` runtime wrappers with private, readonly vector storage;
2. marked and editor-hidden public compiler ABI for construction, size, indexed access, storage
   normalization, and nullable export adaptation across assemblies;
3. wrapper-shaped canonical fields, parameters, returns, locals, varargs, overloads, nullable
   references, and generic storage;
4. natural `T[]` generic arrays, including direct and cross-module `Array<Int>` -> `int32[]`
   substitution and nested arrays;
5. wrapper-aware construction, initializers, indexing, loops, iterators, copying, content
   operations, recursive content operations, data classes, and varargs;
6. explicit C# export projection to primitive vectors with live aliasing, weakly interned repeated
   conversion identity, and vector-reference/wrapper-identity round-trip preservation; and
7. `netstandard2.0` producer tests consumed by both `net48` and `net10.0`, plus C# metadata and
   runtime verification.

The wrapper ABI is versioned by runtime surface level 6. Because no artifact has shipped, its
member spelling can still be deliberately revised before the first ABI gate, but generated code
and runtime definitions must change together.

## Required validation

Before array-bearing ABI expands, commit tests for:

1. direct and separate-module generic substitution with `T = Int`;
2. overloads that differ only by `Array<Int>` versus `IntArray`;
3. `===`, `==`, hash codes, reflection/type tests, nullability, and generic storage;
4. mutation, bounds, negative sizes, copy operations, iteration, varargs, and nested arrays;
5. boxing through `Any`, generic interfaces, and callable/reference captures;
6. all three target profiles with identical observable Kotlin results;
7. C# inbound/outbound conversion, aliasing, the selected repeated-conversion identity policy, and
   round trips; and
8. stale raw-vector metadata rejection and separately compiled producer/consumer linkage.

## Deferred details

The remaining primitive scalar wrappers (`ByteArray`, `ShortArray`, `FloatArray`, and unsigned
arrays when their scalar model lands), implicit-conversion API policy, debugger proxies, enumerator
shape, span projections on `net10.0`, and optimization strategy remain open.
The current private-field and marked-member layout is provisional only until the first ABI gate.
None of these details may collapse Kotlin wrapper identity or expose a profile-specific
representation in common Kotlin metadata.
