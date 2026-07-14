# Handover — Kotlin/.NET backend, interim development

Written 2026-07-14 for the next agent working on the `dotnet` branch (any model/harness).
**Read `AGENTS.md` in this directory FIRST — it is the binding design law.** This file only adds
session state, process, and a curated task menu. Keep both files updated as you work.

## Branch state

- Branch `dotnet`; latest functional tip `5a77b7435`
  ("[DotNet] Add interface delegation"), clean tree, based directly on
  `origin/master` (`995cf26a0`, rebased 2026-07-13).
  Handover-only maintenance stays in separate non-functional commits.
- Full DotNet suite: **320 tests, 0 failures, 0 errors, 0 skips** across 8 classes
  (`FirLightTree`/`FirPsi` × IlText/Box(+Strings,Typealias)); the separate generated CLI suite is
  **10 tests, 0 failures, 0 errors, 0 skips**.
- Landed feature slices, in order: executing box gate, final classes, exceptions/try-catch-finally,
  top-level properties/objects/companions, class inheritance, interfaces, hybrid nullability
  (`Nullable<T>` in exact positions, box-collapse at `Any?` boundaries), reified generics stage 1,
  exhaustive Boolean/Boolean? `when` without source `else`, primitive-array CLR vectors and
  indexed loops, constrained generics stage 2, invariant generic arrays stage 3, generic
  interfaces and declaration-site variance stage 4, generic member functions stage 5, generic
  class inheritance stage 6, abstract and sealed classes, abstract interface redeclarations,
  interface delegation through FIR's frontend-owned forwarding artifacts.
  Each has a design bullet in `AGENTS.md` — the bullets are accurate; trust but verify.
- Interim continuation landed `8702cf407`: JVM-shaped intrinsic registration for fir2ir's
  `noWhenBranchMatchedException`, emitting target-neutral `[mscorlib]InvalidOperationException`
  instead of Roslyn's modern-only `SwitchExpressionException`. `whenprobe_s1` settled the
  assembly-scope/Framework-compatibility decision; `whenprobe_s2` forced the exact golden's
  otherwise unreachable fallthrough with raw CLR `bool` value `2`, including a prior value on
  the evaluation stack. The new ilText/box pins cover both parser variants, statement/value
  positions, mapped catch handling, generic results and the non-first-argument stack shape.
- Interim continuation landed `d7915e827`: `cli/dotnet` now generates into its own top-level
  `DotNetCliTestGenerated.java`, so upstream regeneration of the shared `CliTestGenerated.java`
  no longer conflicts with DotNet test data. The same 10 tests pass in the new suite, and an
  explicit smoke filter preserves their selection after the nested-to-top-level move (Smoke-mode
  dry-run discovers all 10). The fresh backend suite remains 270/0/0/0.
- Interim continuation landed `08931c5c7`: `IntArray`, `LongArray`, `DoubleArray`,
  `BooleanArray`, and `CharArray` map to native CLR vectors. JVM-shaped registry intrinsics cover
  unary construction, literal factories, `size`, `get`, and `set`; direct `for` loops use the
  backend.common indexed-get shape. `arrprobe_s1` verified exact signatures/opcodes and runtime
  faults on modern CoreCLR and .NET Framework. A negative-size guard prevents CLR
  `OverflowException` from creating a false Kotlin `ArithmeticException` catch edge. Literal and
  indexed operands spill to locals because CLR protected regions require an empty entry stack.
  Nullable/object/generic storage and array identity equality work; generic `Array<T>`, unsupported
  scalar arrays, initializer constructors, spreads, escaping iterators, and copy/content helpers
  reject. Contrary to the old task-menu guess, no fake-stdlib declarations were needed: fir2ir
  already supplies the primitive-array builtins and `*ArrayOf` calls.
- Interim continuation landed `fffb99e14`: supported direct, non-null, non-generic module-local
  class and all-abstract interface bounds now remain on CLR generic method/class metadata and on
  the backend's structural `!n`/`!!n` type. Bound virtual/interface calls spill receiver and
  arguments, reload the receiver address, and emit `constrained.` immediately before `callvirt`;
  non-virtual class members and bound/`Any` widening use `box !n`/`!!n`. The spills preserve source
  order and the CLR empty-stack rule around argument-side `try`. The bound walk also recovers an
  instantiated generic declaring owner inherited by a non-generic bound. `genconstraintprobe_s1`–
  `_s2` verified metadata, interface/class dispatch, boxing identity, and external value-type
  interface instantiations on CoreCLR 10.0.9 and .NET Framework 4.8; the final positive golden
  assembles under both ILAsm versions and executes on Framework. New ilText/rejection/box pins run
  under both FIR parser variants. Nullable, generic-instantiation, type-parameter, builtin, mapped,
  unavailable, equality/Any-member, unconstrained-widening, variance, and `T?` shapes still reject.
