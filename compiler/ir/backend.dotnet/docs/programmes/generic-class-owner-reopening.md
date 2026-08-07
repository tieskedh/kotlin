# On-hold question: true CLR-generic Kotlin class owners

- Status: **On hold — do not implement**
- Current authority: [`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md)
- Related export boundary: [`../decisions/draft-adr-explicit-csharp-export-surface.md`](../decisions/draft-adr-explicit-csharp-export-surface.md)

## Exact question

Is a true CLR-generic owner for a Kotlin-owned generic class worth its permanent
ABI and compiler complexity when Kotlin/.NET deliberately allows the
physically checkable generic-argument part of an otherwise unchecked cast to
fail at the cast boundary?

The alternative under consideration is not the removed design in which typed
dispatch was normal and an erased canonical route was an exceptional fallback.
It would require both paths to be complete:

```text
Kotlin class Box<T>
    physical owner and authoritative state: Box<T>
    complete Kotlin semantic capability:   erased Box view
```

Exact, unprojected operations could use `Box<string>` or `Box<int>`. Stars,
projections, variance, widened operations, and declaration-erased runtime
classification would use the complete erased capability ABI. The same object
would implement both views and retain one authoritative state.

## Why the question became credible

The current accepted ABI deliberately follows mature-target delayed failure:

```kotlin
val original = Box("text")

@Suppress("UNCHECKED_CAST")
val wrong = original as Any as Box<Int>

wrong.value = 7
original.value // failure when the value is consumed as String
```

Kotlin diagnoses the generic-argument portion of this cast as unchecked. The
language permits a platform to reject a physically incompatible construction
earlier. If Kotlin/.NET instead throws during `as Box<Int>`, a physical
`Box<string>` never has to accept an `Int` through that invalid view. Typed
single-state storage therefore becomes plausible; the former two-store or
deoptimization contradiction no longer decides the entire architecture.

On this target the physical throwable would normally be the original
`System.InvalidCastException`, classified as Kotlin `ClassCastException` by the
accepted exception model. Do not wrap or translate it merely to mimic a JVM
stack trace.

## What early failure may cover

Only the physically incompatible part of a cast that Kotlin already cannot
fully check is a candidate for earlier failure. For example, a value whose
physical owner is `Box<string>` may fail an unchecked request for `Box<int>` at
that cast.

The eventual design must decide and test at least:

- checked `as`, safe `as?`, and suppressed/unsuppressed unchecked diagnostics;
- concrete reference, value, nullable-value, and user-defined struct arguments;
- open method type parameters and nested generic constructions;
- null receivers and nullable cast targets; and
- the exact physical exception and logical Kotlin classification.

`as?` must still return `null` rather than leak `InvalidCastException` when the
ordinary safe-cast contract applies.

## What must never be rejected

Early incompatible-cast failure does not relax ordinary Kotlin semantics. A
candidate route must keep all of these valid without wrapping or copying:

- `is Box<*>`, `as Box<*>`, and safe star casts;
- declaration-site covariance and contravariance;
- use-site `out`, `in`, and star projections;
- widened receivers and arguments produced by normal Kotlin subtyping;
- identity, mutation, virtual dispatch, inheritance, `super`, nested/inner
  classes, defaults, and separate compilation on every successful path;
- nullable and bounded generic forms; and
- candidate-accepting operations such as `contains` and `containsAll` that must
  return `false`, rather than throw, for an incompatible candidate admitted by
  the widened Common signature.

The `containsAll` family proves that the erased Kotlin operation path is a
normal correctness path. A typed member may optimize a compatible candidate,
but its erased bridge must test compatibility and preserve Common behavior; it
may not narrow `object` to `int` before deciding that a `String` is absent.

## Questions whose answers select the outcome

### 1. Does the complete semantic matrix work with one object and one state?

If no, retain the accepted erased owner. Wrappers, copying, two authoritative
stores, or visibility-dependent runtime identity are not acceptable repairs.

If yes, continue to the ABI and product questions; semantic possibility alone
does not justify the route.

### 2. Can inheritance and dispatch remain complete?

The spike must cover open classes, typed overrides, erased capability slots,
`MethodImpl`, C# subclasses, Kotlin subclasses of C# types, projected calls,
and multi-level separate compilation. An erased bridge that bypasses the most
derived override or narrows a candidate too soon rejects the route.

### 3. Can reflection expose one Kotlin declaration identity?

`KClass`, `KType`, class literals, `is`, callable owners, and future member
enumeration must normalize every constructed `Box<T>` to one logical Kotlin
classifier while retaining logical arguments only where Kotlin APIs expose
them. Raw `System.Type` constructions may be useful CLR evidence but cannot
become Kotlin declaration identity.

### 4. Is the C# surface honest and understandable?

Measure whether a true `Box<T>` owner actually removes enough adapters and
provides usable C# construction, inheritance, nullability, constraints, and
IntelliSense. Half-typed surfaces, surprising erased members, or CLR casts that
look stronger than the supported contract count against the design. Explicit
export remains an independent alternative.

### 5. Do measured benefits justify the permanent cost?

Use representative applications, not one microbenchmark. Compare at least:

- boxing, allocations, and object size for Kotlin primitives and arbitrary CLR
  structs such as `Guid`, `DateTime`, `decimal`, enums, tuples, and user types;
- exact typed dispatch and compatible erased dispatch;
- JIT, ReadyToRun, and NativeAOT code size and throughput;
- DLL metadata size, TypeDefs, MethodDefs, MethodImpl rows, and generic
  instantiations;
- compile time, memory, KLIB/physical-binding size, and incremental rebuilds;
  and
- compiler, runtime, reflection, importer/exporter, and maintenance complexity.

If improvement is small or confined to shapes handled by removable private
specialization, retain the erased owner and optimize behind it.

## Shared adversarial comparison matrix

Any reopening must compare the accepted erased owner and the candidate typed
owner against the same sources and assertions:

1. exact reference/value/nullable/struct construction and member access;
2. invalid checked and safe casts, including exception timing and identity;
3. stars, projections, declaration variance, widened joins, and erased calls;
4. `contains`/`containsAll` false/null/empty/throwing candidates;
5. mutable same-object state on every successful view;
6. inheritance, overrides, abstract members, interfaces, default methods, and
   C# subclassing;
7. nested/inner classes, recursive bounds, generic methods, arrays, and
   nullability;
8. KClass/KType/reflection normalization;
9. self-describing separate libraries and version-skew rejection;
10. both FIR parsers and every compatible target profile; and
11. C# compilation and execution against the supported public surface.

The matrix may be designed and committed while this question is on hold, but
production typed-owner infrastructure must not be implemented merely to make
one side pass. A later explicitly authorized architecture spike must remain
bounded and must not publish a third ABI.

## What this question locks

Until explicitly reopened, do not:

- emit a CLR-generic TypeDef as the implementation owner of an ordinary
  Kotlin-owned generic class;
- change the accepted delayed-use cast behavior or its tests;
- reintroduce canonical class interfaces, ancestry classifiers, generic-class
  bridge manifests, or typed-owner capability probes;
- freeze public ABI or export rules that assume the internal Kotlin class is
  CLR `C<T>`; or
- describe CLI generic capability as authorization for that representation.

This question does **not** block:

- Common stdlib and language-feature foundations using the accepted erased
  owner;
- callable invocation, member reflection, annotations, or contracts;
- structured CLI IR and complete support for physical generic metadata;
- imported CLR generic classes and interfaces;
- CLR-generic methods and truthful exact interface capabilities;
- explicit fail-closed .NET export facades/adapters; or
- removable private specialization whose disablement changes no supported ABI
  or behavior.

## Reopening condition

Reopen only on an explicit request after the semantic matrix, architecture
spike plan, and measurement corpus exist. The decision must then either retain
the erased owner or amend its ADR atomically across compiler, runtime, stdlib,
KLIB/physical metadata, reflection, export/import tooling, tests, and
documentation. There is no mixed compatibility period on this pre-ABI branch.
