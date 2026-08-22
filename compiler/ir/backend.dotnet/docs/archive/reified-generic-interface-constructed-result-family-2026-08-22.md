# Constructed generic-interface result proof

Date: 2026-08-22

## Question

Can the generic-interface rehearsal express the first natural composition
needed by `Iterable<T>.iterator(): Iterator<T>` without globally erasing either
generic owner or generic state merely because Kotlin can form a CLR-unnameable
covariant view?

The proof deliberately uses name-independent rehearsal declarations:

```kotlin
interface Cursor<out T> {
    fun hasNext(): Boolean
    fun next(): T
}

interface IterableValue<out T> {
    fun iterator(): Cursor<T>
}
```

Exact Kotlin and ordinary C# callers must see the natural constructed result.
A widened Kotlin view and a hostile nested state value must reach the same
cursor object without a wrapper, shadow state, or fabricated CLR construction.

## Fail-first evidence

The previous grammar admitted `Cursor<T>` but rejected the outer interface, so
the producer emitted no authoring family for a constructed result. After
structural admission, the first lowering initially replaced the natural
`Cursor<!T>` result with `object`; a later state/call lowering then either
requested a nonexistent `object iterator()` MethodRef or narrowed hostile
nested state through the typed entry too early. Separate C# authoring also
exposed an independent locator defect: Roslyn's global namespace was serialized
as the literal `'<global namespace>'`, so a valid global `Cursor<int>` return
could not match the Kotlin MethodDef.

These failures occurred after the nested `Cursor<T>` family and exact producer
graph had already been selected. They therefore isolated composition and
locator bugs rather than invalidating CLR-generic owner admission.

## Selected representation

Admission is structural and narrow. A public abstract covariant root may have
one no-input result which is a non-null, already-admitted, one-parameter
covariant interface constructed invariantly over the outer owner parameter.
Names, packages, stdlib ownership, and declaration order do not participate;
root selection reaches a fixpoint so the nested family is published first.

For `IterableValue<int>` the physical result family is:

| Surface | Result |
| --- | --- |
| Natural CLR/Kotlin/C# entry | `Cursor<int>` |
| Kotlin declaration-semantic slot | `object` |

The semantic result cannot truthfully be `CursorSemantic`. An ordinary C#
implementation may return an object which implements only natural
`Cursor<int>`; requiring the Kotlin capability would need a wrapper and would
change identity. Conversely, claiming `Cursor<object>` would fabricate a CLR
construction which need not exist for a value-type widening.

The `object` carrier is operation-local, not the default storage model. Exact
nested state remains a real `Cursor<!T>` field. Only a concrete outer state
producer which can receive a Kotlin-legal but CLR-unnameable widened nested
cursor selects object-domain state. The planner records
`PAIRED_SEMANTIC_STATE_OUTPUT`, and an exact-receiver
`SEMANTIC_RESULT_CAPABILITY` route reads that state through the compiler
dispatcher. The natural typed entry still performs the exact CLR-view cast for
ordinary callers. This route is distinct from a semantic receiver and cannot
degrade an unrelated proven typed call.

ABI and Runtime surface 44 publish the
`CONSTRUCTED_INTERFACE_PRODUCER` member role. Producer and consumer reconstruct
the same object-return semantic MethodRef across separate compilation. The C#
authoring locator now treats Roslyn's global namespace as empty and reports all
same-name physical candidates when a manifest locator is malformed.

## Closed corpus

The separate-compilation corpus covers:

- exact Kotlin `IterableValue<Int>` state and its natural `Cursor<Int>` result;
- covariant outer widening and the same cursor identity;
- hostile `IterableValue<Any?>` state containing a `Cursor<Int>` observed as
  `Cursor<Any?>`, without globally erasing exact state;
- an ordinary partial C# `IterableValue<int>` which authors only the natural
  `Cursor<int> iterator()` member and returns an ordinary C# cursor;
- direct C# calls, Kotlin widened calls, generated compiler ABI, separate
  producer/consumer binding, and unchanged object identity; and
- PSI and LightTree on Framework 4.8 and .NET 10.

Candidate, explicit epoch-off, and property-absent configurations execute on
both target profiles: twelve focused lanes, all green. The full `dotNetTest`
aggregate exits zero. Direct XML audit reports 190 suites and 2,287 tests with
zero failures, errors, or skips. The 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written; the unchanged six-test
`dotnet.ir` model root remained up-to-date.

## Boundary and next gate

This proves the constructed-result grammar, not the actual stdlib owners;
production emission remains erased. It does not admit arbitrary nested result
graphs, inputs, properties, defaults, overloads, diamonds, extra producer
members, or mixed/multiple type parameters. The next bounded work is the
broader input/property composition needed by `Collection<T>` and `Set<T>`,
still without a Map, Set, Sequence, package, or declaration-name exception.
