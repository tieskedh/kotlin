# Generic-owner physical-value provenance foundation

Date: 2026-08-25

## Context

The generic-owner rehearsal had accumulated bounded recognizers for exact
compiler temporaries, exact current-receiver helpers, parameterless producer
chains, and generated-owner captures. Their positive cases shared one deeper
property: an identity-preserving flow retained a real CLR construction after
its logical Kotlin view widened. Their negative cases shared the converse: a
source value, mutable join, semantic input, or unrecorded physical edge could
not acquire a construction merely because its Kotlin type looked exact.

Continuing the source-built Stdlib census would have added another local rule
before that common principle was executable. The census therefore stops at the
generated generic-subclass static-initialization blocker while the common
physical-value model is consolidated.

## Decision

The rehearsal uses three cooperating authorities rather than one scalar
exact/semantic lattice:

- staged declaration authority selects and binds actual TypeDefs, MethodDefs,
  fields, edges, slots, and calling conventions;
- compilation-local value provenance tracks produced carriers, guaranteed
  real views, selected lineage, and null state; and
- operation routing starts from the already-selected callable contract and may
  use provenance only as supporting evidence.

State selection remains a separate producer-wide, open-world decision.

The first implementation is a production-inert symbolic algebra. A local
TypeDef is identified by IR symbol identity plus canonical/natural/exact view;
a producer declaration retains its library artifact and recorded path; and a
foreign CLR declaration retains its metadata source and handle identity. A
symbolic generic parameter is scoped to its physical TypeDef or MethodDef, so
two unrelated owners' `!0` values never compare equal. Only a one-way final
binder may turn these references into verifier-visible IL types. An unavailable
live declaration cannot be reinterpreted as a semantic fallback.

Declaration identity never contains its current arity, type category, or null
encoding. A conflict-checked index owns those descriptions, rejects a retained
MethodDef whose reported arity disagrees with its producer/foreign identity,
and advances monotonically between authority epochs. Conflicting later
descriptions fail instead of creating a second apparent TypeDef or MethodDef.
Symbolic parameters, constructions, and carriers can be created only through
that index and validate their complete nested argument graph.

`ProducedCarrier` and `StorageCarrier` are independent. Placement validates a
producer against a carrier already selected for the destination; it never uses
that destination as circular proof. A subsequent read produces the selected
storage carrier while retaining independently justified views. Placement is
identity-preserving: reference-to-`object` flow is permitted, while boxing,
unboxing, semantic adaptation, and nullable materialization must be explicit
operations which create new facts. Alternative reaching writes join; a
sequential overwrite kills prior contents.

Selected lineage is only a selector over the guaranteed-view map. Its
constructor rejects a lineage entry without independent evidence. Joins
intersect guaranteed views and retain lineage only when every reaching path
selects the same construction. Ordinary construction disagreement loses
precision and may join at a truthful common carrier; it is not a declaration
conflict and never fabricates `I<object>`. Evidence-source labels remain
diagnostic side data and do not alter lattice equality.

Null is a carrierless produced layout. Guaranteed views quantify over non-null
values, so `null + Source<int>` retains the `Source<int>` guarantee while
becoming maybe-null. Null can be placed directly into an already-selected
compatible reference carrier without pretending that it was produced as
`object`. Inline nullable values require explicit null materialization, while
an already typed substitution-dependent `!T` may remain maybe-null in the same
`!T` carrier without accepting carrierless null. Split nullable remains an
orthogonal method-result layout and cannot silently become field or local
state.

## Executable proof

`DotNetGenericOwnerPhysicalValueModelTest` covers:

- lineage admission, retention, and loss for a dual-construction object;
- declaration-description conflicts and monotone authority epochs;
- owner- and MethodDef-scoped generic parameter identity;
- unreachable-flow bottom and representative join algebra laws;
- truthful common-carrier selection through an admitted shared view, with
  fabricated constructions rejected;
- produced-versus-storage carrier placement and broad-value non-narrowing;
- alternative and sequential mutable writes containing different
  constructions;
- explicit rejection of implicit scalar/value boxing;
- null/exact joins, inline-null materialization, substitution-dependent typed
  null, and direct null placement in an exact reference slot; and
- split-nullable isolation from ordinary state and incompatible layouts.

The model has no caller in routing, lowering, ABI serialization, or emission.
It therefore changes no Kotlin IR, rehearsal IL, production-erased product, or
runtime surface.

The final target aggregate exits zero. Direct XML audit covers 187 freshly
written FIR suites/2,235 tests, two freshly written integration suites/127
tests, and two freshly written backend unit suites/42 tests. The unchanged
six-test `dotnet.ir` root remains up-to-date. The complete inventory is 192
suites and 2,410 tests with zero failures, errors, or skips.

## Boundary and next step

The model does not yet prove a physical InterfaceImpl from an IR/KLIB
supertype. The representation plan must record admitted symbolic physical
supertype edges; until then the shadow analysis reports that view as unknown.
Stars, projections, value-type variance, mixed generated captures, retained
foreign generic flows, deep Kotlin/C# inheritance, separate assemblies, and
value-class split-result substitutions remain executable shadow gates; this
pure algebra does not claim to close them.

Before shadow facts can become authoritative, the declaration adapter must
derive class- and struct-constrained generic-parameter null encodings and
cross-check retained foreign TypeDef arity/category against the retained
metadata hierarchy. The current unconstrained `!T` encoding deliberately
remains substitution-dependent.

Next, run one read-only shadow analysis after the existing final-routing
fixpoint. Seed only authoritative receiver/parameter/result facts, select
immutable-local storage after analyzing its initializer, and store symbolic
snapshots outside every routing/emitter map. This final-only shadow can explain
surviving decisions but has survivor bias after semantic-body remapping. No
recognizer is removed until the same engine also observes the pre-remap entry
environment and explains both the positive and hostile negative cases.