- Interim continuation landed `344ae86f2`: invariant `Array<E>` maps to a structural CLR vector
  for reference-shaped or open `!n`/`!!n` elements. The JVM-shaped registry owns `arrayOf`,
  `emptyArray`, reference-element `arrayOfNulls`, `size`, `get`, and `set`; direct `for` loops
  reuse the indexed lowering. Literal/get/set operands spill for protected-region safety. The
  structural kind stays distinct from `PrimitiveArray`, so backend assignability never admits CLR
  covariance. Concrete primitive elements reject because CLR would collapse `Array<Int>` and
  `IntArray` to the same `int32[]` ABI; projections, nullable value elements/`Array<T?>`, nested
  arrays, initializer lambdas, spreads, iterator escape, casts, and copy/content helpers also stay
  out. `genarrayprobe_s1` verified `T[]` metadata, typed element opcodes for reference and value
  instantiations, bounded dispatch, and the CLR covariant-store check on CoreCLR 10.0.9 and
  Framework 4.8. Both final goldens assemble and execute on both runtimes. The feature also closes
  the existing main-detector gap: `main(args: Array<String>)` now emits the valid CLR
  `.entrypoint` `main(string[])` shape.
- Interim continuation landed `7d54b3a82`: top-level all-abstract generic interfaces now emit
  as real reified CLR interfaces. Their `out`/`in` parameters preserve `+`/`-` metadata; full
  open, closed, transitive, and permuted interface instantiations remain in the structural
  supertype graph and on every `implements`/member-owner token. Generic classes may implement
  supported interface instantiations. Assignability applies CLR covariance/contravariance only
  when both differing arguments are statically reference-shaped; exact value instantiations
  remain supported, while value/open-parameter variant conversions, use-site projections/stars,
  nullable type-parameter slots, and unsupported bounds reject.
  `genifaceprobe_s1` verified metadata, constraints, interface inheritance, and dispatch on
  CoreCLR 10.0.9 and .NET Framework 4.8. Both new goldens assemble under both ILAsm versions;
  the positive golden and expanded box test execute on both runtimes. The final FIR suite is
  294/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `4768b7763`: non-inline generic methods are now supported on every
  otherwise-supported class, object, companion, and all-abstract interface. Generic owners keep
  their `!n` space independent from a method's `!!n` space across declarations, calls, nested
  generic owner tokens, inherited interface views, and instantiated generic base overrides/super
  calls. The override return-type pre-pass now identity-substitutes open method parameters while
  substituting only the generic owner view; the new test exposed the old path's internal crash.
  Inline/reified methods, nullable type-parameter slots, generic/type-parameter bounds, and generic
  properties remain rejected. `genmemberprobe_s1` verified class/interface methods, combined
  owner/method parameters, constraints, and nested companion owner tokens on CoreCLR 10.0.9 and
  Framework 4.8. Both exact goldens assemble under both ILAsm versions; the positive golden runs
  identically on both runtimes. Runtime pins include inherited virtual methods satisfying generic
  interface slots, constrained calls, arity overloads, objects, companions, member extensions,
  nullable method instantiations, and generic virtual/super dispatch. The final FIR suite is
  300/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `5cc01c4bc`: supported generic classes may now extend module-local
  generic bases through mapped closed, open, permuted, nested, generic-array, concrete-nullable,
  fixed, and constrained instantiations across arbitrary chains. Full base tokens remain in the
  prelinked structural graph and are recursively substituted at each hop, so constructor calls,
  overrides/super calls, inherited generic methods, inherited interface slots, and open base or
  interface upcasts recover the exact declaring-owner view. Invalid base arguments and evicted
  bases reject the whole derived chain while unrelated valid instantiations survive.
  `geninheritprobe_s1` verified multi-hop tokens, constructors, overrides, generic methods,
  interfaces, and constraints on CoreCLR 10.0.9 and .NET Framework 4.8. Both new goldens assemble
  under modern and Framework ILAsm; the positive golden executes identically on both runtimes.
  The final FIR suite is 306/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `f90b08a1a`: top-level plain abstract and sealed classes now emit
  as ordinary CLR `abstract` types; Kotlin sealing remains frontend-enforced. New abstract
  functions/accessors use `newslot abstract virtual`, abstract base overrides reuse their slot
  with `abstract virtual`, and open/concrete members, constructors, state, companions, generic
  owners/methods, constraints, and mapped inheritance keep their existing machinery. A pure
  abstract interface obligation may remain only as a fake override on an abstract owner with no
  emitted method; concrete descendants introduce or reuse the implementation slot, while
  concrete owners remain strict. `abstractprobe_s1` verified metadata, constructor chains,
  re-abstraction, interface mapping, and generic substitution; `abstractprobe_s2` verified both
  new-slot and slot-reuse concrete implementations after a methodless abstract interface carrier.
  Both probes and the exact positive golden assemble and execute identically on CoreCLR 10.0.9
  and .NET Framework 4.8. Runtime pins also cover mutable abstract properties, abstract generic
  calls through abstract views, generic sealed owners, constrained dispatch, companion factories,
  and state. The final FIR suite is 310/0/0/0; the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `a35ec8319`: an abstract interface function or accessor may now
  redeclare an inherited member, emitting another `newslot abstract virtual` slot. One class
  member with an exact signature fills the original and every redeclared slot, including repeated
  and diamond redeclarations, mutable properties, independent generic methods, composed generic
  owners, and implementations inherited virtually from a base class. The existing mapped-return
  pre-pass still rejects covariant redeclarations whose CLR return signatures differ, while
  nullability covariance mapping to the same IL type survives. `ifaceredeclareprobe_s1` and both
  exact goldens assemble and execute identically on CoreCLR 10.0.9 and .NET Framework 4.8.
  The preceding `dimprobe_s1` audit found the next hard boundary: modern ILAsm/CoreCLR runs a
  Default Interface Method, but Framework 4.8 ILAsm rejects a non-static interface method body,
  so DIM and `super<I>` remain loudly unsupported unless the runtime floor is deliberately raised.
  The final FIR suite is 316/0/0/0; the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `5a77b7435`: FIR interface delegation now renders through the
  ordinary member pipeline. Constructor-property delegates reuse their private backing field;
  plain parameters, expressions, bounded type parameters, and `var` delegates use FIR's private
  `$$delegate_n` field, initialized after the base constructor and before later member state.
  Forwarding composes with functions/accessors, mutable properties, generic owners and methods,
  multiple delegates, inherited interface redeclarations, explicit overrides, constrained type
  parameters, and inherited virtual class implementations. Reassigning a `var` keeps forwarding
  to the initially captured delegate, matching JVM behavior. An unavailable delegated interface
  still cascades whole-class, including mixed supported/unsupported delegation.
  `delegationprobe_s1` and both exact goldens assemble and execute identically on CoreCLR 10.0.9
  and .NET Framework 4.8; runtime pins cover initialization order, one-time capture, constrained
  calls, and base/interface dispatch. The final FIR suite is 320/0/0/0; the generated CLI suite
  remains 10/0/0/0.
