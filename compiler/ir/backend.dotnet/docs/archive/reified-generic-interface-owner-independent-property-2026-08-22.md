# Reified generic-interface owner-independent property

Date: 2026-08-22

## Question

Can the general covariant natural/exact/semantic family carry a normal
owner-independent read-only primitive property, as required by
`Collection.size`, without turning it into a method-only C# convention or
introducing a Runtime/Stdlib special case?

The structural proof adds `val exactSize: Int` to the existing hostile family
which already contains a constructed producer result, nested exact input,
primitive method queries, separate Kotlin libraries, generated C#
implementations, and ordinary precompiled/non-partial C# implementations.

## Physical family

ABI/runtime surface 47 adds the explicit
`OWNER_INDEPENDENT_PROPERTY_GETTER` role. It is intentionally distinct from
`OWNER_INDEPENDENT_QUERY`: the producer record states that this member is an
abstract read-only property getter rather than asking a consumer to infer that
fact from its primitive signature.

The natural covariant interface owns one real CLR Property row:

```text
.property instance int32 exactSize()
    .get instance int32 get_exactSize()
```

The invariant exact sibling does not copy that property. The non-generic
Kotlin capability owns only its compiler-named `int32` method slot and no
Property row. A Kotlin implementation exposes the same public natural
property and delegates its private capability MethodImpl to that getter. Its
generic `!T` state is unchanged and no object field or shadow property is
introduced.

## Foreign dispatch

An ordinary C# implementation writes only:

```csharp
public int exactSize => 1;
```

The same source shape works with the supported generator and in a separately
compiled sealed non-partial assembly with no generator. C# calls the property
directly. A widened Kotlin reader first tries the semantic capability and then
selects exactly one natural interface construction for a raw implementation.
The reflection helper invokes `get_exactSize` on that construction.

The operation-local join uses `object` because the reflection API returns
`object`: the capability branch boxes `int32`, the raw branch returns the
boxed reflection result, and the Kotlin caller unboxes it back to `int32`.
This boxing does not change the public property type, owner state, or normal
direct C#/Kotlin path. The foreign fallback is now structurally available to
any admitted no-input value-result member whose semantic result is either its
natural CLR type or `object`.

## Verification

PSI and LightTree execute the property family on Framework 4.8 and .NET 10.
The proof checks direct and widened Kotlin access, direct C# property syntax,
generated C# implementations, reference and value constructions, a raw
precompiled C# property, the manifest's declared Property locator, and one
unchanged object identity. The prior foreign-subclass rehearsal remains green
on all four parser/runtime lanes.

The final complete `dotNetTest` aggregate exits zero. Direct XML audit reports
191 suites and 2,293 tests with zero failures, errors, or skips: 187 FIR suites/
2,159 tests, two integration suites/127 tests, one backend resolver test, and
the unchanged six-test `dotnet.ir` model root.

## Boundary and next gate

This admits one abstract, public, read-only, non-null primitive property getter
inside the existing structural broad-input composition. Mutable properties,
owner-dependent property results, nullable results, defaults, custom interface
accessor bodies, and multiple property shapes remain separate gates.

Runtime/Stdlib collection mappings remain excluded. Before migrating
`Collection<T>` or `Set<T>`, the same emitted family must materialize and prove
the optional fixed-barrier direct input together with the existing nested
semantic input: the structural equivalent of `contains(T)` plus
`containsAll(Collection<T>)`.
