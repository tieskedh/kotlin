# Dedicated .NET class-member reflection

- Status: Accepted (pre-ABI)
- Initial library ABI version: 30
- Initial runtime surface level: 30
- Initial private member-factory protocol: 1
- Compact shared-thunk protocol: 2
- Compact protocol library ABI/runtime surface: 32
- First Stdlib catalog protocol: 1
- Stdlib catalog library ABI/runtime surface: 33
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

Protocol 1 was a semantic proof, not a decision to freeze one generated
callable class per reflected member. Applying that factory lowering to
ordinary target test producers demonstrated material, widespread executable
and textual-IL expansion. Protocol 2 replaces those per-member TypeDefs while
retaining the explicit producer opt-in until the remaining reflection-product
and foreign/mapped-classifier boundaries are closed.

Runtime surface and library ABI 31 centralize the common `KFunction` and
`KCallable` method bodies in `FunctionReferenceBase`. Generated direct/member
references now contain only declaration-specific invocation, bound-value,
default, vararg, and suspend hooks; property accessors reuse the same bodies.
The base does not itself implement `KFunction`, so a JVM-shaped adapted
`FunctionN`-only reference cannot acquire reflection identity merely by sharing
identity storage. This mature-target-aligned correction removed 693 repeated
lines from the five affected generated-IL baselines without changing logical
callable behavior.

A compact KLIB-derived producer descriptor plus reflection-product decoder, or
equally compact shared executable thunks, is required before member reflection
can become a default producer capability or enter ABI freeze. The replacement
must preserve this closure's exact member set, declaration graphs, callable
identity, invocation, and separate-compilation behavior while being compared
for producer size, trimming, NativeAOT behavior, startup, and invocation. This
is required architecture work, not an optional later micro-optimization.

### Compact shared-thunk protocol

Protocol 2 selects the shared-executable-thunk alternative for the admitted
Kotlin-produced closure. It does not make Runtime or the backend the owner of
member discovery:

- the producer still derives the complete logical member set from the exact
  post-KLIB class scope;
- `Kotlin.Reflection` still owns lookup, availability policy, and the public
  `members` result;
- Runtime supplies a fixed family of callable carriers shared by every
  producer; and
- each admitted Kotlin class supplies one private indexed dispatcher plus
  small direct-call MethodDefs, rather than one generated CLR callable class
  per function, getter, and setter.

The fixed carriers extend the established `FunctionReferenceBase`, implement
`KFunction`, and expose exactly the callable's execution arity through the
matching `Function0` through `Function22` capability or the big-arity
`FunctionN` capability. This follows the JVM requirement that a function
obtained through `KClass.members` remains invokable through its correct
function type. A carrier must not implement the wrong fixed arities merely to
reduce the number of Runtime classes; on CLR, ordinary interface tests are
physical and there is no JVM `FunctionWithAllInvokes` cast intrinsic to hide
that lie from Kotlin callers.

The carrier retains the same declaration id, flags, logical signature,
parameter factory, exact annotations, and empty-vararg value as a direct
reference. Its positional and masked-default calls enter the producer
dispatcher. The dispatcher performs ordinary IR calls to producer-owned
thunks; it never uses `System.Reflection`, a metadata token, a MethodDef name,
or a CLR signature to rediscover the target. Shared Common default lowering,
continuation lowering, virtual/interface dispatch, value representation, and
exception propagation therefore remain the only execution implementations.

Properties continue to use the established Runtime property wrappers. Their
getter and optional setter identities are shared carriers, so property
equality, accessor backlinks, mutation, and direct-reference equality retain
the existing implementation rather than acquiring a reflection-only path.

Protocol 2 is private and replaceable before ABI freeze. Its compactness
invariant is nevertheless explicit: for a producer class without unrelated
direct callable references, increasing the reflected member count may add
descriptor data, dispatcher cases, and MethodDefs, but must not add one CLR
TypeDef per member. Ordinary compilation still emits none of this support.

This protocol deliberately keeps executable thunks instead of selecting a
Runtime KLIB decoder now. A decoder remains a viable future size trade-off for
the optional reflection product, but only if it preserves direct invocation,
default and vararg behavior, suspend continuation behavior, exact target
exceptions, trimming/NativeAOT reachability, and equality with direct
references. Introducing a decoder merely to replace MethodDefs while falling
back to `MethodInfo.Invoke` is not equivalent and is rejected.

Kotlin Stdlib implementation classes are fail-closed in this first closure.
Generating direct references for every Stdlib member would make ordinary builds
depend on compiler-only annotations and internal callable shapes which are not
yet admitted, while omitting only those members would publish a false partial
view. Mapped built-ins and Stdlib member enumeration therefore advance together
in a later dedicated closure; ordinary user/library reflection does not wait for
it.

### Generated Stdlib member catalog

The first mapped/Stdlib closure advances both physical cases through one
catalog architecture. `kotlin.String` proves a Kotlin classifier whose runtime
carrier is the foreign `System.String` TypeDef; `kotlin.collections.ArrayList`
proves a Kotlin-owned Stdlib implementation class. The catalog machinery is
data-driven and must not acquire handwritten member descriptions as additional
classifiers are admitted.

The authoritative member set still comes from the Kotlin declarations visible
while `Kotlin.Stdlib.dll` is produced. For a mapped built-in that means the
compiler's Kotlin built-ins declaration, never enumeration of its BCL carrier.
For a Stdlib implementation class it means the actualized post-KLIB IR class
scope. Each admitted classifier either contributes its complete accessible
function/property set or has no catalog entry; the catalog never returns a
partial collection.

