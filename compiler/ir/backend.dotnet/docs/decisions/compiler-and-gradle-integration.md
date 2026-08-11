# ADR: Kotlin/.NET compiler and Gradle integration

- Status: **Accepted — pre-ABI**
- Date: 2026-07-27
- Scope: generated compiler arguments/options, built-in Gradle target,
  compilations, tasks, associations, and artifact publication

## Context

Kotlin's shared compiler-argument description is authoritative for generated
CLI classes, serialized argument metadata, and public Gradle compiler options.
Maintaining handwritten .NET models would create a parallel compiler API
before the target ships.

Not every CLI argument is a user-owned Gradle option. Target framework,
product kind, outputs, dependencies, stdlib selection, and friend
authorization participate in variant or task ownership and must not be
independently overridden after resolution.

The built-in target must invoke the actual .NET compiler, preserve Kotlin
Multiplatform behavior, and publish one self-describing DLL from one coherent
compilation.

Native supplies the closest target -> compilation factory -> task precedent,
but a .NET target framework is an API/runtime profile rather than a CPU/OS
machine target.

## Decision

### Compiler arguments are generated from the shared DSL

Declare a .NET argument leaf beneath the common KLIB-based arguments and
generate `K2DotNetCompilerArguments`, copying/freezing behavior, annotations,
defaults, and serialized metadata through the ordinary generators.

The target inherits common compiler and KLIB arguments. CLR-specific arguments
select physical output, target profile, friendship, and explicit interop
policy; they never redefine Common language semantics or KLIB behavior.

Absent repeatable values follow the same generated normalization as other
targets. Existing CLI spellings and absence semantics remain compatible until
deliberately revised through the shared description.

Every current .NET product emits a CLR assembly and library products serialize
KLIB into that assembly. The target nevertheless reports common
`isSecondStage = false` until its frontend/linker registers the common
partial-linkage diagnostics. Marking it true earlier would configure warning
names that do not exist. This is a temporary diagnostic-integration boundary,
not a claim that CLR emission performs no linking.

### Public Gradle compiler options are minimal and generated

Generate the public interface, default implementation, and argument-fill
helper from the same argument description. The interface extends
`KotlinCommonCompilerOptions` and adds only `moduleName` initially.

`moduleName` maps to the CLR assembly name and receives a target/project
convention. The target/compilation/task, not the public compiler-options block,
owns:

- target framework and product kind;
- destination and declared outputs;
- dependencies, classpath, and stdlib selection;
- compilation association and friend authorization; and
- packaging/application layout.

Explicit C# exports require a dedicated typed DSL before becoming public
Gradle API. Provisional raw selectors may pass through `freeCompilerArgs`; the
transport encoding is not frozen as a Gradle property.

### One immutable profile belongs to one target

A `.NET` Gradle target owns one immutable target framework. Every compilation,
dependency configuration, task, and outgoing variant derives the same value.
No task-level switch or free compiler argument may reinterpret a resolved
target.

Multiple profiles use multiple explicitly named targets. The public DSL is
conceptually:

```text
dotnet(targetFramework, name, configure)
```

Annual framework versions do not create one DSL method apiece.

All profiles remain `KotlinPlatformType.dotnet`. Profile-specific APIs use
source-set refinement or separate targets, never mutable task semantics.

### Reuse Kotlin Gradle target and compilation architecture

The implementation uses the established KGP components:

- a Kotlin-only target and preset/typed DSL entry point;
- a target-specific compilation factory and decorated compilation;
- generated compiler-option conventions;
- compile/runtime dependency configurations;
- compilation/source-set side effects for task creation; and
- the normal Gradle compiler runner with a distinct .NET compiler class.

Do not import Native host availability, cinterop, linker, cache, framework, or
architecture-container machinery. The shared lifecycle is precedent; the
machine-target product model is not.

### Compilation owns operational inputs and one DLL output

The compile task receives:

- the target's immutable framework;
- explicit library/application product kind;
- compilation-owned module name;
- self-describing DLL dependencies and friend paths;
- common/plugin options and source structure; and
- a declared output directory containing the canonical DLL and optional
  diagnostics.

The self-describing DLL is simultaneously the CLR artifact and Kotlin library
dependency. API and runtime variants publish that one task output under the
platform/profile attributes. Gradle provider wiring preserves producer task
ordering and configuration-cache safety without eager task evaluation.

Associated compilations use the producer DLL as both dependency and friend
input. The producer emits `InternalsVisibleTo` from the consumer's authoritative
module identity; no independent friend artifact is introduced.

### Compiler distribution owns compiler modules

The Gradle task invokes the embeddable compiler distribution, not repository
project classes. That distribution includes the .NET frontend/FIR checker in
the same module closure as other target checkers. KGP never repairs a partial
compiler distribution by adding compiler-internal modules to user classpaths.

