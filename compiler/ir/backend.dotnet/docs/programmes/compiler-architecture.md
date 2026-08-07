# .NET compiler architecture programme

- Status: **Active — neutral foreign-linkage and exact-contract carriers extracted; physical ABI serialization is the next seam**
- Current ownership: [`../../STATUS.md`](../../STATUS.md)

## Governing rule

Mirror the ownership, dependency direction, and package conventions of mature Kotlin targets until
a concrete CLR constraint requires a deviation. Prototype history, file size, constructor size, or
the fact that two concerns were first implemented together is not such a constraint.

This programme does not seek fewer files or generic domain/application/infrastructure layers. A
new boundary must have a clear producer and consumer, own validation, and enforce a useful
dependency direction.

## Mature-target precedent

- JVM keeps foreign binary facts and finders below FIR, Kotlin/Java enhancement in FIR, target
  identity/configuration in dedicated core/config modules, and IR lowering/codegen in the backend.
- JS and Wasm keep configuration, KLIB serialization/loading, frontend integration, and backend
  production in distinct dependency roles.
- Native keeps target configuration, KLIB ownership, IR lowering, and native code generation
  separate even when historical packages do not perfectly reflect module ownership.
- JVM's descriptor-less reflection reconstructs logical Kotlin callables from metadata in
  `core:reflection.jvm`; the backend does not become the owner of runtime member discovery. A
  .NET lowering may construct a compile-time callable-reference graph, but later KLIB decoding,
  member enumeration, and reflective lookup need a runtime/reflection owner outside
  `backend.dotnet`.
- Swift Export keeps declaration admission and its host-facing model in Analysis-API/SIR provider
  layers, while the Native backend binds the resulting physical reverse bridges. The .NET backend
  may emit and bind CIL for a validated export plan, but must not remain the permanent owner of
  export selection, declaration-family admission, or the reusable host-facing model.
- CLI modules compose pipelines and may call backend entry points. That does not make foreign
  metadata, serialization, product descriptions, or packaging code-generation responsibilities.

Historical `backend.*` package names in mature modules are evidence to interpret, not conventions
to copy into a new target.

## Settled ownership map

- Logical target/platform vocabulary: `core:language.targets.dotnet`, in
  `org.jetbrains.kotlin.config` and `org.jetbrains.kotlin.platform.dotnet`.
- Compiler keys and target/profile policy: `compiler:config.dotnet`, in
  `org.jetbrains.kotlin.config`.
- CLR content-root carrier: `cli-base`, in `org.jetbrains.kotlin.cli.dotnet.config`.
- PE/ECMA-335 reading and objective evidence: `compiler:frontend.common.dotnet`, in
  `org.jetbrains.kotlin.load.dotnet`.
- Neutral cross-phase CLR-facing carriers: `compiler:dotnet.imports`, in
  `org.jetbrains.kotlin.load.dotnet`.
- Kotlin interpretation of foreign evidence: `compiler:fir:fir-dotnet`, in
  `org.jetbrains.kotlin.fir.dotnet`.
- IR context, lowerings, intrinsics, type mapping, CIL generation, and backend product
  construction: `compiler:ir:backend.dotnet`, in `org.jetbrains.kotlin.backend.dotnet`.
- CLI pipeline sequencing: `compiler:cli:cli-dotnet`, in
  `org.jetbrains.kotlin.cli.pipeline.dotnet`.
- Kotlin stdlib declarations/algorithms: `libraries:stdlib` and its generators, in ordinary
  Common and .NET library packages.

`DotNetTarget` represents the target-framework/API contract. Product kind, runtime identifier,
deployment layout, NuGet selection, and textual IL rendering are separate axes and must not
accumulate on that enum.

The .NET platform marker is one unversioned Kotlin platform identity. `net48`,
`netstandard2.0`, and `net10.0` are target-framework contracts, not three Analysis API platforms.

A future Analysis API or IDE component is a consumer of these same authorities, not a second
compiler model. It reconstructs Kotlin declarations from embedded KLIB and obtains physical CLR
owner, method name, property/event shape, and intentional export absence from the same versioned
physical ABI/placement model used by compilation and C# export. It must not reuse JVM
`javaMethodName` queries or recompute lowering-owned names in the plugin.

## Objective CLR evidence versus Kotlin policy

The loader may establish facts such as:

- the exact selected TypeDef and constructor identity of a CodeAnalysis attribute;
- its target row, decoded value, multiplicity, and malformed state;
- a Roslyn nullable flag aligned to a resolved physical signature component;
- physical assignability, variance, constraints, array, delegate, or by-ref-like classification;
  and
- exact Property/MethodSemantics/Param attachment.

It may not decide the resulting Kotlin type, contract, smart cast, diagnostic, declaration, or
call convenience. Those are FIR/import-policy decisions constrained by Common.

Conversely, FIR policy must not parse PE files, bind assemblies, or rediscover physical members.
It consumes immutable selected evidence. The backend consumes retained physical linkage, not FIR
implementation internals.

## Dependency invariants

### Objective CLR load module

