# Generic-owner local physical-interface view

Date: 2026-08-26

## Context

The physical-value shadow could retain an exact local class construction and
compare a predicted local with the emitter, but it could not justify selecting
a natural generic-interface view which a later operation query may consume.
`C<T>` logically implementing `I<T>` was not enough: lowering may erase, split,
redirect, add, or omit the corresponding CLR InterfaceImpl row. The next slice
needed physical edge authority without reconstructing emitted metadata from an
`IrClass.superTypes` walk or allowing a logical destination to manufacture
`I<object>`.

## Decision

One context-owned local declaration-authority object now advances monotonically
through two epochs. Architecture planning binds admitted local generic-class
TypeDefs in `EARLY_REPRESENTATION_PLAN`. The pre-remap value shadow can consume
that epoch only to construct the current receiver `C<!T>`.

At the end of generic-interface bridge selection, a rehearsal-only recorder may
publish one complete class edge plan. This first adapter accepts only:

- an admitted unconstrained generic class with the canonical Object base;
- exactly one direct natural generic-interface family;
- a producer-published `ROOT`, `OWNED`, no-exact family contract;
- exactly three direct InterfaceImpl rows: natural `I<T>`, the class-owner
  capability, and the interface-family capability;
- invariant, non-null arguments equal to source owner-parameter default types;
  and
- no generated declared, exact, or canonical generic-interface bridge origin.

A non-Object base, another direct interface, a derived/intersection or
reused capability family, an exact sibling, non-universal bound, nullable or
projected argument, generated/inherited extra bridge family, or external
capability causes no record. Absence means `Unavailable`; it is not an empty
edge set.

The following authority pass adds the natural, class-owner capability, and
interface-family capability TypeDefs and binds only the recorded plan into
`BOUND_DECLARATION_INDEX`. It always records the Object base plus the three
selected InterfaceImpl constructions expressed in the source class's scoped
parameters. This is the complete bounded selection-site set for the admitted
grammar, not a universal audit of every later emitter row. The pass never walks
logical ancestry. Interface ancestry below those positive rows remains
incomplete; positive direct views survive, while negative downstream
conclusions do not.

## Value selection

An immutable source alias:

```kotlin
val sourceNaturalAlias: InlineProducer<T> = this
```

creates only a desired natural-view selector from its logical destination.
The produced value remains `InlineSelfView<!T>`. Closure over the recorded
class edge must independently contain `InlineProducer<!T>` before the
provenance can add `RECORDED_INTERFACE_EDGE`, select lineage, and place the
local in the natural interface carrier.

Selected lineage is a selector, never evidence. Internally it is keyed by
physical TypeDef identity; its diagnostic snapshot includes family kind and
TypeDef view. The natural `InlineProducer<T>` and interface-family capability
therefore cannot collide merely because both map to the same logical Kotlin
owner. The class-owner and interface-family capabilities are retained as two
separate guaranteed views.

Two hostile immutable controls use the same initializer:

```kotlin
val sourceWideAlias: InlineProducer<Any?> = this
val sourceNullableAlias: InlineProducer<T?> = this
```

Neither is an exact owner-parameter selector. The first cannot construct
`InlineProducer<object>`; the second cannot reinterpret open nullable `T?` as
`!T`. Their shadow facts retain only the exact current-receiver view with empty
selected lineage, while the unchanged emitter uses `object` locals.
Mutable flow, stars, use-site projections, and non-universal bounds remain
fail-closed.

## Executable evidence

The pure model ablation keeps the desired logical view constant and varies
only physical authority. Selection succeeds with the recorded
`C<!0> : I<!0>` edge, but fails when the edge is absent or when the only row is
`I<object>`. Recorded-edge evidence and selected lineage are both checked.

The integration probe checks four physical views: the concrete generic-class
self construction, natural interface, class-owner capability, and interface-
family capability. The POST-only source alias has `NOT_OBSERVED` continuity;
its predicted natural storage nevertheless matches the emitter's existing
`DECLARED_TYPE` local. The scope of this cross-check is one local plus the one
call operand below, not the surrounding operation graph.

The validator also reads the sibling IL which is subsequently assembled and
executed. It isolates the emitted `sourceAliasMatches` MethodDef and requires
one exact:

```text
callvirt instance !0 class 'InlineProducer`1'<!0>::'produce'()
```

Value and reference substitutions of the positive and hostile paths execute in
each profile-specific Framework 4.8 and .NET 10 product. Four PSI/LightTree and
profile positive rehearsal lanes, four production-erased inverse lanes, and
the focused backend unit suite pass. The final normal aggregate exits zero.
Direct XML audit covers 194 suites and 2,432 tests with zero failures, errors,
or skips. Backend, FIR, and integration freshly wrote four suites/60 tests,
187 suites/2,239 tests, and two suites/127 tests respectively; the unchanged
up-to-date `dotnet.ir` root retains one suite/six green tests.

## Boundary

This is behavior-neutral shadow architecture. Production creates no authority
index or edge-plan map, performs no authority analysis, and remains erased. No
new authority selects an emitted local, call, route, MethodDef, MethodImpl,
InterfaceImpl, field, state, or ABI record. The emitter remains authoritative;
the probe only cross-checks
the selected local and emitted call. The existing class-owner route analyzer
does not classify this direct natural-interface call; callable/MethodDef
authority remains the next slice.

`BOUND_DECLARATION_INDEX` is not sealed-emission authority. Later liveness,
fixpoint eviction, retained foreign metadata, separate assemblies, non-Object
bases, derived and multiple families, constraints, value classes, and
additional emitter-selected rows remain outside this adapter. Before codegen
may consume the index, the chosen representation needs a sealed-emission
cross-check or must become the emitter's own selected input.

## Next step

Bind the authoritative callable/MethodDef family behind the same staged query
and add a read-only operation-routing decision derived from callable authority
plus value provenance. Compare it with the existing natural and semantic
routes, including the edge ablation and broad/open-nullable controls. Do not
remove the compiler-temporary, semantic-body helper/result-chain, generated-
capture, or other bounded recognizers until that general query explains both
their positive behavior and hostile negatives.
