# Reified inline functions use complete call-site substitution

- Status: Accepted (pre-ABI)
- Scope: ordinary `inline fun <reified T>`, substituted runtime type operations,
  cross-library bodies, and the physical CLR remainder
- Does not enable: `KType`/`typeOf`, value classes, suspend inline functions,
  coroutine state machines, or broad member/annotation reflection

## Context

Kotlin reification is a call-site compiler operation. It is not the CLR's
ability to execute a generic method with a runtime type handle. The copied
inline body must observe the Kotlin type argument after substitution, including
its Kotlin nullability, projections, declaration-erased Kotlin classifier, and
ordinary array carrier.

The target already has the required ordinary-inline KLIB pipeline, nominal
`KClass`/class literals, runtime type tests and casts for every admitted
classifier, ordinary enums, exact generic-array allocation, and one
declaration-erased identity for Kotlin-owned generic classes and interfaces.
Those independently completed foundations now form the input to this feature.

## Kotlin authority

The frontend owns which type argument is legal for a reified parameter. The
shared IR inliner owns substitution in copied bodies. After substitution, the
.NET backend must compile the resulting ordinary IR exactly as if the concrete
type had appeared in source.

The supported operation closure is:

- `T::class` for every already admitted classifier;
- `is T`, `!is T`, `as T`, and `as? T`, including nullable targets;
- generic array literals, zeroed allocation, initializer construction, vararg,
  spread, `orEmpty`, and `toTypedArray` through the ordinary array carriers;
- nested reified calls and reified calls from default arguments;
- enum `values`, `valueOf`, and `entries` through each enum class's existing
  synthetic functions and property; and
- same-module and separately compiled calls in every supported KLIB inliner
  mode.

No reified-only classifier, type token, array wrapper, closed Kotlin-owned
`C<T>` identity, or CLR-generic dispatch path is introduced.

`typeOf<T>()` is a separate operation with a separate result model. It remains
unavailable until the target has a truthful `KType`, type-argument, variance,
and nullability representation. Supporting reified functions does not make a
missing stdlib/reflection declaration available, just as it does not enable
value classes or coroutines.

## Mature-target precedent

JS, Wasm, and Native enable reified bodies in the shared first- and
second-stage KLIB inliner. The shared body preprocessor substitutes reified
parameters before target lowerings inspect type operators and array
construction.

Their physical cleanup is backend-owned and occurs after inlining:

- JS and Wasm remove declarations that still have reified parameters;
- Native replaces such declaration bodies and default expressions with a
  deterministic throwing body; and
- Native processes `typeOf` in a following, independent reflection lowering.

JVM stores compiler-readable inline bodies in class files and emits reification
markers for its inliner. A direct non-inlined invocation is not the semantic
implementation of a reified Kotlin call. JVM enum helpers are codegen
intrinsics; JS, Wasm, and Native rewrite their platform intrinsics after
substitution.

Kotlin/.NET follows the KLIB architecture and the Native physical fallback,
while retaining its self-describing-DLL and explicit-C#-export rules.

The .NET stdlib also has bodyless compiler intrinsics such as `emptyArray`.
They cannot be deserialized as if they were KLIB-owned source bodies during
the pre-serialization stage. The target therefore preserves reified calls in
that stage, serializes the authoritative KLIB body, and runs one target-stage
shared inlining completion before enum and physical-remainder lowerings. The
target resolver imports bodies only from selected KLIB declarations; bodyless
compiler intrinsics remain for their existing CIL intrinsic path. This is a
pipeline-ordering constraint, not a second reification mechanism.

## CLR constraint and selected physical contract

A Kotlin-produced DLL must retain the logical declaration and inline body in
its embedded KLIB, but a public CLR generic method would be a misleading C#
API: C# could call it without Kotlin substitution and would observe only the
CLR operations that happen to work.

Each reified inline declaration therefore has:

1. its authoritative public Kotlin declaration and body in KLIB;
2. complete call-site substitution for every Kotlin invocation; and
3. when its whole CLR signature is truthful for every legal substitution, one
   assembly-visible CLR generic throwing stub in the producer DLL.

The stub preserves deterministic physical binding and diagnostics without
becoming ordinary C# API. Its body and every default expression call the
authoritative Common `throwUnsupportedOperationException` helper with the
declaration identity. A separately compiled
Kotlin consumer must inline the KLIB body; it never calls the stub. Explicit
C# export rejects reified declarations rather than projecting the stub.

Some logical signatures do not have one truthful open CLR method shape. For
example, `Array<T?>` must become a nullable value vector for scalar `T` and a
reference vector for reference `T`. Such a declaration has no physical
MethodDef: its KLIB declaration remains complete and every Kotlin call is
substituted. Reified remainders, whether emitted or omitted, are excluded from
the producer's physical Kotlin declaration index because that index may never
become a fallback ABI for them.

