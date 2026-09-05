# Draft ADR: one natural CLR-generic interface plus semantic routing

- Status: **Draft — replacement hypothesis, production-inert**
- Scope: Kotlin-owned generic interfaces, their natural CLR contract, Kotlin
  semantic views, ordinary CLR-language implementations, inheritance, and
  atomic migration
- Current production authority:
  [erased generic-interface identity](generic-interface-erased-identity.md)
- Shared physical model:
  [generic-owner physical authority and value provenance](draft-adr-generic-owner-physical-authority.md)

## Context

Kotlin declaration-site variance and the CLR variance rules are similar but not
identical. Kotlin may declare an output-variant interface and still admit an
owner-dependent input through `@UnsafeVariance`:

```kotlin
interface Collection<out E> : Iterable<E> {
    fun contains(element: @UnsafeVariance E): Boolean
}
```

The CLR cannot place `Contains(!T)` on a covariant `Collection<out T>` TypeDef.
The rehearsal previously preserved CLR covariance by splitting one logical
Kotlin interface into:

```text
Collection<out T>                 output-safe natural members
Collection__KotlinExact<T>        exact input members
CollectionKotlinSemantic          broad Kotlin operations
```

That experiment proved that one object can support natural and semantic views
without proxy identity or shadow state. It also exposed a more serious cost:
the natural interface was not the complete implementation contract. An
ordinary C# class could claim `Collection<T>` while omitting `Contains(T)`, and
the missing obligation was found later through public-method convention or
generated hidden ABI.

The desired production architecture must make a normal CLR-language
implementation statically honest. Preserving host-visible covariance is not
worth making the primary interface incomplete.

## Decision hypothesis

An admitted Kotlin-owned generic interface has:

```text
one complete natural CLR-generic TypeDef
        +
one compiler semantic capability when Kotlin needs unnameable views
```

There is no invariant exact sibling in the desired ABI.

The natural TypeDef contains the complete exact-construction contract: every
abstract or default member which a foreign implementation must satisfy and
whose exact owner substitution has an honest CLR signature. Its physical
generic-parameter variance is weakened, per parameter, to the strongest CLR-
legal variance for that complete surface. Kotlin IR/KLIB retains the original
logical variance.

If the complete exact contract cannot be represented even with invariant CLR
parameters, that interface is not admitted to this generic-owner ABI. It
remains on the erased production representation or receives an explicit
adapter/export design. The compiler must not publish a reduced natural
interface and then recover required members by convention.

This is a replacement hypothesis for the existing exact-sibling rehearsal,
not a production ABI change. The old implementation remains comparison
evidence until the hostile replacement matrix and exact inverse pass.

## Why this is the natural Kotlin/CLR boundary

Kotlin/JVM keeps Kotlin variance in Kotlin metadata while Java normally sees
an invariant generic owner and a complete Java interface contract. JVM bridge
lowering supplies representation adapters; Java implementors do not implement
a hidden Kotlin interface to become valid. The JVM collection mapping likewise
routes Kotlin calls through actual `java.util.Collection` members and generates
special bridges where Kotlin and Java signatures differ.

The CLR can retain more physical generic information than the JVM, including
value-type constructions. The target should use that strength, but it should
not invert the authoring rule: ordinary CLR metadata remains the implementation
contract, and compiler ABI remains an implementation technique.

## Physical variance selection

Physical variance is selected independently for every owner parameter over the
complete interface family.

1. Begin with no stronger variance than Kotlin declares. An invariant Kotlin
   parameter is never made variant merely to improve C# assignability.
2. Inspect every emitted natural member parameter, result, property accessor,
   method-generic constraint, inherited construction, and relevant MethodImpl
   obligation using ECMA-335 polarity.
3. Inspect nested constructed types using their actual physical variance. A
   class and an invariant interface reverse neither polarity; they require both.
4. If a declared `out` or `in` parameter occurs illegally anywhere in the
   complete surface, weaken that physical parameter to invariant.
5. Recompute inherited and nested legality to a family fixpoint. Weakening a
   parent may force a child parameter to become invariant.
6. Record the selected physical variance and complete member family in the
   producer ABI. Consumers bind it; they do not repeat the analysis from a
   substituted logical type.
7. Reject the declaration if the invariant result still requires a fabricated
   construction, an unrepresentable CLR signature, or an incomplete contract.

