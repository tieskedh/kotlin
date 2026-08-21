# Reified nominal method constraints checkpoint (2026-08-21)

This checkpoint records the first method-generic producer whose method
parameter is admitted from direct nominal constraints alone. It does not need
the previously required constructed self-bound.

## Scope

The proof uses a public non-final Kotlin class, a public non-generic interface,
and a covariant generic-interface default:

```kotlin
open class Base
interface Marker

interface Producer<out T> {
    fun <R> produce(value: R): T where R : Marker, R : Base
}
```

Admission is structural. Every bound must be either the already admitted direct
constructed self-bound or a direct public non-generic nominal classifier. The
nominal set may contain interfaces and at most one non-final class. `Any`,
declaration-erased `Number` and `CharSequence`, nullable or generic classifiers,
final classes, and non-public declarations do not satisfy this proof.

## Physical contract

The natural `<R>(R): T` slot, the semantic `<R>(R): object` slot, and the
portable `<T, R>(object, R): T` helper each retain exact `Base` and `Marker`
GenericParamConstraint rows on their own method parameter. Metadata and C# put
the class constraint first; Kotlin source order and constraint-row order are not
semantic.

The fail-first candidate was rejected by the earlier gate solely because it had
no `Consumer<R>` self-bound. Generalizing that structural admission was the only
compiler change required. The existing type mapper, constraint copier, portable
helper, and C# authoring path then preserved the class-plus-interface contract
without a library name or member-shape exception.

## Execution and metadata evidence

A Kotlin implementation and an ordinary partial C# implementation inherit the
default. A second C# implementation overrides only the natural method with the
ordinary source constraint `where R : Base, Marker`. Exact and Kotlin-widened
calls reach the selected body on Framework 4.8 and .NET 10, retain receiver and
argument identity, and invoke both bound operations. Reflection separately
verifies both constraints on the natural slot, semantic slot, and helper.

All four PSI/LightTree x Framework/.NET 10 candidate lanes and all four erased
epoch-off inverse lanes pass. The final normal aggregate audits 190 XML suites
and 2,287 tests with zero failures, errors, or skips.

## Deliberate boundary

Owner-relative `R : T` is not admitted by this checkpoint. Its natural CLR slot
can encode the bound, but the paired semantic capability has an unconstrained
owner-erased `R`. Ordinary generated C# therefore cannot forward that actual
`R` to a `where R : T` override without reflection, IL weaving, or replacing it
with `T`. That needs a separate interop-preserving design.

Special `class`, `struct`, and `new()` constraints, nullable constraints, and
other constructed bounds also remain closed.
