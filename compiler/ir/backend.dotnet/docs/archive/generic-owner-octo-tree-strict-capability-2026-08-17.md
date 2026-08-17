# Generic-owner OctoTree strict capability — 2026-08-17

## Scope

This checkpoint completes the strict `Node.set` member family in the schema-12
record-driven OctoTree candidate. The typed virtual family remains unchanged;
the product now also emits the recorded non-generic capability interface slots
and private-final explicit dispatchers. Production emission and the accepted
erased ABI remain unchanged.

## Physical capability family

Node, Leaf, and Branch each record a producer-selected non-generic capability
TypeDef and one exact `set` interface MethodDef. The signature preserves the
four declaration-independent positions and widens only the strict owner input:

```text
bool setCapability(int, int, int, object, int)
```

For each owner, the physicalizer requires:

- the dispatcher MethodDef owner is that exact generic TypeDef;
- visibility is private and dispatch is final;
- the capability interface owner/name/signature is exactly the recorded slot;
- no owner GenericParam remains in the interface signature; and
- the recorded explicit MethodDef identity is the fully qualified interface
  slot, not a reconstructed Kotlin/member name.

The generated explicit implementation casts or unboxes the object position to
the typed slot's recorded `!T`, then invokes that owner's virtual typed entry.
This is the correct direction for a strict input: an incompatible value fails
at the real typed-use barrier, while a compatible value still observes the
most-derived override.

## Direct C# execution

A separately compiled C# consumer proves four distinct routes:

1. `IOctoTreeNodeSemantic` on a Branch instance accepts boxed `int`, invokes
   the inherited Node dispatcher, reaches Branch's virtual typed override, and
   mutates the same true `Node<int>[]` state.
2. Branch's owner-specific capability performs the same mutation through its
   own private-final dispatcher.
3. An incompatible string through the Node capability throws
   `InvalidCastException` before the Branch vector changes.
4. Leaf's capability accepts the boxed int conversion and then observes Leaf's
   most-derived throwing typed body.

`GetInterfaceMap` additionally proves that the Node and Branch target methods
are private, virtual, and final. The inherited Node target is declared on
`Node<int>` while the Branch-specific target is declared on `Branch<int>`.

The external illegal Node subclass implements the now-real abstract typed slot,
so its expected compile failure continues to isolate only the
`FamilyAndAssembly` constructor boundary.

## Evidence

- FIR test-fixture compilation: green;
- focused OctoTree PSI/LightTree × Framework 4.8/.NET 10 × same/separate
  compilation: four XML suites, eight tests, zero failures, errors, or skips;
- combined hostile plus OctoTree matrix: four XML suites, 16 tests, zero
  failures, errors, or skips;
- whitespace/error audit: green;
- warm-cache strict aggregate: green in 641.5 seconds;
- direct aggregate audit: 190 XML suites, 2,238 tests, zero failures, errors,
  or skips.

## Remaining boundary

The strict `set` family is complete, but the Leaf value and Branch vector
accessor families still have only their typed entries in the product. Their
recorded object/System.Array capability slots and explicit dispatchers are the
next bounded slice. Broad semantic hooks and the outer Tree's private semantic
root remain later requirements; a strict cast/unbox dispatcher must never be
used to implement those widened Kotlin contracts.
