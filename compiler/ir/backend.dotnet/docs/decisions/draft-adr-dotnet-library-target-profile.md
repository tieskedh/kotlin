# Draft ADR: .NET library target profile

- Status: **Accepted pre-ABI direction; explicit profile axis implemented, capability policies incomplete**
- Date: 2026-07-16
- Scope: target framework/API identity of Kotlin-owned platform and user libraries

This is a repository-local decision record for the experimental .NET backend. The `dotnet` branch
is a proof of concept; this document does not claim a public Kotlin or Kotlin/.NET commitment.

## Context

The original `-Xdotnet-target=netframework|net` switch combined two concerns:

- which ILAsm and runtime execute an application; and
- which library API surface the emitted assemblies claim to use.

Those are not the same axis. `.NET Standard` is an immutable library API contract, not a runtime
that launches an executable. This is close to the JVM distinction between the selected execution
environment and the conservative Java API/release floor used to compile a portable library.

That backend emitted the same `mscorlib`-scoped logical IL for Framework and CoreCLR. Only the
assembler/runtime path and the KLIB's provisional target property differed. Treating that
toolchain distinction as a final target model would have prevented profile-specific lowering and
made dependency compatibility implicit.

## Decision

Keep three first-class target profiles:

| Profile | Applications | Libraries | Capability role |
| --- | --- | --- | --- |
| `net48` | yes | yes | .NET Framework 4.8/4.8.1 |
| `netstandard2.0` | no | yes | portable intersection consumable from both runtime targets |
| `net10.0` | yes | yes | modern .NET 10 LTS |

The target profile and product kind are independent compiler configuration axes. `.NET Standard`
has no application host and must be rejected for executable output. A library selects one profile;
an application selects one executable profile. Dependency compatibility is explicit:

- `net48` consumes `net48` and `netstandard2.0` libraries;
- `net10.0` consumes `net10.0` and `netstandard2.0` libraries;
- `netstandard2.0` consumes only `netstandard2.0` libraries.

Do not assume that identical Kotlin IR requires identical IL. The selected profile is available to
target lowerings, type/member mapping, interface strategy, metadata references, runtime/stdlib
selection, assembly writing, and packaging. The compiler emits different physical code when CLR
capabilities require it. The logical Kotlin declarations and observable common Kotlin semantics
remain the same.

Kotlin-owned platform artifacts are multi-targeted:

```text
<kotlin-home>/lib/dotnet/net48/Kotlin.Runtime.dll
<kotlin-home>/lib/dotnet/net48/Kotlin.Stdlib.{klib,dll}
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Runtime.dll
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.{klib,dll}
<kotlin-home>/lib/dotnet/net10.0/Kotlin.Runtime.dll
<kotlin-home>/lib/dotnet/net10.0/Kotlin.Stdlib.{klib,dll}
```

The variants share Kotlin logical identities and source contracts but may have different physical
implementation and interface-body placement. Each KLIB/DLL binding records its exact profile;
resolution rejects incompatible variants. Packaging may later use a variant map instead of one
KLIB per profile, but it must preserve this selection and binding rule.

An application deploys exactly one `Kotlin.Runtime` and one `Kotlin.Stdlib` variant. The `net48`
and `net10.0` variants must each be a binary superset of the `netstandard2.0` platform surface so a
portable library can bind to the application-selected runtime without loading a second Kotlin
runtime identity. Profile-specific code may add members, use a different body strategy, or call
newer BCL APIs; it may not remove the portable helper/member surface on which a
`netstandard2.0` consumer was compiled. Modern interface bodies can therefore coexist with portable
helper entry points where the shared-library ABI requires both.

This superset rule applies to Kotlin-owned platform assemblies. Arbitrary `net48` and `net10.0`
user-library binaries are profile-specific assets and are not interchangeable merely because their
Kotlin sources have the same declarations.

`netstandard2.0` is the shared-library choice when one binary must be consumed by both runtime
targets. APIs or implementations that require Framework-only or modern-only capabilities select
`net48` or `net10.0`; they do not leak into the portable surface. Target-specific Kotlin APIs use
the normal multiplatform/source-set model rather than conditional meanings for one common
declaration.

