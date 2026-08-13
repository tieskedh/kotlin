# ADR: Kotlin semantic authority and bounded CLR platform freedom

- Status: **Accepted — pre-ABI**
- Date: 2026-08-13
- Scope: every Kotlin/.NET lowering, physical ABI, runtime check, bridge,
  optimization, and interop projection whose observable behavior could differ
  from another Kotlin platform

This is the selected repository direction for the experimental target. It is
not a public KEEP or an official Kotlin core-team decision.

## Context

CLR metadata and runtime type information can represent facts which an erased
Kotlin implementation cannot test. Using those facts can improve failure
locality, C# interoperability, optimization, and diagnostics. It can also
silently replace Kotlin subtyping, variance, override, or cast semantics with
whatever the CLR happens to enforce.

Neither maximal CLR fidelity nor emulation of one mature target is the
language authority. The Kotlin specification defines required observations
and, in some places, deliberately leaves runtime representation, outcome, or
failure timing to the platform or implementation.

The specification's [cast-expression rules][cast-expressions] make the
distinction concrete:

- a throwing `E as T` must fail immediately for a runtime-available
  non-parameterized `T`, but for other targets it is implementation-defined
  whether it fails at the cast expression; and
- for safe `E as? T` with a runtime-available parameterized `T`, generic
  parameters are not checked with respect to subtyping.

The [RTTI rules][runtime-type-information] also require platform
specifications to clarify which generic types have distinct runtime
representations. The [generic-cast documentation][unchecked-casts] explains
why a concrete parameterized cast may receive an unchecked warning. A warning
describes limited proof; it does not transfer semantic authority to the CLR.

## Decision

> Kotlin semantics remain authoritative. Kotlin/.NET uses a stronger CLR
> runtime check only where the Kotlin specification deliberately leaves the
> runtime outcome or failure point platform- or implementation-dependent.

Every use of that freedom is operation-specific. The owning ADR must identify
the exact specification permission and the CLR fact used. Similar syntax, an
unchecked warning, `@Suppress`, `@UnsafeVariance`, a reified physical carrier,
or a convenient `isinst` instruction is not independent permission.

### Required Kotlin behavior

Source-legal Kotlin behavior is preserved completely. This includes:

- ordinary subtyping, declaration-site variance, use-site projections, and
  star projections;
- override-family dispatch, `super`, defaults, and separate compilation;
- widened receiver and argument views;
- broad candidate inputs whose Kotlin body accepts a value that a strict CLR
  construction would reject;
- successful-cast object identity, mutation, synchronization, and virtual
  dispatch; and
- the specified result and failure behavior of the exact operation being
  lowered.

The CLR may implement these semantics through a capability, bridge, helper,
or classified check. It may not narrow them because a constructed CLR type is
more restrictive.

`@UnsafeVariance` only suppresses the compiler's variance error for the
annotated type use. It does not permit a backend to skip the Kotlin body,
narrow a broad candidate before dispatch, introduce a second store, or move a
failure unless a separate language rule permits that exact observation. This
follows the specification's narrow definition of
[`kotlin.UnsafeVariance`][unsafe-variance].

### Bounded platform freedom

For a parameterized throwing cast such as:

```kotlin
@Suppress("UNCHECKED_CAST")
val ints = strings as Box<Int>
```

the specification permits implementation-defined failure at the cast point.
An admitted true CLR-generic owner may therefore reject a physically
incompatible `Box<string>` to `Box<int>` request immediately. Success still
returns the same object, and failure uses the accepted Kotlin exception
classification. This permission does not apply to an ordinary variance or
projection conversion and does not authorize a wrapper, copy, or second
logical view.

The warning is not the reason this is permitted. The cast-expression rule for
failure timing is the reason. If that rule changes, the target decision must
be revisited.

### Safe casts are evaluated separately

The permission above does not extend mechanically from `as` to `as?`. For a
parameterized safe-cast target, the specification says that generic arguments
are not checked with respect to subtyping. A Kotlin-owned `C<A> as? C<B>`
therefore uses the logical classifier/capability check, returns the same object
when that classifier matches, and otherwise returns `null`. It does not use
the exact constructed CLR owner as a universal safe-cast predicate or claim an
exact `C<B>` physical carrier for the successful semantic view.

Non-parameterized safe casts and separately specified reified operations keep
their own accepted rules. No rule in this ADR generalizes one operation's
platform freedom to another.

### Physical implementation freedom

Boxing, helpers, bridges, private storage, devirtualization, and scalar
replacement may use the best truthful CLR mechanism when disabling that
mechanism leaves every supported Kotlin observation unchanged. Once a choice
changes failure timing, identity, dispatch, reflection, public/protected ABI,
or cross-module behavior, it is no longer an implementation detail and needs
an explicit semantic/ABI decision.

## Evidence required for a stronger CLR check

The change and its owning ADR must establish all of the following:

1. the exact Kotlin operation and specification clause that permits the
   platform difference;
2. the exact CLR runtime or metadata fact that makes the check truthful;
3. the result, identity, exception classification, and failure point on every
   successful and unsuccessful path;
4. negative coverage proving that valid variance, projections, override
   families, broad candidates, and separate consumers are not narrowed; and
5. honest diagnostics and reflection which claim no stronger check than the
   runtime actually performs.

Absence of any item keeps the semantic fallback authoritative.

## Consequences

- Kotlin/.NET need not retain an erased physical representation solely to
  reproduce behavior which Kotlin explicitly leaves implementation-defined.
- Greater CLR precision is admitted selectively, never as a target-wide
  "stricter casts" policy.
- A true CLR-generic Kotlin owner may use earlier failure for incompatible
  parameterized throwing casts only as part of its atomic ABI decision.
- The generic-owner semantic capability remains mandatory for ordinary Kotlin
  variance, projections, broad candidate inputs, and parameterized safe casts.
- Compiler warnings and suppression annotations remain diagnostics/source
  permissions, not backend semantic permissions.

## Rejected alternatives

### Always use the strongest available CLR check

Rejected. It changes valid Kotlin subtyping, safe-cast, and override-family
behavior whenever CLR constructed-type identity is narrower.

### Reproduce JVM erasure even where Kotlin permits a platform difference

Rejected as a universal rule. Mature-target behavior is valuable comparison
evidence, but it is not mandatory where the Kotlin specification deliberately
allows a truthful CLR result.

### Treat an unchecked warning or `@UnsafeVariance` as a semantic waiver

Rejected. Those mechanisms describe limited static proof or suppress a source
restriction; they do not authorize arbitrary runtime behavior.

### Repair a stronger check with a wrapper or copied object

Rejected. A cast or legal Kotlin view preserves the original object's
identity, state, synchronization, and virtual dispatch.

[cast-expressions]: https://kotlinlang.org/spec/expressions.html#cast-expressions
[runtime-type-information]: https://kotlinlang.org/spec/runtime-type-information.html#runtime-type-information
[unsafe-variance]: https://kotlinlang.org/spec/annotations.html#kotlinunsafevariance
[unchecked-casts]: https://kotlinlang.org/docs/generics.html#unchecked-casts
