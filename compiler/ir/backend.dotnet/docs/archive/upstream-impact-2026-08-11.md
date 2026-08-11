# Upstream integration record — 2026-08-11

## Scope and accounting

This snapshot reviews the exact pending range:

```text
0e8c5f3f53f0ed2af01c6165d5a5ec7d8f58ba54..d78e4a4c1465c00475b8019654b5905124dc30a6
```

The range contains 170 commits. Every subject and changed-path set was
accounted for. Patches were then inspected for shared compiler, FIR/FIR2IR,
IR, KLIB, inline, reflection, export, CLI, Build Tools API, Gradle, stdlib,
test-infrastructure, and IDE contracts which can affect Kotlin/.NET. Git and
the exact range above are the reproducible exhaustive ledger; this snapshot
retains only the consequences that remain useful after integration.

No upstream commit changes a Kotlin/.NET-owned source path. The target changes
1,015 paths and upstream changes 1,247 paths from the common base. Nine paths
are changed by both sides:

- generated compiler-argument and Build Tools API baselines;
- `Fir2IrVisitor.kt` and the shared FIR2IR test generator;
- `NonLinkingIrInlineFunctionDeserializer.kt`;
- `LauncherScriptTest.kt`; and
- three KGP API/plugin/metrics files.

The pre-rebase virtual merge has one textual conflict, in
`NonLinkingIrInlineFunctionDeserializer.kt`. Upstream replaces ad-hoc
`IrModuleFragmentImpl(ErrorModuleDescriptor)` construction with the shared
`IrErrorModuleFragment`; the target independently expanded the same detached
deserializer for selected-dependency inline bodies. The semantic resolution is
to retain the target deserializer and use the shared sentinel for each dummy
file. The other eight overlaps merge textually and still require generated-
owner or semantic inspection after rebase.

## Shared contract and reverse-dependency audit

The range changes four shared contract families that warranted more than a
path-overlap check.

### IR module ownership

`f4942fc7f2` makes `KotlinIrLinker` create one `IrModuleFragment` and passes it
into every module deserializer. `9847127cff` adds the shared
`IrErrorModuleFragment` for the exceptional places which intentionally have no
real module. The .NET target implements no `KotlinIrLinker`,
`IrModuleDeserializer`, or `BasicIrModuleDeserializer` subclass, so their
constructor changes have no target-owned implementor. The target does extend
the shared non-linking inline deserializer and must adopt the shared sentinel
without turning that dummy fragment into library identity.

### KLIB metadata and loading

`ba7c1ef136` and `c21fe0f9bd` remove obsolete public package-fragment factory
entry points. The target has no call site. The Native/shared loading series
`94e6245399`, `1e7bc9908f`, `c7f9ee3402`, `c9a0fbc09c`, `5fec1552b7`, and
`3c8f5d820d` makes explicitly selected include/export/cache/friend libraries
part of one loaded graph and validates selections by canonical path. The
target does not consume those Native options, but its existing embedded-DLL
loader and friend validation already follow the applicable invariant: one
canonical physical DLL identity, one selected graph, and no path-only grant of
Kotlin visibility.

### Shared FIR and fake overrides

`e95f386863` restores the receiver/intersection-cast workaround and
`bd5406efdd` extends the same visitor for JS dynamic array increments. The
rebase must preserve those shared conversions beside the target's independent
prefer-actual exhaustive-when lookup. `36f8d97ab0` again generates static fake
overrides for every backend, with `b5ce15a76b`, `959249cbb6`, `878fc855b6`,
and `9960101a6e` tightening Java static/non-static and interface cases. The CLR
importer currently exposes no imported static member as an inheritable FIR/IR
member, so no target rule changes. Its instance fake overrides and target
`IrExternalOverridabilityCondition` continue through the shared builder and
must remain green.

`d39a104333` adds partial-linkage cases where a dependency change introduces
abstract function, property, fun-interface, and intersection fake-override
obligations. Kotlin/.NET still deliberately reports `isSecondStage = false`
until common partial-linkage diagnostics are registered. These tests become
required evidence for that future integration; they are not a reason to add a
target-private linkage repair.

### Generated and public integration contracts

`c19870d679` changes generated JS export arguments and therefore overlaps the
shared argument/BTA baselines which also contain .NET entries. KGP changes for
Gradle 9.7, deprecated snapshot removal, isolated projects, and metrics overlap
three files extended by the .NET target. None changes the .NET DSL contract,
but all target entries must survive regeneration and the affected owners must
compile after rebase. `8214ea351d` and `d5a8146100` make launcher Kotlin-home
and classpath handling safe for spaces; the target's launcher tests inherit
that correction and must retain their .NET cases.

