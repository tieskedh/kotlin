# Nominal `KClass` and class literals over classified CLR evidence

- Status: Accepted (pre-ABI)
- Scope: the Common `KClass` floor, static `C::class`, dynamic `value::class`,
  `KClass.isInstance`, names, equality, hashing, and the compiler/runtime CLR
  type bridge
- Does not enable: `KType`, `typeOf`, member reflection, annotation discovery,
  reified declarations, enums, annotation-class code generation, or value
  classes

## Context

Kotlin class literals produce `kotlin.reflect.KClass`, not a platform type
descriptor. The .NET target already has several cases in which the physical
CLR carrier is not the logical Kotlin classifier:

- `CharSequence` is the classified union of `System.String` and one
  Kotlin-owned implementation capability;
- broad Kotlin exception relations share the `System.Exception` carrier;
- `Array<*>` is the classified SZ-array view of `System.Array`;
- a Kotlin-owned generic class has one declaration-erased canonical identity
  and one typed CLR `C<T>` implementation capability; and
- split generic interfaces have canonical and typed capability views.

Consequently, replacing `KClass` with `System.Type`, or defining equality and
`isInstance` only through `System.Type`, would contradict runtime type tests
which the target already implements correctly.

This decision establishes only the Common reflection floor needed by class
literals, annotation arguments, contracts bootstrap work, and the later
reified programme. It is not a broad reflection implementation.

## Common authority

The Common declaration requires a nominal `KClass<T : Any>` which is also a
`KClassifier` and provides:

- nullable `simpleName` and `qualifiedName`;
- `isInstance(value)`;
- logical equality and a matching hash code; and
- values produced by both `C::class` and non-null `value::class`.

The Common `cast` and `safeCast` extensions use `isInstance` as their
classifier check and then retain the original value. They do not authorize a
copy, wrapper around the tested value, or a second cast semantics.

Generic arguments are not part of Kotlin class identity. In particular, the
runtime class of a `Box<Int>` value is the declaration `Box`, even though the
same object may physically be an instance of closed CLR `Box<int>`.

## Mature-target evidence

### JVM

The JVM backend emits a JVM `Class` token or obtains the runtime Java class of
the evaluated expression, then wraps it through the Kotlin reflection
factory. The stdlib's lightweight `ClassReference` implements the Common
surface, maps Java platform spellings back to Kotlin names, boxes primitive
classes for `isInstance`, and defines equality through the normalized Java
class. The Java `Class` is evidence held by `KClass`; it is not itself the
Kotlin value.

### JS

JS lowers static and dynamic class references to `KClassImpl` factories.
Primitive, array, function, `Any`, `Nothing`, and `Throwable` classifiers have
special implementations because a JS constructor alone cannot represent all
Kotlin relations. Dynamic expressions are evaluated once before runtime
classification.

### Wasm

Wasm lowers class references to `KClassImpl` over RTTI and uses a distinct
interface implementation over interface type data. Names, instance checks,
equality, and hashes are derived from the Kotlin runtime classifier rather
than exposing a raw Wasm reference type.

### Native

Native lowers static class references to `KClassImpl` constants over type-info
and dynamic references to the evaluated object's runtime type-info. Its
implementation delegates `isInstance` to the native Kotlin classifier and
uses type-info identity for equality and hashing.

All four precedents retain a Kotlin-owned value, evaluate dynamic receivers
once, and add a classifier layer where the host's raw type identity is
insufficient.

## CLR facts

`System.Type` is exact and useful for ordinary CLR classes, interfaces, boxed
value types, and closed vector types. It also preserves assembly/load-context
identity. It cannot by itself express:

- the `String` plus Kotlin-capability `CharSequence` relation;
- the distinction between logical `Throwable` and `Exception` when both use
  `System.Exception` as their physical carrier;