After KLIB serialization, Stdlib production emits one reserved product catalog
and producer-local direct thunks. The thunks enter the same callable-reference,
property-reference, default, suspend, value-representation, and compact shared-
carrier pipeline used by ordinary producer factories. A mapped member call is
therefore lowered through the target's existing exact built-in intrinsic or
physical mapping. No callable is implemented by `MethodInfo.Invoke`, and a CLR
method name or signature never becomes Kotlin declaration authority.

An inherited fake override remains the reflected declaration visible in the
selected class scope, while its resolved real override is only the execution
target. This is the same declaration/execution split used by JVM property
references. It matters on .NET when, for example, an `ArrayList` scope member
has the logical `Collection` receiver but its body is owned by
`AbstractCollection`; substituting the execution target as reflection identity
would corrupt signatures and equality, while invoking the fake receiver shape
would produce an invalid physical cast.

Catalog generation may expose runtime-retained annotations that earlier
Stdlib subsets never needed to materialize. Such an annotation is not filtered
out to make a member executable. Its authoritative shared source joins the
Stdlib product instead. The first closure therefore compiles Common
`ReturnValue.kt`, so `@IgnorableReturnValue` on mutable-collection members is
both a real Kotlin annotation class and part of the reflected callable facts.

`Kotlin.Reflection.dll` owns lookup order. Its versioned provider asks the
Stdlib catalog first and then the ordinary producer-factory path. The catalog
returns an array only for an exact Kotlin `KClass` identity and the reflection
product exposes it through an ordinary read-only Kotlin collection. Unsupported
Stdlib/mapped classifiers continue to fail closed through the existing stable
unsupported-reflection result.

The catalog entry is CLR-public compiler ABI but Kotlin-internal metadata. It is
marked and hidden from ordinary C# discovery like other `@PublishedApi internal`
cross-assembly helpers. Runtime has no static dependency on Stdlib and does not
know the catalog shape. Removing `Kotlin.Reflection.dll` still makes the catalog
unreachable through `KClass.members` and changes no lightweight reflection
semantics.

This closure deliberately puts the derived executable catalog beside the
Stdlib code it invokes instead of teaching the optional product to decode
arbitrary embedded KLIB at runtime. A later reflection-owned descriptor decoder
may replace that private representation only if it preserves the same direct
execution, identity, trimming, and NativeAOT properties. It may not reconstruct
mapped Kotlin members by scanning `System.String` or another host carrier.

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
implementation methods. The generated Stdlib catalog supplies that mapping for
its explicitly admitted classifiers; the remaining mapped classifiers continue
to fail closed. Neither gap may be hidden by general `Type.GetMethods` or
`Type.GetProperties` enumeration.

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

### Invoke compact members through CLR reflection

Rejected. `MethodInfo.Invoke` would wrap target exceptions, make lowered
defaults and suspend continuations a second reconstruction problem, weaken
trimming and NativeAOT reachability, and make physical CLR rows authoritative
over the selected Kotlin declaration. An indexed direct-call dispatcher is a
real CLR-specific implementation technique; reflective member lookup is a
semantic change.

### Use one carrier that physically implements every fixed Function interface

Rejected for this target. JVM can put all invoke methods on one implementation
and make Kotlin function casts consult a separate arity intrinsic. The current
.NET fixed-arity model uses exact CLR interface capabilities for those casts
and calls. Advertising every `FunctionN` interface would make `is Function0`
and `is Function3` true for a reflected `Function2`, unless the target first
replaced that complete classifier/cast ABI. Fixed Runtime carrier classes are
bounded product cost and preserve the already accepted function model.

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
5. Kotlin and foreign authority paths remain disjoint; a resolved real override
   is an execution adapter and never replaces the reflected scope declaration.
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
10. Protocol 2 emits no member-specific callable TypeDef. Its producer
    dispatcher calls Kotlin declarations directly and is removable without
    changing public signatures, callable equality, object identity, or member
    semantics.
11. A mapped/Stdlib catalog entry is generated from one complete Kotlin class
    scope and guarded by exact `KClass` identity; a host carrier's CLR members
    never augment that set.
12. Runtime neither references `Kotlin.Stdlib.dll` nor interprets the Stdlib
    catalog. Lookup and collection projection remain optional-product policy.

Library ABI and Runtime surface 32 version the shared carrier factory and the
protocol-2 producer call. An old Runtime/new producer combination is rejected
at the existing surface-floor check instead of failing later with a missing
carrier MethodRef.

Library ABI and Runtime surface 33 atomically version the first Stdlib catalog
and the reflection-provider entry that can call it. The catalog itself is
Stdlib compiler ABI protocol 1; unsupported or mismatched product combinations
must fail during the existing product/surface checks rather than at a later
catalog MethodRef.

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
- foreign and unadmitted mapped/Stdlib classifiers fail clearly until their
  complete providers are selected;
- admitted mapped and Stdlib classifiers enumerate only their complete Kotlin
  scopes, preserve callable identity and direct invocation, and exclude
  unrelated methods present on the CLR carrier;
- the reflection product has only Runtime/Stdlib dependencies and those base
  products have no reverse AssemblyRef;
- ordinary and packaged reflection sources build the same optional
  `Kotlin.Reflection.dll` product;
- Framework CLR and CoreCLR use the same logical member set; and
- the full XML-audited target gate remains green.
