# Generic-owner nested construction carrier

## Result

The test-only CLR-generic-owner rehearsal now materializes a legal Kotlin
`Producer<Int> -> Producer<Any?>` view inside an invariant generic class
without globally erasing that class or its state.

The open declaration remains:

```text
RehearsalNestedBox<T>
  private !T value
```

Its exact constructions retain their natural CLR arguments:

```text
Box<Int>                 -> Box<int>
Box<String>              -> Box<string>
Box<Producer<String>>    -> Box<Producer<string>>
```

Only the unstable closed construction is widened:

```text
logical Box<Producer<Any?>> -> physical Box<object>
```

That slot can hold the original `Producer<int>`, a later
`Producer<string>`, or an ordinary precompiled CLR implementation. The CLR
cannot name one `Producer<object>` construction which contains a value-type
construction through variance. The object carrier is therefore attached to
this outer instantiation, not to the open field declaration.

## Composition rule

An expression which is logically an admitted generic owner but physically
produces `object` remains `object` through reference identity and null checks.
Only an actual generic-owner member operation enters the semantic/foreign
dispatcher. Codegen must not recover the logical type by emitting a cast to a
fabricated constructed interface.

This fixed a real downstream composition defect. Construction substitution
already selected `Box<object>`, but equality initially requested
`Producer<object>` from `Box<object>.read()`. Preserving the expression's
physical carrier made identity, semantic dispatch, and subsequent mutation
compose without a wrapper or duplicated state.

## Evidence

Same-module and separate-compilation products prove:

- one open `!T` field and no shadow field;
- exact value, reference, and nested-reference constructions stay typed;
- the broad factory publishes `Box<object>` while the exact factory publishes
  `Box<Producer<string>>`;
- broad reads preserve identity and dispatch to the original producer;
- the same box can later store a different compatible logical producer view;
- a later KLIB consumer obeys the producer-selected construction; and
- C# reflection observes the actual DLL signatures.

PSI and LightTree execute both products under the rehearsal epoch on .NET 10
and .NET Framework 4.8. The production epoch-off inverse executes the same
Kotlin source without applying the rehearsal-only C# layout assertions.

## Remaining boundary

The accepted rule is structural but bounded. It covers an admitted covariant
generic owner whose universal logical argument maps to `object`. It does not
yet prove non-universal supertypes which can receive value-type producer
subtypes, nested open arguments, contravariant/mixed/multi-parameter owners,
or arbitrary Runtime/Stdlib collection graphs. Those require a general
construction-stability proof; they may not respond by erasing every enclosing
owner or field.
