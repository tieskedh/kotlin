# Draft ADR: Structured CLR importer boundary

- Status: **Draft candidate; physical declaration metadata and bounded type-identity resolution are implemented**
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

The third slice preserves CLR Property, PropertyMap, and MethodSemantics rows. A physical property
records its metadata token, declaring TypeDef, exact metadata name, flags, structural property
signature, index-parameter types, and original signature blob. Accessor association is taken only
from MethodSemantics; it is never inferred from `get_`/`set_` names. Each accessor remains the
original MethodDef, and non-accessor `Other` semantics are retained. The reader enforces CTS
structural rules such as one owner per Property, valid association kinds, and same-TypeDef
Property/accessor ownership, but it does not reject a valid CTS image merely for violating
optional CLS naming or accessor-shape conventions. Those coherence checks belong to the later
Kotlin import policy.

The Property signature grammar follows the official .NET augmentation as well as ECMA-335:
by-reference property results and index parameters can be represented physically, while invalid
headers, nil blobs, excessive counts, and trailing bytes fail as bad images. A direct Framework
and modern fixture deliberately uses property and accessor names that do not follow C# spelling,
proving that metadata association is authoritative. Corrupting the property signature header
fails closed. The scale lane also requires real `mscorlib` and .NET 10 `System.Runtime` Property
and MethodSemantics rows to decode.

The fourth slice preserves GenericParam and GenericParamConstraint rows. Each generic parameter
keeps its token, zero-based number, owner TypeDef/MethodDef, descriptive metadata name, raw flags,
variance, reference-type/value-type/default-constructor constraints, and the modern
`AllowByRefLike` flag. Each ordinary constraint keeps its own row token, GenericParam owner, and
TypeDef/TypeRef/TypeSpec handle. The physical layer does not normalize a TypeSpec constraint to a
TypeDef merely because both eventually resolve to the same type.

This follows the JVM importer's separation between raw foreign type parameters and later lazy FIR
bound enhancement. The CLR-specific divergence is necessary because declaration-site variance
and special constraints are runtime metadata, a constraint can itself be an open TypeSpec, and
.NET 10 adds `AllowByRefLike`; Java classfiles have no equivalent physical combination. The reader
validates flags, owner/name/number uniqueness, contiguous zero-based numbering, contiguous
constraint ownership, duplicate constraints, and agreement between a MethodDef signature's
generic arity and its GenericParam rows. Constraint classification as base class versus interface,
recursive TypeSpec resolution, variance-position legality, visibility, and profile compatibility
require the resolved assembly graph and remain importer-policy work.

No special CLR flag is silently reinterpreted as a Kotlin Common rule. In particular,
ReferenceTypeConstraint is not Kotlin definitely-non-null, NotNullableValueTypeConstraint is not a
Kotlin nullability annotation, DefaultConstructorConstraint has no direct Kotlin type-parameter
syntax, and AllowByRefLike requires a future explicit ref-like boundary. The physical reader
recognizes the modern flag profile-neutrally; the selected reference graph later decides whether
that metadata is legal for `net48`, `netstandard2.0`, or `net10.0`.

The shared IL fixture covers invariant, covariant, and contravariant type parameters plus class,
interface, and owner-type-parameter constraints under both Framework and modern ILAsm. A Roslyn
.NET 10 fixture independently covers reference/value/default-constructor flags, recursive
method-parameter constraints, and `allows ref struct`. A corrupted MethodDef generic count fails
against its GenericParam rows. The scale lane requires generic parameters and constraints in both
real Framework `mscorlib` and .NET 10 `System.Runtime`.

The fifth slice preserves MemberRef rows and their reusable FieldSig or MethodRefSig. Each
reference keeps its row token, exact MemberRefParent handle, metadata name, structural signature,
and original blob. The parent remains a TypeDef, TypeRef, ModuleRef, MethodDef, or TypeSpec handle;
a constructed generic owner is not flattened into a display name or substituted member copy.
Method references share the physical method-signature algebra with MethodDef, but admit the
call-site vararg sentinel and still reject non-managed calling conventions. Field references use
a standalone `DotNetClrFieldSignature` that can later be reused by FieldDef rather than creating a
second field-type model.

