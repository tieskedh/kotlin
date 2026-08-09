# ADR: Kotlin/.NET runtime and standard-library ownership

- Status: **Accepted — pre-ABI**
- Date: 2026-07-17
- Scope: physical ownership, source authority, bootstrap production,
  installation, and application packaging

This is the selected product direction for the experimental target. Assembly
names, versions, signing, and package publication remain pre-ABI.

## Context

Generated code needs stable compiler/runtime identities and ordinary Kotlin
library implementations. Keeping both in one assembly would turn bootstrap
convenience into permanent architecture. Emitting library implementations into
every program would duplicate type identity and behavior.

A CLR DLL alone is not a Kotlin compile-time library. CLR metadata does not
carry Kotlin extension receivers, source visibility, expect/actual
relationships, logical nullability, or every Kotlin contract. Kotlin-produced
libraries therefore need authoritative Kotlin metadata as well as physical CLR
declarations.

Mature targets build their platform libraries as separate products from user
applications. Common and generated stdlib sources own ordinary declarations;
platform targets supply actuals and irreducible host operations.

## Decision

### Physical ownership is split three ways

- `Kotlin.Runtime.dll` owns compiler/runtime identities and services required
  by generated code: callable and interface identities, exact runtime types,
  classification/state services, and narrow semantic helpers. This includes
  the minimal physical `KClass` and `KType` interfaces required by runtime-
  owned callable signatures; their logical Common declarations and ordinary
  behavior remain Kotlin source authority.
- `Kotlin.Stdlib.dll` owns ordinary Kotlin library declarations,
  implementations, facades, and private implementation classes.
- The user assembly owns only declarations from that compilation and calls the
  two platform products.

An ordinary stdlib algorithm never belongs in the emitter or runtime. A
private implementation class belongs to the stdlib assembly and does not
become public compiler ABI merely because a compiler-generated bridge or
factory reaches it.

Runtime must not reference Stdlib: Stdlib already implements and depends on
Runtime identities. When a runtime-owned public contract needs a Common type,
put only the minimal cycle-free physical interface in Runtime and keep its
ordinary implementation and behavior in Stdlib. Do not weaken the contract to
`object`, duplicate the identity in both assemblies, or move the Common
implementation into Runtime.

### Common and generated Kotlin sources are authoritative

The target compiles exact Common/generated declarations plus narrow .NET
actuals. Signatures, bodies, annotations, documentation, and expect/actual
relationships come from those authoritative sources.

A bounded bootstrap generator may materialize only the dependency-closed
subset the target can currently compile. It invokes the same Common templates
and fails on source drift; it never maintains a .NET copy. The endpoint is the
complete Common generated corpus plus narrow target actuals, after which the
selection layer disappears.

An admitted generated source shard mapped to one physical facade owns the
complete top-level function closure in that shard, including internal and
private helpers. Do not maintain a second per-function physical allowlist:
that can silently omit a helper while retaining its authoritative caller.
Compiler-only resolution markers are the explicit exception; their exact
intrinsics or lowerings must remove both calls and declarations before CIL.
The generator/source projection remains the admission boundary for public
Kotlin API.

Common abstract collection classes and other source families enter only with
their complete dependency closure. A missing target feature is not a CLR
reason to replace a Common algorithm with a BCL call or intrinsic. Explicit
BCL collection adapters remain a separate interop programme.

Common I/O retains EOF, exception, and Kotlin rendering semantics. The target
owns only its actual declarations and irreducible `System.Console` operations.
Internal Common markers do not acquire a foreign CLR protocol unless Common
semantics promise one.

### One self-describing stdlib DLL is the Kotlin library artifact

Each profile's `Kotlin.Stdlib.dll` contains both its CLR implementation and a
private authoritative KLIB resource. The KLIB and physical declaration index
come from the same resolved, actualized frontend result.

The embedded manifest binds logical declarations to the containing PE
assembly and target profile. The compiler validates that binding against the
PE metadata. No standalone or sibling KLIB is produced, installed, resolved,
or accepted.

Only cross-module declarations enter the physical binding index. Private,
private-to-this, and local identities remain implementation details even when
their CLR definitions are necessary.

See the
[self-describing-library ADR](adr-self-describing-dotnet-library-dll.md).

### Runtime and stdlib are a profile pair

The platform product consists of `Kotlin.Runtime.dll` and
`Kotlin.Stdlib.dll` for one target profile:

- `net48` consumers use the `net48` pair or the portable
  `netstandard2.0` pair;
- `net10.0` consumers use the `net10.0` pair or the portable
  `netstandard2.0` pair; and
- `netstandard2.0` consumers use only the portable pair.

Every pair exposes the same supported Kotlin logical identities. Profiles may
change legal CLR references and physical implementation capabilities, not
Common declarations or behavior. An exact-profile stdlib is never combined
with another profile's runtime merely because provisional assembly versions
happen to match.

`Kotlin.Runtime.dll` has no Kotlin declaration KLIB, so its public versioned
implementation manifest is the physical profile/contract view needed by the
compiler and Roslyn tooling. It is not a second Kotlin declaration namespace.

The monotone runtime-surface level has one physical runtime authority: the
standard assembly-level
`AssemblyMetadata("Kotlin.RuntimeSurfaceLevel", "<level>")` value. A stdlib or
ordinary Kotlin library records the minimum level it requires in its
authoritative KLIB manifest; it does not prove which sibling runtime was
selected. Conversely, the C# implementation manifest does not duplicate the
runtime's actual level. This retains the same producer/consumer direction as
mature targets' pre-link library-version checks without inventing a second
.NET metadata vocabulary.

