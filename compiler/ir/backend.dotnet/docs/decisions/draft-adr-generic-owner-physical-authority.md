# Draft ADR: physical authority and value provenance for generic owners

- Status: **Draft — production-inert consolidation model**
- Scope: Kotlin-owned CLR-generic owner rehearsals, retained foreign CLR
  declarations, physical callable contracts, state selection, local value flow,
  semantic routing, and final emission evidence
- Does not select: a production generic-owner ABI, a particular natural
  interface surface, an exact-sibling interface, C# export policy, or a
  per-declaration migration

## Context

The generic-owner rehearsal must preserve two different kinds of truth.
Kotlin IR and KLIB describe the logical program: declaration identity, types,
variance, nullability, projections, stars, overrides, and operation semantics.
The CLR executes concrete TypeDefs, MethodDefs, fields, interface edges,
MethodImpls, generic binders, and calling conventions.

Several bounded proofs preserve an exact CLR carrier through immutable aliases,
helpers, result chains, generated captures, semantic bodies, and post-emission
override closure. These are not independent representation rules. They are
instances of one value-provenance question:

> Which already-existing physical carrier and runtime views are guaranteed for
> this value at this program point?

That question must remain separate from declaration selection and from the
producer-wide choice of authoritative object state. A local proof cannot create
a public ABI, specialize a field whose legal writers remain open, or reinterpret
an emitted MethodDef. Conversely, a logically widened view need not erase an
unrelated exact carrier which is still physically proven.

## Decision

The backend uses four cooperating models:

1. logical Kotlin authority;
2. staged physical declaration authority;
3. producer-wide state and storage planning; and
4. per-value physical provenance followed by late operation routing.

None is a universal representation oracle. Information flows from the first
three into value provenance and routing; local value facts never flow backward
to redefine declarations or state.

### 1. Kotlin remains logical authority

Kotlin IR and KLIB exclusively own:

- logical declaration and classifier identity;
- source and substituted Kotlin types;
- declaration- and use-site variance;
- nullability, stars, and projections;
- override, default, and `super` semantics;
- source-visible parameters and results; and
- operation-specific behavior, including broad candidate policies.

A CLR carrier or runtime construction is evidence about execution, not a new
Kotlin type. A later physical decision may not be read back as a logical type or
used to admit a source operation rejected by Kotlin.

### 2. Physical declaration authority advances through sealed epochs

Physical declarations have stable identities and epoch-specific descriptions.
The minimum authority epochs are:

```text
EARLY_REPRESENTATION_PLAN
        -> BOUND_DECLARATION_INDEX
        -> SEALED_EMISSION_SIGNATURE_INDEX
```

`EARLY_REPRESENTATION_PLAN` selects symbolic facts which must be decided before
lowering and emission, including:

- candidate physical owner families and generic binders;
- member and state relationships;
- callable parameter domains and result layouts; and
- bridge and MethodImpl obligations.

It contains no claim that a final row was emitted.

`BOUND_DECLARATION_INDEX` binds admitted symbolic identities to the selected
TypeDef, MethodDef, field, InterfaceImpl, and MethodImpl families and validates
their complete expected structural contracts. It remains expected declaration
authority, not final-emission evidence.

`SEALED_EMISSION_SIGNATURE_INDEX` is constructed afresh from final live emitter
facts after all representation-affecting lowerings. It certifies exact physical
owners, names, flags, generic arities and binders, parameter carriers, result
layout, fields, interface edges, and MethodImpl endpoints. It does not copy a
missing fact from the bound index or overlay expected data onto actual output.

A stable identity may correlate descriptions between epochs. A later epoch may
bind an earlier symbolic physical type expression, but may not reinterpret a
previously selected declaration family. Missing evidence is `Unavailable`.
Contradictory descriptions of the same physical declaration are `Conflict`.
Ordinary loss of precision between two values is not a declaration conflict.

The declaration-authority domain is explicit:

```text
PhysicalDeclarationFact
    Unavailable
    Planned(symbolic identity and physical type expressions)
    BoundProducer(producer record plus verified expected metadata shape)
    SealedEmitted(actual live metadata/emitter declaration)
    RetainedForeign(actual imported metadata declaration)
    Conflict(same identity, contradictory authoritative descriptions)
```

`Planned -> BoundProducer -> SealedEmitted` is the monotonic current-producer
epoch chain. `RetainedForeign` is a terminal peer of `SealedEmitted`: it comes
from selected foreign metadata rather than a Kotlin representation plan.
`Unavailable` never defaults to an expected record, and `Conflict` is a hard
error. A separately compiled Kotlin consumer sees the producer's sealed fact
through its verified producer record; it does not relabel that fact as a local
emission.

Physical carriers form a tagged vocabulary, not a Kotlin-subtype lattice:

```text
PhysicalCarrier
    ExactNatural(physical type expression, declaration authority)
    SemanticCapability(physical type expression, declaration authority)
    CanonicalErased(physical TypeDef, declaration authority)
    Object
    OtherExact(physical type expression, declaration authority)
```

`OtherExact` covers truthful non-owner carriers such as primitives, nominal
structs, arrays, and non-generic classes. `Object` is a real verifier-visible
carrier, not a synonym for unknown. Absence of a carrier fact is `Unavailable`;
incompatible authoritative descriptions are `Conflict`. Neither can be
silently converted into `Object`.

The corresponding callable endpoint distinguishes at least a current sealed
MethodDef, a separately compiled producer-recorded MethodDef verified against
its DLL, and a retained foreign MethodDef/MethodImpl. These endpoint kinds may
share the same structural signature but never exchange authority by name.

