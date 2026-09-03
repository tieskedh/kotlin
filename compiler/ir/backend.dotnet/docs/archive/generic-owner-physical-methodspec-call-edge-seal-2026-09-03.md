# Generic-owner physical MethodSpec call-edge seal — 2026-09-03

This archive records the first shared final-emitter seal for already-authoritative
local exact-natural MethodSpec operations. It changes neither Kotlin semantics,
the production-erased ABI, physical-library records, operation admission, nor
local/result placement.

## Closed gap

The final operation shadow could already bind owner-parameter `!T` MethodSpecs
and, in the latest bounded form, a current caller-MethodDef `!!R` MethodSpec.
The split-nullable local-placement consumer had its own narrow emitter check,
but an ordinary direct-result call did not independently rebind the complete
operation after the final call resolver selected its actual IL token.

The emitter now receives the identity-keyed authoritative operation map. For
one enrolled local natural call with a non-empty MethodSpec, it resolves the
call once, constructs one verifier-visible live edge, and seals that edge before
argument coercion or instruction emission. The seal returns an ephemeral bound
edge; it never selects a route or grants a carrier, result, or storage policy.

## Independent binder scopes

The seal keeps declaration and use-site binders separate:

```text
selected callee TypeDef !n     -> selected natural interface TypeDef
selected callee MethodDef !!m  -> selected physical MethodDef
current caller TypeDef !n      -> current emitted TypeDef
current caller MethodDef !!m   -> current emitted MethodDef
```

The selected MethodDef's open receiver, ordinary parameters, and result are
checked in the callee scope. Its required constructed receiver, complete
MethodSpec vector, and instantiated parameter/result signature are checked in
the caller scope. Equal numeric indices therefore cannot make owner `!0`,
callee `!!0`, and caller `!!0` interchangeable.

## Conjunctive live edge

A successful seal requires all of the following to agree:

- exact selected local MethodDef identity and IR declaration identity;
- exact declaring natural TypeDef, category, arity, and emitter class identity;
- instance/virtual dispatch and the complete open MethodDef signature;
- the required receiver construction and the independently rendered MemberRef
  owner token;
- the complete MethodSpec vector;
- every instantiated ordinary parameter and the direct or split result;
- the producer result layout and null state; and
- the live receiver's unique natural construction plus every direct ordinary
  argument carrier before boxing, casts, or other coercion.

An enrolled mismatch is an internal declaration/emission conflict. It never
falls back to a semantic call. Intrinsic, foreign-dispatch, capability, and
discard emitters reject an enrolled operation if they would bypass the seal.

The shared carrier matcher supports the bounded carrier vocabulary already
published by the operation query. It does not widen MethodSpec admission.
Retained-foreign operations and local/external semantic-equivalence witnesses
remain on their existing independent metadata and `K`/`J` seals.

## Executable and hostile evidence

The model fixture proves owner-bound and current-caller-bound MethodSpec edges
through the same query, plus a positive split-nullable result. Hostile variants
change the caller MethodDef identity, swap `!0` and `!!0` in the open signature
or MethodSpec, change the rendered owner token, live argument, dispatch mode,
result, split flag, or produced null state; each conflicts.

`genericOwnerInlineWidenedTemporary.kt` supplies the real compiler path. Its
single four-lane run includes:

- `InlineMethodProducer<T>.produce<T>(T): T`, whose MethodSpec is owner `!T`;
- `InlineMethodProducer<T>.produce<R>(R): T`, whose MethodSpec is caller
  `!!R`; and
- `InlineMethodLookup<K,V>.lookup<R>(K,R): V?`, whose owner-bound MethodSpec
  composes with `SplitNullable(!V, out bool)`.

The existing IL obligations continue to require the exact natural calls and
unboxed value paths. Widened controls remain semantic. One receiver identity
and one authoritative state are unchanged.

Production remains structurally erased. The non-rehearsal boundary now also
requires the authoritative operation map to be empty, so normal emission pays
no generic-owner authority and cannot accidentally enter the seal.

## Verification

The four directly affected backend suites passed 119/119 tests: 96 physical-
value model tests, 14 MethodDef-emission comparison tests, five CLR call-site
binding tests, and four new emitter-seal tests. Direct JUnit XML audit found one
focused integration test in every PSI/LightTree and .NET 10/Framework 4.8 suite
for both candidate and production-erased inverse modes: four tests per mode,
with zero failures, errors, or skips. Every candidate assembly was assembled
and executed by the fixture.

The focused commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalOperationEmitterSealTest" --tests "*DotNetGenericOwnerPhysicalValueModelTest" --tests "*DotNetGenericOwnerPhysicalMethodDefEmissionComparisonTest" --tests "*DotNetIlCallSiteSignatureBindingTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
```

The latest fresh full production-erased checkpoint remains the inherited
2,621-test gate recorded in `STATUS.md`; this bounded rehearsal-only seal does
not claim a new target-wide checkpoint.

## Next gate

The operation is now safe to feed another bounded consumer. Continue with a
structural non-materializing consumer rather than widening MethodSpec
admission. The first recorded candidate is the ordered prefix-bearing direct-
result container: preserve exact call-result authority across compiler-owned
prefix statements only through an emission-order obligation which validates
the prefix and result spine, never by reconstructing a carrier from logical
Kotlin types.
