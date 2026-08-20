# Generic-owner capability superinterface closure

Date: 2026-08-20

## Outcome

ABI 42 records the complete non-generic physical interface closure implemented
by every producer-selected generic-class capability. This makes universal
assignability a stable producer declaration fact rather than a relationship a
separate consumer attempts to reconstruct from logical KLIB after lowering.

The rule is structural and contains no collection or `Iterator` exception:

- a local reified Kotlin interface contributes its semantic capability;
- an external reified Kotlin interface contributes its producer capability;
- a Kotlin generic interface which retains one erased physical identity
  contributes that identity after an `Any?` substitution of owner parameters;
- an ordinary non-generic interface contributes itself; and
- a constructed imported CLR generic interface contributes nothing because
  no one construction is valid for every `C<T>`.

The producer flattens the resulting non-generic interface graph into sorted,
assembly-qualified TypeDef identities. The local emitted graph retains its
natural direct edges; flattening exists in the ABI because separately rebuilt
physical references do not carry the producer's linked transitive graph.

## Fail-first boundary

The local hostile proof adds a covariant real generic class implementing
`Iterator<T>`, widens its `Int` construction to the logical `Any?` view, and
passes it to an ordinary Kotlin `for`-loop. `ForLoopsLowering` creates the
erased `Iterator.hasNext` and `Iterator.next` calls after generic-owner
materialization.

Before the closure rule, all four PSI/LightTree and Framework 4.8/.NET 10 local
lanes failed during IL validation: the non-generic class capability had no
reference upcast to `Kotlin.Collections.Iterator`. After the local rule, all
four passed. Exported IL proved one real `C<int>` with a `!T` value field, one
non-generic semantic capability implementing `Iterator`, and ordinary erased
Iterator loop locals and calls. No `C<object>` fallback was emitted.

The same class was then moved to a producer library while the loop remained in
a separate consumer. All four separate lanes failed at the same missing
capability-to-Iterator upcast. This showed that the consumer could not soundly
recover the physical relationship from logical KLIB alone and selected the
ABI 42 publication change. With the producer record consumed, all four lanes
pass.

## Physical ABI

Each generic-owner Class record now includes zero or more assembly-qualified
capability-superinterface TypeDefs. The codec validates counts and path bounds,
requires a sorted unique list, rejects capability self-inheritance, rejects
supertypes without a capability, and consumes the complete payload. External
declarations populate capability assignability only from this record; the old
consumer-side generic-interface reconstruction was removed.

The runtime-surface and physical ABI levels move together from 41 to 42 so an
old compiler cannot silently consume a producer whose capability inheritance
contract it does not understand.

## Evidence

The enabled rehearsal passes the eight focused direct/separate lanes across
PSI, LightTree, .NET Framework 4.8, and .NET 10. The epoch-off inverse passes
the same eight lanes using the erased production ABI. Both matrices have zero
failures, errors, or skips. The focused codec round trip and backend model
suite also pass.

The final normal production aggregate directly audits 190 XML suites and
2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests,
two integration suites/126 tests, and one `dotnet.ir` suite/six tests.

## Remaining boundary

This closes one stable early declaration relationship, not the general late
router. A body-producing Common lowering can now use an ordinary universal
superinterface without consulting source-time `IrCall` identity. Operations
whose exact/capability route still depends on receiver or value provenance
must be classified by a final idempotent call/value router after all such
body-producing lowerings. Broader family admission and the atomic Runtime/
Stdlib migration also remain fail-closed.
