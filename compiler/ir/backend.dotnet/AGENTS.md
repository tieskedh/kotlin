# Kotlin/.NET target bootstrap contract

This file contains the small set of rules needed before changing Kotlin/.NET.
It is not an architecture summary or implementation diary.

Before acting, read:

- [`STATUS.md`](STATUS.md) for the reviewed base, latest gate, active work, and
  current blockers;
- [`docs/README.md`](docs/README.md) for document authority and the decision/
  programme/archive index; and
- the owning ADR and active programme before changing representation, physical
  ABI, metadata, artifacts, or public interop.

The target is a pre-ABI Kotlin-to-CIL prototype. Do not describe this working
position as a Kotlin core-team decision unless the repository contains one.

## Authority and priorities

Implementation authority descends in this order:

1. Kotlin language semantics and authoritative Common/generated stdlib source;
2. repository-wide compiler contracts and generated-source ownership;
3. accepted Kotlin/.NET ADRs;
4. active programme gates and current status; and
5. reverified historical evidence.

When several correct designs remain, prefer:

1. Kotlin Common behavior and shared compiler/source machinery;
2. a truthful, idiomatic CLR and C# representation; and
3. real CLR generics, typed state, and typed calls wherever complete evidence
   permits them.

The later priorities never weaken an earlier one. A current backend limitation
is not a CLR constraint, and convenient BCL behavior is not permission to fork
Kotlin semantics.

## Non-negotiable representation rules

- Kotlin IR/KLIB owns logical Kotlin declarations, types, variance,
  nullability, overrides, and semantics. Emitted or retained CLR metadata owns
  the corresponding physical CLR declarations and signatures.
- Never reinterpret a previously emitted MethodDef or retained foreign CLR
  declaration from a later logical type approximation.
- Never fabricate a constructed CLR view such as `I<object>` when the runtime
  object is only known to implement `I<!T>`.
- A Kotlin object has one receiver identity and one authoritative state. Do not
  introduce a wrapper, proxy, duplicate field graph, or shadow state merely to
  repair Kotlin/CLR representation.
- Prefer a proven natural CLR route. Use a semantic/capability route only where
  the Kotlin operation requires a view the CLR cannot truthfully name. A broad
  semantic input must not erase unrelated exact receiver-derived state, and
  exact provenance must not narrow a genuinely broad source value.
- Production Kotlin-owned generic classes and interfaces remain on their
  accepted erased ABI until one complete rehearsal passes and the entire
  family is switched atomically. A test-only generic family is not production
  authority.
