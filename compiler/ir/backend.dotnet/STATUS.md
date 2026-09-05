# Kotlin/.NET development status

This file is the current integration snapshot. Read [`AGENTS.md`](AGENTS.md)
before changing the target. Future ordering belongs in the
[way forward](docs/programmes/way-forward.md), durable representation rules in
ADRs, and dated evidence in [`docs/archive`](docs/archive/README.md).

## Current checkpoint

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `2868cfb88a7ea111ea6f6bf02f24430dc0e039e5`.
- Physical library ABI 69, generic-owner artifact schema 22, and compiler/
  runtime surface 60 are current. Git owns the exact promoted commit identity.
- The production-inert generic-owner authority consolidation is closed. It
  separates Kotlin logical authority, CLR declaration authority, per-value
  physical provenance, late operation routing, and producer-wide state.
- Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype identities
  may still be replaced atomically.

The detailed checkpoint evidence and the disposition of earlier bounded proofs
are in the
[2026-09-05 consolidation archive](docs/archive/generic-owner-physical-authority-consolidation-2026-09-05.md).
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

The focused ABI-69 generic-SAM-wrapper gate completed on 2026-09-05. A
Common-generated implementation of an admitted CLR-generic SAM interface now
owns one invariant physical binder, closes each use from the exact conversion
operand, and retains classifier-based Common equality. The separately compiled
`Sink<in T>` fixture passed PSI and LightTree on .NET 10 and Framework 4.8 in
both candidate and production-erased inverse modes: four suites and four tests
per mode, with zero failures, errors, or skips. The physical checks cover local
and producer-recorded interface authority, `object`, `string`, value-type, and
caller-MethodDef constructions, one raw `FunctionN` field, and no fabricated
interface edge. The lasting rules are in the
[fun-interface decision](docs/decisions/fun-interfaces.md) and
[generic-interface draft](docs/decisions/draft-adr-reified-generic-interface-owner.md).

The source-built Runtime/Stdlib integration tests
`DotNetLibraryIntegrationTest.testProducesNet48StdlibDll` and
`testProducesNet10StdlibDll` also pass. They prove source production through
DLL assembly on both profiles, and the former generated
`kotlin.collections.sam$kotlin_Comparator$0` missing-binder failure is absent in
those lanes. They do not by themselves prove the complete census or semantic
execution corpus.

The latest full target-wide checkpoint remains the ABI-69/schema-22 aggregate
completed on 2026-09-05; it predates the bounded generic-SAM-wrapper slice.
Direct JUnit XML audit of that production-erased aggregate found 212 suites and
2,813 tests, with zero failures, errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 22 | 400 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,279 |
| integration | 2 | 128 |

The gate forced a fresh dependency-wide run with:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun-tasks -q
```

The same 15-fixture architecture matrix passed in candidate and production-
erased inverse modes through PSI and LightTree on .NET 10 and Framework 4.8:
four suites and 60 tests per mode, with zero failures, errors, or skips. The
backend model includes 106 physical-value tests; the constructor/current-
emitter authority regression is covered directly as well. Exact fixture names
and commands are preserved in the consolidation archive.

## Active work

Resume the source-built Runtime/Stdlib generic-owner rehearsal census beyond
the closed generated-SAM-owner case within phase 1 of the way forward. The next
real failure selects the next structural provenance, placement, operation, or
state rule. Do not add declaration, package, collection, `Map`, member-name,
IR-origin, or stdlib exceptions.

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
- The earlier 461-commit upstream rebase completed on 2026-08-24. The freshly
  fetched upstream range from the reviewed `2868cfb88a` base to pinned
  `88a184ab89` contains 291 new commits and remains unreviewed. Rehearse that
  synchronization only from this clean feature checkpoint, with an explicit
  replay and verification plan; do not mix it into the next semantic slice.

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