After authenticating the runtime's assembly identity, target profile, and
public implementation manifest, the CLI requires exactly one standard
runtime-surface value and requires the current pre-ABI level exactly. Missing,
duplicate, malformed, stale, and future values fail before FIR or CIL
generation. This check must also work for a Kotlin-only `-no-stdlib` consumer,
which need not load physical BCL reference assemblies. The objective CLR
reader therefore validates the runtime image's exact assembly-level
`CustomAttribute` parent, constructor `MemberRef`, top-level
`AssemblyMetadataAttribute` `TypeRef`, profile-selected core `AssemblyRef`,
`(string, string)` signature, and ECMA-335 value blob directly. General
foreign-annotation import remains stricter graph-resolved evidence; this
bounded compiler-owned carrier check must not become its replacement.

### Production is explicit and distribution-owned

Runtime/stdlib production is an explicit library-product lifecycle, not a side
effect of compiling an application. One selected-profile invocation produces
the runtime and self-describing stdlib together. Installation copies both into
the same target-profile directory. Discovery rejects an incomplete or
incompatible pair instead of silently constructing a replacement.

Ordinary compilation prefers an installed exact-profile pair and then a legal
portable pair. `-no-stdlib` remains the explicit opt-out and bootstrap escape
hatch. Application packaging copies the selected runtime and stdlib bytes
unchanged beside the application.

Repository product/install tasks are opt-in and remain outside ordinary
compiler-distribution assembly until the target is a supported distribution
product. Their concrete task names and output paths are build implementation,
not architecture.

### The packaged-source fallback is temporary

Before every compiler distribution and test bootstrap supplies a platform
pair, the backend may package a read-only copy of the canonical target/Common
source product. It is injected only when neither installed artifacts nor
explicit product sources are available.

The fallback is byte-identical to repository source and is never a second
implementation. The explicit source route and fallback route must resolve the
same logical declarations and produce identical KLIB and compiler-owned CIL
for one compiler, source set, and profile.

Same-run ownership separation may emit stdlib-owned and user-owned
declarations to different assemblies from one lowering session during this
bootstrap phase. It is not the normal library lifecycle and must disappear
once distribution-owned pairs are universal.

FIR actualization may retain either a Common expect file or its .NET actual
file as the physical IR owner. The same-run partition therefore recognizes
every verified owner filename for one admitted declaration and maps all of
them to the same stdlib facade. Adding the first executable declaration to an
actual-only source file must add that owner to the partition; it must not make
the declaration appear in the following user assembly. Product tests compare
both DLLs and IL-text tests reject such stdlib-to-user leakage.

### Reproducibility has an explicit boundary

For a fixed compiler, source corpus, and profile:

- metadata ordering and archive timestamps are deterministic;
- the packed KLIB and compiler-owned CIL are byte-identical across repeated
  product builds;
- ordinary-source and packaged-fallback routes produce the same logical and
  compiler-owned output; and
- no recursive DLL hash is embedded in the self-bound KLIB.

Deterministic PE, module identity, signing, resources, and PDBs ultimately
belong to the writer selected by the
[CIL/PE production ADR](cil-and-pe-production.md), not to stdlib source
ownership.

## Rejected alternatives

### Put ordinary implementations in `Kotlin.Runtime`

Rejected. It mixes language/runtime ABI with library policy and prevents the
implementation from using the same Kotlin source and bridge pipeline as user
code.

### Emit stdlib helpers into every user assembly

Rejected. It duplicates physical type identity and makes library behavior a
per-program compiler artifact.

### Treat a bare CLR DLL as a Kotlin library

Rejected. Reflection cannot reconstruct authoritative Kotlin declarations.

### Rebuild platform libraries during every application compilation

Rejected. Mature targets resolve distribution-owned platform products; user
compilation is not a platform-library publisher.

### Maintain copied .NET algorithms

Rejected. Bootstrap capability staging does not transfer semantic authority
away from Common sources or generators.

## Consequences

- Runtime ABI and ordinary stdlib policy have distinct physical owners.
- Built-in and user implementations use the same general compiler pipeline.
- Compiled programs reference one Kotlin runtime/stdlib identity pair.
- Bootstrap temporarily carries source/product complexity that normal
  distribution consumption removes.
- Metadata-public factories, facades, and helpers are compiler/stdlib ABI even
  when their Kotlin visibility is internal.
- Private implementation names remain replaceable before and after ABI freeze
  unless exposed by a separate interop contract.

## Freeze conditions and open decisions

Before public distribution, decide together:

- final runtime and stdlib assembly names;
- strong-name and key-rotation policy;
- AssemblyVersion versus package-version compatibility;
- publication layout and target-pack integration;
- final .NET KLIB platform marker;
- complete source/product ownership generation; and
- removal criteria for packaged-source and same-run fallbacks.

Validation must continue to cover direct/fallback source identity, repeated
production, self-describing metadata, separate and installed consumers,
profile compatibility, incomplete-pair rejection, byte-preserving packaging,
private-index exclusion, and real Framework/CoreCLR execution.

Future collection breadth, primitive-specialized iterators, BCL adapters, and
other ordinary library APIs must preserve this ownership split.
