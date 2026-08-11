# Direct property declaration facts and accessor objects

- Status: Accepted (pre-ABI)
- Initial library ABI version: 29
- Initial runtime surface level: 29
- Scope: direct `KProperty0`/`KProperty1`/`KProperty2` references, mutable
  counterparts, `isConst`, `isLateinit`, and JVM-shaped getter/setter accessor
  objects
- Does not enable: `KClass.members`, declared/inherited member lookup,
  `getDelegate`, field reflection, type-use annotation lookup, or a general
  runtime KLIB decoder

## Context

The .NET target already retains one exact `KProperty` object around its lowered
getter and optional setter callables. That object owns the logical property
name, return `KType`, parameters, type parameters, annotations, visibility,
modality, equality, invocation, and the original getter/setter execution
identities. What is still missing is the richer property-reflection surface
which exposes declaration flags and the accessors themselves as `KFunction`
objects.

Broad class-member enumeration is a different architectural problem. On JVM,
descriptor-less reflection reconstructs members from Kotlin metadata in the
dedicated reflection implementation; the JVM backend does not own that runtime
lookup. JS, Wasm, and Native retain the Common `KClass` floor and do not promise
JVM's member-container surface. Growing a KLIB decoder or reflective member
finder inside `backend.dotnet` merely because direct references already lower
there would therefore invert the selected ownership boundary.

Accessor objects do not have that problem. Their declaration target is already
present in the rich property-reference IR, and their execution is already
represented by the one property object. They are consequently a bounded
compile-time materialization feature and a prerequisite for any later member
enumeration owner.

## Authority and mature-target evidence

Common owns property-reference identity and the `get`/`set` behavior of
`KProperty0` through `KProperty2`. The additional accessor surface is
JVM-shaped:

- `KProperty.isConst` and `KProperty.isLateinit` report source declaration
  facts;
- every property exposes a `Getter`, and every mutable property a `Setter`;
- an accessor is a `KFunction` whose `property` points back to its owner;
- arity-specific accessors also implement the corresponding function type;
- getter parameters equal the exposed property receiver parameters;
- setter parameters append the setter value parameter; and
- accessor annotations and visibility belong to the accessor declaration, not
  to the property or its physical JVM/CLR method flags.

Native and Wasm supply the representation precedent already followed by the
.NET property lowering: one property wrapper delegates to ordinary lowered
getter/setter execution. The .NET implementation composes that wrapper model
with the JVM reflection contract; it does not copy JVM bytecode lookup or make
CLR reflection authoritative.

## Decision

### Logical surface

The .NET `actual` declarations publish the JVM-shaped `KProperty` declaration
facts, `Accessor`/`Getter`/`Setter` hierarchy, and arity-specific getter/setter
capabilities for arities zero through two. `getDelegate` is deliberately not
published by this tranche: delegate storage and accessibility have a distinct
semantic closure, and local delegated-property tokens currently retain an
explicitly weaker execution contract.

`isConst` and `isLateinit` come from the authoritative property IR. An imported
CLR property reports false for both because CLR `literal` fields are not CLR
properties and CLR metadata has no Kotlin `lateinit` declaration fact.

The callable ABI carried the `isLateinit` bit before executable `lateinit`
support so enabling the language feature would not require another reflection
representation. That feature is now complete: Common's lowering owns storage,
reads, failure, and `isInitialized`, while the existing exact IR declaration
bit makes positive `isLateinit` observations truthful. Ordinary, `const`,
local, and foreign property references continue to exercise the false case.
See [`lateinit-properties.md`](lateinit-properties.md).

### One execution identity

Each property wrapper creates and then retains exactly one getter and, for a
mutable property, one setter accessor. The accessor stores its owning property
and invokes that property's established `Get` or `Set` operation. It never
performs CLR member lookup and never stores a competing MethodDef, delegate,
receiver, or mutable state path.

This indirection is intentional. Property invocation, accessor invocation, and
later enumerated-property invocation must share virtual dispatch, bound
receivers, foreign binding, exception identity, and separate-library behavior.
Changing the property execution path changes all three rather than leaving a
reflection-only path stale.

### Callable facts

The compiler materializes the getter and setter callable signatures while the
exact property and accessor IR declarations coexist:

- getter name is `<get-name>` and setter name is `<set-name>`;
- getter return type is the logical property type;
- setter return type is `Unit`;
- receiver ordering follows the already selected property parameter order;
- the setter value parameter retains its source name, type, and annotations;
- type parameters and recursive bounds use the shared `KType` graph builder;
- annotations come from the exact getter or setter declaration;
- visibility and modality come from that accessor declaration; and
- `isInline`, `isExternal`, `isOperator`, `isInfix`, and `isSuspend` use the
  existing declaration-bit encoding. Impossible accessor flags remain false
  because the authoritative IR says so, not because the runtime assumes it.

