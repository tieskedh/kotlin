# Upstream integration record — 2026-09-05

## Exact scope and preservation

The reviewed upstream base moved from
`2868cfb88a7ea111ea6f6bf02f24430dc0e039e5` to the pinned
`origin/master` commit `88a184ab89279617dbfe4e89ba9831ed1b43c863`.
That upstream range contains 291 linear commits and no merge commits. The
pre-rebase .NET head was
`7d319409fa76dee4ac800f5247219d411f114b39`; it contains 733 target commits
above the old base and is published unchanged as
`fork/rollback/dotnet-20260905-generic-sam`.

The isolated replay completed all 733 commits without a conflict or empty
commit and produced pure-rebase head
`53b0bf147b5f584efca25371390fe73f249ef071`. A complete `git range-diff`
between the old and replayed target ranges records:

- 732 identical patches;
- one context-adjusted patch;
- no dropped patch; and
- no added patch.

The only context-adjusted patch is the existing
`[DotNet] Add the built-in Gradle target` commit
(`37f3b0a003` to `0e83c5f09f`). Its target changes are unchanged; only adjacent
upstream context moved. The pure replay tree is
`91bd1b9f47bff16df87bee79252a9cea54275b86`, exactly equal to a conflict-free
virtual merge of the old target head and the pinned upstream commit.

The replay and all verification ran in the isolated worktree
`kotlin-dotnet-rebase-20260905`. The ordinary `dotnet` checkout was not used as
an experimental merge workspace.

## Shared-path and reverse-dependency audit

The two histories overlap on 14 paths:

- compiler argument JSON and the generated Build Tools API surface;
- the CLI base build and shared FIR-session construction;
- generated FIR non-suppressible diagnostic names;
- FIR2IR lazy-class and .NET test-generation infrastructure;
- both generated Kotlin Gradle plugin API dumps;
- `KotlinMultiplatformExtension`;
- the prepare-compiler build;
- Test Federation domain source, generated domain output, and
  `CompilerModules`.

The exact overlapping paths are:

```text
compiler/arguments/resources/kotlin-compiler-arguments.json
compiler/build-tools/kotlin-build-tools-api/api/kotlin-build-tools-api.api
compiler/cli/cli-base/build.gradle.kts
compiler/cli/src/org/jetbrains/kotlin/cli/common/FirSessionConstructionUtils.kt
compiler/fir/checkers/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/FirNonSuppressibleErrorNames.kt
compiler/fir/fir2ir/src/org/jetbrains/kotlin/fir/lazy/Fir2IrLazyClass.kt
compiler/fir/fir2ir/testFixtures/org/jetbrains/kotlin/test/TestGeneratorForFir2IrTests.kt
libraries/tools/kotlin-gradle-plugin/api/all/kotlin-gradle-plugin.api
libraries/tools/kotlin-gradle-plugin/api/external/kotlin-gradle-plugin.api
libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/dsl/KotlinMultiplatformExtension.kt
prepare/compiler/build.gradle.kts
repo/domains.yaml
repo/gradle-build-conventions/test-federation-convention/src/main/generated/domains.kt
repo/kotlin-build-helpers/src/CompilerModules.kt
```

Each overlap was reviewed against both parents rather than accepted merely
because Git replayed it cleanly. Thirteen paths retain an identical stable
patch ID for the .NET delta. `KotlinMultiplatformExtension.kt` differs only
because upstream added Swift Export state and API in the same class and import
context; manual comparison confirms that the complete 19-line .NET DSL delta
and the upstream Swift Export additions both remain intact. The generated
JSON/API/domain outputs match their owners, the .NET CLI module and Test
Federation domain remain registered, and the FIR/FIR2IR additions retain both
upstream behavior and the target's existing hooks. No overlap required a
semantic .NET workaround.

Reverse-dependency inspection also covered the upstream test-framework,
property-reference, foreign-class-usage, Gradle convention, compiler-module,
FIR lazy-class, and generated API changes. Three bounded adaptations were
required after the mechanically identical replay.

## Post-rebase adaptations

### Target-model build conventions

Commit `cca408f805` updates `core/language.targets.dotnet` to the repository's
new foreign-class-usage registration API. It removes the target-local JDK 8
toolchain selection because that is now supplied by the shared convention.
The module compilation and API-surface check pass with no target-specific
replacement convention.

