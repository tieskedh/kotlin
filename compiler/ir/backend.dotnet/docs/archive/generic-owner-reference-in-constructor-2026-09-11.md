# Reference-contravariant constructor boundaries

This snapshot records the bounded rehearsal developed from `2976ddf152`.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); the
durable rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md#constructor-methoddef-seal).

## Finding and accepted refinement

The initial PE assertion reproduced a non-generic `ClosedInput` next to real
`Source<T>` and `Sink<T>` interfaces. Constructor planning treated every
logical variant interface parameter as a semantic-carrier hazard, including a
closed reference-only contravariant parameter. The later physical interface
policy could already preserve that parameter as a normal CLR construction.

The accepted refinement is deliberately limited to reference contravariance.
For example, a `Sink<Any?>` can be the same CLR object passed to `Sink<String>`
through native reference variance. A constructor with that parameter need not
erase its unrelated `C<T>` owner or `!T` field.

Planning consults an independently selected early complete-interface plan or
authenticated producer variance. Final interface admission must realize a
consumed early plan unchanged. Missing authority, open/value arguments,
projections, physically invariant logical variance, and nested hazards retain
the exclusion. The constructor and later interface lowering share the existing
negative carrier policy, but a negative query's false result is not positive
physical-view authority.

This does not alter Common constructors, initialization, delegation, state
selection, retained foreign metadata, Runtime/Stdlib, or artifact schemas.
Native/JS constructor lowerings retain logical parameter and initialization
ownership. JVM `IrTypeMapper.writeGenericType` explicitly omits generic
signatures for `Nothing` in non-contravariant positions; Wasm's type transformer
uses erased upper bounds and distinct bottom value/result handling. These are
useful semantic precedents, not a reusable CLR construction/subtyping rule.
The change stays in target rehearsal planning. Production uses the original
hazard predicate unchanged.
Interface lowering's extracted policy is likewise rehearsal-only and preserves
its previous behavior.

## Rejected broader proposal

The first proposal also admitted closed reference covariance. Its small
reference-only example passed, but this warning-free Kotlin case disproved
the completeness of the proposed admission criterion:

```kotlin
interface Source<out E> { fun read(): E }
val bottom = object : Source<Nothing?> {
    override fun read(): Nothing? = null
}
val widened: Source<String?> = bottom
```

Passing that value to the candidate's natural `Source<string>` constructor
parameter was rejected by the emitter: the actual object implemented the
`Nothing` construction, not the required string construction. No invalid IL
was emitted. The experiment did not establish that reopening the enclosing
owner introduced this existing output-view gap; it established that “closed
reference, with no primitive-value subtype” cannot prove the entire boundary.
This is legal covariance, not BK-1 or an unchecked cast. The broader proposal
was rejected rather than weakening the example or accepting an earlier cast
failure. Both nullable and non-nullable closed output constructor forms remain
negative admission tests.

The failed proposal is recoverable from the deliberately retained local stash
`58e22906b92514943a9dab5b2573f9f4483499f7`, titled
`Rejected broad closed-constructor rehearsal: Nothing covariance counterexample`.
It is not a completed feature and must not be applied as a green checkpoint.
The original arity assertion and bottom rejection are also recorded under
`D:\CodexTemp\closed-constructor-input-20260911\` as `baseline.xml` and
`hostile-bottom.xml`.

A separate attempted external `Sink<Int>` constructor call remained refused
because it lacked the required object-domain `L` seal. That evidence is
`unadmitted-value-constructor.xml` in the same directory. Such constructors
remain metadata/exclusion tests, not newly admitted executable routes. The
existing broad constructor with an authenticated object-domain signature is
still executed. Planner hints are negative-only: an erased owner does not by
itself prove that every constructor parameter was finally emitted as object.

## Verification

The custom fixture `genericOwnerClosedConstructorInput.kt` uses separate
producer, intermediate, and consumer Kotlin assemblies plus ordinary C#.
It checks reference contravariance, nullable references, identity, mutable and
immutable `!T` state, secondary `this` delegation, external base delegation,
producer-recorded interface variance, and a C# subclass. PE/reflection checks
require natural constructor parameters, typed int/string/nullable-value
fields, one store, and the actual inherited generic base. C# counts initializer
effects to detect duplicated constructor execution.

The negative metadata cases cover closed output and nullable-output views,
value widening, value/nullable-value arguments, open arguments, stars,
projections, logical `in` forced physically invariant by an unsafe output,
and nested output views. The inverse requires erased owners and absence of
every rehearsal epoch record.

The final rehearsal-physical gate passed on 2026-09-11:

| Lane | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | ---: |
| Candidate, PSI/LightTree and net48/net10 | 4 | 20 | 0 |
| Complete backend suite | 22 | 402 | 0 |
| Same production-erased inverse | 4 | 20 | 0 |

All six changed Kotlin files retained identical SHA-256 hashes through the
final backend/candidate/inverse sequence. All four XML roots were audited:
backend 402 and FIR2IR 20 were fresh; `dotnet.ir` 6 and integration 128 were
inherited and also had no failures, errors, or skips. The inherited full
production-erased checkpoint remains `3a2384f636` (2,821 tests). This is not a
new full aggregate, ABI-readiness claim, or complete Runtime/Stdlib closure.
Physical ABI 71, artifact schema 22, and runtime surface 62 are unchanged.

The scoped generator was run and both parser/profile runners contain the new
fixture. XML archives are in `D:\CodexTemp\closed-constructor-input-20260911\`:
`backend-402.zip`, `candidate-20.zip`, and `inverse-20.zip`. The five candidate
fixtures cover the new constructor, canonical state, complete natural
interfaces/separate compilation, invariant nullable SAMs, and split-nullable
results. Final commands were:

```powershell
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:backend.dotnet:test --rerun
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*testGenericOwnerClosedConstructorInput' --tests '*DotNet*BoxTestGenerated*testGenericOwnerCanonicalStateReference' --tests '*DotNet*BoxTestGenerated*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation' --tests '*DotNet*BoxTestGenerated*testGenericOwnerInvariantOpenNullableSamSeparateCompilation' --tests '*DotNet*BoxTestGenerated*testGenericOwnerSplitNullableResultSeparateCompilation'
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*testGenericOwnerClosedConstructorInput' --tests '*DotNet*BoxTestGenerated*testGenericOwnerCanonicalStateReference' --tests '*DotNet*BoxTestGenerated*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation' --tests '*DotNet*BoxTestGenerated*testGenericOwnerInvariantOpenNullableSamSeparateCompilation' --tests '*DotNet*BoxTestGenerated*testGenericOwnerSplitNullableResultSeparateCompilation'
```