## Lasting directions normalized into active owners

### Inline artifact content remains producer-owned

`913edff7bd` prevents JVM header mode from stripping bodies in an inline
scope. Wasm commits `faa44ff3a7`, `dbc3457687`, and `c58ec030a4` remove test
runners and language-feature overrides whose only job was forcing what is now
the normal IR-inliner mode. The applicable .NET rule is unchanged but
strengthened: producer artifacts retain executable inline bodies under their
own KLIB contract, ordinary semantic tests use the production default, and
the disabled/intra-module/full matrix exists only for the supported physical
compiler-ABI paths. A CLR fallback body or header-like facade never replaces
the KLIB body.

### Export is a dependency-aware projection

JVM commit `396844b28e` proves that host-visible non-null metadata is not
runtime enforcement: a boxed value-class parameter exposed to Java also gets
the ordinary Kotlin entry check. Swift Export commits `5d8cdb5dbb`,
`943ce963ce`, and `11308ebbfc` use exact existential capabilities, fully
qualified marker identities, and explicit full/transitive/excluded module
modes. An excluded dependency which remains in an exported inheritance or
signature graph is translated as a stub rather than replaced with an
unrelated bottom/top type. The reorganized inheritance and coroutine export
tests (`19ca0eec2d`, `d78e4a4c14`) keep each cross-language scenario
independently executable.

The C# export ADR now owns the corresponding rules: Roslyn attributes are
additive to entry checks; admission operates on the complete dependency graph;
fully qualified Kotlin identity selects generated names; and an excluded or
unsupported dependency produces a truthful stub or rejects the export rather
than weakening the signature to `object`.

### Metadata, not host binaries, owns Kotlin IDE reconstruction

The annotation/stub series `e6b11905f5`, `8f53d6a30a`, `780ff792a3`,
`b57558c94d`, and `0f5ad68f6c` reconstructs compiled annotation properties as
constructor parameters and reads their defaults from Kotlin metadata. The
light-class consolidation, decompiler/stub cleanup, and
`79ce5a8266` Analysis API substitutor change do not add a .NET API, but confirm
the existing placement: a future .NET IDE view consumes embedded KLIB plus the
shared physical ABI model; it does not infer Kotlin annotation constructors or
defaults from CLR properties/custom attributes.

### Export and build operations get reusable input models

`18a4910a12` moves a KLIB manifest fact down to `util-klib`, and
`6129254eb0` gives standalone TypeScript export and BTA/tests one KLIB-loader
input helper. `7daa42a125` gives JS KLIB compilation and linking separate BTA
functional suites, argument partitions, and output roots. These are ownership
precedents rather than reusable JS code: future C# export input construction
and .NET BTA operations belong outside `backend.dotnet`, consume the
self-describing DLL as one selected Kotlin/CLR artifact, and state their real
operation/output model instead of masquerading as JS linking or JVM
compilation.

The Gradle caching and isolated-project series (`280307ec38` through
`400283bfb0`, plus `ad1ec26192`) moves shared mutable build coordination into
thread-safe Build Services with stable keys. Future .NET resolution and
incremental caches therefore remain build-session/project-isolation safe;
compiler IR and selected assemblies do not enter process-global KGP state.

## Screened directions with no current target action

The remaining commits cover Symbol Light Classes test consolidation; Native
LLDB retries, cinterop headers, archives, caches, devirtualization and Apple
tooling; JS arithmetic, console, browser metrics, AST and test-output fixes;
Wasm DWARF/runtime updates; JVM/JDK-only diagnostics and removal of obsolete
legacy codegen tests; KAPT, Lombok/Compose/scripting/plugin-specific changes;
Gradle publication, cross-compilation, secondary-variant and test-federation
maintenance; PSI deprecation normalization; and test-data-only cleanups.

They were screened by both subject and paths. None changes an accepted
Kotlin/.NET scalar, array, generic, enum, annotation, exception, coroutine,
reflection, initialization, stdlib, target-profile, or self-describing-DLL
decision. Native test retry is specifically not precedent for hiding a flaky
.NET gate, and JS/Native host-specific representations do not authorize a new
CLR exception to Common semantics.

## Integration and verification outcome

Pending at the time of the pre-rebase review. The exact rebase, range-diff,
generated-owner checks, focused boundary checks, strict aggregate audit, and
preservation of the 147 user-owned EOL-only IL baseline changes are recorded
here after mechanical integration.
