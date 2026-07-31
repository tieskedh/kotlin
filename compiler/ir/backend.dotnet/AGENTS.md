# Kotlin/.NET target bootstrap contract

This file is the self-contained contract an agent must read before changing
Kotlin/.NET code. It contains the rules needed to start safely; feature
rationale and representation detail belong in the linked ADRs.

The target is a pre-ABI Kotlin-to-CIL prototype. Objective CLR loading lives in
`compiler/frontend.common.dotnet`, FIR policy is composed through the .NET
frontend/CLI modules, IR lowering and CIL production live in
`compiler/ir/backend.dotnet`, and target vocabulary/configuration live above
those compiler layers.

Read:

- [`STATUS.md`](STATUS.md) for the current head, last full gate, active work,
  blockers, and next bounded tasks;
- [`docs/README.md`](docs/README.md) for the decision/programme/archive index;
  and
- the owning ADR before changing a representation, public physical surface,
  metadata contract, or artifact boundary.

## Authority and decision order

Implementation authority descends in this order:

1. accepted Kotlin language and Common stdlib semantics;
2. repository-wide compiler contracts and generated-source ownership;
3. accepted Kotlin/.NET ADRs;
4. current status, the way forward, and active programme gates;
5. verified historical review evidence.

Common Kotlin declarations and the stdlib generators are authoritative for
Kotlin behavior. A .NET source file supplies narrow `actual` declarations and
irreducible host operations; it does not fork a Common algorithm merely
because a BCL equivalent exists.

Kotlin metadata is authoritative for the logical Kotlin declaration. CLR
metadata is authoritative for the physical CLR declaration. For a
Kotlin-produced DLL, retain the complete KLIB contract and derive a truthful
CLR/Roslyn view in addition to it. For a foreign DLL, exact CLR metadata and
standard attributes are evidence from which FIR may derive a Kotlin view.
Never infer authoritative KLIB identity or Kotlin-only split-interface/C#
implementation-manifest contracts from CLR annotations.

This document records the target authors' working position. Do not describe a
choice as a Kotlin core-team decision unless the repository contains such a
decision.

## Module and dependency map

| Owner | Responsibility |
| --- | --- |
| `:core:language.targets.dotnet` | One logical .NET platform and the target-framework vocabulary |
| `:compiler:config.dotnet` | Generated primitive compiler keys and target/product policy |
| `:compiler:frontend.common.dotnet` | Objective PE/ECMA-335 facts, resolution, and physical evidence |
| FIR-owned .NET code | Kotlin types, symbols, contracts, enhancement, and diagnostics |
| `:compiler:cli:cli-base` | Neutral `.NET` content-root carrier |
| `:compiler:cli:cli-dotnet` | Pipeline sequencing and application of configuration |
| `:compiler:ir:backend.dotnet` | IR context, lowerings, intrinsics, CIL mapping/emission, and backend products |
| Kotlin library/ABI infrastructure | Embedded KLIB and physical ABI models/codecs |
| Gradle/packaging layers | Variant configuration, installation, dependency copying, and layouts |
| Roslyn authoring project | C#-facing source generation/analyzers over explicit interop contracts |

Enforce these directions:

- language-target code imports no compiler, FIR, IR, backend, CLI, Gradle, or
  Roslyn layer;
- `config.dotnet` imports no CLR loader, FIR, IR, backend, or CLI layer;
- `frontend.common.dotnet` imports neither compiler configuration nor FIR, IR,
  backend, CLI, Gradle, or Roslyn code;
- objective CLR parsing never creates Kotlin types, contracts, symbols, or
  diagnostics;
- FIR and backend may consume a neutral retained-declaration carrier, but
  neither may own a carrier required by the other;
- CLI orchestrates owners and does not become the owner of metadata, ABI, or
  codegen models;
- backend code must not be imported merely to obtain a target enum, content
  root, physical CLR model, or frontend validator; and
- `.NET` roots are never represented as `JvmClasspathRoot`.

Package placement mirrors the mature target that owns the same concern unless
the CLR creates a concrete different boundary. Do not introduce generic
domain/application/infrastructure packages or new Gradle modules solely to
shorten files or constructors.

See
[`docs/review/architecture-responsibility-audit.md`](docs/review/architecture-responsibility-audit.md).

