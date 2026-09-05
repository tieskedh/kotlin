# Generic-owner nested-variant result correction — 2026-09-03

> **Intermediate snapshot.** This records the verified state at that point in
> the rehearsal. It predates the schema-22 declaration-authority and emitter-
> liveness consolidation and is not the current status or future-work plan; use
> [`STATUS.md`](../../STATUS.md) and the
> [way forward](../programmes/way-forward.md) for those.

This archive records a correctness correction to the rehearsal's first
constructed-call and path-complete direct-result fixtures. It changes neither
Kotlin semantics nor the production-erased owner representation. Its physical
library metadata version does advance, as recorded below, so stale producer
evidence cannot retain the corrected result policy.

## Corrected assumption

The earlier executable fixtures treated an exact outer construction as proof
of an exact nested result:

```text
InlineConstructedSource<!T>
    .source(): InlineProducer<!T>
```

That implication is false for the logical declaration
`InlineConstructedSource<out T>`. A physical
`InlineConstructedSource<object>` can authoritatively hold and return the same
object which implements only `InlineProducer<int>` after Kotlin's legal
`InlineProducer<Int> -> InlineProducer<Any?>` widening. The outer receiver view
does not prove the nested `InlineProducer<object>` view.

The live-MethodDef result check and result-spine machinery remain valid after a
natural operation has independently been selected. The invalid part was using
the outer receiver construction to select that natural operation in this
virtual, owner-relative result family. The executable route claims in
[`generic-owner-physical-constructed-call-result-live-slot-2026-09-02.md`](generic-owner-physical-constructed-call-result-live-slot-2026-09-02.md)
and
[`generic-owner-path-complete-direct-result-calls-2026-09-03.md`](generic-owner-path-complete-direct-result-calls-2026-09-03.md)
are superseded for `InlineConstructedSource<T>.source()`; their general
post-selection validation rules are not reversed.

## Repair

Class-owner planning now freezes a pristine logical-variance hazard index for
every local Kotlin-owned generic interface before planning any class. This is
negative-only evidence:

- declaration-site variance, or a non-invariant use-site projection, can veto
  an exact nested-interface state/result proof when its argument references the
  current class owner;
- it cannot admit a CLR TypeDef, manufacture a constructed view, select a
  MethodDef, or publish an ABI family; and
- later physical interface admission verifies that the declaration still has
  the same logical variance snapshot.

An owner-dependent variant-interface result uses the semantic object route
until a future producer-wide result analysis positively proves the expected
natural construction on every reachable return. The public natural MethodDef
remains the typed CLR/C# view. Kotlin dispatch uses the capability path, with
the recorded foreign-natural fallback, so Kotlin implementations and ordinary
CLR implementations retain one object and one authoritative state.

Two late-emission boundaries preserve that conservative choice without
weakening final call authority. An open-nested call which already returns
`object` may keep that same carrier in an immutable `object` local; no
non-object constructed call result is reconstructed by the legacy placement
fallback. Shared value-class lowering may also reinterpret an already equal
semantic-interface carrier while its enclosing consumer requests `object`.
That is an instruction-free CLR reference widening; boxing and all real
carrier changes remain rejected. The hostile value-class control fails to emit
without this distinction.

The local family plan derives this decision from its final closed roles and
reasons rather than caching it before inheritance/state closure. The same
producer-recorded query drives external call-route resolution and property
artifact validation, so methods, properties, and separately compiled consumers
cannot disagree about the result carrier.

An open Kotlin class adds one further interop obligation: a C# subclass only
overrides the natural typed MethodDef. The allocation-free virtual MethodDef
probe and capability dispatcher now forward any number of
declaration-independent arguments, so such ordinary parameterized C# overrides
remain visible from Kotlin's semantic route. An open/abstract semantic-result
shape whose inputs require an unproved carrier conversion is rejected from the
generic-owner rehearsal and stays erased.

Because the new producer-recorded result reason changes what an independently
compiled consumer must select, the physical library ABI advances from 67 to
68. The generic-owner artifact schema remains 21; old producers are rejected
rather than interpreted with the former exact-result assumption.

The hostile runtime case constructs `InlineConstructedSourceValue<Any?>` from
an actual `InlineProducer<Int>`, forwards it through
`InlineConstructedCallRoute<Any?>`, and requires both identity and value to
survive. A second case uses an invariant interface with an explicit `out T`
use-site projection. Its emitted class stores and reads one `object`; the IL
gate therefore rejects typed field access through a fabricated
`InlineInvariantProducer<object>` construction.

The complete-natural separate-compilation fixture independently publishes the
variant-result interface and consumes it from a downstream Kotlin assembly with
an exact `Outer<Any?>` receiver whose nested object is physically only
`Contract<int>`. The C# fixture overrides a two-argument result method and a
result property using only their natural typed entries; widened Kotlin calls
must still observe both overrides.

An open class with an owner-relative input is the fail-closed control. Its
prototype records `BLOCKED_UNSUPPORTED_FOREIGN_SEMANTIC_OVERRIDE`, and its
emitted owner remains on the arity-zero erased epoch rather than imposing a
hidden semantic override on ordinary C#.

## Deliberate conservative boundary

This correction establishes the safe default, not the final positive result
certificate. A later general producer-result analysis may restore a natural
route only when every normal return independently guarantees the same expected
physical construction. Logical result type, exact outer receiver, casts, IR
origin, or a natural-looking emitted signature are not such evidence. Unknown,
semantic/object state, a broad source parameter, a semantic-result call, an
open override family without producer authority, or incompatible joins remain
semantic.

That later analysis must also keep result layout independent from why a
semantic hook exists: a hook required only for an unrelated broad input must
not erase a separately proven exact result.

The recursive hazard test is deliberately conservative in this checkpoint: it
may propagate through an invariant outer container even where a future
polarity-aware path proof could retain more precision. That can only withhold a
typed rehearsal route; it cannot invent one. The producer-wide result analysis
must replace this over-approximation before production cutover.

## Verification

Backend compilation and all 97 physical-value model tests passed. The hostile
same-module and complete-natural separate-compilation fixtures assembled and
executed through PSI and LightTree on .NET 10 and .NET Framework 4.8. Direct
JUnit XML audit found eight tests in the candidate epoch and eight in the
production-erased inverse, with zero failures, errors, or skips in either mode.
The inverse emitted none of the candidate generic or semantic identities. The
candidate matrix also compiled and executed ordinary C# subclasses which
override a two-argument result method and a result property using only their
natural typed entries.
