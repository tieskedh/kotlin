# Generic-owner OctoTree typed private root (2026-08-17)

> **Superseded performance evidence:** a later large-only audit found that the
> generated candidate used CLR `EqualityComparer<T>.Default` where real Kotlin
> calls Runtime `AreEqual(object, object)`. The structural private-root proof
> remains valid, but every timing/allocation conclusion below is superseded by
> [`generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md`](generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md).

## Scope

This checkpoint removes one conservative classification defect from the
production-inert true-CLR-generic owner architecture. The separate-compilation
OctoTree candidate now stores its private `root: Node<T>?` as the real CLR
reference `OctoTreeNode<T>`, while the externally required non-generic semantic
capability remains available on the same object graph. Production generic
owners, normal emission, DLL/KLIB, Runtime, Common semantics, casts, and the
public C# surface remain unchanged.

The change does not make null generally typed. It distinguishes a null literal
written to a proven local generic class reference from a null literal written
to an unconstrained owner parameter.

## Bounded physical proof

`TypedWriteValueProvenanceAnalyzer` may treat a null write as physically typed
only when the destination is a marked-nullable reference to a local, non-value
generic class with a stable pre-lowering logical key, exact arity, invariant
arguments, and no explicit nullable owner-parameter argument. A constructor
call to the same proven carrier now supplies physically typed provenance.

Consequently:

- `private var root: Node<T>? = null`, followed only by exact `Node<T>`
  constructions, becomes `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`;
- the root's read/write access remains producer-private typed identity access,
  with no invented KLIB callable or reflection member;
- internal Tree get/set/rendering bodies use `Node<T>`, `Leaf<T>`, `Branch<T>`,
  and `Node<T>[]` directly; and
- the outer semantic capability still boxes/narrows only at the open-world
  boundary.

The negative control `private var value: T? = null` remains
`SEMANTIC_OBJECT_REQUIRED`: an unconstrained `T` can close over a CLR value or
reference type and has no single truthful nullable `!T` field carrier. The
same proof deliberately rejects external classifiers, projections, generic
value classes, and `C<T?>`. Without a selected producer logical key, the
same-compilation snapshot remains semantic rather than guessing a physical
TypeDef path.

The receiver-route analysis applies the same narrow null rule. Null is not a
possible receiver and therefore no longer poisons exact non-null constructions
written to the root. An only-null field remains unresolved when read, so this
does not manufacture a callable receiver proof.

## Closed corpus and metadata

The unchanged schema-3/workload-2 corpus is regenerated independently through
PSI and LightTree for Framework 4.8 and .NET 10. It retains the compiler's 21
exact and nine semantic static call sites; the physicalizer may eliminate an
internal capability crossing only because the complete selected state and
family proof is stronger than the original pre-physicalization IR census.

The verifier now requires:

- open `OctoTree<T>.root` to be `OctoTreeNode<T>`;
- closed `OctoTree<int>.root` and both private state methods to use
  `OctoTreeNode<int>`;
- default null initialization to target a proven CLR class reference;
- one hidden private typed read/write pair and no logical reflection exposure;
  and
- checksum-identical candidate and erased products on both CLR profiles.

The physical-family schema does not change: schema 13 already expresses the
typed constructed carrier and producer-private identity access. This feature
corrects the compiler evidence which selects that existing record.

## Aggregate result

The final run used 200,000 iterations, five startup runs, five throughput runs,
and one compile run. All lanes produced checksum `-2063014063`.

| Lane | Candidate ms | Erased ms | Candidate / erased | Candidate allocation | Erased allocation |
|---|---:|---:|---:|---:|---:|
| Framework 4.8 | 32.88 | 20.32 | 1.62x | 13.61 MiB | 14.36 MiB |
| .NET 10 JIT | 22.29 | 27.80 | 0.80x | 12.45 MiB | 13.38 MiB |
| ReadyToRun | 21.79 | 23.02 | 0.95x | 12.45 MiB | 13.38 MiB |
| trimmed | 47.55 | 61.92 | 0.77x | 12.45 MiB | 13.38 MiB |
| NativeAOT | 7.62 | 10.69 | 0.71x | 11.88 MiB | 13.38 MiB |

