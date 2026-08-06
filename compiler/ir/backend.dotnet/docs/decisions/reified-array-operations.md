# Reified array operations reuse ordinary substituted carriers

- Status: Completed prerequisite consumed by reified inline (pre-ABI)
- Scope: Kotlin call-site substitution for generic-array construction and varargs
- Does not enable: `KType`, annotations, value classes, unsigned arrays, or
  suspend inline functions

This document records the array-specific prerequisite. The later
[reified-inline decision](reified-inline-functions.md) completed the public
language feature and is authoritative for the current pipeline, physical
remainder, enum helpers, and published stdlib closure.

## Context

Common declares or relies on the following reified array operations:

- `arrayOf<T>(...)` and generic varargs;
- `arrayOfNulls<T>(size)`;
- `emptyArray<T>()`;
- `Array<T>(size) { ... }`;
- `Array<out T>?.orEmpty()`; and
- `Collection<T>.toTypedArray()`.

The shared IR inliner substitutes a reified type parameter throughout copied
IR before target lowering. `ArrayConstructorLowering` then rewrites the
initializer constructor to an allocation and the exact Common fill loop. A
.NET implementation therefore does not need a second reified-array type-token
system. It needs every substituted ordinary `Array<E>` operation to use the
same truthful carrier that non-inline Kotlin already uses.

This audit follows the completed primitive-array, nullable-primitive generic
array, `Array<*>`, and generic-class identity decisions. It asks whether array
allocation still requires a representation decision. It does not treat a
successful concrete allocation as permission to enable public reified inline.

## Common authority

After complete call-site substitution, Common requires one invariant generic
array of the substituted element type, ordered evaluation of every element or
initializer, zero initialization for `arrayOfNulls`, negative-size failure,
and the original array identity for vararg/spread and `orEmpty` paths where
Common returns an existing value.

Common does not require all targets to expose the same foreign runtime array
type. It does require Kotlin operations on the result to retain the logical
element type and the ordinary `Array<E>` behavior selected by that target.
Specialized primitive arrays remain different Kotlin classes.

## Mature-target evidence

### JVM

The JVM backend leaves reification markers in a physical inline method and
replaces `NEW_ARRAY` markers at a Kotlin call site. Its actual `emptyArray`
uses `arrayOfNulls<T>(0)`, and the resulting JVM reference array carries the
erased JVM component class. Generic class arguments erase inside that
component class, while nested JVM arrays retain their physical nesting.

### JS

JS uses the shared IR inliner and removes declarations with reified type
parameters after valid calls are inlined. Generic Kotlin arrays share the JS
array representation; its runtime generic-array test deliberately checks only
generic-array identity, not the element argument. Its target actuals can omit
`reified` where the uniform physical representation does not need it.

### Wasm

Wasm also uses the shared inliner and removes surviving reified declarations.
Array allocation is lowered to the target's ordinary Wasm GC array operations
after type substitution. It does not retain a separately callable generic
method as the meaning of Kotlin reification.

### Native

Native uses the shared inliner. Its `arrayOfNulls` allocates the ordinary
generic-array carrier through `arrayOfUninitializedElements`, `arrayOf` is an
identity intrinsic over its vararg, and `emptyArray` is a runtime operation.
A surviving reified declaration is poisoned separately; array construction is
not implemented by calling that physical fallback.

## CLR fact

The CLR can allocate an SZ vector from a concrete or open metadata element
token. Kotlin/.NET already maps every admitted `Array<E>` to one of these
ordinary shapes:

| Kotlin element family | Substituted CLR vector element |
| --- | --- |
| reference class or `String` | exact reference carrier |
| non-null signed Common scalar | exact CLR value type |
| nullable signed Common scalar | exact `System.Nullable<V>` |
| nested generic array | the selected inner vector or classified `System.Array` view |
| signed specialized primitive array | Kotlin.Runtime wrapper class |
| Kotlin generic interface | its non-generic canonical interface |
| Kotlin generic class | its one non-generic physical class owner |
| `CharSequence` | classified `System.Object` carrier |
| `Any` / `Any?` | `System.Object` |

Those mappings already drive literals, `emptyArray`, `arrayOfNulls`, generic
varargs, and the Common array-constructor lowering. They preserve the exact
typed vectors where the CLR can do so and the selected classified/canonical
identity where Kotlin erasure requires it.

## Decision