For a Kotlin-produced natural slot, declaration authority and implementation-
family evidence are independent. A declaration-level natural-slot seal records
the final natural TypeDef row, final MethodDef row, logical parameter domains,
and result layout as one self-sealing fact. It does not require an implementation
class, semantic hook, dispatcher, or MethodImpl. A wider implementation-family
seal is optional. When present, its complete natural-slot projection, including
TypeDef, MethodDef, logical domains, and result layout, must equal the
declaration seal exactly; it never creates or strengthens that seal.
The current physical index calls these records `N` and `J`, respectively. The
dependency is one-way: the presence of `J` requires a matching `N`; the presence
of `N` never requires `J`.

Value-flow precision is the product lattice described below: carrier placement
chooses a verifier-valid destination, guaranteed-view sets intersect at joins,
lineage agrees or disappears, and value layouts join independently. Two normal
incoming constructions therefore lose precision without producing declaration
`Conflict`.

An emitted MethodDef and its signature remain authoritative after emission.
Later Kotlin substitution may require a bridge to that slot; it may not rewrite
the inherited slot as though it had originally been emitted with the substituted
signature.

### 3. Callable contracts compose independent policies

Every admitted physical callable has one producer-owned contract containing at
least:

```text
CallableContract {
    physical MethodDef identity and generic binders
    receiver/virtual-slot and MethodImpl authority
    parameter domains[]
    result layout
    semantic policy
}
```

Parameter domains are independent per parameter. The initial vocabulary
distinguishes:

- `DECLARATION_INDEPENDENT`;
- `STRICT_OWNER_INPUT`;
- `BROAD_CANDIDATE_INPUT`; and
- `OWNER_EXACT_RECEIVER` for an explicit receiver where applicable.

The result layout is independently one of:

```text
Void
Direct(result slot)
SplitNullable(payload slot, final out bool isNull)
```

The result slot may be declaration-independent or a strict owner output. A
result layout does not imply a parameter policy, member role, state layout, or
semantic route. Combined names such as
`SPLIT_NULLABLE_WITH_OWNER_INPUT` are forbidden.

The natural interface shape is selected separately by the owning generic-
interface ADR. This model neither requires nor forbids an invariant exact
sibling. If such a view is selected, it is merely another authority-recorded
physical TypeDef and member family; it is not a permanent component of this
model.

### 4. State and storage are producer-wide decisions

Each object has one authoritative state. A field or equivalent state slot is
selected from the complete producer-visible obligation set:

- all constructors and initializers;
- all legal writes and semantic entries;
- override and inheritance families;
- public and protected callers;
- object escape and memory semantics; and
- separate-compilation/open-world obligations.

The selected field may be `!T`, another exact CLR carrier, a semantic capability,
or `object`. It is described as the authoritative selected store, not
unconditionally as a typed store.

Local observations cannot specialize that field. In particular, seeing only a
`Box<Producer<int>>` allocation in one body cannot make a mutable logical
`Box<Producer<Any?>>` field exact when another legal writer can supply an
incompatible construction.

After state selection, value provenance may optimize reads, writes, and calls
around that one slot. It may not introduce a proxy, wrapper, duplicate field,
shadow state, delayed synchronization, or representation-dependent object
identity.

#### Bounded Stage 6 FieldDef authority

Detached-family inheritance, private-helper reachability, state selection, and
owner-dependent output pairing form one monotone fixpoint. An inherited
semantic hook can expose a private helper writer, move its field to semantic
state, and require another output hook; no phase may consume the stale result of
an earlier pass. The closure only adds roles, reasons, and reachability or moves
unresolved state to required semantic-object storage.

Admission is then decided from the final requirement of every field. The
priority-compressed owner disposition is diagnostic after this closure; it may
report the dominant reason but cannot admit, reject, or conceal a resolved or
unresolved field requirement. Intrinsic owner blockers remain independent.

The first executable state grammar admits exactly one owner-dependent state
slot. It must be private, mutable, non-static, plain-memory, and logically one
direct owner parameter. That slot must already be classified as either:

- `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`, bound to that owner's exact generic
  parameter; or
- `SEMANTIC_OBJECT_REQUIRED`, bound to `object`.

For this first grammar, an admitted initializer may only be the exact
`POSITIONAL_CONSTRUCTOR_PARAMETER` recipe: the selected constructor parameter
is copied directly into the field with the recorded field-`T` value type. An
explicit init-block store, another-field source, computed expression, or other
nontrivial initializer makes the bounded family unavailable. For typed state,
every direct store must likewise take its value from the exact non-dispatch
parameter of its writer and that parameter must have the field's direct `T`
type. These are conservative admission failures, not hard diagnostics against
otherwise valid Kotlin; the rehearsal simply does not claim BOUND state
authority for the owner.

One unresolved owner-dependent slot makes the whole owner unavailable. An
owner-level priority/disposition summary cannot conceal
`COMPLETE_ACCESS_GRAPH_REQUIRED` or
`TYPED_WRITE_VALUE_PROVENANCE_REQUIRED`. Nested, projected, logically nullable,
volatile, and declaration-independent slots are outside this first grammar.

At `BOUND_DECLARATION_INDEX`, the family records the complete identity set of
all instance fields already declared on the owner. The admitted state slot also
records its exact IR field identity, declaring owner, privacy/static/init-only
flags, symbolic carrier, memory semantics, and whether the producer plan
contained an implicit field initializer. Later lowerings may neither add a new
`!T`/`object` shadow field nor remove any pre-existing instance field. Field
emission consumes the symbolic authority before consulting the logical type
mapper.

