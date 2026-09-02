# Generic-owner semantic-equivalence certificate — 2026-09-02

This archive records the bounded rehearsal checkpoint which permits one
logically widened Kotlin producer call to use a natural CLR-generic entry on an
exact local final implementation. It is evidence and chronology, not the
normative design; current rules live in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Closed question

An exact construction such as `Producer<int32>` proves that a natural call is
verifier-legal. It does not prove that the natural call and the Kotlin semantic
route select the same dynamic implementation. This checkpoint closes that gap
for one bounded family without treating construction, selected lineage, or a
logical Kotlin type as semantic-equivalence evidence.

The admitted route requires all of the following:

- one exact local final implementation carrier with approved physical value
  provenance;
- one complete declaration family selected by symbol and recorded physical
  identity;
- the bounded broad-universal, output-only producer operation;
- replacement of the already selected conservative semantic target in the same
  routing transaction;
- a final-emission obligation tied to the exact call and implementation;
- two observed generated bodies: interface semantic dispatcher to class
  semantic dispatcher, then class semantic dispatcher to the typed
  implementation entry;
- positional pure-forwarder bodies whose instantiated receiver, parameters,
  result layout (including the required absence of a split-nullable flag),
  TypeDef, MethodDef, and MethodSpec all match the final target headers; and
- final emitter rebinding of the unchanged call identity, exact implementation
  carrier, natural owner view, and MethodDef identity.

Missing, duplicate, stale, ambiguous, or conflicting evidence fails closed.
Stars, projections, mutable or joined broad flows, open and foreign
implementations, semantic hooks, split-nullable/open-nullable results, and
receivers without the exact final implementation carrier retain semantic
dispatch.

## ABI record

Physical-library ABI 67 adds the orthogonal `K` semantic-equivalence
certificate. `K` names a versioned forwarding proof and is identity-bound to
the exact same-library `J` sealed-family record. It does not duplicate `J`,
publish value provenance, or make selected lineage authoritative.

An exportable family publishes `K` only after final emission satisfies its
obligation. Private and executable-only families can satisfy the local proof
without acquiring public ABI. Production-erased mode publishes no `K`, creates
no obligation or emitter witness, and retains the previous semantic call.

The external declaration index can load and join raw `J`/`K`, but routing does
not consume external `K` yet. The objective PE-authentication follow-up below
closes the required metadata/body validation without activating that route.

## Identity and state invariants

The slice introduces no wrapper, proxy, alternate receiver, shadow state, or
fabricated CLR construction. Natural and semantic routes operate on the same
object and authoritative state. Existing final CLR MethodDefs remain physical
truth, Kotlin IR/KLIB remains logical truth, and retained foreign CLR metadata
is unchanged. The generic-interface production ABI remains erased pending an
atomic cutover, and inverse mode remains exact.

## Focused evidence

Backend compilation and the complete backend model suite passed after final
route and emitter hardening:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin -q --no-daemon
.\gradlew.bat :compiler:backend.dotnet:test -q --no-daemon
```

Direct XML audit reported 18 suites and 342 tests, with zero failures, errors,
or skips.

The local hostile fixture and the dedicated two-assembly certificate fixture
were then run through PSI and LightTree on .NET 10 and Framework 4.8:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -q "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests '*testGenericOwnerInlineWidenedTemporary' --tests '*testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --no-configuration-cache

.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -q --tests '*testGenericOwnerInlineWidenedTemporary' --tests '*testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation' --no-configuration-cache
```

Candidate and production-erased inverse modes each reported eight tests with
zero failures, errors, or skips. The separate producer publishes exact `J` and
`K`; its consumer republishes neither record. In inverse mode neither assembly
publishes `K` and the semantic route remains present.

The much larger pre-existing
`genericOwnerForeignOverrideSeparateCompilation` fixture still fails on the
unchanged base and on this feature at the same nested contravariant read. It is
not evidence for or against this certificate and was not counted as a green
gate.

## Objective PE-authentication follow-up

External ingestion now binds all four `J` TypeDefs, their complete GenericParam
and direct-edge facts, all six MethodDefs and their Param rows, and both
MethodImpls to objective producer-DLL metadata. It then reads only the two
dispatcher bodies named by each successfully bound family and checks the exact
positional forwarding grammar through the same typed entry. A method-generic
body must call through a MethodSpec whose complete arity-`n` vector is exactly
`!!0` through `!!(n-1)`; direct and MemberRef encodings remain physical choices
only where they name the same MethodDef and owner construction. A split-nullable
`J` MethodDef separately requires both the exact trailing `bool&` signature and
its corresponding `out` Param row.

