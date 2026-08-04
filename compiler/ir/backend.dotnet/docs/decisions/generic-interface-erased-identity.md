# ADR: erased ABI for Kotlin-owned generic interfaces

- Status: **Accepted — pre-ABI**
- Date: 2026-08-04
- Scope: Kotlin-owned ordinary generic interfaces, including member ABI,
  inheritance, variance, casts, defaults, implementation, separate
  compilation, and the default CLR surface

## Decision

A Kotlin-owned ordinary generic interface has one authoritative non-generic
CLR TypeDef, one declaration-erased classifier identity, and one erased virtual
slot family:

```text
Kotlin:  interface Source<out T> { fun read(): T }
CLR:     interface Source { object read__KotlinErased__<id>(); }
```

KLIB remains authoritative for the logical parameters, bounds, variance,
arguments, projections, nullability, members, and override graph. The physical
binding records the single interface owner and its erased member locations. It
does not record declaration-variant or invariant-exact sibling TypeDefs,
typed-view bridges, capability guards, or split-view intersection slots.

Erasure is the semantic runtime ABI, not merely the fallback ABI. Every
Kotlin construction and projection of one declaration uses the same physical
interface identity for storage, calls, inheritance, `is`, `as`, and `as?`.
Class and method implementations may require ordinary erased-signature or
covariant-return bridges, but those bridges satisfy slots on the one interface
hierarchy; they do not create another interface representation.

This rule applies to Kotlin-owned ordinary interfaces. Imported CLR generic
interfaces retain their native constructed identity and CLR variance rules.
An accepted built-in mapping may expose an additional exact host capability
where that capability is independently truthful, as Common `Comparable<T>`
does with `System.IComparable<T>`. An explicit .NET export may generate a
separate typed interface or adapter. Neither case turns the ordinary
Kotlin-owned interface declaration into a split runtime ABI.

This is the Kotlin/.NET target authors' pre-ABI decision. It follows the
single-semantic-identity architecture of mature Kotlin targets, but is not a
Kotlin core-team decision, KEEP, or official target commitment.

## Why

The mature targets retain one runtime interface identity per Kotlin
declaration:

- JVM emits one interface classfile. Its optional generic `Signature`
  attribute helps Java tooling, while executable descriptors and runtime type
  checks remain erased and bridges adapt refined implementations.
- JS uses one declaration/interface identity and one dispatch contract;
  generic arguments do not create runtime interface constructors.
- Wasm and Native assign runtime/interface-table identity to the erased
  declaration rather than to every generic construction.

CLR generic interface constructions are different physical interface
identities. There is no JVM-like signature-only generic layer over a
non-generic TypeDef: `Source<string>` and `Source<int32>` name distinct
constructed interfaces, while the open `Source<T>` TypeDef is itself generic.
Using those types as ordinary Kotlin identity would make value/reference
variance, stars, projections, and unchecked casts stricter than Kotlin's
declaration-erased model.

The previous candidate therefore emitted a non-generic canonical interface,
a declaration-variant generic sibling, sometimes an invariant exact sibling,
and member/implementation bridges between them. After Kotlin-owned generic
classes became physically erased, a representative implementor such as:

```kotlin
class Values<T> : Source<T>
```

cannot truthfully implement `Source<T>` because its CLR class has no `T`.
It can implement only the erased `Source`. Mapping the edge to
`Source<object>` would describe a different CLR capability and still fail for
value constructions. Consequently the typed path is absent precisely from the
ordinary generic implementations which dominate Common collections, while
every declaration still pays the TypeDef, metadata, lowering, default-body,
guarded-call, and maintenance cost.

Closed non-generic implementors can sometimes satisfy a typed capability, but
that fact does not justify publishing typed siblings for every declaration.
Host-friendly typed authoring belongs to an explicit export/mapping boundary,
where unsupported shapes can fail closed and the surface can be documented as
a .NET contract.

## Semantic contract

For a Kotlin-owned `Source<T>`:

- `Source<String>`, `Source<Int>`, `Source<*>`, and legal projected forms share
  one runtime classifier;
- subtype and projection conversions preserve the same object and never
  allocate an adapter;
