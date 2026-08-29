# Kotlin/.NET development status

This file is a current snapshot, not a changelog or architecture document.
Read [`AGENTS.md`](AGENTS.md) before changing the target, use
[`docs/programmes/way-forward.md`](docs/programmes/way-forward.md) for ordering,
and follow the owning ADR or active programme for design detail.

## Integration state

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `c72fbd7b4e4ee01698c08204796ddfc43383d642`.
- The current integration checkpoint closes the bounded same-producer
  recorded-MethodDef dispatch proof described below. Git owns its exact
  identity.
- Reviewed upstream synchronization evidence:
  [`docs/archive/upstream-sync-2026-08-27.md`](docs/archive/upstream-sync-2026-08-27.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest full gate

The production-erased strict target aggregate passed on 2026-08-29 at the
bounded local recorded-MethodDef checkpoint:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 202 suites and 2,548 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 12 | 163 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,251 |
| integration | 2 | 128 |

The focused candidate and production-erased inverse both passed under PSI and
LightTree on Framework 4.8 and .NET 10. The candidate reader carries exact
`fetch` and `accept` MethodDef tokens; separately compiled generator-free C#
ordinary and explicit implementations preserve one identity and state, reject
incompatible widened input before mutation, and never select public decoys.

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

The source-built Stdlib census remains paused while generic-owner architecture
is consolidated in rehearsal mode. Production Kotlin-owned generic owners
remain erased.

The admitted family emits logically covariant
`CompleteNaturalContract<T>` as one physically invariant natural TypeDef with
exact `fetch(): !T` and `accept(!T)` slots and no exact sibling. The separately
compiled `CompleteNaturalChild<T>` inheritance edge and
`CompleteNaturalOuter<T>` nested result reach the same physical-variance
fixpoint, and a downstream module consumes both middle-assembly families.

ABI 62 records final TypeDef GenericParam variance in the ordinary `C` class
record and repeats it in the `H` family record. External binding requires exact
`C/H` agreement and never reconstructs physical variance from logical KLIB.
Plain non-partial C# reference- and value-type implementations compile and run;
omitting either natural member fails in the C# compiler. Kotlin exact and
widened calls retain one object identity and behavior on both runtimes, and a
producer-recorded natural factory result retains its exact carrier.

The first half of the MethodDef/MethodImpl stage is now complete. A
same-producer reader emits the exact open `ldtoken method`, binds it to the
unique implemented closed construction with the mandatory two-handle CLR API,
and leaves ordinary or explicit `MethodImpl` selection to the CLR. Input-domain
policy is frozen at producer admission; unsupported or missing authority fails
closed instead of being reconstructed from a late signature. Physical library
ABI remains 62 and compiler-runtime surface level is 60.

The next active slice is ABI 63: publish one exact natural MethodDef descriptor
per logical member, with parameter domains and direct/void/split-nullable result
layout kept orthogonal, validate it against the producer DLL, and consume it in
a downstream assembly. Stage 5 remains open until that cross-assembly route and
the hostile same-name/same-regular-arity interface-MethodDef proof pass. Only
then may this bounded family retire its split comparison surface inside the
rehearsal. Natural generic-class typed-versus-broad state follows. The remaining
order and final selected-family freeze are owned by the generic-owner programme
and migration plan linked below.

Shared authority and provenance:
[`docs/decisions/draft-adr-generic-owner-physical-authority.md`](docs/decisions/draft-adr-generic-owner-physical-authority.md).
Interface shape and remaining hostile cases:
[`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md).
Class-owner admission, state, C# surface, and migration boundaries:
[`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).
Ordered generic-owner migration stages:
[`docs/programmes/generic-class-owner-migration-plan.md`](docs/programmes/generic-class-owner-migration-plan.md).

## Current blockers

- ABI 62 does not publish exact natural MethodDef authority. A downstream
  consumer therefore cannot yet emit the proven token route; outside the
  bounded same-producer slice Runtime still uses split/fallback machinery.
- The hostile overloaded-interface MethodDef, cross-assembly descriptor,
  trimming, and NativeAOT gates remain open for stage 5.
- Natural generic-class typed versus broad state selection is not yet proven.
- Retained/static/global declaration authority and shared value provenance
  remain incomplete.
- Remaining inherited/open/projected foreign conversions and SZ-array entry
  guards remain unproved.
- Complete Runtime/Stdlib, deployment, tooling, production-erased inverse, and
  rollback gates still block any production cutover.
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
