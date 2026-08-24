# Generic-owner private semantic-result routing

Date: 2026-08-24

## Context

After ABI/runtime surface 59 selected `Map<K,out V>`, the first dependency
recomputation compiled the actual Common/Runtime/Stdlib product under the
generic-owner rehearsal. Planning admitted the selected generic-interface
families but rejected ten calls such as:

```text
SequenceBuilderIterator.hasNext -> <get-nextIterator>
FlatteningSequence.ensureItemIterator -> <get-iterator>
MovingSubList.get -> <get-list>
AbstractMap.containsKey -> implFindEntry
IndexingIterator.hasNext -> <get-iterator>
```

Every call had an exact receiver construction and a result whose authoritative
state could contain a Kotlin-legal but CLR-unnameable nested generic view. The
planner therefore correctly selected `SEMANTIC_RESULT_CAPABILITY`. The failure
occurred during materialization: lookup considered only capability-interface
slots, while every target member was private and deliberately owned no such
slot.

## Decision

An exact same-owner semantic-result route to a private member binds directly to
that member's already planned private semantic hook. This is an internal call,
not a new externally callable Kotlin view.

The fallback is deliberately unavailable to non-private hooks. Public and
protected semantic families still require their planned capability dispatcher;
a missing dispatcher remains an ABI error. The change does not widen
visibility, create an interface slot, alter state, or route the call through
the natural typed wrapper.

## Executable proof

`genericOwnerPrivateSemanticResult.kt` isolates the same structural shape. An
invariant generic owner inherits an input from a contravariant scope, stores an
admitted `Iterator<T>`, and reads its private getter from an owner-independent
Boolean member. With the direct private-hook binding removed, the planner fails
with exactly one missing route:

```text
PrivateIteratorOwner.hasNext -> PrivateIteratorOwner.<get-iterator>
    (EXACT_CONSTRUCTION)
```

With the binding restored, PSI and LightTree execute on Framework 4.8 and
.NET 10. The same four lanes remain green with the rehearsal epoch disabled,
so the production-erased ABI is unchanged. The final full aggregate exits zero;
direct XML audit covers 191 suites and 2,341 tests with no failures, errors, or
skips.

## Result and next boundary

The source-built Stdlib product passes all ten former missing routes. It next
fails in final value routing when an external-function-carrier query attempts
to compute a public Kotlin ABI key for a local or generated Comparator-bearing
function after IR rewriting. That is a separate local-versus-external ABI
authority issue. It must be closed without a Comparator, Sequence, or stdlib
special case before the next generic-interface dependency family is selected.
