# .NET compiler responsibility audit

Status: first bounded correction implemented on 2026-07-31; behavior and
artifact formats are unchanged.

## Question

Which responsibilities currently placed in `backend.dotnet` actually belong
to the IR backend, and which are there only because the prototype was first
implemented in one module?

The default is to mirror the ownership and dependency direction of mature
Kotlin targets, including packages inside a module. A CLR-specific deviation
requires a concrete CLR constraint; prototype history, file size, or
constructor size is not such a constraint.

## Mature-target evidence

The mature targets do not put every target concern in their IR backend:

- JVM foreign-binary contracts and finders live in modules such as
  `core:compiler.common.jvm`, `core:deserialization.common.jvm`, and
  `compiler:frontend.common.jvm`, predominantly under
  `org.jetbrains.kotlin.load.java` and `org.jetbrains.kotlin.load.kotlin`.
  Kotlin projection and Java enhancement live under
  `org.jetbrains.kotlin.fir.java`; configuration uses
  `org.jetbrains.kotlin.config`; IR lowering and class generation use
  `org.jetbrains.kotlin.backend.jvm`.
- JS and Wasm keep target configuration in `js.config` and `wasm.config`,
  KLIB loading and serialization in `ir.serialization.js`, FIR/frontend
  concerns outside the code generator, and lowering/output in their backend
  modules.
- Native keeps target configuration in `native.config`, KLIB loading in
  `ir.serialization.native`, and IR/code generation in `backend.native`.
- The web and Native serialization modules retain some historical
  `backend.*` package names. They are evidence for dependency direction, not
  a reason to introduce the same package ambiguity in a new target.

CLI modules do import backend entry points because the CLI composes the
pipeline. That does not make PE parsing, foreign declaration loading, or
KLIB serialization code-generation responsibilities.

## Current dependency problem

There are 93 production Kotlin files in `backend.dotnet` and nine in
`cli-dotnet`. `cli-dotnet` currently imports backend types for four unrelated
reasons:

1. invoke the IR backend;
2. read target configuration and product descriptions;
3. parse and resolve existing CLR assemblies; and
4. load or write Kotlin library and interop ABI carriers.

The clearest inversion is `DotNetClrFirSymbolProvider`: all 58 of its current
`backend.dotnet` imports are CLR load or enhancement types. The frontend
pipeline separately imports the PE/classpath reader, managed-resource reader,
artifact descriptions, embedded-KLIB codec, and C# implementation manifest
codec. A frontend consumer therefore cannot select the foreign declaration
model without depending on the IR backend module.

## Complete backend production-file classification

This classification assigns every current production file once. A “mixed”
label is a request to split ownership before moving the file, not a proposed
generic layer.

### Objective CLR load and metadata model: 29

- `DotNetClrArrayRuntimeTypes.kt`
- `DotNetClrByRefLikeClassifier.kt`
- `DotNetClrConstructedTypeConstraintValidator.kt`
- `DotNetClrCustomAttributeDecoder.kt`
- `DotNetClrCustomAttributeNamedArgumentValidator.kt`
- `DotNetClrDelegateRuntimeTypes.kt`
- `DotNetClrMetadata.kt`
- `DotNetClrNominalConstraintValidator.kt`
- `DotNetClrNullableDeclarationResolver.kt`
- `DotNetClrNullableEffectiveAccessibility.kt`
- `DotNetClrNullableEvidenceApplicator.kt`
- `DotNetClrNullableGenericParameterEvidence.kt`
- `DotNetClrNullableMetadata.kt`
- `DotNetClrNullableTypeTransformApplicator.kt`
- `DotNetClrObsoleteMetadata.kt`
- `DotNetClrParamArrayMetadata.kt`
- `DotNetClrPhysicalTypeClassifier.kt`
- `DotNetClrPrimitiveTypeCatalog.kt`
- `DotNetClrResolvedConstraints.kt`
- `DotNetClrResolvedHierarchy.kt`
- `DotNetClrResolvedSignatures.kt`
- `DotNetClrSerializedAssemblyName.kt`
- `DotNetClrSerializedTypeName.kt`
- `DotNetClrSerializedTypeResolver.kt`
- `DotNetClrSignatureTypeAssignability.kt`
- `DotNetClrSpecialConstraintValidator.kt`
- `DotNetClrTypeAssignability.kt`
- `DotNetClrTypeResolver.kt`
- `DotNetManagedResourceReader.kt`