This follows the JVM rule that foreign linkage retains owner, member kind, name, and descriptor
until a later provider maps it to Kotlin. The CLR-specific shape is necessary because one
MemberRef table carries both fields and methods, can name a member through a TypeSpec owner, and
method return types and custom modifiers participate in physical identity. The physical layer
does not look up a same-named MethodDef, infer a property, or apply generic substitution.

FieldSig decoding follows the current .NET augmentation `FieldSig ::= FIELD Type`. It therefore
retains modern by-reference and typed-reference field forms instead of rejecting them according
to the older ECMA grammar. This does not make such fields ordinary Kotlin properties or permit
them on every target profile: legality depends on the declaring type, byref-like rules, selected
reference graph, and future import policy. Nested byrefs and void fields still fail structurally.

The Framework/modern IL fixture independently emits an external constructor MethodRef, an
external static FieldRef, and a MethodRef whose parent is a constructed generic TypeSpec. A
corrupted FieldSig header fails as a located bad image. A separate Roslyn .NET 10 producer and
consumer exercise a real `ref` field MemberRef, while the scale lane requires MemberRefs in both
Framework `mscorlib` and .NET 10 `System.Runtime`. This foundation is deliberately added before
semantic custom-attribute decoding because ordinary CustomAttribute constructors are commonly
identified through MemberRef; decoding their blobs without the constructor's physical parameter
signature would be guessing.

The sixth slice preserves Field rows. Each definition keeps its row token, declaring TypeDef from
the TypeDef FieldList partition, exact metadata name, raw FieldAttributes, reusable structural
FieldSig, and original blob. Field and method ownership share one bounded partition decoder; field
definitions and field references share one signature decoder. Visibility, static, init-only,
literal, special-name, and runtime-special-name facts remain CLR facts rather than Kotlin property
decisions.

This follows the JVM importer's field/member split: the physical owner, name, descriptor, and flags
exist before FIR decides whether or how a foreign field is exposed. The CLR difference is that an
enum's underlying storage is an instance field conventionally named `value__`, literal enum values
are separate static fields, and modern byref-like types may own by-reference fields. The reader
preserves those rows without assuming that every field is a Kotlin property or that `value__`
spelling alone proves an enum. Enum classification still requires its direct `System.Enum` base
and resolved core-library identity.

The reader rejects invalid FieldAttributes combinations, missing ownership, empty or malformed
FieldSig blobs, literal fields that are not static, init-only literal fields, and
runtime-special-name without special-name. It does not enforce optional CLS field naming and does
not decide whether a by-reference field is legal for the selected profile; those checks need the
resolved declaring type and reference graph.

The dual-ILAsm fixture covers generic-parameter fields, private static init-only fields, enum
`value__` storage, and enum literals. A corrupted FieldSig header fails closed. The Roslyn .NET 10
fixture exposes the same by-reference type through both its FieldDef and a consumer FieldRef,
proving that the shared signature model does not diverge. Real Framework `mscorlib` and .NET 10
`System.Runtime` provide scale coverage. FieldDef is a necessary input for semantically decoding
enum-valued custom attributes, but cross-assembly enum resolution still belongs to the importer
graph above the physical reader.

The seventh slice adds that physical assembly/type graph without yet creating Kotlin declarations.
The resolver consumes an explicit binding from each source AssemblyRef row to the concrete
assembly selected by the build frontend. It deliberately does not choose assemblies by simple
name, version heuristics, probing directories, or host-runtime reflection. This follows JVM and
Native dependency resolution: the class/symbol layer consumes an already selected dependency
graph rather than silently substituting another platform library. The CLR-specific work is to
follow TypeRef resolution scopes, nested TypeRefs, nominal TypeSpecs, ExportedType rows, and
assembly type-forwarder chains.

Every lookup is bounded and cycle-safe. Missing bindings, missing or ambiguous types, forwarding
cycles, non-nominal TypeSpecs, and unsupported ModuleRef/File multi-module edges are distinct
structured results. The latter are a recorded deferral rather than a name-based fallback:
supporting multi-module assemblies requires the build frontend to select modules and bind File
and ModuleRef edges just as it selects assemblies.

