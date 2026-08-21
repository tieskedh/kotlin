# Reified constrained method-generic default checkpoint (2026-08-21)

## Scope

This checkpoint composes the first retained constructed method constraint with
a Kotlin generic-interface default under the test-only generic-owner rehearsal:

```kotlin
interface ConstrainedDefaultProducer<out T> {
    fun <R> produce(value: R): T where R : Consumer<R> {
        value.consume(value)
        return /* T */
    }
}
```

Admission remains structural and narrow. The owner is the admitted one-member
covariant producer root. Its one invariant, non-reified method parameter has
one direct non-null self-bound whose owner is independently proven as the
admitted one-member contravariant consumer root. The member has one direct
`R` input, one non-null direct owner-`T` result, and one default body.

Owner-relative, other nominal or constructed, special, multiple, and nullable
constraints, overloads, children, and mixed member families remain closed.

## Physical contract

The natural CLR interface owns `<R>(R): T`; its method GenericParam has exactly
one `Consumer<!!R>` GenericParamConstraint. The non-generic declaration-
semantic capability owns `<R>(R): object` with the same self-bound. Only the
owner-dependent result changes carrier.

The portable static helper has owner `T` followed by method `R`, an `object`
moved receiver, direct `R` input, `T` result, and the exact constraint on its
own `R`. Framework class implementations use a private final generic target
which calls that helper and explicitly implements the closed natural slot.
.NET 10 retains the natural DIM and the compatible helper.

Every slot, helper, class target, and semantic bridge owns a distinct method
parameter and substitutes `Consumer<R>` onto that parameter. No bridge retains
a producer-IR method-parameter identity or weakens the bound to `object`.

## Fail-first result and repairs

The first candidate was rejected by the structural admission rule because a
default had been restricted to the universal method bound. Removing that
single default-only exclusion made both .NET 10 lanes pass and preserved the
exact constraint on the natural slot, semantic slot, and helper.

Both Framework lanes then failed with `TypeLoadException`: the concrete class
did not implement the natural constrained generic method. Its helper-backed
target and semantic capability bridge were present and correctly constrained,
but the emitter compared the target's closed `int` result with the interface
declaration's still-open `!T`. It therefore suppressed the natural MethodImpl.

The emitter now performs that compatibility check after substituting the
interface owner through the implementing class. It still rejects a genuinely
incompatible result; it no longer mistakes `!T` under `I<int>` for a different
type from `int`. The existing helper target then emits the one required
natural `.override`, without adding another body or semantic fallback.

## Kotlin and C# execution

A Kotlin class in a second DLL implements `ConstrainedDefaultProducer<Int>`
without declaring a source method. Direct and legally widened Kotlin calls
execute the inherited Kotlin body, invoke `Consumer<R>.consume`, and preserve
the producer and constraint-value identities.

One partial C# class likewise declares no source method and inherits the
profile-aware Kotlin default. A second partial class declares only the normal
`int produce<R>(R) where R : Consumer<R>` method. Direct C# and Kotlin-widened
calls select the inherited body or C# override respectively. Body and consumer
counters prove that neither path executes twice or bypasses the authored
override. C# source never names the semantic capability.

Reflection independently inspects the two interfaces implemented by the C#
class. Each has one method-owned generic parameter, direct `R` input, the
expected natural or semantic result, and exactly one `Consumer<R>` bound. A
separate assembly scan proves there is one portable helper with owner/method
parameter order, moved receiver, result, and constraint intact.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors,
or skips: PSI and LightTree on Framework 4.8 and .NET 10. The rehearsal-off
erased inverse passes the same four lanes.

The final normal production aggregate passes. Its direct JUnit XML audit finds
190 suites and 2,287 tests with zero failures, errors, or skips: 187 freshly
written FIR suites/2,155 tests, two freshly written integration suites/126
tests, and the unchanged up-to-date six-test `dotnet.ir` model root.

## Remaining boundary

This checkpoint admits one default only because every physical occurrence of
its recursive constructed bound is truthful. It does not authorize arbitrary
generic-method constraints or make semantic routing the normal entry. The
natural constrained CLR method and closed natural MethodImpl remain the
ordinary Kotlin/C# path; the semantic capability is only the widened Kotlin
escape hatch already required by owner covariance.