`@UnsafeVariance` does not remove the member. It is evidence that Kotlin's
logical declaration uses its parameter outside its declared position, which
normally forces that physical parameter to invariance.

Expected initial Runtime shapes are:

| Kotlin declaration | Natural CLR shape |
| --- | --- |
| `Iterator<out T>` | covariant `Iterator<out T>` |
| `Iterable<out T>` | covariant `Iterable<out T>` |
| `Collection<out T>` | invariant `Collection<T>`, including candidate members |
| `Set<out T>` | invariant `Set<T>` through complete `Collection<T>` |
| `List<out T>` | invariant `List<T>`, including index/candidate members |
| `ListIterator<out T>` | covariant when its complete surface remains output-safe |
| mutable collection interfaces | invariant, as their logical contract already requires |
| `Map<K, out V>` | invariant `Map<K,V>` because `containsValue(V)` is required |
| `Map.Entry<out K, out V>` | covariant when output-only |
| `Sequence<out T>` | covariant when its complete surface remains output-only |

These are consequences of the structural algorithm, not declaration-name
exceptions. A custom interface with the same member graph receives the same
shape.

Physical invariance does not mean erasure. `Collection<int>`,
`Collection<string>`, `Map<int,string>`, `!T` fields, and typed calls remain real
CLR-generic constructions.

## The natural interface is the complete authoring contract

For an admitted owner, an ordinary C#, F#, VB, IL, or other CLR type implements
the one natural interface and all of its members. The host compiler must reject
an omitted or incompatible member.

For example, the intended C# shape is conceptually:

```csharp
sealed class IntCollection : Kotlin.Collections.Collection<int>
{
    public int Size { get; }
    public bool IsEmpty { get; }
    public bool Contains(int value) { /* ... */ }
    public bool ContainsAll(Kotlin.Collections.Collection<int> values) { /* ... */ }
    public Kotlin.Collections.Iterator<int> GetIterator() { /* ... */ }
}
```

It does not need to be `partial`, run a source generator, implement a
`__KotlinExact` sibling, or name a semantic capability.

The implementation manifest may still record logical identity, exact MethodDef
locators, defaults, bridge policy, and compiler-owned semantic routes. It may not
turn a method outside the interface contract into a required obligation by
name, arity, or public-method convention.

## Semantic capability and ordinary foreign implementations

Kotlin logical variance, stars, projections, and value-type widening can create
a view for which no constructed natural CLR interface exists. A non-generic
semantic capability may carry those operations on Kotlin-produced objects. It
is compiler ABI, not a second Kotlin classifier and not a base interface of the
natural contract.

Kotlin-produced implementations may implement that capability directly. An
ordinary foreign implementation need not. When Kotlin uses such an object
through a widened or projected logical view, the compiler/runtime must derive
every mechanically available route from:

- the actual constructed natural interfaces and their MethodDefs;
- selected-view provenance when it remains available;
- the logical member's producer-recorded bridge/argument policy; and
- ordinary CLR type tests, conversions, and virtual interface dispatch.

For an output-only member, a preserved `Source<string>` construction can be
called directly under a logical `Source<Any?>` view and its result widened after
the call. This does not invent `Source<object>`.

For a broad candidate member such as `Collection.contains`, the route selects a
real implemented `Collection<T>` slot. A compatible candidate is converted and
sent through that interface MethodDef; an incompatible candidate receives the
Kotlin-recorded wrong-shape outcome such as `false` or `-1`. A general
`@UnsafeVariance` input with a `STRICT_OWNER_INPUT` domain uses an explicit
checked entry conversion; an incompatible value fails at that boundary. A
`BROAD_CANDIDATE_INPUT` on a foreign natural-only implementation may use the
same checked physical entry only when the producer policy says that compatible
values dispatch to the typed slot and incompatible values fail there. This does
not replace a broader Kotlin-owned semantic body: if the logical operation
promises such behavior, it is not invented from the typed slot and falls under
the explicit adapter/diagnostic rule below.

The runtime fallback must resolve real constructed interface slots and
MethodImpls. Searching a concrete class for a public method by name and argument
count is forbidden. Multiple matching constructions require preserved lineage
or a unique, policy-valid selection; interface enumeration order is never
authority.

