# Declaration-owned callable parameters

- Status: Accepted (pre-ABI)
- Scope: `KCallable.parameters` and `KParameter` on direct function,
  constructor, property, and local delegated-property references
- Depends on:
  [`callable-type-parameters.md`](callable-type-parameters.md),
  [`callable-return-types.md`](callable-return-types.md), and
  [`callable-annotation-discovery.md`](callable-annotation-discovery.md)
- Does not enable: `callBy`, property accessor objects, member
  enumeration, context parameters as a language feature, or foreign CLR
  generic-method import

## Cross-target contract

Common does not declare `KParameter`; JS and Wasm retain the Common
`KCallable.name` floor, while Native adds only `returnType`. JVM is therefore
the authoritative mature-target precedent for this deliberate .NET platform
extension.

JVM orders an unbound callable's parameters as instance, context, extension
receiver, then ordinary values. A bound reference omits the receivers already
captured by the reference and reindexes the remaining parameters. An inner
class constructor uses the outer instance as `INSTANCE`; ordinary constructors
have only value parameters. A property exposes its getter-like receiver list,
while a local delegated-property token has no parameters. Receiver names are
`null`; source value names are retained when available. Vararg type is the
array type. A default on either the declaration or the corresponding
overridden parameter makes a value parameter optional.

`KParameter` equality and hashing are owned by the containing callable and
the exposed index. They are not declaration-only identities: bound and
unbound references have different exposed positions, and equal callable
objects must produce equal parameter objects.

## Decision

### Extend the one callable signature graph

The compiler extends the existing declaration-owned signature carrier to
contain exactly three sections:

1. the return `KType`;
2. the callable-owned `KTypeParameter` objects; and
3. ordered parameter descriptors containing name, type, kind, optional and
   vararg flags, and the parameter's annotation list.

Return, bounds, and every parameter type are built from one allocation table.
An occurrence of a callable type parameter in a parameter type is therefore
the same classifier object exposed through `typeParameters`, not an equal
reconstruction. KLIB-derived or importer-enhanced IR remains the signature
authority; physical CLR signatures and runtime reflection never reconstruct
Kotlin-owned parameters.

The carrier is private versioned compiler/runtime ABI, not serialized Kotlin
metadata. Runtime surface level 21 may replace the earlier flat
`[returnType, typeParameters...]` transport because no Kotlin/.NET ABI has
been frozen.

### Keep construction on the correct side of the assembly boundary

`Kotlin.Runtime` physically owns the erased `KCallable` slot and the property
reference wrappers, but it must not depend on `Kotlin.Stdlib`, which owns
`KParameter`, `KParameter.Kind`, and their Kotlin behavior. Generated code
therefore passes one erased `Function2` factory with the signature carrier.
The Runtime-owned callable base invokes that factory once from its constructor,
passing the actual callable object as owner, and stores the returned read-only
list.

This direction preserves the dependency graph:

```text
compiler-produced descriptor graph
        + Stdlib parameter factory
                    |
                    v
Runtime callable object --owner--> Stdlib KParameter objects
```

Runtime knows only `Function2`, `object[]`, and the erased Kotlin `List`. It
does not name a Stdlib implementation or `KParameter.Kind`. Stdlib does not
decode Runtime fields. Disabling the factory changes neither callable
identity nor the logical signature; it is construction plumbing, not a
second reflection model.

`KParameter.Kind` is physically nested under `KParameter`. The shared external
stdlib mapping retains the full `Kotlin.Reflection.KParameter/Kind` reference,
while CIL declaration emission uses only the nested declaration name under its
already selected enclosing TypeDef. Treating that external path as the nested
TypeDef's local name would duplicate the owner and make physical ABI manifests
unresolvable even though ILAsm accepts the assembly.

### Bound receivers and inner constructors

Kotlin callable-reference syntax captures only receiver positions, never an
ordinary value parameter. Receiver descriptors occur before values in the
shared IR contract. The bound-value count therefore removes that many leading
receiver descriptors and the retained list is reindexed from zero. A member
extension may consequently expose all receivers, only its extension receiver,
or neither, depending on which receiver prefix the reference captured.

