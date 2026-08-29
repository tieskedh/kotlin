# Local recorded MethodDef dispatch checkpoint (2026-08-29)

This archive record preserves the bounded rehearsal checkpoint which replaced
runtime public-name/arity selection for one same-producer complete-natural
generic-interface route with an exact CLR MethodDef token. It is evidence, not
the general external-consumer ABI and not a production cutover.

## Scope

The exercised producer owns the logically covariant, physically invariant
interface:

```kotlin
interface CompleteNaturalContract<out T> {
    fun fetch(): T
    fun accept(value: @UnsafeVariance T)
}
```

`CompleteNaturalReader` is emitted in that same producer. Its foreign branch
therefore has final local authority for the natural interface MethodDefs. This
checkpoint deliberately does not let a downstream compiler reconstruct an
authoritative token from ABI-62 KLIB or function records.

## Physical route

The producer emits the exact open declaration operand:

```text
ldtoken method instance !0 CompleteNaturalContract`1::fetch()
ldtoken method instance void CompleteNaturalContract`1::accept(!0)
```

At runtime the helper:

1. selects the unique already-implemented closed construction of the recorded
   open interface on the receiver;
2. binds the open MethodDef handle to that construction with the mandatory
   two-handle `MethodBase.GetMethodFromHandle` overload;
3. applies recorded method-generic arguments only after that binding;
4. validates ordinary arguments against the bound physical parameter types;
5. invokes the interface `MethodInfo`, leaving ordinary and explicit
   `MethodImpl` selection to the CLR; and
6. interprets direct/void versus split-nullable result layout independently
   from parameter-domain policy.

No custom marker, concrete-method scan, public member name, regular arity, or
interface enumeration order identifies the MethodDef.

## Input boundary found by the hostile proof

Reflection would otherwise coerce `null` to `default(Int32)` for an `Int32`
parameter, and an incompatible reference/value argument could escape as
`ArgumentException`. The helper now validates before `Invoke`:

- `null` is admitted only by a reference or `Nullable<T>` parameter;
- a non-null value must satisfy the bound parameter type, with the CLR boxed
  `Nullable<T>`/underlying-`T` convention handled explicitly; and
- a widened owner-dependent mismatch fails with `InvalidCastException` before
  the implementation or any decoy can observe or mutate state.

The domain vector comes from the same structural variance analysis used by the
producer's admitted generic-owner family plan and is frozen at interface
admission. Missing or arity-mismatched domain authority leaves the token route
unavailable. A late signature comparison and logical source variance alone are
not runtime authority.

## Executable evidence

The generator-free C# implementation assembly is compiled separately from the
Kotlin producer. It contains both:

- an ordinary `CompleteNaturalContract<string>` implementation; and
- an explicit-interface implementation with a private final CLR MethodImpl.

The consumer verifies exact and widened reads/writes, one receiver identity,
unchanged authoritative state after invalid input, exact interface maps, and
zero observation by concrete public decoys. Producer IL is also inspected for
both exact `ldtoken method` operands and the absence of `InvokeUniqueMember` and
member-name string operands in the reader.

The focused candidate and production-erased inverse passed under both FIR
parsers on .NET Framework 4.8 and .NET 10. The final aggregate counts are owned
by `STATUS.md` at this checkpoint.

## Authority and rollback boundary

- The token route requires a `TYPED_ENTRY` identity for the exact source
  function and an owner in the module currently being emitted.
- Reconstructed ABI-62 external function information cannot authorize it.
- Runtime surface level is 60; Kotlin library ABI remains 62.
- Production remains erased and emits no call to `InvokeRecordedMember`.
- One receiver/object identity and one authoritative state are unchanged; no
  wrapper, proxy, adapter object, or shadow state was added.

## Remaining stage-5 work

This is only the first half of MethodDef/MethodImpl routing. ABI 63 must publish
one exact natural MethodDef descriptor per logical member, including an
orthogonal direct/void/split-nullable result layout, so a separate consumer can
emit the same producer-owned token without reconstruction. That slice must add
the hostile same-name/same-regular-arity interface-MethodDef family which
behaviorally distinguishes token identity from the legacy selector, plus
generic MethodDef, owner-dependent input, split-nullable, cross-assembly,
trimming, and NativeAOT evidence. Stage 5 remains open until those producer
records and inverse gates pass.