Immediately before BOUND, after every admitted bridge- and body-producing pass,
the backend re-scans the complete live module and re-proves the typed-writer
grammar for every live store to the selected field. A newly materialized store
which is not the exact non-dispatch field-`T` writer parameter makes the family
`Unavailable`; this is still conservative pre-BOUND admission, not an internal
error. Once the family is BOUND, any further field or writer-set change is a
contradiction of frozen authority and therefore an internal conflict.

Before any dependency artifact, IL result, or PE product is published, the
actual final observations are validated. The full observed instance-field
identity set on the owner must equal the complete BOUND set; the selected owner-
dependent FieldDef is then sealed separately. That seal requires one matching
FieldDef on the exact TypeDef, no duplicate owner/field in another emission
scope, and the BOUND TypeDef category and generic arity. It revalidates field
identity, owner-derived scope, flags, carrier, binder, and the exact owner
parameter index. The physical field name is first observed and sealed by this
final-emission epoch; it is not BOUND authority. The PE harness correlates that
sealed name with the objective FieldDef. Diagnostic state snapshots are
published only after ILAsm reports success; IL-only and failure paths expose no
snapshot or false seal.

The producer-visible write set is frozen at BOUND. Every existing `IrSetField`
site receives a unique copy-preserving lineage containing its exact target,
owning producer declaration, statement origin, and verifier-visible value type.
Final routing requires each lineage exactly once and rejects removal,
duplication, retargeting, producer/origin/type changes, or a newly introduced
write. The sole post-BOUND materialization contract is one pre-recorded exact
positional Common `INITIALIZE_FIELD` store. Its constructor, parameter index,
receiver, value symbol, value type, and occurrence count must match the
producer-recorded initializer, and the field initializer itself must have been
lowered away. This expected lowering is not a newly admitted writer.

This is a producer-local authority proof, not a serialized per-value witness or
a general external state record. A memberless downstream generic child may
inherit the exact base construction but owns no copied or shadow field. Broader
state forms require another whole-owner structural proof. Converting a generic
child's semantic-capability carrier to a separately owned base capability is an
independent interface-carrier problem and remains outside Stage 6.

### 5. Per-value provenance is a product fact

A physical value fact contains independent components:

```text
PhysicalValueFact {
    ProducedLayout
    destination StorageLayout, when already selected
    guaranteedPhysicalViews
    selectedViewLineage?
    diagnostic provenance
}

PhysicalValueLayout
    Unreachable
    Direct(carrier, null state)
    SplitNullable(payload carrier, bool null-flag carrier)
```

`ProducedLayout` is the complete verifier-visible layout supplied by a
definition or expression. `StorageLayout` is the independently fixed layout of
its destination. For an ordinary direct value their carrier components are the
earlier `ProducedCarrier` and `StorageCarrier` distinction; a split-nullable
call additionally carries its auxiliary null flag until materialization.

Only compiler-owned local/temporary placement may choose `StorageLayout` from
reaching-definition facts. Public and protected parameters/results are fixed by
the authority-selected `CallableContract`; fields and captured storage are
fixed by the producer-wide state plan. Reads are produced with those already-
fixed layouts. This prevents local provenance from specializing public ABI or
state and avoids a circular placement analysis.

`guaranteedPhysicalViews` contains only real runtime views proven for every
reaching non-null value. Evidence may come from:

- an exact constructed produced or storage carrier;
- a producer-recorded or retained CLR inheritance/interface edge;
- an emitted or retained MethodDef result contract;
- a successful checked physical conversion; or
- a verifier-valid CLR reference-variance conversion whose declaration
  variance and reference-shaped arguments are both established by physical
  authority.

Kotlin logical subtyping alone is never physical-view evidence. CLR variance
never applies to a value-type argument and boxing is not variance. The analysis
must not fabricate `I<object>` for an object which is only known to implement
`I<int>`.

`selectedViewLineage` records which already-guaranteed physical view was chosen
before a representation-preserving logical widening. It is only a selector:

> Lineage may restrict which proven view is intended; it may never prove that a
> view exists.

Direct layout tracks known-null, known-non-null, and maybe-null state where
relevant. Closed `Nullable<V>` and nominal value-class carriers remain direct
physical carriers. `SplitNullable` is a two-slot callable-boundary layout, not a
second state field. Diagnostic provenance explains where a fact came from, but
is not an additional lattice dimension and must not affect convergence.

### 6. Transfer and placement rules

#### Definitions, conversions, and calls

- Construction produces its authority-selected constructed carrier and closes
  guaranteed views only over recorded physical rules.
- An identity-preserving reference upcast may change the produced carrier or
  add a verifier-valid view without changing object identity.
- Boxing, unboxing, semantic adaptation, and nullable materialization are
  explicit transfers which produce new facts. A permissive storage check may
  not hide them.
- A logical implicit or unchecked cast preserves existing physical evidence but
  creates none. A successful checked physical barrier may add the exact view it
  checked.
- A call result is produced from the selected MethodDef's instantiated physical
  result layout. It is never remapped from a later logical return type.
- A parameter read is produced from the parameter's producer-recorded storage
  carrier and entry environment.

#### Immutable and mutable locals

- An immutable local with one representation-preserving reaching definition may
  retain that definition's exact carrier and views when placement admits them.
- A mutable local is governed by its actual reaching definitions, not by `isVar`
  alone. Sequential overwrite kills the previous fact; alternative writes join.
- Source locals and compiler temporaries obey the same rules. IR origin, an
  inliner marker, a generated name, or package membership supplies no evidence.
- Inlining substitutes facts through actual definitions and binder-safe physical
  substitutions. It does not grant exactness merely because the inliner created
  a temporary.

#### Joins

