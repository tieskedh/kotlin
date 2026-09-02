# Generic-owner fixed-input split-nullable placement — 2026-09-02

This archive records the first exact natural split-result operation whose
ordinary parameter vector composes strict owner binders with fixed,
declaration-independent CLR leaves. It changes no physical-library ABI,
generic-owner artifact schema, Runtime/Stdlib declaration, production owner
representation, or Kotlin semantics.

## Boundary

The admitted empty-MethodSpec form is one complete ordered vector in which
every slot is independently one of:

```text
STRICT_OWNER_INPUT       selected natural TypeDef !n
                         -> instantiated current-owner !m

DECLARATION_INDEPENDENT  bool | int32 | string | object
                         -> the identical fixed CLR leaf
```

The result remains independently
`SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool)`. Natural and semantic
MethodDefs must publish the same fixed leaf at the same ordinal; a source type
alone cannot establish that fact. A fixed leaf is neither TypeDef nor MethodDef
binder authority. Any MethodSpec combined with a fixed leaf remains unavailable.

Final value analysis seeds regular parameters from the role-specific physical
entry signature. The operation consumer reuses only a final regular-parameter
fact whose producer-planned slot domain is `DECLARATION_INDEPENDENT`, whose
typed and current physical prototypes carry the same supported fixed leaf, and
whose final storage is `Direct(Fixed(the same leaf))`, alongside immutable-local
facts. Owner, constructed, broad semantic-object, fallback-object, and
MethodDef-binder facts remain excluded. This closes a general composition bug
found by the hostile fixture: value transfer correctly predicted the split
result, but operation routing previously rebuilt its environment from locals
only and therefore forgot eligible direct fixed-leaf parameter carriers.
Dispatch-receiver entry facts were not added to that hand-off, so this slice
does not widen `this`-based routing.

The late emitter binds the complete open and instantiated parameter vectors,
requires fixed-leaf equality, validates every live argument in order, and
passes only the private final Boolean slot as the nested null-flag address.
Owner slots still require authenticated current-owner parameters. No logical
destination type, wrapper, proxy, fabricated construction, semantic fallback,
boxing, cast, or nullable materialization participates.

## Executable evidence

`genericOwnerFixedCarrierMultiInput.kt` defines:

```kotlin
interface FixedCarrierLookup<K, out V> {
    fun lookup(
        first: K,
        selectFirst: Boolean,
        token: Int,
        label: String,
        marker: Any?,
        second: K,
    ): V?
}
```

The exact natural contract and owner call are:

```text
!1 FixedCarrierLookup<!0,!1>.lookup(
    !0, bool, int32, string, object, !0, out bool
)

callvirt instance !1 class FixedCarrierLookup<!0,!0>::
    lookup(!0, bool, int32, string, object, !0, bool&)
```

The end-to-end fixture passes Boolean, Int, String, and nullable Any?/`object`
parameters directly, while the two owner values use independently retained
`!T` locals. The result local is emitted as `!T` plus a private Boolean flag.
Exact IL assertions exclude `box`, `unbox`, `castclass`, `isinst`, semantic
dispatch, and recorded-member fallback from that route. Runtime cases cover
`Int`, `String`, and `Int?`, both owner-input positions and Boolean branches,
matching and mismatching Int/String inputs, nullable object null/non-null, and
a selected null payload.

A hostile `<R>(K, Boolean, Int, String, Any?, K, R): V?` interface remains erased and
publishes neither a candidate generic TypeDef nor a semantic capability. The
model test independently validates Boolean, Int32, String, and Object fixed
leaves in the six-slot end-to-end domain/carrier vector and rejects wrong
Boolean, Int, String, owner, missing, and extra arguments. The hostile
already-admitted MethodSpec direct constructed receiver/owner-parameter route
in `genericOwnerInlineWidenedTemporary.kt` remains excluded from this hand-off;
admitting it would be a scope regression.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerFixedCarrierMultiInput" --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerFixedCarrierMultiInput" --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The physical-value model contains 93 green tests. Candidate and fresh
production-erased inverse each passed eight tests: two fixtures across PSI and
LightTree on .NET 10 and Framework 4.8, with zero failures, errors, or skips.
The inverse emitted no candidate generic TypeDef, semantic capability, generic
implementation owner, retained operation, or split-result local.

This slice adds local same-compilation candidate/inverse evidence only. It
adds no separate-Kotlin-assembly or C# proof.

## Next boundary

Repair and pin direct constructed-parameter entry placement so it validates the
live storage-read carrier rather than a reconstructed whole-expression carrier.
Only then expand constructed entry forms. The real caller-MethodDef `!!R` entry
and broader MethodSpecs remain separate later proofs. Do not resume the paused
Stdlib census or add a Boolean, Map, declaration-name, package, member-name,
IR-origin, or collection exception.
