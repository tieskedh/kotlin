# Generic resized array-copy correction — 2026-08-15

This immutable checkpoint records the correctness audit and implementation
evidence for Common `Array<T>.copyOf(newSize): Array<T?>`. Current normative
rules remain in `AGENTS.md`, the primitive-array ADR, and the open-nullable
array/vararg ADR.

## Failure found

The Sequence builder closure first admitted the exact Common generic resized
copy declaration. Its original intrinsic always allocated from the source
vector's runtime component. That is correct for non-null copies and reference
padding, but not when a non-null CLR value element becomes nullable.

The first direct test exposed the hard failure in all four PSI/LightTree and
Framework 4.8/.NET 10 lanes:

```text
Array<Int> / int32[]
    -> copyOf(3): Array<Int?>
    -> retained int32[]
    -> InvalidCastException to Nullable<int32>[]
```

An output-projected `Array<out Int>` exposed the non-throwing form of the same
bug: the retained `int32[]` padded with zero, so Kotlin observed `0` instead of
null. An open `Array<out T?>` with `T = Int` had the same semantic failure.

## Selected physical rules

For closed non-null value `V`, resized copy allocates the truthful
`Nullable<V>[]`. The copied prefix uses typed `ldelem V`, direct
`Nullable<V>` construction, and typed `stelem Nullable<V>`. The fresh suffix
is the empty nullable value. There is no boxing, `System.Array.SetValue`, or
whole-vector object conversion. All eight Kotlin scalar value families execute
this one physical rule.

An output-projected/open result already has the physical `System.Array` read
ABI. Equal-sized and truncating copies preserve the exact component because
they add no suffix. Growing reference vectors and vectors already storing
`Nullable<V>` also preserve their exact component. Only a growing non-null
value vector uses a new `object[]`; `System.Array.Copy` boxes its copied prefix
and the fresh suffix remains null. The copy already requires a new identity,
so this does not alter an existing view or source alias.

FIR captures direct `Array<out Any?>.copyOf(...)` results as the singular
bottom input `Array<in Nothing?>`. That shape maps to the same `System.Array`
read capability, including indexed `Any?` reads. It does not enable a write
carrier: a dedicated source sentinel writes null through the capture and its
containing function remains omitted from the IL golden. Every non-bottom input
projection remains rejected.

No public physical signature, runtime helper, runtime-surface level, library
codec, or KLIB logical contract changed.

## Verification

Focused evidence covered:

- all eight closed scalar value families with non-null prefix and null suffix;
- invariant and output-projected `Int` copies;
- open projected non-null value, widened `Any?`, reference, and nullable-value
  substitutions;
- exact reference/nullable/non-growing component retention;
- deterministic PSI/LightTree IL for the boxing-free closed loop and open
  runtime branch;
- unchanged rejection of bottom-capture null writes and open invariant
  nullable writes;
- the complete Sequence/RingBuffer consumer; and
- the three historical declaration-eviction goldens on both frontends.

The strict aggregate command exited with code 0:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct XML audit of its three result roots:

```text
dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,085 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,216 tests
failures=0 errors=0 skipped=0
```
