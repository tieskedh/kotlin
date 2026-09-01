# Generic-owner physical MethodSpec operation — 2026-09-01

This archive records the first final-IR consumer which supplies a MethodSpec
vector to the shared generic-owner physical-operation query. It changes no
physical-library ABI, artifact schema, Runtime/Stdlib surface, production owner
representation, or Kotlin semantics.

## Boundary

Earlier checkpoints already established two independent facts:

- a selected physical MethodDef records its own generic arity and the emitter
  can construct natural and semantic MethodSpecs; and
- the shared operation model substitutes TypeDef and MethodDef binders
  independently when its caller supplies an authenticated MethodSpec vector.

The final-IR operation consumer nevertheless omitted every generic MethodDef.
It therefore could not prove that an exact owner-bound call used the same
physical MethodDef, receiver, value argument, MethodSpec argument, and result
carrier which codegen ultimately emitted.

The bounded rule is now:

```text
selected physical MethodDef arity       = 1, from BOUND authority
final IR MethodSpec vector              = logical T
current physical caller owner           = Route<T>
BOUND caller TypeDef parameter          = !T
exact receiver                          = Producer<!T>
ordinary argument                       = !T
recorded callee signature               = <!!R>(!!R): !T
----------------------------------------------------------------
MethodSpec vector                       = <!T>
instantiated call                       = < !T >(!T): !T
```

The final IR type argument only locates a type parameter of the current
physical owner. The sealed declaration index proves the corresponding CLR
`!n`; the IR vector neither establishes the selected MethodDef's arity nor
creates a physical binder. The whole vector must agree with the recorded
arity before the operation query runs.

This first consumer accepts only a non-null bare parameter of the current
generic class. Concrete, nullable, nested, retained-foreign, caller-MethodDef,
and constrained substitutions remain unavailable. Constraints are not
silently ignored: the shared operation model still refuses any constrained
MethodDef until an authority-backed constraint-satisfaction proof exists.

## Logical route remains authoritative

An exact natural receiver may use this vector only after logical receiver
selection has chosen the natural interface and final value facts admit the
receiver and ordinary argument. A logically widened receiver still selects
the semantic capability, even when the underlying object retains an exact
natural construction and the MethodSpec argument itself is `!T`.

The operation snapshot now publishes the complete bound MethodSpec carrier
vector. This distinguishes a genuinely proven `!!0 := !T` instantiation from
a generic call which happened to execute through the expected final routing
maps. An unavailable or conflicting route publishes no MethodSpec carrier
evidence.

## Red-to-green and hostile proof

`genericOwnerInlineWidenedTemporary.kt` adds the structural family:

```kotlin
interface InlineMethodProducer<out T> {
    fun <R> produce(marker: R): T
}
```

An exact `InlineMethodProducer<T>` alias invokes `produce<T>` with an exact
`T` marker. Before the change, the operation consumer deliberately skipped the
generic MethodDef. Afterwards it reports one BOUND/MATCH natural operation and
one MethodSpec argument carrier: owner parameter zero of
`InlineMethodProducerRoute<T>`.

Two controls prevent overclaiming:

- `InlineMethodProducer<Any?>` remains a broad semantic receiver. Its operation
  evidence is explicitly unavailable and its existing guarded semantic route
  remains selected.
- `routeCallerMethodArgument<R>` invokes `produce<R>`. The equally numbered
  caller-MethodDef parameter is not current-owner `!T` authority, so this
  bounded consumer publishes no route claim while ordinary codegen still
  executes the call.

Value (`Int`) and reference (`String`) owner substitutions execute both exact
and widened calls. The implementations validate the marker as well as the
result, so the proof exercises the instantiated MethodSpec input and cannot
pass from a coincidental return value alone. The caller-MethodDef hostile call
also executes.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --tests "org.jetbrains.kotlin.backend.dotnet.DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

Direct JUnit XML audit found 87 shared physical-value model tests and 85
retained-foreign authority tests, with zero failures, errors, or skips. The
candidate and production-erased matrices each contained four suites and four
tests—PSI and LightTree on Framework 4.8 and .NET 10—with zero failures,
errors, or skips.

## Remaining boundary

This slice authorizes one exact operation; it does not grant a local-placement
token to its result. Caller-MethodDef, concrete, constrained, nullable, nested,
foreign, and multiple MethodSpec carriers still need their own authority
grammar. A method-generic declaration combined with a split-nullable owner
result is also not yet admitted by the declaration rehearsal, so
`<R>(K, R): V?` must not be presented as a closed composition. Split-nullable
payload/flag materialization across locals and control flow, the remaining
parameter-entry carriers, fields, captures, properties, and Runtime/Stdlib
migration remain later work.
