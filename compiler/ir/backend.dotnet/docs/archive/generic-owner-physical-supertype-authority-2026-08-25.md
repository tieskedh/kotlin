# Generic-owner physical-supertype authority

Date: 2026-08-25

## Context

The physical-value provenance foundation could retain an exact constructed
carrier, but it deliberately could not turn a Kotlin IR/KLIB supertype into a
guaranteed CLR view. That missing boundary matters: an admitted Kotlin edge may
be erased, redirected to a semantic capability, split across natural/exact
views, or absent from the emitted `InterfaceImpl` graph.

## Decision

Physical declaration authority now owns one complete direct-supertype edge set
per recorded TypeDef. Absence of that set is `Unavailable`; a recorded empty
set is positive evidence that the recordable direct graph is empty. Each edge
is a producer-recorded `BaseType` or `InterfaceImpl` target expressed in the
same binder-scoped symbolic carrier grammar as value provenance.

The binder enforces:

- at most one base edge, and exactly one for a recorded class/value-type set
  other than core `System.Object`;
- interface targets for `InterfaceImpl` and class targets for `BaseType`;
- no direct self edge, duplicate metadata row, or indirect inheritance cycle
  at declaration-index binding time;
- only parameters scoped to the source TypeDef, recursively through nested
  constructions and arrays; and
- no invented construction or logical-type substitution.

Closure substitutes an actual `C<A...>` construction through recorded edge
templates and returns the positive interface views reached. A missing
downstream complete set preserves positive views already found but marks the
closure incomplete. Multiple real views such as `I<int>` and `I<string>` are
ordinary facts, not declaration conflicts.

Two existing producer channels now adapt as far as their evidence permits:

1. published generic-interface family contracts normalize canonical/declared
   to their single recorded natural TypeDef and bind an explicitly recorded
   exact sibling identity, but publish no edge set because `directParents`
   deliberately omits canonical-only Runtime `InterfaceImpl` rows; and
2. detached generic-class physical-family artifacts consume their exact
   `directSupertypes`, including recorded semantic capability edges, from an
   explicitly supplied library artifact identity.

Neither adapter consults `IrClass.superTypes`, a declaration/package/member
name, or a generated CLR naming convention. A partial family relation is never
mislabeled as complete ancestry, and published interface capabilities are not
mislabeled as direct edges when their current ABI records only the capability
identity/closure. Detached artifact binding returns `Unavailable` for assembly/
current-compilation identities, method parameters, or named value types whose
inline-null truth is not present in that schema. Core `System.Object` is
normalized to the canonical object leaf rather than registered as a second
constructed carrier identity.

## Executable proof

The direct model tests cover complete-empty versus unavailable sets, category
and base-row validation, scoped and nested substitution, base traversal,
incomplete positive closure, multiple constructed views, duplicate/conflicting
records, binding-time cycles, and canonical `System.Object`. Producer-adapter
tests cover fail-closed partial published-family ancestry, canonical/declared/
exact identity normalization, generic-class owner/interface/capability/core
edges, and fail-closed named value-type and retained-foreign arguments.

The feature is production-inert. No lowering, route, emitter, serialized ABI,
or product consumes this authority yet.

## Boundary and next step

Retained foreign CLR hierarchy needs an identity for every retained target
TypeDef, not only the imported declaration's own TypeDef. Ordinary published
Kotlin class ABI still needs exact physical edge publication. Generic-parameter
constraint null encodings and named value-class carriers also remain
unavailable rather than guessed.

Next add the read-only final-routing shadow snapshot. Its exact self receiver
can be seeded independently, and interface views may be added only through
these recorded edge sets. The snapshot remains outside routing fixpoints,
emitter inputs, and ABI serialization; no bounded recognizer is removed yet.
