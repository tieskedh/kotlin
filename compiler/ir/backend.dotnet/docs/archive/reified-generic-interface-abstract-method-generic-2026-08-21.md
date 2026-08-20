# Reified generic-interface abstract method-generic checkpoint (2026-08-21)

## Scope

This checkpoint closes the abstract sibling of the first owner-plus-method-
generic default under the test-only generic-owner rehearsal:

```kotlin
interface AbstractMethodGenericProducer<out T> {
    fun <R> produceAbstractGeneric(value: R): T
}
```

The admitted shape remains exact: one public root member, one non-reified
invariant method parameter `R` with exactly the universal bound, one direct
`R` input without a default or vararg carrier, and one non-null direct
owner-`T` result. Constraints, nullable results, properties, overloads,
children, and mixed member families remain closed.

## Physical contract

The natural covariant CLR interface owns the ordinary generic method
`produceAbstractGeneric<R>(R): T`. The non-generic declaration-semantic
capability owns `produceAbstractGeneric<R>(R): object`. Both MethodDefs retain
method-generic arity one and the same method-owned `R`; only the owner-relative
result is widened.

An implementation supplies the natural generic method. Kotlin lowering or
the optional C# authoring generator supplies the compiler-ABI connection to
the semantic slot. C# source never names or implements that hidden capability,
and no reflection, runtime carrier, default helper, manifest schema change, or
physical ABI version change is required.

## Fail-first result and repair

The initial candidate failed all four lanes before C# generation because the
abstract interface was absent from the implementation manifest. The previous
checkpoint had deliberately limited the structurally valid `<R>(R): T` shape
to members with a recorded Kotlin default.

The correction changes only that admission boundary: the same exact shape is
accepted when the member is abstract or has a proven default implementation.
The already-established local and external semantic-slot materialization
retains and remaps method type parameters correctly, and the existing C#
authoring path forwards the abstract semantic slot to the ordinary source
generic method without new special-case generation.

## Kotlin and C# execution

A generic Kotlin class in a second DLL implements
`AbstractMethodGenericProducer<T>` with one ordinary `<R>(R): T` method. A
later Kotlin executable calls it through both the exact `Int` construction and
the legal covariant `Any?` view. Both calls reach the same method and preserve
receiver identity across separate compilation.

A partial C# class separately implements
`AbstractMethodGenericProducer<int>` with only
`int produceAbstractGeneric<R>(R)`. A direct C# interface call and a Kotlin-
widened call both return the distinct C# result, while an identity probe
confirms one receiver. The generated source contains the required bridge, but
the authored C# source contains no semantic-capability name.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors,
or skips: PSI and LightTree on Framework 4.8 and .NET 10. The rehearsal-off
erased inverse passes the same four lanes.

The final production aggregate passes 190 XML suites and 2,287 tests with
zero failures, errors, or skips. The 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written; the unchanged six-test
`dotnet.ir` model root remained up-to-date.

## Remaining boundary

This checkpoint does not authorize arbitrary generic methods. Owner-relative,
nominal, special, or constructed constraints, more type or value parameters,
nullable owner results, defaults/varargs, properties, child inheritance,
overloads, multiple/mixed members, diamonds, reabstraction, and ordinary
capability-free foreign generic-method dispatch remain separate fail-first
gates.
