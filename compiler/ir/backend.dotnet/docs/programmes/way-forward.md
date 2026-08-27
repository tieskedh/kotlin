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

### 1. Consolidate generic-owner physical authority

The current priority is the production-inert generic-interface/class-owner
rehearsal. Production remains on the accepted erased owners while the candidate
proves that natural CLR-generic declarations can be the normal route and that
semantic capabilities remain only the necessary escape hatch.

The consolidation separates:

- Kotlin IR/KLIB logical authority;
- producer-selected, retained-foreign, and final-emission CLR declaration
  authority;
- per-value physical-carrier provenance; and
- producer-wide state/storage selection.

The interface review additionally replaces the desired exact-sibling ABI with
one complete natural CLR-generic TypeDef plus semantic routing. Physical
variance is retained only where the complete interface surface is CLR-legal;
otherwise the affected parameter is physically invariant while Kotlin's
logical variance remains in KLIB. Ordinary CLR-language implementations must
not require a hidden generated ABI for behavior derivable from real interface
slots. The existing split implementation remains comparison evidence until a
custom hostile proof and exact inverse pass.

It must preserve one receiver identity and one authoritative state, never
fabricate a CLR construction, and never allow a logically widened view to
create physical evidence. Broad semantic input may not contaminate unrelated
exact receiver-derived state. Exact provenance may not narrow a genuinely
broad source value.

The shared model is owned by the
[physical-authority/provenance draft](../decisions/draft-adr-generic-owner-physical-authority.md).
Interface shape, current comparison boundary, and remaining hostile grammar are
owned by the
[generic-interface reopening draft](../decisions/draft-adr-reified-generic-interface-owner.md),
the [generic-class-owner programme](generic-class-owner-reopening.md), its
[carrier/admission matrix](generic-class-owner-carrier-matrix.md), and its
[migration plan](generic-class-owner-migration-plan.md).

Exit this phase only when one shared authority/provenance model explains every
bounded recognizer and its hostile negatives without declaration, package,
collection, member-name, or IR-origin exceptions.

Before retained-foreign authority is extended, close the imported CLR variance
gap: legal reference-only CLR covariance/contravariance must remain available,
while value/open-argument conversions that Kotlin's broader logical variance
would accept must fail at a mandatory physical-conversion gate with a stable
source diagnostic.

### 2. Make the generic-owner go/no-go decision

After consolidation, run one complete rehearsal over:

- Runtime and source-built Stdlib interface families;
- value, reference, nullable, value-class, star, projection, and variance cases;
- mutable state, defaults, properties, diamonds, deep inheritance, and
  multiple owner/method parameters;
- separate Kotlin and C# producers, implementations, subclasses, and consumers;
- Framework 4.8, .NET 10, ReadyToRun, trimming, and NativeAOT; and
- the exact production-erased inverse and rollback.

This checkpoint chooses **GO**, **CONSTRAIN**, or **NO-GO** for the architecture;
it does not by itself authorize production migration. A GO still has to satisfy
every entry condition in the
[migration plan](generic-class-owner-migration-plan.md), including ordinary
product breadth, representative applications, reflection, the concurrency and
memory model, the public C# surface, and representative measurements. Only
then may the accepted erased-owner decisions be amended and the complete
selected production family switch atomically. There is no per-interface pilot
and no mixed erased/generic production epoch. A constrained result keeps each
unsupported whole declaration erased; a no-go retains the erased Kotlin ABI
and uses explicit C# export/adapters where the CLR cannot truthfully carry the
complete Kotlin contract.

### 3. Resume Common Runtime/Stdlib closure

Only after the go/no-go checkpoint resumes should the collection census select
another generic-owner-dependent family. Continue by complete Common/generated
dependency closure, never by handwritten target algorithms or declaration-name
exceptions. Kotlin collection identity remains distinct from optional BCL
adapters.

The current admitted surface, exclusions, and next dependency calculation are
owned by [`common-collections.md`](common-collections.md). Independent
non-generic foundations may proceed only when they do not pre-commit the
generic-owner decision.

## Independent workstreams

These may advance in bounded slices when they do not alter the current
generic-owner critical path or share unsafe build outputs:

