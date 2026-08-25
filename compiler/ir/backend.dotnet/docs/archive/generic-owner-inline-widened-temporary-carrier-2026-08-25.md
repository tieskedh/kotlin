# Generic-owner inline widened temporary carrier

Date: 2026-08-25

## Context

After the first closed semantic-input bridge, the actual source-built Stdlib
rehearsal reached `AbstractMutableList.indexOf`. Common inference instantiated
the covariant receiver of an inline helper at `Any?`, and the shared inliner
introduced this logical chain:

```text
AbstractMutableList<E> receiver
  -> temporary List<Any?>
  -> inlined-receiver List<Any?>
  -> iterator body
```

The producer and class owner were already a real CLR generic construction.
Materializing `List<object>` was nevertheless invalid for an open or
value-shaped `E`. Routing the complete member through the semantic capability
would have discarded a stronger typed fact which the compiler already owned.

## Decision

An immutable alias introduced wholly by Common IR lowering may retain the
exact natural generic-owner carrier supplied by its initializer. The admitted
origins are ordinary compiler temporaries, inlined parameter/extension-
receiver temporaries, and for-loop iterators. The physical carrier is
propagated through a chain of such aliases and an implicit logical widening
does not fabricate the wider sibling construction.

Selection is representation-driven. Both the normal and declared generic
signature mappings may prove that the logical source and destination are CLR
generic constructions; no classifier, package, stdlib, collection, or member
name participates. The candidate is rejected when the producer is `object`, a
semantic capability view, non-reference-shaped, already assignable to the
logical destination, or already represented by that destination.

Source declarations and mutable locals are deliberately outside the rule.
They retain their declared Kotlin view and can therefore carry different
exact constructions through the established semantic route. The change does
not create a wrapper, proxy, shadow field, second identity, or new public ABI.

## Proof and result

`genericOwnerInlineWidenedTemporary.kt` supplies a name-independent structural
proof. An inlined covariant producer self-view executes for `Int` and `String`
owners. The same test then assigns those two objects in turn to one mutable
source `Producer<Any?>`, proving that the compiler-temporary fast path did not
pin an ordinary Kotlin view to the first physical construction and that
identity is unchanged.

Four rehearsal and four production-erased inverse lanes pass through PSI and
LightTree on Framework 4.8 and .NET 10. The final target aggregate exits zero.
Direct XML audit covers 187 FIR suites/2,223 tests, two integration suites/127
tests, one backend resolver suite/three tests, and the unchanged six-test
`dotnet.ir` root: 191 suites/2,359 tests total, with no failures, errors, or
skips.

The source-built Stdlib rehearsal no longer reports
`AbstractMutableList.indexOf`. Its first remaining owner failure is the
independent `AbstractMutableList.removeAll` semantic-body call which requests
exact open `this` as `MutableList<object>`.

## Boundary

This checkpoint does not authorize a source-level invariant generic cast, a
general local type refinement, or a semantic-to-natural conversion. It does
not change warning-bearing `as`/`as?`, Kotlin variance, state selection,
interface admission, visibility, C# authoring, or the atomic production
switch. The next semantic-body self-conversion must be solved from physical
route authority without weakening the source/mutable-local exclusion.
