# Draft ADR: IL assembly pipeline during and after the POC

- Status: Candidate for the `dotnet` POC branch
- Date: 2026-07-15
- Scope: Turning generated CIL and metadata into a CLR assembly

## Context

The current backend runs inside the JVM-hosted Kotlin compiler. `DotNetIlEmitter` renders textual
IL directly, and `DotNetIlAssembler` invokes an external assembler when a binary is requested.
The `net10.0` target uses a pinned modern CoreCLR ILAsm; `netstandard2.0` libraries use that writer
as well. The `net48` target uses the installed .NET Framework ILAsm. Exact-IL tests retain the text,
while runtime tests assemble it and execute
the result.

Modern ILAsm is not abandoned software. Microsoft still publishes matching packages for current
.NET releases. The runtime-specific package used by the POC is nevertheless labelled as an
internal implementation package that should not be referenced directly. The Framework executable
is a useful frozen compatibility floor, not a suitable statement about the long-term compiler
toolchain.

Direct PE emission is also not a simple library substitution. Microsoft's
`System.Reflection.Metadata` APIs provide compiler-oriented metadata, instruction, and PE
builders, but they are .NET APIs. The JVM-hosted compiler cannot call them in-process. Using them
would require a .NET sidecar process, while true in-process emission requires a Kotlin/JVM
ECMA-335 metadata and PE writer (whether owned here or adopted after a separate dependency audit).

## Candidate decision

Keep textual IL plus ILAsm as the assembly path for the POC.

- Modern ILAsm is the primary assembler for `net10.0` and the portable `netstandard2.0` library
  profile.
- Framework ILAsm remains the assembler for the Framework target and an independent compatibility
  oracle while .NET Framework 4.8 is part of the supported prototype boundary.
- Textual IL is an implementation and validation artifact, not Kotlin runtime ABI. Exact goldens
  may deliberately pin compiler-owned metadata where reviewability matters, but consumers must not
  depend on formatting or incidental token/layout choices.
- Assembly failure is atomic with respect to the requested PE and runtimeconfig. ILAsm can leave a
  partial output on nonzero exit, so the compiler deletes both before reporting the failure.
- Do not add a .NET sidecar merely to replace ILAsm with `System.Reflection.Metadata`; that would
  retain process and runtime provisioning costs while adding a private protocol and another
  failure boundary.
- Do not start a Kotlin PE writer while callable, object, exception, default-argument, and metadata
  representations are still changing. That work would duplicate moving semantics in a harder-to-
  inspect form without enabling Kotlin behavior.

Before productionization, introduce a structured compiler-owned CIL/metadata model between IR
lowering and serialization. The present string-oriented emitter is not yet that abstraction. The
model should eventually support two sinks:

1. a deterministic textual IL renderer for exact goldens, diagnostics, probes, and ILAsm
   conformance checks; and
2. a direct PE/metadata writer for normal production artifacts.

The likely production writer is JVM-hosted so ordinary Kotlin compilation does not need a second
managed runtime process. `System.Reflection.Metadata` remains a useful reference implementation
and may be used in isolated tooling or conformance experiments; it is not assumed to be the
compiler's in-process implementation.

The profile-superset integration test now uses exactly that isolated-conformance allowance: a
small CoreCLR reflection verifier loads already assembled platform pairs in separate contexts and
compares their externally consumable type/member surfaces and normalized custom-attribute
payloads. It is test data, has no compiler protocol, does not assemble or rewrite artifacts, and
is never invoked by production compilation. It therefore strengthens PE evidence without
introducing the rejected production sidecar design.

## Why ILAsm is the better POC boundary

ILAsm accepts symbolic class, member, signature, and instruction declarations and owns the binary
bookkeeping: metadata-table rows and handles, signature blobs, heaps, method-body layout, branch
encoding, and PE construction. This lets the POC spend its complexity budget on Kotlin lowering,
CLR representation, and runtime ABI.

Text also makes the branch unusually reviewable. A golden exposes fields, flags, generic owners,
MethodImpl overrides, boxing, casts, and call signatures without a disassembly step. The second
assembler then catches invalid metadata or syntax that a string comparison cannot catch. This has
already found representation bugs in the prototype.

## Why ILAsm should not be assumed forever

An eventual direct writer can remove the external process and temporary-file boundary, improve
throughput and structured diagnostics, and own deterministic PE, Portable PDB, resource, signing,
and reproducibility policy. It also avoids making a runtime-specific package marked for internal
use part of the permanent compiler distribution contract.

Those are production-toolchain benefits, not reasons to hide an unsettled backend behind binary
serialization today.

## Migration triggers and acceptance criteria

Start the direct-writer project when most of the following are true:

- the foundational Kotlin runtime and callable/class/exception identities have stopped moving;
- the backend has a structured CIL/metadata model rather than text fragments as its only model;
- external assembly is a measured compilation or distribution bottleneck;
- Portable PDBs, resources, deterministic signing, or metadata features need first-class binary
  control; or
- supported hosts cannot reliably provision the selected ILAsm toolchain.

A direct writer is acceptable only when it:

- emits every exact metadata shape currently covered by the text suite;
- runs the complete runtime suite on every supported CLR target;
- produces deterministic output under a documented identity/version/signing policy;
- has structured diagnostics for invalid stack, signature, and metadata construction; and
- retains a text-render-and-ILAsm conformance lane as an independent oracle during migration.

The cutover should replace only serialization/assembly ownership. It must not opportunistically
change Kotlin callable identity, runtime ABI, generic representation, or target-framework policy.

## Consequences

For the POC, compilation continues to depend on a provisioned external assembler and textual
round-trip. The package version remains pinned, and both assembler implementations remain part of
feature validation where their compatibility boundary matters.

For the eventual production compiler, direct PE emission is the direction, but it is explicitly a
separate compiler-infrastructure milestone. Textual IL remains valuable after that milestone as a
human-readable diagnostic format and independent behavioral/metadata oracle.

## References

- [Microsoft ILAsm documentation](https://learn.microsoft.com/en-us/dotnet/framework/tools/ilasm-exe-il-assembler)
- [Current Microsoft.NETCore.ILAsm package](https://www.nuget.org/packages/Microsoft.NETCore.ILAsm)
- [Runtime-specific ILAsm package and its internal-package notice](https://www.nuget.org/packages/runtime.win-x64.Microsoft.NETCore.ILAsm)
- [ECMA-335 metadata-writing APIs](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.metadata.ecma335)
- [Managed PE-writing APIs](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.portableexecutable)
