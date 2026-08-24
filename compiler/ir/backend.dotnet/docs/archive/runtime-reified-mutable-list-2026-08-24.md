# Runtime reified MutableList (2026-08-24)

## Scope

ABI/runtime surface 56 selects the complete Common `MutableList<T>` family:

```text
MutableList<T>
    : List<T>
    , MutableCollection<T>

    -> bool Add(T)
    -> bool Remove(T)
    -> bool AddAll<U>(Collection<U>) where U : T
    -> bool AddAll<U>(int index, Collection<U>) where U : T
    -> bool RemoveAll<U>(Collection<U>) where U : T
    -> bool RetainAll<U>(Collection<U>) where U : T
    -> void Clear()
    -> T Set(int index, T element)
    -> void Add(int index, T element)
    -> T RemoveAt(int index)
    -> MutableListIterator<T> GetListIterator()
    -> MutableListIterator<T> GetListIterator(int index)
    -> MutableList<T> SubList(int fromIndex, int toIndex)
```

The owner is invariant. Its List, MutableCollection, MutableListIterator, and
recursive MutableList result dependencies already have natural CLR-generic
views, so the dependency closure is complete without admitting Map or another
unrelated family.

## Structural member grammar

Surface 54 initially recorded a relative nested input by a fixed parameter
index. MutableList proves the general rule instead: a selected method has
exactly one non-null covariant single-parameter construction over the owner's
one invariant parameter. Declaration-independent parameters may surround it.
Consequently both bulk overloads use the same physical rule even though their
collection inputs occupy different positions:

```text
AddAll<U>(Collection<U>)
AddAll<U>(int index, Collection<U>)
                         where U : T
```

The compiler derives the position from the Common IR shape and fails closed
unless it finds exactly one candidate. The bridge copies every independent
argument unchanged and substitutes only that nested input. There is no
MutableList, package, or declaration-name switch in bridge planning or call
codegen.

The bounded natural-only foreign grammar is generalized by the same principle.
Exactly one direct owner-dependent input may occur beside independently
representable parameters. Its result may be Unit, a declaration-independent
value, or the same owner parameter. This covers `Add(T)`, `Add(int,T)`, and
`Set(int,T):T`. Name plus complete argument count distinguishes overloads;
same-name/same-arity ambiguity still fails closed.

## State and inherited semantic boundary

The hostile Kotlin implementation retains one object and one true generic
state slot:

```text
RuntimeMutableListValue<T>
    field !T value
```

Exact and projected element mutation, indexed mutation, removal, natural
iterators, and full live sublists all observe that state. Reference/value bulk
widening preserves the original collection object. A projected or otherwise
CLR-unnameable call alone may cross the semantic capability; it does not erase
the list owner, its field, or any exact nested construction, and it creates no
wrapper or shadow state.

The child does not copy the covariant List parent's exact/candidate slots.
Calls through a widened read-only List view retain the earlier fixed false/-1
barriers and exact/semantic protocol. Invariance of MutableList does not make a
CLR-illegal covariant parent input legal.

## C# contract

A sealed, non-partial C# class implements only natural `MutableList<T>` and its
natural parents. Roslyn warnings-as-errors statically requires every direct
mutation, iterator, and sublist member. The class names no exact or semantic
compiler interface and needs no generator, partial declaration, wrapper, or
adapter.

The C# consumer is compiled separately from both Kotlin modules. Kotlin calls
that object through exact and `MutableList<in T>` views. Projected value-type
`Add`, indexed `Add`, and `Set` reach the ordinary typed C# methods, including
the old-value result from `Set`; both relative AddAll overloads retain their
original nested object. CLR interface maps validate the Kotlin MethodImpls for
both MutableList and MutableCollection bulk slots.

## Runtime epoch correction

The provisional ABI and Runtime constants had remained at 53 while surfaces
54 and 55 added Runtime TypeDefs. Surface 56 advances both constants to 56.
This is not cosmetic: a library which can name MutableCollection, MutableSet,
or MutableList must not be paired with a Runtime whose declared surface lacks
those TypeDefs. The existing installed-platform tests reject missing,
duplicate, malformed, stale, and future surface metadata; the C# probe also
reads the actual assembly metadata and requires level 56 or newer.

## Verification

The hostile three-module Kotlin product and separately compiled C# consumer
run under PSI and LightTree on .NET Framework 4.8 and .NET 10. They cover exact
and projected direct mutation, both bulk positions, MutableCollection diamond
dispatch, `Set` input/result composition, removal, mutable iterators, live
sublist identity, inherited read-only barriers, original nested-argument
identity, receiver identity, and reflected `!T` state.

The four focused parser/runtime cases pass after a forced rebuild. The strict
target aggregate exits zero. Direct XML audit covers 190 freshly written
suites and 2,319 tests: 187 FIR suites/2,191 tests, two integration suites/127
tests, and the one-test backend resolver suite. The unchanged green six-test
`dotnet.ir` root brings the complete target inventory to 191 suites/2,325
tests, with zero failures, errors, or skips.

## Boundary and next gate

Production interface mapping remains atomically erased outside the rehearsal.
Map, defaults, multiple owner parameters, trimming, NativeAOT, static foreign
protocol, public tooling presentation, and inverse rollback remain separate
gates. The next family must be recomputed from the Common dependency/member
graph; it must not acquire a declaration-specific representation exception.
