# Foreign overrides through identical physical boundaries

This snapshot records the bounded rehearsal fix developed from `9a40b8e9eb`.
Current state belongs in [`../../STATUS.md`](../../STATUS.md); the lasting rule
belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced correctness defect

A generic Kotlin class can implement a covariant interface through fixed
`Any?` inputs while returning a normal `Boolean`, `Long`, or `Unit`. Its class
family still has a semantic body, but the natural wrapper has the same physical
input and result carriers. The earlier foreign-override dispatch rule covered
owner-dependent or semantic interface results and missed this fixed-boundary
case.

Against the base commit, an ordinary C# subclass overriding `bool decide(object)`
was observed by the natural and exact Kotlin calls, but a Kotlin star call
returned the Kotlin base implementation. After correcting class dispatch, the
canonical interface bridge independently reproduced the same bypass: it chose
the protected body instead of the class's foreign-aware operation. Both were
correctness bugs, not merely missed speedups. The initial failure is retained
at `D:\CodexTemp\foreign-scalar-result-20260911\baseline.xml`.

A third hostile test added a new interface to a separately compiled subclass
while reusing its inherited implementation. Its generated bridge also bypassed
the C# override: only the producer's body hook had been eagerly bound, and no
independent class call existed to materialize the published capability slot.
That executable failure is retained in the same directory as
`inherited-interface-baseline.xml`. Inherited obligations now bind the class
operation eagerly, sharing the existing producer-recorded slot construction
used by direct calls; member selection no longer depends on unrelated calls.

## Rule and implementation

The existing compiler-owned semantic body/natural-wrapper relationship proves
the direction of forwarding. Identical physical boundaries then permit the
capability dispatcher to make an ordinary virtual call to the natural entry.
It forwards the existing receiver and arguments and retains the exact return
carrier. There is no override probe, reflection, boxing, additional state, or
new C# authoring obligation on this route.

Early admission requires fixed identical input carriers, a declaration-
independent result without a semantic-result conversion, no method binders,
and no split-nullable result. Emission independently seals the same owner and
complete final MethodDef signature for source, hook, and dispatcher. The result
domain alone does not supply that authority. Existing differing-boundary
families retain their probed semantic dispatch and broad-value behavior.

Canonical interface forwarding also respects class operation policy instead
of invoking a bare body. Same-owner bridges can call the private dispatcher;
inherited bridges use a published capability slot. Explicit nonvirtual `super`
calls still select the body. This follows the Common virtual/nonvirtual call
distinction; the target-specific work connects physically separate CLR slots.
The reviewed precedents are JVM `BridgeLowering.delegatingCall`, the common
JS/Wasm `BridgesConstruction` delegation, and Native
`NativeExportedBridgeCallDispatchLowering`'s explicit nonvirtual forwarding.
They preserve the same dispatch distinction rather than providing a reusable
implementation of the CLR paired-slot ABI.

All selected changes consume rehearsal-only context state or run behind the
rehearsal property. There is no production-selected mapping/emission, Common
compiler, Runtime/Stdlib, retained foreign metadata, schema, or artifact change.
The physical ABI remains 71, artifact schema 22, and runtime surface 62.

## Verification

The custom fixture is `genericOwnerForeignScalarResult.kt`, with separate
producer, intermediate, and consumer Kotlin assemblies and a separately
compiled ordinary C# consumer. It checks natural, exact, star, and interface
calls, inherited Kotlin implementations including a new interface contract,
Kotlin/C#/C# override chains,
nonvirtual Kotlin `super`, scalar/void results, and the production-erased
inverse. Physical checks inspect actual PE MethodDefs and dispatcher CIL.

The actual PE checks bind dispatcher names from producer records, verify
natural and semantic `bool`/`int64`/`void` signatures, require no foreign-override
probe, and inspect each dispatcher body as exactly
`ldarg.0; ldarg.1; callvirt <token>; ret`, without locals or extra sections.
Execution independently verifies that the call reaches the correct override.
The erased inverse has no rehearsal epoch records or generic TypeDefs.

The final rehearsal-physical gate on 2026-09-11 passed:

| Lane | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | ---: |
| Candidate, PSI/LightTree and net48/net10 | 4 | 16 | 0 |
| Complete backend suite | 22 | 402 | 0 |
| Same production-erased inverse | 4 | 16 | 0 |

All six changed Kotlin files retained identical SHA-256 hashes throughout the
final candidate/backend/inverse sequence. All four XML roots were audited:
backend 402 and FIR2IR 16 were fresh; `dotnet.ir` 6 and integration 128 were
inherited and also had no failures, errors, or skips. The inherited production-
erased full checkpoint is `3a2384f636` (2,821 tests), not a fresh full aggregate
for this bounded delta. ABI-readiness and complete Runtime/Stdlib closure are
not claimed.

The scoped runner generator was run and both parser runners include the new
fixture. The three XML archives are under
`D:\CodexTemp\foreign-scalar-result-20260911\`: `candidate-16.zip`,
`backend-402.zip`, and `inverse-16.zip`. The final commands were:

```powershell
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignScalarResult' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerAbstractForeignOutput' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignOverrideSeparateCompilation' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerSplitNullableResultSeparateCompilation'
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:backend.dotnet:test --rerun
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignScalarResult' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerAbstractForeignOutput' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignOverrideSeparateCompilation' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerSplitNullableResultSeparateCompilation'
```
