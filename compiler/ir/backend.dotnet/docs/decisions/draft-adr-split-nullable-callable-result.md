# Draft ADR: split-nullable physical callable results

- Status: **Draft — production-inert calling-convention rehearsal**
- Scope: direct logical open-`T?` callable results on admitted CLR-generic
  owners and methods
- Baseline: [hybrid generic nullability and covariant-return bridges](adr-hybrid-generic-nullability-and-covariant-returns.md)
- Shared model: [generic-owner physical authority and value provenance](draft-adr-generic-owner-physical-authority.md)

## Context

One unconstrained CLR generic parameter cannot itself denote both a value and
its outer null state. The accepted one-slot ABI therefore represents logical
`T?` as boxed-or-null `object`. That is stable and general, but it boxes an exact
value result such as `Source<Int>.read()`.

A callable may instead carry the null state separately:

```text
!T Read(..., out bool isNull)
```

This is a physical calling convention for one logical result. It is not a
second Kotlin parameter, a new Kotlin type, or permission to split object state.

## Decision hypothesis

An admitted direct logical nullable result may use:

```text
SplitNullable(payload = PhysicalTypeExpression, out bool isNull)
```

Kotlin IR, KLIB, reflection, override selection, and source calls continue to
see `T?`. The producer records the result layout on the exact MethodDef family.
A consumer binds that record to the actual MethodDef and final `[out] bool&`
parameter; it never infers the layout from a declaration name or current
logical substitution.

The payload is a producer-recorded physical type expression scoped to its owner
and method binders. Substitution applies the actual physical construction
directly. It must not round-trip through a logical `IrType` mapper. Therefore:

```text
T = Int       -> payload int32
T = String    -> payload string
T = Int?      -> payload Nullable<int32>
T = ValueBox  -> the recorded nominal generic-slot carrier, not its incidental
                 underlying field representation
```

`isNull` describes absence of the outer logical result. The payload is ignored
when the flag is true; it does not acquire a magic sentinel value.

## Orthogonal callable composition

Parameter domains, receiver/virtual authority, semantic policy, and result
layout are independent components of one callable contract. The compiler must
not introduce roles such as:

```text
SPLIT_NULLABLE_WITH_OWNER_INPUT
SPLIT_NULLABLE_MAP_GET
SPLIT_NULLABLE_WITH_BROAD_BARRIER
```

A structural two-parameter lookup can instead compose:

```text
parameter 0: STRICT_OWNER_INPUT(!K)
result:     SplitNullable(!V, out bool)

!V Get(!K key, out bool isNull)
```

The same contract can later describe `Map<K, out V>.get(K): V?` without a Map,
package, or member-name rule. Exact `Lookup<int,int>` and `Map<int,int>` calls
then keep both the key and result unboxed.

An exact receiver invokes the recorded natural slot. At an ordinary logical
Kotlin value boundary the compiler reconstructs nullability from payload plus
flag. The physical-authority model may instead retain the exact pair through a
compiler-controlled local when its complete use and destination layout are
independently proven. A semantic, star, projection, or otherwise unnameable
route may materialize boxed-or-null `object` only at its operation boundary. It
must not erase the owner, fields, unrelated parameters, helpers, or result
chains.

## C# and override surface

An ordinary CLR implementation sees the natural method with an ordinary
`out bool` parameter. No partial class, source generator, wrapper, or hidden
interface is required to implement that MethodDef.

Optional tooling may offer an idiomatic `TryRead`/`TryGet` projection. Such an
export is additive; it does not replace the Kotlin callable, implement hidden
semantic ABI, or own a second body.

Every override, default, inherited slot, bridge, MethodImpl, and separate
consumer must agree on the complete result layout. An already emitted wider or
one-slot MethodDef remains authoritative and receives an explicit adapter; it
is never retrospectively rewritten.

## State boundary

This draft covers callable results and representation-preserving local
transport of an already selected result layout. It does not authorize:

- two physical fields for one logical property;
- a payload field plus unsynchronized null flag;
- split parameters whose evaluation/aliasing semantics differ;
- duplicate or shadow object state; or
- local specialization flowing backward into public ABI.

A future split field design would require its own atomicity, volatility,
reflection, constructor, mutation, and separate-compilation proof.

## Required evidence

Before acceptance, a declaration- and package-independent matrix must prove:

1. reference, signed value, nullable signed value, open owner, and open method
   payload substitutions;
2. nominal Kotlin value-class substitutions use the producer-recorded generic
   slot carrier rather than a remapped underlying carrier;
3. null and non-null results, exception paths, defaults, virtual overrides,
   direct `super`, diamonds, and MethodImpl bridges;
4. independent strict, broad-candidate, declaration-independent, and multiple
   input policies;
