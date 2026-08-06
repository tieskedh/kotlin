# `KType` and `typeOf` retain the logical Kotlin type graph

**Status:** Accepted for the pre-ABI prototype

## Scope

This decision admits the Common `KType`, `KTypeProjection`, `KVariance`,
`KTypeParameter`, and `typeOf` surface for denotable runtime type graphs. It
does not admit callable or member enumeration and invocation, annotation
discovery or valued annotation classes, value classes, unsigned types,
suspend reflection, or broad `kotlin-reflect` compatibility.

## Context

`KClass` records a runtime classifier, but it cannot represent type arguments,
declaration-site type parameters, projections, stars, or use-site nullability.
Those distinctions remain observable even when the CLR carrier is erased. For
example, the body of this non-inline function denotes its declaration
parameter, not the CLR type argument used by a particular invocation:

```kotlin
fun <T> declaredType() = typeOf<List<T>>()
```

Consequently, neither `System.Type` nor a constructed CLR generic handle can
be the authority for `KType`.

## Kotlin authority

The Common declarations and their non-JVM implementations define the runtime
model:

- a classifier is a `KClass`, a `KTypeParameter`, or `null` where Kotlin
  admits no single classifier;
- arguments preserve invariant, in, out, and star projections in Kotlin
  order, including the inner-first ordering of inner-class arguments;
- use-site nullability is retained independently from the physical carrier;
- type parameters retain name, variance, reified status, upper bounds, and
  declaration identity; and
- equality, hashing, validation, and rendering follow the unchanged Common
  implementation.

The .NET target compiles those Common sources rather than replacing them with
a BCL-shaped model.

## Mature-target precedent

The JVM backend treats `typeOf` as a compiler intrinsic and constructs
declaration parameters in two phases so repeated and recursive bounds preserve
identity. JS and Wasm construct helper-backed Kotlin type graphs after
substitution, but their current recursive-bound limitation is not a semantic
requirement. Native also constructs the graph in IR and explicitly handles
recursion.

Kotlin/.NET follows the shared architecture: the compiler materializes a
Kotlin graph after reified call-site substitution. It follows the JVM's
two-phase parameter construction so recursive bounds are represented rather
than rejected or truncated.

## CLR facts

`System.Type` cannot faithfully carry Kotlin nullability, projections, erased
Kotlin-owned classifier identity, declaration-site type-parameter identity,
or the distinction between a declaration parameter and the current CLR
method instantiation. CLR reflection remains useful physical evidence for an
imported classifier; it is not the logical Kotlin type graph.

## Decision

### Runtime graph

The target uses the unchanged Common non-JVM `KTypeImpl` and a narrow target
`KTypeParameter` implementation. A parameter's declaring-container key is
derived from the stable Kotlin IR mangling of its declaring class or function,
not from a CLR owner, metadata token, or source-level name alone.

### Compiler construction

A target lowering runs after reified inline substitution and before ordinary
.NET code generation. For each surviving `typeOf` intrinsic it:

1. discovers every reachable declaration type parameter, including those in
   upper bounds;
2. creates exactly one parameter object for each declaration without bounds;
3. initializes all upper bounds after those identities exist; and
4. constructs the requested root type and all projections.

No `typeOf` intrinsic may reach CIL emission.

### Classifier identity

Class classifiers reuse the existing `KClass` construction path. A
Kotlin-owned generic classifier therefore retains its single erased runtime
identity while distinct `KType` values retain distinct logical arguments.
Imported CLR generic classifiers may retain their open CLR classifier evidence
plus logical Kotlin arguments. A closed CLR generic instantiation never
becomes the authority for a Kotlin-owned classifier.

When a compiler/builtin classifier participates in a denotable Kotlin type but
has no emitted CLR Type, `KClass` stores a separate KLIB-mangled declaration
key. Equality and hashing use that key, never `simpleName` or `qualifiedName`;
`isInstance` fails closed because no runtime instance carrier exists.

