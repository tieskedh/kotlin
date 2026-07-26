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
- whether the member is abstract, helper-backed on a portable profile, or DIM-backed on
  `net10.0`; and
- which typed view owns the one semantic DIM body.

Physical signatures are repeated only as stable MethodDef locators and integrity checks. They do
not form a second Kotlin type system.

The schema is a compiler ABI. Readers reject unknown versions, duplicate records, missing chunks,
inconsistent profile/body combinations, and corrupt payloads. No compatibility is promised for
the current unshipped schema; an incompatible change increments the schema and stale artifacts
fail explicitly.

### 2. Roslyn partial-type generation is the supported C# source-authoring path

The intended tooling is a Roslyn source generator paired with an analyzer. A user supplies a
partial C# type and the strongly typed source members. Generated partial declarations add the
required physical interface views and adapters. The analyzer reports incomplete, ambiguous, or
inconsistent source bodies before ordinary CLR load or dispatch.

The exact typed view is the normal complete C# surface when it exists. Declared-variance and
canonical slots adapt to that typed behavior, boxing, widening, or narrowing only when their own
physical ABI requires it. No generated adapter may introduce independent Kotlin behavior.

The manifest prototype is implemented before the generator so its sufficiency can be tested
without freezing a generator around inferred names or KLIB access.

### 3. Default bodies remain profile-aware and single-owned

For `net48` and `netstandard2.0`, generated C# implementations forward inherited Kotlin defaults
to the producer-recorded `__KotlinDefaultImpls` helper. They do not copy or translate the Kotlin
body.

For `net10.0`, generated C# types inherit an effective DIM and omit redundant methods. The
retained helper remains recorded for qualified-super compatibility and exact body selection, but
ordinary generated calls and implementations preserve virtual dispatch.

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
Inherited contracts are explicitly marked unsupported by the first schema slice; they must not be
silently generated.
Runtime-bootstrap interfaces and ordinary non-generic interfaces are not yet source-authoring
inputs. Friend-accessible internal interfaces are also omitted by the public-only first collector.
All must gain equivalent manifest records before the generator claims support for implementing
them.
