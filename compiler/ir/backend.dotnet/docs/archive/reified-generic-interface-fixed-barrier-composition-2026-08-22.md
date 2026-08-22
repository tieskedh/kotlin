# Reified generic-interface fixed-barrier composition

Date: 2026-08-22

## Question

Can the general natural/exact/semantic interface family carry an
upstream-defined fixed candidate barrier in the same physical product as its
constructed result, nested semantic input, method queries, read-only property,
and producer-proven typed state?

The proof extends the existing hostile `ExactInputFamily<out T>` with
`Collection<T>` and its `contains(T)` override. The Runtime `Collection`
declaration is still in the production-erased epoch; it is used here only as
the authoritative source of Kotlin's existing special-bridge policy. This
checkpoint does not migrate the Runtime or Stdlib collection TypeDefs.

## Structural admission and ABI 48

An admitted family may now have one canonical-only Runtime generic-interface
parent in addition to at least one admitted reified-family parent. The Runtime
parent is accepted only when all of these facts hold:

- its type argument is the child's identical owner parameter;
- the child is covariant;
- one directly declared broad fixed-barrier member resolves through
  `SpecialBridgeMethods` to exactly that Runtime parent; and
- the ordinary Runtime TypeDef exposes its inherited CLR/C# obligations.

The Runtime parent remains authoritative in KLIB and ordinary CLR
`InterfaceImpl` metadata. It is not copied into the producer's reified-family
ancestry record. A separate consumer revalidates both the KLIB edge and the
exact upstream provider; a published member-role bit alone is insufficient.
There is no `Collection`, package, or declaration-name admission switch.

ABI/runtime surface 48 records that interpretation and the new runtime helper.
Older products reject the changed surface before attempting to consume it.

## Physical and semantic routes

The ordinary typed route remains the default:

```text
ExactInputValue<T>.current        -> !T field
exact contains(T)                 -> bool contains(!T)
ordinary Kotlin/C# exact call     -> direct typed dispatch
```

The object domain is used only by the widened semantic boundary. A
Kotlin-owned implementation checks the upstream special-bridge type before
the capability dispatcher narrows `object` to `T`. An incompatible candidate
therefore returns the upstream `false` result instead of failing during the
cast. The class still owns one `!T` field; no object field, shadow state, or
global owner erasure is introduced.

The C# implementation manifest carries the same policy explicitly: one erased
`object -> bool` slot, one exact `!T -> bool` slot, and a one-argument
`FALSE` wrong-shape rule. The supported source generator emits the check
before invoking the ordinary typed C# method.

A separately compiled, sealed, non-partial C# implementation uses no source
generator and names no Kotlin semantic or exact interface. For a widened call,
the runtime first selects exactly one natural CLR construction, resolves the
ordinary concrete `contains` method whose parameter is that construction's
first type argument, and caches that `MethodInfo` under a distinct resolver
kind. A wrong candidate returns `false` before invoking the C# method; a
compatible candidate invokes it exactly once. Multiple constructions remain
ambiguous and a missing compatible typed method still fails closed.

## Verification

The fixed-barrier family is green under PSI and LightTree on Framework 4.8 and
.NET 10. The proof covers:

- Kotlin-owned reference and `Int` constructions;
- compatible and incompatible widened calls;
- the natural interface's real Runtime `Collection` parent;
- manifest slot signatures and the upstream `FALSE` policy;
- generated partial C# implementations;
- an ordinary precompiled/non-partial C# implementation; and
- exact receiver identity plus a call counter proving the wrong candidate did
  not enter the typed C# body.

The prior ordinary C# foreign-override rehearsal is also green in all four
lanes. The complete `dotNetTest` aggregate exits zero. Direct XML audit reports
190 freshly written suites and 2,287 tests with zero failures, errors, or
skips. Together with the unchanged six-test `dotnet.ir` root, the target gate
is 191 suites and 2,293 tests, all green.

## Boundary and next gate

This closes the optional fixed-barrier member in the general emitted family.
It does not reify the Runtime-owned `Collection<T>` or Stdlib-owned collection
graph, and it does not generalize the raw fallback to arbitrary special-method
policies or overload sets. Nullable owner substitutions and null-candidate
behavior remain an explicit later proof rather than an implication of the
reference/value cases above.

The next bounded feature is the Runtime/Stdlib collection migration itself:
derive the smallest atomic `Collection<T>`/`Set<T>` producer graph which can
use this general family, preserve Common Kotlin semantics and ordinary C#
authoring, and keep typed CLR fields/calls as the normal route. Defaults,
mutable collections, maps, multiple parameters, and general overload sets
remain later gates unless that atomic graph proves they are inseparable.
