# Complete generic-owner OctoTree physical family — 2026-08-17

## Scope

Generic-owner physical-family schema 11 can now describe the complete
recursive family selected from Kotlin/Native's unchanged OctoTree source. The
artifact remains production-inert: no production Kotlin owner, emitted field,
DLL/KLIB metadata, Runtime surface, Common behavior, or public C# surface is
changed by this checkpoint.

The selected family is atomic and keyed only by compiler-owned logical
declaration identities:

- `OctoTree<T>`;
- sealed `OctoTree.Node<T>`;
- `OctoTree.Node.Branch<T>`; and
- `OctoTree.Node.Leaf<T>`.

Diagnostic owner/member labels do not select TypeDefs, MethodDefs, constructor
edges, state, or reflection identity. Relabeling every selected source member
produces an equal artifact.

## Complete physical graph

Each owner records its exact TypeDef visibility, dispatch category,
GenericParam constraints, constructors, `this`/`base` edges, member-family
roles and signatures, direct-super targets, optional capability TypeDef,
private fields, state access paths, and normalized reflection family.

The state choices are deliberately mixed:

- `OctoTree.root` remains one private `object` field because widened semantic
  writes are reachable. Its compiler-only typed identity read/write methods
  are private, carry no invented Kotlin callable identity, and are hidden from
  reflection.
- `Branch.nodes` is the exact recursive `Node<T>[]` carrier. Its fixed zeroed
  length-eight initializer is attached to the exact base-delegating
  constructor roots and its logical getter reads that same physical vector.
- `Leaf.value` is true `!T` state. Schema 11 records not only its typed read and
  write paths but also the exact logical constructor and parameter index whose
  value initializes the field without conversion.

The last point closes an important false-positive risk: selecting a `!T` field
without recording how its initial value reaches the field would obtain the
cost of a new representation without proving executable initialization.

## Cross-compilation findings

The separate KLIB producer exposed two differences which a
same-compilation-only fixture did not show.

First, imported `kotlin.Any.<init>` has a stable logical key. Constructor
binding now classifies the actual core owner before consulting the selected
producer constructor map, so the edge binds to `System.Object::.ctor` rather
than being mistaken for a missing producer MethodDef. Non-core bases still
require an exact selected logical constructor.

Second, KLIB inlining retained explicit field initializers for `root = null`
and constructor property `Leaf(var value: T)`. The planner now distinguishes:

- a proven default-null reference, which emits no redundant physical recipe
  because CLR object fields are already null before constructor execution;
- a fixed zeroed SZ-array; and
- an exact logical constructor plus positional parameter copied into state.

Unknown initializers remain `UNSUPPORTED`. A positional initializer without a
stable constructor key, with a missing/out-of-range parameter, or whose
parameter carrier disagrees with the field carrier fails closed.

## Atomic closure

The top-level artifact now validates every producer-scoped physical type
recursively. Each named producer type must resolve to a recorded owner TypeDef
with exact generic arity and class category. Every constructor/member/
reflection/state MethodDef owner must likewise resolve to a recorded owner or
recorded capability TypeDef.

Removing `Node<T>`, replacing `Branch.nodes` with a phantom producer TypeDef,
or changing the `Leaf.value` constructor parameter rejects the whole artifact.
There is no partially usable recursive family.

## Evidence

The focused hostile-plus-OctoTree matrix executes same and separate
compilation through PSI and LightTree on Framework 4.8 and .NET 10: four XML
suites, 16 tests, and zero failures, errors, or skips. It retains the existing
hostile C# physicalizer while adding canonical schema-11 round-trip,
declaration relabeling, constructor/state, recursive TypeDef, and malformed
family checks.

The final strict aggregate result is recorded in `STATUS.md` and the commit
which archives this checkpoint.

## Remaining boundary

This artifact is sufficient input for the next bounded product, but it is not
that product. Next, build the record-driven OctoTree CLR-generic candidate and
direct C# consumer/subclass products from the decoded family. Those products
must compile and execute independently on Framework 4.8 and .NET 10 and must
not derive signatures, visibility, constructor access, or sealed hierarchy
rules from source names.

Only after that product is green should representative allocation/dispatch
measurement decide where semantic capability crossings can be removed. It
does not authorize an easy-owner production migration or a mixed public owner
ABI.
