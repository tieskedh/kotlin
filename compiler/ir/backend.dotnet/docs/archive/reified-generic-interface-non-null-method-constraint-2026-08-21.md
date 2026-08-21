# Non-null method constraint checkpoint (2026-08-21)

## Question

Can a reified generic-interface default retain Kotlin's explicit `<R : Any>`
method bound while exposing one honest CLR-generic natural API to Kotlin and C#,
without falsely constraining `R` to reference types or value types?

## Fail-first evidence

The existing method-generic family gate admitted the implicit `Any?` bound,
direct owner-relative bounds, admitted constructed self-bounds, and public
non-generic nominal constraints. It rejected the explicit `Any` bound. The new
separate-compilation corpus therefore failed before emission: the manifest had
no `produceNonNullDefault` family and the C# authoring phase could not select
that member.

No emitter or authoring workaround was added. The gate now recognizes exactly
one direct `Any` bound as the already-decided Kotlin non-null form. The existing
constraint mapper continues to omit it from CLR GenericParam metadata.

## Selected representation

Kotlin source and KLIB retain:

```kotlin
interface Producer<out T> {
    fun <R : Any> produce(value: R): T
}
```

The family is physically:

```text
natural interface slot       <R>(R): !T       constraints: none
semantic interface slot      <R>(R): object   constraints: none
portable default helper   <T, R>(object, R): T constraints: none on R
Kotlin/C# override            <R>(R): result   constraints: none
```

This composes the reified-interface ADR with the accepted non-null generic-
upper-bound decision. Kotlin `R : Any` permits both reference and value
substitutions. A CLR `class` constraint would reject `Int32`; `struct` would
reject `String`; `new()` and nominal `System.Object` express different
contracts. The truthful executable CLR representation is consequently an
unconstrained GenericParam, while Kotlin compilation enforces non-null
substitution through the retained logical signature.

The semantic family does not alter that method-owned parameter. It widens only
the owner-dependent result from `!T` to `object`. An ordinary C# class can
inherit the Kotlin default or override only the natural unconstrained generic
method. Generated compiler ABI forwards the same `R`; C# never names the
semantic capability.

## Closed corpus

The producer lives in `lib`, Kotlin implementations live in `middle`, and
Kotlin plus C# consumers execute later. Exact and widened calls cover inherited
Kotlin defaults, Kotlin overrides, and ordinary C# overrides with both `Int32`
and `String` method constructions. They retain receiver identity and select one
authoritative body.

Reflection independently verifies that the natural and semantic interface
slots each own one method GenericParam with no special flags and no
GenericParamConstraint rows. The portable helper owns `<T, R>` and leaves its
`R` equally unconstrained; the Kotlin override does the same. Manifest checks
pin natural `!T`, semantic `object`, and unchanged `R` input carriers.

PSI and LightTree execute the candidate, explicit epoch-off inverse, and
property-absent inverse on .NET Framework 4.8 and .NET 10: twelve lanes with
zero failures, errors, or skips.

The full `dotNetTest` aggregate exits zero. Direct XML audit covers 190 suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites with 2,155
tests and two integration suites with 126 tests were freshly written; the
unchanged `dotnet.ir` model root retained its six green tests as up-to-date.
The feature test appears in all four PSI/LightTree by Framework/.NET box
suites.

## Boundary

This proof admits one explicit direct `R : Any` bound on the existing
single-member default producer family. It does not add CLR nullable attributes,
accept `R : Any?` as a new case (that remains the existing universal bound), or
generalize nullable, imported CLR special, mixed, multiple, owner-relative, or
other constructed constraints. Those require independent Kotlin semantics,
binding, override, reflection, and separate-compilation proofs.
