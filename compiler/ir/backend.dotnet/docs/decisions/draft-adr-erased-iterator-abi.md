# Draft ADR: Split Kotlin iteration ABI on CLR

- Status: **Draft candidate; canonical and typed views implemented for Iterator/Iterable**
- Date: 2026-07-17
- Scope: Kotlin-to-Kotlin iteration identity, execution, and the typed CLR view

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin `Iterator<out T>` and `Iterable<out T>` are covariant for every logical element type. In
particular, `Iterator<Int>` and `Iterable<Int>` can be viewed as their `Any` forms without an
adapter, and `===` must continue to observe the original object and state.

The CLR cannot express that rule with one constructed generic identity. Variance conversions of
`Iterator<int>` or `IEnumerable<int>` do not produce the corresponding `<object>` construction.
`IEnumerator<T>` is also the wrong semantic contract: it has `MoveNext`/`Current` state and
exception rules rather than Kotlin's `hasNext`/`next` contract. An implicit wrapper would repair
some calls but would break Kotlin identity.

The mature targets establish the semantic direction:

- common stdlib owns the logical `expect` declarations;
- Native and Wasm use Kotlin-owned `actual` iteration interfaces;
- JVM and JS treat host-array `iterator()` production as a compiler/runtime-helper boundary; and
- no target makes a host enumeration interface the canonical Kotlin identity.

The CLR-specific distinction is that an optional constructed generic interface is useful for
unboxed execution and C# consumption, but it cannot carry Kotlin identity across value/reference
variance. That distinction is the reason for a split representation rather than a direct copy of
one mature target.

## Decision drivers

The representation must:

1. preserve identity and cursor state across every legal Kotlin covariance conversion;
2. retain Kotlin iteration and `NoSuchElementException` semantics;
3. give every Kotlin module a stable, versioned physical identity and fallback slot;
4. permit unboxed primitive execution when the same object exposes an exact closed capability;
5. provide a useful typed interface to C# without making it the universal Kotlin ABI; and
6. remain valid on .NET Framework 4.8 and modern CoreCLR.

## Candidate decision

`Iterator` and `Iterable` are runtime-owned instances of the general Kotlin generic-interface
ABI. Each logical declaration has a non-generic canonical identity and a covariant typed sibling:

```text
Kotlin.Collections.Iterator {
    bool HasNext()
    object Next()
}

Kotlin.Collections.Iterator<out T> : Kotlin.Collections.Iterator {
    bool HasNext()
    T Next()
}

Kotlin.Collections.Iterable {
    Kotlin.Collections.Iterator GetIterator()
}

Kotlin.Collections.Iterable<out T> : Kotlin.Collections.Iterable {
    Kotlin.Collections.Iterator GetIterator()
}
```

The second `GetIterator` deliberately returns the canonical iterator identity. A typed outer
capability may be supplied by an erased-only provider, so the ABI does not promise that an
arbitrary returned object also exposes `Iterator<T>`. A later C# convenience API may probe and
return that nested capability explicitly; it must not make an unsound promise in the base
interface.

All ordinary Kotlin storage, parameters, returns, casts, projections, stars, and variance
conversions use the canonical non-generic identity. The logical `T` remains in Kotlin IR and,
eventually, Kotlin metadata. Consequently this is an instruction-free reference copy:

```kotlin
val ints: Iterator<Int> = producer
val anys: Iterator<Any> = ints
check(ints === anys)
```

The typed sibling is an optional execution and interop capability on that same object. A call
through an exact logical receiver probes `Iterator<T>` or `Iterable<T>`, invokes the typed slot
when present, and otherwise invokes the canonical slot. A primitive `Iterator<Int>.next()` can
therefore return `int32` without boxing on the typed path. A widened or erased-only provider still
works through `object Next()` and narrows or unboxes at the call site. Failure of a typed probe is
not a failed Kotlin cast and never authorizes an adapter.

Every Kotlin-compiled implementation exposes both views on the same object. The general
`DotNetGenericInterfaceBridgeLowering` generates explicit forwarding MethodImpls for the
canonical and declared slots. It handles stdlib classes and user classes identically:

```text
class CountingIterator :
    Kotlin.Collections.Iterator,
    Kotlin.Collections.Iterator<int>
{
    int next()                    // source implementation
    object <canonical bridge>()  // boxes only here
    int <declared bridge>()       // unboxed
}
```

The stable canonical member names remain `HasNext`, `Next`, and `GetIterator`; this preserves the
existing POC runtime ABI. The typed sibling uses the same CLR-friendly names. General user-owned
interfaces retain the hashed canonical member-name scheme recorded in the generic-interface ADR.
The collection bridge table, collection-specific lowering origins, special MethodImpl emitter,
and generic Iterator/Iterable call intrinsics have been removed. Primitive iterator aliases keep
a small temporary intrinsic boundary until `IntIterator`, `LongIterator`, and the other ordinary
stdlib classes are produced.

