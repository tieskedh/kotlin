# Runtime reified MutableCollection (2026-08-23)

## Scope

ABI/runtime surface 54 selects the next complete Common dependency family:

```text
MutableCollection<T>
    : Collection<T>
    , MutableIterable<T>

    -> bool Add(T)
    -> bool Remove(T)
    -> bool AddAll<U>(Collection<U>) where U : T
    -> bool RemoveAll<U>(Collection<U>) where U : T
    -> bool RetainAll<U>(Collection<U>) where U : T
    -> void Clear()
```

The owner is invariant. Its element operations can therefore live on one
natural CLR construction. The bulk inputs are the first admitted nested input
whose legal Kotlin widening must also work when `U` is a CLR value type.

## Relative generic input

CLR interface covariance cannot convert `Collection<int>` to
`Collection<object>`. Erasing every `MutableCollection<T>` bulk input to
`object`, however, would discard the exact nested construction and spread an
operation-local Kotlin problem into the owner and its state. Surface 54 instead
uses a CLR-only method parameter:

```text
MutableCollection<object>.AddAll<int>(Collection<int>)
```

The physical constraint `U : T` represents the Kotlin subtype relationship.
It is present on both the Runtime interface MethodDef and compiler-generated
Kotlin MethodImpl. The Kotlin/KLIB declaration remains the ordinary
non-generic `addAll(Collection<T>)`; the extra parameter is a physical CLR slot
shape and is absent from Kotlin reflection identity.

The compiler rule is recorded structurally as one relative generic input
`G<U>` rather than as a `MutableCollection` or stdlib name switch. The selected
Runtime descriptor supplies the member and parameter index; mapping,
MethodImpl emission, call instantiation, and foreign fallback consume that
producer fact.

## State and semantic boundary

Kotlin implementations retain one object and one state. The hostile proof's
implementation is a real generic class with:

```text
RuntimeMutableCollectionValue<T>
    field !T value
```

Exact `Add(T)`/`Remove(T)` and the natural owner remain typed. A concrete bulk
input which the receiver's CLR construction cannot name enters the existing
object-domain semantic hook for that operation. It does not erase the owner,
field, or nested input object and introduces no wrapper or shadow state.

The normal natural call is a statically bound generic interface call. Only a
capability-free foreign object observed through an unnameable projected Kotlin
receiver uses the unique-construction runtime fallback. That fallback selects
one generic method definition by name, argument count, and generic arity,
closes it with the recorded `U`, and fails on ambiguity or violated
constraints. It remains subject to trimming, NativeAOT, performance, static
protocol, and tooling gates; it is not authority for unrestricted reflective
dispatch.

## C# contract

A sealed, non-partial C# class implements only
`MutableCollection<T>` and its natural parent interfaces. The C# compiler
statically requires `Add(T)`, `Remove(T)`, all three relative bulk methods, and
`Clear()`. No arity-zero semantic interface, exact sibling, partial class,
generator, wrapper, or adapter is needed.

The inherited `Collection<T>` candidate-input convention is unchanged by this
surface. A C# implementation still supplies `Contains(T)` and
`ContainsAll(Collection<T>)` for Kotlin broad calls under the earlier bounded
foreign protocol; making that inherited contract fully static remains a
production gate. Surface 54 closes the newly selected mutation slots rather
than claiming that this prior issue has disappeared.

## Verification

The hostile three-module Kotlin product runs under PSI and LightTree on .NET
Framework 4.8 and .NET 10. It covers exact element mutation, reference and
value-type bulk widening, input projection, remove/retain/clear, original
argument identity, receiver identity, and reflected `!T` state.

A warnings-as-errors C# consumer implements only the natural interfaces. It
reflects invariant owner variance, `Collection<U>` parameter shape, and the
`U : T` constraint on the Runtime interface and Kotlin MethodImpl. It executes
direct value widening and the foreign projected fallback without acquiring a
compiler-ABI interface.

The focused proof covers four green parser/runtime cases. The complete six-
family Runtime regression selection covers 24 green cases. The final target
aggregate exits zero. Direct XML audit covers 190 freshly written suites/2,311
tests with zero failures, errors, or skips: 187 FIR suites/2,183 tests, two
integration suites/127 tests, and the one-test backend resolver suite. The
unchanged green six-test `dotnet.ir` root makes the complete inventory 191
suites/2,317 tests.

## Boundary and next gate

Production interface mapping remains atomically erased outside the rehearsal.
`MutableList`, `MutableSet`, Map, defaults, diamonds, multiple owner/type
parameters, trimming, and NativeAOT remain outside surface 54. The next family
must reuse the relative-generic-input grammar or introduce another general
shape rule; it must not erase owner/state or add a declaration-name dispatch
exception merely to make the next Runtime interface pass.