### Null physical-ABI declarations

Commit `a5b998a478` makes the physical ABI encoder reject a null declaration
value explicitly. Upstream's stricter platform-nullability makes this check
necessary at the Java-map read boundary: it makes the encoder's existing
non-null declaration invariant explicit rather than admitting malformed
persisted ABI input. It is fail-closed and changes neither the physical-library
ABI number nor the serialized schema for valid declarations.

### Rich property-reference parameters

Commit `0d460a7bdc` updates the .NET property-reference IL golden after upstream
FIR2IR began producing rich property references directly. Five generated
setter-adapter parameter names change from positional `pN` names to `value`.
Method types, arity, instructions, calls, captures, interfaces, `MethodImpl`s,
reflection identity, object identity, and state are unchanged. PSI, LightTree,
and cross-assembler focused coverage all accept the new output; no .NET
lowering was added to recreate the former Common wrapper names.

## Verification

The focused generic-owner gates were run before the full target lane. Four
high-risk fixtures cover the generated generic SAM wrapper, hardest-model fake
override, exact current-receiver capture, and widened inline temporary through
`finally`. Across PSI and LightTree, Framework 4.8 and .NET 10, the candidate
mode reports 16 passing tests. The production-erased inverse and adjacent
upstream-risk matrix report 40 passing tests. Neither mode has failures,
errors, or skips.

The first fresh dependency-wide run found only three FIR2IR golden failures,
all the same upstream rich-property-reference parameter-name change described
above. After the bounded golden commit, FIR2IR was rebuilt fully and direct
JUnit XML audit recorded 187 suites and 2,283 tests with zero failures,
errors, or skips.

A later aggregate attempt saw one `FileNotFoundException` while publishing
`Canonical.Provider.il`. The exact test then passed in isolation and again in
the complete integration lane. Independent inspection established that:

- all five involved backend, metadata, test, temp-directory, and Gradle-test
  files are byte-identical at the old and rebased heads;
- the test uses a unique directory and same-thread JUnit execution;
- no compiler path between directory creation and publication removes the
  parent directory; and
- the 229-character path is below the legacy Windows path limit.

The failure is therefore not evidence of a rebase semantic regression. It
does expose a pre-existing robustness gap: two `mkdirs()` calls ignore their
result. No speculative production change was included after one
non-reproducible failure. If it recurs, the general fix is checked directory
creation immediately at the backend publication boundary, not a
test-specific `mkdirs()` call.

The complete integration task was then rebuilt explicitly:

```text
.\gradlew.bat --max-workers=1 :compiler:tests-integration:dn --rerun --no-configuration-cache -q
```

It reports two suites and 128 tests with zero failures, errors, or skips. The
public aggregate subsequently completed successfully:

```text
.\gradlew.bat --max-workers=1 :compiler:backend.dotnet:dotNetTest -q
```

Direct audit of all preserved JUnit XML roots at implementation head
`0d460a7bdc3b64d55ecc3602034ee859b3478fb2` records 212 suites and 2,817 tests:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 22 | 400 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,283 |
| integration | 2 | 128 |

Every root reports zero failures, errors, or skips. The lane covers both FIR
frontends, Framework 4.8 and .NET 10, source-built Runtime/Stdlib products,
C# interop, separate compilation, the ABI-69/schema-22 generic-owner
rehearsal, and the production-erased inverse.

## Promotion boundary and consequence

The rollback ref was published before changing `fork/dotnet`. Promotion must
use an exact force-with-lease against old remote head
`7d319409fa76dee4ac800f5247219d411f114b39`; no unrelated remote movement may
be overwritten. The local `dotnet` checkout and `fork/dotnet` then move to the
same verified documentation head.

This upstream integration changes no accepted Kotlin/.NET representation,
generic-owner ordering, BK-1 scope, production-erased contract, or atomic
cutover rule. It removes the pending 291-commit upstream blocker. Work resumes
at the source-built Runtime/Stdlib generic-owner census beyond the completed
generated-SAM-owner slice; the next failure must still select a structural
provenance, placement, operation, or state rule rather than a declaration- or
stdlib-specific exception.
