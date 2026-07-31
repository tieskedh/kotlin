# Common collections programme review

Status: selected direction; first generator-owned, second array-backed-list,
and third non-inline query slices implemented and validated.

## Question

Should Kotlin/.NET keep adding isolated collection helpers only when another
feature happens to need them, or should collections become a first-class
feature programme?

Collections are the better next programme. They already have direct value for
ordinary Kotlin programs, exercise the target's split generic-interface ABI,
and form reusable foundations for enums and much of the remaining stdlib.
Enums therefore become a later consumer of the collection work rather than
the reason for doing it.

## Kotlin and mature-target authority

Kotlin Common remains authoritative for logical collection declarations and
algorithms. In particular:

- the Common `Iterator`, `Iterable`, `Collection`, and `List` contracts own
  Kotlin identity and semantics;
- `libraries/tools/kotlin-stdlib-gen` owns generated algorithms such as
  `first`, `firstOrNull`, `last`, and `lastOrNull`;
- JVM, JS, Wasm, and Native compile generated Kotlin sources and add
  platform-specific implementations only where their host requires one; and
- no mature target reconstructs Kotlin collection semantics from a foreign
  host collection interface.

The .NET target already follows this identity rule physically. Its canonical,
declared, and exact CLR views are capabilities on one object; they are not
separate Kotlin collections. That existing decision remains the foundation of
this programme.

## CLR interoperability boundary

`IEnumerable<T>`, `IEnumerator<T>`, `IReadOnlyCollection<T>`, and
`IReadOnlyList<T>` are useful foreign-language contracts, but none is the
authoritative Kotlin identity:

- `IEnumerator<T>` has `MoveNext`/`Current` state rather than Kotlin's
  `hasNext`/`next` contract;
- CLR generic variance does not preserve Kotlin covariance for value-type
  arguments;
- Kotlin read-only interfaces are views, not a promise that the underlying
  object is immutable; and
- silently wrapping during a Kotlin subtype conversion would change `===` and
  shared iterator state.

Future adapters and C# export helpers should therefore be explicit
interoperability capabilities. Imported CLR collections retain their foreign
identity. They do not become the implementation model for Kotlin-owned
collections.

## Selected source-product strategy

The full Common generated `_Collections.kt` is not a viable first product
slice. It is 4,130 lines and immediately depends on contracts, ranges, random,
mutable collections, maps, sets, sorting, and operations outside the current
backend surface. Compiling it now would either fail publication or falsely
claim a broad supported stdlib.

Hand-copying further Common algorithms into
`libraries/stdlib/dotnet/src` is also rejected. Even an initially exact copy
can drift when the Common templates change.

Instead, a bounded bootstrap generator invokes the existing Common
`Elements` templates and materializes only explicitly supported
Iterable/List variants into a classified Common source under
`libraries/stdlib/dotnet/common/src/generated`. The algorithm bodies,
documentation, annotations, and signatures therefore come from the same
template objects as the mature targets. The .NET-specific input is only the
temporary capability selection.

The first slice selects:

- `Iterable.first` and `List.first`;
- `Iterable.last` and `List.last`;
- `Iterable.firstOrNull` and `List.firstOrNull`; and
- `Iterable.lastOrNull` and `List.lastOrNull`; plus
- the exact Common `List.lastIndex` property required by `List.last`.

The generated source is passed through `-Xcommon-sources` by both the direct
repository product and the packaged fallback. It is emitted once on the
stable `Kotlin.Collections.CollectionsKt` stdlib facade. No consumer receives
an algorithm copy.

`lastIndex` is not owned by an `Elements` template: its authoritative
declaration is in Common `kotlin/collections/Collections.kt`. The bootstrap
generator therefore extracts that complete declaration from the Common source
file rather than maintaining a target copy. A changed or missing declaration
marker fails generation. On the CLR its generic extension getter is a static
generic method, matching JVM's accessor-method representation; an extension
property does not claim a CLR `.property` row.

