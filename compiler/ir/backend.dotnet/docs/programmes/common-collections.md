# Common collections programme

- Status: **Active — select the next exact non-inline Common dependency closure**
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

## Next selection rule

Select the next exact non-inline Common/generated family only when all of these are closed:

1. every source declaration and generator variant is identified from an authoritative owner;
2. every called Common helper and expect/actual dependency is supported;
3. its types and calls have truthful same- and cross-module CLR representations;
4. the direct, packaged fallback, installed, and portable stdlib products use the same source;
5. no public or protected declaration is evicted; and
6. adversarial behavior can be executed on both runtime profiles.

Do not choose a family solely because one downstream feature, such as enums, needs it.

## Abstract collection/list blockers

All mature targets compile the shared `AbstractCollection.kt` and `AbstractList.kt`. Kotlin/.NET
must do the same once their exact closure exists. The current blockers are:

- generic inline helpers used by `contains`, `containsAll`, `indexOf`, and `lastIndexOf`;
- `joinToString` and its `CharSequence`/`Appendable`/`StringBuilder` closure; and
- typed collection-to-array expect/actual operations that preserve the caller's CLR array element
  type.

Importing the abstract bases early would require declaration eviction, copied Common algorithms,
or unjustified .NET intrinsics. All three are rejected. The private direct List view remains until
these prerequisites are genuinely supported.

## Programme order

1. Add bounded non-inline Common/generated operations by the selection rule above.
2. Complete generic inline and string-building prerequisites needed by the abstract bases.
3. Implement typed collection-to-array semantics and compile the exact Common abstract bases.
4. Add mutable collection/list contracts and an ordinary implementation.
5. Add sets and maps from their exact Common dependency closures.
6. Add explicit BCL adapters and C# conveniences without changing Kotlin identity.
7. Let `EnumEntries` and enums consume the established collection substrate.
8. Remove the bootstrap allowlist when the complete generated product is supportable.

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
