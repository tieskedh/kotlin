# Owner-independent query family proof

Date: 2026-08-22

## Question

Can the generic-interface rehearsal emit a normal multi-member covariant CLR
interface without making `Iterator`, `Set`, or another stdlib declaration a
special case? The first required shape combines an owner-dependent producer
with an owner-independent query:

```kotlin
interface Cursor<out T> {
    fun hasNext(): Boolean
    fun next(): T
}
```

Both members must remain ordinary C# obligations on the natural `Cursor<T>`.
A Kotlin widened value-type view must reach the same implementation and object
through the sibling declaration-semantic capability.

## Fail-first evidence

The valid Kotlin producer, middle implementation, widened consumer, and C#
implementation compiled through the production-erased path. With the rehearsal
enabled, the separate producer did not publish a C# implementation contract for
the two-member owner. Manifest validation therefore failed before any adapter
could be generated. The failure isolated the single-member admission rule; the
existing bridge and authoring pipelines already iterate complete member sets.

## Selected representation

Admission is structural and deliberately narrow. A public top-level covariant
root may contain exactly one abstract no-input member returning its owner
parameter directly plus one or more abstract no-input members returning a
non-null primitive independent of that parameter. Names, packages, and stdlib
ownership do not participate. Properties, inputs, defaults, method generics,
overloads, nested constructed types, and additional producer members remain
outside this proof.

For `Cursor<int>` the physical members are:

| Surface | Query | Producer |
| --- | --- | --- |
| Natural CLR/C# interface | `bool hasNext()` | `int next()` |
| Kotlin semantic capability | `bool hasNext()` | `object next()` |

The query exists on both interfaces because a receiver held only through the
semantic capability must still invoke it. Its carrier does not widen: `bool`
is identical on both surfaces. Exact calls use the natural interface. Only the
widened producer result crosses `object`; there is no wrapper, copied state, or
second object identity.

ABI and Runtime surface 43 add the producer-owned
`OWNER_INDEPENDENT_QUERY` member role. A separate consumer accepts the family
only when KLIB declarations, the published two-member family, both physical
member families, and the capability TypeDef agree.

## Closed corpus

The separate-compilation corpus covers:

- a Kotlin `Cursor<Int>` implementation and covariant `Cursor<Any?>` view;
- exact query/producer calls and widened producer dispatch;
- an ordinary partial C# `Cursor<int>` implementation which authors only the
  two natural methods;
- generated semantic adapters, direct C# calls, Kotlin widened calls, and one
  receiver identity; and
- PSI and LightTree on Framework 4.8 and .NET 10.

Candidate, explicit epoch-off, and property-absent configurations execute on
both target profiles: twelve focused lanes, all green. The full `dotNetTest`
aggregate exits zero. Direct XML audit reports 190 suites and 2,287 tests with
zero failures, errors, or skips. The 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written; the unchanged six-test
`dotnet.ir` model root remained up-to-date.

## Boundary and next gate

This is the first general multi-member covariant family and the direct
`Iterator<T>` member-shape prerequisite. It does not yet reify the stdlib
owner. The next hard composition is a constructed owner-dependent result such
as `Iterable<T>.iterator(): Iterator<T>`, including exact and semantic carriers
across a producer boundary. Collection inputs, properties, defaults, diamonds,
and mixed or multiple type parameters remain separate gates.