The shared inner-class lowering has already materialized an inner
constructor's containing outer instance as its first receiver parameter when
the descriptor graph is built. The .NET reflection lowering consumes that IR
fact directly; it must not synthesize a second target-specific outer
descriptor. A bound outer instance removes the shared descriptor through the
same receiver-prefix rule.

Context positions are represented by the public `Kind.CONTEXT` enum so the
surface remains source-compatible with JVM. This tranche does not admit
context parameters into the .NET language subset; when that independent
feature lands, its existing IR positions must feed this same ordering.

The authoritative Common `ExperimentalContextParameters` declaration is
packaged for frontend and KLIB resolution. Its retention is `BINARY`, and this
target does not project Kotlin-only non-runtime applications into CLR custom
attributes, so the declaration is resolution-only during physical CIL
emission. It remains present in the logical stdlib KLIB contract without
injecting an otherwise unused CLR attribute class into every source product.

### Kotlin and foreign annotation ownership

Kotlin parameter annotations are constructed from the exact
`IrValueParameter`/KLIB application, using the same all-or-nothing valued
annotation rules as callable annotations. Receiver annotations remain attached
to their receiver parameter; synthetic instance parameters have an empty list.

For an admitted foreign CLR method, the retained MethodDef and Param rows are
authoritative for name availability, `ParamArray`, and parameter custom
attributes. The annotation list remains the honest CLR view, including
well-known `OptionalAttribute` and `ParamArrayAttribute` instances beside user
attributes. A missing or invalid Param name yields `null`, never the importer's
synthetic `pN` resolver name. `ParamArray` does set `isVararg`; CLR
optional/default flags and their visible attribute do not become Kotlin
`isOptional`, because the current importer does not admit them as Kotlin
default arguments or `callBy` semantics. Foreign generic methods remain
fail-closed.

## Design attack

### Put `KParameter` implementations in Runtime

Rejected. `KParameter.Kind` is an ordinary Kotlin enum and lives with the
platform reflection API in Stdlib. Moving or duplicating it in Runtime would
either create a Runtime-to-Stdlib dependency cycle or a second physical enum
identity.

### Recreate parameters in each property getter

Rejected. It could pass an owner after construction, but would repeatedly
allocate lists and parameter objects and would make stable construction an
accident of generated subclasses. Runtime already owns the one construction
point shared by functions and property wrappers.

### Key parameters by a copied callable identifier

Rejected. Function equality includes bound values, while property equality
includes its generated getter/setter identities. Copying those rules into a
second key would drift as callable identity evolves. Passing the callable
object makes JVM's `callable + index` rule direct.

### Infer foreign names from IR

Rejected. FIR necessarily invents valid names when a CLR Param row omits or
malforms one. That resolver-only spelling is not runtime metadata and must not
leak through reflection.

## Invariants

1. One callable owns one return/type-parameter/parameter type graph.
2. Exposed parameter order and reindexing follow JVM's callable model.
3. `KParameter` equality and hashing use the actual callable object and index.
4. Receiver names are `null`; invented foreign resolver names never escape.
5. `isOptional` means Kotlin default-call semantics, not a suggestive CLR flag.
6. Parameter annotations retain their exact declaration/Param-row owner.
7. Runtime never depends on Stdlib or reconstructs Kotlin signatures.
8. Absence of the platform surface remains a feature-detected diagnostic path.
9. `call` and `callBy` consume this list through
   [`callable-positional-invocation.md`](callable-positional-invocation.md) and
   [`callable-named-invocation.md`](callable-named-invocation.md). Direct
   property accessors extend the same graph through
   [`property-accessor-reflection.md`](property-accessor-reflection.md);
   member enumeration remains a separate decision.
10. The Common frontend prohibits direct references to a declaration that is
    both a member and an extension. Its two-receiver parameter view therefore
    remains coupled to future `KClass` member enumeration, not this direct-
    reference tranche.

## Verification

The gate covers top-level and member functions, constructors, inner
constructors, directly expressible extension receivers, bound/unbound
references, properties and local delegated properties, names, ordering,
reindexing, varargs, direct and inherited defaults, parameter annotations,
generic classifier object identity, equality/hash/list stability, imported
CLR Param names/`ParamArray`/annotations, manual `KCallable` implementations,
missing-stdlib feature detection, emitted factory wiring, separate KLIB and C#
consumption, both FIR parsers, both CLR profiles, and the full audited aggregate.
