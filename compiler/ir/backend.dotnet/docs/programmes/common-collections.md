# Common collections programme

- Status: **Active — runtime-typed array closure admitted; builder language prerequisites next**
- ABI foundation: [`../decisions/draft-adr-generic-interface-abi.md`](../decisions/draft-adr-generic-interface-abi.md)

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

The .NET canonical, declared, and exact CLR interfaces are physical capabilities of one Kotlin
object. They are not separate Kotlin collections.

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

Generated and target-private collection shards explicitly share
`Kotlin.Collections.CollectionsKt`. Callable/accessor-only shards may aggregate into that one
physical facade. At most one shard may own top-level physical state and its initializer; multiple
state owners require a separately designed initialization order and are rejected meanwhile.

## Current admitted product

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

Until Common `AbstractList` and its complete closure are available, the private view implements the
current List surface directly, including backed sublists, iterators, and structural
equality/hash/text. This is bounded representation code, not a target copy of a Common algorithm
family, and should disappear when the shared abstract base becomes available.

The view implements no BCL collection interface. Reusing a BCL wrapper would still not implement
the Kotlin canonical/declared/exact interfaces and would add another identity layer.

### Output-projected generic arrays

`Array<out E>` retains the physical `E[]` vector and KLIB projection metadata. CLR reference-vector
covariance can represent `Array<Derived> -> Array<out Base>` without copying. CLR value vectors are
invariant, so `Array<Int> -> Array<out Any>` is rejected rather than boxed, copied, or emitted as
invalid IL. `Array<Int>.asList()` remains supported at exact element type and retains its `int32[]`.

Input and star projections need their own truthful carrier rules and remain outside this product
boundary.

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

- `Iterable<T>.any(predicate)` and `Iterable<T>.all(predicate)` from `Aggregates`; and
- `Iterable<T>.indexOfFirst(predicate)`, `List<T>.indexOfFirst(predicate)`,
  `Iterable<T>.indexOfLast(predicate)`, and `List<T>.indexOfLast(predicate)` from `Elements`.

All six declarations are ordinary non-reified inline functions. Their public physical methods
remain fallback bodies in `Kotlin.Collections.CollectionsKt`, while Kotlin consumers inline the
serialized Common IR from the self-describing stdlib. No .NET source owns an algorithm body.

The generated algorithms preserve distinctions that a target rewrite could easily erase:

- `all` is vacuously true and `any` false for an empty Collection without requesting an iterator;
- both quantifiers traverse once and stop at the first decisive predicate result;
- Iterable index search checks index overflow at the Common boundary and evaluates in encounter
  order; and
- List `indexOfLast` walks backwards from `listIterator(size)`, while List `indexOfFirst` keeps the
  ordinary forward `for` traversal and neither List overload calls an overflow helper.

The CLR supplies no conflicting representation constraint. LINQ quantifiers/search, BCL
enumeration, a target-authored loop, forced materialization, or a non-inline substitute would
change identity, control flow, predicate/non-local-return behavior, traversal direction, or
physical source ownership. They are therefore rejected. Cross-DLL tests must prove both halves of
the regular-inline contract: the fallback methods and KLIB bindings exist in the stdlib, but calls
from Kotlin consumers disappear after inlining; only the Iterable index bodies may retain calls to
the ordinary compiler-ABI overflow helper.

### Signed numeric sum

The admitted numeric closure is the complete signed Common `Numeric.f_sum` family for `Iterable`:

- `Iterable<Byte>.sum()` and `Iterable<Short>.sum()` return `Int`;
- `Iterable<Int>.sum()` returns `Int`;
- `Iterable<Long>.sum()` returns `Long`;
- `Iterable<Float>.sum()` returns `Float`; and
- `Iterable<Double>.sum()` returns `Double`.

The bootstrap generator selects these six `PrimitiveType.numericPrimitives` variants from the
same template object that generates `_Collections.kt`. Unsigned variants remain excluded because
unsigned value classes are not a supported scalar family; `Sequence`, object-array,
primitive-array, `sumOf`, and `average` variants are separate dependency closures. No body is
copied or rewritten for .NET.

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

