# Reified owner-relative method constraint checkpoint (2026-08-21)

This checkpoint closes the first method constraint which depends directly on
a reified generic interface owner parameter.

## Scope

The admitted shape is one public top-level covariant interface with one public
abstract owner-plus-method-generic producer:

```kotlin
interface Producer<out T> {
    fun <R : @UnsafeVariance T> produce(value: R): T
}
```

The method owns exactly one non-reified invariant `R`. Its only bound is the
direct, non-null interface parameter `T`; its sole value parameter is `R` and
its result is `T`. Defaults, nested or multiple relative bounds, nullable
bounds, mixed members, inherited forms, and indirect signatures remain closed.

## Kotlin authority and physical CLR contract

Kotlin source and KLIB retain `R : T` as the authoritative language
relationship. The constraint cannot be copied literally to the natural CLR
slot: CoreCLR rejects a GenericParamConstraint which refers to the covariant
owner parameter. The non-generic semantic capability has no owner `T` token at
all. Both interface slots therefore omit the executable constraint.

Every Kotlin implementation override must omit it as well. The fail-first
candidate reached a `TypeLoadException` because the implementation retained a
stronger constraint than the weakened interface slot. The lowering now follows
the logical override chain, including an external producer stub, and marks the
corresponding implementation method parameter for metadata-only erasure. Its
logical supertype remains in IR for body code generation.

This is not method erasure. Both physical slots keep generic arity one and use
their own `!!R` for the value parameter. The natural result remains `!T`; only
the semantic result is `object`. No route substitutes owner `T` for method
`R`, changes the input to `object`, uses reflection, or requires IL weaving.

## C# authoring

Schema 7 publishes the normalized `(method R=0, owner T=0)` relationship as
tooling guidance, not as a reconstructed CLR constraint. `KDNCS009` tells a C#
author not to add `where R : T`. An ordinary partial C# class implements one
unconstrained natural generic method. Generated source supplies the semantic
capability adapter and forwards its actual `R` unchanged.

The hostile C# implementation returns `typeof(R).Name`. An exact natural call
and Kotlin-widened calls with `Int32` and `String` prove that the adapter does
not silently instantiate the source method with owner `T`. All paths retain one
receiver identity.

## Kotlin widened execution

A Kotlin implementation is first observed as `Producer<Marker>` and called
with a marker value, then widened normally to `Producer<Any?>` and called with
a `String`. Both calls return the implementation's original marker object and
preserve receiver identity. This is precisely why retaining `R : Marker` only
on an invariant implementation MethodDef would be incorrect for this
`@UnsafeVariance` declaration.

Reflection verifies covariance, typed natural `T` results, object semantic
results, method-owned `R` value parameters, and zero executable constraints on
both interface slots and the Kotlin implementation override.

All four PSI/LightTree x Framework 4.8/.NET 10 candidate lanes and all four
erased epoch-off inverse lanes pass. The final normal aggregate audits 190 XML
suites and 2,287 tests with zero failures, errors, or skips.
