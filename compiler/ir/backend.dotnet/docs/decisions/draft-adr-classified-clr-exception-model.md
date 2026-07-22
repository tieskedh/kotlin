# Draft ADR: Classified CLR exception model

- Status: **Accepted pre-ABI direction; foundational slice implemented**
- Date: 2026-07-17
- Scope: physical throwable representation, Kotlin hierarchy classification, catches, signatures,
  rethrow, and CLR interop

This is the selected repository direction for the experimental backend. Nothing has shipped, so it
may replace the current hybrid mappings without a compatibility bridge. It is not a public KEEP or
an official Kotlin target commitment.

## Context

Every ordinary CLR throwable is a `System.Exception` object. VM, BCL, C#, and third-party frames
can create subclasses that Kotlin did not compile and cannot comprehensively translate at their
throw sites. Wrapping those objects would change reference identity, exact CLR type, stack trace,
exception data, serialization behavior, debugger presentation, and foreign catch behavior.

Kotlin nevertheless defines logical distinctions that the CLR hierarchy does not encode directly:
`Throwable`, `Exception`, `RuntimeException`, `Error`, cancellation, and specific common exception
classes. A physical inheritance tree alone cannot represent both universes.

The current backend's mapped and Kotlin-owned hybrid types are observable implementation facts.
They are not compatibility constraints and are superseded where they conflict with this decision.

## Decision

### One physical throwable universe

`System.Exception` is the universal physical carrier for a throwable whose Kotlin logical category
does not require an exact CLR class in the signature. Every foreign exception remains the original
object. The backend does not wrap, clone, translate, or replace it when it enters Kotlin code, is
caught, crosses a Kotlin call, or is rethrown.

This applies to every catchable foreign exception. CLR conditions that terminate execution before
managed handling, such as a runtime-declared uncatchable stack overflow, remain an explicit
platform limitation rather than a fake Kotlin exception value.

### One logical classification service

The runtime exposes one versioned classification predicate conceptually equivalent to:

```text
isKotlinExceptionInstance(System.Exception value, KotlinExceptionTypeId target): Boolean
```

All non-exact Kotlin exception catches, type tests, boundary admission checks, and logical casts use
that predicate. An exact Kotlin-owned or exactly mapped CLR class uses ordinary CLR assignability
when that is proven equivalent. The predicate must be deterministic, allocation-free on the hot
path, nonthrowing, and safe to execute from a CLR exception filter. Its input object is never
replaced.

The classifier combines:

1. built-in rules for CLR exception types that have a deliberate Kotlin category;
2. truthful CLR ancestry for Kotlin-owned exact classes rooted in `Kotlin.RuntimeException`,
   `Kotlin.Error`, or a deliberately mapped BCL exception; and
3. compiler-emitted hierarchy metadata only for a logical edge that cannot be represented by that
   ancestry without lying to ordinary CLR tooling.

Unknown foreign `System.Exception` subclasses classify as Kotlin `Exception`, but not
`RuntimeException`, unless a target runtime rule or Kotlin metadata assigns a narrower category.
Known fatal CLR failures classify as Kotlin `Error` where the CLR allows them to be caught. Known
programming/runtime faults and cancellation types receive their deliberate Kotlin categories. The
mapping table is a versioned runtime contract, not a set of emitter-local special cases.

Target runtime variants may contain different built-in CLR type rules because Framework 4.8 and
.NET 10 expose different BCL types. They implement the same predicate ABI and Kotlin hierarchy.
The `netstandard2.0` surface references only the shared predicate; deployment selects the runtime
variant appropriate to the consuming application.

### Cancellation and the `IllegalStateException` parent edge

Kotlin `CancellationException` is physically `System.OperationCanceledException`. This is a
deliberate CLR-specific identity: ordinary .NET cancellation objects, including
`TaskCanceledException`, remain their original objects and are directly catchable by both Kotlin
and C# cancellation handlers. Kotlin construction selects the corresponding
`OperationCanceledException` constructor, and Kotlin subclasses use it as their truthful CLR base.

The logical Kotlin parent is nevertheless `IllegalStateException`. Because
`OperationCanceledException` and `InvalidOperationException` are sibling CLR types,
`IllegalStateException` cannot retain `InvalidOperationException` as its universal signature
carrier without either rejecting the valid parent assignment or wrapping cancellation. It
therefore uses `System.Exception` as its carrier, constructs `InvalidOperationException`, and uses
the classifier for catches and type tests. A cancellation object classifies as
`CancellationException`, `IllegalStateException`, `RuntimeException`, `Exception`, and
`Throwable`, but never as `Error`. An ordinary `InvalidOperationException` classifies as
`IllegalStateException` but not as `CancellationException`.

