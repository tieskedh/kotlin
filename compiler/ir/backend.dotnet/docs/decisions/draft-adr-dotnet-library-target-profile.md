# Draft ADR: .NET library target profile

- Status: **Draft candidate; representation probe validated, codegen not yet migrated**
- Date: 2026-07-16
- Scope: target framework/API identity of `Kotlin.Runtime` and `Kotlin.Stdlib`

This is a repository-local decision record for the experimental .NET backend. The `dotnet` branch
is a proof of concept; this document does not claim a public Kotlin or Kotlin/.NET commitment.

## Context

The current `-Xdotnet-target=netframework|net` switch combines two concerns:

- which ILAsm and runtime execute an application; and
- which library API surface the emitted assemblies claim to use.

Those are not the same axis. `.NET Standard` is an immutable library API contract, not a runtime
that launches an executable. This is close to the JVM distinction between the selected execution
environment and the conservative Java API/release floor used to compile a portable library.

The current backend emits the same `mscorlib`-scoped logical IL for Framework and CoreCLR. Only the
assembler/runtime path and the KLIB's provisional target property differ. Producing two permanent
stdlib packages from that toolchain distinction would create duplicate artifacts without proving a
real Kotlin or CLR API distinction.

## Candidate decision

Use `netstandard2.0` as the baseline target-framework/API profile for the Kotlin-owned platform
libraries:

```text
Kotlin.Runtime.dll
Kotlin.Stdlib.dll
Kotlin.Stdlib.klib
```

Keep `netframework` and `net` as concrete executable/toolchain targets. They are not aliases for
the library TFM, and `netstandard2.0` must not be added as a runnable value of
`-Xdotnet-target`.

The intended installed shape is one canonical pair rather than one pair per execution target:

```text
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Runtime.dll
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.klib
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.dll
```

Future modern-.NET-specific implementations may add a higher TFM as a multi-targeted asset, but
they must preserve a compatible public API. They do not change Kotlin callable or collection
identity.

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

The exercised output was `40` then `42` in every runtime/assembler pairing. This proves the
representation and consumer model are viable. It does not prove that every currently emitted BCL
member reference belongs to the 2.0 contract.

## Required implementation shape

Do not implement this decision as a textual `mscorlib` replacement. Introduce an explicit
core-library/API profile consumed by module headers, type rendering, member references, runtime
helpers, and platform-library production.

Before changing the installed layout:

1. Validate every runtime and stdlib BCL type/member reference against the `netstandard2.0`
   reference assembly, including currently cold helper bodies.
2. Emit the exact `netstandard, Version=2.0.0.0, PublicKeyToken=cc7b13ffcd2ddd51`
   AssemblyRef and `.NETStandard,Version=v2.0` `TargetFrameworkAttribute`.
3. Replace the KLIB's execution-target binding with a library-TFM property; executable target
   compatibility is then checked against that profile rather than string equality.
4. Run one produced platform-library pair against Framework and CoreCLR applications, including a
   C# consumer compiled against the 2.0 reference contract.
5. Only then replace the provisional per-runtime Kotlin-home discovery paths and add distribution
   installation.

## Rejected alternatives

### Treat `netstandard2.0` as a third executable target

There is no .NET Standard runtime or application host. This would preserve the current conflation
instead of fixing it.

### Permanently publish separate Framework and CoreCLR stdlibs

The current logical IL and public ABI are the same. Separate assets are justified only by a real
TFM-specific implementation need, not by the external assembler selected during the POC.

### Globally rename `mscorlib` to `netstandard`

That would make the positive probe permanent without an API audit, proper assembly identity,
target-framework metadata, or an extensible profile model. It would also create unnecessary exact
IL churn before the boundary is represented deliberately.

## Deferred work

This draft does not choose a modern .NET light-up TFM, NuGet package layout/versioning, general
Kotlin library multi-targeting, or the eventual direct PE writer. It only separates the portable
platform-library API floor from executable runtime selection.
