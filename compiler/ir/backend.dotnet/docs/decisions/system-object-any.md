# ADR: `System.Object` as the Kotlin `Any` foundation

- Status: **Accepted — pre-ABI**
- Date: 2026-07-15
- Scope: Kotlin `Any` representation, virtual members, and cross-assembly
  runtime semantics

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Kotlin `Any` is the logical supertype of every non-null Kotlin value. On CLR,
that includes generated references, strings, boxed primitives, arrays, mapped
exceptions, and foreign objects. Kotlin exception classes must also remain in
the `System.Exception` hierarchy.

CLR has one root class, `System.Object`, and single class inheritance. Its
`Equals`, `GetHashCode`, and `ToString` virtuals are the physical counterparts
of Kotlin `equals`, `hashCode`, and `toString`.

JVM likewise maps `kotlin.Any` to its platform root. Native and Wasm own a root
because their object models require one; CLR already provides the only root
that every interoperable value can inhabit.

## Rejected alternatives

### A public `Kotlin.Any` base class

Rejected. Strings, CLR arrays, boxed values, foreign objects, and
`System.Exception` cannot inherit a second root. Kotlin exceptions cannot
extend both it and `System.Exception`.

### A universal marker interface

Rejected. Existing CLR types do not implement a Kotlin marker and cannot be
retrofitted. Wrapping them would change identity. A future capability
interface may serve a narrower role but cannot represent `Any`.

### Compiler-local helper duplication

Rejected. Null-safe equality, Kotlin hash normalization, and value-to-string
semantics are stable cross-assembly behavior. Copying their implementation
into every generated module would create multiple authorities.

## Decision

Kotlin `Any` has no standalone CLR TypeDef. Its physical type is
`System.Object`; its logical identity remains in the compiler and KLIB.

Generated classes without a proper Kotlin base extend `System.Object` and call
its constructor. Mapped and Kotlin-owned exceptions remain descendants of
`System.Exception` and therefore ordinary `Any` values through the same root.

### Virtual member mapping

Kotlin's three `Any` members reuse the existing `System.Object` slots.
Overrides emit an ordinary CLR override rather than a new slot. Calls through
`Any`, fake overrides, user overrides, and base views use the same physical
member signature and virtual dispatch. Explicit `super` calls remain
non-virtual.

CLR reflection and C# consequently see `Equals`, `GetHashCode`, and
`ToString`; Kotlin tools see the original Kotlin names and types from KLIB.

### Runtime semantic helpers

One compiler/runtime helper owner in `Kotlin.Runtime.Internal` supplies the
universal cross-assembly operations for equality, hashing, and string
conversion. Its exact physical member names are compiler ABI pinned by tests,
not source API.

Universal equality is null-safe and otherwise dispatches virtual `Equals`.
Boxed `Double` values use canonical IEEE-bit equality so every NaN payload is
equal while negative and positive zero differ. `===` remains direct reference
comparison and never calls the helper.

Universal hashing dispatches virtual `GetHashCode` while normalizing Boolean
and `Double` to Kotlin/JVM-compatible results. Equality and hashing therefore
remain consistent across Framework CLR and CoreCLR.

Universal string conversion returns `"null"` for null and otherwise preserves
Kotlin rendering, including lowercase Boolean, invariant integers, and Kotlin
floating-point spelling. It uses virtual `ToString` only where CLR behavior is
already Kotlin-compatible. String templates, concatenation, `println(Any?)`,
and `Any.toString()` share this type-directed boundary.

Specialized codegen may avoid boxing or helper calls only when it preserves
the same result. A value or open type parameter boxes when it reaches the
universal object fallback.

The helper owner is metadata-public because generated callers live in other
assemblies. Its reserved internal namespace marks compiler/runtime ABI, not a
Kotlin or C# user API.

## Ownership

- Common Kotlin/frontend: logical `Any`, member signatures, and nullability.
- .NET type mapping: the `System.Object` carrier and boxing boundaries.
- Backend: slot reuse, virtual/non-virtual calls, and specialized fast paths.
- `Kotlin.Runtime`: one cross-assembly fallback implementation.
- KLIB: Kotlin names, logical types, overrides, and declaration identity.

## Consequences

- Kotlin and CLR values share one physical root with no wrapper allocation for
  reference upcasts.
- Kotlin overrides participate in natural CLR virtual dispatch.
- Cross-assembly helpers add runtime ABI but no public `Kotlin.Any` type.
- Value operands may box at the universal fallback; optimizations must preserve
  identity and semantics.
- Introducing a distinct `Kotlin.Any` later would be an incompatible
  representation break.

## Freeze conditions and boundaries

Before ABI freeze, tests must pin:

- physical slot reuse and virtual versus `super` dispatch;
- null-safe equality, reference identity, canonical NaN/signed-zero behavior,
  and matching hashes;
- Kotlin string rendering for null, Boolean, integers, and floating point;
- strings, arrays, boxed values, mapped exceptions, Kotlin classes, and hostile
  foreign objects through separate assemblies; and
- identical observable behavior on Framework CLR and CoreCLR.

This foundation does not itself decide reflection APIs, general type tests,
foreign-object import, interface redeclarations of `Any`, data-class private
equality machinery, or general `T : Any` constraint encoding. Those consumers
must retain one physical `System.Object` root. Default platform
`System.Object.ToString()` text is not Kotlin ABI.