Every production module extracted from that executable closure must also be
registered with the repository's central `CompilerModules` distribution
owner. A project dependency is insufficient evidence: repository tests can
see the dependency while the assembled `kotlin-compiler.jar` silently omits
it. The installed `kotlinc-dotnet` launcher must execute after each such split,
and the expected entry classes must be physically present in the assembled
compiler jar. This obligation applies equally to frontend adapters, FIR-to-IR
extensions, serializers, lowerings, and code generation.

### Temporary execution/product restrictions are explicit

Until compiler protocols acquire a .NET target identity, tasks run the .NET
compiler in-process. Incremental compilation, daemon/BTA routing, and compiler
reference-index generation remain disabled rather than masquerading as JVM or
metadata compilation.

When BTA support is added, the operation declares a real .NET target identity
and whether it uses the build session's reusable `ApplicationEnvironment`.
Selected assemblies, embedded-KLIB state, physical bindings, and target
compilation caches remain scoped to that build session; environment reuse does
not authorize process-global target state.

The operation model must describe the product that actually exists. JS and
Wasm distinguish reusable KLIB compilation from a later link operation and
test each argument partition and output root independently. Kotlin/.NET instead
produces or consumes one self-describing DLL containing both Kotlin KLIB and
CLR implementation. It must not invent a standalone KLIB/link operation or
route through JS/JVM operations to fit an existing BTA shape. If a later
application-link or export operation consumes a producer DLL, its input and
output roots are distinct and the producer artifact identity—not a repeated
display/module name—selects the input.

Unsafe common-source incremental compilation, if introduced, has a distinct
.NET property and defaults to disabled until task inputs and invalidation cover
source-set visibility, selected DLL/KLIB identity, friend authorization,
physical ABI metadata, and inline bodies. It never consumes a JVM, JS, or Wasm
switch merely because the shared Gradle machinery is similar.

Gradle-side dependency, target, and incremental caches use project-isolation-
safe, build-session-scoped services with stable structural keys. They do not
retain compiler IR, selected assembly objects, project instances from another
isolated project, or target state in static/process-global maps. Cross-project
coordination goes through the shared Build Service lifecycle rather than root-
project lookup or eager task realization.

Its snapshot must also retain logical expanded typealias dependencies from the
embedded KLIB wherever an alias changes the visible declaration shape. A JVM
classpath snapshot of CLR files is not a substitute: invalidation needs both
logical Kotlin ABI and producer-recorded physical CLR binding. Larger
snapshots and conservative recompilation are acceptable until a target-owned
diff proves a narrower rule safe.

BTA, daemon, and incremental buffering must preserve structured diagnostic
identities as well as rendered messages. A `.NET` operation may share the
repository's diagnostic transport, but it may not route through a collector
which erases diagnostic IDs or through a JVM operation merely to gain
incremental execution.

The initial target model is library-first. Executable and test-run products
must become explicit target products and may never make `netstandard2.0`
executable.

Until normal dependency publication includes profile-aware stdlib variants,
target integration may use compiler-owned bootstrap products in dedicated
fixtures. It must not silently filter an explicit user stdlib dependency.

These restrictions are temporary integration work, not permanent target
semantics.

## Rejected alternatives

### Handwritten arguments or options

Rejected. They duplicate shared defaults, freezing, metadata, and generation
and drift from mature targets.

### Expose every CLI flag as Gradle API

Rejected. It creates multiple owners for resolved target/task state and freezes
internal transports.

### Select the framework at task execution

Rejected. Dependency resolution and compiler output could select incompatible
profiles.

### Route through JVM/metadata daemon identities

Rejected. It misidentifies the compiler target. Native-style external
toolchain execution is also unnecessary because the .NET compiler is already a
Kotlin CLI compiler.

### Publish a KLIB beside the DLL

Rejected. The self-describing DLL is the atomic Kotlin/CLR artifact.

## Consequences

- Compiler and Gradle APIs follow shared generation and convention machinery.
- Public compiler options remain intentionally small and evolvable.
- Each target is profile-coherent before resolution or compiler invocation.
- One compilation owns one canonical self-describing artifact.
- Temporary in-process and library-first restrictions have named exit work
  rather than becoming accidental architecture.

## Freeze conditions and open work

Before declaring the integration production-ready, complete and validate:

- common partial-linkage diagnostic registration and second-stage behavior;
- profile-aware stdlib dependency publication;
- explicit application, test-run, and packaging models;
- daemon, Build Tools API, and incremental-compilation protocols with a real
  .NET target identity, session-scoped state, and complete invalidation inputs;
- configuration-cache-safe project and associated-compilation dependencies;
- exhaustive profile mapping and immutable target/task agreement; and
- a dedicated typed C# export DSL before exposing export selection publicly.

None may add a second owner for target framework, select KLIB separately from
its DLL, or expose operational compiler inputs as independently mutable user
options.
