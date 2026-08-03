# ADR: Common `Comparable` over CLR interface views

- Status: **Accepted — pre-ABI**
- Date: 2026-08-03
- Scope: `Comparable<T>`, built-in comparison carriers, generic bounds, casts,
  interface bridges, C# visibility, and separate compilation

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Common declares the contravariant interface `Comparable<in T>` and the single
`compareTo(T): Int` operation. Primitive classes, `String`, `Enum`, ordinary
Kotlin classes, and generic algorithms all consume that same logical
relationship. The result promises only a negative, zero, or positive value;
the ordering itself remains the Kotlin declaration's contract.

The mature targets preserve one Common identity but choose host integration
according to their runtime:

- JVM maps the built-in interface and carriers to Java's `Comparable`, while
  backend/runtime intrinsics preserve Kotlin primitive behavior.
- JS, Wasm, and Native own compatible Kotlin interface and carrier behavior;
  their enum and comparison implementations consume that interface rather
  than introducing an enum-private substitute.

CLR already exposes both `System.IComparable.CompareTo(object)` and the
contravariant `System.IComparable<T>.CompareTo(T)`. All selected Kotlin scalar
carriers and `System.String` implement both. They are therefore truthful
physical capabilities, but they are not a complete semantic implementation:
CLR string comparison is culture-sensitive on its instance path, and CLR
floating comparison orders NaN differently from Kotlin. The generic and
non-generic CLR interfaces also do not inherit one another.

## Decision

### Logical identity and physical views

KLIB retains authoritative `kotlin.Comparable<in T>` identity, variance,
bounds, and overrides. Its canonical CLR view is profile-selected
`System.IComparable`; its typed interop view is
`System.IComparable<in T>`. A Kotlin-produced implementation names both
interfaces on the same object.

The existing split-generic-interface lowering emits two private forwarding
bridges to the source implementation:

- `CompareTo(object)` implements the canonical erased slot and performs the
  checked argument cast or unbox at the use boundary;
- `CompareTo(T)` implements the contravariant typed slot for natural C# use.

Neither bridge wraps, clones, or translates the receiver. Unlike
Kotlin-owned split interfaces, the typed BCL interface is not claimed to
inherit the canonical BCL interface; the implementing class explicitly names
both truthful capabilities.

### Kotlin comparison operation boundary

A call whose logical dispatch slot is `Comparable.compareTo` widens or boxes
both operands to `object` once and calls a versioned Kotlin runtime helper.
The helper preserves exact Kotlin behavior for the built-in CLR carriers:

- `System.String` uses ordinal UTF-16 comparison and rejects a null or
  non-string argument at the logical use boundary;
- `System.Single` and `System.Double` use the existing Kotlin total-order
  helpers, including NaN and signed zero; and
- every other admitted carrier dispatches through the same receiver's
  canonical `System.IComparable` slot.

Direct calls to an ordinary Kotlin class's concrete `compareTo` body remain
ordinary virtual calls. Existing exact primitive intrinsics may remain direct
when their behavior is identical to the semantic helper. These are execution
specializations of one logical operation, not additional Kotlin identities.

### Generic bounds

The recursive Common shape `T : Comparable<T>` is admitted as a real CLR
generic parameter. KLIB retains the full recursive typed bound. CLR metadata
records the truthful canonical `System.IComparable` constraint; the generic
method body uses the same semantic helper, so primitive substitution and
Kotlin floating/string ordering remain correct.

The first physical constraint intentionally does not promise
`System.IComparable<T>`. Every Kotlin-produced implementation exposes that
typed view, but the canonical constraint is the minimum relationship needed
by Kotlin execution and also admits a foreign non-generic comparable without
inventing a typed relationship. A future C# export facade may publish a
stronger convenience constraint only when its accepted inputs and forwarding
semantics are explicit.

### Casts, tests, and foreign values

