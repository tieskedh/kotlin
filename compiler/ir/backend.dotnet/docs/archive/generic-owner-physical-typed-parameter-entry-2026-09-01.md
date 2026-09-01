# Generic-owner physical typed-parameter entry — 2026-09-01

This archive records the first authority-backed regular-parameter entry in the
generic-owner physical-value rehearsal. It changes no physical-library ABI,
artifact schema, Runtime/Stdlib surface, production owner representation, or
Kotlin semantics.

## Boundary

A Kotlin declaration can own more than one physical entry. Its natural typed
MethodDef may receive owner `!T`, while a paired semantic hook receives
`object`. Logical Kotlin source types alone therefore cannot seed a physical
parameter fact, and copying the typed entry environment into the hook would
narrow a genuinely broad value.

The bounded rule is:

```text
role-specific RepresentationPlan parameter = bare current-owner T
physical produced carrier                  = !n
local requested carrier                    = !n
live direct parameter/local read           = same MethodDef-owner !n
----------------------------------------------------------------
retain !n in the immutable local
```

An object parameter in the semantic-hook prototype instead enters as object
with unknown guaranteed views. The rule uses the producer-selected prototype,
physical owner identity, binder index, and live emitter slot. It does not use a
declaration, package, member or parameter name, collection/stdlib identity, or
IR origin. Selected lineage cannot create the entry fact.

The current slice admits only a bare, non-nullable parameter of the current
physical TypeDef and a whole-expression direct storage read. It does not admit
method parameters, nullable or nested carriers, casts/conversions, control-flow
initializers, fields, captures, properties, foreign carriers, or split layouts.
An unconstrained `!T` uses substitution-dependent null encoding and therefore
does not claim a non-null runtime value.

## Evidence

`genericOwnerInlineWidenedTemporary.kt` adds a source-named immutable alias of a
typed `T` parameter and executes it with both `Int` and `String`. The shadow and
emitter comparison require:

- produced and storage carrier `!0` bound to the physical `InlineSelfView`
  TypeDef;
- unknown guaranteed views and maybe-null substitution state;
- authoritative retained-producer local selection; and
- exact agreement with the independently observed live slot.

The existing `ShadowOwner` hostile fixture runs in the same matrix. Its broad
semantic candidate, stars, projections, casts, mutable flow, and unsupported
joins remain semantic/object-domain or unavailable; an exact receiver-derived
local remains exact. This proves that entry precision does not leak from a
typed MethodDef into a semantic hook.

The initial combined run exposed one stale validator from the earlier local-
placement comparison: it still expected a source immutable exact receiver alias
to degrade after the authoritative consumer had deliberately made it exact.
The same failure reproduced on the untouched prior checkpoint. Commit
`0d92249b31` repaired that proof independently before this feature was rebased.

Verification:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun -Pkotlin.dotnet.genericOwnerRehearsal=true --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --tests "*testGenericOwnerPhysicalValueShadow" --no-configuration-cache -q
```

The shared model suite reported 87 tests with zero failures, errors, or skips.
Direct XML audit reported four candidate suites/eight tests and four fresh
production-erased inverse suites/eight tests, also with zero failures, errors,
or skips, across FIR PSI, FIR LightTree, Framework 4.8, and .NET 10.

## Next boundary

Complete exact typed result production and the remaining parameter-entry
compositions through the same authority model. Then admit null, bottom, and
unknown control-flow arms and explicit representation-changing conversions.
Do not advance the source-built Stdlib census by adding another local
recognizer.
