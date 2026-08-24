# Common collections programme

- Status: **Active — Map min/max adapter closure complete; next leaf tranche paused for generic-owner rehearsal**
- ABI foundation: [`../decisions/generic-interface-erased-identity.md`](../decisions/generic-interface-erased-identity.md)

## Purpose

Collections are a first-class Kotlin/.NET feature programme. They provide ordinary user value,
exercise generic-interface and array ABI, and form prerequisites for enums and wider stdlib work.

Kotlin Common remains authoritative for logical declarations and algorithms. The .NET target adds
only narrow actuals and physical representation work required by the CLR.

## Mature-target authority

- Common `Iterator`, `Iterable`, `Collection`, and `List` own Kotlin identity and semantics.
- `libraries/tools/kotlin-stdlib-gen` owns generated algorithm bodies.
- JVM, JS, Wasm, and Native compile those generated sources and add platform implementations only
  where their host requires one.
- No mature target reconstructs Kotlin collection semantics from a host collection interface.

Kotlin-owned generic collection interfaces have one erased CLR owner and one erased virtual slot
family. Independently truthful mapped BCL capabilities, such as `IComparable<T>` for Common
`Comparable<T>`, are explicit exceptions rather than implicit siblings of every Kotlin interface.

## CLR interoperability boundary

`IEnumerable<T>`, `IEnumerator<T>`, `IReadOnlyCollection<T>`, and `IReadOnlyList<T>` may become
useful adapters or export surfaces, but they are not Kotlin collection identity:

- CLR enumeration state differs from Kotlin `hasNext`/`next`;
- CLR generic variance does not preserve Kotlin covariance for value arguments;
- Kotlin read-only collections are views, not immutability promises; and
- implicit wrapping during a subtype conversion would change `===` and shared iterator state.

Imported CLR collections retain foreign identity. Kotlin-owned collections acquire BCL capabilities
only through a separately documented interop feature.

## Transitional source-product strategy

Compiling the complete generated Common collection corpus before its dependency closure exists
would either fail publication or falsely advertise support. Copying algorithms into .NET sources
would fork Common behavior.

A bounded bootstrap generator therefore invokes the existing Common templates and materializes
only explicitly admitted variants under `libraries/stdlib/dotnet/common/src/generated`. Exact
non-generated Common declarations are extracted fail-closed from their owning source file. A
missing, changed, or ambiguous marker fails generation.

This allowlist is temporary. It grows with real backend capability and is eventually removed in
favor of compiling the complete ordinary Common/generated corpus.

Generated and extracted shards preserve their authoritative source ownership in three physical
facades: ordinary generated collection templates use `Kotlin.Collections.CollectionsKt`,
`Maps.kt` uses `Kotlin.Collections.MapsKt`, and `Sets.kt` uses
`Kotlin.Collections.SetsKt`. This mirrors the mature source/JVM facade split and prevents CLR
erasure from collapsing unrelated generic-receiver overloads such as collection and map
`ifEmpty` onto one signature. It is physical naming, not a fork of the Kotlin declarations.
At most one shard may own top-level physical state and its initializer in a facade; multiple state
owners require a separately designed initialization order and are rejected meanwhile. Once a
generated shard is admitted, its complete function closure is physically owned by the selected
facade; there is no second hand-maintained function allowlist which can omit private Common
helpers. Exact resolution markers remain declaration-suppressed after their consuming lowerings.

## Current admitted product

### Completed Set/Map and hash-storage foundation

The completed tranche extends the same one-owner rule from lists to `Set`, `MutableSet`, `Map`,
`MutableMap`, `Map.Entry`, and `MutableMap.MutableEntry`. Each Kotlin-owned declaration has one
non-generic CLR interface identity and one erased virtual slot family. `HashMap`, `HashSet`, and
their linked aliases are ordinary Kotlin-owned erased classes; KLIB remains authoritative for all
key, value, entry, variance, and mutability relationships. No BCL collection becomes a second
runtime identity, and no typed export is implied by this substrate.

The implementation authority is the shared Native/Wasm open-addressed hash table and its set
facade. It preserves Common structural equality and hash rules, nullable keys and values,
insertion-order iteration, mutable key/value/entry views, fail-fast iterators and detached entry
comparison, builder sealing, and overflow behavior. The one necessary CLR adaptation is private
storage: an erased Kotlin-owned map has no physical `K` or `V` token with which to allocate
`K[]`/`V[]`, so keys and values use `object[]`; the hash and presence tables remain `int[]`.
Logical reads narrow at their declared use sites. Disabling or changing that private carrier may
affect performance, but may not alter DLL signatures, casts, reflection, dispatch, or identity.

JVM's `java.util.HashMap`/`HashSet` typealiases are the required host-mapping counterexample, not
the default architecture. Java's collection hierarchy already participates in Kotlin/JVM's
erased mapping and bridge model. `System.Collections.Generic.Dictionary<K,V>` and
`HashSet<T>` expose constructed CLR identity, and `Dictionary` rejects null keys; their comparer,
enumerator, view, entry-mutation, and versioning contracts are not the complete Kotlin contracts.
Legacy `Hashtable` also rejects null keys and exposes `IDictionaryEnumerator` plus mutation slots
that do not match Kotlin. They therefore remain importable native .NET types and possible private
or export-layer implementation choices, but neither may define Kotlin runtime identity.

The completed dependency closure is one bounded product:

- the six erased interfaces including both nested entry interfaces, Common
  `AbstractMutableSet`/`AbstractMutableMap`, the Native/Wasm `HashMap` algorithm adapted only at
  its private storage boundary, and `HashSet` over that map;
- Common `HashMap`, `HashSet`, `LinkedHashMap`, and `LinkedHashSet` expect/actual surfaces plus the
  exact empty, singleton, mutable, hash, linked, and builder factories;
- Common Map entry/components/basic access and mutation, eager conversions, filters,
  key/value transforms, `getOrElse`/`getOrPut`, and non-Sequence plus/minus operations;
- generated Iterable and object-array association, grouping to eager lists, distinct, Set
  algebra, and Set snapshots; and
- unchanged upstream `IteratorsTest` and `HashMapCompactTest` compiled through the portable
  Kotlin.Test/stdlib product, with target-owned tests retained for erased CLR ABI, metadata,
  profile execution, C# calls, collisions, views, exceptions, and non-local inline returns.

The linked declarations remain Kotlin aliases over the insertion-ordered hash implementation;
they do not manufacture separate `LinkedHashMap` or `LinkedHashSet` CLR TypeDefs for C#. That
absence is part of the low-level runtime boundary, not an export policy for a future typed C# API.

The closure also repaired four compiler-wide boundaries exposed by authoritative Common source:

- a final method inherited from a base class can satisfy an interface first declared by a derived
  class through one private forwarding MethodImpl, matching Kotlin/JVM source semantics;
- a literal final `while (true)` emits no impossible verifier-visible fallthrough edge;
- the Common AbstractMap caches emit the CLR `volatile.` prefix on every marked field access; and
- a stdlib product resolves calls to admitted stdlib helpers locally and can never create a
  self-reference to an older external `Kotlin.Stdlib` assembly.

The admitted runtime surface is version 16. Product metadata proves that all top-level and nested
Kotlin collection interfaces are non-generic CLR TypeDefs, while Roslyn executes ordinary erased
`HashMap` and `HashSet` calls without observing `Dictionary<K,V>`, `HashSet<T>`, or another BCL
generic interface as Kotlin identity.

The Kotlin-owned Sequence, eager Iterable window/chunk and Sequence-consumer,
generated equality aggregates, Grouping, and signed primitive/object-array
sorting closures are completed separately below. Unsigned arrays/ranges, random,
dependency-blocked reified variants, concurrency, and BCL adapters remain
separate closures. The formerly parked open-nullable boundary is complete under
[`../decisions/open-nullable-array-views-and-varargs.md`](../decisions/open-nullable-array-views-and-varargs.md):
ordinary `Array<out T?>` reads retain the original vector through
`System.Array`, Kotlin-owned `vararg T?` expansions use a fresh declaration-
stable `object[]`, and the authoritative object-array `filterNotNull`/
`filterNotNullTo` plus both `setOfNotNull` overloads are admitted. Invariant or
input-projected method-owned `Array<T?>` remains excluded except for the one
immutable conditionally initialized local view required by Common
`RingBuffer.toArray`; that local retains either the resized or supplied exact
vector, accepts only writes of the original logical `T`, and creates no
declaration ABI.
A source member that reaches any other excluded boundary must fail closed
rather than receive a .NET-specific body.

The gate proves null keys and values, primitive/reference/widened keys, hash collisions,
replacement without reordering, resize and upstream compaction, entry `setValue`, live
key/value/entry removals, iterator removal and concurrent modification, map/set rendering and
ordering, builder sealing, Framework CLR/CoreCLR execution, public metadata, C# reflection/calls,
and the absence of implicit `Dictionary<K,V>`/`HashSet<T>` identity. Changed-key behavior and a
broader foreign-implementation equality matrix remain useful additions to the shared-test product;
they are tests of the same accepted representation, not missing architectural decisions.

### Completed Kotlin-owned Sequence foundation

The completed Sequence tranche publishes the authoritative Common
`Sequence<out T>` identity, builder and non-builder implementation objects and
adapters, sliding windows, and the complete generated Sequence inventory
except for explicitly dependency-blocked members. One non-generic erased CLR
interface owns the iterator capability; KLIB retains element variance and
every logical generic signature. The implementation classes, coroutine-backed
builders, and lazy pipelines are the Common objects and algorithms, not
`IEnumerable<T>`, LINQ, or target-authored loops.

The original exact excluded partition was fail-closed: sequence-builder-
dependent `ifEmpty`, `flatMapIndexed`, running/scan, `zipWithNext`, window/
chunk members; `shuffled` and random; `groupingBy`/`Grouping`; and unsigned
selector sums. The later Grouping and coroutine-builder closures have admitted
`groupingBy` plus every builder/window member. Random-backed `shuffled` and the
unsigned selector sums remain excluded.
The generator fingerprints the complete Common inventory and rejects any new
or changed member that is neither admitted nor explicitly excluded. Its
supporting dependency closure includes Common array/Iterable adapters,
`MutableCollection.addAll(Sequence)`, `AbstractIterator`, and the exact
floating-point expects/actuals reached by sorting and selection.

All top-level operations use the one source-aligned
`Kotlin.Sequences.SequencesKt` facade. Where the erased receiver and CLR's lack
of return-type overloading collapse Common families, physical names are
derived deterministically from the logical element or selector-result type.
An instantiated generic min/max result may temporarily arrive through its
physical `Comparable` upper-bound view; only the frontend-proven implicit
substitution recovery uses `unbox.any` to restore a primitive result. This is
not permission to broaden explicit or safe cast behavior.

The gate proves lazy iterator/callback counts, constrained-once failure,
covariance, array and Iterable adapters, all flatten/flatMap routes, reified
filtering, stable lazy sorting and sortedness including NaN/signed zero,
builders and all `yieldAll` routes, running operations, overlapping/gapped/
partial/transformed/expanded windows, eager snapshots, numeric and selector
overloads, deterministic metadata, and the absence of BCL enumeration
identity. The same portable
`netstandard2.0` stdlib and consumer execute through the real Framework CLR 4
host and .NET 10; both PSI and LightTree also execute direct profile products.
Roslyn implements the erased Sequence interface and calls a public Common
facade method, without implying an idiomatic typed C# export. See the
[`Sequence` foundation ADR](../decisions/sequence-foundation.md).

### Completed eager Iterable windowing closure

The completed eager tranche publishes all four generated Iterable classifier
members: ordinary and transforming `windowed`, plus ordinary and transforming
`chunked`. Their exact Common bodies compose the already selected
`SlidingWindow.kt` machinery. The first complete compile exposed Common's
`List(size, init)` declaration and its delegated `MutableList(size, init)` as
the one missing prerequisite, so the owning generator projects that exact
factory pair into the same `CollectionsKt` product rather than inventing a
.NET factory or loop.

