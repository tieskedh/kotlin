# ADR: Self-describing DLL as the Kotlin/.NET library artifact

- Status: **Accepted**
- Date: 2026-07-27
- Scope: compiler-produced Kotlin libraries and standard-library variants for `net48`,
  `netstandard2.0`, and `net10.0`

## Context

The initial prototype published a sibling pair:

```text
Library.klib
Library.dll
```

The KLIB owned Kotlin declaration metadata and the physical declaration index, while the DLL
owned CLR metadata and executable IL. The KLIB hashed its sibling DLL so mismatched files failed
before linking.

That pair is safe inside one compiler-owned directory but is not a sound publication identity.
Maven and Gradle may cache two artifacts in unrelated locations, NuGet and MSBuild naturally
select DLL assets, and ordinary CLR tooling has no concept of a required sibling KLIB. Publishing
the two files independently would make atomic profile selection, friendship, signing, and version
skew unnecessarily fragile.

Nothing has shipped, so the artifact boundary may change without a compatibility mode.

## 1. Other Kotlin targets

Every mature target publishes one authoritative library container:

- JVM uses its native JAR/class-file format. Kotlin metadata is carried by class metadata and the
  module mapping inside that same artifact.
- JS, Wasm, and Native use one KLIB containing Kotlin metadata, serialized IR, and target
  components.
- Native may additionally export frameworks, static libraries, or shared libraries, but those
  foreign products do not create an independently published metadata identity for the same Kotlin
  dependency.

No mature target requires consumers to rediscover two independently cached files and then infer
that they form one Kotlin library.

## 2. .NET platform difference

The CLR DLL is the native reusable library artifact for all required profiles. Assembly identity,
references, visibility, custom attributes, resources, strong-name signatures, NuGet selection,
MSBuild, Roslyn, reflection, debuggers, and ordinary deployment all start from that DLL.

ECMA-335 permits arbitrary embedded managed resources on `net48`, `netstandard2.0`, and
`net10.0`. A managed resource is covered by the containing PE's content identity and future
strong-name signature. This gives Kotlin a standard place to carry metadata without inventing a
sidecar protocol or making .NET tooling treat KLIB as the runtime artifact.

Unlike a sibling KLIB, an embedded resource cannot record the final DLL's SHA-256: including the
hash inside the file being hashed is recursive. Containment is the binding. Future signing and
ordinary PE integrity cover both IL and resources together.

## 3. Kotlin Common invariant

The artifact change does not alter declaration identity or language semantics:

- `PublicIdSignatureComputer(DotNetIrMangler)` remains the logical declaration identity;
- the serialized Kotlin metadata remains the authority for Kotlin visibility, nullability,
  variance, extensions, expect/actual information, and future inline or serialized-IR bodies;
- the physical declaration index maps those logical declarations to CLR types, members, slots,
  properties, and `MethodImpl` records;
- target profile remains a physical compatibility attribute, not a change in Kotlin meaning; and
- the frontend serialization result and backend physical declaration index jointly produce the
  one embedded metadata resource for a compilation.

CLR metadata remains authoritative for execution and ordinary .NET tooling. Neither CLR names nor
the C# authoring manifest become a second Kotlin declaration model.

## 4. .NET validity

Every compiler-produced library DLL contains one private embedded managed resource named:

```text
Kotlin.Metadata
```

The first carrier format is a complete packed KLIB. Its manifest records:

```text
dotnet_metadata_container=managed-resource-klib-v1
dotnet_implementation_binding=self
```

It contains no implementation SHA because the containing DLL is the implementation. The packed
KLIB reuses Kotlin's existing versioning, metadata fragments, language-feature flags, and physical
ABI properties. It is not a second published archive.

The C# implementation manifest remains a separate public
`Kotlin.CSharpImplementationManifest` resource. Roslyn tooling needs only the deliberately
exported C# authoring contract and ordinary CLR metadata; it must not parse the full Kotlin KLIB.

`Kotlin.Metadata` is private CLR metadata rather than C# API. Private resource visibility does not
provide a security boundary; it communicates ownership and keeps the resource out of the public
assembly contract examined by ordinary consumers.

The exact internal resource encoding may later avoid a nested ZIP if measurements justify direct
component access. Such a change requires a container-format version bump, but does not change the
canonical DLL artifact or Kotlin declaration identity.

## 5. Alignment with compiler architecture

Serialization stays in the KLIB-based CLI pipeline before IR lowering. Physical CLR bindings stay
in the backend and are attached after emission has resolved the actual declaration index. A
target-owned packager combines those two inputs.

This follows the shared KLIB metadata machinery used by JS, Wasm, and Native while selecting the
CLR-native DLL container as JVM selects its native class-library container. A new .NET-only
metadata type system, declaration-key namespace, archive format, or .NET sidecar process is
rejected.

## 6. Kotlin-aligned target choice

Make the self-describing DLL the only physical Kotlin/.NET library artifact. Do not produce,
publish, install, resolve, or accept a standalone Kotlin/.NET KLIB.

The CLI compiler reads `Kotlin.Metadata` directly from a DLL, including profile-selected installed
stdlib variants. Gradle dependencies, compilation association, and friend paths all use that DLL.
The compiler rejects a standalone KLIB carrying the Kotlin/.NET ABI marker with a diagnostic that
requires the self-describing assembly instead.

### Dependency-loading boundary

The CLI compiler accepts a compiler-produced DLL directly on both the ordinary classpath and the
friend path. A bounded, JVM-hosted PE/ECMA-335 reader:

- locates the private embedded `Kotlin.Metadata` `ManifestResource` without loading target code or
  starting a .NET process;
- checks every PE, metadata-stream, table, heap, and resource range before use;
- passes the resource bytes to the shared metadata-KLIB loader, which validates canonical ZIP
  entry names, duplicates, required components, CRCs, and a bounded expansion budget;
- constructs an ordinary `KotlinLibrary` whose `path` is the containing DLL and whose metadata
  component serves the retained KLIB module header and package fragments directly from memory;
- reads the physical `Assembly` row and requires the embedded manifest's name, version, culture,
  and unsigned status to match the containing DLL;
- requires the embedded carrier and `self` binding, and rejects a recursive implementation hash.

FIR consumes the same `KotlinLibrary` and `KlibMetadataComponent` contracts as other KLIB targets.
CLR-specific code is limited to locating and authenticating the resource in PE metadata. The
reusable byte-array loader belongs to common KLIB infrastructure because packed metadata is not a
CLR concept. No temporary file, synthetic KLIB path, second declaration model, or .NET runtime
process participates in dependency loading.

All target profiles use this JVM-hosted loading path. `net48`, `netstandard2.0`, and `net10.0`
differ in emitted CLR capabilities, not in the meaning or deserialization of Kotlin declarations.

## Consequences

Gradle, Maven, NuGet, MSBuild, Roslyn, reflection, and deployment can converge on one profile-aware
DLL asset. Future signing covers executable and Kotlin metadata together. The compiler retains
the common Kotlin metadata model instead of reconstructing Kotlin semantics from CLR signatures.

The private packed-KLIB encoding remains replaceable behind a versioned container format; the
self-describing DLL and its Kotlin identity are the stable decision.
