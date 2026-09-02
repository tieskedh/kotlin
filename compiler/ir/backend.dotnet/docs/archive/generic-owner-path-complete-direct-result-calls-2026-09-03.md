# Generic-owner path-complete direct-result calls — 2026-09-03

This archive records the first identity-bound result-path plan for direct
generic-owner call results. It changes neither Kotlin semantics nor the
production-erased ABI and publishes no new external record.

## Closed gap

The physical-value shadow already followed identity wrappers, sequential
containers, and control-flow result edges. Final placement nevertheless
rejected every call-bearing `IrBlock`, `IrComposite`, and `IrWhen`, because its
only operation witness was one direct `IrCall`. Looking at every descendant
call would be wrong: calls in conditions, receivers, and arguments execute, but
do not produce the enclosing value.

The direct-result consumer now records one result-path plan. Its exact recursive
grammar is:

- a direct call leaf;
- an `IMPLICIT_CAST` or `IMPLICIT_NOTNULL` identity wrapper around another
  admitted result;
- a non-returnable `IrBlock` or `IrComposite` whose statement list contains
  exactly one expression, itself an admitted result; or
- an `IrWhen` with at least two non-statically-false reachable arms, a terminal
  true/else arm, no non-false arm after that terminal arm, and an admitted result
  in every reachable arm.

Every reachable leaf must be a distinct, parameterless, non-method-generic
natural `IrCall`. Its exact IR identity must own a final direct physical
operation whose selected MethodDef, produced carrier, guaranteed views,
selected lineage, and null state agree with the destination fact. Only an
actual `IMPLICIT_NOTNULL` on that path may refine `MAYBE_NULL` to `NON_NULL`.

The plan binds the initializer root and the ordered identities on its result
spine; it does not bind the whole child tree. Identity wrappers, admitted
containers/joins, result expressions, and leaf calls are on that spine.
Conditions, branch objects, receiver subtrees, and argument subtrees are not.
Before choosing the CLR local, final emission rewalks the live initializer and
requires the same root and ordered result-spine identities under the same
reachability grammar. It then independently resolves every leaf through the
ordinary call resolver. Each leaf must rebind the exact retained physical
operation and MethodDef, not merely a MethodDef with the same return carrier;
its live result carrier must also equal the authority-recorded destination
carrier. The ordinary value emitter then emits the original expression against
that fixed carrier. Containers and control flow contribute no carrier or view
authority themselves.

Condition calls deliberately do not contaminate the result plan. A missing
operation on any result arm rejects the whole destination and its transitive
immutable aliases; an operation belonging to a structurally equal sibling call
cannot substitute for the missing identity.

## Still unavailable

This first path grammar is result-only. A block or composite with a prefix
declaration or statement is rejected even when its final expression is an
otherwise valid call. Prefix locals do not yet have verifier-visible slots when
the enclosing destination is selected; supporting them requires ordered
emission-time obligations, not reconstruction from logical Kotlin types.

The plan also rejects returnable blocks, non-exhaustive control flow, duplicate
call identities, mixed call/read/null/bottom results, safe-call and Elvis
graphs, `try`, mutation, explicit casts, calls with ordinary inputs or
MethodSpecs, split-nullable layouts, super/intrinsic/semantic/foreign routes,
and calls whose live receiver/result cannot be independently resolved. Those
remain separate structural proofs.

The older split-nullable control-flow walker remains deliberately separate and
narrower. It recognizes only a flat exhaustive `IrWhen` whose result arms are a
direct call or one single-expression non-returnable `IrBlock`, and it carries
the independent payload/flag policy. That is a temporary proof restriction,
not a second fundamental path grammar. It should later reuse the same result-
spine traversal once every split leaf can retain and late-rebind its complete
operation, payload carrier, and out-flag obligation.

## Executable proof

The physical-value model covers direct, nested block/composite, and exhaustive
two-arm plans; missing per-arm authority; prefix-bearing and non-exhaustive
denial; transitive alias behavior; structurally equal replacement calls; and a
live operation/MethodDef or result mismatch on one arm. `IrComposite` is proven
only by this hand-built model.

The integration fixture adds
`InlineConstructedCallRoute<T>.sourceThroughPathCompleteControlFlow`. A call in
its condition selects between two braced result calls on already-live
`InlineConstructedSource<T>` parameters. Both `Int` and `String` execute both
branches and preserve the selected producer's object identity. The IL gate
requires two natural
`InlineConstructedSource<!0>.source(): InlineProducer<!0>` calls, one
`InlineProducer<!0>` destination local, and no boxing, cast, semantic
capability, or reflective dispatch in that method.

The final IR emitted for this source shape supplies `IrBlock`/`IrWhen`
evidence. It does not supply an `IrComposite`, so this checkpoint makes no
executable-composite claim.

Verification covers the physical-value model plus PSI and LightTree on .NET 10
and .NET Framework 4.8, with the same four-lane production-erased inverse. The
production run must publish no placement/operation evidence and no candidate
generic-owner TypeDef.

Backend source/test compilation and all 94 physical-value model tests passed.
Direct XML audit found one focused fixture in each parser/profile suite for the
candidate and again for the production-erased inverse: four tests per mode,
with zero failures, errors, or skips. The candidate IL validator checked the
two exact natural calls, typed destination local, and forbidden fallback
instructions described above; the inverse validator checked that the candidate
records and CLR-generic TypeDefs were absent.

The focused commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
```

## Next gate

Bind the real caller-MethodDef `!!R` entry without confusing its method binder
with owner `!n`. Prefix-bearing containers require their separate ordered
emission-obligation design. Broader MethodSpecs, mixed result leaves,
null/bottom/unknown joins, conversions, captures, properties, fields, state,
and new Runtime/Stdlib declarations remain later gates.