Local delegated-property tokens receive exact synthetic getter/setter callable
shapes over their known value type, empty declaration annotations, null
visibility, and final modality. Calling those accessors preserves the existing
property-token failure rather than inventing access to captured delegate state.

A `const val` remains one CLR literal field with no public accessor MethodDef.
Like JVM, the private callable-reference getter body reads the retained literal
directly. This makes `::constant.getter()` executable without changing the
ordinary physical `const` ABI or issuing the invalid CLR `ldsfld` instruction
against a metadata-only literal.

### Identity and physical ABI

Repeated `property.getter` or `property.setter` reads return the cached object.
Accessor equality and hashing are based on accessor kind/arity plus owning
property equality, matching the JVM rule that accessors of equal property
references compare equal while getter, setter, bound, unbound, and distinct
arity views do not collapse.

The runtime owns non-generic physical accessor interfaces beside the existing
erased `KProperty` interfaces. Arity-specific nested interfaces remain distinct
function capabilities, so a zero-argument getter does not become a
`Function1` or `Function2`. Logical generic arguments remain in KLIB/IR only.
Because this built-in property family has dedicated erased runtime interfaces
rather than the ordinary split-generic ABI, user implementations receive the
normal covariant MethodImpl bridge from `KPropertyN.Getter` to
`KProperty.Getter` (and the corresponding setter slots).
The new interface slots, factory payload, and implementation classes advance
both the library ABI and runtime surface monotonically to 29; older producer or
runtime combinations fail at the existing version gate.

## Design attack

### Reconstruct accessors through `System.Reflection`

Rejected. CLR accessor flags, names, signatures, and attributes are physical
facts and may describe bridges or projections. They cannot recover bound
receivers, Kotlin parameter kinds, logical types, default semantics, or KLIB
annotations reliably.

### Reuse the lowered getter function object as the accessor

Rejected. The lowered execution object is intentionally only a `FunctionN` in
the property-wrapper model. Making it a `KFunction` would create reflection
identity for an implementation detail, lose the owning-property backlink, and
make generated adapters declaration authorities.

### Build broad member enumeration in the backend first

Rejected. It would place runtime KLIB decoding and member lookup in the codegen
owner, unlike JVM's reflection architecture. Direct accessors are independently
truthful and provide the callable objects a later dedicated enumeration owner
will consume.

### Publish `getDelegate` as returning null

Rejected. Null means a genuinely non-delegated property on JVM; using it for an
unsupported or inaccessible delegate would erase an observable semantic
distinction. Delegate discovery needs its own complete decision.

## Invariants

1. Property, getter, and setter execution share one underlying property path.
2. KLIB/importer IR owns declaration facts; CLR reflection owns none of them.
3. Accessor annotations never inherit property annotations accidentally.
4. A setter value parameter keeps its exact source name, type, and annotations.
5. Arity-specific function capabilities remain physically distinct.
6. Repeated accessor reads are stable and accessor equality composes property
   equality, including bound receiver values.
7. Local delegated-property accessors do not acquire hidden delegate access.
8. The tranche introduces no class-member lookup or runtime KLIB decoder.

## Verification

The feature gate covers both FIR parsers and both CLR profiles, plus separate
producer/consumer binaries where applicable:

- positive top-level/object `const`, positive executable `lateinit`, negative
  ordinary/local/foreign `isConst`/`isLateinit` flags, and literal execution
  without an accessor MethodDef;
- top-level, member, extension, member-extension, bound, and unbound references;
- `val` getter and `var` getter/setter access through `invoke`, `call`, and
  `callBy` for arities zero through two;
- names, return types, parameter order/kinds/names/types, annotations,
  visibility, modality, and function declaration flags;
- virtual override dispatch, mutation, target exception identity, and
  hand-written property implementations through both narrow and base slots;
- imported mutable CLR property getter/setter attributes, declaration facts,
  owner backlinks, invocation, and named invocation;
- stable repeated reads, equal independently-created property references,
  unequal getter/setter and arity views, and bound-receiver-sensitive equality;
- local delegated-property metadata plus preserved execution failure;
- physical nested interfaces, exact InterfaceImpl/MethodImpl rows, and
  ABI/surface skew rejection; and
- the strict aggregate target gate with a direct XML failure/error/skip audit.