List/RandomAccess receivers retain Common's indexed fast path, snapshot
windows, and ephemeral moving-sublist transform view. Other Iterables retain
the shared `windowedIterator` and RingBuffer path. Tests pin overlap, gaps,
partial windows, empty input, transform-view reuse, traversal counts, callback
exception identity and stopping point, and the exact invalid-size/step message.
The KLIB declarations and physical `CollectionsKt` MethodDefs are consumed both
from an installed Kotlin library and from one portable netstandard stdlib on
the real Framework CLR 4 and .NET 10 hosts. No `IEnumerable<T>`, LINQ, or BCL
window implementation is introduced. CharSequence/array windowing, Random,
unsigned, reified, and idiomatic C# export remain separate selections.

### Completed eager Iterable Sequence-consumer closure

The completed tranche publishes exactly the seven generated Iterable-family
declarations whose removed dependency is Kotlin Sequence: ordinary and indexed
`flatMap` plus both destination variants when the transform returns Sequence,
`minus(Sequence)`, and `plus(Sequence)` for both Iterable and Collection
receivers. Their exact Common bodies compose the already selected
`MutableCollection.addAll(Sequence)`, Sequence-to-list, filter, snapshot, and
overflow helpers. No operation is rewritten for .NET.

The four flatMap pairs have identical physical Function parameter carriers
because CLR overload identity cannot observe the lambda result type. The
existing Iterable-result declarations keep their physical names. Only the new
siblings receive the source-aligned `flatMapSequence`,
`flatMapIndexedSequence`, `flatMapIndexedSequenceTo`, and
`flatMapSequenceTo` names, derived from the logical selector result in IR. This
is a bounded compiler-owned stdlib ABI projection; it does not give general
.NET meaning to `@JvmName` and does not introduce a public `DotNetName`.

Tests pin overload resolution by lambda return type, eager transform and inner-
Sequence order, destination identity, exception identity/stopping, one RHS
traversal, `minus` RHS materialization before receiver traversal, empty
snapshots, and nullable/widened elements. Installed KLIB consumers inline all
four flatMap bodies and call the public plus/minus fallbacks on Framework CLR 4
and .NET 10. Roslyn directly implements the canonical erased Sequence
interface and calls both plus receiver forms and minus. KLIB retains the full
generic `Sequence<T>` signatures. This closure neither authorizes a partial
physical `Sequence<T>` owner migration nor blocks a separately selected typed
C# adapter/export; the canonical owner can change only through the atomic
generic-interface cutover with primitive covariance, iterator/state, casts,
reflection, overrides, and separate compilation proved together.

### Completed equality aggregate closures

The completed generated equality tranche publishes exactly 20 new Common
declarations: `allEqual` and `allEqualBy` for Iterable, generic object arrays,
and each of the eight signed primitive-array wrappers. The same pair for
Sequence was already admitted by the complete Sequence foundation. These are
one supported-classifier family; no receiver is silently omitted and no target
algorithm replaces a Common body.

The direct tests pin zero selector invocations for empty and singleton inputs,
first-mismatch short circuit, traversal and callback exception stopping,
nullable selector keys, widened nullable/numeric values, and the exact
equals-consistent NaN and signed-zero behavior for primitive and boxed Float
and Double arrays. Raw product metadata contains ten ordinary and ten selector
MethodDefs on `CollectionsKt`. Installed Kotlin calls all ten ordinary
fallbacks and inlines all ten selector bodies; Roslyn calls the signed
`IntArray` overload directly. The portable netstandard product executes on
both Framework CLR 4 and .NET 10.

The subsequent `allDistinct`/`allDistinctBy` tranche publishes the same 20-
declaration supported-classifier matrix. Common's allocation-free signed-
ByteArray fast path is retained by making its shared 256-bit set consume a
normalized Int index: signed Byte uses `toInt() and 0xFF`, while the upstream
UByte caller uses `toInt()`. This removes an accidental public unsigned-scalar
dependency from the internal helper without changing either algorithm. The
.NET stdlib emits the helper as one non-public type and emits no `Kotlin.UByte`
TypeDef; public unsigned arrays and ranges remain unselected.

The hostile evidence additionally pins singleton selector elision, first-
duplicate short circuit, complete and oversized Byte-domain behavior,
nullable selector keys, callback exception stopping, and Float/Double NaN and
signed-zero distinctness for primitive and boxed values. Raw metadata again
contains ten ordinary and ten selector MethodDefs. Installed Kotlin calls all
ten ordinary fallbacks and inlines every selector body, while Roslyn calls the
signed IntArray overload directly on Framework CLR 4 and .NET 10. No target
HashSet algorithm or partial classifier family was introduced.

### Completed natural min/max aggregate closure

The completed natural-order tranche publishes exactly 52 additional Common
declarations. Iterable and generic object arrays each contribute `min`, `max`,
`minOrNull`, and `maxOrNull` for generic Comparable elements and the dedicated
Float and Double templates. ByteArray, ShortArray, IntArray, LongArray,
FloatArray, DoubleArray, and CharArray each contribute the same four forms.
The generator defines no natural-order BooleanArray variant, so none is
invented. The corresponding Sequence inventory was already admitted by the
complete Sequence foundation.

The exact Common bodies preserve `NoSuchElementException` versus null for
empty inputs, first identity for comparison-equal elements, one traversal per
call, NaN propagation, and Kotlin total ordering for signed zero. Iterable and
object-array erasure makes the logical generic, Float, and Double siblings
differ only by return type in CLR metadata. Their physical names therefore use
the same bounded logical-element-derived mapping as Sequence:
`minOrThrow`, `minOrThrowOfFloat`, `minOrThrowOfDouble` and their max/nullable
counterparts. KLIB retains the logical Kotlin overloads; this is neither a
general public `DotNetName` facility nor a physical `Sequence<T>` migration.

Raw metadata contains exactly 52 methods with the pinned physical-name
distribution. Installed Kotlin calls every one, while Roslyn directly calls
the IntArray `min` and `max` overloads. Hostile Kotlin evidence covers empty
throwing/nullable behavior, tie identity and traversal, all seven primitive
classifiers, generic object arrays, and Float/Double NaN/signed-zero behavior.
One portable netstandard product executes on Framework CLR 4 and .NET 10.

### Completed selector min/max aggregate closure

The subsequent selector-order tranche publishes exactly 40 additional Common
declarations: `minBy`, `maxBy`, `minByOrNull`, and `maxByOrNull` for Iterable,
generic object arrays, and each of the eight signed primitive-array wrappers.
Boolean is valid and required in this family because selector result `R`, not
the receiver element, supplies the Comparable ordering. The corresponding
Sequence declarations were already admitted by the complete Sequence
foundation; Map and CharSequence variants remain separately classified.

The exact Common bodies preserve `NoSuchElementException` versus null for an
empty input and deliberately do not invoke the selector for empty or singleton
inputs. Every later visited element is selected exactly once, comparison-equal
keys retain the first element, and callback failure stops traversal at the
failing element. Generic Comparable dispatch also retains Kotlin Float/Double
NaN and signed-zero total ordering rather than a direct CLR numeric comparison.

The first hostile compile exposed a general emitter defect rather than a
collection-specific problem. The inliner represents a local return from these
bodies as a break from a synthetic do-while loop. When the inline call was a
later arithmetic operand, the break emitter discarded operands which predated
loop entry. Loop registration now records the evaluation-stack baseline;
same-region break/continue drains only values above it, while cross-region
`leave` still requires an empty entry stack. The original inline expression
now executes on Framework CLR 4 and .NET 10 without rewriting the Common body.

Raw metadata contains ten MethodDefs for each logical name. These are ordinary
public inline fallbacks, not `@InlineOnly`: installed Kotlin consumers inline
all 40, while Roslyn implements the truthful erased `Kotlin.Function1`
capability and directly calls the signed IntArray fallbacks. No Runtime surface,
ABI schema, physical-name mapping, or lambda/delegate export claim was added.

### Completed selector-result min/max aggregate closure

The following selector-result tranche publishes exactly 120 additional Common
declarations. `minOf`, `maxOf`, `minOfOrNull`, and `maxOfOrNull` each have
generic Comparable, Float, and Double result forms for Iterable, generic object
arrays, and all eight signed primitive-array wrappers. Boolean is required
because the selector result rather than the receiver element supplies ordering.
The corresponding Sequence declarations were already present; comparator,
Map/CharSequence, Random, and unsigned families remain independent. The
comparator family is completed by the separately recorded closure below.

The unmodified Common bodies pin throwing versus null for empty inputs, zero
selector calls for empty inputs, exactly one call for a singleton, first-result
identity for equal Comparable keys, and immediate callback-failure propagation.
The Float/Double-specialized result forms preserve Kotlin NaN propagation and
signed-zero ordering on Framework CLR 4 and .NET 10.

This family also closes a backend result-representation gap. A substituted
generic nullable result can be physically returned through its reference-
shaped Comparable upper bound. For a concrete value result such as `R = Int`,
that boundary contains boxed Int or null. A frontend-proven `IMPLICIT_CAST`
now uses `unbox.any Nullable<Int>` to recover both cases. The rule is limited
to supported nullable scalars and does not change explicit `as` or `as?`
semantics.

CLR cannot overload the generic, Float, and Double forms by return type alone,
so the exact compiler-owned projection uses twelve bounded names: the four
logical generic names plus `...Float` and `...Double` variants. Raw metadata
contains ten MethodDefs under every physical name. Installed Kotlin compiles
and executes all 120 declarations and inlines every `@InlineOnly` body, leaving
no fallback calls. Those fallbacks are assembly-visible rather than public;
Roslyn is explicitly rejected from calling them. This is an honest interop
boundary, not a general `DotNetName` facility or a generic-owner migration.

### Completed comparator min/max aggregate closure

The remaining comparator-order selection is now complete for the supported
collection classifiers. The eight existing Iterable declarations are joined by
72 object-/primitive-array declarations. The final surface contains
`minWith`, `maxWith`, `minWithOrNull`, `maxWithOrNull`, and the four matching
selector-result `minOfWith`/`maxOfWith` forms over Iterable, generic object
arrays, and all eight signed primitive-array wrappers. Map, CharSequence, and
unsigned variants remain independent classifier families.

The closure uses only the already admitted Kotlin-owned Comparator, iteration,
array indexing, and inline foundations. The hostile oracle pins throwing versus
null for empty inputs, zero comparisons for singleton element selection, one
selector call and zero comparisons for singleton result selection, first
identity for comparison-equal elements/results, and exact comparator/selector
failure order. It also covers nullable selector results, a contravariant
`Comparator<Any?>`, every receiver, and explicit Float NaN and Double signed-
zero behavior under the supplied comparator.

No CLR return-only collision or physical-name mapping exists in this family.
Raw metadata contains ten MethodDefs for each of the eight source names.
Installed Kotlin calls all 40 ordinary element-selection fallbacks and inlines
all 40 `@InlineOnly` selector-result bodies. Roslyn implements the erased
`Kotlin.Comparator` slot and directly calls signed IntArray `minWith` and
`maxWithOrNull`; it is rejected from the assembly-visible `minOfWith` fallback.
This does not claim implicit C# delegate/`IComparer<T>` conversion and does not
change Kotlin Comparator or generic-owner identity.

### Completed CharSequence min/max aggregate closure

CharSequence now publishes its complete 28-declaration generated min/max
family on the source-aligned `Kotlin.Text.StringsKt` façade. The closure
contains natural min/max, selector element min/max, generic/Float/Double
selector-result min/max, comparator element min/max, and comparator
selector-result min/max, each with throwing and nullable forms. It remains
separate from Map because the two families have different source files,
façades, bodies, and fallback visibility.

The first generated compile identified one exact missing prerequisite:
Common's `CharSequence.lastIndex` extension property. The owning generator now
projects that declaration into the same StringsKt product. CLR represents its
extension receiver as one static getter MethodDef; KLIB retains the Kotlin
property identity, and no false CLR PropertyDef is invented.

