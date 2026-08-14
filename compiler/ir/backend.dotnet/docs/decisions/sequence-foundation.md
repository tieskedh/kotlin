# ADR: Kotlin-owned Sequence foundation

- Status: **Accepted — pre-ABI**
- Date: 2026-08-14
- Scope: the ordinary `Sequence<out T>` identity, non-builder Common sequence
  implementation classes, and the dependency-closed Common generated
  sequence/collection conversion families
- Does not enable: sequence/iterator builders, random/shuffle, window/chunk,
  `Grouping`, unsigned aggregates, BCL enumeration identity, or typed C# export

## Decision

Kotlin/.NET compiles the authoritative Common `Sequence<out T>` declaration,
the dependency-closed declarations from Common `Sequences.kt`, the Common
generated sequence templates, and the JS/Wasm single-threaded
`ConstrainedOnceSequence` actual. Kotlin-owned sequences remain ordinary lazy
Kotlin objects. They do not map to `System.Collections.IEnumerable`,
`IEnumerable<T>`, `IEnumerator`, `IAsyncEnumerable<T>`, LINQ, or a CLR
delegate pipeline.

`Sequence<T>` follows the accepted erased generic-interface ABI: KLIB retains
covariant `T`, while one non-generic CLR interface owns one erased `iterator`
slot. Its implementation classes follow the accepted erased Kotlin-owned
generic-class ABI. Intermediate operations therefore return ordinary
Kotlin-owned objects whose iterator acquisition, callback order, laziness,
exception identity, and one-shot behavior come from Common source.

The initial closure deliberately excludes every declaration whose body reaches
an independent unavailable substrate:

- `ifEmpty`, lazy `flatMapIndexed`, running/scan operations, and
  `zipWithNext` reach the sequence-builder coroutine API;
- `windowed`/`chunked` reach the iterator-builder and sliding-window closure;
- `shuffled` reaches `kotlin.random.Random` and a sequence builder;
- `groupingBy` reaches the independently selected `Grouping` identity and
  aggregate source;
- `UInt`/`ULong` selector sums reach the unsigned value-class product.

Every other generated Sequence member is admitted as one generator-owned
dependency partition, including terminal traversal, filtering, mapping,
flattening, eager snapshots and associations, distinctness, predicate and
numeric aggregates, stable lazy sorting, plus/minus, zipping, joining,
reified filtering, and collection/array/iterator conversions. The generator
must enumerate the complete Common Sequence family and fail if a member is
neither admitted nor named in the exact excluded partition.

The resulting source closure also contains the exact Common array and
`Iterable` `asSequence` adapters, `MutableCollection.addAll(Sequence)`, Common
`AbstractIterator`, and only the floating-point expects/actuals reached by the
admitted min/max and sortedness algorithms. Those supporting declarations are
dependencies of the Common bodies, not target substitutes for them.

## Authority and mature-target evidence

- Common `Sequence.kt`, `Sequences.kt`, and `_Sequences.kt` own the logical
  identity, algorithms, laziness, and evaluation order.
- JVM compiles the same declarations and uses an atomic reference only for
  concurrent `ConstrainedOnceSequence.iterator()` acquisition.
- JS and Wasm compile the same object model and implement that actual with one
  nullable sequence reference; this is the selected precedent while the .NET
  concurrency/memory-model programme remains parked.
- Native compiles the same model and uses an atomic reference when its
  concurrency model requires one.

The CLR supplies no semantic reason to replace this model. Its generic
enumeration interfaces have different identities and iteration state, and
LINQ would change implementation objects, callback/exception paths, and
observable laziness.

## Physical naming and C# boundary

All top-level declarations share `Kotlin.Sequences.SequencesKt`. CLR method
signatures cannot overload by return type and the erased Sequence receiver
does not retain its element argument. The compiler therefore gives colliding
Common generator families deterministic C#-spellable physical names derived
from their authoritative logical overload domain. This includes element-
specific numeric aggregates and selector-result-specific min/max/sum
aggregates. KLIB continues to expose the original Kotlin names and overloads;
the physical names are a bounded stdlib ABI projection, not general .NET
meaning for `@JvmName`.

A substituted generic result can temporarily have its erased physical upper-
bound view. For example, `Sequence<T>.min()` instantiated with `T = Int` has
logical return type `Int`, while the physical Common declaration returns its
`Comparable` upper-bound carrier. A frontend-proven `IMPLICIT_CAST` back to a
supported primitive therefore emits `unbox.any` at that boundary. This is
generic substitution recovery, not a broader cast policy: explicit casts,
safe casts, and unproven reference-to-value conversions keep their existing
operation-specific rules.

Raw C# may implement the single erased Kotlin `Sequence` interface and call
its erased iterator slot or public facade methods. That is a truthful low-level
surface, but it is not an idiomatic `IEnumerable<T>` contract. A future export
adapter may project a typed enumerable view without changing Kotlin identity,
iterator timing, or same-object behavior. Kotlin-owned objects do not acquire
an implicit BCL interface in this tranche.

## Cross-profile invariant

.NET Framework 4.8 execution is independent evidence. A successful .NET 10
JIT, ReadyToRun, trimmed, or NativeAOT run cannot stand in for CLR 4 behavior,
including behavior or performance affected by `System.Object`, boxing,
interface dispatch, or runtime generic optimizations. The feature gate must
execute the same hostile semantic corpus on the installed Framework 4.8 CLR
and on .NET 10, in addition to validating all three target products.

## Rejected alternatives

### Map Sequence to `IEnumerable<T>`

Rejected. Constructed CLR identity does not preserve Kotlin value-type
covariance, and `IEnumerator<T>.MoveNext`/`Current` is not the Kotlin
`Iterator.hasNext`/`next` state machine. Wrapping subtype conversions would
also change identity and iterator acquisition.

### Implement operations with LINQ

Rejected. It forks Common algorithms and can change allocation, callback,
exception, comparison, and enumeration behavior.

### Admit builder-dependent members with target helpers

Rejected. Sequence builders are a coroutine-language/runtime closure. Their
absence must remain visible instead of being hidden behind copied iterators or
backend intrinsics.

### Start with only eager terminal functions

Rejected. It avoids the Kotlin-owned lazy implementation objects and generic
interface/class boundary that define the substrate, then forces a second
representation decision later.

## Completion and freeze conditions

Completion requires PSI and LightTree execution on Framework CLR and CoreCLR,
portable `netstandard2.0` production consumed by both runtimes, separately
compiled Kotlin consumers, and Roslyn metadata/call evidence. Tests must cover
empty/singleton/multiple, primitive/reference/nullable/widened values; repeated
and constrained-once iteration; laziness and exact iterator/callback counts;
short-circuiting; exception identity and timing; reified filtering; stable
sorting; numeric overloads; and absence of BCL enumerable identity.

Before ABI freeze, correct the Sequence TypeDef, facade, collision names, and
erased iterator slot atomically across stdlib production, physical ABI records,
fallback sources, installed consumers, and C# evidence. Do not add aliases for
prototype names or expose a second typed Sequence identity.