## Next selection rule

Select the next exact Common/generated family only when all of these are closed:

1. every source declaration and generator variant is identified from an authoritative owner;
2. every called Common helper and expect/actual dependency is supported;
3. its types and calls have truthful same- and cross-module CLR representations;
4. the direct, packaged fallback, installed, and portable stdlib products use the same source;
5. no public or protected declaration is evicted; and
6. adversarial behavior can be executed on both runtime profiles.

Do not choose a family solely because one downstream feature, such as enums, needs it.

## Abstract collection/list blockers

All mature targets compile the shared `AbstractCollection.kt` and `AbstractList.kt`. Kotlin/.NET
must do the same once their exact closure exists. The remaining source-product blockers are:

- `joinToString` and its `CharSequence`/`Appendable`/`StringBuilder` closure.

Importing the abstract bases early would require declaration eviction, copied Common algorithms,
or unjustified .NET intrinsics. All three are rejected. The private direct List view remains until
these prerequisites are genuinely supported.

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

The source audit exposed a prerequisite that a partial builder must not hide: Common
`StringBuilder.kt` and its exact `Standard.kt` dependency use the public `kotlin.contracts` source
family. That family includes public effect interfaces, annotation classes, and the
`InvocationKind` enum. The target cannot publish those KLIB declarations while omitting their
physical product, and it cannot fake just enough contract declarations for builder compilation.
Complete enum and annotation-class representation decisions therefore precede builder
actualization. The independent typed collection-to-array prerequisite has now landed while that
language foundation remains parked.

Modern enum support cannot be used as a small cycle breaker. Every enum publishes `entries`, whose
authoritative `EnumEntriesList` extends Common `AbstractList`; that abstract base needs this same
builder closure. Conversely, the builder's contract source publishes the `InvocationKind` enum.
The target must therefore admit enums, annotation classes, contracts, the builder, the abstract
bases, and `EnumEntries` as one coherent bootstrap cluster unless upstream Common creates a smaller
truthful closure. A one-enum exception, target-authored `EnumEntries` implementation, disabled
`entries` member, or temporarily broader KLIB is rejected. Reversible compiler work should proceed
while this atomic cluster is being designed.

### Typed collection-to-array prerequisite

The completed [runtime-typed allocation decision](../decisions/collection-to-array.md) keeps both
loops in exact Common source. The .NET actual supplies only the CLR-specific allocation needed to
reproduce the reference array's runtime vector element type and a non-Java termination policy.
This is independently implementable with ordinary generic functions: public reified
`Collection<T>.toTypedArray()` remains parked with the reified-inline programme.

JVM is the closest physical precedent because both JVM and CLR arrays carry runtime component
identity and permit reference-array covariance. JS, Native, and Wasm remain the algorithmic
precedent for delegating to `collectionToArrayCommonImpl` and, unlike JVM's Java interop contract,
performing no tail null-termination. A static-token `T[]`, erased `object[]`, LINQ loop, or copied
target algorithm is rejected.

The source product now extracts the exact Common expects and both complete Common loops. Its
target actuals preserve empty/reuse/iteration/store behavior, while one declaration-suppressing
intrinsic reproduces the supplied CLR vector's runtime element type. The Common `as T` store path
uses the CLR's open `unbox.any !!T` operation for both value and reference instantiations. Public
reified `toTypedArray` remains outside this completed prerequisite.

## Programme order

1. Complete the enum/annotation-class foundation required by the exact contract DSL, then
   actualize the selected builder and generated join closure.
2. Compile the exact Common abstract bases once the remaining builder prerequisite exists.
3. Add mutable collection/list contracts and an ordinary implementation.
4. Add sets and maps from their exact Common dependency closures.
5. Add explicit BCL adapters and C# conveniences without changing Kotlin identity.
6. Let `EnumEntries` consume the established collection substrate.
7. Remove the bootstrap allowlist when the complete generated product is supportable.

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
