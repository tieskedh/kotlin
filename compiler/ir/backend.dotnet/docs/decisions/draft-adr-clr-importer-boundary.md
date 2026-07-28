# Draft ADR: Structured CLR importer boundary

- Status: **Draft candidate; the read-only physical metadata/signature foundation is implemented**
- Date: 2026-07-28
- Scope: ordinary foreign CLR assemblies referenced by Kotlin/.NET compilations

This is a repository-local decision record for the experimental .NET backend. It does not freeze
an importer ABI or a Kotlin source mapping.

## Context

The compiler currently consumes Kotlin-produced self-describing DLLs through their embedded KLIB.
That is the correct Kotlin-to-Kotlin path, but it does not make an ordinary CLR DLL a Kotlin
library. End users also need to reference BCL, C#, F#, and other managed assemblies whose only
authoritative declaration model is ECMA-335 metadata.

Treating those assemblies as Kotlin libraries would be wrong: CLR metadata does not contain
Kotlin declaration identity, source visibility, extension receivers, expect/actual information,
or Kotlin nullability. Treating them as C# source would also be wrong: C# is one CLR producer, and
its display names and convenience rules are not the physical ABI.

## Precedent from mature targets

JVM keeps Kotlin metadata and foreign Java classfiles on different paths. A classfile-backed Java
model is exposed through `JavaClassFinder`/`JavaSymbolProvider`; FIR then applies Java-specific
type enhancement and synthetic-property policy. The byte reader does not invent Kotlin
declaration identity, and backend codegen still targets the actual JVM member signature.

Native likewise keeps foreign platform description at an importer boundary. C/Objective-C
interop reads the foreign model and produces Kotlin-facing declarations and bridges rather than
teaching arbitrary backend phases to infer source semantics from native symbols. JS and Wasm
require explicit external/import declarations instead of treating host names as Kotlin
declarations.

The uniform architectural rule is therefore:

> Decode the target's physical foreign declaration model first, map it to Kotlin in a dedicated
> importer/symbol-provider layer second, and keep backend linkage attached to the original physical
> identity.

## CLR-specific constraints

The CLR physical model differs materially from JVM classfiles and native headers:

- a DLL is already a self-describing metadata graph with AssemblyRef, TypeRef, TypeDef, TypeSpec,
  MemberRef, MethodDef, Property, Event, MethodImpl, GenericParam, custom-attribute, and forwarding
  rows;
- nested type identity, generic arity, declaration-site variance, constraints, by-ref forms,
  custom modifiers, calling conventions, and return type all participate in physical meaning;
- properties and events are first-class metadata groupings whose accessor methods retain their
  own physical identities;
- nullable-reference information is optional flow metadata expressed mainly through attributes,
  not a CLR type-system distinction;
- `net48`, `netstandard2.0`, and `net10.0` may expose different reference assemblies and legal
  runtime features even when Kotlin source semantics stay the same; and
- loading target code with reflection is neither safe nor viable for a JVM-hosted cross compiler.

These are valid reasons for a CLR-specific physical model. They are not reasons to bypass FIR,
reuse C# display rules as Kotlin rules, or create a second Kotlin identity namespace.

## Decision

The importer is layered:

```text
bounded PE/ECMA-335 reader
          |
immutable physical CLR model
          |
CLR-to-Kotlin import policy
          |
lazy FIR symbol provider
          |
IR with retained physical linkage
```

1. The JVM-hosted PE reader owns bounds checking, metadata stream/table decoding, coded handles,
   signature blobs, and custom-attribute serialization. It never loads the target assembly.
2. The immutable physical model preserves metadata names, tokens, flags, row multiplicity,
   nesting, signatures, and assembly scope. It contains no Kotlin or C# source interpretation.
3. A separate import-policy layer will map supported CLR constructs to Kotlin-facing foreign
   declarations. Unsupported physical forms fail with a located importer diagnostic rather than
   being erased into a plausible but false Kotlin type.
4. A target-owned lazy FIR symbol provider will expose those declarations, following the JVM
   provider architecture. The backend will receive explicit retained CLR owner/member linkage; it
   must not rediscover a MethodDef from a Kotlin or C# display name.
5. Kotlin-produced DLLs continue to use embedded KLIB as authoritative Kotlin identity.
   Their ordinary CLR metadata may be cross-checked, but is not re-imported as foreign API.
6. CLR Property rows remain first-class importer input. A later Kotlin-facing property may be
   synthesized only when its getter/setter association, types, accessibility, staticness, and
   override shape are coherent. Accessors remain callable physical MethodDefs. C# naming
   conventions do not rename Kotlin declarations.
7. Ordinary custom attributes are compared by decoded semantic constructor arguments, named
   arguments, attribute type, and multiplicity. Raw blob bytes are not semantic equality because
   legal encoders may choose different byte layouts for equivalent content. Raw bytes may be
   retained for diagnostics and unsupported-form rejection.

The first implemented slice exposes assembly identity, AssemblyRef rows, TypeRef rows, TypeDef
rows, TypeDefOrRef base handles, nested-owner edges, and raw type flags/visibility.

The second slice adds a lossless physical signature algebra and decodes TypeSpec and MethodDef
signatures. It preserves, rather than display-renders:

