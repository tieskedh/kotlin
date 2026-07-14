# Handover — Kotlin/.NET backend, interim development

Written 2026-07-14 for the next agent working on the `dotnet` branch (any model/harness).
**Read `AGENTS.md` in this directory FIRST — it is the binding design law.** This file only adds
session state, process, and a curated task menu. Keep both files updated as you work.

## Branch state

- Branch `dotnet`; latest functional tip `8702cf407` ("[DotNet] Support exhaustive when
  without source else"), clean tree, based directly on `origin/master` (`995cf26a0`, rebased
  2026-07-13). Handover-only maintenance stays in separate non-functional commits.
- Full DotNet suite: **270 tests, 0 failures, 0 skips** across 8 classes
  (`FirLightTree`/`FirPsi` × IlText/Box(+Strings,Typealias)).
- Landed feature slices, in order: executing box gate, final classes, exceptions/try-catch-finally,
  top-level properties/objects/companions, class inheritance, interfaces, hybrid nullability
  (`Nullable<T>` in exact positions, box-collapse at `Any?` boundaries), reified generics stage 1,
  exhaustive Boolean/Boolean? `when` without source `else`. Each has a design bullet in
  `AGENTS.md` — the bullets are accurate; trust but verify.
- Interim continuation landed `8702cf407`: JVM-shaped intrinsic registration for fir2ir's
  `noWhenBranchMatchedException`, emitting target-neutral `[mscorlib]InvalidOperationException`
  instead of Roslyn's modern-only `SwitchExpressionException`. `whenprobe_s1` settled the
  assembly-scope/Framework-compatibility decision; `whenprobe_s2` forced the exact golden's
  otherwise unreachable fallthrough with raw CLR `bool` value `2`, including a prior value on
  the evaluation stack. The new ilText/box pins cover both parser variants, statement/value
  positions, mapped catch handling, generic results and the non-first-argument stack shape.
- `git stash@{0}` holds a superseded partial implementation (object-boxing nullability, replaced
  by the hybrid model). It is droppable; do not build on it, do not touch it otherwise.
- `.claude/settings.json` contains `"worktree": {"bgIsolation": "none"}` — deliberate; leave it.

## Hard rules (the ones that bit us; violations have all caused real damage)

1. **Probe first.** Any IL spelling not already golden-pinned must be verified by assembling and
   RUNNING an ilasm probe before it lands in codegen. Probe series naming: one series per feature
   (`statprobe`, `excprobe`, `objprobe`, `fieldprobe`, `inheritprobe`, `ifaceprobe`, `boxprobe`,
   `genprobe`, `whenprobe` are taken). Keep probe files OUT of the repo (use a temp dir).
2. **Diagnostics, not crashes.** Unsupported IR fails via `dotNetUnsupported()` with a specific
   message; rejection granularity is the whole class (pair/property-group where AGENTS.md says so);
   eviction cascades with chained reasons. Never emit fallback IL. Never let a construction reach
   ilasm-rejected or JIT-poisoned output: interface/generic mapping mistakes characteristically
   assemble CLEAN and throw `TypeLoadException`/`MissingMethodException` lazily — that is why box
   coverage per dispatch shape is mandatory, and why reviews must assemble suspicious goldens.
3. **State your precedent.** Every feature decision names the mature target it follows
   (JVM/Roslyn/JS/Native) or states the CLR-specific reason for deviating. This is a real design
   gate, not paperwork — it caught several wrong designs.
4. **Do not pin frontend-rejected shapes.** If the Kotlin frontend rejects a construction
   (e.g. `Int? == Long?` → EQUALITY_NOT_APPLICABLE), it cannot appear in an ilText test; document
   it in AGENTS.md instead. The test harness crashes on frontend diagnostics.
5. **Never** edit `*Generated.java` by hand; regenerate with
   `./gradlew :compiler:fir:fir2ir:generateTests` (the aggregate `generateTests` may pull in
   unrelated broken modules — stay scoped). Runners generate into `build/tests-gen` (not committed).
6. **Never** bypass or dodge Smart App Control (no hash perturbation, no content restructuring to
   dodge the classifier). A SAC-blocked box test SKIPs; that is the designed behavior.
7. **Git:** never push; never touch other branches; per-feature commits directly on `dotnet` with
   a detailed what/why/how message (look at `git log` for the house style) ending with your own
   `Co-Authored-By:` trailer. Non-functional changes go in separate commits. No worktrees — work
   directly in this checkout.
