# Reified generic-interface default property checkpoint (2026-08-20)

## Scope

This checkpoint closes the first property-shaped default on a Kotlin-owned
generic interface under the test-only generic-owner rehearsal:

```kotlin
interface DefaultPropertyProducer<out T> {
    val defaultPropertyValue: T
        get() = defaultValue()
}
```

The proof intentionally uses an `Int` construction. An exact C# read and a
Kotlin-legal widened `DefaultPropertyProducer<Any?>` read must therefore cross
the typed/semantic boundary while preserving the same receiver and the same
single default body.

## Physical contract

The natural interface is a real covariant CLR
`DefaultPropertyProducer<T>`. Its getter is associated with one real CLR
`Property<T>` row and remains the only ordinary C# property API.

The declaration-semantic capability remains the established non-generic,
method-only compiler ABI. It deliberately does not fabricate a second
Property row. Consequently one logical Kotlin property contract contains two
different physical accessor forms:

- a getter associated with the natural CLR Property; and
- a regular semantic-capability MethodDef returning `object`.

Framework 4.8 keeps the Kotlin body in the producer-recorded generic default
helper. .NET 10 keeps the body on the natural getter DIM and retains the same
helper for compatibility/exact selection.

## Fail-first result and repair

The first candidate passed interface admission but failed C# authoring with
`KDNCS006`: the generator grouped every slot of a logically property-shaped
contract as though every MethodDef were associated with CLR Property metadata.
The semantic capability MethodDef correctly had no associated Property, so
the manifest cross-check rejected it.

The repair keeps logical and physical facts separate. Property syntax is now
grouped only for locators which actually name a CLR Property. A property-
shaped locator without a Property name is handled as a method-backed semantic
accessor. Its generated explicit method forwards to exactly one of:

- the ordinary C# source property, when the class supplies one;
- the producer-recorded helper on portable profiles; or
- the natural property DIM on .NET 10.

The method-backed path validates getter/setter shape, by-reference exclusions,
C#-expressible names, source-property ambiguity, and every result/input
conversion. A future method-backed setter therefore converts its semantic
input to the helper/property carrier before dispatch rather than relying on an
invalid implicit `object` conversion.

No manifest schema, runtime surface, KLIB format, or physical-library ABI
version changed. Existing locators already recorded both the natural Property
name and the method-only semantic slot truthfully.

## C# and Kotlin execution

The separate-compilation product adds two ordinary partial C# classes:

- one implements `DefaultPropertyProducer<int>` without a source property and
  inherits the Kotlin default through generated compiler ABI; and
- one declares a normal `int defaultPropertyValue` property returning a
  distinct value.

For the first class, both an exact C# interface-property read and the Kotlin
widened read return the Kotlin default. A counter proves the body executes once
per read, not through duplicated implementations. For the second class, both
the direct C# property read and Kotlin widened read observe the C# value and do
not execute the Kotlin body. Both widened paths retain reference identity.
C# source never names the semantic capability.

The inherited DIM is read through the interface view because C# default
interface members are not projected as members of the implementing class. A
class-authored override remains directly visible as its ordinary property.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors, or
skips:

- FIR PSI on .NET Framework 4.8;
- FIR LightTree on .NET Framework 4.8;
- FIR PSI on .NET 10; and
- FIR LightTree on .NET 10.

The rehearsal-off erased inverse passes the same four lanes. This proves that
the production representation remains unchanged when the epoch is disabled.

The final normal production aggregate directly audits 190 JUnit XML suites and
2,287 tests with zero failures, errors, or skips. The run freshly wrote 187 FIR
suites with 2,155 tests and two integration suites with 126 tests; the unchanged
`dotnet.ir` model root contributes its six tests.

## Remaining boundary

This checkpoint does not admit arbitrary read-only properties. Admission is
limited structurally to one public non-nullable direct `T` getter on a `val`
of a single-parameter covariant interface. Read-only property inheritance,
multiple or mixed property/member defaults, method-generic defaults, diamonds,
reabstraction, changed type arguments, and deeper/multiple inheritance remain
separate fail-first gates.
