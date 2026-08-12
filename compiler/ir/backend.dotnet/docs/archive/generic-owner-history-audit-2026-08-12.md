# Historical audit: removed CLR-generic class owner model

- Audit date: 2026-08-12
- Typed/capability implementation: `722a34ee04` plus `cde3907eb1`
- Erased replacement: `b520514f37`
- Current semantic base while auditing: `ed80e65036`
- Current candidate:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)

This snapshot records evidence from Git and the current hostile oracle. It is
not architecture authority. The accepted erased-owner ADR remains binding
until the draft replacement is accepted.

## What the removed implementation actually built

Commit `722a34ee04` added a real invariant CLR `C<T>` implementation TypeDef
and a non-generic canonical interface for the same Kotlin declaration. The
class owned typed fields, constructors, members, base construction, and C#
subclassing. Every constructed instance implemented the canonical interface;
there was no wrapper or second mutable object.

`DotNetGenericClassBridgeLowering` generated a private canonical adapter for
every non-private instance member. The producer emitted explicit MethodImpl
relationships between those adapters and canonical interface slots. Physical
library metadata retained typed and canonical owner paths, member pairs, and
the open typed TypeDef used by runtime ancestry classification.

Runtime `is C<*>`/casts walked the CLR base chain and compared the exact open
generic TypeDef, rather than trusting a foreign implementation of the public
canonical interface. Nested owners, derived classes, and C# subclasses were
therefore classified by ancestry while ignoring constructed arguments.

Commit `cde3907eb1` then added guarded typed dispatch. Fresh constructions and
immutable aliases could call the typed member directly. Other receivers used
an `isinst C<X>` capability probe and fell back to the canonical slot. The
probe preserved receiver/argument evaluation order and virtual dispatch.

The old ADR recorded a two-million-update .NET 10 microbenchmark. Its exact
numbers are historical, but the material result remains useful: direct or
guarded `C<int>` calls removed nearly all allocation caused by boxed canonical
reads/writes. This proves that the CLR-generic owner was mechanically real and
that value specialization can matter; it does not prove the semantic bridge.

## The exact semantic failure

The canonical bridge computed an erased signature, then generated a body which
implicitly cast each erased argument to the typed source parameter and called
the typed member. That direction is visible in the historical
`DotNetGenericClassBridgeLowering.createBridge`: bridge arguments are converted
to `targetParameterTypes` before `irCall(source.symbol)`.

That is correct only when the logical call requires an exact `T`. It is wrong
for candidate-accepting operations available through ordinary variance. The
failure exposed by `b520514f37` is:

```kotlin
val ints: Collection<Int> = ...
val widened: Collection<Any?> = ints
widened.containsAll(listOf(1, "wrong"))
```

Common must inspect `"wrong"` and return false. The historical bridge instead
tried to narrow it to the physical `int` argument before the algorithm ran and
threw. Special-casing `containsAll` would not repair other nested candidates,
`@UnsafeVariance` members, projections, overrides, or separate compilation.

The current generic-interface bridge supplies an additional reusable
precedent which the removed generic-class bridge did not consume:
`SpecialBridgeMethods.findSpecialWithOverride` guards Kotlin's shared
type-safe-barrier built-ins and returns their specified incompatible value
before narrowing. This is appropriate for `contains`, candidate `remove`, map
lookups/removals, and list index searches. It deliberately does not classify
`containsAll`; that nested candidate requires its semantic body and iteration
behavior. A replacement class lowering should reuse this shared table for
barrier slots and solve general/nested semantic slots rather than treating
either category as the other.

The current hostile files
`genericOwnerHardestModelOracle.kt` and
`genericOwnerHardestModelOracleSeparateCompilation.kt` pin this legal behavior
alongside stars, both projections, open `T?`, mutable state, owner-relative
methods, generic interfaces, multi-level overrides, arrays, reflection, and
both compiler/runtime cross-products. All eight focused executions pass on the
erased correctness baseline.

## The former mutation contradiction

The old design also attempted to preserve JVM-style late failure for this
unchecked cast:

```kotlin
val strings = Box("text")          // physical Box<string>
@Suppress("UNCHECKED_CAST")
val ints = strings as Any as Box<Int>
ints.write(1)
```

A `Box<string>` field cannot accept an `int`. Preserving the invalid write
would require early rejection, erased storage, or two stores. At the time the
target chose late failure and replaced the owner with one erased class in
`b520514f37`.