The preceding object-root candidate measured 2.41x, 1.05x, 1.29x, 1.23x, and
1.06x in the same lane order, with 23.0%-27.3% more allocation. The typed-root
candidate instead allocates 5.2%-11.3% less and is faster in all modern .NET
lanes. Framework remains 1.62x slower and therefore remains an explicit
decision input rather than being hidden by the favorable modern results.

## Route attribution

| Lane | Typed | Capability | Clusterization | Rendering |
|---|---:|---:|---:|---:|
| Framework 4.8 | 3.29x | 3.65x | 2.36x | 0.66x |
| .NET 10 JIT | 1.11x | 1.28x | 0.67x | 0.69x |
| ReadyToRun | 1.19x | 1.31x | 0.85x | 0.64x |
| trimmed | 1.01x | 1.09x | 1.11x | 0.80x |
| NativeAOT | 0.83x | 1.02x | 0.61x | 0.48x |

Ratios are candidate/erased median workload time. Per logical typed-path
iteration, the candidate now reports six typed calls, zero semantic calls, two
value conversions, and zero compatibility checks; it previously reported two,
four, four, and one. Clusterization moves from nine typed plus nine semantic
calls, 18 conversions, and eight checks to 18 typed calls, two conversions,
and zero checks. It allocates 30.4% less than erased in every lane.

The externally forced capability path still has two semantic calls, four
conversions, and one compatibility check per iteration and allocates 99.4%
more than erased. That is the real open-world semantic boundary, not evidence
that the private root should be widened again. Rendering remains lowering-
confounded because generated C# is compared with Common `joinToString` lowering
and is not an ABI-selection result.

## Material audit boundary

The result supports true CLR generic storage and direct internal paths; it does
not by itself authorize the atomic production owner cutover. The remaining
large-only audit must distinguish a compiler-generated avoidable crossing from
the required semantic export. In particular, the capability route's remaining
boxing/checking and Framework's generic dispatch cost are material, but no
micro-optimization should land unless it removes a compiler defect or closes
an outstanding migration condition. If that audit finds no such defect, the
next architecture proof is the one-state concurrency/memory-model condition,
not another easy owner or representation switch.

## Reproduction and verification

The corpus was generated with:

```powershell
& compiler/ir/backend.dotnet/tools/verify-generic-owner-applications.ps1 `
  -CorpusKind octo-tree `
  -OutputDirectory compiler/ir/backend.dotnet/build/generic-owner-octo-tree-typed-root-corpus
```

Both measurements used the same corpus, all five modes, 200,000 iterations,
five startup and throughput runs, one compile run, and the existing explicitly
validated MSVC/Windows SDK NativeAOT toolchain. The aggregate and route result
SHA-256 values are respectively
`451e8225d7e3cfb90cdf291e37d48d32ec064c5704071954be37edfa3b5cfb23`
and
`b785edeea5da375f477b5f9096d4b0b6279a9ec595f7c9984e3ff762647fdc39`.
The profile manifest hashes are
`07ab98ceaf651c077901304229cf886ab62aab11f725ae98940241e18a4a6924`
for net10 and
`6e6064488fdee45d36c9c243441532df3aed469a38db043ac010c7cba280f8c1`
for net48. The route manifest is byte-identical across all four cells with
SHA-256
`7ce996f9aaa59be4b8522a82b87a1fc726fe7f211752cda2880ee30b66c86ad5`.

- Focused PSI/LightTree x Framework 4.8/.NET 10 same/separate matrix: eight
  tests, zero failures, errors, or skips.
- Closed corpus and all candidate/erased products: green on both profiles.
- Aggregate plus all four attributed routes: green on Framework, JIT,
  ReadyToRun, trimmed, and real NativeAOT.
- Final strict aggregate direct audit: 190 XML files, 2,238 tests, zero
  failures, errors, or skips.