That physical facade is shared with the target-private collection source
which owns array factories, `emptyList`, and concrete implementation classes.
Giving each source file a suffixed CLR file class would make the embedded KLIB
resolve a function whose compiler-owned external call points at a different
type. Compiler-owned stdlib source shards which explicitly select the same
facade are therefore emitted as one physical class, following the JVM
stdlib's single public collection-facade boundary. Callable-only and
accessor-only shards can be aggregated directly. At most one shard may own
top-level physical state and its class initializer; supporting several would
require a separately designed cross-file initialization order and is rejected
rather than guessed.

This is transitional source selection, not a new semantic fork. As the
backend supports the dependency closure, the allowlist grows and is
eventually deleted in favor of compiling the complete ordinary Common
generated corpus.

## Alternatives attacked

### Map Kotlin collections directly to BCL interfaces

Rejected. It loses Kotlin's value-type covariance and iterator contract, and
either changes identity through wrappers or lies about CLR assignability.

### Compile the complete Common collection corpus immediately

Rejected for this stage. A successful stdlib producer may not evict
unsupported declarations, and the current target does not yet support that
corpus's complete dependency graph.

### Continue handwritten target extractions

Rejected for new algorithms. It duplicates the Common implementation and
makes upstream template changes advisory instead of authoritative.

Keeping only a platform-source `List.last` is rejected for an additional
reason: a classified Common `Iterable.last` cannot resolve a platform-only
overload. It resolves itself and recurses when its receiver happens to be a
List. The dependency closure must remain Common together.

### Implement enums first and add only `EnumEntries` dependencies

Rejected as the work order. It would make generally useful collection
foundations look enum-private and would encourage a minimum implementation
shaped around one consumer. Enums remain a later vertical slice over the same
public collection substrate.

### Add `IEnumerable<T>` to every Kotlin collection now

Rejected as part of this source-product feature. Host enumeration adapters
affect ABI evolution, C# surface shape, allocation, identity, and mutable
views. They require a separate documented interoperability feature and must
not be smuggled into Common algorithm production.

## Programme order

1. Replace handwritten generated-algorithm copies with template-owned,
   classified Common slices and add nullable terminal operations.
2. Add concrete read-only list production (`listOf`/array-backed views) from
   its exact Common/actual dependency closure.
3. Compile Common abstract collection/list bases once their generated helper
   closure is present.
4. Add mutable collection/list interfaces and an ordinary implementation,
   then sets and maps, each from a concrete stdlib need.
5. Add explicit BCL adapters and C# convenience surfaces without changing
   Kotlin identity.
6. Let `EnumEntries` and later enums consume the established list substrate.

Every slice must execute on Framework CLR and CoreCLR, publish and consume a
portable self-describing stdlib, preserve canonical fallback behavior, and
test empty, singleton, widened, nullable, primitive, reference, and hostile
implementation shapes.

## Second-slice selected design

The next bounded product is the exact Common
`Array<out T>.asList(): List<T>` expect/actual pair. It is selected before
`listOf` because it has a closed non-inline Common declaration and one narrow
platform body. The complete `listOf` family also includes the Common
`@InlineOnly` zero-argument overload and separate vararg and singleton
contracts. Publishing only a convenient subset, or replacing the missing
cross-module inline model with a target intrinsic, would create a temporary
.NET semantic surface rather than complete one Common slice.

The bootstrap generator extracts the complete `Array.asList` expect
declaration from authoritative Common `_Arrays.kt`, with the same fail-closed
unique-marker rule already used for `List.lastIndex`. The .NET actual is
ordinary target stdlib Kotlin. Its representation follows Native and Wasm:
one Kotlin-owned `List<T>, RandomAccess` view retains the original array and
observes later element replacement. JVM and JS use their existing platform
list implementations for the same backed-view contract; none copies the
elements.