- generic arguments affect compile-time operations and KLIB, not physical
  interface identity;
- a call through the interface executes the one erased slot and narrows or
  unboxes its result at the logical use site;
- an incompatible unchecked argument or result fails at the operation which
  consumes the wrong logical type, not at an earlier CLR capability probe;
- virtual dispatch, defaults, `super` selection, and intersections follow the
  Kotlin override graph; and
- separate producers and consumers bind through recorded physical owner and
  member identities rather than generated-name inference.

Kotlin's special collection bridges remain Common/shared-backend semantics.
Wrong-shaped `contains`/`remove` arguments and related sentinel-return members
use the accepted special-bridge table. Ordinary user members do not acquire a
collection barrier merely because their erased signatures look similar.

## Physical mapping and members

An interface-owned parameter in a member signature maps to:

1. its already accepted erased Kotlin carrier, when one exists;
2. an exactly representable erased upper bound; or
3. `System.Object`.

Nested owner-dependent constructions erase recursively only where their
physical carrier is already accepted. Unsupported open CLR constructions fail
closed rather than inventing `I<object>` or a closed generic class/interface.
Arrays retain their separately selected exact and erased carriers.

Methods may retain their own CLR method type parameters. A method bound which
depends on an erased interface parameter remains authoritative in KLIB and
omits the unrepresentable owner-relative CLR constraint. This is the same
class-versus-method ownership distinction used by erased generic classes.

Logical overloads which collide after erasure receive deterministic physical
names derived from the complete Kotlin signature. Existing authoritative
Common generator spellings may be projected by a bounded stdlib-owned table.
Names never depend on declaration order or the current overload set, and KLIB
plus the physical binding restore the Kotlin declaration.

A class or subinterface whose refined implementation no longer matches an
erased slot receives one ordinary forwarding bridge and explicit `MethodImpl`
where required. The bridge boxes, casts, or unboxes only at that slot boundary
and dispatches virtually to the selected Kotlin implementation. It is not a
typed interface-view bridge and no runtime capability probe precedes a normal
Kotlin call.

## Inheritance, intersections, and defaults

Generic interface inheritance is declaration-erased physically:

```text
Kotlin:  interface Child<T> : Parent<T>
CLR:     interface Child : Parent
```

KLIB retains the substituted logical edge. Repeated, diamond, projected, and
cross-module inheritance resolve through the Kotlin override graph. Distinct
parent TypeDefs retain distinct CLR slots; a selected body may satisfy them
through ordinary bridges/`MethodImpl` rows. The backend does not emit a typed
intersection TypeDef or a split-view intersection record.

On DIM-capable profiles, the default body lives on the one erased interface
slot. On portable/Framework profiles, the accepted helper and class-forwarder
policy implements that same logical default. There is no typed default owner
and therefore no canonical-versus-typed promotion or helper-backed correction
for an otherwise unreachable sibling.

## CLR mappings, imports, and exports

Imported `Foreign<T>` interfaces remain native CLR declarations. Kotlin may
use them only through the separately accepted importer rules; the importer
does not fabricate an erased Kotlin-owned identity for a foreign interface.

Built-in mappings are explicit exceptions owned by their mapping ADR. For
`Comparable<T>`, `System.IComparable` is the erased Kotlin carrier and an exact
`System.IComparable<T>` edge may be emitted only when the implementing CLR type
can name the complete truthful construction. An erased `C<T> : Comparable<T>`
does not fabricate `IComparable<object>`. Direct primitive/string semantics
continue through the mapping's Kotlin semantic boundary.

Ordinary Kotlin compilation does not silently publish `Source<T>` as a CLR
generic interface. A future explicit export may publish, for example,
`IReadOnlySource<T>` and generate an adapter to the Kotlin implementation. That
surface is a separate .NET contract, never Kotlin cast identity, and may have
its own documented adapter identity. Unsupported mutation, inheritance,
variance, projection, default, collision, or nullability shapes fail closed.

The supported C# rule remains:

> Kotlin interfaces remain Kotlin interfaces. C# consumes or implements only
> explicitly exported, safe .NET APIs.

