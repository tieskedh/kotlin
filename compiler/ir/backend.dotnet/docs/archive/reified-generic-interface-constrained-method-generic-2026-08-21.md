# Reified generic-interface constrained method-generic checkpoint (2026-08-21)

## Scope

This checkpoint closes the first constructed method constraint under the
test-only generic-owner rehearsal:

```kotlin
interface ConstrainedProducer<out T> {
    fun <R> produce(value: R): T where R : Consumer<R>
}

interface Consumer<in T> {
    fun consume(value: T)
}
```

Admission is structural and deliberately narrow. The producer is the already-
admitted abstract `<R>(R): T` root. Its one invariant, non-reified method
parameter has one direct non-null self-bound whose owner is independently
proven as the admitted one-member contravariant consumer root. No declaration
name, source file, module, or stdlib identity participates in that proof.

Constrained defaults, owner-relative bounds, other nominal or constructed
bounds, special or multiple constraints, nullable results, properties,
overloads, children, and mixed member families remain closed.

## Physical contract

The natural covariant CLR interface owns `<R>(R): T`; its method GenericParam
has exactly one `Consumer<!!R>` GenericParamConstraint. The non-generic
declaration-semantic capability owns `<R>(R): object` with the same exact
self-bound. Only the owner-dependent result changes carrier.

Every generated semantic slot and implementation bridge owns a distinct CLR
method parameter. Lowering therefore copies and substitutes each bound after
the new method-parameter symbols exist. A separate compilation reconstructs
the constraint from the producer-published reified consumer contract and
remaps it to the consumer stub's own `R`; it never retains the producer IR
symbol or reconstructs a constraint from an arity or declaration name.

The IL type mapper emits the recursive GenericInstance with a positional
method GenericParam leaf. That leaf deliberately has no recursively expanded
upper-bound payload inside its own constraint TypeSpec.

## Fail-first results and repairs

The initial run rejected `Consumer<R>` because Kotlin-owned constructed
constraints were legal only for declaration-erased interfaces. The narrow
repair admits an exact self-bound only when its classifier already has a
selected reified interface family.

The next separate-compilation run exposed two stale-identity defects: copied
semantic slots and implementation bridges retained the source MethodDef's
method-parameter symbol inside their bounds. Both paths now substitute bounds
through the same method-parameter mapping already used for inputs and results.

The C# authoring analyzer then rejected a valid source implementation because
Roslyn gives source `R` and metadata `R` different symbols. Constraint matching
now treats method parameters as alpha-equivalent by kind and ordinal while
recursively requiring the same array shape, named-type definition, and type
arguments. It does not weaken owner parameters or nominal constraint identity.

Finally, the metadata oracle was corrected to inspect structurally the two
interfaces implemented by the generated C# class instead of inferring the
compiler capability name. Both must expose one declared generic MethodDef with
the expected result carrier, direct `R` parameter, and exact `Consumer<R>`
self-bound.

## Kotlin and C# execution

A generic Kotlin class in a second DLL implements `ConstrainedProducer<T>`.
Its method calls `value.consume(value)` and returns its stored `T`. A later
Kotlin executable invokes that one implementation through exact `Int` and
legal covariant `Any?` views, observes two constraint calls, and preserves
both producer and constraint-value identity.

A partial C# class implements `ConstrainedProducer<int>` with only the ordinary
source method and the normal C# `where R : Consumer<R>` clause. A second C#
class implements `Consumer<Self>`. Direct C# and Kotlin-widened calls reach the
same authored method, invoke the consumer twice, and preserve one receiver and
value identity. Authored C# never names the semantic capability; generated
source supplies that compiler-ABI connection.

## Verification

The enabled candidate passes four focused lanes with zero failures, errors,
or skips: PSI and LightTree on Framework 4.8 and .NET 10. The rehearsal-off
erased inverse passes the same four lanes.

The final production aggregate passes 190 XML suites and 2,287 tests with zero
failures, errors, or skips. The 187 FIR suites/2,155 tests and two integration
suites/126 tests were freshly written; the unchanged six-test `dotnet.ir`
model root remained up-to-date.

## Remaining boundary

This checkpoint proves one recursive constructed interface constraint; it
does not authorize arbitrary generic-method constraint lowering. In
particular, the independently admitted consumer family is what makes the CLR
TypeSpec truthful for every use. A bound whose physical classifier is erased,
ambiguous, nullable, owner-relative, or otherwise not one stable CLR TypeDef
must still fail closed or use an independently justified carrier rule.