For a producer whose final natural MethodDef is available, the bounded
reflection route carries that declaration directly. The caller emits an
`ldtoken method` operand for the exact open interface MethodDef, selects the
unique policy-valid closed interface construction actually implemented by the
receiver, and binds the two with
`MethodBase.GetMethodFromHandle(declarationHandle, construction.TypeHandle)`.
The resulting interface `MethodInfo` is invoked so ordinary implementations and
explicit `MethodImpl` rows remain CLR dispatch authority. A generic MethodDef is
closed only afterwards with its producer-bound method arguments. The one-handle
overload, a custom marker, a generated slot name, and runtime descriptor or
name/arity search are not equivalent substitutes.

This mechanism does not let a consumer reconstruct a declaration token from
logical IR. For an admitted bounded natural slot, the producer publishes a
self-sealing declaration record containing the final natural TypeDef and
MethodDef, method-generic binders, complete physical signature, parameter
domains, and result layout. A separately compiled consumer may use that record
only after it is validated against the containing producer DLL.

The declaration record exists independently of Kotlin implementation families.
An optional implementation-family seal must project exactly the same complete
natural slot, including TypeDef, MethodDef, logical domains, and result layout,
but is neither required for an interface-only producer nor a source of
declaration authority. The initial portable grammar covers directly declared,
constraint-free, root/edge-free producer and split-nullable producer slots whose
carriers remain declaration-local. It also covers a neutral direct-callable
slice with one or more CLR-legal strict owner inputs and a direct non-null
declaration-independent leaf result. That role contributes no second semantic
rule: ordered parameter domains and the result layout remain the authority.
`BROAD_CANDIDATE_INPUT` is not silently treated as strict by this neutral role;
it remains a separate semantic-route proof.
Missing natural `N` authority for inherited, constrained, edge-bearing, or
wider interface forms does not permit logical reconstruction or name/arity
fallback.

A separately inheritable Kotlin implementation class may additionally publish
the bounded standalone `M` seal defined by the physical-authority ADR. `M`
records the class MethodDef and its exact constructed natural-interface edge so
a later Kotlin subclass preserves that physical base slot. It neither replaces
the natural `N` declaration record nor creates a hidden interface, semantic
family, or MethodImpl. This is producer-recorded Kotlin authority which is
external to the consumer, not retained foreign CLR authority.

After the producer-DLL validation, a downstream compiler independently checks
the record against the logical KLIB declaration: instance shape, every
declaration-independent ordinary parameter carrier, direct owner-result
parameter index, and split-nullability must agree. KLIB can reject a bad
physical record; it cannot reconstruct one or replace its MethodDef identity.
Logical domains or nullability annotations likewise cannot distinguish two
logical claims on the same physical MethodDef row.

Concrete public decoys and same-name/same-regular-arity interface MethodDefs are
resolved by the complete recorded signature and exact declaration token. Static
lineage may later emit a direct interface call; bounded reflective invocation,
cache behavior, trimming, and NativeAOT remain deployment gates rather than a
new semantic contract.

If a semantic route cannot be derived from the complete natural contract and
recorded Kotlin policy, the target must expose an explicit adapter requirement
or reject that interop route. It must not silently require a hidden generated
interface from the foreign type.

## Optional generation

A Roslyn generator or equivalent tool may remain useful for:

- forwarding portable default implementations;
- emitting diagnostics and boilerplate; and
- adding explicit host-facing projections or conveniences.

Generation is an authoring convenience for declared natural obligations and
explicitly selected host adapters. Compiler/runtime code owns optimization of
semantic routing; foreign source generation does not implement hidden
capability ABI as a fast path. Correctness tests must always include a
non-partial, precompiled implementation with no generator.

If generation would need user-owned semantic state or a body which cannot be
derived from the natural methods, that is an explicit adapter design, not an
implicit completion of the natural implementation.

## Calls, defaults, and inheritance

- Exact Kotlin and CLR calls target the recorded natural MethodDef directly.
- Widened calls follow the shared physical-authority/provenance ADR. Logical
  semantics select the allowed route family; provenance may select only an
  already-guaranteed construction.
- Kotlin defaults have one authoritative body. Portable helper forwarding and
  modern default-interface-method placement remain profile decisions; adapters
  do not copy the body.
- Virtual dispatch and explicit interface implementations use MethodDef and
  MethodImpl identity, never current source spelling.
