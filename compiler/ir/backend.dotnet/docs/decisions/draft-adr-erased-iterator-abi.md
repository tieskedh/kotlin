# Draft ADR: Erased Kotlin iteration ABI on CLR

- Status: **Draft candidate; implemented for array/user iterators, user iterables, and the first target-stdlib producer**
- Date: 2026-07-16
- Scope: Kotlin-to-Kotlin `Iterator`/`Iterable` identity and execution across CLR assembly boundaries

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin `Iterator<out T>` is covariant for every logical element type. A value can therefore move
from `Iterator<Int>` to `Iterator<Any>` without an adapter, and Kotlin reference identity must
still observe the same iterator object and state.

`Iterable<out T>` has the same representational requirement one level earlier. An
`Iterable<Int>` must widen to `Iterable<Any>` without replacing the producer object, and its
`iterator()` result must enter the canonical iterator ABI rather than a second generic CLR slot.

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

and the corresponding Kotlin-owned producer identity:

```text
Kotlin.Collections.Iterable {
    Kotlin.Collections.Iterator GetIterator()
}
```

Source `kotlin.collections.Iterator<T>` and the supported primitive iterator classes map to that
same non-generic interface. `Next()` is the universal erased slot. The compiler casts reference
results and uses `unbox.any` for primitives, nullable primitives, and open method/class type
parameters. A covariance conversion changes only the logical Kotlin view; it emits no wrapper and
preserves the same object and cursor state.

Source `kotlin.collections.Iterable<T>` maps to the non-generic producer interface. An ordinary
implementation keeps its source-visible `iterator(): Iterator<T>` member, while the compiler adds
a private explicit `GetIterator()` MethodImpl forwarding to it. Calls through `Iterable<T>` or an
inherited fake override use `GetIterator()` and receive the same erased iterator object. A real
`for` loop over a user-defined `Iterable<T>` therefore follows the ordinary Kotlin lowering:
`GetIterator()`, erased `HasNext()`, and erased `Next()` with result narrowing at the use site.

The first producer is now an ordinary generic Kotlin implementation emitted into
`Kotlin.Stdlib.dll` as `Kotlin.Collections.ArrayIterator<T>`. It stores an exact `T[]` vector plus
an index. Primitive elements stay typed inside the implementation and box only in its generated
erased `Next()` bridge; reference elements pass through that bridge unchanged. Exhaustion throws
exact `Kotlin.NoSuchElementException : Kotlin.RuntimeException`; using
`System.InvalidOperationException` would introduce a false Kotlin IllegalStateException edge.

`Kotlin.Runtime.dll` retains only the erased Iterator/Iterable identities and exact exception
types. The former handwritten `Kotlin.Runtime.Internal.ArrayIterator(System.Array)` has been
removed. The stdlib source is compiled through the same bridge policy as user code; its class is
not a compiler-special implementation. Because the POC cannot yet import Kotlin metadata from a
separately compiled module, source is currently injected into the same frontend/lowering run and
scoped emission places the implementation only in `Kotlin.Stdlib.dll`. This bootstrap packaging is
recorded separately in `draft-adr-target-stdlib-bootstrap.md`.

Direct `for (element in array)` loops do not use this object. The existing common indexed-loop
lowering evaluates the array once and remains allocation-free. The iterator ABI is used only when
an explicit `iterator()` call produces a value that can escape or be stored.

The current bounded implementation supports the five established primitive vectors, concrete
reference-element arrays, open invariant `Array<T>` producers, and ordinary user classes whose
contract reaches `Iterator<T>` directly or through a supported subinterface. Each array producer
constructs the corresponding closed stdlib class, such as `ArrayIterator<int32>` or
`ArrayIterator<string>`. An open vector stays exact `!n[]`/`!!n[]` and constructs
`ArrayIterator<!n>`/`ArrayIterator<!!n>`; erased `Next()` is narrowed with
`unbox.any !n`/`!!n`.
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

The same ownership rule applies table-wise to `Iterable`: a class with a class-owned typed
`iterator()` receives the private `GetIterator()` bridge, a derived class inherits a base bridge,
and an abstract obligation-only base defers bridge ownership to the first implementing descendant.
The bridge policy is compiler-owned and contract-driven; `ArrayIterator` is not a privileged
source class and user implementations use the same path.

A bodyless module-local iterator or iterable subinterface inherits the corresponding non-generic
runtime identity. It may declare unrelated abstract members, but it does not republish typed
`next`, `hasNext`, or `iterator` slots. Calls to inherited fake overrides are emitted against the
erased runtime slots, and an implementing class receives the same private bridges as a direct
implementer. A source redeclaration such as `override fun next(): T` or
`override fun iterator(): Iterator<T>` is rejected because emitting it on the CLR interface would
create a second typed execution contract beside the canonical erased one. Interface member bodies
remain rejected at the .NET Framework 4.8 compatibility floor; this design does not depend on
default interface methods.

The subinterface's own generic identity remains subject to the backend's existing CLR generic
interface rules. Declaration-site covariance works for reference-shaped arguments, but a widening
such as `IteratorView<Int>` to `IteratorView<Any>` or `IterableView<Int>` to
`IterableView<Any>` remains rejected because CLR variance does not apply to value-type
instantiations. Widening those objects to the canonical `Iterator<Any>` or `Iterable<Any>` view is
safe and remains adapter-free because the base identity is erased. This limitation must not be
mistaken for a second representation.

This decision is deliberately limited to Kotlin-owned contracts. It does not claim that an
arbitrary imported CLR covariant interface can preserve Kotlin value-type covariance, raw CLR
object identity, and exact closed CLR signatures simultaneously. Imported `IEnumerable<T>` /
`IEnumerator<T>` and any future compiler-internal foreign variance views are a separate interop
design. They must not change the Kotlin-owned erased identities or make this bridge policy depend
on BCL interfaces.

## Consequences

Benefits:

- primitive and reference iterator covariance preserves `===` and shared cursor state;
- Kotlin iteration semantics stay independent from `IEnumerator` rules;
- a generic consumer can return `T` through `unbox.any !!n`; and
- CLR enumeration adapters can be added without changing Kotlin iterator identity.

Costs:

- primitive elements box at the erased `Next()` boundary and unbox at the Kotlin call site;
- each bridge-owning iterator carries two small private MethodImpl bridges and each iterable one;
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
- `compiler/testData/codegen/dotnet/box/arrayIterators.kt`;
- `compiler/testData/codegen/dotnet/ilText/iterables.kt`;
- `compiler/testData/codegen/dotnet/box/iterables.kt`; and
- the remaining iterator-family negatives in `genericArraysRejected.kt`.

The array IL pin now carries an AssemblyRef to `Kotlin.Stdlib, Version=1.0.0.0` and constructs
closed generic stdlib iterators. The box harness requires `Kotlin.Stdlib.dll` and verifies its
retained IL contains the generic implementation plus compiler-generated `HasNext`/`Next`
MethodImpl bridges before executing the program on CoreCLR.

## Deferred decisions

This draft does not decide separately compiled Kotlin producer modules, subinterfaces with member
bodies, primitive-specialized iterator subclasses, collection/list iterators, mutable iterators,
sequences, CLR `IEnumerable<T>`/`IEnumerator<T>` adapters or foreign variance views, typed
fast-path members, or Kotlin metadata encoding. The target-stdlib migration of `ArrayIterator` is
implemented, but standalone stdlib production/consumption is not. Later layers may add views or
optimizations, but ordinary Kotlin iteration covariance must continue to preserve the erased
canonical identities or this draft must be explicitly revised.