The reopened programme deliberately chooses the platform-permitted alternative:
a physically incompatible unchecked `as Box<Int>` may fail at the cast. That
removes the requirement that typed storage accept an invalid write. It does
not relax any ordinary star, projection, widened candidate, or variance call.

That distinction exposes a second flaw in the removed implementation. It
mapped owner-dependent backing fields through the typed generic mapper, so a
`C<int>` state slot was physically `!T`. Kotlin can legally write an
incompatible value through an `@UnsafeVariance` member on a covariantly
widened owner and defer the failure until the first exact consumer requires a
physical conversion; a discarded getter result performs no cast. Unlike the unchecked
exact cast above, that ordinary widened call cannot be rejected early. A
replacement must select an object/semantic carrier for every field reachable
from such a mutation path, while retaining typed accessors over the same state,
or leave the declaration unadmitted. Correcting only the old argument bridge
would not fix this storage-timing bug.

## Reuse map

### Reuse after revalidation

- real generic TypeDef, GenericParam, constraint, base-construction, field,
  constructor, and typed member emission;
- the one-object `C<T>` plus non-generic capability shape;
- exact open-TypeDef ancestry classification based on producer binding;
- explicit InterfaceImpl/MethodImpl and physical-binding records;
- exact typed construction and guarded/direct-call mechanics;
- C# construction/subclass/override and separate-library test infrastructure;
- deterministic erased overload/capability slot naming; and
- the historical performance corpus structure, rerun on representative apps.

### Rewrite before use

- canonical member lowering: the erased semantic entry must own or clone the
  Kotlin body instead of narrowing all arguments into the typed member;
- override mapping: typed and semantic entries must form one coherent virtual
  family across Kotlin and C# subclasses;
- cast lowering: exact incompatible constructions may fail early, while
  stars/projections use open declaration identity;
- physical carrier selection across joins, stores, external calls, and
  producer-published bindings, including field carriers selected from semantic
  mutation reachability rather than owner `!T` alone;
- reflection normalization now that KClass, KType, callable members, and
  invocation exist; and
- nullable metadata and arbitrary struct constraints added since the old model.

### Do not restore

- typed-primary bridges that cast every erased argument before semantic
  dispatch;
- a second implementation object, wrapper, proxy, or copied state;
- two concurrently authoritative stores;
- `C<object>` as star identity or universal exact construction;
- static-Kotlin-type or visibility-only physical ABI decisions;
- per-class/easy-owner production rollout before the hostile model; or
- any inference of physical identities from names/arity rather than bindings.

## New problem that must be solved first

Open nullable arguments were not solved by the historical model. An
unconstrained `T?` means a nullable reference for reference substitutions and
`Nullable<T>` for value substitutions; CLR has no one static construction
which spells both. The current candidate therefore treats
`fun <T> make(T?): C<T?>` as a primary gate.

The current direct CLR probe adds two post-history facts. A guarded
`Nullable<!!T>` token fails at execution on CLR 4 and CoreCLR even though the
illegal construction is placed only on the value-type branch. Runtime type
construction succeeds on both JIT runtimes and creates exact
`C<string>`/`C<Nullable<int>>` owners with null and mutable state preserved
through the same object's semantic capability.

The architecture spike must compare that dynamic exact route with the earlier
`C<object>` semantic fallback. Both must compose with exact objects, state,
casts, arrays, joins, storage, reflection, trimming/AOT, and separate
compilation. JIT feasibility does not select reflection construction, and a
fallback is not allowed to masquerade as an exact construction. If neither
route satisfies the full matrix, the model changes before production. Success
of an easy `C<int>` prototype is not sufficient evidence.

## Conclusion

The historical experiment does not show that CLR-generic Kotlin owners are
impossible. It shows that a typed-authoritative forwarding bridge is
insufficient. The strongest viable direction is to retain the current erased
body behavior as the semantic authority, put one real `C<T>` owner and one
planner-selected state under it, permit early failure only for physically
incompatible unchecked exact casts, and specialize/devirtualize exact paths
around the semantic fallback. A true generic owner is compatible with an
object-carried field when Kotlin's legal widened mutation requires it; it is
not compatible with two stores or changed failure timing.

The next prototype should modify the historical bridge direction first and
attack open-nullable construction before adding broader optimization.
