# Kotlin/.NET execution programme

- Status: **Living pre-ABI route map**
- Current branch, active task, and verification: [`../../STATUS.md`](../../STATUS.md)
- Normative decisions and programme index: [`../README.md`](../README.md)

This file owns future ordering and release gates. It does not own physical ABI
detail, implementation history, commit hashes, or current test counts.

## Product direction

Work is selected in this order:

1. preserve Kotlin language and Common stdlib semantics;
2. reuse shared compiler lowerings, declarations, generators, and tests;
3. make the resulting surface and execution model native to the CLR and normal
   for C# where the complete Kotlin contract permits it; and
4. retain real CLR generics, typed state, and typed calls wherever physical
   declaration authority and whole-program/open-world constraints prove them.

The later priorities never weaken an earlier one. A CLR-native shortcut is not
acceptable when it changes Kotlin identity or behavior, and a semantic fallback
is not acceptable merely because proving a truthful CLR carrier is harder.
Target-specific representations require a concrete CLR constraint and an owning
ADR. This is the target authors' working direction, not a claim of Kotlin
core-team endorsement.

Nothing has shipped. Before ABI freeze, a wrong prototype contract is replaced
atomically across compiler, Runtime, Stdlib, metadata, tooling, and tests; it is
not preserved through compatibility aliases. See the
[documentation authority index](../README.md) and the
[target/profile decision](../decisions/dotnet-platform-and-target-frameworks.md).

## Primary critical path

### 1. Complete the generic-owner rehearsal census

The schema-22 physical-authority consolidation is the current foundation.
Production remains on erased owners while the rehearsal prefers natural CLR-
generic declarations and uses semantic capabilities only where the CLR cannot
truthfully name the Kotlin view.

Resume the source-built Runtime/Stdlib census now, inside this phase. Each real
failure may add only a structural rule derived from:

- Kotlin IR/KLIB logical authority;
- producer-selected, retained-foreign, BOUND, or sealed-emission declaration
  authority;
- per-value produced-carrier and selected-view provenance;
- destination storage/entry facts;
- independent parameter-domain and result-layout policies; or
- producer-wide open-world state authority.

Do not add declaration, package, collection, `Map`, member-name, IR-origin, or
stdlib exceptions. A broad semantic input may not contaminate unrelated exact
receiver-derived state, and exact provenance may not narrow a genuinely broad
source value. Selected view lineage can choose among already guaranteed views;
it can never prove that a view exists.

The current candidate must continue to preserve:

- one receiver identity and one authoritative state;
- no wrapper, proxy, or shadow state;
- no fabricated CLR construction;
- existing emitted and retained foreign MethodDef/MethodImpl/FieldDef authority;
- ordinary C# implementation and override behavior without a hidden compiler-
  ABI obligation;
- exact separate-compilation consumption of producer records; and
- a production-erased inverse and rollback.

Exit this phase only when the shared model explains the complete selected
Runtime/Stdlib family and its hostile negatives, including value/reference/
nullable/value-class substitutions, stars and projections, mutable state,
defaults, properties, diamonds, deep Kotlin/C# inheritance, multiple owner and
method parameters, and separate producers and consumers.

The architecture is owned by the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).
The admitted generic-interface shape and migration conditions are owned by the
[generic-interface ADR](../decisions/draft-adr-reified-generic-interface-owner.md),
[generic-owner programme](generic-class-owner-reopening.md),
[carrier matrix](generic-class-owner-carrier-matrix.md), and
[migration plan](generic-class-owner-migration-plan.md).

### 2. Make the generic-owner go/no-go decision

After the rehearsal census closes, run the complete candidate over:

- Runtime and source-built Stdlib families;
- Kotlin and C# producers, implementations, subclasses, and consumers;
- Framework 4.8, .NET 10, ReadyToRun, trimming, and NativeAOT;
- representative applications, reflection/tooling, concurrency and memory-
  model cases, and performance/code-size measurements; and
- the exact production-erased inverse and rollback.

Choose **GO**, **CONSTRAIN**, or **NO-GO**. This decision does not itself switch
production. A constrained result keeps each unsupported whole declaration
erased. A no-go retains the erased Kotlin ABI and uses explicit C# export or
adapters where the CLR cannot carry the complete contract.

### 3. Close and migrate the production family after GO

