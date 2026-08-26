# Generic-owner physical-value pre-remap alias shadow

Date: 2026-08-26

## Context

The first integrated physical-value shadow observed only the final-routing
epoch and object locals. It proved that exact provenance can survive object
storage, but it could not answer the placement question which the existing
compiler-temporary recognizer answers late in emission: may an immutable local
retain the exact constructed reference carrier produced by its initializer?

Answering that question only after semantic remapping has survivor bias. It
also risks making the destination's logical generic type circular evidence for
its own CLR carrier. The next slice therefore needed the same analysis before
semantic remapping, without changing any actual local slot or emitted IL.

## Decision

The production-inert shadow now observes two immutable IR epochs:

- `PRE_SEMANTIC_REMAP`, immediately after the authoritative body moves from
  its source member to the semantic hook and before that body is remapped; and
- `POST_FINAL_ROUTING`, after all current body-producing lowerings and the
  final routing fixpoint.

One phase-independent engine analyzes both bodies. The current receiver starts
from the admitted early physical construction `C<!T>` and its independent
`CURRENT_PHYSICAL_RECEIVER` guarantee. A regular object-domain parameter starts
as `object` with unknown views. No IR declaration origin participates.

A generic local contributes only `Deferred` storage. The engine selects an
exact local carrier only when the initializer already produces one direct
null-reference carrier and its provenance independently guarantees that same
construction. The destination does not prove that construction. An object
local instead has fixed object storage; reading it later produces `object`
while retaining independently justified views.

The shadow consequently predicts the same exact carrier for an immutable
source alias and an otherwise identical compiler alias. This is a prediction
about the architecture model, not a change to the emitter's actual local slot.
The existing origin-based emitter recognizer remains authoritative pending a
separate comparison.

## Fail-closed transfer grammar

The bounded engine:

- pre-scans actual `IrSetValue` definitions and rejects a multiply defined or
  mutable local rather than pinning storage from its initializer;
- recursively rejects stars and every non-invariant projection;
- preserves an implicit cast or not-null wrapper only for an already direct
  `NULL_REFERENCE` carrier and a known object/current-owner reference target;
- cannot treat boxing, unboxing, nullable materialization, arrays, value
  classes, split-nullable results, or scalar flow as identity;
- accepts only variables and recursively transparent block/composite prefixes
  when evaluating a container result; and
- rejects calls, branches, returns, throws, loops, or another opaque prefix
  instead of treating the lexically last expression as the semantic result.

Unsupported analysis publishes no partial carrier, guarantee, lineage, or
null fact. Final analysis is marked complete only after all post-routing
snapshots have been appended, and a successful rehearsal backend output
requires that completed final epoch. Zero admitted pre-remap snapshots remains
a valid result for a module without a matching body.

## Hostile proof

The probe exercises every named value in both epochs. It proves:

- exact source widening and an exact alias predict `C<!T>` production and
  `C<!T>` storage with the current-receiver guarantee;
- exact-to-object placement keeps produced and storage carriers distinct;
- an object reread remains physically object while retaining only the prior
  guarantee;
- a genuinely broad input remains object plus unknown views;
- a broad generic value flowing to Deferred storage cannot synthesize the
  current receiver's construction from its destination;
- direct and nested stars plus an explicit non-invariant projection fail
  closed;
- mutable flow reaches genuinely different runtime constructions but cannot
  select the initializer's construction;
- an explicit cast from exact object storage cannot launder an exact carrier;
  and
- ordinary branch flow and an inline early-return block cannot use a lexical
  trailing `this` as false exact proof.

The cast runtime assertion deliberately permits either null or the original
identity. Generic-class BK-1 remains a separate unfinished gate; this fixture
is not evidence that BK-1 is implemented.

Two initial hostile drafts were correctly rejected by existing emission
because they attempted to materialize `C<string>` directly in a source slot
whose current physical expectation was `C<object>`. The final probe uses the
already existing broad and exact object identities for its hostile joins. No
emitter admission was added merely to make the shadow test run.

## Verification and boundary

The final focused matrix passes eight lanes: rehearsal enabled and explicitly
disabled, each through PSI and LightTree on Framework 4.8 and .NET 10. The
production-off lanes publish no snapshots. The final normal target aggregate
exits zero. Direct XML audit covers 194 suites and 2,431 tests with zero
failures, errors, or skips. The FIR, integration, and backend-unit roots
freshly write 187 suites/2,239 tests, two suites/127 tests, and four suites/59
tests respectively; the unchanged `dotnet.ir` root retains six green tests.

The analysis remains absent from state selection, call routing, `stateSizes`,
MethodImpl materialization, emission, local-slot selection, and serialized
ABI. Production remains erased. Calls, results, fields, constructors, captures,
real joins, retained foreign constructions, separate assemblies, nullable
joins, value classes, and split-nullable composition remain later stages.

## Next step

Bind the pre-remap facts to the actual existing compiler-owned inline alias and
compare the shadow's predicted storage with the emitter's current selected
local carrier. The comparison must remain read-only and must cover both the
positive compiler temporary and the new source-alias case. Only after the same
facts explain the old positive and all hostile negatives may the IR-origin
recognizer be removed or actual local placement become authoritative.