The current .NET product deliberately does not compile Common `AbstractList`
yet because its helper closure is programme step 3. The target actual
therefore implements the complete current Common `List` surface directly,
including structural equality/hash/text, forward and reverse iterators, and
backed sub-list views. This is bounded representation code, not a copied
Common algorithm family. It may be deleted in favor of the Common abstract
base once that base and its exact closure become supported.

The CLR gives a concrete reason not to reuse `System.Array.AsReadOnly`,
`List<T>`, or `IReadOnlyList<T>` as the implementation identity: those types
do not implement the target's canonical, declared, and exact Kotlin `List`
interfaces. Wrapping a BCL list again would add a second owner without
removing that mismatch. The Kotlin view therefore implements no BCL
collection interface in this slice. Explicit C# adapters remain programme
step 5.

Empty arrays are not redirected to `EmptyList`. JVM, JS, Wasm, and Native all
return an array-backed view for `asList`, including the empty case. Keeping a
distinct view also avoids silently changing identity and leaves the factory
contract uniform.

The Common signature also closes the first general output-projected generic
array boundary. `Array<out E>` keeps the same physical `E[]` vector token and
the projection remains in KLIB metadata, matching JVM's separation of source
projection from its array carrier. CLR reference-vector covariance can
therefore service `Array<Derived> -> Array<out Base>` without a wrapper.
CLR value vectors are not covariant: `int32[]` is not an `object[]`. The
backend must reject `Array<Int> -> Array<out Any>` rather than box, copy, or
emit invalid IL. Direct `Array<Int>.asList()` still instantiates the method at
`Int`, retains the original `int32[]`, and is fully supported. Input and star
projections remain outside this slice because neither identifies a truthful
CLR vector element token.

Adversarial validation must prove mutation and sub-view aliasing, empty and
singleton behavior, nullable/reference/value elements, covariant widening
without identity changes, wrong-type search barriers, duplicate search,
iterator boundaries, structural equality/hash/text, and the absence of a BCL
collection contract. Separate-product tests must prove that the Common expect
is preserved in the embedded KLIB while consumers call the single
`Kotlin.Collections.CollectionsKt` implementation on `net48`,
`netstandard2.0`, and `net10.0`.

## Abstract-base closure audit and third-slice selection

JVM, JS, Wasm, and Native all compile the exact shared
`AbstractCollection.kt` and `AbstractList.kt` sources. They do not maintain
target copies of the skeletal collection algorithms. Their only relevant
platform split is array production: JVM uses its runtime-type-preserving
`collectionToArray` implementation, while JS, Wasm, and Native delegate to
the shared collection-to-array algorithm.

The current .NET target cannot yet admit that complete source closure
truthfully:

- `AbstractCollection.contains`/`containsAll` and
  `AbstractList.indexOf`/`lastIndexOf` call generic inline Common functions,
  while cross-module generic inline lowering remains explicitly parked;
- `AbstractCollection.toString` calls Common `joinToString`, whose exact
  closure owns `CharSequence`, `Appendable`, and `StringBuilder`, also parked;
  and
- the two protected `toArray` methods require the Common expect/actual
  collection-to-array family. CLR arrays, like JVM arrays, retain their
  runtime element type, so the .NET actual must preserve the caller's `T[]`
  rather than use the erased Native/Wasm cast.

Importing the classes before those dependencies are real would force one of
three false choices: evict public/protected methods from the stdlib product,
copy Common algorithms into target source, or replace them with .NET
intrinsics even though the CLR creates no semantic need for an intrinsic.
All three are rejected. The temporary private array view therefore remains a
direct List implementation for now.

The next bounded slice instead grows the existing generator allowlist with
the exact non-inline Common query family whose dependency closure is already
supported:

- `Iterable.any()` and `Iterable.none()`; and
- `Iterable`/`List` `single()` and `singleOrNull()`.

