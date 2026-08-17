# Generic-owner direct CLR property surface

- Date: 2026-08-17
- Scope: production-inert CLR-generic owner architecture evidence
- Physical-family schema: 15
- Production Kotlin-owned generic owners: unchanged and erased

## Condition closed

The future canonical `C<T>` owner must expose an honest ordinary C# surface,
not merely correctly typed methods whose names happen to start with `get_` or
`set_`. This slice closes the bounded PropertyDef part of that condition.

The compiler-derived prototype now records, for every property accessor:

- whether it is the getter or setter;
- the producer-selected physical Property name; and
- the existing logical callable key and typed-entry MethodDef signature.

Schema 15 groups those facts in a
`DotNetGenericOwnerPhysicalPropertyRecord`. The logical getter and setter keys
remain KLIB authority. There is no new property linkage key and no physical
name reconstruction in a consumer.

## Hard invariants

A physical property:

- has one non-void type, one complete getter, and an optional complete setter;
- uses instance, non-generic, non-indexed getter/setter signatures;
- binds only visible `TYPED_ENTRY` MethodDefs on the recorded owner;
- cannot bind a semantic hook or non-generic capability dispatcher;
- cannot share an accessor MethodDef or physical name with another property;
- cannot reference a missing owner GenericParam or consumer-local TypeDef; and
- is serialized atomically with its property type, logical keys, MethodDef
  owners, names, and signatures.

The bounded OctoTree selector additionally excludes private accessors and fake
overrides. Inherited CLR properties stay inherited; they are not republished as
new Property rows on a child TypeDef.

## Executable product

The decoded four-owner OctoTree family now drives real C# properties:

| Owner | Property | Shape |
| --- | --- | --- |
| `OctoTree<T>` | `depth` | getter-only `int` |
| `OctoTreeLeaf<T>` | `value` | get/set `T` |
| `OctoTreeBranch<T>` | `nodes` | getter-only `OctoTreeNode<T>[]` |

These properties reuse the exact typed state already proved by the prior
family. `value` remains one private `T` field and `nodes` remains one private
`Node<T>[]` field. Capability reads/writes cross the object/System.Array
boundary and then use those same properties and fields; no shadow state or
wrapper object exists.

A separately compiled C# consumer uses direct property syntax, checks the open
and closed generic property types, and verifies get/set behavior shares state
with the semantic capabilities. A separate raw ECMA-335 inspector checks each
Property row, PropertyMap membership, signature, and getter/setter
MethodSemantics association against the decoded record.

## Material defect found

The first product used the natural source spelling for both a private state
field and its public property. The CLR metadata model permits those different
member kinds to share a name, but Roslyn cannot author this C# source:

```text
CS0102: the type already contains a definition for depth/value/nodes
```

The fix is producer-selected hidden physical state names such as
`__kotlin_state_0_value`, while the public property retains `value`. This is
not a second carrier: the record and raw metadata audit still require exactly
one field of the original physical type. It also makes the test-owned producer
closer to idiomatic C# without changing Kotlin identity or semantics.

## Negative evidence

The architecture tests reject:

- duplicate Property names/rows;
- a partially serialized getter or setter;
- getter/setter type or shape disagreement;
- one MethodDef attached to multiple properties;
- a property accessor on another physical owner;
- a private or unrecorded member accessor; and
- a capability dispatcher presented as an ordinary property accessor.

## Verification

The focused PSI/LightTree × Framework 4.8/.NET 10 product matrix passed after
the backing-field correction. It compiles the producer and a separate C#
consumer, executes property/capability state identity, and runs the raw
metadata inspector in every lane.

The initial aggregate was intentionally diagnostic: all four representative
lanes failed with the same Roslyn field/property collision and the other 2,103
FIR tests passed. After the correction, the strict aggregate exited
successfully. The direct XML audit covers:

- dotnet.ir: one XML file / 6 tests;
- FIR: 187 XML files / 2,107 tests; and
- integration: two XML files / 125 tests.

The total is 190 XML files and 2,238 tests with zero failures, errors, or skips.

## Non-claims and next boundary

This slice does not select or emit the production public `C<T>` ABI. It does
not finish nullable-reference annotations, property naming/overload collision
policy, indexers/extension properties, broad semantic property hooks, or the
full reference/value/nullable/struct acceptance matrix. The next work remains
the next complete hostile migration condition; this result is not permission
for per-class easy-owner rollout.
