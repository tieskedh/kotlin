# ADR: Hybrid generic nullability and covariant-return bridges

- Status: **Accepted**
- Date: 2026-07-21
- Scope: Kotlin-owned declarations on `net48`, `netstandard2.0`, and `net10.0`

This is a pre-ABI decision for the experimental Kotlin/.NET backend. No Kotlin/.NET binary has
shipped, so conflicting prototype signatures are replaced rather than preserved.

## Context

The CLR has no single reified spelling for an unconstrained Kotlin `T?`. A closed value-type
substitution would naturally use `Nullable<T>`, while a reference substitution uses the reference
itself. A generic declaration nevertheless has one fixed metadata signature; its fields and method
slots cannot change shape for each future substitution.

Concrete nullable primitives do not have that problem. `Int?`, `Long?`, `Double?`, `Boolean?`, and
`Char?` can use the corresponding closed `System.Nullable<T>`, preserving the natural strongly
typed C# surface. Nullable references keep their ordinary CLR reference carrier.

Kotlin also permits an override to refine a return type. CLR method-slot identity includes the
return type on every supported profile. Merely emitting a narrower-return method therefore does
not override a wider-return base or interface slot. Modern CLR covariant-return metadata cannot be
the common solution because it is unavailable on `net48` and `netstandard2.0`.

## Decision

### 1. Open `T?` uses a boxed-or-null object carrier

Every occurrence whose outer type is a nullable type parameter is physically `System.Object` in
the declaring ABI. This applies to parameters, results, locals, and fields.

- a value-type `T` is boxed when it enters the carrier;
- a non-null `Nullable<V>` boxes as `V`, and an empty value boxes as `null`, following CLR nullable
  boxing rules;
- a reference enters without allocation;
- recovery of a known closed value uses `unbox.any`, which handles boxed values and
  `Nullable<V>` recovery;
- recovery of a known reference uses `castclass`; and
- recovery of an open non-null `T` performs the Kotlin null check and then `unbox.any !n`/`!!n`.

The physical carrier is selected from the declaration-time Kotlin type and is not rewritten after
generic substitution. Thus `Holder<T?>` has one stable field and slot shape even when used as
`Holder<Int>` or `Holder<String>`.

An occurrence nested as an argument of a different invariant reified carrier, such as
`Array<T?>` or `ForeignBox<T?>`, is not made `Array<object>`/`ForeignBox<object>` silently. Such a
carrier would not accept the natural closed `Array<Int?>`/`ForeignBox<Int?>` representation at a
call site. Those nested shapes remain rejected until that carrier has a deliberate erased view or
generated adapter. Callable and split-interface carriers may erase it only where their accepted
ABI already defines an object-shaped execution view.

This is deliberate local erasure, not blanket generic erasure. Non-null `T` remains a reified CLR
generic parameter, and a declaration written with a concrete `Int?` remains
`System.Nullable<Int32>`. Kotlin metadata retains the logical `T?` type and all constraints.

### 2. Physical clashes are diagnosed after carrier mapping

Because `T?` and `Any?` both use `object`, overloads that differ only by those logical types are a
CLR platform-declaration clash. The compiler must diagnose the clash; it must not invent an
unstable name or select one overload by declaration order.

### 3. Covariant returns use exact methods plus explicit slot bridges

When a Kotlin override's mapped return type differs from the selected base or interface slot, the
backend emits:

1. the ordinary Kotlin implementation with its precise return type and a distinct virtual slot;
2. a compiler-owned final virtual bridge explicitly mapped to the wider CLR slot with
   `MethodImpl`; and
3. a bridge body that dispatches virtually through the precise Kotlin implementation and widens,
   boxes, or wraps only the returned value.

The bridge has no independent Kotlin body or logical declaration identity. It is private where CLR
metadata permits; if a cross-assembly compiler surface must be public, it is marked and hidden as
compiler ABI. Calls compiled against the base slot, the precise derived member, and every mapped
interface view must select the same Kotlin override.

