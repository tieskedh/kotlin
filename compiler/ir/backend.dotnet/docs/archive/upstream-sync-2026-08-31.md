# Upstream integration rehearsal — 2026-08-31

## Exact scope and preservation

This rehearsal moves the reviewed upstream base from
`c72fbd7b4e4ee01698c08204796ddfc43383d642` to the pinned upstream commit
`2868cfb88a7ea111ea6f6bf02f24430dc0e039e5`. The upstream range contains 174
linear commits. The pre-rebase .NET head was
`8e298848435746800f4e554d2893aee9a8eddcb5`; it contains 672 target commits
above the old base and remained the exact local and remote `dotnet` head while
the rehearsal ran.

The old head is retained locally as
`codex/pre-rebase-dotnet-20260831-retained-authority`. Promotion must publish it
under the distinct remote rollback ref
`rollback/dotnet-20260831-retained-authority`, then update remote `dotnet` only
with an exact `--force-with-lease` against `8e298848435746800f4e554d2893aee9a8eddcb5`.

The rebase ran in a dedicated worktree. A virtual merge first produced tree
`0abcab449e8c56c0c0e20eed3a11cf6c59bf908c` without a textual conflict. The
commit-by-commit rehearsal then replayed all 672 commits without stopping and
produced pure-rebase head `5b811c5032118937dee3dea9acea8862c95cedea`.
Its tree is exactly the virtual-merge tree.

The complete range-diff accounts for every old target patch:

- 671 patches are identical;
- 1 patch is context-adjusted;
- no old patch is missing; and
- no replay-created patch exists.

The context-adjusted patch is `a50a1bf016` to `56bb39377d` (`[DotNet]
Register Test Federation domain`). Its generated domain entries are unchanged;
the patch follows upstream's directory rename from
`repo/test-federation-runtime` to `repo/test-runtime` and no longer owns an
unrelated end-of-file newline repair.

The histories touch 18 common paths:

- `compiler/cli/cli-base/build.gradle.kts`;
- `compiler/cli/cli-metadata/build.gradle.kts`;
- `compiler/cli/src/org/jetbrains/kotlin/cli/common/FirSessionConstructionUtils.kt`;
- `compiler/config/configuration-keys-generator/build.gradle.kts`;
- `compiler/fir/entrypoint/build.gradle.kts`;
- `compiler/fir/fir2ir/build.gradle.kts`;
- `compiler/test-infrastructure/build.gradle.kts`;
- `compiler/tests-integration/build.gradle.kts`;
- `core/compiler.common/src/org/jetbrains/kotlin/util/UnitStats.kt`;
- `libraries/tools/kotlin-gradle-plugin-api/api/kotlin-gradle-plugin-api.api`;
- `libraries/tools/kotlin-gradle-plugin/api/all/kotlin-gradle-plugin.api`;
- `libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/compilerRunner/GradleKotlinCompilerRunner.kt`;
- `libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/registerKotlinPluginExtensions.kt`;
- `libraries/tools/kotlin-stdlib-gen/build.gradle.kts`;
- `prepare/compiler/build.gradle.kts`;
- `repo/domains.dump.txt`;
- `repo/kotlin-build-helpers/src/CompilerModules.kt`; and
- `settings.gradle.kts`.

Every endpoint contains the compatible facts from both histories. In
particular, settings use upstream's `repo:test-runtime` while retaining every
.NET module registration, and the compiler-module and API lists retain both
upstream additions and the .NET entries.

## Semantic audit

Git's clean replay is not semantic proof. All 174 upstream commits were
classified, and changes outside the direct path intersection were checked for
target-owned reverse dependencies.

Upstream now propagates metadata-compilation mode into the single FIR source
session and marks its module data as Common. That composes with .NET's existing
optional metadata providers and explicit choice to preserve separate Common
and platform sessions for expect/actual compilation. The merged function
contains both facts; neither side reconstructs the other's session policy.

Upstream enables type checking in `DEFAULT_IR_ACTIONS` and removes the custom
action parameter from Common phase factories. .NET calls those factories only
through the surviving ordinary signature and has no dependency on the removed
parameter. The stricter validator is nevertheless a semantic gate because it
can expose temporarily ill-typed target IR between lowerings; the full target
corpus below passed with the upstream default.

`IrModuleDependencies` now exposes only `allDependencies`, and the base linker
adds authoritative lists of its deserializers and fragments. The target has no
direct use of the removed `all`, `stdlib`, or `included` properties and did not
require a compatibility shim. The static fake-override alignment, corrected
generic version-overload result type, nullable-`Nothing` cast correction, and
multiple-expect actualizer changes all remain owned by Common FIR/IR. They do
not create physical CLR authority or permit a later logical substitution to
rewrite an emitted or retained MethodDef.

The source-built Common Stdlib collection declarations did not change in this
upstream range. Stdlib source changes are confined to Wasm internals and JVM
reflection, plus build configuration. The paused generic-owner/Stdlib blocker
therefore remains the same architecture problem after synchronization.

Upstream consolidates test runtime modules and makes `test-federation` and
`project-tests` conventions mandatory through `common-configuration`. Existing
upstream projects were mechanically cleaned, but the eleven target-owned .NET
projects did not exist upstream and consequently retained redundant plugin
applications after the pure replay. This is the only required target-specific
adaptation found by the audit.

The new Kotlin Archive publication API and its generated KGP baselines compose
with the existing `.NET` platform enum. A focused KGP API check confirms that
the merged public surface and checked-in dump agree.

## Post-rebase adaptation

Commit `2a49a2690f` removes explicit `test-federation-convention` applications
from all eleven target-owned .NET modules and explicit
`project-tests-convention` applications from the two .NET test owners. Every
module already applies `common-configuration`; all test tasks, domains, inputs,
and dependencies remain unchanged. No compiler source, target runtime, logical
IR, physical CLR metadata, or generic-owner rehearsal rule changed.

## Verification

The adapted backend and its tests compile:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin -q
```

The merged Kotlin Gradle plugin API baseline passes:

```text
.\gradlew.bat :kotlin-gradle-plugin:apiCheck -q
```

The strict target command then completes successfully in Full domain mode:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit records 205 suites and 2,621 tests:

- 15 backend suites with 228 tests;
- 1 `dotnet.ir` suite with 6 tests;
- 187 FIR2IR suites with 2,259 tests; and
- 2 integration suites with 128 tests.

All four roots report zero failures, errors, or skips. The gate covers both FIR
frontends, Framework 4.8 and .NET 10, Runtime/Stdlib production, C# interop,
separate compilation, the current physical-authority rehearsal, and the exact
production-erased inverse. The Windows Perflib warning emitted by Gradle's
resource monitor is unchanged environmental noise and did not affect either
exit code.

## Promotion boundary and consequence

This synchronization changes no accepted Kotlin semantic rule, CLR physical
authority rule, generic-owner ABI number, artifact schema, runtime surface, or
production-switch decision. Production remains atomically erased. The
retained-foreign adapter remains rehearsal-only and the next feature remains
one bounded imported operation through shared operation/value provenance.

Promotion is permitted only from this clean tested lineage, after publishing
the named rollback ref and using the exact remote lease above. The rebase does
not authorize advancing the source-built Stdlib census or weakening the
one-object, one-state, no-fabricated-construction invariants.