## Why 2.0

`.NET Standard 2.0` is the common supported library surface spanning modern .NET and the backend's
.NET Framework 4.8 compatibility floor. `.NET Standard 2.1` is not implemented by .NET Framework.
No new .NET Standard versions are planned, so 2.0 is useful here precisely as a stable floor rather
than as a way to chase current framework APIs.

Microsoft's current guidance likewise reserves `netstandard2.0` for libraries that must span .NET
Framework and modern .NET:

- <https://learn.microsoft.com/dotnet/standard/net-standard>
- <https://learn.microsoft.com/dotnet/standard/frameworks>
- <https://learn.microsoft.com/dotnet/standard/library-guidance/cross-platform-targeting>

## Probe evidence

The local `netstandard_s1` probe mechanically retargeted the complete current runtime IL, generated
stdlib IL, and an ordinary `first()`/`last()` program from `mscorlib` to the exact `netstandard`
2.0 facade identity. This was an isolated representation probe, not the proposed implementation.

The probe established:

- modern and Framework ILAsm both accepted the `netstandard`-scoped runtime, stdlib, and program;
- both modern-assembled and Framework-assembled platform-library pairs ran on CoreCLR 10 and .NET
  Framework 4.8;
- an unchanged `mscorlib`-scoped application also ran against the `netstandard`-scoped platform
  libraries on both runtimes; and
- the Framework C# compiler accepted calls to the Kotlin stdlib while compiling with `/nostdlib`
  against the actual `netstandard2.0` reference assembly.

The exercised output was `40` then `42` in every runtime/assembler pairing. A subsequent metadata
audit resolved all 27 referenced BCL types and all 55 external BCL member references against the
actual .NET Standard 2.0 reference assembly with zero errors.

## Implemented POC shape

Do not implement this decision as a textual `mscorlib` replacement. Introduce an explicit
core-library/API profile consumed by module headers, type rendering, member references, runtime
helpers, and platform-library production.

The implementation now:

1. carries an explicit core-library profile through type mapping, module headers, member
   references, nullable metadata, runtime helpers, and platform-library production;
2. emits the exact `netstandard, Version=2.0.0.0, PublicKeyToken=cc7b13ffcd2ddd51`
   AssemblyRef and `.NETStandard,Version=v2.0` `TargetFrameworkAttribute`;
3. exposes only `net48`, `netstandard2.0`, and `net10.0` as explicit profile values and rejects an
   executable product under `netstandard2.0` before frontend/code generation;
4. binds every produced KLIB to its selected profile with `dotnet_library_tfm` and rejects an
   incompatible dependency before FIR analysis;
5. discovers an installed exact-profile stdlib first, then the portable `netstandard2.0` variant
   for either executable profile;
6. selects the profile before IR lowerings and carries it through user IL, runtime/stdlib
   generation, assembly metadata, assembly writing, deployment, and packaging;
7. provides `-Xdotnet-produce-library`, which emits `<module>.klib`, `<module>.il`, and
   `<module>.dll` for ordinary Kotlin sources with no entry point or runtimeconfig; and
8. writes a versioned declaration-binding index into an ordinary produced KLIB and uses it to
   resolve Kotlin calls and types to the paired CLR assembly; and
9. mechanically compares the externally consumable CLR reflection surface of the assembled
   `Kotlin.Runtime` and `Kotlin.Stdlib` variants, requiring each executable profile to retain every
   portable public/protected type, base/interface edge, generic constraint, method, field,
   property, and event with compatible accessibility and overridability, together with every
   portable custom-attribute identity and normalized payload on the assembly or exposed surface.

The repository's opt-in stdlib producer and installer create all three profile variants under
their corresponding `lib/dotnet/<profile>` directories. A focused integration lane proves that a
single `netstandard2.0` stdlib pair is discovered as fallback, compiled against, assembled, and
executed by both `net48` and `net10.0` applications.

