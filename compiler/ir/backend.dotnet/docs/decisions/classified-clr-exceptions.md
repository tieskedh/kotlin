# ADR: Classified CLR exception model

- Status: **Accepted — pre-ABI**
- Date: 2026-07-17
- Scope: throwable representation, Kotlin hierarchy classification, catches,
  signatures, identity-associated state, stack traces, and CLR interop

This is the selected repository direction for the experimental target. It is
not a public KEEP or an official Kotlin target commitment.

## Context

Every ordinary CLR throwable is a `System.Exception` object. VM, BCL, C#, and
third-party frames can create subclasses that Kotlin did not compile and
cannot comprehensively translate at their throw sites. Wrapping such an object
would change its reference identity, exact CLR type, stack trace, exception
data, serialization behavior, debugger presentation, and foreign catches.

Kotlin nevertheless defines logical distinctions that the CLR hierarchy does
not encode directly: `Throwable`, `Exception`, `RuntimeException`, `Error`,
cancellation, and the Common exception classes. No single physical inheritance
tree can truthfully represent both universes.

JVM can place Common state directly on `java.lang.Throwable`; JS, Wasm, and
Native control their throwable representation. The CLR-specific constraint is
that Kotlin must preserve arbitrary existing `System.Exception` objects.

## Decision

### One physical throwable universe

Every throwable value remains its original `System.Exception` object. The
compiler and runtime do not wrap, clone, translate, or replace a foreign value
when it enters Kotlin code, is caught, crosses a Kotlin call, or is rethrown.

An exact Kotlin-owned or CLR exception class is physical when that identity is
semantically truthful. Broad Kotlin categories that include objects from
multiple CLR roots use `System.Exception` as their physical carrier.

CLR conditions that terminate execution before managed handling remain a
platform limitation, not a fabricated Kotlin throwable.

### One versioned classifier

One runtime predicate classifies a physical exception against a logical
Kotlin exception type. Every non-exact Kotlin catch, type test, cast, and
boundary admission check uses that predicate. An exact CLR check is only an
optimization when CLR assignability is proven equivalent.

The classifier combines:

1. deliberate rules for known CLR exception types;
2. truthful CLR ancestry for Kotlin-owned or exactly mapped classes; and
3. versioned compiler metadata only for logical edges that truthful ancestry
   cannot encode.

It is deterministic, nonthrowing, safe inside a CLR exception filter, and
allocation-free on its hot path. The mapping table is runtime ABI rather than
emitter-local special cases. Runtime variants may recognize different
profile-specific BCL types, but they expose one classifier ABI and one Kotlin
hierarchy. Portable libraries reference only that shared ABI.

Unknown foreign subclasses classify as Kotlin `Exception`, but not
`RuntimeException`, unless a deliberate runtime rule or Kotlin metadata gives
them a narrower category. Fatal CLR failures classify as Kotlin `Error` only
where managed code can catch them.

### Cancellation preserves CLR identity and Kotlin ancestry

Kotlin `CancellationException` is physically
`System.OperationCanceledException`, so ordinary .NET cancellation objects,
including `TaskCanceledException`, retain identity and remain catchable from
both languages.

Its Kotlin parent is nevertheless `IllegalStateException`. Because
`OperationCanceledException` and `InvalidOperationException` are sibling CLR
types, the broad parent uses the shared carrier and classifier. Cancellation
therefore classifies as `CancellationException`, `IllegalStateException`,
`RuntimeException`, `Exception`, and `Throwable`, but not `Error`.

This logical relation is uniform across all supported profiles.

### Out-of-memory failure uses the exact CLR identity

Common `OutOfMemoryError` is physically `System.OutOfMemoryException`. The CLR type has the exact
parameterless and nullable-message constructor surface required by Common, and the classifier
already places managed CLR out-of-memory failures in Kotlin `Error`. A Kotlin construction,
including collection-capacity overflow, therefore retains the original CLR object and is caught as
both `OutOfMemoryError` and `Error` without a Kotlin wrapper.

JVM uses `java.lang.OutOfMemoryError`; JS, Wasm, and Native expose a target-owned physical error.
The CLR's existing exact type is the only platform delta. Mapping overflow to
`IllegalArgumentException`, inventing a Kotlin-owned sibling, or classifying every `Error` as
out-of-memory would change Common semantics or foreign identity and is rejected.

### Catch lowering preserves CLR search semantics

A Kotlin catch not exactly expressible as one CLR type becomes a filter over
`System.Exception`. The filter calls the classifier while preserving source
order, `finally`, and first-pass CLR search behavior.

The catch variable contains the original physical reference while IR and KLIB
retain its logical Kotlin type. Codegen never stores a foreign subtype in an
unrelated Kotlin-owned exception local.

### Signatures retain logical identity separately

Broad logical categories use `System.Exception` in CLR signatures and their
exact Kotlin type in KLIB. Entry and return boundaries classify values when a
narrower logical category must be enforced.

When carrier erasure makes Kotlin methods collide, their stable physical name
is derived from the owner-independent logical callable signature. It is never
derived from declaration order, the mapped CLR signature, or the currently
present overload set. Overrides inherit the selected logical slot's name;
`MethodImpl` or an adapter represents additional physical views.