- Logical Kotlin type joining and verifier-valid carrier placement are separate.
- Guaranteed non-null view sets intersect after physical closure.
- Lineage survives only when every non-bottom incoming path selects the same
  guaranteed physical view. Different or absent selections remove it.
- Different exact constructions normally lose precision and select a truthful
  common carrier, common guaranteed capability, or `object`; they do not create
  a constructed generic join.
- `null` is a value layout, not an `object` producer. Joining known null with an
  exact reference preserves the non-null arm's guaranteed views while making
  the value nullable.
- An unknown maybe-non-null arm removes guarantees it does not share.
- Disagreement between value facts is normal dataflow. `Conflict` is reserved
  for contradictory authority about one declaration.

#### Constructors, captures, and generated classes

- Constructor results use their selected physical construction and state plan.
- A capture preserves an exact fact only when the captured definition and the
  generated field's producer-wide storage plan both prove that carrier.
- Mixed captures are analyzed independently; one broad capture does not erase
  unrelated exact captures.
- Anonymous and compiler-generated classes follow ordinary class, field,
  constructor, and inheritance rules. Generated status is never proof.

#### Fields and properties

- A field read produces the field's fixed storage carrier and its authority-
  recorded views.
- A field write must enter that one carrier through an explicit legal transfer.
- A property call is routed through its recorded accessor MethodDef and body;
  privacy or finality alone does not prove that it is a trivial field access.
- A proven trivial accessor may preserve the field fact because of its actual
  definition graph, not because of a property or getter recognizer.
- Setters and mutable properties remain subject to every legal write and broad
  entry; a typed getter proof does not imply typed writable state.

#### Inheritance and bridges

- Virtual dispatch starts from the logical override family and its recorded
  physical slots.
- Existing base/interface MethodDefs, retained MethodImpls, and foreign slots
  remain authoritative.
- A memberless derived interface owns only its exact `InterfaceImpl` edge. Its
  inherited fake override is a logical view of the parent slot: it does not
  copy the parent's MethodDef or result layout and does not create a bridge.
  Calls through that logical view bind the parent MethodDef through the
  recorded `InterfaceImpl` edge. This rule does not exempt a class fake
  override, which can select a concrete inherited implementation and must still
  satisfy the recorded slot or an explicit MethodImpl obligation.
- When a base already owns every required exact constructed interface view, its
  interface obligation is already discharged; classifier equality alone is
  insufficient to prove that fact. Otherwise bind the inherited target and
  recorded interface slot at the leaf construction and compare their complete
  verifier-visible callable shapes: instance/static form, MethodDef generic
  arity, ordinary explicit parameters, and the direct or split-nullable result
  layout including its physical trailing `bool&`. Repeated paths to one exact
  construction deduplicate. Distinct constructions require unambiguous
  declaration, override, `InterfaceImpl`, and `MethodImpl` authority or fail
  closed; per-value selected lineage is never declaration authority. An equal
  shape may implement the leaf slot implicitly; a mismatch requires an explicit
  adapter/MethodImpl.
- A declared split override which also overrides an unsplit class MethodDef
  preserves two physical slots: the natural split interface MethodDef and an
  adapter to the unchanged base MethodDef. Neither logical substitution nor a
  fake override rewrites either slot.
- A derived exact body with a different physical signature receives an explicit
  bridge/MethodImpl obligation. It does not rewrite the base slot.
- Direct-`super` calls target the producer-recorded base MethodDef non-virtually.
- Deeper inheritance and separate assemblies extend the same authority graph;
  they do not reconstruct slots by name or current logical substitution.

#### Semantic hooks

A semantic body receives an explicit entry environment. Its exact current
receiver may retain an authority-proven natural construction while a broad
parameter begins on its semantic or object carrier. Broadness propagates from
actual definitions and uses; it does not contaminate unrelated receiver-derived
state, helper results, locals, or captures.

The owning declaration ADR decides which typed entry, hook, capability, bridge,
or fallback exists. Provenance only determines which of those already-selected
routes a value can satisfy.

### 7. Late operation routing

Operation routing occurs after logical resolution and declaration selection:

```text
logical operation and override family
        + authority-selected callable endpoints and semantic policy
        + receiver/argument physical value facts
        -> one proven physical route | unavailable | conflict
```

The logical operation determines the permitted behavior, broad-candidate
barriers, virtual target family, defaults, `super`, and required result. Value
provenance cannot choose a semantically different member merely because it is
typed.

Within that allowed family, an already-proven selected natural view may service
an output-safe operation after logical widening. This is permitted when:

- the selected view is in `guaranteedPhysicalViews`;
- lineage is unambiguous when the object has multiple relevant constructions;
- the operation consumes no owner-dependent input which the widened Kotlin view
  admits more broadly;
- invoking that MethodDef preserves virtual dispatch, body selection, side
  effects, exception behavior, and evaluation count; and
- its exact result can be widened to the logical result through an explicit
  identity-preserving or value conversion.

Thus a locally selected `Source<string>` may continue to call its natural
producer under a logical `Source<Any?>` or star view when the exact lineage is
still proven. The result is widened after the call. This does not create
`Source<object>` and does not require semantic dispatch.

For an imported CLR MethodDef, retained metadata selects the MethodDef and its
physical owner family but does not by itself select a receiver construction.
The bounded foreign route chooses that construction only from the receiver's
existing value fact, in this order:

1. selected lineage for the retained owner family;
2. the receiver's verifier-visible direct carrier when it constructs that
   family; or
3. one unique construction of that family in the recorded physical-
   interface closure of its direct carrier and guaranteed views. A guaranteed
   view of the family is the zero-edge case.