The surface comparison runs as isolated test tooling under CoreCLR, loading each profile pair in
its own assembly-load context. It consumes assembled PEs rather than rendered-IL substrings and
allows the deliberate portable-abstract-to-modern-DIM transition while rejecting removed or
narrowed callable surface. It also compares constructor and named custom-attribute arguments on
assemblies, types, members, parameters, returns, and generic parameters; only
`TargetFrameworkAttribute` is excluded because its profile value must differ. A target-owned
`@TestOnly` hook produces each runtime variant without turning runtime generation into a library
side effect. This audit does not yet compare raw attribute-blob encoding, MethodImpl rows,
resources, or internal friend-only surface; those remain part of the future structured metadata
model and ABI-freeze audit.

The user-library pair uses the module name as its unsigned CLR assembly identity at version
`1.0.0.0`. Its KLIB carries the same assembly name, version, companion filename, and library TFM.
The current version and unsigned form are deterministic prototype binding inputs, not a public ABI
freeze. Before Gate B, platform assemblies and general user-library production need an explicit
first-publication policy for naming, strong names, AssemblyVersion compatibility, and package
versions. After publication, changing those CLR identity components requires an explicit ABI
transition; before publication, architecture may still require breaking them together with the
KLIB schema and all producers and consumers.
CLR consumers can use the existing explicit export boundary from that DLL. Kotlin consumers do
not need a second selector language. Following JS/Native linking, the logical key is Kotlin's
public `IdSignature`. Because this POC KLIB contains declaration metadata while the executable
implementation is already in a sibling CLR DLL, the CLR-specific index adds only the facts not
carried by that logical signature: physical owner-type path, method name, and static/instance
dispatch. Parameter and return signatures are still derived from Kotlin metadata.

The producer indexes only declarations that survive backend emission. The loader recognizes only
KLIBs with the explicit index ABI marker, validates their complete unsigned sibling-assembly and
target-profile binding, and requires the named sibling DLL. Arbitrary metadata KLIBs remain
compile-time-only. A consumer never reconstructs a facade from a source filename; it uses the
producer-recorded owner. Executable consumers copy referenced sibling DLLs next to their output.
The current manifest-property encoding is a bounded POC schema, not a public annotation or final
KLIB component design.

`netstandard2.0` PE production uses modern ILAsm independently of consumer runtime. Framework ILAsm
accepts the same source but injects an `mscorlib` AssemblyRef into the resulting PE; it therefore
remains the `net48` application/library writer and a compatibility oracle, not the canonical
portable-library writer. Modern ILAsm writes `net10.0` applications/libraries. A direct PE writer
should eventually replace this tool constraint.

The present `net10.0` core profile deliberately has a `.NETCoreApp,Version=v10.0`
`TargetFrameworkAttribute` and modern product/runtime path, but temporarily retains `mscorlib`
MemberRefs for the implemented common surface. That is a compatibility-oriented implementation
stage, not a premise that `net48` and `net10.0` code must remain identical. Newer BCL references,
default-interface placement, and other capability-dependent code must be selected through the
profile, with explicit tests for the resulting metadata and behavior.

## Rejected alternatives

### Treat `netstandard2.0` as a third executable runtime

There is no .NET Standard runtime or application host. This would preserve the current conflation
instead of fixing it.

### Publish one physical platform-library asset for every profile

Rejected. The profiles have materially different CLR capabilities and core-library identities.
Forcing one physical artifact would either deny `net10.0` sound modern implementations or emit IL
that cannot run on Framework. The variants may share Kotlin metadata inputs and logical identities;
they do not share one mandatory physical implementation.

### Globally rename `mscorlib` to `netstandard`

That would make the positive probe permanent without an API audit, proper assembly identity,
target-framework metadata, or an extensible profile model. It would also create unnecessary exact
IL churn before the boundary is represented deliberately.

## Deferred work

This decision does not choose NuGet package layout/versioning, the first published assembly
identity/signing/version policy, the public multi-TFM selection syntax, or the eventual direct PE
writer. The publication identity policy is a Gate B prerequisite. Before productionization, the
declaration index needs a proper versioned KLIB component and a compatibility policy for Kotlin
signature-mangler evolution; the current manifest encoding is intentionally provisional.
Transitive dependency publication,
asset selection in Gradle/KMP, and package-manager layout also remain open. None of those concerns
should reuse executable `main` semantics merely because the existing POC runtime option is named
`-Xdotnet-target`.
