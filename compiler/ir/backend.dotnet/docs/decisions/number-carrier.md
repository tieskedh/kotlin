# ADR: classified `Number` superclass carrier

- Status: **Accepted — pre-ABI**
- Date: 2026-08-11
- Scope: `Number`, built-in numeric boxes, user subclasses, conversion calls,
  casts, type tests, generic bounds, reflection, and runtime ownership

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Common Kotlin defines `Number` as an abstract class with six abstract numeric
conversion members and an open deprecated `toChar` member. Kotlin programs may
subclass it. JVM and Native preserve that class contract directly; JVM also
rewrites only calls to the inherited `Number.toChar` slot to
`toInt().toChar()`, without replacing a user override. JavaScript specializes
built-in numeric conversion calls in its backend where its host representation
requires that treatment.

The CLR has no superclass whose instances are exactly Kotlin `Byte`, `Short`,
`Int`, `Long`, `Float`, `Double`, and Kotlin-written `Number` subclasses.
`System.Object` contains too much, and `System.IConvertible` both admits values
outside Kotlin `Number` and specifies conversions that differ from Kotlin for
floating-point NaN, infinities, truncation, and saturation. The six built-in
Kotlin number types must retain their original CLR value-type boxes and object
identity at an erased boundary.

## Decision

### Logical class and physical carrier

KLIB remains authoritative for the logical abstract `kotlin.Number` class,
its members, inheritance, nullability, and bounds. A value whose static Kotlin
type is `Number` or `Number?` uses `System.Object` as its broad CLR signature
carrier. The carrier does not mean that every CLR object is a Kotlin number.

The runtime classifier's positive set is exactly:

1. boxed `System.SByte`, `System.Int16`, `System.Int32`, `System.Int64`,
   `System.Single`, or `System.Double`; and
2. instances of the runtime-owned abstract `Kotlin.Number` class.

`Boolean`, `Char`, unsigned scalar types, arbitrary `IConvertible`
implementations, enums, strings, and other objects are not members of this
classifier. Successful widening and casts preserve the original object.

### Kotlin-written subclasses

`Kotlin.Runtime.dll` owns one public abstract `Kotlin.Number` CLR class.
Kotlin-written subclasses of logical `Number` physically extend this class.
It contains the six abstract virtual conversion slots and the open `toChar`
default, implemented as `toInt()` followed by the Kotlin 16-bit conversion.

This physical base represents the subclass arm of the classifier; it is not a
wrapper or replacement for built-in numeric boxes. Direct calls on a known
subclass use ordinary CLR virtual dispatch. Calls through the broad logical
`Number` carrier use runtime dispatch that recognizes a built-in box or invokes
the corresponding virtual slot on `Kotlin.Number`.

Only a call whose resolved declaration is the Common `Number` member may use
this classified dispatch. A call resolved to a user override remains an
ordinary virtual call, including an override of deprecated `toChar`. An
explicit `super.toChar()` call invokes the runtime base body non-virtually; it
must not re-enter the classified helper and redispatch to the same override.

### Operations, casts, and reflection

The compiler and runtime use one classifier for `is Number`, `!is Number`,
`as Number`, `as? Number`, and `Number::class.isInstance`. Nullability is
applied around that classifier. Checked failure is the target's classified
Kotlin `ClassCastException`; a non-null checked cast of null retains Kotlin's
`NullPointerException` boundary.

Each broad conversion helper implements the Common conversion semantics for
all six built-in boxes, including floating-point NaN and saturation behavior,
then falls back to virtual dispatch on `Kotlin.Number`. Reflection exposes the
logical Common `Number` members as one complete classifier family; reflected
calls pass through the same classified operation boundary rather than
inventing a reflection-only conversion table.

### Generic bounds and interop

`T : Number` remains authoritative in KLIB but has no CLR
`GenericParamConstraint`. Constraining it to `Kotlin.Number` would reject all
six legal built-in substitutions, while constraining it to `IConvertible`
would admit invalid substitutions. The first Number operation or classified
cast rejects a foreign caller that bypasses the logical Kotlin bound.

Raw C# sees the broad Kotlin `Number` carrier as `object`. A future explicit
.NET export may provide a checked, typed facade, but must not replace the
Kotlin ABI or claim that `System.IConvertible` is Kotlin `Number`.

The physical `Kotlin.Number` base is public because Kotlin subclasses in other
assemblies must extend it. A C# type can therefore extend that CLR base and its
instances satisfy the runtime classifier, but this decision does not yet claim
that the foreign importer reconstructs such a type as a logical Kotlin
`Number` subclass. Static C#-authoring and Kotlin-import behavior require their
own exact importer/export gate; mere physical subclassability is not that gate.

### Runtime compatibility

The abstract class, virtual slots, classifier membership, cast helpers, and
conversion helpers are compiler/runtime ABI. Adding or changing them increments
the embedded runtime-surface level and must move all profiles together.

## Alternatives rejected

### Map `Number` to `System.Object` without classification

Rejected. It makes every object pass `is Number` and defers invalid casts to an
unrelated operation.

### Use `System.IConvertible`

Rejected. Its membership is broader than Kotlin `Number`, its conversions are
not the Common conversion contract, and user `Number` subclasses should not
have to expose a foreign protocol.

### Replace `Number` with a Kotlin-owned interface

Rejected. Common, JVM, and Native define an extensible abstract class, so an
interface would change Kotlin inheritance and reflection semantics without a
CLR necessity.

### Wrap built-in boxes in `Kotlin.Number`

Rejected. Wrapping changes object identity, foreign interop, allocation, and
the established primitive scalar representation.

### Duplicate built-in scalar and subclass behavior in every caller

Rejected. Type tests, casts, ordinary calls, generic-bound calls, reflection,
and separate consumers must share one versioned classifier and operation
boundary.

## Ownership

- Common Kotlin and KLIB: logical class identity, members, bounds, and
  inheritance.
- .NET type mapping and codegen: object carrier, physical subclass base,
  classified operations, and direct-override selection.
- `Kotlin.Runtime`: abstract subclass base, classifier, casts, and conversion
  helpers.
- Kotlin reflection catalog: declaration-derived `Number` member descriptors
  whose invocation uses ordinary compiler dispatch.
- Future .NET export tooling: optional checked C# surface above, never a second
  Kotlin number identity.

## Freeze conditions and boundaries

Before this representation freezes, tests must pin:

- all six built-in boxes and user subclasses through direct, widened,
  generic-bound, reflected, and separate-module calls;
- inherited and overridden `toChar` behavior;
- identity-preserving positive casts and hostile negative/null casts;
- exact `is` and `KClass.isInstance` membership;
- floating-point NaN, infinity, truncation, and saturation behavior;
- physical subclass inheritance, abstract/virtual slots, runtime-surface
  rejection, and equal behavior on Framework CLR and CoreCLR; and
- omission of an unsound physical CLR constraint for `T : Number`.

This ADR does not admit unsigned types as `Number`, define arithmetic on broad
numbers, choose a general foreign numeric-conversion protocol, or freeze a
C# export API.
