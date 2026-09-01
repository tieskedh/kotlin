# Generic-owner physical control-flow join placement — 2026-09-01

This archive records the second authoritative consumer of final physical-value
facts on physical-library ABI 66, generic-owner artifact schema 21, and
compiler/runtime surface 60. It is rehearsal-only executable evidence for the
physical-authority ADR. It does not change production representation or Kotlin
semantics.

## Boundary

The first consumer could retain only one already-produced local `C<!n>` carrier
at an equal local storage boundary. A widened control-flow initializer with two
different concrete implementation carriers still reached emission as a
fabricated logical sibling:

```text
InlineSelfView<!T>   InlineSecondView<!T>
          \           /
       logical InlineProducer<Any?>
                  |
        fabricated InlineProducer<object>
```

Both concrete classes physically implement `InlineProducer<!T>`. The correct
join is therefore neither either concrete class nor the logical
`InlineProducer<object>`, but the unique interface construction which their
recorded physical graphs already share.

## Rule

For an exhaustive control-flow expression whose logical classifier selects one
admitted natural-interface family:

```text
identical direct reaching carrier
    -> retain it unchanged

different direct reaching carriers
    -> close each carrier over recorded physical InterfaceImpl edges
    -> intersect the closures in the selected family
    -> retain the sole shared construction
```

The logical classifier is only a family selector. It does not prove an edge or
an argument vector. Equal `selectedViewLineage` may disambiguate several shared
constructions only if that selected view is already present in every recorded
closure. Missing edges and ordinary ambiguity yield `Unavailable`; they are
normal loss of dataflow precision, not contradictory declaration authority.
No CLR variance conversion or `I<object>` construction is synthesized.

The resulting producer and storage carriers must still be the same direct,
null-reference, local owner-bound construction. Every `!n` is authenticated
against the final physical MethodDef owner. The identity-keyed permission says
nothing about calls, casts, state, or logical Kotlin subtyping.

## Independent emission check

The emitter reconstructs the selected construction from TypeDef authority and
the live physical owner. It also requires the initializer to remain an
`IrWhen`. Variable emission supplies the selected local type as the fixed
physical destination, so every live branch is independently emitted and
validated against that boundary. The analysis is not a substitute CLR type
mapper, and a changed control-flow shape fails closed.

Direct initializers keep the stricter whole-expression carrier comparison from
the first consumer.

## Hostile proof

`genericOwnerInlineWidenedTemporary.kt` creates two generic classes with one
recorded `InlineProducer<T>` interface family. Inside `InlineSelfView<T>`, an
exhaustive `if` joins `this` with a newly allocated
`InlineSecondView<T>` into the logical type `InlineProducer<Any?>`. The test
executes both arms for `Int` and `String` and proves:

- one unique recorded `InlineProducer<!T>` construction is the physical local;
- emitted IL contains that construction and no `InlineProducer<object>`;
- the logically widened `produce()` still follows Kotlin's semantic route;
- both views preserve the selected object's identity; and
- the same source remains valid under the production-erased inverse.

Model tests independently prove unique selection, identical-carrier retention,
missing-edge rejection, ambiguous-view rejection, and disambiguation only by a
lineage which already selects a shared recorded view.

This bounded slice deliberately does not admit typed regular parameters,
non-exhaustive control flow, null/bottom/unknown arms, stars or projections,
mutable or multiple-write locals, foreign/fixed/nested/value-shaped carriers,
fields, captures, or split-nullable layouts.

## Verification

The backend and FIR2IR test fixtures compile. The shared physical-value model
passes 87 tests. The focused fixture assembled and executed through FIR PSI and
LightTree on .NET 10 and Framework 4.8. Direct JUnit XML audit reported four
candidate suites/four tests and four production-erased inverse suites/four
tests, each with zero failures, errors, or skips.

The focused commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest '-Pkotlin.dotnet.genericOwnerRehearsal=true' <four focused parser/profile filters> --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun <the same four filters> --no-configuration-cache -q
```

No physical ABI, artifact schema, Runtime/Stdlib surface, production owner ABI,
or breaking-Kotlin decision changed.
