# Generic-owner callable-contract composition (2026-08-31)

This snapshot records the bounded Stage 7 rehearsal which composes one strict
owner-dependent input with a different owner-dependent split-nullable result.
It is implementation evidence, not a production ABI decision. Durable rules
remain in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md)
and the
[split-nullable result ADR](../decisions/draft-adr-split-nullable-callable-result.md).

## Closed proof

The declaration-independent fixture is:

```kotlin
interface Lookup<K, out V> {
    fun lookup(key: K): V?
}
```

The candidate publishes one natural CLR interface and one same-object semantic
capability. Its callable contract is assembled from independent components:

```text
semantic role: INPUT_OUTPUT
parameter 0:  STRICT_OWNER_INPUT(!K)
result:       SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool isNull)

natural:      !V lookup(!K, out bool)
semantic:     object lookup(object)
```

No `Lookup`, `Map`, collection, package, member-name, or stdlib identity is used
for admission, publication, binding, or routing. The first positive grammar is
intentionally narrow: a root interface with exactly two parameters, one
invariant direct input parameter, one distinct covariant direct nullable result
parameter, and exactly one public abstract ordinary member. This is a temporary
proof restriction, not the durable callable model.

## Architectural change

The `H` member record no longer encodes split nullability as a semantic member
role. It records:

```text
logical member role
+ independent result layout: VOID | DIRECT | SPLIT_NULLABLE
```

Physical-library ABI 65 adds that result-layout field. Stale ABI-64 artifacts
are rejected rather than interpreted through a compatibility alias; no public
Kotlin/.NET ABI has shipped.

The local BOUND callable family and producer-final `N` MethodDef now preserve
the strict `!K` parameter domain and split `!V` result independently. A separate
consumer joins the logical KLIB member with the producer-recorded MethodDef and
uses that exact token. The selected MethodDef, not its semantic role or a
reconstructed logical signature, is physical authority.

The executable oracle exposed one real downstream bug: an exact separate-
consumer call initially emitted:

```text
object Lookup<int,int>.lookup(int)
unbox.any Nullable<int>
```

even though `N` had already sealed `int lookup(int, out bool)`. The final router
now uses one shared recorded-callable query for both direct split calls and the
natural-only foreign fallback. A pre-existing local MethodDef remains stronger
authority than an interface `N` record; an interface declaration record may not
claim a concrete implementation-class MethodDef reached through override
lineage.

The exact value path now calls the natural slot directly with `int32` input and
payload and no boxing. An object/semantic view uses the same object's capability
when present. An ordinary C# class may implement only `Lookup<int,int>` or
`Lookup<int,string>`; the foreign route selects and invokes the producer-
recorded natural MethodDef without requiring a partial class, generator, hidden
interface, wrapper, proxy, or copied state.

## Scope control

Separating result layout from semantic role must not silently migrate every
already-admitted nullable result. Stage 7 therefore selects split layout for:

- the pre-existing result-only split-nullable grammar; or
- exactly the new single-member structural owner-input/result grammar.

The existing source-built Runtime `Map` family remains on its prior contract.
Its dedicated candidate fixture is a negative boundary proof for this commit.
Applying the composed contract to a multi-member family remains later work.

## Preserved invariants

- Kotlin IR/KLIB remains logical authority.
- The emitted or producer-recorded CLR MethodDef remains physical authority.
- One receiver object and one authoritative state are used throughout.
- No CLR construction is fabricated from Kotlin variance.
- A selected lineage can choose only an already-proven view.
- Exact calls remain typed; semantic materialization occurs only at the
  operation boundary.
- A broad semantic route does not rewrite unrelated exact state or carriers.
- Concrete class MethodDefs are not reinterpreted through interface `N`.
- Production remains erased and has an exact no-`H/N/M/J` inverse.

## Executable evidence

All commands ran from the Stage 7 worktree on Windows with required .NET
Framework 4.8 and .NET 10 tools present.

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:compileTestKotlin -q
```

The custom candidate and production-erased inverse each passed four copies:
PSI and LightTree on Framework 4.8 and .NET 10.

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*GenericOwnerCallableCompositionSeparateCompilation" -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*GenericOwnerCallableCompositionSeparateCompilation" -q
```

The candidate checks `H/N`, objective PE metadata, GenericParam variance,
`[out] bool&`, direct unboxed IL, recorded widened dispatch, separate Kotlin
assemblies, same-object identity, and ordinary non-partial C# implementations.
The inverse checks absence of candidate records, one arity-zero owner, and one
erased object-domain MethodDef before executing the same Kotlin corpus.

The previous split-nullable and unchanged Runtime-Map boundaries also passed
their complete four-copy candidate fixtures:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*GenericOwnerSplitNullableResultSeparateCompilation" -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*GenericOwnerRuntimeMapSeparateCompilation" -q
```

Five directly affected backend model classes produced 111 tests, and the full
`DotNetLibraryIntegrationTest` class produced 107 tests; both XML audits had
zero failures, errors, or skips.

```text
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarationsTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericInterfacePhysicalAuthorityTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerNaturalMethodDefPublicationTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest" -q
.\gradlew.bat :compiler:tests-integration:dn --tests "org.jetbrains.kotlin.cli.DotNetLibraryIntegrationTest" -q
```

The fresh dependency-wide gate exposed one pre-existing Stage 6 test-oracle
lag. Commit `bbfeab29db` had deliberately extended semantic-state output
pairing from nested owner applications to direct `T` state. The compiler and
producer-recorded route resolver therefore correctly reported both
`PAIRED_OPEN_OUTPUT_STATE` and `PAIRED_SEMANTIC_STATE_OUTPUT`, and classified
five exact-receiver state reads as `SEMANTIC_RESULT_CAPABILITY`; the older
hardest-model oracle still expected the pre-change single reason and eighteen
exact typed routes. The unchanged `bbfeab29db` worktree reproduced the failure.
The oracle now requires the exact two-reason set and the concrete route census
of 13 exact, 13 semantic-receiver, five semantic-result, and 24 still-erased
calls. Both hardest-model fixtures then passed on all four parser/runtime lanes.
No compiler or runtime behavior was weakened to repair the gate.

After that correction the complete fresh ABI-65 target result set contained
204 suites and 2,600 tests with zero failures, errors, or skips: backend
14/207, `dotnet.ir` 1/6, FIR2IR 187/2,259, and integration 2/128. The supported
unqualified target aggregate also passed on the final state. Current commands
and totals remain indexed in `STATUS.md`.

## Remaining boundary

Stage 7 does not prove multiple members, multiple owner-dependent inputs,
generic methods, bounds, properties/defaults, inheritance/diamonds,
value-class payloads, explicit MethodImpl composition, or applying the layout
to Runtime/Stdlib `Map`. Retained-foreign declaration authority and broader
per-value operation routing remain the next consolidation work. Production
cutover and the source-built Stdlib census remain paused.
