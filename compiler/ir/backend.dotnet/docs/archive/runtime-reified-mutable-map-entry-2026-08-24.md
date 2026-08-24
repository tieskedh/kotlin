# Runtime reified MutableMap.MutableEntry (2026-08-24)

## Scope

ABI/runtime surface 58 selects the first invariant multiple-owner-parameter
child:

```text
interface PairSource<out K, out V>
    -> K first
    -> V second

interface MutablePair<K, V> : PairSource<K, V>
    -> V replaceSecond(V value)
```

The rule is structural and declaration-name independent. A public interface
with two or more invariant nullable-`Any`-bounded parameters may extend exactly
one already admitted covariant producer-property root of equal arity through
an identity substitution. The child must declare exactly one abstract,
non-null input/output member whose argument and result are the same direct
owner parameter. A changed, reordered, projected, fixed, or nullable parent
argument; a second parent; an additional member or property; a default; or a
deeper lineage rejects the complete family.

## Runtime placement and natural contract

Runtime instantiates the rule as:

```text
Kotlin.Collections.MutableMap.MutableEntry`2<K,V>
    implements Kotlin.Collections.Map.Entry`2<K,V>
    -> !1 SetValue(!1 newValue)
```

The natural invariant interface is nested under the accepted arity-zero
MutableMap metadata container. Surface 58 does not create or imply
`MutableMap`2`. The existing non-generic nested MutableEntry remains the
Kotlin semantic capability.

An ordinary sealed non-partial C# class implements only the natural invariant
MutableEntry interface, its inherited typed Key/Value properties, and typed
`SetValue(V): V`. It names no exact sibling, erased interface, generated
semantic interface, partial class, source generator, wrapper, or adapter.

## Typed state and operation-local semantic routing

Kotlin implementations retain one object and independent fields:

```text
MutablePairValue<K,V>
    field !0 firstState
    field !1 secondState

RuntimeMutableEntryValue<K,V>
    field !0 keyState
    field !1 valueState
```

Exact calls use the natural `!1 -> !1` slot. A star or input-projected Kotlin
operation crosses only the child capability's `object -> object` slot. The
capability inherits the parent's getter capabilities and does not copy their
bodies or state. A downstream semantic obligation may not turn a
`TYPED_STORAGE_PRODUCER_GRAPH_PROVEN` field into object storage merely because
the boundary must narrow one operation; object input is checked at the actual
typed store. No shadow state, wrapper, proxy, or global owner erasure is
introduced.

## General dual-entry closure

The full Runtime regression selection exposed several general composition
defects which the small MutableEntry proof alone did not exercise:

- a final function whose honest public parameter is a closed natural
  `I<C>` or method-generic `I<T>` now retains that typed MethodDef, while a
  separately published compiler entry owns the object parameter required by a
  widened Kotlin caller;
- producer and consumer agree on that paired entry across KLIB/DLL boundaries,
  including copied method parameters and their constraints;
- a concrete natural Runtime receiver and argument construction wins over an
  earlier conservative semantic route, while an actually unnameable carrier
  still uses capability/foreign dispatch;
- relative-input MethodImpl bridges coalesce identical inherited slots and
  emit every required override instead of duplicating one physical method;
- a semantic body reached through an inherited relative input propagates only
  to its private helper graph and does not degrade producer-proven typed state;
- the foreign object argument vector boxes value carriers before `stelem.ref`,
  and a canonical semantic Collection parameter is entered only after an
  explicit runtime guard; and
- the existing collection-`containsAll` Runtime helper declares its actual
  nine-slot maximum evaluation stack instead of the invalid value eight.

These rules contain no MutableEntry, Map, List, Iterator, or collection-name
switch. They preserve typed/native CLR access as the normal route and keep the
semantic carrier as the operation-specific escape hatch.

## Verification

The hostile three-module Kotlin product and separately compiled C# consumer
run under PSI and LightTree on .NET Framework 4.8 and .NET 10. The four focused
rehearsal lanes and four production-erased inverse lanes pass. They cover the
general synthetic MutablePair shape, Runtime MutableEntry placement, inherited
natural getters, typed input/output mutation for reference and value owners,
stars/projections, identity, exact interface maps, natural-only C# authoring,
and reflected independent `!0`/`!1` fields.

The complete ten-family Runtime selection passes in both parsers in rehearsal
and production-erased inverse mode. It additionally covers Iterator,
MutableIterator, Collection/Set, List/ListIterator, MutableCollection,
MutableSet, MutableList, and Map.Entry regressions.

The dependency-wide strict aggregate exits zero. Direct XML audit covers 191
suites and 2,333 tests: 187 FIR suites/2,199 tests, two integration suites/127
tests, the one-test backend resolver suite, and the six-test `dotnet.ir` model
root. Every suite is fresh; there are zero failures, errors, or skips.

## Boundary and next gate

Production Kotlin-owned generic-interface mapping remains atomically erased
outside the rehearsal. Surface 58 closes only this invariant arity-two child
and the general dual-entry/relative-input regressions required to keep the
existing Runtime family coherent. Generic Map/MutableMap, mixed variance,
nullable input/output mutation, defaults, broader multiple-parameter
inheritance, static foreign protocol, trimming, NativeAOT, tooling
presentation, and final atomic rollback remain separate gates. The next family
must be recomputed from the complete Common dependency/member graph.
