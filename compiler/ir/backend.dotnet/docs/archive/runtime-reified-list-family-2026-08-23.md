# Runtime reified List family (2026-08-23)

## Scope

ABI/runtime surface 51 extends the Runtime generic-owner rehearsal with the
complete read-only List dependency closure:

```text
ListIterator<out T> : Iterator<T>
    -> bool HasNext()
    -> T Next()
    -> bool HasPrevious()
    -> T Previous()
    -> int NextIndex()
    -> int PreviousIndex()

List<out T> : Collection<T>
    -> T Get(int)
    -> ListIterator<T> GetListIterator()
    -> ListIterator<T> GetListIterator(int)
    -> List<T> SubList(int, int)

List__KotlinExact<T> : List<T>, Collection__KotlinExact<T>
    -> bool Contains(T)
    -> bool ContainsAll(Collection<T>)
    -> int IndexOf(T)
    -> int LastIndexOf(T)
```

The accepted arity-zero interfaces remain declaration-semantic capabilities.
They neither replace the natural typed route nor become parents of ordinary C#
implementations.

## Physical and semantic result

Natural `List<T>` and `ListIterator<T>` are covariant because every member on
those interfaces is output-safe or independent of `T`. Candidate-consuming
members remain on the invariant exact sibling. Kotlin implementations carry
the selected natural, exact, and semantic views on one object. Their
producer-proven state stays physically typed:

```text
RuntimeListValue<T>
    field !T first
    field !T second

PairListIterator<T>
    field !T first
    field !T second
```

Exact Kotlin calls and CLR reference covariance use ordinary constructed
interfaces. Kotlin-only value-type widening crosses the semantic capability
only at an operation whose view the CLR cannot name. `get`, both
`listIterator` overloads, and `subList` preserve the selected natural
construction until a later use chooses its typed or semantic view. No wrapper,
shadow state, or second object identity is introduced.

`contains` retains its false barrier. `indexOf` and `lastIndexOf` use the same
general wrong-shape policy with `-1`, so an incompatible value is rejected
before a typed foreign method is invoked. `containsAll` reuses the Collection
element-wise fallback when its nested construction is incompatible, including
the required `true` result for an empty input.

## Ordinary C# implementations

A sealed, non-partial C# class implements only `List<T>` and its ordinary
members. Its input methods are normal public `Contains(T)`,
`ContainsAll(Collection<T>)`, `IndexOf(T)`, and `LastIndexOf(T)` methods. It
does not name an arity-zero interface, exact sibling, generated semantic
capability, partial-class hook, source generator, or adapter.

The runtime fallback remains structural rather than List-specific. It selects
one natural constructed interface, resolves a member by name and argument
count, rejects a same-name/same-arity ambiguity, and caches that decision by
runtime type and complete selection key. This admits both
`GetListIterator()` and `GetListIterator(int)` without a declaration-name
exception in the compiler.

The fixed candidate barrier now carries its authoritative boxed fallback value
instead of assuming Boolean `false`. That generalization supplies `-1` for
List index queries while retaining the previously proved Collection behavior.

## Runtime-owned List implementation

Runtime reflection exposes annotation collections through the hand-written
`ReflectionAnnotationList`. Surface 51 gives that same object natural
`List<object>`, exact `List__KotlinExact<object>`, and natural
`ListIterator<object>`/`Iterator<object>` MethodImpl bundles in addition to its
canonical semantic slots. Typed iterator and sub-list results therefore stay
inside the same Runtime graph instead of falling back to erased-only helper
objects.

## Verification

The hostile separate-compilation product executes under PSI and LightTree on
.NET Framework 4.8 and .NET 10. Kotlin-owned reference and value lists prove
exact and widened reads, candidate barriers, `containsAll`, both iterator
overloads, reverse/index queries, recursive sub-lists, identity, and reflected
`!T` fields. A warnings-as-errors C# consumer independently implements only
the natural interfaces and exercises the same member families, including
wrong-shape calls which must not enter typed C# methods.

The final full target aggregate exits zero. Direct XML audit covers 190 freshly
written suites/2,299 tests with zero failures, errors, or skips: 187 FIR suites/
2,171 tests, two integration suites/127 tests, and the one-test backend resolver
suite. The unchanged green six-test `dotnet.ir` root makes the complete target
inventory 191 suites/2,305 tests.

## Boundary and next gate

This slice does not migrate mutable collection interfaces, Map/Map.Entry,
defaults, broader overload families, multiple owner parameters, or concrete
Stdlib collection storage. Their current mappings remain authoritative. The
next dependency family must again be recomputed from the Common declaration
graph and admitted atomically. Natural CLR representation and typed state
remain the default; semantic routing requires evidence that the operation
cannot preserve Kotlin behavior through one CLR construction.
