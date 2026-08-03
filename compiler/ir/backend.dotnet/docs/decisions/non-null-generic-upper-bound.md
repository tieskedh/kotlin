# ADR: Logical `T : Any` with an unconstrained CLR parameter

- Status: **Accepted — pre-ABI**
- Date: 2026-08-03
- Scope: explicit non-null generic upper bounds on Kotlin classes, interfaces,
  and functions

This is the target authors' working decision for the experimental target. It
is not a public KEEP or an official Kotlin target commitment.

## Context

Kotlin's default generic upper bound is `Any?`; an explicit `T : Any` excludes
`null` while still admitting both non-null reference types and value types.
The bound is part of the Kotlin declaration and must survive separate
compilation in embedded KLIB.

ECMA-335 has runtime generic flags for `class` and `valuetype` constraints,
but neither represents Kotlin's union of non-null references and all value
types. A `ReferenceTypeConstraint` would reject `T = Int`; a
`NotNullableValueTypeConstraint` would reject `T = String`. A constraint row
to `System.Object` does not add non-null enforcement. C#'s `where T : notnull`
is a Roslyn nullable-analysis contract represented through nullable metadata,
not a CLR runtime constraint.

JVM erases both `Any?` and `Any` generic upper bounds to the same physical
`java.lang.Object` bound while Kotlin metadata and the Kotlin compiler retain
the distinction. JS has no host runtime generic constraint. Wasm and Native
likewise keep the Kotlin type rule in compiler/library metadata rather than
inventing separate reference-only and value-only declarations. The closest
Kotlin-aligned CLR representation is therefore logical retention plus physical
constraint erasure.

## Decision

An explicit Kotlin `T : Any` remains authoritative in KLIB and at every Kotlin
frontend/type-substitution boundary. The corresponding CLR generic parameter
retains its real owner, position, arity, and use in fields, parameters, results,
and calls, but emits no `ReferenceTypeConstraint`,
`NotNullableValueTypeConstraint`, or `GenericParamConstraint` row solely for
the `Any` bound.

This rule applies uniformly to Kotlin classes, interfaces, and functions. It
does not erase other representable class, interface, or type-parameter bounds,
and it does not admit nullable or generic-instantiation bounds that remain
unsupported. Code generation may use only the ordinary `System.Object`
capabilities implied by `Any`; it may not assume either reference-only or
value-only storage.

Roslyn-compatible `notnull` metadata is a truthful future interop enhancement
and should be emitted when the target's standard nullable-attribute programme
supports generic parameters. It is additive warning metadata, not an
authoritative replacement for KLIB and not a prerequisite for this physical
ABI. General foreign-call null guards remain owned by the explicit C# export
boundary; this decision does not create wrappers, clone objects, or reinterpret
nullable foreign values.

## Rejected alternatives

### Emit the CLR reference-type constraint

Rejected. Kotlin permits value-type substitutions such as `Int`, `Guid`, and
user-defined structs for `T : Any`. A `class` constraint would change accepted
Kotlin programs and prevent natural CLR specialization.

### Emit the CLR non-nullable-value-type constraint

Rejected. It would exclude every legal Kotlin reference substitution and add
value-type members that Kotlin's `Any` bound does not promise.

### Split the declaration into reference and value forms

Rejected. It duplicates declaration identity, complicates overload and bridge
metadata, and cannot preserve one Kotlin generic declaration under separate
compilation.

### Reject the bound until nullable attributes exist

Rejected. Nullable attributes improve the foreign-language view but do not
enforce a runtime constraint. Rejection would make a Roslyn warning surface a
prerequisite for Kotlin semantics that KLIB already represents exactly.

### Treat absence of a CLR constraint as absence of a Kotlin constraint

Rejected. CLR metadata is only the physical foreign-language view of a
Kotlin-produced declaration. Kotlin consumers must load the embedded KLIB and
continue rejecting nullable substitutions.

## Invariants and verification

- Reference and value substitutions execute through the same declared generic
  parameter without wrappers or identity changes.
- The physical generic parameter has no `class` or `valuetype` flag and no
  `System.Object` constraint row solely because of `T : Any`.
- Embedded KLIB retains the non-null bound and a separate Kotlin consumer
  cannot substitute a nullable type.
- Other explicit constraints remain represented or rejected by their own
  existing rules; this decision is not a general constraint-erasure escape.
- Common functions such as `requireNoNulls` retain their exact source behavior,
  including traversal, same-object return, first-null failure, message, and
  exception identity.
- Framework CLR and CoreCLR execute the same reference/value cases and direct
  physical fallbacks.

## Consequences

Raw C# and reflection see a physically unconstrained parameter until standard
`notnull` metadata is added. That view is necessarily weaker than Kotlin's
logical contract, just as nullable-reference metadata generally is weaker than
runtime enforcement. The embedded KLIB remains sufficient for exact Kotlin
round-tripping and separate compilation; adding Roslyn warning metadata later
does not require a MethodDef or generic-constraint ABI change.
