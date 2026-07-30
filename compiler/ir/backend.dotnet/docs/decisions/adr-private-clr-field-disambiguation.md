# ADR: Private CLR field-name disambiguation

- Status: **Accepted**
- Date: 2026-07-27
- Scope: Kotlin-owned fields emitted for `net48`, `netstandard2.0`, and `net10.0`

This is a pre-ABI decision. No Kotlin/.NET binary has shipped, so conflicting prototype behavior
is replaced rather than retained behind a compatibility mode.

## 1. Other Kotlin targets

The JVM backend accepts a source property whose backing-field name collides with generated
storage. Its late `RenameFieldsLowering` gives public/protected ABI fields first claim on their
name, gives static implementation fields the next stable priority, and suffixes later non-public
fields. The upstream diagnostic fixture
`diagnostics/tests/jvm/duplicateJvmSignature/specialNames/innerClassField.kt` specifically accepts
an inner-class property named ``this$0``.

JS and Wasm share `JsInnerClassesSupport`, which represents the outer receiver with a synthesized
field and lets their target layouts identify fields structurally rather than exposing a CLR-like
foreign field namespace. Native similarly creates a synthesized outer field and lowers class
layout to target storage. Those targets preserve the logical property and the implicit outer
receiver; none makes the spelling of private capture storage part of Kotlin source ABI.

The common direction is therefore to preserve both declarations and disambiguate private physical
storage after target lowerings, not to reject otherwise valid Kotlin.

## 2. Relevant .NET differences

ECMA-335 field identity includes the declaring type, field name, and field signature. Consequently
the CLR can contain two same-named fields with different types, while two fields with the same
name and mapped type are duplicates regardless of staticness or visibility. This rule is the same
on Framework 4.8, .NET Standard 2.0 metadata, and .NET 10.

C# cannot declare overloads of a field name by type, and ordinary reflection and tooling are
predominantly name-oriented. Preserving a type-distinguished duplicate merely because ILAsm
accepts it would create an awkward CLR-only shape without preserving any Kotlin contract.

The .NET backend retains properties through code generation, unlike the JVM pipeline at its rename
phase. A .NET pass must therefore inspect both loose `IrField` declarations and backing fields
owned by `IrProperty`.

## 3. Kotlin Common invariant

A legal Kotlin property and compiler-required state such as an inner instance's outer receiver or
an object's singleton cache must coexist. Renaming private storage is unobservable to ordinary
Kotlin code because field reads and writes bind the `IrField` symbol; the property's logical name,
accessors, metadata identity, visibility, and initialization semantics remain unchanged.

Rejecting the containing class would transgress common Kotlin semantics. Renaming a public,
protected, constant, or otherwise exposed compiler-ABI field could instead break cross-module or
foreign access and is therefore forbidden.

## 4. .NET language and runtime rules

The selected names must be unique enough for CLR metadata and natural for C# tooling. Public and
protected fields keep their declared or compiler-defined names. Later non-public fields receive
the smallest deterministic `$n` suffix according to the mature backend ordering. The choice is
profile-independent; .NET 10 supplies no field-identity feature that warrants a different ABI.

The existing emitter field-identity gate remains authoritative for collisions among fields which
cannot be renamed. It is a safety check, not the primary handling of private storage.

## 5. Architectural alignment

`DotNetRenameFieldsLowering` is a late target `ClassLoweringPass`, placed after every lowering that
can synthesize fields. It follows JVM phase placement and priority rather than adding ad-hoc
special cases for `INSTANCE`, ``this$0``, captured locals, delegates, or future generated fields.
The pass mutates only the physical `IrField.name`, so all symbol-based consumers continue to refer
to the same declaration.

The allocator strengthens the JVM pass's simple per-base counter by reserving every original field
spelling before choosing a suffix. Thus a legal source field such as ``this$0$1`` cannot be
silently shadowed by the name chosen for a second ``this$0`` field. This is not a different
semantic or ABI rule; it closes an avoidable generated-name collision while retaining the JVM
priority order. The same collision-resistant allocator would be an appropriate upstream
improvement for any mature backend with a flat named-field namespace.

The CLR's ability to distinguish fields by type is deliberately not used as an alternate naming
scheme. That keeps the generated shape closer to the JVM rule and more usable from normal .NET
tools.

## 6. Kotlin-aligned target choice

When several representations remain possible, prefer the JVM-proven policy: reserve exposed ABI
names, suffix private implementation storage, and retain a final physical-collision diagnostic.
This preserves Kotlin Common semantics, avoids a tooling-hostile CLR curiosity, and introduces no
new logical identity or profile mode.

## Consequences

- ``val `this$0`: Outer?`` in an inner class coexists with the synthesized outer-reference field.
- Pre-existing suffix-like source names are reserved, so the synthetic field skips
  ``this$0$1`` when necessary.
- An object property named `INSTANCE` coexists with the stable public singleton field, including
  when nullability erases both fields to the same CLR type.
- Same-named private fields of different CLR types are also disambiguated for C# and reflection
  usability even though raw metadata could retain both names.
- Public/protected collisions still fail atomically in the emitter.
- Private physical field names are intentionally not stable Kotlin ABI and must not be persisted
  as logical declaration identities.