Only after GO, satisfy every entry condition in the migration plan, complete
the chosen Runtime/Stdlib production dependency closure, and amend the accepted
erased-owner decisions. Switch compiler, Runtime, Stdlib, metadata, importer,
tooling, tests, and rollback atomically. There is no per-interface pilot and no
mixed erased/generic production epoch.

Kotlin collection identity remains distinct from optional BCL adapters. The
selected dependency closure and exclusions are owned by
[`common-collections.md`](common-collections.md).

## Independent workstreams

These may advance in bounded slices when they do not alter the generic-owner
critical path or share unsafe build outputs:

- [`structured-cli-ir.md`](structured-cli-ir.md): migrate complete physical
  ECMA-335 forms from text construction into the policy-free CLI model;
- [`compiler-architecture.md`](compiler-architecture.md): preserve mature-
  target ownership and extract only for a concrete independent consumer;
- [`clr-annotations.md`](clr-annotations.md): extend imported/exported CLR facts
  through exact mappings and Kotlin stability rules; and
- explicit C# export and build/distribution work that does not redefine Kotlin
  runtime identity.

Upstream integration and concrete correctness repairs take precedence when they
would invalidate the checkpoint. Performance or cleanup work is selected only
for a measured material hotspot, a real bug, or demonstrable duplication.

## Cross-cutting entry and exit rules

Every semantic or representation slice must:

1. identify the authoritative Common declaration or compiler rule;
2. inspect relevant JVM, JS, Wasm, and Native precedent;
3. isolate the exact CLR constraint requiring different treatment;
4. update the owning ADR/programme before or with an ABI-bearing change;
5. implement one complete producer/consumer slice and fail closed outside it;
6. prove logical behavior, physical metadata, separate compilation, C# use, and
   every observing target profile; and
7. retain an exact inverse when the work is rehearsal-only.

Unsupported input must fail with a located diagnostic or explicit rejection;
it must not silently shrink a published library. Golden CIL text is physical
evidence, not sufficient semantic evidence.

The strict aggregate remains one supported entry point. Internal groups may be
partitioned only when disjoint, exhaustive, visible to the repository lifecycle,
and safe around Framework ILAsm/CLR4 resources. Current commands and counts
belong only in [`../../STATUS.md`](../../STATUS.md).

## Parked families

Parking means “fail clearly without constraining the future ABI,” not
“approximate now.” Outside the current critical path are:

- multi-field value classes and unsigned Runtime/Stdlib publication;
- coroutine scheduling, `kotlinx.coroutines`, debugger integration, and
  `Task`/`ValueTask` export;
- wider concurrency, volatility, synchronization, and atomic APIs;
- broad member/constructor reflection and unselected CLR annotation shapes;
- unsupported foreign generic constraints and nullable generic leaves;
- broad KMP/distribution integration; and
- speculative specialization or devirtualization without semantic and
  measurement gates.

An active programme or accepted ADR may refine this list. Adjacent work must not
assume a parked representation.

## Release gates

### Gate A — viable internal experimental backend

- publication fails on evicted or unbound declarations;
- logical keys, physical bindings, visibility, friends, and profiles are
  explicit and cross-module safe;
- `net48`, `netstandard2.0`, and `net10.0` products are deliberate; and
- the strict matrix is green with no unavailable required tool hidden as a skip.

### Gate B — third-party experimental binaries

- every required ABI draft is accepted or explicitly excluded;
- compiler, KLIB, physical ABI, Runtime/Stdlib, importer, and tooling version
  skew fails predictably;
- signing, assembly versioning, packaging, and distribution are specified;
- Kotlin ABI, compiler ABI, and C# export are mechanically distinguishable; and
- importer and foreign implementation paths have freeze-level tests.

### Gate C — official experimental target discussion

- module and package ownership follow reviewable mature-target directions;
- dedicated .NET FIR/session and KLIB platform integration are suitable for
  repository-wide use;
- the full profile/runtime matrix runs in CI;
- shared semantic and multi-module coverage approaches mature-target scale;
- diagnostics and CIL/metadata validation are structured; and
- every parked language area has an inclusion schedule or explicit exclusion.

## Maintenance

Keep only open ordering and gates here. Completed tranche narratives belong in
Git and dated archives; durable representation rules belong in ADRs; current
head and verification belong in `STATUS.md`.