The hostile oracle exercises both arms of the classified carrier: an unchanged
`System.String` reference and a Kotlin object implementing the erased
`Kotlin.CharSequence` capability. Empty/singleton evaluation, first
selector-result identity, comparator/selector failure order, nullable results,
Float/Double NaN and signed-zero behavior, and exact indexed custom dispatch
execute on Framework CLR 4 and .NET 10.

The generic, Float, and Double selector-result overloads collide physically by
return type. The existing bounded mapping now keys admission by exact façade,
function package, and receiver, admitting StringsKt/`kotlin.text`/
`CharSequence` without exposing a general `DotNetName`. Raw metadata contains
28 aggregate MethodDefs: twelve public fallback methods and sixteen
assembly-visible `@InlineOnly` methods. Installed Kotlin calls eight ordinary
fallbacks and inlines the other twenty bodies. Roslyn directly calls natural,
selector, and comparator fallbacks using both a string and an explicitly
implemented foreign CharSequence; inline-only methods remain inaccessible.

### Completed Map min/max aggregate closure

Map now publishes its complete 24-declaration generated min/max adapter family
on the source-aligned `Kotlin.Collections.MapsKt` façade. It contains selector
element min/max, generic/Float/Double selector-result min/max, comparator
element min/max, and comparator selector-result min/max, each with throwing
and nullable forms. Map has no natural entry min/max family.

Every declaration is the authoritative Common `@InlineOnly` adapter over the
receiver's `entries` view. The hostile oracle proves empty/singleton selector
counts, first ties and result identity, nullable comparator results, callback
stopping, and Float/Double NaN/signed-zero behavior on Framework CLR 4 and
.NET 10. No target algorithm, eager entry copy, or BCL map operation exists.

The generic, Float, and Double result overloads require the same bounded
return-only physical naming as the completed collection and CharSequence
families. Admission is exact by MapsKt façade, `kotlin.collections` package,
and `Map` receiver; no general `DotNetName` policy is introduced. Raw metadata
contains 24 assembly-visible MethodDefs. Installed Kotlin inlines every Map
adapter; comparator-element bodies then call the source-prescribed public
CollectionsKt entries fallback. C# is rejected from calling the inline-only
Map helpers directly.

This closure retains logical `Map<K, V>` KLIB types over today's erased
physical Map owner and adds no fields. It is expected to recompile against the
future atomic CLR-generic owner model. No further erased-owner leaf tranche is
selected before the complete Kotlin-emitter/inverse-rollback rehearsal reaches
its next go/no-go checkpoint.

### CLR-generic MutableCollection rehearsal

The atomic generic-owner rehearsal has now reached invariant natural
`MutableCollection<T>` without changing the production-erased decision below.
Its exact element operations use `T`, while `addAll`, `removeAll`, and
`retainAll` have physical CLR slots `<U : T>(Collection<U>)`. That relative
method parameter preserves a nested `Collection<int>` when Kotlin widens it
into `MutableCollection<Any?>`; neither the owner nor its true `!T` state is
therefore forced to `object`.

This is one structural relative-generic-input rule rather than a collection
codegen exception. The Kotlin declaration and KLIB member remain non-generic.
Ordinary C# implements the natural interface and its selected mutation members
under normal Roslyn checking. Only an unnameable projected view of a
capability-free foreign object uses the bounded runtime fallback. The prior
inherited Collection candidate-input convention, trimming, NativeAOT,
performance, and tooling remain open gates. See the
[`surface 54 archive`](../archive/runtime-reified-mutable-collection-2026-08-23.md).

Surface 55 composes natural invariant `MutableSet<T>` over natural `Set<T>`
and `MutableCollection<T>`. The child redeclares its mutable iterator and
mutation slots, while both child and mutable-collection bulk MethodDefs reuse
the same relative generic input. CLR interface maps prove both Kotlin paths;
one warnings-as-errors C# method implements both contracts. The Kotlin set
keeps one identity and `!T` state. Widened read-only Set candidate inputs remain
the separate covariant-parent gate rather than forcing the invariant child or
its state into the object domain. See the
[`surface 55 archive`](../archive/runtime-reified-mutable-set-2026-08-23.md).

Surface 56 selects the dependency-closed invariant natural `MutableList<T>`
family over the existing List and MutableCollection parents. Direct positional
mutation remains typed, mutable iterators and sublists retain their natural
constructed results, and Kotlin implementations keep one `!T` state graph.
The indexed and non-indexed bulk overloads both use `<U : T>`: the compiler
selects the unique `Collection<T>`-shaped owner-relative parameter rather than
assuming it occupies position zero.

The same structural rule extends the bounded natural-only foreign path. One
owner-dependent input may be surrounded by declaration-independent parameters
and may return Unit, a declaration-independent value, or `T`; overload
selection remains name plus complete arity. This admits `add(index, value)` and
`set(index, value)` without a MutableList branch. Inherited covariant List
candidate inputs keep their existing exact/semantic protocol and do not erase
the invariant child or duplicate parent slots. The provisional ABI/Runtime
epoch advances to 56 so none of the three new mutable collection TypeDefs can
be paired with a surface-53 Runtime. See the
[`surface 56 archive`](../archive/runtime-reified-mutable-list-2026-08-24.md).

The post-surface-56 dependency recomputation selects `Map.Entry<out K, out V>`
as surface 57 before Map. It is parentless and therefore forms the smallest
complete remaining Runtime prerequisite, while also forcing the compiler's
first real multiple-owner-parameter proof. The admitted declaration shape is
not Map-specific: two or more covariant nullable-`Any`-bounded parameters,
exactly one abstract read-only direct producer property per parameter, a
bijective getter/parameter mapping, and no other member.

Runtime keeps the accepted nested arity-zero Entry as the Kotlin semantic
identity and adds a natural nested arity-two Entry under the existing
arity-zero Map metadata container. This does not select a natural Map owner.
Kotlin implementations must retain independent `!0` and `!1` fields; ordinary
non-partial C# implements only the natural two-property interface. Stars, open
arguments, projections, and value-type widening may select the semantic
operation without erasing exact Entry constructions, either field, or the
later Map family. MutableEntry and Map remain dependency-blocked until their
own complete structural families are selected. The four focused rehearsal
lanes, four production-erased inverse lanes, and strict 191-suite/2,329-test
target inventory are green. See the
[`surface 57 archive`](../archive/runtime-reified-map-entry-2026-08-24.md).

The next dependency recomputation selected and completed invariant
`MutableMap.MutableEntry<K,V>` as surface 58. It is the smallest complete child
of the now-natural Entry root and adds one typed `V -> V` mutation without
selecting Map or MutableMap. Admission is structural: an equal-arity
identity-substituted covariant producer-property parent, all child parameters
invariant, and exactly one abstract direct input/output member whose argument
and result are the same non-null owner parameter. The child inherits the
parent's typed key/value getters and adds `!V SetValue(!V)`; only a projected
operation may cross its semantic input/output slot. Runtime nests the natural
child under the existing arity-zero MutableMap metadata container. No generic
MutableMap owner is implied.
The full Runtime regression closure also preserves natural closed and
method-generic classifier inputs through paired object entries, coalesces
relative-input MethodImpls, prevents semantic reachability from degrading
producer-proven typed fields, and fixes value boxing plus the Runtime
`containsAll` helper's physical stack contract. The strict 191-suite/2,333-test
target inventory is green. See the
[`surface 58 archive`](../archive/runtime-reified-mutable-map-entry-2026-08-24.md).

The following dependency recomputation selected and completed mixed-variance
`Map<K,out V>` as surface 59. Its structural eight-member grammar combines an
invariant key, covariant value, Boolean key/value probes, nullable value lookup,
one owner-independent primitive property, one primitive query, and natural
read-only Set/Collection/Entry results covering K, V, and K/V. Only already
published covariant natural result families are admitted; the lowering contains
no Map-name switch.

Natural Map retains `ContainsKey(!K)` and all three typed constructed views.
The invariant exact sibling owns `ContainsValue(!V)`. The nullable lookup uses
`object Get(!K)` because an unconstrained CLR V cannot uniformly denote both a
nullable reference and `Nullable<V>`, but Kotlin implementations still keep
independent `!0` key and `!1` value fields. Thus the exceptional carrier is
operation-local and does not turn Map, List, or nested generic state into
object storage. Exact/widened/star Kotlin calls, including an explicit unsafe
cast used as a lookup receiver, and an ordinary natural-only C# class preserve
one receiver and view identity. The four rehearsal lanes, four
production-erased inverse lanes, complete eleven-family Runtime selection, and
strict 191-suite/2,337-test target inventory are green. `MutableMap<K,V>`
remains unselected. See the
[`surface 59 archive`](../archive/runtime-reified-map-2026-08-24.md).

### Completed Kotlin-owned Grouping foundation

The completed Grouping tranche publishes the authoritative Common
`Grouping<T, out K>` interface, every executable aggregate/fold/reduce/count
declaration from `Grouping.kt`, the Common `eachCount` expect with its
Native/Wasm actual, and the exact four generated factories over Iterable,
Sequence, object arrays, and CharSequence. The generator does not define a
primitive-array factory, so none is invented for .NET.

KLIB retains both type parameters and key covariance. The physical product
uses one non-generic erased `Kotlin.Collections.Grouping` interface with only
source-iterator and key-selection capabilities; it does not acquire LINQ,
`IGrouping<TKey, TElement>`, or `IEnumerable<T>` identity. Common map state is
authoritative for absent versus present-null accumulators, first flags,
pre-seeded destinations, encounter order, and exception timing. Factories keep
their source-aligned CollectionsKt, SequencesKt, and StringsKt ownership;
aggregate operations use GroupingKt.

The gate proves laziness and repeated traversal, all four factories, nullable
accumulator semantics, both fold families, reduction, destination identity,
key covariance, exception identity/timing, deterministic metadata, and the
negative BCL boundary. Roslyn directly implements the erased interface and
calls `eachCount`. One portable `netstandard2.0` stdlib and identical consumer
execute independently on the real Framework CLR 4 host and .NET 10, including
object-carried primitive and Char values; both PSI and LightTree execute direct
products on both profiles. See the
[`Grouping` foundation ADR](../decisions/grouping-foundation.md).

### Completed mutable collection/list foundation

The completed tranche is a representation and product foundation rather than one more
leaf-function slice. It landed as one coherent boundary comprising:

- the authoritative `MutableIterator`, `MutableListIterator`, `MutableIterable`,
  `MutableCollection`, and `MutableList` logical contracts, backed by one non-generic erased CLR
  TypeDef and slot family per Kotlin-owned interface;
- the Common `AbstractMutableCollection` and `AbstractMutableList` expect contracts with an
  actual implementation derived from the shared JS/Native/Wasm algorithm lineage, including
  mutable iterators, fail-fast `modCount`, range removal, and live mutable sublists;
- the Common `ArrayList` expect contract and an ordinary Kotlin-owned erased .NET actual with one
  object-vector state, capacity management, fail-fast iterators and sublists, and read-only builder
  sealing;
- the exact Common list factories, size constructors, and `buildList` surface whose complete
  dependencies become available;
- separate-DLL physical ABI, stale-runtime rejection, Framework/CoreCLR execution, and a CLR
  metadata/C# boundary which proves that no BCL generic collection identity is implied; and
- execution of every dependency-closed upstream stdlib collection test through a real .NET test
  product, retaining target-owned tests only for physical CLR and product boundaries.

This tranche deliberately tests the accepted generic-class and generic-interface erasure decisions
at their hardest ordinary stdlib boundary. Mutable return refinements such as
`MutableIterable.iterator(): MutableIterator` and `MutableList.subList(): MutableList` must be
satisfied through the general erased-interface and covariant-return bridge machinery; a
collection-specific dispatch path is forbidden. `MutableCollection.remove` uses the repository's
shared special-bridge semantics and returns `false` for an incompatible widened argument instead
of narrowing it before the Kotlin body.

