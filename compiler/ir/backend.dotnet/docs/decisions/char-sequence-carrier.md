# ADR: classified `CharSequence` carrier

- Status: **Accepted — pre-ABI**
- Date: 2026-08-01
- Scope: `CharSequence`, `String`, runtime classification, physical interface
  implementation, casts, type tests, and generic bounds

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Common Kotlin defines `String` as a `CharSequence`. A `CharSequence` value can
therefore be either the platform string or an arbitrary Kotlin implementation,
and every operation, cast, type test, generic bound, and separate-module call
must admit both without changing object identity.

The mature targets preserve that logical model:

- JVM maps the relationship onto `java.lang.String` and
  `java.lang.CharSequence`, which the host already relates physically.
- Native and Wasm own compatible string and character-sequence
  representations.
- JavaScript keeps host strings unwrapped, recognizes either a JavaScript
  string or a Kotlin `CharSequence` implementation in `is CharSequence`, and
  rewrites `length`, `get`, and `subSequence` to runtime dispatch helpers.

CLR has the JavaScript-shaped constraint. `System.String` is sealed and does
not implement a Kotlin-owned interface. No existing BCL interface gives both
`System.String` and arbitrary Kotlin implementations the exact Common
`CharSequence` contract. Retrofitting an interface is impossible; wrapping a
string would change `===`, foreign identity, and ordinary CLR interop.

## Decision

### Logical identity and physical carrier

KLIB remains authoritative for the logical `kotlin.CharSequence` type and for
the declaration that `kotlin.String` implements it. A value whose static
Kotlin type is `CharSequence` or `CharSequence?` uses `System.Object` as its
physical CLR signature carrier.

`kotlin.String` remains the original `System.String` reference. A widening to
`CharSequence` emits no wrapper, copy, translation, or identity association.
The object carrier is not a claim that every CLR object is a CharSequence; it
is only the common storage shape for the classified set.

### Kotlin implementation capability

`Kotlin.Runtime.dll` owns one non-generic `Kotlin.CharSequence` CLR interface
for Kotlin and explicitly authored foreign implementations. Its slots are the
physical capabilities for Common `length`, `get`, and `subSequence`.
Kotlin-generated classes that logically implement `CharSequence` implement
this interface on the same object.

The bootstrap interface keeps the compiler's ordinary physical member names:
`get_length`, `get`, and `subSequence`. `subSequence` returns `System.Object`,
because a valid logical result may again be either `System.String` or an
implementation of the capability. This avoids a second bridge ABI when one
implementation also satisfies another Kotlin interface with the same logical
member. The runtime C# implementation manifest records the logical members
and these physical slots; future explicit export tooling may add idiomatic
Pascal-cased conveniences without renaming Kotlin dispatch slots or inferring
the relationship from names alone.

`System.String` deliberately does not and cannot implement this interface.
The interface is one arm of the classifier, not the universal carrier.

### One runtime classifier and operation boundary

Compiler-generated code uses one runtime-owned classifier whose positive set
is exactly:

1. non-null `System.String` values; and
2. non-null values implementing `Kotlin.CharSequence`.

`is CharSequence`, `!is CharSequence`, `as CharSequence`, and
`as? CharSequence` all use that classifier. A successful cast returns the
original reference. A failed checked cast throws the mapped Kotlin
`ClassCastException`; a safe cast returns null. Nullability is applied around
the same classifier, rather than weakening its non-null positive set.

Calls through a logical `CharSequence` use runtime helpers for `length`,
`get`, and `subSequence`. The string arm calls the corresponding
`System.String` operation. The implementation arm dispatches through
`Kotlin.CharSequence`. Direct calls on a concrete Kotlin implementation may
use its ordinary virtual member, but must occupy the same interface slots.

The string `subSequence` arm validates Common index boundaries before calling
`System.String.Substring`; it must not leak the BCL's broader
`ArgumentOutOfRangeException` classification where Kotlin requires an index
failure. A malformed value reaching an operation helper fails by checked
interface dispatch rather than being treated as an empty sequence or by
calling `ToString`.

