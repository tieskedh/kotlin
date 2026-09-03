# Generic-owner ordered-prefix MethodSpec result — 2026-09-03

This archive records the first bounded non-materializing direct-result container
whose compiler-generated prefix locals must be emitted before an exact natural
caller-MethodDef MethodSpec call. It changes neither Kotlin semantics, the
production-erased ABI, declaration admission, operation routing, nor state.

## Closed gap

FIR2IR inlines an explicit `kotlin.run` used by the fixture into a logical
carrier roundtrip of this form:

```text
IMPLICIT_CAST<T>(
    block<Any?> {
        val receiverAlias = source
        val markerAlias = marker
        IMPLICIT_CAST<Any?>(receiverAlias.produce<R>(markerAlias))
    }
)
```

The selected call was already an authoritative exact-natural
`I<!T>::produce<!!R>(!!R): !T`, and the shared call-edge seal already validated
its live MethodSpec. The earlier result-placement grammar nevertheless rejected
every prefix-bearing container because the two nested locals had no CLR slots
at the outer pre-emission boundary. Retaining only the outer `!T` would have
trusted slots which did not yet exist; mapping the logical roundtrip instead
would materialize through `object` and box value-type owner results.

## Bounded composition

The new form is exact-root-only. Its structural plan requires:

- exactly two immutable prefix variables, each initialized by one bare
  `IrGetValue`;
- the first prefix to be the call's bare dispatch-receiver read and the second
  its sole ordinary-argument read;
- one direct result call and exactly the balanced implicit
  `T -> Any? -> T` result spine, with both type operands checked;
- the already-BOUND local natural interface MethodDef
  `<R>(R): T`, instantiated only with the exact current caller MethodDef's
  unconstrained `!!R`; and
- an independently guaranteed receiver construction `I<!T>`, independently
  retained receiver/input prefix carriers `I<!T>` and `!!R`, and result carrier
  `!T`.

The permission is not enabled in recursive container or branch evaluation. An
extra/missing/effectful/mutable/wrapped prefix, a returnable block, control-flow
prefix, `IMPLICIT_NOTNULL`, unbalanced/wrongly typed cast, different receiver or
argument source, split/void result, widened/semantic/foreign route, other
MethodSpec, or carrier mismatch remains unavailable.

Prefix locals keep ordinary independent placement authority. Only the outer
result is withheld while local placement correlates both exact prefix tokens
with the already-selected operation. Failure removes the outer and every
dependent direct or split placement, but does not revoke an independently valid
prefix. Repeated or shared prefix identities invalidate every affected outer.
The live-tree rewalk retains the originally captured pair decision, so an
in-place mutation of the call receiver or argument cannot change the meaning of
an earlier plan.

## Emission obligation

The variable emitter binds the complete ordered plan before allocating any
prefix slot. It then:

1. emits each prefix variable once through ordinary local emission;
2. observes its exact identity-bound storage token and verifier type;
3. emits the bare physical call once, omitting only the proven implicit object
   roundtrip;
4. consumes the same authoritative operation's actual shared MethodSpec
   call-edge seal; and
5. stores and reloads the `!T` result through the ordinary outer local.

The scoped obligation requires exact prefix order and identity, selected route
identity, MethodDef, receiver, MethodSpec vector, ordinary inputs, direct result,
and completion. It selects or repairs none of those facts. No wrapper, proxy,
shadow state, fabricated construction, reflection dispatcher, boxing, or
semantic capability is introduced.

## Executable and hostile evidence

`genericOwnerInlineWidenedTemporary.kt` executes both independent binder
orientations:

- reference owner `!T` with value-type caller `!!R`; and
- value-type owner `!T` with reference caller `!!R`.

The IL validator requires one contiguous
`ldarg/stloc/ldarg/stloc/ldloc/ldloc/callvirt/stloc/ldloc/ret` spine and the
exact `I<!T>::produce<!!R>(!!R): !T` token, with no semantic dispatcher,
reflection, `object[]`, box, unbox, cast, or `isinst` path.

The model tests cover wrong cast operators and operands, wrapped receiver reads,
duplicate and in-place-mutated prefix sources, missing/wrong receiver and
marker carriers, absent operations, downstream aliases, shared prefix
identities, and preservation of independent prefix authority when an outer is
denied. The emitter-obligation tests cover missing, duplicate, reordered, or
wrong-route observations. The denial closure covers both direct and retained
split dependents.

## Verification

The four directly affected backend suites passed 121/121 tests: 97 physical-
value model tests, 14 MethodDef-emission comparison tests, five CLR call-site
binding tests, and five emitter-seal tests.

The focused fixture passed through PSI and LightTree on .NET 10 and Framework
4.8 in both candidate and production-erased inverse modes. Direct JUnit XML
audit found four tests per mode, with zero failures, errors, or skips. Every
candidate assembly was assembled and executed.

The commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalOperationEmitterSealTest" --tests "*DotNetGenericOwnerPhysicalValueModelTest" --tests "*DotNetGenericOwnerPhysicalMethodDefEmissionComparisonTest" --tests "*DotNetIlCallSiteSignatureBindingTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --no-configuration-cache -q --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary' --tests 'org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary'
```

The latest full production-erased checkpoint remains the inherited 2,621-test
gate recorded in `STATUS.md`; this rehearsal-only consumer claims no new
target-wide checkpoint.

## Next boundary

This closes one compiler-generated ordered container, not general prefix or
control-flow effects. Broader consumer categories, MethodSpec and ordinary-
argument/result shapes, null/bottom/unknown joins, returnable/protected
containers, captures, properties, class nodes, MethodImpls, and new
Runtime/Stdlib declarations still require their own structural authority.
