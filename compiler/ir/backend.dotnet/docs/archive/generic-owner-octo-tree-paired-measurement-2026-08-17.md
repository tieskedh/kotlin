# Generic-owner OctoTree paired measurement (2026-08-17)

## Scope

This checkpoint measures the complete schema-13, record-driven OctoTree
candidate against the current erased production owner. It remains a test-owned,
production-inert architecture product. Production generic owners, emission,
DLL/KLIB, Runtime, Common semantics, and the public C# surface are unchanged.
The candidate is generated C# physicalization rather than a complete Kotlin
product, so published size and the rendering implementation are not an
end-to-end ABI comparison and this checkpoint does not authorize owner cutover.

## Closed schema-3 corpus

`verify-generic-owner-applications.ps1 -CorpusKind octo-tree` now produces the
same closed bundle through PSI and LightTree for Framework 4.8 and .NET 10. The
bundle fingerprints and admits no files beyond:

- the separate-compilation Kotlin oracle and the unchanged Kotlin/Native
  `ocTree.kt` source;
- the compiler-recorded physical-family artifact and call-route manifest;
- the generated candidate producer source and `SnapshotProducer.dll`;
- the checksum-identical candidate and production-erased C# application
  sources and executables;
- the erased Kotlin producer, Runtime, Stdlib, runtime configs, and pinned SDK
  configuration; and
- one exact manifest covering every source and product hash.

The compiler manifest contains 30 resolved OctoTree sites: 21 exact typed-entry
and nine semantic-capability sites. PSI and LightTree must agree byte-for-byte
within each target, and Framework/CoreCLR must agree on the profile-neutral
route artifact. The verifier also pins the mixed physical field model:
`Tree.root: object`, `Leaf.value: T0`, and
`Branch.nodes: OctoTreeNode<T0>[]`.

Closing the standalone product found and fixed four harness defects before any
performance claim was accepted:

- a `Main(string[])` Framework entry point was invoked as parameterless;
- the erased executable did not receive its adjacent producer/Runtime/Stdlib
  dependencies;
- the candidate producer was renamed after compilation, leaving an assembly
  identity that CoreCLR could not resolve; and
- the verifier mistook the canonical logical-key prefix `F:/` for a Windows
  drive-root path.

The older hostile schema-2 corpus remains accepted and executable.

## Workload and protocol

Workload version 2 prepares one depth-two, 512-point checkerboard tree and
measures the same logical operations in both representations. Aggregate work
per iteration has one set and one get, with rendering every 512 iterations.
Four separately compiled route products attribute:

- `octo-tree-typed-path`;
- `octo-tree-capability-path`;
- `octo-tree-clusterization`; and
- `octo-tree-rendering`.

Every process reports a fail-closed checksum, elapsed ticks, allocation,
typed/capability/erased calls, construction count, value conversions, and
runtime compatibility checks. Candidate and erased checksums are identical per
route and across Framework 4.8, .NET 10 JIT, ReadyToRun, full trim, and a real
NativeAOT link/run. The final runs used 200,000 iterations, five startup runs,
five throughput runs, and one compile run.

## Aggregate result

| Lane | Candidate ms | Erased ms | Candidate / erased | Candidate allocation | Erased allocation |
|---|---:|---:|---:|---:|---:|
| Framework 4.8 | 48.65 | 20.22 | 2.41x | 18.20 MiB | 14.36 MiB |
| .NET 10 JIT | 28.94 | 27.60 | 1.05x | 17.04 MiB | 13.38 MiB |
| ReadyToRun | 30.13 | 23.26 | 1.29x | 17.04 MiB | 13.38 MiB |
| trimmed | 76.18 | 61.82 | 1.23x | 17.04 MiB | 13.38 MiB |
| NativeAOT | 11.40 | 10.76 | 1.06x | 16.47 MiB | 13.38 MiB |

All lanes produced checksum `-2063014063`. Candidate allocation is 23.0% to
27.3% higher. Candidate/erased median startup was respectively 168.0/170.1 ms
on Framework, 47.5/47.1 ms on JIT, 46.5/45.0 ms on ReadyToRun, 84.6/81.2 ms
trimmed, and 23.8/23.8 ms on NativeAOT. Median peak working set remained close
within each lane: 72.1/72.6 MiB on Framework, about 26 MiB on managed .NET 10,
and 16.3/16.3 MiB on NativeAOT.

Published bytes are deliberately non-comparable: the candidate is not a
Kotlin product and carries no KLIB/Runtime/Stdlib closure. The input inventory
nevertheless remains recorded: the candidate producer is 7,680 bytes with
four GenericParams and 1,166 IL bytes; the erased producer is 18,944 bytes with
12,476 Kotlin-metadata bytes and 1,421 IL bytes. The physical-family artifact
is 45,276 bytes.

## Route attribution

| Lane | Typed | Capability | Clusterization | Rendering |
|---|---:|---:|---:|---:|
| Framework 4.8 | 5.14x | 5.59x | 2.69x | 0.66x |
| .NET 10 JIT | 1.50x | 1.75x | 0.73x | 0.69x |
| ReadyToRun | 1.64x | 1.82x | 0.92x | 0.64x |
| trimmed | 1.82x | 1.83x | 1.12x | 0.81x |
| NativeAOT | 1.38x | 1.48x | 0.77x | 0.49x |

Ratios are candidate/erased median workload time. Typed-path allocation is
9.2 versus 4.6 MiB and capability-path allocation is 13.7 versus 4.6 MiB in
every lane: current internal semantic crossings respectively double and triple
the erased allocation. Clusterization is near parity in allocation and wins on
JIT/NativeAOT, consistent with real `T` leaf state and typed node vectors.
Rendering allocates 7.5% to 17.0% less in the candidate, but its generated C#
loop is not the production Common `joinToString` lowering; this route proves
body execution and bounds a possible benefit, not an ABI decision.

## Material audit finding and next gate

The poor typed/capability routes are not evidence that CLR generics are
intrinsically slow. The generated `OctoTree<int>` still stores its private
`root: Node<T>?` as `object`; typed set/get therefore cross the semantic node
capability and box/unbox values while only Leaf and Branch state receive the
typed representation.

The post-measurement audit found a concrete conservative-poisoning candidate:
`TypedWriteValueProvenanceAnalyzer` classifies the `null` initializer of the
private `Node<T>?` root through its expression type (`Nothing?`) and therefore
as `SEMANTIC_OBJECT`. Null is representation-neutral for a proven reference
carrier such as `Node<T>?`, but not for bare unconstrained `T?`. The next
hardest-first foundation must distinguish those cases, retain all non-null
producer and complete-access-graph proofs, and then remeasure. This is a
material architectural opportunity because it can remove the exact capability
crossings that dominate the route result; it is not permission to type a bare
open-nullable owner parameter or to weaken the open-world fallback.

## Verification

- Closed corpus: PSI/LightTree x Framework 4.8/.NET 10, all four bundles and
  both executable representations green.
- Aggregate: Framework, JIT, ReadyToRun, trimmed, and real NativeAOT green.
- Route attribution: all four routes across the same five lanes green.
- Final strict aggregate direct audit: 190 XML files, 2,238 tests, zero
  failures, errors, or skips.
