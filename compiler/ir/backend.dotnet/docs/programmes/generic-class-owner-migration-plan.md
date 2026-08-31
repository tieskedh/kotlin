# Generic-owner atomic migration and rollback plan

- Status: **Production migration not authorized**
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Shared candidate model:
  [`../decisions/draft-adr-generic-owner-physical-authority.md`](../decisions/draft-adr-generic-owner-physical-authority.md)
- Class candidate:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Interface candidate:
  [`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
- Current production baselines:
  [generic classes](../decisions/generic-class-erased-identity.md) and
  [generic interfaces](../decisions/generic-interface-erased-identity.md)

This plan owns only the migration unit, entry conditions, cutover procedure,
and exact inverse. [`../../STATUS.md`](../../STATUS.md) owns the current
checkpoint. Git and the [archive](../archive/README.md) own proof chronology and
old schema narratives.

## Timing

The target deliberately separates two moments.

1. **Now, before ABI selection:** design and hostile-test truthful natural
   generic owners in a production-inert rehearsal. Consolidate declaration
   authority, value provenance, callable layouts, state selection, semantic
   routing, foreign implementation, reflection, and rollback.
2. **After ordinary product breadth exists:** decide whether evidence from real
   Kotlin/C# applications, tooling, deployment, size, startup, allocation, and
   throughput justifies one production cutover.

Postponing all architecture would let new code depend accidentally on erasure.
Publishing a generic ABI now would freeze costs before representative apps can
measure them. The rehearsal therefore advances now while production remains
erased.

## Atomic migration unit

The unit is not one easy declaration. It includes, for the selected complete
Runtime/Stdlib family:

- Kotlin-owned generic class and interface TypeDefs;
- fields, methods, properties, constraints, inheritance, and MethodImpls;
- semantic capabilities and special bridges still required by the final model;
- embedded KLIB/manifest schema and separate-compilation binding;
- importer, reflection normalization, casts, and callable reflection;
- C# authoring and direct/export surfaces;
- Runtime and source-built Stdlib artifacts;
- Framework 4.8 and .NET 10 deployment products; and
- the production-erased inverse.

No module may publish a selected logical owner as erased while another module
publishes the same epoch as CLR-generic. No per-interface pilot, consumer-side
guess, or mixed schema is permitted.

## Rehearsal sequence

Before any cutover proposal, complete these stages in order:

1. Freeze logical versus physical vocabulary and declaration authority epochs.
2. Run value-carrier provenance in shadow mode; do not let it select public ABI
   or producer-wide state.
3. Close the bounded retained/imported same-TypeDef physical-conversion
   prerequisite: preserve legal reference-only variance and reject recursively
   proven closed value-type variance at source and final emission boundaries.
4. Prove one complete natural input-bearing interface with ordinary non-partial
   CLR implementations and no exact sibling or hidden author obligation.
5. Replace concrete-name structural fallback with actual MethodDef/MethodImpl
   routing:
   - first prove that a final same-producer MethodDef can be carried by an exact
     open-declaration token and rebound to the selected closed construction;
   - then publish the complete physical MethodDef and orthogonal result-layout
     descriptor required for a separate consumer to emit the same token without
     rebuilding it from logical IR.
   Stage 5 is complete only after validated cross-assembly descriptor
   consumption and the hostile same-name/same-regular-arity interface-MethodDef
   proof, with missing, contradictory, ambiguous, or stale producer authority
   failing closed.
6. **Bounded proof complete.** Prove one natural generic class whose complete
   writer set permits typed state, and a separate hostile owner whose semantic
   writes force broad state. The Stage 6 grammar admits exactly one private
   mutable direct-owner-parameter field with plain memory semantics after one
   monotone detached-family/private-helper/state/output fixpoint. Final per-
   field requirements decide admission; owner disposition remains diagnostic.
   BOUND freezes every existing instance-field identity, each explicit writer's
   site/producer/origin/value-type lineage and exact multiplicity, and one exact
   `POSITIONAL_CONSTRUCTOR_PARAMETER` initializer. Typed direct stores require
   the exact non-dispatch writer parameter with the field's direct `T` type;
   init-block, other-field, computed, and other nontrivial initializers remain
   unavailable without becoming hard user errors. The complete live module
   re-proves that writer grammar after bridge/body production and before BOUND;
   unsupported live stores remain unavailable, whereas post-BOUND changes are
   internal conflicts. Final observations validate the complete field set
   before dependency/IL/PE publication, seal the selected FieldDef separately,
   and publish its snapshot only after ILAsm success. The seal checks TypeDef
   shape, carrier/index, scope uniqueness, and emitted field name, and
   memberless inherited constructions add no shadow state. Broader state
   remains a later generalization, not an implicit admission.
7. **Bounded proof complete.** Compose one strict invariant owner input with a
   distinct covariant split-nullable owner result on a custom structural
   declaration. Semantic role, parameter domains, and result layout are
   independently producer-recorded and consumed; exact value calls stay
   unboxed, ordinary C# implementations need no generated ABI, the erased
   inverse remains exact, and existing Runtime `Map` stays unchanged. Multiple
   members/inputs, value classes, richer inheritance, and Runtime/Stdlib
   application remain later generalization rather than implicit admission.
8. Close the remaining retained/foreign entry validation, including inherited,
   open, projected, vararg, and multiple-view generic cases plus SZ-array and
   bounded-element entry guards.
9. Freeze and migrate the complete selected rehearsal family, deleting old
   recognizers only after the shared model derives their positive and hostile-
   negative behavior.
10. Run the full selected Runtime/Stdlib, Kotlin/C# assembly, reflection,
   deployment, and representative application matrix.
11. Run the exact erased inverse from the same source and compare the recorded
    semantic corpus before deciding GO, CONSTRAIN, or NO-GO.

After stage 5, a bounded interface slice may replace its split comparison
surface inside the rehearsal only after every downstream owner form admitted
for that slice preserves the selected root authority. That closure includes
the relevant interface and class fake overrides, declared overrides, and
MethodImpl obligations; it may not be inferred from a root-only descriptor
proof. Such a bounded replacement is not stage 9's complete selected-family
freeze and does not authorize a production cutover.

The source-built Stdlib census must not drive one-off representation rules.
When it exposes a blocker, reduce that blocker to a structurally custom proof
before changing the general model.

The Stage 6 evidence is recorded in the
[producer-wide state FieldDef archive](../archive/generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md).
It does not serialize per-value lineage or authorize production migration.
It also does not close generic-child-capability to separately owned base-
capability conversion; that remains an interface-routing prerequisite.

The Stage 7 evidence is recorded in the
[callable-contract composition archive](../archive/generic-owner-callable-contract-composition-2026-08-31.md).
It does not admit multi-member Runtime/Stdlib families or authorize production
migration.

## Rules during ordinary feature work

- Keep production artifacts on the accepted erased epoch.
- Commit each completed proof or architectural feature independently after its
  proportionate gate; do not accumulate unrelated work behind a long rehearsal.
- A bounded recognizer may remain temporarily, but its scope and hostile
  negatives must be explicit. It is not a durable representation rule.
- New schema fields record producer-selected physical truth; consumers never
  reconstruct owners, members, or routes from names.
- A later Kotlin specialization may bridge to an emitted MethodDef but may not
  rewrite its physical signature.
- One object has one identity and one authoritative state. No migration step may
  introduce a wrapper, proxy, twin owner, or shadow field graph.
- Do not optimize a semantic route until correctness and route attribution show
  that the route is genuinely necessary.
- Rebase/synchronize upstream only at a clean committed proof boundary. Record a
  rollback ref, range audit, semantic gate, and generated-file delta before
  continuing architecture work.

## Entry conditions for a production decision

### Semantics

- The selected Common and language corpus is equal between candidate and erased
  inverse, except explicitly accepted breaking entries such as BK-1.
- Stars, projections, variance, broad candidates, defaults, overrides, `super`,
  exceptions, equality/identity, and reflection are covered.
- `as`, `as?`, and admitted parameterized `is` use the one recorded BK-1
  predicate; ordinary valid Kotlin variance still succeeds.

### Physical truth

- Final emitted/retained TypeDefs, MethodDefs, fields, InterfaceImpls, and
  MethodImpls are sealed authority after all representation-affecting lowerings.
- Missing or contradictory final evidence fails closed. Earlier expected data
  cannot fill it.
- No fabricated constructed generic type is emitted or claimed.
- State layout is producer-wide; value provenance never specializes it from a
  local observation.
- Split-nullable payloads bind producer-recorded physical expressions, including
  value-class substitutions.

### Separate compilation and interop

- Producer and consumer bind by artifact plus exact logical and physical
  identity; stale, partial, duplicate, mixed-epoch, and hostile-name artifacts
  reject deterministically.
- Existing CLR declarations remain native and require no Kotlin metadata or
  generator.
- Ordinary C#, F#, VB, and valid IL implement one complete natural interface
  without hidden compiler ABI.
- Kotlin calls through widened views observe ordinary CLR overrides whenever the
  behavior is mechanically derivable from real slots and recorded Common policy.
- Non-derivable behavior is explicitly adapted, diagnosed, or excluded from
  admission rather than discovered through runtime name/arity convention.

### Compiler and product breadth

- Both FIR parsers, FIR2IR, all relevant lowerings, inlining, KLIB serialization,
  defaults, reflection, and diagnostics use the same authority queries.
- Multiple parameters, bounds, nullability, value classes, properties,
  constructors, generated/anonymous classes, captures, defaults, diamonds,
  reabstraction, and deep mixed-language inheritance are covered.
- The complete selected Runtime and source-built Stdlib graph builds from the
  canonical source manifest without an owner-specific exception list.

### Deployment and tooling

- Framework 4.8 and .NET 10 JIT execute the full matrix.
- ReadyToRun, trimming, and NativeAOT require no runtime code generation or
  unbounded metadata preservation.
- Raw metadata, IL verification, debugger/Rider/Visual Studio presentation,
  IntelliSense filtering, KClass/KType, ABI diffing, and package tooling show a
  coherent one-declaration model.
- Code size, metadata size, compile time, startup, allocation, and representative
  route performance are measured against the erased inverse. No single hostile
  microbenchmark selects the ABI.

## Schema and artifact boundary

The candidate and erased epochs have distinct manifest/schema identifiers.
Every record is self-describing and validates the producer fingerprint, target
profile, complete owner/member family, physical signatures, and required route
set before yielding one binding.

A consumer either accepts the entire expected epoch or rejects it. It never
combines old exact-sibling roles with a new complete natural interface, reads a
missing field from logical KLIB shape, or uses a current-compilation guess to
repair stale external metadata.

Before cutover, retain a tested schema-aware erased encoder/decoder and artifact
builder. “Git can revert it” is not the exact inverse gate.

## Cutover procedure

After every entry condition passes:

1. record immutable base, candidate, and rollback refs plus the exact selected
   declaration family;
2. build and archive the erased control products;
3. switch the complete family and schema in one commit series with no unrelated
   changes;
4. regenerate Runtime/Stdlib and compiler-owned manifests from canonical inputs;
5. run semantic, metadata, interop, deployment, tooling, and performance gates;
6. run the erased inverse from the candidate source and compare its outputs;
7. audit the full diff, generated files, and every external artifact; and
8. only then promote/push the integration branch with a force-with-lease or
   equivalent guarded ref update when a rebase changed history.

Any failed gate aborts the cutover. It does not authorize a partial production
surface.

## Exact rollback

Rollback must remove, from the same logical source state:

- natural generic implementation TypeDefs selected by the candidate epoch;
- candidate-only capabilities, bridges, helper MethodDefs, MethodImpls, and
  metadata roles;
- generic fields and callable layouts introduced by the candidate;
- candidate importer/reflection/export assumptions; and
- candidate Runtime/Stdlib artifacts.

It must restore the accepted erased owner family, manifest epoch, runtime
helpers, reflection normalization, C# surface, and test products without
wrappers, compatibility aliases, or mixed identities. The strict target gate
and representative application corpus must pass on that inverse.

Rollback is required at every checkpoint proposed for promotion, not only at
the final release candidate.

## Outcomes

- **GO:** one truthful natural architecture covers the selected complete family,
  interop, deployment, tooling, measurements, and exact inverse.
- **CONSTRAIN:** a mechanically defined whole-declaration grammar passes; all
  other owners stay erased and require explicit export/adaptation.
- **NO-GO:** keep erased Kotlin owners and use explicit host-facing typed
  export/adapters.

None permits repeated production switching. The rehearsal learns while the ABI
is unfrozen; production changes at most once for the selected epoch.