5. a custom `Lookup<K,V>` exact `!V Get(!K, out bool)` proof before `Map`;
6. exact calls without boxing and semantic calls boxing only at their boundary;
7. ordinary non-partial C# implementation and virtual redispatch, plus optional
   export tooling which shares the same body;
8. producer/consumer compilation with stale, absent, and contradictory result-
   layout records failing closed;
9. Framework 4.8, .NET 10, ReadyToRun, trimming, and NativeAOT; and
10. the exact erased-production inverse and rollback.

The bounded Stage 7 rehearsal closes item 5 and the exact value/reference,
ordinary C#, separate-compilation, Framework 4.8, .NET 10, and erased-inverse
lanes needed for that custom structural family. It also proves that semantic
role, input domains, and result layout can be recorded and consumed
independently. The remaining items above still block acceptance, especially
value classes, richer virtual/inheritance shapes, deployment lanes, stale and
contradictory layout records, and broader input-policy composition. Evidence is
recorded in the
[Stage 7 archive](../archive/generic-owner-callable-contract-composition-2026-08-31.md).

The local-transport authority may retain one natural
`SplitNullable(!T, out bool)` result in two compiler-private locals only when an
immutable logical local has a positive number of reads and every read is its
bare value directly returned to the same enclosing MethodDef with the identical
split result layout, outside `try`/`catch`/`finally`. The exhaustive rule is
`readCount > 0`, `readCount == directFunctionReturnCount`, no other-target
return, and no protected return. Multiple static sites are safe only because
each executed use terminates its path; this does not admit sequential or mixed
consumption.

Placement binding checks that rule and the emitter repeats it against live IR
immediately before declaring the payload/flag locals. A late mismatch fails
closed rather than retaining stale placement authority. The authority also
requires the complete final BOUND direct-natural operation witness; a result
payload or diagnostic snapshot alone never grants the pair token. The first
slice established the narrower one-return case, value/reference/nullable-value
substitutions, and ordinary-consumer and exception-region negatives in the
[local-placement archive](../archive/generic-owner-physical-split-nullable-local-placement-2026-09-01.md).
The positive-N terminal-return extension is recorded in the
[multiple-direct-returns archive](../archive/generic-owner-physical-split-nullable-multiple-direct-returns-2026-09-02.md).
General exception paths, control-flow joins, fields, and acceptance remain open.

The second local-transport slice began with one strict ordinary argument and
now consumes a complete empty-MethodSpec vector. Every slot is independently
either `STRICT_OWNER_INPUT` with an identity-preserving current-owner carrier,
or a fixed `DECLARATION_INDEPENDENT` Boolean, Int32, String, or Object leaf
whose natural and semantic MethodDefs agree. Placement retains the complete
MethodDef/receiver/parameter/result witness. The hand-off to operation routing
admits only a regular parameter whose producer-planned slot domain is
`DECLARATION_INDEPENDENT`, whose typed and current physical prototypes carry
the same supported fixed leaf, and whose final storage is
`Direct(Fixed(the same leaf))`; owner, constructed, broad semantic-object,
fallback-object, and MethodDef-binder facts remain excluded. Emission
re-resolves the live virtual MethodDef, requires one unique recorded receiver
view, validates every ordered live argument, and uses a private Boolean local
as the nested call's null-flag address, never the enclosing flag. The interface MethodDef's independent `!K`/`!V` binder
positions remain distinct even when both bind to one outer type parameter.
Evidence is in the
[strict-input](../archive/generic-owner-physical-split-nullable-strict-input-placement-2026-09-02.md),
[repeated-input](../archive/generic-owner-physical-split-nullable-repeated-input-placement-2026-09-02.md),
and [fixed-input](../archive/generic-owner-physical-split-nullable-fixed-input-placement-2026-09-02.md)
archives. Unsupported/nested carriers and fixed leaves combined with a
MethodSpec remain unavailable.

The third local-transport slice admits exactly one MethodSpec-bearing operation
shape: `<R>(K, R): V?`. Its retained authority contains both the declaration-
time receiver, `!K`/`!!R` parameter vector and `!V` payload, and the independently
instantiated receiver, parameters, owner-bound `<!m>` MethodSpec and payload.
This prevents equal call-site carriers from hiding a TypeDef/MethodDef binder
swap. The sole MethodDef parameter must be fully unconstrained, every call value
must be a direct exact storage read, and the shared exhaustive pair-use policy
applies. This proves one composition, not arbitrary MethodSpecs or argument
vectors. Evidence is in the
[MethodSpec local-placement archive](../archive/generic-owner-physical-split-nullable-methodspec-placement-2026-09-02.md).

This remains a **GO** for production-inert consolidation. It is not a GO for
public ABI or for applying the layout to `Map`; the custom proof passing removes
only that prerequisite, not the broader Runtime-family and release gates.