The producer records the physical name and consumers use that record. The
variant-interface ADR continues to own separately named canonical interface
slots.

CLR constructors cannot be renamed. Colliding constructor overloads remain
unsupported until an explicit compiler-ABI factory representation is
accepted; dropping an overload, widening a logical type, or inventing a
wrapper is forbidden.

### Exact classes keep truthful ancestry

Kotlin-owned classes remain valid when an exact physical identity is required.
They derive from the truthful exact CLR base for their Kotlin parent, and
further Kotlin subclasses retain that ordinary CLR chain. This keeps CLR
reflection, debugging, typed catches, C# inheritance, and cross-module
derivation honest.

Physical ancestry is the classifier's first input. Supplemental Kotlin
metadata may add a non-physical logical edge but may never contradict the CLR
base chain. Exact classes supplement rather than replace the universal
exception universe.

### Throwing and causes preserve the object

Throwing a Kotlin-owned or foreign exception emits the original reference.
Source `throw e` emits CLR `throw`, not an inferred bare `rethrow`, unless a
future optimization proves Kotlin evaluation and observable CLR stack behavior
equivalent.

Kotlin-owned constructors map `cause` to `InnerException`. Foreign objects
retain their own `InnerException`, `Data`, message, stack, and exact type.

The standard `Throwable.message` and `Throwable.cause` properties use the
virtual `System.Exception.Message` and `InnerException` slots for mapped
declarations and source-defined subclasses. A source subclass owns IR fake
overrides whose FqName cannot be registered in advance, so codegen follows the
transitive override chain only when every real declaration remains in the
mapped standard exception hierarchy. A real Kotlin user override is ordinary
virtual Kotlin dispatch and must not be redirected to the CLR base slot.

### Kotlin-only state is identity-associated

Common `Throwable` state absent from `System.Exception` is stored by a
versioned `Kotlin.Runtime` service associated with the exact original object:

- the key is weak and the associated value does not retain its key;
- lookup and mutation are thread-safe and insertion-ordered;
- exact reference identity, not virtual equality, owns state and detects
  self-suppression or cycles;
- foreign extensibility surfaces, especially `Exception.Data`, are untouched;
  and
- retrieval exposes an immutable Kotlin snapshot rather than a mutable runtime
  collection.

The first such state is suppressed exceptions. Self-suppression is ignored,
duplicates are retained, and every non-empty read is a stable snapshot. The
same mechanism applies to Kotlin-owned, mapped BCL, and unknown foreign
objects.

The state also records the exact logical constructor classifier when two
Kotlin exception declarations truthfully share one physical CLR type, notably
`Throwable()` and `Exception()`. Dynamic `value::class` consults this tag so
the Common `KClass` identity remains exact without wrapping the exception or
writing `Exception.Data`. Untagged foreign exceptions continue through the
ordinary CLR ancestry and mapping rules.

### Stack traces compose Kotlin and CLR facts

Exact CLR type, message, inner-exception chain, and captured CLR frames remain
authoritative diagnostic facts. `stackTraceToString` composes them with Kotlin
suppressed-state semantics and reference-identity cycle detection;
`printStackTrace` writes that composed description to the platform error
stream.

Formatting may consume `System.Exception.ToString()` or lower-level CLR
properties, but it may not mutate the exception or discard Kotlin state.

### Interop boundaries are explicit

C# may pass any `System.Exception` to a Kotlin `Throwable` or `Exception`
surface. Narrower logical categories require a classifier guard unless their
physical type is exact. Boundary failure uses one stable Kotlin interop error;
it may not admit a value under a false logical type.

Callbacks and catches return the same object to CLR code. Nullable metadata
and C# conveniences do not redefine classification.

## Consequences

- Kotlin logical inheritance is uniform while foreign CLR identity and tooling
  behavior are preserved.
- Catch filters and physical/logical type separation are foundational backend
  mechanisms.
- The classifier, mapping table, supplemental hierarchy metadata, signature
  naming, and throwable-state service are versioned runtime/compiler ABI.
- Broad exception signatures are less idiomatic to C# than a fabricated class
  hierarchy; explicit export facades own ergonomic projections.
- Existing unpublished hybrid mappings may be corrected without a
  compatibility bridge.

## Freeze conditions and open decisions

Before ABI freeze, validation must cover:

- mixed broad/exact catch ordering and nonthrowing classifier filters;
- unknown foreign subclasses and known BCL faults on both application
  profiles through a portable library;
- identity, exact CLR type, `InnerException`, `Data`, message, stack trace, and
  suppressed state across catch and rethrow;
- inherited `message`/`cause` and user-overridden properties on Kotlin
  subclasses across separately compiled modules;
- returns, properties, generic positions, and narrow foreign boundaries; and
- C# provider/consumer behavior for broad, narrow, nullable, and exact
  exception surfaces.

The complete built-in mapping table, supplemental hierarchy encoding,
classifier caching, boundary-failure type, colliding-constructor ABI, and the
Common `CancellationException(cause)` factory remain open. None may weaken
object preservation, truthful ancestry, or the single-classifier invariant.
