# Profile-specialized Common generic array fill

- Status: **Accepted pre-ABI**
- Date: 2026-08-15
- Scope: Common `Array<T>.fill` on Kotlin generic arrays
- Does not change: Kotlin array identity, generic-owner ABI/state, KLIB,
  Runtime surface level, or the public physical signature

## Context

Common defines the evaluation order, range validation, mutation, and exception
categories of generic array fill. The first .NET implementation preserved
those semantics by converting every array to `System.Array`, every element to
`object`, and invoking a Runtime loop which called `SetValue` for each slot.
That route is necessary for a genuinely erased array capability, but discards
the exact CLR vector and element token already present for ordinary
`Array<E>` and method-generic `Array<T>`.

The supported target profiles do not share one optimal BCL surface. .NET
Framework 4.8 and netstandard 2.0 cannot reference modern generic
`System.Array.Fill<T>`. On .NET 10, measured value and open-generic routes
benefit materially from that generic BCL operation, while a statically known
reference vector is better served by its simple typed store loop. The
cross-profile evidence is archived separately; it is not a Kotlin semantic
input.

## Decision

Receiver, element, `fromIndex`, and `toIndex` are each evaluated once and
spilled in Kotlin source order. Before any write, codegen applies Common's
`checkRangeIndexes` precedence:

1. `fromIndex < 0` or `toIndex > size` throws the mapped
   `IndexOutOfBoundsException` category;
2. otherwise `fromIndex > toIndex` throws the mapped
   `IllegalArgumentException` category; and
3. an empty valid range performs no writes.

The physical write route is then selected from evidence the type mapper
already owns:

| Physical receiver | Profile/element evidence | Write route |
| --- | --- | --- |
| exact `E[]` | Framework 4.8 or netstandard 2.0 | typed `stelem E` loop |
| exact `R[]`, `R` statically reference-shaped | every profile | typed `stelem R` loop |
| exact `V[]`, `Nullable<V>[]`, or open `T[]` | .NET 10 | generic `System.Array.Fill<E>` |
| erased `System.Array` capability | every profile | Runtime `ArrayFill(System.Array, object, ...)` |

An open CLR parameter is deliberately not guessed to be reference-shaped. It
may be substituted by a value, nullable value, or reference, so .NET 10 uses
the BCL generic operation for that one declaration-stable route. Framework
and netstandard emit the same valid open `stelem !T`/`stelem !!T` loop.

An exact path evaluates the element in its real physical element type. It
does not box a scalar or nullable value and does not cross `System.Array`
reflection. A known reference also remains typed even though conversion to
`object` would be allocation-free, because virtual `SetValue` per slot is not
the best shared-runtime route.

## Erased generic-owner boundary

An ordinary Kotlin generic class remains one canonical non-generic CLR owner.
Its owner-dependent `Array<T>` field is authoritative `System.Array` state,
and a member which fills that field stays on the Runtime fallback. This
decision does not introduce a reified owner, duplicate typed storage, cache,
adapter, or delayed synchronization merely to reach the faster path.

Method generics are different: `fun <T> fill(values: Array<T>, value: T)` has
a truthful CLR `T[]` parameter and may use the exact route directly. This is
ordinary use of an existing reified method token, not a generic-class-owner
policy change.

## ABI and profile consequences

The optimization changes only private method bodies. KLIB remains the logical
authority; no declaration, metadata schema, Runtime method, or exported C#
surface changes. The existing Runtime helper remains required for erased
capabilities and keeps its surface-37 signature.

The .NET 10 MemberRef is emitted only when the selected core-library profile
admits it. A netstandard library must never acquire the newer call and remains
portable through typed IL. Framework execution is independent evidence; a
CoreCLR optimization cannot stand in for its result.

## Verification obligations

Completion requires:

- value, nullable-value, null, reference, and open generic substitutions;
- exact receiver/argument/bound evaluation order;
- empty, partial, and full ranges plus both exception categories;
- an erased generic-owner sentinel which remains on Runtime `ArrayFill`;
- Framework IL proving typed element stores without boxing or `SetValue`, plus
  portable netstandard product/consumer execution;
- .NET 10 assembly and execution of closed and open generic BCL MemberRefs;
- checksum-identical paired measurements on Framework 4.8 and .NET 10; and
- the complete strict target gate through both FIR frontends.

## Rejected alternatives

Always using the erased Runtime helper is rejected because exact vectors lose
their existing physical type and pay avoidable boxing/reflective dispatch.

Always using `System.Array.Fill<T>` is rejected because the API is unavailable
on the Framework/netstandard floor and because a known reference loop is a
better measured route.

Always emitting a handwritten loop is valid but leaves the much stronger
.NET 10 value/open-generic BCL implementation unused.

Reifying or shadowing canonical generic-owner array state solely for fill is
rejected by the generic-owner identity/state decision.
