# Generic-owner physical caller-MethodSpec operation — 2026-09-03

This archive records the first exact natural call whose callee MethodSpec is
instantiated by an authenticated parameter of the current caller MethodDef. It
changes neither Kotlin semantics nor the production-erased ABI and publishes no
external physical-library record.

## Closed gap

The preceding checkpoint could retain a caller parameter and immutable local as
the verifier-visible carrier `!!R`, but operation binding accepted only current-
TypeDef parameters such as `!T` as MethodSpec arguments. The logical type `R`
could not fill that gap: caller `!!0`, callee `!!0`, and owner `!0` are distinct
metadata binders even when their indices are equal.

The local operation adapter now authenticates a bare call type argument against
the exact BOUND current `TYPED_ENTRY` MethodDef. It substitutes that symbolic
caller carrier into the separately selected callee MethodDef; it does not
rewrite either binder or infer one from emitted syntax.

## Bounded grammar

The caller is a final generic-class typed entry with one unconstrained,
invariant MethodDef parameter. Its call type argument is exactly that function's
bare outer-unmarked type-parameter symbol, and the ordinary argument is the
retained direct `!!0` value.

The selected callee is the BOUND MethodDef of a local, declared natural CLR
interface with one unconstrained TypeDef parameter. It is an instance method
with one unconstrained MethodDef parameter, exactly one
`DECLARATION_INDEPENDENT(!!0)` input, and a direct
`STRICT_OWNER_OUTPUT(!0)` result. The receiver already carries the exact
natural `I<!T>` construction. A direct-super call is excluded.

This yields the verifier signature:

```text
callvirt instance !0 class InlineMethodProducer<!0>::produce<!!0>(!!0)
```

Semantic or widened receivers, split-nullable results, owner-dependent inputs,
mixed or multiple MethodSpec vectors, nested or nullable method arguments,
constraints, `super`, captures, state, foreign declarations, and separate
consumers remain outside this gate.

## Executable and hostile evidence

The operation snapshot distinguishes the caller carrier from the existing
owner-bound MethodSpec proof: the new argument is
`METHOD_TYPE_PARAMETER(Method(current typed entry), 0)`, while the old path
remains `OWNER_TYPE_PARAMETER(Type(current owner), 0)`. Runtime execution pairs
`T = String` with both `R = Any?` and `R = Int`; the latter proves that a value-
type caller argument remains independent from the reference owner result.

One hostile function first invokes the same method through a logically widened
receiver and then through its independent exact alias. The widened call receives
no exact operation authority, while the exact call remains BOUND. The private
direct-receiver form remains outside the gate because caller-binder authority
cannot also invent receiver-entry authority. The existing caller-MethodDef plus
split-result form remains unavailable.

The emitted public caller contains exactly one natural `produce<!!0>(!!0)` call
and no owner-bound `produce<!0>`, semantic interface, reflective dispatch,
`object[]`, boxing, unboxing, or cast of `!!0`. Object identity and the single
authoritative producer state are unchanged.

## Verification

Backend and FIR test compilation passed. The three relevant backend suites
passed 115/115 tests: 96 physical-value model tests, 14 MethodDef-emission
comparison tests, and five CLR call-site binding tests. Direct JUnit XML audit
found one focused fixture in every PSI/LightTree and .NET 10/Framework 4.8
suite, in both candidate and production-erased inverse modes: four tests per
mode, with zero failures, errors, or skips. Each candidate assembly was
assembled and executed by the fixture.

The focused commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalValueModelTest" --tests "*DotNetGenericOwnerPhysicalMethodDefEmissionComparisonTest" --tests "*DotNetIlCallSiteSignatureBindingTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

## Next gate

Before this operation token authorizes another local/result consumer or a
broader route replacement, add one shared late call-edge seal which rebinds the
selected MethodDef, receiver construction, complete MethodSpec vector, and
ordinary argument carriers against the live emitter. It should cover existing
owner-bound and new caller-bound direct operations together rather than add a
caller-specific seal.
