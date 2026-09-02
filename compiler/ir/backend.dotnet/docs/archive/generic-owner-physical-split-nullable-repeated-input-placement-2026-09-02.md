# Generic-owner repeated-input split-nullable placement — 2026-09-02

This archive records the first repeated ordinary-input vector carried into an
exact natural `SplitNullable` pair, together with a consumer implementation
which has no zero/one cardinality branch. It changes no physical-
library ABI, artifact schema, Runtime/Stdlib declaration, production owner
representation, or Kotlin semantics.

## Boundary

The IR-free physical-operation query and late call emitter already consumed a
complete ordered argument vector. Publication, final value transfer, and the
retained pair token nevertheless admitted either zero/one ordinary input or the
separate exact `<R>(K, R): V?` MethodSpec shape.

The non-MethodSpec rule is now:

```text
selected MethodDef       = exact natural interface member
ordinary parameter count = N, with no cardinality limit
open domains             = N * STRICT_OWNER_INPUT
open carriers            = selected TypeDef parameters
instantiated carriers    = current physical-owner parameters
live arguments           = N exact direct storage reads, checked in order
result                    = SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool)
```

The first declaration-publication slice requires `N > 0` direct occurrences of
the same unique invariant input parameter and one distinct covariant nullable
result parameter. That remains a cautious declaration grammar; the operation,
placement, and emitter consumers retain and validate the complete vector and do
not branch on `N`. The executable rehearsal proves the first genuinely
multi-input instance at `N = 2`; it does not claim that every arbitrary length
has separate runtime, foreign, or C# evidence.

Every argument is proved independently by the shared operation query. One
object/broad/mismatched argument makes the whole operation unavailable. The
placement witness retains the open and instantiated parameter vectors together
with MethodDef identity, receiver construction, result layout, and exact call
identity. Late emission re-resolves the live MethodDef and compares every
argument carrier before passing the private Boolean local by address.

Declaration-independent slots, repeated owner inputs combined with a MethodSpec,
MethodDef parameters beyond the existing exact
`<R>(K, R)` slice, nullable/nested inputs, semantic receivers, conversions,
fields, captures, and general pair consumers remain unavailable. No fabricated
construction, wrapper, proxy, shadow state, or semantic fallback is introduced.

## Executable evidence

`genericOwnerInlineWidenedTemporary.kt` adds the structural declaration:

```kotlin
interface InlineRepeatedInputLookup<K, out V> {
    fun lookup(first: K, second: K): V?
}
```

`InlineRepeatedInputSplitLocalRoute<T>` passes two distinct immutable `!T`
locals through an exact `InlineRepeatedInputLookup<T,T>` receiver and forwards
the logical `T?` result through one retained payload/null-flag pair. The nested
implementation returns `null` for equal inputs and otherwise returns the second
input, so swapped, dropped, or duplicated loads cannot pass coincidentally.

The candidate MethodDef contains the exact operand:

```text
callvirt instance !1 class 'InlineRepeatedInputLookup`2'<!0,!0>::
    'lookup'(!0,!0,bool&)
```

Both argument loads precede the private final flag address. The result is stored
as outer `!0` plus `bool`; the method contains no `System.Nullable`, semantic
capability, `box`, `unbox.any`, `castclass`, or `isinst` materialization.
Runtime cases cover distinct/equal `Int`, distinct `String`, and `Int?` with a
null first or both-null inputs.

A hostile `<R>(K, K, R): V?` declaration remains outside the candidate natural
family. Both producer classification and producer-record consumption preserve
the earlier single-owner-input MethodSpec boundary; the emitted candidate IL
must contain neither a natural generic TypeDef nor a semantic capability for
that unproved mixed shape.

The shared model test now uses two repeated strict slots and rejects a route
whose second argument alone has the object carrier. The initial candidate run
failed exactly because no operation snapshot existed for the repeated-input
call; after replacing the four count-specific guards with the full-vector rule,
the same validator and runtime proof passed.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The physical-value model remains 92 tests with zero failures, errors, or skips.
Candidate and fresh production-erased inverse each passed PSI and LightTree on
.NET 10 and Framework 4.8. The inverse emitted no repeated-input candidate
TypeDef, semantic capability, generic implementation owner, or retained pair
flag.

## Next boundary

Seed fixed declaration-independent parameter carriers from role-specific
physical signature authority and validate their live slots. Then admit the real
caller-MethodDef `!!R` entry/MethodSpec carrier. Do not resume the paused
Stdlib static-initialization blocker or add a Boolean, Map, declaration-name,
package, member-name, or IR-origin exception.
