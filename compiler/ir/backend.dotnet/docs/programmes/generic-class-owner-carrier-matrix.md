# Generic class owner carrier and admission matrix

- Status: **Architecture design artifact — not production authority**
- Date: 2026-08-12
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Candidate ADR:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Current production authority:
  [`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md)

This document makes the candidate carrier and member-admission rules
deterministic enough to drive the hostile prototype. It does not authorize a
CLR-generic Kotlin owner. The accepted erased owner remains the emitted ABI
and the semantic oracle.

## Terms

For a Kotlin-owned generic declaration `C<A>`:

- `C<>` is its one proposed open CLR implementation TypeDef;
- `P(A)` is the truthful CLR representation of logical argument `A` when one
  can be named at the current point;
- `C<P(A)>` is an exact constructed owner;
- `S(C)` is the non-generic semantic capability implemented by every
  construction of `C<>`;
- an **exact carrier** is a statically typed `C<P(A)>` value;
- a **semantic carrier** is the same object viewed as `S(C)`; and
- a **dynamic exact construction** is an exact `C<P(A)>` object whose closed
  owner can only be selected from a runtime type token.

`S(C)` is not a Kotlin classifier, raw type, wrapper, proxy, copied object, or
second store. It is compiler ABI on the one object. KLIB remains authoritative
for Kotlin arguments, variance, projections, nullability, and bounds.

## Argument representation matrix

The mapping is recursive. A nested construction is statically exact only when
every component has a statically nameable truthful mapping.

| Logical argument at the construction point | Truthful physical argument | Static exact construction? | Required carrier when not exact |
| --- | --- | --- | --- |
| `String` | `string` | yes | — |
| `Int` or another primitive/value type | `int32` or its exact value type | yes | — |
| `Int?` or another closed nullable value type | `Nullable<int32>` | yes | — |
| closed nullable reference `R?` | `R` plus nullable metadata | yes | — |
| method/owner parameter `T` with no added nullable transformation | the corresponding CLR `!!T`/`!T` | yes | — |
| definitely non-null `T & Any` | the corresponding CLR parameter | yes | — |
| `T?` where `T` is proven reference-constrained | the CLR parameter plus nullable metadata | yes | — |
| `T?` where `T` is proven value-constrained | `Nullable<!!T>`/`Nullable<!T>` | yes | — |
| unconstrained `T?` | reference `T` for reference substitutions, `Nullable<T>` for value substitutions | no single static token | `S(C)` at the enclosing ABI; dynamic exact construction or a tested semantic fallback internally |
| star projection | no argument may be asserted | no | `S(C)` |
| use-site `out`/`in` projection | CLR classes are invariant; the projected relation is Kotlin-only | no projected class construction | `S(C)` |
| nested `G<A>` | `G<P(A)>` only if `G` and every argument are exact | recursive | `S(C)` at the first non-exact owner |

Kotlin `T : Any` does not by itself make `T?` statically representable. On
this target a logical non-null bound deliberately does not imply a CLR
`class` or `struct` constraint, so `T` may still close to either a reference
or a value type.

Declaration-site Kotlin variance does not make the CLR class variant. A
truthful variant interface may be additive when its complete member surface
satisfies CLR variance rules; otherwise projected uses remain on `S(C)`.

### Construction-time choice versus metadata-fixed edges

Runtime exact construction helps only where code is allowed to choose a closed
type at object-construction time. It cannot change metadata fixed on an open
TypeDef: a base class TypeSpec, InterfaceImpl TypeSpec, field or method
signature, or generic constraint.

The hardest example is `open class D<T> : C<T?>`. One CLR `D<T>` TypeDef
cannot conditionally extend `C<Nullable<T>>` for value substitutions and
`C<T>` for reference substitutions. Reflection can create either closed
`C<>` object, but it cannot replace `D<>`'s base row after the TypeDef exists.

The admission choices are therefore limited and explicit:

1. keep the whole `D<T>` declaration on an erased owner which extends a tested
   semantic/fallback construction of `C<>`;
2. emit `D<T>` but use one fixed fallback base such as `C<object>`, exposing
   the relation to Kotlin through `S(C)` and accepting that the direct C# base
   is not logical `C<T?>`; or
3. reject the shape until a different one-owner representation exists.

Generating separate reference/value `D` TypeDefs, changing base type at
runtime, wrapping the base object, or claiming both constructions are one CLR
inheritance identity are not options. The hostile prototype must decide
whether the fixed fallback preserves all Kotlin override, `super`, state,
cast, reflection, and C# honesty requirements. If not, this declaration shape
is deterministically unadmitted even if ordinary `C<T>` is reified.

The accepted erased semantic oracle now contains this exact inheritance shape
in same- and separate-compilation forms, with `Int?` and `String?`, producer-
and consumer-owned subclasses, virtual dispatch, direct `super`, mutation,
and classifier checks. It also distinguishes a general user
`@UnsafeVariance` body from the shared fixed-result collection barriers. These
tests define the behavior the physical prototype must match; they do not make
the fixed CLR fallback admissible by themselves.

A direct CLR 4/CoreCLR `C<object>` fallback probe further shows that this
choice preserves one inherited state, virtual dispatch, direct `super`, and
nullable value/reference mutation. It simultaneously proves that CLR
reflection and C# see only the fixed `C<object>` ancestry. Consequently option
2 is a semantic fallback for an unadmitted declaration, not an exact reified
admission of `D<T> : C<T?>`. The first classifier must record that distinction
instead of treating “runs correctly through the capability” as “has the
logical CLR base.”

The same fixed-metadata rule applies recursively to open-nullable interfaces,
fields, members, nested owners, and constraints. Semantic carriers can keep a
field/member signature honest; inheritance is stricter because every CLR class
must select exactly one physical base.

## Open-nullable construction evidence

The direct CLR integration probe
`testOpenNullableGenericOwnerRuntimeConstructionOptions` establishes two
different facts on CLR 4 and CoreCLR:

1. ILAsm accepts a generic method containing `Nullable<!!T>` behind an
   `IsValueType` branch, but both runtimes reject execution. Runtime control
   flow does not make an invalid unconstrained constructed token legal.
2. Runtime type construction can select `C<Nullable<int>>` for `T = int` and
   `C<string>` for `T = string`. Both retain null, accept later writes through
   one semantic capability, and expose the exact closed physical argument.

The second result proves JIT feasibility, not product suitability. Reflection
construction, trimming annotations, generic sharing, ReadyToRun, NativeAOT,
startup, allocation, code size, and separate-assembly behavior remain gates.
No production path may silently depend on dynamic code generation.

A bounded local .NET 10 probe subsequently passed ReadyToRun execution and a
fully trimmed self-contained execution for the exercised `int` and `string`
constructions. NativeAOT managed compilation emitted IL3050 because
`Type.MakeGenericType` requires dynamic code; native linking could not run on
the available machine because its Visual C++ platform linker is absent. This
is recorded, without upgrading it to a product guarantee, in
[`../archive/generic-owner-runtime-compilation-probe-2026-08-12.md`](../archive/generic-owner-runtime-compilation-probe-2026-08-12.md).

The later finite factory now has a reproducible measurement corpus derived
from the exact version-7 producer record rather than a second handwritten
program. It combines exact `int`, already-nullable, reference, and consumer-
struct roots with mandatory unlisted struct/reference fallback, paired state,
typed/semantic arrays, and multi-level hostile dispatch. One fingerprinted
net10 bundle executed with a stable checksum under JIT, ReadyToRun, full
trimming, and NativeAOT. Its project, SDK selector, producer, generated source,
and family record are fingerprinted and publication cannot mutate the six-file
bundle.
See
[`../archive/generic-owner-native-aot-measurement-2026-08-13.md`](../archive/generic-owner-native-aot-measurement-2026-08-13.md).
This closes the reproducible bounded-corpus NativeAOT baseline, not the
representative-application/product comparison.

The competing fallback is a construction such as `C<object>` used only
through `S(C)`. It is simpler for AOT but is not the exact physical meaning of
logical `C<Int?>` or `C<String?>`. If admitted, all boundaries must accept that
a closed logical value may have this fallback construction; consumers may
never recover `C<Nullable<int>>` merely from their own substitution. The
hostile prototype must compare dynamic exact construction and semantic
fallback rather than assuming either.

## Carrier selection by value provenance

Carrier selection is a proof about all legal producers of a value, not a
pretty-printing rule for the consumer's current static type.

| Value site | Exact carrier is allowed when | Otherwise |
| --- | --- | --- |
| fresh local construction | its complete argument graph has a truthful static construction | `S(C)` |
| dynamic exact construction | the runtime closes the recorded open TypeDef to the truthful argument graph | return/store as `S(C)` unless the closed token is also represented in the ABI |
| local alias | the alias preserves the same exact construction | `S(C)` |
| local join/phi | every incoming value proves the same exact construction | `S(C)` |
| array/collection element | the element container admits every producer with the same exact construction | a semantic-capability element carrier |
| private field/parameter/return | every reachable producer/write is physically compatible, no semantic entry can mutate it incompatibly, and invalidation is local | `S(C)` when proof is absent |
| internal/friend boundary | the physical binding records one stable construction and every producer obeys it | `S(C)` |
| public/protected Kotlin ABI | every legal current and future producer has the same declaration-stable construction | `S(C)` |
| base class/interface edge | the complete argument graph is statically exact in the declaring TypeDef | a deliberately selected fixed fallback/capability edge, or reject the declaration |
| explicit C# export | the complete exported boundary is truthful and conversion-free | an explicit semantic facade/adapter or no export |
| star/projected view | never as a projected CLR class construction | `S(C)` |
| unchecked exact cast | the object is actually the requested construction | throw/fail the cast; do not wrap or copy |

An exact public signature is therefore possible for genuinely stable shapes,
for example a closed exported factory that always constructs and returns
`C<string>`. It is not inferred merely because a consumer sees logical
`C<String>`. The physical binding must state the carrier selected by the
producer.

Dynamic exact construction would make more closed consumer boundaries
admissible because an open producer could still construct the eventual exact
owner. That benefit counts only if AOT and trimming can implement the same
closed set honestly. Otherwise the public ABI remains semantic even though a
JIT happens to create an exact object underneath it.

## Member slot-domain classification

The historical model failed because it treated every erased argument as a
typed argument awaiting a cast. The replacement classifies the complete
logical domain admitted at each member position before selecting a bridge.

### Strict exact input

Examples are `write(value: T)` and `MutableCollection.add(element: E)` when
the call's legal argument is required to inhabit the receiver's element
domain.

- The natural CLR member may be `virtual void Write(!T)`.
- A semantic-capability entry may narrow at this true typed-use barrier.
- A physically incompatible unchecked exact cast may consequently fail
  before this call.
- Stars and output projections that do not admit the input cannot manufacture
  a broader call.

Input projections remain valid: a physical owner with a broader actual
argument accepts the narrower value allowed by the projected Kotlin view.

### Strict output

Examples are `read(): T` and a field/property getter.

- The natural CLR member may return `!T`.
- The semantic-capability entry boxes or reference-converts that result.
- Exact callers may use the typed virtual directly.
- Projection and star callers use `S(C)` and the logical Kotlin use-site cast
  supplied by their actual operation.

### Broad candidate input

Examples include covariant Common `contains(@UnsafeVariance E)`,
`containsAll(Collection<E>)` after widening, and any slot whose legal call can
present a candidate outside the physical owner argument.

Such a slot must have three distinguishable roles:

1. a natural typed CLR virtual such as `Contains(!T)` for exact C# and Kotlin
   callers;
2. a semantic virtual body/hook accepting `object` (and semantic nested
   carriers) which can execute the full Kotlin/Common contract; and
3. a capability dispatcher which preserves evaluation order and virtual
   dispatch, sends compatible candidates through the typed virtual, and sends
   incompatible candidates to the semantic hook without first narrowing.

For a Kotlin implementation, the typed virtual may box and invoke its semantic
hook, while the capability dispatcher is a separate method so this does not
recurse. A base dispatcher inherited by a C# subclass still calls the typed
virtual for a compatible value and therefore observes an ordinary C# override.
For an incompatible candidate it calls the semantic virtual hook; a C# author
who needs to customize that Kotlin-only widened behavior must be able to
override the documented semantic hook explicitly.

The semantic hook is the one Kotlin source-body authority for this policy.
The typed Kotlin virtual is a carrier-converting wrapper into it, not a second
copy of the algorithm. The dispatcher uses that typed virtual for compatible
candidates to preserve C# overrides and calls the semantic hook directly for
incompatible candidates. A Kotlin semantic-body `super` call binds the
producer-recorded base semantic hook non-virtually; routing it through a typed
base wrapper would reintroduce premature narrowing. Defaults and nested
carrier operations must retain the same family identity.

If the broad source member is abstract and no inherited semantic body exists,
both the typed virtual and semantic hook remain abstract obligations. A C#
subclass which implements only the typed member cannot truthfully become
concrete because it has not defined the wider Kotlin candidate domain.

A separate-assembly CLR 4/CoreCLR probe now enforces this physically: both C#
compilers reject the typed-only abstract implementation, while a complete
consumer subclass dispatches compatible candidates through its typed override
and incompatible candidates through its semantic override. The same probe
keeps a fixed-result special barrier from invoking the semantic hook. This is
the required behavior for the future producer-selected slot-family records;
it is not yet compiler-produced Kotlin evidence.

This is a candidate dispatch shape, not yet accepted ABI. The prototype must
prove `MethodImpl`, multi-level Kotlin/C# overrides, `super`, defaults, and
separate compilation. It must also prove that an incompatible candidate
returns Common's result instead of changing exception timing.

Broad slots have two different incompatible-candidate policies:

1. **Type-safe barrier.** Kotlin's shared `SpecialBridgeMethods` already
   identifies built-ins such as `Collection.contains`, `MutableCollection.remove`,
   map candidate operations, and list index searches, together with the
   authoritative incompatible result (`false`, `null`, `-1`, or the second
   argument). This is the mature JVM bridge contract. The capability
   dispatcher returns that result when a checked argument is incompatible and
   invokes the typed virtual only when compatible. It does not call an
   arbitrary semantic hook for the rejected candidate.
2. **Semantic body.** A general user `@UnsafeVariance` operation, or a nested
   operation such as `containsAll` whose Common body must inspect a collection
   and preserve iteration/exception behavior, cannot be reduced to one fixed
   barrier result. Its object-domain semantic hook/body remains authoritative
   for the wider path.

### State carrier follows semantic mutation reachability

A CLR-generic owner does not imply a `!T` field. Kotlin permits a hostile but
legal covariant owner to accept `@UnsafeVariance T` through a widened view,
store an incompatible object, and fail only when a later exact consumer casts
the read. For `C<int>`, a `!T` field would reject the write early and therefore
change observable Kotlin behavior.

The planner must construct a field-access graph from every typed and semantic
body before selecting storage:

- a field may use `!T` only when every write is proven compatible with the
  physical construction and no open semantic entry can mutate it;
- any field reachable from a widened semantic write uses one object/semantic
  carrier, with natural typed accessors performing the use-site conversion;
- typed and semantic entries always address that same field; a shadow typed
  field, copy, wrapper, or delayed synchronization is forbidden; and
- if an exact C# field (rather than typed property/method access) is part of
  the required surface, the declaration is unadmitted instead of publishing
  two states or changing failure timing.

This reachability classification is producer-owned and belongs in the binding
schema. Consumer substitution and devirtualization may optimize a proven
typed-only field but cannot revise its cross-assembly carrier.

An object-carried field also widens the output override problem. A semantic
read after an incompatible write must return the raw stored object, while a
typed `Read(): !T` converts and may fail. A C# override of only the typed read
cannot automatically define the semantic read. The admission planner must
therefore classify the paired output family and its C# obligations together
with storage. If normal open C# override behavior cannot remain coherent, the
declaration is rejected from direct reified admission rather than publishing
two states or an override that disappears on widened Kotlin paths.

The prototype reuses `SpecialBridgeMethods.findSpecialWithOverride` rather
than copying a .NET list of collection names or inferring policy from one
annotation. Slot-domain classification still has to cover non-JVM-specific
general and nested broad methods. The physical binding records barrier versus
semantic policy so a consumer never reconstructs it from a method name.

The first executable classifier is now present as a production-inert lowering.
It reuses that shared special-method registry, recursively applies Kotlin
declaration/use-site variance to direct member inputs, identifies explicit
owner-parameter `T?` in fixed supertype rows, and records direct semantic
field writes plus open typed outputs. It can return only:

- blocked by a metadata-fixed conditional supertype;
- blocked by semantic state plus open-output coherence;
- semantic state proof required; or
- complete field-access graph required.

None of those outcomes authorizes a CLR-generic TypeDef. The current emitter
does not read the plan, and the following lowering still assigns every local
Kotlin generic class to the erased owner ABI. Direct-body evidence is the first
input to step 5 below; it is not the complete cross-call/cross-assembly graph.

The classifier now also constructs detached IR members for those roles. Typed
entries retain owner-dependent explicit parameters/results; semantic hooks and
capability dispatchers erase only that explicit domain while keeping the real
generic owner receiver. Immutable pipeline snapshots additionally record
masked-default ownership, actual direct-`super` calls, producer logical keys,
and the one required state carrier. The test fixture asserts the hostile
nullable-derived and unsafe-store snapshots across all eight Kotlin lanes, and
the test-owned CLR physicalizer asserts the matching GenericParam,
InterfaceImpl/MethodImpl, field, visibility, virtual-slot, and override facts.
No detached member is present in `IrClass.declarations`, no emitter consumes a
snapshot, and `dotnet.ir` gains no speculative node: its programme requires a
real migrated production producer/consumer, which this architecture phase
deliberately does not have.

The former direct-body evidence is now a complete local producer graph. One
module scan records every function and constructor, general function-access
edge, field initializer, and anonymous initializer; owner plans project their
own fields from that shared graph. Private helpers are strict nodes and become
semantic-reachable only through a broad exposed entry. The hostile graph pins
the lowered chain `writeUnsafe -> installUnchecked -> <set-stored> -> stored`,
so a hidden setter cannot make widened mutation disappear. It separately pins
typed read-only private state and now classifies every direct write value as
physically typed, semantic object, or unresolved. The analysis propagates
callable-boundary seeds through call arguments, local definitions/
assignments, returns, and casts. A cast does not refine provenance: the new
exact hostile store proves `T -> Any? -> T` typed because every producer is
exact, while the covariant unsafe store records the identical final cast as
semantic because its exposed producer is widened. Any mixed semantic path
selects object state; unresolved paths retain the typed-value-provenance
obligation, and non-private state remains behind the explicit cross-assembly
obligation. No carrier classification authorizes a reified owner.

The test-owned physicalizer now consumes the snapshot rather than merely
checking an independent hand-written shape. Recorded state and member roles
generate a temporary generic producer; a separate C# assembly subclasses it
and verifies compatible typed overrides, incompatible semantic hooks, paired
outputs, one state, GenericParam, and explicit interface dispatch on both CLR
profiles. Production emission and `dotnet.ir` remain unchanged.

The detached family graph now extends through local Kotlin generic subclasses.
Typed prototypes override only typed prototypes; an inherited semantic hook
adds and overrides the corresponding semantic prototype even when the derived
source body is locally strict. Capability dispatchers remain private/final and
never become override targets. An external generic base cannot supply a local
prototype, so the consumer snapshot records its logical member key and the
owner remains `REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA`. No MethodDef name,
today’s erased slot, or consumer substitution is accepted as a replacement for
the producer-selected physical family record. The first such record now exists
only in the architecture channel. Its versioned, deterministic artifact is
fingerprinted to the temporary producer and supplies owner/capability paths,
arity, disposition, state requirements, complete roles/reasons, exact selected
method names, and slot dispatch. It is wholly decoded before binding and
rejects stale, truncated, wrong-producer, duplicate, incomplete, and missing-
member input. The resolved consumer acquires separate typed and semantic
targets; the final dispatcher remains outside the override set. Production
artifacts and codegen remain erased.

### Nested and mixed domains

A parameter `Collection<T>` is not automatically strict merely because it
contains `T`. Variance, projections, and the member's declared use determine
whether the caller may provide elements outside physical `T`. Each nested
owner is classified recursively.

A method with both strict and candidate positions classifies them
independently. One broad candidate position is enough to require a semantic
body capable of seeing that value. The implementation must not choose one
bridge direction for an entire class or method merely for convenience.

## Override and dispatch invariants

Every admitted implementation must preserve these invariants:

1. Exact typed calls, capability calls, defaults, and `super` reach the
   behavior selected by the Kotlin override graph.
2. A compatible capability argument observes the most-derived ordinary C#
   typed override.
3. An incompatible widened candidate reaches the recorded type-safe barrier
   or semantic hook without a typed cast; only a semantic-policy slot may be
   customized through an honest semantic override.
4. Interface `MethodImpl` rows connect dispatchers/slots; they never bind a
   broad capability directly to a narrowing typed member.
5. Receiver and argument evaluation, object identity, exception identity, and
   successful mutation are unchanged by choosing an exact fast path.
6. There is one authoritative state. Typed and semantic methods cannot own
   synchronized duplicate fields.

The eventual C# surface must name and document semantic hooks clearly enough
that a C# subclass author can reason about them. If the required surface is
too surprising, explicit export or an erased owner may be better for that
declaration family.

The direct integration probe
`testReifiedGenericOwnerSemanticDispatchShape` validates this mechanical
shape on CLR 4 and CoreCLR. One `HostileOwner<T>` implements an explicit
non-generic capability while retaining typed virtual read, write, and
candidate members. The probe covers `int`, `Nullable<int>`, `string`, a user
struct, null, incompatible candidates, strict capability writes, typed return
boxing, a typed-only C# override, a multi-level typed override, and a
Kotlin-like broad semantic override. Compatible capability calls observe the
most-derived typed override; incompatible candidates reach the semantic hook
without throwing.

This establishes CLR dispatch feasibility only. Compiler-produced member
families, Kotlin override graphs, `super`, interface defaults, reflection,
physical bindings, and separate Kotlin/C# assemblies remain required evidence.
The probe exercises the semantic-hook policy; a compiler-produced shared
type-safe-barrier family remains separate evidence.

## Cast and runtime classification matrix

| Kotlin operation | Runtime evidence | Required result |
| --- | --- | --- |
| `value is C<*>` | recorded open TypeDef ancestry or trusted `S(C)` binding | true for every construction/derived instance of this declaration |
| `value as C<*>` | same | same object or ordinary cast failure |
| `value as? C<*>` | same | same object or null |
| `value as C<X>` | exact requested construction when the cast attempts to check it | same object or classified `InvalidCastException`/`ClassCastException` |
| `value as? C<X>` | recorded open TypeDef ancestry or trusted `S(C)` binding; generic arguments are not a subtyping predicate | same semantic object or null; never an exact-carrier claim |
| ordinary variance/projection conversion | logical KLIB relation plus `S(C)` | same object; never an exact constructed-owner cast |
| unchecked incompatible exact cast | physical construction disagrees | may fail at the cast; never create a second view/store |

The prototype must distinguish a parameterized throwing cast whose failure
timing is implementation-defined from a safe cast whose generic arguments are
not checked with respect to subtyping. It may not generalize one operation's
platform freedom to another or claim stronger runtime checking in diagnostics
or reflection than it performs. See the
[platform-freedom ADR](../decisions/kotlin-semantic-authority-and-platform-freedom.md).

## Reflection and binding requirements

- The producer records the open `C<>` TypeDef, `S(C)`, exact/capability member
  pairs, slot-domain classification, and selected ABI carrier.
- Consumers use that binding and never derive a physical name from Kotlin
  fqName plus arity.
- `KClass` normalizes every construction and subclass ancestry edge to one
  Kotlin classifier.
- `KType` obtains logical arguments and nullability from KLIB, including when
  a runtime construction is a semantic fallback.
- Callable reflection exposes one Kotlin declaration per source member and
  routes invocation through the same dispatcher family.
- `S(C)`, dispatchers, and semantic hooks do not leak as Kotlin members.

### Draft physical binding shape

The binding codec is not changed during this architecture phase. A production
migration would require a new schema epoch whose logical class record carries
one `GenericOwnerBinding` equivalent with at least:

The architecture-only version-7 artifact proves the logical-key join,
producer fingerprint, owner/capability paths, arity, disposition, basic state
requirement, complete role set, selected MethodDef owner/name and dispatch,
slot-domain vector, and neutral structural signature. It is deliberately not
the production codec. Each capability dispatcher separately names the exact
non-generic interface MethodDef it implements, with an equal signature.
Role-specific direct-super targets and the distinct static masked-default
helper carry complete signatures as well. The recursive type vocabulary covers
built-ins, `!T`, `!!T`, producer/core/assembly named generic instances, and SZ
arrays without profile-specific IL spelling. Version 4 additionally records
the exact target profile, open-TypeDef runtime-classification mode, the strict
subset of admitted construction modes, each constructor MethodDef/visibility/
constructed owner and exact `this`/`base` edge, plus the selected field's
visibility/type and exact typed/semantic read/write paths and conversions. The
producer subset is only `STATIC_EXACT`; final-compilation runtime roots belong
to the separate consumer record below. Version 5 maps the producer's exact
open implementation TypeDef to the existing KLIB classifier key, declares the
KLIB logical graph authoritative for type arguments, hides the capability from
Kotlin classifier/member identity, and collapses every typed, semantic,
capability, and default-helper MethodDef family to one logical callable with an
exact invocation entry. Exact closed/open classifier normalization is separate
from ancestry-based logical instance classification. No Kotlin name, variance,
projection, nullability, bound, or logical type argument is reconstructed from
CLR metadata.

The producer-side signature source is now the compiler snapshot rather than a
hostile-name map. Exact constructor and member records are derived from lowered
IR through the admitted structural carrier grammar; the actual static default
dispatcher provides its parameter/mask tail. Semantic owner-dependent array
positions select `System.Array`, while an unsupported type or shape yields no
proof and can never be silently widened to `object`. Physical base names use
the ordinary compiler name plus uniform role suffixes. State access families
are selected by transitive field-read/write evidence. Rewriting all hostile
diagnostic source labels leaves the complete artifact equal, which pins
`sourceName` as diagnostic rather than carrier or binding authority. Physical
MethodDef identities must also be unique across logical families;
generated/user name collisions remain fail-closed.

The architecture-only consumer now derives one external Kotlin-subclass
physicalization from that complete record. Its own open TypeDef is explicitly
current-compilation scoped and cannot appear in a producer artifact. The
delegated constructor selects the immediate producer base, must have the same
complete admitted signature, and must forward every corresponding child
parameter unchanged; domain-vector agreement alone fails. Override
slots retain producer MethodDef names, signatures, declaring owners, and role-
specific direct-`super` targets while taking child visibility/modality from
compiler IR. A fake override may therefore call a MethodDef on an ancestor even
though construction names the immediate derived base. The hostile C# oracle
also derives a further generic grandchild from these slots and proves sealed/
virtual metadata and typed/semantic multi-level dispatch. This does not add a
production binding row or authorize a reified owner. Schema 6 additionally
records every producer GenericParam constraint row. The bounded child must have
the same compiler-derived row; matching arity with different constraints fails.
It must also be public, open, non-inner, and contain only one direct base, one
identity-delegating constructor, and no added interface, field, initializer,
nested type, state, or non-fake member. Inherited fake overrides stay inherited.

Schema 7 separates candidate classification from physical-family publication.
The producer catalogs every logically bindable generic owner with its arity,
disposition, and complete sorted constructor/member key sets. A physical
family must join that entry exactly, but a catalog entry may deliberately have
no family. The hostile metadata-fixed derived owner uses that latter state: it
remains objectively known and deterministically erased, and a consumer asking
for its physical family receives the recorded blocker rather than permission
to infer one.

Runtime roots do not belong in that producer artifact. A separate consumer/
application record now proves one bounded construction-site mechanism. The
final compilation supplies a finite set of concrete runtime types; the decoded
producer supplies the exact open owner, semantic capability, and strict public
one-`!T` constructor. The bounded fallback currently requires an unconstrained
owner parameter. Value roots map to `C<Nullable<V>>`, reference roots to
`C<R>`, and an already-nullable value root remains idempotently nullable. Every
branch is a statically visible construction selected by exact runtime-token
equality. One mandatory default `C<object>` route carries every unlisted value
or reference through `S(C)`. The record cannot express unbounded
`MakeGenericType`, and consumers cannot recover an exact carrier from the
fallback. Invalid/nested nullable roots and constrained owners reject the whole
plan. The hostile consumer executes all routes on both CLRs. The exact finite
corpus passes managed analysis with IL3050/IL2026 as errors and completes
signed-MSVC native link and execution. This remains bounded-corpus evidence.

| Field | Meaning |
| --- | --- |
| implementation owner path | the one open generic `C<>` TypeDef that owns state |
| semantic capability path | the non-generic compiler-ABI `S(C)` interface |
| CLR parameter arity | validation against the implementation TypeDef |
| CLR parameter constraints | ordered special/type constraint rows copied only after exact producer/child proof |
| runtime classification mode | recorded open-TypeDef ancestry, never name/arity guessing |
| construction modes admitted | static exact, runtime exact, semantic fallback, or a strict subset selected by the accepted ADR |
| profile/version epoch | fail-closed producer/consumer compatibility |

Each logical source member then carries one `GenericMemberFamilyBinding`
equivalent:

| Field | Meaning |
| --- | --- |
| logical owner/member key | join to the authoritative KLIB declaration |
| slot-domain vector | strict input, strict output, broad candidate, and mixed/nested positions |
| incompatible-candidate policy | none, shared type-safe barrier plus its result kind, or semantic body |
| typed owner and method | natural `!T` member used by exact Kotlin/C# calls, when present |
| semantic hook owner and method | object-domain Kotlin behavior, when required |
| capability dispatcher owner and method | implementation of the `S(C)` slot |
| capability slot identity | exact interface MethodDef implemented by the dispatcher |
| override-family identity | deterministic link across overrides, defaults, and inherited dispatchers |
| direct-super targets | exact producer-selected typed and/or semantic non-virtual targets |

Every field, property, parameter, return, array element, and nested physical
position whose carrier is not derivable from the logical signature also needs
a producer record:

| Carrier record | Meaning |
| --- | --- |
| exact static | the serialized physical type expression names `C<P(A)>` |
| semantic | the position is physically `S(C)` despite retaining logical `C<A>` in KLIB |
| runtime-selected exact behind semantic | the value is carried as `S(C)`; the current proof admits only a finite final-compilation token table whose exact constructions are statically rooted |
| semantic fallback construction | the mandatory default `C<object>` route is recorded for validation but is never exposed as logical `A` or recovered as an exact carrier |
| state carrier | the one physical field plus exact typed and semantic access paths selected from producer mutation reachability |

The physical type-expression vocabulary belongs with the neutral physical
CLI/binding model, not in emitter-owned strings. Logical type arguments,
variance, nullability, projections, Kotlin bounds, and `KType` rendering do
not move into this record.

Consumers validate rather than infer:

1. the implementation path resolves to a generic TypeDef of the recorded
   arity and the capability path resolves to a non-generic interface;
2. the implementation TypeDef implements that exact capability;
3. typed, hook, dispatcher, and capability-slot signatures match their
   recorded slot domains;
4. a broad-candidate dispatcher is not the narrowing typed member itself;
5. every recorded `MethodImpl`, base construction, and direct-super target is
   physically present and profile-compatible;
6. all carrier type expressions reference selected assemblies and admitted
   open TypeDefs; and
7. a missing, duplicate, malformed, stale, or future record rejects the
   artifact before codegen.

The old typed-owner experiment recorded typed and canonical paths plus one
bridge method pair. That is insufficient for this model because it cannot
distinguish strict and broad slots, a semantic hook from a dispatcher, or a
producer-selected carrier. Reusing its encoding without a schema redesign
would recreate consumer guesswork.

## Admission algorithm for the prototype

For each generic owner declaration and each use:

1. Classify every type argument with the argument matrix.
2. Recursively classify nested owners and array/container positions.
3. Classify every member parameter/return as strict, broad candidate, or
   mixed, including variance and `@UnsafeVariance`.
4. Build the typed/semantic override family and reject the declaration if any
   source-legal call lacks a non-narrowing path.
5. Build the complete field-access graph from those families; select `!T`
   storage only when every write is compatible, otherwise select one semantic
   field and prove failure at the first exact consuming conversion plus
   output-override coherence.
6. Select a construction mechanism for open-nullable arguments. Runtime-exact
   routes require a finite final-compilation root table plus a recorded semantic
   fallback; unbounded reflection is not a mechanism. Prove the table on both
   runtime profiles and all AOT gates, then separately classify every metadata-
   fixed occurrence which runtime construction cannot repair.
7. Physicalize external Kotlin subclasses only from compiler source evidence
   joined to the completely decoded producer record; never infer a constructor,
   argument mapping, constraint compatibility, immediate base, slot, or direct-
   `super` target from names, arity, or domains alone.
8. Select carriers from producer provenance and recorded bindings, not the
   consuming expression alone.
9. Prove casts, reflection normalization, arrays/joins, C# inheritance, and
   separate compilation on the hostile matrix.
10. If any proof fails, retain the accepted erased owner for the whole
   declaration. Do not publish a partial CLR-generic ABI.

Only after this algorithm is executable and the hostile owner passes may the
draft ADR choose between dynamic exact construction, semantic fallback, or a
more constrained admission set.
