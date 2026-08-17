# OctoTree root product checkpoint (2026-08-17)

## Scope

This checkpoint completes the production-inert schema-12 OctoTree candidate
from its decoded physical-family artifact. It adds the outer `OctoTree<T>`
product to the already executable Node/Leaf/Branch family. It changes no
production TypeDef, DLL/KLIB schema, Runtime/Stdlib contract, Common semantic
path, or public C# export.

The decoded record is authoritative for the outer TypeDef, GenericParam,
public constructor, semantic root field, hidden root read/write methods,
public typed members, non-generic capability interface, explicit dispatchers,
visibility, dispatch, and all signatures. In particular:

```text
root field       : object
root read/write  : private object identity access
get              : (int, int, int) -> object
set typed        : (int, int, int, !T) -> void
set capability   : (int, int, int, object) -> void
```

The `object` root is deliberate. `Node<T>?` participates in semantic state and
cannot become a `Node<T>` field merely because the candidate owns exact child
TypeDefs. `get(): T?` also retains `object`, which truthfully represents both
nullable reference substitutions and boxed-or-null value substitutions.

## Actual call-route join

The physicalizer now consumes the compiler-derived call-route census in
addition to the physical-family artifact. It requires the exact logical
caller/callee pairs for:

- `OctoTree.get` to Leaf value read;
- `OctoTree.get` to Branch nodes read; and
- `OctoTree.set` to `Node.set`.

All three must be `SEMANTIC_CAPABILITY`. The bounded C# body therefore walks
the object root through the recorded non-generic Leaf/Branch interfaces and
invokes `Node.set` through its recorded capability. It never casts erased
root state back to a constructed `Node<T>` merely to recover a typed call.

The outer set capability casts or unboxes its own object argument before it
enters the typed Tree member. An incompatible argument consequently fails
before root initialization or child mutation. A compatible typed value may
box again when the typed Tree body crosses the independently required Node
capability; that crossing is real architecture evidence, not a candidate
optimization.

## Scenario-body boundary

The generated C# algorithms remain bounded scenario oracles. The generic-
owner artifact deliberately catalogs owner-dependent state; ordinary
declaration-independent backing state is still owned by the normal class
emitter. The test product therefore uses a clearly named private
`__scenarioDepth` field to execute the constructor/getter body and a private
`ScenarioNumber` helper for the unchanged coordinate calculation. Neither is
reflected, asserted, serialized, or described as generic-owner ABI evidence.

This is not permission for production emission to invent missing fields or
helpers. Atomic migration must compose the accepted generic-owner model with
the ordinary declaration/body emitter and prove the complete emitted class.

## Direct C# evidence

Separately compiled Framework 4.8 and .NET 10 consumers prove that:

- open and closed `OctoTree<T>.root` are both physically `object` and start
  null;
- the recorded hidden root accessors are private with exact object signatures;
- the public constructor/depth/get/set/ToString MethodDefs have their recorded
  shapes;
- typed set/get and capability set/get observe one Branch/Leaf object graph;
- incompatible capability input throws before root or child mutation;
- explicit get/set interface targets are private/virtual/final;
- the Tree remains externally subclassable; and
- an external subclass inherits the same base-declared capability dispatchers
  rather than receiving duplicate MethodDefs.

The child product simultaneously retains true `Leaf<int>` and
`Node<int>[]` state. The outer semantic root therefore coexists with, rather
than erases, the proven typed islands.

## Verification

- focused OctoTree matrix: 8 tests, zero failures/errors/skips;
- combined hostile plus OctoTree matrix: 16 tests, zero
  failures/errors/skips; and
- final warm-cache strict aggregate: 660.2 seconds, 190 XML files and 2,238
  tests, zero failures/errors/skips.

The next bounded gate should close whole-family metadata and reflection
normalization: one Kotlin classifier/callable view, hidden capability members,
exact TypeDef/GenericParam/InterfaceImpl/MethodImpl/field/method rows, and
separate-product identity. It must retain this one object graph and cannot
replace executable body evidence with metadata-only assertions.
