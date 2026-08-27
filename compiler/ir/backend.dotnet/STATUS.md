# Kotlin/.NET development status

This file is a current snapshot, not a changelog or architecture document.
Read [`AGENTS.md`](AGENTS.md) before changing the target, use
[`docs/programmes/way-forward.md`](docs/programmes/way-forward.md) for ordering,
and follow the owning ADR or active programme for design detail.

## Integration state

- Integration branch target: `dotnet`. This checkout is the isolated
  `codex/rebase-probe-20260827` rehearsal until promotion completes.
- Reviewed upstream base:
  `c72fbd7b4e4ee01698c08204796ddfc43383d642`.
- Last semantic checkpoint:
  `375174e6ea3ce496f3a7635b23c89c169aa5116c`.
- The 253-commit upstream range was replayed without conflicts. All 640 target
  patches remain accounted for: 639 are patch-identical and one is
  context-adjusted. Three post-rebase integration commits remove obsolete
  inline-validation duplication, regenerate Build Tools platform metadata,
  and version the combined Gradle statistics schema.
- Exact range, overlap, semantic-risk, rollback, and verification evidence:
  [`docs/archive/upstream-sync-2026-08-27.md`](docs/archive/upstream-sync-2026-08-27.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest full gate

The strict target aggregate passed at the semantic checkpoint above on
2026-08-27:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 201 suites and 2,524 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 11 | 148 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,243 |
| integration | 2 | 127 |

This has the same suite/test totals as before the rebase. It covers PSI and LightTree,
Framework 4.8 and .NET 10, Runtime/Stdlib production, C# interop, separate
compilation, the generic-owner rehearsal, and the production-erased inverse.

The backend compilation, scoped Build Tools generators/API check and metadata
argument compatibility test, and Gradle statistics schema test also pass.
The repository-wide Build Tools forward suite remains environment-blocked on
this Windows host because its hostile-path test requires symlink privilege;
no compiler assertion failed. The installed-distribution and focused KGP
surfaces are being checked separately because they are not constituents of
`dotNetTest`.

Markdown-only commits after the semantic checkpoint inherit this gate only
when their link, whitespace, and staged-file audits pass.

## Binding production state

- Kotlin Common declarations, shared compiler machinery, and generated stdlib
  sources remain the logical authority.
- Kotlin-produced libraries are self-describing DLLs. Embedded KLIB owns
  logical Kotlin identity; emitted or retained CLR metadata owns physical CLR
  identity.
- Production Kotlin-owned generic classes and interfaces still use the
  accepted erased ABI. The CLR-generic owner work is rehearsal-only and cannot
  affect production unless the complete selected Runtime/Stdlib cutover family
  and its exact inverse pass the atomic migration gates.
- The candidate architecture keeps one object and one authoritative state.
  Natural CLR-generic routes are preferred where proven; semantic capability
  routes are the fail-closed escape hatch for Kotlin views the CLR cannot
  truthfully name. No wrapper, proxy, shadow state, or fabricated construction
  may repair a representation gap.
- BK-1 remains the sole accepted target-specific cast change. Its exact scope
  is owned by the semantic-authority and breaking-change decisions, not by this
  status file.

See the accepted erased-owner decisions and the two reopening drafts indexed
under [`docs/README.md`](docs/README.md).

## Active work

The source-built Stdlib census is paused while physical declaration authority
and per-value carrier provenance are consolidated in shadow/rehearsal mode.
The current generic-owner checkpoint has:

- a local final-emission signature certificate for one complete family;
- binder-correct MethodDef generic parameters and MethodSpec call routing; and
- a producer-recorded ABI-61 sealed-family certificate whose transport is
  independently validated but which is not yet a consumer route.

The next bounded sequence is:

1. add retained-foreign final-evidence adaptation;
2. prove static/file-facade operation authority;
3. prove overlapping and global family ownership without allowing earlier
   BOUND evidence to fill a missing final fact;
4. compose owner-dependent input policy with split-nullable result layout on a
   custom two-parameter lookup family before applying it to `Map`;
5. replace bounded recognizers only after the shared provenance model explains
   both their positive cases and hostile negatives; and
6. run the complete Runtime/Stdlib, separate Kotlin/C# assembly, Framework 4.8,
   .NET 10 JIT/ReadyToRun/trimmed/NativeAOT, production-erased inverse, and
   rollback decision gates.

Authority and remaining hostile cases:
[`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md).
Class-owner admission, state, C# surface, and migration boundaries:
[`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).

## Current blockers

- The generic-owner sealed authority is not yet complete for retained foreign
  declarations, static/file-facade calls, or overlapping/global ownership.
- Shared carrier provenance is still a shadow comparison; bounded recognizers
  remain authoritative until their hostile matrices are derived generally.
- No production generic-interface or generic-class cutover is authorized until
  the full source-built Runtime/Stdlib graph and exact inverse rollback pass.
- The prepare/install stdlib source manifest is under audit because its
  hand-maintained source list may lag the canonical compiler-owned manifest.
- Wider target gaps and release gates are intentionally listed only in the
  [way forward](docs/programmes/way-forward.md).

## Navigation

- Documentation authority and index: [`docs/README.md`](docs/README.md)
- Ordered open work and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Generic-owner programme:
  [`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md)
- Common collections programme:
  [`docs/programmes/common-collections.md`](docs/programmes/common-collections.md)
- Compiler ownership programme:
  [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md)
- Historical evidence: [`docs/archive/README.md`](docs/archive/README.md)

Update this file only when the integration base, latest verified semantic
checkpoint, active work, or current blockers change. Git and dated archive
records own history; ADRs own lasting decisions.