JVM uses host collection implementations but preserves the Common API; JS, Native, and Wasm share
the Kotlin mutable abstract-base and array-list algorithm lineage. The .NET actual follows that
lineage. The one necessary CLR adaptation is private storage: an erased Kotlin-owned
`ArrayList<E>` has no reified CLR `E` token with which to allocate `E[]`, so it stores elements in
an `object[]`, boxing CLR value elements. KLIB retains `E`, and reads narrow or unbox only at their
logical use sites. A private `List<object>`, a public `ArrayList<E>` CLR TypeDef, or a second typed
state would add host identity or recreate the rejected hybrid generic-class model.

JVM's host mapping remains the required counterexample rather than evidence that every target must
own its collection class. `java.util.List` has erased runtime identity and a protocol close enough
to Kotlin's `MutableList` for the JVM backend's established name, bridge, and mutability-marker
mapping; `java.util.Iterator` already separates `hasNext()` from `next()`. Neither CLR candidate
has that complete match. `System.Collections.Generic.List<T>` makes its constructed generic
identity observable, while legacy non-generic `System.Collections.ArrayList` implements
`IEnumerator.MoveNext()`/`Current`, returns an index from `Add(object)`, and returns `void` from
`Remove(object)`. It therefore cannot fill the Kotlin erased virtual slots without pervasive
compiler call rewriting and iterator adapters, and it supplies no modern typed C# surface in
return. Using either BCL class as private storage would still require the Kotlin owner and is a
removable implementation choice, not a reason to change its runtime identity. This is why the
.NET actual follows the JS/Native/Wasm owned-class precedent after explicitly testing and rejecting
the JVM-style host mapping.

Capacity overflow follows the Native/Wasm algorithm and throws Common `OutOfMemoryError` before
reading the supplied collection. The exact CLR carrier is `System.OutOfMemoryException`, as owned
by the classified-exception ADR. Substituting an available argument exception would make the
target implementation, rather than Common, authoritative.

The same foundation includes general open generic-vararg materialization because the authoritative
Common factories use `vararg T`. Like JVM, Native, Wasm, and JS, .NET must support omitted,
literal, spread, and forwarded generic varargs rather than hand-writing factory bodies. The CLR can
represent a method-owned `vararg T` exactly as `T[]`: `newarr !!T`, `ldelem !!T`, and `stelem !!T`
work for both reference- and value-type substitutions. This is a truthful CLR capability owned by
the method and does not reintroduce CLR-generic identity for Kotlin-owned classes. The physical
signature authority reads the vararg marker itself: pre-lowering KLIB still spells the parameter
as source-level `Array<out T>`, whereas both producer CIL and separate consumer calls must use the
normalized invariant `T[]`. Treating that declaration as an ordinary bounded output projection
would compile same-module calls but fail separate execution with a missing method.

Common `listOfNotNull` also requires the general nullable method-parameter smartcast. For
`<T : Any>(value: T?)`, the logical null check narrows the read to `T`, while the open nullable
parameter uses the accepted boxed-or-null `object` slot. The CLR recovery is `unbox.any !!T`, the
single operation that checks a reference substitution and unboxes a value substitution. This is a
frontend-proven generic null-narrowing rule, not a collection helper or an unchecked object cast.

Common `ifEmpty` exercises a second general method-generic boundary: `C : R`. ECMA-335 can retain
that relative method constraint directly. The codegen model must keep the positional relationship
and widen the same `C` value to `R` only when that constraint proves it. The physical conversion is
`box C; unbox.any R`, not merely `box C`: `R` can also be a value-type substitution, so its local
or return slot must receive an actual `R` value. Unrelated open parameters remain rejected. This
preserves receiver identity and avoids a Common-specific cast or wrapper.

The foundation does not add `IEnumerable<T>`, `IList<T>`, or another BCL capability. Interop
evidence must instead show both truthful sides: imported CLR generic collections keep their native
constructed identity, while Kotlin-owned mutable collections expose only their erased low-level
CLR contract until a separate explicit adapter/export feature is selected. This keeps later
adapter work removable and prevents it from determining Kotlin runtime identity.

The bootstrap generator now admits every Common collection-template
variant whose dependency closure consists only of the already published read-only foundation,
this mutable-list foundation, arrays, fixed function arities, and existing exceptions/helpers.
The exact inventory is generator-owned and fail-closed and now includes the completed Set/Map
closure above. Ordinary signed ranges, complete Sequence builders, Grouping,
eager Iterable windowing and Sequence consumers, equality, natural min/max,
selector min/max aggregates, and signed-array sorting have
since landed as complete independent
foundations. Random, dependency-blocked reified variants, reflection, and
unsigned families remain excluded when they introduce an independent
dependency rather than being approximated or copied.

The first bulk admission is intentionally collection-facing and homogeneous. It includes the
complete dependency-closed Iterable/List variants of:

- indexed iteration plus the authoritative `IndexedValue`, `Iterators.kt`, and `Iterables.kt`
  source closure;
- `elementAtOrElse`/`getOrElse`, drop/take and predicate filtering, including generic mutable
  destinations and nullable filtering;
- collection snapshots, mutable snapshots, and primitive-array snapshots;
- map/flatMap and indexed/not-null/destination variants whose transform returns `Iterable` rather
  than `Sequence`;
- running fold/reduce and their indexed aliases;
- in-place mutable-list reverse and Iterable reversed snapshots;
- partition, plus/minus for single, Iterable, or Array operands, and zip/zipWithNext for Iterable
  or Array operands; and
- every non-Sequence mutable-collection helper needed by those bodies, including array/Iterable
  bulk mutation and first/last removal.

The authoritative `Pair` and `Triple` source used by partitioning and zipping carries Common's
internal `JsImplicitExport(Boolean)` directive. The bootstrap source product retains that exact
declaration and both applications in KLIB. Like the existing optional `JsName` expectation, its
declaration lives in the resolution-only `kotlin.js` shard and receives no .NET TypeDef or CLR
custom-attribute row: the payload controls JS declaration-file export and has no truthful .NET
meaning. This collection-source decision does not itself define valued annotation classes;
user/runtime construction, Common value semantics, CLR value encoding, foreign import, and
annotation reflection remain separate feature boundaries. Dropping the applications from
`Tuples.kt`, inventing a parameterless
stub, or treating this JS compiler directive as a CLR attribute would make target source rather
than Common authoritative.

`Pair`, `Triple`, `IndexedValue`, `IndexingIterable`, and `IndexingIterator` are ordinary
Kotlin-owned classes in the stdlib assembly. Generic members and classes use the already accepted
erased Kotlin runtime ABI; `Tuples.kt` follows JVM's file-facade ownership as `Kotlin.TuplesKt`,
without implying a CLR-generic `Pair<A, B>` surface. Anonymous and local callable classes created
inside an admitted stdlib shard remain owned by that same shard after closure conversion, rather
than leaking into the user assembly. This is source-product partitioning, not a collection- or
lambda-specific constructor path.

The array-valued InlineOnly mutable operators expose a CLR-only inliner boundary rather than a
collection exception. Type inference through `MutableCollection<in T>` can widen the inliner's
logical parameter temporary while the supplied Kotlin array remains an exact value vector. The
backend therefore preserves exact array storage provenance through immutable compiler-generated
inline temporaries and widens only at the already selected projected `System.Array` consumer.
User locals, invariant ABI and writable destinations receive no such widening.

This batch is one compiler-foundation test: it combines erased class and interface dispatch,
method-owned CLR generics, relative constraints, projected arrays, nullable type-parameter
recovery, inline non-local control flow, data classes, anonymous generic classes, and
cross-library inlining. That first batch did not silently bundle array-receiver overloads. The
later Set/Map tranche admits only the object-array association, grouping, snapshot, and Set-op
variants whose array carriers are already exact. At that boundary Sequence,
random, primitive/range sorting, range signatures, reflection, unsigned, and
still dependency-blocked reified variants failed closed outside the admitted
batches; Sequence, ordinary signed ranges, and signed-array sorting have since
landed only through their own complete foundations.

The first shared semantic-test product is deliberately built from repository-owned sources rather
than a .NET transcription. Its generator projects the exact Common `Test` expectation,
`assertEquals`, `Asserter`, `messagePrefix`, `AssertionErrorWithCause`, and `DefaultAsserter`
closure into a staged `Kotlin.Test.dll`; the .NET source supplies only the parameterless CLR marker,
default-asserter selection, and exception-construction actual. The existing
`libraries/stdlib/test/collections/IteratorsTest.kt` and dependency-closed
`HashMapCompactTest.kt` are compiled unchanged against a portable stdlib and that test product and
executed on Framework CLR and CoreCLR. The test also forces the exact Common assertion failure
message and verifies that `@Test` becomes a real CLR custom attribute. This proves the upstream-
test path, hash-compaction behavior, and portable producer/consumer ABI; it does not claim the
complete kotlin.test API or an external test-framework adapter yet.

Several bodies use scalar dependencies that are smaller than the independently parked range
product. Exact Common `require`, `Int.coerceAtLeast`, and two-argument `Int.minOf` are admitted;
the last keeps its generated Common expect plus the same narrow actual comparison used by the
mature non-JVM targets. `optimizeReadOnlyList` is extracted from its authoritative Common owner.

`drop` and `takeLast` spell counted implementation loops with `Int.until`, the exact
random-access mutable-list filter compacts its tail with `Int.downTo`, and selected generic-array
search spells `for (index in indices)`. This programme initially kept those dependencies behind
private resolution markers and a bounded target loop matcher. The completed ordinary-range
tranche removed all three markers, compiles the real Common declarations, and routes both these
collection bodies and user code through shared `RangeContainsLowering`/`ForLoopsLowering`.
Materialized ranges, `step`, Long progressions, and ordinary iteration now use the same public
range/progression object model rather than a collection-specific counted-loop exception.

### Builder and Common abstract-base foundation

The completed builder/abstract-base tranche compiles the authoritative Common
`Appendable`, exact generated `Iterable.joinTo`/`joinToString`, and Common
`AbstractCollection` and `AbstractList`. The subsequent contracts tranche
replaced the temporary non-contract projection with the complete Common
`StringBuilder` file, including both `buildString` declarations. Its widened
`containsAll` regression belongs to the shared semantic matrix and must pass
through the one owner-erased virtual route; no collection-specific bridge is
permitted. Nothing in the admitted builder file is missing or target-authored.

`StringBuilder` is a Kotlin-owned `CharSequence` and `Appendable` over private
BCL storage. The public ABI does not expose the BCL type. Common `RandomAccess`
is the authoritative expect declaration and a narrow .NET actual supplies the
physical marker.

`AbstractCollection<out E>` and `AbstractList<out E>` keep logical covariance in KLIB. Their
physical Kotlin runtime representation is one non-generic erased CLR class per declaration; the
superseded invariant typed classes and canonical identities no longer participate. Source-built and
actualized/deserialized inner-class parameter forms normalize to the same outer-first logical
arity; Common `AbstractList.IteratorImpl`, `ListIteratorImpl`, and `SubList` exercise that path.

Common `AbstractCollection` has an optional valued `@JsName("toArray")`
expectation. The .NET bootstrap declaration is resolution-only and is erased
before product IR: it neither relies on the general annotation-value runtime
nor emits a CLR attribute for a JS-only directive.

### Generated terminal and cardinality operations

The admitted Common template variants are:

- `Iterable`/`List`: `first`, `last`, `firstOrNull`, and `lastOrNull`;
- `Iterable`: `any` and `none`; and
- `Iterable`/`List`: `single` and `singleOrNull`.

The exact Common `List.lastIndex` extension property is extracted with them. On the CLR it is an
ordinary static generic accessor method, as on JVM; an extension property does not claim a CLR
Property row.

Template selection preserves Common fast paths: Collection cardinality, List indexed access, and
hostile implementations behave according to the upstream algorithm rather than a .NET rewrite.

### Array-backed read-only list view

The exact Common `Array<out T>.asList(): List<T>` declaration is paired with a narrow .NET actual.
The Kotlin-owned `List<T>, RandomAccess` view retains the original array and observes later element
replacement. Empty arrays also receive backed views; they are not redirected to `EmptyList`.

