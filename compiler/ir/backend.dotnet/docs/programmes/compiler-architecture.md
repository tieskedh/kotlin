# .NET compiler architecture programme

- Status: **Active — retained foreign-declaration carrier is the next extraction seam**
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
- Kotlin interpretation of foreign evidence: FIR-owned .NET integration, in
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

FIR owns Kotlin projection and lazy symbol policy. It may consume the loader and a neutral retained
declaration carrier. It must not import backend-only CIL, lowering, runtime construction, or
physical emitter state.

### Backend

The backend owns transformations and emission that begin with Kotlin IR or retained physical
bindings. It does not own selected dependency discovery, foreign declaration semantics, KLIB
library identity, or C# source-tooling policy merely because it currently produces related bytes.

### Configuration and CLI

Configuration owns target/profile/product validation, not rendered core-library declarations or
application layout. CLI owns orchestration and content roots; Gradle/packaging owns copying and
deployment only after compiler product validation.

## Remaining extraction order

### 1. Retained foreign-declaration carrier

Create a neutral carrier for the selected assembly, TypeDef, member, structural signature, and
other physical facts that must survive FIR2IR. It must be consumable by FIR and backend without
either depending on the other's implementation module.

Then move the foreign CLR provider into a FIR-owned .NET module. Keep objective loading in
`frontend.common.dotnet` and IR binding in `backend.dotnet`.

Exit conditions:

- no FIR provider imports backend implementation types;
- no backend binder imports FIR symbol-provider internals;
- every imported callable/property carries exact producer-owned physical linkage;
- unsupported or stale carrier forms fail structurally; and
- cross-module foreign calls remain independent of display names.

### 2. Kotlin library and physical ABI serialization

Move KLIB-in-DLL resource handling and physical ABI models/codecs behind a neutral .NET
library/serialization owner when frontend, tooling, or packaging needs the second consumer. IR
collectors may produce records but do not own the reusable format merely because they produce it.

### 3. C# implementation manifest

Separate the manifest model/codec from the backend IR collector into a shared interop/ABI owner.
Roslyn tooling, compiler production, and validators must consume one versioned schema without a
frontend or tool importing backend implementation packages.

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
- generated compiler configuration and language-target identity;
- general KLIB library loading/serialization;
- reusable C# manifest decoding;
- CLI content-root identity;
- Gradle variant/product publication; and
- Common stdlib source algorithms.

## Alternatives rejected

- **Move every non-emitter file into one shared module.** This recreates the same grab bag.
- **Copy `frontend.common.jvm` literally.** Its dependency role is precedent; Java abstractions are
  not the CLR model.
- **Move Kotlin policy with attribute decoders.** Valid evidence and Kotlin meaning have different
  owners.
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