Raw CLR tooling may see the erased implementation TypeDef, but it is not an
idiomatic typed C# contract. Generic C# source authoring against a Kotlin-owned
interface therefore waits for the explicit export product; the compiler does
not retain an implicit generic sibling solely as a generator opt-in token.
Non-generic Kotlin interfaces and explicit mapped/exported interfaces remain
eligible for their separately accepted authoring paths.

## Metadata and migration

The pre-ABI migration:

- bumps every affected physical/authoring schema;
- records one physical owner for a Kotlin-owned generic interface;
- removes declared/exact owner paths and split-view member records;
- removes implicit declaration-wide typed-view and intersection bridges/records;
- removes capability probes and guarded fallback call paths;
- moves default bodies to the one erased interface contract;
- stops advertising implicit generic C# authoring views; and
- rejects stale artifacts explicitly.

Nothing has shipped, so all compiler, runtime, stdlib, manifest, Roslyn
tooling, tests, and consumers move together without compatibility aliases.

## Optimization rule

CLR generics may still be used internally only where disabling the mechanism
does not change public/protected ABI, supported Kotlin/.NET reflection,
runtime casts, object identity, virtual dispatch, or cross-module observable
semantics. Private helpers and implementation metadata may differ. If a
mechanism changes one of those observations, it is a new ABI or explicit
export feature and requires its own decision.

## Rejected alternatives

- **One CLR generic interface only.** It has no universal construction for
  stars/value/open variance and would strengthen Kotlin runtime checks.
- **Treat `I<object>` as the erased identity.** CLR variance excludes value
  constructions and invariance/unsafe members; it is not `I<*>`.
- **Canonical plus declared/exact siblings for every declaration.** Rejected
  because ordinary erased generic classes cannot expose the typed edge, while
  all declarations pay the permanent second ABI and bridge cost.
- **Retain typed siblings only for locally closed implementors.** Physical
  declaration ABI would depend on the current implementation set and change
  across separate compilation.
- **Select typed calls through runtime guards.** A Kotlin operation must not
  acquire representation-dependent dispatch, early failure, or repeated
  capability checks merely because a non-authoritative sibling happens to be
  present.
- **Use the C# implementation generator as justification for the split.**
  Foreign authoring is an explicit export/tooling product and must layer over
  the Kotlin ABI instead of defining it.
- **Wrap ordinary Kotlin subtype conversions.** This breaks `===`, mutation,
  synchronization, cursor state, and foreign identity.

## Explicitly on hold

This decision does not authorize:

- typed C# export of generic Kotlin interfaces;
- automatic `IEnumerable<T>`/`IEnumerator<T>` collection projection;
- generic C# source implementation without an explicit exported contract;
- value-type foreign implementors or adapter identity rules;
- fun-interface/SAM export, suspend interfaces, or coroutine mappings;
- value classes, general reified inline support, or `KType`; or
- internal specialization with observable typed interface edges.

## Verification gate

The migrated ABI must cover:

- invariant, covariant, contravariant, mixed, star, and use-site-projected
  interfaces with reference, value, nullable-value, bounded, and open types;
- producer/consumer calls, same-object conversions, casts, and delayed
  incompatible argument/result failure;
- properties, method-generic members, owner-relative bounds, nested carriers,
  unsafe variance, and deterministic erased overload names;
- direct, repeated, diamond, intersection, refined-return, and cross-library
  inheritance;
- abstract and default members on every supported profile;
- generic and non-generic implementing classes, including exact closed
  implementations without an implicit typed sibling;
- Common iterator/collection implementations and special bridges;
- retained explicit `Comparable` host mappings and imported CLR generics;
- physical absence of declared/exact Kotlin-owned interface TypeDefs,
  capability probes, and split-view metadata;
- stale-schema rejection and self-describing separate-library binding; and
- C# consumption/authoring diagnostics which distinguish raw erased
  implementation types from explicit typed exports.

The implementation is complete only when the former declaration-wide
declared/exact TypeDefs, view bridges, intersection records, guarded calls,
default promotions, manifest contracts, and active documentation no longer
survive as behavior. Mapping-specific bridges for independently truthful host
capabilities are not part of that removed implicit ABI.
