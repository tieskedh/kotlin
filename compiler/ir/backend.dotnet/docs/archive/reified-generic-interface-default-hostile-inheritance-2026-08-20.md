# Reified generic-interface default hostile inheritance — 2026-08-20

## Question

Can a defaulted real CLR generic interface cross an external generic Kotlin
override and then an ordinary C# subclass without making C# implement the
hidden Kotlin semantic capability?

The bounded chain is:

```text
lib.dll
  DefaultConsumer<in T>.consumeDefault(T) { default body }
      |
middle.dll
  OpenDefaultConsumer<T>.consumeDefault(T) { Kotlin override }
      |
C# product
  Derived : OpenDefaultConsumer<object>
  override consumeDefault(object)
```

The C# class is deliberately non-partial. It cannot receive a generated
semantic sibling and does not name any compiler ABI.

## Corrected fail-first assumption

The first test formulation expected the C# interface-authoring generator to
augment the derived class. All four candidate lanes rejected that assertion:
the generator correctly handles directly authored Kotlin interface contracts,
not a class whose Kotlin base already fulfills the semantic obligation.

Requiring generated source here would weaken ordinary subclassing by making
`partial` part of the contract. The corrected proof therefore makes the C#
class non-partial and requires that generated source does not mention it.

## Selected behavior

`OpenDefaultConsumer<T>` is a real CLR generic Kotlin class in the rehearsal
epoch. Its natural override accepts `!T`. Its inherited semantic capability
remains an operation boundary: a Kotlin-valid contravariant input is converted
to the actual construction and the natural member is invoked virtually.

For `OpenDefaultConsumer<object>`, both of these calls therefore reach the
ordinary C# override:

```text
DefaultConsumer<object>.consumeDefault("reference")
DefaultConsumer<Int>.consumeDefault(76)
```

The second view is legal Kotlin contravariance even though CLR variance does
not convert reference-constructed `I<object>` to value-constructed `I<int>`.
The C# override observes both values, the Kotlin middle body observes neither,
and both paths retain the same receiver identity.

No new compiler probe, runtime reflection, source adapter, state, physical ABI
record, or C# semantic member is needed. The producer-recorded interface and
member families plus ordinary CLR virtual dispatch are already sufficient.

## Verification

The enabled generic-owner rehearsal passes PSI and LightTree on Framework 4.8
and .NET 10: four tests, zero failures, errors, or skips. The exact three-product
layout is compiled separately in every lane. The production epoch-off inverse
uses the erased owner spelling and passes the same four-lane matrix. The test
also asserts that the source generator emits no class fragment for the derived
C# type.

## Remaining boundary

This closes one contravariant default, one identity type-argument edge, one
generic Kotlin override, and one ordinary C# subclass. It does not close
multiple default members, default properties, method-generic defaults,
diamonds, reabstraction, changed type arguments, multiple/deeper inheritance,
unsafe broad inputs, or trimming/NativeAOT for this exact default chain.
