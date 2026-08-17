# Generic-owner OctoTree ordinary-body closure (2026-08-17)

## Scope

This checkpoint closes the declaration-independent state, private-helper, and
ordinary-body gap in the detached OctoTree candidate. It remains a test-owned,
production-inert product: production Kotlin generic owners, the emitter,
DLL/KLIB, Runtime, Common semantics, and the public C# surface are unchanged.
It does not authorize an owner cutover.

## Schema-13 physical evidence

The compiler snapshot now projects every direct field of a Kotlin generic
owner through the same module-wide producer graph. Generic semantic taint and
typed-write admission remain restricted to owner-parameter-dependent fields;
an ordinary field instead records
`DECLARATION_INDEPENDENT_STORAGE`, its exact non-owner carrier, initializer,
access paths, and init-only flag. Explicit Boolean false and Int zero
initializers have a bounded default-zero recipe, while constructor parameters
retain their positional initializer record.

Private declarations never acquire a logical KLIB member key merely because
the producer is compiled in the same invocation. A real private non-KLIB body
helper is a separate private-final implementation MethodDef on its exact owner;
it cannot become a member family, capability dispatcher, or reflection member.
Private property accessors and fake overrides may remain optimized or inherited.
The codec serializes these facts as schema 13 and rejects implementation/logical
MethodDef collisions as part of the whole-family closure.

For the representative family this records:

- `Tree.depth`: final `int`, initialized from constructor parameter zero;
- `Tree.actual`: mutable `bool`, explicitly default-zero initialized and also
  written transitively by `Tree.set`;
- `Branch.nodes`: final `Node<T>[]`;
- `Leaf.value`: mutable `!T`; and
- `Tree.canClusterize`: one private-final implementation MethodDef, absent from
  capability and logical reflection views.

## Complete executable product

The record-driven producer no longer uses bounded scenario substitutes. It
contains the complete recursive `Tree.get`, `Tree.set`, `Branch.set`, and
private `canClusterize` algorithms. `Tree.set` writes the real `actual` field,
the coordinate helper is an internal static ordinary MethodDef, and the Leaf
failure uses the target mapping `System.NotSupportedException`.

The separately compiled C# consumer reflects the real `depth` and `actual`
fields, forces `actual` true and proves a subsequent set resets it, executes an
8 x 8 x 8 checkerboard write/read corpus with 512 successful reads, and fills
the eight unique depth-one coordinates to prove Branch-to-Leaf clustering.
The existing typed, capability, inheritance, incompatible-input, and raw
metadata checks remain active on Framework 4.8 and .NET 10.

The exhaustive family metadata expectation is eight TypeDefs, four
GenericParams, four InterfaceImpl rows, eight MethodImpl rows, five fields, and
37 MethodDefs. The assembly additionally contains only `<Module>` and, for the
Framework product, the two exact Roslyn support attributes.

## Verification

- Focused PSI/LightTree x Framework 4.8/.NET 10 same/separate matrix: 8 tests,
  zero failures, errors, or skips.
- Combined hostile-plus-OctoTree matrix: 16 tests, zero failures, errors, or
  skips.
- Final coherent post-restart strict aggregate: 1,895.0 seconds; direct audit
  covers 190 XML files and 2,238 tests with zero failures, errors, or skips.

## Next gate

Measure the complete record-driven OctoTree candidate against its erased
production counterpart on Framework 4.8 and .NET 10 JIT, ReadyToRun, trimmed,
and NativeAOT lanes. Attribute costs to typed state, semantic capability
crossings, compatibility checks, and ordinary algorithms; one aggregate ratio
cannot decide the representation. A separate broad bug/performance audit is
worth doing only if it identifies a material architectural win.
