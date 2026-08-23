# Runtime reified MutableSet (2026-08-23)

## Scope

ABI/runtime surface 55 selects the complete Common `MutableSet<T>` family:

```text
MutableSet<T>
    : Set<T>
    , MutableCollection<T>

    -> MutableIterator<T> GetIterator()
    -> bool Add(T)
    -> bool Remove(T)
    -> bool AddAll<U>(Collection<U>) where U : T
    -> bool RemoveAll<U>(Collection<U>) where U : T
    -> bool RetainAll<U>(Collection<U>) where U : T
    -> void Clear()
```

The Common declaration is invariant and redeclares the mutable iterator and
complete mutation family. Both parents already have natural CLR-generic views,
so this is the first mutable collection diamond rather than a marker-only
interface addition.

## Natural diamond

The Runtime adds invariant natural `MutableSet<T>` above natural `Set<T>` and
`MutableCollection<T>`. Its source-declared slots are real newslot MethodDefs.
The bulk methods reuse surface 54's structural relative-generic-input grammar;
no MutableSet, stdlib, or declaration-name branch is added to the compiler.

A Kotlin implementation binds both physical paths:

```text
MutableSet<T>.AddAll<U>(Collection<U>)
MutableCollection<T>.AddAll<U>(Collection<U>)
                    where U : T
```

CLR interface-map validation proves both mappings on Framework 4.8 and .NET
10. They retain the same receiver and state. The compiler may use separate
private MethodImpl bridges for the distinct slots, but they forward to the one
authoritative Kotlin implementation family.

## State and semantic boundary

The hostile implementation is a true generic bounded set with one typed slot:

```text
RuntimeMutableSetValue<T>
    field !T value
```

Exact element mutation, natural iteration, and both natural bulk owner paths
remain CLR-typed. Reference/value bulk widening and projected inputs preserve
the original nested collection object. A CLR-unnameable operation alone may
enter the existing semantic capability; it does not erase the set owner or
field and creates no wrapper or shadow state.

`MutableSet<T>` does not solve the different covariant `Set<T>` candidate-input
problem by pretending it is CLR-representable. A Kotlin call through a widened
read-only Set view still uses the already recorded exact/semantic or bounded
foreign route. That inherited gate remains explicit rather than contaminating
the new invariant owner.

## C# contract

A sealed, non-partial C# class names only `MutableSet<T>`. Its natural parents
are acquired through the interface graph; no exact or semantic compiler
interface, generator, partial class, wrapper, or adapter is named or
implemented.

Roslyn warnings-as-errors statically requires the redeclared iterator and all
mutation MethodDefs. One ordinary C# generic bulk method satisfies both the
MutableSet and MutableCollection slots. CLR interface maps prove that the two
contracts target the same C# MethodDef. Kotlin executes direct, parent-diamond,
projected, read-only-parent, iterator, and identity routes on that object.

## Verification

The hostile three-module Kotlin product runs under PSI and LightTree on .NET
Framework 4.8 and .NET 10. It covers duplicate/exact mutation, reference and
value bulk widening, the MutableCollection diamond, projected mutation,
read-only Set candidate barriers, mutable iterator removal, original argument
identity, receiver identity, and reflected `!T` state.

The focused proof covers four green parser/runtime cases. The complete seven-
family Runtime regression selection covers 28 green cases. The strict target
aggregate completed successfully. Direct XML audit covers 190 freshly written
suites and 2,315 tests; the unchanged green model root brings the complete
target inventory to 191 suites and 2,321 tests, with zero failures, errors, or
skips.

## Boundary and next gate

Production interface mapping remains atomically erased outside the rehearsal.
`MutableList`, Map, defaults, deeper diamonds, multiple owner/type parameters,
trimming, and NativeAOT remain outside surface 55. The next selected family
must preserve the same rule: natural CLR inheritance and state are default;
semantic routing is admitted only for the concrete Kotlin operation which the
CLR graph cannot name.
