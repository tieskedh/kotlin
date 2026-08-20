# Reified generic-interface default proof — 2026-08-20

## Question

Can an admitted real CLR generic interface retain a Kotlin default body across
Framework 4.8 and .NET 10 while keeping the natural member as the only ordinary
C# contract and preserving Kotlin views which CLR variance cannot express?

The bounded proof uses one contravariant interface:

```kotlin
interface DefaultConsumer<in T> {
    fun consumeDefault(value: T) { /* one Kotlin body */ }
}
```

One physical `DefaultConsumer<object>` is also called through Kotlin's legal
`DefaultConsumer<Int>` view. The latter has no CLR variance conversion because
`int` is a value type.

## Fail-first evidence

The first candidate failed for two independent architectural reasons:

- the modern Kotlin implementation did not acquire its semantic capability,
  so the CLR rejected the type before execution; and
- the portable generated C# implementation could not name a helper nested in
  a generic interface (`CS0648`).

A temporarily broad bridge exception then made the epoch-off modern product
recurse through its canonical bridge. The production inverse therefore caught
an over-general correction before it could land.

## Selected representation

- The natural `DefaultConsumer<T>.consumeDefault(T)` MethodDef is the normal
  Kotlin and C# slot.
- Framework moves the one body to a metadata-public top-level
  `__KotlinDefaultImpls_<logical-owner-digest>` compiler-ABI type. Owner type
  parameters are helper-method parameters, and Kotlin/generated-C# implementors
  forward their natural slot to it.
- .NET 10 places the body on the natural DIM and retains the helper for the
  accepted compatibility ABI.
- A value-type-narrowed Kotlin view calls the same object's semantic
  capability. When an implementation overrides the natural member, the
  capability dispatches through that virtual slot; it does not bypass Kotlin
  or C# overrides by selecting the interface body directly.
- Ordinary C# never authors the semantic capability. The portable generator
  supplies its forwarding obligation; modern C# inherits the DIM or overrides
  the natural method normally.

The top-level helper is a physical naming correction, not a second Kotlin
identity. Its exact owner is already stored in structured producer ABI; a
consumer must not derive the digest or helper name.

## Verification

The focused rehearsal executes the same-module and separate-Kotlin-DLL cases
with PSI and LightTree on Framework 4.8 and .NET 10. It covers the inherited
default, a Kotlin natural override, generated ordinary-C# default inheritance,
an ordinary C# natural override, exact and narrowed Kotlin calls, one body, and
same-object identity. The enabled candidate and epoch-off inverse each require
eight green lanes with zero failures, errors, or skips. Profile IL tests pin the
portable helper/forwarder and modern DIM/helper layouts.

The first full integration run found one stale physical assertion which still
expected the former nested generic helper. The corrected cross-profile oracle
requires the namespace-qualified top-level helper plus its 32-hex logical-owner
digest. The final production aggregate covers 190 XML suites and 2,287 tests
with zero failures, errors, or skips: 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written; the unchanged six-test
`dotnet.ir` model root remained up-to-date.

## Remaining boundary

This proves one contravariant default member. Multiple defaults, generic
methods, property defaults, diamonds, reabstraction, hostile inheritance, and
broader foreign implementation shapes remain closed until their complete
semantic, ABI, C# authoring, separate-compilation, and profile matrices pass.
