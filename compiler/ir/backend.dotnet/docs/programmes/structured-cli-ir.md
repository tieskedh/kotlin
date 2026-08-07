# Structured CLI representation programme

- Status: **Active — external AssemblyRef production slice complete**
- Decision authority: [`../decisions/cil-and-pe-production.md`](../decisions/cil-and-pe-production.md)
- Architecture authority: [`compiler-architecture.md`](compiler-architecture.md)

## Objective

Replace direct textual-IL construction incrementally with one compiler-owned,
serializer-independent representation of physical ECMA-335 programs. The
backend continues to own Kotlin semantics, ABI decisions, target-profile
legalization, export policy, instruction selection, and lowering. The low-level
`:dotnet:dotnet.ir` module owns only physical CLI vocabulary, its structural
invariants, deterministic text serialization, and eventually the already
selected JVM-hosted PE serialization sink.

This is not a second semantic IR and not an emitter rewrite performed in
advance. No CLI node is added without a concrete production producer and
consumer.

## Fixed boundaries

`dotnet.ir` may model every genuine CLI capability when a migrated feature
needs it, including generic types and methods. Capability is not policy:

- Kotlin-owned ordinary generic classes retain their one declaration-erased
  runtime owner and authoritative state;
- imported CLR generics retain native CLR identity;
- generic methods, exact interface capabilities, explicit .NET exports, and
  removable private optimizations may use truthful generic metadata under
  their owning decisions; and
- no serializer or CLI node selects a Kotlin representation or target profile.

The current artifact route remains deterministic CIL text followed by the
target-selected ILAsm. The direct production sink remains JVM-hosted; a
`System.Reflection.Metadata` sidecar is not silently reintroduced as a third
product architecture.

## Slice rule

Each migration slice must:

1. identify one existing closed textual production form;
2. add only the physical nodes that form requires;
3. reject malformed states before serialization;
4. render deterministically through `dotnet.ir`;
5. switch the real backend product to that route;
6. remove the old string construction for the migrated form; and
7. retain structural, exact-text, assembler, metadata, runtime, and
   separate-product evidence in proportion to the form's consequences.

Focused migration comparisons may run old and new rendering while a slice is
being developed. The committed production path has one renderer. Structural
model assertions supplement rather than replace representative ILAsm, PE, and
runtime evidence.

## First slice: external assembly references

The first slice owns external `AssemblyRef` identity only:

- assembly name;
- four-part CLI version;
- optional eight-byte public-key token; and
- deterministic textual `.assembly extern` serialization.

The backend still decides which selected Kotlin library or foreign CLR
assembly must be referenced and in which deterministic order. `dotnet.ir`
validates and serializes the resulting physical identities. Core-library
selection, the emitted Assembly row, module declaration, custom attributes,
managed resources, TypeDefs, signatures, and method bodies remain outside this
slice.

This slice is deliberately useful without predicting later nodes: ordinary
separate Kotlin libraries and foreign CLR consumers exercise the production
serializer, while unit tests can attack malformed versions, token lengths,
identifier escaping, mutation of supplied byte arrays, and deterministic
formatting.

The slice is complete. Selected Kotlin-library and foreign-CLR references now
use this model and renderer in the production emitter; their former direct
string construction is gone. Focused consumers cover version-only Kotlin
libraries and foreign references across both runtime profiles, while the
ordinary aggregate owns the model, compiler, ILAsm, packaging, and execution
regression evidence.

## Following slices

Choose the next smallest closed form after measuring and reviewing the first.
Likely candidates are the remaining module manifest, structured type and
member references, and then one complete compiler-generated method shape. No
candidate is pre-authorized merely by appearing in this list.
