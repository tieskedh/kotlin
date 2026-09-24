# Kotlin/.NET development status

This file is the current integration snapshot. Read [`AGENTS.md`](AGENTS.md)
before changing the target. Future ordering belongs in the
[way forward](docs/programmes/way-forward.md), durable representation rules in
ADRs, and dated evidence in [`docs/archive`](docs/archive/README.md).

## Current checkpoint

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `88a184ab89279617dbfe4e89ba9831ed1b43c863`.
- Physical library ABI 72, generic-owner artifact schema 22, and compiler/
  runtime surface 62 are current. Git owns the exact promoted commit identity.
- The production-inert generic-owner authority consolidation is closed. It
  separates Kotlin logical authority, CLR declaration authority, per-value
  physical provenance, late operation routing, and producer-wide state.
- Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype identities
  may still be replaced atomically.

The detailed checkpoint evidence and the disposition of earlier bounded proofs
are in the
[2026-09-05 consolidation archive](docs/archive/generic-owner-physical-authority-consolidation-2026-09-05.md).
The reviewed 291-commit upstream integration, replay preservation, bounded
adaptations, and post-rebase gate are in the
[2026-09-05 upstream-sync archive](docs/archive/upstream-sync-2026-09-05.md).
Current representation rules are linked from the navigation section below.

## Production contract

- Kotlin Common declarations and Kotlin IR/KLIB remain logical authority.
  Emitted or retained CLR metadata remains physical authority.
- Kotlin-produced libraries remain self-describing DLLs containing their KLIB
  and physical binding records.
- Kotlin-owned generic classes and interfaces still use the accepted erased
  production ABI. CLR-generic owners remain rehearsal-only until one complete
  family can switch atomically with an exact inverse and rollback.
- The candidate retains one receiver identity and one authoritative state.
  Proven natural CLR-generic routes are preferred; semantic capabilities are
  used only for Kotlin views the CLR cannot truthfully name. No wrapper, proxy,
  shadow state, or fabricated CLR construction repairs a representation gap.
- Retained foreign CLR metadata is terminal physical authority for imported
  declarations. Source backing does not imply current-emitter ownership.
