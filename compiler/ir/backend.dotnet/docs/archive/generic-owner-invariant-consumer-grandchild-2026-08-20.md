# Generic-owner invariant consumer grandchild

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now composes the exact invariant property
and consumer family across a second inheritance edge:

```kotlin
interface PropertyCell<T> {
    var value: T
}

interface ConsumerChild<T> : PropertyCell<T> {
    fun consume(value: T)
}

interface ConsumerGrandchild<T> : ConsumerChild<T> {
    fun consumeSecondary(value: T)
}
```

The normal physical hierarchy remains the natural invariant CLR generic
hierarchy. The root owns one mutable `Property<T>` row, each descendant owns
one `Consume(!T)` MethodDef, and a Kotlin implementation stores one
authoritative value in a physical `!T` field.

## Bounded structural admission

This is an exact depth-two extension, not recursive general admission. The
root and first child must satisfy the already-proven property-root/one-consumer
shape. The grandchild is public and top-level, has one unbounded invariant
parameter, has exactly one direct `ConsumerChild<T>` edge using its own
non-null `T`, declares no property, and declares exactly one public abstract
non-generic `Unit` method with one direct `T` input.

The lowering recognizes the first consumer child by reconstructing that whole
root edge. It admits one further consumer only above precisely that child. A
third edge, another property, multiple members or parents, changed arguments,
nullable input, defaults, overloads, constraints, or additional parameters
remain fail-closed. No declaration or library name participates.

## Natural and semantic paths

Exact and open calls stay on the natural CLR family:

```text
ConsumerGrandchild<!!T>
    : ConsumerChild<!!T>
    : PropertyCell<!!T>

Consume(!T) -> void
ConsumeSecondary(!T) -> void
```

Only the input-projected operation uses an object receiver. The compiler
capability family mirrors the hierarchy without copying slots: the root owns
two property accessor methods, the child owns one consumer method, and the
grandchild owns one secondary consumer method. Reflection proves a 2 -> 1 -> 1
declared-method chain, no semantic CLR Property rows, and one implementation
`!T` field. Both consumers update that same field.

## C# and separate compilation

Ordinary non-partial C# `ConsumerGrandchild<string>` and
`ConsumerGrandchild<object>` classes implement one normal auto-property and
two normal `Consume(T)` methods. Exact C# calls and Kotlin projected secondary
calls update the same property. C# supplies no compiler interface or adapter.

The authoring analysis from the first consumer-child proof composes
transitively: Roslyn's constructed grandchild reports both the child and root
inherited interfaces, so each child-owned consumer manifest can find the bound
complete producer bundle at the root. A standalone consumer still retains its
generated object adapter.

The separate fixture places the property root in `lib.dll` and both consumer
descendants in `middle.dll`. The middle manifest contains two distinct one-
member child contracts and no copied root accessors. This proves restoration
of the external root plus local fixpoint ordering for the two descendants. It
does not yet claim a three-producer-assembly inheritance chain.

## Evidence

The fail-first direct and separate products rejected only the grandchild
because its already-reified consumer parent mapped to a non-interface carrier.
After bounded admission, metadata, Kotlin execution, and ordinary C# execution
all preserve the natural hierarchy, exact/open signatures, projected operation
boundary, object identity, and single typed state.

PSI and LightTree execute the direct and separate fixtures on .NET Framework
4.8 and .NET 10: eight tests with zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate directly audits 190
XML suites and 2,287 tests with zero failures, errors, or skips: 187 FIR
suites/2,155 tests and two integration suites/126 tests were freshly written,
while the unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

Arbitrary depth remains intentionally unsupported. The next inheritance gates
include a third producer assembly, broader/multiple members, producer/consumer
mixes, multiple parents, changed substitutions, defaults, constraints, and
the Runtime/Stdlib owner graph.
