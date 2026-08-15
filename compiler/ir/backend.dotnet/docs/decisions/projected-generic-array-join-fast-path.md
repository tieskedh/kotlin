# Exact-vector fast path for projected generic array joining

- Status: **Accepted pre-ABI**
- Date: 2026-08-15
- Scope: Common `Array<out T>.joinTo` and `joinToString`
- Does not change: Kotlin cast semantics, array identity, generic-owner ABI,
  KLIB, Runtime surface, or the public physical signature

## Context

The public receiver of `Array<out T>.joinTo` must admit every Kotlin-valid
projected view. A CLR value vector cannot be covariantly converted to a vector
of a wider reference element, so the truthful public physical capability is
`System.Array`, not `T[]`. The first Common-aligned implementation therefore
loaded every element through `System.Array.GetValue` and converted it back to
the method's `T` before calling the ordinary Common `appendElement` path.

That fallback is required for a value array observed through a wider view,
for example `Array<Int>` as `Array<out Any?>`. It is unnecessarily erased for
the common case where the physical vector is already compatible with the
method's exact CLR `T[]`. For `T = Int`, `GetValue` creates a box which is then
immediately unboxed before `appendElement<Int>` performs its normal rendering.

## Decision

The public method remains:

```text
joinTo<T, A>(System.Array receiver, ...): A
```

At method entry it performs one non-throwing CLR compatibility probe:

```text
receiver isinst T[]
```

When the probe succeeds, the Common algorithm iterates that same vector with
`ldelem T`. When it fails, the existing `System.Array.GetValue` algorithm runs
unchanged. Both arms retain Common's prefix, separator, limit, truncation,
transform, postfix, buffer-identity, evaluation, and failure behavior.

The probe is an internal implementation selection, not a Kotlin type test or
cast. It cannot make a valid Kotlin operation throw and does not expose a
stronger CLR result. A widened value vector fails the probe and uses the
semantic fallback. CLR covariance may let a widened reference vector pass;
that is safe because joining only reads elements and the exact `T` accepts
every value readable from the compatible vector.

The private source declaration which expresses the probe is a bodyless
backend intrinsic and is excluded from CLR code generation. No helper
MethodDef, public symbol, Runtime operation, or library ABI entry is emitted.

## Generic-owner boundary

An ordinary Kotlin generic class still has one canonical non-generic CLR
owner. An owner-dependent `Array<T>` field remains authoritative
`System.Array` state. Passing that field to `joinTo` does not reify the owner
or create shadow state: the method probes the actual vector once and either
uses its compatible typed read capability or retains the erased fallback.

This is incremental use of an existing method-generic CLR token. It supplies
evidence that typed physical operations can coexist with the accepted erased
owner, but it is not production admission of a Kotlin-owned `C<T>`.

## ABI and semantic consequences

KLIB remains the Kotlin authority and the public CLR receiver remains
`System.Array`. C# callers can continue to supply both exact vectors and
Kotlin-valid widened value-vector views through that capability. The fast
path changes only the implementation body selected after the call begins.

The implementation does not rely on Kotlin's platform freedom for unchecked
casts: no cast outcome or failure point changes. CLR RTTI is used only to pick
an equivalent read path after the Kotlin operation has already been admitted.

## Verification obligations

Completion requires:

- exact value, nullable-value, reference, and method-generic arrays;
- widened value views which must use the fallback;
- erased-owner nullable value arrays;
- transform count and failure identity, limits, live reads, buffer identity,
  and all Common rendering rules;
- PSI and LightTree execution on Framework 4.8 and .NET 10;
- physical IL proving the public `System.Array` receiver, `isinst T[]`,
  `ldelem T`, and retained `GetValue` arm;
- absence of the private probe or an extracted exact-loop helper from CLR
  metadata;
- checksum-identical causal allocation evidence on both supported runtimes;
  and
- the complete strict target gate.

## Rejected alternatives

Always retaining `System.Array.GetValue` is correct but boxes every exact
value element solely because the public capability must also admit widened
views.

Changing the public receiver to `T[]` is rejected because it cannot represent
valid widened value-array calls and would make CLR compatibility override
Kotlin semantics.

Using a full constructed-generic CLR cast as a Kotlin `as` or `as?` result is
outside this decision. Those operations remain governed individually by the
Kotlin semantic-authority decision.

Applying the optimization to arbitrary user loops is deferred. The shared
Common for-loop lowering owns array iteration and a target post-pass would
need to duplicate arbitrary loop bodies while preserving labeled
`break`/`continue`, temporaries, and control-flow identity. That larger change
needs its own cross-target design and evidence.