- Kotlin semantics remain authoritative when CLR runtime checks are stronger.
  The only accepted exception is the exact BK-1 scope recorded in the
  [semantic-authority decision](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and [breaking-change ledger](docs/decisions/breaking-kotlin-changes.md).
- A warning, `@Suppress`, `@UnsafeVariance`, reification, or convenient CLR
  runtime check is not a general semantic waiver.
- Imported CLR generic declarations retain their native TypeDef, MethodDef,
  construction, and variance identity. Never route them through the erased or
  rehearsal ABI for Kotlin-owned generic declarations. Native variance permits
  only verifier-valid reference-argument conversions; Kotlin logical variance,
  boxing, or an open parameter never fabricates a value-type CLR conversion.
- An ordinary CLR-language implementation of an admitted Kotlin-owned
  interface satisfies one complete statically checkable natural CLR contract.
  Compiler/runtime lowering owns compiler-derivable semantic routes; generated
  foreign source must not implement hidden compiler ABI even as an optimization.
  Optional tooling may forward declared defaults or build a visible, explicitly
  selected adapter. A genuinely non-derivable route needs such an explicit
  adapter/diagnostic or remains unadmitted.
- Kotlin-produced libraries are one self-describing DLL containing their KLIB
  and physical binding data. Do not create a sibling standalone KLIB or infer
  Kotlin-only contracts from CLR annotations.
- Nothing has shipped. Correct an unsound prototype contract atomically, bump
  its schema/surface, reject stale artifacts, and do not add compatibility
  shims for unpublished identities.

Feature-specific rules belong in the ADRs indexed by
[`docs/README.md`](docs/README.md), especially the accepted erased-owner
decisions and the draft generic-owner reopening model.

## Module and dependency boundaries

| Owner | Responsibility |
| --- | --- |
| `:core:language.targets.dotnet` | Logical platform and target-framework vocabulary |
| `:compiler:config.dotnet` | Generated compiler keys and target/product policy |
| `:compiler:frontend.common.dotnet` | Objective PE/ECMA-335 facts and validation |
| .NET FIR modules | Kotlin projection, contracts, symbols, and diagnostics |
| `:compiler:dotnet.imports` | Neutral versioned retained-declaration carriers |
| `:compiler:cli:cli-dotnet` | Pipeline orchestration and configuration application |
| `:compiler:ir:backend.dotnet` | Kotlin representation, lowerings, codegen, and products |
| `:compiler:ir:serialization.dotnet` | .NET KLIB IR and logical mangling |
| `:dotnet:dotnet.ir` | Policy-free physical CLI model, validation, and serialization |
| Shared KLIB/ABI modules | Embedded logical metadata and neutral physical-ABI codecs |
| Gradle/packaging modules | Target model, publication, installation, and layouts |
| Roslyn authoring project | Explicit C# source generation and analyzers |

Enforce the direction implied by the table:

- objective CLR parsing creates no Kotlin types, contracts, or diagnostics;
- target vocabulary/configuration imports no FIR, IR, backend, CLI, Gradle, or
  Roslyn implementation;
- FIR and backend may share a neutral carrier but may not own a carrier needed
  by the other;
- CLI orchestrates and does not become metadata or ABI authority;
- `.NET` roots are not represented as `JvmClasspathRoot`; and
- package/module placement follows the mature target owning the same concern
  unless a concrete CLR boundary requires otherwise.

See [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md).

## Products and profiles

Kotlin/.NET has one logical `DotNet` platform. Target framework is an
independent axis:

| Target | Applications | Libraries | May consume |
| --- | --- | --- | --- |
| `net48` | yes | yes | `net48`, `netstandard2.0` |
| `netstandard2.0` | no | yes | `netstandard2.0` |
| `net10.0` | yes | yes | `net10.0`, `netstandard2.0` |

`net48` and `net10.0` never consume one another. Select the profile before
lowering; it controls reference assemblies, legal CLR capabilities,
Runtime/Stdlib variants, assembly production, and compatibility. Common
semantics remain the same across profiles.

Runtime owns compiler/runtime identities; Stdlib owns ordinary Common
declarations and algorithms; the user assembly owns its declarations and
initialization. A target source file supplies only narrow `actual` declarations
and irreducible host operations. Do not move a Common algorithm into the
emitter or Runtime.

## Contribution workflow

For a bounded semantic or representation feature:

1. start from authoritative Common or shared compiler behavior;
2. inspect relevant JVM, JS, Wasm, and Native precedent;
3. isolate the exact CLR constraint requiring different treatment;
4. challenge the preferred design and identify Kotlin/interop consequences;
5. update the owning ADR/programme with any lasting choice;
6. implement the complete bounded producer/consumer slice;
7. test logical behavior, physical metadata, profiles, separate assemblies,
   foreign use, and hostile negatives in proportion to the boundary; and
8. commit and push the completed feature with its current-status update.

`dotnet` is the target's main integration branch. A feature may be developed
there directly or proven in an isolated worktree, but once it is coherent and
green under the applicable verification lane, commit it and push `dotnet`
before starting unrelated feature work. Do not let completed features
accumulate only on a private/probe branch, and do not present a red or partial
checkpoint as a completed feature.

Use focused checks for internal slices and one proportionate final gate for the
coherent feature. Do not create artificial microcommits merely to repeat a long
gate, and do not batch unrelated semantics merely to amortize it.

Do not broaden into an adjacent parked programme merely because it becomes
visible. Unsupported IR or metadata must fail specifically; never emit a
plausible zero/null/empty fallback or silently evict a declaration from a
published artifact.

Generated sources, configuration keys, test runners, and API baselines are
owned by their generators. Run the scoped generator and review its complete
output; do not hand-edit generated files.

Use worktrees when they improve throughput. A coherent, immutable checkpoint
may run its long gate in one worktree while another performs read-only analysis
or reversible follow-on work. Keep checkpoint/branch purpose explicit; do not
run overlapping Gradle or external-tool lanes against shared outputs; and do
not merge or push dependent work until the predecessor is green. If the
predecessor changes semantically, its old evidence is invalid and follow-on
work must be rebased or discarded explicitly.

Preserve unrelated user changes and existing stashes. Never repurpose a user
branch or mutate another worktree merely for convenience.

## Upstream synchronization

Before rebasing:

1. fetch and pin the exact upstream head;
2. account for the complete upstream and target ranges;
3. inspect every shared path and target-owned reverse dependency affected by
   upstream interfaces, bases, sealed hierarchies, constructors, or factories;
4. compute a virtual merge; and
5. create an exact rollback ref.

Rehearse the rebase in an isolated worktree. Resolve generated artifacts only
through their owner, keep mechanical integration separate from semantic
adaptation, range-diff every target patch, run focused shared-surface checks,
then run and audit the strict target gate. Record exact evidence in one dated
archive document; do not append the rebase diary to status or programmes.

Promote only from a clean tested checkpoint. Publish a distinct rollback ref
before a required non-fast-forward remote update and use an exact
`--force-with-lease`, never blind `--force`.

## Decision escalation

Exhaust Common/shared source, mature-target precedent, CLR facts, accepted
decisions, and safe executable probes before asking. Ask the user only when a
remaining choice is material and hard to reverse; continue independent
reversible work meanwhile. Record a durable unresolved question only when it
actually blocks the programme, and move the answer into the owning ADR once
decided. There is no arbitrary waiting period and no requirement to create an
open-question file for an issue that can be resolved from the repository.

## CIL production

Textual IL plus ILAsm is the accepted prototype path. The endpoint is the
structured compiler-owned CLI model with deterministic text and direct-PE
sinks. `backend.dotnet` owns Kotlin policy and lowering; `dotnet.ir` owns
physical CLI structures and serializers. Migrate complete production forms and
remove the superseded string path in the same slice. Do not add speculative CLI
nodes or treat CLI generic capability as permission to change Kotlin ABI.

Any new IL shape must be assembled and, when behavior is observable, executed.
Verify metadata tables, interface maps, reflection, and dispatch as applicable;
substring or golden-text checks alone are insufficient.

See the [CIL/PE decision](docs/decisions/cil-and-pe-production.md) and
[`docs/programmes/structured-cli-ir.md`](docs/programmes/structured-cli-ir.md).

## Verification

Choose tests from the changed boundary and observable risk, not the file count.
Slowness is never a reason to skip relevant verification, but it is also not a
reason to rerun unrelated target layers. Use these lanes:

1. **Focused lane.** During development, and as the commit gate for a bounded
   pure model, production-inert read-only shadow, target-specific frontend
   checker, diagnostic, or logical importer query that changes no selected IR,
   emitted signature/body, physical mapping, artifact, Runtime/Stdlib surface,
   shared test infrastructure, or production ABI, run the narrowest
   integration/unit test which exercises every affected parser, target profile,
   and positive/negative semantic branch. Compile/generator consistency for
   every changed module remains mandatory. A profile-independent shadow need
   not repeat the same observation on an unaffected runtime profile.
2. **Boundary lane.** For a bounded change inside one compiler layer, run that
   layer's complete relevant .NET suite plus any cross-layer integration test
   which consumes its output. Escalate to the full aggregate when the selected
   physical carrier, emitted metadata/CIL, executable behavior, or packaged
   artifact can change.
3. **Full target lane.** Run the aggregate below after changes which can affect
   production-selected type mapping, lowering output, codegen output, Runtime/
   Stdlib surfaces, generic or array representation, physical ABI, artifacts,
   profiles, toolchain integration, shared test infrastructure, or upstream
   integration, and before an ABI-readiness checkpoint. Moving a coherent
   checkpoint to `dotnet` does not by itself escalate its verification lane. A
   phase implemented as a lowering does not enter this lane solely because of
   its class name: a proven production-inert analysis which mutates no IR and
   has no routing/emission consumer uses the focused or boundary lane above.

The full target aggregate is:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

One already-green full checkpoint may be inherited by later focused- or
boundary-lane commits only when `STATUS.md` records both the checkpoint and the
exact delta evidence. It may not be inherited across a physical-boundary
change, and accumulated focused/boundary work must pass one fresh full aggregate
before it is declared a new target-wide or ABI-readiness checkpoint. A test
failure, missing required tool, or incomplete affected profile/parser matrix
always blocks the selected lane.

Do not trust Gradle exit alone. Audit JUnit XML under all four roots:

```text
compiler/ir/backend.dotnet/build/test-results/test/
dotnet/dotnet.ir/build/test-results/test/
compiler/fir/fir2ir/build/test-results/dotNetTest/
compiler/tests-integration/build/test-results/dn/
```

`STATUS.md` owns the current expected totals. Required-tool absence or Smart
App Control refusal is failure in the strict lane. PSI and LightTree must cover
the same target corpus; observable behavior must execute on Framework 4.8 and
.NET 10, and portable libraries must be consumed through compatible profiles.

`--rerun` on the empty aggregate task does not rerun its dependencies. Use the
global `--rerun-tasks` only when a deliberately fresh dependency-wide gate is
required. Before retrying a timed-out build, check whether its Gradle daemon or
test workers are still running; never start a second invocation against the
same outputs.

Generate FIR2IR runners with:

```text
.\gradlew.bat :compiler:fir:fir2ir:generateTests
```

When updating a golden from PowerShell, quote the complete
`-Pkotlin.test.update.test.data=true` native argument. Generated goldens still
require assembly/execution review.

A Markdown-only ownership/history/index change does not require the compiler
gate. It does require link validation, whitespace/diff review, and confirmation
that no semantic file is staged.

The pinned modern toolchain is provisioned by
[`tools/provision-dotnet-toolchain.ps1`](tools/provision-dotnet-toolchain.ps1)
under `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\`. Framework ILAsm/CLR4 is a
shared external resource and must not be exercised concurrently by independent
test processes.

## Documentation ownership

- `AGENTS.md`: bootstrap rules and repeatable workflow only.
- `STATUS.md`: current integration, latest gate, active work, and blockers.
- `docs/programmes/way-forward.md`: future ordering and release gates.
- Active programme files: one current workstream's scope and exit conditions.
- ADRs: durable decisions, invariants, consequences, and rejected alternatives.
- Git: chronological implementation history.
- Tests/CI: executable evidence.
- `docs/archive`: immutable dated evidence and superseded designs.

Do not put commit diaries or current test counts in ADRs/programmes. Do not copy
feature-specific representation detail into this bootstrap file. A semantic or
ABI change updates its owning ADR in the same feature commit; a completed
feature updates `STATUS.md` and is committed and pushed before unrelated work
continues.