Zero candidates, or multiple candidates without an existing selector, are
`Unavailable`. The operation query does not accept a desired foreign
construction from its caller, so a logical Kotlin type cannot manufacture one.
Lineage remains only a selector over guaranteed views. Following an authenticated
physical edge selects an operation receiver; it does not add that derived view to
the value's provenance.

After parameter admission, the route produces its result from the instantiated
MethodDef layout. `Void` produces no value, `Direct` produces that exact
carrier, and `SplitNullable` produces its exact payload-plus-flag layout.
A constructed result guarantees its own physical view with frozen-result
evidence. Reference-shaped and substitution-dependent results remain
conservatively maybe-null until logical authority or a checked transfer proves
more; a later logical return type never remaps their carrier.

The same rule must never narrow a genuinely broad source. A public parameter,
mutable join, object field, or foreign value which carries no preserved unique
view cannot acquire one from its logical type. It uses the selected semantic or
object route, a separately admitted checked foreign route, or fails closed.
A broad candidate input, semantic-result contract, explicit MethodImpl,
direct-super target, or retained foreign override slot remains authoritative
even when another exact carrier happens to be available.

The emitter consumes the selected route. It does not rediscover representation
from declaration names, packages, stdlib membership, member names, IR origins,
or logical supertypes.

### 8. Disposition of the current bounded proofs

The commits from `445266c9` through `030bb9e1` remain executable evidence while
the shared model runs in shadow mode. Their architectural disposition is:

| Proof | Fundamental rule | Current implementation disposition |
| --- | --- | --- |
| `445266c9` post-representation covariant slots | an already emitted base/interface MethodDef is physical authority | **Fundamental; retain.** Later specialization emits an adapter/MethodImpl. Central declaration authority should replace any local signature reinterpretation. |
| `ec04adb7` erased bootstrap interface edges | a TypeDef may mention only generic binders it physically owns | **Fundamental; retain.** This is a metadata validity guard, not an optimization recognizer. |
| `8dd5800d` closed semantic interface inputs | a broad parameter may enter a semantic domain without erasing unrelated exact receiver/state facts | **Temporary proof restriction.** The current final/non-generic and paired-body slice should generalize to entry-environment facts plus independent parameter domains. |
| `3581b56d` nullable generic interface results | direct open `T?` may have a producer-recorded payload-plus-null-flag layout | **Fundamental layout, removable combined role.** `SplitNullable` remains; any member category which couples it to inputs/owners is derived from `CallableContract`. |
| `155e82c9` compiler-owned inline temporaries | a single-definition immutable alias may preserve its producer fact | **Derivable and removable.** IR origin and compiler ownership are not evidence; ordinary reaching-definition, placement, and binder-safe inlining rules must derive the result. |
| `00dc1de3` exact-receiver output-only helpers | a proven receiver view may service an operation which consumes no broadened owner input | **Derivable and removable.** Use the shared polarity/parameter-domain query and virtual-slot authority, not a helper recognizer. |
| `03cd3271` parameterless exact result chains | an authority-recorded producer result may carry exact provenance through a chain | **Temporary proof restriction, then removable.** Parameterlessness is conservative; the general condition is that no argument or semantic input selects an incompatible result construction. |
| `030bb9e1` generated-owner captures | an exact captured definition may enter a field whose producer-wide storage plan selects that exact carrier | **Derivable and removable.** Generated/anonymous status is never evidence; capture definition, constructor transfer, and field plan are. |
| Stage 6 producer-wide FieldDef authority | detached families, private helpers, state, and output pairing reach one monotone fixpoint; final per-field requirements select state before BOUND identity/writer freezing and actual-only sealing | **Fundamental authority rule with a temporary proof grammar.** Retain fixpoint closure, field-set and writer-lineage preservation, final-requirement admission, and actual-only sealing; generalize the admitted field/carrier grammar structurally. |

None of the bounded positive proofs is presently classified as unsound within
its asserted restrictions. Three tempting generalizations are unsound and are
therefore forbidden: treating an IR origin as proof, treating a generated class
as proof, or treating a parameterless call as proof without its MethodDef result
contract and receiver lineage. Shadow comparison must include hostile negatives
for each before deleting the old recognizer.

### 9. Split-nullable is an orthogonal result layout

A direct logical `T?` result may use:

```text
SplitNullable(payload = physical T expression, out bool isNull)
```

The flag denotes logical absence of the outer result and is a hidden final CLR
parameter, not a Kotlin value parameter. Payload substitution uses the
producer-recorded physical expression and the actual constructed owner/method
arguments. It must not round-trip through a later logical type mapper.

Because parameter domains and result layout are independent, a future
structural lookup may compose:

```text
parameter 0: STRICT_OWNER_INPUT(!K)
result:     SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool)
```

and emit an exact `!V Get(!K, out bool)` without a `Map`, member-name, package,
or combined-role exception. Exact value-type calls remain unboxed. Semantic or
unknown routes may materialize the logical nullable value only at their
operation boundary. Split result layout never authorizes split fields or
duplicate state.

### 10. Separate compilation and foreign declarations

A Kotlin producer serializes stable physical declaration facts, not local
value provenance. A consumer binds those facts by artifact identity, physical
owner/member identity, binder scope, and complete recorded contract. It does
not infer them from generated names or reinterpret them from substituted KLIB.

When the current emitter owns the final natural generic-interface MethodDef,
the bounded local route emits its exact open-declaration token, selects an
already-proven closed construction implemented by the receiver, and uses the
mandatory two-handle `MethodBase.GetMethodFromHandle` overload. A separate
consumer uses the same route only from the declaration-level natural-slot seal.
Logical IR, an implementation-family seal, and incomplete older library records
are not declaration-token authority.

