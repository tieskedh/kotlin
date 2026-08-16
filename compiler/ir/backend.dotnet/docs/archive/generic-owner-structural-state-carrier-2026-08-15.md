# Generic-owner structural state carrier — 2026-08-15

## Scope

The generic-owner planner already classified each owner-dependent field by
semantic storage requirement. That classification did not retain the field's
exact future CLR type. A `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN` state could be
`T`, `Node<T>[]`, or another constructed carrier, while the hostile candidate
fixture silently assumed `T`. Extending that fixture to recursive OctoTree
would therefore have required a source-name/type guess outside compiler
authority.

This tranche records the exact structural carrier before any physical TypeDef
path is selected. It remains production-inert: ordinary generic owners and
their fields are still emitted by the accepted erased ABI.

## Bounded type grammar

The state-only tree introduced here is now generalized as
`DotNetGenericOwnerPrototypeTypeSnapshot`; its state subset has the following
admitted leaves and constructors:

| Logical carrier | Snapshot node |
| --- | --- |
| `Boolean`, `Int`, `String`, `Any` | exact scalar |
| non-null owner parameter `T` | owner GenericParam index |
| Kotlin-owned ordinary generic class | pre-lowering logical declaration key plus invariant arguments |
| `Array<E>` | SZ-array over the exact element node |

The snapshot retains a carrier only when its complete tree references an
owner parameter. Unsupported classifiers and projections produce no record;
they do not degrade to `object`. A generic classifier requires a published
producer key. Same-compilation executables deliberately have no substitute
display-name identity.

An open nullable owner parameter is also rejected. CLR `!T` cannot represent
Kotlin `T?` for every substitution: `T = Int` requires `Nullable<Int>`, not
`Int`. Consequently ArrayCopy's semantic `Array<T?>` state cannot become a
structural `T[]` candidate. Nullable reference classifiers such as `Node<T>?`
remain physically compatible with their non-null CLR class carrier.

## Late physical binding

The tree binds a logical generic-classifier leaf only from a complete map of
producer declaration keys to selected TypeDef paths. Binding fails if any path
is missing. The result is the existing neutral physical type-expression
record; no IL spelling enters the planner.

The hostile physical-family artifact now obtains its typed field carrier from
this record. Its current direct `T` state remains physically identical, but
the fixture no longer contains separate type authority.

## Separate OctoTree evidence

The unchanged repository OctoTree source is now staged into a true library
module without synthesizing a library `main`. A separate consumer constructs
`OctoTree<Int>`, writes and reads two distant leaves, and renders the tree.

The published producer snapshot proves:

- semantic `OctoTree.root: Node<T>?` retains a structural `Node<T>` candidate;
- typed `Node.Branch.nodes: Array<Node<T>?>` retains `Node<T>[]`;
- typed `Node.Leaf.value: T` retains owner parameter zero; and
- binding `Branch.nodes` without the selected `Node` TypeDef path fails, while
  supplying it produces the exact nested producer type under an SZ array.

The producer contains 21 exact typed-entry routes, nine semantic-capability
routes, and nine external-family records. The same-compilation corpus contains
25 exact routes because its driver contributes four calls; those calls
correctly belong only to the separate consumer in the library split.

## Consequence

The next candidate product no longer needs to infer a typed field from a state
classification or source name. It can consume a compiler-derived structural
carrier after the complete recursive family chooses physical paths.

This does not yet serialize the carrier, emit a recursive `OctoTree<T>` family,
change production fields, or create a direct C# product. The next gate is to
bind the complete record-driven family atomically and exercise it through a
paired Kotlin candidate plus direct C# consumer/subclass before measuring it.

## Verification

Focused execution covers the hostile physical family, ArrayCopy's negative
`Array<T?>` boundary, same-compilation OctoTree, and separate producer/consumer
OctoTree through PSI and LightTree on Framework 4.8 and .NET 10: **16 tests**,
zero failures, errors, or skips.

The final strict `:compiler:backend.dotnet:dotNetTest` aggregate completed in
**2,676.6 seconds**. Direct XML audit covers **190 files and 2,238 tests**, with
zero failures, errors, or skips. The separate OctoTree case occurs exactly
four times in those results.
