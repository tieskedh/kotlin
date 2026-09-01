# Generic-owner physical owner-input operation — 2026-09-01

This archive records the first authoritative argument-bearing natural operation
in the generic-owner physical-value rehearsal. It changes no physical-library
ABI, artifact schema, Runtime/Stdlib surface, production owner representation,
or Kotlin semantics.

## Boundary

Stage 7 had already emitted and executed the structural contract:

```text
Lookup<K, out V>.lookup(K): V?
    -> !V Lookup(!K, out bool isNull)
```

The shared operation model could compose that signature, but final IR value
provenance supplied only receivers. The old route fixpoint consequently sent a
generic helper's exact `Lookup<!T,!T>` call through the guarded semantic
capability even though both its receiver and argument were verifier-visible and
exact.

The bounded authoritative rule is now:

```text
role-specific physical parameter prototype = I<!n,!n>
immutable receiver alias                   = same I<!n,!n>
immutable argument alias                   = !n
selected natural MethodDef input           = STRICT_OWNER_INPUT(!K)
selected natural MethodDef result          = SplitNullable(!V, out bool)
logical member policy                      = no semantic-result requirement
--------------------------------------------------------------------------
emit the natural call with !K and preserve the !V + bool result layout
```

The interface TypeDef, its variance and the constructed entry carrier all come
from BOUND declaration authority. Logical type arguments locate only current-
owner binders; they cannot admit a TypeDef, fabricate an InterfaceImpl edge, or
create `I<object>`. Every ordinary argument must have one final, non-conflicting
physical storage fact and must enter its recorded MethodDef slot without a
representation change.

Exact natural receiver selection is now independent of interface arity. Broad
and open semantic selection deliberately retains its older bounded grammar.
The operation query can therefore prove `Lookup<!T,!T>` without claiming that a
logically widened `Lookup<T, Any?>` has the same natural construction.

## Logical policy remains first

The physical query does not override an explicit semantic-result contract.
Generic-class member plans record that logical policy directly. Published
generic-interface families expose the same decision through their materialized
producer-owned capability slot: a slot marked for object/foreign result
dispatch remains semantic.

Only when that policy is absent may one fully BOUND exact-natural operation
remove an older conservative local semantic target after the final routing
fixpoint. Direct `super`, external families, broad-candidate slots, missing
receiver/argument facts, MethodSpecs and semantic views remain untouched. The
reconciliation changes no IR and selects no new carrier; it merely lets the
authority-selected MethodDef replace a weaker legacy fallback for the same
logical operation.

## Red-to-green and hostile proof

`genericOwnerInlineWidenedTemporary.kt` adds a public admitted two-parameter
interface and a generic helper with two paths:

```kotlin
val exact: InlineLookup<T, T> = source
val keyExact: T = key
exact.lookup(keyExact)

val wide: InlineLookup<T, Any?> = exact
wide.lookup(keyExact)
```

Before the change, the exact operation query was absent. After argument facts
and arity-independent exact selection were added, it reported a BOUND natural
route but exposed a real downstream mismatch: the legacy router still selected
the guarded semantic capability. The authoritative reconciliation makes the
exact call direct-natural.

The widened path is the hostile control. Its physical receiver may still carry
`InlineLookup<!T,!T>`, but its selected Kotlin view is broader. It publishes no
exact operation snapshot and remains semantic. The `T = Int` execution is
especially important: CLR reference variance cannot manufacture
`InlineLookup<int,object>`.

Both paths execute non-null payload and null-flag branches for `Int` and
`String`. The implementations return the received key on the payload branch,
so the runtime proof cannot pass merely because every lookup happens to return
`null`.

The placement comparison additionally requires the constructed typed entry to
produce and store exactly `InlineLookup<!T,!T>`, with BOUND TypeDef authority,
the two current-owner binder references, frozen-entry evidence, and exact
agreement with the live emitter carrier. Both `Int` and `String` substitutions
execute on .NET Framework 4.8 and .NET 10.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -Pkotlin.dotnet.genericOwnerRehearsal=true --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
```

Direct JUnit XML audit found 87 shared physical-value model tests and 85
retained-foreign authority tests, with zero failures, errors, or skips. The
candidate and production-erased matrices each contained four suites and eight
tests—PSI and LightTree on Framework 4.8 and .NET 10—with zero failures,
errors, or skips.

## Remaining boundary

This slice authorizes an operation and its emitted natural call; it does not
materialize the split `!V + bool` pair into one Kotlin local. Local direct-result
transfer therefore remains parameterless. MethodSpec arguments, split-nullable
local/control-flow materialization, remaining entry carriers, null/bottom/
unknown joins, explicit representation-changing conversions, fields, captures,
properties and Runtime/Stdlib migration remain later work.
