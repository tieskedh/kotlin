# Generic-owner invariant producer

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now admits the first declaration-
invariant Kotlin generic interface:

```kotlin
interface InvariantProducer<T> {
    fun produce(): T
}
```

Its sole public CLR owner is the natural invariant `InvariantProducer<T>`.
Exact value/reference constructions and open method substitutions use ordinary
CLR generic arguments and calls. The interface still has a non-generic
object-result capability for Kotlin star reads, but that sibling is an
operation fast path rather than a C# implementation obligation.

## Non-contagion proof

An invariant owner has no legal declaration-site sibling widening. Therefore:

```text
<T>(Box<InvariantProducer<T>>) -> Box<InvariantProducer<T>>
```

remains physically:

```text
Box<InvariantProducer<!!T>> -> Box<InvariantProducer<!!T>>
```

It does not enter the object-carried open-variant boundary. `Box<T>` retains
its single `!T` field and the original box and producer identities are
unchanged. This is the direct control for the concern that supporting semantic
variant views would gradually turn ordinary invariant generic state into
`object` state.

## Star and C# boundary

`InvariantProducer<*>` cannot name one closed CLR construction. A public star
parameter is therefore physically `object`, not the hidden capability. At a
no-input producer call the backend first probes the capability and otherwise
invokes the member on exactly one natural closed `InvariantProducer<T>`
construction. Zero constructions fail as a cast and multiple constructions
remain ambiguous.

An ordinary non-partial C# class may consequently implement only:

```csharp
sealed class CsProducer : InvariantProducer<string>
{
    public string produce() => "value";
}
```

It needs no knowledge of the Kotlin capability. The Roslyn authoring tool now
skips a class whose Kotlin contracts are all admitted invariant owners. It
still requires `partial` when any variant contract needs generated semantic
interfaces or adapters. Thus `partial` remains an optional fast path here and
a real requirement only for the still-bounded variant families.

## Evidence

The same-module and separate-KLIB fixtures cover:

- exact `InvariantProducer<int>` and `InvariantProducer<string>` calls;
- star reads from Kotlin implementations;
- star reads from ordinary non-partial C# implementations;
- invariant metadata and the natural `!0` MethodDef result;
- the C# authoring manifest's `INVARIANT` record;
- exact open `Box<InvariantProducer<!!T>>` input/result metadata;
- retained box and producer identity across the open boundary; and
- external-stub reconstruction in a separate Kotlin consumer.

The rehearsal matrix executes PSI and LightTree for both fixtures on .NET 10
and .NET Framework 4.8: eight tests, zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate audits 190 XML suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155
tests and two integration suites/126 tests were freshly written, while the
unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Fail-first findings

The fail-first sequence established three separate boundaries:

1. Roslyn initially saw the invariant interface as a non-generic TypeDef,
   proving that the feature did not already exist accidentally.
2. Reifying the interface exposed a public star parameter as the hidden
   capability, so an ordinary C# implementation could not call it. Selecting
   `object` for star parameters moved capability-or-natural selection to the
   member operation.
3. An initial authoring optimization skipped every invariant contract. The
   full production aggregate correctly rejected that rule because historical
   invariant split interfaces still require generated object-input adapters.
   The final predicate skips only one abstract zero-input producer with the
   exact `object` semantic and `!0` natural result-slot pair; inputs,
   properties, multiple members, overrides, intersections, wrong-shape policy,
   other slot bundles, and other variances all retain generation.

## Remaining boundary

This proof admits one invariant no-input producer member. It does not yet admit
mutable read/write interfaces, properties, multiple members, overloads,
defaults, mixed variance, multiple type parameters, value-class substitutions,
or arbitrary foreign member shapes. Those require separate complete proofs.
In particular, skipping Roslyn capability generation is valid only because all
currently admitted invariant operations are either exact natural calls or the
proven star-output fallback.
