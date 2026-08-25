# Generic-owner semantic-body exact result chain

Date: 2026-08-25

## Context

The source-built Stdlib rehearsal had reached `AbstractMutableMap.remove`.
Its broad key candidate correctly requires an object-domain semantic hook,
but the Common body obtains each entry only through parameterless producers
on the hook's exact current owner:

```text
exact this
  -> entryIterator()
  -> next()
  -> entry.key / entry.value
```

Blanket semantic-body remapping changed those independently exact results to
`MutableMap.MutableEntry<object, object>`. The later key read then could not
supply the logical `Map.Entry<K, V>` receiver. This was a downstream loss of
an already-known physical construction, not evidence that the broad candidate
or all entry state should become typed.

After preserving the exact result chain, the compiler exposed a second
independent omission. Runtime IL already declares
`MutableMap.MutableEntry<K, V> : Map.Entry<K, V>`, but the compiler's internal
CLR Runtime type graph did not record that declared-generic edge. Consequently
code generation could see the correct `MutableEntry<!K, !V>` value but could
not recover its existing `Map.Entry<!K, !V>` base view.

## Decision

A semantic hook may preserve the natural CLR construction of a result chain
when every step is a parameterless producer on an exact receiver. The proof
starts at the hook's physical current `this`, follows only calls with no
non-dispatch parameters and no `super` target, and propagates through
immutable locals whose declared and initializer types are invariantly equal.
Calls which require a semantic result route remain excluded.

Runtime generic interfaces which already select their declared view in the
rehearsal may continue the same proof through a parameterless producer, but a
nested reified variant-owner result remains semantic. Mutable locals,
source-level widened views, input-bearing calls, and arbitrary semantic values
do not acquire exact provenance.

The compiler's Runtime type graph now mirrors the already-emitted
`MutableEntry<K, V> : Map.Entry<K, V>` edge. This changes no Runtime TypeDef or
public ABI; it makes the backend's physical subtype model truthful to the
existing artifact.

Neither rule mentions Map, MutableMap, Entry, a property name, a package, or
the Stdlib. No wrapper, proxy, copied body, shadow state, or second identity is
introduced.

## Proof and result

`genericOwnerSemanticBodyExactResultChain.kt` defines a covariant two-parameter
owner whose broad `remove` operation accepts `Any?` after widening while its
entry iterator, entry, key, and value are produced solely from exact current
state. It covers value and reference substitutions, wrong and matching broad
candidates, result values, mutation, and receiver identity.

Ablating the result-chain rule leaves
`MutableMap.MutableEntry<object, object>` at the property receiver. Restoring
that rule alone produces the correct `MutableMap.MutableEntry<!K, !V>` and
then exposes the missing Runtime base edge. With both corrections, four
rehearsal and four production-erased inverse lanes pass through PSI and
LightTree on Framework 4.8 and .NET 10.

The final target aggregate exits zero. Direct XML audit covers 187 FIR
suites/2,231 tests, two integration suites/127 tests, one backend resolver
suite/three tests, and the unchanged six-test `dotnet.ir` root: 191 suites and
2,367 tests total, with no failures, errors, or skips.

The source-built Stdlib rehearsal no longer reports
`AbstractMutableMap.remove`. Its first remaining owner failure is the
independent `AbstractMutableMap.get_keys` semantic getter: construction of its
anonymous view object still expects `AbstractMutableMap<object, object>` while
the hook correctly retains exact current `AbstractMutableMap<!K, !V>`.

## Boundary

This checkpoint does not turn arbitrary semantic interface values into
natural CLR constructions. It does not authorize input-bearing transitions,
mutable/source-local narrowing, nested variant result materialization, or
global object-state removal. BK-1 casts, Kotlin variance, typed-state
admission, ordinary C# authoring, and the atomic production switch remain
unchanged.
