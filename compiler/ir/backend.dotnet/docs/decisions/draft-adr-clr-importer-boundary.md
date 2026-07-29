# Draft ADR: Structured CLR importer boundary

- Status: **Draft candidate; physical declaration and Param-attachment metadata, bounded type/constraint/hierarchy resolution with primitive-aware nominal, generic-interface/delegate variance, complete CLR vector-interface, and array assignability, sealed aggregate constraint status, nominal plus reference/value/default-constructor/by-ref-like constraint validation, scope-qualified open-parameter implication, and verified physical reference/value/Nullable/by-ref-like classification, custom-attribute values through closed generic attribute owners, and selected-graph named-member validation are implemented**
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

The assembly-identity continuation retains the Assembly definition's full public key and computes
its standard eight-byte public-key token. The earlier `hasPublicKey` boolean was insufficient for
resolved foreign type identity: same name/version/culture is not a complete strong name. JVM and
KLIB dependency layers likewise retain the producer identity chosen by the dependency graph;
the CLR-specific addition is its strong-name key/token.

This does not turn the physical resolver into an assembly binder. An AssemblyRef is allowed to
omit its key/token, as the deliberately minimal Framework ILAsm fixture does, and .NET binding can
apply profile-owned version/unification policy. The build frontend still selects the edge. The
resolved destination nevertheless retains its complete producer identity for diagnostics and
later semantic attribute type keys. Real signed `mscorlib` and `System.Runtime` definitions pin
non-empty keys and eight-byte tokens; a Roslyn .NET 10 AssemblyRef independently matches the
computed destination token.

The ninth slice first resolves the constructor edge before reading the custom-attribute value blob.
This
follows the JVM importer rule that a foreign annotation class and constructor are resolved before
annotation arguments are interpreted. The CLR-specific difference is that the constructor token
is either a local MethodDef or a MemberRef. The initial slice admits a TypeDef/TypeRef owner; the
twentieth slice below adds the closed TypeSpec owner required by generic attributes. The resolver
requires the exact `.ctor` instance shape, non-generic default calling convention, `void` return,
and a concrete attribute class derived from the caller-supplied selected core-library
`System.Attribute` TypeDef. It never infers attribute identity from a type suffix or constructor
name alone.

The base-type walk reuses the bounded physical type resolver and returns structured unresolved,
cycle, and limit results. This keeps the algorithm profile-neutral: `net48` supplies the
`System.Attribute` identity selected from its Framework graph, while `netstandard2.0` and
`net10.0` supply the identity selected from their own reference graph. The same Kotlin Common
rule applies on every profile, and this layer still creates neither a Kotlin annotation nor a C#
declaration.

The Framework fixture resolves two external MemberRef constructors on
`System.ObsoleteAttribute`, rejects a field MemberRef and a constructor whose owner is
`System.Object`, and retains both attribute occurrences. A Roslyn .NET 10 fixture resolves two
local MethodDef constructors on a custom attribute with an `Int32` parameter. A synthetic cyclic
inheritance graph proves that malformed metadata terminates with a structured cycle result.
At this slice, MemberRefs owned by a constructed TypeSpec failed with a dedicated unsupported-
parent result. That was a correct temporary boundary: resolving such owners requires a
constructed declaring-type identity and substitution model shared with the general CLR member
resolver. The eighteenth through twentieth slices add that model and remove the boundary.

The tenth slice decodes scalar fixed arguments from the value blob after constructor resolution.
As on JVM, the constructor's resolved parameter types determine how bytes become typed values;
the importer does not guess a value type from its width. The CLR-specific representation is the
ECMA-335 `CustomAttrib` prolog, little-endian scalar payloads, packed UTF-8 `SerString`, and final
named-argument count. The implementation follows the same type-code split as
`System.Reflection.Metadata`'s `CustomAttributeDecoder`, but runs on the JVM and never loads the
target assembly.

The decoded model distinguishes booleans, UTF-16 code units, integral type plus exact bits,
IEEE-754 payload bits, and nullable strings. Exact float bits are retained because reflection
consumers can observe signed zero and NaN payloads; any compatibility normalization must be a
separate explicit policy rather than accidental loss in the reader. A nil blob is accepted only
for a zero-parameter constructor. The reader rejects invalid prologs, truncation, non-`0`/`1`
booleans, malformed or non-canonical packed lengths, invalid UTF-8, and trailing bytes.
Constructor handles are qualified by the assembly containing the CustomAttribute row, because a
metadata token is not globally unique.

This remains uniform across `net48`, `netstandard2.0`, and `net10.0`: the profiles can expose
different attribute classes, but the value format and Kotlin Common boundary do not change.
Arrays, tagged `object`, `System.Type`, enum, and named arguments return structured unsupported
results rather than being approximated. They are the next semantic slices; only after all
supported values are typed may the import policy project an ordinary CLR attribute into a
Kotlin-facing declaration or compare profile surfaces.

The real Framework fixture covers the legal no-argument representation plus malformed scalar
blobs. The Roslyn .NET 10 fixture covers signed and unsigned integers, nullable UTF-8 strings,
booleans, characters, signed zero, and NaN. Hostile cases pin cross-assembly token mismatch,
truncation, invalid boolean and UTF-8 data, non-canonical string lengths, trailing bytes, and the
explicit named/tagged-object deferrals.

The eleventh slice generalizes that constructor-driven decoder to one-dimensional arrays and
tagged `object` values. JVM annotation readers likewise retain typed arrays and element values;
the CLR-specific difference is that an `object` payload carries a serialization type code and an
`object[]` can therefore contain differently typed elements. A null object emitted by Roslyn is a
tagged nullable string value, while a null array is the distinguished signed length `-1`. The
decoder preserves those actual CLR value shapes and does not manufacture Kotlin `Any` or
`Array<T>` semantics.

One recursive value-type algebra now drives primitives, tagged values, and SZARRAY values.
Constructor signatures remain authoritative for fixed arguments; only a tagged object reads its
actual type from the value blob. Nested SZARRAY element types are rejected because custom
attribute arrays are one-dimensional and non-jagged. Arrays retain their declared element type
even when null or empty. This matches `System.Reflection.Metadata` decoding and ordinary CLR
reflection instead of introducing a target-specific value convention.

The reader bounds arrays to one million decoded elements and tagged recursion to 32 levels. These
are untrusted-input resource guards, not ABI limits: exceeding them returns a located structured
failure and never changes a valid value into another value. Impossible negative lengths, unknown
serialization codes, truncated payloads, and nested array type codes also fail structurally.
The policy is identical on all three profiles because ECMA-335 value encoding is identical;
profile-specific API availability remains an input to later type resolution.

The Roslyn .NET 10 fixture covers non-empty, empty, and null arrays, a scalar boxed integer, and a
heterogeneous object array containing string, integer, and null values. Framework hostile blobs
cover invalid and oversized lengths, pre-allocation truncation detection, unknown and unresolved
tagged types, jagged arrays, and excessive boxing depth. `System.Type`, enums, and named
field/property arguments remain the next semantic type-resolution layer.

The twelfth slice decodes enum fixed arguments whose exact type comes from the resolved
constructor signature. JVM annotation deserialization likewise keeps an enum declaration identity
plus its entry/value rather than erasing it to the underlying integer. The CLR difference is that
the blob stores only the enum's underlying bits, so the decoder resolves the signature's
TypeDef/TypeRef through the selected assembly graph and reuses the exact `System.Enum` plus
`value__` storage proof before consuming those bits.

The decoded value therefore retains both the resolved enum TypeDef identity and an integral value
whose signedness and width exactly match the physical storage field. Empty and non-empty enum
arrays retain that same element identity. A named signature encoded as `class` when it resolves
to an enum is malformed rather than a request to box it; unresolved type edges and invalid enum
storage are located structured failures. Resolved type wrappers compare by selected assembly
instance plus metadata handle, making repeated resolver results usable as physical graph keys
without pretending that row numbers are cross-build ABI identity.

This again does not vary by profile. Framework resolves a synthetic fixed value against its
selected `mscorlib` `System.Enum`; real Roslyn .NET 10 output resolves `Int16` enum scalar and
array values through `System.Runtime` forwarding. Kotlin Common still sees no Kotlin enum until
the import policy deliberately projects the foreign declaration. Boxed enums, named enum
arguments, and `System.Type` values remain unsupported because their types are serialized as CLR
reflection names; the next layer must parse and resolve those names rather than treating strings
as type identity.

The thirteenth slice parses the CLR reflection type-name syntax used inside `System.Type` and
serialized enum values without binding it. JVM class-literal annotation arguments likewise pass
through a structural class/type model before symbol resolution. The CLR-specific grammar adds
escaped identifiers, nested `+` components, backtick arity, bracketed assembly-qualified generic
arguments, pointer/by-reference suffixes, SZARRAY versus multidimensional arrays, and an optional
AssemblyName display-name tail.

