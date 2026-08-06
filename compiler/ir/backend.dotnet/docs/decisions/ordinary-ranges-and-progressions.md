# ADR: ordinary ranges, progressions, and primitive iterators

- Status: **Accepted — pre-ABI**
- Scope: `ClosedRange`, `OpenEndRange`, `ClosedFloatingPointRange`, signed
  `Char`/`Int`/`Long` ranges and progressions, their primitive iterators, the
  non-random Common generated range operations, and ordinary range loops
- Does not enable: unsigned types, `Random`, reified operations, typed C#
  exports, or a BCL range identity

## Decision

Kotlin/.NET compiles the authoritative shared stdlib range, progression,
progression-iterator, primitive-iterator, and progression-utility sources.
Kotlin-owned ranges and progressions are ordinary reference classes. They do
not map to `System.Range`, `System.Index`, a CLR value type, or a target-written
replacement algorithm.

The generic range interfaces retain their complete type parameters, bounds,
and member types in KLIB. Their physical TypeDefs and virtual slots follow the
accepted erased generic-interface ABI. The signed primitive range and
progression classes are non-generic CLR classes with exact `char`, `int32`, or
`int64` state. Their generic `Iterable<T>` and range-interface edges use the
ordinary erased Kotlin interface views and bridges.

The eight shared primitive iterator base classes become ordinary public
Kotlin.Stdlib classes. Each owns its exact `nextByte`, `nextInt`, and analogous
primitive member plus the authoritative boxed `next` bridge from Common
source. They no longer alias the single erased `Iterator` interface. Array and
progression iterator factories return instances of the correct base class;
therefore `iterator is IntIterator` is truthful and an unrelated iterator is
not accidentally every primitive iterator.

The target uses the repository's shared `RangeContainsLowering` followed by
shared `ForLoopsLowering`, in the same order as JVM, JS, Wasm, and Native. A
range value is materialized only when it survives that optimization. The
private `Array.indices`, `Int.until`, and `Int.downTo` bootstrap markers are
removed once the real declarations exist.

Primitive `rangeTo` declarations are bodyless Kotlin built-ins. As on the JVM
and JS, target codegen constructs the selected concrete range from the two
evaluated operands. `rangeUntil` delegates to the authoritative generated
`until` declaration rather than duplicating its minimum-bound/empty-range
algorithm in target codegen.

The shared loop lowering retains the substituted element type of an array
access even though the intrinsic IR declaration still mentions its type
parameter. Intrinsics may therefore report their natural, receiver-substituted
return type to CIL expression codegen. This is general intrinsic typing, not a
range-specific coercion; without it an `Array<String>` loop would incorrectly
try to view the loaded `String` as the unresolved method parameter `T`.
An erased/star-projected receiver is deliberately different: a lowering-time
capture owned by the `Array` declaration is not a type parameter of the method
being emitted and must never escape as a free CLR `!n` token. Its fixed
`Any?` read is `object`. Concrete foreign/use-site element results still fall
through to the existing checked narrowing, and real method-generic arrays keep
their valid `!!n` element type.

Common progression validation accepts `Number` because `Char`, `Int`, and
`Long` steps share that logical Kotlin supertype. CLR has no physical exact
root for all Kotlin numeric boxes, so direct `kotlin.Number` carriers use the
classified `object` boundary while exact scalar boxes retain their identity.
This admits the authoritative validation body; it does not claim that the
whole `Number` virtual API has been admitted.

## Authority and mature-target evidence

- Common owns `Range.kt`, `Ranges.kt`, the generated progression/range
  classes, iterator classes, progression arithmetic, and generated operations.
- JVM keeps the same reference classes, uses the shared range/loop lowerings,
  and intrinsifies primitive `rangeTo`; `rangeUntil` calls the generated
  `until` implementation.
- JS compiles the shared classes and range operations, uses the shared
  lowerings, and redirects primitive range built-ins to target helpers which
  construct the same logical ranges.
- Wasm uses `RangeContainsLowering` and a small `ForLoopsLowering` subclass for
  target loop details while retaining the shared Kotlin range model.
- Native uses the shared range model and a small `ForLoopsLowering` subclass;
  primitive iterators remain the Kotlin primitive-iterator hierarchy.

The CLR creates no semantic reason to depart from that object model. Its
useful delta is exact primitive field/vector storage and CIL construction of a
reference object.

## Admitted Common closure

The first complete signed closure contains:

- `ClosedRange`, `OpenEndRange`, and `ClosedFloatingPointRange`;
- generic comparable and floating-point range implementations;
- `CharRange`, `IntRange`, and `LongRange`;
- `CharProgression`, `IntProgression`, and `LongProgression`;
- progression iterators and `getProgressionLastElement`;
- all eight primitive iterator bases, because they are one existing Common
  class family and every primitive array already declares its exact iterator
  return type;