- `git stash@{0}` holds a superseded partial implementation (object-boxing nullability, replaced
  by the hybrid model). It is droppable; do not build on it, do not touch it otherwise.
- `.claude/settings.json` contains `"worktree": {"bgIsolation": "none"}` — deliberate; leave it.
- `.gradle/codex-backend-dotnet.stop` is the ignored autonomous-work stop flag. It is currently
  `false`; set it to `true` to have the agent finish its current coherent feature, update this
  handover, and stop before starting another one.

## Hard rules (the ones that bit us; violations have all caused real damage)

1. **Probe first.** Any IL spelling not already golden-pinned must be verified by assembling and
   RUNNING an ilasm probe before it lands in codegen. Probe series naming: one series per feature
   (`statprobe`, `excprobe`, `objprobe`, `fieldprobe`, `inheritprobe`, `ifaceprobe`, `boxprobe`,
   `genprobe`, `genconstraintprobe`, `genarrayprobe`, `genifaceprobe`, `genmemberprobe`,
   `geninheritprobe`, `abstractprobe`, `dimprobe`, `ifaceredeclareprobe`, `delegationprobe`,
   `whenprobe`, `arrprobe` are taken). Keep probe files OUT of the repo (use a temp dir).
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

1. **Audit ordinary named nested classes.** Companion objects already prove the CLR nested
   registration, type-reference, recursive-render, fixpoint-eviction, and generic-outer machinery.
   Determine which parts can be generalized safely to `class Outer { class Nested }`, following
   the JVM's static-nested semantics while using real CLR nested metadata. Probe visibility,
   construction, calls/fields, multi-level nesting, forward references, generic nested classes,
   and a nested class inside a generic outer on both runtimes before lifting the existing
   enclosing-class gate. Keep `inner`, local/anonymous, enum/data, and nested-interface support out
   of the first slice unless their IR and CLR requirements are independently settled.

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
