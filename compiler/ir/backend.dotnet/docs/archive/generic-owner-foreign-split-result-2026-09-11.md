# Foreign virtual split-nullable result composition

This bounded rehearsal starts from `9c6cb16d9a`. Current integration belongs
in [`../../STATUS.md`](../../STATUS.md); the durable input/result distinction
belongs in the [physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced boundary

The source-built census's unavailable anonymous-owner construction was
downstream of an earlier owner-admission failure. Diagnostic snapshots showed
`AbstractMap` and `AbstractCollection` blocked by unsupported foreign semantic
overrides. Adding another generated-capture exception would not establish the
missing enclosing physical binder. That diagnostic was removed after its XML
was retained.

A custom `Lookup<K, out V>.find(K): V?` reproduced the admission boundary.
It was retained as a red probe, not accepted by assuming that a strict logical
`K` proves every physical input conversion. A nested semantic value and a
foreign natural construction still require complete input authority. The
broader Common candidate-input contract is independently unproved here.

The selected result-only proof instead uses a fixed Boolean input:

```text
NullableSource<out V>.find(Boolean): V?
Store<K, out V> : NullableSource<V>
    anchor: !0
    value: object   // legal widened writes; one authoritative field
    natural find(bool, out bool): !1
```

Previously, the planner refused this owner because its foreign dispatcher
could not supply the natural slot's extra flag or reconstruct its result.
The baseline consequently had an erased `Store` with no exact
`NullableSource<!V>` view. No collection declaration participates in the proof.

## Correction and boundaries

The mature-target precedent is ordinary bridge adaptation of established
virtual slots: JVM `BridgeLowering` separates signature adaptation from
special candidate-input barriers, and Native `BridgesBuilding` likewise keeps
its barrier policy distinct from forwarding, including nonvirtual super.
Neither provides this CLR payload/flag convention. The change reuses the
existing .NET nullable conversion and does not change shared compiler code.

Fixed input-carrier equality and result layout are independent. The planner
now admits the direct owner-nullable result candidate with its existing
binder-independent fixed inputs. Final emission still validates the physical
owner family, generic constraints, ordinary parameter vectors, semantic slot,
and override probe. The natural call uses its actual MethodDef, including the
physical trailing flag and actual payload carrier. No substituted Kotlin type
is remapped to invent the payload.

When the probe detects a later ordinary C# override, the dispatcher allocates
a private Boolean local, passes its address, and invokes the natural slot.
The existing split-nullable expression conversion reconstructs the semantic
result from that payload/flag pair. No new conversion implementation, combined
member role, runtime helper, reflection search, or physical schema is added.

When the probe selects the Kotlin implementation, dispatch still calls its
authoritative semantic body directly. Forcing it through the typed wrapper
would narrow state after a legal widened write. The unrelated `!K` field,
receiver identity, and one semantic field remain unchanged. Ordinary exact
calls retain the typed payload calling convention; semantic reconstruction
boxes a value payload only at the existing object-result boundary.

Owner-dependent/broad inputs, refined or nested split-result admission, and
owner-relative MethodSpec/split composition remain outside this slice. Existing
negative fixtures must remain erased. This does not close `Map.get`, the
source-built Stdlib, open-nullable captured-getter routing, or the production
cutover.

## Executable evidence

`genericOwnerForeignSplitResult.kt` contains separate Kotlin producer,
subclass, and consumer modules. Its checks include legal widened state writes,
exact and semantic reads, interface calls, identity, nullable key/payload,
reference and nominal value-class substitution, and Kotlin override/super.

An ordinary separately compiled C# consumer overrides only the natural
methods. It covers a direct subclass, a C# grandchild, a separately compiled
generic Kotlin intermediate class, and an overriding Kotlin intermediate
class. Exact, class-semantic, and interface-semantic calls must agree;
nonvirtual Kotlin `super` must still run the base body. A nondefault payload
with a true null flag must be ignored, while an empty nullable payload with a
false flag must still materialize null. A nominal value-class result must
preserve its actual object identity and nominal MethodDef carrier.

The PE reader requires public virtual `!1 find(bool, [out] bool&)`, a typed
`!0` anchor FieldDef, and exactly one object-domain value FieldDef. CLR
InterfaceMap checks bind the natural result and flag to the expected virtual
base slot. No C# declaration implements hidden Kotlin ABI.

## Verification

Development evidence is under `D:\CodexTemp\foreign-split-result-20260911`:
the initial owner-input source/XML, the fixed-input red baseline, and the
expanded C# green XML. The final candidate passed four suites and 36 tests at
17:15:04 local time; the complete backend passed 22 suites and 402 tests at
17:15:09. The same production inverse passed four suites and 36 tests at
17:18:29. All have zero failures, errors, or skips. XML is retained as
`candidate-36.zip`, `backend-402.zip`, and `inverse-36.zip`. SHA-256 checks
confirmed that all five changed compiler/test sources were unchanged across
these gates. The physical model's six tests and integration's 128 tests remain
the inherited full checkpoint's evidence, not fresh runs in this lane.

The selected matrix uses `:compiler:fir:fir2ir:dotNetTest --rerun` with:

```text
--tests '*testGenericOwnerForeignSplitResult'
--tests '*testGenericOwnerForeignOverrideSeparateCompilation'
--tests '*testGenericOwnerSplitNullableResultSeparateCompilation'
--tests '*testGenericOwnerAbstractForeignOutput'
--tests '*testGenericOwnerForeignScalarResult'
--tests '*testGenericOwnerFixedCarrierMultiInput'
--tests '*testGenericOwnerSemanticIteratorResult'
--tests '*testGenericOwnerExactInterfaceInputsSeparateCompilation'
--tests '*testValueClassSeparateCompilation'
```

Both commands use `--max-workers=1 --no-configuration-cache -q`; only the
candidate adds `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The complete
backend suite uses `:compiler:backend.dotnet:test --rerun`. The scoped generator
registered the new fixture in all four parser/profile runners.

The compiler delta is restricted to rehearsal owner-admission candidates and
an emitter path consuming rehearsal-only dispatch records. The new shared
conversion entry delegates without modifying existing conversion behavior.
The production inverse requires absence of rehearsal epoch records and generic
TypeDefs in this fixture family. The inherited full checkpoint remains
`17676a90e1` (2,863 tests); this is not a new full aggregate or ABI epoch.