The private view now extends authoritative Common `AbstractList`; its former direct iterator,
sublist, equality, hash, and rendering algorithms have been removed. It owns only the retained
array, size, and checked indexed access required by that representation.

The view implements no BCL collection interface. Reusing a BCL wrapper would still not implement
the Kotlin-owned erased interfaces and would add another identity layer.

### Output-projected generic arrays

`Array<out E>` retains the original exact vector object through a physical `System.Array` view and
KLIB projection metadata. Reads recover the KLIB-declared `E`; writes remain projected out. This
represents `Array<Int> -> Array<out Any?>` without boxing, copying, or invalid `int32[] -> object[]`
IL, while an invariant `Array<Int>` remains the exact `int32[]` carrier.

Star projection uses the separately selected erased `Any?` read contract over the same physical
base. Input projections still need a truthful write carrier and remain outside this boundary.

### Indexed optional access

The admitted Common template closure pairs:

- `List<T>.getOrNull(index)`; and
- `Iterable<T>.elementAtOrNull(index)`.

All mature targets take both bodies from `Elements`. The Iterable body uses the List operation for
its indexed fast path and otherwise traverses only as far as the requested element. A negative
index returns `null` without constructing an iterator. The List operation checks bounds before one
indexed access.

The List body spells that bounds check as `index in 0..<size`. JVM, JS, Wasm, and Native all run
the shared `RangeContainsLowering`; it removes the temporary range and `contains` call in favor of
primitive comparisons. Kotlin/.NET must admit that same Common lowering immediately before its
for-loop lowering. Rewriting the generated body as target source would only hide a missing shared
compiler phase, while materializing an `IntRange` solely for this check would retain work the mature
targets deliberately eliminate.

The Common `List.elementAtOrNull` overload remains absent from the currently published bootstrap
allowlist: upstream marks that special overload `@InlineOnly`. The completed ordinary inliner has
removed the compiler blocker, so this exact generated declaration is now eligible for the next
source-admission slice. Until that slice updates generation, product metadata, and cross-DLL tests
together, including only the Iterable overload remains sound because ordinary overload resolution
still chooses it for a List and reaches the admitted non-inline List fast path. A target copy or
non-inline substitute remains forbidden.

Both admitted functions are ordinary static generic methods on
`Kotlin.Collections.CollectionsKt`. The CLR creates no representation difference and supplies no
reason for an intrinsic or BCL implementation.

### Iterable count and overflow

The admitted closure is Common `Iterable<T>.count()`. Its `Aggregates` body returns a
`Collection.size` directly and otherwise consumes the iterator once, calling the Common
`checkCountOverflow` expect declaration after every increment. The paired Common
`throwCountOverflow` helper owns the `ArithmeticException` type and exact message.

The generated `Collection<T>.count()` overload remains absent from the current allowlist because it
is `@InlineOnly`; it is now eligible for the same exact-source adoption as the selected predicate
families. Until then, a statically known Collection still resolves to the admitted Iterable
overload and reaches the same size fast path. Publishing a non-inline substitute overload would
fork the Common source surface.

JVM and Native/Wasm make the internal `checkCountOverflow` actual `@InlineOnly`; JS uses an ordinary
actual with the same body. The Common expect declaration itself is not inline. Kotlin/.NET retains
the JS-shaped callable actual after reassessment with the first inline collection slice. This is
not a semantic fork: regular inline bodies may call the `@PublishedApi` helper, and its physical
compiler-ABI method makes the same overflow decision and throws through the same Common helper.
Selecting the JVM/Native/Wasm shape would instead require a complete `@InlineOnly` declaration-
suppression and ABI change, while removing no call required by Common semantics. That is a distinct
pre-ABI optimization/representation decision rather than a prerequisite for admitting the source.
Both overflow actuals must keep the same shape until such a feature changes declaration, product
metadata, inlined-consumer behavior, and tests atomically. The logical operation, overflow
condition, exception, and message remain exactly Common either way.

`count`, `checkCountOverflow`, and `throwCountOverflow` share
`Kotlin.Collections.CollectionsKt`. The two helpers remain Kotlin-internal compiler ABI rather than
ordinary C# API. No BCL count property participates in Kotlin dispatch.

### Equality search and index overflow

The admitted closure is the exact `Elements` family comprising:

- `Iterable<T>.contains(element)`;
- `Iterable<T>.indexOf(element)` and `Iterable<T>.lastIndexOf(element)`; and
- the non-inline `List<T>.indexOf(element)` and `List<T>.lastIndexOf(element)` overloads.

The List overloads are intentionally present even though List declares matching members. Common
marks them `EXTENSION_SHADOWED_BY_MEMBER` because the extension takes precedence for some generic
calls; omitting them would change overload resolution rather than merely remove duplicate code.
Common's `OnlyInputTypes` type-parameter annotation is preserved by the generator.

The generated bodies determine both traversal and equality direction. `contains` uses
`Collection.contains` when that capability exists and otherwise delegates to the admitted
Iterable `indexOf`. Iterable `indexOf`/`lastIndexOf` use the corresponding List member fast path;
ordinary iterables compare `element == item`, returning the first match or retaining the last
match respectively. They call Common `checkIndexOverflow` before comparing each visited element.
The paired `throwIndexOverflow` helper owns the `ArithmeticException` and exact message.

JVM and Native/Wasm use an `@InlineOnly` index-overflow actual; JS uses an ordinary callable actual
with the same body. The paired reassessment above deliberately retains the ordinary JS-shaped .NET
actual. Iterable `indexOfFirst` and `indexOfLast` therefore prove that an inline body from a
self-describing library can retain a legal call to physical `@PublishedApi` compiler ABI. The List
variants contain no overflow-helper call. Do not let one overflow actual become inline while the
other retains an accidental historical shape.

All five public overloads and both internal helpers share
`Kotlin.Collections.CollectionsKt`; the helpers are marked compiler ABI. Using LINQ or BCL
collection search was rejected: it would substitute CLR comparer and enumeration policy, miss
Common's exact fast-path and overflow boundaries, and conflate optional BCL adapters with Kotlin
collection identity. This equality-search closure alone did not remove the Common abstract-base
blocker; the paired predicate closure below now supplies its separate inline-lambda helpers.

### Predicate quantifiers and index search

The admitted exact Common template closure is:

- `Iterable<T>.any(predicate)`, `all(predicate)`, `none(predicate)`, and
  `count(predicate)` from `Aggregates`; and
- `Iterable<T>.indexOfFirst(predicate)`, `List<T>.indexOfFirst(predicate)`,
  `Iterable<T>.indexOfLast(predicate)`, and `List<T>.indexOfLast(predicate)` from `Elements`.

All eight declarations are ordinary non-reified inline functions. Their public physical methods
remain fallback bodies in `Kotlin.Collections.CollectionsKt`, while Kotlin consumers inline the
serialized Common IR from the self-describing stdlib. No .NET source owns an algorithm body.

The generated algorithms preserve distinctions that a target rewrite could easily erase:

- `all` and `none` are vacuously true and `any` false for an empty Collection without requesting
  an iterator; predicate `count` likewise returns zero through that same fast path;
- all three quantifiers traverse once and stop at the first decisive predicate result, while
  predicate `count` traverses to exhaustion and calls Common `checkCountOverflow` after every
  matching increment;
- Iterable index search checks index overflow at the Common boundary and evaluates in encounter
  order; and
- List `indexOfLast` walks backwards from `listIterator(size)`, while List `indexOfFirst` keeps the
  ordinary forward `for` traversal and neither List overload calls an overflow helper.

The CLR supplies no conflicting representation constraint. LINQ quantifiers/search, BCL
enumeration, a target-authored loop, forced materialization, or a non-inline substitute would
change identity, control flow, predicate/non-local-return behavior, traversal direction, or
physical source ownership. They are therefore rejected. `none(predicate)` is kept as its own
Common declaration rather than target-rewritten to `!any(predicate)`: doing otherwise changes
inline/source ownership and may change callback/non-local-return structure. Cross-DLL tests must
prove both halves of the regular-inline contract: the fallback methods and KLIB bindings exist in
the stdlib, but calls from Kotlin consumers disappear after inlining; only the Iterable index and
predicate-count bodies may retain calls to the ordinary compiler-ABI overflow helpers.

### Accumulator folds

The selected fold closure is the complete collection-facing Common `Aggregates` family:

- `Iterable<T>.fold(initial, operation)`;
- `Iterable<T>.foldIndexed(initial, operation)`;
- `List<T>.foldRight(initial, operation)`; and
- `List<T>.foldRightIndexed(initial, operation)`.

JVM, JS, Wasm, and Native all consume these generated Common bodies; none owns a platform fold
algorithm. Kotlin/.NET therefore selects the same four template variants without a target body or
intrinsic. Array, primitive-array, unsigned-array, string, and sequence variants belong to their
own source-product closures and are not implied by this collection slice.

All four declarations are ordinary non-reified inline functions with fallback methods on
`Kotlin.Collections.CollectionsKt`. `fold` traverses left to right and returns the initial value
without invoking the operation when empty. `foldIndexed` additionally calls the already admitted
Common `checkIndexOverflow(index++)` before every operation. The List pair requests
`listIterator(size)` only for a non-empty list and traverses through `hasPrevious`/`previous`;
`foldRightIndexed` obtains `previousIndex` before the matching element. This pins operation order,
index association, iterator calls, and exception timing to Common.

The dependency closure is already physical: `Iterable`/`List` and their iterators are published,
fixed `Function2` and `Function3` invocation execute, generic method results retain `R`, the
ordinary inliner preserves captures and non-local returns, and `checkIndexOverflow` is callable
compiler ABI. No collection builder, enum, annotation, reflection token, reified operation, or new
CLR carrier is involved.

The cross-library non-local-return gate exposed one ordinary inline cleanup requirement. KLIB IR
stores the generic accumulator as its erased `Any?` slot and recovers the substituted `R` through
an `IMPLICIT_CAST`. If the caller discards the fold result, that recovery is itself in statement
position. Kotlin/.NET now emits and discards precisely that existing cast shape, including its
checked unbox; it does not infer a new cast, optimize failed casts, or broaden the accepted runtime
classifier set.

A LINQ aggregate, indexed loop over arbitrary Iterable, reversed copy, BCL enumerator, target-authored
fallback, or fallback-only non-inline declaration is rejected. Each changes traversal capability,
allocation, user-operation timing, non-local return behavior, or authoritative source ownership
without a CLR representation constraint. Adversarial completion therefore covers empty and
nullable accumulators, primitive/reference/widened elements, exact left/right traces, hostile
iterators, Function3 index association, capture and non-local return, packaged fallback bodies,
separate-consumer inlining, and direct fallback execution on Framework CLR and CoreCLR.

### Receiver-seeded reductions

The selected reduction closure is the complete collection-facing Common family:

- `Iterable<T>.reduce` and `reduceIndexed`;
- `Iterable<T>.reduceOrNull` and `reduceIndexedOrNull`;
- `List<T>.reduceRight` and `reduceRightIndexed`; and
- `List<T>.reduceRightOrNull` and `reduceRightIndexedOrNull`.

JVM, JS, Wasm, and Native consume these same generated Common declarations. Kotlin/.NET therefore
selects the `S, T : S` Iterable/List template variants unchanged. The non-null forms throw the
Common `UnsupportedOperationException` with its exact collection/list message when empty; the
nullable forms return null without invoking the operation. Left variants seed from the first
iterator element and begin indexed callbacks at one. Right variants request `listIterator(size)`,
seed from the last element, and obtain each remaining `previousIndex` before its matching
`previous` value.

The existing representation closes every physical dependency. A CLR method type parameter may
carry the direct `T : S` GenericParamConstraint already used by ordinary target code. Direct open
`S?` results use the accepted boxed-or-null object slot while KLIB retains their logical nullable
type; non-null `S` remains the real CLR method parameter. Split Iterable/List and iterator views,
fixed Function2/Function3 invocation, `checkIndexOverflow`, the classified physical
`UnsupportedOperationException`, and ordinary cross-library inlining are already published. No
builder, annotation-class representation, enum, reflection token, reified operation, or new
carrier is selected here.

