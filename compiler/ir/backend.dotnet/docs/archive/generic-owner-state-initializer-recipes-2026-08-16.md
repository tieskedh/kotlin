# Generic-owner state initializer recipes

## Scope

The production-inert CLR-generic owner planner now records whether each
explicit owner-dependent field initializer belongs to a bounded physical
recipe grammar. This closes the first executable-state gap found while
inventorying the unchanged recursive OctoTree application.

No production owner, field, constructor, MethodDef, DLL/KLIB schema, Runtime
surface, or Common behavior changes in this tranche.

## Compiler evidence

Each owner-dependent state snapshot retains every explicit field initializer
as one of:

- `FIXED_ZEROED_SZ_ARRAY`, with its exact non-negative element count; or
- `UNSUPPORTED`, which is an explicit fail-closed result rather than absence
  of evidence.

The admitted vector recipe requires the existing typed-write proof: the call
must be `arrayOfNulls`, its invariant element must be a local Kotlin generic
classifier which structurally retains the current owner's parameter, and its
length must be an exact integer constant. The recipe is separate from the
field's path-unbound carrier. A later physical-family builder must bind that
carrier by logical classifier key before it can emit the initializer.

The analyzer shares the same structural predicate with typed-write
provenance. It therefore cannot classify an unchecked object vector merely
because a later cast gives it the logical Kotlin type `Array<T?>`.
The recipe is also downgraded to `UNSUPPORTED` when a same-compilation nested
classifier has no stable producer key and therefore no bindable structural
state carrier.

## Real-application boundary

For Kotlin/Native's unchanged separately published OctoTree source,
`Branch.nodes` records both:

- path-unbound state `Node<T>[]`; and
- one fixed zeroed vector initializer of length eight.

For the unchanged ArrayCopy benchmark, the expression
`arrayOfNulls<Any>(capacity) as Array<T?>` records `UNSUPPORTED`, has no exact
typed carrier, and remains semantic-object state. This opposing oracle guards
the erased-vector mistake that would otherwise make the OctoTree evidence look
more general than it is.

## Remaining boundary

This recipe does not yet serialize into the physical-family artifact and does
not describe arbitrary constructor statements. OctoTree's secondary Branch
constructor still contains a real loop, and producer-private state accessors
still lack a physical-family identity. Those obligations must be represented
or deliberately kept as producer-private executable details before the full
record-driven candidate can be claimed.