The result retains top-level namespace and metadata name, every nested metadata-name component,
per-component generic arity, recursively parsed generic arguments, normalized modifiers, and the
unparsed assembly display name. `[,]` and `[*,*]` normalize to the same rank-two array because the
CLR treats them as the same completed-runtime type. In contrast, ``Pair`2[,]`` remains an open
generic type followed by an array modifier, not an invented empty generic-argument list.
Reflection.Emit-only bounded array forms return structured unsupported results.

Assembly display-name property parsing and assembly binding are deliberately not part of this
syntax layer. The build frontend still owns the selected graph, and later resolution must check a
parsed qualifier against that graph rather than call host `Type.GetType`, probe the filesystem, or
choose an assembly by simple name. This preserves Kotlin Common's type identity boundary and
works identically for names produced on `net48`, `netstandard2.0`, and `net10.0`.

The JVM-hosted parser bounds input length, recursive generic depth, generic argument count, and
component count. It rejects invalid escapes, arities, empty/mismatched generic arguments, malformed
by-reference shapes, empty assembly tails, and unexpected tokens with exact offsets. Tests cover
escaped namespace/nested characters, open and constructed generics, assembly-qualified arguments,
arrays/pointers/byrefs, equivalent array spellings, the open-generic-array ambiguity, malformed
forms, and excessive nesting. Real Roslyn value integration follows after assembly-name parsing
and selected-graph resolution.

The fourteenth slice parses the retained AssemblyName display-name tail without binding it. This
follows the same division used by JVM classpath resolution: parsing a binary name or descriptor
does not authorize the class finder to choose an arbitrary archive. The CLR-specific syntax has a
quoted/escaped simple name and case-insensitive `Version`, `Culture`, `PublicKeyToken`,
`PublicKey`, `ProcessorArchitecture`, `Retargetable`, and `ContentType` properties.

The parser mirrors the .NET runtime lexer for whitespace, quotes, and `\\`, `\,`, `\=`, quote,
tab, carriage-return, and newline escapes. Known properties are validated and may occur once;
`PublicKey` and `PublicKeyToken` are mutually exclusive. The parsed model distinguishes an absent
key/token from explicitly null, retains whether full public-key identity was requested, and keeps
two through four exact version components. Unknown properties are accepted and retained, matching
Desktop compatibility, but they do not silently become Kotlin or compiler ABI.

Keeping version components exact instead of immediately applying host `System.Version` behavior
is deliberate. `net48`, `netstandard2.0`, and `net10.0` can have different binding/unification
policy, and the build frontend must select the graph edge. The next resolver passes this parsed
qualifier to that frontend-owned binder and then verifies the returned producer identity; neither
this parser nor the physical type resolver probes by name.

Tests cover quoted names and values, escaped separators, the full known property set, the official
`neutral` and explicit-null forms, unknown-property retention, duplicate public-key identity,
invalid tokens/versions, missing values, and unclosed quotes. This parser is a CLR-specific
physical necessity; it does not alter Kotlin Common type identity.

The fifteenth slice resolves a parsed serialized type only through a new build-frontend-owned
binder. This matches JVM classpath and Native platform-library architecture: the declaration/type
resolver consumes an already selected dependency graph and does not discover dependencies itself.
The CLR-specific binder input contains the original attribute-owning assembly, the current
unqualified-name context assembly, and the parsed optional AssemblyName qualifier. That is enough
for profile-owned version unification, retargeting, facade, and unqualified custom-attribute-name
policy without hard-coding one runtime's loader behavior in the importer.

After binding, the existing bounded TypeDef/TypeRef/ExportedType resolver selects the exact
top-level and nested TypeDef. The serialized resolver verifies that the sum of encoded
per-component generic arities equals the final TypeDef's physical GenericParam count, recursively
resolves constructed arguments, and applies pointer, by-reference, SZARRAY, and
multidimensional-array modifiers into a typed resolved algebra. Open generic types remain named
definitions; constructed types never lose their argument identities.

An unqualified generic argument receives the selected enclosing type's assembly as context while
the binder also retains the original attribute-owning assembly. This is a deliberate interface
instead of a guessed search order. Invalid type syntax, unsupported Reflection.Emit shapes,
invalid AssemblyName syntax, unbound selected edges, unresolved TypeDefs, and generic-arity
mismatches are distinct results. Nested generic-argument failures carry an index path.

The real .NET 10 destination fixture resolves a nested ordinary type and a nested generic type
whose arguments come from `System.Runtime` and the destination assembly, then preserves array,
pointer, and by-reference shape. It also proves unqualified argument context, invalid and unbound
qualifiers, and a missing assembly inside a generic argument. The algorithm is profile-neutral;
the supplied binder is where `net48`, `netstandard2.0`, and `net10.0` select different graphs.

The sixteenth slice feeds that resolved type algebra back into ordinary custom-attribute values.
JVM annotation deserialization represents a class literal by its resolved class/type identity and
an enum argument by its enum declaration plus value; JS, Wasm, and Native retain the corresponding
KLIB declaration identity before their platform representations are produced. None treats a
serialized display name or an enum's storage integer as the semantic value. The .NET-specific
difference is physical only: ECMA-335 stores a `System.Type` argument as a nullable reflection
`SerString`, and a boxed or named enum prefixes its storage bits with serialization code `0x55`
and a reflection type name. This slice consumes that form for boxed enums; named field/property
arguments reuse it in the following slice.

The custom-attribute decoder therefore receives the same selected-graph serialized-type resolver
as the rest of the importer. A non-null `System.Type` value retains the complete resolved
named/generic/pointer/by-reference/array algebra, while an encoded null remains a distinct null
type value. `Type[]` retains `System.Type` as its element kind even when empty or null. A serialized
enum name must resolve to either one named TypeDef or a constructed instance of one named TypeDef,
with no pointer, by-reference, or array modifier, and then pass the existing exact selected-
`System.Enum` ancestry and `value__` storage proof before any bits are read. The complete
constructed identity is retained because C# enums nested in generic classes are themselves
generic CLR types. Boxed enum values use the same enum-value algebra as constructor-typed fixed
enums; no second identity or body exists for the boxed case.

This does not transgress Kotlin Common: the layer creates neither a Kotlin class literal nor a
Kotlin enum entry, and it does not equate a foreign enum with a Kotlin enum. It only preserves the
foreign semantic identity needed by later import policy. It also follows the same ECMA-335 value
rules on `net48`, `netstandard2.0`, and `net10.0`; profile differences belong solely to the
frontend-selected assembly binder. The decoder never invokes host `Type.GetType`, probes the
filesystem, falls back by simple name, or lets C# display spelling become an identity.

Invalid type syntax, AssemblyName syntax, binding, or TypeDef resolution is returned with the
complete serialized-type-resolution result. A resolved non-enum, an enum name carrying a pointer,
by-reference, or array modifier, invalid enum storage, and a null enum name are invalid enum values
rather than integers or skipped arguments. Real Roslyn .NET 10 output covers constructed nested
`System.Type`, type arrays, null type values, boxed type values, ordinary boxed `Int16` enums, and
two constructed views of an `Int16` enum nested in a generic class. The Framework fixture
independently covers qualified boxed types and enums plus unbound assemblies and false enum
identities. Named field/property arguments remain the next slice because they additionally require
physical member resolution and assignment-shape validation.

At this slice, a constructor parameter whose fixed enum type was itself a constructed nested enum
still returned the structured unsupported fixed-type result. That temporary boundary required the
general TypeSig-to-resolved-structural-type substitution model also needed by constructed members
and the FIR importer. The eighteenth and nineteenth slices below add and consume that general
model; no custom-attribute-only signature resolver was introduced.

The seventeenth slice decodes the ordered named-argument sequence after the fixed arguments. JVM
binary annotation visitors and KLIB annotation records first retain an argument name plus typed
constant; class/member projection happens in the frontend layer. The CLR-specific record contains
two additional physical facts: a `0x53` field versus `0x54` property kind and an explicit
`FieldOrPropType` before the `SerString` name and value. The implementation follows
`System.Reflection.Metadata.CustomAttributeDecoder` and feeds that type back through the same
primitive/array/tagged/System.Type/serialized-enum value algebra.

Each decoded named argument therefore retains its field/property kind, exact non-empty CLR name,
declared serialized value type, and semantic value. The list retains source order and duplicates.
It is deliberately not converted to a Kotlin argument map: CLR attribute instantiation applies the
assignments in encoded order, malformed input can repeat a name, and the importer must not erase
observable or diagnostic evidence before policy. Invalid kind codes, nil or empty names,
truncation, serialized-type failures, and enum failures report the named-argument index separately
from a fixed-argument index.

The blob does not contain a FieldDef, Property, or accessor token. Resolving the record to whichever
member happens to exist in the currently selected attribute assembly would therefore be validation,
not recovery of encoded identity. The authoritative semantic record remains kind/name/type/value.
A later selected-graph validator must apply CLR inheritance and hiding plus the language/runtime
requirements for a public non-static writable field or public get-and-set/init property, and must
verify assignment-compatible physical type. Its result may diagnose an unusable attribute or
support Kotlin projection, but it must not replace the encoded record with a MemberDef identity.

This division preserves Kotlin Common because no foreign field or property is manufactured as a
Kotlin annotation parameter yet. It also obeys the same ECMA-335 format on `net48`,
`netstandard2.0`, and `net10.0`; only later member binding sees profile-specific assembly graphs.
Real Roslyn .NET 10 output covers field and property arguments containing scalars, null strings,
`System.Type`, enums, boxed enums, and null/non-null arrays. Framework synthetic metadata proves
ordered duplicate retention and located invalid kind, name, and truncation failures.

The eighteenth slice adds that selected-graph validator and, first, the general resolved-signature
foundation it requires.

1. JVM and KLIB-backed targets begin with the annotation declaration's logical parameter identity
   and validate the typed constant against that declaration. They do not use a foreign runtime's
   opportunistic reflection lookup as declaration identity. The .NET backend follows the same
   layering: encoded kind/name/type/value remains the annotation-like record, while a separately
   resolved CLR member proves that the record is usable.
2. The unavoidable CLR difference is that a custom-attribute named argument contains no
   FieldDef/Property token. CoreCLR first decodes the name and type and later enumerates or looks
   up members on the currently loaded attribute class. Its `CustomAttributeData` path and actual
   instance-creation path even differ for malformed or version-skewed metadata. The compiler
   therefore validates the documented ordinary CLR contract rather than treating either runtime
   implementation quirk as Kotlin semantics.
3. Kotlin Common is unchanged. Validation creates no Kotlin property, annotation parameter, or
   default value and never changes the decoded sequence. A later FIR projector may consume only a
   valid result, but the foreign CLR record and its multiplicity remain available for diagnostics
   and semantic comparison.
4. The rules are legal on every supported profile: a named field must be public, instance, and
   writable; a named property must be non-indexed and have public instance getter and setter/init
   accessors with consistent physical signatures. `net10.0` init-only setters carry a required
   modifier on the setter return; resolved signatures preserve that modifier while accessor-shape
   comparison deliberately ignores modifiers. `net48` and `netstandard2.0` need no imitation of
   init syntax. All three profiles otherwise use the same validation algorithm over their
   frontend-selected reference graph.
5. As on the mature importers, type identity is resolved before language projection. A new
   assembly-context-bearing signature algebra resolves every nominal node in TypeDef/TypeRef/
   TypeSpec, generic-instance, modifier, array, pointer, by-reference, and function-pointer
   structure. It can then substitute a generic base view without losing which assembly owns a
   nested handle. This is the general TypeSig-to-resolved-structural-type layer previously
   required by the ADR, not a custom-attribute-only substitute.
6. Where CLR metadata still permits several behaviours, the selected rule is the one a Kotlin core
   review should choose: the encoded field/property tag is authoritative; lookup is restricted to
   that member species; the nearest declaring level with that kind and name hides older levels;
   the encoded value type must exactly match the substituted physical type; ambiguous members,
   repeated assignment to the same resolved member, cycles, bad arity, invalid accessors, and
   resource-limit exhaustion are structured invalid results. No lookup fallback repairs an invalid
   producer.

The generic substitution is not theoretical. Roslyn accepts a non-generic attribute deriving from
`BaseAttribute<int>` and emits named assignments to inherited `T` fields and properties. The
validator resolves the base TypeSpec, proves its physical generic arity, substitutes `T = int`
through both member and accessor signatures, and retains the declaring base view next to the
original encoded argument. Real Roslyn .NET 10 coverage includes that field/property pair, an
ordinary setter, an init setter, and an exactly typed property whose enum is nested in a closed
generic owner. It also checks non-public/static/readonly fields, static/read-only/write-only/
private-set/indexed properties, missing members, type mismatch, field-versus-property mismatch,
ordered duplicates, invalid generic arity, inheritance cycles and limits, and duplicate
MethodSemantics accessors.

The nineteenth slice uses the same resolved-signature model for constructor-typed fixed arguments
whose enum identity is a closed generic instance.

1. JVM and KLIB-backed targets retain an enum argument's declaration/type identity and its value;
   they do not reduce a generic enum owner to an unqualified declaration name or storage integer.
   The .NET importer must preserve the equivalent complete constructed CLR identity.
2. The platform difference is physical: a CLR constructor signature can encode the enum as a
   `GenericInstance` TypeSig whose arguments include primitive signature codes and nominal types
   from several assemblies, while the attribute blob contains only the enum's underlying bits.
   The decoder must therefore derive identity from the resolved constructor signature rather than
   from a serialized reflection type name.
3. Kotlin Common is unchanged. This layer creates no Kotlin enum, annotation, or type projection;
   it only retains the foreign constructed identity beside the exactly decoded storage value.
4. ECMA-335 custom-attribute fixed-argument rules and constructed generic type signatures are
   profile-neutral. The selected `net48`, `netstandard2.0`, or `net10.0` reference graph still
   determines nominal identity; no profile is made to imitate another profile's available APIs.
5. The implementation consumes the general assembly-context-bearing signature resolver added for
   member validation. It converts the already resolved structure into the existing semantic enum
   identity algebra and does not invent a display-name key or perform decoder-local generic
   substitution.
6. The core-team choice is to accept only a complete, arity-correct constructed signature, prove
   its exact selected `System.Enum` ancestry and `value__` storage, and decode that width. Invalid
   generic arity is malformed metadata with a located structured failure; unsupported non-enum
   shapes are not repaired by erasure or an open-type fallback.

Real Roslyn .NET 10 metadata covers the same `Generic<int>.NestedKind` both as a tagged `object`
argument and as a strongly typed constructor argument. Both resolve to the same complete
constructed identity and exact `Int16` bits. A doctored constructor signature with the type
argument removed is rejected during constructor-signature resolution, before blob decoding, and
retains expected versus actual generic arity. Primitive generic arguments are resolved to the
selected graph's exact `System.Int32` TypeDef, so the signature and serialized-name paths agree on
semantic identity without depending on the compiler host runtime.

The twentieth slice admits a custom-attribute constructor whose MemberRef owner is a closed
generic TypeSpec.

1. JVM and KLIB-backed importers retain the annotation class identity together with the
   substituted constructor and parameter types. Erasure may be part of a target ABI, but an
   importer does not discard the logical generic owner before annotation values are interpreted.
2. The CLR-specific representation is reified rather than erased. A generic attribute application
   points to a constructor MemberRef whose owner is a `GenericInstance` TypeSpec, while the
   MemberRef signature may use owner `VAR` parameters. The owner view and every substituted
   parameter therefore need assembly-context-bearing resolved types.
3. Kotlin Common is unchanged. A foreign generic attribute application must be fully closed; it
   does not make Kotlin annotations generic, change Kotlin type parameters, or collapse
   `[A<Int>]` and `[A<String>]` into one occurrence. Encoded attachment, order, and multiplicity
   remain authoritative.
4. The physical reader/resolver remains profile-neutral and preserves such metadata from any
   selected assembly. The
   [C# 11 generic-attributes design](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-11.0/generic-attributes)
   states that .NET Core added the required runtime support. Therefore Kotlin-facing generic-
   attribute projection and future emission are supported only for a profile which proves that
   capability, currently `net10.0`; neither `net48` nor the `netstandard2.0` portability floor
   inherits that promise merely because modern Roslyn can describe the metadata. Ordinary non-
   generic attributes remain uniform.
5. The implementation follows the same resolved declaration/view pattern already used for generic
   base members. `DotNetClrResolvedCustomAttributeConstructor` now carries a closed
   `DotNetClrResolvedTypeView` plus an already resolved and owner-substituted method signature.
   Value decoding and named-member validation consume that authoritative result; neither
   reconstructs a TypeSpec or substitutes raw assembly-relative handles.
6. The core-team choice is to reject an open, partially open, non-generic-instance, value-type, or
   wrong-arity owner before blob decoding. Unresolved constructor types, out-of-range owner
   parameters, and residual method type parameters are distinct structured failures. There is no
   fallback to the open TypeDef and no display-name identity.

Real Roslyn .NET 10 metadata covers `GenericProbeAttribute<T>(T)` applied once as `<int>` and once
as `<ExternalKind>`. Both constructor fixed arguments and the public named field `T Value` are
substituted through the same closed view; the enum path retains its exact `Int16` storage.
Doctored metadata covers missing owner arity, an open owner argument, a non-nominal array owner, an
out-of-range owner parameter, a residual method parameter, and an unresolved constructor type.

Satisfaction of CLR generic constraints is deliberately not implemented as attribute-local logic.
The twenty-first slice below resolves and substitutes their complete physical contract. A later
selected-graph satisfaction/assignability validator remains shared by foreign base types,
interfaces, member owners, and generic attributes. It must be completed before a generic foreign
attribute is projected as a stable Kotlin annotation; until then these slices prove and preserve
closed identity, signatures, and constraints, not all runtime-instantiation legality.

The twenty-first slice resolves every constraint of one constructed type view.

1. JVM import starts from a Java type parameter plus its class/interface bounds, while KLIB-backed
   targets retain Kotlin type parameters and upper bounds by declaration identity. Substitution
   happens in the type checker; neither importer turns a bound into an unrelated display-name key.
   The .NET importer follows that declaration-plus-substituted-bound structure.
2. CLR metadata differs physically. Special constraints are GenericParam flags, while each
   GenericParamConstraint target is a TypeDef, TypeRef, or TypeSpec. A direct token carries no
   class/value discriminator; a TypeSpec carries a complete structural signature and may refer to
   owner `VAR` parameters. The importer must not invent the missing discriminator or discard the
   TypeSpec.
3. Kotlin Common is unchanged. Reference/value/default-constructor and `AllowByRefLike` flags
   remain foreign CLR constraints; they do not become Kotlin nullability, `Any`, or a Kotlin
   constructor bound. Resolution creates no FIR type parameter and does not yet claim that an
   argument satisfies a bound.
4. Row resolution and owner substitution are legal and identical on `net48`,
   `netstandard2.0`, and `net10.0` selected graphs. The modern `AllowByRefLike` bit is retained
   losslessly but its satisfaction is profile/runtime policy. An older profile is not taught the
   modern rule merely because the physical reader recognizes bit `0x20`.
5. The implementation mirrors mature importer layering: a general
   `DotNetClrConstructedTypeConstraintResolver` consumes a closed resolved type view, validates
   parameter numbering and arity, resolves direct nominal targets, resolves TypeSpec targets
   through the common signature resolver, and substitutes owner arguments through the common
   substitution algebra. The custom-attribute constructor merely stores that result.
6. The core-team choice is to keep direct nominal and structural TypeSpec constraints as distinct
   resolved variants. Constraint resolution failures prevent downstream value decoding, but
   satisfaction is a separate assignability operation. Folding both stages together would either
   duplicate a partial CLR type checker in the attribute decoder or prematurely map foreign
   constraints to Kotlin semantics.

The dual-ILAsm fixture resolves the same `SignatureHost<T>` constraints under Framework and modern
assemblers. Real Roslyn .NET 10 metadata resolves `where T : struct, IProbeConstraint<T>` for
`ProbeConstraintValue`: `System.ValueType` remains a direct nominal constraint, while
`IProbeConstraint<T>` becomes the exact constructed
`IProbeConstraint<ProbeConstraintValue>` TypeSpec. Hostile metadata covers bad TypeSpec arity,
owner substitution outside the argument range, an unresolved nominal token, and invalid parameter
numbering. The [official GenericParameterAttributes contract](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.genericparameterattributes?view=net-10.0)
is the source of truth for the retained special flags.

The twenty-second slice retains InterfaceImpl rows and resolves immediate hierarchy edges as exact
constructed views.

1. JVM import retains generic superclass and interface signatures, while KLIB-backed targets
   retain Kotlin supertypes by declaration identity. Their type systems substitute the current
   owner's arguments through those edges before subtype checking. They do not infer implemented
   interfaces from member names. The .NET importer follows that same declared-edge model.
2. CLR metadata differs physically: the single superclass edge is TypeDef.Extends, while every
   directly implemented or inherited interface is a separate InterfaceImpl row. Its interface
   column is a TypeDefOrRef coded token and may therefore be a TypeSpec containing the complete
   reified generic instantiation. InterfaceImpl itself has a metadata token and may own custom
   attributes. Erasing the row to its target TypeDef would lose both the exact interface view and
   its attachment identity. The
   [official System.Reflection.Metadata contract](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.metadata.interfaceimplementation?view=net-10.0)
   confirms the TypeDefinition, TypeReference, or TypeSpecification target forms.
3. Kotlin Common is unchanged. These are foreign physical supertype edges, not Kotlin synthetic
   supertypes, override decisions, or a new subtyping rule. A later FIR policy may project a valid
   edge; malformed CLR hierarchy metadata never becomes a Kotlin declaration.
4. InterfaceImpl, TypeDef.Extends, TypeSpec, and owner substitution have the same metadata meaning
   on `net48`, `netstandard2.0`, and `net10.0`. DIM availability can change where an interface
   default body lives, but it does not change which nominal interface instantiation a type
   implements. Profile-specific API selection remains the build frontend's responsibility.
5. The implementation follows the existing importer layering. The PE reader retains an immutable
   `DotNetClrInterfaceImplementation` row. `DotNetClrTypeViewResolver` resolves a TypeDef/TypeRef
   or structural TypeSpec in its owning assembly and substitutes the declaring view's arguments.
   `DotNetClrTypeHierarchyViewResolver` then resolves the immediate base and ordered interfaces,
   with structured failures for arity, resolution, non-nominal TypeSpecs, illegal method
   parameters, and class/interface shape. Neither layer performs FIR projection or transitive
   assignability.
6. The core-team choice is to preserve the exact row and exact constructed view, and to reject
   malformed hierarchy shape before subtype reasoning. A display-name graph or reuse of the
   module-local IL emitter's `DotNetIlClassInfo` would mix imported selected-assembly identity with
   current-module code-generation state. Variance-aware transitive assignability remains the next
   shared operation on this resolved graph.

The dual-ILAsm fixture proves profile-neutral retention and resolution of
`IntArrayBox : IBox<int[]>` and owner substitution for
`GenericBox<T> : GenericBase<T[]>, IBox<T[]>`. Real Roslyn .NET 10 metadata proves that
`ProbeConstraintValue` physically implements
`IProbeConstraint<ProbeConstraintValue>`, matching the separately resolved constraint. Hostile
selected metadata covers owner and interface arity, out-of-range owner substitution, illegal
method parameters, non-nominal TypeSpecs, a class in an InterfaceImpl row, an interface as a class
base, and an interface with a TypeDef.Extends edge.

The twenty-third slice walks those views for exact nominal assignability.

1. Kotlin's common type checker and the JVM/KLIB importers establish subtyping by following
   declared, substituted supertypes. A repeated interface in a diamond is one logical reachable
   supertype; it is not a cycle or a second identity. Missing/error supertypes remain diagnostic
   information rather than silently proving `false`.
2. CLR nominal assignability likewise includes exact type identity, the transitive class base
   chain, and implemented-interface reachability. Unlike erased JVM signatures, constructed CLR
   views remain reified and invariant unless the generic interface or delegate declaration
   authorizes CLR variance. CLR additionally has array conversions, boxing, generic-parameter
   constraints, and `Nullable<T>` rules. The
   [official Type.IsAssignableFrom contract](https://learn.microsoft.com/en-us/dotnet/api/system.type.isassignablefrom?view=net-10.0)
   describes the complete runtime relation; this slice implements only its exact nominal
   class/interface subset.
3. Kotlin Common is unchanged. This operation answers whether one foreign physical view reaches
   another; it does not make a new Kotlin subtype, reinterpret Kotlin declaration-site variance,
   or permit a foreign conversion in Kotlin source. FIR import policy remains authoritative.
4. Exact class/interface reachability has the same meaning on `net48`, `netstandard2.0`, and
   `net10.0`. DIM availability is irrelevant. The compiler walks the build frontend's selected
   metadata graph and never calls host reflection, so the host runtime cannot substitute its own
   profile.
5. `DotNetClrTypeAssignabilityResolver` performs a bounded breadth-first walk over resolved base
   and InterfaceImpl views. Equality includes selected assembly identity and all reified
   arguments. Diamonds are deduplicated, cycles are detected on the completed adjacency graph,
   and unresolved hierarchy plus resource limits remain structured results. An exact reachable
   path is a positive proof; if none exists, an encountered malformed edge or cycle is reported
   instead of being weakened to `NotAssignable`. Whole-import graph validation remains a separate
   diagnostic pass and may still reject an unrelated malformed branch.
6. The core-team choice is to land exact nominal reachability before variance or conversion
   policy. Treating every differing generic argument as invariant is conservative: it can defer a
   legal CLR variant conversion but cannot invent one. Variance requires selected-graph
   reference/value/ref-like classification; arrays, boxing, `Nullable<T>`, and type parameters
   require their own explicit rules. None belongs as an optimistic fallback in this walker.

The dual-ILAsm fixture proves transitive generic-base and interface substitution, exact generic
invariance, and a non-cyclic interface diamond on Framework and modern metadata. Synthetic
selected graphs prove unresolved edges, a real inheritance cycle, and the resolution bound. Real
Roslyn .NET 10 metadata proves that `ProbeConstraintValue` reaches both
`System.ValueType` and `IProbeConstraint<ProbeConstraintValue>`.

The twenty-fourth slice applies exact nominal assignability to individual resolved
GenericParamConstraint rows.

1. Kotlin's common type checker validates each substituted upper bound separately. JVM foreign
   bounds and KLIB Kotlin bounds use the same subtype machinery; an unsupported/error type is not
   silently reported as an ordinary bound violation. The .NET importer likewise keeps
   constraint-shape resolution, nominal subtype proof, and later source diagnostics separate.
2. CLR constraint legality is broader than nominal reachability. A GenericParamConstraint may
   name a class, interface, constructed type, or another parameter, while GenericParam flags add
   reference-type, non-nullable-value-type, and default-constructor rules. Modern metadata also
   has by-ref-like eligibility, and valid type arguments include primitives and arrays whose CLR
   identities/conversions are not nominal TypeDef views in the current resolved-signature model.
3. Kotlin Common is unchanged. A foreign CLR constraint does not become a Kotlin upper bound
   merely because this physical validator can prove it, and a CLR constraint violation cannot be
   weakened into a Kotlin warning. FIR projection remains a later policy operation.
4. Exact nominal bound reachability is profile-neutral. Special constraints and by-ref-like
   eligibility require selected-profile capability and physical type classification; in
   particular, recognizing a modern flag does not make its rule available on `net48` or the
   `netstandard2.0` floor.
5. `DotNetClrNominalConstraintValidator` consumes the already resolved constructed-type contract
   and the shared assignability resolver. It returns one result per physical constraint row:
   satisfied, violated, unsupported non-nominal argument/constraint, or invalid assignability.
   The result retains the original parameter binding and therefore its special flags, but exposes
   no aggregate “all constraints satisfied” bit.
6. The core-team choice is to land this deliberately named partial validator rather than mix
   primitive mapping, dependent-parameter reasoning, constructors, nullability, and by-ref-like
   rules into one optimistic boolean. Proven violations are useful evidence; unsupported and
   invalid are not violations, and all nominal rows being satisfied is not complete CLR generic
   constraint satisfaction.

Real Roslyn .NET 10 coverage proves both nominal rows of
`ConstrainedProbeAttribute<ProbeConstraintValue>` and proves that an ordinary class violates them.
Synthetic contracts cover a primitive argument, a non-nominal constraint, and an assignability
resolution limit. The retained parameter still independently proves the physical `struct` and
`new()` flags; this slice makes no claim about satisfying those flags.

The twenty-fifth slice classifies resolved signatures as physical reference, non-nullable value,
or nullable value types.

1. Kotlin's common type system and the JVM/JS/Native/Wasm backends ask their target type-system
   context for primitive, class, value/inline, array, and nullable categories. They do not infer
   storage kind from source spelling after lowering. The CLR importer needs the same centralized
   category boundary before special constraints, boxing, variance, or FIR projection can use it.
2. CLR signatures physically encode `class` versus `valuetype`, but that bit must agree with the
   selected TypeDef hierarchy. Ordinary value types derive from `System.ValueType`; enum
   definitions derive from `System.Enum`, while `System.ValueType` and `System.Enum` themselves
   are abstract reference classes. `System.Nullable<T>` is a distinct value-type construction,
   primitives carry intrinsic categories, and CLR arrays are reference types. The
   [official ValueType contract](https://learn.microsoft.com/en-us/dotnet/api/system.valuetype)
   and [Nullable contract](https://learn.microsoft.com/en-us/dotnet/fundamentals/runtime-libraries/system-nullable%7Bt%7D)
   are the semantic references.
3. Kotlin Common is unchanged. Physical `NULLABLE_VALUE` describes CLR `System.Nullable<T>`; it
   does not make an imported type Kotlin-nullable or collapse Kotlin's nullable reference
   semantics. Likewise a CLR primitive category does not select a Kotlin built-in mapping.
4. Classification uses the exact core types selected for the target graph. The algorithm is the
   same on `net48`, `netstandard2.0`, and `net10.0`, but their core TypeDefs and available
   constructions come from their own reference assemblies. It never asks the host JVM or a host
   .NET runtime.
5. `DotNetClrPhysicalTypeClassifier` consumes a complete resolved signature and an explicit
   selected-core catalog containing `System.ValueType`, `System.Enum`, and
   `System.Nullable<T>`. It validates generic arity and the encoded class/value bit against the
   shared hierarchy relation, special-cases the two reference roots, classifies primitives and
   arrays directly, and returns structured unsupported or invalid-hierarchy outcomes for forms
   that cannot yet be legal generic arguments.
6. The core-team choice is to verify both signature evidence and selected hierarchy instead of
   trusting either in isolation. By-ref-like status is deliberately not inferred from value-type
   ancestry: it is an orthogonal semantic `IsByRefLikeAttribute` marker whose exact identity,
   payload, and multiplicity must be decoded through the ordinary attribute layer before profile
   policy can use it.

Framework 4.8 coverage classifies a class, enum, and `Nullable<int>` against the selected
`mscorlib` definitions. Roslyn .NET 10 coverage adds primitives, arrays, an interface
construction, an ordinary struct, `System.ValueType`/`System.Enum` themselves, and hostile false
class/value bits, open generic arity, generic parameters, and a bounded hierarchy.

The twenty-sixth slice adds the orthogonal CLR by-ref-like dimension.

1. Mature Kotlin importers preserve semantically resolved annotations/attributes separately from
   nominal type identity. Target-specific compiler markers are recognized by their selected
   declaration identity and contract, not by a short name. The .NET importer follows that same
   semantic-attribute boundary.
2. CLR by-ref-like structs are ordinary non-nullable value types plus the compiler/runtime marker
   `System.Runtime.CompilerServices.IsByRefLikeAttribute`. They cannot be placed on the managed
   heap. Modern generic parameters additionally require the `AllowByRefLike` flag before such a
   type argument is legal. The [official Type.IsByRefLike contract](https://learn.microsoft.com/en-us/dotnet/api/system.type.isbyreflike?view=net-10.0),
   [marker contract](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.compilerservices.isbyreflikeattribute),
   and [.NET ECMA augment](https://github.com/dotnet/runtime/blob/main/docs/design/specs/Ecma-335-Augments.md#byreflike-types-in-generics)
   are authoritative.
3. Kotlin Common is unchanged. A foreign by-ref-like value does not become a Kotlin value class,
   ordinary generic argument, capturable local, boxable `Any`, or heap-storable value. FIR policy
   and backend legality must explicitly model or reject every such use.
4. The marker type is supplied by the selected profile. If that identity is unavailable,
   non-nullable nominal values classify as `MARKER_UNAVAILABLE`, not as ordinary structs.
   Recognition alone does not enable use: `net10.0` must still enforce `AllowByRefLike` and ref
   safety, while portable profiles cannot inherit modern generic support.
5. `DotNetClrByRefLikeClassifier` composes the physical kind classifier with the existing ordinary
   custom-attribute constructor/value decoder. It requires exact selected marker identity, a
   decoded empty marker payload, single multiplicity, and a non-nullable-value target. Invalid
   constructors, payloads, duplicates, and target kinds remain structured results.
6. The core-team choice is to reuse ordinary semantic attribute comparison rather than introduce
   a marker-name shortcut or raw-blob special case. A foreign attribute with the same short name
   is unrelated. Profile absence remains explicit, and by-ref-like classification stays separate
   from the later decision whether a particular Kotlin construct can safely use it.

Real Roslyn .NET 10 coverage distinguishes a `ref struct`, an ordinary struct, a class, a
primitive, and a struct carrying a same-short-name foreign marker. Synthetic selected metadata
covers a missing marker catalog, duplicate exact markers, a truncated marker value, and an exact
marker attached to a reference class.

The twenty-seventh slice applies physical classification to the first special generic-constraint
rules.

1. JVM, JS, Native, and Wasm all keep declaration-level generic bounds separate from the
   target-specific representation checks applied to an actual type argument. JVM is the closest
   foreign-import precedent: Java bounds and classfile constraint evidence are imported into the
   compiler type model rather than re-decided by bytecode emission. None of those targets has a
   CLR-style by-ref-like anti-constraint.
2. The CLR separately encodes `ReferenceTypeConstraint`,
   `NotNullableValueTypeConstraint`, and `AllowByRefLike`. The first two constrain the physical
   argument category. The last is an anti-constraint: it permits a by-ref-like argument but does
   not require one. The selected runtime must also support by-ref-like generic instantiation.
3. Kotlin Common is unchanged. CLR `class` is not Kotlin `T : Any`, CLR `struct` is not a Kotlin
   value-class bound, and `AllowByRefLike` does not make ordinary Kotlin generic code ref-safe.
   These results are foreign constructed-type validation only.
4. `net10.0` admits a by-ref-like argument only when the parameter has `AllowByRefLike`.
   `net48` and `netstandard2.0` reject such an instantiation even if hostile or mismatched metadata
   sets the flag. An ordinary non-by-ref-like argument remains legal with or without that
   anti-constraint. A missing selected marker identity remains unsupported rather than silently
   treating every value type as ordinary.
5. `DotNetClrSpecialConstraintValidator` consumes the same resolved parameter bindings as nominal
   constraint validation and composes them with the shared by-ref-like classifier. It emits one
   structured result per applicable reference/value rule plus the implicit by-ref-like eligibility
   rule. It preserves invalid classification and marker-unavailable outcomes and exposes no
   aggregate success boolean.
6. The core-team choice is a separate policy validator over the common resolved-constraint model,
   not attribute-decoder logic, a C# syntax check, or a codegen heuristic. At this slice the CLR
   default-constructor constraint remained separate because public parameterless constructors,
   abstract reference types, and implicit value-type construction required their own exact
   member/instantiation rules; the following slice closes that boundary.

Real Roslyn .NET 10 definitions cover `class`, `struct`, unconstrained, and
`allows ref struct` parameters. Constructed-view tests cover references, ordinary values,
`Nullable<int>`, a real ref struct, both portable targets, absent marker identity, and an invalid
class/value signature. They also prove the orthogonality of physical value-type satisfaction and
by-ref-like eligibility.

The twenty-eighth slice adds the CLR default-constructor special constraint.

1. Kotlin Common and the JVM/JS/Native/Wasm backends have no common generic “has a constructor”
   bound. Where a target imports constructor-bearing foreign declarations, constructors remain
   target callables and generic-bound satisfaction uses the target type/member model. The .NET
   target follows that separation instead of inventing Kotlin syntax for CLR `.ctor`.
2. ECMA-335 defines the `.ctor` special constraint as either a value type or a concrete reference
   type with a public constructor taking no arguments. Constructors are not inherited. The
   [CLI generic-parameter rule](https://www.ecma-international.org/publications-and-standards/standards/ecma-335/)
   is the ABI authority; the
   [C# `new()` contract](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/new-constraint)
   provides the source-language surface but does not redefine the metadata rule.
3. Kotlin Common remains unchanged. Satisfying this foreign constraint does not add a Kotlin
   constructor bound, make `T()` legal in common code, or change Kotlin constructor visibility.
4. The CLI rule is uniform on `net48`, `netstandard2.0`, and `net10.0`. Every physical value type,
   including `Nullable<T>` under the standalone `.ctor` flag, satisfies it. A reference argument
   must be non-abstract and own an exact public parameterless CLR instance constructor. Arrays,
   strings, non-public/parameterized constructors, and inherited constructors do not satisfy it.
   By-ref-like eligibility remains an independent profile-aware rule.
5. `DotNetClrSpecialConstraintValidator` resolves primitive `System.Object`/`System.String`
   through an explicit selected-core catalog and inspects the selected nominal TypeDef's own
   MethodDefs. A constructor requires exact `.ctor`, public instance visibility,
   `SpecialName`/`RTSpecialName`, default instance calling convention, zero generic/value
   parameters, and `void` return shape. Invalid physical/by-ref-like classification remains
   structured rather than being collapsed into a constructor failure.
6. The core-team choice is to apply the CLI rule to selected metadata, not look for Kotlin
   constructors, C# source syntax, inherited members, or a method named `.ctor` alone. The result
   remains per-rule and non-aggregate because nominal constraints and dependent generic-parameter
   arguments still have their own validation paths.

Real Roslyn .NET 10 definitions cover implicit public, parameterized, private, abstract-public,
and derived-without-own-default constructors. Tests cover `System.Object`, `System.String`, arrays,
ordinary/nullable/by-ref-like values, and all three target profiles. Synthetic selected metadata
removes `RTSpecialName` from an otherwise matching `.ctor` to prove that name and signature alone
are insufficient.

The twenty-ninth slice gives compact CLR primitive signatures their selected nominal views.

1. JVM, JS, Native, and Wasm begin with compiler built-in identities and map them to target
   primitive/boxed representations where target operations require it. JVM generic bounds in
   particular use the boxed class view of a primitive argument. They do not discover those
   identities through display names or the compiler host runtime.
2. CLR signatures encode Boolean, numeric, native-integer, String, and Object types as compact
   element codes rather than TypeDefOrRef tokens. Generic type constraints are nevertheless
   checked against their boxed selected `System.*` TypeDefs and interface/base hierarchy. The
   defining assembly differs by selected graph (`mscorlib`, reference facades, or
   `System.Runtime`/CoreLib).
3. Kotlin Common is unchanged. A physical `ELEMENT_TYPE_I4` to selected `System.Int32` mapping is
   not a new Kotlin `Int` identity, nullability rule, boxing promise, or stdlib declaration.
4. The primitive set and its CLR metadata names are profile-uniform, while assembly identity and
   hierarchy come from the selected target graph. Framework coverage resolves every primitive
   from `mscorlib`; .NET 10 coverage resolves every primitive from its selected
   `System.Runtime` reference graph. `netstandard2.0` must likewise begin from its selected facade
   and binder rather than borrowing either runtime's TypeDef.
5. `DotNetClrPrimitiveTypeCatalogResolver` resolves every compact primitive through
   `DotNetClrTypeResolver`, fails structurally on the first unresolved definition, and returns an
   immutable complete catalog. Nominal constraint validation maps a primitive argument to that
   selected zero-argument TypeView before using the existing bounded hierarchy walker. The
   default-constructor validator reuses the same catalog for primitive reference types, removing
   its former object/string-only core catalog.
6. The core-team choice is one selected-core primitive catalog shared by importer policy layers,
   following the other backends' built-in-to-physical mapping pattern. Host reflection,
   hard-coded host assembly identities, decoder-local mappings, and permanent primitive
   “unsupported” results are rejected.

Real selected-reference tests resolve the complete primitive set from Framework 4.8 `mscorlib`
and .NET 10 `System.Runtime`, plus a missing-catalog type failure. A Roslyn generic
`IComparable<T>` constraint proves that compact `Int32` and `String` arguments satisfy their exact
boxed interface views while `Object` does not. The earlier mixed contract now correctly records
`Int32 -> System.ValueType` as satisfied and its unrelated project interface as violated.

The thirtieth slice adds a truthful aggregate constraint status without claiming unsupported CLR
conversions.

1. Kotlin's mature backends ask their target type checker for bound satisfaction and preserve
   diagnostics/unsupported states until the relevant target conversion is modeled. They do not
   turn a partial exact-symbol walk into a universal validity Boolean.
2. CLR variance is declared on interface/delegate GenericParam rows and can make two constructed
   views assignable even when their arguments differ. Arrays and open generic parameters add
   further non-nominal rules. An exact hierarchy miss is therefore not always proof of a
   constraint violation.
3. Kotlin Common remains unchanged. CLR covariance/contravariance and an importer validation
   status do not alter Kotlin declaration-site variance, subtyping, or common generic bounds.
4. The rule is metadata/profile-neutral: every selected profile supplies the variance rows and
   hierarchy being checked. Profile selection can change available types, not the meaning or
   precedence of `Invalid`, `Unsupported`, `Violated`, and `Satisfied`.
5. The exact hierarchy walker now records `VariantConversionRequired` only if it actually reaches
   the same variant definition with different arguments; an unrelated type remains
   `NotAssignable`. Dependent generic parameters receive explicit unsupported results in nominal
   and special validation. `DotNetClrConstructedTypeConstraintValidator` combines all retained
   per-row results with the precedence invalid metadata > unsupported semantics > proven
   violation > supported satisfaction. Its result retains both complete sub-validations and issue
   coordinates.
6. The core-team choice is a sealed status over preserved evidence, not a Boolean and not an
   eager partial implementation of CLR variance. At this slice only a wholly supported
   `Satisfied` shape could proceed, while variant, array, and dependent cases remained actionable
   implementation work rather than false diagnostics. The following slice implements the bounded
   generic-interface variance subset.

Roslyn coverage uses a real covariant interface where the exact graph reaches
`IVariantConstraint<String>` for an `IVariantConstraint<Object>` constraint and records
unsupported variance. `Object` against `IComparable<Object>` remains a proven violation because
its hierarchy contains no matching interface definition. Aggregate tests cover full success,
simultaneous nominal/special violations, two preserved array issues, dependent parameters, the
variant candidate, and an invalid class/value signature.

The thirty-first slice evaluates CLR generic-interface variance.

1. Kotlin/JVM, JS, Native, and Wasm perform variance through their target type-checking contexts,
   preserving Kotlin declaration-site/use-site rules while mapping foreign target variance at the
   importer boundary. The .NET resolver similarly extends assignability rather than teaching
   custom-attribute decoding or IL emission about variance.
2. CLR GenericParam rows permit covariance and contravariance on interfaces and delegates.
   Variant conversion applies only to reference-type arguments: covariance checks actual argument
   to expected argument, contravariance reverses that direction, and invariant parameters require
   identity. Boxing a value argument does not make variance legal.
3. Kotlin Common remains unchanged. CLR declaration variance on a foreign interface does not
   rewrite Kotlin-owned variance or make a Kotlin generic conversion valid.
4. The interface variance rule is identical on `net48`, `netstandard2.0`, and `net10.0`; only the
   selected definitions and available hierarchies differ. Arrays and delegate variance retain
   explicit unsupported boundaries because their physical conversion rules are not supplied by
   the nominal interface graph.
5. `DotNetClrSignatureTypeAssignabilityResolver` composes the bounded exact walker, physical type
   classifier, and primitive catalog. It retains every reachable same-definition interface
   candidate under the existing bound, validates contiguous GenericParam variance metadata,
   requires reference categories, reverses contravariant argument checks, and recursively applies
   nominal/primitive/interface assignability with its own active-pair and resolution bounds. One
   successful candidate proves the conversion; invalid/unsupported evidence otherwise remains
   structured.
6. The core-team choice is a dedicated assignability layer shared by every consumer, not
   C#-specific import sugar or constraint-only logic. Unsupported arrays, open parameters, and
   delegates keep returning `VariantConversionRequired`; they are not guessed from C# conversions
   or made invariant. Value arguments with different identities are proven not assignable.

Real Roslyn definitions cover covariance, multiple same-definition candidates, contravariance
through `IComparable<in T>`, invariance, nested covariance, a value argument, and an array
argument. The supported reference-only
variant conversions now produce aggregate `Satisfied`; invariant/value cases produce `Violated`;
the array-dependent conversion remains `Unsupported`.

The thirty-second slice evaluates physical CLR array-to-array assignability.

1. Kotlin Common models `Array<T>` invariantly and keeps specialized primitive arrays nominally
   separate. JVM imports Java reference arrays as flexible `Array<T>..Array<out T>?` types and its
   backend recognizes the VM's physical array casts; JS, Native, and Wasm retain their target array
   carriers behind Kotlin's logical array types. No target changes Kotlin declaration subtyping
   merely because its runtime carrier admits a wider assignment.
2. CLR signature compatibility has a platform-specific array relation. Vectors are compatible
   with vectors and general arrays with general arrays of the same rank when their elements are
   array-element-compatible. Reference elements recurse through ordinary CLR assignability.
   Value elements do not gain reference covariance, but signed/unsigned integer pairs and enums
   with the same reduced storage type are physically compatible without representation change.
   `bool`/`byte` and `char`/`ushort` are not reduced pairs.
3. Kotlin Common is unchanged. In particular, `Array<String>` does not become a Kotlin subtype of
   `Array<Any>`, and `Array<Int>` does not become a Kotlin subtype of `Array<UInt>`. The relation is
   consulted only while validating or projecting foreign CLR signatures.
4. The array rule is VES-level and therefore profile-uniform across `net48`,
   `netstandard2.0`, and `net10.0`. Profile selection can change the nominal types reachable from
   element signatures, but not vector/rank or reduced-storage compatibility. Runtime store checks
   remain a foreign interop hazard and do not redefine Kotlin mutation semantics.
5. The former variance-only layer is generalized as
   `DotNetClrSignatureTypeAssignabilityResolver`. It composes exact nominal traversal, interface
   variance, physical type classification, primitive selection, array shape, and validated enum
   storage under one recursive bound. It distinguishes unsupported signature forms from invalid
   metadata. Its signature entry point does not insert boxing; generic-constraint validation uses
   the distinct nominal-view entry point where selected type definitions, rather than stack
   locations, are being compared. No generic-constraint consumer contains an independent array
   rule. Unsupported nested signature conversion is retained separately from unsupported top-level
   arguments or constraint rows, so diagnostics do not assign a failure to the wrong side.
6. The core-team choice is to implement the complete unambiguous array-to-array subset now and
   retain array-to-`System.Array`, vector-to-generic-interface, open-parameter, and custom-modified
   conversions as structured unsupported boundaries. Guessing those relations from C# syntax
   would omit CLR-only signatures and would make one foreign language the importer authority.

Real Roslyn metadata covers reference vector covariance, rectangular and jagged arrays, rank
mismatch, value-to-reference rejection, signed/unsigned reduced storage, enum underlying storage,
the non-pairs `char`/`ushort`, and the explicit array-to-`System.Array` boundary. Supported
conversions now contribute `Satisfied`, proven mismatches contribute `Violated`, and deferred
array-to-nominal conversions remain `Unsupported`.

The thirty-third slice resolves the nominal CLR array base hierarchy.

1. Kotlin/JVM keeps Java array flexibility in the Java importer and lets the JVM carrier remain an
   object with the VM-defined array bases; Native, JS, and Wasm likewise keep carrier inheritance
   outside Kotlin Common's `Array<T>` declaration. The .NET importer follows that split instead of
   adding `System.Array` as a Kotlin Common supertype.
2. Every CLR vector and general array is a reference type derived from the selected
   `System.Array`. Its ordinary base/interface graph supplies `System.Object` and the non-generic
   `System.Collections` interfaces. Zero-based vectors additionally have VES/BCL-supplied generic
   interfaces; multidimensional arrays do not.
3. Kotlin Common remains unchanged. Kotlin `Array<T>` and specialized primitive-array wrappers do
   not acquire foreign supertypes. This relation is only for imported physical signatures and
   generic-constraint validation.
4. `System.Array` is a CTS basis on `net48`, `netstandard2.0`, and `net10.0`. The defining assembly
   identity differs by the selected reference graph, so the importer receives the resolved
   definition rather than matching the string `System.Array`. Generic vector interfaces remain a
   separate profile-capability question.
5. `DotNetClrSignatureTypeAssignabilityResolver` now walks from any physical array through the
   injected selected `System.Array` view. A successful ordinary hierarchy result is assignable.
   If that graph does not match, a general array or a non-interface/non-unary target is proven not
   assignable. Only a vector facing a unary generic interface retains
   `VECTOR_TO_GENERIC_INTERFACE`; unsupported aggregate status preserves only the unsupported issue,
   while the complete nominal validation still retains simultaneous proven violations.
6. The core-team choice is selected-identity composition, not namespace/name recognition and not a
   synthetic Kotlin supertype. The generic vector-interface catalog is deliberately the next
   layer because its exact surface is supplied by the VES/BCL rather than by `System.Array`'s
   ordinary metadata hierarchy.

Roslyn metadata now proves vector and rectangular-array conversion to `System.Array`,
`System.Object`, and non-generic `IEnumerable`, rejection of a multidimensional array as generic
`IList<object>`, and preservation of the vector/generic-interface boundary. Mixed aggregate
coverage proves that a supported violation is retained in the complete validation even when an
unsupported row determines the sealed aggregate status.

The thirty-fourth slice resolves the selected CLR generic vector-interface surface.

1. Kotlin/JVM projects Java arrays through the Java type system and the JVM's physical array
   interfaces without adding those interfaces to Kotlin Common `Array`. Native, JS, and Wasm
   likewise keep target carriers and adapters below logical Kotlin array identity. The .NET
   importer therefore extends only foreign CLR signature assignability.
2. The VES/BCL gives zero-based rank-one vectors the generic interfaces `IList<T>`,
   `ICollection<T>`, `IEnumerable<T>`, `IReadOnlyList<T>`, and `IReadOnlyCollection<T>`.
   Multidimensional arrays do not receive them. Compatibility uses the CLR
   array-element-compatible relation, not the declared variance of the generic interface; this is
   why `int[]` can satisfy an `IList<uint>` or `IReadOnlyCollection<uint>` location even though
   value arguments cannot participate in ordinary generic variance.
3. Kotlin Common is unchanged. No Kotlin `Array<String>` subtype of a Kotlin collection is
   invented, no specialized primitive-array wrapper becomes a CLR vector, and reduced signedness
   never becomes a Kotlin subtype rule.
4. .NET Framework 4.8 and .NET 10 runtime probes confirm the five-interface surface, reference
   element covariance, reduced `int`/`uint` compatibility, and rejection for rectangular arrays.
   .NET Standard 2.0 contains the same five contract identities; a portable binary relies on the
   consuming CLR implementation, as with its other VES behavior. The owning TypeDefs are resolved
   separately through each selected profile graph.
5. `DotNetClrArrayRuntimeTypesResolver` resolves and validates `System.Array` plus all five unary
   interface TypeDefs. The resulting immutable catalog is complete or returns structured
   unresolved/invalid evidence. Signature assignability compares the expected definition with
   those identities and reuses the same recursive array-element compatibility used for
   array-to-array conversion. An unrelated unary generic interface is now proven not assignable;
   no name is inspected in the checking path.
6. The core-team choice is a complete selected identity catalog, not a target-profile enum switch,
   a namespace/name predicate, or synthesis through `IList<T>`'s ordinary hierarchy. The last
   alternative would lose the CLR reduced-storage rule when reaching `IEnumerable<uint>` from an
   `int[]`.

Roslyn coverage proves `IList<object>`, `IEnumerable<object>`, `IReadOnlyList<object>`,
`IList<uint>` from both `int[]` and an `int`-backed enum array, rejection of
`char[]`/`IList<ushort>`, rejection for a rectangular array, and rejection of an unrelated unary
generic interface. The catalog itself is resolved from both the selected modern core graph and the
installed .NET Framework 4.8 `mscorlib`.

The thirty-fifth slice evaluates physical CLR delegate variance.

1. Kotlin/JVM keeps Kotlin function types under common Kotlin variance and imports Java functional
   interfaces through the Java enhancement/type-checking boundary. Native similarly represents
   Objective-C blocks as interop types and generated adapters, while JS and Wasm retain their own
   callable carriers. None changes Kotlin `FunctionN` subtyping to imitate a foreign callable
   representation.
2. The CLR uniquely represents delegates as sealed nominal TypeDefs directly derived from
   `System.MulticastDelegate`. A generic delegate can carry covariant and contravariant
   GenericParam rows, and compatible-instantiated delegate conversion uses the same
   reference-argument restriction and direction rules as compatible-instantiated interfaces.
3. Kotlin Common is unchanged. CLR delegate variance is consulted only for foreign physical
   signatures; it does not make `System.Func`/`System.Action` canonical Kotlin callable types or
   weaken Kotlin function-type identity and value-argument semantics.
4. The VES rule is identical on `net48`, `netstandard2.0`, and `net10.0`. Each compilation must
   nevertheless resolve `System.MulticastDelegate` through its selected profile graph. No profile
   recognizes delegates by C# type name, `Invoke` method spelling, or host reflection.
5. `DotNetClrDelegateRuntimeTypesResolver` validates the selected non-generic abstract
   `System.MulticastDelegate` definition. The shared signature-assignability layer then accepts
   variance metadata only on interfaces or sealed TypeDefs whose direct resolved base is that
   identity. It reuses the existing bounded recursive reference-only variance algorithm; a
   variant class or non-sealed delegate-shaped TypeDef is invalid metadata rather than an
   unsupported conversion.
6. The core-team choice is selected physical identity plus the common variance evaluator, not a
   delegate-name registry, special cases for `Func`/`Action`, or callable adapters in constraint
   validation. Kotlin callable export adapters remain a separate importer/exporter concern.

Real Roslyn metadata covers a custom `in`/`out` delegate, `Func`, `Action`, nested covariance,
unchanged value arguments, and changed value arguments. The selected delegate root is resolved
from the .NET 10, .NET Framework 4.8, and .NET Standard 2.0 facade graphs; malformed variance on a
generic class remains invalid instead of becoming delegate-compatible.

The thirty-sixth slice proves constraints for scope-qualified open generic arguments.

1. JVM imports Java type-parameter bounds into the foreign type model and lets the common Kotlin
   type checker compare substituted bounds. JS, Native, and Wasm likewise retain the logical
   declaration owner of an open type parameter; none treats a bare parameter number as a global
   type or rewrites every use to an arbitrary upper bound.
2. CLR signatures have two owner-relative parameter spaces: `!n` belongs to the declaring
   TypeDef and `!!n` belongs to the declaring MethodDef. GenericParamConstraint permits recursive
   TypeSpecs and naked parameters from those scopes. For generic-argument validity, the VES tests
   a parameter's boxed value against every nominal constraint and separately requires every
   special constraint. Consequently a declared bound is evidence at a constructed-use site, but
   the same numeric index from another owner is not.
3. Kotlin Common is unchanged. A Kotlin `T : Any` is not reinterpreted as CLR `class`, and a CLR
   `new()` or `allows ref struct` rule does not become an inferred Kotlin bound. The later FIR
   importer may expose only meanings for which it has an explicit Kotlin mapping; this physical
   layer merely proves whether an already selected CLR construction is valid for every future
   instantiation of its open argument.
4. Ordinary class/interface/value/default-constructor validity is the same on `net48`,
   `netstandard2.0`, and `net10.0`. The selected profile still owns all nominal identities.
   `AllowByRefLike` remains the deliberate modern exception: an open source parameter which may
   be ref-like can flow only to a target parameter which also permits it, and only on `net10.0`.
5. The resolver therefore constructs an explicit generic-parameter context from one resolved
   declaring type view and, optionally, one MethodDef. It validates owner, arity, numbering, and
   every `!n`/`!!n` reference before exposing bindings. A TypeDef's own `!n` bindings are visible
   only through its complete identity view (`Owner<!0, !1, ...>`); a substituted base/member view
   cannot relabel an outer parameter as one owned by that TypeDef. Nominal validation follows
   declared bounds transitively under a bounded cycle guard and keeps ordinary CLR assignability
   authoritative for concrete bounds. Special validation uses only proven implications: an own
   `class` flag or non-`Object` class bound proves reference shape, `struct` proves non-nullable
   value shape, `new()` or `struct` proves construction, and by-ref-like permission is checked
   contravariantly. Without the selected context, an open parameter remains explicitly
   unsupported.
6. The core-team choice is a scope-qualified evidence object passed to constraint validation, not
   a process-global `(kind, index)` map, eager upper-bound erasure, inferred public constraints,
   or a change to generic signature assignability. In particular, the global signature relation
   keeps the VES rule that an unboxed open parameter is assignable only to itself; boxed
   constraint evidence exists only in this generic-argument validation layer.

Roslyn metadata covers type- and method-owned parameters, self-referential constructed bounds,
naked dependent bounds, class/reference/value/default-constructor implications, weaker-source
rejection, owner mismatch, and the distinct `!0`/`!!0` spaces. Direct Framework and CoreCLR
compilation/execution probes keep the ordinary rules profile-uniform, while synthetic selected
metadata pins modern by-ref-like permission and malformed/cyclic contexts.

The thirty-seventh slice preserves physical Param ownership before nullable-reference semantics.

1. JVM retains method, return, and value-parameter annotation attachment in the Java classfile
   model before signature enhancement; Native, JS, and Wasm likewise preserve declaration
   ownership before mapping foreign or serialized metadata into Kotlin types. No mature target
   guesses a return annotation from its payload or applies a declaration annotation to every
   parameter because the physical attachment was discarded.
2. The CLR-specific difference is structural rather than architectural. A MethodDef signature
   contains the authoritative return and parameter types, but optional Param rows separately
   carry names, flags, custom attributes, constants, and marshal metadata. MethodDef.ParamList
   partitions the Param table into contiguous owner runs. Param sequence 0 denotes the return;
   sequence `n + 1` denotes value parameter `n`. An absent Param row is legal, so a foreign
   declaration cannot use Param-row presence as its parameter-count model.
3. Kotlin Common is unchanged. Raw CLR `In`, `Out`, `Optional`, nullable-reference, default, or
   marshal metadata does not itself become a Kotlin modifier, nullability rule, default
   expression, or type. Kotlin-produced declarations remain KLIB-authoritative. This slice
   preserves the evidence required for a later explicit CLR-to-Kotlin policy and creates no FIR
   declaration.
4. The Param table and attachment rules are common CLI metadata on `net48`,
   `netstandard2.0`, and `net10.0`; profile-specific C# compilers may emit different optional
   rows and attributes, but the physical decoding rule does not change. The reader rejects
   invalid ParamList bounds/ordering, reserved flags, and a sequence outside the owner signature.
   It preserves row gaps, duplicate/decreasing sequences, and non-null empty names because ECMA
   classifies those as warnings, leaving ambiguous source projection to a located import-policy
   diagnostic.
5. The implementation therefore extends the existing immutable physical model rather than
   inventing a nullable or C#-parameter model. Each row retains its token, MethodDef owner, raw
   flags, sequence, and nullable name in physical order. Existing CustomAttribute parent handles
   can now attach unambiguously to a return or parameter row. The MethodDef signature still owns
   types and count; missing rows are not synthesized or collapsed into a map.
6. The core-team choice is to land this lossless attachment prerequisite before decoding
   NullableAttribute/NullableContextAttribute. Applying nullable flags first would necessarily
   mis-associate some return or parameter annotations. Constant and FieldMarshal payload tables
   remain explicit later physical slices; their presence flags are retained now and are not
   treated as decoded values.

Framework and modern ILAsm independently preserve named generic-method parameters. A Roslyn
fixture covers an attributed return, attributed input, `out`, optional/default, and marshal
metadata. Byte-level hostile images prove rejection of a reserved flag, an out-of-signature
sequence, and an invalid ParamList; a warning-only duplicate/decreasing sequence remains
losslessly readable. The scale lane validates Param ownership against real .NET 10
`System.Runtime`.

The thirty-eighth slice decodes Roslyn nullable-reference attribute payloads without yet changing
Kotlin types.

1. JVM keeps declaration, return, parameter, and type-use annotations in its foreign binary model
   and applies Java type enhancement only after ownership and type structure are known. Native
   export/import and JS/Wasm serialization likewise keep logical declaration facts separate from
   their eventual target type projection. The uniform rule is to preserve and validate the
   foreign contract before creating Kotlin-facing types.
2. The CLR-specific difference is the C# compiler convention. Nullable reference annotations are
   not runtime CLR type distinctions: Roslyn encodes local preorder transforms in
   `NullableAttribute(byte|byte[])`, inherited defaults in `NullableContextAttribute(byte)`, and
   optional accessibility filtering in the module-level
   `NullablePublicOnlyAttribute(bool)`. Roslyn can embed these attribute types privately in each
   producer, so a fixed framework-assembly identity would reject valid C# output.
3. Kotlin Common is unchanged. Values 0, 1, and 2 are retained as oblivious, not-annotated, and
   annotated foreign evidence; they do not immediately become Kotlin platform, non-null, or
   nullable types. In particular, a missing local attribute may mean an enclosing Roslyn context,
   deliberate obliviousness, or accessibility filtering. Kotlin-produced declarations remain
   KLIB-authoritative.
4. These custom-attribute shapes are legal ordinary metadata on `net48`, `netstandard2.0`, and
   `net10.0`; framework availability changes whether Roslyn embeds private attribute definitions,
   not the semantic decoding. Profile selection remains responsible for the assembly graph.
   Public-only filtering is preserved as a module fact and is not applied until effective CLR
   accessibility is available.
5. The decoder therefore reuses exact custom-attribute constructor, ancestry, generic
   substitution, and value decoding. It recognizes only exact top-level
   `System.Runtime.CompilerServices` names with the exact Roslyn constructor signatures, without
   imposing an assembly identity. Scalar and array transforms remain distinct, context and
   public-only values remain distinct, and no ad-hoc blob parser or C# display-type model is
   introduced.
6. The core-team choice is an explicit `Absent`/`Decoded`/`Invalid` result below FIR enhancement.
   Duplicate recognized compiler attributes, malformed values, null flag arrays, named
   arguments, and flags outside 0..2 are invalid instead of depending on metadata row order. This
   is deliberately stricter than arbitrary first-wins recovery while leaving every valid Roslyn
   binary unaffected. It does not change the ordinary custom-attribute multiset rule: these three
   attributes are singleton compiler controls, not ordinary repeatable API annotations. Type-tree
   application, enclosing-context lookup, effective-accessibility filtering, and Kotlin
   enhancement diagnostics are the next semantic slice.

The Roslyn fixture covers a nullable constructed generic return, nested nullable arguments,
reference arrays, a nullable generic constraint, context compression, oblivious declarations,
and the compiler's `nullablePublicOnly` module feature. Synthetic selected-metadata variants prove
that duplicate attributes, a null byte-array payload, and an out-of-range context flag remain
located invalid results. The decoder still creates no FIR declaration or Kotlin type.

The thirty-ninth slice aligns one selected transform with the resolved CLR signature tree.

1. JVM enhancement flattens a foreign type into indexed components and computes qualifiers for
   those components before constructing enhanced Kotlin types. Roslyn likewise traverses its
   resolved `TypeWithAnnotations` tree in a deterministic preorder and accepts an array transform
   only when every position is consumed. The shared architectural rule is an explicit alignment
   phase between decoded annotation evidence and the resolved foreign type shape.
2. CLR type structure changes which nodes consume a Roslyn flag. Reference primitives, nominal
   references, generic parameters, pointers, arrays, ordinary generic instances, and function
   pointers consume before their children. Non-generic value types and `Nullable<T>` consume no
   position, while another generic value type consumes an oblivious position before its
   arguments. `ref` and custom modifiers are signature wrappers rather than C# type nodes and
   consume no position. Function pointers then traverse return type followed by parameters.
3. Kotlin Common remains untouched. `NOT_ANNOTATED` is deliberately named as Roslyn evidence
   rather than `NOT_NULL`; neither it nor `ANNOTATED` becomes a Kotlin type in this layer.
   Value-type skipping is metadata alignment, not a declaration that CLR value types obey Kotlin
   nullability or boxing semantics.
4. The traversal is identical on `net48`, `netstandard2.0`, and `net10.0`. The selected profile
   supplies the actual nominal definitions and hierarchy used to validate class/value encoding
   and identify `System.Nullable<T>`; no profile gains a different nullability meaning.
5. The implementation reuses `DotNetClrResolvedTypeSignature` and
   `DotNetClrPhysicalTypeClassifier`. It returns a flat ordered list of exact resolved component
   nodes, matching the mature indexed-qualifier pattern, instead of introducing a parallel C#
   display-type tree. Uniform transforms repeat over every consuming component. Sequence
   transforms must match the component count exactly.
6. The core-team choice is a non-throwing `Applied`/`Invalid` result below FIR. A flag-count
   mismatch or invalid nominal class/value shape remains located invalid evidence; no prefix is
   applied and no later flags are shifted onto the wrong generic argument. Roslyn recovers from a
   mismatch by leaving its type unchanged; the Kotlin importer retains the reason explicitly so
   its later diagnostic policy can choose an oblivious fallback without hiding malformed input.
   Enclosing-context selection and `NullablePublicOnly` accessibility remain the next declaration
   policy slice.

Real Roslyn output pins a nullable reference generic return, generic parameter context, arrays,
`ref`, a generic struct with a skipped primitive argument, and the special outer
`Nullable<GenericStruct<...>>` rule. Synthetic input pins function-pointer return/parameter order,
exact count mismatch, and invalid nominal class/value encoding.

The fortieth slice selects nullable evidence for one physical declaration before applying it to
that declaration's resolved type.

1. JVM enhancement first resolves annotation ownership, declaration applicability, and default
   qualifiers, then aligns the selected qualifiers with the foreign type tree. It does not let a
   missing declaration-site annotation manufacture a definitely-non-null Kotlin type. The .NET
   importer follows that same selection-before-enhancement boundary.
2. The CLR-specific difference is Roslyn's module `NullablePublicOnlyAttribute(bool
   IncludesInternals)` convention. An absent marker includes every declaration; `false` includes
   public/protected declarations; `true` additionally includes internal and
   private-protected declarations. Effective visibility folds the declaration through every
   containing type. Parameters and generic parameters use their method/type owner. Property and
   event rows have no CLR accessibility of their own, so Roslyn's
   `CSharpCompilation.ShouldEmitNullableAttributes` climbs them to the containing type and
   `PEPropertySymbol` deliberately supplies that type to `PEModuleSymbol` when decoding. A private
   C# property in a public type is therefore included even though its private accessor MethodDefs
   are independently suppressed. This unintuitive rule is metadata compatibility, not a Kotlin
   visibility decision. See Roslyn's
   [emitter policy](https://github.com/dotnet/roslyn/blob/c67ab9a38782b72900e4c758a6fcea476a600b44/src/Compilers/CSharp/Portable/Compilation/CSharpCompilation.cs#L4917-L4955),
   [PE property importer](https://github.com/dotnet/roslyn/blob/c67ab9a38782b72900e4c758a6fcea476a600b44/src/Compilers/CSharp/Portable/Symbols/Metadata/PE/PEPropertySymbol.cs#L330-L333),
   and
   [nullable metadata contract](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md).
3. Kotlin Common is unchanged. Selected flags remain foreign evidence; an included declaration
   without local or enclosing evidence is oblivious, while an excluded declaration is
   suppressed. Neither result is a definitely-non-null Kotlin type, and Kotlin-produced
   declarations remain KLIB-authoritative.
4. The compiler convention and ECMA visibility flags have the same meaning on `net48`,
   `netstandard2.0`, and `net10.0`. The selected assembly graph may expose different declarations,
   but no profile changes the selection algorithm.
5. `DotNetClrNullableEffectiveAccessibilityResolver` computes a closed
   public/internal/private category from exact metadata flags and bounded containing-type
   ownership. Then
   `DotNetClrNullableDeclarationResolver` applies the module policy before decoding local
   evidence, selects a Param/Field/Property/GenericParam attribute when present, or walks the
   nearest MethodDef and containing TypeDefs for context. Missing rows are not synthesized and
   malformed ownership, duplicates, visibility, attributes, cycles, or depth remain structured
   failures below FIR.
6. The core-team choice is an explicit `Selected`/`Oblivious`/`Suppressed`/`Invalid` result that
   still constructs no Kotlin type. This keeps compatibility filtering separate from diagnostic
   fallback and type-tree application, prevents excluded malformed local payloads from producing
   spurious diagnostics, and makes the later FIR policy decide how oblivious or invalid foreign
   evidence is surfaced.

Real Roslyn fixtures pin both values of `NullablePublicOnly`, friend-assembly inclusion,
public/internal/private/protected members, fields, properties, accessor MethodDefs, and nested
visibility. Synthetic selected metadata pins an out-of-range parameter, duplicate Param
attachment, invalid ownership, invalid visibility, containing-type cycles, the traversal limit,
and malformed local evidence that must remain suppressed when the module policy excludes it.

The forty-first slice composes declaration evidence with type-shape application while retaining
an explicit unchanged-type fallback.

1. JVM FIR signature enhancement computes indexed qualifiers with `JavaTypeQualifiers.NONE` for
   an unqualified component and uses the unenhanced converted Java type when enhancement returns
   no replacement. The important boundary is that absence does not delete the foreign
   declaration or manufacture a rigid Kotlin type. The .NET importer follows that fallback shape
   below FIR.
2. Roslyn's `NullableTypeDecoder.TransformType` returns the unchanged metadata type when no local
   or context flag exists, the module accessibility policy excludes the declaration, or an array
   transform does not line up with the type tree. That is a CLR/C#-metadata compatibility rule:
   nullable-reference metadata is advisory compiler evidence, not the runtime signature. See the
   [Roslyn decoder](https://github.com/dotnet/roslyn/blob/c67ab9a38782b72900e4c758a6fcea476a600b44/src/Compilers/CSharp/Portable/Symbols/Metadata/PE/NullableTypeDecoder.cs#L14-L72).
3. Kotlin Common is unchanged. “Unchanged” here means the exact resolved physical CLR signature,
   not a Kotlin platform type and not a claim that an unannotated reference is non-null.
   `NOT_ANNOTATED` and `ANNOTATED` remain ordered evidence until generic interaction and FIR
   projection choose Kotlin-facing types.
4. The fallback and evidence application are profile-neutral on `net48`, `netstandard2.0`, and
   `net10.0`; the selected profile still owns nominal type resolution and physical
   classification.
5. `DotNetClrNullableEvidenceApplicator` composes the existing declaration resolver's output with
   the physical preorder applicator without re-resolving or guessing a declaration signature.
   Selected valid evidence becomes `Applied`; ordinary missing and accessibility-filtered
   evidence remain separate `Oblivious` and `Suppressed` results. Invalid declaration evidence
   and invalid type-transform alignment become distinct `DiagnosticFallback` results carrying
   the unchanged physical type and original structured cause.
6. The core-team choice is not Roslyn's silent invalid-input recovery. Importing the unchanged
   runtime signature preserves the foreign API, but the invalid cause remains mandatory for the
   later FIR diagnostic policy. Collapsing it into ordinary obliviousness would hide malformed
   metadata; failing or dropping the whole declaration would let advisory C# metadata shrink a
   valid CLR API. Diagnostic severity and the Kotlin type remain later policy.

Real selected, context, oblivious, and accessibility-suppressed Roslyn evidence pins every
non-diagnostic result. Synthetic selected metadata pins both flag-count and physical
classification fallback, while an invalid declaration target pins the distinct declaration
diagnostic path.

The forty-second slice keeps nullable generic-parameter declaration evidence separate from
nullable evidence on each constraint type.

1. JVM `AbstractSignatureParts` enhances every Java bound first, then derives
   `boundsNullability` from those enhanced bounds when qualifying a type-parameter use. A
   declaration/default qualifier is not copied blindly across every bound. The .NET importer
   follows the same order: preserve declaration evidence, enhance individual constraints, and
   leave bound propagation to the Kotlin-facing layer.
2. Roslyn has two physically independent channels. `PETypeParameterSymbol` reads a single
   GenericParam `NullableAttribute` value or the containing method/type context for the C#
   `class`, `class?`, `notnull`, and unconstrained marker. It separately calls
   `NullableTypeDecoder.TransformType` with each GenericParamConstraint handle, using the
   containing symbol for accessibility and context. See Roslyn's
   [constraint-row transform](https://github.com/dotnet/roslyn/blob/c67ab9a38782b72900e4c758a6fcea476a600b44/src/Compilers/CSharp/Portable/Symbols/Metadata/PE/PETypeParameterSymbol.cs#L296-L305)
   and
   [parameter-marker interpretation](https://github.com/dotnet/roslyn/blob/c67ab9a38782b72900e4c758a6fcea476a600b44/src/Compilers/CSharp/Portable/Symbols/Metadata/PE/PETypeParameterSymbol.cs#L470-L560).
3. Kotlin Common is unchanged. A C# parameter marker is foreign declaration evidence, not a
   Kotlin upper bound, and a nullable C# constraint type does not redefine Kotlin's `T : Any?`
   rule. Definitely-non-null and bound-propagation choices remain in FIR after every imported
   bound has a Kotlin-facing type.
4. GenericParam and GenericParamConstraint rows have the same CLI meaning on `net48`,
   `netstandard2.0`, and `net10.0`. The selected profile resolves nominal identities, but does
   not change where nullable evidence is attached or how its preorder is aligned.
5. The declaration resolver therefore gains an exact GenericParamConstraint target. Its
   accessibility and enclosing context come from the owning generic parameter's TypeDef or
   MethodDef, while its local transform comes only from the constraint row. A generic-parameter
   evidence resolver aggregates the untouched parameter marker with one evidence application per
   resolved row from a declaration-qualified identity context.
6. The core-team choice rejects two tempting shortcuts. Copying the parameter marker to every
   constraint loses real nested constraint annotations. Applying constraint flags after
   substituting `!n` can change the physical tree, so one original flag could be shifted onto
   several substituted nodes. Only original identity-context constraints are aligned here;
   substitution and Kotlin bound propagation happen later. Missing context bindings and
   malformed row evidence remain structured fallback rather than guessed nullability.

Real Roslyn metadata pins `class`, `class?`, `notnull`, and unconstrained parameter markers beside
non-null, nullable, nested-generic, method-owned, and multiple constraint-row transforms. Hostile
metadata pins owner mismatch, non-identity context rejection, non-scalar parameter markers,
malformed constraint-row fallback, and accessibility suppression before malformed local decoding.

The forty-third slice projects valid nullable evidence into Kotlin's established foreign-type
qualifier vocabulary without constructing a FIR type.

1. JVM `NullabilityQualifier` has exactly the three policy states needed here:
   `FORCE_FLEXIBILITY`, `NOT_NULL`, and `NULLABLE`. JVM enhancement keeps an unenhanced foreign
   type when no qualifier applies, while a forced-flexibility qualifier can explicitly preserve a
   platform position even when surrounding defaults or bounds might otherwise make it rigid. The
   .NET importer follows that Kotlin-facing model rather than inventing C#-named Kotlin type
   categories.
2. C# defines the corresponding type states as oblivious, nonnullable, and nullable, and Roslyn
   serializes them as nullable flags 0, 1, and 2. For semantic nullable positions the projection
   is therefore exact: `OBLIVIOUS -> FORCE_FLEXIBILITY`,
   `NOT_ANNOTATED -> NOT_NULL`, and `ANNOTATED -> NULLABLE`. See the C#
   [three-state type model](https://github.com/dotnet/csharplang/blob/main/proposals/csharp-9.0/nullable-reference-types-specification.md#nullability-of-types)
   and Roslyn's
   [metadata encoding](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md#nullableattribute).
3. Roslyn's generic-value-type leading 0 is a CLR-specific structural exception. It exists only to
   keep preorder decoding independent of generic constraints and is not a nullable type position.
   The physical applicator tags it as `GENERIC_VALUE_TYPE_PADDING`, requires the value to remain
   oblivious, and retains it for alignment auditing. The Kotlin projector omits that component
   while continuing to project every semantic type argument beneath the value type. Treating the
   padding as a platform type would make Kotlin Common nullability depend on a serialization
   convenience.
4. Kotlin Common remains authoritative and unchanged. This mapping affects only imported CLR type
   positions carrying valid C# compiler evidence. Kotlin declarations retain their own declared
   nullability, and a known CLR value type never acquires a reference-nullability qualifier.
5. `DotNetClrKotlinNullabilityProjector` maps every already aligned semantic component and retains
   the original evidence application, including structural padding, for auditing. Ordinary
   oblivious declarations,
   accessibility-suppressed declarations, and both structured diagnostic fallbacks remain
   separate unchanged-type projections. The projector does not re-run metadata selection,
   alignment, or physical classification.
6. Valid Roslyn nullable metadata is a compiler-owned foreign type contract, not a configurable
   third-party migration annotation. The projected qualifier therefore has no warning-only mode:
   a later FIR adapter must apply valid `NOT_NULL` and `NULLABLE` evidence as ordinary enhanced
   types. This does not decide the severity of the separate malformed-metadata diagnostic.
7. The core-team choice rejects weakening flag 1 to flexibility: that would erase C#'s explicit
   nonnullable contract and make flags 0 and 1 observably identical. It also rejects deriving
   definitely-non-null from a generic-parameter marker. JVM computes that property only with
   enhanced parameter bounds in view, and C# likewise defines type-parameter nullability from the
   complete constraint set.
8. Malformed-metadata diagnostic severity remains a later slice. Assigning severity here would
   couple a reusable type-qualifier operation to declaration source locations, language settings,
   and FIR reporting that do not exist at this boundary. The existing mandatory diagnostic
   fallback remains intact so that later policy cannot silently reinterpret it as obliviousness.

Real Roslyn returns and generic constraints pin not-null, nullable, nested mixed sequences, and
generic-value padding; synthetic selected semantic evidence pins forced flexibility. A
non-oblivious hostile padding value is rejected. Existing oblivious, public-only-suppressed,
flag-count, physical-classification, and malformed-declaration cases adversarially pin every
unchanged projection and exact object identity across fallback.

The forty-fourth slice classifies every CLR DLL classpath entry before either Kotlin metadata or
foreign declarations are exposed.

1. JVM classpath loading distinguishes Kotlin-produced class files carrying Kotlin metadata from
   ordinary Java class files, then feeds each through its own declaration provider. Native
   likewise does not require a foreign platform library to pretend to be a KLIB. The .NET
   frontend must therefore stop treating every `.dll` as a Kotlin library whose missing
   `Kotlin.Metadata` resource is an error.
2. A private managed resource named `Kotlin.Metadata` is the self-describing Kotlin/.NET artifact
   discriminator. Its presence selects the existing Kotlin-produced path, where the embedded KLIB
   remains authoritative and is authenticated against the containing Assembly row. Its absence
   selects the ordinary CLR path, where the bounded physical metadata graph is authoritative.
   These alternatives are disjoint even though both use the same PE reader.
3. Selection never falls back. A present but non-private or malformed `Kotlin.Metadata` resource
   is an invalid Kotlin-produced dependency, not permission to reinterpret compiler-owned
   implementation rows as a foreign API. An invalid PE remains a bad classpath entry rather than
   an ignored dependency.
4. Kotlin Common is unchanged. Kotlin-produced declarations retain KLIB identity and semantics;
   ordinary CLR declarations retain only physical assembly metadata at this slice. Merely placing
   a foreign DLL on the classpath does not manufacture FIR declarations, Kotlin identities,
   runtime references, or copied deployment artifacts.
5. The frontend records canonical foreign assembly files and their exact decoded metadata in
   classpath order. This is the selected-input boundary needed by the future lazy CLR FIR provider
   and assembly-reference binder. It deliberately does not bind duplicate identities, resolve the
   graph, or choose first-wins declaration shadowing before that provider owns lookup policy.
6. The core-team choice rejects ignoring a valid foreign DLL after accepting it: that would make
   later provider behavior depend on reparsing configuration roots and would lose deterministic
   classpath order. It also rejects importing Kotlin-produced physical rows beside their KLIB:
   doing so would create two declaration identities for one assembly.
7. Malformed nullable-metadata reporting still waits for the provider/source boundary. Mature JVM
   incompatible-binary checkers report from a deserialized declaration source at a Kotlin usage
   site; the current metadata-only .NET FIR session has neither a foreign symbol provider nor a
   foreign source element. DLL classification is the prerequisite, not a reason to invent
   location-free frontend diagnostics.

The classifier opens each entry through one bounded PE-reader session and reuses one parsed
metadata image for resource selection and foreign-row decoding. Adversarial coverage distinguishes
a valid resource-free CLR assembly, existing valid Kotlin-produced metadata carriers, an invalid
PE, and a carrier whose reserved resource is present but malformed. The foreign case retains its
exact physical Assembly identity and allows an otherwise unrelated compilation; the malformed
carrier remains on the Kotlin path and fails. The focused test is 1/0/0/0. The fresh strict gate is
871/0/0/0 across 16 XML suites (796 FIR/IL/box, 21 generated CLI, and 54 library integration
tests).

The forty-fifth slice preserves and decodes physical Constant rows without yet projecting a
foreign declaration.

1. JVM classfile loading retains the `ConstantValue` attribute as
   `JavaField.initializerValue`; FIR later creates a foreign constant expression and decides
   whether it has Kotlin compile-time meaning. Kotlin metadata/KLIB similarly serializes a
   compile-time value separately from the declaration. The CLR reader therefore keeps Constant
   rows separate from Field, Param, and Property definitions rather than mutating those models
   into Kotlin defaults.
2. Each row retains its Constant token, exact HasConstant parent, semantic scalar, and raw blob.
   The Type byte admits only Boolean, Char, I1/U1/I2/U2/I4/U4/I8/U8, R4/R8, String, or
   Class-as-nullref, followed by the required zero padding byte. Integral and floating values keep
   their exact little-endian bits, so unsigned maxima, signed zero, and NaN payloads survive.
3. The value decoder follows the official `System.Reflection.Metadata.BlobReader` compatibility
   contract rather than inventing a stricter source-language encoding. A one-byte Boolean treats
   any non-zero value as true. A String consumes complete UTF-16LE code-unit pairs; unpaired
   surrogates remain code units, and a final odd raw byte is retained but has no character.
   Fixed-width scalars require at least their width and retain any trailing raw bytes. Nullref
   requires a four-byte zero prefix. This is the CLR-specific reason not to reject encodings that
   the platform metadata reader accepts.
4. The physical reader rejects an invalid Type, non-zero padding, invalid or nil parent,
   duplicate parent, truncated scalar, non-zero nullref prefix, or oversized value. ECMA requires
   a Field with HasDefault to own exactly one Constant row. A Param has the stronger bidirectional
   rule: HasDefault owns one, and absence forbids one. The Property table defines HasDefault but
   does not specify an equivalent error rule, so the physical layer retains that flag without
   adding one.
5. Constant.Type matching the declared parent type is a CLS rule and can require selected-graph
   knowledge, notably an enum's exact underlying type. That validation stays above the
   profile-neutral reader. The reader also does not infer a missing row from `literal` or
   HasDefault, coerce a row through C# syntax, or treat a field constant as runtime storage.
6. Kotlin Common remains authoritative. A CLR Param constant is not a Kotlin default argument, a
   Property constant is not a Kotlin property initializer, and a Field constant becomes a Kotlin
   compile-time initializer only if the future FIR provider's explicit foreign-import policy
   chooses that projection. Kotlin-produced DLLs continue to use their KLIB metadata instead of
   these physical rows.
7. The core-team choice rejects attaching the decoded value directly to Field/Param/Property and
   losing its row token: custom attributes and diagnostics can target the Constant row/parent
   distinction, while ambiguous or CLS-invalid type relationships need later structured
   diagnostics. It also rejects converting floating values through host `Float`/`Double`
   equality, which would lose observable payload bits.

The implementation gate is a dual-ILAsm executable probe plus a physical-reader fixture covering
every supported Type and Field/Param/Property ownership. Hostile images must cover invalid
Type/padding/tag, duplicate parents, truncated values, non-zero nullref, and both directions of
the normative flag/row rules. Compatibility cases must pin non-one true, raw trailing bytes,
UTF-16 code units, and exact floating bits. No test may claim Kotlin default-argument or constant
projection at this slice.

The completed probe assembled with modern 10.0.9 and Framework 4.8 ILAsm, and its Framework
executable returned zero. The focused dual-ILAsm physical/adversarial test is 1/0/0/0. It covers
every allowed Type, all three parent kinds, exact NaN/signed/unsigned bits, non-one true, odd and
unpaired UTF-16, trailing scalar bytes, invalid Type/padding/tag/duplicate parent,
truncation/non-zero nullref, and the exact Field/Param/Property flag asymmetry. The fresh strict
gate is 871/0/0/0 across 16 XML suites (796 FIR/IL/box, 21 generated CLI, and 54 library
integration tests).

The forty-sixth slice preserves physical FieldMarshal rows without yet interpreting CLR interop
semantics.

1. Kotlin Common has no unmanaged-marshalling declaration contract. JVM binary import has no
   equivalent marshal descriptor, while Kotlin/Native C interop emits explicit Native-only
   `CCall.CString`/`CCall.WCString` and related stub annotations after the Common declaration
   meaning is fixed. The .NET importer must likewise retain target evidence below a later
   platform policy rather than changing a Kotlin type, modifier, parameter mode, default, or
   storage rule in the physical reader.
2. ECMA-335 table 13 owns a HasFieldMarshal parent, restricted to Field or Param, and a non-null
   NativeType blob. A parent may occur at most once. A Field or Param whose HasFieldMarshal flag
   is set must own exactly one row; ECMA does not specify the reverse implication, so a row
   without the flag remains readable.
3. The descriptor bytes are deliberately opaque in this slice. The official
   `System.Reflection.Metadata` FieldDefinition and Parameter APIs return a raw `BlobHandle`
   rather than a typed marshal model, and .NET's `UnmanagedType` enumeration contains platform
   forms outside the narrow ECMA II.23.4 grammar. Eagerly accepting only that grammar would reject
   the target platform's own metadata; eagerly inventing support for every runtime extension
   would duplicate selected-profile interop policy inside the physical reader.
4. Every row therefore retains its FieldMarshal token, exact parent handle, and copied raw blob.
   A nil/unread parent, duplicate parent, nil blob index, or oversized descriptor is invalid
   physical metadata. A present zero-length or unknown descriptor remains distinct evidence:
   its semantic validity belongs to a later selected-profile decoder that can issue a located,
   structured interop diagnostic. Nil and present-empty must not collapse.
5. The core-team choice is lossless layering. Parsing enough bytes merely to reject an empty or
   unknown descriptor here looks stricter, but creates split semantic validation and prevents
   future runtime/profile extensions from reaching the owning declaration. Conversely, treating
   the raw row as a Kotlin `MarshalAs` annotation would expose a C#/.NET implementation detail as
   Common source semantics. Both alternatives are rejected.
6. Kotlin-produced DLLs continue to select their authoritative KLIB and never import these
   physical rows beside it. Ordinary foreign CLR assemblies retain them in row order for the
   future lazy FIR provider and interop policy.

The implementation gate requires real Roslyn metadata for both Field and Param owners plus
byte-level hostile images for nil/unread/duplicate parents, nil blob indices, flag-without-row,
row-without-flag, empty/unknown payload retention, exact raw bytes, and defensive size handling.
No test may claim marshal-spec interpretation or Kotlin annotation/type projection at this slice.

The completed Roslyn fixture emits `LPUTF8Str` on a Field and `LPWStr` on a Param and pins their
exact `0x30` and `0x15` descriptor bytes, table-13 tokens, owners, and flags. Hostile images cover a
nil or out-of-range parent, duplicate parent, nil NativeType index, Field/Param
flag-without-row, the accepted row-without-flag converse, and lossless unknown/present-empty
blobs. The same reader also traverses real Framework `mscorlib` and net10 `System.Runtime`
metadata. The focused integration test is 1/0/0/0. The fresh strict gate is 871/0/0/0 across
16 XML suites (796 FIR/IL/box, 21 generated CLI, and 54 library integration tests).

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
- Lossless optional Param rows with MethodDef/return/value-parameter attachment:
  **Correct direction**.
- Decoding nullable-reference attributes before Param attachment exists:
  **Architecturally wrong and should be changed**.
- Exact Roslyn nullable-attribute recognition by top-level name plus resolved constructor shape,
  without a fixed defining assembly: **Reasonable platform-specific divergence**.
- Separate scalar/sequence/context/public-only nullable evidence with explicit invalid results:
  **Correct direction**.
- Treating duplicate compiler nullable attributes as first-wins input:
  **Architecturally wrong and should be changed**.
- Deferring Kotlin/FIR type enhancement until type-tree, context, accessibility, and generic
  policy are available: **Correct temporary implementation, but not a final design**.
- Roslyn-preorder alignment over resolved CLR signature components:
  **Correct direction**.
- Skipping non-generic value types, `Nullable<T>`, by-reference, and modifier wrappers while
  retaining generic-value and function-pointer positions:
  **Reasonable platform-specific divergence**.
- Partially applying a short or long nullable transform:
  **Architecturally wrong and should be changed**.
- Retaining flag-count and physical-classification failures below later diagnostic policy:
  **Correct direction**.
- Declaration-level nullable evidence selection and effective-accessibility filtering before FIR:
  **Correct direction**.
- Deriving nullable-public-only accessibility for a Property row from its accessor visibility:
  **Architecturally wrong and should be changed**.
- Treating suppressed or missing nullable evidence as definitely non-null:
  **Architecturally wrong and should be changed**.
- Unchanged physical-type fallback for oblivious or accessibility-suppressed nullable evidence:
  **Correct direction**.
- Retaining malformed declaration/type-alignment causes in an explicit diagnostic fallback:
  **Correct direction**.
- Silently collapsing invalid nullable evidence into ordinary obliviousness:
  **Architecturally wrong and should be changed**.
- Dropping a valid CLR declaration because its advisory nullable metadata is malformed:
  **Architecturally wrong and should be changed**.
- Keeping GenericParam declaration evidence separate from per-GenericParamConstraint transforms:
  **Correct direction**.
- Applying a generic-parameter marker to every constraint type:
  **Architecturally wrong and should be changed**.
- Aligning constraint nullability after generic substitution changes the original type tree:
  **Architecturally wrong and should be changed**.
- Exact 0/1/2 projection to forced-flexible/not-null/nullable Kotlin foreign qualifiers:
  **Correct direction**.
- Projecting a generic-value-type preorder padding flag as Kotlin nullability:
  **Architecturally wrong and should be changed**.
- Retaining and validating generic-value padding in physical evidence while omitting it from
  Kotlin qualifiers: **Reasonable platform-specific divergence**.
- Treating valid C# nonnullable evidence as a platform type:
  **Architecturally wrong and should be changed**.
- Deriving Kotlin definitely-non-null from a CLR generic-parameter marker before enhanced bounds:
  **Architecturally wrong and should be changed**.
- Assigning malformed-nullable-metadata diagnostic severity in the reusable qualifier projector:
  **Architecturally wrong and should be changed**.
- Treating every CLR DLL as a Kotlin-produced embedded KLIB:
  **Architecturally wrong and should be changed**.
- Disjoint DLL classification by reserved-resource presence, retaining resource-free assemblies
  as ordered physical CLR metadata: **Correct direction**.
- Falling back to foreign import after a present Kotlin metadata resource fails visibility or
  decoding validation: **Architecturally wrong and should be changed**.
- Importing the physical CLR rows of a Kotlin-produced dependency beside its authoritative KLIB:
  **Architecturally wrong and should be changed**.
- Binding or projecting retained foreign assemblies before a lazy FIR provider owns lookup and
  source policy: **Correct temporary deferral, but not a final design**.
- Lossless physical Constant rows with platform-compatible scalar decoding and later
  Field/Param/Property semantic projection: **Correct direction**.
- Lossless opaque physical FieldMarshal rows with later selected-profile descriptor decoding:
  **Correct direction**.
- Restricting physical FieldMarshal blobs to ECMA's narrower MarshalSpec grammar despite .NET
  platform extensions: **Architecturally wrong and should be changed**.
- Projecting FieldMarshal rows directly as Kotlin types, modifiers, annotations, defaults, or
  storage rules: **Architecturally wrong and should be changed**.
- Lossless MemberRef and reusable FieldSig model: **Correct direction**.
- Physical FieldDef preservation on the reusable FieldSig model: **Correct direction**.
- Physical Property/PropertyMap/MethodSemantics preservation: **Correct direction**.
- Physical GenericParam/GenericParamConstraint preservation: **Correct direction**.
- Physical InterfaceImpl preservation with its own attachment token:
  **Correct direction**.
- Exact custom-attribute constructor and `System.Attribute` hierarchy resolution:
  **Correct direction**.
- Closed TypeSpec-owned generic attribute identity and constructor substitution:
  **Correct direction**.
- Assembly-context-bearing constructed-type constraint resolution and owner substitution:
  **Correct direction**.
- Keeping direct nominal constraints distinct from TypeSpec signatures:
  **Correct direction**.
- Assembly-context-bearing immediate hierarchy views with owner substitution:
  **Correct direction**.
- Keeping imported hierarchy resolution separate from module-local IL codegen assignability:
  **Correct direction**.
- Bounded exact-nominal assignability over selected imported hierarchy views:
  **Correct direction**.
- Deferring CLR variance and conversion rules until physical type classification is available:
  **Correct temporary implementation, but not a final design**.
- Per-row nominal GenericParamConstraint validation with non-boolean unsupported/invalid results:
  **Correct direction**.
- Treating nominal validation as incomplete until special constraints, dependent parameters,
  primitives, arrays, and by-ref-like eligibility are implemented:
  **Correct temporary implementation, but not a final design**.
- Selected-core-verified reference/non-nullable-value/Nullable signature classification:
  **Correct direction**.
- Keeping CLR `Nullable<T>` classification distinct from Kotlin nullability:
  **Correct direction**.
- Deferring by-ref-like classification to exact decoded marker semantics rather than value-type
  ancestry or name matching: **Correct direction**.
- Exact decoded and multiplicity-aware selected-profile by-ref-like marker classification:
  **Correct direction**.
- Keeping by-ref-like identity separate from Kotlin usability and `AllowByRefLike` legality:
  **Correct direction**.
- Per-rule reference/value/by-ref-like constructed-argument validation with explicit target
  profile and non-boolean unsupported/invalid results: **Correct direction**.
- Exact selected-TypeDef/MethodDef validation of the CLR default-constructor constraint:
  **Correct direction**.
- Keeping CLR constructor-constraint satisfaction separate from Kotlin Common constructor
  semantics: **Correct direction**.
- Complete selected-core CLR primitive TypeDef catalog with structured resolution:
  **Correct direction**.
- Reusing boxed selected primitive views for nominal generic-constraint assignability:
  **Correct direction**.
- Keeping the physical primitive catalog separate from Kotlin built-in identity:
  **Correct direction**.
- Preserving a reachable variant-conversion candidate instead of reporting false exact
  non-assignability: **Correct direction**.
- Sealed aggregate generic-constraint status with invalid/unsupported/violated/satisfied
  precedence and retained per-row evidence: **Correct direction**.
- Deferring remaining dependent-parameter assignability behind explicit unsupported results:
  **Correct temporary implementation only when no declaring context is selected**.
- Scope-qualified type/method generic-parameter contexts with bounded constraint implication:
  **Correct direction**.
- Treating `(TYPE|METHOD, index)` as a declaration-independent identity:
  **Architecturally wrong and should be changed**.
- Inferring stronger public CLR constraints from a Kotlin use site:
  **Architecturally wrong and should be changed**.
- Bounded recursive reference-only CLR generic-interface variance in shared assignability:
  **Correct direction**.
- Keeping value arguments invariant in CLR variance rather than applying boxing:
  **Correct direction**.
- Recognizing physical delegates only by a sealed direct base edge to the selected
  `System.MulticastDelegate` identity: **Correct direction**.
- Reusing the bounded reference-only generic variance evaluator for interfaces and delegates:
  **Correct direction**.
- Generalizing interface variance into bounded physical signature assignability:
  **Correct direction**.
- Rank-aware CLR array-to-array assignability without changing Kotlin `Array` invariance:
  **Correct direction**.
- Implementing CLR reduced integer/enum array storage compatibility only at the foreign signature
  boundary: **Reasonable platform-specific divergence**.
- Resolving physical arrays through the selected `System.Array` identity and ordinary hierarchy:
  **Correct direction**.
- Treating general arrays as proven outside the vector-only generic interface surface:
  **Correct direction**.
- Resolving the five VES/BCL vector interfaces as a complete selected-identity catalog:
  **Correct direction**.
- Reusing array-element compatibility rather than ordinary generic variance for vector interfaces:
  **Reasonable platform-specific divergence**.
- Profile-neutral physical retention with Kotlin-facing generic-attribute support gated to a
  proven runtime profile: **Reasonable platform-specific divergence**.
- Constructor-typed scalar custom-attribute decoding with exact observable bits:
  **Correct direction**.
- Typed array and tagged-object decoding with bounded untrusted-input recursion:
  **Correct direction**.
- Exact fixed-enum identity and storage decoding: **Correct direction**.
- Bounded structural CLR serialized-type-name parser: **Correct direction**.
- Keeping AssemblyName parsing and selected-graph binding out of type-name syntax:
  **Correct direction**.
- Runtime-compatible AssemblyName syntax with exact retained identity input:
  **Correct direction**.
- Build-frontend-owned binding plus target resolver for serialized CLR types:
  **Correct direction**.
- Passing both original and unqualified-context assemblies to profile policy:
  **Reasonable platform-specific divergence**.
- Assembly-context-bearing resolved physical signature algebra and generic substitution:
  **Correct direction**.
- Exact constructed generic enum identity for constructor-typed fixed arguments:
  **Correct direction**.
- Separate selected-graph validation of named custom-attribute members:
  **Correct direction**.
- Preserving encoded named-argument identity beside, rather than replacing it with, a resolved
  FieldDef/Property: **Correct direction**.
- Applying the documented ordinary CLR attribute contract instead of CoreCLR malformed-metadata
  quirks: **Reasonable platform-specific divergence**.
- Shared CLR generic-constraint satisfaction/assignability before stable foreign constructed-type
  and generic-attribute projection:
  **Deferred problem that must be recorded before the importer surface becomes stable**.
- Separate lazy FIR import policy/provider: **Correct direction**.
- Reusing embedded KLIB for Kotlin-produced DLLs: **Correct direction**.
- Mapping raw CLR rows directly to Kotlin IR: **Architecturally wrong and should be changed**.
- Kotlin-facing general custom-attribute projection, property synthesis, events, resolved
  constraint semantics, broader FIR symbols, and backend calls:
  **Deferred problems that must be recorded before the importer surface becomes stable**.
- Nullable enhancement and a closed complete-interface FIR symbol slice:
  **Implemented direction; broaden only through separately reviewed contracts**.
- Treating standard CLR/Roslyn attributes as a bidirectional foreign-language contract:
  **Correct direction**.
- Applying valid Roslyn nullable metadata to foreign FIR types while leaving absent or invalid
  evidence flexible: **Correct direction**.
- Re-reading Roslyn nullable attributes as authority when an embedded KLIB already supplies the
  original Kotlin type: **Architecturally wrong and should be changed**.
- Flattening conditional flow attributes such as `NotNullWhen` into declaration nullability:
  **Architecturally wrong and should be changed**.
- Withholding a foreign classifier when the provider cannot represent its complete public
  contract: **Correct temporary implementation for a closed first slice**.
- Inventing public Kotlin/.NET export-annotation names inside an importer implementation:
  **Architecturally wrong and should be changed**.

The cost is a substantial target-owned metadata and import layer. The alternative is greater:
spreading partial CLR decoding and C# assumptions across resolution, IR lowering, codegen, and
tooling would create untestable semantic drift and ABI debt.

## Annotation interoperability refinement (2026-07-29)

The importer treats standard CLR tables and established .NET/Roslyn attributes as the preferred
shared foreign-language vocabulary. A valid foreign `NullableAttribute`/`NullableContextAttribute`
contract changes the enhanced Kotlin type, following JVM Java-type enhancement; it is not merely a
warning payload. Missing, suppressed, malformed, contradictory, or unbound evidence cannot
strengthen a reference type and therefore projects to flexibility.

This does not make Roslyn metadata authoritative for Kotlin-produced declarations. A private
Kotlin metadata resource selects the embedded-KLIB path before foreign declaration construction.
KLIB supplies the original Kotlin type; nullable attributes remain a derived C# view and should be
validated against that source. Conditional CodeAnalysis attributes remain flow contracts and must
not be flattened into declaration nullability. They can, however, map bidirectionally to the exact
overlap with Kotlin contracts, such as `returns(true) implies (x != null)` and non-returning
functions. `callsInPlace`, arbitrary type predicates, and general multi-value implications have no
standard CLR encoding and remain KLIB metadata.

The first FIR provider is a closed vertical slice over complete public, top-level, non-generic
abstract-interface contracts using primitive, `string`, and `object` signatures. If any public
declared method is outside the slice, the classifier is withheld rather than partially projected.
Classifier construction is lazy, duplicate class identities are withheld, and selected-graph
binding accepts only exact unique classpath assemblies. Backend calls are a later slice; this one
proves that physical foreign metadata and its nullable attributes affect Kotlin source analysis.

The detailed mature-target comparison, carrier matrix, attacks, and deferred public-annotation
decision are recorded in `docs/review/clr-annotation-interoperability.md`.

The first conditional-flow slice, `NotNullWhenAttribute`, is implemented. Its Boolean constructor
maps exactly
to common FIR's `returns(true|false) implies (parameter != null)` effect for a Boolean-returning
method and a reference parameter. This mapping does not alter the parameter's declaration type,
infer the inverse condition, or introduce a .NET-only data-flow rule. Duplicate, malformed,
wrong-signature, named-payload, non-reference, and non-Boolean-return shapes contribute no effect.
The JVM frontend's reluctance to import vendor contract strings remains relevant; this CLR
divergence is justified only because `System.Diagnostics.CodeAnalysis.NotNullWhenAttribute` is a
standard platform binary contract consumed by Roslyn and matches Kotlin's effect algebra exactly.
The focused real/hostile-metadata smart-cast test is 1/0/0/0. The fresh strict gate is
873/0/0/0 across 16 XML suites (796 FIR/IL/box, 21 generated CLI, and 56 library integration
tests).
