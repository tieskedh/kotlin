# Reified generic-interface owner-relative method default (2026-08-21)

## Question

Can the admitted covariant method family
`<R : @UnsafeVariance T>(R): T` own a Kotlin default body without restoring an
illegal CLR `R : T` constraint, narrowing a Kotlin-widened method construction
to the implementing class's physical `T`, duplicating the body, or making C#
author compiler ABI?

## Fail-first evidence

Removing only the admission guard exposed three independent composition gaps:

1. Kotlin-widened calls reached a typed class forwarder and cast an `Int32`
   method argument to the implementation's physical `String` owner argument.
2. On .NET 10 the compatibility helper still called the abstract semantic
   interface slot. Routing a widened default back through that helper produced
   invalid recursive IL rather than executing the DIM body.
3. After the Kotlin routes were corrected, a C# subclass which overrode only
   the ordinary typed generic method was bypassed by the class semantic
   dispatcher.

The third failure also reproduced through a Kotlin class in a second DLL and a
Kotlin subclass in a third DLL. It therefore was an external-family ABI gap,
not a same-module dispatch accident.

## Selected representation

KLIB retains the authoritative `R : T` relationship. As in the abstract proof,
the natural interface MethodDef, semantic MethodDef, Kotlin overrides, and C#
surface retain method-generic `R` but omit the illegal or stronger executable
CLR constraint.

Framework and .NET 10 now share one authoritative helper body:

- the helper is generic in owner `T` followed by method `R`;
- Framework's natural implementation closes it with the exact owner argument;
- the .NET 10 natural DIM is a thin typed wrapper which closes the same helper;
- a Kotlin semantic bridge closes owner-dependent helper parameters with
  `object` and preserves the actual method `R`.

Generated C# semantic adapters make the same distinction. A natural slot uses
its exact owner construction. A default semantic slot with a recorded erased
owner-relative bound calls the helper with `object` for that owner parameter.
An ordinary C# source override remains the sole natural authoring entry and is
preferred over the inherited default.

Generic Kotlin class implementations route their interface capability through
the already planned class capability dispatcher. That dispatcher now admits a
narrow second allocation-free foreign-override shape: one method parameter,
one declaration-independent value parameter, and strict owner output. Its
protected virtual probe is itself generic in the same `R`; emitted
`ldvirtftn`/`ldftn` comparisons close the typed method with `!!R`. The selected
typed or semantic call receives that same argument. External family binding
reconstructs the probe's erased method-parameter schema, so a later Kotlin DLL
overrides the producer probe rather than opening a new slot.

## Closed corpus

The separate corpus covers:

- exact and widened Kotlin calls to an inherited default with `String` and
  `Int` method constructions;
- a generic Kotlin implementation of the abstract sibling whose body returns
  the actual `R` value;
- an ordinary partial C# implementation which inherits the default;
- an ordinary C# implementation which overrides only the natural generic
  method;
- a direct C# subclass of the Kotlin generic implementation; and
- a `lib` -> `middle` -> `leaf` Kotlin chain followed by a C# subclass,
  proving external generic probe binding.

Reflection verifies two generic interface slots, zero physical owner-relative
constraints, typed versus object results, the helper's `<T, R>` order, object
receiver, and `R` value parameter. The default body counter proves one selected
body, while `typeof(R)` and returned values prove that no route substitutes
physical owner `T` for the actual method construction.

PSI and LightTree execute the enabled candidate and the production inverse on
.NET Framework 4.8 and .NET 10: eight lanes, zero failures, errors, or skips.
The final normal production aggregate directly audits 190 XML suites and 2,287
tests: 187 FIR suites/2,155 tests, two integration suites/126 tests, and the
unchanged six-test `dotnet.ir` model root, with zero failures, errors, or skips.

## Boundary

This closes one direct owner-relative bound on one default covariant producer
method, plus the generic Kotlin-class and ordinary C# override route required
by that family. Nested, multiple, nullable, and inherited relative bounds,
overloads, mixed members, and broader parameter graphs remain separate gates.

A non-generic Kotlin class which implements the abstract owner-relative
interface does not yet own the generic-class semantic dispatcher used by this
proof. That shape must be proved or rejected explicitly next; it must not be
made correct by degrading the natural interface, unrelated generic state, or
all implementation calls to `object`.
