# Upstream integration rehearsal — 2026-08-27

## Exact scope and preservation

The candidate integration replays the target from the reviewed upstream base
`f9a1706ce08c497554ee47fde7c9e7e89508152c` to the pinned upstream commit
`c72fbd7b4e4ee01698c08204796ddfc43383d642`. The upstream range contains 253
linear commits. The pre-rebase .NET head was
`b84207935c084137f81261db4f46cb6202374cab`; it contains 640 target commits
above the old base. Before promotion it remained the exact remote `dotnet`
head and was retained locally as
`codex/pre-rebase-dotnet-20260827-producer-seal`. Promotion must first publish
that rollback ref under a distinct remote name and must move remote `dotnet`
only with an exact `--force-with-lease` against `b84207935c`.

The rebase was rehearsed in a dedicated worktree before any production branch
or remote ref moved. A virtual merge produced tree
`3e476e8af762cc919281905d8a959c06d81fcb11` without a textual conflict. The
commit-by-commit rehearsal likewise replayed all 640 commits without a stop and
produced pure-rebase head `f4e2bf22092d5e1f7f33555be28bdb31da2e04c9`.

The complete range-diff contains all 640 old target patches:

- 639 patches are identical;
- 1 patch is context-adjusted;
- no old patch is missing; and
- no replay-created patch exists.

The context-adjusted patch is `199b8ed375` (`[DotNet] Add ordinary inline
function support`). Its Common lowering hunk applied around upstream's new
direct validation-phase construction. Its .NET behavior remained present; the
now-obsolete target-only `includeLateinitLowering` switch was removed in a
separate adaptation after replay.

The two histories have 11 paths touched by commits on both sides:

- `compiler/build-tools/kotlin-build-tools-api/api/kotlin-build-tools-api.api`;
- `compiler/fir/fir2ir/src/org/jetbrains/kotlin/fir/backend/Fir2IrVisitor.kt`;
- `compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/ArrayConstructorLowering.kt`;
- `compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/CommonLoweringPhases.kt`;
- `core/compiler.common/src/org/jetbrains/kotlin/util/PerformanceManager.kt`;
- `libraries/tools/kotlin-gradle-plugin-api/api/kotlin-gradle-plugin-api.api`;
- `libraries/tools/kotlin-gradle-plugin/api/all/kotlin-gradle-plugin.api`;
- `libraries/tools/kotlin-gradle-statistics/src/main/kotlin/org/jetbrains/kotlin/statistics/metrics/StringListMetrics.kt`;
- `repo/domains.dump.txt`;
- `repo/kotlin-build-helpers/src/CompilerModules.kt`; and
- `settings.gradle.kts`.

The KGP API path has no endpoint difference because one side's historical
change is later reversed; the other ten are changed at both endpoints. Every
overlap was inspected and contains the compatible facts from both histories.
No whole target commit was dropped or replaced.

## Semantic audit

Git's clean replay was not treated as semantic proof. The audit additionally
covered changes outside the shared-path intersection.

Upstream's IR validation phases now own the same private/all-inline call-site
predicates formerly repeated by .NET. The target therefore uses those phases
directly. Upstream's new `ArrayConstructorLowering` prerequisite is satisfied
by `DotNetUpgradeCallableReferences`, while the target's evaluation-order and
integer-increment corrections remain intact.

The FIR2IR receiver-cast change removes or relocates some projected/flexible
`IMPLICIT_CAST` nodes. The full target corpus consequently remains the gate for
nullable `Nothing`, imported CLR receivers, stars/projections, and the BK-1
`is`/`as`/`as?` boundary. Such logical-flow changes may not rewrite a
producer-recorded MethodDef, retained foreign metadata, or an already proved
physical view.

The upstream inference series changes fixation, flexible nullability, and
second-kind incorporation. Imported CLR generic constraints and nullable
overloads therefore remain guarded by the existing PSI/LightTree and C#
interop corpus. No upstream producer-library or KLIB change requires advancing
rehearsal physical-library ABI 61. This does not independently prove that the
provisional schema is complete or ready to freeze.

Upstream also improves one-way FIR aliases, local-declaration parent patching,
and deserialized property origins. A one-way alias can refine a logical view
but cannot create physical authority. Patched local parents and deserialization
origins likewise cannot become logical producer keys. The existing hostile
generic-owner tests cover mutable joins, distinct exact constructions,
generated captures, properties, separate compilation, and fail-closed
producer records.

## Post-rebase adaptations

### Shared inline validation

Commit `610966e4c7` removes the two .NET wrapper factories whose constructor
callbacks no longer exist upstream and uses the shared phases directly. It
also removes the unused `includeLateinitLowering` Common API. That switch was
introduced only while .NET postponed Common `lateinit`; commit `7663331b0b`
enabled the ordinary Common path, leaving no false caller. The resulting
`CommonLoweringPhases.kt` is identical to upstream.

### Build Tools platform metadata

Upstream introduced a second generated, implementation-internal
`MetadataTargetPlatform`. The existing .NET patch predated that mirror, so a
textually clean replay left it without `DOTNET("DotNet")`. Commit `7762411e0b`
reruns the official BTA generators and records their one required output. The
public enum and API dump already contained the same value.

### Gradle statistics schema

Upstream removed two string-list metrics and advanced their schema from 4 to
5. The earlier .NET platform entry had not independently advanced the
version/hash, which was a latent repository-wide test defect not covered by
`dotNetTest`. Commit `375174e6ea` composes both changes, advances the schema to
6, and records checksum `16352a97fce94197b6e4ce7bb62bfd23`.

## Verification

The backend compiles at the adapted head. BTA sources were regenerated through
all three owning modules and the API dump was regenerated. The API check and
the metadata compiler-argument compatibility test pass. The targeted Gradle
statistics version/hash test also passes.

The strict target command completes successfully:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit records 201 suites and 2,524 tests:

- 11 backend suites with 148 tests;
- 1 `dotnet.ir` suite with 6 tests;
- 187 FIR2IR suites with 2,243 tests; and
- 2 integration suites with 127 tests.

All roots report zero failures, errors, or skips. This is exactly the
pre-rebase suite/test count and covers both FIR frontends, Framework 4.8 and
.NET 10, Runtime/Stdlib products, C# interop, separate compilation, the
generic-owner producer-seal rehearsal, and the production-erased inverse.

The repository-wide BTA forward-compatibility `check` was also attempted. Its
ordinary API and conversion prerequisites pass, but Windows rejects the
suite's hostile path symlink transformation with `A required privilege is not
held by the client` in `testEscapableCharacters2.4.0`. This is a machine
privilege gate, not a compiler or test assertion failure. Rerun that check from
a Developer Mode or elevated Windows environment; do not report it as green
from this machine state.

## Promotion boundary and consequence

The upstream integration changes no accepted generic-owner representation,
authority epoch, operation-routing order, or production-switch decision.
Production remains atomically erased. The transported producer seal remains
rehearsal-only and rollback remains exact through the retained pre-rebase ref.

This document records the tested rehearsal before ref promotion. Promotion is
complete only after the named remote rollback ref, exact-lease `dotnet` update,
and direct remote verification are recorded in current status or a follow-up
documentation commit.

Resume the generic-owner programme at the recorded next authority boundary:
retained-foreign sealed evidence and the static/file-facade operation route.
Do not advance the source-built Stdlib census merely because the upstream base
moved.
