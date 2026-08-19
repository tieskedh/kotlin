# Reified generic-interface member-declaring child (2026-08-19)

## Result

The test-only generic-owner rehearsal now preserves the first child interface
which both intersects admitted reified roots and declares its own generic
member:

```kotlin
interface Child<out T> : Primary<T>, Secondary<T> {
    fun produceChild(): T
}
```

The natural CLR shape is `Child<out T>` with a genuine `!T produceChild()`
MethodDef. The declaration-semantic shape is one non-generic child capability
which inherits both root capabilities and declares one object-result slot for
`produceChild`. It does not repeat either inherited root slot.

The same corpus separately proves the branch which did not previously need a
child capability:

```kotlin
interface MemberChild<out T> : Primary<T> {
    fun produceMemberChild(): T
}
```

This child cannot reuse `Primary`'s capability because its own declaration
needs an authoritative slot. It receives one child capability inheriting only
the primary capability and declaring only `produceMemberChild`.

Production remains on the accepted erased generic-interface ABI.

## General compiler rule

The interface-inheritance fixpoint now admits either no child-owned member or
one member matching the already proven producer-output shape. A member-free
child in one semantic domain reuses the parent capability. A child which owns a
member always receives a child capability because that declaration needs an
authoritative semantic slot. Intersections use the same capability construction
and inherited-supertype mechanism.

Root and child slots are materialized through one compiler primitive. Each slot
is derived from the declaring member's stable logical identity and registered
against that source member. The child materializes only its declared member;
parent members remain inherited from their producer-recorded capabilities.
There is no stdlib, collection, package, or declaration-name branch.

## Separate compilation and C# authoring

Assembly A declares the two roots. Assembly B declares `Child<T>`, its reader,
and a Kotlin implementation. Assembly C executes exact and widened calls. ABI
38 already carries the assembly-qualified child capability identity and its
external capability supertypes, so no schema change or consumer-side generated-
name inference is required.

The public C# implementation manifest records one declared `produceChild`
family for the child contract. It does not copy the root members. The supported
Roslyn generator composes the inherited root contracts and generates their
semantic bridges alongside each child bridge. Every partial C# class authors
only its natural methods. Kotlin widened calls reach all authored bodies on
Framework 4.8 and .NET 10.

## Evidence

The fail-first product rejected `Child<T>` as a non-interface carrier as soon
as `produceChild(): T` was introduced. After the compiler change, PSI and
LightTree execute the rehearsal and the production inverse on .NET 10 and
Framework 4.8: eight focused tests with zero failures, errors, or skips.

The exported .NET 10 IL additionally proves both child shapes:

- `Child<T>` directly implements both natural external root constructions and
  its local child capability;
- the child capability inherits both external root capabilities and owns
  exactly one abstract object-result method;
- the Kotlin implementation stores its value in one `!T` field;
- exact child calls use `Child<int>.produceChild()`; and
- widened child calls use the child capability slot without fabricating
  `Child<object>`.

## Remaining boundary

Multiple child-owned members, overloads, inputs, defaults, properties, generic
methods, mixed variance, deeper hostile inheritance, Runtime/Stdlib closure,
precompiled/non-partial and other-language implementors, deployment modes, and
the eventual atomic production cutover remain separate gates.
