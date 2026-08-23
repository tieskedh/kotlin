# Runtime reified MutableListIterator (2026-08-23)

## Scope

ABI/runtime surface 53 selects the smallest complete Common family after the
mutable iterator foundation:

```text
MutableListIterator<T>
    : ListIterator<T>
    , MutableIterator<T>

    -> T Next()
    -> void Set(T)
    -> void Add(T)
```

The declaration is invariant and both parents already have natural CLR-generic
views. It therefore opens typed mutation inputs without selecting
`MutableCollection`, bulk mutation, mutable collection state, or another
Runtime dependency family.

## Physical and semantic result

The Runtime adds one invariant natural `MutableListIterator<T>` TypeDef beside
the accepted arity-zero semantic identity. The natural interface inherits
`ListIterator<T>` and `MutableIterator<T>` and owns its source-declared
`Next(): T`, `Set(T)`, `Add(T)`, `HasNext()`, and `Remove()` slots. It needs no
exact sibling: declaration invariance permits both input and output members on
the same honest CLR construction.

Kotlin implementations carry the natural and semantic views on one object.
Exact and open operations use the natural interface, and a concrete
implementation retains true generic state:

```text
RuntimeMutableListIteratorValue<T>
    field !T value
```

No wrapper, shadow state, or erased field is introduced.

## Projection boundary

A star read or an input-projected write is a Kotlin view which the invariant
CLR construction cannot itself name. That individual operation uses the
existing semantic capability. The receiver and state do not become globally
semantic:

```text
MutableListIterator<string>
    exact Next/Set/Add       -> natural MutableListIterator<string>
    star Next               -> operation-local semantic read
    MutableListIterator<in String>.Set/Add
                            -> operation-local semantic write
```

For an ordinary capability-free C# implementation, the cached foreign
dispatcher selects exactly one implemented natural construction and invokes
the matching member. Same-name/same-arity ambiguity still fails closed. This
is a rehearsal interop proof, not a decision to freeze unrestricted reflective
member discovery as production ABI: trimming, NativeAOT, static protocol
description, and tooling presentation remain explicit production gates.

## C# contract

Ordinary C# implements only the invariant natural interface:

```text
sealed class Cursor<T> : MutableListIterator<T>
{
    T Next();
    void Set(T value);
    void Add(T value);
    // inherited iterator operations
}
```

No exact or semantic compiler interface, partial class, generator, adapter, or
wrapper is required. Exact Kotlin calls are normal CLR interface calls. Star
and input-projected Kotlin calls retain the same C# object identity and enter
the foreign fallback only at the operation.

## Verification

The hostile separate-compilation product runs under PSI and LightTree on .NET
Framework 4.8 and .NET 10. Kotlin-owned reference and value constructions prove
exact and projected inputs, star reads and removal, identity, and reflected
`!T` storage. A warnings-as-errors C# consumer implements only the natural
interface and executes exact, star, reference-projected, and value-projected
paths. Existing stable-sorting and Kotlin-reflection regressions remain green.

The focused proof covers four new parser/runtime cases with zero failures,
errors, or skips. The stable-sorting and member-reflection regression selection
covers another 20 green cases.

The final full target aggregate exits zero. Direct XML audit covers 190 freshly
written suites/2,307 tests with zero failures, errors, or skips: 187 FIR suites/
2,179 tests, two integration suites/127 tests, and the one-test backend resolver
suite. The unchanged green six-test `dotnet.ir` root makes the complete target
inventory 191 suites/2,313 tests.

## Boundary and next gate

`MutableCollection`, `MutableList`, `MutableSet`, Map, defaults, broader
overload families, multiple owner parameters, trimming, and NativeAOT remain
outside this feature. The next Common dependency family must be recomputed from
surface 53. The natural typed CLR route remains the default; semantic routing
is justified per unnameable Kotlin operation rather than selected as the owner
representation.
