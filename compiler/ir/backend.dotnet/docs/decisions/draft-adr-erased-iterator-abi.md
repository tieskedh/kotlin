# Draft ADR: Erased Kotlin iterator ABI on CLR

- Status: **Draft candidate; implemented for array iterators in the prototype**
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

Direct `for (element in array)` loops do not use this object. The existing common indexed-loop
lowering evaluates the array once and remains allocation-free. The iterator ABI is used only when
an explicit `iterator()` call produces a value that can escape or be stored.

The current bounded implementation supports the five established primitive vectors and concrete
reference-element arrays. An open `Array<T>.iterator()` producer remains rejected even though an
already-created `Iterator<T>` can be consumed by a generic function. User classes implementing
`Iterator<T>` also remain rejected until bridge generation can provide the erased slots from
their logically typed members.

## Consequences

Benefits:

- primitive and reference iterator covariance preserves `===` and shared cursor state;
- Kotlin iteration semantics stay independent from `IEnumerator` rules;
- a generic consumer can return `T` through `unbox.any !!n`; and
- CLR enumeration adapters can be added without changing Kotlin iterator identity.

Costs:

- primitive elements box at the erased `Next()` boundary and unbox at the Kotlin call site;
- the raw interface is not an idiomatic CLR enumeration surface; and
- logical element types require Kotlin metadata for future cross-module Kotlin compilation.

## Validation

Probe series `iteratorabi_s1` assembled the runtime, a generic consumer, primitive/reference array
producers, and an exact exhaustion catch with modern 10.0.9 and .NET Framework 4.8 ILAsm. All four
same-assembler and cross-runtime pairings produced the same results.

Repository pins cover both FIR parsers and real CoreCLR execution:

- `compiler/testData/codegen/dotnet/ilText/arrayIterators.kt`;
- `compiler/testData/codegen/dotnet/box/arrayIterators.kt`; and
- the open-producer and user-implementation negatives in `genericArraysRejected.kt`.

## Deferred decisions

This draft does not decide collection/list iterators, mutable iterators, sequences, user-defined
iterator bridge generation, `Iterable<T>`, CLR `IEnumerable<T>`/`IEnumerator<T>` adapters, typed
fast-path members, or Kotlin metadata encoding. Those layers may add views or optimizations, but
ordinary Kotlin iterator covariance must continue to preserve the erased canonical identity or
this draft must be explicitly revised.
