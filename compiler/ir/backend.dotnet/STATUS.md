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
- The current integration checkpoint closes the bounded ABI-63 external
  recorded-MethodDef dispatch proof described below. Git owns its exact
  promoted identity.
- Reviewed upstream synchronization evidence:
  [`docs/archive/upstream-sync-2026-08-27.md`](docs/archive/upstream-sync-2026-08-27.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest full gate

The production-erased strict target aggregate passed on 2026-08-29 at the
bounded ABI-63 external recorded-MethodDef checkpoint:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 204 suites and 2,568 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 14 | 183 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,251 |
| integration | 2 | 128 |

The ABI-63 complete candidate matrix and focused production-erased inverse
have each passed 4 suites and 8 tests across PSI/LightTree and Framework 4.8
and .NET 10, with zero failures, errors, or skips.

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

Physical library ABI 63 records final TypeDef GenericParam variance in the
ordinary `C` class and `H` family records and adds a declaration-level `N` seal
for each admitted directly declared natural producer or split-nullable producer
slot. `N` contains the final natural TypeDef and MethodDef rows plus orthogonal
parameter domains and result layout. It is derived from final emission,
validated against the containing producer PE before exposure, and can exist
without a Kotlin implementation class. The implementation-level `J` family
seal remains optional; when present, its complete projected `N`, including
logical domains and result layout, must equal the declaration `N`. `J` is never
the source of declaration authority.

A separately compiled consumer now emits the producer-recorded open
`ldtoken method` for direct and split-nullable results without logical
reconstruction of physical identity, public-name lookup, or regular-arity
selection. Before routing, it independently authenticates the PE-validated `N`
against KLIB's logical instance shape, ordinary parameter carriers, owner-result
parameter index, and split-nullability bit; KLIB can reject but cannot construct
physical authority. The hostile same-name/same-regular-arity split-nullable
family records distinct `bool` and `int32` MethodDefs, and downstream IL asserts
the corresponding distinct tokens.

A generator-free C# class implements only the natural
`OverloadedNullableSource<string>` interface. Through its covariantly widened
`OverloadedNullableSource<object>` view, it calls public functions in the final
separately compiled Kotlin consumer; Boolean/Int32 overloads and hit/null paths
all dispatch correctly without compiler capability ABI. This closes the
bounded stage-5 descriptor and overload gate. Compiler-runtime surface level
remains 60.

The initial `N` grammar is deliberately limited to declaration-local,
constraint-free, root/edge-free natural TypeDefs. The next active slice may
retire the old comparison surface only for this bounded rehearsal family, then
proceeds to natural generic-class typed-versus-broad state. Inherited,
edge-bearing, constrained, and wider callable forms remain later authority
work; the remaining order and final selected-family freeze are owned by the
generic-owner programme and migration plan linked below.

Shared authority and provenance:
[`docs/decisions/draft-adr-generic-owner-physical-authority.md`](docs/decisions/draft-adr-generic-owner-physical-authority.md).
Interface shape and remaining hostile cases:
[`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md).
Class-owner admission, state, C# surface, and migration boundaries:
[`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).
Ordered generic-owner migration stages:
[`docs/programmes/generic-class-owner-migration-plan.md`](docs/programmes/generic-class-owner-migration-plan.md).

## Current blockers

- The external natural-MethodDef seal remains bounded to directly declared
  root/edge-free, constraint-free, declaration-local carrier shapes. Inherited,
  edge-bearing, constrained, and wider callable forms remain open.
- Trimming and NativeAOT remain deployment/freeze gates for the eventual
  selected family; they are not part of the now-closed bounded stage-5
  descriptor/overload proof.
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