`compiler:frontend.common.dotnet` may depend on neutral target vocabulary but not on:

- FIR or IR;
- `backend.dotnet`;
- CLI pipeline code;
- Gradle or packaging implementations; or
- Roslyn tooling.

The managed-resource reader accepts a carrier resource name from its caller. It reports physical
presence/absence and does not decide that an assembly is Kotlin-produced or foreign.

### FIR integration

`compiler:fir:fir-dotnet` owns Kotlin projection and lazy symbol policy. It may consume the loader
and `compiler:dotnet.imports`. It must not import backend-only CIL, lowering, runtime construction,
physical emitter state, or CLI pipeline code.

`compiler:fir:fir2ir:dotnet-backend` owns only target-specific FIR-to-IR integration, mirroring the
existing JVM backend module at that repository seam. In particular, a retained foreign CLR
`SZARRAY` has a flexible Kotlin call view whose rigid source implementation is already accepted by
FIR; the .NET FIR2IR overridability condition preserves that accepted override edge using the exact
physical declaration carrier. CIL codegen must not reconstruct it by names or signatures after
the edge has been lost.

### Neutral cross-phase carriers

`compiler:dotnet.imports` owns small, versioned compiler carriers shared across
the FIR/backend boundary. The foreign-declaration carrier preserves one
already-selected resource-free assembly, declaring TypeDef, member, and
structural signature through FIR2IR. It references immutable objective rows
selected by the loader and validates their exact assembly and owner membership
when constructed.

The exact-contract carrier preserves only an already-derived additive CLR
projection while resolved FIR and the corresponding IR declaration coexist.
It contains the five closed effect kinds and ordinary value-parameter indices,
not the Kotlin contract graph, FIR/IR nodes, Roslyn symbols, or CIL. The CLI
only transports the carrier; the explicit export backend is its sole consumer.
Discarding it cannot change Kotlin analysis, KLIB, or execution.

The module does not select a classpath, enhance a Kotlin type, construct FIR,
bind IR, or render CIL. Producing the exact-contract carrier is a FIR-owned
semantic decision; owning its neutral validated shape here does not move that
interpretation into the carrier module.

The carrier protocol has an explicit version. A producer must construct a supported version and a
consumer must match it exhaustively; an unknown version or carrier shape is not reinterpreted from
display names or tokens.

The complete selected assembly/KLIB dependency graph is likewise shared .NET import/library
infrastructure. Backend emission may consume already-selected bindings but may not discover,
reorder, or cache the graph. Native dependency-DAG utilities are architectural precedent only;
depending on a Native implementation would give the graph the wrong owner.

JVM's `core:deserialization.common.jvm` is the dependency-role precedent for a source element
shared across frontend and later compiler phases. The .NET carrier is not placed there: its facts
come from foreign CLR import rather than Kotlin/JVM metadata deserialization, and depending on the
compiler-level CLR loader from `core` would invert the repository dependency direction. A narrow
compiler-level import module preserves the precedent without coupling `core` to a target frontend.

### Backend

The backend owns transformations and emission that begin with Kotlin IR or retained physical
bindings. It does not own selected dependency discovery, foreign declaration semantics, KLIB
library identity, or C# source-tooling policy merely because it currently produces related bytes.

### Configuration and CLI

Configuration owns target/profile/product validation, not rendered core-library declarations or
application layout. CLI owns orchestration and content roots; Gradle/packaging owns copying and
deployment only after compiler product validation.

## Extraction order and progress

### 1. Retained foreign-declaration carrier — completed

Completed on `dotnet`: `compiler:dotnet.imports` now owns the versioned exact-row carrier,
`compiler:fir:fir-dotnet` owns foreign Kotlin projection and lazy FIR symbols, `cli-dotnet`
only installs the provider and target FIR2IR extensions, and `backend.dotnet` only consumes the
retained linkage.

Extract the existing self-validating carrier from `backend.dotnet` into
`compiler:dotnet.imports`. Preserve direct references to the selected objective assembly and rows:
copying only tokens or display strings would require a second graph lookup and would weaken exact
classpath identity. Add explicit carrier protocol versioning without changing the admitted
declaration grammar or physical ABI.

Move the foreign CLR provider and its Kotlin nullability projection together into
`compiler:fir:fir-dotnet`. Keep objective loading in `frontend.common.dotnet`, session composition
in `cli-dotnet`, and IR binding in `backend.dotnet`.

Exit conditions:

- no FIR provider imports backend implementation types;
- no backend binder imports FIR symbol-provider internals;
- every imported callable/property carries exact producer-owned physical linkage;
- unsupported or stale carrier forms fail structurally; and
- cross-module foreign calls remain independent of display names.

Evidence: the focused carrier test rejects wrong owners and copied TypeDef, MethodDef, Property,
getter, and setter rows; dependency analysis reports no production-dependency correction for either
new module; the strict 925-test .NET gate is green. A targeted four-module rebuild after extraction
completed with 287 of 292 tasks already up to date, so the new seams do not force a monolithic
compiler rebuild for an ordinary downstream edit.

