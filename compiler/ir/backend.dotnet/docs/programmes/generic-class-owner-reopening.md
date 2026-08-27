# Programme: truthful CLR-generic Kotlin owners

- Status: **Active — production-inert architecture replacement proof**
- Current production class authority:
  [`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md)
- Current production interface authority:
  [`../decisions/generic-interface-erased-identity.md`](../decisions/generic-interface-erased-identity.md)
- Shared candidate model:
  [`../decisions/draft-adr-generic-owner-physical-authority.md`](../decisions/draft-adr-generic-owner-physical-authority.md)
- Class candidate:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Interface candidate:
  [`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
- Atomic migration and inverse:
  [`generic-class-owner-migration-plan.md`](generic-class-owner-migration-plan.md)

This programme owns current scope and exit conditions. Git and the
[archive](../archive/README.md) own the rehearsal chronology; ADRs own durable
representation rules.

## Objective

Determine whether Kotlin-owned generic classes and interfaces can use truthful
natural CLR-generic owners as their normal physical representation while
preserving the complete Kotlin contract.

The desired endpoint, where admitted, is:

```text
Kotlin logical declaration in IR/KLIB
        +
one natural CLR-generic owner and one authoritative state
        +
compiler semantic capability/routing only for CLR-unnameable Kotlin views
```

For interfaces, the current replacement hypothesis is one complete natural
generic TypeDef. Physical variance is retained only where its full member and
inheritance surface is CLR-legal; otherwise the affected parameter becomes
physically invariant while Kotlin logical variance remains unchanged.

For classes, admission is open-declaration-wide while exactness may be local to
a construction or value. A semantic construction or field must not contaminate
unrelated proven typed state.

The programme succeeds only if ordinary CLR-language code can consume,
implement, construct, and subclass admitted natural owners without a hidden
generated ABI for behavior the compiler can derive from actual CLR slots.

## Binding boundary

Production remains erased throughout this programme. Candidate code may emit
test-only families, metadata, manifests, runtime helpers, and exact inverse
products, but it must not alter normal Runtime/Stdlib or user artifacts.

There is no per-interface, easy-owner, or mixed-module production pilot. A
future cutover replaces the selected complete family in one schema epoch after
the complete candidate and erased inverse both pass.

BK-1 remains the only accepted target-specific cast difference. It is governed
by the [breaking-change ledger](../decisions/breaking-kotlin-changes.md), not by
this programme.

## Current work package

The source-built Stdlib census is paused. Work proceeds in this order:

1. **Close foreign variance soundness.** Retain legal CLR reference covariance
   and contravariance, but reject implicit value/open-argument conversions at a
   mandatory physical-conversion gate with a stable source diagnostic.
2. **Plan the complete natural interface surface in shadow mode.** Compute the
   strongest CLR-legal physical variance per parameter over all members,
   constraints, properties, inherited constructions, defaults, and MethodImpl
   obligations.
3. **Prove a custom input-bearing interface.** Emit one natural TypeDef with no
   exact sibling; compile a complete non-partial C# implementation; prove exact
   value/reference calls and logically widened Kotlin calls on Framework 4.8
   and .NET 10.
4. **Replace structural foreign fallback.** Broad operations select recorded
   constructed interface MethodDefs and MethodImpls. Public concrete method
   name/arity discovery is removed, including for explicit-interface and
   overload-hostile C# implementations.
5. **Migrate the rehearsal, not production.** Re-prove the custom and Runtime
   families, then atomically remove exact-sibling TypeDefs, manifest roles,
   generator obligations, and runtime conventions from the candidate epoch.
6. **Complete declaration authority.** Add retained-foreign, static/file-facade,
   overlapping-family, and global final-emission evidence without letting an
   earlier expected record fill a missing final fact.
7. **Complete value provenance.** Make the shared operation query authoritative
   one bounded case at a time and delete a recognizer only after the general
   model derives both its positive and hostile-negative behavior.
8. **Compose callable contracts.** Prove owner-dependent input plus
   `SplitNullable(!V, out bool)` on a custom `Lookup<K,V>` before applying it to
   `Map`.
9. **Run the go/no-go rehearsal and exact inverse.** Only then resume the
   generic-owner-dependent Stdlib census.

The current checkpoint and blockers live only in [`../../STATUS.md`](../../STATUS.md).

## Non-negotiable gates

### Kotlin semantics

- Common source and shared compiler behavior remain authoritative.
- Stars, projections, declaration variance, broad candidate policies, defaults,
  overrides, `super`, reflection, and BK-1 produce their recorded Kotlin result.
- Valid Kotlin widening succeeds on the same object even when the CLR cannot
  name a corresponding construction.
- Exact provenance never narrows a genuinely broad source.

### Physical truth

- Existing emitted and retained foreign TypeDefs, MethodDefs, fields,
  InterfaceImpls, and MethodImpls remain authoritative.
- No `I<object>` or `C<object>` construction is fabricated from a value known
  only as `I<int>` or `C<int>`.
- Produced and storage carriers remain separate facts.
- Missing final evidence fails closed; expected declaration data cannot fill it.
- One object has one receiver identity and one authoritative state. No wrapper,
  proxy, copied field graph, or shadow state repairs representation.

### Interop

- Existing CLR libraries retain their native declarations and require no
  Kotlin generator, manifest, or semantic interface.
- An ordinary implementation of an admitted Kotlin-owned interface satisfies
  one complete statically checked natural CLR contract.
- Ordinary C# subclasses override only the natural surface; Kotlin semantic
  dispatch observes those overrides wherever behavior is mechanically
  derivable.
- Optional generators may forward declared defaults or build an explicitly
  selected visible adapter, but cannot implement hidden semantic ABI or become
  the admission authority.
- Non-derivable semantics use an explicit adapter/diagnostic or keep the owner
  unadmitted; they never become a hidden convention.

### Separate compilation and deployment

- Producer records bind by artifact plus exact logical and physical identity.
- Stale, partial, ambiguous, contradictory, and mixed-epoch artifacts reject.
- Framework 4.8, .NET 10, ReadyToRun, trimming, and NativeAOT use the same
  logical contract and truthful profile-specific metadata.
- The exact erased inverse remains runnable from every candidate checkpoint
  proposed for promotion.

## Hostile matrix

The model is not trusted until structurally custom declarations cover:

- reference, value, nullable-value, value-class, bounded, open, and multiple
  owner/method parameters;
- exact, widened, star, projection, semantic, null, bottom, and conflicting
  control-flow facts;
- immutable aliases, mutable locals, inlining, fields, properties, captures,
  generated classes, and mixed exact/broad values;
- broad candidate inputs, general `@UnsafeVariance` inputs, strict inputs,
  output-only calls, and split-nullable results;
- one runtime object implementing multiple constructed interfaces;
- Kotlin/Kotlin, Kotlin/C#, and C#/Kotlin inheritance, defaults, reabstraction,
  diamonds, explicit MethodImpls, and direct `super`;
- separate Kotlin and CLR producer/consumer assemblies and inlined dependency
  bodies; and
- identity, state, reflection, metadata, C# authoring, AOT/trimming, and exact
  rollback assertions.

The detailed construction vocabulary remains in the
[carrier/admission matrix](generic-class-owner-carrier-matrix.md). Direct host
surface attacks remain in the
[C# surface artifact](generic-class-owner-csharp-surface.md). Both are design
inputs and must defer to the current draft ADRs where they disagree.

## Go/no-go outcome

After the complete rehearsal, record exactly one result:

- **GO:** the shared model covers the complete selected Runtime/Stdlib family,
  ordinary CLR interop, deployment matrix, and erased inverse without hidden
  semantics or material unbounded cost;
- **CONSTRAIN:** truthful natural owners are possible only for a mechanically
  defined whole-declaration grammar; every other declaration remains erased;
  or
- **NO-GO:** retain erased Kotlin owners and use explicit C# export/adapters for
  host-facing typed surfaces.

A GO does not itself switch production. It authorizes the later atomic
migration only after ordinary product breadth, reflection/tooling, concurrency
and memory semantics, representative applications, and measurements satisfy
the [migration plan](generic-class-owner-migration-plan.md).

## Maintenance

Keep this document limited to current scope, ordering, and exit conditions.
Move completed proof narratives to Git or a dated archive record. Change a
durable representation rule only in its owning ADR, and update
[`../../STATUS.md`](../../STATUS.md) when the active checkpoint changes.