Where possible, this visibility is the CLR equivalent of JVM package-private
`@InlineOnly` fallbacks and the Native throwing-body rule. Omission is the
fail-closed equivalent when the CLR cannot state the logical declaration. In
neither case is the physical remainder a second semantic implementation.

## Enum operation boundary

The Common public helpers remain the authoritative declarations. Their
target actuals follow the JS/Wasm intrinsic-body pattern: after shared
substitution, a small .NET enum-usage lowering redirects the intrinsic to the
concrete enum's existing static `values`, `valueOf`, or `entries` member.

The lowering must reject a surviving type parameter, the abstract `Enum`
base, and a non-enum classifier. It must not use CLR `System.Enum`, reflection,
names to discover a type, or a target-authored duplicate array/list algorithm.

## Authoritative stdlib closure

Feature completion admits the complete reified surface whose non-reified
dependencies are already in the .NET product:

- `Array<out T>?.orEmpty`;
- `Collection<T>.toTypedArray`;
- `Iterable<*>` and `Array<*>` `filterIsInstance`/`filterIsInstanceTo`; and
- `enumValues`, `enumValueOf`, and `enumEntries`.

Built-in `arrayOf`, `arrayOfNulls`, `emptyArray`, and `Array(size, init)` keep
their existing ordinary carrier paths. Sequence variants wait for the
independent Sequence product; atomic variants wait for concurrency. `typeOf`
waits for `KType`. These are dependency exclusions, not alternate reified
semantics.

## Design attack

### Execute the body as a normal CLR generic method

Rejected. CLR reification does not substitute Kotlin IR, cannot reproduce
Kotlin-owned generic erasure, and would expose an apparently useful but only
partly correct C# method.

### Pass `System.Type` or `KClass` as an extra hidden argument

Rejected. The shared inliner has already substituted the type. A second token
duplicates authority, does not encode `KType`, and can disagree with Kotlin
classifier rules.

### Enable only the convenient reified stdlib declarations

Rejected. User-authored reified functions can contain the same operations.
The compiler operation matrix must be complete before the generated stdlib is
admitted as evidence.

### Block reified support until broad reflection and every future type family

Rejected. Mature targets separate reified substitution, physical cleanup, and
`typeOf` processing. A type family that the target does not yet admit cannot be
a legal reified argument; when that family lands, its ordinary operation
matrix must extend the same reified gate before publication.

### Retain a public throwing stub for diagnostic clarity

Rejected. That method would appear in C# IntelliSense and metadata as a normal
public generic API even though every call fails. KLIB carries the Kotlin API;
the physical remainder is compiler infrastructure.

## Invariants

1. Shared IR call-site substitution is the only reified semantic mechanism.
2. A substituted operation uses the same classifier, cast, array, enum, and
   exception path as equivalent ordinary source.
3. Kotlin-owned generic class and interface arguments never become CLR runtime
   identity through reification.
4. No Kotlin call reaches a reified throwing stub or requires an omitted
   remainder.
5. No reified remainder belongs to the physical Kotlin declaration index,
   ordinary C# API, or explicit export candidates.
6. `typeOf` remains rejected until the separate `KType` decision is complete.
7. Adding a new admitted classifier family extends the reified adversarial
   matrix before that family and reified calls can ship together.

## Verification gate

The feature must prove both FIR parsers, Framework CLR and CoreCLR, and all
three KLIB inliner modes, including:

- same-module, member, private, default-argument, nested, and cross-library
  reified calls;
- reference, primitive, nullable primitive, `Any?`, `Nothing?`, classified
  carrier, Kotlin-owned generic class/interface, imported exact CLR type,
  generic array, star array, and primitive-array-wrapper arguments;
- positive and negative `is`, checked and safe casts, `T::class`, allocation,
  side-effect order, and exception identity;
- enum empty/single/multiple entries, fresh `values` arrays, exact singleton
  identity, successful and failing `valueOf`, and stable `entries` identity;
- producer KLIB body retention and complete disappearance of every consumer
  call;
- assembly-visible throwing stubs for truthfully representable signatures,
  omission of untruthful signatures, and absence of both from the physical
  Kotlin declaration index and explicit C# exports;
- installed and fallback stdlib products with the same logical declarations;
  and
- continued located rejection of `typeOf`, value classes, and suspend inline
  declarations.

The full target aggregate and JUnit XML audit are required because this changes
inlining, type operators, physical ABI, stdlib sources, and cross-module
binding.

## Freeze conditions

Before ABI freeze, the throwing-stub visibility/message, enum intrinsic names,
and physical binding schema may be corrected together with every producer and
consumer. ABI freeze additionally requires explicit C# export diagnostics and
version-skew tests for a producer whose reified body was compiled by another
supported compiler version.
