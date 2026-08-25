# Generic-owner semantic-body exact current-receiver capture

Date: 2026-08-25

## Context

The source-built Stdlib rehearsal had reached the semantic `keys` getter of
`AbstractMutableMap<K, V>`. The getter constructs an anonymous generic view
whose generated constructor captures the getter's current owner. Blanket
semantic-body remapping changed the construction from its exact
`Anonymous<K, V>` to `Anonymous<*, *>`. The constructor consequently expected
`AbstractMutableMap<object, object>`, although its only outer input was the
actual current `AbstractMutableMap<!K, !V>`.

This was not a broad Kotlin value being stored in exact state. It was a local,
non-ABI implementation owner whose construction could exist only relative to
one already-exact receiver.

## Decision

A moved semantic body may preserve an owner-dependent construction when all of
the following are true:

- the constructed class is already classified as a retained non-ABI
  implementation owner and is physically reified by the rehearsal;
- the construction supplies one invariant argument for every class type
  parameter, and every argument is derived from the current generic owner;
- every constructor parameter which mentions the constructed class's own type
  parameters receives the semantic hook's exact current receiver; and
- substituting the construction arguments into that constructor parameter
  produces exactly the receiver argument's invariant type.

Constructor visibility is deliberately not the criterion. An anonymous class
inside a public owner can have a non-private IR constructor while the class
itself still has no nameable Kotlin or CLR ABI. Conversely, public, external,
erased, cached-singleton, projected, partially substituted, mixed-input, and
ordinary ABI owners remain outside the proof.

Only the construction and its exact receiver input retain their physical
types. A broad semantic value written to the surrounding cache remains in the
object/capability domain. The rule creates no wrapper, proxy, copied body,
shadow state, or second object identity, and contains no collection, Map,
property, package, or declaration-name condition.

## Proof and result

`genericOwnerSemanticBodyExactCurrentReceiverCapture.kt` uses a public
two-parameter covariant owner so that the generated constructor has the same
visibility pressure as a public stdlib owner. Its semantic result cache stores
`Iterator<K>`, and its anonymous iterator captures only exact current `this`.
The proof covers value and reference constructions, widened owner views, cache
identity, and a genuinely broad replacement supplied through
`CapturingOwner<Any?, Any?>`.

Four rehearsal and four production-erased inverse lanes pass through PSI and
LightTree on Framework 4.8 and .NET 10. Removing the construction rule restores
the `AbstractMutableMap<object, object>` constructor demand. With the rule in
place, the source-built Stdlib census no longer reports that mismatch.

The next independent root is static-initialization binding for generated
generic subclasses. An anonymous `AbstractSet<K>` implementation has a
synthetic `<StaticInitialization>` owner whose `<EnsureInitialized>` call does
not yet bind to the source-built generic base's companion/static holder. Later
"constructor call of unsupported class" diagnostics in its enclosing getter
are cascading consequences of that earlier class eviction.

The final target aggregate exits zero. Direct XML audit covers 187 FIR
suites/2,235 tests, two integration suites/127 tests, one backend resolver
suite/three tests, and the unchanged six-test `dotnet.ir` root: 191 suites and
2,371 tests total, with no failures, errors, or skips.

## Boundary

This checkpoint does not make semantic values exact, authorize arbitrary
generated constructors, weaken typed receiver authority, or change public
generic-owner ABI. It does not compose the already-proven split nullable
result carrier with Map lookup. BK-1 casts, Kotlin variance, typed-state
admission, ordinary C# authoring, and the atomic production switch remain
unchanged.
