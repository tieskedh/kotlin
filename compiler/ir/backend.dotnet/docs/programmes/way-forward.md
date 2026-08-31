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
slots.

The bounded declaration/MethodDef stages and the first producer-wide state
stage are closed. Stage 6 reaches one monotone fixpoint across detached-family
inheritance, private-helper reachability, state, and output pairing, then admits
from final per-field requirements rather than the diagnostic owner disposition.
BOUND freezes the complete existing instance-field set, exact explicit-writer
lineages and multiplicities, and the positional initializer contract.
Immediately beforehand, after bridge/body production, the complete live module
re-proves every typed store; an unsupported live store makes the family
unavailable, while any post-BOUND change is an internal conflict. Final
observations are validated before dependency/IL/PE publication: the full field
set matches BOUND, the owner-dependent FieldDef seals separately, and snapshots
publish only after ILAsm success. The seal checks TypeDef category/arity,
carrier and exact parameter index, cross-scope uniqueness, and the newly
observed physical field name. This
distinguishes a producer-proven private mutable `!T` slot from a hostile owner
whose widened writes require one private mutable `object` slot, without shadow
state or changed identity. Details and remaining grammar are
[archived](../archive/generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md).

The first grammar accepts only an exact positional constructor-parameter
initializer. Typed direct stores consume the exact non-dispatch writer
parameter with the field's direct `T` type. Init-block, other-field, computed,
and other nontrivial initializers remain unavailable and fall back out of this
proof; they are not hard user errors.

Stage 7 now composes one strict owner-dependent callable input with a distinct
split-nullable owner result on a custom structural declaration. Semantic role,
parameter domains, and result layout remain independent; the existing Runtime
`Map` family is deliberately unchanged. The bounded proof and its exact erased
inverse are recorded in the
[Stage 7 archive](../archive/generic-owner-callable-contract-composition-2026-08-31.md).
The first retained-foreign declaration adapter now authenticates one bounded
open root-interface MethodDef directly from its selected raw metadata; wider
hierarchies, MethodImpls, nominal carriers, and constrained constructions remain
outside that grammar. One production-inert imported route now derives its
receiver construction solely from its direct verifier carrier, shared guaranteed
views, their recorded physical-interface closure, and selected lineage, then
produces its exact instantiated MethodDef result through the same value model.
The first inherited grammar authenticates one child interface with an exact
TypeDef carrier, zero or one unconstrained parameter with exact CLR variance,
and one same-assembly `InterfaceImpl`. The carrier is independent of declared
members; the hostile child has no marker MethodDef. The edge may close the root
MethodDef owner or forward the child binder through the admitted carrier grammar.
Recorded physical substitution, rather than a logical Kotlin type, derives each
concrete parent view. A narrow target hook now transports that class-level
carrier through lazy external FIR2IR as compilation-local class metadata;
Common neither interprets nor serializes it, and callable authority remains
separate. The next ordered retained boundary is admitting and exercising a
genuinely memberless imported interface through the actual compiler pipeline,
without a global registry, fake member, or name-based lookup. MethodImpls,
multiple binders/edges/members, variance conversions, constraints, classes,
cross-assembly inheritance, and Runtime/Stdlib application remain later.
Broader state shapes and multi-member or Runtime/Stdlib callable application
remain later extensions of the same model. A bounded slice may stop emitting a
comparison surface only after downstream owner closure is an epoch invariant;
complete removal still waits for the selected-family freeze and hostile
inverse. Generic-child-capability to separately owned base-capability conversion
remains an independent interface-routing proof, not part of Stages 6 or 7.

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

Retained-foreign physical-conversion coverage must derive inherited,
implementation, open, projected, vararg, and multiple-view cases from the
shared provenance model. Legal reference-only CLR covariance/contravariance
must remain available; a missing reference-hierarchy proof is not evidence of
an invalid CLR conversion.

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
