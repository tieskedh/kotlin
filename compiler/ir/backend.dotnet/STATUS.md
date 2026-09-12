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
- Physical library ABI 71, generic-owner artifact schema 22, and compiler/
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

The full production-erased aggregate passed on 2026-09-12 after binding
Kotlin-owned interface results in foreign CLR signatures. Direct JUnit XML
audit found no failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Backend | 24 | 414 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR, both parsers and runtimes | 187 | 2,383 |
| CLI/library integration | 2 | 129 |
| **Full production total** | **214** | **2,932** |
| Post-full recursive-state candidate matrix | 4 | 48 |
| Post-full recursive-state backend model gate | 24 | 414 |
| Post-full recursive-state erased inverse | 4 | 48 |

Full checkpoint `3724aeab9c` supersedes `2a13f45341`. The actual FIR2IR and
CLI integration Test tasks were explicitly rerun without filters and without
the rehearsal property. The unchanged physical CLI dependency remained up to
date; its full six-test XML was audited too. Candidate execution covers PSI
and LightTree on Framework 4.8 and .NET 10. The original incoming-reference
fixture was individually checked in each full FIR suite. The later exact-
override assertions ran separately in the focused matrix below.

The reproduced reference and classifier-collision failures, unchanged foreign
MethodDefs, separate Kotlin/C# execution, source hashes and full gate are in the
[foreign/Kotlin result archive](docs/archive/foreign-kotlin-interface-results-2026-09-12.md).
Earlier bounded feature evidence remains indexed in the
[archive](docs/archive/README.md); this full gate does not establish complete
candidate Runtime/Stdlib closure or authorize a production generic-owner switch.

The new candidate covers consuming native and Kotlin-owned results from
separate C# factories, ordinary C# and Kotlin implementations, same-object
identity, owner-generic value/reference substitutions and nested results.
CLI negatives cover use-site nullability, erased stars, stronger Kotlin bounds,
missing dependencies and class/typealias lookalikes.

The latest repair resolves a self-admission cycle in invariant recursive-owner
state. Its matrix verifies typed recursive fields, reordered binders, nullable
and value-class substitutions, separate Kotlin/C# consumers, broad-writer
negatives and an unchanged covariant boundary. The new rule is explicitly
rehearsal-only; production mapping, ABI, Runtime and Stdlib are unchanged.
It inherits the full checkpoint above without claiming that the older full
run executed the new assertions. Source hashes match all three delta gates.
See the
[recursive-state evidence](docs/archive/invariant-recursive-owner-state-2026-09-12.md).
The preceding
[fixed call-result repair](docs/archive/local-fixed-interface-call-results-2026-09-12.md)
and [exact override-result proof](docs/archive/foreign-kotlin-interface-override-results-2026-09-12.md)
remain separately verified. Broader object-domain state and open result
substitutions remain unclosed.

## Active work

Continue the source-built Runtime/Stdlib generic-owner rehearsal inside phase 1
of the way forward. The latest census, on `f132c354f5`, contains 215 error
lines, exactly unchanged from `3724aeab9c` and `2a13f45341`;
`AbstractMap` and its generated views no longer fail there. These are
diagnostics, not independent bugs or a completion percentage. That census
predates the recursive-owner state repair and must be rerun before claiming
a changed source-built frontier.

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
