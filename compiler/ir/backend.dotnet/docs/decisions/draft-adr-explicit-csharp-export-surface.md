# Draft ADR: Explicit C# export surface

- Status: **Draft**
- Date: 2026-08-27
- Scope: additive C# naming, delegates, properties, default overloads,
  nullability, adapters, admission, and collision policy
- Related: [C# interface source authoring](adr-csharp-interface-source-authoring.md),
  [generic-owner physical authority](draft-adr-generic-owner-physical-authority.md),
  [generic-interface reopening](draft-adr-reified-generic-interface-owner.md),
  and the
  [direct C# generic-owner surface programme](../programmes/generic-class-owner-csharp-surface.md)

## Context

Some Kotlin declarations already have a truthful and useful CLR surface. This
can include a direct natural CLR-generic owner when the generic-owner rehearsal
proves that representation. Such a declaration should be consumed directly by
C#; wrapping it merely for uniformity would add identity, tooling, and ABI
cost without adding truth.

Other Kotlin constructs do not naturally spell the C# API users expect.
Kotlin function types are not `System.Func` or `System.Action`, Kotlin default
arguments are callee-executed, a CLR property row can be useful in addition to
Kotlin accessors, and a different C# name may be desirable. Some genuine
semantic mismatches require an explicit adapter rather than a different name
or forwarding member.

This ADR governs those additive projections. It does not decide whether a
Kotlin-owned generic owner is erased or CLR-generic; that belongs to the owner
representation ADRs. It also does not govern imported CLR declarations, which
retain their foreign TypeDefs, MethodDefs, and native C# surface.

The current CLI selectors are provisional control-plane machinery. They are
not stable source syntax, Kotlin metadata, or ABI.

## Decision

### Kotlin and recorded physical ABI remain authoritative

The dependency direction is one-way:

```text
Kotlin source / IR / KLIB semantics
        +
producer-selected or retained physical CLR ABI
        -> optional explicit C# projection
```

An export consumes these authorities. It may not choose or reinterpret the
underlying Kotlin representation in order to obtain a nicer C# signature.
In particular, it may not change Kotlin casts, reflection, virtual dispatch,
generic construction, authoritative state, or cross-module ABI.

The exported surface therefore follows this order:

1. **Direct natural surface.** If the recorded physical declaration already
   truthfully represents the supported contract, C# uses it directly. No
   facade or adapter is generated merely because the declaration is Kotlin-
   owned.
2. **Additive projection.** Naming aliases, delegate views, CLR property rows,
   nullable metadata, and default-argument overloads may forward to the same
   declaration and object.
3. **Explicit adapter.** A real representation or semantic mismatch may use a
   separately identified adapter with an explicitly documented reduced or
   converted contract.
4. **Unsupported.** If none of these forms is truthful, export fails closed.

A direct natural `C<T>` or `I<T>` is consequently not an export facade: it is
the producer-recorded Kotlin/.NET ABI itself. Conversely, this ADR does not
promote the current generic-owner rehearsal to production. Production remains
on the owner ABI selected by the owning accepted ADR until an atomic cutover.

### Export is optional and additive

One export request selects one logical Kotlin declaration and one explicit CLR
name. Selection uses complete logical Kotlin identity, not a CLR token, simple
name, declaration order, or generated-name convention. An overload selector
includes its expanded Kotlin parameter signature; the return type is excluded
because Kotlin cannot overload by return type alone. Missing and ambiguous
selectors are errors.

The original declaration, physical members, metadata, backing state, and
callable identity remain unchanged. A forwarding member executes the original
declaration. A same-object projection does not create a proxy, alternate field
graph, or shadow state.

An explicit adapter has its own CLR identity and may hold a reference to the
Kotlin object, but it never becomes that object's Kotlin identity and never
owns a duplicate authoritative state. `ReferenceEquals(adapter, value)` is not
promised. Any identity or round-trip guarantee must be stated by that adapter's
contract rather than inferred from presentation.

### Ordinary C# does not require hidden Kotlin ABI

Existing C# libraries are imported through their native CLR metadata and do
not need an export, source generator, `partial` declaration, marker, or hidden
Kotlin capability.

When C# implements a published natural Kotlin CLR interface, the declared CLR
interface is the statically checked supported contract. Kotlin semantic routes
must be derived mechanically from recorded slots and physical metadata wherever
that is possible. An optional generator may add convenience members or a
proven fast path for C# deliberately authored for Kotlin, but it is not an
admission requirement for an otherwise supported implementation.

If a Kotlin operation cannot be derived from the declared natural slots, the
compiler must not pretend that a same-name public method, reflective convention,
or generated hidden member was part of the interface contract. That operation
requires an explicit supported adapter/opt-in contract or remains unsupported.
The [C# interface source-authoring ADR](adr-csharp-interface-source-authoring.md)
owns the detailed implementation rule.

### Admission is complete and fail-closed

Admission validates the complete selected declaration family and dependency
graph before publishing any member. It includes constructors, properties and
both accessors, inherited members, bounds, nested generic uses, visibility,
variance, defaults, and every generated overload or adapter member.

An excluded or unsupported dependency is not replaced by `object`, `dynamic`,
a bottom type, or an unrelated wrapper. A referenced declaration receives a
truthful supported projection or causes the dependent export to be rejected.
Generated identities derive from complete logical Kotlin identity, including
package and owner.

For generic projections, the exporter reuses an already guaranteed physical
construction or emits a distinct, explicitly admitted projection. It never
fabricates a view such as `I<object>` when an object is known only as `I<!T>`.
CLR variance is published only where the complete projected member surface is
legal and the CLR conversion really exists; value-type arguments do not gain
reference variance through boxing, and CLR generic classes remain invariant.

### Functions and delegates

An exported function keeps ordinary mapped parameter and result types and may
project supported fixed-arity Kotlin function positions as `System.Func` or
`System.Action`. Unsupported arities and callable forms are rejected rather
than exposed as misleading typed delegates.

Kotlin-to-CLR projection binds the delegate to an exact callable route where
available and otherwise uses a closed forwarding thunk. CLR-to-Kotlin
adaptation uses an explicit runtime adapter that stores the original delegate.
Projecting that adapter back to the same delegate shape returns the original
delegate. Repeated projection of one Kotlin callable to the same closed shape
must yield CLR-equal delegates so callback removal works without a global
cache; no equality is promised across different shapes.

Exceptions pass through unchanged. Reverse bridges and inherited slots bind by
producer-recorded declaration plus complete physical signature, never by name
alone.

### Properties and nullable metadata

An exported property adds forwarding CLR accessors and a real `.property` row
to the owning facade. A `val` has only a getter; a `var` exposes a setter only
when the Kotlin setter is public. Function-valued properties use the same
delegate rules as function parameters and results.

The projection emits truthful standard Roslyn nullable attributes on methods,
parameters, returns, property rows, and accessors. KLIB remains logical
authority. Nullable attributes are not runtime enforcement: every non-null
value entering through a generated boundary receives the same Kotlin boundary
validation as the declaration it exposes before adaptation or unboxing.

### Kotlin defaults become forwarding overloads

For a contiguous trailing suffix of defaulted parameters, the exporter may
emit one ordinary CLR overload for each progressively omitted suffix. Each
overload supplies physical placeholders and masks and invokes the existing
callee-owned `$default` dispatcher. It does not copy or evaluate a default
expression.

The `$default` dispatcher remains compiler ABI and is never itself exported.
The draft emits neither CLR optional flags nor `.param` constants: embedding a
constant in C# callers has different execution and versioning semantics and
requires a separate opt-in decision. Non-trailing omission does not generate
an exponential set of overloads.

### Names and collisions are planned atomically

Before emission, validate all requested methods, properties, accessors, fields,
default overloads, existing owner members, and compiler-generated members by
complete physical owner/name/signature identity. An occupied identity rejects
the complete affected export. The compiler never silently renames, drops, or
redirects a declaration, and never relies on return type, visibility, or
staticness to disambiguate an illegal CLR method collision.

The provisional textual CLI selector grammar must not grow into a declaration
language. A declaration-bound annotation or typed export DSL must replace it
before public use.

## Ownership

- Kotlin source, IR, and KLIB own logical declarations, identity, types,
  nullability, defaults, visibility, and semantics.
- Producer-recorded and retained CLR metadata own existing physical owners,
  names, signatures, and slots.
- The export model owns explicit selection, naming, complete admission, and a
  rendering-independent host projection plan.
- The backend owns physical collision validation and emitted forwarding CIL.
- Runtime interop helpers own delegate and explicit adapter round trips.
- Standard CLR attributes provide a truthful C# view; they do not replace KLIB
  or runtime boundary checks.

The current proof of concept still performs selector resolution and much model
construction inside `DotNetIlEmitter`. Before broadening export to generic
owners, members, or inheritance, move reusable admission and projection
planning to a named export/interop owner while leaving physical CIL emission in
the backend.

## Rejected alternatives

- requiring every Kotlin declaration to be consumed through a facade;
- permanently assuming that every Kotlin-owned generic owner is erased;
- making C# presentation determine Kotlin runtime ABI;
- requiring a source generator or hidden compiler interface for ordinary C#
  import or supported natural-interface implementation;
- automatic whole-module export;
- replacing canonical Kotlin callables with delegates;
- extending provisional selector strings into a source language;
- CLR optional constants as Kotlin default arguments;
- fabricating generic constructions or silently widening unsupported types to
  `object`; and
- silent collision-based omission or declaration-order naming.

## Consequences

- C# directly consumes truthful natural Kotlin/.NET declarations, including
  admitted CLR-generic owners, without a mandatory facade.
- Explicit export remains useful for host naming, delegates, properties,
  nullable presentation, default overloads, reduced projections, and genuine
  adapters.
- Same-object projections preserve the original receiver and state. Explicit
  adapters are visible as different identities and may allocate.
- Unsupported shapes fail explicitly instead of leaking erased, reflective,
  or partially typed protocols.

## Promotion conditions and open decisions

Before accepting this ADR and its public surface, decide and validate:

- source annotation versus typed Gradle/export DSL and stable naming rules;
- member, constructor, class, extension, generic, and suspend projections;
- ordinary C# implementation of every admitted natural interface without
  hidden Kotlin-specific source requirements;
- complete generic and variance admission, including value arguments,
  mutation, inheritance, and separate assemblies;
- delegate equality, callback registration/removal, and both projection round
  trips;
- nullable metadata and runtime checks through nested generics, arrays,
  delegates, properties, and defaults;
- explicit adapter construction, lifetime, identity, and round-trip rules;
- atomic cross-kind collision diagnostics before backend emission; and
- trimming, NativeAOT, debugger, reflection, IntelliSense, and ABI-diff views.

No resolution may make a C# projection authoritative for Kotlin declarations,
replace a truthful natural surface merely for uniformity, or introduce another
authoritative state.
