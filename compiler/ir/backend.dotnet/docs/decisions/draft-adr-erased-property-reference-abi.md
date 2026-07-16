# Draft ADR: Erased Kotlin property-reference ABI on CLR

- Status: **Draft candidate; bounded implementation in the prototype**
- Date: 2026-07-16
- Scope: Kotlin property-reference identity and execution across CLR assembly boundaries

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

A Kotlin property reference has two related roles. It is a reflective `KPropertyN` object with a
name and `get`/optional `set` operations, and an immutable property reference is also invokable as
the corresponding function type. Bound receivers belong to the reference object and must be
evaluated once when that object is created.

The mature targets split those roles in two main ways. JVM generates specialized property-
reference subclasses because its reflection runtime already defines that class hierarchy. Native
and Wasm lower a property reference to a wrapper containing ordinary getter and optional setter
callable objects. The .NET POC has the callable-object machinery needed by the latter model but no
JVM-like reflection base-class hierarchy, so the Native/Wasm wrapper model is the relevant
precedent.

The CLR still requires one target-specific adaptation. Kotlin property types are variant and may
contain primitive arguments, while CLR generic variance does not apply through value-type
instantiations. Generic physical `KPropertyN` interfaces would therefore repeat the identity hole
already rejected for `FunctionN`.

## Candidate decision

The POC uses non-generic Kotlin-owned property-reference identities:

```text
Kotlin.KProperty : Kotlin.KCallable
Kotlin.KMutableProperty : Kotlin.KProperty
Kotlin.KProperty0 : Kotlin.KProperty, Kotlin.Function0 { object Get() }
Kotlin.KProperty1 : Kotlin.KProperty, Kotlin.Function1 { object Get(object) }
Kotlin.KProperty2 : Kotlin.KProperty, Kotlin.Function2 { object Get(object, object) }
Kotlin.KMutableProperty0 : Kotlin.KProperty0, Kotlin.KMutableProperty { void Set(object) }
Kotlin.KMutableProperty1 : Kotlin.KProperty1, Kotlin.KMutableProperty { void Set(object, object) }
Kotlin.KMutableProperty2 : Kotlin.KProperty2, Kotlin.KMutableProperty { void Set(object, object, object) }
```

Logical receiver/value types remain in IR and, eventually, Kotlin metadata. Calls cast or unbox
the erased results and box arguments at this first universal boundary. The existing erased
`Kotlin.Function0/1/2` interfaces remain the only callable execution identity: `KPropertyN` merely
inherits the matching interface, and invocation uses that existing slot.

Following Native/Wasm, the runtime implementation object stores the property name, a lowered
getter `FunctionN`, and an optional lowered setter `FunctionN+1`. The wrapper delegates `Get`,
`Set`, and `Invoke` to those callables. Bound receiver storage remains inside the generated getter
and setter objects. The common property-reference lowering evaluates a non-trivial bound receiver
once and shares it between both objects.

Runtime wrapper classes are private implementation details. Metadata-public factory methods live
under `Kotlin.Runtime.Internal` solely because generated modules construct wrappers across the
assembly boundary. They are compiler/runtime contracts, not a Kotlin or C# programming surface.
An explicit Kotlin class implementing KPropertyN uses the same erased Get/Set and inherited
FunctionN slots; it does not need, and is not assumed to expose, an exact execution capability.

The first slice constructs immutable arities zero through two and mutable arities zero and one.
The `KMutableProperty2` identity and erased `Set` slot can be represented, but constructing its
wrapper requires a three-argument setter callable and the callable POC currently ends at
`Function2`. This ADR does not expand the callable ABI as a side effect of property references;
mutable arity-two references stay explicitly unsupported until `Function3` is designed as part of
the callable family.

## Boundaries

- This decision does not reuse or extend the provisional export-selector grammar. Property
  references are Kotlin runtime values, not CLR facade-selection controls.
- It does not define full reflection (`getter`, `setter`, `returnType`, parameters, owner,
  annotations), local delegated-property reflection, equality, hashing, or rendering.
- It does not promise exact typed property access. Optional execution capabilities may be added
  later, but erased `Get`/`Set` and the inherited erased `FunctionN.Invoke` remain universal.
- Consumers may not depend on the private wrapper names or assume that a property reference
  exposes any widened or original logical signature as another CLR interface.
- It does not expose wrapper implementation-class names as ABI.

## Consequences

The design preserves one property-reference object across legal Kotlin variance conversions and
reuses the established callable identity. It also matches the existing lowering pipeline: getter
and setter references proceed through the ordinary callable-reference lowering, including bound
receiver and mutable-capture handling.

The cost is boxing at the erased wrapper boundary and a deliberately small reflection surface.
Those are acceptable for the POC because typed access can be layered on without changing identity,
whereas choosing a generic canonical interface now would make primitive variance identity
unrecoverable without adapters.

## Validation at this draft stage

The focused property-reference box assembles the generated program and Kotlin.Runtime with both
modern ILAsm and the .NET Framework 4.8 ILAsm selection, then executes both products on CoreCLR.
It covers immutable and mutable arities zero/one, direct Get/Set, inherited FunctionN invocation,
primitive-result variance, one-time bound-receiver evaluation, and an explicit user implementation.
The exact IL-text pin separately records the generated wrapper-construction and erased-call shapes.