### Generic bounds

A Kotlin type parameter bounded by `CharSequence` remains a real CLR generic
parameter in physical method and class signatures. The logical bound remains
in KLIB, but it is omitted from CLR `GenericParamConstraint` rows: constraining
the parameter to `Kotlin.CharSequence` would reject the legal
`T = kotlin.String` substitution.

Operations on such a `T` box/widen the value to the object carrier and use the
same classifier helpers. Kotlin callers remain constrained by KLIB. A foreign
caller that bypasses that logical bound can instantiate the unconstrained CLR
parameter, but the first CharSequence operation or cast rejects an invalid
value. An explicit future C# export facade may publish a friendlier checked
surface; it must not replace the Kotlin virtual or generic ABI.

### Runtime compatibility

The interface, helper names, and classifier membership are compiler/runtime
ABI. Adding them increments the embedded runtime-surface level. Every profile
uses the same logical classifier and physical interface identity even when
the core-library reference or helper IL differs.

## Alternatives rejected

### Map `CharSequence` to `System.String`

Rejected. It excludes user implementations, breaks generic substitutions,
and makes `subSequence` results falsely string-only.

### Use only `Kotlin.CharSequence` as the carrier

Rejected. `System.String` cannot implement it. Wrapping strings or changing
their identity would contradict Kotlin's platform-string model and CLR
interop.

### Treat every `System.Object` as a CharSequence

Rejected. The object is only a carrier. Universal admission would make type
tests and casts unsound and defer failures to unrelated member calls.

### Use `System.Collections.Generic.IEnumerable<char>` or another BCL shape

Rejected. `System.String`'s implemented interfaces and the BCL enumeration
protocol do not provide the Common indexed sequence contract, and an adapter
would change identity and evaluation behavior.

### Make `System.Text.StringBuilder` the `CharSequence` identity

Rejected as a representation argument. The BCL builder is not a common base
of strings and Kotlin implementations and does not implement the selected
capability. Whether a Kotlin `StringBuilder` uses it as private storage is a
separate stdlib implementation decision.

## Ownership

- Common Kotlin and KLIB: logical identity, members, nullability, inheritance,
  and generic bounds.
- .NET type mapping and codegen: object carrier, interface implementation,
  classified casts/tests, and member dispatch.
- `Kotlin.Runtime`: capability interface, classifier, checked/safe casts, and
  operation helpers.
- Runtime C# implementation manifest: explicit foreign implementation slots.
- Kotlin stdlib: `Appendable`, `StringBuilder`, and all ordinary text
  algorithms; none are moved into the runtime by this decision.

## Consequences

- Strings cross `CharSequence` boundaries with their original CLR identity.
- Kotlin and explicitly authored C# implementations remain possible without
  pretending that `System.String` implements a new interface.
- A `CharSequence` parameter appears as `object` to raw CLR consumers; the
  implementation manifest and future explicit export tooling provide the
  source-authoring view.
- Runtime classification and helper calls add a small cost at logically
  polymorphic operations. Direct String or concrete-implementation operations
  may remain specialized when semantics are identical.
- Changing the positive classifier set, object carrier, capability identity,
  or generic-bound erasure after publication would be an ABI break.

## Freeze conditions and boundaries

Before this representation freezes, tests must pin:

- raw string and custom-implementation identity through nullable and widened
  `CharSequence` slots;
- all three operations through string, interface, generic-bound, and
  separate-assembly views;
- positive, negative, nullable, checked, and safe type operations without
  false admission of arbitrary objects;
- physical interface slots, class interface maps, runtime C# manifest
  locators, and runtime-surface rejection;
- invalid index and invalid-cast exception classification; and
- equal behavior on Framework CLR and CoreCLR.

This ADR does not select a `StringBuilder` storage representation, implement
`Appendable`, admit the generated string algorithm corpus, define general
reflection, or make arbitrary BCL character containers Kotlin
`CharSequence`s. Those features may build on this carrier but cannot collapse
its two-arm classifier or wrap `System.String`.