8. **Bootstrap syntax:** this repo compiles with a 2.5 bootstrap that uses name-based
   destructuring `[a, b]` (several dotnet files already do). Positional `(a, b)` over data-like
   classes will not compile.

## Rituals

- **Run tests:** `./gradlew :compiler:fir:fir2ir:test --tests "*DotNet*" -q` — but do NOT trust
  the quiet console alone. Verify from the JUnit XML:
  `compiler/fir/fir2ir/build/test-results/test/TEST-*DotNet*.xml` (sum tests/failures/skipped).
  SKIPs are acceptable only for SAC/toolchain reasons; failures never.
- **Update goldens:** add/modify the `.kt`, then run with `-Pkotlin.test.update.test.data=true`
  (QUOTE the whole `-P...` argument in PowerShell or it gets mangled), then READ the generated
  `.txt` critically — auto-generated goldens will happily pin broken output (this happened: a
  golden once pinned duplicate IL methods that ilasm rejects).
- **Before every commit:** fresh `--rerun` of the full suite + XML verification; `git status`
  shows only intended files (no scratch test data — a prior session leaked `zzrev*` files);
  if you added/changed goldens, assemble at least the new ones with ilasm as a sanity check.
- **Toolchain** (modern, pinned 10.0.9): `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\ilasm\ilasm.exe`
  and `...\toolchain\dotnet\dotnet.exe`. Run a dll: put
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  in `x.runtimeconfig.json` next to it, then `dotnet.exe exec x.dll`. Repair with
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`.
- **Self-review adversarially.** The process that repeatedly caught real bugs here: after
  implementing, actively try to break your own gates with hostile Kotlin constructions, assemble
  the emitted IL, and run dispatch shapes on the real CoreCLR. Budget real time for this.

## Task menu (recommended order)

1. **Small infra: split dotnet CLI tests out of `CliTestGenerated.java`.** The dotnet section of
   that shared generated file conflicts on every upstream rebase. Give the `model("cli/dotnet")`
   group its own generated class (own `testGroupSuite` entry → e.g. `DotNetCliTestGenerated.java`)
   in `compiler/tests-integration/testFixtures/.../TestGeneratorForTestsIntegrationTests.kt`.
2. **Main course: arrays.** Next roadmap item. CLR has native vectors (`newarr`, `ldelem`/`stelem`,
   `ldlen`) — probe series suggestion: `arrprobe`. Design questions to settle probe-first:
   which Kotlin array types in scope (suggest primitive arrays `IntArray` etc. first — they map
   1:1 to `int32[]` and dodge generics interplay; `Array<T>` composes with stage-1 generics but
   adds covariance questions — CLR arrays are covariant, Kotlin's are invariant, so `Array<T>`
   likely needs `as`-free invariant discipline stated explicitly); creation (`IntArray(n)`,
   `intArrayOf(...)` need injected fake-stdlib declarations — see `DotNetStdlibSource` and the
   intrinsic-registry pattern; injected declarations must compile with ZERO diagnostics);
   `size`/`get`/`set`/indexing operators as intrinsics; `for (x in array)` via the existing
   for-loop lowering. Everything else (copyOf, iterators as objects, Array<T?>): reject loudly.
3. **Generics stage 2 (if appetite remains): constraints.** `T : Base` / `T : Iface` → CLR
   constraint clauses (`class ... where` in IL: `<(class 'Base') T>` — probe the spelling).
   Unlocks member calls on `T` receivers bounded by a supported interface. Variance and `T?`
   remain out (see the generics bullet for why).

## Known warts (fine to leave; do not "fix" casually)

- `x != null` on `Int?` emits a redundant double negation — semantically correct, cosmetic only.
- `emitTypeOperatorCall`'s outer-coercion tail is mostly dead code (interception happens earlier).
- The upstream sync recipe (shallow clone!) is in the commit `ea4c43a26` message and boils down
  to: `git fetch origin`, dry-run with
  `git merge-tree --write-tree --merge-base=<current-base> dotnet origin/master`, then
  `git rebase --onto origin/master <current-base> dotnet` where current-base = `995cf26a0`.
  Not urgent; skip unless asked.

## When handing back

Leave this file updated: what you landed (commit hashes), what you started but did not finish,
any new probe series/decisions, and anything you discovered that contradicts AGENTS.md.