The physical reader retains ExportedType token, attributes, TypeDefId hint, namespace/name, and
Implementation handle. Current Roslyn output proves a CLR-specific augmentation to the classic
multi-module rules: an AssemblyRef forwarder root carries `Forwarder` while its ordinary
visibility bits are `NotPublic`, and automatically emitted nested descendants point to the
enclosing ExportedType with flags `0`. The reader accepts that shape only when the enclosing chain
ends in a marked AssemblyRef forwarder. Ordinary File exports must remain public and ordinary
nested multi-module exports must remain nested-public. No C# source attribute is consulted during
resolution.

Enum classification requires the resolved declaring TypeDef to extend exactly the selected
core-library `System.Enum` TypeDef supplied by the profile graph. Only then is the single
runtime-special instance field `value__` interpreted as storage, and its physical type must be
one of the eight CLR signed/unsigned integer primitives. A same-named base or field does not
establish enum identity. The resolver does not map that storage to a Kotlin enum, numeric type, or
annotation argument; it supplies the physical width needed by the later semantic
CustomAttribute decoder.

A real Roslyn .NET 10 destination/facade pair pins root and nested type-forwarding, exact
destination identity, a forwarded `Int16` enum, missing bindings, ambiguity, cycles, and the
explicit multi-module deferral. The existing Framework fixture independently resolves its
`Int16` enum against the selected `mscorlib` `System.Enum`. The algorithm is profile-neutral;
different emitted/reference-assembly graphs across `net48`, `netstandard2.0`, and `net10.0` are
inputs, not reasons to change Kotlin Common semantics.

The eighth slice preserves CustomAttribute rows before interpreting their values. Each row keeps
its own token, exact HasCustomAttribute parent, MethodDef-or-MemberRef constructor handle, and the
raw value bytes. A nil Value index remains distinct from a present zero-length blob. Rows are not
grouped or deduplicated: attachment and multiplicity are semantic inputs, and two identical
attribute rows remain two occurrences.

This matches the JVM importer rule that annotation ownership and repeated annotations are retained
before annotation arguments are enhanced or mapped to Kotlin. The CLR-specific difference is the
wide HasCustomAttribute coded-index domain and the constructor indirection through either a local
MethodDef or an external MemberRef. The reader validates coded handles and bounds the blob but does
not yet decide whether the constructor shape or blob content is a legal custom attribute. That
decision requires constructor resolution and enum storage from the selected assembly graph.

The dual-ILAsm fixture attaches two byte-identical `ObsoleteAttribute` rows to one TypeDef and
asserts both rows, their shared external constructor identity, and their original value bytes.
Real Framework `mscorlib` and .NET 10 `System.Runtime` supply broad parent/constructor/value scale.
This row layer remains profile-neutral and does not turn a CLR attribute into a Kotlin annotation.
The next slice resolves constructors and decodes fixed and named arguments into semantic values;
only that decoded form participates in ordinary attribute comparison.

Observing a TypeSpec where a source spelling looked like a simple base type remains valid physical
evidence, not permission to coerce that token to a TypeDef or a Kotlin type. Property, field,
resolved generic-constraint, and nullable-attribute projection still remain above or after this
physical foundation, and no FIR declaration is created yet. In this sentence,
“Property projection” means Kotlin-facing property synthesis; physical Property rows and
associations are already implemented, as are unresolved physical GenericParam constraints and
MemberRef/FieldDef signatures. “Field projection” likewise means Kotlin-facing import, not the
already implemented physical rows.

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
- Lossless MemberRef and reusable FieldSig model: **Correct direction**.
- Physical FieldDef preservation on the reusable FieldSig model: **Correct direction**.
- Physical Property/PropertyMap/MethodSemantics preservation: **Correct direction**.
- Physical GenericParam/GenericParamConstraint preservation: **Correct direction**.
- Separate lazy FIR import policy/provider: **Correct direction**.
- Reusing embedded KLIB for Kotlin-produced DLLs: **Correct direction**.
- Mapping raw CLR rows directly to Kotlin IR: **Architecturally wrong and should be changed**.
- Remaining semantic custom attributes, type forwarding, Kotlin-facing
  property synthesis, events, resolved constraint semantics, nullability enhancement, FIR symbols,
  and backend calls:
  **Deferred problems that must be recorded before the importer surface becomes stable**.

The cost is a substantial target-owned metadata and import layer. The alternative is greater:
spreading partial CLR decoding and C# assumptions across resolution, IR lowering, codegen, and
tooling would create untestable semantic drift and ABI debt.
