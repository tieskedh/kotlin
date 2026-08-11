# Dedicated .NET class-member reflection

- Status: Accepted (pre-ABI)
- Initial library ABI version: 30
- Initial runtime surface level: 30
- Initial private member-factory protocol: 1
- Scope: the owner, public boundary, and explicitly opted-in first executable
  closure for `KClass.members`
- Depends on:
  [`kclass-and-class-literals.md`](kclass-and-class-literals.md),
  [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md),
  [`callable-parameters.md`](callable-parameters.md), and
  [`property-accessor-reflection.md`](property-accessor-reflection.md)
- Does not enable: constructors, declared-member convenience APIs, fields,
  delegates, type-use annotations, unrestricted foreign-class import, or
  coroutine-aware reflection/export

## Context and target precedent

Common `KClass` promises classifier identity, names, and `isInstance`; it does
not promise member enumeration. JS and Wasm retain that floor. Native makes
`KClass` a `KDeclarationContainer`, but its container is only a marker and has
no `members` property.

JVM is the relevant precedent for a richer platform surface. Its stdlib
declares `KDeclarationContainer.members`, but the lightweight runtime throws
when the optional `kotlin-reflect` implementation is absent. The implementation
in `core/reflection.jvm` reconstructs logical callables from Kotlin metadata
and exact Java declarations. The JVM backend does not own runtime member
enumeration.

The .NET target already has exact direct callable and property-reference
objects. They preserve declaration-owned signatures, parameters, annotations,
visibility, modality, flags, invocation, accessors, and separate-compilation
identity. That makes them the executable result of enumeration; it does not
make `backend.dotnet` the correct owner of discovering which members a runtime
class exposes.

CLR reflection alone is insufficient for Kotlin-produced declarations. It
sees lowered MethodDefs, erased owners, bridges, helpers, and optional export
projections, but it does not reconstruct KLIB-only declarations, Kotlin
properties, fake overrides, declaration type parameters, or Kotlin visibility.
Embedded KLIB remains authoritative.

## Decision

### JVM-shaped public surface

The .NET `KClass` actual implements a .NET `KDeclarationContainer` with the
JVM-shaped property:

```kotlin
public val members: Collection<KCallable<*>>
```

The collection contains functions and properties accessible in the class,
including inherited members, and excludes constructors. It is read-only and
cached for one `KClass` object. Enumeration returns the already selected
callable/property representation rather than a second reflection-only wrapper.

### Dedicated optional product

Runtime member discovery belongs to an optional `Kotlin.Reflection.dll`
product, parallel in responsibility to JVM `kotlin-reflect`. Its Kotlin source
lives outside `backend.dotnet`. The reflection product may depend on
`Kotlin.Runtime` and `Kotlin.Stdlib`; neither product may depend statically on
`Kotlin.Reflection`.

`Kotlin.Runtime` owns only a small bootstrap/delegation seam required by the
physical `KClass` interface. It attempts to obtain the version-matched
reflection provider and otherwise throws a stable reflection-not-supported
error. It does not decode KLIB, enumerate CLR members, apply Kotlin override
rules, or manufacture callables itself.

This optional-product boundary is part of the architecture even while the
pre-ABI test product is assembled by target-owned build orchestration. Product
construction in the backend does not transfer ownership of reflection policy
to code generation.

### Kotlin-produced declarations

The first executable closure uses compiler-emitted private member factories
for ordinary user/library classes compiled with the experimental
`-Xdotnet-reflection` producer flag. Ordinary compilation does not emit those
factories. A reflection product presented with a producer that did not opt in
fails with the stable unsupported-reflection result; it does not infer a
partial answer from CLR metadata.

After KLIB serialization, the opt-in lowering derives each factory from the
exact KLIB-derived IR class scope and creates ordinary unbound function and
property references. Existing callable-reference lowerings then materialize
their signatures and execution paths. The factory is derived runtime support;
it is not declaration authority, public C# API, or a replacement for embedded
KLIB.

The reflection product locates only the reserved, versioned factory associated
with an exact Kotlin-produced class. It does not scan physical methods and
infer Kotlin declarations from names or signatures. A missing, malformed, or
version-incompatible factory fails closed.

Callable equality uses the declaration identity captured before mutable
backend lowerings. Only an upstream `IdSignature` that is `visibleCrossFile`
may supply the cross-module key. File-scoped identities use path-independent
local coordinates; absolute checkout, temporary extraction, or source-resource
paths must never enter emitted callable identity or deterministic IL.

Factories must cover the complete admitted Kotlin class shape rather than only
members mentioned elsewhere in the consumer. In particular, direct and
inherited functions, properties, overloads, overrides, generic substitutions,
member extensions, visibility, and mutable accessors remain available across
separate compilation. Constructors remain outside `members` by contract.

The executable-factory representation is selected for this opt-in closure
because it preserves exact callable behavior without moving a KLIB/protobuf
reader into Runtime. A later dedicated reflection-product decoder may replace
the private factory format only through a versioned ABI decision; it may not
move into `backend.dotnet` or make CLR metadata authoritative.

This is a semantic proof, not a decision to freeze one generated callable
class per reflected member as the final compact representation. Applying the
factory lowering to ordinary target test producers demonstrated material,
widespread executable and textual-IL expansion. The current private factories
therefore knowingly trade producer size for reuse of the one proven callable
implementation only when the producer explicitly opts in.

A compact KLIB-derived producer descriptor plus reflection-product decoder, or
equally compact shared executable thunks, is required before member reflection
can become a default producer capability or enter ABI freeze. The replacement
must preserve this closure's exact member set, declaration graphs, callable
identity, invocation, and separate-compilation behavior while being compared
for producer size, trimming, NativeAOT behavior, startup, and invocation. This
is required architecture work, not an optional later micro-optimization.

