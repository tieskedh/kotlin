# Draft ADR: Erased Kotlin iterator ABI on CLR

- Status: **Draft candidate; implemented for array and user iterators in the prototype**
- Date: 2026-07-16
- Scope: Kotlin-to-Kotlin iterator identity and execution across CLR assembly boundaries

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin `Iterator<out T>` is covariant for every logical element type. A value can therefore move
from `Iterator<Int>` to `Iterator<Any>` without an adapter, and Kotlin reference identity must
still observe the same iterator object and state.

The CLR does not provide that model directly. `IEnumerator<T>` has different execution semantics
(`MoveNext` plus `Current`) and CLR generic variance conversions apply only to reference-type
arguments. In particular, `IEnumerator<int>` cannot be converted to `IEnumerator<object>`. A
wrapper would make the conversion work but would change observable identity and split one Kotlin
rule into multiple physical representations.

## Decision drivers

The candidate must:

1. preserve identity and state across every legal Kotlin iterator covariance conversion;
2. keep `hasNext`/`next` and `NoSuchElementException` semantics rather than importing the
   `IEnumerator` state machine and exception contract;
3. have one stable Kotlin-owned physical identity across modules and runtime versions;
4. support primitive, reference, nullable-reference, and open generic consumers; and
5. run on both .NET Framework 4.8 and modern CoreCLR.

## Considered alternatives

### `IEnumerator<T>` as the Kotlin ABI

This is attractive for direct CLR interop, but it is not semantically or representationally
faithful. Its API does not match Kotlin Iterator, its variance cannot convert value-type
instantiations, and adapters would break identity. CLR enumeration belongs in an explicit interop
layer rather than Kotlin-to-Kotlin signatures.

### Generic `Kotlin.Collections.Iterator<out T>`

A Kotlin-owned generic interface could expose Kotlin method names, but CLR variance would still
exclude the `Iterator<Int>` to `Iterator<Any>` case. Making only reference-element iterators use
that representation would leave Kotlin with two physical identity rules.

### Non-generic Kotlin-owned interface with erased execution

This is the selected candidate. Kotlin's logical element type remains compiler and metadata
information, while every supported iterator view shares one CLR interface and one object.

## Candidate decision

The runtime exposes this Kotlin-owned execution identity:

```text
Kotlin.Collections.Iterator {
    bool HasNext()
    object Next()
}
```

Source `kotlin.collections.Iterator<T>` and the supported primitive iterator classes map to that
same non-generic interface. `Next()` is the universal erased slot. The compiler casts reference
results and uses `unbox.any` for primitives, nullable primitives, and open method/class type
parameters. A covariance conversion changes only the logical Kotlin view; it emits no wrapper and
preserves the same object and cursor state.

The first producer is the internal runtime class
`Kotlin.Runtime.Internal.ArrayIterator`. It stores one `System.Array` plus an index. `GetValue`
boxes primitive vector elements and returns reference elements unchanged. Exhaustion throws exact
`Kotlin.NoSuchElementException : Kotlin.RuntimeException`; using
`System.InvalidOperationException` would introduce a false Kotlin IllegalStateException edge.

That handwritten class is bootstrap packaging, not a permanent ownership decision. This POC has
no real .NET stdlib module in which an ordinary Kotlin `ArrayIterator<T>` implementation can be
compiled. Once that target stdlib exists and its classes use the bridge policy below, the logical
array-iterator implementation should move there. The runtime assembly should retain the erased
interface identity and only narrowly unavoidable low-level helpers. Whether primitive arrays use
specialized stdlib iterator classes or the current shared `System.Array` implementation is a
separate performance decision.

Direct `for (element in array)` loops do not use this object. The existing common indexed-loop
lowering evaluates the array once and remains allocation-free. The iterator ABI is used only when
an explicit `iterator()` call produces a value that can escape or be stored.

