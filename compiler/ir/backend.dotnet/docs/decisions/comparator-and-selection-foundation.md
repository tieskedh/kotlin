# ADR: Common Comparator and order-selection foundation

- Status: **Accepted pre-ABI**
- Date: 2026-08-12
- Scope: the Kotlin `Comparator<T>` declaration, Common comparison-combinator
  source, comparator-based scalar selection, Iterable comparator selection,
  and non-mutating sortedness traversal
- Does not enable: `MutableList.sort`/`sortWith`, array sorting, `sorted*`
  snapshots, binary search, random/shuffle, Sequence ordering, or a BCL
  comparer/collection identity

## Decision

Kotlin/.NET publishes Common `Comparator<T>` as one ordinary Kotlin-owned fun
interface in `Kotlin.Stdlib`. Its single `compare(T, T): Int` member uses the
already accepted Kotlin generic-interface ABI. The declaration remains
invariant, exactly as Common declares it; call sites express consumer
flexibility with `Comparator<in T>`. It is not a typealias for
`System.Collections.Generic.IComparer<T>`, a CLR delegate, or an export facade.

The target compiles the complete authoritative Common
`kotlin.comparisons.Comparisons.kt` file. Common therefore owns
`compareValues`, `compareValuesBy`, `compareBy`/descending forms, chained
comparators, nullable ordering, natural/reverse singleton comparators, and
reversal. The .NET target adds no equivalent BCL implementation and does not
recognize these declarations as intrinsics.

The same Common generator owns this first dependency-closed consumer set:

- comparator-based two-, three-, and vararg `minOf`/`maxOf`;
- `Iterable.minWith`, `minWithOrNull`, `maxWith`, and `maxWithOrNull`;
- `Iterable.minOfWith`, `minOfWithOrNull`, `maxOfWith`, and
  `maxOfWithOrNull`; and
- `Iterable.isSortedWith`, `isSorted`, `isSortedDescending`, `isSortedBy`, and
  `isSortedByDescending`.

These operations only traverse existing arrays/Iterables, invoke the ordinary
interface slot, compare established `Comparable` values, and return an
existing element/value. They require no sort buffer, mutation algorithm,
random source, Sequence identity, or new runtime helper. Generated source is
a projection of the same template members consumed by the mature targets;
the allowlist selects families but owns no algorithm body.

## Cross-target alignment

JS, Wasm, and Native actualize Common `Comparator<T>` as an ordinary Kotlin
fun interface. JVM alone uses `java.util.Comparator<T>` as a platform
typealias, while retaining the Common comparison functions. Kotlin/.NET
follows the non-JVM declaration identity because its internal Kotlin ABI must
not depend on a BCL generic owner. SAM construction and equality use the
accepted Common wrapper lowering and `FunctionAdapter` runtime capability.

Natural ordering invokes Kotlin `Comparable.compareTo`, including the
existing ordinal String boundary and Kotlin Float/Double total order. It must
not dispatch wholesale to `System.IComparable` semantics. Nullable comparison
is Common's explicit branch order, not `Comparer<T>.Default`.

## Physical and C# boundary

The physical declaration is an ordinary public CLR interface TypeDef in
`Kotlin.Stdlib`; it has no `MulticastDelegate` base and no implicit identity as
`IComparer<T>`. Kotlin-produced comparator wrapper classes remain private.
C# can implement and call the ordinary interface through the established
interface-authoring boundary. Direct C# lambda conversion and an idiomatic
`IComparer<T>` view are not part of this tranche.

The current compatible path is an export using
`System.Collections.Generic.IComparer<T>`. Exact export-created comparators
may implement both interfaces when the physical signatures permit it;
otherwise explicit `toIComparer`/`asKotlinComparator` adapters preserve object
and exception behavior. The separate generic-owner reopening must also test
whether the .NET actual can instead become a truthful direct foreign typealias,
as JVM's actual aliases `java.util.Comparator<T>`. Such a migration is allowed
only when imported naming/variance, SAM construction, reflection, casts,
open-generic Kotlin implementors, value-type substitutions, and separate
compilation all agree; until then the current Kotlin identity remains
authoritative.

The tranche adds Stdlib declarations but no Runtime capability or manifest
encoding. Runtime surface 36 and library ABI codec 35 therefore remain
unchanged. A separately compiled consumer binds declarations through the
producer's embedded KLIB and self-describing physical manifest.

## Stable sorting boundary

Common declares `MutableList<T>.sortWith` as a platform `expect`. JVM delegates
to Java's stable collection sort; JS uses its platform array sort; Native/Wasm
copy through a target array sort. Kotlin/.NET does not select one of those
host-specific implementations implicitly.

The accepted
[stable-list-and-array-sorting ADR](stable-list-and-array-sorting.md) now owns
that platform boundary. It reuses the Native/Wasm stable merge lineage over
the classified CLR array carrier and proves arbitrary-list behavior, both CLR
profiles, and direct C# calls without changing Comparator identity.

## Rejected alternatives

### Alias Comparator to `IComparer<T>` in this tranche

Rejected for the present owner model. Common owns an invariant Kotlin fun
interface, while `IComparer<in T>` is a reified foreign generic interface. In
particular, today's non-generic physical owner for `class C<T> : Comparator<T>`
has no CLR `!T` with which to implement `IComparer<T>`; substituting
`IComparer<object>` is untruthful for value-type arguments. The future
generic-owner reopening may supersede this decision with a complete direct
actualization rule. A one-interface exception or closed-reference-only alias
remains rejected.

### Treat Comparator as a delegate

Rejected. Kotlin interface implementation, inheritance, casts, SAM equality,
and KLIB identity require the ordinary interface model accepted by the fun-
interface ADR.

### Admit `sorted*` before a stable platform actual

Rejected. Common snapshot functions ultimately require `sort`/`sortWith` on
lists or arrays. Publishing them with a target-authored insertion sort, an
unstated BCL dependency, or an unstable algorithm would hide the remaining
foundation rather than complete it.

## Completion evidence

The gate must cover both FIR parsers and both CLR profiles, unchanged Common
or compiler tests where compatible, and hostile target cases for:

- direct and stored comparator SAMs, `compareBy` chains, reversal, nullable
  order, and natural String/Float/Double ordering;
- first-element tie retention, empty behavior, vararg traversal, selector and
  comparator evaluation count/order, early termination, and exception
  identity;
- generic/widened `Comparator<in T>` calls and separate Kotlin libraries;
- ordinary interface TypeDef/MethodImpl shape, private wrappers, and absence
  of delegate or BCL comparer identity; and
- a Roslyn class implementing and calling the exact ordinary CLR interface.
