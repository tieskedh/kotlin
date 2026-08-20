# Generic-owner open nested-construction boundary

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now carries construction stability
through a nested open type parameter without globally erasing the enclosing
owner or its field model.

The closed cases remain physical CLR constructions:

```text
Box<Int>                 -> Box<int>
Box<String>              -> Box<string>
Box<Producer<String>>    -> Box<Producer<string>>
Box<Consumer<Cat>>       -> Box<Consumer<Cat>>
```

`RehearsalNestedBox<T>` still owns exactly one `!T` field. There is no object
shadow field, wrapper, proxy, or copied state.

## Why the open boundary is object

One generic MethodDef such as:

```text
<T> identity(Box<Producer<T>> box): Box<Producer<T>>
```

must accept at least both of these legal physical values after substitution:

```text
exact caller value       Box<Producer<string>>
semantic caller value    Box<object>
```

CLR invariance prevents either construction from containing the other. The
single honest physical signature is therefore:

```text
object identity<T>(object box)
```

The object is the original box. The compiler retains that physical provenance
through immutable locals and direct call chains. A later box member call uses
the same object's existing generic-class capability. A producer read as
`object` then enters the admitted producer capability-or-ordinary-foreign
dispatcher; a consumer read as `object` enters its capability at `consume`.
Identity and null consumers do not cross either operation boundary.

An open factory follows the same rule. Its body closes a real `Box<object>`
because its `Producer<T>` or `Consumer<T>` input has no universal natural
`I<!!T>` construction, while its public generic MethodDef carries both input
and result as `object`. C# can pass a natural precompiled producer through this
boundary and observes the same instance stored in the concrete box.

## Non-contagion control

The rule requires a nested admitted variant owner with an open argument. The
control:

```text
<T> identity(Box<Box<T>> box): Box<Box<T>>
```

remains:

```text
Box<Box<!!T>> identity<T>(Box<Box<!!T>> box)
```

The inner invariant box has one stable reified construction for each method
substitution. It therefore does not activate the object boundary. Closed exact
and reference-only producer/consumer constructions likewise remain typed.
No `Box`, `List`, `Producer`, `Consumer`, stdlib, or source-name switch selects
the rule.

## Fail-first findings

The fail-first sequence exposed four composition defects:

1. an open variant argument was initially treated as a stable `I<!!T>` generic
   argument;
2. an open consumer parameter selected its sibling capability, excluding an
   ordinary natural implementation from storage or forwarding before any
   member operation;
3. a returned `Box<object>` was reconstructed as an invariant typed sibling at
   a caller local or member receiver; and
4. the first factory-only proof could not accept an already constructed exact
   box through an open identity boundary.

The final rule maps open interface occurrences to object, maps the whole open
nested class boundary to object, retains the actual construction as provenance,
and dispatches only from that proven physical carrier. Exact typed routes are
unchanged.

## Evidence

The same-module and separate-KLIB fixtures cover:

- value, reference, and broad producer substitutions;
- value and reference contravariant consumer substitutions;
- direct call chains and immutable aliases;
- exact and semantic box identity;
- read and write dispatch on the original field;
- a natural precompiled C# producer crossing the open factory;
- C# reflection of open factory and identity `object` boundaries;
- C# reflection of the stable `Box<Box<!!T>>` negative control; and
- the unchanged one-`!T` open field.

The rehearsal matrix executes PSI and LightTree for both fixtures on .NET 10
and .NET Framework 4.8: eight tests, zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate audits 190 XML suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155
tests and two integration suites/126 tests were freshly written, while the
unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This proof does not admit invariant or mixed-variance interfaces,
multi-parameter owners, value-class/unsigned substitutions, open nullable
compositions beyond the existing rules, arbitrary mutable producer graphs, or
broader foreign input-bearing implementations. Those remain separate gates
and may not erase unrelated stable owner state as a shortcut.
