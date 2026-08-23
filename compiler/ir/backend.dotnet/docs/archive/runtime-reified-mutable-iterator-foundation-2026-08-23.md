# Runtime reified mutable iterator foundation (2026-08-23)

## Scope

ABI/runtime surface 52 selects the smallest complete dependency family after
the read-only List closure:

```text
MutableIterator<out T> : Iterator<T>
    -> void Remove()

MutableIterable<out T> : Iterable<T>
    -> MutableIterator<T> GetIterator()
```

Both Common declarations are covariant. `remove()` consumes no `T`, and the
only owner-dependent `MutableIterable` shape is its nested mutable iterator
result. The family therefore closes over the existing natural
`Iterator<T>`/`Iterable<T>` foundation without selecting `MutableCollection`,
`MutableListIterator`, mutation inputs, or mutable collection storage.

## Physical and semantic result

The Runtime adds covariant natural `MutableIterator<T>` and
`MutableIterable<T>` TypeDefs beside their accepted arity-zero semantic
identities. Kotlin implementations carry both views on one object and retain
producer-proven element state as real generic storage:

```text
RuntimeMutableIteratorValue<T>
    field !T value

RuntimeMutableIterableValue<T>
    field !T value
```

Exact calls and CLR reference covariance remain typed. Kotlin-only value-type
widening crosses the semantic capability only for `next`, `remove`, or the
nested iterator result which cannot be named by that widened CLR construction.
No wrapper, shadow state, or identity change is introduced.

The foreign dispatcher now admits a natural member returning `Unit` whenever
all of its inputs are declaration-independent. This is the same structural
rule already used by value-result members; it contains no mutable-interface or
method-name switch. `MutableIterator.remove()` is its first Runtime proof.

## Covariant-return composition

`MutableIterable<T>.GetIterator()` narrows the inherited
`Iterable<T>.GetIterator()` result from `Iterator<T>` to
`MutableIterator<T>`. Those are two ordinary CLR interface slots. Kotlin
implementations emit both MethodImpls. A C# class implements the narrowed
public member plus one explicit implementation of the natural base
`Iterable<T>` slot:

```text
public MutableIterator<T> GetIterator()
Iterator<T> Iterable<T>.GetIterator() => GetIterator()
```

This duplicate is imposed by the faithful CLR interface graph and C#'s return-
type implementation rules, not by a Kotlin semantic capability. The class
names only natural public Runtime interfaces. Omitting the base slot would
either lose CLR `MutableIterable<T> : Iterable<T>` subtyping or permit an
implementation to return a non-mutable iterator, weakening the Kotlin
contract. A .NET-10-only default interface body cannot define the portable
Framework 4.8 ABI.

## Verification

The hostile separate-compilation product executes under PSI and LightTree on
.NET Framework 4.8 and .NET 10. Kotlin-owned reference and value constructions
prove exact and widened `next`, `remove`, nested mutable iterator results,
identity, and reflected `!T` fields. A warnings-as-errors C# consumer
implements only the two natural interfaces, verifies CLR covariance and the
generic parent graph, executes reference/value paths, and confirms the base
natural return bridge preserves mutability.

The final full target aggregate exits zero. Direct XML audit covers 190 freshly
written suites/2,303 tests with zero failures, errors, or skips: 187 FIR suites/
2,175 tests, two integration suites/127 tests, and the one-test backend resolver
suite. The unchanged green six-test `dotnet.ir` root makes the complete target
inventory 191 suites/2,309 tests.

## Boundary and next gate

`MutableListIterator`, `MutableCollection`, `MutableList`, `MutableSet`, Map,
defaults, broader overload families, and multiple owner parameters remain on
their current mappings. The next dependency family must be recomputed from the
Common graph. Input-bearing mutable owners are a materially harder boundary:
their normal exact CLR construction should remain typed, while projections and
Kotlin-only broad views may require narrowly isolated semantic transitions.
