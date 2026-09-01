# Generic-owner physical typed call result — 2026-09-01

This archive records the first authority-backed natural-MethodDef result transfer
into local storage in the generic-owner physical-value rehearsal. It changes no
physical-library ABI, artifact schema, Runtime/Stdlib surface, production owner
representation, or Kotlin semantics.

## Boundary

The physical operation model already instantiated the result layout recorded by
one selected MethodDef, but final value flow did not consume that result. A
logical `T` call initializer was therefore outside the transfer grammar even
when the emitter independently selected a natural MethodDef returning owner
`!T`.

The bounded rule is:

```text
bound declaration authority selects natural MethodDef
receiver provenance guarantees one construction of its declaring TypeDef
existing logical route, when present, is EXACT_TYPED_ENTRY
call has no ordinary arguments, MethodSpec, super target, or split result
instantiated MethodDef result                         = !n
immutable local storage                              = same !n
live emitter-resolved MethodDef result               = same !n
---------------------------------------------------------------------
retain !n in the local
```

The legacy route census records calls which require generic-owner routing. Its
absence is not evidence and does not make an ordinary natural call unknown. A
present semantic or semantic-result route does veto this transfer. Declaration
authority supplies the MethodDef; selected lineage may only choose an already-
guaranteed receiver view and can never prove that view exists.

The MethodDef-owner view selector is now shared by local and retained-foreign
operations rather than living in the retained-foreign adapter. The physical-
operation query remains the sole result-layout instantiator. Late local
placement separately resolves the call as emission will, rejects intrinsics and
split-nullable results, reconstructs the expected owner binder from the physical
method owner, and requires exact carrier equality.

The current slice does not admit ordinary call arguments, method type
parameters, direct-super targets, semantic or semantic-result routes,
`SplitNullable`, fields, captures, properties, state, or representation-changing
conversions. A result `!T` remains substitution-dependently maybe-null; the
logical Kotlin result type cannot strengthen that physical fact.

## Red-to-green proof

`genericOwnerInlineWidenedTemporary.kt` now materializes:

```kotlin
val sourceNaturalAlias: InlineProducer<T> = this
val exactResultAlias: T = sourceNaturalAlias.produce()
```

Before the implementation, the final comparison reported the result initializer
as `UNSUPPORTED` while the live emitter already stored `exactResultAlias` as
owner `!0`. After the implementation, shared value flow produces and stores the
same owner-bound `!0`, and the emitter selects
`PHYSICAL_VALUE_RETAINED_PRODUCER` only after resolving the live call result to
that same carrier. Both `Int` and `String` substitutions execute.

The hostile physical-value fixture remains in the matrix. Source and compiler
aliases, stars/projections, mutable/multiple-write flow, control-flow joins,
typed versus semantic entry environments, and semantic routes keep their prior
fail-closed boundaries. The pure retained-foreign suite also remains green after
moving the common MethodDef-owner view selector.

An attempted additional gate, `genericOwnerSemanticBodyExactResultChain`, failed
in all four profiles before value transfer because `SingleEntryOwner<K,V>` could
not bind its inherited `EntryOwner<K,V>` construction. The exact PSI failure was
reproduced on untouched preceding checkpoint `d1b6d8ebd0`; it is an existing
inherited-owner admission blocker, not a regression or a valid gate for this
slice.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -Pkotlin.dotnet.genericOwnerRehearsal=true --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
```

The shared physical-value model reported 87 tests and the retained-foreign
authority suite 85 tests, all green. Direct JUnit XML audit reported four
candidate suites/eight tests and four fresh production-erased inverse
suites/eight tests, with zero failures, errors, or skips across FIR PSI, FIR
LightTree, Framework 4.8, and .NET 10.

## Next boundary

Compose owner-dependent argument admission, MethodSpecs, and split-nullable
results through the same operation contract, then complete remaining parameter-
entry forms. Do not turn a semantic call or an absent receiver proof into a
natural result, and do not advance the source-built Stdlib census with another
result-chain recognizer.
