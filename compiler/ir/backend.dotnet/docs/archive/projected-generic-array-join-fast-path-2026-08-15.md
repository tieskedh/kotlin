# Projected generic-array join fast path — 2026-08-15

This immutable checkpoint records the correctness and causal performance
evidence for retaining the projected `System.Array` ABI while using an exact
CLR vector when one is physically available. Current normative rules remain
in `AGENTS.md` and the projected-array join ADR.

## Cost found

The Common-aligned array `joinTo` implementation had the correct public
capability but used it for every element even when the actual vector was an
exact value array:

```text
Int[] as System.Array
  -> GetValue(index)
  -> box Int
  -> unbox.any T
  -> appendElement<T>
```

The public `System.Array` shape cannot be removed: `Array<Int>` viewed as
`Array<out Any?>` is valid Kotlin, while CLR `int[]` is not an `object[]`.
The avoidable part was using the widened capability after the physical vector
had already proved compatible with the method's `T[]`.

## Selected implementation

`joinTo` now performs one `isinst !!T[]`. A successful probe executes the
same Common algorithm over that vector with `ldelem !!T`; a failed probe
executes the existing `System.Array.GetValue` arm. The probe is non-throwing
and does not define a Kotlin cast result. Widened value arrays and any erased
owner state without a compatible vector retain the semantic path.

The source-only probe declaration is a backend intrinsic excluded from
codegen. The final stdlib IL contains neither its name nor an extracted exact-
loop helper. Public method shape, KLIB, Runtime surface, array identity, and
the canonical non-generic Kotlin owner remain unchanged.

## Causal measurement

`tools/measure-generic-array-join.ps1` rebuilds the Framework 4.8 and .NET 10
platform products and first isolates `Array.joinTo` in both emitted IL files.
It requires the public `System.Array` receiver, `isinst !!0[]`, `ldelem !!0`,
and the `GetValue` fallback, and rejects helper leakage.

The C# workload then executes the actual exact and widened stdlib routes for
correctness context. It does **not** compare those routes as a performance
claim: widening changes `T` to `object`, can reuse `GetValue`'s box, and gives
the JIT a different `appendElement` specialization. The causal pair holds
`T = int`, `Appendable`, transform, rendering, and output constant. Its only
difference is:

```text
typed  : T[]          -> ldelem T
legacy : System.Array -> GetValue -> unbox T
```

Measurement parameters were 100,000 operations over eight elements, median
of five runs, after 2,000 warmups. .NET 10 tiered compilation was disabled.
Every route produced checksum 2,200,000.

| Runtime | Typed ticks | Legacy ticks | Local speedup |
| --- | ---: | ---: | ---: |
| Framework 4.8 / CLR 4 | 1,311,163 | 1,910,542 | 1.457× |
| .NET 10 | 773,176 | 962,660 | 1.245× |

The stable result is allocation, not timing. For 800,000 element loads:

| Runtime | Typed bytes | Legacy bytes | Removed |
| --- | ---: | ---: | ---: |
| Framework 4.8 / CLR 4 | 130,400,040 | 149,600,040 | 19,200,000 |
| .NET 10 | 85,600,040 | 104,800,040 | 19,200,000 |

Both runtimes therefore remove exactly **24 bytes per element**, one redundant
`Int` box. This is bounded physical-route evidence, not an application-level
generic-owner performance estimate.

Environment and provenance:

- .NET Framework release `533509`, CLR `4.0.30319.42000`;
- .NET SDK `10.0.100`, runtime `10.0.9`;
- stopwatch frequency 10,000,000 ticks per second;
- measurement-tool SHA-256
  `51e9f25e3d3ad91c626f766442e88667179929501d3fa360f2cffea26be4a2b8`;
- Framework stdlib/IL SHA-256
  `103e8d27f5c5c69e72a51ff2d591cc5c2665a769885f54dd7abe1839be6efb91` /
  `51dfc06cb5b2e2f5ec07c56b55b671ebf4c9c80bd0b233d258c5932cd37b5813`;
  and
- .NET 10 stdlib/IL SHA-256
  `3d960e1796943448333edc75c1f48c388d4f106e97b48941ee48da4054d70e94` /
  `b4f24ad787cf7cb5da9d9aaa4022bd6829c33aa558808e3ad3c260686ced7535`.

The harness deliberately uses one normal multi-target build and the standard
`bin/Release/<target-framework>` separation. Explicit per-target `--output`
directories were rejected after MSBuild reused copy-local state and placed the
net10 Kotlin.Stdlib in the net48 directory; differing stdlib hashes exposed
that invalid run before it could become evidence.

## Verification

Focused semantic execution covers Common rendering, limits, live reads,
identity, transform/failure behavior, exact method-generic arrays, widened
value fallback, and erased-owner nullable value arrays. PSI and LightTree both
execute on Framework 4.8 and .NET 10. Each lane also validates the physical
stdlib body and absence of helper metadata.

The final strict aggregate completed successfully in **5,500.1 seconds**:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct XML audit of its three result roots:

```text
dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,103 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,234 tests
failures=0 errors=0 skipped=0
```
