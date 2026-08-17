# Generic-owner nullable direct surface (2026-08-17)

## Decision

Physical-family schema 16 makes nullable-reference metadata an explicit,
per-value-position part of the bounded CLR generic-owner candidate.

Kotlin semantics remain authoritative. Nullable metadata describes the
already-selected CLR carrier for C# tools; it neither changes Kotlin subtype
rules nor narrows the set of legal Kotlin generic substitutions. In
particular, attaching Roslyn non-null flag `1` to an exact unconstrained `T`
use does not add a CLR `class`, `struct`, or `notnull` constraint.

Nullability is not stored on physical type identity. The same `object` CLR type
can represent a non-null `Any`, a nullable `Any?`, an intentionally oblivious
boundary, or the shared semantic carrier for an unconstrained Kotlin type
parameter. Each signature, property, and state use therefore owns its nullable
transform.

This closes one migration condition. Production Kotlin-owned generic classes
remain erased and there is no public per-class ABI switch.

## Problem found

Schema 15 could describe a truthful direct physical type and PropertyDef, but
the detached producer record had discarded the source IR nullability needed to
author truthful C# and metadata. For example, these physical shapes are not
enough on their own:

```text
OctoTreeNode<!0>
OctoTreeNode<!0>[]
!0
object
```

The bounded recursive product needs to distinguish:

```text
Kotlin source position       CLR carrier       Roslyn preorder flags
root: Node<T>?               Node<T>           [2, 1]
nodes: Array<Node<T>?>       Node<T>[]         [1, 2, 1]
Leaf.value: T                T                 [1]
get(): T? semantic result    object            [2]
Array<T?> fallback           System.Array      [1]
```

Reconstructing these facts later from a physical type is impossible. In
particular, the nullable array element and the non-null constructed type
argument have independent meanings.

The work also made an existing export arm reachable in a meaningful generic-
owner product. An unmarked CLR type-parameter use had emitted nullable flag
`0` (oblivious). A local Roslyn metadata probe and the product's generated C#
both establish flag `1` as the compatible exact-use encoding. The backend now
uses `NON_NULL` for that position. This is metadata only: nullable type
arguments are still legal and an open Kotlin `T?` continues to use the current
truthful nullable `object` carrier.

## Representation

`DotNetNullableReferenceFlag` is the shared typed vocabulary:

```text
OBLIVIOUS = 0
NON_NULL  = 1
NULLABLE  = 2
```

Both live export types and generic-owner prototype types produce this enum.
The generic-owner overload runs at the IR-to-prototype boundary, before live
`IrType` information is lost.

Every path-unbound prototype value slot carries a complete preorder flag
vector. Binding a logical generic classifier to its selected producer TypeDef
preserves the vector unchanged. Physical MethodDef slots, Property records,
and state records then carry the same typed vector.

State snapshots retain nullable flags only when a complete physical carrier is
known. A declaration-independent or proven typed state derives flags against
its exact carrier; semantic and volatile state derive flags against `object`;
unresolved state remains flagless as well as carrierless. This prevents a
partially described state from escaping the proof phase.

## Fail-closed joins

The type grammar computes the exact number of nullable-reference transforms a
carrier requires. Construction and decoding reject a shorter or longer vector.

A physical property requires exact equality between its flags and:

- the getter return slot, when present; and
- the setter value parameter, when present.

An identity physical state access requires exact equality between its field
flags and the corresponding read return or write parameter. Semantic
box/widen and checked cast/unbox paths intentionally need not share a physical
type or transform: they cross between `object?` storage and exact `T` values.

Schema 16 serializes enum names rather than raw integers and rejects unknown
names. Tests also reject malformed transform cardinality, property/accessor
disagreement, state/access-path disagreement, duplicate or partial property
records, and stale schemas.

## C# and raw metadata product

The record-driven OctoTree producer now starts with `#nullable enable` and
renders every relevant field, property, parameter, and return from the recorded
preorder vector. Producer compilation uses warnings-as-errors, so a generated
nullable-flow defect is a product failure rather than test noise.

The raw ECMA-335 inspector does not trust reflection's normalized nullable
view. It decodes both scalar and byte-vector `NullableAttribute` blobs and
applies `NullableContextAttribute` fallback at MethodDef and TypeDef scope. It
checks effective transforms on:

- Field rows;
- Property rows;
- MethodDef return Parameter rows; and
- ordinary method Parameter rows.

Framework 4.8 separately verifies the compiler-synthesized embedded nullable
attribute TypeDefs. .NET 10 consumes the platform-provided attribute types.

The generated C# product still exercises the same object graph through direct
typed calls, real properties, private typed state, and non-generic semantic
capabilities. Null-forgiving operators appear only at recorded semantic-to-
typed crossings where the generated runtime cast/check remains authoritative;
they suppress C# flow warnings and do not change execution.

## Verification

The following gates passed during development:

- `:compiler:backend.dotnet:compileKotlin`;
- `:compiler:fir:fir2ir:compileTestFixturesKotlin`;
- the representative OctoTree separate-compilation product;
- the hostile generic-owner oracle product; and
- the combined eight-product FIR PSI/LightTree x Framework 4.8/.NET 10 matrix.

The final `:compiler:backend.dotnet:dotNetTest` aggregate exited successfully.
Its direct XML audit covers 187 suites and 2,107 tests with zero failures,
errors, or skips.

## Performance and remaining work

Nullable transforms are compile-time/artifact data and add no runtime
capability dispatch, compatibility check, boxing, or state indirection. The
critical review found no large hot-path optimization attributable to this
feature. It did find the incorrect type-parameter metadata described above.

This condition does not close:

- nullable transforms on the complete base/interface graph;
- overload and generated-name collision policy;
- broad property semantics whose Kotlin input domain is wider than a direct
  CLR property setter;
- every nullable value-type and user-struct application shape;
- the complete reflection/cast/dispatch acceptance matrix; or
- the atomic production migration from erased Kotlin owner to canonical CLR
  `C<T>`.

The next step must remain a complete hostile migration condition. An easy-owner
rollout or a micro-optimization is not evidence for selecting the public owner
ABI.
