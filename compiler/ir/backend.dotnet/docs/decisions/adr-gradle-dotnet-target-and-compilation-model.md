# ADR: Built-in Gradle target and compilation model for Kotlin/.NET

- Status: **Accepted**
- Date: 2026-07-27
- Scope: built-in Kotlin Multiplatform targets for `net48`, `netstandard2.0`, and
  `net10.0`

## Context

Kotlin/.NET has a distinct logical Kotlin platform identity and a separate target-framework
attribute, but no built-in Gradle target yet. The target must invoke the real .NET compiler,
preserve common multiplatform behavior, and publish the self-describing DLL from one compilation.

The target-framework choice must be immutable for a target and all of its compilations. Changing
the profile only at task execution would let dependency resolution select one profile while the
compiler emits another.

## 1. Other Kotlin targets

Kotlin/Native is the closest structural precedent:

- each Gradle target owns one immutable `KonanTarget`;
- a target-specific compilation factory creates compilations with target-specific options,
  dependency configurations, association, and task names;
- target and compilation side effects create default compilations, tasks, attributes, and
  artifacts; and
- Native adds separate host-availability, cinterop, cache, linker, executable, framework, and
  binary-container machinery.

JVM treats the target JVM version as a secondary physical compatibility attribute. JS and Wasm
use separate immutable target kinds where their runtime and compilation models differ. All retain
one authoritative target value while a compilation is configured.

## 2. .NET platform differences

A .NET target framework is an API/runtime compatibility profile, not a CPU/OS machine target:

- `net48` selects the established .NET Framework BCL and metadata constraints;
- `netstandard2.0` selects a portable API floor and permits libraries only; and
- `net10.0` selects the modern BCL and CLR capabilities.

The profiles can require different generated IL, notably for default interface methods, while
preserving the same Kotlin semantics. They do not require Native's host manager, cinterop model,
native linker, compilation caches, or per-architecture binary model.

A .NET library compilation writes one self-describing DLL. The DLL is simultaneously the CLR
runtime/tooling artifact and the Kotlin library dependency because it embeds the authoritative
KLIB payload. Gradle outgoing variants publish that DLL under the profile attribute and producer
identity.

## 3. Kotlin Common invariant

All profiles remain the same `KotlinPlatformType.dotnet` leaf platform. Common source-set
refinement, language and API settings, opt-ins, declaration identity, expect/actual behavior, and
Kotlin library dependency semantics must not vary with the physical CLR profile.

One target has one immutable target framework. Profile-specific source APIs use ordinary
multiplatform source-set structure or separate targets; a task-level switch must not reinterpret
the same resolved compilation.

The initial built-in compilation produces libraries on every profile. Executable and test-run
models are deferred until they can be represented as explicit products without making
`netstandard2.0` appear executable.

## 4. .NET validity

The exact target-framework attribute is applied to the target, its compile and runtime dependency
configurations, and its outgoing variants. Gradle may resolve `netstandard2.0` as the documented
fallback for `net48` or `net10.0`, but no task may override the selected profile.

The compile task passes:

- the immutable target framework;
- library product mode;
- the compilation-owned module name;
- self-describing DLL dependencies and friend paths;
- common compiler options, plugin options, and source structure; and
- one output directory containing the DLL and optional diagnostic IL.

The DLL is a declared compile-task output and the target's API and runtime artifact. This follows
KGP's normal task-output/provider model, so project dependency resolution retains producer task
ordering and configuration-cache safety without evaluating the compiler task eagerly. Associated
compilations use that same DLL as both dependency and friend input. The producer emits
`InternalsVisibleTo` from the associated compilation's module identity; the consumer does not use
an independent friend-artifact convention.

