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

The full production-erased aggregate passed on 2026-09-11 after correcting
array-factory result widening. Direct JUnit XML audit found no failures,
errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Backend | 22 | 402 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR, both parsers and runtimes | 187 | 2,351 |
| CLI/library integration | 2 | 128 |
| **Full production total** | **212** | **2,887** |
| Focused array-result candidate | 4 | 4 |

This supersedes the inherited full checkpoint `17676a90e1`. The actual FIR2IR
Test task was explicitly rerun without filters and without the rehearsal
property. The unchanged physical CLI dependency remained up to date; its full
six-test XML was audited too. Candidate execution covers PSI and LightTree on
Framework 4.8 and .NET 10. The full production corpus includes the same fixture.

The reproduced failures, unchanged factory MethodDefs, separate-assembly
execution, source hashes, and full gate are recorded in the
[array-result archive](docs/archive/array-factory-object-results-2026-09-11.md).
Earlier bounded feature evidence remains indexed in the
[archive](docs/archive/README.md); this full gate does not establish complete
candidate Runtime/Stdlib closure or authorize a production generic-owner switch.

The subsequent native-array constructor/state feature inherits that full
checkpoint (`cafb56a4e8`). Its rehearsal-physical gate passed 32 candidate tests,
404 backend tests, and the same 32-test production inverse, with no failures,
errors, or skips. Both parsers and runtimes execute the Kotlin/C# fixture;
actual PE and BOUND/final seals prove the original `!T[]` state and constructor
inputs. Production selected routes remain unchanged. See the
[array-constructor archive](docs/archive/generic-owner-array-constructor-2026-09-11.md).

The latest projected-array state feature inherits that same full checkpoint.
Its 32 candidate tests, 406 backend tests, and 32-test production inverse all
passed without failures, errors, or skips. The private implementation retains
its generic owner with one System.Array field; BOUND/final seals and ordinary
C# consumption verify the physical contract and original-array identity. See
the [projected-array archive](docs/archive/generic-owner-projected-array-state-2026-09-11.md).

## Active work

Canonical array factories now accept an instruction-free widening of their
actual Iterator/Iterable result to an already-selected object destination.
Exact invariant array operands now preserve the selected generic constructor
through their independently proven element/field/parameter carriers. Native
arrays no longer require a fictitious nominal TypeDef in that proof. Fixed
output-projected array state now also preserves a generic owner through its
complete private writer graph, using System.Array rather than a fabricated
T[] or object[]. Rerun the source-built candidate census from this green
checkpoint; the census after `af73c6519e` contained 227 error lines. Raw public
projected-array entries, broader nullable array state, and unproven element
constructions remain outside this proof.

The incoming DLL investigation isolates a missing Kotlin/foreign reference
graph in both candidate and production: a C# MethodDef can reference a
Kotlin-produced interface, but the native importer graph cannot yet bind that
reference to the existing KLIB classifier. See the
[reference-graph evidence](docs/archive/foreign-kotlin-interface-reference-graph-2026-09-11.md).
This is not an input-conversion failure. Complete incoming binding remains
open; neither that failure nor the outward contract proof justifies general
`object -> !K` entry casts. Foreign-input forwarding now covers proven equal
natural/semantic carriers, including boxed-or-null owner inputs and projected
arrays. Different-carrier conversion, unbound named carriers, and unclosed
broad-input policies require their own proof. The natural-interface grammar
for nullable owner inputs with split-nullable results remains separate.
Nullable callable capture is now closed for the selected fixed invocation
contracts without a getter exception. Broader callable-reference metadata and
nested nullable generic constructions remain separate requirements.

The census
established that `AbstractMap` and `AbstractCollection` are blocked at foreign
semantic override admission; their anonymous children's unavailable generic
base binders are downstream, not permission for another capture exception.
Split-result forwarding with proven equal input carriers is now closed.
Different-carrier owner-relative inputs and Common broad-candidate inputs
still need their own complete physical conversion/semantic policy; a logical
strict `K` alone is not that proof.
Public/top-level semantic overload families also remain separate.

Continue the source-built Runtime/Stdlib generic-owner rehearsal census within
phase 1 of the way forward. SAM admission passes and the census reaches
emission, but physical inheritance/MethodDef-view closure remains incomplete.
Closed reference-contravariant constructor inputs no longer erase unrelated
generic owners/state. The complete natural/semantic constructor contract is
still open: the existing `L` seal alone does not admit broad constructors, and
closed reference covariance still includes CLR-unnameable bottom views.
Rerun the census from this green checkpoint; custom-shape correctness is not
complete stdlib closure. Broad inputs, refined/nested split-result forwarding,
fixed semantic fields, and their inheritance compositions remain separate gaps.
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
