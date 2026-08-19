# Generic-owner value-subtype construction stability

## Result

Nested generic-owner carrier selection now detects a non-universal reference
argument whose Kotlin subtype set contains a CLR value carrier.

The hostile relation is:

```text
Int : Comparable<Int>
Producer<Int> -> Producer<Comparable<Int>>       Kotlin covariance
Producer<int> -/-> Producer<IComparable<int>>    CLR value-type variance
```

Therefore a logical `Box<Producer<Comparable<Int>>>` is physically
`Box<object>`. The object is still the original `Producer<int>`; no wrapper,
shadow state, or fictitious `Producer<IComparable<int>>` view is created.

The paired reference-only control is:

```text
Cat : Animal
Producer<Cat> -> Producer<Animal>                Kotlin and CLR covariance
Box<Producer<Animal>>                            remains exact
```

## General rule

For an admitted covariant owner argument, the emitter asks whether one of the
eight currently supported signed Common CLR value carriers is a proper Kotlin
subtype of the logical argument. The query uses the shared IR type checker and
is cached per logical argument type for the emission. A proper subtype makes
the construction unstable even when the target maps to a reference-shaped
generic instance. Exact value arguments are not proper subtypes of themselves,
and a reference-only hierarchy finds no value carrier, so both stay typed.

This is a representation rule derived from Kotlin subtyping and the CLR's
value-type variance restriction. It contains no checks for `Number`,
`Comparable`, the rehearsal declarations, collections, or source names.

## Evidence

Same-module and separate-KLIB products exercise both the unstable
`Comparable<Int>` case and the stable `Cat -> Animal` control. Kotlin reads,
identity, member dispatch, and C# reflection agree on the producer-selected
physical construction. PSI and LightTree execute on .NET 10 and .NET Framework
4.8. The production epoch-off inverse executes the same source with the
accepted erased ABI.

The fail-first C# oracle observed a false natural construction for the
Comparable factory while Kotlin-only execution still succeeded through its
semantic infrastructure. After the type-system predicate, the factory
publishes `Box<object>` and the reference-only factory still publishes
`Box<Producer<Animal>>`.

## Remaining boundary

This proof does not include Kotlin value classes, unsigned carriers, open
arguments, contravariant/mixed/multi-parameter owners, or arbitrary imported
CLR structs. The next gate is the dual contravariant construction and recursive
open-argument propagation. None of those gates may globally erase the open
outer owner or unrelated generic state.
