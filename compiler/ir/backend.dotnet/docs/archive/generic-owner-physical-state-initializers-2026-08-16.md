# Generic-owner physical state initializers

## Scope

Generic-owner physical-family schema 8 serializes the first bounded
producer-owned state initializer. A typed fixed zeroed SZ-array can now satisfy
the physical state's write obligation without inventing a setter MethodDef.

Production generic owners remain erased. The artifact is still test-owned and
is not embedded in today's DLL or KLIB.

## Record

`DotNetGenericOwnerPhysicalStateInitializerRecord` contains:

- `FIXED_ZEROED_SZ_ARRAY`;
- the exact non-negative element count; and
- every logical base-delegating constructor root which executes the
  initializer.

Constructor roots must exist in the same physical family and must have an
exact `BASE` delegation edge. A missing root or a `THIS`-delegating root rejects
the family. The stable delegation graph is authoritative; transient
post-lowering `IrInstanceInitializerCall` nodes are not required.

For `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`, member access paths and initializer
paths jointly cover READ and WRITE. Every member path remains typed identity.
An initializer is admitted only for exact owner-dependent SZ-array storage.
Semantic-object state cannot carry this typed initializer recipe.

## Codec

Schema 8 adds a counted initializer block to every state record. Canonical
encoding sorts recipes and retains sorted constructor keys. The codec rejects
schema 7 as stale, truncated initializer blocks, negative lengths, duplicate
recipes, incomplete access/init operation coverage, non-vector storage, and
constructor roots outside the recorded family.

A validated synthetic family changes one hostile typed state from `T` to
`T[]`, replaces its ordinary write path with an eight-element initializer,
updates the exact typed read MethodDef and reflection family, and proves
canonical encode/decode plus exact initializer preservation. This exercises
the positive codec path before the complete OctoTree family is selected.

## OctoTree join

The separately compiled OctoTree prototype already records `Branch.nodes` as
path-unbound `Node<T>[]` with a fixed length of eight. Its constructor graph
contains one base-delegating root and one `this`-delegating secondary
constructor. The next tranche can therefore bind that existing proof directly
to the schema-8 state initializer; it must not derive either the TypeDef or
constructor from display names.