- BK-1 remains the only accepted target-specific cast change. Its scope belongs
  to the
  [semantic-authority decision](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and [breaking-change ledger](docs/decisions/breaking-kotlin-changes.md).

## Latest verification

The full production-erased gate completed on 2026-09-24 after closing coherent
Kotlin bottom-view operations, preserving natural paired-entry results and
authenticating complete source MethodDef signatures.
Direct JUnit XML audit found no failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Backend | 28 | 460 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR, both parsers and runtimes | 187 | 2,383 |
| CLI/library integration | 2 | 130 |
| **Full production total** | **218** | **2,979** |
| Coherent-interface candidate matrix | 4 | 32 |

FIR2IR and CLI/library integration were explicitly rerun unfiltered. The final
aggregate omits the rehearsal property and reuses the freshly green full CLI
outputs. Backend and physical-model tests were up to date and their complete
XML was audited; the physical-model XML is unchanged from 2026-09-11. This
replaces full checkpoint `96e600ca11`. It is not a claim that every dependency
freshly executed in the final invocation.

Candidate and erased execution cover PSI and LightTree on Framework 4.8 and
.NET 10. Separate Kotlin/C# consumers verify bottom and nullable-bottom body
execution, original exceptions, receiver identity, typed state/results and
overload binding. All 32 focused inverse cases were individually audited in
the full output. See the
[coherent-interface evidence](docs/archive/coherent-interface-bottom-dispatch-2026-09-24.md)
for commands, intermediate failures, ABI validation and exact exclusions.

Earlier bounded candidate evidence remains indexed in the
[archive](docs/archive/README.md). This full gate does not establish complete
candidate Runtime/Stdlib closure or authorize a production generic-owner switch.

## Active work

The source-built Runtime/Stdlib generic-owner rehearsal remains inside phase 1
of the way forward. Broad owner/census expansion is paused for the
[storage-cycle strategic gate](docs/programmes/generic-owner-storage-cycle.md):
separate generic factories, another Kotlin library's widening/writes, and C#
reads of the same object, followed by a general representation rule and an
actual candidate/erased comparison. Bottom and ordinary non-bottom covariance,
existing foreign allocations, runtime/allocation/size, compilation cost,
compiler complexity and interop all belong to this gate. An early
GO/CONSTRAIN/NO-GO selects further investment before the full census, without
choosing a new public contract or authorizing production migration.
The latest census, on `bd0f943a24`, contains 215 error
lines, exactly unchanged from `f132c354f5`, `3724aeab9c` and `2a13f45341`;
`AbstractMap` and its generated views no longer fail there. These are
diagnostics, not independent bugs or a completion percentage.

Operation-directed interface dispatch and independent generic-state composition
are the immediate architecture boundaries. The
[selected-view transport candidate](docs/decisions/draft-adr-selected-interface-view-transport.md)
remains experimental, not an admitted storage ABI. The broader
[dispatch/storage research](docs/archive/target-directed-interface-dispatch-research-2026-09-13.md)
supports operation contracts before compound storage. The production-inert
native query now distinguishes a reference conversion from the current source
from independently proved target membership. It binds the requested retained
interface MethodDef without changing receiver facts or storage; native targets
can be valid with multiple variant sources and without an exact interface row.
The existing native emitter is now execution-tested against those operation
targets; it already binds the requested retained interface MethodDef and does
not need the new model query to do so. Coherent Kotlin semantic-family dispatch
now executes through bottom producers, separate forwarding, `Any`/star recovery
and `ValueBox<Any>`, while independently proved fields and natural C# results
remain typed. The subsequent
[nested-storage counterexamples](docs/archive/nested-bottom-storage-2026-09-24.md)
reproduce invalid CLR casts both in a separate box factory and on a later write
to an already allocated exact box. Both unchanged probes pass the erased
inverse on both parsers/runtimes; their source is archived, not left in the
active corpus. A broader construction/public surface and the treatment of
existing narrow C# containers require an explicit interop decision before
integration. Neither global state erasure nor pair transport follows from
this result. Conflicting foreign implementations of
Kotlin-owned families require an explicit interop policy; neither researched
target-priority policy is accepted.

The extended standalone CLR probe passes eight assertion groups on net48-target/
serviced CLR4 and .NET 10, including no-exact-row native calls and C# interface
reimplementation with original effects/exceptions. The separate-library JVM
baseline now covers direct exact/wide/star operations and bottom producers in
reference-looking nested state. These are mechanism and Common-precedent proofs,
not themselves Kotlin/.NET compiler integration, performance or deployment
gates; they do not increase the full-test count. The subsequent native-call
feature admits only source-proved safe upcasts; broader native casts remain
unclosed. Runtime/Stdlib surfaces remain unchanged. Both
[constructor experiments](docs/archive/selected-interface-view-storage-2026-09-12.md)
remain parked with their original guard. Existing C# allocations, open generic
factories and broad mutable writes remain hard storage/interop proof boundaries.

Owner-admission evidence still identifies foreign semantic interface inputs,
including `AbstractCollection.containsAll`, as unclosed. The unavailable base
views of descendants such as `AbstractMutableMap` are downstream of admission,
not permission to invent a base construction or bypass the MethodDef guard.
Semantic constructor authority and typed writer provenance remain separate
roots. The admitted constructor, array-state and fresh-allocation boundaries
are recorded in the
[allocation archive](docs/archive/generic-owner-projected-allocation-2026-09-11.md)
and owning ADR. Raw public projected-array entries, broader nullable array
state and unproved element constructions remain outside those proofs.

The incoming reference graph now binds the admitted public root-interface
results of foreign MethodDefs to their existing KLIB classifiers. Physical
Kotlin references do not become foreign declarations. Fresh exact Kotlin
override results and proven fixed local getter/helper results now have separate
executable coverage, but foreign inputs, properties, inheritance, stronger
bounds and broader override/state flows
remain outside these proofs. See the
[bounded result evidence](docs/archive/foreign-kotlin-interface-results-2026-09-12.md).
Neither reference binding nor the outward contract proof justifies general
`object -> !K` entry casts. Unclosed broad-input policies, nested inputs,
unbound named carriers, refined/nested split results, public semantic overloads,
broader callable-reference metadata and their inheritance compositions remain
separate requirements. The complete natural/semantic constructor contract is
also open: the existing `L` seal alone does not admit broad constructors, and
closed reference covariance still includes CLR-unnameable bottom views.
Custom-shape correctness is not complete stdlib closure.
Do not add declaration, package, collection, `Map`, member-name, IR-origin,
or stdlib exceptions.

The next slice must preserve ordinary C# overrides and implementations: hidden
semantic compiler ABI cannot become a second source-level obligation. It must
also keep production erased and prove the same focused inverse before promotion.

## Current blockers

- The shared value/operation grammar is still deliberately bounded. Broader
  MethodSpec vectors, nullable/bottom/unknown joins, captures, properties,
  conversions, multiple members, defaults, and deeper inheritance/MethodImpl
  graphs require independent proofs.
- Producer-wide state does not yet cover every open writer graph, array,
  volatile, nullable/value-class, external-state, or mixed-construction case.
- The complete Runtime/Stdlib family graph and wider separately compiled
  Kotlin/C# producer, subclass, implementation, and consumer matrix remain open.
- ReadyToRun, trimming, NativeAOT, reflection/tooling, representative
  applications and measurements, and exact migration rollback still block any
  production cutover.

## Navigation

- Documentation authority and index: [`docs/README.md`](docs/README.md)
- Ordered work and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Physical authority and value provenance:
  [`docs/decisions/draft-adr-generic-owner-physical-authority.md`](docs/decisions/draft-adr-generic-owner-physical-authority.md)
- Generic-interface candidate:
  [`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md)
- Generic-owner programme:
  [`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md)
- Atomic migration and rollback:
  [`docs/programmes/generic-class-owner-migration-plan.md`](docs/programmes/generic-class-owner-migration-plan.md)
- Historical evidence: [`docs/archive/README.md`](docs/archive/README.md)

Update this file only when the integration base, verified checkpoint, active
work, or current blockers change. Git owns chronology, ADRs own lasting
decisions, programmes own future ordering, and dated archives own detailed
evidence.
