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

The production callable value-class result repair passed the full target lane
on 2026-09-11. Direct JUnit XML audit found no failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Complete backend suite | 22 | 402 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR, both parsers and runtimes | 187 | 2,327 |
| CLI/library integration | 2 | 128 |
| **Total** | **212** | **2,863** |

The actual FIR2IR Test task was explicitly rerun without filters; the backend
was rerun on the same final source, integration rebuilt, and the unchanged
physical model remained up to date. This is a new production-erased full
checkpoint, replacing the inherited `3a2384f636` gate. No public MethodDef,
Runtime/Stdlib surface, shared compiler, or artifact schema changed.

The reproduced nominal-box defect, separate Kotlin/C# consumers, unchanged
upstream fixtures, instruction-level forwarding checks, and exact commands are
in the [callable value-class archive](docs/archive/value-class-callable-nominal-result-2026-09-11.md).
Earlier rehearsal checkpoints remain indexed in the archive.

## Active work

Close the generated callable physical-view gap exposed after the private
overload repair. The census no longer rejects the private overload in
`AbstractMap`, but its lambda has no proven exact `ExactFunction1` construction.
Reapply the safely parked feature and repeat its candidate/inverse matrix on
the repaired nominal value-class baseline; retain the hostile value-class case.
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
