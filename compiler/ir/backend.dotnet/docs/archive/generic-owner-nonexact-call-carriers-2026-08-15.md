# Generic-owner non-exact call carriers — 2026-08-15

## Problem

The production-inert generic-owner snapshot used CLR GenericParams for every
owner-relative typed MethodDef position. That was too strong for two Kotlin
shapes:

- an unconstrained `T?` was recorded as `!T`; and
- a projected `Array<out/in T>` was recorded as `!T[]` in the typed role.

Neither record is a truthful fixed MethodDef signature. `T = Int` needs a
nullable value result to distinguish null from zero. An exact `Owner<Any>` can
also receive a value or narrower reference vector through an output-projected
array view; those vectors are not all CLR `object[]` or one `!T[]`.

## Selected bounded carriers

The snapshot mapper now applies this table:

| Kotlin member position | Physical record |
| --- | --- |
| non-null owner `T` | owner `!T` |
| unconstrained owner/method `T?` | `object` |
| invariant `Array<T>` | `!T[]` |
| invariant `Array<T?>` | `System.Array` |
| projected `Array<out/in E>` | `System.Array` |
| independent invariant method `Array<R>` | `!!R[]` |

For a nullable value, normal CLR boxing produces either the boxed underlying
value or null, so `object` retains the Kotlin distinction. It is not a claim
that the logical result became `Any?`: the slot remains owner-dependent and a
Kotlin consumer must apply the corresponding nullable substitution semantics.

`System.Array` is likewise the fixed boundary carrier, not permission to use
untyped operations internally. A body may select a typed vector path after a
non-throwing compatibility proof, as the existing projected join fast path
does.

## Executable evidence

Three independent owner shapes pin the result:

- recursive OctoTree records `get(): T?` as a strict owner output carried by
  `object` in both its typed and capability signatures;
- `GenericNullableArrayCopier<T>` records `System.Array` for both its
  `Array<T?>` constructor input and `shiftRight()` result; and
- hostile `HostileUnsafeStore<T>.echo(Array<out T>)` records `System.Array`
  for every typed/semantic/capability signature.

The hostile temporary C# producer and its separately compiled derived class
now override both echo MethodDefs with `System.Array`. The capability
dispatcher still performs one `is T[]`-equivalent probe: a compatible vector
enters the typed override and another Kotlin-valid vector enters the semantic
override. Thus the metadata carrier does not erase dispatch authority.
Reflection verifies the exact `System.Array` MethodDefs. The unrelated
`relay<R>(R[])` continues to prove a native method-generic `R[]` signature.

The separate Kotlin consumer exposed one related invalid assumption: external
override binding equated a physical GenericParam reference with logical owner
dependence. That fails for a strict `T?` or projected-array slot carried by
`object`/`System.Array`. The binder now merges the producer's logical slot
domains independently and then requires exact compiler-derived physical
signature equality. If a semantic hook is learned only from the producer, the
consumer's existing capability signature is the exact local owner-erased
witness. No physical name or type-shape heuristic creates the role.

## Boundaries

This changes no production owner, emitted field or method, DLL/KLIB schema,
Runtime surface, or Common behavior. Schema 7's format is unchanged; only the
recorded test-artifact signature is corrected.

The recursive OctoTree candidate remains blocked on constructed member types
such as `Node<T>[]`. Those require a path-unbound signature tree and late
logical-key-to-TypeDef binding equivalent to the completed state-carrier
mechanism. A source/display name is not an acceptable shortcut.

## Verification

Focused PSI/LightTree execution on Framework 4.8 and .NET 10 covers the hostile
physical family/C# subclass, OctoTree, separate OctoTree producer/consumer,
ArrayCopy, and nullable-array copier. The final cold-cache strict aggregate
completed in 3,123.2 seconds. Direct audit covers 190 XML files and 2,238 tests
with zero failures, errors, or skips.
