# KLIB-first callable return types

- Status: Accepted (pre-ABI)
- Scope: `KCallable.returnType` on function, constructor, property, and local
  delegated-property reference objects
- Depends on:
  [`ktype-and-typeof.md`](ktype-and-typeof.md) and
  [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md)
- Does not enable: `KParameter`,
  visibility/modality flags, accessor objects, member lookup, reflective
  invocation, type-use annotation discovery, or a complete `kotlin-reflect`
  product

## Context and target precedent

Common `KCallable` exposes only `name`. JS and Wasm retain that floor. Native
adds exactly `returnType`, materializing a `KType` from the rich reference's
reflection target; its property wrappers expose the getter target's return
type, while local delegated properties retain their declared value type. JVM
also exposes `returnType`, but continues into the much larger `parameters`,
`typeParameters`, `call`, visibility, modality, and suspend surface.

The Native boundary is the smallest mature-target precedent that composes
with the already completed .NET `KType` graph. It adds useful logical type
information without first inventing a `KParameter` owner model or general
member reflection.

The distinction between the declaration target and the executable adapter is
observable. Unit coercion, suspend conversion, fun-interface adaptation, and
other callable-reference transformations may change the generated `invoke`
shape without changing the reflected declaration. Generic fake overrides are
also declaration facts: `A::foo` may expose a substituted fake-override return
type while `H<A>::foo` still exposes the declaration parameter `T`.

## Decision

### Follow Native's exact reflection-target rule

For a function or constructor reference, `returnType` is built from
`IrRichFunctionReference.reflectionTargetSymbol.owner.returnType`. For a
property reference it is built from the original `IrProperty` getter return
type. A local delegated-property token uses the value type supplied by the
shared property-reference lowering.

Do not use the generated `invoke` method's return type, the callable
interface's final type argument, a physical CLR method signature, or a name-
based lookup. Those are execution/use-site projections and can disagree with
the logical declaration selected by Kotlin reflection.

### Reuse the one logical `KType` materializer

The existing `typeOf` graph builder is extracted as shared .NET reflection IR
infrastructure. It allocates every reachable declaration type parameter before
initializing bounds, then preserves classifier identity, nested arguments,
stars, use-site variance, nullability, recursive bounds, and stable declaration
container keys. Callable return types use that builder unchanged; no second
signature codec or `System.Type`-shaped graph is introduced.

Each callable object retains the constructed `KType` once. Repeated
`returnType` reads therefore return the same object and cannot depend on later
CLR reflection state. Bound and unbound references retain their ordinary
execution and equality behavior.

The minimal physical `KType` interface moves beside `KClass` and `KCallable`
into `Kotlin.Runtime.dll`. Its unchanged Common implementation remains in
`Kotlin.Stdlib.dll`. This makes the public callable slot truthfully typed
without a Runtime-to-Stdlib assembly cycle, an object-return bridge, a wrapper,
or a second type identity.

### Kotlin and imported CLR declarations meet in semantic IR

A Kotlin dependency supplies its target signature through embedded KLIB. The
consumer materializes `KType` from that deserialized declaration, so the DLL's
erased signature and optional CLR attributes are not the Kotlin round-trip
store.

An imported C# declaration has no KLIB. Its selected assembly metadata,
admitted signature, and recognized nullable attributes are consumed by the
.NET importer before FIR/IR. `returnType` then materializes that already
enhanced logical IR type. The runtime does not reopen `MethodInfo`,
`PropertyInfo`, or custom attributes to reconstruct the signature. Thus
standard C# nullability can contribute at the foreign-import boundary without
tying the reflection API to CLR annotations or allowing them to override
Kotlin-produced KLIB.

This tranche does not broaden the deliberately narrow CLR interface importer.
In particular, foreign generic methods currently make an interface contract
unsupported and fail the whole declaration closed. When that importer feature
lands, its method-owned type parameters and bounds must feed this same graph;
`returnType` must not add a private generic-signature decoder as a shortcut.

### Keep the parameter/member boundary closed

`returnType` adds no callable owner object beyond the reference already being
used. `KParameter` would require stable instance/context/extension/value
positions, names, optional/vararg semantics, parameter annotations, equality,
and future `callBy` ownership. Callable type parameters subsequently
established the one shared identity table required by parameter and return
types; see [`callable-type-parameters.md`](callable-type-parameters.md). It does
not make an empty or partial `KParameter` list correct.

## Design attack

### Reflect the generated `invoke` result

Rejected. Adapters can change that result independently of the referenced
declaration. It would make reflection depend on lowering order rather than
Kotlin identity.

### Use the final `KFunctionN`/`KPropertyN` type argument

Rejected. It is a use-site execution type and may substitute a generic owner
where mature reflection deliberately retains the target declaration's type
parameter. It also cannot represent fake-override ownership reliably.

### Decode the physical CLR member signature at runtime

Rejected. Kotlin-owned classes and interfaces are declaration-erased, KLIB
retains information absent from ECMA-335, and imported nullable enhancement is
a compiler decision. Runtime decoding would create a second, weaker authority
and repeat the annotation-discovery error already excluded by its ADRs.

### Add JVM's complete `KCallable` surface now

Rejected. Native proves `returnType` is independently useful. Parameters,
type-parameter ownership, invocation, visibility, modality, and suspend
reflection each add semantic contracts not needed to return one existing
`KType` graph.

### Store only `System.Type`

Rejected. It cannot preserve projections, Kotlin nullability, declaration type
parameters, recursive bounds, erased Kotlin generic identity, or logical-only
classifiers.

## Consequences and deferred work

- The .NET platform actual follows Native and JVM by adding
  `KCallable.returnType` above Common.
- Function-reference and property-wrapper compiler/runtime constructors carry
  one additional logical `KType`; local delegated properties carry the same
  value explicitly.
- Runtime owns the one physical `KType` interface; Stdlib-owned `KTypeImpl`
  implements it and continues to own Common behavior.
- Runtime surface level advances to 19; the physical Kotlin declaration-index
  schema is unchanged.
- The extracted type-graph builder becomes the single producer for `typeOf`
  and callable return types.
- Parameters, accessor objects, type-use
  annotations, general members, and reflective invocation remain parked.

## Invariants

1. Kotlin callable return types come from KLIB-derived reflection targets.
2. Imported CLR signature and nullability evidence is interpreted by the
   importer, not by runtime reflection.
3. The generated execution adapter never becomes the reflection authority.
4. `returnType` uses the same `KType` graph and identities as `typeOf`.
5. A callable remains one object across invocation, annotations, name, and
   return-type views.
6. No parameter, owner lookup, accessor, or reflective-call API is implied.

## Verification

The gate must cover function, constructor, property, mutable property, bound
and unbound references; nullable, projected, nested-inner, declaration-type-
parameter, recursive-bound, fake-override, `Unit`, and local delegated-property
types; repeated-read identity; invocation/mutation on the same object;
separate Kotlin KLIB consumption; imported C# scalar and nullable return types;
the fail-closed foreign-generic-method boundary; selected unchanged shared
Native/JVM reflection tests; both FIR parsers;
Framework CLR and CoreCLR; exact IL; and the full XML-audited aggregate.