## Target and product model

Kotlin/.NET has one unversioned logical Kotlin platform, `DotNet`. The target
framework is an independent configuration axis represented by
`org.jetbrains.kotlin.config.DotNetTarget`:

| Target | Applications | Libraries | May consume |
| --- | --- | --- | --- |
| `net48` | yes | yes | exact `net48` and `netstandard2.0` |
| `netstandard2.0` | no | yes | exact `netstandard2.0` |
| `net10.0` | yes | yes | exact `net10.0` and `netstandard2.0` |

`net48` and `net10.0` never consume one another. Product kind, future runtime
identifier, packaging, and target framework remain orthogonal. Do not put
product/layout/textual-CIL facts on `DotNetTarget`.

`-Xdotnet-target={net48|netstandard2.0|net10.0}` defaults to `net48`.
Configuration rejects invalid values and Standard executables before FIR or
codegen. Profile selection precedes lowerings and controls legal core-library
references, target metadata, runtime/stdlib variants, assembly writing, and
dependency compatibility. Kotlin semantics remain invariant even when
profile-specific CIL differs.

`net10.0` executable requests produce a DLL plus runtime configuration for
`dotnet exec`; do not invent a self-hosting modern ILAsm executable.
`netstandard2.0` produces only portable library DLLs. Framework and modern
ILAsm are target/profile writers and compatibility oracles, not interchangeable
requirements for every profile-specific feature.

Compiler arguments, Gradle compiler options, platform identity, target
attributes, and target/compilation ownership are generated or target-owned as
recorded in the integration ADRs indexed by
[`docs/README.md`](docs/README.md). Do not restore handwritten generated
argument/option models.

## Pre-ABI and publication policy

Nothing has shipped and no public Kotlin/.NET ABI 1 exists. Until an explicit
freeze is recorded:

- correct prototype binaries, names, metadata, runtime types, and layouts
  instead of preserving mistakes;
- move every producer, consumer, runtime, and tool together;
- bump and validate the relevant versioned schema when a physical contract
  changes;
- reject stale artifacts explicitly; and
- do not add compatibility shims for unpublished identities.

Developer-mode declaration eviction may help incomplete frontend work reach a
diagnostic fixpoint. A library or stdlib publication with any eviction is an
error; no successful artifact may silently omit declarations or their
dependents. The endpoint is a located frontend diagnostic.

Every Kotlin library is one self-describing CLR DLL containing its private
KLIB payload and physical binding data. Do not emit, install, or publish a
standalone or sibling Kotlin/.NET KLIB. See
[`docs/decisions/adr-self-describing-dotnet-library-dll.md`](docs/decisions/adr-self-describing-dotnet-library-dll.md).

## Metadata and CLR interoperability

The objective loader reports selected assembly identity, metadata tables,
signatures, custom attributes, and validated physical relationships without
guessing producer language. FIR owns Kotlin-facing enhancement and stability
rules; backend binding consumes retained identities after frontend decisions.
See
[`docs/decisions/draft-adr-clr-importer-boundary.md`](docs/decisions/draft-adr-clr-importer-boundary.md).

Standard CLR/Roslyn attributes are the shared foreign-language vocabulary only
where their exact target, payload, and semantics are verified. Unknown,
malformed, inapplicable, or state-weakening evidence contributes no Kotlin
effect. In particular, Roslyn member-state attributes do not override Kotlin's
stricter smart-cast rule for mutable properties.

When CLR metadata can express a truthful view of Kotlin semantics, emit both
the KLIB contract and the standard CLR view. Nullable attributes help C# and
foreign import; they do not replace Kotlin nullability. CodeAnalysis contract
attributes can reconstruct only the exact effects they express; Kotlin-only
contracts and declaration identity remain in KLIB.

C# authoring and export surfaces are explicit opt-in interop products.
Properties, defaults, callable adapters, collisions, nullability, and
implementation manifests are not inferred from ordinary Kotlin source names.
See
[`docs/decisions/adr-csharp-interface-source-authoring.md`](docs/decisions/adr-csharp-interface-source-authoring.md).

Friend visibility combines Kotlin module authorization with exact CLR
`InternalsVisibleTo` identity. Metadata-public compiler ABI is not ordinary
source `public`. See
[`docs/decisions/adr-friend-assemblies-and-compiler-abi.md`](docs/decisions/adr-friend-assemblies-and-compiler-abi.md).

