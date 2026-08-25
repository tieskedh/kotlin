# Generic-owner semantic-body exact helper

Date: 2026-08-25

## Context

The source-built Stdlib rehearsal had reached
`AbstractMutableList.removeAll`. Its relative generic-interface input gives
the member an object-domain semantic hook, while the moved Common body invokes
the generic extension helper:

```text
MutableList<T>.removeAll(predicate: (T) -> Boolean)
```

The hook still executes on one actual `AbstractMutableList<!T>`. The previous
blanket body remapping nevertheless changed the helper method argument to
`object`, which demanded the unrelated invariant construction
`MutableList<object>` from exact `this`.

Preserving only the call exposed a second composition bug. The lifted
predicate class correctly had semantic object storage for its captured broad
`Collection<T>` view, but an otherwise unused generic-owner class capability
also projected its exact callable superinterface from
`ExactFunction1<T, Boolean>` to `ExactFunction1<object, Boolean>`. One TypeDef
then had two incompatible `InvokeExact` obligations and failed CLR type
loading.

## Decision

A generic extension helper may retain owner-dependent method arguments from a
semantic hook's exact current receiver only when all of these conditions hold:

- the call has a real extension receiver;
- that receiver is the hook's exact current `this`;
- the preserved method argument references the current generic owner; and
- every occurrence outside the receiver is output-only under declaration and
  use-site variance.

Thus `(T) -> Boolean` is legal: the helper supplies exact elements to the
callback. A direct `T`, `Collection<T>`, invariant `C<T>`, or mixed input stays
semantic. A widened candidate can never enter `!T` merely because the same
call also has exact `this`.

Compiler-generated lambda and function-reference implementation classes use
their ordinary erased `FunctionN` identity and optional exact
`ExactFunctionN` interface. Their private implementation class receives a
generic-owner semantic capability only when call-route analysis proves a
semantic call which addresses that class itself. The callable class is not a
second source-level Kotlin classifier. Removing an unused capability avoids
the conflicting exact interface construction while leaving its captured
semantic object field, erased invocation, construction-relative exact
interface, identity, and body unchanged.

Neither rule mentions `AbstractMutableList`, `MutableList`, collections,
`removeAll`, a package, or a declaration name. No wrapper, proxy, shadow
state, duplicated body, visibility widening, or public ABI is introduced.

## Proof and result

`genericOwnerSemanticBodyExactReceiverHelper.kt` supplies the executable
structural proof. A one-element mutable-list implementation invokes a local
generic output-helper with captured collection state for both `Int` and
`String`. It exercises removal and retention while the callback receives the
elements read through the owner's exact natural list construction. A separate
covariant iterator method passes a
direct widened candidate, proving that input-polarity exclusion still routes
that value semantically and preserves identity.

Ablation of the exact-helper rule routes the call through a false
`MutableList<object>` view and fails execution. Restoring the unneeded
callable-class capability reproduces the CLR `InvokeExact` type-load failure.
Four rehearsal and four production-erased inverse lanes pass through PSI and
LightTree on Framework 4.8 and .NET 10.

The final target aggregate exits zero. Direct XML audit covers 187 FIR
suites/2,227 tests, two integration suites/127 tests, one backend resolver
suite/three tests, and the unchanged six-test `dotnet.ir` root: 191 suites and
2,363 tests total, with no failures, errors, or skips.

The source-built Stdlib rehearsal no longer reports
`AbstractMutableList.removeAll` or `retainAll`. Its first remaining owner
failure is `AbstractMutableMap.remove`: a semantic
`MutableMap.MutableEntry<object, object>` value cannot yet satisfy the logical
`Map.Entry` key-property receiver without selecting the correct semantic
property view.

## Boundary

This checkpoint does not turn arbitrary semantic values into natural CLR
generic constructions. It does not authorize input-bearing helpers, source or
mutable local narrowing, global callable erasure, or removal of a capability
which an analyzed private-class route actually requires. BK-1 casts, Kotlin
variance, generic state selection, Runtime interface admission, ordinary C#
authoring, and the atomic production switch remain unchanged.