This mapping is uniform on `net48`, `netstandard2.0`, and `net10.0`; all three profiles expose the
required BCL cancellation root. Numeric classifier id 14 is assigned to
`CancellationException`. Runtime surface level 7 and physical ABI schema 13 introduce the rule;
older unpublished libraries/runtimes are rejected because their `IllegalStateException`
signatures are physically incompatible.

### Catch lowering

A Kotlin catch whose logical type is not exactly expressible as one CLR type is emitted as a CLR
filter over `System.Exception`. The filter calls the classification predicate. Catch ordering,
`finally`, and first-pass CLR search semantics must match Kotlin source order.

The catch variable physically contains the original `System.Exception` reference while IR and
Kotlin metadata retain its logical Kotlin type. Codegen must never store a foreign CLR subtype into
an unrelated Kotlin-owned exception local. An exact CLR typed catch is only an optimization when it
is proven equivalent to the classifier for that Kotlin type.

### Signatures and overloads

Logical category types such as Kotlin `Throwable`, `Exception`, `RuntimeException`, `Error`, and
`IllegalStateException` use `System.Exception` in physical CLR signatures when the category can
contain foreign objects or truthful children with different CLR roots.
Kotlin metadata records the logical type. Entry and return boundaries apply the same classifier
when a narrower logical category must be enforced.

Erasure to `System.Exception` may make otherwise distinct Kotlin overloads collide physically.
Every method with a non-dispatch physical parameter that directly or transitively contains one of
the shared-carrier categories receives a stable Kotlin ABI name of the form:

```text
<ordinary-name>__KotlinException__<logical-signature-digest>
```

The digest is derived from Kotlin's owner-independent logical callable signature, including nested
type arguments. An override derives the name from its selected logical slot declaration: an
override of `Base<T>.f(T)` at `T = Throwable` must retain the unmangled generic base slot rather
than deriving a new name from the substituted parameter. It is not derived from declaration
order, the mapped CLR signature, or the set of currently present overloads. Consequently a later
overload does not rename an existing method, distinct logical overloads remain distinct after CLR
erasure, and an ordinary override retains exactly the physical name of its base or interface slot.
If one Kotlin override must satisfy physical views with different names, an explicit `MethodImpl`
mapping owns that difference; a generated adapter is added only when the signatures also differ.
The backend does not select one slot arbitrarily. The producer records ordinary names in the
physical declaration index; metadata
consumers call the recorded name. Generic-interface typed views use the same logical rule, while
their separately named canonical erased slots remain governed by the variant-interface ABI.

Physical-name grammar version 2 introduces this rule. Prototype libraries carrying the earlier
grammar are rejected rather than guessed or bridged because no artifact has shipped.

This method-name mechanism cannot represent two colliding CLR constructors because `.ctor` is not
renamable. Such constructor overloads remain unsupported until a separate compiler-ABI factory
representation is accepted; silently dropping an overload is forbidden. Widening one category or
inventing a wrapper is not an acceptable fix. C# export facades choose explicit projected names
and normally expose `System.Exception` for a classified category.

### Exact Kotlin-owned types

Kotlin-owned CLR exception classes remain valid when exact physical identity is semantically or
interoperably required, for example a compiler-defined exception with no foreign equivalents or a
user-declared exception class. They physically derive from the truthful exact CLR base selected for
their Kotlin parent: a user `RuntimeException` subclass derives from `Kotlin.RuntimeException`, an
`Error` subclass from `Kotlin.Error`, and a subclass of an exactly mapped Kotlin exception from its
BCL class. Further Kotlin subclasses retain that ordinary CLR chain. This keeps reflection,
debuggers, C# inheritance, typed catches, and cross-module derivation honest.

Physical ancestry is the first classifier input for Kotlin-owned classes. Compiler hierarchy
metadata supplements it only where a future logical relationship cannot be encoded truthfully in
CLR ancestry; metadata must not replace or contradict the CLR base chain merely to make the
classifier convenient.

An exact class supplements the universal exception universe; it does not become a replacement root
for foreign exceptions. A broad logical parent catch continues to accept classified BCL objects
and Kotlin-owned objects alike.

### Throw and rethrow

