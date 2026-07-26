# ADR: DLL manifest and Roslyn tooling for C# interface implementations

- Status: **Accepted direction; manifest schema remains experimental**
- Scope: C# source implementations of Kotlin-owned interfaces on `net48`,
  `netstandard2.0`, and `net10.0`

## Context

A Kotlin-owned generic interface may have a non-generic canonical identity, a
declaration-variant CLR view, an invariant exact view, generated intersection slots, and
profile-dependent default-body placement. Those physical types are one Kotlin declaration and
one logical contract. A handwritten C# implementation must currently discover every physical
slot and keep its typed and erased behavior coordinated.

The sibling KLIB contains Kotlin logical metadata, but ordinary C# builds and Roslyn source
generators consume DLL references. Requiring a KLIB-aware MSBuild integration merely to implement
an interface would make the C# path nonstandard and would couple source generation to an internal
compiler artifact. Copying Kotlin default bodies into generated C# would create independent
semantic bodies and violate the interface-default ABI.

The tooling also has a hard scope boundary. A Roslyn generator can add another declaration of a
user-authored partial C# type. It cannot retrofit a precompiled CLR type, a type emitted by another
.NET language, or a non-partial C# declaration.

## Decision

### 1. The DLL owns a versioned implementation manifest

Every compiler-produced Kotlin library DLL carries a versioned C# implementation manifest. The
manifest and the ordinary CLR metadata in that same DLL are sufficient for tooling; the sibling
KLIB is not an input to the supported C# source-authoring path.

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
not form a second Kotlin type system.

Schema 6 names `kotlin-public-id-signature-legacy-v1` as its logical-identity scheme. Interface
and member records use the same public `IdSignature` rendered by
`PublicIdSignatureComputer(DotNetIrMangler)` for the KLIB/DLL physical index. A manifest must not
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

### 2. Roslyn partial-type generation is the supported C# source-authoring path

The intended tooling is a Roslyn source generator paired with an analyzer. A user supplies a
partial C# type and the strongly typed source members. Generated partial declarations add the
required physical interface views and adapters. The analyzer reports incomplete, ambiguous, or
inconsistent source bodies before ordinary CLR load or dispatch.

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

Schema 6 records that guidance as normalized pairs of method-type-parameter and
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

The helper type and method are marked compiler ABI and deliberately nameable from generated C#
source. Tools consume their recorded physical identity; they do not derive a helper name from the
interface.

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

The final DLL representation should use a named managed manifest resource plus a small
discoverability marker. This avoids custom-attribute size concerns and gives metadata-only tools
a conventional opaque payload.

The current ILAsm pipeline cannot place arbitrary bytes in a DLL's managed-resource section.
ILAsm's `.mresource` grammar can only point at a linked external file, which is not self-contained.
The initial prototype therefore uses hashed, indexed `AssemblyMetadataAttribute` chunks. This is
a correct temporary carrier, not the final ABI. A capable PE writer or post-assembly metadata
stage must move the unchanged record payload into a true managed resource before the schema or
packaging format is frozen.

The compiler must not add an external sidecar, load the target assembly to discover the manifest,
or make the runtime responsible for per-library implementation metadata.

### 6. Inputs are treated as untrusted metadata

The generator and analyzer parse without executing target code. They impose explicit payload,
record-count, nesting, and string-size limits; resolve every locator against the referenced
assembly; reject duplicates and unknown required records; and produce deterministic diagnostics.
They never use reflection-based assembly loading as their production metadata reader.

Reflection is acceptable in execution tests that prove the payload is physically present in the
DLL. Production tooling uses Roslyn/ECMA-335 metadata APIs.

## Alternatives rejected

- **Read the sibling KLIB from MSBuild.** This makes ordinary C# authoring depend on Kotlin
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

The initial prototype covers direct canonical/declared/exact views, read-only and mutable
properties, a generic method, an exact-only unsafe input, a portable helper default, and the
corresponding `net10.0` DIM. It removes the sibling KLIB before extracting the actual DLL metadata,
generates a partial C# implementation, compiles it with Roslyn, and executes Kotlin-authored
verification through typed and widened views for `net48`, `netstandard2.0`, and `net10.0`.
Schema 6 composes Kotlin parents from their own manifest contracts and the ordinary CLR interface
graph, whether they are in the same DLL or referenced compiler-produced Kotlin library DLLs. This
covers a two-branch generic diamond with a shared root default, a parent-owned mutable property,
and a sibling-owned property through a child exact view. Logical root keys deduplicate the diamond;
the manifest does not duplicate local or assembly-qualified physical TypeSpecs.

An unrelated same-named parent intersection is different: CLR metadata exposes the bodyless
derived slot but not the fact that Kotlin selected it to unify several logical declarations.
Schema 6 therefore records the sorted contributor logical keys and the derived declared/exact
MethodDef locators. Generated C# keeps one source body, explicitly adapts the derived slot, and
maps the parent canonical identities to that same body. For a variant mutable-property
intersection, the declared record contains the variance-safe getter while the exact records
contain the complete getter/setter property; getter-selected Property rows keep both exact
accessors associated with one CLR property. The cross-assembly test deletes both KLIBs before
reading the DLL contracts. It also compiles a
`netstandard2.0` parent into a `net10.0` child, reads the child promotion directly from ECMA-335
`MethodImpl` metadata, omits generated class forwarders only after every parent slot has a concrete
child mapping, and executes the inherited default. Non-generic parents remain explicitly
supported by schema 3. Their one CLR interface is both the Kotlin canonical identity and the normal
C# authoring view, so they have no artificial declared or exact owner. Their members use a
distinct canonical locator rather than mislabelling the slot as erased. The no-KLIB fixture
implements an ordinary inherited property, mutable property, method, and profile-aware default
through an idiomatic PascalCase partial C# surface. Generated explicit members map that surface to
the Kotlin physical Property and MethodDef names; no property state or default body is duplicated.
The same fixture proves helper forwarding on both portable profiles, natural DIM inheritance on
`net10.0`, and child-owned DIM promotion when the selected parent DLL is portable.
The fixture also resolves a method-generic interface constraint from the actual CLR GenericParam
metadata and emits the matching C# `where` clause without a manifest constraint record.
Friend-accessible internal interfaces now receive the same records as public contracts when their
complete containing-type chain is public or ordinary internal. The no-KLIB fixture authorizes one
C# output identity through producer-emitted `InternalsVisibleTo`, implements top-level and nested
internal interfaces, and executes an internal Kotlin verifier on every profile. The same source
reference compiled under an unauthorized assembly identity fails with Roslyn `CS0122`. Private
nested interfaces and `@PublishedApi internal` compiler-only interfaces are deliberately absent
from the authoring manifest.

Schema 6 also records special-barrier policy directly from Kotlin's shared
`SpecialBridgeMethods` identity table. A no-KLIB child contract overriding
`Collection.contains` records one checked argument with a `false` fallback, while a child
overriding `List.indexOf` records `-1`. An ordinary user unsafe input records no policy. The
runtime DLL now supplies the complete inherited contracts. The no-KLIB fixture reads those
contracts, generates partial C# implementations, and executes both children through exact and
widened Kotlin views. Wrong-shaped values therefore return `false` or `-1` without changing the
typed C# body. The same built-in-derived manifest payload is emitted and compared on `net48`,
`netstandard2.0`, and `net10.0`.
