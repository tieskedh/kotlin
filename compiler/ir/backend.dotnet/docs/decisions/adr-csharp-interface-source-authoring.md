# ADR: DLL manifest and Roslyn tooling for C# interface implementations

- Status: **Accepted for non-generic interfaces; generic split-surface design superseded**
- Scope: C# source implementations of non-generic Kotlin-owned interfaces on
  `net48`, `netstandard2.0`, and `net10.0`; historical evidence for the removed
  implicit generic authoring prototype

The generic canonical/declared/exact design recorded below is no longer
normative. [Erased generic-interface identity](generic-interface-erased-identity.md)
removed those typed siblings, and
[explicit C# export](draft-adr-explicit-csharp-export-surface.md) owns any future
typed generic authoring surface. The manifest and analyzer remain applicable to
non-generic Kotlin interfaces only. Historical generic sections are retained as
design evidence until the explicit export programme selects its own contracts;
they must not be used to reconstruct or extend the removed split ABI.

That accepted production scope is unchanged. The production-inert
[CLR-generic interface reopening](draft-adr-reified-generic-interface-owner.md)
reuses the dormant generic manifest reader and Roslyn generation mechanism for
one bounded foreign-source bridge proof. It does not restore the historical
canonical/declared/exact ABI or authorize generic interface authoring in
production.

## Context

A non-generic Kotlin-owned interface has one physical CLR identity but may still
need producer-recorded member identity, default-body ownership, and special
bridge policy for safe C# implementation. A Kotlin-owned generic interface has
one erased runtime identity and is deliberately absent from this implicit
source-authoring product; C# receives a typed generic contract only through a
future explicit export.

The private `Kotlin.Metadata` resource contains Kotlin logical metadata, but ordinary C# builds and
Roslyn source generators should consume the deliberately exported authoring contract rather than
the compiler's full KLIB model. Requiring KLIB-aware MSBuild integration merely to implement an
interface would make the C# path nonstandard and would couple source generation to an internal
compiler encoding. Copying Kotlin default bodies into generated C# would create independent
semantic bodies and violate the interface-default ABI.

The tooling also has a hard scope boundary. A Roslyn generator can add another declaration of a
user-authored partial C# type. It cannot retrofit a precompiled CLR type, a type emitted by another
.NET language, or a non-partial C# declaration.

## Decision

### 1. The DLL owns a versioned implementation manifest

Every compiler-produced Kotlin library DLL carries a versioned C# implementation manifest. The
manifest and the ordinary CLR metadata in that same DLL are sufficient for tooling; the private
Kotlin metadata resource is not an input to the supported C# source-authoring path.

“Self-contained” applies to the DLL contract, not to unnecessary duplication. Method signatures,
generic constraints, visibility, and Property rows remain authoritative CLR metadata. The
manifest supplies the information CLR metadata cannot express:

- the one logical Kotlin interface and member identities;
- canonical, declared-variance, and exact physical owners;
- declaration type-parameter names and Kotlin variance;
- the normal strongly typed authoring view of every member;
- each erased, declared, exact, property-accessor, and helper MethodDef locator;
- declaration-specific wrong-shaped erased-argument behavior, including the checked argument count
  and `false`, `null`, `-1`, or argument fallback selected by Kotlin's shared special-bridge table;
- each derived intersection slot and the sorted logical members it unifies;
- whether the member is abstract, helper-backed on a portable profile, or DIM-backed on
  `net10.0`; and
- which typed view owns the one semantic DIM body.

Physical signatures are repeated only as stable MethodDef locators and integrity checks. They do
not form a second Kotlin type system. Tooling resolves a locator against the open declaring
TypeDef before applying any consumer-owned generic substitution. Owner, method name, generic
arity, return type, and every parameter type must identify exactly one MethodDef. An absent,
ambiguous, or signature-inconsistent locator is a malformed-manifest diagnostic rather than
permission to select a member by name and parameter count. IL identifier quoting is normalized,
and an explicit reference to the MethodDef's own assembly is equivalent to the local form;
external assembly qualifiers remain identity-significant. The one exception is the standard CLR
core-facade set (`mscorlib`, `netstandard`, `System.Runtime`, and `System.Private.CoreLib`) for
`System.*` types, which Roslyn may unify through type forwarding across profiles.

Schema 7 names `kotlin-public-id-signature-legacy-v1` as its logical-identity scheme. Interface
and member records use the same public `IdSignature` rendered by
`PublicIdSignatureComputer(DotNetIrMangler)` for the DLL's physical index. A manifest must not
introduce a runtime-, Roslyn-, or tooling-owned declaration identity. The `X:` key of a derived
intersection record is a deterministic identity for that physical composition record, not a
second identity for any contributing Kotlin declaration.

Hand-written runtime IL follows the same rule. `Kotlin.Runtime.dll` resolves its supported
`Iterator`, `ListIterator`, `Iterable`, `Collection`, and `List` declarations and members from the
actual common `IrBuiltIns`, computes their ordinary `C:` and `F:` keys through
`DotNetIrMangler`, and then attaches a runtime-owned registry of CLR owners, slots, and Property
rows. That registry owns only the physical projection. In particular, CLR `Size` remains distinct
from Kotlin source `size` without acquiring a second logical declaration identity.

The schema is a compiler ABI. Readers reject unknown versions, duplicate records, missing chunks,
unknown logical-identity schemes, non-Kotlin declaration keys, inconsistent profile/body
combinations, and corrupt payloads. No compatibility is promised for the current unshipped
schema; an incompatible change increments the schema and stale artifacts fail explicitly.

Ordinary `internal` interfaces use the same Kotlin declaration-identity machinery. Their manifest
records do not make them public and do not copy friend authorization: CLR TypeDef accessibility
and the producer's ordinary `InternalsVisibleTo` attributes remain authoritative. Tooling may
generate an implementation only when Roslyn reports the interface and every containing type as
accessible from the current compilation. Private/protected owner chains and
`@PublishedApi internal` compiler-ABI interfaces are not source-authoring contracts.

The DLL-only matrix verifies this boundary from raw ECMA-335 metadata, not only from C# access
results. An ordinary top-level internal interface and its internal containing class are non-public
TypeDefs; a public nested contract remains `NestedPublic` inside that internal owner; a private
nested interface remains `NestedPrivate`; and an `@PublishedApi internal` compiler-ABI interface
is physically public but absent from the authoring manifest. The producer's
`InternalsVisibleTo("GeneratedShape")` custom-attribute value is compared byte-for-byte, including
its serialized-string length and terminator, on every target profile.
The physically public `@PublishedApi internal` TypeDef also carries the exact
`KotlinCompilerAbiAttribute` (`01 00 00 00`) and `EditorBrowsable(Never)` blobs, while the ordinary
internal friend interface carries no compiler-ABI marker. Thus the manifest omission is backed by
a mechanically distinct CLR compiler-ABI surface rather than by naming convention.

Nested manifest owner paths remain structured. Dotted rendering is used only to match the C#
source spelling; Roslyn metadata lookup joins the top-level TypeDef to nested components with the
CLR `+` separator. Accessibility is then decided by walking the complete owner chain: public
components pass, internal components require same-assembly access or producer friendship, and
private/protected components fail. A broad symbol-accessibility shortcut must not override a valid
friend grant on an internal containing type.

The production lane covers both a non-generic nested friend interface and a nested covariant
generic friend interface. The latter retains its canonical and declared/exact physical views as
`NestedPublic` TypeDefs inside the internal owner; an authorized C# base list names the normal
declared view and the generator supplies the remaining adapters. Kotlin execution observes the
same typed property body through that contract on every profile.

### 2. Roslyn partial-type generation is the supported C# source-authoring path

The intended tooling is a Roslyn source generator paired with an analyzer. Its artifact targets
`netstandard2.0` as a tooling-host contract; this is independent of whether a referenced Kotlin
library targets `net48`, `netstandard2.0`, or `net10.0`.

A user opts in by writing the real semantic CLR relationship in the ordinary C# base list:

```csharp
public partial class MyShape<T> : Shape<T>
{
    // strongly typed C# source bodies
}
```

For a split generic Kotlin interface, that source name is the normal declared Kotlin view. For a
non-generic interface it is the one canonical CLR interface. A generator attribute, marker
interface, generated base class, or tooling-only identity is not an alternative opt-in: each would
either lose arbitrary generic substitution, consume C#'s single class base, or create an identity
which does not participate in ordinary C# type checking.

The base list may retain an ordinary C# class base and may name multiple unrelated Kotlin
interface roots. Generation adds only interface views and explicit members in another partial
declaration; it never repeats or replaces the class base. The DLL-only matrix executes one class
whose existing base constructor and state remain intact while independent ordinary and generic
Kotlin contracts both dispatch through their generated adapters.

#### Base-list substitutions are validated recursively

The other Kotlin targets translate the complete foreign-facing type tree rather than validating
only its outer classifier:

- JVM type mapping recursively maps array elements, generic arguments, and nested owners under the
  applicable `TypeMappingMode`;
- JS TypeScript export recursively translates `Array` elements, function components, and class
  type arguments. JS `dynamic` maps to its JS export model because dynamic typing is a deliberate
  JS language facility, not a general Kotlin interop rule;
- Wasm export likewise recurses through function and external-class type arguments and produces an
  error/unknown export type for a shape outside that boundary; and
- Native Swift export recursively translates function components and supported nominal types,
  propagating `SirUnsupportedType` when a nested shape cannot be represented.

The CLR-specific difference is its richer constructed type metadata. A closed generic argument
may itself contain named generic constructions, nullable value types, type parameters, and
single-dimensional zero-based arrays. Those are stable CLR TypeSpecs and may be preserved
structurally. C# nullable-reference annotations are flow-contract metadata rather than a distinct
CLR type identity; the generator does not turn them into Kotlin nullability declarations.

Other CLR shapes do not have an admitted Kotlin Common interpretation:

- C# `dynamic` is physically `System.Object` plus C#-specific dynamic metadata, not Kotlin
  `Any?`;
- pointer and function-pointer types are outside the Kotlin-owned interface ABI;
- unresolved or open-unbound constructions have no stable substitution; and
- a CLR rectangular or non-vector array is not Kotlin `Array<Array<...>>` and must not be
  flattened into one.

The Kotlin invariant is that a legal nested substitution keeps its exact structure through every
canonical/declared/exact adapter. In particular, a vector such as `Nullable<int>[]` is a natural
generic `Array<Int?>` carrier; it is not a specialized primitive-array wrapper and is not erased
to `object[]`. An unsupported leaf rejects the complete authoring substitution with `KDNCS004`;
the generator never repairs it by widening that leaf to `object`.

The selected Kotlin-design-aligned target rule is therefore to recurse through the complete Roslyn type symbol,
admit named constructions and CLR SZARRAY vectors whose children are all representable, and reject
the first unsupported nested shape with a positional diagnostic. This matches the other backends'
structural translation rule while making the strong CLR-specific decision not to invent Kotlin
meanings for `dynamic`, pointers, or rectangular arrays.

Rejecting rectangular arrays and pointer-bearing substitutions is a **Correct temporary
implementation, but not a final importer design**. A future CLR importer may expose deliberate
platform-specific Kotlin types for them. Source authoring may admit those shapes only after that
logical type and its adapter/identity rules exist; it must never reach support by pretending they
are Kotlin `Array` or `Any?`.

#### Inherited source obligations are unified after consumer substitution

**1. Other-target rule.** The mature backends start from Kotlin's override graph and then compare
the target signatures after generic substitution. JVM bridge lowering may let one implementation
and its generated bridges satisfy several substituted JVM methods. JS bridge construction derives
the required substituted bridge signatures and delegates them to the selected implementation.
Native and Wasm likewise retain the distinct overridden declaration identities while their
vtable/interface-table entries may select one implementation. None asks the foreign author to
provide two semantic bodies merely because two inherited declarations have distinct owners.

**2. CLR-specific difference.** CLR interface slots remain distinct by declaring TypeDef and
MethodDef even when closing a generic parent makes their method signatures equal. A single class
method can nevertheless implement several inherited interface slots with the same closed
signature; C# performs that natural mapping without requiring duplicate declarations. Where the
physical signatures differ, generated explicit implementations and `MethodImpl` mappings remain
available. This rule is the same on `net48`, `netstandard2.0`, and `net10.0`; DIM placement may
change the selected default body on `net10.0`, but does not change how an authored abstract body
satisfies coincident closed slots.

**3. Kotlin Common invariant.** If Kotlin override resolution permits one declaration to implement
both inherited members after a legal substitution, calls through either parent view must reach
that one body. The parents' logical `IdSignature`s must remain distinct for metadata, qualified
selection, and future evolution. Signature coincidence is an implementation relationship, not
permission to merge declarations.

**4. .NET validity rule.** Tooling may reuse one source method only when Roslyn reports an
identical effective name, generic arity, parameter/ref-kind shape, compatible generic
constraints, and one return contract after the complete consumer substitution. Return-only
overloads, incompatible constraints, conflicting property shapes, and competing selected
defaults are not legal coalescing cases. They require an already recorded Kotlin intersection or
override decision, distinct generated adapters where representable, or a source-authoring
diagnostic.

**5. Selected architecture.** The manifest continues to expose every contributing Kotlin member
under its existing logical identity. After substitution, the generator may resolve several
coincident obligations to the same user-authored body and emits each required canonical,
declared/exact, or parent-owned physical adapter to that body. It never invents a combined logical
member and never copies the body.

**6. Kotlin-aligned target choice.** Accept a closed inherited family when Kotlin permits one effective
override and the CLR can represent all of its slots from one body; otherwise reject it before C#
emission with a precise diagnostic. Blanket rejection would transgress Kotlin Common, while
blind name-based merging would discard Kotlin identity and override semantics. This is
**Correct direction** and must be covered by DLL-only execution through every contributing parent
view.

Generated partial declarations add the canonical and exact physical views, explicit adapters,
barriers, and profile-specific forwarding required by the manifest. Those generated physical
interfaces are compiler ABI, not interfaces the C# author is expected to name in a base list or
implement explicitly. The analyzer reports incomplete, ambiguous, inaccessible, or inconsistent
source bodies before ordinary CLR load or dispatch. At minimum it diagnoses:

- a Kotlin implementor which is not `partial`;
- a nested implementor whose containing source type is not `partial`;
- a contract which the current assembly cannot access through ordinary CLR visibility and
  producer-emitted friendship;
- a user member which conflicts with a member the compiler ABI requires the generator to emit;
- a generic substitution the generator cannot represent;
- a manifest/generator schema or logical-identity version mismatch; and
- a malformed or unsupported manifest without executing code from the referenced assembly.

The paired artifact has one diagnostic-ownership rule. For a semantically analyzable C# type, the
analyzer alone reports `KDNCS` diagnostics and the generator only emits or skips source. If an
existing C# compiler error inside that same type would suppress analyzer output—most importantly
an inaccessible Kotlin base or a missing inherited interface member—the generator reports the
Kotlin diagnostic while skipping emission, and the analyzer suppresses its duplicate. Compiler
errors in unrelated types do not transfer ownership. Manifest-wide version/format failures remain
analyzer-owned. Tests require one occurrence, not merely the presence, of representative
analyzer-owned and generator-owned diagnostics.

A nested reference-class or record-class implementor uses the same contract. The generator
reconstructs partial declarations for its containing source-type chain, preserving type kind,
generic parameters, interface variance, and required `static`, `readonly`, or `ref` shape.
Constraints remain owned by the user declaration and need not be copied onto a generated partial
part. Every containing type must itself be partial because generation occurs in another syntax
tree. File-local types cannot cross that syntax-tree boundary and are rejected.
Each generated syntax tree receives a SHA-256 hint derived from the fully qualified C# type
display so punctuation and nested-name variants cannot collide. That hint is only a Roslyn
generated-source filename; it is not persisted in the DLL and is not a Kotlin or tooling
declaration identity.

C# struct and record-struct implementors are deliberately deferred. The supported Roslyn
source-authoring path currently covers reference classes and record classes only, and reports
`KDNCS010` for a value-type implementor. It must neither generate a reference wrapper nor
reinterpret the source type as a class: either choice changes the identity and copy semantics the
C# author selected.

This is not only a generator limitation. A portable helper forwarder converts `this` to an
interface receiver and therefore boxes a struct, while a `net10.0` DIM or constrained call can
execute against the unboxed receiver. Adopting those physical mechanisms without a Kotlin
interop contract could make mutation visibility, interface-box identity, and even default
dispatch profile-dependent.

Value-type source authoring may be enabled only by a separate accepted interop decision and a
cross-profile test matrix covering interface-box identity, copies and mutation, default values,
qualified and ordinary default dispatch, equality, generic constraints, nullable value types,
and calls through canonical, declared, and exact views. Until then `KDNCS010` is a deliberate ABI
boundary, not a temporary request to synthesize a wrapper. Manually authored CLR structs are not
claimed as part of this supported C# authoring path.

The exact typed view is the normal complete C# surface when it exists. Declared-variance and
canonical slots adapt to that typed behavior, boxing, widening, or narrowing only when their own
physical ABI requires it. No generated adapter may introduce independent Kotlin behavior.

Properties remain real CLR Property rows. The user-authored C# partial owns one typed property
body and its storage; generated explicit properties adapt canonical, declared, exact, and
intersection views to that body. A variant declared view exposes only the accessor legal under CLR
variance, while the exact view exposes the complete Kotlin `var`. Kotlin ABI names remain stable.
The generator may accept an idiomatic PascalCase C# source property and bind it explicitly to the
recorded Kotlin/CLR property names; optional consumer-facing aliases belong to an explicit C#
export facade. C#-specific `init`, `required`, indexer, and event semantics are not inferred from a
Kotlin property.

Representable method constraints are read from the located CLR GenericParam and
GenericParamConstraint rows. The manifest does not copy them into a second type model. A
Kotlin owner-relative constraint deliberately omitted because it is illegal on a CLR variant view
must not be reconstructed as a C# constraint; any additional analyzer guidance is Kotlin tooling
metadata, not executable CLR signature metadata.

Schema 7 records that guidance as normalized pairs of method-type-parameter and
interface-owner-type-parameter indices on direct members and derived intersection slots. This is
only the logical fact needed to explain the weakened boundary and generate an appropriate runtime
adapter. It is not a CLR `where` clause and does not duplicate arbitrary Kotlin types or
representable constraints. Readers reject out-of-range, duplicate, or unordered pairs.

Wrong-shape behavior is likewise semantic metadata, not a C# naming convention. Generated
canonical adapters use the recorded policy before narrowing to the typed source body. An ordinary
user `@UnsafeVariance` member has no policy and retains normal cast or unbox failure. Tooling must
not generalize the collection policy from an annotation, source spelling, or a return type.

The manifest prototype is implemented before the generator so its sufficiency can be tested
without freezing a generator around inferred names or KLIB access.

### 3. Default bodies remain profile-aware and single-owned

For `net48` and `netstandard2.0`, generated C# implementations forward inherited Kotlin defaults
to the producer-recorded `__KotlinDefaultImpls` helper. They do not copy or translate the Kotlin
body.

For `net10.0`, generated C# types inherit an effective DIM and omit redundant methods. The
retained helper remains recorded for qualified-super compatibility and exact body selection, but
ordinary generated calls and implementations preserve virtual dispatch.

An inherited portable declaration remains recorded as helper-backed in its own DLL. When a
`net10.0` child promotes that declaration into a selected DIM, the child DLL's ordinary CLR
interface graph and `MethodImpl` rows are authoritative for the promotion. Tooling resolves the
parent manifest's MethodDef locators, then requires a complete concrete mapping from the child's
physical interface views before treating the DIM as effective. The manifest does not duplicate
those physical mappings, and tooling never infers promotion merely from the consumer profile.
Missing, incomplete, or ambiguous mappings are diagnostics rather than a reason to guess or emit
an overriding helper forwarder.

Schema 7 also records each declaration's sorted Kotlin logical override keys. These are ordinary
`DotNetIrMangler` member identities, not a tooling namespace. They preserve the Kotlin-selected
member when several inherited defaults share a CLR signature but portable metadata contains only
abstract slots and independent helpers. Generated adapters for every inherited slot invoke the
most-derived selected helper. On `net10.0`, ordinary method defaults remain method-free when the
CLR and Roslyn both recognize the selected DIM. A property conflict is different: the child
interface's MethodImpl rows select the getter DIM for CLR dispatch, but Roslyn's C# base-list
validation still requires the inherited parent Property obligations to be satisfied on the
implementing class. The generator therefore emits explicit parent Property adapters which
dispatch virtually through the child property's selected DIM. They do not call the compatibility
helper or copy the body. A child-selected body is never reconstructed by calling each parent's
helper independently.

Getter and setter declarations remain independently authoritative even though CLR metadata groups
their MethodDefs under one Property row. Kotlin may select one qualified-super getter and a
different qualified-super setter. The manifest records separate logical override edges and helper
locators for both accessors; generated explicit properties batch the syntax only after resolving
each accessor's semantic member independently. Tooling must never select one parent as the owner
of the whole mutable property.

For a covariant generic property, the strongly typed declared view owns the canonical DIM body.
The erased canonical interface remains an abstract CLR Property slot reached by an
interface-owned MethodImpl adapter. CLR dispatch accepts that mapping, but Roslyn does not count
the mapped accessor as satisfying the canonical Property obligation on a C# implementing class.
The generator therefore emits the physically required explicit canonical Property adapter and
dispatches virtually through the typed DIM. Any exact typed view follows the same body without an
erased-result cast. This is a CLR/C# representation bridge, not a second Kotlin implementation.

For a covariant mutable default property, the variance-safe getter body lives on the declared
typed view and the setter body lives on the exact typed view. Other typed view accessors already
contain DIM adapters and must be inherited; generating a class adapter back to the same typed
Property would recurse. Only the abstract erased canonical Property and Kotlin-selected inherited
slots receive class adapters. On portable profiles an erased setter converts its `object` value to
the helper's constructed value parameter before calling the selected helper. On `net10.0` it
performs the same boundary conversion before virtual dispatch to the exact setter DIM. An ordinary
`@UnsafeVariance` wrong-shape value therefore fails with the normal cast exception before entering
the typed body.

Tooling cross-checks every consumed edge against the CLR interface ancestry and the resolved
authoring signatures, including source name, member kind, generic arity, parameters, and
covariant-compatible result. A syntactically valid logical key cannot redirect an unrelated CLR
slot; stale or tampered edges fail as `KDNCS006`.

If a C# class combines unrelated authored Kotlin roots whose competing defaults have no
Kotlin-owned resolving declaration, the generator requires one C# source body. It does not invent
a parent preference. This is the foreign-source equivalent of Kotlin requiring an explicit
override for an unresolved default conflict.

Promotion matching uses the complete raw ECMA-335 MethodImpl declaration signature: declaring
assembly and owner, method name, generic arity, return type, and every parameter type. A row with a
coincident name and arity but a different signature is not evidence of an effective DIM. The
metadata-only test reader decodes those signatures without loading the producer and rejects a
deliberately corrupted return type.

The helper type and method are marked compiler ABI and deliberately nameable from generated C#
source. Ordinary function helpers retain their CLR method name. Property-accessor helpers use the
physical-name-grammar-3 form
`get_<property>__KotlinDefault__<logical-identity-digest>` or
`set_<property>__KotlinDefault__<logical-identity-digest>`. This changes only the compiler helper:
the public interface retains a normal CLR Property row and `get_`/`set_` accessors. The reserved
suffix makes the helper C#-expressible and collision-resistant without creating a tooling-only
identity; its digest comes from the existing `DotNetIrMangler` declaration identity. Tools consume
the recorded physical locator and never derive the helper name from the interface.

### 4. This is not the universal CLR implementation mechanism

The manifest describes the Kotlin ABI for all consumers, but the Roslyn generator is only the
supported C# source-authoring convenience. It requires a user-owned partial C# type. It does not
cover:

- precompiled C# implementors;
- non-partial C# types;
- F#, Visual Basic, IL, or other CLR producers; or
- arbitrary foreign types that happen to have matching methods.

Those producers may implement the public physical CLR contract manually or use a future
language-neutral adapter/export tool. Kotlin runtime identity and dispatch do not depend on the
Roslyn generator.

### 5. The schema is independent of its physical carrier

The DLL representation uses the public managed-resource name
`Kotlin.CSharpImplementationManifest`. The ManifestResource row itself is the discoverability
marker. Its embedded bytes contain an eight-byte magic (`KDNCSM01`), the little-endian schema and
payload length, the raw SHA-256 payload digest, and the bounded UTF-8 record payload. This avoids
custom-attribute size concerns and gives metadata-only tools a conventional opaque payload while
leaving the logical record schema carrier-independent.

Both the selected Framework 4.8 and modern .NET 10 ILAsm embed a same-directory `.mresource`
source file into the resulting PE. The compiler-owned assembler therefore stages the IL and
payload in an isolated temporary directory and deletes the staged files after assembly. The
resulting DLL is self-contained. `AssemblyMetadataAttribute` chunks have no compatibility
standing.

The compiler must not add an external sidecar, load the target assembly to discover the manifest,
or make the runtime responsible for per-library implementation metadata.

### 6. Inputs are treated as untrusted metadata

The generator and analyzer parse without executing target code. They impose explicit payload,
record-count, nesting, and string-size limits; resolve every locator against the referenced
assembly; reject duplicates and unknown required records; and produce deterministic diagnostics.
They never use reflection-based assembly loading as their production metadata reader.

Reflection is acceptable in execution tests that prove the payload is physically present in the
DLL. Production tooling opens the referenced PE, reads its ECMA-335 ManifestResource row and CLR
resource section, and never uses `Assembly.Load`. Roslyn does not expose managed-resource bytes
through its symbol model, so this DLL-only reader is the narrowly scoped file-access exception to
the normal analyzer rule against arbitrary file I/O.

## Alternatives rejected

- **Read the private KLIB payload from MSBuild.** This makes ordinary C# authoring depend on Kotlin
  packaging and still leaves DLL-only references incomplete.
- **Infer view and helper names.** Physical names are compiler ABI and may be disambiguated;
  inference creates version and collision debt.
- **Copy default bodies into generated C#.** This creates multiple semantic bodies and breaks
  exact qualified-super and profile compatibility.
- **Require a generated base class.** C# has single class inheritance, so a base class cannot be
  the only supported implementation path.
- **Use runtime reflection or dispatch proxies as the normal path.** That changes object identity,
  moves compile-time obligations to runtime, and does not create ordinary CLR MethodImpl mappings.
- **Call the Roslyn path universal CLR interop.** Source generators cannot modify already emitted
  or non-C# types.

## Consequences

The design gives C# a normal strongly typed source surface while keeping Kotlin's canonical
identity, variance, default-body ownership, and cross-profile semantics authoritative. The DLL is
independently consumable by standard .NET build tooling, and generator evolution is schema-gated.

The cost is a new versioned compiler ABI surface, profile-specific generation, a generator and
analyzer product that must be shipped and versioned with the compiler, and eventual PE-writer work
for the final managed-resource carrier. Manual and non-C# implementors remain possible but do not
receive the source-authoring convenience automatically.

## Required validation before schema freeze

Tests must read only the produced DLL and then compile and execute generated C# implementations
covering:

- canonical, declared, and exact views;
- read-only and mutable properties;
- generic methods and representable constraints;
- ordinary and special-barrier unsafe inputs;
- portable helper-owned defaults and `net10.0` DIM inheritance;
- inherited interfaces, intersections, reabstraction, and conflict resolution;
- public, nested, and friend-accessible internal contracts;
- separate assemblies and multi-targeted portable/modern assets;
- nullability, value types, boxing, and reference identity;
- malformed, oversized, duplicate, unknown-version, and tampered manifests; and
- compatibility across at least two compiler generations once an ABI freeze is proposed.
