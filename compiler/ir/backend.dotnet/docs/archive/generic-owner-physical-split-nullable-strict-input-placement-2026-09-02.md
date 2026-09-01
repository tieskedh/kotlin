# Generic-owner strict-input split-nullable placement — 2026-09-02

This archive records the first bounded local transport of an argument-bearing
exact natural `SplitNullable` operation in the generic-owner rehearsal. It
changes no physical-library ABI, artifact schema, Runtime/Stdlib declaration,
production representation, or Kotlin semantics.

## Boundary

The previous local consumer retained only a parameterless split result. The
operation model already proved ordinary inputs independently from result layout,
but a payload-only placement token could not authenticate which overload,
receiver construction, or parameter vector the emitter would invoke.

The new admitted form is:

```text
final logical route       = EXACT_NATURAL
final physical route      = BOUND + DIRECT_NATURAL + MATCH
ordinary parameters       = exactly one STRICT_OWNER_INPUT(!m)
MethodSpec vector         = empty
result                    = SplitNullable(STRICT_OWNER_OUTPUT(!n), bool)
logical local             = immutable T?
local uses                = one read, directly returned to this MethodDef
return region             = outside try/catch/finally
enclosing result          = identical SplitNullable(!n, bool)
```

The final operation consumer publishes an identity-keyed witness only after
declaration authority, receiver/argument provenance, and the completed router
agree. This commit makes that witness mandatory for both the original
parameterless pair and the new one-argument form. Placement retains the whole
operation: MethodDef identity, required
receiver construction, instantiated ordinary parameter vector, empty MethodSpec
vector, and result layout. A diagnostic snapshot, logical type, route-census
absence, or payload carrier alone cannot authorize emission.

The late emitter independently:

- re-resolves the exact local physical MethodDef and virtual call;
- requires one unique recorded view of its declaring generic interface from
  both the resolved and direct live receiver carriers;
- compares the complete verifier-visible receiver and parameter vector;
- requires a direct storage read with the exact `!m` argument carrier;
- rechecks the split payload and hidden final Boolean parameter; and
- uses a compiler-private Boolean local as the nested call's null-flag address,
  never the enclosing caller's `out bool` address.

Ambiguous receiver constructions remain unavailable. Multiple ordinary
arguments, MethodSpecs, semantic or `super` routes, indirect/computed arguments,
mutation, joins, captures, multiple reads, protected returns, and carrier
disagreement remain on the ordinary materializing path.

## Executable evidence

`genericOwnerInlineWidenedTemporary.kt` uses the already admitted structural
`InlineLookup<K,V>` family. An anonymous exact owner supplies one
`InlineLookup<T,T>` receiver, an immutable `!T` local supplies the argument, and
the returned logical `T?` is forwarded through one retained payload/flag pair.

The isolated outer MethodDef contains:

```text
.locals init (
  [0] class 'InlineLookup`2'<!0, !0> 'sourceNaturalAlias',
  [1] !0 'exactArgumentAlias',
  [2] !0 'exactResultAlias',
  [3] bool 'exactResultAlias@isNull'
)

ldloc.0
ldloc.1
ldloca 3
callvirt instance !1 class 'InlineLookup`2'<!0, !0>::'lookup'(!0, bool&)
stloc.2
```

The `!1` result is deliberate: it is the interface MethodDef's independent `V`
binder, while the exact constructed owner binds both `K` and `V` to the outer
owner's `!0`. The payload local therefore remains outer `!0` without erasing the
declaration's independent input/output positions. The method contains no
`System.Nullable` materializer, semantic-capability crossing, `box`, or
`unbox.any`.

Runtime coverage includes `T = Int`, `T = String`, and `T = Int?` with a null
argument/result. The test validator also requires the authoritative exact
operation snapshot, retained pair placement, the exact named payload/flag slots,
and the precise `ldloca` slot passed to the nested call.

This executable class-owner proof uses one outer parameter for both interface
arguments because the current rehearsal does not yet admit the needed broader
two-parameter generic-class/interface inheritance shape. Independent `!K` input
and `!V` split-result composition is already covered by callable-contract,
MethodDef-emission, producer/consumer, and separate-compilation proofs. A
different outer `<K,V>` executable owner remains a later inheritance/admission
gate rather than an exception in this feature.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:compileTestKotlin -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalValueModelTest" -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*FirPsiDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*FirLightTreeDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirPsiDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*FirPsiDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirPsiDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" --tests "*FirLightTreeDotNetFrameworkBoxTestGenerated*testGenericOwnerInlineWidenedTemporary" -q
```

The physical-value model reported 90 tests with zero failures, errors, or
skips. Candidate and fresh production-erased inverse each passed four test
methods across FIR PSI, FIR LightTree, .NET 10, and Framework 4.8. The inverse
published no generic-owner operation or placement authority and retained the
production-erased metadata surface.

## Next boundary

Generalize pair placement through another independently authenticated operation
dimension: MethodSpec or multiple ordinary arguments. Multiple consumers and
control-flow joins require separate use/storage policies. Do not advance the
Stdlib census or add a Map/member/package/IR-origin recognizer for any of them.
