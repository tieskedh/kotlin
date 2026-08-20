# Generic-owner mutable invariant cell

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now admits its first declaration-
invariant interface with members in both directions:

```kotlin
interface InvariantCell<T> {
    fun readCell(): T
    fun writeCell(value: T)
}
```

The normal physical and C# contract is one natural invariant
`InvariantCell<T>`. Exact and open Kotlin calls use its `!T` result/input
slots directly. The Kotlin value implementation remains a CLR-generic owner
with one `!T` field; neither the interface nor its state is globally erased.

## Operation-local semantic boundary

Kotlin projections which cannot name the same honest invariant CLR
construction use `object` only at their public operation boundary:

```text
InvariantCell<*>.readCell()              object -> object
InvariantCell<out Any?>.readCell()       object -> object
InvariantCell<in String>.writeCell(v)    (object, string) -> void
```

A Kotlin-emitted object takes its non-generic semantic capability. An ordinary
foreign object instead selects exactly one natural closed `InvariantCell<>`
construction and invokes the requested member. The runtime cache key contains
the runtime type, open interface, and member name. Invocation occurs outside
the lock; a member exception is unwrapped and rethrown. No wrapper, duplicate
state, fabricated `InvariantCell<object>`, or second identity is introduced.

Runtime surface 40 adds the zero-or-one-argument `InvokeUniqueMember` compiler
ABI. The existing surface-39 `InvokeUniqueProducer` remains available and
forwards to it, so this additive change does not remove the prior entry.

## Construction-local storage

The exact/open control remains fully typed:

```text
<T>(Box<InvariantCell<T>>) -> Box<InvariantCell<T>>
    = Box<InvariantCell<!!T>> -> Box<InvariantCell<!!T>>
```

Only materializing the projected view chooses a broad construction:

```text
Box<InvariantCell<out Any?>> -> Box<object>
```

That box can retain first a String cell and then an Int cell because both are
legal values of the projected Kotlin type. The open `Box<T>` TypeDef still has
one `!T` field, and unrelated exact/nested constructions remain typed.

## Ordinary C# implementation

Same-module and separate-compilation probes deliberately use non-partial C#
classes which implement only:

```csharp
InvariantCell<string>
```

with normal `string readCell()` and `void writeCell(string)` methods. Exact,
star/output-projected, and input-projected Kotlin calls all reach those natural
members and retain the original C# object. The C# authoring tool skips source
generation only when the manifest proves either the earlier invariant
producer shape or exactly this one-producer/one-consumer invariant bundle.
Every broader or variant contract remains fail-closed.

An additional ordinary `InvariantCell<object>` proves the genuine
`InvariantCell<Any?> -> InvariantCell<in String>` input relation rather than
only an exact-argument projection. Missing and multiply constructed foreign
objects fail deterministically, and an exception thrown by the selected write
member escapes without a reflection wrapper.

## Fail-first and evidence

Before admission, both runtime profiles rejected `InvariantCell<string>` as a
non-generic CLR type. After structural admission, projected foreign writes
failed by trying to cast the ordinary C# object to the Kotlin capability. The
argument-bearing unique-construction fallback is therefore required by the
new input operation rather than being dead support code.

The final focused proof covers:

- natural invariant CLR metadata with `!T` input and result slots;
- a Kotlin implementation with a physical `!T` field;
- exact calls, projected/star reads, projected writes, and Unit materialization;
- legal broad `Any?`/`object` input constructions, missing/ambiguous rejection,
  and write-exception transparency;
- overloaded and explicitly open-nullable negative controls which remain
  non-generic;
- exact/open `Box<InvariantCell<!!T>>` and construction-local `Box<object>`;
- Kotlin and ordinary non-partial C# identity/mutation;
- the two-member C# authoring manifest and fail-closed generator rule; and
- same-module and separate-KLIB restoration.

PSI and LightTree execute both fixtures on .NET Framework 4.8 and .NET 10:
eight tests with zero failures, errors, or skips. The production epoch-off
inverse executes the same eight tests with zero failures, errors, or skips.
The final normal production aggregate directly audits 190 XML suites and 2,287
tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written, while the unchanged six-
test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This proof does not admit properties, defaults, overloads, constraints,
inherited broader member families, multiple or mixed-variance type parameters,
or value-class substitutions. Those shapes need independent structural and
foreign-implementation proofs before admission may widen.
