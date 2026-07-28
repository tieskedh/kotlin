# Draft ADR: Structured CLR importer boundary

- Status: **Draft candidate; physical declaration metadata, bounded type-identity resolution, custom-attribute values through constructed fixed enums, and selected-graph named-member validation are implemented**
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

The ninth slice resolves the constructor edge before reading the custom-attribute value blob. This
follows the JVM importer rule that a foreign annotation class and constructor are resolved before
annotation arguments are interpreted. The CLR-specific difference is that the constructor token
is either a local MethodDef or a MemberRef whose owner is a TypeDef or TypeRef. The resolver
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
MemberRefs owned by a constructed TypeSpec fail with a dedicated unsupported-parent result. That
is a correct temporary boundary, not a final generic-attribute design: resolving such owners
requires a constructed declaring-type identity and substitution model that must be shared with
the general CLR member resolver. Semantic fixed and named argument decoding remains the next
layer.

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
argument removed fails at its exact fixed-argument index and retains expected versus actual
generic arity. Primitive generic arguments are resolved to the selected graph's exact
`System.Int32` TypeDef, so the signature and serialized-name paths agree on semantic identity
without depending on the compiler host runtime.

Generic attribute constructors whose MemberRef parent is a closed TypeSpec remain the existing
structured temporary boundary. The resolved-signature layer can represent their types, but
constructor resolution must first retain the constructed owner view rather than silently reducing
it to an open TypeDef. That follow-up can reuse this foundation; `OPEN_GENERIC_ATTRIBUTE_TYPE` is a
defensive validator result, not the final generic-attribute design.

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
- Exact custom-attribute constructor and `System.Attribute` hierarchy resolution:
  **Correct direction**.
- Rejecting TypeSpec-owned attribute constructors until constructed-member substitution exists:
  **Correct temporary implementation, but not a final design**.
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
- Rejecting closed generic attribute constructors until constructor resolution retains their
  constructed TypeSpec owner: **Correct temporary implementation, but not a final design**.
- Separate lazy FIR import policy/provider: **Correct direction**.
- Reusing embedded KLIB for Kotlin-produced DLLs: **Correct direction**.
- Mapping raw CLR rows directly to Kotlin IR: **Architecturally wrong and should be changed**.
- Kotlin-facing custom-attribute/annotation projection, property synthesis, events, resolved
  constraint semantics, nullability enhancement, FIR symbols, and backend calls:
  **Deferred problems that must be recorded before the importer surface becomes stable**.

The cost is a substantial target-owned metadata and import layer. The alternative is greater:
spreading partial CLR decoding and C# assumptions across resolution, IR lowering, codegen, and
tooling would create untestable semantic drift and ABI debt.