A further-derived refinement emits the adapters not already satisfied by an inherited bridge
chain. For class inheritance, the new bridge targets the immediate wider physical class slot;
inherited bridges continue to route older slots virtually through that slot. This avoids
quadratically rebinding every ancestor while preserving dispatch. Inherited implementations
satisfying a newly introduced interface slot receive an explicit adapter because an abstract
interface owns no inherited forwarding body and CLR name-and-signature inference includes the
return type.

An abstract class refinement may own a concrete bridge which dispatches to its precise abstract
slot. A concrete subclass then implements that precise slot normally. An abstract interface
refinement instead remains a separate abstract CLR slot: portable interfaces cannot contain the
adapter body, so each body-owning class receives the adapters for all abstract slots it satisfies.
This distinction changes bridge placement only; it does not change Kotlin override selection or
copy a semantic body.

### 4. The bridge model is uniform across profiles

All three profiles use the floor-compatible explicit bridge representation. `net10.0` does not
switch ordinary Kotlin ABI to CLR covariant-return metadata. A future export-only C# facade may use
modern metadata, but that cannot alter Kotlin dispatch or the portable ABI.

## Ownership

- Common Kotlin frontend and metadata own nullable types and override selection.
- The .NET type mapper owns concrete `Nullable<V>` versus open boxed-or-null carriers.
- The .NET backend lowering owns physical covariant-return bridges and `MethodImpl` mappings.
- Code generation owns boundary boxing, null checks, unboxing, casts, and widening.
- The importer/exporter owns nullable annotations and any modern C#-only projection.

## Consequences

- `Map<K, V>.get(): V?` and comparable APIs have one cross-module-safe CLR signature.
- Closed uses of an open `T?` may box even where a hand-written closed declaration would not.
- Reflection over raw CLR metadata sees `object`; Kotlin reflection must reconstruct `T?` from
  Kotlin metadata.
- Covariant overrides cost a small forwarding method but work uniformly on Framework and modern
  CLR runtimes.
- C# sees the precise ordinary method while compiler bridges are hidden from normal completion.

## Implementation status

As of 2026-07-22, the core representation of both halves is implemented for Kotlin-owned
declarations.

- open nullable type parameters use the boxed-or-null carrier in fields, parameters, returns,
  locals, generic forwarding, and split-interface execution views. Method parameters bounded by
  `String` obey the same rule for `T?`, while non-null `T` retains its established `string` slot;
- concrete class, property, interface, inherited-interface, abstract-class, abstract-interface,
  generic-method, and multilevel covariant returns use exact slots plus private final bridges;
- bridge names are deterministic across the PSI and LightTree pipelines, while semantic identity
  is carried by each explicit `MethodImpl` row rather than by that private name; and
- a `netstandard2.0` producer is executed from separately compiled `net48` and `net10.0` Kotlin
  consumers, with direct Framework and modern C# consumers verifying precise public methods,
  private compiler bridges, and dispatch through base and interface views;
- a portable helper-owned default refined covariantly by a consumer interface uses a class-owned
  forwarder and return bridge on `net48`, but a single interface-owned DIM return bridge and no
  class forwarder on `net10.0`; a foreign modern C# implementation inherits that DIM naturally;
  and
- every metadata-relevant generated bridge is a structured physical KLIB record. A third Kotlin
  assembly consumes a producer-recorded interface bridge without duplicating it on the
  implementing class, while a third-party C# implementation inherits the same external DIM.

The required-evidence list below remains the ABI-freeze checklist. Closure capture for open `T?`
awaits general closure construction support, and importer/exporter projections remain separate
work; neither changes this Kotlin-owned physical representation.

## Required evidence

Before this ABI is frozen, tests must cover:

1. method- and class-level `T?` with primitive and reference substitutions;
2. null, boxing, `!!`, equality, fields, closures, and generic-to-generic forwarding;
3. physical clash detection for `T?` versus `Any?`;
4. separate-module producer/consumer linkage on all profile combinations allowed by the target;
5. class, property, interface, inherited-interface, and multi-level covariant returns;
6. dispatch through every base/interface view and direct precise calls;
7. direct IL assertions for `MethodImpl`, exact-return slots, and absence of copied bodies; and
8. C# compilation/reflection showing the intended public surface and hidden bridges.