The direct erased Function2/Function3 fallback is part of that evidence, not merely the optional
exact callable capability. A foreign or older implementation returns `object`; after that checked
recovery, every initialization or assignment from `T` to the relatively constrained `S` must use
the same verifier-safe `box T; unbox.any S` sequence. Testing only compiler-generated exact
lambdas would hide an invalid object-in-`S` slot for value substitutions.

The production inliner exposes an empty nullable branch as `Nothing?` followed by an
`IMPLICIT_CAST` to the substituted result. JVM and Native retain nullable-bottom subtyping through
their normal post-inline coercions, JS uses its native null value, and Wasm handles nullable
`Nothing` explicitly when adapting the expected type. Kotlin/.NET likewise reuses its existing
nullable-bottom emission: a reference result receives null and a closed nullable scalar receives
the already-selected empty `Nullable<V>` carrier. This is not a runtime cast or a widening of the
accepted classifier set.

Replacing the functions with LINQ `Aggregate`, indexing arbitrary Iterable, a reversed copy, a
target-authored exception path, `T`-only overloads, or object-erased non-null results is rejected.
Those alternatives change bounds, traversal capability, allocation, exception text/timing, or the
useful physical generic signature without a CLR necessity. Adversarial completion must cover
single-element no-callback behavior; empty exception type/message and nullable null; reference,
value, nullable, and widened `S`; exact left/right order and indices; hostile iterators; operation
failure identity and stopping point; non-local return; the physical `T : S` constraint and `S?`
slot; packaged KLIB inlining; and direct fallback execution on both supported CLR families.

### Iteration actions

The selected action closure is exactly `Iterable<T>.forEach` and `forEachIndexed`. JVM, JS, Wasm,
and Native consume the same generated Common declarations: `forEach` invokes the action once for
each iterator element in encounter order, while `forEachIndexed` increments from zero and passes
every index through the Common `checkIndexOverflow` operation before invoking the action with that
index and its already-retrieved element. Both functions are inline and return Unit; no
target-authored loop or BCL enumeration path is selected.

`forEach` retains Common's binary `@kotlin.internal.HidesMembers` compiler directive. Its
annotation class already belongs to the authoritative Common stdlib source closure, and embedded
KLIB preserves the annotation for Kotlin overload resolution. It is not a user runtime annotation
and therefore does not require a CLR custom-attribute representation. A separate consumer with a
hostile same-signature member must still resolve the annotated Common extension, proving the
published KLIB behavior rather than merely the source spelling.

The existing split Iterable/Iterator carrier, Function1/Function2 invocation, Unit carrier,
`checkIndexOverflow`, capture, non-local return, and cross-library inliner close the physical
dependencies. LINQ `ForEach`, a Collection/List shortcut, BCL `IEnumerable<T>`, or a target body is
rejected because each would change receiver admission, traversal or inline control flow without a
CLR necessity. `onEach` and `onEachIndexed` are deliberately not rewritten as convenient variants.
Their exact Common bodies use `apply`; the completed authoritative `Standard.kt`/contracts product
now supplies that dependency, so both functions are unblocked and may form a subsequent exact
generated batch after their full variant and adversarial matrix is selected.

Adversarial completion covers empty and singleton protocols, exact order and zero-based indices,
overflow checking, nullable and value elements, Unit callbacks, mutation/capture, exception
identity and stopping point, non-local return, hostile member resolution through `HidesMembers`,
packaged and installed KLIB inlining, physical fallback signatures, and direct fallback execution
on Framework CLR and CoreCLR.

### First-match predicates

The selected first-match closure is exactly `Iterable<T>.first(predicate)` and
`firstOrNull(predicate)`. JVM, JS, Wasm, and Native consume these same generated Common loops. Each
tests elements in iterator order and returns immediately on the first match. The throwing form owns
the exact Common `NoSuchElementException("Collection contains no element matching the
predicate.")`; the nullable form returns null after complete exhaustion.

The existing split Iterable/Iterator carrier, Function1 invocation, exact physical
`Kotlin.NoSuchElementException`, open type-parameter return, boxed-or-null `T?` fallback,
nullable-bottom emission, capture, non-local return, and packaged inliner close every dependency.
No CLR representation constraint distinguishes this pair from the mature targets. LINQ `First`,
`FirstOrDefault`, BCL enumeration, a Collection/List shortcut, or a target-authored body is
rejected because each can change exception identity/message, null ambiguity, receiver admission,
predicate timing, or non-local control flow.

`find` is not silently folded into this slice even though its Common body delegates to
`firstOrNull(predicate)`: it is an `@InlineOnly` declaration, so physical declaration suppression
and the published .NET ABI must be audited together before admission. Adversarial completion for
the selected pair covers empty and no-match behavior, exact exception text, first-match
short-circuiting before hostile trailing elements, nullable matching versus no match, widened
elements, predicate exception identity and stopping point, capture, non-local return, separate and
installed KLIB inlining, physical fallback signatures, and direct fallback execution on Framework
CLR and CoreCLR.

### Last-match predicates

The selected last-match closure is the complete Common collection-facing pair:

- `Iterable<T>.last(predicate)` and `lastOrNull(predicate)`; and
- the corresponding `List<T>` overloads.

JVM, JS, Wasm, and Native consume these same generated declarations. The Iterable bodies traverse
forward to exhaustion and retain the last matching value. The non-null form uses a separate
`found` flag, then performs Common's suppressed unchecked `last as T`; this is what distinguishes a
found nullable null from absence. The List bodies request `listIterator(size)` and traverse
backward, returning the first reverse match. Throwing forms own the exact Common
`NoSuchElementException`: the Iterable form says `Collection contains no element matching the
predicate.`, while the List form says `List contains no element matching the predicate.` Nullable
forms return null.

The existing Iterable/List/ListIterator carriers, Function1 invocation, exact physical exception,
open-type-parameter cast barrier, boxed-or-null `T?` fallback, nullable-bottom handling, and
cross-library inliner close the representation. In particular, the physical Iterable fallback for
`T = Int` may retain its object-backed nullable accumulator and recover the successful result with
the existing checked `unbox.any !!0`; this is Common's cast, not permission to optimize a failed
cast. LINQ `Last`/`LastOrDefault`, reverse copies, indexing arbitrary Iterable, or one uniform
forward loop are rejected because they change traversal capability, predicate order/timing,
exception identity, or nullable-match semantics without a CLR necessity.

`findLast` remains outside this slice because its delegating declaration is `@InlineOnly` and shares
the separate physical-suppression audit with `find`. Adversarial completion covers empty/no-match
message and null, Iterable full traversal, List reverse short-circuit and exact iterator protocol,
nullable and widened matches, value-type cast recovery, predicate failure identity/stopping,
capture, non-local return, separate/installed inlining, all four physical signatures, and direct
fallback execution on Framework CLR and CoreCLR.

### Single-match predicates

The selected single-match closure is exactly Common's two `Iterable<T>` declarations:

- `single(predicate)`; and
- `singleOrNull(predicate)`.

JVM, JS, Wasm, and Native consume these same generated declarations; Common does not provide a
separate List predicate overload. Both bodies traverse in encounter order, remember the first
match with a distinct `found` flag, and stop as soon as a second match proves that uniqueness is
impossible. `single` throws the exact Common `NoSuchElementException` when there is no match and
the exact Common `IllegalArgumentException` at the second match. `singleOrNull` returns null for
both cases. A single matching nullable null remains a successful value, not absence.

The existing Iterable carrier, Function1 invocation, exception mappings, object-backed `T?`
fallback, open-type-parameter cast barrier, nullable-bottom handling, and cross-library inliner
close the representation. A LINQ `Single`/`SingleOrDefault` substitution is rejected: CLR default
values cannot distinguish nullable success, no match, and multiple matches, and foreign exception
types/messages would replace Common semantics. Full traversal after a second match is also
rejected because Common has already fixed predicate timing and failure visibility. Completion must
cover zero, one, and multiple matches; the precise second-match stopping point; nullable and
widened values; exact exception identity/messages; predicate failure identity; capture and
non-local return; separate and installed KLIB inlining; both physical signatures; and direct
fallback execution on Framework CLR and CoreCLR.

### Signed numeric sum

The admitted numeric closure is the complete signed Common `Numeric.f_sum` family for `Iterable`:

- `Iterable<Byte>.sum()` and `Iterable<Short>.sum()` return `Int`;
- `Iterable<Int>.sum()` returns `Int`;
- `Iterable<Long>.sum()` returns `Long`;
- `Iterable<Float>.sum()` returns `Float`; and
- `Iterable<Double>.sum()` returns `Double`.

The bootstrap generator selects these six `PrimitiveType.numericPrimitives` variants from the
same template object that generates `_Collections.kt`. Unsigned variants remain excluded because
their scalar/runtime product has not yet been admitted on top of the single-field value-class
foundation; `Sequence`, object-array,
primitive-array, and `sumOf` variants remain separate dependency closures. The signed Iterable
average closure is selected independently below. No body is copied or rewritten for .NET.

Each Common body initializes the result type's zero, requests one iterator, and performs
`sum += element` in encounter order. Consequently empty identity, `Byte`/`Short` promotion to
`Int`, signed integer wraparound, Float rounding after every addition, Double IEEE behavior, and
exception/side-effect order come directly from Common plus the already completed scalar
semantics. There is no Collection/List or BCL fast path. LINQ `Sum`, `IEnumerable<T>`, checked
addition, pairwise/vector reduction, and target intrinsics are rejected because each would change
identity, overflow, rounding, traversal, or exception policy without a CLR representation need.

The canonical CLR receiver of all six logical declarations is deliberately the same non-generic
`Kotlin.Collections.Iterable`. Replacing it with `Iterable<T>` would break Kotlin value/open
covariance and canonical-only providers. The CLR therefore has the same overload-erasure problem
that the Common generator already records through its platform names. The physical stdlib methods
use the generator-supplied `sumOfByte`, `sumOfShort`, `sumOfInt`, `sumOfLong`, `sumOfFloat`, and
`sumOfDouble` spellings, while KLIB continues to expose six logical functions named `sum` and the
self-describing library manifest binds each logical declaration to its physical method. This
bounded projection is an exact backend table keyed by the six logical element types and pinned to
the Common generator's platform-name intent. It applies only to compiler-owned stdlib
implementations; it does not reinterpret arbitrary user `@JvmName` annotations as a .NET API.

The bootstrap source product includes the authoritative `Multiplatform.kt` and
`JvmAnnotationsH.kt` Common headers needed to resolve that optional expectation. As on mature
non-JVM targets, an optional JVM annotation with no platform actual is erased rather than emitted
as a runtime annotation class. Compiler-owned stdlib source products opt in to
`ExperimentalMultiplatform`, matching the Common stdlib build contract, without granting the
opt-in to ordinary user compilations. These headers are resolution-only compiler input; admitting
them does not claim general annotation-class or reflection support.

JVM uses these same spellings because its erased receiver signatures also collide. JS, Wasm, and
Native compile the same generated bodies but their symbol/overload representations do not require
the CLR/JVM physical method-name projection. A hash suffix, overload-set-dependent renaming,
duplicated C# wrapper, or a target-authored `sum` implementation would be less stable or less
interoperable than consuming the authoritative explicit platform names.

### Signed numeric average

The admitted average closure is the complete signed Common `Numeric.f_average` family for
`Iterable`: Byte, Short, Int, Long, Float, and Double receivers all return `Double`. JVM, JS,
Wasm, and Native consume these same generated bodies. Unsigned values remain excluded with their
separate scalar/runtime publication; Sequence, object-array, and primitive-array variants remain
distinct source and representation closures.

