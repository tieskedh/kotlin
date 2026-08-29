# Kotlin/.NET development status

This file is the current integration snapshot. Read [`AGENTS.md`](AGENTS.md)
before changing the target. Future ordering belongs in the
[way forward](docs/programmes/way-forward.md), durable representation rules in
ADRs, and dated evidence in [`docs/archive`](docs/archive/README.md).

## Integration state

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `c72fbd7b4e4ee01698c08204796ddfc43383d642`.
- Current checkpoint: physical library ABI 64, generic-owner artifact schema
  21, compiler/runtime surface 60.
- The Stage 6 checkpoint closes the bounded producer-wide state proof without
  changing a published library-index record: one admitted generic class seals
  a private mutable `!T` FieldDef when its complete writer graph is typed, while
  a hostile covariant owner seals one `object` FieldDef when a legal semantic
  write requires it. Both retain one object and one authoritative state.
- Git owns the exact promoted checkpoint identity.
- Reviewed upstream synchronization:
  [`docs/archive/upstream-sync-2026-08-27.md`](docs/archive/upstream-sync-2026-08-27.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest verification

The latest fresh unqualified production-erased target aggregate passed on
2026-08-29 at the ABI-64 checkpoint:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 204 suites and 2,583 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 14 | 198 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,251 |
| integration | 2 | 128 |

The Stage 6 state-authority slice is rehearsal-only and keeps ABI 64. Its
focused model, candidate, production-inverse, metadata, reflection, separate-
compilation, and ordinary C# evidence is owned by the
[Stage 6 archive](docs/archive/generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md).

## Production binding state

- Kotlin Common declarations and Kotlin IR/KLIB remain logical authority.
  Emitted or retained CLR metadata remains physical authority.
- Kotlin-produced libraries remain self-describing DLLs containing their KLIB
  and physical binding records.
- Production Kotlin-owned generic classes and interfaces still use the
  accepted erased ABI. All CLR-generic owner work remains rehearsal-only until
  one complete family can switch atomically with its exact inverse and
  rollback.
- The candidate keeps one receiver identity and one authoritative state.
  Proven natural CLR-generic routes are preferred; semantic capabilities are
  used only for Kotlin views the CLR cannot truthfully name. No wrapper, proxy,
  shadow state, or fabricated construction may repair a representation gap.
- BK-1 remains the only accepted target-specific cast change; its scope is
  owned by the
  [semantic-authority decision](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and [breaking-change ledger](docs/decisions/breaking-kotlin-changes.md).

## Active work

The source-built Stdlib census remains paused while generic-owner physical
authority and value provenance are consolidated in rehearsal mode.

Stage 6 now computes detached-family inheritance, private-helper reachability,
state selection, and owner-dependent output pairing as one monotone fixpoint.
Admission consumes the final per-field requirements; the priority-compressed
owner disposition is diagnostic and cannot hide an unresolved field.

BOUND freezes the complete pre-existing instance-field identity set plus the
selected field's exact owner, flags, and symbolic carrier. It also freezes each
explicit writer's unique site/producer/origin/value-type lineage and the one
exact positional constructor initializer. Final routing checks identities and
exact multiplicities, preventing a later `!T` or `object` shadow field or a
removed, duplicated, retargeted, or altered writer.

Immediately before BOUND, after bridge/body-producing passes, the complete live
module is re-scanned and every typed store must again satisfy the exact non-
dispatch field-`T` writer grammar. A new unsupported live store makes the family
unavailable; only changes after BOUND are internal authority conflicts.

Final observations are validated before dependency, IL, or PE publication. The
full observed instance-field set must match BOUND, while the selected owner-
dependent FieldDef is sealed separately with its physical name, TypeDef
category/arity, exact owner-parameter index, cross-scope uniqueness, and other
BOUND facts. Snapshots are published only after ILAsm success, and the PE
harness correlates the sealed name with objective metadata.

The bounded grammar is deliberately small: exactly one private mutable instance
field whose logical type is one direct owner parameter, with plain memory
semantics and either producer-graph-proven typed storage or required
semantic-object storage. The only admitted initializer is an exact positional
constructor-parameter copy; typed direct stores must consume the writer's exact
non-dispatch field-`T` parameter. Explicit init-block, other-field, computed, or
otherwise nontrivial initializers remain unavailable rather than producing a
hard user error. Nested, projected, nullable, volatile, incomplete-writer, and
typed-write-provenance shapes remain
unavailable. Exact scope, owner, flags, carrier, binder, final emitted name and
PE correlation, inherited construction, absence of shadow state, Kotlin
behavior, identity, and ordinary C# use are covered in the
[Stage 6 archive](docs/archive/generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md).

The next migration stage composes owner-dependent callable inputs with direct
and split-nullable results on a custom declaration. The shared model and
remaining boundary are owned by the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md)
and [way forward](docs/programmes/way-forward.md).

## Current blockers

- Declaration and implementation authority is not yet closed for deeper base
  chains, multiple or distinct constructed views, constraints, method
  generics, explicit MethodImpls, or general callable forms.
- Retained foreign CLR declaration authority remains a separate incomplete
  boundary.
- Producer-wide state remains incomplete beyond the bounded direct-owner-
  parameter/plain-field grammar, including nested carriers, multiple owner-
  dependent fields,
  nullable/value-class storage, volatile state, mixed captures, open writer
  graphs, and external state authority. Shared per-value provenance also
  remains incomplete.
- Conversion from a generic child semantic-capability carrier to a differently
  owned base capability remains a separate interface-routing gap; Stage 6 does
  not claim to solve it.
- Remaining retained-foreign projected conversions and SZ-array entry guards
  are not yet proven.
- Complete Runtime/Stdlib coverage, Framework/CoreCLR deployment breadth,
  ReadyToRun, trimming, NativeAOT, tooling, and rollback still block a
  production cutover.
- Wider target and release gaps are listed only in the
  [way forward](docs/programmes/way-forward.md).

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

Update this file only when the integration base, latest verified checkpoint,
active work, or current blockers change. Git owns chronology, ADRs own lasting
decisions, programmes own future ordering, and dated archives own detailed
evidence.
