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
The retained-foreign adapter authenticates one selected open root-interface
MethodDef directly from retained and re-resolved raw metadata. This is a per-
MethodDef authority unit rather than a one-member declaration restriction: one
consumer now independently binds a no-argument method and two same-name, same-
arity overloads with different physical parameter signatures. Its receiver may
be the root of a resource-bounded acyclic graph of public top-level memberless
interfaces, each with a complete ordered vector of up to 1,024 binders and a
complete retained/raw edge set. Graphs may cross assemblies, branch, share
diamond nodes, and close, forward, or permute binders at every level. The
existing physical-view closure performs all substitution; selected lineage may
select an already-proven construction but cannot establish one. Cycles and
metadata disagreement conflict, while missing authority and resource limits
fail unavailable. Resource-free external pipelines cover direct,
cross-assembly, multi-edge, multi-view, intermediate, and recursive
four-assembly unconstrained forms. The recursive proof derives
`PairOuter<int,string> -> PairForwarding<string,int> -> Source<int>` without a
false `Source<string>` view. The overload proof emits each exact original parent
signature without names, arity heuristics, or interface row order as physical
authority.

The first constrained TypeDef boundary now admits bounded TypeSpec-backed
nominal rows in the retained inherited graph. The shared CLR validator proves
each exact closed or forwarded `InterfaceImpl` construction in its source
TypeDef's open binder context. That proof is keyed by source and exact unbound
target edge and survives substitution only along that edge; it cannot authorize
arbitrary constructions of the constrained target. A positive dependent
`TDerived : TBase` chain and a missing-implication conflict are executable
metadata-model evidence. The resource-free external FIR fixture has no selected
physical core catalog, so constrained end-to-end FIR remains a later gate rather
than acquiring a duplicate local validator.

An exact direct nominal constraint may now name a public, top-level,
non-generic CLR interface, ordinary reference class, or value type whose
retained hierarchy agrees with raw metadata. Its exact TypeDef becomes an
auxiliary carrier and may also close an inherited edge. This does not infer the
TypeDef by name, authorize a constrained generic construction, or claim a
complete edge set for the auxiliary TypeDef; missing selected hierarchy remains
`Unavailable`.

A TypeSpec may also recursively construct an exact public generic interface,
ordinary reference class, sealed CLR delegate, or value type whose selected/raw
TypeDef has a complete supported binder vector. The construction can contain
exact value arguments and can close or flow through an inherited edge.
Reference, non-nullable-value, and nullable-value carriers use the shared
physical classifier. An actual signature must agree with the selected TypeDef's
class/value marker. Only a bare TypeDef/TypeRef constraint row may infer that
marker from the selected definition because the metadata row has no signature-
side kind. Non-nullable values preserve `NON_NULL_ONLY`; a selected
`System.Nullable<T>` construction preserves `INLINE_NULLABLE_VALUE`. A
constrained construction is admitted at any nested depth only when the shared
nominal and special-constraint validators prove that exact subtree in the
source TypeDef's open context. Exact `class`, `struct`, `new()`, and
`allows ref struct` flags use this same grammar. Open binder rows remain target-
independent declaration authority, while a
construction needing special or possible by-ref-like validation requires an
explicit target. This proves by-ref-like-capable binder forwarding on .NET 10,
rejects it on Framework 4.8, and remains unavailable without a target. The
proof is scoped to source, exact edge root, and constrained subtree. An outer
proof cannot satisfy an inner construction by implication, and the general
construction helper remains closed. A variant non-interface binder is admitted
only when the shared classifier proves a sealed TypeDef whose immediate selected
base is the exact selected `System.MulticastDelegate`. Names and `Invoke`-
shaped members are not evidence; ordinary variant classes and non-sealed
delegates conflict, while missing core/hierarchy authority is unavailable.
Exact covariant and contravariant delegate constructions are retained. An
orthogonal TypeDef fact records the selected/raw proof that a variant `CLASS`
is a sealed CLR delegate; category remains `CLASS`. Interfaces and those
delegates then share one reference-only variance transfer. The transfer uses a
single frontend/backend argument-direction planner, physical reference
classification, and exact recorded ancestry. It preserves the source carrier
and object identity, adds the converted construction only to per-value
provenance, and never mutates the recorded-interface closure. Nested interface
variance and SZ-array reference covariance compose; differing value arguments,
unknown open binders, wrong direction, missing ancestry, or an unauthenticated
variant class fail closed. Declared delegate members are still outside retained
operation authority. Carrier traversal shares the physical ABI's depth and
node ceilings. Constrained constructions outside retained edges remain
unavailable.

Raw inherited-graph and auxiliary-nominal binder counts, plus their aggregate
constraint-row count, are reserved before generic-context resolution. Both
reuse the physical-artifact collection ceiling, so hostile metadata cannot
force an unbounded normalized constraint graph before the adapter returns
`Unavailable`.

The next ordered boundary is constraint-safe composition of this variance
transfer. The current general construction helper deliberately rejects a
TypeDef with nominal or special GenericParam constraints, so a converted target
cannot yet escape the exact retained-edge proof that admitted a constrained
construction. The next slice must reuse the shared nominal/special validators
under conversion-scoped authority; it may not make the general construction
helper permissive or treat an earlier edge proof as authority for a different
construction. Producer-recorded delegate authority and storage-placement
consumption remain separate follow-ons. Only after this bounded constraint
family should the graph grammar widen to properties, class nodes, MethodImpls,
or Runtime/Stdlib declarations.

Broader state shapes remain later extensions of the same model. A bounded slice
may stop emitting a comparison surface only after downstream owner closure is an
epoch invariant; complete removal still waits for the selected-family freeze
and hostile inverse. Generic-child-capability to separately owned base-
capability conversion remains an independent interface-routing proof, not part
of Stages 6 or 7.

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
