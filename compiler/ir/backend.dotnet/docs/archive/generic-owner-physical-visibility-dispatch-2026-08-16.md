# Generic-owner physical visibility and dispatch

## Scope

Generic-owner physical-family schema 10 makes the decoded artifact sufficient
to select the CLR visibility and dispatch shape of owner TypeDefs and member
MethodDefs. A later record-driven producer must not recover these properties
from compiler-local snapshots or infer them from member roles.

Production generic owners remain erased. The artifact is test-owned and is not
embedded in today's DLL or KLIB.

## Owner declaration envelope

Every physical family now records the compiler-derived owner visibility and
dispatch independently of its candidate disposition and runtime-classification
mode:

- `PUBLIC` or `NOT_PUBLIC`; and
- `FINAL`, `OVERRIDABLE`, `ABSTRACT`, or `SEALED`.

This is required by the recursive OctoTree family in particular. Its sealed
abstract `Node<T>` cannot be reconstructed truthfully from arity, constructor
edges, or the fact that `Branch<T>` and `Leaf<T>` inherit it. `SEALED` is the
producer's Kotlin-to-CLR dispatch policy input, not an instruction to set the
CLR sealed bit on an abstract base which still has recorded derived TypeDefs.

## Member slots

Every physical member slot now carries exact CLR visibility. The producer
derives the typed entry from the Kotlin declaration visibility, while physical
semantic hooks are protected and explicit capability dispatchers are private.
The record validates the latter two rules directly:

- `SEMANTIC_HOOK` requires `FAMILY`; and
- `CAPABILITY_DISPATCHER` requires `PRIVATE` plus final dispatch.

The capability interface MethodDef remains a separate exact identity. Its
private implementation MethodDef is not confused with the public logical
member or the interface slot.

## Codec and evidence

Schema 10 expands the owner and member-slot records with the new enum values.
Canonical encode/decode retains the complete declaration envelope, and schema
9 is stale. Opposing oracles reject a public semantic hook and a public
capability dispatcher. The existing source-label permutation still yields the
same physical artifact, so diagnostic names do not acquire ABI authority.

The focused PSI/LightTree x Framework 4.8/.NET 10 same/separate-compilation
matrix covered eight tests with zero failures, errors, or skips.
The final strict aggregate completed in 1,860.0 seconds and directly audited
190 XML files and 2,238 tests with zero failures, errors, or skips.

## Next gate

The complete recursive OctoTree artifact can now carry the public open tree,
sealed abstract node, final leaves/branches, non-public implementation members,
and role-specific helper visibility without consulting the live prototypes.
It must next bind all four owners, recursive field/signature types, constructor
edges, private root access, and the fixed `Node<T>[8]` initializer atomically.