These files model or validate physical CLR facts. Two could not move in the
first correction unchanged:

- `DotNetClrSpecialConstraintValidator.kt` read `DotNetTarget`, whose
  then-current definition also owned a backend-only core-library renderer; and
- `DotNetClrConstructedTypeConstraintValidator.kt` composes that validator.

The target-configuration correction below gives them a non-backend profile
dependency. They move into the load module as part of that correction.
Replacing the target with an ad-hoc Boolean merely to enable the move remains
rejected.

### Mixed CLR evidence and Kotlin/FIR policy: 3

- `DotNetClrFlowNullabilityMetadata.kt`
- `DotNetClrImportedDeclaration.kt`
- `DotNetClrKotlinNullabilityProjection.kt`

`DotNetClrFlowNullabilityMetadata.kt` contains both objective decoders and
Kotlin-facing input/return qualifier selection. It must be split.
`DotNetClrKotlinNullabilityProjection.kt` explicitly owns Kotlin qualifier
policy and belongs with the FIR importer. `DotNetClrImportedDeclaration.kt`
combines retained frontend source records with IR/backend binding and must
not move as one file.

### Configuration and product descriptions: 3

- `DotNetConfigurationKeys.kt`
- `DotNetCoreLibraryProfile.kt`
- `DotNetStdlibSource.kt`

These are currently mixed too. `DotNetConfigurationKeys.kt` combines
configuration keys, target profiles, artifact identities, exports, and
configuration extensions. `DotNetCoreLibraryProfile.kt` combines target
facts with textual IL rendering. `DotNetStdlibSource.kt` is a compiler
product resource catalog consumed before code generation.

### Kotlin library, ABI, and interop carriers: 3

- `DotNetCSharpImplementationManifest.kt`
- `DotNetKotlinMetadataResource.kt`
- `DotNetLibraryAbi.kt`

Both large codec files combine reusable carrier models/codecs with IR
collection and backend binding. Their model/codec portions belong in later
.NET library/serialization or interop ownership; their IR producers remain
backend consumers of those models. The C# manifest is not part of PE loading
merely because it uses CLR signature vocabulary.

### IR backend context and lowerings: 36

- `DotNetBackendContext.kt`
- `DotNetExactCallableSymbols.kt`
- `DotNetFunctionReferenceSymbols.kt`
- `DotNetGenericInterfaceAbi.kt`
- `DotNetIrMangler.kt`
- `DotNetMainFunctionDetector.kt`
- `DotNetPropertyReferenceSymbols.kt`
- `DotNetRuntimeTypes.kt`
- `DotNetSharedVariablesManager.kt`
- `DotNetTypedArgumentsCallableSymbols.kt`
- `DotNetLoweringPhases.kt`
- every 25 production files under `backend.dotnet/.../lower`

These stay in `backend.dotnet`. Their types and state are defined in terms of
Kotlin IR, lowering order, or backend-only runtime capabilities.

### CIL generation and backend-owned platform construction: 18

- `DotNetCompilerAbi.kt`
- `DotNetIlAccessibility.kt`
- `DotNetIlAssembler.kt`
- `DotNetIlClassCodegen.kt`
- `DotNetIlCodegenSupport.kt`
- `DotNetIlEmitter.kt`
- `DotNetIlExpressionCodegen.kt`
- `DotNetIlIntrinsicMethods.kt`
- `DotNetIlMethodCodegen.kt`
- `DotNetIlMethodContext.kt`
- `DotNetIlType.kt`
- `DotNetMappedExceptions.kt`
- `DotNetNullableMetadata.kt`
- `DotNetPrimitiveArrays.kt`
- `DotNetRuntimeLibrary.kt`
- `DotNetRuntimeLibraryHelpers.kt`
- `DotNetStdlibLibrary.kt`
- `DotNetThrowableRuntime.kt`

These stay in `backend.dotnet` for this correction. Runtime and stdlib
artifact descriptions may later move, but construction of their physical IL
is code generation. `DotNetIlAssembler` remains adjacent to assembly
production until the CLI/backend product seam is separately designed.