## Runtime and stdlib ownership

Physical product ownership is:

- `Kotlin.Runtime.dll`: compiler/runtime identities and services required by
  generated code;
- `Kotlin.Stdlib.dll`: ordinary Kotlin library declarations and algorithms;
  and
- the user assembly: declarations and initialization owned by that
  compilation.

Do not place an ordinary stdlib algorithm in the emitter or runtime. Do not
copy it into every consumer. Common/generated source is compiled once into
the profile-selected stdlib; target-private external helpers cover only
irreducible CLR operations.

The direct repository stdlib product and temporary packaged-source fallback
must use the same canonical source set and produce the same logical KLIB/IL
content. The fallback is a bootstrap cycle breaker, not a second
implementation or final distribution design. Installed selection treats each
profile's runtime/stdlib as one pair and copies their bytes unchanged beside
applications.

Collections follow exact Common generator/source dependency closures.
Kotlin collection identity is not replaced by BCL collection identity;
explicit BCL adapters are a separate interop programme. Common I/O owns EOF
and rendering semantics; `.NET` supplies only narrow actuals and the
`Console.ReadLine` host operation.

See
[`docs/decisions/draft-adr-target-stdlib-bootstrap.md`](docs/decisions/draft-adr-target-stdlib-bootstrap.md)
and
[`docs/review/common-collections-program.md`](docs/review/common-collections-program.md).

## Nullability model

Nullability uses a hybrid physical representation:

- a nullable non-erased CLR value position uses `System.Nullable<T>`;
- a nullable reference position uses the same CLR reference type plus truthful
  nullable metadata where representable;
- an erased `Any?`/object boundary represents both a boxed value and `null` as
  CLR object/null, not as a surviving `Nullable<T>` box;
- generic `T?` representation follows the type parameter's constraints and
  exact use-site requirements; and
- Kotlin-produced declarations retain their logical nullability in KLIB even
  when Roslyn-compatible nullable attributes are also emitted.

Do not globally map `T?`, `Any?`, or a nullable primitive to one CLR shape.
Boxing, unboxing, equality, type tests, arrays, generic constraints, returns,
and imported CLR enhancement must preserve the boundary at which nullability
is observed.

See the
[generic nullability ADR](docs/decisions/adr-hybrid-generic-nullability-and-covariant-returns.md).

## Core representation boundaries

- `System.Object` is the physical foundation for Kotlin `Any`; Kotlin-facing
  `equals`, `hashCode`, and `toString` semantics remain explicit compiler or
  runtime behavior. See
  [the `Any` foundation ADR](docs/decisions/system-object-any.md).
- Kotlin primitive arrays use Kotlin-owned wrapper identity around CLR
  storage. Do not expose raw CLR vectors as Kotlin array identity. See
  [the primitive-array ADR](docs/decisions/primitive-arrays.md).
- Variant/generic interfaces use the versioned split-interface/bridge model
  where one CLR interface cannot truthfully carry all Kotlin views. MethodImpl
  and effective interface maps are semantic ABI, not IL spelling trivia. See
  [`draft-adr-variant-interface-abi.md`](docs/decisions/draft-adr-variant-interface-abi.md).
- Interface default bodies are profile-aware. Do not simulate modern DIM into
  the Framework ABI or reject a Kotlin body without applying the accepted
  fallback policy. See
  [the interface-default ADR](docs/decisions/adr-profile-aware-interface-default-implementations.md).
- Function values use the selected erased `FunctionN` identity plus exact
  execution capabilities; callable and property-reference identity is a
  separate physical contract. See
  [`draft-adr-erased-callable-abi.md`](docs/decisions/draft-adr-erased-callable-abi.md).

Exact private lowering machinery is not automatically public ABI. Tests own
private field disambiguation, nested equality views, and conformance
mechanics; the documentation index identifies the implementation/verification
files awaiting relocation.

## Exception model

All throwable values remain the original `System.Exception` objects in one
physical CLR universe. Exact Kotlin or CLR identities are physical only when
truthful. Broad or non-physical Kotlin subtype relationships are enforced by
one versioned classifier used consistently by type tests, casts, catches, and
metadata consumers.