- signed `downTo`, `until`, `step`, `reversed`, nullable/mixed `contains`,
  exact-conversion helpers, and coercion operations generated from the Common
  templates;
- array `lastIndex` and `indices` for the supported object and signed
  primitive arrays; and
- Common `repeat`, whose body can now use the real `0 until times` product.

Range `random`/`randomOrNull` stays out because `kotlin.random.Random` is an
independent platform-entropy and algorithm closure. Unsigned ranges stay out
with unsigned scalar/value-class support. This exclusion is dependency based;
it does not authorize target copies of the omitted declarations.

## Physical and interop contract

For `val r: IntRange = 1..3`, the physical value is conceptually:

```text
class Kotlin.Ranges.IntRange : Kotlin.Ranges.IntProgression,
                               Kotlin.Ranges.ClosedRange,
                               Kotlin.Ranges.OpenEndRange
```

Its endpoints and progression step use `int32`. Generic range-interface slots
box at the erased boundary exactly as other Kotlin-owned generic interfaces
do. The concrete class API remains exact. KLIB, not CLR generic construction,
is authoritative for `ClosedRange<Int>`.

Raw C# may consume the truthful concrete reference classes and their exact
primitive members. It must not be told that the erased implementation
interface is a native `ClosedRange<int>` contract. A future explicit export
may publish such a typed interface or facade under the export ADR; it cannot
change Kotlin range identity or state.

The raw class surface does not duplicate inherited erased-interface members
under friendlier typed names. For example, C# can call `nextInt()` directly on
`IntIterator`, while the iteration-state capability remains
`Kotlin.Collections.Iterator.HasNext()`. Explicit .NET export may combine
those into an idiomatic typed view; the implementation ABI does not grow a
second slot family merely for raw C# convenience.

The Common generated range surface uses JVM names to disambiguate overloads
which erase to the same physical signature. Kotlin/.NET gives those functions
stable CLR names as well. Primitive mixed-domain overloads retain their
generator-selected names. The four generic nullable `contains` families pin
the range kind and erased bound in the name (`closedRange` or `openEndRange`,
and `Comparable` or `Any`) rather than depending on declaration order. KLIB
continues to expose every declaration as `contains`.

`System.Range` is specifically not the implementation carrier. It represents
indexing endpoints with from-end semantics, is a CLR value type, is not
iterable, and does not carry Kotlin progression steps, empty-range equality,
or the `Char`/`Long` families.

## Design attack

### Retain only counted-loop intrinsics

Rejected. It makes `for` demonstrations work while range values, iteration,
`step`, equality, `repeat`, `indices`, and separate-library calls remain absent.
It also keeps target pattern matching where all mature targets use the shared
loop framework.

### Map to `System.Range`

Rejected. The host type has a different identity, state, domain, equality,
and iteration model. Adapters would be more complex than compiling Common and
would still be semantically incomplete.

### Keep primitive iterator classes aliased to `Iterator`

Rejected. The alias makes every iterator satisfy every primitive iterator
type test, erases exact public return classes, and prevents ordinary
progression iterators from inheriting their Common base contract. It was a
bootstrap shortcut, not a defensible ABI.

### Admit every generated range declaration immediately

Rejected. `random` would pretend that the independent `Random` substrate is
complete, and unsigned declarations would pull in their parked scalar/value-
class representation. The signed, non-random set is a source-generator-owned
dependency closure rather than an arbitrary declaration count.

## Verification and freeze conditions

The feature is complete only when tests cover both frontends and CLR profiles,
materialized and optimized ranges, all three signed progression domains,
positive/negative steps, empty and extreme bounds, iterator exhaustion and
primitive-iterator type identity, nullable and mixed `contains`, `repeat`
including non-local return and invalid counts, array indices, separate
producer/consumer binding, KLIB logical generics, physical non-generic range
interfaces, exact primitive fields/signatures, and C# observation of the
truthful concrete surface.

Before ABI freeze, correct any range owner/member names and primitive-iterator
factory contracts together across stdlib production, physical ABI records,
compiler fallback binding, and installed consumers. Do not add compatibility
aliases for the former prototype iterator alias or private counted-loop
markers.

The admitted Common `Ranges.kt` source intentionally suppresses compatibility
diagnostics for both old and new upper-bound spellings. The compiler-owned
stdlib source product enables the repository's `dontWarnOnErrorSuppression`
analysis flag, matching stdlib compilation rather than editing that Common
source. Ordinary installed-library consumers do not inherit this setting.
