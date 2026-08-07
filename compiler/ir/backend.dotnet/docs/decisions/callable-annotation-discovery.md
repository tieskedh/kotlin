# KLIB-first callable annotation discovery

- Status: Accepted (pre-ABI)
- Scope: runtime-retained annotations exposed by `KCallable.annotations` on
  callable and property-reference objects
- Depends on:
  [`valued-annotation-classes.md`](valued-annotation-classes.md),
  [`annotation-discovery.md`](annotation-discovery.md), and
  [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md)
- Does not enable: member enumeration, reflective invocation, callable
  signatures, parameter/type-use annotations, accessor objects, or typed
  foreign attribute declarations

## Context and target precedent

Common `KCallable` promises only `name`. JVM additionally makes `KCallable` a
`KAnnotatedElement`: Kotlin callables obtain declaration annotations from
Kotlin metadata and exact JVM members, while Java callables use Java
reflection. A Kotlin property and its getter/setter remain distinct annotation
owners. Native declares a `KAnnotatedElement` marker without an annotation-list
member; JS and Wasm retain the Common floor.

The useful JVM-shaped surface is implementable on CLR, but its authority rule
cannot be “read every custom attribute”. Kotlin-produced applications may be
KLIB-only and CLR rows are deliberately incomplete projections. Conversely, a
C# declaration has no KLIB and its exact MethodDef or Property row is the
authoritative source.

## Decision

### One platform surface on the existing callable object

The .NET actual `KCallable` extends `KAnnotatedElement` and exposes the same
read-only `List<Annotation>` transport as `KClass.annotations`. Generated
function references and property-reference wrappers retain that list on the
existing executable object. No reflection wrapper or second callable identity
is introduced, and ordinary invocation, equality, bound values, and caching
remain unchanged.

This is a platform extension above Common, following JVM where useful. It does
not make `KAnnotatedElement` or member reflection a new Common contract.

### Kotlin-produced declarations use KLIB-derived IR

Before callable lowering removes the exact reflection target, the backend
binds each reference to a private annotation producer derived from that target.
Only runtime-retained, executable Kotlin annotation applications are copied.
Their existing concrete annotation classes, defaults, arrays, enums, nested
values, and `KClass` values use the ordinary annotation lowerings.

For a dependency, the consumer obtains the declaration and its applications
from embedded KLIB and emits the same producer. No projected CLR row is read or
required. Removing all optional CLR custom-attribute projections from a
Kotlin-produced DLL therefore cannot change a Kotlin callable reference.

An empty target uses the runtime's empty read-only annotation list. `SOURCE`
and `BINARY` applications remain available to compilation through KLIB but are
absent from the runtime list.

### Property and accessor ownership stays distinct

A property-reference wrapper receives annotations from the `IrProperty`.
Its lowered getter and setter references may independently retain annotations
from their accessor declarations, but those values are not merged into the
property list. This follows JVM's declaration model and prevents a physical
CLR accessor method from silently redefining a Kotlin property application.

Constructor and function references likewise use their exact Kotlin
declaration owner. General accessor objects and accessor annotation queries
remain future reflection work.

### Foreign CLR declarations use exact metadata identity

An imported CLR callable retains its declaring TypeDef and exact MethodDef or
Property metadata token. The generated producer passes those identities to the
runtime, which searches only declared methods or properties and reads direct
custom attributes from the matching row. It does not match by source name,
signature guess, inherited member scan, or property/accessor conflation.

The returned `System.Attribute` objects use the already accepted physical
`kotlin.Annotation` carrier. Typed Kotlin construction or property access for
an attribute still requires a separately admitted foreign attribute importer.
No foreign lookup runs for a Kotlin declaration, so derived CLR rows cannot
duplicate KLIB-produced values.

## Design attack

### Decode CLR rows for Kotlin declarations

Rejected. Valid Kotlin applications can lack rows, and rows cannot represent
the complete Kotlin value/use-site grammar. This would make an optional export
projection the round-trip store.

### Discover a member by CLR name at runtime

Rejected. Overloads, explicit interface implementations, accessors, and future
name projection make names non-unique. The importer already owns exact metadata
tokens; discarding them would create avoidable ambiguity.

### Merge property and accessor annotations

Rejected. They are different Kotlin declarations and different CLR rows.
Convenient merging would make the result depend on physical lowering details.

### Add broad member reflection first

Rejected. A compiler-created reference already has an exact declaration owner.
It can expose annotations without defining `KClass.members`, lookup, parameter
objects, or general reflective calls.

### Treat flow/nullability attributes as restored Kotlin contracts

Rejected. A foreign CLR attribute may enhance an imported declaration under
the importer rules, and an exact standard attribute may be emitted as an
additive C# view. Neither path replaces the authoritative Kotlin contract or
KLIB application graph.

## Consequences and deferred work

- Callable references now carry one additional compiler/runtime ABI argument
  containing a Kotlin read-only annotation list.
- The monotone runtime surface level advances to 18; the Kotlin physical
  declaration-index schema is unchanged.
- Bound references may construct their annotation values with the reference;
  no global runtime metadata interpreter is required.
- Foreign lookup is token-exact but currently linear over one declared member
  family. Caching is a future non-observable optimization.
- `KCallable.returnType` and declaration-owned `typeParameters` subsequently
  landed through their own KLIB-first signature decisions. Parameters,
  visibility, owner, accessor objects, reflective call, and `KClass.members`
  remain unavailable.
- Parameter, receiver, field, accessor, and type-use annotation surfaces need
  their own exact owners before admission.

## Invariants

1. Kotlin-produced callable annotations come from KLIB-derived declarations,
   never from their CLR projection.
2. Kotlin and foreign CLR callables use disjoint authority paths.
3. A reference remains one object across execution and reflection views.
4. Property annotations never include getter or setter applications.
5. Foreign lookup uses the retained declaring type plus exact metadata token
   and returns direct attributes from that row only.
6. Removing CLR projection rows cannot change Kotlin-produced results.
7. The feature adds no member enumeration, signature reconstruction, or
   general reflective invocation.

## Verification

The gate covers runtime/binary/source retention; repeated declaration order;
empty, function, constructor, bound/unbound member, top-level property, and
mutable property references; invocation and mutation on the same objects;
property/accessor separation; separate Kotlin-library KLIB consumption; exact
C# method and property attributes; emitted token-based lookup; existing
callable identity tests; both FIR parsers; Framework CLR and CoreCLR; and the
full XML-audited target aggregate.
