# Reified multiple method constraints checkpoint (2026-08-21)

## Scope

This checkpoint composes two independent interface constraints on the method
parameter of the existing owner-plus-method-generic default proof:

```kotlin
interface Marker {
    fun mark(): Int
}

interface MultiProducer<out T> {
    fun <R> produce(value: R): T
            where R : Consumer<R>, R : Marker {
        value.consume(value)
        value.mark()
        return /* T */
    }
}
```

Admission remains structural and production-inert. The owner is the admitted
one-member covariant producer root. Its single invariant, non-reified method
parameter has exactly one direct self-bound on an independently admitted
contravariant consumer root and one or more direct public non-generic nominal
interface bounds. The member has one direct `R` input, a non-null direct
owner-`T` result, and either an abstract or default body.

The nominal interfaces are admitted by stable physical shape, not declaration
name or marker-member shape. Nullable, class, special, owner-relative,
nominal-only, other constructed, declaration-erased `CharSequence`, overload,
inheritance, and mixed-member forms remain closed.

## Physical contract

The natural CLR interface owns `<R>(R): T`; its method GenericParam has exact
`Consumer<!!R>` and `Marker` GenericParamConstraint rows. The non-generic
declaration-semantic capability owns `<R>(R): object` with the same two bounds.
Only the owner-dependent result changes carrier.

The portable static helper has owner `T` followed by method `R`, an `object`
moved receiver, direct `R` input, `T` result, and both bounds on its own `R`.
Every local, external, helper, forwarding, and semantic declaration owns its
own method parameter and substitutes the recursive self-bound onto that
parameter. No bridge weakens either constraint to `object` or retains a
producer-IR parameter identity.

GenericParamConstraint row order has no CLR semantic meaning. The C# authoring
tool therefore compares source and metadata constraint types as an exact
multiset while retaining its existing alpha-equivalent comparison of recursive
method parameters by kind and ordinal. This accepts another source order but
still rejects a missing, additional, or differently constructed constraint.

## Fail-first results and repair

The first candidate was absent from the physical-family manifest because
method-generic admission required one upper bound. Admission now recognizes
exactly one admitted self-bound plus direct public non-generic interface bounds.
The first separate consumer then rejected the marker because the validator had
mistaken a local `IrFile` parent for stable nominal identity; a deserialized
KLIB declaration is parented by an external package fragment. Public non-
generic interface identity is the relevant invariant on both sides.

Both .NET 10 lanes then passed, while both Framework lanes showed that the
Kotlin-widened call bypassed the authored C# override. The source override had
spelled `Marker, Consumer<R>` in the reverse order from Kotlin metadata. The
authoring matcher treated those arrays positionally, failed to recognize the
source method, and generated a portable bridge which called the Kotlin default
helper. DIM happened to reach the natural override on .NET 10 and concealed
the mismatch. Multiset comparison makes both profiles select the same source
method without adding a semantic fallback.

## Kotlin and C# execution

A Kotlin class in a second DLL and an ordinary partial C# class inherit the
default without declaring a source method. A second partial C# class declares
only the natural generic method and deliberately reverses its two independent
constraint clauses. Direct C# and exact or Kotlin-widened Kotlin calls reach
the inherited default or authored override respectively. Counters prove each
body plus `consume` and `mark` operations execute once per call, while identity
checks prove that no wrapper or second owner was introduced.

Reflection independently inspects the natural and semantic interfaces on the
C# class. Each has one method-owned generic parameter, direct `R` input, the
expected natural or semantic result, and exactly the unordered
`Consumer<R>`/`Marker` bounds. A separate assembly scan proves there is one
portable helper with owner/method parameter order, moved receiver, result, and
the same complete constraint set intact.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors, or
skips: PSI and LightTree on Framework 4.8 and .NET 10. The rehearsal-off erased
inverse passes the same four lanes.

The final normal production aggregate passes. Its direct JUnit XML audit finds
190 suites and 2,287 tests with zero failures, errors, or skips: 187 freshly
written FIR suites/2,155 tests, two freshly written integration suites/126
tests, and the unchanged up-to-date six-test `dotnet.ir` model root.

## Remaining boundary

This checkpoint proves that retained CLR method constraints form a semantic
set and can compose without erasing the method or owner. It does not authorize
arbitrary Kotlin upper bounds. Each new constraint family still needs a
truthful physical mapping, separate-compilation reconstruction, normal C#
authoring, and both runtime profiles before admission expands.