This family is selected together because it answers collection
emptiness/cardinality without a predicate or transformation lambda.
`Iterable.any`/`none` preserve the Common Collection fast path;
`Iterable.single` preserves List dispatch; and the List overloads preserve
indexed access without touching the receiver's iterator. Predicate overloads
remain out because they are generic inline declarations, and `count` remains
out because its Iterable form introduces the separate overflow-helper
expect/actual closure.

As with the first slice, the source is generated by the same
`Elements`/`Aggregates` templates as the mature targets and emitted once on
`Kotlin.Collections.CollectionsKt`. This is a prerequisite collection
product, not a substitute for eventually compiling the complete abstract
base sources.

## First-slice outcome

The first slice implements exactly the selected operations and Common
`lastIndex` dependency. Both repository and packaged-fallback producers
classify the generated file as Common. The direct, fallback, installed, and
portable products contain one physical `Kotlin.Collections.CollectionsKt`;
none contains a suffixed `CollectionsKt1`.

The adversarial box distinguishes a general one-shot `Iterable` from a
`List` whose `iterator()` must never execute. It covers empty, singleton,
widened, nullable, primitive, and reference values. The IL-text pin separately
proves that a generic extension property is represented by a static generic
accessor without a CLR `.property` row. Installed portable products execute
the nullable operations and `lastIndex` on Framework CLR 4 and CoreCLR 10.

The strict aggregate gate completes 893 tests across 16 JUnit XML suites:
806 FIR/IL/box, 21 generated CLI, and 66 library integration tests, with zero
failures, errors, or skips. Physical ABI schema 16 and runtime surface level 9
remain unchanged.

## Second-slice outcome

The generator now extracts the complete Common `Array<out T>.asList()` expect,
and the ordinary .NET actual is emitted once on
`Kotlin.Collections.CollectionsKt`. The self-describing KLIB retains the
Common projection; its physical index contains the facade method but no
private view or iterator identity. Direct and fallback products are
byte-identical, installed consumers call the same facade, and the portable
`netstandard2.0` product executes its `Array<Int>` backed view on Framework
CLR 4 and CoreCLR 10.

The adversarial box executes for PSI and LightTree on both runtimes. It proves
array and nested-sub-list aliasing, reference projection, value/reference/
nullable elements, List covariance without identity change, wrong-type
`contains`/search/`containsAll` barriers, duplicate search, iterator and range
failures, structural equality/hash/text including NaN, signed zero, and
direct self rendering, plus distinct empty and singleton views. Product IL
proves that the private view implements Kotlin List/RandomAccess capabilities
and no `System.Collections` interface. A positive IL pin admits
`Derived[] -> Base[]`; the negative corpus withholds the unrepresentable
`int32[] -> object[]` value widening.

The fresh strict aggregate gate completes 897 tests across 16 JUnit XML
suites: 810 FIR/IL/box, 21 generated CLI, and 66 library integration tests,
with zero failures, errors, or skips. Physical ABI schema 16 and runtime
surface level 9 remain unchanged.

## Third-slice outcome

The generator now emits `Iterable.any()`/`none()` and
`Iterable`/`List` `single()`/`singleOrNull()` from the same Common
`Aggregates` and `Elements` templates as the mature targets. The embedded
KLIB and physical index retain all six overloads on the one
`Kotlin.Collections.CollectionsKt` owner. Direct and fallback products remain
identical, and separate and installed consumers bind only that public facade.

The adversarial box executes for PSI and LightTree on Framework CLR and
CoreCLR. It proves zero-element inspection for `any`/`none`, exact
empty/singleton/multiple behavior, nullable and widened values, traversal
counts, both List dispatches without iterator creation, indexed access only
for singleton Lists, and exact Common exception messages. Portable product
execution covers both target runtimes. Predicate-inline and count overloads
remain absent by design.

The fresh strict aggregate gate completes 901 tests across 16 JUnit XML
suites: 814 FIR/IL/box, 21 generated CLI, and 66 library integration tests,
with zero failures, errors, or skips. Physical ABI schema 16 and runtime
surface level 9 remain unchanged.
