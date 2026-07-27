# ADR: Generated KLIB-based compiler arguments for Kotlin/.NET

- Status: **Accepted**
- Date: 2026-07-27
- Scope: CLI argument identity and shared argument inheritance

## Context

The experimental .NET compiler currently declares `K2DotNetCompilerArguments` by hand. Since the
rebase, Kotlin's compiler-argument description is the source of truth for generated CLI classes,
serialized argument metadata, and Gradle compiler-option generation. Leaving .NET outside that
model would create a parallel compiler API before the target has shipped.

## 1. Other Kotlin targets

JVM, JS, Wasm, Native, and metadata arguments are declared in the shared
`compiler/arguments` DSL. The CLI argument generator emits their `K2*CompilerArguments` classes,
including freezing, copying, annotations, defaults, and inherited common arguments.

JS, Wasm, and Native are KLIB-producing targets. Their target-specific levels descend from
`CommonKlibBasedCompilerArguments` and use `CommonKlibBasedCompilerArgumentsConfigurator`.
Gradle compiler options are generated only for the deliberately selected public subset.

## 2. .NET platform differences

.NET needs target-specific arguments for:

- the `net48`, `netstandard2.0`, or `net10.0` target-framework profile;
- self-describing DLL library and standard-library products;
- producer-emitted `InternalsVisibleTo` authorization;
- CLR export facades; and
- a CLR assembly name and physical output.

Those are consequences of CLR metadata, target-framework compatibility, and .NET tooling. They
justify a .NET argument leaf; they do not justify handwritten freezing, copying, or common-option
machinery.

## 3. Kotlin Common invariant

Argument plumbing must not redefine language options or KLIB behavior. Kotlin/.NET inherits the
same common compiler and common KLIB arguments as other KLIB backends. Target-specific arguments
select physical output and interop policy only.

In particular, moving the class does not change Kotlin declaration identity, common source
semantics, expect/actual matching, or the meaning of any existing .NET flag.

## 4. .NET validity

The generated leaf preserves the existing CLR-specific flag spellings, repeatability, observable
absence semantics, and canonical target-framework values. Like other generated target argument
classes, absent repeatable string arguments normalize to empty arrays rather than nullable
arrays; the pre-shipping .NET configuration path already treats those states identically. Its
configurator descends from the common KLIB configurator because the target produces and consumes
KLIB metadata.

Unlike Native's separate library-compilation and binary-link stages, every current .NET product
emits a CLR assembly. Library and standard-library modes also serialize a KLIB into the assembly's
private `Kotlin.Metadata` resource, and the DLL links dependency IR into physical CLR metadata.
However, the .NET frontend has not yet registered
the common partial-linkage diagnostic names. Marking these invocations as a common second stage
therefore asks the configurator to set warning levels for diagnostics that do not exist and makes
every compilation fail. The generated argument model inherits the common partial-linkage options,
but its configurator reports `isSecondStage = false` until the frontend and linker implement that
diagnostic contract. This is a temporary isolation boundary, not a claim that CLR emission does no
linking.

The argument model does not claim that all three profiles emit identical CLR metadata. It merely
keeps their selection in one validated .NET argument namespace.

## 5. Alignment with compiler architecture

Declare `dotNetArguments` beside the other target levels, beneath
`commonKlibBasedArguments`. Generate `K2DotNetCompilerArguments` into `cli-base/gen`, include the
level in serialized compiler-argument metadata, and remove the handwritten duplicate.

Do not expose operational flags such as destination, product kind, friend authorization, or
classpath as public Gradle compiler options merely because they exist in the generated CLI model.
The built-in target and compilation tasks must own those values. A later generated
`KotlinDotNetCompilerOptions` surface may select only user-configurable options.

## 6. Core-team choice

The Kotlin-consistent choice is a generated KLIB-based .NET argument leaf with no parallel
handwritten representation.

Classifications:

- generated .NET argument declarations: **Correct direction**;
- inheriting common KLIB arguments and configurator behavior: **Correct direction**;
- retaining CLR-only flags in a target leaf: **Reasonable platform-specific divergence**;
- deferring common second-stage partial-linkage warning mapping until the .NET frontend registers
  the diagnostics: **Correct temporary implementation, but not a final design**;
- delaying the public Gradle compiler-option subset until task ownership is defined:
  **Correct temporary implementation, but not a final design**;
- continuing to hand-maintain `K2DotNetCompilerArguments`:
  **Architecturally wrong and should be changed**.

## Consequences

Compiler argument metadata, defaults, copying, freezing, and common KLIB inheritance now follow
the same source of truth as mature targets. The sibling generated-Gradle-options ADR selects
common options plus `moduleName` for the public compiler-options interface while keeping
operational inputs target-owned. Full common partial-linkage diagnostics remain a recorded
prerequisite before the backend may identify its CLR-emission invocations as common second-stage
compilations.
