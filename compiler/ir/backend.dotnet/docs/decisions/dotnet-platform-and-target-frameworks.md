# ADR: .NET platform identity and target frameworks

- Status: **Accepted — pre-ABI**
- Dates: 2026-07-16 through 2026-07-27
- Scope: Kotlin platform identity, target-framework profiles, capabilities,
  compatibility, and Gradle variant selection

## Context

Kotlin/.NET has two different axes:

- one Kotlin execution platform, CLR/.NET; and
- a target-framework/API contract selected from `net48`,
  `netstandard2.0`, and `net10.0`.

The original prototype conflated runtime/tool selection with the library API
surface. That would prevent capability-specific lowering, make dependency
compatibility implicit, and risk treating one Kotlin declaration as different
language semantics per framework.

Mature Kotlin targets keep physical/environment capabilities below
`KotlinPlatformType`: JVM has target-version/environment attributes, Native
has Konan targets, and Wasm distinguishes JS/WASI environments. Variant rules
select compatible artifacts without redefining Common declarations.

## Decision

### Kotlin/.NET is one logical platform

Compiler metadata uses the `DotNet` platform and Gradle metadata uses
`KotlinPlatformType.dotnet`. The target never impersonates JVM or Common and
is never compatible with JVM, Android/JVM, JS, Wasm, or Native artifacts.

Common metadata fallback applies as for every leaf target. Framework-specific
source APIs use normal source-set refinement; physical lowering differences do
not change the meaning of one Common declaration.

Target frameworks are not separate `KotlinPlatformType` values.

### Three framework profiles are first-class

| Profile | Applications | Libraries | Role |
| --- | --- | --- | --- |
| `net48` | yes | yes | .NET Framework 4.8/4.8.1 contract |
| `netstandard2.0` | no | yes | portable library floor shared by both runtimes |
| `net10.0` | yes | yes | modern .NET 10 contract and capabilities |

`.NET Standard` is an immutable library API contract, not an executable
runtime. Executable output under `netstandard2.0` is rejected before FIR or
codegen.

`.NET Standard 2.0` is selected because it spans the supported Framework floor
and modern .NET; 2.1 is not implemented by .NET Framework. It is a stable
intersection, not a way to expose current framework APIs.

### Framework, product, and runtime identifier are independent

The target framework selects the legal API/capability contract. Library versus
application is a separate product decision. A future runtime identifier (RID)
and package/layout selection remain independent axes.

The neutral `DotNetTarget` vocabulary owns only target-framework identity and
capabilities used across compiler layers. Product, packaging, artifact,
installation, and textual-CIL policy stay with their respective owners.

Profile selection occurs before target lowerings and controls core/member
references, target metadata, interface strategy, runtime/stdlib selection,
assembly writing, dependency compatibility, and packaging. Kotlin logical
declarations and observable Common behavior remain invariant.

### Compatibility is an explicit graph

- `net48` consumes `net48` and `netstandard2.0`.
- `net10.0` consumes `net10.0` and `netstandard2.0`.
- `netstandard2.0` consumes only `netstandard2.0`.
- `net48` and `net10.0` never consume one another.

This is not a numeric target-version ordering. Exact variants are preferred
over the portable fallback. A consumer with no profile remains ambiguous;
Gradle must not guess Framework, modern, or even portable output without a
consumer contract.

### Platform products are multi-targeted

Kotlin-owned runtime/stdlib products have one pair per profile. They share
Kotlin declarations and logical identities but may use different physical
implementation and interface-body placement.

Each executable-profile platform pair is a binary superset of the portable
platform surface, so one portable library binds to the application-selected
Kotlin runtime identity. It may add profile-specific members or bodies but may
not remove portable types, members, constraints, attributes, resources, or
semantic interface obligations.

This superset rule applies to Kotlin-owned platform products. Arbitrary
profile-specific user libraries are not interchangeable merely because their
Kotlin source resembles one another.

Target-specific APIs that cannot inhabit the portable surface select an exact
profile through source sets and variants; they do not conditionally alter a
Common declaration.

### One typed Gradle attribute owns profile selection

Gradle uses the typed
`org.jetbrains.kotlin.dotnet.targetFramework` attribute with the canonical
TFM values above. Ordinary compatibility and disambiguation rules implement
the graph; profile is not encoded in platform type, usage, artifact filename,
or a private resolution pass.

The attribute is attached to every profile-specific resolvable and consumable
configuration. API and runtime variants publish the same canonical
self-describing DLL artifact; Kotlin metadata is selected atomically from its
private resource rather than as a sibling KLIB.

Compiler and Gradle target enums may remain boundary-specific to preserve
dependency direction. Their mapping is exhaustive by canonical moniker and
must fail when either side gains an unhandled profile.

### Metadata records and validates the selected contract

Every Kotlin-produced DLL records its exact TFM in the embedded Kotlin
metadata/physical binding and emits truthful CLR target-framework metadata.
The loader rejects an incompatible dependency before Kotlin analysis and
never infers compatibility from assembly names or similar IL.

`Kotlin.Runtime.dll`, which has no Kotlin declaration KLIB, exposes the same
profile through its versioned implementation manifest bound to the physical
Assembly row. Partial or mismatched runtime/stdlib pairs are invalid.

Physical profile variants may use different semantically equivalent metadata
encodings. Ordinary custom-attribute compatibility compares decoded identity,
constructor/named arguments, and multiplicity rather than incidental blob
bytes, except where a separate compiler protocol explicitly pins bytes.

## Rejected alternatives

### Treat .NET as JVM or Common

Rejected. It admits the wrong platform conventions or erases a real leaf from
multiplatform refinement.

### Make every TFM a Kotlin platform

Rejected. Physical capabilities do not define different Kotlin semantics.

### Treat target frameworks as an ordered version

Rejected. Framework and modern runtime profiles are siblings with a shared
portable floor.

### Treat `netstandard2.0` as an executable runtime

Rejected. No such application host exists.

### Publish one physical platform asset for all profiles

Rejected. It either denies sound modern capabilities or emits output that the
Framework runtime cannot consume.

### Retarget IL by textual core-library replacement

Rejected. Profile selection must drive type/member mapping and metadata
deliberately, not rewrite one profile's output after codegen.

## Consequences

- Common compilation can name .NET without masquerading as another backend.
- Gradle expresses the real CLR compatibility graph independently of Kotlin
  platform identity.
- Exact and portable library selection is deterministic and atomic with KLIB.
- Profile-specific CIL is intentional where CLR capabilities differ.
- Compiler and build tooling must carry profile choice explicitly across every
  product boundary.

## Freeze conditions and open decisions

Before publication, validate the complete 3-by-3 compatibility matrix,
exact-over-portable preference, unspecified-consumer ambiguity, exhaustive
compiler/Gradle mapping, target metadata, self-describing artifact selection,
and portable platform-surface supersets across real runtimes.

NuGet layout, final multi-TFM publication syntax, RID modeling, reference
versus runtime asset modeling, transitive packaging, and first-publication
assembly/signing/version policy remain open. They may not merge the framework,
product, or platform axes.

## References

- [.NET Standard](https://learn.microsoft.com/dotnet/standard/net-standard)
- [Target frameworks](https://learn.microsoft.com/dotnet/standard/frameworks)
- [Library guidance](https://learn.microsoft.com/dotnet/standard/library-guidance/cross-platform-targeting)
