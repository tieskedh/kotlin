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

The external declaration index can load and join `J`/`K`, but routing does not
consume external `K` yet. A separate consumer must first validate the referenced
PE TypeDefs, MethodDefs, MethodImpls, and bounded dispatcher bodies objectively;
matching serialized records alone are not sufficient authority.

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

## Next gate

Implement a bounded objective PE method-body validator for the exact `J`/`K`
forwarding graph. External `K` consumption must remain disabled until that
validator rejects altered rows, altered bodies, ambiguous MethodImpls, and
stale or contradictory certificates in a separately compiled assembly.