Ordinary metadata reads deliberately do not project MethodSpecs or bodies. The
selected-body API retains one open PE handle while declaration facts,
MethodSpecs, and bodies are read. Counts, per-row bytes, aggregate bytes, and
retained signature-component counts (including custom modifiers and array
shape entries) bound the untrusted object graph. As for every compiler
classpath input, the DLL must remain immutable during compilation; the open
read is not a concurrent-mutation contract.

A successful collection of validations creates an opaque ephemeral stamp. The
stamp is tied to the normalized DLL path and Assembly identity and contains
copies of the exact immutable `K`/`J` entries it authenticated. An external
library must rejoin those entries by same-library authority before its
validated index can expose them. The raw certificate index remains separate;
no unstamped certificate can reach the query. Extra selected bodies are
irrelevant, while every body owned by an authenticated `K` must occur exactly
once.

The cross-module entry owns the DLL read, body selection, validation, and stamp
construction, so a caller cannot pair an independently supplied metadata
snapshot with another path. The final collection also enforces a role-scoped
bijection between logical authority and physical MethodDef/MethodImpl handles.
Families for different implementations may share interface slots only when
they name the same logical interface member; families for different interface
members may share implementation-owned rows only when they name the same
implementation member. The interface-specific dispatcher and MethodImpl remain
owned by the complete `J` relation. TypeDefs, MemberRefs, TypeSpecs, MethodSpecs,
and byte-identical bodies are not falsely treated as member-row ownership.

This follow-up changes no route. The query has no operation-routing consumer.
The separate consumer fixture proves that its actual external-library
configuration receives and rejoins both non-empty, pipeline-created stamped
families, then asserts that ordinary and method-generic widened calls retain
semantic dispatch. The production-erased producer in the inverse publishes
neither `J` nor `K` and therefore creates the empty stamp.

Hostile model evidence rejects altered TypeDef flags, arity and edges; altered
MethodDef flags, signatures and body presence; cross-wired or extra
MethodImpls; wrong MemberRef owners; wrong call tokens, MethodSpecs, generic
vectors, argument order, boxing types and opcodes; local signatures, extra
method sections, non-managed body kinds, missing/duplicate bodies, trailing IL,
cross-snapshot bindings, cross-family physical-row aliases, malformed
split-nullable Param
rows, and reuse of a stamp for another file. Positive role-sharing evidence
retains only rows which have the same role-qualified logical authority. A
legal open local MemberRef, both `call` and `callvirt` on the admitted final
sealed family, and unrelated selected bodies remain accepted because none
changes the recorded proof.

### Focused follow-up evidence

The repaired backend outputs and focused validator compiled and passed:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin --no-daemon --console=plain -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerNaturalMethodDefMetadataValidatorTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticEquivalenceForwardingEvidenceTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticEquivalenceMetadataValidatorTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSealedFamilyTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSemanticEquivalenceCertificateTest" --no-daemon --console=plain -q
```

Direct XML audit reported 76 tests across the six affected backend suites with
zero failures, errors, or skips. Eleven belong to the dedicated validator and
include cross-family role-bijection and legitimate-sharing cases, plus hostile
split-nullable `Param`-row variants. The existing physical-reader integration
fixture was extended with a
real MethodSpec and selected MethodDef body:

```text
.\gradlew.bat :compiler:tests-integration:dn --tests "org.jetbrains.kotlin.cli.DotNetLibraryIntegrationTest.testReadsPhysicalClrMetadataAndSignatures" -q
```

It reported one passing test. Finally the extended two-assembly fixture was
run in candidate mode and as the production-erased inverse, each with the four
exact PSI/LightTree and .NET 10/Framework 4.8 runner filters:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --no-daemon --console=plain -q

.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetFrameworkBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --tests "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetFrameworkBoxTestGenerated.testGenericOwnerSemanticEquivalenceCertificateSeparateCompilation" --no-daemon --console=plain -q
```

Each mode reported four tests with zero failures, errors, or skips by direct
XML audit. The candidate producer authenticated both its arity-zero MemberRef
and arity-one MethodSpec body chains; its separate consumer received the
PE-stamped query but retained semantic dispatch. The inverse published neither
`J` nor `K` and executed the same logical cases.

## Next gate

Consume only PE-stamped external `K` in the bounded external equivalent of the
local exact-final route. The consumer must independently prove the exact
receiver construction and complete operation facts; the stamp is declaration
authority and never value provenance. Raw `K`, broader receiver flows, and all
currently excluded result/implementation shapes must remain semantic.