- A C# override/implementation of the natural slot must be observed by every
  Kotlin route derived from that slot. A semantic helper may not bypass it and
  fall back to a Kotlin base body.
- Base and inherited physical signatures remain authoritative after emission.
  A derived body receives a bridge where needed; substitution does not rewrite
  the base MethodDef.
- A downstream Kotlin override of a separately compiled Kotlin class binds an
  admitted producer-recorded `M` base MethodDef before applying logical
  substitution. Equal physical layouts use ordinary CLR override dispatch; an
  explicit MethodImpl requires independent authority.
- Diamonds, reabstraction, competing defaults, and multiple constructions are
  admitted only after their complete natural and semantic families are
  deterministic across separate assemblies.

## Callable contracts and split-nullable results

Parameter policies and result layout are independent components of the shared
callable contract. A direct logical `T?` result may use the producer-recorded
layout:

```text
!T Read(..., out bool isNull)
```

The payload is substituted from the physical owner/method expression, never by
remapping a later logical `IrType`. Owner-dependent inputs compose normally.
For example, the desired structural lookup shape is:

```text
parameter 0: STRICT_OWNER_INPUT(!K)
result:     SplitNullable(!V, out bool)

!V Get(!K key, out bool isNull)
```

No `Map`, package, member-name, or combined
`SPLIT_NULLABLE_WITH_OWNER_INPUT` rule is permitted. Exact `Map<int,int>` calls
remain unboxed. Whether an additional idiomatic C# `TryGet` export is desirable
is an export-surface question, not the Kotlin ABI calling convention.

## Identity, state, and casts

- Natural, semantic, star, projection, and widened logical views refer to the
  same receiver object. `===` does not depend on the selected route.
- An implementation has one authoritative state selected producer-wide. The
  interface model introduces no proxy, wrapper, shadow field, or synchronized
  duplicate store.
- A semantic route may use broader parameters or results; it does not globally
  erase unrelated exact receiver-derived state.
- BK-1 parameterized checks use one Kotlin-aware predicate for `as`, `as?`, and
  `is` where that decision applies. They may add a physical view only after an
  actual successful check.
- Valid Kotlin variance remains logically valid. Physical invariance changes
  routing and host conversions, not Kotlin's type relation.

## Imported CLR declarations are a different problem

An existing CLR-generic interface remains its native retained TypeDef with its
actual variance, constraints, MethodDefs, InterfaceImpls, and MethodImpls. It
does not acquire this Kotlin-owned natural/semantic family or require a Kotlin
manifest or generator.

CLR declaration-site variance is physically valid only for verifier-supported
reference-argument conversions. Kotlin must reject an implicit foreign
`IOut<int> -> IOut<object>` conversion even though its logical variance system
would otherwise accept it. It must continue to accept a real
`IOut<string> -> IOut<object>` CLR conversion. This requires a mandatory
physical-conversion gate; it is not solved by importing all foreign parameters
as invariant and not repaired by a semantic capability.

## Separate compilation and tooling

The producer records:

- the natural TypeDef and selected physical variance;
- every natural MethodDef/Property and generic binder;
- a self-sealing declaration record for each externally consumable natural slot,
  independent of optional implementation-family evidence;
- admitted standalone implementation-class MethodDef seals and their exact
  constructed natural-interface edges;
- semantic capability and hook identities where emitted;
- parameter policies and result layouts;
- default/helper and MethodImpl obligations; and
- the schema epoch needed to reject the former exact-sibling family.

A consumer binds these facts to the same assembly and metadata identities. It
does not reconstruct a hidden owner from a generated name or recalculate
physical variance from substituted KLIB.

Kotlin reflection normalizes natural and semantic physical declarations to one
logical Kotlin interface. CLR reflection truthfully observes the separate
compiler-ABI TypeDef where it exists. Compiler ABI must be marked and hidden
from ordinary browsing as far as CLR tooling permits.

Trimming and NativeAOT are freeze gates. Runtime interface selection must use
metadata and call paths which can be preserved and compiled ahead of time;
unbounded reflection or runtime code generation cannot be the only broad
foreign route.

## Rejected alternatives

### Keep the covariant natural interface incomplete

Rejected. It allows an ordinary CLR type to claim the Kotlin interface while
omitting required members and moves a static contract failure to runtime.

