# Common input barriers across ordinary foreign overrides

This bounded rehearsal starts from `5ff1de08d0` and is verified after the
production class-slot repair `b35b6f646c`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced boundary and decision

The source-built candidate census after that checkpoint has 225 error lines,
down from 228: three projected-allocation failures disappeared. These are
diagnostics, not independent bugs or a completion percentage. Read-only
instrumentation of final owner admission identifies AbstractMap.get and
AbstractCollection.containsAll as unsupported foreign semantic overrides.
Their anonymous children's missing generic base parameters are downstream,
not evidence for a new capture exception or a stale physical-view cache.

An ordinary foreign subclass may override only the natural typed member.
Previously its dispatcher could forward identical natural/semantic input
carriers, but an object-domain candidate could not reach an owner-parameter
input. Common SpecialBridgeMethods already records whether an argument may be
checked and what an incompatible argument returns. This policy, not a member
name or an arbitrary strict K, authorizes the new conversion.

The bounded slice requires one Common NULL-default checked argument, a bare
owner parameter, no method binders or primitive-bound shortcut, and a direct
nullable owner result. At emission the actual input vectors must bind exactly
object to the recorded !n. Owner arity, binder index/kind, both semantic entries,
family identity, MethodSpec constraints and result contracts remain sealed.
Only the ordinary foreign-override branch performs the check and unbox/cast.
An incompatible input returns the authorized null without calling that override.
The Kotlin branch retains its broad input and one authoritative state.

JVM BridgeLowering and JS BridgesConstruction use the Common special-bridge
policy; Wasm inherits that JS construction. Native BridgesBuilding has the
analogous TypeSafeBarrierDescription. No Common source was changed. CLR
reification requires the additional actual-MethodDef compatibility check.

## Null and result authority

The first non-null-bound foreign test incorrectly expected a Kotlin intermediate
K : Any declaration to reject null before an ordinary C# override. A real JVM
probe using the repository's kotlinc-jvm 2.5.255-SNAPSHOT and JDK 21 disproves that
expectation: the Java get(Object) override accepts null and is called once.
javap shows that the Kotlin intermediate's null prelude belongs to its body;
the foreign override replaces that body. The probe sources and output are kept
with the evidence below.

The CLR route likewise tests the actual physical input. Null is compatible
when boxing default(!n) yields null: references and nullable value constructions
can pass it; a non-nullable value construction cannot. Kotlin-only bodies keep
their Common null checks. This changes neither Kotlin generic upper bounds nor
BK-1 and does not create an exact-provenance fact for unrelated values.

The existing Runtime Map<K,V>.Get MethodDef still returns object. It must not
become split merely because Kotlin says V?. The fixture separately declares
TypedLookup<K,out V>.get(K):V?, with a natural !V result and out bool. A single
Store implements both; explicit MethodImpl adaptation preserves each interface
slot. A second ObjectResult implements only the old Runtime contract. The
input conversion composes with either result layout without a new combined
member role, a Runtime edit, or a Map-specific compiler branch.

## Executable scope and limits

The fixture has separate Kotlin lib/middle/main assemblies and an ordinary C#
consumer/implementation assembly. It covers direct, inherited and deeper C#
overrides, closed and generic Kotlin intermediates, nonvirtual super calls,
exact/widened/star routes, incompatible inputs which must not call C#, null,
nullable-value keys/payloads, reference keys and nominal value-class arguments.
Direct C# calls execute both interface layouts on the identical object. PE
checks assert the natural !K input, !V plus bool& result, out metadata and the
unchanged object-result MethodDef.

Store keeps exactly one typed !K field and one object value field. The latter
is necessary for its legal widened replace operation; the test performs that
write and reads the new value through the semantic routes. No wrapper, proxy,
shadow state, hidden C# source ABI or fabricated construction is introduced.
The negative Strict owner has a similar input/result/state shape but lacks a
Common barrier and remains erased. Two model tests reject changed binders,
wrong arity, extra parameters and unproved physical conversions.

This is not arbitrary strict-input narrowing, nested-interface conversion,
containsAll admission, a public semantic-overload rule, a Runtime Map split
migration, complete stdlib closure or a performance measurement. Other Common
default kinds and more complex checked argument contracts remain separate.

## Verification

On the repaired base, the final candidate passes 48 tests in four suites and
the backend passes 411 tests in 24 suites. The matching production inverse
passes all 48 tests in four suites, including the originally failing
specialized Kotlin override. Direct XML audit finds zero failures, errors or
skips. The seven semantic source SHA-256 hashes match across both final gates.

The final serial matrix uses `--max-workers=1 --no-configuration-cache -q`,
`-Pkotlin.dotnet.genericOwnerRehearsal=true`, the actual backend test task with
`--rerun`, and FIR2IR's actual dotNetTest task with `--rerun`. Exact generated
methods, prefixed with `testGenericOwner`, are:

- ForeignBarrierInput
- ForeignSplitResult
- ForeignNullableInput
- ForeignOwnerInput
- ForeignOverrideSeparateCompilation
- ForeignScalarResult
- AbstractForeignOutput
- RuntimeMapSeparateCompilation
- SplitNullableResultSeparateCompilation
- FixedCarrierMultiInput
- HardestModelOracle
- HardestModelOracleSeparateCompilation

Each runs in the PSI and LightTree box classes on Framework 4.8 and .NET 10.
The inverse uses the same filters without the rehearsal property and asserts
absence of rehearsal records and candidate generic TypeDefs in the new fixture.

The full production checkpoint `b35b6f646c` (2,914 tests) is inherited, not rerun
or inflated by the focused matrix. The new policy query returns null outside
rehearsal; the new emitter branch requires that policy. Existing production
paths and signatures are structurally unchanged. ABI 71, artifact schema 22
and surface 62 are unchanged. The validator is fixture-local.

Evidence directory: `D:\CodexTemp\owner-admission-20260912`. It retains admission
and actual-MethodDef diagnostics, the initial red custom fixture, the corrected
JVM null probe, and one-lane Kotlin/C# results. `semantic-hashes.json` freezes
seven semantic files; `run-matrix.ps1` defines the exact serial matrix.

The first candidate matrix passed 48 tests plus 411 backend tests. Its inverse
exposed the pre-existing erased class-slot cast defect in all four lanes. The
feature was preserved in stash `a5a66cf47bf4279d9b5b92524fb4c9922863f77e` while
that defect was separately repaired and fully verified. See the
[class-slot repair archive](covariant-bridge-input-policy-2026-09-12.md).
The original failure assertions remain intact. Final candidate/inverse evidence
comes from the repaired base, not the earlier candidate-only checkpoint.
The `post-b35` subdirectory retains its own `semantic-hashes.json`, serial
run/audit scripts, `candidate-48-backend-411.zip`, `inverse-48.zip` and
`verification.json`. Restoring the stash normalized the new fixture's line
endings; comparison with its saved blob confirms unchanged source content.
The original pre-repair evidence remains intact.
