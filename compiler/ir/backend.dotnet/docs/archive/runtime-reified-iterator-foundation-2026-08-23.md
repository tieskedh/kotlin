# Runtime reified iterator foundation (2026-08-23)

## Scope

The smallest honest Runtime `Collection<T>` graph cannot start at Collection.
Its inherited `iterator(): Iterator<T>` contract first requires two Runtime-
owned generic identities:

```text
Iterable<out T>
    -> Iterator<T> GetIterator()

Iterator<out T>
    -> bool HasNext()
    -> T Next()
```

ABI/runtime surface 49 adds those two natural CLR interfaces alongside the
accepted non-generic Runtime interfaces. The compiler exposes the constructed
views only in the generic-owner rehearsal epoch. Production/off code continues
to select the erased interfaces, so this checkpoint does not half-migrate the
public collection graph.

## Physical result

The Runtime now contains independently authorable covariant
`Kotlin.Collections.Iterator<T>` and `Kotlin.Collections.Iterable<T>`
TypeDefs. `Iterable<T>.GetIterator()` returns the constructed
`Iterator<T>`, not the erased interface or `object`.

During the rehearsal, every compiler-emitted Kotlin implementation which can
truthfully name the construction owns both MethodImpl bundles on one object:

```text
RuntimeIteratorValue<T>
    field !T value
    implements Iterator                 // Kotlin semantic fallback
    implements Iterator<!T>             // normal CLR route

RuntimeIterableValue<T>
    field !T value
    implements Iterable
    implements Iterable<!T>
```

An exact call first uses the constructed CLR interface. A Kotlin-legal view
which the CLR cannot name, such as value-type covariance from `Iterator<Int>`
to `Iterator<Any?>`, uses the existing erased semantic slot locally. It does
not change the object, duplicate state, or widen either field to `object`.

The nested declared view is retained in a typed outer signature only for a
Runtime family which explicitly promises that Kotlin implementations receive
the corresponding typed MethodImpl bundle. This prevents a general nested-
interface rule from making unproven split mappings look constructed.

## Loader bug and invariant

The first executable product failed with a CoreCLR `TypeLoadException` naming
`HasNext`. Metadata inspection initially made the private explicit MethodImpl
look suspect. The actual defect was earlier and more architectural: a compiler-
generated non-generic semantic capability had acquired `Iterator<object>` in
addition to the real object's `Iterator<T>` construction.

The emitter calculates interface closure twice: once for its assignability
graph and once for the final `implements` line. Excluding the constructed edge
only in the first calculation was insufficient. Surface 49 enforces the same
rule at both boundaries:

> A compiler-generated generic-owner semantic capability never acquires a
> constructed generic-interface parent. The real implementation object may
> carry that parent directly.

After that correction, the original private explicit MethodImpl shape loads
on both runtimes. The temporary public-method workaround was removed rather
than retained as accidental API.

## C# boundary

The executable probe compiles ordinary, non-partial C# with warnings as errors.
Its classes implement only `Iterator<T>` or `Iterable<T>` and author the normal
`HasNext`, `Next`, and `GetIterator` members. They do not name or implement the
erased Kotlin interface or any compiler semantic capability.

The same probe verifies that Kotlin-emitted reference- and value-type
implementations implement their exact constructed interface, that the nested
result remains `Iterator<T>`, that both private value fields are generic
parameters, and that neither generated semantic capability has a constructed
generic parent.

This is an additive dependency foundation, not the public signature cutover.
With the rehearsal disabled, arbitrary exported Kotlin signatures still name
the accepted erased Iterator/Iterable ABI. Consequently a C# value which owns
only the new natural interface is independently valid CLR code but is not yet
accepted by every erased Kotlin API boundary. That boundary must migrate with
the atomic Runtime/Stdlib collection graph; it is not papered over with a
wrapper or a requirement that C# manually implement Kotlin compiler ABI.

## Verification

The focused Kotlin/C# product is green under PSI and LightTree on .NET
Framework 4.8 and .NET 10. Reference covariance, value-type semantic widening,
nested constructed returns, identity, typed storage, and natural C# authoring
execute in every lane. Explicit rehearsal-off products are green on both
runtimes. The preceding exact/semantic generic-interface family is also green
in all four frontend/runtime lanes.

The final full target aggregate exits zero. Direct XML audit covers 190 freshly
written suites and 2,291 tests with zero failures, errors, or skips: 187 FIR
suites/2,163 tests, two integration suites/127 tests, and the one-test backend
resolver suite. The unchanged green six-test `dotnet.ir` model root makes the
target total 191 suites/2,297 tests.

## Boundary and next gate

This checkpoint does not migrate Runtime `Collection<T>`, Stdlib `Set<T>`,
mutable collections, Map, defaults, overload families, or multiple owner
parameters. The next gate remains the smallest atomic Runtime
`Collection<T>`/Stdlib `Set<T>` producer graph. It must use these Runtime-owned
constructed dependencies, keep typed CLR storage/calls as the normal route,
and migrate public Kotlin/C# type-use boundaries coherently rather than
leaving natural C# implementations behind an erased API.
