# Generic open-`T` equality lower-boxing closure (2026-08-17)

## Scope

The corrected OctoTree candidate exposed a production Kotlin/.NET cost rather
than a candidate-only defect: ordinary structural equality between two values
of the same open CLR type parameter boxed both operands before calling
`Kotlin.Runtime.Internal.Intrinsics.AreEqual(object, object)`. In the typed
generic-owner candidate, that paid for reified state and then discarded the
physical `T` carrier at every equality site.

This checkpoint closes the bounded optimization requested by the preceding
measurement. The helper is part of Runtime surface 38 and is selected by
ordinary production codegen, not only by the experimental owner candidate.
Production Kotlin-owned class owners remain erased; this does not authorize a
`C<T>` TypeDef cutover.

## Selected runtime route

`Intrinsics.AreEqualGeneric<T>(T left, T right)` classifies each constructed
`T` once through a private generic static cache.

- References, `Float`, `Double`, `Float?`, and `Double?` box both operands and
  delegate to the existing universal helper. Null safety, virtual left-biased
  `Any.equals`, canonical NaN equality, and signed-zero distinction therefore
  retain one authority.
- Other value types box the right operand and invoke
  `System.Object.Equals(object)` on the address of the left operand through
  `constrained. T`. A struct override consequently avoids the receiver box,
  while the call remains object equality rather than
  `IEquatable<T>.Equals(T)` or `EqualityComparer<T>.Default`.
- Only two operands with the exact same physical open type parameter use the
  generic entry. Mixed open parameters and open-versus-stable carriers remain
  on the universal object fallback.

The generated caller contains no `box !!0`; the Runtime fallback retains any
boxing needed by the constructed type. The cache uses statically rooted type
handles and `Nullable.GetUnderlyingType`, has no user-visible type or dynamic
code generation, and is accepted by Framework CLR 4.8, CoreCLR, ILLink, and
NativeAOT.

## Adversarial semantic evidence

The production box suite now covers value, reference, null, asymmetric virtual
reference equality, `Double` NaN/signed zero, nullable `Double` signed zero,
and nullable `Int` through the generic entry on PSI/LightTree and both CLR
profiles.

The separate C# integration product adds a struct whose
`IEquatable<T>.Equals` always returns false while its `object.Equals` compares
the payload. Equal values must compare equal and unequal values must not. It
also covers direct and nullable floating equality and nullable integral null.
The emitted Kotlin method is pinned to `AreEqualGeneric<!!0>(!!0, !!0)` and
rejects a caller-side `box !!0`.

The closed OctoTree candidate and erased applications independently repeat the
hostile struct, direct floating, and nullable floating/null controls before
every measured run. Those controls execute, outside the timed/allocation
region, on Framework 4.8, .NET 10 JIT, ReadyToRun, full trimming, and real
NativeAOT. The corpus verifier requires these source shapes and rejects both a
CLR comparer and the old candidate two-box call.

## Aggregate result

The final run used 200,000 iterations, five startup runs, five throughput runs,
and one compile run. Every candidate/erased lane produced checksum
`-2063014063`.

| Lane | Candidate ms | Erased ms | Candidate / erased | Candidate allocation | Erased allocation | Delta |
|---|---:|---:|---:|---:|---:|---:|
| Framework 4.8 | 33.4288 | 20.3217 | 1.6450x | 20,136,320 | 15,058,176 | +33.72% |
| .NET 10 JIT | 24.1466 | 27.7952 | 0.8687x | 18,916,008 | 14,034,312 | +34.78% |
| ReadyToRun | 23.7001 | 23.2083 | 1.0212x | 18,916,008 | 14,034,312 | +34.78% |
| trimmed | 51.9017 | 61.6486 | 0.8419x | 18,916,008 | 14,040,504 | +34.72% |
| NativeAOT | 7.9445 | 10.8534 | 0.7320x | 12,455,824 | 14,034,352 | -11.25% |

Against the semantically correct two-box candidate, the new helper removes
exactly 5,861,184 bytes on Framework/JIT/ReadyToRun/trimmed and 11,722,368
bytes on NativeAOT: 22.55%-23.66% and 48.48% respectively of the old candidate
allocation. Candidate allocation excess falls from 72.3%-76.6% to about
33.7%-34.8% on the managed lanes and becomes an 11.25% saving on NativeAOT.
Timing moved favorably in the modern lanes, but separate-run timing variation
is not attributed solely to the helper.

## Route attribution and remaining cost

| Lane | Typed | Capability | Clusterization | Rendering |
|---|---:|---:|---:|---:|
| Framework 4.8 | 3.4609x | 3.6631x | 2.3403x | 0.6589x |
| .NET 10 JIT | 1.2472x | 1.3547x | 0.8826x | 0.6916x |
| ReadyToRun | 1.3063x | 1.4549x | 1.1265x | 0.6364x |
| trimmed | 1.1318x | 1.2032x | 1.1228x | 0.8220x |
| NativeAOT | 0.8732x | 1.0507x | 0.7648x | 0.4705x |

Ratios are candidate/erased median workload time. Typed and capability routes
still allocate 121.47% and 221.17% more than erased on Framework/JIT/
ReadyToRun; clusterization retains 126.09% excess there. NativeAOT reaches a
0.26% allocation saving on typed entry, but capability remains 99.44% above
erased. Framework capability timing barely improves despite the removed
boxes, which isolates dispatch, compatibility checks, and indirection as
material independent costs. Rendering remains generated-C# versus Common
`joinToString` confounded.

The result therefore proves that CLR generics are not intrinsically the
measured problem and that preserving typed fields can produce real wins. It
also disproves the stronger claim that equality alone makes the capability
architecture cheap. No further small equality micro-optimization is selected.
The next hostile-owner gate is the one-state concurrency/memory-model migration
condition.

## Reproduction and verification

The closed manifest hashes are
`ed5ebb3563a85d4d028efcaf7980042ca76d915f78809c318a8122d584b76339`
for net10 and
`ca8b29926c84cde6eda75154b772368c11f75bba055cc7dc2ee3f95382e400e8`
for net48. Aggregate and route result SHA-256 values are respectively
`834486dccae54680d82497d0659ed51234c693607e97950683392f9b32c4aa11`
and
`a55fd63322c7b9bdaa85ce41f11be187e7dd2f0286d8f56db167f6f0624b706f`.

- Focused production box and C# integration matrix: green.
- Closed PSI/LightTree x Framework 4.8/.NET 10 corpus: green.
- Aggregate and four attributed routes: green on all five deployment lanes,
  including full trimming and real NativeAOT.
- Final strict aggregate direct audit: 190 XML files, 2,238 tests, zero
  failures, errors, or skips.
