# Covariant generic-interface broad-input composition proof

Date: 2026-08-22

## Question

Can the CLR representation needed by a Kotlin `Collection<out T>`-shaped
interface retain all three of these properties without a stdlib exception?

- a genuinely covariant read surface;
- natural exact typed inputs for C# and exact Kotlin calls; and
- Kotlin-wide candidate and nested-candidate calls which preserve the original
  receiver and argument identities?

The hard members are structurally equivalent to:

```kotlin
fun contains(candidate: @UnsafeVariance T): Boolean
fun containsAll(candidates: Collection<@UnsafeVariance T>): Boolean
```

## Rejected single-interface representation

Both the Framework 4.8 C# compiler and the .NET 10 Roslyn compiler reject this
shape with `CS1961`:

```csharp
interface Illegal<out T> {
    bool Contains(T candidate);
    bool ContainsAll(Illegal<T> candidates);
}
```

The nested `Illegal<T>` input is not an escape from CLR variance legality. Its
covariant `T` occurs beneath a method-input position and is therefore still an
illegal use of the interface's covariant parameter. Replacing these arguments
with `object` on the one natural interface would compile, but would discard the
exact typed C# contract. Making that interface invariant would discard its
truthful covariant read contract. Neither is the selected direction.

## Proven structural direction

The executable proof instead composes three views:

1. a CLR-legal covariant read view containing only producer positions;
2. an invariant exact view which inherits that read view and owns typed input
   members; and
3. a non-generic semantic capability for Kotlin-unnameable widened inputs.

One class implements the invariant exact view and semantic capability. The
exact view supplies the covariant read view by inheritance, so casts between
all applicable views preserve the one object identity. No wrapper, shadow
state, or copied collection is present.

The two broad-input policies remain deliberately different:

- an incompatible `contains` candidate returns the upstream fixed type-safe
  barrier result without entering an arbitrary semantic body; and
- an incompatible nested `containsAll` argument reaches the semantic body as
  the original non-generic capability object, because the Common algorithm may
  inspect it and its behavior cannot be replaced by one constant result.

Compatible capability calls dispatch through the ordinary typed virtual. An
ordinary C# override of that member therefore remains authoritative for exact
arguments. The semantic hook is used only for the incompatible nested route.
The `T = object` construction compiles without a MethodDef or C# source
collision because the non-generic capability is implemented explicitly.

CLR reference covariance also remains useful: an exact string implementation
is directly observable through the covariant object read view. CLR variance
does not box `IReadView<int>` into `IReadView<object>`; the non-generic semantic
view covers that Kotlin-legal value-type widening while preserving identity.

## Executable evidence

`DotNetLibraryIntegrationTest.testCovariantGenericInterfaceBroadInputComposition`
builds the illegal and legal products independently with both C# compilers. It
then executes a separate consumer on Framework CLR 4 and .NET 10 and checks:

- rejection of both direct and nested inputs on one covariant interface;
- exact typed read, `contains`, and `containsAll` dispatch;
- compatible semantic dispatch back through typed C# virtuals;
- fixed-result rejection of incompatible `contains` candidates;
- identity-preserving semantic dispatch of an incompatible nested candidate;
- reference-type covariance and value-type semantic widening;
- one receiver and nested-argument identity across all views; and
- the collision-sensitive `T = object` construction.

The focused JUnit execution reports one test, zero failures, zero errors, and
zero skips. Both runtime lanes execute within that test. The full `dotNetTest`
aggregate then exits zero. Direct XML audit reports 190 suites and 2,288 tests
with zero failures, errors, or skips. The two integration suites/127 tests are
freshly written; the unchanged 187 FIR suites/2,155 tests and six-test
`dotnet.ir` root remain up-to-date from the preceding green checkpoint.

## Boundary and next gate

This closes the CLR legality and identity proof, not the compiler ABI. The
physical names and public/export presentation of the exact and covariant views
remain an ABI decision. Production Kotlin interfaces are still erased, and no
`Collection`, `Set`, package, or declaration-name switch was added.

It also does not claim that an arbitrary precompiled C# class which implements
only one view can acquire the missing Kotlin semantic contract. Generated
partial C# implementations can receive explicit capability forwarding, while
direct/non-partial authoring needs a truthful documented boundary or a generic
fallback whose behavior is proved equivalent. The compiler must not invent an
arbitrary `containsAll` result when no semantic body exists.

The next implementation gate is to represent these three roles in the generic-
interface planner and physical-family record, then prove Kotlin implementations,
ordinary generated C# implementations, MethodImpls, overrides, defaults,
separate compilation, and rollback before admitting the actual collection
owner graph.