Kotlin `Throwable` state missing from `System.Exception`, especially
suppressed exceptions, is identity-associated runtime state on the original
object. Do not wrap, clone, translate, or mutate foreign extensibility
surfaces such as `Exception.Data`. Stacktrace operations preserve CLR
diagnostic facts and compose Kotlin suppressed-state semantics with
reference-identity cycle handling.

See the
[classified-exception ADR](docs/decisions/classified-clr-exceptions.md).

## Static placement and initialization

Objects and companions follow Kotlin/JVM first-active-use semantics on CLR
`.cctor` ownership. A plain object owns `INSTANCE`. A companion singleton
field lives on the selected enclosing static owner, using a non-generic holder
when a generic owner would otherwise create one singleton per construction.

Companion backing state and init blocks remain on the companion instance; the
selected owner's `.cctor` constructs it. This is the accepted CLR nested-type
delta from JVM field hoisting and is observable only under initialization
re-entrancy.

The **beforefieldinit decision** is to omit `beforefieldinit` for
Kotlin-initialized types so the CLR cannot run their initializer earlier than
Kotlin permits. Failed initialization preserves one Kotlin-visible failure
identity/state above CLR `.cctor` caching rather than exposing unstable
wrapper chains.

See the
[static-placement ADR](docs/decisions/adr-companion-static-placement-and-initialization.md)
and the
[initialization-failure ADR](docs/decisions/adr-kotlin-static-initialization-failures.md).

## Diagnostics and CIL production

Unsupported IR fails through a specific `dotNetUnsupported()` diagnostic
path. Never emit plausible fallback IL such as empty strings or zero values.
Do not let a known-invalid construction reach ILAsm or become JIT-poisoned;
interface/generic mapping mistakes can assemble successfully and fail only at
type load or dispatch.

Textual IL plus ILAsm is the accepted prototype production path. The endpoint
is a structured compiler-owned CIL/metadata model with deterministic text and
direct-PE sinks; do not add a sidecar merely to exchange one external process
for another. See
[`docs/decisions/draft-adr-il-assembly-pipeline.md`](docs/decisions/draft-adr-il-assembly-pipeline.md).

Any IL spelling not already golden-pinned must first be assembled and executed
in a temporary probe outside the repository. Verify physical metadata through
tables, reflection/interface maps, and real dispatch as appropriate; substring
checks alone are insufficient for semantic ABI.

## Contribution workflow

For every bounded semantic feature:

1. Start from authoritative Common Kotlin/source-generator behavior.
2. Document how JVM, JS, Wasm, and Native handle the concern.
3. Deviate only for a concrete CLR constraint, not a limitation of the current
   target implementation.
4. Attack the preferred design and state what a Kotlin-aligned target team
   would likely reject; do not manufacture core-team endorsement.
5. Amend the owning ADR/programme before implementing the representation or
   semantic choice.
6. Implement the complete bounded feature across every producer and consumer.
7. Test adversarial source, metadata, dispatch, profile, artifact, and runtime
   boundaries.
8. Commit and push the completed feature with its ADR and status update.

Also:

- preserve unrelated worktree changes and do not modify another branch;
- work directly on `dotnet`; do not create worktrees;
- never edit `*Generated.java`, generated configuration keys, or API baselines
  by hand—run the owning scoped generator and critically review its output;
- the Kotlin 2.5 bootstrap uses name-based destructuring `[a, b]` for
  data-like carriers; do not introduce positional `(a, b)` destructuring that
  the bootstrap cannot compile;
- do not pin a frontend-rejected source shape in an IL-text test; use the
  frontend diagnostic suite;
- do not broaden a feature to an adjacent parked programme merely because it
  becomes visible during implementation; and
- keep temporary probes, playgrounds, and IDE projects outside the repository.

Enums, annotation classes, value classes, reflection, broad inline/reified
support, coroutines, concurrency primitives, and broad KMP/Gradle product
integration remain separate programmes until `STATUS.md` or the way forward
selects one.

## Verification contract

The strict semantic commit gate is:

