# Generic-owner invariant projection boundary

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now distinguishes exact declaration-
invariant constructions from Kotlin use-site output projections:

```kotlin
val exact: InvariantProducer<String> = value
val projected: InvariantProducer<out Any?> = exact
```

The first remains the natural CLR `InvariantProducer<string>`. The second is a
Kotlin semantic view of the same object; CLR invariance cannot honestly name
it `InvariantProducer<object>`. Public projected callable slots therefore use
`object`, and an output operation chooses the compiler capability or exactly
one natural foreign construction at the call.

## Construction-local storage

Materializing the projected value in another invariant owner selects a broad
argument for that concrete construction only:

```text
Box<InvariantProducer<out Any?>>  ->  Box<object>
```

`Box<T>` still has one physical `!T` field. Exact `Box<Int>`, `Box<String>`,
and `Box<InvariantProducer<String>>` constructions remain typed, while the
open control stays:

```text
<T>(Box<InvariantProducer<T>>) -> Box<InvariantProducer<T>>
    = Box<InvariantProducer<!!T>> -> Box<InvariantProducer<!!T>>
```

The projected box can store first an invariant String producer and then an
invariant Int producer because both are legal values of the projected Kotlin
type. It preserves each original object and performs no wrapper conversion,
shadow-state update, or fabricated invariant CLR cast.

## Planner composition correction

The fail-first factory initially published the whole non-generic `Box`
capability even though its constructor had already selected a concrete
`Box<object>`. The generic-owner provenance comparison rejected all projected
types before recognizing that the origin and declared complete logical type
were identical.

An exact constructor origin now proves its complete already-selected physical
construction. Arbitrary public inputs remain unresolved; casts and widening
preserve their producer origins. Consequently this rule cannot manufacture
typed evidence for a foreign boundary value, while a downstream general
semantic fallback can no longer degrade a concrete outer construction.

## Foreign interop

Same-module and separate-compilation C# probes use ordinary non-partial
classes implementing only natural `InvariantProducer<string>`. They call the
projected Kotlin operation and enter the projected box factory without naming
or implementing any Kotlin semantic capability. The box read returns the
exact same C# object by reference.

## Evidence

The same-module and separate-KLIB fixtures cover:

- projected calls and identity for Kotlin String implementations;
- mutation of one projected box between String and Int implementations;
- retained exact/open invariant `Box<InvariantProducer<!!T>>` metadata;
- a concrete `Box<object>` return and `object` field for the projected factory;
- projected object-to-object callable metadata;
- ordinary non-partial C# projected calls and nested storage identity; and
- producer-record reconstruction by a separate Kotlin/C# consumer.

The rehearsal matrix executes PSI and LightTree for both fixtures on .NET 10
and .NET Framework 4.8: eight tests, zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate audits 190 XML suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155
tests and two integration suites/126 tests were freshly written, while the
unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This closes only the no-input declaration-invariant producer under output
use-site projection. Mutable properties, input members, multiple members,
overloads, defaults, mixed variance, multiple type parameters, value classes,
and arbitrary foreign member shapes remain separate proofs. In particular,
this result is not permission to map every projected or nested generic value
to `object`; the broad carrier is construction- and operation-local.
