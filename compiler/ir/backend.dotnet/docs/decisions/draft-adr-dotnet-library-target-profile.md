# Draft ADR: .NET library target profile

- Status: **POC implementation landed locally, including bounded Kotlin cross-module consumption**
- Date: 2026-07-16
- Scope: target framework/API identity of Kotlin-owned platform and user libraries

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

Keep `netframework` and `net` as concrete executable/runtime selections. They are not aliases for
the library TFM, and `netstandard2.0` must not be presented as something that can launch an
application. It is nevertheless a real compilation target for libraries. The current
`-Xdotnet-target` option still means executable runtime plus POC assembler selection; a general
library product must select the library TFM independently instead of hiding it behind that runtime
switch. The first POC product fixes that independent library selection to `netstandard2.0`; adding
more TFMs later is a library-product decision, not an extension of the runtime enum.

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
3. binds the KLIB with `dotnet_library_tfm=netstandard2.0`, independently of executable target;
4. discovers one installed pair under `lib/dotnet/netstandard2.0`; and
5. keeps ordinary application IL on its existing executable core-library profile;
6. provides `-Xdotnet-produce-library`, which emits `<module>.klib`, `<module>.il`, and
   `<module>.dll` for ordinary Kotlin sources with no entry point or runtimeconfig; and
7. writes a versioned declaration-binding index into an ordinary produced KLIB and uses it to
   resolve Kotlin calls and types to the paired CLR assembly.

The user-library pair uses the module name as its unsigned CLR assembly identity at version
`1.0.0.0`. Its KLIB carries the same assembly name, version, companion filename, and library TFM.
CLR consumers can use the existing explicit export boundary from that DLL. Kotlin consumers do
not need a second selector language. Following JS/Native linking, the logical key is Kotlin's
public `IdSignature`. Because this POC KLIB contains declaration metadata while the executable
implementation is already in a sibling CLR DLL, the CLR-specific index adds only the facts not
carried by that logical signature: physical owner-type path, method name, and static/instance
dispatch. Parameter and return signatures are still derived from Kotlin metadata.

The producer indexes only declarations that survive backend emission. The loader recognizes only
KLIBs with the explicit index ABI marker, validates their complete unsigned netstandard2.0
assembly binding, and requires the named sibling DLL. Arbitrary metadata KLIBs remain
compile-time-only. A consumer never reconstructs a facade from a source filename; it uses the
producer-recorded owner. Executable consumers copy referenced sibling DLLs next to their output.
The current manifest-property encoding is a bounded POC schema, not a public annotation or final
KLIB component design.

Portable PE production uses modern ILAsm independently of executable target. Framework ILAsm
accepts the same source but injects an `mscorlib` AssemblyRef into the resulting PE; it therefore
remains the Framework application writer and a compatibility oracle, not the canonical
netstandard2.0 library writer. A direct PE writer should eventually replace this tool constraint.

## Rejected alternatives

### Treat `netstandard2.0` as a third executable runtime

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

This draft does not choose a modern .NET light-up TFM, NuGet package layout/versioning, or the
eventual direct PE writer. The fixed `netstandard2.0` user-library mode also does not yet choose a
public multi-TFM selection syntax. Before productionization, the declaration index needs a proper
versioned KLIB component and a compatibility policy for Kotlin signature-mangler evolution; the
current manifest encoding is intentionally provisional. Transitive dependency publication and
package-manager layout also remain open. None of those concerns should reuse executable `main`
semantics merely because the existing POC runtime option is named `-Xdotnet-target`.