### Require an invariant exact sibling from foreign implementations

Rejected as the normal contract. It creates another public TypeDef, exposes
compiler ABI to C#, complicates reflection and inheritance, and still leaves
the primary interface incomplete.

### Require a source generator or partial C# type

Rejected. It excludes precompiled C#, F#, VB, IL, and non-partial types and
makes tooling the type-system authority.

### Recover members by public name and arity

Rejected. It is structural duck typing, cannot faithfully handle overloads or
explicit interface implementations, and is fragile under trimming and AOT.

### Erase every generic interface

Retained as the binding production fallback, not selected as the desired
endpoint. It is simple and semantically robust but loses direct `I<T>` identity,
typed value constructions, and natural CLR-language authoring.

### Preserve covariance through a separate host projection

Deferred to explicit export. A deliberate read-only covariant C# projection may
be valuable, but it must not be confused with the complete Kotlin interface or
force its implementation contract to split.

## Required hostile evidence

Before this hypothesis can replace the exact-sibling rehearsal, tests must
cover structurally custom owners and the Runtime family across both parsers,
Framework 4.8, .NET 10, separate assemblies, trimming, and NativeAOT:

1. output-only covariant and input-only contravariant roots retain CLR variance;
2. one illegal member weakens only the affected parameter to invariant;
3. inheritance/nested constructions reach the same variance fixpoint in producer
   and consumer;
4. complete C# and at least one non-C# CLR implementation are accepted without
   generation, while an omitted member fails in the host compiler;
5. exact reference and value calls use natural MethodDefs without boxing;
6. widened output calls preserve selected construction and virtual dispatch;
7. broad candidate calls produce Kotlin wrong-shape outcomes and call explicit
   foreign interface implementations when compatible;
8. general unsafe inputs fail only at the permitted bridge boundary;
9. stars, projections, nullability, value classes, multiple owner/method
   parameters, and split-nullable results compose;
10. one object implementing two constructions is deterministic with lineage and
    fails closed without it;
11. Kotlin/C#, C#/Kotlin, defaults, diamonds, and deeper inheritance preserve
    MethodDef/MethodImpl authority;
12. ordinary C# overrides are observed through widened Kotlin routes;
13. no runtime public-name/arity fallback, proxy, wrapper, or shadow state is
    present;
14. schema mismatch and partial old/new interface families fail closed; and
15. the exact erased-production inverse produces the pre-rehearsal surface.

The first implementation proof must use a custom interface family. It must not
advance the stdlib census merely to make the next current failure green.

## Migration

1. Record this natural-interface hypothesis and the shared authority/provenance
   model without changing production.
2. Add a shadow variance planner over the complete existing rehearsal family.
3. Prove a custom interface whose logical covariance is physically weakened by
   one exact input, including plain C# implementation and widened Kotlin calls.
4. Route broad foreign operations through recorded interface MethodDefs and
   policy, never concrete method convention.
5. Rehearse Collection/Set/List/Map and inheritance only after the custom proof.
6. Remove exact-sibling TypeDefs, manifest roles, runtime lookup, and generated
   adapters atomically from the rehearsal once every old positive behavior is
   explained or deliberately rejected.
7. Run the complete Runtime/Stdlib, separate-compilation, reflection, C# tooling,
   performance, trimming, NativeAOT, erased inverse, and rollback gates.
8. Only then make a GO / CONSTRAIN / NO-GO decision for one atomic production
   cutover.

## Consequences

- Ordinary CLR-language implementations receive one honest generic interface
  contract and no hidden admission protocol.
- Exact typed calls and fields remain real CLR generics even where physical
  declaration variance is weakened.
- Kotlin retains logical variance through compiler routing and semantic
  capabilities where the CLR cannot name the view.
- Some raw C# covariance is lost for input-bearing Kotlin interfaces. Output-
  only interfaces retain it, and an explicit host projection may later restore
  a read-only convenience surface.
- The design removes one permanent TypeDef/member family and the natural-only
  public-method convention from the desired ABI.
- The remaining hard problem is explicit and testable: efficient, AOT-safe
  compiler-derived routing for ordinary foreign implementations under a broad
  Kotlin view.

This is a **GO** for replacing the exact-sibling rehearsal in a custom,
production-inert proof. It is not a GO for production migration.
