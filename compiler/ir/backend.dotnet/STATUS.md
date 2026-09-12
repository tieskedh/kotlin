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

The full production-erased aggregate passed on 2026-09-12 after preserving
Common input policies in class-slot bridges. Direct JUnit XML audit found no failures,
errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Backend | 23 | 409 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR, both parsers and runtimes | 187 | 2,371 |
| CLI/library integration | 2 | 128 |
| **Full production total** | **213** | **2,914** |
| Focused class-slot candidate | 4 | 16 |

This supersedes the full checkpoint `cafb56a4e8`. The actual FIR2IR
Test task was explicitly rerun without filters and without the rehearsal
property. The unchanged physical CLI dependency remained up to date; its full
six-test XML was audited too. Candidate execution covers PSI and LightTree on
Framework 4.8 and .NET 10. The full production corpus includes the same fixture.

The reproduced failure, unchanged MethodDefs, separate-assembly execution,
source hashes and full gate are recorded in the
[class-slot bridge archive](docs/archive/covariant-bridge-input-policy-2026-09-12.md).
Earlier bounded feature evidence remains indexed in the
[archive](docs/archive/README.md); this full gate does not establish complete
candidate Runtime/Stdlib closure or authorize a production generic-owner switch.

The focused candidate matrix covers the repaired class-slot boundary and
existing physical covariant slots, closed semantic inputs and C# split-result
overrides. The nominal value-class input regression passes on all production
lanes, but its generic class-slot parameter adaptation remains an independently
reproduced candidate gap. It is not claimed by the 16-test candidate matrix.

The current Common-authorized foreign-input feature inherits that full
checkpoint (`b35b6f646c`). Its rehearsal-physical gate passes 48 candidate tests,
411 backend tests and the same 48-test production inverse, with zero failures,
errors or skips. The seven semantic source hashes match across both gates.
Production-selected paths remain structurally unchanged. See the
[foreign-input archive](docs/archive/generic-owner-foreign-input-barrier-2026-09-12.md)
for the exact boundary, physical evidence and inherited checkpoint.

## Active work

Continue the source-built Runtime/Stdlib generic-owner rehearsal inside phase 1
of the way forward. The census after `5ff1de08d0` contains 225 error lines versus
228: the three projected-allocation failures are gone. These are diagnostics,
not independent bugs or a completion percentage. The admitted constructor,
array-state and fresh-allocation rules are recorded in the
[allocation archive](docs/archive/generic-owner-projected-allocation-2026-09-11.md)
and owning ADR. Raw public projected-array entries, broader nullable array
state and unproved element constructions remain outside those proofs.

Final owner-admission diagnostics identify `AbstractMap.get` and
`AbstractCollection.containsAll` as unsupported foreign semantic overrides.
Their children's unavailable generic base binders are downstream, not a reason
to invent a base construction or another capture exception. The current slice
closes one Common NULL-default bare-owner input on the ordinary foreign branch,
independently of the actual object or split result layout. It does not close
containsAll's semantic interface input. Rerun the census from this green
checkpoint to identify the next complete structural boundary.

The incoming DLL investigation isolates a missing Kotlin/foreign reference
graph in both candidate and production: a C# MethodDef can reference a
Kotlin-produced interface, but the native importer graph cannot yet bind that
reference to the existing KLIB classifier. See the
[reference-graph evidence](docs/archive/foreign-kotlin-interface-reference-graph-2026-09-11.md).
This is not an input-conversion failure. Complete incoming binding remains
open; neither that failure nor the outward contract proof justifies general
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
