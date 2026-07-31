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

These files model or validate physical CLR facts. Two cannot move in the
first correction unchanged:

- `DotNetClrSpecialConstraintValidator.kt` reads `DotNetTarget`, whose current
  definition also owns a backend-only core-library renderer; and
- `DotNetClrConstructedTypeConstraintValidator.kt` composes that validator.

They remain an explicit short-lived outlier until target configuration is
separated. Replacing the target with an ad-hoc Boolean merely to enable a
move is rejected.

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

The new module deliberately has no compiler-project dependencies. Its
compilation therefore enforces the forbidden-dependency rule:

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

## Subsequent architecture extraction order

This is the order for later ownership corrections when a concrete consumer
requires them. It is not a mandate to exhaust every extraction before
continuing bounded feature work; package or module movement without a new
producer/consumer boundary would be mechanical churn.

1. Extract target-profile and configuration ownership, then move the two
   target-coupled CLR constraint validators.
2. Move the foreign CLR provider and retained declaration-source model into
   FIR-owned .NET packages/module, leaving IR binding in the backend.
3. Split KLIB-in-DLL and physical ABI models/codecs from their IR producers
   into .NET library/serialization ownership.
4. Split the C# implementation manifest codec/model from its backend IR
   collector into a shared interop/ABI owner.
5. Separate backend transformation/emission from CLI/Gradle application
   layout only when their artifact handoff owns validation.

The Common collections programme may proceed between these corrections. Its
next bounded slice is the exact Common/actual dependency closure for a
concrete list product; it must consume the load/FIR boundary established here
rather than pulling foreign metadata ownership back into the backend.

Structured CIL/direct PE emission remains a later programme. It is not a
prerequisite for correcting who owns already-existing CLR metadata.
