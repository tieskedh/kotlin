# Producer-wide state FieldDef authority checkpoint (2026-08-29)

This record preserves the bounded Stage 6 rehearsal proof for producer-wide
generic-class state. It is not a general field grammar, a serialized external
state contract, or authorization for a production generic-owner cutover.

## Bounded authority

On unchanged physical library ABI 64, detached-family inheritance, private-
helper reachability, state selection, and owner-dependent output pairing first
run to one monotone fixpoint. Admission consumes the resulting final per-field
requirements; the priority-compressed owner disposition is diagnostic and
cannot override them.

The local rehearsal authority then admits exactly one owner-dependent state
slot. It must be a private mutable non-static instance field whose logical type
is one direct owner parameter, whose memory semantics are plain, and whose
producer plan has resolved it as either:

- `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`, physically the exact owner parameter;
  or
- `SEMANTIC_OBJECT_REQUIRED`, physically `object`.

The only initializer admitted by this first BOUND grammar is the exact
`POSITIONAL_CONSTRUCTOR_PARAMETER` recipe. Typed-state direct stores must take
their value from the writer's exact non-dispatch parameter, whose type is the
field's direct owner `T`. Explicit init-block stores, another-field sources,
computed expressions, and other nontrivial initializers make the family
unavailable. This is fail-closed rehearsal admission, not a hard user error for
the Kotlin declaration.

The whole owner remains unavailable when any owner-dependent slot still needs a
complete access graph or typed-write provenance. An owner-level compressed
disposition cannot hide such a slot. Nested, projected, logically nullable,
volatile, declaration-independent, and otherwise unresolved state is excluded.

Generic-owner artifact schema remains 21 and compiler/runtime surface remains
60. The new state-emission snapshot is rehearsal-only diagnostic evidence; it
does not serialize local value lineage or establish a downstream field record.

## BOUND FieldDef and writer authority

The BOUND declaration index now owns:

- the complete identity set of every pre-existing instance field on the owner;
- exact local IR field and declaring-TypeDef identity;
- private instance mutable flags;
- the symbolic `!T` or `object` carrier and exact type binder;
- plain memory semantics; and
- whether the producer plan contained an implicit field initializer.

The emitter consults this authority before the logical field type mapper. Final
routing requires the same complete instance-field identity set, so neither a
new `!T` nor an `object` shadow field may appear after BOUND.

Immediately before BOUND, after every admitted bridge- and body-producing pass,
the complete live module is scanned again. Every live typed store must still be
the exact non-dispatch writer parameter of the field's direct owner `T` type. A
new unsupported live store makes the family `Unavailable`; it does not turn
valid Kotlin into a hard compiler error. Once BOUND has frozen the family,
however, any later field or store change is an internal authority conflict.

Every existing selected `IrSetField` site is stamped with unique copy-
preserving target, producer declaration, statement-origin, and verifier-visible
value-type lineage. Each lineage must occur exactly once after lowering;
removal, duplication, retargeting, or changes to producer/origin/value type
fail. The only late materialization contract is one pre-recorded exact
positional Common `INITIALIZE_FIELD`: constructor, parameter index, receiver,
value symbol, value type, and occurrence count must match the producer record,
and the original field initializer must no longer remain. It is not a newly
admitted writer after BOUND.

This freezes the writer assumptions behind the state decision without making
local value provenance a state-selection oracle.

## Actual-only final seal

Field rendering records fresh final-emission observations. Before dependency,
IL, or PE publication, the full observed instance-field identity set on the
owner must equal the complete BOUND set. The selected owner-dependent FieldDef
is sealed separately: exactly one matching field must exist on the exact
TypeDef; field identity, owner-derived scope, visibility, static/init-only
flags, carrier, exact owner-parameter index, binder, TypeDef category/arity, and
cross-scope uniqueness are checked. The seal separately observes the emitted
physical name; BOUND does not supply or validate that name. The PE harness then
correlates the sealed name with the objective FieldDef.

Validation precedes every publication path, but the diagnostic state snapshot
is published only after ILAsm succeeds. IL-only and failed assembly paths expose
no snapshot. Missing, duplicate, or contradictory output is an internal
conflict; expected BOUND data never fills absent final evidence.

## Hostile product

The separate producer contains two structurally similar owners:

```kotlin
open class TypedStateOwner<T>(initial: T) {
    private var state: T = initial
    fun write(next: T) { state = next }
    fun read(): T = state
}

open class BroadStateOwner<out T>(initial: T) {
    private var state: T = initial
    fun write(next: @UnsafeVariance T) { state = next }
    fun read(): T = state
}
```

The first seals one private mutable `!0` FieldDef. The second seals one private
mutable `object` FieldDef because a widened semantic view can legally reach its
writer. Its natural constructor, `read`, and `write` surface still uses `!0`;
only the authoritative state carrier and necessary semantic route are broad.

A separately compiled middle library adds memberless generic children. Their
base edges remain exact `Base<!0>` constructions and they add no fields. Kotlin
and ordinary C# consumers prove typed value/reference state, star reads, a
widened semantic write, broad readback, exact-view rejection of an incompatible
value, recovery through the same state, reflection, and reference identity.
There is one receiver and one field graph throughout.

The hostile product also exposed a composition bug: a final direct `T` result
which reads semantic-object state needs the same paired semantic-state output
route as a nested owner-dependent result. The planner now derives that rule from
the result's owner-parameter dependence rather than a declaration or member
name. The broad read therefore observes semantic state; the exact natural view
performs the physical cast at its own boundary.

## Production-erased inverse

Without the rehearsal property, the same sources retain arity-zero Kotlin-owned
owners, private mutable `object` state, erased constructor/read/write signatures,
direct non-generic child bases, and no candidate state-emission snapshots or
`H`, `N`, `M`, or `J` records. No production Runtime/Stdlib surface or generic-
owner artifact schema changes.

## Verification

Direct JUnit XML audit and command results establish:

- the focused physical-value/FieldDef model suite: 1 suite, 71 tests, zero
  failures, errors, or skips;
- candidate PSI/LightTree x Framework 4.8/.NET 10: 4 suites, 4 tests, zero
  failures, errors, or skips; this includes PE metadata, reflection, inherited
  bases, absence of shadow state, executable Kotlin, and ordinary C#;
- the exact production-erased inverse over the same matrix: 4 suites, 4 tests,
  zero failures, errors, or skips; and
- the changed backend, CLI pipeline, and FIR test-fixture modules compile.

The latest fresh unqualified production-erased aggregate is inherited from the
immediately preceding ABI-64 checkpoint because every selected state,
mapping, route, snapshot, and final seal is rehearsal-dominated and the exact
inverse proves the production path unchanged. That aggregate contains 204
suites and 2,583 tests with zero failures, errors, or skips.

Candidate command:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerStateAuthoritySeparateCompilation" -q
```

Production-erased inverse command:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerStateAuthoritySeparateCompilation" -q
```

The inherited aggregate's partition and command are recorded in
[`../../STATUS.md`](../../STATUS.md).

## Remaining boundary

This checkpoint does not admit nested or projected carriers, nullable or value-
class fields, multiple/mixed state families, volatile memory, custom storage,
mixed captures, incomplete/open writer graphs, or externally serialized state
authority. It does not complete shared per-value provenance, retained foreign
declarations, deployment modes, the Runtime/Stdlib selected family, or atomic
rollback. The separately identified conversion from a generic child capability
carrier to a differently owned base capability remains an interface-routing
problem outside this state proof. Production Kotlin-owned generic classes and
interfaces remain erased.