### Mixed orchestration and packaging: 1

- `DotNetBackend.kt`

`DotNetBackend.compile` currently composes lowering, runtime/stdlib
construction, IL emission, assembly, cleanup, dependency copying,
runtimeconfig generation, and output selection. It remains in place during
the load-layer correction. Future extraction must follow a real producer and
consumer boundary; splitting the method only to reduce its length is
rejected.

## CLI production-file classification

- CLI entry and sequencing: `K2DotNetCompiler.kt`, `DotNetCliPipeline.kt`,
  `DotNetBackendPipelinePhase.kt`, and `DotNetPipelineArtifacts.kt`.
- Configuration: `DotNetConfigurationPipelinePhase.kt`.
- FIR integration: `DotNetClrFirSymbolProvider.kt`,
  `DotNetFrontendPipelinePhase.kt`, and `DotNetFir2IrPipelinePhase.kt`.
- Kotlin library serialization: `DotNetLibraryMetadataPipelinePhase.kt`.

The CLI remains the pipeline composer. The FIR provider should eventually
move to a FIR-owned .NET module, while the library metadata phase should
consume a .NET serialization/library module. Neither move is bundled into
the first load-layer correction.

## CLR evidence versus Kotlin policy

The load boundary ends at validated CLR evidence. For example, it may report:

- an attribute is exactly
  `System.Diagnostics.CodeAnalysis.NotNullWhenAttribute`;
- its constructor Boolean is `true`;
- it targets parameter zero;
- its blob and constructor are valid; and
- duplicate or ambiguous metadata was rejected.

It may not decide that this evidence creates the Kotlin contract
`returns(true) implies (parameter != null)`. Likewise, Roslyn nullable flags
may be decoded, selected by physical scope, and aligned with a signature in
the loader, but conversion to Kotlin nullable/not-null/flexible qualifiers is
FIR enhancement policy.

This distinction applies to `NotNullWhen`, `NotNullIfNotNull`,
`DoesNotReturn`, `DoesNotReturnIf`, `AllowNull`, `DisallowNull`,
`NotNull`, `MaybeNull`, `ParamArray`, `Obsolete`, generic constraints, and
`IsByRefLike`: objective identity/value/shape belongs below FIR; Kotlin type,
contract, symbol, and diagnostic effects belong at or above FIR.

## Implemented first correction

Create `:compiler:frontend.common.dotnet`, mirroring the dependency role of
`frontend.common.jvm` without copying its historical contents. Put the
objective CLR load model under `org.jetbrains.kotlin.load.dotnet`.

The first move includes the 27 objective files above which do not depend on
the current target-profile coupling, plus the objective decoder portion of
`DotNetClrFlowNullabilityMetadata.kt`. Kotlin nullability projection and its
input/return qualifier policy move under `org.jetbrains.kotlin.fir.dotnet`
inside the current CLI module as a package boundary; a separate FIR module is
later work. `DotNetClrImportedDeclaration.kt` stays mixed and documented for
the subsequent FIR slice.

`DotNetManagedResourceReader` must not import the Kotlin KLIB carrier
constant. Its classpath discriminator accepts the managed-resource name from
the library-loading caller. This preserves one physical PE read path without
making the loader own Kotlin library identity.

The new module initially had no compiler-project dependencies. The subsequent
target-configuration correction added only
`core:language.targets.dotnet`, which contains neutral target identity and
capability vocabulary. Its compilation still enforces the forbidden-dependency
rule:

- no FIR;
- no IR;
- no `backend.dotnet`;
- no CLI pipeline;
- no Gradle or packaging implementation; and
- no Roslyn tooling.

Java/JDK and Kotlin-stdlib facilities are sufficient for the selected slice.
`cli-dotnet` and `backend.dotnet` depend on the loader; the loader never
depends on either consumer.

The implemented move contains 28 source files: the 27 dependency-free
objective files from the inventory plus the objective decoder portion of the
mixed flow-nullability file. The Kotlin input/return enhancer was moved beside
the Kotlin nullability projector under `org.jetbrains.kotlin.fir.dotnet`.
`DotNetClrSpecialConstraintValidator` now receives the physical core-type
catalog it actually consumes instead of reaching through an internal
by-ref-like-classifier detail.