Kotlin Stdlib implementation classes are fail-closed in this first closure.
Generating direct references for every Stdlib member would make ordinary builds
depend on compiler-only annotations and internal callable shapes which are not
yet admitted, while omitting only those members would publish a false partial
view. Mapped built-ins and Stdlib member enumeration therefore advance together
in a later dedicated closure; ordinary user/library reflection does not wait for
it.

### Foreign and mapped classifiers

Kotlin-produced factories and foreign CLR reflection are disjoint authority
paths, as they are for annotation discovery. The first closure fails closed for
foreign or mapped classifiers without a complete reflection-provider mapping;
it never returns an empty collection that falsely means the class has no
members.

Foreign support requires a separate complete family that constructs Kotlin
callables from exact CLR declaration identities and applies the same importer
enhancement rules used by compilation. Mapped built-ins likewise need an
explicit Kotlin declaration mapping rather than exposing arbitrary BCL
implementation methods. Neither gap may be hidden by general `Type.GetMethods`
or `Type.GetProperties` enumeration.

## Design attack

### Put member enumeration in `backend.dotnet`

Rejected. It would invert the JVM ownership precedent and turn code generation
into a runtime metadata implementation. A lowering may emit exact executable
artifacts; it may not own runtime discovery, caching, or lookup policy.

### Enumerate CLR MethodDefs and Property rows

Rejected for Kotlin-produced classes. Physical members include helpers and
bridges and omit Kotlin-only declaration structure. Filtering by names,
attributes, or current lowering conventions would make reflection depend on an
accidental CIL layout.

### Decode embedded KLIB in `Kotlin.Runtime`

Rejected. It would add serialization, symbol reconstruction, and callable
binding to the minimal runtime and create a Runtime-to-Stdlib/reflection
dependency cycle. Only the dedicated reflection product may later own such a
decoder.

### Generate factories in each consumer

Rejected. `value::class.members` must not depend on which consumer happened to
compile the query. Producer-owned support preserves separate compilation and
ensures that a dynamically obtained `KClass` observes the same declaration
set.

### Return an empty list for unsupported classifiers

Rejected. Empty means a supported class truly exposes no functions or
properties. Unsupported foreign, mapped, local, or version-skewed cases must
fail with a clear diagnostic until their complete mapping is admitted.

### Make reflection mandatory in Runtime or Stdlib

Rejected. JVM keeps full member reflection optional, and the capability carries
material code-size, metadata, trimming, and NativeAOT costs. Ordinary class
literals, `isInstance`, annotations, direct callable references, and `typeOf`
must remain usable without loading the full reflection product.

### Freeze the first executable factory as the final encoding

Rejected. It proves the semantic boundary with the existing callable pipeline,
but duplicates private executable support for members that may never be
enumerated. Compacting that support before the complete logical surface is
known risks either encoding too little authority or creating a second callable
implementation. The private versioned protocol remains replaceable pre-ABI.

### Emit executable factories in every producer

Rejected for the pre-ABI representation. The approach preserves semantics but
materially expands producers, including binaries that never use member
enumeration. Until a compact descriptor/decoder or shared-thunk protocol is
proven, only `-Xdotnet-reflection` may request the executable support. The flag
does not select different Kotlin semantics or a stable alternative ABI; it
temporarily exposes the complete semantic experiment.

## Invariants

1. Embedded KLIB/importer declarations remain the Kotlin semantic authority;
   CLR rows are physical evidence only.
2. `backend.dotnet` emits executable support but owns no runtime enumeration or
   KLIB decoder.
3. `Kotlin.Reflection` is optional and version-matched; Runtime and Stdlib have
   no static dependency on it.
4. Enumerated members use the established callable/property objects and their
   invocation paths, not reflection-only duplicates.
5. Kotlin and foreign authority paths remain disjoint.
6. Unsupported classifiers fail closed rather than returning a misleading
   partial or empty member set.
7. Removing `Kotlin.Reflection.dll` cannot change non-member `KClass`, direct
   callable, annotation, `KType`, cast, dispatch, or object-identity semantics.
8. A future optimization or decoder may be disabled without changing public
   DLL signatures, member identity/equality, invocation, or separate-compilation
   behavior.
9. Without `-Xdotnet-reflection`, ordinary producers emit no executable member
   factory. Enabling member reflection by default requires a compact protocol
   and a new explicit pre-ABI decision.

## First closure verification

The first complete gate must prove:

- absence of the optional product produces the stable unsupported-reflection
  failure without affecting lightweight `KClass` operations;
- ordinary CLI compilation emits no member factory, while
  `-Xdotnet-reflection` reaches the lowering and emits the versioned factory;
- declared and inherited functions/properties, overloads, overrides, mutable
  accessors, member extensions, generic owner/member parameters, and visibility
  facts use their exact Kotlin declaration graphs;
- invocation, `callBy`, mutation, exception identity, accessor backlinks, and
  equality agree with direct references;
- constructors and compiler-generated physical helpers do not appear;
- producer-created and dynamically obtained `KClass` values enumerate a
  separately compiled producer without consumer-generated tables;
- foreign, mapped, and Stdlib classifiers fail clearly until their complete
  providers are selected;
- the reflection product has only Runtime/Stdlib dependencies and those base
  products have no reverse AssemblyRef;
- ordinary and packaged reflection sources build the same optional
  `Kotlin.Reflection.dll` product;
- Framework CLR and CoreCLR use the same logical member set; and
- the full XML-audited target gate remains green.
