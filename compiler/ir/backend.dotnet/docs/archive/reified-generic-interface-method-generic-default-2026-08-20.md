# Reified generic-interface method-generic default checkpoint (2026-08-20)

## Scope

This checkpoint closes the first Kotlin default which composes a real CLR
generic interface owner with a real CLR generic method under the test-only
generic-owner rehearsal:

```kotlin
interface DefaultMethodGenericProducer<out T> {
    fun <R> produceDefaultGeneric(value: R): T = /* one Kotlin body */
}
```

The admitted shape is intentionally exact: one public root member, one
non-reified invariant method parameter `R` with exactly the universal bound,
one direct `R` input without a default or vararg carrier, and one non-null
direct owner-`T` result. Abstract roots, other bounds, nullable results,
properties, overloads, children, and mixed member families remain closed.

## Physical contract

The natural covariant CLR interface owns the ordinary generic method
`produceDefaultGeneric<R>(R): T`. This remains the only normal Kotlin and C#
entry. The non-generic declaration-semantic capability owns a separate
`produceDefaultGeneric<R>(R): object` MethodDef. It erases only the
owner-dependent result; method `R` is neither erased nor reconstructed.

On Framework 4.8 the single Kotlin body lives in the existing top-level
generic default-helper ABI. That static helper has generic arity two: owner
`T` followed by method `R`. On .NET 10 the natural generic method owns the DIM
body and the same helper remains available for the accepted compatibility
path. No manifest schema, Runtime surface, KLIB format, or physical-library
ABI version changed.

## Fail-first result and repair

All four initial lanes failed before C# generation because the interface was
absent from the implementation manifest. The root admission rule explicitly
rejected every method with method-owned type parameters, and semantic-slot
materialization had no method-parameter copy/remap path.

The correction adds one default-only structural admission rule. Local and
separately reconstructed semantic slots copy the source method parameters,
substitute all direct `R` occurrences with the copied slot parameter, and
erase only direct occurrences of owner `T`. Existing default lowering and C#
authoring then compose without special-case source generation: they already
preserve method parameters and order helper arguments as owner parameters
followed by method parameters.

The manifest test was strengthened at the same time. It now requires generic
arity one on both natural and semantic slots and generic arity two on the
portable/retained helper. A non-generic approximation cannot pass merely by
having compatible `object` parameters.

## C# and Kotlin execution

The separate product compiles two partial C# implementations of the real
`DefaultMethodGenericProducer<int>` interface:

- one declares no source method and inherits the Kotlin default through the
  generated profile-aware adapter; and
- one declares the ordinary `int produceDefaultGeneric<R>(R)` method and
  returns a distinct result.

For the inherited implementation, a direct C# generic-interface call and a
Kotlin-widened `DefaultMethodGenericProducer<Any?>` call both execute the one
Kotlin body. For the override implementation, both paths execute the ordinary
C# generic override and do not enter the Kotlin body. Body counters and
reference comparisons prove single execution and one object identity.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors,
or skips: PSI and LightTree on Framework 4.8 and .NET 10. The rehearsal-off
erased inverse passes the same four lanes, proving the production
representation remains unchanged while the epoch is disabled.

The full target aggregate and final JUnit XML audit are recorded in
`STATUS.md`; this archive records the feature-specific proof rather than a
moving global count.

## Remaining boundary

This checkpoint does not authorize arbitrary generic methods. Abstract
method-generic roots, owner-relative or nominal constraints, more parameters,
nullable owner results, defaults/varargs, method-generic properties, child
inheritance, overloads, multiple/mixed members, diamonds, reabstraction, and
ordinary capability-free foreign generic-method reflection dispatch remain
separate fail-first gates.