### 2. Kotlin library and physical ABI serialization

Move KLIB-in-DLL resource handling and physical ABI models/codecs behind a neutral .NET
library/serialization owner when frontend, tooling, or packaging needs the second consumer. IR
collectors may produce records but do not own the reusable format merely because they produce it.
The future Analysis API/IDE view is one such consumer: it must query that shared format rather than
importing backend packages or duplicating physical name and placement rules.

### 3. C# implementation manifest

Separate the manifest model/codec from the backend IR collector into a shared interop/ABI owner.
Roslyn tooling, compiler production, and validators must consume one versioned schema without a
frontend or tool importing backend implementation packages.

The same boundary applies to explicit C# export before generic, member, or inheritance export
expands: selector resolution, complete-family admission, and the host-facing export plan move out
of `DotNetIlEmitter` into a precisely named export/interop owner. The backend retains IR-to-CIL
wrapper construction, physical collision validation, and bridge binding. The current emitter-local
model is provisional POC debt, not mature-target precedent.

### 4. Runtime and stdlib product descriptions

Move neutral artifact descriptions and product resource catalogs toward distribution/shared target
configuration when Gradle, CLI, or packaging becomes an independent consumer. Keep target-profile
policy distinct from backend textual rendering and from deployment layout. Follow
[`../decisions/runtime-and-stdlib-ownership.md`](../decisions/runtime-and-stdlib-ownership.md).

### 5. Product handoff and application layout

Separate backend transformation/emission from CLI/Gradle layout only when the handoff has a typed
artifact and owns validation. Do not create a packaging abstraction just to relocate file-copying
code.

These extractions may interleave with bounded feature work. Module movement without a real new
consumer or enforceable direction is mechanical churn.

## Responsibilities that remain in the IR backend

- backend context and IR-to-CLR type mapping;
- target lowerings and synthetic compiler ABI construction;
- intrinsics and runtime-call selection;
- CIL instruction, stack, control-flow, metadata, and textual diagnostic production;
- MethodImpl and physical slot emission;
- backend-owned runtime/stdlib assembly construction; and
- compilation orchestration from lowered IR to validated backend products.

Large coordinators such as `DotNetBackend.compile` or `DotNetIlEmitter` are reviewed for mixed
ownership, but size alone does not authorize extraction. A split component must own a coherent
state transition or validation boundary.

## Responsibilities that must not drift back into the backend

- selected assembly graph and objective PE parsing;
- foreign Kotlin type/contract/nullability policy;
- IDE-side reconstruction of physical names, owners, properties, or events;
- generated compiler configuration and language-target identity;
- general KLIB library loading/serialization;
- reusable C# manifest decoding;
- reusable C# export selection, admission, and host-facing model construction;
- CLI content-root identity;
- Gradle variant/product publication; and
- Common stdlib source algorithms.

## Alternatives rejected

- **Move every non-emitter file into one shared module.** This recreates the same grab bag.
- **Copy `frontend.common.jvm` literally.** Its dependency role is precedent; Java abstractions are
  not the CLR model.
- **Move Kotlin policy with attribute decoders.** Valid evidence and Kotlin meaning have different
  owners.
- **Put the carrier in `frontend.common.dotnet`.** `DeserializedContainerSource` is compiler
  transport state; making the objective PE/ECMA-335 reader depend on compiler containers would
  erase its pure physical-evidence boundary.
- **Put the carrier in `core:deserialization.common.dotnet`.** Unlike JVM Kotlin binary source
  elements, the carrier retains types from the compiler-level foreign CLR loader. A `core` module
  cannot depend upward on that compiler module, and duplicating the physical model would create two
  authorities.
- **Retain only tokens, names, or copied signatures.** Tokens are scoped to a selected assembly
  image and names are not identities. Rebinding those snapshots later would restore the second
  classpath lookup the carrier exists to prevent.
- **Leave FIR policy in `cli-dotnet`.** CLI constructs sessions and supplies selected inputs; it
  does not own foreign Kotlin declaration semantics merely because it installs the provider.
- **Introduce package boundaries without dependency enforcement.** Useful for navigation, but not
  a substitute when a coherent module seam exists.
- **Split large constructors/classes to reduce size.** This creates abstractions without
  responsibility.
- **Pass anonymous Booleans instead of target identity.** This erases which platform contract
  justifies a capability.
- **Move a mixed configuration file wholesale.** Shared file history does not give all contained
  concerns one owner.

## Architecture completion gates

- every production concern has one named owner and package consistent with that owner;
- frontend consumers have no conceptual dependency on backend implementation details;
- objective metadata, Kotlin policy, physical binding, serialization, interop tooling, and
  packaging remain separate dependency roles;
- static dependency checks enforce the loader and configuration boundaries;
- extracted carriers validate themselves and have explicit producer/consumer versioning;
- behavior and artifact formats remain unchanged unless a separately accepted ADR changes them;
  and
- compilation and test performance are measured when a module split could change classpath or
  configuration costs.

The programme is complete when the open shared carriers have real owners, not when every large
file has been split or every target concern has received its own Gradle module.