The current Kotlin daemon and Build Tools API do not define a .NET toolchain or daemon target
protocol. The first task therefore runs the compiler in-process, with incremental compilation,
the Build Tools API path, and compiler-reference-index generation disabled. Routing .NET through
the JVM or metadata daemon target would misidentify the compiler. Native-style external toolchain
execution is also inapplicable because the .NET compiler is already a Kotlin CLI compiler.

The compiler distribution already owns profile-specific Kotlin/.NET stdlib assemblies and a bootstrap
fallback, but the Gradle `kotlin-stdlib` module does not yet publish .NET variants. The compile-task
integration test therefore disables KGP's default Maven stdlib dependency and exercises the
compiler-owned bootstrap. This is not the final dependency model: silently filtering the ordinary
stdlib module from a .NET classpath would also hide explicit user dependencies. Profile-aware
stdlib Gradle variants must be published before the target is usable with default dependency
injection.

The Gradle task invokes the embeddable compiler distribution, not the repository's project
classpath. The distribution must therefore include the .NET FIR checker module in the same
frontend-module closure as the JVM, JS, Native, and Wasm checkers. KGP must not compensate for a
partial compiler distribution by adding compiler-internal modules to a user compilation
classpath.

## 5. Alignment with compiler architecture

Use the established KGP structure:

- a `KotlinOnlyTarget` with target-level generated compiler options;
- a `KotlinOnlyTargetPreset` selected by a typed public DSL entry point;
- a `KotlinCompilationImplFactory` with runtime dependency configurations;
- a decorated compilation with a typed compile-task provider;
- `KotlinCompilationProcessorSideEffect` and `KotlinSourceSetProcessor` for task creation;
- shared compiler-option convention propagation; and
- the ordinary Gradle compiler runner with a distinct .NET compiler class.

The public DSL is `dotnet(targetFramework, name, configure)`. Annual .NET profiles do not justify
one new DSL method per target framework. Multiple profiles are represented by multiple immutable
targets with explicit names.

## 6. Kotlin-aligned target choice

Adopt Native's immutable target -> compilation factory -> task architecture, but model a target
framework as a typed CLR profile rather than as a Native machine target. Use one profile-parameter
DSL instead of proliferating profile-named methods. Keep the first compilation library-only and
isolate the temporary in-process execution restriction.

Classifications:

- immutable profile-owned target and compilation:
  **Correct direction**;
- reuse of KGP target, compilation, option, task, and side-effect infrastructure:
  **Correct direction**;
- one typed `dotnet(profile, name, configure)` entry point:
  **Reasonable platform-specific divergence**;
- one self-describing library output owned by one compilation:
  **Correct direction**;
- one self-describing DLL outgoing artifact:
  **Reasonable platform-specific divergence**;
- shipping the .NET FIR checker in the embeddable compiler's frontend-module closure:
  **Correct direction**;
- library-only initial product model:
  **Correct temporary implementation, but not a final design**;
- forced in-process, non-incremental execution until compiler protocols gain .NET:
  **Correct temporary implementation, but not a final design**;
- explicitly disabling the default Maven stdlib in the compile-task integration fixture until
  profile-aware stdlib variants are published:
  **Correct temporary implementation, but not a final design**;
- importing Native host, cinterop, linker, cache, or architecture machinery:
  **Architecturally wrong and should be changed**;
- selecting the target framework through free compiler arguments or a mutable task property:
  **Architecturally wrong and should be changed**.

## Consequences

Each `.NET` Gradle target is profile-coherent before dependency resolution or compiler invocation.
The target follows mature Kotlin Gradle architecture without pretending that a target framework is
a machine architecture.

DLL-only publication and friend association are implemented. Functional model tests cover all
three profiles and a real-compiler integration lane compiles both an associated compilation and a
separate project dependency from the producer DLL while excluding the producer task. Executable products, test execution,
profile-aware stdlib Gradle variants, incremental compilation, daemon support, and Build Tools API
support remain separate work packages. None may introduce a second owner for the target framework
or select embedded Kotlin metadata independently from its containing DLL.
