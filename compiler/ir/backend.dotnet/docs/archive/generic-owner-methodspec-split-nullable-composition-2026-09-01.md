# Generic-owner MethodSpec and split-nullable composition — 2026-09-01

This archive records the first producer and consumer proof that a generic
MethodDef binder, ordinary parameter domains, and a split-nullable result layout
compose as independent callable-contract components. It changes no physical-
library ABI, artifact schema, Runtime/Stdlib surface, production owner
representation, or Kotlin semantics.

## Structural contract

The custom declaration is:

```kotlin
interface MethodLookup<K, out V> {
    fun <R> lookup(key: K, marker: R): V?
}
```

Its natural CLR contract is:

```text
owner binder:   <K invariant, V covariant>
method binder:  <R invariant, unconstrained>
parameter 0:    STRICT_OWNER_INPUT(!K)
parameter 1:    DECLARATION_INDEPENDENT(!!R)
result:         SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool isNull)

!V lookup<!!R>(!K, !!R, out bool isNull)
```

No `MethodLookup`, package, member-name, collection, `Map`, IR-origin, or stdlib
identity participates in admission. The existing published member role remains
`INPUT_OUTPUT`; the existing result layout remains `SPLIT_NULLABLE`. There is no
combined method-generic/split-nullable role.

The common local callable binder and authority validator now derive the natural
and semantic MethodDefs by composing:

- the producer-selected TypeDef and MethodDef binders;
- each recorded parameter domain and its direct owner or MethodDef carrier; and
- the independently recorded `Direct` or `SplitNullable` result layout.

This first grammar is intentionally fail-closed. It admits one single-member
root interface, one direct invariant owner input, direct unconstrained
MethodDef-parameter inputs, and one distinct direct covariant nullable owner
output. Nested, nullable, constrained, concrete, defaulted, vararg, inherited,
and broader multi-member forms require their own structural authority.

## Operation and hostile boundaries

The local generic helper proves the exact call with an owner-bound receiver,
ordinary argument, MethodSpec argument, and split result:

```text
receiver:       MethodLookup<!T,!T>
key:            !T
MethodSpec R:   !T
result:         SplitNullable(!T, out bool)
route:          exact natural BOUND/MATCH
```

`Int` and `String` implementations execute hit and null paths. The exact
value-type route is a direct natural call with no result boxing.

Two controls prevent overclaiming:

- `MethodLookup<T, Any?>` remains a logical broad view and therefore uses the
  semantic route even when its receiver still guarantees
  `MethodLookup<!T,!T>`; it never fabricates `MethodLookup<!T,object>`.
- A caller MethodDef's `!!R` is not a current owner TypeDef's `!T`. The hostile
  caller-generic operation executes normally but supplies no false owner-bound
  operation authority.

## Separate compilation and C#

The producer DLL publishes and the consumer validates one natural `N` record
whose generic arity is one, whose slots are `!0`, `!!0`, and the final `bool&`,
and whose split payload is `!1`. Objective PE inspection verifies the same
MethodDef, GenericParam rows, variance, parameter order, and `[out]` flag.

Separate `lib`, `middle`, and executable Kotlin assemblies exercise exact and
widened value/reference calls. Ordinary C# classes implement only
`MethodLookup<int,int>` or `MethodLookup<int,string>` and the natural generic
method. They do not implement a semantic capability, use `partial`, run a
generator, or acquire a wrapper. Exact C# and Kotlin calls use the natural
MethodSpec. Widened Kotlin calls dispatch to the same C# object through the
producer-recorded capability/natural fallback, including value and reference
results. Reflection confirms that the C# classes expose only their one natural
`MethodLookup<,>` construction.

The erased inverse keeps both Kotlin interfaces as arity-zero physical TypeDefs.
`MethodLookup.lookup<R>` remains an erased `object`-result MethodDef with an
ordinary `!!R` parameter, and no candidate H/N/M/J records are published.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerCallableCompositionSeparateCompilation" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerCallableCompositionSeparateCompilation" --no-configuration-cache -q
```

Direct JUnit XML audit found 87 physical-value model tests and 85 retained-
foreign authority tests, all green. Candidate evidence contains four local and
four separate-compilation tests; the erased inverse contains eight tests. Every
matrix covers PSI, LightTree, Framework 4.8, and .NET 10 with zero failures,
errors, or skips.

The inherited full production-erased checkpoint remains the 2,621-test target
gate recorded by the upstream-sync archive. This rehearsal-only slice preserves
the production inverse structurally, so no new full aggregate was required for
this feature checkpoint.

## Remaining boundary

The operation preserves `SplitNullable(!V, bool)` but local placement does not
yet materialize that two-slot value across immutable locals or control-flow
joins. Concrete, constrained, nullable, nested, retained-foreign, and multiple
MethodSpec carriers remain unavailable to this local proof. Broader inheritance,
multi-member families, value-class substitutions, trimming, NativeAOT, and the
Runtime/Stdlib census remain later gates. Runtime `Map` is unchanged.