The natural `N` declaration seal is admitted only when its logical and physical
owner/member joins agree with the ordinary class, function, member-family, and
published-interface records in the same producer. Orphan, cross-library,
cross-owner, result-layout-mismatched, or implementation-disagreeing seals fail
closed. Before exposing the record as `BoundProducer` authority, the dependency
loader validates the sealed TypeDef path, flags, ancestry, generic parameters
and constraints, plus the complete MethodDef name, flags, binders, signature,
parameter rows, and result layout against the containing DLL. Split-nullable
validation includes the final `[out] bool&`. Name or logical-signature matching
alone is insufficient.

A consumer also joins the validated physical record with its independent KLIB
projection before routing. Instance/static shape, declaration-independent
ordinary parameter carriers, the direct owner-result parameter index, and the
logical split-nullability bit must agree exactly. This logical join may
invalidate a stale or cross-wired record but never establishes physical
identity, supplies a missing carrier, or rewrites the recorded MethodDef.
Physical duplicate detection therefore precedes logical slot annotations:
different Kotlin domains or nullability cannot turn two claims on one CLR row
into distinct MethodDefs.

#### Standalone implementation MethodDef seal

The natural `N` seal owns the interface declaration MethodDef. A separate `M`
seal may own one Kotlin implementation-class MethodDef which a downstream
consumer must treat as an already-existing physical base slot.

`M` records:

- the logical natural-interface member, implementation owner, and
  implementation member identities;
- the final implementation TypeDef path, generic arity, invariant parameters,
  visibility, dispatch, and constraint-free binder;
- the exact direct construction of the `N` owner implemented by that TypeDef;
  and
- the final implementation MethodDef name, flags, binder, complete parameter
  carriers, and direct or split-nullable result layout.

`M` is selected only through the exact pre-lowering implementation and natural
declaration identities and is projected only from final-emission observations.
The complete library index joins it conjunctively with its `C`, `F`, and `N`
records. The bounded `J` and `M` owner grammars are disjoint: an implementation
owner/member or physical MethodDef claimed by both is conflicting. A later
grammar which admits coexisting partial and complete evidence must require exact
agreement before replacing that restriction. Neither `J`, KLIB substitution, a
matching method name, nor a current consumer type can create `M`.

The bounded `M` grammar admits only a top-level public, non-abstract, non-sealed
generic class with invariant unconstrained owner parameters; one ordinary
public virtual non-final non-abstract instance hidebysig MethodDef with no
specialname/runtime-specialname flags or method-generic parameters; and exactly
one direct natural-interface construction whose
arguments are parameters of that class binder. Binding those arguments into
`N` must produce the complete `M` signature. Consumer-relative named carriers,
multiple candidate `N` roots or constructions, and inferred constructions are
unavailable.

`M` describes implicit CLR slot eligibility only. It does not claim a
MethodImpl row, semantic hook, capability, or complete implementation family.
The producer rejects publication when final emission associates the MethodDef
body with an explicit MethodImpl. The consumer validates the exact TypeDef,
GenericParams, MethodDef, parameters, result layout, and constructed
InterfaceImpl against the producer DLL. It also consumes objective MethodImpl
rows and rejects any explicit redirection of the recorded class body or the
selected natural-interface construction. Representing an explicit MethodImpl
as positive authority would require another sealed record and remains outside
this grammar.

The ABI-64 `M` wire is allocation-bounded before recursive decoding: physical
fields, arities, parameter/argument/path collections, recursive depth, and
aggregate type nodes have explicit ceilings. MethodImpl equivalence resolves
only exact same-module `TypeRef` aliases, including within complete signatures
and `TypeSpec` constructions. Local scope chains are depth-bounded and cyclic
or over-deep chains reject the authority record; foreign or merely same-named
references never become local physical evidence.

A downstream override planner consults `M` before mapping the logical KLIB
return type. An equal complete physical shape reuses the ordinary CLR virtual
slot. A mismatch requires separately proven bridge/MethodImpl authority or
fails closed; it never rewrites the base MethodDef.

The initial natural `N` declaration-seal grammar is deliberately bounded to
directly declared slots on root natural interfaces with no direct edges or
generic constraints and only declaration-local carriers. Semantic member role,
parameter domains, and result layout are independent facts. The admitted
grammars currently include direct producer results, direct split-nullable
producer results, and one structural callable with one strict invariant owner
input plus a distinct covariant split-nullable owner output. Unsupported forms
are `Unavailable`; they are never reconstructed, widened to `object`, or
inferred from an implementation seal. This is an admission boundary for the
first portable record, not a permanent claim that inherited, edge-bearing, or
multi-member slots need a different authority model.

CLR interface dispatch selects the ordinary or explicit `MethodImpl`. The
selected construction supplies binding context; selected-view lineage may
choose among already-guaranteed constructions but cannot establish one. No
concrete method search, marker attribute, interface enumeration order, or
logical signature reconstruction participates in slot identity. The callable
record keeps parameter policies and result layout orthogonal. In particular, a
split-nullable MethodDef token includes its physical trailing `bool&`, while the
recorded result layout tells the invoker how to materialize the logical result.

Retained foreign CLR metadata is independent physical authority. A foreign
TypeDef or MethodDef is identified by its selected assembly metadata and
metadata handle. Its generic arity, variance, constraints, signature,
InterfaceImpls, and MethodImpls are consumed exactly or rejected. Enhanced FIR
types do not rewrite retained metadata.

A physical TypeDef fact is complete only when it includes every ordered
`GenericParam` row: variance, nominal constraint carriers, reference-type,
non-nullable-value-type and default-constructor flags, and by-ref-like
permission. Arity alone is not TypeDef authority. A source which cannot supply
those rows returns `Unavailable`; it may not fill the missing rows with
invariant unconstrained parameters. Likewise, knowing the rows does not by
itself prove that arbitrary type arguments satisfy them. Construction of a
constrained TypeDef requires a separate physical constraint-satisfaction proof
or an already-retained/emitted construction; until then it remains
`Unavailable`.

