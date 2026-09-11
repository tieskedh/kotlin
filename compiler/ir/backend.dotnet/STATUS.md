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

The latest Runtime-declared private overload fix passed the rehearsal-physical
lane on 2026-09-11. Direct JUnit XML audit found no failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Focused candidate, both parsers and runtimes | 4 | 24 |
| Same production-erased inverse | 4 | 24 |
| Complete backend suite | 22 | 402 |

The inherited production-erased target-wide base is `3a2384f636`: 212 suites,
2,821 tests (backend 400, `dotnet.ir` 6, FIR2IR 2,287, integration 128), all
green. Its actual FIR2IR Test task was explicitly rerun without filters. This
feature is not a new full aggregate: only rehearsal-selected unbound private
method naming and fixture-local checks changed;
production ABI, Runtime/Stdlib, shared compiler, and artifact schemas are
unchanged.

The reproduced Runtime-interface overload collisions, shared logical name
selection, separate Kotlin/C# execution, inverse, census advancement, and
commands are in the
[Runtime overload archive](docs/archive/generic-owner-runtime-semantic-overloads-2026-09-11.md).
The initial custom-interface proof and unchanged public/exact controls are in
the [private overload archive](docs/archive/generic-owner-private-semantic-overloads-2026-09-11.md).
The independently repaired result-chain regression is recorded in the
[semantic result archive](docs/archive/generic-owner-semantic-owner-result-2026-09-11.md).
The inherited full gate is recorded in the
[invariant nullable SAM archive](docs/archive/generic-owner-invariant-nullable-sam-2026-09-11.md).

## Active work

Close the generated callable physical-view gap exposed after the private
overload repair. The census no longer rejects the private overload in
`AbstractMap`, but its lambda has no proven exact `ExactFunction1` construction.
Public/top-level semantic overload families remain separate. The expanded
matrix also retains the semantic iterator result repair:
no closed invariant interface is fabricated from a widened logical result,
and unrelated typed state stays typed.

Continue the source-built Runtime/Stdlib generic-owner rehearsal census within
phase 1 of the way forward. SAM admission passes and the census reaches
emission, but physical inheritance/MethodDef-view closure remains incomplete.
Closed reference-contravariant constructor inputs no longer erase unrelated
generic owners/state. The complete natural/semantic constructor contract is
still open: the existing `L` seal alone does not admit broad constructors, and
closed reference covariance still includes CLR-unnameable bottom views.
Rerun the census from this green checkpoint; custom-shape correctness is not
complete stdlib closure. Broad inputs, split-result forwarding, fixed semantic
fields, and their inheritance compositions remain separate gaps.
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
