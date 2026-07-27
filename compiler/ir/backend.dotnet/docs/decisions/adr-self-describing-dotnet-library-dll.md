# ADR: Self-describing DLL as the Kotlin/.NET library artifact

- Status: **Accepted**
- Date: 2026-07-27
- Scope: compiler-produced Kotlin libraries and standard-library variants for `net48`,
  `netstandard2.0`, and `net10.0`

## Context

The prototype publishes a sibling pair:

```text
Library.klib
Library.dll
```

The KLIB owns Kotlin declaration metadata and the physical declaration index, while the DLL owns
CLR metadata and executable IL. The KLIB hashes its sibling DLL so mismatched files fail before
linking.

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
- the same frontend serialization result and physical declaration index produce every metadata
  carrier for one compilation.

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

## 6. Core-team choice

Make the self-describing DLL the only canonical published Kotlin/.NET library artifact.

During migration, continue writing the sibling KLIB so the existing compiler and Gradle resolver
can operate. It is explicitly transitional and records:

```text
dotnet_metadata_container=sibling-klib-v1
dotnet_implementation_binding=sibling-sha256-v1
dotnet_implementation_sha256=<DLL SHA-256>
```

The embedded and sibling carriers must contain byte-identical Kotlin metadata entries and the same
logical-to-physical declaration records. Their container/binding properties differ, and only the
sibling carries the external DLL hash. The CLI compiler reads `Kotlin.Metadata` directly from a
DLL, including profile-selected installed stdlib variants; Gradle dependency and friend wiring
become DLL-first next. The sibling KLIB is removed only after ordinary, friend, stdlib,
cross-profile, and cross-module tests no longer consume it.

### Migration state

The CLI compiler now accepts a compiler-produced DLL directly on both the ordinary classpath and
the friend path. A bounded, JVM-hosted PE/ECMA-335 reader:

- locates the private embedded `Kotlin.Metadata` `ManifestResource` without loading target code or
  starting a .NET process;
- checks every PE, metadata-stream, table, heap, and resource range before use;
- reads the physical `Assembly` row and requires the embedded manifest's name, version, culture,
  and unsigned status to match the containing DLL;
- requires the embedded carrier and `self` binding, and rejects a recursive implementation hash;
  and
- presents the packed payload to the unchanged shared KLIB deserializer through a
  compilation-scoped temporary file that is deleted with the root compiler disposable.

Temporary extraction is **Correct temporary implementation, but not a final design**. It isolates
the container transition while preserving one Kotlin deserializer. The final DLL-backed
Kotlin-library abstraction should expose the same KLIB components directly and may add bounded
random access or caching; it must not introduce another metadata model.

The legacy sibling KLIB remains accepted and hash-verified during migration. Installed-stdlib
discovery now selects only the best compatible profile DLL and rejects a legacy KLIB that has no
canonical DLL; focused tests install no sibling at all. Gradle variants still publish and select
the pair, so producer output must continue writing it until that path and its compatibility tests
are DLL-first.

Classifications:

- one self-describing DLL per profile: **Correct direction**;
- complete packed KLIB as the initial private managed-resource payload:
  **Correct temporary implementation, but not necessarily a final encoding**;
- reuse of Kotlin KLIB metadata and `DotNetIrMangler` identities: **Correct direction**;
- separate public C# authoring manifest: **Reasonable platform-specific divergence**;
- transitional sibling KLIB with a DLL hash:
  **Correct temporary implementation, but not a final design**;
- independently publishing or resolving sibling KLIB and DLL artifacts:
  **Architecturally wrong and should be changed**;
- embedding a self-hash or accepting two independently generated metadata carriers:
  **Architecturally wrong and should be changed**.

## Consequences

Gradle, Maven, NuGet, MSBuild, Roslyn, reflection, and deployment can converge on one profile-aware
DLL asset. Future signing covers executable and Kotlin metadata together. The compiler retains
the common Kotlin metadata model instead of reconstructing Kotlin semantics from CLR signatures.

The bounded ECMA-335 reader, CLI DLL-first path, and installed-stdlib DLL selection have landed. A
direct DLL-backed Kotlin-library abstraction remains desirable to remove temporary extraction, but
it does not block proving the single-artifact contract. Publication of only the DLL must not be
enabled before Gradle dependency/friend paths have moved and the transitional sibling inputs have
been removed from the compatibility matrix.