Every body accumulates into `Double` in encounter order, increments an `Int` count through Common
`checkCountOverflow`, and returns `Double.NaN` when the receiver is empty or `sum / count`
otherwise. Existing scalar conversions/arithmetic, the Iterable carrier, the exact NaN constant,
and the ordinary count-overflow compiler ABI close the implementation. LINQ `Average`, checked or
wider counters, pairwise/vector summation, host enumeration, and target-authored empty handling are
rejected because they change overflow, rounding, traversal, exception timing, or source ownership
without a CLR requirement.

The six physical methods use Common's platform names `averageOfByte`, `averageOfShort`,
`averageOfInt`, `averageOfLong`, `averageOfFloat`, and `averageOfDouble`, while embedded KLIB keeps
the logical overload name `average`. Completion must pin empty NaN, all six conversions, encounter-
order floating behavior, full traversal and failure identity, physical signatures, portable
library consumption, and direct fallback execution on Framework CLR and CoreCLR.

### Proven-frontier generator batch

After the repeated element, predicate, fold, reduction, sum, and average closures, historical
micro-slice size is no longer a reason to select one or two Common declarations at a time. The
next source-product batch is therefore the complete still-missing frontier whose dependencies are
already represented: `Iterable.sumBy`, `Iterable.sumByDouble`, and `requireNoNulls` for `Iterable`
and `List`. JVM, JS, Wasm, and Native consume these same Common generator templates; .NET does not
own substitute loops.

The selector sums reuse the proven inline lambda, encounter-order Iterable, Int overflow, Double
arithmetic/NaN, capture, and non-local-return paths. Their Common `Deprecated`, `ReplaceWith`, and
`DeprecatedSinceKotlin` records are compiler-recognized Kotlin metadata contracts already present
in the built-ins; selecting them neither defines a new annotation class nor infers semantics from
CLR attributes. Their physical fallbacks retain the distinct logical names `sumBy` and
`sumByDouble`, so no erased overload-name codec is needed.

Both null guards traverse the exact receiver, stop at the first null with Common's message, and
otherwise return the same object under the strengthened `T : Any` view. That is a logical erased-
generic cast over the existing canonical Iterable/List identities, not a typed-capability probe,
wrapper, copy, or permission to optimize a later failed element use. The List overload must not
silently switch to indexed access: Common specifies iteration for both overloads.

The audit deliberately excludes the rest of the nearby generator frontier:

- `elementAtOrElse`, `getOrElse`, and therefore general `elementAt` reach the public contracts DSL;
- the Long and Double `sumOf` overloads were excluded from that batch until their parameterless
  `ExperimentalTypeInference` and `OverloadResolutionByLambdaReturnType` marker sources and erased
  physical overloads could land together; the subsequent signed-selector-sum tranche below
  completes them, while the unsigned overloads still require the unsigned
  scalar/runtime and generated-stdlib product;
- mapping, filtering, snapshot, running-fold, and running-reduce families construct collection
  implementations that do not yet exist;
- the completed Comparator/selection foundation owns Common comparison
  combinators, comparator `minOf`/`maxOf`, Iterable comparator selection, and
  non-mutating `isSorted*`; the completed stable sorting closure reuses the
  Native/Wasm stable list/generic-array and signed-wrapper quicksort lineages
  and admits the complete dependency-closed eager/range/reverse/snapshot
  ordering consumers, as recorded in
  [`../decisions/stable-list-and-array-sorting.md`](../decisions/stable-list-and-array-sorting.md),
  while unsigned arrays/ranges remain separate; Sequence sorting subsequently
  landed in the completed foundation above;
  the Comparator foundation itself is recorded in
  [`../decisions/comparator-and-selection-foundation.md`](../decisions/comparator-and-selection-foundation.md);
- the complete supported-classifier `allEqual`/`allEqualBy` and
  `allDistinct`/`allDistinctBy` families have since landed as the independent
  closures above, with the signed Byte fast path retaining the shared
  carrier-neutral byte-domain bit set;
- `onEach` reaches `apply` and the public contracts DSL; and
- random, unsigned, array, Set, and Map variants retain their separate dependency and
  representation closures; Sequence variants subsequently landed in the completed foundation above.

The completed gate proves empty and overflowing selector sums, Double rounding and NaN, nullable and
widened elements, capture/non-local return, callback failure identity and timing, same-object null
guard success, exact first-null stopping and message, hostile iterator behavior, both physical
guard overloads, packaged inlining versus fallback calls, and direct execution on Framework CLR
and CoreCLR.

### Inline-only delegator and accessor batch

The accepted [`@InlineOnly` physical ABI decision](../decisions/inline-only-physical-abi.md)
unlocks one dependency-homogeneous Common batch. The bootstrap generator selects exactly:

- `List.component1` through `component5`;
- `List.elementAt` and `List.elementAtOrNull`;
- `Iterable.find`;
- `findLast` for `Iterable` and `List`;
- `firstNotNullOf` and `firstNotNullOfOrNull` for `Iterable`;
- `Collection.count`; and
- `Iterable.asIterable`.

These are 14 logical declarations from the authoritative `Elements`, `Aggregates`, and
`SequenceOps` Common generator groups. JVM, JS, Wasm, and Native consume the same generated
declarations. The .NET generator selects family variants; it does not copy bodies, remove
annotations, or invent target overloads.

Every dependency is already represented. Components and List `elementAt` call `get`; the List
`elementAtOrNull` specialization calls the existing `getOrNull`; `find` and both `findLast` views
delegate to the already selected predicate terminals; the first-non-null pair reuses ordinary
inlining, `R : Any`, nullable flow, Elvis, and the Common exception; Collection `count` reads
`size`; and `asIterable` returns the same Iterable object. General Iterable `elementAt` remains
excluded because its body reaches contract-bearing `elementAtOrElse`; selecting only the List
family is the exact Common specialization, not a target source fork.

All 14 physical methods are assembly-visible. Their logical declarations remain public in KLIB,
and same-module, separate, and installed Kotlin calls must inline them. A public physical fallback,
EditorBrowsable-only hiding, body eviction, or C# wrapper under the same declaration identity would
contradict Common and JVM without a CLR requirement. No overload in this batch collides after CLR
receiver erasure; `sumOf` remains separate because it does.

Completion must prove component indices and failures; List direct indexing without iteration;
Iterable first-match and full last-match traversal; List reverse `listIterator(size)` traversal;
nullable success, no-result, exact first-non-null failure message, callback failure identity and
non-local return; Collection size access without iteration; `asIterable` identity; physical
assembly visibility and C# inaccessibility; absence of external calls in every KLIB inliner mode;
and execution through repository, packaged, installed, netstandard, Framework CLR, and CoreCLR
products.

The bootstrap box harness may exercise the ordinary public Iterable and `getOrNull` paths, but is
not evidence for the List `@InlineOnly` overloads: it analyzes stdlib and test sources as one
frontend module and only later emits two physical assemblies. The portable self-describing-stdlib
test is the authoritative adversarial execution route for List `elementAtOrNull`, including direct
indexing, bounds, and propagated `get` exception identity, because it compiles the consumer from
the producer's embedded KLIB exactly as an external Kotlin user does.

### Completed signed selector sums

The completed signed selector-sum frontier is exactly the Common `Int`-, `Long`-, and
`Double`-selector overloads of `Iterable<T>.sumOf`. Their authoritative
`Aggregates.f_sumOf` bodies start from `0.toInt()`, `0.toLong()`, and `0.toDouble()`, traverse the
receiver once in encounter order, invoke the selector once per element, and use ordinary wrapping
`Int`/`Long` or encounter-ordered IEEE `Double` addition. JVM compiles the same three Common
declarations and uses
their explicit `sumOfInt`, `sumOfLong`, and `sumOfDouble` platform spellings because erased
function types cannot distinguish selector return types. JS, Wasm, and Native retain the same
logical declarations and bodies without publishing a separately callable CLR-style fallback.

.NET keeps logical `sumOf` in KLIB and uses the same Common-supplied `sumOfInt`, `sumOfLong`, and
`sumOfDouble` spellings for the assembly-visible physical bodies. Each selector overload may share
that name with the existing one-parameter numeric `sum()` fallback because the selector adds a
distinct CLR `Function1` parameter; it may not collide with another selector overload. The mapping
is keyed by the exact logical selector-result/return type and is recorded in the self-describing
physical binding. Naming any MethodDef `sumOf`, overloading selector functions only by their CLR
return type, deriving a hash from the current overload set, omitting the physical body, or widening
it for C# are rejected: each either produces invalid/unstable CLR metadata or contradicts the
accepted inline-only contract without a CLR necessity.

The `Long` and `Double` declarations carry `@OptIn(ExperimentalTypeInference::class)` and
`@OverloadResolutionByLambdaReturnType`. The general marker foundation now admits those exact
parameterless annotation declarations without removing their KLIB metadata. The temporary source
product therefore projects the complete authoritative Common `ExperimentalTypeInference` source
and the exact final Common `OverloadResolutionByLambdaReturnType` declaration; it does not invent
target-local marker stubs or pull the adjacent deprecated `BuilderInference` declaration into this
closure. Both extraction markers fail generation if upstream moves or reshapes either declaration.
Source overload resolution must then select all three signed functions from the lambda result.
This is not permission to select the complete generator group: `UInt` and `ULong` still require
the parked unsigned value-class family, while JVM-only big-number overloads are not Common .NET
declarations.

The completed gate proves empty zero for all three overloads, `Int` and `Long` wrapping overflow,
`Double` encounter order and NaN, nullable and widened receiver elements, callback order and
capture, callback failure identity and stopping point, non-local return, lambda-result overload
resolution, assembly visibility under all three stable names, logical KLIB identity under
`sumOf`, no external call from separate and installed Kotlin consumers, C# inaccessibility, and
execution through the portable stdlib on Framework CLR and CoreCLR.

### Completed same-receiver observation

The closed Common frontier is exactly `Iterable.onEach` and `Iterable.onEachIndexed`. The
authoritative `Aggregates` templates return `C` from a receiver `C : Iterable<T>`: `onEach` uses
`apply` around the encounter-order loop, while `onEachIndexed` uses `apply` around the already
selected Common `forEachIndexed` operation. The contracts and scope-function product now supplies
the exact `apply` declaration and calls-in-place effect that previously kept this pair outside the
bootstrap product.

JVM, JS, Wasm, and Native compile these same Common declarations. The CLR creates no reason to
replace `apply`, duplicate either loop, map the call to a BCL enumeration helper, or weaken the
return to `Iterable<T>`. The method-owned `C` remains a truthful CLR generic method parameter;
the Kotlin-owned `Iterable<T>` upper bound uses its accepted erased interface carrier while KLIB
retains the complete logical relationship. This is method-generic capability above the canonical
Kotlin object model, not a reintroduction of a CLR-generic Kotlin-owned class or interface.

The critical semantic requirement is same-receiver preservation. A custom subtype returned from
either operation must retain its logical type and the identical runtime object, with all mutations
performed by the callback visible through that object. Both functions traverse once in encounter
order. The indexed form starts at zero and inherits `forEachIndexed`'s exact
`checkIndexOverflow(index++)` behavior. Iterator or callback failures propagate unchanged at the
point Common evaluates them; a non-local return from the inline callback exits the caller and does
not manufacture a returned receiver.

The public physical fallbacks remain ordinary inline MethodDefs rather than `@InlineOnly`
compiler ABI. Separate and installed Kotlin consumers must nevertheless inline the authoritative
KLIB body when possible, while direct CLR fallback calls must preserve the same generic return and
runtime behavior. Returning only the erased Iterable carrier, wrapping or copying a receiver,
calling `forEach` instead of the indexed Common dependency, or adding a target-specific fast path
is rejected: each either loses `C`, changes traversal/failure behavior, or forks Common without a
CLR constraint.

Completion must prove empty, singleton, nullable and widened elements; exact callback/index order;
custom-subtype static return and same-object identity; mutation visibility; iterator and callback
failure identity, timing and stopping; non-local return; one public physical fallback for each
logical declaration; complete KLIB bodies and no external call from separate and installed Kotlin
consumers; direct fallback execution; and Framework CLR/CoreCLR coverage through the portable
stdlib products.