Retaining an assembly, TypeDef, or MethodDef identity is necessary but does not
authorize a caller-supplied normalized description of that row. Foreign
declaration descriptions enter the shared index only through an adapter over
the exact FIR-retained TypeDef or callable carrier and its selected assembly
graph. A TypeDef carrier is sealed and validates its selected assembly,
definition, resolved hierarchy, and graph by retained identity. It is authority
in its own right and never requires a declared marker member. Callable carriers
add exact MethodDef or Property rows; they do not authenticate unrelated
TypeDefs.

Lazy external FIR2IR transports an already-selected class carrier through one
target hook and optional compilation-local class metadata. Common FIR2IR and IR
do not inspect or serialize its platform payload, and targets which do not
supply one retain the ordinary null metadata behavior. The DotNet backend may
consume that class payload as TypeDef authority without materializing a class's
declarations. A callable still requires its own retained MethodDef or Property
carrier, which must match the class carrier's selected assembly, TypeDef,
hierarchy, and graph by identity. Class metadata therefore cannot fabricate or
replace callable authority.

Importer admission is based on the complete physical type contract, not on the
presence of a declared callable. A public abstract interface with no supported
declared MethodDef or Property may therefore enter when its complete TypeDef,
GenericParam, hierarchy, and `InterfaceImpl` facts are supported. Lazy FIR2IR
must preserve that class authority even though there is no declaration from
which to recover it. A fake override inherited in Kotlin's logical view does
not become a child MethodDef: invocation binds the retained parent MethodDef
through the exact recorded edge. A resource-free foreign-DLL pipeline test is
the executable admission proof; constructing only a synthetic IR class is not.

The adapter re-resolves the raw MethodDef signature in that same graph, checks
it against the retained resolved signature, checks that the retained hierarchy
contains every raw base/interface row, and imports the complete ordered
`GenericParam` rows. The index remembers exactly which foreign rows that
adapter authenticated; advancing an authority epoch preserves that set but
cannot expand it. A direct `bind` of an otherwise identical foreign description
is therefore `Unavailable`.

The initial adapter grammar binds one selected ordinary public abstract virtual
instance MethodDef on a public top-level abstract root interface. The owner
view must be the open declaration view; direct base/interface edges and owner
MethodImpls are outside this first grammar. Direct results and parameters may use the
shared boolean, `int32`, string, and object leaves, exact owner or method
parameters, SZ arrays, and recursive constructions of the same retained
interface. This is a structural admission boundary, not a declaration-name or
package rule. A valid shape outside it is `Unavailable`; a detached MethodDef,
an incomplete retained hierarchy, invalid generic binder/flags, or disagreement
between retained and raw signatures is `Conflict`. Recorded constraints still
do not prove an arbitrary constrained construction.

The first inherited-receiver grammar admits one public top-level abstract
interface with an exact retained TypeDef carrier in the same selected assembly
graph. It may declare no callable. It has zero or one unconstrained type
parameter whose exact CLR variance is retained, no base class, no MethodImpl,
and a complete set of one or two retained and raw `InterfaceImpl` rows. At least
one row reaches the selected MethodDef owner by retained TypeDef identity. If
only one row reaches that owner, an optional second row may target an
independently authenticated public, non-generic root interface; that target
receives its own TypeDef authority and complete empty edge set. Alternatively,
both rows may reach the MethodDef owner as distinct exact constructions. Two
identical physical edges contradict the complete metadata set and are
`Conflict`, not an ambiguous value fact. Receiver and targets may reside in
different selected assemblies. Each raw TypeSpec must bind through its exact
AssemblyRef identity; an unbound or mismatched reference is a declaration
conflict rather than permission to search by name. An owner edge may close the
selected MethodDef owner with supported declaration-independent carriers or
reference the child `!0`, recursively through the admitted SZ-array carrier.
The adapter resolves the receiver's exact open GenericParam context, re-resolves
every raw edge, and requires exact retained/raw agreement. It records the child
binder on its own TypeDef and retains the entire direct edge set as an unordered
physical set; ordinary physical-closure substitution derives `Base<int>` from
`Child<int>`.

When two distinct owner constructions remain in that closure, declaration
authority proves both views but selects neither operation view. A receiver with
no selected lineage therefore produces `Unavailable`. Existing lineage may
select either construction only after the closure independently guarantees it;
it cannot manufacture a third construction. InterfaceImpl row order never
breaks the tie. The external-DLL pipeline proves this with one memberless child
implementing both `Source<int>` and `Source<bool>` and exact typed calls through
both selected Kotlin locals.

No logical type or InterfaceImpl row order participates. Additional binders,
more than two direct edges, a dual-owner plus auxiliary combination, generic or
deeper auxiliary interfaces, constraints, variance conversions, classes,
MethodImpls, unsupported carrier leaves, and hierarchy disagreement remain
unavailable or conflicting according to the ordinary validity boundary.

CLR reference-only variance may establish a verifier-valid view only through
the retained or producer-recorded generic declaration and physical
assignability rules. Kotlin variance alone cannot do so, and value arguments
remain invariant at the CLR boundary.

Per-value lineage is never serialized as hidden object state. If a public or
storage boundary loses the only evidence selecting one of several foreign
constructions, later code must use an admitted semantic/checked route or report
the boundary unsupported. It may not guess by interface enumeration order.

## Non-negotiable invariants