The classpath reader now requires its caller to provide the carrier resource
name. Its result is correspondingly `WithCarrier` or `WithoutCarrier`, not a
claim that the assembly was Kotlin-produced or is foreign. An adversarial
integration case embeds a differently named managed resource and proves that
the reader classifies it as carrier-free under the Kotlin carrier name and as
carrier-bearing only under the explicitly supplied name. This prevents both
`Kotlin.Metadata` identity and producer-language policy from leaking back into
the objective load layer.

## Attacked alternatives

### Move every non-emitter file into one target-common module

Rejected. It would reproduce the current grab bag under a new name and mix
configuration, FIR policy, KLIB serialization, interop manifests, and
packaging.

### Copy `frontend.common.jvm` literally

Rejected. JVM is the mature precedent for foreign-metadata ownership and
dependency direction, not a requirement to copy historical module contents
or Java-specific classfile abstractions.

### Move Kotlin nullability and contract policy with the attribute decoder

Rejected. A valid CLR attribute is evidence; the Kotlin type or contract
effect is a frontend policy choice. Combining them would make the new loader
own Kotlin semantics.

### Split `DotNetIlEmitter` or `DotNetBackend.compile` because they are large

Rejected for this correction. Size identifies review pressure, not a
responsibility boundary. An extracted component must own validation and have
a clear producer and consumer.

### Introduce package boundaries without a module boundary

Rejected for the first load slice. Packages improve navigation but do not
prevent the loader from importing backend code. The selected coherent slice
has a dependency-free closure, so a real module provides enforceable value
without gratuitous build complexity.

## Second correction: target configuration and dependency roots

The next concrete consumer of a non-backend target profile is the objective
CLR special-constraint validator. The target-profile/configuration boundary
is therefore no longer speculative.

The mature-target comparison is:

- JVM keeps `JvmTarget` and platform identity in
  `core:language.targets.jvm`, makes `compiler:config.jvm` consume that
  vocabulary, and keeps CLI content-root carriers in `cli-base`;
- JS keeps generated compiler keys and target configuration in `js.config`;
- Native keeps generated compiler keys and target configuration in
  `native.config`; and
- all three keep physical code-generation rendering outside their shared
  configuration models.

Kotlin/.NET already has independent target-identity consumers in CLI
configuration, CLR validation, FIR composition, backend lowering, runtime and
stdlib construction, physical ABI production, and Gradle target-framework
selection. That is enough to justify the JVM-shaped language-target seam,
rather than making target identity configuration machinery.

Kotlin/.NET follows that dependency direction with three deliberately
distinct owners:

- `core:language.targets.dotnet` owns `DotNetTarget`, parsing, the .NET
  platform marker, and only narrowly defined target capabilities that are
  consumed across compiler layers;
- `compiler:config.dotnet` owns executable eligibility, library-profile
  compatibility, and generated primitive compiler keys/accessors for output,
  assembly name, product kind, and target; and
- `cli-base` owns `DotNetClasspathRoot`, because content roots are CLI
  composition carriers rather than target-profile facts.

Package placement mirrors the same mature-target boundary:
`DotNetTarget` is beside `JvmTarget` in `org.jetbrains.kotlin.config`,
`DotNetPlatform`/`DotNetPlatforms` live in
`org.jetbrains.kotlin.platform.dotnet`, and generated configuration keys and
policy extensions stay in `org.jetbrains.kotlin.config`. There is no CLR
constraint that justifies a backend or extra nested configuration package.

The platform marker deliberately remains one unversioned Kotlin/.NET
platform. `net48`, `netstandard2.0`, and `net10.0` are target-framework/API
contracts selected through compiler configuration; they do not become three
Kotlin language or Analysis API platform identities. This preserves the
accepted single-platform Gradle model while keeping the target value
independently consumable.

The language-target module depends only on the shared language-target model.
The new configuration module depends on it and `compiler:config`. The CLR
load module depends only on the language-target module so that the
constructed-type special-constraint validators can consume the exact profile
and its focused by-ref-like capability rather than a backend renderer or an
ad-hoc caller-supplied Boolean. `cli-dotnet` and `backend.dotnet` consume both
target vocabulary and configuration directly.

