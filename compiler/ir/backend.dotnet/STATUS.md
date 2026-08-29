# Kotlin/.NET development status

This file is the current integration snapshot. Read [`AGENTS.md`](AGENTS.md)
before changing the target. Future ordering belongs in the
[way forward](docs/programmes/way-forward.md), durable representation rules in
ADRs, and dated evidence in [`docs/archive`](docs/archive/README.md).

## Integration state

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `c72fbd7b4e4ee01698c08204796ddfc43383d642`.
- Current checkpoint: physical library ABI 64, generic-owner artifact schema
  21, compiler/runtime surface 60.
- ABI 64 closes the first standalone implementation-class MethodDef seal for
  one separately compiled Kotlin generic class. This is Kotlin producer
  authority external to its consumer, not retained foreign CLR authority.
- Git owns the exact promoted checkpoint identity.
- Reviewed upstream synchronization:
  [`docs/archive/upstream-sync-2026-08-27.md`](docs/archive/upstream-sync-2026-08-27.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest verification

The fresh unqualified production-erased target aggregate passed on 2026-08-29
at the ABI-64 checkpoint:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 204 suites and 2,583 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 14 | 198 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,251 |
| integration | 2 | 128 |

Feature-local evidence also passed:

- focused ABI/codec/metadata validation: 2 suites, 36 tests;
- candidate PSI/LightTree x Framework 4.8/.NET 10 matrix: 4 suites, 4 tests;
- production-erased inverse over the same matrix: 4 suites, 4 tests.

All reported lanes have zero failures, errors, or skips. The inverse proves
arity-zero production owners and absence of rehearsal `H`, `N`, `M`, and `J`
records. Commands, hostile source, PE assertions, and exact evidence are
recorded in the
[ABI-64 archive](docs/archive/generic-owner-external-class-methoddef-authority-2026-08-29.md).

## Production binding state

- Kotlin Common declarations and Kotlin IR/KLIB remain logical authority.
  Emitted or retained CLR metadata remains physical authority.
- Kotlin-produced libraries remain self-describing DLLs containing their KLIB
  and physical binding records.
- Production Kotlin-owned generic classes and interfaces still use the
  accepted erased ABI. All CLR-generic owner work remains rehearsal-only until
  one complete family can switch atomically with its exact inverse and
  rollback.
- The candidate keeps one receiver identity and one authoritative state.
  Proven natural CLR-generic routes are preferred; semantic capabilities are
  used only for Kotlin views the CLR cannot truthfully name. No wrapper, proxy,
  shadow state, or fabricated construction may repair a representation gap.
- BK-1 remains the only accepted target-specific cast change; its scope is
  owned by the
  [semantic-authority decision](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and [breaking-change ledger](docs/decisions/breaking-kotlin-changes.md).

## Active work

The source-built Stdlib census remains paused while generic-owner physical
authority and value provenance are consolidated in rehearsal mode.

The declaration-level `N` record seals one admitted natural-interface
MethodDef. ABI 64 adds an independent implementation-level `M` record that
seals one already-emitted Kotlin class MethodDef and its exact direct
construction of that natural interface. A separate consumer can therefore
preserve the physical base slot without remapping the logical KLIB signature
or fabricating a MethodImpl. Producer-final and consumer-PE validation reject
missing, contradictory, or redirecting evidence; foreign and merely same-name
aliases never become physical authority.

The admitted `M` grammar is intentionally narrow: one top-level public open
invariant unconstrained generic Kotlin class, one ordinary public open
non-generic method, one exact direct natural-interface construction, and no
explicit MethodImpl. The complete bounded proof and remaining boundary are in
the [ABI-64 archive](docs/archive/generic-owner-external-class-methoddef-authority-2026-08-29.md).

The active consolidation now extends this same authority/provenance model
rather than advancing the stdlib census through local recognizers. Its design
is owned by the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md);
the precise next ordering is owned by the
[way forward](docs/programmes/way-forward.md).

## Current blockers

- Declaration and implementation authority is not yet closed for deeper base
  chains, multiple or distinct constructed views, constraints, method
  generics, explicit MethodImpls, or general callable forms.
- Retained foreign CLR declaration authority remains a separate incomplete
  boundary; ABI 64 does not close it.
- Natural generic-class typed-versus-broad state selection and shared
  per-value provenance remain incomplete.
- Remaining retained-foreign projected conversions and SZ-array entry guards
  are not yet proven.
- Complete Runtime/Stdlib coverage, Framework/CoreCLR deployment breadth,
  ReadyToRun, trimming, NativeAOT, tooling, and rollback still block a
  production cutover.
- Wider target and release gaps are listed only in the
  [way forward](docs/programmes/way-forward.md).

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

Update this file only when the integration base, latest verified checkpoint,
active work, or current blockers change. Git owns chronology, ADRs own lasting
decisions, programmes own future ordering, and dated archives own detailed
evidence.
