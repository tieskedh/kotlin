# OctoTree state-access capability checkpoint (2026-08-17)

## Scope

This checkpoint extends only the production-inert, schema-12 decoded
OctoTree physical-family product. It does not emit a production generic
Kotlin owner, change a DLL or KLIB contract, or authorize migration.

The product now consumes the recorded capability slots belonging to the
already physicalized typed state-access member families:

- `Leaf<T>` retains one private `T` field and typed `T` read/write methods;
- the non-generic Leaf capability reads and writes through `object`;
- `Branch<T>` retains one private `Node<T>[]` field and typed read method; and
- the non-generic Branch capability exposes the same vector as
  `System.Array`.

Each explicit capability implementation is private, virtual, and final as
required by the CLR interface map. No capability owns state. The Leaf write
casts or unboxes its `object` input and then calls the typed writer; the Leaf
read boxes only when its `T` result crosses the object boundary. The Branch
read widens the same `Node<T>[]` reference to `System.Array` without copying,
wrapping, or changing its runtime element type.

## Record authority

The physicalizer first resolves each state access through its recorded
logical-member key and exact typed MethodDef identity. It then obtains the
capability dispatcher and interface MethodDef from that same member family.
It rejects missing roles, guessed carriers, non-private/non-final dispatchers,
owner mismatches, or a capability signature other than:

```text
Leaf read   : () -> object
Leaf write  : (object) -> void
Branch read : () -> System.Array
```

The generated C# bodies remain bounded scenario oracles. Physical owner,
interface, method, field, type, visibility, dispatch, and signature identities
come from the decoded artifact.

## Direct C# evidence

The separately compiled consumer proves on Framework 4.8 and .NET 10 that:

- a Leaf capability write changes the same true `int` field observed by the
  typed getter;
- the capability read returns the boxed value from that field;
- an incompatible string throws `InvalidCastException` before mutation;
- a Branch capability read returns the exact `Node<int>[]` object observed by
  the typed getter and reflected field; and
- interface maps bind all three new slots to private/virtual/final methods on
  their closed Leaf or Branch implementation TypeDefs.

The existing strict `Node.set` capability, inherited virtual dispatch,
sealed-construction rejection, and hostile semantic fallback remain in the
same combined gate.

## Verification

- focused OctoTree matrix: 8 tests, zero failures/errors/skips;
- combined hostile plus OctoTree matrix: 16 tests, zero
  failures/errors/skips; and
- final warm-cache strict aggregate: 626.2 seconds, 190 XML files and 2,238
  tests, zero failures/errors/skips.

The next bounded product slice is the outer `OctoTree<T>` semantic root and
its actual member/call routes. It must reuse this one Node/Leaf/Branch physical
family and must not introduce a second owner or state representation.
