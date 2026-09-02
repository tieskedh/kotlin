# Generic-owner external semantic-equivalence routing — 2026-09-02

This archive records the first bounded consumer of an external, PE-authenticated
generic-owner semantic-equivalence certificate. It is evidence and chronology,
not the normative design; current rules live in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).
The producer certificate and its objective PE authentication were closed by the
[preceding checkpoint](generic-owner-semantic-equivalence-certificate-2026-09-02.md).

## Closed question

An external `K` record contains one producer's claim that its natural and
semantic entries are equivalent for the exact `J` family that it names. Only
after PE authentication is that fact declaration authority; it cannot establish
which construction a consumer value carries. This checkpoint admits one
natural external call only after the consumer independently reconstructs every
value and operation fact required by the local exact-final route.

The admitted consumer shape is deliberately narrow:

- one top-level, non-method-generic function;
- one exact, non-null parameter whose external implementation class is final
  and has exactly one invariant fixed `Int32` or `String` argument;
- one immutable identity alias which widens that parameter to the logical
  interface's universal output view;
- exactly one read of that alias, as the receiver of the selected call;
- one arity-zero, non-method-generic natural MethodDef with no ordinary
  arguments or MethodSpec; and
- one direct, non-null result carried by an owner type parameter.

The consumer re-queries the exact logical interface member, implementation
owner, and implementation member. Their same-library, PE-stamped `K`/`J` pair
is projected into a sealed declaration index. Separately, the parameter supplies
the exact implementation construction; complete `J` interface closure supplies
its recorded natural views; and the ordinary physical operation router binds
the unique natural owner view, MethodDef, empty input vector, and direct result.
Neither `K`, the PE stamp, nor selected-view lineage creates any of those value
facts.

Only a completely bound proof may replace routing. In one transaction, the
analysis removes the already-selected semantic target and its compatible
foreign target, installs the natural operation, preserves the alias's exact
storage carrier, and creates one emitter witness. A partial replacement is a
compiler error rather than a fallback.

## Final-emission authority

The emitter does not trust the earlier analysis object as a substitute for the
external artifact. It repeats the three-declaration query and proves agreement
with the same normalized producer DLL, Assembly identity, `K`, `J`, all role
TypeDefs, natural MethodDef, and bound family. It then independently validates
the live parameter/local carrier, exact implementation construction, complete
and unique natural interface view, empty regular-parameter and MethodSpec
vectors, direct result carrier, and final interface `callvirt`.

Missing, duplicate, stale, ambiguous, or changed evidence fails compilation
after route selection. A logical Kotlin type or expected stack type never fills
a missing physical fact, and no `I<object>`-like construction is fabricated.

## Hostile closure and object boundary

Raw or unstamped `K`, an already broad parameter, a star projection, an
`Int`/`String` control-flow join, a mutable alias, a caller MethodDef type
parameter, and a callee MethodDef generic remain on their semantic or guarded
route. The fixture executes both sides of the joined and mutable controls so a
green result cannot hide one invalid construction.

The joined control exposed a pre-dispatch mapping bug rather than a missing
authority rule. An honest `object` destination was being remapped from the
logical type of a whole `IrWhen` to a fictitious constructed natural interface,
forcing its `Producer<int>` and `Producer<string>` arms through a nonexistent
common `Producer<object>` carrier. In rehearsal mode only, a generic-owner
expression entering an admitted semantic `object` boundary now stays at that
object boundary. The existing explicit-cast and typed-destination behavior is
unchanged; this repair supplies no exact provenance and cannot activate the new
external route.

Constructor, field, property, capture, generated-class, open-implementation,
argument-bearing, MethodSpec-bearing, split-nullable, and open-nullable forms
remain outside this checkpoint.

## ABI and identity invariants

This slice introduces no wrapper, proxy, alternate receiver, duplicate field
graph, or shadow state. Natural and semantic views retain one object identity
and one authoritative state. Existing emitted CLR MethodDefs and retained
foreign CLR metadata remain physical truth, while Kotlin IR/KLIB remains
logical truth.

The physical-library ABI remains 67, generic-owner artifact schema remains 21,
and compiler/runtime surface remains 60. The feature consumes existing `J`/`K`
authority and publishes no new record. Production Kotlin-owned generic owners
remain erased until an atomic cutover, and the exact inverse remains available.

## Focused evidence

The changed backend sources and directly affected authority model are verified
with:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin --no-daemon -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest" --no-daemon -q
```

Outcome: backend compilation passed. Direct JUnit XML audit of
`DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest` found **26/26 tests**
green, with zero failures, errors, or skips.

The two-assembly fixture is run in candidate mode through PSI and LightTree on
.NET 10 and Framework 4.8:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --no-daemon -q
```

Outcome: direct JUnit XML audit found **4/4 tests** green, one in each requested
runner, with zero failures, errors, or skips.
The candidate validator must observe exactly one natural `callvirt` in each of
`externalIntValue` and `externalStringValue`, no semantic/guarded call in those
methods, and no natural call in `externalBroadValue`, `externalStarValue`,
`externalJoinedValue`, `externalMutableValue`,
`externalCallerMethodGenericValue`, `externalMethodIntValue`, or
`externalMethodStringValue`.

The same four runners form the production-erased inverse without the rehearsal
property:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --no-daemon -q
```

Outcome: direct JUnit XML audit found **4/4 tests** green, one in each requested
runner, with zero failures, errors, or skips.
The inverse must publish no candidate `H`, `N`, `M`, `J`, or `K` record, create
no PE stamp, route witness, or retained external placement, and emit no candidate
natural or semantic owner.

## Next gate

Extend operation collection only through path-complete block/composite forms,
then bind a real caller-MethodDef `!!R` entry and additional independently
proven non-materializing consumers. Broader MethodSpec, input/result,
nullability, capture, property, class-node, MethodImpl, and Runtime/Stdlib shapes
remain separate gates; external `K` must stay declaration authority throughout.