- primitive kinds and `class` versus `valuetype`;
- TypeDef/TypeRef/TypeSpec handles;
- type and method generic-parameter positions;
- generic instantiations;
- managed and unmanaged pointers, by-reference parameters/returns, and typed references in their
  legal grammatical contexts;
- SZARRAY versus general arrays, including rank, sizes, and signed lower bounds;
- required and optional custom modifiers in the positions accepted by the selected CLR profile;
- function-pointer calling convention, `this` flags, generic arity, and vararg boundary; and
- the original blob for diagnostics and unsupported-form rejection.

The decoder follows ECMA-335 plus the official
[.NET ECMA augmentations](https://github.com/dotnet/runtime/blob/main/docs/design/specs/Ecma-335-Augments.md)
rather than treating the older grammar diagrams as a complete description of accepted CLR
metadata. In particular,
MethodDef signatures cannot contain a call-site vararg sentinel, by-reference and typed-reference
forms cannot become arbitrary generic arguments, and compressed integers must use their canonical
width. Conversely, custom modifiers are accepted at the additional type positions used by the CLR
and current .NET reference assemblies, and a modifier may retain a TypeSpec handle. The reader
does not recursively resolve that handle; the later resolver must reject cycles. A
`CLASS`/`VALUETYPE` or generic-type constructor token is restricted to TypeDef/TypeRef as required
by the actual CLR, even though older ECMA text names the broader coded-index family. The physical
MethodDef owner is derived from the TypeDef MethodList partition and remains a metadata handle.
Raw blob equality is not promoted to Kotlin type equality.

One direct fixture assembled independently by Framework and modern ILAsm covers generic TypeSpec,
type and method generic parameters, by-reference parameters, SZARRAY, and bounded multidimensional
arrays including two- and four-byte negative lower bounds. A copied assembly with a corrupted
method-signature blob must fail as a located bad image. The compiler test does not invoke
reflection or Roslyn to perform production decoding; `System.Reflection.Metadata` remains an
independent oracle in the existing C# test tooling. The same test walks the real Framework
`mscorlib` implementation and the .NET 10 `System.Runtime` reference assembly; the latter pins a
modified TypeSpec root that the older ECMA TypeSpec diagram omits.

Observing a TypeSpec where a source spelling looked like a simple base type remains valid physical
evidence, not permission to coerce that token to a TypeDef or a Kotlin type. Property, field,
MemberRef, generic-constraint, and nullable-attribute projection still remain above or after this
signature foundation, and no FIR declaration is created yet.

## Kotlin Common invariant

Foreign import convenience must not change Kotlin-defined semantics:

- Kotlin-owned declarations retain their KLIB identity and nullability;
- CLR reference types without supported nullable metadata do not become definitely non-null merely
  because C# syntax omitted `?`;
- CLR variance and overload rules inform the foreign declaration surface but do not redefine
  Kotlin variance, equality, override, or exception semantics;
- CLR value types, by-ref values, pointers, ref-like types, delegates, and arrays require explicit
  import mappings and cannot be approximated as ordinary Kotlin classes; and
- profile selection may change API availability or physical implementation, never the meaning of
  the same common Kotlin declaration.

## Profile rule

The importer reads the selected profile's reference assemblies and preserves their AssemblyRef
scope. `netstandard2.0` compilation sees only the portable contract. `net48` and `net10.0` may see
their profile-specific contracts. Type forwarding and facade assemblies must be resolved as CLR
metadata rules, not by substituting a same-named type from another profile.

The physical reader itself is profile-neutral; profile legality and reference resolution belong
above it. This permits the same malformed-image and metadata-shape tests on every profile without
pretending their API sets are identical.

## Rejected alternatives

### Reuse the Java model as though CLR were Java

This would lose Property/Event rows, return-type identity, CLR generic constraints, custom
modifiers, nested metadata identity, and CLR-specific accessibility. Similar FIR architecture does
not imply identical foreign type models.

### Invoke Roslyn or reflection as an importer sidecar

That would make a JVM-hosted compiler depend on a target runtime/toolchain process, risk loading
untrusted target code, and add a private serialization protocol. Roslyn remains useful as an
independent compatibility oracle in tests, not as the compiler's declaration database.

### Infer foreign declarations from textual IL or C# display names

Text rendering is not the metadata graph, and C# names are not CLR tokens. Either approach loses
overloads and exact slot identity and would make tooling conventions redefine Kotlin resolution.

## Classification and consequences

- Bounded JVM-hosted physical reader: **Correct direction**.
- CLR-specific immutable metadata model: **Reasonable platform-specific divergence**.
- Lossless TypeSpec and MethodDef signature model: **Correct direction**.
- Separate lazy FIR import policy/provider: **Correct direction**.
- Reusing embedded KLIB for Kotlin-produced DLLs: **Correct direction**.
- Mapping raw CLR rows directly to Kotlin IR: **Architecturally wrong and should be changed**.
- Remaining field/property/MemberRef signatures, semantic custom attributes, type forwarding,
  properties, events, generic constraints, nullability enhancement, FIR symbols, and backend calls:
  **Deferred problems that must be recorded before the importer surface becomes stable**.

The cost is a substantial target-owned metadata and import layer. The alternative is greater:
spreading partial CLR decoding and C# assumptions across resolution, IR lowering, codegen, and
tooling would create untestable semantic drift and ABI debt.
