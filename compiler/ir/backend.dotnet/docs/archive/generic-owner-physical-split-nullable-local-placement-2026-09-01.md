# Generic-owner physical split-nullable local placement — 2026-09-01

This archive records the first bounded local transport of an exact natural
`SplitNullable(!T, out bool)` result in the generic-owner physical-value
rehearsal. It changes no physical-library ABI, artifact schema, Runtime/Stdlib
surface, production owner representation, or Kotlin semantics.

## Boundary

Callable authority and operation routing already preserved a split nullable
result as a typed payload plus Boolean null flag. An ordinary Kotlin local still
forced that pair through the general logical `T?` materializer, which boxed an
open value-type payload even when the local only forwarded the result to an
enclosing MethodDef with the identical split layout.

The bounded admission rule is:

```text
initializer route          = exact natural SplitNullable(!n, bool)
callee                      = parameterless, non-MethodSpec MethodDef
logical local               = immutable T?
local uses                  = one read, directly returned to this MethodDef
return region               = outside try/catch/finally
enclosing result            = identical SplitNullable(!n, bool)
live emitter result         = identical owner-bound !n
------------------------------------------------------------------
local storage               = private !n payload + private bool flag
```

The emitter passes only the private flag local by address to the nested virtual
call. On the direct return it copies that flag into the enclosing final
`out bool` and loads the payload. It never forwards the caller-owned address to
the virtual callee, and it emits no nullable materialization, boxing, proxy,
wrapper, field, or shadow state.

Arguments, MethodSpecs, `super` or semantic routes, mutation, joins, captures,
multiple reads, protected-region returns, and carrier disagreement receive no
pair-retention token. They keep the ordinary materializing path.

## Creation-site member authority

The executable fixture exposed a declaration-authority gap rather than a
nullable-layout gap. Executable compilations deliberately have no serialized
pre-lowering linkage-key table, while late operation binding had begun to
reconstruct a local member relation independently. The result call could bind
to its physical split MethodDef, but the enclosing locally published MethodDef
was not recognized as split.

Family publication now binds every local source member bijectively, by IR
declaration identity, to its exact published physical member contract. Local
signature and operation consumers use that creation-site relation. Separate
consumers continue to use only the validated producer record. Names, arity,
declaration order, member shape, and a sibling contract never select the slot.

## Executable and hostile evidence

`genericOwnerInlineWidenedTemporary.kt` exercises non-null and null results for
`T = Int`, `T = String`, and `T = Int?`. The emitted
`readThroughLocal` MethodDef contains one natural call with the physical shape:

```text
callvirt instance !0 class 'InlineSplitLocalProducer`1'<!0>::'read'(bool&)
```

Its isolated CIL contains neither `box` nor `unbox.any`, no
`System.Nullable` materializer, and no semantic-capability call.

Two negatives exercise the fail-closed boundary. Passing the local to an
ordinary helper materializes the logical nullable value. Returning it from a
`try`/`finally` also uses the ordinary path and executes the `finally` exactly
once. A two-owner-parameter placement-policy hostile proves that an inner `!B`
pair is not retained for an enclosing `!A` split MethodDef merely because both
layouts are split. Model tests also reject direct, carrierless-null, and
mismatched-payload writes into split storage, reject joins and overwrites, and
admit only one direct unprotected return.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalValueModelTest" -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*FirPsiDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*FirLightTreeDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirPsiDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*FirPsiDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirPsiDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
```

The shared physical-value model reported 90 tests with zero failures, errors,
or skips. Direct JUnit XML audit reported four candidate tests and four fresh
production-erased inverse tests across FIR PSI, FIR LightTree, .NET 10, and
Framework 4.8, each with zero failures, errors, or skips.

## Next boundary

Generalize split-pair placement only through independently proven use and
destination layouts: argument-bearing or MethodSpec calls, multiple consumers,
and control-flow joins. Keep public callable layout and producer-wide state
authority separate. Do not advance the source-built Stdlib census with a
member-, package-, collection-, Map-, or IR-origin-specific exception.