1. Kotlin IR/KLIB remains logical authority.
2. Emitted and retained CLR declarations remain physical authority.
3. One Kotlin object has one receiver identity and one authoritative state.
4. No wrapper, proxy, duplicate store, or shadow state repairs representation.
5. No CLR construction is invented from Kotlin subtyping.
6. Selected-view lineage is a selector over proven views, never evidence.
7. ProducedLayout and StorageLayout are distinct and non-circular; auxiliary
   split-result slots are explicit.
8. A broad semantic input does not contaminate unrelated exact state.
9. Exact provenance never narrows a genuinely broad source value.
10. State admission consumes the monotone closure's final per-field
    requirements; the compressed owner disposition remains diagnostic.
11. The complete live module re-proves typed writers immediately before BOUND;
    unsupported live stores make the family unavailable rather than becoming
    internal errors.
12. After a state family is bound, a later lowering cannot change its complete
    instance-field identity set or add, remove, duplicate, or retarget a writer
    except through an exactly recorded lowering contract.
13. A later lowering cannot degrade an already-proven typed route unless it has
    new authority invalidating the proof.
14. Missing authority fails closed; expected facts never fill missing final
    emission evidence.
15. Production remains erased until one complete atomic generic-owner cutover.
16. Rehearsal emission and its physical ABI retain an exact inverse/rollback.

## Hostile counterexamples required

The model is not trusted until one declaration- and package-independent matrix
covers at least:

- a mutable source local which successively stores two incompatible
  constructions;
- alternative control-flow arms with different exact constructions and with an
  unknown arm;
- known-null plus exact-reference joins and nullable value-layout joins;
- Kotlin value-type covariance/contravariance which CLR cannot express;
- CLR reference-only variance which the verifier can express;
- stars, input/output projections, and an exact view widened before a call;
- one object implementing two constructions, with preserved lineage locally and
  deliberately lost lineage at a public/storage boundary;
- broad candidate inputs whose body must run or whose fixed barrier must win;
- semantic hooks with an exact receiver and unrelated broad parameters;
- generated and anonymous classes with exact, broad, and mixed captures;
- exact and object-carried fields, custom properties, and hostile setters;
- an owner with both resolved and unresolved state requirements, which must not
  be admitted through an owner-level summary;
- a post-BOUND `!T` or `object` shadow field, duplicated/removed/retargeted
  explicit writer, malformed or repeated constructor initializer, and a
  duplicate final owner/field observation in another emission scope;
- a bridge/body-producing pass which materializes an unsupported typed store
  before BOUND, proving ordinary `Unavailable` admission rather than an internal
  conflict, paired with the same mutation after BOUND proving a hard conflict;
- deeper Kotlin/Kotlin, Kotlin/C#, and C#/Kotlin inheritance, defaults,
  reabstraction, diamonds, explicit MethodImpls, and `super`;
- retained foreign generic TypeDefs and MethodDefs, including reference/value
  variance and multiple constructions;
- producer/consumer libraries compiled separately and stale or contradictory
  physical records;
- direct and split-nullable results for references, signed value types,
  `Nullable<V>`, open owner/method parameters, and Kotlin value classes; and
- Framework 4.8, current CoreCLR, ReadyToRun, trimming, and NativeAOT.

Each positive test must prove object identity, authoritative state, selected
MethodDef/MethodImpl, exact carrier where promised, and Kotlin-visible result.
Each negative test must prove fail-closed behavior without a fabricated view or
silent semantic fallback.

## Migration recommendation

Proceed with this model now in shadow mode.

1. Expose read-only queries over existing representation, physical binding,
   state, and final-emission facts.
2. Compute value facts without changing emitted code.
3. Compare shadow placement and routes with every existing bounded proof and
   its hostile negatives.
4. Add retained-foreign and separate-consumer authority only through exact
   adapters over actual metadata or producer records.
5. Make the shared operation query authoritative for one bounded family at a
   time.
6. Remove an old recognizer only after the shared model derives both its
   positive and negative behavior.
7. Preserve the completed custom owner-input plus SplitNullable proof while
   generalizing callable composition; do not apply it to stdlib declarations
   until the shared model derives the broader family.
8. Run the complete generic-owner, Runtime/Stdlib, C# interop, deployment, and
   erased-inverse matrix before proposing production cutover.

The consolidation fails if reproducing a proof requires a declaration name,
package, collection kind, stdlib identity, member name, IR-origin whitelist,
logical-supertype reconstruction, or serialized per-value witness. In that
case the bounded implementation remains authoritative while the model is
revised.

This is a **GO** for production-inert architectural consolidation. It is not a
GO for a generic-interface, generic-class, or stdlib ABI cutover.

The bounded Stage 6 state slice implements the declaration/state half of this
model and is retained as executable evidence. Stage 7 independently composes a
strict owner input with a split-nullable owner result on a custom structural
family, including producer-recorded MethodDef consumption, ordinary C#
implementation, and the exact erased inverse. The next ordered work closes
retained/foreign declaration authority and shared value/operation provenance;
it is not another shape-specific state recognizer or a resumed stdlib census.

## Consequences

- Exact CLR carriers become the normal local route where authority and flow
  prove them; semantic/object routing remains an operation-specific escape
  hatch rather than a contagious default.
- Existing bounded recognizers become migration scaffolding, not permanent
  architectural categories.
- Split-nullable results compose with parameter policies without multiplying
  member roles.
- State analysis remains conservative and open-world even when local values are
  precise.
- Foreign CLR identity remains native and exact, while Kotlin-owned owners may
  choose a different natural/semantic family in their owning ADR.
- Final emission is independently certified, enabling exact rollback and
  preventing expected declarations from masquerading as emitted metadata.