`DotNetTarget` no longer exposes a backend core-library renderer. The backend
owns the exhaustive mapping from `DotNetTarget` to
`DotNetCoreLibraryProfile`; textual `.assembly extern`, target-framework
attribute, and custom-attribute rendering therefore remain code generation.
Product kind, runtime identifier, deployment packaging, and future NuGet
asset selection remain separate axes rather than accumulating on the target
enum.

`DotNetConfigurationKeys.kt` is split rather than moved wholesale. Runtime
and stdlib identities, library artifact descriptions, export selectors,
external-library/friend models, their keys, and the produced-artifact
projection remain with their current backend consumers until the
serialization, interop, and product seams are designed.

The content-root correction replaces the accidental use of
`JvmClasspathRoot` for CLR DLLs and .NET metadata inputs. Its placement mirrors
`JvmContentRoots.kt`: `DotNetClasspathRoot` and its configuration helper live
under `org.jetbrains.kotlin.cli.dotnet.config` in `cli-base`, while the .NET
pipeline alone interprets those roots as managed assemblies or ordinary
Kotlin libraries.

The correction exits only when:

- target identity, platform identity, target/profile parsing, and primitive
  keys use the mature-target packages and import no backend type;
- the CLR load module imports neither FIR, IR, backend, nor CLI code;
- the two target-coupled CLR constraint validators live with the physical CLR
  models they validate;
- `cli-dotnet` contains no `JvmClasspathRoot` reference; and
- the existing profile, constraint, CLI, assembly, and runtime tests remain
  behaviorally unchanged.

### Attacked alternatives for the second correction

#### Move the whole backend configuration file

Rejected. It would make a foundational configuration module own
serialization artifacts, interop selectors, foreign-library state, and
compiler/runtime distribution identities merely because they currently share
one file.

#### Put textual core-library facts on `DotNetTarget`

Rejected. Target compatibility and executable capability are pre-FIR facts;
IL assembly references and custom-attribute blobs are backend rendering. An
exhaustive backend mapping preserves type safety without reversing ownership.

#### Pass `supportsByRefLikeGenerics` as a Boolean

Rejected. That would erase which API/runtime contract is being validated and
would make later profile additions silently inherit an arbitrary capability.
The validator consumes the authoritative profile enum and a focused
target-capability extension owned beside it.

#### Put `DotNetClasspathRoot` in `config.dotnet`

Rejected. That would force target configuration to depend on CLI content-root
infrastructure. Mature JVM placement shows that a target-specific root can
live in `cli-base` without making it a target-profile model.

#### Keep `DotNetTarget` inside `config.dotnet`

Rejected. The target identity already has independent consumers above and
below configuration. Owning it in `config.dotnet` would make the objective CLR
loader transitively depend on compiler-configuration machinery and would
understate the target value's role.

#### Encode product, runtime, and packaging policy on `DotNetTarget`

Rejected. The current enum selects a target-framework/API contract. Library
versus executable, future runtime identifiers, and package asset selection are
orthogonal decisions. Only capabilities that genuinely alter multiple
compiler layers belong beside target identity.

## Subsequent architecture extraction order

This is the order for later ownership corrections when a concrete consumer
requires them. It is not a mandate to exhaust every extraction before
continuing bounded feature work; package or module movement without a new
producer/consumer boundary would be mechanical churn.

1. Create a shared retained-declaration carrier seam, then move the foreign
   CLR provider into a FIR-owned .NET module while leaving IR binding in the
   backend. The carrier must not be owned solely by the FIR implementation
   module: mirror JVM's `deserialization.common.jvm` dependency role so FIR
   and backend can consume it without depending on one another.
2. Split KLIB-in-DLL and physical ABI models/codecs from their IR producers
   into .NET library/serialization ownership.
3. Split the C# implementation manifest codec/model from its backend IR
   collector into a shared interop/ABI owner.
4. Separate backend transformation/emission from CLI/Gradle application
   layout only when their artifact handoff owns validation.

The Common collections programme may proceed between these corrections. Its
next bounded slice is the exact Common/actual dependency closure for a
concrete list product; it must consume the load/FIR boundary established here
rather than pulling foreign metadata ownership back into the backend.

Structured CIL/direct PE emission remains a later programme. It is not a
prerequisite for correcting who owns already-existing CLR metadata.