The completed implementation passes that matrix. It also closes the general compiler boundary
that the Common `apply` body exposed: a method parameter `C : Iterable<T>` may be viewed as the one
erased `Iterable` carrier while executing the body, then recovered as the same open `C` with CLR
`unbox.any C`. The recovery is proven by the method's physical erased-interface constraint and the
frontend `IMPLICIT_CAST`; it neither weakens the logical return to `Iterable` nor introduces a
closed CLR generic interface.

## Next selection rule

Reuse an upstream compiler box test directly whenever its complete dependency closure is already
admitted. Do not copy bodies from `libraries/stdlib/test/generated`: those generated tests depend
on the common `kotlin.test` runner and broader collection constructors/product. Wire their source
directories into a real .NET stdlib-test product once those dependencies exist; until then,
target-owned tests cover only portable packaging, CIL, CLR profiles, and other .NET-specific
boundaries rather than pretending to be the authoritative Common suite.

Select the next exact Common/generated family only when all of these are closed:

1. every source declaration and generator variant is identified from an authoritative owner;
2. every called Common helper and expect/actual dependency is supported;
3. its types and calls have truthful same- and cross-module CLR representations;
4. the direct, packaged fallback, installed, and portable stdlib products use the same source;
5. no public or protected declaration is evicted; and
6. adversarial behavior can be executed on both runtime profiles.

Do not use allowlist size as the unit of progress. Close a reusable language,
runtime, collection, importer, or test-product foundation first; inventory
every Common generator family whose only blocker that foundation removes; and
admit that complete dependency-homogeneous set in the same tranche. If one
candidate reveals another independent language or representation decision, it
stays out with that exact blocker recorded rather than shrinking the
foundation or receiving a target workaround.

Each foundation tranche must test its Kotlin-to-Kotlin separate-product ABI
and the truthful C# boundary, including negative interop claims. Once its
source closure can compile the relevant shared stdlib tests, wire those tests
through a real .NET stdlib-test product in the same programme. Target-owned
tests remain responsible for CIL, CLR profiles, physical ABI, foreign
metadata, packaging, and other genuinely .NET-specific evidence.

Do not choose a family solely because one downstream feature, such as enums, needs it.

## Implemented abstract collection/list foundation

All mature targets compile shared `AbstractCollection.kt` and `AbstractList.kt`; the current
Kotlin/.NET worktree can compile them too. Their `joinToString` and
`CharSequence`/`Appendable`/`StringBuilder` closure is present in the same stdlib product, without
declaration eviction, copied algorithms, or BCL collection substitution. The shared erased-class
matrix now covers widened `containsAll`, same-object mutation, portable overrides, and the private
Common helper classes without a collection-specific workaround.

### String-building prerequisite

The first part of this prerequisite is the accepted
[`CharSequence` classified-carrier decision](../decisions/char-sequence-carrier.md). JVM can reuse
a host interface already implemented by its string; JavaScript supplies the closer precedent by
classifying host strings beside Kotlin interface implementations and rewriting the three logical
operations. CLR has the same constraint because `System.String` is sealed.

The carrier has landed and passed its representation gate. The selected
[`Appendable`/`StringBuilder` decision](../decisions/appendable-string-builder.md) keeps both public
identities Kotlin-owned and uses `System.Text.StringBuilder` only as private storage. Raw BCL
builders do not become a third `CharSequence` classifier arm.

The deeper source audit found a precise staging cut that the earlier cluster analysis missed. The
complete Common `StringBuilder` expect class and its non-contract top-level extensions did not
depend on contracts; only the two top-level `buildString` declarations called `apply` and the
contracts DSL. That fail-closed projection made the first builder phase possible without copying
or rewriting a body. The contracts phase has now removed the cut: the product contains the whole
Common file, including both exact `buildString` declarations, and KLIB and physical stdlib remain
equal.

The completed first phase combines that builder surface with exact generated `Iterable.joinTo` and
`Iterable.joinToString`, Common `AbstractCollection` and `AbstractList`, and the migrated
`ArrayAsList` representation. The deprecated CharArray append extension brings exact Common
`NotImplementedError`; it is not a reason to admit `Standard.kt` or contracts early.

Modern enums and the non-reified `EnumEntries` core are complete. They consume the completed
general Comparable representation and Common abstract-list substrate. A Kotlin enum is a
Kotlin-owned reference class with static entry fields, not a CLR value-type enum. The later
completed reified tranche publishes Common `enumEntries`, `enumValues`, and `enumValueOf` by
substituting the enum class and binding those same synthetic members.

The third phase published the exact Common contracts DSL/effects, `Standard.kt` through
`takeUnless`, and both `buildString` declarations. The completed range phase subsequently admitted
the final exact `repeat` declaration over real `Int.until`, so `Standard.kt` is no longer
projected. Common's `returnsResultOf` effect remains authoritative in KLIB even though it has no
exact Roslyn CodeAnalysis representation. A one-enum exception, target-authored `EnumEntries`,
contract shim, rewritten `buildString`, or temporarily broader KLIB remains rejected.

### Typed collection-to-array prerequisite

The completed [runtime-typed allocation decision](../decisions/collection-to-array.md) keeps both
loops in exact Common source. The .NET actual supplies only the CLR-specific allocation needed to
reproduce the reference array's runtime vector element type and a non-Java termination policy.
This is independently implementable with ordinary generic functions: public reified
`Collection<T>.toTypedArray()` was deliberately parked until the reified-inline programme.

JVM is the closest physical precedent because both JVM and CLR arrays carry runtime component
identity and permit reference-array covariance. JS, Native, and Wasm remain the algorithmic
precedent for delegating to `collectionToArrayCommonImpl` and, unlike JVM's Java interop contract,
performing no tail null-termination. A static-token `T[]`, erased `object[]`, LINQ loop, or copied
target algorithm is rejected.

The source product extracts the exact Common expects and both complete Common loops. Its
target actuals preserve empty/reuse/iteration/store behavior, while one declaration-suppressing
intrinsic reproduces the supplied CLR vector's runtime element type. The Common `as T` store path
uses the CLR's open `unbox.any !!T` operation for both value and reference instantiations. Public
reified `toTypedArray` now composes this prerequisite with shared call-site substitution; no
second loop or collection-specific type-token path was added.

## Programme order

1. **Completed:** actualize the Common `Appendable`/`StringBuilder` class layer and non-contract
   extensions, generate exact `joinTo`/`joinToString`, compile Common `AbstractCollection` and
   `AbstractList`, and migrate the private direct array-list view over the erased class ABI.
2. **Completed:** add ordinary Kotlin enums plus the non-reified `EnumEntries` core over that substrate, with
   producer-recorded entry-field binding and no CLR value-type enum identity.
3. **Completed:** publish exact contracts, the initially dependency-closed `Standard.kt`
   projection, and both `buildString` declarations once `InvocationKind` exists.
4. **Completed:** add mutable collection/list contracts and an ordinary implementation.
5. **Completed:** add sets and maps from their exact Common dependency closures.
6. **Completed:** compile the ordinary signed ranges/progressions and primitive-iterator
   foundation, replace private counted-loop markers with the shared loop lowering, and admit the
   exact Common declarations this substrate releases, including the final `repeat`. See
   [the range/progression decision](../decisions/ordinary-ranges-and-progressions.md).
7. **Completed:** publish the dependency-closed reified collection/array operations whose ordinary
   carriers are already selected, including `filterIsInstance`, `orEmpty`, and `toTypedArray`.
8. **Completed:** publish the Kotlin-owned Sequence foundation, exact Common builders and sliding
   windows, and every generated Sequence member outside the remaining Random/unsigned partition.
9. **Completed:** publish the complete Common Grouping aggregate source and all four generated
   factories over admitted carriers without adding BCL grouping/enumeration identity.
10. **Completed:** publish all four eager generated Iterable window/chunk variants with the exact
    Common sized-list factory pair and both RandomAccess and iterator/RingBuffer routes.
11. **Completed:** publish all seven eager Iterable/Sequence-consumer variants with stable
    logical-selector-derived collision names and direct Kotlin/C# product evidence.
12. **Completed:** publish `allEqual` and `allEqualBy` for every supported
    Iterable/object-array/signed-primitive-array classifier, with the exact
    selector and floating equality semantics and direct Kotlin/C# evidence.
13. **Completed:** publish `allDistinct` and `allDistinctBy` over the same
     supported classifiers, after removing the internal byte-domain helper's
     accidental public-UByte dependency without changing its algorithm.
14. **Completed:** publish the complete natural `min`/`max` family over generic,
    Float, and Double Iterable/object-array receivers and all seven naturally
    ordered signed primitive-array wrappers, with deterministic bounded CLR
    names for return-only collisions.
15. **Completed:** publish `minBy`/`maxBy` throwing and nullable forms over
    Iterable, object arrays, and all eight signed primitive arrays, including
    the loop-entry stack-baseline fix required by their inlined local returns.
16. **Completed:** publish all 120 generic/Float/Double selector-result
    `minOf`/`maxOf` throwing and nullable forms over Iterable, object arrays,
    and all eight signed primitive arrays, including nullable generic-result
    substitution recovery.
17. **Completed:** publish the remaining 72 object-/primitive-array comparator
    declarations, completing all eight `minWith`/`maxWith` and
    `minOfWith`/`maxOfWith` forms over the ten supported receivers.
18. **Completed:** publish all 28 CharSequence min/max aggregate forms plus the
    exact Common `CharSequence.lastIndex` prerequisite on StringsKt, preserving
    both String and custom-capability classifier arms.
19. **Completed:** publish all 24 Map min/max adapters on MapsKt, retaining
    Common inline-only visibility and exact delegation through `entries`.
20. After the generic-owner rehearsal checkpoint, add explicitly selected BCL
    adapters and C# conveniences without changing Kotlin identity.
21. Remove the bootstrap allowlist when the complete generated product is supportable.

## Alternatives rejected

- **Map Kotlin collections directly to BCL interfaces.** This loses Kotlin iterator and value-type
  covariance semantics or changes identity through wrappers.
- **Compile the full corpus immediately.** Publication cannot silently omit unsupported members.
- **Continue handwritten algorithm extraction.** It makes upstream Common changes advisory.
- **Implement enum-only collection pieces first.** It distorts a public foundation around one
  consumer.
- **Add `IEnumerable<T>` while adding Common functions.** That is a separate ABI/export decision.
- **Use target intrinsics for blocked Common helpers.** The CLR supplies no semantic need to fork
  those algorithms.
- **Map numeric sum to LINQ or a BCL collection interface.** That changes Kotlin collection
  identity and can change overflow, Float rounding, traversal, and exception order.
- **Give each sum receiver an exact generic CLR interface.** Ordinary Kotlin parameters must retain
  canonical identity so value/open covariance and canonical-only implementations keep working.
- **Give general .NET meaning to `@JvmName`.** The admitted sum names are a bounded consumption of
  the Common generator's explicit platform-name records for compiler-owned stdlib declarations,
  not a new user annotation contract.
- **Retain or invent an annotation solely to tunnel the sum names into IR.** `@JvmName` is
  correctly erased on non-JVM targets; a target annotation would broaden the parked annotation
  programme for information that one exact compiler-owned projection can represent.

## Adversarial completion gates

Every admitted family must prove:

- empty, singleton, multiple, nullable, primitive, reference, and widened cases;
- hostile Iterator/Collection/List implementations and exact dispatch/visit counts;
- Common exception type, message, equality, hash, and rendering behavior where applicable;
- aliasing, subview identity, and mutation visibility for backed views;
- reference projection without copying and value projection rejection;
- direct, fallback, installed, separate-consumer, and portable-product binding;
- execution on Framework CLR and CoreCLR through every compatible profile pairing;
- one physical facade with no duplicate body or unbindable KLIB record; and
- absence of accidental BCL interface identity unless an interop ADR explicitly adds it.

The programme is complete when the normal Common/generated collection product can be compiled
without an allowlist or target algorithm forks, and BCL adapters remain a distinct optional
surface.