The current bounded implementation supports the five established primitive vectors, concrete
reference-element arrays, open invariant `Array<T>` producers, and ordinary user classes whose
contract reaches `Iterator<T>` directly or through a supported subinterface. An open vector stays
exact `!n[]`/`!!n[]` and passes directly to the shared `ArrayIterator(System.Array)`; erased
`Next()` is narrowed with `unbox.any !n`/`!!n`.
This does not admit `Array<T?>`, projections, concrete primitive-element generic arrays, or nested
arrays: their receiver types remain rejected by the structural array mapper before iterator
lowering runs.

A user implementation retains its logically typed Kotlin members, including `next(): T`. The
backend adds two private explicit CLR interface implementations on the same object: `HasNext()`
forwards to `hasNext()`, while erased `object Next()` forwards to `next()` and boxes its result at
that boundary. This follows the JVM's typed-member-plus-erased-bridge semantic pattern; the extra
`HasNext` forwarder is required only because the Kotlin-owned CLR interface uses CLR-style slot
names. A base class which directly declares `Iterator<T>` owns the bridges when it has class-owned
typed members, and derived classes inherit them. An abstract base with only the interface
obligation defers bridge ownership to the first concrete descendant. No adapter object, generic
runtime interface, or public typed capability is added.

A bodyless module-local iterator subinterface inherits the same non-generic runtime identity. It
may declare unrelated abstract members, but it does not republish typed `next` or `hasNext` slots.
Calls to inherited fake overrides are emitted against the erased runtime slots, and an implementing
class receives the same private bridges as a direct implementer. A source redeclaration such as
`override fun next(): T` is rejected because emitting it on the CLR interface would create a second
typed execution contract beside the canonical erased one. Interface member bodies remain rejected
at the .NET Framework 4.8 compatibility floor; this design does not depend on default interface
methods.

The subinterface's own generic identity remains subject to the backend's existing CLR generic
interface rules. Declaration-site covariance works for reference-shaped arguments, but a widening
such as `IteratorView<Int>` to `IteratorView<Any>` remains rejected because CLR variance does not
apply to value-type instantiations. Widening that object to the canonical `Iterator<Any>` view is
safe and remains adapter-free because the base identity is erased. This limitation must not be
mistaken for a second iterator representation.

## Consequences

Benefits:

- primitive and reference iterator covariance preserves `===` and shared cursor state;
- Kotlin iteration semantics stay independent from `IEnumerator` rules;
- a generic consumer can return `T` through `unbox.any !!n`; and
- CLR enumeration adapters can be added without changing Kotlin iterator identity.

Costs:

- primitive elements box at the erased `Next()` boundary and unbox at the Kotlin call site;
- each bridge-owning user implementer carries two small private MethodImpl bridges;
- the raw interface is not an idiomatic CLR enumeration surface; and
- logical element types require Kotlin metadata for future cross-module Kotlin compilation.

## Validation

Probe series `iteratorabi_s1` assembled the runtime, a generic consumer, primitive/reference array
producers, and an exact exhaustion catch with modern 10.0.9 and .NET Framework 4.8 ILAsm. All four
same-assembler and cross-runtime pairings produced the same results.

Repository pins cover both FIR parsers and real CoreCLR execution, including reference,
primitive, open-generic producer/consumer, covariant, inherited, and subinterface user
implementations:

- `compiler/testData/codegen/dotnet/ilText/arrayIterators.kt`;
- `compiler/testData/codegen/dotnet/box/arrayIterators.kt`; and
- the remaining iterator-family negatives in `genericArraysRejected.kt`.

## Deferred decisions

This draft does not decide the target-stdlib packaging migration, separately compiled Kotlin
producer modules, iterator subinterfaces with typed redeclarations or member bodies,
primitive-specialized iterator subclasses, collection/list iterators, mutable iterators,
sequences, `Iterable<T>`, CLR `IEnumerable<T>`/`IEnumerator<T>` adapters, typed fast-path members,
or Kotlin metadata encoding. Those layers may add views or optimizations, but ordinary Kotlin
iterator covariance must continue to preserve the erased canonical identity or this draft must
be explicitly revised.
