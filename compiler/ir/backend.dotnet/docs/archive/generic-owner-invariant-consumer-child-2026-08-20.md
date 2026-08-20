# Generic-owner invariant consumer child

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now composes an owner-parameter input
across one exact invariant interface-inheritance edge:

```kotlin
interface PropertyCell<T> {
    var value: T
}

interface ConsumerChild<T> : PropertyCell<T> {
    fun consume(value: T)
}
```

The normal physical contract is the natural invariant CLR
`ConsumerChild<T> : PropertyCell<T>` with one child-owned `Consume(!T)`
MethodDef. The inherited mutable `Property<T>` remains inherited. A Kotlin
implementation stores its one authoritative value in a physical `!T` field;
the consumer body updates that same field.

## Structural boundary

Admission is deliberately narrow. Both owners must be public, top-level,
single-parameter invariant interfaces. The parent is the already admitted
exact mutable-property root. The child has exactly one direct `Parent<T>` edge
using its own invariant, unbounded, non-null `T`, declares no property, and
declares exactly one public abstract non-generic `Unit` method with one direct
`T` parameter.

The sibling exact-property child remains admitted. A child with multiple
members, a producer or nullable input, defaults, overloads, changed arguments,
multiple parents, deeper inheritance, constraints, or another type parameter
remains fail-closed. This is not a declaration-name, collection, or Stdlib
exception.

## Natural and semantic paths

Exact and open Kotlin/C# access names the natural generic child and method:

```text
ConsumerChild<!!T> : PropertyCell<!!T>
ConsumerChild<!!T>.Consume(!T) -> void
```

Only an input-projected operation crosses the object receiver boundary:

```text
ConsumerChild<in String>.consume(String)
    -> (object receiver, string value) -> void
```

For Kotlin implementations the child capability owns one object-input method
and inherits the parent's two property-accessor slots. It contains no CLR
Property row and does not copy the inherited slots. For ordinary foreign
objects, the existing surface-40 dispatcher selects exactly one natural child
construction and invokes `Consume(T)`. The already-closed mutable invariant
cell tests retain the missing/multiple-construction and member-exception
oracles for that argument-bearing dispatcher.

## Ordinary C# implementation

Same-module and separate-compilation probes compile non-partial C#
`ConsumerChild<string>` and `ConsumerChild<object>` classes. Each supplies one
normal auto-property and one normal `Consume(T)` method. Exact C# calls and
Kotlin input-projected calls update the same property. C# authors implement no
semantic interface and need no generated adapter.

The first authoring run found a real composition defect. The analyzer judged
each invariant manifest contract independently and required every contract to
own a producer. It therefore rejected the child-owned consumer manifest even
though its property producer is inherited. A structurally exact one-member
invariant consumer contract is now recognized as a complete child fragment
only when its constructed CLR interface inherits a bound manifest contract
with the complete producer bundle. A standalone invariant consumer still
requires its generated object adapter. The producer manifests and physical
inheritance remain the fail-closed boundary; contravariant and broader
contracts still require the generated capability path.

The separate manifest contains exactly the child-owned method with natural
`!T` and semantic `object` inputs. Reflection proves one natural parent, one
declared `Consume(T)` method, no child Property row, one `!T` implementation
field, and a one-method child capability inheriting the two-method parent
capability.

## Evidence

The fail-first direct and separate products rejected the child because its
already-reified `PropertyCell<T>` parent mapped to a non-interface carrier.
After backend admission the Kotlin-only matrix passed, while ordinary C#
classes still failed with `KDNCS001` because the analyzer demanded `partial`.
The first manifest-composition repair made the complete C#/metadata oracle pass
without changing those source classes, but the full integration gate caught
that treating every consumer-only manifest as a child suppressed the adapter
for a standalone synthetic `Shape<T>`. The final context-aware rule keeps that
existing generator case green while retaining the ordinary consumer child.

PSI and LightTree execute the direct and separate fixtures on .NET Framework
4.8 and .NET 10: eight tests with zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate directly audits 190
XML suites and 2,287 tests with zero failures, errors, or skips: 187 FIR
suites/2,155 tests and two integration suites/126 tests were freshly written,
while the unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This closes one direct consumer above an exact property root. It does not admit
arbitrary mixed member families, multiple input members, a method-cell parent,
producer/consumer children, deeper inheritance, defaults, constraints,
classifier-derived fields, or the Runtime/Stdlib owner graph.
