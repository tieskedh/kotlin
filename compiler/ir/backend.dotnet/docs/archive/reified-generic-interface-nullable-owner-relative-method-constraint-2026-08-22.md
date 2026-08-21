# Nullable owner-relative method constraint proof

Date: 2026-08-22

## Question

Can a reified-interface default declare `<R : T?>(R): T`, retain the exact
Kotlin constraint in KLIB, and still expose an ordinary CLR-generic API to
Kotlin and C#? The proof must include the legal value-type construction
`T = Int`, `R = Int?`; it may not solve the relation by erasing the method
parameter or by requiring C# to implement a compiler capability.

## Fail-first evidence

The first corpus was valid Kotlin but failed before emission because the
reified-interface manifest had no nullable owner-relative member. Admitting the
direct relation advanced compilation and exposed a second independent gap in a
Kotlin override body: after `value ?: -1` narrowed `R : Int?`, the local still
had physical type `!!R` while the consuming expression required `int32`.

These failures separated structural admission from executable body codegen.

## Selected representation

Kotlin source and KLIB remain authoritative for `R : T?`. Every executable CLR
method retains the real method parameter and omits a GenericParamConstraint:

| Member | Physical signature |
| --- | --- |
| Natural interface slot | `<R>(R): T` |
| Semantic interface slot | `<R>(R): object` |
| Portable default helper | `<T, R>(object, R): T` |
| Kotlin override | ordinary unconstrained `<R>` |
| C# override | ordinary unconstrained `<R>` |

Emitting CLR `R : T` would not preserve the Kotlin rule. It would be a stronger
and false constraint because CLR `Nullable<int>` does not satisfy `R : int`,
while Kotlin permits `R = Int?` under `R : T?` when `T = Int`.

For a final nullable-primitive upper bound, FIR can prove a non-null primitive
use without changing the declared local slot. Codegen handles only that proven
shape:

```text
load !!R
box !!R
unbox.any int32
```

The open token therefore accepts both `R = Int` and `R = Int?`. A null at the
proven non-null use fails there; unrelated generic reads and declarations are
unchanged.

## Closed corpus

The separate-compilation corpus covers:

- an inherited Kotlin default called exactly and through a widened owner view;
- a Kotlin override whose `R : Int?` body executes both value and null cases;
- ordinary C# default inheritance and an ordinary unconstrained C# override;
- `Int?`, `String`, and null method arguments;
- one receiver identity and the selected default or override body;
- Framework 4.8 and .NET 10, with PSI and LightTree; and
- reflection over natural/semantic slots, the helper, and the Kotlin override,
  proving zero special flags and zero GenericParamConstraint rows for `R`.

Candidate, explicit epoch-off, and property-absent configurations execute on
both target profiles: twelve focused lanes, all green.

The full `dotNetTest` aggregate exits zero. Direct XML audit reports 190 suites
and 2,287 tests with zero failures, errors, or skips. The 187 FIR suites/2,155
tests and two integration suites/126 tests were freshly written; the unchanged
six-test `dotnet.ir` model root remained up-to-date. The feature test is present
in all four PSI/LightTree by Framework/.NET box suites.

## Boundary

This closes one defaulted direct `R : T?` relation with one owner parameter and
one method parameter. Multiple or nested nullable relations, nullable nominal
or constructed roots, and mixed method/property families remain closed gates.
The representation does not imply a general CLR encoding for Kotlin
nullability constraints.
