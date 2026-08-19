# Reified generic-interface external child (2026-08-19)

## Result

The test-only generic-owner rehearsal now preserves the first admitted
CLR-generic interface family across a producer boundary. Assembly A declares a
structurally admitted `Producer<out T>` and its one non-generic
declaration-semantic capability. Assembly B may declare the transparent
member-free `Child<out T> : Producer<T>`. Assembly C consumes exact and widened
views of implementations from B.

The physical result is one natural covariant `Producer<T>`, one natural
covariant `Child<T>`, and the original capability in A. B does not erase the
child, emit a second capability, publish a local semantic alias, or introduce
state. This is a general compiler rule based on the interface graph; it has no
Map, Set, Sequence, package, or declaration-name branch.

Production remains on the accepted erased generic-interface ABI.

## Producer-qualified semantic identity

The prior physical ABI recorded a generic owner's capability TypeDef path but
implicitly resolved that path in the logical owner's own assembly. That was
true only while a reified family stayed inside one product. For B's child, the
logical owner is in B while the inherited semantic carrier is still in A.

ABI 38 therefore records both:

```text
capability assembly: A
capability owner path: IProducerKotlinSemantic...
```

The consumer reconstructs the physical capability from that complete identity.
The child fixpoint retains the external root as evidence only; it never
synthesizes a local IR capability. Library selection also verifies that every
recorded capability assembly is present in the selected self-describing graph,
so omitting A fails before IL emission or runtime loading.

## Kotlin and C# dispatch

Exact Kotlin child calls use `Child<T>`/the inherited natural `Producer<T>`
slot. Stars, projections, open arguments, and widened value-type views use A's
non-generic capability on the same object. The emitted consumer IL names
`[A]IProducerKotlinSemantic...`; it contains no cast to a fabricated
`Producer<object>`.

The public C# implementation manifests retain B's natural `Child<T>` as the
declared source contract and A's capability as the canonical compiler ABI.
Because the admitted child adds no members, its inherited root contract owns
the generated semantic forwarding slots. Direct partial C# implementations of
both root and child author only the natural `produce()` member. Kotlin widened
dispatch reaches those bodies without asking C# source to name the capability.

## Evidence

The separate-compilation corpus now places the root in module `lib`, the child
and Kotlin implementations in module `middle(lib)`, and exact/widened consumers
in module `main(middle)`. The emitted IL proves:

- `RehearsalSeparateChildProducer` has CLR arity one and covariant `T`;
- its sole interface edge is `[lib]RehearsalSeparateProducer<T>`;
- no child semantic-capability TypeDef exists in `middle`;
- widened child consumption names the root capability in `[lib]`; and
- typed implementation fields remain `!T` where their producer graph is
  proven.

The same products include generated direct C# implementations of the root and
the external child. PSI and LightTree execute the Kotlin and C# graph on .NET
10 and Framework 4.8: four suites, four tests, and zero failures, errors, or
skips. The ABI codec round-trip additionally pins the producer-qualified
capability and rejects an invalid assembly identity.

## Remaining boundary

This checkpoint does not admit a child which declares new members, combines
independent reified roots, or changes the root's variance/domain. Those cases
need their own member-family and semantic-capability proofs. Inputs, defaults,
properties, mixed variance, Runtime/Stdlib closure, precompiled/non-partial
implementors, other CLR languages, deployment modes, and inverse rollback also
remain gates before any production cutover.
