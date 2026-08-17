# Generic-owner OctoTree Kotlin equality correction (2026-08-17)

## Scope

The large-only post-root audit found that the record-driven C# candidate did
not lower the ordinary generic `==` operations in OctoTree like the Kotlin/.NET
backend. It used `EqualityComparer<T>.Default`; production Kotlin boxes open
`T` operands and calls
`Kotlin.Runtime.Internal.Intrinsics.AreEqual(object, object)`.

That difference invalidated the earlier OctoTree candidate performance and
allocation comparison. It was also a semantic defect: CLR default equality
does not implement Kotlin's generic floating rules for signed zero, and a CLR
`IEquatable<T>` path is not automatically Kotlin's left-biased `Any.equals`
path. The private `Node<T>` root proof remains structurally valid, but its
first measurement is superseded by this checkpoint.

Production generic owners and ordinary Kotlin emission remain unchanged. This
feature corrects only the production-inert candidate, its closed measurement
product, and the evidence used for the future owner decision.

## Corrected product

Both equality sites in `Branch<T>` now call the public Runtime helper used by
real Kotlin code. The candidate producer therefore records and deploys an
explicit `Kotlin.Runtime` dependency on Framework 4.8, JIT, ReadyToRun,
trimming, and NativeAOT. The corpus verifier requires the Runtime call in the
generated source and rejects any remaining `EqualityComparer<T>`.

The separately compiled direct C# product adds controls which fail under the
old comparer lowering:

- generic `Double` and `Float` treat `-0.0` and `+0.0` as distinct;
- different `Double` NaN payloads compare equal after Kotlin bit
  canonicalization; and
- those rules execute on PSI/LightTree and Framework 4.8/.NET 10 products.

The logical workload, erased product, physical-family artifact, and compiler
route manifest are unchanged. Candidate source/product and dependency hashes
change as required.

## Corrected aggregate result

The final run used 200,000 iterations, five startup runs, five throughput runs,
and one compile run. Every candidate/erased lane produced checksum
`-2063014063`.

| Lane | Candidate ms | Erased ms | Candidate / erased | Candidate allocation | Erased allocation |
|---|---:|---:|---:|---:|---:|
| Framework 4.8 | 33.81 | 20.19 | 1.68x | 24.79 MiB | 14.36 MiB |
| .NET 10 JIT | 26.11 | 28.81 | 0.91x | 23.63 MiB | 13.38 MiB |
| ReadyToRun | 25.03 | 22.83 | 1.10x | 23.63 MiB | 13.38 MiB |
| trimmed | 57.42 | 62.57 | 0.92x | 23.63 MiB | 13.38 MiB |
| NativeAOT | 9.57 | 10.72 | 0.89x | 23.06 MiB | 13.38 MiB |

The semantically correct typed-root candidate is faster than erased in JIT,
trimmed, and NativeAOT, close but slower in ReadyToRun, and 1.68x slower on
Framework. It allocates 72.3%-76.6% more in every lane. The superseded CLR-
comparer run reported 5.2%-11.3% less allocation; that apparent allocation win
was the missing Kotlin equality boxes, not a valid generic-owner benefit.

No direct before/after root-isolation claim can be made from the older object-
root corpus because it contained the same incorrect comparer lowering.

## Corrected route attribution

| Lane | Typed | Capability | Clusterization | Rendering |
|---|---:|---:|---:|---:|
| Framework 4.8 | 3.60x | 3.66x | 2.66x | 0.66x |
| .NET 10 JIT | 1.33x | 1.46x | 1.09x | 0.70x |
| ReadyToRun | 1.44x | 1.55x | 1.33x | 0.64x |
| trimmed | 1.26x | 1.33x | 1.42x | 0.79x |
| NativeAOT | 1.17x | 1.31x | 1.53x | 0.48x |

Ratios are candidate/erased median workload time. Typed, capability, and
clusterization now allocate respectively 243.2%, 342.9%, and 282.6% more than
erased because the typed `T` values must be boxed at each ordinary generic
equality call, whereas the erased object state already holds the boxes. The
route protocol's conversion field continues to describe owner entry/state
boundary conversions; ordinary body equality allocation is measured directly
and is not misreported as a bridge conversion. Rendering remains separately
lowering-confounded by generated C# versus Common `joinToString`.

## Next large foundation

The corrected result identifies one material optimization target rather than
just a microbenchmark tweak: open-`T` Kotlin equality currently boxes both
operands before the Runtime helper. A future generic Runtime helper may be able
to preserve Kotlin equality with fewer boxes, but it must be production-used
and independently prove:

- `Float`/`Double` NaN canonicalization and signed-zero distinction;
- null and reference behavior;
- left-biased `Any.equals` dispatch;
- a foreign/custom struct whose `IEquatable<T>.Equals` deliberately disagrees
  with `object.Equals`; and
- identical Framework, JIT, ReadyToRun, trimmed, and NativeAOT behavior.

Using `EqualityComparer<T>.Default`, CLR operator equality, or a candidate-only
shortcut is forbidden. If no semantically exact lower-boxing helper is
possible, the measured allocation is a real cost of reified storage and must
remain in the owner decision.

## Reproduction and verification

The closed manifest hashes are
`574c764e5d885a0bad264404677b5de1a5aef6824d5d86f41f35f46795bb3bb2`
for net10 and
`dc42729ab8b0ef7706e64a6f9d60b92053b591d4598e4330583bd32229a934b6`
for net48. Aggregate and route result SHA-256 values are respectively
`2a94dbafe010f484db7873f1254642f87a6a929d85dda50ac9d58128344a44e9`
and
`2b96f11204c24332355f4407a8bb83ab3ea48ea5e0fa77db6c08e5fece0df22d`.

- Focused PSI/LightTree x Framework 4.8/.NET 10 matrix: eight tests, zero
  failures, errors, or skips.
- Closed four-cell corpus and all candidate/erased products: green.
- Corrected aggregate plus four routes: green on all five deployment lanes,
  including real NativeAOT.
- Final strict aggregate direct audit: 190 XML files, 2,238 tests, zero
  failures, errors, or skips.