A base class that already owns the complete bridge set supplies it to descendants. Its bridge
forwards through the virtual source implementation, so an override still dispatches correctly.
An abstract obligation-only base has no bridge to inherit; the first concrete implementation owns
the bridges. The lowering orders classes base-first and generates a bridge set only where one is
not already inherited.

Generic subinterfaces use the same split machinery. A declaration such as
`IteratorView<out T> : Iterator<T>` has its own canonical identity and covariant typed sibling;
the typed sibling extends `Iterator<T>`, while the canonical sibling extends canonical `Iterator`.
Redeclarations are no longer rejected merely for being collection members. Their physical slots
and implementing bridges follow the same rules as every other Kotlin-owned generic interface.
Interface bodies remain outside the .NET Framework 4.8-compatible slice.

## Target stdlib placement

`ArrayIterator<T>` and `ArrayIterable<T>` are ordinary Kotlin implementations emitted into
`Kotlin.Stdlib.dll`. They are not compiler-privileged bridge implementations. `ArrayIterator<T>`
stores the exact CLR vector and cursor, observes later mutations, and throws Kotlin
`NoSuchElementException` when exhausted. `ArrayIterable<T>` stores the vector and constructs a
fresh iterator for each request. Both now receive the same general canonical and typed bridges as
user classes.

Direct `for (element in array)` remains an allocation-free indexed lowering. An escaping
`array.iterator()` calls a Kotlin-internal, metadata-public generic stdlib factory through a
compiler intrinsic. The implementation class and constructor remain private, matching the JVM/JS
compiler-helper boundary for host arrays. Array `asIterable()` uses the corresponding factory, and
the first common operations, `Iterable<T>.first()` and `last()`, remain in the bootstrap stdlib
source until the explicit stdlib product consumes the checked-in common source graph.

The mature product is one self-describing `Kotlin.Stdlib.dll`, built once from the
common/common-non-JVM and .NET-specific source sets against the selected target profile. The
current implementation already lives in ordinary `libraries/stdlib/dotnet/src` files;
`DOTNET_STDLIB_SOURCES` is only the backend-resource view of those same files for temporary
same-run bootstrap compatibility. It is not the final generated source graph. No new
stdlib-generator target is needed merely for `first`/`last`; those bodies already belong to the
common generated corpus. A future builtins generator entry may supply the bodyless .NET array
actuals.

## C# and foreign CLR boundaries

C# can explicitly ask whether a Kotlin iterator implements
`Kotlin.Collections.Iterator<int>` and call `Next()` without boxing. It may also use the canonical
`Kotlin.Collections.Iterator` interface for a universal object-shaped fallback. This is an
honest capability boundary: the CLR generic sibling is never presented as though
`Iterator<int>` were assignable to `Iterator<object>`.

`IEnumerable<T>` and `IEnumerator<T>` remain foreign CLR contracts. Imported implementations keep
their actual CLR restrictions. Kotlin/.NET will require explicit adapters or export helpers to
cross that boundary; it will not redefine `===`, silently wrap a foreign object during ordinary
subtyping, or make `Any` physically distinct from `System.Object` to fake a generic conversion.

## Consequences

Benefits:

- primitive and reference covariance preserves `===` and shared state;
- exact primitive calls can avoid boxing while erased providers remain universally valid;
- C# gets a real typed capability rather than an object-only API;
- stdlib and user implementations use one bridge algorithm; and
- BCL adapters can evolve without changing Kotlin identity.

Costs:

- an exact-capable iterator implementation carries canonical and declared MethodImpl bridges;
- erased fallback calls still box primitive results;
- `Iterable<T>.GetIterator()` cannot universally promise a nested typed capability; and
- logical type arguments require Kotlin metadata for complete separate-module reconstruction.

## Validation

Repository pins cover both FIR parsers and real CoreCLR execution for primitive, reference,
nullable-reference, open-generic, covariant, inherited, abstract-deferred, subinterface, array,
and user-defined implementations:

- `compiler/testData/codegen/dotnet/ilText/arrayIterators.kt`;
- `compiler/testData/codegen/dotnet/box/arrayIterators.kt`;
- `compiler/testData/codegen/dotnet/ilText/iterables.kt`;
- `compiler/testData/codegen/dotnet/box/iterables.kt`; and
- the iterator-family negatives in `genericArraysRejected.kt`.

The IL pins require canonical and closed declared interfaces on the same objects, both MethodImpl
families, the guarded typed call and erased fallback, and typed primitive/open-generic returns.
The box harness inspects retained `Kotlin.Stdlib.il`, assembles the new runtime interface metadata,
and executes the result on CoreCLR. The cross-library integration suite separately validates the
self-describing stdlib DLL and consumers.

## Deferred decisions

This draft does not decide the final common-source build wiring, primitive-specialized iterator
class production, collection/list/mutable-iterator/sequence implementations, a nested typed
iterator convenience API for C#, `IEnumerable<T>`/`IEnumerator<T>` adapters, or the final Kotlin
metadata encoding. These layers may add capabilities and optimizations, but must keep canonical
Kotlin iteration identity adapter-free.