Throwing a Kotlin-owned or foreign exception emits the original reference. Kotlin has no bare
rethrow statement: source `throw e`, including when `e` is a catch variable, evaluates and throws
that value and therefore emits CLR `throw`, not an inferred `rethrow`. This preserves object
identity; the CLR's documented stack-trace behavior is the platform behavior. A future optimization
may use `rethrow` only if Kotlin evaluation and observable platform behavior are proven equivalent.

`cause` maps to `InnerException` for Kotlin-owned constructors. Foreign objects retain their own
`InnerException`, `Data`, stack trace, and exact type without normalization.

### Import and export boundary

C# may pass any `System.Exception` to a Kotlin `Throwable` or `Exception` surface. Narrower logical
categories require a generated classifier guard unless the physical CLR parameter is an exact
class. Failure is reported at the boundary with a stable Kotlin interop exception; it must not
enter Kotlin under a false logical type.

Exported catches or callbacks return the same exception object to CLR code. Nullable metadata and
export conveniences do not redefine classification.

## Consequences

- Kotlin logical inheritance is uniform while foreign CLR identity and tooling behavior are kept.
- Catch filters and physical/logical type separation become fundamental backend mechanisms.
- Exception-typed physical signatures are less idiomatic to C# than a fabricated class hierarchy;
  export facades own any ergonomic projections.
- The classifier and hierarchy metadata are runtime ABI and must be versioned and tested across
  modules and target profiles.
- Existing hybrid mappings may be deleted rather than migrated because no artifact has shipped.

## Implemented foundation

The current foundational slice implements:

1. separate carrier, constructor/subclass-base, typed-catch, and classifier roles in one mapping
   registry;
2. `System.Exception` physical storage for the broad categories and stable numeric classifier ids;
3. runtime-owned exact `Kotlin.RuntimeException` and `Kotlin.Error` roots;
4. classifier-backed CLR filters for broad catches and classifier-backed broad type tests;
5. normal typed handlers for exact Kotlin-owned classes and exactly mapped BCL types;
6. direct construction, throwing, identity comparison, and inheritance of user exception classes;
7. portable-library exception ancestry consumed and further subclassed by both `net48` and
   `net10.0` applications; and
8. C# reflection/runtime tests proving CLR ancestry and preservation of foreign exact type,
   identity, `InnerException`, and `Data` on both application profiles; and
9. stable physical disambiguation of direct and nested-generic classified-exception method
   overloads, including ordinary and generic-base class overrides, ordinary and split-generic
   interface implementations, and one override satisfying differently named class/interface
   slots, from a `netstandard2.0` producer consumed and executed on both application profiles; and
10. exact `OperationCanceledException` cancellation identity plus the classified
    `IllegalStateException` parent edge, including owned, foreign, and `TaskCanceledException`
    values, cause preservation, and a Kotlin-owned subclass on both application profiles.

This is not completion of the ADR. In particular, narrow exported-parameter admission, exception
returns and properties at foreign/narrow boundaries, constructor collisions, broader generic
boundary positions, and any necessary hierarchy-metadata encoding remain open.

## Required validation

Before source exception support expands, commit tests for:

1. remaining mixed `Throwable`, `Exception`, `RuntimeException`, `Error`, cancellation, and
   exact-class catch-order combinations;
2. unknown C# exception subclasses and known BCL faults on both `net48` and `net10.0`;
3. one `netstandard2.0` library catching exceptions supplied by applications on both runtimes;
4. `===` identity, exact CLR type, `InnerException`, `Data`, message, and stack trace before and
   after Kotlin catch/rethrow;
5. Kotlin user exception subclasses across separately compiled modules;
6. exception-typed returns, properties, constructor collisions, and remaining generic/boundary
   positions (direct and nested-generic method overloads are now covered);
7. classifier behavior during filters, including a guarantee that it cannot throw; and
8. C# provider/consumer tests for broad, narrow, nullable, and exact exception surfaces.

## Deferred details

The existing numeric type-id allocation is now embedded in generated assemblies and is append-only
from this implementation point, though it may still be deliberately replaced before the first ABI
gate because nothing has shipped. Hierarchy-metadata encoding (if a non-physical logical edge proves
to need it), classifier caching, boundary-failure exception type, and the complete built-in CLR
mapping table remain implementation decisions. They must be fixed before Gate B and may not change
the object-preservation, truthful-ancestry, or single-classifier invariants.

The common `CancellationException(cause)` top-level factory is part of the remaining mapped-
constructor/factory ABI work. Its eventual lowering must preserve the common message/cause contract
without inventing a second cancellation identity or exposing a profile-dependent surface.