- [`structured-cli-ir.md`](structured-cli-ir.md): migrate complete physical
  ECMA-335 forms from text construction into the policy-free CLI model, removing
  the superseded string path in the same slice;
- [`compiler-architecture.md`](compiler-architecture.md): preserve mature-target
  ownership and extract only when a concrete independent consumer exists;
- [`clr-annotations.md`](clr-annotations.md): extend imported/exported CLR facts
  only through exact mappings and Kotlin stability rules;
- explicit C# export and build/distribution work that does not redefine Kotlin
  runtime identity.

Completed foundations such as
[`inline-functions.md`](inline-functions.md) remain maintenance constraints for
their consumers; they are not independent active workstreams.

Upstream integration and concrete correctness repairs take precedence when they
would invalidate the active checkpoint. Performance or cleanup work is selected
only for a measured material hotspot, a real bug, or demonstrable duplication.

## Cross-cutting entry and exit rules

Every semantic or representation slice must:

1. identify the authoritative Common declaration or compiler rule;
2. inspect the relevant JVM, JS, Wasm, and Native precedent;
3. isolate the exact CLR constraint requiring different treatment;
4. update the owning ADR/programme before or with an ABI-bearing change;
5. implement one complete producer/consumer slice and fail closed outside it;
6. prove logical behavior, physical metadata, separate compilation, C# use, and
   every observing target profile; and
7. retain an exact inverse when the work is rehearsal-only.

Unsupported input must fail with a located diagnostic or explicit rejection;
it must not silently shrink a published library. Golden CIL text is physical
evidence, not sufficient semantic evidence.

The strict target aggregate remains one supported entry point. Its internal
groups may be partitioned only when they remain disjoint, exhaustive, visible
to repository test lifecycle, and safe around Framework ILAsm/CLR4 resources.
Current commands and counts belong only in [`../../STATUS.md`](../../STATUS.md).

## Parked families

Parking means “fail clearly without constraining the future ABI,” not
“approximate now.” The following remain outside the current critical path:

- multi-field value classes and unsigned Runtime/Stdlib publication;
- coroutine scheduling, `kotlinx.coroutines`, debugger integration, and
  `Task`/`ValueTask` export;
- the wider concurrency, volatility, synchronization, and atomic surface;
- broad member/constructor reflection and unselected CLR annotation shapes;
- unsupported foreign generic constraints and nullable generic leaves;
- broad KMP/distribution integration beyond the current target model; and
- speculative wrapper elimination, specialization, or devirtualization without
  a complete semantic and measurement gate.

An active programme or accepted ADR may refine this list. Adjacent work must not
assume a parked representation.

## Release gates

### Gate A — viable internal experimental backend

- publication fails on evicted or unbound declarations;
- logical keys, physical bindings, source visibility, friends, and profiles are
  explicit and cross-module safe;
- `net48`, `netstandard2.0`, and `net10.0` products are deliberate; and
- the strict target matrix is green with no unavailable required tool hidden as
  a skip.

### Gate B — third-party experimental binaries

- every ABI draft required by the supported surface is accepted or excluded;
- compiler, KLIB, physical ABI, Runtime/Stdlib, importer, and tooling version
  skew fails predictably;
- signing, assembly versioning, packaging, and distribution ownership are
  specified;
- Kotlin ABI, compiler ABI, and C# export are mechanically distinguishable; and
- supported importer and foreign implementation paths have freeze-level tests.

### Gate C — official experimental target discussion

- module and package ownership follow reviewable mature-target directions;
- dedicated .NET FIR/session and KLIB platform integration are suitable for
  repository-wide use;
- the full profile/runtime matrix runs in CI;
- shared semantic and multi-module coverage approaches mature-target scale;
- diagnostics and CIL/metadata validation are structured; and
- every parked language area has an inclusion schedule or explicit supported-
  surface exclusion.

## Maintenance

Keep only open ordering and gates here. Completed tranche narratives belong in
Git and dated archive evidence; durable representation rules belong in ADRs;
current head and verification belong in `STATUS.md`. When an active workstream
changes priority, update this route map and its owning programme together.