- Kotlin's SZ-array-only `Array<*>` relation;
- declaration-erased identity of a closed CLR generic class; or
- Kotlin source names for local and anonymous classes after CLR metadata names
  have been invented.

An open generic `System.Type` also cannot use `IsInstanceOfType` directly to
classify a closed constructed value. Its class/base/interface ancestry must be
compared by generic type definition.

## Decision

### Runtime value and ownership

`Kotlin.Runtime.dll` owns non-generic physical `KClassifier` and `KClass`
interfaces plus one compiler-runtime `KClassImpl`. Logical generic
`KClass<T>` remains authoritative in KLIB; its parameter has no runtime member
use and is erased from the physical interface in the same manner as the
existing callable-reflection floor.

`KClassImpl` stores:

- optional `System.Type` evidence;
- the Kotlin simple and qualified names;
- one versioned classifier kind; and
- an optional versioned logical classifier id.

The stored `System.Type` is an additive CLR bridge. Exact CLR-backed
classifiers expose an exact type; an ordinary generic class exposes its
producer-recorded open typed TypeDef; classified relations may expose only
their physical carrier or no single type. Consumers must not reconstruct
Kotlin identity from this field alone.

### Static class literals

The backend lowers `C::class` directly to the runtime factory with names from
the authoritative IR declaration and one of these classifier shapes:

| Kotlin classifier | Runtime evidence |
| --- | --- |
| ordinary class/interface, scalar, `String`, `Any`, `Unit`, primitive-array wrapper | exact `System.Type` |
| Kotlin-owned generic class | producer-recorded open typed `C<>` TypeDef plus declaration-erased classifier kind |
| generic/split Kotlin interface | its canonical interface where available; otherwise an open generic interface definition |
| `Array` | classified SZ-array kind, not a bare acceptance of every `System.Array` |
| `CharSequence` | classified relation shared with ordinary type tests |
| mapped Kotlin exception | the existing exception classifier id |
| `Number` | the admitted signed Common numeric scalar set |
| `Nothing` | an always-false classifier |

A surviving type-parameter class literal remains rejected until the complete
reified gate substitutes it.

### Dynamic class literals

The backend evaluates a non-null `value::class` receiver exactly once, boxes
it only at the established `Any` boundary, and calls the runtime classifier.
The runtime:

1. returns Kotlin scalar, `String`, `Any`, `Unit`, primitive-array, and mapped
   exception identities for their exact physical types;
2. normalizes every admitted CLR SZ vector to Kotlin `Array` identity;
3. normalizes a closed ordinary generic class to its open definition;
4. reads compiler-authored local/anonymous naming evidence where CLR naming
   cannot reconstruct the Common names; and
5. otherwise retains the exact runtime CLR class, including a foreign class.

Named non-local Kotlin classes use their ordinary CLR namespace/nested name,
which is already source-derived. Local and anonymous Kotlin classes receive a
compiler-ABI custom attribute containing the nullable Common `simpleName`;
their `qualifiedName` is always null. This attribute is runtime naming
evidence only. It neither replaces the embedded KLIB nor authorizes Kotlin
declaration reconstruction from arbitrary CLR metadata.

### Exception construction identity

`Throwable()` and `Exception()` currently allocate the same physical
`System.Exception` type. The original object therefore receives the exact
Kotlin constructor classifier id in the existing identity-associated
throwable runtime state. `value::class` consults this tag before falling back
to exact CLR type mapping. The operation does not wrap, clone, translate, or
write `Exception.Data`; untagged foreign `System.Exception` retains the
foreign/default mapped view.

Other Kotlin exception constructions are tagged as well, so one rule covers
the collision and remains stable if another classified physical carrier is
shared later. A user-defined exception subclass retains its own exact emitted
CLR type.

### Instance checks, equality, and hashing

`KClass.isInstance` delegates to the same semantic classifiers as ordinary
`is`/`as` operations:

- exact `Type.IsInstanceOfType` for exact classifiers;
- an open-definition ancestry walk for ordinary generic classes/interfaces;
- the existing `CharSequence`, SZ-array, and exception classifiers;
- the signed Common numeric-box set for `Number`; and
- constant false for `Nothing`.

Equality and hashing use classifier kind plus logical id or normalized
`System.Type`, never names. Two wrappers for the same classifier are equal
without requiring reference interning. Closed `Box<int>` and `Box<string>`
therefore produce equal dynamic `KClass` values for declaration `Box`, while
the physical objects and typed capabilities remain unchanged.

## Design attack

### Map `KClass` directly to `System.Type`

Rejected. It loses classified relations, Kotlin names, and erased generic
identity, and would make reflection disagree with existing casts and type
tests.

### Use closed `C<T>` as a faster `KClass` identity

Rejected. It makes Kotlin type arguments observable through class equality
and dynamic class literals. The typed CLR class remains a capability of the
same object, not Kotlin runtime identity.

### Derive every name from the invented CLR metadata name

Rejected. Local classes with escaped dollar signs and anonymous/callable
objects cannot be distinguished reliably from names alone. A narrow
compiler-ABI attribute is less ambiguous and does not compete with KLIB
authority.

### Put the complete KLIB classifier in a CLR attribute

Rejected. Runtime naming needs only nullable local `simpleName`. Duplicating
the declaration graph in attributes would create a second metadata authority
and version-skew problem.

### Make `Throwable` or `Exception` a wrapper solely for `obj::class`

Rejected. It violates the selected single-object CLR exception universe.
Identity-associated classifier state solves the only physical collision
without changing the thrown object.

### Implement member reflection while the wrapper exists

Rejected. `KType`, callable discovery, annotations, constructors, supertypes,
and member invocation are independent representation and product programmes.
The Common `KClass` floor does not require them.

## Invariants

1. KLIB remains authoritative for logical `KClass<T>` and declaration
   identity; CLR metadata remains physical evidence.
2. Static and dynamic class literals agree for every admitted exact runtime
   class.
3. Dynamic receivers are evaluated once and their original values are never
   wrapped or copied.
4. Kotlin generic arguments never participate in `KClass` equality,
   hashing, or `isInstance`.
5. `KClass.isInstance` and ordinary type operators share classifier
   semantics.
6. Local/anonymous CLR attributes carry names only and cannot reconstruct a
   Kotlin library contract.
7. Exception construction tags are identity-associated state on the original
   `System.Exception` object.
8. `System.Type` does not become `KClass` or `KType` authority.
9. `KType`, public reified support, enums, and annotation-class codegen remain
   disabled after this feature.

## Verification

The feature gate must cover both FIR parsers and both runtime profiles, plus
portable producer/consumer binaries where applicable:

- static/dynamic equality and hashes for every signed scalar, `String`,
  `Any`, `Unit`, `Nothing`, named class/interface, nested class, local class,
  anonymous object, primitive array, and generic array;
- nullable names for local/anonymous classes, including escaped dollar signs;
- `isInstance`, Common `cast`, and `safeCast` over success, null, wrong type,
  interfaces, `CharSequence`, `Number`, arrays, and exceptions;
- declaration-erased equality and instance checks for several `C<T>`
  instantiations, inheritance, stars, and a failed classifier check;
- exact distinction of Kotlin-constructed `Throwable()` and `Exception()` and
  unchanged identity of foreign/mapped CLR exceptions;
- no admission of rectangular or non-zero-based CLR arrays as Kotlin `Array`;
- exact assembly identity for same-named classes from separate producers;
- physical reflection over `KClass`, `KClassifier`, helper visibility, local
  naming attributes, and the retained `System.Type` bridge; and
- continued rejection of `T::class`, `typeOf`, enum/annotation reflection,
  and broad member reflection outside their selected programmes.

The strict target gate and JUnit XML audit remain mandatory before this
pre-ABI decision is recorded as implemented.