### Nullable relative bounds

KLIB and the constructed graph retain a bound such as `X : Y?` exactly. The
physical CLR generic method omits that `GenericParamConstraint`: spelling it
as `X : Y` would reject the legal Kotlin substitution `X = Int?`, `Y = Int`,
while `object` is not a CLR generic constraint. This is a truthful weakening
for foreign callers, not lost Kotlin information. A non-null relative method
bound such as `C : R` remains a real positional CLR constraint under its
existing decision.

### Physical and C# boundary

The public Common reflection surface is a truthful Kotlin surface that C# may
inspect. Compiler construction helpers are compiler ABI, not a second public
reflection model. A reified `typeOf` operation is completed at its Kotlin call
site; the target does not publish a callable CLR-generic `typeOf<T>` remainder.

## Design attacks and rejected alternatives

### Use `System.Type`

Rejected because it loses Kotlin-only graph information and would make
physical CLR generic instantiations redefine erased Kotlin runtime identity.

### Decorate `System.Type` with nullable or projection flags

Rejected because declaration parameters, recursive bounds, and Kotlin
classifier identity still need an independent graph. The decoration would be
the graph under another name while retaining a misleading authority split.

### Support only concrete reified types

Rejected because Common `typeOf` also observes non-reified class and function
parameters, including a non-reified parameter whose bound contains a reified
parameter.

### Construct parameters and bounds in one pass

Rejected because recursive and repeated references would be duplicated,
truncated, or rejected. Two-phase construction is a small, established
foundation and preserves graph identity.

### Key parameters by CLR generic handles

Rejected because the same Kotlin declaration may be observed through multiple
CLR instantiations, while overloaded Kotlin declarations require identities
stronger than their source names.

### Add member and annotation reflection in this tranche

Rejected because type graphs are their prerequisite but do not settle member
discovery, invocation, annotation retention, or foreign metadata enhancement.
Those remain separately reviewable language features.

## Invariants

- Common owns `KType` equality, hashing, validation, and string rendering.
- `System.Type` may contribute classifier evidence only through `KClass`.
- Type arguments never participate in the runtime identity of a Kotlin-owned
  class.
- A non-reified declaration parameter remains a `KTypeParameter` regardless
  of a physical CLR method instantiation.
- One construction graph contains one parameter object per declaration, and
  each parameter's bounds are initialized exactly once.
- Parameter declaration identity is Kotlin/KLIB-derived and stable across
  separate compilation.
- Display names never substitute for logical classifier or parameter identity.
- Nullable relative bounds remain exact in KLIB/KType and are omitted from CLR
  constraints rather than strengthened.
- Every admitted classifier extends ordinary and reified type operations and
  `typeOf` together.

## Verification

Coverage must exercise both FIR parsers, supported runtimes and profiles,
installed and fallback products, and separate producer/consumer libraries. It
must include:

- Kotlin-owned, primitive, array, function, and imported CLR classifiers;
- nullability, stars, every projection, nesting, aliases, and inner ordering;
- erased classifiers with observably distinct logical `KType` arguments;
- class, function, property, inline, and reified parameters, their flags,
  upper bounds, recursion, repetition, and container-sensitive equality;
- Common projection validation, equality, hashing, and rendering;
- absence of surviving `typeOf` intrinsics and constructed CLR generic
  authority; and
- truthful C# inspection of the public graph and rejection of still-parked
  reflection shapes.

The box harness must run a library module through the same metadata
serialization, backend, and finalization phases as the CLI. A source-module
dependency inside one frontend invocation is not separate-compilation
evidence.

## Freeze conditions

The helper names, visibility, container-key encoding, and physical metadata
are compiler ABI during the prototype and remain changeable until the ABI
freeze. Before freezing them, cross-version producer/consumer behavior and
explicit C# export interaction require their own version-skew evidence.