`is Comparable<*>`, checked casts, safe casts, and unchecked parameterized
casts classify the canonical `System.IComparable` identity. Existing CLR
values implementing that interface are therefore admissible foreign
comparables. This does not reinterpret a foreign value as a Kotlin primitive
or grant it another Kotlin declaration identity.

Parameterized casts remain erased. A cast such as `String as Comparable<Int>`
may succeed; the incompatible argument fails only when `compareTo` performs
the later checked typed use. This is the same Kotlin erasure rule used by the
other split generic interfaces.

A foreign value implementing only `System.IComparable<T>` does not satisfy
this logical identity: that typed CLR interface has no universal erased
instantiation. A foreign canonical-only `System.IComparable` value does
satisfy it and executes through the fallback above. Any broader typed-only
foreign enhancement belongs to the separate CLR-import programme and must not
silently change Kotlin runtime identity.

### Profile and ABI ownership

The physical BCL owner follows the selected core-library profile:
`mscorlib` for Framework/CoreCLR executable profiles and `netstandard` for the
portable library profile. No Kotlin runtime duplicate of either CLR interface
is emitted.

The profile-aware type mapper owns the two physical interface identities and
method names. The generic-interface lowering owns bridges. The runtime owns
only the semantic operation helper. KLIB and the ordinary Common declaration
remain the source of Kotlin identity.

## Alternatives rejected

### Reuse `System.IComparable<T>` as semantic authority

Rejected. It gives culture-sensitive string behavior and CLR floating NaN
ordering when the call is made through a generic Kotlin boundary.

### Use only a Kotlin-owned `Kotlin.Comparable` interface

Rejected. It would exclude sealed built-in CLR carriers or require wrappers,
losing identity and direct C# interoperability despite the BCL already
providing truthful physical capabilities.

### Use only `System.IComparable`

Rejected as the complete physical surface. It is a sufficient erased Kotlin
carrier but needlessly hides the truthful typed `IComparable<T>` view from C#
and from generated implementation metadata.

### Use only `System.IComparable<T>`

Rejected. Parameterized Kotlin casts and stars require one declaration-erased
identity, and the CLR generic interface has no universal closed instantiation.

### Create an enum-only comparison base

Rejected. Common enums consume the same public `Comparable<E>` contract as
ordinary classes and algorithms. An enum-local substitute would fork Common
semantics and leave the actual prerequisite unresolved.

## Consequences

- `Comparable` becomes an independently useful language/stdlib foundation and
  removes one blocker from the later enum cluster.
- Kotlin implementations are naturally callable through both standard CLR
  interfaces, with no adapter object.
- Polymorphic Kotlin comparison boxes value operands at the semantic boundary.
  Direct primitive calls retain their existing unboxed intrinsics; further
  typed fast paths require measurement and must not bypass Kotlin ordering.
- A raw CLR consumer can invoke either physical interface without reading
  KLIB. The typed view is convenient, while KLIB remains authoritative for
  Kotlin variance and recursive bounds.
- Changing either physical interface, bridge slots, classifier membership, or
  the helper's built-in dispatch after publication is an ABI break.

## Freeze conditions and boundaries

Before this representation freezes, tests must pin:

- direct, canonical, contravariant, and recursive-bound calls for ordinary
  Kotlin implementations;
- every selected primitive carrier plus ordinal string, NaN, and signed-zero
  behavior;
- positive/negative type tests and erased casts with failure delayed until an
  incompatible argument use;
- canonical and typed InterfaceImpl/MethodImpl rows and exact profile owners;
- portable producer consumption from Kotlin and C# on Framework CLR and
  CoreCLR; and
- canonical-only foreign inclusion and typed-only foreign exclusion.

This ADR does not implement enums, collection sorting/min-max families,
foreign-interface enhancement (including malformed foreign metadata), or
typed fast paths for polymorphic Kotlin calls. Those features may consume this
representation but cannot replace its Common identity or weaken the semantic
helper.
