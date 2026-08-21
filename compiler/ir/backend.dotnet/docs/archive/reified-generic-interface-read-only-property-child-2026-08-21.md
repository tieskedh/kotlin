# Reified read-only property child checkpoint (2026-08-21)

This checkpoint closes the first exact inheritance edge between covariant
reified generic interfaces whose declared members are read-only properties.

## Scope

The structural proof is one public top-level covariant parent with one abstract
read-only owner-parameter property and one public top-level covariant child
which adds one further property:

```kotlin
interface Parent<out T> {
    val parentValue: T
}

interface Child<out T> : Parent<T> {
    val childValue: T
}
```

The parent substitution must be the exact owner parameter. The child property
must be public, abstract, read-only, non-null, and return that parameter
directly. Defaulted, mutable, multiple-property, changed-argument,
mixed-member, and deeper or multiple inheritance shapes remain closed.

## Physical ownership

`Parent<T>` and `Child<T>` remain natural covariant CLR generic interfaces.
The parent producer DLL owns `parentValue` and its typed `!T` getter. The child
producer DLL owns only `childValue` and its typed `!T` getter; it inherits the
exact `Parent<T>` construction rather than copying the parent Property or
accessor.

The non-generic semantic capabilities mirror that ownership. The child
capability owns only the child object-result getter and inherits the parent
capability. A generic Kotlin implementation stores its constructor properties
in two independent physical `!T` fields. Semantic widening remains an
operation boundary and does not erase either interface or field.

## C# authoring composition

An ordinary partial C# class implements `Child<string>` with two natural
read-only C# properties. It does not name or implement either compiler
capability. Generated source supplies the capability adapters and forwards each
one to its corresponding source property. Exact C# calls and Kotlin calls
widened independently through `Parent<Any?>` and `Child<Any?>` therefore reach
the same foreign object and the selected property body.

## Fail-first evidence

Before the lowering change, the child was absent from the reified family and
the separate-compilation candidate failed because its exact parent
instantiation mapped to a non-interface CLR carrier. The parent itself was
already admitted. The repair extends only covariant child-shape admission for
one abstract read-only property and excludes interface default implementations.
It does not weaken the general inherited-family gate or create a semantic
fallback for broader shapes.

## Verification

Reflection checks covariance, the exact `Child<T> : Parent<T>` edge, one
child-owned and one parent-owned CLR Property row, their declaring assemblies,
and both physical `!T` backing fields. Kotlin exact and widened parent/child
calls, ordinary C# property calls, generated semantic adapters, and receiver
identity are exercised across separate `lib.dll`, `middle.dll`, and consuming
assemblies.

All four PSI/LightTree x Framework 4.8/.NET 10 candidate lanes and all four
erased epoch-off inverse lanes pass. The final normal aggregate audits 190 XML
suites and 2,287 tests with zero failures, errors, or skips.
