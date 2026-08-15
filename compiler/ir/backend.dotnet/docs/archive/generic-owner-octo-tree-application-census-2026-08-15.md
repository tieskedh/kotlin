# Generic-owner OctoTree application census — 2026-08-15

## Scope

This tranche broadens the compiler-derived generic-owner census with a second
exact repository application. It stages the unchanged
`kotlin-native/performance/ring/src/commonMain/kotlin/org/jetbrains/ring/OctoTest/ocTree.kt`
as a declared Gradle input and adds only a bounded .NET driver. The driver
constructs `OctoTree<Boolean>` at depth two, performs all 512 coordinate
writes, checks all 512 reads, and executes the source's recursive rendering.

This source is intentionally complementary to ArrayCopy. ArrayCopy observed
only exact typed-entry calls but required semantic array state. OctoTree has a
mixed exact/capability call distribution and contains both substitution-stable
classifier arrays and genuinely owner-dependent value state.

## Generic-array foundation

The exact source exposed two independent missing foundations.

First, Common array `joinTo` and `joinToString` were not yet available on the
.NET stdlib surface. Their target implementation follows the generated Common
algorithm and `appendElement` semantics: null, `CharSequence`, `Char`, ordinary
objects, transforms, limits, truncation, appendable identity, evaluation, live
array mutation, and callback exception identity are preserved. The receiver is
the original CLR vector; joining does not copy it to `object[]`.

Second, the erased-owner array mapper treated every array whose logical element
mentioned the owner parameter as `System.Array`. That is necessary for direct
`Array<T>` because the runtime component type depends on the substitution, but
it is unnecessarily weak for `Array<Node<T>?>`. Production `Node<T>` is one
erased CLR `Node` classifier for every T, so its truthful component type is
always `Node`.

The new proof requires a concrete source classifier and a
declaration-stable physical carrier. It rejects `object`, CLR generic
parameters, constructed CLR generics, and recursively unstable arrays. Thus:

| Kotlin type inside an erased owner | Physical carrier |
|---|---|
| `Array<T>` / `Array<T?>` | `System.Array` |
| `Array<Node<T>?>` where `Node<T>` is an erased Kotlin class | `Node[]` |
| open constructed CLR-generic element | `System.Array` |

The concrete-classifier requirement is essential. An initial broader proof
allowed a bounded direct `T` and changed `EnumEntriesList<T>.entries` to
`Enum[]`; Framework 4.8 rejected that representation. The final rule excludes
that route, and the Framework stdlib producer is green. The physical IL oracle
now pins a `StableNode[]` field, `newarr StableNode`, and typed `stelem
StableNode`, while `StableNode<T>.value` correctly remains `object` in the
production erased owner.

## State proof

The architecture snapshot preserves the distinction between current physical
storage and future candidate evidence:

- `OctoTree.root: Node<T>?` remains `SEMANTIC_OBJECT_REQUIRED`;
- `Node.Branch.nodes: Array<Node<T>?>` is
  `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`, and its exact
  `arrayOfNulls<Node<T>>(8)` initializer is a physically typed producer;
- `Node.Leaf.value: T` is also candidate-typed-proven, although production
  still stores it as `object` because production generic owners remain erased.

The producer proof recognizes only `arrayOfNulls<LocalGenericOwner<T>>()`.
Direct `arrayOfNulls<T>()` and `arrayOfNulls<T?>()` remain unresolved/semantic;
the proof cannot be used to reintroduce the bounded-`T` bug.

## Route result

The complete compiler snapshot contains 43 OctoTree-related static sites:
25 exact typed-entry candidates, nine semantic-capability routes, and nine
external-family records. The local instrumented manifest contains the first
34 records. Its exact sparse site vector is pinned, including nine zero-hit
sites. The bounded execution produces:

| Evidence | Count |
|---|---:|
| local static exact routes | 25 |
| local static semantic-capability routes | 9 |
| external static family records | 9 |
| local dynamic exact-entry events | 5,941 |
| local dynamic semantic-capability events | 3,096 |
| local producer events | 9,037 |
| unrelated/external dynamic events | 2,728 |
| all dynamic events | 11,765 |

PSI and LightTree produce identical route, count, and instrumented assembly
hashes within each target profile. Framework CLR 4 and .NET 10 also produce
identical route and count bytes. The source-controlled command is:

```powershell
& compiler/ir/backend.dotnet/tools/verify-generic-owner-call-route-traces.ps1 `
  -Corpus octo-tree
```

Instrumentation uses `Interlocked` counters and is correctness evidence only;
none of these counts is a throughput or allocation measurement.

## Consequence

This result narrows both sides of the reopening decision. Canonical semantic
capability traffic is material in a hostile recursive owner but still confined
to nine static sites, while 25 sites can use exact typed entries. At the same
time, owner erasure no longer forces every nested generic array through
`System.Array`: declaration-stable classifier vectors can already gain typed
CLR allocation, load, and store operations without changing owner identity or
pretending that `T` state is reified.

The result does not admit production `C<T>`, complete representative breadth,
paired erased/candidate products, direct C# consumers, or performance claims.
Those migration gates remain open.

## Verification

Focused semantic execution covers array joining, the stable-classifier vector,
and the exact OctoTree application through PSI/LightTree on Framework 4.8 and
.NET 10. The IL oracle is cross-assembled by both frontends, and the Framework
stdlib producer validates the bounded-`T` exclusion. The final strict
`:compiler:backend.dotnet:dotNetTest` aggregate completed in **5,208.0
seconds**. Direct XML audit covers **190 files and 2,234 tests**, with zero
failures, errors, or skips.
