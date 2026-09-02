# Generic-owner constructed-call live-result validation — 2026-09-02

This archive records the late-authority repair for a direct constructed-generic
call result. It changes neither Kotlin semantics nor the production ABI, and it
consumes the candidate's existing constructed-producer role without changing
the external ABI schema.

## Boundary

The generic-interface rehearsal already classifies and emits a parameterless,
non-method-generic natural MethodDef whose result is a one-level interface
construction such as `InlineProducer<!T>`. Local BOUND declaration authority,
however, admitted only a direct owner-parameter result. The authoritative
operation and final value-flow analyses therefore could not consume that
already-emitted constructed result.

The local callable binder now consumes the result TypeDef only when it is an
already-BOUND natural interface, and constructs its result solely from the
current natural owner's recorded type parameters. Publication admission is
deliberately narrower than the recursive symbolic-carrier vocabulary: one
non-null construction, direct invariant uses of current-owner parameters, no
ordinary inputs, and no MethodSpec. It neither invents a TypeDef nor promotes
the member into the separate complete-implementation grammar.

The late emitter hand-off also previously validated constructed call
initializers through the whole expression's mapped Kotlin type. That mapper can
reconstruct the desired construction independently of the MethodDef which will
actually put a value on the CLR stack, so a stale physical signature could
agree with its own logical reconstruction.

The retained-carrier token now records `DIRECT_CALL_RESULT_CARRIER` for the
already-admitted direct `IrCall`, allowing only nested `IMPLICIT_CAST` and
`IMPLICIT_NOTNULL` identity operators. The emitter reconstructs the expected
TypeDef plus current physical-owner parameters, invokes the same resolver as the
ordinary physical-call path, rejects intrinsic and split-result calls, and
requires the live verifier-visible result carrier to be exactly equal. Missing
or different results fail closed. The query is authoritative only for the
narrow call grammar whose earlier operation proof excludes capability and
foreign dispatch; executable IL additionally pins that condition.

This remains a validation rule rather than a source of provenance. Local BOUND
declaration authority must first select the natural MethodDef, receiver
provenance must already guarantee its exact construction, the final route may
not be semantic, and produced and storage carriers must already agree.
MethodSpecs, super calls, constructors, genuinely broad receivers without an
already-guaranteed exact physical view, stars, projections, mutable flow,
conversions, split results, foreign constructions, and unsupported joins gain
no authority.

## Executable proof

The physical-value model reverses the independent late observations:

```text
whole expression = object, live call result = I<!T>  -> retain I<!T>
whole expression = I<!T>, live call result = object  -> fail closed
live call result absent                              -> fail closed
```

The existing `genericOwnerInlineWidenedTemporary.kt` fixture starts at an exact
constructed `InlineConstructedSource<!T>` parameter whose entry slot was proved
by the preceding live-slot closure, calls the natural `source()` MethodDef
returning the independently CLR-representable construction
`InlineProducer<!T>`, and copies that result through an immutable local.
No declaration, package, or member name is added to compiler logic. Keeping the
receiver as an entry parameter also prevents this proof from depending on the
separate, still-open selection of an implemented interface view from a concrete
current receiver. The runtime checks both `Int` and `String`
substitutions, requires the returned producer to remain identical with `===`,
and then calls it normally. Placement comparison requires
`PHYSICAL_VALUE_RETAINED_PRODUCER`. Emitted IL requires exactly:

```text
callvirt instance InlineProducer<!0>
    InlineConstructedSource<!0>::source()
stloc callResultNaturalAlias
ldloc callResultNaturalAlias
ret
```

No boxing, unboxing, cast, semantic capability, or runtime member dispatcher is
allowed in that method. With rehearsal disabled, none of the new arity-bearing
candidate TypeDefs or uses may appear.

Verification:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The model has 94 green tests. The focused candidate and fresh production-erased
inverse each execute four tests: PSI and LightTree on .NET 10 and Framework 4.8.
All have zero failures, errors, and skips.

## Remaining boundary

This repair proves only a local executable-only producer. It does not publish or
consume the constructed result target through the external N/physical-ABI
record, and therefore makes no separate-compilation claim. That external schema
boundary must close before the shape can count toward production cutover.

Ordinary resolution is not a universal model of every special call emitter.
The admitted exact receiver/no-input shape structurally excludes those emitters,
and the IL gate pins the final instruction. Any broader admission must share an
explicit final-route predicate or witness with emission rather than infer it
from this proof. In particular, a logically widened immutable receiver may
retain an exact physical construction in provenance, but the ordinary emitter
does not yet consume that operation witness; the late check therefore rejects
that broader case instead of guessing. Closing that exact-view composition is
the next direct-call boundary. Constructor allocations and transparent
block/composite containers still use their previous whole-expression boundary
and require separate live observations. Caller-MethodDef `!!R`, captures,
properties, state, and new Runtime/Stdlib declarations remain independent later
proofs.
