# ADR: declaration-owned type-use annotation reflection

- Status: **Accepted — pre-ABI**
- Date: 2026-08-11
- Scope: `KType.annotations` for declaration-derived Kotlin types
- Does not enable: a Common reflection API change, arbitrary CLR nested
  type-use attributes, `KClass.supertypes`, or annotated `typeOf` results

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context and target precedent

Common `KType` exposes classifier, arguments, and marked nullability. JS,
Wasm, and Native retain that floor. JVM deliberately adds `KAnnotatedElement`
to its platform `KType` actual and exposes runtime type-use annotations on
declaration-derived return, parameter, receiver, supertype, type-argument,
and upper-bound types.

JVM does not make every annotated source type observable: annotations written
inside `typeOf<...>()` and substituted reified type arguments are currently
absent from `KType.annotations`. Type-use annotation reflection is therefore a
JVM platform extension over declaration metadata, not part of Common's
minimum `KType` contract.

The .NET target has already selected a JVM-shaped optional reflection product
with `KAnnotatedElement`, executable annotation values, callable return and
parameter types, callable type parameters, and one KLIB-derived `KType` graph.
Kotlin-produced declaration types retain type annotations in semantic IR and
embedded KLIB. ECMA-335 custom attributes, however, attach to metadata owners;
the CLR has no Java-like general type path for an arbitrary annotated nested
generic use.

## Decision

### Add the platform reflection capability only to .NET

The .NET `KType` actual extends `KAnnotatedElement`. The .NET-specific
`KTypeImpl` stores one read-only `List<Annotation>` beside classifier,
arguments, and nullability. Common, JS, Wasm, and Native declarations and
implementations remain unchanged.

Every declaration-derived `KType` node receives the runtime-retained Kotlin
annotations attached to that exact semantic `IrType` node. Root annotations
do not absorb annotations from a nested type argument, projection, receiver,
or upper bound. Repeated applications retain declaration order. `BINARY` and
`SOURCE` applications do not become runtime values.

The existing annotation implementation and value algebra construct the
objects. Type reflection must not add a second annotation constructor,
default-value decoder, or list implementation.

### Keep `typeOf` aligned with JVM

`typeOf<@A T>()` and annotations introduced only through a reified type
argument produce no `KType.annotations`, including on nested arguments. The
shared .NET `KType` graph builder therefore has an explicit
declaration-annotation mode rather than implicitly copying annotations for
every caller.

This preserves the observable JVM boundary and avoids making annotation
survival depend on shared-inliner details. A future Kotlin-wide change to
annotated `typeOf` should be adopted from Common/JVM rather than invented by
this target.

### Preserve one logical authority

For Kotlin-produced libraries, attached IR/KLIB type annotations are the only
authority. Physical CLR custom attributes are optional foreign-language
projections and are never reopened to reconstruct Kotlin annotation values.

For imported CLR declarations, exact importer enhancement remains decisive.
Roslyn nullable attributes may affect the imported logical nullability of a
type, but they do not become `KType.annotations`. Method, property, parameter,
and generic-parameter custom attributes retain their own declaration owners.
The first tranche admits no invented mapping from those rows to an arbitrary
nested Kotlin type use. FIR's synthetic `FlexibleNullability`,
`EnhancedNullability`, and other internal-IR type markers are compiler
evidence rather than Kotlin annotation applications and are filtered by the
same boundary.

### Keep structural type behavior stable

Repeated `annotations` reads return the same list object stored by the
`KType`. Adding annotations does not change the target's established
classifier/arguments/nullability equality, hash, or rendering behavior. This
matches the useful JVM separation between structural type identity and the
additional annotation view.

### Version the physical capability

The runtime-owned physical `KType` interface now implements
`KAnnotatedElement` and gains the typed `annotations` getter. The Stdlib
factory accepts the corresponding annotation array. These are compiler,
runtime, and Stdlib ABI changes and advance the runtime-surface level.

## Design attack

### Add `annotations` to Common `KType`

Rejected. Common and three mature target families do not promise this API.
The JVM precedent is explicitly platform-specific.

### Emit every Kotlin type annotation as a CLR custom attribute

Rejected. ECMA-335 has no general nested type-use owner/path equivalent. A
nearby return or parameter row would change ownership and flatten annotations
from distinct type nodes.

### Decode the produced DLL at reflection time

Rejected. Kotlin libraries already carry the exact KLIB graph, while physical
signatures erase or project Kotlin facts. Runtime decoding would create a
second weaker authority and would fail for nested uses with no CLR carrier.

### Populate annotations for `typeOf`

Rejected for this tranche. It would exceed the observable JVM behavior and
make a .NET-only promise about annotations that the Common API does not make.

### Flatten nested annotations onto the root `KType`

Rejected. Type position is semantic information. `List<@A String>` is not the
same annotation owner as `@A List<String>`.

## Ownership

- Common Kotlin and KLIB: type syntax, annotation targets, retention, values,
  declaration signatures, and exact nested type graph.
- .NET reflection IR: select declaration-derived types and attach each
  executable runtime annotation list to the exact `KType` node.
- `Kotlin.Runtime`: the cycle-free physical `KType : KAnnotatedElement`
  capability and read-only list interface.
- `Kotlin.Stdlib`: the .NET-specific `KTypeImpl` state and ordinary annotation
  objects/list behavior.
- CLR importer/exporter: exact foreign enhancement and optional standard
  metadata projections, never Kotlin round-trip authority.

## Verification

The completion gate must cover:

- root, nested argument, projected argument, parameter, receiver, and generic
  upper-bound positions reachable through the currently admitted reflection
  APIs;
- repeat order, runtime retention, and exclusion of binary/source values;
- stable list identity and unchanged structural `KType` equality/hash;
- empty annotated and nested `typeOf` results, matching JVM;
- separate producer/consumer KLIB compilation;
- absence of nullable/host custom-attribute leakage into Kotlin annotation
  lists;
- exact physical `KType : KAnnotatedElement` and factory signatures;
- runtime-surface mismatch rejection; and
- PSI and LightTree on Framework CLR and CoreCLR.
