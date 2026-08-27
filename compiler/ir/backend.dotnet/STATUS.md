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
- The current semantic checkpoint includes the bounded same-TypeDef retained-
  foreign CLR variance diagnostic and late physical-boundary closure, followed
  by the production-inert complete-surface variance planner and IR shadow. Git
  owns the exact checkpoint identity; this snapshot records only its verified
  state.
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

The strict target aggregate passed on 2026-08-27 at the current late
physical-boundary checkpoint:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 201 suites and 2,525 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 11 | 148 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,243 |
| integration | 2 | 128 |

The focused retained-CLR variance lane also passed with both FIR parsers and
both runtime profiles:

```text
.\gradlew.bat :compiler:tests-integration:test `
  --tests "org.jetbrains.kotlin.cli.DotNetLibraryIntegrationTest.testForeignClrVarianceRejectsVerifierInvalidBoundaryConversions" -q
```

The matrix proves legal exact/reference-only conversions, conservative unknown
reference relations, source-diagnosed explicit conversions, and late-fatal
local and MethodDef-argument joins. The final emitter consults exact retained
TypeDef identity only after normal physical assignability and coercions fail;
it never reconstructs Kotlin logical subtyping. The target remains production
erased.

The backend compilation, scoped Build Tools generators/API check and metadata
argument compatibility test, and Gradle statistics schema test also pass.
The repository-wide Build Tools forward suite remains environment-blocked on
this Windows host because its hostile-path test requires symlink privilege;
no compiler assertion failed. The installed-distribution and focused KGP
surfaces are being checked separately because they are not constituents of
`dotNetTest`.

Markdown-only commits after the semantic checkpoint inherit this gate only
when their link, whitespace, and staged-file audits pass.

The later complete-surface variance work changes no selected carrier, IR body,
emitted metadata, artifact, or production ABI and therefore inherits the full
checkpoint under the focused/boundary policy. Its IR-free planner passed all
160 backend unit tests. Both the production-empty and rehearsal-bound modes
passed the exact probe once under PSI and once under LightTree on `net10`, with
two JUnit tests per mode and zero failures, errors, or skips.

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

The source-built Stdlib census is paused while the generic-owner architecture
is consolidated in shadow/rehearsal mode. The review selected two replacement
directions:

- one shared physical-declaration/value-provenance model, separate from
  producer-wide state selection; and
- one complete natural CLR-generic interface whose physical variance is
  weakened where its full contract requires it, plus semantic routing only for
  Kotlin views the CLR cannot name. Its general IR-free fixpoint and first
  production-inert IR shadow are now executable.

The former exact-sibling interface and natural-only public-method convention
remain implementation evidence, not the desired ABI. The current checkpoint
also has:

- a local final-emission signature certificate for one complete family;
- binder-correct MethodDef generic parameters and MethodSpec call routing; and
- a producer-recorded ABI-61 sealed-family certificate whose transport is
  independently validated but which is not yet a consumer route.

The first retained-foreign variance slice is closed at both ends. FIR rejects
explicit return/argument conversions between two constructions of the same
retained foreign TypeDef when the complete closed argument relation proves
that CLR variance would require reference arguments. At final emission, every
already-selected local, argument, return, field, and slot boundary rechecks the
actual physical producer and turns a recursively proven closed value-type
variance step into a module-fatal error rather than silently evicting an API.
Unknown reference hierarchies, open parameters, projections,
implementation/inherited roots, varargs, and ambiguous multi-view lineage
remain conservative unknowns for the shared provenance model.

The next bounded sequence is:

1. use the complete-surface shadow to prove one custom input-bearing interface
   as a single natural TypeDef without an exact sibling;
2. route broad foreign operations through real constructed interface MethodDefs
   and recorded Kotlin policy, never concrete public-method name/arity lookup;
3. migrate the rehearsal family and remove exact-sibling ABI only after the
   hostile inverse proves every retained behavior;
4. add retained-foreign final-evidence adaptation and prove static/file-facade
   operation authority;
5. prove overlapping and global family ownership without allowing earlier
   BOUND evidence to fill a missing final fact;
6. compose owner-dependent input policy with split-nullable result layout on a
   custom two-parameter lookup family before applying it to `Map`;
7. replace bounded recognizers only after the shared provenance model explains
   both their positive cases and hostile negatives; and
8. run the complete Runtime/Stdlib, separate Kotlin/C# assembly, Framework 4.8,
   .NET 10 JIT/ReadyToRun/trimmed/NativeAOT, production-erased inverse, and
   rollback decision gates.

Shared authority and provenance:
[`docs/decisions/draft-adr-generic-owner-physical-authority.md`](docs/decisions/draft-adr-generic-owner-physical-authority.md).
Interface shape and remaining hostile cases:
[`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md).
Class-owner admission, state, C# surface, and migration boundaries:
[`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).

## Current blockers

- The generic-owner sealed authority is not yet complete for retained foreign
  declarations, static/file-facade calls, or overlapping/global ownership.
- Imported CLR declaration-site variance is logically broader than the CLR's
  reference-only physical conversion. The closed same-TypeDef source and late
  physical-boundary slice is now complete, but inherited/implementation views,
  open or projected shapes, varargs, and ambiguous multi-view lineage still
  require the shared provenance model rather than local inference.
- The current exact-sibling rehearsal does not give ordinary CLR languages one
  complete statically checked natural interface. Its replacement proof has not
  yet been emitted or executed.
- The active Roslyn authoring analyzer still requires `partial` for every
  manifest contract outside a narrow invariant-natural fallback, including
  non-generic and old generic-capability shapes. That conflicts with the
  complete-natural-contract direction. The diagnostic cannot simply be
  disabled: producer metadata and compiler routing must first make generated
  capability unnecessary for admission, then the rule must be removed and an
  ordinary precompiled non-partial implementation proved.
- Raw `System.Array` is physically broader than Kotlin `Array<*>` and
  `Array<out E>`. Foreign-entry SZ-array and bounded-element guards are now a
  required decision but are not yet proven across every entry form.
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
