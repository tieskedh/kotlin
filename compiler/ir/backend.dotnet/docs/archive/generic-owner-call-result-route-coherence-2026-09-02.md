# Generic-owner call-result route-coherence follow-up — 2026-09-02

This archive records a soundness follow-up to the
[first retained constructed call-result slice](generic-owner-physical-constructed-call-result-live-slot-2026-09-02.md).
It changes neither Kotlin semantics nor production ABI and adds no external
physical authority; only the delta from that earlier proof is recorded here.

## Rejected optimization hypothesis

An immutable value may retain the exact physical construction
`Source<!T>` while Kotlin views it logically as `Source<Any?>`. That fact proves
that a CLR call through the exact interface is verifier-valid. It does **not**
prove that the exact natural entry and the Kotlin semantic entry have identical
behavior for every dynamic implementation.

A hostile implementation can own semantic object-domain state and expose a
write through an unsafe-variance route. After legal Kotlin covariance widens
`Source<Int>` to `Source<Any?>`, that route can replace its nested producer with
a `Producer<String>`. The semantic `source()` call must then return that same
producer. Invoking `Source<int>.source()` merely because the receiver once had
that construction would narrow too early or observe a different forwarding
path. This is the adversarial counterexample which defines the safety boundary;
the executable fixture below pins the resulting route decision rather than
materializing this mutating implementation.

Therefore an exact construction is sufficient for an already-authorized exact
natural route, but it is not by itself a semantic-equivalence certificate for a
logically widened open-interface call. Such widening remains on the compiler-
generated semantic capability, including its ordinary-C# natural fallback. A
future direct optimization requires both an exact final concrete receiver and
a producer-recorded certificate that its natural and semantic MethodDefs/
MethodImpl edges reach one authoritative body endpoint.

## Closed authority gap

The previous local placement consumer compared an independently predicted
produced/storage carrier with the ordinary live resolver result. A semantic
final route could still leave the same natural result in value shadow, allowing
the local to retain `Producer<!T>` even though emission selected an object-
domain call.

Direct call-result placement now additionally requires:

- the exact initializer `IrCall` identity;
- its post-routing authoritative natural-operation record;
- an operation-independent receiver root: a physically fixed parameter entry,
  the current physical receiver with independently recorded receiver/view
  evidence, or a direct constructor-result tail (possibly the final value of a
  block or composite), connected only by immutable identity-preserving aliases;
- the identical direct produced layout and a monotone provenance refinement
  which retains every operation-guaranteed view and never changes an existing
  selected lineage;
- either the identical null state or an actual `IMPLICIT_NOTNULL` transition
  from `MAYBE_NULL` to `NON_NULL`; and
- late agreement with the emitter's receiver slot and resolved MethodDef result.

An identity wrapper around a declaration which the emitter preserves for
foreign dispatch cannot publish direct-operation authority. Predicted object
storage cannot prove a natural receiver merely because provenance records that
the object supports such a view.

Call-bearing `IrWhen`, block, and composite initializers remain unavailable
until they have a path-complete set of operation witnesses. The older compiler-
temporary and nested-construction fallbacks likewise cannot reconstruct a call
result after final routing rejected it. This is deliberately conservative: a
predicate call which does not produce the result also blocks retained placement
until result-leaf dependency analysis replaces the whole-subtree boundary.

Denial is transitive across direct local aliases. Without that closure, a
semantic call local would correctly fall back to `object`, while its next
immutable alias could still receive the stale predicted `Producer<!T>` token
and fail compilation at the late live-slot check. A per-function identity graph
now propagates the denied direct-call dependency in linear worklist order.
Split-nullable pairs retain their separate per-call/path operation plan and are
not reclassified by this direct-result closure. Generic-array call-result
placement also remains an explicitly separate representation boundary.

## Executable proof

The physical-value model requires:

```text
no operation record                                  -> no direct-call token
record for structurally equal sibling call           -> no direct-call token
same parameterless/non-generic IrCall + equal result -> token
same IrCall + different result                        -> no token
operation-guaranteed view dropped / lineage changed  -> no token
input-bearing direct result -> immutable alias        -> neither token
call-bearing block -> immutable alias                 -> neither token
same call + actual !! null refinement                 -> token
same refinement without !!                            -> no token
denied call -> immutable alias                        -> neither token
admitted call -> immutable alias                      -> both tokens
```

The integration fixture retains the pre-existing exact happy path:
`Source<!T>.source()` returns `Producer<!T>` into an exact local with no boxing,
cast, semantic dispatcher, or identity change. Its new widened path instead
requires the guarded semantic dispatcher, two object locals (the call result
and a transitive immutable copy), no direct `Source<!T>.source()` instruction,
and returns the original producer object for each `Int` and `String`
substitution. A genuinely physical `Source<Any?>` control cannot mint the
enclosing caller's `!T` construction.

The verification commands were:

```text
.\gradlew.bat -q :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:test --tests org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -q "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -q --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
```

Direct XML audit found one model suite with 94 tests and four FIR2IR suites with
four tests in each candidate/inverse run. Every run had zero failures, errors,
or skips. The candidate executions cover PSI and LightTree on .NET 10 and
Framework 4.8; the production-erased inverse without the rehearsal property
covers the same matrix.

## Remaining boundary

The next direct-call feature is not “try the widened interface again”. It is the
producer-side semantic-equivalence certificate for a final concrete
implementation, followed by consumer binding against frozen TypeDefs,
MethodDefs, and MethodImpl rows. Only then may exact final concrete provenance
authorize the natural route.

Transparent result-only containers need a path-complete operation collector.
A direct constructor-result tail is admitted only as the exact receiver root
above; general constructor-produced value placement, external constructed-
result TypeDef records, caller-
MethodDef binders, arrays, captures, fields, properties, and broader state keep
their independent gates. Production remains erased until the complete atomic
cutover and inverse rollback pass.