Reified array construction will reuse the ordinary post-substitution
`Array<E>` mapping and intrinsic paths. There is no reified-only array carrier,
metadata token, wrapper, or CLR generic method implementation.

The following operation paths are representation-ready for every element
classifier already admitted by the target:

- `arrayOf<T>` including empty literals and ordered spreads;
- generic vararg materialization;
- `arrayOfNulls<T>`;
- `emptyArray<T>`;
- `Array<T>(size) { ... }`, including nested constructors; and
- the allocation side of `orEmpty` and `toTypedArray`.

The shared inliner first replaces every reified parameter at a valid call
site. Target codegen then sees only the same concrete or ordinary open types
it already maps. A reified type parameter that survives into a target array
operation is an internal feature-closure error.

`orEmpty` and `toTypedArray` are now published through their exact Common
declarations and narrow target actuals as part of the complete reified stdlib
closure. Their algorithms were not copied into the emitter.

## Prerequisites subsequently closed

Array construction readiness did not by itself close the reified language
feature. The completed reified feature subsequently closed these adjacent
boundaries:

- `T::class` uses the selected nominal `KClass` identity and truthful
  `System.Type` bridge;
- enum intrinsics use ordinary enums and the non-reified `EnumEntries` core;
- array `is`/`as` substitutions execute in the final call-site matrix; and
- each truthfully representable physical remainder is a non-public throwing
  stub, while a signature with no truthful open CLR shape is omitted.

`typeOf<T>()` still needs `KType`, type arguments, variance, and nullability.
Valued annotations, value classes, and unsigned arrays retain their own
ordinary representation prerequisites. They do not reopen this array
decision; when admitted, they extend the same reified operation matrix before
publication.

## Design attack

### Emit an ordinary callable CLR generic method for the reified body

Rejected. A C# caller could execute it without Kotlin call-site substitution,
and the method would appear to support only those operations the CLR happens
to reify. Kotlin reification is a compiler operation, not CLR generic-method
dispatch.

### Introduce a runtime `System.Type` argument for every array operation

Rejected. The shared inliner has already substituted the logical type, and
the ordinary IL type token is sufficient for every admitted carrier. A second
token would duplicate authority and still would not define `KClass` or
`KType`.

### Allocate `object[]` for every substituted element

Rejected. It discards exact scalar, nullable-scalar, nested-array, wrapper,
and ordinary reference carriers, weakens the C# view, and contradicts the
existing generic-array decisions.

### Use closed `C<T>[]` for Kotlin generic-class elements

Rejected. Kotlin generic-class identity erases type arguments. Arrays of a
Kotlin generic class therefore use its one non-generic physical owner, not an
invariant typed CLR construction. Logical element arguments remain in KLIB and
element reads narrow at their ordinary use sites.

### Treat the concrete carrier matrix as public reified support

Rejected. It proves only the target operations that receive already-
substituted IR. It does not prove the inliner gates, every legal classifier,
reflection operations, enum/annotation intrinsics, or the physical fallback.

## Invariants

1. Common IR substitution is authoritative for the element type.
2. Reified and non-reified code reaching the same substituted `Array<E>` uses
   the same mapper, intrinsic, lowering, and runtime identity.
3. No closed typed CLR generic class becomes Kotlin array-element identity.
4. `Array<*>` remains the classified `System.Array` view and specialized
   primitive arrays remain Kotlin.Runtime wrappers.
5. An unsupported substituted element classifier rejects the operation; it
   never falls back to `object[]` merely to make reified code compile.
6. The final public feature changes the gates only as one complete closure;
   this prerequisite did not authorize partial enablement.

## Verification

The dedicated concrete carrier matrix represents the IR shapes expected after
shared substitution without claiming to execute a reified declaration. It
must execute on both FIR frontends and both runtime profiles and cover:

- reference, scalar, nullable-scalar, `Any?`, and classified
  `CharSequence` elements;
- the one erased generic-class owner and generic-interface canonical element identity;
- nested exact arrays, nested `Array<*>`, and specialized-array wrapper
  elements;
- literal, empty, null-initialized, initializer, vararg, and spread paths;
- evaluation order, zero initialization, identity, erased follow-up casts,
  and negative-size failure; and
- continued explicit rejection of reified declarations and open
  `Array<T?>`.

Existing portable-library tests remain the cross-module evidence for each
underlying carrier. The final reified producer/consumer matrix now executes
actual shared substitution in all three KLIB inliner modes; this concrete
carrier matrix remains the array-specific regression floor.