```text
./gradlew :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

Do not trust quiet Gradle success alone. Audit every JUnit XML file under:

```text
compiler/fir/fir2ir/build/test-results/dotNetTest/
compiler/tests-integration/build/test-results/dn/
```

`STATUS.md` owns the expected current file/test totals. Strict mode turns a
missing required toolchain or Smart App Control refusal into failure. The
short internal `dn` task name preserves Framework CLR/ILAsm path-length
budget; invoke the backend aggregate rather than treating `dn` as public API.

A Markdown-only ownership, history, rename, or index change does not require
the compiler gate. It does require a repository-reference audit, local-link
validation, whitespace/diff review, and confirmation that no semantic file is
staged.

Generate FIR-to-IR runners with:

```text
./gradlew :compiler:fir:fir2ir:generateTests
```

Do not use the repository-wide aggregate merely for this target. Generated
runners live under `build/tests-gen` and are not committed. To update an
IL-text golden, change the `.kt`, run the scoped test with
`-Pkotlin.test.update.test.data=true`, then read and assemble the resulting
`.txt`; generated goldens can faithfully preserve broken IL.

Focused compilation/tests are useful while iterating but never replace the
strict gate for a completed semantic feature. Before committing, verify that
status shows only intended files.

## Box tests

Like mature targets, Kotlin/.NET box tests execute on real runtimes. PSI and
LightTree compile the same target-owned corpus:

- `net10.0` produces a DLL and runs it with the signed `dotnet exec` host;
- `net48` produces an executable assembly loaded and invoked by the signed
  Windows PowerShell CLR 4 host; and
- no lane directly launches a fresh unsigned executable.

The shared `kotlin-dotnet-framework-toolchain` JUnit resource lock serializes
the physical Framework ILAsm/CLR4 lane because those external tools are
nondeterministic under unbounded fan-out. Do not serialize ordinary compiler
or modern-runtime work.

The IL-text suite compares text even without a toolchain and assembles every
supported golden with each available compatible ILAsm. Text equality does not
replace execution: add real runtime coverage for dispatch, exception,
initialization, reflection-map, cross-assembly, or profile behavior.

Adversarial coverage should include nullable/value/reference and widened
forms, hostile user implementations, empty/singleton/multiple boundaries,
separate producers/consumers, direct/fallback/installed products, malformed or
inapplicable foreign metadata, both parsers, and every supported runtime/profile
that can observe the feature.

## Modern .NET toolchain

The durable per-user toolchain lives under:

```text
%LOCALAPPDATA%\kotlinc-dotnet\toolchain\
```

It contains the pinned .NET 10 host/SDK/reference pack and modern CoreCLR
ILAsm. Provision or repair it with
`compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`.
Production CIL assembly does not depend on Roslyn.

Modern assembler discovery order is:

1. `KOTLIN_DOTNET_ILASM` for an exact `ilasm.exe`;
2. `KOTLIN_DOTNET_ROOT` containing `ilasm/` and `dotnet/`;
3. the durable per-user location above; and
4. legacy Framework ILAsm only where the selected profile permits it.

Modern C# integration discovers `csc.dll` and the reference pack from the same
toolchain root and invokes Roslyn through the discovered `dotnet` host.

A CoreCLR DLL needs a sibling runtime configuration and runs through
`dotnet.exe exec`. Prefer the signed host over direct execution of unsigned
test binaries.

Smart App Control can refuse to load freshly assembled unsigned content. A
normal optional lane reports that environmental inability as a visible skip;
the strict required-toolchain lane fails. Never perturb hashes, restructure a
program, weaken a test, or otherwise attempt to bypass the classifier. Valid
options are a host without SAC, a trusted signature, or a user-controlled OS
policy change.

## Documentation maintenance

- `AGENTS.md` owns only this bootstrap contract.
- `STATUS.md` owns current state and verification.
- `docs/review/way-forward.md` owns future gates and ordering.
- active programme files own one current workstream.
- ADRs own durable decisions, invariants, consequences, and rejected
  alternatives.
- Git owns chronological implementation history.
- tests/CI own executable evidence.
- `docs/archive` owns immutable snapshots and superseded history.

An ADR contains no current test count or commit log. A `draft-` filename must
agree with `Status: Draft`; accepted pre-ABI decisions are renamed and state
their freeze conditions. A semantic or ABI change updates its owning ADR in
the same feature commit.

When finishing a feature, update `STATUS.md` with the semantic head, fresh
gate, remaining blockers, and next bounded work. Keep `HANDOVER.md` as a
compatibility pointer only.
