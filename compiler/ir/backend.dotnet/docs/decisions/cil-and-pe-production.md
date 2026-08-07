# ADR: CIL and PE production

- Status: **Accepted — pre-ABI**
- Date: 2026-07-15
- Amended: 2026-08-07 to assign the physical CLI model and serializers to
  `:dotnet:dotnet.ir`
- Scope: transforming lowered CIL and metadata into CLR assemblies

## Context

The Kotlin compiler is JVM-hosted. The prototype renders textual IL and calls
an external assembler for binary products. Modern and portable targets use a
pinned CoreCLR ILAsm; the Framework target uses Framework ILAsm. Exact tests
retain text while semantic tests assemble and execute artifacts.

Microsoft publishes modern ILAsm packages, but the runtime-specific package is
an implementation artifact rather than a desirable permanent compiler
contract. Framework ILAsm is a valuable compatibility floor, not a long-term
cross-platform production architecture.

Direct PE emission is not a drop-in use of `System.Reflection.Metadata`:
those are .NET APIs and cannot run in-process in the JVM compiler. They require
a sidecar, whereas in-process production requires a Kotlin/JVM ECMA-335 and PE
writer or another separately audited JVM dependency.

## Decision

### Prototype production uses textual IL and ILAsm

- Modern ILAsm writes `net10.0` and `netstandard2.0` products.
- Framework ILAsm writes `net48` products and remains an independent
  compatibility oracle while Framework 4.8 is supported.
- Textual IL is a compiler artifact and verification format, not Kotlin ABI.
  Consumers never depend on formatting, row order, tokens, or incidental
  layout.
- Assembly failure is atomic: partial PE and runtime-configuration outputs are
  removed before failure is reported.
- The selected toolchain version is pinned and provisioned explicitly.

Do not add a .NET sidecar merely to replace ILAsm with another external
process plus a private protocol. `System.Reflection.Metadata` remains useful
as a reference implementation and in isolated test/conformance tools that do
not become part of production compilation.

Do not build a direct writer against moving string fragments. Kotlin lowering,
physical type/member identities, and metadata contracts must first feed one
structured compiler-owned model.

### The production endpoint is structured CIL with two sinks

Before productionization, introduce a structured CIL/metadata model between
IR lowering and serialization. It owns validation and supports:

1. a deterministic textual renderer for diagnostics, exact tests, probes, and
   ILAsm conformance; and
2. a direct PE/metadata writer for normal production artifacts.

The direct writer should be JVM-hosted so ordinary Kotlin compilation does not
require a second managed-runtime process. Serialization ownership may change
without changing Kotlin declaration identity, runtime ABI, generic lowering,
or target-framework policy.

The physical model and its serializers live in the low-level
`:dotnet:dotnet.ir` module under `org.jetbrains.kotlin.dotnet.ir`, following
the dependency direction between `:compiler:ir.backend.wasm` and
`:wasm:wasm.ir`. The compiler backend lowers concrete representation choices
into that model; the model must not depend on Kotlin IR, FIR, KLIB,
target-framework configuration, export selection, or Kotlin ABI policy.

The CLI model is capability-complete only as features require it. Supporting
an ECMA-335 generic TypeDef, TypeSpec, MethodSpec, or GenericParam does not
authorize the backend to use that shape as Kotlin runtime identity. In
particular, the accepted declaration-erased owner of a Kotlin-owned generic
class remains authoritative. Imported CLR generics, generic methods, truthful
interface capabilities, explicit export artifacts, and removable private
specialization may use physical CLI generic constructs under their owning
decisions.

Migration is incremental. No model node is introduced without a concrete
producer and consumer. Each slice gains structural validation and a
deterministic text rendering, switches the production path for that shape,
and removes the superseded string construction for the same shape. The
complete corpus must not permanently run through two emitters.

Text remains an independent oracle after direct PE becomes primary.

## Why this boundary

ILAsm currently owns metadata handles, signature blobs, heaps, method-body
layout, branch encoding, and PE construction. That keeps prototype effort on
Kotlin semantics and CLR representation while exposing flags, generic owners,
MethodImpl rows, boxing, casts, and call signatures to human review.

A future direct writer removes process and temporary-file overhead, gives the
compiler structured diagnostics, and owns deterministic PE, Portable PDB,
resource, signing, and reproducibility policy. Those are production benefits,
not reasons to hide unsettled representation choices in binary code today.

## Migration triggers

Start the direct-writer programme when most of these are true:

- foundational runtime, callable, class, interface, and exception identities
  no longer change routinely;
- codegen produces a structured CIL/metadata model;
- external assembly is a measured performance or distribution bottleneck;
- Portable PDB, resources, deterministic signing, or advanced metadata require
  direct control; or
- supported hosts cannot reliably provision the assembler.

## Acceptance criteria

A direct writer must:

- emit every semantic metadata shape covered by exact tests;
- pass real runtime tests on every supported target;
- produce deterministic output under an explicit identity/version/signing
  policy;
- diagnose invalid stacks, signatures, and metadata structurally;
- preserve self-describing DLL resources and custom attributes; and
- retain text-render-and-ILAsm conformance during migration.

## Consequences

- Prototype compilation depends on an external pinned assembler and textual
  round trip.
- Text goldens remain unusually reviewable and assembly catches failures that
  string comparison cannot.
- Direct PE is a separate compiler-infrastructure programme rather than an
  incidental backend refactor.
- The writer transition cannot opportunistically revise language semantics or
  physical ABI.
- Physical CLI vocabulary, structural invariants, and serialization are
  reusable target-format infrastructure; Kotlin representation planning and
  target-profile legalization remain backend responsibilities.

## References

- [Microsoft ILAsm documentation](https://learn.microsoft.com/en-us/dotnet/framework/tools/ilasm-exe-il-assembler)
- [Microsoft.NETCore.ILAsm package](https://www.nuget.org/packages/Microsoft.NETCore.ILAsm)
- [ECMA-335 metadata APIs](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.metadata.ecma335)
- [Managed PE APIs](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.portableexecutable)
