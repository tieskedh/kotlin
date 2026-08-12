# Bounded output-projected arrays use one read-only CLR array view

- Status: Accepted (pre-ABI)
- Scope: Kotlin `Array<out E>` in signatures, locals, calls, and reads
- Does not admit: `Array<in E>`, invariant open `Array<T?>`, or a second
  generic-array identity. Open nullable output views and nullable generic
  varargs follow the distinct accepted carrier rules in
  [`open-nullable-array-views-and-varargs.md`](open-nullable-array-views-and-varargs.md).

## Common authority

Kotlin `Array<T>` is invariant, but an output projection may view every compatible exact array
without copying. `Array<Int>` can therefore be read as `Array<out Any?>`; `size`, indexed reads,
iteration, identity, aliasing, and a later exact cast all continue to observe the original array.
The projected view permits no element write.

The Common collection factories exercise this rule without making it a collection-specific
contract: a method-generic vararg is materialized as an exact `T[]`, then read through an
`Array<out Any?>` view by the authoritative `asArrayList` path.

## Mature-target evidence

- JVM uses one reference-array carrier after generic erasure. Output projection changes the
  Kotlin type system's read/write permissions, not array identity.
- JS uses its ordinary array representation and enforces the projection in Kotlin metadata and
  operations.
- Native and Wasm likewise retain the original array object while projected reads use the
  declared upper view; neither copies an array to implement projection.

Those targets establish the semantic direction: projection is an identity-preserving capability
view, not a conversion to a new collection or vector.

## CLR constraint

CLR SZ arrays are covariant only for reference element types. `string[]` may widen to `object[]`,
but `int32[]`, `Nullable<int32>[]`, and a method-generic `T[]` cannot truthfully widen to
`object[]` for every substitution. Copying or boxing into `object[]` would change identity,
aliasing, runtime element type, mutation visibility, and cost.

Every SZ vector instead has the common reference base `System.Array`. The target already uses
that carrier for `Array<*>`, with `get_Length` and `GetValue` followed by the logical result
narrowing. The same physical operations can preserve the stronger bounded read result retained in
KLIB.

## Decision

`Array<out E>` uses `System.Array` as its physical read-only carrier. KLIB remains authoritative
for `E`, nullability, and the output projection. Indexed reads call `System.Array.GetValue` and
recover the declared logical result at the use site with the existing checked cast or
`unbox.any`; iteration uses the same identity-preserving classified array path. Writes remain
rejected by the frontend and backend.

Invariant `Array<E>` continues to use the exact CLR `E[]` carrier whenever the existing array
mapping admits `E`. In particular, an invariant method-generic vararg remains `T[]`; only a
projected view of that value widens without an instruction to `System.Array`. `Array<*>` retains
its separately selected erased read result `Any?` and runtime-classification rules.

Although Kotlin IR initially types a reference `vararg E` parameter as source-level
`Array<out E>`, the vararg marker selects a distinct physical declaration rule. A non-null open
`vararg E` uses exact invariant `E[]` storage. An open nullable `vararg E?` instead uses the
declaration-stable boxed-or-null `object[]` rule accepted in
[`open-nullable-array-views-and-varargs.md`](open-nullable-array-views-and-varargs.md). Signature
mapping reads that marker before target lowering, because a separate consumer reconstructs the
producer's physical call signature from the logical KLIB declaration. Ordinary non-vararg
`Array<out E>` parameters remain `System.Array`, including an open nullable element.

The shared inliner may introduce immutable argument temporaries whose substituted logical type is
wider than the exact vector supplied at the call site. Common's InlineOnly
`MutableCollection<in T>.plusAssign(Array<T>)` is the canonical adversarial shape: receiver
contravariance can make the temporary read `Array<Any?>` while its value remains an `Array<Int>`.
The .NET method code generator follows exact array provenance through only those compiler-created
immutable temporary chains and keeps the original `int32[]` local. A later projected consumer
widens that slot to `System.Array`. Source locals, mutable compiler locals, public signatures and
invariant destinations retain their declared mapping; this is neither array covariance nor an
array copy.

This is an ABI correction: existing projected parameters such as `Array<out T>.asList()` change
from a potentially untruthful exact vector signature to `System.Array`. The pre-ABI physical
schema version moves with every producer and consumer; no compatibility inference is added.

## CLR and C# boundary

Raw C# sees `System.Array`, which is broader than the Kotlin declaration. That is truthful as a
low-level physical view but is not an idiomatic typed export: the embedded KLIB alone carries the
bounded element contract. A future explicit .NET export may generate a checked typed adapter when
it can preserve the declared element relationship. It must not change Kotlin array identity or
make the internal physical signature pretend to be `E[]` for value-array covariance.

Imported CLR `T[]` parameters remain native CLR signatures. This decision applies to
Kotlin-owned projected generic-array declarations, not to foreign metadata.

That distinction also governs overrides. The importer may expose an exact foreign `E[]` MethodDef
through a flexible Kotlin view such as `Array<E>..Array<out E>?`; the retained MethodDef, not that
enhanced view, remains the physical slot authority. When a rigid Kotlin override emits the same
exact `E[]` parameter and return carriers, it fills the foreign slot directly. Covariant-return
analysis must not reinterpret the flexible view as `System.Array` and manufacture a MethodImpl:
CLR requires the implementation body and declaration signatures to match exactly and rejects such
a bridge during type loading. The backend rechecks the complete retained foreign signature before
suppressing an adapter, and its final slot validation consumes the same retained physical record.

## Design attack

- **Keep `E[]` and support only CLR-legal reference covariance.** Rejected because ordinary
  Kotlin projection also covers value-element arrays and method-generic substitutions.
- **Use `object[]`.** Rejected because value arrays cannot widen to it and copying breaks identity.
- **Special-case Common `asArrayList`.** Rejected because factories merely reveal the general
  language rule; the same projection occurs in user signatures and other Common algorithms.
- **Wrap every projected array.** Rejected because mature targets preserve the original object,
  and wrappers change identity, casts, aliasing, and interop.
- **Treat every `System.Array` as a Kotlin array for RTTI.** Rejected. Runtime tests and casts keep
  the accepted SZ-array classifier, excluding rectangular and non-zero-based arrays.

## Scope boundary

Input projections and invariant open nullable arrays need a different write-safe carrier and
remain unsupported. The accepted open-nullable output and vararg cases are governed by
[`open-nullable-array-views-and-varargs.md`](open-nullable-array-views-and-varargs.md). This change
does not add array copying, value-vector covariance as an exact CLR vector type, specialized
primitive-array identity, reified public APIs, or BCL collection interfaces.

## Verification

The completion matrix must cover reference, value, nullable-value, method-generic, nested, empty,
and widened arrays; size, indexed reads, iteration, identity, mutation through an exact alias,
wrong-element failure at logical use, and continued write rejection. It must include same-module
and separate Kotlin consumers, physical metadata and stale-schema rejection, Framework CLR and
CoreCLR execution, a truthful C# `System.Array` view, and continued rejection of input projections
and non-SZ arrays as Kotlin runtime identity. It must also cover a shared-inline temporary whose
logical element type widens across a contravariant receiver while the exact value-vector carrier
remains unchanged. A portable producer plus separately compiled Common-stdlib consumer must prove
that method-generic `vararg T` binds as `T[]`, never as the ordinary projected `System.Array` view.
It must also implement imported primitive, reference, and nullable ordinary-vector interface
members from Kotlin on Framework CLR and CoreCLR, proving both direct dispatch and the absence of a
spurious `System.Array` MethodImpl.
